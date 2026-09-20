import { MAX_CANVAS_PIXELS, type Manifest, type TileHeader, type ViewLayout } from './types';

export interface ViewRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export function computeFitLayout(
  imageWidth: number,
  imageHeight: number,
  cssWidth: number,
  cssHeight: number,
  devicePixelRatio: number,
  maxCanvasPixels = MAX_CANVAS_PIXELS
): ViewLayout {
  const values = [imageWidth, imageHeight, cssWidth, cssHeight, devicePixelRatio];
  if (!values.every(Number.isFinite) || values.some((value) => value <= 0)) {
    throw new Error('Dimensiones inválidas');
  }
  const maxDprByPixels = Math.sqrt(maxCanvasPixels / (cssWidth * cssHeight));
  const effectiveDpr = Math.min(devicePixelRatio, maxDprByPixels);
  const canvasWidth = Math.max(1, Math.floor(cssWidth * effectiveDpr));
  const canvasHeight = Math.max(1, Math.floor(cssHeight * effectiveDpr));
  const cssScale = Math.min(cssWidth / imageWidth, cssHeight / imageHeight);
  const imageWidthPx = Math.max(1, Math.round(imageWidth * cssScale * effectiveDpr));
  const imageHeightPx = Math.max(1, Math.round(imageHeight * cssScale * effectiveDpr));

  return {
    cssWidth,
    cssHeight,
    effectiveDpr,
    canvasWidth,
    canvasHeight,
    imageRectPx: {
      x: (canvasWidth - imageWidthPx) / 2,
      y: (canvasHeight - imageHeightPx) / 2,
      width: imageWidthPx,
      height: imageHeightPx
    },
    viewportPx: { width: imageWidthPx, height: imageHeightPx }
  };
}

export function tileDestination(
  manifest: Manifest,
  layout: ViewLayout,
  header: TileHeader,
  viewRect: ViewRect = fullViewRect(manifest)
): { x: number; y: number; width: number; height: number } {
  const level = manifest.levels[header.z];
  if (!level) throw new Error('Nivel inexistente');
  const levelX = header.x * manifest.tileSize;
  const levelY = header.y * manifest.tileSize;
  const originalX0 = (levelX / level.width) * manifest.width;
  const originalY0 = (levelY / level.height) * manifest.height;
  const originalX1 = ((levelX + header.w) / level.width) * manifest.width;
  const originalY1 = ((levelY + header.h) / level.height) * manifest.height;
  return {
    x: layout.imageRectPx.x + ((originalX0 - viewRect.x) / viewRect.width) * layout.imageRectPx.width,
    y: layout.imageRectPx.y + ((originalY0 - viewRect.y) / viewRect.height) * layout.imageRectPx.height,
    width: ((originalX1 - originalX0) / viewRect.width) * layout.imageRectPx.width,
    height: ((originalY1 - originalY0) / viewRect.height) * layout.imageRectPx.height
  };
}


export function fullViewRect(manifest: Manifest): ViewRect {
  return { x: 0, y: 0, width: manifest.width, height: manifest.height };
}

export function centeredTestRegion(manifest: Manifest): ViewRect {
  const width = Math.max(1, Math.floor(manifest.width / 2));
  const height = Math.max(1, Math.floor(manifest.height / 2));
  return {
    x: Math.floor((manifest.width - width) / 2),
    y: Math.floor((manifest.height - height) / 2),
    width,
    height
  };
}

export function viewportForRect(
  manifest: Manifest,
  layout: ViewLayout,
  rect: ViewRect
): { width: number; height: number } {
  if (
    rect.x < 0 || rect.y < 0 || rect.width < 1 || rect.height < 1 ||
    rect.x + rect.width > manifest.width ||
    rect.y + rect.height > manifest.height
  ) {
    throw new Error('Región VIEW fuera de la imagen');
  }
  return {
    width: Math.max(1, Math.round(layout.imageRectPx.width * rect.width / manifest.width)),
    height: Math.max(1, Math.round(layout.imageRectPx.height * rect.height / manifest.height))
  };
}

export function viewportForNavigation(layout: ViewLayout): { width: number; height: number } {
  return {
    width: Math.max(1, Math.round(layout.imageRectPx.width)),
    height: Math.max(1, Math.round(layout.imageRectPx.height))
  };
}

export function canvasPointToImage(
  layout: ViewLayout,
  rect: ViewRect,
  cssX: number,
  cssY: number
): { x: number; y: number } | null {
  const xPx = cssX * layout.effectiveDpr;
  const yPx = cssY * layout.effectiveDpr;
  const image = layout.imageRectPx;
  if (
    xPx < image.x || yPx < image.y ||
    xPx > image.x + image.width || yPx > image.y + image.height
  ) return null;

  return {
    x: rect.x + ((xPx - image.x) / image.width) * rect.width,
    y: rect.y + ((yPx - image.y) / image.height) * rect.height
  };
}

export function zoomViewRect(
  manifest: Manifest,
  rect: ViewRect,
  anchor: { x: number; y: number },
  factor: number,
  maxZoom = 128
): ViewRect {
  if (!Number.isFinite(factor) || factor <= 0) throw new Error('Factor de zoom inválido');
  const minWidth = Math.max(1, manifest.width / maxZoom);
  const minHeight = Math.max(1, manifest.height / maxZoom);
  const width = clamp(rect.width * factor, minWidth, manifest.width);
  const height = clamp(rect.height * factor, minHeight, manifest.height);
  const anchorX = clamp((anchor.x - rect.x) / rect.width, 0, 1);
  const anchorY = clamp((anchor.y - rect.y) / rect.height, 0, 1);
  return clampViewRect(manifest, {
    x: anchor.x - width * anchorX,
    y: anchor.y - height * anchorY,
    width,
    height
  });
}

export function panViewRect(
  manifest: Manifest,
  rect: ViewRect,
  deltaX: number,
  deltaY: number
): ViewRect {
  return clampViewRect(manifest, {
    ...rect,
    x: rect.x + deltaX,
    y: rect.y + deltaY
  });
}

export function clampViewRect(manifest: Manifest, rect: ViewRect): ViewRect {
  const width = clamp(rect.width, 1, manifest.width);
  const height = clamp(rect.height, 1, manifest.height);
  return {
    x: clamp(rect.x, 0, manifest.width - width),
    y: clamp(rect.y, 0, manifest.height - height),
    width,
    height
  };
}

export function integerViewRect(manifest: Manifest, rect: ViewRect): ViewRect {
  const width = Math.max(1, Math.min(manifest.width, Math.round(rect.width)));
  const height = Math.max(1, Math.min(manifest.height, Math.round(rect.height)));
  return {
    x: Math.max(0, Math.min(manifest.width - width, Math.round(rect.x))),
    y: Math.max(0, Math.min(manifest.height - height, Math.round(rect.y))),
    width,
    height
  };
}

export function viewZoom(manifest: Manifest, rect: ViewRect): number {
  return Math.min(manifest.width / rect.width, manifest.height / rect.height);
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}
