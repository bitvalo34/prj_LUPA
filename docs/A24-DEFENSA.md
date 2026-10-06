# A24 - Guion de ejecución y defensa

Integrantes: Erwin Arevalo (23001717) y Adrian Romero (23004161).
Fecha de consolidación: 6 de octubre de 2026.

Este guion acompaña `output/pdf/LUPA-RFC-A24-Evaluacion.pdf`. La revisión de Adrian se conserva como base del RFC. No se atribuye a Adrian una revisión cruzada nueva ni una ejecución que no esté registrada.

## Preparación reproducible

Desde la raíz del repositorio, con Java 21, Maven >=3.9, Node 22 y dependencias ya disponibles:

```bash
cd frontend
npm test
npm run build
cd ..
mvn -o verify
java -jar target/lupa.jar --host=127.0.0.1 --port=18084 --data-root=/home/erwin/PRJIMA/data
```

En este equipo el Maven del sistema es 3.8.7 y no cumple el requisito. Se utilizó `/home/erwin/.sdkman/candidates/maven/current/bin/mvn` (3.9.16). Node está en `/home/erwin/.nvm/versions/node/v22.22.0/bin`.

El frontend debe compilarse ANTES de empaquetar Java. La revisión A24 corrigió `src/main/resources/web/index.html` para cargar `app-E9QJGLo1.js`, generado por el código actual. El índice anterior cargaba `app-Dyp4zZvL.js`, procedente de I22. El archivo nuevo debe incluirse al versionar/entregar; no basta cambiar el índice. Los hashes del candidato medido están en `docs/evidence/a24/candidate-files.sha256`.

El catálogo para la demostración debe estar publicado previamente. No descargar dependencias, tipografías ni imágenes durante la evaluación. Los originales y las grandes pirámides son datos locales y no se incluyen en el paquete documental A24. La preparación/entrega completa del proyecto sigue correspondiendo al cierre del día 25.

## Recorrido sugerido: 8-10 minutos

| Tiempo | Acción | Explicación y evidencia observable |
|---|---|---|
| 0:00-1:00 | Abrir `http://127.0.0.1:18084` y el catálogo | Java sirve HTML, CSS, JS y catálogo. No hay CDN ni servicio externo en el recorrido. |
| 1:00-2:00 | Seleccionar `demo-grande` o `eval-93` | HTTP inicial; Upgrade a WebSocket `lupa.v1`; HELLO/WELCOME; OPEN/MANIFEST; VIEW/PLAN; TILE; confirmaciones; DONE. La imagen original no se descarga. |
| 2:00-3:00 | Alternar detalle -2, 0 y -2 | Mostrar niveles PLAN distintos y el descenso de memoria administrada. No es únicamente ampliar píxeles: cambian las teselas transmitidas y se cierran bitmaps. |
| 3:00-4:00 | Activar Lente y mover/navegar | Contexto y foco usan niveles distintos. PLAN comunica lo realmente aplicado; el presupuesto puede reducir el nivel. |
| 4:00-5:00 | Realizar cambios rápidos de vista | Cada intención usa una época. Se cancela trabajo no comprometido; lo ya comprometido termina el frame, se descarta si es antiguo y se confirma sin dibujarlo. |
| 5:00-6:00 | Abrir un segundo navegador con otra región | Las sesiones no comparten intención, créditos ni épocas. Comparten caché JPEG y admisión DRR. Explicar que las relecturas STA no pasan por DRR. |
| 6:00-7:00 | Mostrar importación local y refrescar catálogo | Original privado, staging, validación de todos los JPEG, versión inmutable y actualización atómica del catálogo. Una entrada inválida no reemplaza el catálogo válido. |
| 7:00-8:00 | Reconectar y presentar resultados | La conexión nueva inicia sesión nueva; no hay reanudación persistente. Mostrar JSON/capturas A24 y tablas E24, distinguiendo latencia backend de Canvas. |
| 8:00-10:00 | Preguntas y limitaciones | Defender decisiones, presupuestos, límites de medición y defectos conocidos; no afirmar garantías no verificadas. |

## Comandos verificados de evidencia

Importación aislada, sin tocar el catálogo principal:

```bash
bash scripts/a24/verify-import.sh
```

Requiere la muestra autorizada local `prueba-real.jpg`. Publica en una carpeta nueva bajo `results/a24/import`, guarda hashes y comprueba fallo controlado. En esta ejecución: 29 teselas, 224916 bytes JPEG; original de 176062 bytes sin modificación; entrada inválida con salida 3 y catálogo idéntico.

Campaña de Canvas, con servidor ya iniciado y navegador Chrome/Chromium instalado:

```bash
A24_BROWSER=/ruta/al/chrome node scripts/a24/measure-visual.mjs \
  http://127.0.0.1:18084 results/a24/repeticion demo-grande eval-93
```

En Windows se ejecutó Node con `A24_BROWSER=C:\Program Files\Google\Chrome\Application\chrome.exe`; el servidor permaneció en WSL. Cinco repeticiones por imagen, un cliente, 1440x1000 y DPR 1. El runner deriva el tráfico externo del navegador a un proxy local no disponible; permite loopback. No desactiva la red del sistema operativo.

La medición usa el primer `drawImage` por deliveryId de cada época, no los repintados posteriores. Verifica todos los ids esperados por DONE; conserva también una aproximación con doble requestAnimationFrame. No mide el fotón emitido por la pantalla ni latencia humana. La pausa de estabilización del runner no se suma al tiempo registrado.

## Preguntas que ambos deben poder responder

1. **¿Dónde queda la imagen?** Original o referencia privada y pirámide en disco del servidor. El navegador conserva solo JPEG transitorios e ImageBitmap de las piezas necesarias; el Canvas representa la vista, no un archivo completo de decenas de GB.
2. **¿Qué devuelve un crédito?** Una confirmación terminal válida de una reserva conocida o cancelación precompromiso. El ACK duplicado devuelve cero. Terminar socket.write libera el buffer transitorio, no el crédito de consumo.
3. **¿DONE implica imagen visible?** No. Cuenta envíos originales escritos. El Worker y Canvas pueden seguir trabajando; A24 mide el dibujo por separado.
4. **¿Por qué retransmitir si TCP ya es fiable?** STA recupera un fallo de consumo/decodificación de aplicación. Conserva deliveryId y reserva; no reemplaza la retransmisión de TCP ni detecta pérdidas de segmentos.
5. **¿Cómo se importa un PNG gigante?** Hay una herramienta de evaluación que consume un stream con pyvips, produce DeepZoom y luego Java valida/publica mediante `import-prepared-dz`. Es distinta del importador ordinario JPEG/TIFF. No se debe decir que toda entrada usa exactamente el mismo recorrido.
6. **¿Está toda la memoria limitada a 64 MiB?** No. Es el presupuesto administrado de bitmaps del cliente. El proceso, navegador, GPU y codec tienen otros consumos. La JVM y libvips tampoco se reducen a esos 64 MiB.
7. **¿Qué pasa si falla publicar el catálogo?** Puede quedar una versión completa sin referencia; el catálogo anterior permanece válido. No se promete una transacción única que abarque todo el sistema de archivos.
8. **¿Qué queda limitado?** Sin autenticación/TLS propio, sin reanudación de sesión ni presupuesto dinámico. Los historiales por conexión de DRR/Worker requieren limpieza para garantizar operación indefinida con reconexiones ilimitadas. No se presenta esa garantía como aprobada.

El guion queda preparado y sustentado; un ensayo realizado por los integrantes debe registrarse después de hacerlo. La elaboración de este archivo no acredita que ambos ya hayan defendido o ensayado el proyecto.
