import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Proxies API calls to the backend during `npm run dev` so the browser only ever talks to one
// origin (localhost:5173) — that's what makes SameSite=Strict cookies from AuthController work
// without CORS gymnastics. In the Docker Compose deployment, nginx does the same job (see
// frontend/nginx.conf) so the built app behaves identically in both places.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/v1': 'http://localhost:8080',
      '/auth': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
})
