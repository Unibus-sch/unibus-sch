# Spring migration production-readiness gates

This document describes pre-cutover checks only. It does not authorize an AWS deployment,
production Supabase write, schema change, or traffic switch.

## Required configuration

Frontend build variables:

- `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`
- `VITE_PUBLIC_API_BASE_URL`, `VITE_AUTH_API_BASE_URL`
- `VITE_ADMIN_API_BASE_URL`, `VITE_DRIVER_API_BASE_URL`
- `UNIBUS_DEPLOYMENT_ENV=production` for a non-Vercel production build

Spring runtime variables:

- `SUPABASE_DB_URL`, `SUPABASE_DB_USERNAME`, `SUPABASE_DB_PASSWORD`
- `SUPABASE_API_URL`, `SUPABASE_SERVICE_ROLE_KEY`
- `APP_CORS_ALLOWED_ORIGINS`, `APP_CORS_ALLOWED_ORIGIN_PATTERNS`
- Naver, Kakao, and VAPID values listed in `backend/.env.example`

Never put database passwords, the service-role key, or the VAPID private key in a `VITE_`
variable. Deployment logs and test reports must not print their values.

## Mandatory order

1. Back up and inspect the target Supabase project.
2. Apply reviewed Supabase migrations through
   `20260918000000_integrate_realtime_storage_push.sql` using the normal migration process.
3. Start Spring with schema validation enabled and require a successful health check.
4. Run Gradle, frontend environment/type/build tests, Docker build, isolated parity, integration,
   and browser E2E checks.
5. Deploy to a staging origin and verify exact/preview CORS plus an unknown-origin rejection.
6. Obtain explicit approval before changing any production frontend API base URL.

The Docker staging configuration, environment inventory, probes, logging, deployment, and rollback
commands are defined in `staging-deployment.md`. Completing that procedure is evidence for this
gate, not authorization to switch production traffic.

## Rollback boundary

The frontend keeps separate public, auth, admin, and driver API base URLs. Rollback changes those
values back to the Supabase Edge Function and redeploys the frontend; PostgreSQL, Realtime, and
Storage remain in place. The three intentionally non-migrated endpoints (`GET /campus/path`,
`POST /reports`, and legacy `POST /buses/{id}/location`) continue to use Edge throughout.
