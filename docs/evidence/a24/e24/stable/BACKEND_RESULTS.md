# E24 — resultados backend (stable)

Generado desde datos originales. Las latencias son de protocolo/recepción; no son tiempos de Canvas.

| Caché | Clientes | Variante | Primera TILE mediana/p95 ms | DONE mediana/p95 ms | JPEG mediana/run B | Obsoleto bytes | RSS servidor máx MiB | Errores |
|---|---:|---|---:|---:|---:|---:|---:|---:|
| cold | 1 | no-cancel | 55.768/57.101 | 99.765/102.601 | 117484 | 0.00% | 94.10 | 0 |
| cold | 1 | normal | 56.714/57.019 | 101.069/103.083 | 117484 | 0.00% | 94.30 | 0 |
| cold | 5 | no-cancel | 64.227/68.375 | 122.148/123.547 | 587420 | 0.00% | 156.85 | 0 |
| cold | 5 | normal | 83.615/84.660 | 162.331/167.182 | 587420 | 0.00% | 158.86 | 0 |
| cold | 20 | no-cancel | 99.341/103.964 | 196.544/206.220 | 2349680 | 0.00% | 176.82 | 0 |
| cold | 20 | normal | 97.512/98.983 | 196.641/203.260 | 2349680 | 0.00% | 175.05 | 0 |
| warm | 1 | no-cancel | 6.438/7.971 | 16.747/19.313 | 117484 | 0.00% | 95.09 | 0 |
| warm | 1 | normal | 6.180/8.196 | 17.169/20.476 | 117484 | 0.00% | 96.12 | 0 |
| warm | 5 | no-cancel | 7.129/9.175 | 38.221/43.512 | 587420 | 0.00% | 160.71 | 0 |
| warm | 5 | normal | 7.574/8.469 | 41.686/47.679 | 587420 | 0.00% | 160.82 | 0 |
| warm | 20 | no-cancel | 14.194/16.547 | 92.171/103.804 | 2349680 | 0.00% | 187.60 | 0 |
| warm | 20 | normal | 11.904/15.453 | 79.639/88.865 | 2349680 | 0.00% | 181.02 | 0 |

## Comparación normal vs sin cancelación

| Caché | Clientes | Ahorro JPEG mediano | Ahorro app mediano | Obsoleto normal | Obsoleto sin cancelación |
|---|---:|---:|---:|---:|---:|
| cold | 1 | 0.00% | 0.00% | 0.00% | 0.00% |
| cold | 5 | 0.00% | 0.00% | 0.00% | 0.00% |
| cold | 20 | 0.00% | 0.00% | 0.00% | 0.00% |
| warm | 1 | 0.00% | 0.00% | 0.00% | 0.00% |
| warm | 5 | 0.00% | 0.00% | 0.00% | 0.00% |
| warm | 20 | 0.00% | 0.00% | 0.00% | 0.00% |

## Metas visuales

- Primera vista < 1 s: **no evaluable con el cliente técnico**.
- Refinamiento visible < 500 ms mediana: **no evaluable con el cliente técnico**.
- A24 debe medir navegador/Canvas; no se sustituyen esas metas por VIEW→TILE o VIEW→DONE.

p95 usa nearest-rank; fallos no reciben latencia cero; `cold` significa caché LUPA fría, no page cache del SO.
