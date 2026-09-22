import { isDeepStrictEqual } from "node:util";

const requiredEnvironment = ["EDGE_API_URL", "SPRING_API_URL"];
for (const name of requiredEnvironment) {
  if (!process.env[name]) throw new Error(`${name} is required`);
}

const edgeUrl = process.env.EDGE_API_URL.replace(/\/$/, "");
const springUrl = process.env.SPRING_API_URL.replace(/\/$/, "");
for (const [name, rawUrl] of [["EDGE_API_URL", edgeUrl], ["SPRING_API_URL", springUrl]]) {
  const hostname = new URL(rawUrl).hostname;
  if (!["127.0.0.1", "localhost", "::1"].includes(hostname)) {
    throw new Error(`${name} must use a loopback host; production comparison is intentionally blocked`);
  }
}

const anonKey = process.env.SUPABASE_ANON_KEY;
let matched = 0;
const differences = [];

function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]));
  }
  return value;
}

async function request(baseUrl, path, options = {}, isEdge = false) {
  const headers = { ...options.headers };
  if (isEdge && anonKey) {
    headers.apikey = anonKey;
    headers.Authorization = `Bearer ${anonKey}`;
  }
  if (options.token) headers["X-Auth-Token"] = options.token;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(`${baseUrl}${path}`, {
    method: options.method || "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  return { status: response.status, body, headers: response.headers };
}

async function compare(name, path, options = {}) {
  const [edge, spring] = await Promise.all([
    request(edgeUrl, path, options, true),
    request(springUrl, path, options, false),
  ]);
  const sameStatus = edge.status === spring.status;
  const sameBody = isDeepStrictEqual(stable(edge.body), stable(spring.body));
  if (sameStatus && sameBody) {
    matched += 1;
    return { edge, spring };
  }
  differences.push({
    name,
    edge: { status: edge.status, body: edge.body },
    spring: { status: spring.status, body: spring.body },
  });
  return { edge, spring };
}

const routes = await compare("public route list", "/routes");
const routeId = routes.edge.body?.data?.[0]?.id;
if (!routeId) throw new Error("The isolated Supabase seed must contain at least one route");

const buses = await compare("public bus list", "/buses");
const busId = buses.edge.body?.data?.[0]?.id;
if (!busId) throw new Error("The isolated Supabase seed must contain at least one bus");

await compare("public notice list", "/notices");
await compare("route detail", `/routes/${routeId}`);

// Route path generation writes only to the isolated route cache. Warm both implementations
// before comparing so the shared database does not make the first caller report cached=false.
await request(edgeUrl, `/routes/${routeId}/path`, {}, true);
await request(springUrl, `/routes/${routeId}/path`);
await compare("route path", `/routes/${routeId}/path`);

await compare("bus detail", `/buses/${encodeURIComponent(busId)}`);
await compare("latest bus locations", "/buses/locations/latest");
await compare("missing notice", "/notices/not-a-uuid");
await compare("missing route", "/routes/not-a-uuid");
await compare("missing bus", "/buses/UNKNOWN-PARITY-BUS");
await compare("signup validation", "/auth/signup", { method: "POST", body: {} });
await compare("login validation", "/auth/login", { method: "POST", body: {} });
await compare("kakao validation", "/auth/kakao", { method: "POST", body: {} });
await compare("admin token missing", "/users");
await compare("admin token invalid", "/users", { token: "invalid-parity-token" });
await compare("driver token missing", "/driver/status");
await compare("push token missing", "/notifications/subscribe", { method: "POST", body: {} });
const authenticatedError = await compare(
  "push token invalid",
  "/notifications/subscribe",
  { method: "POST", body: {}, token: "invalid-parity-token" },
);
await compare("unknown route", "/does-not-exist");

const springHeaders = authenticatedError.spring.headers;
if (springHeaders.get("cache-control") !== "private, no-store") {
  differences.push({ name: "authenticated Cache-Control", spring: springHeaders.get("cache-control") });
}
if (!springHeaders.get("vary")?.toLowerCase().includes("x-auth-token")) {
  differences.push({ name: "authenticated Vary", spring: springHeaders.get("vary") });
}
if (springHeaders.get("x-content-type-options") !== "nosniff") {
  differences.push({
    name: "X-Content-Type-Options",
    spring: springHeaders.get("x-content-type-options"),
  });
}

async function verifySpringCors(origin, expectedStatus) {
  const result = await request(springUrl, "/notices", {
    method: "OPTIONS",
    headers: {
      Origin: origin,
      "Access-Control-Request-Method": "GET",
    },
  });
  if (result.status !== expectedStatus) {
    differences.push({ name: `CORS ${origin}`, spring: { status: result.status } });
    return;
  }
  if (expectedStatus === 200 && result.headers.get("access-control-allow-origin") !== origin) {
    differences.push({
      name: `CORS allow-origin ${origin}`,
      spring: result.headers.get("access-control-allow-origin"),
    });
  }
}

await verifySpringCors("https://unibus-sch.vercel.app", 200);
await verifySpringCors(
  "https://unibus-sch-git-parity-ddingddong9s-projects.vercel.app",
  200,
);
await verifySpringCors("https://attacker.example", 403);

console.log(`Edge/Spring parity: ${matched} response contracts matched`);
if (differences.length > 0) {
  for (const difference of differences) {
    console.error(`DIFFERENCE ${difference.name}: ${JSON.stringify(difference)}`);
  }
  process.exitCode = 1;
} else {
  console.log("Edge/Spring parity, compatibility headers, and CORS: verified");
}
