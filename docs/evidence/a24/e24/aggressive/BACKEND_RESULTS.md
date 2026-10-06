# E24 — resultados backend (aggressive)

Generado desde datos originales. Las latencias son de protocolo/recepción; no son tiempos de Canvas.

| Caché | Clientes | Variante | Primera TILE mediana/p95 ms | DONE mediana/p95 ms | JPEG mediana/run B | Obsoleto bytes | RSS servidor máx MiB | Errores |
|---|---:|---|---:|---:|---:|---:|---:|---:|
| cold | 1 | no-cancel | 198.241/215.456 | 170.721/209.836 | 1129423 | 92.59% | 101.86 | 0 |
| cold | 1 | normal | 9.118/12.586 | 35.908/46.009 | 94642 | 6.09% | 93.36 | 0 |
| cold | 5 | no-cancel | 224.551/244.960 | 196.847/241.196 | 5729661 | 92.54% | 168.01 | 0 |
| cold | 5 | normal | 34.061/43.769 | 89.053/97.233 | 427080 | 0.00% | 145.88 | 0 |
| cold | 20 | no-cancel | 463.483/488.514 | 346.377/484.899 | 22838064 | 92.50% | 215.23 | 0 |
| cold | 20 | normal | 43.939/51.827 | 109.480/133.061 | 1708320 | 0.00% | 183.32 | 0 |
| warm | 1 | no-cancel | 14.188/34.440 | 25.963/34.635 | 1103386 | 92.34% | 124.14 | 0 |
| warm | 1 | normal | 2.158/5.097 | 6.774/10.423 | 638146 | 40.57% | 108.27 | 0 |
| warm | 5 | no-cancel | 89.598/111.107 | 84.868/112.798 | 5599476 | 92.39% | 282.08 | 0 |
| warm | 5 | normal | 7.416/14.192 | 19.039/22.007 | 1473407 | 70.71% | 165.63 | 0 |
| warm | 20 | no-cancel | 357.250/396.497 | 256.827/378.523 | 22317324 | 92.53% | 282.96 | 0 |
| warm | 20 | normal | 29.601/40.148 | 62.087/68.390 | 3401006 | 51.08% | 245.17 | 0 |

## Comparación normal vs sin cancelación

| Caché | Clientes | Ahorro JPEG mediano | Ahorro app mediano | Obsoleto normal | Obsoleto sin cancelación |
|---|---:|---:|---:|---:|---:|
| cold | 1 | 91.75% | 91.43% | 6.09% | 92.59% |
| cold | 5 | 92.55% | 92.22% | 0.00% | 92.54% |
| cold | 20 | 92.51% | 92.18% | 0.00% | 92.50% |
| warm | 1 | 41.15% | 41.02% | 40.57% | 92.34% |
| warm | 5 | 73.78% | 73.53% | 70.71% | 92.39% |
| warm | 20 | 84.76% | 84.46% | 51.08% | 92.53% |

## Metas visuales

- Primera vista < 1 s: **no evaluable con el cliente técnico**.
- Refinamiento visible < 500 ms mediana: **no evaluable con el cliente técnico**.
- A24 debe medir navegador/Canvas; no se sustituyen esas metas por VIEW→TILE o VIEW→DONE.

p95 usa nearest-rank; fallos no reciben latencia cero; `cold` significa caché LUPA fría, no page cache del SO.
