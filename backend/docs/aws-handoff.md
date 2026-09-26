# AWS staging handoff

This is an implementation handoff for the teammate who will create AWS resources. The repository
does not create, update, or authorize any AWS or production resource.

## Deliverables

- Git branch: `feature/server-setting`
- Container build: `backend/Dockerfile`
- Provider-neutral runtime reference: `backend/compose.staging.yaml`
- Placeholder-only configuration: `backend/.env.staging.example`
- Runtime profile: `backend/src/main/resources/application-staging.yml`
- Deployment and rollback runbook: `backend/docs/staging-deployment.md`
- Read-only post-deploy check: `npm run verify:staging`
- CI verification: `.github/workflows/spring-backend-ci.yml`
- Staging deployment: `.github/workflows/staging-backend-deploy.yml`

Record the exact Git commit, image digest, deployment timestamp, deployer, and previous known-good
digest in the release ticket. Do not use a mutable `latest` tag.

## Expected AWS runtime contract

The AWS implementation may use ECS/Fargate, ECS on EC2, or a Docker host. Preserve these settings:

| Setting | Required value |
|---|---|
| Container port | `8080/tcp` |
| Public ingress | HTTPS load balancer only; do not expose the container directly |
| Liveness | `GET /actuator/health/liveness`, expect HTTP 200 |
| Readiness/target health | `GET /actuator/health/readiness`, expect HTTP 200 |
| Startup allowance | at least 45 seconds |
| Health interval/timeout | 15 seconds / 3 seconds |
| Unhealthy threshold | 4 consecutive failures |
| Stop timeout | at least 30 seconds; deliver `SIGTERM` |
| Filesystem | read-only root with writable temporary `/tmp` only |
| Linux privileges | non-root user, no added capabilities, no privilege escalation |
| Initial resources | 1 vCPU, 768 MiB memory; tune from staging metrics |
| Desired instances | 1 for first staging validation; test 2 before production planning |
| Logging | standard output, Logstash JSON, retention configured in CloudWatch |

Required outbound connectivity:

- PostgreSQL TLS to the staging Supabase database on port 5432 or its pooler port.
- HTTPS 443 to the staging Supabase API/Storage endpoint.
- HTTPS 443 to Kakao and Naver APIs.
- HTTPS 443 only to hosts listed in `PUSH_ALLOWED_HOSTS` for Web Push.
- Container registry and AWS control-plane endpoints required by the chosen runtime.

The security group must not allow public PostgreSQL access. Prefer private tasks/instances with an
ALB in front. Restrict the staging hostname using the team's normal access-control mechanism if it
contains non-public test data.

## AWS value mapping

Secret values belong in AWS Secrets Manager or SSM SecureString and are injected at runtime. Plain
configuration can be task-definition/container environment. The names below are contracts; this
document intentionally contains no values or ARNs.

| Application variable | AWS source recommendation |
|---|---|
| `SUPABASE_DB_URL` | Secrets Manager or SSM SecureString |
| `SUPABASE_DB_USERNAME` | Secrets Manager or SSM SecureString |
| `SUPABASE_DB_PASSWORD` | Secrets Manager |
| `SUPABASE_SERVICE_ROLE_KEY` | Secrets Manager |
| `NAVER_CLIENT_ID` | Secrets Manager or SSM SecureString |
| `NAVER_SECRET_KEY` | Secrets Manager |
| `VAPID_PRIVATE_KEY` | Secrets Manager |
| `SUPABASE_API_URL` | Plain environment |
| `APP_CORS_ALLOWED_ORIGINS` | Plain environment |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | Plain environment; empty unless required |
| `KAKAO_USER_INFO_URL` | Plain environment |
| `VAPID_PUBLIC_KEY` | Plain environment |
| `VAPID_SUBJECT` | Plain environment |
| `PUSH_ALLOWED_HOSTS` | Plain environment |
| `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE` | Plain environment |
| `DB_CONNECTION_TIMEOUT_MS`, `DB_VALIDATION_TIMEOUT_MS` | Plain environment |
| `UNIBUS_LOG_FORMAT`, `UNIBUS_LOG_LEVEL_ROOT`, `UNIBUS_LOG_LEVEL_APP` | Plain environment |

The task/execution role needs only the chosen registry pull, log write, and named secret read
permissions. Do not grant wildcard secret access or database administrative credentials.

## Supabase staging preparation

Use a separate staging project. One designated operator applies migrations; AWS deployment should
not run schema migrations automatically during container startup.

Before linking, confirm the project reference in the Supabase dashboard and release ticket. Then:

```bash
supabase link --project-ref STAGING_PROJECT_REF
supabase migration list --linked
supabase db push --dry-run --linked
```

Review the dry-run list and confirm it ends with
`20260918000000_integrate_realtime_storage_push.sql`. Only after review:

```bash
supabase db push --linked
supabase migration list --linked
```

Never run `supabase db reset --linked` as part of deployment. Never use `--include-seed` against a
shared staging project unless the data reset was explicitly approved. If history differs, stop and
review the schema before considering `migration repair`; do not guess a repair status.

## Image handoff

The repository CI builds and scans an image but deliberately does not authenticate to or push an
AWS registry. The AWS owner should build from the handed-off commit, push to ECR, and record the
digest. Example names only:

```bash
GIT_SHA="GIT_COMMIT_SHA"
IMAGE_URI="AWS_ACCOUNT_ID.dkr.ecr.AWS_REGION.amazonaws.com/ECR_REPOSITORY:${GIT_SHA}"

docker build \
  --build-arg APP_VERSION="${GIT_SHA}" \
  --build-arg VCS_REF="${GIT_SHA}" \
  -t "${IMAGE_URI}" backend
docker push "${IMAGE_URI}"
```

Use the resulting `IMAGE_URI@sha256:IMAGE_DIGEST` in the task definition or Docker host. Retain the
previous digest until the rollback exercise passes.

## Post-deploy acceptance

Run the read-only smoke suite from a trusted machine with network access to staging:

```bash
UNIBUS_TARGET_ENV=staging \
STAGING_API_URL=https://STAGING_API_HOST \
STAGING_FRONTEND_ORIGIN=https://STAGING_FRONTEND_HOST \
npm run verify:staging
```

Optionally add `STAGING_ADMIN_TOKEN` and `STAGING_DRIVER_TOKEN` for authenticated read checks.
These tokens must be staging-only, short-lived, and supplied by the secret runner; the script does
not print them and performs no mutation requests.

Acceptance requires:

- AWS target health and Spring readiness remain healthy for at least ten minutes.
- The read-only smoke suite passes, including CORS denial and security headers.
- Spring startup confirms schema validation passed.
- Logs are valid JSON and contain no tokens, passwords, URLs with credentials, or private keys.
- Realtime, Storage, Web Push, administrator, and driver E2E checks pass against staging.
- Restart and scaling to two instances do not break authentication or driver-state restoration.
- Rollback to the recorded previous digest is performed and revalidated once.

## Production boundary

Do not update production Vercel variables, production DNS, an ALB production listener, or the
production Supabase project during this handoff. A successful staging deployment is evidence for a
later production decision, not permission to route production traffic.
