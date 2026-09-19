import { describe, expect, it } from 'vitest';
import { deriveTransferPhase } from './state';

describe('deriveTransferPhase', () => {
  it('no considera terminado DONE mientras faltan decodificaciones', () => {
    expect(deriveTransferPhase(true, 1, 0, true)).toBe('processing');
  });

  it('no considera terminado DONE mientras falta el primer paint', () => {
    expect(deriveTransferPhase(true, 0, 1, true)).toBe('processing');
  });

  it('pasa a observar solo cuando servidor, decode y paint terminaron', () => {
    expect(deriveTransferPhase(true, 0, 0, true)).toBe('observing');
  });

  it('permanece recibiendo antes de DONE cuando no hay trabajo local pendiente', () => {
    expect(deriveTransferPhase(false, 0, 0, true)).toBe('receiving');
  });
});
