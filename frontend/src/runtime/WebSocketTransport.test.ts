import { describe, expect, it } from 'vitest';
import { deriveWebSocketUrl } from './WebSocketTransport';

describe('I20 WebSocket same-origin URL', () => {
  it('derives ws from an HTTP origin without hard-coding a port', () => {
    expect(deriveWebSocketUrl({ protocol: 'http:', host: '127.0.0.1:8081' } as Location))
      .toBe('ws://127.0.0.1:8081/lupa');
  });

  it('derives wss from an HTTPS origin', () => {
    expect(deriveWebSocketUrl({ protocol: 'https:', host: 'lupa.example.test' } as Location))
      .toBe('wss://lupa.example.test/lupa');
  });
});
