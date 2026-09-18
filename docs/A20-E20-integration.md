# Integración A20 ↔ E20 — LUPA v1

Este documento fija el contrato práctico para que el visor de Adrian conecte A20 con el servidor E20 sin depender del cliente técnico.

## Conexión

- URL local por defecto: `ws://127.0.0.1:8081/lupa`
- Subprotocolo obligatorio: `lupa.v1`
- WebSocket versión 13.
- El servidor no negocia extensiones de compresión en v1.
- Para navegador, Origin debe corresponder al mismo servidor/localhost según la política configurada.
- Para herramientas técnicas sin Origin se permite únicamente si el servidor arrancó con `--ws-allow-no-origin=true`.

Servidor:

```bash
java -jar target/lupa.jar \
  --host=127.0.0.1 \
  --port=8081 \
  --data-root=/home/erwin/PRJIMA/data
```

## Secuencia mínima para A20

### 1. HELLO

Cliente:

```json
{"type":"HELLO","version":1,"windowBytes":1048576,"bitmapBudgetBytes":67108864}
```

Servidor:

```json
{"type":"WELCOME","version":1,"windowBytes":1048576,"maxTileBytes":262144,"maxInFlight":16}
```

A20 debe conservar el `windowBytes` confirmado por WELCOME; no asumir que el servidor aceptó exactamente el solicitado.

### 2. OPEN

Cliente:

```json
{"type":"OPEN","epoch":1,"imageId":"demo-auxiliar"}
```

Servidor, ejemplo real actual:

```json
{
  "type":"MANIFEST",
  "epoch":1,
  "imageId":"demo-auxiliar",
  "imageVersion":"v1",
  "width":1096,
  "height":815,
  "levels":[
    {"z":0,"width":137,"height":102},
    {"z":1,"width":274,"height":204},
    {"z":2,"width":548,"height":408},
    {"z":3,"width":1096,"height":815}
  ],
  "tileSize":256
}
```

A20 debe conservar `imageVersion` y enviarla en VIEW.

### 3. VIEW temporal soportado por E20

E20 soporta en esta fase:

- `mode="uniform"`
- `detailOffset=0`
- `focus=null`

Ejemplo:

```json
{
  "type":"VIEW",
  "epoch":2,
  "imageId":"demo-auxiliar",
  "imageVersion":"v1",
  "rect":{"x":0,"y":0,"width":1096,"height":815},
  "viewportPx":{"width":800,"height":600},
  "detailOffset":0,
  "mode":"uniform",
  "focus":null
}
```

Respuesta observada con la muestra:

```json
{"type":"PLAN","epoch":2,"appliedLevel":3,"contextLevel":3}
```

En modo uniforme, `contextLevel == appliedLevel`. El refinamiento de foco y detailOffset se completa en E21/A21.

## TILE binario

Cada tesela llega como **un mensaje WebSocket binario independiente**:

```text
[4 bytes H big-endian][H bytes JSON UTF-8][JPEG]
```

La cabecera JSON contiene:

```json
{
  "type":"TILE",
  "deliveryId":1,
  "epoch":2,
  "imageId":"demo-auxiliar",
  "imageVersion":"v1",
  "z":0,
  "x":0,
  "y":0,
  "w":137,
  "h":102,
  "codec":"jpeg",
  "payloadBytes":4133
}
```

Validaciones recomendadas en A20:

1. longitud binaria >= 4;
2. leer H como uint32 big-endian;
3. `1 <= H <= 4096`;
4. parsear exactamente H bytes como JSON UTF-8;
5. exigir `type == "TILE"`;
6. exigir `codec == "jpeg"`;
7. `payloadBytes <= 262144`;
8. comprobar `4 + H + payloadBytes == mensaje.byteLength`;
9. verificar que `epoch` sigue siendo la época activa antes y después de decodificar;
10. crear ImageBitmap solo del JPEG, no del envelope completo.

La identidad de caché es:

```text
imageId + imageVersion + z + x + y
```

`deliveryId` no identifica el contenido: identifica solamente esa entrega de esa conexión.

## Orden observado con demo-auxiliar

El recorrido real verificado en WSL envió:

- delivery 1: miniatura `z=0 x=0 y=0`, 137×102, 4133 bytes;
- deliveries 2–21: las 20 teselas de `z=3` que cubren la imagen completa;
- las teselas de borde usaron dimensiones reales, por ejemplo 72×256, 256×47 y 72×47;
- `DONE epoch=2 sentTiles=21`.

La miniatura forma parte de los mismos créditos que las demás teselas y se envía una sola vez en el primer plan posterior a cada OPEN. Los VIEW posteriores de esa misma apertura no deben esperar que z=0 vuelva a llegar.

## RELEASE

Después de procesar un TILE, A20 debe liberar exactamente una vez su `deliveryId`.

Cuando la tesela fue aceptada y dibujada:

```json
{"type":"RELEASE","deliveryId":17,"status":"displayed"}
```

Si la época ya cambió o la tesela no se usará:

```json
{"type":"RELEASE","deliveryId":17,"status":"discarded"}
```

Si falló la decodificación/procesamiento:

```json
{"type":"RELEASE","deliveryId":17,"status":"failed"}
```

No enviar cantidad de bytes: E20 recupera la reserva registrada internamente.

Un RELEASE duplicado o desconocido no crea crédito.

## DONE

Ejemplo:

```json
{"type":"DONE","epoch":2,"sentTiles":21}
```

DONE significa **que el servidor terminó de escribir el plan vigente**, no que Canvas ya terminó de dibujarlo ni que todas las entregas ya fueron liberadas.

A20 debe continuar enviando RELEASE de entregas pendientes incluso si DONE ya llegó.

## Épocas

- rango: 1..2147483647;
- una nueva intención usa una época mayor;
- mensajes de épocas anteriores se ignoran;
- cambiar época no reinicia deliveryId ni la ventana;
- A20 debe descartar antes y después de la decodificación cualquier TILE cuya época ya no sea activa;
- OPEN de una imagen nueva también invalida el plan anterior.

## Errores de aplicación

Códigos LUPA v1:

- `VERSION_UNSUPPORTED`
- `BAD_VIEW`
- `IMAGE_NOT_FOUND`
- `IMAGE_NOT_READY`
- `LIMIT_EXCEEDED`
- `INTERNAL_READ_ERROR`

Un error del plan no debe interpretarse como DONE.

## Cliente técnico de referencia

El cliente independiente del servidor puede usarse para comparar el comportamiento de A20:

```bash
java -cp target/lupa.jar gt.lupa.tools.LupaControlProbe \
  ws://127.0.0.1:8081/lupa \
  demo-auxiliar \
  /home/erwin/PRJIMA/data
```

El probe valida envelope, JPEG, dimensiones, SHA-256 contra el archivo publicado, envía RELEASE=`discarded` y exige DONE coherente.

## Lo que A20 no debe asumir todavía

E20 no declara completos en esta tarea:

- `mode="focus"`;
- `detailOffset=-1/-2`;
- refinamiento de contexto distinto del nivel uniforme;
- heartbeat completo;
- política avanzada de navegación rápida y cancelación de E21/E22.

Para I20, usar el subconjunto uniforme documentado arriba.
