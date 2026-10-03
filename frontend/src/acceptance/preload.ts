type Primitive = string | number | boolean | null;
type Flat = Record<string, Primitive>;
type Event = Flat & { atMs: number; phase: string; type: string };

type TileMeta = {
  deliveryId: number;
  epoch: number;
  imageId: string;
  imageVersion: string;
  z: number;
  x: number;
  y: number;
  w: number;
  h: number;
  bytes: number;
};

type Counters = {
  liveBitmapBytes: number;
  peakLiveBitmapBytes: number;
  liveBitmaps: number;
  peakLiveBitmaps: number;
  pendingDecodeJobs: number;
  peakPendingDecodeJobs: number;
  staleDrawViolations: number;
  imageMixViolations: number;
  externalRequests: number;
};

type Probe = {
  setPhase(value: string): void;
  events(): Event[];
  counters(): Counters;
};

type Report = {
  mode: string;
  seed: number;
  startedAt: string;
  finishedAt: string;
  status: 'PASS' | 'FAIL';
  checks: Record<string, boolean>;
  summary: Flat;
  samples: Flat[];
  failures: string[];
};

const params = new URLSearchParams(window.location.search);
if (params.get('a23') === '1') installProbe();

function installProbe(): void {
  const start = performance.now();
  const events: Event[] = [];
  const bitmapMeta = new WeakMap<object, TileMeta>();
  const seen = new WeakSet<object>();
  const closed = new WeakSet<object>();
  const decodeJobs = new Set<string>();
  let phase = 'boot';
  let activeEpoch = 0;
  let activeImageId = '';
  let activeImageVersion = '';
  let liveBitmapBytes = 0;
  let peakLiveBitmapBytes = 0;
  let liveBitmaps = 0;
  let peakLiveBitmaps = 0;
  let peakPendingDecodeJobs = 0;
  let staleDrawViolations = 0;
  let imageMixViolations = 0;
  let externalRequests = 0;

  const record = (type: string, data: Flat = {}): void => {
    if (events.length >= 50000) events.shift();
    events.push({ atMs: Math.round((performance.now() - start) * 100) / 100, phase, type, ...data });
  };

  const nativeFetch = window.fetch.bind(window);
  window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof input === 'string' || input instanceof URL ? String(input) : input.url;
    const url = new URL(raw, window.location.href);
    const external = url.origin !== window.location.origin;
    if (external) externalRequests++;
    record('FETCH', { url: url.href, external });
    return nativeFetch(input, init);
  }) as typeof window.fetch;

  const NativeWebSocket = window.WebSocket;
  const WrappedWebSocket = function (url: string | URL, protocols?: string | string[]): WebSocket {
    const absolute = new URL(String(url), window.location.href);
    const ws = protocols === undefined
      ? new NativeWebSocket(absolute.href)
      : new NativeWebSocket(absolute.href, protocols);
    if (absolute.hostname !== window.location.hostname || absolute.port !== window.location.port) externalRequests++;
    record('WS_OPEN_REQUEST', { url: absolute.href });

    const send = ws.send.bind(ws);
    ws.send = ((data: string | ArrayBufferLike | Blob | ArrayBufferView) => {
      if (typeof data === 'string') {
        const control = parseJson(data);
        if (control) {
          if (control.type === 'VIEW') {
            activeEpoch = num(control, 'epoch');
            activeImageId = str(control, 'imageId');
            activeImageVersion = str(control, 'imageVersion');
          }
          record('WS_OUT_' + str(control, 'type'), controlSummary(control));
        }
      }
      send(data);
    }) as typeof ws.send;

    ws.addEventListener('message', (message: MessageEvent) => {
      if (typeof message.data === 'string') {
        const control = parseJson(message.data);
        if (!control) return;
        if (control.type === 'MANIFEST') {
          activeImageId = str(control, 'imageId');
          activeImageVersion = str(control, 'imageVersion');
        }
        record('WS_IN_' + str(control, 'type'), controlSummary(control));
      } else if (message.data instanceof ArrayBuffer) {
        const header = parseTile(message.data);
        if (header) record('WS_IN_TILE', tileSummary(header));
      }
    });
    return ws;
  } as unknown as typeof WebSocket;
  Object.setPrototypeOf(WrappedWebSocket, NativeWebSocket);
  (window as unknown as { WebSocket: typeof WebSocket }).WebSocket = WrappedWebSocket;

  const NativeWorker = window.Worker;
  const WrappedWorker = function (scriptURL: string | URL, options?: WorkerOptions): Worker {
    const worker = new NativeWorker(scriptURL, options);
    const postMessage = worker.postMessage.bind(worker);
    worker.postMessage = ((message: unknown, transferOrOptions?: Transferable[] | StructuredSerializeOptions) => {
      const value = obj(message);
      if (value?.type === 'decode') {
        const jobId = str(value, 'jobId');
        decodeJobs.add(jobId);
        peakPendingDecodeJobs = Math.max(peakPendingDecodeJobs, decodeJobs.size);
        record('WORKER_DECODE_POSTED', { jobId, epoch: num(value, 'epoch') });
      } else if (value?.type === 'invalidate') {
        record('WORKER_INVALIDATE', { minEpoch: num(value, 'minEpoch') });
      } else if (value?.type === 'reset') {
        record('WORKER_RESET', { connectionId: num(value, 'connectionId') });
      }
      if (transferOrOptions === undefined) postMessage(message);
      else postMessage(message, transferOrOptions as Transferable[]);
    }) as typeof worker.postMessage;

    worker.addEventListener('message', (message: MessageEvent) => {
      const value = obj(message.data);
      if (!value) return;
      const resultType = str(value, 'type');
      if (!['decoded', 'discarded', 'failed'].includes(resultType)) return;
      const jobId = str(value, 'jobId');
      decodeJobs.delete(jobId);
      record('WORKER_RESULT', {
        jobId,
        result: resultType,
        epoch: num(value, 'epoch'),
        deliveryId: num(value, 'deliveryId'),
        reason: str(value, 'reason')
      });
      const header = obj(value.header);
      if (resultType !== 'decoded' || !header || !value.bitmap || typeof value.bitmap !== 'object') return;
      const meta: TileMeta = {
        deliveryId: num(header, 'deliveryId'),
        epoch: num(header, 'epoch'),
        imageId: str(header, 'imageId'),
        imageVersion: str(header, 'imageVersion'),
        z: num(header, 'z'),
        x: num(header, 'x'),
        y: num(header, 'y'),
        w: num(header, 'w'),
        h: num(header, 'h'),
        bytes: num(header, 'w') * num(header, 'h') * 4
      };
      const bitmap = value.bitmap as object;
      bitmapMeta.set(bitmap, meta);
      if (!seen.has(bitmap)) {
        seen.add(bitmap);
        liveBitmaps++;
        liveBitmapBytes += meta.bytes;
        peakLiveBitmaps = Math.max(peakLiveBitmaps, liveBitmaps);
        peakLiveBitmapBytes = Math.max(peakLiveBitmapBytes, liveBitmapBytes);
      }
    });
    return worker;
  } as unknown as typeof Worker;
  Object.setPrototypeOf(WrappedWorker, NativeWorker);
  (window as unknown as { Worker: typeof Worker }).Worker = WrappedWorker;

  if (typeof ImageBitmap !== 'undefined') {
    const close = ImageBitmap.prototype.close;
    ImageBitmap.prototype.close = function (): void {
      const bitmap = this as unknown as object;
      const meta = bitmapMeta.get(bitmap);
      if (meta && !closed.has(bitmap)) {
        closed.add(bitmap);
        liveBitmaps = Math.max(0, liveBitmaps - 1);
        liveBitmapBytes = Math.max(0, liveBitmapBytes - meta.bytes);
        record('BITMAP_CLOSE', tileSummary(meta));
      }
      close.call(this);
    };
  }

  const drawImage = CanvasRenderingContext2D.prototype.drawImage;
  const wrappedDrawImage = function (this: CanvasRenderingContext2D, ...args: unknown[]): void {
    const source = args[0];
    const meta = source && typeof source === 'object' ? bitmapMeta.get(source) : undefined;
    if (meta) {
      const stale = meta.z !== 0 && activeEpoch > 0 && meta.epoch !== activeEpoch;
      const mixed = Boolean(activeImageId) && (
        meta.imageId !== activeImageId ||
        (Boolean(activeImageVersion) && meta.imageVersion !== activeImageVersion)
      );
      if (stale) staleDrawViolations++;
      if (mixed) imageMixViolations++;
      record('CANVAS_DRAW_TILE', { ...tileSummary(meta), activeEpoch, stale, mixed });
    }
    Reflect.apply(drawImage, this, args);
  };
  CanvasRenderingContext2D.prototype.drawImage = wrappedDrawImage as typeof CanvasRenderingContext2D.prototype.drawImage;

  const probe: Probe = {
    setPhase(value) { phase = value; record('PHASE', { value }); },
    events() { return events.map((event) => ({ ...event })); },
    counters() {
      return {
        liveBitmapBytes,
        peakLiveBitmapBytes,
        liveBitmaps,
        peakLiveBitmaps,
        pendingDecodeJobs: decodeJobs.size,
        peakPendingDecodeJobs,
        staleDrawViolations,
        imageMixViolations,
        externalRequests
      };
    }
  };

  window.addEventListener('DOMContentLoaded', () => {
    void runCampaign(probe).catch((error: unknown) => publishReport({
      mode: params.get('a23Mode') ?? 'full',
      seed: seed(),
      startedAt: new Date().toISOString(),
      finishedAt: new Date().toISOString(),
      status: 'FAIL', checks: {}, summary: {}, samples: [],
      failures: [error instanceof Error ? error.message : String(error)]
    }));
  }, { once: true });
}

async function runCampaign(probe: Probe): Promise<void> {
  const mode = params.get('a23Mode') ?? 'full';
  const startedAt = new Date().toISOString();
  const failures: string[] = [];
  const checks: Record<string, boolean> = {};
  const samples: Flat[] = [];

  await waitFor(() => document.querySelector('.status-lamp') !== null, 10000, 'UI LUPA no inició');
  await waitFor(() => document.querySelectorAll('.cartridge').length > 0, 20000, 'catálogo real sin cartuchos');
  const cartridges = Array.from(document.querySelectorAll<HTMLButtonElement>('.cartridge'));
  cartridges[0]!.click();
  await waitStable('imagen inicial', 20000);
  samples.push(sample('initial'));

  if (mode === 'capture-initial') return finish({ initialStable: true });
  if (mode === 'capture-detail') {
    clickDetail('0'); await waitStable('detalle 0', 20000); samples.push(sample('detail'));
    return finish({ detailStable: true });
  }
  if (mode === 'capture-reduced') {
    clickDetail('-2'); await waitStable('detalle -2', 20000); samples.push(sample('reduced'));
    return finish({ reducedStable: true });
  }
  if (mode === 'capture-focus') {
    clickText('.navigation-group button', 'Lente'); await setRadius(256); await waitStable('foco', 20000); samples.push(sample('focus'));
    return finish({ focusStable: true });
  }
  if (mode === 'late') {
    probe.setPhase('late-decode');
    const before = metricNumber('TILE recibidos');
    clickDetail('0'); dispatchKey(stage(), '+');
    await waitFor(() => metricNumber('TILE recibidos') > before, 10000, 'no llegó TILE para carrera tardía');
    await sleep(120); dispatchKey(stage(), 'ArrowRight'); await sleep(700); await waitStable('descarte tardío', 30000);
    const late = probe.events().filter((event) => event.phase === 'late-decode');
    checks.lateDecodeDiscarded = late.some((event) => event.type === 'WORKER_RESULT' && event.result === 'discarded' && String(event.reason).includes('después de decodificar'));
    checks.noStaleCanvasDraw = probe.counters().staleDrawViolations === 0;
    if (!checks.lateDecodeDiscarded) failures.push('No se observó descarte determinista después de decodificar');
    if (!checks.noStaleCanvasDraw) failures.push('Se detectó dibujo obsoleto');
    return finish();
  }

  probe.setPhase('view-changes');
  const random = mulberry32(seed());
  const actions = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', '+', '-'];
  for (let index = 0; index < 100; index++) {
    const action = actions[(Math.floor(random() * actions.length) + index) % actions.length]!;
    dispatchKey(stage(), action);
    await sleep(20);
    samples.push({ campaign: 'view', index: index + 1, action, epoch: metricNumber('Época') });
  }
  await waitStable('100 cambios de vista', 30000);
  const viewEvents = probe.events().filter((event) => event.phase === 'view-changes' && event.type === 'WS_OUT_VIEW');
  checks.viewInteractions100 = samples.filter((row) => row.campaign === 'view').length === 100;
  checks.viewConsolidationActive = viewEvents.length > 0 && viewEvents.length < 100;
  checks.viewRateLimited = minimumGap(viewEvents) >= 90;
  checks.viewFinalStable = phase() === 'observing';
  if (!checks.viewInteractions100) failures.push('No se registraron 100 interacciones');
  if (!checks.viewConsolidationActive) failures.push('No se demostró consolidación de VIEW');
  if (!checks.viewRateLimited) failures.push('VIEW superó el límite nominal de 10/s');
  if (!checks.viewFinalStable) failures.push('La vista final no estabilizó');
  samples.push(sample('after-100-views'));

  clickText('.zoom-controls button', 'Restablecer');
  await waitStable('restablecer antes de detalle', 20000);
  probe.setPhase('detail-cycles');
  clickDetail('-2'); await waitStable('detalle bajo inicial', 20000);
  let realLevelChanges = 0;
  for (let cycle = 1; cycle <= 100; cycle++) {
    const low = planLevel();
    const closesBefore = eventCount(probe.events(), 'BITMAP_CLOSE', 'detail-cycles');
    clickDetail('0'); await waitStable('detalle alto ' + cycle, 20000);
    const high = planLevel();
    clickDetail('-2'); await waitStable('detalle bajo ' + cycle, 20000);
    const reduced = planLevel();
    const closes = eventCount(probe.events(), 'BITMAP_CLOSE', 'detail-cycles') - closesBefore;
    if (high > low || high > reduced) realLevelChanges++;
    samples.push({ campaign: 'detail', cycle, lowLevel: low, highLevel: high, reducedLevel: reduced, closedBitmaps: closes, managedMiB: managedMiB() });
    if (managedMiB() > 64.05) { failures.push('Presupuesto administrado excedido en ciclo ' + cycle); break; }
  }
  const detailRows = samples.filter((row) => row.campaign === 'detail');
  checks.detailCycles100 = detailRows.length === 100;
  checks.detailRealLevelChange = realLevelChanges === 100;
  checks.detailClosesObserved = detailRows.every((row) => Number(row.closedBitmaps) > 0);
  checks.decodeQueueBounded = probe.counters().peakPendingDecodeJobs <= 34;
  checks.detailFinalStable = phase() === 'observing';
  if (!checks.detailCycles100) failures.push('No se completaron 100 ciclos de detalle');
  if (!checks.detailRealLevelChange) failures.push('No todos los ciclos cambiaron nivel efectivo');
  if (!checks.detailClosesObserved) failures.push('Algún ciclo no observó cierre de bitmaps');
  if (!checks.decodeQueueBounded) failures.push('Cola de decodificación excedió el límite');
  samples.push(sample('after-100-detail-cycles'));

  probe.setPhase('focus');
  clickText('.navigation-group button', 'Lente');
  await setRadius(32); await waitStable('foco mínimo', 20000); samples.push(sample('focus-center'));
  await setRadius(512); dispatchKey(stage(), 'ArrowRight'); dispatchKey(stage(), 'ArrowDown'); await waitStable('foco borde', 20000);
  for (let i = 0; i < 4; i++) { dispatchKey(stage(), 'ArrowRight'); dispatchKey(stage(), 'ArrowDown'); await sleep(110); }
  await waitStable('foco esquina', 20000); samples.push(sample('focus-corner'));
  clickText('.navigation-group button', 'Uniforme'); await waitStable('salir de foco', 20000);
  checks.focusPlansObserved = probe.events().some((event) => event.phase === 'focus' && event.type === 'WS_OUT_VIEW' && event.mode === 'focus');
  checks.focusRadius512Observed = probe.events().some((event) => event.phase === 'focus' && event.type === 'WS_OUT_VIEW' && String(event.focus).includes('512'));
  if (!checks.focusPlansObserved) failures.push('No se observaron VIEW de foco');
  if (!checks.focusRadius512Observed) failures.push('No se observó radio 512');

  if (cartridges.length >= 2) {
    probe.setPhase('image-change');
    clickDetail('0'); dispatchKey(stage(), '+'); await sleep(25); cartridges[1]!.click();
    await waitStable('cambio de imagen', 30000);
    checks.secondImageSelected = readout('IMAGEN ACTIVA') === cartridgeId(cartridges[1]!);
    if (!checks.secondImageSelected) failures.push('No quedó activa la segunda imagen');
    samples.push(sample('second-image'));
  }

  probe.setPhase('errors');
  clickText('.header-actions button', 'Reconectar');
  await waitFor(() => metricNumber('Conexión') >= 2, 10000, 'reconexión no incrementó conexión');
  cartridges[0]!.click(); await waitStable('recuperación', 30000);
  checks.reconnectRecovered = phase() === 'observing';
  if (!checks.reconnectRecovered) failures.push('No se recuperó tras reconectar');
  samples.push(sample('after-reconnect'));
  await finish();

  async function finish(extra: Record<string, boolean> = {}): Promise<void> {
    Object.assign(checks, extra);
    probe.setPhase('finished');
    const counters = probe.counters();
    checks.noStaleCanvasDraw = counters.staleDrawViolations === 0;
    checks.noImageMix = counters.imageMixViolations === 0;
    checks.noExternalRequests = counters.externalRequests === 0;
    checks.bitmapBudgetBounded = counters.peakLiveBitmapBytes <= 64 * 1024 * 1024;
    if (!checks.noStaleCanvasDraw) failures.push('Se dibujaron entregas obsoletas');
    if (!checks.noImageMix) failures.push('Se mezclaron imagen o versión');
    if (!checks.noExternalRequests) failures.push('Se observaron solicitudes externas del runtime');
    if (!checks.bitmapBudgetBounded) failures.push('Bitmaps vivos superaron 64 MiB');
    const report: Report = {
      mode,
      seed: seed(),
      startedAt,
      finishedAt: new Date().toISOString(),
      status: failures.length === 0 && Object.values(checks).every(Boolean) ? 'PASS' : 'FAIL',
      checks,
      summary: {
        interactions: samples.filter((row) => row.campaign === 'view').length,
        detailCycles: samples.filter((row) => row.campaign === 'detail').length,
        wsViews: probe.events().filter((event) => event.type === 'WS_OUT_VIEW').length,
        receivedTiles: metricNumber('TILE recibidos'),
        drawnTiles: metricNumber('Dibujados'),
        discardedTiles: metricNumber('Descartados'),
        failedTiles: metricNumber('Fallidos'),
        releases: metricNumber('RELEASE'),
        peakLiveBitmapBytes: counters.peakLiveBitmapBytes,
        peakLiveBitmaps: counters.peakLiveBitmaps,
        peakPendingDecodeJobs: counters.peakPendingDecodeJobs,
        staleDrawViolations: counters.staleDrawViolations,
        imageMixViolations: counters.imageMixViolations,
        externalRequests: counters.externalRequests,
        finalPhase: phase(),
        finalImage: readout('IMAGEN ACTIVA'),
        finalVersion: readout('VERSIÓN')
      },
      samples,
      failures: Array.from(new Set(failures))
    };
    publishReport(report);
  }
}

function stage(): HTMLElement { return required<HTMLElement>('.screen-stage'); }
function dispatchKey(target: HTMLElement, key: string): void { target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })); }

function clickDetail(value: '-2' | '-1' | '0'): void {
  const button = Array.from(document.querySelectorAll<HTMLButtonElement>('.detail-selector button')).find((item) => item.textContent?.trim() === value);
  if (!button) throw new Error('No se encontró detalle ' + value);
  button.click();
}

function clickText(selector: string, text: string): void {
  const button = Array.from(document.querySelectorAll<HTMLButtonElement>(selector)).find((item) => item.textContent?.trim() === text);
  if (!button) throw new Error('No se encontró botón ' + text);
  button.click();
}

async function setRadius(value: number): Promise<void> {
  await waitFor(() => document.querySelector<HTMLInputElement>('.focus-radius input') !== null, 2000, 'No apareció el control de radio de foco');
  const input = document.querySelector<HTMLInputElement>('.focus-radius input');
  if (!input) throw new Error('No se encontró radio de foco');
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, String(value));
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
}

async function waitStable(label: string, timeoutMs: number): Promise<void> {
  await waitFor(() => phase() === 'observing' && metricNumber('Decodificando') === 0 && metricNumber('Por pintar') === 0, timeoutMs, label + ' no estabilizó');
  await sleep(80);
}

async function waitFor(predicate: () => boolean, timeoutMs: number, message: string): Promise<void> {
  const deadline = performance.now() + timeoutMs;
  while (performance.now() < deadline) { if (predicate()) return; await sleep(25); }
  throw new Error(message);
}

function sleep(ms: number): Promise<void> { return new Promise((resolve) => window.setTimeout(resolve, ms)); }

function sample(label: string): Flat {
  return {
    label,
    phase: phase(),
    connection: metricNumber('Conexión'),
    epoch: metricNumber('Época'),
    mode: metricText('Modo'),
    detail: metricText('Detalle'),
    zoom: metricText('Zoom'),
    received: metricNumber('TILE recibidos'),
    drawn: metricNumber('Dibujados'),
    discarded: metricNumber('Descartados'),
    failed: metricNumber('Fallidos'),
    releases: metricNumber('RELEASE'),
    managedMiB: managedMiB(),
    planLevel: planLevel(),
    image: readout('IMAGEN ACTIVA'),
    version: readout('VERSIÓN'),
    canvasHash: canvasHash()
  };
}

function metricText(label: string): string {
  for (const row of Array.from(document.querySelectorAll<HTMLElement>('.metric'))) {
    if (row.querySelector('span')?.textContent?.trim() === label) return row.querySelector('strong')?.textContent?.trim() ?? '';
  }
  return '';
}
function metricNumber(label: string): number { const value = Number(metricText(label).replace(/[^0-9.-]/g, '')); return Number.isFinite(value) ? value : 0; }
function managedMiB(): number {
  const match = metricText('Bitmaps LUPA').match(/([0-9.]+)\s*(B|KiB|MiB)/i);
  if (!match) return 0;
  const value = Number(match[1] ?? '0'); const unit = (match[2] ?? '').toLowerCase();
  return unit === 'mib' ? value : unit === 'kib' ? value / 1024 : value / (1024 * 1024);
}
function planLevel(): number { const match = readout('NIVEL').match(/z(\d+)/i); return match ? Number(match[1] ?? '-1') : -1; }
function readout(label: string): string {
  for (const row of Array.from(document.querySelectorAll<HTMLElement>('.readout'))) {
    if (row.querySelector('small')?.textContent?.trim() === label) return row.querySelector('strong')?.textContent?.trim() ?? '';
  }
  return '';
}
function phase(): string { return document.querySelector<HTMLElement>('.status-lamp')?.dataset.phase ?? ''; }
function cartridgeId(button: HTMLButtonElement): string { return button.querySelector('strong')?.textContent?.trim() ?? ''; }
function required<T extends Element>(selector: string): T { const value = document.querySelector<T>(selector); if (!value) throw new Error('No se encontró ' + selector); return value; }
function eventCount(events: Event[], type: string, targetPhase: string): number { return events.filter((event) => event.type === type && event.phase === targetPhase).length; }
function minimumGap(events: Event[]): number {
  if (events.length < 2) return Number.POSITIVE_INFINITY;
  let minimum = Number.POSITIVE_INFINITY;
  for (let index = 1; index < events.length; index++) minimum = Math.min(minimum, events[index]!.atMs - events[index - 1]!.atMs);
  return minimum;
}

function canvasHash(): string {
  const canvas = document.querySelector<HTMLCanvasElement>('.screen-stage canvas');
  const context = canvas?.getContext('2d');
  if (!canvas || !context || canvas.width < 1 || canvas.height < 1) return 'none';
  let hash = 2166136261 >>> 0;
  for (let index = 0; index < 64; index++) {
    const x = Math.min(canvas.width - 1, Math.floor(((index % 8) + 0.5) * canvas.width / 8));
    const y = Math.min(canvas.height - 1, Math.floor((Math.floor(index / 8) + 0.5) * canvas.height / 8));
    for (const byte of context.getImageData(x, y, 1, 1).data) { hash ^= byte; hash = Math.imul(hash, 16777619) >>> 0; }
  }
  return hash.toString(16).padStart(8, '0');
}

function publishReport(report: Report): void {
  let output = document.getElementById('a23-report') as HTMLPreElement | null;
  if (!output) {
    output = document.createElement('pre'); output.id = 'a23-report';
    Object.assign(output.style, { position: 'fixed', left: '0', bottom: '0', zIndex: '2147483647', maxWidth: '40vw', maxHeight: '35vh', overflow: 'auto', background: 'rgba(0,0,0,.88)', color: '#d7ffd7', font: '11px monospace', padding: '8px' });
    document.body.appendChild(output);
  }
  output.dataset.status = report.status; output.textContent = JSON.stringify(report);
  document.documentElement.dataset.a23Status = report.status;
}

function parseJson(text: string): Record<string, unknown> | null { try { return obj(JSON.parse(text) as unknown); } catch { return null; } }
function parseTile(buffer: ArrayBuffer): Record<string, unknown> | null {
  if (buffer.byteLength < 4) return null;
  const bytes = new DataView(buffer).getUint32(0, false);
  if (bytes < 2 || bytes > 4096 || 4 + bytes > buffer.byteLength) return null;
  return parseJson(new TextDecoder().decode(new Uint8Array(buffer, 4, bytes)));
}
function controlSummary(control: Record<string, unknown>): Flat {
  const summary: Flat = { controlType: str(control, 'type') };
  for (const key of ['epoch', 'imageId', 'imageVersion', 'deliveryId', 'appliedLevel', 'contextLevel', 'detailOffset', 'mode', 'sentTiles']) {
    const value = control[key]; if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') summary[key] = value;
  }
  const focus = obj(control.focus); if (focus) summary.focus = [num(focus, 'x'), num(focus, 'y'), num(focus, 'radiusPx')].join(',');
  return summary;
}
function tileSummary(value: Record<string, unknown> | TileMeta): Flat {
  const record = value as unknown as Record<string, unknown>;
  return { deliveryId: num(record, 'deliveryId'), epoch: num(record, 'epoch'), imageId: str(record, 'imageId'), imageVersion: str(record, 'imageVersion'), z: num(record, 'z'), x: num(record, 'x'), y: num(record, 'y'), w: num(record, 'w'), h: num(record, 'h') };
}
function obj(value: unknown): Record<string, unknown> | null { return typeof value === 'object' && value !== null ? value as Record<string, unknown> : null; }
function str(value: Record<string, unknown>, key: string): string { return typeof value[key] === 'string' ? value[key] as string : ''; }
function num(value: Record<string, unknown>, key: string): number { const candidate = value[key]; return typeof candidate === 'number' && Number.isFinite(candidate) ? candidate : 0; }
function seed(): number { const candidate = Number(params.get('seed') ?? '23001717'); return Number.isSafeInteger(candidate) ? candidate : 23001717; }
function mulberry32(initial: number): () => number {
  let state = initial >>> 0;
  return () => { state += 0x6D2B79F5; let value = state; value = Math.imul(value ^ value >>> 15, value | 1); value ^= value + Math.imul(value ^ value >>> 7, value | 61); return ((value ^ value >>> 14) >>> 0) / 4294967296; };
}
