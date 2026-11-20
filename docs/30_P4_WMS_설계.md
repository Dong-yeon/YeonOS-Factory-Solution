# 30. P4 WMS 설계 — 1부: P2 와 병행하는 재고 원장 엔진·라벨·로케이션·차감 전략·실사

작성 2026-10-20. 8장 결정 11건은 **추천안 그대로 채택**(2026-10-20 사용자, 10장).
근거: docs/00 2장(도메인 흐름)·3장(재고 단위·품질 게이트·차감 전략 = 정책)·5.3 테이블 표(`INV_STOCK`/`INV_TRX`, `SYS_PRINT_QUEUE`/`SYS_LABEL_TEMPLATE`, `sys_period_close`)·6장 로드맵 P4 행, 01 3.1(원장 공통 규약 — 기간 마감·대사)·5.8(아웃박스 3단), 02 2.6 보존 클래스·6.2-D(원장 규약, "재고는 이동을 from/to 두 행 → 합계 0"), 11 2.2(스캔 키 규칙)·2.4(오프라인 증감 이벤트)·2.11.3(WMS 우회 — override 사유·권한·로그, 스캔 준수율), 17 2장(P1 공통 규칙)·`mst_item.lot_ctrl_yn`, 19(창고·로케이션 = `mst_equip_hier` WH › LOC, 코드는 공장 안 유일, "로케이션 용량·혼적·피킹 순서는 P4"), 22(외부 코드 매핑 `IF_CODE_TYPE = WH`, 클래스 속성 `attrs`), 24 체크리스트 A~K, **25 D2(P2 는 재고를 세지 않는다 — `prd_receipt`·자재 LOT 간이 등록이 P4 첫 소스, LOT 마스터는 P2 4단계)**·6장(라벨 인쇄 큐는 P4), 27 U10(카메라 바코드)·U11(라벨 = 아웃박스 → 서버가 네트워크 프린터에 ZPL), 29 0장·6장(1부/2부 나눔, 병행 규칙).
P0 플랫폼 규약(01)·P1 공통 규칙(17 2장)·P2 공통 규칙(25 3장)은 그대로 따른다 — 이 문서는 **P4 에서 새로 정하는 것**만 적는다.

---

## 0. 왜 1부·2부로 나누나

재고 수량의 키는 **품목 × LOT × 위치**(00 3장)인데 LOT 마스터(`lot_master`)는 P2 4단계, 생산 입고(`prd_receipt`)는 P2 7단계, 투입(LOT 스캔)은 P2 5b, PDA 의 바탕(`hb.pop`·단말 세션·오프라인 대기열)은 P2 5a 에서 생긴다. 수입검사 판정은 P3 2부다. 이것들을 기다리지 않고 **"LOT 이 붙기 전에도 정할 수 있고 시험할 수 있는 것"**을 1부로 떼어 P2·P3 와 동시에 진행한다.

| | 1부 (이 문서, 지금) | 2부 (P2 4·5a·5b·7단계 + P3 2부 병합 뒤) |
|---|---|---|
| 기대는 것 | P1(품목·단위·장비 계층 WH/LOC·거래처·클래스 속성) + P0(아웃박스·채번·정책·결재·작업 잠금) | P2 `lot_master`·`prd_receipt`·자재 LOT 간이 등록·LOT 스캔 투입·`hb.pop`·`sys_terminal`, P3 2부 수입검사 판정 |
| 범위 | 로케이션 속성·스캔 키 규칙·로케이션 바코드, 라벨 템플릿·프린터·인쇄 큐, **재고 원장 엔진**(원장·현재고 투영·역분개·대사·기간 마감, LOT 비관리 품목으로 시험), 차감 전략 순수 함수(FIFO/FEFO/지정 LOT/합격분만), 실사(기초 재고 포함) | 원장에 LOT 연결(`lot_id` FK)·LOT 관리 품목, 생산 입고·자재 입고·투입 차감, 수입검사 연동(QC 보류 → 가용), 구매 입고(SRM 31 과 경계), 출하(피킹·파렛트·출하 라벨·출하검사 게이트), PDA 화면, MRB 처분 연동 |
| 원칙 | 2부가 붙을 **자리**(원장의 `lot_id`·`stock_status`·`src_type/src_id` 칸, 차감 함수 입력의 유효기한 칸, `LabelPrintService` 계약)를 1부 테이블·함수에 미리 둔다 — 2부에서 1부 테이블을 갈아엎지 않게 | 1부 엔진(`InventoryService.post`)과 순수 함수만 호출 |

## 1. 결론 먼저

- 1부는 **"재고가 어떻게 움직이고, 어떻게 세고, 무엇을 붙여 찾는가"**의 엔진까지다. 데모 흐름: 창고·로케이션(P1) → 로케이션 속성(용도·피킹 순서·혼적) + 로케이션 라벨 일괄 인쇄 → 기초 재고 실사(OPENING) → 이동·조정 → 현재고·수불 원장 조회 → 정기 실사(차이 승인 → 조정 원장) → 월 마감(마감 뒤 소급 입력 차단) → 대사 작업이 "원장 합 = 현재고" 를 매일 확인.
- **재고 원장 `inv_trx` 는 원장 6종 중 마지막 하나**다. 01 3.1 규약(두 시각·`entry_method`·`recorded_by`/`performed_by`·역분개·`hb_forbid_update_delete()`) + **이동은 from/to 두 행(합 0)**(02). 현재고 `inv_stock` 은 원장의 **투영** — 원장 INSERT 트리거 하나만 고친다(서비스·직접 SQL 어디로 들어와도 갈라지지 않음). 마이너스 재고는 `CHECK (qty >= 0)` 이 동시성까지 막는다(8장 D3).
- 1부 원장은 **LOT 비관리 품목(`lot_ctrl_yn = 'N'`)만 받는다** — `lot_id` 칸은 만들어 두되 FK·필수 규칙은 2부가 P2 `lot_master` 위에서 건다(8장 D1). LOT 번호를 WMS 가 따로 만들지 않는다(P2 와 이중 마스터 방지).
- 라벨은 **템플릿(ZPL + 자리표시자) → 서버에서 렌더 → 인쇄 작업 행 + 아웃박스 `LABEL_PRINT` → 송신기가 네트워크 프린터(TCP 9100)에 보냄**(27 U11). 인쇄 상태는 아웃박스 상태에서 파생(송신기는 DB 를 쓰지 않는다 — 01 5.8). P2 의 LOT 라벨 요청도 이 `LabelPrintService` 를 부른다(25 6장).
- 차감 전략은 **순수 함수 + 표 기반 단위 테스트**(29 3장과 같은 원칙) — 2부 투입·피킹이 그대로 부른다.

## 2. 범위와 단계

순서 원칙(17·25·29 와 같음): **참조되는 것 먼저**, 매 단계 화면·API·테스트(정상 + 이상 사용 A~K + A사로 B사 + 직접 SQL)까지 끝난 상태로 커밋, 끝나면 7장 표에 ✅.

| 단계 | 범위 | 첫 화면 | 완료 기준 |
|---|---|---|---|
| **W1** | **로케이션 속성·스캔 키**: `wms_loc_attr`(WH·LOC 노드 1:1 — 용도 STORAGE/RECEIVING/QC/STAGING/LINESIDE/SCRAP, 피킹 순서, 품목 혼적·LOT 혼적 허용, 재고 둘 수 있음), **스캔 키 규칙** `ScanKeyResolver`(바코드 글자 → 대상 유형·키: 내부 형식 `{유형}:{공장}:{코드}` + GS1-128 AI 파서 (00)(01)(10)(17)(30)(37)) — 순수 함수 | 로케이션 속성 | 노드 유형 WH·LOC 만, 같은 회사·사용 중 노드만(트리거), 스캔 판별 표 기반 테스트(잘린 바코드·AI 길이 초과·FNC1·알 수 없는 접두어·다른 공장 코드) |
| **W2** | **라벨·인쇄 큐**: 프린터 `wms_printer`(공장·주소·포트·DPI·용도), 템플릿 `wms_label_tpl`(라벨 유형 LOC/ITEM/LOT/PALLET/SHIP × 코드, ZPL 본문 + 자리표시자, 크기·DPI) + 렌더러(ZPL 특수문자 이스케이프 `^FH`), 인쇄 작업 `wms_print_job`(아웃박스 `message_id` 연결, 재발행 = 사유 + 권한) + 송신기 `LabelPrintHandler`(TCP) + **가짜 프린터**(테스트용 TCP 서버), 로케이션 라벨 일괄 인쇄 | 라벨 템플릿 · 인쇄 이력 | 자리표시자 화이트리스트 밖 거부, 값의 `^`·`~` 로 ZPL 명령 주입 불가, 프린터 주소 사설망만(8장 D6), 프린터 꺼짐·응답 지연 = 재시도 후 FAIL, 같은 메시지 두 번 송신 시 인쇄 작업 1건 |
| **W3** | **재고 원장 엔진**: `inv_trx`(원장) + `inv_stock`(투영, INSERT 트리거) + `InventoryService.post(trx[])`(한 트랜잭션, 이동 = 두 행, `client_req_id` 멱등) + 역분개 정정 + 대사 작업(`sum(inv_trx) = inv_stock`, 불일치는 알림만) + 사무 화면의 **이동·조정**(LOT 비관리 품목) + 현재고·수불 원장 조회(기간 필수·keyset) | 현재고 · 수불 원장 · 재고 이동/조정 | 마이너스 재고 거부(동시 출고 2건 경쟁 포함), 원장 UPDATE/DELETE 42501·트리거, 이동 쌍 합 0(트리거), 역분개의 역분개 거부, 대사 불일치 주입 → 알림 1건·자동 보정 없음, LOT 관리 품목 거부(1부) |
| **W4** | **차감 전략**: `AllocationStrategy`(FIFO·FEFO·지정 LOT 우선·합격분만·유효기한 지난 것 제외·로케이션 피킹 순서) 순수 함수 + 회사·품목 클래스 정책 `WMS_ALLOC_STRATEGY` + 미리보기 API("이 품목 120 EA 를 꺼내면 어느 위치·LOT 에서") | 현재고 화면의 "출고 후보 미리보기" | 표 기반 테스트(동률·부족·0·음수 요청·유효기한 오늘 경계·지정 LOT 이 다른 품목·한 위치에 여러 LOT), 결과 합 = 요청 또는 부족량 명시 |
| **W5** | **실사·기초 재고·기간 마감**: `wms_count`(유형 OPENING/CYCLE/FULL, 범위 = WH·LOC·품목, 블라인드 여부, 상태 DRAFT → COUNTING → REVIEW → POSTED/CANCELLED) + `wms_count_line`(장부 수량 = **센 시각 기준 원장 합**, 센 수량·센 사람·시각, 재실사) → 확정 = `COUNT_ADJ`/`OPENING` 원장, 차이 허용 한도 넘으면 APPROVE 권한. 기간 마감 `sys_period_close`(공장 × 모듈 × 월, 마감·재개는 APPROVE + 사유) + 원장 INSERT 트리거가 마감 기간 `occurred_at` 거부 | 재고 실사 · 기간 마감 | 실사 중 이동이 있어도 차이 = 센 수량 − 그 시각 장부(이중 차감 없음), 확정 두 번 = 한 번만, 마감 월 소급 입력 55000, 재개 기록·감사, 블라인드 실사에서 장부 수량 응답에 없음 |

## 3. 1부 공통 규칙

| 규칙 | 내용 | 근거 |
|---|---|---|
| 원장 | `inv_trx` 는 25 3장 원장 규칙 그대로(두 시각·`entry_method`·`recorded_by`/`performed_by`·`reversal_of_id`·`client_req_id`·트리거·REVOKE·카탈로그 원장 목록 등록) | 01 3.1, 25 3장 |
| 수량 부호 | 행마다 **부호 있는 수량**(들어오면 +, 나가면 −), 단위는 품목 기본 단위 하나(환산은 입력 때 서비스가, 원장에는 기본 단위 + 입력 단위·입력 수량을 참고로) | 02 6.2-D, 17 2장 |
| 이동 | 위치·상태 이동은 **같은 `move_grp_id` 의 두 행(−/+), 합 0** — 지연 제약 트리거(`DEFERRABLE INITIALLY DEFERRED`)가 트랜잭션 끝에 쌍의 합을 검사 | 02 |
| 투영 | `inv_stock` 은 `inv_trx` AFTER INSERT 트리거만 고친다(앱 계정 INSERT·UPDATE·DELETE 회수 — 읽기만). 키 = 공장 × 품목 × 위치 × LOT(nullable) × 재고 상태, `UNIQUE NULLS NOT DISTINCT`(PG15+) | 01 3.1 대사, 00 결함 2(규칙 SQL 한 곳) |
| 동시성 | 마이너스 거부는 `inv_stock` `CHECK (qty >= 0)` — 두 출고가 동시에 와도 행 잠금 + 제약으로 하나만 통과, 위반(23514 `ck_inv_stock_qty`)은 409 `INSUFFICIENT_STOCK`(부족 키·현재 수량) | 24 C |
| 재고 상태 | `stock_status` AVAILABLE(가용)·QC(검사 대기)·HOLD(보류)·BLOCKED(부적합) — 상태 바꿈도 "이동"(같은 위치, 다른 상태). 1부는 조정·이동에서 상태 선택만, 자동 전환(검사 합격 → 가용)은 2부 | 00 3장 품질 게이트, SAP 재고 유형 관행[추정] |
| 위치 | 원장 위치 = `mst_equip_hier` 의 WH 또는 LOC 노드(트리거: 같은 회사·같은 공장·`wms_loc_attr.stock_yn = 'Y'`·사용 중). 공장은 위치에서 파생하되 행에 남긴다(조회·마감 키) | 19 |
| 출처 | `src_type`(MANUAL/COUNT/OPENING/… 2부 PRD_RECEIPT/MAT_RECEIPT/WO_ISSUE/SHIP)·`src_id` — 2부 출처가 늘어도 칸은 그대로 | 25 D2 |
| 정정·override | 정정 = 역분개(사유 필수). 화면의 "수정" 없음. 스캔 대신 손 입력·혼적 규칙 무시 등 **우회는 사유 코드 + 권한 + 접속 로그 `action=OVERRIDE`** — override 비율이 KPI | 11 2.11.3 |
| 기간 마감 | 마감된 공장·월의 `occurred_at` 원장 INSERT 는 트리거가 거부(55000 `ck_inv_trx_period_closed`). 재개는 APPROVE + 사유, 마감·재개 모두 감사 | 01 3.1, 02 6.2-D |
| 대사 | 매일 회사마다(`TenantRunner` + `JobLock`) `sum(inv_trx) group by 키` 와 `inv_stock` 비교 → 불일치는 알림(아웃박스 `NOTIFY`) + 결과 행, **자동 보정 없음** | 01 3.1 |
| 외부 송신 | 라벨은 아웃박스 3단 — 업무 트랜잭션 안에서 프린터에 붙지 않는다 | 01 5.8 |
| 판정 함수 | `ScanKeyResolver.resolve(text, plantCd)`, `ZplRenderer.render(tpl, values)`, `AllocationStrategy.allocate(candidates, qty, options)` — **순수 함수 + 표 기반 테스트** | 29 3장 |
| 병행 | 29 6장 규칙 그대로: 이 브랜치(`claude/p4-wms-fbb0da`)에서 다음 Flyway 번호(지금 V037~), main 에 늦게 들어가는 쪽이 번호 재부여 / 문구 키 `wms.`·`js.wms` 접두어 / 공통 `hb.*` JS·CSS 수정 금지 / P2 테이블(`prd_*`·`lot_*`·`sal_*`)·BOM·라우팅 코드 수정 금지 | 29 6장 |
| 테스트 | 25 3장 그대로 + **동시 출고 경쟁**(C)·**마감 경계**(회사 시간대 월말 23:59/00:00)·**ZPL 주입**·가짜 프린터 장애 | 24 |

## 4. 단계별 데이터 모델 (요약 — 단계 착수 때 상세를 이 문서 해당 절에 추가)

```text
── W1 로케이션 속성·스캔 키 ─────────────────────────────────
wms_loc_attr         (comp, node_id(mst_equip_hier, WH·LOC), loc_usage STORAGE/RECEIVING/QC/STAGING/LINESIDE/SCRAP,
                      stock_yn(재고 둘 수 있음 — 상위 랙 노드는 N 가능), pick_seq int, mix_item_yn, mix_lot_yn,
                      default_status nullable(QC 구역 → 들어오면 QC 상태 제안), row_ver)
                     ← P1 테이블을 고치지 않는 1:1 곁 테이블(19 "용량·혼적·피킹 순서는 P4"). 행이 없으면 기본값(STORAGE·Y·Y·Y)
                     용량(max_qty·부피)은 하지 않음(9장)
ScanKeyResolver      내부 형식  LOC:{plant}:{loc_cd} · ITEM:{item_cd} · LOT:{lot_no}(2부) · PLT:{pallet_no}(2부)
                     GS1-128   (00)SSCC (01)GTIN (10)배치 (17)유효기한 YYMMDD (30)/(37)수량 — FNC1·GS(\u001d) 구분
                     결과 = (type, key, extra{batch, expiry, qty}) | UNKNOWN(이유)  — DB 를 보지 않는다. 존재 확인은 호출한 서비스
                     접두어 없는 순수 코드는 정책 WMS_SCAN_BARE_AS(LOC/ITEM/NONE, 기본 LOC — 기존 로케이션 라벨 호환)

── W2 라벨·인쇄 큐 ─────────────────────────────────────
wms_printer          (comp, plant, printer_cd, printer_nm, lang ZPL, host, port(기본 9100), dpi 203/300/600,
                      usage LOC/LOT/SHIP/ANY, use_yn, row_ver)
                     host 는 IP 리터럴 또는 이름 → 송신 때마다 해석 후 사설 대역 검사(8장 D6)
wms_label_tpl        (comp, label_type LOC/ITEM/LOT/PALLET/SHIP, tpl_cd, tpl_nm, width_mm, height_mm, dpi, body(ZPL, 최대 32KB),
                      default_yn(유형별 1건 — 부분 유일), use_yn, row_ver)
                     자리표시자 {{name}} — 라벨 유형별 허용 목록(LabelFields: LOC = plantCd·locCd·locNm·path·barcode …)
                     밖의 이름은 저장 때 칸 오류, 렌더 때 값은 ^FH\ 와 함께 ^ ~ \ 를 _5E _7E _5C 로(명령 주입 방지)
                     한글은 ^CI28(UTF-8) + 프린터에 한글 글꼴 필요 — 템플릿 도움말에 적는다
wms_print_job        (comp, job_id(채번 없음 — 4.2), printer_id, tpl_id, label_type, biz_key(예 LOC:123 / LOT:…), copies 1~99,
                      zpl_sha256, outbox_message_id, reprint_of_id nullable, reprint_reason nullable,
                      requested_by, requested_at)
                     ← 상태 칸 없음: sys_outbox.status(NEW/SENDING/SENT/FAIL) 를 message_id 로 조인해 보여 준다
                     렌더된 ZPL 은 아웃박스 payload 에만(인쇄 작업 행에는 해시) — 재발행은 같은 값으로 다시 렌더
LabelPrintService    request(labelType, bizKey, values, printerCd|null, copies) — 업무 트랜잭션 MANDATORY
                     → 템플릿 고르기 → 렌더 → 작업 행 → OutboxService.publish("LABEL_PRINT", job_no, {host, port, zpl})
                     ★ P2 의 LOT 라벨·2부 출하 라벨이 부르는 계약(25 6장 "LOT 라벨 출력 요청을 아웃박스 topic 으로만")
LabelPrintHandler    OutboxHandler(topic LABEL_PRINT): 소켓 연결 3초·쓰기 5초 타임아웃, 주소 재검사, retryOnReadTimeout=true
                     (라벨 중복보다 누락이 나쁘다 — 단 재발행 표시는 화면이 "같은 작업 재시도" 로 구분)
FakeLabelPrinter     테스트 전용 TCP 서버(받은 바이트 기록·지연·끊김 흉내) — 데모 프로필은 화면에 "받은 ZPL" 보기

── W3 재고 원장 엔진 ──────────────────────────────────
inv_trx              원장 (comp, plant, trx_type, item_id, node_id(위치), lot_id nullable(1부 항상 null — FK 는 2부),
                      stock_status, qty numeric(18,6) ≠ 0 (부호), unit_cd(기본 단위),
                      in_qty·in_unit_cd nullable(입력 단위 참고), move_grp_id nullable, src_type, src_id nullable,
                      reason_cd nullable(코드 WMS_ADJ_REASON), remark, client_req_id nullable,
                      occurred_at, recorded_at, entry_method, recorded_by, performed_by, reversal_of_id)
                     trx_type: OPENING · ADJ_IN/ADJ_OUT · MOVE_OUT/MOVE_IN · STATUS_OUT/STATUS_IN · COUNT_ADJ
                               (2부 추가: RCV_PRD · RCV_MAT · RCV_PO · ISSUE_WO · RETURN_WO · SHIP · SCRAP · RETURN_CUST)
                     유일 (comp, client_req_id) 부분 · 역분개 대상은 한 번만(부분 유일 reversal_of_id)
                     인덱스 (comp, item_id, occurred_at, trx_id) — 수불 조회 keyset, (comp, plant, occurred_at)
                     보존 클래스 QUALITY_RECORD(LOT 추적 근거 — 8장 D8)
inv_stock            투영 (comp, plant, item_id, node_id, lot_id nullable, stock_status, qty ≥ 0, last_trx_id, updated_at)
                     UNIQUE NULLS NOT DISTINCT (comp, item_id, node_id, lot_id, stock_status) — 수량 0 행은 남김(최근 위치 이력)
                     앱 계정 SELECT 만, 트리거 함수 hb_inv_apply()(SECURITY INVOKER — RLS 그대로)가 upsert
inv_reconcile_run    (comp, run_at, key_count, mismatch_count, detail jsonb(최대 100건)) ← 대사 결과, 일반 테이블
InventoryService     post(List<TrxReq>) MANDATORY — 형식 검사(FieldErrors) → 위치·품목·LOT 비관리(1부) 검사 → INSERT(트리거가 투영)
                     → 23514 ck_inv_stock_qty 를 INSUFFICIENT_STOCK 로, 같은 client_req_id 는 처음 결과 그대로
                     reverse(trxId, reason) — 이동이면 쌍 전체를 함께 역분개
                     stockAsOf(keyFilter, Instant) — 원장 합(실사 장부 수량용)

── W4 차감 전략 ───────────────────────────────────────
AllocationStrategy   allocate(List<Candidate>, BigDecimal qty, Options) → (List<Pick>, shortage)
                     Candidate = (nodeId, pickSeq, lotId|null, lotNo, fifoDate, expiryDate|null, status, qty)
                     Options   = (strategy FIFO/FEFO/LOC_SEQ, designatedLots[], availableOnly(합격분만), excludeExpiredOn(날짜), allowPartialLot)
                     정렬: 지정 LOT → (FEFO: 유효기한 null 은 맨 뒤) → fifoDate → pickSeq → nodeId → lotId (결정적 동률 깨기)
                     유효기한·fifoDate 의 출처는 2부(lot_master·자재 입고) — 1부는 입력 칸과 테스트만
                     품목 유효기간 일수 = 품목 클래스 속성 SHELF_LIFE_DAYS(22 7b attrs, semantic ID) — mst_item 칸 추가 안 함(8장 D5)

── W5 실사·마감 ───────────────────────────────────────
wms_count            (comp, plant, count_no 채번, count_type OPENING/CYCLE/FULL, scope_node_id(WH·LOC), item_filter jsonb nullable,
                      blind_yn, status DRAFT/COUNTING/REVIEW/POSTED/CANCELLED, started_at, posted_at, posted_by, tol_pct, row_ver)
wms_count_line       (count_id, node_id, item_id, lot_id nullable, stock_status, book_qty(센 시각 기준 장부 — 서버 계산),
                      counted_qty nullable, counted_at, counted_by, recount_no, diff_qty(파생), approved_yn, remark)
                     유일 (count_id, node_id, item_id, lot_id, stock_status, recount_no) — 같은 칸 두 번 세기는 재실사 번호로
                     확정 = 차이 ≠ 0 인 줄마다 COUNT_ADJ(OPENING 은 OPENING) 원장, src_type=COUNT·src_id=count_line_id
                     차이율 > tol_pct(정책 WMS_COUNT_TOL_PCT) 인 줄은 APPROVE 권한만 확정
sys_period_close     (comp, plant, module INV, period yyyymm, status CLOSED/REOPENED, closed_at/by, reopened_at/by, reason)
                     ← 00 5.3 이름 그대로(P4 가 처음 만든다 — 모듈 칸으로 다른 모듈도 같은 표). 월 경계 = 공장 시간대(25 D5)
```

### 4.1 W1 상세 — 로케이션 속성·스캔 키 (V037)

**`wms_loc_attr`** (PK = `node_id`, 장비 계층 WH·LOC 1:1)

| 칸 | 규칙 |
|---|---|
| 노드 | 같은 회사의 WH·LOC 만(트리거 `ck_wms_loc_attr_node_type`·`fk_wms_loc_attr_node_comp`), **처음 붙일 때만** 사용 중 노드(`_node_use`) — 이미 있던 속성은 노드가 사용 중지된 뒤에도 고칠 수 있다(새 참조만 차단), 대상 노드 고정(`_node_fixed`), 삭제 없음(기본값으로 저장) |
| 행 없음 | 화면·API 는 기본값(STORAGE · 재고 둠 Y · 피킹 0 · 혼적 Y·Y · 기본 상태 없음)으로 보여 주고 `rowVer` = null. 저장 = rowVer null 이면 INSERT, 있으면 그 값으로 UPDATE — 어긋나면 409(동시 첫 저장은 PK 대기 → 409) |
| 하위에도 적용 | 용도·품목/LOT 혼적·기본 상태만 하위 WH·LOC 에 upsert(값이 같으면 건드리지 않음). **재고 둠·피킹 순서·비고는 퍼지지 않는다**(랙 N · 칸 Y 처럼 층마다 다름). 사용 중지 하위에는 새로 붙이지 않는다. 부모 기준 advisory 잠금 |
| 피킹 순서 다시 매기기 | 하위를 트리 순서(순서 → 코드)대로 step(기본 10, 1~1000) 간격. 바뀐 행만, 999,999 넘으면 `PICK_SEQ_OVERFLOW` |
| 기본 상태 | 제안만 — 화면은 용도를 QC·SCRAP 으로 고를 때 비어 있으면 QC·BLOCKED 를 채운다 |

**스캔 확인** `POST /api/wms/loc/scan {plantCd, text}` — `ScanKeyResolver`(순수 함수) 판별 → LOC 면 공장 안 코드로 노드 찾기(없음·창고 아님·사용 중지 구분), ITEM 이면 품번, GS1 은 읽은 값만(GTIN ↔ 품목 매핑은 2부), LOT·PLT 는 "2부에서". 판별 실패도 200 + 이유(확인 칸), 공장만 400. 바코드 글자는 본문으로(URL·접속 로그에 남기지 않음).
GS1 판별 조건: 심볼 식별자 `]C1`·`]d2`·`]Q3`·`]e0`, GS(0x1D), 또는 괄호 형식 — **숫자만 있는 코드는 GS1 로 보지 않는다**(로케이션 코드가 숫자일 수 있다). 날짜 YY 는 오늘 기준 -49~+50 년 창, DD=00 은 그 달 말일.

### 4.2 W2 상세 — 라벨 템플릿·프린터·인쇄 큐 (V038)

| 대상 | 규칙 |
|---|---|
| 템플릿 검사 `ZplRenderer.validate` | `^XA … ^XZ`, 자리표시자 `{{이름}}` 은 유형별 허용 목록(`LabelFields`)만 + **`^FD … ^FS` 안에만**(명령 인자 자리 금지), 금지 명령 `^CC ~CC ^CT ~CT ^CD ~CD ^JU ~JR ^ID ^FH`(제어문자 바꾸기·설정 저장·초기화·삭제·렌더러 몫), 짝 안 맞는 `{{ }}` 거부, 32KB |
| 렌더 `ZplRenderer.render` | 값이 든 필드 앞에 `^FH_` 를 넣고 **그 필드의 글자·값 모두** `_ ^ ~` → `_5F _5E _7E`(템플릿 글자의 `_` 도 이스케이프 문자로 읽히므로). 값은 200자·제어문자 거부, 허용 밖 이름 거부, 결과 40KB(아웃박스 본문 64KB 여유) |
| 기본 템플릿 | 유형마다 1건(부분 유일). 다른 것을 기본으로 저장하면 같은 트랜잭션에서 이전 기본을 내림, 동시 지정은 409. 사용 중지면 기본 불가. 유형·코드 고정 |
| 프린터 주소 `PrinterAddress` | 저장·요청 때는 **DNS 없이** 형식 + IP 글자면 사설 대역(10/8·172.16/12·192.168/16·fc00::/7) + 포트가 정책 `WMS_PRINTER_PORTS`(기본 9100~9103) 안. 루프백은 설정 `happyboot.wms.print-allow-loopback`(local·test 만 true). `010.0.0.1`(8진수 해석)·`300.1.1.1` 은 형식 오류 |
| 요청 `LabelPrintService.request` | 업무 트랜잭션 MANDATORY. 프린터(사용 중·용도 ANY 또는 같은 유형)·템플릿(지정 또는 기본·같은 유형·사용 중) → 항목마다 렌더 → 아웃박스 `LABEL_PRINT`(host·port·zpl·copies) → 작업 행. 500장·99부 상한. 업무가 롤백되면 작업·아웃박스 모두 없음 |
| 송신기 `LabelPrintHandler` | 트랜잭션 밖. **보낼 때마다 이름을 해석해 모든 주소가 허용 대역인지 보고, 검사한 그 주소로 연결**(DNS 재바인딩). 주소 문제 = FAIL(재시도 무의미), 연결·쓰기 실패 = 재시도. 연결 3초·소켓 5초. 같은 메시지 두 번 = 한 장 더(누락보다 낫다) |
| 작업 `wms_print_job` | 추가만(앱 UPDATE·DELETE 회수). 상태 칸 없음 — `sys_outbox.status` 를 message_id 로 조인. 렌더 결과 ZPL·값·해시를 남겨 **재발행은 같은 ZPL**(프린터만 바꿀 수 있음). 재발행 사유 2자+(CHECK), LOT·PALLET·SHIP 은 APPROVE. 보존 `QUALITY_RECORD` |
| 로케이션 라벨 | `POST /api/wms/loc/print`(wms-loc PRINT): 고른 노드(+ 하위), 같은 공장·사용 중 WH·LOC 만, 값 = 공장·코드·이름·경로·용도·바코드 `LOC:{공장}:{코드}`, 대상(bizKey) = 바코드 값 |

### 4.3 W3 상세 — 재고 원장 엔진 (병합 때 V048, 화면·마무리는 이 브랜치)

| 대상 | 규칙 |
|---|---|
| 원장 `inv_trx` | 4장 표의 칸 + `req_seq`(요청 안 행 순서). CHECK: 유형 8종·상태 4종·입력 방식(KEY/SCAN/AUTO/IMPORT/CORRECT)·**부호**(들어오는 유형 +, 나가는 유형 −, 역분개는 반대, 실사 조정은 자유)·이동 유형 ↔ `move_grp_id` 필수·`CORRECT` ↔ 역분개 + 사유·수량 0 금지. 앱 UPDATE·DELETE 회수 + `hb_forbid_update_delete` |
| INSERT 검사 `hb_guard_inv_trx` | 같은 회사 품목·위치·작업자(23503), 단위 = 품목 기본 단위(`ck_inv_trx_unit`), **LOT 관리 품목·lot_id 거부**(1부, `ck_inv_trx_lot_item`), 위치 = 같은 공장 WH·LOC. 새 사실은 사용 중 품목 + 사용 중 + `stock_yn = 'Y'` 위치만. 역분개는 원래 행의 정확한 거울(유형·품목·위치·상태·−수량·공장)만, 역분개의 역분개 없음(`ck_inv_trx_reversal_mirror`), 한 행 한 번(부분 유일) |
| 투영 `hb_inv_apply` | AFTER INSERT: **UPDATE 먼저**, 키가 없을 때만 INSERT … ON CONFLICT. (ON CONFLICT 를 먼저 쓰면 "넣으려던 행"(출고면 음수)에 CHECK 가 먼저 걸려 재고가 있어도 출고가 막힌다 — W3 작업 중 발견.) `inv_stock` 직접 INSERT·UPDATE·DELETE 는 트리거 깊이로 거부(55000 `ck_inv_stock_projection`) — SECURITY DEFINER 없이 |
| 이동 짝 `hb_check_inv_move` | 지연 제약 트리거: 같은 `move_grp_id` = 정확히 2행·합 0·같은 품목·LOT·공장·서로 다른 (위치, 상태)·같은 종류의 OUT/IN(23514 `ck_inv_trx_move_pair`) |
| 게시 `InventoryService.post(rows, clientReqId)` | **다른 모듈이 부르는 계약**(MANDATORY). 형식 검사 → INSERT(트리거가 투영). `clientReqId` 가 이미 있으면 아무것도 넣지 않고 처음 행 id(`replay = true`), 동시에 같은 id 는 409 `DUPLICATE_REQUEST`. 23514 `ck_inv_stock_qty` → 409 `INSUFFICIENT_STOCK`, 트리거 제약 → 칸 오류(`CONSTRAINT_FIELD`) |
| 역분개 `reverse(trxId, reason)` | 사유 2~500자. 이동·상태 변경은 **짝 두 행을 함께** 새 짝으로 되돌린다. 원래 발생 시각 그대로(기간 마감 W5 가 막는다), 되돌리면 마이너스면 409(그 사이 써 버림) |
| 화면 입력 `StockService` | 조정(증감 부호 = 방향, 사유 코드 `WMS_ADJ_REASON` 필수) · 이동(같은 위치면 상태 변경). 발생 시각 = 공장 현지 시각(비우면 지금, DST 없음·5분 넘는 미래 거부). 입력 방식은 KEY·SCAN 만. 혼적 금지 위치(`mix_item_yn = 'N'`)에 다른 품목이 있으면 `MIX_ITEM`(조정 = `nodeId`, 이동 = `toNodeId`) |
| 조회 | 현재고: 공장 필수, 위치(하위 포함 기본), 품번 앞 일치·품명 포함(LIKE 이스케이프), 0 숨김 기본, 행 → 그 키 최근 수불 20건. 수불: 발생일 기간 필수(92일 상한, 공장 시간대 하루) + 페이지 + 엑셀. 엑셀 요청은 hb.grid 가 보내는 평평한 조건 + sort·columns |
| 대사 `InventoryJobs.reconcile` | 매일 03:40 회사마다(`TenantRunner` + `JobLock`) 원장 키별 합 FULL JOIN 현재고 → 결과 행 `inv_reconcile_run`(불일치 최대 100건), 불일치면 아웃박스 `NOTIFY`. **자동 보정 없음**. 화면 "대사 실행" = FN1 |

### 4.4 W4 상세 — 차감 전략 (임시 V9300, 병합 때 재번호)

| 대상 | 규칙 |
|---|---|
| 순수 함수 `AllocationStrategy.allocate(candidates, qty, options)` | DB 를 보지 않는다. 결과 = 꺼낼 칸·수량 + 부족량 + 제외(이유) + 후보에 없는 지정 LOT. **꺼낸 합 + 부족 = 요청**(시험 불변식). 요청 0·음수·없음은 IllegalArgumentException(서비스가 칸 오류로) |
| 꺼낼 수 있는 상태 | AVAILABLE, "합격분만" 을 끄면 QC 도. HOLD·BLOCKED 는 항상 제외(`STATUS`) |
| 유효기한 | `excludeExpiredOn` 날짜보다 **앞**이면 제외(`EXPIRED`) — 기한 당일까지 사용. 끄면 지난 것도 꺼낸다(판단은 부르는 쪽) |
| 정렬 | 지정 LOT(목록 순서) → 전략 키 → 위치 id → LOT id(없음 먼저) → 상태. FIFO = 입고일(없으면 맨 뒤) → 피킹 순서, FEFO = 유효기한(없으면 맨 뒤) → 입고일 → 피킹 순서, LOC_SEQ = 피킹 순서 → 입고일. 입력 순서와 무관하게 같은 결과 |
| 통째로만 | `allowPartialLot = false` 면 LOT 이 있는 후보는 남은 요청보다 크면 건너뜀(`WHOLE_LOT` — 파렛트·포장 단위). LOT 없는 후보는 나눈다 |
| 전략 결정 | 화면에서 고름 → 품목 클래스 속성 `ALLOC_STRATEGY`(값이 틀리면 무시) → 정책 `WMS_ALLOC_STRATEGY`(공장 → 회사, 기본 FIFO). 품목 칸은 늘리지 않음(D5 와 같은 방식) |
| 후보(1부) | 현재고(> 0, LOT 없음, 범위 = 고른 위치 하위 포함) + 피킹 순서(`wms_loc_attr`) + **입고일 = 그 칸 원장을 시각순으로 더해 잔량이 마지막으로 0 이하이던 행 뒤의 첫 입고**(지금 남은 물건이 들어오기 시작한 날, 공장 시간대). 유효기한 = 입고일 + 품목 클래스 속성 `SHELF_LIFE_DAYS`(있을 때만). 2부는 LOT 유효기한·입고일 |
| 미리보기 API | `POST /api/wms/stock/alloc-preview`(wms-stock SEARCH) — 저장·예약 없음. 동시에 다른 출고가 일어나면 실제 차감은 W3 원장 CHECK 가 막는다(미리보기는 약속이 아니다) |

### 4.5 W5 상세 — 실사·기초 재고·기간 마감 (V074)

| 대상 | 규칙 |
|---|---|
| 실사 머리 `wms_count` | 공장 × 범위(WH·LOC, 하위 포함) × 품목(선택, 1개 — 4장 초안의 `item_filter jsonb` 대신 단순화), 유형 OPENING/CYCLE/FULL, 블라인드(기본 Y), 허용 % = 만들 때 정책 `WMS_COUNT_TOL_PCT`(공장별, 기본 5) 를 복사. 번호 `NumberingService.nextOrDefault('WMS_COUNT', CNT-{yyMM}-{seq:4})`. 상태 전이 표·키 고정·시작 뒤 범위 고정·확정/취소 뒤 불변은 트리거(`ck_wms_count_transition`·`_fixed`·`_done`), 취소는 사유(CHECK), 삭제 없음 |
| 실사 줄 `wms_count_line` | 칸(위치 × 품목 × LOT × 상태) × 재실사 번호 유일(NULLS NOT DISTINCT). **장부 = 센 시각까지 원장 합**(서버 계산, `InventoryService.ledgerQtyAsOf`), 센 수량·시각·사람·장부는 함께(CHECK), 차이 = 생성 칸. 머리가 DRAFT·COUNTING 일 때만 추가·수정, 원장 id 는 머리가 REVIEW 이고 그 줄을 출처로 하는 COUNT 원장일 때만(트리거) |
| 흐름 | 만들기(DRAFT) → 시작(COUNTING, 범위 안 현재고 칸마다 줄 — OPENING 은 줄 없음, 5,000줄 상한) → 줄 추가(찾은 물건·기초 재고, 범위·품목 안) → 센 수량(빈 칸은 건너뜀, 미래·시작 전 시각 거부 — OPENING 은 과거 시각 허용) → 검토(REVIEW, 마지막 재실사 줄 모두 셈) → 재실사(고른 칸 +1 줄, COUNTING 으로) → 확정(POSTED) / 취소 |
| 블라인드 | DRAFT·COUNTING 동안 장부·차이·차이율을 **응답에 넣지 않는다**(화면 숨김이 아니라 서버) |
| 확정 | 머리 FOR UPDATE → rowVer → REVIEW. 칸마다 마지막 줄의 차이 ≠ 0 → 원장 COUNT_ADJ(OPENING 유형은 OPENING — 장부보다 적으면 `OPENING_BELOW_BOOK`), **발생 시각 = 센 시각**, 출처 COUNT·줄 id → 줄에 원장 id → 머리 POSTED. 한도를 넘는 줄(장부 0 에 차이 포함)이 있으면 APPROVE 없이는 403 `WMS_COUNT_APPROVAL`. 확정 사이 재고가 나가 마이너스면 409 전부 롤백. 두 번·동시 확정은 한 번만(뒤는 409) |
| 기간 마감 `sys_period_close` | **공통 표**(공장 × 모듈 `module_cd` × 월 — INV 가 첫 사용자, MES 실적·CMS·QMS 측정 원장도 같은 표에 자기 모듈 코드로). 상태 CLOSED/REOPENED, 재개 = 시각·사람·사유(CHECK), 마감·재개는 감사 트리거, 삭제 없음 |
| 마감 검사 | `inv_trx` BEFORE INSERT 트리거 `hb_check_inv_period`: 공장 시간대(없으면 회사) 기준 월이 CLOSED 면 55000 `ck_inv_trx_period_closed` → 409 `PERIOD_CLOSED`. 역분개도 원래 발생 시각이라 같이 막힌다. **마감과 원장 동시**: 원장은 (회사·공장·모듈·월) advisory 잠금을 공유로, 마감·재개는 배타로(`hb_period_lock_key`) — 진행 중인 그 달 원장이 커밋될 때까지 마감이 기다리고, 마감 중에 들어온 원장은 마감 커밋 뒤 막힌다 |
| 마감 API | 지난 달까지만(공장 시간대, `NOT_PAST_MONTH`), 재개 = 사유 2~500자 + rowVer. 마감 전 점검(미확정 실사 수·마지막 대사 불일치)은 경고만. 둘 다 `wms-period` APPROVE |

## 5. 화면

| 화면 | prog_cd | 액션 | 메모 |
|---|---|---|---|
| 로케이션 속성 | `wms-loc` | SEARCH·SAVE·PRINT·EXCEL | 공장 선택 → 왼쪽 WH·LOC 트리(장비 계층 화면과 같은 `hb.tree`, WH·LOC 만) / 오른쪽 속성 폼, 여러 노드 골라 "라벨 인쇄"(프린터·템플릿 선택), 스캔 확인 칸("바코드를 찍으면 무엇으로 읽히나") |
| 라벨 템플릿·프린터 | `wms-label` | SEARCH·SAVE·DELETE | 탭 2개. 템플릿 = ZPL 본문 + 허용 자리표시자 목록 + "견본 값으로 렌더"(ZPL 글자 그대로 보기, 가짜 프린터 프로필이면 그 프린터로 시험 인쇄). 외부 미리보기 서비스는 쓰지 않음(9장) |
| 인쇄 이력 | `wms-print-job` | SEARCH·PRINT | 기간 필수, 상태(아웃박스 조인)·실패 사유, 재발행(사유 필수 — LOT·출하 라벨 재발행은 중복 라벨 위험이라 APPROVE) |
| 현재고 | `wms-stock` | SEARCH·EXCEL·FN1(대사 실행) | 공장·창고(하위 포함)·품목·상태, 수량 0 숨김 기본, 행 → 오른쪽에 그 키 최근 수불 20건(W3 — 수불 원장 화면 바로가기 대신), "출고 후보 미리보기"(W4) |
| 수불 원장 | `wms-trx` | SEARCH·SAVE(역분개)·EXCEL | 기간 필수 + 페이지(offset — **지연 조인 + 트랜잭션 custom plan**, 실측·결정 5.1), 역분개 쌍 표시, `entry_method`·대리 입력 열, 역분개는 오른쪽 상세에서(사유) |
| 재고 이동·조정 | `wms-move` | SEARCH·SAVE | 이동(위치·상태) / 조정(증감 + 사유 코드), 스캔 칸(위치·품목) 우선 — 위치 라벨은 비어 있는 위치 칸(출발 → 도착), 도착이 QC 구역이면 기본 상태 제안, 저장 = 원장 행 미리보기 확인 → 확정 1회(누른 순간 만든 요청 id), 오른쪽 오늘 입력. 역분개는 수불 원장 화면 |
| 재고 실사 | `wms-count` | SEARCH·SAVE·APPROVE·EXCEL (엑셀 내보내기·붙여넣기 12.12.14) | 실사 만들기(범위) → 줄 생성 → 센 수량 입력(엑셀 붙여넣기) → 검토(차이·재실사) → 확정. 블라인드면 장부·차이 열 숨김(서버가 안 보냄) |
| 기간 마감 | `wms-period` | SEARCH·APPROVE | 공장 × 월 목록, 마감·재개(사유), 마감 전 점검(미확정 실사·대사 불일치 경고) |

메뉴 그룹: 새 상위 메뉴 "창고"(WMS). PDA 화면(`/pop/**` 계열 재사용 — 입고·이동·피킹·실사)은 2부(P2 5a 의 `hb.pop`·단말 세션 뒤).

### 5.1 수불 원장 페이지 방식 실측 (2026-10-24, 상위 지시 — 시험·문서만, 코드 그대로)

`TrxPagingPerfTest`(`-Dhb.perf=true` 때만). Testcontainers PostgreSQL 16, 앱 계정·RLS 아래, 50행 페이지, 3번 중 최선. 문장 시간 제한 30초(운영 MyBatis `default-statement-timeout` 과 같음). SQL 은 `InvMapper.findTrxs` 와 같은 열(LOT 번호·역분개 부질의, `wms_node_path`)·조인·조건(공장·발생일 92일 안)·정렬(`occurred_at desc, trx_id desc`). 다른 회사 원장 20만 행을 섞어 RLS 가 거르게 했다. 세 방식이 같은 행을 돌려주는지 함께 검사.

| 원장(한 공장·기간 안) | 쪽(offset) | offset(지금) | 지연 조인 | keyset |
|---|---|---|---|---|
| 10만 | 1쪽(0) | 841ms | 7ms | 983ms |
| 10만 | 1,000쪽(49,950) | 3,125ms | 17ms | 405ms |
| 10만 | 2,000쪽 = 끝(99,950) | 5,588ms | 20ms | 6ms |
| 100만 | 1쪽(0) | 6ms | 7ms | 6ms |
| 100만 | 10,000쪽(499,950) | **시간 초과(>30초)** | 161ms | 7ms |
| 100만 | 20,000쪽 = 끝(999,950) | **시간 초과(>30초)** | 157ms | 8ms |
| 건수(count) | 10만 842ms(색인 계획) · 100만 172ms(병렬 순차 읽기) | | | |

**EXPLAIN 요약**

- offset 깊은 쪽(100만): `Seq Scan → Sort → Limit` 로 바뀐다. 그 전에 **건너뛰는 행마다 select 목록(부질의 2개·`wms_node_path`)을 계산**해, 50만~100만 번 함수를 부른다. 시간 초과의 원인이다. 10만에서도 끝 쪽은 temp 1,700 블록을 쓰고 5.6초.
- 지연 조인: 안쪽 `select trx_id … order by … limit 50 offset N` 이 `ix_inv_trx_plant` **Index Only Scan Backward** 로 id 만 고르고, 바깥에서 50행에만 열·부질의를 계산(pk 조회 50번). 100만 끝 쪽 버퍼 9,553·200ms 안. 건너뛰는 비용은 남지만 색인 항목만 읽는다.
- keyset: `(occurred_at, trx_id) < (직전 행)` 이 `ix_inv_trx_plant` Index Scan Backward 로 바로 그 자리에서 50행 — 어느 쪽이든 버퍼 ~410·5ms 안팎.
- 계획이 흔들린다. 10만일 때 offset·keyset 첫 쪽이 `ix_inv_trx_item` 을 골라 버퍼 180만·약 1초가 나왔다(RLS 조건 `comp_cd = hb_current_comp()` 와 통계 탓으로 추정 — 100만에서는 plant 색인을 골랐다). 지연 조인은 두 크기 모두 같은 계획(index-only)이었다.

**판단(제안 — 바꿀지는 상위 결정)**

| 안 | 바꾸는 곳 | 장단 |
|---|---|---|
| (가) 그대로 offset | 없음 | 앞쪽 몇 쪽은 빠르다. 깊은 쪽은 100만 규모에서 **화면이 30초 뒤 오류**(D8 보존 기간이 길어 원장은 쌓이기만 한다). 92일 안 한 공장 100만 행 = 하루 약 1.1만 행 — 소규모 공장엔 드물지만 PDA·자동 차감(ISSUE_WO)이 켜지면 현실적이다[추정] |
| **(나) 추천 → 채택: 지연 조인** | `InvMapper.findTrxs` SQL 하나(서버만) — 쪽 번호·`hb.grid`·API 그대로 | 끝 쪽도 0.2초. 계획도 안정. 엑셀(`cursorTrxs`)은 스트리밍이라 해당 없음 |
| (다) keyset | 서버 API(커서) + `hb.grid` 페이지 방식(공용) | 가장 빠르고 일정. 대신 "임의 쪽으로 건너뛰기"·쪽 수 표시가 사라지고 공용 그리드를 고친다 — 지금 차이(0.2초 대 0.01초)로는 바꿀 값이 없다 |

건수(count)는 100만에서 0.2초라 그대로 둔다. 같은 offset 모양의 다른 목록(출하·실사 grid 등)은 select 목록에 행마다 부르는 함수가 없거나 행 수가 작아 이번 실측 대상이 아니다.

**결정·구현(2026-10-24, 상위 승인 — (나) 지연 조인, keyset 은 하지 않음)**

- `InvMapper.findTrxs` = 안쪽 `trxKeys`(inv_trx 만, 품번 조건일 때만 품목 조인, 같은 조건·정렬·끝 키 `trx_id`) → 바깥에서 50행에만 열·부질의. 회사는 안팎 inv_trx 모두 RLS.
- **트랜잭션 custom plan**: 바꾼 매퍼를 그대로 쓰면 같은 문장이 5번 넘게 실행된 뒤 드라이버 준비 문장의 **일반 계획**(limit·offset 바인드 값을 모름)으로 바뀌어 100만 행 끝 쪽이 0.13초 → 3초. `StockService.trxs` 가 조회 전에 `set_config('plan_cache_mode', 'force_custom_plan', true)`(`InvMapper.customPlan`, 그 트랜잭션만).
- 현재고 `InvMapper.findStock`(QA 2026-10-24 M3) 도 같은 지연 조인. 기본 정렬이 품번·**위치 경로**라 경로를 정렬 키로 쓰면 1쪽도 행마다 `wms_node_path` — 안쪽은 그 공장 창고·위치마다 한 번 계산한 표 `p`(**MATERIALIZED** — 풀어 쓰면 다시 행마다 불려 시간 초과가 났다)와 조인. custom plan 은 차이가 없어(아래) 넣지 않았다.

| 바꾼 뒤 — 수불 원장(단독 실행) | 1쪽 | 중간 | 끝 |
|---|---|---|---|
| 10만 — 지금 offset / 바꾼 `trxs` | 978ms / 11ms | 4,158ms / 20ms | 4,748ms / 27ms |
| 100만 — 지금 offset / `findTrxs`(일반 계획) / 바꾼 `trxs`(+custom plan) | 7 / 9 / 9ms | 24,934ms / 1,947ms / **102ms** | **시간 초과** / 2,995ms / **135ms** |

| 바꾼 뒤 — 현재고(품목 × 위치 1,000, 기본 정렬) | 1쪽 | 중간 | 끝 |
|---|---|---|---|
| 10만 칸 — 바꾸기 전 / 바꾼 `findStock` | 54ms / 103ms | 1,628ms / 248ms | 4,810ms / 271ms |
| 50만 칸 — 바꾸기 전 / 바꾼 `findStock` | 42ms / 59ms | 8,596ms / 923ms | **25,847ms** / **1,242ms** |

- 현재고 1쪽은 위치 경로 표(위치 1,000번 계산)만큼 조금 늘었다(50ms 안팎). 깊은 쪽은 정렬할 행 수(칸 수)에 비례해 남는다 — 50만 칸은 소규모 공장에선 드물다[추정].
- 같은 시각 쪽 경계(`InventoryTest`: 같은 발생 시각 7행을 3행씩 → 빠짐·겹침 없음, 품번 조건 같은 결과, B사 0행)는 일반 시험으로 고정. 실측은 `TrxPagingPerfTest` 두 건(`-Dhb.perf=true`).
- 같은 문제(깊은 offset + select 목록의 무거운 계산)의 다른 화면은 QA 조사(docs/qa/2026-10-24_offset-deep-page.md) 몫.


## 6. 다른 단계·모듈과의 경계

| 대상 | 1부에서 | 넘기는 곳 |
|---|---|---|
| LOT 번호·LOT 마스터·계보 | 만들지 않는다. 원장 `lot_id` 칸만(항상 null) | P2 4단계 → 2부가 FK·LOT 관리 품목 허용 |
| 생산 입고 `prd_receipt`·자재 LOT 간이 등록 | 읽지 않는다 | 2부: 기존 행은 원래 `occurred_at` 그대로 `RCV_PRD`/`RCV_MAT` 로 한 번 이관, 이후 P2 서비스가 같은 트랜잭션에서 `InventoryService.post` 호출(8장 D2) |
| 투입 차감(POP LOT 스캔) | 차감 전략 함수만 | 2부 `ISSUE_WO`(P2 5b 계보 CONSUME 와 같은 트랜잭션) |
| 수입검사 → 가용 | 재고 상태 QC·AVAILABLE 칸만 | 2부(P3 2부 판정 → 동기 호출로 `STATUS_OUT/IN`) |
| 구매 입고(PO·납품) | 하지 않음 | SRM(docs/31)이 납품·입고 예정을, 실제 입고 원장 `RCV_PO` 는 WMS 2부 — 31 과 경계는 2부 착수 때 맞춤 |
| 출하·피킹·파렛트·출하 라벨 | 라벨 유형 SHIP·PALLET 자리, 차감 함수 | 2부(수주 `sal_order` P2 2단계 위에서) |
| LOT 라벨 | `LabelPrintService` 계약·LOT 템플릿 유형 | P2 가 LOT 생성 때 호출(병합 뒤) |
| PDA·오프라인 대기열 | 사무 화면 스캔 칸(키보드 웨지)만 | 2부(P2 5a `hb.pop`·`sys_terminal`·대기열 재사용, 증감 이벤트) |
| 설비 소모품·예비품 재고 | — | P5 CMS(docs/33)가 필요하면 같은 원장의 품목 유형 CON 으로 |
| ERP 재고 송신·창고 코드 매핑 | `IF_CODE_TYPE = WH` 는 P1 그대로 | P6 |

## 7. 진행

| 단계 | 상태 | 날짜 | 테스트 |
|---|---|---|---|
| W1 로케이션 속성·스캔 키(V037) | ✅ | 2026-10-20 | 750건(check), 신규 ScanKeyResolverTest 24·LocAttrTest 14. 브라우저 1366·390 — 발견·수정 3(TESTING.md) |
| W2 라벨·인쇄 큐(V038) | ✅ | 2026-10-20 | 805건(check), 신규 ZplRendererTest 43·LabelPrintTest 12(가짜 TCP 프린터). 브라우저: 템플릿·프린터·로케이션 라벨 4장 → 가짜 프린터(9100) 도착·이력·재발행. 발견·수정 4(TESTING.md) |
| W3 재고 원장 엔진(병합 때 V048, 마무리는 마이그레이션 없음) | ✅ | 2026-10-21 | 1,148건(check), `InventoryTest` 10(엑셀 2종 추가). 화면 3개(현재고·수불 원장·이동/조정) 브라우저 1366·390 — 발견·수정 4(TESTING.md) |
| W4 차감 전략(임시 V9300) | ✅ | 2026-10-21 | 1,205건(check, main 병합 뒤), 신규 `AllocationStrategyTest` 16(순수 함수 표 기반)·`AllocPreviewTest` 8. 현재고 화면 "출고 후보 미리보기" 브라우저 1366·390 — 발견·수정 1 |
| W5 실사·기초 재고·기간 마감(V074) | ✅ | 2026-10-21 | 1,346건(check), 신규 `CountTest` 10·`PeriodCloseTest` 6. 화면 2개 브라우저 1366·390 — 발견·수정 2 |
| 2부 B1 생산 입고 → 재고 원장(V079) | ✅ | 2026-10-21 | 1,369건(check), 신규 `ReceiptPostingTest` 10. 현재고 화면 "생산 입고 이관" 브라우저 1366 |
| D-B1 기본 단위 변경 금지(V091) | ✅ | 2026-10-21 | 신규 `ItemUnitLockTest` 3 |
| 2부 B2 LOT 관리 품목 재고 사무 화면(V092) | ✅ | 2026-10-21 | 1,459건(check), 신규 `LotStockTest` 8. 브라우저 1366 — LOT 스캔 조정·LOT 없음 칸 오류·LOT 조건 현재고 |
| 재고 조회 계약 `StockQuery.available`(13장, APS 자재 가용 — 마이그레이션 없음) | ✅ | 2026-10-21 | 신규 `StockQueryTest` 5 |
| 2부 B4a 자재 입고 RCV_MAT(12.3, V107) | ✅ | 2026-10-21 | 병합 뒤 check 전체 통과(날짜 경계 8건은 #140 에서 수정), 신규 `MaterialReceiptTest` 14. 브라우저 1366·390 — GS1 스캔 입고·LOT 라벨 대기열·공급사 LOT 중복 칸 오류·QC 구역 상태 제안 |
| CHECK NULL 구멍(`ck_inv_trx_correct`·`ck_wms_print_job_reprint`, V108(임시 V9306)) | ✅ | 2026-10-22 | 1,622건(check), 신규 `WmsCheckNullTest` 3 |
| 2부 B5 투입 차감 + LOT 변환 경계 합의안(12.4, 문서) | ✅ 확정 | 2026-10-22 | D-B11~D-B13 사용자 확정, 스위치 켜기 전제 충족(MES #167) |
| 2부 B5 계약 `LotStockLink`(12.4.2 — 인터페이스·NONE·of, MES 가 훅을 붙이는 자리) | ✅ | 2026-10-22 | 1,640건(check), 신규 `LotStockLinkContractTest` 2 |
| 2부 B5 투입 차감 ISSUE_WO + LOT 변환 CONV_OUT/IN 구현(12.4.7, V114(임시 V9307)) — MES 훅 전이라 공장 스위치는 끈 채 | ✅ | 2026-10-22 | 1,660건(check, main 24a8438 merge 뒤), 신규 `LotStockPostingTest` 14. 브라우저 1366·390 — 로케이션 보급 라인 칸(라인사이드일 때만), 로케이션 화면 390 가로 넘침(스캔 칸 size) 함께 고침 |
| 2부 B6 출하 경계 합의안(12.5, 문서) | ✅ 확정 | 2026-10-22 | D-B15~D-B20 추천안. 영업 계약 `OrderShipGate` 는 MES 합의·계약 PR 대기 |
| 2부 B3 품질 상태 연동 경계 합의안(12.15, 문서) | ✅ 확정(QMS 찬성 + 덧붙임 반영) | 2026-10-24 | D-B26~D-B30 질문. 코드는 합의 뒤(마이그레이션 1건 예상: 출처 QMS_INSP·QMS_NCR·유형 SCRAP·정책) |
| 다건 원장 LOT 선잠금(12.4.5 ②, 마이그레이션 없음) | ✅ | 2026-10-22 | 1,632건(check), 신규 `LotLockOrderTest` 1(선잠금을 빼면 교착 재현) |
| 2부 B5 MES 훅 통합 시험(실제 POP 실적·정정 경로, 마이그레이션 없음) | ✅ | 2026-10-22 | 신규 `PopStockLinkTest` 4 |
| 2부 B4b 구매 입고 RCV_PO(12.6, V122(임시 V9308)) | ✅ | 2026-10-22 | 1,717건(check, main a880060 merge 뒤 — 건너뜀 2), 신규 `PoReceiptTest` 10. 브라우저 1366·390(ZT 발주 → 발주 행 선택지·공급사 고정·40 입고·잔량 60·61 초과 409 문구, 390 가로 넘침 없음) |
| 재고 칸 잠금 순서 (품목·위치·LOT·상태)(11.6 ③, QA QB-1, 마이그레이션 없음) | ✅ | 2026-10-22 | 1,730건(check, main a02b8139 merge 뒤), 신규 `CellLockOrderTest` 2(정렬을 빼면 교착 재현) |
| 2부 B6a 출하 — 지시·피킹·확정·취소, 영업·PLM 게이트, 라벨·ERP_SHIP, 계약 `ShipQuery`(12.7, V130(임시 V9309)) | ✅ | 2026-10-22 | 1,778건(check, main 95934636 merge 뒤 — 건너뜀 3), 신규 `ShipTest` 10·`ShipPlmGateTest` 2. 브라우저 1366·390(ZT 수주 → 지시 → 제안 → 13 초과 칸 오류 → 12 피킹 → 확정 → 출하 취소, 390 가로 넘침 없음) |
| CMS 예비품 출고·반납 `SpareIssueLink` 구현(12.8, V132(임시 V9310)) | ✅ | 2026-10-22 | 1,788건(check, JDK 21, main fc6a20db merge 뒤 — 건너뜀 2), 신규 `MroIssuePostingTest` 9 |
| 2부 R3b ASN 입고(12.9, SRM R3a `AsnQuery`·게이트 ASN 오버로드 위에, 마이그레이션 없음) | ✅ | 2026-10-22 | 1,809건(check, JDK 21, main 770157af merge 뒤 — 건너뜀 2), 신규 `AsnReceiptTest` 4. 브라우저 1366·390(ZT 발주·ASN 은 SRM API → 도착 예정 선택지·ASN: 스캔·채우기·40 입고 → 남은 20, 390 가로 넘침 없음) |
| B6a 후속: 출하 화면 영업 표 읽기 → `OrderLineQuery`(12.7.5, 마이그레이션 없음) | ✅ | 2026-10-22 | 1,809건(check, JDK 21, main 770157af merge 뒤 — 건너뜀 2), `ShipTest` 10·`ShipPlmGateTest` 2 그대로 통과 |
| 2부 B6b-1 팔레트·SSCC — 담기·빼기·닫기(SSCC·라벨)·다시 열기·취소, 팔레트 필수 정책(12.10, 임시 V9311) | ✅ | 2026-10-22 | 1,834건(check, JDK 21 — 건너뜀 2, 실패 1 = `OpsConsoleTest` TOTP 30초 구간 경계 타이밍(WMS 무관, 단독 2회 통과)), 신규 `ShipPalletTest` 9. 브라우저 1366·390(ZT 지시 → 피킹 → 새 팔레트 → 담기 → 닫기(SSCC) → 닫힌 팔레트 피킹 취소 거부 → 다시 열기 → 닫기 → 출하 확정) |
| 2부 B6b-2 팔레트 스캔(PLT:·SSCC)·ERP_SHIP 팔레트 번호·SSCC 목록·출하 라벨 팔레트 수(12.10.5, 마이그레이션 없음) | ✅ | 2026-10-22 | 1,846건(check, JDK 21, main 042252a0 위 — 건너뜀 2, 실패 0), `ShipPalletTest` 9 → 11. 브라우저 1366·390(ZT `PLT:` 번호·SSCC 18자리 스캔 → 그 지시 열림·팔레트 행 표시, 검사 숫자 틀림·LOT·없는 번호 거부 문구, 390 가로 넘침 없음) |
| R3b 후속: 같은 공급사 LOT 나눠 받기 = 새 내부 LOT(12.9, docs/29 결정 #43 — MES V135, WMS 는 도움말·주석만) | ✅ | 2026-10-22 | 관련 시험 35건(`MaterialReceiptTest` 14·`AsnReceiptTest` 5·`PoReceiptTest` 11·`I18nKeyGuardTest` 5, main 5aa9fad8 위). 브라우저 390(ASN 40 입고 → 남은 20 같은 공급사 LOT → 새 LOT) |
| UI 단계 3 목록 틀 ②~⑥ — 화면 10개(12.11, 서버 변경 없음, ① 그리드 전환은 다음 PR — 결정 #45) | ✅ | 2026-10-22 | 1,855건(check, JDK 21, main 149c775d 위 — 건너뜀 2, 실패 0), page.css 추가 뒤 `DesignTokenGuardTest` 통과, JS 타입 검사·node 44. 브라우저 1366·390 화면 10개 전후 스크린샷(가로 넘침 없음, 콘솔 오류 0), 출하 대화상자 동작(1366) |
| UI 단계 3 ① 그리드 전환 1차 — 출하·실사 목록 `/grid`·`/columns`, 기존 그리드 정렬 키 하나 400 회귀 수정(12.11.1, 스키마 변경 없음) | ✅ | 2026-10-22 | 1,861건(check, JDK 21 — 건너뜀 2, 실패 0), 신규 `WmsListGridTest` 6. 브라우저 1366·390(출하·실사 그리드 정렬·페이지·행 선택 → 상세, 동작 뒤 같은 행 유지, 가로 넘침 없음) |
| UI 단계 3 ① 2차 — 라벨 템플릿·프린터 그리드, 출하·실사 상태 배지, 위치·라벨 미저장 확인 → 화면 안 대화상자(12.11.2, 스키마 변경 없음) | ✅ | 2026-10-22 | 1,863건(check, JDK 21, main 0e8be0d5 위 — 건너뜀 2, 실패 0), `WmsListGridTest` 6 → 7, JS 단위 51. 브라우저 1366·390(라벨 두 그리드·배지·미저장 확인 대화상자, 위치 트리 대화상자, 출하 상태 배지, 가로 넘침 없음) |

## 8. 결정 필요 (사용자)

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D1 | 1부에서 재고 원장을 실제로 만들까 | **만든다 — LOT 비관리 품목만**(소모품·부자재·LOT 안 쓰는 품목). `lot_id` 칸은 두고 FK·필수 규칙은 2부. 원장·투영·마이너스 동시성·역분개·대사·마감·실사를 P2 를 기다리지 않고 시험 | (B) 1부는 설계·순수 함수·라벨만, 원장 테이블은 2부 — 안전하지만 가장 무거운 엔진 시험이 P2 7단계 뒤로 밀림 / (C) WMS 가 LOT 마스터를 먼저 만듦 — P2 4단계와 이중 마스터 |
| D2 | P2 생산 입고·자재 LOT 를 원장에 붙이는 방식(2부) | **동기 호출 + 한 번 이관**: 기존 `prd_receipt` 는 원래 발생 시각으로 `RCV_PRD` 이관(마감 전 기간만), 이후 P2 서비스가 같은 트랜잭션에서 `InventoryService.post` — 00 "같은 트랜잭션이 필요한 모듈 간 반응은 동기" | 아웃박스로 비동기 반영 — 입고 직후 현재고가 잠깐 비고, 실패 재처리 경로가 하나 더 생김 |
| D3 | 마이너스 재고 | **허용 안 함**(`CHECK (qty >= 0)`, 정책 없음). 백플러시처럼 실물 먼저 나가는 현장은 2부에서 "부족 투입 = 대기 + 반장 내 할 일" 로 | 정책 `WMS_ALLOW_NEGATIVE`(품목 유형별) — 현장엔 흔하지만 CHECK 를 못 써서 동시성 보장을 서비스 잠금으로 옮겨야 함 |
| D4 | 재고 상태를 키에 둘까 | **둔다**(AVAILABLE·QC·HOLD·BLOCKED) — MRB 부분처분·부분 합격처럼 한 LOT 안에서 수량이 상태별로 갈림. 상태 바꿈 = 이동 | LOT 상태(`lot_master.status`)만 — LOT 단위로만 막을 수 있고 부분 수량을 못 나눔 |
| D5 | 유효기한(FEFO) 출처 | **품목 유효기간 = 품목 클래스 속성 `SHELF_LIFE_DAYS`(attrs, 새 칸 없음)**, LOT 유효기한은 2부에서 입고 때 계산·GS1 (17) 값 우선 — 저장 위치(`lot_master` 칸 요청 vs WMS 곁 테이블 `wms_lot_ext`)는 2부 착수 때 P2 와 맞춤 | `mst_item.shelf_life_days` 칸 추가 — P1 화면·엑셀·QA 를 고쳐야 해 병행 중 충돌 |
| D6 | 프린터 연결 (SaaS 에서는 서버가 고객 공장 LAN 프린터에 못 닿음) | **1부 = 서버 직접 TCP(설치형·데모) + 주소 검사(사설 대역 10/8·172.16/12·192.168/16 만, 루프백·링크로컬·메타데이터 거부, 포트 정책 허용 목록)**. SaaS 용 **인쇄 에이전트(고객 PC 가 기기 키로 작업을 당겨감, `/ext/v1/print-jobs`)는 2부** — 같은 인쇄 작업 행·아웃박스 토픽 위에 송신기만 다름 | (B) 처음부터 에이전트만 — 설치형에도 에이전트가 필요해짐 / (C) 브라우저 → 로컬 에이전트(Zebra Browser Print) — CSP `connect-src` localhost 완화(27 U11) |
| D7 | 바코드 형식 | **내부 = `유형:공장:코드` 접두어(QR 또는 Code128)**, 들어오는 협력사 라벨 = GS1-128 AI 파서. 접두어 없는 순수 코드는 정책 `WMS_SCAN_BARE_AS`(기본 LOC) | GS1 만 — GS1 회원 코드(GTIN)가 없는 중소 공장이 대부분[추정] / 고객 OEM 라벨 형식별 파서 — 고객마다 코드(2부·P6 에서 어댑터로) |
| D8 | 재고 원장 보존 | **`QUALITY_RECORD`(부품 EOL + 1년)** — LOT 추적 근거라 품질기록과 같게. 세법 장부 5년보다 대개 길다 | 새 클래스 `INVENTORY_LEDGER`(10년 고정) — 클래스가 늘고 EOL 연동이 빠짐 |
| D9 | 실사 중 재고 이동 | **막지 않는다** — 줄마다 "센 시각 기준 장부"(원장 합)로 차이를 계산해 이동이 섞여도 이중 조정 없음(원장이 있어 가능한 방식). 블라인드 기본 Y | 실사 범위 위치를 잠금(이동 금지) — 단순하지만 실사 동안 라인 공급이 멈춤 |
| D10 | 기간 마감 단위 | **공장 × 월, 마감 뒤 소급 = 차단(BLOCK)**, 재개는 APPROVE + 사유 + 감사. 결재 연동은 하지 않음 | 정책으로 "결재 후 허용" — 01 3.1 의 선택지지만 1부엔 결재 문서 유형이 하나 더 늘어남(2부 이후 필요하면) |
| D11 | 메뉴·화면 수 | **새 상위 메뉴 "창고"** + 화면 8개(5장). 라벨 템플릿·프린터는 한 화면 탭 2개 | 라벨·인쇄는 "공통/시스템" 메뉴 — 라벨은 품질·생산도 쓰지만 첫 사용자가 창고 |

## 9. 하지 않을 것 (1부)

- LOT 관리 품목의 원장 입출고, 생산·구매 입고, 투입, 출하, 피킹 지시, 파렛트·SSCC — **2부**(P2 LOT·입고·수주 필요)
- PDA 화면·오프라인 대기열 — **2부**(P2 5a 재사용, 따로 만들지 않음)
- 로케이션 용량(수량·부피·무게)·자동 적치 추천(put-away)·웨이브 피킹·AMR/컨베이어 연동 — 소규모 공장 범위 밖[추정], 필요하면 P6
- 라벨 시각 편집기(드래그 디자이너), 외부 미리보기 서비스(라벨 내용을 회사 밖으로 보냄) — ZPL 글자 + 견본 렌더 + 가짜 프린터까지
- 재고 금액·원가(이동평균·FIFO 원가) — ERP 몫(07)
- 시리얼(SN) 단위 재고 `lot_serial` — 02 결정대로 2차(반도체), 원장 키에는 자리만 고려
- 결과 캐시·스냅샷 테이블(월말 재고 스냅샷 등) — 원장 합 + 투영으로, 성능 문제가 실측되면 그때

## 10. 검토 결과 (원래 세션이 작성)

2026-10-20 사용자: "D1~D11 추천대로 채택하고 W1 로케이션 속성·스캔 키부터 구현".

| # | 결정 | 반영 위치 |
|---|---|---|
| D1 | 1부에서 재고 원장을 만든다 — LOT 비관리 품목만, `lot_id` 칸은 두고 FK·필수는 2부 | 2장 W3, 4장 W3 |
| D2 | P2 입고는 동기 호출 + 기존 `prd_receipt` 한 번 이관(원래 발생 시각) | 6장, 2부 |
| D3 | 마이너스 재고 허용 안 함(`CHECK (qty >= 0)`, 정책 없음) | 3장 동시성, W3 |
| D4 | 재고 상태(AVAILABLE·QC·HOLD·BLOCKED)를 현재고 키에, 상태 바꿈 = 이동 | 3장, 4장 W3 |
| D5 | 품목 유효기간 = 클래스 속성 `SHELF_LIFE_DAYS`, LOT 유효기한 저장 위치는 2부에서 P2 와 | 4장 W4 |
| D6 | 1부 = 서버 직접 TCP + 사설 대역 검사, SaaS 인쇄 에이전트는 2부 | 4장 W2 |
| D7 | 내부 바코드 `유형:공장:코드`, 협력사 라벨 GS1-128 파서, 순수 코드는 정책 `WMS_SCAN_BARE_AS`(기본 LOC) | 4장 W1 |
| D8 | 재고 원장 보존 = `QUALITY_RECORD` | 4장 W3 |
| D9 | 실사 중 이동 막지 않음(센 시각 기준 장부), 블라인드 기본 Y | 4장 W5 |
| D10 | 기간 마감 = 공장 × 월, 마감 뒤 소급 차단, 재개 APPROVE + 사유 | 4장 W5 |
| D11 | 새 상위 메뉴 "창고" + 화면 8개 | 5장 |

## 11. 2부 경계 합의안 — 생산 입고 → 재고 원장 (D2, MES 세션 검토용 · 2026-10-21 초안)

> 상태: **합의(2026-10-21, MES 세션 답 반영 — 11.9)**. 코드 없음 — 이 절대로 2부 B1 을 구현하고, B1 이 main 에 병합된 뒤 MES 가 호출 두 줄을 넣는다. 이 절의 V 파일은 모두 WMS 대역 임시 번호, **MES 표(`prd_*`·`lot_*`)는 바꾸지 않는다**.

### 11.1 결론 먼저

- **이관과 이후 연동을 같은 함수 하나로**: WMS 가 `ProductionReceiptPosting.post(receiptId)` 를 공개한다. `prd_receipt` 한 행을 읽어 재고 원장 한 행(`RCV_PRD`, 정정이면 그 원장의 역분개)을 넣고, 멱등 키 = `PRD_RECEIPT:{receiptId}`. **기존 행 이관(backfill)도, 이후 MES 의 동기 호출도 이 함수만 부른다** — 이관이 덜 끝난 회사에서 옛 입고를 정정해도 먼저 그 입고를 넣고 되돌리므로 순서가 꼬이지 않는다.
- **MES 쪽 변경 = 호출 두 줄**: `ReceiptService.receiveAs` 와 `correct` 에서 `prd_receipt` INSERT 직후(ERP 아웃박스 발행 전) 같은 트랜잭션으로 `post(id)`. MES 표·트리거·화면은 그대로. 의존 방향은 production → wms 하나.
- **이관 = SQL 마이그레이션이 아니라 회사별 Java 작업**(화면 버튼 + 미리보기): RLS·원장 트리거·마감 검사를 그대로 지나고, 행마다 실패 이유를 남기며, 몇 번을 돌려도 같다(멱등). SaaS 에서 회사마다 준비된 때 실행한다.
- **잠금 순서는 `lot_master` → 기간 잠금(공유) → `inv_stock`** 하나로 고정. 지금 MES 트리거가 이미 이 순서다(LOT `FOR UPDATE` 는 `prd_receipt` 트리거, 그 뒤 WMS INSERT).
- **마감 기간**: 이관은 원래 발생 시각 그대로 넣고, 마감된 달에 걸리면 **그 행만 실패로 보고**(마감을 몰래 우회하지 않음). 정정(역분개)은 **정정한 시각**으로 넣어 원래 달이 마감됐어도 이번 달에 반영된다(prd_receipt 정정 행의 시각과 같음).

### 11.2 원장 쪽 변경 (WMS, 2부 B1 임시 V93xx)

| 대상 | 변경 | 이유 |
|---|---|---|
| `inv_trx.trx_type` CHECK | `RCV_PRD` 추가(들어오는 유형, +). 이후 2부 유형(`ISSUE_WO`·`RCV_MAT`·`SHIP` …)은 각 단계에서 | V048 CHECK 는 1부 8종 |
| `inv_trx.lot_id` | FK → `lot_master` + 같은 회사 트리거. **LOT 관리 품목(`lot_ctrl_yn='Y'`)은 lot_id 필수, 비관리 품목은 lot_id 비움**(재고 키를 품목 × 위치로 유지 — MES 가 비관리 품목에도 LOT 을 만들더라도 원장에는 `src_id` 로만 연결) | 1부 `ck_inv_trx_lot_item` 을 이 규칙으로 바꾼다(D1 의 "2부에서") |
| 역분개 시각 | `InventoryService.reverse(trxId, reason, occurredAt)` 오버로드 — 정정 경로는 지금 시각, 기존 화면 역분개는 원래 시각 그대로 | 11.5 |
| 출처 | `src_type = 'PRD_RECEIPT'`, `src_id = receipt_id`, `entry_method` = 입고 행 그대로(KEY/SCAN, 정정 = CORRECT, 이관 = IMPORT), `recorded_by` = 입고 행의 입력자, `performed_by` = 입고 행의 작업자 | 원장 규약(01 3.1) — 누가 언제가 그대로 이어진다 |

### 11.3 `ProductionReceiptPosting.post(receiptId)` 규칙

1. `@Transactional(MANDATORY)` — MES 입고 트랜잭션 안에서만. WMS 는 `prd_receipt` 를 **읽기만** 한다(RLS 그대로).
2. 멱등 키 `PRD_RECEIPT:{receiptId}`(+ `req_seq` 1)가 이미 있으면 아무것도 안 하고 그 원장 id 를 돌려준다.
3. 입고 행(`reversal_of_id` 없음): `RCV_PRD`, 위치 = 입고 위치, 수량 = 입고 수량. **`unit_cd` ≠ 지금 품목 기본 단위면 칸 오류(`UNIT`)로 거부 — 환산하지 않는다**(Q1, 11.10 D-B1), **재고 상태 = 위치의 기본 상태(`wms_loc_attr.default_status`, QC 구역이면 QC) 없으면 AVAILABLE**. 출하검사 게이트(P3 2부)가 붙으면 그때 QC 로.
4. 정정 행(`reversal_of_id` 있음): 원래 입고의 원장 행을 찾아(없으면 먼저 2·3 으로 넣고) 역분개, 발생 시각 = 정정 행의 `occurred_at`, 사유 = 정정 사유. 그 사이 재고를 옮기거나 써서 마이너스가 되면 **409 `INSUFFICIENT_STOCK` 로 MES 정정 전체가 롤백**(재고를 먼저 되돌려 놓아야 한다 — MES 화면 문구 추가 필요).
5. 오류는 WMS 오류 코드 그대로 던진다(`PERIOD_CLOSED`·`INSUFFICIENT_STOCK`·칸 오류). MES 는 잡지 않는다 — 입고·재고가 같이 실패해야 둘이 갈라지지 않는다(D2 를 동기 호출로 고른 이유).

### 11.4 이관(backfill)

| 항목 | 방식 |
|---|---|
| 실행 | 현재고 화면 FN2 "생산 입고 이관" → **미리보기**(전체·이미 들어감·남음·마지막 실행) 확인 → 실행 |
| 대상 | 그 회사 `prd_receipt` 전부, `receipt_id` 순(정정은 원래 입고 뒤에 오므로 순서대로면 된다) |
| 트랜잭션 | **입고마다 따로 트랜잭션**(B1 구현 — 실패 행이 묶음 전체를 깨지 않게; PG 는 오류 하나로 트랜잭션이 깨진다). 실패 행은 건너뛰고 결과 표(`inv_backfill_run` — 입고 id·오류 코드, 최대 200건)에 남긴다. 다시 돌리면 실패 행만 다시 시도(멱등 키). 회사 단위 작업 잠금 대신 멱등 키로 동시 실행을 막는다 |
| 원장 트리거와 충돌 | 이관은 "새 사실" 이 아니라 과거 사실의 옮겨 적기 → `entry_method = 'IMPORT'` + `src_type = 'PRD_RECEIPT'` 인 행은 **사용 중지 품목·위치·`stock_yn='N'` 검사를 건너뛴다**(그때는 사용 중이었다). 회사·공장·단위·마감 검사는 그대로 |
| 마감된 달 | 실패로 남긴다(`PERIOD_CLOSED`). 관리자가 그 달을 재개(사유 "생산 입고 이관")하고 다시 돌리거나, 그대로 두고 기초 재고 실사로 맞춘다 — **마감을 우회하는 경로는 만들지 않는다** |
| 끝난 뒤 | 대사(`reconcile`)에 "prd_receipt 순입고 합 = RCV_PRD 원장 합(품목·LOT 별)" 검사를 하나 더 — 이후 MES 호출이 빠진 경로가 생기면 알림. LOT → LOT 변환(REWORK·REPAIR·SPLIT·MERGE·RELABEL)은 입고가 아니므로 이 대사에서 뺀다(Q4) |

### 11.5 마감 기간과 시각

| 경우 | 원장 발생 시각 | 마감된 달이면 |
|---|---|---|
| 새 입고(MES 호출) | 입고 행 `occurred_at`(= 지금) | 이번 달은 마감할 수 없다(W5) → 생기지 않음 |
| 정정 | **정정 행 `occurred_at`(= 지금)** | 원래 달이 마감돼도 이번 달에 반영 — 회계 관행(전기 정정은 당기) |
| 이관 | 원래 입고 시각 | 그 행 실패 보고(11.4) |
| 화면 역분개(수불 원장 화면) | 원래 시각(지금과 같음) | 409 — 이번 달로 바로잡으려면 조정 |

### 11.6 잠금 순서와 동시성

```text
MES 입고 트랜잭션: prd_wo 읽기 → lot_master FOR UPDATE (prd_receipt 트리거, 순입고 ≤ 만든 양)
                 → [WMS] 기간 advisory 잠금(공유, inv_trx 트리거) → inv_stock 행 잠금(투영 트리거)
                 → sys_outbox(ERP_RECEIPT)
기간 마감        : 기간 advisory 잠금(배타)만 — lot·재고 행을 잡지 않으므로 교착 없음
2부 다른 경로    : LOT 을 잠가야 하면(투입 차감 ISSUE_WO 의 계보 CONSUME 등) 반드시 lot_master 먼저, inv_stock 은 원장 INSERT 로만
```
**고정 조건(MES 합의)**: ① 2부 모든 WMS 경로는 `lot_master` 를 **먼저** 잠근다. ② WMS·PLM 어느 경로도 `inv_stock`·기간 잠금을 쥔 채 `lot_master`·ECO 를 잠그지 않는다. ③ **한 트랜잭션에서 재고 칸 여러 개를 건드리면 (item_id, node_id, lot_id(없음 먼저), stock_status) 순으로**(2026-10-22 상위 결정, QA QB-1) — 원장 트리거가 행마다 칸을 잠그므로 INSERT 순서 = 잠금 순서. WMS: `InventoryService.postAs`·`reverseAt` 가 이 순서로 넣고(행 id·req_seq 는 받은 순서 그대로 — 실사 확정 줄 순서·이동 짝·LOT 변환·투입 차감 모두), `LotStockMapper.lockCells` 도 같은 ORDER BY. MES: POP 실적·정정의 투입 처리 순서를 같은 규칙으로(투입마다 따로 원장이 들어가므로). 순서가 다르면 실사 확정(칸 A→B)과 이동(B→A)처럼 서로 거꾸로 기다린다. 투입 차감(ISSUE_WO, P2 5b 에 붙임)의 순서는 `prd_wo_proc` → `lot_master` → ECO → 기간·`inv_stock`.

같은 LOT 동시 입고 두 건은 지금처럼 `lot_master` 행에서 줄 서고, 뒤 건은 앞 건의 재고 반영이 커밋된 뒤 진행한다. 시험: 같은 LOT 동시 입고(MES 기존) + **입고와 같은 위치 동시 출고**, **입고 정정과 동시 이동**(정정이 마이너스면 409, 둘 다 반영되면 합 0 이상) 을 B1 에 넣는다.

### 11.7 MES 에 부탁하는 것 (코드 두 군데)

| 위치 | 변경 |
|---|---|
| `ReceiptService.receiveAs` | `mapper.insert(…)` 다음, `erp.receipt(id)` 앞에 `receiptPosting.post(id)` |
| `ReceiptService.correct` | 같은 자리에 `receiptPosting.post(id)`(정정 행 id) |
| 생산 입고 화면 문구 | 정정이 409 `INSUFFICIENT_STOCK` 일 때 "이미 옮기거나 쓴 재고입니다 — 재고를 되돌린 뒤 정정" (문구 키는 MES 블록) |
| 데모 시더 `receiveAs` | 그대로(같은 호출을 지난다 — `TenantRunner` 행위자로 기록) |

WMS 가 하는 것: 11.2 마이그레이션, `ProductionReceiptPosting`, 이관 작업·화면, 시험(정상 + 이관 멱등 + 마감 달 실패 + 정정 시각 + A사로 B사 + 동시성), 대사 검사 추가.

### 11.8 MES 세션에 묻는 것

| # | 질문 | WMS 추천 |
|---|---|---|
| Q1 | `prd_receipt.unit_cd`(= 지시 단위)가 늘 품목 기본 단위인가? 아니면 환산이 필요한가 | 기본 단위라고 보고 B1 시험으로 고정. 다르면 WMS 가 환산(`in_qty`·`in_unit_cd` 참고 칸) |
| Q2 | LOT 비관리 품목(`lot_ctrl_yn='N'`)도 MES 가 산출 LOT 을 만드는가 | 만든다면 원장에는 lot_id 를 비우고 `src_id` 로만 연결(11.2) |
| Q3 | 정정 시각을 "정정한 때" 로 넣는 데 동의하는가(11.5) | 동의 — prd_receipt 정정 행과 같은 시각 |
| Q4 | 재작업·수리 LOT(`origin` REWORK·REPAIR, 7b)의 입고 경로 | 같은 `prd_receipt` 를 지나면 같은 함수로 충분. 다른 표로 들어온다면 그 표도 같은 모양(`post(출처, id)`)으로 |
| Q5 | 입고 화면에서 위치 목록이 `stock_yn='N'`(재고 안 둠) 노드를 보여 주는가 | WMS 트리거가 새 입고를 거부하므로, MES 위치 목록도 `wms_loc_attr.stock_yn` 을 보거나 칸 오류(`STOCK_NOT_ALLOWED`)를 그대로 보여 주기 |

### 11.9 MES 답 (2026-10-21, 상위 세션 경유 — 합의)

| # | 답 | 반영 |
|---|---|---|
| 방향 | 동의. MES 는 **B1 이 main 에 병합된 뒤** `receiveAs`·`correct` 의 `mapper.insert` 직후·`erp.receipt` 앞에 `receiptPosting.post(id)`. 오류는 잡지 않아 함께 롤백, 409 문구는 MES 가 넣는다 | 11.7 그대로 |
| Q1 | 입고 단위 = 지시 단위 = 지시 생성 때 품목 기본 단위. 단 BOM·환산이 없는 품목은 지시·입고가 있어도 기본 단위를 바꿀 수 있는 빈틈(V018 사용 중 검사는 BOM·환산만) | `post()` 가 단위 불일치를 칸 오류로 거부(11.3-3). P1 보강은 11.10 D-B1 |
| Q2 | 비관리 품목도 LOT 을 만든다(입고 lot_id 필수) | 원장 lot_id 비움 + `src_id` 연결(11.2) |
| Q3 | 정정 시각 = 정정한 때, 동의 | 11.5 |
| Q4 | 7a 처리 LOT(PRODUCED·wo_id)은 같은 입고 경로. 7b LOT 단위 재작업(REWORK/REPAIR)·SPLIT·MERGE·RELABEL 은 입고가 아니라 LOT → LOT 변환 | B1 범위 밖, 대사에서 제외(11.4). 재고 반영 경로는 11.10 D-B2 |
| Q5 | B1 은 WMS 트리거 칸 오류 `STOCK_NOT_ALLOWED` 를 MES 가 `nodeId` 칸 오류로 보여 준다. 위치 목록 거르기는 나중에 WMS 공개 메서드(입고 가능 위치)로 | B1 은 오류만, 공개 메서드는 후속 — **2026-10-26 계약 `ReceiptLocQuery`(13장, docs/44 S2), MES 가 붙임** |
| 잠금 | 충돌 없음(입고: lot_master → 기간 공유 → inv_stock, prd_wo_proc 는 잠그지 않음). 조건 두 가지 고정 | 11.6 고정 조건 |

### 11.10 결정 대기 (사용자)

| # | 질문 | WMS 추천 | 다른 안 |
|---|---|---|---|
| D-B1 ✅ 채택(2026-10-21) — V091 | 지시·입고에 쓰인 품목의 **기본 단위 변경**을 막을까(지금 V018 은 BOM·환산에 쓰였을 때만 막음) | **막는다** — 사용 중 검사에 지시(`prd_wo`)·재고 원장(`inv_trx`)을 더한다(P1 품목 트리거 = 남의 표 변경이라 P1/MES 세션과 함께). 그 전까지는 WMS `post()` 가 단위 불일치를 거부해 원장이 섞이지 않게 한다 | 그대로 두고 WMS 가 환산 — 환산 기준(언제의 단위)이 모호해 원장 합이 틀어질 수 있다 |
| D-B2 ✅ 방향 채택(2026-10-21) — 설계는 B2 뒤(12장 위치) | LOT → LOT 변환(재작업·분할·병합·재라벨)을 재고에 어떻게 반영할까 | **"계보 변환 반영" 경로**: 출처 = `lot_genealogy`, 같은 위치·같은 상태에서 부모 LOT −·자식 LOT +(이동 짝과 같은 합 0 규칙), 같은 트랜잭션 동기 호출 — LOT 관리 품목 재고가 생긴 뒤(2부 B2 이후) | 변환 뒤 실사로 맞춤 — 원장으로 추적이 끊긴다 |

### 11.11 B1 구현 메모 (2026-10-21)

- `ProductionReceiptPosting.post(receiptId)`(MANDATORY) — MES 가 B1 병합 뒤 부를 공개 메서드. 내부 `post(id, importMode)` 는 이관·미이관 정정용.
- 기록자: 실시간 = 입고 행의 입력자, 이관 = 이관을 돌린 사람(원래 입력자는 출처 행에). 정정 역분개 사유 = `[PRD_RECEIPT:{원래 id}] 정정 사유`(1자 사유도 원장 사유 규칙 2자를 넘게).
- `InventoryService.postAs`·`reverseAt`(같은 패키지) — 기록자·발생 시각·멱등 키를 출처에서 받는다. `TrxReq` 에 `lotId` 칸(13칸 생성자는 그대로).
- 대사: 이관을 한 번 끝낸 회사(`inv_backfill_run` 행 있음)만 "원장에 없는 생산 입고" 를 세어 `missing_receipts` 에 남기고, 0 보다 크면 알림.

## 12. 2부 단계 (초안 2026-10-21 — 상위 세션 확인용)

0장 2부 범위를 **참조되는 것 먼저 · 다른 모듈과 경계가 적은 것 먼저**로 나눈다. 각 단계는 1부와 같이 화면·API·시험(정상 + A~K + A사로 B사 + 동시성)까지 끝내고 7장에 ✅, 임시 번호는 V9303~.

| 단계 | 범위 | 기대는 것 | 다른 모듈과 경계 | 막힌 것 |
|---|---|---|---|---|
| ✅ B1 | 생산 입고 → 재고 원장, 이관(11장) | P2 7a `prd_receipt` | MES 호출 두 줄(MES 세션) | — |
| ✅ B2 | **LOT 관리 품목 재고 — 사무 화면**: 현재고·수불 원장·이동/조정·실사·차감 미리보기에 LOT 열·LOT 조건, 이동·조정·실사가 LOT 단위로(LOT 스캔 `LOT:` 해석 포함), LOT 보류(`lot_master.hold_yn`)면 출고·이동 거부 | B1 원장 LOT 규칙, P2 `lot_master` 읽기 | 없음(읽기만) | — |
| B3 | **품질 상태 연동**: 수입·출하검사 판정 → 재고 상태 STATUS_OUT/IN(같은 트랜잭션 동기 호출, 00 3장 품질 게이트), MRB 처분(부분 합격) — **경계 합의안 12.15(2026-10-24, QMS 검토 대기)** | B2 | QMS 2부(판정 서비스가 WMS 를 부름) — 계약 `QualityStockLink` | QMS 합의 |
| ✅ B4a | **자재 입고**(`RCV_MAT`: 공급사 LOT·GS1 (01)(10)(17)(30), LOT 은 P2 `LotService.receive` 를 부름) — 12.3 | B2 | P2 자재 LOT 등록(호출만) | — |
| ✅ B4b | 구매 입고(`RCV_PO`, SRM 2부 게이트 `PoReceiptGate` — 12.2·12.6) | B4a | SRM docs/31 2부(호출만) | — |
| B5 | **투입 차감 `ISSUE_WO`**(P2 5b 투입 CONSUME 과 같은 트랜잭션, 11.6 잠금 순서 `prd_wo_proc → lot_master → ECO → 기간·inv_stock`), 차감 전략(W4) 호출, 부족 투입 = 409(D-B4) — 경계 합의안 12.4 | B2·B4a | MES 5b·4단계가 계약 `LotStockLink` 를 부름(12.4.5) | MES 합의 |
| B6 | **출하**: 피킹 지시(차감 전략)·파렛트·출하 라벨(W2 `LabelPrintService`)·`SHIP`·출하검사 게이트 — 경계 합의안 12.5(B6a/B6b). **B6a ✅ 12.7, B6b-1 ✅ 12.10** | B2(출하검사 게이트는 B3 — D-B15) | 영업 게이트 `OrderShipGate`·PLM `shipChecks` | B6b(팔레트·SSCC) |
| B7 | **PDA 화면**(`/pop` 계열 재사용 — 입고·이동·피킹·실사, 오프라인 대기열) — 경계 합의 12.12(`/pop/wh`, B7-1 이동·실사 → B7-2 입고·피킹) | B2~B6 | P2 5a `hb.pop` + MES 단말 유형 PR | — |
| D-B1 ✅ | 작업지시·재고 원장에 쓰인 품목의 기본 단위 변경 금지(V018 트리거 함수 + 품목 서비스·엑셀 올리기 같은 조건, 원장 검사가 품목 행 FOR SHARE) | — | P1 품목(남의 코드 3곳) | — |
| B2+ (D-B2) | LOT → LOT 변환 반영(계보 출처, 같은 칸 부모 `CONV_OUT`·자식 `CONV_IN`) — 설계 12.2 | B2 | MES 7b·P2 4단계 | 12.2.5 결정 |

다음은 **B2**(경계 없음). B3·B5 는 시작 전에 11장처럼 경계 합의안을 문서로 먼저 올린다.

### 12.1 B2 상세 (2026-10-21)

| 대상 | 규칙 |
|---|---|
| LOT 입력 | 화면은 LOT **번호**(`lotNo`)로 받는다. LOT 관리 품목 = 필수(`LOT_REQUIRED`)·그 품목의 LOT(`LOT_MISMATCH`)·있는 LOT(`NO_SUCH_CODE`), 비관리 품목에 LOT = `LOT_NOT_CONTROLLED`. 이동은 출발·도착이 같은 LOT |
| 게이트 LOT 인자 | **재고에서 실제로 뽑은 LOT** — LOT 관리 품목 = 지시 LOT id, 비관리 품목 = null(SRM 리뷰 2026-10-23). 게이트가 품목 LOT 관리 여부로 판정(관리 품목 다르면 409 `SRM_RTN_LOT_MISMATCH`, 비관리 품목에 값 = 부르는 쪽 버그) |
| 보류 LOT | `lot_master.hold_yn = 'Y'` 면 **조정 출고·다른 위치로 이동**을 거부(`LOT_HOLD`). 같은 위치 상태 바꿈(HOLD 로 격리)·실사 조정·역분개·입고는 된다. 원장 트리거가 LOT 행을 `FOR SHARE` 로 읽어 보류 지정과 줄 선다(잠금 순서 lot_master → 기간 → inv_stock, 11.6) |
| 스캔 | `LOT:{번호}` → 품목·LOT 을 함께 채운다(보류면 메시지에 표시) |
| 조회 | 현재고·수불 원장에 LOT 번호 열, 현재고 조건 LOT 번호(앞 일치, 대소문자 그대로), 최근 수불은 그 LOT 칸만 |
| 실사 | 시작 때 LOT 칸까지 줄, 장부 = 센 시각까지 그 LOT 원장 합, 줄 추가는 LOT 규칙 같음, 확정 원장에 LOT |
| 차감 미리보기 | LOT 후보의 선입일 = LOT 이 만들어진 시각(`lot_master.occurred_at`), LOT 없는 칸은 원장 계산(4.4) |

### 12.2 재고 유입·유출 경로 — 자재 입고·구매 입고·투입 차감·LOT 변환 (설계 초안 2026-10-21, 상위 세션·MES·SRM 검토용)

**왜 지금**: APS 5단계(자재 가용) 조사에서 원자재 재고가 **기초·조정·실사로만** 들어오고, POP 투입(계보 CONSUME)이 재고를 줄이지 않는다는 점이 드러났다. 그래서 `StockQuery.available`(13장)은 "마지막 실사 뒤 쓴 양"만큼 늘 많게 나온다. 재고 정확도의 핵심이므로 B4·B5 를 D-B2 와 묶어 설계한다. 코드는 결정 표(12.2.5)를 받은 뒤 단계별로 짓는다.

#### 12.2.1 지금 상태 (조사 결과)

| 대상 | 있는 것 | 없는 것 |
|---|---|---|
| 자재 LOT | `LotService.receive` — 공급사 LOT 번호·수량으로 RECEIVED LOT 생성(멱등 `PRD_LOT_RECEIVE`, 공급사×품목×공급사 LOT 유일) | 위치, 원장 행 |
| 발주 | `srm_po_line.qty`·`cancel_qty`·`unit_cd`(**품목 기본 단위와 다를 수 있음**), 미결 = `qty − cancel_qty` 를 SQL 여러 곳(응답 화면·V062 트리거)이 직접 계산 | 입고 수량, `PoOpenQty` 계약(docs/31 약속만), 머리 상태 PARTIAL_RECEIVED·RECEIVED |
| 투입 | `PopWorkService.result` → `lockForConsume`(LOT id 순) → ECO 표시 → `LotService.consume`(계보 CONSUME, 수량 = LOT 단위) | 위치(POP 투입 줄에 없음, 단말 노드만 있음), BACKFLUSH(`prd_wo_bom.issue_method` 는 있으나 아무도 안 읽음) |
| LOT 변환 | `LotService.split·merge·relabel·rework` — 부모 `FOR UPDATE` 뒤 계보 행(부모에서 나간 양) | 재고 반영(원장과 계보가 연결 안 됨), 자식 위치·상태 |
| 원장 유형 | OPENING·ADJ_IN/OUT·MOVE_OUT/IN·STATUS_OUT/IN·COUNT_ADJ·RCV_PRD | RCV_MAT·RCV_PO·ISSUE_WO·변환 |

#### 12.2.2 공통 규칙 (세 경로 모두)

- **같은 트랜잭션 동기 호출**(D2·11장과 같다): 상대 모듈 서비스가 자기 행을 쓴 직후 WMS 계약을 부른다. 원장 실패 = 업무 전체 실패. 아웃박스로 미루지 않는다(재고가 잠깐 비고 재처리 경로가 하나 늘어남).
- **잠금 순서**(11.6 고정 조건 그대로): 상대 모듈 머리 행(`prd_wo_proc`·`srm_po_line`) → `lot_master` → ECO → 기간 advisory(공유) → `inv_stock`. WMS 계약은 `inv_stock` 을 원장 INSERT 로만 잡고, 그 뒤 LOT·ECO·발주 행을 잡지 않는다.
- **연결 스위치**: 공장 정책 `WMS_STOCK_LINK`(N 기본). N 이면 계약이 아무것도 하지 않는다(지금 동작). 기초 재고 실사(W5)로 원자재를 맞춘 뒤 Y 로 켠다. 켜기 전 기록은 **이관하지 않는다**(B1 생산 입고와 다름 — 투입·변환은 위치가 없어 과거분을 원장으로 재구성할 수 없다). 결정 D-B5.
- **부족**: D3(마이너스 재고 없음) 그대로. 켜진 공장에서 재고가 모자라면 409 `INSUFFICIENT_STOCK`, 업무 전체 롤백. 결정 D-B4.
- **멱등**: 출처 키 `{출처}:{id}` + `req_seq`(위치가 여러 칸이면 칸마다 1, 2, …). 출처 = `src_type`·`src_id`.
- **마감 달**: 11.4 와 같다 — 마감된 달의 발생 시각이면 `PERIOD_CLOSED`, 우회 경로 없음(POP 늦은 입력도 같다).
- **단위**: 원장은 품목 기본 단위만(D-B1). LOT 단위 ≠ 품목 기본 단위면 거부. 발주 단위 환산은 D-B3.
- **LOT 비관리 품목**: LOT 이 있어도 원장 `lot_id` = null(B1 규칙) → 품목 단위로 차감·입고.

#### 12.2.3 경로별 설계

| 경로 | 부르는 곳(상대 모듈) | 원장 | 위치·상태 | 정정 |
|---|---|---|---|---|
| **RCV_MAT 자재 입고**(B4a) | WMS 입고 화면이 `LotService.receive` 를 부르고(LOT 생성은 P2 것), 같은 트랜잭션에서 원장 | `RCV_MAT` + | 화면이 받는다(입고 위치 스캔). 상태 = 위치 기본 상태(QC 구역 → QC, B3 전까지는 사람이 고름) | 원장 역분개 + LOT 은 P2 정정 규칙(입고 LOT 취소는 MES 결정 — 열린 질문) |
| **RCV_PO 구매 입고**(B4b) | 위와 같고 발주 행을 고르면 SRM 계약 `PoReceiptGate.receive(poLineId, qtyInPoUnit, trxRef)` 를 **원장보다 먼저** 호출(발주 행 잠금 → 미결 확인·허용 초과율 → 입고 수량 투영·머리 상태) | `RCV_PO` +, `inv_trx.po_line_id`(새 열, 빈 값 허용) | RCV_MAT 와 같음 | 원장 역분개 → 같은 게이트에 음수(입고 수량 되돌림·상태 되돌림) |
| **ISSUE_WO 투입 차감**(B5) | `PopWorkService.result` 가 `lots.consume` 뒤 CONSUME 계보 행마다 WMS `StockIssue.issue(genId)` | `ISSUE_WO` −(출처 `LOT_GEN`·계보 id) | 그 LOT 의 AVAILABLE 재고에서 **단말 작업장 아래 LINESIDE 위치 먼저 → 나머지는 pick_seq 순**(차감 전략 W4 의 지정 LOT·LOC_SEQ). 여러 칸이면 칸마다 한 행. 결정 D-B6 | `PopWorkService.correct` 의 CONSUME 역분개 → 그 계보 id 의 ISSUE_WO 행들 역분개(같은 트랜잭션) |
| **LOT 변환**(B2+, D-B2) | `LotService.split·merge·relabel·rework` 가 계보 행마다 WMS `LotConversion.apply(genId)` | `CONV_OUT` −(부모 LOT) / `CONV_IN` +(자식 LOT) 짝, `move_grp_id` 로 합 0 — 이동 짝과 같은 검사, 다른 점은 **위치·상태 같고 LOT 이 다름** | 부모 재고가 있는 칸(상태 그대로)에서 pick_seq 순으로 계보 수량만큼, 자식은 **같은 칸·같은 상태**로. 재작업·수리는 보류 부모에서도 된다(계보 규칙과 같음 — 원장 보류 검사는 ADJ_OUT·MOVE_OUT 만 막는다) | 분할·병합은 MES 가 반대 작업으로 되돌림 → 반대 변환이 자연히 생김. 계보 역분개(CONSUME 만)와 무관 |

- 차감 위치를 WMS 가 정하므로 **MES·POP 화면은 바뀌지 않는다**(투입 줄에 위치를 받지 않음). 현장이 LINESIDE 로 미리 이동(출고 지시 → 라인 보충)해 두는 운영이 정확도를 만든다 — 보충 지시는 B6 피킹과 같은 틀로 뒤에.
- BACKFLUSH(지시 BOM 소요로 자동 차감)는 이번 범위가 아니다. POP 이 스캔 투입만 하므로, 스캔 없는 자재는 실사로 맞춘다(결정 D-B7 에 따로).

#### 12.2.4 상대 모듈 경계 (합의할 것)

| 모듈 | 부탁하는 것 | WMS 가 주는 것 |
|---|---|---|
| **MES(P2 5b·4단계·7b)** | `PopWorkService.result`·`correct`, `LotService.split·merge·relabel·rework` 끝에 계약 호출 한 줄씩(ObjectProvider + NONE, PLM 계약과 같은 모양). 409 `INSUFFICIENT_STOCK`·`PERIOD_CLOSED` 를 POP 오류로 보여 주기 | `module.wms.link.StockIssue`·`LotConversion`(NONE 포함), 메시지 키 |
| **SRM(2부)** | `PoReceiptGate` 계약 — 발주 행 잠금·미결 확인(허용 초과율 정책)·`srm_po_line.recv_qty` 투영(새 열)·머리 상태 PARTIAL_RECEIVED/RECEIVED, 미결 계산을 `qty − cancel_qty − recv_qty` 로(응답 화면·V062 트리거 포함), ERP 원천 모드(`SRM_PO_SOURCE = ERP`)면 게이트 NONE | 원장 `po_line_id`·`RCV_PO`, 대사: `recv_qty` = 그 발주 행 RCV_PO 원장 합(WMS 대사 검사에 추가) |
| P2 자재 LOT | `LotService.receive` 를 WMS 화면이 부를 수 있게(지금 `@Transactional` 공개 메서드 — 그대로 씀). 입고 LOT 취소 규칙 | — |

잠금 순서 확인: 구매 입고 = `srm_po_line`(게이트) → `lot_master`(새 LOT INSERT, 경합 없음) → 기간 → `inv_stock`. 투입 = 11.6 그대로. 변환 = 부모 `lot_master FOR UPDATE`(LotService) → 기간 → `inv_stock`. 셋 다 고정 조건 ①②를 지킨다.

#### 12.2.5 결정 — **2026-10-21 사용자: 추천안 그대로 채택**(D-B3~D-B8)

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D-B3 | 구매 입고 수량은 누가 가지나 | **SRM 게이트 + 투영**: 원본 = WMS 원장(`po_line_id` 합), SRM 은 `recv_qty` 투영·상태를 게이트 안에서 갱신. 발주 단위 ≠ 기본 단위면 게이트가 품목 단위 환산(P1 `mst_unit_conv`)으로 기본 단위 수량을 돌려줌 | SRM 이 WMS 계약으로 원장 합을 매번 읽음 — 미결 확인이 발주 행 잠금과 엇갈림 / 발주 단위 = 기본 단위만 허용 |
| D-B4 | 연결이 켜진 공장에서 재고 부족 투입·변환 | **409 거부(D3)** — POP 이 "재고 부족: 이동·조정 먼저" 를 보여 줌 | 대기열(D3 원안 "대기 + 감독자 작업") — B5 다음 단계로 |
| D-B5 | 연결을 언제 켜나 | **공장 정책 `WMS_STOCK_LINK`(N 기본), 기초 실사 뒤 켬, 과거 투입·변환 이관 없음** | 배포 즉시 전 공장 — 원장이 실물과 안 맞는 상태에서 부족 거부가 생산 기록을 막음 |
| D-B6 | 투입 차감 위치 | **WMS 가 정함**: 단말 작업장 아래 LINESIDE → pick_seq | POP 투입 줄에 위치 스캔 추가(MES 화면·API 변경) / 단말 노드 한 곳 고정 |
| D-B7 | BACKFLUSH | **이번 범위 밖**(스캔 투입만, 나머지 실사) | 실적 수량 × BOM 소요로 자동 차감(POP 실적 경로 변경 + 부족 처리 필요) |
| D-B8 | 단계 순서 | **B4a RCV_MAT → B5 ISSUE_WO + B2+ 변환(MES 경계 한 번에) → B4b RCV_PO(SRM 2부 게이트 뒤)** | B4 를 한 번에 — SRM 2부 일정에 묶임 |

열린 질문(합의 때): 입고 LOT 취소(P2) 규칙, 변환 자식 LOT 을 다른 위치로 만들고 싶을 때(지금 안: 같은 칸 → 이동으로), ERP 원천 발주의 입고 송신(P6·아웃박스).

### 12.3 B4a 자재 입고 RCV_MAT 상세 (설계 2026-10-21, 구현 ✅ 2026-10-21 V107)

발주 없이 들어오는 자재(공급사 납품·무상 사급·샘플 등)를 위치와 함께 원장에 넣는다. 다른 모듈 화면·코드는 바꾸지 않는다 — P2 `LotService.receive`(공개 `@Transactional` 메서드)를 **부르기만** 한다. 구매 입고(B4b)는 같은 화면에 발주 행 칸을 더하는 방식으로 뒤에 붙인다.

#### 12.3.1 화면 `wms-rcv` (사무, 키보드 웨지 스캐너 — PDA 는 B7)

| 칸 | 규칙 |
|---|---|
| 공장·입고 위치 | 위치 스캔(`LOC:`)·선택. 재고를 둘 수 있는 위치(`stock_yn = Y`), SCRAP 용도(그 노드나 조상) 거부 `SCRAP_LOCATION` |
| 품목 | 품번 입력·`ITEM:` 스캔. GS1 (01) GTIN → 품목 속성 `GTIN`(14자리로 맞춰 비교, 딱 한 품목일 때만 — D-B10), 없거나 모호하면 품번을 직접. GS1 (30)(37) → 수량 칸 |
| 수량 | 품목 기본 단위·소수 자릿수(P1 단위). 0 이하 거부 |
| 공급사·공급사 LOT | 공급사 = 구매처(PURCHASE·BOTH, P2 규칙 그대로). GS1 (10) → 공급사 LOT 칸, (17) → 유통기한 칸(표시·검사만, D-B9) |
| 재고 상태 | 위치 기본 상태 제안(QC 구역 → QC, 그 밖 AVAILABLE). 사람이 바꿀 수 있음. B3 뒤에는 수입검사 대상 품목이면 QC 고정 |
| 발생 시각 | 비우면 지금, 늦은 입력만 과거(미래 거부, 마감 달 `PERIOD_CLOSED`) |
| 라벨 | "LOT 라벨 인쇄" 선택 + 프린터·매수 → W2 `LabelPrintService.request("LOT", …)`(아웃박스, 같은 트랜잭션 — 인쇄 실패가 입고를 되돌리지 않는다) |

흐름은 이동·조정 화면과 같다: 미리보기(만들 LOT·원장 행) → 확정 1회(누른 순간 만든 `clientReqId`). 확정 결과 = 원장 id·LOT 번호(라벨 대기열 id).

#### 12.3.2 서버 `MaterialReceiptService.receive` (한 트랜잭션)

```text
1. sys_inbox 선점 'WMS_RCV_MAT' + clientReqId   — 이미 있으면 그 clientReqId 원장 행을 읽어 replay(새 LOT 을 만들지 않는다).
                                                   동시에 두 번이면 뒤 요청이 unique 인덱스에서 기다렸다가 replay
2. 검사(칸 오류 한 번에): 위치·품목·수량·상태·시각, LOT 관리 품목이면 공급사 LOT 형식
3. LotService.receive(item, qty, supplier, supplierLot, occurredAt, remark) → lot_id — 모든 품목(공급사·공급사 LOT 추적).
   원장 lot_id 는 B1 규칙대로 LOT 관리 품목만, 비관리 품목은 출처(src_id)로만 연결
4. InventoryService.post([RCV_MAT +qty, 위치, 상태, lot_id, src_type 'RCV_MAT'], clientReqId)
5. (선택) LabelPrintService.request('LOT', …)
```

- 잠금: 3 은 새 LOT INSERT 라 경합 없음 → 4 의 기간 advisory(공유) → `inv_stock`. 11.6 고정 조건과 맞는다.
- 오류 매핑: P2 의 칸 오류(`supplierLotNo` `DUPLICATE`·`NOT_SUPPLIER_LOT` 등)는 그대로 화면 칸으로. 원장 오류는 기존 `CONSTRAINT_FIELD`.
- 권한: `@ProgAuth(wms-rcv, SAVE)`. P2 LOT 화면 권한은 따로 요구하지 않는다(LOT 생성은 입고의 일부).
- `sys_prog` `wms-rcv`(Flyway), 메뉴 시드(`DevCompanySeed.WMS_MENUS`), 메시지 ko/en.

#### 12.3.3 원장·스키마 (V107)

| 대상 | 변경 |
|---|---|
| `inv_trx` 유형 | CHECK 에 `RCV_MAT` 추가, 부호 + (원장 트리거 부호 규칙) |
| 출처 | `src_type = 'MAT_RECEIPT'`·`src_id` = 입고 LOT 필수(CHECK `ck_inv_trx_rcv_mat_src`) |
| 대사 | `inv_reconcile_run.rcv_mat_over` = 입고 원장 합(역분개 포함) > LOT `qty_created` 인 LOT 수(0 이 정상, 넘으면 알림) |
| 남의 표 | 없음 — `lot_master` 는 P2 서비스가 쓴다 |

#### 12.3.4 정정 (입고 취소)

- 원장: 수불 원장 화면의 역분개(W3) 그대로 — 그 칸에 재고가 남아 있어야 한다(`CHECK (qty >= 0)` → 이미 쓴 만큼은 409).
- LOT: 역분개만으로는 LOT 이 `qty_created` 그대로 남는다. **LOT 쪽 취소 규칙은 P2(MES) 결정**(열린 질문 — "입고 LOT 취소 = 보류 + 사유" 를 MES 에 제안). B4a 는 역분개 때 LOT 을 건드리지 않는다. MES 가 만드는 `lot_hold`(보류 근거 원장)를 입고 취소 출처로 쓰는 방향으로 MES 의견을 받는 중(2026-10-21 상위 세션).

#### 12.3.5 시험 계획

정상(LOT 품목·비관리 품목, QC 구역 기본 상태, 라벨 대기열) · B(없는 위치·SCRAP 위치·묶음 노드, 0·음수·자릿수 초과, 구매처 아닌 거래처, 공급사 LOT 중복, 미래 시각) · 마감 달 · **같은 clientReqId 두 번·동시 두 번 → LOT 1개·원장 1행** · 역분개(재고 남음/이미 출고) · **A사로 B사**(B사 위치·품목·공급사 id) · 권한(조회만 사용자) · 대사(RCV_MAT 합 > LOT 수량을 직접 SQL 로 만들면 검출).

#### 12.3.6 결정 (B4a 추가분) — **2026-10-21 사용자: 추천안 채택**

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D-B9 | GS1 (17) 유통기한을 어디에 두나 | **이번엔 저장하지 않음**(표시·과거 날짜 경고만). FEFO 는 지금처럼 LOT 생성 시각 + 품목 클래스 `SHELF_LIFE_DAYS`(W4). 공급사 유통기한이 필요해지면 MES 에 `lot_master.expiry_date` 를 부탁 | 지금 `lot_master` 에 열 추가(남의 표) |
| D-B10 | GS1 (01) GTIN → 품목 | **품목 속성 `GTIN`(클래스 속성 `attrs`) 조회, 없으면 품번 직접** — 스키마 변경 없음 | `mst_item.gtin` 열(P1 표 변경)·외부 코드 매핑(P1 `IF_CODE`) |

### 12.4 B5 투입 차감 ISSUE_WO + B2+ LOT 변환 — MES 경계 합의안 (2026-10-22, MES 검토 반영 — 계약·부탁 ③·D-B11~13 동의)

12.2 의 결정(D-B4 부족 409 · D-B5 공장 스위치 · D-B6 위치는 WMS · D-B7 BACKFLUSH 밖)을 MES 코드 위에 놓은 상세. 코드는 MES 합의 뒤.

#### 12.4.1 지금 MES 코드 (조사 2026-10-22)

| 대상 | 사실 |
|---|---|
| POP 실적 `PopWorkService.result` | 요청 id 로 replay 면 계보를 다시 만들지 않음 → `prd_wo_proc FOR UPDATE` → 검사 → `lots.lockForConsume`(LOT id 순) → 산출 LOT → ECO 표시 → 투입마다 `lots.consume(…)`(계보 CONSUME, **gen_id 를 돌려주지만 버림**) → `prd_result`. 투입 수량 = LOT 단위 = 품목 기본 단위(V034 트리거 + D-B1) |
| POP 정정 `correct` | `prd_wo_proc FOR UPDATE` → 산출 LOT 잠금 → 그 실적의 살아 있는 CONSUME 마다 `lots.reverse(gen)`(역분개 계보, 새 gen_id) |
| LOT 변환 `LotService.split·merge·relabel·rework` | 부모 LOT `FOR UPDATE` → 자식 LOT → `edge()`(계보 행, gen_id 반환 — 지금 버림). **모든 계보 INSERT 가 `edge()` 한 곳**(reverse 만 따로) |
| 단말·공정 위치 | 단말 = LINE·CELL 노드, 공정(`prd_wo_proc.node_id`)도 LINE·CELL. 장비 계층 규칙상 **WH·LOC 는 LINE·CELL 아래에 올 수 없다**(V020 `hb_equip_parent_ok`) → 12.2 의 "단말 작업장 아래 LINESIDE" 는 구조상 불가 — 12.4.3 으로 고친다 |
| 오류 처리 | `consume` 의 `BusinessException` 은 투입 행 칸 오류(`INVALID_VALUE`)로 바뀐다 — WMS 의 409(`INSUFFICIENT_STOCK`·`PERIOD_CLOSED`)가 그대로 나가려면 조정 필요(12.4.5 ③) |

#### 12.4.2 계약 하나 — `module.wms.link.LotStockLink`

```java
public interface LotStockLink {
    LotStockLink NONE = …;                 // WMS 없는 설치본·공장 스위치 N → 아무것도 안 함
    void onEdge(long genId);               // 계보 행 하나가 생긴 직후(같은 트랜잭션)
    void onReverse(long reversalGenId);    // 계보 역분개 직후
    static LotStockLink of(ObjectProvider<LotStockLink> p) { … }
}
```

- **WMS 가 계보 행을 직접 읽는다**(`lot_genealogy` 읽기만): 부모·자식 LOT, `rel_type`, 수량, `wo_proc_id`, `occurred_at`. MES 는 gen_id 만 넘긴다 — 인자 불일치가 생길 수 없다.
- `rel_type` 으로 나눈다: `CONSUME` → 투입 차감 `ISSUE_WO`, `SPLIT·MERGE·RELABEL·REWORK·REPAIR` → LOT 변환 `CONV_OUT/CONV_IN`, `SORT` → 아무것도 안 함(만드는 곳 없음).
- 공장 스위치 `WMS_STOCK_LINK`(공장 정책, N 기본): 투입은 공정의 공장, 변환은 부모 재고가 있는 공장마다 본다. N 이면 그 공장 몫은 건너뛴다.
- **켜기 전제(MES 검토)**: POP 오프라인 대기열(`hb.pop.js` flush)이 `status < 500` 응답을 지우는 동안에는 B5 의 409(`INSUFFICIENT_STOCK`·`PERIOD_CLOSED`)가 현장 실적을 조용히 버린다. **MES 가 "거부됨" 목록 보존으로 고치기 전에는 어느 공장도 스위치를 켜지 않는다.** → **충족(2026-10-22, MES #167 — 거부 목록 보존 main 반영)**. 기본값은 N 그대로, 공장별로 기초 실사 뒤 켠다.

#### 12.4.3 투입 차감 `ISSUE_WO`

| 항목 | 규칙 |
|---|---|
| 원장 | `ISSUE_WO` −, 출처 `LOT_GEN`·src_id = 계보 id, 멱등 키 `LOT_GEN:{genId}` + 칸 순번. LOT 관리 품목은 그 LOT, 비관리 품목은 품목 재고(lot_id 비움, B1 규칙) |
| 위치(D-B6 고침) | `wms_loc_attr` 에 **`feed_node_id`**(이 LINESIDE 위치가 보급하는 LINE·CELL, WMS 표) 추가. 순서 = ① `feed_node_id` 가 공정 노드이거나 그 상위 LINE 인 LINESIDE 위치 ② 그 밖 위치 pick_seq 순(SCRAP 제외). 재고 상태는 AVAILABLE 만. 여러 칸이면 칸마다 한 행 |
| 부족 | 켜진 공장에서 AVAILABLE 합 < 투입 수량 → 409 `INSUFFICIENT_STOCK`(D-B4) — POP 실적 전체 롤백 |
| 정정 | `onReverse(정정 계보 id)` → 공정·공장은 **원래 계보 행(`reversal_of_id`)** 에서 읽는다(옛 역분개 행은 맥락이 비어 있음 — #143 뒤 행만 복사됨). 원래 계보 id 의 ISSUE_WO 행들을 원래 칸으로 역분개(`reverseAt`, 발생 시각 = **정정 계보의 occurred_at** — 11.5 와 같은 규칙) |
| 멱등 | POP replay 는 계보를 만들지 않으므로 훅도 안 불린다. 동시 같은 요청은 `uq_prd_result_req` 가 막고, 원장 키 `LOT_GEN:{genId}` 가 한 번 더 막는다 |
| 마감 | 마감 달 시각 투입 → 409 `PERIOD_CLOSED`(11.4 와 같다 — 우회 없음) |

#### 12.4.4 LOT 변환 `CONV_OUT / CONV_IN`

| 항목 | 규칙 |
|---|---|
| 원장 | 부모 LOT `CONV_OUT` − / 자식 LOT `CONV_IN` +, 같은 칸·같은 상태, `move_grp_id` 짝(합 0). 원장 트리거 짝 검사에 "변환 짝 = 위치·상태 같고 LOT 다름, 품목 같음" 추가. 출처 `LOT_GEN`·계보 id |
| 어느 칸에서 | 부모 재고가 있는 칸에서 계보 수량만큼. 상태 순서(D-B11): 재작업·수리 = HOLD → BLOCKED → QC → AVAILABLE(불량·보류분을 먼저), 분할·병합·라벨 바꿈 = AVAILABLE → QC → HOLD → BLOCKED. 같은 상태 안에서는 pick_seq |
| 보류 | 보류 부모에서 재작업·수리는 계보가 허용 → 원장도 허용(보류 검사는 ADJ_OUT·MOVE_OUT 만) |
| 부족 | 켜진 공장에서 부모 재고 합 < 계보 수량 → 409(D-B4) |
| 정정 | 분할·병합은 MES 가 반대 작업으로 되돌린다 → 반대 변환이 자연히 생긴다. 계보 역분개(CONSUME 만)와 무관 |
| LOT 비관리 품목 | 원장에 LOT 이 없으므로 변환은 재고에 변화 없음(건너뜀) |

#### 12.4.5 MES 에 부탁하는 것 ("남의 코드 변경" — 1·3·4·5 는 MES 몫) 과 잠금 순서

1. `LotService`: `ObjectProvider<LotStockLink>` 필드, `edge()` 에서 gen_id 를 받은 직후 `LotStockLink.of(…).onEdge(gen)`, `reverse()` 에서 역분개 gen 직후 `onReverse(gen)`.
2. 잠금 순서는 그대로 지켜진다: 결과 = `prd_wo_proc → lot_master(lockForConsume) → ECO → [WMS] 기간 공유 → inv_stock`, 변환 = `lot_master(부모 FOR UPDATE) → [WMS] 기간 → inv_stock`, 정정 = `prd_wo_proc → 산출 LOT → 투입 LOT(MES 계보 가드 V077 `hb_guard_lot_genealogy` 가 역분개 INSERT 때 FOR UPDATE — WMS 트리거 FOR SHARE 보다 앞) → [WMS] 기간 → inv_stock`. WMS 는 그 뒤 LOT·ECO 를 잡지 않는다(11.6 고정 조건).
   **WMS 단독 다건 트랜잭션**(실사 확정·여러 줄 입고·이동 짝·역분개 짝)은 원장 행을 넣기 전에 관련 LOT 을 **lot_id 오름차순으로 먼저 FOR SHARE** 잡는다(`InventoryService.lockLots` — 2026-10-22 적용). 그러지 않으면 줄1 비관리 품목 X(inv_stock) → 줄2 LOT B 순서가 "LOT B → X 재고" 순인 MES 경로와 교착한다(시험 `LotLockOrderTest` — 선잠금을 빼면 `deadlock detected` 로 재현).
3. `PopWorkService.result` 의 `consume` catch: **칸 오류가 없는 `BusinessException`(409 `INSUFFICIENT_STOCK`·`PERIOD_CLOSED`)은 그대로 다시 던진다** — 지금은 `lotNo`·`STATE` 칸 오류로 바뀌어 원인이 사라진다. POP 화면은 409 메시지를 그대로 보여 준다.
4. (MES) `correct` 앞에 투입 LOT 을 `lockForConsume`(id 순)으로 먼저 잠근다 — 정정끼리·정정과 실적의 LOT 잠금 순서를 맞춘다.
5. (MES) `hb.pop.js` 대기열 flush 가 4xx 를 지우지 않고 "거부됨" 목록에 남긴다 — 스위치 켜기 전제(12.4.2). ✅ #167

WMS 가 하는 것: 계약·구현, `wms_loc_attr.feed_node_id`(로케이션 화면에 칸), 원장 유형 `ISSUE_WO`·`CONV_OUT`·`CONV_IN`·짝 검사, 정책 `WMS_STOCK_LINK`, 시험(정상·스위치 N·부족·마감·정정·동시(같은 LOT 투입 둘·투입과 이동)·A사로 B사).

#### 12.4.6 결정 — MES 동의, **사용자 확정 2026-10-22: 추천안 그대로**

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D-B11 | 변환할 때 부모의 어느 상태 재고부터 | **재작업·수리 = HOLD → BLOCKED → QC → AVAILABLE, 그 밖 = AVAILABLE 먼저** | 화면에서 상태를 고름(MES LOT 화면 변경) |
| D-B12 | LINESIDE 를 공정에 잇는 방법(D-B6 고침) | **`wms_loc_attr.feed_node_id`(WMS 표, LINE·CELL)** | 장비 계층 규칙을 바꿔 LINE 아래 LOC 허용(P1 V020 변경) |
| D-B13 | 라인 옆에 재고가 없을 때 | **창고 위치에서 pick_seq 순으로 차감**(실물 이동을 기록 안 한 것 — 총량은 맞음) | 라인 옆 재고만 — 보충 이동을 강제(현장 부담, 부족 거부가 잦음) |
| D-B14 **사용자 확정 2026-10-22: 추천안** | 창고에 들어온 적 없는 재공(WIP) LOT 의 투입·변환 | **원장에 반영하지 않음** — "재고로 관리되는 LOT" = 한 번이라도 원장에 들어온 LOT(LOT 관리 품목은 lot_id, 비관리 품목은 자재 입고 src·생산 입고 prd_receipt.lot_id). 앞 공정 산출 LOT 을 다음 공정이 바로 투입하는 흐름이 스위치를 켠 공장에서 409 로 막히지 않게 | 모든 투입에 409(D-B4 그대로) — 재공 투입이 막힌다 |

#### 12.4.7 구현 메모 (2026-10-22, V114(임시 V9307))

| 대상 | 구현 |
|---|---|
| 빈 | `LotStockPosting implements LotStockLink`(wms.inv) — MANDATORY 트랜잭션, 멱등 키 `LOT_GEN:{계보 id}`(postAs), 정정 키 `LOT_GENR:{정정 계보 id}:{n}` |
| 칸 고르기 | 후보를 우선순위(보급 라인 LINESIDE → pick_seq → 노드, 변환은 D-B11 상태 순서)로 읽고, **칸 순서(품목·위치·LOT·상태, 11.6 ③ — 처음엔 stock_id 순이었다가 QB-1 로 바꿈)로 FOR UPDATE** 한 뒤 지금 수량으로 다시 배분 — 라인마다 우선순위가 달라도 잠금 순서는 같다. 다른 라인을 보급하는 LINESIDE·SCRAP(조상 포함)·AVAILABLE 아닌 재고는 투입 대상이 아니다 **잠금 순서 = (MES 가 잡은 공정·LOT·ECO) → inv_stock 칸 FOR UPDATE → 기간 advisory(공유, 원장 INSERT 트리거)** — 칸을 기간보다 먼저 잡는다. 전제: 기간 마감·재개(배타 advisory)는 inv_stock 행을 잠그지 않는다(W5). 다른 원장 경로(실사 확정 포함)는 기간을 공유로만 잡아 "기간 공유 → inv_stock" 이므로 공유끼리 부딪히지 않는다. 마감·재개가 inv_stock 을 잠그게 바뀌면 이 순서를 다시 본다 |
| 원장 | 유형 `ISSUE_WO`(−)·`CONV_OUT`(−)/`CONV_IN`(+), 출처 `LOT_GEN` 필수(`ck_inv_trx_lot_gen_src`), 변환 짝 = 같은 (위치·상태)·다른 LOT(`hb_check_inv_move` 갱신), entry_method AUTO, 기록자 = 계보 recorded_by |
| 건너뜀 | 공장 스위치 N · 재공 LOT(D-B14 — 로그 `WMS_WIP_SKIP gen=… rel=… lot=… qty=…` INFO 로 건수를 셀 수 있다) · 비관리 품목 변환 · 부모 재고가 꺼진 공장에만 있음 · SORT |
| 로케이션 | `wms_loc_attr.feed_node_id` — LINESIDE 만(`ck_wms_loc_attr_feed`), 같은 공장의 사용 중 LINE·CELL(트리거 `fk_wms_loc_attr_feed_comp`). 로케이션 화면에 "보급 라인" 칸(LINESIDE 일 때만 보임), 하위 적용에는 퍼뜨리지 않음 |
| MES 훅 통합 | ✅ 2026-10-22 — MES #180(main #183) 훅으로 실제 POP 실적·정정 경로 통합 시험 `PopStockLinkTest` 4(스위치 Y: 라인 옆 먼저 차감·재전송 한 번·재고 부족 POP 409 실적·계보 없음·정정 원래 칸, 스위치 N 반영 없음). 스위치 켜기 전제는 #167 로 충족 |

### 12.5 B6 출하 — 경계 합의안 (2026-10-22, D-B15~D-B20 확정 — 영업 계약은 MES(영업 소유) 합의 뒤 B6a)

#### 12.5.1 지금 상태 (조사 2026-10-22)

| 대상 | 있는 것 | 없는 것 |
|---|---|---|
| 수주(P2 2단계, V032) | `sal_order`(OPEN/CLOSED/CANCELLED, 머리 상태는 줄에서 계산) · `sal_order_line`(품목·수량 = 품목 기본 단위·납기·OPEN/DONE/CANCELLED). 줄 마감은 사람이(`OrderService.closeLine` — "3단계부터 입고·출하가 닫는 자리") | 출하 수량·PARTIAL 상태, 출하 문서, `module.sales.link` 계약 |
| 출하검사(QMS) | `qms_insp.insp_type = 'FINAL'`(LOT 별, OPEN → JUDGED OK/NG, VOID 가능) | WMS 가 물을 계약(`qms.link` 없음). B3(판정 → 재고 상태)는 QMS 2부 대기 |
| PLM 변경 게이트 | `EcoTrace.shipChecks(lotIds)` → `ShipCheck(lotId, ecoId, ecoNo, reason, ppapStatus, block)` — **#171(PLM 3d), main 들어가는 중**. docs/39 17.3·17.4: WMS 정책 `SHIP_BLOCK_UNAPPROVED_CHANGE` 기본 Y, `lot_master` 잠금 뒤 한 번, block → 409 | WMS 정책·호출 |
| WMS | 차감 전략(W4, 미리보기만)·STAGING 위치 용도·라벨 유형 SHIP/PALLET·스캔 키 `PLT:`(자리만)·원장(B1~B5) | 원장 유형 `SHIP`, 출하·피킹 문서, 팔레트·SSCC |
| ERP | 아웃박스 `ERP_WO_RESULT`·`ERP_RECEIPT`(`ErpPublisher`), docs/07:94 "출하 확정 시 MES → ERP, 출하번호 키" | 출하 주제 |

#### 12.5.2 흐름 (B6a)

```text
출하 지시(wms_ship) ← 수주 줄(OPEN, 납기·고객) 고르기 — 줄마다 출하 수량(미결 이하)
  → 피킹: 차감 전략(W4)으로 후보 칸·LOT 제안 → 피킹 확정 = 출발 칸 → STAGING(출하 대기) 이동(MOVE 짝), 피킹 줄에 LOT·수량
  → 출하 확정(한 트랜잭션):
       ① 수주 게이트(영업 계약) — 출하 줄 전부를 한 번에 OrderShipGate.ship(List<ShipQty>) (MES 가 머리 order_id 순 → 줄 line_id 순 잠금)
       ② 피킹 LOT 을 lot_id 순 FOR SHARE(보류 확인 — 보류 LOT 출하 거부)
       ③ PLM shipChecks(잠금 없음) — 정책 Y + block 이면 409 SHIP_CHANGE_UNAPPROVED(줄 = LOT, ECO 번호), INTERIM 은 경고
       ④ 원장 SHIP −(STAGING 칸, 출처 SHIP_LINE) — 칸 순서(11.6 ③) FOR UPDATE → 기간 공유(12.4.7 과 같은 순서)
       ⑤ 출하 라벨(SHIP)·ERP_SHIP 아웃박스
```

- 잠금 순서: **영업 수주 머리·줄(order_id → line_id 순, MES) → lot_master(lot_id 순 FOR SHARE) → (ECO 읽기만) → inv_stock 칸(칸 순서, 11.6 ③) → 기간 공유**. 구매 입고(D-B3: 발주 머리 → 행 → lot_master)와 같은 모양 — 상위 문서 행이 먼저.
- 멱등: 게이트에는 멱등이 없다(MES 검토) — **WMS 출하 확정·취소가 요청 키(sys_inbox + 원장 client_req_id)로 한 번만** 부른다.
- 출하 취소(확정 뒤): SHIP 역분개(STAGING 으로 돌아옴) + 수주 게이트에 음수. 반품(`RETURN_CUST`)은 B6 밖.
- 출하검사 게이트는 **재고 상태**로 건다(D-B15): 피킹은 AVAILABLE 만(W4 그대로) — B3 이 들어오면 출하검사 대상 품목은 입고 때 QC, 합격 판정이 AVAILABLE 로 바꾼다. B6 에 따로 검사 조회를 넣지 않는다.

#### 12.5.3 상대 모듈 경계

| 모듈 | 부탁하는 것 | WMS 가 주는 것 |
|---|---|---|
| 영업(P2, MES 소유 — 검토 2026-10-22 반영) | `module.sales.link.OrderShipGate.ship(List<ShipQty(orderLineId, qty)>)` — **출하 줄 전부를 한 번에**, MES 가 머리 order_id 순 → 줄 line_id 순으로 잠금. 수량은 품목 기본 단위(환산 없음). `shipped_qty` 누적 ≥ 수량이면 자동 DONE(PARTIAL 상태 없음, 마감 출처 `done_by_ship_yn`). 초과는 정책 `SAL_OVERSHIP_PCT`(회사, 기본 0) 넘으면 409 `OVER_SHIP`, CANCELLED·사람 마감 DONE 줄은 409 `ORDER_LINE_CLOSED` — 칸 오류 없는 예외라 WMS 는 그대로 던져 전체 롤백. 음수 = 취소(0 아래 거부, 자동 DONE 줄은 다시 OPEN, 사람 마감 줄은 DONE 유지). 게이트 멱등 없음 | 원장 SHIP(출처 = 출하 줄)·출하 수량 원본. **읽기 계약 `ShipQuery.shippedByLine(lineIds)`**(module.wms.link, StockQuery 옆 — 줄별 SHIP 원장 합, 역분개 포함) → MES 대사 시험·화면 경고 |
| PLM | `EcoTrace.shipChecks`(#171) 그대로 | 정책 `SHIP_BLOCK_UNAPPROVED_CHANGE`(기본 Y)·409 |
| QMS | B3(출하검사 판정 → 재고 상태) 경계 합의 — 이 절 밖 | — |
| ERP(P6 경계) | — | `ERP_SHIP` 아웃박스(출하번호 키, 줄·LOT·수량), 송신기는 기존 `ErpPort` |
| ERP 고객 반품(2026-11-02) | — | `ERP_CUST_RTN` 아웃박스(반품번호-R/-C, 취소 음수), 송신기 종류 `CUST_RTN` — docs/59 15장 |

#### 12.5.4 결정 — **사용자 확인 2026-10-22: 추천안**("멈추는 결정은 추천안으로 진행")

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D-B15 | 출하검사 게이트를 어떻게 | **재고 상태로(B3 뒤)** — 피킹은 AVAILABLE 만. B6 는 검사 조회를 하지 않는다 | QMS 계약 `FINAL` 최신 판정을 출하 확정 때 조회(QMS 2부 전에 별도 계약 필요) |
| D-B16 | 출하 수량은 누가 가지나 | **영업 게이트 + 투영**(D-B3 과 같은 모양): 원본 = WMS 원장, `sal_order_line.shipped_qty` 투영·자동 DONE 은 영업 계약 안에서 | WMS 계약으로 영업이 읽기만(줄 잠금 없음 → 동시 출하가 미결을 넘을 수 있음) |
| D-B17 | ERP 출하 송신 | **B6a 에 `ERP_SHIP` 아웃박스**(docs/07:94) | P6 로 미룸 — 출하가 ERP 에 안 감 |
| D-B18 | 팔레트·SSCC | **B6b 로 미룸** — B6a 는 LOT·품목 단위 피킹·출하, 출하 라벨만 | B6a 에 팔레트 표·`PLT:` 번호·SSCC(18)까지 |
| D-B19 | 출하 라벨 고객 품번(custItemCd) | **B6a 는 비움**(고객 품번 마스터는 P1 몫으로 제안) | `mst_ext_id_map`(외부 시스템별 매핑이라 고객별 품번과 맞지 않음) |
| D-B20 | 단계 나눔 | **B6a**(출하 지시·피킹 → STAGING·출하 확정 SHIP·게이트(보류·PLM·영업)·라벨·ERP) → **B6b**(팔레트·SSCC·PDA 피킹은 B7) | 한 번에 |

### 12.6 B4b 구매 입고 RCV_PO (구현 2026-10-22, V122(임시 V9308) — SRM #177 `PoReceiptGate`·`PoSupplyQuery` 위에)

D-B3(게이트 + 투영) 그대로. **원본 = WMS 원장**(`inv_trx.po_line_id`·`po_qty`), SRM `srm_po_line.recv_qty`·발주 머리 상태는 게이트가 바꾸는 투영. WMS 는 SRM 표에 쓰지 않는다.

#### 12.6.1 흐름 (`MaterialReceiptService.receive`, 한 트랜잭션 — B4a 와 같은 화면·API, `poLineId` 가 있으면 구매 입고)

1. 요청 id 선점(sys_inbox `WMS_RCV_MAT`, B4a 와 같은 키 공간) — 이미 있으면 처음 결과(게이트도 다시 부르지 않는다).
2. 칸 검사(위치·수량·상태·시각·프린터). 품목은 발주 행에서 정하므로 화면 품목은 확인용(다르면 `itemId` `PO_ITEM`).
3. **게이트** `PoReceiptGate.receive(poLineId, 발주 단위 수량, "RCV_PO:{clientReqId}", 로그인 아이디)` — 잠금 발주 머리 → 행, 열린 수량·초과율·상태·ERP 원천·환산·자릿수 판정은 SRM(409 `SRM_*`, 다른 회사 404). 그 뒤 공장 확인(`poLineId` `PO_PLANT`).
4. 공급사 = 발주 거래처(`Receipt.custId` → `cust_cd`, 화면 값은 쓰지 않음) → P2 `LotService.receive(기본 단위 수량)`.
5. 원장 `RCV_PO` +(기본 단위 `Receipt.baseQty`), `po_line_id`·`po_qty`(발주 단위, 부호 = qty), 출처 MAT_RECEIPT·LOT. 라벨은 B4a 와 같음.

잠금 순서: **발주 머리 → 발주 행(게이트) → 새 LOT(경합 없음) → 기간 공유 → inv_stock** — 상위 문서 행이 먼저(12.5.2 출하와 같은 모양).

#### 12.6.2 정정

수불 원장 화면 역분개(`InventoryService.reverseAt`) — 행에 `po_line_id` 가 있으면 LOT 선잠금 **앞에서** 게이트에 `−po_qty`(근거 `RCV_POR:{trxId}`). 이미 출고해 재고가 모자라면 409 `INSUFFICIENT_STOCK` 이고 게이트 변경도 같이 롤백. 마감된 발주도 되돌릴 수 있다(SRM 규칙). 이미 되돌린 행은 게이트 전에 칸 오류.

#### 12.6.3 원장·스키마 (V122, 임시 V9308)

| 대상 | 내용 |
|---|---|
| `inv_trx` | `po_line_id`(FK `srm_po_line`)·`po_qty`, 부분 색인 `(comp_cd, po_line_id)` |
| CHECK | 유형 RCV_PO(+), `ck_inv_trx_rcv_po`: RCV_PO ⇔ 발주 행, 발주 행 ⇔ po_qty, po_qty ≠ 0·부호 = qty / 출처 RCV_MAT·RCV_PO = MAT_RECEIPT·LOT |
| 원장 검사 `hb_guard_inv_trx` | 발주 행이 같은 회사·같은 품목(`fk_inv_trx_po_line_comp`, FK 는 RLS 를 안 봄), 역분개 거울에 `po_line_id`·`po_qty = −원래` |
| 대사 | `inv_reconcile_run.rcv_po_mismatch` = `srm_po_line.recv_qty` ≠ Σ `po_qty`(역분개 포함)인 발주 행 수 — 게이트를 거치지 않은 변경 검출(읽기만, 남의 표 읽기) |
| 코드 | `INV_TRX_TYPE.RCV_PO` |

#### 12.6.4 화면

품목을 고르면 `GET /api/wms/rcv/po-lines?plantCd&itemId` → SRM `PoSupplyQuery.open` 을 발주 행으로 합친 선택지(발주번호-행·공급사·가장 이른 납기·잔량 — 발주 단위). 기본 = "발주 없음(자재 입고)". 고르면 공급사 칸은 발주 거래처로 고정, 확인 창에 "구매 입고 — 발주 …, 수량은 발주 단위". SRM 빈이 없는 설치본은 `enabled = false` → 칸 숨김, API 로 `poLineId` 를 보내면 `PO_UNAVAILABLE`.

#### 12.6.5 남은 것

| 항목 | 내용 |
|---|---|
| ✅ 잔량 단위 | SRM R2(#199)가 `Supply` 에 `poUnitCd`·`openPoQty` 를 더함 → 화면 잔량을 발주 단위로(입력 수량과 같은 단위, 2026-10-22) |
| ✅ 발생 시각 넘기기 | (SRM #199 가 WMS 호출부 2곳을 `receiveAt` 으로 바꿈 — 입고 = 원장 발생 시각, 되돌림 = 원래 RCV_PO 행 시각) SRM R2 가 `PoReceiptGate` 에 발생 시각(occurredAt) 오버로드를 더하면(입고 이력·납기 준수율) 입고는 원장 `occurred_at`, 역분개는 역분개 행 시각으로 바꿔 부른다 — 지금은 기존 시그니처(상위 세션 안내 2026-10-22) |
| ✅ 원장 RCV_PO 직접 게시 | (2026-10-24 막음) 계약 `InventoryService.post`(·`postChecked`)는 **RCV_PO 행이 있으면 400 `trxType`** — SRM 게이트를 건너뛸 길을 닫는다. 입고 서비스(`MaterialReceiptService`)만 패키지 안 `postReceipt` 로 게이트(`receiveAt`) 뒤에 넣는다. 대사 `rcv_po_mismatch` 는 그대로(직접 SQL 몫) |

### 12.7 B6a 출하 (구현 2026-10-22, V130(임시 V9309) — 영업 `OrderShipGate`(V123)·PLM `EcoTrace.shipChecks` 위에)

12.5 경계 합의안 그대로. 출하 수량 원본 = WMS 원장 SHIP, 영업 `sal_order_line.shipped_qty`·자동 마감은 영업 게이트 투영(D-B16).

#### 12.7.1 문서와 상태

| 표 | 내용 |
|---|---|
| `wms_ship` | 머리 — 공장·고객 하나, 출하 대기 위치(STAGING, 같은 공장) 하나, 예정일. DRAFT → PICKING(피킹이 있음) → SHIPPED, 출하 취소 = SHIPPED → PICKING, 피킹을 다 취소하면 PICKING → DRAFT, 지시 취소 = DRAFT → CANCELLED(사유) |
| `wms_ship_line` | 줄 = 수주 행 하나(같은 회사·같은 고객·같은 품목, 트리거), 지시 수량(기본 단위). DRAFT 일 때만 추가 |
| `wms_ship_pick` | 피킹 — 출발 칸·LOT·수량. PICKED → SHIPPED(출하) / UNPICKED(끝), 출하 취소는 SHIPPED → PICKED. 원장 이동 짝·SHIP 의 출처(SHIP_PICK = pick_id) |

지시 만들 때 남은 수량 = 수주 − 출하 투영 − 다른 열린(DRAFT·PICKING) 지시에 잡힌 지시 수량. 최종 판정(허용 초과율)은 확정 때 영업 게이트.

#### 12.7.2 흐름과 잠금

| 동작 | 순서(한 트랜잭션) |
|---|---|
| 피킹 확정 | 요청 id(sys_inbox `WMS_SHIP_PICK`) → 머리 FOR UPDATE(row_ver) → 칸 검사(출하 대기·폐기(조상 포함) 위치 거부, 줄 피킹 합 ≤ 지시, LOT 관리 품목은 LOT) → 피킹 행 → 원장 MOVE 짝(출발 → STAGING, AVAILABLE 만, **한 번에 — 칸 순서 11.6 ③**) |
| 피킹 취소 | 머리 → 고른 PICKED 피킹의 이동 짝을 `InventoryService.reverseAll` 로 한 번에(칸 순서) → UNPICKED, 남은 피킹 없으면 DRAFT |
| 출하 확정 | 요청 id(`WMS_SHIP_CONFIRM`) → 머리 FOR UPDATE → ① 영업 `OrderShipGate.ship`(수주 행별 합, 한 번에 — 영업이 머리 → 행 잠금) → ② 피킹 LOT lot_id 순 FOR SHARE(`lockLotsFirst`) → ③ PLM `shipChecks`(읽기만): block 이고 정책 `SHIP_BLOCK_UNAPPROVED_CHANGE`(기본 Y)면 409 `WMS_SHIP_CHANGE_BLOCKED`(LOT·ECO 번호), 그 밖은 결과 warnings → ④ 원장 SHIP −(STAGING 칸, 칸 순서) — 보류 LOT 은 원장 검사가 거부(`LOT_HOLD`) → 피킹 SHIPPED·머리 SHIPPED → ⑤ 출하 라벨(줄마다 한 장, 고객 품번 비움 D-B19)·ERP_SHIP |
| 출하 취소(승인) | 요청 id(`WMS_SHIP_UNDO`) → 머리 → 영업 게이트 음수(자동 마감 행 다시 열림) → SHIP 역분개 한 번에(원래 시각 — 마감 달이면 409) → 피킹 PICKED·머리 PICKING → ERP 음수 전표 |

잠금 순서 = **WMS 출하 머리 → 영업 수주 머리·행 → lot_master → (ECO 읽기) → inv_stock 칸(칸 순서) → 기간 공유**. 영업·MES 경로는 wms_ship 을 잡지 않으므로 머리를 먼저 잡아도 엇갈리지 않는다.

지시 만들기(2026-10-29, QA W-1): 남은 수량(수주 − 출하 − 다른 열린 지시)을 읽기 **전에** 수주 행 id 오름차순으로 트랜잭션 advisory 잠금(키 `WMS_SHIP_OL:{회사}:{수주 행 id}`) — 같은 수주 행으로 동시에 만드는 지시가 차례로 읽어 남은 수량을 넘지 않는다. 영업 표는 잠그지 않는다(영업에 잠금 계약이 없고, 영업 게이트의 행 잠금과 엇갈리지 않게). 순서 = **수주 행(advisory) → 채번 → 출하 머리 INSERT**. 시험 `qa.ShipCreateRaceQaTest`.
- 남은 수량을 읽어 검사하는 WMS 경로 = **지시 만들기뿐**(2026-10-29 grep: `wms_ship_line` INSERT 는 `ShipMapper.insertLine` 하나 ← `ShipService.create`, 줄 수량 UPDATE 없음, CANCELLED 는 되살리지 않음).
- 범위 밖 메모: ① 영업이 수주 행 수량을 줄이는 것과의 경합(영업 쪽, 최종은 확정 때 영업 게이트).
- **출하 취소(undo, 2026-10-30 사용자 결정 U-1)**: 다시 열면 지시 수량 전체가 "열린 지시" 로 돌아오므로 지시 만들기와 같은 수주 행 advisory 잠금(행 id 순)을 잡고, 끝에서 남은 수량이 음수면 409 `WMS_SHIP_UNDO_OVER`(트랜잭션째 롤백 — 영업 게이트·SHIP 역분개 포함). 잠금 순서: **출하 머리 → 수주 행(advisory) → 영업 게이트**. 만들기(수주 행 → 채번 → 머리 INSERT)는 남의 출하 머리를 잡지 않으므로 엇갈리지 않는다. 시험 `ShipTest#U1_*`(순서·동시).

#### 12.7.3 원장·스키마 (V130, 임시 V9309)

| 대상 | 내용 |
|---|---|
| `inv_trx` | 유형 SHIP(−), `ck_inv_trx_ship_src`(SHIP = 출처 SHIP_PICK), 부분 색인(SHIP_PICK 출처) |
| 원장 검사 `hb_guard_inv_trx` | 보류 LOT 거부에 SHIP 추가(V122 판 교체) |
| 가드 | 머리 전이 표·번호·공장·고객 고정·피킹 뒤 대기 위치 고정·대기 위치 = STAGING / 줄 = DRAFT 일 때만, 수주 행 같은 회사·고객·품목 / 피킹 전이·고정 열 |
| 정책·코드·화면 | `SHIP_BLOCK_UNAPPROVED_CHANGE`(회사, Y), `WMS_SHIP_STATUS`·`WMS_PICK_STATUS`·`INV_TRX_TYPE.SHIP`, `wms-ship`(SEARCH·SAVE·APPROVE), 채번 `WMS_SHIP`(SHP-yyMMdd-seq) |
| 계약 | `module.wms.link.ShipQuery.shippedByLine(orderLineIds)` — 수주 행별 SHIP 원장 합의 부호 반대(역분개 포함) |
| ERP | 아웃박스 `ERP_SHIP`(줄마다, 업무 키 출하번호-줄-v{row_ver}, 취소는 음수) — 송신기는 P2 `ErpOutboundHandler`(종류 SHIP)를 WMS 설정 빈으로 하나 더(P2 코드 변경 없음). 정책 `ERP_SYSTEM` 이 등록된 어댑터일 때만 |

#### 12.7.4 화면 `wms-ship`

출하 목록 → 신규(고객 코드 → 남은 수량 있는 수주 행 → 지시 수량) / 머리·줄(줄마다 "제안" — 차감 전략 W4 미리보기에서 출하 대기·폐기 칸 제외) → 피킹할 칸(수량만 고침) → 피킹 확정 → 출하 확정(라벨 프린터 선택). 피킹 취소는 피킹 목록 체크, 출하 취소는 승인 권한.

#### 12.7.5 남은 것

| 항목 | 내용 |
|---|---|
| ✅ 수주 행 읽기 | (2026-10-22) MES 계약 `OrderLineQuery`(#223)로 교체 — 열린 행 = `openLines(custId)`, 지시 줄의 수주 번호·행·납기 = `lines(ids)` 를 자바에서 붙임. 다른 열린 지시에 잡힌 수량만 WMS 가 센다(`heldByOrderLine`). ShipMapper 는 영업 표를 읽지 않는다. DB 가드 `hb_guard_wms_ship_line`(같은 회사·고객·품목 확인)은 트리거라 그대로 영업 표를 본다 |
| ✅ 피킹 칸 고르기 | (2026-10-24) 사무 화면 줄의 [다른 칸] — `GET /api/wms/ships/{id}/lines/{lineId}/stock?scan=` 이 줄 품목의 **가용 칸 전부**(출하 대기 칸·STAGING·SCRAP 상위 포함 제외, 200칸까지, 보류 LOT 은 표시만)를 주고, `LOC:`(위치 하위 포함)·`LOT:` 스캔으로 좁힌다(다른 품목·없는 키 = 빈 목록 + 안내). [더하기] 수량 = min(칸 가용, 줄 남은 수량 − 이미 넣은 다른 칸), 같은 칸·LOT 다시 = 바꿈. 피킹 서버 규칙(`pick` — 칸 용도·넘침·LOT·재고)은 그대로라 목록은 고르기 도움일 뿐. 상세를 다시 그리면 패널을 닫는다(수량이 낡음). PDA 스캔 피킹은 B7-2(12.12.11) |
| ✅ B6b-1 | 팔레트·SSCC(D-B18) — 12.10. 스캔·ERP SSCC 목록은 B6b-2 |

### 12.8 CMS 예비품 출고·반납 (구현 2026-10-22, V132(임시 V9310) — 계약 docs/33 11.15 `module.cms.link.SpareIssueLink`, D30~D33)

CMS 는 사용 기록(`cms_mwo_part`)만, 수량·차감은 WMS 원장. WMS 는 CMS 표를 읽지 않고 FK 도 걸지 않는다 — 값은 계약 인자로.

| 항목 | 구현 |
|---|---|
| 계약 파일 | `module.cms.link.SpareIssueLink`(인터페이스·`NONE`·`of`) — 문서대로 CMS 패키지에 WMS 가 만듦(남의 코드 = 새 파일). CMS 의 완료 전이·반납 버튼 호출은 CMS 몫 |
| 계약 변경(WMS 제안) | `onPartReturn(partId, qty, reason, **clientReqId**, by)` → **boolean**(true = 반납함·같은 요청, false = 그 줄 출고 원장 없음). 반납 버튼 두 번 누름이 한도 안에서 두 번 들어가는 것을 막는다 |
| 출고 `onMwoDone` | 공장 정책 `WMS_MRO_LINK`(기본 N) = Y 일 때만. 줄(partId 순)마다 멱등 키 `CMS_PART:{partId}`(이미 있으면 건너뜀), 원장 밖 품목 건너뜀(D33, 로그 `WMS_MRO_SKIP`). 칸 = 그 공장 AVAILABLE·보류 아닌 LOT, 폐기(조상)·수입검사(QC)·출하 대기(STAGING) 위치 제외, LOT 선입(lot_id) → pick_seq → 노드. **모든 줄의 후보 칸을 먼저 칸 순서(11.6 ③)로 잠근 뒤** 지금 수량으로 배분 → 줄마다 `ISSUE_MRO` −(발생 = 완료 시각, AUTO). 모자라면 409(D31 — 완료 전체 롤백) |
| 반납 `onPartReturn` | 요청 id 가 이미 있으면 true. 그 줄 출고 칸(원장에서)을 잠근 뒤 다시 계산 → 누적 반납 ≤ 출고(넘으면 409 `WMS_MRO_RETURN_OVER`, 남은 양). **나중에 출고한 칸부터** `MRO_RTN` +(그 칸 출고 − 반납까지, 같은 LOT·상태), 발생 = 지금 |
| 원장 | 유형 `ISSUE_MRO`(−)·`MRO_RTN`(+), `ck_inv_trx_mro_src`(출처 CMS_PART), 부분 색인 |
| CMS 합의(2026-10-22, 상위 경유) | 빈 목록이면 아무것도 안 함(CMS 는 분기 없이 넘김), 시각·수량은 CMS 값 그대로(환산 없음), 권한은 호출자 것(cms-mwo SAVE) — 계약 안에서 WMS 화면 권한 검사 없음, 반납 초과는 409(칸 오류 아님) |
| 잠금 | (CMS: cms_mwo) → lot_master(lot_id 순 FOR SHARE) → inv_stock 칸(칸 순서) → 기간 공유 |

**C18 반납 누계 읽기(2026-10-23, CMS 계약 docs/33 11.15.2 — 마이그레이션 없음)**: `MroIssuePosting.returnedQty(partIds)` — 키 = 출고 원장(ISSUE_MRO, 출처 CMS_PART)이 있는 줄(반납 없으면 0), 값 = MRO_RTN 합(역분개 포함, 0 아래면 0). 원장 밖 품목·스위치 꺼짐·완료 전·없는 id·남의 회사 줄 = 키 없음(모름). 빈 입력·null = 빈 맵, 500줄씩 IN 으로 읽음, 잠금 없음, 부르는 쪽(CMS 상세) 읽기 트랜잭션 안. 읽기 실패는 예외 대신 빈 맵 + 경고 기록 — 다만 SQL 오류 자체는 PostgreSQL 트랜잭션을 깨므로 그 뒤 같은 트랜잭션 쿼리도 실패한다(읽기 하나뿐인 지금 호출 모양에서는 문제없음, 호출이 늘면 세이브포인트 후보).

### 12.9 R3b ASN 입고 (구현 2026-10-22, 마이그레이션 없음 — SRM docs/31 4.11 R3a `AsnQuery`·`PoReceiptGate` ASN 오버로드)

입고 흐름은 B4b 그대로(입고 경로 하나, R3-1). ASN 은 칸을 채우는 입력 도구 + 게이트에 ASN 행을 알려 SRM 이 ASN 받은 양·상태를 같은 트랜잭션에서 바꾸게 한다.

| 항목 | 구현 |
|---|---|
| 스캔 | `ScanKeyResolver` 유형 **ASN**(`ASN:{번호}` — SRM `AsnQuery.SCAN_PREFIX`, 납품서 인쇄 바코드). 협력사 납품서 번호는 스캔으로 해석하지 않는다(접두어 없는 스캔은 정책 `WMS_SCAN_BARE_AS`) — 화면 검색 칸으로 |
| API | `GET /api/wms/rcv/asns`(공장의 도착 예정·입고 중 ASN, 늦은 것 포함 ~ 30일 뒤, SRM 없으면 빈 목록), `GET /api/wms/rcv/asn?key=`(번호·`ASN:` 바코드·납품서 번호, 없으면 404, 다른 공장 ASN 은 `asnKey` `PO_PLANT`) |
| 입고 | `RcvForm.asnLineId`(발주 행이 있을 때만 — 아니면 `ASN_NEEDS_PO`) → `PoReceiptGate.receiveAt(…, at, asnLineId)`. 다른 발주 행의 ASN 행은 SRM 409 `SRM_ASN_LINE_MISMATCH`, ASN 남은 양 초과는 막지 않음(R3-4) |
| 역분개 | `InventoryService.reverseAll` 이 `reverseAt(…, at, reversesRef = "RCV_PO:{원래 요청 id}")` — SRM 이 그 이력의 발생일·ASN 행을 찾아 ASN 받은 양·상태도 되돌린다(원장 `client_req_id` 를 읽음, ASN 행은 WMS 원장에 두지 않음 — R3-3) |
| 화면 | 입고 화면 위에 ASN 칸(SRM 있을 때만): 도착 예정 선택 / 번호 검색 / 스캔 → ASN 행 표 → "채우기" = 품목·발주 행(열린 행 목록에 없으면 넣음)·수량(남은 양, 발주 단위)·공급사 LOT·유통기한(라벨만 — D-B9). 발주 행을 바꾸면 ASN 연결을 끊는다. ASN 행마다 입고 요청 하나(LOT 하나) |
| ✅ 남은 것 | (2026-10-22 해결 — docs/29 결정 #43, MES V135: 입고마다 새 내부 LOT, 공급사 LOT 은 속성. WMS 코드 변경 없음, 입고 화면 도움말·주석만 고침, `AsnReceiptTest` 나눠 받기 시험은 MES 가 추가) 같은 ASN 행을 나눠 받을 때 두 번째부터 **공급사 LOT 중복**(P2 규칙: 공급사·품목별 공급사 LOT 한 번 — B4a)으로 거부된다(브라우저에서 확인). 덜 온 나머지를 같은 공급사 LOT 으로 받는 길은 P2 LOT 규칙 결정이 필요(같은 공급사 LOT 의 추가 입고 = 같은 LOT 에 더하기 / 새 LOT) — 동결 뒤 MES(P2)와 정할 일 |

### 12.10 B6b 팔레트·SSCC (설계·B6b-1 구현 2026-10-22, V134(임시 V9311) — 그룹 A, D-B18 의 뒤)

B6a 는 LOT·품목 단위로 피킹 → STAGING → 출하. B6b 는 STAGING 에 모인 피킹을 **팔레트(물류 단위)** 로 묶고, GS1 **SSCC**(Serial Shipping Container Code, AI (00), 18자리)를 붙여 팔레트 라벨을 찍는다. 재고 원장은 바뀌지 않는다 — 팔레트는 피킹을 묶는 문서이고 재고 칸은 STAGING 위치 그대로(D-B25).

#### 12.10.1 단계 (PR 1개 = 단계 1개)

| 단계 | 범위 |
|---|---|
| **B6b-1** | 팔레트 표·피킹 담기/빼기·닫기(SSCC 부여·팔레트 라벨)·다시 열기·빈 팔레트 취소, 출하 확정 때 팔레트 필수 정책, 출하 화면 팔레트 칸 |
| ✅ B6b-2 | 출하 화면 스캔(`PLT:` 내부 번호·GS1 (00) SSCC → 팔레트 열기), ERP_SHIP 본문에 줄별 SSCC 목록, 출하 라벨에 팔레트 수 |

#### 12.10.2 모양 (B6b-1)

| 대상 | 규칙 |
|---|---|
| `wms_pallet` | 출하 지시 하나 안(D-B21), 번호 `PLT-yyMMdd-seq`(채번 `WMS_PALLET`), `sscc` char(18)(회사 안 유일, 닫을 때 부여 — D-B23), 상태 OPEN → CLOSED(봉인) → (지시 출하 확정과 함께) 그대로, CLOSED → OPEN(다시 열기, 사유), 빈 OPEN → CANCELLED |
| `wms_ship_pick.pallet_id` | PICKED 피킹만 담고 뺀다, 팔레트는 같은 지시·OPEN 일 때만(가드). 피킹 취소(UNPICKED)는 팔레트에서 빠진 뒤(또는 함께 빠짐) |
| SSCC | 확장 자리 `0` + 회사 정책 `WMS_GS1_COMPANY_PREFIX`(GS1 업체코드 6~12자리 숫자) + 일련번호(채번 `WMS_SSCC`, 초기화 없음, 남는 자리 0 채움) + 검사 숫자(GS1 mod 10) — 정책이 비면 SSCC 없이 내부 번호만(D-B22). 일련번호 자리가 모자라면 409 |
| 팔레트 라벨 | 라벨 유형 PALLET(W2) — `palletNo`·`sscc`(새 필드)·품목(한 품목이면 그 품목, 여럿이면 "MIXED")·수량 합(한 품목일 때)·LOT 수·`barcode`(`PLT:{번호}`). 닫을 때 프린터를 고르면 인쇄 대기열 |
| 출하 확정 | 공장 정책 `WMS_SHIP_PALLET_REQUIRED`(기본 N) = Y 면 PICKED 피킹이 전부 CLOSED 팔레트에 있어야 확정(아니면 409 `WMS_SHIP_UNPALLETIZED`, D-B24) |
| 잠금 | 지시 머리 FOR UPDATE → 팔레트 행 FOR UPDATE → 피킹 행. 원장·재고 칸은 잡지 않는다 |

#### 12.10.3 결정 — **상위 판단(사용자 지시 "멈춘 세션은 추천안으로")**, 2026-10-22

| # | 질문 | 추천(채택) | 다른 안 |
|---|---|---|---|
| D-B21 | 팔레트 범위 | **출하 지시 하나 안** — 한 고객·한 대기 위치, 출하 확정·취소와 같이 움직인다 | 지시를 넘는 혼적 팔레트 — 확정·취소가 여러 지시에 걸려 잠금·되돌림이 복잡 |
| D-B22 | SSCC 번호 | **회사 정책 GS1 업체코드 + 채번 일련번호 + 검사 숫자**, 업체코드 없으면 내부 번호만 | 항상 내부 번호 — GS1 라벨을 쓰는 고객사에 못 보냄 |
| D-B23 | SSCC 부여 시점 | **닫을 때(봉인)**, 다시 열어도 같은 SSCC 유지(라벨 재발행) | 만들 때 — 빈 팔레트·취소마다 번호를 버림 |
| D-B24 | 팔레트 필수 | **공장 정책 `WMS_SHIP_PALLET_REQUIRED`, 기본 N** — 팔레트 없이 출하하는 공장(택배·소량)을 막지 않음 | 항상 필수 |
| D-B25 | 원장 | **영향 없음** — 팔레트는 묶음 문서, 재고 칸은 STAGING 위치 그대로 | 팔레트를 재고 칸(LPN)으로 — 원장 키에 팔레트를 더해야 해 B1~B6a 전부 바뀜 |

#### 12.10.4 B6b-1 구현 (2026-10-22, V134(임시 V9311))

| 항목 | 구현 |
|---|---|
| API | `POST /api/wms/ships/{id}/pallets`(새 OPEN) · `/pallets/picks`(`palletId` + `pickIds` 담기, `palletId` null = 빼기) · `/pallets/{palletId}/close`(프린터·템플릿·매수) · `/reopen`(사유 2자 이상) · `/cancel`(빈 것만). 모두 SAVE, 머리 `rowVer`(성공하면 머리 row_ver 올림 — 같은 지시의 다른 동작과 줄 세움). 지시가 DRAFT·PICKING 일 때만(아니면 409 `WMS_SHIP_STATE`). 조회 `GET /{id}` 에 `pallets`, 피킹에 `palletId`·`palletNo`. 템플릿·프린터 선택지는 `?usage=PALLET` |
| 담기·빼기 | 피킹은 이 지시의 PICKED 만(아니면 행 오류 NOT_FOUND). 빼는 쪽·넣는 쪽 팔레트를 **id 순으로** FOR UPDATE 한 뒤 둘 다 OPEN 인지(아니면 `PALLET_CLOSED`) |
| 닫기 | 빈 팔레트 `PALLET_EMPTY`, 프린터·매수 먼저 확인(틀리면 아무것도 안 바뀜). SSCC 는 처음 닫을 때만 만든다(다시 닫으면 그 SSCC — 일련번호 안 씀). 업체코드 모양이 틀리거나 일련 자리 넘침 → 409 `WMS_SSCC_UNAVAILABLE`(팔레트 OPEN 그대로). 라벨 = PALLET 한 장(`LabelFields` PALLET 에 `sscc` 추가) |
| 피킹 취소 | 닫힌 팔레트의 피킹은 행 오류 `PALLET_CLOSED`(먼저 다시 열기), OPEN 팔레트의 피킹은 팔레트에서 빼고(pallet_id NULL) 되돌림 |
| 출하 확정 | 정책 Y 면 `countUnpalletized`(PICKED 인데 CLOSED 팔레트에 없음) > 0 → 409 `WMS_SHIP_UNPALLETIZED {0}`. 확정 뒤 팔레트·피킹 묶음은 그대로(출하 취소로 PICKING 이 되면 다시 열기 가능) |
| DB 가드 | `hb_guard_wms_pallet`(INSERT 는 OPEN·SSCC 없음, 키·SSCC 고정, 전이 표, 찬 팔레트 취소 금지), `hb_guard_wms_ship_pick_pallet`(PICKED 만, 빼는 팔레트·넣는 팔레트 OPEN, 같은 지시·회사), RLS·DELETE 회수·보존 QUALITY_RECORD |
| 화면 | 출하 화면 오른쪽 "팔레트" — 새 팔레트·담을 팔레트 선택·고른 피킹 담기/빼기(피킹 표 체크 재사용), 팔레트 라벨 프린터·템플릿, 팔레트 행마다 닫기·다시 열기·취소. 피킹 표에 팔레트 칸 |
| 남의 표·코드 | 없음(WMS 표 `wms_ship_pick` 에 칸 추가, `LabelFields`·`HbErrorCode` 는 WMS 블록) |

#### 12.10.5 B6b-2 구현 (2026-10-22, 마이그레이션 없음)

| 항목 | 구현 |
|---|---|
| 스캔 | `POST /api/wms/ships/scan {text}`(SEARCH) → 그 팔레트의 지시(`shipId`·`shipNo`·`palletId`·`palletNo`·`sscc`·`status`). 읽는 모양: `PLT:{번호}`(팔레트 라벨 barcode, 스캔 키 규칙 W1 의 PLT), GS1 (00)(심볼 식별자 `]C1`·괄호·GS 형식 — W1 판정 그대로), **접두어 없는 숫자 18자리(SSCC)·20자리(`00`+SSCC)** — 스캐너가 심볼 식별자를 안 보내도 읽게(이 화면만, 접두어 없는 코드 정책 `WMS_SCAN_BARE_AS` 와 무관). 검사 숫자 틀림 400 `CHECK_DIGIT`, 팔레트 바코드가 아님(LOT·GTIN·위치 등) 400 `NOT_PALLET`, 없음·다른 회사 404 |
| 화면 | 출하 화면 위 "팔레트 스캔" — 찾으면 그 지시를 열고 팔레트 행을 표시, OPEN 이면 담을 팔레트로 고름. 공장 조회 조건과 무관하게 연다 |
| ERP_SHIP | 줄마다 `palletNos`·`ssccs`(그 줄 피킹이 담긴 팔레트, 정렬·중복 없음, 없으면 빈 목록) 추가 — 기존 칸은 그대로(받는 쪽이 모르는 칸은 무시) |
| 출하 라벨 | SHIP 필드에 `palletCount`(그 줄의 출하된 피킹이 담긴 팔레트 수, 팔레트 없이 나가면 0) |
| 남의 표·코드 | 없음 |

### 12.11 UI 단계 3 목록 틀 적용 (2026-10-22 — docs/40 7장 P1·10.1, 결정 docs/29 #44)

WMS 화면 10개(출하·입고·이동·실사·마감·원장·재고·위치·라벨·인쇄 이력)에 목록 화면 표준 틀 ②~⑥ 을 적용했다. 서버 API·스키마 변경 없음.

| 항목 | 적용 |
|---|---|
| ① 표 → hb.grid | **12.11.1(결정 docs/29 #45 = A — 화면별 API 추가만)** — 주 목록 표 4개(출하·실사·마감·라벨 템플릿/프린터)는 hb.grid 의 서버 페이징·`/columns`·`/export` 가 필요해 API 추가가 든다. 나머지 표(출하 줄·피킹·팔레트·수주 행 입력, 실사 줄 입력, 입고 ASN·최근, 이동 최근, 재고 이력·할당)는 상세·입력 표라 표로 둔다. 원장·재고·인쇄 이력·위치는 이미 hb.grid/hb.tree |
| ② 조회 영역 | 10개 모두 `hb-search` 패널(이미 있음 — 확인만) |
| ③ 도구줄 순서 | [신규] 를 맨 앞으로(출하·실사·라벨 — 건수 글자는 뒤로). 엑셀은 그리드 화면의 그리드 머리(hb.grid 공통) |
| ④ 위험 동작 | 출하 취소·지시 취소·실사 취소 → 외곽선 위험 톤 + 도구줄 오른쪽 끝 자리(`wms-danger-zone`), 마감 재개·역분개 → 외곽선 위험 톤(이미 저장과 다른 폼). 위험 톤은 **공통 `hb-btn-danger-outline` 을 page.css 에 추가**(상위 허용 예외, 의미 토큰만), 오른쪽 끝 자리는 WMS 전용 `css/views/wms.css` 의 `wms-danger-zone` |
| ⑤ 상태 표시 | `hb.status` 배지: 출하 상태·피킹 상태·팔레트 상태·실사 상태·마감 상태(톤은 docs/40 부록 A — 진행 normal, 끝 success, 검토·재개 warning, 대기·취소 neutral) |
| ⑥ 대화상자 | `window.confirm/prompt` 19곳 → `hb.dialog.confirm/prompt`(제목 = 동사, 확정 버튼 = 동사, 취소·재개·역분개는 danger). 사유 입력은 required·500자. **남김**: 위치·라벨 화면의 `confirmLeave`(미저장 이동 확인) 2곳 — 그리드 `beforeSelect` 가 동기 boolean 을 받아 비동기 대화상자로 못 바꾼다(공통 부족 — 비동기 beforeSelect 필요) |

#### 12.11.1 ① 표 → hb.grid 1차 (2026-10-22, 결정 docs/29 #45 = A — 화면별 API 추가만, 스키마 변경 없음)

| 목록 | 새 API(기존 목록 API 그대로) | 정렬 허용 열 | 기본 정렬 |
|---|---|---|---|
| 출하 `wms-ship` | `GET /api/wms/ships/grid`(plantCd·status·from·to + page·size·sort·total) · `/columns` | shipNo·custCd·planDate·status·createdAt | 예정일 ↓, 끝에 ship_id ↓ |
| 실사 `wms-count` | `GET /api/wms/counts/grid`(plantCd·status + 페이징) · `/columns` | countNo·countType·status·createdAt | 만든 시각 ↓, 끝에 count_id ↓ |

- 권한 `@ProgAuth(SEARCH)`, 정렬은 enum 허용 열만(모르는 열·4개 이상·같은 열 두 번 400), size ≤ 정책 `GRID_PAGE_SIZE_MAX`. 회사 범위는 RLS 그대로.
- `/export` 는 두지 않았다 — 두 화면의 `sys_prog` 동작에 EXCEL 이 없어 넣으려면 마이그레이션이 든다(결정 #45 는 `/grid`·`/columns` 만).
- **마감 기간 목록은 표로 둔다** — 서버가 그 해 12개월을 늘 다 채워 주는 달력(빈 달 포함)이라 페이징·정렬이 뜻이 없다.
- 그리드 CODE 열은 코드 이름 글자로 보인다 — 목록의 `hb-status` 배지(12.11 ⑤)는 상세 화면에만 남는다(공통 부족: 그리드 열의 상태 배지 표시).
- **함께 고친 회귀**: 기존 그리드 API(재고 `/api/wms/stock`·원장 `/api/wms/trx`·인쇄 이력 `/api/wms/print-jobs`)가 `@RequestParam List<String> sort` 로 받아 정렬 키 하나(`itemCd,asc`)를 스프링이 쉼표로 쪼개 **헤더 한 번 클릭 = 400** 이었다(브라우저에서 재현). `request.getParameterValues("sort")` 로 원래 값을 읽게 고침(CustApiController 와 같은 방식), 회귀 시험 추가.

#### 12.11.2 ① 2차 + 공통 보강 반영 (2026-10-22 — main #258 hb.grid `status`·`rowClass`·비동기 `beforeSelect`)

| 항목 | 적용 |
|---|---|
| 라벨 템플릿·프린터 목록 | 표 → 보기(템플릿/프린터)마다 hb.grid 하나. `GET /api/wms/labels/grid`·`/columns`(정렬 labelType·tplCd·tplNm·updatedAt, 기본 유형·코드), `GET /api/wms/printers/grid`·`/columns`(정렬 plantCd·printerCd·printerNm·usage·updatedAt, 기본 공장·코드). 기존 목록 API 그대로(출하 화면 선택지 등이 씀). 기본 = ★(글자 열 + 화면 format), 포트는 쉼표 없는 글자 |
| 상태 칸 배지 | 출하·실사 그리드 상태 열 = `hb-status`(12.11 ⑤ 톤 그대로), 취소 행 `hb-tree-dim`. 라벨·프린터 사용 여부 = 배지(사용 normal·미사용 neutral) + 미사용 행 흐리게 |
| 미저장 이동 확인 | 위치·라벨 화면 `confirmLeave` → 화면 안 대화상자("저장하지 않고 이동", 공통 문구 `js.common.leaveTitle`). 트리·그리드는 비동기 `beforeSelect`(아니라고 하면 선택이 옮겨 가지 않음 — 예전 위치 트리는 선택이 먼저 옮겨 가고 폼만 남는 어긋남이 있었다), 공장 바꾸기·보기 바꾸기·조회·신규·다시 매기기는 Promise 로. 공장 바꾸기를 취소하면 고르기 전 공장으로 되돌린다 |
| 화면 CSS | 라벨 화면은 그리드 머리가 `display` 를 가져 `hidden` 이 안 먹어, 감싼 div(`wms-grid-wrap`, wms.css)를 숨긴다 |

### 12.12 B7 PDA 화면 — 경계 합의안 (2026-10-23, 결정 #49 — MES 답 반영(상위 경유) · B7-1 은 MES 작은 PR 뒤)

**한 줄**: 창고 PDA 는 P2 5a 의 **단말 계열**(`/pop/**`·`/api/pop/**`·`ROLE_TERMINAL`·배지 작업자·`hb.pop`)을 그대로 쓰고, WMS 는 그 위에 **창고 화면(`/pop/wh/*`)과 `/api/pop/wh/**` API** 만 얹는다. 재고는 지금처럼 `InventoryService.post` 한 곳으로만 움직이고, 단말이 만든 `client_req_id` 로 멱등·오프라인 재전송을 받는다.

#### 12.12.1 이미 있는 것 (코드로 확인)

| 쪽 | 있는 것 | 근거 |
|---|---|---|
| MES(5a) | 단말 등록·활성화·쿠키 `HB_TERMINAL`, `terminal_type` POP/PDA, 배지 전환·비활동 내려감, 보안 체인 `PopSecurityConfig`(`/pop/**`·`/api/pop/**`, 사무 API 와 분리), `hb.pop`(스캔 칸·숫자 키패드·큰 확정·피드백·온라인 표시) | docs/25 4.6, `module/pop/*` |
| MES(5b) | 오프라인 대기열(IndexedDB write-ahead, 네트워크·5xx 만 재전송, 4xx 는 빼고 화면에), `client_req_id` 멱등 규칙 | docs/25 4.7 |
| WMS | 원장 `inv_trx` 의 `entry_method`(SCAN/KEY…)·`performed_by`·`client_req_id` 멱등, `StockService.move`(위치·상태 이동, 같은 자리 거부, 섞임 검사, LOT), `CountService.enter`(센 수량), 스캔 해석 `ScanKeyResolver` | 12.3·5장 |

→ **새 원장·새 테이블 없음**이 기본안. B7 은 화면 + 단말용 얇은 API.

#### 12.12.2 경계 — 누가 무엇을

| 항목 | 소유 | 정하는 것 |
|---|---|---|
| 단말·배지·세션·`hb.pop`·대기열 엔진 | **MES** | WMS 는 고치지 않는다 — 필요한 것은 아래 합의 1~3(MES 작은 PR) |
| PDA 화면(`/pop/wh/*`)·`/api/pop/wh/**` | **WMS** | 화면 템플릿·JS·API 컨트롤러는 WMS 소유(`module/wms/pda`). API 는 `@ProgAuthExempt(근거: 단말 체인)` — 5a 규칙과 같음 |
| 재고 쓰기 | **WMS** | 사무 화면과 같은 서비스(`StockService.move`·`CountService.enter`·…)를 부른다. PDA 전용 우회 경로 없음 |
| 기록 | 공통 | `recorded_by` = 단말 행위자(`t{단말}:{코드}`), `performed_by` = 배지 작업자, `entry_method` = SCAN(스캔으로 고른 값)/KEY(손 입력) |

**MES 와 합의한 것 (2026-10-23, 상위 경유)**

| # | 항목 | 합의 |
|---|---|---|
| 1 | 홈 갈래 | **서버에서 나눔** — `GET /pop` 이 단말 유형 PDA 면 WMS 페이지 `/pop/wh` 로 리다이렉트. MES 작은 PR: `PopSession` 에 `terminalType` + `/pop` PDA 리다이렉트. 창고 템플릿·JS 는 WMS 소유, 공용은 `hb.pop.js`(쿠키·배지 세션·키패드·대기열)만 |
| 2 | 오프라인 대기열 | `hb.pop.queue` 는 URL·본문을 그대로 저장하는 범용 — 대기 건수는 POP·창고 합쳐 같은 머리띠. 거부 목록 요약은 MES 가 `queue.send(url, body, {label})` 를 지원하고 WMS 는 보낼 때 label(예: "이동 RM-100 10 → A-01")을 넘긴다 |
| 3 | 단말 바인딩 | PDA 단말은 `node_id` 비움 = **공장 전체**(지금도 가능, 마이그레이션 없음). 창고 노드(`WH`·`LOC`)에 묶는 것은 필요해지면 MES 가 V035 트리거를 유형별 허용 노드로 넓힌다. PDA 는 POP "내 할 일" 조회를 쓰지 않으므로 영향 없음 |
| 4 | 단말 유형 권한 | MES 같은 PR 에서 체인이 막는다 — `/pop/wh/**`·`/api/pop/wh/**` = PDA 만, POP 할 일·실적 API = POP 만(403). WMS 컨트롤러는 유형을 다시 보지 않는다. 쿠키 인증·CSRF·CSP·401 은 `/pop/**` 체인 그대로 |

#### 12.12.3 화면 순서

| 단계 | 화면 | 흐름(키보드 없이, 스캔 우선) | 쓰는 서비스 |
|---|---|---|---|
| **B7-1** | 창고 홈 `/pop/wh` | 배지 → 메뉴(이동·실사, B7-2 에서 입고·피킹) | — |
| **B7-1** | 이동(`/pop/wh` 이동 칸) | 출발 위치 스캔 → 품목/LOT 스캔(그 위치 재고 1건이면 자동) → 수량(키패드, 기본 = 그 재고 전량) → 도착 위치 스캔 → 확정 | `StockService.move` |
| **B7-1** | 실사 센 수량(`/pop/wh` 실사 칸) | 진행 중 실사 고르기(이 공장) → 위치 스캔 → 그 위치 줄들 → 품목/LOT 스캔 → 센 수량 → 저장. 제출·재실사·게시는 사무 화면 | `CountService.enter` |
| B7-2 | 입고 `/pop/wh/rcv` | 발주/ASN/라벨 스캔 → 수량 → 위치 | `MaterialReceiptService.receive` |
| B7-2 | 피킹 `/pop/wh/pick` | 출하 지시 고르기 → 제안 칸 스캔(다른 칸 스캔 = 12.7 "B7 몫") → 수량 → STAGING | `ShipService` 피킹 |

#### 12.12.4 결정 후보 (상위·MES 확인)

| # | 항목 | 제안 | 대안 |
|---|---|---|---|
| D-B21 | 오프라인 | **이동만** 대기열(label 붙여)(같은 `client_req_id` 재전송, 재고 부족 등 4xx 는 빼고 화면). 실사 센 수량은 온라인만 — 실사 줄의 `row_ver` 충돌을 대기 뒤에 풀면 남의 입력을 덮는다 | 둘 다 온라인만(가장 단순) |
| D-B22 | 권한 | 단말 + 작업자면 이동·센 수량 가능, 사무 권한표(`@ProgAuth`) 는 보지 않는다(5a 와 같음). 막을 것은 **공장 밖 위치·품목**(404 처럼) | 작업자 역할에 창고 권한 칸 추가(스키마 변경) |
| D-B23 | 발생 시각 | 단말이 보낸 시각, `POP_FUTURE_TOLERANCE_SEC` 넘는 미래 거부, 마감된 기간이면 거부(`sys_period_close` → `ck_inv_trx_period_closed`, 지금 규칙 그대로) | — |
| D-B24 | 바코드 | `ScanKeyResolver` 그대로(위치 코드·품목 코드·LOT 라벨). GS1 (402)·SSCC 는 12.10 뒤 | — |

#### 12.12.5 B7-1 완료 기준(24 7장 K + 이상 사용)

- 1280×800·800×1280·390 넘침 없음, 누르는 대상 48px, 키보드 없이 끝까지, 확정 연타 1회
- 같은 `client_req_id` 재전송 → 처음 결과 그대로·원장 행 1쌍, 오프라인 → 온라인 재전송, 대기 중 재고가 줄면 4xx 로 빠지고 화면에 남음
- 사무 계정(ROLE_USER)으로 `/api/pop/wh/**` → 거부, 단말 쿠키로 사무 `/api/wms/**` → 거부, POP 유형 단말로 `/api/pop/wh/**` → 403(합의 4)
- 작업자 없음 → 401 `POP_NO_WORKER`, 비활동 뒤 같은 요청 거부
- A사 단말로 B사 위치·품목·실사 id → 없는 것처럼, 원장 `recorded_by`/`performed_by` 가 단말/작업자로 드러남

#### 12.12.6 하지 않을 것 (B7)

- 새 원장·새 재고 테이블, PDA 전용 재고 규칙 — 사무 화면과 같은 서비스만
- 실사 제출·게시·재실사 지시, 이동 정정(역분개) — 사무 화면
- 네이티브 앱·카메라 바코드 — 웨지 스캐너(키보드 입력) 가정, 카메라는 나중 후보

#### 12.12.7 B7-1 구현 메모 (2026-10-23, MES 단말 유형 #343(main #350) 위에 — 마이그레이션 없음)

| 항목 | 한 것 |
|---|---|
| 화면 | `/pop/wh` 한 화면에 칸(배지·PIN·메뉴·이동·실사·보내지 못한 이동) — 12.12.3 의 `/pop/wh/move`·`/count` 를 따로 두지 않았다(단말 화면은 다시 읽지 않고 칸만 바꾸는 POP 홈과 같은 방식). 템플릿 `wms/pda-home`, JS `views/wms/pda-home.js`, CSS `views/wms-pda.css`(pop.css 위) |
| API | `module/wms/pda` — `GET /api/pop/wh/scan?text=`(위치면 그 위치 재고 0 초과 50줄), `POST /moves`, `GET /counts`(이 공장 COUNTING), `GET /counts/{id}`, `POST /counts/{id}/counts`. 모두 `@ProgAuthExempt`(단말 체인), 작업자는 MES `PopAuthService.currentWorker`, 없으면 428 `POP_NO_WORKER` |
| 범위 | 단말 공장 = 위치·실사 범위(작업장 비움 = 공장 전체, 합의 3). 다른 공장 실사 id = 404, 다른 공장 위치로 이동 = 칸 오류(재고 서비스 규칙 그대로) |
| 이동 | 사무 화면과 같은 `StockService.move` — 상태는 그대로(출발 = 도착), `performed_by` = 배지 작업자, `recorded_by` = 단말(`t{id}:{코드}` — `InventoryService.me()` 의 행위자 대체), `entry_method` SCAN/KEY, `clientReqId` 필수. 같은 id 재전송 = 처음 결과(`replay`), 동시에 두 번이면 `DUPLICATE_REQUEST` 를 잡아 먼저 들어간 행으로(POP 실적과 같음). 발생 시각 = 확정 누른 순간의 공장 현지 시각 |
| 실사 | `CountService.enter` 그대로(머리 rowVer, 블라인드 장부 감춤). `CountService.me()` 가 로그인 사용자만 받던 것을 `InventoryService.me()` 처럼 요청 행위자로 대체 — 센 사람 칸(`counted_by`) = 단말. 409 면 친 수량은 남기고 다시 읽어 보여 준다(QU-2 와 같은 원칙) |
| 오프라인 | 이동 **확정**만 대기열(`hb.pop.queue.send(url, body, {label})`). 위치·재고 **스캔 조회는 온라인 필요** — 확정 순간에 끊긴 것을 잃지 않는 것까지가 B7-1. 스캔까지 오프라인(위치·재고 미리 받아 두기)은 후보(12.12.8) |
| 문구 | 머리·배지·PIN 은 MES `pop.*`·`js.pop.*` 를 같이 쓰고(같은 셸), 이동·실사·대기열 문구는 `pdaWh.*`·`js.pdaWh.*`(POP 대기열 문구가 "실적" 이라) |

#### 12.12.8 남긴 것 (B7)

| 항목 | 비고 |
|---|---|
| B7-2 입고·피킹 | 12.12.3 |
| ✅ 실사 줄의 작업자 | (2026-10-24 사용자 결정: 기록함) → 12.12.13 `counted_worker_id` |
| 오프라인 스캔 | 위치·재고를 단말에 받아 두고 스캔까지 오프라인 — 재고가 바뀌는 사이의 판정이 단말·서버 둘로 갈라져 크다. 필요하면 따로 |
| 배지·PIN 흐름 중복 | POP 홈(`pop/home.js`)과 창고 홈이 같은 배지·PIN·대기열 거부 목록 코드를 각자 가진다 — MES 가 `hb.pop` 에 세션 도우미로 꺼내면 둘 다 줄어든다(공통 후보, MES 판단) |

#### 12.12.9 B7-2a 피킹 API (2026-10-23, 서버만 — 화면은 MES 배지·PIN 공통화 병합 뒤 B7-2b)

| 항목 | 한 것 |
|---|---|
| API | `GET /api/pop/wh/ships`(이 공장 DRAFT·PICKING), `GET /ships/{id}`, `GET /ships/{id}/lines/{lineId}/suggest`, `POST /ships/{id}/picks` — 사무 피킹과 같은 `ShipService`(view·suggest·pick) |
| 범위 | 단말 공장의 출하만 — 다른 공장·회사 id 는 404(목록에도 없음). 출발 칸은 `PickReq.fromNodeId` 그대로라 **제안 밖 칸 스캔(12.7 의 B7 몫)도 서비스 규칙으로** 된다. 공장 밖 칸은 서비스가 거부 |
| 멱등·동시 | `clientReqId` 는 inbox(같은 요청 = 지금 보기), 머리 rowVer 가 옛것이면 409 — 덮어쓰지 않는다 |
| 기록 | `ShipService.me()` 가 로그인 사용자 없으면 요청 행위자(단말). 세 서비스(실사·출하·원장)의 대체 규칙을 `InventoryService.me()` 하나로 모음(Count·Ship 은 위임) |
| D-B25 | **입고·피킹은 온라인만** — 피킹은 rowVer, 입고는 발주 잔량·ASN 이 대기 사이에 바뀌어 늦게 보낸 요청이 남의 것을 덮거나 초과를 만든다(이동만 대기열 — D-B21) |
| **입고 보류(결정 2026-10-23 사용자: (다) 나중에)** | 사무 입고(`MaterialReceiptService.receive`)는 모든 입고에서 MES `LotService.receive` 로 RECEIVED LOT 을 만드는데, 그 서비스가 **로그인 사용자만** 받는다 — 단말 요청은 401. `LotService` 는 건드리지 않는다(사용자 작업). **보류 — LotService 단말 행위자 처리 뒤(사용자 작업)** 에 PDA 입고 API(입고 스캔·ASN·발주 행·입고, 온라인만)를 붙인다. 코드는 넣지 않았다 |

#### 12.12.10 PQ-1 같은 요청 id·다른 내용 = 409 (2026-10-23, QA #369 PQ-1 사용자 결정 — 마이그레이션 없음)

| 항목 | 정한 것 |
|---|---|
| 규칙 | 사람이 보낸 이동·조정(사무 화면·창고 PDA — `StockService.move`·`adjust`)은 `InventoryService.postChecked`: 같은 `clientReqId` 가 다시 오면 **처음 원장 행과 내용이 같을 때만** 처음 결과(replay 200). 유형·품목·위치·상태·수량·LOT·출처 중 하나라도 다르거나, 회사 안 다른 출처 원장 행이 쓰던 id 면 **409 `DUPLICATE_REQUEST`** — 아무것도 움직이지 않는다(POP 실적과 같은 규칙) |
| 왜 | 대기열이 id 를 잘못 다시 쓰면 화면은 "성공", 재고는 그대로인 조용한 틀림이 됐다(QA PQ-1) |
| 동시 | 같은 id 가 동시에 두 번이면 한쪽이 unique 에 걸린다 → PDA 는 새 트랜잭션에서 같은 이동을 다시 불러 같은 비교를 한다(같으면 replay, 다르면 409 — 예전처럼 무조건 replay 하지 않는다) |
| 그대로 둔 것 | 내부 게시(LOT 재고·생산 입고·예비품 출고/반납)는 자기 키로 다시 계산한 행을 보낼 수 있어 `post` 의 id 만 보는 replay 그대로 |
| 비교에서 뺀 것 | 발생 시각·비고 — 같은 확정을 다시 보낸 것인지만 본다 |


#### 12.12.11 B7-2b PDA 피킹 화면 (2026-10-23, 화면만 — B7-2a API·MES 공용 hb.pop 위에, 마이그레이션 없음)

| 항목 | 한 것 |
|---|---|
| 흐름 | 메뉴 [피킹] → 이 공장 출하 지시(DRAFT·PICKING) → 줄(누름 또는 품목 스캔, 다 피킹한 줄은 꺼짐) → 출발 칸: **제안 누름**(LOT 함께) 또는 **위치 스캔**(제안 밖 칸도 — 판정은 서버, 12.7 B7 몫), LOT 관리 품목은 LOT 스캔 → 수량(키패드) → [피킹] → 출하 대기 칸으로. 줄이 남으면 그 줄에 머물고 제안을 다시 읽는다 |
| 온라인만(D-B25) | 대기열을 쓰지 않는다 — `hb.pop.api` 직접. 오프라인이면 오류로 보이고 친 것은 남는다 |
| 키패드 | **빈 칸에서 시작**(제안 수량을 채워 두지 않음) — 실사에서 겪은 "7 → 77" 과 같은 이유. 제안 수량은 목록 글자로만 |
| 409 | 머리 rowVer 가 옛것(다른 단말·사무가 지시를 바꿈)이면 친 출발 칸·LOT·수량을 남기고 지시를 다시 읽는다. 재고 부족 409 는 그대로 오류만 |
| 확인한 서버 동작(기록) | 이미 PICKING 인 지시에 다른 사람이 피킹해도 머리 rowVer 는 바뀌지 않아 409 가 아니다 — 두 피킹 모두 유효한 더하기이고 지시 수량 초과는 줄 검사(400 "피킹할 수 있는 양 n")가 막는다. 조용한 틀림 아님 |
| 배지·대기열 | MES 공용 `hb.pop.badgeLogin`·`rejectedList`(#366) 그대로 — 피킹은 대기열이 없어 거부 목록에 오지 않는다 |
| 남은 것 | ~~PDA 입고~~ → 12.12.15(2026-11-01), 팔레트 담기(B6b)·출하 확정은 사무 화면 |


#### 12.12.12 B7-3 PDA 반품 출고 (2026-10-24, 마이그레이션 없음 — 사무 반품 출고 12.14 를 단말로)

| 항목 | 한 것 |
|---|---|
| API | `GET /api/pop/wh/returns/available`(SRM 없는 설치본은 메뉴 숨김), `GET /api/pop/wh/returns?returnNo=`(지시 + 후보 칸, `RTN:` 스캔 키), `POST /api/pop/wh/returns` — 사무와 같은 `ReturnIssueService`(SRM 게이트·RTN_SUP·멱등·나눠 출고 없음). `@ProgAuthExempt`(단말 체인), PDA 유형만(합의 4) |
| 범위 | **단말 공장 반품만** — 다른 공장·회사 반품 번호는 없는 것(400 `returnNo` NOT_FOUND, 사무 화면과 같은 칸 오류) |
| 기록 | 원장 `performed_by` = 배지 작업자, `entry_method` = SCAN, `recorded_by`·SRM `updated_by` = 단말 행위자 `t{id}:{cd}`(최대 41자 — id bigint 19자리 + 코드 20자, 칸 50자 안이라 넘칠 길 없음 — 상위 확인 2026-10-24) |
| 화면 흐름 | 메뉴 [반품 출고] → 반품 라벨 스캔 → 후보 칸 누름 또는 위치 스캔 → 수량(빈 키패드) → [칸 추가](여러 칸, **같은 칸·상태를 다시 넣으면 바꿈** — 두 번 눌러 두 배가 되지 않게) → 합 = 지시 수량이면 [반품 출고]. 합이 넘으면 "칸을 눌러 빼세요" 안내, 칸 재고보다 많으면 거부 |
| 온라인만 | D-B25 와 같은 이유 — 나눠 출고가 없어(R4-9) 대기 뒤 늦게 보내면 그사이 재고·반품 상태 판정이 단말·서버로 갈라진다. 요청 id 는 누른 순간 만들고 4xx(409 제외)면 버려 고친 입력은 새 요청 |
| 정정 | 사무 수불 원장 역분개(12.14 — 출고 전체·SRM 확정으로) |


#### 12.12.13 실사 줄의 센 사람 (2026-10-24, V170·임시 V9315 — 12.12.8 "정책 질의" 의 사용자 결정: 기록함)

| 항목 | 한 것 |
|---|---|
| 칸 | `wms_count_line.counted_worker_id`(FK `mst_worker`, 같은 회사 트리거 `fk_wms_count_line_worker_comp`, 센 수량 없이 작업자만 있을 수 없음 `ck_wms_count_line_worker`) |
| 채움 | 창고 PDA 센 수량 = 배지 작업자(`CountService.enter(id, f, workerId)`). **사무 화면에서 다시 세면 비운다** — 줄마다 "마지막으로 센 사람" 하나(사무 입력의 센 사람은 `counted_by` = 로그인 아이디) |
| 화면 | 사무 실사 줄 표에 "센 사람" 열 — PDA 면 작업자 이름(코드), 사무 입력이면 로그인 아이디. PDA 응답(`LineView`)에도 `countedWorkerCd·Nm` |
| 기존 줄 | 이 판 전 줄은 비어 있다(되짚어 채우지 않음 — `counted_by` 단말 행위자로만 남음) |

#### 12.12.14 실사 엑셀 내보내기·붙여넣기 (2026-10-24, V172·임시 V9316 — 3장 화면 표의 "남은 것")

| 항목 | 한 것 |
|---|---|
| 권한 | `sys_prog` `wms-count` actions 에 EXCEL 추가(V172·임시 V9316 — 이미 있으면 그대로). 내보내기 API `POST /api/wms/counts/{id}/export` = `@ProgAuth(EXCEL)`, 버튼도 EXCEL 있을 때만 |
| 내보내기 | 고른 실사 한 건의 **최신 줄만**(재실사로 밀린 옛 줄 제외) — 열: 줄 번호·위치·품번·품명·LOT·재고 상태·재실사·단위·장부·센 수량·차이·센 사람. 공통 `ExcelExportService`(파일명·감사·건수 상한 그대로). **블라인드 세는 중이면 장부·차이 칸은 비운다** — 화면 `view()` 와 같은 규칙을 그대로 써서 엑셀로 장부가 새지 않음 |
| 붙여넣기 | 화면만(새 API 없음) — 센 수량 칸에 여러 줄 붙이면 ① 한 열: 붙인 칸부터 아래로 ② 탭 두 열 "줄 번호·센 수량" ③ 내보낸 엑셀 행 그대로(첫 열 줄 번호·10번째 열 센 수량). ②③은 **줄 번호로 맞춤**(정렬이 바뀌어도 엉뚱한 줄에 안 들어감). 빈 값은 건너뜀(지우지 않음), 숫자 아님·화면에 없는 줄은 세어 알림, 천 단위 쉼표 제거. 저장은 기존 [센 수량 저장](rowVer·서버 검사 그대로) |
| 안 한 것 | 엑셀 파일 올리기(`hb.import` 작업) — 실사는 한 건 수십~수백 줄이라 붙여넣기로 충분, 올리기 작업 이력·오류 파일까지는 과함. 필요해지면 같은 줄 번호 열쇠로 붙임 |

#### 12.12.15 PDA 입고 (2026-11-01, 사용자 결정 W-6·W-7 — MES `LotService.receive` 단말 행위자 #711(main #715) 위에, 마이그레이션 없음)

12.12.9 의 "입고 보류" 를 푼다. 사무 입고와 **같은** `MaterialReceiptService`(SRM 게이트·ASN·MES LOT·원장·멱등)를 단말 + 배지 작업자로.

| 항목 | 정한 것 |
|---|---|
| API | `GET /api/pop/wh/rcv/po-enabled`·`/rcv/scan?text`·`/rcv/asn?key`·`/rcv/po-lines?itemId`·`/rcv/locs?itemId`, `POST /rcv` `{clientReqId, itemId, qty, nodeId, poLineId, asnLineId, supplierLotNo, entryMethod}` |
| 흐름 | 품목·GS1 라벨(품목·공급사 LOT·라벨 수량) 또는 ASN 납품서 스캔 → ASN 행(품목·발주 행·공급사 LOT 함께) / 발주 행(또는 **발주 없이 받기** = 자재 입고 RCV_MAT, 공급사 없음) → 위치 후보 누름·위치 스캔 → 수량(빈 키패드) → [입고]. 같은 ASN 이면 끝난 뒤 그 ASN 을 다시 읽어 다음 행 |
| 기록 | 원장 `performed_by` = 배지 작업자(`receiveByWorker`), `recorded_by`·LOT 기록자 = 단말(`t{id}:{코드}` — W-6) |
| 공장·상태·시각 | 공장 = 단말 공장, 상태 = **위치 기본 상태**(없으면 AVAILABLE) → 수입검사 대상이면 QC 고정(12.15.6), 발생 시각 = 서버가 받은 때. PDA 는 상태를 고르지 않는다 |
| 위치 후보 = 저장 판정 | `MaterialReceiptService.locations` 와 저장이 같은 `rcvNodeReason`(구 `pdaNodeReason`, W-9 로 사무 입고도 같은 규칙) — 재고 안 둠 `STOCK_NOT_ALLOWED`·출하 대기 `STAGING_NOT_ALLOWED`(출하분과 섞지 않음, docs/53 F1)·폐기 위치 아래 `SCRAP_LOCATION` 은 목록에 사유와 함께, 저장도 400. 받는 칸(RECEIVING) 먼저 |
| 온라인만(D-B25) | 대기열 안 씀 — 발주 잔량·ASN 이 대기 사이에 바뀌어 늦은 요청이 초과를 만든다. 같은 `clientReqId` 다시 = 처음 결과(replay, inbox — 동시면 뒤 요청이 기다렸다가 처음 결과). 요청 id 는 누른 순간, 4xx(409 제외)면 버리고 다음 누름에 새 id, 수량을 고쳐도 새 id |
| 문구 | `valid.STAGING_NOT_ALLOWED` 를 "생산 입고" → "입고" 로 넓힘(생산 입고·PDA 입고 둘 다 같은 코드) |
| 시험 | `PdaReceiveTest`(6) — docs/testing/wms.md |

~~**어긋남(질의)**: 사무 입고 화면은 출하 대기 칸 입고를 막지 않는다~~ → **결정 W-9(2026-11-01 사용자, 추천대로)**: 사무 입고도 거부 — 같은 `rcvNodeReason`, 400 `STAGING_NOT_ALLOWED`(PDA·생산 입고와 같은 코드). 사무 화면 위치 목록에서도 빠지고, 출하 대기 칸을 스캔하면 안내 문구. 시험 `MaterialReceiptTest#B_*`.
남긴 것: PDA 입고 라벨 인쇄(사무 화면은 있음 — 단말 프린터 고르기 필요), 공급사 고르기(발주 없이 받을 때 — 지금은 공급사 없음).

### 12.13 R4-8 구매 입고 이력에 받은 LOT (구현 2026-10-23, V154·임시 V9312 — SRM docs/31 4.12.7 선행, 반품 출고 R4-4 의 앞)

| 항목 | 정한 것 |
|---|---|
| 왜 | 반품 출고(R4-4)에서 교체 요구 반품이 **어느 입고를 되돌릴지** 알아야 한다 — 반품 지시에는 LOT 만 있다. SRM 입고 이력(`srm_po_receipt`)에 받은 LOT 를 남긴다 |
| 순서 | 구매 입고는 지금처럼 **게이트(이력 INSERT) → LOT 생성(MES `LotService.receive`) → 원장**. LOT 는 게이트 뒤에 생기므로 같은 트랜잭션에서 `PoReceiptGate.attachLot("RCV_PO:{요청 id}", lotId)` 가 이력 1행의 `lot_id` 를 채운다. LotService 는 그대로(사용자 작업 중) — 호출 순서도 바꾸지 않음 |
| attachLot 규칙 | 그 근거의 **입고(qty > 0)·lot_id 비어 있음** 1행만 UPDATE. 0행(없는 근거·되돌림 행·이미 채움·다른 회사)이면 **409 `SRM_STATE`** — 조용히 넘어가면 반품 출고가 입고를 못 찾는다. SRM 없음(`NONE`) = IllegalStateException(발주 입고 자체가 안 됨) |
| 되돌림 | 역분개의 게이트 `reverseAt(..., reversesRef)` 가 원래 입고 이력의 `lot_id` 를 되돌림 행에 그대로. 이 판 전 입고(빈 칸)를 되돌리면 되돌림 행도 비어 있다 |
| DB | `lot_id bigint NULL` + 부분 색인 `(comp_cd, lot_id)`. FK 없음(MES 표) — 같은 회사 LOT 만 트리거 `fk_srm_po_receipt_lot_comp`. 이력은 추가만이던 것을 **`lot_id` null → 값 한 번만** 풀었다(트리거 + 칸 단위 `GRANT UPDATE (lot_id)`, 설비 상태 원장 `end_at` 과 같은 모양). 다른 칸·값 바꾸기·지우기·삭제는 그대로 막힘 |
| 남의 표·링크 | SRM `srm_po_receipt`(칸·트리거), `module.srm.link.PoReceiptGate`(`attachLot` 추가, `NONE` 포함)·`SrmPoReceiptGate`·`PoReceiptMapper` — 상위 승인(2026-10-23), SRM 세션 리뷰 |

### 12.14 R4-4 협력사 반품 출고 RTN_SUP (구현 2026-10-23, V159·임시 V9313 — SRM 반품 출고 게이트 docs/31 4.12.7, R4-3·R4-9·R4-10)

**한 줄**: SRM 이 확정한 반품 지시를 WMS 가 원장 `RTN_SUP`(−) 로 내보내고, 같은 트랜잭션에서 SRM 게이트 `ReturnShipGate.ship` 이 반품을 출고됨으로(교체 요구면 그 LOT 입고를 되돌림). 정정은 원장 역분개 → 게이트 `unship`.

| 항목 | 정한 것 |
|---|---|
| 원장 | 유형 `RTN_SUP`(나감, 역분개만 +). 출처 `SRM_RETURN` = 반품 id(`ck_inv_trx_rtn_src`, FK 없음). LOT 칸은 LOT 관리 품목만(입고·출하와 같은 규칙) |
| 재고 칸 | 반품 공장만. LOT 관리 품목 = 지시 LOT 의 칸, LOT 비관리 품목 = 그 품목의 **LOT 없는** 칸(상위 확인 2026-10-23). 재고 상태 전부 허용(불량 LOT 은 보통 HOLD) — 칸 여러 개, 같은 (칸, 상태) 두 번 = 칸 오류 |
| 보류 LOT | 나간다 — 원장 트리거 `ck_inv_trx_lot_hold` 는 ADJ_OUT·MOVE_OUT·SHIP 만 막는다(반품 대상이 보류 불량 LOT 라서 그대로 둠) |
| LOT 마스터 | **건드리지 않음** — 출하(SHIP)와 같다(원장 트리거가 LOT 행을 FOR SHARE 로 읽을 뿐, 상태·보류·수량 칸 그대로). 사용자 LotService 작업과 겹치지 않음 |
| 수량 | 반품 지시 수량(지시 단위 = NCR 단위 = 발주 행 단위)을 **기본 단위로 바꾼 값과 칸 합이 같아야**(R4-9 나눠 출고 없음) — 다르면 409 `SRM_RTN_QTY_MISMATCH`. 환산 없음·자릿수 넘침은 입고 게이트 오류 그대로 |
| 순서·잠금 | 게이트(반품 FOR UPDATE → 교체 요구면 발주 머리 → 행) → 원장(LOT → 기간 → 재고). 구매 입고·역분개와 같은 방향(SRM 리뷰 1) |
| 교체 요구(R4-3) | 지시의 LOT 를 받은 입고 = `srm_po_receipt.lot_id`(R4-8, 12.13 — 비관리 품목도)로 찾아 `reverseAt(지시 수량)`. 없으면 409 `SRM_RTN_NO_RECEIPT`, 그 LOT **순입고**(입고 역분개·앞선 반품으로 되돌린 것을 뺀)보다 많으면 409 `SRM_RECV_REVERSE`. 결과의 열린 수량을 화면에 "교체품 대기" 로 |
| 멱등 | 요청 id = `client_req_id`, SRM 근거 = `RTN_ISS:{요청 id}`(srm_return.ship_ref, 회사 안 유일). 같은 id·같은 내용 → 처음 결과(게이트 다시 안 부름), 다른 내용 → 409 `DUPLICATE_REQUEST`(PQ-1), 이미 나간 반품을 새 id 로 → 409 `SRM_STATE`. **동시에 두 번**(QR-1, QA #449): 늦은 쪽은 요청 확인을 지나 게이트의 반품 잠금을 기다리다 SHIPPED 를 보고 SRM_STATE — 게이트가 MANDATORY 라 그 트랜잭션은 롤백 전용이므로, 컨트롤러(사무·PDA)가 SRM_STATE·DUPLICATE_REQUEST 를 잡아 새 트랜잭션(`replayIssue`·`replayReturnIssue`)에서 다시 부른다 → 먼저 커밋된 같은 요청 원장으로 처음 결과(내용 다르면 409). 같은 요청 원장이 없으면 같은 오류 그대로 |
| 정정(R4-10) | 수불 원장 화면 역분개 — **한 행을 골라도 그 출고(같은 요청 id 의 RTN_SUP 행) 전체**를 되돌리고, 게이트 `unship(근거 RTN_ISSR:{원장 id}, reversesRef = RTN_ISS:{요청 id})` → 반품 확정으로·`shipped_at`·`ship_ref` 비움. 교체 요구였으면 같은 LOT 로 다시 입고(`receiveAt` + `attachLot`) — 그새 교체품이 들어와 넘치면 409 `SRM_RECV_OVER`, 발주 마감·행 닫힘이면 409 `SRM_RTN_PO_CLOSED`("발주 {번호} 이(가) 마감… 다시 열거나 정리한 뒤 역분개" — 수불 원장 화면 역분개 문구, SRM 리뷰 2026-10-23), 모두 그대로(사람이 정리) |
| SRM 표 | `srm_return.ship_ref` + `ck_srm_return_ship_ref`(출고됨 ⇔ 근거 있음) + 부분 유일 색인, 전이 SHIPPED → CONFIRMED 추가(트리거 함수 CREATE OR REPLACE — 적용된 V147 은 그대로) |
| 화면 | `wms-rtn-issue` 반품 출고(SEARCH·SAVE): 반품 번호 스캔(`RTN:{번호}`) → 지시·후보 칸 → "지시 수량 채우기"(위 칸부터) → 확정 1회. SRM 없는 설치본은 안내만(게이트 `NONE`) |
| 남은 질의(SRM 리뷰 5) | **앱 계정의 직접 UPDATE 로 SHIPPED·CONFIRMED 를 바꾸는 것을 막을지** — 아래 안, 상위 판단 |

**SRM 리뷰 5 — 직접 UPDATE 막기 안**

| 안 | 내용 | 장단 |
|---|---|---|
| (가) 지금 그대로 | `ck_srm_return_ship_ref` 로 근거 없는 SHIPPED 는 막힘. 근거 문자열을 지어 넣는 직접 UPDATE 는 감사 로그(hb_audit)로만 드러남 | 추가 코드 없음. 앱 코드 경로는 게이트 하나뿐 |
| (나) **추천**: 커밋 때 검사(지연 제약 트리거) | 반품 상태가 바뀐 트랜잭션 끝에 `SHIPPED ⇔ 그 반품의 되돌리지 않은 RTN_SUP 원장 행이 있음` | 원장과 반품이 어긋날 길이 DB 에서 닫힘. 대신 SRM 표 트리거가 WMS 원장(inv_trx)을 읽는다(구매 입고 대사와 같은 방향의 의존) — 다음 작은 PR |
| (다) 칸 단위 권한 | status·shipped_at·ship_ref UPDATE 를 앱 계정에서 빼고 SECURITY DEFINER 함수로만 | RLS 우회 금지 규칙(SECURITY DEFINER) 에 걸림 — 안 함 |

**결정·구현(2026-10-23, 상위 승인 — V161·임시 V9314)**: 안 (나). 지연 제약 트리거 `hb_check_srm_return_ledger`(DEFERRABLE INITIALLY DEFERRED) 를 두 곳에 건다.
- `srm_return` 상태가 바뀐 행, `inv_trx` 의 출처 `SRM_RETURN` 행(출고·역분개).
- 커밋 때 반품마다 `상태 = SHIPPED ⇔ 그 반품의 되돌리지 않은 RTN_SUP 행이 있음` 을 본다. 어긋나면 23514 `ck_srm_return_shipped_ledger`.

| 항목 | 정한 것 |
|---|---|
| 근거 | 재고의 원본은 WMS 원장이고 반품 상태는 그 투영이다(구매 입고 recv_qty = 이력 합 커밋 검사와 같은 모양). 앱 코드 경로(게이트)는 하나지만, 앱 계정 SQL 한 줄(근거를 지어 넣은 UPDATE·원장 INSERT)로 "출고됨인데 재고는 그대로" 같은 **조용한 틀림**이 생길 수 있었다 |
| 모듈 결합 | SRM 표 트리거가 WMS 원장을 읽는다. 의도한 것이다 — 반품 출고는 두 모듈이 한 트랜잭션에서 함께 바꾸는 단위다. 읽기만 하고 고치지 않는다. 함수는 이 V 한 곳에 둔다(WMS 마이그레이션 — 원장 쪽 규칙) |
| RLS | 호출자 권한(SECURITY DEFINER 없음) — 같은 회사 행만 보인다. 반품·원장 행은 늘 같은 회사(게이트·원장 트리거가 보장)라 다른 회사 행을 볼 필요가 없다 |
| 비용 | 반품 상태가 바뀔 때와 반품 출처 원장 행을 넣을 때만 한 번씩. 원장 쪽은 `ix_inv_trx_srm_return`, 역분개 확인은 `uq_inv_trx_reversal` 색인 |
| 기각 | (가) 그대로 — 어긋남을 감사 로그로만 뒤늦게 안다. (다) SECURITY DEFINER — 규칙 위반. 대사 배치(재고 대사에 한 줄) — 틀린 뒤에야 찾고 고칠 길이 사람 몫. 즉시(비지연) 트리거 — 게이트가 원장보다 먼저 상태를 바꾸므로(잠금 순서) 문장마다 보면 정상 흐름이 막힌다 |
| 시험 | 게이트만 부르고 원장 없음 → 커밋 거부(출고·되돌림 둘 다), 근거를 지어 넣은 직접 SHIPPED·확정 반품에 직접 넣은 RTN_SUP → 커밋 거부, SRM 상태 변경(취소·확정 등) 시험 그대로 통과(`ReturnClaimSchemaTest`·`ReturnTest`·`ClaimTest`) |

### 12.15 B3 품질 상태 연동 — QMS 경계 합의안 (2026-10-24, 문서만 — QMS 검토 대기, 코드는 합의 뒤)

2부 표 B3 "수입·출하검사 판정 → 재고 상태 STATUS_OUT/IN(같은 트랜잭션 동기 호출), MRB 처분(부분 합격)" 의 상세. 6장 "수입검사 → 가용", D4(상태를 재고 키에), D-B15(출하검사 = 재고 상태, B3 뒤)를 지금 QMS 코드 위에 놓는다.

#### 12.15.1 지금 코드 (조사 2026-10-24)

| 대상 | 사실 |
|---|---|
| QMS 검사 `qms_insp`(V099) | 검사 1건 = **LOT 1개**(`lot_id` 필수) × 유형(RECEIVE·PROCESS·FINAL·FIRST_OFF·LAST_OFF·PATROL) × 공정. OPEN → JUDGED(OK/NG) / VOID(사유). 확정 결과는 무효가 되어도 남는다. 공장 칸 없음 |
| QMS 판정 `InspService.judge` | `lockInsp`(FOR UPDATE) → 행 판정 → `mapper.judge` → `spcEvents.onJudged` — 한 트랜잭션. 무효 `voidInsp` 도 같은 모양(`onVoided`) |
| QMS NCR `qms_ncr`(V112) | LOT 1개(`lot_id` 필수, 근거 검사 `insp_id` 는 확정 검사만). 처분 행 `qms_ncr_disp` = 유형(USE_AS_IS·REWORK·SCRAP·RETURN·SORT) × 수량. 특채(USE_AS_IS)가 있으면 결재로만 종결(승인 = 종결) |
| QMS 봉쇄 `ContainService` | LOT 보류는 MES `LotHoldPort`(`lot_master.hold_yn`) — WMS 는 이미 보류 LOT 의 조정 출고·이동·출하를 막는다(B2·B6a). **B3 와 겹치지 않음**(보류 = LOT 단위 차단, B3 = 수량 단위 상태) |
| WMS 재고 상태 | 키 (품목·위치·LOT·상태), 상태 AVAILABLE·QC·HOLD·BLOCKED. 상태 바꿈 = `STATUS_OUT`(−)/`STATUS_IN`(+) 짝(같은 위치). 피킹·투입은 AVAILABLE(피킹은 "합격분만" 끄면 QC 도), HOLD·BLOCKED 는 항상 제외 |
| 입고 상태 | 자재·구매 입고 = 화면 선택(위치 기본 상태 제안 — QC 구역이면 QC), 생산 입고 RCV_PRD = 위치 기본 상태(없으면 AVAILABLE). **검사 대상인지 보지 않는다** |
| LOT ↔ 원장 | LOT 관리 품목은 원장 `lot_id` = 그 LOT. **비관리 품목은 원장 `lot_id` 가 비어 있다**(B1 규칙) — 입고 LOT 은 있어도(RCV_MAT 마다 내부 LOT) 재고는 품목·위치로 합쳐진다 |

#### 12.15.2 계약 하나 — `module.wms.link.QualityStockLink`

```java
public interface QualityStockLink {
    QualityStockLink NONE = …;            // WMS 없는 설치본·공장 스위치 N → 아무것도 안 함
    void onInspJudged(long inspId);       // 판정 확정 직후(같은 트랜잭션, MANDATORY)
    void onInspVoided(long inspId);       // 확정 검사 무효 직후 — 판정이 바꾼 상태를 되돌림
    void onNcrClosed(long ncrId);         // NCR 종결 직후(직접 종결·특채 결재 승인 둘 다) — 처분 수량 반영
    static QualityStockLink of(ObjectProvider<QualityStockLink> p) { … }
}
```

- 12.4 `LotStockLink` 와 같은 모양: **QMS 는 id 만 넘기고 WMS 가 QMS 표를 읽기만 한다**(`qms_insp`·`qms_ncr`·`qms_ncr_disp` — 유형·결과·LOT·품목·수량·판정 시각). 인자 불일치가 생길 수 없고, QMS 가 WMS 표를 알 필요가 없다.
- 반영 대상은 **RECEIVE·FINAL 만**. PROCESS·FIRST_OFF·LAST_OFF·PATROL 은 재고가 아닌 공정 판단이라 건너뛴다.
- 공장 스위치 `WMS_QUALITY_LINK`(공장 정책, N 기본 — 새 정책 1건): 재고가 있는 공장마다 본다. 켜기 전 쌓인 QC 재고는 이동 화면으로 사람이 정리.
- WMS 의 409(`PERIOD_CLOSED`·`INSUFFICIENT_STOCK`)는 **그대로 QMS 트랜잭션을 깨뜨린다** — 판정·무효·종결이 같이 거부된다(원장과 판정이 어긋날 길이 없음). QMS 는 이 `BusinessException` 을 칸 오류로 바꾸지 말고 그대로 던진다(12.4.5 ③ 과 같은 부탁).

#### 12.15.3 판정 → 재고 상태

| 판정 | 원장 | 수량 |
|---|---|---|
| OK | 그 LOT 의 **QC 재고 전부**를 같은 위치에서 `STATUS_OUT`(QC) / `STATUS_IN`(AVAILABLE) | 판정 순간의 QC 수량(검사 `lot_qty` 가 아님 — 그새 일부가 이동·출고됐어도 남은 만큼) |
| NG | 같은 방식으로 QC → **BLOCKED** | 같음. 이후 처분은 NCR(12.15.5) |
| QC 재고 0 | 아무것도 안 함(로그 `WMS_QC_EMPTY insp=… lot=…`) | 입고 때 QC 를 안 고른 경우 — 이미 AVAILABLE 인 재고를 판정으로 끌어내리지 않는다(D-B26) |

- 출처 `QMS_INSP`·src_id = 검사 id(새 출처 1건 — `ck_inv_trx_*_src` 갱신), 멱등 키 `QMS_INSP:{검사 id}:{n}`, entry_method AUTO, 기록자 = 판정자(`judged_by`), 발생 시각 = 판정 시각(`judged_at`).
- 칸 고르기: 그 LOT 의 QC 칸을 **칸 순서(품목·위치·LOT·상태, 11.6 ③)로 FOR UPDATE** 뒤 지금 수량으로.

#### 12.15.4 무효 → 되돌리기

- `onInspVoided`: 그 검사의 `QMS_INSP` 원장 짝을 `InventoryService.reverseAll` 로 한 번에 — AVAILABLE/BLOCKED → QC. 근거 키 `QMS_INSPR:{검사 id}`.
- 그새 AVAILABLE 로 풀린 수량이 피킹·투입·이동으로 나가 모자라면 **409 `INSUFFICIENT_STOCK` 로 무효가 거부된다**(재고를 먼저 QC 칸으로 돌려놓고 무효). 판정 안 된(OPEN) 검사의 무효는 원장이 없어 아무것도 안 함.
- 같은 LOT 을 다시 검사해 판정하면 그 검사의 QC 재고로 12.15.3 을 다시 한다(새 검사 id = 새 키).

- **처분이 반영된 검사의 무효(WC-2)**: 그 LOT 의 NCR 종결로 BLOCKED 수량 일부가 이미 AVAILABLE(특채)·폐기(SCRAP)로 나갔으면 되돌릴 BLOCKED 가 모자라 **409 `WMS_QUALITY_DISPOSED`**("처분이 반영된 재고가 있어 검사를 무효로 할 수 없습니다 — NCR {번호}") — `INSUFFICIENT_STOCK` 과 갈라 원인을 보여 준다.

#### 12.15.5 NCR 종결 → 처분 수량 (MRB 부분 합격)

NG 판정으로 BLOCKED 가 된 재고에서 처분 행마다(행 순서):

| 처분 | 원장 | 메모 |
|---|---|---|
| USE_AS_IS(특채) | BLOCKED → AVAILABLE, 처분 수량만 | **부분 합격** — 결재 승인 = 종결 때 |
| SCRAP | BLOCKED 에서 처분 수량 출고 — 새 유형 `SCRAP`(−, 3장 원장 유형 "2부 추가" 목록에 있던 이름) | 사유 = NCR 번호. 폐기 위치 이동은 하지 않음(원장에서 빠짐) |
| RETURN | 그대로 BLOCKED — 실제 출고는 SRM 반품(R4-4 `RTN_SUP`, 상태 무관) | 반품 지시와 이중 차감 없음 |
| REWORK | 그대로 BLOCKED — MES LOT 재작업 계보가 `CONV_OUT/IN`(12.4) | 재작업 결과 LOT 은 MES 쪽 |
| SORT | 그대로 BLOCKED — 선별 뒤 USE_AS_IS·SCRAP 을 다른 처분 행으로 | — |

- 출처 `QMS_NCR`·src_id = 처분 행 id, 멱등 키 `QMS_NCR:{NCR id}`(종결 한 번 = 게시 한 번, 구현 때 처분 행 키에서 바꿈). BLOCKED 수량보다 처분 합이 크면 **409 `WMS_QUALITY_SHORT`**("재고: 그 LOT 의 부적합(BLOCKED) 재고 {n} 이 처분 합 {m} 보다 적습니다") 로 종결 거부 — **특채 결재 승인 중**(WB-2)에도 같은 키라 결재자가 "재고" 맥락으로 읽는다(일반 `INSUFFICIENT_STOCK` 문구를 쓰지 않음).
- **단위(WC-1)**: NCR 수량 단위 = LOT 단위(V112 가 LOT 에서 복사) = 만들 때의 품목 기본 단위(V034 트리거) = 원장 단위 — 원장에 쓰인 품목은 기본 단위를 바꿀 수 없다(D-B1). **환산하지 않는다**: NCR `unit_cd` ≠ 품목 기본 단위면(원장 전에 단위가 바뀐 옛 LOT 만 가능) **409 `WMS_QUALITY_UNIT`** — 사람이 이동 화면에서 처리.
- **비관리 품목(D-B27 확장)**: 판정과 같이 **반영하지 않고 로그만**(`WMS_QC_SKIP ncr=… lot=…`) — 409 를 내면 그 NCR 을 영영 종결할 수 없다.
- NCR 이 **확정 검사 없이** 열린 경우(현장 발견)도 같은 규칙 — BLOCKED 재고가 없으면 처분할 것도 없다(USE_AS_IS·SCRAP 이 있는데 BLOCKED 0 이면 409 — 사람이 먼저 이동 화면에서 BLOCKED 로).
- **스위치를 끈 뒤의 종결(B-1, QA 2026-10-24 `docs/qa/2026-10-24_b3-mail-pcn.md` → 상위 결정)**: 공장 스위치는 **새 판정 반영만 끈다**. 그 LOT 의 판정 원장(`QMS_INSP`, 되돌리지 않은 것)이 있는 공장은 스위치가 N 이어도 NCR 종결을 반영한다 — 무효(12.15.4)가 스위치와 무관하게 되돌리는 것과 같은 이유(원장이 판정·처분과 갈라지지 않게). 반영 대상 공장 = 스위치 Y ∪ 판정 반영된 공장(`QualityStockMapper.reflectedPlants` — 판정 방향 행만, 12.15.13). 판정이 반영된 적 없는 LOT(스위치 N 일 때 판정)은 지금처럼 로그만(`WMS_QC_OFF`). 시험 `QualityStockTest#판정_뒤_스위치를_꺼도_NCR_종결은_반영되고_반영_안_된_LOT은_그대로`.
- NCR 취소·종결 되돌리기는 지금 QMS 에 없다(종결은 끝) — 생기면 `onNcrReopened` 를 계약에 더한다(지금은 넣지 않음).

#### 12.15.6 입고 상태 고정 + 출하검사 게이트

| 대상 | 정할 것 |
|---|---|
| 자재·구매 입고(B4a·B4b·ASN) | 그 날 유효한 **RECEIVE 검사계획이 있는 품목**(QMS 기존 계약 `InspPlanLookup.lines(item, null, RECEIVE, 오늘)` 비어 있지 않음)은 공장 스위치 Y 일 때 **입고 상태 = QC 고정**(화면 선택 무시 — 12.3 재고 상태 행 "B3 뒤에는 수입검사 대상 품목이면 QC 고정" 그대로) |
| 생산 입고 RCV_PRD(B1) | FINAL 계획이 있는 품목이면 QC 로(MES 변경 없음 — WMS 게시 `ProductionReceiptPosting` 이 정함) |
| 출하 피킹(D-B15) | 지금 그대로 AVAILABLE 만 — FINAL OK 가 QC → AVAILABLE 로 풀어 줘야 피킹 후보가 된다. "합격분만" 끄기(QC 도 꺼냄)는 스위치 Y 공장에서 **꺼짐 고정** |
| 검사 만들기 | 입고가 검사를 자동으로 만들지는 않는다(지금처럼 QMS 화면에서 LOT 을 골라) — QMS 할 일 목록에 "QC 재고가 있는데 검사가 없는 LOT"(WMS 읽기 계약 `StockQuery` 에 추가) 후보 |

#### 12.15.7 잠금 순서·동시성

```
판정·무효 : qms_insp FOR UPDATE(QMS) → [WMS] lot_master FOR SHARE(lockLots) → 기간 → inv_stock(칸 순서)
NCR 종결  : qms_ncr FOR UPDATE(QMS) → [WMS] 같음
```
- **구현 확인(2026-10-24)**: `QualityStockPosting` 은 칸을 읽기 전에 `InventoryService.lockLotsFirst(lot)` 로 **LOT 공유 잠금을 먼저** 잡고, 그 뒤 칸 FOR UPDATE(칸 순서) → 원장 INSERT(트리거가 lot_master 를 다시 FOR SHARE 로 읽음 — 이미 쥔 잠금) → 기간 공유 → 투영. 무효의 역분개도 `reverseAll` 이 같은 순서(LOT 먼저). QMS 의 NCR 등록 순서 "검사 FOR SHARE → LOT"(QMS 잠금 순서 수정 PR)와 같은 방향 — **검사·NCR(QMS) → LOT → 칸**, 거꾸로 잡는 경로 없음.
- WMS 경로는 QMS 표를 잠그지 않는다(읽기만) → QMS·WMS 사이 순환 없음. 11.6 고정 조건(lot_master 먼저) 그대로.
- 같은 LOT 의 판정과 사무 이동이 동시에: 칸 FOR UPDATE 에서 줄 선다 — 판정이 먼저면 이동은 AVAILABLE 칸에서, 이동이 먼저면 판정은 남은 QC 만(12.15.3 "판정 순간의 수량").
- 같은 검사 판정 두 번: QMS `lockInsp` + 상태 OPEN 검사가 막는다. 멱등 키는 그 위의 이중 안전.

- **동시 시험(상위 지시 2026-10-24, `QualityStockTest#C_판정_반영과_이동이_같은_칸에서_겹쳐도_교착_없음`)**: 같은 LOT·같은 QC 칸을 다른 트랜잭션이 FOR UPDATE 로 쥔 채 "판정 반영"(LOT 공유 → 칸)과 "사람의 이동"(원장 게시 `postAs` 도 LOT 공유 먼저 → 칸)을 놓고, 둘 다 잠금 대기인 것을 확인한 뒤 놓는다 → **교착 없이 둘 다 끝남**(실측: 판정 OK·이동 409 `INSUFFICIENT_STOCK` — 판정이 먼저 QC 를 다 풀었다). 원장 합 = 현재고, 음수 0. LOT 을 FOR UPDATE 로 잡는 MES 경로(보류·해제·계보·입고 트리거)는 `inv_stock` 을 잡지 않는다(production 패키지 참조 0) — 칸은 WMS 계약 안에서만.

#### 12.15.8 QMS 에 부탁하는 것

1. `InspService.judge`·`voidInsp`, NCR 종결 두 곳(직접 `close`·특채 결재 승인 `NcrApprovalDocType`) 끝에서 `QualityStockLink.of(p).onInspJudged / onInspVoided / onNcrClosed` 호출 — 같은 트랜잭션, `spcEvents` 와 같은 자리.
2. WMS 의 `BusinessException`(409)을 그대로 던진다 — 판정 화면은 메시지를 보여 준다("재고가 기간 마감됨", "QC 재고가 이미 나감" 등).
3. 판정 화면 확인 문구(스위치 Y 공장 LOT): "OK = 그 LOT 의 검사 대기 재고 n EA 가 가용으로 풀립니다" — 수량은 WMS 읽기 계약 **`StockQuery.lotStatusQty(lotId)` → `Map<상태, 수량>`**(WB-3 전제, 기본 단위, 스위치 Y 공장 합, 읽기만·예외 없음 — 13장 계약 규칙)으로.

WMS 가 하는 것: 계약·구현 `QualityStockPosting`, 출처 `QMS_INSP`·`QMS_NCR`·유형 `SCRAP`(마이그레이션 1건), 정책 `WMS_QUALITY_LINK`, 입고 QC 고정, 시험(OK·NG·QC 0·무효·무효 때 부족 409·NCR 부분 합격·폐기·마감 409·동시(판정과 이동)·스위치 N·**A사로 B사 검사 id**).

#### 12.15.9 질문 (QMS 검토·사용자 결정) — **QMS 검토 2026-10-24: 부탁 3·D-B26~D-B30 모두 찬성**(docs/29_P3 21장), 덧붙임 D-B27 확장·WC-1·WC-2·WB-2·WB-3 을 12.15.4·12.15.5·12.15.8 에 반영(상위 채택)

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D-B26 | 판정 때 이미 AVAILABLE 인 그 LOT 재고(입고 때 QC 를 안 고름)를 NG 판정이 끌어내릴까 | **안 끌어내림 — QC 재고만** 바꾼다. 입고 QC 고정(12.15.6)이 들어가면 이 경우가 줄어든다. NG 인데 가용 재고가 남은 LOT 은 QMS 봉쇄(LOT 보류)로 막는다 | NG 면 그 LOT 의 AVAILABLE 도 BLOCKED 로 — 확실하지만 이미 피킹된 것과 어긋나고, 판정이 "모르는 재고"를 움직인다 |
| D-B27 | 비관리 품목(원장 `lot_id` 없음)의 검사 판정 | **반영하지 않음**(로그만) — 재고가 LOT 으로 갈리지 않아 "그 LOT 의 QC 수량"을 알 수 없다. 비관리 품목은 입고 QC 고정도 하지 않는다 | 입고 원장(RCV_MAT 출처 LOT)의 위치·수량으로 추정해 바꿈 — 그새 같은 칸에 섞인 다른 입고분까지 풀릴 수 있다 |
| D-B28 | 공장 스위치를 따로 둘까 | **`WMS_QUALITY_LINK` 따로**(N 기본) — 검사 운영이 준비된 공장부터 | `WMS_STOCK_LINK` 하나로 — 투입 차감과 검사 운영의 준비 시점이 다르다 |
| D-B29 | SCRAP 처분의 원장 | **새 유형 `SCRAP`(−)** — 수불 원장에서 폐기를 조정과 갈라 본다 | `ADJ_OUT` + 사유 코드 SCRAP — 마이그레이션 없음, 대신 폐기 집계가 사유 코드에 기댄다 |
| D-B30 | 무효 때 재고가 모자라면 | **무효 거부(409)** — 원장과 판정이 어긋나지 않게 | 무효는 되고 재고는 남은 만큼만 되돌림 + 경고 — 판정 기록과 재고가 갈라짐 |

#### 12.15.10 구현 메모 (2026-10-24, V181·임시 V9317 — QMS 호출 4곳·QMS 시험은 이 PR 병합 뒤 QMS 세션)

| 대상 | 구현 |
|---|---|
| 계약 | `module.wms.link.QualityStockLink`(NONE 포함) — 구현 `wms.inv.QualityStockPosting`(MANDATORY), 매퍼 `QualityStockMapper`(QMS 표 읽기만) |
| 원장 | V181(임시 V9317): 유형 `SCRAP`(−, 출처 `QMS_NCR` 필수 `ck_inv_trx_scrap_src`), 출처 `QMS_INSP`·`QMS_NCR` 은 src_id 필수(`ck_inv_trx_qms_src`), 정책 `WMS_QUALITY_LINK`(공장, N). entry_method AUTO, 기록자 = 판정자·종결자, 발생 시각 = 판정·종결 시각 |
| 판정 | QC 칸(스위치 Y 공장) → LOT 공유 잠금 → 칸 FOR UPDATE → `STATUS_OUT/IN` 짝(같은 위치). 같은 검사 다시 = 원장 있으면 건너뜀 |
| 무효 | 반영된 원장 → `InventoryService.reverseFromSource`(출처 정정용 — 사람 화면은 막힌 행도). 처분 원장이 있는 LOT 이면 먼저 409 `WMS_QUALITY_DISPOSED`(NCR 번호) |
| NCR | 특채·폐기만(RETURN·REWORK·SORT 는 BLOCKED 그대로). 스위치 켜진 공장에 LOT 재고가 없으면 로그만(`WMS_QC_OFF`). 단위 다르면 `WMS_QUALITY_UNIT`, 모자라면 `WMS_QUALITY_SHORT`("재고: …" 문구) |
| 화면 역분개 막기(추가) | 수불 원장 화면의 역분개(`InventoryService.reverse`·`reverseAll`)는 출처 `QMS_INSP`·`QMS_NCR` 행이면 400 `QMS_SOURCE`("QMS 에서 검사를 무효로") — 사람이 되돌리면 QMS 판정·NCR 과 재고가 갈라진다 |
| 읽기 | `StockQuery.lotStatusQty(lotId)`(기본 메서드 = 빈 맵 — NONE 그대로), 스위치 Y 공장 합 |
| 입고 QC 고정 | `QcReceiptRule` — 자재·구매 입고(RECEIVE)·생산 입고(FINAL, 이관 제외)에서 스위치 Y + LOT 관리 + `InspPlanLookup` 계획 있음 → QC |
| 시험 | `QualityStockTest` 9건(정상·이상 사용·A사로 B사·동시) — 검사·NCR 행은 소유자 연결(트리거 끔)로 판정 결과만 넣고 계약을 직접 부른다 |

#### 12.15.11 후속 마무리 (2026-10-24, 상위 지시 — 마이그레이션 없음)

| 항목 | 구현 | 막는 곳 |
|---|---|---|
| ① 미리보기 "합격분만" 끄기 | 스위치 Y 공장에서 `availableOnly = false` 가 오면 `AllocService.preview` 가 400 칸 오류 `availableOnly`·`QUALITY_LINK`(12.15.6 출하 피킹 행 "꺼짐 고정"). 값이 없으면 기본 true 그대로 | **서버** — 화면 선택지는 그대로 두고 오류 문구로 알린다 |
| ② 입고 화면 QC 안내 | `GET /api/wms/rcv/qc-fixed?plantCd&itemId` → `true`/`false` (`MaterialReceiptService.qcFixed` = 저장과 같은 `QcReceiptRule`). 화면은 품목·공장을 바꿀 때 물어 true 면 상태 칸을 QC 로 묶고(비활성) "수입검사 대상 — 검사 대기(QC)로 입고합니다" 안내, false 로 바뀌면 묶기 전에 고른 상태로 되돌린다. 묶인 동안 위치 기본 상태 제안은 덮지 않는다 | 안내만 — 저장 때 서버가 같은 규칙으로 다시 정한다(화면을 거치지 않은 요청도 QC) |
| ③ 수불 원장 화면 QMS 행 | 출처 `QMS_INSP`·`QMS_NCR` 행은 역분개 칸을 숨기고 상태 줄에 "QMS 에서 검사를 무효로" | **서버** 400 `QMS_SOURCE`(12.15.10) — API 직접 호출 시험은 이미 있음(`QualityStockTest#판정_원장_행은_수불_원장_화면에서_되돌릴_수_없다`) |
| ④ QC 재고 LOT 읽기 | `StockQuery.qcLots(plantCd, afterLotId, limit)` → `QcLots(lots, truncated)` — 13장 계약 표. "검사가 없는" 거르기는 **QMS 몫**(WMS 는 QMS 표를 읽지 않는다 — 검사 유무는 QMS 할 일 목록이 자기 표로) | 회사 범위 RLS, 상한 500·lot_id 키셋·한 건 더 읽어 잘림 표시 |

- 시험: `AllocPreviewTest#품질_연동_공장은_합격분만_끄기를_서버가_거부`, `QualityStockTest` +2(입고 안내·QC LOT 읽기) + A사로 B사 확장(같은 공장 코드·스위치 Y 인 B사로 A사 QC LOT 0건). 화면 확인 1366/390(입고 QC 묶임·풀림·저장 QC, 원장 QMS 행 역분개 칸 숨김).

#### 12.15.12 대사 — 품질 연동이 빠진 경로 찾기 (2026-10-24, V184·임시 V9318)

QMS 호출 4곳(판정·무효·NCR 종결)은 QMS 트랜잭션 안의 계약 호출이라 빠지면 재고가 판정과 갈라진다. 매일 대사(`InventoryJobs.reconcile`·화면 FN1)에 두 칸을 더한다 — **세기만, 자동 보정 없음**, 0 보다 크면 기존 대사 알림(아웃박스 NOTIFY)에 함께.

| 칸 | 센 것 | 왜 이 정의 |
|---|---|---|
| `inv_reconcile_run.qc_judged` | 스위치 Y 공장(공장 정책 — `PolicyService` 로 공장마다 판정)에 **QC 재고(LOT, 수량 > 0)가 남았는데** 그 LOT 의 RECEIVE·FINAL 검사가 JUDGED 이고 **OPEN 검사가 없는** LOT 수 | 판정 반영은 "그 순간 QC 전부"를 푼다(12.15.3) — 반영됐다면 QC 가 남을 수 없다. 다시 검사 중(OPEN)인 LOT 은 뺀다. 공정검사(WC-3)·스위치 N 공장은 세지 않는다 |
| `inv_reconcile_run.qms_void_live` | **VOID 검사인데 되돌리지 않은 `QMS_INSP` 원장**이 남은 검사 수 | 무효는 반영 원장을 모두 되돌린다(12.15.4) — 남았으면 무효 호출이 빠진 것 |

- 하지 않은 것: NCR 종결 대사 — 스위치 켜진 공장에 LOT 재고가 없으면 로그만 남기고 원장을 만들지 않는 정상 경로(`WMS_QC_OFF`)가 있어 "처분 원장 없음" 이 빠짐인지 정상인지 표만으로 가를 수 없다. 처분 합 > 부적합은 게시 때 409 로 막는다(`WMS_QUALITY_SHORT`).
- 읽기: `QualityStockMapper.qcPlants/countQcJudged/countVoidLive` — QMS 표는 읽기만(12.15.10 과 같은 방향), 회사 범위 RLS. 고칠 길은 사람 몫: 빠진 판정은 QMS 에서 다시 판정 호출, 빠진 무효는 무효 호출(둘 다 멱등 — 12.15.3·12.15.4).
- 시험: `QualityStockTest#대사는_판정이_빠진_QC_LOT과_무효인데_살아_있는_판정_원장을_세고_고치지_않는다` — 판정만 된 LOT(센다) / 다시 검사 중·스위치 N·공정검사(안 센다) / 반영 뒤 무효만(센다) → 1·1, 알림 1건, 재고 그대로 → 빠진 호출을 다시 부르면 0·0. A사로 B사: B사 대사 0·0.

#### 12.15.13 판정된 LOT 에 더 들어오는 입고 (B-2, 2026-10-24 — QA `docs/qa/2026-10-24_b3-mail-pcn.md` B-2 → WMS·QMS 제안, 상위 승인)

**문제**: 생산 입고는 LOT 당 여러 건(`prd_receipt`, 부분 입고)이다. 판정 뒤 같은 LOT 에 더 들어오면 입고 QC 고정(12.15.6)이 다시 QC 로 넣고, 판정은 "이미 반영"이라 다시 불러도 아무것도 안 해 그 몫이 QC 로 남는다. NG LOT 이면 추가분이 NCR 수량 밖에 있다.

| 안 | 내용 | 판단 |
|---|---|---|
| (가) 모두 판정 결과 상태로 | OK LOT 추가분 → AVAILABLE, NG → BLOCKED | 기각 — 판정은 그 검사 `lot_qty` 표본의 결론이라 추가분을 대표하지 않는다(QMS) |
| (나) 모두 재검사(QC 유지, 지금) | 추가분은 새 검사가 풀 때까지 QC | 기각 — NG LOT 일부가 새 검사 OK 로 풀리는 길이 생긴다 |
| **(다) 비대칭 — 채택** | **OK LOT 추가분 = QC(재검사)**, **NG LOT 추가분 = 입고 때 바로 BLOCKED**(판정을 이어받음) | QMS 찬성(2026-10-24), 상위 승인 |

- **NG 이어받기 조건**: 그 LOT 의 살아 있는(JUDGED) 최신 수입·출하 판정이 NG(`QualityStockMapper.latestResult`). 스위치 Y·LOT 관리 품목에서만(12.15.6 과 같은 문). `QcReceiptRule.status(plant, item, lotId, …)` — 생산 입고(`ProductionReceiptPosting`)가 LOT id 를 넘긴다. 자재·구매 입고는 늘 새 LOT 이라 해당 없음.
- **OK LOT 추가분**: 지금 그대로 QC — 새 검사의 판정(12.15.3)이 그 LOT 의 QC 전부를 푼다.
- **무효 짝**: NG 가 무효로 사라지고 살아 있는 최신 판정이 NG 가 아니면, 그 판정 시각 뒤 BLOCKED 로 들어온 입고 몫(위치별 순합, 남은 BLOCKED 안에서)을 **QC 로 되돌린다**(`QualityStockPosting.releaseInherited`, 출처 `QMS_INSP`·같은 검사 id, 키 `QMS_INSPQ:{id}`). 다른 NG 가 살아 있으면 그대로. 판정 반영이 없던 무효(스위치 N 때 판정)에도 같다.
- **"판정 원장"의 정의를 좁힘**: 무효 때 되돌린 짝(STATUS_IN = QC)도 출처가 `QMS_INSP` 라 — 판정 방향 행 = **STATUS_IN 이 QC 가 아닌 행**만 센다: B-1 의 판정 반영 공장(`reflectedPlants`), V184 대사 `qms_void_live`.
- **대사 `qc_judged` 보정**: OK LOT 의 추가 QC 는 빠짐이 아니므로, 최신 판정 시각 뒤 QC 로 들어온 몫(입고 + 무효 때 되돌린 QC)보다 QC 가 많은 LOT 만 센다.
- **NCR 수량(QMS 몫)**: OPEN NCR 은 QMS 상세가 "BLOCKED n > 부적합 m" 경고, 종결된 NCR 의 추가분은 새 NCR — 재고는 BLOCKED 라 출하가 막혀 안전. QMS 후속: 검사 만들 때 로트 수량 기본값 = QC 수량, BLOCKED 초과 경고.
- 시험: `QualityStockTest#판정된_LOT에_더_들어온_생산_입고는_OK면_재검사_QC_NG면_BLOCKED_무효면_QC로` — OK 추가 5 → QC / NG 추가 4 → BLOCKED 14 / 대사 0·0 / NG 무효 → QC 14·BLOCKED 0·대사 0·0 / 무효 뒤 추가 3 → QC / NG 둘 중 나중 것만 무효 → BLOCKED 그대로.

### 12.16 정위치 점검·위치 정확도 (구현 2026-10-29, V204·임시 V9319 — 설계 docs/55, docs/49 W-3)

- 표 `wms_loc_check`(칸 × 공장 날짜 UNIQUE, OPEN → DONE, 집계·`accurate_yn`) + `wms_loc_check_line`(판정 OK·MISPLACED·MISSING·UNKNOWN, `book` = 판정 당시 장부 jsonb, 처리 `review_*` 는 null 에서 한 번만, 두 표 모두 DELETE 금지 트리거). 실사 표와 섞지 않는다.
- 서비스 `loccheck/LocCheckService` — 줄 추가·완료 모두 머리 `FOR UPDATE` 뒤 상태 확인(둘째 완료·완료 중 줄 추가 409), MISSING 은 완료 트랜잭션 안. 보정 = 줄 `FOR UPDATE` → `InventoryService.postChecked`(MOVE_OUT·MOVE_IN, `src_type = LOC_CHECK`, `src_id = 줄`, `reason_cd = LOC_CORRECT` — 새 코드 그룹 `WMS_MOVE_REASON`), 출발 칸 = 판정 당시 장부 칸 중 하나, 출발·도착 STAGING 막음. 혼적 검사(`mixCheck`)는 하지 않는다 — 물건은 이미 그 칸에 있다(현실을 장부에 맞추는 이동).
- PDA `/api/pop/wh/loc-checks/scan`(위치 → 시작)·`/{id}/lines`(물건)·`/{id}/done`·`GET /{id}` — 온라인만(`pda-loccheck.js`, 대기열 안 씀). 사무 화면 `wms-loc-check`(`/api/wms/loc-checks/grid·columns·{id}·lines/{id}/correct·confirm`). 홈 카드 `wms-loc-accuracy`(docs/37 20.1).

### 12.17 출하 특이사항 (구현 2026-10-29, V208·임시 V9320 — 설계 docs/56, docs/49 W-4)

- 표 `wms_ship_note`(조건 `cust_id`·`item_class_id`·`item_id` 비면 전체 — 품목군·품목 동시 불가, 제목·내용·`ack_required_yn`·`use_yn`, `row_ver` = 규칙 버전, DELETE 금지, 조건 대상 같은 회사 트리거) + `wms_ship_note_ack`(지시 × 규칙 × 버전 UNIQUE, 쌓기만 — UPDATE·DELETE 트리거·REVOKE). 영업 표는 읽지 않는다(고객 = `wms_ship.cust_id`, 품목 = `wms_ship_line.item_id`).
- 판정 SQL 한 곳 `ShipNoteMapper.matchOn` — 상세(`ShipView.notes`)·목록 열(`noteCnt`·`noteOpenCnt`, `ShipMapper.headCols`)·검사가 같이 쓴다.
- 검사: `ShipService.pick`(머리 잠금 뒤, 사무·PDA 공통) — 피킹하는 줄에 확인 필수 미확인 규칙 → 행 `NOTE_ACK_REQUIRED`(400). `confirm` — 피킹된 줄 전체로 다시(피킹 뒤 규칙을 켜거나 글을 고친 경우). 피킹 취소·출하 취소는 막지 않음.
- 확인: `POST /api/wms/ships/{id}/notes/{noteId}/ack`(출하 SAVE), PDA `POST /api/pop/wh/ships/{id}/notes/{noteId}/ack`(배지 작업자 `worker_id`). 열린 지시·걸린 사용 중 규칙만, 같은 버전 두 번 = 처음 기록.
- 화면 `wms-ship-note`(규칙 목록·편집), 출하 상세 "출하 특이사항" 칸 + 줄 배지 + [확인], PDA 피킹 지시 목록·줄 배지·특이사항 [확인](미확인이면 [피킹] 꺼짐).

### 12.18 수불 원장 화면 역분개 — 업무 출처 행 막기 (2026-11-02, QA CR-e 후속 — 마이그레이션 없음)

원칙: **출처 모듈에 정정 경로가 있는 행은 그 경로로만**(사람이 원장만 되돌리면 출처 기록과 갈라지고, 뒤의 출처 정정이 `ALREADY_REVERSED` 로 막힌다). 출처 정정은 `reverseFromSource`·`reverseAt`(fromSource) 로 그대로 된다.

| 출처 | 수불 원장 화면 역분개 | 정정 경로 |
|---|---|---|
| `QMS_INSP`·`QMS_NCR` | 400 `QMS_SOURCE`(12.15) | QMS 검사 무효 |
| `CUST_RTN` | 400 `CUST_RTN_SOURCE`(docs/59 R-6) | 고객 반품 화면 [취소] |
| `SHIP_PICK`(SHIP·피킹 이동 짝) | 400 `SHIP_SOURCE`(CR-e) | 출하 화면 [고른 피킹 취소]·[출하 취소](`reverseAll(List, String)` — 출하 모듈 경로) |
| `PRD_RECEIPT`·`LOT_GEN` | 400 `MES_SOURCE` | MES 입고 정정(`ProductionReceiptPosting`)·POP 실적 정정(`LotStockPosting.onReverse`) |
| `MAT_RECEIPT`·`SRM_RETURN`·`MANUAL` | 됨 — 원장 화면이 정정 경로(게이트 함께, 12.3.4·12.14) | — |
| `CMS_PART` 출고 `ISSUE_MRO` | 400 `CMS_ISSUE_SOURCE`(**W-10**, 2026-11-02 사용자 결정) | CMS 보전 작업지시 반납(MRO_RTN) |
| `CMS_PART` 반납 `MRO_RTN` | 됨 — CMS 에 반납 취소 계약이 없어 원장 화면이 유일한 되돌림(W-10). 계약이 생기면 막는다(docs/29 미완 #53) | 원장 화면 |
| `COUNT`(COUNT_ADJ·OPENING)·`LOC_CHECK`(보정 MOVE 짝) | 됨 — **정책(W-11, 2026-11-02 사용자 결정): 원장 화면이 정정 경로**. 실사는 확정(POSTED) 뒤, 정위치 점검은 보정 뒤 자체 취소가 없어 정확히 되돌리는 유일한 길 | 원장 화면 |

시험: `CustRtnTest#결함CRe_*`, `ReceiptPostingTest#입고_원장은_원장_화면에서_되돌리지_못하고_MES_정정은_된다`, `PopStockLinkTest#투입_차감_원장은_원장_화면에서_되돌리지_못하고_POP_정정은_된다`, `QualityStockTest#판정_원장_행은_*`, `MroIssuePostingTest#출고_원장은_원장_화면에서_되돌리지_못하고_반납과_반납_역분개는_된다`.

### 12.19 W-12 적치 검사 — 위치 ↔ 허용 품목군 (2026-11-02 사용자 결정, V230·임시 V9325)

결정(docs/49 W-12, 추천안 그대로): 정책 `WMS_PUTAWAY_CHECK`(공장) = OFF / **WARN(기본)** / BLOCK, 허용 목록이 없는 위치는 전부 허용, 입고·이동·PDA·반품 받는 칸 **전부 같은 판정 함수 하나**, 위치 화면에 허용 품목군 칸. 하위 위치 상속은 아래 추천안 — **사용자 확인 2026-11-02**(WMS 세션에 직접 "하위 위치 상속 추천안대로 진행"). 품목군 없는 품목 = 위반은 W-12b 사용자 결정 2026-11-02.

| 항목 | 설계 |
|---|---|
| 표 | `wms_loc_class_allow`(노드 × 품목군, `use_yn`·`row_ver`·감사 칸, RLS, 같은 회사·창고/로케이션 노드만 트리거, 삭제 금지 — 빼기 = `use_yn = 'N'`, 보존 MASTER) |
| 실효 목록(상속) | 그 노드에 사용 중 행이 **있으면 그것만**, 없으면 **가장 가까운 상위 노드의 목록**, 어디에도 없으면 전부 허용. 하위가 직접 목록을 두면 상위 목록을 **대신**한다(합치지 않음 — 창고는 원자재, 그 안의 한 칸만 완제품 같은 예외를 표현) |
| 위반 | 품목의 품목군이 실효 목록에 없음. **품목군이 없는 품목도 위반**(목록 = "이 품목군만") — **W-12b 사용자 결정 2026-11-02(추천안)**: WARN 이면 경고, BLOCK 이면 409 |
| 판정 | `PutawayCheck.check(공장, 노드, 품목)` 하나 — OFF = 안 봄, WARN = 저장 + 응답 `warnings`(문구), BLOCK = 409 `WMS_PUTAWAY_DENIED`(위치·품목·품목군·허용 목록), 원장·LOT·반품·NCR 모두 롤백 |
| 경로 | 사무 입고·PDA 입고(`MaterialReceiptService` 공통 본체), 사무 이동·PDA 이동(`StockService.move` — **위치를 바꾸는 이동만**, 같은 위치 상태 바꿈은 둘 곳이 그대로라 보지 않음), 고객 반품 받는 칸(`CustRtnService.receive`) |
| 보지 않는 경로 | 조정 입고(ADJ_IN — 이미 그 칸에 있는 수량 정정), 실사 조정, 정위치 점검 보정(장부를 실물에 맞춤 — 막으면 실물과 장부가 갈라짐), 생산 입고(MES 입고 가능 위치 계약 `ReceiptLocQuery` 가 따로), 출하 피킹(STAGING 으로 나감), QMS 처분 상태 이동(같은 칸) |
| 응답 | `RcvResult.warnings`·`PostResult.warnings`(이동)·PDA `MoveResult.warnings`·`Rtn.warnings` — 없으면 빈 목록. 화면(입고·이동·고객 반품·PDA)은 저장 문구 뒤에 경고를 붙여 오류 톤으로 |
| 위치 화면 | `wms-loc` 상세에 "적치 허용 품목군" 체크 목록 + [허용 품목군 저장](SAVE) — 직접 목록 / "상위 {코드} 의 목록을 따름" / "목록 없음 — 전부 허용" 안내. API `GET /api/wms/loc/classes`, `GET·PUT /api/wms/loc/{nodeId}/allow` |
| 버린 대안 | (가) 하위 목록 = 상위와 합집합 — 예외 칸을 "더 좁게" 만들 수 없음. (나) 상속 없음(노드마다 목록) — 창고 하나에 칸 수백 개를 일일이 설정. (다) 품목군 없는 품목은 통과 — 분류 안 한 품목이 아무 칸에나 들어가 검사가 구멍 |

시험: `PutawayCheckTest`(4 — 위치 화면 직접·상속·없음·빼기, 사무 입고 WARN/BLOCK/OFF, 사무 이동·PDA 입고·PDA 이동·같은 위치 상태 바꿈, A사로 B사·직접 SQL), `CustRtnTest#반품_받는_칸도_적치_검사_*`.

### 12.20 데모 이력 시더 — 입고 9·이동·출하·반품·정위치 점검·실사 (2026-11-03 다시 씀 — docs/61 10장 "데모" 결정, 마이그레이션 없음)

처음 쓴 절(같은 번호)은 브랜치 정리로 코드와 함께 사라졌다(docs/29 2026-11-03 표). 재고 현황판(C3 — 재고 연령 구간 누적 막대 0~30·31~90·91~180·180+, 정위치 정확도, 상태별 구성)과 창고 화면이 비어 있지 않게 다시 만들었다.

| 항목 | 설계 |
|---|---|
| 흐름 | `WmsDemoFlow`(module.wms) — 서비스 길만, local·demo 프로필의 `WmsDemoSeeder` `@Order(DemoSeedOrder.WMS)` — MES·QMS 뒤(출하·반품이 MES 완제품 입고 재고를 쓴다), SRM 앞(SRM 입고가 먼저 생기면 이 시더가 건너뜀) |
| 자재 입고 9 | `MaterialReceiptService.receive` — 입고 화면처럼 발생 시각(공장 현지)을 받는 길. 200·130·75·60·45·21·12·5·1일 전, 코일 3종·용접 너트·CO2 와이어(공급사 P001·P003·P007), RM-A01-01/02. 5일 전 코일은 QC 상태. LOT 의 `occurred_at` 도 그날이라 **연령 구간 넷이 모두 생긴다**(완제품은 MES 입고가 지난 90일) |
| 이동 3 | `StockService.move`(과거 시각): 코일 1,700 KG·너트 5,000 EA 라인사이드(BODY-LS) 불출, 아연도금 코일 2,000 KG 같은 칸 가용 → 보류(표면 녹) — 상태 구성에 보류가 보인다 |
| 출하 3 | `ShipService.create → pick → confirm`: 확정 1(S001 수주 행 600, 완제품 가용·보류 아닌 LOT 을 오래된 것부터), 피킹 중 1, 작성 1. **피킹·확정은 지금 시각만**(서비스가 now) |
| 고객 반품 1 | `CustRtnService.receive`: 확정 출하의 첫 LOT 12개 품질 반품 → 보류 재고 + QMS 클레임 NCR(`NcrPort`, docs/29_P3 38장). 지금 시각 |
| 정위치 점검 1 | `LocCheckService.start → scan → done`(오늘 — 점검 머리는 오늘만 받는다): RM-A01-02 에서 열연 코일 LOT(일치)·와이어 품목(일치)·너트 LOT(장부는 RM-A01-01 → 다른 칸) 스캔, 아연도금 코일 안 스캔 → 없음. 다른 칸은 정정(장부 이동), 없음은 확인(사유) |
| 순환 실사 1 | `CountService.create → start → enter → submit → post`(지금): RM-A01-01, 센 수량 = 장부, 너트 한 LOT 만 −60(허용 5% 안 — 승인 불필요) → 실사 조정 원장 1 |
| 건너뜀 | 자재 입고(RCV_MAT·RCV_PO)·출하·실사·정위치 점검·고객 반품이 하나라도 있으면 0. 위치 5개(RM-A01-01·02, BODY-LS, FG-A01, FG-B01)·데모 품목·공급사가 없으면 0. 출하·반품은 완제품 가용 LOT 4개 이상과 S001 열린 수주 행이 있을 때만. 한 트랜잭션 |
| 알려진 한계 | 출하·반품·정위치 점검·실사는 서비스가 지금 시각만 받아 **오늘 하루치**다 — 정위치 정확도 "추세"는 점 하나. 과거로 넣으려면 점검 머리(오늘만)·실사 시작 시각 규칙을 바꿔야 해 범위 밖(필요하면 시더 전용 길을 따로 결정) |
| 버린 대안 | (가) 재고 원장(inv_trx) 직접 INSERT — 재고 칸·LOT·마감 트리거와 화면 규칙을 건너뛴다. (나) `BizClock.setClock` 으로 시계 돌리기 — 앱 전체 시계라 기동 중 다른 요청까지 과거가 되고, DB now() 는 안 바뀐다 |

시험: `DemoHistoryFlowTest`(자재 입고 9·연령 구간 넷·과거 이동 4행·QC/보류 재고, 출하 SHIPPED·PICKING·DRAFT, 반품 1·클레임 NCR 1, 정위치 점검 일치 2·다른 칸 1·없음, 실사 확정 1·조정 1, 멱등, A사로 B사).

## 13. 다른 모듈에 내주는 계약 (읽기, 2026-10-21)

다른 모듈은 `inv_stock`·`inv_trx` 를 직접 조회하지 않고 `module.wms.link` 계약만 쓴다. 모양은 PLM 계약(docs/39 16장)과 같다 — 인터페이스 + 구현 빈 + `NONE` + `of(ObjectProvider)`, WMS 가 없는 설치본은 `NONE`.

| 계약 | 쓰는 곳 | 규칙 |
|---|---|---|
| `StockQuery.available(plantCd, itemIds)` → `Map<품목 id, 수량>` | APS 5단계 자재 가용 | 품목 기본 단위(원장은 기본 단위만 — D-B1 로 단위가 바뀌지 않는다). 그 공장 **모든 위치 합**. 재고 상태 `AVAILABLE` 만, 보류 LOT(`lot_master.hold_yn = 'Y'`) 제외, 위치 용도 `SCRAP` 인 노드와 **그 아래 노드** 제외, **용도 `STAGING`·`QC` 인 칸(칸 자신) 제외(2026-10-29 docs/53 F2·F-a — 가용 정의 하나 `AvailSql`, 출하 피킹·생산 투입·MRO·차감 미리보기와 같은 정의)**. 요청한 품목 id 는 모두 키로 돌려준다(재고 없음·다른 공장·다른 회사 = 0, null·중복은 뺌). 호출자의 회사 범위에서 읽는다(RLS) |
| `StockQuery.reservedByShip(plantCd, itemIds)` → `Map<품목 id, 수량>` | 출하 예약 읽기(docs/53 (가), 2026-10-29) — APS 가 "가용 − 출하 예약" 을 원하면 씀(판단은 APS) | 품목 기본 단위. 그 공장 열린 출하 지시(DRAFT·PICKING) 줄의 **아직 피킹 안 한 수량**(지시 − 피킹 PICKED·SHIPPED, 0 아래면 0) 합. 막지 않는 숫자(새 표 없음 — 피킹·피킹 취소·출하·지시 취소가 곧 해제). 요청 품목 id 모두 키(없으면 0), 다른 공장·다른 회사 0, **WMS 없음(NONE) = 0** |
| `StockQuery.lotStatusQty(lotId)` → `Map<상태, 수량>` (B3, 12.15.8) | QMS 판정 화면 확인 문구 | 스위치 Y 공장의 그 LOT 상태별 합(0 인 상태 없음), 기본 단위. 없는 LOT·다른 회사·비관리 = 빈 맵 |
| `StockQuery.qcLots(plantCd, afterLotId, limit)` → `QcLots(lots, truncated)` (B3 후속, 12.15.11) | QMS 할 일 "QC 재고가 있는데 검사가 없는 LOT" 후보 | 스위치 Y 공장의 QC 재고 합 > 0 인 LOT(lot_id·lot_no·품목·QC 수량). lot_id 오름차순, `afterLotId` 다음부터 `limit`(1~500, 범위 밖은 끝값)건, 더 있으면 `truncated` — 마지막 lot_id 로 이어 부른다. 공장 없음·스위치 N·WMS 없음 = 빈 목록. RLS |
| `ReceiptLocQuery.productionReceipt(plantCd, itemId, lotId)` → `List<ReceiptLoc(nodeId, nodeCd, path, status, reasonCd)>` (docs/44 S2, 11.8 Q5 — 2026-10-26) | MES 생산 입고 화면 위치 목록 | 이 공장 WH·LOC 전부(경로 순), 받을 수 없는 칸은 사유 `NODE_INACTIVE`·`STOCK_NOT_ALLOWED`(원장 트리거와 같은 칸 규칙)·`STAGING_NOT_ALLOWED`(출하 대기 칸 — docs/53 F1, 2026-10-27). 사유는 실시간 입고와 **같은 메서드** `ProductionReceiptPosting.nodeReason`. `status` = 저장과 **같은 메서드** `ProductionReceiptPosting.status`(위치 기본 상태 → QC 고정·NG LOT 이어받기). MANDATORY(호출자 트랜잭션)·RLS·없는 공장·품목 = 빈 목록, WMS 없음 = `NONE`. LOT 보류는 보지 않음(MES 가 먼저 막음). 시험 `ReceiptLocQueryTest` — 목록의 모든 칸에 실제 게시해 "받음 ⇔ 성공, 상태 같음" |
| `ShipQuery.shippedByLine(orderLineIds)` → `Map<수주 행 id, 수량>` (B6a, 12.7) | 영업 출하 수량 투영 대사·화면 경고 | 수주 행별 SHIP 원장 합의 부호 반대(역분개 포함 — 취소한 출하는 빠짐), 기본 단위. 요청한 행 id 는 모두 키(출하 없음·다른 회사 = 0). 잠그지 않음 |
| `ShipQuery.byLots(lotIds)` → `List<LotShipment>` (docs/57, 2026-10-30) | QMS 리콜·클레임 추적(LOT → 고객·출하) | LOT × 출하 지시 한 줄: 고객·출하 번호·확정 시각·순 수량(SHIP 합의 부호 반대, 역분개 포함 — 출하 취소분 빠짐, 0 은 없음)·팔레트 번호·SSCC. LOT 500 상한(넘으면 IllegalArgumentException), 중복·null 무시, 다른 회사 빠짐, 잠그지 않음. 길: 피킹 lot_id(`ix_wms_ship_pick_lot`, V215·임시 V9321) → 원장 출처 색인 |
| `ShipQuery.lotStock(lotIds)` → `List<LotStockRow>` (docs/57) | 같은 화면 "창고에 남은 것" | LOT × 공장 × 칸 × 상태 수량 > 0 — **가용 정의를 쓰지 않음**(QC·HOLD·BLOCKED·출하 대기·검사·폐기 칸 그대로, `locUsage`). 입력 규칙은 byLots 와 같음 |

- **잠그지 않는다**: 그 순간 커밋된 현재고 투영을 읽는다. APS 는 계획 숫자로만 쓰고, 실제 차감은 원장(`CHECK (qty >= 0)`)이 막는다 — 계약이 "확보"를 약속하지 않는다.
- 하지 않은 것: 할당·예약(차감 전략 W4 는 미리보기만), 위치·LOT 별 내역(필요해지면 계약에 메서드 추가), QC 상태 재고를 "곧 가용"으로 세기(B3 품질 상태 연동 뒤 판단).

