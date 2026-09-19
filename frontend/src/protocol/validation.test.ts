import { describe, expect, it } from 'vitest';
import { parseCatalog, parseManifest, parseWelcome } from './validation';

describe('validación LUPA', () => {
  it('acepta catálogo S19 real', () => {
    const catalog = parseCatalog({
      schemaVersion: 1,
      images: [{ imageId: 'demo-auxiliar', imageVersion: 'v1', width: 1096, height: 815, tileSize: 256, maxLevel: 3 }]
    });
    expect(catalog.images[0]?.imageId).toBe('demo-auxiliar');
  });

  it('rechaza catálogo con tileSize incompatible', () => {
    expect(() => parseCatalog({
      schemaVersion: 1,
      images: [{ imageId: 'demo', imageVersion: 'v1', width: 1, height: 1, tileSize: 512, maxLevel: 0 }]
    })).toThrow();
  });

  it('valida límites negociados de WELCOME', () => {
    expect(parseWelcome({ type: 'WELCOME', version: 1, windowBytes: 524288, maxTileBytes: 262144, maxInFlight: 16 }).windowBytes)
      .toBe(524288);
    expect(() => parseWelcome({ type: 'WELCOME', version: 1, windowBytes: 1, maxTileBytes: 262144, maxInFlight: 16 }))
      .toThrow();
  });

  it('comprueba continuidad y nivel máximo de MANIFEST', () => {
    const manifest = parseManifest({
      type: 'MANIFEST', epoch: 1, imageId: 'demo', imageVersion: 'v1',
      width: 512, height: 384, tileSize: 256,
      levels: [{ z: 0, width: 256, height: 192 }, { z: 1, width: 512, height: 384 }]
    }, 'demo', 1);
    expect(manifest.levels).toHaveLength(2);

    expect(() => parseManifest({
      type: 'MANIFEST', epoch: 1, imageId: 'demo', imageVersion: 'v1',
      width: 512, height: 384, tileSize: 256,
      levels: [{ z: 0, width: 256, height: 192 }, { z: 2, width: 512, height: 384 }]
    }, 'demo', 1)).toThrow(/continuos/);

    const odd = parseManifest({
      type: 'MANIFEST', epoch: 3, imageId: 'odd', imageVersion: 'v1',
      width: 1097, height: 815, tileSize: 256,
      levels: [
        { z: 0, width: 138, height: 102 },
        { z: 1, width: 275, height: 204 },
        { z: 2, width: 549, height: 408 },
        { z: 3, width: 1097, height: 815 }
      ]
    }, 'odd', 3);
    expect(odd.levels[0]).toEqual({ z: 0, width: 138, height: 102 });
  });
});
