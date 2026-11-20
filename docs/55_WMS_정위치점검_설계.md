# 55. WMS 정위치 점검·위치 정확도 설계 — docs/49 6장 3 (W-3)

> 2026-10-29 사용자 결정(docs/49 7장 W-3, 상위 전달)을 코드 전에 모양으로 옮긴다. **문서만 — 코드·마이그레이션 없음.** 상위 검토(9장) 뒤 구현 PR.
> 기준 main f44d2651. 근거는 코드 위치로 단다. 코드로 확인 안 된 판단은 **[추정]**.

## 0. 요약

- **무엇**: 창고 사람이 PDA 로 한 위치(칸)를 스캔하고, 그 칸에 있는 물건(LOT·품목 라벨)을 스캔한다. 서버가 장부(현재고)와 대조해 **정상·다른 위치·없음·미등록** 넷 중 하나로 판정한다. 그 칸을 다 봤으면 "전수 확인 완료" — 장부에는 있는데 스캔되지 않은 것이 **없음** 으로 채워진다.
- **왜**: 실사(수량 맞추기)와 달리 "물건이 장부의 그 칸에 있나" 만 본다 — 피킹 실패·잘못 둔 재고를 빨리 찾고, **위치 정확도 = 일치 칸 ÷ 확인 칸** 을 홈 카드로 본다.
- **결정(사용자 2026-10-29)**: 저장 = **별도 표** `wms_loc_check`(머리) + `wms_loc_check_line`(스캔 줄) — 실사와 섞지 않음 / 판정 4유형 + **판정 당시 장부 위치 저장** / 위치 전수 확인 1행 / **보정은 사람이 확인 뒤** "보정 이동" 버튼 → 사유 코드 `LOC_CORRECT` 이동 원장(자동 이동 아님), 보정 이력 삭제 금지 / LOT·품목·수량 대조는 "다른 위치" 판정 범위만(수량 맞추기는 실사) / 위치 정확도 홈 카드 / PDA 는 `/pop/wh` 체인 재사용(~~`hb.pop.queue`~~ → 물건 스캔은 **온라인만** — 7장 상위 검토).

## 1. 지금 있는 것 (조사 2026-10-29)

| 필요한 것 | 있는 것 | 근거 |
|---|---|---|
| 스캔 키 해석 | `LOC:`·`LOT:`·`ITEM:` + 정책 `WMS_SCAN_BARE_AS` | `scan/ScanKeyResolver`, `StockService.lookup` |
| PDA 체인 | `/api/pop/wh/**`(단말 체인·작업자 배지·`client_req_id` 멱등), 화면 `/pop/wh`(이동·실사·피킹·반품) | `pda/PdaApiController`·`PdaService`, `pda-home.js` |
| 오프라인 대기열 | `hb.pop.queue.send(url, body)` — IndexedDB 선기록, 같은 `clientReqId` 재전송, 거부 목록 | `common/hb.pop.js` — **이 기능은 쓰지 않음**(온라인만, 2.1) |
| 이동 원장 | `MOVE_OUT`/`MOVE_IN` 짝(`move_grp_id`), 칸 잠금 순서, 음수 재고 CHECK | `InventoryService.post`, `StockService.move` |
| 사유 코드 | 조정 `WMS_ADJ_REASON`(회사 확장 Y) — 이동에는 사유 없음 | V048 |
| 홈 카드 | `HomeCardProvider`(공장 카드 `plantScoped`) | `ship/ShipOpenCard` 등 |
| 실사 | 블라인드·재실사·한도 승인·차이 조정 — **수량** 이 대상 | `count/*`, V067·V074 |

- 장부 = `inv_stock`(키: 품목 × 위치 × LOT × 상태, 수량 0 행 남음). LOT 은 여러 칸에 나뉘어 있을 수 있다(같은 LOT 일부 이동).
- 시리얼(SN)은 없다(docs/49 W-5 결정 전) — **스캔 단위는 LOT 또는 품목**(2장 R-1).

## 2. 모양

### 2.1 흐름

1. PDA "정위치 점검" → **위치 스캔**(`LOC:`) — 재고를 둘 수 있는 칸(`stock_yn = Y`)만. 오늘(공장 날짜) 그 칸의 점검 머리가 없으면 만든다(OPEN), 있으면 이어 쓴다. 이미 DONE 이면 409 `LOC_CHECK_DONE`(같은 날 같은 칸 1회 — 정확도 분모를 흔들지 않음).
2. **물건 스캔**(`LOT:` 또는 `ITEM:`, 바코드 그대로) — 한 번 = 한 줄. 서버가 그 순간의 장부로 판정(2.2)하고 **장부 스냅숏**(그 LOT·품목이 있는 칸·상태·수량, 판정 당시)을 줄에 저장한다. 결과를 PDA 에 바로 보인다.
   - **온라인만**(상위 검토 2026-10-29): 판정이 "서버가 받은 때의 장부" 라, 오프라인 대기열에 쌓였다 늦게 가면 그 사이 이동·출고로 판정·스냅숏이 **조용히 틀린다**. 그래서 `hb.pop.queue` 를 쓰지 않는다(PDA 입고 온라인만 — docs/49 6장 2 와 같은 규칙). 오프라인이면 PDA 에 "연결됐을 때만 점검" 표시 + 스캔·완료 버튼 비활성. `clientReqId` 멱등은 그대로(응답이 끊겨 다시 보낼 때).
3. **전수 확인 완료** — 그 칸 장부(수량 > 0, 상태 무관)에 있는데 이 점검에서 스캔되지 않은 LOT·품목마다 **없음** 줄을 만든다. 머리 DONE + 집계(정상·다른 위치·없음·미등록 수, 일치 여부).
4. 사무 화면 "정위치 점검"(새 화면 `wms-loc-check`) — 불일치 줄 목록 → 줄마다 **보정 이동**(다른 위치만) 또는 **확인만**(사유) → 처리 기록. 보정은 사람이 누를 때만.

### 2.2 판정 (스캔 한 줄)

| 결과 | 조건(스캔한 칸 = C) | 저장 |
|---|---|---|
| **OK 정상** | 그 LOT(비관리 품목은 그 품목)의 재고(수량 > 0)가 C 에 있다 | 장부 스냅숏 |
| **MISPLACED 다른 위치** | 재고가 있지만 C 에는 없다(다른 칸에만) | 장부 스냅숏 — 보정 이동의 출발 칸 후보 |
| **UNKNOWN 미등록** | 키를 못 읽음·없는 LOT/품목·이 회사가 아닌 것(RLS 로 없음)·재고가 어디에도 없음(이미 나간 것이 남음) | 스캔 원문·이유 |
| **MISSING 없음** | (스캔이 아니라 완료 때 생성) C 장부에 있는데 스캔되지 않음 | 장부 스냅숏(그 칸 수량) |

- 대조 범위(결정): **위치만**. 수량은 보지 않는다 — OK 라도 수량이 다를 수 있고 그건 실사 몫. "다른 위치" 일 때만 LOT·품목·장부 수량을 보정 이동 기본값으로 쓴다.
- 같은 점검에서 같은 LOT(품목)을 두 번 스캔 → 새 줄을 만들지 않고 앞 줄을 돌려준다(`DUPLICATE` 표시). 같은 `clientReqId` 재전송은 같은 결과(멱등).
- 상태(QC·HOLD 등)는 판정에 쓰지 않는다 — 위치만 본다. 스냅숏에는 상태별로 남긴다.
- 출하 대기(STAGING) 칸 자체 점검: 허용(피킹된 물건이 그 칸에 있나 보는 것도 의미가 있다). 단 보정 이동의 **출발·도착 칸이 STAGING 이면 막는다**(피킹분은 출하 화면의 피킹 취소로 — 2.4).

### 2.3 표 (V204·임시 V9319, docs/29 2장)

**`wms_loc_check`**(점검 머리 — 칸 × 날짜 하나)

| 칸 | 내용 |
|---|---|
| `check_id`, `comp_cd`, `plant_cd`, `node_id` | 점검한 칸(같은 회사·공장의 `LOC`/`WH`, `stock_yn = Y` — 트리거) |
| `check_date` date | 공장 날짜(`BizClock`) — **UNIQUE (comp_cd, node_id, check_date)** |
| `status` | `OPEN` → `DONE`(되돌리지 않음 — 전이 트리거) |
| `worker_id` | 시작한 작업자(배지) |
| `ok_cnt`·`misplaced_cnt`·`missing_cnt`·`unknown_cnt`, `accurate_yn` | 완료 때 한 번 채움. `accurate_yn = Y` ⇔ 다른 위치·없음·미등록 0 |
| `started_at/by`, `done_at/by`, 감사·`row_ver` | |

**`wms_loc_check_line`**(스캔·판정 줄 — 원장처럼 쌓기만)

| 칸 | 내용 |
|---|---|
| `line_id`, `comp_cd`, `check_id` | |
| `scan_code` varchar(200), `scan_type` | 스캔 원문(MISSING 은 null)·`LOT`/`ITEM`/`OTHER` |
| `item_id`, `lot_id` | 판정 대상(미등록은 null 가능) |
| `result` | `OK`·`MISPLACED`·`MISSING`·`UNKNOWN` |
| `book` jsonb | **판정 당시 장부** `[{nodeId, status, qty}]`(그 LOT·품목의 수량 > 0 칸, 최대 50) |
| `scanned_at`(단말 시각)·`recorded_at`(서버)·`recorded_by`·`worker_id`·`client_req_id` | `client_req_id` 회사별 UNIQUE(멱등) |
| `review_action` | null → `CORRECTED`(보정 이동) 또는 `CONFIRMED`(확인만) — **한 번만**(트리거) |
| `review_reason`, `reviewed_at/by`, `move_grp_id` | 보정이면 이동 원장 짝의 `move_grp_id` |

- 두 표 모두 RLS(`tenant_isolation`)·DELETE 금지 트리거(보정 이력 삭제 금지 — 결정). 줄 UPDATE 는 `review_*`·`move_grp_id` 를 null 에서 한 번만 채우는 것만.
- 인덱스: `(comp_cd, plant_cd, check_date)`(목록·홈 카드), 줄 `(comp_cd, check_id)`, 처리 안 된 불일치 `(comp_cd, result) where review_action is null and result <> 'OK'`.
- 코드: `WMS_LOC_CHECK_RESULT`(OK·MISPLACED·MISSING·UNKNOWN), 이동 사유 그룹 새로 `WMS_MOVE_REASON`(회사 확장 N) = `LOC_CORRECT` — 조정 사유(`WMS_ADJ_REASON`, 회사 확장 Y)에 넣으면 조정 화면 선택지에 섞인다.

### 2.4 보정 이동 (사람이 확인 뒤 — 결정)

- 대상: `MISPLACED` 이고 `review_action` 이 null 인 줄. 사무 화면에서 출발 칸(스냅숏 중 하나, 기본 = 가장 많은 칸)·수량(기본 = 그 칸 지금 수량, 그 이하)·상태(출발 칸 그 상태 그대로)를 보고 **[보정 이동]**.
- 원장: `MOVE_OUT`(출발 칸)·`MOVE_IN`(점검한 칸) 짝, `src_type = 'LOC_CHECK'`, `src_id = line_id`, `reason_cd = 'LOC_CORRECT'`, 발생 시각 = 지금. `InventoryService.post` 그대로(잠금 순서·음수 CHECK·기간 마감·혼적 검사 `mixCheck`).
- 줄: `review_action = CORRECTED`, `move_grp_id`, 처리자·시각. 같은 줄 두 번 → 409(트리거 + 서비스).
- 막는 것: 출발·도착 칸이 STAGING(400 `STAGING_LOCKED` — 피킹분), 보류 LOT(HOLD) 출발(이동 규칙 그대로 `LOT_HOLD`), 지금 출발 칸 수량 < 요청(409 `INSUFFICIENT_STOCK` — 그새 바뀜), 다른 공장 칸.
- `MISSING`·`UNKNOWN`·(보정하지 않을) `MISPLACED` → **[확인만]**(사유 필수, 원장 없음). 없어진 재고를 맞추는 것은 실사·조정 화면(수량은 실사 몫 — 결정).

### 2.5 위치 정확도 (홈 카드)

- 정의: 기간 안 DONE 점검 중 `accurate_yn = Y` 칸 수 ÷ DONE 점검 칸 수(같은 날 같은 칸 1회 — UNIQUE). 기간 = 공장 오늘까지 30일[추정 — 데모에서 정함].
- 카드 `wms-loc-accuracy`(공장 카드, 화면 권한 `wms-loc-check`): 숫자 = **처리 안 된 불일치 줄 수**(사람이 할 일), 보조 = 확인 칸·일치 칸, 항목 = "정확도 n%(30일)" + 처리 안 된 불일치 오래된 순. 정확도만 숫자로 두면 할 일이 안 보인다.
- 링크: 점검 화면(공장·"미처리" 필터).
- 전날(또는 그 전) OPEN 으로 남은 머리 — 완료하지 않은 점검은 **정확도 분모에서 빠진다**(DONE 만 셈). 목록에서 "완료 안 됨" 으로 보인다.

### 2.6 동시성·잠금 (상위 검토 2026-10-29)

- 같은 칸 같은 날 PDA 두 대: **줄 추가는 같은 OPEN 머리에 허용**(UNIQUE 머리 하나 — 머리 만들기 경합은 `insert … on conflict do nothing` 뒤 다시 읽기).
- 줄 추가: 머리 `FOR UPDATE` → 상태 OPEN 확인(DONE 이면 409 `LOC_CHECK_DONE`) → 장부 읽기 → 판정·줄 INSERT. 완료 중 들어온 줄 추가는 머리 잠금을 기다린 뒤 DONE 을 보고 409.
- 완료: 머리 `FOR UPDATE` → OPEN 확인(둘째 완료는 409) → **같은 트랜잭션·같은 장부 시점**에서 MISSING 줄 생성 → 집계·DONE.
- 보정: 줄 `FOR UPDATE`(처리 안 됨 확인) → `InventoryService.post`(LOT 공유 잠금 → 칸 순서, 11.6). 잠금 순서 = **점검 머리 → 재고 읽기**(판정·완료는 재고를 잠그지 않음), **점검 줄 → 재고 칸**(보정). 둘이 서로 반대 순서로 잡는 곳이 없다.

### 2.7 화면·API

| 곳 | 내용 |
|---|---|
| PDA `/pop/wh` 패널 "정위치 점검" | 위치 스캔 → 물건 스캔(결과 색: 정상 초록·다른 위치 노랑 + 장부 칸·미등록 빨강) → [전수 확인 완료](남은 장부 n 건을 "없음"으로 확인 대화). 스캔·완료는 **온라인만** — `hb.api` 직접(대기열 안 씀), 오프라인이면 "연결됐을 때만 점검" + 버튼 비활성. `scanned_at` = 단말 시각(참고), 판정은 서버가 받은 때 |
| API(PDA) | `POST /api/pop/wh/loc-checks/scan`(위치), `POST .../{id}/lines`(물건, `clientReqId`), `POST .../{id}/done` — 단말 체인·작업자 배지·`@ProgAuth(wms-loc-check)` 는 PDA 체인 방식 그대로(12.12) |
| 사무 화면 `wms-loc-check`(새 `sys_prog`, SEARCH·SAVE) | 목록(공장·기간·상태·"미처리 불일치") hb.grid → 상세 줄(결과·장부 스냅숏 경로·처리) → [보정 이동]·[확인만] |
| API(사무) | `GET /api/wms/loc-checks/grid`·`/columns`·`/{id}`, `POST /api/wms/loc-checks/lines/{lineId}/correct`·`/confirm`(`rowVer` 없음 — 줄은 한 번만 처리되므로 409 로 충분) |

## 3. 하지 않음

- 수량 맞추기(실사), 자동 보정, 점검 지시·배정(누가 어느 칸을 언제 — 필요해지면 실사 계획처럼), SN 단위 판정(W-5 결정 뒤 — 그때 스캔 단위만 바뀐다), 적재율.
- 점검 머리·줄 삭제·되돌리기 — 잘못 스캔은 "확인만(사유)" 으로 남긴다.

## 4. 시험 (구현 PR)

| 묶음 | 경우 |
|---|---|
| 판정 | LOT 이 이 칸에 있음 → OK / 다른 칸에만 → MISPLACED + 스냅숏(칸·수량) / 없는 LOT·재고 0 LOT·읽을 수 없는 키 → UNKNOWN / 비관리 품목 `ITEM:` OK·MISPLACED / 완료 → 스캔 안 된 장부 → MISSING, 집계·`accurate_yn` |
| 멱등·중복 | 같은 `clientReqId` → 같은 줄 / 같은 LOT 두 번 → 앞 줄(`DUPLICATE`) / DONE 뒤 스캔·완료 → 409 / 같은 날 같은 칸 다시 시작 → 409 |
| 온라인만 | PDA 오프라인 → 스캔·완료 버튼 비활성, 요청을 보내지 않음(대기열에 쌓이지 않음 — JS 단위 시험) |
| 동시성 | 같은 칸 PDA 두 대 줄 추가 → 같은 머리에 둘 다 / **동시 완료 2** → 하나만 DONE, 다른 하나 409, MISSING 한 벌 / **완료 중 줄 추가** → 409, 줄 안 생김 / 같은 줄 동시 보정 2 → 하나만 CORRECTED, 원장 한 짝 |
| 보정 | 보정 이동 → MOVE 짝(`LOC_CHECK`·`LOC_CORRECT`), 줄 CORRECTED / 두 번 → 409 / OK·MISSING 줄 보정 → 400 / STAGING 출발·도착 → 400 / 그새 출발 칸이 줄어듦 → 409 / 마감된 달 → `PERIOD_CLOSED` / 보류 LOT → 400 / 확인만 사유 없음 → 400 |
| 삭제 금지 | 머리·줄 DELETE → DB 거부 / 처리 뒤 `review_*` 다시 바꾸기 → DB 거부 |
| **A사로 B사** | B사 단말로 A사 칸 스캔 → 칸 없음 / A사 LOT 스캔 → UNKNOWN(RLS) / B사 사용자로 A사 줄 보정 → 404 / 홈 카드 B사 0 |
| 권한 | 화면 권한 없음 403 / PDA 아닌 단말(POP) → 거부(체인) |
| 홈 카드 | 일치·불일치 칸 → 정확도, 처리 안 된 불일치 수, 다른 공장·다른 회사 0 |

## 5. 단계

1. 이 문서 PR → 상위 검토(9장).
2. 구현 PR 하나(마이그레이션 V204·임시 V9319 — 표·코드·`sys_prog`·트리거, 서비스·PDA·사무 화면·홈 카드·시험). 크면 ① 표·PDA 판정 ② 사무 보정·홈 카드 둘로 나눈다[추정 — 구현하며 정함].

### 5.1 구현 (2026-10-29, 구현 PR 하나 — 나누지 않음)

- 마이그레이션 V204·임시 V9319(`wms_loc_check`·`wms_loc_check_line` + 트리거·RLS·코드 4묶음·`sys_prog` `wms-loc-check`·원장 색인 `ix_inv_trx_loc_check`).
- 서버 `module/wms/loccheck/`(`LocCheckService`·`LocCheckMapper`·`LocCheckApiController`·홈 카드 `LocCheckCard` `wms-loc-accuracy`), PDA 는 `PdaService`·`PdaApiController` 에 `/api/pop/wh/loc-checks/**` 4개.
- 화면: 사무 `wms/loc-check`(목록 hb.grid → 상세 줄 → [보정 이동]·[확인만]), PDA `/pop/wh` 정위치 패널 — 흐름은 `pda-loccheck.js` 로 떼어 node 단위 시험(온라인만).
- 시험: `PdaLocCheckTest`(판정·완료 집계·홈 카드·보정/확인·삭제 거부·A사로 B사·권한·동시 완료 2·완료 중 줄 추가·동시 보정 2), `src/test/js/pda-loccheck.test.js` 4개(오프라인·완료 뒤 포함). 결과는 docs/testing/wms.md.

## 6. 결정할 것 (상위 검토 — 사용자 결정 밖의 세부)

| # | 질문 | 제안 |
|---|---|---|
| R-1 | 스캔 단위 | LOT(관리 품목) 또는 품목(비관리) — SN 없음(W-5 뒤) |
| R-2 | "없음" 을 만드는 장부 범위 | 그 칸 수량 > 0 모든 상태(QC·HOLD 포함) — 위치만 보므로 |
| R-3 | 같은 날 같은 칸 다시 점검 | 막음(409) — 정확도 분모 1회(D사와 같음). 다음 날은 새로 |
| R-4 | 보정 이동 막는 칸 | 출발·도착 STAGING(피킹분은 출하 화면에서) |
| R-5 | 보정을 PDA 에서도? | 사무 화면만 — "사람이 확인 뒤"(결정)를 장부 스냅숏·출발 칸 선택과 함께 보이려고 |
| R-6 | 홈 카드 숫자 | 처리 안 된 불일치 줄 수(정확도는 항목 글자) |
| R-7 | 정확도 기간 | 30일 |

## 7. 검토 (상위)

- 2026-10-29 상위: R-1~R-7 **제안대로 승인**. 고칠 것 2건 반영 — ① 물건 스캔·완료는 **온라인만**(`hb.pop.queue` 안 씀 — 늦게 간 스캔의 판정·스냅숏이 조용히 틀림, PDA 입고와 같은 규칙), 오프라인이면 표시 + 버튼 비활성 ② **완료·동시성**: 줄 추가·완료 모두 머리 `FOR UPDATE` 뒤 상태 확인(둘째 완료·완료 중 줄 추가 409), MISSING 은 같은 트랜잭션·같은 장부 시점, 잠금 순서 2.6. 참고: 완료 안 된 머리는 정확도 분모에서 빠짐(2.5).
- 2026-10-30 사용자 결정 L-1(QA 2026-10-29): 점검일 ≠ 오늘(공장 시간대 `BizClock.zone(plant)`)인 열린 점검의 물건 스캔·완료는 409 `WMS_LOC_CHECK_STALE` — 자정을 넘긴 PDA 가 어제 머리에 쓰면 그 시각 장부가 "어제 점검" 정확도로 들어가 조용히 틀린다. 같은 요청 id 다시(쓰기 없음)는 처음 줄 그대로. PDA 는 409 를 받으면 점검을 버리고(`pda-loccheck.js`) 위치를 다시 스캔하면 오늘 머리가 열린다. 어제 OPEN 머리는 그대로 남고 정확도 분모(DONE)에 들어가지 않는다.
- 2026-10-30 후속(상위 지시): 줄 조회 때 장부 스냅숏 칸마다 지금 칸 용도(`usage`)를 붙여(저장값은 그대로) 사무 화면이 출하 대기 칸에는 [보정 이동] 대신 안내를 보인다. 서버 400 `STAGING_LOCKED` 는 그대로.
