# 남은 Edge API 이전 및 스테이징 E2E 인수인계

작업공간: `/Users/gwonjaewon/Desktop/unibus-sch-spring-migration`

작업 브랜치: `codex/spring-migration`

원본 프로젝트는 수정하지 않았습니다. 사용자가 선택한 범위에 따라 실행 도구·문서 준비까지만 진행했습니다.
실제 스테이징 계정·데이터 등록, 원격 DB 변경, AWS·Vercel·DNS 변경은 하지 않았습니다.

## 조사 결과와 API 계약

`src`, `utils`, Vercel `api` 디렉터리, Edge 라우트 전체, 인증 미들웨어와 SQL 마이그레이션을 조사했습니다.
브라우저 코드에서 `supabase.functions.invoke` 호출은 발견되지 않았습니다.

| 남아 있던 호출 | 이전한 Spring API | 프런트 API 그룹 | 유지한 동작 |
|---|---|---|---|
| 캠퍼스 경로 조회 | GET `/campus/path` | public | 비로그인 조회, `success/data/path/stops/cached`, `[lng,lat]`, 정류장 필드 |
| 사용자 문의 접수 | POST `/reports` | auth | 앱 세션 인증, 입력 검증, 관련 ID의 null 처리, 600초당 5회 제한 |
| 기존 버스 GPS 등록 | POST `/buses/{id}/location` | driver | 기사·관리자 인증, 운행 버스 소유권, 입력 검증, 위치 이력 응답 |

Supabase PostgreSQL·Realtime·Storage는 그대로 유지합니다. 기존 설정과의 호환성을 위해
API 주소가 없을 때 Edge를 사용하는 대체 경로는 남겨 두었지만, 스테이징 필수 API 주소 4개는 모두 Spring을 가리켜야 합니다.
기본 요청 대상도 무조건 Edge가 아니라 public API 그룹으로 변경했습니다.
`GET /notifications/vapid-public-key`는 이미 Spring으로 연결되어 추가 이전이 필요하지 않았습니다.

캠퍼스 경로는 `supabase/functions/make-server/routes/campus.tsx`를 기준으로 구현했습니다.

- 고정 캠퍼스 UUID를 먼저 조회하고, 없으면 이름에 `학내순환`이 포함된 `shuttle` 노선을 조회합니다.
  여러 노선이 일치하면 Spring은 ID순으로 선택합니다. 기존 Edge의 `limit(1)`에는 정렬 조건이 없었습니다.
- 정류장은 `stop_order`, 경로 보조점은 `after_stop_order, point_order` 순입니다.
  원본 정류장이 2개 미만이면 고정 정류장 5개와 네이버 경로용 보조점을 사용합니다.
  좌표가 없는 정류장은 표시 목록에서 제외합니다. 유효 정류장이 1개만 남으면 Edge처럼 동일 좌표의 20개 점을 반환합니다.
- 소수점 7자리 `[lng,lat]` 문자열을 SHA-256으로 해시합니다. 일치하는 DB 캐시를 먼저 사용하고 다음으로 메모리 캐시를 확인합니다.
  새 응답은 `cached:false`, 캐시 응답은 `cached:true`입니다.
- 캐시 적중 시 생성 횟수 제한을 적용하지 않습니다. 생성 요청은 600초당 30회, 메모리 캐시는 30분입니다.
- 기존 Naver Directions 클라이언트를 사용합니다. 실패 시 정류장 구간마다 20개 점으로 보간하며 마지막에서 첫 정류장으로 돌아오는 구간도 포함합니다.
  Naver Secret은 브라우저에 전달하지 않습니다.
- `Cache-Control: public, max-age=300, stale-while-revalidate=3600`을 유지합니다.
  인증 요청은 기존 private/no-store 설정이 우선합니다. 경로 생성 시 `route_path_cache`에 저장할 수 있습니다.
- 캐시 저장 실패는 Edge처럼 전체 요청 실패로 처리하지 않습니다. DB 조회 실패는 기존 전역 오류와 같은 500 응답
  `{ "success": false, "error": "Internal server error" }`을 반환합니다.

기존 GPS API는 Edge처럼 `bus_locations`에만 이력을 저장하며 실시간 위치 갱신 API가 아닙니다.
실제 운행 화면은 `/driver/location`을 사용하며, 이 API의 `record_bus_location`이
`bus_latest_state`와 샘플 위치 이력을 갱신합니다. 기존 GPS API는 호환성을 위해 유지하고,
운행 시작·종료와 같은 버스 잠금을 사용해 쓰기 충돌을 방지합니다.

`api/campus-route.ts`는 Supabase Edge 호출이 아니라 별도의 기존 Vercel Function입니다.
현재 프런트 호출은 없지만 외부 호출 가능성이 있어 보존했습니다. 이를 활성화하려고 Vercel에 서버 Secret을 추가하면 안 됩니다.
삭제하려면 외부 사용 여부를 먼저 확인해야 합니다.

## 로컬 검증 방법

```bash
cd /Users/gwonjaewon/Desktop/unibus-sch-spring-migration/backend
./gradlew test build prepareStagingFixtures --no-daemon --no-configuration-cache
cd ..
npm run test:api-routing
npm run test:env
npm run test:staging-smoke
npm run test:staging-fixtures
npm run typecheck
```

Gradle 통합 테스트는 스테이징 DB가 아닌 임시 로컬 PostgreSQL 컨테이너를 사용합니다.
캠퍼스 DB·캐시·대체 경로·횟수 제한, 문의 권한·입력 검증·횟수 제한, 기존 GPS 소유권·이력을 검증합니다.
데이터 준비 도구는 bcrypt 호환성, 반복 실행 안전성, 기존 사용자·토큰 보존,
명시한 노선만 수정하는 동작과 정상 PNG 생성을 검증합니다.
이미지 스크립트는 로컬 요청 대역으로 검증했습니다. 실제 Storage·카카오·Realtime E2E에는 스테이징 권한이 필요합니다.

`verify:parity`에는 캐시 생성 후 캠퍼스 조회와 신규 인증 실패 비교를 추가했습니다.
로컬 주소만 허용하며, 도구 수정 자체가 실제 Edge 비교 실행을 의미하지는 않습니다.

`CAMPUS_SPRING_URL=http://127.0.0.1:LOCAL_PORT npm run verify:campus-contract`는
수정하지 않은 Edge 캠퍼스 코드를 빈 메모리 DB 어댑터로 실행합니다.
Naver 설정을 끄고 캐시 생성 후 대체 경로 JSON을 로컬 Spring과 비교합니다.
Edge 배포나 Supabase 접속 없는 대체 경로 계약 검증이며, 실제 Edge·Supabase 전체 차등 테스트는 아닙니다.

### 체크포인트 검증 결과 — 2026-10-05

- Gradle `test build prepareStagingFixtures --no-daemon --no-configuration-cache` 통과: 테스트 61개, 실패·오류·건너뛰기 0개.
- 프런트 타입 검사와 Vite 빌드 통과: 실제 키가 아닌 로컬 검증용 환경변수 사용.
- Node API 연결·이미지 준비·환경변수·스모크 테스트 12개 모두 통과.
- Docker 이미지 빌드와 컨테이너 헬스체크 통과. 이미지 ID:
  `sha256:16950b959a94365b5ff93cd2a0e3f6bb6e33833657c4a5c64367f8b8f6317898`.
- 최신 Docker 이미지와 임시 DB에서 HTTP 스모크 검사 11개, 문의 생성, 기존 GPS 쓰기 검증 통과.
  로컬 어댑터로 실행한 Edge 원본과 캠퍼스 JSON·캐시 헤더가 정확히 일치함을 확인.
- 실제 Naver 호출, 실제 Edge·Supabase 차등 회귀, 브라우저 소셜 로그인, 실제 Storage 업로드,
  Realtime 전달, Web Push는 이번 체크포인트에서 검증하지 않음.
- 원격 계정·데이터 생성, 기존 사용자·토큰 삭제, AWS·Vercel·DNS 변경, GitHub 푸시는 하지 않음.
  임시 컨테이너·네트워크는 제거했으며 빌드 이미지와 테스트 PNG는 로컬에 보존.

## 비밀값을 안전하게 설정하고 테스트 데이터를 준비하는 방법

### 실제 스테이징 등록 결과 — 2026-10-05

로컬 설정 준비 후 사용자 승인을 받아 테스트 관리자·기사 각 1명, 통학 노선 2개,
정류장 4개, 운행 중이 아닌 테스트 버스 1대, 테스트 공지 1개를 등록했습니다.
기존 노선 수정 옵션은 사용하지 않았고, 기존 사용자·토큰 삭제 또는 교체는 하지 않았습니다.
이미지는 로컬 PNG 생성까지만 완료했으며 실제 Storage 업로드와 AWS·Vercel 배포는 아직 하지 않았습니다.

최초 접속은 CA 인증서 파일 누락으로 중단되었습니다. Supabase 설정 화면에서 제공하는
`https://supabase-downloads.s3-ap-southeast-1.amazonaws.com/prod/ssl/prod-ca-2021.crt`를
`backend/build/staging-fixtures/supabase-ca.crt`로 내려받고, 실행 시 JDBC URL에
`sslrootcert`를 추가해 정상 등록했습니다. 로컬 비밀 설정 파일 자체는 변경하지 않았습니다.
다시 실행할 때도 `sslmode=verify-full`을 유지하고 JDBC URL에 아래 매개변수를 추가해야 합니다.

```text
&sslrootcert=/Users/gwonjaewon/Desktop/unibus-sch-spring-migration/backend/build/staging-fixtures/supabase-ca.crt
```

이 인증서는 공개 CA 인증서이며 Git에서 제외되는 빌드 폴더에 저장했습니다.
도구 실패 시 비밀값을 출력하지 않고 예외 종류와 SQL 상태 코드만 표시하도록 진단을 보강했습니다.

`backend/.env.staging-e2e.example`을 참고해 로컬 편집기에서 값을 입력하고
Git에서 제외되는 `backend/.env.staging-e2e.local`로 저장합니다. 권한을 제한하고 제외 여부를 확인합니다.

```bash
chmod 600 backend/.env.staging-e2e.local
git check-ignore backend/.env.staging-e2e.local
```

DB 접속정보는 스테이징 Supabase 프로젝트의 Connect 화면에서 확인합니다.
비밀번호는 별도 환경변수에 저장하고 JDBC URL에 넣지 않습니다.
안전장치는 `db.srxzkpdtxmqrtcxgpeyl.supabase.co` 또는 `*.pooler.supabase.com` 호스트만 허용합니다.
Pooler 사용자 이름은 `postgres.srxzkpdtxmqrtcxgpeyl`, DB 이름은 `postgres`여야 합니다.
`sslmode=verify-full`이 필수이며 필요하면 공급자의 CA 인증서를 `sslrootcert`로 지정합니다.
인증서 오류 때문에 TLS 검증 수준을 낮추면 안 됩니다.

비밀번호 관리 도구로 관리자·기사용 새 비밀번호를 각각 생성합니다.
16자 이상, UTF-8 기준 최대 72바이트이며 두 이메일은 서로 다르고 `@example.invalid`로 끝나야 합니다.
비밀번호·접속정보·해시는 채팅, GitHub, 셸 명령 기록이나 로그에 붙여 넣지 않습니다.
도구는 완료 요약이나 비밀값이 제거된 오류만 출력하며 예외 상세 정보는 출력하지 않습니다.

준비 모드는 원격 접속정보 없이 로컬 테스트 PNG만 생성합니다.

```bash
cd backend
./gradlew prepareStagingFixtures --no-daemon
```

실제 등록은 담당자가 로컬 파일과 대상 프로젝트를 확인한 뒤 명시적으로 실행합니다.
파일은 신뢰할 수 있는 셸 변수 설정 파일이어야 하며 명령 추적을 끈 상태에서 읽어 들입니다.

```bash
cd backend
set +x
set -a
source .env.staging-e2e.local
set +a
STAGING_FIXTURE_MODE=apply ./gradlew prepareStagingFixtures --no-daemon --no-configuration-cache
```

등록 대상은 예약된 `[E2E]` 테스트 데이터입니다.

- bcrypt 계정 2개: 관리자 1명, 기사 1명.
- 서울 통학 노선 2개: `[출발]`·`[도착]` 접두사로 방향 구분, `region=서울`.
- 정류장 4개와 테스트 기사에게 배정된 활성 버스 1대. 운행 중 상태로 만들지는 않습니다.
- 낮은 우선순위의 테스트 공지 1개.

테스트 시간표는 실제 운행 정보가 아닙니다. DB 쓰기는 한 트랜잭션으로 처리하며 스키마는 변경하지 않습니다.
기존 행은 초기화하지 않고 ID 충돌은 거부합니다. 반복 실행해도 테스트 계정 비밀번호와 세션을 보존합니다.
Gradle `--info`·`--debug`, SQL 바인딩 값 로깅, 셸 `set -x`를 사용하지 마세요.
비밀 환경변수가 Gradle 저장 상태에 포함되지 않도록 이 작업은 configuration cache를 지원하지 않으며
실제 등록 명령에도 `--no-configuration-cache`를 명시합니다.

기존 서울역 노선의 방향은 이름만 보고 추측하지 않습니다.
담당자가 정류장·시간표를 확인한 뒤 로컬 파일의 `STAGING_COMMUTER_ROUTE_ID`,
`STAGING_COMMUTER_REGION`, `STAGING_COMMUTER_DIRECTION=to-school` 또는 `from-school`을 설정해야 합니다.
지정한 노선만 수정합니다. 등교·하교 노선이 각각 존재하면 방향별로 따로 실행합니다.

Spring 배포와 Storage 준비가 완료되면 PNG를 업로드하고 테스트 공지에만 연결합니다.
위에서 환경변수를 읽은 동일한 터미널에서 실행합니다.

```bash
cd ..
STAGING_FIXTURE_MODE=apply npm run prepare:staging-image
```

이미지 스크립트는 정확한 스테이징 프로젝트와 `https://api-staging.unibus-sch.com`만 허용합니다.
예약된 테스트 관리자로 로그인하여 Spring `/notices/images`로 PNG를 업로드하고,
스테이징 Storage URL인지 검증한 뒤 테스트 공지에 연결합니다. 마지막에 새로 만든 세션만 로그아웃합니다.
로컬 service-role key는 필요하지 않으며 AWS 백엔드 실행 환경에만 둡니다.
Storage 업로드와 공지 수정은 한 DB 트랜잭션으로 묶을 수 없습니다.
업로드 후 연결이 실패하면 남은 파일을 기록해 추후 개별 정리합니다. 이 도구는 파일을 삭제하지 않습니다.

## 기존 사용자 2건·토큰 2건: 생성 목적 확인 전 보존

기존 행을 삭제하거나 토큰을 교체하지 마세요. AWS 담당자에게 어떤 테스트에서 언제, 누가 생성했는지 확인합니다.
스테이징 SQL Editor에서는 메타데이터만 조회합니다. `token`·`password_hash`를 조회하지 않습니다.

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

행 정보만으로 생성 목적을 확정할 수는 없습니다. 담당자 답변을 배포 기록에 남긴 후
별도 승인된 범위에서 특정 행만 정리할지 판단합니다. 이 문서에는 삭제 명령을 포함하지 않습니다.

## AWS 담당자에게 전달할 재배포 순서

1. 체크포인트 SHA와 검토된 패치를 전달하거나, 별도 푸시 승인을 받아 마이그레이션 브랜치를 게시합니다.
   로컬 SHA만으로는 GitHub에서 가져올 수 없습니다. AWS 담당자는 `feature/server-setting`에 적용하거나 cherry-pick하고
   서버 설정 충돌을 검토한 뒤 확정된 커밋으로 백엔드 이미지를 빌드합니다. `main`에 병합하지 않습니다.
2. 백엔드에는 스테이징 DB·API·service-role, `NAVER_CLIENT_ID`, `NAVER_SECRET_KEY`,
   VAPID 공개키·개인키·subject, `PUSH_ALLOWED_HOSTS`가 필요합니다. 기존 AWS 비밀값 주입 방식으로 관리합니다.
   Spring CORS는 Edge의 `ALLOWED_ORIGINS`가 아닌 `APP_CORS_ALLOWED_ORIGINS`입니다.
   `https://unibus-sch.com`과 승인된 프리뷰 주소를 포함합니다.
3. 백엔드를 먼저 배포하고 시작 시 스키마 검증과 readiness를 확인합니다. 이번 변경에는 새 DB 스키마 마이그레이션이 필요하지 않습니다.
   비로그인 `/campus/path` 성공과 GPS·문의 미인증 요청 거부를 확인한 뒤 프런트를 배포합니다.
4. 위 절차로 스테이징 테스트 데이터를 준비합니다. 기존 사용자·토큰은 보존합니다.
5. Vercel `unibus-staging` Production Branch가 `feature/server-setting`인지 확인합니다.
   API 주소 4개는 모두 `https://api-staging.unibus-sch.com`, Supabase는 `srxzkpdtxmqrtcxgpeyl`이어야 합니다.
   동일한 코드 버전의 프런트를 빌드하고 서버 Secret을 Vercel에 복사하지 않습니다.
   CLI 사용 시 설치된 Vercel 60.1.3을 `npm i -g vercel@latest`로 업데이트하는 것을 권장합니다.
6. 읽기 스모크 테스트 후 테스트 관리자·기사로 각각 로그인합니다. 새 테스트 공지만 생성·수정·삭제하고,
   PNG 업로드, 통학 방향 탭, 배정 버스 운행 시작, `/driver/location` GPS, 상태 복원, 관리자 강제 종료를 확인합니다.
   브라우저 Network에 `/functions/v1/make-server` 요청이 없는지 확인합니다. Realtime 공지·최신 GPS 이벤트도 별도로 확인합니다.
7. 동의한 새 스테이징 사용자로 카카오 로그인을 확인합니다. `src/app/services/kakao.ts`를 기준으로
   프런트 카카오 앱의 허용 도메인과 해당 OAuth Redirect URI를 설정합니다. 운영 사용자·토큰은 복사하지 않습니다.
   개인 소셜 로그인과 실제 Push 구독에는 테스트 사용자 참여가 필요합니다.
8. SHA, 이미지 digest, 배포 시각, 검사 결과와 테스트 데이터 ID를 기록합니다. 이전 백엔드 이미지와 프런트 배포를 보존합니다.
   롤백은 `staging-deployment.md`를 따르며 필요하면 백엔드·프런트를 함께 되돌립니다.
   미배포된 스테이징 Edge Function은 롤백 대상으로 사용할 수 없습니다.

계정 비밀번호는 팀 비밀번호 관리 도구 또는 접근이 제한된 일회성 비밀 전달 채널로만 공유합니다.
역할·이메일·프로젝트·만료 시점은 별도로 전달하고 비밀번호·토큰은 GitHub·이슈·채팅·AWS 전달 문서에 작성하지 않습니다.
