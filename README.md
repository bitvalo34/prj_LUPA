# prj_LUPA

LUPA (Lectura Ultraresolutiva Progresiva y Adaptativa) — servidor asíncrono académico en Java 21.

## Base integrada S19 + E19 + A19 + I19 + E20 + A20 + I20 + E21

El transporte y control E21 están incorporados a `main`. A21 amplía el visor **LUPA 64** con desplazamiento, zoom real por VIEW, detalle `-2/-1/0`, lente con foco fijo, composición contexto-detalle y liberación de recursos por época.

Requisitos de desarrollo comprobados:

- JDK 21
- Maven 3.9.x
- libvips 8.15.x para importación

Compilar y probar:

```bash
mvn clean verify package
```

La ejecución normal del servidor usa el catálogo real publicado por A19. En el equipo de desarrollo:

```bash
java -jar target/lupa.jar \
  --host=127.0.0.1 \
  --port=8081 \
  --data-root=/home/erwin/PRJIMA/data
```

Consultar catálogo:

```bash
curl -i http://127.0.0.1:8081/api/catalog
curl -I http://127.0.0.1:8081/api/catalog
```

La fixture de E19 permanece únicamente como modo explícito de prueba:

```bash
java -jar target/lupa.jar --catalog=fixture --port=8081
```

Para importar una imagen se usa `gt.lupa.ingest.IngestApplication` con el mismo `--data-root` que el servidor.

Documentación:

- `docs/contrato-v1.md` — contrato S19.
- `docs/E19.md` — base HTTP.
- `docs/A19.md` y `docs/A19_FINAL_VALIDATION.md` — importador/publicación.
- `docs/I19.md` — integración del catálogo real, comandos y revisión.
- `docs/E20.md` y `docs/E20-closure.md` — transporte WebSocket/LUPA.
- `docs/A20.md` — visor, Worker, Canvas, build frontend y evidencia A20.
- `docs/A20-E20-integration.md` — frontera exacta entre visor y transporte.
- `docs/E21.md` — estado, planificación, foco, épocas y créditos del servidor.
- `docs/A21.md` — navegación, lente, memoria y verificación del cliente.
