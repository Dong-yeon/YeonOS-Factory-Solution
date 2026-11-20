# QA 2026-10-24 — 협력사 포털(플랫폼·셸·SRM 업무) · 반품 출고 이상 사용

> 독립 QA 세션(docs/29 8장). 기준 main `919b029d`(V167 — 포털 #425·#436·#444, 반품 출고 #414·#425). 제품 코드는 고치지 않는다.
> 표시: **[코드]** · **[재현]** · **[기록]**(docs/testing/qa.md).
> 시험(`src/test/java/io/smarthappy/qa/`):
> - `PortalBoundaryQaTest` 7 — 통과
> - `PortalMapperGuardQaTest` 4 — 통과 1 · 재현 `@Disabled` 3
> - `ReturnIssueQaTest` 5 — 통과 4 · 재현 `@Disabled` 1

## 0. 요약

| ID | 구분 | 내용 | 조치 |
|---|---|---|---|
| QR-1 | 결함(작음) | 반품 출고를 같은 요청 id 로 동시에 두 번 보내면 원장은 한 번만 남는다. 그런데 늦은 쪽은 "처음 결과"가 아니라 409 `SRM_STATE` 를 받아, 실제로 나간 출고를 사용자가 실패로 본다 | **WMS** `@Disabled` 재현 |
| PG-1 | 가드 빈틈 | 포털 매퍼 가드는 `id='…'`(작은따옴표) 문장을 아예 보지 않는다 | **상위(가드)** `@Disabled` 재현 |
| PG-2 | 가드 빈틈 | `#{custId}` 가 `<if>` 안에만 있어도 통과한다 — 분기를 안 타면 협력사 조건이 빠진다 | **상위(가드)** |
| PG-3 | 가드 빈틈 | 가드 주석은 "자바 패키지 module.srm.portal" 도 본다고 하지만 검사기는 .xml 만 읽는다 — `@Select` 주석 매퍼는 가드 밖 | **상위(가드)** |
| — | 확인 | 아래 1·3장의 경계·위조·우회 시도는 모두 막혔다 | 이상 없음 |

PG-1~3 의 모양은 지금 포털 매퍼(`PortalSrmMapper.xml`)에 **없다** [코드]. 앞으로 들어올 문장을 가드가 막는지의 문제다.

## 1. 포털 경계 — 실제 로그인 세션으로 [재현] `PortalBoundaryQaTest`

모듈 시험(`PortalSrmTest` 등)은 인증 객체를 바로 끼운다. 여기서는 `/login` 으로 실제 세션을 만들어, 요청 필터(계정·협력사 유효 확인, MFA, 비밀번호 변경 강제)를 지나게 했다.

| 해 본 것 | 결과 |
|---|---|
| 협력사 세션으로 회사 체인 **핸들러 1,136개 전수**(포털·허용 목록·POP·운영자·`/ext` 체인 제외, 경로 변수 = 1, CSRF 포함) | API 403 `PORTAL_FORBIDDEN`, 화면 302 `/portal` — 예외 0. 거부 뒤에도 세션은 살아 있고 포털은 200 |
| 회사 관리자(모든 화면 권한) 세션으로 포털 API·화면 전수 | API 403. 화면은 관리자 MFA 등록 강제(302 `/mfa`)가 먼저 — 포털 화면은 안 열림 |
| 경로 변형 13종(끝 슬래시·대소문자·`..`·`%2e%2e`·`;x=1`·`//`·`%73`·`/api/portalx`·질의 문자열에 포털 경로) | 200·500 없음(방화벽 400·404·403) |
| 협력사 강제 비밀번호 변경(`/api/platform/password-change`) → 포털 비밀번호 변경(`/api/portal/me/password`) 뒤 | 계속 협력사: 포털 200, 내부 403 `PORTAL_FORBIDDEN` |
| 회사 사용자 언어 변경(`/api/platform/my-info/locale`) 뒤 | 계속 회사: 포털 403, 내부 200 |
| 협력사가 회사 언어 API | 403 — **포털에는 언어 바꾸기가 없다**(관찰, 허용 목록 밖) |
| CSRF 없는 포털 ASN 만들기 | 403, ASN 0건 |
| 소속 협력사 사용 중지(`mst_cust.use_yn = 'N'`) | 다음 요청부터 401(요청 필터 `countActive` 가 협력사 유효도 본다) |
| ASN 본문에 `custId`·`custCd` = B 를 넣음 | 무시하고 A 소속으로 만든다(서비스가 `PortalUser.current()` 로만) |
| 내 발주 회신 본문에 B 발주 행 / B 발주 id 로 회신 | 4xx·회신 0건 / 404 |

**[코드]** 세션 유형이 바뀌는 길은 없다.
- `sys_user.user_type`·`cust_id` 는 트리거(`ck_sys_user_type_fixed`)가 고정한다.
- 세션 갱신(`withLocale`·`withPwdChangeRequired`)은 유형·소속을 복사한다.
- 거래 정지(`trade_status = 'BLOCKED'`)는 "조회만, 새 입력 409, 취소는 받음" 이고 모듈 시험이 본다(`PortalSrmTest#거래_정지면…`).

## 2. 포털 매퍼 가드 빈틈 [재현] `PortalMapperGuardQaTest`

가드의 검사기 `PortalMapperGuardTest.violations` 를 그대로 불러 임시 매퍼에 돌렸다.

| 모양 | 가드 결과 | 위험 |
|---|---|---|
| `<select id="bad">… po_id = #{poId}</select>`(대조) | 잡음 | — |
| PG-1 `<select id='bad'>… po_id = #{poId}</select>` | **안 잡음**(문장 정규식이 `id="…"` 만 본다) | XML 로는 맞는 문장 — 협력사 조건 없이 통과 |
| PG-2 `… po_id = #{poId} <if test="custId != null"> and cust_id = #{custId}</if>` | **안 잡음** | 내부·포털 겸용 매퍼를 만들 때 자연스럽게 나오는 모양. null 이면 남의 행 |
| PG-3 `module/srm/portal/PortalQaMapper.java` 의 `@Select("… po_id = #{poId}")` | **안 잡음**(.xml 만 읽는다, 폴더도 `mapper/srm/portal` 고정) | 주석 매퍼·다른 모듈 포털 매퍼(`mapper/qms/portal` 등)가 가드 밖 |

**제안(상위·가드):**
1. 문장 정규식이 속성 따옴표 둘 다를 받게 한다.
2. `#{custId}` 가 `<if>`·`<choose>` 밖(문장 바로 아래)에 있어야 한다.
3. `src/main/java/**/portal/**` 의 `@Select/@Update/@Insert/@Delete` 문자열도 같은 규칙으로 검사한다.
4. 대상 폴더를 `mapper/**/portal/**` 로 넓힌다.

세 `@Disabled` 를 풀면 수정 확인이 된다.

## 3. 반품 출고 RTN_SUP [재현] `ReturnIssueQaTest`

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| **C** 같은 요청 id 로 출고 두 번 동시(반품 행을 다른 트랜잭션이 잡은 동안 둘 다 대기 확인 후) | 원장 1행·재고 40→30·SHIPPED 는 맞다. 응답은 **[409 `SRM_STATE`, 200]** | **QR-1** |
| **C** 두 사람이 같은 출고 원장 행을 동시에 역분개(둘 다 대기 확인 후) | 200 하나·4xx 하나, 반품 CONFIRMED, 재고 원복 한 번 | — |
| 역분개 왕복 두 번(출고→되돌림→출고→되돌림) | 매번 SHIPPED↔CONFIRMED·재고 30↔40, 원장 4행 / 역분개 행을 다시 역분개 → 4xx | — |
| 앱 계정으로 커밋 검사 우회: `SET CONSTRAINTS ALL IMMEDIATE` 뒤 직접 SHIPPED | 그 자리에서 `ck_srm_return_shipped_ledger` | — |
| 앱 계정 `session_replication_role = replica` · `ALTER TABLE srm_return DISABLE TRIGGER ALL/…` | 42501(권한 없음) | — |

**QR-1 [코드]** `ReturnIssueService.issue`
- 같은 요청 id 확인(`mapper.findReq(req)`)을 게이트보다 먼저 하는데, 동시에 두 번이면 둘 다 "아직 없음" 으로 지나간다.
- 늦은 쪽은 `ReturnShipGate.ship` 에서 반품 행 잠금을 기다린다. 앞의 것이 커밋하면 SHIPPED 를 보고 409 `SRM_STATE` 가 된다.
- 데이터는 안전하다(한 번만 나감). 화면만 틀린 결과를 보인다 — 실제로 나간 출고를 "지금 상태에서는 할 수 없습니다" 로 알린다.

PQ-1 때 PDA 이동은 같은 경우를 `replayMove`(새 트랜잭션에서 다시 불러 같은 내용이면 처음 결과)로 풀었다. 반품 출고도 게이트에서 `SRM_STATE` 가 났을 때 같은 요청 id 원장이 생겼으면 `postChecked` 로 처음 결과를 돌려주는 방식이 맞다(제안).
