# E23 — Evidencia formal de comparación preliminar

Fecha de ejecución: 2026-09-24  
Responsable: Erwin Arevalo  
Rama: `feature/e23-backend-measurement`  
Commit de las ejecuciones: `5d9986fa80162ac6c532f91ec08656b289d5d378`

## Objetivo

Validar que las herramientas de E23 permiten comparar de forma reproducible la política normal de sustitución/cancelación de planes frente a una baseline experimental sin cancelación, manteniendo el mismo transporte, pirámide, imagen, recorrido, ventana, política de RELEASE y validación del cliente.

Este ensayo es **preliminar**. No sustituye la campaña formal de E24 con cinco repeticiones por condición y control explícito de caché.

## Configuración común

- imagen: `demo-grande/v1`
- clientes: 5
- escenario: `aggressive`
- VIEW por cliente: 12
- total de VIEW: 60
- intervalo nominal entre VIEW: 5 ms
- semilla: `230023`
- ventana: 1,048,576 bytes
- bitmap budget: 67,108,864 bytes
- detailOffset: 0
- validación JPEG: estructural
- RELEASE del cliente técnico: `discarded`
- renderizado: no existe; las métricas son del cliente técnico y no de Canvas

## Ejecución normal

Run ID:

`e23-20260924T064058Z-c5-aggressive-normal-s230023`

Resultado:

| Métrica | Valor |
|---|---:|
| Clientes configurados | 5 |
| Clientes exitosos | 5 |
| Clientes fallidos | 0 |
| VIEW enviadas | 60 |
| VIEW completadas | 5 |
| VIEW incompletas por sustitución | 55 |
| TILE recibidas | 65 |
| JPEG bytes | 1,163,935 |
| LUPA binary bytes | 1,174,285 |
| TILE obsoletas | 0 |
| JPEG bytes obsoletos | 0 |
| RELEASE enviados | 65 |
| Errores | 0 |

La política normal conserva principalmente la intención vigente: las vistas superadas no continúan hasta DONE.

## Baseline experimental sin cancelación

Run ID:

`e23-20260924T064156Z-c5-aggressive-no-cancel-s230023`

Resultado:

| Métrica | Valor |
|---|---:|
| Clientes configurados | 5 |
| Clientes exitosos | 5 |
| Clientes fallidos | 0 |
| VIEW enviadas | 60 |
| VIEW completadas | 60 |
| VIEW incompletas | 0 |
| TILE recibidas | 928 |
| JPEG bytes | 15,762,553 |
| LUPA binary bytes | 15,910,686 |
| TILE obsoletas | 863 |
| JPEG bytes obsoletos | 14,598,618 |
| RELEASE enviados | 928 |
| Errores | 0 |

La baseline conserva los planes aceptados y los drena en FIFO dentro de una cola experimental acotada. El cliente continúa descartando resultados de épocas que ya no corresponden a la intención activa y envía RELEASE por cada entrega.

## Comparación

| Métrica | Normal | Sin cancelación |
|---|---:|---:|
| VIEW enviadas | 60 | 60 |
| VIEW completadas | 5 | 60 |
| TILE transmitidas | 65 | 928 |
| TILE obsoletas | 0 | 863 |
| JPEG bytes | 1,163,935 | 15,762,553 |
| JPEG bytes obsoletos | 0 | 14,598,618 |
| Primera TILE útil, n | 5 | 5 |
| Primera TILE útil, mediana | 24.876 ms | 169.544 ms |
| Primera TILE útil, p95 | 27.145 ms | 175.174 ms |
| DONE, n | 5 | 60 |
| DONE, mediana | 61.346 ms | 150.014 ms |
| DONE, p95 | 63.797 ms | 175.854 ms |
| Errores | 0 | 0 |

Comprobación de trabajo útil en la baseline:

```text
928 TILE totales - 863 TILE obsoletas = 65 TILE no obsoletas
15,762,553 JPEG bytes - 14,598,618 bytes obsoletos = 1,163,935 bytes no obsoletos
```

Esos valores coinciden exactamente con todo lo transmitido en la ejecución normal.

En esta ejecución concreta, la política normal evitó aproximadamente:

- 93.0 % de las TILE que la baseline terminó transmitiendo;
- 92.6 % de los bytes JPEG transmitidos por la baseline.

Estas cifras describen **este ensayo preliminar**, no una estimación estadística final del proyecto.

## Interpretación y límites

La evidencia confirma que las herramientas distinguen trabajo vigente y obsoleto, registran bytes y latencias, y permiten aislar la política de cancelación bajo VIEW solapadas.

No debe interpretarse `viewsCompleted=5` en modo normal como pérdida de solicitudes: las 55 vistas intermedias fueron sustituidas por intenciones posteriores según la semántica normal. La última intención de cada cliente completó el flujo.

La muestra de tiempo a primera TILE útil contiene cinco observaciones en ambos modos porque una TILE solo se considera útil si corresponde a la intención activa al momento de recepción. En la baseline, las épocas anteriores llegan a DONE pero sus TILE son obsoletas para la intención vigente.

Las mediciones son de aplicación en el proceso del cliente. No son tiempo de renderizado de navegador ni bytes exactos de TCP/WebSocket. La campaña E24 deberá repetir condiciones, conservar fallos, distinguir caché fría/caliente y documentar el número de observaciones usadas en mediana y p95.

## Reproducción

Validación determinista:

```bash
bash scripts/e23/verify-no-cancellation-baseline.sh
```

Servidor normal:

```bash
bash scripts/e23/start-server-normal.sh
```

Cliente:

```bash
bash scripts/e23/run-load-scenario.sh \
  scripts/e23/scenarios/5-aggressive-normal.properties
```

Servidor baseline:

```bash
bash scripts/e23/start-server-no-cancel.sh
```

Cliente:

```bash
bash scripts/e23/run-load-scenario.sh \
  scripts/e23/scenarios/5-aggressive-no-cancel.properties
```

Comparación:

```bash
python3 scripts/e23/compare-load-runs.py \
  <normal>/summary.json \
  <no-cancel>/summary.json
```

## Estado

- herramienta implementada: **sí**
- escenario ejecutado: **sí**
- comparación preliminar reproducible: **sí**
- resultado estadístico final validado: **no; corresponde a E24**
