# UniBus Spring Boot backend

This directory is the isolated replacement for `supabase/functions/make-server`. Phase 1 established the runtime and test environment. Phase 2 migrated the public notice, route, and bus read APIs. Phase 3 migrated authentication and shared sessions. Phase 4 migrated administrator-only notice, route, bus, user, report, and notification APIs. Phase 5 migrated driver operations, including trip state, GPS updates, re-entry restoration, and stop handling. Phase 6 connects those Spring writes to Supabase Realtime and Storage and moves browser Web Push subscription management to Spring. The remaining campus-path, user-report creation and legacy GPS APIs now also use Spring. See `docs/remaining-edge-and-staging-e2e.md` for the audit and staging preparation.

## Requirements

- JDK 21 for direct local execution
- Docker for integration tests and image builds
- Supabase CLI for the existing local PostgreSQL, Realtime, and Storage stack

The repository currently keeps schema migrations under `../supabase/migrations`. Spring validates mapped entities but never creates or updates the schema (`ddl-auto=validate`), and SQL initialization is disabled.

## Run directly

Start the existing local Supabase stack first:

```bash
supabase start
```

Copy `.env.example` values into your shell, then run:

```bash
export SUPABASE_DB_URL='jdbc:postgresql://127.0.0.1:54322/postgres?sslmode=disable'
export SUPABASE_DB_USERNAME='postgres'
export SUPABASE_DB_PASSWORD='postgres'
./gradlew bootRun
```

`GET http://localhost:8080/health` is the compatibility health endpoint. `GET /actuator/health` is used by Docker and infrastructure health checks.

Point each frontend API group at Spring using the corresponding base URL:

```bash
VITE_PUBLIC_API_BASE_URL=http://localhost:8080 \
VITE_AUTH_API_BASE_URL=http://localhost:8080 \
VITE_ADMIN_API_BASE_URL=http://localhost:8080 \
VITE_DRIVER_API_BASE_URL=http://localhost:8080 \
npm run dev
```

The migrated endpoints are documented in [`docs/public-api-contract.md`](docs/public-api-contract.md)
and [`docs/auth-api-contract.md`](docs/auth-api-contract.md).
Administrator endpoints are documented in [`docs/admin-api-contract.md`](docs/admin-api-contract.md).
Driver endpoints are documented in [`docs/driver-api-contract.md`](docs/driver-api-contract.md).
Realtime, Storage, and Web Push boundaries are documented in
[`docs/integration-flow.md`](docs/integration-flow.md).

## Test and build

```bash
./gradlew clean test
./gradlew clean build
docker build -t unibus-backend:phase1 .
```

The integration test starts an isolated PostgreSQL 15 container. It never connects to the production Supabase database. Tests run before the image build; the Dockerfile packages the already-verifiable application without requiring a Docker socket inside the build container.

On macOS with Docker Desktop, the full build can also run without a host JDK:

```bash
docker run --rm \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v "$PWD:/workspace" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace \
  gradle:8.14.3-jdk21-alpine \
  gradle clean build --no-daemon
```

## Run in Docker with local Supabase

`compose.yaml` points the API container to the Supabase CLI database on the host:

```bash
docker compose up --build
```

Production credentials must be supplied as runtime environment variables. Do not bake them into the image or commit an `.env` file.

## Prepare a staging deployment

The staging profile and hardened Compose definition are intentionally separate from the local
development Compose file:

```bash
cp .env.staging.example .env.staging
docker compose --env-file .env.staging -f compose.staging.yaml config --quiet
```

Do not start it until every placeholder points to an isolated staging resource. Image publishing,
health checks, logging, deployment, and rollback are documented in
[`docs/staging-deployment.md`](docs/staging-deployment.md). This configuration does not switch any
production frontend or DNS traffic.

For the teammate provisioning AWS, use [`docs/aws-handoff.md`](docs/aws-handoff.md). It contains
the runtime contract, secret-name mapping, Supabase migration checks, image digest handoff, and
post-deploy acceptance checklist without any real account IDs, ARNs, endpoints, or credentials.

운영 전환 준비는 [`docs/production-cutover.md`](docs/production-cutover.md)를 따릅니다.
운영 대상 확정, 백업, 담당자별 설정, 배포 순서, 중단 기준과 롤백을 정리한 준비 문서이며
실제 운영 변경은 별도 명시적 승인이 필요합니다.

## Migration safety boundaries

- Only health and the documented public GET endpoints are public.
- Every not-yet-migrated route and every mutation method is denied by Spring Security.
- The datasource is required and Hibernate cannot mutate the schema.
- Request bodies with a declared size above 6 MiB are rejected, matching the Edge Function boundary.
- Supabase remains the Realtime and Storage provider; neither service is reimplemented in Spring.
- Browser Realtime remains connected directly to Supabase. Spring publishes indirectly by committing to the existing `notices` and `bus_latest_state` tables in the `supabase_realtime` publication.
- Notice detail view-count increments and route path coordinate/cache updates preserve existing Edge behavior. Contract tests exercise those writes only in an isolated PostgreSQL container.
- Passwords remain bcrypt `$2b$` cost 10. Session tokens remain compatible in both directions: clients receive a 32-byte base64url token while PostgreSQL stores its `sha256:` digest. Legacy plaintext session rows are upgraded when Spring validates them.
- Notice image uploads continue to use Supabase Storage through its REST API. Keep `SUPABASE_SERVICE_ROLE_KEY` server-only.
- Web Push uses the existing VAPID keys and validates subscription endpoint hosts before making outbound requests.
- The frontend fetches the VAPID public key and manages subscriptions through Spring. There is no hard-coded fallback key, so a public/private VAPID mismatch fails visibly instead of creating an unusable subscription.
- Driver starts are serialized by driver and bus row locks. The database's active-trip uniqueness constraints remain the final guard against two drivers claiming one bus or one driver retaining multiple active trips.
- GPS writes continue through the existing `record_bus_location` function, preserving latest-state upserts and 30-second history sampling for Supabase Realtime consumers.
