import { defineConfig } from "@playwright/test";

/** Against a running factory in mock login (dev/run-local.sh): pnpm e2e. */
export default defineConfig({
  testDir: "e2e",
  use: { baseURL: process.env.FACTORY_URL ?? "http://localhost:18080", locale: "fr-FR" },
});
