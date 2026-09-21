# Spring Boot staging deployment

This configuration prepares an isolated Docker staging deployment. It does not change production
DNS, frontend API base URLs, AWS resources, or the production Supabase project.

## Files and safety boundary

- `Dockerfile` builds the same immutable image for staging and a later production rollout.
- `compose.staging.yaml` runs that image with the `staging` Spring profile, a read-only root
  filesystem, dropped Linux capabilities, bounded resources, health checks, and rotated logs.
- `.env.staging.example` contains names and placeholders only. Copy it to `.env.staging` on the
  staging host; the populated file is ignored by Git and must be mode `0600`.
- `application-staging.yml` fails startup when required integration values are absent. Database
  schema validation is always enabled in this profile and cannot be disabled through Compose.

Use an isolated staging Supabase project or a disposable clone. Do not point these values at the
production project while validating the deployment.

The AWS owner should also follow `aws-handoff.md`, which maps this provider-neutral contract to
AWS networking, IAM, Secrets Manager, health checks, image digests, and acceptance evidence.

## Environment variables

| Variable | Required | Secret | Purpose |
|---|---:|---:|---|
| `UNIBUS_BACKEND_IMAGE` | yes | no | Immutable registry reference; prefer `@sha256:...` |
| `UNIBUS_STAGING_BIND_ADDRESS`, `UNIBUS_STAGING_PORT` | no | no | Host bind address and port; defaults to `127.0.0.1:18080` |
| `SUPABASE_DB_URL` | yes | sensitive | JDBC URL with TLS, pointing only to staging |
| `SUPABASE_DB_USERNAME` | yes | sensitive | Staging database login name |
| `SUPABASE_DB_PASSWORD` | yes | yes | Staging database password |
| `SUPABASE_API_URL` | yes | no | Staging Supabase REST/Storage base URL |
| `SUPABASE_SERVICE_ROLE_KEY` | yes | yes | Server-only Storage service-role key |
| `APP_CORS_ALLOWED_ORIGINS` | yes | no | Exact staging frontend origins, comma-separated |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | no | no | Scoped preview patterns; empty is safer when unused |
| `NAVER_CLIENT_ID` | yes | sensitive | Staging Naver API client identifier |
| `NAVER_SECRET_KEY` | yes | yes | Staging Naver API secret |
| `KAKAO_USER_INFO_URL` | no | no | Kakao user-info endpoint |
| `VAPID_PUBLIC_KEY` | yes | no | Public key used by the staging frontend |
| `VAPID_PRIVATE_KEY` | yes | yes | Server-only staging VAPID private key |
| `VAPID_SUBJECT` | yes | no | Staging operator `mailto:` contact |
| `PUSH_ALLOWED_HOSTS` | yes | no | Explicit Web Push endpoint host allowlist |
| `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE` | no | no | Connection pool sizing; defaults to `10` and `2` |
| `DB_CONNECTION_TIMEOUT_MS`, `DB_VALIDATION_TIMEOUT_MS` | no | no | Database timeout controls |
| `UNIBUS_LOG_FORMAT` | no | no | Console format; defaults to `logstash` JSON |
| `UNIBUS_LOG_LEVEL_ROOT`, `UNIBUS_LOG_LEVEL_APP` | no | no | Root and application levels; default `INFO` |
| `UNIBUS_DOCKER_LOG_MAX_SIZE`, `UNIBUS_DOCKER_LOG_MAX_FILE` | no | no | Docker log rotation limits |
| `UNIBUS_STAGING_CPU_LIMIT`, `UNIBUS_STAGING_MEMORY_LIMIT` | no | no | Container resource limits |

Inject secrets from the deployment platform's secret manager whenever possible. Environment
variables still appear in container metadata, so access to the Docker daemon and host must be
restricted. Never prefix server secrets with `VITE_`, print `docker compose config` without
`--quiet`, enable SQL parameter logging, or run the application with `DEBUG` in staging.

## Health checks

- `/actuator/health/liveness` checks only the Spring process state. It deliberately does not depend
  on Supabase, preventing restart storms during a database incident.
- `/actuator/health/readiness` checks Spring readiness and the database. Docker removes the
  container from a healthy state if it cannot serve database-backed API traffic.
- `/health` remains the Edge-compatible application endpoint, but infrastructure must use the
  Actuator probes.
- Health responses never expose component names or details in the staging profile.

The image-level and Compose health checks both use readiness. Compose allows 45 seconds for
startup, checks every 15 seconds, times out after 3 seconds, and marks the container unhealthy
after four consecutive failures.

## Build and publish

Run these commands from `backend/`. Substitute registry/image names only; do not put credentials
on the command line.

```bash
./gradlew clean test build

GIT_SHA="$(git rev-parse HEAD)"
IMAGE_TAG="REGISTRY_HOST/ORGANIZATION/unibus-backend:${GIT_SHA}"
docker build \
  --build-arg APP_VERSION="${GIT_SHA}" \
  --build-arg VCS_REF="${GIT_SHA}" \
  -t "${IMAGE_TAG}" .
docker push "${IMAGE_TAG}"
```

Resolve and record the pushed digest in the release record. Set `UNIBUS_BACKEND_IMAGE` to that digest,
not `latest`, so a deploy and rollback always use known bytes.

## Deploy without production cutover

1. Create an isolated staging Supabase project and apply the reviewed repository migrations.
2. On the staging host, copy `.env.staging.example` to `.env.staging`, populate it from the secret
   manager, and run `chmod 600 .env.staging`.
3. Validate interpolation without printing resolved secrets:

   ```bash
   docker compose --env-file .env.staging -f compose.staging.yaml config --quiet
   ```

4. Record the currently deployed image digest as `PREVIOUS_IMAGE`, then pull and start the new one:

   ```bash
   docker compose --env-file .env.staging -f compose.staging.yaml pull api
   docker compose --env-file .env.staging -f compose.staging.yaml up -d --no-deps api
   ```

5. Wait for `healthy`, then verify readiness through the host-only port:

   ```bash
   docker compose --env-file .env.staging -f compose.staging.yaml ps
   curl --fail --silent --show-error \
     "http://127.0.0.1:18080/actuator/health/readiness"
   ```

6. Test the staging domain, CORS preflight, login, public reads, administrator authorization,
   driver recovery, Realtime, Storage, and Web Push. Keep production frontend variables and DNS
   unchanged; only a dedicated staging frontend may point at this API.

   Run the default read-only checks from the repository root:

   ```bash
   UNIBUS_TARGET_ENV=staging \
   STAGING_API_URL=https://STAGING_API_HOST \
   STAGING_FRONTEND_ORIGIN=https://STAGING_FRONTEND_HOST \
   npm run verify:staging
   ```

   Optional `STAGING_ADMIN_TOKEN` and `STAGING_DRIVER_TOKEN` values add authenticated read checks.
   The smoke suite never performs mutation requests and never prints tokens or response bodies.

## Logs

Spring writes Logstash JSON to standard output. Docker rotates the outer `json-file` log at 10 MiB
and retains five files by default. Review recent logs without rendering environment variables:

```bash
docker compose --env-file .env.staging -f compose.staging.yaml logs --since 10m api
```

Application logs must not contain tokens, request bodies, passwords, database URLs, service-role
keys, VAPID private keys, or full push subscription keys. Keep application logging at `INFO` and
raise a single package temporarily only during a controlled investigation.

## Rollback

Rollback changes only the backend container image. It does not revert the database and does not
switch production traffic.

```bash
UNIBUS_BACKEND_IMAGE="REGISTRY_HOST/ORGANIZATION/unibus-backend@sha256:PREVIOUS_IMAGE_DIGEST" \
  docker compose --env-file .env.staging -f compose.staging.yaml pull api

UNIBUS_BACKEND_IMAGE="REGISTRY_HOST/ORGANIZATION/unibus-backend@sha256:PREVIOUS_IMAGE_DIGEST" \
  docker compose --env-file .env.staging -f compose.staging.yaml up -d --no-deps api
```

Confirm readiness and the staging smoke tests again. If the new release included a database
migration, use only a separately reviewed forward-compatible database recovery plan; never run an
automatic destructive down-migration as part of container rollback.

## Promotion gate

Staging success is not production approval. Production promotion requires a reviewed image digest,
backup evidence, migration compatibility review, full regression results, an observed rollback
exercise, and explicit authorization to change frontend API base URLs or DNS.
