# E22 — Evidencia y cierre técnico

Fecha de trabajo: 21 de septiembre de 2026.

Este archivo registra garantías de E22. Los límites son **cotas de diseño**; no
son resultados de capacidad, throughput, latencia o carga de E23/E24.

## Evidencia observada antes del bloque final

En WSL, Erwin reportó ejecución satisfactoria de:

```bash
mvn -o -Dtest=NioHttpServerSlowWebSocketTest test

mvn -o \
  -Dtest=PathPolicyTest,HttpRouterSecurityTest,PublishedImageStoreTest,FileCatalogSourceSecurityTest,NioHttpServerSlowWebSocketTest,LupaSessionReconnectCleanupTest \
  test

mvn -o clean verify package
```

El commit `5236ed9` también obtuvo éxito en los workflows E19 CI y A20 Frontend CI,
incluyendo construcción Maven online/offline y arranque del JAR empaquetado.

La instrumentación y shutdown añadidos después de ese punto deben volver a pasar la
regresión antes de declarar cerrado E22.

## Recursos y políticas

| Recurso | Límite/default | Dónde se aplica | Al agotarse | Evidencia principal |
|---|---:|---|---|---|
| TCP/HTTP aceptado | 128 | `NioHttpServer` | conexión nueva se cierra | `NioHttpServerAdmissionTest` |
| WebSocket | 64 | antes del HTTP 101 | HTTP 503 | `NioHttpServerAdmissionTest` |
| Sesiones LUPA | 32 | HELLO válido | ERROR + cierre 1013 | `LupaSessionAdmissionTest` |
| Worker HTTP/estado | cola 256 | `ThreadPoolExecutor` | HTTP 503/rechazo | configuración + regresión |
| Disco de teselas | 4 threads, cola 64 | `StorageExecutors` | rechazo, nunca CallerRuns | `StorageExecutorsTest` |
| Metadata | 2 threads, cola 32 | `StorageExecutors` | rechazo | `StorageExecutorsTest` |
| Turnos de lectura | 8 globales, quantum 128 KiB | `TileReadAdmission` | DRR por costo JPEG + espera acotada/rechazo | `TileReadAdmissionTest`, `LupaSessionFairnessTest` |
| Espera de turnos | máx. sesiones | `TileReadAdmission` | rechazo | pruebas de admisión |
| Caché JPEG | 128 MiB residentes | `CompressedTileCache` | LRU | `CompressedTileCacheTest` |
| JPEG desalojado aún retenido | contador separado | handles de caché | se libera al último close | `CompressedTileCacheTest` |
| Envelope TILE fuera de caché | 16 MiB global | `TransientBufferBudget` | plan falla/cierra responsable | `TransientBufferBudgetTest` |
| Entregas por sesión | 16 | protocolo/sesión | deja de preparar datos | regresión de créditos |
| Ventana por conexión | 512 KiB–1 MiB | HELLO/créditos | espera RELEASE | `LupaSessionFairnessTest` |
| Descriptores de selección | 512 | planificador | LIMIT_EXCEEDED | regresión I21 |
| Control JSON | 16 KiB | WebSocket/parser | cierre de protocolo | regresión WebSocket |
| TILE header | 4096 B | codificación TILE | error de plan | pruebas de sesión |
| JPEG | 262144 B | lector publicado | lectura rechazada | `PublishedTileReaderTest` |
| Cola WebSocket | 64 frames | `WebSocketWriteQueue` | rechazo/cierre responsable | `NioHttpServerSlowWebSocketTest` |
| Cola serial por sesión | 64 tareas | `SerialExecutor` | rechazo/cierre | pruebas de robustez |
| Cabeceras HTTP | 16 KiB default | `HeaderAccumulator` | 431 | integración HTTP |
| Cabecera HTTP incompleta | 5 s | conexión HTTP | 408 | integración HTTP |
| HELLO | 5 s | sesión antes de negociar | cierre 1008 | `LupaSessionTimeoutTest` |
| Ping | 15 s | WebSocket | un ping pendiente | `WebSocketHeartbeatTest` |
| Pong | 10 s después de escribir PING | WebSocket | cierre | `WebSocketHeartbeatTest` |
| RELEASE | 30 s después de escribir TILE | por delivery | cierre 1008 | `LupaSessionTimeoutTest` |
| Escritura sin progreso | 10 s | frame comprometido | cierre del canal | `WebSocketWriteTimeoutTest` |
| Cierre WebSocket | 2 s | close handshake | cierre forzado | regresión WebSocket |

## Memoria administrada

La cota explícita principal es 128 MiB de JPEG residente + 16 MiB de envelopes TILE
fuera de caché. Las lecturas admitidas añaden como orden de magnitud hasta
`8 × 256 KiB` de payload JPEG, además de objetos Java, estructuras de mapas/colas,
buffers de lectura, ImageIO y stacks.

`retainedEvictedBytes` cuenta JPEG desalojados del LRU que todavía siguen
referenciados. Esto evita afirmar que un eviction liberó memoria cuando todavía hay
consumidores.

Estas cifras **no** son una cota del heap total, memoria nativa, buffers del sistema
operativo ni RSS.

## Instrumentación local

E22 mantiene snapshots/counters acotados; no conserva buffers ni historiales:

- conexiones y WebSocket: `NioHttpServer.ServerSnapshot`;
- sesiones activas: `SessionAdmission.Snapshot`;
- workers/colas de disco y metadata: `StorageExecutors.Snapshot`;
- lecturas/turnos: `TileReadAdmission.Snapshot`;
- bytes TILE transitorios: `TransientBufferBudget.Snapshot`;
- caché, hits, misses y evictions: `CompressedTileCache.Snapshot`;
- saldo, reservas, deliveries y turnos por sesión: `LupaSession.SessionSnapshot`;
- causas de timeout, liberaciones, cleanup y callbacks tardíos:
  `E22Metrics.Snapshot`.

No existe telemetría externa. Los diagnósticos opcionales escriben a stdout y no
mantienen una cola o archivo de log dentro del proceso. LUPA no implementa rotación
de archivos porque no crea archivos de log: si stdout se redirige a archivo, la
rotación corresponde al launcher/supervisor del entorno.

## Rutas y almacenamiento

La superficie pública se limita a `/`, `/assets/*`, `/api/catalog` y `/lupa`.
No existe descarga genérica de archivos. Traversal, separadores codificados,
backslash, NUL, doble decodificación y network paths se rechazan antes del router.

Catálogo, manifiestos y teselas se resuelven desde identificadores validados dentro
del árbol publicado y se rechazan symlinks encontrados en la cadena. La política es
check-before-open; no se afirma protección absoluta frente a un atacante local capaz
de modificar el filesystem durante la carrera TOCTOU.

## Reconexión y ciclo de vida

Una conexión nueva crea una sesión nueva: HELLO/WELCOME, OPEN/MANIFEST y VIEW vuelven
a comenzar. No se heredan créditos, deliveries, timers, reservas, planes ni callbacks.
`deliveryId` puede reiniciar en 1 porque su ámbito es la conexión.

El cierre es idempotente. Los permisos y presupuestos usan leases idempotentes; los
callbacks tardíos detectan sesión/plan obsoleto y no reinsertan estado.

## Apagado

Los ejecutores propios se cierran con `shutdownNow()` y una espera máxima de 2 s.
El servidor también espera la terminación del grupo NIO. El cierre es idempotente y
no cierra ejecutores globales al terminar una sola sesión.

## Matriz de cierre

| Criterio | Evidencia | Estado |
|---|---|---|
| conexiones/sesiones/colas acotadas | admisión + colas bounded + pruebas | Implementado y probado |
| caché JPEG 128 MiB | LRU + tests de bytes/versiones/concurrencia | Implementado y probado |
| buffers fuera de caché acotados | `TransientBufferBudget` | Implementado y probado |
| disco fuera de callbacks de red | ejecutores bounded separados | Implementado y probado |
| turnos entre sesiones | A-B-A con costos iguales + A-B-C-B-A con costos variables DRR | Implementado; pendiente de ejecutar regresión nueva |
| cliente sin RELEASE aislado | cliente bloqueado por créditos + otro DONE | Implementado y probado |
| cliente que no lee aislado | sockets WebSocket reales | Verificado en equipo |
| heartbeat/timeouts separados | scheduler monotónico + tests | Implementado y probado |
| sesión inmóvil saludable permanece | test sin nuevas VIEW | Implementado y probado |
| RELEASE vencido cierra | deadline individual por delivery | Implementado y probado |
| originales/rutas privadas inaccesibles | parser/router/storage tests | Verificado en equipo |
| cleanup exactamente una vez | cierre doble + leases idempotentes | Implementado y probado |
| callback tardío no revive sesión | prueba de reconexión/cleanup | Verificado en equipo |
| reconexión sin heredar estado | nueva sesión y deliveryId local | Verificado en equipo |
| apagado de recursos propios | tests de termination | Pendiente de ejecutar Bloque 6 |
| regresión I21 / build offline | `mvn -o clean verify package` | Verificado antes de Bloque 6 |
| E23/E24 carga/rendimiento | fuera de alcance | No declarado |

## Comandos de cierre

```bash
mvn -o \
  -Dtest=E22MetricsTest,LupaSessionMetricsTest,NioHttpServerShutdownTest,StorageExecutorsTest \
  test

mvn -o clean verify package
```

Después de estos comandos, la evidencia manual recomendada es repetir el recorrido
real de I21 con dos navegadores y conservar únicamente la traza breve necesaria para
la entrega.
