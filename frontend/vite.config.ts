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
    // No manualChunks here, and that is a measured decision rather than an
    // oversight. Splitting the libraries into their own vendor chunks -- the
    // usual advice -- was tried and made the first load *worse*: 199 kB
    // gzipped against 172 kB. Forcing all of @mui into one eager chunk drags
    // in the components only the lazily-loaded screens use, which is precisely
    // what the route splitting in App.tsx exists to avoid. Letting Rollup
    // decide keeps MUI split across the chunks that actually need it.
    //
    // The one thing a vendor split would buy is cache reuse across an app
    // update. That happens a few times a year; a cold first load happens to
    // somebody every day.
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
