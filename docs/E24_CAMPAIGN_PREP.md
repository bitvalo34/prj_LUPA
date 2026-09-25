# E24 — Procedimiento preparado desde E23

E23 deja lista esta campaña, pero **no presenta estas ejecuciones como resultados finales**.

## Variables

- `MODE=normal|no-cancel`
- `CACHE=cold|warm`
- `CLIENTS=1|5|20`
- `REPETITIONS=5` por defecto
- `SCENARIO=aggressive` por defecto

Ejemplo:

```bash
MODE=normal CACHE=cold CLIENTS=5 REPETITIONS=5 \
  bash scripts/e24/run-campaign.sh
```

## Definición de caché

**cold**: cada repetición arranca un JVM nuevo de LUPA. Por tanto la caché JPEG comprimida de la aplicación empieza vacía.

Esto **no vacía la page cache del sistema operativo** y no requiere privilegios.

**warm**: se arranca un único JVM, se ejecuta una corrida de calentamiento que no se incluye en el resumen y después se realizan las repeticiones medidas sin reiniciar el servidor.

## Matriz recomendada

Para cada modo:

- normal
- no-cancel

y para cada estado:

- cold
- warm

ejecutar:

- 1 cliente × 5 repeticiones
- 5 clientes × 5 repeticiones
- 20 clientes × 5 repeticiones

La campaña conserva cada `summary.json`, salida del cliente y log del servidor.

## Estadística

`summarize-campaign.py` produce:

- `runs.csv`: una fila por repetición;
- `aggregate.json`: agregado de la condición.

Mediana y p95 se calculan sobre **observaciones VIEW exitosas**, no sobre cinco promedios de repetición. Se utiliza percentil nearest-rank. El número de muestras `n` se conserva.

Los clientes fallidos y errores se cuentan por separado y no desaparecen del resumen.

## Ejecución completa sugerida

```bash
for mode in normal no-cancel; do
  for cache in cold warm; do
    for clients in 1 5 20; do
      MODE="$mode" CACHE="$cache" CLIENTS="$clients" REPETITIONS=5 \
        bash scripts/e24/run-campaign.sh
    done
  done
done
```

La comparación de foco frente a uniforme debe mantenerse como experimento independiente de la comparación de cancelación.
