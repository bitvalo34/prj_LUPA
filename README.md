# prj_LUPA

LUPA (Lectura Ultraresolutiva Progresiva y Adaptativa) — servidor asíncrono académico en Java 21.

## Estado actual

**A24 (6 de octubre de 2026):** la consolidación final, el candidato verificado y la evidencia se describen en [docs/A24.md](docs/A24.md). RFC final: [output/pdf/LUPA-RFC-A24-Evaluacion.pdf](output/pdf/LUPA-RFC-A24-Evaluacion.pdf). Guion: [docs/A24-DEFENSA.md](docs/A24-DEFENSA.md). Construir el frontend antes de empaquetar Java para servir el asset actualizado.

`main` incorpora el desarrollo base, A22/I22, A23/I23 y E24. La revisión A24, la evidencia y el ajuste del visor están preparados; Erwin confirmará el cierre técnico. Su identidad exacta y límites están registrados en docs/A24.md. El historial de módulos se conserva en `docs/`.

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
- `docs/I21.md` — integración de control, dos clientes y reconexión.
- `docs/E22.md` y `docs/E22_EVIDENCE.md` — concurrencia, límites y recursos del servidor.
- `docs/A22.md` — segunda imagen, versiones, fallo de importación, visor y memoria.
- `docs/evidencias/A22.md` — matriz de evidencia y pendientes de validación real.
