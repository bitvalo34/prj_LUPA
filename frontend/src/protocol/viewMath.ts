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
