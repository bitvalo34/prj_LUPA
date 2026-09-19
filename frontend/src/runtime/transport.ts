export interface TransportHandlers {
  onOpen(): void;
  onText(text: string): void;
  onBinary(buffer: ArrayBuffer): void;
  onClose(code: number, reason: string): void;
  onError(message: string): void;
}

export interface LupaTransport {
  connect(handlers: TransportHandlers): void;
  sendText(text: string): void;
  close(code?: number, reason?: string): void;
  readonly isOpen: boolean;
}
