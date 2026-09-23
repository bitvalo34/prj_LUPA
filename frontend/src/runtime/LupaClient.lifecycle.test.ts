import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CatalogImage, WorkerResponse } from '../protocol/types';
import { LupaClient } from './LupaClient';
import type { LupaTransport, TransportHandlers } from './transport';

const FIRST: CatalogImage = {
  imageId: 'primera', imageVersion: 'v1', width: 256, height: 256, tileSize: 256, maxLevel: 0
};
const SECOND: CatalogImage = {
  imageId: 'segunda', imageVersion: 'v1', width: 256, height: 256, tileSize: 256, maxLevel: 0
};
const FIRST_V2: CatalogImage = {
  ...FIRST, imageVersion: 'v2'
};

class FakeTransport implements LupaTransport {
  private handlers: TransportHandlers | null = null;
  private openState = false;
  readonly sent: Array<Record<string, unknown>> = [];

  get isOpen(): boolean {
    return this.openState;
  }

  connect(handlers: TransportHandlers): void {
    this.handlers = handlers;
  }

  open(): void {
    this.openState = true;
    this.handlers?.onOpen();
  }

  sendText(text: string): void {
    if (!this.openState) throw new Error('fake transport closed');
    this.sent.push(JSON.parse(text) as Record<string, unknown>);
  }

  close(code = 1000, reason = ''): void {
    if (!this.openState) return;
    this.openState = false;
    this.handlers?.onClose(code, reason);
  }

  receive(value: Record<string, unknown>): void {
    this.handlers?.onText(JSON.stringify(value));
  }

  receiveBinary(buffer: ArrayBuffer): void {
    this.handlers?.onBinary(buffer);
  }
}

class FakeWorker {
  readonly posted: unknown[] = [];
  private messageListener: ((event: MessageEvent) => void) | null = null;

  addEventListener(type: string, listener: EventListenerOrEventListenerObject): void {
    if (type === 'message') this.messageListener = listener as (event: MessageEvent) => void;
  }

  postMessage(message: unknown): void {
    this.posted.push(message);
  }

  emit(response: WorkerResponse): void {
    this.messageListener?.({ data: response } as MessageEvent);
  }

  terminate(): void {}
}

function welcome(transport: FakeTransport): void {
  transport.receive({
    type: 'WELCOME', version: 1, windowBytes: 1_048_576, maxTileBytes: 262_144, maxInFlight: 16
  });
}

function manifest(transport: FakeTransport, image: CatalogImage, epoch: number): void {
  transport.receive({
    type: 'MANIFEST',
    epoch,
    imageId: image.imageId,
    imageVersion: image.imageVersion,
    width: image.width,
    height: image.height,
    tileSize: image.tileSize,
    levels: [{ z: 0, width: image.width, height: image.height }]
  });
}

function tile(image: CatalogImage, epoch: number, deliveryId: number): ArrayBuffer {
  const payload = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
  const header = new TextEncoder().encode(JSON.stringify({
    type: 'TILE', deliveryId, epoch,
    imageId: image.imageId, imageVersion: image.imageVersion,
    z: 0, x: 0, y: 0, w: 256, h: 256,
    codec: 'jpeg', payloadBytes: payload.byteLength
  }));
  const envelope = new ArrayBuffer(4 + header.byteLength + payload.byteLength);
  const view = new DataView(envelope);
  view.setUint32(0, header.byteLength, false);
  new Uint8Array(envelope, 4, header.byteLength).set(header);
  new Uint8Array(envelope, 4 + header.byteLength).set(payload);
  return envelope;
}

function createClient(transports: FakeTransport[]) {
  const worker = new FakeWorker();
  let index = 0;
  const client = new LupaClient(
    () => transports[index++]!,
    () => worker as unknown as Worker
  );
  return { client, worker };
}

beforeEach(() => {
  vi.stubGlobal('window', {
    location: { search: '' },
    setTimeout: globalThis.setTimeout.bind(globalThis),
    clearTimeout: globalThis.clearTimeout.bind(globalThis)
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ciclo de vida A22', () => {
  it('abre una segunda imagen y una versión nueva sin crear otra conexión', () => {
    const transport = new FakeTransport();
    const { client } = createClient([transport]);

    client.connect();
    transport.open();
    welcome(transport);
    client.selectImage(FIRST);
    manifest(transport, FIRST, 1);

    client.selectImage(SECOND);
    manifest(transport, SECOND, 2);
    client.selectImage(FIRST_V2);
    expect(transport.sent.filter((message) => message.type === 'OPEN')).toEqual([
      { type: 'OPEN', epoch: 1, imageId: 'primera' },
      { type: 'OPEN', epoch: 2, imageId: 'segunda' },
      { type: 'OPEN', epoch: 3, imageId: 'primera' }
    ]);
    expect(client.snapshot().connectionId).toBe(1);
    expect(client.snapshot().selectedImageVersion).toBe('v2');

    manifest(transport, FIRST_V2, 3);
    expect(client.snapshot().manifest?.imageVersion).toBe('v2');
    client.destroy();
  });

  it('libera inmediatamente reservas de decode viejas al cambiar de imagen', () => {
    const transport = new FakeTransport();
    const { client } = createClient([transport]);

    client.connect();
    transport.open();
    welcome(transport);
    client.selectImage(FIRST);
    manifest(transport, FIRST, 1);
    transport.receiveBinary(tile(FIRST, 1, 1));

    expect(client.snapshot().pendingDecodes).toBe(1);
    expect(client.snapshot().managedBitmapBytes).toBe(256 * 256 * 4);
    client.selectImage(SECOND);
    expect(client.snapshot().pendingDecodes).toBe(1);
    expect(client.snapshot().managedBitmapBytes).toBe(0);
    expect(client.snapshot().trace.some((entry) => entry.event === 'OPEN_CANCEL_RESERVATIONS')).toBe(true);
    client.destroy();
  });

  it('mantiene desconectado al seleccionar y reabre la selección tras reconectar', () => {
    const first = new FakeTransport();
    const second = new FakeTransport();
    const { client } = createClient([first, second]);

    client.connect();
    first.open();
    welcome(first);
    client.selectImage(FIRST);
    first.close(1011, 'timeout');

    client.selectImage(SECOND);
    expect(client.snapshot().phase).toBe('disconnected');
    expect(client.snapshot().error).toContain('Reconectar');

    client.reconnect();
    second.open();
    welcome(second);
    expect(client.snapshot().connectionId).toBe(2);
    expect(second.sent.filter((message) => message.type === 'OPEN')).toEqual([
      { type: 'OPEN', epoch: 1, imageId: 'segunda' }
    ]);
    expect(client.snapshot().error).toBeNull();
    client.destroy();
  });

  it('presenta un ERROR de OPEN como recuperable y permite reintentar', () => {
    const transport = new FakeTransport();
    const { client } = createClient([transport]);

    client.connect();
    transport.open();
    welcome(transport);
    client.selectImage(FIRST);
    transport.receive({
      type: 'ERROR', epoch: 1, code: 'IMAGE_NOT_READY', message: 'publication pending'
    });

    expect(client.snapshot().phase).toBe('ready');
    expect(client.snapshot().error).toContain('termine la importación');
    client.retrySelectedImage();
    expect(transport.sent.at(-1)).toEqual({ type: 'OPEN', epoch: 2, imageId: 'primera' });
    expect(client.snapshot().error).toBeNull();
    client.destroy();
  });

  it('mantiene recuperable un OPEN fallido aunque termine un decode obsoleto', () => {
    const transport = new FakeTransport();
    const { client, worker } = createClient([transport]);

    client.connect();
    transport.open();
    welcome(transport);
    client.selectImage(FIRST);
    manifest(transport, FIRST, 1);
    transport.receiveBinary(tile(FIRST, 1, 1));
    const decodeJob = worker.posted.find(
      (message): message is { type: 'decode'; jobId: string } =>
        typeof message === 'object' && message !== null &&
        (message as { type?: string }).type === 'decode'
    );
    expect(decodeJob).toBeDefined();

    client.selectImage(SECOND);
    transport.receive({
      type: 'ERROR', epoch: 2, code: 'IMAGE_NOT_READY', message: 'publication pending'
    });
    worker.emit({
      type: 'discarded',
      jobId: decodeJob!.jobId,
      connectionId: 1,
      epoch: 1,
      deliveryId: 1,
      reason: 'invalidated by OPEN'
    });

    expect(client.snapshot().pendingDecodes).toBe(0);
    expect(client.snapshot().phase).toBe('ready');
    expect(client.snapshot().error).toContain('termine la importación');
    client.destroy();
  });

  it('solicita recuperación selectiva y acepta el mismo deliveryId solo como retransmisión esperada', () => {
    const transport = new FakeTransport();
    const { client, worker } = createClient([transport]);

    client.connect();
    transport.open();
    welcome(transport);
    client.setViewport(800, 600, 1);
    client.selectImage(FIRST);
    manifest(transport, FIRST, 1);

    transport.receive({
      type: 'PLAN', epoch: 2, appliedLevel: 0, contextLevel: 0
    });
    transport.receiveBinary(tile(FIRST, 2, 7));

    const firstDecode = worker.posted.find(
      (message): message is { type: 'decode'; jobId: string } =>
        typeof message === 'object' && message !== null &&
        (message as { type?: string }).type === 'decode' &&
        (message as { jobId?: string }).jobId === '1:7'
    );
    expect(firstDecode).toBeDefined();

    worker.emit({
      type: 'failed',
      jobId: '1:7',
      connectionId: 1,
      epoch: 2,
      deliveryId: 7,
      reason: 'synthetic decode failure'
    });

    expect(transport.sent.at(-1)).toEqual({
      type: 'ACK_STATE',
      epoch: 2,
      received: [],
      missing: [7]
    });

    transport.receiveBinary(tile(FIRST, 2, 7));

    worker.emit({
      type: 'discarded',
      jobId: '1:7',
      connectionId: 1,
      epoch: 2,
      deliveryId: 7,
      reason: 'test terminal acknowledgement'
    });

    expect(transport.sent.at(-1)).toEqual({
      type: 'ACK_STATE',
      epoch: 2,
      received: [[7, 7]],
      missing: []
    });

    expect(
      client.snapshot().trace.some(
        (entry) => entry.event === 'RETRANSMIT_ACCEPTED'
      )
    ).toBe(true);

    client.destroy();
  });

  it('termina un plan fallido sin confundir ERROR con DONE', () => {
    const transport = new FakeTransport();
    const { client } = createClient([transport]);

    client.connect();
    transport.open();
    welcome(transport);
    client.setViewport(800, 600, 1);
    client.selectImage(FIRST);
    manifest(transport, FIRST, 1);
    transport.receive({
      type: 'PLAN', epoch: 2, appliedLevel: 0, contextLevel: 0
    });
    transport.receive({
      type: 'ERROR', epoch: 2, code: 'INTERNAL_READ_ERROR', message: 'tile read failed'
    });

    expect(client.snapshot().phase).toBe('observing');
    expect(client.snapshot().plan).toBeNull();
    expect(client.snapshot().serverDone).toBe(false);
    expect(client.snapshot().error).toContain('consulta la traza');
    client.destroy();
  });
});
