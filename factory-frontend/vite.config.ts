import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// The backend serves /api, the login and logout (session cookie): in development it sits behind the proxy.
const backend = process.env.FACTORY_BACKEND_URL ?? "http://localhost:8080";

export default defineConfig({
  plugins: [tanstackRouter({ target: "react", autoCodeSplitting: true }), react(), tailwindcss()],
  server: {
    proxy: Object.fromEntries(["/api", "/oauth2", "/login/oauth2", "/logout"].map((path) => [path, backend])),
  },
  test: { environment: "jsdom", setupFiles: ["./src/test-setup.ts"], include: ["src/**/*.test.{ts,tsx}"] },
});
