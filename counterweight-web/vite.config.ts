import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/**
 * The built output is served by counterweight-server as static content — there
 * is no separate web host. In development the API is proxied so the browser
 * sees one origin and no CORS preflight sits in front of every till request.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: false },
    },
  },
  build: {
    outDir: "dist",
    // The till reloads over the shop LAN, not the internet. Chunking buys
    // nothing here and makes the cache harder to reason about.
    chunkSizeWarningLimit: 1200,
  },
});
