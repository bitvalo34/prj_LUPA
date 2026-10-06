// Test-only instrumentation injected before the application by CDP.
// Records primitives, never retains bitmap/payload references.
(() => {
  const p = window.a24 = { events: [], live: 0, peak: 0, closes: 0, stale: 0, mixed: 0, pending: 0, failures: 0, epoch: 0, image: '', version: '' };
  const meta = new WeakMap(), closed = new WeakSet();
  const rec = (type, data = {}) => p.events.push({ t: performance.now(), type, ...data });
  const WS = window.WebSocket;
  window.WebSocket = class extends WS {
    constructor(...args) {
      super(...args);
      this.addEventListener('message', e => {
        if (typeof e.data !== 'string') return;
        const m = JSON.parse(e.data);
        rec('IN', m);
      });
    }
    send(data) {
      if (typeof data === 'string') {
        const m = JSON.parse(data);
        if (m.type === 'VIEW') { p.epoch = m.epoch; p.image = m.imageId; p.version = m.imageVersion; }
        rec('OUT', m);
      }
      return super.send(data);
    }
  };
  const WorkerNative = window.Worker;
  window.Worker = class extends WorkerNative {
    constructor(...args) {
      super(...args);
      this.addEventListener('message', e => {
        const m = e.data;
        if (['decoded','discarded','failed'].includes(m.type)) p.pending--;
        if (m.type === 'failed') p.failures++;
        if (m.type === 'decoded') {
          meta.set(m.bitmap, m.header);
          p.live += 4 * m.header.w * m.header.h;
          p.peak = Math.max(p.peak, p.live);
        }
      });
    }
    postMessage(m, ...args) {
      if (m.type === 'decode') p.pending++;
      return super.postMessage(m, ...args);
    }
  };
  const draw = CanvasRenderingContext2D.prototype.drawImage;
  CanvasRenderingContext2D.prototype.drawImage = function(...args) {
    const result = Reflect.apply(draw, this, args);
    const m = meta.get(args[0]);
    if (m) {
      if (m.z !== 0 && m.epoch !== p.epoch) p.stale++;
      if (m.imageId !== p.image || m.imageVersion !== p.version) p.mixed++;
      rec('DRAW', {...m,_draw:true});
      // Double rAF is a rendering opportunity proxy, not physical display latency.
      requestAnimationFrame(() => requestAnimationFrame(() => rec('FRAME', {...m,_frame:true})));
    }
    return result;
  };
  const close = ImageBitmap.prototype.close;
  ImageBitmap.prototype.close = function() {
    const m = meta.get(this);
    if (m && !closed.has(this)) { closed.add(this); p.live -= 4*m.w*m.h; p.closes++; rec('CLOSE',m); }
    return close.call(this);
  };
})();
