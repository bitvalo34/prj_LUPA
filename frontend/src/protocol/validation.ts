import type {
  Catalog,
  CatalogImage,
  Done,
  ImageLevel,
  LupaError,
  Manifest,
  Plan,
  TileHeader,
  Welcome
} from './types';

const IMAGE_ID = /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/;
const IMAGE_VERSION = /^v[1-9][0-9]{0,9}$/;
const MAX_INT = 2_147_483_647;

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function requireInt(
  object: Record<string, unknown>,
  field: string,
  min = 0,
  max = MAX_INT
): number {
  const value = object[field];
  if (!Number.isSafeInteger(value) || (value as number) < min || (value as number) > max) {
    throw new Error(field + ' debe ser un entero válido');
  }
  return value as number;
}

export function requireString(
  object: Record<string, unknown>,
  field: string,
  maxLength = 256
): string {
  const value = object[field];
  if (typeof value !== 'string' || value.length === 0 || value.length > maxLength) {
    throw new Error(field + ' debe ser texto no vacío');
  }
  return value;
}

export function parseCatalog(value: unknown): Catalog {
  if (!isRecord(value)) throw new Error('El catálogo debe ser un objeto JSON');
  if (requireInt(value, 'schemaVersion', 1, 1) !== 1) throw new Error('schemaVersion no soportado');
  if (!Array.isArray(value.images)) throw new Error('images debe ser un arreglo');

  const images: CatalogImage[] = value.images.map((item, index) => {
    if (!isRecord(item)) throw new Error('images[' + index + '] debe ser un objeto');
    const imageId = requireString(item, 'imageId', 64);
    const imageVersion = requireString(item, 'imageVersion', 11);
    if (!IMAGE_ID.test(imageId)) throw new Error('imageId inválido: ' + imageId);
    if (!IMAGE_VERSION.test(imageVersion)) throw new Error('imageVersion inválida: ' + imageVersion);
    return {
      imageId,
      imageVersion,
      width: requireInt(item, 'width', 1),
      height: requireInt(item, 'height', 1),
      tileSize: requireInt(item, 'tileSize', 256, 256),
      maxLevel: requireInt(item, 'maxLevel', 0, 63)
    };
  });

  return { schemaVersion: 1, images };
}

export function parseWelcome(value: unknown): Welcome {
  if (!isRecord(value) || value.type !== 'WELCOME') throw new Error('WELCOME inválido');
  return {
    type: 'WELCOME',
    version: requireInt(value, 'version', 1),
    windowBytes: requireInt(value, 'windowBytes', 512 * 1024, 1024 * 1024),
    maxTileBytes: requireInt(value, 'maxTileBytes', 1, 262_144),
    maxInFlight: requireInt(value, 'maxInFlight', 1, 16)
  };
}

export function parseManifest(value: unknown, imageIdExpected: string, epochExpected: number): Manifest {
  if (!isRecord(value) || value.type !== 'MANIFEST') throw new Error('MANIFEST inválido');
  const epoch = requireInt(value, 'epoch', 1);
  const imageId = requireString(value, 'imageId', 64);
  const imageVersion = requireString(value, 'imageVersion', 11);
  if (epoch !== epochExpected) throw new Error('MANIFEST pertenece a otra época');
  if (imageId !== imageIdExpected) throw new Error('MANIFEST pertenece a otra imagen');
  if (!IMAGE_VERSION.test(imageVersion)) throw new Error('imageVersion inválida');
  const width = requireInt(value, 'width', 1);
  const height = requireInt(value, 'height', 1);
  const tileSize = requireInt(value, 'tileSize', 256, 256);
  if (!Array.isArray(value.levels) || value.levels.length < 1 || value.levels.length > 64) {
    throw new Error('levels debe tener entre 1 y 64 elementos');
  }
  const levels: ImageLevel[] = value.levels.map((item, index) => {
    if (!isRecord(item)) throw new Error('Nivel inválido');
    const z = requireInt(item, 'z', 0, 63);
    if (z !== index) throw new Error('Los niveles deben ser continuos desde 0');
    return { z, width: requireInt(item, 'width', 1), height: requireInt(item, 'height', 1) };
  });
  const first = levels[0]!;
  const last = levels[levels.length - 1]!;
  if (first.width > tileSize || first.height > tileSize) throw new Error('z=0 no cabe en una tesela');
  if (last.width !== width || last.height !== height) throw new Error('Nivel máximo incoherente');
  const maxLevel = levels.length - 1;
  for (const level of levels) {
    const divisor = 2 ** (maxLevel - level.z);
    const expectedWidth = Math.ceil(width / divisor);
    const expectedHeight = Math.ceil(height / divisor);
    if (level.width !== expectedWidth || level.height !== expectedHeight) {
      throw new Error('Dimensiones de nivel no cumplen la pirámide S19 en z=' + level.z);
    }
  }
  return { type: 'MANIFEST', epoch, imageId, imageVersion, width, height, levels, tileSize };
}

export function parsePlan(value: unknown, manifest: Manifest): Plan {
  if (!isRecord(value) || value.type !== 'PLAN') throw new Error('PLAN inválido');
  const maxLevel = manifest.levels.length - 1;
  return {
    type: 'PLAN',
    epoch: requireInt(value, 'epoch', 1),
    appliedLevel: requireInt(value, 'appliedLevel', 0, maxLevel),
    contextLevel: requireInt(value, 'contextLevel', 0, maxLevel)
  };
}

export function parseDone(value: unknown): Done {
  if (!isRecord(value) || value.type !== 'DONE') throw new Error('DONE inválido');
  return { type: 'DONE', epoch: requireInt(value, 'epoch', 1), sentTiles: requireInt(value, 'sentTiles', 0) };
}

export function parseError(value: unknown): LupaError {
  if (!isRecord(value) || value.type !== 'ERROR') throw new Error('ERROR inválido');
  const code = requireString(value, 'code', 64);
  const allowed = new Set([
    'VERSION_UNSUPPORTED',
    'BAD_VIEW',
    'IMAGE_NOT_FOUND',
    'IMAGE_NOT_READY',
    'LIMIT_EXCEEDED',
    'INTERNAL_READ_ERROR'
  ]);
  if (!allowed.has(code)) throw new Error('Código ERROR desconocido');
  const result: LupaError = {
    type: 'ERROR',
    code: code as LupaError['code'],
    message: requireString(value, 'message', 512)
  };
  if (value.epoch !== undefined) result.epoch = requireInt(value, 'epoch', 1);
  return result;
}

export function parseTileHeader(value: unknown): TileHeader {
  if (!isRecord(value) || value.type !== 'TILE') throw new Error('Cabecera TILE inválida');
  const codec = requireString(value, 'codec', 16);
  if (codec !== 'jpeg') throw new Error('codec debe ser jpeg');
  const imageId = requireString(value, 'imageId', 64);
  const imageVersion = requireString(value, 'imageVersion', 11);
  if (!IMAGE_ID.test(imageId) || !IMAGE_VERSION.test(imageVersion)) throw new Error('Identidad TILE inválida');
  const retry = value.retry === undefined ? undefined : requireInt(value, 'retry', 1, 2);
  return {
    type: 'TILE',
    deliveryId: requireInt(value, 'deliveryId', 1),
    epoch: requireInt(value, 'epoch', 1),
    imageId,
    imageVersion,
    z: requireInt(value, 'z', 0, 63),
    x: requireInt(value, 'x', 0),
    y: requireInt(value, 'y', 0),
    w: requireInt(value, 'w', 1, 256),
    h: requireInt(value, 'h', 1, 256),
    codec: 'jpeg',
    payloadBytes: requireInt(value, 'payloadBytes', 1, 262_144),
    ...(retry === undefined ? {} : { retry })
  };
}

export function parseJsonControl(text: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new Error('Control JSON malformado');
  }
  if (!isRecord(value) || typeof value.type !== 'string') throw new Error('Control sin type');
  return value;
}
