import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      // Tests exercise the staging-only purchase path; production deploys never set
      // TEST_MODE. The tiny title cap keeps the rate-limit test to three calls.
      miniflare: { bindings: { TEST_MODE: "1", TITLE_DAILY_LIMIT: "2" } },
    }),
  ],
});
