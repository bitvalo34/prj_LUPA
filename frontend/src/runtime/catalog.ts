import type { Catalog } from '../protocol/types';
import { parseCatalog } from '../protocol/validation';

export async function loadCatalog(signal?: AbortSignal): Promise<Catalog> {
  const response = await fetch('/api/catalog', { cache: 'no-store', signal });
  if (!response.ok) throw new Error('Catálogo HTTP ' + response.status);
  return parseCatalog(await response.json());
}
