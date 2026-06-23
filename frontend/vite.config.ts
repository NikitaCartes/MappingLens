import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The frontend is a static SPA that talks to the MappingLens REST API.
// In dev, proxy /api to a locally running `mappinglens serve` (default :8080).
export default defineConfig({
  plugins: [react()],
  worker: {
    format: "es",
  },
  server: {
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
