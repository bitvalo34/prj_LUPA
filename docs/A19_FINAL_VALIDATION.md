# A19 — Validación final reproducible

Este documento cierra la parte técnica pendiente de A19 sin declarar I19 terminado.

## Evidencia ya verificada en el equipo de Erwin

- Java Temurin 21.0.12.
- Maven 3.9.16.
- libvips 8.15.1.
- build y pruebas de los bloques anteriores correctos.
- importación JPEG completa correcta.
- publicación de `prueba-local/v1` correcta.
- catálogo real servido por E19 con HTTP 200.
- SHA-256 del JPEG fuente y de la copia privada idénticos.
- la importación observada produjo 29 teselas y 224916 bytes JPEG totales para una imagen normalizada de 1096 × 815.

## Política de muestras para el cierre

Mientras no exista una segunda fotografía nativa grande, el cierre técnico puede usar una fotografía propia/autorizada como fuente de tres representaciones locales:

1. **Muestra pequeña JPEG**: copia byte por byte de la fotografía autorizada.
2. **Muestra TIFF real**: TIFF de una sola página generado localmente a partir de esa fotografía con compresión LZW.
3. **Muestra grande manejable JPEG**: derivada 4× mediante `vips resize` con kernel Lanczos3.

La muestra grande conserva contenido fotográfico real pero **no añade detalle óptico nuevo** porque es una ampliación. Para la demostración final es preferible reemplazarla por una fotografía nativa de mayor resolución si el equipo dispone de una.

La documentación oficial de libvips indica que `resize` admite ampliación y que Lanczos3 es el kernel normal de alta calidad; `tiffsave` admite compresión LZW. Estas opciones solo se usan para fabricar muestras locales de validación, no para alterar silenciosamente un original durante la importación.

## Preparación online

Antes de desconectar Internet deben estar disponibles localmente:

```bash
java -version
mvn -version
vips --version
mvn clean verify package
mvn dependency:go-offline
```

El repositorio ya contiene versiones Maven fijadas. libvips debe estar instalado con sus bibliotecas, no basta copiar únicamente `/usr/bin/vips`.

## Validación offline completa

Use una fotografía JPEG propia o con permiso documentado. Primero, con Internet disponible, confirme que Maven tiene todas las dependencias. Luego desconecte Wi-Fi/Ethernet y ejecute:

```bash
cd /home/erwin/PRJIMA
git switch feature/a19-ingest
git pull --ff-only origin feature/a19-ingest

OFFLINE_ASSERT=1 KEEP_A19_FINAL_RUN=1 \
  ./scripts/verify-a19-final.sh /home/erwin/PRJIMA/prueba-real.jpg
```

`OFFLINE_ASSERT=1` hace fallar la comprobación si Maven Central continúa accesible. `mvn -o` obliga a Maven a usar únicamente el repositorio local. Las importaciones y libvips no requieren Internet.

Al finalizar debe aparecer:

```text
A19 FINAL VALIDATION OK
```

El script imprime la ruta temporal conservada cuando `KEEP_A19_FINAL_RUN=1`. En ella quedan las muestras derivadas, `data/catalog.json`, metadata privada, manifiestos y pirámides para inspección.

## Qué verifica el script

- build y JUnit con Maven offline;
- loader JPEG real de la fotografía fuente;
- generación TIFF de una página;
- generación de una muestra grande manejable;
- importación real de JPEG;
- importación real de TIFF;
- importación real de la muestra grande;
- conservación SHA-256 de cada original privado;
- catálogo con tres imágenes publicadas;
- límite de teselas ya aplicado por el importador;
- espacio final medido en bytes;
- duración real de cada importación;
- compatibilidad de `/api/catalog` de E19 usando el catálogo publicado.

La última comprobación es solo evidencia de compatibilidad A19→E19. **No declara I19 completada.**

## Recursos de libvips

A19 ejecuta libvips con valores iniciales conservadores:

- concurrencia: 2;
- caché: hasta 100 operaciones;
- caché de imágenes: hasta 256 MiB;
- archivos abiertos por caché: hasta 100.

Son parámetros de libvips, no una garantía de memoria RSS total. El proceso, codecs, mmap, buffers del sistema y Java pueden consumir memoria adicional.

## Registro de resultados

Después de una corrida exitosa, copie de la salida del script a esta tabla:

| Muestra | Fuente/licencia | Formato | Dimensiones | Bytes original | Versión | Niveles | Teselas | Bytes teselas | Duración ms | Resultado |
|---|---|---|---:|---:|---|---:|---:|---:|---:|---|
| pequeña | fotografía propia/autorizada | JPEG | pendiente de corrida | pendiente | v1 | pendiente | pendiente | pendiente | pendiente | pendiente |
| TIFF | derivada local de la misma fotografía | TIFF LZW, 1 página | pendiente | pendiente | v1 | pendiente | pendiente | pendiente | pendiente | pendiente |
| grande manejable | derivada 4× de la misma fotografía | JPEG | pendiente | pendiente | v1 | pendiente | pendiente | pendiente | pendiente | pendiente |

## Matriz de cierre de A19

| Criterio | Evidencia esperada | Estado antes de la corrida final |
|---|---|---|
| Importador Java ejecutable | comando `import` | verificado con JPEG |
| ProcessBuilder + libvips local | código + libvips 8.15.1 | verificado |
| Original privado e intacto | SHA-256 fuente/copia | verificado con JPEG |
| Pirámide 256/0/onetile | manifiesto + teselas | verificado con JPEG |
| Normalización | autorot + sRGB + alpha blanco | código entregado; integración JPEG verificada |
| Validación de todas las teselas | `TilePyramidValidator` | verificado en importación JPEG |
| Límite 262144 bytes | validador | verificado en importación JPEG |
| Manifiesto/catálogo S19 | catálogo real leído por E19 | verificado |
| Publicación consistente | versión inmutable + catálogo atómico | código entregado y publicación JPEG verificada |
| Errores preservan catálogo | pruebas JUnit/código | código entregado; ampliar pruebas si se requiere evidencia de cada fallo inyectado |
| JPEG real | importación completa | verificado |
| TIFF real 1 página | script final | pendiente de ejecutar |
| Muestra pequeña | script final + permiso | pendiente de ejecutar |
| Muestra grande manejable | script final + permiso | pendiente de ejecutar |
| Maven offline | `mvn -o` con red desconectada | pendiente de ejecutar |
| Importación completa offline | script con `OFFLINE_ASSERT=1` | pendiente de ejecutar |
| Preparada para I19 | `/api/catalog` sobre catálogo real | compatibilidad ya verificada; I19 sigue pendiente |

A19 solo debe marcarse cerrada cuando la corrida final complete los elementos pendientes y se registre la evidencia real; no se deben inventar duraciones, tamaños o resultados.
