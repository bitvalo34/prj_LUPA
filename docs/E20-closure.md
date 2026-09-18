# E20 — Matriz de cierre

Fecha de evidencia: 18 de septiembre de 2026.

Estados usados:

- **Código**: implementado y cubierto por pruebas automatizadas.
- **CI**: verificado por GitHub Actions.
- **WSL**: verificado manualmente en el equipo de Erwin.
- **Pendiente posterior**: pertenece deliberadamente a E21/E22/A20/I20.

| Criterio | Evidencia | Estado |
|---|---|---|
| /lupa negocia WebSocket real | handshake RFC 6455, respuesta 101 y subprotocolo lupa.v1 | Código + CI |
| Validación de apertura | método, Upgrade/Connection, versión 13, Key, subprotocolo y Origin | Código + CI |
| HTTP y primera trama juntos | prueba raw socket con handshake + frame en el mismo write TCP | Código + CI |
| Máscara cliente | parser exige máscara y aplica offset incremental | Código + CI |
| Fragmentación de lectura | parser incremental, incluso byte por byte | Código + CI |
| Fragmentación de mensaje | continuación de texto, UTF-8 dividido y ping intercalado | Código + CI |
| Longitudes 7/16/64 | pruebas canónicas, no mínimas, MSB y límite previo a reserva | Código + CI |
| Ping/pong | prueba raw socket conserva payload | Código + CI |
| Cierre | validación de close y timeout básico | Código + CI |
| Escrituras parciales | WebSocketWriteQueue y AsyncWritePump con pruebas deterministas | Código + CI |
| Una escritura pendiente por socket | cola serializada de salida | Código + CI |
| HELLO/WELCOME | negociación v1, ventana, maxTileBytes y maxInFlight | Código + CI + WSL |
| Estado por conexión | ESPERA_HELLO → LISTA → IMAGEN_ABIERTA → CERRADA | Código + CI |
| OPEN/MANIFEST real | PublishedImageStore sobre catálogo/manifiesto A19 | Código + CI + WSL |
| VIEW/PLAN | región contenida, viewport limitado y nivel uniforme automático | Código + CI + WSL |
| contextLevel uniforme | igual a appliedLevel conforme al contrato de modo uniform | Código + CI + WSL |
| Miniatura primero | z=0 es primer TILE del primer plan tras OPEN; no se repite en VIEW posteriores y no se duplica si appliedLevel=0 | Código + CI + WSL |
| Selección regional | cursor perezoso con intervalos semiabiertos y bordes exactos | Código + CI |
| Lectura de teselas | rutas publicadas, <=262144 bytes, JPEG decodificable, dimensiones de borde | Código + CI + WSL |
| TILE binario | 4 + H + JSON + JPEG, H<=4096, type=TILE y payloadBytes exacto | Código + CI + WSL |
| Integridad del JPEG | probe compara longitud y SHA-256 con archivo publicado | WSL |
| Créditos | reserva por 4+H+payload, ventana 512KiB–1MiB | Código + CI |
| maxInFlight | máximo 16 entregas pendientes | Código + CI |
| RELEASE | displayed/discarded/failed devuelve una sola reserva registrada | Código + CI + WSL |
| RELEASE duplicado/desconocido | no incrementa saldo | Código + CI |
| RELEASE de época anterior | puede liberar una entrega todavía pendiente | Código + CI |
| Invariante de ventana | libre + reservas = ventana negociada | Código + CI |
| Épocas | obsoletas ignoradas, intención nueva invalida trabajo pendiente básico | Código + CI |
| Lectura tardía | generación revalidada antes de encolar resultado | Código + CI |
| DONE | se emite después de terminar escrituras TILE del plan vigente | Código + CI + WSL |
| Plan fallido | INTERNAL_READ_ERROR y no DONE falso | Código + CI |
| Dos sesiones | créditos y deliveryId independientes | Código + CI |
| Desconexión con lectura pendiente | resultado tardío se descarta y no produce TILE/DONE | Código + CI |
| Desconexión durante salida binaria | prueba raw cierra abruptamente y confirma liberación/accept loop | Código + CI |
| Regresión HTTP | /, assets y /api/catalog siguen pasando | CI |
| Build offline | mvn -o clean verify package | CI + WSL |
| Recorrido real offline | demo-auxiliar: 21 TILE, RELEASE por cada entrega y DONE=21 | WSL |
| A20 puede integrarse sin visor previo | docs/A20-E20-integration.md + probe Java independiente | Código/documentación |
| Visor completo | corresponde a A20/I20 | Pendiente posterior |
| Foco/detailOffset completo | corresponde a E21/A21 | Pendiente posterior |
| Heartbeat, timeouts avanzados y carga | corresponde a E22/E23 | Pendiente posterior |

## Evidencia manual registrada

En WSL, con `demo-auxiliar/v1` (1096×815, tileSize 256, niveles 0..3):

- handshake y `lupa.v1` correctos;
- WELCOME con ventana 1048576, maxTileBytes 262144 y maxInFlight 16;
- PLAN epoch 2 con appliedLevel/contextLevel 3;
- 21 TILE recibidos;
- delivery 1 fue la miniatura z=0 de 137×102 y 4133 bytes;
- deliveries 2–21 cubrieron z=3, incluidos bordes 72×256, 256×47 y 72×47;
- todos los SHA-256 fueron comparados por el probe con los JPEG publicados;
- se envió RELEASE=`discarded` por cada entrega;
- DONE informó `sentTiles=21`;
- el probe terminó con `E20 TILE/RELEASE probe completed successfully.`.

## Cierre de alcance

E20 cubre el transporte y flujo base exigido. No debe confundirse esta evidencia con la finalización del visor A20 ni con el refinamiento de control de E21/E22.
