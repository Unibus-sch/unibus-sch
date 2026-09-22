# Driver API migration contract

This contract was derived from `supabase/functions/make-server/routes/driver.tsx` and the
existing PostgreSQL functions and constraints. Spring uses the same `X-Auth-Token` sessions,
and both `driver` and `admin` roles may call these endpoints.

Authentication failures preserve the Edge responses:

| Condition | Status | JSON |
| --- | --- | --- |
| Header absent | `401` | `{ "error": "Unauthorized: No token provided" }` |
| Token invalid | `401` | `{ "error": "Unauthorized: Invalid token" }` |
| Token expired | `401` | `{ "error": "Unauthorized: Token expired" }` |
| Role is neither driver nor admin | `403` | `{ "error": "Forbidden: Driver or Admin access required" }` |

Migrated endpoints:

- `GET /driver/buses`: active buses assigned to the current driver or available as shared buses
- `POST /driver/start`: begin a trip and close any previous active trip for the same driver
- `PUT /driver/progress`: save stop progress and perform configured route transitions
- `PUT /driver/phase`: advance station-waiting service to the campus-bound phase
- `POST /driver/location`: validate and normalize GPS data, update latest state, and sample history
- `POST /driver/stop`: complete the active trip and restore its original assigned route
- `GET /driver/status`: restore the active bus, current route, stops, and trip after app re-entry

All success responses keep the Edge envelope `{ "success": true, "data": ... }`. Route and
trip fields remain camelCase, while the legacy bus state fields remain `is_running`,
`current_driver_id`, `assigned_driver_id`, `is_assigned_to_me`, and `is_shared`. A driver with
no active operation receives `200` with `{ "success": true, "data": { "activeBus": null } }`.

## Concurrency and state rules

- Starting a trip locks the driver row and selected bus row in one transaction.
- If two drivers start the same bus concurrently, one succeeds and the other receives `409`.
- Concurrent starts by the same driver are serialized; the later request replaces the earlier
  operation, matching the Edge behavior while leaving exactly one active trip and running bus.
- Existing partial unique indexes on active trips remain the database-level invariant.
- GPS calls reuse `record_bus_location`, including speed clamping to `0..55`, heading
  normalization to `0..359`, latest-state upsert, and 30-second history sampling.
- Administrator force-stop continues to use `admin_force_stop_bus`; it cancels the active trip,
  restores `origin_route_id`, clears the running driver, and makes `/driver/status` return null.

## Runtime configuration

`VITE_DRIVER_API_BASE_URL` selects the Spring driver API origin. If omitted, the frontend falls
back to `VITE_AUTH_API_BASE_URL`, so authentication and driver APIs can share one Spring origin.
No Supabase service-role key is sent by the browser.
