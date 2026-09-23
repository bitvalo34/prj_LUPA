import type { ReleaseStatus } from '../protocol/types';

interface DeliveryState {
  connectionId: number;
  retryExpected: boolean;
  retryRequests: number;
}

const MAX_RELEASED_HISTORY = 2048;
const MAX_SELECTIVE_RETRIES = 2;

export class DeliveryLedger {
  private readonly active = new Map<number, DeliveryState>();
  private readonly released = new Set<number>();

  register(deliveryId: number, connectionId: number): boolean {
    if (this.active.has(deliveryId) || this.released.has(deliveryId)) return false;
    this.active.set(deliveryId, {
      connectionId,
      retryExpected: false,
      retryRequests: 0
    });
    return true;
  }

  acceptRetransmission(deliveryId: number, connectionId: number): boolean {
    const delivery = this.active.get(deliveryId);
    if (!delivery || delivery.connectionId !== connectionId || !delivery.retryExpected) return false;
    delivery.retryExpected = false;
    return true;
  }

  requestRetry(
    deliveryId: number,
    connectionId: number,
    sender: (deliveryId: number) => void
  ): boolean {
    const delivery = this.active.get(deliveryId);
    if (
      !delivery ||
      delivery.connectionId !== connectionId ||
      delivery.retryExpected ||
      delivery.retryRequests >= MAX_SELECTIVE_RETRIES
    ) {
      return false;
    }

    delivery.retryExpected = true;
    delivery.retryRequests++;
    sender(deliveryId);
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
