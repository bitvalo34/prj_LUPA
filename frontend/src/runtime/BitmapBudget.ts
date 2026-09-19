import { BITMAP_BUDGET_BYTES, DETAIL_BUDGET_BYTES, TRANSIENT_BUDGET_BYTES } from '../protocol/types';

export type StoredKind = 'context' | 'detail';

export class BitmapBudget {
  private readonly reservations = new Map<string, number>();
  private transientBytes = 0;
  private contextBytes = 0;
  private detailBytes = 0;

  reserve(jobId: string, bytes: number): boolean {
    if (!Number.isSafeInteger(bytes) || bytes <= 0 || this.reservations.has(jobId)) return false;
    if (this.transientBytes + bytes > TRANSIENT_BUDGET_BYTES) return false;
    if (this.transientBytes + this.contextBytes + this.detailBytes + bytes > BITMAP_BUDGET_BYTES) return false;
    this.reservations.set(jobId, bytes);
    this.transientBytes += bytes;
    return true;
  }

  cancel(jobId: string): void {
    const bytes = this.reservations.get(jobId);
    if (bytes === undefined) return;
    this.reservations.delete(jobId);
    this.transientBytes -= bytes;
  }

  commit(jobId: string, kind: StoredKind): number | null {
    const bytes = this.reservations.get(jobId);
    if (bytes === undefined) return null;
    this.reservations.delete(jobId);
    this.transientBytes -= bytes;
    if (kind === 'context') {
      if (this.contextBytes + bytes > TRANSIENT_BUDGET_BYTES) return null;
      this.contextBytes += bytes;
    } else {
      if (this.detailBytes + bytes > DETAIL_BUDGET_BYTES) return null;
      this.detailBytes += bytes;
    }
    return bytes;
  }

  removeStored(kind: StoredKind, bytes: number): void {
    if (kind === 'context') this.contextBytes = Math.max(0, this.contextBytes - bytes);
    else this.detailBytes = Math.max(0, this.detailBytes - bytes);
  }

  reset(): void {
    this.reservations.clear();
    this.transientBytes = 0;
    this.contextBytes = 0;
    this.detailBytes = 0;
  }

  snapshot() {
    return {
      transientBytes: this.transientBytes,
      contextBytes: this.contextBytes,
      detailBytes: this.detailBytes,
      managedBytes: this.transientBytes + this.contextBytes + this.detailBytes,
      totalBudgetBytes: BITMAP_BUDGET_BYTES
    };
  }
}
