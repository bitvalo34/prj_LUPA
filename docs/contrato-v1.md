# LUPA — Contrato v1

**Tarea:** S19 — Coordinación y contrato  
**Proyecto:** LUPA — Lectura Ultraresolutiva Progresiva y Adaptativa  
**Integrantes:** Erwin Arevalo (23001717) y Adrian Romero (23004161)  
**Responsable de E19:** Erwin Arevalo  
**Responsable de A19:** Adrian Romero  
**Fecha de preparación:** 15 de septiembre de 2026

Este documento fija el contrato técnico mínimo para que E19 y A19 puedan desarrollarse sin incompatibilidades. Reutiliza la arquitectura y la semántica de `Avances_LUPA.docx`. Cuando un detalle no estaba fijado de forma exacta en ese documento y se necesita para implementar, se marca como **concreción S19**.

S19 no implementa todavía el visor completo, WebSocket completo, planificador, créditos, épocas ni transmisión funcional de teselas.

---

## 1. Estado real del repositorio

### 1.1 Verificación realizada

Estado verificado en el equipo de Erwin:

- Entorno: Ubuntu sobre WSL2.
- Directorio local: `/home/erwin/PRJIMA`.
- Remoto: `https://github.com/bitvalo34/prj_LUPA`.
- Rama local inicial: `master`.
- `master`: `818cc2b` — `Initial Files`.
- `origin/master`: `818cc2b`.
- `origin/main`: `e946e1e` — `Initial commit`.
- No existían cambios locales sobre archivos rastreados.
- Existía `outputs/` como directorio no rastreado con la calendarización.
- No se encontraron `AGENTS.md`, instrucciones adicionales, `pom.xml` ni implementación funcional.
- No se observaron commits nuevos posteriores al estado registrado el 13/09.

Además, `main` y `master` pertenecen a historiales independientes. No se utilizará `--allow-unrelated-histories` para unirlos.

### 1.2 Rama común

**Decisión S19:** la rama común de integración será `develop`, creada desde `main`.

Razón: `main` es la rama predeterminada del repositorio y permite mantener una historia limpia para el desarrollo futuro. `master` queda intacta como referencia histórica del avance documental entregado.

Flujo previsto:

- `develop`: integración estable del trabajo de la semana.
- `feature/e19-http`: trabajo de Erwin para E19.
- `feature/a19-ingest`: trabajo de Adrian para A19.
- I19 integra ambos resultados en `develop` después de comprobar este contrato.

No se desarrollará funcionalidad directamente sobre `main`.

### 1.3 Entorno Java

En la comprobación inicial, el equipo de Erwin tenía Java 22 activo.

El diseño de LUPA mantiene **Java 21** como versión objetivo, compatible con el requisito académico que permite Java 20 o 21. Antes de iniciar E19 deberá configurarse un JDK 21 para compilación y ejecución reproducible.

---

## 2. Coordinación y reparto

### 2.1 Acuerdos confirmados

- Erwin implementa y mantiene E19.
- Adrian implementará A19.
- Para S19, Erwin redacta el contrato y Adrian realizará revisión posterior.
- Al iniciar S19 no existían acuerdos adicionales entre ambos sobre ramas, muestras o interfaces.

### 2.2 Propiedad de módulos

**Erwin — E19**

Mantiene principalmente:

- configuración Maven y Java 21;
- `src/main/java/gt/lupa/http/`;
- transporte HTTP inicial;
- recursos web iniciales;
- `/api/catalog`;
- pruebas del parser y respuestas HTTP;
- interfaz consumida por HTTP para obtener catálogo/manifiestos;
- fixture temporal mientras A19 no esté integrado.

**Adrian — A19**

Mantiene principalmente:

- `src/main/java/gt/lupa/ingest/`;
- preparación e importación de originales;
- ejecución local de libvips;
- publicación de pirámides;
- manifiestos persistidos;
- catálogo publicado;
- datos de demostración.

### 2.3 Archivos compartidos

- `docs/contrato-v1.md`: mantenido por Erwin durante S19; Adrian revisa.
- `pom.xml`: inicialmente controlado por Erwin durante E19. Si A19 necesita dependencias adicionales, se coordina antes de editarlo.
- Modelos compartidos de catálogo/manifiesto y cualquier cambio de esquema requieren revisión antes de modificarse.
- Los archivos generados dentro de `data/` por A19 no se editarán manualmente desde E19.

---

## 3. Contrato HTTP inicial

El servidor Java es el único punto público de acceso.

### 3.1 Rutas públicas

| Ruta | Métodos | Función |
|---|---|---|
| `/` | GET, HEAD | Página principal |
| `/assets/*` | GET, HEAD | Recursos web locales permitidos |
| `/api/catalog` | GET, HEAD | Catálogo de imágenes publicadas |
| `/lupa` | GET + Upgrade | Apertura futura del WebSocket LUPA |

No se expondrán rutas de `data/originals`, `data/staging`, manifiestos físicos, directorios internos ni una lista completa de teselas.

### 3.2 Tipos MIME mínimos

| Recurso | `Content-Type` |
|---|---|
| HTML | `text/html; charset=utf-8` |
| CSS | `text/css; charset=utf-8` |
| JavaScript | `text/javascript; charset=utf-8` |
| JSON | `application/json; charset=utf-8` |
| JPEG | `image/jpeg` |
| PNG | `image/png` |
| SVG | `image/svg+xml` |

Las respuestas de recursos conocidos incluyen `Content-Length`.

HEAD envía los mismos encabezados de GET, pero sin cuerpo.

### 3.3 Errores HTTP

**Concreción S19:**

- solicitud HTTP malformada → `400 Bad Request`;
- ruta inexistente → `404 Not Found`;
- intento de acceder a originales/rutas privadas → `404 Not Found`;
- método no admitido sobre una ruta conocida → `405 Method Not Allowed`;
- catálogo ausente, corrupto o inválido → `503 Service Unavailable`;
- intento inválido de negociación WebSocket en `/lupa` → `400 Bad Request` o `426 Upgrade Required`, según corresponda.

Un catálogo inválido nunca se transforma silenciosamente en un catálogo vacío.

### 3.4 Comportamiento de `/lupa`

`/lupa` queda reservado para una solicitud GET de actualización WebSocket.

El cliente deberá ofrecer:

```text
Sec-WebSocket-Protocol: lupa.v1
```

El comportamiento contractual final será responder `101 Switching Protocols` cuando la negociación sea válida y seleccionar `lupa.v1`.

La implementación completa del encuadre y ciclo WebSocket pertenece a una tarea posterior; S19 solamente congela la ruta y su contrato.

---

## 4. Catálogo HTTP

### 4.1 Estructura exacta

**Concreción S19:**

```json
{
  "schemaVersion": 1,
  "images": [
    {
      "imageId": "lupa-small",
      "imageVersion": "v1",
      "width": 4096,
      "height": 3072,
      "tileSize": 256,
      "maxLevel": 4
    }
  ]
}
```

Campos requeridos:

| Campo | Tipo | Regla |
|---|---|---|
| `schemaVersion` | entero | debe ser `1` |
| `images` | arreglo | cero o más imágenes publicadas |
| `imageId` | string | identificador público |
| `imageVersion` | string | versión publicada e inmutable |
| `width` | entero | mayor que 0 |
| `height` | entero | mayor que 0 |
| `tileSize` | entero | `256` para LUPA v1 |
| `maxLevel` | entero | mayor o igual que 0 |

El catálogo no contiene rutas privadas, nombres de originales, rutas de staging, rutas físicas de manifiestos, rutas de teselas ni una lista completa de teselas.

### 4.2 `imageId`

**Concreción S19:**

```text
^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$
```

Longitud: 1 a 64 caracteres.

Ejemplos válidos: `lupa-small`, `lupa-large`, `panorama-01`.

### 4.3 `imageVersion`

**Concreción S19:**

```text
^v[1-9][0-9]{0,9}$
```

Ejemplos: `v1`, `v2`, `v15`.

Cada par `imageId + imageVersion` identifica una versión publicada e inmutable.

Los identificadores recibidos desde el cliente se validan y se resuelven contra el catálogo. Nunca se utilizan directamente como rutas arbitrarias del sistema de archivos.

### 4.4 Lectura y actualización

Para E19, el proveedor de catálogo podrá leer y validar `data/catalog.json` al solicitar una instantánea.

Si el archivo:

- no existe;
- no puede leerse;
- contiene JSON inválido; o
- incumple este esquema,

el error se conserva como fallo de datos y `/api/catalog` responde `503 Service Unavailable`.

A19 publica una actualización solamente después de completar y validar la nueva versión.

---

## 5. Manifiesto persistido

Cada versión publicada contiene un manifiesto.

Ejemplo:

```json
{
  "schemaVersion": 1,
  "imageId": "lupa-small",
  "imageVersion": "v1",
  "width": 4096,
  "height": 3072,
  "tileSize": 256,
  "overlap": 0,
  "depth": "onetile",
  "levels": [
    {"z": 0, "width": 256, "height": 192},
    {"z": 1, "width": 512, "height": 384},
    {"z": 2, "width": 1024, "height": 768},
    {"z": 3, "width": 2048, "height": 1536},
    {"z": 4, "width": 4096, "height": 3072}
  ]
}
```

Validaciones:

- `schemaVersion = 1`;
- identificadores válidos;
- dimensiones positivas;
- `tileSize = 256`;
- `overlap = 0`;
- `depth = "onetile"`;
- niveles continuos desde `0` hasta `maxLevel`;
- nivel 0 cabe en una tesela;
- nivel máximo coincide exactamente con la resolución original;
- cada nivel guarda sus dimensiones exactas.

Para nivel máximo `Z`:

```text
width(z)  = ceil(widthOriginal  / 2^(Z-z))
height(z) = ceil(heightOriginal / 2^(Z-z))
```

---

## 6. Relación con el futuro mensaje `MANIFEST`

El manifiesto persistido será la fuente de los datos enviados posteriormente por el protocolo LUPA.

Ejemplo consistente:

```json
{
  "type": "MANIFEST",
  "epoch": 1,
  "imageId": "lupa-small",
  "imageVersion": "v1",
  "width": 4096,
  "height": 3072,
  "levels": [
    {"z": 0, "width": 256, "height": 192},
    {"z": 1, "width": 512, "height": 384},
    {"z": 2, "width": 1024, "height": 768},
    {"z": 3, "width": 2048, "height": 1536},
    {"z": 4, "width": 4096, "height": 3072}
  ],
  "tileSize": 256
}
```

`schemaVersion`, `overlap` y `depth` pertenecen al almacenamiento y no cambian el mensaje `MANIFEST` definido en LUPA v1.

`OPEN` contiene `imageId`. El servidor resuelve en el catálogo la versión publicada vigente y responde después con `MANIFEST`, incluyendo `imageVersion`.

---

## 7. Organización del almacenamiento

```text
data/
  catalog.json
  originals/
    <imageId>/
      <imageVersion>/
        source.<ext>
  staging/
    <importId>/
  pyramids/
    <imageId>/
      <imageVersion>/
        manifest.json
        tiles/
          <z>/
            <x>_<y>.jpg
```

Reglas:

- `originals/` es privado;
- `staging/` contiene importaciones incompletas y nunca aparece en el catálogo;
- `pyramids/` contiene versiones completas y publicadas;
- una versión publicada no se modifica;
- las teselas de borde conservan su tamaño real;
- `overlap=0` y `depth=onetile`.

La identidad lógica de una tesela es:

```text
imageId + imageVersion + z + x + y
```

Convención interna de ubicación:

```text
data/pyramids/<imageId>/<imageVersion>/tiles/<z>/<x>_<y>.jpg
```

Esta ruta nunca se expone al navegador.

---

## 8. Publicación completa e inmutable

A19 seguirá este orden contractual:

1. validar formato, dimensiones, permiso de uso y espacio disponible;
2. crear importación en `data/staging`;
3. generar la pirámide;
4. generar el manifiesto;
5. validar manifiesto y teselas;
6. mover/publicar la versión completa bajo `data/pyramids`;
7. actualizar `data/catalog.json` al final.

Una importación incompleta nunca se vuelve visible.

Si la publicación falla, el catálogo anterior permanece utilizable.

El catálogo debe reemplazarse mediante escritura temporal y sustitución final, evitando exponer JSON parcialmente escrito.

Las sesiones ya abiertas pueden terminar de usar su versión inmutable. Nuevas aperturas observan la versión publicada vigente.

---

## 9. Interfaz mínima entre HTTP y almacenamiento

La frontera mínima será equivalente a:

```java
public interface CatalogSource {
    CatalogSnapshot readCatalog() throws CatalogException;

    ImageManifest readManifest(
        String imageId,
        String imageVersion
    ) throws CatalogException;
}
```

`CatalogSnapshot` representa el esquema público de la sección 4.

`ImageManifest` representa el esquema persistido de la sección 5.

E19 puede utilizar una implementación con datos de prueba. A19 proporcionará después la implementación que lea datos publicados reales.

HTTP no debe conocer cómo libvips genera los archivos ni construir rutas con valores no validados.

La lectura asíncrona y transmisión de teselas pertenece a tareas posteriores.

---

## 10. Fixture temporal para E19

Mientras A19 no esté integrado, Erwin utilizará datos conceptualmente equivalentes a:

```text
src/test/resources/fixtures/
  catalog.json
  lupa-small-v1-manifest.json
```

`catalog.json`:

```json
{
  "schemaVersion": 1,
  "images": [
    {
      "imageId": "lupa-small",
      "imageVersion": "v1",
      "width": 4096,
      "height": 3072,
      "tileSize": 256,
      "maxLevel": 4
    }
  ]
}
```

El manifiesto temporal utiliza exactamente el ejemplo de la sección 5.

E19 no necesita una imagen real ni una pirámide completa para comprobar parser HTTP, rutas, MIME, HEAD, errores y `/api/catalog`.

I19 sustituirá el origen temporal por el catálogo producido en A19 sin cambiar el esquema consumido por HTTP.

---

## 11. Muestras de integración

Al iniciar S19 no existían muestras elegidas por el equipo.

Para no bloquear E19, se definen las siguientes **propuestas S19**, pendientes únicamente de revisión de Adrian:

### 11.1 Muestra pequeña

- `imageId`: `lupa-small`
- `imageVersion`: `v1`
- formato: JPEG RGB
- dimensiones objetivo: `4096 × 3072`
- fuente: imagen de prueba generada localmente por el equipo
- permiso: material propio del proyecto
- responsable de preparar/importar: Adrian

### 11.2 Muestra grande manejable

- `imageId`: `lupa-large`
- `imageVersion`: `v1`
- formato: JPEG RGB
- dimensiones objetivo: `8192 × 8192`
- fuente: imagen de prueba generada localmente por el equipo
- permiso: material propio del proyecto
- responsable de preparar/importar: Adrian

El contenido deberá permitir distinguir niveles de detalle, por ejemplo con regiones, texto o cuadrícula generada por el equipo.

Estas muestras sirven para integración y no convierten archivos de decenas o cientos de gigabytes en requisito de E19.

---

## 12. Compatibilidad futura con LUPA v1

Se conservan las decisiones de `Avances_LUPA.docx` sin implementar todavía su transporte completo.

### 12.1 Base tecnológica

- Java 21.
- Java NIO.2 con canales asíncronos.
- HTTP/1.1 inicial.
- WebSocket en `/lupa` con subprotocolo `lupa.v1`.
- controles JSON UTF-8.
- una tesela JPEG por mensaje binario independiente.

### 12.2 Estados

```text
HTTP
ESPERA_HELLO
LISTA
IMAGEN_ABIERTA
CERRADA
```

### 12.3 Mensajes

| Mensaje | Dirección | Campos principales |
|---|---|---|
| HELLO | cliente → servidor | `version`, `windowBytes`, `bitmapBudgetBytes` |
| WELCOME | servidor → cliente | `version`, `windowBytes`, `maxTileBytes`, `maxInFlight` |
| OPEN | cliente → servidor | `epoch`, `imageId` |
| MANIFEST | servidor → cliente | `epoch`, `imageId`, `imageVersion`, `width`, `height`, `levels`, `tileSize` |
| VIEW | cliente → servidor | `epoch`, `imageId`, `imageVersion`, `rect`, `viewportPx`, `detailOffset`, `mode`, `focus` |
| PLAN | servidor → cliente | `epoch`, `appliedLevel`, `contextLevel` |
| TILE | servidor → cliente | `deliveryId`, `epoch`, `imageId`, `imageVersion`, `z`, `x`, `y`, `w`, `h`, `codec`, `payloadBytes` |
| RELEASE | cliente → servidor | `deliveryId`, `status` |
| DONE | servidor → cliente | `epoch`, `sentTiles` |
| ERROR | servidor → cliente | `epoch` opcional, `code`, `message` |

OPEN y VIEW usan épocas crecientes por conexión. Una intención nueva reemplaza la anterior sin reiniciar créditos.

Identidad de caché:

```text
imageId + imageVersion + z + x + y
```

`deliveryId` identifica una entrega de esa conexión y no se reutiliza.

Estados de `RELEASE`:

```text
displayed
discarded
failed
```

Un RELEASE válido devuelve exactamente una vez la reserva registrada.

### 12.4 Límites iniciales conservados

```text
control JSON <= 16 KiB
cabecera JSON de TILE <= 4096 bytes
JPEG de TILE <= 262144 bytes
tile <= 256 x 256
overlap = 0
depth = onetile
detailOffset = -2, -1 o 0
mode = uniform o focus
```

Un TILE binario contiene:

```text
4 bytes de H en orden de red
H bytes de cabecera JSON UTF-8
payload JPEG
```

Longitud exacta:

```text
4 + H + payloadBytes
```

Nivel 0 es la imagen que cabe en una tesela y el nivel máximo es la resolución original.

`rect` y el centro del foco usan coordenadas de la imagen original. `viewportPx` y `radiusPx` usan píxeles físicos.

La miniatura viaja mediante TILE y consume los mismos créditos.

Los originales permanecen privados.

Los presupuestos, límites y objetivos del avance son parámetros iniciales, no resultados medidos.

---

## 13. Entrega esperada para I19

### Erwin aporta desde E19

- Maven configurado para Java 21;
- servidor HTTP inicial;
- GET y HEAD;
- recursos web locales;
- `/api/catalog`;
- manejo de errores HTTP;
- pruebas del contrato HTTP;
- consumo del fixture de S19.

### Adrian aporta desde A19

- importador local;
- invocación segura de libvips;
- pirámide de teselas de 256;
- manifiesto conforme a este contrato;
- publicación inmutable;
- `data/catalog.json`;
- muestras de integración;
- originales privados.

### Compatibilidad I19

I19 será compatible cuando el servidor de Erwin pueda utilizar el `data/catalog.json` y manifiestos de Adrian sin cambiar los esquemas ni añadir adaptadores para corregir diferencias de formato.

---

## 14. Requisitos académicos confirmados

Del enunciado oficial se conserva para la implementación:

- el servidor debe atender múltiples clientes;
- debe existir un protocolo propio para controlar la resolución por cliente;
- la comunicación inicial utiliza HTTP;
- todo objeto solicitado debe ser manejado por el servidor Java, sin solicitudes externas;
- deben poder agregarse nuevas imágenes;
- la imagen de ultra calidad no debe servirse completa al cliente;
- el cliente debe gestionar/liberar recursos para no sobrecargar el navegador;
- el servidor debe desarrollarse en Java 20 o 21; LUPA fija Java 21;
- el proyecto se evaluará desconectado de internet;
- ponderación: Backend 35 %, Frontend 30 %, Documento del protocolo 35 %.

### Entrega y evaluación

**Confirmado por Erwin:** fecha de entrega: **6 de octubre de 2026**.

**Confirmado por el enunciado:**

- la entrega por GES es obligatoria;
- entregar únicamente por GES no es suficiente;
- además habrá calificación virtual o presencial.

Todavía no están confirmados:

- hora exacta de entrega/evaluación;
- si la evaluación será virtual o presencial;
- archivos concretos que deben subirse al GES;
- formato exigido para esos archivos;
- límite de tamaño de subida del GES.

Estos puntos no se inventarán y permanecen abiertos hasta recibir información académica adicional.

---

## 15. Funciones que pertenecen a tareas posteriores

No forman parte de S19:

- implementación funcional completa de E19;
- visor Canvas completo;
- WebSocket completo;
- HELLO/WELCOME funcional;
- OPEN/MANIFEST funcional por WebSocket;
- VIEW/PLAN;
- épocas funcionales;
- créditos;
- `deliveryId` funcional;
- RELEASE funcional;
- planificador;
- lectura y transmisión de teselas;
- Worker de decodificación;
- caché de bitmaps;
- lente de interés;
- pruebas de carga;
- mediciones de rendimiento.

S19 únicamente fija las interfaces y acuerdos para que esas tareas no diverjan.

---

## 16. Matriz de cierre de S19

| Criterio | Evidencia | Estado |
|---|---|---|
| Estado real del repositorio revisado | comandos Git ejecutados por Erwin | **verificado en equipo** |
| Ramas y cambios posteriores al 13/09 comprobados | `git fetch`, `git branch`, `git log` y revisión remota | **verificado en equipo y remoto** |
| Rama común fijada | `develop` creada desde `main` | **confirmado** |
| Reparto de responsabilidades | secciones 2 y 13 | **confirmado por Erwin; revisión de Adrian pendiente** |
| Contrato de rutas HTTP | sección 3 | **documentado** |
| Contrato de catálogo | sección 4 | **documentado** |
| Contrato de manifiesto | secciones 5 y 6 | **documentado** |
| Contrato de almacenamiento/publicación | secciones 7 y 8 | **documentado** |
| Interfaz E19/A19 | sección 9 | **documentado** |
| Fixture temporal para Erwin | sección 10 | **documentado** |
| Muestras | sección 11 | **propuestas documentadas; revisión de Adrian pendiente** |
| Fecha de entrega | 6/10/2026 | **confirmado por Erwin** |
| Entrega por GES | sección 14 | **confirmado por enunciado** |
| Modalidad exacta, hora y detalles de archivos del GES | sección 14 | **pendiente** |

S19 queda técnicamente documentada. No debe marcarse como completamente cerrada hasta que Adrian revise los acuerdos que le afectan y se confirmen los datos académicos todavía pendientes.
