# 59. 고객 반품 입고 설계 — docs/49 6장 5 (사용자 결정 W-7 순서 3)

> 2026-11-01 상위 지시: W-7 순서(운송 정보 → PDA 입고 → **고객 반품 입고는 설계 문서부터**). **문서만.** 영업·QMS 경계는 끝 결정 표(9장)로 — 상위 검토·사용자 결정 뒤 구현.
> 근거는 코드 위치로(main f154b6c5 기준 조사), 코드로 확인 안 된 판단은 **[추정]**.

## 0. 요약

- **무엇**: 고객이 돌려보낸 물건을 **어느 출하로 나간 무엇인지** 근거를 잡아 받고(원장 + 위치), 쓸 수 있는지는 품질이 정할 때까지 막아 둔 뒤, 판정대로 되살리거나 폐기·재작업한다.
- **왜**: docs/49 2장 "기타 — 고객 반품 입고: 이름만"(docs/30 733행 `RETURN_CUST` 이름뿐, 코드 없음). 지금은 조정 입고(ADJ_IN)로 넣을 수밖에 없어 **어느 출하·LOT 의 반품인지·왜 들어왔는지가 원장에 안 남고**, 품질 판정 없이 가용 재고가 될 수 있다(조용히 틀림).
- **모양(제안)**: WMS 반품 입고 기록 `wms_cust_rtn` + 새 원장 유형 `RCV_RTN`(출처 `CUST_RTN`) → **BLOCKED** 로 들어감 → QMS NCR(출처 CLAIM)의 처분이 기존 `QualityStockLink.onNcrClosed` 로 풀거나(USE_AS_IS) 폐기(SCRAP). 출하 근거 = `wms_ship`·LOT, 돌려받을 수 있는 양 = 순 출하 − 이미 받은 반품.

## 1. 지금 있는 것 (조사 2026-11-01)

| 대상 | 위치 | 고객 반품에 쓸 수 있나 |
|---|---|---|
| 원장 유형 | `ck_inv_trx_type`(V181:14-17) 19개, 들어옴 부호 `ck_inv_trx_sign`(V181:18-24). 다음 sort_no 31 | 반품 유형 **없음** — 새로 |
| 출하 근거 | `wms_ship`·`wms_ship_line`·`wms_ship_pick`(lot_id), 원장 SHIP(출처 SHIP_PICK). 계약 `ShipQuery.shippedByLine`·`byLots`(docs/57) | 있음 — 출하 행·LOT 단위 순 출하 |
| 협력사 반품(거꾸로 방향 본보기) | `srm_return`(V147:33-80, ship_ref V159) + `ReturnShipGate`(SRM) + WMS `ReturnIssueService`(RTN_SUP, 출처 SRM_RETURN, 멱등 `RTN_ISS:{요청}`·역분개 `RTN_ISSR:`) | 모양 본보기(머리·상태·멱등·역분개) |
| QMS 클레임 접수 | `qms_capa` source = CUSTOMER_CLAIM(V085:141~) — **LOT·수량·출하 참조 없음**. 계약 `CapaPort.createInternal` 은 INTERNAL 만 | 접수(8D)는 있음, 물건 단위 기록은 없음 |
| QMS 부적합 | `qms_ncr`(V112:39-80) source 에 **CLAIM** 있음, lot_id NOT NULL, `qms_ncr_disp` = USE_AS_IS·REWORK·SCRAP·RETURN·SORT. 고객·출하 참조 없음 | LOT 단위 판정·처분 그릇 |
| 재고 상태 ↔ 품질 | `QualityStockLink.onNcrClosed`(QualityStockPosting:136): USE_AS_IS → BLOCKED→AVAILABLE, SCRAP → 원장 SCRAP(BLOCKED 에서), 그 밖은 BLOCKED 유지 | **BLOCKED 로 들어오면 그대로 탄다** |
| 수입검사 QC | `QcReceiptRule` — 검사 유형에 고객 반품 없음(`ck_qms_insp_type` V099:55) | QC 경로는 안 맞음(→ 3장) |
| 영업 출하 수량 | `sal_order_line.shipped_qty` = `OrderShipGate.ship`(음수 = 출하 취소) 투영(V123) | 반품으로 줄일지 = **결정 R-3** |
| 위치 용도 | `ck_wms_loc_attr_usage`(V037:48) = STORAGE·RECEIVING·QC·STAGING·LINESIDE·SCRAP — RETURN 없음 | 결정 R-5 |

## 2. 흐름 (제안)

```
[사무 wms-cust-rtn]  고객·출하 번호 / LOT / 팔레트(SSCC) 스캔 → 출하 근거 행 고르기(돌려받을 수 있는 양 표시)
                      → 수량·사유(코드)·위치(반품 칸) → [반품 입고]
   └ 한 트랜잭션: wms_cust_rtn(RECEIVED) + 원장 RCV_RTN(+, BLOCKED, 출처 CUST_RTN·src_id = 반품 id, lot_id = 출하 LOT)
                  (결정 R-2 (가)면) QMS 계약으로 NCR(source CLAIM, lot, 수량) 생성 → 번호를 반품에 남김
[QMS NCR]           판정·처분 → onNcrClosed: USE_AS_IS → AVAILABLE / SCRAP → SCRAP 원장 / REWORK·SORT·RETURN → BLOCKED 유지(후속)
[사무] 잘못 받음 → 반품 입고 취소(역분개, NCR 이 아직 OPEN 일 때만 — 결정 R-6)
```

PDA 반품 입고는 사무 화면 뒤(PDA 입고 12.12.15 와 같은 모양) — 1차 범위 밖.

## 3. 표·원장 (제안 — 구현 PR 에서 임시 V 번호)

```
wms_cust_rtn  (회사별, RLS, 감사, DELETE 회수, 보존 QUALITY_RECORD)
  rtn_id PK, comp_cd, rtn_no(채번 WMS_CUST_RTN, unique(comp_cd, rtn_no)), plant_cd,
  cust_id, ship_id(FK (comp_cd, ship_id) — 운송 정보와 같은 회사+id FK), ship_line_id, item_id, lot_id(null = 비관리 품목),
  qty(기본 단위, > 0), reason_cd(공통코드 WMS_CUST_RTN_REASON — 품질·오출하·수량 초과·기타), remark,
  node_id(받은 칸), status RECEIVED | CANCELLED, ncr_id(스냅숏 번호 ncr_no — QMS 표 FK 없음, srm_return 과 같은 방식),
  capa_no(선택 — 고객 클레임 번호를 사람이 적음), received_at/by, performed_by(작업자), cancel_reason, client_req_id unique,
  row_ver, 감사 4칸
원장: trx_type RCV_RTN(들어옴), src_type CUST_RTN 필수(ck_inv_trx_rtn_cust_src), 역분개 = 기존 reverse 규칙
```

- **상태 = BLOCKED 고정**: 고객 반품은 "왜 돌아왔는지 모른다" 가 기본 — 판정 전 가용이 되면 다시 출하된다(조용히 틀림). QC 가 아니라 BLOCKED 인 까닭: QC → AVAILABLE 은 검사 판정(InspService)만 풀고, 반품은 검사 계획 대상이 아니다. NCR 처분 경로(onNcrClosed)가 BLOCKED 에서 시작한다.
- **같은 LOT 으로 받음**: 출하 피킹의 lot_id 그대로 — 계보·리콜 추적(docs/57)이 끊기지 않는다. 새 LOT 을 만들지 않는다(MES 계보 변경 없음).
- **돌려받을 수 있는 양** = (출하 행·LOT 의 순 출하 — `ShipQuery` 와 같은 SQL 조각) − (그 출하 행·LOT 의 살아 있는 반품 합). 넘으면 409 `WMS_CUST_RTN_OVER`. 동시 두 건은 출하 행 advisory 잠금(`WMS_SHIP_OL` 과 같은 방식, 키 `WMS_CRTN:{ship_line}`)으로 줄 세움.
- **출하 취소(undo)와의 관계**: 반품이 걸린 출하는 출하 취소 409(반품 먼저 취소) — U-1 과 같은 "넘침" 규칙을 반대로.
- **보류 LOT**: 원장 트리거 `ck_inv_trx_lot_hold` 는 들어오는 유형을 막지 않는다(docs/30:1138) — 보류 LOT 반품도 받는다(오히려 그래야 함).

## 4. 화면 (제안 — `wms-cust-rtn`)

| 항목 | 내용 |
|---|---|
| 찾기 | 고객 코드 + 기간 / 출하 번호 / LOT 번호 / 팔레트·SSCC 스캔 → 출하 근거 행(출하·품목·LOT·순 출하·이미 반품·남은 양) |
| 입력 | 수량(남은 양 이하), 사유 코드, 위치(반품 칸 — 결정 R-5), 비고, 고객 클레임 번호(선택) |
| 목록 | 반품 번호·고객·출하·LOT·수량·상태·NCR 번호·NCR 상태(QMS 읽기 계약 — 결정 R-2) |
| 권한 | SEARCH·SAVE(받기)·APPROVE(취소) |
| 하지 않음 | 처분(QMS 화면), 재출하·교환 출하(영업 수주로 — 결정 R-3), 고객 측 재고 |

## 5. 시험 (구현 PR)

| 묶음 | 경우 |
|---|---|
| 정상 | 출하 10 중 3 반품 → 원장 RCV_RTN·BLOCKED·같은 LOT, 남은 양 7 / 비관리 품목 / 여러 LOT 출하 행에서 LOT 하나만 |
| 넘침·동시 | 남은 양 초과 409, 같은 출하 행 동시 두 건(합 초과면 하나만), 같은 요청 id 다시 = 처음 결과 |
| 품질 | NCR USE_AS_IS → AVAILABLE, SCRAP → SCRAP 원장, REWORK → BLOCKED 유지, 판정 전 출하 피킹 후보에 안 나옴(A-1 가용 정의) |
| 취소 | 반품 취소 → 역분개·남은 양 복구, NCR 이 닫힌 뒤 취소 409 |
| 출하 취소 | 반품이 걸린 출하 undo 409 |
| **A사로 B사** | B사로 A사 출하 id·LOT·반품 id → 404/빈 목록, 직접 SQL 다른 회사 출하 id → 회사+id FK |
| 권한 | SAVE 없음 403, 취소는 APPROVE |

## 6. 하지 않음 (1차)

- 고객 포털에서 반품 신청(RMA 발행) — 결정 R-1 에 따라 2차.
- 시리얼(SN) 단위 반품(W-5 하지 않음), 고객 측 재고·위탁.
- 교환 출하 자동 생성·대금(신용) 처리·ERP 반품 전표 — ERP 송신은 아웃박스 후속(R-4).
- 출하 근거 없는 반품(근거 모르는 물건) — 1차는 거부, 조정 입고 + 사유로(결정 R-7).

## 7. 단계

1. 이 문서 → 상위 검토·사용자 결정(9장).
2. (R-2 (가)면) QMS 작은 PR: `NcrPort.createForClaim(lotId, itemId, qty, ref)` + 읽기 `ncrStatus(ids)` — QMS 몫.
3. (R-3 (나)면) 영업: 기존 `OrderShipGate.ship(음수)` 재사용 — 새 계약 없음, 결정만.
4. WMS PR: 표·원장 유형·서비스·화면·시험(임시 V9323~).
5. 후속: PDA 반품 입고, ERP 반품 송신, 고객 포털 RMA.

## 8. 위험

- 원장 유형 추가는 `ck_inv_trx_type`·`ck_inv_trx_sign` 판 교체 — 다른 모듈 PR 과 같은 판을 동시에 고치면 충돌(병합 순서 상위 조율).
- 순 출하 계산을 반품 쪽에 또 쓰면 출하 추적(docs/57)·출하 화면과 숫자가 어긋날 수 있다 → 같은 SQL 조각 하나로.

## 9. 결정할 것 (상위 검토 → 사용자) — **2026-11-01 사용자 결정: R-1~R-8 모두 제안대로**

| # | 질문 | 선택지 | 제안 |
|---|---|---|---|
| R-1 | 반품 근거 문서의 주인 | (가) WMS 반품 입고 기록이 근거(받은 사실) — 승인 문서 없음 (나) 영업 RMA(반품 승인) 먼저, WMS 는 RMA 로만 받음 (다) QMS 클레임(CAPA)에 행·수량을 붙여 근거로 | **(가)** 1차 — 지금 영업에 반품 개념이 없고, QMS CAPA 엔 LOT·수량이 없다. 받은 사실이 먼저 생기고 승인 문서는 2차(RMA) |
| R-2 | 품질 판정 연결 | (가) 반품 입고 때 QMS NCR(CLAIM) 자동 생성(QMS 새 계약) (나) WMS 는 BLOCKED 로만 넣고 품질 담당이 NCR 을 손으로 만듦 | **(가)** — (나)는 NCR 을 안 만들면 BLOCKED 재고가 이유 없이 쌓인다. QMS 작은 PR 필요 |
| R-3 | 영업 출하 수량(`shipped_qty`) 되돌림 | (가) 안 줄임 — 반품은 별개 사실, 교환은 새 수주 행 (나) 반품 수량만큼 줄임(`OrderShipGate` 음수) → 수주 행이 다시 열려 교환 출하가 같은 행으로 | **(가)** — 줄이면 매출·청구(ERP) 숫자가 출하 사실과 어긋나고, 품질 반품(불량)과 오출하 반품을 같은 행에서 섞는다. 오출하 정정은 기존 출하 취소(undo)로 |
| R-4 | ERP 반품 송신 | 1차 포함 / 후속 | 후속(출하 송신 아웃박스 모양 그대로) |
| R-5 | 반품 칸 | (가) 위치 용도 `RETURN` 추가(체크 판 교체) (나) 기존 QC 용도 칸 사용 | **(나)** 1차 — 상태가 BLOCKED 라 용도로 막을 필요는 적다. 칸이 섞이는 게 문제면 (가) |
| R-6 | 반품 입고 취소 | NCR 이 OPEN 일 때만 / 언제나(역분개) | NCR OPEN 일 때만 — 처분이 끝난 재고를 되돌리면 처분 원장과 어긋난다 |
| R-7 | 출하 근거 없는 반품 | 거부(조정 입고로) / 고객·품목만으로 받기 | 거부 1차 — 근거 없는 반품은 "어느 LOT" 이 없어 추적이 끊긴다 |
| R-8 | QMS 클레임(CAPA)과 묶기 | 반품에 capa_no 를 사람이 적음(1차) / CAPA 에 반품 목록 표시(QMS 화면) | 1차는 번호만, QMS 화면 표시는 QMS 몫 후속 |

## 10. 검토 (상위)

- 2026-11-01 상위 → 사용자 결정(제안 그대로): R-1 근거 = WMS 반품 입고 기록(RMA 2차) · R-2 반품 입고 때 QMS NCR(CLAIM) 자동 생성 — **QMS 가 계약 `NcrPort.createForClaim` 을 먼저 낸다** · R-3 영업 `shipped_qty` 안 줄임(오출하는 출하 취소) · R-4 ERP 반품 송신 후속 · R-5 기존 QC 용도 칸 · R-6 취소는 NCR OPEN 때만 · R-7 출하 근거 없는 반품 거부 · R-8 `capa_no` 번호만.
- 순서: QMS 계약 병합 알림 → WMS 구현 PR(임시 V9323).

## 11. 구현 (2026-11-02, V224·임시 V9323 — QMS `NcrPort`(#742, V223) 위)

| 항목 | 한 것 |
|---|---|
| 표·원장 | `wms_cust_rtn`(3장 그대로 — 회사+id FK `fk_wms_cust_rtn_ship`, 요청 id unique, 감사·RLS·DELETE 회수), 원장 `RCV_RTN`(들어옴, `ck_inv_trx_cust_rtn_src` 출처 CUST_RTN 필수), 사유 코드 `WMS_CUST_RTN_REASON`(QUALITY·WRONG_SHIP·OVER_QTY·OTHER, 회사 확장 없음) |
| 서비스 | `wms.inv.CustRtnService` — 근거(출하 행 × LOT, 순 출하 = SHIP 원장, 출하 추적과 같은 계산) → 잠금(출하 머리 FOR SHARE → 출하 행 advisory `WMS_CRTN:`) → 같은 요청 확인(같으면 처음 결과, 내용 다르면 409 `DUPLICATE_REQUEST`) → 받을 수 있는 양 다시 계산(넘으면 409 `WMS_CUST_RTN_OVER`) → 반품 INSERT → **NCR**(`createForClaim`) → 원장 BLOCKED |
| 잠금 순서 | 반품 → NCR → LOT 보류 → 원장(LOT FOR SHARE·칸). **NCR 을 원장보다 먼저** — 원장이 LOT 을 FOR SHARE 로 쥔 뒤 보류(UPDATE)로 올리면 같은 LOT 의 다른 반품과 교착(docs/29_P3 38장 NP-2 순서 그대로) |
| 받는 칸 | 사무 입고와 같은 `rcvNodeReason`(출하 대기·폐기 위치 아래·재고 안 둠 거부, W-9) — 후보 목록은 QC 용도 칸 먼저(R-5) |
| 취소(R-6) | 승인 권한. 반품 행 잠금 → `cancelForClaim`(OPEN 아니면 409 `QMS_NCR_STATE`) → 원장 역분개(출처 경로 `reverseFromSource`) → CANCELLED. 수불 원장 화면 역분개는 400 `CUST_RTN_SOURCE` |
| 출하 취소 | 살아 있는 반품이 걸린 출하는 409 `WMS_SHIP_HAS_RETURN`(반품 번호 안내). 반품 입고가 출하 머리를 FOR SHARE 로 잡아 출하 취소(FOR UPDATE)와 줄 선다 |
| **LOT 비관리 품목** | 출하 피킹에 LOT 이 없어 QMS NCR(LOT 필수)을 만들 수 없다 → **NCR 없이 BLOCKED 로만** 받고 취소는 언제나. 풀기는 사람(재고 상태 바꿈). **사용자 결정(2026-11-02): 이대로 — 비관리 품목은 NCR 없이 BLOCKED** |
| 품질 연동 스위치 | NCR 종결 → `onNcrClosed` 는 공장 스위치 `WMS_QUALITY_LINK` = Y(또는 판정 반영 이력)일 때만 재고를 바꾼다(기존 규칙). 스위치가 꺼진 공장은 BLOCKED 가 남는다 — 손 NCR 과 같음 |
| 화면 | `wms-cust-rtn`(WMS, SEARCH·SAVE·APPROVE): 근거 찾기(출하 번호·LOT 번호(`LOT:` 스캔)·고객 코드 + 출하 기간) → [받기] → 수량·사유·받는 칸·고객 클레임 번호·비고 → 반품 입고. 받은 반품 목록(NCR 번호·상태), [반품 취소](승인 + NCR OPEN 또는 없음) |
| 시험 | `CustRtnTest`(7) — docs/testing/wms.md |

## 12. QA 후속 (2026-11-02, docs/qa/2026-11-02_cust-rtn.md — 마이그레이션 없음)

| ID | 고친 것 | 이유 |
|---|---|---|
| CR-a | `CustRtnService.receive` — 원장 게시(`inventory.post`)가 replay 면 409 `DUPLICATE_REQUEST`, 반품·클레임 NCR·LOT 보류까지 전체 롤백 | 같은 반품을 다시 보낸 경우는 앞의 `findByReq`(반품 표 unique)가 먼저 처음 결과를 돌려준다. 따라서 여기까지 와서 replay 가 나오면 **다른 원장 행(출하·이동 등)이 쓰던 요청 id** 이고, 그대로 두면 원장 없는 반품(재고 0)이 남는다. `postChecked`(PQ-1) 를 쓰지 않은 이유: 이 자리의 replay 는 내용 비교 없이 모두 남의 것이라 결과(409)가 같고, 비교 쿼리 한 번이 덜 든다 |
| CR-e | 수불 원장 화면 역분개(`InventoryService.reverse`)가 출처 `SHIP_PICK` 행(출하 SHIP·피킹 이동 짝 MOVE_OUT/IN)을 400 `SHIP_SOURCE` 로 막음. 출하 모듈의 출하 취소·피킹 취소(`reverseAll(List, String)`)만 되돌린다. 원장 화면 상세도 역분개 칸 대신 안내(`SHIP_PICK`·`CUST_RTN`) | 원장만 되돌리면 출하·피킹 상태·영업 `shipped_qty` 는 그대로라 셋이 어긋나고(같은 물건 다시 피킹·출하 → 영업 출하 이중), 살아 있는 반품의 근거(순 출하)가 사라진다. #749 이전부터 있던 구멍 |

- 다른 업무 출처 훑기(사람 화면 역분개가 열려 있는 것)는 상위 보고로 넘김 — `PRD_RECEIPT`·`LOT_GEN`·`CMS_PART` 는 출처 모듈 정정 경로가 따로 있어 같은 종류의 어긋남이 가능, `MAT_RECEIPT`·`SRM_RETURN`·`MANUAL` 은 설계상 원장 화면 역분개가 정정 경로(게이트 함께), `COUNT`·`LOC_CHECK` 는 정책 질의.
- 시험: `CustRtnTest#결함CRa_*`·`#결함CRe_*`(QA 부록 A-1·A-4 + 피킹 짝) — 고치기 전 2건 실패 확인.

## 13. 결정 CR-c·CR-d (2026-11-02 사용자 결정 — 추천안, 마이그레이션 없음)

| ID | 결정 | 구현 |
|---|---|---|
| CR-c | 클레임 NCR(출처 `WMS_CUST_RTN`)의 처분(USE_AS_IS·SCRAP)은 **그 반품이 받은 칸(`wms_cust_rtn.node_id`)의 BLOCKED 재고부터** 처리하고, 모자라면 지금 순서(칸 순)로 이어간다 | `QualityStockMapper.findNcr` 가 클레임 NCR 이면 반품 칸(`rtn_node_id`)을 함께 읽고, `QualityStockPosting.onNcrClosed` 가 잠근 칸 목록을 "반품 칸 먼저 → 칸 순"으로 다시 줄 세운다(잠금 순서는 그대로). 검사 NCR(출처 없음)은 그대로 칸 순 |
| CR-d | 같은 요청 id 로 다시 오면 출하 행·LOT·수량 외에 **받는 칸·사유**까지 비교 — 다르면 409 `DUPLICATE_REQUEST`(PQ-1 규칙과 같음) | `CustRtnService.receive` 의 `findByReq` 비교에 `nodeId`·`reasonCd` 추가 |

- 하지 않은 대안(CR-c): "LOT 단위 처분을 유지하고 같은 LOT 에 열린 클레임 둘을 막기" — 같은 LOT 이 여러 고객에게 나간 경우 둘째 반품을 받을 수 없게 되어 버림.
- 남는 경우: 반품 재고를 다른 칸으로 옮겼거나(BLOCKED 이동) 일부를 이미 처분해 반품 칸이 모자라면 칸 순으로 이어가므로, 그때는 다른 칸의 같은 LOT BLOCKED 를 쓸 수 있다(사용자 결정 "모자라면 지금 순서").
- CR-b(출처 있는 NCR 의 QMS 화면 취소 거부)는 QMS 몫.
- 시험: `CustRtnTest#결함CRc_반대_조합_*`·`#결함CRd_*`, `CustRtnQaTest#결함CRc_*`(QA 부록 A-3).

## 14. QMS 8D 조회용 색인 (2026-11-02, V227·임시 V9324 — 상위 요청)

QMS 8D 반품 연계가 `ShipQuery.custReturns` 로 `wms_cust_rtn` 을 `btrim(capa_no) = ?` OR `ncr_id = any(?)` 로 읽는다. 색인이 없으면 8D 상세를 열 때마다 회사 안 반품 전체를 훑으므로 부분 색인 둘을 둔다: `ix_wms_cust_rtn_capa (comp_cd, btrim(capa_no)) WHERE capa_no IS NOT NULL`, `ix_wms_cust_rtn_ncr (comp_cd, ncr_id) WHERE ncr_id IS NOT NULL`. 식 `btrim(capa_no)` 는 QMS 쿼리와 글자 그대로 같아야 색인을 탄다(바꾸면 함께). 시험 `CustRtnTest#QMS_8D_조회용_색인이_있다`(정의 확인).

## 15. ERP 반품 송신 (2026-11-02 구현 — R-4 "후속, 출하 송신 아웃박스 모양 그대로", 마이그레이션 없음)

| 항목 | 내용 |
|---|---|
| 발행 | `CustRtnErpPublisher` — 반품 입고·취소 트랜잭션 안에서 아웃박스 `ERP_CUST_RTN` 한 행. 정책 `ERP_SYSTEM` 이 등록된 어댑터일 때만(출하 `ShipErpPublisher` 와 같은 규칙) |
| 송신기 | P2 `ErpOutboundHandler` 를 종류 `CUST_RTN` 으로 하나 더(WMS 설정 빈 — MES 파일은 건드리지 않음) |
| 업무 키 | 반품번호-R(입고) / 반품번호-C(취소) — 반품마다 입고·취소가 한 번씩이라 겹치지 않음. 출하는 `-v{row_ver}` 지만 반품은 다시 입고가 없어 고정 접미어로 충분 |
| 본문 | system·day·occurredAt(공장 시간대)·rtnNo·shipNo·custCd·itemCd·itemNm·erpItemCd(ERP 품목 매핑, 출하와 같은 조회)·qty(취소는 음수)·unitCd·lotNo·reasonCd·capaNo·ncrNo |
| 발행 안 함 | 같은 요청 id 다시(처음 결과), 409 로 롤백된 요청(CR-a 등 — 아웃박스도 같은 트랜잭션이라 함께 롤백), 어댑터 없음 |
| 화면 | 연계 현황(`sys-outbox`) 주제 이름 `outboxTopic.ERP_CUST_RTN` |

- 교환 출하 자동 생성·대금(신용) 처리는 여전히 하지 않음(6장) — ERP 가 이 전표로 반품 처리.
- 시험: `CustRtnTest#ERP_반품_전표는_입고에_양수_취소에_음수_같은_요청은_한_번`.
