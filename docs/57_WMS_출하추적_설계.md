# 57. 출하 추적 조회(리콜) 설계 — docs/49 6장 6

> 2026-10-29 상위 지시(새 3번)·결정을 코드 전에 모양으로 옮긴다. **문서만.** 상위 검토(7장) 뒤 구현.
> 결정(상위 2026-10-29): LOT 계보는 MES 새 읽기 계약 `LotTraceQuery`((가) — MES 작은 PR, WMS 는 `lot_genealogy` 를 새로 직접 읽지 않음, 기존 예외 `LotStockMapper` 는 늘리지 않음) / 출하 쪽은 WMS 계약 `ShipQuery.byLots`(WMS 소유, 읽기만) / 화면은 QMS(리콜·클레임 흐름)에서 두 계약을 부른다.
> 기준 main c432e34c. MES 계약은 **#675(`LotTraceQuery`, 사용자 결정 C-1 대기 — 아직 병합 전)의 모양** 기준. 근거는 코드 위치로. 코드로 확인 안 된 판단은 **[추정]**.

## 0. 요약

- **무엇**: 문제 LOT(원자재·반제품·완제품) 번호를 넣으면 → 그 LOT 과 **계보로 이어진 아래 LOT**(그 LOT 을 써서 만든 것) → 그 LOT 들이 **어느 고객에게 어느 출하로 얼마나** 나갔는지, 아직 창고에 남은 것은 어디에 얼마인지 한 화면에.
- **왜**: 리콜·클레임 때 "누구에게 연락해야 하나" 를 원장 조건 조회로 손으로 이어 붙이지 않게(docs/49 2장 표 "추적": 지금은 원장 조건 조회뿐).
- **경계**: WMS 는 출하·재고만, MES 는 계보만, QMS 는 화면·흐름. 서로 표를 직접 읽지 않는다.

## 1. 지금 있는 것 (조사 2026-10-29)

| 대상 | 위치 | 비고 |
|---|---|---|
| LOT 기본 정보 | MES `LotLookup`(production/link) — `byNo`·`lots(ids)` | 있음 |
| 계보 추적 | MES 내부 `LotService.trace(lotId, FWD/BACK)` → `LotMapper.trace`(재귀 CTE, 역분개 제외, 순환 표시, depth·limit) | **계약 `LotTraceQuery` — MES PR #675(병합 전)** |
| 출하 LOT | `wms_ship_pick.lot_id` → `wms_ship_line` → `wms_ship`(고객·출하 번호), 원장 `inv_trx` SHIP(−, `src_type = SHIP_PICK`, `src_id = 피킹`)·역분개 | WMS 안 |
| 팔레트·SSCC | `wms_ship_pick.pallet_id` → `wms_pallet`(번호·SSCC) | WMS 안 |
| 남은 재고 | `StockQuery.lotStatusQty(lotId)`(상태별, 품질 연동 공장만) | 공장 제한 있음 → 2.2 의 `lotStock` 로 보완 |
| 기존 계약 모양 | `ShipQuery.shippedByLine`(영업용), `NONE` + `of(ObjectProvider)` | 같은 모양으로 메서드 추가 |

## 2. 모양

### 2.1 흐름 (QMS 화면 서비스 한 트랜잭션, 읽기만)

```
LOT 번호들 ─ LotLookup.byNo ─▶ 뿌리 LOT id
           ─ LotTraceQuery.forward(뿌리 ids, maxDepth) ─▶ TraceResult(rows(뿌리, LOT, 깊이, via, 종류, cycle), truncated) ← MES
           ─ LotLookup.lots(나온 LOT ids) ─▶ LOT 번호·품목(줄에 보일 글자)                                  ← MES
           ─ ShipQuery.byLots(뿌리 + 아래 LOT ids) ─▶ LOT × 출하(고객·번호·확정 시각·순 수량·팔레트/SSCC) ← WMS
           ─ ShipQuery.lotStock(같은 ids) ─▶ LOT × 공장·칸·상태·수량(창고에 남은 것)          ← WMS
```

### 2.2 WMS 계약 `ShipQuery` 에 더하는 것 (default 메서드 — `NONE` 은 빈 목록)

```java
int LOTS_MAX = 500;

/** LOT 이 나간 출하 — LOT × 출하 지시 한 행. 순 수량 = 그 LOT·그 지시의 SHIP 원장 합의 부호 반대(역분개 포함 — 출하 취소분은 빠지고 0 인 행은 없음) */
record LotShipment(long lotId, long shipId, String shipNo, String plantCd, long custId, String custCd, String custNm,
                   OffsetDateTime shippedAt, long itemId, BigDecimal qty, String unitCd, List<String> palletNos, List<String> ssccs) {}

default List<LotShipment> byLots(Collection<Long> lotIds) { return List.of(); }

/** LOT 이 아직 창고에 있는 곳 — LOT × 공장 × 칸 × 재고 상태, 수량 > 0 만(출하 대기 칸 포함 — 피킹했지만 아직 안 나간 것) */
record LotStockRow(long lotId, String plantCd, long nodeId, String path, String locUsage, String stockStatus, BigDecimal qty) {}

default List<LotStockRow> lotStock(Collection<Long> lotIds) { return List.of(); }
```

- `lotStock` 은 **가용 정의(`AvailSql`)를 쓰지 않는다** — 리콜은 "쓸 수 있나" 가 아니라 "어디 있나". QC·HOLD·BLOCKED 상태, STAGING·QC·SCRAP 용도 칸 재고도 상태·칸 용도(`locUsage`)별로 그대로 보인다(상위 검토 2026-10-29).
- 둘 다 호출자 회사 범위(RLS)·읽기만·잠금 없음·**예외 없음**(없는·다른 회사 LOT 은 빠짐), `LOTS_MAX` 초과는 IllegalArgumentException(LotLookup 과 같은 규칙), 중복·null 무시.
- 정렬: `byLots` = lot_id, 확정 시각, ship_id / `lotStock` = lot_id, 공장, 경로.
- 근거 SQL(구현 때): `inv_trx t join wms_ship_pick p on t.src_type = 'SHIP_PICK' and t.src_id = p.pick_id and t.trx_type = 'SHIP'`(역분개 행도 같은 출처 — `InventoryService.reverseAll` 이 원 행의 `src_type`·`src_id` 를 그대로 씀, InventoryService.java:283) → `wms_ship_line` → `wms_ship`(status 무관 — 순 수량으로 판정) → `mst_cust`, 팔레트는 `string_agg distinct`. 색인 `ix_inv_trx_*` 출처 색인 확인[추정 — 구현 때 EXPLAIN].

### 2.3 MES 계약 `LotTraceQuery` (#675 모양 — MES 몫, 여기서는 쓰는 법만)

```java
record TraceRow(long rootLotId, long lotId, int depth, Long viaLotId, String relType, boolean cycle) {}
record TraceResult(List<TraceRow> rows, boolean truncated) {}
TraceResult forward(Collection<Long> lotIds, int maxDepth);   // MAX_IDS 500 · MAX_DEPTH 10 · MAX_ROWS 5000, 살아 있는 계보만, 뿌리 = depth 0
```

- 화면은 `truncated` 를 반드시 보인다(노란 줄 "계보가 상한에 걸려 일부만") — 리콜 범위가 조용히 덜 보이면 안 된다.
- 줄에는 LOT 번호·품목이 없다 → `LotLookup.lots(ids)` 로 채운다(500 개씩 나눠서).
- 같은 LOT 이 여러 뿌리·경로로 나오면 출하는 LOT 기준 한 번만 조회하고, 화면에서 뿌리별로 다시 묶는다.

### 2.4 화면 (QMS — 제안)

| 항목 | 내용 |
|---|---|
| prog | `qms-lot-trace`(QMS > 리콜·추적) [추정 — 이름은 QMS 관례에 맞춤] |
| 입력 | LOT 번호 여러 개(붙여넣기 줄바꿈), 깊이(기본 10) |
| 결과 1 — 고객별 요약 | 고객 · 출하 수 · 순 수량 · 관련 LOT 수 → 연락 대상 |
| 결과 2 — 출하 목록 | 뿌리 LOT · 출하 LOT(깊이·경로) · 고객 · 출하 번호 · 확정 시각 · 수량 · 팔레트/SSCC |
| 결과 3 — 창고에 남은 것 | LOT · 공장 · 칸 · 상태 · 수량 (→ 보류는 기존 MES 보류·QMS 흐름으로, 이 화면은 조회만) |
| 내보내기 | 엑셀(EXCEL 권한) — 고객 연락용 |
| 잘림 | 계보·결과가 상한에 걸리면 노란 줄로 "일부만" |

- **읽기만**: 이 화면은 아무것도 바꾸지 않는다. 보류·회수 지시는 범위 밖(기존 LOT 보류·NCR·클레임).

## 3. 하지 않음

- 거꾸로 추적(완제품 → 원자재 공급사)은 MES 화면(`LotService.trace BACK`)에 이미 있음 — 이 화면은 정방향 + 출하만.
- 고객 반품 입고·고객 측 재고, SN 단위(W-5 하지 않음), 회수 지시·알림 발송.

## 4. 시험 (구현 PR)

| 묶음 | 경우 |
|---|---|
| `byLots` | 한 LOT 두 지시 / 한 지시 두 LOT / 부분 출하 / 출하 취소 → 빠짐 / 취소 뒤 다시 출하 → 한 행 / 팔레트·SSCC / 비관리 품목(lot_id null)은 안 나옴 / 중복·null id / 501개 → 예외 |
| `lotStock` | 여러 칸·상태, 출하 대기 칸 포함, 0 은 없음 |
| **A사로 B사** | B사 범위로 A사 LOT id → 빈 목록 |
| NONE | WMS 없는 설치본 → 빈 목록 |
| 화면(QMS) | 계보 2단계 → 아래 LOT 출하까지 / 잘림 표시 / 권한 / 엑셀 |

## 5. 단계

1. 이 문서 → 상위 검토.
2. WMS PR: `ShipQuery.byLots`·`lotStock` + `WmsShipQuery` 구현 + 시험(마이그레이션 없음 — 색인이 필요하면 임시 V9321 → V215).
3. MES PR: `LotTraceQuery` — #675(사용자 결정 C-1 대기).
4. QMS 화면 PR: 2·3 위에(누가 — 상위 배정).

### 5.1 구현 (2026-10-30)

- `ShipQuery` default 메서드 `byLots`·`lotStock` + 레코드 `LotShipment`·`LotStockRow`, 구현 `WmsShipQuery`·`WmsLinkMapper.shipsByLots`·`stockByLots`.
- 색인 V215(임시 V9321) `ix_wms_ship_pick_lot (comp_cd, lot_id) WHERE lot_id IS NOT NULL` — LOT → 피킹 → 원장(출처 색인 `ix_inv_trx_src`). 원장에는 LOT 색인이 없어 피킹에서 시작한다.
- 시험 `ShipTraceQueryTest` 4: 한 LOT 두 지시·한 지시 두 LOT·피킹만(출하 전)은 안 나옴·비관리 품목 안 나옴·출하 취소 → 빠짐·다시 확정 → 한 줄 / 팔레트 번호 / lotStock 모든 상태·칸 용도(출하 대기 칸 피킹분·검사 칸 QC)·다 나간 LOT 없음 / 빈 입력·501 → 예외·500 은 됨·A사로 B사 빈 목록·NONE.
- QMS 화면(T-4)은 이 PR 병합 뒤 상위가 QMS 세션에 배정.

## 6. 결정할 것 (상위 검토)

| # | 질문 | 제안 |
|---|---|---|
| T-1 | 화면 위치 | QMS(리콜·클레임 흐름), WMS·MES 는 계약만 |
| T-2 | 남은 재고도 같이? | 예 — `lotStock`(품질 연동 스위치와 무관하게 모든 공장) |
| T-3 | 출하 취소분 | 순 수량 0 이면 행 없음(취소 이력은 출하 화면에서) |
| T-4 | QMS 화면 구현 주체 | QMS 세션(또는 상위 배정) — WMS 는 계약·시험까지 |
| T-5 | 상한 | LOT 500·깊이 10·계보 줄 5,000 — #675 와 같게. `byLots`·`lotStock` 은 LOT 500 개씩 나눠 부름 |

## 7. 검토 (상위)

- 2026-10-29 상위: T-1~T-5 **제안대로 승인**. T-3(출하 취소분 제외)은 리콜 관점에서 맞음 — 취소된 출하는 고객에게 가지 않았다. 추가 1건 반영(2.2): `lotStock` 은 가용 정의를 쓰지 않고 QC·HOLD·STAGING 등 모든 상태·칸 용도를 그대로 보인다(`locUsage` 열). 구현 PR 은 MES #675 병합(사용자 결정 C-1) 뒤, QMS 화면(T-4)은 구현 PR 병합 뒤 상위가 QMS 세션에 배정.
