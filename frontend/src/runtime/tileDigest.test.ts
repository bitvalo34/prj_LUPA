import { describe, expect, it } from 'vitest';
import { sha256Hex } from './tileDigest';

describe('sha256Hex', () => {
  it('omite el diagnóstico sin interrumpir el TILE cuando Web Crypto no está disponible', async () => {
    await expect(sha256Hex(new Uint8Array([1, 2, 3]).buffer, null)).resolves.toBeNull();
  });

  it('devuelve el SHA-256 hexadecimal cuando el navegador lo permite', async () => {
    const subtle = {
      digest: async () => new Uint8Array([0x01, 0xab, 0xff]).buffer
    } as unknown as SubtleCrypto;

    await expect(sha256Hex(new ArrayBuffer(0), subtle)).resolves.toBe('01abff');
  });
});
