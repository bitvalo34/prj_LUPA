# A20 - Artefactos de producción

Generados por el workflow **A20 Frontend CI** a partir del commit de cierre del frontend.

Assets servidos desde Java:

- `/assets/app-BbaHyGPw.js` - bundle principal React/LUPA.
- `/assets/decodeWorker-0Jrd7RtM.js` - Web Worker de decodificación.
- `/assets/style-0qxgnZBz.css` - sistema visual LUPA 64.
- `frontend/package-lock.json` - lockfile npm v3.

La ejecución que generó estos artefactos completó correctamente:

- `npm ci`;
- Vitest;
- `npm ci --offline` usando la caché local ya preparada;
- TypeScript + Vite build;
- validación de ausencia de recursos remotos de runtime;
- `mvn clean verify package`;
- smoke test del JAR Java.

Los nombres hashados pueden cambiar cuando cambie el código fuente. `HttpRouter` sirve únicamente recursos permitidos bajo `/assets/` desde classpath.
