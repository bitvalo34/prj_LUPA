import type { ReleaseStatus } from '../protocol/types';

interface DeliveryState {
  connectionId: number;
}

const MAX_RELEASED_HISTORY = 2048;

export class DeliveryLedger {
  private readonly active = new Map<number, DeliveryState>();
  private readonly released = new Set<number>();

  register(deliveryId: number, connectionId: number): boolean {
    if (this.active.has(deliveryId) || this.released.has(deliveryId)) return false;
    this.active.set(deliveryId, { connectionId });
    return true;
  }

  releaseOnce(
    deliveryId: number,
    connectionId: number,
    status: ReleaseStatus,
    sender: (deliveryId: number, status: ReleaseStatus) => void
  ): boolean {
    const delivery = this.active.get(deliveryId);
    if (!delivery || delivery.connectionId !== connectionId) return false;

    this.active.delete(deliveryId);
    this.released.add(deliveryId);
    while (this.released.size > MAX_RELEASED_HISTORY) {
      const oldest = this.released.values().next().value as number | undefined;
      if (oldest === undefined) break;
      this.released.delete(oldest);
    }

    sender(deliveryId, status);
    return true;
  }

  clear(): void {
    this.active.clear();
    this.released.clear();
  }

  sizes(): { active: number; releasedHistory: number } {
    return { active: this.active.size, releasedHistory: this.released.size };
  }
}
