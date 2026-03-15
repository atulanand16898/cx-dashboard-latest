import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Vite configuration.
 *
 * Dev proxy: During local development, Vite proxies all /api requests to the Spring Boot
 * backend at localhost:8080. This sidesteps CORS in dev. The backend now also has a proper
 * CORS configuration (SecurityConfig.java) for production deployments where the proxy is
 * not present.
 *
 * Production: Set VITE_API_BASE_URL=https://your-api.com in your .env.production if the
 * frontend is hosted separately from the backend. The backend must also have
 * cors.allowed-origins set to the frontend URL via the CORS_ALLOWED_ORIGINS env var.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // Rewrite is not needed since backend also uses /api prefix
        // secure: false,  // uncomment if backend uses self-signed TLS cert
      }
    }
  }
})

