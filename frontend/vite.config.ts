import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: '../src/main/resources/web',
    emptyOutDir: true,
    target: 'es2022',
    cssCodeSplit: false,
    sourcemap: false,
    rolldownOptions: {
      output: {
        entryFileNames: 'assets/app-[hash].js',
        chunkFileNames: 'assets/chunks/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]'
      }
    }
  },
  worker: {
    format: 'es'
  },
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8081',
      '/lupa': {
        target: 'ws://127.0.0.1:8081',
        ws: true
      }
    }
  }
});
