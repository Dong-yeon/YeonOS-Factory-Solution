# QA 2026-10-23 — 시험 요청 IP 대역 겹침 (회사 코드 이름 공간 후속)

> 독립 QA 세션(docs/29 8장). 기준 main `c0fa4cb7`(#413 — NS-1·NS-2 수정, 회사 코드 가드 해제). 상위 요청: 관찰해 둔 IP 대역 겹침을 같은 방식으로 조사.
> 표시: **[코드]** · **[추정]**. 결론: **지금은 순서 의존 실패 경로 없음** — 가드 시험은 만들지 않고, QA 소유 시험 2곳만 겹치지 않는 대역으로 옮겼다.

## 1. 한도의 모양 [코드]

`LoginAttemptLimiter` 가 웹 로그인(`HappyAuthenticationProvider`)과 단말 활성화(`PopAuthService.activate`)의 실패를 센다.
- 메모리에 있고 Spring 컨텍스트에 하나다 — 같은 컨텍스트를 쓰는 시험끼리 공유된다.
- 키는 둘이다.
  - IP+회사(`ip-comp-max-fail` 20): 성공하면 비운다.
  - IP 단독(`ip-max-fail` 100): 비우지 않고 창(`ip-window-minutes` 10분)이 끝나야 풀린다.
- 시험 회사 코드는 시험마다 새로 만들므로 IP+회사 키는 시험끼리 겹치지 않는다. 겹칠 수 있는 것은 **IP 단독 키**뿐이다. 같은 IP 에서 10분 안에 실패가 100번 쌓여야 다른 시험의 로그인·활성화가 429 가 된다.

## 2. 대역 겹침과 실패를 쌓는 시험

| 대역 | 시험(IP 만드는 방식) | 실패를 쌓나 |
|---|---|---|
| `10.77.` | PopTerminalTest(나노초 난수 두 칸) · PasswordChangeTest(`10.77.0.{SEQ}`) · DemoProfileTest(`10.77.{n/250}.{n%250+1}`) | PopTerminalTest `틀린_등록_코드는_IP_기준으로_막힌다` 가 한 IP 에 약 20회(IP+회사 한도에서 429). 나머지는 몇 회 |
| `10.78.1.1`·`.2` | PopTerminalTest(고정) | 실패 3회(고정 IP — 같은 실행에서 이 시험만) |
| `10.81.` | QaPopFixture · PopStockLinkTest · CompletionReceiptTest(나노초 난수) | 성공 경로만 |
| `10.82.` | PlmChangeLinkTest · ErpTest | 성공 경로만 |
| `10.83.` | LotReportTest · ResultViewTest | 성공 경로만 |
| `10.86.` | PdaWarehouseQaTest · PdaPickTest · PopBreakdownTest | 성공 경로만 |

[추정] 한 IP 에 쌓이는 실패는 최대 약 20회이고 한도는 100회다. 대역이 겹쳐도 같은 IP 를 고를 확률은 낮다(나노초 난수 두 칸). 그래서 지금은 다른 시험을 막는 경로가 없다. 기본 주소 `127.0.0.1`(MockMvc 기본)로 실패를 내는 시험도 찾지 못했다 — 실패를 내는 시험은 모두 자기 대역을 정한다.

## 3. 판단

- 회사 코드(NS-1·NS-2)는 겹치면 **반드시** 23505 였다. IP 는 겹쳐도 한도 100 에 닿아야 문제가 된다. 정적 가드("파일마다 둘째 옥텟 하나")는 지금 13개 파일을 고치게 하는데, 막는 위험이 작다.
- 대신 **위험해지는 조건**을 적어 둔다: 한 시험이 같은 IP 로 실패를 수십 회 이상 쌓도록 바뀔 때(예: IP 단독 한도 100 자체를 시험). 그때는 그 시험만 고정 전용 대역(예: `10.78.` 처럼)을 쓰고, 끝에 `LoginAttemptLimiter` 창을 비우는 시험용 초기화가 있으면 좋다(지금은 `setClock` 만 있음).
- QA 소유 시험은 겹치지 않게 옮겼다: `PdaWarehouseQaTest` `10.86.` → `10.91.`, `QaPopFixture` `10.81.` → `10.92.`.

## 4. 다음 후보

- `LoginAttemptLimiter` 에 시험용 `reset()`(패키지 범위)과, 그것을 쓰는 확장(`@AfterEach`)을 실패를 쌓는 시험(PopTerminalTest·LoginSessionTest·PasswordChangeTest)에만 붙이는 안 — 상위·플랫폼 판단.
