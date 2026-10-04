# E24 — Campaña reproducible de rendimiento

Responsable: **Erwin Arevalo**  
Estado: **PREPARADA PARA EJECUCIÓN REAL; no se declaran resultados de rendimiento todavía**.

E24 mide 1, 5 y 20 clientes con cinco repeticiones, caché fría/caliente y comparación normal frente a baseline sin cancelación. Los resultados finales deben provenir del equipo real de Erwin; GitHub CI solo valida herramientas.

## Mejoras sobre la preparación de E23

E23 ya aportaba cliente WebSocket real, baseline sin cancelación, escenarios y scripts. E24 añade:

- workload `stable` además del dinámico `aggressive`;
- semillas independientes `230023..230027`, emparejadas entre variantes;
- orden normal/no-cancel balanceado entre condiciones;
- métricas opt-in de sesiones, colas, lecturas, caché, buffers y heap del servidor;
- muestreo `/proc` de RSS/CPU/IO del servidor y generador;
- identidad reproducible de la muestra y hashes de catálogo/manifiesto;
- análisis emparejado y reporte backend regenerable;
- validación de que la caché warm presenta hits/residencia y cold presenta misses.

## Matriz formal

Se ejecutan **dos workloads independientes**:

1. `stable`: una vista estable por cliente para recepción inicial y terminación del plan.
2. `aggressive`: 12 intenciones por cliente a 5 ms para estudiar solapamiento/cancelación.

Por workload: 2 variantes × 2 estados de caché × 3 cantidades de clientes × 5 repeticiones = **60 ejecuciones medidas**. Suite total: **120 ejecuciones medidas**, más warmups no medidos.

## Caché

- `cold`: cada repetición usa un JVM nuevo, por lo que la caché JPEG comprimida de LUPA empieza vacía.
- `warm`: un JVM persistente ejecuta una corrida de calentamiento no medida y luego las cinco repeticiones.
- La page cache del sistema operativo **no se vacía**. Por eso se habla de “caché de aplicación fría/caliente”, no de caché total del sistema.

## Métricas

El cliente técnico registra VIEW→PLAN, VIEW→primera TILE útil, VIEW→última TILE, VIEW→DONE, retraso del generador, bytes JPEG/binarios/control, teselas/bytes obsoletos, errores e incompletos.

`E24MetricsRecorder` registra, cuando se activa explícitamente, sesiones, colas de disco/metadatos, lecturas, DRR, caché comprimida, buffers transitorios, heap y contadores E22. `sample-process.py` añade RSS, CPU, threads e I/O de Linux/WSL para servidor y generador.

**VIEW→TILE y VIEW→DONE son métricas de recepción/protocolo, no renderizado.** Las metas “primera vista < 1 s” y “refinamiento visible < 500 ms de mediana” requieren evidencia A24 con navegador/Canvas.

## Estadística

Los p95 usan **nearest-rank**. Se conserva una fila por repetición y también la distribución agrupada. Fallos/cancelaciones no reciben latencia cero. El ahorro de bytes usa:

```text
100 × (bytes_baseline − bytes_normal) / bytes_baseline
```

Los valores negativos se conservan.

## Ejecución local formal

Después de traer esta rama al WSL y confirmar una muestra ya publicada e inmutable:

```bash
cd /home/erwin/PRJIMA

git status
git rev-parse HEAD
java -version
mvn -version

export IMAGE_ID=demo-grande
export SAMPLE_SOURCE='DESCRIBIR LA FUENTE REAL DE LA MUESTRA'
export SAMPLE_PERMISSION='DESCRIBIR EL PERMISO O LICENCIA REAL'
export JPEG_QUALITY_NOTE='85 si fue la calidad real; si no, indicar desconocida'

bash scripts/e24/run-all-workloads.sh
```

El script exige árbol rastreado limpio, repite `scripts/e23/verify-e23-final.sh` sobre el mismo commit y después ejecuta ambas matrices. No importes imágenes, navegues manualmente ni ejecutes otras cargas durante los intervalos medidos.

Los resultados quedan bajo `results/e24/formal-<UTC>/`, incluyendo entorno, preflight, configuraciones, datos originales, logs, CSV de servidor/proceso, agregados, comparaciones y `BACKEND_RESULTS.md`.

## Cierre

E24 permanece **PREPARADA** hasta que la campaña real se ejecute y se revisen sus artefactos. Si aparece un defecto reproducible, se conserva la evidencia inicial, se corrige con el cambio mínimo y se repiten las condiciones afectadas. Los límites solo se cambian con evidencia; la baseline sigue desactivada por defecto.
