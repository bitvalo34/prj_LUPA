# E23 — Matriz de cierre técnico

Responsable: **Erwin Arevalo**  
Cierre técnico preparado: 25 de septiembre de 2026.

Este documento distingue evidencia ejecutada en el equipo de Erwin de artefactos implementados en el repositorio. La campaña estadística formal corresponde a E24.

## Evidencia ejecutada en el equipo

| Verificación | Resultado observado |
|---|---|
| ampliación HTTP/WS/LUPA inicial | 31 tests, 0 failures, 0 errors |
| batería backend E23 inicial | 92 tests, 0 failures, 0 errors |
| RELEASE/épocas/créditos/cancelación | 36 tests, 0 failures, 0 errors |
| batería backend después de créditos | 93 tests, 0 failures, 0 errors |
| saturación/recuperación | 29 tests, 0 failures, 0 errors |
| batería backend final de esa subfase | 96 tests, 0 failures, 0 errors |
| baseline sin cancelación | 20 tests, 0 failures, 0 errors |
| carga válida de 1 cliente | 1/1 OK, 6/6 VIEW, 0 errors |
| carga válida de 5 clientes | 5/5 OK, 30/30 VIEW, 0 errors |
| carga válida de 20 clientes | 20/20 OK, 120/120 VIEW, 0 errors |
| comparación agresiva normal | 5/5 clientes, 0 errors |
| comparación agresiva no-cancel | 5/5 clientes, 0 errors |

Durante el desarrollo se detectó java.lang.IllegalStateException: Send pending. La causa era el solapamiento de sendText en el cliente Java. La evidencia se conservó, se serializaron todos los envíos por conexión y se repitieron los escenarios afectados con errors=0.

## Matriz criterio / evidencia / estado

| Criterio E23 | Evidencia | Estado |
|---|---|---|
| errores HTTP repetibles | parser + integración + EOF/timeout/rutas/admisión | Cumplido |
| errores apertura/tramas WebSocket | handshake/raw/máscara/RSV/fragmentación/heartbeat | Cumplido |
| errores LUPA y conservación de estado | policy/robustness/regresión | Cumplido |
| RELEASE válido por cada status | pruebas de sesión | Cumplido |
| RELEASE duplicado/desconocido idempotente | pruebas + invariante créditos | Cumplido |
| épocas antiguas y entregas pendientes | pruebas de sesión | Cumplido |
| carreras de cancelación deterministas | sender y executor controlados | Cumplido |
| free + reserved = window | aserciones en transiciones | Cumplido |
| desconexión con I/O tardío | robustez/cleanup | Cumplido |
| cliente sin RELEASE aislado | fairness + créditos | Cumplido |
| cliente que no lee aislado | WebSocket lento real | Cumplido |
| límite sesiones/read admission | pruebas de admisión | Cumplido |
| saturación disco y recuperación | StorageExecutorsTest | Cumplido |
| recursos/buffers acotados | regresión E22/E23 | Cumplido |
| cliente de carga real | E23LoadClient | Cumplido |
| escenarios 1/5/20 ejecutados | resultados locales reportados | Cumplido |
| logs estructurados y resumen | JSONL + CSV + JSON | Cumplido |
| bytes JPEG/LUPA diferenciados | esquema del cliente | Cumplido |
| TILE obsoletas registradas | escenario agresivo | Cumplido |
| baseline sin cancelación separada | opción JVM + cola acotada | Cumplido |
| modo normal restaurado por defecto | propiedad experimental false por defecto | Cumplido |
| comparación preliminar ejecutada | evidencia versionada | Cumplido |
| campaña E24 preparada | cold/warm, 1/5/20, cinco repeticiones | Cumplido |
| funcionamiento offline | Maven -o, Java y datos locales | Cumplido |
| protocolo público sin mensajes experimentales | opción local de arranque | Cumplido |

## Fallo prioritario corregido

Clasificación: **medición incorrecta**.

Síntoma: RELEASE_SEND_ERROR / IllegalStateException: Send pending.

Causa: varios mensajes WebSocket podían solaparse sobre una misma conexión del cliente de carga.

Corrección: serialización de RELEASE y posteriormente de todos los controles salientes por conexión.

Regresión: escenarios repetidos con clientes exitosos y errors=0. No se modificó la semántica del servidor para ocultar el defecto.

## Separación E23 / E24

E23 entrega herramientas, pruebas, escenarios y una comparación preliminar. La campaña completa de cinco repeticiones y sus resultados estadísticos corresponden a E24.

Como validación del handoff, Erwin ejecutó posteriormente la matriz formal de E24: 12 condiciones, 5 repeticiones por condición, conditionFailures=0 y validatorExit=0. Esto confirma que el procedimiento preparado por E23 es ejecutable; no convierte la comparación preliminar de E23 en resultado estadístico final.

## Estado

**E23 está técnicamente completa**, sujeto a conservar una ejecución final sobre el HEAD que se vaya a mergear:

    bash scripts/e23/verify-e23-final.sh

La revisión humana y el merge son actividades de integración, no funciones faltantes.
