# LUPA — control y recuperación selectiva

## Selective Tile Acknowledgment (STA)

LUPA v1 conserva `RELEASE` por compatibilidad, pero el navegador utiliza
`ACK_STATE` como mecanismo de confirmación y recuperación selectiva por entrega.

### Formato

```json
{
  "type": "ACK_STATE",
  "epoch": 8,
  "received": [[31, 36], [39, 44]],
  "missing": [37, 38]
}
```

- `epoch`: época a la que pertenecen las entregas.
- `received`: rangos inclusivos de `deliveryId` ya terminales en el cliente.
- `missing`: entregas concretas que deben recuperarse.
- Los rangos deben estar ordenados y no solaparse.
- Un id no puede aparecer simultáneamente como recibido y faltante.
- Cada arreglo admite como máximo 32 elementos.

### Créditos

Confirmar una entrega en `received` libera exactamente su reserva de la ventana.
Solicitarla en `missing` **no** devuelve crédito ni crea una segunda reserva.
La retransmisión reutiliza el mismo `deliveryId`.

Por tanto, durante recuperación selectiva se conserva:

```text
freeWindowBytes + reservedBytes = negotiatedWindowBytes
```

### Recuperación

El servidor conserva únicamente metadatos suficientes para volver a localizar la
tesela publicada: identidad de imagen/versión, coordenadas y deliveryId. No conserva
una segunda copia permanente del JPEG en la reserva.

Ante `missing`:

1. se valida que la entrega siga pendiente y pertenezca a la época/versión vigente;
2. se cancela temporalmente el deadline de RELEASE;
3. se vuelve a leer la tesela publicada mediante el ejecutor de disco acotado;
4. se reenvía con el **mismo deliveryId**;
5. se rearma el timeout al completar la escritura;
6. el número de recuperaciones se limita a dos por delivery.

Los resultados obsoletos se descartan. Una solicitud repetida mientras ya existe una
recuperación en curso no crea trabajo duplicado.

### Cliente

El ledger del navegador distingue una entrega nueva de una retransmisión esperada.
Un deliveryId repetido solo se acepta cuando el propio cliente lo marcó previamente
como `missing`. Un duplicado no solicitado continúa siendo una violación de protocolo.

Los éxitos terminales se confirman mediante `received`. Un fallo de decodificación
solicita recuperación selectiva; si se agota el límite local, se utiliza el cierre
terminal compatible existente.

## Relación con SACK

STA está inspirado en la idea de reconocimiento selectivo: el receptor describe qué
entregas concretas recibió y cuáles faltan, evitando reenviar una ventana completa.
No pretende ser una implementación literal de TCP SACK ni Selective Repeat; está
adaptado a teselas, deliveryId, épocas y créditos de LUPA.
