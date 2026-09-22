# Realtime, Storage, and Web Push integration

Supabase remains the managed PostgreSQL, Realtime, and Storage platform. Spring replaces the
Edge Function at the HTTP/business-logic boundary; it does not proxy Realtime WebSockets.

## Event paths

```text
Admin UI -> Spring POST /notifications/send -> PostgreSQL notices
                                               -> Supabase Realtime -> user browser
                                               -> Web Push service -> service worker

Driver UI -> Spring POST /driver/location -> record_bus_location(...)
                                            -> bus_latest_state
                                            -> Supabase Realtime -> map clients

Admin UI -> Spring POST /notices/images -> Supabase Storage notice-images
                                         -> public object URL
```

`20260918000000_integrate_realtime_storage_push.sql` makes `notices` and
`bus_latest_state` explicit members of `supabase_realtime` and creates or aligns the public
`notice-images` bucket with Spring's 5 MiB and image MIME restrictions. Apply this migration
through the normal Supabase migration process before routing production traffic to Spring.

Spring never creates or alters the Supabase schema. At startup it performs a read-only
`information_schema` and routine check for the tables, columns, and PostgreSQL functions used by
the migrated endpoints. A missing object stops startup with the required migration name instead
of allowing the first live request to fail. `APP_SCHEMA_VALIDATION_ENABLED=false` is reserved for
focused tests and must not be used as a deployment workaround.

## CORS boundary

- `APP_CORS_ALLOWED_ORIGINS` contains exact local and production frontend origins.
- `APP_CORS_ALLOWED_ORIGIN_PATTERNS` contains only scoped preview-host patterns.
- Origins never contain a path or trailing slash.
- Unknown origins fail the preflight request; wildcard `*` is not used with authenticated APIs.
- The Supabase Edge `ALLOWED_ORIGINS` secret remains separate while the three non-migrated
  endpoints continue to run on Edge.

## Web Push boundary

- `GET /notifications/vapid-public-key` is public and returns Spring's configured public key.
- `POST /notifications/subscribe` and `POST /notifications/unsubscribe` require an existing
  `X-Auth-Token` session and preserve the Edge Function response/error contract.
- Push endpoints are restricted to standard browser push hosts plus `PUSH_ALLOWED_HOSTS`.
- Notification payloads use `aes128gcm` encryption and RFC 8292 `vapid` authorization.
- `VAPID_PRIVATE_KEY` and `SUPABASE_SERVICE_ROLE_KEY` stay server-only.

## Isolated verification

`scripts/verify-supabase-integrations.mjs` seeds only the Supabase instance supplied through
environment variables, listens with the same Supabase JavaScript Realtime client used by the
frontend, calls the running Spring API, and removes its test rows and Storage object afterward.
The script refuses non-loopback Supabase URLs so it cannot be pointed at production accidentally.
It verifies:

1. an admin notification produces a matching `notices` INSERT event;
2. a driver GPS write produces a matching `bus_latest_state` event;
3. a PNG uploaded through Spring is byte-identical at its public Storage URL;
4. an authenticated Web Push subscription is stored for the correct user.

The Gradle `WebPushSenderIntegrationTest` separately generates real P-256 subscriber/VAPID
keys, sends an encrypted request to a local HTTP push endpoint, and checks its transport headers
and ciphertext. Neither verification writes to the production Supabase project or contacts a
real browser push provider.

`npm run verify:parity` compares representative Edge and Spring status codes and JSON values,
including route-path fields, timestamp strings, authentication errors, unknown paths, cache
headers, and CORS. It refuses non-loopback Edge and Spring URLs. Run it only against an isolated
Supabase stack after both HTTP implementations are started.

`npm run build` first validates every required frontend URL and public Supabase value. Set
`UNIBUS_DEPLOYMENT_ENV=production` (Vercel also supplies `VERCEL_ENV=production`) in a real
production build so loopback URLs are rejected before Vite generates a deployable bundle.
