export const LUPA_VERSION = 1;
export const MAX_EPOCH = 2_147_483_647;
export const REQUESTED_WINDOW_BYTES = 1024 * 1024;
export const BITMAP_BUDGET_BYTES = 64 * 1024 * 1024;
export const TRANSIENT_BUDGET_BYTES = 16 * 1024 * 1024;
export const DETAIL_BUDGET_BYTES = BITMAP_BUDGET_BYTES - TRANSIENT_BUDGET_BYTES;
export const MAX_TILE_HEADER_BYTES = 4096;
export const MAX_TILE_PAYLOAD_BYTES = 262_144;
export const MAX_CANVAS_PIXELS = 8_294_400;

export interface CatalogImage {
  imageId: string;
  imageVersion: string;
  width: number;
  height: number;
  tileSize: number;
  maxLevel: number;
}

export interface Catalog {
  schemaVersion: 1;
  images: CatalogImage[];
}

export interface ImageLevel {
  z: number;
  width: number;
  height: number;
}

export interface Manifest {
  type: 'MANIFEST';
  epoch: number;
  imageId: string;
  imageVersion: string;
  width: number;
  height: number;
  levels: ImageLevel[];
  tileSize: number;
}

export interface Welcome {
  type: 'WELCOME';
  version: number;
  windowBytes: number;
  maxTileBytes: number;
  maxInFlight: number;
}

export interface Plan {
  type: 'PLAN';
  epoch: number;
  appliedLevel: number;
  contextLevel: number;
}

export interface Done {
  type: 'DONE';
  epoch: number;
  sentTiles: number;
}

export interface LupaError {
  type: 'ERROR';
  epoch?: number;
  code:
    | 'VERSION_UNSUPPORTED'
    | 'BAD_VIEW'
    | 'IMAGE_NOT_FOUND'
    | 'IMAGE_NOT_READY'
    | 'LIMIT_EXCEEDED'
    | 'INTERNAL_READ_ERROR';
  message: string;
}

export interface TileHeader {
  type: 'TILE';
  deliveryId: number;
  epoch: number;
  imageId: string;
  imageVersion: string;
  z: number;
  x: number;
  y: number;
  w: number;
  h: number;
  codec: 'jpeg';
  payloadBytes: number;
}

export interface ParsedTile {
  header: TileHeader;
  buffer: ArrayBuffer;
  payloadOffset: number;
  payloadBytes: number;
}

export interface ViewLayout {
  cssWidth: number;
  cssHeight: number;
  effectiveDpr: number;
  canvasWidth: number;
  canvasHeight: number;
  imageRectPx: {
    x: number;
    y: number;
    width: number;
    height: number;
  };
  viewportPx: {
    width: number;
    height: number;
  };
}

export type ReleaseStatus = 'displayed' | 'discarded' | 'failed';

export interface WorkerDecodeJob {
  type: 'decode';
  jobId: string;
  connectionId: number;
  epoch: number;
  header: TileHeader;
  buffer: ArrayBuffer;
  payloadOffset: number;
  payloadBytes: number;
}

export interface WorkerInvalidate {
  type: 'invalidate';
  connectionId: number;
  minEpoch: number;
}

export interface WorkerReset {
  type: 'reset';
  connectionId: number;
}

export type WorkerRequest = WorkerDecodeJob | WorkerInvalidate | WorkerReset;

export type WorkerResponse =
  | {
      type: 'decoded';
      jobId: string;
      connectionId: number;
      epoch: number;
      deliveryId: number;
      header: TileHeader;
      bitmap: ImageBitmap;
    }
  | {
      type: 'discarded';
      jobId: string;
      connectionId: number;
      epoch: number;
      deliveryId: number;
      reason: string;
    }
  | {
      type: 'failed';
      jobId: string;
      connectionId: number;
      epoch: number;
      deliveryId: number;
      reason: string;
    };
