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


---

# E21 addendum — contrato operativo para A21

Este addendum concreta el comportamiento que A21 debe consumir después de E21. Sustituye las limitaciones temporales de E20 donde se indicaba que `focus` y `detailOffset` todavía no estaban implementados.

## VIEW efectivo en E21

E21 acepta:

- `detailOffset`: `-2`, `-1` o `0`.
- `mode`: `uniform` o `focus`.
- `focus=null` en modo `uniform`.
- En modo `focus`, `focus.x` y `focus.y` están en coordenadas de la imagen original y `focus.radiusPx` está en píxeles físicos del viewport.

Las coordenadas `rect` también están en el espacio de la imagen original. `viewportPx` describe exclusivamente el rectángulo físico realmente ocupado por la imagen, no las bandas de letterboxing. A20 ya calcula esta transformación con `computeFitLayout` y `viewportForRect`.

### Uniform

Ejemplo:

```json
{
  "type":"VIEW",
  "epoch":10,
  "imageId":"demo-auxiliar",
  "imageVersion":"v1",
  "rect":{"x":0,"y":0,"width":1096,"height":815},
  "viewportPx":{"width":800,"height":595},
  "detailOffset":-1,
  "mode":"uniform",
  "focus":null
}
```

En `uniform`:

```text
contextLevel == appliedLevel
```

`detailOffset` se aplica sobre el nivel automático y el nivel efectivo puede bajar adicionalmente si el presupuesto bitmap o el límite de descriptores lo requiere. Por eso A21 debe dibujar lo que anuncia PLAN y no inferir por su cuenta el nivel final.

### Focus

Ejemplo:

```json
{
  "type":"VIEW",
  "epoch":11,
  "imageId":"demo-auxiliar",
  "imageVersion":"v1",
  "rect":{"x":0,"y":0,"width":1096,"height":815},
  "viewportPx":{"width":800,"height":595},
  "detailOffset":0,
  "mode":"focus",
  "focus":{"x":548,"y":407,"radiusPx":140}
}
```

Política E21:

```text
contextLevel = max(0, appliedLevel - 1)
```

salvo que el foco recortado no interseque la región visible, caso en el que E21 no anuncia detalle que no vaya a enviar.

El círculo de `radiusPx` se transforma al espacio original con una escala independiente por eje. Si la relación de aspecto difiere, su región geométrica en coordenadas originales es una elipse, no un círculo arbitrario.

## Orden del plan y composición

Orden E21:

1. miniatura inicial z=0 cuando todavía no fue comprometida para ese OPEN;
2. contexto mínimo;
3. foco visible;
4. resto de contexto visible.

A21 debe componer niveles de menor a mayor detalle para que el contexto nunca tape una tesela de mayor resolución. El compositor actual de A20 ya ordena primero `context` y después `detail`; A21 debe extender esa clasificación para reconocer `contextLevel` del PLAN, no asumir que únicamente z=0 es contexto.

## Sustitución por época

No existe mensaje CANCEL.

Una VIEW válida con época mayor sustituye el plan previo. Una OPEN válida posterior sustituye imagen y plan. Una solicitud inválida no consume la época.

El servidor puede retirar:

- selección aún no leída;
- lectura todavía pendiente/no comprometida;
- resultado de lectura obsoleto;
- TILE todavía encolada antes de entregarse al canal asíncrono.

No puede prometer retirar una TILE cuyo frame ya fue comprometido a `AsynchronousSocketChannel.write`.

Por tanto A21 debe:

- descartar mensajes de épocas antiguas antes de decodificar cuando sea posible;
- volver a comprobar `epoch` y versión después de decodificar;
- cerrar cualquier ImageBitmap obsoleto;
- enviar RELEASE incluso para una entrega antigua ya transmitida.

## Créditos y RELEASE

La reserva del servidor para cada TILE es:

```text
4 + H + payloadBytes
```

sin contar overhead WebSocket.

La ventana negociada y el espacio de `deliveryId` no se reinician al cambiar VIEW u OPEN.

Estados válidos:

```text
displayed
discarded
failed
```

Un RELEASE duplicado o desconocido se ignora sin crear crédito. A21 nunca envía una cantidad de bytes en RELEASE.

Antes de agotar `epoch=2147483647` o el espacio de deliveryId, el cliente debe cerrar y reconectar. El servidor no reutiliza deliveryId dentro de una conexión.

## DONE

DONE significa únicamente:

> todos los TILE de ese plan vigente terminaron su escritura en el transporte.

No significa:

- que todos estén decodificados;
- que todos estén dibujados;
- que todos tengan RELEASE;
- que el compositor visual haya terminado.

A21 debe conservar su propia condición de "vista lista" basada en Worker + compositor, como ya hacía A20.

## Errores

Errores recuperables de aplicación definidos por LUPA v1:

- `VERSION_UNSUPPORTED`
- `BAD_VIEW`
- `IMAGE_NOT_FOUND`
- `IMAGE_NOT_READY`
- `LIMIT_EXCEEDED`
- `INTERNAL_READ_ERROR`

Una VIEW inválida devuelve `BAD_VIEW` sin destruir la intención válida anterior.

Violaciones de encuadre/formato o secuencia no recuperable pueden cerrar el WebSocket. A21 debe distinguir un ERROR LUPA de un cierre de transporte.

## Responsabilidad de A21

A21 puede añadir controles de pan/zoom/foco y navegación, pero debe preservar:

- coordenadas originales para `rect` y centro del foco;
- `viewportPx` físico de la zona de imagen real;
- `radiusPx` físico;
- monotonicidad de épocas;
- descarte de resultados obsoletos;
- RELEASE único por entrega;
- composición contexto → detalle;
- reconexión antes de agotar identificadores.

E21 no implementa esos controles visuales; solo entrega el contrato backend necesario.
