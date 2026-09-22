# Authentication API migration contract

This contract was derived from the existing Hono auth routes, token helpers, and
authentication middleware under `supabase/functions/make-server`.

| Method and path | Success | Important errors |
| --- | --- | --- |
| `POST /auth/signup` | `200`, `{ success, data: { user } }` | existing validation messages and `400`; server failure `500` |
| `POST /auth/login` | `200`, `{ success, token, user }` | invalid credentials `401`; rate limit `429` |
| `POST /auth/kakao` | `200`, `{ success, token, user }` | invalid Kakao token/profile `401`; rate limit `429` |
| `POST /auth/logout` | `200`, `{ success, message }` | idempotent when the token is absent |

Compatibility rules:

- Existing bcryptjs `$2b$` hashes are verified without resetting or rewriting the password.
- New local accounts use bcrypt `$2b$` with cost 10, matching the Edge Function.
- A session token returned to the client is 32 random bytes encoded as unpadded base64url.
- PostgreSQL stores `sha256:` plus the unpadded base64url SHA-256 digest, exactly matching the Edge helper.
- Existing hashed Edge sessions are accepted by Spring.
- Pre-hardening plaintext session rows are accepted once and upgraded to the hashed form.
- Spring-issued sessions remain usable by authenticated endpoints that still run on the Edge Function.

`VITE_AUTH_API_BASE_URL` selects the Spring auth origin. If it is unset, the
frontend uses `VITE_PUBLIC_API_BASE_URL`; if both are unset, auth stays on the
Edge Function for a reversible rollout.
