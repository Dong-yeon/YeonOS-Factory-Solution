# 39. PLM 설계 — 설계 변경(ECR → ECO)·영향 대상·유효일·도면 첨부

작성 2026-10-20 (PLM 하위 세션, docs/29). 마이그레이션은 **V050**(임시 V9900 → 병합 때 재번호).
근거: **docs/35 A11**(빈 곳 분석 — "PLM 없음(설계변경·도면)", 사업계획서 PLM·KS X 9003 제품개발 빈칸: ECN 머리 + 영향 대상(품목·BOM·라우팅·검사계획) + 적용 조건(날짜·LOT·재고 소진) + 결재, 4M 과 같은 `change_id`), docs/17 P1-3(품목에는 리비전 없음 — 설계 변경 = BOM·라우팅·도면 리비전), docs/18 3장 C1~C6(새 리비전 → 확정, 변경 번호·사유 칸, ECO 와 묶어 감사, 날짜 유효 교체)·5.1(유효 BOM = 기간 겹침 금지 확정본 1건), docs/20(라우팅 리비전 규칙 = BOM 그대로, `change_no`·`change_reason`), docs/02 2.3(변경 관리 `qms_change`, 고객 승인 전 변경 LOT 출하 금지 게이트, PPAP 18요소 P6), docs/25 4.5(`lot_genealogy.change_id` = 변경 연결 자리, P3), 01 5.4(결재 엔진 `ApprovalDocType`)·6단계(첨부 `FileService`). 결재·첨부 쓰는 모양은 SRM 발주(docs/31 4.3 `SRM_PO` — 핸들러가 서비스를 주입하지 않음, 반려·회수 = 작성 중)·PMS 게이트(docs/32 `PMS_GATE`, 첨부 `pms-project`)와 같게 맞췄다.
P0 플랫폼 규약(01)·P1 공통 규칙(17 2장)은 그대로 따른다 — 이 문서는 **PLM 에서 새로 정하는 것**만 적는다.

---

## 1. 결론 먼저

- PLM 1단계는 **"바꾸자는 요청(ECR) → 무엇을 언제부터 바꾸는지 정한 지시(ECO) → 결재 → 적용"** 의 기록과 통제다. 설계 데이터(BOM·라우팅)는 P1 표를 그대로 쓰고, PLM 은 그 위에 **변경 건**을 얹는다.
- **BOM·라우팅의 리비전·확정 흐름은 바꾸지 않는다.** ECO 의 영향 대상은 "작성 중(DRAFT) 리비전"이고, ECO 적용 = 그 리비전에 ECO 번호·유효일을 적고 **기존 `BomService.release`·`RoutingService.release` 를 그대로 호출**한다(확정 검사·이전 확정본 자동 마감·기간 겹침 금지가 모두 그대로 작동). → 남의 표 **스키마 변경 없음**.
- **적용 시점 = 유효일**. 적용(확정)은 결재 직후 해도 되고, 새 리비전의 `valid_from` 이 유효일이므로 그날부터 전개·작업지시 발행이 새 리비전을 고른다(docs/18 5.1). 스케줄러가 필요 없다.
- **결재는 플랫폼 결재 재사용**(`ApprovalDocType` = `PLM_ECO`). 승인 = ECO 승인됨, 반려·회수 = 작성 중으로 되돌림. 적용은 결재와 **별도 동작**(적용 실패가 결재 처리를 롤백시키지 않게 — 결정 D2).
- **결재 뒤 대상이 바뀌면 적용 거부**: 상신 때 대상 리비전의 `row_ver` 를 ECO 에 고정하고, 적용 때 다르면 409(`PLM_TARGET_CHANGED`) — "결재받은 내용 ≠ 확정된 내용" 을 막는다. BOM 행 저장·일괄 대체가 머리 `row_ver` 를 올리는 P1 규칙(SKILL BOM)에 기대는 것.
- **고객 승인 게이트**(02 2.3): ECO 에 "고객 승인 필요" 를 켜면 고객 승인 기록(승인 번호·날짜·첨부) 전에는 적용할 수 없다. PPAP 패키지는 P6 — 1단계는 이 게이트와 첨부 자리만.
- **도면·문서 = 플랫폼 첨부 재사용**: 대상 화면 `plm-eco`·`plm-ecr` + 문서 id. 권한은 화면 기준(조회 SEARCH, 올리기·삭제 SAVE), 확장자·크기·시그니처 검사는 `FileService` 그대로.

## 2. 범위와 단계

| 단계 | 범위 | 화면 | 완료 기준 |
|---|---|---|---|
| **1** (이번) | ECR(요청·접수/반려/취소) · ECO(작성·영향 대상·결재 상신·적용·취소) · 고객 승인 기록 · 첨부 | `plm-ecr` 설계 변경 요청, `plm-eco` 설계 변경 지시 | 결재 승인 → 적용 → 대상 BOM·라우팅이 유효일부터 유효, 결재 뒤 대상 변경 거부, A사로 B사 대상 거부 |
| 2 | 도면 = **QMS Q3 관리문서 리비전(`mst_doc`·`mst_doc_rev`, docs/32 표가 가리키는 QMS 소유) 재사용** — 문서 유형 DRAWING, ECO 대상 유형 DOC 가 작성 중 문서 리비전을 가리키고 적용 때 그 리비전 배포(결정 D8). 품목 변경 이력 보기(ECO 목록). 검사계획 리비전 대상(docs/35 A11) | 품목 변경 이력 | QMS Q3 병합 뒤. PLM 이 도면 표를 따로 만들지 않는다 |
| 3 | 변경 연결: `lot_genealogy.change_id`·작업지시 → ECO(결정 D1), 변경 후 첫 LOT·초품 연결(QMS 4M 과 경계 확정 뒤) · 적용 잠금 정책(`PLM_ECO_REQUIRED` — ECO 없는 BOM·라우팅 확정 금지, 결정 D4) | ECO 상세 "적용 뒤" 탭 | MES·QMS 세션과 경계 합의(남의 표 변경) |
| 4 | PPAP 패키지(02 2.3 P6) · 고객 통지(PCN) · ECO 일괄 대체 연동(docs/18 C5 결과를 ECO 대상으로) · 적용 조건 LOT·재고 소진(docs/35 A11 — 1단계는 날짜만) | PPAP | 재고 소진 조건은 P4 재고 원장 위 |

## 3. 데이터 모델 (1단계, V050)

```text
plm_ecr       설계 변경 요청 : ecr_id, comp_cd, ecr_no(채번 PLM_ECR), title, reason_cd(PLM_CHANGE_REASON, 회사 추가 가능),
              description, item_id(주 품목, 선택), requester_id(sys_user), status(OPEN/ACCEPTED/REJECTED/CANCELLED),
              eco_id(접수된 ECO), decided_at, decided_by, decision_comment, row_ver, 감사 4칸
plm_eco       설계 변경 지시 : eco_id, comp_cd, eco_no(채번 PLM_ECO), title, reason_cd, description, effective_date(유효일),
              owner_id(담당 sys_user), cust_approval_req_yn, cust_id(고객 거래처), cust_approval_status(NA/PENDING/APPROVED/REJECTED),
              cust_approval_ref(고객 승인 번호), cust_approved_on, status(DRAFT/PENDING/APPROVED/RELEASED/CANCELLED),
              apr_id(마지막 결재 요청), submitted_at, approved_at, released_at, released_by, cancel_reason, row_ver, 감사
plm_eco_target 영향 대상 : target_id, comp_cd, eco_id, seq, target_type(ITEM/BOM/ROUTING), item_id(필수),
              bom_id | routing_id(유형에 맞게 하나), change_desc(무엇을 바꾸나), base_row_ver(상신 때 고정), row_ver, 감사
```

| 규칙 | 어디서 | 이유 |
|---|---|---|
| 회사별 RLS + FORCE + `tenant_isolation`, `tg_audit`, 보존 MASTER, 머리 2개는 DELETE 회수 | V050 | P1 기준정보 공통 |
| ECO 상태 전이 표 하나 `DRAFT→PENDING→APPROVED→RELEASED`, `PENDING→DRAFT`(반려·회수), `DRAFT·APPROVED→CANCELLED`(사유) | 트리거 `ck_plm_eco_transition` | 직접 SQL 로도 건너뛰기 불가 |
| ECO 내용(제목·유효일·고객·대상)은 **DRAFT 일 때만** 수정 | 트리거 `ck_plm_eco_fixed`·`ck_plm_eco_target_fixed` | 결재받은 내용 고정. 고객 승인 기록 칸만 PENDING·APPROVED 에서 허용 |
| 대상의 BOM·라우팅·품목은 같은 회사, BOM·라우팅의 품목 = 대상 품목 | 트리거 `fk_plm_eco_target_comp`·`ck_plm_eco_target_item` | 회사별 FK 는 RLS 무시(SKILL 화면·권한) |
| 한 작성 중 리비전은 **열린 ECO(DRAFT·PENDING·APPROVED) 하나에만** | 트리거(대상 BOM·라우팅 행 `FOR UPDATE` 후 확인) `uq_plm_eco_target_open` | 두 ECO 가 같은 리비전을 서로 다른 날짜로 확정하는 것 방지, 동시 추가 직렬화 |
| 적용 = 대상 BOM·라우팅이 지금도 DRAFT + `row_ver = base_row_ver` → 유효일·ECO 번호 적고 확정 | 서비스(한 트랜잭션, 하나라도 실패하면 전부 롤백) | 결재 내용 = 확정 내용 |
| 고객 승인 필요면 적용 전 `cust_approval_status = APPROVED` | 서비스 + 트리거 `ck_plm_eco_cust_gate`(RELEASED 로 갈 때) | 02 2.3 게이트 |
| 유효일 ≥ 오늘(작성·상신·적용 때 각각) | 서비스 + 확정 검사(`validFrom PAST`) | 지난 날짜로 확정하면 이미 만든 실적의 "그때 BOM" 이 바뀜(BomService) |
| ECR 상태 `OPEN→ACCEPTED(eco_id 필수)/REJECTED(사유)/CANCELLED`, 닫힌 요청 불변 | 트리거 `ck_plm_ecr_transition` | |

## 4. 흐름

```text
ECR 등록(누구나 SAVE) ──접수(APPROVE)──▶ 새 ECO(작성 중, 요청 내용 복사 + 주 품목을 ITEM 대상으로) 또는 기존 작성 중 ECO 에 묶기
          └─반려(APPROVE, 사유)  └─취소(요청자)
ECO 작성 중: 대상 추가(품목 / BOM 작성 중 리비전 / 라우팅 작성 중 리비전), 첨부(도면), 고객 승인 필요 여부
   ──상신(SAVE, 결재선)──▶ PENDING: 대상 row_ver 고정, apr_request(PLM_ECO, eco_id)
        결재 승인 ─▶ APPROVED      반려·회수 ─▶ DRAFT(고정 해제)
   APPROVED ──고객 승인 기록(필요할 때)──▶ ──적용(APPROVE)──▶ RELEASED
        적용 = 대상마다 [DRAFT 인가, row_ver 같은가] → 머리에 change_no = ECO 번호, change_reason = ECO 제목, valid_from = 유효일
               → release(이전 확정본은 유효일 전날까지로 자동 마감) — 한 트랜잭션
   DRAFT·APPROVED ──취소(SAVE, 사유)──▶ CANCELLED (대상 리비전은 작성 중 그대로 남는다 — 다른 ECO 가 쓸 수 있음)
```

- 대상을 추가할 때 BOM·라우팅은 **작성 중 리비전만** 고를 수 있다. 확정본을 바꾸려면 BOM·라우팅 화면에서 "새 리비전" 을 먼저 만든다(P1 흐름 그대로, docs/18 C1).
- ITEM 대상은 "이 품목에 영향(도면·사양)" 기록용 — 적용 때 할 일이 없다(2단계 도면 리비전이 붙는 자리).

## 5. 권한·API

| 동작 | API | 권한 |
|---|---|---|
| ECR 목록·상세·등록·수정(OPEN, 요청자) | `GET/POST/PUT /api/plm/ecrs[/{id}]` | `plm-ecr` SEARCH·SAVE |
| ECR 접수·반려 / 취소 | `POST /api/plm/ecrs/{id}/accept`·`/reject` / `/cancel` | APPROVE / SAVE(요청자만) |
| ECO 목록·상세·작성·수정 | `GET/POST/PUT /api/plm/ecos[/{id}]` | `plm-eco` SEARCH·SAVE |
| 대상 한 벌 저장 | `PUT /api/plm/ecos/{id}/targets` (rowVer, 추가·수정·삭제) | SAVE |
| 대상 후보(작성 중 BOM·라우팅) | `GET /api/plm/ecos/candidates?itemCd=` | SEARCH |
| 상신 | `POST /api/plm/ecos/{id}/submit` (rowVer, 결재선) | SAVE |
| 고객 승인 기록 | `POST /api/plm/ecos/{id}/cust-approval` | SAVE |
| 적용 / 취소 | `POST /api/plm/ecos/{id}/release` / `/cancel` | APPROVE / SAVE |
| 첨부 | 플랫폼 `/api/platform/files?targetType=plm-eco&targetId={id}` | 화면 권한 그대로 |

- 적용은 `mst-bom`·`mst-routing` APPROVE 를 따로 보지 않는다 — 결재선이 통제다(결정 D3).
- 다른 회사 ECO·대상·첨부는 RLS 로 보이지 않으므로 404, 다른 회사 BOM id 를 대상으로 넣으면 트리거 23503 → 400 칸 오류.

## 6. 남의 표 변경 · 공통 변경

| 구분 | 내용 |
|---|---|
| 남의 표 **스키마** | 없음. `mst_bom`·`mst_routing`·`lot_genealogy` 칸·트리거를 바꾸지 않는다 |
| 남의 표 **데이터** | ECO 적용이 `BomService.update`·`release`, `RoutingService.update`·`release` 를 호출(머리 `change_no`·`change_reason`·`valid_from` 기록 + 확정). 공개 서비스 메서드 호출이고 BOM·라우팅 코드는 고치지 않는다 |
| 공통 변경 | `FlywayMigrationGuardTest` 파일 이름 규칙이 세 자리(`V\d{3}`)만 허용 → 임시 번호(4자리) 대역을 허용하도록 `V\d{3,4}`. docs/29 임시 번호 규칙과 가드가 맞지 않던 것 — 모든 하위 세션 공통 |
| 공유 파일 | `messages*.properties` 끝 PLM 블록, `HbErrorCode` PLM 주석 아래, `DevCompanySeed` 끝 `PLM_MENUS`·`PLM_SHORTCUTS`(폴더 `menu.plm` 650, BOM·라우팅 바로가기), `AuditViewService.TABLE_PROG` 에 PLM 표 3개 |
| 재사용 | 회사 사용자 확인 함수 `hb_user_of_comp`(CMS 마이그레이션에 이미 있음 — 같은 이름으로 다시 만들면 Flyway 실패, 그대로 씀), 품목 찾기 `OrderService.items` |

## 7. 이상 사용 시험 계획 (A~K 중 1단계 해당)

| 분류 | 시험 |
|---|---|
| A 권한 | SEARCH 만 있는 사용자 저장·상신 403, SAVE 만 있는 사용자 적용·접수 403, 남의 ECR 수정·취소 거부 |
| B 다른 회사 | A사로 B사 ECO 조회·수정·상신·적용 404, A사 ECO 에 B사 BOM id 대상 → 거부, B사 첨부 조회 404, 직접 SQL(앱 계정)로 B사 대상 INSERT 거부 |
| C 상태 | 작성 중 아닌 ECO 수정·대상 저장 409, 승인 전 적용 409, 고객 승인 전 적용 409, 직접 SQL 로 DRAFT→RELEASED 건너뛰기 거부 |
| D 동시성 | 같은 rowVer 로 두 번 저장 → 409, 같은 리비전을 두 ECO 에 동시에 추가 → 하나만, 적용 연타 → 한 번만 |
| E 값 | 지난 유효일, 빈 제목, 너무 긴 글, 없는 사유 코드, 대상 유형과 id 불일치 |
| F 결재 뒤 변경 | 상신 뒤 BOM 행 수정 → 적용 409 `PLM_TARGET_CHANGED`(rowKey 로 대상 표시), 상신 뒤 BOM 화면에서 직접 확정 → 적용 409 |
| G 롤백 | 대상 둘 중 하나가 확정 검사 실패 → 둘 다 확정 안 됨, ECO 도 APPROVED 그대로 |
| H 결재 엔진 | 반려 → DRAFT 로 돌아와 다시 수정·상신 가능, 회수 동일, 결재 처리 중 핸들러 예외 → 결재도 롤백 |

## 8. 결정 필요 (사용자) — 추천안으로 진행

| # | 질문 | 추천(진행한 것) | 대안 |
|---|---|---|---|
| D1 | `lot_genealogy.change_id` 는 무엇을 가리키나 | **채택(사용자 2026-10-20): ECO.** 3단계에서 반영, 1단계는 손대지 않음. 근거: ECO(`plm_eco.eco_id`) — 계보에 남길 "변경" 은 설계 변경과 4M 변경 모두인데, IATF 8.3.6·8.5.6 의 변경 관리는 같은 구조(요청 → 영향 → 결재 → 유효일)라 QMS 4M 은 ECO 에 1:1 확장 표(`qms_change` → `eco_id`)로 붙이는 편이 키가 하나로 모인다 | `change_id` = `qms_change`(docs/25 원안 "4M 변경 — P3 자리"), ECO 는 BOM 리비전 `change_no` 로만 연결 |
| D2 | 결재 승인 = 자동 적용? | **아니오 — 적용은 별도 동작.** 적용 실패(확정 검사)가 승인 처리를 롤백시키면 결재자가 승인을 못 한다. 승인 → 담당이 적용 | 승인 핸들러에서 바로 적용(실패하면 승인 불가) |
| D3 | 적용할 때 BOM·라우팅 APPROVE 권한도 보나 | **안 본다** — 결재선이 통제. 화면 권한은 `plm-eco` APPROVE | 둘 다 요구(권한 관리 부담 ↑) |
| D4 | ECO 없이 BOM·라우팅 직접 확정을 막나 | **채택(사용자 2026-10-21): 회사 정책 `PLM_ECO_REQUIRED`(기본 N)** — Y 면 BomService·RoutingService 확정이 ECO 적용 경로에서만 허용(4단계에서 구현, 14.2). 1단계는 막지 않았다. **남의 코드 변경**(BOM·라우팅 서비스 + 두 표 트리거) — 정책 N 이면 기존 동작 그대로 | 지금 바로 막기 |
| D5 | 승인 뒤 유효일이 지나 버리면 | **적용 거부**(`PLM_EFFECTIVE_PAST`) → 취소 후 새 ECO. 유효일은 결재받은 내용이라 적용자가 바꾸지 않는다 | 적용 때 유효일 = max(유효일, 오늘) + 사유 기록 |
| D6 | ECR 접수 권한 | `plm-ecr` APPROVE(변경 검토 담당) | 결재 엔진으로 ECR 도 결재 |
| D8 | 도면 리비전을 어디에 두나 | **채택(사용자 2026-10-20): QMS Q3 `mst_doc_rev` 재사용**(문서 유형 DRAWING) — 관리문서와 도면이 같은 리비전·배포 규칙, 표 하나. PLM 은 ECO 대상 유형 DOC 만 추가 | PLM 전용 `plm_doc_rev`(원래 2단계 안) — QMS·PMS 와 문서 표가 둘이 됨 |
| D7 | 상신 뒤 첨부(도면)를 고정하나 | **1단계는 고정하지 않음** — `FileService` 에 대상별 잠금 훅이 없어 고치면 공통 변경. 대신 화면이 "상신 뒤 추가" 를 표시(첨부 `created_at` > `submitted_at`), 삭제는 사용 중지 + 감사. 2단계 도면 리비전(QMS `mst_doc_rev`, D8)이 배포본 파일을 고정 | 지금 `FileService` 에 대상 잠금 훅 추가(공통 변경) |

## 9. 하지 않을 것 (1단계)

- EBOM/MBOM 분리(docs/18 E1·E2) — 지금 BOM 은 생산 BOM 하나. 필요해지면 2차.
- 도면 뷰어·CAD 연동·PDM 체크인/아웃 — 첨부 파일까지만.
- ECO 대상 자동 탐지(역전개로 영향 BOM 추천) — docs/18 C5 일괄 대체가 이미 미리보기를 한다. 4단계에서 연결.
- 작업지시 재발행·진행 중 지시 처리(유효일 이후에도 이전 리비전 스냅샷으로 진행) — 스냅샷 규칙(docs/25 4.4)이 이미 보장.

## 10. 검토 결과 (PLM 하위 세션, 2026-10-20)

- **BOM·라우팅 흐름을 바꾸지 않는 것이 1단계의 핵심 판단.** 확정 검사·자동 마감·겹침 금지를 PLM 이 다시 구현하면 두 군데가 어긋난다. 공개 서비스 메서드를 그대로 부르면 BOM 규칙이 바뀔 때 ECO 적용도 같이 바뀐다. 대가: BOM 확정 오류 응답(행 rowKey)이 ECO 화면에서는 대상 단위로만 보인다 → ECO 쪽에서 대상 rowKey(`t{targetId}`)로 감싸 보낸다.
- `row_ver` 고정 비교는 머리 `row_ver` 가 행 저장마다 오른다는 P1 규칙에 기댄다. 직접 SQL 로 행만 바꾸면 못 잡는다 — 직접 SQL 은 감사 로그(UNKNOWN 행위자)로 드러나는 것으로 받아들인다.
- 한 리비전 = 열린 ECO 하나 규칙은 부분 유일 인덱스로는 못 건다(ECO 상태가 다른 표). 트리거가 대상 BOM·라우팅 행을 `FOR UPDATE` 로 잠그고 확인 — 동시 추가 두 건은 직렬화된다.
- 다른 모듈 설계(docs/31·32·35)와 맞춰 본 결과: 결재는 SRM·PMS 와 같은 모양, 첨부 대상 키는 화면 코드가 달라 겹치지 않는다(PMS 는 `D{id}` 접두어). 처음 쓴 2단계 안(`plm_doc_rev`)은 QMS Q3 관리문서와 겹쳐 D8 로 바꿨다. 적용 조건은 docs/35 A11 의 날짜·LOT·재고 소진 중 날짜만 1단계.
- 고객 승인은 ECO 칸 4개로 최소형. PPAP 패키지(4단계)가 오면 칸을 옮기지 않고 패키지가 ECO 를 가리키게 한다.

## 11. 진행

| 단계 | 상태 | 날짜 | 시험 |
|---|---|---|---|
| 1 ECR·ECO·영향 대상·유효일·고객 승인·첨부 | ✅ (main 병합, V050) | 2026-10-20 | `PlmEcoTest` 11건, 전체 check 1072건 |
| 2 도면 리비전(QMS `mst_doc_rev`)·첨부 고정 D7 (15장) | ✅ main 병합(V082) | 2026-10-21 | `PlmDrawingTest` 6건, 전체 check 1394건 |
| 3b 변경 표시 `plm_eco_lot`·변경 종류·설비/공정 대상·실제 `ChangeMarker`/`EcoTrace` (13장) | ✅ main 병합(V060) | 2026-10-21 | `PlmEcoLotTest` 9건, 전체 check 1157건 |
| 3c MES `change_id` FK·`mark` 호출(MES 세션 V077) + PLM 연결 확인 시험 | ✅ main 병합 | 2026-10-21 | `PlmEcoLotTest` 3c 경로 |
| 4 PPAP·PCN·일괄 대체 연동·D4 ECO 필수 정책 (14장) | ✅ (main 병합, V069) | 2026-10-21 | `PlmStage4Test` 8건, 전체 check 1189건 |
| QMS 2부 계약 ①②④·DOC 확장·Q5 계약·Q9 계약 (16장) | ✅ main 병합(V088 등) | 2026-10-21~22 | `PlmQmsContractTest`·`PlmLinkContractTest` |
| 3d 출하 게이트 계약 `EcoTrace.shipChecks` (17장) | ✅ main 병합(#176) | 2026-10-22 | `PlmEcoLotTest` +1 |
| 4 남음: 적용 조건 재고 소진 + `EcoLookup.ppap` (18장·16.4) | ✅ main 병합(V119) | 2026-10-22 | `PlmStockOutTest` 5 |
| 전자서명 연결 (19장) | ✅ main 병합(V126) | 2026-10-22 | `PlmEsignTest` 7 |
| 20장 ① 화면 영문 코드 문구화 + 이 표 정리 | ✅ main 병합(#215) | 2026-10-22 | 브라우저 |
| 20장 ② 홈 카드 2장 (20.6) | ✅ main 병합 | 2026-10-22 | `PlmHomeCardTest` 5 |
| 20장 ③ 영향 대상 후보 보기 (20.7) | ✅ main 병합 | 2026-10-22 | `PlmImpactTest` 6 |
| UI 단계 3 — 대화상자·상태 배지·위험 동작 자리 + 주 목록 hb.grid (21장) | ✅ main 병합(#276·#288) | 2026-10-22 | `PlmGridTest` 3, 브라우저 1366·390 |
| 결재 줄 단위 서명 `beforeAct` + 결재함 안내 `esignFor` (22장) | ✅ main 병합(#370) | 2026-10-23 | `PlmEsignTest` 7 → 9 |
| PCN 메일 발송 — 고객 담당자, 발송 이력 (23장) | ✅ main 병합(#409, V156·임시 V9907) | 2026-10-23 | `PlmPcnMailTest` 7 + `PlmPcnMailOffTest` 1, 브라우저 1366·390 |
| 읽기 계약 `EcoLookup.releasedFor`·`approvedFor` + `EcoView` 승인일·적용일 (24장) | ✅ main 병합(#423) | 2026-10-23 | `PlmEcoForItemTest` 3 + `PlmLinkContractTest` NONE |
| 주 목록 엑셀 — 지시·요청 grid 메뉴 (25장) | ✅ main 병합(#432, V164·임시 V9908) | 2026-10-23 | `PlmListExcelTest` 3, 브라우저 1366·390 |
| 기한 지남 알림 — 지시 담당에게 사내 알림 (26장) | ✅ main 병합(#439) | 2026-10-23 | `PlmOverdueNotifyTest` 3 |
| 지시 복사 (27장) | ✅ main 병합(#460) | 2026-10-24 | `PlmEcoCopyTest` 3, 브라우저 1366·390 |
| grid 변경 이력 보기 (28장) | ✅ main 병합(#471) | 2026-10-24 | `PlmAuditViewTest` 1, 브라우저 1366·390 |
| 변경 통지서 엑셀 — 고객 제출용 한 장 (29장) | ✅ main 병합(#485) | 2026-10-24 | `PlmEcoNoticeTest` 3, 브라우저 1366·390 |
| 홈 카드 "고객 응답 대기" 숫자 링크 — 목록 묶음 선택지 `CUST_WAIT` (30장) | ✅ main 병합(#494) | 2026-10-24 | `PlmHomeCardTest` 5 → 6, 브라우저 1366·390 |
| PPAP 제출 요약서 엑셀 + 고객 제출 문서 서식 도우미 (31장) | ✅ main 병합(#506) | 2026-10-24 | `PlmPpapSummaryTest` 3, `PlmEcoNoticeTest` 3 그대로, 브라우저 1366·390 |
| PCN 메일 발송 결과 표시 — 이력 줄 상태 배지·이유 (32장) | ✅ main 병합(#514) | 2026-10-24 | `PlmPcnMailStatusTest` 2, 브라우저 1366·390 |
| PCN 메일 변경 통지서 첨부 (33장) | ✅ main 병합(#524, V183·임시 V9909) | 2026-10-24 | `PlmPcnMailAttachTest` 3, 브라우저 1366·390 |
| 사내 지시 기록서 엑셀 (34장) | ✅ main 병합(#550) | 2026-10-24 | `PlmEcoRecordTest` 3, 통지서·요약서 접속 로그 detail, 브라우저 1366·390 |
| 고객 통지 응답 기한 임박 알림 (35장) | ✅ main 병합(#550) | 2026-10-24 | `PlmOverdueNotifyTest` 3 → 5 |
| DOC 대상 문서 유형에 PFMEA (36장, PMS E-1) + 보낸 통지서 사본 DB 잠금(36.1, QA P-1) | ✅ main 병합(#550, V187·임시 V9910) | 2026-10-24 | `PlmDrawingTest` +1 |
| PPAP 제출 메일 — 고객 담당자, 요약서 첨부 (37장) | ✅ main 병합(#559, V190·임시 V9911) | 2026-10-26 | `PlmPpapMailTest` 6, 브라우저 1366·390 |
| PPAP 요소 6·7 ← APQP 현행 확정본 (38장, PMS A3-6) | 이 PR(V194·임시 V9912) | 2026-10-26 | `PlmPpapApqpTest` 6, 브라우저 1366·390 |

~~남은 작은 것: 대상 표의 "지금 상태"·결재선 상태가 영문 코드 그대로~~ → 20장 ①(2026-10-22): 대상 리비전 상태 = 공통코드 `BOM_STATUS`(라우팅도 같은 값)·`DOC_REV_STATUS`, 결재 줄 상태 = 플랫폼 결재 화면 문구(`js.sysApproval.st.*`) — 새 문구 키 없음.

## 12. 3단계 경계 합의안 — 설계 변경 ↔ 생산 LOT 연결 (MES·QMS 세션과 합의용, 2026-10-20, 문서만)

> 상태: **합의 — Q1 사용자 채택(2026-10-20), Q2·Q3 MES 세션 동의(2026-10-20, 조건 반영)**, 3a~3d 진행은 12.7 표. D1 채택(사용자 2026-10-20: `change_id` → ECO, QMS 4M 은 ECO 1:1 확장)을 실제 표·코드로 옮길 때 누가 무엇을 바꾸는지 정한다. MES 표 변경이 들어가므로 MES 세션 동의 후 착수.

### 12.1 지금 코드에서 확인한 사실 (main 3f84ca5)

| # | 사실 | 위치 | 뜻 |
|---|---|---|---|
| F1 | `lot_genealogy.change_id bigint` — FK 없음, 회사 확인 없음, **어디서도 쓰지 않음**(항상 NULL) | V034, `LotMapper.insertEdge` 에 칸 없음 | 지금 바꿔도 기존 데이터 영향 없음 |
| F2 | 생산 LOT 은 `LotService.createProduced` 가 **`lot_master` 한 행만** 만든다. 계보 행은 자재 LOT 을 스캔 투입했을 때(`consume`)만 생긴다 | V034·LotService | **계보 칸에만 변경을 표시하면 투입 스캔 없는 생산 LOT 은 표시가 빠진다** |
| F3 | 역분개 트리거의 "원래 행 그대로" 검사는 (부모·자식·유형·단위·수량)만 본다 — `change_id` 는 대상 아님 | V034 `ck_lot_genealogy_reversal` | 역분개 행이 다른 `change_id` 를 가질 수 있다 |
| F4 | `lot_master.wo_id` → `prd_wo.bom_id`·`routing_id`(발행 때 고정) → `plm_eco_target.bom_id`·`routing_id`(적용 뒤 불변) | V033·V034·V050 | **칸을 하나도 안 늘려도** "이 LOT 은 ECO X 로 확정된 리비전으로 만들었다" 를 알 수 있다 |

### 12.2 결론 (제안)

1. **연결은 두 가지로 나눈다.**
   - **(가) 리비전 경로 — 칸 추가 없음.** LOT → 지시 → 스냅샷 BOM·라우팅 리비전 → 그 리비전을 확정한 ECO. 설계 변경(BOM·라우팅이 바뀐 것)은 전부 이 경로로 찾는다. PLM 이 조회 함수를 제공한다(12.4).
   - **(나) 변경 표시 — `change_id`.** 리비전에 드러나지 않는 변경(4M: 설비·작업자·측정·환경·협력사·장소, 임시 변경)과 "변경 뒤 첫 LOT(초품 확인 대상)" 표시에만 쓴다. D1 대로 대상은 `plm_eco`.
2. **(나)의 표시는 계보가 아니라 LOT 단위로 남긴다**(F2). 새 표 `plm_eco_lot`(PLM 소유, 추가만): ECO × LOT × 표시 이유(FIRST_LOT·4M_WINDOW·TEMP) × 발생 시각. `lot_genealogy.change_id` 는 **투입 계보 행이 생길 때 같은 값을 따라 적는 사본**(계보 추적 화면에서 바로 보이게) — 원본은 `plm_eco_lot`.
   - D1 의 "change_id → ECO" 는 그대로 지킨다(칸의 대상 = `plm_eco`). 바뀌는 것은 "원본 기록 위치를 LOT 단위 표로 하나 더 둔다" 뿐 — **사용자 재확인 필요(12.6 Q1)**.
3. **MES 는 PLM 표를 직접 읽지 않는다.** PLM 이 빈 하나(`ChangeMarker`)를 내놓고, MES 는 실적 확정 트랜잭션 안에서 그 빈만 부른다. PLM 모듈이 없는 설치본은 빈 구현(표시 없음)이라 MES 가 그대로 돈다.

### 12.3 누가 무엇을 바꾸나

| 소유 | 바꾸는 것 | 남의 표 변경? |
|---|---|---|
| **MES** (V 파일은 MES 대역, MES 세션 — Q2 동의) | `lot_genealogy.change_id` FK → `plm_eco(eco_id)` + `hb_guard_lot_genealogy` 에 같은 회사 확인(`fk_lot_genealogy_ctx_comp` 에 한 줄) + 역분개는 원래 행과 같은 `change_id`(F3 — 반사 검사 목록에 추가). `LotMapper.insertEdge` 에 칸 추가. **새 LOT 을 만드는 세 곳 모두** 같은 트랜잭션에서 `ChangeMarker.mark(…)`: 5b 실적 확정, 7a 재작업·수리(`prd_rework` 양품 → `createProduced`), 7b LOT 단위 재작업(origin REWORK/REPAIR — 지시 없음). 빈은 `ObjectProvider` 로 받는다 | MES 자기 표. 단 **FK 가 PLM 표를 가리키므로 병합 순서 = PLM(V050) 뒤** — 이미 충족 |
| **PLM** (V99xx) | `plm_eco_lot` 표, `ChangeMarker` 빈(표시 판정 + 기록), `EcoTrace.ecosOfLot(lotId)`(가 경로 + 나 표시 합쳐 돌려줌), ECO 에 `change_kind`(DESIGN·PROCESS_4M·TEMP) + 대상 유형 EQUIP·PROC(4M 은 BOM·라우팅 대상이 없을 수 있음), 표시 구간 칸(`mark_lots` 또는 `mark_until`) | 없음 |
| **QMS** (2부) | `qms_change`(PK = `eco_id`, ECO 1:1 확장: 4M 유형 MAN/MACHINE/MATERIAL/METHOD/MEASURE/ENV/SUPPLIER/SITE·영향 평가·관련 특성/CP 행), 초품 판정 `qms_setup_verif` 가 `plm_eco_lot`(FIRST_LOT)을 입력 목록으로 | 없음(PLM 표를 FK 로 참조만) |
| **WMS** (출하) | 게이트: `plm_eco_lot` 에 걸린 LOT 인데 ECO 고객 승인이 APPROVED 아니면 출하 차단(02 2.3) — `EcoTrace` 로 조회 | 없음 |

### 12.4 PLM 이 내놓을 계약 (초안)

```java
// module.plm — MES·QMS·WMS 가 쓰는 유일한 입구 (PLM 표 직접 조회 금지)
public interface ChangeMarker {
    /** 실적 확정 트랜잭션 안에서(MANDATORY). 이 LOT 에 걸리는 열린 표시가 있으면 plm_eco_lot 에 기록하고 eco_id 를 돌려준다 */
    Optional<Long> mark(long lotId, Long woId, Long equipId, OffsetDateTime occurredAt);   // woId 없음 = LOT 단위 재작업(7b)
}
public interface EcoTrace {
    /** (가) 리비전 경로 + (나) 표시를 합친 목록 — LOT 추적·출하 게이트·감사 보고서용 */
    List<EcoRef> ecosOfLot(long lotId);
}
```

- 표시 판정(제안): ECO 가 RELEASED 이고, 같은 품목(지시 품목 또는 대상 품목) + 지시 리비전이 그 ECO 의 대상이거나(DESIGN) 대상 설비·공정이 맞고(4M), 표시 구간 안. 구간 = 유효일부터 **첫 N LOT**(회사 정책 `PLM_MARK_FIRST_LOTS`, 기본 1) 또는 4M·임시는 `mark_until` 까지.
- 동시성(Q3 합의 — 정확히 N): `mark` 가 ECO 행을 `FOR UPDATE` 로 잠그고 센다. **잠금 순서는 MES 행(`prd_wo_proc`·`lot_master`) → ECO 행으로 고정**. PLM 의 ECO 상태 변경 경로(작성·상신·결재 끝·적용·취소)는 MES 표를 잠그지 않는다 — 적용이 잠그는 것은 `plm_eco`·`plm_eco_target`·`mst_bom`·`mst_routing` 뿐(2026-10-20 V050·`PlmMapper`·BOM/라우팅 매퍼 확인). 교착 방지.
- "첫 N" 은 **확정(도착) 순**이다 — 오프라인 대기열 재전송은 늦게 도착해 발생 순과 다를 수 있다. 표시일 뿐 게이트가 아니다(초품 판정·출하 게이트는 QMS·WMS 가 따로).
- PLM 없는 설치본: `ChangeMarker`·`EcoTrace` 의 **no-op 구현**(표시 없음·빈 목록)이 기본 빈 — MES 는 그대로 돈다.
- 원장 규칙: `plm_eco_lot` 은 추가만(수정·삭제 차단 + REVOKE). 실적 역분개로 LOT 이 보류돼도 표시는 남긴다(사실 기록).

### 12.5 버린 대안

| 대안 | 버린 이유 |
|---|---|
| `change_id` 를 계보 칸에만(원안) | F2 — 투입 스캔 없는 생산 LOT 이 빠진다. 계보 행은 자재 수만큼 생겨 같은 표시가 여러 번 중복 |
| `lot_master.change_id` 칸 추가 | LOT 하나에 변경이 여럿 걸릴 수 있다(설계 + 4M 동시). MES 마스터 표 변경이 더 크다 |
| 모든 LOT 에 (가) 결과를 미리 적기 | 리비전 경로로 언제든 계산된다 — 중복 저장은 어긋날 자리만 만든다 |
| MES 가 `plm_*` 를 직접 조회 | 모듈 경계 위반, PLM 없는 설치본에서 깨짐(모듈 선택 납품) |

### 12.6 합의할 질문

| # | 상대 | 질문 | 제안 |
|---|---|---|---|
| Q1 | 사용자 | 원본 표시를 `plm_eco_lot`(LOT 단위)에 두고 `change_id` 는 계보 사본으로 — D1 의 보강으로 받아들일지 | **채택(사용자 2026-10-20): 예**(F2 근거) |
| Q2 | MES | MES 표 변경을 누가 | **합의: MES 세션**(MES 대역 V 파일). 조건: ① PLM 이 인터페이스 + no-op 빈을 먼저 main 에 ② `mark` 는 새 LOT 세 곳 모두, `woId` nullable ③ MES 실제 작업은 PLM 3a 병합 뒤 MES 다음 단계 |
| Q3 | MES·QMS | 첫 N LOT 동시 표시 | **합의: 잠금으로 정확히 N**. 잠금 순서 MES 행 → ECO 행 고정, PLM 경로는 MES 표를 잠그지 않음, N 은 도착 순(표시일 뿐) |
| Q4 | QMS | 4M 세부를 `qms_change`(1:1)로 — 고객 승인 칸은 ECO 것을 그대로 쓰고 QMS 쪽에 두지 않기 | 예(한 곳) |
| Q5 | WMS | 출하 게이트를 언제 켤지 — 정책 `SHIP_BLOCK_UNAPPROVED_CHANGE` | **사용자 확인 2026-10-22: 추천안** — 정책 주인 WMS, 기본 **Y**(17.3 G3). PLM 계약은 17장 |

### 12.7 진행 순서 (Q2 조건 ①·③)

| 순서 | 누가 | 내용 | 조건 |
|---|---|---|---|
| **3a** ✅ | PLM | `ChangeMarker`·`EcoTrace` 인터페이스 + **no-op 빈** 만 main 에(표·V 파일 없음, 패키지 `module.plm.link`). MES 는 `ChangeMarker.of(ObjectProvider)`·`EcoTrace.of(…)` 로 받는다 — 빈 없으면 `NONE` | 2026-10-20, `PlmLinkContractTest` 2건 + 앱 컨텍스트 1건 |
| 3b | PLM | `plm_eco_lot`(V99xx), ECO `change_kind`·대상 유형 EQUIP·PROC·표시 구간 칸, 실제 구현(no-op 대체), `EcoTrace` (가)+(나) | Q1 사용자 답 뒤 |
| 3c | MES | FK·트리거·`insertEdge`·세 곳 `mark` 호출 | 3a 병합 뒤 MES 다음 단계 |
| 3d | QMS·WMS (+ PLM 계약) | `qms_change`(1:1)·초품 입력 목록 ✅ QMS Q5·Q7 / 출하 게이트 — PLM 계약 `EcoTrace.shipChecks` ✅(17장), WMS 게이트는 B6 | 각 2부 |

### 12.8 시험 계획 (3단계 착수 때)

투입 스캔 없는 생산 LOT 도 첫 LOT 표시 / LOT 단위 재작업(지시 없음) 표시 / 오프라인 재전송이 늦게 와도 도착 순 N / 투입 있는 LOT 은 계보 `change_id` = 표시 ECO / 역분개 행 `change_id` 다르면 트리거 거부 / 다른 회사 ECO id 를 `change_id` 로 직접 SQL → 거부 / 첫 N 자리 동시 2건 → N 건만 / PLM 빈 구현일 때 MES 실적 그대로 / `ecosOfLot` 이 (가)·(나) 를 합쳐 중복 없이 / A사로 B사 LOT 조회 0건.

## 13. 3b 구현 — `plm_eco_lot`·변경 종류·4M 대상·실제 `ChangeMarker`/`EcoTrace` (PLM 하위 세션, 2026-10-20)

> Q1 채택(사용자 2026-10-20) 뒤 착수. 마이그레이션 **V060(임시 V9901)**. MES 표(`lot_genealogy`·`prd_*`·`lot_master`)의 스키마·데이터는 바꾸지 않는다 — 읽기만. `change_id` FK·`mark` 호출은 3c(MES).

### 13.1 바뀌는 것

| 대상 | 내용 |
|---|---|
| `plm_eco` 칸 3개 | `change_kind`(DESIGN 기본·PROCESS_4M·TEMP), `mark_lots`(첫 N, 1~100, 비우면 회사 정책 `PLM_MARK_FIRST_LOTS` 기본 1 — 0 이면 첫 LOT 표시 끔), `mark_until`(4M·임시의 표시 끝 날짜, 포함). TEMP 는 `mark_until` 필수, DESIGN 은 없음, `mark_until ≥ effective_date`. 작성 중에만 수정(기존 고정 트리거에 세 칸 추가) |
| `plm_eco_target` 유형 2개 | EQUIP(`equip_id` → `mst_equip`)·PROC(`proc_id` → `mst_proc`). 품목은 그대로 필수(4M 도 "어느 품목의 어느 설비" 로 적는다). 같은 회사 확인은 트리거. 적용 때 할 일 없음(ITEM 과 같음) |
| 새 표 `plm_eco_lot` | ECO × LOT 표시: `mark_reason`(FIRST_LOT·4M_WINDOW·TEMP), `seq_no`(그 ECO 의 몇 번째 — 도착 순), `wo_id`·`equip_id`(그때 값, 정보용), `occurred_at`(LOT 발생)·`recorded_at`·`recorded_by`. 추가만(수정·삭제 트리거 + REVOKE), 유일 (ECO, LOT)·(ECO, seq_no). 보존 PRODUCTION_RECORD, 감사 트리거 없음(행 자체가 이력 — `RetentionTest.AUDIT_EXEMPT`) |
| 빈 | `PlmLinkConfig` 의 no-op 빈 → `EcoChangeMarker`·`EcoLotTrace`(같은 계약). MES 쪽 `ChangeMarker.of(…)` 코드는 그대로 |
| API·화면 | ECO 폼에 변경 종류·첫 N·표시 끝, 대상 추가에 설비·공정, 상세 "표시된 LOT" 표(`GET /api/plm/ecos/{id}/lots`, SEARCH) |

### 13.2 표시 판정 (`mark(lotId, woId, equipId, occurredAt)`)

LOT 품목 = `lot_master.item_id`, 지시 리비전 = `prd_wo.bom_id`·`routing_id`(woId 가 null 이면 없음), 발생일 = `occurredAt` 을 회사 시간대 날짜로.
RELEASED ECO 중 대상 하나가 이 LOT 에 맞으면 후보:

| 대상 유형 | 맞는 조건 |
|---|---|
| BOM / ROUTING | 지시 스냅샷 리비전 = 대상 리비전(날짜 무관 — 리비전 자체가 "변경 뒤") |
| EQUIP | `equipId` = 대상 설비 그리고 발생일 ≥ 유효일 |
| PROC | 지시 공정에 그 공정이 있음(`prd_wo_proc`) 그리고 발생일 ≥ 유효일 |
| ITEM | 그 ECO 에 **같은 품목의 다른 유형 대상이 없을 때만** 발생일 ≥ 유효일 — 도면만 바뀐 ECO 용. BOM·설비 대상이 같이 있으면 그쪽이 더 구체적이라 품목 대상으로는 표시하지 않는다(유효일 뒤에도 이전 리비전 지시로 만든 LOT 을 "변경 뒤 첫 LOT" 으로 잘못 표시하지 않게) |

- 구간: `mark_until` 이 있으면 **유효일 ≤ 발생일 ≤ 표시 끝** 안의 LOT 모두(4M_WINDOW·TEMP, 개수 제한 없음), 없으면 **첫 N**(FIRST_LOT).
- 잠금: 후보 ECO 를 `eco_id` 오름차순으로 `FOR NO KEY UPDATE`(상태 RELEASED 다시 확인) → 세고 → 넣는다. 잠근 뒤 센 값으로 `seq_no` = 정확히 N(Q3). `FOR UPDATE` 대신 `NO KEY UPDATE` 인 것은 다른 표의 ECO FK 확인(KEY SHARE)까지 막을 필요가 없어서 — 표시끼리는 그대로 직렬화된다.
- 잠금 순서 MES 행 → ECO 행(Q3): 호출자가 MES 행을 쥔 채 부르고, PLM 은 ECO 행만 잠근다. **`plm_eco_lot` 은 `lot_master`·`prd_wo`·`mst_equip` 에 FK 를 두지 않는다** — FK 확인이 ECO 잠금 뒤에 MES 행에 KEY SHARE 를 걸어 순서가 뒤집히기 때문(지시 행을 FOR UPDATE 로 쥔 다른 트랜잭션이 ECO 를 기다리면 교착). 대신 BEFORE INSERT 트리거가 잠금 없는 조회로 같은 회사인지 본다. `lot_master`·`prd_wo` 는 앱 DELETE 가 없어 참조가 끊기지 않는다.
- 같은 LOT 을 두 번 부르면 이미 있는 표시를 그대로 둔다(멱등). 돌려주는 값 = 이 LOT 에 걸린 ECO 중 **가장 늦게 적용된 것**(released_at, eco_id) — `lot_genealogy.change_id` 칸이 하나라서. 나머지도 `plm_eco_lot` 에 모두 남고 `ecosOfLot` 이 다 돌려준다.
- `@Transactional(MANDATORY)` — MES 트랜잭션 안에서만. 표시가 없으면 빈 값(실적을 막지 않는다).
- N 은 표시할 때의 정책 값이다(ECO 에 `mark_lots` 를 적으면 그것이 우선). 적용 뒤 정책을 바꾸면 그 뒤 표시부터 바뀐다 — 표시일 뿐이라 받아들인다.

### 13.3 `ecosOfLot(lotId)`

(가) `lot_master.wo_id → prd_wo.bom_id·routing_id → plm_eco_target(BOM·ROUTING) → RELEASED ECO` + (나) `plm_eco_lot`. ECO 하나에 한 줄, 둘 다면 `Via.REVISION`(리비전 근거가 더 강하다 — 표시 여부는 상세 "표시된 LOT" 에서). 정렬 = 유효일, eco_id. 다른 회사 LOT 이면 RLS 로 빈 목록. `@Transactional(readOnly)`(MES 트랜잭션 안이면 그것에 참여).

### 13.4 시험 (`PlmEcoLotTest`)

정상: 투입 스캔 없는 생산 LOT 첫 N / 지시 없는 LOT 단위 재작업(설비 4M) / 4M 구간 / ITEM 만 있는 ECO 날짜 판정 + 리비전 대상 우선 / `ecosOfLot` (가)+(나) 중복 없음.
이상 사용: B 다른 회사(A사 LOT 을 B사 범위로 표시 → 빈 값, 직접 SQL 로 B사 LOT·ECO 표시 거부, A사로 B사 `/lots` 404) / C 상태(적용 전 ECO 는 표시 안 함, 작성 중 아니면 변경 종류 수정 409, 직접 SQL 로 표시 행 UPDATE·DELETE 거부) / D 동시성(첫 1 자리에 LOT 2개 동시 → 1건, 늦게 도착한 오프라인 재전송은 도착 순) / E 값(TEMP 에 끝 없음, DESIGN 에 끝, 끝 < 유효일, N 범위, 없는 설비·공정 코드) / 트랜잭션 밖 호출 거부.

### 13.5 검토 (PLM 하위 세션)

- 원안 12.4 의 "`FOR UPDATE` 로 잠그고 센다" 를 유지하되 FK 를 빼는 것이 핵심 판단이다 — FK 는 정합성 도구지만 여기서는 잠금 순서 규칙(MES → ECO)을 몰래 깨는 경로였다. 같은 회사 확인은 트리거로 대신하고, 참조 대상(LOT·지시)은 지워지지 않는 표라 잃는 것은 "직접 SQL 로 없는 id 를 넣는 경우" 뿐이다 — 트리거가 그것도 막는다(없으면 같은 회사 확인 실패).
- ITEM 대상의 판정을 "다른 유형 대상이 없을 때만" 으로 좁힌 것은 1단계 화면이 ECR 접수 때 주 품목을 ITEM 대상으로 자동으로 넣기 때문이다(4장). 그대로 두면 거의 모든 ECO 가 품목 날짜 판정을 갖게 되어 리비전 판정이 무의미해진다.
- 4M 구간의 끝을 시각이 아니라 날짜로 둔 것은 유효일과 같은 단위로 맞추고 화면 입력(공장 시간대 변환)을 줄이기 위해서다. 시각 단위가 필요해지면 칸을 추가한다(날짜 → 시각 변환은 정보 손실 없음).
- 버린 대안: 표시를 MES 이벤트(AFTER_COMMIT)로 비동기 처리 — 도착 순 N 은 지킬 수 있지만 실적 트랜잭션과 따로 커밋돼 `change_id` 사본을 같은 트랜잭션에 적을 수 없다(12.2 (나)).

## 14. 4단계 — PPAP 패키지·고객 통지(PCN)·일괄 대체 연동·ECO 필수 정책(D4) (PLM 하위 세션, 2026-10-21)

> 사용자 결정(2026-10-21): 4단계 시작 + D4 채택. "재고 소진" 적용 조건은 WMS 원장(W3~W5)이 main 에 들어온 뒤. 마이그레이션 **V069(임시 V9902)**.

### 14.1 범위

| 항목 | 내용 |
|---|---|
| D4 ECO 필수 | 회사 정책 `PLM_ECO_REQUIRED`(Y/N, 기본 N). Y 면 BOM·라우팅 확정은 ECO 적용 경로에서만 |
| 일괄 대체 연동 | ECO 화면에서 docs/18 C5 일괄 대체(자품목 X → Y)를 **ECO 유효일**로 미리보기·실행 → 만들어진(또는 고친) 작성 중 BOM 을 그 ECO 의 BOM 대상으로 같은 트랜잭션에 추가 |
| PPAP 패키지 | ECO 하나에 패키지 하나(고객 1). 제출 레벨 1~5, 18요소 각각 요구(S 제출·R 보관·N 해당 없음)·상태·메모·첨부 파일. 제출 → 고객 판정(승인·임시 승인·거절) |
| PCN | ECO 하나에 고객별 통지 여러 건: 통지일·응답 기한·응답(확인·승인·거절)·메모. 기록만(메일 발송은 하지 않음 — 14.5) |

### 14.2 D4 — 확정 허용 범위(`EcoReleaseGate`)

- 확정 허용 표시는 **트랜잭션 로컬 DB 설정** `app.plm_eco_release`(`set_config(…, true)`). PLM 적용(`EcoService.release`)이 대상 확정 직전에 켜고 직후에 끈다. 트랜잭션이 끝나면 자동으로 사라진다.
- 막는 곳 두 겹:
  1. `BomService.release`·`RoutingService.release` — 정책 Y 이고 허용 표시가 없으면 409 `PLM_ECO_REQUIRED`(화면 문구). 정책 N 이면 아무것도 하지 않는다(기존 시험 그대로).
  2. 트리거 `ck_plm_eco_required`(`mst_bom`·`mst_routing` BEFORE UPDATE, 상태가 RELEASED 로 바뀔 때) — 직접 SQL·다른 경로의 마지막 방어. 정책 값은 `sys_policy` 회사 값(없으면 정의 기본값).
- 허용 표시 클래스는 **master 패키지**(`module.master.EcoReleaseGate`)에 둔다 — BOM·라우팅이 PLM 을 알지 않게(PLM 이 master 에 기대는 방향만 있다). 
- 직접 SQL 로 설정을 켜고 확정하는 것은 막지 못한다 — 앱 계정 직접 SQL 은 감사 로그(UNKNOWN 행위자)로 드러나는 것으로 받아들인다(10장과 같은 판단).
- 정책을 Y 로 바꿔도 이미 확정된 리비전은 그대로. 폐기·새 리비전 만들기는 막지 않는다(확정만 통제).

### 14.3 일괄 대체 연동

- `GET /api/plm/ecos/{id}/replace-preview?fromItemCd&toItemCd` → `BomChangeService.replacePreview(…, 유효일)` 그대로.
- `POST /api/plm/ecos/{id}/replace` `{rowVer, fromItemCd, toItemCd, bomIds}` — ECO 작성 중 + rowVer → `BomChangeService.replace`(유효일 = ECO 유효일) → 결과 작성 중 BOM 마다 BOM 대상 추가(변경 내용 "X → Y"). 이미 그 ECO 의 대상이면 그대로, **다른 열린 ECO 에 묶인 리비전이면 전체 롤백**(400 `OPEN_ECO`, 대상 rowKey `b{bomId}`) — 일괄 대체만 되고 대상이 빠지는 반쪽 상태를 만들지 않는다.
- 권한: `plm-eco` SAVE. `mst-bom` 권한은 따로 보지 않는다(D3 과 같은 판단 — 결재선이 통제).

### 14.4 PPAP

```text
plm_ppap       eco_id(유일), cust_id(판매 거래처), submit_level(1~5, 기본 3), status(DRAFT/SUBMITTED/APPROVED/INTERIM/REJECTED),
               submitted_on, decided_on, decision_ref(PSW·승인 번호), decision_note, remark, row_ver, 감사
plm_ppap_elem  ppap_id × elem_no(1~18), req_cd(S/R/N), status(OPEN/DONE/WAIVED), file_id(→ sys_file, 그 ECO 첨부만), note, row_ver, 감사
```

- 요소 이름은 코드 그룹 `PLM_PPAP_ELEM`(번호 1~18, 문구 키). **레벨별 요구 표는 넣지 않는다**(AIAG 표 저작권 — docs/02 "구조만, 값은 고객 입력"). 기본 요구: 레벨 1 = PSW(18)만 S, 나머지 R / 레벨 5 = 전부 R / 그 외 = 전부 S. 고객 요구에 맞춰 화면에서 바꾼다.
- 전이: `DRAFT → SUBMITTED → APPROVED | INTERIM | REJECTED`, `INTERIM·REJECTED → DRAFT`(재제출). APPROVED 는 끝. 트리거 `ck_plm_ppap_transition`.
- 제출 조건: S 요소가 모두 DONE(또는 WAIVED), **PSW(18)는 DONE**. 내용(레벨·요소)은 DRAFT 에서만 수정 — 트리거 `ck_plm_ppap_fixed`.
- 판정 APPROVED(`plm-eco` APPROVE): ECO 가 고객 승인 필요이고 고객이 같고 ECO 가 적용 전이면 **같은 트랜잭션에서 ECO 고객 승인 = APPROVED(승인 번호 = 판정 번호, 승인일 = 판정일)** — 10장 "패키지가 ECO 를 가리킨다" 그대로. 칸을 옮기지 않는다.
- 첨부: 요소 파일은 ECO 첨부(`plm-eco`, 대상 id = ECO) 중 하나를 고른다 — 같은 회사·같은 ECO·사용 중인 파일만(트리거).

### 14.5 PCN

```text
plm_pcn  pcn_no(채번 PLM_PCN), eco_id, cust_id, notified_on, response_due(≥ 통지일), status(SENT/ACK/APPROVED/REJECTED),
         responded_on, response_note, note, row_ver, 감사
```

- 통지 기록은 ECO 가 취소가 아니면 언제나(적용 뒤 통지도 있다). 응답은 SENT 에서 한 번(`SENT → ACK·APPROVED·REJECTED`), 응답 뒤 불변. 같은 ECO·고객의 열린(SENT) 통지는 하나.
- ~~메일·외부 발송은 하지 않는다~~ → 2026-10-23 23장: 고객 담당자에게 메일(기록과 분리된 [메일 보내기]). 기록 + 응답 기한 지난 SENT 를 목록에 표시.
- PCN 은 게이트가 아니다 — 출하·적용 게이트는 ECO 고객 승인(PPAP 판정이 채움) 하나로 모은다.

### 14.6 남의 표·코드 변경

| 대상 | 변경 |
|---|---|
| `BomService.release`·`RoutingService.release` | 정책 Y 일 때만 `EcoReleaseGate` 확인 한 줄(N 이면 기존 그대로) |
| `mst_bom`·`mst_routing` | BEFORE UPDATE 트리거 `tg_plm_eco_required` 하나씩(정책 Y + 허용 표시 없음 + RELEASED 로 바뀔 때만 거부) |
| `BomChangeService.replace`·`replacePreview` | 호출만(코드 변경 없음) |

### 14.7 검토

- 허용 표시를 ThreadLocal 이 아니라 트랜잭션 로컬 DB 설정으로 둔 이유: 트리거(직접 SQL·다른 경로 방어)와 서비스가 **같은 신호**를 보고, 트랜잭션이 끝나면 저절로 사라진다(예외로 끄지 못해도 새는 일이 없다). 대가: 서비스 검사에 DB 왕복 1회 — 정책 Y 일 때만.
- 일괄 대체 실행을 ECO 대상 추가와 한 트랜잭션으로 묶은 이유: 따로 하면 "새 리비전은 생겼는데 어느 ECO 에도 안 묶인" 작성 중 BOM 이 남고, 정책 Y 회사에서는 그 리비전을 확정할 길이 ECO 하나뿐이다.
- PPAP 레벨 표를 시드하지 않은 것은 저작권과 고객별 차이(CSR) 때문이다 — 기본값은 "전부 제출(레벨 3)" 쪽으로 안전하게.
- 버린 대안: PCN 을 QMS `qms_pcn`(docs/02 원안)으로 — D1·Q4 에서 변경 키를 ECO 로 모았으므로 통지도 ECO 에 둔다. QMS 4M 확장(`qms_change`)은 그대로 3d.
- 구현 중 발견: 두 표 공용 트리거 함수에서 `CASE WHEN TG_TABLE_NAME = 'mst_bom' THEN NEW.bom_id ELSE NEW.routing_id END` 는 실행되지 않는 갈래의 칸도 해석해 **mst_bom 에서 "칸 없음" 오류**가 난다(시험에서 직접 SQL 이 42703 으로 실패). 메시지에는 표 이름만 쓴다.
- 화면: PPAP 18행·통지 표는 390 폭에서 상자 안 가로 스크롤(`plm.css` — 공통 page.css 는 건드리지 않음). 3b 의 "표시된 LOT" 표도 같이 감쌌다.

## 15. 2단계 — 도면 리비전(QMS Q3 `mst_doc_rev`)·첨부 고정(D7) (PLM 하위 세션, 2026-10-21)

> QMS Q3 병합(main, `mst_doc`·`mst_doc_rev`·`DocService`·공통 `FileLockRule`·`sys_file.locked_yn`) 뒤 착수. 마이그레이션 **V082**. QMS 표는 바꾸지 않는다(읽기·FK 참조만), 상태 변경은 `DocService` 공개 메서드로만.

### 15.1 도면 대상 (D8)

| 항목 | 내용 |
|---|---|
| 대상 유형 `DOC` | `plm_eco_target.doc_rev_id` → `mst_doc_rev`. 품목은 그대로 필수("어느 품목의 도면"). 문서 유형 **DRAWING** 의 **작성 중** 리비전만, 같은 회사(트리거) |
| 고르기 | 대상 추가에서 품목 코드 + 문서 번호 → 그 문서의 작성 중 리비전(없으면 `NO_DRAFT_REV` — QMS 화면에서 새 리비전부터) |
| 한 리비전 = 열린 ECO 하나 | BOM·라우팅과 같다 — 트리거가 문서 리비전 행을 `FOR UPDATE` 로 잠그고 확인(`uq_plm_eco_target_open`) |
| 상신 | 리비전이 지금도 작성 중(결재 중이면 `NOT_DRAFT` — 문서 결재와 ECO 결재가 겹치면 안 된다), `base_row_ver` 고정 |
| 적용 | 작성 중 + `row_ver` 같음 → `DocService.releaseFromChange(docRevId)`(이전 배포본 폐기 + 파일 잠금 표시까지 QMS 가 한다). BOM·라우팅과 한 트랜잭션 — 하나라도 실패하면 전부 롤백 |
| 결재 뒤 도면 파일 | 문서 리비전 첨부(`qms-doc`)는 QMS 규칙상 작성 중이면 바뀔 수 있다 → PLM `FileLockRule` 이 **결재 중·승인된 ECO 의 대상 리비전 첨부를 잠근다**(행 `row_ver` 는 파일 변경을 모르므로) |

### 15.2 첨부 고정 (D7 — 1단계 보류분)

| 대상 | 앱 규칙(`PlmFileLock`, 올리기·삭제) | DB 최종(`locked_yn = 'Y'`, 한 방향) |
|---|---|---|
| ECO 첨부 `plm-eco` × `{eco_id}` | 작성 중일 때만 열림(상신 = 고정, 반려·회수로 작성 중이 되면 다시 열림). 없는 ECO(다른 회사 포함) = 잠김 | 적용(RELEASED) 때 찍는다 |
| PPAP 증빙 `plm-eco` × `PPAP-{ppap_id}` | 패키지가 작성 중일 때만 | 고객 승인(APPROVED) 때 찍는다 |
| 대상 도면 `qms-doc` × `{doc_rev_id}` | 결재 중·승인된 ECO 의 대상이면 잠김(QMS 규칙과 OR) | 배포 때 QMS(`DocReleaser`)가 찍는다 |

- PPAP 증빙을 ECO 첨부와 분리한 이유: PPAP 는 보통 ECO 승인 **뒤** 시생산으로 만든다. ECO 첨부를 상신 때 고정하면 증빙을 올릴 곳이 없어진다. 요소 파일은 그 ECO 첨부 또는 그 패키지 증빙 중에서 고른다(트리거).
- 1단계의 "상신 뒤 추가" 표시는 이제 생기지 않는다(올리기 자체가 막힘) — 화면 표시 코드는 이전 데이터용으로 둔다.
- 대상별 트리거를 `sys_file` 에 걸지 않는다(상위 세션 결정 — 공통 트리거 하나 + 모듈 앱 규칙).

### 15.3 검토

- 도면 대상의 품목 필수를 유지한 것은 3b 표시 판정(대상 품목 = LOT 품목)과 화면 "품목 변경 이력" 이 같은 키를 쓰게 하려는 것. 문서의 적용 대상(`mst_doc_target`)과 품목이 맞는지는 검사하지 않는다 — 문서가 품목 클래스·공정에 걸릴 수도 있어서(QMS 규칙을 PLM 이 다시 판정하지 않는다).
- 문서 유형을 DRAWING 으로 좁힌 것은 D8 합의 범위. 작업표준서·관리계획서를 ECO 로 바꾸는 요구가 오면 유형 검사만 넓힌다(표·흐름 그대로).
- 적용 순서: BOM·라우팅 확정 → 도면 배포. 도면 배포가 실패해도(누가 그 사이 문서 결재를 올림) 전체 롤백이라 "BOM 은 새것, 도면은 옛것" 이 남지 않는다.

## 16. QMS 2부와 맞닿는 계약 — EcoLookup·EcoTrace.markedLots·EcoSubmitRule (PLM 하위 세션, 2026-10-21)

> QMS 2부 PLM 경계(docs/29_P3 11장) Q-a·Q-b 답 — PLM 이 먼저 main 에. 마이그레이션 없음. ③ `EcoQualityView`(QMS 제공)는 QMS 구현이 들어올 때 PLM 화면과 함께.

| 계약 | 패키지 | 내용 |
|---|---|---|
| ① `EcoLookup.eco(ecoId) → Optional<EcoView>` | `module.plm.link` | 번호·제목·변경 종류·상태·유효일·`markLots`·`markUntil`·고객 승인 상태 + 대상 품목 id(중복 없이)·설비·공정·도면 리비전 id. BOM·라우팅 리비전은 넣지 않는다(리비전 경로는 `EcoTrace`). 다른 회사·없는 ECO = 빈 값(RLS), 빈 없으면 `NONE` |
| ② `EcoTrace.markedLots(ecoId) → List<MarkedLot>` | 기존 `EcoTrace` 에 default 메서드 | LOT id·번호·표시 이유·seq·발생 시각, **도착 순**. 이유 필터는 쓰는 쪽(QMS 초품, Q-d 결과와 무관하게 계약 그대로) |
| ④ `EcoSubmitRule.check(ecoId) → List<FieldError>` | 인터페이스 PLM, 구현 다른 모듈(여러 빈) | `EcoService.submit` 에서 **ECO 행 잠금(FOR UPDATE)·rowVer 확인·기존 검사 뒤, 상태 변경 전** 모든 빈을 불러 칸 오류를 합친다(같은 트랜잭션, 400). 읽기만, 예외 대신 오류 목록. 빈 없으면 지금 동작 그대로 |

- ④ 를 결재 엔진의 `beforeSubmit` 이 아니라 `EcoService.submit` 에 둔 이유: 칸 오류를 ECO 폼에 그대로 돌려주고, ECO 행 잠금과 같은 트랜잭션에서 검사하려고.
- 잠금 순서: PLM 은 `plm_eco`(FOR UPDATE) → 규칙이 읽는 표. 규칙을 가진 모듈의 **저장 경로도 `plm_eco` 를 먼저 `FOR SHARE` 로 읽어야** 상신과 직렬화된다("상신 검사는 옛 4M 을 봤는데 결재는 바뀐 4M") — QMS 에 전달(상위 세션 경유).
- 시험: `PlmQmsContractTest` 3건(조회 계약·A사로 B사 빈 값·시험용 규칙 빈으로 상신 거부 → ECO DRAFT·`base_row_ver` 없음, 규칙 통과 → PENDING), `PlmLinkContractTest` NONE 단언.

### 16.1 DOC 대상 확장 합의 (QMS #88 6036b47 반영, 2026-10-21) — ✅ 구현 V088(임시 V9904, QMS 2부 Q5 착수 때)

| 항목 | 합의 |
|---|---|
| 유형 | DRAWING → DRAWING·WI·CP·INSP_STD (트리거 유형 검사 + 서비스 `NOT_DRAWING` 판정만) |
| 품목 | DOC 대상은 **품목 선택**(필수 아님) — `plm_eco_target.item_id` 를 DOC 일 때만 NULL 허용(CHECK). 3b 표시 판정은 DOC 를 보지 않으므로 영향 없음 |
| 4M 표시 | 4M ECO 는 품목 있는 EQUIP·PROC 대상이 따로 있어야 LOT 이 표시된다 — 없으면 QMS ④ 규칙이 "PROCESS_4M 인데 표시 가능한 대상 없음" 칸 오류(PLM 은 막지 않음) |
| 문서 적용 대상 | DOC 에 품목을 적었는데 그 품목·클래스가 `mst_doc_target` 에 없으면 **경고만**(막지 않음) — 상세 응답의 대상 행에 경고 표시 |
| 잠금 순서 | QMS 4M 저장은 서비스·트리거 모두 `plm_eco … FOR SHARE` 먼저 → 양쪽 plm_eco → qms_* 동일 (QMS 11.4) |

- 구현 메모(V088): `item_id` NOT NULL 해제 + `ck_plm_eco_target_item_req`(DOC 만 비움), 트리거 유형 검사 `ck_plm_eco_target_doc_type`(구 `ck_plm_eco_target_drawing`), 고정 칸 비교를 `IS NOT DISTINCT FROM` 으로(품목 NULL 행의 상신 고정이 막히지 않게). 서비스 오류 코드 `NOT_DRAWING` → `DOC_TYPE`. 경고 `docTargetWarn` = 문서에 품목·품목 클래스 적용 대상이 하나라도 있는데 이 품목·그 클래스가 없을 때(적용 대상이 공정뿐이거나 없으면 경고 없음).

### 16.2 QMS 2부 Q5 추가 계약 (docs/29_P3 12.1 Q5-5, 2026-10-21)

| 계약 | 내용 |
|---|---|
| `EcoLookup.byNo(ecoNo)` | ECO 번호(대소문자 무시)로 — QMS "ECO 번호로 열기". 다른 회사·없는 번호 = 빈 값 |
| `EcoLookup.ecos(ids)` | 여러 ECO 한 번에(머리 1회 + 대상 1회 쿼리) — 없는·다른 회사 id·null·중복은 빠짐, 순서 eco_id, 상한 `MAX_IDS` 500(초과 IllegalArgumentException) |
| ③ `EcoQualityView.summary(ecoId) → Optional<Summary>` | 인터페이스 + NONE = PLM, 구현 = QMS. `Summary(hasChange, fourMTypes, charCount, firstOk, firstNg, firstPending)` — 초품 수는 Q7 전까지 null. ECO 상세 응답 `quality`(NONE 이면 null) → 화면 "품질(4M)" 영역 + `/page/qms-change?ecoId=` 링크(없으면 영역 숨김) |

- 두 default 메서드라 기존 `EcoLookup.NONE`·MES 쪽 코드는 그대로 컴파일된다.
- QMS 매퍼는 `plm_eco` 를 SELECT 하지 않는다 — 예외는 11.4 의 잠금용 `FOR SHARE` 읽기뿐.
- QMS Q7 초품(V104) 뒤: 요약의 초품 수(OK·NG·미판정)가 채워지고, 첫 LOT 표시가 하나라도 있으면 "품질(4M)" 영역에 `/page/qms-setup?ecoId=` 링크("초품 판정에서 열기")를 함께 보인다. 4M 세부가 없어도 첫 LOT 이 있으면 요약이 온다(QMS 구현).

### 16.3 QMS Q9 착수 게이트용 읽기 계약 (docs/29_P3 12.9 R-1, 2026-10-22)

| 계약 | 내용 |
|---|---|
| `EcoTrace.firstLotsOfItem(itemId, limit) → List<FirstLot(ecoId, ecoNo, lotId, lotNo, seqNo, occurredAt)>` | 그 품목 LOT 에 붙은 **FIRST_LOT** 표시만(4M_WINDOW·TEMP 제외), 적용(RELEASED) ECO 만, **최근 표시부터**(recorded_at, eco_lot_id 내림) limit 개. limit 1~`MAX_FIRST_LOTS`(500) 밖이면 IllegalArgumentException. 다른 회사·없는 품목은 빈 목록. default 메서드(PLM 없음 = 빈 목록) |

- 읽기만·잠금 없음 → QMS 착수 트랜잭션(공정 행 FOR UPDATE 뒤)에서 불러도 잠금 순서 문제 없음. QMS 는 이 중 살아 있는 초품 판정이 없는 LOT 만 남긴다(QMS 몫).

### 16.4 PMS PM7 PPAP 조회 계약 (상위 세션 요청 2026-10-22)

| 계약 | 내용 |
|---|---|
| `EcoLookup.ppap(ecoId) → Optional<PpapView(ppapId, status, decidedOn)>` | ECO 하나의 PPAP 패키지(유일). 없음·다른 회사·없는 ECO = 빈 값, 읽기만·잠금 없음, default 메서드(PLM 없음 = 빈 값) |

- PMS 가 `PpapService` 를 직접 부르던 것을 이 계약으로 바꾼다(PMS 몫). 요소·고객·판정 번호는 넣지 않는다 — 필요해지면 그때 칸을 더한다.

## 17. 3d — 출하 게이트 PLM 계약 `EcoTrace.shipChecks` + 추천안 확정 (PLM 하위 세션, 2026-10-22)

> 상위 세션 지시(2026-10-22, 사용자 "멈춘 하위 세션은 추천안으로 계속"): 3d 착수. QMS 쪽 3d(`qms_change` 1:1·초품 입력 목록)는 QMS 2부 Q5(V094)·Q7(V104)로 main 에 들어왔다. 남은 것은 **출하 게이트**(02 2.3 "고객 승인 전 변경 LOT 출하 금지")이고, 출하(WMS B6)가 아직 없으므로 PLM 은 **읽기 계약만** main 에 먼저 둔다(16장 QMS 계약과 같은 순서). 마이그레이션 없음.

### 17.1 지금 코드에서 확인한 사실

| # | 사실 | 위치 | 뜻 |
|---|---|---|---|
| S1 | ECO 적용(RELEASED)은 고객 승인 필요(`cust_approval_req_yn = 'Y'`)이면 `cust_approval_status = 'APPROVED'` 일 때만 — 트리거 `ck_plm_eco_cust_gate`. 적용 뒤 승인 칸은 고정 | V050 | **LOT 에 걸리는 ECO(적용된 것)는 이미 고객 승인을 받았다** — 출하 게이트가 ECO 승인 칸을 다시 볼 필요 없음 |
| S2 | ECO 고객 승인은 PPAP 승인 판정이 채우거나 **담당이 승인 번호로 직접** 적는다(14.4). PPAP 판정은 ECO 적용 뒤에도 바뀐다(INTERIM·REJECTED → DRAFT 재제출) | V069·`EcoService.custApproval` | ECO 는 승인됐는데 PPAP(PSW)는 아직인 상태가 생긴다 — **출하 게이트가 막을 실제 빈칸은 이것** |
| S3 | LOT 에 걸리는 ECO = (가) 리비전 경로 + (나) 표시(13.3 `ecosOfLot`) | `PlmLinkMapper.xml` | 첫 N 뒤의 LOT(표시 없음)도 새 리비전이면 변경 LOT |
| S4 | WMS 출하(B6)·정책 `SHIP_BLOCK_UNAPPROVED_CHANGE` 는 main 에 없다. WMS 잠금 순서 = `lot_master → ECO → 기간·inv_stock`(docs/30 11.6) | docs/30 | PLM 계약은 잠그지 않는 읽기여야 그 순서 어디서든 부를 수 있다 |

### 17.2 계약

```java
// module.plm.link.EcoTrace (default 메서드 — PLM 없음 = 빈 목록)
record ShipCheck(long lotId, long ecoId, String ecoNo, String reason, String ppapStatus, boolean block) {}
List<ShipCheck> shipChecks(Collection<Long> lotIds);
```

- 대상 = LOT 에 걸린 **적용(RELEASED) ECO** 중 **PPAP 패키지가 있고 APPROVED 가 아닌 것**. 문제 없는 LOT 은 결과에 없다.
- `reason`: `PPAP_OPEN`(DRAFT·SUBMITTED·REJECTED → `block = true`) · `PPAP_INTERIM`(임시 승인 → `block = false`, 경고).
- 읽기만·잠금 없음. 없는·다른 회사 LOT·null·중복은 빠짐, 순서 lot_id·eco_id, 상한 `EcoLookup.MAX_IDS` 500(초과 IllegalArgumentException — 출하 한 건의 LOT 수보다 넉넉).
- **판정은 PLM, 켜고 끄기는 WMS**: 무엇이 "미승인 변경" 인지는 PLM 규칙(PPAP 상태)이라 PLM 이 정하고, 출하를 막을지(정책)·화면 문구는 WMS 가 정한다.

### 17.3 결정 — 사용자 확인 2026-10-22: 추천안

| # | 질문 | 추천(확정) | 대안 |
|---|---|---|---|
| G1 | 게이트 대상 LOT | **리비전 경로 + 표시 둘 다**(S3) — 새 리비전으로 만든 LOT 은 첫 N 뒤라도 변경품 | 표시(첫 N)만 — 그 뒤 LOT 이 PPAP 전에 나간다 |
| G2 | PPAP 임시 승인(INTERIM) | **막지 않고 경고**(`block = false`) — 고객 임시 승인은 조건부 출하 허락이다. 조건(수량·기한) 관리는 범위 밖 | 차단 — 고객이 허락한 출하까지 막는다 |
| G3 | WMS 정책 `SHIP_BLOCK_UNAPPROVED_CHANGE` 기본값 | **Y**(12.6 Q5 초안의 N 을 바꿈) — 계약은 PPAP 가 있는 ECO 만 돌려주므로 PPAP 를 쓰지 않는 회사는 영향 없고, 쓰는 회사에서 PSW 전 출하는 IATF 위반이라 기본이 막는 쪽이어야 한다. 출하가 아직 없어 기존 동작 변화도 없다 | N(켜야 막음) |
| G4 | 하위 LOT 전개(자재 LOT 의 ECO 로 완제품 출하를 막나) | **하지 않음** — 그 LOT 에 직접 걸린 ECO 만. 자재 변경의 고객 승인은 자재 LOT 출하·입고 쪽 문제 | 계보 전개 — 비용 크고 고객이 다르다 |
| G5 | ECO 고객 승인 칸 검사 | **하지 않음**(S1 — 적용 게이트가 보장). 계약에 이유 코드를 두지 않는다 | 방어용으로 한 번 더 — 도달할 수 없는 분기 |

### 17.4 WMS 에 전하는 초안 (B6 착수 때 WMS 가 정함)

- 출하 확정(또는 피킹 확정) 트랜잭션에서 `lot_master` 잠금 뒤 `EcoTrace.of(provider).shipChecks(lotIds)` 한 번. 정책 Y 이고 `block = true` 가 하나라도 있으면 409(예 `SHIP_CHANGE_UNAPPROVED`, 칸 오류 rowKey = LOT) — 문구에 ECO 번호. `block = false` 는 화면 경고.
- 잠금이 없으므로 "PPAP 승인 직후 출하" 는 다음 시도에 통과하고, "임시 승인 → 재제출(DRAFT)" 과 동시인 출하는 임시 승인 시점 기준으로 나갈 수 있다 — 고객이 이미 허락한 상태에서 나간 것이라 받아들인다(PLM 잠금을 출하 경로에 끌어들이지 않는다).
- WMS 매퍼는 `plm_*` 를 조회하지 않는다(16장 QMS 와 같은 규칙).

### 17.5 시험

`PlmEcoLotTest` +1: 리비전 경로 LOT(첫 N 뒤, 표시 없음)·표시 LOT(지시 없는 4M)·이전 리비전 LOT / PPAP 없음 → 빈 목록 / DRAFT → 두 LOT 차단 / SUBMITTED → INTERIM(경고)·APPROVED(빠짐) / 재제출 뒤 REJECTED → 다시 차단 / null·중복·없는 id 빠짐, 빈 목록, 상한 초과 IllegalArgumentException / **A사로 B사** B사 범위에서 A사 LOT → 빈 목록. `PlmLinkContractTest` NONE 빈 목록.

### 17.6 검토

- 원안 12.3 의 "ECO 고객 승인이 APPROVED 아니면 차단" 은 적용 게이트(S1) 때문에 실제로는 생기지 않는다 — 그대로 구현했으면 늘 통과하는 게이트였다. 막아야 할 빈칸은 PPAP(S2)라 판정 기준을 옮겼다.
- 계약을 `ecosOfLot` 결과에 PPAP 칸을 더하는 방식으로 하지 않은 이유: 출하는 LOT 여러 개를 한 번에 본다(N+1), 그리고 "막을지" 판정이 쓰는 쪽마다 달라지면 안 된다.
- 남은 PLM 일: 4단계 적용 조건 "재고 소진" → 18장(2026-10-22 착수).

## 18. 4단계 남은 것 — 적용 조건 "재고 소진" (PLM 하위 세션, 2026-10-22)

> 상위 세션 지시(2026-10-22, 사용자 "멈추지 말고 추천안으로"): docs/35 A11 의 적용 조건(날짜·LOT·재고 소진) 중 1단계는 날짜만, 4단계에서 "재고 소진" 은 WMS 원장 뒤로 미뤘다(14장 머리). WMS 원장·계약 `StockQuery`(docs/30 13장)가 main 에 들어왔으므로 착수. 재고 수량은 **`StockQuery` 로만** 읽고 `inv_*` 를 조회하지 않는다. 마이그레이션 **임시 V9905**.

### 18.1 무엇을 하나

| 항목 | 내용 |
|---|---|
| 칸 (`plm_eco`) | `apply_cond`(DATE 기본·STOCK_OUT, 공통코드 `PLM_APPLY_COND`), `stock_item_id`(소진 품목 → `mst_item`, 같은 회사 트리거), `stock_plant_cd`(→ `sys_plant` 복합 FK). STOCK_OUT 이면 둘 다 필수, DATE 면 둘 다 없음(CHECK `ck_plm_eco_stock_out`) |
| 고정 | 작성 중에만 수정 — 별도 트리거 `hb_guard_plm_eco_stock_out`(기존 `hb_guard_plm_eco` 를 다시 쓰지 않는다 — V050·V060·V088 이 고친 함수를 통째로 재정의하면 어긋날 자리가 생긴다). 오류 이름은 같은 `ck_plm_eco_fixed` |
| 적용(`EcoService.release`) | STOCK_OUT: `StockQuery.available(공장, [품목])` > 0 이면 409 `PLM_STOCK_REMAIN`(칸 오류 `stockItemCd` `STOCK_REMAIN` + 남은 수량), 0 이하면 기존 흐름 그대로. **D5(유효일 지나면 거부)를 적용하지 않고 실제 적용일 = max(유효일, 오늘)** — BOM·라우팅 `valid_from` 에 그 날짜가 들어간다. ECO 유효일(결재 내용)은 바꾸지 않는다 |
| 상세 | STOCK_OUT 이고 적용 전(작성 중·결재 중·승인됨)이면 `eco.stockAvailable` = 조회 시점 가용 재고. 화면 "남은 가용 재고" |
| 화면 | ECO 폼에 적용 조건·소진 품목 코드·재고 공장(STOCK_OUT 일 때만 입력), 상태 줄에 남은 재고 |

### 18.2 결정 — 사용자 확인 2026-10-22: 추천안

| # | 질문 | 추천(확정) | 대안 |
|---|---|---|---|
| R1 | 무엇을 세나 | **`StockQuery.available` 그대로 — AVAILABLE 만(보류 LOT·QC·폐기 위치 제외)**. 보류·검사 대기 옛 품목은 MRB·처분 대상이지 "쓸 재고" 가 아니다. 계약에 있는 것만 써서 WMS 추가 요청 없음 | 모든 상태 합 — WMS 계약 추가 필요, QC 재고 하나 때문에 전환이 막힌다 |
| R2 | 소진 품목 개수 | **ECO 하나에 하나** — 일괄 대체 X→Y 의 X 가 보통 하나. 여럿이면 ECO 를 나눈다 | 품목 여러 개 표(조건 표) — 지금 요구 없음 |
| R3 | 공장 | **필수** — `StockQuery` 가 공장 단위이고, BOM 은 공장이 없어 고를 수 없다 | 회사 전체 합 — 계약에 없음 |
| R4 | 남은 수량 허용 | **0 이하일 때만**(잔량 허용 칸 없음) — 잔량을 버리거나 전용하는 판단은 사람이 하고, 그 뒤 재고 조정으로 0 을 만든다(근거가 원장에 남는다) | 허용 잔량 칸 — 원장에 근거 없이 옛 품목이 남는다 |
| R5 | 유효일이 지난 뒤 소진되면 | **적용일 = 오늘(유효일은 "가장 이른 날")** — 소진 조건은 날짜를 미리 알 수 없어 D5 를 그대로 쓰면 늘 취소·재작성이 된다 | D5 그대로 거부 |
| R6 | 자동 적용(소진되는 순간 스케줄러가 적용) | **하지 않음** — 적용은 사람이 누르는 동작(D2). 상세에 남은 재고를 보여 주고, 0 이면 적용 버튼이 통한다 | 스케줄러 자동 적용 — 결재 뒤 동작을 사람 없이 하게 됨, `TenantRunner` 배치 필요 |
| R7 | WMS 없는 설치본 | **STOCK_OUT 저장을 막는다**(칸 오류 `applyCond` `NO_WMS`), 적용 때도 같은 검사(409) — `StockQuery.NONE` 은 0 을 돌려주므로 그대로 두면 늘 "소진됨" 으로 통과한다 | NONE 그대로 — 조건이 무의미 |

### 18.3 잠금·동시성

- 적용은 `plm_eco FOR UPDATE` → (STOCK_OUT 이면) `StockQuery` 읽기(잠금 없음) → BOM·라우팅 확정. 재고 행을 잠그지 않는다 — 읽은 직후 옛 품목이 입고돼도 "적용 순간 0" 이었다는 판단은 맞고, 그 뒤 들어온 옛 품목은 MRB·전용 대상이다(WMS·MES 잠금 순서 `lot_master → ECO → 기간·inv_stock` 에 PLM 이 inv 잠금을 끼워 넣지 않는다).

### 18.4 시험 (`PlmStockOutTest` 5)

정상: 가용 5 → 상세 5·적용 409(남은 5, ECO 승인됨·BOM 작성 중 그대로) → 조정 -5 → 상세 0·적용 → BOM 확정 `valid_from` = 오늘, 적용 뒤 상세에 재고 없음 / 유효일이 어제가 된 STOCK_OUT 은 적용(valid_from = 오늘), 같은 상황 DATE 는 `PLM_EFFECTIVE_PAST`.
이상 사용: E 값(조건 코드, 품목·공장 필수·없음, DATE 에 소진 품목 `STOCK_COND`, 비우면 DATE, 직접 SQL 품목 없는 STOCK_OUT → CHECK) / C 상신 뒤 조건·품목 직접 SQL 수정 → `ck_plm_eco_fixed` / B **A사로 B사**(B사에서 A사 품목 코드 → 없음, B사 ECO 에 A사 품목 id 직접 SQL → `fk_plm_eco_stock_item_comp`, A사 재고 5 가 있어도 B사 상세 0, A사 사용자가 B사 ECO 상세 → 404).
- 시험 한계: WMS 빈이 없는 설치본(R7)은 같은 앱 컨텍스트에서 만들 수 없어 자동 시험이 없다 — 코드 경로는 `getIfAvailable() == null` 두 곳.

### 18.5 검토

- 재고 판단을 PLM 이 직접 SQL 로 하지 않고 계약을 쓴 대가: AVAILABLE 만 볼 수 있다(R1). 이것이 오히려 업무에 맞다고 판단했다 — 전환을 막아야 하는 재고는 "쓸 수 있는 옛 품목" 뿐이다.
- LOT 조건(특정 LOT 부터 적용, A11 의 셋째)은 하지 않았다 — 3b 의 변경 표시(첫 N LOT)와 지시 리비전 스냅샷이 "어느 LOT 부터 새것" 을 이미 기록한다. 요구가 오면 별도 단계.

## 19. 전자서명 연결 — ECO 결재 승인·PPAP 판정 (PLM 하위 세션, 2026-10-22)

> 상위 세션 지시(2026-10-22, 사용자 "멈춘 세션은 추천안으로"): 시스템관리 전자서명(V116 `sys_esign`, `platform.esign.ESignService`, docs/36 16.5)을 PLM 승인 지점에 붙인다. PLM 이 첫 사용 모듈. 마이그레이션 **V126(임시 V9906)**(정책 정의 한 줄 — 표 변경 없음).

### 19.1 흐름

| 지점 | 화면 | 서버(한 트랜잭션) | 의미 |
|---|---|---|---|
| ECO 결재 승인 | 승인 → `hb.esign` 대화상자(비밀번호 + MFA 등록자는 인증 코드) → 토큰 → `POST /api/plm/ecos/{id}/approve {comment, esignToken}` | ECO 결재 중 확인 → `ESignService.consume(…, "plm_eco", ecoId, hash)` → `ApprovalService.approve`(결재 엔진이 결재 행 잠금 → 마지막이면 핸들러가 ECO 를 APPROVED). 결재 엔진이 거부하면(내 차례 아님) 서명 행도 롤백 | 합의 줄 `REVIEW`, 결재 줄 `APPROVE` |
| PPAP 고객 판정 기록 | 판정 기록 → `hb.esign` → `POST /api/plm/ppaps/{id}/decide {…, esignToken}` | 기존 판정(승인이면 ECO 고객 승인까지) → 판정이 들어간 내용으로 `consume(…, "plm_ppap", ppapId, hash)` | `AUTHOR` |
| 반려·회수 | 공통 결재 API 그대로 | 서명 없음 | — |
| 상세 | ECO 결재 영역·PPAP 영역에 서명 목록 | `ESignService.list` + 지금 내용 해시와 다르면 `changed` → "서명 뒤 바뀜"(빨강) | — |

### 19.2 정규 글자 (`PlmSignContent`, 판 v1)

형식: 머리 줄(`PLM_ECO v1` / `PLM_PPAP v1`) + 칸마다 한 줄 `길이:값`(null 은 `-`) — 값 안에 구분자가 있어도 모호하지 않다. 순서를 바꾸면 이전 서명이 모두 "바뀜" 으로 보이므로 바꿀 때는 판을 올린다.

| 대상 | 칸(순서대로) |
|---|---|
| ECO | 번호·제목·사유·설명·유효일·담당 id·고객 승인 필요·고객 코드·변경 종류·첫 N·표시 끝·적용 조건·소진 품목 코드·재고 공장 / 대상마다 `T` 줄 + 순서·유형·품목 id·BOM id·라우팅 id·설비 id·공정 id·문서 리비전 id·**상신 때 고정한 리비전 row_ver**·변경 내용 (순서 = 대상 seq) |
| PPAP | 패키지 id·ECO id·고객 코드·제출 레벨·상태·제출일·판정일·판정 번호·판정 메모 / 요소마다 `E` 줄 + 번호·요구·상태·파일 id (순서 = 요소 번호) |

- ECO 칸은 **상신 뒤 고정되는 칸**(트리거 `ck_plm_eco_fixed`·대상 고정)만 — 결재 중에 바뀔 수 없으므로 "서명 뒤 바뀜" 은 반려·회수 → 수정 → 재상신에서만 생긴다.
- 대상 리비전은 **지금** row_ver 가 아니라 상신 때 고정한 `base_row_ver` — 적용(확정)이 row_ver 를 올려도 서명이 "바뀜" 이 되지 않게. 결재 뒤 리비전 수정은 기존 `PLM_TARGET_CHANGED` 표시가 맡는다.
- 상태·결재 시각·고객 승인 상태는 넣지 않는다(서명이 그 상태를 만든다).

### 19.3 결정 — 사용자 확인 2026-10-22: 추천안

| # | 질문 | 추천(확정) | 대안 |
|---|---|---|---|
| S1 | 서명 필수 여부 | 회사 정책 **`PLM_ESIGN_REQUIRED`(기본 N)**. 화면은 정책과 관계없이 늘 서명 대화상자를 띄운다 — N 이면 서명 없는 API 호출(결재함·자동화)도 통과, Y 면 PLM 승인 API·PPAP 판정은 토큰 필수(403 `PLM_ESIGN_REQUIRED`) | 기본 Y — 기존 회사의 결재함 승인이 바로 막힌다 |
| S2 | 공통 결재함으로 승인하면 | 정책 Y 면 **결재를 끝내는 승인**은 결재 완료 핸들러(`EcoApprovalDocType.onFinished`)가 "이 트랜잭션에서(`signed_at = now()`) 이 사람이 지금 내용으로 APPROVE·REVIEW 서명" 을 확인하고 없으면 403 → 결재 전체 롤백. **중간 줄(합의 등)은 막지 못한다** — 결재 엔진에 줄 단위 확장점이 없다(플랫폼 요청 후보: `ApprovalDocType.beforeAct`) → **2026-10-23 해소(22장)**: 확장점 `beforeAct` 로 줄마다 서명 검사, `onFinished` 의 확인은 뺐다 | 플랫폼 결재 API 를 지금 고침 — 남의 코드, 모든 문서 유형에 영향 |
| S3 | 서명 지점 | ECO 결재 승인(합의 REVIEW·결재 APPROVE) + PPAP 판정 기록(AUTHOR). **ECO 적용(release)·반려·회수·PCN 응답은 서명 없음** — 적용은 결재받은 내용의 실행, 반려는 결재 의견이 근거 | 적용에도 서명 — 같은 내용에 두 번 서명 |
| S4 | PPAP 판정의 의미 | **AUTHOR(작성)** — 승인한 사람은 고객이고, 우리 사용자는 "고객 판정을 이 내용으로 기록했다" 를 서명한다 | APPROVE — 고객 판정을 사내 승인처럼 보이게 함 |
| S5 | 정규 글자 범위 | 상신 뒤 고정되는 칸 + 대상의 고정 row_ver(19.2) | 화면 표시 전체(JSON) — 이름 칸 변경·필드 추가마다 "바뀜" |

- 참고(상위 검토 2026-10-22): 정책 Y 인데 토큰 없음의 응답이 모듈마다 다르다 — PLM 403 `PLM_ESIGN_REQUIRED`, QMS 400 `QMS_ESIGN_REQUIRED`. 통일은 플랫폼 후속(공통 오류 코드)에서 하고 지금은 그대로 둔다. → **2026-10-23 결정 #49: 403 으로 통일(QMS 를 400 → 403), 오류 코드 이름·문구는 모듈별 그대로 — docs/36 18장.**

### 19.4 잠금·트랜잭션

- PLM 승인 API 는 ECO 행을 잠그지 않는다 — 결재 엔진 순서(결재 행 → 핸들러가 ECO)를 그대로 둔다. ECO 내용은 결재 중 고정이라 잠그지 않고 읽어도 해시가 맞다.
- 서명 토큰 발급(`/api/platform/esign/token`)은 업무 트랜잭션 밖(플랫폼 규칙 — 비밀번호 비교가 연결을 쥐지 않게), 소비는 업무 트랜잭션 안. 업무가 실패하면 서명 행도 없고 토큰은 쓴 것으로 남는다(다시 서명).

### 19.5 시험 (`PlmEsignTest` 7)

정상: 합의 REVIEW → 결재 APPROVE 서명으로 승인, 상세 서명 2건(이름 스냅샷·방식·바뀜 없음), 결재 의견도 남음 / 반려 → 수정 → 이전 서명 "바뀜" / 정책 N 은 서명 없이 승인(서명 행 0) / PPAP 임시 승인 판정 + AUTHOR 서명 → 재제출(작성 중) 뒤 "바뀜".
이상 사용: 정책 Y — 토큰 없는 PLM 승인 403, 공통 결재함 마지막 승인 403 + 롤백(ECO 결재 중·결재 줄 그대로), 중간 합의 줄은 통과(한계 S2 를 시험으로 고정) / E·F 토큰 — 다른 ECO·다른 의미·다른 사람·다른 세션·재사용 → 403 `ESIGN_TOKEN`, 내 차례 아닌 사람의 서명 → 결재 거부 + 서명 행 롤백, 결재 중이 아닌 ECO 409 / PPAP — 정책 Y 토큰 없음 403·의미 다른 토큰 403(판정 롤백) / **A사로 B사** — B사 사용자가 A사 ECO id 로 토큰 → 승인 404, 어느 회사에도 서명 행 없음, 상세 404.

## 20. 다음 단계 계획 (PLM 하위 세션, 2026-10-22)

> 계획 단계(1·2·3a~3d·4·재고 소진·전자서명)가 모두 main 에 들어간 뒤 남은 일 정리. 사용자 요청으로 작성, 순서는 상위 세션 확정 대기.

### 20.1 바로 할 수 있는 것 (PLM 단독, 마이그레이션 없음)

| 순서 | 항목 | 근거 | 규모 |
|---|---|---|---|
| 1 | 화면 영문 코드 → 문구 — 영향 대상 "지금 상태"(`DRAFT` 등)·결재선 상태(`CURRENT` 등) | 11장 "남은 작은 것"(1단계부터 미룸), `eco-mgt.js` 대상 표·결재선 표. 공통코드 `BOM_STATUS` 는 이미 있음 | 작음 |
| 1 | 11장 진행표 정리 — 3c(시험 끝)·3d·16장 계약·18장·19장 반영 | 표의 3c 행이 "—" 그대로 | 문서만 |
| 2 | PLM 홈 카드(`HomeCardProvider`, docs/37 규약) — 승인됐는데 적용 안 한 ECO(유효일 임박·지남) / 응답 기한 지난 PCN / 제출 대기 PPAP | 다른 모듈(MES·QMS·WMS·CMS·PMS·영업)은 카드가 있고 PLM 만 없음. 새 표 없음 | 중간 |

### 20.2 설계 판단이 필요한 것

| 순서 | 항목 | 추천 |
|---|---|---|
| 3 | 영향 대상 후보 보기 — 바뀌는 부품을 쓰는 상위 BOM(역전개)을 ECO 대상 후보로 | 9장에서 미룬 것. 14.3 일괄 대체 미리보기가 일부를 대신하므로 "후보 보기"(대상 추가는 사람이)만 |
| — | 적용 조건 "LOT"(docs/35 A11 셋째) | **보류** — 변경 표시(첫 N LOT)·지시 리비전 스냅샷이 "어느 LOT 부터 새것" 을 이미 기록(18.5). 요구가 오면 |
| — | PCN 실제 발송 | **시스템관리 알림 채널 뒤** — 지금은 기록만(14.5) |

### 20.3 다른 세션이 먼저 (PLM 계약은 준비됨)

| 세션 | 할 일 | PLM 쪽 |
|---|---|---|
| WMS(B6 출하) | 출하 확정 때 `EcoTrace.shipChecks` 호출 + 정책 `SHIP_BLOCK_UNAPPROVED_CHANGE` | 계약 ✅, 초안 17.4 |
| 시스템관리 | 결재 엔진 줄 단위 확장점(`ApprovalDocType.beforeAct`) — 정책 Y 에서 중간 줄도 서명 강제(19.3 S2 한계) — ✅ 2026-10-23 확장점 생김(docs/36 18.2) | ✅ 2026-10-23 PLM 연결(22장) |
| 플랫폼 | 서명 필수 응답 코드 통일(PLM 403 / QMS 400) — ✅ 2026-10-23 403 으로(docs/36 18장) | 19.3 참고 줄 |

### 20.4 하지 않을 것 (유지)

EBOM/MBOM 분리·CAD/PDM 연동(9장), 재고 소진·유효일 자동 적용(D2·R6 — 적용은 사람이 누른다).

### 20.5 추천 순서

① 영문 코드 문구화 + 진행표 정리(작은 PR 하나) → ② PLM 홈 카드 → ③ 영향 대상 후보 보기. ①·② 마이그레이션 없음, ③ 은 착수 때 이 문서에 설계 절을 먼저 쓴다.

### 20.6 ② PLM 홈 카드 설계 (2026-10-22, 마이그레이션 없음)

docs/37 규약(`HomeCardProvider` — 자기 패키지 빈, 화면 SEARCH 권한이 있을 때만, 카드마다 읽기 전용 트랜잭션, 숫자 = "지금 손댈 것", 숫자 링크는 화면이 조건을 다 받을 때만) 그대로. 화면 `plm-eco`, sort_no = PLM 화면 대역(985~).

| 카드 | 숫자 | 보조 숫자 | 항목(≤5) | 숫자 링크 |
|---|---|---|---|---|
| `plm-eco-approved` 적용 대기 설계 변경 | 결재 승인됐는데 아직 적용 안 한 ECO(`APPROVED`) | 유효일 지남(적용하면 D5 거부 → 취소 후 다시 — WARN) · 고객 승인 대기(필요인데 APPROVED 아님) · 재고 소진 조건 | 유효일 빠른 순, "제목 · 유효일" (지남은 WARN) | `status=APPROVED` — ECO 목록 조회 조건 그대로, 숫자 ≤ 목록 상한(500)일 때만 |
| `plm-cust-wait` 고객 응답 대기 | PPAP 제출됨(판정 대기) + PCN 응답 기한 지남(SENT, 기한 < 오늘) | PPAP 판정 대기 · PCN 기한 지남 | PCN 기한 지남(기한 순) → PPAP 판정 대기(제출일 순) | ~~없음 — ECO 목록에 PPAP·PCN 조건이 없어 숫자와 목록이 달라진다(docs/37 H27)~~ → **`status=CUST_WAIT`**(2026-10-24, 30장 — 숫자도 지시 수로) |

- 상태(톤): 카드 1 = 유효일 지남이 있으면 WARN, 카드 2 = PCN 기한 지남이 있으면 WARN, 그 밖 NEUTRAL. 사람별 집계 없음(R9). 날짜 = 회사 오늘(`HomeCardContext.today()`), 공장 카드 아님.
- 취소된 ECO 의 PPAP·PCN 은 세지 않는다(끝난 것 제외, R8).
- 버린 안: 카드 3장(PPAP·PCN 따로) — 둘 다 "고객에게서 답이 와야 하는 것" 이라 한 장의 보조 숫자로 충분, 홈 카드 수를 늘리지 않는다.

### 20.7 ③ 영향 대상 후보 보기 — 설계 (2026-10-22, 상위 검토 통과 — I1~I6 추천안 확정, 구현 2026-10-22)

> 9장 "ECO 대상 자동 탐지(역전개로 영향 BOM 추천)" 의 범위 좁힌 판. 바뀌는 부품(자품목)을 쓰는 상위 BOM 을 ECO 화면에서 찾아 **후보로 보여 주고, 사람이 골라 대상에 넣는다**. 마이그레이션 없음 예정.

#### 20.7.1 지금 있는 것 (코드 확인)

| # | 사실 | 위치 | 뜻 |
|---|---|---|---|
| I-F1 | 역전개 `BomTreeService.whereUsed(itemCd, date, allRevs, topOnly)` — 재귀 CTE 한 번, 날짜 기준 유효 리비전, 레벨·행 상한 정책(`BOM_MAX_LEVEL`·`BOM_EXPLODE_MAX_ROWS`), 행마다 `bomId`·모품목·레벨·`top` | master `bom` (P1 3b), 화면 권한 `mst-bom-where` | PLM 이 역전개를 다시 짤 필요가 없다 — 공개 서비스 메서드를 부른다(1단계 원칙: BOM 규칙을 PLM 이 다시 구현하지 않는다) |
| I-F2 | ECO BOM 대상 = **작성 중 리비전만**, 한 리비전 = 열린 ECO 하나(`uq_plm_eco_target_open`, 서비스 `OPEN_ECO`) | V050 트리거·`EcoService` 대상 저장 | 후보가 확정본이면 바로 넣을 수 없다 — 작성 중 리비전이 있어야 한다 |
| I-F3 | 일괄 대체(14.3)는 자품목 X → Y 로 **작성 중 리비전을 만들고** 그 ECO 대상으로 넣는다 | `EcoService.replace` | "X 가 바뀌는데 Y 로 바꿀 것" 이 정해졌으면 일괄 대체가 이미 답이다. 후보 보기는 **"X 가 바뀌면 어디까지 영향인가" 를 먼저 보는 단계** |
| I-F4 | 라우팅은 자품목을 참조하지 않는다(품목 자신의 공정) | V022 | 자품목 변경으로 라우팅 후보는 생기지 않는다 |

#### 20.7.2 만들 것

- API `GET /api/plm/ecos/{id}/impact?itemCd=&scope=PARENT|ALL` (권한 `plm-eco` SEARCH, 읽기만) — ECO 유효일 기준 `whereUsed(itemCd, 유효일, allRevs=false, topOnly=false)` 를 부르고, 모품목마다 PLM 이 붙이는 칸:

| 칸 | 내용 |
|---|---|
| `level` · `parentCd/Nm` · `rev`(유효일에 쓰이는 확정본) · `path` | 역전개 결과 그대로 |
| `draftBomId` · `draftRev` | 그 모품목의 작성 중 BOM 리비전(있으면) |
| `state` | `IN_THIS`(이 ECO 대상에 이미) · `OPEN_ECO`(다른 열린 ECO 에 묶임 + 그 번호) · `NO_DRAFT`(작성 중 리비전 없음 — 일괄 대체 또는 BOM 화면에서 새 리비전) · `ADDABLE` |

- 화면: ECO 상세 "영향 대상" 영역에 [영향 후보 보기] → 품목 코드(기본 = ECO 의 ITEM 대상 첫 품목) → 후보 표. `ADDABLE` 행에 [대상에 넣기] = 화면의 대상 목록에 BOM 행을 더할 뿐(저장은 기존 대상 저장 API·검사 그대로). 2단계 이상(`scope=ALL`) 행은 [품목 대상으로] — ITEM 대상(완제품 영향 기록).
- **새 쓰기 API 없음** — 대상 추가는 기존 `PUT …/targets`(작성 중·rowVer·`OPEN_ECO` 검사)가 판정한다. 후보 표는 보기일 뿐이라 그 사이 상태가 바뀌어도 저장 때 거부된다.

#### 20.7.3 결정 — 사용자 확인 2026-10-22: 추천안(상위 검토 통과)

| # | 질문 | 추천 | 대안 |
|---|---|---|---|
| I1 | 출발 품목 | 화면에서 품목 코드 입력, 기본값 = ECO ITEM 대상 첫 품목 | ECO ITEM 대상 전부를 한 번에 — 결과가 섞여 "어느 부품 때문인가" 가 흐려짐 |
| I2 | 범위 | 기본 **1단계 상위(BOM 후보)**, [위로 더 보기] = 전체 레벨(2단계 이상은 품목 대상 후보 — 정보) | 처음부터 전체 — 완제품까지 수십 행, 넣을 수 있는 것은 1단계뿐 |
| I3 | 기준일 | **ECO 유효일**에 유효한 확정 리비전(전개와 같은 규칙) | 오늘 — 유효일이 먼 ECO 는 그 사이 바뀐 BOM 을 놓친다 |
| I4 | 권한 | `plm-eco` SEARCH 만(`mst-bom-where` 는 보지 않음) — D3 과 같은 판단(결재선이 통제, 보기만) | 둘 다 요구 — 설계 변경 담당에게 BOM 화면 권한을 따로 줘야 함 |
| I5 | 작성 중 리비전이 없으면 | 넣지 않고 `NO_DRAFT` 안내(일괄 대체 14.3·BOM 화면) — **PLM 이 리비전을 만들지 않는다**(1단계 원칙) | 후보에서 바로 새 리비전 복사 — BOM 화면의 리비전 규칙을 PLM 이 다시 부르는 길이 하나 더 생김 |
| I6 | 자동 추가 | **하지 않음** — 보기 + 사람이 고름 | 1단계 상위를 모두 자동 추가 — 쓰지 않는 대체 BOM(alt)까지 대상이 됨 |

#### 20.7.4 시험 계획

정상: 자품목 X 를 쓰는 상위 BOM 3개(작성 중 있음·없음·다른 열린 ECO 에 묶임) → 상태 `ADDABLE`·`NO_DRAFT`·`OPEN_ECO`(번호), 이 ECO 에 이미 넣은 것 `IN_THIS` / 유효일 기준(유효일 뒤 확정되는 리비전은 안 보임) / `scope=ALL` 레벨 2 행. 이상 사용: 없는 품목 코드·다른 회사 품목(빈 결과 아님 → 칸 오류) / **A사로 B사 ECO id → 404** / 권한 SEARCH 없음 403 / 행 상한 넘음 → BOM 과 같은 오류(`BOM_TOO_MANY_ROWS`) / 후보 표를 본 뒤 다른 ECO 가 그 리비전을 먼저 가져가면 저장 때 `OPEN_ECO`(기존 검사).

### 20.8 현재 상태 (2026-10-26 — 20장 계획 이후 정리, 문서만)

> 20장(2026-10-22)의 ①~③ 이 끝난 뒤 상위 확정 순서로 이어진 일과, 아직 열려 있는 것을 한 곳에 모은다. 진행 날짜·시험 수는 11장 표가 원본.

**① 20장 계획 이후 완료** (모두 main 병합)

| 장 | 내용 | PR | 마이그레이션 |
|---|---|---|---|
| 21 | UI 단계 3 — 대화상자·상태 배지·위험 동작 자리, 주 목록 hb.grid | #276·#288 | — |
| 22 | 결재 줄 단위 전자서명 `beforeAct` + 결재함 안내 `esignFor` | #370 | — |
| 23 | PCN 메일 발송(고객 담당자, 발송 이력) | #409 | V156 |
| 24 | 읽기 계약 `EcoLookup.releasedFor`·`approvedFor` | #423 | — |
| 25 | 주 목록 엑셀(지시·요청) | #432 | V164 |
| 26 | 기한 지남 알림(지시 담당) | #439 | — |
| 27 | 지시 복사 | #460 | — |
| 28 | grid 변경 이력 보기 | #471 | — |
| 29 | 변경 통지서 엑셀(고객 제출용) | #485 | — |
| 30 | 홈 카드 "고객 응답 대기" 숫자 링크(`CUST_WAIT`) | #494 | — |
| 31 | PPAP 제출 요약서 엑셀 + 서식 도우미 `PlmSheet`·`PlmDocs` | #506 | — |
| 32 | PCN 메일 발송 결과 표시(`MailService.statuses`) | #514 | — |
| 33 | PCN 메일 변경 통지서 첨부 + QA 정렬 가드 PLM 몫 | #524 | V183 |
| 34·35·36 | 사내 지시 기록서 · 통지 응답 기한 임박 알림 · DOC 대상 PFMEA · 보낸 사본 DB 잠금(QA P-1) | #550 | V187 |

**② 보류 — 착수 조건이 생기면** (사용자 결정 또는 다른 세션 선행이 필요한 것)

| 항목 | 근거 절 | 보류 이유 | 착수 조건 |
|---|---|---|---|
| 적용 조건 "LOT"(특정 LOT 부터 새것) | 18장 끝, 20.2 | 변경 표시(첫 N LOT, 13장)·지시 리비전 스냅샷이 "어느 LOT 부터" 를 이미 기록 — 요구 대기 | 사용자 요구(실제 쓰임) |
| 자동 적용(재고 소진 순간 스케줄러가 적용) | 18장 R6, 20.4 | 적용은 사람이 누르는 동작(D2) — 원칙 결정 | 사용자 결정(원칙 변경) |
| 하위 LOT 전개(자재 LOT 의 ECO 로 완제품 출하 막기) | 17장 G4 | 자재 변경의 고객 승인은 자재 LOT 출하·입고 쪽 — 범위 결정 | 사용자 결정 + WMS·MES 합의 |
| PPAP 판정 대기 알림 | 26장 N1 | 고객 쪽 일이라 날짜 기한이 없어 "지남" 이 정해지지 않음 | 판정 기한 칸(요구) — 사용자 결정 |
| PCN 메일에 지시 첨부(도면 등)도 | 33장 F1 | `MailAttach` 는 대상 하나 + 지시 첨부에 사내 문서가 섞임 | 플랫폼 여러 대상 첨부(시스템관리) + 사용자 요구 |
| 메일 응답 수신(고객 회신 파싱) | 23장 E9 | 범위 밖 — 사람이 읽고 응답 기록 | 사용자 결정 |
| 지시 대상 엑셀 일괄 넣기(C) | 2026-10-24 상위 보류 | 수동 입력으로 충분한지 확인 전 | 사용자 요구. **하게 되면 플랫폼 `ImportService` 템플릿(P1 엑셀 가져오기 엔진)으로** — PLM 안에서 POI 로 따로 읽지 않는다 |
| 390 폭 grid 우클릭 메뉴가 상세 서랍에 덮임 | 28장 관찰 | PLM 밖 공통 hb.grid·hb.page | 시스템관리(공통 UI) — 상위에 전달됨 |

**③ 하지 않을 것** (유지) — EBOM/MBOM 분리·CAD/PDM 연동(9장), 재고 소진·유효일 자동 적용(20.4), ECO 대상 자동 추가(20.7 I6 — 후보는 보기만, 사람이 고름).

**다음 코드 후보**(상위 확인 2026-10-26): PPAP 제출 메일 → 37장(설계·구현).

## 21. UI 단계 3 목록 틀 (PLM 하위 세션, 2026-10-22 — 결정 docs/29 #44·#45·#47·#48, docs/40 P1·10.1·부록 A)

> 동결 해제 뒤 과제. 화면 2개(`plm-ecr` 설계 변경 요청, `plm-eco` 설계 변경 지시). 공통 JS·CSS 는 고치지 않고 PLM 전용 `plm-common.js` 에 도우미를 둔다(CMS `cms-ui.js` 와 같은 모양). 스키마 변경 없음.

### 21.1 PR 1 — 서버 변경 없는 ②~⑥

| 항목 | 내용 |
|---|---|
| ② 조회 영역 | 두 화면 모두 이미 `hb-search` 패널 — 그대로 |
| ③ 도구줄 순서 | 목록 도구줄 = [신규] 하나(엑셀 없음) — 그대로 |
| ④ 위험 동작 | 지시 취소·요청 취소 = `hb-btn-danger-outline` + **저장 줄과 떨어진 별도 도구줄**. 결재 반려·요청 반려 = 외곽선 위험 톤(승인·접수 옆 그대로 — 둘 중 하나를 고르는 판정이라 자리는 같은 줄) |
| ⑤ 상태 표시 | `hb-status` 배지 — 지시 목록·상세 상태, 고객 승인, 결재 줄, PPAP, 통지(PCN), 요청 목록·상세. 톤 표는 `plm.TONES` 하나(아래) |
| ⑥ 대화상자 | `confirm`/`prompt` 9곳 → `hb.dialog`: 미저장 이동(목록 행·신규·조회) 3곳씩 = `plm.leaveOk()`, 상신·적용·접수·일괄 대체·첨부 삭제 = 확인, 지시 취소·요청 취소 = **사유 필수**(500자). 취소·Esc = 아무 일 없음 |

PLM 톤 표(부록 A 규칙을 PLM 코드에 — 취소·반려는 사용자가 고른 결과라 빨강 아님, 고객이 거절한 판정만 danger):

| 종류 | 코드 → 톤 |
|---|---|
| 지시 `PLM_ECO_STATUS` | DRAFT neutral · PENDING info · APPROVED info · RELEASED success · CANCELLED neutral |
| 요청 `PLM_ECR_STATUS` | OPEN info · ACCEPTED success · REJECTED neutral · CANCELLED neutral |
| 고객 승인 `PLM_CUST_APR` | NA neutral · PENDING warning · APPROVED success · REJECTED danger |
| PPAP `PLM_PPAP_STATUS` | DRAFT neutral · SUBMITTED info · APPROVED success · INTERIM warning · REJECTED danger |
| 통지 `PLM_PCN_STATUS` | SENT info · ACK/APPROVED success · REJECTED danger |
| 결재 줄 | WAIT neutral · CURRENT info · APPROVED success · REJECTED/SKIPPED/REF neutral |

- 요청 취소 사유는 전에는 선택이었다 — 공통 규칙(사유 필수)에 맞춰 필수로(서버는 그대로 받는다).

### 21.2 PR 2 — 주 목록 → hb.grid (2026-10-22)

지시·요청 목록을 hb.grid 로: 기존 API 그대로 두고 `/grid`(같은 조건 SQL 조각 공유 + page·size·sort → {list,total})·`/columns`(ColumnSpec) 추가, 정렬 허용 enum + 마지막 PK, sort 는 `request.getParameterValues("sort")`, `@ProgAuth(SEARCH)`. 화면은 `GridOptions.status`(배지)·`rowClass`(취소·적용된 행 흐림)·비동기 `beforeSelect`(미저장 이동 확인). 상세 안의 행 표(대상·결재선·PPAP 요소·통지·표시된 LOT·후보)는 일반 표 유지.

| 항목 | 지시(`plm-eco`) | 요청(`plm-ecr`) |
|---|---|---|
| API(추가만) | `GET /api/plm/ecos/grid`·`/columns` | `GET /api/plm/ecrs/grid`·`/columns` |
| 조건 | 목록과 같은 `ecoFrom`·`ecoWhere` SQL 조각(상태·품목·번호/제목) | `ecrFrom`·`ecrWhere` |
| 정렬 허용 | `EcoSort` 번호·제목·유효일·담당·상태 + 마지막 `eco_id desc`, 기본 번호 내림 | `EcrSort` 번호·제목·품목·상태·지시 번호 + `ecr_id desc` |
| 열 | 번호 120·제목 180·유효일·담당·대상 수·상태(코드) — 목록 칸(약 650px) 안에 들어오게 | 번호·제목·품목 코드·요청자·상태·지시 번호 |
| 화면 | `status` = `plm.TONES.eco`, `rowClass` = 취소·적용된 지시 흐림, `beforeSelect` = `plm.leaveOk()` | `plm.TONES.ecr`, 열림이 아닌 요청 흐림 |

- 기존 목록 API(`GET /api/plm/ecos`·`/ecrs`)는 그대로 둔다 — 요청 화면의 "묶을 지시" 선택지 등이 쓴다.
- 상세 안의 행 표(대상·결재선·PPAP 요소·통지·표시된 LOT·영향 후보)는 일반 표 유지(결정 #45).

## 22. 결재 줄 단위 전자서명 — `beforeAct` 연결 (PLM 하위 세션, 2026-10-23, 결정 docs/29 #49)

> 시스템관리가 결재 엔진 확장점 `ApprovalDocType.beforeAct(bizKey, LineAct)` 를 만들었다(main #350, docs/36 18.2). 19.3 S2 의 한계(정책 Y 인데 공통 결재함의 중간 합의 줄은 서명 없이 통과)를 없앤다. 마이그레이션 없음.

### 22.1 바뀐 자리

| 전 (19장) | 후 |
|---|---|
| PLM 승인 API(`EcoService.approve`)가 토큰을 직접 `consume` → `ApprovalService.approve(aprId, comment)` | PLM 승인 API 는 결재 중 확인만 하고 토큰을 **결재 엔진에 넘긴다**(`approve(aprId, comment, esignToken)`) |
| 공통 결재함 경로: 마지막 승인만 `onFinished` 가 "이 트랜잭션의 서명 행"(`signedNow`)으로 확인, 중간 줄은 통과 | `EcoApprovalDocType.beforeAct` 가 **승인·합의 줄마다** — 토큰이 있으면 지금 내용 해시로 소비, 없으면 정책 Y 일 때 403 `PLM_ESIGN_REQUIRED` → 그 처리 전체 롤백 |
| 의미(REVIEW/APPROVE)는 PLM API 가 결재선을 다시 읽어 판단 | `LineAct.lineType` 으로 — 합의 줄 REVIEW, 결재 줄 APPROVE |
| `PlmMapper.signedNow` | 삭제(쓰는 곳 없음) |

- 두 경로(PLM 화면 · 공통 결재함 API)가 **같은 자리**에서 같은 규칙으로 검사한다 — 서명 검사가 한 곳.
- 반려는 서명 없음(19.3 S3 그대로). 회수는 엔진이 확장점을 부르지 않는다.
- 위임 대결: 서명자 = 실제로 누른 사람(토큰도 그 사람 세션) — 원 결재자 이름은 결재 이력(`on_behalf_of`)에.
- 세션: `consume` 은 HttpSession 이 필요해 요청 문맥에서 꺼낸다(QMS `QmsESign` 과 같은 방식). 요청 밖(배치 등)에서 승인하면 세션이 없어 토큰 없음과 같다.

### 22.2 남은 것

- 공통 **결재함 화면**은 아직 서명 대화상자를 열지 않는다(docs/36 18.2 후속) — 정책 Y 회사에서 결재함 화면으로 PLM 문서를 승인·합의하면 403 문구("설계 변경 화면에서 서명")가 나온다. 전에는 마지막 승인만 그랬고 이제 중간 합의 줄도 같다. 결재함이 hb.esign 을 열게 되면 PLM 은 바꿀 것 없음(토큰이 API 로 오면 beforeAct 가 받는다).

### 22.3a 결재함 화면 서명 안내 — `esignFor` (2026-10-23, 시스템관리 #355 docs/36 18.2b)

- `EcoApprovalDocType.esignFor(bizKey, lineType, approve)`: **정책 Y 일 때 승인·합의만** 안내(합의 REVIEW·결재 APPROVE, 대상 `plm_eco`·ECO id, 제목 "번호 제목"). 반려·정책 N·결재 끝 = null. 의미 규칙은 beforeAct 와 같은 함수(`meaning`) 하나
- 정책 N 이면 안내하지 않는다 — 결재함에서 서명 없이 처리하던 회사의 흐름을 바꾸지 않는다(PLM 화면은 19.3 S1 대로 정책과 관계없이 서명 대화상자). 안내는 화면용일 뿐 판정은 beforeAct
- **두 화면이 다르다(상위 확인 2026-10-23)**: 정책 N 에서 PLM 화면은 서명을 받고(남겨 둘 기록), 결재함은 묻지 않는다(현행 흐름 유지) — 정책 Y 에서는 둘 다 서명 필수로 같다
- 22.2 의 "결재함 화면 합의 403" 회귀는 이것으로 닫힌다(#355 와 같은 묶음)

### 22.3 시험 (`PlmEsignTest` 7 → 8 → 9)

- 바뀜: "정책 Y — 중간 합의 줄 통과(한계 고정)" → **공통 결재함 중간 합의 줄도 403 + 롤백**(줄 CURRENT 그대로·이력 없음), 공통 결재함 API 에 REVIEW 토큰을 실으면 통과, 결재 줄에 REVIEW 토큰 → 403 `ESIGN_TOKEN`, 서명 2건
- 추가: 정책 Y 여도 반려는 서명 없이 된다(서명 행 0)
- 추가(22.3a): 결재함 상세 안내 — 정책 N 없음 / 정책 Y 합의 줄 REVIEW·대상·제목, 반려 없음 → 안내대로 서명해 결재함 API 로 합의 → 결재 줄 APPROVE 안내 → 승인 → 끝난 결재 없음
- 그대로 통과: 정상 2단 서명·반려 뒤 "바뀜"·정책 N·E/F 토큰 6종·PPAP·A사로 B사

## 23. PCN 실제 발송 — 고객 담당자에게 메일 (PLM 하위 세션, 2026-10-23 — **사용자 결정: E1~E9 추천안 그대로**, 플랫폼 `MailService`(docs/36 18.3.1) 위에 구현 23.5)

> 14.5 에서 미룬 "PCN 메일 발송". 시스템관리 메일 송신기(아웃박스 토픽 MAIL, main #378, docs/36 18.3)가 생겨 착수. **코드 전에 이 절로 범위·결정을 확인받는다** — 받는 사람이 회사 밖(고객)이고, 지금 메일 계약은 사내 알림함 전용이다.

### 23.1 지금 있는 것 (코드 확인)

| # | 사실 | 위치 | 뜻 |
|---|---|---|---|
| F1 | 메일 송신기는 아웃박스 본문 `{to, subject, body}` 를 그대로 보낸다(글자 메일, 주소 다시 검사, 재시도·중복 규칙) | `platform.notify.MailNotifyHandler` | 송신 자체는 재사용 가능 |
| F2 | 메일 계약 M1 = **알림함에 들어간 사내 알림만** 메일로, 받는 주소 = `sys_user.email`. 주소 검사 `MailSettings.validAddress` 는 패키지 안(비공개), 발행은 `NotificationService` 안에서만 | docs/36 18.3 | PLM 이 MAIL 토픽에 직접 발행하면 **계약 밖 사용** + 주소·제어 문자 검사를 PLM 이 따로 짜야 한다(두 벌) |
| F3 | 고객 담당자 표 `mst_cust_contact`(이름·부서·메일·대표 여부·사용), 고객 `mst_cust.email` | V009 | 받는 사람 후보가 이미 기준정보에 있다 |
| F4 | PCN = 기록 + 응답(SENT → ACK·APPROVED·REJECTED), 같은 ECO·고객 열린 통지 하나 | 14.5, V069 | 발송은 기록과 별개 동작으로 붙이면 기존 흐름 그대로 |

### 23.2 결정 — 사용자 확인 2026-10-23: 추천안

| # | 질문 | 추천 | 대안(안 하는 이유) |
|---|---|---|---|
| E1 | 받는 사람 | 그 고객의 **담당자(`mst_cust_contact`, 사용 중·메일 있음) 중에서 고름**, 기본 = 대표 담당자. 여러 명이면 사람마다 메일 한 통 | 주소 자유 입력 — 오타·엉뚱한 곳으로 설계 정보 유출, 기록이 기준정보와 어긋남 / `mst_cust.email`(대표 메일) — 담당자 없이 대표 창구로만 감 |
| E2 | 언제 | PCN 기록과 **분리된 [메일 보내기]** — 열린(SENT) 통지에서만, 다시 보내기 허용(담당자 바뀜·재요청), 매번 발송 이력 한 줄 | 기록 저장 때 자동 발송 — 저장을 고치거나 담당자를 확인하기 전에 나간다 |
| E3 | 송신 경로 | **플랫폼에 외부 메일 발행 API 하나를 요청**(시스템관리): 예 `MailService.publish(bizKey, to, subject, body)` — 켜짐 판정·주소 검사·제어 문자 거름·아웃박스 발행을 플랫폼이 한 곳에서. PLM 은 부르기만 | PLM 이 MAIL 토픽에 직접 발행 — M1 계약 밖, 주소 검사 두 벌 |
| E4 | 내용 | 글자 메일, **회사 기본 언어**(고객 언어 정보 없음). 제목 `[{회사명}] 변경 통지 {PCN 번호} — {ECO 제목}`, 본문 = PCN 번호·ECO 번호·제목·사유·유효일·응답 기한·비고 + "회신은 이 메일 담당자에게"(보낸 사람 이름). **사내 화면 주소는 넣지 않는다**(고객은 로그인 못 함). 첨부 없음(송신기가 글자 메일만 — 도면은 따로) | 화면 주소 포함 — 고객에게 사내 시스템 주소 노출 / 첨부 — 송신기 범위 밖 |
| E5 | 권한·확인 | `plm-eco` SAVE. 화면은 받는 사람·제목·본문 미리보기 확인 대화상자(되돌릴 수 없는 외부 발송 — `hb.dialog` danger 아님, 확인만) | SEARCH 만 — 보기 권한자가 외부로 보낼 수 있음 |
| E6 | 기록 | 새 표 `plm_pcn_mail`(회사별·RLS): pcn_id·contact_id·**받는 주소 스냅샷**·이름 스냅샷·제목·요청 시각·요청자 + 아웃박스 메시지 id. UPDATE/DELETE 차단(발송 이력은 원장처럼). 상세 PCN 행 아래 "메일: 이름 <주소> · 시각 · 요청자" | 아웃박스만 — 아웃박스는 보존 기간 뒤 지워지고, 업무 화면에서 누가 언제 누구에게 보냈는지 못 봄 |
| E7 | 송신 결과 | 처음엔 **요청 기록만**(보냄/실패는 아웃박스 운영 화면). 실패 표시가 필요하면 플랫폼 아웃박스 상태 조회 API 뒤에 | PLM 이 `sys_outbox` 직접 조회 — 남의 표 |
| E8 | 메일 꺼짐 | 설정이 꺼져 있으면 버튼 숨김 + API 409(`PLM_MAIL_OFF`) — 17장과 같은 "보이는데 안 감" 방지 | — |
| E9 | 응답 수신 | **하지 않음** — 고객 회신은 사람이 읽고 기존 응답 기록(14.5) | 메일 수신 파싱 — 범위 밖 |

### 23.3 만들 것 (E1~E9 추천 기준)

- 마이그레이션 **V156·임시 V9907**: `plm_pcn_mail` + RLS(`comp_cd`·ENABLE/FORCE·`tenant_isolation`) + UPDATE/DELETE 차단 트리거.
- API: `GET /api/plm/pcns/{id}/contacts`(받는 사람 후보, SEARCH) · `POST /api/plm/pcns/{id}/mail {contactIds[]}`(SAVE, 멱등 키) · PCN 목록 응답에 발송 이력.
- 화면: PCN 행 [메일 보내기] → 담당자 체크 + 미리보기 → 확인. 메일 꺼짐이면 버튼 없음.
- 시험(계획): 정상 2명 → 아웃박스 MAIL 2건·이력 2줄·본문에 화면 주소 없음 / 메일 꺼짐 409 / 응답 끝난 PCN 409 / 다른 고객 담당자 id → 400 / 메일 없는·사용 안 하는 담당자 → 400 / SEARCH 만 → 403 / 제목에 줄바꿈 든 ECO 제목 → 걸러짐 / **A사로 B사** PCN id·담당자 id → 404 / 이력 UPDATE·DELETE 거부.

### 23.4 다른 세션에 필요한 것

- **시스템관리**: 외부 메일 발행 공개 API(E3) — 받는 주소·제목·본문·업무 키를 받아 켜짐·주소·제어 문자를 검사하고 MAIL 아웃박스 발행(같은 트랜잭션). 18.3 M1 의 "사내 알림만" 에 "모듈이 명시적으로 보내는 외부 메일" 한 줄 추가. 이게 들어오기 전엔 PLM 은 23.3 중 표·화면·이력까지만 만들 수 있다.

### 23.5 구현 (2026-10-23, 마이그레이션 V156·임시 V9907)

| 항목 | 내용 |
|---|---|
| 표 | `plm_pcn_mail`(pcn·담당자·**받는 주소·이름 스냅샷**·제목·아웃박스 메시지 id·created_at/by) + RLS. 트리거: 담당자 = 그 통지 고객의 담당자(직접 SQL 로도 다른 고객 담당자 기록 불가), UPDATE/DELETE 차단(`hb_forbid_update_delete` + 앱 계정 DELETE·TRUNCATE 회수), 변경 감사 `hb_audit`, 보존 클래스 MASTER(통지와 같음) |
| API | `GET /api/plm/pcns/{id}/mail-targets`(SAVE — 담당자 메일은 개인정보라 보낼 수 있는 사람만): 켜짐·후보(사용 중·메일 있음, 대표 먼저)·제목·본문 미리보기 / `POST /api/plm/pcns/{id}/mail {contactIds}`(SAVE, **`X-Idempotency-Key` 필수** — 없으면 400): 1~10명, 중복·후보 밖 = 400 칸 오류(`NOT_PCN_CONTACT` — 다른 고객·회사·사용 안 함·메일 없음을 구분하지 않음), 응답 끝난 통지 409, 메일 꺼짐 409 `MAIL_DISABLED`(플랫폼) |
| 송신 | 담당자마다 `MailService.publish("PCN:{통지 id}:{담당자 id}", Recipient(주소, 이름), 제목, 본문)`(아웃박스 키 `EXT:PCN:…`, docs/36 18.3.1) — 같은 트랜잭션, 하나라도 거부되면 전부 롤백(한 명도 안 나감) |
| 본문 | 회사 기본 언어(`sys_comp.locale_cd`), 글자 메일: 인사(담당자 이름) · 회사 · 통지 번호 · 지시 번호·제목 · 사유(공통코드 이름) · 적용 예정일 · 회신 기한 · 비고(줄바꿈 유지) · 회신 안내(보낸 사람·회사). **사내 화면 주소 없음**. 제목·이름은 제어·양방향 문자 → 공백, 이름의 표시 이름 금지 글자(`" < > , ; \`) 제거 — 남는 게 없으면 주소로(플랫폼 검사 400 을 미리 피함) |
| 목록 | PCN 행에 `mails`(발송 이력)·`mailEnabled` |
| 화면 | PCN 행 [메일 보내기](열린 통지·SAVE·메일 켜짐) → PCN 표 **아래** 펼침(좁은 화면에서 표 가로 스크롤에 갇히지 않게): 담당자 체크(대표 기본, `hb-check-list`) + 제목 + 본문 미리보기(줄바꿈 유지, 글자로만) → [보내기] → 확인 대화상자("N명에게 보냅니다: … 되돌릴 수 없습니다") → 행 아래 이력 줄 "메일 이름 <주소> · 시각 · 보낸 사람". 담당자 없음·꺼짐 안내 |

## 24. 읽기 계약 — `EcoLookup.releasedFor` · `approvedFor` (PLM 하위 세션, 2026-10-23 — **사용자 결정: 추천안 전부**)

> PMS(APQP A3 2차)가 "그 품목에 어떤 날 이후 **확정(적용)** 된 ECO" 를 원한다. QMS 는 같은 질문의 "승인" 판을 `ChangeLookup`(docs/29_P3 20장)으로 제안하며 **Q-r**(A안: QMS 가 PLM 을 읽어 합침 / B안: PLM 이 ECO 목록, QMS 는 4M 세부만)·**Q-s**(`EcoView` 에 승인일 칸)를 PLM 에 물었다. 이 절은 PLM 쪽 모양 제안 + 두 질문에 대한 PLM 의견이다. 코드는 결정 뒤.

### 24.1 지금 있는 것 (코드 확인)

| # | 사실 | 위치 | 뜻 |
|---|---|---|---|
| F1 | `plm_eco.approved_at`(결재 완료 시각 — APPROVED·RELEASED 이면 반드시 있음, 체크 `ck_plm_eco_approved`), `released_at`(적용 시각 — RELEASED 일 때만, `ck_plm_eco_released`), `effective_date`(유효일 — 계획 날짜) | V050 | **승인일·적용일 칸을 줄 수 있다**(새 표·칸 없음). 반려 → 작성 중으로 돌아가면 상태로 거른다 |
| F2 | 상태 흐름: DRAFT → PENDING → APPROVED → RELEASED, APPROVED·DRAFT 에서 CANCELLED | V050 트리거 | "확정" = RELEASED 하나. 승인됐다가 취소된 ECO 는 승인 목록에서도 빠져야 한다(상태로) |
| F3 | `EcoView.itemIds` = 대상 행의 `item_id` 전부(ITEM 대상 + BOM·라우팅 리비전의 모품목 + 품목이 있는 설비·공정 대상, 도면 제외) | `PlmEcoLookup.view` | "그 품목의 ECO" 의 품목 범위를 **이미 계약에 있는 정의 그대로** 쓰면 두 질문의 답이 어긋나지 않는다 |
| F4 | 색인 `ix_plm_eco_target_item_ref (comp_cd, item_id)` | V050 | 품목 → ECO 찾기에 새 색인 불필요 — **마이그레이션 없음** |
| F5 | `EcoView` 를 PLM 밖에서 생성하는 코드 없음(읽기만) | grep | 레코드에 칸을 더해도 다른 모듈 컴파일은 그대로 |

### 24.2 결정 — 사용자 확인 2026-10-23: 추천안(R1~R7)

| # | 항목 | 추천 | 대안(안 하는 이유) |
|---|---|---|---|
| R1 | 모양 | `EcoPage releasedFor(long itemId, LocalDate since)` · `EcoPage approvedFor(long itemId, LocalDate since)` — `record EcoPage(List<EcoView> rows, boolean truncated)`. 둘 다 `EcoLookup` 의 **default 메서드**(PLM 없는 설치 = 빈 페이지) | 기준을 인자로(`changedFor(itemId, since, Basis)`) — 부르는 쪽이 문자열·enum 을 잘못 넘길 길이 생김, 두 질문이 이름으로 드러나는 편이 읽기 쉬움 |
| R2 | "확정" 의 뜻 | `releasedFor` = **RELEASED** 이고 `released_at`(회사 날짜) ≥ since. `approvedFor` = **APPROVED 또는 RELEASED** 이고 `approved_at`(회사 날짜) ≥ since — 승인 뒤 취소·반려는 빠짐 | 유효일 기준 — 계획 날짜라 적용 전 ECO 가 섞이고, 유효일을 지나도 적용 안 한 ECO 가 "확정" 으로 보임 |
| R3 | 품목 범위 | `EcoView.itemIds` 와 같은 정의(F3) — 대상 행의 `item_id` = itemId. **역전개하지 않음**(자품목 ECO 가 모품목 목록에 끼지 않음 — 그건 영향 후보 보기 20.7 의 몫) | 역전개 포함 — 레벨·상한·기준일이 붙어 계약이 무거워지고, PMS 가 원한 "그 품목의 변경" 보다 넓어짐 |
| R4 | 날짜 | since 는 회사 날짜(포함). 시각 칸은 회사 시간대로 날짜화(`BizClock` 의 시간대) — 자정 근처에 회사·서버 날짜가 어긋나지 않게 | 서버 시간대 날짜 — 해외 회사에서 하루 어긋남 |
| R5 | 순서·상한 | 기준 시각(적용·승인) 오름차순, 같으면 eco_id. 상한 `MAX_IDS` 500, 넘으면 처음 500 + `truncated = true` | 예외 — 읽기 계약은 예외 없음(16장 틀) |
| R6 | 공통 규칙 | 읽기만·잠금 없음·예외 없음: 없는·다른 회사 품목·since null = 빈 페이지(RLS). 행은 기존 `EcoView` 그대로(대상 id 포함 — `ecos(ids)` 와 같은 2회 쿼리) | — |
| R7 | **Q-s 답 — `EcoView` 에 날짜 칸** | **준다**: `approvedOn`·`releasedOn`(회사 날짜, 없으면 null) 두 칸을 `EcoView` 끝에 추가. `eco`·`byNo`·`ecos` 결과에도 같이 실림 | 승인일만 — PMS 의 "확정" 질문에 적용일이 또 필요해 두 번 고치게 됨 |

### 24.3 Q-r 에 대한 PLM 의견 — **B안 찬성**

- 승인·적용 여부와 대상 품목의 원본은 PLM(F1·F3) — "그 품목에 어떤 ECO 가 승인/확정됐나" 는 PLM 계약(R1)이 답하고, QMS `ChangeLookup` 은 **4M 세부만**(`details(ecoIds)`) 주면 PLM 의 상태 규칙(예: 승인 뒤 취소 제외, 적용 시각 기준)이 바뀌어도 QMS 가 따라 고치지 않는다.
- PMS 는 `EcoLookup.releasedFor`(또는 `approvedFor`)로 ECO 목록을 받고, 4M 변경(`changeKind = PROCESS_4M`)인 것만 ids 로 QMS `details` 를 부른다 — 계약 두 개, 각자 자기 원본.
- A안도 R7(날짜 칸)이 있으면 가능하지만, QMS 가 "자기 4M ECO id 전부 → `ecos(ids)` → 품목·날짜로 거름" 을 하면 품목이 많은 회사에서 id 목록이 커지고(상한 500) 거르기 규칙이 두 곳이 된다.

### 24.4 구현 계획 (결정 뒤, 마이그레이션 없음)

- `EcoLookup`: `EcoPage` 레코드·두 default 메서드, `EcoView` 에 `approvedOn`·`releasedOn`. `PlmEcoLookup` + `PlmLinkMapper`(머리 SQL 하나 — 품목·기준·since·501행, 대상은 기존 `ecoTargetsOf`).
- 시험(`PlmLinkContractTest` 확장 예정): 품목 X 에 ITEM 대상·BOM 모품목·라우팅 대상 ECO 각 하나 + 다른 품목 ECO → X 의 것만 / 상태별(DRAFT·PENDING 제외, 승인 뒤 취소 제외, approved 는 APPROVED·RELEASED, released 는 RELEASED) / since 경계(그날 포함·전날 제외, 회사 시간대 자정 근처) / 501건 → 500 + truncated / since null·없는 품목 → 빈 / **A사로 B사** 품목 id → 빈 / `eco(id)` 에 approvedOn·releasedOn.

### 24.6 구현 (2026-10-23, 마이그레이션 없음)

- `EcoLookup`: `EcoPage(rows, truncated)`(+ `EMPTY`), default `releasedFor`·`approvedFor`(PLM 없음 = 빈 페이지), `EcoView` 끝에 `approvedOn`·`releasedOn`. PLM 밖에서 `EcoView` 를 만드는 코드가 없어 다른 모듈은 그대로 컴파일.
- `PlmEcoLookup`: since → **회사 자정의 시각**(`BizClock.zone()`)으로 바꿔 `approved_at`/`released_at` 과 비교(시각 칸 그대로 — 색인 쓰임), 머리 1회(`ecoHeadsForItem`, 상한 + 1 행) + 대상 1회(`ecoTargetsOf`). 날짜 칸은 시각을 회사 시간대로 날짜화(eco·byNo·ecos 에도).
- 품목 조건 = `exists (plm_eco_target … item_id = itemId)` — `EcoView.itemIds` 와 같은 정의, 색인 `ix_plm_eco_target_item_ref`.

### 24.5 검토

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-23 | PLM | 제안 작성 — R1~R7 추천, Q-r = B안 찬성, Q-s = 준다(승인일·적용일 두 칸). QMS docs/29_P3 20장과 함께 사용자 결정 대기 |
| 2026-10-23 | 사용자(상위 전달) | **추천안 전부 채택** — Q-r **B안**(PLM 이 ECO 목록, QMS 는 4M 세부만), Q-s `EcoView` 에 `approvedOn`·`releasedOn`, R1~R5(·R6·R7) 그대로. 이어서 구현 PR(24.4, 마이그레이션 없음) |

## 25. 주 목록 엑셀 내보내기 (PLM 하위 세션, 2026-10-23 — 상위 확정 순서 ①, V164·임시 V9908)

> 다른 모듈 grid 엑셀(docs/09 6.1, CMS C21)과 같은 방식. 화면은 grid 메뉴 "엑셀로 내보내기"(EXCEL 동작이 있을 때만) → `POST …/export`.

| 항목 | 지시(`plm-eco`) | 요청(`plm-ecr`) |
|---|---|---|
| API | `POST /api/plm/ecos/export` | `POST /api/plm/ecrs/export` |
| 권한 | `@ProgAuth(EXCEL)` — `sys_prog.actions` 에 EXCEL 추가(V9908, 표 변경 없음) | 같음 |
| 조건 | grid 와 **같은 SQL 조각**(`ecoFrom`·`ecoWhere`·`ecoOrder` — 정렬 조각도 grid 와 공유) | `ecrFrom`·`ecrWhere`·`ecrOrder` |
| 정렬 | `PageQuery.sortOnly` — 허용 enum(`EcoSort`)·최대 3개·마지막 PK, 기본 번호 내림 | `EcrSort`, 기본 번호 내림 |
| 열 | `ColumnSpec.selectExportable(GRID_COLUMNS, 보이는 열)` — 지정 없으면 grid 열 전부, 코드 열은 이름으로 | 같음 |
| 행 | 건수 먼저(플랫폼 상한·시간 제한) → 매퍼 커서로 한 행씩 | 같음 |
| 없는 품목 코드 | 머리만(grid 의 빈 쪽과 같다) | 같음 |

## 26. 기한 지남 알림 — 지시 담당에게 사내 알림 (PLM 하위 세션, 2026-10-23 — 상위 확정 순서 ②, 마이그레이션 없음)

> 홈 카드(20.6)는 "보러 와야" 보인다. 담당자가 놓치기 쉬운 두 가지를 플랫폼 알림(docs/36 11장 — 알림함, 구독에 메일을 켰으면 메일도 18.3 M1)으로 민다.

| # | 정한 것 | 다른 안(안 한 이유) |
|---|---|---|
| N1 | 이벤트 두 개(`PlmNotifyEvents`): `PLM_ECO_OVERDUE` = 결재 승인됐는데 유효일이 지나도록 적용 안 한 지시, `PLM_PCN_OVERDUE` = 열린 고객 통지의 응답 기한 지남(취소 지시 제외). **기본 켬**(본인 지시만 오므로 많지 않다) | PPAP 판정 대기도 — 고객 쪽 일이라 날짜 기한이 없어 "지남" 이 정해지지 않음 |
| N2 | 조건 = **홈 카드와 같은 식**(`PlmHomeMapper.overdueEcos`·`overduePcns` — 카드 1 "유효일 지남"·카드 2 "PCN 기한 지남") | 따로 쓴 조건 — 카드 숫자와 알림이 어긋남 |
| N3 | 받는 사람 = **지시 담당**(`owner_id`). 화면 권한(`plm-eco` SEARCH)·구독·사용 중 판정은 플랫폼 `NotificationService` 그대로 | 화면 조회 권한자 전원 — 같은 알림이 여럿에게, 누가 할 일인지 흐림 |
| N4 | **처음 지났을 때 한 번** — 중복 키 `ECO:{id}:{유효일}`·`PCN:{id}:{응답 기한}`. 승인·통지 뒤에는 날짜가 고정(트리거)이라 같은 건은 다시 안 오고, 반려 → 재상신으로 유효일이 바뀌어 다시 지나면 새 알림 | 매일 다시 — 적용을 미룬 지시가 매일 알림을 쌓음(홈 카드가 계속 보여 줌) |
| N5 | 실행 = `PlmJobs.overdue()` **매시**(`happyboot.jobs.plm-overdue-cron`, 기본 매시 40분) + `TenantRunner.forEachActiveComp` + `JobLock` — 회사 시간대마다 "오늘" 이 바뀌는 시각이 달라 CmsJobs 와 같은 방식. 한 번에 각 500건(넘는 것은 다음 시) | 하루 한 번 고정 시각 — 해외 회사는 날짜가 바뀌고 한참 뒤에 옴 |
| N6 | 알림 링크 = `plm-eco` + `keyword={지시 번호}`(조회 패널의 `q.keyword`, docs/37 14.2) | — |

## 27. 지시 복사 (PLM 하위 세션, 2026-10-24 — 상위 확정 순서 ②, 마이그레이션 없음)

> 비슷한 변경을 다시 낼 때(같은 품목의 다음 단계, 취소한 지시 다시 내기) 처음부터 쓰지 않게. 상세 [복사](SAVE) → `POST /api/plm/ecos/{id}/copy` → 새 작성 중 지시를 연다.

| # | 정한 것 | 다른 안(안 한 이유) |
|---|---|---|
| C1 | **어느 상태든** 복사(작성 중·결재 중·승인·적용·취소) — 원본은 그대로 | 적용·취소만 — "작성 중을 둘로 나누기" 같은 쓰임을 막음 |
| C2 | 머리는 **지금 검증 규칙을 다시 탄다**(`validate`) — 유효일은 오늘 이후로 당김, 임시 변경 끝 날짜가 새 유효일보다 앞이면 새 유효일로(4M 은 비움), 담당 = 복사한 사람, 고객 승인은 필요 여부·고객만(상태 대기·승인 번호 없음). 지금 규칙에 안 맞으면(예: 고객이 판매처가 아니게 됨) **400 이고 새 지시도 없음** | 원본 값 그대로 INSERT — 지금은 안 되는 값(지난 유효일·사용 중지 고객)이 작성 중으로 들어옴 |
| C3 | 대상은 **품목·설비·공정 행만** 대상 저장 규칙(`saveTargets`)으로 옮긴다(변경 내용 포함). **BOM·라우팅·문서 대상은 뺀다** — 특정 작성 중 리비전에 묶이고(한 리비전 = 열린 지시 하나), 적용된 원본의 리비전은 이미 확정이라 다시 쓸 수 없음. 지금 사용 중지된 품목·설비·공정도 뺀다. 뺀 수는 응답·알림 문구로 | 리비전 대상도 복사 — `OPEN_ECO`·`NOT_DRAFT` 로 전체 실패 |
| C4 | 결재·전자서명·첨부·PPAP·PCN·표시 LOT·고객 승인 기록은 **옮기지 않는다**(새 지시의 사실이 아님) | 첨부 복사 — 상신 뒤 고정(D7) 규칙·파일 소유가 얽힘 |
| C5 | 제목은 그대로(번호가 다르다). 멱등 키(`PLM_ECO_COPY`)로 연타 한 번 | 제목 앞에 "복사:" — 회사 언어·제목 길이 상한(200)과 얽힘 |

## 28. grid "변경 이력 보기" (PLM 하위 세션, 2026-10-24 — 상위 확정 순서 ①, 마이그레이션 없음)

- 지시·요청 grid 옵션에 `auditTable`(`plm_eco`·`plm_ecr`)만 추가 — 우클릭 메뉴 "변경 이력 보기" → 플랫폼 `/api/platform/audit`(docs/09 4.4)이 그 행의 감사 기록을 보여 준다.
- **공유 파일 변경 없음**: 플랫폼 `AuditViewService.TABLE_PROG` 에 `plm_ecr → plm-ecr`·`plm_eco → plm-eco`(+ 대상·PPAP·PCN) 가 1단계(V050)부터 이미 등록돼 있었고, 두 표의 `hb_audit` 트리거도 V050 에 있다 — 상위가 허락한 두 줄 추가는 필요 없었다.
- 권한 = 그 화면 SEARCH(플랫폼 판정), 다른 회사 = 빈 이력(RLS).
- 관찰(공통, PLM 밖): 390 폭에서 행을 우클릭하면 행 선택 → 상세 서랍이 열려 grid 메뉴를 덮는다(메뉴 위에 서랍) — 다른 화면도 같은 hb.grid·hb.page 조합이면 같다. 데스크톱 폭(1366)은 정상. 공통 JS·CSS 범위라 상위에 후보로 보고.

## 29. 변경 통지서 엑셀 — 고객 제출용 한 장 (PLM 하위 세션, 2026-10-24 — 상위 확정 ③, 마이그레이션 없음)

> PCN 메일(23장)은 글자 메일이라 첨부가 없다. 고객에게 내거나 출력해 서명받는 서식은 지시 상세 [통지서] → `POST /api/plm/ecos/{id}/notice` 로 엑셀 한 장을 내려받는다(SRM 발주서 엑셀과 같은 방식 — 서버가 POI 로 서식 고정).

| # | 정한 것 | 다른 안(안 한 이유) |
|---|---|---|
| T1 | 권한 = `plm-eco` **EXCEL**(V164 에 이미 있음 — 마이그레이션 없음) | PRINT 동작 추가 — sys_prog 마이그레이션 + 권한 다시 주기 |
| T2 | 내용: 머리(발신 회사·지시 번호·제목·사유·변경 종류·적용 예정일·상태·고객·고객 승인·담당) → 변경 내용(줄바꿈 유지) → 대상 표(순번·유형·품목 코드·품명·리비전/설비/공정/문서·변경 내용) → **고객 확인란**(판정·일자·확인자 서명·의견 — 빈 칸) | 화면 전체 — 결재선·서명·표시 LOT·재고·첨부는 사내 정보라 고객 문서에 넣지 않음 |
| T3 | 언어 = **회사 기본 언어**(PCN 메일과 같음), 코드는 공통코드 이름 | 사용자 언어 — 고객에게 가는 문서가 누가 눌렀느냐로 달라짐 |
| T4 | 결재 전(작성 중·결재 중)이면 제목에 "(초안 — 결재 전)" | 결재 전엔 막기 — 고객과 미리 협의하는 쓰임을 막음 |
| T5 | 모든 글자 칸은 `ExcelExportService.safeText`(수식 주입 방지·제어 문자) — 그래서 빈 값 자리 표시로 "-" 를 쓰지 않는다(`'-` 가 됨, 브라우저 확인에서 발견) | — |
| T6 | 파일 이름 `{지시 번호}_notice.xlsx`, 저장된 내용 기준(화면에서 고치고 저장 안 한 값은 안 들어감) | — |

## 30. 홈 카드 "고객 응답 대기" 숫자 링크 (PLM 하위 세션, 2026-10-24 — 상위 확정 A, 마이그레이션 없음)

> 20.6 에서 카드 2 는 "ECO 목록에 PPAP·PCN 조건이 없어 숫자와 목록이 달라진다(docs/37 H27)" 로 링크를 두지 않았다. 목록에 그 조건을 더해 링크를 잇는다. 상위 조건: **카드 숫자와 목록 건수가 같은 SQL 기준**이고 그것을 시험으로 확인.

| # | 정한 것 | 다른 안(안 한 이유) |
|---|---|---|
| W1 | 목록 상태 칸에 **묶음 선택지 `CUST_WAIT`**(상태 아님) — QMS 부적합 `OPEN_ALL` 과 같은 방식(docs/37 결정 (a)). 조회 패널의 상태 칸은 공통코드 자동 채움 대신 고정 선택지(공통코드 문구 키 그대로 + 묶음 한 줄) | 별도 조건 칸 — 조회 패널 칸이 늘고, 카드 링크는 기존 `status` 한 값으로 충분 |
| W2 | 조건 = 취소 아닌 지시 중 **PPAP 판정 대기(SUBMITTED)** 또는 **응답 기한(< 회사 오늘) 지난 열린 PCN(SENT)** — 카드 보조 숫자와 같은 정의 | — |
| W3 | 카드 **숫자 = 지시 수**, `PlmMapper.countEcos(…, custWaitOn = 오늘)` — 목록·grid·엑셀이 쓰는 `ecoWhere` 조각 **그 자체**로 센다. 보조 숫자(PPAP 건수·PCN 건수)는 그대로 행 수 | 숫자 = PPAP + PCN 건수(20.6 처음) — 한 지시에 둘 다 있거나 고객별 PCN 이 여럿이면 목록 건수와 달라진다 |
| W4 | 링크는 숫자 ≤ 목록 상한(500)일 때만(H27) | — |
| W5 | 기준일은 서비스가 `BizClock.today()` 로 넣는다(SQL 의 now() 없음) | SQL `current_date` — 회사 시간대가 아님 |

- 시험: `PlmHomeCardTest` — 한 지시에 PPAP 판정 대기 + 고객 둘의 기한 지난 PCN, 응답 받은 PCN·판정 끝난 PPAP 은 빠짐 → 카드 숫자 3 = 목록(`status=CUST_WAIT`) 3행 = grid total 3, 보조 숫자 2·3. 모르는 묶음 값은 400.

## 31. PPAP 제출 요약서 엑셀 — 고객 제출용 한 장 (PLM 하위 세션, 2026-10-24 — 상위 확정 B, 마이그레이션 없음, **상위 검토 통과 — S1~S8 추천안 + 덧붙임 3, 구현 2026-10-24**)

> PPAP 패키지(14.4)는 요소 18개의 요구·상태·첨부를 화면에만 가진다. 고객에게 패키지를 낼 때 붙이는 "무엇을 냈나" 한 장을 지시 상세 PPAP 영역 [요약서] → `POST /api/plm/ppaps/{id}/summary` 로 내려받는다. 29장 변경 통지서와 같은 방식(서버가 POI 로 서식 고정).

| # | 정할 것 | 추천안 | 다른 안(안 한 이유) |
|---|---|---|---|
| S1 | 서식 | **이 저장소 자체 서식 "PPAP 제출 요약"** — AIAG PSW 양식을 흉내 내지 않는다(저작권, 14.4·docs/02 "구조만"). PSW 는 요소 18 의 첨부 파일(고객 양식)로 따로 낸다 | PSW 양식 재현 — 저작권·고객별 차이(CSR) |
| S2 | 머리 | 발신 회사·지시 번호·지시 제목·고객·제출 레벨·패키지 상태·제출일, 판정이 있으면 판정·판정일·판정 번호 | — |
| S3 | 요소 표 | 18행 전부: 번호·요소 이름(공통코드 `PLM_PPAP_ELEM`)·요구(S 제출·R 보관·N 해당 없음 — `PLM_PPAP_REQ` 이름)·상태(`PLM_PPAP_ELEM_STATUS` 이름)·첨부 파일 이름. **요소 메모(`note`)·패키지 비고(`remark`)는 넣지 않는다**(사내 작업 메모) | 메모 포함 — 고객에게 나가면 안 되는 글이 섞일 수 있다. 요구가 생기면 "고객용 비고" 칸을 따로 |
| S4 | 고객 판정란 | 판정(승인·임시 승인·거절)·일자·판정 번호·확인자 서명·의견 — 빈 칸(29장 고객 확인란과 같은 모양) | — |
| S5 | 초안 | 패키지 DRAFT 면 제목에 "(초안 — 제출 전)". 내려받기는 막지 않는다(제출 전 고객과 확인) | DRAFT 막기 — 미리 협의하는 쓰임을 막음 |
| S6 | 권한·범위 | `plm-eco` **EXCEL**(V164 에 있음 — 마이그레이션 없음). 없는 id·다른 회사 = 404(RLS). 취소된 지시의 패키지도 내려받기 허용(기록) | PRINT 동작 추가 — sys_prog 마이그레이션 + 권한 다시 주기 |
| S7 | 언어·안전·파일 | 회사 기본 언어(29장 T3), 모든 글자 칸 `safeText`, 빈 값은 빈 칸(29장 T5 — `"-"` 금지). 파일 이름 `{지시 번호}_ppap.xlsx`, 저장된 내용 기준 | — |
| S8 | 코드 구조 | 새 `PpapSummaryService`. 29장 `EcoNoticeService` 의 서식 도우미(제목·머리 행·표 머리·테두리 상자·safeText 셀)는 같은 패키지의 작은 도우미로 빼서 둘이 쓴다 — 두 서식이 같은 모양을 유지하게 | 복사 — 두 서식의 글꼴·테두리가 따로 놀게 됨 |

- 화면: 지시 상세 PPAP 영역에 [요약서](EXCEL 이 있고 패키지가 있을 때만) → `hb.download` → "PPAP 요약서를 내려받았습니다".
- 시험 계획(`PlmPpapSummaryTest`): 정상 — DRAFT 패키지(초안 표시·요소 18행·코드 이름·첨부 파일 이름·메모 없음·판정란) / 판정된 패키지(초안 없음·판정·판정 번호) / E — 첨부 파일 이름 `=HYPERLINK(...)` → `'` / A — EXCEL 없음 403 / **A사로 B사** 404·없는 id 404.
- 상위 덧붙임(2026-10-24): ① 첨부는 **파일 이름만**(경로·id·올린 사람 없음) ② 취소된 지시의 패키지는 제목에 "(취소된 지시)" ③ S8 리팩터링 뒤 기존 `PlmEcoNoticeTest` 그대로 통과.
- 구현: `PlmSheet`(서식 — 제목·머리·소제목·문단·표·확인란·폭, 모든 칸 safeText) + `PlmDocs`(발신 회사·회사 언어·문구·코드 이름)로 빼고 `EcoNoticeService` 를 그 위로 옮김, 새 `PpapSummaryService`·`POST /api/plm/ppaps/{id}/summary`. 리팩터링 전·후 통지서 파일을 브라우저로 내려받아 값·글꼴·테두리·합친 칸·폭·높이까지 같음을 확인.

## 32. PCN 메일 발송 결과 표시 (PLM 하위 세션, 2026-10-24 — 상위 지정 다음 후보, 마이그레이션 없음, **상위 검토 통과 — M1~M6 추천안 + 덧붙임 2, 구현 2026-10-24**)

> 23장 PCN 메일은 "보냈다(아웃박스에 넣었다)" 이력만 보인다. 메일 서버가 받았는지·실패했는지는 플랫폼 읽기 계약 `MailService.statuses`(docs/36 18.5, main #494)로 읽어 통지 표의 발송 이력 줄에 붙인다. `plm_pcn_mail.outbox_id` 는 23장부터 이미 남기고 있다.

| # | 정할 것 | 추천안 | 다른 안(안 한 이유) |
|---|---|---|---|
| M1 | 어디서 읽나 | 통지 목록(`PcnService` — 지시 상세 PCN 표)을 만들 때 그 지시의 발송 이력 `outbox_id` 를 모아 **`statuses` 한 번**(500 넘으면 500개씩 나눠) | 줄마다 `status` — 이력 수만큼 조회 / 아웃박스 직접 조회 — 18.5 R1 이 막은 길 |
| M2 | 보이는 것 | 이력 줄 끝에 **상태 배지**(`mail.state.*` — 보내는 중·보냄(메일 서버 접수)·보내지 못함) + 실패면 **이유 문구**(`mail.reason.*`), 보내는 중인데 이유가 있으면(재시도 대기) 그 이유도. 보냄이면 메일 서버가 받은 시각 | 시도 횟수 표시 — 사용자에게 뜻이 약하다(이유 문구가 대신 말함) |
| M3 | 문구 | 서버가 **보는 사람 언어로 문구까지 채워** 내려준다(`stateNm`·`reasonNm` + 코드 `mailState`·`mailReason`) — 플랫폼 키(R7) 그대로, PLM 새 문구 없음 | 화면이 `mail.*` 키를 직접 — 화면 문구 묶음은 `js.*` 만 내려온다 |
| M4 | 결과를 못 찾으면 | 상태 칸을 비우고 이력 줄만(23장 그대로). 지금은 아웃박스를 지우지 않지만 보관 정책이 생기면 옛 이력이 이렇게 보인다 | "알 수 없음" 배지 — 실패처럼 읽힌다 |
| M5 | 실패한 메일 다시 보내기 | **새 기능 없음** — 기존 [메일 보내기]로 새로 보내고 이력 한 줄이 더 생긴다(18.5 R6, 23장 E1 그대로) | 이력 줄에 [다시 보내기] — 같은 사람에게 보내는 길이 둘이 되고 멱등 키·확인 대화상자를 또 만든다 |
| M6 | 권한 | 지금 이력과 같다(통지 목록 = `plm-eco` SEARCH). 상태·이유 코드는 받는 주소·서버 응답을 담지 않는다(R3·R4) | — |

- 시험 계획(`PlmPcnMailTest` 확장 또는 `PlmPcnMailStatusTest`): 보낸 직후 PENDING → 아웃박스 행을 SENT·FAIL(`REJECTED: …`)·FAIL(`TEMPORARY: …` 상한)로 바꿔 각 줄 상태·이유 코드·문구 / 이유 문구에 받는 주소·서버 응답 없음 / 이력 없는 통지 / **A사로 B사** — B사 지시 상세에 A사 메일 상태 없음(RLS + 18.5 R1).
- 화면: 이력 줄 `받는 사람 · 보낸 시각 · 보낸 사람` 뒤에 배지(+ 이유). 390 폭에서 줄바꿈.
- 상위 덧붙임(2026-10-24): ① 다른 회사 아웃박스 id 가 이력에 섞여도 결과가 나오지 않음을 시험으로 ② "보냄" 배지에 툴팁 "메일 서버가 받았습니다 — 받는 사람이 읽었는지는 알 수 없습니다"(+ 접수 시각).
- 구현: `PcnMailRow` 에 `mailState`·`mailReason`·`stateNm`·`reasonNm`·`acceptedAt`, 아웃박스 id 는 `@JsonIgnore`(응답에 없음). `PcnService.ofEco` → `fillMailStatus`(500개씩 `statuses`). 화면 배지 톤 `plm.TONES.mail`(보내는 중 info·보냄 success·보내지 못함 danger).
- 함정: 이 저장소 MyBatis 에는 **UUID 형 처리기가 없어** `uuid` 칸을 `UUID` 필드로 받으면 오류 없이 null 로 남는다 → `outbox_id::text` 로 받아 서비스에서 `UUID.fromString`.

## 33. PCN 메일에 변경 통지서 첨부 (PLM 하위 세션, 2026-10-24 — 상위 지정, 플랫폼 18.6 `MailAttach` 위, **상위 검토 통과 — F1~F7 + 덧붙임, 구현 2026-10-24, 임시 V9909**)

> 23장 메일은 글자뿐이고, 29장 통지서는 사람이 내려받아 따로 보내야 한다. 플랫폼 메일 첨부(docs/36 18.6, main #497)가 생겼으니 [메일 보내기]에서 **그 시점의 변경 통지서 엑셀을 첨부**해 보낸다.

| # | 정할 것 | 추천안 | 다른 안(안 한 이유) |
|---|---|---|---|
| F1 | 무엇을 첨부하나 | **변경 통지서 엑셀(29장) 하나만** — 메일 펼침에 "통지서 첨부" 체크(기본 켬) | 지시 첨부(도면 등)도 골라서 — `MailAttach` 는 **대상 하나**(18.6 A1)라 통지서 사본과 지시 첨부를 한 메일에 섞을 수 없고, 지시 첨부에는 사내 문서가 섞여 있다. 요구가 생기면 플랫폼 여러 대상 지원 뒤에 |
| F2 | 통지서 사본 | 보낼 때 서버가 통지서를 만들어 `FileService.attachAs("plm-eco", "PCN-{pcnId}", "{지시 번호}_notice.xlsx", …, reuseSameContent)` 로 **통지마다 사본**을 남기고 그 file_id 를 `MailAttach("plm-eco", "PCN-{pcnId}", [id])` 로 — 메일 발행과 같은 트랜잭션(실패하면 사본도 없음). 같은 내용이면 사본 하나를 다시 쓴다 | 지시 첨부(plm-eco × {eco_id})에 넣음 — 지시 첨부 목록이 더러워지고 상신 뒤 잠금(D7)과 얽힘 / 메일마다 새 파일 — 같은 내용이 쌓임 |
| F3 | 사본 대상 잠금 | `PCN-{id}` 대상은 **사용자 올리기·지우기 항상 잠김**(서버만 넣는다) — 지금 `PlmFileLock` 은 숫자가 아닌 대상 id 를 잠김으로 본다(그대로 맞음, 시험으로 고정) | 별도 화면 코드(plm-pcn) — sys_prog 마이그레이션 |
| F4 | 무엇을 보냈나 기록 | `plm_pcn_mail.notice_file_id`(nullable, FK sys_file) — **V183(임시 V9909)**. 이력 줄에 "통지서 첨부" 표시 + 그 사본 내려받기(plm-eco SEARCH — 지금 첨부 내려받기 규칙) | 기록 없음 — 고객이 "무엇을 받았나" 물으면 답을 못 함 / 사본 대상 파일 목록으로 추정 — 어느 메일에 붙었는지 모름 |
| F5 | 결재 전 지시 | 막지 않는다 — 통지서에 "(초안 — 결재 전)"이 찍혀 나간다(29장 T4 그대로). 화면 체크 옆에 지금 상태 안내 | 결재 전 첨부 금지 — 고객 사전 협의 쓰임을 막음 |
| F6 | 오류 | 플랫폼 칸 오류(`attach NOT_FOUND`·`ATTACH_EXT`·`ATTACH_COUNT`·`ATTACH_SIZE`)를 메일 펼침의 체크 칸에 표시. 통지서는 xlsx 하나라 회사 허용 목록에서 xlsx 를 뺀 경우만 실제로 난다(`ATTACH_EXT`) | — |
| F7 | 받는 사람 여럿 | 지금처럼 사람마다 `publish`(23장), 사본은 한 번 만들어 모두에게 같은 file_id | — |

- 마이그레이션: **V183**(임시 V9909) `plm_pcn_mail.notice_file_id` + FK(+ 같은 회사 확인은 기존 insert 트리거에 한 줄). 원장 표라 UPDATE 차단 그대로(insert 때만 채움).
- 시험 계획: 통지서 첨부 켬 → 아웃박스 본문에 첨부 1개(이름·크기·SHA-256)·`notice_file_id` 기록·사본은 `PCN-{id}` 대상 / 두 번째 보내기(같은 내용) → 같은 사본 / 끔 → 글자 메일·null / 사용자가 `PCN-{id}` 로 올리기 → 잠김 / 허용 목록에서 xlsx 뺌 → 400 `ATTACH_EXT`·이력 없음 / **A사로 B사** — B사 사용자 메일 보내기 404, 사본 내려받기 404.
- 상위 덧붙임(2026-10-24): F4 `notice_file_id` 는 insert 때만·바꿀 수 없게(원장 차단 트리거 그대로 + insert 가드에 "그 통지 사본·같은 회사·사용 중" 확인), 사본 내려받기 = 통지 이력 보기 권한(plm-eco SEARCH)·A사로 B사 404 / F5 첨부만 따로 막지 않고 지금 발송 규칙 그대로, 결재 전이면 **본문에도 초안 안내 한 줄** / F3 사용자 올리기·지우기 잠김을 API 직접 호출 시험으로.
- 구현:
  - 잠금(F3): 플랫폼 `attachAs` 도 같은 잠금 규칙을 묻는다 — `PlmFileLock` 은 `PCN-{id}` 를 늘 잠김으로 보되, `PcnService` 가 사본을 넣는 동안 트랜잭션 자원(`NOTICE_ATTACHING` = 그 대상 id)을 묶어 **그 대상만** 연다. 사용자 올리기·지우기는 자원이 없는 다른 트랜잭션이라 잠김(시험).
  - 사본 재사용(F2): POI 는 문서 속성 만든 시각·zip 항목 시각에 지금 시각을 넣어 같은 내용도 바이트가 달라 SHA-256 재사용이 안 된다 → `PlmSheet.bytes()` 가 만든 시각을 비우고 zip 시각을 1980-01-01 로 고정(같은 내용 = 같은 바이트). 통지서·PPAP 요약서 내려받기도 같은 규칙.
  - 본문 초안 안내 `plm.pcn.mail.draft`(미리보기에도 보임), 화면 체크 "변경 통지서 엑셀 첨부"(기본 켬)·결재 전 안내, 이력 줄 "첨부 {파일}" 내려받기 링크.
- 같은 PR 의 작은 일(상위 2026-10-24, QA 정렬 가드 `SortNullsLastGuardTest` N-1): 지시·요청 grid 정렬(`ecoOrder`·`ecrOrder`)의 `nulls last` 를 뺐다 — 지시 키는 모두 NOT NULL(소유자는 inner join), 요청의 빈 값 있는 키(품목·지시 — left join)는 `(칸 is null), 칸` 으로 빈 값을 오름·내림 모두 뒤에 둬 **예전 순서 그대로**(가드는 `nulls last` 가 든 반복만 보므로 키별 `nulls last` 도 걸린다). 가드 KNOWN 의 PLM 두 줄을 지움. 시험 `PlmGridTest` 요청 품목 정렬.

## 34. 사내 지시 기록서 엑셀 — 감사·품질 보관용 (PLM 하위 세션, 2026-10-24 — 상위 확정 A, 마이그레이션 없음)

> 29장 통지서는 고객용이라 결재선·서명·LOT 같은 사내 정보를 뺐다. 감사·품질 기록으로 보관할 **사내판**을 지시 상세 [기록서] → `POST /api/plm/ecos/{id}/record` 로 내려받는다.

| # | 정한 것 | 다른 안(안 한 이유) |
|---|---|---|
| R1 | 내용: 머리(지시 번호·제목·사유·종류·상태·유효일·담당·작성·상신·승인·적용 일시·적용한 사람·취소 사유) → 변경 내용 → 대상 표(리비전·지금 상태·결재 뒤 바뀜 표시) → **결재선**(순서·결재자·유형·결정·일시·의견) → **전자서명**(의미·서명자·방법·일시·지금 내용과 다름) → 고객 승인·PPAP(레벨·상태·제출·판정·판정 번호)·PCN(번호·고객·통지·기한·상태·응답) → 표시된 LOT 수 | 화면 그대로 캡처 — 보관 서식이 화면 배치에 묶임 |
| R2 | 언어 = **보는 사람 언어**(사내 문서, 상위 승인) — 29·31장(회사 기본 언어)과 다름 | 회사 기본 언어 — 사내 문서는 읽는 사람 기준 |
| R3 | 권한 = `plm-eco` EXCEL. 화면 상세와 같은 조회(`EcoService.detail`)를 그대로 써서 보이는 것과 기록이 같다. 다른 회사·없는 id = 404 | — |
| R4 | **감사 기록**: 다른 엑셀 내보내기와 같게 접속 로그 `ACTION`·`EXCEL`(ProgAuth 가 남김) + detail `record;ecoId={id}` | 로그 없음 — 결재·서명 같은 감사 정보가 나간 기록이 없음 |
| R5 | 파일 이름 `{지시 번호}_record.xlsx`, 서식 도우미 `PlmSheet`(같은 내용 = 같은 바이트), 모든 칸 safeText·빈 값 빈 칸 | — |

- 상위 확인(2026-10-24): 감사 정보를 담으니 내려받기를 접속 로그에 남기는지 — 엑셀 동작(EXCEL)은 `ProgAuthAspect` 가 `ACTION` 으로 남긴다. detail 은 컨트롤러가 `record;ecoId=` 로 채우고, **같이 고침**: 29장 통지서(`notice;ecoId=`)·31장 PPAP 요약서(`ppapSummary;ppapId=`)도 detail 이 비어 있었다(서비스 안이 아니라 컨트롤러에서 — PCN 메일이 통지서를 다시 쓰기 때문).
- 언어는 로그인 사용자 정보(`LoginUser.localeCd`)로 정해진다(I18nConfig) — 요청 Accept-Language 가 아니다.

## 35. 고객 통지 응답 기한 임박 알림 (PLM 하위 세션, 2026-10-24 — 상위 확정 B, 마이그레이션 없음)

> 26장 "기한 지남" 은 이미 늦은 뒤에 온다. 그 앞 단계로 응답 기한이 다가온 열린 통지를 지시 담당에게 한 번 알린다.

| # | 정한 것 | 다른 안(안 한 이유) |
|---|---|---|
| D1 | 이벤트 `PLM_PCN_DUE_SOON`(기본 켬, 정렬 142) — 받는 사람·화면 권한·구독은 26장과 같음(지시 담당, 플랫폼 `NotificationService`) | 사람별 설정 — 구독(켜고 끔)으로 충분 |
| D2 | 조건 = 26장과 같은 열린 통지(`SENT`, 취소 지시 제외) 중 **응답 기한이 오늘 ~ 오늘 + 2일**(`PlmHomeMapper.dueSoonPcns`). 오늘이 기한이면 임박, 내일부터는 지남(26장) | 기한 하루 전만 — 주말·휴일에 놓침 |
| D3 | 며칠 전 = **코드 상수 2**(`DUE_SOON_DAYS`, 상위 승인) — 회사마다 바꿔야 하면 그때 정책(마이그레이션) | 처음부터 정책 — 쓰임 확인 전 |
| D4 | 한 번만: 중복 키 `PCN-SOON:{id}:{응답 기한}`(지남 `PCN:` 과 따로) — 매시 실행(26장 `PlmJobs.overdue`)이 같은 통지를 다시 알리지 않는다 | 매일 다시 — 알림이 쌓임 |
| D5 | 링크 = `plm-eco` + 지시 번호 검색(26장 N6 그대로) | — |

- **경계(상위 확인 2026-10-24)**: 회사 오늘 기준 — 기한 = 오늘이면 **임박**, 다음 날(기한 = 어제)부터 **지남**. 같은 통지·같은 기한은 임박(`PCN-SOON:`)·지남(`PCN:`) 알림을 **각각 한 번씩** 받는다(키가 다르다). 시험 `PlmOverdueNotifyTest` "경계…"(시계를 하루 옮겨 확인).

## 36. DOC 대상 문서 유형에 PFMEA (PLM 하위 세션, 2026-10-24 — 상위 지시, PMS 검토 docs/41 11.10.3 E-1, V187·임시 V9910)

> APQP PFMEA 개정도 ECO 로 통제할 수 있게 DOC 대상 허용 유형에 `PFMEA` 를 더한다(16.1 의 도면·작업표준서·관리계획서·검사기준서 + PFMEA).

- 바꾼 곳 두 곳: DB 트리거 `hb_guard_plm_eco_target` 의 `v_doc_type NOT IN (…)`(새 V 로 `CREATE OR REPLACE` — 본문은 **가장 최근 정의 V088** 에서 출발, 그 뒤 V 에서 다시 정의되지 않음을 grep 으로 확인) / `EcoService.DOC_TYPES`(API 칸 오류 `DOC_TYPE`). 두 목록은 같아야 한다(주석으로 서로 가리킴).
- 그대로 둔 것: `plm_eco_target` 칸·CHECK, `PLM_TARGET_TYPE` 코드(대상 유형은 여전히 DOC 하나), QMS `mst_doc` 유형 목록(PFMEA 는 V078 부터 있음).
- 목록 밖(`OTHER`)은 그대로 거부 — API `DOC_TYPE`, 직접 SQL `ck_plm_eco_target_doc_type`.

### 36.1 (같은 PR) 보낸 PCN 통지서 사본 DB 잠금 — QA P-1 (docs/qa/2026-10-24_b3-mail-pcn.md, #529)

- 33장 사본(`plm-eco × PCN-{id}`)은 앱 규칙(`PlmFileLock`)으로만 막혀 있고 `sys_file.locked_yn = 'N'` 이었다 → 사본을 넣은 같은 트랜잭션에서 `PlmMapper.lockFiles(PCN-{id})` 로 `locked_yn = 'Y'`(QMS 배포 문서·ECO 적용 첨부와 같은 한 방향 잠금). 앱을 거치지 않는 지우기·바꾸기도 `ck_sys_file_locked` 가 막는다. 같은 내용 재사용(F2)은 사용 중 파일만 보므로 잠긴 사본도 그대로 다시 쓴다.
- QA `PcnNoticeQaTest` 의 관찰 시험(잠기지 않음 고정)을 #529 병합 뒤 이 PR 에서 뒤집었다 → `보낸_사본은_DB_잠금_표시로_굳는다`(Y·직접 SQL 거부).

## 37. PPAP 제출 메일 — 고객 담당자에게 요약서 첨부 (PLM 하위 세션, 2026-10-26 — **상위 채택 P1~P9, 구현 2026-10-26**, V190·임시 V9911)

> PPAP 패키지(14.4)를 제출(SUBMITTED)한 뒤 고객 담당자에게 "제출했다" 를 메일로 알린다. 23장 PCN 메일·32장 발송 결과·33장 첨부 규칙을 그대로 따르고, 첨부는 31장 PPAP 제출 요약서. 상위 필수 조건 셋: **아웃박스 경유**, **첨부 Mail Q1(a) 5파일·10MB**, **받는 사람이 고객 담당자로 정해지는 근거**(임의 주소 입력 여부까지).

| # | 정할 것 | 추천안 | 다른 안(안 한 이유) |
|---|---|---|---|
| P1 | 받는 사람 — 근거 | **그 PPAP 패키지의 고객(`plm_ppap.cust_id`, 판매 거래처 — V069 트리거가 SALES·BOTH 만 허용)의 담당자 표 `mst_cust_contact` 에서 고른다**(사용 중·메일 있음, 대표 먼저 기본 체크, 1~10명). 서버가 고른 id 가 그 목록 안인지 다시 본다(다른 고객·다른 회사·사용 안 함·메일 없음은 구분 없이 `NOT_PPAP_CONTACT`) — 23장 E1 과 같은 규칙 | 주소 직접 입력 — **허용하지 않는다**: 오타·엉뚱한 곳으로 설계·품질 자료가 나간다. 담당자가 없으면 기준정보(거래처 담당자)에 먼저 넣는다(화면 안내) |
| P2 | 송신 경로 | **플랫폼 `MailService.publish(…, MailAttach)` → 아웃박스 MAIL**(18.3·18.6) — PLM 은 SMTP·아웃박스 표를 직접 만지지 않는다. 같은 트랜잭션에서 발행 + 발송 이력 | PLM 이 직접 송신 — 재시도·중복·실패 기록이 두 벌 |
| P3 | 언제 | **SUBMITTED 패키지만**(제출 뒤 고객 판정 전). 작성 중(DRAFT)·판정 뒤(APPROVED·INTERIM·REJECTED)는 409 `PLM_PPAP_STATE`. 다시 보내기 허용(담당자 바뀜·재요청) — 매번 이력 한 줄. 취소된 지시의 패키지는 409 | 판정 뒤에도 — "제출했다" 알림의 뜻이 없음 / 제출과 동시에 자동 — 받는 사람을 고를 수 없음 |
| P4 | 첨부 | **31장 PPAP 제출 요약서 하나**: 보낼 때 그 시점 요약서를 만들어 `plm-eco × PPAPMAIL-{ppapId}` 에 사본(`attachAs`, 같은 내용 재사용 — 33장 F2·바이트 고정), **DB 잠금 `locked_yn = Y`**(36.1 P-1), `MailAttach(plm-eco, PPAPMAIL-{id}, [사본])`. Mail Q1(a) **5파일·10MB** 는 플랫폼이 검사(`ATTACH_COUNT`·`ATTACH_SIZE`) — 요약서 한 장이라 실제로는 넘지 않는다 | 요소 증빙 파일(PSW 등)도 첨부 — `MailAttach` 는 대상 하나라 증빙(PPAP-{id})과 사본을 섞을 수 없고, 증빙 18개는 5파일 상한을 쉽게 넘는다. 증빙은 고객 포털·별도 경로(요약서에 파일 이름 목록이 있다). 플랫폼 여러 대상 첨부가 생기면 다시 본다 |
| P5 | 사본 대상 잠금 | `PPAPMAIL-{id}` 는 사용자 올리기·지우기 **늘 잠김** — 33장 F3 과 같이 `PlmFileLock` + 트랜잭션 자원으로 서버가 넣는 동안만 연다. 증빙 대상 `PPAP-{id}` 와 이름이 달라 증빙 목록에 섞이지 않는다 | 증빙 대상(PPAP-{id})에 사본 — 증빙 목록이 더러워지고 작성 중에만 열리는 규칙과 얽힘 |
| P6 | 기록 | **새 표 `plm_ppap_mail`**(**V190**·임시 V9911, 회사별 RLS·감사·보존 MASTER·`REVOKE DELETE, TRUNCATE`): ppap_id·contact_id·받는 주소/이름 스냅샷·제목·아웃박스 id·요약서 사본 id·요청 시각·요청자. insert 가드 = 담당자가 그 패키지 고객의 담당자, 사본은 그 패키지 사본·같은 회사·사용 중. UPDATE/DELETE 차단(원장) | `plm_pcn_mail` 에 섞음 — PCN 과 PPAP 는 다른 업무 단위(FK 가 다름) |
| P7 | 발송 결과 | 32장 그대로 — `MailService.statuses` 로 이력 줄에 배지·이유, 아웃박스 id 는 응답에 없음 | — |
| P8 | 내용 | 글자 메일 + 첨부, **회사 기본 언어**(23장 E4). 제목 `[{회사}] PPAP 제출 {지시 번호} — {지시 제목}`, 본문: 지시 번호·제목, 제출 레벨, 제출일, 제출(S) 요소 이름 목록, "판정을 회신해 주십시오" — **요소 메모·패키지 비고·사내 화면 주소 없음**(31장 S3) | 본문에 메모 — 사내 메모가 나감 |
| P9 | 권한·멱등 | `plm-eco` SAVE(받는 사람 후보 조회도 SAVE — 담당자 메일은 개인정보), `X-Idempotency-Key` 필수(없으면 400), 메일 꺼짐 409 `MAIL_DISABLED`(버튼 숨김) — 23장 E5·E8 | — |

- API: `GET /api/plm/ppaps/{id}/mail-targets` · `POST /api/plm/ppaps/{id}/mail {contactIds}` · PPAP 상세 응답에 발송 이력(+ 결과 배지).
- 화면: PPAP 영역 [메일 보내기](SUBMITTED·SAVE·메일 켜짐) → 담당자 체크(대표 기본) + 제목·본문 미리보기 + "요약서 첨부(보내는 시점 내용)" 안내 → 확인 대화상자 → 이력 줄(받는 사람·시각·보낸 사람·결과 배지·첨부 링크).
- 시험 계획(`PlmPpapMailTest`): 제출된 패키지 → 담당자 둘(사본 1개·두 메일 같은 사본 id·아웃박스 첨부 1개·`locked_yn = Y`) / 다시 보냄 → 사본 그대로 / 작성 중·판정 뒤·취소 지시 409 / 다른 고객 담당자·사용 안 함·메일 없음 id → 400 `NOT_PPAP_CONTACT` / 주소 직접(없는 칸) → 무시 / 멱등 키 없음 400·같은 키 두 번 409 / 조회 권한만 403 / **A사로 B사** 패키지·담당자 → 404 / 사용자가 `PPAPMAIL-{id}` 에 올리기·지우기 → 409 `FILE_LOCKED` / 원장 수정 거부 / 본문에 메모·비고 글자 없음.
- 상위 채택(2026-10-26): P1~P9 그대로(주소 직접 입력 금지·첨부 요약서 한 장 좋음). 필수 시험: 다른 거래처 담당자 거부 · 사용 중지 담당자 거부 · 같은 멱등 키 한 번 · SUBMITTED 아니면 거부 · A사로 B사 · 아웃박스 롤백(이력·아웃박스 함께).
- 구현:
  - `PpapMailService`(`mailTargets`·`mail`) + `POST /api/plm/ppaps/{id}/mail`(헤더 `X-Idempotency-Key` 필수 — 없으면 400, 23장과 같이 컨트롤러 `@RequestHeader`)·`GET …/mail-targets`. 받는 사람 후보·검사는 `PlmPpapMapper.findPpapContacts`(패키지 고객의 사용 중·메일 있는 담당자). 칸 오류 `NOT_PPAP_CONTACT`.
  - 요약서 사본: `PpapSummaryService.summary` → `attachAs(plm-eco, PPAPMAIL-{id}, reuseSameContent)` → `lockFiles`(DB 잠금). 서버만 넣게 `PlmFileLock` 트랜잭션 자원을 `SERVER_ATTACHING` 하나로 합쳤다(PCN-·PPAPMAIL- 공통, 33장 쓰던 `PcnService.NOTICE_ATTACHING` 대체).
  - 발송 결과(P7): 32장 `fillMailStatus` 를 `PlmMailStatus`(PCN·PPAP 공통)로 옮기고, 이력 줄 공통 칸을 `PlmDtos.MailRow`(추상)로 — `PcnMailRow`·`PpapMailRow` 가 상속. 응답 모양은 그대로.
  - 화면: 메일 펼침(`toggleMail`)·이력 줄(`mailLine`)을 PCN·PPAP 공통 함수로(통지서 첨부 체크는 PCN 만, PPAP 는 "요약서 첨부" 고정 안내). PPAP 영역 [제출 메일 보내기](SUBMITTED·SAVE·메일 켜짐·취소 아닌 지시).
  - 롤백 시험: 담당자 표에는 주소 형식 검사가 없어 "bad-address" 담당자를 둘째로 두면 첫 메일 발행 뒤 플랫폼이 400 → 이력·아웃박스·사본 모두 0(한 트랜잭션).

## 38. PPAP 요소 6·7 ← APQP 현행 확정본 (PLM 하위 세션, 2026-10-26 — PMS A3-6 요청 docs/41 11.11, **상위 채택 Q1~Q7, 구현 2026-10-26**, V194·임시 V9912)

> PMS(APQP) 가 `ApqpLookup.current(itemId)`(docs/41 11.11 P1~P4)를 만들었다 — 품목의 현행 확정(RELEASED) PFMEA·관리계획서 리비전(`RevView`·phase·docRevId), 예외 없음, APQP 없는 설치본 = 빈 목록. 요청: PPAP 요소 6(공정 FMEA)·7(관리계획서)을 채울 때 이것을 후보로 보이고, 근거를 남긴다면 (가) APQP id 를 FK 없이 또는 (나) docRevId 의 QMS 문서 첨부를 가져오기, 폐기된 리비전은 `rev()` 로 상태 표시.
> 지금 요소는 요구(S/R/N)·상태(OPEN/DONE/WAIVED)·증빙 파일 하나(그 ECO·`PPAP-{id}` 첨부, V082 트리거)·메모뿐 — APQP 를 가리킬 칸이 없어 **근거를 남기려면 마이그레이션이 필요**하다(그래서 설계 먼저).

| # | 정할 것 | 추천안 | 다른 안(안 한 이유) |
|---|---|---|---|
| Q1 | 후보 범위 | **그 ECO 대상(`plm_eco_target`)의 품목들**(종류 무관 item_id, 중복 제거) 각각 `current(itemId)` — 요소 6 = kind FMEA, 요소 7 = kind CP(단계 전부, **PRODUCTION 먼저** 표시·기본 선택은 하지 않음). 대상 품목이 많아도 PPAP 한 건 열 때 한 번이라 상한 없음(11.11 P4), 다만 대상은 ECO 상한 안 | 고객 품목만 — 대상 품목이 곧 PPAP 대상 부품이다. 모든 품목 검색 — 이 지시와 무관한 문서가 섞임 |
| Q2 | 근거 기록 | **(가) APQP 리비전 id 를 FK 없이** — `plm_ppap_elem` 에 `apqp_kind`(FMEA·CP)·`apqp_rev_id`·`apqp_label`(보낸 때 "번호 rev N[ 단계]" 스냅샷, APQP 없는 설치본·권한 없는 사람에게도 보이게) 세 칸. CHECK: 셋 다 null 이거나 셋 다 있음, 요소 6 ↔ FMEA·요소 7 ↔ CP 만. 증빙 파일(`file_id`)과 **함께 둘 수 있다**(파일 = 고객 제출본, APQP = 사내 원본) | (나) QMS 문서 첨부 가져오기 — `DocLookup` 에 파일 계약이 없어 QMS 표·`sys_file` 대상 이름을 PLM 이 알아야 한다(모듈 경계 위반). QMS 가 파일 계약을 내면 그때 "가져오기" 버튼만 덧붙인다(보류) |
| Q3 | 저장 검사 | 저장(`save`, 작성 중만 — 기존 트리거) 때 보낸 (kind, revId) 가 **그 순간 후보 목록 안**(현행 확정·대상 품목)인지 서버가 다시 본다 — 아니면 칸 오류 `apqpRevId` `NOT_CANDIDATE`. 비우기 = 세 칸 null. label 은 서버가 만든다(사용자 값 무시) | 아무 id 허용 — 다른 품목·작성 중·폐기본·다른 회사 id 가 근거로 들어감 |
| Q4 | 권한 | 후보 조회·연결 = `plm-eco` SAVE **+ 그 APQP 화면(PFMEA·관리계획서) SEARCH** — PMS 산출물 연결(docs/41 D20 의 반대 방향)과 같은 규칙. APQP SEARCH 가 없으면 후보 빈 목록, 저장 시 연결 바꾸기 403(이미 있는 연결은 label 로만 보인다) | SAVE 만 — PLM 권한으로 APQP 문서 존재·번호를 엿볼 수 있음 |
| Q5 | 상태 표시 | 상세 응답의 요소에 `apqpStatus` — `rev(kind, id)` 로 읽어 RELEASED·OBSOLETE(폐기 = "새 리비전 있음" 배지), 못 읽으면(APQP 없음·지워짐·권한 없음) null → label 만. **근거는 바꾸지 않는다**(제출된 패키지는 그 시점 근거가 맞다) | 폐기되면 자동으로 새 리비전으로 — 고객에 낸 것과 기록이 어긋남 |
| Q6 | 전자서명·요약서·메일 | 서명 내용 해시(`PlmSignContent.ppap`)에 **연결이 있을 때만** label 을 덧붙인다(연결 없는 기존 패키지의 해시 그대로 → 기존 서명 유효). 요약서(31장) 증빙 칸: 파일 이름, 없으면 APQP label. 메일 본문(37장)은 요소 이름만 — 그대로 | 해시에 늘 넣기 — 기존 서명이 전부 "내용 바뀜"이 된다 |
| Q7 | 제출 조건 | 바꾸지 않는다 — 요소 상태(DONE·WAIVED)가 기준, APQP 연결은 선택. 연결해도 상태를 자동으로 DONE 으로 바꾸지 않는다 | 6·7 은 APQP 연결 필수 — APQP 를 안 쓰는 설치본·고객이 막힌다(정책 키로 나중에 가능) |

- 마이그레이션(**V194**·임시 V9912): `plm_ppap_elem` 칸 셋 + CHECK 둘(모두/없음, 요소 번호 ↔ 종류). 감사 트리거는 이미 있음(표 단위). FK 없음(모듈 간 id 는 이력처럼 — docs/41 11.11 문안).
- API: `GET /api/plm/ppaps/{id}/apqp-candidates` → `{fmea:[…], cp:[…]}`(`CurrentView` 를 PLM DTO 로: kind·revId·no·rev·title·phase·itemCd) · 저장 `PpapElemItem` 에 `apqpRevId`(kind 는 요소 번호로 정해진다) · 상세 요소에 `apqpKind/RevId/Label/Status`.
- 화면: 요소 6·7 줄에 [APQP 에서 고르기] → 후보 목록(품목·번호·rev·단계·확정 시각) → 고르면 label 표시 + 상태 배지(폐기 = 경고색), [연결 끊기]. 작성 중·SAVE·APQP SEARCH 일 때만 버튼.
- 시험 계획(`PlmPpapApqpTest`, APQP 쪽 표를 owner 로 시드 또는 `ApqpLookup` 실제 빈): 대상 품목의 확정 PFMEA·CP → 후보 / 작성 중·결재 중·폐기·다른 품목 → 후보 아님·저장 400 `NOT_CANDIDATE` / 요소 6 에 CP id·요소 7 에 FMEA id → 400 / 연결 뒤 APQP 새 리비전 확정(앞 것 OBSOLETE) → 근거 그대로·`apqpStatus` OBSOLETE / 제출 뒤 연결 바꾸기 409 / APQP SEARCH 없음 → 후보 빈·저장 403 / 연결 없는 패키지 서명 해시 불변 / **A사로 B사** 패키지 후보 404·B사 FMEA id 저장 400 / label 위조 무시.
- 상위 채택(2026-10-26): Q1~Q7 그대로(Q2 = (가), Q4 = APQP SEARCH 추가). 마이그레이션 조건: 제출 뒤 불변 가드에 새 칸 포함(가장 최근 정의에서), CHECK 둘은 추가만. 필수 시험: 후보 밖 rev 400 · SEARCH 없음 → 후보 빈 목록·연결 403 · 요소 6 에 CP 거부 · 기존 서명 해시 불변 · A사로 B사.
- 구현:
  - 마이그레이션 V194: 칸 셋 + CHECK 둘(`ck_plm_ppap_elem_apqp` 모두/없음 — `apqp_label IS NOT NULL` 을 명시해야 한다: `length(btrim(NULL)) > 0` 은 NULL 이라 CHECK 를 통과한다, 시험이 잡음 / `ck_plm_ppap_elem_apqp_kind` 요소 번호 ↔ 종류). **가드는 다시 정의하지 않았다** — 마지막 정의(V082 `hb_guard_plm_ppap_elem`)가 패키지 DRAFT 아니면 행 UPDATE 를 칸 구분 없이 막아 새 칸도 이미 덮인다(시험: 제출 뒤 owner 직접 UPDATE → `ck_plm_ppap_fixed`).
  - API 는 설계 글머리와 달리 **저장(`PUT /ppaps/{id}`)에 싣지 않고 따로** `PUT /api/plm/ppaps/{id}/apqp {rowVer, elemNo, revId|null}` — 저장 본문의 요소 항목에 칸을 더하면 칸을 빠뜨린 저장(기존 화면·API 사용자)이 근거를 몰래 지운다(조용히 틀림). 연결도 패키지 `row_ver` 를 올려 동시 저장과 충돌한다. 화면은 저장 안 한 입력이 있으면 먼저 저장하라고 안내. 후보 `GET /api/plm/ppaps/{id}/apqp-candidates`(`plm-eco` SAVE).
  - `PpapApqpService`(`kinds`·`candidates`·`link`·`fillStatus`) — APQP 는 `ApqpLookup.of(provider)` 로만, 권한은 `PermissionService.actions(apqp-fmea|apqp-cp)` SEARCH. 후보 밖은 구분 없이 칸 오류 `apqpRevId` `NOT_CANDIDATE`(작성 중·결재 중·폐기·다른 품목·다른 종류·다른 회사·없는 id). 상세 응답 `PpapDetail.apqpKinds` + 요소 `apqpKind/RevId/Label/Status`(상태는 그 종류 SEARCH 가 있을 때만).
  - 서명 해시: 요소 줄 뒤에 연결이 있을 때만 `A` 줄(kind·revId·label, 상태는 넣지 않음) — 연결 없는 패키지는 38장 이전 내용 문자열 그대로(시험이 문자열로 고정). 요약서 증빙 칸 = 파일 이름, 없으면 label.
  - 덧: 37장에서 빠졌던 칸 오류 문구 `valid.NOT_PPAP_CONTACT`(ko/en)를 같이 넣었다.

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-26 | PLM | 설계 Q1~Q7(V194·임시 V9912) — 상위 검토 대기 |
| 2026-10-26 | 상위 | Q1~Q7 채택(Q2 (가)·Q4 그대로), 가드·CHECK 조건과 필수 시험 5 |
| 2026-10-26 | PLM | 구현 — 연결은 저장과 분리한 API(위 이유), 가드 재정의 불필요(행 단위로 이미 막음) |

## 39. 데모 둘러보기 (2026-11-02, `PlmDemoSeeder`·`PlmDemoFlow`, 마이그레이션 없음)

local·demo 프로필, 회사에 ECR·ECO 가 하나도 없을 때만 회사 관리자 이름으로 한 번 넣는다(한 트랜잭션). SRM 시더 뒤(`DemoSeedOrder.PLM`). 결재(도면·ECO)는 실제 `ApprovalService` 로 — 요청자 = 회사 관리자, 결재자 = **데모 결재자**(`demo-approver`, 권한그룹 없음, 비밀번호는 아무도 모르는 무작위 값의 해시 — 로그인 불가). 본인 결재 금지 정책을 지키려고 둘째 사용자를 둔다. 날짜는 시더 스레드 전용 시계라 "유효일 ≥ 오늘" 검사를 그날 기준으로 통과한다.

| 화면 | 무엇을 보면 되나 | 데모 데이터 |
|---|---|---|
| 관리문서 `qms-doc` | DRAWING 리비전: A0 결재·배포 → A1 은 ECO 가 배포(A0 폐기, 첨부 잠금) | DWG-64311-DM100(A0 폐기·A1 배포), DWG-65110-DM200(A0 배포·A1 작성 중 = ECO-3 대상) |
| ECO `plm-eco` — 적용 | 설계 변경 + 고객 승인 기록 → 적용 = 도면 리비전 배포 | ECO-1 사이드 멤버 INR 판 두께 1.6t → 1.8t(-45일 작성, 고객 승인 CA-DEMO-0142, -41일 적용) |
| ECO — 4M 연결 | 변경 종류 PROCESS_4M, 설비·공정 대상, QMS 4M 세부(`qms-change`), 변경 뒤 첫 LOT 3개 표시 | ECO-2 스폿 셀 1 용접 건 교체(MACHINE·METHOD, -16일 적용) |
| ECR `plm-ecr` → ECO · 홈 "승인됨 적용 대기" 카드 | ECR 접수로 ECO 생성 → 도면 B 대상 → 승인, 유효일은 2주 뒤 | ECO-3 너트 M8 → M10(APPROVED) |
| ECO — 임시 4M | TEMP: 복귀 조건 필수, 표시 끝 날 | ECO-4 와이어 대체품(작성 중, MATERIAL·SUPPLIER) |
| ECR 접수 대기 | 접수·반려 해 보기 | 프런트 사이드 멤버 RH 타점 추가 검토(OPEN) |

- 시험 `PlmDemoSeederTest`: 상태 분포·도면 리비전 상태·4M 세부 1:1·결재 기록(요청자 ≠ 결재자, 결재자 권한그룹 없음), 멱등, 기준정보 없으면 아무것도 안 함, A사로 B사.
