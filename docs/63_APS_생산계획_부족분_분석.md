# 63. APS 생산계획 부족분 분석 — 화면 실측과 SAP PP·Odoo 대조

> 작성 2026-11-02 · APS 하위 세션(브랜치 `claude/aps-work`) · 상태: **분석·제안(사용자 결정 대기), 코드 변경 없음**
> 계기: 상위 세션 지시 "MPS·MRP·자원 부하·일일 작업 배정·계획 확정 흐름 중 무엇이 부족한지 공개 레퍼런스 근거로 정리"(2026-11-02)
> 관계: **docs/62(계획 모듈 조사, 상위 클라우드 세션 2026-11-02, 브랜치 `claude/elegant-carson-ys2zdp` — 이 문서 작성 시점 main 미병합)** 가 계획 계층(S&OP → MPS → RCCP → MRP → CRP → 상세 일정 → 배정)과 후보 A1~D1·결정 AP-1~5 를 이미 냈다.
> 이 문서는 그 위에 **(1) 지금 화면을 실제로 띄워 본 결과 (2) SAP PP·Odoo 의 구체 동작과 1:1 대조 (3) 62 에 없는 빈칸(계획 기준정보·일일 배정·계획 확정 흐름)** 만 더한다. 62 와 같은 후보는 번호만 적고 다시 설명하지 않는다.
> 겹침 확인: docs/35 A6(간이 생산계획), docs/47 "계획 대비 실적"·"수주 기반 자재 소요" 행, docs/51(차트), docs/52 #31(APS = 중소 기준 같음, 솔버 제외) — 참조만.

## 1. 결론 먼저

- 지금 APS 는 **"이미 있는 지시를 언제·어느 설비에서"** 까지는 SAP PP-DS·Odoo 계획 간트와 견줄 만하다(유한 용량, 고정, 비교, 반영, CTP). 빈 곳은 그 **위(무엇을 몇 개)** 와 **아래(오늘 현장이 무엇부터)** 다.
- 화면 실측에서 가장 눈에 띄는 것: **자재 부족 표가 "604 EA 부족 · 기간 안 없음"까지 보여 주고 끝난다** — 다음 행동(구매 요청·반제품 지시)으로 이어지는 버튼이 없다. SAP MD04·Odoo MPS 는 같은 화면에서 계획 주문을 만들고 바꾼다.
- 62 에 없는 빈칸 세 가지:
  1. **계획 기준정보가 없다** — 품목에 안전재고·로트 크기(그대로/고정/최소/배수)·자체 생산 리드타임·확정 구간이 없다(`mst_item` 확인). 구매 쪽만 `srm_source.lead_days·moq·order_mult` 가 있다. **MPS·MRP 어느 것을 하든 먼저 필요**하다.
  2. **일일 작업 배정이 계획을 모른다** — POP 작업 목록은 `priority → plan_start → wo_no → op_seq` 순(`PopMapper.xml` 194행)이라, APS 가 정한 설비별 순서·시각이 현장 목록에 나오지 않는다. 반영(A7)은 날짜·설비만 옮긴다.
  3. **"계획 확정" 이 한 번의 반영뿐이다** — 공장별 "지금 따르는 계획"(현행 계획) 개념·확정 구간(동결)·확정 뒤 변경 요청이 없다. SAP 는 planning time fence + firming, Odoo 는 MPS 확정 수량으로 이것을 한다.
- 추천 순서(결정 대기, 8장): **G1 계획 기준정보 → G2 부족 → 구매 요청(작은 MRP) → 62 A1 MPS → 62 A2·A3 MRP·실행 연결**, 그 사이에 **G5 일일 배정 목록**과 **G6 현행 계획·확정 구간**을 끼운다. 부하(62 B1)·준수율(62 B2)은 차트 결정(docs/61) 뒤.

## 2. 화면 실측 (2026-11-02, 로컬 8090 — 보기만, 계산·반영 안 함)

환경: 다른 세션이 띄운 `claude/ui-local-fixes` 앱(main a3213600 근처), 데모 회사 공장 P1. 이 세션은 데이터를 만들지 않았다(조회·API GET 만).

| # | 본 것 | 판단 |
|---|---|---|
| S1 | 들어가면 목록이 비어 있다 — 계산일 기본 기간이 지난 7일이라 10-26 에 계산한 유일한 계획(`APS261026-001`)이 안 보인다. 시작일을 10-20 으로 넓혀야 나온다 | 작은 화면 마감 거리(G9) — 기본을 "최근 계획 N건" 이나 30일로 |
| S2 | 공장 P1 의 계획 대상 지시가 **1건**(공정 4개, 설비 2대 + 외주) | 데모 데이터가 얇아 MPS·부하·순서 문제를 보여 줄 수 없다(G10) |
| S3 | 간트(주·일·근무조 확대): 공정 10 이 SPOT-101 에서 하루를 넘겨 이어지고, 외주 공정 30 은 "외주" 행에 24시간 막대 | 1~4단계 설계대로 동작 |
| S4 | 자재 표: 품목 5개, 필요 1,200 · 할당 596 · **부족 604** · 충족 시각 "기간 안 없음"(입고 예정 포함 켬) | **부족을 보이고 끝 — 다음 행동 없음**(G2). 다단(반제품) 소요는 계산 안 함(A33) |
| S5 | 지시별 결과: 납기 11-13(수주), 끝 10-29 14:20, "납기 안 · 자재 부족" | 늦음 판정은 동작. 계획 대비 실적 열은 없음(62 B2) |
| S6 | 자원별 부하율 표시 없음 — 간트 막대로만 짐작 | 62 B1. 결과 표만으로 계산 가능(G4) |
| S7 | 상세에 [반영 미리보기]·[폐기], 목록 위 [납기 답변] | 반영 뒤 "현행 계획" 표시·확정 구간 없음(G6) |

## 3. 레퍼런스 대조 — 계획 흐름 단계별

| 단계 | SAP PP(ERP·S/4HANA) | Odoo 17 MRP | YeonOS 지금 | 차이 |
|---|---|---|---|---|
| 수요 입력 | 계획 독립 소요(PIR) + 판매 오더, 전략 40 = 판매 오더가 PIR 을 소진(앞·뒤 소진 일수) [SAP-40] | MPS 격자의 "예측 수요" 칸 + 확정 판매 [O-MPS] | 내시 `sal_forecast_rev/line`(DAY/WEEK/MONTH, `firm_yn`)·수주 `sal_order_line` | 내시와 수주를 **합치는 규칙(소진)** 없음 — 62 A1 |
| 계획 기준정보 | 자재 마스터 MRP1~4 뷰: MRP 유형·로트 크기 절차(EX 그대로·FX 고정·기간 TB/WB/MB, 최소·최대·반올림)·안전재고·자체 생산 시간·planning time fence [SAP-LOT][SAP-PTF] | MPS "Safety Stock Target·Min/Max to Replenish", 보충 규칙 [O-MPS][O-RR] | **없음**(`mst_item` 에 계획 칸 없음). 구매만 `srm_source.lead_days·moq·order_mult` | **G1** |
| MPS | MD43/MD61 + MPS 실행, planning time fence 안 제안은 자동 변경 안 함 [SAP-PTF] | 품목 × 일/주/월 격자, 보충 = 안전재고 목표 − 시작 재고 + 예측 수요 + 간접 수요, [Replenish] 로 MO/PO 생성 [O-MPS] | 없음 | 62 A1 |
| MRP 실행 | 순소요 → 로트 크기 → 리드타임 역산 → **계획 오더(생산)·구매 요청(구매)**, BOM 다단 전개 [SAP-MD04] | 보충 규칙(자동/수동)·MTO 경로·MPS 중 하나 — **MPS 와 보충 규칙을 함께 쓰지 말 것** [O-STR] | `MaterialPlanner` = 지시 1단 자재 가용 확인·부족 표시만(A27~A34) | 62 A2, **G2**(작은 첫걸음) |
| 결과 검토 | MD04 재고/소요 목록 — 수요·공급 시간순 + **예외 메시지**(10 당김·15 밀기·20 취소 등)와 재일정 제안일 [SAP-MD04][SAP-EXC] | MPS 칸 색(초록 = 만들어야 함, 회색 = 이미 있음, 노랑 = 부족, 빨강 = 넘침) [O-MPS] | 자재 표(부족·충족 시각) | 예외 메시지 = 62 A2 의 일부. G2 에서 "부족 → 행동" 먼저 |
| 계획 확정 | **확정(firming)**: 확정 구간 안 제안은 계획 실행이 바꾸지 않음, 수동 확정일로 구간 연장 [SAP-FIRM] | 확정된 MO 는 MPS 가 다시 제안하지 않음(회색) [O-MPS] | 계획 버전 계산 → 사람이 [반영] 1회(APPLIED). 확정 구간·현행 계획 없음 | **G6** |
| 실행 변환 | 계획 오더 → 생산 오더, 구매 요청 → 구매 오더(MD04 에서 직접) [SAP-MD04] | MPS [Replenish] / 보충 보고서에서 [Order Once] → 초안 MO/PO [O-RR] | 지시는 작업지시 화면에서 수주 행 골라 직접, 구매 요청 `srm_pr` 은 사람이 입력 | 62 A3, G2 |
| 부하·평준화 | CM01(작업장 부하 %) → CM21 계획표에서 끌어 놓기·자동 이동으로 평준화 [SAP-CRP] | 작업장 용량·대체 작업장(용량 차면 자동 전환), 작업장별 계획 간트 [O-WC] | 유한 용량 스케줄 + 끌어 놓기 고정, **부하율 표시 없음** | 62 B1, **G4** |
| 현장 배정 | 생산 오더 작업 순서 → 작업장별 디스패치 목록(CM25) | 작업장별 작업 지시(Shop Floor) — 계획 시작 순 | POP 목록 = 우선순위·계획일 순(**APS 순서 아님**) | **G5** |
| 피드백 | 계획 대비 실적 보고 | 작업 지시 실적·OEE | 홈 지연 건수만 | 62 B2 / docs/47 "계획 대비 실적" |

## 4. 부족분 후보

62 와 같은 것은 62 번호로만 적는다. 새 후보는 G 번호. 규모: 소 = PR 1개·며칠, 중 = PR 1~2개, 대 = PR 3개 이상.

### 4.1 새 후보 (62 에 없음)

| # | 후보 | 내용 | 근거 | 규모 | 마이그레이션 | 필요한 사용자 결정 | 우선 |
|---|---|---|---|---|---|---|---|
| **G1** | **계획 기준정보** | 품목 × 공장: 안전재고, 로트 규칙(그대로 EX / 고정 FX / 최소·배수), 자체 생산 리드타임(일), 확정 구간(일). 구매 리드타임·MOQ·배수는 `srm_source` 를 그대로 읽는다(중복 금지) | SAP MRP1~2 뷰·로트 절차 [SAP-LOT], Odoo 안전재고 목표·Min/Max [O-MPS] — MPS·MRP 모두 이 값 없이는 계산 불가 | 소~중 | **있음** — 새 표 | 표 주인: **APS 표 `aps_item_param`(추천)** vs `mst_item` 칸(기준정보 세션, 남의 표) (AP-6) | **1** |
| **G2** | **부족 → 구매 요청 초안** (작은 MRP) | 계획 자재 표에서 부족 줄을 골라 [구매 요청 만들기] → `srm_pr` DRAFT(필요일 = 필요 시각 − 구매 리드타임 표시, 수량 = 부족을 MOQ·배수로 올림). 반제품(MAKE) 부족은 "지시 필요" 로만 표시 | 화면 S4 — 지금은 부족을 보고 끝. Odoo 보충 보고서 [Order Once], SAP MD04 에서 바로 구매 요청 [O-RR][SAP-MD04] | 소~중 | 작음 — 요청 줄 출처 칸(`srm_pr_line` 에 계획 출처, **SRM 표**) 또는 APS 쪽 연결 표 | 출처 연결을 SRM 표에 둘지 APS 연결 표에 둘지 (AP-7). 자동 생성 안 함(62 AP-4 와 같은 원칙) | **2** |
| G3 | 다단 소요(반제품 지시가 공급) | 지금 A33 "다단 안 함" — SFG 지시를 공급으로 보고, SFG 부족은 상위 소요로 | SAP·Odoo 모두 BOM 다단 전개가 기본 | 중 | 없음(결과 사본 칸 추가 가능) | 62 A2 안에 넣을지 따로 할지 (AP-8) | 62 A2 와 같이 |
| **G4** | **부하율 표(결과에서 계산)** | 계획 결과(`aps_plan_op.setup_sec + run_sec`)와 계산 때 근무 구간(`work_windows`)으로 자원 × 일 부하율 표를 만든다(100% 넘음은 유한 용량이라 없음 → **가동률·여유** 표, 무한 능력 비교는 62 B1). 화면은 표 먼저, 차트는 docs/61 결정 뒤 | SAP CM01 부하 % [SAP-CRP], Odoo 작업장 부하 [O-WC], 화면 S6 | 소 | **없음** — 조회만 | 표만 먼저 할지 62 B1(무한 능력 부하) 와 같이 할지 (AP-9) | 3 |
| **G5** | **일일 작업 배정 목록** | 반영된 계획의 설비별 공정 순서·계획 시각을 POP·출력용 배정표로. 방법 ⓐ **APS 읽기 계약 `ApsDispatchQuery.sequence(plantCd, day)`**(반영 계획 결과를 읽음, POP 가 정렬에 씀 — MES 표 무변경) ⓑ A7 다른 안 `prd_wo_proc.plan_start_at`(MES 표 변경) | POP 정렬 근거 `PopMapper.xml` 194행. SAP CM25 디스패치·Odoo 작업장 작업 목록 [SAP-CRP][O-WC]. docs/47 B사 POP "순위" 정렬 요구(참조) | 중 | ⓐ 없음 / ⓑ 있음(MES) | ⓐ/ⓑ, 반영 뒤 현장이 순서를 어기면 막을지·표시만 할지 (AP-10) | 3 |
| **G6** | **현행 계획·확정 구간** | 공장별 "현행 계획" 1개(마지막 반영 계획), 확정 구간(G1 의 일수 또는 공장 정책) 안 지시는 다음 반영이 날짜를 바꾸지 않는다(미리보기에 "확정 구간 — 그대로"), 바꾸려면 사유와 함께 고정(A22) 으로. 홈·POP 이 "현행 계획 기준" 을 읽는 기준점 | SAP planning time fence·firming [SAP-PTF][SAP-FIRM], 62 AP-3 확정 구간(MPS 쪽)과 같은 개념을 상세 일정 쪽에도 | 소~중 | 작음 — APS 표 칸(`aps_plan.current_yn` 류) | 확정 구간 기준(품목별 G1 / 공장 정책 하나) (AP-11) | 2 (G5 앞) |
| G7 | 재일정 예외 메시지 | 지금 지시의 계획 완료일과 MRP/계획 결과를 비교해 "당김·밀기·취소" 제안 목록 | SAP 예외 10·15·20 [SAP-EXC] | 중 | 없음 | 62 A2 안 | 62 A2 와 같이 |
| G8 | 계산 비동기·밤 계획 실행 | 지금 계산은 요청 안 동기(A8 상한 31일·지시 1,000). MRP 는 밤 일괄이 흔하다 | 62 2장 QAD 사례 | 중 | 작음(실행 기록) | MRP 가 들어올 때 정함 | 뒤 |
| G9 | 화면 마감: 조회 기본 기간 | S1 — 계산일 기본 7일이면 최근 계획이 안 보임 | 화면 실측 | 소 | 없음 | 없음(추천: 최근 30일) | 아무 때나 |
| G10 | 계획 데모 시더 | 공장 하나에 지시 20~40건·내시·수주·재고·발주가 겹치는 시나리오(늦음·부족·병목이 보이게) | S2 — 지금 데모로는 MPS·부하·순서 문제를 보일 수 없다. docs/61 데모 시더 결정과 같은 방식 | 소~중 | 없음(시더 코드) | 시나리오 범위 | G1 과 같이 |

### 4.2 62 후보에 덧붙이는 구체(설계 때 쓸 메모)

| 62 # | 덧붙임 |
|---|---|
| A1 MPS | 수요 = 내시 확정분(`firm_yn = Y`)은 그대로, 예측분은 수주가 소진(SAP 전략 40 의 뒤·앞 소진 일수와 같은 매개변수 2개 [SAP-40]). 보충 계산식은 Odoo 식(안전재고 목표 − 시작 재고 + 예측 수요 + 간접 수요)을 출발점으로 [O-MPS]. MPS 는 **사람이 고치는 표**라 APS 계산 결과의 "추가만" 원칙(A1)과 다르다 — 버전 머리는 추가만, 칸은 확정 전까지 수정 가능 + 감사 |
| A2 MRP | 로트 규칙은 G1 의 EX·FX·최소·배수 4개로 시작(SAP 기간 로트 TB/WB/MB 는 뒤) [SAP-LOT]. 페깅은 계획 주문 → (수주 행·내시 줄·상위 지시) 연결 표. Odoo 경고대로 "보충 규칙과 MPS 를 같이 켜지 않게" — 품목별 계획 방식 하나(G1 칸) [O-STR] |
| A3 실행 | 생산 = `WoService.create` 경유(지금 반영과 같은 원칙, 지시 표 무변경), 구매 = `PrService.create` 경유(G2 와 같은 길) |
| B1 부하 | G4(결과 기반 가동률)와 무한 능력 부하(MPS·계획 지시 기준)를 한 화면 두 모드로 |
| B2 준수율 | 분모 결정은 docs/47 "계획 대비 실적" 행과 같은 질문 — 현행 계획(G6) 이 있으면 분모 = 현행 계획 |

## 5. 추천 순서와 이유

1. **G1 계획 기준정보 + G10 시더** — 어떤 계획 기능도 이 값 없이는 "왜 이 수량인가" 를 설명할 수 없다. 작고 위험이 적다.
2. **G2 부족 → 구매 요청** — 지금 화면의 막다른 길(S4)을 바로 잇는다. 62 A2 MRP 의 구매 쪽을 1단만 먼저 하는 셈이라 버려지는 코드가 없다.
3. **G6 현행 계획·확정 구간 → G5 일일 배정 목록** — 계획이 현장까지 내려가는 길. G5 는 G6 의 "현행 계획" 이 있어야 무엇을 읽을지 정해진다.
4. **62 A1 MPS → A2 MRP(G3·G7 포함) → A3** — 62 의 추천 순서 그대로. G1·G2 를 먼저 하면 A2 의 구매 쪽이 이미 있다.
5. **G4 부하율 표** 는 조회만이라 아무 때나 끼울 수 있다. 차트·62 B1·B2 는 docs/61 차트 결정 뒤.
6. 62 C(스케줄러 고도화)·D1(S&OP)·G8 은 62 판단 그대로 뒤.

**62 AP-1 과의 관계**: 62 는 "A1 MPS 먼저" 를 추천한다. 이 문서는 그 앞에 G1(기준정보)·G2(작은 MRP)를 두자는 안이다 — MPS 를 먼저 하더라도 G1 은 MPS 의 안전재고·로트 칸으로 어차피 필요하다.

## 6. 경계 (62 6장에 덧붙임)

| 대상 | 원칙 |
|---|---|
| 품목 계획 칸(G1) | APS 표에 두면 기준정보 화면은 읽기 링크만. `mst_item` 에 두면 기준정보 세션 몫(남의 표) |
| 구매 요청(G2·A3) | `PrService.create` 공개 메서드로만 — APS 가 `srm_pr` 에 직접 쓰지 않는다. 출처 연결이 SRM 표에 필요하면 SRM 세션과 |
| POP 정렬(G5) | ⓐ 안은 APS 가 읽기 계약을 내고 POP(MES 세션)가 부른다 — APS 가 POP 매퍼를 고치지 않는다 |

## 7. 하지 않을 것

- 62 7장 그대로(통계 예측·다공장·EDI 수신).
- 최적화 솔버(docs/52 #31 [제외], 62 C6 PoC 후보로만).
- 계획 기준정보를 ERP 처럼 수십 칸(MRP 유형·조달 유형·재주문점 ·…)으로 늘리는 것 — G1 은 5칸 안팎에서 시작.

## 8. 결정 요청 (사용자) — 62 의 AP-1~5 다음 번호

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| AP-6 | 계획 기준정보(G1) 표 주인 | **APS 표 `aps_item_param`(품목 × 공장)** — 계획 전용 값, 남의 표 변경 없음 | `mst_item` 칸 추가(기준정보 세션) — 품목 화면 한 곳에서 보지만 남의 표 |
| AP-7 | 부족 → 구매 요청(G2) 출처 연결 | **APS 쪽 연결 표**(계획 자재 줄 ↔ 요청 줄) — SRM 표 무변경 | `srm_pr_line` 에 출처 칸(SRM 세션) — 요청 화면에서 출처가 바로 보임 |
| AP-8 | 다단 소요(G3) | **62 A2 MRP 와 함께**(전개 엔진 하나) | 지금 `MaterialPlanner` 에 먼저 — 나중에 MRP 와 규칙이 둘 |
| AP-9 | 부하 표(G4) | **표 먼저(조회만), 차트는 docs/61 뒤** | 62 B1 과 한꺼번에 |
| AP-10 | 일일 배정(G5) 방식 | **ⓐ APS 읽기 계약 + POP 정렬은 MES 세션**, 순서 어김은 표시만 | ⓑ `prd_wo_proc.plan_start_at`(MES 표) / 순서 어김 막기 |
| AP-11 | 확정 구간(G6) 기준 | **공장 정책 하나(기본 2일) 먼저, G1 품목별 값이 있으면 그것 우선** | 품목별만 / 구간 없음 |
| AP-12 | 첫 범위(62 AP-1 과 함께) | **G1 + G10 → G2 → G6 → G5**, 그 뒤 62 A1~A3 | 62 AP-1 추천(A1 MPS 먼저) 그대로 |

## 9. 출처

공개 자료만. 계획 계층·APICS/ASCM CPIM·EDI·디스패치 규칙·Timefold 출처는 docs/62 9장을 따른다(중복 생략).

- [SAP-40] SAP Help, Planning with Final Assembly (40) — https://help.sap.com/docs/SAP_ERP/74c0b3a391174482a66a3a23bb28c10d/3923bf53d25ab64ce10000000a174cb4.html
- [SAP-PTF] SAP Help, Planning Time Fence — https://help.sap.com/docs/SAP_ERP/85d3fce10e264972a0155c8b46ecf93b/12adce5314894208e10000000a174cb4.html
- [SAP-FIRM] SAP Help, Firming Procurement Proposals — https://help.sap.com/docs/SAP_ERP/66326f67e0e1416d83c0fdfa4060189d/c798b6535fe6b74ce10000000a174cb4.html
- [SAP-LOT] SAP Help, Lot-Sizing Procedures — https://help.sap.com/docs/SAP_ERP/66326f67e0e1416d83c0fdfa4060189d/bc97b6535fe6b74ce10000000a174cb4.html
- [SAP-MD04] SAP Help, MRP List and Stock/Requirements List (S/4HANA) — https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/fe39e10a9a864a8f8dc9537704f0fa13/0819ba53422bb54ce10000000a174cb4.html · Advanced MD04 — https://help.sap.com/doc/2d1f4f3d24da48d7b3d0d842e7c0ab2d/2025.1/en-US/MD4_EN.pdf
- [SAP-EXC] SAP Press, How to Evaluate MRP Results with SAP S/4HANA — https://blog.sap-press.com/how-to-evaluate-mrp-results-with-sap-s4hana · MRP Exception Monitor — https://help.sap.com/doc/80c6ad471d2d42598c766e3ef5c1fac4/2023.1/en-US/ERM-EN-PUBLIC.pdf
- [SAP-CRP] (요약·2차 자료) SAP PP Capacity Planning CM01·CM21 — https://www.guru99.com/capacity-requirement-planning-sap-pp.html · https://www.tutorialspoint.com/sap_pp/sap_pp_capacity_leveling.htm
- [O-MPS] Odoo 17 문서, Master production schedule — https://www.odoo.com/documentation/17.0/applications/inventory_and_mrp/manufacturing/workflows/use_mps.html (보충 계산식은 19.0 문서 https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/manufacturing/workflows/use_mps.html)
- [O-STR] Odoo 17 문서, Replenishment strategies — https://www.odoo.com/documentation/17.0/applications/inventory_and_mrp/inventory/product_management/product_replenishment/strategies.html
- [O-RR] Odoo 17 문서, Reordering rules — https://www.odoo.com/documentation/17.0/applications/inventory_and_mrp/inventory/warehouses_storage/replenishment/reordering_rules.html · MTO — https://www.odoo.com/documentation/17.0/applications/inventory_and_mrp/inventory/product_management/product_replenishment/mto.html
- [O-WC] Odoo 17 문서, Manage work orders using work centers — https://www.odoo.com/documentation/17.0/applications/inventory_and_mrp/manufacturing/advanced_configuration/using_work_centers.html

코드 근거(이 저장소): `mst_item`(V013 — 계획 칸 없음), `srm_source`(V043 — `lead_days·moq·order_mult`), `sal_forecast_line`(`bucket_type`·`firm_yn`), `srm_pr`·`srm_pr_line`(DRAFT~CLOSED), `PopMapper.xml` 작업 목록 정렬, `module/aps/MaterialPlanner`(1단·부족 표시), `WoService.create`·`PrService.create`(공개 서비스).

## 10. 검토 결과

2026-11-09 상위 세션 · 사용자 결정 "APS (docs/63)"를 3차 묶음에 넣음 — **8장 추천안 그대로 채택**.

| # | 결정 |
|---|---|
| AP-6 | 채택 — APS 표 `aps_item_param`(품목 × 공장), 남의 표 변경 없음 |
| AP-7 | 채택 — APS 쪽 연결 표(계획 자재 줄 ↔ 요청 줄), `PrService.create` 경유, SRM 표 무변경 |
| AP-8 | 채택 — 다단 소요는 62 A2 MRP 와 함께(이번 범위 아님) |
| AP-9 | 채택 — 부하 표는 조회만, 차트는 docs/61 뒤(이번 범위 아님) |
| AP-10 | 채택 — 일일 배정은 ⓐ 읽기 계약 방식(이번 범위 아님) |
| AP-11 | 채택 — 확정 구간은 공장 정책 하나(기본 2일), G1 품목별 값 우선(이번 범위 아님) |
| AP-12 | 채택 — 순서 G1 + G10 → G2 → G6 → G5 |

**3차 범위(APS 하위 세션)**: G1 계획 기준정보 + G10 계획 데모 시더 → G2 부족 → 구매 요청 초안 + G9 조회 기본 기간(최근 30일). G6·G5·62 A1~A3 는 다음 차수.
