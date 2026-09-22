import assert from "node:assert/strict";
import test from "node:test";

import { validateFrontendEnvironment } from "./frontend-env.mjs";

const validLocalEnvironment = {
  VITE_SUPABASE_URL: "http://127.0.0.1:54321",
  VITE_SUPABASE_ANON_KEY: "local-public-anon-key",
  VITE_PUBLIC_API_BASE_URL: "http://127.0.0.1:8080",
  VITE_AUTH_API_BASE_URL: "http://127.0.0.1:8080",
  VITE_ADMIN_API_BASE_URL: "http://127.0.0.1:8080",
  VITE_DRIVER_API_BASE_URL: "http://127.0.0.1:8080",
};

test("accepts a complete local development environment", () => {
  assert.doesNotThrow(() => validateFrontendEnvironment(validLocalEnvironment));
});

test("reports every missing required frontend value without exposing secrets", () => {
  assert.throws(
    () => validateFrontendEnvironment({}),
    (error) => {
      assert.match(error.message, /VITE_SUPABASE_URL is required/);
      assert.match(error.message, /VITE_SUPABASE_ANON_KEY is required/);
      assert.match(error.message, /VITE_DRIVER_API_BASE_URL is required/);
      return true;
    },
  );
});

test("rejects malformed URLs and credentials embedded in URLs", () => {
  assert.throws(
    () => validateFrontendEnvironment({
      ...validLocalEnvironment,
      VITE_PUBLIC_API_BASE_URL: "not-a-url",
      VITE_AUTH_API_BASE_URL: "https://user:password@example.com",
    }),
    /must be a valid absolute URL[\s\S]*must not contain credentials/,
  );
});

test("rejects loopback URLs only for an actual production deployment", () => {
  assert.throws(
    () => validateFrontendEnvironment({
      ...validLocalEnvironment,
      UNIBUS_DEPLOYMENT_ENV: "production",
    }),
    /must not use a loopback host in a production deployment/,
  );
});
