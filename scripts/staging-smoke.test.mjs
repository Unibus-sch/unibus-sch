import assert from "node:assert/strict";
import test from "node:test";

import { runStagingSmoke } from "./staging-smoke.mjs";

const validEnvironment = {
  UNIBUS_TARGET_ENV: "staging",
  STAGING_API_URL: "https://api.staging.example.test",
  STAGING_FRONTEND_ORIGIN: "https://app.staging.example.test",
};

test("verifies read-only staging health, API, CORS, and authentication contracts", async () => {
  const verified = await runStagingSmoke(validEnvironment, successfulFetch);

  assert.equal(verified.length, 11);
  assert(verified.includes("readiness"));
  assert(verified.includes("rejected CORS preflight"));
  assert(verified.includes("authentication rejection headers"));
});

test("requires an explicit staging target marker", async () => {
  await assert.rejects(
    () => runStagingSmoke({ ...validEnvironment, UNIBUS_TARGET_ENV: "production" }, successfulFetch),
    /must be exactly staging/,
  );
});

test("rejects insecure remote API URLs before sending requests", async () => {
  await assert.rejects(
    () => runStagingSmoke({ ...validEnvironment, STAGING_API_URL: "http://api.example.test" }, successfulFetch),
    /must use HTTPS/,
  );
});

test("fails when the deployed compatibility contract differs", async () => {
  await assert.rejects(
    () => runStagingSmoke(validEnvironment, async (url, options) => {
      const response = await successfulFetch(url, options);
      if (new URL(url).pathname === "/does-not-exist") {
        return json({ error: "Different" }, 404);
      }
      return response;
    }),
    /differs from Edge/,
  );
});

async function successfulFetch(rawUrl, options = {}) {
  const url = new URL(rawUrl);
  const path = url.pathname;
  if (options.method === "OPTIONS") {
    const origin = options.headers.Origin;
    if (origin === validEnvironment.STAGING_FRONTEND_ORIGIN) {
      return new Response(null, {
        status: 200,
        headers: { "Access-Control-Allow-Origin": origin },
      });
    }
    return new Response(null, { status: 403 });
  }
  if (path === "/actuator/health/liveness" || path === "/actuator/health/readiness") {
    return json({ status: "UP" });
  }
  if (path === "/health") {
    return json({ success: true, data: { status: "UP" } });
  }
  if (["/notices", "/routes", "/buses", "/buses/locations/latest"].includes(path)) {
    return json({ success: true, data: [] });
  }
  if (path === "/does-not-exist") return json({ error: "Not Found" }, 404);
  if (path === "/users") {
    return json({ error: "Unauthorized: Invalid token" }, 401, {
      "Cache-Control": "private, no-store",
      Vary: "Origin, X-Auth-Token",
      "X-Content-Type-Options": "nosniff",
    });
  }
  return json({ error: "unexpected test request" }, 500);
}

function json(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}
