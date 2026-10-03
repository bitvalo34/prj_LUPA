#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';

const [, , baseUrl, outputDir, ...modesArg] = process.argv;
if (!baseUrl || !outputDir) {
  console.error('Usage: node scripts/a23/cdp-runner.mjs http://127.0.0.1:PORT OUTPUT_DIR [mode ...]');
  process.exit(2);
}

const modes = modesArg.length > 0
  ? modesArg
  : ['full', 'late', 'capture-initial', 'capture-detail', 'capture-reduced', 'capture-focus'];
const debugPort = Number(process.env.A23_CDP_PORT ?? '9223');
const seed = Number(process.env.A23_SEED ?? '23001717');
const browser = process.env.A23_BROWSER ?? 'google-chrome';
const decodeDelay = Number(process.env.A23_DECODE_DELAY_MS ?? '500');

fs.mkdirSync(outputDir, { recursive: true });
const browserLog = fs.openSync(path.join(outputDir, 'browser.log'), 'w');

const { spawn } = await import('node:child_process');
const chrome = spawn(browser, [
  '--headless=new',
  '--no-sandbox',
  '--disable-dev-shm-usage',
  '--disable-background-networking',
  '--disable-component-update',
  '--disable-default-apps',
  '--disable-features=Translate,MediaRouter,OptimizationHints',
  '--disable-sync',
  '--metrics-recording-only',
  '--no-first-run',
  '--password-store=basic',
  '--use-mock-keychain',
  `--remote-debugging-port=${debugPort}`,
  `--user-data-dir=${path.join(outputDir, 'chrome-profile')}`,
  'about:blank'
], { stdio: ['ignore', browserLog, browserLog] });

let cdp;
try {
  const wsUrl = await waitForDebugger(debugPort, 15000);
  cdp = await connectCdp(wsUrl);
  await cdp.call('Page.enable');
  await cdp.call('Runtime.enable');
  await cdp.call('Network.enable');
  await cdp.call('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 1000,
    deviceScaleFactor: 1,
    mobile: false
  });

  const metadata = {
    generatedAt: new Date().toISOString(),
    browser,
    seed,
    viewport: { width: 1440, height: 1000, dpr: 1 },
    baseUrl,
    modes: []
  };

  for (const mode of modes) {
    const params = new URLSearchParams({
      a23: '1',
      a23Mode: mode,
      seed: String(seed)
    });
    if (mode === 'late') params.set('i21DecodeDelayMs', String(decodeDelay));
    const url = `${baseUrl.replace(/\/$/, '')}/?${params}`;
    console.log(`A23 browser mode=${mode} url=${url}`);
    await cdp.call('Page.navigate', { url });
    await waitForDocumentReady(cdp, 20000);
    const report = await waitForReport(cdp, mode === 'full' ? 480000 : 120000);
    fs.writeFileSync(path.join(outputDir, `${mode}.json`), JSON.stringify(report, null, 2));
    metadata.modes.push({ mode, status: report.status, failures: report.failures ?? [] });

    if (mode.startsWith('capture-') || mode === 'late' || mode === 'full') {
      const shot = await cdp.call('Page.captureScreenshot', {
        format: 'png',
        captureBeyondViewport: false,
        fromSurface: true
      });
      fs.writeFileSync(path.join(outputDir, `${mode}.png`), Buffer.from(shot.data, 'base64'));
    }

    if (report.status !== 'PASS') {
      console.error(`A23 ${mode} FAIL`, report.failures ?? []);
      metadata.status = 'FAIL';
      fs.writeFileSync(path.join(outputDir, 'metadata.json'), JSON.stringify(metadata, null, 2));
      process.exitCode = 1;
      break;
    }
  }

  if (!metadata.status) metadata.status = 'PASS';
  fs.writeFileSync(path.join(outputDir, 'metadata.json'), JSON.stringify(metadata, null, 2));
} finally {
  try { cdp?.close(); } catch {}
  chrome.kill('SIGTERM');
  await new Promise((resolve) => {
    const timer = setTimeout(resolve, 3000);
    chrome.once('exit', () => { clearTimeout(timer); resolve(); });
  });
  fs.closeSync(browserLog);
}

async function waitForDebugger(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json`);
      if (response.ok) {
        const targets = await response.json();
        const page = targets.find((target) => target.type === 'page');
        if (page?.webSocketDebuggerUrl) return page.webSocketDebuggerUrl;
      }
    } catch {}
    await sleep(100);
  }
  throw new Error('Chrome DevTools Protocol no quedó disponible');
}

function connectCdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  let sequence = 0;
  const pending = new Map();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timeout conectando CDP')), 10000);
    ws.addEventListener('open', () => {
      clearTimeout(timer);
      resolve({
        call(method, params = {}) {
          const id = ++sequence;
          return new Promise((resolveCall, rejectCall) => {
            pending.set(id, { resolve: resolveCall, reject: rejectCall });
            ws.send(JSON.stringify({ id, method, params }));
          });
        },
        close() { ws.close(); }
      });
    });
    ws.addEventListener('error', () => reject(new Error('Error CDP WebSocket')));
    ws.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (!message.id) return;
      const operation = pending.get(message.id);
      if (!operation) return;
      pending.delete(message.id);
      if (message.error) operation.reject(new Error(`${message.error.code}: ${message.error.message}`));
      else operation.resolve(message.result ?? {});
    });
  });
}

async function waitForDocumentReady(cdp, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = await evaluate(cdp, 'document.readyState');
    if (value === 'complete') return;
    await sleep(50);
  }
  throw new Error('La página no terminó de cargar');
}

async function waitForReport(cdp, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const raw = await evaluate(cdp, `document.getElementById('a23-report')?.textContent || ''`);
    if (raw) return JSON.parse(raw);
    await sleep(100);
  }
  const diagnostics = await evaluate(cdp, `({
    status: document.documentElement.dataset.a23Status || '',
    phase: document.querySelector('.status-lamp')?.dataset.phase || '',
    error: document.querySelector('.screen-error')?.textContent || '',
    report: document.getElementById('a23-report')?.textContent || ''
  })`);
  throw new Error(`Timeout esperando reporte A23: ${JSON.stringify(diagnostics)}`);
}

async function evaluate(cdp, expression) {
  const result = await cdp.call('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true
  });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.text || 'Runtime.evaluate falló');
  }
  return result.result?.value;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
