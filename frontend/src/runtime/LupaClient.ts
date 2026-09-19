import {
  BITMAP_BUDGET_BYTES,
  LUPA_VERSION,
  MAX_EPOCH,
  REQUESTED_WINDOW_BYTES,
  type CatalogImage,
  type Manifest,
  type Plan,
  type ReleaseStatus,
  type ViewLayout,
  type Welcome,
  type WorkerResponse
} from '../protocol/types';
import { computeFitLayout } from '../protocol/viewMath';
import { parseTileEnvelopeBase, validateTileAgainstManifest } from '../protocol/tileEnvelope';
import {
  parseDone,
  parseError,
  parseJsonControl,
  parseManifest,
  parsePlan,
  parseWelcome
} from '../protocol/validation';
import { BitmapBudget } from './BitmapBudget';
import { CanvasCompositor } from './CanvasCompositor';
import { DeliveryLedger } from './DeliveryLedger';
import { TraceBuffer, type TraceEntry } from './TraceBuffer';
import type { LupaTransport } from './transport';
import { WebSocketTransport } from './WebSocketTransport';
import { deriveTransferPhase } from './state';

export type ViewerPhase =
  | 'disconnected'
  | 'connecting'
  | 'ready'
  | 'opening'
  | 'receiving'
  | 'processing'
  | 'observing'
  | 'error';

export interface ClientSnapshot {
  phase: ViewerPhase;
  connectionId: number;
  selectedImageId: string | null;
  manifest: Manifest | null;
  plan: Plan | null;
  welcome: Welcome | null;
  activeEpoch: number;
  receivedTiles: number;
  drawnTiles: number;
  discardedTiles: number;
  failedTiles: number;
  releases: number;
  pendingDecodes: number;
  pendingPresentations: number;
  serverDone: boolean;
  doneSentTiles: number | null;
  managedBitmapBytes: number;
  error: string | null;
  trace: TraceEntry[];
}

interface PendingDecode {
  deliveryId: number;
  connectionId: number;
  epoch: number;
  bytes: number;
  kind: 'context' | 'detail';
}

export class LupaClient {
  private transport: LupaTransport | null = null;
  private readonly worker: Worker;
  private readonly trace = new TraceBuffer();
  private readonly budget = new BitmapBudget();
  private readonly ledger = new DeliveryLedger();
  private compositor: CanvasCompositor | null = null;
  private readonly pending = new Map<string, PendingDecode>();
  private listeners = new Set<(snapshot: ClientSnapshot) => void>();
  private connectionId = 0;
  private epoch = 0;
  private selected: CatalogImage | null = null;
  private manifest: Manifest | null = null;
  private readonly manifestHistory = new Map<string, Manifest>();
  private plan: Plan | null = null;
  private welcome: Welcome | null = null;
  private viewport: { width: number; height: number; dpr: number } | null = null;
  private layout: ViewLayout | null = null;
  private resizeTimer = 0;
  private notifyTimer = 0;
  private phase: ViewerPhase = 'disconnected';
  private error: string | null = null;
  private receivedTiles = 0;
  private drawnTiles = 0;
  private discardedTiles = 0;
  private failedTiles = 0;
  private releases = 0;
  private pendingPresentations = 0;
  private serverDone = false;
  private doneSentTiles: number | null = null;
  private destroyed = false;

  constructor(
    private readonly transportFactory: () => LupaTransport = () => new WebSocketTransport(),
    workerFactory: () => Worker = () =>
      new Worker(new URL('../worker/decodeWorker.ts', import.meta.url), { type: 'module' })
  ) {
    this.worker = workerFactory();
    this.worker.addEventListener('message', (event: MessageEvent<WorkerResponse>) => this.onWorker(event.data));
    this.worker.addEventListener('error', () => this.fail('El Worker de decodificación falló'));
  }

  subscribe(listener: (snapshot: ClientSnapshot) => void): () => void {
    this.listeners.add(listener);
    listener(this.snapshot());
    return () => this.listeners.delete(listener);
  }

  attachCanvas(canvas: HTMLCanvasElement): void {
    this.compositor?.destroy();
    this.compositor = new CanvasCompositor(canvas, (kind, bytes) => this.budget.removeStored(kind, bytes));
    if (this.manifest && this.layout) this.compositor.startImage(this.manifest, this.layout);
  }

  connect(): void {
    if (this.destroyed) return;
    this.transport?.close(1000, 'reconnect');
    this.connectionId++;
    this.epoch = 0;
    this.manifest = null;
    this.manifestHistory.clear();
    this.plan = null;
    this.welcome = null;
    this.serverDone = false;
    this.doneSentTiles = null;
    this.error = null;
    this.phase = 'connecting';
    this.ledger.clear();
    this.worker.postMessage({ type: 'reset', connectionId: this.connectionId - 1 });
    this.compositor?.reset();
    this.budget.reset();
    this.pending.clear();
    this.pendingPresentations = 0;
    this.receivedTiles = 0;
    this.drawnTiles = 0;
    this.discardedTiles = 0;
    this.failedTiles = 0;
    this.releases = 0;
    this.trace.push('LOCAL', 'CONNECT', 'conexión local #' + this.connectionId);
    this.emitNow();

    const connectionAtOpen = this.connectionId;
    const transport = this.transportFactory();
    this.transport = transport;
    transport.connect({
      onOpen: () => {
        if (connectionAtOpen !== this.connectionId) return;
        this.trace.push('LOCAL', 'WS_OPEN', 'lupa.v1');
        this.send({
          type: 'HELLO',
          version: LUPA_VERSION,
          windowBytes: REQUESTED_WINDOW_BYTES,
          bitmapBudgetBytes: BITMAP_BUDGET_BYTES
        });
      },
      onText: (text) => this.onText(connectionAtOpen, text),
      onBinary: (buffer) => this.onBinary(connectionAtOpen, buffer),
      onClose: (code, reason) => {
        if (connectionAtOpen !== this.connectionId) return;
        this.trace.push('LOCAL', 'WS_CLOSE', 'code=' + code + ' ' + reason);
        this.cleanupConnection(connectionAtOpen);
        if (this.phase !== 'error') this.phase = 'disconnected';
        this.emitNow();
      },
      onError: (message) => {
        if (connectionAtOpen === this.connectionId) this.fail(message);
      }
    });
  }

  reconnect(): void {
    this.connect();
  }

  selectImage(image: CatalogImage): void {
    this.selected = image;
    if (!this.welcome || !this.transport?.isOpen) {
      this.phase = this.phase === 'connecting' ? 'connecting' : 'ready';
      this.emitNow();
      return;
    }
    this.openSelected();
  }

  setViewport(width: number, height: number, dpr: number): void {
    if (width < 1 || height < 1 || dpr <= 0) return;
    const previous = this.viewport;
    this.viewport = { width, height, dpr };
    if (!this.manifest) return;

    this.layout = computeFitLayout(this.manifest.width, this.manifest.height, width, height, dpr);
    this.compositor?.setLayout(this.layout);

    if (!previous) {
      this.requestView();
      return;
    }
    const changed =
      Math.abs(previous.width - width) >= 2 ||
      Math.abs(previous.height - height) >= 2 ||
      Math.abs(previous.dpr - dpr) >= 0.05;
    if (!changed) return;

    window.clearTimeout(this.resizeTimer);
    this.resizeTimer = window.setTimeout(() => this.requestView(), 180);
  }

  downloadTrace(): void {
    const lines = this.trace
      .snapshot()
      .map((entry) => [entry.at, entry.direction, entry.event, entry.detail].join('\t'))
      .join('\n');
    const blob = new Blob([lines + '\n'], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'lupa-a20-trace-' + new Date().toISOString().replace(/[:.]/g, '-') + '.txt';
    anchor.click();
    URL.revokeObjectURL(url);
  }

  snapshot(): ClientSnapshot {
    return {
      phase: this.phase,
      connectionId: this.connectionId,
      selectedImageId: this.selected?.imageId ?? null,
      manifest: this.manifest,
      plan: this.plan,
      welcome: this.welcome,
      activeEpoch: this.epoch,
      receivedTiles: this.receivedTiles,
      drawnTiles: this.drawnTiles,
      discardedTiles: this.discardedTiles,
      failedTiles: this.failedTiles,
      releases: this.releases,
      pendingDecodes: this.pending.size,
      pendingPresentations: this.pendingPresentations,
      serverDone: this.serverDone,
      doneSentTiles: this.doneSentTiles,
      managedBitmapBytes: this.budget.snapshot().managedBytes,
      error: this.error,
      trace: this.trace.snapshot()
    };
  }

  destroy(): void {
    this.destroyed = true;
    window.clearTimeout(this.resizeTimer);
    window.clearTimeout(this.notifyTimer);
    this.transport?.close(1000, 'viewer destroy');
    this.worker.terminate();
    this.compositor?.destroy();
    this.listeners.clear();
  }

  private cleanupConnection(connectionId: number): void {
    this.worker.postMessage({ type: 'reset', connectionId });
    for (const [jobId, pending] of this.pending) {
      if (pending.connectionId === connectionId) {
        this.budget.cancel(jobId);
        this.pending.delete(jobId);
      }
    }
    this.compositor?.reset();
    this.budget.reset();
    this.ledger.clear();
    this.manifest = null;
    this.plan = null;
    this.welcome = null;
    this.serverDone = false;
    this.doneSentTiles = null;
  }

  private onText(connectionId: number, text: string): void {
    if (connectionId !== this.connectionId) return;
    let control: Record<string, unknown>;
    try {
      control = parseJsonControl(text);
    } catch (error) {
      this.protocolViolation(error);
      return;
    }
    const type = String(control.type);
    this.trace.push('IN', type, summarizeControl(control));

    try {
      switch (type) {
        case 'WELCOME': {
          const welcome = parseWelcome(control);
          if (welcome.version !== LUPA_VERSION) throw new Error('Versión LUPA no negociada');
          this.welcome = welcome;
          this.phase = 'ready';
          this.emitNow();
          if (this.selected) this.openSelected();
          return;
        }
        case 'MANIFEST': {
          if (isStaleEpoch(control, this.epoch)) {
            this.trace.push('LOCAL', 'STALE_MANIFEST', 'epoch=' + String(control.epoch));
            return;
          }
          if (!this.selected) throw new Error('MANIFEST sin imagen seleccionada');
          const manifest = parseManifest(control, this.selected.imageId, this.epoch);
          this.manifest = manifest;
          this.rememberManifest(manifest);
          this.plan = null;
          this.serverDone = false;
          this.doneSentTiles = null;
          this.phase = 'opening';
          if (this.viewport) {
            this.layout = computeFitLayout(
              manifest.width,
              manifest.height,
              this.viewport.width,
              this.viewport.height,
              this.viewport.dpr
            );
            this.compositor?.startImage(manifest, this.layout);
            this.requestView();
          }
          this.emitNow();
          return;
        }
        case 'PLAN': {
          if (isStaleEpoch(control, this.epoch)) {
            this.trace.push('LOCAL', 'STALE_PLAN', 'epoch=' + String(control.epoch));
            return;
          }
          if (!this.manifest) throw new Error('PLAN sin MANIFEST');
          const plan = parsePlan(control, this.manifest);
          if (plan.epoch !== this.epoch) throw new Error('PLAN pertenece a una época futura');
          this.plan = plan;
          this.phase = 'receiving';
          this.emitNow();
          return;
        }
        case 'DONE': {
          if (isStaleEpoch(control, this.epoch)) {
            this.trace.push('LOCAL', 'STALE_DONE', 'epoch=' + String(control.epoch));
            return;
          }
          const done = parseDone(control);
          if (done.epoch !== this.epoch) throw new Error('DONE pertenece a una época futura');
          this.serverDone = true;
          this.doneSentTiles = done.sentTiles;
          this.phase = this.pending.size === 0 && this.pendingPresentations === 0 ? 'observing' : 'processing';
          this.trace.push(
            'LOCAL',
            'DONE_STATE',
            'decode=' + this.pending.size + ' pintura=' + this.pendingPresentations
          );
          this.emitNow();
          return;
        }
        case 'ERROR': {
          const remote = parseError(control);
          if (remote.epoch !== undefined && remote.epoch < this.epoch) {
            this.trace.push('LOCAL', 'STALE_ERROR', 'epoch=' + remote.epoch + ' code=' + remote.code);
            return;
          }
          this.fail(remote.code + ': ' + remote.message);
          return;
        }
        default:
          throw new Error('Control LUPA inesperado: ' + type);
      }
    } catch (error) {
      this.protocolViolation(error);
    }
  }

  private onBinary(connectionId: number, buffer: ArrayBuffer): void {
    if (connectionId !== this.connectionId || !this.welcome) return;
    let tile;
    try {
      tile = parseTileEnvelopeBase(buffer, this.welcome.maxTileBytes);
      const knownManifest = this.manifestHistory.get(manifestKey(tile.header.imageId, tile.header.imageVersion));
      if (!knownManifest) throw new Error('TILE no corresponde a una versión MANIFEST conocida');
      validateTileAgainstManifest(tile, knownManifest);
    } catch (error) {
      this.protocolViolation(error);
      return;
    }

    const header = tile.header;
    this.receivedTiles++;
    this.trace.push(
      'IN',
      'TILE',
      'delivery=' + header.deliveryId + ' epoch=' + header.epoch +
        ' z=' + header.z + ' x=' + header.x + ' y=' + header.y +
        ' jpeg=' + header.payloadBytes
    );

    if (!this.ledger.register(header.deliveryId, connectionId)) {
      this.protocolViolation(new Error('deliveryId duplicado'));
      return;
    }

    if (header.epoch < this.epoch) {
      this.discardedTiles++;
      this.release(header.deliveryId, connectionId, 'discarded');
      this.notifySoon();
      return;
    }
    if (header.epoch > this.epoch) {
      this.protocolViolation(new Error('TILE pertenece a una época futura'));
      return;
    }

    const decodedBytes = header.w * header.h * 4;
    const jobId = connectionId + ':' + header.deliveryId;
    if (!this.budget.reserve(jobId, decodedBytes)) {
      this.discardedTiles++;
      this.trace.push('LOCAL', 'BUDGET_DROP', 'delivery=' + header.deliveryId + ' rgba=' + decodedBytes);
      this.release(header.deliveryId, connectionId, 'discarded');
      this.notifySoon();
      return;
    }

    this.pending.set(jobId, {
      deliveryId: header.deliveryId,
      connectionId,
      epoch: header.epoch,
      bytes: decodedBytes,
      kind: header.z === 0 ? 'context' : 'detail'
    });
    this.phase = 'processing';
    this.worker.postMessage(
      {
        type: 'decode',
        jobId,
        connectionId,
        epoch: header.epoch,
        header,
        buffer: tile.buffer,
        payloadOffset: tile.payloadOffset,
        payloadBytes: tile.payloadBytes
      },
      [tile.buffer]
    );
    this.notifySoon();
  }

  private onWorker(response: WorkerResponse): void {
    const pending = this.pending.get(response.jobId);
    if (!pending) {
      if (response.type === 'decoded') response.bitmap.close();
      return;
    }
    this.pending.delete(response.jobId);

    if (response.type === 'decoded') {
      if (
        response.connectionId !== this.connectionId ||
        response.epoch !== this.epoch ||
        !this.manifest ||
        response.header.imageId !== this.manifest.imageId ||
        response.header.imageVersion !== this.manifest.imageVersion
      ) {
        response.bitmap.close();
        this.budget.cancel(response.jobId);
        this.discardedTiles++;
        if (response.connectionId === this.connectionId) {
          this.release(response.deliveryId, response.connectionId, 'discarded');
        }
      } else {
        const bytes = this.budget.commit(response.jobId, pending.kind);
        if (bytes === null || !this.compositor) {
          response.bitmap.close();
          this.discardedTiles++;
          this.release(response.deliveryId, response.connectionId, 'discarded');
        } else {
          this.pendingPresentations++;
          const accepted = this.compositor.store(
            response.header,
            response.bitmap,
            bytes,
            () => {
              this.pendingPresentations = Math.max(0, this.pendingPresentations - 1);
              this.drawnTiles++;
              this.release(response.deliveryId, response.connectionId, 'displayed');
              this.updateCompletionPhase();
              this.notifySoon();
            },
            () => {
              this.pendingPresentations = Math.max(0, this.pendingPresentations - 1);
              this.discardedTiles++;
              this.release(response.deliveryId, response.connectionId, 'discarded');
              this.updateCompletionPhase();
              this.notifySoon();
            }
          );
          if (!accepted) {
            this.pendingPresentations = Math.max(0, this.pendingPresentations - 1);
            this.budget.removeStored(pending.kind, bytes);
            this.discardedTiles++;
            this.release(response.deliveryId, response.connectionId, 'discarded');
          }
        }
      }
    } else {
      this.budget.cancel(response.jobId);
      if (response.connectionId === this.connectionId) {
        if (response.type === 'discarded') {
          this.discardedTiles++;
          this.release(response.deliveryId, response.connectionId, 'discarded');
        } else {
          this.failedTiles++;
          this.release(response.deliveryId, response.connectionId, 'failed');
        }
      }
      this.trace.push('LOCAL', response.type.toUpperCase(), response.reason);
    }

    this.updateCompletionPhase();
    this.notifySoon();
  }

  private rememberManifest(manifest: Manifest): void {
    const key = manifestKey(manifest.imageId, manifest.imageVersion);
    this.manifestHistory.delete(key);
    this.manifestHistory.set(key, manifest);
    while (this.manifestHistory.size > 8) {
      const oldest = this.manifestHistory.keys().next().value as string | undefined;
      if (oldest === undefined) break;
      this.manifestHistory.delete(oldest);
    }
  }

  private updateCompletionPhase(): void {
    const next = deriveTransferPhase(
      this.serverDone,
      this.pending.size,
      this.pendingPresentations,
      this.plan !== null
    );
    if (next) this.phase = next;
  }

  private openSelected(): void {
    if (!this.selected) return;
    const epoch = this.nextEpoch();
    if (epoch === null) return;
    this.manifest = null;
    this.plan = null;
    this.serverDone = false;
    this.doneSentTiles = null;
    this.compositor?.reset();
    this.worker.postMessage({ type: 'invalidate', connectionId: this.connectionId, minEpoch: epoch });
    this.phase = 'opening';
    this.send({ type: 'OPEN', epoch, imageId: this.selected.imageId });
    this.emitNow();
  }

  private requestView(): void {
    if (!this.manifest || !this.layout || !this.selected || !this.transport?.isOpen) return;
    const epoch = this.nextEpoch();
    if (epoch === null) return;
    this.serverDone = false;
    this.doneSentTiles = null;
    this.plan = null;
    this.worker.postMessage({ type: 'invalidate', connectionId: this.connectionId, minEpoch: epoch });
    this.compositor?.beginEpoch(epoch);
    this.phase = 'receiving';
    this.send({
      type: 'VIEW',
      epoch,
      imageId: this.manifest.imageId,
      imageVersion: this.manifest.imageVersion,
      rect: { x: 0, y: 0, width: this.manifest.width, height: this.manifest.height },
      viewportPx: this.layout.viewportPx,
      detailOffset: 0,
      mode: 'uniform',
      focus: null
    });
    this.emitNow();
  }

  private nextEpoch(): number | null {
    if (this.epoch >= MAX_EPOCH) {
      this.fail('Se agotó el rango de épocas; reconecta la sesión');
      return null;
    }
    this.epoch++;
    return this.epoch;
  }

  private send(value: Record<string, unknown>): void {
    try {
      const text = JSON.stringify(value);
      this.transport?.sendText(text);
      this.trace.push('OUT', String(value.type), summarizeControl(value));
    } catch (error) {
      this.fail(error instanceof Error ? error.message : 'No se pudo enviar control');
    }
  }

  private release(deliveryId: number, connectionId: number, status: ReleaseStatus): void {
    if (connectionId !== this.connectionId || !this.transport?.isOpen) return;
    const sent = this.ledger.releaseOnce(deliveryId, connectionId, status, (id, releaseStatus) => {
      this.send({ type: 'RELEASE', deliveryId: id, status: releaseStatus });
    });
    if (sent) this.releases++;
  }

  private protocolViolation(error: unknown): void {
    const message = error instanceof Error ? error.message : 'Violación de protocolo';
    this.trace.push('LOCAL', 'PROTOCOL_ERROR', message);
    this.transport?.close(1008, 'client protocol validation failed');
    this.fail(message);
  }

  private fail(message: string): void {
    this.error = message;
    this.phase = 'error';
    this.trace.push('LOCAL', 'ERROR', message);
    this.emitNow();
  }

  private notifySoon(): void {
    if (this.notifyTimer) return;
    this.notifyTimer = window.setTimeout(() => {
      this.notifyTimer = 0;
      this.emitNow();
    }, 100);
  }

  private emitNow(): void {
    const snapshot = this.snapshot();
    for (const listener of this.listeners) listener(snapshot);
  }
}

function summarizeControl(control: Record<string, unknown>): string {
  const keys = ['epoch', 'imageId', 'imageVersion', 'deliveryId', 'status', 'appliedLevel', 'sentTiles'];
  const parts = keys
    .filter((key) => control[key] !== undefined)
    .map((key) => key + '=' + String(control[key]));
  return parts.join(' ');
}


function manifestKey(imageId: string, imageVersion: string): string {
  return imageId + ':' + imageVersion;
}

function isStaleEpoch(control: Record<string, unknown>, currentEpoch: number): boolean {
  const value = control.epoch;
  return Number.isSafeInteger(value) && (value as number) >= 1 && (value as number) < currentEpoch;
}
