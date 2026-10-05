# 운영 전환 준비·배포·롤백 인수인계

작성일: 2026-10-05. 상태: **준비 문서 완료 / 운영 전환 미승인**.

이번 작업은 문서 준비만 수행했습니다. 운영·스테이징 설정, AWS, Vercel, DNS, DB와 Storage는 변경하지 않았습니다.
아래 배포·변경 단계는 담당자가 별도 승인 후 수행할 미래 절차입니다.
원본 프로젝트와 UI 브랜치는 전환 대상에 포함하지 않습니다.

## 1. 운영 대상 확정

현재 확인된 스테이징은 Vercel `unibus-staging`, 프런트 `https://unibus-sch.com`,
API `https://api-staging.unibus-sch.com`, Supabase `srxzkpdtxmqrtcxgpeyl`입니다.
Vercel의 `Production` 표시는 프로젝트 내 배포 대상 이름이며 실제 운영 데이터 환경이라는 뜻이 아닙니다.
`unibus-sch.com`을 운영에 사용할지 별도의 운영 도메인을 사용할지는 아직 승인되지 않았습니다.
가비아 네임서버·기존 DNS는 이번 준비 작업에서 변경하지 않습니다.

| 확정할 항목 | 확인 담당 |
|---|---|
| 운영 Vercel Team·Project ID·브랜치·프런트 도메인 | 사용자 / 팀장 |
| 운영 API HTTPS Origin·AWS 서비스·리전 | AWS 담당자 |
| 기존 운영 Supabase 프로젝트 ID | Supabase 담당자 |
| 검토된 서버·프런트 SHA, 이미지 digest | AWS / 프런트 담당자 |
| 이전 프런트 배포 ID, Edge URL 또는 이전 Spring digest | 공동 |
| 승인자·작업 시간·모니터링 담당·롤백 결정자 | 팀장 |

기본 데이터 원칙은 **기존 운영 Supabase의 운영 데이터를 유지**하는 것입니다.
스테이징 DB를 운영으로 이름만 바꾸거나 테스트 사용자·토큰·노선·공지·이미지를 운영으로 복사하지 않습니다.
새 운영 DB로 데이터 이전을 선택한다면 별도 이전·복구 계획과 승인이 필요합니다.

## 2. 담당자별 준비

### 사용자 / 프런트 담당자

- [ ] 운영 Vercel 프로젝트·브랜치 확정 및 이전 정상 배포 ID 보존.
- [ ] 승인된 운영 API와 기존 운영 Supabase의 공개 설정을 준비.
- [ ] 운영 카카오 JavaScript SDK 도메인·키 및 네이버 지도 허용 도메인 확인.
- [ ] 운영 환경변수로 새 프런트 빌드를 준비. 스테이징 빌드를 그대로 승격하지 않음.
- [ ] 유지할 스테이징 프로젝트·도메인과 운영 변경 대상을 구분.

### AWS 담당자

- [ ] 카카오 수정 `d63fccf`가 포함된 실제 서버 SHA·이미지 digest를 확인.
- [ ] UI 브랜치가 제외된 승인된 릴리스 사용. `main` 병합·푸시는 별도 승인 없이는 금지.
- [ ] 운영 전용 서비스, HTTPS, 최소 권한, Secret 주입, CORS, Naver, VAPID 구성 준비.
- [ ] 비루트·읽기 전용 컨테이너, readiness/liveness, 시작·종료 유예, 이전 이미지 보존.
- [ ] JSON 로그·보관 기간·알람·모니터링 담당과 DB 연결 풀 합계/연결 한도 확인.
- [ ] 다중 인스턴스 운행 잠금·세션·공유 rate-limit 검증 근거 전달.
  근거가 없다면 검증 미완료로 기록하고 최종 승인자가 위험 수용 여부를 결정.

현재 저장소에는 전용 `application-staging.yml`과 `compose.staging.yaml`이 준비되어 있습니다.
운영 전용 실행 설정/배포 정의는 별도 작성·검토해야 합니다. staging의 강화 설정을 유지하되
실제 자원·Secret·프로젝트는 분리합니다. 기본 프로필로 필수 설정 검증·보안 로그 설정을 누락하지 않습니다.
`ddl-auto=validate`, SQL 초기화 비활성화, 시작 전 스키마 검증을 유지하고 시작 시 스키마 변경을 금지합니다.

### Supabase 담당자 / 팀장

- [ ] 운영 DB 백업 시각·ID·복구 위치·권한·예상 시간·기존 복구 검증 근거 기록.
- [ ] DB 백업과 별도로 Storage 객체·정책 보호 및 복구 방법 확인.
- [ ] 실제 운영 스키마·이력을 읽기 비교하고 필요한 SQL만 개별 변경 승인.
- [ ] 사용자/토큰 RLS, 공지 SELECT, notices·bus_latest_state publication 확인.
- [ ] GPS RPC, active-trip 유일성 제약, 공유 rate-limit RPC, 이미지 버킷 정책 확인.
- [ ] 기존 운영 Edge 및 운영 VAPID 키·구독의 복귀 가능 여부 확인.

저장소에는 seed·데이터 보정·관리자 자격 증명 변경·데모 제거 SQL이 있습니다.
저장소 전체 SQL 적용, `db reset`, 자동 migration repair, 테스트 seed를 금지합니다.
백업 존재만으로 복구 가능을 선언하지 않습니다. 기존 복구 검증 근거를 사용하고,
추가 검증이 필요하면 사용자의 재테스트 생략 결정과 구분해 먼저 승인을 받습니다.

## 3. 환경변수 계약

실제 값은 비밀 관리 도구로 전달하며 아래는 변수 이름만 기록합니다.

| 위치 | 변수 | 운영 원칙 |
|---|---|---|
| Vercel 공개 | `VITE_SUPABASE_PROJECT_ID`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY` | 동일한 기존 운영 프로젝트 |
| Vercel 공개 | `VITE_PUBLIC_API_BASE_URL`, `VITE_AUTH_API_BASE_URL`, `VITE_ADMIN_API_BASE_URL`, `VITE_DRIVER_API_BASE_URL` | 승인된 운영 API, 네 값 모두 명시 |
| Vercel 공개 | `VITE_NAVER_CLIENT_ID`, `VITE_KAKAO_APP_KEY` | 지도 ID / 카카오 JavaScript 키 |
| Vercel 빌드 | `UNIBUS_DEPLOYMENT_ENV` | 운영 `production`, 스테이징은 계속 `staging` |
| AWS 서버 | `SUPABASE_DB_URL`, `SUPABASE_DB_USERNAME`, `SUPABASE_DB_PASSWORD` | 운영 DB, 검증된 TLS, 비밀 주입 |
| AWS 서버 | `SUPABASE_API_URL`, `SUPABASE_SERVICE_ROLE_KEY` | 동일 운영 Supabase, service-role은 서버만 |
| AWS 서버 | `NAVER_CLIENT_ID`, `NAVER_SECRET_KEY` | 서버 API용 키, 프런트 지도 키와 구분 |
| AWS 서버 | `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`, `PUSH_ALLOWED_HOSTS` | 기존 운영 구독과 키 연속성 |
| AWS 서버 | `APP_CORS_ALLOWED_ORIGINS`, `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | 정확한 운영 Origin, patterns 기본 비움 |
| AWS 서버 | `KAKAO_USER_INFO_URL` | 기본 공식 API, 불필요한 override 금지 |
| AWS 실행 | DB 풀·타임아웃·`UNIBUS_LOG_*` | 자원 예산·INFO·비밀 로그 차단 |

DB 비밀번호·service-role·VAPID private key·AWS 자격 증명은 Vercel에 넣지 않습니다.
운영 비밀값을 채팅·문서·Git·명령 인자·빌드 이미지에 넣지 않습니다.
프런트 설정은 빌드에 포함되므로 변경 후 새 빌드가 필요합니다.
빌드 검증기는 필수 값·URL·localhost를 검사하지만 **운영/스테이징 프로젝트 일치를 보장하지 않습니다**.
담당자가 네 API 주소와 Supabase ID/URL/anon key의 환경 일치를 별도 확인해야 합니다.
스테이징 비밀값을 운영으로 복사하지 않습니다. 운영 VAPID 키 교체는 기존 구독 재등록이 필요할 수 있으므로
API 이전과 키 교체를 묶지 않습니다.

## 4. 승인 후 배포 순서

1. 팀장이 대상·백업·복귀 경로·미검증 위험·진행 시각을 확인하고 명시적으로 승인.
2. 필요한 최소 스키마 차이가 있다면 먼저 별도 승인. 구/신 백엔드에 호환되는 변경만 담당자가 적용.
   비호환 변경은 동일 전환에 섞지 않음.
3. AWS 담당자가 운영 전용 서비스에 승인된 이미지 digest·운영 설정을 주입.
   프런트는 기존 운영 API 유지. 스키마 검증·readiness 실패 시 중단.
4. 운영 프런트 Origin CORS·모니터링·알람 확인.
5. 프런트 담당자가 운영 설정으로 새 빌드 생성. 승인된 프로젝트·브랜치·배포 ID를 확인한 후
   전환 시점에 API 주소 네 개를 함께 변경. 문서 자체는 배포/도메인 이동 명령 실행 승인이 아님.
6. 전환 직후 최소 읽기 관찰: health·목록 조회·브라우저 API Origin·오류 로그 확인.
   전체 E2E 재실행은 아님. 계정 생성·공지·Push·GPS 쓰기는 별도 승인 없이 금지.
7. 초기 10분 집중 관찰 후 1시간/다음 날 확인. 이전 프런트·Edge·이미지는 보존.

DNS 변경이 꼭 필요하면 기존 값·새 값·TTL·복귀 절차를 별도 승인받습니다.
가비아 DNS·네임서버를 임의로 바꾸지 않고 기존 Edge를 먼저 삭제하지 않습니다.
기존 설치형 PWA의 캐시/서비스워커로 구 프런트가 남을 수 있으므로 전환·복귀 관찰에 포함합니다.

## 5. 성공·중단 기준

아래는 제안값입니다. 전환 전에 팀장과 AWS 담당자가 기존 지표에 맞게 확정합니다.

| 상황 | 조치 |
|---|---|
| 환경·키 참조 오류, 스키마 검증 실패 | 전환 금지 / 발견 즉시 중단 |
| 개인정보 노출·권한 우회·다른 버스 GPS 오염·이중 운행 | 즉시 중단·롤백·조사 |
| readiness 실패 1분 이상 지속 | 신규 트래픽 중단·롤백 판단 |
| 5xx 1% 이상 5분 지속 또는 주요 로그인/조회 지속 실패 | 롤백 판단; 저트래픽은 개별 실패 확인 |
| p95 기존 기준의 2배 이상 5분 지속 | 풀·DB·자원 점검, 지속 시 롤백 |
| Realtime·이미지 장애·Push 실패 급증 | 기능 영향 기록, 해결 불가 시 롤백 판단 |

반복 재시작이나 자동 DB 변경으로 오류를 가리지 않습니다. 알람 확인자와 롤백 결정자를 지정합니다.

## 6. 롤백

**A. 기존 Edge로 복귀:** 운영 프로젝트에 실제로 배포된 호환 `make-server`를 사전 확인합니다.
이전 정상 프런트 아티팩트 복귀를 우선 사용하고, 새 빌드가 필요하면 보존한 네 API 주소를
기존 운영 Edge API base로 명시적으로 되돌립니다. 비어 있는 환경변수 fallback에 의존하지 않습니다.
Supabase 프로젝트·Storage·VAPID는 유지하고, 기존 Edge의 세션·RPC·DB 계약 호환성 근거를 확인합니다.
기존 Edge가 없거나 호환성이 확인되지 않으면 사용할 수 있는 복귀 경로로 기록하지 않습니다.

**B. 이전 Spring으로 복귀:** 보존한 이전 이미지 digest와 운영 DB 계약의 호환성을 확인해 복귀합니다.
프런트 계약이 달라졌으면 이전 프런트 배포도 함께 복귀합니다.
schema down-migration이나 DB 백업 덮어쓰기를 자동 실행하지 않습니다.

복귀 후 readiness·기본 조회·로그인 영향·API Origin·기존 PWA의 잔존 요청을 확인합니다.
시각·배포 ID·digest·결정자·사용자 영향·데이터 상태를 기록합니다.
A/B 모두 불가능하면 전환을 승인하지 않습니다. 비호환 DB 변경의 복구는 별도 승인된 계획이 필요합니다.

## 7. 승인 기록 양식

```text
릴리스 이름 / 일시(KST): 미정
승인자 / 작업자 / 롤백 결정자: 미정
운영 Vercel Team·Project·Branch·Frontend Origin: 미정
운영 API Origin / AWS 서비스 / Supabase 프로젝트 ID: 미정
서버 SHA / 이미지 digest / 프런트 SHA·배포 ID: 미정
이전 프런트 배포 / Edge URL 또는 이전 Spring digest: 미정
DB 백업 ID·시각 / 복구 근거 / Storage 보호 근거: 미정
필요한 최소 SQL / 별도 변경 승인: 미정
운영 설정 교차 검토자(값은 기록하지 않음): 미정
기존 검증 문서 / 미검증 범위 수용 여부: 미정
중단 기준 / 모니터링 담당 / 관찰 결과: 미정
전환 승인 여부: 미승인
롤백 여부 / 사유 / 결과: 미정
```

검증 근거: [staging-final-verification.md](staging-final-verification.md).
API 이전·카카오 수정: [remaining-edge-and-staging-e2e.md](remaining-edge-and-staging-e2e.md).
이번 준비에서는 사용자의 요청에 따라 전체 재테스트를 수행하지 않았습니다.
