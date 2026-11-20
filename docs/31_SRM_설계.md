# 31. P5 SRM 설계 — 1부: 구매요청·발주·납기 회신·협력사 평가 (P2·WMS 와 병행)

작성 2026-10-20. 8장 결정 11건은 **추천안 그대로 채택**(2026-10-20 사용자, 10장).
근거: docs/00 2장 업무 흐름(`[구매/SRM] PR → PO → 납품 → [QMS] 수입검사 → 가용재고`)·4장 모듈 구성(`sh-srm` 협력사·PR/PO·납품·협력사 평가(리비전 시트))·6장 로드맵 P5 행, 01 5.4(결재 `ApprovalDocType`)·5.8(아웃박스·멱등), 02 2.3 표(SQ·5스타: 평가 항목·점수·과락을 설정 가능한 평가 템플릿)·스마트공장 수준 표(중간1 "협력사 정보 공유(발주·납품)"), 06 3.2(Pro = SRM 또는 PMS 택1)·M1(비치헤드 = 2·3차 협력사 — **우리 고객도 누군가의 협력사이고, 동시에 자기 협력사를 관리한다**), 07 3.1(구매 입고는 MES → ERP, "발주는 ERP → MES(조회)", 협력사 평가는 MES 측)·3.2(실패 처리)·5.6(정책 `ERP_WO_SOURCE` 모양), 17 2장(P1 공통 규칙), 22(외부 시스템·`mst_ext_id_map`·ERP 잠금 `ERP_ITEM_SYNC_MODE`), 24 체크리스트 A~K, 25(P2 단계·경계·공통 규칙), 27 M12(공급사 원산지·지배 주체 국가)·M15(보안 취약 협력사 진단), 28(대기업 솔루션 비교 — ERP 내장 안 함, 모듈러), 29(1부·2부 나누기·병행 규칙 6장).
P0 플랫폼 규약(01)·P1 공통 규칙(17 2장)·P2 공통 규칙(25 3장)은 그대로 따른다 — 이 문서는 **SRM 에서 새로 정하는 것**만 적는다.

---

## 1. 결론 먼저

- SRM 1부는 **"무엇을 누구에게 언제까지 사는지 정하고, 협력사가 약속하게 하고, 협력사를 점수로 본다"** 까지다. 데모 흐름: 협력사 정보(인증서 만료 배지) → 구매요청(PR, 부서 승인) → 발주(PO, PR 행 묶기·쪼개기) → **결재**(P0 `ApprovalDocType` `SRM_PO`) → 발행(발주서 엑셀·인쇄, 아웃박스로 협력사 통지) → 납기 회신(분할 납기, 재회신 이력) → "늦어질 발주" 목록 → 협력사 평가(템플릿 리비전 · 점수 · 등급 · 과락).
- **입고·수입검사·납품 LOT·대금은 2부**(WMS·QMS 2부 뒤). 1부 테이블에는 2부가 붙을 **자리**(발주 행 열린 수량 판정 함수, 상태 코드, 평가 자동 항목 키)만 둔다 — 29 의 원칙과 같다.
- **ERP 와의 위치는 정책 하나(`SRM_PO_SOURCE` = SRM / ERP)로 가른다**(8장 D1). ERP 가 없거나 구매가 약한 고객은 SRM 이 발주의 주인, ERP 에 구매가 있는 고객은 ERP 발주를 **받아서** 회신·납품·평가만 한다. 대금·전표·원가는 어느 쪽이든 ERP(SRM 은 계산하지 않음).
- 협력사는 **새 마스터를 만들지 않는다** — P1 거래처(`mst_cust`, 구분 PURCHASE·BOTH)를 쓰고, SRM 전용 칸은 1:1 확장 `srm_supplier` 에 둔다(품목 ERP 잠금처럼 ERP 주인 칸을 건드리지 않게).
- 협력사 포털(외부 사용자 로그인)은 1부에서 하지 않는다(8장 D4) — 회신은 **내부 대리 입력 + 엑셀 회신 올리기**. 인증 경계(회사 사용자 vs 협력사 사용자)를 새로 여는 일이라 따로 설계한다.

## 2. 범위와 단계

**1부·2부 나누기** (29 0장과 같은 이유 — 반은 남의 결과를 키로 쓴다)

| | 1부 (이 문서, 지금) | 2부 (WMS 입고·QMS 2부 수입검사 병합 뒤) |
|---|---|---|
| 기대는 것 | P1 거래처·품목·단위·창고(`mst_equip_hier` WH)·외부 ID + P0 결재·아웃박스·채번·첨부·엑셀 올리기 | P4 입고 원장, P3 2부 수입검사 판정, P2 자재 LOT(`lot_master.supplier_lot_no`) |
| 범위 | 협력사 정보·인증서·공급 품목·단가, PR, PO(결재·개정·취소), 납기 회신, 발주서 엑셀·인쇄, 통지(아웃박스), 평가 템플릿·평가 실행(수동 항목) | 사전 납품 통지(ASN)·납품 LOT·라벨, 입고 반영(열린 수량·완납 마감), 수입검사 불합격 → 반품·클레임, 평가 자동 항목(납기 준수율·입고 PPM), 매입 확정·ERP 송신 |
| 원칙 | 2부가 1부 테이블을 고치지 않게 키·상태·판정 함수를 미리 둔다 | 1부 판정 함수(`PoOpenQty`, `EvalScorer`)를 그대로 부른다 |

순서 원칙(17·25 와 같음): **참조되는 것 먼저**, 매 단계가 화면·API·테스트(정상 + 이상 사용 A~K + A사로 B사 + 직접 SQL)까지 끝난 상태로 커밋, 끝나면 7장 표에 ✅·날짜·테스트 수.

| 단계 | 범위 | 첫 화면 | 완료 기준 |
|---|---|---|---|
| **S1** | **협력사 정보**: `srm_supplier`(거래처 1:1 확장 — 구매 담당·결제 조건·기본 통화·국가·지배 주체 국가(27 M12)·거래 상태 ACTIVE/HOLD/BLOCKED·보안 진단일(27 M15)), 인증서 `srm_supplier_cert`(IATF 16949·ISO 9001·ISO 14001·SQ 등 회사 코드, 번호·만료일·첨부, 만료 판정 `CertStatus`), 공급 품목 `srm_source`(협력사 × 품목: 협력사 품번·리드타임·최소 발주·발주 배수·우선순위·기간), 단가 `srm_price`(협력사 × 품목 × 통화 × 적용 기간, 기간 겹침 배제 제약) | 협력사 정보 | 판매 전용 거래처는 협력사로 못 등록, 거래 정지(BLOCKED) 협력사는 새 발주 차단(기존 발주는 그대로), 단가 기간 겹침 거부, 인증서 만료 경계(오늘 = 만료일), ERP 주인 칸(거래처 기본정보)은 SRM 에서 안 바뀜 |
| **S2** | **구매요청 PR**: `srm_pr`(번호 채번·요청자·부서·공장·필요일·사유, 상태 DRAFT → REQUESTED → APPROVED / REJECTED → CLOSED, 취소) + `srm_pr_line`(품목·수량·단위·필요일·희망 협력사, 행 취소). 승인은 APPROVE 권한(결재 아님, 8장 D5) | 구매요청 | 승인 뒤 수정 불가(트리거 55000), 요청자 본인 승인 금지(정책), 사용 중지 품목·단위 새 참조 차단, 같은 요청 연타 → 1건 |
| **S3** | **발주 PO**: `srm_po`(번호 채번·협력사·공장·납품 창고·통화·결제 조건 스냅샷·개정 번호, 상태 8장 D6) + `srm_po_line`(품목·협력사 품번 스냅샷·수량·단위·단가·금액·요구 납기·PR 행 연결 선택). **PR → PO 전환**(여러 PR 행 → 한 PO, 한 PR 행 → 여러 PO, 남은 수량 초과 금지), 단가 기본값 = 기준일 유효 `srm_price`, **결재 `SRM_PO`**(정책 `SRM_PO_APPROVAL`, 금액 기준), 발행 뒤 변경 = 개정(rev + 1, 발행본 스냅샷 `srm_po_rev`), 취소(사유 필수) | 발주 관리 | 결재 중·발행본 직접 수정 불가(직접 SQL 포함), 반려·회수 → 작성 중으로, 같은 PR 행을 두 사용자가 동시에 전환 → 하나만 성공(합계 ≤ PR 수량), 개정 전후 비교, 금액 = Σ(수량 × 단가) 반올림 규칙 손 검산 |
| **S4** | **발주서·통지**: 발주서 엑셀(서식 고정 POI, 회사 로고·협력사 품번·개정 표시) + 인쇄용 화면(브라우저 인쇄 → PDF, 8장 D7), 발행 = 아웃박스 `SRM.PO_ISSUED`(1부 송신기 = 로그 + 가짜 메일함, 8장 D8) + 통지 이력 화면 | 발주 관리(발행·출력 버튼) · 통지 이력 | 같은 발행 두 번 → 아웃박스 1건(멱등), 송신 실패가 발행 트랜잭션을 깨지 않음, 개정 발행본에 "개정 n" 표시, 엑셀 수식 주입 방지(협력사 품번·비고) |
| **S5** | **납기 회신**: `srm_po_ack`(회신 묶음: 발주 × 개정 × 회신 순번, 출처 MANUAL / IMPORT, 회신자·회신일) + `srm_po_ack_line`(발주 행 × 약속 납기 × 약속 수량 — 분할 납기), 재회신 = 새 묶음(이전 그대로, **최신 묶음이 유효**), 회신 거절(사유), 엑셀 회신 올리기(`ImportTemplate`), "늦어질 발주"·"회신 안 온 발주"(정책 `SRM_ACK_DUE_DAYS`) 목록 | 납기 회신 · 발주 진행 현황 | 약속 수량 합 ≠ 발주 수량이면 경고(초과는 거부), 개정 전 회신은 "이전 개정 회신" 표시·유효 아님, 취소된 발주 회신 거부, 회신 엑셀의 발주 번호가 다른 회사·다른 협력사면 행 오류 |
| **S6** | **협력사 평가**: 템플릿 `srm_eval_tmpl`(리비전 — BOM 규칙: DRAFT → RELEASED → OBSOLETE, 기간) + 항목 `srm_eval_item`(구역·항목·배점·가중치·과락 점수·입력 방식 MANUAL / AUTO(지표 키)) + 등급 구간 `srm_eval_grade`, 평가 실행 `srm_eval`(협력사 × 기간 × 템플릿 리비전, 상태 DRAFT → CONFIRMED, 확정 = APPROVE 권한) + 점수 `srm_eval_score`, 점수 계산 `EvalScorer`(순수 함수), 평가 결과 → 협력사 거래 상태 제안(D 등급 → HOLD 제안, 사람이 확정) | 평가 템플릿 · 협력사 평가 | 확정 템플릿 불변·기간 겹침 거부, 과락 항목 하나라도 → 등급 무관 "과락", 가중치 합 100 아니면 확정 거부, 확정 평가 불변, 같은 협력사·같은 기간·같은 템플릿 중복 평가 거부, AUTO 항목은 1부에서 "2부 대기" 로 수동 입력 허용 |

## 3. SRM 공통 규칙

| 규칙 | 내용 | 근거 |
|---|---|---|
| 문서 상태 | PR·PO·평가는 **상태 전이 표 하나**(서비스 + 트리거 55000). 화면은 허용된 다음 동작만 버튼으로. 작성 중(DRAFT)만 편집, 그 밖의 머리·행 UPDATE 는 트리거가 막는다(상태 전이 칸·`row_ver` 만 예외) | 25 3장, 00 결함 5 |
| 개정 | 발행된 발주를 바꾸면 **개정**: 발행 때마다 머리·행 스냅샷을 `srm_po_rev`(jsonb, 추가만)에 남기고, 행 자체는 rev + 1 로 다시 작성 중. 협력사에게 간 것은 언제나 스냅샷으로 설명된다. 회신은 개정 번호를 들고 있다 | 평가시트 리비전 방식, 18 리비전 규칙 |
| 스냅샷 | 발주 행에 **협력사 품번·품목명·단위·단가**를 저장 때 복사(나중에 마스터가 바뀌어도 발행본은 그대로). 결제 조건·통화는 머리에 | 25 3단계(지시 스냅샷) |
| 수량·금액 | 수량 `numeric` + 품목 단위 소수 자릿수(UNIT `attrs.scale`), 단가 `numeric(18,4)`, 금액 = 행마다 `round(수량 × 단가, 통화 자릿수)`(KRW 0, USD 2 — 새 시스템 코드 `CURRENCY` 의 `attrs.scale`, UNIT 과 같은 방식), 머리 합계 = 행 금액 합(행마다 반올림 뒤 합 — 발주서 표기와 같게). 서버 BigDecimal, Double 금지 | 17 2장, SKILL 7b NUM |
| 열린 수량 | `PoOpenQty.of(poLineId)` = 발주 수량 − 입고 수량(2부) − 취소 수량. **1부는 입고 0 으로 계산**하고 2부 WMS 입고가 같은 함수를 쓴다. PR 남은 수량 = PR 수량 − Σ(유효 PO 행 수량) | 29 판정 함수 |
| 동시성 | PR → PO 전환은 PR 행 `SELECT … FOR UPDATE` 후 남은 수량 확인, PO 저장은 머리 `row_ver`(디테일 일괄 저장 규칙), 결재 상신·발행·취소는 `X-Idempotency-Key`/`claimRequestKey` | P0 8단계 |
| ERP 경계 | `SRM_PO_SOURCE=ERP` 이면 발주 머리·행의 ERP 주인 칸(협력사·품목·수량·단가·요구 납기)은 화면·API·엑셀에서 `ERP_OWNED`, 신규 발주 409 — **회신·통지·평가는 그대로 SRM**. ERP 발주 번호는 `mst_ext_id_map` 대상 유형 `PO` 추가로 1:1 — 대상 유형은 CHECK `ck_mst_ext_id_map_entity` + 소속 확인 트리거 분기라 새 V 파일에서 둘 다 넓힌다 | 22 7a, 07 3.1 |
| 외부 송신 | 협력사 통지·ERP 송신은 아웃박스(트랜잭션 안 외부 호출 금지). 메일·알림 채널은 `retryOnReadTimeout=false` | 01 5.8 |
| 권한 | 화면별 `@ProgAuth`. 결재 처리는 결재선. **단가·금액은 발주 화면 SEARCH 권한에 포함**(별도 액션 없음 — 8장 D3 참고), 엑셀 내보내기 열은 `ColumnSpec` 화이트리스트 | P0 4·8단계 |
| 기준정보 | `srm_supplier`·인증서·공급 품목·단가·평가 템플릿은 P1 기준정보 공통 규칙(회사별 RLS·`row_ver`·`tg_audit`·사용 중지 = 새 참조만 차단·앱 DELETE 회수) | 17 2장 |
| 보존 | V007 에 거래 기록 클래스가 없다(MASTER·QUALITY_RECORD·로그류뿐). **1부는 PR·PO·회신 = `MASTER`, 평가·인증서 = `QUALITY_RECORD`**(IATF 8.4.2.4 공급자 모니터링 기록). 거래 기록 전용 클래스(상법 보존 기간)는 P2 수주·P4 와 같이 정한다 | 02 2.6 |
| 정책 | `SRM_PO_SOURCE`(SRM/ERP, 기본 SRM), `SRM_PR_REQUIRED`(Y/N, 기본 N — PR 없이 발주 허용), `SRM_PR_SELF_APPROVE`(N), `SRM_PO_APPROVAL`(Y/N, 기본 Y), `SRM_PO_APPROVAL_MIN_AMT`(기본 0 = 전부 결재), `SRM_ACK_DUE_DAYS`(회신 기한, 기본 2), `SRM_CERT_DUE_SOON_DAYS`(인증서 만료 임박, 기본 60) | 01 5.6 |
| 채번 | 규칙 `SRM_PR`(`PR{yyMM}{seq:4}`), `SRM_PO`(`PO{yyMM}{seq:4}`), `SRM_EVAL` — `NumberingService` 업무 트랜잭션 안(결번 없음) | P0 5단계 |
| i18n·화면 | 문구 키 `srm.*` / `js.srm.*`, 도움말 `help.srm-*.{field}`, 화면 코드 `srm-*` | 29 6장 |
| 테스트 | 25 3장 그대로 + **금액 손 검산**(통화 자릿수·반올림) + **A사로 B사**(B사 PR 행을 A사 PO 로 전환 시도, B사 협력사로 발주, 회신 엑셀에 B사 발주 번호) | 24, CLAUDE.md |

## 4. 단계별 데이터 모델 (요약 — 단계 착수 때 상세를 이 문서 해당 절에 추가)

```text
── S1 협력사 정보 ─────────────────────────────────────────
srm_supplier         (comp, cust_id PK 겸 FK(mst_cust, cust_type PURCHASE/BOTH 만 — 트리거), buyer_user_id nullable(구매 담당),
                      pay_terms_cd(회사 코드 PAY_TERMS), currency_cd(기본 통화), country_cd, control_country_cd(지배 주체 국가, 27 M12),
                      trade_status ACTIVE/HOLD/BLOCKED, status_reason, sec_assessed_on(보안 진단일, 27 M15), remark, row_ver)
                     ← 거래처 기본정보(명·사업자번호·주소)는 mst_cust 가 주인 — 여기 복사하지 않는다
srm_supplier_cert    (comp, cust_id, cert_type_cd(회사 코드 SUPPLIER_CERT: IATF16949/ISO9001/ISO14001/SQ/…), cert_no, issuer_nm,
                      issued_on, expires_on, use_yn)                ← 갱신은 새 행(이력), CertStatus = VALID/DUE_SOON/EXPIRED/NONE
                                                                       첨부는 sys_file(target srm-supplier / cert-{id}) — file_id 칸 없음
srm_source           (comp, cust_id, item_id, supplier_item_cd, supplier_item_nm, lead_days, moq, order_mult, priority, use_yn)
                                                                     ← 유일 (comp, cust_id, item_id) — 기간 없음(구현 때 단순화)
srm_price            (comp, cust_id, item_id, currency_cd, unit_price numeric(18,4), valid_from, valid_to, remark, use_yn)
                                                                     ← 품목 기본 단위당. EXCLUDE (comp, cust, item, currency, daterange) WHERE use_yn='Y'

── S2 구매요청 ───────────────────────────────────────────
srm_pr               (comp, pr_no 채번, plant_cd, req_user_id, dept_nm, need_date, reason, status DRAFT/REQUESTED/APPROVED/REJECTED/CLOSED/CANCELLED,
                      decided_by/at(승인·반려), reject_reason, cancel_reason, row_ver)
srm_pr_line          (comp, pr_id, line_no, item_id, qty, unit_cd, need_date, pref_cust_id nullable, remark,
                      line_status OPEN/CANCELLED/CLOSED)            ← 남은 수량은 저장하지 않고 PO 행에서 계산(3장)

── S3 발주 ──────────────────────────────────────────────
srm_po               (comp, po_no 채번, rev int, cust_id, plant_cd, wh_id(납품 창고, mst_equip_hier WH), currency_cd, pay_terms_cd(스냅샷),
                      po_date, buyer_user_id, status(8장 D6), apr_request_id nullable, issued_at, cancel_reason,
                      total_amt(행 합 — 저장 때 계산, 트리거가 일치 확인), source SRM/ERP, row_ver)
srm_po_line          (comp, po_id, line_no, item_id, supplier_item_cd(스냅샷), item_nm(스냅샷), qty, unit_cd, unit_price, amt,
                      due_date, pr_line_id nullable, cancel_qty default 0, line_status OPEN/CANCELLED/CLOSED, remark)
                     유일 (po_id, line_no). 같은 회사 PR 행인지 트리거(RLS 는 FK 를 막지 않는다 — SKILL 2장)
srm_po_rev           (comp, po_id, rev, issued_at, issued_by, snapshot jsonb(머리 + 행), + created_*)   ← 추가만, UPDATE/DELETE 차단

── S4 통지 ──────────────────────────────────────────────
(새 테이블 없음) sys_outbox topic SRM_PO_ISSUED / SRM_PO_CANCELLED, biz_key = PO:id:rev
(srm_notify_log 는 만들지 않음 — 통지 이력은 sys_outbox 를 읽는다, 4.4)

── S5 납기 회신 ──────────────────────────────────────────
srm_po_ack           (comp, po_id, rev, ack_seq, source MANUAL/IMPORT, ack_status ACCEPTED/PARTIAL/REJECTED, reject_reason,
                      ack_by_nm(협력사 담당), ack_at, recorded_by)     ← 추가만(재회신 = 새 ack_seq), 최신 ack_seq 가 유효
srm_po_ack_line      (comp, ack_id, po_line_id, promised_date, promised_qty, remark)   ← 한 행에 분할 납기 여러 줄

── S6 협력사 평가 ─────────────────────────────────────────
srm_eval_tmpl        (comp, tmpl_cd, rev, tmpl_nm, status DRAFT/RELEASED/OBSOLETE, valid_from, valid_to, row_ver)   ← BOM 리비전 규칙
srm_eval_item        (comp, tmpl_id, item_no, section_nm, item_nm, max_score, weight, fail_below nullable(과락), input_type MANUAL/AUTO,
                      metric_cd nullable(AUTO: OTD_RATE 납기 준수율 / RECV_PPM 입고 불량 PPM / CERT_VALID / ACK_RATE 회신율), guide)
srm_eval_grade       (comp, tmpl_id, grade_cd A/B/C/D, min_score)
srm_eval             (comp, eval_no 채번, cust_id, tmpl_id, period_from, period_to, status DRAFT/CONFIRMED, total_score, grade_cd, fail_yn,
                      confirmed_by/at, suggest_trade_status nullable, row_ver)
                     유일 (comp, cust_id, tmpl_id, period_from, period_to) WHERE status <> 'CANCELLED'
srm_eval_score       (comp, eval_id, item_id, raw_value nullable(AUTO 지표 원값), score, comment, auto_yn)
```

### 4.1 S1 상세 — 협력사 정보 (V043)

| 대상 | 규칙 |
|---|---|
| 등록 | 구매·겸용(PURCHASE·BOTH) 거래처만(`NOT_SUPPLIER_SRM`), 사용 중지 거래처는 새 등록 불가(`INACTIVE`), 한 번만(`DUPLICATE`). 판매 전용 거래처는 목록·상세 모두 안 보인다(404). 트리거 `ck_srm_supplier_cust_type`·`fk_srm_supplier_cust_comp` |
| 거래 상태 | ACTIVE / HOLD / BLOCKED, ACTIVE 가 아니면 사유 필수(서비스 `REQUIRED` + CHECK `ck_srm_supplier_reason` — 공백만도 거부). BLOCKED 의 발주 차단은 S3 |
| 코드 칸 | 통화(`CURRENCY`, 시스템 — `attrs.scale` 금액 자릿수)·결제 조건(`PAY_TERMS`, 회사 확장 가능)은 **바꿀 때만** 사용 중 코드인지 확인(사용 중지된 코드를 들고 있는 행도 다른 칸 저장 가능). 국가·지배 주체 국가는 ISO 2자리, 보안 진단일은 미래 불가 |
| 구매 담당 | 같은 회사 로그인 계정(아이디 입력 → id), 다른 회사 계정은 트리거 `fk_srm_supplier_buyer_comp`. 새로 고르거나 바꿀 때는 사용 중 계정만(관리자 잠금·사용 중지 → `buyerLoginId:INACTIVE`, 로그인 실패 자동 해제 잠금은 사용 중으로 본다). 그대로 둔 기존 담당은 그 뒤 사용 중지돼도 다른 칸 저장을 막지 않는다(2026-10-24, QA U-1) |
| 인증서 | 유형·발행일 고정(갱신 = 새 행), 번호·기관·만료일·사용만 수정, 미래 발행 불가. **현재 인증서 = 유형마다 사용 중 행 중 발행일이 가장 늦은 행**. 협력사 판정 = 현재 인증서가 없으면 NONE, 있으면 그중 가장 이른 만료일로 `CertStatus`(만료일 당일까지 유효, 남은 날 ≤ 정책 `SRM_CERT_DUE_SOON_DAYS`(기본 60) 이면 DUE_SOON) |
| 공급 품목 | 협력사 × 품목 한 행, 품목 고정. 최소 발주·배수는 품목 기본 단위의 소수 자릿수(UNIT `attrs.scale`, EA = 0) 안에서. 사용 중지 품목은 새 등록·다시 사용(N→Y) 불가(`fk_srm_source_item_active`) |
| 단가 | 품목 기본 단위당, 단가·통화·시작일·품목 고정(`ck_srm_price_fixed`), 수정은 끝일·사용·비고만. **끝 없는 새 단가**를 넣으면 같은 키의 열린 이전 단가(시작일이 더 이른)를 새 시작일 전날로 마감, **끝 있는 한시 단가**는 이전 단가를 자르지 않는다 → 겹치면 `PRICE_OVERLAP`(배제 제약 `ex_srm_price_period`, 동시 추가도 하나만). 금액 자릿수 numeric(18,4), 응답은 `trim_scale` 글자 |
| 기준일 단가 | `SourcePriceService.effectivePrice(협력사, 품목, 통화, 날짜)` — S3 발주 행 단가 기본값 |

화면 `srm-supplier`(메뉴 "구매"): 구매 거래처 표(등록 여부·거래 상태·인증 판정·공급 품목 수) + 상세(거래처 기본정보 읽기 + SRM 칸, 미등록이면 [협력사로 등록]) + 인증서(행별 파일·사용 중지) + 공급 품목(행 고르면 수정) + 단가(행 고르면 끝일·사용만).

### 4.2 S2 상세 — 구매요청 (V044)

| 대상 | 규칙 |
|---|---|
| 번호 | `SrmNumbers.next(SRM_PR)` — 회사에 규칙 행이 없으면 기본 규칙(`PR{yyMM}{seq:4}`, 매월)을 같은 트랜잭션에서 만들고 받는다. `NumberingService.next` 의 예외를 잡아 다시 시도하지 않는다(트랜잭션 프록시를 지난 예외는 바깥 트랜잭션을 rollback-only 로 만든다) |
| 한 벌 저장 | `POST`(신규) / `PUT /{id}`(머리 + 행 배열, `rowVer`) — 행 id 가 있으면 수정(바뀐 행만 UPDATE), 없으면 추가(남은 행 다음 번호, 작성 중엔 지운 번호 재사용 가능), 빠진 기존 행은 삭제. 다른 요청의 행 id 는 400. 머리 `row_ver` 증가로 동시 편집 409. 행 200개 상한 |
| 행 검사 | 행 오류는 **행 키 = 보낸 배열 순번**. 새 행·품목을 바꾼 행은 사용 중 품목만, 그대로 둔 행은 사용 중지 품목이어도 저장(새 참조만 차단). 단위 = 품목 기본 단위 스냅샷(그대로 둔 행은 저장 때 단위 유지), 수량은 그 단위 소수 자릿수. 희망 협력사는 등록된 협력사 코드, 거래 정지면 `SUPPLIER_BLOCKED` |
| 요청 검사 | 행 1개 이상(`LINES_REQUIRED`), 필요일(머리 또는 행 — 행이 비면 머리)은 필수·오늘 이후(`PAST`), 사용 중지 품목 행이 있으면 `INACTIVE` — 작성 중에는 강제하지 않고 **요청할 때** 본다(docs/25 3장 "닫을 때 필수") |
| 상태 전이 | DRAFT → REQUESTED·CANCELLED / REQUESTED → DRAFT(회수)·APPROVED·REJECTED·CANCELLED / REJECTED → DRAFT(다시 작성) / APPROVED → CLOSED·CANCELLED. 서비스는 머리 `FOR UPDATE` + 상태·`rowVer` 확인(다르면 `SRM_STATE` / 409), 트리거 `ck_srm_pr_transition` 이 최종. 작성 중이 아니면 내용 칸 고정 `ck_srm_pr_draft`, 행은 `ck_srm_pr_line_draft`(예외: 행 상태 OPEN → CLOSED·CANCELLED — S3 발주 연결용) |
| 승인 | 요청·회수·다시 작성·취소 = SAVE, 승인·반려·마감 = APPROVE. 요청자 본인 승인은 정책 `SRM_PR_SELF_APPROVE`(기본 N → `SRM_SELF_APPROVE` 409). 반려·취소 사유 필수(CHECK 도). 결정자·시각은 `decided_by/at` |
| 조회 | 요청일 기간 필수(`DateRanges`, 화면 상한 366일 + 정책), 번호·사유·품목 코드/이름 검색 |

화면 `srm-pr`: 기간(`hb.dateRange`)·상태·검색어 → 요청 표 → 상세(머리 폼 + 품목 행 입력 표 + 허용된 다음 동작 버튼만 + 반려·취소 사유 칸). 저장하지 않은 변경이 있으면 상태 동작을 막는다.

### 4.3 S3 상세 — 발주·결재·개정 (V045)

| 대상 | 규칙 |
|---|---|
| 머리 | 협력사(등록·거래 정지 아님)·통화(협력사 기본 통화)·발주일(오늘)·번호는 저장 뒤 고정(`ck_srm_po_fixed`). 결제 조건은 비우면 협력사 기본값 스냅샷. 납품 창고는 같은 공장의 WH 노드(코드), 다른 공장·다른 회사는 `fk_srm_po_wh_plant` |
| 행 | 한 벌 저장(구매요청과 같은 규칙, 행 키 = 배열 순번). 품명·협력사 품번(공급 품목)·단위 스냅샷. **단가를 비우면 발주일 기준 단가표**(`SourcePriceService.effectivePrice`), 없으면 `NO_PRICE`. 금액 = `round(수량 × 단가, 통화 자릿수)`(HALF_UP, 트리거 `ck_srm_po_line_amt` 가 직접 SQL 도 확인), 합계 = 행 금액 합. 리드타임·최소 발주·배수 위반은 **경고 표시만**(`leadShort`·`moqShort`·`multOff`) |
| PR 연결 | 승인된 요청의 열린 행만(`PR_NOT_OPEN`), 같은 품목·단위(`PR_ITEM_MISMATCH`), 남은 수량 = PR 수량 − 살아 있는(취소 아닌) 발주 행 합(이 발주의 기존 행은 빼고) — 같은 발주에서 같은 PR 행 여러 줄이면 합으로(`REMAIN_EXCEEDED`, 인자 = 남은 수량). **PR 행을 번호 순으로 `FOR UPDATE`(+ 요청 머리 `FOR SHARE`) 잠근 뒤 남은 수량은 다른 문장으로 계산** — 같은 문장에 집계를 넣으면 기다린 뒤에도 옛 스냅샷이라 동시에 둘 다 통과한다(테스트로 발견). 정책 `SRM_PR_REQUIRED=Y` 면 연결 필수. 살아 있는 발주 행이 걸린 승인 요청은 취소 불가(`SRM_PR_HAS_PO`, 트리거 `ck_srm_pr_has_po`) |
| 결재 | 결재 필요 = 정책 `SRM_PO_APPROVAL`(Y) 이고 (원화가 아니거나 합계 ≥ `SRM_PO_APPROVAL_MIN_AMT`). 필요하면 `submit`(결재자 로그인 아이디 순서대로 APPROVE 줄, 본인 금지는 P0 정책) → IN_APPROVAL, 아니면 `confirm`(APPROVE 권한) → APPROVED(필요한데 확정하면 `SRM_APPROVAL_REQUIRED`). `ApprovalDocType` `SRM_PO`(bizKey = `발주id:개정`): 상신 직전 작성 중·같은 개정인지, 끝나면 같은 트랜잭션에서 승인 → APPROVED, 반려·회수 → DRAFT. **핸들러는 PoService 를 주입하지 않는다**(ApprovalService 가 생성자에서 핸들러를 모아 순환). 결재 중엔 수정·취소·확정 불가 |
| 확정·상신 때 필수 | 행 1개 이상, 요구 납기 ≥ 오늘(`PAST`), 사용 중 품목, 협력사 거래 정지 아님(`SUPPLIER_BLOCKED` — 정지된 뒤의 기존 작성 중 발주도 진행 불가) |
| 발행·개정 | 발행 = APPROVED → ISSUED + 발행본 스냅샷 `srm_po_rev`(머리 + 행 jsonb, 앱 UPDATE·DELETE 권한 없음 + 트리거). 개정 = ISSUED·ACKED → DRAFT 로 **rev + 1**(트리거 `ck_srm_po_rev_step` 이 다른 rev 변화 금지), APPROVED(발행 전) → DRAFT 는 rev 그대로("다시 작성"). 취소는 DRAFT·APPROVED·ISSUED·ACKED 에서 사유 필수, 마감은 ISSUED·ACKED(APPROVE) |
| ERP 주인(D1) | 정책 `SRM_PO_SOURCE=ERP` 면 발주 만들기·행 수정·개정이 `SRM_ERP_OWNED`(409). 취소·마감·(S5) 회신은 가능. ERP 발주를 엑셀로 받는 템플릿은 S3b(남은 것) |

화면 `srm-po`: 기간·상태·검색어 → 발주 표 → 상세(협력사 코드 datalist·공장·창고·결제 조건 + 품목 행 작은 폼 + "구매요청에서 가져오기"(남은 수량으로 행 추가) + 결재 상신 칸(결재 필요 여부 안내) / 결재선(내 차례면 승인·반려, 내가 상신했으면 회수 — 판정은 결재 API) + 발행 이력).

### 4.4 S4 상세 — 발주서·통지 (V046)

| 대상 | 규칙 |
|---|---|
| 통지 | 발행(개정 포함) → 토픽 `SRM_PO_ISSUED`, **발행된(ISSUED·ACKED) 발주의 취소** → `SRM_PO_CANCELLED`(발행 전 취소는 협력사가 모르는 발주라 통지 없음). bizKey = `PO:id:개정`. 토픽 이름은 대문자·밑줄만(`ck_sys_outbox_topic` — 설계 초안의 `SRM.PO_ISSUED` 는 안 됨). 송신기는 DB 를 못 쓰므로 **본문에 메일 재료**(발주 번호·개정·협력사·받는 주소·품목 수·합계·첫 납기·취소 사유)를 싣는다 |
| 받는 주소 | 사용 중 주 담당자(`mst_cust_contact.main_yn`) 메일, 없으면 거래처 메일. 없으면 송신기가 `FAIL NO_RECIPIENT`(재시도해도 같음) |
| 채널(D8) | 로그 + **가짜 메일함** `SrmMailbox`(회사별 최근 100통, 메모리, messageId 로 중복 거름) — 실제 SMTP 는 메일 공통 채널 결정 때 같은 토픽에 송신기를 바꿔 붙인다. 읽기 타임아웃은 재시도 안 함. 메일함 순서는 릴레이 순서라 보장하지 않는다 |
| 통지 이력 | 새 테이블 없이 `sys_outbox`(RLS) 의 두 토픽을 읽는다(설계 초안의 `srm_notify_log` 는 만들지 않음). 받는 주소는 `a***@도메인` 으로 가림(목록·메일함 모두). **다시 보내기** = FAIL 건만, 아웃박스 행을 되살리지 않고 **지금 발주 상태·지금 주소로 새 메시지**(발행 통지는 ISSUED·ACKED 일 때, 취소 통지는 CANCELLED 일 때 — 아니면 `SRM_STATE`) |
| 발주서 자료 | `GET /{id}/document[?rev=]` — 기본은 마지막 **발행본 스냅샷**(협력사에게 간 그대로), 발행 전·개정 중이면 지금 내용을 같은 모양으로 + `draft`. 없는 개정 404. 발주 화면 PRINT 권한(V046 에서 actions 추가) |
| 엑셀 | 서식 고정 POI 시트(제목·발주처·협력사·머리 칸·품목 표·합계), 숫자는 글자 그대로(반올림·지수 없음), 글자 칸은 `ExcelExportService.safeText`(=+-@ 앞 `'`), 파일 이름 `발주번호_rev{n}[_draft].xlsx` |
| 인쇄 | 인쇄 전용 화면 `srm-po-print`(`?poId=&rev=`, 메뉴 없음 — 발주 화면 [발주서 인쇄]가 새 창으로) → 브라우저 인쇄 → PDF. 공통 CSS 를 고치지 않으려고 인쇄 때 도구 막대를 `hidden` 으로 숨기고 `afterprint` 에 되돌린다 |

### 4.5 S5 상세 — 납기 회신 (V062)

| 대상 | 규칙 |
|---|---|
| 회신 묶음 | `srm_po_ack`(발주 × 개정 × 회신 순번, 출처 MANUAL / IMPORT, 상태 ACCEPTED / PARTIAL / REJECTED, 회신자 이름·회신일·비고, 엑셀이면 올리기 작업 id). **추가만** — 재회신 = 새 순번(발주 머리 `FOR UPDATE` 뒤 max + 1, 유일 `(comp, po, seq)`). 고칠 수 있는 것은 **만든 트랜잭션 안의 상태 칸 하나**(행을 다 넣은 뒤 상태를 계산해 적는다 — 엑셀 1,000행 묶음이 한 발주를 나눠도 같은 묶음) |
| 회신 행 | `srm_po_ack_line`(묶음 × 발주 행 × 약속 납기 × 약속 수량 — 한 발주 행에 분할 납기 여러 줄, 같은 날 두 줄 금지). **묶음과 같은 트랜잭션에서만** 추가(머리 `created_at = now()` 확인, docs/25 V033 수법), 그 뒤 수정·삭제 없음. 거절 묶음은 행 없음 |
| 넣을 수 있는 때 | 발주가 ISSUED·ACKED 이고 묶음 개정 = 발주 지금 개정(트리거). 취소·마감·작성 중(개정 중) 발주는 거부(`SRM_STATE`). 회신일은 미래 불가·발주일 이후(`SRM_ACK_BEFORE_PO`), 약속 납기도 발주일 이후 |
| 수량 | 약속 수량은 발주 단위 자릿수. 발주 행마다 Σ 약속 ≤ 열린 수량(= 수량 − 취소 수량, 2부에서 − 입고) — 넘으면 거부(`SRM_ACK_OVER`, 트리거 `ck_srm_po_ack_line_over` 도), **모자라면 경고**(`SHORT`)와 상태 PARTIAL. 요구 납기보다 늦은 약속은 경고(`LATE`) — 저장 응답 `warnings[{lineNo, kind}]` |
| 상태 | 거절(사유 필수, CHECK) = REJECTED. 아니면 열린 행 전부가 열린 수량만큼 약속되면 ACCEPTED, 그 밖 PARTIAL(약속 없는 행 포함) |
| 유효 회신 | **발주 지금 개정의 가장 큰 순번 묶음**. 이전 개정 묶음은 "이전 개정 회신" 으로 보이기만 하고 유효 아님(개정하면 다시 회신 대기) |
| 발주 상태 | 유효 회신이 수락·부분이면 ISSUED → ACKED, 재회신이 거절이면 ACKED → ISSUED(전이 표에 추가). 이 두 전이는 **같은 트랜잭션에 새 회신 묶음이 있어야** 한다(트리거 — 직접 SQL 로 상태만 ACKED 로 바꾸는 우회 차단) |
| 엑셀 회신 | 템플릿 `SRM_PO_ACK`(화면 `srm-ack`): 협력사 코드·발주 번호·개정(비우면 지금)·행 번호·약속 납기·약속 수량·회신자·비고. 키 = 협력사\|발주\|행\|납기, **파일의 발주마다 새 묶음 하나**(출처 IMPORT). 행 오류: 없는 발주(다른 회사 발주는 RLS 로 안 보여 같은 오류), **발주의 협력사가 파일 협력사와 다름**(`SRM_OTHER_SUPPLIER`), 회신할 수 없는 상태, 이전 개정(`SRM_OLD_REV`), 없는 행, 파일 안 합이 열린 수량 초과. 실행 때 발주 상태·개정을 다시 확인(검증과 실행 사이 개정·취소 → 전체 롤백 `SRM_STATE`) |
| 회신 기한 | 정책 `SRM_ACK_DUE_DAYS`(기본 2, 0~60) — 발행일(회사 시간대) + 일수가 지났는데 유효 회신이 없으면 "회신 안 옴" |
| 진행 현황 | `srm-po-status`(조회 전용, 발주일 기간 필수, ISSUED·ACKED 의 열린 행): 보기 ALL / NO_ACK(기한 지난 미회신 + 거절) / LATE(약속 마지막 날 > 요구 납기) / DUE_SOON(요구 납기 ≤ 오늘 + n일, 기본 7). 행 = 발주 행, 칸 = 회신 상태·기한·지연 일수·약속 첫·끝 날짜·약속 합 |
| ERP 주인(D1) | `SRM_PO_SOURCE=ERP` 여도 회신은 SRM 에서(막지 않는다) |

화면 `srm-ack`: 기간·회신 상태·검색어 → 발주 표(유효 회신 상태·순번) → 상세(발주 행별 열린 수량·요구 납기 + 분할 줄 입력(날짜·수량, 줄 추가) + 회신자·회신일 + [회신 저장] / [거절](사유) + 회신 이력(순번·개정·출처·상태, 이전 개정 표시, 고르면 그 묶음 행) + 엑셀 회신 올리기.

### 4.6 S6 상세 — 협력사 평가 (V068)

| 대상 | 규칙 |
|---|---|
| 템플릿 리비전 | `srm_eval_tmpl`(코드 × 리비전, DRAFT → RELEASED → OBSOLETE, 기간). BOM 규칙: **작성 중만 편집**, 코드마다 작성 중 하나(부분 유일 → `SRM_EVAL_DRAFT_EXISTS`), 확정본끼리 기간 겹침은 배제 제약(`SRM_EVAL_OVERLAP`), 확정본은 이름·기간 시작 불변 — 끝 없는 기간을 한 번 마감하는 것만 허용. 새 리비전 = 마지막 리비전 복사(시작일 오늘), 확정할 때 **끝 없는 이전 확정본 중 시작이 더 이른 것을 새 시작일 전날로 마감**(단가표 규칙과 같음) |
| 항목·등급 | `srm_eval_item`(구역·항목·배점·가중치(0 < w ≤ 100)·과락 점수(0 < f ≤ 배점)·입력 MANUAL / AUTO + 지표 키) + `srm_eval_grade`(등급 코드·최소 총점·HOLD 제안). 작성 중 템플릿은 항목·등급을 **한 벌로 통째 교체**(외부 참조가 없는 작성 중이라 지우고 다시 — 확정본은 트리거가 막음). 행 오류 키 = 항목 배열 순번, 등급은 `g` + 순번 |
| 확정 조건 | 항목 1+ · **가중치 합 100**(`SRM_EVAL_WEIGHT`) · **최소 총점 0 인 등급**(`SRM_EVAL_GRADE0` — 모든 총점에 등급). APPROVE 권한. 트리거가 같은 조건을 다시 본다(직접 SQL) |
| 평가 | `srm_eval`(번호 `SRM_EVAL` `EV{yyyy}{seq:4}`, 협력사 × 기간 × **기간 끝 날에 유효한 확정 리비전**). 기간 ≤ 366일, 끝 날 ≤ 오늘(`SRM_EVAL_FUTURE`), 같은 협력사·기간·템플릿 리비전 중복은 부분 유일(취소 제외, `SRM_EVAL_DUP`). 거래 정지 협력사도 평가할 수 있다. 만들 때 항목마다 점수 행 + AUTO 계산 |
| 점수 | 0 ≤ 점수 ≤ 배점(소수 2자리), 작성 중만. AUTO 로 계산된 행은 점수를 바꿀 수 없고(`SRM_EVAL_AUTO`) 의견만, [다시 계산]으로 갱신. 계산할 수 없는 AUTO(근거 없음·2부 지표)는 사람이 넣는다 |
| AUTO 1부 | `ACK_RATE` = 기간에 발행한 **발행본(개정마다 한 번)** 중 회신일 ≤ 발행일 + `SRM_ACK_DUE_DAYS` 인 회신(거절도 응답)이 있는 비율 → 점수 = 배점 × 비율(2자리 HALF_UP), 발행 0건이면 사람 입력. `CERT_VALID` = 기간 끝 날 현재 인증서(유형마다 사용 중·그날까지 발행된 최신)가 있고 가장 이른 만료 ≥ 그날(`CertStatus`, 당일까지 유효) → 배점, 아니면 0. `OTD_RATE`·`RECV_PPM` = "2부 대기" → 2부에서 계산(`OTD_RATE` R2 4.10, `RECV_PPM` R4 4.12.3) |
| 계산 `EvalScorer` | 순수 함수: 총점 = Σ(점수 × 가중치 / 배점)(항목마다 20자리) → 2자리 HALF_UP, 과락 = 과락 점수 미만 항목 하나라도(같으면 통과), 등급 = 최소 총점 ≤ 총점인 가장 높은 구간(경계 포함), HOLD 제안 = 과락 또는 그 등급의 HOLD. 확정 때 트리거(`ck_srm_eval_total`)가 SQL 로 다시 계산해 서비스 값과 다르면 거부 |
| 확정·취소 | 확정(APPROVE) = 모든 항목 점수 필수(행 오류 키 = 항목 순서) → 결과 저장 → **불변**(머리·점수 트리거). 취소 = 작성 중만, 사유 필수. 확정 평가를 고칠 길은 없다(감사 기록 — 새 기간 평가로) |
| 거래 상태(D11) | 평가는 `suggest_trade_status = HOLD` 만 남긴다 — 협력사 거래 상태는 바꾸지 않음(사람이 협력사 정보에서) |
| 보존 | 템플릿 = MASTER, 평가·점수 = QUALITY_RECORD(IATF 8.4.2.4) |

화면 `srm-eval-tmpl`: 상태·검색어 → 템플릿 표(코드·리비전·기간·항목 수·가중치 합) → 상세(머리 + 항목 작은 폼 + 등급 + 가중치 합 표시) + [확정]·[새 리비전]·[폐기]. 화면 `srm-eval`: 기간(평가 기간 끝)·상태 → 평가 표(결과·HOLD 제안 표시) → 신규(협력사·템플릿 코드·기간) / 상세(항목 점수·자동 근거·미리보기 총점·등급·과락) + [점수 저장]·[다시 계산]·[확정]·[취소].

### 4.7 S3b 상세 — ERP 발주 받기 (V089, 임시 V9502)

| 대상 | 규칙 |
|---|---|
| 받는 조건 | 정책 `SRM_PO_SOURCE=ERP` 인 회사만(아니면 모든 행 `SRM_NOT_ERP_SOURCE`). 검증 뒤 정책이 SRM 으로 바뀌면 실행 전체 롤백(`SRM_STATE`) — 검증 때 본 정책도 실행 때 다시 본다(P1 7a 교훈) |
| 템플릿 `SRM_ERP_PO` | 발주 화면(`srm-po` SAVE)의 [ERP 발주 받기]. 열: ERP 시스템(외부 시스템, 유형 ERP·사용 중)·ERP 발주 번호(대소문자 그대로)·행 번호(1~9999, SRM 행 번호로 그대로)·협력사·공장·창고·발주일(≤ 오늘)·통화(비우면 협력사 기본)·품목·수량(단위 자릿수)·단가(소수 4자리)·요구 납기(≥ 발주일)·비고. 키 = 시스템 \| ERP 번호 \| 행 |
| 코드 해석 | 협력사·품목은 **내부 코드 먼저, 없으면 그 ERP 시스템의 외부 ID 매핑**(CUST·ITEM, 대소문자 구분). 거래 정지 협력사·사용 중지 품목은 행 오류 |
| 머리 일치 | 같은 ERP 번호의 행은 협력사·공장·창고·발주일·통화가 같아야(`SRM_ERP_HEAD_MISMATCH`) |
| 멱등 | ERP 번호 ↔ SRM 발주 = `mst_ext_id_map` 대상 유형 **PO**(시스템 안 1:1 부분 유일). 같은 번호를 다시 받으면: 덮어쓰기 모드 = 같은 행은 SAME(반영 없음), 추가만 모드 = `EXISTS`, **내용이 다르거나 새 행이면 `SRM_ERP_PO_CHANGED`**(ERP 변경을 SRM 개정으로 자동 반영하지 않는다 — 협력사에게 이미 간 발주라 사람이 판단). 동시에 같은 번호 실행 → 매핑 유일이 뒤쪽 실행 전체 롤백 |
| 만들기 | 번호는 SRM 채번(`SRM_PO`), `source = ERP`, 결제 조건 = 협력사 기본, 구매 담당 = 올린 사람. 금액 = 행마다 통화 자릿수 반올림. ERP 에서 이미 승인된 발주라 결재 없이 **확정 → 발행(발행본 스냅샷 + 협력사 통지 아웃박스, D1 "통지는 SRM")** |
| 발행 시점 | 엑셀 엔진은 1,000행 묶음으로 넣으므로 한 발주가 두 묶음에 걸칠 수 있다 → 행은 묶음마다 넣고 **확정·발행은 트랜잭션 커밋 직전(beforeCommit) 한 번** — 같은 트랜잭션(롤백이면 발행도 없음) |
| 받은 뒤 | `SRM_PO_SOURCE=ERP` 이므로 SRM 에서 만들기·행 수정·개정은 `SRM_ERP_OWNED`, 회신·통지·평가·취소·마감은 SRM. 발주 화면 상태 줄에 "ERP 발주 {번호}", 검색어로 ERP 번호 |
| 남의 표 변경 | P1 `mst_ext_id_map`: 대상 유형 CHECK 에 `PO` 추가 + 소속 확인 트리거에 PO 분기(같은 회사 + `source = ERP` 발주만). 기준정보 외부 ID 화면(`ExtService`)은 ITEM·CUST 만 다뤄 영향 없음(6장 예고대로) |

### 4.8 읽기 계약 — 발주 공급 조회 `PoSupplyQuery` (APS 5단계 요청, 마이그레이션 없음)

다른 모듈은 SRM 표를 직접 읽지 않고 계약 패키지 `module.srm.link` 를 쓴다(PLM `module.plm.link` 와 같은 모양).

| 항목 | 내용 |
|---|---|
| 모양 | 인터페이스 `PoSupplyQuery.open(plantCd, itemIds, toDate) → List<Supply>` + SRM 구현 빈 `SrmPoSupplyQuery` + 빈이 없으면 `NONE`(빈 목록) — 쓰는 쪽은 `PoSupplyQuery.of(ObjectProvider)` |
| 대상 | 그 공장의 **발행(ISSUED)·회신(ACKED)·입고 중(PARTIAL_RECEIVED, 2부 R1)** 발주의 열린 행(행 상태 OPEN, 수량 − 취소 − 입고 > 0). 작성 중·결재 중·승인(발행 전)·마감·취소는 제외. 같은 회사만(RLS), 부르는 쪽 트랜잭션 안 읽기 전용 |
| 남은 수량 | 수량 − 취소 수량 − 입고 수량(2부 R1 4.9 에서 계약 모양 그대로 구현만 바꿈). 받은 양은 **이른 약속부터 채운 것**으로 보고 약속 줄에서 지운다 |
| 날짜 | 유효 회신(지금 개정의 최신 묶음, 수락·부분)이 있으면 **약속 줄마다 한 줄**(약속일·약속 수량, `promised = true`), 약속하지 않은 나머지는 요구 납기일 한 줄. 유효 회신이 없거나 거절이면 요구 납기일 한 줄. 약속이 열린 수량을 넘으면 열린 만큼만 |
| 단위 | 품목 **지금 기본 단위**로 환산 — 행 단위 = 기본 단위면 그대로, 아니면 품목 단위 환산표(`mst_unit_conv`, 사용 중, to = 기본 단위), 소수 6자리 HALF_UP. 환산이 없으면 버리지 않고 행 단위 그대로 `inBaseUnit = false` |
| 조건 | `itemIds` **필수** — null 이면 `IllegalArgumentException`(회사·공장 전체 열린 발주를 상한 없이 읽지 않게, 2026-10-21 검토 반영), 빈 목록 = 빈 결과. `toDate` 포함, null = 끝 없음, 지난 날짜(밀린 공급)도 포함. 정렬 = 날짜·발주 번호·행 |
| 결과 한 줄 | poId·poNo·poLineId·lineNo·custId·itemId·supplyDate·qty·unitCd·promised·inBaseUnit + **poUnitCd·openPoQty**(발주 단위와 그 단위의 같은 줄 수량 — WMS 구매 입고 화면이 발주 단위로 입력받아서. 2026-10-22 WMS 제안, 상위 검토 채택. 칸 추가만, 기존 칸 그대로) |

### 4.9 2부 R1 상세 — 구매 입고 게이트 `PoReceiptGate` (V118(임시 V9503))

WMS 12.2 D-B3(2026-10-21 사용자 채택: "SRM 게이트 + 투영")의 SRM 쪽. WMS 구매 입고(B4b `RCV_PO`)가 이 계약을 부른다 — **WMS 표(`inv_trx` 등)는 이 단계에서 고치지 않는다**(아래 "WMS 쪽 계약 초안"은 상위 세션·WMS 세션에 넘기는 초안).

| 항목 | 내용 |
|---|---|
| 모양 | `module.srm.link.PoReceiptGate.receive(poLineId, qty, trxRef, by) → Receipt` + SRM 구현 빈 + 빈 없으면 `NONE`(**IllegalStateException** — 조용히 통과하면 열린 수량 확인 없이 원장에 발주 행이 붙는다). 쓰는 쪽은 `PoReceiptGate.of(ObjectProvider)`. WMS 문서 모양에 `by`(처리자)를 더했다(`LotHoldPort` 와 같이 계약이 감사 칸을 채운다) |
| 트랜잭션·잠금 | 부르는 쪽 트랜잭션 안(MANDATORY), **원장 INSERT 보다 먼저**. 잠금 = **발주 머리 → 발주 행** `FOR UPDATE` — 머리 상태를 다른 행 입고 수량으로 계산하므로 머리까지 잡는다(행만 잡으면 두 행 동시 완료가 서로 "덜 받음" 으로 보고 PARTIAL 에 멈춘다). SRM 다른 경로(회신·개정·취소)도 머리 먼저라 순서가 같고, WMS 잠금 순서(12.2.2: 상대 모듈 머리 행 → `lot_master` → 기간 → `inv_stock`)에 그대로 맞는다 |
| 입고(qty > 0) | 머리 ISSUED·ACKED·PARTIAL_RECEIVED·RECEIVED, 행 OPEN 이어야(아니면 409 `SRM_STATE`). **ERP 원천 발주(`source = ERP`)는 409 `SRM_ERP_OWNED`**(아래 R1-2). 입고 합 ≤ (수량 − 취소) × (1 + `SRM_RECV_OVER_PCT`/100) — 넘으면 409 `SRM_RECV_OVER`(인자 = 더 받을 수 있는 양) |
| 되돌림(qty < 0) | 원장 역분개와 같은 트랜잭션에서 음수로. 머리 PARTIAL_RECEIVED·RECEIVED·**CLOSED**(마감 뒤에도 역분개는 돼야 함, 상태는 마감 그대로). 받은 것보다 많이 되돌리면 409 `SRM_RECV_REVERSE` |
| 수량·단위 | 수량은 발주 단위, 자릿수 = 발주 단위 `attrs.scale`(넘으면 400 `INVALID_VALUE`). 결과 `baseQty` = 품목 **지금 기본 단위**로 환산(같으면 1, 아니면 `mst_unit_conv` 사용 중, 반올림 없음) — **환산이 없으면 409 `SRM_RECV_NO_CONV`**(원장은 기본 단위만, D-B1. 공급 조회 4.8 처럼 행 단위로 돌려주면 원장이 틀린 단위로 쌓인다) **환산 결과가 기본 단위 자릿수(`attrs.scale`)를 넘으면 409 `SRM_RECV_BASE_SCALE`**(예: 1 BOX = 2.5 EA — 원장 `LotService.receive` 의 400 보다 먼저, 원인이 보이게. 2026-10-22 상위 검토) |
| 머리 상태 | 열린 행(OPEN, 수량 − 취소 > 0) 전부 다 받음 → RECEIVED, 하나라도 받음 → PARTIAL_RECEIVED, 되돌려 0 → 유효 회신(지금 개정 최신 묶음이 수락·부분)이면 ACKED 아니면 ISSUED. **자동 마감 없음**(R1-4) |
| 결과 `Receipt` | poId·poNo·poLineId·lineNo·custId·plantCd·whId·itemId·qty·poUnitCd·baseQty·baseUnitCd·recvQty(호출 뒤 합)·openQty(호출 뒤, 초과면 음수)·poStatus |
| 없는·다른 회사 | 404 `NOT_FOUND`(RLS — B사 범위에서 A사 행 id 는 없는 행). 0·null 수량, 빈 `trxRef`(100자)·`by` = IllegalArgumentException(부르는 쪽 버그) |
| DB 가 막는 것 | `recv_qty ≥ 0`(`ck_srm_po_line_recv`), 새 행·작성 중 발주의 입고 수량·입고 있는 행 삭제·마감 발주 입고 증가(`ck_srm_po_line_recv_state`), 입고 상태로 들고 나는 머리 전이가 행 입고 수량과 안 맞음(`ck_srm_po_recv_status` — 직접 SQL 로 상태만 RECEIVED 등), 입고 상태 → 작성 중·취소(`ck_srm_po_transition`). 상태 칸 `varchar(12)` → `varchar(20)`(PARTIAL_RECEIVED 16자) |
| 열린 수량 반영 | 수량 − 취소 − 입고: 회신 화면·엑셀 회신·회신 행 초과 트리거(`ck_srm_po_ack_line_over`), 공급 조회(4.8), 진행 현황(입고 중 발주 포함, 남은 수량 > 0 인 행만, "입고 n" 표시). PR 남은 수량(= PR − Σ 발주 행 수량)은 입고와 무관해 그대로 |
| 화면 | 발주 관리: 입고 중·다 받음 발주는 [마감]만. 진행 현황: 열린 수량 칸 뒤 "· 입고 n", 좁은 화면은 표만 가로 스크롤(`css/views/srm.css`). **입고 화면은 WMS 것**(B4b, `wms-rcv` 에 발주 행 칸) — SRM 에 입고 화면을 만들지 않는다 |

**WMS 쪽 계약 초안** (상위 세션·WMS 세션 합의용 — SRM 은 고치지 않음)

| 항목 | 초안 |
|---|---|
| 원장 | `inv_trx.po_line_id`(빈 값 허용) + **`po_qty`(발주 단위 수량, 빈 값 허용)** — 대사(`srm_po_line.recv_qty` = Σ `po_qty`)를 환산 없이 정확히 하려고. 원장 `qty` 는 `Receipt.baseQty` |
| 부르는 순서 | 입고 화면 저장 → (같은 트랜잭션) `PoReceiptGate.receive(poLineId, poQty, "RCV_PO:{출처 id}", by)` → `LotService.receive`(LOT) → 원장 INSERT. 품목 = `Receipt.itemId`(화면이 고른 품목과 다르면 WMS 가 거부), 공장 = `Receipt.plantCd` |
| 역분개 | 원장 역분개와 같은 트랜잭션에서 같은 행에 `-poQty`. `SRM_RECV_REVERSE` 가 나면 이미 되돌린 것 — 원장 쪽 멱등(출처 키)으로 먼저 막힌다 |
| 오류 표시 | 409 `SRM_STATE`·`SRM_ERP_OWNED`·`SRM_RECV_OVER`·`SRM_RECV_NO_CONV`·`SRM_RECV_BASE_SCALE`, 400 `INVALID_VALUE`, 404 를 입고 화면 메시지로 그대로(문구 키는 SRM 이 넣음) |
| ERP 원천 발주 | 발주 행 칸에서 고를 수 없게(또는 게이트 409) → 발주 없이 RCV_MAT. ERP 로의 입고 송신은 P6(12.2.5 열린 질문 그대로) |
| 빈 없음 | `NONE` 이면 발주 행 칸을 숨기고 RCV_MAT 만 |

**R1 결정 — 사용자 확인 2026-10-22: 추천안**(대기 중이던 결정을 상위 세션 지시로 추천안대로 확정)

| # | 질문 | 추천(채택) | 다른 안 |
|---|---|---|---|
| R1-1 | 잠금 대상 | **머리 → 행**(머리 상태 계산 때문) | 행만 — 두 행 동시 완료 시 상태가 PARTIAL 에 멈춤(트리거 `ck_srm_po_recv_status` 가 잡아 한쪽이 실패) |
| R1-2 | ERP 원천 발주의 구매 입고(WMS 문서 "ERP 원천 모드면 게이트 NONE") | **발주 단위로 판단: `source = ERP` 발주는 409 `SRM_ERP_OWNED`, WMS 는 RCV_MAT 로**. 정책 `SRM_PO_SOURCE` 가 아니라 발주 `source` 로 본다 — 정책을 ERP 로 바꾼 회사에 남은 SRM 발주는 계속 받게 | 정책 ERP 면 게이트가 열린 수량 확인·투영 없이 통과 — 원장에 발주 행이 붙는데 SRM `recv_qty` 는 0 이라 대사가 깨짐 |
| R1-3 | 허용 초과율 | **정책 `SRM_RECV_OVER_PCT`(INT 0~100, 기본 0, 공장 값 가능)** | 품목·협력사별 — 1부 공급 품목(`srm_source`)에 칸 추가가 필요, 요청이 생기면 |
| R1-4 | 다 받으면 자동 마감? | **안 함** — RECEIVED 도 사람이 [마감](허용 초과율 안 추가 입고, 잔량 포기는 마감으로) | 자동 마감 — 초과 허용이 있으면 마감 뒤 추가 입고가 막혀 원장과 엇갈림 |
| R1-5 | 입고가 있는 발주의 개정·취소·회신 | **개정·취소 불가(D6), 회신은 ISSUED·ACKED 에서만**(입고 중 재회신은 2부 다음 단계). 마감은 됨, 마감 뒤 되돌림 허용 | 입고 중 개정 — 행 수량이 입고 아래로 내려가는 경우를 정의해야 함 |
| R1-6 | 단위 환산이 없거나 나누어떨어지지 않을 때 | **거부(`SRM_RECV_NO_CONV`·`SRM_RECV_BASE_SCALE`)** — 반올림하지 않는다 | 행 단위 그대로 반환(4.8 처럼) — 원장 단위가 섞임 / 6자리 반올림 — WMS 에서 자릿수 400 이 나 원인이 흐려짐 |

### 4.10 2부 R2 상세 — 입고 이력 `srm_po_receipt` + 평가 자동 지표 `OTD_RATE` (V128(임시 V9504))

R1 게이트는 입고 **합계**만 남겨 "언제 받았나" 를 모른다. 납기 준수율(OTD)은 날짜가 필요하므로 게이트 안에서 SRM 자기 표에 **입고 이력(추가만)** 을 남기고, 평가 AUTO 지표 `OTD_RATE` 를 계산한다. 다른 모듈 표는 건드리지 않는다.

| 항목 | 내용 |
|---|---|
| 이력 `srm_po_receipt` | 행 = 게이트 호출 1번: 발주·발주 행·**발생일 `occurred_on`**·수량(발주 단위, 부호 = 입고 +/되돌림 −, 0 불가)·근거 `trx_ref`(회사 안 유일)·`created_at/by`. 추가만(수정·삭제 트리거 + 권한 회수), 행이 그 발주의 같은 회사 행인지 트리거 |
| 합계 = 이력 | `srm_po_line.recv_qty` = Σ 그 행 이력 — **커밋 때 검사하는 지연 제약 트리거**(이력 INSERT·행 입고 수량 UPDATE 양쪽, `ck_srm_po_receipt_sum`). 게이트는 이력 → 행 순서로 쓴다. 직접 SQL 로 한쪽만 바꾸면 커밋이 실패 |
| 근거 유일 | `trx_ref` 회사 안 유일(`uq_srm_po_receipt_ref`) — 같은 원장 행으로 게이트를 두 번 부르면 409 `DUPLICATE`(WMS 재시도·이중 호출 방지). 되돌림은 다른 근거(예: `RCV_PO_REV:{id}`) |
| 발생일 | 계약에 오버로드 `receive(…, LocalDate occurredOn)` 와 **`receiveAt(…, OffsetDateTime occurredAt)`**(원장 발생 시각 → SRM 이 그 발주 공장 시간대로 날짜, 부르는 쪽은 시간대를 몰라도 됨) — 4인자 `receive` 는 그대로(비우면 그 공장 시간대의 오늘, `BizClock`). **WMS 호출 2곳은 `receiveAt`**: 구매 입고 = 원장 `occurred_at`, 역분개 = 원래 RCV_PO 행의 `occurred_at`(상위 지시로 이 PR 에서 바꿈). 오늘보다 뒤·발주일보다 앞이면 400 `INVALID_VALUE`. **되돌림은 되돌리는 입고의 발생일**로 부른다(WMS 역분개가 원래 입고 날짜를 넘김) — 그날 순입고보다 많이 되돌리면 409 `SRM_RECV_REVERSE`. 그래야 "납기 안에 받았다가 나중에 되돌림" 이 납기 준수로 남지 않는다 |
| `OTD_RATE` 대상 | 그 협력사의 발행된 발주(ISSUED·ACKED·PARTIAL_RECEIVED·RECEIVED·CLOSED, **원천 SRM 만** — ERP 원천은 게이트를 지나지 않아 R1-2) 행 중 행 상태 취소 아님, 주문(수량 − 취소) > 0, **요구 납기가 평가 기간 안**(평가 기간 끝은 오늘 이하 — `SRM_EVAL_FUTURE` — 라 아직 납기가 안 온 행은 세지 않음) |
| 적시 판정 | 요구 납기일까지 발생한 이력 합(입고 − 되돌림) ≥ 주문 — **행 단위**(수량 가중 아님), 일찍 받은 것도 적시 |
| 점수 | 점수 = 배점 × 적시 행 / 대상 행(2자리 HALF_UP), 원값 % = 4자리, 근거 `OTD:대상:적시`. 대상 0 이면 사람 입력(`OTD_NONE`). `RECV_PPM` 은 R4 에서 계산(4.12.3) |

~~QMS 계약 초안 `ReceiptInspectionQuery.supplierDefects`~~ — **대체됨(2026-10-23)**: 확정 계약은 `module.qms.link.SupplierQualityQuery.receiptQuality`(4.12.2, main #341), 구간 표는 `srm_eval_band`(4.12.3).

**R2 결정 — 사용자 확인 2026-10-22: 추천안**

| # | 질문 | 추천(채택) | 다른 안 |
|---|---|---|---|
| R2-1 | 납기 기준 | **요구 납기(`due_date`)** — 고객(우리) 기준 OTD | 유효 회신 약속일 — 협력사가 늦게 약속하면 준수율이 좋아 보임 |
| R2-2 | 판정 단위 | **행 단위**, 일찍 받은 것도 적시 | 수량 가중 / 조기 입고 창(예: −3일) — 정책이 늘어남, 요청 생기면 |
| R2-3 | 납기 안 온 행 | **세지 않음** — 평가 기간 끝 ≤ 오늘(S6 `SRM_EVAL_FUTURE`)이라 자동 | 납기 전 행도 미입고면 지연 — 진행 중 행이 지연으로 잡힘 |
| R2-4 | 이력의 발생일 | **게이트 선택 인자, 없으면 공장 오늘, 되돌림은 원래 입고일** | 처리 시각만 — WMS 늦은 입력이 지연으로 잡힘 |
| R2-5 | 합계와 이력 일치 | **지연 제약 트리거(커밋 때)** | 서비스만 믿음 — 직접 SQL 로 어긋남 |

### 4.11 2부 R3 설계안 — 사전 납품 통지 ASN (문서, 2026-10-22 — WMS 검토 반영·확정)

**무엇을**: 협력사가 "언제·어느 발주 행을·얼마나·어떤 공급사 LOT 으로 보낸다" 를 입고 **전에** 알린다. 입고 담당은 WMS 입고 화면에서 ASN 을 고르면 발주 행·수량·공급사 LOT·유통기한이 채워져 칸 입력·오입력이 줄고, 구매는 "오는 중" 을 본다. **입고 자체(게이트·원장·LOT·라벨)는 지금 WMS 흐름 그대로** — SRM 은 ASN 표와 읽기 계약 `AsnQuery` 를 준다(추천안). WMS 검토(2026-10-22) 결과를 **[WMS 합의]** 로 표시했다.

#### 4.11.1 데이터 모양 (SRM 자기 표, 회사별 RLS)

| 표 | 칸 | 규칙 |
|---|---|---|
| `srm_asn` 머리 | `asn_no`(채번 `SRM_ASN`, `ASN{yyMM}{seq:4}`)·협력사·공장·납품 창고(선택)·**출하일**·**도착 예정일**·협력사 납품서 번호(`supplier_ref`, 협력사 안 유일 — 같은 납품서 두 번 등록 방지)·운송 메모·출처(MANUAL / IMPORT / PORTAL 자리)·상태·`row_ver`·감사 칸 | 한 ASN = 한 협력사 × 한 공장(발주가 여러 개여도 됨) |
| `srm_asn_line` 행 | 발주 행(`po_line_id`)·품목·발주 단위 스냅샷·**수량(발주 단위)**·공급사 LOT 번호·제조일·유통기한·포장 수(선택) | 발주 행은 같은 회사·같은 협력사·같은 공장, 발주 상태 ISSUED·ACKED·PARTIAL_RECEIVED, **원천 SRM 만**(ERP 원천은 게이트를 못 지나므로 R1-2 와 같이 거부). 같은 발주 행·같은 공급사 LOT 은 한 ASN 안 한 줄 |
| 수량 한도 | **ASN 등록(확정) 때만**: 살아 있는 ASN(취소 아님)의 Σ 미입고 ASN 수량 ≤ 발주 행 열린 수량 × (1 + `SRM_RECV_OVER_PCT`) — 넘으면 행 오류(`SRM_ASN_OVER`). **입고 때 ASN 남은 양을 넘는 것은 막지 않는다**(발주 허용 초과율로만 — 게이트 판정 그대로), 넘게 받으면 그 ASN 행은 다 받음 **[WMS 합의]** | 트리거 + 서비스(회신 S5 와 같은 모양) |
| 입고 연결 | `srm_po_receipt.asn_line_id`(빈 값 허용, 새 칸) — 게이트가 이력에 남김. ASN 행 받은 양 = Σ 그 행 이력. **WMS 원장에는 `asn_line_id` 를 두지 않는다**. 되돌림은 원래 입고 근거(`reversesRef`)로 SRM 이 그 이력 행의 ASN 행을 찾아 함께 되돌린다 | **[WMS 합의]** R3-3 |

#### 4.11.2 상태

`DRAFT`(입력 중, 고침 가능) → `SENT`(확정 = 협력사가 보냈음, 내용 고정 — 고칠 일은 취소 후 새 ASN) → `PARTIAL_RECEIVED` → `RECEIVED`(모든 행 받은 양 ≥ ASN 수량) / `CLOSED`(사람이 마감 — 덜 왔지만 끝) / `CANCELLED`(받은 것이 없을 때만). 입고 상태는 게이트가 이력을 남길 때 같은 트랜잭션에서 바꾼다(발주 머리 상태와 같은 방식, 상태-행 일치 트리거). 도착 예정일 지남 + 미입고 → 진행 현황에 "늦은 ASN".

#### 4.11.3 입력 범위 (협력사 포털 전)

| 입력 | 1단계(R3a) | 뒤 |
|---|---|---|
| 내부 대리 입력 | 화면 `srm-asn`(구매 SAVE): 협력사·공장 → 그 협력사 열린 발주 행 고르기 → 행마다 수량·공급사 LOT·유통기한 → 확정 | — |
| 엑셀 올리기 | 템플릿 `SRM_ASN`(ImportTemplate): 키 = 협력사 납품서 번호 \| 발주 번호 \| 행 \| 공급사 LOT, 같은 납품서 = 한 ASN. 협력사가 보낸 엑셀을 그대로 올리는 용도(회신 S5 와 같은 길) | — |
| 협력사 포털 | **안 함**(D4 그대로) — 협력사 사용자 유형·회사 범위·RLS 정책 모양이 새로 필요. 포털이 생기면 출처 `PORTAL`, 협력사는 **자기 발주 행에만** ASN 을 쓰고 확정까지(입고 쪽 칸은 못 봄) | 별도 설계 |
| EDI(DESADV 등) | 안 함 | P6 어댑터 |

#### 4.11.4 WMS 입고 화면 경계 (추천안 — SRM 은 조회 계약만)

| 단계 | 누가 | 내용 |
|---|---|---|
| ASN 고르기 | WMS 화면 | 공장의 "도착 예정·입고 중" ASN 목록(`expected`) 또는 스캔. **스캔 키 = `ASN:{asn_no}` 접두어만**(SRM 납품서 인쇄 바코드도 이 모양). 협력사 납품서 번호(`supplier_ref`)는 스캔으로 해석하지 않고 입고 화면 "ASN·납품서 번호" 검색 칸이 `AsnQuery.find` 를 부른다. GS1 (402)·SSCC 는 R3c 뒤 **[WMS 합의]** |
| 칸 채우기 | WMS 화면 | **ASN 행 1개 = 입고 요청 1개**(묶음 API 없음). 발주 행·품목·수량(남은 ASN 수량, 발주 단위)·공급사 LOT·유통기한을 채움 — 사람이 실물 수량으로 고칠 수 있음(덜 왔으면 덜 받음). 유통기한은 라벨 글자만(WMS D-B9) **[WMS 합의]** |
| 입고 | WMS 서버(지금 그대로) | `PoReceiptGate.receiveAt(…, asnLineId)` 오버로드 → LOT → 원장. 역분개는 **`reverseAt(poLineId, −qty, trxRef, by, at, reversesRef = 원래 입고 근거 RCV_PO:{client_req_id})`**(이름을 나눔 — 6인자 오버로드 둘은 null 로 부르면 모호, 상위 검토 2026-10-22). WMS 변경 = 입고 호출 1곳 인자 추가 + 역분개 호출 1줄(R3b) **[WMS 합의]** |
| 라벨 | WMS | 입고 때 LOT 라벨은 지금처럼 W2 `LabelPrintService`(아웃박스). ASN 단계 라벨(협력사가 상자에 붙이는 라벨)은 포털 뒤 — 1단계는 SRM **납품서 인쇄 화면**(발주서 S4 와 같은 인쇄 전용, ASN 번호 바코드)만 |
| 수입검사 | QMS | ASN 은 관여하지 않음(입고 상태 QC 는 WMS·QMS 규칙 그대로) |

**계약 초안 `module.srm.link.AsnQuery`** (읽기 전용, 같은 회사, 부르는 쪽 트랜잭션 안, SRM 없으면 `NONE` = 빈 값):

| 메서드 | 돌려주는 것 |
|---|---|
| `expected(plantCd, fromEta, toEta)` | 그 공장 SENT·PARTIAL_RECEIVED ASN 머리(번호·협력사·납품서 번호·출하일·도착 예정일·상태·행 수·남은 행 수), 상한 500 |
| `find(asnNoOrSupplierRef)` | 머리(공장 포함) + 행(asnLineId·poLineId·발주 번호·행 번호·**품목 id**·품목 코드·발주 단위·ASN 수량·받은 양·남은 양·공급사 LOT·제조일·유통기한). 없거나 다른 회사·취소·마감이면 빈 값 |

#### 4.11.5 다른 계약과의 관계

- `PoSupplyQuery`(APS): ASN 이 있으면 그 행의 공급일을 **도착 예정일**로(약속일보다 구체적) — R3-6.
- 평가 `OTD_RATE`: 그대로 입고 이력 기준(ASN 날짜는 쓰지 않음 — "보냈다" 가 아니라 "받았다" 가 기준).
- 회신(S5): ASN 은 회신을 대신하지 않는다(회신 = 약속, ASN = 실제 출하).

#### 4.11.6 R3 결정 — **상위 판단(사용자 지시 '멈춘 하위 세션은 추천안으로', 2026-10-22) + WMS 검토 반영** — 사용자가 항목마다 확인한 것은 아니므로 나중에 바꿀 수 있다

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| R3-1 | 입고 흐름의 주인 | **WMS 그대로, SRM 은 `AsnQuery` 만** — 입고 화면이 ASN 을 읽어 칸을 채우고 게이트 호출은 지금처럼 | SRM 이 "ASN 입고" API 를 열어 WMS 를 부름 — 입고 경로가 둘이 되고 잠금 순서가 뒤집힘 |
| R3-2 | ASN 단위 | **한 ASN = 한 협력사 × 한 공장, 행 = 발주 행 × 공급사 LOT** | 발주 1건 = ASN 1건 — 한 트럭에 여러 발주가 오는 실제와 안 맞음 |
| R3-3 | 입고 ↔ ASN 행 연결 | **게이트 오버로드에 선택 인자 `asnLineId`**(기존 호출 그대로), SRM 이 이력 `asn_line_id` 에 남기고 ASN 상태를 같은 트랜잭션에서 바꿈 — ASN 행이 그 발주 행 것이 아니면 409. **되돌림 = `reversesRef`(원래 입고 근거)로 SRM 이 ASN 행을 찾음**, WMS 원장엔 ASN 칸 없음 **[WMS 합의]** | WMS 원장에 `asn_line_id` — 남의 표 변경 + SRM 이 원장을 읽어야 함 / 연결 없이 발주 행 완료로만 ASN 마감 — 같은 발주 행에 ASN 이 둘이면 어느 쪽인지 모름 |
| R3-4 | ASN 수량 한도 | **등록 때만 Σ 미입고 ASN ≤ 열린 수량 × (1 + 허용 초과율)**, 입고 때 ASN 남은 양 초과는 막지 않음(발주 허용 초과율로만, 넘게 받으면 ASN 행 다 받음) **[WMS 합의 — 처음 안에서 수정]** | 입고 때도 ASN 남은 양으로 막음 — 실물이 더 왔는데 ASN 때문에 못 받음 / 한도 없음 — 중복 통지가 그대로 쌓임 |
| R3-5 | 확정 뒤 수정 | **불가, 취소(받은 것 없을 때) 후 새 ASN**, 덜 온 것은 입고 때 덜 받고 [마감] | 확정본 개정(rev) — 1단계에 과함 |
| R3-6 | APS 공급일 | **ASN 도착 예정일 > 회신 약속일 > 요구 납기**(남은 ASN 수량만큼) — `PoSupplyQuery` 구현만 바꾸고 모양 그대로 | ASN 무시 — 들어오는 날짜를 가장 잘 아는 정보를 버림 |
| R3-7 | 협력사 포털·ASN 라벨 | **1단계 안 함**(D4), 납품서 인쇄 화면만 | 지금 포털 — 인증 경계를 새로 엶 |
| R3-8 | ERP 원천 발주 | **ASN 거부**(R1-2 와 같이 게이트를 못 지남) | 허용 — ASN 은 남는데 입고 연결이 안 됨 |

#### 4.11.7 단계

| 단계 | 범위 | 기대는 것 |
|---|---|---|
| R3a | ASN 표·상태·내부 입력·엑셀 올리기·납품서 인쇄(`ASN:` 바코드)·`AsnQuery`·게이트 `asnLineId`·`reversesRef` 오버로드·진행 현황 "늦은 ASN"·`PoSupplyQuery` 도착 예정일 | 이 문서 결정(R3-1~8) |
| R3b | WMS 입고 화면에서 ASN 고르기·`ASN:` 스캔·납품서 번호 검색·칸 채우기·게이트에 `asnLineId`·역분개에 `reversesRef` | WMS 세션(R3a 계약 위에) |
| R3c | 협력사 포털 입력·ASN 라벨 | D4 포털 설계 |


#### 4.11.8 R3a 구현 (V133(임시 V9505), 2026-10-22)

| 대상 | 구현 |
|---|---|
| 표·트리거 | `srm_asn`(상태 칸 `varchar(20)` — R1 교훈) + `srm_asn_line`, 전이 표 `ck_srm_asn_transition`, 받은 양과 상태 일치 `ck_srm_asn_recv_status`, 받은 ASN 취소 금지 `ck_srm_asn_cancel_received`, 확정 한도 `ck_srm_asn_over`(공장·회사 정책 `hb_srm_recv_over_pct`), 행은 작성 중만 `ck_srm_asn_line_draft`, 발주 행 소속 `fk_srm_asn_line_po_line_comp`·상태 `ck_srm_asn_line_po_state`, 납품서 번호 부분 유일(취소 제외), 이력 `srm_po_receipt.asn_line_id` + 소속 확인. 보존 MASTER |
| 게이트 | `receiveAt(…, OffsetDateTime, Long asnLineId)`(입고, qty &gt; 0) · **`reverseAt(…, OffsetDateTime, String reversesRef)`**(되돌림, qty &lt; 0 — 이름을 나눠 null 호출 모호함을 없앰, 상위 검토 — 원래 이력의 발생일·ASN 행을 씀, 모르는 근거면 ASN 없이). 잠금 발주 머리 → 발주 행 → ASN 머리. `Receipt` 끝에 `asnLineId`. 오류 `SRM_ASN_LINE_MISMATCH`(409) |
| 계약 | `AsnQuery.expected(plantCd, fromEta, toEta)`·`find(key)`(`ASN:` 접두어 떼고 번호 → 없으면 납품서 번호, 여러 협력사에 겹치면 빈 값), `NONE` = 빈 값 |
| 화면 | `srm-asn`(구매 메뉴 55): 기간 = **출하일~도착 예정일 구간이 겹치는 ASN**(공통 기간 프리셋이 오늘까지라 도착 예정일만으로 거르면 미래 도착 ASN 이 기본 조회에서 빠짐 — 브라우저 확인에서 발견)·상태·**"늦은 ASN 만"**(진행 현황 대신 ASN 화면에 — 진행 현황은 발주 행 단위라 ASN 단위 지연이 섞임), 발주 행 가져오기(통지 가능 양으로 채움), 확정·취소·마감, 엑셀 올리기. `srm-asn-print`(메뉴 없음): 납품서 + 스캔 키 `ASN:{번호}` **글자**(바코드 그림은 공용 바코드 라이브러리를 정할 때 — 하위 세션은 vendor·의존성을 더하지 않음) |
| 엑셀 `SRM_ASN` | 같은 협력사·납품서 번호 = ASN 하나(머리 다르면 `SRM_ASN_HEAD_MISMATCH`), 파일 안 합 &gt; 통지 가능 → `SRM_ASN_OVER`(다른 오류 줄은 합에 안 넣음), 커밋 직전 확정(SENT — 협력사가 보낸 것) |
| APS | `PoSupplyQuery`: ASN 남은 양을 **ASN 행마다 도착 예정일 한 줄**(`promised = true`), 그만큼 이른 약속을 채운 것으로 보고 나머지 약속 → 요구 납기 |

**1부에서 AUTO 지표** — `ACK_RATE`(기간 내 발행 발주 중 기한 안 회신 비율)와 `CERT_VALID`(평가 기간 끝 날 기준 필수 인증 유효)는 1부 데이터로 계산 가능, `OTD_RATE`·`RECV_PPM` 은 2부(입고·수입검사) — 1부에서는 "2부 대기" 로 수동 입력.

### 4.12 2부 R4 설계안 — 수입검사 판정 계약(QMS)·입고 PPM `RECV_PPM`·반품·클레임 (문서, 2026-10-23 — 결정 #49, QMS 계약 맞추기용)

**왜 지금 문서만**: 반품·클레임·PPM 은 모두 "어느 협력사의 어느 입고분이 불합격인가" 를 QMS 판정에서 받아야 한다. QMS 표를 SRM 이 직접 읽으면 남의 표 의존이 생기므로 **QMS 가 주는 읽기 계약**(`module.qms.link`)이 먼저다. 이 절은 4.10 초안을 지금 표(2026-10-23 main)에 맞춰 구체화한 것 — QMS 세션과 모양을 맞춘 뒤 코드로 간다.

#### 4.12.1 지금 있는 것 (코드로 확인, 2026-10-23)

| 무엇 | 어디 | 쓸 수 있는 것 |
|---|---|---|
| 수입검사 | `qms_insp`(V099) `insp_type = 'RECEIVE'`, `lot_id`·`item_id`·`lot_qty`(정수)·`status` OPEN/JUDGED/VOID·`result` OK/NG·`judged_at` | 판정 확정 시각·결과. 무효(VOID)여도 확정 결과는 남는다(칸 규칙) |
| 입고 LOT | `lot_master`(V034) `origin = 'RECEIVED'` 일 때만 `supplier_cust_id`·`supplier_lot_no` | **검사 → LOT → 협력사**가 이미 이어짐(새 칸 없이 협력사별 집계 가능) |
| 발주 행 | WMS 원장 `inv_trx.po_line_id`(V122, RCV_PO 만) | LOT → 발주 행(반품·클레임에 발주 번호를 달 때) |
| 부적합 | `qms_ncr`(V112) `source = 'INSP'`·`insp_id`·`lot_id`·`nc_qty`(LOT 단위, 3자리)·`status` OPEN/PENDING/CLOSED/CANCELLED + 처리 `qms_ncr_disp`(USE_AS_IS·REWORK·SCRAP·**RETURN**·SORT, 수량 합 = NCR 수량) | 반품 수량 = 처리 `RETURN` 줄, 불량 수량 = NCR 수량 |

#### 4.12.2 계약 제안 — QMS 가 만들고 SRM 이 부름 (QMS 세션과 맞출 것)

`module.qms.link.SupplierQualityQuery` (읽기 전용, 같은 회사, 부르는 쪽 트랜잭션 안, QMS 없으면 `NONE` = 빈 값 — `AsnQuery`·`PoSupplyQuery` 와 같은 모양):

| 메서드 | 돌려주는 것 | 규칙(제안) |
|---|---|---|
| `receiptQuality(custId, from, to)` | 품목별 줄 `(itemId, unitCd, inspectedQty, rejectedQty, inspectionCnt, ngCnt)` | 대상 = `RECEIVE` 검사 중 **판정 확정(`judged_at`)이 기간 안**·**VOID 아님**·LOT 협력사 = `custId`. `inspectedQty` = Σ `lot_qty`, `rejectedQty` = 그 검사에 달린 NCR(`source = INSP`, 취소 아님) Σ `nc_qty` − Σ 처리 `USE_AS_IS`(R4-2). 기간 = 회사 시간대 날짜 |
| `returnsToSupplier(custId, from, to)` | 줄 `(ncrId, ncrNo, lotId, lotNo, supplierLotNo, itemId, unitCd, returnQty, defectCd, closedAt, poLineId?)` | **닫힌(CLOSED) NCR** 의 처리 `RETURN` 줄 합. `poLineId` 는 **QMS 가 채우지 않는다**(LOT → 발주 행은 WMS 원장 `inv_trx.po_line_id` 에만 있어 QMS 가 읽으면 남의 표 의존) — 누가 잇는지는 R4-8 |
| `ncr(ncrId)` | 위 한 줄 + 설명·불량 유형·판정 검사 번호 | 클레임 상세에 근거로 보임 |

- **SRM 이 QMS 를 호출하지, QMS 가 SRM 을 부르지 않는다**(R4-1) — NCR 닫기 트랜잭션이 SRM 상태에 묶이지 않게. SRM 은 반품·클레임을 만들 때 `ncrId` 를 근거로 남기고 **(회사, ncrId) 유일**로 두 번 만들기를 막는다.
- 단위: 검사·NCR 은 LOT 단위(= 품목 기본 단위). PPM 은 단위가 같은 줄끼리만 합한다(다른 단위가 섞이면 품목별로만 보이고 합계 PPM 은 빈 값).

**QMS 답(2026-10-23, 상위 경유 — 위 제안을 이렇게 고쳐 확정, QMS 가 R4a 구현 시작)**:

| 질문 | 확정 |
|---|---|
| ① 계산 대상·수량 출처 | `qms_insp.status = 'JUDGED'`, 같은 LOT 에 판정이 여럿이면 **최신 1건**. `inspectedQty` = **`lot_master.qty_created`**(`qms_insp.lot_qty` 는 샘플링 입력이라 안 씀 — 위 표의 "Σ `lot_qty`" 는 이것으로 바뀜). `rejectedQty` = NCR 별 `max(0, nc_qty − USE_AS_IS 처리량)`, 처리량은 **현재 처리 줄** 기준. 결과 줄에 **`openNcrCnt`**(아직 닫히지 않은 NCR 수 — 평가 확정 전 화면 표시용) |
| ② 검사 밖 NCR(`source ≠ INSP`) | PPM 분자에 **넣지 않음**. 별도 칸 **`otherNcQty`**(NCR 생성 시각 기준 기간) — 넣을지는 SRM 평가 템플릿이 정함(상위 판단: 채택) |
| ③ 단위 | NCR·처리량은 항상 LOT 단위. 결과는 **(itemId, unitCd)** 로 묶음 — 한 품목이 두 단위면 SRM 이 따로 표시(합계 PPM 은 단위가 하나일 때만). 단위 틈은 MES 가 LOT 사용 중 단위 변경 검사를 넣는 중이라 곧 닫힘 |

SRM 쪽 따름 일(R4a 가 main 에 들어온 뒤): `computeAuto` 의 RECV_PPM = Σ rejected ÷ Σ inspected × 10⁶ → `EvalScorer.ppmScore`, 근거 `PPM:<inspected>:<rejected>:<openNcrCnt>`, 검사 0건 → `PPM_NONE`(사람 입력), 단위 둘 이상 → `PPM_UNITS`(사람 입력). `otherNcQty` 를 PPM 에 넣는 템플릿 선택은 첫 연결에선 두지 않고 근거에만 보인다(필요하면 항목 옵션으로 — 정책 질의).

#### 4.12.3 `RECV_PPM` (평가 AUTO 지표)

PPM = Σ `rejectedQty` ÷ Σ `inspectedQty` × 1,000,000(기간 = 평가 기간, 협력사 = 평가 협력사). **점수는 구간 표**로 — 템플릿 항목에 구간을 둔다(예: ≤ 100 → 배점 100%, ≤ 500 → 80%, ≤ 1,000 → 50%, 그 위 0). 지금 `srm_eval_item` 에는 구간 칸이 없으므로 **새 표 `srm_eval_band`(항목 × 상한 PPM × 점수 비율)** — 확정 템플릿 불변 규칙에 같이 묶는다. 검사 0건이면 사람 입력(`PPM_NONE`), QMS 없으면 `NONE` → 사람 입력(지금과 같음).

**R4b 골격 구현(2026-10-23, V144·임시 V9506 — 계약 없이 되는 부분만)**:
- `srm_eval_band`(항목 × `upper_ppm`(NULL = 그 위 전부) × `score_pct` 0~100), `UNIQUE NULLS NOT DISTINCT (item_id, upper_ppm)` → 같은 상한·끝 없는 구간 둘 다 DB 가 막는다.
- 행 가드 `hb_guard_srm_eval_band`: 항목이 같은 템플릿·같은 회사(`fk_srm_eval_band_item_tmpl`)이고 AUTO `RECV_PPM`(`ck_srm_eval_band_metric`). 작성 중 템플릿만 바꿈 = 항목·등급과 같은 공용 가드 `hb_guard_srm_eval_tmpl_child` 재사용.
- 확정 검사: V068 확정 함수를 고치지 않고 `srm_eval_tmpl` BEFORE UPDATE 트리거를 하나 더(`ck_srm_eval_tmpl_bands`) — RECV_PPM 항목마다 끝 없는 구간 필수. 서비스도 미리 `SRM_EVAL_BAND_NEED`(항목 번호)로 알려 준다. 작성 중엔 구간이 비어도 저장된다.
- 저장은 항목·등급처럼 한 벌(지우고 다시), 새 리비전은 `item_no` 로 짝지어 구간 복사.
- 점수 함수 `EvalScorer.ppmScore(구간, 배점, ppm)`: PPM ≤ 상한인 가장 낮은 구간(경계 포함)의 비율 × 배점, 소수 2자리 HALF_UP, 맞는 구간 없으면 null.
- ~~아직 안 한 것: RECV_PPM 자동 계산~~ → **연결함(2026-10-23, R4a = QMS #341 뒤)**: `EvalService.recvPpm`.
  - PPM = Σ`rejectedQty` ÷ Σ`inspectedQty` × 10⁶(소수 2자리 HALF_UP, 원값 칸), 점수 = `ppmScore`(그 항목 구간).
  - 근거 `PPM:검사:불량:처리 중 NCR 수:검사 밖 NCR 수량` — 처리 중 NCR·검사 밖 NCR 은 PPM 에 넣지 않고 화면 근거로만(확정 전 사람이 본다).
  - 사람 입력으로 남김: 검사 수량 0(QMS 없는 설치본 포함) `PPM_NONE` / 검사 수량 있는 줄의 단위가 둘 이상 `PPM_UNITS:단위들` / 맞는 구간 없음 `PPM_BAND:ppm`(확정 템플릿은 끝 없는 구간이 필수라 실제로는 안 생김).
  - 지표 이름의 "(2부)" 꼬리표 지움(OTD_RATE·RECV_PPM 모두 계산됨), 근거 `PART2` 는 모르는 지표용으로만 남김.
  - **정책 결정(2026-10-23, 상위)**: 검사 밖 NCR(`otherNcQty`)은 PPM 에 넣지 않고 근거에만 표시(현행 유지). 넣어야 한다는 요구가 오면 **템플릿 항목 옵션**으로 — 후보로만 기록.

#### 4.12.4 반품 `srm_return` · 클레임 `srm_claim` (SRM 자기 표)

| 표 | 칸 | 규칙 |
|---|---|---|
| `srm_return` 반품 | 번호(채번 `SRM_RTN`)·협력사·공장·근거 NCR(`ncr_id`, 회사 안 유일)·LOT·발주 행(있으면)·품목·수량(LOT 단위)·**교체 요구 여부**·상태 `DRAFT → CONFIRMED → SHIPPED`(WMS 출고) / `CANCELLED` | 수량 ≤ 그 NCR 의 RETURN 처리 수량 − 이미 반품한 양. **출고는 WMS**(재고 유출 원본 = WMS 원장, 21번 결정) — SRM 은 "반품 지시" 를 만들고 WMS 출고가 끝나면 게이트로 SHIPPED(R4-4) |
| `srm_claim` 클레임 | 번호(`SRM_CLM`)·협력사·근거(NCR 여럿 가능, 줄 표)·요구 조치(시정조치 요구 / 비용 청구 / 둘 다)·청구 금액(표기용, 통화)·**협력사 회신**(원인·대책·회신일, 8D 칸은 최소)·상태 `DRAFT → SENT → ANSWERED → CLOSED` / `CANCELLED` | 통지 = 아웃박스(S4 와 같은 길, `SRM_CLAIM_SENT`). 대금 차감·전표는 ERP(1부 원칙) — SRM 은 금액을 적어 보낼 뿐 |

**발주 열린 수량과의 관계(R4-3)**: 교체 요구 반품이면 반품 출고 때 그 발주 행 입고를 되돌린다(`PoReceiptGate.reverseAt`, 근거 = 반품 출고 원장) → 열린 수량이 다시 생겨 재납품을 받는다. 교체 없음(차감)이면 입고는 그대로, 반품 수량은 클레임 금액 근거로만.

**R4c·R4d 표 골격(2026-10-23, V147·임시 V9507 — 서비스·화면은 QMS `ReturnPage`(#348) 뒤)**:
- `srm_return`: 근거 NCR 은 **QMS 표에 FK 없이** id + 번호 + 처리 줄 번호 스냅샷(QMS 는 공개 계약으로만 읽음 — 회사·협력사·수량 확인은 서비스가 계약 값으로). (회사, NCR, 처리 줄) 은 **살아 있는 반품 하나**(부분 유일 색인, 취소하면 다시). 교체 요구면 발주 행 필수(R4-3), 발주 행은 같은 회사·협력사·품목(단위는 달라도 됨 — 품목 단위 환산, R4c-단위 2026-10-23 V163·임시 V9509). 전이 `DRAFT → CONFIRMED → SHIPPED`, `DRAFT/CONFIRMED → CANCELLED`(사유 필수), 확정 뒤 수량·교체·발주 행 고정, 근거·번호는 늘 고정, 삭제 권한 없음. `SHIPPED` 는 나중 WMS 반품 출고 게이트 자리(R4-4).
- **반품 수량 한도**(처리 줄 반품 수량 − 이미 반품한 양)는 계약 값이 필요해 **서비스**가 본다 — DB 는 수량 > 0 만.
- `srm_claim` + `srm_claim_ncr`: 요구 조치 `CAPA`(금액 없음) / `CHARGE`·`BOTH`(금액 > 0 + 통화 코드). 전이 `DRAFT → SENT → ANSWERED → CLOSED`, `ANSWERED → SENT`(회신 반려), `DRAFT/SENT → CANCELLED`. 근거 NCR 없이 보낼 수 없음, 보낸 뒤 요구·금액·근거 줄 고정, 회신 칸(원인·대책·회신일)은 ANSWERED·CLOSED 에 필수·작성 중엔 없음·보낸 뒤(SENT/ANSWERED)에만 바꿈. 한 NCR 은 **살아 있는(취소 아닌) 클레임 하나**(부모 상태를 봐야 해 트리거 `uq_srm_claim_ncr_live`).
- **반품 대상 목록(QQ-3)**: `returnsToSupplier` 는 `ReturnPage(rows, truncated)` — **목록 표시용만**, 합산에 쓰지 않음(합계는 `receiptQuality`). `truncated` 면 화면에 "잘림" 표시 — 서비스 PR 에서.

**R4c·R4d 서비스·화면(2026-10-23, V149·임시 V9508 — 화면 등록만, 표는 V147)**:
- **반품 지시 `srm-return`** (SEARCH·SAVE·APPROVE, 번호 `RT{yyMM}{seq:4}`):
  - 협력사 → "반품 대상 가져오기"(NCR 종결일 기간, 기본 90일) = 계약 `returnsToSupplier` 줄 + 그 줄의 살아 있는 반품 번호(있으면 못 고름). `truncated` 면 "잘림 — 기간을 줄여 다시" 안내, 합하지 않음(QQ-3).
  - 만들기·수정·**확정 때마다 계약을 다시 읽는다**: `ncr(id)` 가 없음 → `NO_SUCH_CODE`, 다른 협력사 → `SRM_RTN_NCR_SUPPLIER`, 닫힘 아님(다시 열림 포함) → `SRM_RTN_NCR_STATE`; 그 종결 날(회사 날짜)의 `returnsToSupplier` 에서 같은 NCR·처리 줄을 찾고 없으면 `lineNo NO_SUCH_CODE`. 수량 ≤ 처리 줄 수량(`SRM_RTN_OVER`) — 처리 줄마다 살아 있는 반품이 하나라 "이미 반품한 양" 은 이 반품뿐.
  - 교체 요구면 발주 행 필수, 발주 행 고르기 목록 = 그 협력사·품목의 받은 것이 있는 SRM 발주 행(품목 다르면 `SRM_RTN_PO_ITEM`). 발주 단위가 반품 단위와 다르면(R4c-단위) 품목 단위 환산이 있어야 하고(없으면 `SRM_RTN_PO_CONV`), 반품 수량 → 발주 단위 값이 나누어떨어져 발주 단위 자릿수 안이어야(아니면 `SRM_RTN_PO_SCALE` — 예: 1 BOX = 12 EA 에 6 EA). 만들기·수정·확정 때 보고, 출고 게이트가 그때의 환산표로 다시 본다.
  - 확정 = APPROVE, 취소 = SAVE(사유). 출고(SHIPPED)는 WMS 반품 출고 게이트(R4-4) — ~~아직 없음~~ → **있음(#410, `ReturnShipGate` — WMS 화면 wms-rtn-issue 가 원장과 같은 트랜잭션에서 부름, 4.12.7)**. SRM 반품 화면은 출고됨·출고 시각을 보기만 한다.
- **협력사 클레임 `srm-claim`** (SEARCH·SAVE·APPROVE, 번호 `CL{yyMM}{seq:4}`):
  - 근거 NCR 은 `ncr(id)` 로 확인·스냅샷(번호·품목·NCR 수량·단위): 다른 협력사 / 취소된 NCR / 없음 / 같은 것 두 번은 행 오류, 다른 살아 있는 클레임에 든 NCR → `SRM_CLM_NCR_TAKEN`. 처리 중(OPEN) NCR 도 근거가 된다.
  - "한 NCR 은 살아 있는 클레임 하나" 는 줄의 `live_yn`(클레임 취소 때 트리거가 N) + 부분 유일 색인 `uq_srm_claim_ncr_live (comp_cd, ncr_id) WHERE live_yn = 'Y'` 가 보장한다(2026-10-24, QA QC-1, V178·임시 V9512). 처음(V147)엔 트리거가 EXISTS 로만 봐서 작성 중 클레임 둘이 같은 NCR 을 동시에 넣으면(PUT 고치기) 서로의 미커밋 행을 못 봐 둘 다 살았다. 대안 NCR 단위 advisory 잠금은 트리거 안에서 잠그고 다시 보는 절차라 빠뜨리는 경로가 생길 수 있어, 상위 추천대로 DB 색인에 맡겼다. 경합하면 뒤 트랜잭션은 앞 트랜잭션이 끝날 때까지 기다린 뒤 거부된다(앞이 롤백되면 통과).
  - ~~근거 NCR 후보의 한계~~ → **풀림(2026-10-23, QMS #388 `ncrsOfSupplier`)**: 후보 = 협력사 LOT 의 NCR 중 **발견 시각**이 기간 안(취소 제외 — 처리 중·결재 중·반품 없는 NCR 도), `NcrPage.truncated` 면 "기간을 줄여 다시" 안내. 화면 기간 칸은 "NCR 발견일", 줄에 NCR 수량·상태·발견일.
  - 금액: CAPA 에 금액 → `SRM_CLM_NO_CHARGE`, CHARGE·BOTH 는 금액 > 0 + 통화(공통코드 CURRENCY, 트리거 `fk_srm_claim_currency`).
  - 보내기(SAVE, 근거 1개 이상) → **통지 아웃박스 `SRM_CLAIM_SENT`**(bizKey `CLM:id`, 받는 사람 = 대표 담당 메일, 없으면 협력사 메일 — 발주 통지와 같은 송신기·가짜 메일함). 회신 입력(SAVE, 협력사 대신 — D4, 회신일 ≤ 오늘 `SRM_CLM_FUTURE`) → 회신 받음, 반려(SAVE) → 보냄, 종결(APPROVE), 취소(SAVE·사유, 작성 중·보냄만).
  - 통지 이력 화면(srm-notify)은 클레임 통지도 보인다. 실패한 클레임 통지는 다시 보내기가 된다(`NotifyService.resend` → `ClaimService.renotify`, 보낸 뒤 상태에서만). 처음엔 발주 통지만이었다(2026-10-24 문서 정리).

| 미완 | 무엇 | 누구 |
|---|---|---|
| ~~R4c-WMS~~ | ~~반품 출고 + SRM 게이트~~ → **했음(2026-10-23, WMS 세션 — R4-8 #394 `attachLot`, 반품 출고 #410 `ReturnShipGate`·화면 wms-rtn-issue, docs/31 4.12.7·docs/30 12.13·12.14)** | WMS·SRM |
| ~~R4d-QMS~~ | ~~협력사 NCR 목록~~ → **했음**: QMS `ncrsOfSupplier`(#388), SRM 클레임 후보가 이것을 씀(2026-10-23) | QMS·SRM |
| ~~R4d-통지~~ | ~~통지 이력 화면에 클레임 통지~~ → **했음(2026-10-23)**: 이력에 `SRM_CLAIM_SENT` 포함, 번호 칸 = 발주 번호 또는 클레임 번호("발주·클레임"), 다시 보내기 = 클레임이 보냄·회신 받음·종결일 때만 새 메시지 | SRM |
| ~~R4c-단위~~ | ~~교체 요구 반품의 다른 발주 단위~~ → **완료(2026-10-23, V163·임시 V9509)**: 단위 일치 → 품목 단위 환산. 서비스 `SRM_RTN_PO_CONV`·`SRM_RTN_PO_SCALE`, 게이트가 반품 수량 → 발주 단위로 환산해 되돌림(나누어떨어지지 않거나 자릿수 넘침 409 `SRM_RTN_PO_SCALE`), 역분개는 **되돌린 입고량 그대로** 다시 입고(그 사이 환산표가 바뀌어도) | SRM |
| ~~반품 화면 보강~~ | **완료(2026-10-23, 마이그레이션 없음)**: 상세 상태 줄에 출고 근거(`ship_ref`)·출고일, 교체 요구로 출고됨이면 "교체품 대기: 발주 {번호}-{행} 열린 수량 {n} {발주 단위}". "출고 대기" 는 기존 상태 칸(확정)으로 고른다 — 따로 빠른 보기 버튼은 만들지 않음 | SRM |
| ~~협력사 품질 요약 카드~~ | **완료(2026-10-24, 마이그레이션 없음)**: 협력사 상세(srm-supplier)에 최근 90일 — 입고 PPM(평가 RECV_PPM 과 같은 계산·같은 단위 규칙: 검사 없음·단위 둘 이상이면 "없음")·수입검사 수(불합격)·처리 중 NCR(QMS `ncrsOfSupplier`, 잘림 표시)·열린 클레임(작성 중·보냄·회신 받음)·출고 대기(확정) 반품. 보기만, 저장 없음. API `GET /api/srm/suppliers/{id}/quality`(SEARCH) | SRM |
| ~~클레임 ↔ 반품 연결~~ | **완료(2026-10-24, 마이그레이션 없음)**: 같은 근거 NCR 이면 클레임 상세에 "관련 반품 지시", 반품 상세에 "관련 클레임"(취소된 것 제외, 번호·NCR·상태). 번호를 누르면 상대 화면을 그 번호·만든 날 조회 조건으로 연다(셸 탭 `hbShell.open` — 홈 카드 링크와 같은 길, 셸 밖이면 `?q.` 주소). 상세 응답에만 싣고 목록·grid 에는 없음. 상대 화면 권한은 그 화면이 본다(번호·상태는 같은 구매 업무 안에서 보인다). 연결 질의는 같은 협력사(cust_id)만 — NCR 이 스냅샷이라 DB 가 묶지 않는 데 대한 방어(2026-10-24, QA). **정책: 취소된 클레임·반품 상세에도 연결된 상대를 보인다(이력 추적)** — 목록에서 빠지는 것은 취소된 상대 쪽이다(2026-10-24 상위 결정) | SRM |
| ~~ASN 덜 받은 행 수 칸~~ | **완료(2026-10-24, V185·임시 V9513)**: `srm_asn.open_line_cnt` 를 트리거로만 유지(게이트가 아니라 트리거 — 게이트를 거치지 않는 경로도 맞게). 100만 건 덜 받은 행 수 정렬 4.2초 → 0.38초, 늦은 ASN 만 6.7초 → 0.27초(5.6) | SRM |

#### 4.12.5 R4 결정 — 사용자 확인 필요

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| R4-1 | 계약 방향 | **SRM 이 QMS 읽기 계약을 부름**(pull), 근거 `ncrId` 유일로 멱등 | QMS 가 NCR 닫을 때 SRM 포트를 부름(push) — NCR 닫기가 SRM 상태·잠금에 묶임 |
| R4-2 | 불량 수량 | **NCR 수량 − 특채(USE_AS_IS) 처리량** — 그대로 써도 된다고 판정한 양은 불량에서 뺌 | NCR 수량 전부 / NG 판정 LOT 의 `lot_qty` 전부 — 선별(SORT)로 일부만 불량인 경우를 과대 계산 |
| R4-3 | 반품과 발주 열린 수량 | **교체 요구면 입고 되돌림(열린 수량 다시), 아니면 그대로** — 반품 지시에 "교체 요구" 칸 | 늘 되돌림 — 차감(교체 없음) 반품도 재납품 대기로 남음 / 늘 그대로 — 교체품을 받을 열린 수량이 없음 |
| R4-4 | 반품 출고 | **WMS 출고(반품 유형) + SRM 게이트로 상태 반영** — WMS 세션 계약 필요(R1 게이트와 같은 모양) | SRM 이 상태만, 재고는 따로 — 재고 원장과 반품이 어긋남 |
| R4-5 | PPM 점수 | **템플릿 항목 구간 표 `srm_eval_band`** | 고정 공식(예: 100 − PPM/100) — 고객마다 기준이 달라 못 맞춤 |
| R4-6 | 클레임 범위 | **시정조치 요구 + 금액 표기(청구는 ERP)**, 8D 는 최소 칸(원인·대책·회신일) | 8D 전 단계(D1~D8) — QMS CAPA 와 겹침(나중에 QMS CAPA 연결로) |
| R4-7 | 검사 기간 기준 | **판정 확정 시각**(`judged_at`) | 입고 시각 — 판정이 늦게 나면 이미 확정한 평가가 바뀔 수 있음 |
| R4-8 | 반품 LOT → 발주 행 잇기 | **SRM 게이트가 입고 이력에 `lot_id` 를 남김**(`receiveAt` 에 선택 인자, WMS 호출 1곳 인자 추가 — R3b 와 같은 모양), SRM 이 자기 표로 LOT → 발주 행을 찾음 | WMS 읽기 계약 `LotReceiptQuery(lotId)` — WMS 세션 일이 하나 더 / 발주 행 없이 반품 — 교체 요구(R4-3)를 어느 발주에 걸지 모름 |

#### 4.12.6 단계(제안)

| 단계 | 범위 | 기대는 것 |
|---|---|---|
| R4a | QMS `SupplierQualityQuery` 구현 | **QMS 세션**(이 절 4.12.2 를 맞춘 뒤) |
| R4b | `RECV_PPM` + `srm_eval_band`(템플릿 구간) | R4a |
| R4c | 반품 지시 `srm_return` + 화면(QMS 반품 대상 목록에서 만들기) | R4a, R4-4 이면 WMS 반품 출고 계약 |
| R4d | 클레임 `srm_claim` + 통지 | R4a |

#### 4.12.7 반품 출고 게이트 계약 초안 — WMS 가 부르고 SRM 이 받음 (2026-10-23, WMS 시작 때 맞출 것)

R4-4(반품 출고 = WMS 원장) · R4-3(교체 요구면 입고 되돌림) · R4-8(입고 이력에 LOT) 을 잇는 SRM 쪽 계약. 모양은 구매 입고 게이트 `PoReceiptGate`(4.9) 와 같게 — **WMS 가 원장 쓰기 직전에 부르고, SRM 표는 SRM 이 고친다**.

`module.srm.link.ReturnShipGate` (부르는 쪽 트랜잭션 안 MANDATORY, SRM 없으면 `NONE` = IllegalStateException — 조용히 통과하면 확정 안 된 반품이 출고된다):

| 메서드 | 하는 일 | 규칙(초안) |
|---|---|---|
| `find(String returnNo)` → `Optional<ReturnRef>` | WMS 반품 출고 화면이 반품 지시를 고를 때(스캔 키 `RTN:{return_no}`) | 확정(CONFIRMED)만, 다른 회사·없음 = 빈 값. `ReturnRef(returnId, returnNo, custId, plantCd, lotId, lotNo, itemId, unitCd, qty, replaceYn, poLineId)` |
| `ship(returnId, lotId, qty, trxRef, by, occurredAt)` → `Shipped` | 반품 출고 원장 INSERT **직전** | 잠금 = 반품 → (교체 요구면) 발주 머리 → 발주 행. 확정이 아니면 409 `SRM_STATE`. lotId = **WMS 가 재고에서 실제로 뽑은 LOT**(LOT 관리 품목 = 그 LOT id, 비관리 품목 = null) — 게이트가 품목 `lot_ctrl_yn` 으로 판단: 관리 품목은 지시 `lot_id` 와 다르면(null 포함) 409 `SRM_RTN_LOT_MISMATCH`, 비관리 품목은 null 이어야(값이면 IllegalArgumentException) — SRM 리뷰 2026-10-23. **수량 = 지시 수량 전부**(나눠 출고 없음 — R4-9) 아니면 409 `SRM_RTN_QTY_MISMATCH`. 성공 → `SHIPPED` + `shipped_at = occurredAt`. 교체 요구면 같은 트랜잭션에서 `PoReceiptGate.reverseAt(poLineId, −qty(발주 단위 환산 — R4c-단위: 반품 단위 → 기본 → 발주 단위, 나누어떨어지지 않거나 발주 단위 자릿수 넘침 409 `SRM_RTN_PO_SCALE`), trxRef, by, occurredAt, reversesRef)` — `reversesRef` = 그 LOT 을 받은 입고 이력의 근거 — 인자가 아니라 **지시의 `lot_id`** 로 `srm_po_receipt.lot_id`(그 발주 행, qty > 0)를 찾음(비관리 품목도 R4-8 이 입고 LOT 를 남김), 없으면 409 `SRM_RTN_NO_RECEIPT`. 되돌리는 양은 **그 LOT 순입고**(같은 lot_id 의 되돌림 행을 뺀 합)까지 — 넘으면 409 `SRM_RECV_REVERSE`. 환산·자릿수·되돌림 한도 오류는 입고 게이트 것을 그대로 |
| `unship(returnId, trxRef, by, occurredAt, reversesRef)` → `Shipped` | WMS 가 반품 출고 원장을 역분개할 때(같은 트랜잭션) | 출고됨(SHIPPED)만 → 확정(CONFIRMED)으로, `shipped_at` 비움. 교체 요구였으면 되돌린 입고를 다시 입고(`receiveAt`, 같은 발생 시각) — 그새 열린 수량에 재입고가 들어와 초과면 409 `SRM_RECV_OVER`(사람이 정리). **R4-10: 허용(결정 — 사용자 2026-10-23)** |

- `Shipped(returnId, returnNo, status, poLineId, poStatus, openQty)` — WMS 화면이 "교체품 대기 n" 을 보이게.
- `trxRef` = WMS 원장 근거(`RTN_ISS:{client_req_id}`), 회사 안 유일 — 같은 근거 두 번 = 409 `DUPLICATE`(멱등은 WMS `sys_inbox`).
- 수량 단위: 반품 수량은 **LOT 단위(= 기본 단위)**, 발주 행 되돌림은 발주 단위 — 환산은 게이트 안(입고 게이트와 같은 표, 환산 없음 = 409 `SRM_RECV_NO_CONV`). 출고 역분개(`unship`)는 다시 환산하지 않고 출고 때 되돌린 입고 행(`srm_po_receipt.trx_ref = ship_ref`)의 양을 그대로 다시 입고(R4c-단위, 2026-10-23).
- SRM 트리거 쪽: `srm_return` 의 `CONFIRMED → SHIPPED` 는 지금도 열려 있다(V147). 게이트가 생기면 **앱 계정 직접 UPDATE 로 SHIPPED 를 막을지**(게이트만) 는 그때 — 지금은 화면에 출고 버튼이 없다.
- 선행: R4-8 — WMS 구매 입고가 `PoReceiptGate.receiveAt(..., lotId)` 로 LOT 를 넘기고 SRM 입고 이력에 `lot_id` 칸(새 V). 이게 없으면 교체 요구 반품은 어느 입고를 되돌릴지 모른다.
  - **했음(2026-10-23, WMS 세션·V154·임시 V9312 — docs/30 12.13)**: `receiveAt` 인자 대신 **`PoReceiptGate.attachLot(trxRef, lotId)`** — WMS 는 게이트 뒤에 LOT 를 만들므로(LotService 순서 그대로) 같은 트랜잭션에서 입고 이력 1행의 `lot_id` 를 한 번 채운다(0행 = 409 `SRM_STATE`). 되돌림 행은 `reversesRef` 이력의 `lot_id` 를 받는다. 이력은 `lot_id` null → 값 한 번만 UPDATE 허용(트리거 + 칸 GRANT)

**구현(2026-10-23, WMS 세션·V159·임시 V9313 — docs/30 12.14)**: 위 초안대로 + 다음만 다름(ship 행의 LOT 인자·순입고 한도는 위 표에 고쳐 적음) — `find` 는 출고됨(SHIPPED)도 돌려준다(화면이 "이미 출고됨" 을 보이게, `status` 칸), `ship` 의 수량은 **기본 단위**(원장 단위)로 받아 지시 수량의 환산 값과 비교, 출고 근거는 `srm_return.ship_ref`(회사 안 유일·출고됨일 때만), 되돌림 행의 재입고는 `receiveAt` + `attachLot`(같은 LOT). 앱 계정 직접 UPDATE 막기는 docs/30 12.14 안 (나) — 상위 판단.

**사용자 결정(2026-10-23, 상위 경유)**: R4-9·R4-10 **모두 추천안** — 나눠 출고 없음(지시 수량 전부), 출고 역분개 허용(확정으로 되돌리고 교체 요구 입고 다시).

| # | 질문 | 결정 | 다른 안 |
|---|---|---|---|
| R4-9 | 나눠 출고 | **안 함 — 지시 수량 전부 한 번에**(덜 보내면 지시 수량을 고쳐 다시 확정) | 나눠 출고 + 출고 합 칸 — 반품은 보통 한 번에 나가고, 상태가 늘어남 |
| R4-10 | 출고 역분개 | **허용 — 확정으로 되돌리고 교체 요구 입고도 다시**(재입고 초과면 막고 사람이 정리) | 막음 — 잘못 출고한 반품을 고칠 길이 없음 |

### 4.13 2부 R3c 설계안 — 협력사 포털·EDI (문서, 2026-10-23 — 결정 #49, 계약 없이 할 수 있는 준비)

**무엇을**: 지금은 협력사 일(회신·ASN)을 우리 직원이 대신 입력하거나 엑셀로 받는다(D4). R3c 는 **협력사가 직접**(포털) 또는 **기계로**(EDI) 넣게 한다. 포털은 **로그인·회사 범위·RLS 경계를 새로 여는 일**이라 SRM 만으로 정할 수 없다 — 플랫폼(인증·`TenantContext`) 쪽 결정이 먼저다. 이 절은 SRM 이 필요로 하는 것과 선택지를 정리한다.

#### 4.13.1 포털에서 협력사가 하는 일 (SRM 쪽 범위 제안)

| 순서 | 일 | 지금 대신하는 것 | 협력사가 보는 범위 |
|---|---|---|---|
| P-1 | **발주 보기·회신**(약속 납기·분할·거절) | 납기 회신 S5 대리 입력·엑셀 | 자기 협력사의 발행된 발주만, 단가 칸은 보임(발주서에 이미 있음) |
| P-2 | **ASN 입력·확정**(출처 `PORTAL`) + ASN 라벨 인쇄 | ASN R3a 대리 입력·엑셀 | 자기 발주 행만, 입고 쪽 칸(받은 양 외)은 안 보임 |
| P-3 | 인증서 올리기(만료 갱신) | 협력사 정보 S1 대리 입력 | 자기 인증서만, 판정은 우리 쪽 확인 뒤 |
| P-4 | 클레임 회신(R4d) | — | 자기 클레임만 |

#### 4.13.2 경계 — 플랫폼이 정해야 할 것

| 무엇 | 선택지 | SRM 추천 |
|---|---|---|
| 협력사 사용자 | (가) `sys_user` 에 사용자 유형 `SUPPLIER` + 소속 거래처 `cust_id` / (나) 별도 표 `srm_portal_user` + 별도 인증 | **(가)** — 잠금·시도 제한·MFA·감사 로그를 그대로 씀. 대신 **내부 화면(`/page/**`)·내부 API 는 유형이 INTERNAL 일 때만**(필터 한 곳) |
| 회사 범위 | 협력사 사용자도 한 회사 소속(로그인 = 회사코드 + 아이디) | 그대로 — 한 협력사가 여러 고객사에 납품하면 회사마다 계정(SaaS 에서 회사 사이를 넘지 않음) |
| 협력사 범위(회사 안) | (가) **포털 전용 API(`/api/portal/srm/**`)에서 서비스가 `cust_id` 로 거름** / (나) RLS 두 번째 정책 `supplier_isolation`(`hb_current_supplier()`) | **(가) + 시험** — SRM 표마다 정책을 늘리면 `TenantCatalogGuard` 허용 목록·모든 내부 조회가 영향을 받음. 포털 API 는 내부 API 와 **다른 컨트롤러**(내부 DTO 재사용 금지 — 입고 칸 노출 방지), 권한 `@ProgAuth` 대신 포털 전용 주석 + 가드 시험 |
| 인증 강도 | 포털은 바깥 사람 | MFA 필수 정책(`MFA_SECRET_KEY` 있는 설치만 포털 켜기), 세션 짧게(예: 2시간) — 플랫폼 정책 |
| 화면 | 내부 셸 재사용 / 포털 전용 작은 셸 | **포털 전용 셸**(메뉴·도움말·의견 보내기 없음, 같은 `hb.*`·CSP) |

#### 4.13.3 EDI (기계 간)

| 방향 | 메시지(예) | SRM 쪽 |
|---|---|---|
| 발주 보내기 | UN/EDIFACT `ORDERS` / ANSI X12 850 | 발행 아웃박스(S4)에 EDI 송신기 추가 — 채널은 P6 어댑터 |
| 회신 받기 | `ORDRSP` / X12 855 | 회신 S5 `record` 를 그대로 부름(출처 `EDI`), 멱등 = `sys_inbox`(메시지 id) |
| ASN 받기 | `DESADV` / X12 856 | ASN R3a 만들기 + 확정(출처 `EDI`), 납품서 번호 = 메시지의 납품서 번호, SSCC·(402)는 WMS 라벨과 함께(아래) |

SRM 이 **지금 할 수 있는 준비**(계약·플랫폼 결정 없이): 회신·ASN 서비스가 이미 "엑셀 올리기" 와 "화면 입력" 을 같은 서비스로 받으므로, 출처 값 `PORTAL`·`EDI` 를 붙일 자리를 정한다 — **지금 CHECK 는 회신 `ck_srm_po_ack_source`·ASN `ck_srm_asn_source` 둘 다 `MANUAL`·`IMPORT` 뿐**(4.11.1 의 `PORTAL` 은 예고만). 값 추가는 마이그레이션 한 줄씩이지만 쓰는 쪽(포털·어댑터)이 생길 때 같이 넣는다 — 지금 넣으면 아무도 안 쓰는 값이 CHECK 에 남음(R3c-5 결정 뒤). 메시지 형식 해석(EDIFACT·X12 파서)은 P6 어댑터 일 — SRM 에 넣지 않는다.

#### 4.13.4 라벨·GS1 (WMS 와 같이)

ASN 라벨(협력사가 상자에 붙임)은 포털 P-2 뒤. 바코드는 지금 SRM 납품서 인쇄가 `ASN:{번호}` **글자**만(공용 바코드 라이브러리 결정 전). GS1 은 WMS 가 기다리는 항목(docs/29 7.7 "GS1 (402)·SSCC 는 SRM R3c 뒤") — **(00) SSCC = 상자·팔레트 번호, (402) GSIN = 출하 묶음 번호**를 ASN 머리·행에 둘지가 R3c 결정(R3c-4). 회사 GS1 업체 코드가 있어야 만들 수 있어 정책이 필요.

#### 4.13.5 R3c 결정 — 사용자 확인 필요

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| R3c-1 | 포털을 만들지·언제 | **R4(반품·클레임) 뒤, 플랫폼 협력사 사용자 설계를 먼저**(시스템관리·상위) | 지금 SRM 이 단독으로 — 인증·격리 경계를 하위 세션이 여는 것은 규칙(공통 변경)에 어긋남 |
| R3c-2 | 협력사 사용자 모양 | **`sys_user` 유형 SUPPLIER + `cust_id`**, 내부 화면·API 차단 필터 | 별도 인증 체계 — 잠금·MFA·감사를 다시 만듦 |
| R3c-3 | 회사 안 협력사 범위 | **포털 전용 API 에서 서비스 거름 + 가드 시험** | RLS 두 번째 정책 — 모든 SRM 표·내부 조회 영향 |
| R3c-4 | GS1 SSCC·(402) | **포털 ASN 라벨 때 같이, 정책 `GS1_COMPANY_PREFIX` 있을 때만** | 지금 ASN 표에 칸 — 만들 수 없는 값(업체 코드 없음) |
| R3c-5 | EDI | **P6 어댑터 몫, SRM 은 출처 값·멱등만 준비** | SRM 안에 EDIFACT 해석 — 모듈 경계 밖 |
| R3c-6 | 첫 포털 범위 | **P-1 회신 + P-2 ASN** | P-3 인증서까지 — 파일 올리기 경계(바이러스 검사·크기)가 하나 더 |

### 4.14 협력사 포털 업무 구현 (2026-10-23, V167·임시 V9510 — docs/42 7장 3)

플랫폼(docs/42 10장)·포털 셸(11장) 위에 4.13 의 **P-1 발주 보기·회신 + P-2 ASN 입력·확정 + 납품서 인쇄**를 올렸다. 규칙은 docs/42 4장 그대로다. 패키지는 `module.srm.portal`, 매퍼는 `mapper/srm/portal/`(모든 문장 `#{custId}`, 가드 시험), API 는 `@PortalApi`, 협력사는 `PortalUser.current()` 로만 얻는다.

| 항목 | 정한 것 | 왜(버린 안) |
|---|---|---|
| 읽기 | 포털 전용 `PortalSrmMapper`(발주·행·회신 이력·ASN·행·공장·넣을 수 있는 발주 행) + 포털 전용 DTO(`PortalSrmDtos`). 협력사가 볼 칸만 고른다: 받은 양은 보이고, 내부 메모·담당자·감사 행위자·ERP 번호·입고 원장 근거는 빼다 | 내부 매퍼·DTO 를 다시 쓰면 거름 없는 질의와 내부 칸이 섞인다(docs/42 4장) |
| 쓰기 | 포털 서비스가 **같은 트랜잭션에서 소유를 잠가 확인**한다(`lockOwnPo`·`lockOwnAsn`, 남의 것·없는 것 = 404). 그 뒤 내부 서비스(`AckService.recordFromPortal`·`AsnService.createFromPortal`·`update`·`send`·`cancel`)가 검사·상태 계산을 한다 | 검사를 포털에 한 벌 더 두면 한도·상태 규칙이 갈라진다. 내부 서비스는 id 로만 일하니 소유 확인은 포털 몫이다 |
| 출처 | 회신·ASN `source = 'PORTAL'`(V167·임시 V9510 이 CHECK 두 개에 값 추가). 내부 회신 이력은 "협력사 포털"로 보인다 | 4.13.3 "쓰는 쪽이 생길 때 같이"(EDI 는 아직 없음) |
| ASN 협력사 | 포털 입력 DTO 에 협력사 칸이 없다. 서비스가 로그인 소속의 `cust_cd` 로 채워 내부 서비스에 넘기므로, 요청에 `custCd`·`source` 를 넣어도 무시된다(시험). 발주 행은 내부 검사가 "머리 협력사 것"만 받는다(`SRM_OTHER_SUPPLIER`) | — |
| 포털이 고치는 ASN | **출처 PORTAL 만** 고치기·확정·취소가 된다. 우리 직원이 대신 넣었거나(MANUAL) 엑셀로 올린(IMPORT) ASN 은 보기만 하고, 고치려 하면 409 `SRM_PORTAL_ASN_INTERNAL` | 협력사가 우리 직원의 작성 중 자료를 바꾸면 누가 넣었는지 흐려진다. 협력사가 직접 넣을 길은 열려 있다 |
| 마감 | 포털에 없다(내부 전용) | 마감은 "덜 왔지만 끝"이라는 받는 쪽 판단이다 |
| 거래 정지(P1) | `srm_supplier.trade_status = 'BLOCKED'` 면 조회는 되고, 회신·ASN 만들기·고치기·확정은 409 `SRM_PORTAL_BLOCKED`. **취소는 된다**. HOLD 는 막지 않는다 | 이미 보낸 납품 통지를 거둘 길은 남긴다. HOLD 는 새 발주만 멈추는 내부 상태다(진행 중 발주의 납품은 계속) |
| 화면 | `/portal/po`(목록 → 상세·회신 이력 → 행마다 약속 납기·수량, 나눠 회신·거절), `/portal/asn`(공장 → 내 열린 발주 행 → 수량·LOT·날짜, 저장·확정·취소, 납품서 링크), `/portal/asn-print`(메뉴 없음). 셸 조각 + `addShell`, 메뉴 `srm-po`(100)·`srm-asn`(200). 표는 hb.grid 대신 단순 표 | 외부 PC 에서 몇 건 오가는 화면이라 단순 표로 충분하다. 공통코드 API 가 포털 경계 밖이어서 상태 이름은 문구 키(`js.portalSrm.*`)로 한다 |
| 확정 버튼 | 화면이 **저장 → 새 행 버전 읽기 → 확정** 순서로 부른다 | 고친 내용을 저장하지 않고 확정하는 실수를 막는다 |
| 취소 사유 | 화면 칸으로 받는다(브라우저 대화상자 없음) | — |

남은 것(이 PR 밖):

| 할 일 | 메모 |
|---|---|
| P-3 인증서 올리기·P-4 클레임 회신 | 파일 올리기 경계(크기·검사)는 따로 결정한다 — R3c-6 |
| ASN 라벨·GS1(R3c-4) | 정책 `GS1_COMPANY_PREFIX` 와 함께 |
| 발주 통지 메일에 포털 링크 | 메일 본문은 SRM 핸들러 몫이지만, 설치 주소(base URL) 정책을 먼저 정해야 한다 |

### 4.15 협력사 포털 P-4 클레임 회신 (2026-10-24, V175·임시 V9511 — 상위 지시 조건)

| 항목 | 정한 것 | 왜 |
|---|---|---|
| 볼 수 있는 클레임 | 내게 **보낸 적 있는** 클레임만(`sent_at` 있음 — 작성 중은 협력사가 모른다), 보낸 날 기간. 요구 내용·요구 조치·회신 기한·청구 금액(표기)·근거 NCR(번호·품목·수량)·회신. 내부 칸(보낸 사람·종결한 사람·회신 넣은 사람)은 없음 | 4.14 와 같은 "협력사가 볼 칸만" |
| 회신할 수 있는 상태 | **보냄(SENT) 하나**. 회신 받음·종결·취소는 409 `SRM_STATE`, 작성 중은 404. 포털에는 고쳐 쓰기가 없다 — 고칠 일은 우리 쪽이 반려(→ 보냄)한 뒤 다시 회신 | 상위 조건. 대신 입력 화면은 지금처럼 회신 받음에서도 고친다 |
| 회신 내용 | 원인·대책 글(각 2000자, 필수 — 대신 입력과 같은 검사), **파일 없음**. 회신일은 서버가 오늘(회사 날짜) | 파일 경계는 P-3 결정 뒤 |
| 같은 요청 반복 | **멱등 아님 — 409 로 거부**. 두 번째 요청은 이미 회신 받음이라 상태 검사에서 409 `SRM_STATE`(행 버전보다 상태를 먼저 본다). 요청 키 헤더가 같으면 그 전에 409 `DUPLICATE_REQUEST`. 첫 회신 내용은 그대로 | 회신은 "보냄 → 회신 받음" 한 번의 전이라 같은 결과를 다시 돌려줄 것이 없다. 화면은 다시 읽으면 회신이 보인다 |
| 누가 넣었나 | `srm_claim.answer_source`(MANUAL 대신 입력 / PORTAL 협력사)·`answered_by`(V175·임시 V9511). 대신 입력 경로는 그대로 두고 MANUAL 로 기록. 내부 클레임 화면 회신 칸에 "협력사가 포털에서 회신 / 우리 쪽 대신 입력 (누가, 언제)" | 상위 조건. 이 칸이 생기기 전 회신은 NULL(대신 입력으로 보임) — 회사마다 범위를 열어 채우는 선례(V109)가 있으나, NULL = 대신 입력으로 충분해 채우지 않음 |
| 거래 정지(BLOCKED) | **정책: 정지 협력사도 클레임 회신 허용**(상위 승인 2026-10-24) | 근거: 정지를 풀려면 그 원인인 품질 문제를 해결해야 하고, 클레임 회신(원인·대책)이 그 길이다. 새 납기 회신·ASN 은 새 거래라 409(4.14, docs/42 P1) — 둘을 다르게 둔다. 시험 `PortalClaimTest#거래_정지_협력사도_클레임_회신은_200` |
| 협력사 격리 | 포털 매퍼 4문장(`findClaims`·`findClaim`·`findClaimNcrs`·`lockOwnClaim`) 모두 `#{custId}` 를 동적 분기 밖에(#461 가드). 쓰기 = 소유 잠금 → `ClaimService.answerFromPortal` | 4.14 와 같은 모양 |
| 화면 | `/portal/claim`(메뉴 `srm-claim` 300): 목록 → 상세 → 보냄이면 회신 칸 | — |

## 5. 화면

| 화면 | 코드 | 메모 |
|---|---|---|
| 협력사 정보 | `srm-supplier` | 거래처 목록(PURCHASE·BOTH) + 상세(SRM 칸) + 탭: 인증서(만료 배지) · 공급 품목 · 단가. 거래처 기본정보는 읽기 전용 + "거래처관리에서 열기" |
| 구매요청 | `srm-pr` | 표준 화면(목록 + 상세 + 행 표), 요청 → 승인/반려 버튼 |
| 발주 관리 | `srm-po` | 목록 + 상세 + 행 표, "PR 가져오기" 대화상자(남은 수량 표시, 고른 행만), 결재 상신 · 발행 · 개정 · 취소 · 발주서 엑셀 · 인쇄 · 개정 비교 |
| 발주서 인쇄 | `srm-po-print` | 인쇄 전용 템플릿(셸 없음, `@media print` CSS — 우리 화면 CSS, 공통 CSS 수정 없음) |
| 납기 회신 | `srm-ack` | 발주 고르기 → 행별 약속 납기·수량(분할 줄 추가) + 회신 이력 + 엑셀 회신 올리기 |
| 발주 진행 현황 | `srm-po-status` | 조회 전용: 회신 안 옴 · 요구 납기보다 늦은 약속 · 납기 임박(2부: 미입고), 기간 조건 필수 |
| 통지 이력 | `srm-notify` | 조회 전용 + 실패 건 "다시 보내기"(아웃박스 재시도) |
| 평가 템플릿 | `srm-eval-tmpl` | 리비전 머리 + 항목 표 + 등급 구간, 가중치 합 표시 |
| 협력사 평가 | `srm-eval` | 협력사·기간·템플릿 → 점수 입력(AUTO 항목은 계산값 + 근거 보기) → 확정, 결과 목록(등급·과락) |

메뉴: 새 상위 메뉴 **"구매"**(8장 D9). 화면 등록은 `sys_prog` Flyway 로만, 메뉴는 회사별 시드.

### 5.1 UI 단계 3 — 목록 화면 표준 틀 ②~⑥ (2026-10-22, docs/40 P1·V-7·부록 A, 결정 docs/29 #44·#45·#47·#48)

SRM 9화면(인쇄 2화면 제외)에 **서버 변경 없이** 적용한 것. ①(일반 표 주 목록 → hb.grid)은 화면별 API **추가**(`…/grid`·`/columns`)가 필요해 다음 PR 부터 3~4화면씩.

| 항목 | 내용 |
|---|---|
| ② 조회 영역 | 9화면 모두 이미 `hb-search` 패널(기간 프리셋 + 상태·검색어) — 바꾼 것 없음 |
| ③ 도구줄 순서 | [신규] … [엑셀 올리기] — 발주(ERP 발주 받기)·ASN·납기 회신은 이미 그 순서. 발주서 인쇄·엑셀은 상세의 문서 줄(따로) 그대로 |
| ④ 위험 동작 | 구매요청·발주·ASN **취소**, 평가 템플릿 **폐기**를 진행 동작 줄에서 떼어 별도 도구줄(`#dangerBar`, 보일 버튼이 없으면 줄째 숨김). 평가 **취소**·회신 **거절로 기록**은 이미 저장과 떨어진 자리 — 톤만. 인증서 **사용 중지**(행 안 버튼)도 위험 톤. 모두 `hb-btn-danger-outline`(공통 #254) |
| ⑤ 상태 표시 | `hb-status` 배지 — 아래 톤 표 하나(`srm-ui.js` 의 `TONES`). 지연·늦음·납기 지남·과락 = `danger` 배지, 납기 임박 = `warning`. 전에는 빨간 글자(`hb-field-error`·`hb-msg-error`)로만 — 색 하나에 기대지 않게 모양 기호 + 글자 |
| ⑥ 대화상자 | `window.confirm` 24곳 → `hb.dialog`. 19곳은 "저장 안 한 입력 — 이동?"(`srmUi.leaveOk`), 확인 4(발행한 발주 개정·평가 확정 = 일반, 템플릿 폐기·인증서 사용 중지 = 위험 톤 + 기본 포커스 취소), 회신 거절 확인 1은 사유 대화상자에 합침. 사유 입력은 **화면에 늘 떠 있던 사유 칸을 없애고** 누를 때 대화상자로(필수·500자): 구매요청 반려·취소, 발주 취소, ASN 취소, 평가 취소, 회신 거절(설명 + 사유 한 대화상자). 대화상자 취소 = 아무 일 없음(서버 호출 없음). `prompt` 는 SRM 에 없었음 |

**SRM 상태 톤 표**(부록 A 의 규칙을 SRM 코드에 적용 — 색은 정하지 않고 톤만)

| 대상 | 코드 → 톤 |
|---|---|
| 구매요청 `PR_STATUS` | DRAFT neutral ○ / REQUESTED info / APPROVED·CLOSED success ✓ / REJECTED warning ▲(다시 써야 하는 일) / CANCELLED neutral(사용자가 고른 결과라 빨강 아님 — A.3) |
| 발주 `PO_STATUS` | DRAFT neutral / IN_APPROVAL·APPROVED info(볼 것 있음) / ISSUED·ACKED·PARTIAL_RECEIVED normal ●(진행) / RECEIVED·CLOSED success / CANCELLED neutral |
| 납기 회신 `PO_ACK_STATUS` | ACCEPTED success / PARTIAL warning / REJECTED danger ■(납기 위험) |
| ASN `ASN_STATUS` | DRAFT neutral / SENT·PARTIAL_RECEIVED normal / RECEIVED·CLOSED success / CANCELLED neutral — 늦음(도착 예정일 지남)은 따로 danger 배지 |
| 평가 템플릿(`BOM_STATUS` 코드) | DRAFT neutral / RELEASED success / OBSOLETE neutral |
| 협력사 평가 `EVAL_STATUS` | DRAFT neutral / CONFIRMED success / CANCELLED neutral — 과락 danger, 거래 보류 제안 = 거래 상태 HOLD 톤(warning) |
| 통지(아웃박스) | NEW neutral / SENDING info / SENT success / FAIL error ✕(처리 실패 — 다시 보내기 대상) |
| 협력사 거래 상태 `TRADE_STATUS` | ACTIVE normal / HOLD warning / BLOCKED danger — 발주 상세에도 같은 배지 |
| 인증서 판정 | VALID success / DUE_SOON warning / EXPIRED danger / NONE(필수 인증 없음) warning / OFF neutral. 같은 유형의 새 인증서가 있는 **지난 인증서는 OFF 톤**(지금 판단할 대상이 아님) + "지난 것" 글자 |
| 단가 구간 | CURRENT normal / FUTURE info / PAST·OFF neutral |

판단 — 인증서 NONE 을 danger 가 아니라 warning 으로: 필수 인증이 "없음"은 등록을 안 한 것일 수 있어 확인이 먼저(만료 = 기한이 지난 사실이라 danger). 납기 회신 PARTIAL 은 warning(일부 수량·날짜가 요구와 다름 — 확인 필요), REJECTED 는 danger.

부족한 점(공통 — SRM 은 고치지 않음): CMS 와 같음 — 도구줄 안 "오른쪽으로 밀기" 칸이 없어 위험 동작은 줄을 나눈다.

### 5.2 UI 단계 3 ① 주 목록 → hb.grid (2026-10-22, 결정 docs/29 #45 = A안·#48)

**1묶음: 구매요청·발주·ASN.** 기존 목록 API(`GET /api/srm/{prs|pos|asns}`)는 그대로 두고 **추가만**:

| API | 내용 |
|---|---|
| `GET …/grid` | 목록과 **같은 조건 SQL 조각**(`prWhere`·`poWhere`·`asnMatched`, 조건은 `*Crit` getter 클래스 하나) + `page`·`size`(정책 `GRID_PAGE_SIZE_MAX` 이하)·`sort`(최대 3개)·`total` → `{list, total}`. 기간 필수·상한도 목록과 같다(행 수 상한 대신 페이지). `@ProgAuth(SEARCH)` |
| `GET …/columns` | `ColumnSpec`(제목 = i18n 키) — 정렬 가능 열 = 정렬 enum 에 있는 열 |
| 정렬 | 허용 목록 enum(`PrSort`·`PoSort`·`AsnSort`) → 매퍼 `<choose>` 로 열 이름을 고른다(`${}` 없음), 마지막에 PK(`… desc`) — 쪽 경계에서 같은 값 행이 겹치거나 빠지지 않게. `sort` 는 `request.getParameterValues("sort")` 로 받는다(`@RequestParam List` 는 "필드,방향" 을 쉼표에서 쪼갬 → 키 하나가 400) |
| 기본 정렬 | 목록 API 와 같은 순서 — 구매요청 요청일·발주 발주일·ASN 도착 예정일 내림차순(같으면 id 내림차순) |

화면: 주 목록 표 → `hb.grid`(서버 페이징·정렬·열 설정). 상태 칸 = `GridOptions.status` + `srmUi.TONES`(ASN 은 늦음이면 도착 예정일 칸에 danger 배지), 취소·마감 행 = `rowClass` → `hb-tree-dim`, 행 이동 전 미저장 확인 = `beforeSelect: srmUi.leaveOk`(비동기). 상세 행 표(품목 행·발주 행 가져오기 등)는 일반 표 그대로. 건수 칸은 grid 아래 "총 n건" 으로 대신(쓰지 않게 된 문구 키 삭제). 발주 그리드는 개정·통화를 따로 칸으로(전에는 번호·합계 글자에 붙임), ASN 은 남은 행·행 수를 따로.

판단 — 발주 **합계 정렬은 통화를 섞어 숫자 크기로만**: 환산 기준(환율 일자)이 SRM 에 없고 통화 칸이 옆에 보인다. 환산 정렬이 필요하면 환율 원장이 생길 때.

**2묶음: 납기 회신·평가 템플릿·협력사 평가·협력사 정보**(같은 방식 — `…/grid`·`…/columns` 추가, 조건 SQL 조각 `posMatched`·`tmplWhere`·`evalWhere`·`supplierWhere`, 정렬 enum `AckSort`·`TmplSort`·`EvalSort`·`SupplierSort`).

| 화면 | 메모 |
|---|---|
| 납기 회신 `GET /api/srm/acks/pos/grid` | 기본 정렬 = 발주일 내림차순. 회신 기한 정렬 = 발행 시각(기한 = 발행일 + 같은 일수라 순서가 같다). **기한 지남(`overdue`)·회신 기한은 grid 에도 같은 계산**(쪽마다 서비스가 채움) → 회신 기한 칸 danger 배지. 개정·발주일·회신 순번을 따로 칸으로. 검색 조건이 `state`(진행 중/회신 없음·거절/회신 받음/전체)라 "이 값으로 필터" 는 걸지 않음 |
| 평가 템플릿 `GET /api/srm/eval-tmpls/grid` | 기간 조건 없음(목록과 같음). 기본 정렬 = 코드 오름차순, 같은 코드는 PK 내림차순(새 리비전 위). 리비전·항목 수·가중치 합을 따로 칸으로(전에는 한 칸 글자) |
| 협력사 평가 `GET /api/srm/evals/grid` | 기본 정렬 = 기간 끝 내림차순. 점수·등급·과락·거래 제안을 따로 칸으로 — 과락 Y = danger, 거래 제안 = 거래 상태 톤(HOLD warning). 점수·등급은 작성 중이면 비어 정렬 끝에 |
| 협력사 정보 `GET /api/srm/suppliers/grid` | 미등록 구매 거래처 포함(목록과 같음). **인증 판정(`certState`)은 자바 계산**(필수 인증·만료 임박 일수 정책)이라 grid 에서도 쪽마다 계산, 정렬 허용 목록 밖(400). 등록·인증 칸은 화면이 이름으로(`format`), 거래 상태 정렬은 미등록이 끝에. 미등록 거래처의 공급 품목은 0 으로 보인다(전에는 빈칸 — 값은 사실이고 DTO 를 바꾸면 기존 목록·상세 API 도 바뀌어 그대로 둠) |

**3묶음(마지막): 통지 이력·발주 진행 현황** — SRM 9화면 주 목록이 모두 hb.grid.

| 화면 | 메모 |
|---|---|
| 통지 이력 `GET /api/srm/notices/grid` | 조건 `noticeWhere`, 정렬 `NoticeSort`(시각·종류·발주·협력사·상태), 받는 주소는 목록과 같이 가림. 행마다 있던 [다시 보내기] → **도구줄 버튼 하나**(고른 행이 실패일 때만 켜짐) — grid 칸에 버튼을 넣지 않는다. 종류·상태는 화면이 이름으로(`format`), 상태 톤 = 통지 표(FAIL ✕), 실패가 아닌 행은 흐림(볼 것 = 실패). 받는 주소 칸은 표시용 `toAddrText`(없으면 빈 글자) — grid 는 null 칸에 `format` 을 부르지 않아 처음엔 주소 없는 행이 빈칸이었다(2026-10-23 고침, 다시 "(주소 없음)"). API 의 `toAddr` 는 그대로 |
| 발주 진행 현황 `GET /api/srm/po-status/grid` | 조건 `statusMatched`(보기·임박 일수 포함), 정렬 `StatusSort`, 기본 정렬 = 목록과 같은 요구 납기·발주 번호·행, 마지막 PK = 발주 행 id(응답에 `poLineId` 추가 — 목록 API 에도 칸 하나 늘어남, 기존 칸 그대로). 전에는 "상황" 한 칸에 배지 여럿 → **각 칸에 배지**: 요구 납기(지남 danger·임박 warning, 임박 일수 = 검색 패널 값), 약속 끝 날(늦음 danger), 회신 기한(지남 danger), 회신 상태 톤 |
| 스크롤 화면 | 두 화면은 쪽 전체가 스크롤(`hb-page-scroll`)이라 그리드에 높이를 준다 — `srm.css` 의 `.srm-grid-page`(min(70vh, 640px)). 공통 CSS 는 그대로 |

### 5.3 ASN grid 깊은 쪽 — 지연 조인 (2026-10-24, QA offset 조사 M5, 마이그레이션 없음)

QA(docs/qa/2026-10-24_offset-deep-page.md)가 `AsnMapper#findAsnPage` 를 중간 위험으로 짚었다. 조회 기간 안의 모든 ASN 마다 행 수와 덜 받은 행 수(행마다 입고 이력 합)를 계산한 뒤에 정렬·offset 을 했기 때문이다.

**실측**(`AsnGridPerfTest`, `-Dhb.perf=true`). 협력사 하나·발주 행 하나에 ASN(행 1개씩)을 넣고, 기간 = 전체, 쪽 크기 50, 기본 정렬 도착 예정일 desc 로 쟀다.

| 건수 | 쪽 | 고치기 전 | 지연 조인 뒤 |
|---|---|---|---|
| 10만 | 첫 쪽 + 합계 | 158ms | 91ms |
| 10만 | 가운데(1,000) | 381ms | 122ms |
| 10만 | 끝(2,000) | 719ms | 127ms |
| 100만 | 첫 쪽 + 합계 | 501ms | 349ms |
| 100만 | 가운데(10,000) | **3,831ms** | 434ms |
| 100만 | 끝(20,000) | **7,688ms** | 506ms |
| 100만 | 덜 받은 행 수 정렬, 가운데 | — | 4,209ms → 칸(5.6) 뒤 378ms |

**바꾼 것**
- 안쪽 쿼리는 거르기·정렬 칸(번호·협력사 이름·공장·출하일·도착 예정일·상태)만으로 쪽 하나의 `asn_id` 를 고른다. 정렬 마지막 키는 `asn_id desc` 다.
- 바깥은 `cross join lateral` 로 그 쪽(최대 50행)만 행 수·덜 받은 행 수를 계산하고, 같은 키로 다시 정렬한다.
- 조건 조각은 `asnWhere`·`openLineCnt` 로 나눴다. 목록 API(`findAsns`)·합계(`countAsns`)도 같은 조각을 쓰므로 조건이 갈라지지 않는다.

**남은 것**
- 덜 받은 행 수로 **정렬하거나 "늦은 ASN 만"** 을 고르면 안쪽이 후보 전체를 세야 한다(`needOpen`). 100만 건 가운데 쪽이 4.2초다.
- 지금은 두지 않는다. ASN 은 문서 표라 느리게 커지고(하루 수십 건이면 몇 년에 수만 건), 이 정렬은 기본이 아니다.
- → **해결(5.6)**: 게이트가 아니라 트리거로 유지하는 칸 `open_line_cnt`(V185). 같은 정렬이 0.38초다.

**쪽 경계 시험**: `AsnGridPageTest` 다. 작은 쪽(5)으로 끝까지 읽은 순서와 한 번에 읽은 순서가 같고 빠짐·겹침이 없는지 본다.
- 기본 정렬·덜 받은 행 수·협력사+도착 예정일·상태+덜 받은 행 수 정렬, "늦은 ASN 만", 검색어 조건에서 확인한다.
- 기본 정렬 순서와 "늦은 ASN" 집합은 목록 API 와 같다.

### 5.4 통지 grid 깊은 쪽 — 지연 조인 (2026-10-24, QA offset 조사 M4, 마이그레이션 없음)

QA 가 `NotifyMapper#findNoticePage` 를 짚었다: `sys_outbox`(모든 토픽)에서 `payload ->> …` 칸을 행마다 뽑은 뒤 정렬·offset 한다. 상위 지시로 먼저 쟀다(`NotifyGridPerfTest`, `-Dhb.perf=true`). SRM 통지(발주 발행 payload 와 같은 칸, 약 300바이트)를 넣고, 같은 회사의 다른 토픽도 같은 수만큼 넣었다. 기간 301일, 쪽 크기 50.

| SRM 통지 수(+다른 토픽 같은 수) | | 첫 쪽 + 합계 | 가운데 | 끝 | 협력사 정렬 가운데 | 번호 정렬 가운데 |
|---|---|---|---|---|---|---|
| 10만 | 고치기 전 | 147~168ms | 124~148ms | 153~169ms | 135~146ms | 157~179ms |
| 10만 | 지연 조인 뒤 | 95ms | 65ms | 87ms | 100ms | 106ms |
| 100만 | 고치기 전 | 762~874ms | 1,058~1,198ms | 1,073~1,306ms | 1,092~1,211ms | 1,118ms |
| 100만 | 지연 조인 뒤 | 554ms | 433ms | 472ms | 744ms | 895ms |

(API 왕복 시간. 고치기 전은 두 번 실행한 값의 범위)

**계획**(소유자 계정 EXPLAIN ANALYZE, 100만 끝 쪽):
- 지금: 회사 범위 `sys_outbox` 전체(다른 토픽 포함 200만 행)를 병렬 순차 읽기 → 100만 행을 **모든 칸 그대로(행 폭 683)** 정렬, 디스크 정렬 약 135MB → 987ms.
- 지연 조인(안쪽 `outbox_id`·`created_at` 만): 같은 순차 읽기, 정렬 폭 16·디스크 약 25MB → 450ms. 바깥은 PK 로 50행만.
- payload 칸 정렬(협력사)도 지금 모양은 같은 넓은 정렬이다.

**판단**
- offset 깊이 탓은 작다. 첫 쪽도 100만이면 0.8초다. 비용은 (1) 다른 토픽까지 읽는 순차 읽기, (2) 넓은 행 정렬이다. QA 가 걱정한 jsonb TOAST 풀기는 이 payload 크기(약 300바이트, TOAST 기준 2KB 아래)에서는 일어나지 않는다.
- 지연 조인은 (2)만 줄인다(DB 시간 약 절반). 마이그레이션 없이 매퍼만 바꾼다.
- (1)을 줄이려면 `sys_outbox` 에 SRM 토픽 부분 색인(예: `(comp_cd, created_at desc, outbox_id desc) WHERE topic IN (…SRM 3종)`)이 필요하다. 플랫폼 공용 표라 SRM 이 혼자 정할 일이 아니다.
- 규모: 100만 건은 하루 약 3,300건 통지를 1년 쌓은 양이다. 협력사 통지로는 아주 큰 쪽이고, 10만(하루 약 300건)에서는 쪽 위치와 상관없이 0.2초 안이다.
- **결정(상위, 2026-10-24)**: 지연 조인은 지금 한다. 부분 색인은 미룬다(아래).

**바꾼 것**
- `NotifyMapper#findNoticePage`: 안쪽은 거르기 + 정렬 키만으로 쪽 하나의 `outbox_id` 를 고른다(limit/offset). 바깥은 그 행만 PK 로 읽어 칸을 뽑고 같은 키로 다시 정렬한다. 정렬 조각 `noticeOrder` 를 안쪽·바깥이 같이 써서 순서가 갈라지지 않는다. 마지막 키는 `outbox_id desc`.
- 합계(`countNotices`)·목록 API(`findNotices`)는 그대로다. 같은 조건 조각 `noticeWhere` 를 쓴다.
- payload 칸 정렬(협력사·번호)은 안쪽에서도 키를 뽑아야 해서 이득이 작다(100만 가운데 쪽 1.1~1.2초 → 0.7~0.9초).
- 시험: `NotifyGridPageTest` 2건(작은 쪽으로 끝까지 읽은 순서 = 한 번에 읽은 순서, 정렬 5가지 + 상태 조건, 다른 토픽·다른 회사 안 섞임, 기본 정렬 = 목록 API).

**미룬 것 — `sys_outbox` SRM 토픽 부분 색인** (상위 결정 2026-10-24)
- 남은 비용은 회사 범위 `sys_outbox` 전체(다른 토픽 포함)를 읽는 순차 읽기다. 줄이려면 `(comp_cd, created_at desc, outbox_id desc) WHERE topic IN ('SRM_PO_ISSUED', 'SRM_PO_CANCELLED', 'SRM_CLAIM_SENT')` 같은 부분 색인이 필요하다.
- 플랫폼 공용 표라 색인은 시스템관리 결정이 필요하다. 지금 수치로는 급하지 않다: 100만 건 = 하루 약 3,300 통지 × 1년이고, 그래도 0.4~0.9초다. 10만(하루 약 300건)은 0.1초 안팎이다.
- 다시 볼 때: 실제 통지량이 하루 수천 건으로 커지거나, 다른 모듈도 `sys_outbox` 를 토픽별 목록으로 보여 주게 될 때.

### 5.5 grid 정렬 — NOT NULL 키에서 nulls last 빼기 (2026-10-24, QA 정렬 nulls last 가드, 마이그레이션 없음)

QA 가드 `SortNullsLastGuardTest`(표 docs/qa/2026-10-24_sort-nulls-last.md)가 SRM offset 목록 11문장을 짚었다. NOT NULL 키에 `desc nulls last` 가 붙으면 기본 색인 역순(DESC NULLS FIRST)과 맞지 않아 플래너가 색인 순서를 못 쓴다.

- 대상: 발주·회신 현황(`AckMapper` 2)·ASN(안쪽·바깥)·클레임·평가·평가 템플릿·발주·구매 요청·반품·협력사·통지.
- **NOT NULL 키**(번호·협력사 이름·상태·날짜 등): `nulls last` 를 뺐다. NULL 이 없으니 순서는 그대로다.
- **빈 값 있는 키**(발행 시각·회신 상태·약속일·지연 일수·회신 기한·점수·등급·공장 이름·요청자·필요일·거래 상태·공급 품목 수·구매 담당·통지 번호·협력사): `키 is null, 키` 로 바꿨다. 오름차순·내림차순 모두 빈 값이 끝이라 순서가 그대로다.
  - 가드는 `nulls last` 글자가 든 `foreach` 의 NOT NULL 키를 모두 잡는다(키별로 붙여도 잡힌다 — WorkCalMapper 가 아직 목록에 있는 이유). 그래서 글자 대신 이 꼴을 썼다.
- 개수 정렬(`count(*)`·덜 받은 행 수)은 NULL 이 없어 그대로 뒀다.
- 가드 허용 목록에서 SRM 11줄을 지웠다.
- 시험: 기존 grid·쪽 경계 시험 그대로 통과, `NotifyGridPageTest` 에 "빈 값 있는 키는 오름·내림 모두 빈 값이 끝" 1건.

### 5.6 ASN 덜 받은 행 수 칸 — 트리거 유지 (2026-10-24, QA offset M5 남은 것, V185·임시 V9513)

5.3 의 지연 조인 뒤에도 "덜 받은 행 수로 정렬" 과 "늦은 ASN 만" 은 100만 건에서 약 4.2초다. 그 값을 ASN 행마다 입고 이력 합으로 계산하기 때문이다. 값을 `srm_asn.open_line_cnt` 칸에 두고 **트리거로만** 유지한다. 앱 코드는 이 칸을 쓰지 않는다(QMS H3/H5 와 같은 방식).

**값의 정의**: ASN 행 중 `qty > Σ srm_po_receipt.qty(그 asn_line_id)` 인 행 수. 입고와 되돌림 모두 이력 합에 들어간다. 지금 매퍼 조각 `openLineCnt` 와 같다.

**다시 세는 때**(모두 같은 함수 `hb_srm_asn_open_refresh(asn_id)` — 그 ASN 행만 센다)
- `srm_po_receipt` INSERT 중 `asn_line_id` 가 있는 것: 입고와 되돌림. 이력은 추가만 되고, LOT 붙이기(`lot_id`)는 수량과 무관하다.
- `srm_asn_line` INSERT·DELETE·`qty` UPDATE. 작성 중에만 바뀐다(줄 트리거가 막는다).
- ASN 상태 전이는 값과 무관하다. "늦음" 은 상태 + 칸 + 도착 예정일로 따로 거른다.

**잠금 순서**(ASN 머리 행 하나로 직렬화)
- 다시 세는 함수는 먼저 `srm_asn` 그 행을 `FOR UPDATE` 로 잠그고, **그다음 문장에서** 센다.
- READ COMMITTED 에서 함수 안 문장마다 새 스냅숏이라, 잠금을 기다린 쪽은 앞 트랜잭션이 커밋한 입고까지 보고 센다. 한 문장(`UPDATE … SET = (부질의)`)으로 하면 기다리기 전 스냅숏으로 세어 앞 입고를 놓친다.
- 입고 게이트는 이미 발주 머리 → 발주 행 → ASN 머리(`lockAsn`) → 이력 INSERT 순서다. 트리거는 이미 잡은 ASN 잠금을 다시 잡을 뿐이라 순서가 바뀌지 않는다.
- 게이트를 거치지 않은 직접 INSERT 도 트리거가 같은 잠금을 잡는다. 이력 행 INSERT 뒤 ASN 잠금이므로 게이트(ASN 잠금 → INSERT)와 엇갈려 기다리는 고리가 생기지 않는다.

**칸 보호**: `hb_guard_srm_asn` 이 막는다. 만들 때는 0 이어야 하고, 값 변경은 트리거 안(`pg_trigger_depth() >= 2`)에서만 받는다. 앱·직접 SQL 의 UPDATE 는 `ck_srm_asn_open_cnt` 로 거부한다. 선례는 V048 `inv_stock`(원장 투영)이다.

**기존 행 채우기**: 마이그레이션에서 회사마다 범위를 열어 센다(선례 V109·V178). 칸 보호 트리거를 바꾸기 **전에** 채운다(바꾼 뒤면 마이그레이션 UPDATE 가 거부된다).

**바뀌는 것**
- `AsnMapper` 의 `openLineCnt` 조각이 `a.open_line_cnt` 칸을 읽는다. 정렬·"늦은 ASN 만" 거름도 칸으로 한다.
- 입고마다 `srm_asn` 감사 행이 하나 더 생긴다(값이 바뀔 때만 UPDATE).
- 포털·`AsnQuery`(작은 집합)는 그대로 계산한다. 칸과 같은 정의라 시험으로 대사한다.

**시험**(`AsnOpenLineCntTest` 4건)
- 무작위 흐름(ASN 8건 만들기·줄 고치기·확정, 입고·되돌림 60걸음, 고정 씨앗) 뒤 칸 = 이력으로 다시 센 값(전체 비교). grid 의 덜 받은 행 수 정렬이 칸 순서와 같다.
- 동시 입고 2건(같은 ASN 다른 줄, 게이트 없이 직접 INSERT): 뒤 트랜잭션이 잠금 대기에 들어가고, 끝나면 칸 0 = 다시 센 값.
  - 대조: 함수를 "한 문장 UPDATE … = (세기)" 로 바꾸면 이 시험이 1(앞 입고를 놓침)로 실패하는 것을 확인했다.
- 칸 직접 고치기(UPDATE)·0 아닌 값으로 만들기 → `ck_srm_asn_open_cnt`. 작성 중 줄 추가·삭제(직접 SQL)도 다시 센다.
- A사로 B사: B사로 A사 ASN 줄에 입고 → 실패, A사 칸 그대로, 상세 404.

**실측**(`AsnGridPerfTest`, `-Dhb.perf=true`, ASN 반은 보냄, 행 1개씩, 받은 것 없음 — 같은 시험을 main 과 이 변경에서 돌림)

| 건수 | | 고치기 전 | 칸 뒤 |
|---|---|---|---|
| 10만 | 덜 받은 행 수 정렬, 가운데 | 328ms | 79ms |
| 10만 | 늦은 ASN 만, 첫 쪽 + 합계 | 22,252ms | 77ms |
| 100만 | 덜 받은 행 수 정렬, 가운데 | 4,227ms | 378ms |
| 100만 | 늦은 ASN 만, 첫 쪽 + 합계 | 6,722ms | 265ms |
| 100만 | 기본 정렬 첫·가운데·끝 | 305 / 384 / 461ms | 365 / 423 / 460ms(같은 수준) |

- 10만 "늦은 ASN 만" 22초는 고치기 전 계획이 후보마다 행 부질의를 되풀이한 탓으로 보인다(추정 — 계획은 남기지 않음). 칸 뒤에는 단순 거름이다.
- 넣기 시간은 시험 준비(트리거 끈 일괄 넣기 + 칸 채우기)라 비교 대상이 아니다.

### 5.7 덜 받은 행 수 — 남은 읽기 두 곳도 칸으로 + 열린 ASN 부분 색인 (2026-10-24, V189·임시 V9514)

5.6 뒤에도 WMS 도착 예정 목록(`AsnQueryMapper#expected`)·스캔 찾기(`findByKey`)·포털 ASN 목록(`PortalSrmMapper` asnCols)은 덜 받은 행 수를 행마다 이력 합으로 셌다. 같은 정의라 칸(`open_line_cnt`)을 읽게 바꿨다. 계약 응답 값(WMS 가 받는 `AsnHead.openLineCnt`)은 그대로다.

- `expected` 는 기간 조건이 없을 수 있어 공장의 열린 ASN 전체를 셌다. 칸으로 거르면(`open_line_cnt > 0`) 기존 색인 `ix_srm_asn_eta` 로는 다 받은 행을 거의 다 지나야 한다. 그래서 열린 ASN 만 담는 **부분 색인** `ix_srm_asn_open (comp_cd, plant_cd, eta_date, asn_no) WHERE status IN ('SENT','PARTIAL_RECEIVED') AND open_line_cnt > 0` 을 두었다(V189·임시 V9514).
  - 다 받은·마감 ASN 은 색인에서 빠지므로, 쌓여도 색인 크기가 늘지 않는다.
- **실측**(`AsnExpectedPerfTest`, `-Dhb.perf=true`): 한 공장 ASN 100만 건, 최근 약 15일 것(1.5%, 14,850건)만 열림 — 실제처럼 열린 것이 최근에 몰린 모양이다. limit 500.

  | | 계획 | 실행 시간 |
  |---|---|---|
  | 고치기 전(행마다 이력 합) | `ix_srm_asn_eta` 순서로 읽으며 98.5만 행을 거름 + 남은 행마다 부질의 | 1,090 ~ 9,814ms(캐시 상태에 따라) |
  | 칸만(색인 그대로) | 병렬 순차 읽기 100만 행 → 정렬 | 300ms |
  | 칸 + 부분 색인(V189·임시 V9514) | `ix_srm_asn_open` 을 순서대로 500행 | 2.2 ~ 2.9ms |

  계약 경로(앱 계정·RLS·매핑 포함) `expected("P1")` 500건은 154~217ms 다(첫 호출 포함).
- 시험: `AsnOpenLineCntTest` 무작위 흐름 뒤 "계약 `expected` 가 고른 ASN = 보냄·입고 중 + 다시 센 값 > 0, 값 = 다시 센 값" 대사를 더했다. WMS `AsnReceiptTest`·포털 시험은 그대로 통과한다.

## 6. 경계 — 다른 모듈과, 병행 작업과

| 대상 | SRM 1부에서 | 넘기는 곳 |
|---|---|---|
| 입고·재고·창고 위치 | 발주 행에 납품 창고(WH)만. 입고 수량은 세지 않음, `PoOpenQty` 는 입고 0 | P4 WMS 입고 원장이 `po_line_id` 를 들고 2부에서 확장 — **2부 R1(4.9): 게이트 `PoReceiptGate` + `recv_qty` 투영 ✅, WMS B4b 대기** |
| 수입검사 | 하지 않음. 검사계획 `RECEIVE` 유형은 QMS 1부(29 Q1) | P3 2부 — 불합격 → 반품·협력사 클레임(29 Q4 `qms_capa` 출처 SUPPLIER) |
| 납품 LOT·라벨·ASN | 하지 않음 | 2부(SRM) + P2 `lot_master.supplier_lot_no` — **설계안 4.11(R3)** |
| 수주·MRP 소요 → PR 자동 생성 | 하지 않음(PR 은 사람이) | 9장 |
| 대금·세금계산서·전표·원가 | 하지 않음(금액은 발주서 표기용) | ERP(07 3.1), 매입 확정 송신은 2부 |
| 협력사 클레임·8D | 하지 않음 | P3 QMS(29 Q4) |
| 거래처 기본정보 | 읽기만 | P1 거래처관리(ERP 주인일 수 있음) |

병행 규칙(29 6장 그대로):

| 대상 | 규칙 |
|---|---|
| Flyway `V###` | 이 브랜치(`claude/p5-srm-75e463`)에서는 다음 번호로 개발, **main 에 늦게 들어가는 쪽이 병합 직전 번호를 다시 매긴다**. 로컬 DB 는 `docker compose down -v` 로 초기화 |
| `messages*.properties`·`HbErrorCode`·`Policies`·`DemoDataSeeder`·`sys_prog`·메뉴 시드 | 끝에 줄 추가, 키는 `srm.`/`js.srm.` 접두어로 모은다. 병합 때 양쪽 다 남김 |
| 공통 JS(`hb.*`)·CSS·`build.gradle`·잠금 파일 | **고치지 않는다** — 필요하면 화면 JS 안에서 해결, 공통화·의존성 추가는 병합 뒤(8장 D7: 서버 PDF 라이브러리를 지금 넣지 않는 이유 중 하나 = 잠금·체크섬 파일 충돌) |
| 다른 모듈 테이블(`prd_*`·`lot_*`·`sal_*`·`qms_*`·`wms_*`·`inv_*`) | 1부는 읽지도 쓰지도 않는다 |
| P1 테이블 | 고치는 것은 `mst_ext_id_map` 대상 유형 `PO` 추가 하나(CHECK `ck_mst_ext_id_map_entity` 교체 + V026 소속 확인 트리거에 `PO` 분기, 새 V 파일) — `SRM_PO_SOURCE=ERP` 를 구현할 때만. 공통코드 그룹 `CURRENCY`(시스템, `attrs.scale`)·`PAY_TERMS`·`SUPPLIER_CERT`(회사 확장)는 새로 추가(기존 마이그레이션에 없음 확인) |
| WMS 세션과 약속 | WMS 입고는 `srm_po_line.po_line_id`(nullable)를 참조할 수 있게 한다는 것만 공유 노트로 알린다 — 테이블은 병합 순서와 무관하게 2부에서 연결 |

## 7. 진행

| 단계 | 상태 | 날짜 | 테스트 |
|---|---|---|---|
| S1 협력사 정보(V043) | ✅ | 2026-10-20 | 720건, 신규 SupplierTest 8. 브라우저 1366·390(전용 DB·앱), 상세 표 품목 칸 코드만으로 수정 |
| S2 구매요청(V044) | ✅ | 2026-10-20 | 725건, 신규 PrTest 5. 브라우저 1366, 행 입력을 표 → 행별 작은 폼으로 수정 |
| S3 발주·결재·개정(V045) | ✅ | 2026-10-20 | 737건, 신규 PoTest 8 — 동시성 결함 1건 발견·수정(4.3) |
| S4 발주서·통지(V046) | ✅ | 2026-10-20 | 737건, 신규 PoNoticeDocumentTest 4. 브라우저(인쇄 화면 hidden 결함 수정) |
| S3b ERP 발주 받기(V089) | ✅ | 2026-10-21 | 1,452건, 신규 ErpPoImportTest 4. 브라우저 1366(ZT, 엑셀 대화상자로 받기) — ERP 발주에 [개정] 버튼이 보이던 것 숨김 |
| S5 납기 회신·진행 현황(V062) | ✅ | 2026-10-21 | 1,156건, 신규 AckTest 8. 브라우저 1366·390(전용 DB 5442, ZT 데이터) — 거절 사유 칸이 폼 밖이라 null 결함 1건 수정 |
| S6 협력사 평가(V068) | ✅ | 2026-10-21 | 1,164건, 신규 EvalTest 5 + EvalScorerTest 3. 브라우저 1366·390(ZT) — 작성 중 템플릿 폐기가 CHECK 에 걸리던 결함(시험으로 발견)·지표 코드 노출 수정 |
| 2부 R1 구매 입고 게이트(V118(임시 V9503)) | ✅ (SRM 쪽, WMS B4b 대기) | 2026-10-22 | 1,653건, 신규 PoReceiptGateTest 7(상위 검토로 기본 단위 자릿수 1 추가) — PL/pgSQL `IF CASE … THEN` 함정·상태 칸 길이 결함 2건 시험으로 발견·수정. 브라우저 1366·390(ZT, 입고 상태는 SQL) — 진행 현황 390 가로 넘침 수정. WMS 쪽은 4.9 초안 |
| 2부 R2 입고 이력·납기 준수율 OTD_RATE(V128(임시 V9504)) | ✅ | 2026-10-22 | 1,731건, 신규 PoReceiptLogTest 2 + WMS PoReceiptTest 1(호출부 날짜) + PoReceiptGateTest 직접 SQL 보강, 보존 클래스 등록 누락 1건 가드 시험으로 발견·수정. RECV_PPM 은 QMS 계약 초안(4.10) |
| 2부 R3 ASN 설계안(4.11, 문서) | ✅ 확정(상위 판단) | 2026-10-22 | 코드 없음 — 결정 R3-1~8(WMS 검토 반영: 스캔 키 `ASN:` 만, 되돌림 `reversesRef`, 한도는 등록 때만) |
| 2부 R3a ASN 구현(4.11.8, V133(임시 V9505)) | ✅ (SRM 쪽, WMS R3b 대기) | 2026-10-22 | 1,772건(JDK 21), 신규 AsnTest 6. 브라우저 1366·390(ZT) — 목록 기본 기간에 미래 도착 ASN 이 빠지던 결함 수정 |

## 8. 결정 필요 (사용자)

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D1 | **ERP 가 이미 있는 고객에서 SRM 의 위치** | **정책 `SRM_PO_SOURCE` = SRM(기본) / ERP.** SRM 이면 PR·PO 의 주인, ERP 로는 2부에서 발주·입고를 송신. ERP 이면 발주는 ERP 에서 받아(1부: 엑셀 "ERP 발주 받기" 템플릿, 실제 어댑터는 P6) ERP 주인 칸을 잠그고, **회신·통지·평가만 SRM**. 대금·전표는 항상 ERP. `ERP_WO_SOURCE`(07 5.6)·`ERP_ITEM_SYNC_MODE`(22) 와 같은 모양 | (가) 항상 SRM 이 발주 주인 — ERP 구매 쓰는 고객은 이중 입력. (나) SRM 은 평가·회신만(발주는 언제나 ERP) — ERP 없는 20~100인 고객(06 M1)이 발주를 엑셀로 계속 |
| D2 | 협력사 마스터 | **P1 거래처 + 1:1 확장 `srm_supplier`** — 거래처 기본정보는 ERP 주인일 수 있어 SRM 칸을 분리 | 거래처 테이블에 SRM 칸 추가 — P1 화면·엑셀 올리기·ERP 잠금 목록을 고쳐야 하고 P2 병행 중 충돌 |
| D3 | 단가 관리 | **간이 단가표 `srm_price`(협력사 × 품목 × 통화 × 기간) + 발주 행 단가 스냅샷**, 금액은 표기용(세금·대금 없음). 단가 보기 권한 분리는 하지 않음(발주 화면 권한 = 단가 보기) | 단가 없이 수량만 — 발주서에 금액이 없으면 실무에서 못 씀 / 단가 보기 별도 액션 — P0 권한 모델에 "열 권한" 개념을 새로 넣어야 함 |
| D4 | 협력사 포털(외부 로그인) | **1부 안 함.** 회신은 내부 대리 입력 + 엑셀 회신 올리기, 통지는 아웃박스. 포털은 2부 뒤 별도 설계(협력사 사용자 유형·회사 범위·RLS 정책 모양이 새로 필요) | 1부에 포털 — 인증·격리 경계를 새로 여는 일이라 1부 규모를 넘는다 / 서명 링크(로그인 없이 회신) — 링크 유출·만료·재사용 처리를 지금 설계해야 함 |
| D5 | PR 승인 방식 | **APPROVE 권한 단순 승인**(요청자 본인 승인 금지 정책), PR 없이 발주 허용(`SRM_PR_REQUIRED=N`) | PR 도 결재 — 소규모 고객엔 단계가 과함 |
| D6 | 발주 상태·결재 | **DRAFT → IN_APPROVAL → APPROVED → ISSUED(통지) → ACKED(회신) → (2부: PARTIAL_RECEIVED → RECEIVED) → CLOSED, 어디서든 CANCELLED(2부: 입고 0 일 때만)**. 결재 `SRM_PO`, 정책 `SRM_PO_APPROVAL`(Y/N) + `SRM_PO_APPROVAL_MIN_AMT`(이 금액 미만은 결재 없이 APPROVED). 발행 뒤 변경 = 개정(rev + 1, 다시 결재·발행) | 결재 없이 APPROVE 권한만 — 단순하지만 P0 결재의 두 번째 업무 사례를 놓친다 / 개정 대신 취소 후 새 발주 — 협력사 쪽 번호가 바뀌고 회신 이력이 끊긴다 |
| D7 | 발주서 PDF | **1부 = 서식 엑셀(POI) + 인쇄 전용 화면(브라우저 "PDF 로 저장")**. 서버 PDF 라이브러리는 P2 7단계(감사 대응 보고서 PDF)와 한 번에 고른다 — 지금 넣으면 병행 브랜치끼리 `build.gradle`·잠금·체크섬 충돌, 한글 글꼴(OFL) 반입도 함께 정해야 함 | 지금 서버 PDF(Apache PDFBox 2.0 Apache-2.0 / OpenPDF LGPL·MPL) — 첨부로 메일에 실을 수 있지만 위 충돌 |
| D8 | 통지 채널 | **1부 송신기 = 로그 + "가짜 메일함"(테스트·데모에서 보낸 내용 확인)**, 실제 SMTP 는 메일 공통 채널 결정 때(알림·결재 알림과 같이) | 지금 `spring-boot-starter-mail` + 로컬 Mailpit — 데모가 실감나지만 의존성 추가(D7 과 같은 충돌)와 발송 계정 관리 |
| D9 | 메뉴 | **새 상위 메뉴 "구매"**(협력사 정보·구매요청·발주·회신·진행 현황·평가). 거래처관리는 기준정보에 그대로 | 기준정보 아래 — 업무 화면과 기준정보가 섞임 |
| D10 | 평가 템플릿 | **리비전 둠(BOM 규칙), 항목 = 배점 × 가중치 + 과락 + 등급 구간, AUTO 지표 키 4종**(1부 계산 2 + 2부 2). SQ 평가처럼 고객(완성차)이 정한 양식을 그대로 옮길 수 있게 구역·안내 문구 자유 | 고정 항목(QCD 3개) — 단순하지만 평가시트 리비전 방식·SQ 요구(02 2.3)와 안 맞음 |
| D11 | 평가 결과 → 거래 상태 | **제안만**(D 등급·과락 → "HOLD 제안" 배지, 사람이 협력사 정보에서 바꿈) — AI·자동 판정은 제안·사람 확정(SKILL 1장 AI 원칙과 같은 태도) | 자동 HOLD — 발주가 갑자기 막혀 현장 혼선 |

## 9. 하지 않을 것 (1부)

- 입고·수입검사·납품 LOT·ASN·라벨·반품, 평가 자동 지표 중 납기 준수율·입고 PPM — **2부**
- 협력사 포털·외부 로그인·서명 링크(D4)
- MRP·소요량 계산으로 PR 자동 생성, 발주 제안(BOM 전개 × 재고 × 리드타임) — 재고(P4)·지시(P2) 뒤에 별도 판단
- 견적 요청(RFQ)·입찰·계약 관리, 다통화 환율·세금 계산·대금 지급 — ERP 영역(07 3.1)
- 서버 PDF·실제 메일 발송(D7·D8), 협력사 신용 평가·외부 데이터 연동
- 공통 JS·CSS·의존성 변경(6장 병행 규칙)

## 10. 검토 결과 (원래 세션이 작성)

2026-10-20 사용자: "추천대로 D1~D11 진행".

| # | 결정 | 반영 위치 |
|---|---|---|
| D1 | 정책 `SRM_PO_SOURCE` = SRM(기본) / ERP. ERP 면 발주는 받아서 ERP 주인 칸 잠금, 회신·통지·평가만 SRM. 대금·전표는 항상 ERP | 1장, 3장 ERP 경계, S3 |
| D2 | 협력사 = P1 거래처 + 1:1 확장 `srm_supplier` | 4장 S1 |
| D3 | 간이 단가표 `srm_price` + 발주 행 단가 스냅샷, 금액은 표기용, 단가 보기 권한 분리 없음 | 3장 권한, 4장 S1·S3 |
| D4 | 협력사 포털 1부 안 함 — 내부 대리 입력 + 엑셀 회신 올리기 | 2장 S5, 9장 |
| D5 | PR = APPROVE 권한 단순 승인(본인 승인 금지 정책), PR 없이 발주 허용 | 2장 S2, 3장 정책 |
| D6 | 발주 상태 DRAFT → IN_APPROVAL → APPROVED → ISSUED → ACKED → (2부 입고) → CLOSED / CANCELLED, 결재 `SRM_PO` + 금액 기준, 발행 뒤 변경 = 개정 | 2장 S3 |
| D7 | 발주서 = 서식 엑셀 + 인쇄 전용 화면, 서버 PDF 는 P2 7단계와 함께 | 2장 S4, 9장 |
| D8 | 통지 송신기 = 로그 + 가짜 메일함, 실제 SMTP 는 메일 공통 채널 결정 때 | 2장 S4 |
| D9 | 새 상위 메뉴 "구매" | 5장 |
| D10 | 평가 템플릿 리비전(BOM 규칙) + 배점·가중치·과락·등급 구간 + AUTO 지표 4종(1부 계산 2) | 2장 S6, 4장 |
| D11 | 평가 결과 → 거래 상태는 제안만(사람 확정) | 2장 S6 |

## 11. 데모 둘러보기 (2026-11-02, `SrmDemoSeeder`·`SrmDemoFlow`, 마이그레이션 없음)

local·demo 프로필, 회사에 발주가 하나도 없을 때만 회사 관리자 이름으로 한 번 넣는다(한 트랜잭션). 기준정보·MES(`DemoDataSeeder`) → QMS·WMS·CMS·PMS 시더 뒤(`DemoSeedOrder.SRM`). 날짜는 오늘 기준 지난 60일(시더 스레드 전용 시계 — 발주일·회신일·입고 발생 시각·채번 연월이 그날, DB now() 칸인 발행 시각·감사 칸은 실제 넣은 시각). 데모 회사에 `SRM_PO_APPROVAL_MIN_AMT` 회사 값이 없으면 3천만 원으로 둔다.

| 화면 | 무엇을 보면 되나 | 데모 데이터 |
|---|---|---|
| 협력사 `srm-supplier` | 등록 협력사·인증서 만료 임박 표시·공급 품목·단가 | P001 단단강판·P002 바름코일센터·P003 이음볼트(인증서 30일 뒤 만료)·P007 별빛용접재료 |
| 발주 `srm-po` | 상태 전부 — 작성 중(3천만 원 넘어 결재 상신 대기)·발행·회신·입고 중·입고 완료·마감 | 17건(-58일 ~ -1일). 마감 3 · 입고 완료 9 · 부분 입고 1(너트 80%) · 회신 2 · 발행 1 · 작성 중 1 |
| 납기 회신 `srm-ack`·진행 현황 `srm-po-status` | 약속 납기를 늦춘 회신(LATE 경고), 회신 안 온 발주, 납기 지난 열린 행 | 회신 15건(1건은 약속 4일 늦춤), -6일 발주는 납기 지남·미입고 |
| ASN `srm-asn` · 홈 "늦은 ASN" 카드 | 코일 협력사 ASN → 입고에서 ASN 행 고르기, 도착 예정일 지난 ASN | 8건, 1건(-12일 발주)은 예정일 지나도 미도착 |
| 자재 입고 `wms-rcv`·재고 원장 | 구매 입고가 SRM 게이트·재고 원장·LOT 한 길로, 발생 시각 = 지난 입고일 | RCV_PO 16행, 늦은 입고 3건(OTD 지표) |
| 클레임 `srm-claim` | NCR 근거 클레임: 보내기 → 회신 → 종결 / 비용 청구 회신 대기(회신 기한 지남) | 2건 — P002 코일 녹(종결) · P003 너트 나사산(350,000원 청구, 회신 대기). 근거 NCR 2건(QMS, LOT 보류) |
| 평가 템플릿 `srm-eval-tmpl`·평가 `srm-eval` | AUTO 지표(OTD·회신율·입고 PPM 구간·인증 유효) + 수동 협조도, 과락·HOLD 제안 | 템플릿 SQ-BASE 확정 1 · 평가 확정 2(P001·P002) · 작성 중 1(P003) |

- 결재: 큰 발주 하나는 작성 중으로 남긴다 — 결재 흐름은 화면에서 직접(결재자는 다른 사용자여야 한다, `APR_SELF_APPROVE_ALLOWED`).
- 시험 `SrmDemoSeederTest`: 흐름·상태 분포·지난 날짜·원장 두 시각, 다시 돌려도 그대로, 기준정보 없으면 아무것도 안 함, A사로 B사. 순서 가드 `DemoSeedOrderGuardTest`.
- demo 프로필은 회사 관리자 계정이 있을 때만 돈다(PMS 시더와 같음).
