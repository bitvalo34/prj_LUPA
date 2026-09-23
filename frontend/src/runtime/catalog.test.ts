import { describe, expect, it } from 'vitest';
import {
  catalogLoadFailed,
  catalogLoadStarted,
  catalogLoadSucceeded,
  hasNewCatalogVersion,
  type CatalogLoadState
} from './catalog';

const catalog = {
  schemaVersion: 1 as const,
  images: [
    { imageId: 'primera', imageVersion: 'v1', width: 100, height: 80, tileSize: 256, maxLevel: 0 }
  ]
};

describe('estado del catálogo A22', () => {
  it('conserva el último catálogo válido cuando una recarga falla', () => {
    const previous: CatalogLoadState = { catalog, busy: true, error: null };
    const failed = catalogLoadFailed(previous, new Error('Catálogo HTTP 503'));

    expect(failed.catalog).toBe(catalog);
    expect(failed.busy).toBe(false);
    expect(failed.error).toBe('Catálogo HTTP 503');
  });

  it('limpia el aviso al iniciar y reemplaza el catálogo solo al tener éxito', () => {
    const previous: CatalogLoadState = { catalog, busy: false, error: 'fallo anterior' };
    expect(catalogLoadStarted(previous)).toEqual({ catalog, busy: true, error: null });

    const next = { ...catalog, images: [{ ...catalog.images[0]!, imageVersion: 'v2' }] };
    expect(catalogLoadSucceeded(next)).toEqual({ catalog: next, busy: false, error: null });
  });

  it('detecta una versión nueva sin confundirla con otra imagen', () => {
    const active = {
      type: 'MANIFEST' as const,
      epoch: 3,
      imageId: 'primera',
      imageVersion: 'v1',
      width: 100,
      height: 80,
      tileSize: 256,
      levels: [{ z: 0, width: 100, height: 80 }]
    };

    expect(hasNewCatalogVersion({ ...catalog.images[0]!, imageVersion: 'v2' }, active)).toBe(true);
    expect(hasNewCatalogVersion(catalog.images[0]!, active)).toBe(false);
    expect(hasNewCatalogVersion({ ...catalog.images[0]!, imageId: 'otra', imageVersion: 'v2' }, active)).toBe(false);
  });
});
