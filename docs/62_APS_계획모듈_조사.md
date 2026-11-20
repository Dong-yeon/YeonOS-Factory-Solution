# 62. 계획(APS) 모듈 조사 — 생산계획에는 무엇이 있어야 하나

> 작성 2026-11-02 · 상위 병합 세션 · 상태: **조사·제안(사용자 결정 대기)**
> 계기: 사용자 "계획 쪽 모듈이 많이 부족하다 — 안 해 본 쪽이라 인터넷 자료를 더 분석해 보자" (2026-11-02)
> 관련: docs/38(APS 1~3단계 설계·구현), docs/32(PMS 아님 — 혼동 주의), 영업 내시·수주(docs/25 2단계)

## 1. 결론 먼저

- 생산계획은 보통 **위에서 아래로 6~7층**이다(APICS/ASCM 계획 계층). 큰 그림에서 시작해 날마다 할 일까지 내려간다.
  - S&OP: 월·제품군 수준의 판매·생산 합의
  - 수요 관리: 내시(고객 예측)와 수주를 합친다
  - **MPS**(기준생산계획): 품목 × 주·일 계획 수량
  - RCCP(개략 능력 점검): MPS 가 핵심 설비 용량 안인지 본다
  - **MRP**(자재소요계획): BOM 을 전개해 무엇을 언제 얼마나 만들고·사야 하는지 계산한다
  - CRP·부하: 설비별 시간대 부하
  - **상세 일정(스케줄링)**: 유한 용량 순서
  - 배정·실행 피드백: 근무조 작업 목록, 계획 대비 실적
- **YeonOS APS 는 맨 아래 "상세 일정" 한 층만 있다**(docs/38).
  - 있는 것: 이미 만든 작업지시를 설비·근무조 용량 안에서 순서 짓는 유한 용량 순방향 스케줄, 계획 버전, 반영, 비교, 간트 고정, 납기 회신(CTP), 자재 가용 확인.
  - 그래서 **"무엇을 몇 개, 언제 만들지(MPS)"와 "자재를 언제 얼마나 사거나 만들지(MRP)"를 정하는 층이 비어 있다.** 지금은 사람이 작업지시를 직접 만들어야 APS 가 돌기 시작한다.
- 추천 우선순위는 아래와 같다(사용자 결정 대기 — 8장).
  1. **MPS + MRP(자재소요)** — 내시·수주에서 계획 주문(생산 지시 후보·구매 요청 후보)을 만든다. 가장 큰 빈칸이고, 설명하기도 가장 좋다.
  2. **부하 보기 + 계획 준수율** — 설비 × 주 부하 막대, 계획 대비 실적. docs/61 차트와 같이 간다.
  3. **스케줄러 고도화** — 순서에 따른 셋업(품목 바뀔 때만 준비 시간), 디스패치 규칙 선택(납기·여유·임계비), 후방 일정, 설비별 사이클 타임.
  4. S&OP(월·제품군)는 보류.

## 2. 계획 계층 — 조사 요약

출처 대부분은 APS·ERP 벤더 글과 CPIM(ASCM 자격) 학습 자료다. 층 순서와 각 층의 역할은 출처끼리 일치하고, 기간(몇 주·몇 달)은 출처마다 다르다.

| 층 | 질문 | 단위·주기 | 대표 결과물 |
|---|---|---|---|
| S&OP | 이번 분기 제품군별로 얼마나 팔고 만들까 | 제품군 × 월, 3~18개월 | 합의된 월 계획 |
| 수요 관리 | 고객 예측(내시)과 확정 주문을 어떻게 합칠까 | 품목 × 일·주 | 예측 소진(수주가 예측을 갉아먹음), 확정 구간 |
| **MPS** | 어떤 완제품을 언제 몇 개 만들까 | 품목 × 주·일 | 계획 생산량, 예상 재고(PAB), ATP(약속 가능량) |
| RCCP | 그 MPS 가 병목 설비 용량을 넘지 않나 | 핵심 자원 × 주 | 과부하 경고 |
| **MRP** | 그러려면 반제품·원자재를 언제 얼마나 만들고 사야 하나 | BOM 전개 × 일·주 | 순소요, **계획 주문**(생산·구매), 로트 크기, 안전재고, **페깅**(이 소요가 어느 수요에서 왔나), **예외 메시지**(당겨라·밀어라·취소) |
| CRP·부하 | 계획 주문·지시가 설비별로 얼마나 차나 | 설비 × 일·주 | 부하율 |
| 상세 일정 | 설비마다 어떤 순서로, 몇 시에 | 공정 × 분·시 | 간트, 시작·끝 시각 |
| 배정·피드백 | 지금 근무조는 무엇을 하나, 계획대로 됐나 | 근무조 | 작업 목록, 계획 준수율 |

**자동차 부품사 맥락(2·3차 협력사)**
- 고객은 EDI 830(예측·계획 일정)으로 확정 수량과 예측을 함께 보내고, 862(출하 일정)·866(조립 순서)으로 좁혀 온다.
- 공급사는 이것을 MPS·MRP 에 넣고, 밤마다 MRP 를 돌리는 예가 흔하다(QAD 사례).
- **확정 구간(frozen fence)**: 시작 1~4주 전부터는 계획을 바꾸지 않는다. 3구간(고정·조정 가능·예측)으로 나누는 모델이 있다.

**상세 일정 쪽 고급 기능**
- 셋업 매트릭스: 앞 품목 → 다음 품목에 따라 준비 시간이 다르다.
- 같은 계열 묶기, 병목 중심 일정(TOC 의 Drum-Buffer-Rope), 가정 시나리오 비교(what-if), 고정한 공정 주변만 다시 계산.
- 디스패치 규칙
  - 납기 순(EDD): 지연을 줄인다.
  - 짧은 작업 먼저(SPT): 흐름 시간을 줄이지만 긴 작업이 밀린다.
  - 임계비(CR): 남은 시간 ÷ 남은 작업, 매번 다시 계산한다.
  - **어느 하나가 늘 이기지 않는다.** 지표에 따라 다르다(학술 결과).

## 3. YeonOS 지금 상태 (코드·docs/38 확인)

| 층 | 상태 | 근거 |
|---|---|---|
| S&OP | 없음 | — |
| 수요 관리 | **일부** — 영업 내시(`sal_forecast_rev`·`line`)·수주(`sal_order`). 예측 소진·확정 구간 없음 | docs/25 2단계 |
| MPS | **없음** | — |
| RCCP | 없음 | — |
| MRP | **없음**. APS 의 `MaterialPlanner` 는 "이 지시를 만들 자재가 있나" 확인만 하고, 계획 주문을 만들지 않음 | `module/aps/MaterialPlanner` |
| CRP·부하 | **일부** — 스케줄 결과에 자원별 막대는 있으나 설비 × 주 부하율 보기 없음 | `aps-plan` 간트 |
| 상세 일정 | **있음** — 유한 용량 순방향, 규칙 1개(우선순위 → 납기 → 계획 시작 → 번호), 가장 빨리 끝나는 설비, 빈자리 끼워 넣기, 근무조 달력, 외주 리드타임, 계획 버전·반영·비교·간트 고정·CTP | docs/38 1~12장 |
| 배정·피드백 | 없음(근무조 작업 목록은 POP 에 지시 단위로만, 계획 준수율 KPI 없음) | — |

docs/38 이 "하지 않을 것"으로 미뤄 둔 것은 다음과 같다.
- 공정 간 이동·대기, 겹침 생산, 공정 분할
- **순서 의존 셋업**
- 설비별 근무조, **설비별 사이클 타임 차이**
- 후방 일정
- 고정 일괄 해제 등

## 4. 개선 후보

| # | 후보 | 내용 | 데이터(있음/새) | 규모 | 마이그레이션 |
|---|---|---|---|---|---|
| **A1** | **MPS(기준생산계획)** | 품목 × 일(또는 주) 격자. 수요 = 내시 + 수주(예측 소진 규칙), 공급 = 기존 지시·재고. 예상 재고(PAB)·ATP 를 계산하고, 사람이 계획 수량을 고쳐 확정한다. 확정 구간(정책 N일)은 잠근다 | 내시·수주·재고·지시 있음 / MPS 버전 표 새로 | 중~대 | 있음 |
| **A2** | **MRP(자재소요)** | 확정 MPS + 수주를 BOM(유효 리비전)으로 전개 → 순소요(재고·발주 잔량·지시 잔량 차감, 안전재고) → 리드타임 역산 → **계획 주문**(생산 = 지시 후보, 구매 = 구매 요청 후보). 로트 크기(그대로·고정·최소), 페깅(수요까지 거슬러 보기), 예외 메시지(당김·밀기·취소) | BOM·재고·SRM 발주·지시 있음 / MRP 실행·계획 주문 표 새로 | 대 | 있음 |
| A3 | 계획 주문 → 실행 | 생산 계획 주문 → 작업지시 만들기(MES 서비스 경유), 구매 계획 주문 → 구매 요청(SRM 계약) | 지시·SRM 있음 | 중 | 작음 |
| **B1** | 부하 보기(CRP·RCCP) | 설비(또는 설비군) × 주 부하율 막대(용량 = 근무조 달력), 100% 넘으면 표시. MPS 단계에서는 핵심 설비만 개략 점검 | 라우팅 표준 시간·달력 있음 | 중 | 없음 |
| **B2** | 계획 준수율 | 계획 시작·끝 vs 실적 시작·끝, 일별 계획 수량 vs 실적 수량 → 준수율 추세·지연 사유 | APS 계획·MES 실적 있음 | 소~중 | 없음 |
| C1 | 순서 의존 셋업 | 셋업 매트릭스(품목군 → 품목군 분) + 같은 계열 묶기 | 새 표 | 중 | 있음 |
| C2 | 디스패치 규칙 선택 | 계산할 때 규칙을 고름: 지금 규칙 / 납기 / 임계비 / 짧은 작업. 계획 비교로 지표(지연·흐름 시간)를 나란히 | 없음 | 소~중 | 작음 |
| C3 | 설비별 사이클 타임 | 같은 공정이라도 설비마다 CT·셋업이 다름 | 라우팅 설비 행 | 중 | 있음 |
| C4 | 후방 일정 | 납기에서 거꾸로 일정 → 늦게 시작해도 되는 시점 | 없음 | 중 | 없음 |
| C5 | 설비 비가동 반영 | 설비 상태 원장(계획 정지·고장)을 용량에서 뺌 | MES 설비 상태 있음 | 소~중 | 없음 |
| C6 | 최적화 엔진 | 지금 규칙 기반 목록 스케줄 대신 제약 최적화(예: **Timefold Solver**, Apache-2.0, OptaPlanner 후속) | 의존성 추가 | 대 | 없음 |
| D1 | S&OP | 제품군 × 월 판매·생산 합의 | 새 표 | 중 | 있음 |

## 5. 추천 순서와 이유

1. **A1 MPS → A2 MRP → A3 실행 연결**
   - 지금은 사람이 지시를 직접 만들어야 계획이 시작된다. MPS·MRP 가 들어오면 "내시·수주 → 무엇을 만들고 무엇을 사나 → 지시·구매 요청"이 한 줄로 이어진다. 그 뒤에 지금 APS(상세 일정)가 붙는다.
   - 면접에서 설명할 이야기가 가장 크다: 계획 계층, 순소요, 페깅, 확정 구간.
   - 단계 수(PR)는 A1 1~2개, A2 2~3개, A3 1개.
2. **B1 부하 + B2 준수율** — 화면이 차트 중심이라 docs/61 의 차트 도구 결정(C-1) 뒤에 한다.
3. **C2 → C5 → C1·C3** — 작은 것부터. C6(최적화 엔진)은 C 묶음이 끝나도 부족할 때 PoC 한다.
4. D1 S&OP 는 보류(제품군 마스터·재무 연결이 필요하고, 협력사 MES 범위에서 가치가 낮다).

## 6. 다른 모듈과의 경계(초안)

| 대상 | 원칙 |
|---|---|
| 영업(내시·수주) | 읽기만. 예측 소진 결과는 APS 표에 둔다 |
| BOM(기준정보) | `BomTreeService` 전개 계약을 쓴다(유효 리비전, 기준일) |
| 재고(WMS) | 가용·발주 잔량은 공개 조회 계약으로만 |
| 구매(SRM) | 계획 주문 → 구매 요청은 SRM 계약 신설(SRM 소유) |
| 생산(MES) | 계획 주문 → 작업지시 만들기는 MES 공개 서비스 메서드(지금 APS 반영과 같은 방식) |

## 7. 하지 않을 것(지금)

| 제외 | 이유 |
|---|---|
| 수요 예측 알고리즘(통계·AI 예측) | 협력사는 고객 내시를 받는다. 예측을 만들 필요가 작다 |
| 다공장·공급망 네트워크 계획 | 범위 밖 |
| EDI 830/862 수신 | 연계(ERP·EDI) 범위 — 내시는 화면·엑셀 입력으로 시작 |

## 8. 결정 요청 (사용자)

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| AP-1 | 첫 범위 | **A1 MPS → A2 MRP → A3 실행 연결** | B(부하·준수율) 먼저 / C(스케줄러 고도화) 먼저 |
| AP-2 | MPS 시간 단위 | **일 단위**(협력사는 일 출하가 기본, 화면에서 주 합계로 접기) | 주 단위 |
| AP-3 | 확정 구간 | **정책으로 N일(기본 7일) 고정 구간 — 그 안은 계획 수량을 바꾸려면 사유 필수** | 구간 없음 / 3구간 모델 |
| AP-4 | MRP 결과 처리 | **계획 주문을 사람이 골라 지시·구매 요청으로 만든다(자동 생성 안 함)** | 확정 구간 밖은 자동 생성 |
| AP-5 | 담당 | **APS 하위 세션을 새로 열어 맡김**(docs/29 규칙상 사용자가 엶) | 기존 MES 세션에 붙임 |

## 9. 출처

- 계획 계층: ASCM CPIM 학습 자료 발췌 https://learningsystem.ascm.org/wp-content/uploads/2019/11/CPIM_LS_2020_Excerpt-2.pdf · MRP II(위키백과) https://en.wikipedia.org/wiki/Manufacturing_resource_planning · RCCP https://www.horizonsolutions.ai/supply-chain-planning/what-is-rough-cut-capacity-planning · CRP https://usersolutions.com/blog/capacity-requirements-planning-crp · CPIM MRP 학습 https://open-exam-prep.com/study-guides/cpim/mrp-capacity/material-requirements-planning · CPIM 상세 일정 https://open-exam-prep.com/study-guides/cpim/detailed-schedules/creating-production-service-schedules
- APS 개요: https://www.planettogether.com/aps/best-practices/types-of-scheduling-in-production-planning-and-control · https://www.symestic.com/en-us/what-is/production-planning-software · https://onsector.de/en/info/articles/aps · https://vti.com.vn/advanced-planning-and-scheduling/ · 분산 APS 논문 https://arxiv.org/pdf/1107.3225
- MRP 개념(순소요·로트·페깅·예외): https://www.cleverence.com/articles/for-business/mrp-concepts-6341/ · https://www.cleverence.com/articles/oracle-documentation/overview-of-material-requirements-planning-4729/
- Odoo MPS·작업장 용량: https://octurasolutions.com/resources/mrp-planning-in-odoo-19-master-production-scheduling-and-capacity-analysis · https://apps.odoo.com/apps/modules/16.0/mrp_master_production_schedule
- 상세 일정·셋업·DBR·what-if: https://www.symestic.com/en-us/what-is/finite-scheduling-vs.-aps · https://www.oplit.com/blog/drum-buffer-rope-dbr-theory-of-constraints-scheduling · https://scholar.sun.ac.za/handle/10019.1/2008 · https://arxiv.org/pdf/2402.14832 · https://e2btek.com/advanced-planning-scheduling-software-aps/
- 디스패치 규칙: https://www.fabrico.io/blog/production-sequencing-dispatching-rules/ · Waterloo 학위논문 https://uwspace.uwaterloo.ca/bitstream/handle/10012/8940/Brown_Andrew.pdf?sequence=2
- 자동차 협력사·확정 구간·EDI: https://www.tacto.ai/en/procurement-glossary/frozen-planning-fence · https://lightsource.ai/glossary/master-production-schedule-(mps) · https://e2btek.com/automotive-edi · EDI 830 https://www.tracelink.com/orchestration-integration-transactions/transform-your-supply-chain-seamless-edi-integration/edi-ansi-x12-830-forecast-planning-schedule.md · QAD 사례 https://www.casestudies.com/company/qad/case-study/tier-1-automotive-supplier-fully-automates-mrp-with-qad
- 최적화 엔진: Timefold Solver(Apache-2.0) https://github.com/TimefoldAI/timefold-solver · https://timefold.ai/open-source-solver

## 10. 검토 결과

| # | 결정(사용자, 2026-11-02 — 모두 추천안) |
|---|---|
| AP-1 | 첫 범위 = A1 MPS → A2 MRP → A3 실행 연결 |
| AP-2 | MPS 일 단위(화면 주 합계 접기) |
| AP-3 | 확정 구간 정책 N일(기본 7일), 안은 사유 필수 |
| AP-4 | 계획 주문은 사람이 골라 지시·구매 요청으로(자동 생성 안 함) |
| AP-5 | APS 하위 세션을 새로 열어 맡김(사용자가 엶 — docs/29 결정 ②) |
