# A19 — Validación final reproducible

Este documento registra la evidencia medida para el cierre técnico de A19. **No declara I19 completada.**

## Entorno verificado

- WSL2.
- Java Temurin 21.0.12.
- Maven 3.9.16.
- libvips 8.15.1.
- loaders JPEG y TIFF disponibles.
- `dzsave`, `autorot`, `colourspace`, `flatten`, `jpegsave` y `tiffsave` disponibles.

## Política de muestras

La validación utilizó una fotografía propia/autorizada indicada por Erwin como fuente de tres representaciones locales:

1. **Pequeña JPEG**: copia byte por byte de la fotografía autorizada.
2. **TIFF real**: TIFF LZW de una sola página generado localmente a partir de la misma fotografía.
3. **Grande manejable JPEG**: derivada 4× mediante `vips resize`.

La muestra grande conserva contenido fotográfico real, pero no añade detalle óptico nuevo porque es una ampliación. Para una demostración posterior puede sustituirse por una fotografía nativa de mayor resolución sin cambiar el importador.

Las muestras son datos de validación local y no forman parte del código fuente del repositorio.

## Validación offline

El recorrido final se ejecutó con Maven en modo offline y con el script:

```bash
OFFLINE_ASSERT=1 KEEP_A19_FINAL_RUN=1 \
  ./scripts/verify-a19-final.sh /home/erwin/PRJIMA/prueba-real.jpg
```

La preparación previa con Internet se usó únicamente para disponer localmente de Java, Maven/plugins/dependencias y libvips. La corrida offline no necesitó descargas.

## Resultado de build y pruebas

- `mvn -o clean verify package`: **BUILD SUCCESS**.
- JUnit: **34 tests**, 0 failures, 0 errors, 0 skipped.
- JAR empaquetado correctamente.

Los warnings del `maven-shade-plugin` corresponden a recursos/clases duplicados durante una segunda ejecución de la fase `package` dentro del mismo comando compuesto; no produjeron fallo de build. Para uso manual basta `mvn -o clean verify` o el script reproducible incluido.

## Resultados medidos

| Muestra | Fuente/licencia | Formato | Dimensiones | Bytes original | Versión | Niveles | Teselas | Bytes teselas | Duración ms | Resultado |
|---|---|---|---:|---:|---|---:|---:|---:|---:|---|
| pequeña | fotografía propia/autorizada | JPEG | 1096 × 815 | 176062 | v1 | 4 | 29 | 224916 | 5328 | OK |
| TIFF | derivada local autorizada | TIFF LZW, 1 página | 1096 × 815 | 388238 | v1 | 4 | 29 | 224916 | 4506 | OK |
| grande manejable | derivada 4× de la fotografía autorizada | JPEG | 4384 × 3260 | 1179381 | v1 | 6 | 326 | 1600978 | 44647 | OK |

Totales de la corrida:

- teselas publicadas: **384**;
- bytes JPEG de teselas: **2050810**;
- mayor tesela observada: **24104 bytes**, muy por debajo del límite LUPA de 262144 bytes;
- almacenamiento final medido bajo `data/`: **3798351 bytes**.

## Catálogo real publicado

La corrida produjo tres entradas válidas:

```json
{
  "schemaVersion": 1,
  "images": [
    {
      "imageId": "a19-large",
      "imageVersion": "v1",
      "width": 4384,
      "height": 3260,
      "tileSize": 256,
      "maxLevel": 5
    },
    {
      "imageId": "a19-small",
      "imageVersion": "v1",
      "width": 1096,
      "height": 815,
      "tileSize": 256,
      "maxLevel": 3
    },
    {
      "imageId": "a19-tiff",
      "imageVersion": "v1",
      "width": 1096,
      "height": 815,
      "tileSize": 256,
      "maxLevel": 3
    }
  ]
}
```

## Integridad del original

SHA-256 de la fotografía fuente antes y después de la corrida:

```text
783ab0e877d685b9a35a17de1c8e164dc179852d97bb738cf7b72499a6aa3459
```

El hash permaneció idéntico. Las copias privadas publicadas también se comprueban por SHA-256 durante el importador.

## Compatibilidad con E19

El script arrancó E19 con `--catalog=file` sobre el catálogo generado por A19 y obtuvo el JSON de las tres imágenes mediante `/api/catalog`.

Durante el bucle de espera aparece normalmente un primer `curl: (7)` antes de que el servidor termine de abrir el socket. El intento posterior devolvió correctamente el catálogo y el script terminó en `A19 FINAL VALIDATION OK`; por tanto ese primer intento no representa un fallo funcional.

Esta prueba demuestra compatibilidad A19 → E19. **I19 sigue pendiente** porque su alcance incluye la integración formal del siguiente bloque del proyecto.

## Recursos de libvips

A19 ejecuta operaciones libvips con parámetros iniciales conservadores:

- concurrencia: 2;
- caché: hasta 100 operaciones;
- caché de imágenes: hasta 256 MiB;
- archivos abiertos por caché: hasta 100.

Estos parámetros no constituyen una cota estricta del RSS total del proceso.

## Matriz de cierre técnico

| Criterio | Evidencia | Estado |
|---|---|---|
| Importador Java ejecutable | comando `import` y tres importaciones completas | ✅ verificado |
| ProcessBuilder + libvips local | libvips 8.15.1, ejecución local | ✅ verificado |
| Original privado e intacto | SHA-256 estable y copia privada | ✅ verificado |
| JPEG real | `a19-small/v1` | ✅ verificado |
| TIFF real de una página | `a19-tiff/v1` | ✅ verificado |
| Muestra grande manejable | `a19-large/v1`, 4384 × 3260 | ✅ verificado |
| Pirámide 256 / overlap 0 / onetile | manifiestos + importaciones | ✅ verificado |
| Normalización | autorot + sRGB + alpha blanco | ✅ implementado y recorrido real |
| Todas las teselas validadas | 384 teselas recorridas | ✅ verificado |
| Límite 262144 bytes | máximo observado 24104 bytes | ✅ verificado |
| Manifiesto/catálogo S19 | catálogo con tres imágenes | ✅ verificado |
| Publicación consistente | versiones v1 + catálogo válido | ✅ verificado |
| Catálogo real consumible por E19 | `/api/catalog` sobre catálogo real | ✅ verificado |
| Maven offline | `mvn -o` | ✅ verificado |
| Importación completa offline | script con `OFFLINE_ASSERT=1` | ✅ verificado |
| Herramientas disponibles offline | Java, Maven local, libvips local | ✅ verificado |
| Duración y espacio medidos | tabla y totales anteriores | ✅ registrado |
| I19 | integración formal posterior | ⏳ no pertenece al cierre de A19 |

## Estado

**A19 queda cerrada técnicamente en el equipo de Erwin**, con evidencia online y offline del recorrido principal, JPEG/TIFF, muestra grande manejable, publicación consistente y compatibilidad del catálogo con E19.

Los datos generados bajo `data/` y las fotografías de validación son artefactos locales; no deben versionarse como código fuente.
