# 테스트 기록 — PLM 설계 변경

> TESTING.md 에서 옮김(2026-10-22, 내용 그대로). 실행 방법·이상 사용 체크리스트 A~K·데이터 규칙은 [TESTING.md](../../TESTING.md).
> 새 기록은 **이 파일 끝에** 덧붙인다(시간순).

## PLM 1단계 설계 변경 — ECR·ECO·영향 대상·유효일·첨부 (2026-10-20, V050(임시 V9900), 브랜치 `claude/plm-work`)

설계 docs/39. 시험 `PlmEcoTest` 11건(Testcontainers, 앱 계정 RLS 적용 상태).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| ECR 등록 → 접수(새 ECO, 주 품목 = ITEM 대상) → 대상(BOM·라우팅 작성 중 리비전) → 상신 → 결재 승인 → 적용 | ECO RELEASED, BOM·라우팅 A1 = 유효일부터 RELEASED·`change_no` = ECO 번호, A0 = 유효일로 마감, `released_by` = 적용자 | 기존 `BomService`·`RoutingService` 확정 흐름 그대로 호출 |
| 반려 / 회수 | 둘 다 DRAFT 로, 대상 `base_row_ver` 해제 → 다시 고쳐 상신 가능. 결재 중 내용·대상 수정 409 `PLM_ECO_STATE` | — |
| **F** 상신 뒤 BOM 화면에서 A1 행 수정 | 적용 409 `PLM_TARGET_CHANGED`, rowKey `t{대상}`, 상세 `changed=true`, 라우팅도 확정 안 됨 | 상신 때 리비전 `row_ver` 고정 |
| **F** 상신 뒤 BOM 리비전 폐기 | 적용 409, `NOT_DRAFT` | — |
| **G** 대상 2개 중 뒤 것(BOM)이 확정 검사 실패(자품목 사용 중지) | 400 `PLM_RELEASE_CHECK`(대상 rowKey + 원래 행 오류 `t{대상}:{행}`), 앞서 확정한 라우팅도 롤백, A0 마감 안 됨, ECO APPROVED 그대로 | 한 트랜잭션 |
| 고객 승인 필요 ECO 적용 | 기록 전 409 `PLM_CUST_APPROVAL_REQUIRED`, 직접 SQL `status='RELEASED'` → 트리거 `ck_plm_eco_cust_gate`, 승인(번호·날짜 필수) 뒤 적용 OK, 적용 뒤 기록 변경 409. 협력사(PURCHASE)를 고객으로 → 400 | docs/02 2.3 게이트 |
| **C** 승인 전 적용 / 대상 없이 상신 / 직접 SQL DRAFT→APPROVED / 승인 뒤 유효일 직접 수정 / 승인 뒤 대상 직접 삭제 | 409 / 400 `NO_TARGETS` / `ck_plm_eco_transition` / `ck_plm_eco_fixed` / 55000 | 트리거가 최종 |
| **D** 같은 rowVer 로 적용 2건 동시 | 200 1건 + 409 1건, ECO RELEASED 한 번 | `FOR UPDATE` + 조건부 UPDATE |
| **D** 같은 작성 중 리비전을 두 ECO 에 | 400 `OPEN_ECO`(먼저 묶은 ECO 번호 표시), 직접 SQL → `uq_plm_eco_target_open`, 확정본 → `NOT_DRAFT`, 앞 ECO 취소 뒤엔 가능 | 트리거가 리비전 행 `FOR UPDATE` 후 확인 |
| **E** 빈 제목·없는 사유·지난 유효일 / 오래된 rowVer / 대상 유형·id 누락·없는 품목 / 회사 추가 사유 코드 | 400 칸 오류 3개(PAST) / 409 / REQUIRED·CODE·NOT_FOUND / 사용 가능 | `hb_code_usable` |
| **A** SEARCH 만 → 저장·적용 403, SAVE 만 → ECR 접수 403, 남의 ECR 수정·취소 403 `PLM_NOT_REQUESTER`, 빈 반려 사유 400, 닫힌 ECR 다시 접수 409 `PLM_ECR_CLOSED` | 기대대로 | — |
| **B A사로 B사**: ECO·ECR 조회·수정·대상·상신·적용·취소·접수 | 전부 404, 목록·대상 후보에 B사 없음 | RLS |
| **B** A사 ECO 에 B사 BOM id 대상 / B사 ECO 에 ECR 묶기 / B사 사용자를 담당으로 | 400 `NOT_FOUND`·`ecoId`·`ownerId` | — |
| **B 직접 SQL**(앱 계정, A사 범위): B사 리비전·B사 ECO 를 가리키는 대상 행, B사 comp_cd 로 ECR | `fk_plm_eco_target_comp` ×2, 거부(행 없음) | 회사별 FK 는 RLS 를 무시 → 트리거 |
| 전체 `./gradlew check`(엄격 의존성 검증, 최신 main merge 뒤) | 1072건 통과(실패 0, 건너뜀 2 기존) + mapper `${}` 검사 + JS 타입 검사·단위 시험 | — |

#### 브라우저 확인 (ZT 데이터, local, Playwright Chromium)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| admin: ECR 등록(ZT-FG) → 접수 → ECO 대상 후보에서 BOM·라우팅 A1 추가 → 대상 저장 → 도면 PDF 첨부 → 결재선 zt-mgr → 상신 | 화면 메시지·상태 기대대로, 첨부 목록에 시각·올린 사람 | — |
| zt-mgr 로그인 → 승인 버튼(차례일 때만 보임) → 승인 | 승인됨 | — |
| admin 적용 | 적용됨, DB 에서 A0 `valid_to` = 오늘, A1 RELEASED·`ECO2610-0001` | — |
| **적용 뒤 대상 표에 "결재 뒤 바뀜" 이 모두 표시됨(발견)** | 적용(확정)이 리비전 `row_ver` 를 올린 것을 변경으로 봄 | `changed` 는 PENDING·APPROVED 에서만 → 시험에 단언 추가 |
| 대상 후보가 품목 입력 전 전체 목록으로 먼저 채워짐 | 자동화 스크립트가 다른 품목 후보를 집음(데모 BOM 이 확정됨) | 로컬 DB 초기화 후 다시. 화면은 품목 입력 뒤 "품목만" 항목이 생김 — 사람이 고르는 데는 문제 없음 |
| 1920 / 390 폭 | 390 에서 가로 스크롤 없음(scrollWidth 390), 상세는 서랍 | — |
| 콘솔 오류 | `/favicon.ico` 404 하나뿐(기존, PLM 무관) | — |

미확인: 플랫폼 결재함 화면이 아직 없어 결재는 ECO 화면 안 버튼(플랫폼 결재 API 호출)으로만 확인. 고객 승인 화면 흐름은 API 시험만(브라우저 미확인).

## PLM 3a 계약 — ChangeMarker·EcoTrace 인터페이스 + no-op 빈 (2026-10-20, 마이그레이션 없음, 브랜치 `claude/plm-work`)

설계 docs/39 12.4·12.7. MES 표·PLM 표 변경 없음.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| **PLM 없는 설치본**: 빈 없는 BeanFactory 에서 `ChangeMarker.of(ObjectProvider)`·`EcoTrace.of(…)` | `NONE` 반환, `mark`(지시 있음·지시 없음 둘 다) 빈 값, `ecosOfLot` 빈 목록, 예외 없음 | MES 는 이 방법으로만 받는다 |
| PLM 이 있을 때(3a 기본 빈 `PlmLinkConfig`) | 표시·조회 없음 | 3b 에서 실제 구현으로 교체 |
| 다른 구현 빈이 등록된 경우(3b 모양) | 그 빈을 그대로 사용 | MES 호출 코드 그대로 |
| 실제 앱 컨텍스트 | 두 빈 모두 하나씩(`getIfUnique` 가 null 아님) | 중복 빈 없음 |

## PLM 3b 설계 변경 ↔ 생산 LOT 표시 — `plm_eco_lot`·변경 종류·설비/공정 대상·실제 `ChangeMarker`/`EcoTrace` (2026-10-21, V060(임시 V060(임시 V9901)), 브랜치 `claude/plm-work`, docs/39 13장)

`PlmEcoLotTest` 9건 + `PlmEcoTest`·`PlmLinkContractTest` 3a 단언을 3b 로 바꿈. 전체 `./gradlew check` 1,157건 통과(건너뜀 2). MES 표는 읽기만.

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 새 리비전(BOM·라우팅 A1) 지시 LOT 3개, 첫 N=2, **투입 계보 없음** | 앞 2개만 `FIRST_LOT` seq 1·2, 셋째 빈 값 | 표시 원본 = LOT 단위(F2) |
| 정상 | 이전 리비전 지시 LOT(적용 뒤 생산) + ECO 에 ITEM 대상도 있음 | 표시 안 함 | ITEM 대상은 다른 유형 대상이 없을 때만 |
| 정상 | 같은 LOT 두 번 `mark` | 같은 ECO id, 행 그대로(멱등) | — |
| 정상 | `ecosOfLot`: 표시된 LOT / 표시 밖(N 초과) LOT / 이전 리비전 LOT | REVISION 한 줄 / REVISION 한 줄 / 빈 목록 | (가)+(나) 한 줄 |
| 정상 | 품목만 대상 + N 비움 | 유효일 전 발생 LOT 제외, 정책 기본 1 → 1개, Via MARK | 회사 정책 0 → 표시 없음 |
| 정상 | 4M 설비 구간(오늘~+2) — **지시 없는 LOT 단위 재작업** LOT 3개 / 다른 설비 / 구간 밖 | 3개 모두 `4M_WINDOW` / 없음 / 없음 | woId null 지원 |
| 정상 | 임시 변경 + 공정 대상, 4M 과 동시에 걸린 LOT | 두 ECO 모두 행, 반환 = 나중에 적용한 임시 ECO | `change_id` 사본 칸이 하나라서 |
| D 동시성 | 첫 1 자리: A 표시 후 커밋 전 머묾, B 동시 표시 | A 표시, B 빈 값, 행 1개 | ECO 행 NO KEY UPDATE 후 셈 |
| D 순서 | 오프라인 재전송(발생은 더 이르나 늦게 도착) | 도착 순 → 표시 안 함 | 표시일 뿐(Q3) |
| **D 잠금 순서** | 다른 트랜잭션이 `prd_wo` 행 FOR UPDATE 를 쥔 동안 그 지시 LOT 표시(lock_timeout 2s) | 기다리지 않고 표시 | `plm_eco_lot` 에 MES FK 없음(13.2) |
| C 상태 | 승인됐지만 적용 전 ECO / 작성 중 아닌 ECO 의 변경 종류 수정(API·직접 SQL) / 승인 ECO 로 직접 표시 행 | 표시 없음 / 409·`ck_plm_eco_fixed` / `fk_plm_eco_lot_ref_comp` | — |
| C 추가만 | 표시 행 UPDATE·DELETE(앱 계정) / 트랜잭션 밖 `mark` | permission denied / `IllegalTransactionStateException` | REVOKE + 트리거, MANDATORY |
| E 값 | TEMP 끝 없음 / DESIGN 에 끝 / 끝 < 유효일 / N 0·101 / 종류 X / 직접 SQL TEMP 끝 없음 | `REQUIRED` / `MARK_UNTIL_KIND` / `BEFORE_EFFECTIVE` / `RANGE`·400 / `CODE` / `ck_plm_eco_mark_until` | — |
| E 대상 | EQUIP 코드 없음 / 없는 공정 / 같은 설비 두 번 / 설비+공정+품목 상신 / 직접 SQL EQUIP 에 설비 없음 | `REQUIRED` / `NOT_FOUND` / `DUPLICATE`(행 키) / 성공(리비전 대상 없는 상신) / CHECK 거부 | — |
| **B A사로 B사** | A사 범위로 B사 LOT 표시 / B사 LOT `ecosOfLot` / A사 LOT 에 B사 설비 id / B사 지시 `/lots` | 빈 값·B사 행 0 / 빈 목록 / 오류 없이 설비 무시 / 404 | — |
| **B 직접 SQL** | A사 범위: B사 ECO·B사 LOT·B사 설비를 가리키는 표시 행 / B사 comp_cd 행 / A사 대상에 B사 설비 id / B사만 가진 설비 코드로 대상 | 모두 `fk_plm_eco_lot_ref_comp` / 거부, B사 0행 / `fk_plm_eco_target_comp` / `NOT_FOUND` | 트리거가 잠금 없이 같은 회사 확인 |
| 화면 | 로컬 DB, ZT 데이터(`ZT-ECO-3B` 4M 설비 구간, LOT `ZT-PLM3B-L1~3`) 1920·390 | 변경 종류·표시 끝·설비 대상·"표시된 LOT" 3행·구간 안내, 가로 넘침 없음, 콘솔 오류 0. 새 지시에서 설계 변경 = 표시 끝 꺼짐, 임시 = 켜짐 | 390 에서 표시 표 7열이 좁다(대상 표와 같은 모양) — 화면 정리 때 |

## PLM 4단계 PPAP·PCN·일괄 대체 연동·D4 ECO 필수 정책 (2026-10-21, V069(임시 V9902), 브랜치 `claude/plm-work`, docs/39 14장)

`PlmStage4Test` 8건. 기존 BOM·라우팅 시험은 정책 N(기본)으로 그대로 통과. 전체 `./gradlew check` 1,189건 통과(건너뜀 2, 화면 수정 뒤 최종 실행).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| D4 정상 | 정책 Y: BOM·라우팅 화면 확정 / ECO 적용 | 409 `PLM_ECO_REQUIRED`(둘 다 DRAFT 그대로) / 둘 다 확정 | 허용 표시 = 트랜잭션 로컬 `app.plm_eco_release` |
| D4 직접 SQL | 정책 Y 에서 앱 계정으로 `status = 'RELEASED'` / ECO 적용 다음 트랜잭션에서 다시 | `ck_plm_eco_required` / 다시 막힘(표시가 새지 않음) | 트리거 `tg_plm_eco_required` |
| D4 기본 | 정책 N(기본) 직접 확정 + **B사가 Y** | A사 확정 성공 | 회사별 정책 |
| 일괄 대체 | ECO 유효일로 미리보기(FG·FG2 NEW_REV) → 실행 | 새 리비전 2개 = BOM 대상(변경 내용 "RM → RM3") → 상신·승인·적용 → 둘 다 ECO 번호로 확정 | — |
| 일괄 대체 롤백 | 결과 리비전 하나가 다른 열린 ECO 대상 | 400 `OPEN_ECO`(rowKey `b{id}`), FG2 새 리비전 없음·작성 중 행 그대로·대상 0 | 한 트랜잭션 |
| 일괄 대체 상태 | 오래된 rowVer / 승인된 ECO | 409 / 409 `PLM_ECO_STATE` | — |
| PPAP 정상 | 만들기(ECO 고객, 레벨 3 = 18요소 S) → 미완 제출 → 레벨 1·PSW DONE(이 ECO 첨부) → 제출 → 승인(PSW-1) | 400 `PLM_PPAP_INCOMPLETE` 18행(`e18` PSW_REQUIRED) → 제출 → **ECO 고객 승인 APPROVED·번호·날짜** → 적용 성공 | 칸은 ECO 에(10장) |
| PPAP 상태 | 두 번째 만들기 / 제출 뒤 저장(API·직접 SQL) / 승인 뒤 다시 작성·판정 번호 수정 / 작성 중에서 바로 승인(직접 SQL) | 409 / 409·`ck_plm_ppap_fixed` / 409·`ck_plm_ppap_fixed` / `ck_plm_ppap_transition` | — |
| PPAP 거절 | 거절(판정 전 판정 409) → 고객 승인 그대로 NA → 다시 작성 | DRAFT, 판정일 비움 | 재제출 |
| PPAP 값 | 고객 없음·판매처 아님·레벨 9 / 승인 번호 없음·제출일 앞 판정·미래 제출일 / 요소 19·코드 X / 다른 ECO 첨부 | `REQUIRED`·`NOT_SALES`·`RANGE` / `decisionRef`·`BEFORE_SUBMITTED`·400 / 400·칸 2개 / `e18` `fileId` | 첨부 = 같은 ECO·같은 회사·사용 중 |
| A 권한 | SAVE 만 있는 사용자 판정 | 403 (다시 작성은 SAVE 로 됨) | 판정 = APPROVE |
| PCN | 통지 2건(기한 지난 것 overdue) / 같은 고객 열린 통지 / 기한 < 통지일 / 미래 통지일 | `PCN2610-0001`·overdue true·false / `PCN_OPEN` / `BEFORE_NOTIFIED` / `FUTURE` | 채번 PLM_PCN |
| PCN 응답 | 통지일 앞 / 승인 / 두 번째 응답 / 없는 상태 / 응답 뒤 같은 고객 새 통지 / 직접 SQL 응답 변경·통지일 변경 / 취소된 ECO 에 통지 | `BEFORE_NOTIFIED` / 성공 / 409 `PLM_PCN_STATE` / 400 / 성공 / `ck_plm_pcn_transition` ×2 / 409 | — |
| **B A사로 B사** | B사 ECO 의 PPAP·PCN 조회·만들기, B사 PPAP 저장·제출·판정, B사 PCN 응답, B사 ECO 일괄 대체 미리보기·실행 | 모두 404 | RLS |
| **B 직접 SQL** | A사 범위: B사 고객 PCN·B사 ECO PCN·B사 고객 PPAP·B사 PPAP 요소 / B사 comp_cd PCN / A사 PPAP 에 B사 첨부(API) | `fk_plm_pcn_ref_comp`×2·`fk_plm_ppap_ref_comp`·`fk_plm_ppap_elem_comp` / 거부, B사 1건 그대로 / `fileId` 칸 오류 | 트리거 같은 회사 확인 |
| 화면 | 로컬 DB(재생성), ZT 데이터(`ZT-ECO-4A`, `ZT-P4-FG/RM/RM3`, 고객 `ZT-P4-C1`) 1920·390 | 일괄 대체 미리보기 1행(A0 → A1 NEW_REV) → 실행 → 대상 BOM A1 DRAFT / PPAP 만들기 18행 → 미완 제출 시 행 표시 / 통지 기록 `PCN2610-0001`. 가로 넘침 없음, 콘솔 오류 0(제출 거부 400 응답 제외) | **발견 → 수정**: 390 에서 PPAP·통지 표 잘림 → `plm.css` 상자 안 가로 스크롤 / 미완 제출 알림 줄이 18개 문구를 이어 붙임 → 요약만(행에 표시) |

## PLM 3c 연결 확인 — MES 경로 LOT 의 표시·계보 사본·EcoTrace (2026-10-21, 마이그레이션 없음, 브랜치 `claude/plm-work`)

MES 3c(main 병합, `lot_genealogy.change_id` FK + 새 LOT 세 곳 `mark`) 뒤 PLM 쪽 확인. `PlmEcoLotTest` 1건 추가(10건).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| MES 가 부르는 순서 그대로: `LotService.createProduced` → `LotService.mark`(Instant, UTC 오프셋) → `consume(changeId)`, 첫 N=1, LOT 2개 | 첫 LOT: `plm_eco_lot.eco_id` = `lot_genealogy.change_id` = ECO / 둘째: 표시·사본 없음 | 원본·사본 일치 |
| 두 LOT 의 `ecosOfLot` | 둘 다 같은 ECO 한 줄 `Via.REVISION`(표시 여부와 무관) | 출하 게이트·감사는 `EcoTrace` 하나로 |
| ECO 화면 "표시된 LOT" | MES LOT id·지시 번호 1행 | — |

## PLM 2단계 도면 리비전·첨부 고정(D7) (2026-10-21, V082, docs/39 15장)

`PlmDrawingTest` 6건. 전체 `./gradlew check` 1,394건 통과(건너뜀 2) → main 병합(V082) 때 1,411건 통과. QMS 표는 읽기·FK·행 잠금만, 상태 변경은 `DocService.releaseFromChange`.

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 도면 A0 배포 + A1 작성 중 → ECO 대상 DOC(품목 + 문서 번호 소문자) → 상신·승인·적용 | 대상 `DOC`·문서 번호·A1·DRAFT 표시 → A1 RELEASED, A0 OBSOLETE, 도면 첨부·ECO 첨부 `locked_yn = Y`, 직접 SQL 사용 중지 `ck_sys_file_locked` | 적용 = BOM·라우팅 뒤 도면 배포, ECO 첨부 잠금 표시 |
| D7 | 상신 뒤 ECO 첨부·대상 도면 첨부 올리기 / 반려 뒤 / 취소 뒤 | 409 `FILE_LOCKED` ×2 / 다시 열림 / 잠김 | `PlmFileLock` |
| F 결재 뒤 변경 | 승인 뒤 QMS 화면에서 리비전 내용 수정 → 적용 | 409 `PLM_TARGET_CHANGED`(rowKey `t{대상}`), 리비전 DRAFT 그대로 | `base_row_ver` |
| G 롤백 | 승인 뒤 같은 리비전을 문서 결재에 올림(IN_APPROVAL) → 적용 | 409, 리비전 결재 중 그대로, ECO 승인됨 그대로 | `releaseFromChange` 가 작성 중만 |
| PPAP 증빙 | `PPAP-{id}` 에 올리기 → 요소 18 첨부 → 제출 뒤 올리기 → 승인 / 없는 패키지·잘못된 id | 성공 → 409 → 증빙 `locked_yn = Y` / 409 ×2 | ECO 첨부와 분리 |
| E 값 | 도면 아닌 문서 / 없는 번호 / 번호 비움 / 작성 중 리비전 없음 / 같은 리비전 두 지시 / 직접 SQL 도면 아님·두 지시 | `NOT_DRAWING` / `NOT_FOUND` / `REQUIRED` / `NO_DRAFT_REV` / `OPEN_ECO` / `ck_plm_eco_target_drawing`·`uq_plm_eco_target_open` | — |
| **B A사로 B사** | A사 지시에 B사 문서 번호 / 직접 SQL B사 문서 리비전 / A사 사용자로 B사 ECO·PPAP·문서 리비전 첨부 올리기 / A사 PPAP 요소에 B사 파일 | `NOT_FOUND` / `fk_plm_eco_target_comp` / 모두 4xx, B사 파일 수 그대로 / `fk_plm_ppap_elem_comp` | — |
| 화면 | 로컬 DB(재생성), ZT(`ZT-ECO-2A`, 도면 `ZT-DRW-1`, 품목 `ZT-P2-FG`, 결재자 `zt-approver`) 1920·390 | 대상 추가(품목 + 도면 번호) → "도면 · ZT-DRW-1 A0 · DRAFT" → 상신 뒤 첨부 올리기 칸 숨김, 가로 넘침 없음, 콘솔 오류 0 | — |

## PLM ↔ QMS 2부 계약 — EcoLookup·EcoTrace.markedLots·EcoSubmitRule (2026-10-21, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 16장)

`PlmQmsContractTest` 3건(시험용 `EcoSubmitRule` 빈을 `@TestConfiguration` 으로 등록 — 이 클래스 컨텍스트에만) + `PlmLinkContractTest` NONE 단언. 전체 `./gradlew check` 1,434건 통과(건너뜀 2).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 4M ECO(설비·공정·품목 대상) `EcoLookup.eco` | 머리·`markUntil`·유형별 id(품목 2·설비 1·공정 1·도면 0), 없는 ECO 빈 값 | BOM·라우팅은 계약 밖 |
| `markedLots` | 도착 순(seq 1·2), 이유·LOT 번호, 없는 ECO 빈 목록 | — |
| **A사로 B사** | B사 ECO 를 A사 범위로 `eco`·`markedLots` | 빈 값·빈 목록(B사 범위에서는 있음) | RLS |
| 상신 규칙 거부 | 400, 행 칸 오류 + 행 없는 칸 오류 둘 다, 규칙이 본 상태 DRAFT(상태 변경 전), ECO DRAFT·`base_row_ver` 없음 | 같은 트랜잭션 |
| 규칙 통과 | 같은 rowVer 로 다시 상신 → PENDING | — |
| PLM 없는 설치본 | `EcoLookup.of` = NONE, `markedLots` 빈 목록 | — |

## PLM DOC 대상 확장·QMS Q5 계약 — 문서 유형 4종·품목 선택·EcoLookup.byNo/ecos·EcoQualityView (2026-10-21, V088(임시 V9904), docs/39 16.1·16.2)

`PlmDrawingTest` 1건 추가(7건)·기존 값 시험 갱신, `PlmQmsContractTest`·`PlmLinkContractTest` 단언 추가. 전체 `./gradlew check` 1,449건 통과(건너뜀 2).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 작업표준서(품목 없이) + 관리계획서(품목 FG, 문서 적용 대상은 FG2) 대상 → 상신·승인·적용 | 첫 행 품목 없음·경고 없음, 둘째 행 `docTargetWarn` true(막지 않음), 상신 고정·적용 배포 성공, `quality` 없음(QMS 구현 전) | `IS NOT DISTINCT FROM` |
| E 값 | 유형 OTHER 문서 / 없는 품목 코드 / 직접 SQL 유형 OTHER / 직접 SQL ITEM 대상 품목 없음 | `DOC_TYPE` / `itemCd` 칸 오류 / `ck_plm_eco_target_doc_type` / `ck_plm_eco_target_item_req` | 비우기만 허용 |
| 계약 | `byNo(" eco-4m ")`·없는 번호·null / `ecos([eco, 없는 id, eco, null])`·빈 목록·501개 | 찾음·빈 값·빈 값 / [eco] 한 번·빈 목록·IllegalArgumentException | — |
| **B A사로 B사** | B사 ECO 를 A사 범위로 `byNo`·`ecos` | 빈 값·빈 목록 | RLS |
| PLM·QMS 없는 설치본 | `EcoLookup.NONE.byNo/ecos`, `EcoQualityView.of` | 빈 값·NONE | default 메서드 |
| 화면 | 로컬 DB(재생성), ZT(`ZT-ECO-Q5`, 작업표준서 `ZT-WI-1`, 관리계획서 `ZT-CP-1` 적용 대상 `ZT-Q5-FG2`, 품목 `ZT-Q5-FG`) 1920 | 품목 칸 비우면 "문서만" 선택지 → 품목 "-" 행, 품목 FG + CP → "문서 적용 대상에 이 품목이 없음" 경고, 저장 성공, 품질 영역 숨김(QMS 구현 전), 콘솔 오류 0 | — |

## PLM ↔ QMS Q7 초품 링크 (2026-10-21, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 16.2)

QMS Q7(V104) 병합 뒤 ECO 상세 "품질(4M)" 영역 확인·링크 추가. 전체 `./gradlew check` 1,588건 통과(건너뜀 2).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 로컬(DB 재생성) ZT 적용된 설계 변경 ECO `ZT-ECO-Q7` + 첫 LOT 표시 `ZT-Q7-L1`(4M 세부 없음) | 영역 표시 "초품 OK 0 · NG 0 · 미판정 1", "초품 판정에서 열기" → `/page/qms-setup?ecoId=` 200, 초품 확인 화면에 그 LOT | 첫 LOT 있을 때만 링크 |
| **발견 → 수정** | 설계 변경 ECO 에도 "4M 세부가 아직 없습니다" 문구 | DESIGN 이면 4M 문구 빼고 초품 수만 |

## PLM 계약 EcoTrace.firstLotsOfItem (QMS Q9 R-1) + 시험 current_date 정리 (2026-10-22, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 16.3)

`PlmQmsContractTest` 1건 추가(4건), `PlmLinkContractTest` NONE 단언. 전체 `./gradlew check` 1,643건 통과(건너뜀 2). PLM 시험의 `current_date`(UTC) 6곳을 회사 날짜로(`PlmEcoTest`·`PlmEcoLotTest`).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| ECO 2개(적용), FG LOT FIRST_LOT 3·4M_WINDOW 1, FG2 LOT FIRST_LOT 1 → `firstLotsOfItem(FG, 10)` | FL-C·FL-B·FL-A(최근부터), ECO 번호·seq, 4M_WINDOW·다른 품목 없음 | — |
| limit 2 / 없는 품목 / limit 0·501 | 앞 2개 / 빈 목록 / IllegalArgumentException | 상한 500 |
| **A사로 B사** | B사 범위에서 A사 품목 id | 빈 목록 | RLS |
| PLM 없는 설치본 | `EcoTrace.NONE.firstLotsOfItem` | 빈 목록 | default 메서드 |

## PLM 3d — 출하 게이트 계약 EcoTrace.shipChecks (2026-10-22, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 17장)

`PlmEcoLotTest` 1건 추가(11건), `PlmLinkContractTest` NONE 단언. 전체 `./gradlew check` 1,648건 통과(건너뜀 2, 실패 0 — main 24a8438 위), main cd3b2f7(#167) 병합 뒤 1,648건 통과(건너뜀 2, 실패 0).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 설계 ECO(BOM·라우팅 A1, 첫 1) 뒤 둘째 LOT(표시 없음) · 4M 설비 ECO 표시 LOT(지시 없음) · 이전 리비전 LOT, PPAP 없음 | 빈 목록 | — |
| 두 ECO 에 PPAP DRAFT | 두 LOT `PPAP_OPEN`·block, 이전 리비전 LOT 없음 | 리비전 경로 + 표시(G1) |
| 설계 PPAP 승인 / 4M PPAP 임시 승인 | 설계 LOT 빠짐, 4M LOT `PPAP_INTERIM`·block=false | G2 |
| 4M PPAP 재제출 → REJECTED | 다시 `PPAP_OPEN`·block | — |
| 값 — null·중복·없는 id 섞음 / 빈 목록 / null 하나 / 501개 | 빠짐 / 빈 / 빈 / IllegalArgumentException | 상한 MAX_IDS |
| **A사로 B사** — B사 범위에서 A사 LOT id | 빈 목록 | RLS |
| PLM 없는 설치본 — `EcoTrace.NONE.shipChecks` | 빈 목록 | default 메서드 |

## PLM 4단계 남은 것 — 적용 조건 재고 소진 (2026-10-22, V119(임시 V9905), 브랜치 `claude/plm-work`, docs/39 18장)

`PlmStockOutTest` 5건(신규). 재고는 WMS 조정 API 로 넣고 뺐다(PLM 은 계약 `StockQuery` 만). 전체 `./gradlew check` 1,667건 통과(건너뜀 2, 실패 0 — main #176 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 소진 품목 RM 가용 5 → 상세 `stockAvailable` 5, 적용 | 409 `PLM_STOCK_REMAIN`(칸 `stockItemCd` "가용 재고 5"), ECO APPROVED·BOM A1 DRAFT 그대로 | 롤백 |
| 정상 | 조정 -5 → 상세 0 → 적용 | 200, BOM A1 RELEASED·`valid_from` 오늘, 적용 뒤 상세에 재고 칸 없음 | — |
| 정상 | 유효일이 어제가 된 STOCK_OUT / DATE | 적용(valid_from 오늘, ECO 유효일 그대로) / `PLM_EFFECTIVE_PAST` | R5 |
| E 값 | 조건 NOPE / STOCK_OUT 품목·공장 없음 / 없는 품목·공장 / DATE 에 소진 품목 / 비움 | CODE / REQUIRED×2 / NOT_FOUND×2 / STOCK_COND / DATE | — |
| E 직접 SQL | 품목 없이 STOCK_OUT | `ck_plm_eco_stock_out` | — |
| C 상태 | 상신 뒤 조건·소진 품목 직접 SQL 수정 | `ck_plm_eco_fixed` | 별도 트리거 |
| **B A사로 B사** | B사에서 A사 품목 코드 / B사 ECO 에 A사 품목 id(직접 SQL) / A사 재고 5 일 때 B사 RM 상세 / A사 사용자 → B사 ECO 상세 | NOT_FOUND / `fk_plm_eco_stock_item_comp` / 0 / 404 | RLS + 트리거 |
| 한계 | WMS 없는 설치본(NO_WMS) | 자동 시험 없음(같은 컨텍스트에서 빈 제거 불가) | 코드 경로 2곳 |
| 브라우저(local, DB 새로 만듦, DEMO + ZT 품목 `ZT-RM-OLD`, 1366·390) | 적용 조건 유효일 → 소진 품목·공장 잠김 / 재고 소진 → 열림 / 없는 품목 `ZT-NOPE` 저장 → 칸 오류 / `ZT-RM-OLD`·P1 저장 → "남은 가용 재고: ZT-RM-OLD · P1 · 가용 0", 가로 넘침 0 | 기대대로, 콘솔 오류 = 의도한 400 + favicon 404 | — |

같은 PR: 계약 `EcoLookup.ppap(ecoId)`(PMS PM7, docs/39 16.4) — `PlmQmsContractTest` 첫 시험에 단언 추가, `PlmLinkContractTest` NONE.

| 해 본 것 | 결과 |
|---|---|
| PPAP 없음 / DRAFT / SUBMITTED → INTERIM | 빈 값 / (id, DRAFT, null) / INTERIM·판정일 오늘 |
| **A사로 B사** — B사 범위에서 A사 ECO id | 빈 값 |
| 없는 ECO / PLM 없음(NONE) | 빈 값 / 빈 값 |

## PLM 전자서명 연결 — ECO 결재 승인·PPAP 판정 (2026-10-22, V126(임시 V9906), 브랜치 `claude/plm-work`, docs/39 19장)

`PlmEsignTest` 7건(신규). 토큰은 플랫폼 API(`/api/platform/esign/token`, MockHttpSession)로 받고 PLM API 에 실었다. 전체 `./gradlew check` 1,702건 통과(건너뜀 2, 실패 0), main 2716ec3 병합 뒤 1,711건 통과(그 사이 한 번은 시험 JVM 힙 부족 `Java heap space` 로 1,453건에서 중단 — 다시 돌려 통과, 시험 힙 기본값 여유가 적다는 관찰). 첫 실행에서 문구 키 검사(`I18nKeyGuardTest`)가 서명 의미·방식 키를 글자로 이어 붙인 곳을 잡아 명시 키로 고침.

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 합의(qa1) REVIEW 서명 승인 → 결재(mgr1) APPROVE 서명 승인 | ECO APPROVED, 상세 서명 2건(REVIEW·APPROVE, 이름 "qa1 사용자", PWD, 바뀜 없음), 결재 의견 2건 | — |
| 정상 | 합의 서명 뒤 결재자 반려 → 작성 중 → 제목 수정 | 서명 "바뀜" false → true | 정규 글자 v1 |
| 정상 | 정책 N, 토큰 없이 PLM 승인 | APPROVED, 서명 행 0 | S1 |
| 정상 | PPAP 제출 → 정책 Y → AUTHOR 토큰으로 임시 승인 판정 → 재제출 | INTERIM + 서명 1건(바뀜 없음) → 작성 중 뒤 바뀜 | S4 |
| 정책 Y | 토큰 없이 PLM 승인 / 공통 결재함 중간 합의 / 공통 결재함 마지막 승인 / 서명 승인 | 403 `PLM_ESIGN_REQUIRED` / 통과(한계 S2) / 403 + 롤백(ECO PENDING·결재 줄 CURRENT) / APPROVED | 핸들러 확인 |
| E·F 토큰 | 다른 ECO / 의미 REVIEW 로 결재 줄 / 작성자 토큰을 다른 세션·같은 세션에서 결재자가 / 재사용 | 모두 403 `ESIGN_TOKEN`, ECO 결재 중, 서명 행 0 | 플랫폼 consume |
| 롤백 | 내 차례 아닌 사람이 자기 토큰으로 승인 | 4xx, 서명 행 0 | 같은 트랜잭션 |
| C 상태 | 이미 승인된 ECO 에 승인 | 409 `PLM_ECO_STATE` | — |
| PPAP | 정책 Y 토큰 없음 / APPROVE 의미 토큰 | 403 / 403, 둘 다 패키지 SUBMITTED 그대로 | — |
| **B A사로 B사** | B사 사용자가 A사 ECO id 로 토큰 → 승인 / 상세 | 404, 두 회사 모두 서명 행 0 / 404 | RLS |
| 브라우저(local, DB 새로 만듦, DEMO + ZT 작성자 `zt-eng` 이 상신 → admin 승인, 1366·390) | 승인 → 서명 대화상자 "전자서명 — 승인"(대상 ECO 번호·제목) / 틀린 비밀번호 → "남은 횟수 4번" / 맞는 비밀번호 → 상태 "승인됨" + "서명 · 개발 관리자 · 승인 · 비밀번호 · 시각", 가로 넘침 0 / (발견) 유효일 조건 ECO 에도 빈 "남은 가용 재고" 칸 → 재고 소진일 때만 보이게 | 기대대로, 콘솔 = 의도한 400(틀린 비밀번호) | PPAP 판정 대화상자는 같은 도우미라 자동 시험만 |

같은 PR: QA 결함 QS-2(낮음, 코드 분석) — `eco-extras.js` PPAP 증빙 두 번째 조회가 늦게 오면 다른 ECO 화면에 섞일 수 있음 → 응답 때 지금 ECO·패키지 id 가 같은지 보고 아니면 버림. 브라우저 재현은 하지 않음(경합 창이 좁음 — QA 와 같음), JS 타입 검사·단위 시험 통과.

## PLM 20장 ① — 화면 영문 코드 문구화 + docs/39 진행표 정리 (2026-10-22, 마이그레이션 없음, 브랜치 `claude/plm-work`)

화면 JS 만(`eco-mgt.js`) — 새 문구 키 없음(공통코드 `BOM_STATUS`·`DOC_REV_STATUS`, 플랫폼 결재 화면 문구 `js.sysApproval.st.*` 재사용, 키를 이어 붙이지 않음). 전체 `./gradlew check` 1,766건 통과(건너뜀 2, 실패 0 — main #213 Java 21 병합 뒤, JDK 21).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 브라우저(local, DB 새로 만듦, DEMO + ZT BOM 리비전 `ZT1`·ZT 작성자 `zt-eng` 이 상신 → admin 이 열람, 1366·390) — 영향 대상 BOM 행 / 결재 줄 | "작성 중"(전에는 `DRAFT`) / "지금 차례"(전에는 `CURRENT`), 가로 넘침 0, 콘솔 오류 0 | — |
| 문서 리비전(DOC) 대상 상태 | `DOC_REV_STATUS` 문구(작성 중·결재 중·배포·폐기) — 코드 경로만, 브라우저는 BOM 으로 확인 | — |

## PLM 20장 ② — 홈 카드 2장(적용 대기 설계 변경·고객 응답 대기) (2026-10-22, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 20.6)

`PlmHomeCardTest` 5건(신규). 전체 `./gradlew check` 1,784건 통과(건너뜀 2, 실패 0 — main WMS B6a 병합 뒤, JDK 21).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 적용 대기 3(유효일 지남·고객 승인 대기·재고 소진 각 1) + 작성 중·적용됨·취소 각 1 | 숫자 3, 보조 숫자 1·1·1, 항목 유효일 순(지남 WARN), 카드 WARN, 링크 `status=APPROVED` | — |
| 숫자 = 목록 | 링크 조건 그대로 ECO 목록(`/api/plm/ecos?status=APPROVED`) | 3건 = 카드 숫자 | 같은 조건 |
| 정상 | PPAP 판정 대기 1·작성 중 1, PCN 기한 지남 1·남음 1, 취소된 ECO 의 PPAP 제출·PCN 기한 지남 | 숫자 2(보조 1·1), 항목 PCN 먼저 → PPAP(ECO 번호), 카드 WARN, 숫자 링크 없음 | R8 끝난 것 제외 |
| 빈 상태 | 데이터 없음 | 두 카드 0, 회색 | — |
| A 권한 | 홈 권한만 있는 사용자 | PLM 카드 안 보임 | R1 |
| **B A사로 B사** | B사에 같은 데이터, A사 홈 | A사 두 카드 0·항목 없음 | RLS |
| 브라우저(local, DEMO + ZT: 앞 절 ECO 를 서명 승인 → 적용 대기 1, ZT PCN `ZT-PCN-1` 기한 어제, 1366·390) | 홈 | "적용 대기 설계 변경 1건"(정상 톤, 항목 ECO 번호·유효일, 숫자·항목 링크 버튼) / "고객 응답 대기 1건"(확인 필요 톤, 통지 응답 기한 지남 1, 숫자 링크 없음·항목 링크 있음), 가로 넘침 0 | 콘솔 = favicon 404 |

## PLM 20장 ③ — 영향 대상 후보 보기 (2026-10-22, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 20.7)

`PlmImpactTest` 6건(신규). master `BomTreeService.whereUsed` 호출만(변경 없음). 전체 `./gradlew check` 1,795건 통과(건너뜀 2, 실패 0 — main #222 병합 뒤, JDK 21).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 자품목 X 를 쓰는 1단계 상위: P1(작성 중 → 이 ECO 대상)·P2(작성 중 없음)·P3(작성 중 → 다른 열린 ECO)·SA(작성 중 있음)·P6 | 상태 IN_THIS·NO_DRAFT·OPEN_ECO(ECO-OTHER)·NO_DRAFT·ADDABLE, SA 쓰이는 리비전 A0·작성 중 A1·draftBomId, 기준일 = 유효일 | — |
| **유효일 경계** | P4 확정 리비전이 유효일 다음 날부터 / P5 가 유효일 전날까지(valid_to = 유효일) / P6 이 유효일부터 | P4·P5 안 보임, P6 보임 | 역전개 `valid_from ≤ 날짜 < valid_to` 그대로 |
| 정상 | 전체 레벨(`scope=ALL`) — FG 가 SA 를 씀 | FG 레벨 2 `ITEM`, ITEM 대상 넣은 뒤 `IN_THIS`, 레벨 순 | I2 |
| D 동시 | 후보 ADDABLE 을 본 뒤 다른 ECO 가 SA 작성 중 리비전을 먼저 대상으로 → 이 ECO 대상 저장 | 400 `OPEN_ECO`, 다시 보면 `OPEN_ECO` | 기존 대상 저장 검사 |
| E 값 | 품목 없음 / 없는 품목 / scope=UP / 쓰는 곳 없는 품목 / 행 상한 정책 3 | REQUIRED / NOT_FOUND / scope CODE / 빈 목록 / 400 `BOM_TOO_MANY_ROWS` | BOM 정책 그대로 |
| A 권한 | BOM 역전개 권한만 있는 사용자 / 설계 변경 조회만 있는 사용자 | 403 / 200 | I4 |
| **B A사로 B사** | B사 사용자가 A사 ECO id / A사에서 B사 품목 코드 | 404 / NOT_FOUND | RLS |
| 브라우저(local, DEMO + ZT 지시, 1366·390) | 자재 RM-SPFC590-1.6 → 1단계 64311·64312 "작성 중 리비전 없음", [위로 더 보기] → 64310 레벨 2 "영향 품목" → [품목 대상으로] → 대상 저장 "저장했습니다" → 다시 보면 "이 지시에 있음" / 64311 → 64310 ZT1 "다른 지시 ECO2610-0001 에 묶임"(버튼 없음), 가로 넘침 0 | 기대대로, 콘솔 = favicon 404 | — |

## PLM UI 단계 3 PR 1 — 대화상자·상태 배지·위험 동작 자리 (2026-10-22, 서버 변경 없음, 브랜치 `claude/plm-work`, docs/39 21.1)

화면 JS·템플릿만. 전체 `./gradlew check` 1,868건 통과(건너뜀 2, 실패 0 — main 639717b 위, JDK 21). 가드 시험(문구 키·CSP·디자인 토큰) 통과, JS 타입 검사·단위 시험 51 통과.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 브라우저 전(main 639717b, stash) / 후, `plm-eco`·`plm-ecr` 목록·상세 × 1366·390 | 전: 상태 글자만(배지 0), 빨강 채움 취소 버튼이 저장 옆 / 후: 지시 화면 배지 5(목록·상태·결재 줄 등), 요청 화면 배지 2(ZT 요청 1건), 지시 취소 = 외곽선 위험 톤·별도 줄, 가로 넘침 0 | 스크린샷 전후 8쌍 |
| 미저장 이동: 제목을 바꾼 채 다른 행 → "저장하지 않은 입력" 대화상자 → 취소 | 머묾, 입력 그대로 | `plm.leaveOk()` |
| 지시 취소 → 사유 대화상자 → 빈 사유로 확인 / Esc | 닫히지 않음 / 아무 일 없이 닫힘 | 사유 필수 |
| 네이티브 confirm/prompt | 0회(브라우저 dialog 이벤트 없음) | — |
| 콘솔 | favicon 404 만 | — |

## PLM UI 단계 3 PR 2 — 지시·요청 주 목록 → hb.grid (2026-10-22, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 21.2)

`PlmGridTest` 3건(신규). 전체 `./gradlew check` 1,876건 통과(실패 0, 건너뜀 2, JDK 21, main a0024ac 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 쪽 경계 | 지시 5건 제목 오름 2개씩 1~4쪽 | 가·나 / 다·라 / 마 / 빈 쪽, total 5 | — |
| 목록과 같은 순서·조건 | 기본 정렬 vs 목록 API / 상태 CANCELLED / 검색어 / 없는 품목 | 같은 순서 / 1건 ECO-3 / 1건 / 0건 | SQL 조각 공유 |
| 여러 정렬 | 상태 → 번호 | ECO-3(취소)이 맨 앞 … | — |
| E 값 | 허용 밖(targetCnt·SQL 조각 문자열)·정렬 4개·쪽 크기 100000·쪽 0·상태 NOPE / 요청 requesterNm 정렬 | 모두 400 | 허용 enum |
| 열 정의 | `/columns` | 지시 ecoNo·title·status·targetCnt, 요청 ecrNo·status·ecoNo | — |
| A 권한 | 요청 화면 권한만 있는 사용자 → 지시 grid·columns / 요청 grid | 403·403 / 200(5건) | `@ProgAuth(SEARCH)` |
| **B A사로 B사** | B사 사용자 지시·요청 grid | total 0, 행 0 | RLS |
| 브라우저 전(PR 1 상태)·후, 두 화면 × 1366·390 | 목록 표 → 그리드(쪽·건수 "총 4건"), 상태 배지 4·1, 행 클릭 → 상세 열림, 제목 머리 클릭 → 정렬, 제목을 바꾼 채 다른 행 → "저장하지 않은 입력" → 취소하면 머묾, 가로 넘침 0 | 기대대로, 콘솔 = favicon 404 | — |
| (발견) 1366 에서 지시 그리드 상태 칸이 잘림(열 합 720 > 목록 칸 ≈650) | 열 너비 조정(번호 120·제목 180·담당 90·대상 50·상태 80), 요청도 640 안으로 | 상태 칸 보임 | — |
| 한계 | 로컬에 취소·적용된 지시가 없어 흐린 행은 브라우저에서 못 봄 | rowClass 규칙은 코드·상태 값으로만 | — |

## PLM 결재 줄 단위 전자서명 — beforeAct 연결 (2026-10-23, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 22장)

`PlmEsignTest` 7 → 8(1건 바뀜, 1건 추가). PLM 시험 71 통과. 전체 `./gradlew check` 2,102건 통과(실패 0, 건너뜀 2, JDK 21, main f45253e 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | PLM 화면 경로 합의 REVIEW → 결재 APPROVE 서명 | 승인, 서명 2건 | 서명 검사 = `EcoApprovalDocType.beforeAct` |
| 정상 | 공통 결재함 API 에 REVIEW 토큰(같은 세션)으로 합의 | 200, 다음 줄로 | — |
| 정책 Y·토큰 없음 | PLM API 합의 / **공통 결재함 중간 합의 줄**(전에는 통과 = 한계) / 공통 결재함 마지막 승인 | 403 `PLM_ESIGN_REQUIRED` ×3, 합의 줄 CURRENT·이력 0, ECO 결재 중 | 줄마다 확장점 |
| E 의미 | 결재 줄에 REVIEW 토큰(공통 결재함) | 403 `ESIGN_TOKEN` | lineType → 의미 |
| 정책 Y 반려 | 공통 결재함 반려(서명 없음) | 200, ECO 작성 중, 서명 행 0 | 19.3 S3 |
| E·F 토큰 | 다른 ECO·다른 의미·다른 사람·다른 세션·재사용·차례 아님 | 403 / 차례 아님은 엔진이 확장점 전에 거부, 서명 행 0 | 그대로 |
| **B A사로 B사** | B사 사용자가 A사 ECO 로 승인 | 404, 서명 행 0 | 그대로 || 결재함 안내(esignFor, #355 위) | 상세 `esignApprove`·`esignReject`: 정책 N / 정책 Y 합의 줄 / 반려 / 결재 줄 / 끝난 결재 | 없음 / REVIEW·plm_eco·ECO id·"번호 제목" / 없음 / APPROVE / 없음, 안내대로 서명 → 결재함 API 로 승인까지 | `EcoApprovalDocType.esignFor` (PlmEsignTest 9) |

esignFor 포함 전체 `./gradlew check` 2,128건 통과(실패 0, 건너뜀 3, JDK 21, main bf2c5f5 병합 뒤).

## PLM PCN 메일 발송 — 고객 담당자 (2026-10-23, V156·임시 V9907, 브랜치 `claude/plm-work`, docs/39 23장)

`PlmPcnMailTest` 7 + `PlmPcnMailOffTest` 1(신규). 전체 `./gradlew check` 2,216건 통과(실패 0, 건너뜀 2, JDK 21, main 1127f30 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 후보(대표·일반·메일 없음·사용 안 함·다른 고객) → 2명 발송 | 후보 = 사용 중·메일 있는 이 고객 담당자 2명(대표 먼저), 아웃박스 MAIL 2·이력 2, 본문에 이름·통지 번호·지시 제목·비고 줄바꿈, **사내 주소(base-url·/page/) 없음** | — |
| 정상 | 담당자 메일을 바꾼 뒤 목록 / 다시 보내기 | 이력은 보낸 때 주소 그대로 / 이력 한 줄 더 | 주소 스냅샷 |
| H 중복 | 같은 멱등 키 두 번 / 키 없음 | 409 `DUPLICATE_REQUEST`(1통만) / 400 | 키 필수 |
| E 받는 사람 | 메일 없음·사용 안 함·다른 고객·섞음·없는 id / 빈 목록 / 같은 id 두 번 / 11명 | 400 `NOT_PCN_CONTACT` / `REQUIRED` / `DUPLICATE` / `TOO_MANY_ITEMS`, **한 통도 안 나감** | 통째 거부 |
| 상태·권한 | 응답 끝난 통지 / 조회 권한만 | 409 `PLM_PCN_STATE` / 후보·보내기 403 | SAVE |
| E 주입 | 지시 제목에 RLO(U+202E)·`\r\nBcc:` | 제목에 제어·양방향 문자 없음 | 한 줄 정리 |
| E 이름 | 담당자 이름 `가"대표" <a>, b; c\` | 표시 이름 `가대표 a b c` 로 발송(플랫폼 400 없음) | 금지 글자 제거 |
| 직접 SQL | 이력 UPDATE·DELETE / 다른 고객 담당자로 INSERT | 모두 거부 | 원장 트리거·담당자 트리거 |
| **B A사로 B사** | B사 사용자로 A사 통지 후보·보내기 | 404, 이력·아웃박스 0 | RLS |
| 꺼짐(기본 설치) | 목록 `mailEnabled` / 후보 / 보내기 | false / enabled false·빈 후보 / 409 `MAIL_DISABLED` | E8 |
| 브라우저 전(main)·후, 1366·390 (메일 켜고 SMTP 없는 로컬) | 전: [메일 보내기] 없음 / 후: 버튼 → 표 아래 펼침(대표 체크·제목·본문 줄바꿈 미리보기) → 아무도 안 고르면 안내(대화상자 안 뜸) → 확인 대화상자 취소 = 아무 일 없음 → 보내기 → "메일 1통을 보냈습니다" + 이력 줄, 가로 넘침 0 | 기대대로, 콘솔 = favicon 404. 아웃박스는 SMTP 없음으로 실패(로컬 예상) | — |
| (발견) main 병합 뒤 가드 — 새 회사별 표는 보존 클래스 등록·변경 감사 트리거 필수(`RetentionTest`) | V9907 에 `hb_audit`·`sys_retention_table`(MASTER) 추가 | 통과 | 예외 목록(공유 시험)은 건드리지 않음 |
| (발견) 첫 화면에서 펼침을 PCN 표 안 행으로 넣었더니 390 에서 표 가로 스크롤에 갇히고 체크 줄이 세로로 깨짐 | 펼침을 표 아래로, 체크 줄 `hb-check-list`/`hb-check`, 미리보기는 줄마다 `<br>` | 1366·390 정상 | 공통 CSS 변경 없음 |

## PLM 읽기 계약 EcoLookup.releasedFor·approvedFor (2026-10-23, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 24장)

`PlmEcoForItemTest` 3(신규) + `PlmLinkContractTest` NONE 2줄. 전체 `./gradlew check` 2,258건 통과(실패 0, 건너뜀 2, JDK 21, main c0fa4cb 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 품목 X: 적용(그날 0시)·승인만(공정 대상의 품목 X)·전날 23:59:59 적용·승인 뒤 취소·다른 품목·작성 중·UTC 9/10 15:30(서울 9/11 00:30) 적용 | releasedFor = E1·E7(적용 시각 순), approvedFor = E2·E7(승인 시각 순) | 상태·기준 시각 |
| 날짜 칸 | E1 approvedOn·releasedOn / E7 releasedOn / 승인 전 ECO | 9/9·9/10 / **9/11**(회사 시간대) / null | 시각 → 회사 날짜 |
| E 경계 | since 하루 뒤 | 적용 E7 만, 승인 없음(취소 E4 제외) | since 포함 |
| E 값 | since null / 없는 품목 id | 빈 페이지(예외 없음) | — |
| **B A사로 B사** | B사 범위에서 A사 품목 id | 빈 페이지 | RLS |
| 상한 | 승인 501건 | 앞 500건(B0001…B0500) + truncated | 상한 + 1 행 |
| PLM 없음 | `EcoLookup.NONE` | 빈 페이지 | default 메서드 |

## PLM 주 목록 엑셀 — 지시·요청 grid 메뉴 (2026-10-23, V164·임시 V9908, 브랜치 `claude/plm-work`, docs/39 25장)

`PlmListExcelTest` 3(신규) + `PlmGridTest` 3(정렬 조각을 공유하게 바꾼 뒤 그대로 통과). 전체 `./gradlew check` 2,280건 통과(실패 0, 건너뜀 2, JDK 21, main bc3d5ca 위).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 지시 제목 오름·열 [제목, 번호, 상태] | 머리 = 그 순서, 행 = 가·나·다·라·마(ECO-2·4·1·5·3), 취소 행 상태 "취소"(코드 이름) | grid 조각 공유 |
| 정상 | 상태 CANCELLED / 검색어 "지시 가" / 지정 없음 | 1건 / ECO-2 / grid 열 전부·번호 내림 | — |
| 정상 | 요청 번호 오름·[번호, 상태] | 5행, ECR-1 "열림" | — |
| E 값 | 없는 품목 코드 | 머리만 | grid 와 같음 |
| E 값 | 허용 밖 정렬(targetCnt·requesterNm)·정렬 4개·grid 에 없는 열(description)·상태 NOPE | 모두 400 | 허용 enum·열 목록 |
| A 권한 | EXCEL 없는 사용자(SEARCH 만) | 지시·요청 403 | `@ProgAuth(EXCEL)` |
| **B A사로 B사** | B사 사용자 지시 엑셀 | 머리만(A사 행 0) | RLS |
| 브라우저 전(main)·후, 1366·390 | 머리 우클릭 메뉴 / 엑셀 클릭 | 전: 엑셀 없음 / 후: "엑셀로 내보내기" → 파일 "설계 변경 지시_…xlsx"·"설계 변경 요청_…xlsx" 내려받음(열 = grid 6칸, 상태 = 코드 이름), 가로 넘침 0, 콘솔 = favicon 404 | — |

## PLM 기한 지남 알림 (2026-10-23, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 26장)

`PlmOverdueNotifyTest` 3(신규). PLM 시험 89 통과. 전체 `./gradlew check` 2,309건 통과(실패 0, 건너뜀 2, JDK 21, main #432 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 승인·유효일 어제 / 승인·유효일 오늘 / 적용됨·작성 중(유효일 지남) / PCN 기한 어제·오늘·응답 받음·취소 지시의 PCN | 알림 = 승인·어제 지시 1 + 기한 어제 PCN 1(담당에게), 중복 키 `ECO:{id}:{날짜}`·`PCN:{id}:{날짜}`, 링크 `plm-eco` + keyword = 지시 번호 | 홈 카드 같은 조건 |
| H 반복 | 매시 작업 다시 실행 | 새 알림 0 | 중복 키 |
| A 권한·구독 | 담당이 `plm-eco` 권한 없음 / 담당이 그 이벤트 구독 끔 | 알림 없음 | 플랫폼 규칙 |
| **B A사로 B사** | B사 범위에서 실행 / 정기 작업(모든 회사) | A사 담당 알림 0 / A사 담당에게 1 | RLS·`forEachActiveComp` |
| (발견) | 승인된 지시의 유효일을 고쳐 "다시 알림" 을 시험하려 했으나 트리거가 막음(승인 뒤 내용 고정) | 그 경로는 반려 → 재상신에서만 — 문서(N4)에 적음 | 시험에서 뺌 |

## PLM 지시 복사 (2026-10-24, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 27장)

`PlmEcoCopyTest` 3(신규). PLM 시험 92 통과. 전체 `./gradlew check` 2,330건 통과(실패 0, 건너뜀 2, JDK 21, main #444 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 적용된 지시(유효일 열흘 전·임시 변경 끝 사흘 전·고객 승인 받음, 대상: 품목 FG·공정 SPOT·품목 OLD(사용 중지)·BOM 작성 중 리비전) 복사 | 새 작성 중 지시: 유효일·표시 끝 = 오늘, 담당 = 복사한 사람, 고객 승인 필요·대기·번호 없음, 결재 없음 / 대상 = 품목 FG(변경 내용 그대로)·공정 FG, copied 2·skipped 2 / 원본 그대로(적용·대상 4) | C2·C3 |
| E 값 | 고객이 매입처가 됨 → 복사 | 400 custCd, 새 지시 없음 | 머리 재검증 |
| H 중복 | 같은 멱등 키 두 번 | 409 `DUPLICATE_REQUEST`(지시 1개만 생김) | — |
| A 권한 | 조회 권한만 | 403 | `@ProgAuth(SAVE)` |
| **B A사로 B사** | B사 사용자가 A사 지시 id 로 복사 | 404, B사 지시 0 | RLS(find) |
| 브라우저 전(main)·후, 1366·390 | 상세 버튼 / 복사 | 전: [복사] 없음 / 후: [복사] → 확인 대화상자(취소 = 아무 일 없음) → 새 번호로 열림·"ZT-ECO-1 지시를 복사했습니다(대상 1건)"·유효일 오늘·대상 1, 가로 넘침 0, 콘솔 = favicon 404 | — |
| (관찰) | 목록이 "ZT-ECO-1" 검색 중이면 새 지시는 목록에 안 보이고 상세만 열림 | 조회 조건을 그대로 두는 기존 동작(저장·ECR 접수와 같음) | 그대로 |

## PLM grid 변경 이력 보기 (2026-10-24, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 28장)

`PlmAuditViewTest` 1(신규). 전체 `./gradlew check` 2,373건 통과(실패 0, 건너뜀 6, JDK 21, main #460 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | API 로 지시 만들고 제목 고침 → 지시 이력 / 요청 만듦 → 요청 이력 | 최신 먼저 UPDATE·INSERT, 행위자 "…:eng1" / 요청 1건 | grid `auditTable` |
| A 권한 | 요청 화면 권한만 → 지시 이력 / 요청 이력 | 403 / 200 | 플랫폼(화면 SEARCH) |
| **B A사로 B사** | B사 사용자가 A사 지시 키로 이력 | 빈 목록 | RLS |
| 브라우저 전·후, 1366·390 | 행 우클릭 메뉴 | 전: "변경 이력 보기" 없음 / 후: 있음 → 대화상자에 이력(ZT 는 SQL 로 넣어 행위자 UNKNOWN), 가로 넘침 0 | — |
| (관찰, 공통) | 390 에서 우클릭 → 행 선택으로 상세 서랍이 열려 메뉴를 덮음(누를 수 없음) — 이력 대화상자 자체는 정상 | hb.grid·hb.page 공통 동작 | 상위에 후보 보고 |

## PLM 변경 통지서 엑셀 (2026-10-24, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 29장)

`PlmEcoNoticeTest` 3(신규). PLM 시험 96 통과. 전체 `./gradlew check` 2,383건 통과(실패 0, 건너뜀 2, JDK 21, main #471 병합 뒤). main #478(공통 사용자 고르기) 병합 뒤 다시 2,395건 통과(실패 0, 건너뜀 2).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 작성 중 지시(고객 승인 필요, 대상: 품목·공정·BOM 리비전, 변경 내용 두 줄) | 제목 "(초안 — 결재 전)", 지시 번호·고객 "고객 일 (C1)"·적용 예정일, 사유 = 코드 이름, 변경 내용 줄바꿈 유지, 대상 표(유형 "품목", 공정 "SPOT 용접", BOM "Rev B"), 고객 확인란 6칸, 결재선·서명 글자 없음, 파일 이름 ECO-N1_notice.xlsx | T2·T3 |
| 정상 | 승인된 지시(고객 승인 필요 없음) | 초안 표시 없음, 고객·고객 승인 칸 빈 칸 | T4·T5 |
| E 주입 | 변경 내용 `=SUM(1,2)` | 셀 `'=SUM(1,2)` | safeText |
| A 권한 | 조회 권한만(EXCEL 없음) | 403 | `@ProgAuth(EXCEL)` |
| **B A사로 B사** / 없는 id | B사 사용자가 A사 지시 id | 404 / 404 | RLS(findEcos) |
| 브라우저 전·후, 1366·390 | 상세 [통지서] → 내려받기 | 전: 버튼 없음 / 후: "ZT-ECO-1_notice.xlsx" 내려받음, 알림 "변경 통지서를 내려받았습니다", 가로 넘침 0 | — |
| (발견) | 고객 없는 지시의 고객 칸 "-" 가 파일에서 `'-` 로 보임(수식 방지 규칙) | 빈 칸으로 바꾸고 시험 추가 | T5 |


## PLM 홈 카드 "고객 응답 대기" 숫자 링크 (2026-10-24, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 30장)

`PlmHomeCardTest` 5 → 6. PLM 시험 97 통과. 전체 `./gradlew check` 2,401건 통과(실패 0, 건너뜀 2, JDK 21, main #485 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 기존 fixtures(PPAP 판정 대기 1·PCN 기한 지남 1·취소 지시 것) | 숫자 2, 링크 `status=CUST_WAIT` | W3·W4 |
| **숫자 = 목록 건수** | 한 지시에 PPAP 판정 대기 + 고객 둘의 기한 지난 PCN, 응답 받은 PCN·판정 끝난(반려) PPAP 지시 하나 더 | 숫자 3(지시 수) = 목록 `status=CUST_WAIT` 3행(E-PAST·E-CUST·E-BOTH) = grid total 3, 보조 숫자 PPAP 2·PCN 3 | 같은 `ecoWhere` 조각 |
| E 값 | `status=CUST`(모르는 묶음 값) | 400 | `criteria` |
| **B A사로 B사** | B사에만 fixtures | A사 카드 0(기존 시험) — 목록 조건도 같은 RLS | RLS |
| 브라우저 전(main)·후, 1366·390 | 홈 "고객 응답 대기" 카드 → 숫자 / 지시 화면 상태 칸 | 전: 숫자 링크 없음, 상태 칸에 묶음 없음 / 후: 숫자 2 링크 → `/page/plm-eco?q.status=CUST_WAIT`, 상태 칸 "고객 응답 대기(…)" 선택됨, grid ZT-ECO-W2·W1 2행(카드 숫자와 같음), 가로 넘침 0, 콘솔 = favicon 404 | — |

## PLM PPAP 제출 요약서 엑셀 (2026-10-24, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 31장)

`PlmPpapSummaryTest` 3(신규), `PlmEcoNoticeTest` 3 그대로(서식 도우미로 옮긴 뒤). PLM 시험 100 통과. 전체 `./gradlew check` 2,432건 통과(실패 0, 건너뜀 3, JDK 21, main #494 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 작성 중 패키지(레벨 3, 요소 18 — 17 해당 없음, 18 PSW 파일 붙임, 요소 메모·패키지 비고에 사내 글) | 제목 "(초안 — 제출 전)", 지시 번호·고객 "고객 일 (C1)"·레벨 3, 상태 = 코드 이름, 아직 없는 제출일·판정 번호 빈 칸, 요소 18행(이름·요구·상태 = 코드 이름, 파일 = 이름만), 고객 판정란 빈 칸, "사내"·저장 경로 글자 없음, 파일 이름 ECO-P1_ppap.xlsx | S2~S4·S7, 상위 덧붙임 ① |
| 정상 | 제출 → 임시 승인(판정 번호 PSW-77) / 그 뒤 지시 취소 | 초안 표시 없음, 판정일·판정 번호 머리에 / 제목 "(취소된 지시)" | S5·S6, 덧붙임 ② |
| E 주입 | 첨부 파일 이름 `=HYPERLINK("x").pdf` | 셀 `'=HYPERLINK("x").pdf` | safeText |
| A 권한 | 조회 권한만(EXCEL 없음) | 403 | `@ProgAuth(EXCEL)` |
| **B A사로 B사** / 없는 id | B사 사용자가 A사 패키지 id | 404 / 404 | RLS(findPpap) |
| 리팩터링 회귀 | 통지서 전(main)·후 같은 지시로 내려받아 비교 | 셀 142개 값·굵기·글자 크기·테두리·줄바꿈, 합친 칸, 열 폭, 행 높이 모두 같음 | 덧붙임 ③ |
| 브라우저 전(main)·후, 1366·390 | 지시 상세 PPAP 영역 [요약서] | 전: 버튼 없음 / 후: "ZT-ECO-P1_ppap.xlsx" 내려받음, 알림 "PPAP 요약서를 내려받았습니다.", 파일에 ZT 메모·비고 없음, 가로 넘침 0, 콘솔 = favicon 404 | — |

## PLM PCN 메일 발송 결과 표시 (2026-10-24, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 32장)

`PlmPcnMailStatusTest` 2(신규). PLM 시험 102 통과. 전체 `./gradlew check` 2,466건 통과(실패 0, 건너뜀 13 — 실측 시험, JDK 21, main #506 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 담당자 셋에게 메일 → 아웃박스 행을 SENT / FAIL(`REJECTED: …`) / 그대로 | 가 = SENT "보냄(메일 서버 접수)"·접수 시각, 나 = FAILED·REJECTED "메일 서버가 받는 주소를 거절했습니다"(주소 없음), 다 = PENDING | M1~M3 |
| 정상 | 일시 오류로 상한까지(`TEMPORARY: …`, attempts = max) | FAILED·MAX_ATTEMPTS | 플랫폼 18.5 R4 |
| 정상 | 발송 이력 없는 통지 | 빈 목록 | — |
| 노출 | 통지 목록 응답 | `outboxId` 글자 없음 | `@JsonIgnore` |
| **B A사로 B사** | A사 이력 한 줄이 B사의 외부 메일 id(SENT)를 가리킴 / B사 사용자가 A사 지시 통지 목록 | 그 줄 상태·문구 없음(지금 회사만, 18.5 R1) / 404 | 플랫폼 + RLS |
| (발견) | 첫 시험에서 상태가 전부 비어 있음 | MyBatis 에 UUID 형 처리기가 없어 `outbox_id` 가 조용히 null → `::text` 로 받아 변환 | 32장 함정 |
| 브라우저 전(main)·후, 1366·390 | 지시 상세 PCN 표 발송 이력(ZT 담당자 셋 — 보냄·거절·로컬 메일 꺼짐) | 전: 줄만 / 후: 줄 끝 배지 "보냄(메일 서버 접수)"(툴팁 "…읽었는지는 알 수 없습니다 · 시각")·"보내지 못함 + 이유", 가로 넘침 0(390 은 기존 표 가로 스크롤 상자 안), 콘솔 = favicon 404 | 덧붙임 ② |

## PLM PCN 메일 변경 통지서 첨부 (2026-10-24, V183·임시 V9909, 브랜치 `claude/plm-work`, docs/39 33장)

`PlmPcnMailAttachTest` 3(신규). PLM 시험 105 통과(+ PlmGridTest 정렬 확인). 전체 `./gradlew check` 2,493건 통과(실패 0, 건너뜀 11 — 실측 시험, JDK 21, main #514 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 결재 전 지시의 통지에 담당자 둘, 첨부 켬 | 사본 `plm-eco × PCN-{id}` 1개(이름 `{지시번호}_notice.xlsx`), 두 메일 모두 그 `notice_file_id`, 아웃박스 첨부 1개(이름·SHA-256), 본문 "아직 결재 전인 초안입니다" | F1·F2·F5 |
| 정상 | 같은 내용으로 다시 보냄 / 끔 / 비움 | 사본 그대로 1개 / 글자 메일·null / null | F2(바이트 고정) |
| 원장 | `notice_file_id` 를 SQL 로 바꿈 | 거부(원장 트리거) | F4 덧붙임 |
| A 권한·잠김 | 조회 권한만으로 사본 내려받기 / 사용자가 API 로 `PCN-{id}` 에 올리기·사본 지우기 | 200 / 409 `FILE_LOCKED`·409 `FILE_LOCKED`(사본 사용 중 그대로) | F3 덧붙임 |
| **B A사로 B사** | B사 사용자: A사 사본 내려받기 / A사 통지로 첨부 메일 보내기 | 404 / 404 | RLS |
| E 정책 | 회사 허용 확장자에서 xlsx 를 뺌 → 첨부 켜고 보냄 | 400, 메일 이력·사본·아웃박스 모두 그대로 0 / 첨부 끄면 보낼 수 있음 | 한 트랜잭션 |
| (발견) | 같은 내용인데 사본이 둘 생김 | POI 가 만든 시각·zip 시각을 넣어 바이트가 달랐음 → `PlmSheet.bytes()` 에서 고정 | 33장 구현 |
| 브라우저 전·후, 1366·390 (메일 켬, 가짜 SMTP) | 통지 [메일] 펼침 → 보내기 / 이력 줄 | 전: 첨부 체크·초안 안내 없음 / 후: "변경 통지서 엑셀 첨부(보내는 시점 내용)" 체크(기본 켬)·"결재 전 지시라 …" 안내·미리보기에 초안 줄, 보낸 뒤 이력 "보내는 중 · 첨부 ZT-ECO-P1_notice.xlsx" → 링크로 내려받은 파일 제목 "(초안 — 결재 전)"·두 번 받은 파일 SHA-256 같음, 가로 넘침 0, 콘솔 = favicon 404 | — |
| QA 정렬 가드(같은 PR) | 지시·요청 grid 정렬에서 `nulls last` 제거, 요청 품목 정렬 오름·내림 | 가드 통과(KNOWN PLM 두 줄 삭제), 품목 없는 요청은 오름·내림 모두 뒤 — 예전과 같은 순서 | `(칸 is null), 칸` |

## PLM 사내 지시 기록서 엑셀 (2026-10-24, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 34장)

`PlmEcoRecordTest` 3(신규), `PlmEcoNoticeTest`·`PlmPpapSummaryTest` 에 접속 로그 확인 추가. PLM 시험 108 통과. 전체 `./gradlew check` 2,522건 통과(실패 0, 건너뜀 14 — 실측 시험, JDK 21, 34·35·36장 함께, main #528 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 지시 → 상신(결재 mgr1) → 전자서명 승인(의견 "확인함") → 통지 | 기록서: 제목 "(사내)", 상태 코드 이름, 승인 일시, 대상 변경 내용(`=1+1` → `'=1+1`), 결재선 mgr1·결재·승인·확인함, 전자서명 승인·mgr1, 통지 C1 | R1 |
| 언어 | 영어 사용자(locale en)로 내려받기 | "Engineering Change Order Record (internal)", "Approval line" | R2 — 로그인 사용자 언어 |
| 감사 | 기록서·통지서·PPAP 요약서 내려받기 | 접속 로그 `ACTION`·`EXCEL`·`plm-eco` + detail `record;ecoId=`·`notice;ecoId=`·`ppapSummary;ppapId=` | R4(통지서·요약서 detail 같이 고침) |
| A 권한 | 조회 권한만 | 403 | `@ProgAuth(EXCEL)` |
| **B A사로 B사** / 없는 id | B사 사용자가 A사 지시 id | 404 / 404 | RLS(detail) |
| 브라우저 전(main)·후, 1366·390 | 지시 상세 [기록서] | 전: 버튼 없음 / 후: "ZT-ECO-P1_record.xlsx" 내려받음·알림 "지시 기록서를 내려받았습니다.", 파일에 결재선·전자서명·통지 표, 로컬 접속 로그 detail `record;ecoId=…`, 가로 넘침 0, 콘솔 = favicon 404 | — |
| (환경) | 로컬 DB 가 V9909 를 main V180 보다 먼저 적용해 기동 시 Flyway 검증 실패 | 브라우저 확인만 `--spring.flyway.out-of-order=true` 로 기동(커밋 없음) — 병합 때 정식 번호로 바뀌면 사라짐 | — |

## PLM 고객 통지 응답 기한 임박 알림 (2026-10-24, 마이그레이션 없음, 브랜치 `claude/plm-work`, docs/39 35장)

`PlmOverdueNotifyTest` 3 → 5(기존 첫 시험의 "기한 오늘" 통지가 이제 임박 알림 — 기대값 갱신). PLM 시험 109 통과. 전체 `./gradlew check` 2,522건 통과(실패 0, 건너뜀 14 — 실측 시험, JDK 21, 34·35·36장 함께, main #528 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 기한 이틀 뒤·사흘 뒤·응답 받음·취소 지시의 통지 | 이틀 뒤 하나만 `PLM_PCN_DUE_SOON`(키 `PCN-SOON:{id}:{기한}`), 링크 plm-eco + 지시 번호 | D2·D4·D5 |
| 정상 | 기한 오늘 통지(기존 시험) | 임박 알림 1 + 지남은 아님 / 어제 기한은 지남 알림 | D2 |
| 정상 | 다시 실행 | 0(한 번만) | D4 |
| 경계 | 기한 = 오늘 → 실행 / 시계를 하루 옮겨(기한 = 어제) 실행 / 한 번 더 | 임박 1 / 지남 1·임박 0 / 0 — 같은 통지가 `PCN-SOON:`·`PCN:` 각각 한 번 | D2·D4 |
| **B A사로 B사** | 다른 회사 범위로 실행 | 0, A사 담당 알림 그대로 | TenantRunner·RLS |

## PLM DOC 대상 문서 유형에 PFMEA (2026-10-24, V187·임시 V9910, 브랜치 `claude/plm-work`, docs/39 36장)

`PlmDrawingTest` +1. 전체 `./gradlew check` 2,522건 통과(실패 0, 건너뜀 14 — 실측 시험, JDK 21, 34·35·36장 함께, main #528 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | PFMEA 문서(작성 중 리비전)를 지시 DOC 대상으로 저장 | 200, 대상 행 생김 | `DOC_TYPES` + 트리거 |
| E 값 | OTHER 문서를 대상으로 / 직접 SQL 로 OTHER 리비전 대상 넣기 | 400 `DOC_TYPE` / `ck_plm_eco_target_doc_type` | 목록 밖 그대로 거부 |
| QA P-1(같은 PR) | 통지서 첨부 메일 보낸 뒤 사본 `locked_yn` / 직접 SQL 로 사본 `use_yn = N` | `Y` / `ck_sys_file_locked` 거부 | `PlmPcnMailAttachTest` 에 추가 |
| QA 관찰 뒤집기 | #529 병합 뒤 `PcnNoticeQaTest` 관찰(잠기지 않음) → `보낸_사본은_DB_잠금_표시로_굳는다` | 통과. 전체 `./gradlew check` 2,545건 통과(실패 0, 건너뜀 14, main #541 병합 뒤) | P-1 |

## PLM PPAP 제출 메일 (2026-10-26, V190·임시 V9911, 브랜치 `claude/plm-work`, docs/39 37장)

`PlmPpapMailTest` 6(신규). PLM·QA·가드 시험 158 통과. 전체 `./gradlew check` 2,551건 통과(실패 0, 건너뜀 14 — 실측 시험, JDK 21, main #551 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 제출된 패키지 → 담당자 둘 | 2통, 사본 `PPAPMAIL-{id}` 1개(`{지시번호}_ppap.xlsx`·`locked_yn = Y`), 두 이력 같은 사본, 아웃박스 받는 주소·첨부 1개, 본문 제출 요소 1·18, "사내"·사내 주소 없음, 상세 이력 2줄(보내는 중), 응답에 `outboxId` 없음 | P1·P2·P4·P6~P8 |
| 정상 | 다시 보냄 | 사본 그대로 1개, 이력 3 | P3·P4 |
| **E 받는 사람** | 다른 거래처 담당자 / 사용 중지 담당자 / 같은 id 두 번 / 빈 목록 | 400 `NOT_PPAP_CONTACT` / `NOT_PPAP_CONTACT` / `DUPLICATE` / `REQUIRED`, 이력·아웃박스 0, 후보 목록에도 없음(대표 먼저) | P1 — 주소 직접 입력 칸 없음 |
| **멱등** | 같은 키 두 번 / 키 없음 | 200 → 409 `DUPLICATE_REQUEST`(이력 1) / 400 | P9 |
| **상태** | 작성 중 / 판정 뒤(반려) / 취소된 지시 | 409 `PLM_PPAP_STATE` ×3, 이력 0 | P3 |
| **롤백** | 담당자 [정상, 주소 형식 오류] — 첫 메일 발행 뒤 플랫폼 거부 | 400, 이력 0·아웃박스 그대로·사본 0 / 고쳐 보내면 정상(아웃박스 +1) | 한 트랜잭션 |
| A 권한 | 조회 권한만 — 후보·보내기 / 사본 내려받기 | 403·403 / 200 | P9 |
| **B A사로 B사** | B사 사용자 — 후보·보내기·사본 내려받기 | 404·404·404, B사 이력 0 | RLS |
| 잠김·원장 | 사용자가 API 로 `PPAPMAIL-{id}` 올리기·사본 지우기 / 이력 SQL 수정 | 409 `FILE_LOCKED` ×2 / 거부 | P5·P6 |
| 브라우저 전(main)·후, 1366·390 (메일 켬, 가짜 SMTP) | 제출된 ZT 패키지 PPAP 영역 | 전: [제출 메일 보내기] 없음 / 후: 버튼 → 담당자(대표 기본)·"첨부: PPAP 제출 요약서(보내는 시점 내용)"·미리보기(제출 요소 1·18, 메모 없음) → 확인 → "메일 1통을 보냈습니다", 이력 "보내는 중 · 첨부 ZT-ECO-PM_ppap.xlsx"(뒤에 로컬 가짜 SMTP 라 "보내지 못함 + 인증 실패" 로 바뀜), 가로 넘침 0, 콘솔 = favicon 404 / PCN 메일 펼침(공통 함수로 바꾼 뒤) 통지서 첨부 체크 그대로 | — |

## PLM PPAP 요소 6·7 ← APQP 현행 확정본 (2026-10-26, V194·임시 V9912, 브랜치 `claude/plm-work`, docs/39 38장 · PMS A3-6)

`PlmPpapApqpTest` 6(신규). 전체 `./gradlew check` 2,566건 통과(실패 0, 건너뜀 15 — 실측 시험, JDK 21, main #558 병합 뒤).

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 대상 품목 ZT-A 의 확정 PFMEA·관리계획서(시작품·양산) + 작성 중·대상 아닌 품목 것 섞어 후보 | FMEA = 확정 1건만, CP = 양산 → 시작품 순, 작성 중·다른 품목 없음 | Q1 |
| 정상 | 요소 6·7 연결 | label "PF-A rev 1"·"CP-M rev 1 PRODUCTION"(서버가 만듦), 상태 RELEASED, 패키지 row_ver +2 | Q2·Q3 |
| 정상 | 연결 뒤 APQP rev 2 확정(rev 1 폐기) | 근거 그대로 rev 1, 상태 OBSOLETE, 후보는 rev 2 | Q5 |
| **E 후보 밖** | 작성 중·다른 품목·폐기·없는 id / 요소 6 에 CP id·요소 7 에 FMEA id / 요소 5 / 낡은 row_ver | 400 `NOT_CANDIDATE` ×6 / 400 `CODE` / 409, 연결 0 | Q3 |
| **조용히 틀림** | 본문에 `label:"가짜"` / SQL 로 요소 6 kind = CP / label 만 null | 무시(서버 label) / `ck_plm_ppap_elem_apqp_kind` / `ck_plm_ppap_elem_apqp`(처음 CHECK 는 `length(btrim(NULL))` 이 NULL 이라 통과 — 시험이 잡아 `IS NOT NULL` 추가) | V194 |
| **조용히 틀림** | 연결을 저장 본문에 실었다면 칸 빠진 저장이 근거를 지움 | 저장과 분리한 API 로 설계 — 저장은 근거 칸을 건드리지 않음 | 38장 구현 메모 |
| A 권한 | APQP SEARCH 없음 — 후보 / 연결·끊기 / 상세 | 빈 목록 / 403 `NO_PERMISSION` / `apqpKinds` 빈·label 만·상태 null | Q4 |
| A 권한 | PLM 조회만 — 후보·연결 | 403·403 | SAVE |
| 상태 | 제출 뒤 API 끊기 / owner 직접 UPDATE | 409 `PLM_PPAP_STATE` / `ck_plm_ppap_fixed`(V082 가드가 행 단위로 이미 막음) | 제출 뒤 불변 |
| 서명 | 연결 없는 요소의 서명 내용 문자열·해시 | 38장 이전과 같음(문자열 고정), 연결 있으면 `A` 줄 덧붙음, 상태는 해시 밖 | Q6 |
| **B A사로 B사** | B사 사용자 — A사 패키지 후보·연결 / A사 사용자 — B사 FMEA id 연결 | 404·404 / 400 `NOT_CANDIDATE`(후보에 없음), B사 범위 A사 요소 0 | RLS·계약 |
| 브라우저 전(main)·후, 1366·390 | ZT-ECO-AQ 작성 중 PPAP 요소 6·7 | 전: APQP 줄 없음 / 후: [APQP 에서 고르기] → 후보(품목·번호·rev·단계 이름·제목·확정 시각, 양산 먼저) → [연결] → "APQP 근거: ZT-PF-AQ rev 1 ✓현행 확정" + [연결 끊기], SQL 로 폐기 → "▲폐기됨 — 새 리비전 있음". 페이지 가로 넘침 0(390 은 요소 표가 기존처럼 표 안에서 가로 스크롤), 콘솔 = favicon 404 | — |
