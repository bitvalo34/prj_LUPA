import type { LupaTransport, TransportHandlers } from './transport';

export function deriveWebSocketUrl(locationLike: Pick<Location, 'protocol' | 'host'> = window.location): string {
  return (locationLike.protocol === 'https:' ? 'wss://' : 'ws://') + locationLike.host + '/lupa';
}

export class WebSocketTransport implements LupaTransport {
  private socket: WebSocket | null = null;
  get isOpen(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  connect(handlers: TransportHandlers): void {
    this.close(1000, 'replace connection');
    const socket = new WebSocket(deriveWebSocketUrl(), 'lupa.v1');
    socket.binaryType = 'arraybuffer';
    this.socket = socket;
    socket.addEventListener('open', () => handlers.onOpen());
    socket.addEventListener('message', (event) => {
      if (typeof event.data === 'string') handlers.onText(event.data);
      else if (event.data instanceof ArrayBuffer) handlers.onBinary(event.data);
      else handlers.onError('Mensaje WebSocket no soportado');
    });
    socket.addEventListener('close', (event) => handlers.onClose(event.code, event.reason));
    socket.addEventListener('error', () => handlers.onError('Error de transporte WebSocket'));
  }

  sendText(text: string): void {
    if (!this.isOpen || !this.socket) throw new Error('WebSocket no está abierto');
    this.socket.send(text);
  }

  close(code = 1000, reason = 'client close'): void {
    const socket = this.socket;
    this.socket = null;
    if (!socket) return;
    if (socket.readyState === WebSocket.OPEN) socket.close(code, reason);
  }
}
