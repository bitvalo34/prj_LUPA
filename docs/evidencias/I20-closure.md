# I20 — Cierre del primer recorrido completo

Fecha de cierre técnico: 19 de septiembre de 2026.

## Evidencia final

### Build y pruebas locales

En el WSL de Erwin se verificó:

- Vitest: 6 archivos / 23 pruebas aprobadas.
- \`mvn -o clean verify package\`: BUILD SUCCESS.
- Java 21 y frontend empaquetado dentro del JAR.

### Recorrido vertical real

Con \`demo-auxiliar/v1\`:

- página servida por Java;
- catálogo real desde \`/api/catalog\`;
- WebSocket \`/lupa\` con subprotocolo \`lupa.v1\`;
- OPEN/MANIFEST;
- VIEW completa \`rect=0,0,1096,815\`;
- PLAN \`appliedLevel=3\`;
- miniatura z=0 + 20 TILE de detalle;
- DONE \`sentTiles=21\`;
- RELEASE para todas las entregas;
- Canvas visible y progresivo;
- DONE observado con trabajo local todavía pendiente, demostrando que no se confunde con fin de render.

### Selección espacial real

La utilidad de integración I20 emitió:

\`\`\`text
VIEW epoch=3 imageId=demo-auxiliar imageVersion=v1
rect=274,204,548,407 viewport=446x331
\`\`\`

E20 devolvió exactamente 9 teselas z=3:

\`\`\`text
(1,0) (2,0) (3,0)
(1,1) (2,1) (3,1)
(1,2) (2,2) (3,2)
\`\`\`

No se repitió z=0. DONE informó \`sentTiles=9\`. Hubo 9 RELEASE \`displayed\`.

### Identidad del JPEG transmitido

La primera TILE registró:

\`\`\`text
bytes=4133
sha256=1db9f08ef488717d0b2d441d532e018f96e19250bb798c4359344b1e85db6736
\`\`\`

El archivo publicado \`data/pyramids/demo-auxiliar/v1/tiles/0/0_0.jpg\` produjo exactamente el mismo SHA-256 y 4133 bytes.

### Tráfico y recursos

DevTools Network mostró únicamente recursos del mismo origen \`127.0.0.1:8081\`:

- documento raíz;
- bundle JavaScript local;
- CSS local;
- Worker local;
- \`/api/catalog\`;
- WebSocket \`/lupa\` con 101.

No se observó descarga HTTP del original ni dependencia necesaria de CDN/fuentes externas.

### Privacidad de rutas internas

Se verificó en WSL:

\`\`\`text
GET /data/originals/secret.jpg                        -> 404
GET /data/staging/anything                           -> 404
GET /data/pyramids/demo-auxiliar/v1/tiles/0/0_0.jpg -> 404
\`\`\`

Por tanto originales, staging y teselas publicadas no quedan expuestos como archivos HTTP directos.

### Prueba offline

Con el servidor Java local en ejecución y el acceso externo desconectado, una ventana nueva/incógnita pudo abrir \`http://127.0.0.1:8081/\`, cargar el frontend local, leer el catálogo y completar nuevamente el recorrido LUPA hasta “Lista para observar”.

### Sesión nueva

La evidencia visual mostró una sesión posterior con \`Conexión #2\`, contadores independientes y finalización correcta, verificando reconexión sin reutilizar estado de la sesión anterior.

## Matriz criterio | evidencia | estado

| Criterio | Evidencia | Estado |
|---|---|---|
| E20 + A20 desde servidor Java | JAR local + navegador real | Verificado E2E |
| Catálogo de importación real | /api/catalog + demo-auxiliar | Verificado E2E |
| WebSocket 101 / lupa.v1 | DevTools + traza | Verificado E2E |
| HELLO/WELCOME | traza real | Verificado E2E |
| OPEN/MANIFEST | demo-auxiliar/v1 | Verificado E2E |
| VIEW/PLAN | vista completa + parcial | Verificado E2E |
| TILE binario | 21 plan completo + 9 plan parcial | Verificado E2E |
| Miniatura z=0 | delivery inicial | Verificado E2E |
| Worker / JPEG | decode real + Canvas visible | Verificado E2E |
| Canvas progresivo | captura real | Verificado E2E |
| RELEASE | 21 y 9 RELEASE displayed | Verificado E2E |
| DONE separado de render | DONE_STATE con decode/paint pendientes | Verificado E2E |
| Región parcial | rect central, solo 9 teselas pertinentes | Verificado E2E |
| No se envía todo el nivel | 9 vs 20 teselas de detalle | Verificado E2E |
| Hash TILE vs archivo | SHA-256 y bytes idénticos | Verificado E2E |
| No descarga del original | Network + rutas privadas | Verificado |
| Sin solicitudes externas de la app | DevTools Network | Verificado |
| Original/staging/pyramid privados | tres respuestas 404 | Verificado |
| Funcionamiento offline | ventana nueva con Internet externo desconectado | Verificado |
| Reconexión | Conexión #2 + recorrido correcto | Verificado |
| Build frontend | Vitest + Vite | Verificado local/CI |
| Build Java offline | Maven offline BUILD SUCCESS | Verificado local/CI |
| CI final | A20 Frontend CI #65 + E19 CI #74 | Verificado |
| Navegación/foco/lente avanzados | tarea posterior | Fuera de I20 |

## Cierre

I20 queda completa: el recorrido vertical desde imagen importada hasta Canvas funciona con datos reales, selección espacial, créditos, finalización correcta y recursos locales. No se atribuyen a I20 las funciones de navegación avanzada de A21 ni la robustez ampliada de E21/E22.
