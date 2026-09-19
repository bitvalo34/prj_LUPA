export interface TraceEntry {
  at: string;
  direction: 'IN' | 'OUT' | 'LOCAL';
  event: string;
  detail: string;
}

export class TraceBuffer {
  private readonly entries: TraceEntry[] = [];
  constructor(private readonly capacity = 160) {}

  push(direction: TraceEntry['direction'], event: string, detail: string): void {
    this.entries.push({
      at: new Date().toISOString(),
      direction,
      event,
      detail: detail.length > 240 ? detail.slice(0, 237) + '...' : detail
    });
    if (this.entries.length > this.capacity) this.entries.splice(0, this.entries.length - this.capacity);
  }

  snapshot(): TraceEntry[] {
    return this.entries.map((entry) => ({ ...entry }));
  }

  clear(): void {
    this.entries.length = 0;
  }
}
