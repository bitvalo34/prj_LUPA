type Primitive = string | number | boolean | null;
type Flat = Record<string, Primitive>;
type ProbeEvent = Flat & { atMs: number; phase: string; type: string };

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
  peakUiDecoding: number;
  staleDrawViolations: number;
  imageMixViolations: number;
  externalRequests: number;
};

type Probe = {
  setPhase(value: string): void;
  observeUi(): void;
  events(): ProbeEvent[];
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
  const started = performance.now();
  const events: ProbeEvent[] = [];
  const bitmapMeta = new WeakMap<object, TileMeta>();
  const seenBitmaps = new WeakSet<object>();
  const closedBitmaps = new WeakSet<object>();
  const decodeJobs = new Set<string>();

  let probePhase = 'boot';
  let activeEpoch = 0;
  let activeImageId = '';
  let activeImageVersion = '';
  let liveBitmapBytes = 0;
  let peakLiveBitmapBytes = 0;
  let liveBitmaps = 0;
  let peakLiveBitmaps = 0;
  let peakPendingDecodeJobs = 0;
  let peakUiDecoding = 0;
  let staleDrawViolations = 0;
  let imageMixViolations = 0;
  let externalRequests = 0;

  const record = (type: string, data: Flat = {}): void => {
    if (events.length >= 50000) events.shift();
    events.push({
      atMs: Math.round((performance.now() - started) * 100) / 100,
      phase: probePhase,
      type,
      ...data
    });
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

    if (absolute.hostname !== window.location.hostname || absolute.port !== window.location.port) {
      externalRequests++;
    }
    record('WS_OPEN_REQUEST', { url: absolute.href });

    const nativeSend = ws.send.bind(ws);
    ws.send = ((data: string | ArrayBufferLike | Blob | ArrayBufferView) => {
      if (typeof data === 'string') {
        const control = parseJson(data);
        if (control) {
          if (control.type === 'VIEW') {
            activeEpoch = numberField(control, 'epoch');
            activeImageId = stringField(control, 'imageId');
            activeImageVersion = stringField(control, 'imageVersion');
          }
          record('WS_OUT_' + stringField(control, 'type'), controlSummary(control));
        }
      }
      nativeSend(data);
    }) as typeof ws.send;

    ws.addEventListener('message', (message: MessageEvent) => {
      if (typeof message.data === 'string') {
        const control = parseJson(message.data);
        if (!control) return;
        if (control.type === 'MANIFEST') {
          activeImageId = stringField(control, 'imageId');
          activeImageVersion = stringField(control, 'imageVersion');
        }
        record('WS_IN_' + stringField(control, 'type'), controlSummary(control));
        return;
      }
      if (message.data instanceof ArrayBuffer) {
        const header = parseTileHeader(message.data);
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
    const nativePostMessage = worker.postMessage.bind(worker);

    worker.postMessage = ((message: unknown, transferOrOptions?: Transferable[] | StructuredSerializeOptions) => {
      const value = asRecord(message);
      if (value?.type === 'decode') {
        const jobId = stringField(value, 'jobId');
        decodeJobs.add(jobId);
        peakPendingDecodeJobs = Math.max(peakPendingDecodeJobs, decodeJobs.size);
        record('WORKER_DECODE_POSTED', { jobId, epoch: numberField(value, 'epoch') });
      } else if (value?.type === 'invalidate') {
        record('WORKER_INVALIDATE', { minEpoch: numberField(value, 'minEpoch') });
      } else if (value?.type === 'reset') {
        record('WORKER_RESET', { connectionId: numberField(value, 'connectionId') });
      }

      if (transferOrOptions === undefined) nativePostMessage(message);
      else nativePostMessage(message, transferOrOptions as Transferable[]);
    }) as typeof worker.postMessage;

    worker.addEventListener('message', (message: MessageEvent) => {
      const value = asRecord(message.data);
      if (!value) return;
      const result = stringField(value, 'type');
      if (!['decoded', 'discarded', 'failed'].includes(result)) return;

      const jobId = stringField(value, 'jobId');
      decodeJobs.delete(jobId);
      record('WORKER_RESULT', {
        jobId,
        result,
        epoch: numberField(value, 'epoch'),
        deliveryId: numberField(value, 'deliveryId'),
        reason: stringField(value, 'reason')
      });

      const header = asRecord(value.header);
      if (result !== 'decoded' || !header || !value.bitmap || typeof value.bitmap !== 'object') return;

      const meta: TileMeta = {
        deliveryId: numberField(header, 'deliveryId'),
        epoch: numberField(header, 'epoch'),
        imageId: stringField(header, 'imageId'),
        imageVersion: stringField(header, 'imageVersion'),
        z: numberField(header, 'z'),
        x: numberField(header, 'x'),
        y: numberField(header, 'y'),
        w: numberField(header, 'w'),
        h: numberField(header, 'h'),
        bytes: numberField(header, 'w') * numberField(header, 'h') * 4
      };
      const bitmap = value.bitmap as object;
      bitmapMeta.set(bitmap, meta);
      if (!seenBitmaps.has(bitmap)) {
        seenBitmaps.add(bitmap);
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
    const nativeClose = ImageBitmap.prototype.close;
    ImageBitmap.prototype.close = function (): void {
      const bitmap = this as unknown as object;
      const meta = bitmapMeta.get(bitmap);
      if (meta && !closedBitmaps.has(bitmap)) {
        closedBitmaps.add(bitmap);
        liveBitmaps = Math.max(0, liveBitmaps - 1);
        liveBitmapBytes = Math.max(0, liveBitmapBytes - meta.bytes);
        record('BITMAP_CLOSE', tileSummary(meta));
      }
      nativeClose.call(this);
    };
  }

  const nativeDrawImage = CanvasRenderingContext2D.prototype.drawImage;
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
    Reflect.apply(nativeDrawImage, this, args);
  };
  CanvasRenderingContext2D.prototype.drawImage = wrappedDrawImage as typeof CanvasRenderingContext2D.prototype.drawImage;

  const probe: Probe = {
    setPhase(value) {
      probePhase = value;
      record('PHASE', { value });
    },
    observeUi() {
      peakUiDecoding = Math.max(peakUiDecoding, metricNumber('Decodificando'));
    },
    events() {
      return events.map((event) => ({ ...event }));
    },
    counters() {
      return {
        liveBitmapBytes,
        peakLiveBitmapBytes,
        liveBitmaps,
        peakLiveBitmaps,
        pendingDecodeJobs: decodeJobs.size,
        peakPendingDecodeJobs,
        peakUiDecoding,
        staleDrawViolations,
        imageMixViolations,
        externalRequests
      };
    }
  };

  window.addEventListener('DOMContentLoaded', () => {
    void runCampaign(probe).catch((error: unknown) => publishReport({
      mode: params.get('a23Mode') ?? 'full',
      seed: readSeed(),
      startedAt: new Date().toISOString(),
      finishedAt: new Date().toISOString(),
      status: 'FAIL',
      checks: {},
      summary: {},
      samples: [],
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

  await waitFor(() => document.querySelector('.status-lamp') !== null, 10000, 'UI LUPA no inició', probe);
  await waitFor(() => document.querySelectorAll('.cartridge').length > 0, 20000, 'catálogo real sin cartuchos', probe);
  const cartridges = Array.from(document.querySelectorAll<HTMLButtonElement>('.cartridge'));

  cartridges[0]!.click();
  await waitStable('imagen inicial', 20000, probe);
  samples.push(sampleUi('initial'));

  if (mode === 'capture-initial') return finish({ initialStable: true });
  if (mode === 'capture-detail') {
    clickDetail('0');
    await waitStable('detalle 0', 20000, probe);
    samples.push(sampleUi('detail'));
    return finish({ detailStable: true });
  }
  if (mode === 'capture-reduced') {
    clickDetail('-2');
    await waitStable('detalle -2', 20000, probe);
    samples.push(sampleUi('reduced'));
    return finish({ reducedStable: true });
  }
  if (mode === 'capture-focus') {
    clickButtonByText('.navigation-group button', 'Lente');
    await setFocusRadius(256, probe);
    await waitStable('foco', 20000, probe);
    samples.push(sampleUi('focus'));
    return finish({ focusStable: true });
  }
  if (mode === 'late') {
    probe.setPhase('late-decode');
    const before = metricNumber('TILE recibidos');
    clickDetail('0');
    dispatchKey(screenStage(), '+');
    await waitFor(() => metricNumber('TILE recibidos') > before, 10000, 'no llegó TILE para carrera tardía', probe);
    await sleep(120);
    dispatchKey(screenStage(), 'ArrowRight');
    await sleep(700);
    await waitStable('descarte tardío', 30000, probe);

    const lateEvents = probe.events().filter((event) => event.phase === 'late-decode');
    checks.lateDecodeDiscarded = lateEvents.some((event) =>
      event.type === 'WORKER_RESULT' &&
      event.result === 'discarded' &&
      String(event.reason).includes('después de decodificar')
    );
    checks.noStaleCanvasDraw = probe.counters().staleDrawViolations === 0;
    if (!checks.lateDecodeDiscarded) failures.push('No se observó descarte determinista después de decodificar');
    if (!checks.noStaleCanvasDraw) failures.push('Se detectó dibujo obsoleto');
    return finish();
  }

  probe.setPhase('view-changes');
  const random = mulberry32(readSeed());
  const actions = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', '+', '-'];
  for (let index = 0; index < 100; index++) {
    const action = actions[(Math.floor(random() * actions.length) + index) % actions.length]!;
    dispatchKey(screenStage(), action);
    await sleep(20);
    probe.observeUi();
    samples.push({ campaign: 'view', index: index + 1, action, epoch: metricNumber('Época') });
  }
  await waitStable('100 cambios de vista', 30000, probe);

  const viewEvents = probe.events().filter((event) => event.phase === 'view-changes' && event.type === 'WS_OUT_VIEW');
  checks.viewInteractions100 = samples.filter((row) => row.campaign === 'view').length === 100;
  checks.viewConsolidationActive = viewEvents.length > 0 && viewEvents.length < 100;
  checks.viewRateLimited = minimumEventGap(viewEvents) >= 90;
  checks.viewFinalStable = uiPhase() === 'observing';
  if (!checks.viewInteractions100) failures.push('No se registraron 100 interacciones');
  if (!checks.viewConsolidationActive) failures.push('No se demostró consolidación de VIEW');
  if (!checks.viewRateLimited) failures.push('VIEW superó el límite nominal de 10/s');
  if (!checks.viewFinalStable) failures.push('La vista final no estabilizó después del refinamiento');
  samples.push(sampleUi('after-100-views'));

  clickButtonByText('.zoom-controls button', 'Restablecer');
  await waitStable('restablecer antes de detalle', 20000, probe);

  probe.setPhase('detail-cycles');
  clickDetail('-2');
  await waitStable('detalle bajo inicial', 20000, probe);
  let realLevelChanges = 0;

  for (let cycle = 1; cycle <= 100; cycle++) {
    const lowLevel = planLevel();
    const closesBefore = eventCount(probe.events(), 'BITMAP_CLOSE', 'detail-cycles');

    clickDetail('0');
    await waitStable('detalle alto ' + cycle, 20000, probe);
    const highLevel = planLevel();

    clickDetail('-2');
    await waitStable('detalle bajo ' + cycle, 20000, probe);
    const reducedLevel = planLevel();
    const closes = eventCount(probe.events(), 'BITMAP_CLOSE', 'detail-cycles') - closesBefore;

    if (highLevel > lowLevel || highLevel > reducedLevel) realLevelChanges++;
    samples.push({
      campaign: 'detail',
      cycle,
      lowLevel,
      highLevel,
      reducedLevel,
      closedBitmaps: closes,
      managedMiB: managedMiB()
    });

    if (managedMiB() > 64.05) {
      failures.push('Presupuesto administrado excedido en ciclo ' + cycle);
      break;
    }
  }

  const detailRows = samples.filter((row) => row.campaign === 'detail');
  checks.detailCycles100 = detailRows.length === 100;
  checks.detailRealLevelChange = realLevelChanges === 100;
  checks.detailClosesObserved = detailRows.every((row) => Number(row.closedBitmaps) > 0);
  checks.activeDecodesBounded = probe.counters().peakUiDecoding <= 2;
  checks.detailFinalStable = uiPhase() === 'observing';
  if (!checks.detailCycles100) failures.push('No se completaron 100 ciclos de detalle');
  if (!checks.detailRealLevelChange) failures.push('No todos los ciclos cambiaron nivel efectivo');
  if (!checks.detailClosesObserved) failures.push('Algún ciclo no observó cierre de bitmaps');
  if (!checks.activeDecodesBounded) failures.push('Se observaron más de dos decodificaciones activas');
  if (!checks.detailFinalStable) failures.push('La campaña de detalle terminó antes de completar el refinamiento');
  samples.push(sampleUi('after-100-detail-cycles'));

  probe.setPhase('focus');
  clickButtonByText('.navigation-group button', 'Lente');
  await setFocusRadius(32, probe);
  await waitStable('foco mínimo', 20000, probe);
  samples.push(sampleUi('focus-center'));

  await setFocusRadius(512, probe);
  dispatchKey(screenStage(), 'ArrowRight');
  dispatchKey(screenStage(), 'ArrowDown');
  await waitStable('foco borde', 20000, probe);
  for (let index = 0; index < 4; index++) {
    dispatchKey(screenStage(), 'ArrowRight');
    dispatchKey(screenStage(), 'ArrowDown');
    await sleep(110);
  }
  await waitStable('foco esquina', 20000, probe);
  samples.push(sampleUi('focus-corner'));

  clickButtonByText('.navigation-group button', 'Uniforme');
  await waitStable('salir de foco', 20000, probe);
  checks.focusPlansObserved = probe.events().some((event) =>
    event.phase === 'focus' && event.type === 'WS_OUT_VIEW' && event.mode === 'focus'
  );
  checks.focusRadius512Observed = probe.events().some((event) =>
    event.phase === 'focus' && event.type === 'WS_OUT_VIEW' && String(event.focus).includes('512')
  );
  if (!checks.focusPlansObserved) failures.push('No se observaron VIEW de foco');
  if (!checks.focusRadius512Observed) failures.push('No se observó radio 512');

  if (cartridges.length >= 2) {
    probe.setPhase('image-change');
    clickDetail('0');
    dispatchKey(screenStage(), '+');
    await sleep(25);
    cartridges[1]!.click();
    await waitStable('cambio de imagen', 30000, probe);
    checks.secondImageSelected = readReadout('IMAGEN ACTIVA') === cartridgeId(cartridges[1]!);
    if (!checks.secondImageSelected) failures.push('No quedó activa la segunda imagen');
    samples.push(sampleUi('second-image'));
  }

  probe.setPhase('errors');
  clickButtonByText('.header-actions button', 'Reconectar');
  await waitFor(() => metricNumber('Conexión') >= 2, 10000, 'reconexión no incrementó conexión', probe);
  cartridges[0]!.click();
  await waitStable('recuperación', 30000, probe);
  checks.reconnectRecovered = uiPhase() === 'observing';
  if (!checks.reconnectRecovered) failures.push('No se recuperó tras reconectar');
  samples.push(sampleUi('after-reconnect'));

  await finish();

  async function finish(extra: Record<string, boolean> = {}): Promise<void> {
    Object.assign(checks, extra);
    probe.setPhase('finished');
    probe.observeUi();
    const counters = probe.counters();

    checks.noStaleCanvasDraw = counters.staleDrawViolations === 0;
    checks.noImageMix = counters.imageMixViolations === 0;
    checks.noExternalRequests = counters.externalRequests === 0;
    checks.bitmapBudgetBounded = counters.peakLiveBitmapBytes <= 64 * 1024 * 1024;
    checks.activeDecodesBounded = counters.peakUiDecoding <= 2;

    if (!checks.noStaleCanvasDraw) failures.push('Se dibujaron entregas obsoletas');
    if (!checks.noImageMix) failures.push('Se mezclaron imagen o versión');
    if (!checks.noExternalRequests) failures.push('Se observaron solicitudes externas del runtime');
    if (!checks.bitmapBudgetBounded) failures.push('Bitmaps vivos superaron 64 MiB');
    if (!checks.activeDecodesBounded) failures.push('Se observaron más de dos decodificaciones activas');

    publishReport({
      mode,
      seed: readSeed(),
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
        peakUiDecoding: counters.peakUiDecoding,
        staleDrawViolations: counters.staleDrawViolations,
        imageMixViolations: counters.imageMixViolations,
        externalRequests: counters.externalRequests,
        finalPhase: uiPhase(),
        finalImage: readReadout('IMAGEN ACTIVA'),
        finalVersion: readReadout('VERSIÓN')
      },
      samples,
      failures: Array.from(new Set(failures))
    });
  }
}

function screenStage(): HTMLElement {
  return requiredElement<HTMLElement>('.screen-stage');
}

function dispatchKey(target: HTMLElement, key: string): void {
  target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
}

function clickDetail(value: '-2' | '-1' | '0'): void {
  const button = Array.from(document.querySelectorAll<HTMLButtonElement>('.detail-selector button'))
    .find((candidate) => candidate.textContent?.trim() === value);
  if (!button) throw new Error('No se encontró detalle ' + value);
  button.click();
}

function clickButtonByText(selector: string, text: string): void {
  const button = Array.from(document.querySelectorAll<HTMLButtonElement>(selector))
    .find((candidate) => candidate.textContent?.trim() === text);
  if (!button) throw new Error('No se encontró botón ' + text);
  button.click();
}

async function setFocusRadius(value: number, probe: Probe): Promise<void> {
  await waitFor(
    () => document.querySelector<HTMLInputElement>('.focus-radius input') !== null,
    2000,
    'No apareció el control de radio de foco',
    probe
  );
  const input = document.querySelector<HTMLInputElement>('.focus-radius input');
  if (!input) throw new Error('No se encontró radio de foco');
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, String(value));
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
}

async function waitStable(label: string, timeoutMs: number, probe: Probe): Promise<void> {
  const deadline = performance.now() + timeoutMs;

  while (performance.now() < deadline) {
    probe.observeUi();
    const ready = uiPhase() === 'observing' && metricNumber('Decodificando') === 0 && metricNumber('Por pintar') === 0;
    if (!ready) {
      await sleep(25);
      continue;
    }

    // LUPA deliberately schedules refinement about 200 ms after a stable view.
    // A single observing sample can therefore be the pre-refinement transition.
    // Require a full quiet window beyond that timer, with the same epoch, before
    // accepting the view as stable. This preserves the product's refinement
    // behavior instead of disabling it or retrying a failed campaign.
    const epoch = metricNumber('Época');
    await sleep(350);
    probe.observeUi();
    if (
      uiPhase() === 'observing' &&
      metricNumber('Decodificando') === 0 &&
      metricNumber('Por pintar') === 0 &&
      metricNumber('Época') === epoch
    ) {
      return;
    }
  }

  throw new Error(label + ' no estabilizó después del refinamiento');
}

async function waitFor(
  predicate: () => boolean,
  timeoutMs: number,
  message: string,
  probe?: Probe
): Promise<void> {
  const deadline = performance.now() + timeoutMs;
  while (performance.now() < deadline) {
    probe?.observeUi();
    if (predicate()) return;
    await sleep(25);
  }
  throw new Error(message);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function sampleUi(label: string): Flat {
  return {
    label,
    phase: uiPhase(),
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
    image: readReadout('IMAGEN ACTIVA'),
    version: readReadout('VERSIÓN'),
    canvasHash: canvasHash()
  };
}

function metricText(label: string): string {
  for (const row of Array.from(document.querySelectorAll<HTMLElement>('.metric'))) {
    if (row.querySelector('span')?.textContent?.trim() === label) {
      return row.querySelector('strong')?.textContent?.trim() ?? '';
    }
  }
  return '';
}

function metricNumber(label: string): number {
  const value = Number(metricText(label).replace(/[^0-9.-]/g, ''));
  return Number.isFinite(value) ? value : 0;
}

function managedMiB(): number {
  const match = metricText('Bitmaps LUPA').match(/([0-9.]+)\s*(B|KiB|MiB)/i);
  if (!match) return 0;
  const value = Number(match[1] ?? '0');
  const unit = (match[2] ?? '').toLowerCase();
  return unit === 'mib' ? value : unit === 'kib' ? value / 1024 : value / (1024 * 1024);
}

function planLevel(): number {
  const match = readReadout('NIVEL').match(/z(\d+)/i);
  return match ? Number(match[1] ?? '-1') : -1;
}

function readReadout(label: string): string {
  for (const row of Array.from(document.querySelectorAll<HTMLElement>('.readout'))) {
    if (row.querySelector('small')?.textContent?.trim() === label) {
      return row.querySelector('strong')?.textContent?.trim() ?? '';
    }
  }
  return '';
}

function uiPhase(): string {
  return document.querySelector<HTMLElement>('.status-lamp')?.dataset.phase ?? '';
}

function cartridgeId(button: HTMLButtonElement): string {
  return button.querySelector('strong')?.textContent?.trim() ?? '';
}

function requiredElement<T extends Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) throw new Error('No se encontró ' + selector);
  return element;
}

function eventCount(events: ProbeEvent[], type: string, phase: string): number {
  return events.filter((event) => event.type === type && event.phase === phase).length;
}

function minimumEventGap(events: ProbeEvent[]): number {
  if (events.length < 2) return Number.POSITIVE_INFINITY;
  let minimum = Number.POSITIVE_INFINITY;
  for (let index = 1; index < events.length; index++) {
    minimum = Math.min(minimum, events[index]!.atMs - events[index - 1]!.atMs);
  }
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
    for (const byte of context.getImageData(x, y, 1, 1).data) {
      hash ^= byte;
      hash = Math.imul(hash, 16777619) >>> 0;
    }
  }
  return hash.toString(16).padStart(8, '0');
}

function publishReport(report: Report): void {
  let output = document.getElementById('a23-report') as HTMLPreElement | null;
  if (!output) {
    output = document.createElement('pre');
    output.id = 'a23-report';
    Object.assign(output.style, {
      position: 'fixed',
      left: '0',
      bottom: '0',
      zIndex: '2147483647',
      maxWidth: '40vw',
      maxHeight: '35vh',
      overflow: 'auto',
      background: 'rgba(0,0,0,.88)',
      color: '#d7ffd7',
      font: '11px monospace',
      padding: '8px'
    });
    document.body.appendChild(output);
  }
  output.dataset.status = report.status;
  output.textContent = JSON.stringify(report);
  document.documentElement.dataset.a23Status = report.status;
}

function parseJson(text: string): Record<string, unknown> | null {
  try {
    return asRecord(JSON.parse(text) as unknown);
  } catch {
    return null;
  }
}

function parseTileHeader(buffer: ArrayBuffer): Record<string, unknown> | null {
  if (buffer.byteLength < 4) return null;
  const headerBytes = new DataView(buffer).getUint32(0, false);
  if (headerBytes < 2 || headerBytes > 4096 || 4 + headerBytes > buffer.byteLength) return null;
  return parseJson(new TextDecoder().decode(new Uint8Array(buffer, 4, headerBytes)));
}

function controlSummary(control: Record<string, unknown>): Flat {
  const summary: Flat = { controlType: stringField(control, 'type') };
  for (const key of [
    'epoch', 'imageId', 'imageVersion', 'deliveryId', 'appliedLevel',
    'contextLevel', 'detailOffset', 'mode', 'sentTiles'
  ]) {
    const value = control[key];
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
      summary[key] = value;
    }
  }
  const focus = asRecord(control.focus);
  if (focus) {
    summary.focus = [
      numberField(focus, 'x'),
      numberField(focus, 'y'),
      numberField(focus, 'radiusPx')
    ].join(',');
  }
  return summary;
}

function tileSummary(value: Record<string, unknown> | TileMeta): Flat {
  const record = value as unknown as Record<string, unknown>;
  return {
    deliveryId: numberField(record, 'deliveryId'),
    epoch: numberField(record, 'epoch'),
    imageId: stringField(record, 'imageId'),
    imageVersion: stringField(record, 'imageVersion'),
    z: numberField(record, 'z'),
    x: numberField(record, 'x'),
    y: numberField(record, 'y'),
    w: numberField(record, 'w'),
    h: numberField(record, 'h')
  };
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : null;
}

function stringField(value: Record<string, unknown>, key: string): string {
  return typeof value[key] === 'string' ? value[key] as string : '';
}

function numberField(value: Record<string, unknown>, key: string): number {
  const candidate = value[key];
  return typeof candidate === 'number' && Number.isFinite(candidate) ? candidate : 0;
}

function readSeed(): number {
  const candidate = Number(params.get('seed') ?? '23001717');
  return Number.isSafeInteger(candidate) ? candidate : 23001717;
}

function mulberry32(initial: number): () => number {
  let state = initial >>> 0;
  return () => {
    state += 0x6D2B79F5;
    let value = state;
    value = Math.imul(value ^ value >>> 15, value | 1);
    value ^= value + Math.imul(value ^ value >>> 7, value | 61);
    return ((value ^ value >>> 14) >>> 0) / 4294967296;
  };
}
