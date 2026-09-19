import type { LupaTransport, TransportHandlers } from './transport';

export const SAMPLE_CATALOG = {
  schemaVersion: 1 as const,
  images: [
    {
      imageId: 'sample-world',
      imageVersion: 'v1',
      width: 512,
      height: 384,
      tileSize: 256,
      maxLevel: 1
    }
  ]
};

const SAMPLE_MANIFEST = {
  type: 'MANIFEST',
  epoch: 1,
  imageId: 'sample-world',
  imageVersion: 'v1',
  width: 512,
  height: 384,
  levels: [
    { z: 0, width: 256, height: 192 },
    { z: 1, width: 512, height: 384 }
  ],
  tileSize: 256
};

export class SampleTransport implements LupaTransport {
  private handlers: TransportHandlers | null = null;
  private opened = false;
  private nextDeliveryId = 1;
  private thumbnailSent = false;

  get isOpen(): boolean {
    return this.opened;
  }

  connect(handlers: TransportHandlers): void {
    this.handlers = handlers;
    this.opened = true;
    queueMicrotask(() => handlers.onOpen());
  }

  sendText(text: string): void {
    if (!this.opened || !this.handlers) throw new Error('Transporte de muestra cerrado');
    const message = JSON.parse(text) as Record<string, unknown>;
    if (message.type === 'HELLO') {
      this.emitText({
        type: 'WELCOME',
        version: 1,
        windowBytes: 1048576,
        maxTileBytes: 262144,
        maxInFlight: 16
      });
      return;
    }
    if (message.type === 'OPEN') {
      this.thumbnailSent = false;
      this.emitText({ ...SAMPLE_MANIFEST, epoch: message.epoch });
      return;
    }
    if (message.type === 'VIEW') {
      const epoch = Number(message.epoch);
      this.emitText({ type: 'PLAN', epoch, appliedLevel: 1, contextLevel: 1 });
      void this.emitTiles(epoch);
      return;
    }
    if (message.type === 'RELEASE') return;
    this.handlers.onError('Mensaje de muestra no soportado: ' + String(message.type));
  }

  close(code = 1000, reason = 'sample close'): void {
    if (!this.opened) return;
    this.opened = false;
    this.handlers?.onClose(code, reason);
    this.handlers = null;
  }

  private emitText(value: unknown): void {
    queueMicrotask(() => this.handlers?.onText(JSON.stringify(value)));
  }

  private async emitTiles(epoch: number): Promise<void> {
    const [thumb, full, edge] = await Promise.all([
      makeJpeg(256, 192, 11),
      makeJpeg(256, 256, 29),
      makeJpeg(256, 128, 47)
    ]);
    const tiles = [
      ...(!this.thumbnailSent ? [{ z: 0, x: 0, y: 0, w: 256, h: 192, payload: thumb }] : []),
      { z: 1, x: 0, y: 0, w: 256, h: 256, payload: full },
      { z: 1, x: 1, y: 0, w: 256, h: 256, payload: full.slice(0) },
      { z: 1, x: 0, y: 1, w: 256, h: 128, payload: edge },
      { z: 1, x: 1, y: 1, w: 256, h: 128, payload: edge.slice(0) }
    ];

    if (!this.thumbnailSent) this.thumbnailSent = true;
    for (const tile of tiles) {
      if (!this.opened || !this.handlers) return;
      await delay(45);
      const deliveryId = this.nextDeliveryId++;
      this.handlers.onBinary(
        buildEnvelope(
          {
            type: 'TILE',
            deliveryId,
            epoch,
            imageId: 'sample-world',
            imageVersion: 'v1',
            z: tile.z,
            x: tile.x,
            y: tile.y,
            w: tile.w,
            h: tile.h,
            codec: 'jpeg',
            payloadBytes: tile.payload.byteLength
          },
          tile.payload
        )
      );
    }
    this.emitText({ type: 'DONE', epoch, sentTiles: tiles.length });
  }
}

async function makeJpeg(width: number, height: number, seed: number): Promise<ArrayBuffer> {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Canvas de muestra no disponible');
  context.fillStyle = '#e7dcc0';
  context.fillRect(0, 0, width, height);
  for (let y = 0; y < height; y += 16) {
    for (let x = 0; x < width; x += 16) {
      const value = (x * 7 + y * 5 + seed * 13) % 255;
      context.fillStyle = 'rgb(' + value + ',' + ((value + 83) % 255) + ',' + ((255 - value + seed) % 255) + ')';
      context.fillRect(x, y, 16, 16);
    }
  }
  context.strokeStyle = '#efc84b';
  context.lineWidth = Math.max(2, Math.floor(width / 64));
  context.strokeRect(width * .2, height * .2, width * .6, height * .6);
  context.strokeStyle = '#df6656';
  context.beginPath();
  context.moveTo(0, height);
  context.lineTo(width, 0);
  context.stroke();
  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((result) => result ? resolve(result) : reject(new Error('No se pudo crear JPEG de muestra')), 'image/jpeg', .86);
  });
  return blob.arrayBuffer();
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function buildEnvelope(header: Record<string, unknown>, jpeg: ArrayBuffer): ArrayBuffer {
  const headerBytes = new TextEncoder().encode(JSON.stringify(header));
  const output = new ArrayBuffer(4 + headerBytes.byteLength + jpeg.byteLength);
  new DataView(output).setUint32(0, headerBytes.byteLength, false);
  new Uint8Array(output, 4, headerBytes.byteLength).set(headerBytes);
  new Uint8Array(output, 4 + headerBytes.byteLength).set(new Uint8Array(jpeg));
  return output;
}
