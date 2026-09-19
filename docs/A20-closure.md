# A20 — Matriz de cierre

Fecha de cierre técnico: 19 de septiembre de 2026.

## Evidencia real

### Flujo `demo-auxiliar/v1`

La traza guardada en `docs/evidence/A20-demo-auxiliar-trace.txt` demuestra el recorrido real navegador ↔ E20:

- WebSocket `lupa.v1` abierto;
- HELLO/WELCOME;
- OPEN epoch 1;
- MANIFEST de `demo-auxiliar/v1`;
- VIEW epoch 2;
- PLAN con `appliedLevel=3`;
- 21 TILE recibidos;
- delivery 1 fue z=0;
- deliveries 2..21 fueron detalle z=3;
- 21 RELEASE con `status=displayed`;
- DONE informó `sentTiles=21`;
- al llegar DONE todavía existían 2 decodificaciones pendientes (`DONE_STATE decode=2 pintura=0`);
- las dos entregas finales se liberaron después, confirmando que DONE no se trató como composición terminada.

La captura de escritorio revisada muestra catálogo, Canvas compuesto, telemetría y diagnóstico simultáneamente.

### Ventana estrecha / imagen grande

La captura estrecha revisada usó `demo-grande-scale-v2/v1` de **40000×30131** y mostró:

- layout adaptado sin solapamientos;
- catálogo utilizable;
- visor protagonista;
- telemetría trasladada debajo del visor;
- 83 TILE recibidos;
- 83 dibujados;
- 0 descartados;
- 83 RELEASE;
- 0 decodificaciones pendientes;
- 0 pinturas pendientes;
- 4.6 MiB de bitmaps administrados por LUPA.

Esta evidencia cubre además una imagen grande real sin descargar el original al navegador.

## Matriz criterio | evidencia | estado

| Criterio | Evidencia | Estado |
|---|---|---|
| Catálogo real | cartuchos obtenidos desde /api/catalog | Verificado |
| Selección por imageId | demo-auxiliar y demo-grande-scale-v2 seleccionadas | Verificado |
| WebSocket LUPA | lupa.v1 en traza real | Verificado |
| HELLO/WELCOME | traza guardada | Verificado |
| OPEN/MANIFEST | demo-auxiliar/v1 real | Verificado |
| VIEW/PLAN | epoch 2, appliedLevel 3 | Verificado |
| TILE binario | 21 entregas reales en traza | Verificado |
| Miniatura/contexto | delivery 1 z=0 | Verificado |
| Worker real | DONE llegó con decode=2 y luego terminó en 0 | Verificado |
| Concurrencia/cola acotadas | implementación + Vitest/CI | Verificado CI |
| Canvas progresivo | captura real con imagen compuesta | Verificado |
| Bordes/coordenadas | E20 real + pruebas unitarias de viewMath | Verificado |
| RELEASE único | 21 TILE / 21 RELEASE displayed | Verificado |
| DONE no equivale a render final | DONE_STATE decode=2 antes de releases 20/21 | Verificado |
| Epoch + connectionId | implementación + tests/CI | Verificado CI |
| Resultados obsoletos | invalidación Worker + descarte por epoch/conexión | Verificado CI |
| Presupuesto bitmap | 3.5 MiB demo-auxiliar; 4.6 MiB imagen grande | Verificado |
| Limpieza de recursos | 0 decode / 0 por pintar al cierre | Verificado |
| Error/desconexión/reconexión | implementación + CI | Verificado CI |
| Diseño LUPA 64 | captura de escritorio | Verificado visualmente |
| Ventana estrecha | captura adaptada con telemetría debajo | Verificado visualmente |
| Imagen sin filtros decorativos | Canvas limpio en ambas capturas | Verificado visualmente |
| Accesibilidad/foco | foco visible y controles semánticos en implementación | Verificado visualmente + código |
| Recursos locales | Vite -> classpath, sin CDN | Verificado CI |
| Build frontend | npm ci/test/build | Verificado CI |
| Build Java offline | mvn -o clean verify package + E19 CI | Verificado |
| Servidor Java final | JAR sirviendo catálogo/frontend/WebSocket | Verificado en WSL |
| Traza guardada | docs/evidence/A20-demo-auxiliar-trace.txt | Verificado |
| Captura inicial | escritorio + ventana estrecha recibidas y revisadas | Verificado |
| I20 | tarea posterior | No atribuida a A20 |
| Zoom/pan/foco/lente | A21 | No atribuida a A20 |

## Cierre de alcance

A20 queda cerrada técnicamente. No se declara finalizada I20 ni A21. El PR queda listo para revisión; el merge se reserva para la revisión correspondiente.
