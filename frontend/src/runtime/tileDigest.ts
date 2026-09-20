export async function sha256Hex(
  bytes: ArrayBuffer,
  subtle: SubtleCrypto | null | undefined = globalThis.crypto?.subtle
): Promise<string | null> {
  if (!subtle) return null;

  try {
    const digest = await subtle.digest('SHA-256', bytes);
    return Array.from(
      new Uint8Array(digest),
      (value) => value.toString(16).padStart(2, '0')
    ).join('');
  } catch {
    return null;
  }
}
