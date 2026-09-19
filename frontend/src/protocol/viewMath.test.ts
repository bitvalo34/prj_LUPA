import { describe, expect, it } from 'vitest';
import type { Manifest, TileHeader } from './types';
import { centeredTestRegion, computeFitLayout, tileDestination, viewportForRect } from './viewMath';

describe('computeFitLayout', () => {
  it('preserva proporción y excluye bandas del viewport físico', () => {
    const layout = computeFitLayout(1000, 500, 800, 600, 2);
    expect(layout.canvasWidth).toBe(1600);
    expect(layout.canvasHeight).toBe(1200);
    expect(layout.viewportPx).toEqual({ width: 1600, height: 800 });
    expect(layout.imageRectPx.y).toBe(200);
  });

  it('reduce DPR para respetar el límite de canvas', () => {
    const layout = computeFitLayout(1000, 1000, 3000, 2000, 3);
    expect(layout.canvasWidth * layout.canvasHeight).toBeLessThanOrEqual(8_294_400);
    expect(layout.effectiveDpr).toBeLessThan(3);
  });
});

describe('tileDestination', () => {
  it('coloca una tesela de borde sin deformar', () => {
    const manifest: Manifest = {
      type: 'MANIFEST',
      epoch: 1,
      imageId: 'photo',
      imageVersion: 'v1',
      width: 1096,
      height: 815,
      tileSize: 256,
      levels: [{ z: 0, width: 137, height: 102 }, { z: 1, width: 1096, height: 815 }]
    };
    const layout = computeFitLayout(1096, 815, 800, 600, 1);
    const header: TileHeader = {
      type: 'TILE', deliveryId: 1, epoch: 2, imageId: 'photo', imageVersion: 'v1',
      z: 1, x: 4, y: 3, w: 72, h: 47, codec: 'jpeg', payloadBytes: 10
    };
    const destination = tileDestination(manifest, layout, header);
    expect(destination.width).toBeGreaterThan(0);
    expect(destination.height).toBeGreaterThan(0);
    expect(destination.x + destination.width).toBeCloseTo(layout.imageRectPx.x + layout.imageRectPx.width, 5);
    expect(destination.y + destination.height).toBeCloseTo(layout.imageRectPx.y + layout.imageRectPx.height, 5);
  });
});


describe('I20 partial region helpers', () => {
  it('builds a strictly smaller centered region', () => {
    const manifest: Manifest = {
      type: 'MANIFEST', epoch: 1, imageId: 'demo', imageVersion: 'v1',
      width: 1096, height: 815, tileSize: 256,
      levels: [
        { z: 0, width: 137, height: 102 },
        { z: 1, width: 274, height: 204 },
        { z: 2, width: 548, height: 408 },
        { z: 3, width: 1096, height: 815 }
      ]
    };
    const rect = centeredTestRegion(manifest);
    expect(rect.width).toBeLessThan(manifest.width);
    expect(rect.height).toBeLessThan(manifest.height);
    expect(rect.x).toBeGreaterThan(0);
    expect(rect.y).toBeGreaterThan(0);
  });

  it('scales viewportPx to the physical footprint of the partial region', () => {
    const manifest: Manifest = {
      type: 'MANIFEST', epoch: 1, imageId: 'demo', imageVersion: 'v1',
      width: 1000, height: 500, tileSize: 256,
      levels: [{ z: 0, width: 250, height: 125 }, { z: 1, width: 500, height: 250 }, { z: 2, width: 1000, height: 500 }]
    };
    const layout = computeFitLayout(1000, 500, 800, 600, 1);
    const viewport = viewportForRect(manifest, layout, { x: 250, y: 125, width: 500, height: 250 });
    expect(viewport).toEqual({ width: 400, height: 200 });
  });
});
