import { MAX_CANVAS_PIXELS, type Manifest, type TileHeader, type ViewLayout } from './types';

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
  header: TileHeader
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
    x: layout.imageRectPx.x + (originalX0 / manifest.width) * layout.imageRectPx.width,
    y: layout.imageRectPx.y + (originalY0 / manifest.height) * layout.imageRectPx.height,
    width: ((originalX1 - originalX0) / manifest.width) * layout.imageRectPx.width,
    height: ((originalY1 - originalY0) / manifest.height) * layout.imageRectPx.height
  };
}
