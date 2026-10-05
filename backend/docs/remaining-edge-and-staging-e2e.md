# Remaining Edge migration and staging E2E handoff

Work is isolated to `/Users/gwonjaewon/Desktop/unibus-sch-spring-migration` on
`codex/spring-migration`. This checkpoint does not deploy AWS/Vercel or modify any remote DB.
The operator chose tools/documentation preparation; real staging account/data creation is pending.

## Audit and API contracts

The audit covers `src`, `utils`, the Vercel `api` directory, all Edge route handlers, their auth
middleware and SQL migrations. No browser `supabase.functions.invoke` calls were found.

| Previously remaining call | Spring endpoint | Frontend group | Preserved behavior |
|---|---|---|---|
| campus route | GET `/campus/path` | public | anonymous, `success/data/path/stops/cached`, `[lng,lat]`, marker fields |
| user report | POST `/reports` | auth | application session, validation, nullable related IDs, 5 requests / 600 sec |
| legacy bus GPS | POST `/buses/{id}/location` | driver | driver/admin session, active bus ownership, validation, history response |

Supabase PostgreSQL, Realtime and Storage remain in use. API base fallback to Edge is preserved
for legacy configuration, but all four required staging base URLs point to Spring. The default
request destination is now the public API group, not an unconditional Edge destination.
`GET /notifications/vapid-public-key` was already explicitly routed to Spring; it required no migration.

Campus behavior was derived from `supabase/functions/make-server/routes/campus.tsx`:

- Find fixed campus UUID first, otherwise a `shuttle` route whose name includes `학내순환`.
  Spring deterministically sorts multiple name matches by ID; Edge's `limit(1)` was unordered.
- Use stored stops in `stop_order` and shape points in `after_stop_order, point_order` order.
  If fewer than two raw stops exist, use five fixed marker stops plus the Naver shaping point.
  Missing coordinates are omitted from visible stored markers; a single valid marker retains
  Edge's degenerate 20-point fallback behavior rather than inventing a different route.
- Hash seven-decimal `[lng,lat]` strings with SHA-256; reuse matching DB cache, then memory cache.
  Generated responses have `cached:false`; reused responses have `cached:true`.
- Cache hits bypass the 30 requests / 600 sec generation limiter. Memory cache lives 30 minutes.
- Use existing Naver Directions client and fall back to 20 interpolated points per visible-stop
  segment including the return to the first stop. No Naver secret is sent to the browser.
- `Cache-Control: public, max-age=300, stale-while-revalidate=3600`; authenticated requests still
  receive the existing private/no-store override. Cache generation may write `route_path_cache`.
- Cache writes are best effort, as on Edge. DB read failures return the Edge global `500`
  `{ "success": false, "error": "Internal server error" }` envelope.

Legacy GPS writes only `bus_locations`, exactly like Edge. It is **not** the live Realtime GPS
endpoint: existing `/driver/location` uses `record_bus_location` to update `bus_latest_state` and
sample history. Frontend active driver operation already uses `/driver/location`. The legacy
endpoint remains available for compatibility and serializes its write with bus start/stop locks.

`api/campus-route.ts` is a separate legacy Vercel Function, not a Supabase Edge invocation.
No current frontend caller was found. It is preserved for possible external callers; do not add
server secrets to Vercel to activate it. Retirement requires confirming external usage separately.

## Local verification

```bash
cd /Users/gwonjaewon/Desktop/unibus-sch-spring-migration/backend
./gradlew test build prepareStagingFixtures --no-daemon
cd ..
npm run test:api-routing
npm run test:env
npm run test:staging-smoke
npm run test:staging-fixtures
npm run typecheck
```

Gradle integration tests use disposable local PostgreSQL containers, not the staging DB. Tests
cover campus DB/cache/fallback/rate limiting, report permissions/validation/quota and legacy GPS
ownership/history behavior. Fixture tests assert bcrypt compatibility, idempotency, original
users/tokens unchanged, explicit-only route correction, and valid PNG generation. The image script
is tested with local request doubles; real Storage/Kakao/Realtime E2E still requires staging access.
`verify:parity` also includes warmed campus-path and new authentication failures, and still refuses
non-loopback targets. Updating it is not evidence that a live Edge comparison was run.

`CAMPUS_SPRING_URL=http://127.0.0.1:LOCAL_PORT npm run verify:campus-contract` executes the actual
unchanged Edge campus handler against an empty in-memory DB adapter, disables Naver configuration,
and compares its warmed fallback JSON to local Spring. This verifies the exact fallback contract
without deploying Edge or accessing Supabase; it is not a full live Edge/Supabase parity test.

### Checkpoint verification (2026-10-05)

- Gradle `test build prepareStagingFixtures --no-daemon --no-configuration-cache`: passed;
  61 tests, zero failures/errors/skips.
- Frontend typecheck and Vite build: passed with synthetic local-only environment values.
- Node API-routing, fixture-upload, environment and smoke tests: all 12 passed.
- Docker image build and container health: passed. Image ID:
  `sha256:16950b959a94365b5ff93cd2a0e3f6bb6e33833657c4a5c64367f8b8f6317898`.
- Latest Docker image: 11 HTTP smoke checks plus report creation and legacy GPS writes passed
  against disposable local PostgreSQL. Campus JSON/cache headers exactly matched the unchanged
  Edge source using the local adapter described above.
- Real Naver calls, live Edge/Supabase differential regression, browser social login, real
  Storage uploads, Realtime delivery and Web Push were **not** exercised in this checkpoint.
- No remote accounts/data were created, no existing users/tokens deleted, and no AWS/Vercel/DNS
  deployment or GitHub push was performed. Disposable local containers/network were removed
  after verification; the built image and synthetic PNG remain available locally.

## Safe local secrets and staging fixtures

Use `backend/.env.staging-e2e.example` as a template. In a local editor, save the filled configuration
as `backend/.env.staging-e2e.local`, then restrict its permissions:

```bash
chmod 600 backend/.env.staging-e2e.local
git check-ignore backend/.env.staging-e2e.local
```

Get DB connection details from the **staging** project Connect screen. Keep the password in its
separate variable, never the JDBC URL. The guard accepts only `db.srxzkpdtxmqrtcxgpeyl.supabase.co`
or a `*.pooler.supabase.com` host with username `postgres.srxzkpdtxmqrtcxgpeyl` and database
`postgres`. Require `sslmode=verify-full`; configure the provider CA via `sslrootcert` if needed.
Do not weaken TLS verification to get past a certificate failure.

Fill new unique admin/driver passwords (16+ characters, at most 72 UTF-8 bytes) using a password
manager. The two synthetic emails must be distinct and end in `@example.invalid`. Passwords,
connection values and hashes must never be pasted into chat, GitHub, shell history or logs.
The runner logs only aggregate completion or a sanitized error, never exception details.

Preparation mode needs no remote credentials and only creates a synthetic local PNG:

```bash
cd backend
./gradlew prepareStagingFixtures --no-daemon
```

After the designated operator verifies the local file and the target project, load it **without
shell tracing**, then opt into the writes. The loaded file must be trusted shell assignments:

```bash
cd backend
set +x
set -a
source .env.staging-e2e.local
set +a
STAGING_FIXTURE_MODE=apply ./gradlew prepareStagingFixtures --no-daemon --no-configuration-cache
```

This creates reserved `[E2E]` fixtures: two local bcrypt accounts, two Seoul commuter directions
with explicit `[출발]`/`[도착]` name prefixes and `region=서울`, four stops, one active (not running)
bus assigned to the fixture driver, and one low-priority notice. Synthetic timetables are not real
operating data. Relational writes share one transaction; no schema changes are made. Existing rows
are not reset; ID collisions are rejected, and reruns preserve fixture passwords and sessions.
Do not run with Gradle `--info`/`--debug`, SQL bind logging or shell `set -x`.
The task is incompatible with configuration caching, and the apply command explicitly disables
that cache to keep the secret environment out of Gradle's saved task state.

The existing Seoul station route's real direction is **not inferred from its name**. To correct it,
the operator must inspect stops/timetable and set `STAGING_COMMUTER_ROUTE_ID`,
`STAGING_COMMUTER_REGION`, `STAGING_COMMUTER_DIRECTION=to-school` or `from-school` in the local
file. Only that route changes. Run separately for the other direction if two real routes exist.

After Spring staging deploy and Storage readiness, upload the PNG and link only the fixture notice:

```bash
cd ..
STAGING_FIXTURE_MODE=apply npm run prepare:staging-image
```

The image script accepts only `https://api-staging.unibus-sch.com` and the exact staging project.
It logs in as the reserved fixture admin, uploads PNG through Spring `/notices/images`, validates
the resulting staging Storage URL, updates the fixture notice, and logs out its own new session.
It does not require a local service-role key: that key stays in the AWS backend runtime.
Storage upload and notice update cannot be one DB transaction. If upload succeeds and linking
fails, record the orphaned object for later targeted cleanup; this tool does not delete it.

## Existing two users and tokens: preserve pending provenance

Do not delete or rotate these rows. Ask the AWS operator for the original test, timestamp and
owner. Use the staging SQL Editor for metadata-only inspection (never select `token` or
`password_hash`):

```sql
SELECT u.id, u.role, u.provider, u.created_at,
       count(t.id) AS session_count, min(t.created_at) AS first_session_at,
       max(t.expires_at) AS last_session_expiry
FROM public.users u LEFT JOIN public.auth_tokens t ON t.user_id = u.id
GROUP BY u.id, u.role, u.provider, u.created_at
ORDER BY u.created_at;

SELECT id, name, type, region, schedule
FROM public.routes WHERE type IN ('commute', 'commuter') ORDER BY name;
```

Rows alone cannot prove creation purpose. Keep the operator's answer in the release record before
any separate, narrowly targeted cleanup is considered. No cleanup command is included here.

## AWS and frontend redeployment order

1. Share this checkpoint SHA and the reviewed patch (or publish the migration branch in a separately
   authorized push) with the AWS owner. A local-only SHA is not yet fetchable from GitHub. They apply/cherry-pick it onto
   `feature/server-setting`, review conflicts against their server configuration, and build the
   backend image from that exact resulting SHA. Do not merge to `main`.
2. Backend runtime already needs staging DB/API/service-role, `NAVER_CLIENT_ID`,
   `NAVER_SECRET_KEY`, VAPID public/private/subject and `PUSH_ALLOWED_HOSTS`. Keep these in the
   existing AWS secret injection mechanism. Spring uses `APP_CORS_ALLOWED_ORIGINS`, not Edge
   `ALLOWED_ORIGINS`. Include `https://unibus-sch.com` and any approved preview origin.
3. Deploy backend first; require startup schema validation and readiness. No new DB schema
   migration is required by this checkpoint. Check anonymous `/campus/path` success and GPS/report
   auth rejections before promoting the frontend commit.
4. Prepare staging fixtures using the above local operator commands; preserve old users/tokens.
5. Confirm `unibus-staging` Production Branch is `feature/server-setting`; all four API bases use
   `https://api-staging.unibus-sch.com`. Supabase values reference `srxzkpdtxmqrtcxgpeyl`. Build the
   same frontend SHA; do not copy server secrets to Vercel. CLI users should upgrade the locally
   installed Vercel 60.1.3 with `npm i -g vercel@latest` before using current deployment commands.
6. Run the existing read smoke suite, then login as each staging fixture: notices create/update/
   delete **new test notices only**, PNG upload, commuter direction tabs, assigned bus start,
   `/driver/location` GPS, state restoration and admin force-stop. Confirm Network shows zero
   `/functions/v1/make-server` requests. Check Realtime notice and latest-GPS events separately.
7. Test Kakao using a new consenting staging tester. Configure the frontend Kakao application's
   allowed domain and any OAuth Redirect URI according to `src/app/services/kakao.ts`; do not
   copy production users/tokens. Personal social login and real Push subscription need the tester.
8. Record SHA, image digest, deploy time, checks and fixture IDs. Retain previous backend image
   and frontend deployment. Follow `staging-deployment.md` for rollback; roll back both code
   versions together if necessary. A missing staging Edge Function cannot serve as a rollback.

Account passwords are shared only through the team's password manager or a restricted one-time
secret channel. Send account role/email/project/expiry separately; never post passwords or tokens
in GitHub, issue comments, this chat or the AWS handoff text.
