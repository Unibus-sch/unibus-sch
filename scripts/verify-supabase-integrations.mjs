import { createHash, randomBytes, randomUUID } from "node:crypto";

import { createClient } from "@supabase/supabase-js";

const requiredEnvironment = [
  "SUPABASE_API_URL",
  "SUPABASE_ANON_KEY",
  "SUPABASE_SERVICE_ROLE_KEY",
  "SPRING_API_URL",
];

for (const name of requiredEnvironment) {
  if (!process.env[name]) {
    throw new Error(`${name} is required`);
  }
}

const supabaseUrl = process.env.SUPABASE_API_URL.replace(/\/$/, "");
const springUrl = process.env.SPRING_API_URL.replace(/\/$/, "");
const supabaseHost = new URL(supabaseUrl).hostname;
if (!["127.0.0.1", "localhost", "::1"].includes(supabaseHost)) {
  throw new Error("Integration verification only accepts a loopback Supabase URL");
}
const admin = createClient(supabaseUrl, process.env.SUPABASE_SERVICE_ROLE_KEY, {
  auth: { autoRefreshToken: false, persistSession: false },
});
const realtime = createClient(supabaseUrl, process.env.SUPABASE_ANON_KEY, {
  auth: { autoRefreshToken: false, persistSession: false },
});

const runId = randomUUID();
const adminId = randomUUID();
const driverId = randomUUID();
const adminToken = `integration-admin-${runId}`;
const driverToken = `integration-driver-${runId}`;
const pushEndpoint = `https://updates.push.services.mozilla.com/wpush/v2/${runId}`;
const busId = "campus-001";
let noticeId;
let uploadedObject;
let originalBus;

function hashSessionToken(token) {
  return `sha256:${createHash("sha256").update(token).digest("base64url")}`;
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function withTimeout(promise, label, milliseconds = 15_000) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(
      () => reject(new Error(`${label} timed out after ${milliseconds} ms`)),
      milliseconds,
    )),
  ]);
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function expectSupabase(result, operation) {
  const resolved = await result;
  if (resolved.error) throw new Error(`${operation}: ${resolved.error.message}`);
  return resolved.data;
}

async function spring(path, { method = "GET", token, body } = {}) {
  const headers = {};
  if (token) headers["X-Auth-Token"] = token;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(`${springUrl}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(`${method} ${path} failed (${response.status}): ${JSON.stringify(payload)}`);
  }
  return payload;
}

const noticeEvent = deferred();
const locationEvent = deferred();
const subscribed = deferred();
const channel = realtime
  .channel(`spring-integration-${runId}`)
  .on("postgres_changes", { event: "INSERT", schema: "public", table: "notices" }, (event) => {
    if (event.new?.title === `Realtime ${runId}`) noticeEvent.resolve(event.new);
  })
  .on("postgres_changes", { event: "*", schema: "public", table: "bus_latest_state" }, (event) => {
    if (event.new?.bus_id === busId) locationEvent.resolve(event.new);
  })
  .subscribe((status, error) => {
    if (error) subscribed.reject(error);
    else if (status === "SUBSCRIBED") subscribed.resolve();
    else if (["CHANNEL_ERROR", "TIMED_OUT", "CLOSED"].includes(status)) {
      subscribed.reject(new Error(`Realtime subscription failed: ${status}`));
    }
  });

async function seed() {
  originalBus = await expectSupabase(admin.from("buses")
    .select("assigned_driver_id,current_driver_id,is_running")
    .eq("id", busId).single(), "read original bus state");
  await expectSupabase(admin.from("users").insert([
    {
      id: adminId,
      email: `integration-admin-${runId}@example.invalid`,
      name: "Integration Admin",
      role: "admin",
      provider: "local",
    },
    {
      id: driverId,
      email: `integration-driver-${runId}@example.invalid`,
      name: "Integration Driver",
      role: "driver",
      provider: "local",
    },
  ]), "seed users");
  await expectSupabase(admin.from("auth_tokens").insert([
    { user_id: adminId, token: hashSessionToken(adminToken) },
    { user_id: driverId, token: hashSessionToken(driverToken) },
  ]), "seed auth tokens");
  await expectSupabase(admin.from("buses").update({
    assigned_driver_id: driverId,
    current_driver_id: null,
    is_running: false,
  }).eq("id", busId), "prepare bus");
}

async function verifyWebPushSubscription() {
  const p256dh = Buffer.concat([Buffer.from([4]), randomBytes(64)]).toString("base64url");
  const auth = randomBytes(16).toString("base64url");
  const subscribedPush = await spring("/notifications/subscribe", {
    method: "POST",
    token: driverToken,
    body: { subscription: { endpoint: pushEndpoint, keys: { p256dh, auth } } },
  });
  assert(subscribedPush.success && subscribedPush.data?.id, "push subscription was not stored");

  const rows = await expectSupabase(admin.from("push_subscriptions")
    .select("user_id,enabled").eq("endpoint", pushEndpoint), "read push subscription");
  assert(rows.length === 1 && rows[0].user_id === driverId && rows[0].enabled,
    "stored push subscription does not match the authenticated user");
}

async function verifyRealtime() {
  const notification = await spring("/notifications/send", {
    method: "POST",
    token: adminToken,
    body: { title: `Realtime ${runId}`, message: "Spring to Supabase Realtime", target: "all" },
  });
  noticeId = notification.data?.notice?.id;
  assert(notification.success && noticeId, "notification response did not contain a notice id");
  const notice = await withTimeout(noticeEvent.promise, "notice Realtime event");
  assert(notice.id === noticeId, "Realtime notice id differs from the Spring response");

  const started = await spring("/driver/start", {
    method: "POST",
    token: driverToken,
    body: { busId },
  });
  assert(started.success && started.data?.busId === busId, "driver run did not start");
  await spring("/driver/location", {
    method: "POST",
    token: driverToken,
    body: { lat: 36.7691, lng: 126.9512, speed: 12.5, heading: 20 },
  });
  const location = await withTimeout(locationEvent.promise, "bus location Realtime event");
  assert(Number(location.latitude) === 36.7691 && Number(location.longitude) === 126.9512,
    "Realtime location differs from the Spring GPS update");
}

async function verifyStorage() {
  const image = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const form = new FormData();
  form.append("file", new Blob([image], { type: "image/png" }), "integration.png");
  const response = await fetch(`${springUrl}/notices/images`, {
    method: "POST",
    headers: { "X-Auth-Token": adminToken },
    body: form,
  });
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(`POST /notices/images failed (${response.status}): ${JSON.stringify(payload)}`);
  }
  const url = payload.data?.url;
  assert(payload.success && url?.includes("/storage/v1/object/public/notice-images/"),
    "Spring did not return a public Storage URL");
  uploadedObject = decodeURIComponent(url.split("/notice-images/")[1]);

  const downloaded = await fetch(url);
  assert(downloaded.ok, `public Storage URL returned ${downloaded.status}`);
  const bytes = new Uint8Array(await downloaded.arrayBuffer());
  assert(bytes.length === image.length && bytes.every((value, index) => value === image[index]),
    "downloaded Storage object differs from the uploaded bytes");
}

async function cleanup() {
  await realtime.removeChannel(channel).catch(() => undefined);
  if (uploadedObject) {
    await admin.storage.from("notice-images").remove([uploadedObject]).catch(() => undefined);
  }
  await admin.from("notification_deliveries").delete().eq("created_by", adminId);
  if (noticeId) await admin.from("notices").delete().eq("id", noticeId);
  await admin.from("push_subscriptions").delete().eq("endpoint", pushEndpoint);
  await admin.from("bus_latest_state").delete().eq("bus_id", busId);
  await admin.from("bus_locations").delete().eq("bus_id", busId);
  await admin.from("bus_trips").delete().eq("bus_id", busId);
  if (originalBus) {
    await admin.from("buses").update(originalBus).eq("id", busId);
  }
  await admin.from("auth_tokens").delete().in("token", [
    hashSessionToken(adminToken),
    hashSessionToken(driverToken),
  ]);
  await admin.from("users").delete().in("id", [adminId, driverId]);
}

try {
  await seed();
  await withTimeout(subscribed.promise, "Realtime channel subscription");
  // A freshly started local Realtime container reports the channel join before its
  // PostgreSQL CDC subscription row is visible. Hosted Supabase is already warm,
  // but this short wait keeps the isolated verification deterministic.
  await delay(4_000);
  await verifyWebPushSubscription();
  await verifyRealtime();
  await verifyStorage();
  console.log("Realtime notice event: verified");
  console.log("Realtime bus location event: verified");
  console.log("Supabase Storage upload/public download: verified");
  console.log("Spring Web Push subscription persistence: verified");
} finally {
  await cleanup();
}
