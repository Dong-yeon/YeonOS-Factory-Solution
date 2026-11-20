# 04 Doubly(Dubly) 분석 — SmartHappy 재사용 후보

- 작성일: 2026-10-15 (백그라운드 조사 세션)
- 대상: 개인 프로젝트 Doubly 저장소 — 기준 커밋 `d55e37a4`(main, 커밋 2,441개). **읽기 전용으로 분석했고 Doubly·SmartHappy 코드와 기존 문서는 수정하지 않았다.**
- 범위: 백엔드(인증·오류·동시성·스케줄·알림·업로드·관측), DB·테스트·CI·배포, 프론트(API 래퍼·오프라인·실시간·UI 관례), AI 하네스, 문서·작업 규약.
- 비밀값·접속정보·호스트명은 옮기지 않았다(`secrets/` 폴더는 `.gitignore` 대상임만 확인).
- 표기: **[그대로]** 개념·코드 형태를 거의 그대로 / **[변형]** SmartHappy 스택(Boot 4.1·MyBatis·PostgreSQL RLS·세션·Kendo)에 맞게 바꿔야 함 / **[확인됨]** 원래 설계(docs/01)에 이미 있고 Doubly 경험이 근거를 보탬.
- 이 문서는 제안이다. 반영 여부는 원래 세션이 사용자와 정하고, 반영 시 docs/00·01 → 코드 순서를 따른다.

---

## 1. Doubly 개요

| 구분 | 내용 |
|---|---|
| 제품 | 커플 운동·식단·여행·채팅 앱 + 트레이너 플랫폼. 1인 개발, 운영 중(스토어 출시) |
| 백엔드 | Spring Boot 3.4 / Java 21 / JPA(Hibernate) / PostgreSQL / Flyway(V1~V143 + Java 마이그레이션 1개) / Redis(레이트리밋·사용량 카운터, 없으면 인메모리 폴백) / STOMP WebSocket / JWT(access 30분 + refresh 14일, 회전) |
| 프론트 | Expo(React Native + TypeScript), Zustand, fetch 래퍼. 테스트 러너 없이 `scripts/verify-*.mjs` 로 순수 로직 검증 |
| 기타 | Python worker(`worker/spot-diff`, `FOR UPDATE SKIP LOCKED` 작업 큐), 정적 랜딩 사이트(`landing/status.json` 장애 공지), Gemini AI |
| 배포 | Railway(Docker), EAS Build/Update. CI = GitHub Actions(백엔드 테스트·tsc·웹 번들) |
| 데이터 분리 | 테넌트 개념 없음. 모든 "둘의 것"은 `relations` 1행에 스코프(`relation_id`). 서비스마다 소유 검사(중앙 가드 없음) |
| 규모 | 백엔드 테스트 약 1,300건(`@SpringBootTest` 129클래스, H2 + 운영과 같은 Flyway) |

구조는 패키지 by feature(`com.fitto.<feature>/controller|domain|dto|repository|service`) + 횡단 관심사 `common/`. SmartHappy 의 `platform`(HappyBoot) / `module` 구분과 같은 발상이다.

---

## 2. 가져올 것

우선순위: **A = P0 지금(3~7단계)에 넣을 가치가 큼**, B = 해당 모듈 착수 때, C = 참고.

### 2.1 가드 테스트 — "규칙을 사람 기억이 아니라 테스트가 지킨다" (A)

Doubly 에서 가장 효과가 컸던 습관이다. SmartHappy 에는 이미 `TenantCatalogGuard`(DB 카탈로그 검사)가 있으니 같은 계열을 소스·마이그레이션 쪽으로 넓히는 것.

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `backend/src/test/java/com/fitto/common/time/KstClockGuardTest.java` | ① 테스트 JVM 시간대가 고정됐는지 단언 ② `src/main/java` 를 줄 단위로 훑어 존 없는 `LocalDate.now()` 가 있으면 `파일:줄` 목록으로 실패. 주석 줄 제외. 운영 버그 4건을 잡음 | 2.9 의 "업무일 기준 시계"와 짝. `LocalDate.now()`·`LocalDateTime.now()`(존 없음) 금지 가드를 P0 5단계(원장 지원)와 함께 | [그대로] |
| `backend/src/test/java/com/fitto/relation/RestorerCoverageGuardTest.java` | Flyway SQL 을 정규식으로 훑어 `references relations(` 가 있는 테이블을 모으고, 각 테이블이 Restorer 소스에 등장하는지 확인. 예외는 `EXEMPT` 에 **이유 주석과 함께**. 같은 사고가 3번 반복된 뒤 생김 | **회사 단위 처리 목록 커버리지 가드**: `comp_cd` 를 가진 모든 테이블이 (a) 보존 클래스(`sys_retention_class` 매핑) (b) 회사 해지 시 반출·삭제 목록 (c) 감사 대상 여부 표에 등록됐는지 검사. SmartHappy 는 실제 PG 가 있으므로 정규식 대신 **카탈로그(`information_schema.columns where column_name='comp_cd'`)로** 구하는 편이 정확 | [변형] |
| `PlanFeatureSyncTest`, `PolicyVersionSyncTest`, `ClientAnalyticsEventSyncTest` 등 8종 + `backend/build.gradle` 의 `inputs.files(...).withPropertyName('frontendSyncSources')` | 백엔드 테스트가 프론트 소스를 텍스트로 읽어 서버 enum 과 대조. 프론트 파일을 Gradle test 입력으로 등록해 **프론트만 고쳐도 테스트가 다시 돈다**(안 하면 UP-TO-DATE 로 건너뜀) | ① 오류 코드 enum ↔ `js/common/hb.api` 의 분기 코드 ② `Action` enum(SEARCH/SAVE/…) ↔ 화면 JS·템플릿 ③ **i18n 키 동기화**: `messages_ko.properties` 의 키가 `messages_en/vi` 에 모두 있는지, 템플릿·JS 가 쓰는 키가 ko 에 있는지 ④ `help_key` 형식. P0 4단계(i18n·셸) | [변형] |
| `common/security/SecurityErrorResponseTest` | 필터 단계(인증 전) 401/403 도 컨트롤러 오류와 **같은 봉투·한국어 문구**로 나가는지 회귀 테스트 | P0 3단계 지금 해당: `ApiSecurityResponses` 가 `{"result":"ERROR",…}` 형식을 지키는지 테스트. 이미 `ApiErrorMappingTest` 가 있다면 필터 경로 케이스만 추가 | [그대로] |
| `common/config/SchedulingConfig` + `SchedulingTriggerTest` | `@EnableScheduling` 을 `fitto.scheduling.enabled` 조건부 내부 클래스로 분리 → 테스트에선 자동 트리거 끄고 job 메서드를 직접 호출. 트리거가 꺼졌는지 자체를 테스트 | P0 6·7단계(정리 배치·아웃박스 릴레이). `happyboot.scheduling.enabled=false`(test) + 직접 호출 테스트 | [그대로] |

> 마이그레이션 번호 중복 가드도 싸다: `db/migration` 의 `V###` 중복을 검사하는 단위 테스트(또는 `./gradlew check` 태스크). Flyway 도 기동 시 잡지만, Doubly 는 병렬 세션이 같은 번호를 집어 **테스트 671건 중 503건이 컨텍스트 로드에서** 죽었다. SmartHappy 도 지금 병렬 세션을 쓰고 있다.

### 2.2 오류 코드 설계 (A — P0 3단계)

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `common/exception/ErrorCode.java`(약 115~150개), `BusinessException`, `GlobalExceptionHandler` | 코드 enum 하나가 **HTTP 상태 + 사용자 문구**를 함께 가짐. 응답 `errorCode = code.name()`. 상태 선택 근거를 enum 주석에 남김(예: 재설정 코드의 "틀림/만료"를 한 코드로 합쳐 탐색 단서 제거, 결제로 풀리지 않는 한도는 402 대신 429) | 지금 `TENANT_FORBIDDEN/TENANT_REQUIRED/DB_ERROR` 를 enum `HbErrorCode(status, messageKey)` 로 모은다. **문구는 한국어 리터럴이 아니라 i18n 메시지 키**(P0 4단계 결정과 맞춤) | [변형] |
| 같은 핸들러 | 400 계열 매핑: 본문 파싱 실패, `MethodArgumentTypeMismatch`(예전엔 500 → `ParamTypeMismatchTest` 로 회귀 방지), 필수 헤더 누락, 405/404. 나머지 `Exception` 은 스택은 로그로만, 응답은 일반 문구 | `ApiExceptionHandler` 에 같은 목록 반영 | [그대로] |
| (Doubly 의 빈틈) | `DataIntegrityViolationException`·`ConstraintViolationException` 전역 매핑이 없어 500 으로 떨어짐 | SmartHappy 는 처음부터: unique 위반(23505) → 409 `DUPLICATE`, FK 위반(23503) → 409 `IN_USE`, check 위반(23514) → 400. **판별은 `PgErrors` 처럼 SQLState·제약 이름으로**(문구 비교 금지 규칙 유지) | [변형] |
| 프론트 `src/utils/error.ts` `getErrorMessage`, `errorCodeOf` | 상태코드가 아니라 **errorCode 로 분기**(같은 503 이 "업로드 미설정"인지 장애인지 구분) | `hb.api` 가 `code` 로 분기 | [그대로] |

### 2.3 로그인·세션 (A — P0 3단계, 일부 이미 반영)

SmartHappy 는 서버 세션(Spring Security 7 폼 로그인)이라 Doubly 의 JWT 구조 자체는 가져오지 않는다(3장). 가져올 것은 방어 세부다.

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `auth/service/AuthService`(timingDummyHash) | 없는 계정에도 BCrypt 1회 → 응답 시간으로 가입 여부 추정 불가 | **이미 반영됨**(`HappyAuthenticationProvider` dummyHash) | [확인됨] |
| `common/security/AuthRateLimiter` | 로그인은 **IP+ID 기준, 실패만 센다**, 성공 시 리셋. 비밀번호 재설정 발송 한도 초과는 **예외 없이 조용히 생략**(계정 열거 방지) | `login_fail_cnt`·`lock_yn` 은 계정 기준이라 **잠금으로 남의 계정을 막는 공격**(ID 만 알면 5번 틀려 잠금)이 가능. IP 기준 시도 제한을 하나 더 두는 것을 검토(Redis 없이 DB 또는 인메모리 Caffeine). 재설정 기능 도입 시 "조용히 생략" 규칙 | [변형] |
| (Doubly 의 빈틈) `AuthController.clientIp()` | `X-Forwarded-For` 첫 값을 그대로 믿어 레이트리밋 키 위조 가능 | 지금 `request.getRemoteAddr()` 는 올바름. **SaaS 에서 리버스 프록시 뒤에 둘 때** `server.forward-headers-strategy` + 신뢰 프록시 지정으로만 바꾸고, 헤더를 직접 파싱하지 않는다(접속 로그 `sys_access_log.ip` 도 같은 값) | [교훈] |
| refresh 회전 + 재사용 감지(`RefreshTokenStore`: GETDEL, 회전 계보 `next:{oldJti}`, 응답 유실 관용, 재사용 시 `revokeAll`) | 토큰 탈취 대응의 교과서적 구현 | **웹엔 불필요.** PDA·`/ext/**` 에 장기 토큰을 줄 경우(P4 WMS) 그때 참조 | C |
| 프론트 `api/client.ts` `isSessionRejected()` | **401/403 일 때만 로그아웃**. 네트워크 끊김(status 0)·타임아웃·5xx(재배포 중 502)는 세션을 지키고 오류만 표시. 예전엔 잠깐 끊겨도 로그인 화면으로 튕김 | `hb.api` 의 401 처리 규칙으로 그대로. 공장 Wi-Fi 음영·POP 에서 특히 중요 | [그대로] |

### 2.4 멱등·중복 저장 방지 (A — P0 7단계 / P2 MES 실적)

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `docs/CHAT_DUPLICATE_SEND_2026-09-12.md`, `chat/service/ChatService.send`, `diet/controller/MealController` | **3겹**: ① 클라이언트가 화면을 연 동안 키 1개(`Date.now36-random8`)를 만들고 재시도에도 같은 키 ② 서버가 저장 전 `(scope, client_request_id)` 사전 조회 → 있으면 **기존 행을 성공으로 반환** ③ unique 인덱스 + `DataIntegrityViolationException` 을 잡아 먼저 저장된 행 반환(동시 도착 경합). NULL 은 unique 에서 서로 달라 백필 불필요 | docs/01 5.7 의 `X-Idempotency-Key` + `sys_inbox` 설계를 보완: (a) 범용 API 는 `sys_inbox` 유지 (b) **POP 실적·PDA 입출고처럼 같은 키로 "그때 만든 행"을 돌려줘야 하는 것은 업무 행에 `client_req_id` 컬럼 + `unique(comp_cd, client_req_id)`** 가 더 단순(inbox 는 "처리했다"만 알고 결과를 모름). 응답은 409 가 아니라 원래 성공 응답 | [변형] |
| 폐기한 대안(같은 문서) | "에코 올 때까지 버튼 비활성"(서버가 죽으면 영구 먹통), "내용+시각으로 중복 판정"(정상적인 같은 값 연속 입력을 막음 — POP 에서 같은 수량 연속 실적은 정상) | 동일하게 채택하지 않는다 | [교훈] |
| `common/currency/CurrencyLedgerEntry`, `CurrencyService`(V140) | append-only 원장 + 잔액은 지갑 테이블 + `idempotency_key` UNIQUE 를 "이미 처리됨"으로 변환. **환불 키는 원 지출 키에서 파생**(`"refund:" + spendKey`) → 같은 건 이중 환불 불가, 원 건 없으면 환불 거부 | P0 5단계 `LedgerSupport.reverse(table, id, reason)`: 역분개 행에 `reversal_of_id` **unique** 를 걸어 "한 행은 한 번만 역분개"를 DB 가 보장(02 6.2-D 와 일치). 재고 원장 + 현재고 요약 테이블 구조가 지갑/원장과 같은 모양 | [변형] |
| 프론트 연타 방지(`savingRef` 동기 잠금 + `saving` 표시 state, 약 25곳), `store/busyStore.runBusy` | state 는 리렌더 전까지 낡아서 같은 프레임 연타를 못 막음 → 동기 플래그 따로. 화면 전체 잠금은 **초 단위 작업만**, 분 단위(AI 등)는 버튼·결과 자리만 잠금. finally 해제 | `hb.ui` 저장 잠금: 버튼 `data-busy` 동기 플래그 + `kendo.ui.progress`. 같은 원칙 | [변형] |

### 2.5 커밋 후 발행·아웃박스 릴레이 (A — P0 7단계)

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `common/event/CoupleEventPublisher` | 트랜잭션이 있으면 `afterCommit` 에서만 발행(롤백된 작업의 유령 이벤트 방지). **이벤트엔 타입만 싣고 받는 쪽이 인증된 REST 로 다시 조회**(민감 데이터가 채널로 새지 않음) | 화면 실시간 갱신(설비 상태판·작업지시 변경, P2/P5)에 같은 규칙. Kendo DataSource `read()` 재조회 | [그대로] |
| 같은 파일 주석 | **함정**: `afterCommit` 콜백 안에서 다시 `registerSynchronization` 하면 Spring 이 실행하지 않아 **조용히 사라진다** | skill `references/` 함정 목록에 추가할 만함 | [교훈] |
| `worker/spot-diff/spot_worker/jobs.py`, `V143__spot_diff.sql` | 작업 큐: `status in (QUEUED) or (RUNNING and locked_at < now() - 15분)` 을 `for update skip locked limit 1` 로 집고 **같은 트랜잭션에서 RUNNING·locked_at·attempts+1 로 바꾼 뒤 커밋** → 외부 처리 → 결과는 `where status='PENDING'` 조건부 UPDATE(그사이 상태가 바뀌었으면 결과 폐기). 재시도 상한은 **집을 때** 검사, 예상 못 한 예외는 QUEUED 로 되돌리고 `error`(500자) 기록 | docs/01 5.8 의 릴레이는 "`for update skip locked` 로 꺼내 처리" 라고만 되어 있다. **외부 호출(ERP·메일)을 행 잠금 트랜잭션 안에서 기다리면 커넥션 풀이 고갈**된다(Doubly `docs/STABILITY_ANALYSIS_2026-09-01.md` 의 실제 장애 원인). 그래서 `sys_outbox` 에 `SENDING` 상태 + `locked_at` + 오래된 SENDING 회수를 넣고, **집기(짧은 트랜잭션) → 송신(트랜잭션 밖) → 결과 기록(짧은 트랜잭션)** 3단으로 설계할 것을 제안 | [변형] |
| `notification/service/ExpoPushNotificationService` | 재시도는 5xx·연결 실패만, **읽기 타임아웃은 재시도하지 않음**(상대가 이미 받았을 수 있음 — 중복이 누락보다 나쁜 채널). 수신 거부 판정은 **발송 직전 한 곳**. 알림 분류는 보내는 도메인이 아니라 사용자 체감 기준(CHAT/ANNIVERSARY/PARTNER/REMINDER) | 아웃박스 소비자의 재시도 정책을 채널별로(ERP 송신은 수신측 멱등키가 있으면 타임아웃도 재시도 가능, 메일·알림은 불가). 알림 수신 설정 판정 단일 지점 | [변형] |

### 2.6 기동 가드·설정 (A — P0 3단계)

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `common/config/JwtSecretGuard` | prod 프로필에서 비밀값이 비었거나 32자 미만이거나 `dev-only`/`change-me` 를 포함하면 **기동 실패** | `DatabaseRoleGuard` 와 같은 철학. prod 에서 ① `.env.example` 의 예시 비밀번호 그대로 ② 세션 쿠키 `Secure` 꺼짐 ③ `happyboot.deploy.mode` 미지정 이면 기동 중단. 예시 값에 `change-me` 표식을 넣어 두면 검사가 쉬움 | [변형] |
| `application.yml` 의 `${ENV:}` 빈 기본값 + `isConfigured()` | 선택 연동(AI·업로드 등)은 키가 없으면 **그 기능만** `AI_NOT_CONFIGURED`(503) 같은 기능 단위 오류. 필수 설정만 기동 실패 | "필수 = 기동 중단 / 선택 연동 = 기능 단위 오류" 2분류를 규칙으로. ERP 연계·메일·AI(P5) | [그대로] |
| `common/HealthController` + Actuator 설정 | 얕은 `/api/v1/health`(항상 UP, 배포 헬스체크용)와 깊은 `/actuator/health`(ADMIN 전용, 진단용) 분리. 깊은 검사를 헬스체크에 쓰면 Redis 가 잠깐 흔들려도 재시작됨 | 설치형·SaaS 모두 liveness/readiness 구분. Actuator 노출 최소화 + 운영자 권한 | [그대로] |
| `application.yml` Hikari `leak-detection-threshold: 20000` | 트랜잭션 안에서 외부 호출을 기다리는 회귀를 로그로 잡음 | 그대로 추가 | [그대로] |

### 2.7 PDA·POP 클라이언트 패턴 (B — P0 4단계 `hb.api`, P2 POP, P4 WMS PDA)

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `frontend/src/api/client.ts` | 기본 타임아웃 10초(`AbortController`), **네트워크 끊김·타임아웃을 `ApiError(status 0, timedOut)` 로 정규화**해 영문 브라우저 메시지가 사용자에게 안 나감. 일반 REST 는 자동 재시도 없음 | `hb.api` 설계에 그대로 | [그대로] |
| `frontend/src/screens/diet/BarcodeScanScreen.tsx` | 한 번 읽으면 사용자가 다음 행동을 고를 때까지 **스캔 잠금**(`paused` + 동기 ref). 결과를 성공 / 찾았으나 정보 부족 / 없음 / 오류로 나누고 각 상태에 큰 버튼으로 다음 행동 제시. 과거 실패 즉시 재스캔 → 진동·토스트 무한 반복 | PDA 스캔 루프 표준. 키보드 웨지 스캐너면 같은 값 연속 입력 무시 + 처리 중 잠금 | [변형] |
| `docs/BARCODE_LOOKUP_2026-09-29.md`, `BarcodeLookupService` | 서버가 **형식(숫자 8~14자리)부터 거절**(QR 오인식 차단). 외부 DB 결과는 조회 키와 **반드시 대조**("틀린 값은 빈 칸보다 해롭다" — 조회 조건이 무시되면 첫 행이 옴) | 라벨 바코드 파서: LOT·품번·수량 형식 검증 → 존재 대조. ERP 조회 결과도 키 대조 | [그대로] |
| `frontend/src/utils/pendingMealPhotos.ts` | **write-ahead 오프라인 대기열**: 전송 전 로컬에 먼저 기록, 같은 `clientRequestId` 로 재전송, 자동 재전송은 **0/5xx 만**(4xx 는 `rejected` 로 표시해 사람이 판단 — 같은 요청엔 같은 답), 목록 쓰기 직렬화, 로그아웃 시 비움 | PDA(웹이면 IndexedDB, 안드로이드 앱이면 로컬 DB) 입출고 대기열. 2.4 멱등키와 짝이어야 안전 | [변형] |
| `frontend/src/api/chatSocket.ts`, `utils/chatSync.ts` | 구독을 "걸려 있어야 하는 목록(desired)"으로 관리해 **재연결 때 전부 다시 구독**(stompjs 는 소켓만 다시 붙임). 재연결 직후 "화면 마지막 id 이후"를 REST 로 따라잡기(상한 300건, 넘으면 통째 재조회). "연결 중…" 띠 | 설비 모니터링·안돈 화면(P2/P5) 실시간 구현 시 | B |
| `frontend/src/hooks/useDirtyGuard.ts` | 작성 중 이탈 가드 한 곳(뒤로·하드웨어 백·스와이프) | Kendo 탭 셸: 수정 중 탭 닫기/메뉴 이동 확인을 셸 공통으로 | [변형] |

### 2.8 장애 공지·최소 버전 (B — P0 9단계 운영자 콘솔 / PDA 앱)

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `landing/status.json`, `frontend/src/utils/serviceStatus.ts`, `store/serviceStatusStore.ts`, `docs/INCIDENT_NOTICE_2026-09-30.md` | 공지는 **백엔드와 별개 정적 호스팅**(공지가 필요한 순간은 대개 백엔드가 죽은 순간). 스키마: `updatedAt`, `notice{level, title, message, startsAt, endsAt}`, `minAppVersion`. 원칙 "**의심스러우면 없는 것으로**" — 형식이 깨진 항목은 버리고, 버전 비교가 안 되면 강제 업데이트 안 함(깨진 값으로 전원 잠금 방지). 네트워크 실패 시 직전 상태 유지, 점검 기간엔 0/5xx 를 점검 문구로 치환 | SaaS: 정적 `status.json`(웹서버 정적 경로 또는 별도 호스트)을 셸·POP·PDA 가 포커스 복귀 때 읽어 점검 배너. PDA 앱 최소 버전. 설치형은 생략 가능 | [변형] |

### 2.9 시간 기준 (A — P0 5단계, 결정 필요)

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `common/time/KstClock` + 2.1 가드 + `build.gradle` `systemProperty 'user.timezone', 'Asia/Seoul'` | "오늘"은 한 곳에서만 판단. 테스트 JVM 시간대 고정(CI 러너는 UTC) — 고정이 가리는 운영 버그는 소스 스캔 가드가 잡음 | SmartHappy 는 더 복잡하다: 회사·공장 시간대(해외 공장), **근무조 경계의 작업일**(예: 08:00~익일 08:00 = 하루). `BizClock`/`WorkDayResolver(plant)` 를 P0 5단계 원장 지원과 함께 두고 `LocalDate.now()` 금지 가드. `timestamptz` 를 이미 쓰고 있어 저장은 안전(아래 4장) | [변형] |

### 2.10 AI 하네스 (B — P5, docs/03 과 정합)

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `docs/AI_HARNESS_DESIGN.md` | 6축 진단표(입력 경계·비용·출력 계약·관측·회귀 평가·정지 조건). 단계: ① 관측(`ai_calls`: feature, model, outcome, attempt, latency, prompt_fingerprint — **원문은 저장 안 함**) ② 출력 계약(`<T> T generateJson(…, Class<T>)` + Bean Validation, 위반 시 필드명 붙여 1회 재요청) ③ 서킷 브레이커·한도 환불 ④ 골든셋 회귀 평가(`@Tag("eval")`, CI 기본 제외, 허용 오차). "MES 처럼 에이전트가 실데이터를 UPDATE 하면 승인 큐가 필요"라고 명시 | docs/03 "AI 는 제안·사람이 확정" 결정의 구현 골격으로 그대로 사용. `ai_calls` 는 회사별 테이블(RLS) + `AI_PII_MASKING` 정책과 연결 | [변형] |
| `common/ai/GeminiClient`, `docs/gemini-food-analysis-404_2026-10-15.md` | JSON schema 강제, 호출 프로필(SYNC 3회·60초 / BACKGROUND 6회·240초 / WAITING), **총 예산형 재시도**, 1차 모델 예산 60% 후 폴백 모델, 폴백 모델은 환경변수로 교체(모델 은퇴로 404 난 사고). **합계는 모델에게 받지 않고 서버가 항목을 더함**(모델이 "0 을 끝없이 찍는" 퇴행) | LLM 이 수량·합계를 내는 기능(도면·성적서 OCR, 보고서 초안)에서 합계·환산은 서버 재계산, 범위·코드값 검증 | [그대로] |
| `common/ai/AiJobService`, `AiResultCache`, `frontend/src/api/aiJob.ts` | 비동기 작업: jobId 즉시 반환 + 폴링(300ms→3s), **유한 큐가 차면 PENDING 이 아니라 즉시 FAILED** + 미리 차감한 한도 환불. 캐시 키 = 입력 문자열 해시(시간 아님), 적중 시 한도 미차감. 2분 넘으면 "실패"가 아니라 "아직 만드는 중" 안내 | P5 AI 작업. 단 Doubly 는 큐가 메모리라 재시작 시 유실 → SmartHappy 는 2.5 의 DB 작업 큐(skip locked) 위에 | [변형] |

### 2.11 순수 JS 로직 + node 검증 스크립트 (B — P0 4단계 이후)

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| `frontend/scripts/verify-*.mjs`, `package.json` `verify:*` | 테스트 러너 없이, DOM·RN 을 import 하지 않는 순수 모듈을 Node 로 불러 표 기반 단언(`eq(name, actual, expected)`), 실패 시 exit code. **정탐 + 오탐 방지 케이스를 함께** | Kendo/jQuery 화면에서 계산 로직(바코드·라벨 파싱, 단위 환산, 수량 검증, 채번 미리보기)을 `js/common/pure/` 같은 순수 모듈로 분리하고 node 스크립트로 검증. `./gradlew check` 에서 node 가 있을 때만 실행하거나 별도 명령 | [변형] |
| `scripts/verify-theme-contrast.mjs`, `eslint-rules/icon-button-a11y-label.js` | 팔레트 WCAG 대비 자동 검증(본문 4.5:1, 아이콘·경계 3:1), 기호만 있는 버튼에 라벨 요구(warn) | POP 터치 화면 테마(밝은 현장·원거리 판독) 대비 검증. 우선순위 낮음 | C |

### 2.12 문서·작업 규약 (A — 지금)

| Doubly 위치 | 내용 | SmartHappy 적용 | 구분 |
|---|---|---|---|
| Doubly `CLAUDE.md` §7 "Flyway 마이그레이션" | 번호는 **최신 origin/main 기준, SQL·Java 마이그레이션을 함께** 세고, 미병합 브랜치의 선점 번호도 확인, 병합 후 `uniq -d` 중복 검사. 충돌 시 **아직 푸시 안 된 쪽**을 옮김. 커밋 제목에 `(V097)` 번호 표기 | SmartHappy 도 병렬 세션을 쓰므로 skill `checklists.md` 에 같은 절차 추가 + 2.1 중복 가드 | [그대로] |
| `docs/*-current-state.md`(10개), `docs/server-stability-current-state.md` | 영역별 읽기 전용 스냅샷: 머리말에 **기준 커밋**, "코드는 읽기만", 추정은 **(추정)/미확인** 표기, 위치는 `파일:줄`, 후속 결과는 같은 문서에 날짜 절로 덧붙임 | SmartHappy 의 "미확인 표기" 규칙과 같음. 백그라운드 분석 문서(02~04)에 **기준 커밋 표기**를 규칙으로 추가할 만함 | [그대로] |
| Doubly `CLAUDE.md` §5 | "결론은 세션이 아니라 저장소에", 병렬 작업은 각자 워크트리(주 워크트리 동시 편집으로 미커밋분 유실 이력) | SmartHappy 는 이미 docs 번호 체계로 실천 중. 워크트리 규칙은 병렬 **코드** 세션을 돌릴 때 적용 | [확인됨] |
| `docs/QA_CHECKLIST.md`(49개 화면 감사) | ✅/⚠️/❌ = "코드에 방어 로직이 있나", ☐ = 실기기 확인, [확정]/(추정) 구분, "전역 반복 패턴 — 한 번에 고칠 것" 절 | `TESTING.md` 에 화면 단위 감사 표 형식으로(P0 8단계 거래처관리부터) | [변형] |

---

## 3. 가져오지 말 것과 이유

| 항목 (Doubly 위치) | 이유 |
|---|---|
| **H2 + "PostgreSQL 전용 문법 금지"** (`application-test.yml`, CLAUDE.md §4) | SmartHappy 는 Testcontainers 실제 PG 에서 RLS 까지 검증한다. Doubly 는 H2 때문에 `JSONB`·`ON CONFLICT`·`SKIP LOCKED` 를 못 쓰고, `:param is null` 이 운영에서만 500 이 나는 사고를 겪었다 → **오히려 SmartHappy 방식이 옳다는 근거**. `ON CONFLICT` 채번(5.2)도 그대로 유지 |
| **JWT 를 웹 화면 인증에** (`JwtTokenProvider`, `RefreshTokenStore`) | 모바일 앱이라 택한 구조. SmartHappy 웹은 서버 세션 + CSRF 가 단순·안전. 토큰은 PDA·`/ext/**` 에서만 필요할 때 검토(2.3) |
| **Redis 의존 + fail-open** (레이트리밋·refresh 재사용 감지·사용량 카운터) | Redis 장애 시 보안 검사가 통째로 꺼진다. SmartHappy 는 Redis 를 두지 않는 단일 DB 구조이고, 설치형 고객 인프라를 늘리지 않는다. 필요하면 DB 또는 인메모리로 |
| **인메모리 작업 큐·푸시 큐** (`AiJobService` 4스레드·큐 100, 푸시 afterCommit 스레드풀) | 재시작·재배포 때 유실(Doubly 문서가 인정한 1순위 장애 원인이 재배포). SmartHappy 는 아웃박스(DB)로 |
| **중앙 가드 없는 데이터 스코프** (서비스마다 `activeCouple()` private 헬퍼 20여 개 복제, 인가 누락 정적 검사 없음) | SmartHappy 는 RLS + `@ProgAuth` + ArchUnit 으로 이미 더 강하다. 반면교사 |
| **JPA 엔티티·`@CreatedDate` 감사** (`BaseTimeEntity`) | MyBatis 스택. 감사 컬럼은 P0 계획대로 MyBatis 인터셉터·파라미터 주입 |
| **`timestamp`(without time zone) + JVM UTC 고정** | 이 때문에 V119 를 Java 마이그레이션으로 짜야 했다(벽시계 값이 어느 시간대인지 몰라서). SmartHappy 는 `timestamptz` 를 이미 쓰고 있으니 유지 |
| **PlanGuard·402 업셀·크레딧** (`common/plan/PlanGuard`) | B2C 결제 모델. SaaS 요금제 한도(사용자 수·공장 수)는 훗날 `sys_comp.plan_cd` 로 단순히. 다만 "**모든 한도 판정이 한 관문을 지나고, 그 관문이 사용량 계측도 겸한다**"는 발상은 운영자 콘솔(P0 9단계) 지표 설계 때 참고 |
| **클라이언트 직접 업로드(Cloudinary 서명·R2 presign)** | 이미지 서버 우회가 목적. SmartHappy 첨부는 권한 확인·`comp_cd` 키·감사가 필요한 품질 문서라 `FileStorage` 경유가 맞다. 대용량 SaaS 에서 presign 을 쓰더라도 **Doubly 처럼 서버 크기 제한이 없는 서명은 금지**(빈틈) |
| **PR 없이 main 직접 병합·즉시 push 규칙** | SmartHappy 는 "커밋·push 는 사용자 요청 시만" 규칙이 우선. 단 "병합 전 `origin/main..main` 확인"은 유용 |
| Expo/EAS fingerprint·OTA, 맞춤법 엔진, 게임·스티커, 카카오맵 등 | 도메인·플랫폼 특화 |
| 단일 인스턴스 가정 스케줄러(ShedLock 없음) | SaaS 에서 인스턴스를 2개 이상 띄우면 배치가 중복 실행된다. 5장 결정 사항 참고 |

---

## 4. Doubly 에서 발견한 함정·교훈 중 SmartHappy 에 적용할 것

skill `SKILL.md §5` 색인 후보. (출처는 Doubly docs·CLAUDE.md·커밋 메시지)

| # | 함정 / 교훈 | 출처 | SmartHappy 적용 |
|---|---|---|---|
| 1 | **외부 호출을 DB 트랜잭션 안에서 기다리면 커넥션 풀 고갈** — AI 호출이 원인이었음 | `docs/STABILITY_ANALYSIS_2026-09-01.md` | 아웃박스 릴레이 3단 설계(2.5), Hikari leak-detection. `TenantRunner.run` 안에서 ERP 호출 금지를 규칙으로 |
| 2 | **클라이언트 타임아웃 = 서버 타임아웃이면 경합** (60초=60초) | 같은 문서 | `hb.api` 기본 타임아웃 < 서버 처리 한도, 긴 작업은 비동기 작업 + 폴링 |
| 3 | **"단독은 통과, 전체에서만 실패" = 캐시된 컨텍스트마다 살아 있는 `@Scheduled` 스레드**가 공유 DB 를 건드림. `@DirtiesContext` 는 스레드만 하나 더 늘림. 테스트만 고치지 말고 운영 다중 인스턴스 결함으로 보고 **조건부 UPDATE 선점·멱등키**를 함께 | `docs/SCHEDULER_TEST_FLAKE_2026-09-15.md` | test 프로필에서 스케줄 끄기(2.1), 배치 작업은 처음부터 조건부 선점 |
| 4 | **`@SpringBootTest` 의 properties·`@MockitoBean` 조합마다 컨텍스트가 하나 더 생겨 JVM 끝까지 캐시** → 9/22 CI OOM. 힙보다 조합 가짓수를 먼저 본다 | `backend/build.gradle` 주석 | SmartHappy 는 Testcontainers 라 컨텍스트마다 Hikari 풀까지 잡힘. **공통 테스트 베이스 1개로 조합을 고정**하는 것을 TESTING.md 규칙으로. (Doubly 를 PG 로 전체 실행하면 `too many clients` → `max_connections` 상향 필요했음) |
| 5 | **CI 만, 특정 시각에만 빨갛다 → 실행 시각부터 본다**(UTC 러너 vs KST 정의) | `docs/CI_TIMEZONE_FAILURE_2026-09-15.md` | 테스트 JVM 시간대 고정 + 존 없는 now() 금지 가드(2.9) |
| 6 | **병렬 세션 + 푸시 지연 = Flyway 번호 의미 충돌**(파일명이 달라 git 은 충돌로 안 잡음). V89 중복으로 테스트 503건 사망 | Doubly `CLAUDE.md` §1·§7, `docs/MERGE_ORIGIN_MAIN_2026-10-11.md` | 2.12 번호 절차 + 중복 가드 |
| 7 | **공유 자원은 소스만이 아니다** — 주 워크트리 `build/`, Gradle 데몬(다른 세션의 `--stop`), 고아 테스트 JVM 파일 잠금, 남의 `git add -A` 에 섞여 들어간 미커밋 편집. "NoSuchBeanDefinition 무더기 실패"를 코드 결함으로 오독하지 말고 단독 재실행 | Doubly `docs/SESSION_DIGEST.md`, 사용자 메모리 | 병렬 세션 운영 시 TESTING.md 주의 문구 |
| 8 | **테이블 목록 동기화를 사람에게 맡기면 반복해서 깨진다**(Restorer 3회 재발 → 가드로 해결, Purger·ExportSection 은 아직 수동) | `RestorerCoverageGuardTest` 주석 | 2.1 회사 단위 커버리지 가드 |
| 9 | **헬스체크·배포 트리거는 콘솔 설정을 직접 확인** — 주석엔 healthcheck 가 있다고 했는데 실제 설정은 비어 있었음, watch path 가 없어 문서 푸시만으로 재배포(하루 3번 502) | `docs/RAILWAY_DEPLOY_SETTINGS_2026-09-30.md` | SaaS 배포 체크리스트(P0 9단계 이후) |
| 10 | **배포 성공 ≠ 전달** — OTA 가 일치하는 빌드가 없으면 0명에게 가는데 오류도 없음 | `docs/RELEASE_OTA_MISDELIVERY_2026-09-11.md` | 설치형 패치 배포 시 "적용 확인"(버전 엔드포인트 조회)까지를 배포 절차에 |
| 11 | **관측이 추측을 이긴다** — 코드만 읽고 AI 장애 원인 순위를 틀림(실제는 Gemini 503·타임아웃) | `docs/server-stability-current-state.md` | 연계(`if_history`)·아웃박스에 결과·지연·시도 횟수를 남겨 "왜 안 갔나"에 답할 수 있게 |
| 12 | 루트 `Dockerfile`(폴백)과 `backend/Dockerfile`(운영)이 어긋남(시간대·메모리 옵션 누락) | 분석 중 발견 | 배포 산출물은 하나로. 설치형 패키지와 SaaS 이미지가 같은 JVM 옵션을 쓰는지 테스트 또는 체크리스트로 |
| 13 | 읽기 전용 트랜잭션 안의 부가 로그 INSERT 실패가 본 요청(로그인)을 500 으로 만듦 → 로그 저장은 `REQUIRES_NEW` + 실패 삼킴 | `common/analytics/EventLogService` | `sys_access_log` 기록(P0 6단계): 본 요청을 깨뜨리지 않게 분리. 단 RLS 범위가 새 트랜잭션에도 설정되는지 확인 필요(`TenantAwareTransactionManager` 가 REQUIRES_NEW 에도 `set_config` 하는지 테스트) |

---

## 5. 원래 세션에 넘길 결정 사항

1. **오류 코드 enum 도입 시점과 문구 방식** — 지금(3단계) `HbErrorCode(status, messageKey)` 로 모을지, 4단계 i18n 과 함께 할지. 그리고 DB 제약 위반 매핑(23505→409 `DUPLICATE`, 23503→409 `IN_USE`, 23514→400)을 P0 에 넣을지. (2.2)
2. **업무 행 멱등 컬럼 vs `sys_inbox` 통일** — docs/01 5.7 은 멱등키를 `sys_inbox` 로 통일했다. POP 실적·PDA 입출고는 "같은 키 → 그때 만든 행 반환"이 필요해 업무 행 `client_req_id` + unique 가 더 단순하다. 두 경로 병행 여부. (2.4)
3. **아웃박스 릴레이를 "집기 → 트랜잭션 밖 송신 → 결과 기록" 3단으로** 바꾸고 `sys_outbox` 에 `SENDING`·`locked_at`·오래된 SENDING 회수를 추가할지. (2.5, 4장 #1)
4. **다중 인스턴스 배치 잠금** — SaaS 에서 앱을 2대 이상 띄울 계획이 있으면 스케줄 작업에 PG advisory lock(또는 ShedLock JDBC) + 조건부 선점을 P0 6·7단계에 넣을지. 설치형은 1대라 영향 없음. (3장 마지막 행, 4장 #3)
5. **업무일 시계** — 회사/공장 시간대 + 근무조 경계 작업일을 P0 5단계에 `BizClock` 으로 둘지, P2 MES 착수 때 둘지. 존 없는 `now()` 금지 가드와 테스트 JVM 시간대 고정은 지금 넣어도 비용이 작다. (2.9)
6. **가드 테스트 묶음 채택 범위** — (a) 존 없는 now() 금지 (b) `comp_cd` 테이블 ↔ 보존 클래스·회사 해지 목록 커버리지 (c) i18n 키 ko/en/vi 동기화 (d) Flyway 번호 중복 (e) 필터 단계 401/403 봉투. 어느 것을 몇 단계에. (2.1)
7. **로그인 시도 제한을 계정 잠금 외에 IP 기준으로도 둘지** — 계정 잠금만 있으면 ID 를 아는 사람이 남의 계정을 잠글 수 있다. (2.3)
8. **prod 기동 가드 확장** — 예시 비밀번호 그대로, 세션 쿠키 Secure 꺼짐, 배포 모드 미지정 시 기동 중단. 그리고 "필수 설정 = 기동 중단 / 선택 연동 = 기능 단위 503" 규칙을 docs/01 7장에 넣을지. (2.6)
9. **skill 함정 목록 추가 후보** — `afterCommit` 안에서 재등록한 동기화는 조용히 사라짐, 외부 호출을 트랜잭션 안에서 기다리지 않기, 테스트 컨텍스트 조합 수 관리, Flyway 번호 세는 기준(병렬 세션). (4장)

---

### 부록: 주요 참고 파일 (Doubly 루트 기준)

- 백엔드: `backend/src/main/java/com/fitto/common/{exception/ErrorCode, exception/GlobalExceptionHandler, response/ApiResponse, config/JwtSecretGuard, config/SchedulingConfig, event/CoupleEventPublisher, time/KstClock, currency/CurrencyService, ai/GeminiClient, ai/AiJobService, ai/AiResultCache}.java`, `notification/service/ExpoPushNotificationService.java`, `chat/service/ChatService.java`, `diet/controller/MealController.java`, `relation/service/RelationRecordPurger.java`
- 테스트: `backend/src/test/java/com/fitto/common/time/KstClockGuardTest.java`, `relation/RestorerCoverageGuardTest.java`, `common/security/SecurityErrorResponseTest.java`, `common/analytics/ClientAnalyticsEventSyncTest.java`, `backend/build.gradle`(test 블록)
- 프론트: `frontend/src/api/{client, chatSocket, aiJob, upload}.ts`, `frontend/src/utils/{error, serviceStatus, chatSync, pendingMealPhotos}.ts`, `frontend/src/screens/diet/BarcodeScanScreen.tsx`, `frontend/src/hooks/useDirtyGuard.ts`, `frontend/scripts/verify-*.mjs`
- worker: `worker/spot-diff/spot_worker/jobs.py`, `backend/src/main/resources/db/migration/V143__spot_diff.sql`
- 문서: `docs/CHAT_DUPLICATE_SEND_2026-09-12.md`, `docs/AI_HARNESS_DESIGN.md`, `docs/STABILITY_ANALYSIS_2026-09-01.md`, `docs/server-stability-current-state.md`, `docs/SCHEDULER_TEST_FLAKE_2026-09-15.md`, `docs/CI_TIMEZONE_FAILURE_2026-09-15.md`, `docs/BARCODE_LOOKUP_2026-09-29.md`, `docs/INCIDENT_NOTICE_2026-09-30.md`, `docs/gemini-food-analysis-404_2026-10-15.md`

---

## 6. 검토 결과 (원래 세션, 2026-10-15) — 00·01 반영 완료, 6.2-7(로그인 잠금 보완) 구현 완료

판단 기준은 02·03 6장과 같다. **결론: 문서 품질이 높고 대부분 채택한다.** 특히 "규칙을 가드 테스트로 지킨다"는 SmartHappy 의 `TenantCatalogGuard` 방향과 같아서 그대로 확장한다.
00·01 반영과 코드는 사용자 확인 후.

### 6.1 이미 된 것 (P0 3단계 코드와 대조)

| 항목 | 상태 |
|---|---|
| 없는 계정에도 bcrypt 1회(시간 균등화) | `HappyAuthenticationProvider` dummyHash |
| 필터 단계 401/403 도 같은 봉투 (2.1 마지막 행, 결정 6-e) | `LoginSessionTest` 가 `UNAUTHENTICATED`·`SESSION_REVOKED`·`FORBIDDEN` JSON 코드를 검사 |
| `request.getRemoteAddr()` 사용(헤더 직접 파싱 안 함) | 그대로 유지. 프록시 뒤 배포 때 `server.forward-headers-strategy` + 신뢰 프록시로만 |
| REQUIRES_NEW 에도 회사 범위가 설정되는가 (4장 #13) | 코드상 예 — `prepareTransactionalConnection` 은 새 물리 트랜잭션마다 호출. **테스트는 아직 없음** → 6단계 접속 로그(REQUIRES_NEW) 때 같이 추가 |
| `timestamptz`, Testcontainers 실제 PG | 유지 (3장 근거 확인) |

### 6.2 5장 결정 사항 권고

| # | 사항 | 권고 | 시점 |
|---|---|---|---|
| 1 | 오류 코드 enum + DB 제약 매핑 | **채택.** `HbErrorCode(status, messageKey)` 로 모으고 문구는 i18n 키. 제약 위반 23505→409 `DUPLICATE`, 23503→409 `IN_USE`, 23514→400 `INVALID_VALUE`(SQLState·제약 이름으로 판별). 400 계열(본문 파싱·타입 불일치·필수값) 매핑도 함께 | **P0 4단계 시작**(i18n 과 함께) |
| 2 | 업무 행 멱등 컬럼 vs `sys_inbox` | **둘 다.** 범용 API·외부 수신은 `sys_inbox`, "같은 키 → 그때 만든 행 반환"이 필요한 POP 실적·PDA 입출고는 업무 행 `client_req_id` + `unique(comp_cd, client_req_id)`, 응답은 원래 성공 응답 | P2·P4 |
| 3 | 아웃박스 3단(집기 → 트랜잭션 밖 송신 → 결과 기록) + `SENDING`·`locked_at`·오래된 SENDING 회수 | **채택.** 외부 호출을 트랜잭션 안에서 기다리지 않는다를 규칙으로(커넥션 풀 고갈) | P0 7단계 |
| 4 | 다중 인스턴스 배치 잠금 | **채택(싸게).** 설치형도 무중단 재배포 때 잠깐 2대가 뜬다. 작업별 `pg_try_advisory_xact_lock(작업 키)` + 조건부 선점. ShedLock 같은 라이브러리는 안 씀 | P0 6단계(첫 배치와 함께) |
| 5 | 업무일 시계 | **나눔.** 존 없는 `LocalDate.now()`·`LocalDateTime.now()` 금지 가드 + 테스트 JVM 시간대 고정은 **P0 5단계**(싸다). 공장 시간대·근무조 경계 작업일(`WorkDayResolver`)은 **P2 MES 착수 때** | P0 5 / P2 |
| 6 | 가드 테스트 묶음 | (d) Flyway 번호 중복 **P0 4단계**(병렬 세션 운영 중이라 바로), (a) 존 없는 now() **P0 5**, (c) i18n 키 ko/en/vi 동기화 **P0 4**, (b) comp_cd 테이블 ↔ 보존 클래스·회사 해지 목록 커버리지(카탈로그 기반) **P0 6**, (e) 완료 | 단계별 |
| 7 | 로그인 시도 제한(IP 기준) | **채택 — 3단계 보완으로 권고.** 지금은 아이디만 알면 5번 틀려 남의 계정을 **영구** 잠글 수 있다(공장 공용 PC 에서 현실적인 사고). ① 잠금을 **자동 해제(예: 15분)** 로 바꾸고 관리자 해제도 가능하게 ② **IP + 회사코드 기준 실패 횟수 제한**(인메모리, 성공 시 리셋, 초과 시 잠깐 차단) | **P0 3단계 보완(커밋 전 또는 다음 작업)** |
| 8 | prod 기동 가드 확장 + "필수 설정 = 기동 중단 / 선택 연동 = 기능 단위 503" | **채택.** 예시 비밀번호(`change-me`) 그대로, 세션 쿠키 Secure 꺼짐, 배포 모드 이상 → 기동 중단. 규칙은 01 7장에 | P0 9단계(배포 준비) — 규칙 문서는 지금 |
| 9 | skill 함정 목록 | **채택(지금, 문서만).** afterCommit 안 재등록은 조용히 사라짐 / 외부 호출을 트랜잭션 안에서 기다리지 않기 / 테스트 컨텍스트 조합 수 관리(공통 베이스 1개) / Flyway 번호는 최신 main + 병렬 세션 선점까지 세기 | 지금 |

### 6.3 그 밖에 채택

- `hb.api`(P0 4단계): 401/403 일 때만 로그인으로 보내고 네트워크 끊김·타임아웃·5xx 는 세션 유지 + 오류 표시, 기본 타임아웃(서버 처리 한도보다 짧게), `code` 로 분기, 저장 버튼 동기 잠금(`data-busy`).
- Hikari `leak-detection-threshold`(로컬·테스트 프로필) — 트랜잭션 안 외부 호출 회귀 탐지.
- 헬스체크 분리: 얕은 liveness(항상 UP) / 깊은 readiness(DB) — P0 9단계.
- 스케줄링 조건부 활성(`happyboot.scheduling.enabled`, test 프로필 false) + 작업 메서드 직접 호출 테스트 — P0 6단계.
- PDA 스캔 루프·write-ahead 오프라인 대기열·장애 공지 `status.json`·최소 앱 버전 — P2/P4/P0 9단계.
- 분석 문서 머리말에 **기준 커밋** 표기 — 백그라운드 분석 규칙에 추가.

### 6.4 가져오지 말 것 (3장) — 전부 동의

H2 테스트, 웹 JWT, Redis 의존·fail-open, 인메모리 작업 큐, 서비스별 스코프 헬퍼 복제, JPA 감사, `timestamp`(존 없음), B2C 결제 모델, 서명 크기 제한 없는 직접 업로드, main 직접 push 규칙.
