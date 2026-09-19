# LUPA 64 frontend

Frontend A20 construido con React + TypeScript + Vite. La aplicación de producción se sirve exclusivamente desde Java.

## Comandos

```bash
npm ci
npm test
npm run build
```

El build escribe en `../src/main/resources/web`.

Desarrollo opcional:

```bash
# terminal 1, backend Java en :8081
npm run dev
# abrir http://127.0.0.1:5173
```

Vite proxifica `/api` y `/lupa` solamente durante desarrollo. La validación final debe hacerse sobre el JAR de Java, no sobre el dev server.

Modo de muestra explícito:

```text
http://127.0.0.1:8081/?sample=1
```

No existe fallback automático al modo muestra.
