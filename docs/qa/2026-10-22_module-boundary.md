# QA — 2부 모듈 경계 (WMS B5 훅 · lot_hold/Q8 · 전자서명 · 결재 위임 · 화면 공통)

> 2026-10-22 · 독립 QA 하위 세션(docs/29 8장) · 브랜치 `claude/qa-work`
> 기준 커밋: `claude/elegant-carson-ys2zdp` `6f086be` → 작업 중 main(`a880060`, #183·#188·#189)을 병합(`ffb5d2f`)(main `ed6a357` + #178 전자서명 V116 · #180 MES B5 훅 · docs/29 8장). 대상 중 ESign·#180 이 아직 main 에 없어 상위 병합 브랜치에서 시작했다.
> **제품 코드·마이그레이션·SKILL.md 는 고치지 않았다.** 시험 `src/test/java/io/smarthappy/qa/`, 실행 기록 `docs/testing/qa.md`.
> 표기: **[코드]** 코드를 읽고 확인한 것(재현 전) · **[기록]** 모듈 시험·docs/testing 근거 · **[재현]** 이 세션 시험·스크립트로 실제로 일어남을 확인.

## 0. 요약

| # | 심각도 | 결함 | 담당 | 근거 | 시험 |
|---|---|---|---|---|---|
| QB-1 | **중간** | POP **실적 정정**과 다른 라인 **실적**이 같은 비관리 품목 둘(NUT·BOLT)을 서로 반대 순서로 지나가면 **교착(40P01) → 500**. 정정은 투입 계보를 계보 id 순(원래 투입 행 순서)으로 역분개하며 `inv_stock` 칸을 잡고, 실적은 요청 투입 행 순서로 잡는다. 비관리 품목은 LOT 이 달라도 재고 칸(LOT 없음)을 같이 쓰므로 `lockForConsume`(LOT 선잠금)이 줄을 세우지 못한다 | MES(+WMS) | [재현] `[정정, 실적] = [200, 500]`, 서버 로그 `DB 처리 오류 40P01 DeadLockReport` · `DeadlockLoserDataAccessException` | `PopWmsStockLinkQaTest.결함QB1_…` `@Disabled` |
| QS-1 | 낮음 · **main `5b84d95` 에서 수정됨** | 화면: `hb.select` 를 **문구 로드(`hb.loadMessages`) 전에** 만드는 CMS 화면 6개에서 공통코드 응답이 문구 응답보다 먼저 오면 첫 항목이 **`js.common.choose` / `js.common.all` 키 글자**로 남고, 문구가 온 뒤에도 다시 그리지 않는다 | 상위(공통 `hb.select` 수정 예정) · CMS | [재현] node 가상 DOM 스크립트(부록 A) / [코드] 6개 화면. main 병합(`ffb5d2f`) 뒤 같은 스크립트: 문구가 올 때까지 채우지 않고 온 뒤 "선택하세요" ✅ | 재현만(지시) |
| QS-2 | 낮음(의심) | PLM ECO 상세 PPAP: 첫 조회는 `cur.eco.ecoId !== id` 로 막지만, 그 안의 **둘째 조회(증빙 `PPAP-{id}` 첨부)** 는 대상 확인 없이 `evidence` 를 덮고 그린다 — ECO 를 빠르게 바꾸면 앞 ECO 의 증빙이 뒤 ECO 의 PPAP 요소 첨부 후보로 보일 수 있다 | PLM | [코드] `views/plm/eco-extras.js:110-113` | 브라우저 재현 안 함 |

관찰(결함 아님, 설계 확인 필요 표시 ⚠️)

| # | 내용 | 근거 |
|---|---|---|
| O-1 | 실적 ↔ 실적은 **LOT 채번 행 upsert 잠금**(`NumberingMapper` `on conflict … do update`, 규칙 `L{yyMMdd}-{seq}` 회사·날짜 한 행)이 회사 전체 POP 실적을 커밋까지 줄 세워 교착이 나지 않는다 — 처음 가설(QB-1 을 실적끼리로)은 이것 때문에 재현되지 않았다. ⚠️ 같은 이유로 **회사의 모든 POP 실적이 한 줄로 직렬화**된다(라인이 많으면 처리량 상한) | [재현] `관찰_두_라인_실적끼리는_…`(먼저 온 쪽 래치가 시간 초과 = 둘째는 채번에서 기다림) |
| O-2 | ⚠️ 전자서명 토큰 상한 5개를 넘으면 **가장 오래된 것이 아니라 전부** 비운다(`ESignService.issue` `tokens.clear()`) — 여러 탭에서 서명 대화상자를 연 사용자는 앞 탭에서 `ESIGN_TOKEN` | [재현] `ESignQaTest.관찰_6번째_…` |
| O-3 | 다른 대상으로 내민 토큰도 소모된다(맞는 대상으로 다시 못 씀) — 실패 닫힘, 문서(16.2 "한 번") 대로 | [재현] `ESignQaTest.관찰_다른_대상으로_…` |
| O-4 | 위임을 만든 뒤 **대리가 사용 중지**돼도 위임 행은 살아 있다(만들 때만 트리거가 "사용 중 대리" 를 본다). 시작 알림은 `NotificationService.send` 가 사용 중지 사용자를 `NO_USER` 로 걸러 **가지 않는다**(처음 가설 "알림이 간다" 는 틀림 — 시험으로 확인). 위임자 사용 중지는 위임 유지(남은 결재 처리 길) — 의도로 보임 | [재현] `ApprovalDelegateQaTest.만든_뒤_사용_중지된_대리에게는_…` |
| O-5 | WMS 실사 반영(`CountService.post`)도 여러 품목 원장을 **실사 줄 순서**로 한 트랜잭션에 넣는다 — 비관리 품목 칸 잠금 순서가 POP 정정·실적과 다를 수 있어 QB-1 과 같은 꼴 [코드]. QB-1 을 고칠 때 같이 볼 것 | [코드] `CountService.java:350-357` |

## 1. WMS B5 투입 차감·LOT 변환 + MES 훅 (#180)

모듈 시험은 한쪽을 가짜로 둔다 — [기록] `PopWorkTest` 는 LotService 스파이로 409 를 흉내, `LotStockPostingTest` 는 MES 를 SQL 로 흉내, `LotStockLinkHookTest` 는 계약 빈이 Mock. 이 세션은 **실제 POP API + 실제 WMS 빈**(공장 스위치 `WMS_STOCK_LINK=Y`, 자재 입고 API 로 들인 재고)으로 이었다(`QaPopFixture`).

| 해 본 것 | 기대 | 결과 | 판정 |
|---|---|---|---|
| POP 실적(양품 5 + 자재 LOT 6 투입) | 재고 20 → 14, `ISSUE_WO` 1행. 기록자 = 단말(`t…:TAB-1`), 작업자·발생 시각 = 계보, 입력 방식 AUTO | 그대로 | ✅ [재현] |
| 재고만 줄인 LOT(잔량 10 · 재고 4)에서 6 투입 | 409 `INSUFFICIENT_STOCK`, 칸 오류 없음. 실적·산출 LOT·계보·원장 0행 | 그대로 | ✅ [재현] |
| 같은 `clientReqId` 로 재고를 채운 뒤 다시 보냄(POP 대기열 409 보존, docs/30 12.4.2) | 200, `replayed=false`(409 가 요청 id 를 소모하지 않음) | 그대로 | ✅ [재현] |
| 실적 정정 | `ISSUE_WO` 역분개 1행(사유 = 정정 사유, 기록자 단말), 재고 원복 20, 산출 LOT 보류. 다시 정정 → `STATE`, 역분개 1행 그대로 | 그대로 | ✅ [재현] |
| 재공 LOT(앞 공정 산출)을 다음 공정에 투입(D-B14) | 실적 200, 그 LOT 원장 0행(건너뜀) | 그대로 | ✅ [재현] |
| A사 스위치 N · B사 같은 공장 코드 P1 스위치 Y | A사 투입은 차감 없음 | 그대로(정책은 회사 범위) | ✅ [재현] |
| **실적 ↔ 실적**: 두 라인이 NUT·BOLT 를 반대 순서로 투입, 첫 칸을 쥔 뒤 서로 기다리게(래치) | 교착 없음 | 둘 다 200 — 둘째가 **LOT 채번 잠금**에서 기다림(O-1) | ✅ [재현] |
| **정정 ↔ 실적**: 라인 1 실적([NUT, BOLT]) 정정 + 라인 2 실적([BOLT, NUT]), 각자 첫 칸을 쥔 뒤 상대를 기다리게 | 둘 다 200 | **[200, 500] — 40P01** | ❌ **QB-1** [재현] |

### QB-1 재현 절차

1. 비관리 품목 NUT·BOLT 를 자재 입고로 각각 두 번(LOT n1·n2, b1·b2) — 재고 칸은 품목별 하나(LOT 없음).
2. 라인 1 단말: 실적 `inputs = [n1, b1]` → 계보 NUT → BOLT 순.
3. 동시에: 라인 1 단말 `POST /api/pop/results/{id}/correct` + 라인 2 단말 실적 `inputs = [b2, n2]`.
4. 시험은 `@MockitoSpyBean LotService` 로 각 요청의 **첫** `reverse`/`consume` 이 끝난 뒤(= 첫 재고 칸 행 잠금을 쥔 뒤) 두 요청이 모두 거기 올 때까지 기다리게 한다("모두 관찰한 뒤 진행" 래치, 5초 시간 초과 — 고친 뒤에는 한쪽이 첫 칸에서 기다리므로 시간 초과 후 그대로 진행해 시험이 통과한다).
5. 정정: NUT 칸 → (래치) → BOLT 칸 / 실적: BOLT 칸 → (래치) → NUT 칸 → PostgreSQL 교착 감지(1초) → 한쪽 롤백.

- 예상: 둘 다 200(또는 재고가 정말 모자라면 한쪽 409).
- 실제: 정정 200 · 실적 **500**(`ApiExceptionHandler: DB 처리 오류 40P01`). 대기열이 있는 POP 은 500 을 "다시 보낼 오류" 로 보고 재전송하므로 데이터는 결국 들어가지만, 작업자 화면에 오류가 뜨고 라인 2 실적이 늦어진다. 실사 반영(O-5)이 끼면 사무 화면이 500.
- 원인 [코드]:
  - `PopWorkService.result` 300-314행: `lockForConsume(투입 LOT)` 은 lot_id 순이지만 `lots.consume(...)` 은 **요청 투입 행 순서**.
  - `PopWorkService.correct` 365-381행: `lockForConsume` 뒤 `mapper.inputEdges(...)` 를 **계보 id 순**으로 `lots.reverse`.
  - `LotStockPosting.take`(WMS): 한 계보 안의 칸은 stock_id 순으로 잠그지만 **계보 사이**의 순서는 부르는 쪽 몫. 비관리 품목은 `lot = null` 이라 `InventoryService.lockLots` 도 비어 있음.
- 고칠 방향(담당 세션 판단):
  1. (MES) 실적·정정 모두 투입 행을 **같은 열쇠로 정렬한 뒤** 처리 — 열쇠는 LOT id 가 아니라 **재고 칸이 같아지는 단위**(품목 id → LOT id). 실적은 응답 칸 오류의 `rowKey` 가 원래 행 번호여야 하므로 정렬은 처리 순서에만.
  2. (WMS) 또는 `LotStockLink` 에 "이 트랜잭션에서 쓸 품목들 선잠금" 진입점(예: 품목 단위 advisory xact lock 을 item_id 순) — MES 가 계보를 만들기 전에 한 번 부른다. 실사 반영(O-5)도 같은 진입점을 쓰면 세 경로가 같은 순서가 된다.
  - 어느 쪽이든 고친 뒤 `@Disabled` 를 지우면 `결함QB1_…` 이 통과해야 한다(래치 시간 초과 5초가 걸림).

## 2. MES lot_hold + QMS Q8a(부적합)·Q8b(봉쇄)

[기록] 모듈 시험이 근거 여럿·포트 멱등·같은 LOT 동시 두 근거(LotHoldTest), 같은 LOT 동시 NCR 두 건(NcrApiTest), 역순 봉쇄·같은 봉쇄 다른 LOT 동시 해제(ContainApiTest)를 본다. 이 세션은 **다른 모듈이 끼는 경계**를 봤다.

| 해 본 것 | 기대 | 결과 | 판정 |
|---|---|---|---|
| NCR(CLAIM) 등록 → LOT `Y/QMS_NCR` → MES 화면 근거 해제 API 로 QMS 근거 | 400 `HOLD_SOURCE` | 그대로 | ✅ [재현] |
| MES 화면 보류 해제(holdYn N) — MANUAL 근거 없음 | 400 `STATE`, QMS 근거 그대로 | 그대로 | ✅ [재현] |
| MANUAL 보류 → MANUAL 해제 | LOT 은 `Y/QMS_NCR` 그대로 | 그대로 | ✅ [재현] |
| 보류 LOT 의 WMS 상태 이동(AVAILABLE → QC, 같은 칸) | 허용(출고 아님 — `STATUS_OUT/IN`) | 200 | ✅ 의도 [코드] V092 트리거는 `ADJ_OUT·MOVE_OUT` 만 |
| 보류 LOT POP 투입 | 400, 투입 행 칸 `lotNo LOT_HOLD` | 그대로 | ✅ [재현] |
| NCR 취소 | 그 근거만 풀려 `N/` | 그대로 | ✅ [재현] |
| **보류가 커밋되기 전에 들어온 POP 투입**(보류 트랜잭션이 LOT 을 쥔 채 POP 이 잠금 대기에 들어간 것을 본 뒤 커밋) | 기다린 뒤 400 `LOT_HOLD`, 산출 LOT·원장 0 | 그대로(`lockUsable` 이 커밋 뒤 행을 다시 읽음) | ✅ [재현] |
| **보류가 커밋되기 전에 들어온 WMS 이동 출고** — 서비스 사전 검사(`StockService.lot`)는 잠금 없이 `hold_yn = N` 을 읽는다 | 원장 트리거의 `lot_master FOR SHARE` 가 기다린 뒤 새 값으로 400 `LOT_HOLD`, 재고 그대로 | 그대로 — 사전 검사는 빠른 안내용, 판정은 트리거 | ✅ [재현] |
| **NCR 취소 + 봉쇄 해제 + 새 NCR 등록**을 같은 LOT 에 동시에(다른 트랜잭션이 LOT 을 쥔 동안 셋 다 잠금 대기에 든 것을 본 뒤 놓음) | 교착 없음, 셋 다 200, LOT `Y/QMS_NCR`(새 NCR), 봉쇄 CLOSED | 그대로 | ✅ [재현] |

메모: 첫 실행에서 셋째 요청이 잠금 대기에 안 들어간 것은 **시험 쪽** 문제였다 — `CompletableFuture.supplyAsync` 기본 풀(ForkJoin 공용 풀)이 코어 4개 컨테이너에서 스레드 3개라 잡고 있는 트랜잭션 + 요청 3개 중 하나가 출발을 못 함. 동시성 시험은 전용 풀로(이 세션 시험은 `newCachedThreadPool`). 같은 패턴을 쓰는 모듈 시험이 있으면 코어 수에 따라 "동시" 가 아닐 수 있다 → 상위 메모.

## 3. 시스템관리 전자서명 재인증 (ESignService, V116)

[기록] `ESignTest` 6건 + docs/testing/sys.md 가 재사용·만료·다른 의미·다른 대상·다른 사람·다른 세션·트랜잭션 밖·5회 세션 끊김·**같은 세션 동시 30건(풀 10 초과)**·MFA·A사로 B사·직접 SQL 을 이미 본다. 빈 곳만:

| 해 본 것 | 결과 | 판정 |
|---|---|---|
| 같은 세션에서 대상 1~6 토큰 6개 발급 → 1~5 사용 | 1~5 모두 403 `ESIGN_TOKEN`, 6 은 서명 | ⚠️ O-2 [재현] |
| 대상 10 토큰을 대상 11 로 → 다시 대상 10 으로 | 403 → 403, 원장 0 | ✅ O-3 실패 닫힘 [재현] |
| 토큰을 받은 세션이 끊긴 뒤 그 토큰으로 consume | 500 아님, 403 `ESIGN_TOKEN` | ✅ [재현] |
| 요청당 연결 하나 | `issue` 는 바깥 트랜잭션 없이 `runner.call` → 연결 없이 비교 → `mfa.confirm`(순차) → 접속 로그 — 동시에 두 연결을 쥐지 않음 | ✅ [코드] + [기록] 풀 초과 동시 시험 |

## 4. 결재 위임 시작 알림 (docs/36 14.8)

[기록] `ApprovalDelegateTest` 는 서울 시간대·실제 시계로 오늘/모레·멱등·회수·A사로 B사를 본다. 이 세션은 **회사 시간대 America/Los_Angeles + `BizClock` 고정 시계**로 자정 앞뒤를 봤다.

| 해 본 것 | 결과 | 판정 |
|---|---|---|
| 2026-12-01T07:59Z(LA 11-30 23:59 · 서울 12-01 16:59 · UTC 12-01): 시작 11-29 위임 / 시작 12-01 위임 | 400 `PAST` / 200, 만들 때 알림 없음, 정기 작업도 0 | ✅ 회사 날짜 [재현] |
| 08:05Z(LA 12-01 00:05) 정기 작업 두 번 | 알림 1(멱등) | ✅ [재현] |
| 2026-12-02T03:00Z(LA 12-01 19:00): 시작 12-01 | 200 + 만들 때 바로 알림(UTC 날짜 12-02 를 썼다면 PAST 였음) | ✅ [재현] |
| 위임 뒤 대리 사용 중지 → 시작일 정기 작업 | 위임 행 유지, 알림 0(`NotificationService` 가 `NO_USER`) | ✅ O-4 [재현] |
| 위임자 사용 중지 | 위임 유지 | 의도로 보임 [재현] |

MFA: 이번 범위의 MFA 는 전자서명 재인증의 인증 코드 경로(3장 [기록] MFA 행)와 `MfaService.confirm` 순서([코드] 14.4.1 과 같은 선점 → 판정)만 확인했다. 위임과 MFA 를 엮는 경로(대리의 MFA)는 코드에 없다 — 대결은 로그인한 대리 본인의 세션.

## 5. 화면 공통

### 5.1 상세·첨부 늦은 응답(docs/24 C+)

- [코드] `hb.latest(key, promise)`(hb.js 109행)가 들어와 화면 **99개**가 상세·첨부·하위 표 로드에 쓴다. `load*/show*/open*/refresh*` 함수 중 `hb.api` 를 부르고 `hb.latest` 를 안 쓰는 것 26개를 훑었다 — 대부분 화면 열 때 한 번 읽는 목록(공장·코드·설정)이라 해당 없음.
- 남은 의심 **QS-2**: `views/plm/eco-extras.js` `loadPpap` — 첫 응답은 `if (!cur || cur.eco.ecoId !== id) return;` 로 막지만 그 안의 둘째 `hb.api(… 'PPAP-' + ppap.ppapId)` 응답은 확인 없이 `evidence = …; renderPpap()`. ECO A → B 를 빠르게 바꾸면 A 의 증빙이 B 화면의 PPAP 요소 첨부 후보에 섞일 수 있다(요소에 첨부를 거는 저장이 있으면 다른 ECO 파일이 걸림). 고칠 방향: 둘째 응답에도 같은 확인 또는 `hb.latest('ppap', …)` 로 한 줄.
- 표시만: `views/wms/label-mgt.js` `showFields`(라벨 유형 바꾸기 → 견본 칸 목록, 늦은 응답이면 앞 유형의 칸 이름), `views/pms/project-mgt.js` `showDeps`(메모리 목록이라 해당 없음).

### 5.2 셀렉트 placeholder 문구 로드 경합(QS-1, 재현만)

- [코드] `hb.select.fill()`(hb.input.js 71-90행)은 공통코드가 오면 첫 항목을 `hb.t('js.common.choose' | 'js.common.all')` 로 만든다. 문구 묶음이 아직 없으면 `hb.t` 는 **키를 그대로** 돌려준다(hb.js 41행). 화면이 `hb.select(...)` 를 `hb.loadMessages().then(...)` **밖(앞)** 에서 만들면 두 요청이 경주한다. 문구가 나중에 와도 다시 그리지 않는다(`reload` 는 화면이 부를 때만).
- 해당 화면(모두 CMS): `cms/fail-code-mgt.js:134-135`, `cms/counter-mgt.js:340`, `cms/equip-profile-mgt.js:165`, `cms/equip-sec-mgt.js:141`, `cms/pm-plan-mgt.js:285`, `cms/risk-mgt.js:398-403`. 나머지 화면은 `loadMessages().then` 안에서 만든다.
- [재현] 부록 A 스크립트(node + 가상 DOM, 실제 `hb.js`·`hb.numbers.js`·`hb.input.js` 를 그대로 실행): 공통코드 먼저 → 첫 항목 `"js.common.choose"`, 문구 도착 뒤에도 `"js.common.choose"`.
- **수정 확인**(main `5b84d95`, 이 브랜치 `ffb5d2f` 병합 뒤): `hb.loadMessages` 가 페이지당 한 약속, `hb.select` 가 코드 목록과 문구를 둘 다 기다림 → 같은 스크립트에서 공통코드만 온 동안 첫 항목 없음, 문구 도착 뒤 "선택하세요".
- (발견 당시) 고칠 방향: `fill` 안에서 문구를 `hb.loadMessages` 이후로 미루거나, `select()` 가 `ready = Promise.all([codeList, messagesReady])` 로 기다리게. 화면 쪽 순서를 고치는 것만으로는 다음 화면이 같은 실수를 한다.

## 6. 시험 코드

| 파일 | 건수 | 내용 |
|---|---|---|
| `qa/QaPopFixture.java` | — | 실제 POP(단말 2·작업자 2·지시 2) + 실제 WMS(위치·스위치·자재 입고 API) 고정 데이터 |
| `qa/PopWmsStockLinkQaTest.java` | 7(결함 1 `@Disabled`) | 1장 |
| `qa/LotHoldBoundaryQaTest.java` | 4 | 2장 |
| `qa/ESignQaTest.java` | 3 | 3장 |
| `qa/ApprovalDelegateQaTest.java` | 3 | 4장 |

- 시험 데이터 날짜는 `TestAccounts.COMP_TODAY`(회사 날짜), 위임 시험은 `BizClock.setClock` 고정 시계(끝나면 되돌림).
- 동시성 시험은 "모두 관찰한 뒤 진행" — 잡고 있는 트랜잭션이 있으면 `pg_stat_activity` 잠금 대기 수를 보고 놓고, 스파이 래치는 첫 칸 잠금 뒤 두 요청이 다 올 때까지.
- `결함QB1_…` 은 `@Disabled` 를 잠시 풀어 **지금 실패함**(`[200, 500]`, 40P01)을 확인했다.

## 부록 A. QS-1 재현 스크립트 (커밋하지 않은 일회용 — 시험 코드는 qa 패키지 Java 만이라는 규칙)

```js
// node select-race.js — 저장소 루트에서
const vm = require('node:vm'), fs = require('node:fs');
const ROOT = 'src/main/resources/static/js/common/';
// 가상 DOM: el(tag) = {children, attrs, value, textContent, appendChild, getAttribute, selectedIndex(setter → value)}
// fetch 는 URL 별로 미뤄 두었다가 시험이 순서를 정해 푼다(messages / codes)
// 1) hb.js · hb.numbers.js · hb.input.js 를 vm 에서 실행
// 2) sel(data-code=CMS_FAIL_TYPE, data-blank=select) → hb.select(sel) → hb.loadMessages()   (fail-code-mgt.js 의 순서)
// 3) codes 응답 먼저 → await api.ready → sel.children[0].textContent   ⇒ "js.common.choose"
// 4) messages 응답 → await → sel.children[0].textContent               ⇒ "js.common.choose" (다시 그리지 않음)
```
