import type { FocusPoint, Manifest, TileHeader, ViewLayout } from '../protocol/types';
import { tileDestination, type ViewRect } from '../protocol/viewMath';

interface BitmapEntry {
  key: string;
  header: TileHeader;
  bitmap: ImageBitmap;
  bytes: number;
  kind: 'context' | 'detail';
  presented: boolean;
  onPresented: () => void;
  onDiscarded: () => void;
}

export class CanvasCompositor {
  private manifest: Manifest | null = null;
  private layout: ViewLayout | null = null;
  private readonly entries = new Map<string, BitmapEntry>();
  private frame = 0;
  private currentEpoch = 0;
  private viewRect: ViewRect | null = null;
  private focus: FocusPoint | null = null;

  constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly onRemoved: (kind: 'context' | 'detail', bytes: number) => void
  ) {}

  startImage(manifest: Manifest, layout: ViewLayout, viewRect: ViewRect, focus: FocusPoint | null): void {
    this.reset();
    this.manifest = manifest;
    this.viewRect = { ...viewRect };
    this.focus = focus ? { ...focus } : null;
    this.setLayout(layout);
  }

  setLayout(layout: ViewLayout): void {
    this.layout = layout;
    this.canvas.width = layout.canvasWidth;
    this.canvas.height = layout.canvasHeight;
    this.canvas.style.width = layout.cssWidth + 'px';
    this.canvas.style.height = layout.cssHeight + 'px';
    this.schedulePaint();
  }

  setView(viewRect: ViewRect, focus: FocusPoint | null): void {
    this.viewRect = { ...viewRect };
    this.focus = focus ? { ...focus } : null;
    this.schedulePaint();
  }

  beginEpoch(epoch: number, viewRect: ViewRect, focus: FocusPoint | null): void {
    this.currentEpoch = epoch;
    this.viewRect = { ...viewRect };
    this.focus = focus ? { ...focus } : null;
    for (const [key, entry] of this.entries) {
      if (entry.header.z !== 0) {
        if (!entry.presented) entry.onDiscarded();
        entry.bitmap.close();
        this.entries.delete(key);
        this.onRemoved(entry.kind, entry.bytes);
      }
    }
    this.schedulePaint();
  }

  store(
    header: TileHeader,
    bitmap: ImageBitmap,
    bytes: number,
    kind: 'context' | 'detail',
    onPresented: () => void,
    onDiscarded: () => void
  ): boolean {
    if (!this.manifest || header.imageId !== this.manifest.imageId || header.imageVersion !== this.manifest.imageVersion) {
      bitmap.close();
      return false;
    }
    if (header.z !== 0 && header.epoch !== this.currentEpoch) {
      bitmap.close();
      return false;
    }
    const key = cacheKey(header);
    const previous = this.entries.get(key);
    if (previous) {
      if (!previous.presented) previous.onDiscarded();
      previous.bitmap.close();
      this.onRemoved(previous.kind, previous.bytes);
    }
    this.entries.set(key, {
      key,
      header,
      bitmap,
      bytes,
      kind,
      presented: false,
      onPresented,
      onDiscarded
    });
    this.schedulePaint();
    return true;
  }

  reset(): void {
    if (this.frame) cancelAnimationFrame(this.frame);
    this.frame = 0;
    for (const entry of this.entries.values()) {
      if (!entry.presented) entry.onDiscarded();
      entry.bitmap.close();
      this.onRemoved(entry.kind, entry.bytes);
    }
    this.entries.clear();
    this.manifest = null;
    this.layout = null;
    this.viewRect = null;
    this.focus = null;
    this.currentEpoch = 0;
    const context = this.canvas.getContext('2d');
    context?.clearRect(0, 0, this.canvas.width, this.canvas.height);
  }

  destroy(): void {
    this.reset();
  }

  count(): { context: number; detail: number } {
    let context = 0;
    let detail = 0;
    for (const entry of this.entries.values()) {
      if (entry.kind === 'context') context++;
      else detail++;
    }
    return { context, detail };
  }

  private schedulePaint(): void {
    if (this.frame !== 0) return;
    this.frame = requestAnimationFrame(() => {
      this.frame = 0;
      this.paint();
    });
  }

  private paint(): void {
    const manifest = this.manifest;
    const layout = this.layout;
    const viewRect = this.viewRect;
    const context = this.canvas.getContext('2d', { alpha: false });
    if (!manifest || !layout || !viewRect || !context) return;

    context.save();
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = 'high';
    context.fillStyle = '#171a20';
    context.fillRect(0, 0, this.canvas.width, this.canvas.height);

    const entries = Array.from(this.entries.values()).sort((a, b) => {
      if (a.kind !== b.kind) return a.kind === 'context' ? -1 : 1;
      if (a.header.z !== b.header.z) return a.header.z - b.header.z;
      if (a.header.y !== b.header.y) return a.header.y - b.header.y;
      return a.header.x - b.header.x;
    });

    context.beginPath();
    context.rect(
      layout.imageRectPx.x,
      layout.imageRectPx.y,
      layout.imageRectPx.width,
      layout.imageRectPx.height
    );
    context.clip();

    for (const entry of entries) {
      const destination = tileDestination(manifest, layout, entry.header, viewRect);
      context.drawImage(
        entry.bitmap,
        destination.x,
        destination.y,
        destination.width,
        destination.height
      );
      if (!entry.presented) {
        entry.presented = true;
        entry.onPresented();
      }
    }

    this.paintFocus(context, layout, viewRect);
    context.restore();
  }

  private paintFocus(context: CanvasRenderingContext2D, layout: ViewLayout, viewRect: ViewRect): void {
    const focus = this.focus;
    if (!focus) return;
    if (
      focus.x < viewRect.x || focus.y < viewRect.y ||
      focus.x > viewRect.x + viewRect.width || focus.y > viewRect.y + viewRect.height
    ) return;

    const x = layout.imageRectPx.x + ((focus.x - viewRect.x) / viewRect.width) * layout.imageRectPx.width;
    const y = layout.imageRectPx.y + ((focus.y - viewRect.y) / viewRect.height) * layout.imageRectPx.height;
    context.save();
    context.strokeStyle = 'rgba(245, 218, 108, 0.95)';
    context.lineWidth = Math.max(2, layout.effectiveDpr * 1.5);
    context.setLineDash([8 * layout.effectiveDpr, 5 * layout.effectiveDpr]);
    context.beginPath();
    context.arc(x, y, focus.radiusPx, 0, Math.PI * 2);
    context.stroke();
    context.setLineDash([]);
    context.beginPath();
    context.moveTo(x - 7 * layout.effectiveDpr, y);
    context.lineTo(x + 7 * layout.effectiveDpr, y);
    context.moveTo(x, y - 7 * layout.effectiveDpr);
    context.lineTo(x, y + 7 * layout.effectiveDpr);
    context.stroke();
    context.restore();
  }
}

export function cacheKey(header: TileHeader): string {
  return [
    header.imageId,
    header.imageVersion,
    header.z,
    header.x,
    header.y
  ].join(':');
}
