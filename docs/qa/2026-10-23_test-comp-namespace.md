# QA 2026-10-23 — 시험 보조끼리 회사 코드 이름 공간 충돌

> 독립 QA 세션(docs/29 8장). 기준 main `1127f307`. 상위 요청: #395 묶음 check 에서 `ApiErrorMappingTest` 가 시험 순서에 따라 실패한 원인이었다. `TestProbeController` 의 회사 코드 `PA/PB{n}` 가 `TestAccounts.newComp("PB")` 와 겹쳤다(상위가 `TPA/TPB` 로 고침). 같은 종류가 더 있는지 훑었다.
> 표시: **[코드]** · **[재현]**. 시험 `src/test/java/io/smarthappy/qa/TestCompNamespaceQaTest.java`(3건: 통과 1 · 재현 `@Disabled` 2). 제품 코드·다른 시험은 고치지 않았다.

## 0. 요약

| ID | 구분 | 내용 | 조치 |
|---|---|---|---|
| NS-1 | 결함(시험) | `TenantRunnerTest` 가 자체 SEQ 로 `RA{n}`·`RB{n}` 을 만든다. `newComp("RA")`(CmsRiskTest), `newComp("RB")`(CmsRiskTest·MaterialReceiptTest·PoReceiptGateTest·ReceiptPostingTest)와 같은 이름 공간이다 — PB 와 같은 종류 | **상위(플랫폼 시험)** `@Disabled` 재현 |
| NS-2 | 결함(시험) | `newComp("PL4")`(PlmStage4Test)와 `newComp("PL")`(PmsLinkTest·ShipPalletTest)가 겹칠 수 있다. 같은 카운터라도 `"PL" + 412 = "PL4" + 12` | **상위(시험 보조)** `@Disabled` 재현 |
| — | 관찰 | 자체 카운터 `TA`·`TB`·`BLANK`(TenantIsolationTest), `RS`(TenantRunnerTest), `NEW`(OpsConsoleTest), `TPA`·`TPB`(TestProbeController)는 지금은 newComp 접두어와 안 겹친다. 누가 `newComp("TA")` 를 쓰면 바로 같은 문제가 된다 | 가드로 막기 |

## 1. 원리 [코드]

- `TestAccounts.newComp(prefix)` = `prefix + SEQ.incrementAndGet()`. SEQ 는 JVM 하나에 하나(static AtomicInteger)다. 시험은 JVM 하나(`maxParallelForks` 없음)와 컨테이너 DB 하나를 같이 쓰므로, 한 실행 안에서는 newComp 끼리 겹치지 않는다. 단, 접두어 하나가 다른 접두어 + 숫자이면 겹친다(NS-2).
- 시험 클래스가 **자기 SEQ** 로 `"RA" + n` 을 만들어 `sys_comp` 에 직접 넣으면 숫자를 매기는 카운터가 둘이 된다. 실행 순서에 따라 CmsRiskTest 가 먼저 `newComp("RA")` 로 `RA1`·`RA2` 를 만들면, 뒤의 `TenantRunnerTest` 가 `RA1` 을 넣다가 23505(키 중복)로 실패한다. 반대 순서면 newComp 쪽 시험이 실패한다. 순서는 Gradle·JUnit 이 정하므로 "가끔 빨간" 시험이 된다.
- NS-1 은 newComp SEQ 가 작을 때(실행 초반)만 겹친다. 그래서 지금까지 드러나지 않았을 수 있다 [추정].

## 2. 재현 [재현]

`TestCompNamespaceQaTest` 는 `src/test/java` 를 읽는다.
- newComp 계열 접두어: `newComp("X")`, 접두어를 newComp 로 넘기는 `SysAdminTestSupport.setUpCompany("X")`·`QaPopFixture(…, "X")`
- 자체 카운터 접두어: `sys_comp` 를 직접 넣거나 `/api/ops/companies` 를 부르는 파일의 `"X" + SEQ.incrementAndGet()`·`"X" + n`

두 접두어가 같거나 한쪽이 다른 쪽 + 숫자이면 충돌로 본다.

| 시험 | 결과(`@Disabled` 풀고 실행) |
|---|---|
| `검사기가_실제로_잡는다` | 통과 — 자체 카운터 9개(TA·TB·BLANK·RA·RB·RS·TPA·TPB·NEW) 모두 찾음 |
| `newComp_접두어끼리_숫자만_달리_겹치지_않는다` | `PL ↔ PL4` |
| `자체_카운터_회사_코드는_newComp_접두어와_겹치지_않는다` | `RA [TenantRunnerTest] ↔ newComp RA [CmsRiskTest]`, `RB [TenantRunnerTest] ↔ newComp RB [CmsRiskTest, MaterialReceiptTest, PoReceiptGateTest, ReceiptPostingTest]` |

## 3. 수정 제안 (상위 판단)

1. NS-1: `TenantRunnerTest` 의 `RA`/`RB`/`RS` 를 이 클래스 전용 접두어로 바꾼다(예: `TRA`/`TRB`/`TRS`, TestProbeController 의 `TPA` 와 같은 방식). 또는 `createComp` 를 newComp 로 바꾼다. 상태값(SUSPENDED)을 쓰니 `setCompStatus` 와 함께 쓰면 된다.
2. NS-2: `PlmStage4Test` 의 `PL4`/`PL4B` → 숫자로 끝나지 않는 접두어(예: `PLF`/`PLFB`).
3. 가드: 두 `@Disabled` 를 풀어 `guard/` 로 옮기면 새로 생기는 같은 종류를 막는다. 가드는 상위 소유이고 QA 는 qa/ 에만 쓰므로 위치는 상위가 정한다.
4. 근본 대안: `TestAccounts.newComp` 만 회사를 만들게 하고 `sys_comp` 직접 insert 를 가드로 막는다. 예외는 형식 오류를 시험하는 `TenantIsolationTest` 정도뿐이다. 범위가 넓어 제안만 한다.

## 4. 같은 종류 — 요청 IP 대역 (관찰)

로그인·단말 활성화 실패 한도는 IP+회사와 **IP 단독**(`ip-max-fail` 100) 두 가지다. `LoginAttemptLimiter` 는 메모리에 있고, 한 실행 동안 같은 Spring 컨텍스트를 쓰는 시험끼리 공유된다.

시험들이 고른 IP 대역이 겹치는 곳:

| 대역 | 시험 |
|---|---|
| `10.77.` | PasswordChangeTest·PopTerminalTest·DemoProfileTest |
| `10.81.` | QaPopFixture·PopStockLinkTest·CompletionReceiptTest |
| `10.86.` | PdaWarehouseQaTest·PdaPickTest |
| `10.0.0.` | 7개 파일 |

지금 겹친 쪽은 대부분 성공 경로(실패를 쌓지 않음)라 문제가 드러나지 않았다 [추정]. 실패를 일부러 쌓는 시험이 다른 시험과 같은 IP 를 쓰면 같은 종류의 순서 의존 실패(429)가 된다. 회사 코드 가드 다음 후보로 둔다.
