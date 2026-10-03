type Primitive = string | number | boolean | null;
type EventData = Record<string, Primitive>;

type A23Event = EventData & {
  atMs: number;
  phase: string;
  type: string;
};

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

type CampaignReport = {
  mode: string;
  seed: number;
  startedAt: string;
  finishedAt: string;
  status: 'PASS' | 'FAIL';
  checks: Record<string, boolean>;
  summary: Record<string, Primitive>;
  samples: Array<Record<string, Primitive>>;
  failures: string[];
};

const params = new URLSearchParams(window.location.search);
const enabled = params.get('a23') === '1';

if (enabled) {
  installA23Probe();
}

function installA23Probe(): void {
  const startedAt = performance.now();
  const events: A23Event[] = [];
  const bitmapMeta = new WeakMap<object, TileMeta>();
  const seenBitmaps = new WeakSet<object>();
  const closedBitmaps = new WeakSet<object>();
  const decodeJobs = new Map<string, { epoch: number; bytes: number }>();
  let phase = 'boot';
  let liveBitmapBytes = 0;
  let peakLiveBitmapBytes = 0;
  let liveBitmaps = 0;
  let peakLiveBitmaps = 0;
  let peakPendingDecodeJobs = 0;
  let activeEpoch = 0;
  let activeImageId = '';
  let activeImageVersion = '';
  let staleDrawViolations = 0;
  let imageMixViolations = 0;
  let externalRequests = 0;

  const record = (type: string, data: EventData = {}): void => {
    if (events.length >= 50000) events.shift();
    events.push({ atMs: roundMs(performance.now() - startedAt), phase, type, ...data });
  };

  const nativeFetch = window.fetch.bind(window);
  window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof input === 'string' || input instanceof URL ? String(input) : input.url;
    const url = new URL(raw, window.location.href);
    if (url.origin !== window.location.origin) externalRequests++;
    record('FETCH', { url: url.href, external: url.origin !== window.location.origin });
    return nativeFetch(input, init);
  }) as typeof window.fetch;

  const NativeWebSocket = window.WebSocket;
  const WrappedWebSocket = function (
    this: WebSocket,
    url: string | URL,
    protocols?: string | string[]
  ): WebSocket {
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
        const control = parseControl(data);
        if (control) {
          if (control.type === 'VIEW') {
            activeEpoch = numberField(control, 'epoch');
            activeImageId = stringField(control, 'imageId');
            activeImageVersion = stringField(control, 'imageVersion');
          }
          record('WS_OUT_' + String(control.type ?? 'UNKNOWN'), controlSummary(control));
        } else {
          record('WS_OUT_TEXT', { bytes: data.length });
        }
      }
      nativeSend(data);
    }) as typeof ws.send;

    ws.addEventListener('message', (event: MessageEvent) => {
      if (typeof event.data === 'string') {
        const control = parseControl(event.data);
        if (control) {
          if (control.type === 'MANIFEST') {
            activeImageId = stringField(control, 'imageId');
            activeImageVersion = stringField(control, 'imageVersion');
          }
          record('WS_IN_' + String(control.type ?? 'UNKNOWN'), controlSummary(control));
        }
        return;
      }
      if (event.data instanceof ArrayBuffer) {
        const header = parseTileHeader(event.data);
        if (header) record('WS_IN_TILE', tileSummary(header));
        return;
      }
      if (event.data instanceof Blob) {
        void event.data.arrayBuffer().then((buffer) => {
          const header = parseTileHeader(buffer);
          if (header) record('WS_IN_TILE', tileSummary(header));
        });
      }
    });
    return ws;
  } as unknown as typeof WebSocket;
  Object.setPrototypeOf(WrappedWebSocket, NativeWebSocket);
  WrappedWebSocket.prototype = NativeWebSocket.prototype;
  (window as unknown as { WebSocket: typeof WebSocket }).WebSocket = WrappedWebSocket;

  const NativeWorker = window.Worker;
  const WrappedWorker = function (
    this: Worker,
    scriptURL: string | URL,
    options?: WorkerOptions
  ): Worker {
    const worker = new NativeWorker(scriptURL, options);
    const nativePostMessage = worker.postMessage.bind(worker);

    worker.postMessage = ((message: unknown, transferOrOptions?: Transferable[] | StructuredSerializeOptions) => {
      const value = asRecord(message);
      if (value?.type === 'decode') {
        const jobId = stringField(value, 'jobId');
        const header = asRecord(value.header);
        const epoch = numberField(value, 'epoch');
        const bytes = header ? numberField(header, 'w') * numberField(header, 'h') * 4 : 0;
        decodeJobs.set(jobId, { epoch, bytes });
        peakPendingDecodeJobs = Math.max(peakPendingDecodeJobs, decodeJobs.size);
        record('WORKER_DECODE_POSTED', {
          jobId,
          epoch,
          deliveryId: header ? numberField(header, 'deliveryId') : 0,
          bytes
        });
      } else if (value?.type === 'invalidate') {
        record('WORKER_INVALIDATE', {
          connectionId: numberField(value, 'connectionId'),
          minEpoch: numberField(value, 'minEpoch')
        });
      } else if (value?.type === 'reset') {
        record('WORKER_RESET', { connectionId: numberField(value, 'connectionId') });
      }

      if (transferOrOptions === undefined) nativePostMessage(message);
      else nativePostMessage(message, transferOrOptions as Transferable[]);
    }) as typeof worker.postMessage;

    worker.addEventListener('message', (event: MessageEvent) => {
      const value = asRecord(event.data);
      if (!value) return;
      const type = stringField(value, 'type');
      if (type !== 'decoded' && type !== 'discarded' && type !== 'failed') return;
      const jobId = stringField(value, 'jobId');
      decodeJobs.delete(jobId);
      const header = asRecord(value.header);
      const responseData: EventData = {
        jobId,
        result: type,
        epoch: numberField(value, 'epoch'),
        deliveryId: numberField(value, 'deliveryId'),
        reason: stringField(value, 'reason')
      };
      if (header) {
        responseData.z = numberField(header, 'z');
        responseData.imageId = stringField(header, 'imageId');
        responseData.imageVersion = stringField(header, 'imageVersion');
      }
      record('WORKER_RESULT', responseData);

      if (type === 'decoded' && value.bitmap && typeof value.bitmap === 'object' && header) {
        const bitmap = value.bitmap as object;
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
        bitmapMeta.set(bitmap, meta);
        if (!seenBitmaps.has(bitmap)) {
          seenBitmaps.add(bitmap);
          liveBitmaps++;
          liveBitmapBytes += meta.bytes;
          peakLiveBitmaps = Math.max(peakLiveBitmaps, liveBitmaps);
          peakLiveBitmapBytes = Math.max(peakLiveBitmapBytes, liveBitmapBytes);
        }
      }
    });
    return worker;
  } as unknown as typeof Worker;
  Object.setPrototypeOf(WrappedWorker, NativeWorker);
  WrappedWorker.prototype = NativeWorker.prototype;
  (window as unknown as { Worker: typeof Worker }).Worker = WrappedWorker;

  if (typeof ImageBitmap !== 'undefined') {
    const nativeClose = ImageBitmap.prototype.close;
    ImageBitmap.prototype.close = function (): void {
      const key = this as unknown as object;
      const meta = bitmapMeta.get(key);
      if (meta && !closedBitmaps.has(key)) {
        closedBitmaps.add(key);
        liveBitmaps = Math.max(0, liveBitmaps - 1);
        liveBitmapBytes = Math.max(0, liveBitmapBytes - meta.bytes);
        record('BITMAP_CLOSE', tileSummary(meta));
      }
      nativeClose.call(this);
    };
  }

  const nativeDrawImage = CanvasRenderingContext2D.prototype.drawImage;
  CanvasRenderingContext2D.prototype.drawImage = function (...args: Parameters<CanvasRenderingContext2D['drawImage']>): void {
    const source = args[0] as unknown as object;
    const meta = bitmapMeta.get(source);
    if (meta) {
      const stale = meta.z !== 0 && activeEpoch > 0 && meta.epoch !== activeEpoch;
      const mixed = Boolean(activeImageId) && (
        meta.imageId !== activeImageId ||
        (Boolean(activeImageVersion) && meta.imageVersion !== activeImageVersion)
      );
      if (stale) staleDrawViolations++;
      if (mixed) imageMixViolations++;
      record('CANVAS_DRAW_TILE', {
        ...tileSummary(meta),
        activeEpoch,
        activeImageId,
        activeImageVersion,
        stale,
        mixed
      });
    }
    nativeDrawImage.apply(this, args as Parameters<CanvasRenderingContext2D['drawImage']>);
  };

  const probe = {
    setPhase(value: string): void {
      phase = value;
      record('PHASE', { value });
    },
    events(): A23Event[] {
      return events.map((entry) => ({ ...entry }));
    },
    counters(): Record<string, number> {
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
  (window as unknown as { __LUPA_A23__?: typeof probe }).__LUPA_A23__ = probe;

  window.addEventListener('DOMContentLoaded', () => {
    void runRequestedCampaign(probe).catch((error: unknown) => {
      publishReport({
        mode: params.get('a23Mode') ?? 'full',
        seed: readSeed(),
        startedAt: new Date().toISOString(),
        finishedAt: new Date().toISOString(),
        status: 'FAIL',
        checks: {},
        summary: {},
        samples: [],
        failures: [error instanceof Error ? error.message : String(error)]
      });
    });
  }, { once: true });
}

async function runRequestedCampaign(probe: {
  setPhase(value: string): void;
  events(): A23Event[];
  counters(): Record<string, number>;
}): Promise<void> {
  const mode = params.get('a23Mode') ?? 'full';
  const seed = readSeed();
  const startedAt = new Date().toISOString();
  const failures: string[] = [];
  const checks: Record<string, boolean> = {};
  const samples: Array<Record<string, Primitive>> = [];

  await waitFor(() => document.querySelector('.status-lamp') !== null, 10000, 'UI LUPA no inició');
  await waitFor(() => document.querySelectorAll<HTMLButtonElement>('.cartridge').length > 0, 20000, 'catálogo real sin cartuchos');

  const cartridges = Array.from(document.querySelectorAll<HTMLButtonElement>('.cartridge'));
  if (cartridges.length < 1) throw new Error('A23 requiere al menos una imagen publicada');
  cartridges[0]!.click();
  await waitStable('imagen inicial', 20000);
  samples.push(sampleUi('initial'));

  if (mode === 'capture-initial') {
    checks.initialStable = true;
    return finish();
  }

  if (mode === 'late') {
    probe.setPhase('late-decode');
    await provokeLateDecode();
    const lateEvents = probe.events().filter((event) => event.phase === 'late-decode');
    checks.lateDecodeDiscarded = lateEvents.some((event) =>
      event.type === 'WORKER_RESULT' &&
      event.result === 'discarded' &&
      String(event.reason).includes('después de decodificar')
    );
    checks.noStaleCanvasDraw = probe.counters().staleDrawViolations === 0;
    if (!checks.lateDecodeDiscarded) failures.push('No se observó descarte determinista después de decodificar');
    if (!checks.noStaleCanvasDraw) failures.push('Se detectó dibujo de TILE obsoleta');
    return finish();
  }

  if (mode === 'capture-detail') {
    probe.setPhase('capture-detail');
    clickDetail('0');
    await waitStable('detalle 0', 20000);
    samples.push(sampleUi('detail'));
    return finish();
  }

  if (mode === 'capture-reduced') {
    probe.setPhase('capture-reduced');
    clickDetail('-2');
    await waitStable('detalle -2', 20000);
    samples.push(sampleUi('reduced'));
    return finish();
  }

  if (mode === 'capture-focus') {
    probe.setPhase('capture-focus');
    clickButtonByText('.navigation-group button', 'Lente');
    setFocusRadius(256);
    await waitStable('foco', 20000);
    samples.push(sampleUi('focus'));
    return finish();
  }

  if (mode === 'full' || mode === 'views') {
    probe.setPhase('view-changes');
    const rng = mulberry32(seed);
    const stage = requiredElement<HTMLElement>('.screen-stage');
    for (let index = 0; index < 100; index++) {
      const action = chooseNavigationAction(rng(), index);
      const beforeEpoch = readMetricNumber('Época');
      dispatchNavigation(stage, action);
      await sleep(20);
      samples.push({
        campaign: 'view',
        index: index + 1,
        action,
        atMs: Math.round(performance.now()),
        beforeEpoch,
        observedEpoch: readMetricNumber('Época')
      });
    }
    await waitStable('100 cambios de vista', 30000);
    const viewEvents = probe.events().filter((event) => event.phase === 'view-changes' && event.type === 'WS_OUT_VIEW');
    checks.viewInteractions100 = samples.filter((sample) => sample.campaign === 'view').length === 100;
    checks.viewConsolidationActive = viewEvents.length > 0 && viewEvents.length < 100;
    checks.viewRateLimited = minimumEventGap(viewEvents) >= 90;
    checks.viewFinalStable = readPhase() === 'observing';
    checks.noStaleCanvasDraw = probe.counters().staleDrawViolations === 0;
    checks.noImageMix = probe.counters().imageMixViolations === 0;
    if (!checks.viewInteractions100) failures.push('No se registraron 100 interacciones de vista');
    if (!checks.viewConsolidationActive) failures.push('La consolidación de VIEW no quedó demostrada');
    if (!checks.viewRateLimited) failures.push('Se observaron VIEW con separación menor a 90 ms');
    if (!checks.viewFinalStable) failures.push('La vista final no estabilizó');
    if (!checks.noStaleCanvasDraw) failures.push('Se dibujaron entregas obsoletas');
    if (!checks.noImageMix) failures.push('Se mezclaron imagen o versión');
    samples.push(sampleUi('after-100-views'));
  }

  if (mode === 'full' || mode === 'detail') {
    probe.setPhase('detail-cycles');
    clickDetail('-2');
    await waitStable('detalle bajo inicial', 20000);
    let genuineLevelChanges = 0;
    for (let cycle = 1; cycle <= 100; cycle++) {
      const lowLevel = readPlanLevel();
      const closesBefore = countEvents(probe.events(), 'BITMAP_CLOSE', 'detail-cycles');
      clickDetail('0');
      await waitStable('detalle alto ciclo ' + cycle, 20000);
      const highLevel = readPlanLevel();
      clickDetail('-2');
      await waitStable('detalle bajo ciclo ' + cycle, 20000);
      const reducedLevel = readPlanLevel();
      const closesAfter = countEvents(probe.events(), 'BITMAP_CLOSE', 'detail-cycles');
      if (highLevel > lowLevel || highLevel > reducedLevel) genuineLevelChanges++;
      samples.push({
        campaign: 'detail',
        cycle,
        lowLevel,
        highLevel,
        reducedLevel,
        closedBitmaps: closesAfter - closesBefore,
        managedMiB: readManagedMiB()
      });
      if (readManagedMiB() > 64.05) {
        failures.push('Presupuesto administrado excedido en ciclo ' + cycle);
        break;
      }
    }
    const detailSamples = samples.filter((sample) => sample.campaign === 'detail');
    checks.detailCycles100 = detailSamples.length === 100;
    checks.detailRealLevelChange = genuineLevelChanges === 100;
    checks.detailClosesObserved = detailSamples.every((sample) => Number(sample.closedBitmaps) > 0);
    checks.bitmapBudgetBounded = probe.counters().peakLiveBitmapBytes <= 64 * 1024 * 1024;
    checks.decodeQueueBounded = probe.counters().peakPendingDecodeJobs <= 34;
    checks.detailFinalStable = readPhase() === 'observing';
    if (!checks.detailCycles100) failures.push('No se completaron 100 ciclos de detalle');
    if (!checks.detailRealLevelChange) failures.push('No todos los ciclos demostraron cambio efectivo de nivel');
    if (!checks.detailClosesObserved) failures.push('Algún ciclo no observó cierre de bitmaps al reducir');
    if (!checks.bitmapBudgetBounded) failures.push('Bitmaps vivos superaron 64 MiB');
    if (!checks.decodeQueueBounded) failures.push('Cola de decodificación excedió el límite observado');
    samples.push(sampleUi('after-100-detail-cycles'));
  }

  if (mode === 'full' || mode === 'focus') {
    probe.setPhase('focus');
    clickButtonByText('.navigation-group button', 'Lente');
    setFocusRadius(32);
    await waitStable('foco radio mínimo', 20000);
    samples.push(sampleUi('focus-center-min-radius'));
    setFocusRadius(512);
    dispatchKey(requiredElement<HTMLElement>('.screen-stage'), 'ArrowRight');
    dispatchKey(requiredElement<HTMLElement>('.screen-stage'), 'ArrowDown');
    await waitStable('foco borde', 20000);
    samples.push(sampleUi('focus-edge'));
    for (let i = 0; i < 4; i++) {
      dispatchKey(requiredElement<HTMLElement>('.screen-stage'), 'ArrowRight');
      dispatchKey(requiredElement<HTMLElement>('.screen-stage'), 'ArrowDown');
      await sleep(110);
    }
    await waitStable('foco esquina', 20000);
    samples.push(sampleUi('focus-corner'));
    clickButtonByText('.navigation-group button', 'Uniforme');
    await waitStable('uniforme después de foco', 20000);
    checks.focusPlansObserved = probe.events().some((event) =>
      event.phase === 'focus' && event.type === 'WS_OUT_VIEW' && event.mode === 'focus'
    );
    checks.focusRadiusRangeObserved = probe.events().some((event) =>
      event.phase === 'focus' && event.type === 'WS_OUT_VIEW' && String(event.focus).includes('512')
    );
    checks.noImageMix = probe.counters().imageMixViolations === 0;
    if (!checks.focusPlansObserved) failures.push('No se observaron VIEW mode=focus');
    if (!checks.focusRadiusRangeObserved) failures.push('No se observó radio de foco 512 px');
  }

  if ((mode === 'full' || mode === 'image-change') && cartridges.length >= 2) {
    probe.setPhase('image-change');
    clickDetail('0');
    dispatchKey(requiredElement<HTMLElement>('.screen-stage'), '+');
    await sleep(25);
    cartridges[1]!.click();
    await waitStable('cambio de imagen durante carga', 30000);
    const active = readReadout('IMAGEN ACTIVA');
    checks.secondImageSelected = active === cartridgeId(cartridges[1]!);
    checks.noImageMixAfterSwitch = probe.counters().imageMixViolations === 0;
    if (!checks.secondImageSelected) failures.push('No quedó activa la segunda imagen');
    if (!checks.noImageMixAfterSwitch) failures.push('Se detectó mezcla tras cambio de imagen');
    samples.push(sampleUi('second-image'));
  }

  if (mode === 'full' || mode === 'errors') {
    probe.setPhase('errors');
    clickButtonByText('.header-actions button', 'Reconectar');
    await waitFor(() => readConnectionNumber() >= 2, 10000, 'reconexión no incrementó conexión');
    if (cartridges.length > 0) {
      cartridges[0]!.click();
      await waitStable('recuperación tras reconexión', 30000);
    }
    checks.reconnectRecovered = readPhase() === 'observing';
    checks.noExternalRequests = probe.counters().externalRequests === 0;
    checks.releaseAccounting = readMetricNumber('RELEASE') <= readMetricNumber('TILE recibidos');
    if (!checks.reconnectRecovered) failures.push('No se recuperó después de reconectar');
    if (!checks.noExternalRequests) failures.push('Se observó una solicitud externa');
    if (!checks.releaseAccounting) failures.push('Contabilidad RELEASE/TILE inconsistente');
    samples.push(sampleUi('after-reconnect'));
  }

  await finish();

  async function finish(): Promise<void> {
    probe.setPhase('finished');
    const counters = probe.counters();
    checks.noStaleCanvasDraw = counters.staleDrawViolations === 0;
    checks.noImageMix = counters.imageMixViolations === 0;
    checks.noExternalRequests = counters.externalRequests === 0;
    checks.bitmapBudgetBounded = counters.peakLiveBitmapBytes <= 64 * 1024 * 1024;
    if (!checks.noStaleCanvasDraw && !failures.includes('Se dibujaron entregas obsoletas')) failures.push('Se dibujaron entregas obsoletas');
    if (!checks.noImageMix && !failures.includes('Se mezclaron imagen o versión')) failures.push('Se mezclaron imagen o versión');
    if (!checks.noExternalRequests && !failures.includes('Se observó una solicitud externa')) failures.push('Se observó una solicitud externa');
    if (!checks.bitmapBudgetBounded && !failures.includes('Bitmaps vivos superaron 64 MiB')) failures.push('Bitmaps vivos superaron 64 MiB');
    const report: CampaignReport = {
      mode,
      seed,
      startedAt,
      finishedAt: new Date().toISOString(),
      status: failures.length === 0 && Object.values(checks).every(Boolean) ? 'PASS' : 'FAIL',
      checks,
      summary: {
        interactions: samples.filter((sample) => sample.campaign === 'view').length,
        detailCycles: samples.filter((sample) => sample.campaign === 'detail').length,
        wsViews: probe.events().filter((event) => event.type === 'WS_OUT_VIEW').length,
        receivedTiles: readMetricNumber('TILE recibidos'),
        drawnTiles: readMetricNumber('Dibujados'),
        discardedTiles: readMetricNumber('Descartados'),
        failedTiles: readMetricNumber('Fallidos'),
        releases: readMetricNumber('RELEASE'),
        peakLiveBitmapBytes: counters.peakLiveBitmapBytes,
        peakLiveBitmaps: counters.peakLiveBitmaps,
        peakPendingDecodeJobs: counters.peakPendingDecodeJobs,
        staleDrawViolations: counters.staleDrawViolations,
        imageMixViolations: counters.imageMixViolations,
        externalRequests: counters.externalRequests,
        finalPhase: readPhase(),
        finalImage: readReadout('IMAGEN ACTIVA'),
        finalVersion: readReadout('VERSIÓN')
      },
      samples,
      failures
    };
    publishReport(report);
  }
}

async function provokeLateDecode(): Promise<void> {
  clickDetail('0');
  const previousReceived = readMetricNumber('TILE recibidos');
  dispatchKey(requiredElement<HTMLElement>('.screen-stage'), '+');
  await waitFor(() => readMetricNumber('TILE recibidos') > previousReceived, 10000, 'no llegó TILE para carrera tardía');
  await sleep(120);
  dispatchKey(requiredElement<HTMLElement>('.screen-stage'), 'ArrowRight');
  await sleep(700);
  await waitStable('descarte tardío', 30000);
}

function chooseNavigationAction(value: number, index: number): string {
  const actions = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', '+', '-'];
  return actions[(Math.floor(value * actions.length) + index) % actions.length]!;
}

function dispatchNavigation(stage: HTMLElement, action: string): void {
  dispatchKey(stage, action);
}

function dispatchKey(target: HTMLElement, key: string): void {
  target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
}

function clickDetail(value: '-2' | '-1' | '0'): void {
  const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>('.detail-selector button'));
  const button = buttons.find((candidate) => candidate.textContent?.trim() === value);
  if (!button) throw new Error('No se encontró control de detalle ' + value);
  button.click();
}

function clickButtonByText(selector: string, text: string): void {
  const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>(selector));
  const button = buttons.find((candidate) => candidate.textContent?.trim() === text);
  if (!button) throw new Error('No se encontró botón ' + text);
  button.click();
}

function setFocusRadius(value: number): void {
  const input = document.querySelector<HTMLInputElement>('.focus-radius input');
  if (!input) throw new Error('No se encontró control de radio de foco');
  const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
  descriptor?.set?.call(input, String(value));
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
}

async function waitStable(label: string, timeoutMs: number): Promise<void> {
  await waitFor(() => {
    const phase = readPhase();
    return phase === 'observing' && readMetricNumber('Decodificando') === 0 && readMetricNumber('Por pintar') === 0;
  }, timeoutMs, label + ' no estabilizó');
  await sleep(80);
}

async function waitFor(predicate: () => boolean, timeoutMs: number, message: string): Promise<void> {
  const deadline = performance.now() + timeoutMs;
  while (performance.now() < deadline) {
    if (predicate()) return;
    await sleep(25);
  }
  throw new Error(message);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function sampleUi(label: string): Record<string, Primitive> {
  return {
    label,
    phase: readPhase(),
    connection: readConnectionNumber(),
    epoch: readMetricNumber('Época'),
    mode: readMetricText('Modo'),
    detail: readMetricText('Detalle'),
    zoom: readMetricText('Zoom'),
    received: readMetricNumber('TILE recibidos'),
    drawn: readMetricNumber('Dibujados'),
    discarded: readMetricNumber('Descartados'),
    failed: readMetricNumber('Fallidos'),
    releases: readMetricNumber('RELEASE'),
    managedMiB: readManagedMiB(),
    planLevel: readPlanLevel(),
    image: readReadout('IMAGEN ACTIVA'),
    version: readReadout('VERSIÓN'),
    canvasHash: canvasHash()
  };
}

function readMetricText(label: string): string {
  for (const metric of Array.from(document.querySelectorAll<HTMLElement>('.metric'))) {
    const span = metric.querySelector('span');
    if (span?.textContent?.trim() === label) return metric.querySelector('strong')?.textContent?.trim() ?? '';
  }
  return '';
}

function readMetricNumber(label: string): number {
  const value = readMetricText(label).replace(/[^0-9.-]/g, '');
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function readManagedMiB(): number {
  const text = readMetricText('Bitmaps LUPA');
  const match = text.match(/([0-9.]+)\s*(B|KiB|MiB)/i);
  if (!match) return 0;
  const value = Number(match[1]);
  const unit = match[2]?.toLowerCase();
  if (unit === 'mib') return value;
  if (unit === 'kib') return value / 1024;
  return value / (1024 * 1024);
}

function readPlanLevel(): number {
  const value = readReadout('NIVEL');
  const match = value.match(/z(\d+)/i);
  return match ? Number(match[1]) : -1;
}

function readReadout(label: string): string {
  for (const readout of Array.from(document.querySelectorAll<HTMLElement>('.readout'))) {
    if (readout.querySelector('small')?.textContent?.trim() === label) {
      return readout.querySelector('strong')?.textContent?.trim() ?? '';
    }
  }
  return '';
}

function readPhase(): string {
  return document.querySelector<HTMLElement>('.status-lamp')?.dataset.phase ?? '';
}

function readConnectionNumber(): number {
  return readMetricNumber('Conexión');
}

function cartridgeId(button: HTMLButtonElement): string {
  return button.querySelector('strong')?.textContent?.trim() ?? '';
}

function requiredElement<T extends Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) throw new Error('No se encontró ' + selector);
  return element;
}

function minimumEventGap(events: A23Event[]): number {
  if (events.length < 2) return Number.POSITIVE_INFINITY;
  let minimum = Number.POSITIVE_INFINITY;
  for (let index = 1; index < events.length; index++) {
    minimum = Math.min(minimum, events[index]!.atMs - events[index - 1]!.atMs);
  }
  return minimum;
}

function countEvents(events: A23Event[], type: string, targetPhase: string): number {
  return events.filter((event) => event.type === type && event.phase === targetPhase).length;
}

function canvasHash(): string {
  const canvas = document.querySelector<HTMLCanvasElement>('.screen-stage canvas');
  if (!canvas || canvas.width < 1 || canvas.height < 1) return 'none';
  const context = canvas.getContext('2d');
  if (!context) return 'none';
  const points = 64;
  let hash = 2166136261 >>> 0;
  for (let index = 0; index < points; index++) {
    const x = Math.min(canvas.width - 1, Math.floor((index % 8 + 0.5) * canvas.width / 8));
    const y = Math.min(canvas.height - 1, Math.floor((Math.floor(index / 8) + 0.5) * canvas.height / 8));
    const pixel = context.getImageData(x, y, 1, 1).data;
    for (const byte of pixel) {
      hash ^= byte;
      hash = Math.imul(hash, 16777619) >>> 0;
    }
  }
  return hash.toString(16).padStart(8, '0');
}

function publishReport(report: CampaignReport): void {
  let output = document.getElementById('a23-report') as HTMLPreElement | null;
  if (!output) {
    output = document.createElement('pre');
    output.id = 'a23-report';
    output.style.position = 'fixed';
    output.style.left = '0';
    output.style.bottom = '0';
    output.style.zIndex = '2147483647';
    output.style.maxWidth = '40vw';
    output.style.maxHeight = '35vh';
    output.style.overflow = 'auto';
    output.style.background = 'rgba(0,0,0,.88)';
    output.style.color = '#d7ffd7';
    output.style.font = '11px monospace';
    output.style.padding = '8px';
    document.body.appendChild(output);
  }
  output.dataset.status = report.status;
  output.textContent = JSON.stringify(report);
  document.documentElement.dataset.a23Status = report.status;
  document.documentElement.dataset.a23Mode = report.mode;
}

function parseControl(text: string): Record<string, unknown> | null {
  try {
    const value = JSON.parse(text) as unknown;
    return asRecord(value);
  } catch {
    return null;
  }
}

function parseTileHeader(buffer: ArrayBuffer): Record<string, unknown> | null {
  if (buffer.byteLength < 4) return null;
  const headerBytes = new DataView(buffer).getUint32(0, false);
  if (headerBytes < 2 || headerBytes > 4096 || 4 + headerBytes > buffer.byteLength) return null;
  try {
    const text = new TextDecoder().decode(new Uint8Array(buffer, 4, headerBytes));
    return parseControl(text);
  } catch {
    return null;
  }
}

function controlSummary(control: Record<string, unknown>): EventData {
  const summary: EventData = { controlType: stringField(control, 'type') };
  for (const key of ['epoch', 'imageId', 'imageVersion', 'deliveryId', 'appliedLevel', 'contextLevel', 'detailOffset', 'mode', 'sentTiles']) {
    const value = control[key];
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') summary[key] = value;
  }
  const focus = asRecord(control.focus);
  if (focus) summary.focus = [numberField(focus, 'x'), numberField(focus, 'y'), numberField(focus, 'radiusPx')].join(',');
  const rect = asRecord(control.rect);
  if (rect) summary.rect = [numberField(rect, 'x'), numberField(rect, 'y'), numberField(rect, 'width'), numberField(rect, 'height')].join(',');
  return summary;
}

function tileSummary(header: Record<string, unknown> | TileMeta): EventData {
  return {
    deliveryId: numberField(header as Record<string, unknown>, 'deliveryId'),
    epoch: numberField(header as Record<string, unknown>, 'epoch'),
    imageId: stringField(header as Record<string, unknown>, 'imageId'),
    imageVersion: stringField(header as Record<string, unknown>, 'imageVersion'),
    z: numberField(header as Record<string, unknown>, 'z'),
    x: numberField(header as Record<string, unknown>, 'x'),
    y: numberField(header as Record<string, unknown>, 'y'),
    w: numberField(header as Record<string, unknown>, 'w'),
    h: numberField(header as Record<string, unknown>, 'h')
  };
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : null;
}

function stringField(value: Record<string, unknown>, key: string): string {
  return typeof value[key] === 'string' ? value[key] as string : '';
}

function numberField(value: Record<string, unknown>, key: string): number {
  return typeof value[key] === 'number' && Number.isFinite(value[key]) ? value[key] as number : 0;
}

function readSeed(): number {
  const raw = Number(params.get('seed') ?? '23001717');
  return Number.isSafeInteger(raw) ? raw : 23001717;
}

function mulberry32(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state += 0x6D2B79F5;
    let value = state;
    value = Math.imul(value ^ value >>> 15, value | 1);
    value ^= value + Math.imul(value ^ value >>> 7, value | 61);
    return ((value ^ value >>> 14) >>> 0) / 4294967296;
  };
}

function roundMs(value: number): number {
  return Math.round(value * 100) / 100;
}
