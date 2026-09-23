# DRR — evidencia real con dos navegadores

Esta prueba busca demostrar que el arbitraje entre sesiones ya no es FIFO puro y
que el costo comprimido real de cada tesela afecta el siguiente turno elegible.

No es una prueba de throughput ni de carga. Es una evidencia funcional del
algoritmo de Deficit Round Robin (DRR).

## Preparación del servidor

Usar una sola lectura global para que el orden de concesión quede observable y un
quantum pequeño para que las diferencias entre JPEG sean visibles en pocas rondas.

```bash
cd /home/erwin/PRJIMA

java \
  -Dlupa.i21.diag=true \
  -Dlupa.drr.diag.readDelayMs=100 \
  -jar target/lupa.jar \
  --host=127.0.0.1 \
  --port=8081 \
  --data-root=/home/erwin/PRJIMA/data \
  --max-tile-reads=1 \
  --drr-quantum-bytes=8192
```

El servidor imprime eventos `TURN`, `DRR_READ_DELAY` y `DRR_CHARGE` por
sesión. El delay diagnóstico se ejecuta dentro del worker de disco, después de
adquirir el turno DRR; nunca bloquea callbacks de red. Está desactivado por defecto
y acepta 0..2000 ms.

## Frontend

En otra terminal:

```bash
cd /home/erwin/PRJIMA/frontend
npm run dev
```

Abrir dos ventanas o perfiles de navegador distintos:

```text
http://127.0.0.1:5173/
http://127.0.0.1:5173/
```

El solapamiento se fuerza en el servidor mediante
`-Dlupa.drr.diag.readDelayMs=100`, por lo que no es necesario retrasar el decode
del navegador.

## Procedimiento

1. En ambas ventanas seleccionar la misma imagen suficientemente grande.
2. Hacerlo con pocos segundos de diferencia, no esperar a que la primera termine.
3. Mantener ambas ventanas abiertas y sin cambiar de imagen.
4. Dejar que las dos completen el plan.
5. Guardar el stdout del servidor.

Una forma simple:

```bash
# en lugar del comando anterior, si se quiere conservar evidencia:
java \
  -Dlupa.i21.diag=true \
  -Dlupa.drr.diag.readDelayMs=100 \
  -jar target/lupa.jar \
  --host=127.0.0.1 \
  --port=8081 \
  --data-root=/home/erwin/PRJIMA/data \
  --max-tile-reads=1 \
  --drr-quantum-bytes=8192 \
  2>&1 | tee drr-real-browser.log
```

Después extraer solo los eventos relevantes:

```bash
grep -E 'event=(OPEN|TURN|DRR_READ_DELAY|DRR_CHARGE|CLOSE)' drr-real-browser.log \
  > drr-real-browser-evidence.txt
```

## Qué debe observarse

Los ids de sesión aparecen como `session=N`.

Ejemplo conceptual:

```text
session=1 event=TURN ...
session=1 event=DRR_CHARGE bytes=20165 quantum=8192 ...
session=2 event=TURN ...
session=2 event=DRR_CHARGE bytes=3013 quantum=8192 ...
session=2 event=TURN ...
session=1 event=TURN ...
```

No se exige una secuencia fija porque el tamaño JPEG real depende de la imagen. La
evidencia buscada es:

- ambas sesiones obtienen progreso;
- `max-tile-reads=1` mantiene una sola lectura global;
- los eventos `DRR_CHARGE` muestran tamaños JPEG distintos;
- `selectionVisits` puede ser mayor que 1 cuando un owner no reúne déficit
  suficiente y rota;
- una sesión con costo previo pequeño puede volver a ser elegible antes que otra con
  costo previo mayor;
- ninguna sesión monopoliza todas las lecturas mientras la otra está elegible.

## Interpretación de DRR_CHARGE

```text
DRR_CHARGE
  bytes=<costo JPEG real>
  quantum=<quantum configurado>
  selectionVisits=<owners visitados al transferir el permiso>
  chargedTotal=<bytes acumulados cobrados por DRR>
  waiting=<sesiones esperando turno>
  inFlight=<lecturas admitidas>
```

Con `--max-tile-reads=1`, `inFlight` debe permanecer como máximo en 1.

## Criterio de cierre

La prueba queda documentada como evidencia real cuando se conserva:

1. stdout/grep con al menos dos session ids;
2. varias líneas `TURN` y `DRR_CHARGE` de ambos;
3. costos `bytes` distintos;
4. al menos una rotación observable (`selectionVisits > 1`) si la imagen produce
   diferencias suficientes;
5. ambas sesiones completan sin error.

Si no aparece `selectionVisits > 1`, no significa que DRR haya fallado: puede ocurrir
que todos los costos observados quepan en el déficit disponible. En ese caso se
repite con `--drr-quantum-bytes=4096` para hacer la diferencia más visible, sin
cambiar el algoritmo.
