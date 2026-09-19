export type TransferPhase = 'receiving' | 'processing' | 'observing';

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
