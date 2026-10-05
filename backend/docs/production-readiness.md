# Spring migration production-readiness gates

This document describes pre-cutover checks only. It does not authorize an AWS deployment,
production Supabase write, schema change, or traffic switch.

운영 전환의 최신 담당자별 준비·배포·롤백 절차는 한국어 문서
[`production-cutover.md`](production-cutover.md)를 우선 따릅니다.
스테이징 최종 확인 근거와 검증 범위는 [`staging-final-verification.md`](staging-final-verification.md)에 있습니다.
전체 재테스트는 사용자의 결정에 따라 이번 준비 작업에서 수행하지 않습니다.

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
2. Compare the actual production schema and migration history read-only. Separately review and
   approve only necessary, backward-compatible differences, including the integration contracts in
   `20260918000000_integrate_realtime_storage_push.sql`. Never bulk-apply repository migrations:
   they also include seed, data backfill, credential rotation, and demo removal operations.
3. Start Spring with schema validation enabled and require a successful health check.
4. Review existing Gradle, frontend, Docker, isolated parity/integration and browser evidence.
   Record unverified scope explicitly; any further test run requires a separate decision.
5. Deploy to a staging origin and verify exact/preview CORS plus an unknown-origin rejection.
6. Obtain explicit approval before changing any production frontend API base URL.

The Docker staging configuration, environment inventory, probes, logging, deployment, and rollback
commands are defined in `staging-deployment.md`. Completing that procedure is evidence for this
gate, not authorization to switch production traffic.

## Rollback boundary

The frontend keeps separate public, auth, admin, and driver API base URLs. Rollback changes those
values back to the Supabase Edge Function and redeploys the frontend; PostgreSQL, Realtime, and
Storage remain in place. `GET /campus/path`, `POST /reports`, and legacy
`POST /buses/{id}/location` now use the public, auth, and driver Spring API groups respectively.
Edge fallback is retained only when a group base URL is absent; validated staging builds require
all four base URLs. Rolling back to Edge requires an explicitly deployed compatible Edge Function.
The staging project currently reported as missing `make-server` is not an available rollback target.
