import type { Catalog, CatalogImage, Manifest } from '../protocol/types';
import { parseCatalog } from '../protocol/validation';

export interface CatalogLoadState {
  catalog: Catalog | null;
  busy: boolean;
  error: string | null;
}

export async function loadCatalog(signal?: AbortSignal): Promise<Catalog> {
  const response = await fetch('/api/catalog', { cache: 'no-store', signal });
  if (!response.ok) throw new Error('Catálogo HTTP ' + response.status);
  return parseCatalog(await response.json());
}

export function catalogLoadStarted(state: CatalogLoadState): CatalogLoadState {
  return { ...state, busy: true, error: null };
}

export function catalogLoadSucceeded(catalog: Catalog): CatalogLoadState {
  return { catalog, busy: false, error: null };
}

export function catalogLoadFailed(state: CatalogLoadState, error: unknown): CatalogLoadState {
  return {
    catalog: state.catalog,
    busy: false,
    error: error instanceof Error ? error.message : 'No se pudo cargar el catálogo'
  };
}

export function hasNewCatalogVersion(image: CatalogImage, manifest: Manifest | null): boolean {
  return manifest?.imageId === image.imageId && manifest.imageVersion !== image.imageVersion;
}
