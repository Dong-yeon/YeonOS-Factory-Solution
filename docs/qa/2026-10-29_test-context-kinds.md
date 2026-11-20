# QA 다음 회차 확인 대상 — 시험 컨텍스트 종류를 늘리는 패턴 (2026-10-29, 목록만)

> 상위 요청(2026-10-29): 전체 check 간헐 실패(`Could not open JDBC Connection`·`Java heap space`)의 원인은 상위가 찾았다 —
> 시험 컨텍스트 종류 × Spring 기본 캐시 32 × 컨텍스트당 Hikari 최대 10 이 시험 DB `max_connections` 300 에 닿고 힙 1g 를 넘음.
> 조치는 상위가 `spring.test.context.cache.maxSize=16`(build.gradle 시험 태스크). 이 문서는 **컨텍스트 종류 자체를 줄일 후보 목록** — 코드·시험 변경 없음.
> 근거: `src/test/java` 정적 분석(클래스 선언의 `@AutoConfigureMockMvc`·`@MockitoBean`·`@MockitoSpyBean`·`@TestPropertySource`·`@SpringBootTest(properties)`·`@Import`·`@ActiveProfiles`), main `db16ed2c`.
> 정적으로 센 종류는 약 22(아래 표, 같은 설정의 기반 클래스 QmsTestBase·ApsTestBase 는 `MockMvc` 묶음과 같은 컨텍스트) — 상위가 실행 중 센 44 와의 차이는 [추정] 정적으로 못 보는 것(필드가 아닌 곳의 목 빈, 중첩 클래스, 캐시에서 밀려났다 다시 만든 컨텍스트 등). 실행 중 수는 회차 때 Spring 로그(`DefaultCacheAwareContextLoaderDelegate` 디버그)로 다시 센다.

## 1. 지금 묶음 (정적, 클래스 수 순)

| 클래스 수 | 설정 | 예 |
|---|---|---|
| 221 (+QmsTestBase 26·ApsTestBase 8·HomeQmsCardTest) | `@AutoConfigureMockMvc` 만 | 대부분 |
| 37 | 아무 것도 없음(MockMvc 없음) | FileAttachAsTest·RetentionTest·LogDigestJobTest … |
| 5 | MockMvc + `@MockitoBean JavaMailSender` + 메일 속성 | PcnNoticeQaTest·PlmPcnMailTest·PlmPcnMailAttachTest … |
| 5 | MockMvc + `@MockitoBean SupplierQualityQuery` | SupplierQualityTest·ReturnTest·ClaimTest … |
| 2 | MockMvc + `@MockitoBean InspPlanLookup` | QualityLinkQaTest·QualityStockTest |
| 1 | `@MockitoBean InspPlanLookup`(MockMvc 없음) | ReceiptLocQueryTest |
| 1 | MockMvc + `@MockitoSpyBean LotService` | PopWmsStockLinkQaTest |
| 1 | MockMvc + `@MockitoSpyBean LotService` + `@Import(TestGates)` | PopWorkTest |
| 1 | `@MockitoSpyBean PasswordEncoder` | PopPinQaTest |
| 1 | `@MockitoSpyBean PasswordEncoder` + Hikari 4 속성 | PopPinPoolTest |
| 1 | Hikari 4 속성 | AccessLogAfterReleaseTest |
| 1 | `@MockitoSpyBean LotHoldPort`(QmsTestBase) | SpcEventTest |
| 1 | `@MockitoBean QualityStockLink` + `StockQuery`(QmsTestBase) | QmsStockLinkTest |
| 1씩 | MockMvc + `@MockitoBean` AlarmSink / EcoTrace / LotStockLink / SpareIssueLink | EquipEventTest·ShipPlmGateTest·LotStockLinkHookTest·CmsSpareReturnTotalTest |
| 1씩 | MockMvc + `@Import(내부 설정)` | CloseNoticeTest·PlmQmsContractTest |
| 1씩 | 프로필 demo / {test, migrate} | DemoProfileTest·MigrateModeTest |
| 2 | `RANDOM_PORT` + 보안 속성 | ForwardedHeaderTrustedTest·ForwardedHeaderUntrustedTest |

## 2. 줄일 후보 (효과 큰 순)

| # | 후보 | 줄어드는 종류 | 주의 |
|---|---|---|---|
| C1 | **MockMvc 없음 37 클래스를 MockMvc 묶음에 합치기** — `PostgresTestBase` 에 `@AutoConfigureMockMvc` 를 두면(쓰지 않아도 무해) 두 묶음이 하나 | 1 (가장 많이 쓰이는 둘이 하나가 됨 — 캐시에서 덜 밀려남) | MockMvc 를 만드는 비용뿐, 동작 변화 없음 [추정] |
| C2 | **같은 목끼리 맞추기**: `InspPlanLookup` 목 3 클래스(MockMvc 유무만 다름 — C1 이면 하나), `PasswordEncoder` 스파이 2(PopPinQaTest 에 Hikari 4 속성을 같이 두면 PopPinPoolTest·AccessLogAfterReleaseTest 와 묶을 수 있음 — 단 AccessLog 쪽은 스파이 없음), `LotService` 스파이 2(PopWorkTest 의 `@Import(TestGates)` 를 PopWmsStockLinkQaTest 에도 — 쓰지 않으면 무해한지 확인) | 2~3 | 목·스파이는 시험마다 초기화(기본 reset AFTER)라 섞여도 동작 같음 [추정] |
| C3 | **하나뿐인 목을 공용 시험 설정의 "바꿔 끼울 수 있는 가짜" 로**: AlarmSink·EcoTrace·LotStockLink·SpareIssueLink·QualityStockLink·StockQuery·LotHoldPort·SupplierQualityQuery — 공용 `@TestConfiguration`(모든 컨텍스트에 같게)에서 실제 빈을 감싼 위임 빈으로 두고, 시험이 필요할 때만 동작을 바꾸고 `@AfterEach` 에 되돌림 | 6~8 | 시험이 서로 영향을 주지 않게 되돌림을 강제(공용 초기화 규칙). 모듈 경계 계약(포트) 빈이라 가짜가 실제 계약을 벗어나지 않게 |
| C4 | `@Import(내부 설정)` 2(CloseNoticeTest·PlmQmsContractTest) — 내부 설정의 빈을 C3 의 공용 설정으로(조건부 등록 대신 위임) | 2 | 내부 설정이 실제 빈을 **더하는**지 **바꾸는**지에 따라 |
| C5 | 메일 5 클래스(목 + 속성) — 이미 한 묶음. 그대로 | 0 | — |
| C6 | 프로필·RANDOM_PORT·Hikari 속성 시험(5~6) — 시험 목적 자체가 설정이라 그대로 | 0 | 캐시에서 먼저 밀려나도 됨 — 실행 순서상 뒤로 몰면(클래스 이름 순서 등) 다른 묶음을 덜 밀어냄 [추정] |

- C1+C2+C3 이면 정적 기준 약 22 → 약 10 [추정]. 캐시 상한 16 안에 모두 들어가 컨텍스트를 다시 만들지 않는다(시간·힙·연결 모두 줄어듦).
- 연결 쪽 별도 완화(상위 판단 대상): 시험 데이터소스에 `hikari.minimum-idle` 을 작게(예 1~2)·`idle-timeout` 을 짧게 — 캐시에 남아 쉬고 있는 컨텍스트가 연결 10 을 쥐지 않게(`PostgresTestBase` 의 `@DynamicPropertySource` 한 곳).

## 3. 회차 때 할 일
1. 실행 중 컨텍스트 수·최대 연결 수 실측(이번 조사에서 만든 5초 표본기 — DB `pg_stat_activity` 의 클라이언트 연결 수 — 와 Spring 컨텍스트 캐시 통계 로그).
2. 위 후보 중 상위가 고른 것만 시험 PR 로(동작 변화 없음을 전체 check 로 확인 — 시험 수·통과 수 같음).
