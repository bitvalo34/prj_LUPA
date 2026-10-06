#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const [base, out, ...imageArgs] = process.argv.slice(2);
if (!base || !out || !process.env.A24_BROWSER) throw Error('Usage: A24_BROWSER=... node measure-visual.mjs URL OUTPUT [imageId ...]');
const images = imageArgs.length ? imageArgs : ['demo-grande', 'eval-93'];
const port = Number(process.env.A24_CDP_PORT || 9227);
const repeats = Number(process.env.A24_REPEATS || 5);
fs.mkdirSync(out, { recursive: true });
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'lupa-a24-'));
const log = fs.openSync(path.join(out, 'browser.log'), 'w');
const browser = spawn(process.env.A24_BROWSER, ['--headless=new','--disable-background-networking','--disable-component-update','--no-first-run','--disable-sync','--proxy-server=http://127.0.0.1:9','--proxy-bypass-list=localhost;127.0.0.1;[::1]','--disable-features=Translate,MediaRouter,OptimizationHints',`--remote-debugging-port=${port}`,`--user-data-dir=${profile}`,'about:blank'], {windowsHide:true, stdio:['ignore',log,log]});
let ws;
const calls = new Map(); let seq = 0;
const requests = [], external = [], errors = [];
const rows = [];
const sleep = ms => new Promise(r => setTimeout(r, ms));
const call = (method, params={}) => new Promise((resolve,reject) => {
  const id=++seq; const timer=setTimeout(()=>{calls.delete(id);reject(Error('CDP timeout '+method));},30000);
  calls.set(id,{resolve:r=>{clearTimeout(timer);resolve(r);},reject:e=>{clearTimeout(timer);reject(e);}});
  ws.send(JSON.stringify({id,method,params}));
});
const evaluate = async expression => {
  const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
  if(r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails));
  return r.result.value;
};
const wait = async (expression, label, timeout=30000) => {
  const deadline=Date.now()+timeout;
  while(Date.now()<deadline) {if(await evaluate(expression)) return;await sleep(50);}
  throw Error('Timeout '+label);
};
const button = (selector,text) => `Array.from(document.querySelectorAll(${JSON.stringify(selector)})).find(e=>e.textContent.trim()===${JSON.stringify(text)})`;
const click = async (selector,text) => evaluate(`(()=>{const b=${button(selector,text)};if(!b)throw Error('missing button');const t=performance.now();b.click();return t;})()`);
const stable = async () => {
  await wait(`window.a24 && document.querySelector('.status-lamp')?.dataset.phase==='observing' && a24.pending===0 && a24.events.some(e=>e.type==='DONE'&&e.epoch===a24.epoch)`, 'stable');
  await sleep(450); // excludes poll/settle time from measured timestamps
  return evaluate(`(()=>{const e=a24.epoch,ev=a24.events;return {observedAt:performance.now(),epoch:e,level:ev.filter(x=>x.type==='PLAN'&&x.epoch===e).at(-1)?.appliedLevel,done:ev.filter(x=>x.type==='DONE'&&x.epoch===e).at(-1),live:a24.live,peak:a24.peak,closes:a24.closes};})()`);
};
// Hook records preserve protocol type; _draw/_frame distinguish drawing from wire events.
try {
  let target;
  for(let i=0;i<150;i++){try{const t=await (await fetch(`http://127.0.0.1:${port}/json`)).json();target=t.find(x=>x.type==='page');if(target)break;}catch{}await sleep(100);}
  if(!target)throw Error('Browser debugger unavailable');
  ws=new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res,rej)=>{ws.onopen=res;ws.onerror=rej;});
  ws.onmessage = ({data}) => {
    const m=JSON.parse(data);
    if(m.id){const p=calls.get(m.id);if(p){calls.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);}return;}
    if(m.method==='Network.requestWillBeSent'||m.method==='Network.webSocketCreated'){
      const url=m.params.request?.url||m.params.url;
      requests.push(url);
      if(/^https?:|^wss?:/.test(url) && new URL(url).host!==new URL(base).host) external.push(url);
    }
    if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails);
  };
  await call('Page.enable');await call('Runtime.enable');await call('Network.enable');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  const probe=fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)),'visual-probe.js'),'utf8');
  await call('Page.addScriptToEvaluateOnNewDocument',{source:probe});
  const browserVersion=await call('Browser.getVersion');
  fs.writeFileSync(path.join(out,'environment.json'),JSON.stringify({date:new Date().toISOString(),browserVersion,os:os.version(),cpu:os.cpus()[0]?.model,logicalCpus:os.cpus().length,totalMemory:os.totalmem(),viewport:{width:1440,height:1000,dpr:1},base,images,repeats,cache:'HTTP disabled; Java LRU retained across runs; OS cache not cleared',headless:true,network:'External browser traffic routed through unavailable local proxy; loopback bypass. Host network not disabled.'},null,2));
  for (const image of images) for(let r=1;r<=repeats;r++) {
    console.log('A24',image,'repetition',r);
    await call('Network.setCacheDisabled',{cacheDisabled:true});
    await call('Page.navigate',{url:base});
    await wait(`window.a24 && Array.from(document.querySelectorAll('.cartridge')).some(e=>e.querySelector('strong')?.textContent.trim()===${JSON.stringify(image)})`,'catalog');
    const start=await evaluate(`(()=>{const b=Array.from(document.querySelectorAll('.cartridge')).find(e=>e.querySelector('strong')?.textContent.trim()===${JSON.stringify(image)});const t=performance.now();b.click();return t;})()`);
    const initial=await stable();
    await shot(`${image}-${r}-initial`);
    await click('.detail-selector button','-2');const low=await stable();
    const detailStart=await click('.detail-selector button','0');const high=await stable();
    await shot(`${image}-${r}-detail`);
    await click('.detail-selector button','-2');const reduced=await stable();
    await shot(`${image}-${r}-reduced`);
    await click('.navigation-group button','Lente');const focus=await stable();
    await shot(`${image}-${r}-focus`);
    const data=await evaluate('a24');
    const metrics = (state,t) => {
      // Each delivery counts only at its first draw; repainting retained z0 later
      // must never inflate initial-view latency. Bound observations to this step.
      const first = flag => { const seen=new Set();return data.events.filter(x=> {
        if(!x[flag]||x.epoch!==state.epoch||x.t<t||x.t>state.observedAt||seen.has(x.deliveryId))return false;
        seen.add(x.deliveryId);return true;
      }); };
      const draw=first('_draw');
      const frame=first('_frame');
      const ids=new Set(draw.map(x=>x.deliveryId));
      return {epoch:state.epoch,level:state.level,sent:state.done?.sentTiles,uniqueDrawn:ids.size,complete:ids.size===state.done?.sentTiles,firstDrawMs:draw.length?draw[0].t-t:null,completeDrawMs:draw.length?draw.at(-1).t-t:null,completeFrameProxyMs:frame.length?frame.at(-1).t-t:null};
    };
    const row={image,repetition:r,initial:metrics(initial,start),refinement:metrics(high,detailStart),lowLevel:low.level,reducedLevel:reduced.level,highLiveBytes:high.live,reducedLiveBytes:reduced.live,closedOnReduction:reduced.closes-high.closes,peakLiveBytes:data.peak,stale:data.stale,mixed:data.mixed,decodeFailures:data.failures,focusLevel:focus.level};
    row.pass=row.initial.complete&&row.refinement.complete&&high.level>low.level&&reduced.level<high.level&&row.closedOnReduction>0&&row.reducedLiveBytes<row.highLiveBytes&&data.stale===0&&data.mixed===0&&data.failures===0&&data.peak<=67108864;
    rows.push(row);
    fs.writeFileSync(path.join(out,`${image}-${r}-trace.json`),JSON.stringify(data,null,2));
    fs.writeFileSync(path.join(out,'measurements.json'),JSON.stringify(rows,null,2));
    if(!row.pass) throw Error('Visual acceptance failed '+JSON.stringify(row));
  }
  fs.writeFileSync(path.join(out,'network.json'),JSON.stringify({requests,external,errors},null,2));
  if(external.length||errors.length) throw Error('External requests or browser exceptions');
  const summary={status:'PASS',runs:rows.length,externalRequests:external.length,browserExceptions:errors.length,images:{}};
  const stat=v=>{const x=[...v].sort((a,b)=>a-b);return {n:x.length,median:x.length%2?x[(x.length-1)/2]:(x[x.length/2-1]+x[x.length/2])/2,p95:x[Math.ceil(x.length*.95)-1],max:x.at(-1)};};
  for(const image of images){const v=rows.filter(x=>x.image===image);summary.images[image]={initialDrawMs:stat(v.map(x=>x.initial.completeDrawMs)),initialFrameProxyMs:stat(v.map(x=>x.initial.completeFrameProxyMs)),refinementDrawMs:stat(v.map(x=>x.refinement.completeDrawMs)),refinementFrameProxyMs:stat(v.map(x=>x.refinement.completeFrameProxyMs)),peakLiveBytes:Math.max(...v.map(x=>x.peakLiveBytes))};}
  fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify(summary,null,2));
  console.log(JSON.stringify(summary,null,2));
} catch(e) {
  fs.writeFileSync(path.join(out,'failure.json'),JSON.stringify({error:String(e),rows,external,errors},null,2));throw e;
} finally {try{ws?.close();}catch{}browser.kill();fs.closeSync(log);}
async function shot(name){if(!name.includes('-1-'))return;const r=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(out,name+'.png'),Buffer.from(r.data,'base64'));}
