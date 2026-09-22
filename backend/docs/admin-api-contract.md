# Administrator API migration contract

This contract was derived from every Edge Function route guarded by `requireAdmin`.
Spring continues to use the existing `X-Auth-Token` session rows and preserves the
Edge permission errors:

| Condition | Status | JSON |
| --- | --- | --- |
| Header absent | `401` | `{ "error": "Unauthorized: No token provided" }` |
| Token invalid | `401` | `{ "error": "Unauthorized: Invalid token" }` |
| Token expired | `401` | `{ "error": "Unauthorized: Token expired" }` |
| User is not an administrator | `403` | `{ "error": "Forbidden: Admin access required" }` |

Migrated endpoints:

- Notices: `POST /notices`, `POST /notices/images`, `PUT /notices/{id}`, `DELETE /notices/{id}`
- Routes: `POST /routes`, `POST /routes/{id}/path/preview`, `PUT /routes/{id}`, `DELETE /routes/{id}`
- Buses: `POST /buses`, `PUT /buses/{id}`, `POST /buses/{id}/force-stop`, `DELETE /buses/{id}`
- Managed bus reads: `GET /buses`, `GET /buses/{id}` include private fields only for a valid admin token
- Users: `GET /users`, `PUT /users/{id}`, `PUT /users/{id}/role`
- Reports: `GET /reports`, `PUT /reports/{id}`
- Notifications: `POST /notifications/send`, `POST /notifications/send-existing`, `GET /notifications/history`

Web Push subscription management is also migrated, but is not administrator-only:

- `GET /notifications/vapid-public-key` is public.
- `POST /notifications/subscribe` and `POST /notifications/unsubscribe` accept any valid
  `X-Auth-Token` session and return the same `401` token errors as the Edge Function.

The legacy `POST /buses/{id}/location` endpoint and report creation remain on the Edge Function.
The driver application now sends GPS through the
migrated `POST /driver/location` endpoint; the legacy bus-location route is retained only for
clients that have not moved to the driver contract.

Runtime configuration:

- `VITE_ADMIN_API_BASE_URL` selects the Spring administrator API origin.
- `SUPABASE_API_URL` and `SUPABASE_SERVICE_ROLE_KEY` are server-only values used for notice-image Storage uploads.
- Existing `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`, and `PUSH_ALLOWED_HOSTS` values are reused for Web Push.
- The service-role key and VAPID private key must never use a `VITE_` prefix.
