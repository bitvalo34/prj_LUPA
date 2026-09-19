import type { ReleaseStatus } from '../protocol/types';

interface DeliveryState {
  connectionId: number;
  released: boolean;
}

export class DeliveryLedger {
  private readonly deliveries = new Map<number, DeliveryState>();

  register(deliveryId: number, connectionId: number): boolean {
    if (this.deliveries.has(deliveryId)) return false;
    this.deliveries.set(deliveryId, { connectionId, released: false });
    return true;
  }

  releaseOnce(
    deliveryId: number,
    connectionId: number,
    status: ReleaseStatus,
    sender: (deliveryId: number, status: ReleaseStatus) => void
  ): boolean {
    const delivery = this.deliveries.get(deliveryId);
    if (!delivery || delivery.released || delivery.connectionId !== connectionId) return false;
    delivery.released = true;
    sender(deliveryId, status);
    return true;
  }

  clear(): void {
    this.deliveries.clear();
  }
}
