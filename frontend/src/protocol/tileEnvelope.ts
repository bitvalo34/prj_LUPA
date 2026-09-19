import {
  MAX_TILE_HEADER_BYTES,
  MAX_TILE_PAYLOAD_BYTES,
  type Manifest,
  type ParsedTile
} from './types';
import { parseTileHeader } from './validation';

const decoder = new TextDecoder('utf-8', { fatal: true });

export function parseTileEnvelopeBase(
  buffer: ArrayBuffer,
  negotiatedMaxTileBytes = MAX_TILE_PAYLOAD_BYTES
): ParsedTile {
  if (buffer.byteLength < 5) throw new Error('TILE demasiado corto');
  const view = new DataView(buffer);
  const headerLength = view.getUint32(0, false);
  if (headerLength < 1 || headerLength > MAX_TILE_HEADER_BYTES) throw new Error('H fuera de límite');
  const payloadOffset = 4 + headerLength;
  if (payloadOffset >= buffer.byteLength) throw new Error('TILE sin JPEG');

  let text: string;
  try {
    text = decoder.decode(new Uint8Array(buffer, 4, headerLength));
  } catch {
    throw new Error('Cabecera TILE no es UTF-8 válido');
  }

  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error('Cabecera TILE no es JSON válido');
  }

  const header = parseTileHeader(json);
  const payloadBytes = buffer.byteLength - payloadOffset;
  if (payloadBytes !== header.payloadBytes) throw new Error('payloadBytes no coincide');
  if (payloadBytes > Math.min(negotiatedMaxTileBytes, MAX_TILE_PAYLOAD_BYTES)) {
    throw new Error('JPEG supera límite');
  }

  return { header, buffer, payloadOffset, payloadBytes };
}

export function validateTileAgainstManifest(tile: ParsedTile, manifest: Manifest): ParsedTile {
  const { header } = tile;
  if (header.imageId !== manifest.imageId || header.imageVersion !== manifest.imageVersion) {
    throw new Error('TILE pertenece a otra versión de imagen');
  }

  const level = manifest.levels[header.z];
  if (!level) throw new Error('Nivel TILE inexistente');
  const cols = Math.ceil(level.width / manifest.tileSize);
  const rows = Math.ceil(level.height / manifest.tileSize);
  if (header.x >= cols || header.y >= rows) throw new Error('Coordenadas TILE fuera del nivel');

  const expectedWidth = Math.min(manifest.tileSize, level.width - header.x * manifest.tileSize);
  const expectedHeight = Math.min(manifest.tileSize, level.height - header.y * manifest.tileSize);
  if (header.w !== expectedWidth || header.h !== expectedHeight) {
    throw new Error('Dimensiones TILE incoherentes');
  }
  return tile;
}

export function parseTileEnvelope(
  buffer: ArrayBuffer,
  manifest: Manifest,
  negotiatedMaxTileBytes = MAX_TILE_PAYLOAD_BYTES
): ParsedTile {
  return validateTileAgainstManifest(
    parseTileEnvelopeBase(buffer, negotiatedMaxTileBytes),
    manifest
  );
}
