const REQUIRED_ENVIRONMENT = [
  "UNIBUS_TARGET_ENV",
  "STAGING_API_URL",
  "STAGING_FRONTEND_ORIGIN",
];

export async function runStagingSmoke(environment, fetchImpl = fetch) {
  const missing = REQUIRED_ENVIRONMENT.filter((name) => !environment[name]?.trim());
  if (missing.length > 0) {
    throw new Error(`Missing staging smoke variables: ${missing.join(", ")}`);
  }
  if (environment.UNIBUS_TARGET_ENV !== "staging") {
    throw new Error("UNIBUS_TARGET_ENV must be exactly staging");
  }

  const apiUrl = validateBaseUrl(environment.STAGING_API_URL);
  const allowedOrigin = validateOrigin(environment.STAGING_FRONTEND_ORIGIN, "STAGING_FRONTEND_ORIGIN");
  const rejectedOrigin = validateOrigin(
    environment.STAGING_REJECTED_ORIGIN || "https://rejected-origin.invalid",
    "STAGING_REJECTED_ORIGIN",
  );
  if (allowedOrigin === rejectedOrigin) {
    throw new Error("Allowed and rejected CORS origins must differ");
  }

  const timeout = Number.parseInt(environment.UNIBUS_SMOKE_TIMEOUT_MS || "10000", 10);
  if (!Number.isInteger(timeout) || timeout < 1000 || timeout > 60000) {
    throw new Error("UNIBUS_SMOKE_TIMEOUT_MS must be between 1000 and 60000");
  }

  const verified = [];
  const request = async (name, path, options = {}) => {
    const response = await fetchImpl(`${apiUrl}${path}`, {
      ...options,
      signal: AbortSignal.timeout(timeout),
    });
    verified.push(name);
    return response;
  };

  await expectSuccessEnvelope(await request("compatibility health", "/health"));
  await expectUp(await request("liveness", "/actuator/health/liveness"));
  await expectUp(await request("readiness", "/actuator/health/readiness"));

  for (const [name, path] of [
    ["notice list", "/notices"],
    ["route list", "/routes"],
    ["bus list", "/buses"],
    ["latest bus locations", "/buses/locations/latest"],
  ]) {
    await expectCollection(await request(name, path));
  }

  const missingRoute = await request("unknown route compatibility", "/does-not-exist");
  assertStatus(missingRoute, 404, "unknown route compatibility");
  const missingBody = await parseJson(missingRoute, "unknown route compatibility");
  assert(missingBody.error === "Not Found", "Unknown route body differs from Edge");

  const allowedCors = await request("allowed CORS preflight", "/notices", {
    method: "OPTIONS",
    headers: {
      Origin: allowedOrigin,
      "Access-Control-Request-Method": "GET",
    },
  });
  assertStatus(allowedCors, 200, "allowed CORS preflight");
  assert(
    allowedCors.headers.get("access-control-allow-origin") === allowedOrigin,
    "Allowed CORS origin was not echoed exactly",
  );

  const rejectedCors = await request("rejected CORS preflight", "/notices", {
    method: "OPTIONS",
    headers: {
      Origin: rejectedOrigin,
      "Access-Control-Request-Method": "GET",
    },
  });
  assertStatus(rejectedCors, 403, "rejected CORS preflight");
  assert(
    !rejectedCors.headers.has("access-control-allow-origin"),
    "Rejected CORS origin unexpectedly received allow-origin",
  );

  const invalidAuth = await request("authentication rejection headers", "/users", {
    headers: { "X-Auth-Token": "invalid-staging-smoke-token" },
  });
  assertStatus(invalidAuth, 401, "authentication rejection headers");
  assert(invalidAuth.headers.get("cache-control") === "private, no-store",
    "Authenticated response is missing private, no-store");
  assert(invalidAuth.headers.get("vary")?.toLowerCase().includes("x-auth-token"),
    "Authenticated response Vary header is missing X-Auth-Token");
  assert(invalidAuth.headers.get("x-content-type-options") === "nosniff",
    "Security header X-Content-Type-Options differs");

  if (environment.STAGING_ADMIN_TOKEN) {
    const users = await request("administrator read", "/users", {
      headers: { "X-Auth-Token": environment.STAGING_ADMIN_TOKEN },
    });
    await expectSuccessEnvelope(users);
  }
  if (environment.STAGING_DRIVER_TOKEN) {
    const status = await request("driver state read", "/driver/status", {
      headers: { "X-Auth-Token": environment.STAGING_DRIVER_TOKEN },
    });
    await expectSuccessEnvelope(status);
  }

  return verified;
}

function validateBaseUrl(rawValue) {
  const url = new URL(rawValue);
  assert(["http:", "https:"].includes(url.protocol), "STAGING_API_URL must use HTTP(S)");
  assert(!url.username && !url.password, "STAGING_API_URL must not contain credentials");
  assert(!url.search && !url.hash, "STAGING_API_URL must not contain query or fragment values");
  const loopback = ["localhost", "127.0.0.1", "::1"].includes(url.hostname);
  assert(url.protocol === "https:" || loopback, "Remote staging API must use HTTPS");
  return url.toString().replace(/\/$/, "");
}

function validateOrigin(rawValue, name) {
  const url = new URL(rawValue);
  assert(["http:", "https:"].includes(url.protocol), `${name} must use HTTP(S)`);
  assert(url.origin === rawValue.replace(/\/$/, ""), `${name} must be an origin without a path`);
  return url.origin;
}

async function expectUp(response) {
  assertStatus(response, 200, "health probe");
  const body = await parseJson(response, "health probe");
  assert(body.status === "UP", "Health probe did not report UP");
}

async function expectSuccessEnvelope(response) {
  assertStatus(response, 200, "success envelope");
  const body = await parseJson(response, "success envelope");
  assert(body.success === true, "API response success flag was not true");
}

async function expectCollection(response) {
  assertStatus(response, 200, "collection response");
  const body = await parseJson(response, "collection response");
  assert(body.success === true && Array.isArray(body.data), "Collection response shape differs");
}

async function parseJson(response, label) {
  try {
    return await response.json();
  } catch {
    throw new Error(`${label} did not return JSON`);
  }
}

function assertStatus(response, expected, label) {
  assert(response.status === expected, `${label} returned ${response.status}, expected ${expected}`);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}
