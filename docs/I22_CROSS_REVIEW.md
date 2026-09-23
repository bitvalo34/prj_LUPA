# I22 — Revisión cruzada preparada

Este archivo contiene material para que Erwin y Adrian realicen la revisión humana
sin confundirla con la revisión técnica asistida.

## Revisión de backend / Erwin

**Cambios a revisar**

- E22 de concurrencia y recursos;
- Selective Tile Acknowledgment;
- DRR;
- corrección de VIEW rápida frente a turno DRR anterior.

**Archivos representativos**

- `src/main/java/gt/lupa/session/LupaSession.java`
- `src/main/java/gt/lupa/concurrent/TileReadAdmission.java`
- `src/main/java/gt/lupa/config/ServerConfig.java`
- `docs/LUPA_CONTROL_RECOVERY.md`
- `docs/LUPA_DRR.md`

**Preguntas para Adrian**

1. ¿La semántica de ACK_STATE y recuperación conserva correctamente el contrato de
   créditos?
2. ¿La publicación/versionado que consume LupaSession permanece compatible cuando
   una imagen nueva aparece durante una sesión activa?
3. ¿Ves alguna ruta donde una VIEW nueva pueda mezclar una versión o época antigua?

## Revisión de frontend/importación / Adrian

**Cambios a revisar**

- recarga manual de catálogo;
- manejo de nueva versión disponible;
- reconexión;
- importación/versionado/publicación local.

**Archivos representativos**

- `frontend/src/ui/App.tsx`
- `frontend/src/runtime/LupaClient.ts`
- `src/main/java/gt/lupa/ingest/IngestApplication.java`
- `docs/A22.md`

**Preguntas para Erwin**

1. ¿Recargar catálogo puede afectar la sesión WebSocket actualmente abierta?
2. ¿Una versión nueva se abre solo mediante OPEN explícito y no reemplaza
   silenciosamente la versión activa?
3. ¿Una importación fallida puede modificar catálogo/pirámide vigente?
4. ¿Los originales y staging siguen fuera de la superficie HTTP?

## Resultado técnico ya disponible

I22 verificó:

- build offline;
- ejecución Java sin Vite;
- navegación por WebSocket;
- publicación `i22-offline-real/v1` con servidor vivo;
- 404 de originals/staging;
- fallo inválido con catálogo byte-idéntico.

## Registro humano

Completar por los integrantes:

```text
Revisor:
Fecha:
Commit/rango revisado:
Hallazgos:
Correcciones solicitadas:
Resultado: aprobado / requiere cambios
```

Hasta completar ese bloque, el estado formal es:

**Validación técnica completada; revisión humana pendiente.**
