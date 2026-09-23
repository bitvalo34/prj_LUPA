/// <reference lib="webworker" />

import type { WorkerDecodeJob, WorkerRequest, WorkerResponse } from '../protocol/types';

const MAX_ACTIVE = 2;
const MAX_QUEUED_JOBS = 32;
const MAX_QUEUED_BYTES = 8 * 1024 * 1024;

const queue: WorkerDecodeJob[] = [];
const minimumEpoch = new Map<number, number>();
let active = 0;
let queuedBytes = 0;
let postDecodeDelayMs = 0;
let failFirstDecodeOnce = false;

self.onmessage = (event: MessageEvent<WorkerRequest>) => {
  const message = event.data;

  if (message.type === 'configureDiagnostic') {
    postDecodeDelayMs = Math.max(0, Math.min(2000, Math.round(message.postDecodeDelayMs)));
    failFirstDecodeOnce = message.failFirstDecodeOnce;
    return;
  }

  if (message.type === 'decode') {
    if (queue.length >= MAX_QUEUED_JOBS || queuedBytes + message.payloadBytes > MAX_QUEUED_BYTES) {
      post({
        type: 'failed',
        jobId: message.jobId,
        connectionId: message.connectionId,
        epoch: message.epoch,
        deliveryId: message.header.deliveryId,
        reason: 'cola de decodificación llena'
      });
      return;
    }
    const min = minimumEpoch.get(message.connectionId) ?? 0;
    if (message.epoch < min) {
      post({
        type: 'discarded',
        jobId: message.jobId,
        connectionId: message.connectionId,
        epoch: message.epoch,
        deliveryId: message.header.deliveryId,
        reason: 'época obsoleta antes de decodificar'
      });
      return;
    }
    queue.push(message);
    queuedBytes += message.payloadBytes;
    pump();
    return;
  }

  if (message.type === 'invalidate') {
    minimumEpoch.set(message.connectionId, message.minEpoch);
    discardQueued(message.connectionId, message.minEpoch, false);
    return;
  }

  minimumEpoch.set(message.connectionId, Number.MAX_SAFE_INTEGER);
  discardQueued(message.connectionId, Number.MAX_SAFE_INTEGER, true);
};

function discardQueued(connectionId: number, minEpoch: number, all: boolean): void {
  for (let index = queue.length - 1; index >= 0; index--) {
    const job = queue[index]!;
    if (job.connectionId === connectionId && (all || job.epoch < minEpoch)) {
      queue.splice(index, 1);
      queuedBytes -= job.payloadBytes;
      post({
        type: 'discarded',
        jobId: job.jobId,
        connectionId: job.connectionId,
        epoch: job.epoch,
        deliveryId: job.header.deliveryId,
        reason: 'trabajo invalidado en cola'
      });
    }
  }
}

function pump(): void {
  while (active < MAX_ACTIVE && queue.length > 0) {
    const job = queue.shift()!;
    queuedBytes -= job.payloadBytes;
    active++;
    void decode(job).finally(() => {
      active--;
      pump();
    });
  }
}

async function decode(job: WorkerDecodeJob): Promise<void> {
  const minBefore = minimumEpoch.get(job.connectionId) ?? 0;
  if (job.epoch < minBefore) {
    postDiscard(job, 'época obsoleta antes de createImageBitmap');
    return;
  }

  try {
    if (failFirstDecodeOnce && job.header.retry === undefined) {
      failFirstDecodeOnce = false;
      postFailure(job, 'STA diagnostic: synthetic first decode failure');
      return;
    }

    const bytes = new Uint8Array(job.buffer, job.payloadOffset, job.payloadBytes);
    const blob = new Blob([bytes], { type: 'image/jpeg' });
    const bitmap = await createImageBitmap(blob);

    /*
     * I21-only diagnostic hook. Disabled by default. It deliberately widens the race window
     * after decode but before the second epoch check, without retaining compressed buffers
     * outside the normal job lifetime or changing production semantics.
     */
    if (postDecodeDelayMs > 0) {
      await new Promise<void>((resolve) => setTimeout(resolve, postDecodeDelayMs));
    }

    if (bitmap.width !== job.header.w || bitmap.height !== job.header.h) {
      bitmap.close();
      postFailure(job, 'dimensiones JPEG no coinciden con TILE');
      return;
    }

    const minAfter = minimumEpoch.get(job.connectionId) ?? 0;
    if (job.epoch < minAfter) {
      bitmap.close();
      postDiscard(job, 'época obsoleta después de decodificar');
      return;
    }

    const response: WorkerResponse = {
      type: 'decoded',
      jobId: job.jobId,
      connectionId: job.connectionId,
      epoch: job.epoch,
      deliveryId: job.header.deliveryId,
      header: job.header,
      bitmap
    };
    self.postMessage(response, { transfer: [bitmap] });
  } catch (error) {
    postFailure(job, error instanceof Error ? error.message : 'JPEG no decodificable');
  }
}

function postDiscard(job: WorkerDecodeJob, reason: string): void {
  post({
    type: 'discarded',
    jobId: job.jobId,
    connectionId: job.connectionId,
    epoch: job.epoch,
    deliveryId: job.header.deliveryId,
    reason
  });
}

function postFailure(job: WorkerDecodeJob, reason: string): void {
  post({
    type: 'failed',
    jobId: job.jobId,
    connectionId: job.connectionId,
    epoch: job.epoch,
    deliveryId: job.header.deliveryId,
    reason
  });
}

function post(response: WorkerResponse): void {
  self.postMessage(response);
}
