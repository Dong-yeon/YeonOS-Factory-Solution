# QA 2026-10-24 — 깊은 offset 페이지 + select 목록 행별 계산 (WMS 수불 원장 시간 초과와 같은 모양)

> 독립 QA 세션(docs/29 8장), 상위 요청. **분석만 — 코드 수정 없음.** 기준 main `f5474e55`.
> 표시: **[코드]** 매퍼 XML 을 읽어 확인. **실측 없음** — 행 수·시간은 추정이다. 고칠 것은 상위가 모듈 세션에 배정한다.

## 0. 무엇을 찾았나

WMS 실측에서 수불 원장(`InvMapper#findTrxs`) 100만 행의 offset 50만 쪽이 30초 시간 초과가 났다. offset 이 건너뛰는 행마다 select 목록의 부질의·함수를 계산했기 때문이다. WMS 는 지연 조인(안쪽에서 id 만 offset, 바깥에서 50행만 계산)으로 고친다(docs/30 5.1).

같은 모양을 매퍼 전체에서 찾았다.

**방법 [코드]:**
1. `src/main/resources/mapper/**/*.xml` 의 `<select>` 중 `offset` 이 있는 문장 **84개**를 모았다. `<include>` 조각은 펼쳤다.
2. offset 이 걸린 쿼리 층의 select 목록에서 상관 부질의 `(select …)`·`lateral`·업무 함수(`hb_*`·`wms_*`)·`string_agg` 를 셌다.
3. 주 표가 원장처럼 커지는 표인지 따로 봤다.
4. 고위험 후보는 문장을 직접 읽었다.

**판단 기준:**
- **높음** — 원장처럼 커지는 표 + 행마다 큰 계산(같은/다른 원장 표 집계, 재귀 함수). 또는 그 계산 값으로 거르거나 정렬해서 지연 조인만으로는 안 풀린다.
- **중간** — 행마다 계산이 있지만 표가 덜 커지거나 계산이 작다(색인 한 번).
- **낮음** — 부질의 없이 조인만 있다. 깊은 offset 자체의 선형 비용만 남는다. 또는 표가 작다.

부질의가 쓰는 색인은 있다 [코드]. 예: `ix_cms_alarm_log_equip`, `ix_lot_genealogy_parent`, `ix_cms_counter_reading_counter`, `ix_prd_result_proc`, `ix_qms_spc_event_act_event`. 문제는 색인이 아니라 **건너뛰는 행 수 × 행당 계산**이다.

## 1. 높음

| # | 매퍼 · 문장 id | 커질 표 | 행마다 계산(select 목록) | 기간 제한 | 제안 |
|---|---|---|---|---|---|
| H1 | `production/LotMapper.xml` · `findLotPage` | `lot_master` (POP 실적·입고마다 LOT) | `hb_lot_balance(lot_id)` — `lot_genealogy` 합. 잔량 조건(`hb_lot_balance(…) > 0`)이 WHERE 에도 있다 | 366일(`LotService.MAX_PERIOD_DAYS`) | 지연 조인으로 표시용 잔량은 바깥 50행만. 잔량 조건이 켜지면 후보 전체를 계산하는 것은 그대로 — 잔량 칸(계보 트리거가 유지)이나 "잔량 있음" 부분 색인 후보 |
| H2 | `cms/CmsAlarmMapper.xml` · `findAlarmPage` | `cms_alarm_log` (설비가 자동으로 올림 — 가장 빨리 커짐) | `window_count` = 같은 `cms_alarm_log` 상관 `count(*)`(같은 설비·코드·시간창) + 알람 정의 `lateral` | 92일(`AlarmService.MAX_DAYS`) | 지연 조인으로 `window_count` 를 바깥 50행만. 검색어가 `d.alarm_nm` 을 보므로 정의 `lateral` 은 검색어가 있을 때만 안쪽에 둔다 |
| H3 | `qms/SpcEventMapper.xml` · `findGrid` | `qms_spc_event` (규칙 위반마다) | 상태 = `exists(qms_spc_event_act …)` 3개, 최근 조치자·시각 부질의 2개. **상태로 거르고(`v.status in`) 정렬** | **없음** — WHERE 에 날짜 조건 없음, 원장 전체 | 지연 조인만으로는 부족(상태 조건·정렬이 계산 값을 쓴다). ① 상태 칸(조치 때 갱신) 또는 조치 `lateral` 하나로 합치기 ② 기간 필수(다른 원장 화면처럼) |
| H4 | `cms/CmsPmMapper.xml` · `findRunPage` | `cms_chk_run` (점검 회차 누적) | `counter_total` = 그 계수기 **판독 전체 합**(`cms_counter_reading` 누적 — 회차마다 같은 계수기를 반복 계산) + `ng_cnt`(점검 결과 count). `select * from (…) x` 바깥에서 정렬·offset | 366일(`ChkRunService.MAX_DAYS`) | 지연 조인 + 계수기 합을 계수기별 한 번(CTE·`lateral` 를 바깥에서) — 또는 계수기 누계 칸 |
| H5 | `qms/SpcMapper.xml` · `findUnitGrid` | `qms_insp` × `qms_insp_line` (판정된 검사 전체) | offset 과 별개로 **매 페이지 전체 GROUP BY**. 그룹마다 관리 한계 부질의 | **없음** | 기간 제한, 또는 관리 단위 요약 표(검사 판정 때 갱신). 지연 조인으로는 안 풀린다(집계가 먼저) |

## 2. 중간

| # | 매퍼 · 문장 id | 커질 표 | 행마다 계산 | 제안 |
|---|---|---|---|---|
| M1 | `cms/CmsCounterMapper.xml` · `findCounterPage` | 행은 `cms_counter`(적음), 계산은 `cms_counter_reading`(판독 누적) | 판독 `sum`·`count`·최근 판독 `lateral`·최근 게이지 부질의 — 계수기마다 판독 전체 | 계수기 수가 적어 offset 영향은 작다. 판독이 커질수록 **페이지마다 전체 판독 합** — 계수기 누계 칸(판독 트리거) 후보 |
| M2 | `production/ReceiptMapper.xml` · `woPage` | `prd_wo` | 마지막 공정 `hb_proc_qty`(`prd_result` 합 8개를 함수 안에서) | 지연 조인(바깥 50행만 `hb_proc_qty`) |
| M3 | `wms/InvMapper.xml` · `findStock` | `inv_stock` (칸 × 품목 × LOT) | `wms_node_path(node_id)` — 재귀 CTE | WMS 수불 원장과 같은 방식(지연 조인). WMS 세션이 같이 보면 된다 |
| M4 | `srm/NotifyMapper.xml` · `findNoticePage` | `sys_outbox` (모든 아웃박스) | `payload ->> …` 6번 — 큰 jsonb 는 행마다 TOAST 해제 | 지연 조인(안쪽 `outbox_id` 만) |
| M5 | `srm/AsnMapper.xml` · `findAsnPage` | `srm_asn` | 행 수 count + 줄별 입고 합(`srm_po_receipt`) | 지연 조인. 문서 표라 커지는 속도는 느림 |

## 3. 낮음 (부질의 없는 큰 표, 또는 작은 표)

| 매퍼 · 문장 id | 비고 |
|---|---|
| `admin/LogMapper.xml` · `findAccess`·`findAudit` | `sys_access_log` 는 요청마다 커진다. select 목록 계산은 없고 조인만 있다 — 깊은 offset 의 선형 비용만 남는다. 아주 커지면 키셋(occurred_at, log_id) 후보 |
| `cms/CmsStateMapper.xml` · `findReviewPage` · `cms/EquipCountMapper.xml` · `findPage` | 설비 상태·카운트 원장. 조인만 있고 기간 조건이 있다 |
| `qms/InspMapper.xml` · `findGrid` · `qms/NcrMapper.xml` · `findPage` · `wms/LabelMapper.xml` · `findJobs` | 조인만 |
| `wms/ShipMapper.xml` · `findShipPage` · `wms/CountMapper.xml` · `findCountPage` | `wms_node_path` + 줄 count. 업무 문서 표라 느리게 커진다 — WMS 가 M3 와 함께 |
| `sales/SalesMapper.xml` · `findOrderPage` · `srm/PoMapper.xml` · `findPoPage` · `srm/AckMapper.xml` · `findPosPage`·`findStatusPage` · `srm/ClaimMapper.xml` · `findClaimPage` · `production/WoMapper.xml` · `findWoPage` | 머리 표 + 줄 count(색인 한 번). 머리 표는 느리게 커진다 |
| `admin/AdminMapper.xml` · `findUserPage` 외 기준정보 목록(약 40개) | 표가 작다(사용자·품목·설비·기준정보) |

## 4. 공통 제안 (상위 판단)

- **지연 조인 규칙**: 원장 표 목록은 "안쪽 = id·정렬 키만 거름·정렬·offset, 바깥 = 그 50행에 조인·부질의·함수". WMS(docs/30 5.1)와 같은 방식이다. 정렬·거름에 계산 값을 쓰는 문장(H1 의 잔량 조건, H3 의 상태)은 계산 값을 칸으로 옮겨야 지연 조인이 먹힌다.
- **기간 필수**: H3·H5 는 원장 전체를 본다. 다른 원장 화면(92·366일)처럼 기간을 필수로.
- **깊은 offset 상한(플랫폼)**: `PageQuery` 에 offset 상한(예: 1만 행)을 두고, 넘으면 "기간·조건을 좁혀 주세요" 로 400 을 내는 안. 화면이 수십만 행 뒤를 여는 일은 업무상 드물고, 시간 초과(500)보다 낫다 — 플랫폼 결정.
- **가드 후보**: 원장 표 목록(`inv_trx`·`lot_master`·`cms_alarm_log`·`qms_spc_event`·`sys_outbox`·`cms_chk_run` …)의 offset 문장에서 바깥 select 목록의 `(select`·`lateral`·`hb_`/`wms_` 함수를 막는 가드. 이번 스캔 스크립트와 같은 방식(84문장 중 위 표의 것만 걸린다).
- **실측 우선순위**: H2(알람 — 가장 빨리 커짐) → H1(LOT) → H3·H5(기간 없음) → H4. 시험은 `-Dhb.perf=true` 실측 틀(엑셀 5만 행처럼)로 같은 크기를 만들어 보는 것을 권한다.

## 5. 가드 — `OffsetLedgerGuardTest` (2026-10-24 후속, 상위 지시)

위 스캔을 시험으로 만들었다: `src/test/java/io/smarthappy/guard/OffsetLedgerGuardTest.java`(처음엔 qa/ — 상위 지시로 guard/ 로 옮김).

**판정 규칙.** offset 이 있는 `<select>` 에서 offset 이 걸린 쿼리 층(include 펼침, 동적 태그 제거)을 본다. 다음이 있으면 "행별 계산" 이다.
- select 목록의 `(select`·업무 함수(`hb_/wms_/…(`)·`string_agg` 등·jsonb `->>`
- from ~ offset 사이의 `lateral`

행별 계산이 있고 다음 둘 중 하나면 위반이다.
- ① 주 표(파생 표면 그 안의 첫 표)가 원장 표 목록에 있다.
- ② 행별 계산 글자가 원장 표나 원장을 읽는 함수(`hb_lot_balance`·`hb_proc_qty`)를 쓴다.

원장 표 목록(19개)은 근거와 함께 시험 안에 적었다.

**허용 목록.** 지금 걸리는 문장을 `@Disabled` 가 아니라 허용 목록에 넣었다. 그래서 새 문장은 바로 걸리고, 고쳐진 문장은 "목록에서 빼라" 로 실패한다. 줄마다 배정 모듈·풀릴 PR 을 적었다.

| 문장 | 배정 |
|---|---|
| `wms/InvMapper#findTrxs`·`#findStock` | WMS(지연 조인 PR) |
| `production/LotMapper#findLotPage`·`ReceiptMapper#woPage` | MES |
| `cms/CmsAlarmMapper#findAlarmPage`·`CmsPmMapper#findRunPage`·`CmsCounterMapper#findCounterPage`·**`CmsPmMapper#findPlanPage`** | CMS |
| `qms/SpcEventMapper#findGrid`·`SpcMapper#findUnitGrid` | QMS |
| `srm/NotifyMapper#findNoticePage`·`AsnMapper#findAsnPage` | SRM(실측 뒤 결정) |

**가드가 찾은 것 — M6.** `cms/CmsPmMapper.xml#findPlanPage` 는 PM 계획 행마다 그 계수기의 판독 전체 합(`cms_counter_reading`)을 계산한다. M1 과 같은 모양이고, 처음 스캔(1~3장)에서는 빠졌다. CMS 배정이 필요하다.

**검사기 자체 시험** — 걸려야 하는 것:
- 조각(include) 안의 부질의
- 작은따옴표 id
- 파생 표 안의 부질의
- 작은 표가 원장을 읽는 부질의
- 원장 위 `lateral`

통과해야 하는 것: 지연 조인(안쪽 id 만 offset), 작은 표의 count, 조인만 있는 원장, offset 없는 문장.

### 5.1 후속 — `AsnMapper#findAsnPage` 가 지연 조인 뒤에도 걸리는 이유 (2026-10-24, 상위 질의)

SRM #491 이 지연 조인으로 바꾼 뒤에도 가드가 이 문장을 잡는다. 상위는 "바깥 `cross join lateral` 이 원장 계산으로 잡히는 오탐" 인지 물었다.

**[재현]** 가드 검사기(`flatten`·`judge`)를 그대로 불러 확인했다.
- 지금 문장 → `행별 계산이 원장 srm_po_receipt 을 읽음`.
- `<if test="needOpen">`·`<if test="c.late">` 분기만 지운 문장 → 통과.

**판정**
- **바깥 lateral 은 원인이 아니다.** offset 이 안쪽 `k` 에 있으므로 가드는 안쪽 select 만 본다.
- 걸리는 곳은 안쪽의 `needOpen` 분기다. `open_line_cnt` 가 `srm_asn_line`·`srm_po_receipt` 를 집계한다.
- 가드는 동적 태그를 벗기고 내용은 남기므로(`flatten`), 조건부 분기도 늘 있는 것으로 본다.

**실제 위험인가 — 부분적으로 그렇다(오탐 아님)**
- 기본 경로(도착일 정렬·늦은 ASN 아님)는 고쳐졌다. 안쪽에 행별 계산이 없다.
- 덜 받은 줄 수로 정렬하거나 늦은 ASN 만 볼 때는 후보 전체에 입고 합을 계산한 뒤 offset 한다.
- 계산 값으로 정렬·거르기 때문에 지연 조인으로는 풀리지 않는다(H1 잔량 조건·H3 상태와 같은 부류). 풀려면 "덜 받은 줄 수" 칸(입고 때 갱신)이 필요하다.
- ASN 은 문서 표라 느리게 커진다. 그래서 지금 크기에서는 정책 판단으로 둘 수 있다.

**조치**
- KNOWN 에서 빼지 않는다. 사유만 "기본 경로 완료 · needOpen 분기 남음 · 정책 판단" 으로 바꿨다.
- 가드는 고치지 않는다. 동적 분기를 따로 판정하면 분기 안에 숨긴 행별 계산을 놓친다 — 엄격한 쪽이 맞다.
