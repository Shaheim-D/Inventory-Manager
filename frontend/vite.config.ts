import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    // Not the default 'assets': /assets is a real application route, and a
    // static directory of the same name would shadow it once both are served
    // from the same origin.
    assetsDir: 'app-assets',
    // Bundled into the Spring Boot jar's static resources at image-build time,
    // so the platform ships as one deployable artifact (Deployment Design §2).
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    // In development the API lives on the backend's port; in production both are
    // served from the same origin, so every request path stays relative.
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
});
