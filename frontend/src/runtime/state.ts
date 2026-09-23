import type { LupaError } from '../protocol/types';

export type TransferPhase = 'receiving' | 'processing' | 'observing';

export interface TileAccounting {
  terminal: number;
  pending: number;
  accounted: number;
  balanced: boolean;
}

export function deriveTransferPhase(
  serverDone: boolean,
  pendingDecodes: number,
  pendingPresentations: number,
  hasPlan: boolean
): TransferPhase | null {
  if (serverDone && pendingDecodes === 0 && pendingPresentations === 0) return 'observing';
  if (pendingDecodes > 0 || pendingPresentations > 0) return 'processing';
  if (hasPlan) return 'receiving';
  return null;
}

export function deriveTileAccounting(
  received: number,
  drawn: number,
  discarded: number,
  failed: number,
  pendingDecodes: number,
  pendingPresentations: number
): TileAccounting {
  const terminal = drawn + discarded + failed;
  const pending = pendingDecodes + pendingPresentations;
  const accounted = terminal + pending;
  return { terminal, pending, accounted, balanced: accounted === received };
}

export function describeRemoteError(error: LupaError): string {
  const guidance: Record<LupaError['code'], string> = {
    VERSION_UNSUPPORTED: 'El servidor no acepta esta versión del protocolo. Reconecta con una compilación compatible.',
    BAD_VIEW: 'La región solicitada no es válida. Restablece la vista y vuelve a intentarlo.',
    IMAGE_NOT_FOUND: 'La imagen ya no está publicada. Recarga el catálogo y elige una imagen disponible.',
    IMAGE_NOT_READY: 'La imagen todavía no está lista. Espera a que termine la importación y vuelve a abrirla.',
    LIMIT_EXCEEDED: 'El servidor alcanzó un límite temporal. Espera un momento y vuelve a intentarlo.',
    INTERNAL_READ_ERROR: 'No se pudo leer una tesela publicada. Vuelve a abrir la imagen o consulta la traza.'
  };
  return guidance[error.code] + ' (' + error.code + ': ' + error.message + ')';
}

export function describeSocketClose(code: number, reason: string): string | null {
  if (code === 1000) return null;
  const suffix = reason.trim() ? ' Motivo: ' + reason.trim() + '.' : '';
  return 'La conexión se cerró (código ' + code + '). Pulsa Reconectar para abrir una sesión nueva.' + suffix;
}
