# 스테이징 최종 확인 3항목 결과

검증일: 2026-10-05. 작업공간: `unibus-sch-spring-migration`.
작업 브랜치: `codex/spring-migration`. 운영 프로젝트·AWS·DNS·DB 스키마는 변경하지 않았습니다.

## 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| notices publication | 통과 | 읽기 전용 JDBC 트랜잭션으로 `pg_publication_tables` 조회. `supabase_realtime`에 `public.notices`와 `public.bus_latest_state` 포함 |
| 실제 단말 Push | 통과 | Apple 구독 1건 등록 확인 후 발송: attempted=1, sent=1, failed=0. 사용자가 아이폰 알림 실제 수신 확인 |
| Edge 호출 제거 | 검사 범위 내 통과 | 실제 배포 프런트의 브라우저 관찰 리소스 목록에서 아래 API 경로 11종은 모두 Spring 주소 사용. `/functions/v1/make-server` 관찰 0건 |

스테이징 Supabase는 `srxzkpdtxmqrtcxgpeyl`, 프런트는 `https://unibus-sch.com`,
API는 `https://api-staging.unibus-sch.com`입니다. DB는 `sslmode=verify-full`과 공급자 CA로 연결하고
읽기 전용 트랜잭션을 롤백했습니다. 비밀번호·원본 세션 토큰·Push endpoint·구독 키는 기록하지 않았습니다.

`notices`의 RLS 활성화와 anon SELECT 권한도 확인했습니다. 설정 변경 없이 익명 키로 Realtime을 구독하고,
아래 테스트 공지의 실제 INSERT 이벤트 ID가 Spring 응답의 공지 ID와 일치하는 것을 확인했습니다.
이 결과는 Supabase 전송 검증이며, 프런트의 읽지 않은 공지 배지 동작까지 검증한 것은 아닙니다.

## 단말 및 공지 증거

- 최초 활성 Push 구독 0건 → 사용자가 홈 화면 앱에서 알림을 허용한 뒤 활성 구독 1건, Apple 구독 1건.
- 해당 시점에는 다른 활성 구독이 없었으며, 테스트 알림 1건만 발송했습니다.
- 공지 ID: `8fa1dcae-b09e-4718-a922-16cba858f4b2`.
- 제목: `[E2E] 아이폰 Push 수신 확인 2026-10-05T12:35:30.157Z`.
- 관리자 화면 최근 푸시 발송 기록: `1 / 1 성공`.
- 실제 수신 결과: 사용자가 “알림 받았어”라고 확인.
- 스크린샷: Git 제외 경로 `backend/build/ui-verification/staging-final-push.jpg`.

검증 공지와 발송 이력은 보존했습니다. 기존 사용자·토큰·공지·구독은 삭제하지 않았습니다.
발송 도구가 직접 만든 관리자 로그인 세션은 `/auth/logout`으로 해제했습니다.
브라우저 로그인으로 만든 테스트 관리자·기사 세션의 로그아웃 확인창은 자동화가 처리하지 못해
로그아웃 완료를 확인하지 못했습니다. 사용자 직접 로그아웃 또는 별도의 정확한 세션 식별 후 정리가 필요합니다.
기존 토큰 일괄 삭제로 해결하지 않습니다.

## 실제 브라우저 확인 범위

기사 로그인과 배정 버스·상태 조회, 홈, 학내순환 지도, 통학, 공지, 프로필,
관리자 로그인·운영 센터·공지/알림 조회를 확인했습니다.
요청 주소는 브라우저의 현재 페이지에서 관찰된 resource 목록을 통해 수집했습니다.
Chrome DevTools HAR를 저장하거나 요청·응답 헤더/토큰을 내보내지 않았습니다.

관찰한 경로는 모두 `https://api-staging.unibus-sch.com`을 사용했습니다.

```text
/auth/login
/buses
/buses/locations/latest
/campus/path
/driver/buses
/driver/status
/notices
/notifications/history
/reports
/routes
/users
```

이번에는 브라우저에서 문의 제출, 공지 CRUD·이미지 업로드, 기사 운행 시작·GPS·강제 종료를
다시 실행하지 않았습니다. 소셜 로그인·실제 단말 GPS의 전체 경로도 포함하지 않습니다.
따라서 관찰 0건은 위 로그인·조회 흐름에 대한 결과이며, 모든 가능한 사용자 행동의 무호출 증명은 아닙니다.
발송 요청은 브라우저가 아닌 스테이징 전용 도구에서 Spring `/notifications/send`로 실행했습니다.

이 문서는 요청된 최종 확인 3항목의 증거입니다. 운영 전환 승인이나 전체 미검증 기능의 완료 선언을 대신하지 않습니다.
