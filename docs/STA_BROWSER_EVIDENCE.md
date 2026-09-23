# STA — prueba real en navegador

Esta prueba fuerza **una sola falla sintética de decodificación** en el navegador
para demostrar el recorrido completo de Selective Tile Acknowledgment sin corromper
los datos publicados ni modificar el servidor.

El hook está desactivado por defecto y solo se activa por query string:

```text
?staFailFirstDecode=1
```

## Preparación

1. Ejecutar LUPA desde la rama `feature/lupa-selective-ack-drr`.
2. Asegurarse de tener al menos una imagen publicada que cargue correctamente.
3. Abrir el navegador en:

```text
http://127.0.0.1:8080/?staFailFirstDecode=1
```

Si el servidor usa otro puerto, conservar el query string.

## Qué hace el hook

El Web Worker falla **solo el primer decode original** de la página. Una
retransmisión ya lleva `retry=1` en la cabecera TILE, por lo que el hook no vuelve
a fallarla.

No se altera el JPEG en disco ni se cambia el manifiesto.

## Recorrido esperado

Para un delivery cualquiera `N`:

1. servidor -> cliente: `TILE delivery=N`;
2. Worker -> cliente: fallo sintético;
3. cliente -> servidor:

```json
{
  "type": "ACK_STATE",
  "epoch": E,
  "received": [],
  "missing": [N]
}
```

4. servidor vuelve a leer **solo esa tesela**;
5. servidor -> cliente: `TILE delivery=N retry=1`;
6. el cliente acepta el mismo deliveryId porque esperaba su retransmisión;
7. tras dibujar/descartar terminalmente la tesela:

```json
{
  "type": "ACK_STATE",
  "epoch": E,
  "received": [[N, N]],
  "missing": []
}
```

La reserva de crédito original se conserva durante los pasos 3–5. No se crea una
segunda reserva por la retransmisión.

## Evidencia en la traza descargada

Abrir **Diagnóstico** y después **Guardar traza**. Deben aparecer, en este orden
lógico, líneas equivalentes a:

```text
IN     TILE                  delivery=N epoch=E ...
LOCAL  FAILED                delivery=N ... STA diagnostic: synthetic first decode failure
OUT    ACK_STATE             epoch=E received=[] missing=[N]
OUT    SELECTIVE_RECOVERY    delivery=N epoch=E
IN     TILE                  delivery=N epoch=E ... retry=1
LOCAL  RETRANSMIT_ACCEPTED   delivery=N epoch=E
OUT    ACK_STATE             epoch=E received=[[N,N]] missing=[]
```

El evento `FAILED` incrementa el contador de fallos porque representa la falla
inyectada; la evidencia importante es que el mismo deliveryId se recupera y termina
confirmado sin cerrar la sesión.

## Comprobaciones adicionales

- La imagen debe terminar visible.
- La conexión WebSocket debe permanecer abierta.
- No debe aparecer `PROTOCOL_ERROR`.
- No debe aparecer un deliveryId nuevo para sustituir a `N`; la recuperación usa
  el mismo id.
- Al recargar la página **sin** `staFailFirstDecode=1`, el comportamiento vuelve a
  producción normal.

Este hook es únicamente diagnóstico local y no constituye simulación de pérdida de
paquetes TCP. Demuestra la política de recuperación de LUPA frente a una entrega que
el receptor declara faltante/fallida.
