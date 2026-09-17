# A19 scale hardening

Optimización localizada del importador A19 antes de continuar con E20. No cambia el contrato S19, el layout público ni el formato de catálogo/manifiesto.

## Motivo

La línea base con un TIFF real de `40000 x 30131` (1,205,240,000 píxeles, 4,211,236,490 bytes) produjo 24,796 teselas y tardó al menos ~51 min 16 s con la validación original basada en procesos `vipsheader` + `vips avg` por tesela.

Después de mover la validación de JPEG a Java, con 2–4 workers acotados, la misma entrada terminó en:

```text
TIEMPO TOTAL: 1:03.93
SEGUNDOS: 63.93
MAX RSS: 245112 KB
tileCount=24796
tileJpegBytes=912587833
```

La mejora observada fue de al menos ~48x manteniendo la misma cantidad y bytes de teselas.

## Cambios

### 1. Validación de teselas in-process

Cada JPEG publicado (máximo 256x256) se valida en Java:

- archivo regular, sin symlink;
- tamaño `1..262144` bytes;
- firma JPEG;
- decoder JPEG disponible;
- dimensiones exactas, incluidos bordes;
- decodificación completa;
- 3 bandas RGB.

No se usa `ImageIO` sobre el original gigante: únicamente sobre teselas ya generadas de hasta 256x256.

La validación utiliza 2–4 workers (máximo 4 por defecto) y mantiene una cantidad acotada de trabajos en vuelo.

### 2. Progreso por fases

La importación informa fases y progreso sin registrar una línea por tesela:

```text
[A19] preflight...
[A19] inspecting original...
[A19] preflight summary
[A19] staging and generating pyramid...
[A19] normalizing image...
[A19] generating pyramid: levels=... maxLevel=... normalized=...x...
[A19] validating pyramid...
[A19] validating tiles 0 / ... (0.0%)
[A19] validating tiles ... / ... (10.0%)
...
[A19] validation complete: ... tiles
[A19] preserving original metadata/data...
[A19] publishing immutable pyramid version...
[A19] updating catalog atomically...
[A19] publication complete
```

### 3. Política de original

Nueva opción:

```text
--original-policy=copy|reference
```

Valor inicial: `copy`.

- `copy`: comportamiento A19 previo. Copia el original a `data/originals/<imageId>/<version>/source.*`, verifica SHA-256 y conserva metadata privada.
- `reference`: no duplica el archivo. Mantiene una referencia privada absoluta en `import-metadata.json`, conserva SHA-256 y crea el directorio/versionado privado de metadata. El administrador debe mantener ese archivo en su ubicación privada; no se expone por HTTP.

`reference` es útil para originales de decenas o cientos de GB cuando duplicar el archivo no aporta valor operativo.

Ejemplo:

```bash
java -Xmx1024m -cp target/lupa.jar \
  gt.lupa.ingest.IngestApplication import \
  --original=/private/images/huge.tif \
  --image-id=huge-demo \
  --display-name="Huge demo" \
  --license-ref="Private authorized sample" \
  --data-root=/home/erwin/PRJIMA/data \
  --vips=vips \
  --vipsheader=vipsheader \
  --timeout-seconds=7200 \
  --jpeg-quality=85 \
  --original-policy=reference
```

## Preflight mejorado

`preflight` inspecciona el formato/dimensiones y muestra:

```text
originalBytes=...
dimensions=40000x30131
dataRoot=...
usableBytes=...
estimatedRequiredBytes=...
originalPolicy=reference
preflightSpace=OK
```

La estimación es deliberadamente conservadora. No representa una cota estricta del RSS o del espacio temporal interno de libvips.

Con `reference`, la estimación no reserva otra copia completa del original. Con `copy`, sí la incluye.

## Regresión recomendada

```bash
mvn clean verify package
mvn -o clean verify package
```

Prueba rápida de `reference`:

```bash
java -cp target/lupa.jar gt.lupa.ingest.IngestApplication preflight \
  --original=/home/erwin/PRJIMA/eso1242a.tif \
  --image-id=preflight-scale \
  --display-name="Preflight scale" \
  --license-ref="Authorized sample" \
  --data-root=/home/erwin/PRJIMA/data \
  --original-policy=reference \
  --timeout-seconds=7200
```

Antes de E20 se debe confirmar en el WSL de Erwin que las regresiones pasan y realizar al menos una importación real con `--original-policy=reference`.
