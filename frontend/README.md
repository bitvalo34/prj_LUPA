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

## Navegación A21

- Arrastrar o usar las flechas desplaza la región solicitada.
- La rueda, los botones `+`/`-` y las teclas `+`/`-` cambian la escala.
- `0` y Restablecer vuelven a la imagen completa.
- Detalle selecciona `detailOffset=-2`, `-1` o `0`.
- Lente habilita `mode=focus`; un clic fija el foco y el control deslizante cambia su radio físico.

Cada interacción envía VIEW con una época nueva. El Canvas conserva solo la miniatura entre épocas y cierra los demás bitmaps antes de incorporar el plan nuevo.
