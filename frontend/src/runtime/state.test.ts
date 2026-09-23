import { describe, expect, it } from 'vitest';
import {
  deriveTileAccounting,
  deriveTransferPhase,
  describeRemoteError,
  describeSocketClose
} from './state';

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

describe('telemetría y errores A22', () => {
  it('contabiliza TILE terminales y pendientes sin ocultar diferencias', () => {
    expect(deriveTileAccounting(9, 4, 2, 1, 1, 1)).toEqual({
      terminal: 7,
      pending: 2,
      accounted: 9,
      balanced: true
    });
    expect(deriveTileAccounting(9, 4, 2, 1, 1, 0).balanced).toBe(false);
  });

  it('convierte errores remotos en una explicación accionable', () => {
    const message = describeRemoteError({
      type: 'ERROR',
      epoch: 4,
      code: 'IMAGE_NOT_READY',
      message: 'publication pending'
    });
    expect(message).toContain('termine la importación');
    expect(message).toContain('IMAGE_NOT_READY');
  });

  it('explica cierres anormales y omite el cierre normal', () => {
    expect(describeSocketClose(1000, 'reconnect')).toBeNull();
    expect(describeSocketClose(1011, 'timeout')).toContain('Reconectar');
  });
});
