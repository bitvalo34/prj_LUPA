import { describe, expect, it } from 'vitest';
import type { Manifest, TileHeader } from './types';
import { parseTileEnvelope } from './tileEnvelope';

const manifest: Manifest = {
  type: 'MANIFEST',
  epoch: 1,
  imageId: 'sample-world',
  imageVersion: 'v1',
  width: 512,
  height: 384,
  tileSize: 256,
  levels: [
    { z: 0, width: 128, height: 96 },
    { z: 1, width: 512, height: 384 }
  ]
};

function envelope(header: TileHeader, bytes = new Uint8Array([0xff, 0xd8, 0xff, 0xd9])): ArrayBuffer {
  const headerBytes = new TextEncoder().encode(JSON.stringify(header));
  const output = new ArrayBuffer(4 + headerBytes.length + bytes.length);
  new DataView(output).setUint32(0, headerBytes.length, false);
  new Uint8Array(output, 4, headerBytes.length).set(headerBytes);
  new Uint8Array(output, 4 + headerBytes.length).set(bytes);
  return output;
}

describe('parseTileEnvelope', () => {
  it('acepta sobre válido y borde real', () => {
    const buffer = envelope({
      type: 'TILE',
      deliveryId: 7,
      epoch: 2,
      imageId: 'sample-world',
      imageVersion: 'v1',
      z: 1,
      x: 1,
      y: 1,
      w: 256,
      h: 128,
      codec: 'jpeg',
      payloadBytes: 4
    });
    const result = parseTileEnvelope(buffer, manifest);
    expect(result.header.deliveryId).toBe(7);
    expect(result.payloadBytes).toBe(4);
  });

  it('acepta metadata de retransmisión selectiva en TILE', () => {
    const buffer = envelope({
      type: 'TILE',
      deliveryId: 9,
      epoch: 2,
      imageId: 'sample-world',
      imageVersion: 'v1',
      z: 0,
      x: 0,
      y: 0,
      w: 128,
      h: 96,
      codec: 'jpeg',
      payloadBytes: 4,
      retry: 1
    });
    const result = parseTileEnvelope(buffer, manifest);
    expect(result.header.deliveryId).toBe(9);
    expect(result.header.retry).toBe(1);
  });

  it('rechaza longitud H y payload inconsistente', () => {
    const badH = new ArrayBuffer(8);
    new DataView(badH).setUint32(0, 5000, false);
    expect(() => parseTileEnvelope(badH, manifest)).toThrow(/H fuera/);

    const buffer = envelope({
      type: 'TILE',
      deliveryId: 1,
      epoch: 2,
      imageId: 'sample-world',
      imageVersion: 'v1',
      z: 0,
      x: 0,
      y: 0,
      w: 128,
      h: 96,
      codec: 'jpeg',
      payloadBytes: 99
    });
    expect(() => parseTileEnvelope(buffer, manifest)).toThrow(/payloadBytes/);
  });

  it('rechaza coordenadas o dimensiones fuera del MANIFEST', () => {
    const buffer = envelope({
      type: 'TILE',
      deliveryId: 1,
      epoch: 2,
      imageId: 'sample-world',
      imageVersion: 'v1',
      z: 1,
      x: 2,
      y: 0,
      w: 1,
      h: 256,
      codec: 'jpeg',
      payloadBytes: 4
    });
    expect(() => parseTileEnvelope(buffer, manifest)).toThrow(/Coordenadas/);
  });
});
