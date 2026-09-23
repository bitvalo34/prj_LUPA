# LUPA — Deficit Round Robin para turnos entre sesiones

## Objetivo

E22 originalmente utilizaba una cola FIFO de sesiones elegibles. Esa política evita
que una sesión ocupe todos los permisos, pero trata una tesela de 30 KiB igual que
una de 230 KiB.

LUPA evoluciona esa política a **Deficit Round Robin (DRR)** con costo en bytes
comprimidos.

## Unidad de servicio

- Un owner es una sesión LUPA.
- Cada owner mantiene como máximo un turno activo o en espera.
- El quantum por defecto es **128 KiB**.
- El quantum es configurable con `--drr-quantum-bytes`.
- El valor permitido es 1..262144 bytes.

El scheduler conserva por owner:

- déficit acumulado;
- costo comprimido observado de su última tesela;
- si ya posee un turno activo o en espera.

## Cobro exacto

La lectura se admite antes de conocer el tamaño JPEG real. Al finalizar la lectura,
la sesión llama:

```java
readLease.complete(tile.jpegLength());
```

Así, el turno se cobra con los **bytes comprimidos realmente leídos**. Ese costo se
usa como estimación del siguiente turno del mismo owner.

Si una lectura falla o se cancela, el lease se cierra sin cargo de bytes.

## Selección DRR

Cuando se libera un permiso:

1. se visita el siguiente owner en la cola;
2. se suma un quantum a su déficit;
3. si su déficit cubre el costo estimado de su siguiente turno, recibe el permiso;
4. si no, rota al final de la cola;
5. se continúa hasta encontrar un owner elegible.

El costo estimado queda acotado a cuatro quantums para mantener una convergencia
finita y memoria/contabilidad simples. El costo real completo sigue acumulándose en
la métrica `chargedBytes`.

## Efecto esperado

Con teselas de tamaños distintos, una sesión que acaba de consumir una tesela grande
puede necesitar acumular déficit durante varias rondas, mientras una sesión con
teselas pequeñas puede avanzar antes.

Esto no promete latencias idénticas. La propiedad buscada es **equidad aproximada por
bytes servidos**, no simplemente por cantidad de teselas.

## Interacción con otras garantías

DRR no cambia:

- el máximo global de lecturas simultáneas;
- la cola acotada de espera;
- los créditos por conexión;
- `maxInFlight`;
- las prioridades internas del plan `contexto -> foco -> resto visible`;
- la recuperación selectiva STA;
- los límites de caché y buffers.

Una sesión sin crédito o sin trabajo no entra a competir por un turno útil.

## Evidencia

`TileReadAdmissionTest.drrChargesActualCompressedBytesAndLetsSmallerFlowAdvanceEarlier`
fuerza tres owners con costos distintos y comprueba que el flujo pequeño obtiene un
turno adicional antes de que el flujo pesado vuelva a ser elegible.

La secuencia observada es:

```text
A(220 KiB) -> B(32 KiB) -> C(128 KiB) -> B(32 KiB) -> A(220 KiB)
```

Esto demuestra que la política ya no es FIFO puro.
