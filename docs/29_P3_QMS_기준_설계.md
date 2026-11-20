# 29. P3 QMS 설계 — 1부: P2 와 병행하는 품질 기준·계측기·관리문서·시정조치

작성 2026-10-20. 결정 D1~D10 채택(10장), Q1 구현 완료(4.1·7장), Q2a·Q2b·Q3 구현 완료(4.2~4.4·7장, 2026-10-21).
근거: docs/00 5장 테이블 표(`qms_*` 행)·6장 로드맵 P3 행, 02 2.3(IATF 7.1.5 MSA·교정, 7.5.3.2.1 보존, 8.5.1.3 셋업 검증, 8.5.6.1 변경, 10.2.3 문제해결, MSA 4판)·2.4(SPC)·2.6 보존 클래스, 17 2장(P1 공통 규칙), 18(BOM 리비전 규칙), 19·21(물리 자산·특성 마스터), 24 체크리스트 A~K, 25(P2 단계·경계), 26 9번(문서 간 정합·리비전 첨부 복사), 27 M11(IATF 2판·CSR), 28 R1·R3(봉쇄·SPC 자동 보류 — 2부).
P0 플랫폼 규약(01)·P1 공통 규칙(17 2장)·P2 공통 규칙(25 3장)은 그대로 따른다 — 이 문서는 **P3 에서 새로 정하는 것**만 적는다.

---

## 0. 왜 1부·2부로 나누나

P3 QMS 의 절반은 P2 결과(작업지시·LOT·실적·착수 게이트 확장점)를 키로 쓴다 — 공정·출하검사 측정값, 초품/종품, MRB, SPC 부분군, 품질 게이트. P2 는 7단계라 오래 걸리므로(2026-10-20 사용자) **P2 에 기대지 않는 부분을 1부로 떼어 P2 와 동시에 진행**한다.

| | 1부 (이 문서, 지금) | 2부 (P2 4·5단계 뒤) |
|---|---|---|
| 기대는 것 | P1 기준정보(품목·품목 클래스·공정·특성·물리 자산·거래처) + P0(첨부·결재·채번·정책) | P2 작업지시·LOT·실적·착수 게이트 |
| 범위 | 검사 기준(검사계획·샘플링), 계측기 교정·MSA, 관리문서 리비전·정합 검사, 시정조치(8D)·고객 CSR | 검사 실행·측정값 원장, 품질 게이트, 초품/종품, NCR·MRB·처분, 4M 변경, SPC 최소형, 봉쇄(28 R1), 교정 NG 영향 LOT |
| 원칙 | 2부가 붙을 **자리**(키·상태·판정 함수)를 1부 테이블에 미리 둔다 — 2부에서 1부 테이블을 고치지 않게 | 1부 판정 함수를 그대로 호출 |

## 1. 결론 먼저

- 1부는 **"무엇을, 어떻게, 무엇으로 재는지"와 "품질 문서·조치 기록"**까지다. 데모 흐름: 특성 마스터 → 검사계획(품목 × 검사 유형, 리비전·확정) → 계측기 교정 기록·기한 판정 → GRR 연구(%GRR·ndc) → 관리문서 배포(결재) + "특별특성이 검사계획에 빠졌다" 정합 경고 → 고객 클레임 8D 진행·종결.
- **검사계획에 리비전을 둔다**(특성에는 리비전이 없다 — 21 2장). 확정 때 특성 규격을 행에 **스냅샷** → "그때 무엇을 기준으로 검사했나"가 계획 리비전 하나로 설명된다. 2부 측정값은 계획 행을 가리키고 규격을 다시 스냅샷한다.
- 계측기는 **새 마스터를 만들지 않는다** — P1 물리 자산(`asset_type = GAUGE`)을 쓰고, 교정 기록만 `qms_gauge_calib`(추가 + 무효만, 4b 설치 이력과 같은 "원장 변형")로 더한다. 사용 가능 판정은 함수 하나(`GaugeStatus`)로 — 2부 측정값 입력이 같은 함수를 부른다.
- 관리문서·8D 종결은 **P0 결재(`ApprovalDocType`)**를 처음으로 업무에 붙이는 사례가 된다.
- P2 와 동시에 개발하므로 **Flyway 번호·공용 파일 병합 규칙**(6장)을 먼저 정한다.

## 2. 범위와 단계

| 단계 | 범위 | 첫 화면 | 완료 기준 |
|---|---|---|---|
| **Q1** | **검사 기준**: 검사 유형(공통코드), 샘플링 계획 `qms_sampling_plan`(고정 n·전수·비율·AQL 표 참조) + AQL 표 `qms_aql_table`(사용자 입력, 8장 D3), 검사계획 `qms_insp_plan`(품목 또는 품목 클래스 × 검사 유형 × 공정 선택 × 대체 × 리비전, DRAFT → RELEASED → OBSOLETE, 기간) + 행 `qms_insp_plan_line`(특성 · 검사 방법 · 계측기 클래스 · 샘플링 · 주기 · 규격 스냅샷) | 검사계획 관리 | 확정본 불변(직접 SQL 포함), 확정본 기간 겹침 거부, 특성 규격이 바뀐 뒤에도 확정본 행 규격 그대로, 사용 중지 특성·샘플링 계획은 새 참조만 차단, AQL 판정 함수 손 검산 |
| **Q2a** | **계측기 교정**: `qms_gauge_calib`(교정일·결과 OK/NG/LIMITED·기관(사내/거래처)·성적서 첨부·다음 교정일, 추가 + 무효만), `GaugeStatus`(OK·DUE_SOON·OVERDUE·NG·INACTIVE·NO_RECORD), 기한 임박 목록, P1 `mst_phys_asset.last_calib_date` 와의 관계(8장 D4) | 계측기 교정 | 교정 이력 수정·삭제 불가, 무효는 사유 필수, 다음 교정일 = 교정일 + 주기(주기 변경 뒤 과거 행 그대로), 상태 판정 경계(오늘 = 기한일) |
| **Q2b** | **MSA**: `qms_msa_study`(계측기·특성·방법 AVG_RANGE / ANOVA / ATTRIBUTE, 측정자 수·부품 수·반복 수, 공차 또는 공정 변동 기준) + `qms_msa_data`(측정자 × 부품 × 반복 원자료) + 계산(EV·AV·GRR·PV·TV·%GRR·ndc, 계수형 일치도) | GRR 연구 | 손 검산 예제 2개 이상 일치, 빈 칸·이상값 입력 오류, %GRR 판정 구간(10·30%) 정책값 |
| **Q3** | **관리문서**: `mst_doc`(유형 WI 작업표준서·INSP_STD 검사기준서·CP·PFMEA·DRAWING·OTHER, 적용 품목·공정) + `mst_doc_rev`(리비전·상태 DRAFT → IN_APPROVAL → RELEASED → OBSOLETE, 첨부, 배포일) — 배포는 결재, **리비전 복사 때 첨부까지**(26 9번). **정합 검사**: 특별특성(CC/SC)이 확정 검사계획에 없음 / 확정 검사계획이 있는데 검사기준서 배포본 없음 / 검사계획이 가리키는 문서 리비전이 폐기됨 | 관리문서 · 정합 점검 | 결재 반려·회수 때 상태 되돌림, 배포본 하나만(같은 문서 RELEASED 1건), 폐기본 내려받기는 "폐기본" 표시, 정합 점검 3종 |
| **Q4** | **시정조치·고객 요구사항**: `qms_capa`(출처 CUSTOMER_CLAIM/INTERNAL/SUPPLIER/AUDIT, 거래처·품목, D1~D8 구역, 담당·기한, 상태 전이, 수평전개 대상, 첨부, 종결 결재) + `qms_cust_csr`(거래처 × 문서·리비전 × 적용 품목/클래스 × override: 보존 기간·통지 기한·검사 주기) | 시정조치 · 고객 요구사항 | 기한 정책(초기 대응·종결 일수)으로 지연 표시, 종결 뒤 수정 불가, 반려 시 D 단계로 복귀, 2부 NCR 연결 칸 |

순서 원칙(17·25 와 같음): **참조되는 것 먼저**, 매 단계 화면·API·테스트(정상 + 이상 사용 A~K + A사로 B사 + 직접 SQL)까지 끝난 상태로 커밋, 끝나면 7장 표에 ✅.

## 3. 1부 공통 규칙

| 규칙 | 내용 | 근거 |
|---|---|---|
| 리비전 문서 | 검사계획·관리문서는 **BOM 리비전 규칙 그대로**(머리 = 대상 × 대체 × 리비전, 작성 중만 편집, 확정본 불변 트리거 55000, 확정본 기간 겹침 배제 제약, 새 리비전 = 복사 + 이전본 종료일 자동 마감, 자동 리비전 A0~ZZ9) — 같은 코드 경로를 쓸 수 있으면 공통 헬퍼로 뺀다(4장 끝) | 18, 20 |
| 규격 스냅샷 | 검사계획 **확정 때** 행에 특성의 공칭·하한·상한·단위·자릿수·특별특성을 복사. 작성 중엔 특성을 따라간다. 확정 뒤 특성 규격이 바뀌면 화면이 "특성 규격과 다름 — 새 리비전 필요" 배지 | 21 2장(특성 리비전 없음) |
| 기록 성격 | 교정 기록·MSA 원자료·8D 는 **품질기록** — 보존 클래스 `QUALITY_RECORD`(V007, 부품 EOL + 1년). 교정 기록은 추가 + 무효만(원장 변형), MSA 원자료는 연구 확정 전까지만 편집, 8D 는 종결 뒤 불변 | 02 2.3·2.6, 01 3.1 |
| 판정 함수 | `GaugeStatus.of(asset, today)`, `SamplingPlan.sampleSize(lotQty)`·`judge(defects)`, `InspPlanResolver.effective(item, type, proc, date)` — **순수 함수 + 표 기반 단위 테스트**(26 채택 3번). 2부는 이 함수만 부른다 | 26 |
| 기준정보 | 샘플링 계획·AQL 표·문서·CSR 은 P1 기준정보 공통 규칙(회사별 RLS·`row_ver`·`tg_audit`·사용 중지 = 새 참조만 차단) | 17 2장 |
| 정책 | 새 정책 키: `QMS_CALIB_DUE_SOON_DAYS`(기본 30), `QMS_GRR_OK_PCT`(10)·`QMS_GRR_LIMIT_PCT`(30), `QMS_CAPA_CONTAIN_DAYS`(초기 대응, 기본 2)·`QMS_CAPA_CLOSE_DAYS`(종결, 기본 60) — 고객 CSR override 는 Q4 | 27 M11 |
| 결재 | 관리문서 배포·8D 종결 = `ApprovalDocType`(`beforeSubmit` 상태 검사, `onFinished` 같은 트랜잭션에서 상태 전이). 결재 없이 쓰는 회사는 정책 `QMS_DOC_APPROVAL`(Y/N) — N 이면 APPROVE 권한으로 바로 배포 | 01 P0 7단계 |
| 테스트 | 25 3장 그대로 + **계산 손 검산**(AQL 표 조회·GRR 은 공개 예제 수치와 대조) | 24 |

## 4. 단계별 데이터 모델 (요약 — 단계 착수 때 상세를 이 문서 해당 절에 추가)

```
── Q1 검사 기준 ─────────────────────────────────────────
qms_aql_table        (comp, std_cd 예 'ISO2859-1', inspection_level I/II/III/S1~S4, lot_from, lot_to, code_letter)
qms_aql_plan_row     (comp, std_cd, severity NORMAL/TIGHTENED/REDUCED, code_letter, aql, sample_size, ac, re)
                     ← 표 값은 시드로 넣지 않는다(8장 D3). 엑셀 올리기 템플릿 제공
qms_sampling_plan    (comp, plan_cd, plan_nm, method FIXED/ALL/PERCENT/AQL, fixed_n, pct, std_cd, inspection_level,
                      severity, aql, ac, re(고정 n 용), use_yn)
qms_insp_plan        (comp, insp_type RECEIVE/PROCESS/FINAL/FIRST_OFF/LAST_OFF/PATROL, item_id 또는 item_class_id(둘 중 하나),
                      proc_id nullable(공정검사·초종품), alt_no, rev, status, valid_from, valid_to, doc_rev_id nullable(검사기준서),
                      change_reason, row_ver)          ← 확정본 기간 겹침 배제 제약 (comp, insp_type, 대상, proc, alt_no, 기간)
qms_insp_plan_line   (plan_id, line_no, char_id, method_nm(측정 방법), gauge_class(물리 자산 클래스 또는 'VISUAL'),
                      sampling_plan_id, freq_type EVERY_LOT/START/END/EVERY_N/EVERY_HOURS, freq_n,
                      ── 확정 때 스냅샷 ── nominal, lsl, usl, unit_nm, scale, special_cls, data_type,
                      reaction_plan(이탈 시 조치 문구), remark)
                     유일 (plan_id, char_id) — 같은 특성 두 번 금지(24 H 중복 등록)

── Q2 계측기 ──────────────────────────────────────────
mst_phys_asset       (P1 그대로, asset_type GAUGE) — calib_cycle_days 는 "다음 교정일 계산 기본값"
qms_gauge_calib      (comp, asset_id, calib_date, result OK/NG/LIMITED, limit_note, agency INTERNAL/EXTERNAL, cust_id nullable(외부 기관),
                      cert_no, cert_file_id, cycle_days(그때 주기 스냅샷), next_due_date, void_yn, void_reason, voided_by/at,
                      + created_*)               ← UPDATE 는 무효 1회만, DELETE 금지(트리거 + 권한)
qms_msa_study        (comp, study_no 채번, asset_id, char_id, method AVG_RANGE/ANOVA/ATTRIBUTE, n_appraiser, n_part, n_trial,
                      basis TOLERANCE/PROCESS, process_sigma nullable, status DRAFT/DONE, result_json(계산 결과 스냅샷), judged_at)
qms_msa_data         (study_id, appraiser_no, appraiser_nm, part_no, trial_no, value numeric / attr_value)  ← DONE 뒤 불변

── Q3 관리문서 ─────────────────────────────────────────
mst_doc              (comp, doc_no(채번 또는 입력), doc_type, doc_nm, use_yn)
mst_doc_target       (doc_id, item_id | item_class_id | proc_id)         ← 적용 대상 여러 개
mst_doc_rev          (doc_id, rev, status DRAFT/IN_APPROVAL/RELEASED/OBSOLETE, released_at, obsoleted_at, apr_req_id nullable,
                      change_summary)        ← 첨부는 FileService(target_type = 'qms-doc', target_id = doc_rev_id)
                     같은 문서 RELEASED 최대 1건(부분 유일 인덱스) — 배포 = 이전 배포본 OBSOLETE 와 한 트랜잭션

── Q4 시정조치·CSR ─────────────────────────────────────
qms_capa             (comp, capa_no 채번, source, cust_id/item_id nullable, title, occurred_on, status OPEN/CONTAINED/ANALYZED/
                      ACTIONED/VERIFIED/CLOSING/CLOSED/CANCELLED, owner_user_id, contain_due, close_due, csr_id(기한 근거),
                      d1~d8 텍스트 구역, ncr_id nullable(2부), apr_id, closed_at/by, cancel_reason, row_ver)
qms_capa_spread      (capa_id, line_no, target_type ITEM/PROC/CUST, item_id | proc_id | cust_id, status OPEN/DONE/NA, note)  ← 수평전개
qms_cust_csr         (comp, cust_id 고정, csr_no, csr_rev, title, effective_from,
                      overrides jsonb(CONTAIN_DAYS·CLOSE_DAYS·NOTIFY_HOURS·RETENTION_YEARS — 정해진 키만), use_yn) + 첨부(sys_file)
qms_cust_csr_target  (csr_id, item_id | item_class_id)
```

공통 헬퍼 후보: BOM·라우팅·검사계획·관리문서가 같은 "리비전 문서" 동작(작성 중 편집, 확정, 새 리비전, 폐기, 기간 마감, 리비전 자동 채번)을 한다. 검사계획을 만들며 `RevisionedDoc` 헬퍼(리비전 채번·상태 전이 표·기간 마감 SQL 조각)를 뽑고 BOM·라우팅은 **건드리지 않는다**(P2 병행 중 충돌 방지 — 이관은 병합 뒤 별도 작업).

### 4.1 Q1 상세 — 구현하며 정한 것 (2026-10-20, V047)

| # | 정한 것 | 이유 |
|---|---|---|
| Q1-1 | **계측기 클래스 = 회사 확장 공통코드 `GAUGE_CLASS`**(VISUAL·CALIPER·MICROMETER·HEIGHT·DIAL·GO_NOGO·CMM·TORQUE·TENSILE·OTHER + 회사 추가) | P1 물리 자산에 클래스 칸이 없다. Q2a 에서 `mst_phys_asset` 에 `gauge_class` 를 붙이면 "이 행을 잴 수 있는 교정 유효 게이지" 를 같은 코드로 찾는다 |
| Q1-2 | **클래스 계획은 공정 특성만**. 제품 특성 = 계획 품목과 같은 품목(품목 계획만), 공정 특성 = 계획 공정과 같거나 계획에 공정이 없음 — 서비스 `fits` + 트리거 `ck_qms_insp_plan_line_char_target` | `qms_char` 에 품목 클래스 단위 제품 특성이 없다(21). 원자재 군 수입검사는 검사 공정(예: IQC)의 공정 특성으로 — 데모 `COIL` × `IQC` |
| Q1-3 | **규격 스냅샷은 머리 트리거가** DRAFT → RELEASED 순간 `qms_char` 에서 복사(작성 중에 SQL 로 넣은 값도 덮음). 확정에는 행 1건 이상(`ck_qms_insp_plan_no_lines`) | 서비스만 복사하면 직접 SQL·다른 경로 확정에서 빠진다. 작성 중 행의 스냅샷 칸은 비어 있고 화면은 특성을 따라간다 |
| Q1-4 | 대상 키(유형·품목·클래스·공정·대체)는 **상태 무관 불변**(`ck_qms_insp_plan_key_immutable`), NULL 칸은 `coalesce(…, 0)` 으로 유일 인덱스·배제 제약 | BOM QA 3a #1 교훈(키를 바꾸면 행 트리거 검사를 우회). NULL = NULL 이 거짓이라 coalesce 없이는 공정 없는 확정본 두 개가 겹쳐도 통과 |
| Q1-5 | 사용 중지 특성·샘플링·회사 계측기 코드는 **새 참조만 차단** + 같은 대상 키의 다른 리비전이 쓰던 참조는 허용(V028 라우팅과 같은 규칙) → 새 리비전 복사가 막히지 않고 확정 검사가 행에 알림 | 단종 특성을 빼는 개정판을 만들 길이 있어야 한다 |
| Q1-6 | **확정·폐기 검사계획이 쓰는 샘플링 계획은 방식·수치 불변**(이름·순서·사용 여부만) — 서비스 `SAMPLING_LOCKED` + 트리거 `ck_qms_sampling_plan_in_use`. 작성 중 계획만 쓰면 자유 | 샘플링이 바뀌면 확정본의 "그때 기준" 이 바뀐다. 샘플링은 행에 스냅샷하지 않고 참조를 잠근다(2부 측정은 로트마다 n·Ac·Re 를 남길 것) |
| Q1-7 | AQL 샘플링 계획은 **저장 때 표 1(표준·수준)·표 2(표준·엄격도·AQL) 줄이 있어야** 저장(`NO_AQL_LETTER`·`NO_AQL_PLAN`). 표 값 자체는 올리기로만 고치고 확정본 잠금 대상이 아님 | 오타(0.65 ↔ 0.6)를 판정 때가 아니라 저장 때. 표는 표준 원문이라 회사가 바꿀 일이 드묾 — 바뀌면 2부 측정 원장의 n·Ac·Re 스냅샷이 근거 |
| Q1-8 | AQL 표 1 로트 구간 겹침 = 배제 제약(사용 중 행만, **커밋 시점** — 올리기 한 번에 구간을 옮길 수 있게) + 올리기 검증이 기존 행(같은 키 제외)·파일 앞 행과 `AQL_OVERLAP`. 표 2 = 1회 샘플링(Re > Ac, Re ≤ n, Re = Ac + 1, 수월한 검사만 간격 → 판정 `ACCEPT_TO_NORMAL`) | ISO 2859-1 수월한 검사의 Ac·Re 간격 표현 |
| Q1-9 | 판정 함수: `SamplingPlan.sampleSize(lot)` → `Sample(n, Ac, Re, 문자, 전수)`, `Sample.judge(불량)`(+ `plan.judge(lot, 불량)`). 로트 1~10억. n ≥ 로트면 전수(같은 Ac·Re). 비율 = 올림, 최소 1. `InspPlanResolver.effective(품목, 유형, 공정, 날짜)` = 품목 > 클래스 → 같은 공정 > 공정 무관 → 대체 작은 것, 기간 [시작, 끝) | 2부는 이 두 함수만 부른다(GET `/api/qms/insp-plans/effective` 도 같은 경로) |
| Q1-10 | 4장 끝의 `RevisionedDoc` 공통 헬퍼는 **뽑지 않았다** — 검사계획은 라우팅 서비스 구조를 따로 따라 썼다(리비전 채번·숫자 검증만 `BomService` 정적 함수 재사용) | 헬퍼를 검사계획 하나만 쓰면 이름만 공통이다. BOM·라우팅·검사계획·관리문서(Q3)를 함께 옮기는 건 P2 병합 뒤 별도 작업 |

### 4.2 Q2a 상세 — 구현하며 정한 것 (2026-10-21, V070(임시 V9210))

| # | 정한 것 | 이유 |
|---|---|---|
| Q2a-1 | 계측기 = P1 물리 자산(GAUGE) + **칸 하나 `mst_phys_asset.gauge_class`**(남의 표 변경). 게이지만·육안(VISUAL) 아님(CHECK), 회사 코드는 새 참조만 검사(트리거 `fk_mst_phys_asset_gauge_class`) — 사용 중지된 회사 코드가 든 계측기도 다른 칸은 저장된다 | Q1-1 그대로. CMS 처럼 1:1 곁 테이블을 두면 칸 하나에 표가 하나 — 검사계획 행과 같은 코드로 "잴 수 있는 교정 유효 게이지" 를 찾는 2부 조회가 조인 하나 늘 뿐 이득이 없다 |
| Q2a-2 | 교정 기록 `qms_gauge_calib` = **원장 변형**(4b 설치 이력 모양): 추가 + 무효 1회(사유·누가·언제), 그 밖 UPDATE·DELETE 는 트리거 + REVOKE. `reversal_of_id` 를 두지 않는다(정정 = 무효 후 다시 넣기, 측정값 원장 6종 아님) | IATF 7.1.5.2.1 교정 기록은 품질기록 — 고치지 않는다. 역분개는 수량 합이 있는 원장용 |
| Q2a-3 | **다음 교정일 = 교정일 + 그때 주기(`cycle_days` 스냅샷)** — 생성 칸(STORED)이라 계산이 어긋날 수 없다. 주기를 비우면 계측기 기본 주기, 둘 다 없으면 `REQUIRED` | 주기 변경 뒤 과거 행 그대로(완료 기준). 서비스 계산이면 직접 SQL 이 다른 값을 넣을 수 있다 |
| Q2a-4 | **최근 교정일(D4)**: 교정 추가·무효의 AFTER 트리거가 `max(유효 교정일)` 로 맞추고 `row_ver` 를 올린다. 물리 자산 쪽 트리거는 유효 기록이 있으면 파생값과 다른 값을 거부(`ck_mst_phys_asset_calib_derived`)·유형 변경 거부(`ck_mst_phys_asset_gauge_calib`). 유효 기록 0건(전부 무효 포함)이면 NULL 로 돌리고 예전처럼 입력 허용 | 서비스만 맞추면 직접 SQL·자산 화면이 어긋난다. row_ver 를 올려야 교정 추가 전에 열어 둔 자산 화면 저장이 조용히 옛 날짜로 덮지 않고 409 |
| Q2a-5 | 같은 계측기의 교정 추가·무효는 BEFORE 트리거가 **자산 행 FOR UPDATE** 로 줄 세우고, 최근 교정일은 AFTER 트리거의 **다른 문장**에서 계산 | 같은 문장 안 집계는 잠금을 기다린 뒤에도 옛 스냅샷(SRM 교훈) |
| Q2a-6 | `GaugeStatus.of(사용, 최근 결과, 다음 교정일, 오늘, 임박 일수)` 우선순위 = INACTIVE → NO_RECORD → NG → OVERDUE → DUE_SOON → OK. **기한 당일까지 유효**(그날은 DUE_SOON). LIMITED 는 기한으로 판정 + 제한 내용을 상태 줄에. `Status.usable()` = OK·DUE_SOON — 2부가 막을 기준 | 최근 = 교정일 → id 순 마지막 유효 기록(소급 입력이 최근을 덮지 않음) |
| Q2a-7 | 목록 SQL 의 판정 조각(상태 조건 필터용)과 `GaugeStatus` 는 같은 규칙을 두 곳에 쓴다 — 시험 `목록_SQL_판정과_GaugeStatus가_같다` 가 경계 날짜 묶음으로 맞춰 본다. 상세는 Java 로 다시 계산 | 상태로 거르고 정렬하려면 SQL 이 필요하다. 2부는 Java 함수(`GaugeCalibService.status`)만 부른다 |
| Q2a-8 | 외부 기관 = 거래처(사용 중, 같은 회사 — 트리거), 사내면 거래처 없음(CHECK). 성적서 = 첨부(대상 `qms-gauge-calib` × `calib_id`) — 권한은 교정 화면 기준 | 교정 기관 마스터를 따로 만들지 않는다(9장 "교정 기관 연동 안 함") |
| Q2a-9 | 정책 `QMS_CALIB_DUE_SOON_DAYS`(기본 30, 0~365). 화면 `qms-gauge-calib`(SEARCH·SAVE·DELETE(무효)·EXCEL), 메뉴 품질 3번째 | 3장 정책 표 |

### 4.3 Q2b 상세 — 구현하며 정한 것 (2026-10-21, V071(임시 V9211))

| # | 정한 것 | 이유 |
|---|---|---|
| Q2b-1 | 연구 머리 `qms_msa_study` = 계측기(GAUGE) × 특성(선택) × 방법 AVG_RANGE / ATTRIBUTE × 측정자 2~3 · 부품 2~10(계수형 50) · 반복 2~3, 상태 DRAFT → DONE / CANCELLED. ANOVA 는 넣지 않음(D9 2차 — 같은 원자료로 계산만 더하면 된다) | 평균·범위법 계수 K1·K2·K3 가 반복 2~3·측정자 2~3·부품 2~10 까지라 모양을 그 범위로 막는다(계수형은 계수가 필요 없어 50) |
| Q2b-2 | 원자료 `qms_msa_data` = 한 칸 한 행(측정자 × 부품 × 반복, 수치 또는 OK/NG). 계수형 기준값 = **측정자 0 · 반복 1 행**(선택, 전부 있거나 전부 없거나). 작성 중 저장 = 머리 + 원자료 한 벌(지우고 다시 넣기), 빈 칸은 저장 안 함 | 칸 단위 행이라 확정 때 트리거가 "모양대로 다 찼나" 를 셀 수 있다. 기준값 표를 따로 두면 표 하나가 늘 뿐 |
| Q2b-3 | 계산은 순수 함수 `MsaCalc`(double 계산 → 6자리, %는 2자리 반올림). PROCESS 기준에 공정 σ 를 주면 TV = σ·PV = √(TV² − GRR²), 안 주면 연구 부품으로. TOLERANCE 는 % 분모 = 공차/6. ndc = ⌊1.41 · PV / GRR⌋(최소 1). AV² 가 음수면 0. 판정 = 정책 `QMS_GRR_OK_PCT`(10)·`QMS_GRR_LIMIT_PCT`(30) | 손 검산 예제 1 은 손으로 푼 값, 예제 2·3 은 정의대로 따로 짠 계산과 대조(표준 원문 예제 수치는 쓰지 않음 — D3 과 같은 판단) |
| Q2b-4 | **확정 = 결과 스냅샷**: `result_json`(전체 값) + 목록용 칸 `grr_pct`·`ndc`·`verdict` + 공차 기준이면 그 순간 특성 상한 − 하한(`tolerance`). 확정·취소 뒤 머리·원자료 불변(트리거), 확정에는 원자료가 모양대로 다 차야 한다(트리거가 다시 셈 + CHECK 결과 칸) | 특성 규격·정책 구간이 나중에 바뀌어도 "그때 판정" 이 남는다(검사계획 규격 스냅샷과 같은 원칙) |
| Q2b-5 | 상세는 작성 중이라도 원자료가 다 찼으면 **저장된 값으로 미리 계산**(preview, 저장 안 함) — 계산 못 하면 이유 코드(`MSA_INCOMPLETE`·`MSA_NO_TOLERANCE`·`MSA_NO_VARIATION`) | 확정 전에 결과를 보고 원자료 오기입을 찾게 |
| Q2b-6 | 화면 원자료 표 = 행(측정자 × 반복) × 열(부품), 계수형은 맨 위 기준값 행. **엑셀 같은 모양 붙여넣기**(탭·줄바꿈), 모양을 바꾸면 같은 칸(측정자-부품-반복) 값은 남기고 보이는 칸만 보낸다. 칸 오류는 행 키 `a-p-t` 로 그 칸에 표시 | 원자료는 보통 엑셀에 먼저 적는다(편집 그리드 PoC 전이라 hb.grid 아닌 입력 표) |
| Q2b-7 | 연구 번호 = 채번 `QMS_MSA`(회사에 규칙이 없으면 `MSA{yyMM}-{seq:3}` 를 만든 뒤), 화면 `qms-msa`(SEARCH·SAVE·APPROVE(확정)·EXCEL, 취소 = SAVE), 메뉴 품질 4번째 | 5장 화면 표 |

### 4.4 Q3 상세 — 구현하며 정한 것 (2026-10-21, V078(임시 V9212))

| # | 정한 것 | 이유 |
|---|---|---|
| Q3-1 | 표 `mst_doc`(번호·유형·이름, **번호·유형 고정**) + `mst_doc_target`(품목·품목 클래스·공정 중 하나, 한 벌 저장) + `mst_doc_rev`(DRAFT → IN_APPROVAL → RELEASED → OBSOLETE). 만들 때 A0 작성 중을 함께 만든다. 번호를 비우면 채번 `QMS_DOC`(규칙이 없으면 `DOC{yyMM}-{seq:4}`) | 다른 모듈이 유형(DRAWING·INSP_STD)으로 거르므로 유형이 바뀌면 안 된다 |
| Q3-2 | **배포본 하나·작성 중(결재 중 포함) 하나** = 부분 유일 인덱스 2개. 배포 = 문서 행 잠금 → 이전 배포본 폐기 → 이 리비전 배포 → 첨부 잠금 표시, 한 곳(`DocReleaser`)에서 결재 승인·바로 배포·PLM 적용이 같이 쓴다 | 순서를 바꾸면 부분 유일 인덱스에 걸린다. 결재 핸들러가 서비스에 기대면 순환(SRM 교훈) |
| Q3-3 | 결재 = `ApprovalDocType` `QMS_DOC`(bizKey = 리비전 id): 승인 = 배포, 반려·회수 = 작성 중. 정책 `QMS_DOC_APPROVAL`(BOOL, 기본 Y), N 이면 APPROVE 권한으로 바로 배포. 결재 처리 화면은 문서 화면 안(공통 결재 API 호출) — PLM ECO 화면과 같은 모양 | D7 |
| Q3-4 | 트리거: 작성 중에만 내용 변경, 전이 표(DRAFT → IN_APPROVAL·RELEASED·OBSOLETE, IN_APPROVAL → DRAFT·RELEASED, RELEASED → OBSOLETE), 배포·폐기 시각은 한 번 남으면 고정, 결재 중이면 결재 id 필수, 새 리비전 원본은 같은 문서, 삭제 금지 + REVOKE | BOM·검사계획 리비전 규칙과 같은 수준의 직접 SQL 방어 |
| Q3-5 | **새 리비전 = 배포본(없으면 최근 리비전) 복사 + 첨부까지 복사** — 공통 `FileService.copyAll(대상 유형, 원본 id, 새 id)`: 새 저장 키로 실제 파일 복사, 업무 트랜잭션 안에서만(MANDATORY), 롤백되면 복사한 파일을 지운다 | `sys_file.storage_key` 가 유일이라 행만 복사할 수 없다(26 9번 "리비전 복사 때 첨부까지") |
| Q3-6 | **첨부 잠금 = 공통 규칙**(상위 세션 결정 2026-10-21 — PMS·PLM D7 과 같은 요구가 세 번째): ① 앱 `FileLockRule` 빈(`FileReadGrant` 와 같은 모양)을 `FileService` 가 올리기·삭제 전(+ 메타 기록 트랜잭션 안에서 한 번 더) 묻고, 하나라도 잠기면 409 `FILE_LOCKED`. QMS 는 `DocFileLock` — 작성 중이 아니면(결재 중·배포·폐기·없는 리비전) 잠김. ② DB 최종 방어는 **범용 칸 `sys_file.locked_yn`**(한 방향) + 트리거 하나 — 잠긴 행은 사용 중지·내용 변경·해제·삭제 불가. 모듈이 근거로 굳힐 때(문서 배포) 찍는다 | 대상별 판단 함수를 공통 트리거에서 부르면 플랫폼이 모듈 표를 알아야 한다. 결재 중 → 반려처럼 **풀리는** 잠금은 앱 규칙, 되돌릴 수 없는 근거 고정만 DB 칸. PMS 의 기존 대상 한정 트리거는 그대로(공통이 main 에 들어오면 PMS 가 옮김 — 상위 세션 안내) |
| Q3-7 | 다른 모듈이 리비전을 가리키는 방식(PLM D8 열어 둠): **`doc_rev_id` FK**. PLM ECO 적용은 공개 메서드 `DocService.releaseFromChange(docRevId)`(MANDATORY, 작성 중만 — 결재 중이면 409, 행위자 없으면 SYSTEM), 확인은 `DocService.revision(id)`(유형·상태). 표를 직접 고치지 않는다 | BOM·라우팅 확정을 부르는 PLM 방식과 같게(skill 2026-10-20) |
| Q3-8 | 검사계획 머리 `doc_rev_id`(Q1 에 자리만 있던 칸): FK + 트리거(같은 회사·검사기준서·새로 걸 때 폐기본 아님). 연결은 작성 중 계획만(`PUT /insp-plans/{id}/doc`), 확정 뒤 고정(V047 불변 규칙), **새 리비전은 연결을 이어받되 그사이 폐기된 리비전이면 비운다** | 정합 점검 3번이 볼 연결이 화면에서 생겨야 한다 |
| Q3-9 | 정합 점검(`qms-consistency`, 읽기 전용) — 기준 = 오늘(회사 날짜) 유효한 확정 검사계획. ① 사용 중 특별특성(CC·SC·고객 기호)이 어떤 확정 계획 행에도 없음(사용 중지 품목·공정의 특성 제외) ② 확정 계획 대상(품목 / 품목 클래스 — 품목 계획이면 그 품목의 클래스도 / 공정)에 사용 중 검사기준서 배포본이 없음(계획에 직접 건 배포본도 인정) ③ 확정(오늘 유효)·작성 중 계획이 가리키는 리비전이 폐기됨. 점검마다 정렬 전 2,000건 상한 + truncated, 엑셀 | 26 9번 문서 간 정합. 결과는 저장하지 않는다(심사 때 다시 돌림) |

### 4.5 Q4 상세 — 구현하며 정한 것 (2026-10-21, V085(임시 V9213))

| # | 정한 것 | 이유 |
|---|---|---|
| Q4-1 | 8D = 텍스트 구역 D1~D8 + 단계 `OPEN → CONTAINED(D1~D3) → ANALYZED(D4) → ACTIONED(D5·D6) → VERIFIED(D7) → CLOSED(D8)`. "다음 단계" 버튼 하나, 그 단계에 필요한 구역이 비면 400 `QMS_CAPA_STEP_CHECK` + 칸 오류 목록(서비스), DB 는 단계별 CHECK `ck_qms_capa_d3·d4·d6·d7·d8`(빈칸·공백만 = 없음, `coalesce(length(btrim(x)),0) > 0`) | D8. 단계 표시가 곧 "어디까지 채웠나"라서 별도 진행률 칸을 두지 않는다 |
| Q4-2 | 종결 = 결재(`ApprovalDocType` `QMS_CAPA`, 정책 `QMS_DOC_APPROVAL` 을 관리문서와 같이 씀): VERIFIED → CLOSING(결재 중, 내용·수평전개·첨부 고정) → 승인 CLOSED / **반려·회수 = ACTIONED**(효과 확인부터 다시). 정책 N 이면 APPROVE 권한으로 VERIFIED → CLOSED. 종결 때 첨부 `sys_file.locked_yn = Y`(Q3 공통 칸) | 반려 사유는 대개 "효과 근거 부족" — 수평전개를 다시 열어야 하므로 VERIFIED 가 아니라 ACTIONED 로 돌린다. 정책을 따로 두지 않은 것은 "품질 문서 종결은 결재" 하나의 회사 판단이라서(D7) |
| Q4-3 | 취소 = 사유 필수, 종결 결재 중이 아니면 어느 단계에서나. 종결·취소 뒤 불변(트리거), 삭제 금지 + REVOKE | 잘못 접수한 건도 기록으로 남긴다(심사 추적) |
| Q4-4 | 수평전개 = 품목·공정·고객 + 상태(OPEN/DONE/NA), **한 벌 저장**(지우고 넣기, UPDATE 금지). VERIFIED 부터 잠금(트리거 `ck_qms_capa_spread_locked`), VERIFIED 로 가려면 OPEN 행 없음(서비스 칸 오류 `CAPA_SPREAD_OPEN` + 별도 트리거 `ck_qms_capa_spread_open`). 2장 초안의 PLANT 대상은 뺐다(공장 마스터가 MES 쪽에서 아직 움직임 — 2부) | 효과 확인은 "다른 곳에도 퍼졌는지 확인했다"를 포함한다 |
| Q4-5 | 기한 = 만들 때 스냅샷: 발생일 + 정책 일수(`QMS_CAPA_CONTAIN_DAYS` 2·`QMS_CAPA_CLOSE_DAYS` 60). **고객 클레임 + 고객이 있으면 그 고객 CSR override 가 이긴다**: 사용 중·발생일에 시작한 CSR 중 CONTAIN_DAYS·CLOSE_DAYS 가 있는 것, 품목(또는 품목 클래스)이 맞는 CSR → 대상 없는 CSR, 그다음 늦게 시작한 것 **하나**. 그 CSR 에 없는 키는 정책. 근거 `csr_id` 저장(화면 "기한 근거 CSR"). 출처·기한·근거는 트리거로 고정 | CSR 을 고쳐도 지난 8D 기한이 바뀌면 지연 판정을 사후에 조작할 수 있다. 여러 CSR 을 키별로 섞지 않는 것은 "어느 문서 기준인지" 한 줄로 설명하려고 |
| Q4-6 | 기한 판정(조회 때 계산, 저장 안 함): 종결·취소 = 없음, 오늘 > 종결 기한 = `CLOSE_OVERDUE`, 접수(OPEN) 상태로 오늘 > 봉쇄 기한 = `CONTAIN_OVERDUE`, 그 밖 `ON_TIME`. 목록 필터·엑셀 | 봉쇄를 끝낸(CONTAINED 이상) 건은 봉쇄 기한을 더 보지 않는다 |
| Q4-7 | CSR = 거래처 × 문서 번호 × 리비전(유일) + 시작일 + override(jsonb, CHECK 로 정해진 4키만 — 값 1~상한 정수는 서비스 칸 오류) + 적용 품목/품목 클래스(없으면 그 고객 전체, 한 벌 저장) + 첨부. 거래처는 만든 뒤 고정(트리거). NOTIFY_HOURS·RETENTION_YEARS 는 지금은 저장·표시만 — 알림·보존 엔진 연결은 2부 | 26 M11 "고객별 요구가 회사 기본을 이긴다". 키를 열어 두면 아무 값이나 들어와 쓰는 곳이 없는 설정이 된다 |
| Q4-8 | 2부 NCR 연결 = `ncr_id` 칸만(FK 없음), 4M 변경(`qms_change`, PLM ECO 1:1)은 2부로 미룸(상위 세션 결정) | 1부 범위 유지 |

## 5. 화면

| 화면 | prog_cd | 액션 | 메모 |
|---|---|---|---|
| 샘플링 계획 · AQL 표 | `qms-sampling` | SEARCH·SAVE·EXCEL | AQL 표는 엑셀 올리기(템플릿) + 조회, 판정 미리보기("로트 1,200개 → n=80, Ac 2") |
| 검사계획 관리 | `qms-insp-plan` | SEARCH·SAVE·APPROVE·DELETE·EXCEL | BOM 관리 화면 구조(목록 + 머리 + 행 표), 특성 고르기는 품목·공정으로 걸러서, 규격 다름 배지, 비교(리비전 간) |
| 계측기 교정 | `qms-gauge-calib` | SEARCH·SAVE·DELETE(무효)·EXCEL | 게이지 목록(상태 배지·다음 교정일·임박/초과 필터) + 교정 이력 + 성적서 첨부 |
| GRR 연구 | `qms-msa` | SEARCH·SAVE·APPROVE | 원자료 표(측정자 × 부품 × 반복, 엑셀 붙여넣기), 계산 결과 + 판정 구간 |
| 관리문서 | `qms-doc` | SEARCH·SAVE·APPROVE·DELETE | 리비전 목록, 결재 상신, 배포본만 "현행" |
| 품질 정합 점검 | `qms-consistency` | SEARCH·EXCEL | 3종 점검 결과(대상·이유·바로가기) — 읽기 전용 |
| 시정조치(8D) | `qms-capa` | SEARCH·SAVE·APPROVE·EXCEL | 목록(단계·기한 판정 필터) + 상세: 기한 상태 줄(근거 CSR) · D1~D8 구역 · 수평전개 표 · 종결 결재 · 첨부 |
| 고객 요구사항(CSR) | `qms-csr` | SEARCH·SAVE | 거래처별 목록(표 한 장), override 4칸, 적용 품목·품목 클래스, 첨부 |

메뉴 그룹: 새 상위 메뉴 "품질"(QMS) — 기존 기준정보의 특성·불량 유형·용어는 그대로 두고 품질 메뉴에서도 바로가기(8장 D6).

## 6. P2 와 병행 — 겹치는 곳과 규칙

| 대상 | 겹침 | 규칙 |
|---|---|---|
| Flyway `V###` | P2 2단계 이후와 다음 번호가 겹친다 | 이 작업 폴더(브랜치 `worktree-p3-qms`)에서는 다음 번호로 개발하고, **main 에 늦게 들어가는 쪽이 병합 직전 번호를 다시 매긴다**(P1 3c `V018 → V019` 선례). 이미 적용된 로컬 DB 는 `flyway repair` 대신 `docker compose down -v` 로 초기화 |
| `messages*.properties`, `HbErrorCode`, `Policies`, `DemoDataSeeder`, `sys_prog`·메뉴 시드 | 양쪽이 끝에 줄 추가 | 병합 때 양쪽 다 남김. QMS 키는 `qms.` / `js.qms` 접두어로 모아 충돌 줄을 줄인다 |
| `TESTING.md`, SKILL.md §2·§3, docs/00·01 표 | 양쪽이 기록 추가 | 단계 기록은 각자 절, 병합 때 둘 다 |
| 공통 JS(`hb.*`)·CSS | QMS 가 공통을 고칠 일이 생기면 | **공통은 고치지 않는다** — 필요하면 화면 JS 안에서 해결하고 공통화는 병합 뒤 |
| P2 테이블(`prd_*`·`lot_*`·`sal_*`) | 1부는 읽지도 않는다 | 2부 착수 때 P2 병합본 위에서 |
| P2 착수 게이트 확장점 | 2부에서 품질 게이트를 붙일 곳 | 1부는 `GaugeStatus`·`InspPlanResolver` 만 만들어 둔다 |

## 7. 진행

| 단계 | 상태 | 날짜 | 테스트 |
|---|---|---|---|
| Q1 검사 기준 | ✅ (390 상세 서랍 화면 미확인) | 2026-10-20 | 전체 799 통과(신규 87: 계산 68 + API·이상 사용·올리기 19) — V047, 병합 때 번호 재부여(main 은 V035+) |
| Q2a 계측기 교정 | ✅ | 2026-10-21 | 전체 1,175 통과(신규 27: 판정 18 + API·이상 사용 9) + 시더 1 — V070(임시 V9210) |
| Q2b MSA | ✅ | 2026-10-21 | 전체 1,190 통과(신규 14: 손 검산 6 + API·이상 사용 8) — V071(임시 V9211) |
| Q3 관리문서·정합 점검 | ✅ | 2026-10-21 | 전체 1,339 통과(신규 10: DocApiTest) — V078(임시 V9212) |
| Q4 시정조치·CSR | ✅ | 2026-10-21 | 전체 1,429 통과(main 병합 뒤, 신규 10: CapaApiTest) — V085(임시 V9213) |
| (2부) Q5 4M 변경 | ✅ | 2026-10-21 | 전체 1,465 통과(PLM Q5 계약 병합 뒤, 신규 7: ChangeApiTest) — V094(임시 V9214) |
| (2부) Q6 검사 실행·측정값 원장 | ✅ | 2026-10-21 | 전체 1,534 통과(main 병합 뒤, 신규 13: InspJudgeTest 5 + InspApiTest 8) — V099(임시 V9215) |
| (2부) Q7 초품(셋업 검증) | ✅ | 2026-10-21 | 전체 1,546 통과(main 병합 뒤, 신규 6: SetupApiTest 5 + InspApiTest 동시 입력 1) — V104(임시 V9216) |
| (2부) Q9a 교정 NG 영향 LOT(읽기만) | ✅ | 2026-10-21 | 전체 1,590 통과(main 병합 뒤, 신규 2: GaugeImpactTest) — 마이그레이션 없음 |
| (2부) Q10 SPC 최소형 | ✅ | 2026-10-21 | 전체 1,603 통과(main 9870a0d 병합 뒤, 신규 8: SpcCalcTest 5 + SpcApiTest 3) — V106(임시 V9217) |
| (2부) Q8a 부적합(NCR)·처분·자동 보류·특채 결재 | ✅ | 2026-10-21 | 전체 1,638 통과(main 32bf7df 병합 뒤, 신규 6: NcrApiTest) — V112(임시 V9218) |
| (2부) Q8b 봉쇄(같은 품목·기간 LOT 일괄 보류) | ✅ | 2026-10-22 | 전체 1,642 통과(main 3fdc283 위, 신규 4: ContainApiTest) — V113(임시 V9219) |
| (3차) 4M 임시 변경 복귀 확인·변경 효과 확인(51장) | ✅ (브랜치 `claude/qms-wave3`, V246 — main 병합 2026-11-09) | 2026-11-09 | 전체 3,186 통과(main 45827aad 위, 신규 12: ChangeEffectCalcTest 4 + ChangeRecordApiTest 8) — V246·임시 V9200(병합 때 재번호) |

## 8. 결정 필요 (사용자)

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D1 | 검사계획 리비전 | **둔다**(BOM 규칙 그대로, 확정 때 특성 규격 스냅샷) — 특성은 리비전 없음(21)이라 "그때 기준"을 계획이 책임진다 | 리비전 없이 감사 이력만 — 측정값마다 규격 스냅샷은 되지만 "어떤 계획 판으로 검사했나"를 못 묶는다 |
| D2 | 검사계획 대상 | **품목 또는 품목 클래스**(수입검사는 원자재 군 단위가 흔함 — 7b 품목 클래스 재사용), 둘 다 있으면 품목이 우선 | 품목만 — 원자재 수백 개에 같은 계획을 반복 |
| D3 | AQL 표(ISO 2859-1 / KS Q ISO 2859-1) | **시드로 넣지 않는다** — 사용자가 엑셀로 올림(표준 원문 저작권, 02 의 AP 표와 같은 판단). 테스트·데모는 손으로 만든 작은 가짜 표 | 공개된 표를 시드로 — 편하지만 표준 원문 복제 위험 |
| D4 | P1 `mst_phys_asset.last_calib_date` | **교정 기록이 생기면 읽기 전용 파생값**: 교정 저장·무효 때 서비스가 같은 트랜잭션에서 갱신하고, 물리 자산 화면에서는 직접 수정 막음(교정 기록 0건인 이전 데이터만 입력 허용) | 컬럼 폐기하고 조회 때 계산 — P1 화면·엑셀·QA 테스트를 고쳐야 해서 P2 병행 중엔 비쌈 |
| D5 | 교정 NG·기한 초과 계측기 | **판정은 1부(`GaugeStatus`), 막는 것은 2부**(측정값 입력·품질 게이트). 1부 화면은 배지 + 목록만. NG 등록 때 "영향 LOT 찾기"는 2부 측정값 원장이 생기면 같은 화면에 버튼 추가 | NG 등록 때 계측기 자동 사용 중지 — 수리 후 재교정 흐름이 사용 중지·다시 사용과 섞임 |
| D6 | 메뉴 | **새 상위 메뉴 "품질"** + 특성·불량 유형은 기준정보에 그대로 두고 품질에도 바로가기 | 특성·불량 유형을 품질로 이동 — P1 화면 이동이라 P2 병행 중 충돌 |
| D7 | 관리문서 결재 | **결재 사용(정책 `QMS_DOC_APPROVAL` 기본 Y)**, 8D 종결도 결재 | 결재 없이 APPROVE 권한만 — 단순하지만 P0 결재의 첫 업무 사례를 놓친다 |
| D8 | 8D 범위 | **텍스트 구역 D1~D8 + 기한 + 수평전개 + 첨부 + 종결 결재**까지. 원인 분석 도구(5Why·특성요인도 편집기)는 하지 않음, AI 초안은 P5 | 구조화 입력(팀원 표·원인 트리) — 화면 비용 큼 |
| D9 | GRR 계산 방법 | **AVG_RANGE(평균·범위법) 먼저**, ANOVA 는 같은 원자료로 2차, 계수형은 일치율(%)만 | 처음부터 ANOVA — 상호작용항까지 정확하지만 손 검산 예제 준비가 더 든다 |
| D10 | Flyway 병합 순서 | **먼저 끝난 쪽이 먼저 main**, 나중 쪽이 번호 재부여(6장) | 번호 대역 예약(QMS = V100~) — Flyway 는 이미 적용된 최대 번호보다 낮은 새 파일을 거부(outOfOrder 꺼짐)라 대역 예약은 맞지 않음 |

## 9. 하지 않을 것 (1부)

- 측정값·검사 실행·판정 원장, 품질 게이트, 초품/종품, NCR·MRB·처분, 4M 변경, SPC — **2부**(P2 LOT·지시 필요)
- PFMEA 트리·관리계획서 행 편집기·PPAP 18요소 — **P6 APQP**(00). Q3 는 문서 파일 + 리비전 + 정합 점검까지
- AQL 표 시드, 교정 기관 연동, 계측기 바코드 출력
- 기존 BOM·라우팅 코드를 공통 헬퍼로 옮기기(병합 뒤 별도)
- 고객 포털·협력사 포털(8D 를 고객이 직접 보는 것)

## 10. 검토 결과 (원래 세션이 작성)

2026-10-20 사용자: "D1~D10 추천대로 채택하고 Q1 검사 기준부터 구현".

| # | 결정 | 반영 위치 |
|---|---|---|
| D1 | 검사계획에 리비전(BOM 규칙), 확정 때 특성 규격 스냅샷 | 2장 Q1, 3장, 4장 Q1 |
| D2 | 대상 = 품목 또는 품목 클래스, 둘 다면 품목 우선 | 4장 Q1 `qms_insp_plan` |
| D3 | AQL 표는 시드 없음 — 사용자 엑셀 올리기, 테스트·데모는 가짜 작은 표 | 4장 Q1 |
| D4 | `last_calib_date` = 교정 기록이 생기면 서비스가 갱신하는 파생값(직접 수정 막음) | Q2a 착수 때 상세 |
| D5 | 계측기 판정은 1부 `GaugeStatus`, 차단은 2부 | Q2a, 2부 |
| D6 | 새 상위 메뉴 "품질", 특성·불량 유형은 기준정보에 그대로 + 바로가기 | 5장 |
| D7 | 관리문서·8D 종결 결재(정책 `QMS_DOC_APPROVAL` 기본 Y) | Q3·Q4 |
| D8 | 8D = 텍스트 구역 + 기한 + 수평전개 + 첨부 + 종결 결재 | Q4 |
| D9 | GRR 평균·범위법 먼저, ANOVA 2차, 계수형은 일치율 | Q2b |
| D10 | Flyway 는 먼저 끝난 쪽이 먼저 main, 나중 쪽이 번호 재부여 | 6장 |

## 11. 2부 — PLM 과 맞닿는 경계 (착수 전 합의안, 2026-10-21)

> 상태: **제안**(코드·마이그레이션 없음). PLM 12.7 의 3d("`qms_change`(1:1)·초품 입력 목록")가 QMS 2부를 기다리므로, 2부 착수 전에 PLM 과 맞닿는 곳만 먼저 정한다. 나머지 2부 설계(검사 실행·측정값 원장·게이트·NCR·SPC)는 P2 병합본 위에서 따로 쓴다.

### 11.1 이미 정해진 것 (다시 묻지 않음)

| 근거 | 내용 |
|---|---|
| docs/39 D1 (사용자 2026-10-20) | 계보·LOT 에 남기는 "변경" 키는 **ECO 하나**(`plm_eco.eco_id`). QMS 4M 은 ECO 의 1:1 확장 표 `qms_change` |
| docs/39 12.6 Q4 | **고객 승인은 ECO 칸만** 쓴다 — QMS 에 고객 승인 칸을 두지 않는다. PPAP 판정이 ECO 고객 승인을 채운다(14.4) |
| docs/39 12.4·13장 | 변경 표시 원본 = `plm_eco_lot`(FIRST_LOT·4M_WINDOW·TEMP), ECO 칸 `change_kind`(DESIGN·PROCESS_4M·TEMP)·`mark_lots`·`mark_until`, 대상 유형 EQUIP·PROC |
| docs/39 14.5 | PCN 은 PLM(`plm_pcn`). QMS 에 `qms_pcn` 을 만들지 않는다 |
| docs/39 12.6 Q5 | 출하 게이트(미승인 변경 LOT)는 WMS 2부 — `EcoTrace` 로 본다. QMS 는 관여하지 않음 |
| Q3-7 | 관리문서 리비전 배포는 `DocService.releaseFromChange(docRevId)` 만 — PLM 이 QMS 표를 직접 고치지 않음 |

### 11.2 누가 무엇을 갖나

| 대상 | 주인 | 다른 쪽이 하는 일 |
|---|---|---|
| 변경 머리(번호·제목·유효일·대상·결재·고객 승인·상태 DRAFT→PENDING→APPROVED→RELEASED) | PLM `plm_eco` | QMS 는 FK 로 가리키고 계약으로 읽기만 |
| 4M 세부: 4M 유형(MAN·MACHINE·MATERIAL·METHOD·MEASURE·ENV·SUPPLIER·SITE, 복수), 변경 전/후 서술, 영향 평가(특성·검사계획·관리문서·계측기), 임시 변경의 복귀 조건 | QMS `qms_change` (PK = FK `eco_id`) | PLM 은 모름 — ECO 화면에 요약만(11.3 ③) |
| 영향 특성·검사계획 행 | QMS `qms_change_char` (`eco_id` × `qms_char` 또는 검사계획 리비전) | — |
| 변경 뒤 LOT 표시 | PLM `plm_eco_lot` | QMS 는 입력 목록으로 읽음 |
| 초품(셋업 검증) 판정 | QMS `qms_setup_verif` (`eco_id` × `lot_id` 유일, 결과 OK/NG·측정 근거·판정자) | PLM 은 ECO "적용 뒤" 탭에 결과 요약만(계약 ③) |
| 문서 개정(작업표준서·관리계획서·검사기준서) | QMS 관리문서 | ECO 대상 DOC 는 지금 DRAWING 만(39 15장). 4M 이 WI·CP 를 바꿀 때는 **PLM 이 유형 검사만 넓힘**(39 15장 끝 "유형 검사만 넓힌다") |
| NCR·8D → 변경 요청 | QMS 가 연결 칸을 가짐(`qms_ncr.ecr_id`, 8D 는 2부에 `qms_capa_ecr` 연결 표 — 8D 하나가 ECR 여럿) | PLM ECR 표는 바꾸지 않음 |

### 11.3 계약 (3a 와 같은 모양 — 인터페이스 + 빈 없으면 NONE)

| # | 계약 | 제공 | 쓰는 쪽 | 내용 |
|---|---|---|---|---|
| ① | `EcoLookup.eco(ecoId)` → `EcoView(ecoId, ecoNo, title, changeKind, status, effectiveDate, itemIds, equipIds, procIds, custApprovalStatus)` | PLM (`module.plm.link`) | QMS 4M·초품 화면·서비스 | QMS 매퍼가 `plm_*` 를 SELECT 하지 않게(39 12.5 "MES 가 plm_* 를 직접 조회 = 위반" 과 같은 규칙). 다른 회사·없는 ECO = 빈 값 |
| ② | `EcoTrace.markedLots(ecoId)` → `(lotId, reason, seqNo, occurredAt)` 목록 | PLM (기존 `EcoTrace` 에 메서드 추가) | QMS 초품 | 초품 대상 = FIRST_LOT 행(4M_WINDOW·TEMP 는 선택 — 11.5 Q-d) |
| ③ | `EcoQualityView.summary(ecoId)` → 4M 유형·영향 특성 수·초품 OK/NG/미판정 수 | QMS (`module.qms.link`) | PLM ECO 상세 | 빈 없으면 NONE(QMS 없는 설치본에서 ECO 화면 그대로) |
| ④ | `EcoSubmitRule.check(ecoId)` → 칸 오류 목록 (여러 빈) | 인터페이스 = PLM, 구현 = QMS | PLM ECO 상신 `beforeSubmit` | `FileLockRule` 과 같은 모양. QMS 구현: `change_kind = PROCESS_4M·TEMP` 인데 `qms_change` 가 없거나 4M 유형이 비면 상신 거부 — 회사 정책 `QMS_4M_REQUIRED`(기본 Y). PLM 이 QMS 를 알 필요 없음 |

### 11.4 수명 규칙

- `qms_change`·`qms_change_char` 는 **ECO 가 DRAFT 일 때만** 저장(서비스 + 트리거가 `plm_eco.status` 를 읽어 거부 — DB 트리거의 PLM 표 읽기는 FK 검사와 같은 급으로 허용, 앱 코드는 계약 ①). ECO 상신 뒤 고정 = ECO 고정 규칙(`ck_plm_eco_fixed`)과 같은 시점. 반려·회수로 DRAFT 가 되면 다시 열림.
- **상태는 `SELECT status FROM plm_eco WHERE eco_id = ? FOR SHARE` 로 읽는다**(서비스·트리거 모두, PLM 요청 2026-10-21). 그냥 SELECT 면 동시 상신 때 "상신 검사(④)는 4M 없음을 못 봤는데 결재 대상은 바뀐 4M" 이 될 수 있다. PLM 상신은 `plm_eco FOR UPDATE` 뒤 ④ 를 부르므로 둘이 같은 ECO 행에서 줄을 선다. 잠금 순서는 양쪽 모두 `plm_eco` → `qms_*` — 교착 없음.
- 같은 회사 확인(트리거), `change_kind = DESIGN` ECO 에도 `qms_change` 를 붙일 수 있음(설계 변경이 공정 4M 을 동반할 때) — 강제는 ④ 가 PROCESS_4M·TEMP 에만.
- ECO 취소(CANCELLED) 시 `qms_change` 는 그대로 남김(기록), 초품 행은 생기지 않음(표시가 없으므로).
- 초품 판정은 원장 규칙(추가만 + 정정은 무효 행) — 2부 측정값 원장과 같은 규칙으로. NG 면 2부 NCR 을 여는 버튼(자동 생성 아님).
- 계보 `lot_genealogy.change_id` 와 `plm_eco_lot` 는 QMS 가 쓰지 않는다(읽기만, 계약 ②).

### 11.5 PLM 세션·사용자와 합의할 것

| # | 상대 | 질문 | 제안 |
|---|---|---|---|
| Q-a | PLM | 계약 ①·② 를 PLM 이 먼저 main 에(3a 처럼 인터페이스 + 구현) | **PLM 답(2026-10-21): 예.** ① `EcoLookup.eco(ecoId)` → `Optional<EcoView>`(11.3 칸 + `markLots`·`markUntil`·`docRevIds`), ② `EcoTrace.markedLots(ecoId)` → `List<MarkedLot(lotId, lotNo, reason, seqNo, occurredAt)>` 도착 순(이유 필터는 QMS 쪽), ④ 인터페이스 — PLM 이 마이그레이션 없는 작은 PR 로 먼저. ③ 은 ECO 상세 응답의 `quality` 칸(빈 없으면 null) |
| Q-b | PLM | 상신 확장점 ④ `EcoSubmitRule` 를 PLM 이 인터페이스로 두고 `beforeSubmit` 에서 모든 빈을 부를지 | **PLM 답: 예.** `EcoService.submit` 안 `lockDraft`(`plm_eco FOR UPDATE`) 뒤 기존 검사 → 모든 규칙 `check(ecoId)` 결과를 같은 칸 오류 목록에 합침(예외 대신 칸 오류, rowKey 예 `qms4m`, 읽기만, 같은 트랜잭션), 다시 상신도 같은 경로. 조건 = QMS 쪽 `FOR SHARE` 읽기(11.4) |
| Q-c | PLM | ECO 대상 DOC 유형을 WI·CP·INSP_STD 로 넓히는 시점 | **PLM 답: QMS 2부 4M 착수 때 PLM 이 V 하나로.** PLM 의견 요청 "WI·CP 는 공정·품목 클래스에 걸린 문서도 있으니 DOC 대상은 품목 선택(필수 아님)으로?" → **QMS 의견: 찬성, 단 둘을 붙여서.** (1) DOC 대상만 품목 선택 — 문서의 적용 대상은 `mst_doc_target`(품목·클래스·공정)이 원본이라 ECO 쪽에 품목을 강제하면 같은 정보를 두 번 적게 된다. (2) LOT 표시 판정은 DOC 대상을 보지 않으므로(13장 — 품목·리비전·설비·공정), 4M ECO 가 LOT 을 표시하려면 품목이 있는 PROC·EQUIP 대상을 따로 둔다 — ④ QMS 규칙이 "PROCESS_4M 인데 표시 가능한 대상 없음" 을 칸 오류로 알린다. (3) 품목을 적었으면 그 품목(또는 그 클래스)이 문서 적용 대상에 있는지는 경고만(막지 않음) |
| Q-d | 사용자 | 초품 대상에 4M_WINDOW·TEMP 표시 LOT 까지 넣을지 | **채택(사용자 2026-10-21): 첫 LOT(FIRST_LOT)만.** 4M 구간 LOT 은 초품 대상에 넣지 않는다 — 구간 추적은 PLM `plm_eco_lot` 표시가 맡는다 |
| Q-e | 사용자 | `QMS_4M_REQUIRED` 기본값 | **채택(사용자 2026-10-21): Y** (IATF 8.5.6 — 공정 변경은 영향 평가 기록 필수) |

### 11.6 하지 않을 것

- ECO 머리·결재·고객 승인 칸 복제, PCN·PPAP 의 QMS 쪽 표
- QMS 가 `plm_eco_lot`·`lot_genealogy.change_id` 에 쓰기
- 4M 을 ECO 없이 따로 시작하는 흐름(키가 둘로 갈라짐 — D1)

## 12. 2부 단계 계획 (2026-10-21 착수 — P2 완료·PLM 계약 main 반영 뒤)

> 1부와 같은 원칙: 참조되는 것 먼저, 단계마다 화면·API·시험(정상 + A~K + A사로 B사 + 직접 SQL)까지 끝낸 상태로 커밋, 끝나면 7장 표에 ✅. 1부 표는 고치지 않는다(0장 원칙 — 1부가 남긴 자리 `qms_insp_plan.doc_rev_id`·`qms_capa.ncr_id`·`GaugeStatus`·`InspPlanResolver`·`SamplingPlan` 을 쓴다).

| 단계 | 내용 | 기대는 것 | 비고 |
|---|---|---|---|
| **Q5** | **4M 변경**: `qms_change`(PK = `eco_id`, ECO 1:1) + `qms_change_char`(영향 특성·검사계획), 상신 규칙 `Qms4mSubmitRule`(④, 정책 `QMS_4M_REQUIRED` 기본 Y), ECO 상세 요약 `EcoQualityView`(③, 4M 칸 — 초품 수는 Q7), 화면 `qms-change` | PLM ①④, 11장 | 착수 때 PLM 이 DOC 대상 확장 V(16.1) — 상위 세션에 알림 |
| Q6 | **검사 실행·측정값 원장**: 검사 1건(수입·공정·출하, LOT, 적용 검사계획 리비전 = `InspPlanResolver`, 샘플 수 = `SamplingPlan`) + 측정값 원장(추가만, 정정 = 무효 행) + 판정(계량 규격·계수 Ac/Re), 계측기 `GaugeStatus` NG·기한 초과면 측정 거부(D5) | P2 LOT, 1부 Q1·Q2a | 원장 규칙(`occurred_at`·`recorded_at`) |
| Q7 | **초품(셋업 검증)**: `qms_setup_verif`(ECO × LOT, FIRST_LOT 만 — Q-d), 판정 근거 = Q6 검사 기록, `EcoQualityView` 초품 OK/NG/미판정 수 | PLM ②, Q6 | NG 면 NCR 여는 버튼(Q8) |
| Q8 | **NCR·MRB·처분·봉쇄**: 부적합 1건(LOT·불량 유형·수량·출처 검사), MRB 처분(그대로 사용·재작업·폐기·반품, 결재), LOT 보류·해제는 MES LOT 보류 경로(남의 표 — MES 합의), 8D 연결(`qms_capa.ncr_id` FK), ECR 연결 칸 | Q6, P2 LOT 보류 | 봉쇄(28 R1) = 같은 품목·기간 LOT 일괄 보류 제안 |
| Q9 | **품질 게이트·교정 NG 영향 LOT**: MES 착수 게이트 확장점에 초품 미판정·교정 만료 계측기 판정(QMS 가 구현 빈), 교정 NG 등록 → 지난 교정 이후 그 계측기로 잰 LOT 목록(Q6 원장) | P2 게이트 자리, Q6·Q7 | MES 와 확장점 모양 합의 먼저 |
| Q10 | **SPC 최소형**: Q6 측정값 위 X̄-R 관리도·Cp/Cpk·규칙 1·2 경보, 화면 하나 | Q6 | 계산은 순수 함수 + 손 검산 시험(1부 MsaCalc 와 같게) |

### 12.1 Q5 상세 — 구현하며 정한 것 (2026-10-21, V094(임시 V9214))

| # | 정한 것 | 내용 | 이유 |
|---|---|---|---|
| Q5-1 | 표 | `qms_change`(PK·FK `eco_id` → `plm_eco`, `comp_cd`, 4M 유형 배열 `m4_types varchar[]`(MAN·MACHINE·MATERIAL·METHOD·MEASURE·ENV·SUPPLIER·SITE, 1개 이상·중복 없음 CHECK), 변경 전·후·사유 서술, 영향 평가 서술, 임시 변경 복귀 조건, `row_ver`) + `qms_change_char`(`eco_id` × 특성 `char_id` 또는 검사계획 `insp_plan_id` 중 하나, 영향 서술, 한 벌 저장) | 11.2 |
| Q5-2 | 저장 시점 | **ECO DRAFT 일 때만** — 서비스는 `plm_eco … FOR SHARE` 로 상태를 읽고, 트리거도 같은 읽기로 최종 방어(11.4). 상신 뒤 고정, 반려·회수로 DRAFT 가 되면 다시 열림 | PLM 16장 잠금 순서 |
| Q5-3 | 상신 규칙 ④ | 정책 `QMS_4M_REQUIRED` = Y 이고 ECO `changeKind` 가 PROCESS_4M·TEMP 면: 4M 세부 없음 → `QMS_4M_MISSING`, 4M 유형 없음 → (CHECK 로 불가), **표시 가능한 대상 없음**(품목 있는 EQUIP·PROC 대상이 하나도 없음) → `QMS_4M_NO_MARK_TARGET`(16.1). rowKey `qms4m` | 16.1 합의 |
| Q5-4 | 화면 | `qms-change`: 목록(4M 세부가 있는 ECO) + "ECO 번호로 열기" → ECO 머리(계약 ①, 읽기 전용) + 4M 폼 + 영향 특성 표. ECO 가 DRAFT 가 아니면 읽기 전용. PLM ECO 상세의 `quality` 칸(③)에서 이 화면으로 링크 | PLM 화면은 PLM 이 고친다 |
| Q5-5 | 계약 보강(PLM, main 반영) | ① 에 **`byNo(ecoNo)`**(화면 "ECO 번호로 열기" → `GET /api/qms/changes/by-no`)·**`ecos(ids)`**(목록 ECO 머리 한 번에, 상한 `EcoLookup.MAX_IDS` 500 ≥ 목록 상한 200) — PLM 이 docs/39 16.2 로 추가 | QMS 매퍼가 `plm_eco` 를 SELECT 하지 않게(잠금용 `FOR SHARE` 읽기는 11.4 합의 예외) |
| Q5-6 | ECO 번호 사본 | `qms_change.eco_no` 를 INSERT 트리거가 `plm_eco` 에서 복사(이후 고정) — PLM 이 번호 변경을 막으므로(`plm_eco` 트리거) 어긋나지 않는다. 목록·검색이 PLM 을 부르지 않게 | 상태·제목은 바뀌므로 복사하지 않고 계약으로 |
| Q5-7 | 계약 ③ | 인터페이스는 **PLM** `module.plm.link.EcoQualityView`(`Summary(hasChange, fourMTypes, charCount, firstOk, firstNg, firstPending)`, PLM 결정 16.2), 구현은 `ChangeService` 하나. 초품 수는 Q7 전까지 null. **4M 이 필요 없는 ECO(설계 변경·정책 N)에 4M 세부도 없으면 빈 값** — ECO 화면에 품질 영역을 띄우지 않는다(필요한데 없으면 `hasChange = false` 로 보여 줌). QMS 쪽에 먼저 만든 같은 역할 인터페이스는 지웠다 | 한 계약에 인터페이스 하나 |
| Q5-8 | 다른 모듈 시험 | QMS 규칙(기본 Y)이 생기면 PLM 시험 중 4M·임시 ECO 를 4M 없이 상신하는 2개 클래스(`PlmEcoLotTest`·`PlmQmsContractTest`)가 막힌다 → 그 시험 준비 단계에 회사 정책 `QMS_4M_REQUIRED = N` 한 줄(PLM 시험 범위 밖이라는 주석). QMS 쪽 규칙은 `ChangeApiTest` 가 본다 | 남의 파일 변경 — PR 에 적음 |
| Q5-9 | 직렬화 시험 | ECO 행을 FOR UPDATE 로 쥔 트랜잭션(상신 흉내)이 있는 동안 4M 저장은 기다렸다가, 상대가 PENDING 으로 커밋하면 409 — 시험으로 확인(대기 ≥ 400ms) | 11.4 |

### 12.2 Q6 상세 — 검사 실행·측정값 원장 (2026-10-21, V099(임시 V9215))

| # | 정한 것 | 내용 | 이유 |
|---|---|---|---|
| Q6-1 | 표 3개 | `qms_insp`(검사 1건: 번호 채번 `QMS_INSP`, 유형, LOT, 품목·공정, 적용 검사계획, 로트 수량, 상태 OPEN/JUDGED/VOID, 결과 OK/NG, 검사 시각) · `qms_insp_line`(계획 행마다: 특성·데이터 유형·규격·계측기 클래스 **스냅샷** + 샘플 수 n·Ac·Re, 행 결과) · `qms_insp_meas`(측정값 **원장**) | 계획 행은 확정 뒤 불변이지만 "그때 무엇으로 판정했나" 를 검사 기록만으로 읽게 한다(1부 D1 과 같은 생각) |
| Q6-2 | 적용 검사계획 | 만들 때 `InspPlanResolver.effective(LOT 품목, 유형, 공정, 검사일)` — 없으면 400 `QMS_INSP_NO_PLAN`. 샘플 수는 행마다 `SamplingPlan.sampleSize(로트 수량)` | 1부가 남긴 판정 함수를 그대로 호출(0장 원칙) |
| Q6-3 | 로트 수량 | 정수 1~10억, 기본 = LOT 생성 수량 올림. 사람이 줄일 수 있다(부분 입고·분할) | 샘플링 표는 개수 기준 |
| Q6-4 | 측정값 원장 | 추가만(UPDATE·DELETE 트리거 거부 + REVOKE), `occurred_at`(잰 시각)·`recorded_at`·`recorded_by`. 값 = 계량은 숫자, 계수는 OK/NG. **정정 = 무효 행**(`void_of_id` 가 원래 행을 가리키는 빈 값 행) 뒤 같은 샘플 번호로 다시 입력. (행, 샘플 번호) 에 살아 있는 값은 하나(트리거가 검사 행을 잠그고 확인) | CLAUDE.md 원장 규약 |
| Q6-5 | 계측기 | 계측기 클래스가 VISUAL(육안)이 아니면 측정마다 게이지 자산 필수. 같은 회사·같은 클래스(트리거), **`GaugeStatus` 가 NG·기한 초과·교정 기록 없음·사용 중지면 거부**(400 `GAUGE_BLOCKED`, 서비스) — 1부 D5 "차단은 2부" | Q9 교정 NG 영향 LOT 가 이 칸으로 "그 게이지로 잰 LOT" 을 찾는다 |
| Q6-6 | 판정 | 순수 함수 `InspJudge`: 행마다 살아 있는 값 중 불합격 수(계량 = 규격 밖, 계수 = NG) → Ac 이하이고 샘플을 다 쟀으면 OK, Re 이상이면 바로 NG(다 재지 않아도), 그 밖 미정. 검사 "판정 확정" = 모든 행이 OK/NG 일 때만, 하나라도 NG 면 검사 NG. 확정 뒤 측정 고정 | 샘플링 표 의미 그대로(Ac/Re) |
| Q6-7 | 무효 | 검사 무효(VOID) = 사유 필수, 확정 전·후 모두(DELETE 권한). 무효 검사의 측정값은 남는다 | 잘못 연 검사도 기록 |
| Q6-8 | 화면 | `qms-insp`(메뉴 품질 맨 앞): 목록 표(유형·상태·결과, 상한 500) + 새 검사 줄(LOT 번호·유형·공정·로트 수량) + 행 카드(규격·n·Ac·Re·판정 미리보기, 잰 샘플 칩·무효, **붙여넣기 → 다음 빈 샘플 번호부터**, 게이지 고름 — 막힌 게이지는 사유와 함께 고를 수 없게) + 판정 확정·검사 무효. 엑셀은 이번 범위 밖 | 검사대에서 캘리퍼 값을 줄로 붙여 넣는 흐름 |
| Q6-9 | 잠금 순서 | 측정 트리거·서비스·판정 확정 모두 **검사 머리 → 검사 행**(측정은 머리 FOR SHARE → 행 FOR UPDATE, 확정은 머리 FOR UPDATE). 처음 초안(행 → 머리)은 확정과 교착 가능 — 설계 검토에서 고침. 같은 샘플 동시 입력은 행 잠금 + 살아 있는 값 검사로 하나만(동시 시험은 아직 없음) | 3c 교착 교훈(skill) |
| Q6-10 | 원장 감사 | `qms_insp_meas` 는 감사 트리거 없이 `RetentionTest.AUDIT_EXEMPT` 에 근거와 함께(행 자체가 이력). 앱 계정은 UPDATE·DELETE 권한 자체가 없다(REVOKE) — 트리거는 소유자 경로의 마지막 방어 | 생산 실적 원장과 같은 규칙 |

### 12.3 Q7 상세 — 초품(셋업 검증) (2026-10-21, V104(임시 V9216))

| # | 정한 것 | 내용 | 이유 |
|---|---|---|---|
| Q7-1 | 대상 | ECO 가 표시한 LOT 중 **FIRST_LOT 만**(사용자 Q-d) — PLM 계약 `EcoTrace.markedLots(ecoId)` 에서 이유로 거른다. 설계 변경(DESIGN) ECO 도 첫 LOT 표시가 있으면 대상 | IATF 8.5.1.3 셋업 검증 |
| Q7-2 | 표 | `qms_setup_verif`(ECO × LOT 판정 1건: 근거 검사 `insp_id`, 결과 OK/NG = 그 검사 결과 복사, 메모, 상태 ACTIVE/VOID). ECO·LOT 마다 살아 있는 판정 하나(부분 유일), 정정 = 무효(사유) 뒤 다시 | 판정 근거를 Q6 검사 기록에 묶는다(측정값 원장까지 따라간다) |
| Q7-3 | 근거 검사 | 그 LOT 의 **판정 확정된** 검사(유형 무관 — 보통 초품 FIRST_OFF), 결과를 그대로 복사(트리거가 일치 확인) | 판정 두 번 하지 않기 |
| Q7-4 | 잠금 | `eco_id` 는 **FK 를 두지 않는다**(트리거가 잠금 없이 같은 회사·FIRST_LOT 표시 확인) — `plm_eco_lot` 과 같은 이유: MES 표시 경로는 LOT → ECO(FOR UPDATE) 순서라, ECO FK 의 KEY SHARE 를 먼저 쥐면 순서가 뒤집혀 교착 가능(docs/39 13.2). LOT·검사는 FK | 3c 교착 교훈 |
| Q7-5 | 요약 계약 ③ | `EcoQualityView.Summary` 의 `firstOk`·`firstNg`·`firstPending`(= FIRST_LOT 표시 수 − 살아 있는 판정 수)을 채운다. 4M 세부가 없어도 **첫 LOT 표시가 있으면** 요약을 돌려준다(Q5-7 조건에 "또는 첫 LOT 표시" 추가) | ECO 화면에서 초품 진행이 보이게 |
| Q7-6 | 화면 | `qms-setup`: 최근 판정 목록 + ECO 번호로 열기 / `?ecoId=` → 첫 LOT 표마다 판정(근거 검사 고름 → 기록, 무효). NG 면 "부적합(Q8)" 자리만 표시 | 4M 변경 화면에서 링크 |
| Q7-7 | 동시성(Q6 보강) | 같은 검사 행·같은 샘플에 두 트랜잭션이 동시에 넣으면 둘째는 행 잠금을 기다렸다가 `uq_qms_insp_meas_live` 로 거부 — 시험 `InspApiTest` D 케이스(상위 세션 요청) | Q6-9 에서 "시험 없음" 이던 것 |

### 12.4 Q8 착수 전 — NCR·MRB·처분·봉쇄와 MES LOT 보류 경계 (제안, 2026-10-21)

> 상태: **MES 합의(2026-10-21)** — Q8-A~D·Q-f·Q-g 모두 동의, 보완은 아래 "MES 답 반영". MES 가 먼저 `lot_hold`·`LotHoldPort` 를 만들고(2026-10-21 착수), QMS Q8 은 그 뒤 착수. Q-h = Y(사용자 결정 2026-10-21).

**문제**: 지금 보류는 `lot_master.hold_yn`·`hold_reason` 칸 하나(MES `LotService.hold`, 사유 필수, 보류 LOT 은 분할·병합·투입의 부모 불가 — 트리거). 수동 보류·NCR 여러 건·봉쇄가 한 LOT 을 함께 잡으면 **하나를 풀 때 다른 보류까지 풀린다**.

| # | 제안 | 이유 |
|---|---|---|
| Q8-A | **보류 근거 원장은 MES 가 갖는다**: `lot_hold`(LOT × 출처 `MANUAL`·`QMS_NCR`·`QMS_CONTAIN` × 참조 id, 보류·해제 시각·사람·사유, 살아 있는 근거 하나씩). `lot_master.hold_yn` = 살아 있는 근거가 하나라도 있음(트리거가 맞춘다) — 기존 트리거·조회는 그대로 | MES 트리거가 `hold_yn` 을 보므로 원본은 MES 쪽에 있어야 한다. 근거가 여러 개여도 마지막 근거가 풀릴 때만 해제 |
| Q8-B | **계약** `module.production.link.LotHoldPort`(MES 제공, 빈 없으면 NONE): `hold(lotId, source, refId, reason)`·`release(lotId, source, refId, reason)`·`holds(lotId)`. 부르는 쪽 트랜잭션 안(MANDATORY), 같은 근거 두 번 보류는 그대로 성공(멱등) | QMS 가 `lot_master` 를 직접 고치지 않는다(PLM 계약과 같은 모양) |
| Q8-C | 잠금 순서: QMS 는 **LOT 행(MES) 먼저 → qms_ncr** — 포트가 `lot_master` 를 FOR UPDATE 로 잡는다. QMS 트리거가 LOT 을 잠그지 않는다 | 3c·Q7 교착 교훈 |
| Q8-D | 기존 화면 수동 보류·해제는 MES 가 출처 `MANUAL` 근거로 바꾼다(MES 쪽 작업) | 근거 하나 원칙 |

**QMS Q8 범위(합의 뒤)**: `qms_ncr`(번호 채번, 출처 = 검사·초품·고객 클레임·공정 중 발견, LOT·품목·불량 유형 `qms_defect`·수량, 상태 OPEN → DISPOSITION(MRB) → CLOSED / CANCELLED) + `qms_ncr_disp`(처분 행: 그대로 사용(특채)·재작업·폐기·반품·선별, 수량 합 = 부적합 수량). 만들 때 LOT 보류(정책 `QMS_NCR_AUTO_HOLD` 기본 Y), 종결 때 해제. **특채(그대로 사용)는 결재**(`ApprovalDocType` `QMS_NCR`). 8D 연결 = `qms_capa.ncr_id` 에 FK(1부가 남긴 자리, QMS 표). 봉쇄 = 같은 품목·기간 LOT 일괄 보류 제안 → 사람이 골라 `QMS_CONTAIN` 근거로. 재작업 처분은 기록만 — 실제 재작업은 MES LOT 재작업(7b)이 하고 NCR 은 결과 LOT 을 참조 칸으로.

| # | 상대 | 질문 | 제안 |
|---|---|---|---|
| Q-f | MES | Q8-A·B·D 를 MES 가 먼저(마이그레이션 하나 + 포트) | 예 — QMS Q8 착수 조건 |
| Q-g | MES | 해제 사유를 원장에 남기고 `hold_reason` 칸은 "살아 있는 근거들의 사유 요약" 으로 | 예(화면 표시 그대로) |
| Q-h | 사용자 | 부적합 등록 때 LOT 자동 보류 기본값 | Y — **사용자 결정 Y**(2026-10-21, 정책 `QMS_NCR_AUTO_HOLD` 기본 Y) |

**MES 답 반영 (2026-10-21, 상위 세션 경유)**

| # | 정한 것 | QMS 쪽 영향 |
|---|---|---|
| H-1 | 출처 4종: `MANUAL`·`MES_CORRECT`(POP 실적 정정 자동 보류, 참조 = 실적 id)·`QMS_NCR`·`QMS_CONTAIN`. MES 의 직접 `hold_yn` 갱신 경로는 없앤다 | 없음 |
| H-2 | 해제 권한은 출처별: MES 화면은 `MANUAL`·`MES_CORRECT` 만, `QMS_*` 는 QMS 처분·종결(포트)로만 — 서비스 + 트리거. 포트는 `QMS_*` 출처만 받는다 | NCR 종결·봉쇄 해제가 유일한 해제 경로 |
| H-3 | 표 `lot_hold`(LOT·출처·참조 id·사유·보류 시각/사람·해제 시각/사람/사유): UPDATE 는 해제 칸 한 번만, DELETE 금지, 살아 있는 근거 부분 유일(`comp_cd, lot_id, source, ref_id`, NULLS NOT DISTINCT), 해제 사유 필수. 참조 id FK 없음 | **`QMS_*` 참조(NCR·봉쇄)의 존재 검사는 QMS(포트 부르는 쪽) 책임** |
| H-4 | `lot_hold` 트리거가 `hold_yn`·`hold_reason`(살아 있는 근거 사유 요약)·`row_ver` 를 맞추고, `lot_master` 가드가 근거 없는 `hold_yn` 직접 변경을 막는다 — 기존 `hold_yn` 읽는 곳은 그대로 | 없음 |
| H-5 | `LotHoldPort`(`module.production.link`): `boolean hold(lotId, source, refId, reason, by)`(새로 잡으면 true, 같은 근거 다시 = false 멱등) / `boolean release(...)` / `holds(lotId)` / 일괄 `holds(Collection)`(상한 500). MANDATORY, 다른 회사·없는 LOT = 없음 예외. 잠금 `lot_master` FOR UPDATE → `lot_hold` | **봉쇄 일괄은 lot_id 오름차순으로 부른다**(교착 방지), QMS 는 LOT 다음에 `qms_ncr` |
| H-6 | 기존 보류 이관: `hold_yn = Y` 는 출처를 구분할 수 없어 전부 `MANUAL` 근거 1행 | 이관된 보류는 QMS 가 풀지 않는다 |

### 12.5 Q9a 상세 — 교정 NG 영향 LOT 조회 (읽기만, 2026-10-21, 마이그레이션 없음)

> Q9 중 MES 와 맞닿지 않는 부분만 먼저(상위 세션 승인). 착수 게이트 확장점(초품 미판정·교정 만료 계측기)은 MES 와 모양 합의 뒤.

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| Q9a-1 | 대상 | **NG 이고 무효가 아닌** 교정 행만(그 밖은 `CALIB_NOT_NG`). 다른 계측기의 교정 id 로 부르면 404 | 영향 범위는 "그 계측기가 틀렸다" 가 확정됐을 때만 의미 |
| Q9a-2 | 구간 | 시작 = 그 NG 교정일보다 **이전**의 마지막 유효(OK·LIMITED, 무효 아님) 교정일 0시(회사 시간대), 없으면 처음부터 / 끝 = NG 교정일 **다음 날 0시** 미만 | 마지막으로 맞다고 확인된 날 이후 측정은 모두 의심(같은 날 교정 전 측정 포함 — 보수적으로). 날짜만 있는 교정이라 시각 경계는 하루 단위 |
| Q9a-3 | 근거 | Q6 측정값 원장 `qms_insp_meas` 에서 그 계측기로 잰 **살아 있는** 값(무효 행 자신·무효된 원래 행 제외), 검사 × LOT 로 묶어 측정 수·첫/마지막 측정 시각 | 무효된 값은 판정에 안 쓰였다. 원장 시각 `occurred_at` 기준 |
| Q9a-4 | 상한 | 1,000행(검사 단위), 넘으면 `truncated` 표시. LOT 수는 서로 다른 LOT 기준 | 화면 한 번 조회 크기. 기존 인덱스(계측기 × 측정 시각)로 충분 — 마이그레이션 없음 |
| Q9a-5 | 쓰기 없음 | 조회만(권한 `qms-gauge-calib` 조회). 보류·NCR·고객 통보 같은 후속 조치는 Q8(NCR)·Q9 게이트 뒤 | MES LOT 보류 경계(12.4) 합의 전이라 |
| Q9a-6 | 화면 | `qms-gauge-calib` 교정 이력의 NG 행에 "영향 LOT" 버튼 → 구간 문구 + 표(LOT·품목·검사 번호·결과·측정 수·마지막 측정) | 교정 등록한 그 자리에서 바로 본다 |

### 12.6 Q10 상세 — SPC 최소형 (2026-10-21, V106(임시 V9217))

> 상위 세션 승인(2026-10-21): 다른 모듈과 경계 없음 — Q6 측정값 원장 읽기. 규칙은 결정적, 정정(무효) 측정값 제외를 처음부터 시험.

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| Q10-1 | 관리도 단위 | **품목 × 특성 × 검사 유형 × 공정(없으면 없음)** 하나. 계량형(VARIABLE) 특성만 | 공정검사·출하검사 값을 섞으면 다른 모집단. 같은 특성을 두 공정에서 재면 공정으로 나눈다 |
| Q10-2 | 부분군 | **검사 행 하나 = 부분군 하나**(그 LOT 검사에서 잰 샘플들). **판정 확정(JUDGED)된 검사만**, 무효 검사 제외, 측정값은 **살아 있는 값만**(무효 행·무효된 원래 행 제외). 순서 = 그 부분군 첫 측정 시각, 같으면 검사 행 id | 진행 중 검사 값은 바뀔 수 있다. 정정 원장의 무효 값을 넣으면 관리도가 틀린 값을 보여 준다(역분개 교훈) |
| Q10-3 | 부분군 크기 | 2~25 만 X̄-R 에 쓴다. 1개 또는 26개 이상은 **제외하고 수를 보여 준다** | 샘플링 계획에 따라 n 이 LOT 마다 달라진다. 상수표(d2·d3) 범위 밖은 계산하지 않는다 |
| Q10-4 | 관리한계 (가변 n) | σ̂(군내) = 평균(Rᵢ / d2(nᵢ)), 중심 X̿ = Σnᵢx̄ᵢ / Σnᵢ. X̄: X̿ ± 3σ̂/√nᵢ. R: 중심 d2(nᵢ)σ̂, 한계 (d2 ± 3d3)σ̂ (하한 음수면 0). n 이 모두 같으면 교과서식 X̿ ± A2R̄, D3R̄·D4R̄ 와 같다(시험으로 확인) | 부분군마다 n 이 달라도 한 식으로. d2·d3 은 수학 상수라 코드에 둔다(MsaCalc 와 같게) |
| Q10-5 | 런 규칙 | X̄ 를 표준화(zᵢ = (x̄ᵢ − X̿)/(σ̂/√nᵢ))해 **Western Electric 1~4**: 1 = \|z\| > 3 / 2 = 연속 3점 중 2점이 같은 쪽 z > 2 / 3 = 연속 5점 중 4점이 같은 쪽 z > 1 / 4 = 연속 8점 같은 쪽. R 은 규칙 1(한계 밖)만. 규칙에 걸린 마지막 점에 표시. **기본 활성 = 1·2**(계획 12장), 요청·고정 한계에서 고를 수 있음. 중심선 위 정확히 0 인 점은 어느 쪽도 아님 | NIST: 규칙을 더할수록 오경보가 는다. 결정적 규칙이라 같은 입력이면 같은 경보 |
| Q10-6 | 고정 관리한계 | 표 `qms_spc_limit`(관리도 단위 × 중심·σ̂·부분군 수·산출 구간·활성 규칙, 상태 ACTIVE/VOID). 단위마다 살아 있는 한계 하나, 정정 = 무효(사유) 뒤 다시, 삭제 금지. 고정 한계가 있으면 그 값으로 그리고(2단계), 없으면 보이는 구간에서 계산(1단계) — 응답에 출처 표시 | 한계가 데이터와 함께 흔들리면 관리도가 아니다. 누가 언제 어떤 구간으로 한계를 정했는지 남긴다 |
| Q10-7 | 공정능력 | Cp·Cpk = 군내 σ̂, Pp·Ppk = 전체 표준편차(n − 1). 단측 규격은 단측 지수만(Cp·Pp 없음). 부분군 규격 스냅샷(검사 행의 하한·상한)이 **서로 다르면 계산하지 않고** `specMixed` 표시. σ = 0 이면 지수 없음 | 공정능력은 규격이 하나일 때만 뜻이 있다. 요구 수준(1.33·1.67)은 고객마다 달라 판정하지 않고 값만 |
| Q10-8 | 구간 | 최근 부분군 N 개(기본 25, 최대 200), 끝 날짜(회사 시간대 그날 끝)까지 | 화면 한 장. 기간 조회보다 "최근 25군" 이 현장 관습 |
| Q10-10 | 고정 조건 | 쓸 수 있는 부분군 **20개 이상**(`SPC_FEW_SUBGROUPS`), 군내 변동 있음(σ̂ > 0, `SPC_NO_VARIATION`). 저장 값 = 보이는 구간의 계산값(이미 고정 한계가 있으면 409 — 무효 뒤 다시) | 관리한계 산출에 20~25군이 관습(AIAG SPC). σ̂ = 0 이면 한계 폭이 0 이라 모든 점이 경보 |
| Q10-11 | 응답 숫자 | 관리도 응답 값·고정 한계 칸은 소수 10자리까지 반올림 | 부동소수 끝자리(0.03999…)를 JSON·화면에 내지 않는다 |
| Q10-9 | 하지 않을 것 | 위반 이벤트 저장·자동 보류(28 R3)·OCAP 는 Q8(NCR)·MES 보류 포트 뒤. X̄-S·p/np/c/u 관리도, Nelson 5~8 은 나중 | 보류는 MES `lot_hold` 합의(12.4) 뒤. 최소형 |

### 12.7 Q8a 상세 — 부적합(NCR)·처분(MRB)·자동 보류·특채 결재 (2026-10-21, V112(임시 V9218))

> MES `lot_hold`·`LotHoldPort`(V109) main 반영 뒤 착수. Q8 을 둘로 나눈다 — **Q8a**(이 절: NCR 한 건의 등록 → 처분 → 종결, LOT 자동 보류·해제, 특채 결재, 8D 연결 FK) / **Q8b**(봉쇄: 같은 품목·기간 LOT 일괄 보류 제안 → 사람이 골라 `QMS_CONTAIN`). 사용자 Q-h = Y.

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| Q8a-1 | 표 | `qms_ncr`(번호 채번 `QMS_NCR`, 출처, LOT·품목(LOT 에서 복사)·단위, 근거 검사(선택), 불량 유형 `qms_defect`, 부적합 수량, 내용, 발견 시각, 상태, 보류 여부 `hold_yn`, 결재 id, 종결·취소 기록) + `qms_ncr_disp`(처분 행: 유형·수량·메모·재작업 결과 LOT 번호) | 12.4 범위 그대로 |
| Q8a-2 | 출처 | `INSP`(검사)·`SETUP`(초품)·`PROCESS`(공정 중 발견)·`CLAIM`(고객 클레임)·`OTHER`. 근거 검사는 선택이지만 주면 **같은 LOT 의 판정 확정 검사**(트리거) | 검사 NG 에서 바로 연다. 클레임·공정 발견은 검사 없이 |
| Q8a-3 | 상태 | `OPEN`(등록 — 머리·처분 고침) → `CLOSED`(종결), 특채가 있으면 `OPEN` → `PENDING`(특채 결재 중) → 승인 `CLOSED` / 반려·회수 `OPEN`. `OPEN` → `CANCELLED`(사유). 고칠 수 있는 건 OPEN 만(트리거) | 처분은 MRB 회의 결과를 OPEN 동안 고쳐 가며 적는다 |
| Q8a-4 | 처분 | 유형 `USE_AS_IS`(특채 — 그대로 사용)·`REWORK`·`SCRAP`·`RETURN`·`SORT`(선별), 행 1개 이상, **수량 합 = 부적합 수량**이어야 종결·상신(`QMS_NCR_DISP_SUM`). 재작업은 기록만 — 실제 재작업은 MES LOT 재작업, 결과 LOT 번호를 참조 칸에 | 수량이 어디로 갔는지 남김. 재고 이동은 각 모듈 몫 |
| Q8a-5 | 특채 결재 | 처분에 `USE_AS_IS` 가 있으면 직접 종결 거부(`QMS_NCR_APPROVAL_REQUIRED`) → 결재 상신(`ApprovalDocType` `QMS_NCR`, 결재선은 화면에서). 승인 = 종결 + 보류 해제, 반려·회수 = OPEN | IATF 8.7.1.1 특채(고객 승인 필요 시 그 기록은 첨부 — **NCR 첨부 24장, 2026-10-26**) |
| Q8a-6 | 자동 보류 | 정책 **`QMS_NCR_AUTO_HOLD`(BOOL, 기본 Y — 사용자 Q-h)** 가 Y 면 등록 때 `LotHoldPort.hold(LOT, QMS_NCR, ncr_id)`, `hold_yn = Y`. 종결(승인 포함)·취소 때 `release`. MES 없음(`NONE`) → 409 `QMS_NCR_HOLD_UNAVAILABLE`(정책을 끄라는 문구) | QMS 는 `lot_master` 를 고치지 않는다(12.4 H-5) |
| Q8a-7 | 잠금 순서 | **LOT(포트) 먼저 → `qms_ncr`**. 등록은 ncr_id 를 시퀀스에서 먼저 받고(`GENERATED BY DEFAULT`) → 포트 hold(LOT FOR UPDATE) → INSERT. INSERT 를 먼저 하면 FK 가 LOT 의 KEY SHARE 를 쥔 뒤 포트가 FOR UPDATE 로 올리려 해, 같은 LOT 동시 등록 두 건이 서로 기다린다(업그레이드 교착). 종결·취소·결재 콜백도 포트 release → `qms_ncr` UPDATE(rowVer) | 12.4 Q8-C·H-5. 시험으로 동시 등록 확인 |
| Q8a-8 | 8D 연결 | `qms_capa.ncr_id` 에 FK + 같은 회사 확인 트리거(1부가 남긴 자리, QMS 표). 8D 화면에서 NCR 을 고르는 칸은 다음(Q8b 와 함께) | 자리만 있던 칸을 묶는다 |
| Q8a-9 | 화면 | `qms-ncr`: 목록(상태·출처·검색) + 새 NCR(LOT 번호·출처·근거 검사 번호·불량 유형·수량·내용) + 상세(머리, LOT 의 살아 있는 보류 근거 — 포트 `holds`, 처분 표, 종결·취소·특채 결재). 검사 실행의 NG 검사·초품 NG 카드에서 `?lotNo=&inspNo=&source=` 로 연다 | 검사·초품 화면에 자리만 있던 "부적합(Q8)" 을 잇는다 |
| Q8a-10 | 시험 | 정상(등록 = 보류·종결 = 해제·특채 결재 승인/반려) + 정책 N(보류 안 함) + 다른 출처 보류(MANUAL)가 남아 있으면 NCR 종결 뒤에도 LOT 보류 유지 + MES 화면으로 QMS 근거 해제 거부(포트 계약) + 같은 LOT 동시 등록 2건(교착 없음, 근거 2행) + A~K·A사로 B사·직접 SQL | 근거 여러 개 원칙(12.4 Q8-A) |
| Q8a-11 | 목록 상태 묶음(2026-10-22, 홈 요청) | 상태 칸에 **"열린 것" = `OPEN_ALL`**(처리 중 + 특채 결재 중) 선택지. 서버는 `OPEN_ALL` 을 `status in (OPEN, PENDING)` 으로 바꾼다. 상태 칸은 공통코드 자동 목록 대신 고정 선택지(전체·열린 것·각 상태 — 이름은 공통코드 문구 키 그대로). 홈 `qms-ncr-open` 숫자 링크가 `?q.status=OPEN_ALL` 로 연다 | 홈 숫자(OPEN + PENDING)와 목록 범위를 같게. 묶음 값을 공통코드에 넣으면 상태 코드가 아닌 값이 코드 표에 섞인다 |

### 12.8 Q8b 상세 — 봉쇄(같은 품목·기간 LOT 일괄 보류) (2026-10-22, V113(임시 V9219))

> 28 R1 "봉쇄": 부적합이 나면 같은 품목·같은 기간에 만든 다른 LOT 도 의심 — 시스템은 **후보를 제안**하고 사람이 골라 보류한다(자동 일괄 보류 아님). LOT 보류는 MES `LotHoldPort`(출처 `QMS_CONTAIN`, 참조 = contain_id).

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| Q8b-1 | 표 | `qms_contain`(번호 채번 `QMS_CONTAIN`, 품목, 근거 NCR(선택 — 같은 품목), LOT 발생 기간 시작·끝 날짜, 사유, 상태 OPEN/CLOSED, LOT 수) + `qms_contain_lot`(봉쇄 × LOT, 해제 시각·사람·사유 — 한 번만) | 봉쇄 한 건에 LOT 여러 개, LOT 마다 따로 푼다 |
| Q8b-2 | 후보 | 품목 + 기간(회사 시간대 시작일 0시 ~ 끝날 다음 날 0시, LOT `occurred_at`)의 LOT, 최대 500(포트 `MAX_IDS`), 각 LOT 의 살아 있는 보류 근거(포트 `holds`)를 같이 보여 준다 | 사람이 판단할 재료. 이미 다른 근거로 보류된 LOT 도 봉쇄 근거를 따로 남길 수 있다 |
| Q8b-3 | 봉쇄 실행 | 고른 LOT(1~500, 모두 그 품목)을 **lot_id 오름차순**으로 포트 hold(12.4 H-5, 교착 방지). contain_id 는 시퀀스에서 먼저(Q8a-7 과 같은 이유) → 포트 → `qms_contain`·`qms_contain_lot` INSERT | LOT → qms_* 잠금 순서 |
| Q8b-4 | 해제 | 고른 LOT(살아 있는 줄만)을 오름차순으로 포트 release(사유 필수) → 줄에 해제 기록. 모든 줄이 풀리면 봉쇄 CLOSED(자동). 다른 근거(NCR·수동)가 남은 LOT 은 계속 보류 | 근거 여러 개 원칙 |
| Q8b-5 | NCR 연결 | 근거 NCR 을 주면 그 NCR 과 같은 품목이어야 한다(트리거). NCR 상세에서 "봉쇄" 로 품목·번호를 채워 연다. NCR 종결과 봉쇄 해제는 따로(봉쇄는 의심 LOT, NCR 은 확인된 부적합) | 처분이 끝나도 의심 LOT 조사는 남을 수 있다 |
| Q8b-6 | 화면 | `qms-contain`: 목록 + 새 봉쇄(품목 코드·기간·NCR 번호·사유 → 후보 조회 → 고름 → 봉쇄) + 상세(LOT 줄·지금 보류 근거, 골라서 해제) | |
| Q8b-7 | 시험 | 정상(후보·봉쇄·부분 해제·전부 해제 = CLOSED) + 다른 근거 남음 + LOT 순서(역순으로 보내도 오름차순 호출 — 동시 봉쇄 2건 교착 없음) + A~K·A사로 B사·직접 SQL | |
| Q8b-8 | 자동 종결 경합(상위 세션 검토, #158 뒤) | 해제 루프(포트·줄 기록) **뒤, 남은 보류를 세기 직전**에 봉쇄 머리 `FOR UPDATE`. 같은 봉쇄의 다른 LOT 을 동시에 풀면 잠금 없이 셀 때 서로 상대 미커밋 해제를 못 봐 둘 다 "남음" → 보류 0 인데 OPEN. 머리를 잠그면 둘째는 첫째 커밋을 기다렸다가 새 문장 스냅샷으로 0 을 센다(READ COMMITTED). 잠금 순서 LOT → qms_contain 유지 | 시험: 머리를 다른 트랜잭션이 쥔 동안 두 LOT 동시 해제 → CLOSED, 결함 원형(잠금 없이 세면 둘 다 1)은 SQL 시험으로 재현 |

### 12.9 Q9 착수 게이트 — MES 확장점 (제안 → **MES 합의 2026-10-22**)

> 상태: **MES 합의(2026-10-22)** — Q-i~Q-l 모두 동의, 보완은 아래 "MES 답 반영". 순서: MES 가 B5 ②③④ 뒤 계약 + 호출 PR(R-2 `LotLookup` 포함), PLM R-1(#163) → 둘 다 병합 뒤 QMS 구현 빈. Q-m = WARN(사용자 확정 2026-10-22). 지금 MES 착수 지점: `PopWorkService.event(…, "START", …)` — 공정 행 `prd_wo_proc` 을 `FOR UPDATE` 로 잡고(`findProc(lock = true)`) → `QualGate.check` (BLOCK = `QUAL_BLOCKED`, WARN = `ack` 필요) → 이벤트 INSERT(`gate_mode` OK/WARN) → 상태 RUN. 작업장 홈은 같은 `QualGate` 로 미리보기.

**제안 계약** `io.smarthappy.module.production.link.StartGate`(MES 가 정의, 다른 모듈이 빈으로 구현 — 여러 개, `@Order` 순)

```text
interface StartGate {
    String key();                                   // "QMS" — 결과·로그에 출처로
    GateVerdict check(StartContext ctx);            // 읽기만, 예외 던지지 않음(아래 G-5)
}
record StartContext(String plantCd, long woId, long woProcId, long procId, long itemId, Long equipId,
                    long workerId, long terminalId,                   // MES 답 a)
                    LocalDate workDay, Instant at, boolean preview)   // preview = 공정 화면 미리보기(MES 답 Q-l)
record GateVerdict(String mode /* OK·WARN·BLOCK */, List<GateReason> reasons)
record GateReason(String code, String messageKey, List<Object> args, String refType, Long refId)
                                                    // 예: QMS_FIRST_PENDING · js 아닌 서버 문구 키 · [LOT 번호] · "QMS_SETUP" · eco_id
```

| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| G-1 | 호출 자리 | MES 가 `QualGate.check` **바로 다음**, 이벤트 INSERT 전에 `StartGate` 빈을 모두 부르고 **가장 나쁜 mode** 를 쓴다(BLOCK > WARN > OK). BLOCK = 착수 거부(새 오류 코드 `START_GATE_BLOCKED`, 이유 문구 이어 붙임), WARN = 자격 WARN 과 **같은 `ack`** 로 한 번에 확인 | 현장 확인은 한 번. 자격 게이트와 같은 경험 |
| G-2 | 트랜잭션·잠금 | MES 착수 트랜잭션 안(MANDATORY), `prd_wo_proc` FOR UPDATE **뒤**. 구현 빈은 **읽기만·잠금 없음**(FOR SHARE 도 안 씀) — 그래서 잠금 순서가 생기지 않는다. 쓰기(경보 기록 등)가 필요하면 아웃박스로 | MES 행 잠금 뒤 QMS 행을 잠그면 QMS → MES 순서 경로(NCR 의 LOT 보류)와 엇갈릴 수 있다 |
| G-3 | 미리보기 | 작업장 홈 미리보기도 같은 계약(`preview = true`, 읽기 전용 트랜잭션) — 착수 버튼 옆에 이유 표시 | 누르기 전에 안다 |
| G-4 | 기록 | 이벤트의 `gate_mode` 는 합친 값. 이유는 MES 가 이벤트에 짧은 요약 칸(예: `gate_detail varchar(500)`, 출처·코드 나열)으로 남길지 — **MES 결정**(Q-j) | 감사: "경고를 보고 착수했다" 의 근거 |
| G-5 | 오류 처리 | 구현 빈이 예외를 던지면 MES 는 **BLOCK(`GATE_ERROR`)** 으로 다루고 로그(실패 닫힘) — 또는 정책으로 WARN. **MES 결정**(Q-k) | 품질 게이트가 조용히 꺼지면 안 된다 |
| G-6 | 성능 | 착수 한 번에 빈마다 인덱스 조회 몇 번(아래 QMS 규칙은 품목·공정 키 조회) — 목표 수십 ms | POP 응답 |
| G-7 | 없는 설치본 | 빈이 없으면 자격 게이트만(지금과 같다) | QMS 없는 설치형 |

**QMS 구현 규칙 초안**(정책으로 OFF·WARN·BLOCK, 회사별):

| 규칙 | 판정 | 정책(기본) | 근거 |
|---|---|---|---|
| Q9-1 초품 미판정 | 같은 품목에 **다른 지시가 만든** FIRST_LOT 표시 LOT 중 살아 있는 초품 판정이 없는 것이 있으면 → 정책 mode, 이유에 LOT 번호·ECO 번호. 첫 LOT 을 만드는 지시 자신은 막지 않는다(그래야 초품을 만든다) | `QMS_GATE_FIRST_PENDING` = WARN | Q7(셋업 검증), IATF 8.5.1.3 |
| Q9-2 계측기 | 이 품목·공정(검사 유형 PROCESS)에 적용되는 확정 검사계획 행이 요구하는 계측기 클래스마다, 회사에 **쓸 수 있는**(`GaugeStatus` 차단 아님) 게이지가 하나도 없으면 → 정책 mode, 이유에 클래스 | `QMS_GATE_GAUGE` = WARN | Q2a·Q6(측정 차단 D5) — 측정 단계에서 막히기 전에 착수에서 알림 |
| Q9-3 (나중) | 이 품목 SPC 경보(Q10 고정 한계 위반) 미조치 | — | 위반 이벤트 원장(Q10-9) 뒤 |

| # | 상대 | 질문 | 제안 |
|---|---|---|---|
| Q-i | MES | 계약 `StartGate`(여러 빈, 가장 나쁜 mode, 자격 WARN 과 같은 ack)를 MES 가 정의·호출 | 예 — **MES 동의**(G-a·G-c) |
| Q-j | MES | 이벤트에 이유 요약 칸을 둘지 | 예 — **MES 동의**(G-d) |
| Q-k | MES | 구현 빈 예외 = BLOCK(실패 닫힘) | 예 — **MES 동의 + 세이브포인트·정책**(G-e) |
| Q-l | MES | 작업장 홈 미리보기에도 부를지 | **공정 화면만**(G-f) |
| Q-m | 사용자 | Q9-1·Q9-2 기본값 WARN(BLOCK 은 회사가 켬) | WARN — **사용자 확정 2026-10-22**(`QMS_GATE_FIRST_PENDING`·`QMS_GATE_GAUGE` = WARN) |

**MES 답 반영 (2026-10-22, 상위 세션 경유)**

| # | 정한 것 | QMS 쪽 영향 |
|---|---|---|
| G-a | `StartGate` 는 MES 가 정의(`module.production.link`), `PopWorkService.event START` 의 `QualGate` 다음·이벤트 INSERT 전에 `orderedStream()` 으로 호출, 가장 나쁜 mode. `StartContext` 에 `workerId`·`terminalId` 추가 | 위 계약 모양 그대로 |
| G-b | **QMS 구현 빈은 MES 표(`lot_master`·`prd_wo` 등)를 직접 읽지 않는다** — `StartContext` 값 + 기존 계약으로. 모자란 읽기는 계약으로 요청(아래 R-1·R-2) | Q9-1 은 새 읽기 계약 뒤 |
| G-c | 오류 코드: 자격만 걸리면 기존 `QUAL_BLOCKED`/`QUAL_WARN_ACK`, 게이트 이유가 하나라도 있으면 `START_GATE_BLOCKED`/`START_GATE_WARN_ACK`(한 메시지). 이유 문구는 MES 가 `GateReason.messageKey` 를 요청 로캘로 풂 | **그 키(예 `qms.gate.firstPending`·`qms.gate.noGauge`)는 QMS ko/en 메시지에 정의**(서버 키, `js.` 아님) |
| G-d | `prd_wo_proc_event.gate_detail varchar(500)`(MES 마이그레이션) — `출처:코드(참조)` 나열(예 `QMS:QMS_FIRST_PENDING(LOT L-123)`), OK 면 비움 | `GateReason.code`·참조를 짧게 |
| G-e | 빈 예외 = MES 가 **빈마다 세이브포인트**로 감싸고(SQL 오류가 트랜잭션을 aborted 로 만들지 않게) `GATE_ERROR` 이유 + 정책 `START_GATE_ON_ERROR`(회사, 기본 BLOCK, WARN 선택) | QMS 빈은 예외를 던져도 착수 트랜잭션이 깨지지 않음 — 그래도 던지지 않게 쓴다 |
| G-f | 미리보기는 **공정 화면에서만** `preview = true`, 작업장 홈 "내 할 일" 목록에는 부르지 않음(N × 빈 조회) | 빈 조회는 한 번 단위로 가볍게 |
| G-g | ack = 기존 흐름(ACK 오류 → 확인 → 재요청 때 재판정), 같은 `client_req_id` replay 는 재판정 없음 | 판정은 결정적(같은 데이터면 같은 결과) |

**QMS 가 요청하는 읽기 계약** (G-b — Q9-1 초품 미판정에 필요. Q9-2 는 QMS 표·`StartContext` 만으로 됨)

| # | 제공 | 계약(제안) | 쓰는 곳 |
|---|---|---|---|
| R-1 | **PLM** | `EcoTrace.firstLotsOfItem(long itemId, int limit)` → 그 품목 LOT 에 붙은 **FIRST_LOT 표시**(`MarkedLot` + `ecoId`), 적용(RELEASED) ECO 만, 최근 것부터 상한 | Q9-1 후보 — QMS 는 이 중 살아 있는 초품 판정(`qms_setup_verif`)이 없는 LOT 만 남긴다 |
| R-2 | **MES** | `LotLookup.lots(Collection<Long> lotIds)` → `LotView(lotId, lotNo, itemId, woId, occurredAt)`, 상한 500, 없는·다른 회사 id 는 빠짐(읽기, 잠금 없음) | Q9-1 "다른 지시가 만든" 판정(`woId != ctx.woId`)·이유 문구의 LOT 번호 |

- R-1 은 PLM 표(`plm_eco_lot`), R-2 는 MES 표라 각 모듈이 계약으로 낸다. 둘 다 읽기·잠금 없음이라 착수 트랜잭션(공정 행 FOR UPDATE 뒤)에서 불러도 순서 문제 없음.
- Q9-2(계측기)는 `StartContext.itemId·procId` → QMS `InspPlanResolver`(PROCESS) → 계획 행 계측기 클래스 → QMS `GaugeStatus` 로 끝난다(다른 모듈 읽기 없음).

### 12.10 전자서명 연결 — MRB 처분 확정·교정 판정 (2026-10-22, V124(임시 V9220), 상위 세션 요청 — 추천안)

> 플랫폼 전자서명(docs/36 16장, `platform.esign.ESignService`, `sys_esign`)이 main 에 들어왔다. Q9 를 기다리는 동안 QMS 판정 지점에 **서명 의미**를 붙인다. 붙이는 방법은 docs/36 16.5 그대로: 화면 `hb.esign` → 토큰 → 업무 요청 본문 `esignToken` → 업무 트랜잭션 안 `consume(…, ESignService.hash(정규 글자))` → 상세 API 에 서명 목록.

| # | 항목 | 정한 것(추천안) | 이유 |
|---|---|---|---|
| E-1 | 서명 지점 | ① **NCR 직접 종결**(처분 확정, 특채 없음) = `APPROVE` ② **NCR 특채 상신**(처분 확정 후 결재로) = `AUTHOR` ③ **교정 기록 등록**(교정 판정 OK·NG·LIMITED) = `APPROVE`. PPAP 는 PLM 모듈(`PpapService`)이라 QMS 범위 밖 — PLM 세션 몫 | 판정이 확정되는 순간. MRB·교정 판정은 결재 없이 1인 판정이라 결재 기록만으로는 "누가 무엇을 확인했는가"가 남지 않는다(docs/36 Q32) |
| E-2 | 특채 결재자 | 결재자의 승인 서명은 **이 단계에서 붙이지 않는다** — 승인은 플랫폼 `ApprovalService` 처리라 업무 트랜잭션에 토큰을 넣을 자리가 없다. 플랫폼 결재에 서명을 붙이는 일(결재 처리 API 가 토큰을 받음)은 시스템관리 후속 후보 | 모듈이 결재 처리를 가로채면 결재 규칙(대결·순서)이 두 곳이 된다 |
| E-2 (2026-10-23 갱신) | 특채 결재자 — 붙임 | 시스템관리 결재 확장점 `ApprovalDocType.beforeAct` + 결재 처리 API `esignToken`(#344) 뒤 구현. `NcrApprovalDocType.beforeAct`: **승인·합의 줄만**(반려는 서명 없음 — 제품을 내보내지 않음) — 승인 줄 = `APPROVE`, 합의 줄 = `REVIEW`, 대상 = 그 NCR, 해시 = 지금 처분 내용(결재 중엔 처분을 못 고쳐 작성 서명과 같은 내용). 정책 `QMS_ESIGN_REQUIRED` Y 면 토큰 필수(없음 403 `QMS_ESIGN_REQUIRED`, 틀림 403 `ESIGN_TOKEN`), N 이면 준 토큰만 서명. 거부 = 줄 처리 전체 롤백(줄 CURRENT·NCR PENDING 그대로). 위임 처리는 누른 사람(세션)이 서명. 화면: NCR 상세 [승인] → 서명 대화상자(지금 차례 줄이 모두 합의면 REVIEW). 공통 결재함 안내 = `esignFor`(정책 Y·승인/합의 줄 → 의미·대상 NCR·번호, 반려·정책 N 은 null) — 결재함이 같은 서명 대화상자를 연다(검증은 beforeAct 하나) | 결재 규칙은 엔진 한 곳, 서명 검증은 문서 유형(QMS)이 — 모듈이 결재 처리를 가로채지 않음 |
| E-3 | 필수 여부 | 정책 **`QMS_ESIGN_REQUIRED`(BOOL, 기본 N)** — Y 면 위 지점에서 토큰이 없을 때 ~~400~~ 403 `QMS_ESIGN_REQUIRED`(2026-10-23 결정 #49 — 서명 필수 응답 모듈 공통 403, docs/36 18장). N 이어도 **토큰을 주면 서명**한다(API 일관). 화면은 상세 응답의 `esignRequired` 가 Y 일 때만 서명 대화상자를 연다 | IATF 16949 는 전자서명을 요구하지 않는다 — 의료·항공 고객사(Part 11)를 받는 회사가 켠다. 기본 Y 면 데모 시더·기존 흐름이 모두 재인증을 거쳐야 한다 |
| E-4 | 대상 | NCR = `qms_ncr` + ncr_id. 교정 = **`qms_gauge` + 계측기 asset_id**(교정 행 id 는 등록 뒤에야 생기므로 토큰을 행에 묶을 수 없다) — 정규 글자 안에 calib_id 를 넣어 어느 교정의 서명인지 해시로 묶는다 | 토큰은 발급 때 대상 id 가 있어야 한다(`targetId > 0`) |
| E-5 | 정규 글자 | 줄바꿈(`\n`)으로 이은 `키=값`, 순서 고정, 값의 `\`·줄바꿈·`|` 는 `\\`·`\n`·`\|` 로. **NCR**: `qms_ncr` / `ncrNo` / `lotNo` / `itemCd` / `defectCd` / `ncQty`(끝 0 뺀 숫자) / `unitCd` / `description` / 처분 행마다 `disp=줄번호|유형|수량|재작업LOT|메모`(줄번호 순). **교정**: `qms_gauge_calib` / `calibId` / `assetCd` / `calibDate`(yyyy-MM-dd) / `result` / `limitNote` / `agency` / `custCd` / `certNo` / `cycleDays` / `remark`. 빈 값은 빈 글자 | 같은 내용이면 같은 해시 — 서명 뒤 내용이 바뀌면 상세에서 "서명 뒤 바뀜" |
| E-6 | 순서 | 검증(상태·rowVer·수량 합)을 모두 통과한 **뒤**, 상태를 바꾸는 UPDATE·INSERT 와 같은 트랜잭션에서 `consume`. 업무가 롤백되면 서명 행도 없다(토큰은 이미 쓴 것 — 다시 서명). 교정은 INSERT 로 calib_id 를 받은 뒤 consume | docs/36 16.2 |
| E-7 | 보여 주기 | NCR 상세 `signatures`(서명자·의미·방식·시각·`current` = 지금 내용 해시와 같음). 계측기 상세 교정 이력 행마다 서명(해시가 그 행의 정규 글자와 같은 서명) | 공통 조회 API 는 없다 — 대상 화면 권한으로 모듈이 싣는다 |
| E-8 | 시험 | 정책 Y: 토큰 없음 ~~400~~ 403(2026-10-23) / 서명 → 종결·상세 서명 1(current) / 같은 토큰 다시 403 / 다른 NCR·다른 의미 토큰 403 / rowVer 충돌이면 서명 행 없음 / 특채 상신 AUTHOR / 교정 서명이 그 교정 행에만 / 정책 N + 토큰 = 서명, 토큰 없음 = 그대로 / B사 사용자는 A사 NCR 에 서명 못 함 / 직접 SQL 서명 행 수정 거부 | A~K |

### 12.11 Q9 구현 — QMS 착수 게이트 빈 (2026-10-22, V125(임시 V9221))

> MES `StartGate`·`LotLookup`(V120, #186)·PLM `EcoTrace.firstLotsOfItem`(#167) main 반영 뒤. 12.9 합의(G-a~g, R-1·R-2, Q-m = WARN) 그대로 구현한다.

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| Q9-a | 빈 | `QmsStartGate`(key `QMS`, `@Order(100)`) — 읽기만·잠금 없음, MES·PLM 표는 계약으로만(`EcoTrace`·`LotLookup`, 없으면 `NONE` → 그 규칙은 조용히 통과) | G-2·G-b |
| Q9-b | 정책 | `QMS_GATE_FIRST_PENDING`·`QMS_GATE_GAUGE`(ENUM OFF·WARN·BLOCK, 기본 WARN). 규칙마다 이유가 있으면 그 정책 mode, 둘을 합쳐 가장 나쁜 것 | 사용자 Q-m |
| Q9-c | Q9-1 범위 | 품목의 FIRST_LOT 표시 최근 50개(R-1) → LOT 의 지시(R-2) → **착수 지시가 만든 LOT 은 제외**(첫 LOT 을 만드는 지시는 막지 않음), 입고·재작업 LOT(지시 없음)은 "다른 지시" 로 본다 → (ECO, LOT) 에 `qms_setup_verif` ACTIVE 가 없으면 미판정. 판정은 ECO 마다 따로(다른 ECO 판정이 덮지 않음), 무효(VOID)만 남으면 다시 미판정. 판정 NG 는 이 규칙 밖(판정은 끝났다 — NCR 흐름) | 12.9 Q9-1 |
| Q9-d | 이유 | LOT 하나에 한 줄(`QMS_FIRST_PENDING`, 문구 `qms.gate.firstPending`(LOT 번호, ECO 번호), 참조 LOT·lot_id), 3줄 넘으면 4번째는 `qms.gate.firstPendingMore`(나머지 수) — MES `gate_detail` 500자 안. 계측기는 클래스마다 `QMS_NO_GAUGE`(`qms.gate.noGauge`, 클래스) | G-c·G-d |
| Q9-e | Q9-2 범위 | 품목(+클래스)·`StartContext.procId`·**작업일(`workDay`)** 로 유효한 확정 PROCESS 검사계획(`InspPlanResolver`) → 행의 계측기 클래스(육안 `VISUAL` 제외, 검사 실행과 같은 규칙) → 사용 중 게이지의 최근 유효 교정으로 `GaugeStatus`(임박 일수 정책) 가 `usable` 인 것이 하나도 없으면. 계획이 없으면 판정 안 함(검사 만들 때 `QMS_INSP_NO_PLAN` 으로 드러남) | 12.9 Q9-2 |
| Q9-f | 시험 | `QmsStartGateTest` 4 — 빈을 착수 트랜잭션처럼(회사 범위 + 트랜잭션) 직접 불러 판정·정책·문구(ko/en)·미리보기·A사로 B사. MES 쪽 호출·합치기·문구 풀이·세이브포인트는 MES `PopWorkTest`(가짜 빈)가 맡고, 진짜 QMS 빈이 들어와도 그 시험은 그대로 통과 | 계약 양쪽을 각자 시험 |
| Q9-g | 초품 NG + 열린 부적합(사용자 결정 2026-10-22 "추천안대로") | 살아 있는 초품 판정이 **NG** 인 첫 LOT 에 종결 안 된 부적합(OPEN·PENDING)이 있으면 경고 — 같은 정책 `QMS_GATE_FIRST_PENDING`(OFF·WARN·BLOCK)을 따르고, 부적합이 종결·취소되면 사라진다. 이유 `QMS_FIRST_NG`(`qms.gate.firstNg`(LOT, ECO, 부적합 번호), LOT 3줄 + `qms.gate.firstNgMore`), 미판정 줄 다음. **착수 지시가 만든 LOT 도 포함**(첫 LOT 을 만드는 일은 끝났고 NG 가 났다 — 미판정 제외와 다름). 판정 OK 인 LOT 의 부적합은 이 규칙 밖. 마이그레이션 없음 | NG 는 NCR 흐름으로 처리 — 처분이 끝나기 전에 같은 품목을 다시 돌리지 않게 알린다 |
| Q9-h | 하지 않은 것 | Q9-3(SPC 경보 미조치)은 위반 원장(Q10-9) 뒤 | 범위 |

## 13. 2부 중간 정리 (QMS 하위 세션, 2026-10-21 · Q8 반영 2026-10-22)

> 2부 Q5~Q7·Q9a 가 main 에 들어갔고(9870a0d, 상위 세션 전체 check 1,600건), Q10 은 병합 브랜치에서 V106 으로 check 중(#136). Q8(NCR·MRB·봉쇄)과 Q9 게이트는 **MES 작업(`lot_hold`·`LotHoldPort`, 착수 게이트 확장점)과 사용자 결정(Q-h)** 뒤라 여기서 멈추고 한 것·버린 것·남긴 것·교훈을 모은다. 근거는 12.1~12.6 각 결정 표와 TESTING.md 의 같은 이름 절.
>
> **2026-10-22 갱신**: Q8 을 Q8a(NCR·처분·자동 보류·특채 결재, V112)·Q8b(봉쇄, V113)로 끝냈다. 2부에서 남은 단계는 **Q9 착수 게이트** 하나 — MES 확장점 초안을 12.9 에 적었다(MES 합의 대기).

### 13.1 한 것 — 단계·V 번호·시험

| 단계 | main V (임시) | 그때 전체 check | 신규 시험(클래스) | 화면 |
|---|---|---|---|---|
| Q5 4M 변경 | V094 (V9214) | 1,465 | ChangeApiTest 7(ECO FOR SHARE 대기 1 포함) | `qms-change` |
| Q6 검사 실행·측정값 원장 | V099 (V9215) | 1,534 | InspJudgeTest 5·InspApiTest 8 | `qms-insp` |
| Q7 초품(셋업 검증) + Q6 동시 입력 시험 | V104 (V9216) | 1,546 (병합 때 1,588) | SetupApiTest 5·InspApiTest +1(같은 샘플 동시) | `qms-setup` |
| Q9a 교정 NG 영향 LOT(읽기만) | 없음 | 1,590 (병합 때 1,600) | GaugeImpactTest 2 | `qms-gauge-calib` 에 "영향 LOT" |
| 12.4 Q8 경계 제안 + MES 답 반영 | 없음(문서) | — | — | — |
| Q10 SPC 최소형 | V106 (V9217) | 1,603 | SpcCalcTest 5(손 검산)·SpcApiTest 3 | `qms-spc` |
| Q8a 부적합(NCR)·처분·자동 보류·특채 결재 | V112 (V9218) | 1,638 | NcrApiTest 6(같은 LOT 동시 등록·근거 여러 개 포함) | `qms-ncr` |
| Q8b 봉쇄 | V113 (V9219) | 1,642 | ContainApiTest 4(역순 요청 동시 봉쇄 포함) | `qms-contain` |

- 표 11개: `qms_change`·`qms_change_char`(Q5), `qms_insp`·`qms_insp_line`·`qms_insp_meas`(Q6, 측정값은 **원장**), `qms_setup_verif`(Q7), `qms_spc_limit`(Q10), `qms_ncr`·`qms_ncr_disp`(Q8a), `qms_contain`·`qms_contain_lot`(Q8b). Q8 로 화면 2개(`qms-ncr`·`qms-contain`)·정책 1개(`QMS_NCR_AUTO_HOLD` 기본 Y — 사용자 Q-h)·결재 문서 유형 1개(`QMS_NCR`) 추가. 화면 4개 + 기존 화면 기능 1개, 정책 키 1개(`QMS_4M_REQUIRED` 기본 Y), 오류 코드 7개(`QMS_CHANGE_STATE`·`QMS_INSP_NO_PLAN`·`QMS_INSP_STATE`·`QMS_INSP_INCOMPLETE`·`QMS_SETUP_DONE`·`QMS_SPC_LIMIT_EXISTS` + 칸 오류 코드들).
- 결정: Q5-1~9, Q6-1~10, Q7-1~7, Q8-A~D·H-1~6(제안·합의), Q9a-1~6, Q10-1~11, Q8a-1~10, Q8b-1~7, G-1~7(Q9 제안). 사용자 답 Q-d(초품 = FIRST_LOT 만)·Q-e(4M 필수 기본 Y)·Q-h(부적합 자동 보류 기본 Y).
- 매 단계 정상 + 이상 사용(A~K) + A사로 B사 + 직접 SQL + 브라우저(1366·390, ZT 데이터) — TESTING.md 각 절. **브라우저에서만 보인 결함**이 단계마다 하나씩 있었다(13.5-3).

### 13.2 설계에서 지킨 것 (2부가 플랫폼·1부 규칙을 실제로 쓴 자리)

| 규칙 | 2부에서 | 시험으로 고정한 것 |
|---|---|---|
| 1부 표는 고치지 않고 남긴 자리를 쓴다(0장) | `InspPlanResolver`·`SamplingPlan`(Q6 만들기), `GaugeStatus`(Q6 측정 차단 = 1부 D5 "차단은 2부"), 측정값의 게이지 칸(Q9a 가 그대로 조회) | 막힌 게이지 측정 거부, 계획 없는 검사 400 |
| 원장은 추가만, 정정 = 무효 행 | 측정값 `qms_insp_meas`(`void_of_id`), 초품 판정·고정 관리한계(무효 사유 뒤 다시) | 직접 SQL UPDATE/DELETE 42501·트리거, **정정 값이 읽기 쪽에 섞이지 않음**(Q9a 영향 LOT·Q10 관리도) |
| 회사 격리 = RLS + 같은 회사 확인 트리거 | 표 7개 전부 | 표마다 "A사로 B사"(조회 0·404·직접 SQL 거부) |
| 다른 모듈은 계약으로만 | PLM 계약 ①`EcoLookup`(byNo·ecos 보강 요청)·②`EcoTrace`·③`EcoQualityView`(구현)·④`EcoSubmitRule`(구현) — QMS 매퍼는 `plm_eco` 를 SELECT 하지 않는다(잠금용 FOR SHARE 읽기만 11.4 예외) | PLM 상세의 품질 영역 값 = QMS 계산값 |
| 잠금 순서는 상대 모듈 순서에 맞춘다 | Q5 `plm_eco` FOR SHARE → `qms_*`, Q6 검사 머리 → 행, Q7 `eco_id` FK 없음(MES 가 LOT → ECO 순), Q8 제안 LOT → `qms_ncr`·봉쇄 lot_id 오름차순 | FOR SHARE 대기(≥ 400ms) 후 409, 같은 샘플 동시 입력 하나만 |
| 계산은 순수 함수 + 손 검산 | `InspJudge`(Ac/Re), `SpcCalc`(X̄-R 가변 n·WE1~4·Cp/Cpk) — 1부 `MsaCalc` 와 같게 | 교과서 상수표와 같은 값, 규칙 경계(정확히 3σ·중심선 위 0) |

### 13.3 버린 것

- **NCR 을 QMS 혼자 먼저 만들기** — LOT 보류는 MES `lot_master.hold_yn` 한 칸이라 출처 여럿이 한 LOT 을 잡으면 하나를 풀 때 다 풀린다. 보류 근거 원장을 MES 가 갖게 제안(12.4)하고 합의 뒤로 미뤘다.
- **`qms_setup_verif.eco_id` FK** — KEY SHARE 잠금이 MES 표시 경로(LOT → ECO FOR UPDATE)와 순서를 뒤집는다(Q7-4). 트리거가 잠금 없이 확인.
- **교정 NG 영향 LOT 저장·자동 보류** — 읽기만(Q9a-5). 보류가 필요하면 사람이 봉쇄(Q8b)로.
- **봉쇄 자동 일괄 보류** — 후보만 제안하고 사람이 고른다(Q8b, 28 R1). 데모 LOT 까지 다 잡히는 걸 화면에서 확인.
- **`lot_master` 에 후보 조회 인덱스** — MES 기존 `ix_lot_master_item` 의 품목 열로 충분(남의 표 변경 없음).
- **SPC 위반 이벤트 저장·자동 보류(28 R3)·X̄-S·계수형 관리도·Nelson 5~8** — 최소형(Q10-9). 관리도는 조회 때 계산, 고정 한계만 저장.
- **진행 중 검사 값을 관리도에** — 바뀔 수 있는 값(Q10-2). 판정 확정 검사만.

### 13.4 남긴 것 (다음 후보 — 결정은 사용자·상위 세션)

| 후보 | 막힌 곳 | 비고 |
|---|---|---|
| ~~Q9 품질 게이트~~ **닫음(2026-10-22, 12.11)** | MES 착수 게이트 확장점 모양 합의(**12.9 초안**, Q-i~Q-l MES · Q-m 사용자) | 초품 미판정·계측기 없음 → 착수 경고/차단(QMS 가 구현 빈) |
| ~~교정 NG 영향 LOT → 봉쇄 후보로 넘기기, SPC 경보 → 보류·OCAP~~ **닫음(2026-10-23·24)** | 화면 연결만 | SPC 위반 → NCR·봉쇄 바로가기 = 19.5 I-10. 교정 NG → 봉쇄: 교정 화면 영향 LOT 위에 **품목마다 "봉쇄 열기"** — `qms-contain?itemCd=&from=&to=&lots=&reason=`(기간 = 그 품목 영향 LOT 의 발생일 최소~최대, 회사 시간대 — 영향 API 행에 `lotDate` 추가). 봉쇄 화면은 기간을 채우고 후보를 찾아 **그 LOT 만 체크**, 후보(품목 × 기간, 상한 500)에 없는 LOT 은 번호와 함께 알림. 봉쇄는 품목 하나씩이라 품목별 링크. 자동 보류 없음 |
| ~~8D 화면에서 NCR 고르기~~ **닫음(2026-10-23, 17장)** | 8D 화면 칸 추가 | `qms_capa.ncr_id` FK 는 Q8a 에서 묶음 |
| ~~SPC 위반 이벤트 원장~~ **닫음(2026-10-23, 19.5 · 자동 보류 23.8)** (감사 대응: 언제 알았고 무엇을 했나 — docs/02 2.4) | 설계 | 지금은 조회 때 계산. Q9-3 게이트의 전제 |
| ~~종품(LAST_OFF) 판정~~ **닫음(2026-10-29, 27장)** | — | 1단계 판정 나란히(V202) · 2단계 공정 완료 경고(MES CloseNotice #661 + QMS 구현) |
| 검사 실행 엑셀 올리기·측정 장비 직접 수집 | P5 Collector 와 함께 | 지금은 화면 붙여넣기 |

### 13.5 교훈 (skill 반영 후보 — 상위 세션이 SKILL.md 에)

1. **정정 원장을 읽는 쪽이 정정을 빼는지가 진짜 시험** — 쓰기 쪽(무효 행·트리거)은 단계마다 시험했지만, 그 값을 읽는 Q9a·Q10 은 "살아 있는 값 = `void_of_id is null` 이고 나를 가리키는 무효 행이 없음" 을 매번 같은 두 줄로 써야 했다. 원장을 읽는 새 조회마다 "무효했다 다시 넣은 값" 케이스를 처음부터 넣는다(상위 세션 역분개 교훈과 같은 결).
2. **잠금 순서는 상대 모듈의 경로까지 센다** — Q5(ECO FOR SHARE 먼저), Q6(머리 → 행 — 첫 초안은 거꾸로라 확정과 교착 가능), Q7(FK 의 KEY SHARE 도 잠금), Q8 제안(봉쇄 일괄 lot_id 오름차순). 설계 표에 "잠금" 줄을 따로 둔 단계에서 실수가 없었다.
3. **브라우저에서만 보인 결함이 단계마다 하나** — Q5 체크박스 모양, Q6 "게이지를 고르세요" 문구 없음, Q7 빈 미리보기 문구, Q9a 결과 칸에 상태 코드(`OPEN`) 그대로, Q10 규칙 표기 섞임. API 시험이 다 통과해도 1366·390 화면 확인을 건너뛰지 않는다. 코드 → 문구는 공통코드 이름 맵을 거친다.
4. **가드 시험이 막는 것은 미리 안다** — `hb.t` 키는 글자 그대로(이어 붙이면 `I18nKeyGuardTest`), 시간대 없는 `now()` 금지(`ZonelessNowGuardTest` → `clock.now()`), 원장 표는 감사 트리거 대신 `RetentionTest.AUDIT_EXEMPT`, 새 메뉴는 `DevCompanySeed`·`AuditViewService` 두 곳.
5. **Java 삼항의 언박싱** — `cond ? Double : double` 은 Double 을 언박싱해 null 이면 NPE. null 을 돌려줄 헬퍼는 if 로. 부동소수 결과는 응답 자리에서 반올림(0.03999…) — JSON 시험의 `closeTo` 가 긴 소수를 BigDecimal 로 읽어 실패하는 것도 같은 원인.
6. **경계는 문서로 먼저, 답은 표로** — 12.4 를 코드 없이 먼저 올리고 MES 답을 H-1~6 표로 받자 Q8 범위가 바뀌지 않고 확정됐다. 하위 세션끼리 직접 묻지 않고 상위 세션을 거친 덕에 결정이 한 문서에 모였다.
7. **병합 비용** — 2부 동안 main 이 여러 번 바뀌었고 충돌은 전부 공유 파일 끝 블록(TESTING.md·messages·docs 7장 표)이라 "main 블록 먼저, 우리 블록 뒤" 로 기계적으로 풀렸다. 상위 세션이 PR 을 병합 브랜치로 옮겨 넣을 때 **브랜치 머리에 이미 올린 다음 단계 커밋 일부가 같이 들어갈 수 있다**(Q9a 병합에 Q10 착수 커밋 d5a6df7 이 포함) — 다음 단계는 PR 을 올린 뒤 새 커밋으로만 쌓고, 병합 알림 뒤 `git merge origin/main` 으로 맞춘다.
8. **다른 모듈 행을 잠그는 계약 앞에서는 내 FK 를 먼저 만들지 않는다** — Q8a: NCR INSERT 가 FK 로 LOT 의 KEY SHARE 를 쥔 뒤 포트가 같은 LOT 을 FOR UPDATE 로 올리면 같은 LOT 동시 등록 두 건이 업그레이드 교착. id 를 시퀀스에서 먼저 받아(`GENERATED BY DEFAULT`) 계약 → INSERT. Q8b 도 같은 모양.
9. **여러 행을 잠그는 일괄 작업은 서버가 정렬한다** — Q8b: 요청 순서 그대로 부르면 [A,B]·[B,A] 가 교착. 역순 요청 두 건 동시 시험으로 고정(상대가 잠근 동안 기다렸다 둘 다 성공).
10. **결재 콜백은 서비스가 아니라 매퍼·포트만** — 서비스 → ApprovalService → DocType 순환. 1부 8D 와 같은 결론을 NCR 특채에서 다시.

### 13.6 docs/01·SKILL §3 반영 후보 (상위 세션)

- docs/01 9장(또는 P3 줄): "P3 QMS 2부 Q5~Q8·Q9a·Q10 ✅ 2026-10-22 — V094·V099·V104·V106·V112·V113, Q9 게이트는 MES 확장점 합의 뒤(12.9)".
- SKILL §3 로드맵 줄: "P3 QMS 1부 ✅, 2부 Q5~Q8·Q9a·Q10 ✅ — Q9 착수 게이트는 MES `StartGate` 합의 뒤".
- SKILL §2 QMS 블록 추가 후보: "부적합·봉쇄의 LOT 보류는 `LotHoldPort` 로만 — id 시퀀스 선발급 → 포트 → INSERT, 여러 LOT 은 lot_id 오름차순", "보류는 내 근거만 푼다(다른 근거가 남으면 LOT 은 보류)".
- SKILL §2 QMS 블록(현행 규칙 후보): "측정값 원장을 읽는 조회는 살아 있는 값만(`void_of_id is null` + 무효 행 없음) — 무효 후 재입력 시험 필수", "QMS → PLM 은 계약 ①~④로만, 잠금용 `plm_eco FOR SHARE` 만 예외", "LOT 보류는 MES `LotHoldPort` 로만(합의 12.4 H-1~6) — QMS 가 `lot_master` 를 고치지 않는다", "SPC 계산은 `SpcCalc` 하나(조회·고정 한계 공용), 고정 한계는 단위마다 살아 있는 하나".

## 14. QMS 공개 계약 `module.qms.link` (2026-10-22, 마이그레이션 없음)

> 상위 세션 요청: PMS PM6 이 QMS `DocService.revision/list/detail`·`CapaService.create/detail` 을 직접 불렀다(SKILL §5 — 다른 모듈은 제공자 link 계약으로만). QMS 가 계약을 내고, PMS 가 main 반영 뒤 교체한다.

| 계약 | 메서드 | 규칙 | PMS 쓰는 곳 |
|---|---|---|---|
| `DocLookup` | `rev(docRevId)` → `Optional<DocRevView(docRevId, docId, docNo, docNm, rev, status)>` / `revsByDocNo(docNo)` → 리비전 목록(최근 것부터, 대소문자 무시, **사용 중 문서만**) | 읽기만·잠금 없음·**예외 없음**(없거나 다른 회사 = 빈 값) — 구현은 매퍼만이라 부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다 | 산출물 문서 연결(번호 → 리비전 고르기는 PMS 규칙 그대로), 상태 반영, 이력 라벨 |
| `CapaPort` | `createInternal(InternalRequest(title, itemCd, ownerUserId, problem))` → capa_id / `capa(capaId)` → `Optional<CapaView(capaId, capaNo, title, status)>` | 만들기 = **MANDATORY**(부르는 쪽 트랜잭션), 출처 INTERNAL, 규칙은 `CapaService.create` 그대로(채번·기한), 입력 오류 = 칸 오류(부르는 쪽 저장도 실패), 문제 서술 4,000자로 자름. 권한(qms-capa SAVE)은 부르는 쪽이 확인. 조회 = 예외 없음 | 이슈 "시정조치로 올리기", 이슈 상세의 8D 번호·상태 |

- 둘 다 `NONE` + `of(ObjectProvider)`(QMS 없는 설치본: 조회 빈 값, 만들기 IllegalStateException). 구현 `QmsDocLookup`·`QmsCapaPort`(QMS 안), 조회 보조 `QmsLinkMapper`.
- 시험 `QmsLinkContractTest` 2: 대소문자 무시·사용 중지 제외·없는 값 빈 값(같은 트랜잭션 쓰기 정상)·A사로 B사 빈 값 / 트랜잭션 밖 만들기 거부·INTERNAL 생성·4,000자 자름·없는 품목 칸 오류·B사 조회 빈 값·NONE.

## 15. UI 단계 3 — QMS 화면 목록 틀 (2026-10-22, 결정 docs/29 #44·#45·#47, docs/40 7장 P1·10.1·부록 A)

### 15.1 1차(서버 변경 없음) — 14화면

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| U-1 | 공용 파일 | `static/js/views/qms/qms-ui.js`(QMS 화면 전용, 공통 hb.* 아님): 상태 **톤 표**(`qmsUi.TONES`), 배지(`badge`·표 한 칸 `cell`), 확인(`ask(동작, 문구)`)·사유(`reason(동작, 이름)`)·미저장 이동(`leaveOk(dirty)`) 대화상자. 타입은 같은 파일의 JSDoc `QmsUi` | CMS `cms-ui.js` 와 같은 모양 — 톤 표를 화면마다 두면 같은 상태가 화면마다 다른 색이 된다 |
| U-2 | 톤 표 | 부록 A 규칙을 QMS 코드에: 진행 = `normal`, 결재 = `info`, 완료·합격 = `success`, 기한 임박·제한 사용·봉쇄 중·미판정 = `warning`, 판정 NG·기한 지남 = `danger`, **취소·무효·폐기 = `neutral`**(사용자가 고른 결과라 빨강 아님, A.3) | 색이 아니라 의미 — 값은 tokens.css |
| U-3 | 대화상자 | `window.confirm` 24곳·`prompt` 6곳 → `hb.dialog`. 제목·확정 버튼은 동사(동작 표 하나: 배포·폐기·결재 올리기·종결·취소 처리·무효 처리·다음 단계·판정 확정·연구 확정·지우기·새 개정·첨부 지우기·저장하지 않고 이동), 폐기·취소·무효·지우기는 위험 톤(기본 포커스 = 취소). 사유는 필수 500자 — 빈 사유는 대화상자가 막고, 취소 = 아무 일 없음. 개정 번호 입력은 필수 아님(비우면 서버가 다음 번호) | docs/40 P2·V-7 |
| U-4 | 미저장 이동 | `confirmLeave()` 가 Promise 를 돌려준다 — 그리드 `beforeSelect`(비동기 허용)·조회 패널 `onSearch`·신규·목록 행 클릭이 같은 확인 | 화면 안 대화상자는 동기로 막을 수 없다 |
| U-5 | 위험 동작 | 취소·폐기·무효·4M 세부 지우기 7곳은 `hb-btn-danger-outline` + 도구줄 **오른쪽 끝**(`qms.css` `.hb-qms-danger-zone` 하나 — WMS 와 같은 방식), 결재 반려 3곳은 자리 그대로 외곽선만 | P1 ④ "저장과 떨어진 자리" |
| U-6 | 상태 칸 | 일반 표 7화면(부적합·봉쇄·검사·초품·SPC 한계·문서 리비전·교정 이력) 상태·판정 칸 = 배지, 그리드 6화면(시정조치·문서·계측기·검사계획·MSA·샘플링) = `GridOptions.status` + `rowClass`(취소·폐기·사용 중지 행 흐림) | A.6 |
| U-7 | 조회 영역 | SPC 목록 찾기 → `hb-search` 조회 패널(`?q.keyword=` 주소로 연다). 초품 확인의 "ECO 번호로 열기"는 목록 조건이 아니라 열기 동작이라 그대로 | P1 ② |
| U-8 | 하지 않은 것 | 도구줄 순서는 이미 규칙대로(그리드 화면은 그리드 자체 엑셀). 검사계획 그리드 상태 칸 폭(70)이 배지에 좁음 — 열 폭은 서버 ColumnSpec 이라 2차(그리드 PR)에서 | 1차 = 서버 변경 없음 |

### 15.2 2차 — 일반 표 주 목록 → hb.grid

기존 목록 API 는 그대로 두고 `/grid`·`/columns` **추가만**(결정 #45·#47). 상세 행 표는 일반 표 유지, 3~4화면씩 PR.

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| G-1 | 서버 | 매퍼에 목록·그리드 공통 조건 조각(`listWhere`) → 목록(`findList`, 상한 그대로)·그리드(`findPage`/`findGrid` + `countList`)가 같은 조건. 정렬은 허용 enum(`NcrSort`·`ContainSort`·`InspSort`)의 choose 키 + **마지막 PK 내림차순**(같은 값이면 순서 고정), 기본 = 목록과 같은 순서(등록·검사 시각 최근). `sort` 는 `getParameterValues`(List 로 받으면 키 하나가 쉼표로 쪼개져 400) | 문자열 치환 없이 정렬(`${}` 금지), 쪽을 넘겨도 행이 겹치거나 빠지지 않게 |
| G-2 | 입력 검증 | 검사 실행 그리드는 목록과 같은 검증(모르는 유형·상태·결과 400). 부적합 상태 묶음 `OPEN_ALL` 도 그리드에서 같다 | 같은 조건 |
| G-3 | 열 | 번호 다음에 **상태(배지)** — 좁은 화면·가로 스크롤에서도 상태가 먼저 보이게. 수량은 끝 0 생략(`withMinScale(0)`) | 1366 에서 상태 칸이 오른쪽 밖으로 밀렸다(브라우저 확인) |
| G-4 | 화면 | `hb.grid`(상태 배지·끝난 행 흐림·부적합은 미저장 이동 확인), 저장·처리 뒤 `reload(false, 보던 id)` 로 고른 행 유지(onSelect 를 다시 부르지 않음), 새로 만든 행은 `reload(true, 새 id)`, 주소 조건(`?q.status=`)은 조회 패널 준비 뒤 첫 조회 | 상세를 두 번 읽지 않게 |
| G-5 | 1묶음 | 부적합·봉쇄·검사 실행 | |
| G-6 | 2묶음 | **4M 변경**(ECO 제목·상태는 PLM 계약으로 채움 — 정렬은 QMS 칸만: ECO 번호·고친 시각, 4M 유형 이름은 그리드 `format`) · **CSR**(기본 정렬 = 목록과 같은 고객 → 문서 번호 → 시작일 최근, 사용 중지 포함 조건 그대로, 사용 중지 행 흐림) · **SPC 관리도 단위**(묶음 행이라 행 키 `unitKey` = 품목:특성:유형:공정(없으면 0), 고정 한계 YN 칸, 정렬 끝 = 품목·특성·유형·공정) | 같은 원칙 |
| G-7 | 하지 않은 것 | **초품 확인**은 목록이 "최근 판정"(조건 없음)뿐이고 주 동작이 "ECO 번호로 열기"라 일반 표 유지 — 조회 조건이 생기면 그때. 상세 행 표(처분·LOT·관리도 점 등)는 일반 표 | 결정 #45 "주 목록만" |

## 16. QMS 공개 계약 — 협력사 품질 조회 `SupplierQualityQuery` (2026-10-23, 마이그레이션 없음, 결정 #49 — docs/31 4.12.2 SRM 제안)

> SRM 협력사 평가(수입 PPM)·반품·클레임이 기다리는 계약. 사용자 결정(상위 전달): SRM 이 QMS 를 부르는 **pull**(QMS 없으면 `NONE`), **불량 수량 = NCR 수량 − 특채(USE_AS_IS)**, **PPM 기간 = 판정 확정 시각**. SRM 이 QMS 표를 직접 읽지 않게 `module.qms.link` 로 낸다(14장과 같은 방식: 인터페이스 + `NONE` + `of(ObjectProvider)`, 읽기만·잠금 없음·예외 없음). 처음 낸 초안 `ReceiptInspectionQuery`(로트 불합격 기준, PR 전)는 이 계약으로 바꿨다.

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| SQ-1 | 메서드 | ① `receiptQuality(custId, from, to)` → 품목·단위별 `ItemQuality(itemId, itemCd, unitCd, inspectedQty, rejectedQty, inspectionCnt, ngCnt, openNcrCnt, otherNcQty)` ② `returnsToSupplier(custId, from, to)` → 닫힌 NCR 의 반품(RETURN) 처리 줄 `SupplierReturn(ncrId, ncrNo, lineNo, lotId, lotNo, supplierLotNo, itemId, itemCd, qty, unitCd, defectCd, defectNm, closedAt, poLineId)`(종결 시각·NCR·줄 순, 상한 500) ③ `ncr(ncrId)` → `NcrDetail`(위 + 상태·출처·NCR 수량·반품 처리량 합·설명·검사 번호) | SRM 제안 모양 그대로 + QMS 질의 답(아래 SQ-3·4·5)의 필드 |
| SQ-2 | 대상(①) | 판정 확정 **수입검사**(`insp_type = RECEIVE`, `status = JUDGED` — 무효 제외), **LOT 마다 가장 최근 판정 하나**(무효 뒤 재검사면 마지막 것), 그 판정 시각이 기간 안, LOT 공급사(`lot_master.supplier_cust_id`) = custId | 같은 LOT 을 두 번 세지 않게 |
| SQ-3 | 수량(①) | `inspectedQty` = 그 LOT 들의 **LOT 수량**(`lot_master.qty_created` — 검사 화면의 샘플링 입력 `qms_insp.lot_qty` 는 올림한 정수라 쓰지 않음), `rejectedQty` = 그 검사를 근거로 한 NCR(출처 INSP, 취소 제외)마다 `max(0, nc_qty − 특채 처리량)` 의 합. 무효 판정을 근거로 한 NCR 은 빠진다(살아 있는 판정만) | 사용자 결정(NCR − 특채). NCR 마다 0 아래로 내려가지 않게 |
| SQ-4 | 처리 중 NCR(①) | OPEN·PENDING NCR 의 처리 줄은 아직 바뀔 수 있다 → 현재 처리 줄로 빼고, 그 수를 `openNcrCnt` 로 함께 낸다(SRM 이 "확정 전" 표시·확정 후 재계산) | 종결만 세면 처리 중 불량이 통째로 빠져 PPM 이 낮게 나옴 |
| SQ-5 | 검사 없는 NCR(①) | 출처 INSP 아님(공정·클레임 등)·같은 협력사 LOT·**NCR 발생 시각**이 기간 안 → `otherNcQty`(같은 NCR − 특채 규칙). PPM 분자에 넣을지는 SRM 평가 템플릿이 정한다(QMS 추천: 넣지 않음 — 판정 기간과 발견 기간이 달라 같은 LOT 이 두 기간에 걸림). 검사 없이 클레임만 있는 품목도 한 줄(검사 0) | 상위 질의 ② 의 QMS 답 |
| SQ-6 | 단위 | LOT 단위 = LOT 이 생길 때의 품목 기본 단위(LOT 삽입 트리거가 강제), NCR 은 LOT 단위를 복사(`ck_qms_ncr_lot_copy`) → 모든 수량이 LOT 단위. 품목 단위 변경 차단(`ItemMapper.unitInUse`)이 `lot_master` 를 보지 않아 이론상 한 품목에 두 단위가 생길 수 있음 → **품목 + 단위로 묶어** 낸다 | 상위 질의 ③ 의 QMS 답. master 쪽 보완은 범위 밖(제안만) |
| SQ-7 | 반품(②) | 상태 CLOSED NCR(출처 무관)의 RETURN 처리 줄, 종결 시각이 기간 안. 처리 중 NCR 의 반품 줄은 아직 아님. `poLineId` 는 QMS 가 모른다(항상 null — SRM 이 LOT·입고로 찾음) | 처리 줄은 종결 뒤에만 고정 |
| SQ-8 | 기간·인자 | **회사 시간대 날짜**(BizClock), 양끝 포함(`[from 0시, to+1 0시)`). `from > to`·null·**1년 넘는 구간**은 빈 값, 없는·다른 회사 id 는 빈 값 | 예외 대신 빈 값(부르는 쪽 트랜잭션 보호) |
| SQ-9 | 시험 | `SupplierQualityQueryTest` 3: 품질(170.5/11, 판정 3·불합격 1, 처리 중 1, 클레임 2 / 검사 없는 협력사 = 0/0·클레임 4)·특채 빼기·취소 NCR·무효 판정 근거 NCR·출하검사 제외·반품은 닫힌 RETURN 줄만·NCR 한 건(검사 근거·클레임) / 기간 경계(어제 23:59 판정·종결 → 오늘 구간 밖)·딱 1년은 받음·잘못된 구간 빈 값·트랜잭션 계속 / A사로 B사 / NONE | |
| SQ-10 | SRM 쪽(SRM 세션 몫) | 수입 PPM = rejectedQty ÷ inspectedQty × 1,000,000(otherNcQty 포함 여부는 템플릿), 반품·클레임은 ②·③. 이 PR 은 SRM 코드·docs/31 을 고치지 않음 | 남의 모듈 |

### 16.1 QA #347 후속 — QQ-1·2·3 (2026-10-23, 마이그레이션 없음)

> QA 회차(docs/qa/2026-10-23_supplier-quality.md)의 발견을 상위가 사용자 결정과 함께 넘김: QQ-1 = **NCR 기준 집계 + 무효 경고**, QQ-2 결함, QQ-3 잘림 표시 권고.

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| QQ-1a | 불량 집계 | `rejectedQty`·`openNcrCnt` = 그 LOT 의 출처 INSP NCR(취소 아님) 전부 — 근거 검사가 다시 검사·무효됐어도 LOT 에 붙인다(SQ-3 의 "그 검사 근거 NCR 만" 을 바꿈). 기준 시각 = LOT 의 살아 있는 최근 판정, 판정이 모두 무효면 근거 검사의 판정 시각(그 LOT 은 검사 수량 0 에 불량만 나올 수 있음) | 사용자 결정. 실제 불량이 난 LOT 이 재검사·무효 순서 실수로 PPM 에서 조용히 빠지지 않게 |
| QE-1 | 분모(QA #369 후속, 사용자 결정) | 판정이 모두 무효인데 그 근거 검사로 불량(출처 INSP NCR)을 낸 LOT 도 **그 LOT 수량을 `inspectedQty` 에 한 번** 넣는다(기준 시각 = 근거 검사 판정 시각, 판정 수·NG 수에는 안 듦). 분자·분모 대상 일치 — 전에는 검사 수량 0 에 불량만 나와 PPM 이 커졌다 | 사용자 결정 "무효 검사 LOT 수량도 분모에" |
| QQ-1b | 무효 경고 | 서버는 막지 않는다. 검사 상세에 `openNcrNos`(그 검사 근거 처리 중 NCR), 화면은 무효 전에 확인 대화상자(번호·"NCR·보류는 남고 집계에 계속 들어감"). 이력 = **무효 사유 끝에 `[OPEN NCR 번호,…]`**(칸 500 안, 넘치면 첫 번호 + 개수) | 검사 행 자체가 이력이라 스키마 변경 없이 화면·감사에서 바로 보인다. 응답 모양을 바꾸면 기존 호출이 흔들림 |
| QQ-1c | 소급 | "LOT 마다 최근 판정·판정 시각 기준" 이라 지난 기간 LOT 을 다시 판정하면 그 기간 값이 바뀐다 — 계약 주석에 명시, 평가 시점 결과는 SRM 이 저장 | 사용자 결정(기간 = 판정 확정 시각 유지) |
| QQ-2 | 극단 날짜 | `valid()` 에 연도 1~9999(양끝) — `LocalDate.MAX`·DB timestamptz 범위 밖도 빈 값, 부르는 쪽 트랜잭션 계속 | 계약 약속("예외 없음") 그대로 |
| QQ-3 | 반품 잘림 | `returnsToSupplier` → `ReturnPage(rows, truncated)` — 상한 + 1 줄을 읽어 넘으면 앞 500 줄 + `truncated = true`(기간을 나눠 다시 부름) | 합을 내는 쪽이 말없이 모자라지 않게. SRM 연결 전이라 모양 변경 비용 없음 |
| QQ-5 | 협력사 NCR 목록(SRM 요청) | `ncrsOfSupplier(custId, from, to)` → `NcrPage(rows, truncated)` — 협력사 LOT 의 NCR, **발견 시각** 기준, 취소 제외, 상태·출처·반품 여부 무관(처리 중·반품 없는 NCR 도 — 클레임 근거로 고를 때), 발견 시각·NCR 순, 상한 500 + 1 읽기. `NcrDetail` 에 `occurredAt` 추가 | SRM 클레임 근거 고르기 |
| QQ-4 | 시험 | `SupplierQualityQueryTest` +2(재검사·무효 뒤 NCR 집계·경고 번호·무효 사유 이력·사유 길이 / 극단 날짜·반품 501 줄 잘림·500 줄 안 잘림), QA `SupplierQualityQaTest` 는 #347 병합 뒤 기대값을 새 정책으로 맞추고 `결함QQ2_` 활성화 | |

## 17. 8D ↔ 부적합(NCR) 연결 — 8D 화면에서 NCR 고르기 (2026-10-23, 마이그레이션 없음, 13.4 남긴 것)

> `qms_capa.ncr_id` FK·같은 회사 확인 트리거는 Q8a 에서 이미 묶었지만 입력 경로가 없었다(조회 SELECT 에만). 플랫폼 변경 없는 다음 항목으로 상위 세션에 범위를 회신하고 진행(E-2 는 시스템관리 결재 확장점 뒤).

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| CN-1 | 입력 | 8D 저장(만들기·고치기)에 `ncrNo`(선택). 서버가 번호로 찾는다(대소문자 무시, 앞뒤 공백 무시) | 화면은 번호를 보여 주므로 번호로 받는다(id 를 몰라도 됨) |
| CN-2 | 비움·빠뜨림 | 고치기에서 `ncrNo` 를 **보내지 않으면 그대로**, **빈 문자열이면 끊기**. 만들기에서 빈 값 = 연결 없음 | 다른 칸(고객·품목)은 빈 값 = 지움이지만, 새로 생긴 칸을 모르는 호출이 연결을 몰래 지우지 않게 |
| CN-3 | 막는 것 | 없는·다른 회사 번호 → 칸 오류 `NCR_NOT_FOUND`(RLS 라 둘을 구분하지 않음 — 존재 노출 없음). 취소된 NCR 에 **새로** 잇기 → `NCR_CANCELLED`(이미 이어진 것은 같은 번호로 다시 저장해도 유지). 종결 결재 중·종결·취소된 8D 는 기존 상태 규칙(`QMS_CAPA_STATE`)대로 못 바꿈 | 취소된 NCR 에 이어 둔 기록은 남겨야 추적이 된다 |
| CN-4 | 개수 | 한 NCR 에 8D 여럿 허용, 연결은 8D 쪽 한 칸 | 같은 부적합의 재발·고객별 8D — 막을 근거 없음 |
| CN-5 | 보여 주기 | 8D 상세: NCR 번호·상태 + `qms-ncr?ncrNo=` 링크. NCR 상세: 이어진 8D(번호·단계만, 제목 등 내용은 8D 화면 권한으로) + `qms-capa?capaId=` 링크 + "이 부적합으로 8D 만들기"(`qms-capa?ncrNo=` — 번호를 채운 새 8D) | NCR 권한만 있는 사람에게 8D 내용을 보이지 않게 |
| CN-6 | 잠금 | 8D 행 FOR UPDATE(기존) 뒤 `ncr_id` UPDATE 가 FK 로 NCR 행 KEY SHARE. NCR 쪽 경로는 8D 를 잠그지 않으므로 순서가 뒤집히는 경로 없음(NCR FOR UPDATE 중이면 기다릴 뿐) | 13.5-2 |
| CN-7 | ~~남긴 것~~ **닫음(2026-10-23, V165·임시 V9223)** | 종결 결재 중(CLOSING) `ncr_id` 변경을 DB 도 막는다 — 같은 회사 확인 트리거 `hb_check_qms_capa_ncr` 에 더함, 제약 이름 `ck_qms_capa_closing_locked`(결재 중 내용 고정과 같은 이름). 같은 값 갱신·반려(CLOSING → ACTIONED)는 그대로, 종결·취소는 원래 행 전체 고정 | 처음엔 서비스만 막았다(앱 계정 직접 SQL 경로) |
| CN-8 | 시험 | `CapaNcrLinkTest` 3 + 브라우저(1366·390, ZT) | |

## 18. QMS 공개 계약 — 검사계획 조회 `InspPlanLookup` (2026-10-23, 마이그레이션 없음, 사용자 결정 APQP A2 C5)

> APQP 관리계획서(docs/41 A2)가 검사 행을 QMS 검사계획과 **대조**한다(P6 은 검사계획을 고쳐 쓰지 않음). 14장 `DocLookup` 과 같은 틀: 인터페이스 + `NONE` + `of(ObjectProvider)`, 읽기만·잠금 없음·예외 없음.

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| IL-1 | 메서드 | `lines(itemId, procId, inspType, onDate)` → `PlanLine(inspPlanId, rev, lineNo, charId, charNo, charNm, specialCls, dataType, nominal, lsl, usl, unitNm, scale, methodNm, gaugeClass, samplingPlanCd, freqType, freqN, reactionPlan)` 행 번호 순 | docs/41 5장 `apqp_cp_line` 칸(특성·특별특성·규격·관리 방법·계측기 클래스·샘플링·주기·반응계획)과 맞춤 |
| IL-2 | 고르기 | 검사 실행·착수 게이트와 **같은** `InspPlanResolver`: 그 날 유효한 확정본 하나 — 품목 > 클래스, 같은 공정 > 공정 무관(`procId` null 이면 공정 무관 계획만), 대체 번호 작은 것 | 대조 결과와 실제 검사가 같은 계획을 본다 |
| IL-3 | 규격 | 확정 스냅샷 칸(특성 마스터가 나중에 바뀌어도 그 리비전 값) | 검사계획 리비전 규칙 그대로 |
| IL-4 | 빈 값 | 계획 없음·작성 중·기간 밖·없는/다른 회사 품목·모르는 검사 유형·null 날짜 → 빈 목록(예외 없음) | 부르는 쪽 트랜잭션 보호 |
| IL-5 | 시험 | `InspPlanLookupTest` 2: 행·스냅샷 규격·공정 맞춤·클래스 계획·품목 계획 우선(작성 중이면 아직 클래스)·기간 밖·마스터 변경 뒤 스냅샷 / 잘못된 인자·트랜잭션 계속·A사로 B사·NONE | |

### 18.1 `keys` 추가 (2026-10-26, 마이그레이션 없음 — 상위 요청, PMS M4 확장)

PMS 가 "같은 키 + 다른 리비전 = 겹침(작성 중 rev X)" 을 판정하려고, 계획 id 들의 머리 키를 읽는다.

| # | 정한 것 | 이유 |
|---|---|---|
| IK-1 | `keys(Collection<Long> inspPlanIds)` → `PlanKey(inspPlanId, inspType, itemId, itemClassId, procId, altNo, rev, status)`, inspPlanId 오름차순. **상태 무관**(DRAFT·RELEASED·OBSOLETE 그대로) | 겹침 판정은 작성 중 리비전도 봐야 한다 |
| IK-2 | 부르는 쪽 트랜잭션에서 읽기만(구현이 트랜잭션을 열지 않음), 회사 범위 = RLS — 없는·다른 회사 id 는 결과에서 빠짐. null·빈 입력 = 빈 목록, null·중복 id 거름, **예외 없음** | IL-4 와 같은 원칙(부르는 쪽을 rollback-only 로 만들지 않음) |
| IK-3 | 개수 상한으로 거부하지 않고 **500 개씩 나눠 읽어 합친다**(`KEYS_CHUNK`) | 예외는 IK-2 를 깨고, 잘라 내면 조용히 틀린다 — 상위와 합의(2026-10-26) |
| IK-4 | `NONE` 은 빈 목록(람다 → 익명 클래스, 메서드가 둘) | QMS 없는 설치본 |

시험 `InspPlanLookupTest.키_조회는_상태무관_없는_다른회사_빠짐_501개_이상도_모두_NONE`:
- 확정본 2·작성 중 1 + null·중복·없는 id → 3건, id 순, 키·상태 그대로
- 없는 id 600개(정렬하면 앞) 뒤의 진짜 id 3개 → 둘째 묶음에 있어도 모두
- B사 범위에선 A사 id 가 빠지고 A사 범위에선 B사 id 가 빠짐, `NONE` → 빈 목록

## 19. SPC 위반 이벤트 원장 — 설계 제안 (2026-10-23, 문서만 — 구현은 검토 뒤, 마이그레이션 1개 예정)

> 지금 SPC(12.6)는 **조회할 때 계산**만 한다 — 경보가 났다는 사실·누가 언제 알았고 무엇을 했는지가 어디에도 남지 않는다. 감사 대응(IATF 9.1.1.1·AIAG SPC "out-of-control 조치 기록", docs/02 2.4)과 Q9-3 착수 게이트("이 품목 SPC 경보 미조치", 12.9)·28 R3(위반 → 보류 + 조치 체크리스트)의 전제가 이 원장이다. 13.4 남긴 것 · Q10-9 "하지 않을 것" 을 이어받는다.

### 19.1 정할 것 (추천안)

| # | 항목 | 추천안 | 이유 / 버린 대안 |
|---|---|---|---|
| SV-1 | 언제 잡나 | **수입·공정·출하 검사를 판정 확정(judge)하는 트랜잭션 안**에서, 그 검사의 계량형 행마다 관리도 단위(Q10-1)에 **고정 관리한계(ACTIVE `qms_spc_limit`)가 있을 때만** 새 부분군 점을 고정 한계의 활성 규칙으로 평가 | 판정 확정 = 부분군이 바뀌지 않는 시점(Q10-2). 고정 한계 없는 1단계(보이는 구간 계산)는 한계가 데이터와 같이 흔들려 "위반" 이 확정되지 않는다. **버린 것**: 조회 때 저장(누가 화면을 열어야 생김 — 감사 기록으로 못 씀), 배치(늦고 TenantRunner 관리 비용) |
| SV-2 | 규칙 평가 | 새 점 + 그 단위의 직전 살아 있는 부분군(최대 7개 — WE4 연속 8점)로 `SpcCalc` 의 같은 규칙 함수. **새 점에 걸린 규칙만** 이벤트(지난 점을 다시 이벤트로 만들지 않음) | 관리도 화면과 같은 함수 = 화면 경보와 원장이 어긋나지 않음. 결정적이라 재현 가능 |
| SV-3 | 표 | `qms_spc_event`(원장, **추가만**): 단위 키(품목·특성·검사 유형·공정), 검사 행 id·검사 번호·LOT, 규칙(WE1~4, R1), 점 값(x̄ 또는 R)·z·n, **한계 스냅샷**(limit id·중심·σ̂), `occurred_at`(= 판정 시각)·`recorded_at`. UPDATE/DELETE 차단 | 원장 규약(occurred/recorded, 역분개만). 한계가 나중에 무효·재산정돼도 그때 무엇을 기준으로 위반이었는지 남는다 |
| SV-4 | 처리 기록 | `qms_spc_event_act`(추가만): 이벤트 × 동작 `ACK`(알았음)·`ACTION`(조치 메모 + 선택 연결: NCR·봉쇄·8D 번호)·`CLOSE`(종결, 조치 메모 필수)·`VOID`(사유 필수 — 근거 무효 등). 이벤트 상태 = 마지막 동작에서 파생(OPEN → ACKED → CLOSED / VOID) | 원장을 고치지 않고 처리 이력만 쌓는다. "언제 알았나(ACK)·무엇을 했나(ACTION)" 가 감사 질문 그대로 |
| SV-5 | 정정 | 근거 검사 **무효** 시 그 검사의 열린 이벤트에 같은 트랜잭션에서 `VOID`(사유 "근거 검사 무효 {번호}") — 서버가 자동. 측정값 정정은 판정 확정 뒤엔 불가(Q6)라 해당 없음. 고정 한계 무효는 이벤트를 건드리지 않음(스냅샷이 남음) | 원장 읽는 쪽이 정정을 빼는지가 진짜 시험(13.5-1) — 이벤트 목록·Q9-3 은 VOID 를 뺀다 |
| SV-6 | 자동 보류(28 R3) | **이 단계에서 하지 않음**(→ 23장 설계) — 이벤트 화면에서 사람이 "NCR 만들기"·"봉쇄 열기"(기존 화면 링크, 번호를 ACTION 에 연결). 자동 보류는 정책 키를 둔 다음 단계 후보 | 경보 1건 = LOT 보류는 오경보 비용이 크다(WE2 등). NCR·봉쇄가 이미 보류 경로(포트)를 갖고 있어 두 번 만들지 않는다 |
| SV-7 | 화면 | `qms-spc` 에 "위반" 탭(hb.grid: 단위·규칙·점·발생·상태·처리자 / 상세: 한계 스냅샷·처리 이력·ACK/ACTION/CLOSE/VOID) + 관리도 점에 이벤트 표시(번호) | 별도 메뉴보다 관리도 옆에서 보는 게 현장 흐름. 새 화면 메뉴 없음(권한 = `qms-spc` SEARCH/SAVE) |
| SV-8 | 알림 | 이벤트 생성 시 아웃박스 알림(정책 `QMS_SPC_EVENT_NOTIFY` 받는 사람 그룹, 기본 꺼짐) — 플랫폼 알림 경로 그대로 | 외부·비동기는 아웃박스(CLAUDE.md). 기본 꺼짐 = 데모·시험 소음 없음 |
| SV-9 | Q9-3 게이트 | `QmsStartGate` 에 사유 `SPC_OPEN`: 그 품목·공정의 **열린(OPEN·ACKED) 이벤트**가 있으면 착수 경고/차단 — 기존 Q9 정책 `QMS_START_GATE`(WARN/BLOCK) 따름 | 12.9 G-1~7 의 확장점 그대로, MES 변경 없음 |
| SV-10 | 잠금 | 판정 확정이 이미 검사 머리 FOR UPDATE(Q6) — 이벤트 INSERT 는 그 안. 같은 단위의 두 검사가 동시에 판정되면 직전 점 목록이 서로 상대를 못 볼 수 있음 → **단위별 advisory xact lock**(`pg_advisory_xact_lock(hashtext(단위 키))`) 으로 같은 단위 판정을 줄 세움 | 규칙 2~4 는 연속 점이라 순서가 섞이면 놓치거나 두 번 잡는다. 행 잠금 대상이 없어 advisory |
| SV-11 | 하지 않을 것 | Nelson 5~8, X̄-S·계수형 관리도 이벤트, 이벤트 재평가(한계 재산정 뒤 과거 점 다시 평가), 자동 보류·설비 정지 | 최소형 — 12.6 범위 그대로 |

### 19.2 질문 (사용자·상위)

| # | 누구 | 질문 | QMS 추천 |
|---|---|---|---|
| Q-n | 사용자 | 이벤트를 **고정 관리한계가 있는 단위만** 만들까(SV-1) | 예 — 1단계 계산 한계로는 위반을 확정하지 않음 |
| Q-o | 사용자 | 종결(CLOSE)에 조치 메모만 필수로 할까, NCR·봉쇄·8D 연결까지 필수로 할까 | 메모만 필수(오경보·측정 실수도 종결해야 하므로), 연결은 선택 |
| Q-p | 사용자 | Q9-3 은 기존 착수 게이트 정책(WARN/BLOCK)을 그대로 따를까, SPC 만 따로 정할까 | 그대로(정책 하나 — 현장이 헷갈리지 않게) |
| Q-q | 상위 | 알림 받는 사람 그룹(SV-8)은 플랫폼 알림 그룹을 쓰면 되나 | 예(새 표 없이) |

### 19.3 구현 순서 (검토 뒤)

1. 마이그레이션 1개: `qms_spc_event`·`qms_spc_event_act`(RLS·FORCE·tenant_isolation, 같은 회사 트리거, 추가만 트리거, 감사 예외 `RetentionTest.AUDIT_EXEMPT`), 정책 `QMS_SPC_EVENT_NOTIFY`.
2. `SpcEventRecorder`(판정 확정 훅 — `InspService.judge` 끝, 검사 무효 훅 — `voidInsp`), `SpcCalc` 규칙 함수 재사용.
3. API·화면(SV-7), 게이트 사유(SV-9).
4. 시험: 손 검산(고정 한계·WE1~4 경계)·판정 확정마다 새 점만·동시 판정 두 건(advisory 로 순서·중복 없음)·검사 무효 → VOID·한계 무효 뒤 스냅샷 그대로·A사로 B사·직접 SQL(UPDATE/DELETE 거부)·Q9-3 경고/차단·브라우저 1366·390.

### 19.4 검토 (상위 세션·사용자 작성)

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-23 | 사용자(상위 전달) | **Q-n·Q-o·Q-p·Q-q 모두 추천안 채택** — 이벤트는 고정 관리한계 단위만, 종결은 조치 메모만 필수(연결 선택), Q9-3 은 기존 착수 게이트 정책 그대로, 알림은 플랫폼 알림 그룹. 구현은 이 문서 병합 뒤 착수 |

### 19.5 구현 기록 (2026-10-23, 마이그레이션 1개 — V157·임시 V9222)

| # | 무엇 | 어떻게 / 설계와 다른 점 |
|---|---|---|
| I-1 | 표 | `qms_spc_event`(원장)·`qms_spc_event_act`(처리 기록) — 둘 다 RLS·FORCE·`tenant_isolation`, 앱 계정 UPDATE·DELETE·TRUNCATE 회수 + 추가만 트리거(소유자도 못 고침). 원장 트리거: 같은 회사 검사 행·검사 행 = 그 검사·특성·계량형, 검사 = 같은 단위의 판정 확정, 한계 = 그 단위의 한계. 처리 트리거: 같은 회사 이벤트, 이벤트별 advisory 잠금 뒤 종결·무효 뒤 추가 거부. 같은 (검사 행, 규칙) 은 한 번(`ON CONFLICT DO NOTHING`). 보존 등급 `QUALITY_RECORD`, 감사 예외(원장 자체가 기록) |
| I-2 | 기록 | `SpcEventService.onJudged` — `InspService.judge` 끝(같은 트랜잭션, MANDATORY). 계량형 행마다 고정 한계 있으면 단위 advisory 잠금 → 그 행까지 최근 8 부분군(관리도와 같은 조건: 판정 확정·살아 있는 값, 순서 = 첫 측정 시각·검사 행 id) → `SpcCalc.chart(고정 한계, 한계의 규칙)` 의 **마지막 점 규칙만** 행으로. 발생 시각 = 판정 시각 |
| I-3 | 순서 한계(알고 둔 것) | 창 순서가 **측정 시각** 이라, 먼저 잰 검사를 나중에 판정하면 나중에 잰(먼저 판정된) 검사의 점은 앞 검사를 못 본 채 평가된다(관리도 화면은 다시 그리면 둘 다 반영). 잠금은 같은 순간 판정 둘이 서로를 놓치는 것만 막는다. 재평가는 SV-11 대로 하지 않음 |
| I-4 | 정정 | 검사 무효(`InspService.voidInsp`) → `onVoided` 가 그 검사의 열린 이벤트에 VOID("근거 검사 무효 {번호}"). 이미 종결된 이벤트는 그대로. 고정 한계 무효는 이벤트를 건드리지 않음(스냅샷) |
| I-5 | 상태 | 처리 기록에서 파생: VOID 있음 → VOID, CLOSE 있음 → CLOSED, 처리 하나라도 → ACKED, 없음 → OPEN. 사람이 직접 VOID(메모 필수)도 가능 — 오경보·측정 실수 |
| I-6 | **Q9-3 정책 — 설계와 다름** | Q-p "기존 착수 게이트 정책 그대로" 의 전제(정책 하나 `QMS_START_GATE`)가 사실과 달랐다 — 이미 규칙마다 정책(`QMS_GATE_FIRST_PENDING`·`QMS_GATE_GAUGE`, OFF/WARN/BLOCK). 같은 틀로 **`QMS_GATE_SPC`(기본 WARN)** 를 더했다(현장이 보는 모양 = 다른 Q9 규칙과 같음). 사유 `QMS_SPC_OPEN` = 이 품목·이 공정의 OPEN·ACKED 이벤트, 3줄 + "n건 더", refType `QMS_SPC_EVENT` |
| I-7 | **알림 — 설계와 다름** | SV-8 의 정책 키 `QMS_SPC_EVENT_NOTIFY` 는 만들지 않았다 — 플랫폼 알림 구독(이벤트 `QMS_SPC_EVENT`, 기본 꺼짐)이 이미 "받는 사람 그룹" 이다(Q-q: 새 표 없이). 받는 사람 = `qms-spc` 조회 권한 + 그 알림을 켠 사람, 이벤트마다 한 번, 링크 = `qms-spc` |
| I-8 | 화면 | `qms-spc` 보기 전환(관리도 단위 / 위반). 위반 목록 hb.grid(`/api/qms/spc/events/grid`·`/columns`, 상태 기본 = 미종결), 상세 = 점·한계 스냅샷·처리 이력·처리 폼(종결·무효는 확인 대화상자). 관리도 상세에 그 단위 위반 표 + 이벤트가 남은 점 테두리. 조회 칸 "품목·특성" 은 특성 이름도 찾는다(브라우저 확인에서 발견해 고침) |
| I-9 | 권한 | 조회 = `qms-spc` SEARCH, 처리 = SAVE. 새 메뉴 없음 |
| I-10 | 사람이 여는 보류 경로(SV-6, 2026-10-23) | 위반 상세(종결·무효 아님)에 "부적합 등록"·"봉쇄 열기" — 기존 화면을 채운 채 연다: `qms-ncr?lotNo=&inspNo=&source=INSP&desc=`(내용 = "SPC 위반 {규칙} — 특성 {특성}, 검사 {번호}"), `qms-contain?itemCd=&reason=`. 두 화면은 `desc`·`reason` 인자만 새로 받는다(칸 길이까지 자름). 만든 번호는 위반 상세로 돌아와 조치(ACTION)의 연결로 적는다 — 자동 되돌림·자동 보류는 하지 않음. 마이그레이션·API 없음 |
| I-11 | 홈 카드 "미처리 SPC 위반"(2026-10-24) | `SpcOpenCard`(코드 `qms-spc-open`, 권한 `qms-spc` 조회) — 숫자 = 종결·무효 처리 없는 위반(OPEN·ACKED), 보조 = 미확인(OPEN), 항목 = 최근 5(미확인 = 확인 필요, 처리 중 = 볼 것 있음). 숫자 링크 = `?q.view=events&q.status=OPEN_ALL` — `spc.js` 가 `q.view=events` 면 위반 보기로 열고 상태 칸은 조회 패널이 채운다. 위반 목록 API 와 같은 조건이라 숫자 = 목록 건수(시험으로 고정). 마이그레이션 없음 |

## 20. 계약 모양 제안 — `ChangeLookup`(읽기) · `DocPort`(쓰기) (2026-10-23, PMS APQP A3·A4 요청 — **결정: B안·DRAFT 까지**, ChangeLookup 구현 20.5)

> PMS(APQP A3 2차)가 "그 품목에 어떤 4M 변경점이 승인됐나" 를, A4 뒤에는 "산출물을 QMS 관리문서로 올리기" 를 원한다. 둘 다 QMS 표를 PMS 가 직접 쓰지 않게 `module.qms.link` 계약으로. **사실 관계**: 4M 변경 세부(`qms_change`)는 PLM ECO 1:1 확장이라 **승인 상태·대상 품목·적용일은 PLM 이 원본**(QMS 는 `EcoLookup` 으로 읽음, 11장). 관리문서(`mst_doc`·`mst_doc_rev`)는 QMS 소유, 리비전 DRAFT → IN_APPROVAL → RELEASED → OBSOLETE, 결재 정책 `QMS_DOC_APPROVAL`.

### 20.1 `ChangeLookup` (읽기)

| # | 항목 | 제안 | 이유 / 대안 |
|---|---|---|---|
| CL-1 | 모양 | `approvedFor(itemId, since)` → `List<ChangeView>`, `ChangeView(ecoId, ecoNo, title, ecoStatus, effectiveDate, approvedOn, m4Types, beforeDesc, afterDesc, reasonDesc, impactDesc, revertCond, charIds)` 적용일·ECO 순, 상한 500(+ truncated 는 `ChangePage(rows, truncated)`) | 요청 이름 그대로. 4M 세부(QMS)와 승인·적용(PLM)을 한 줄로 |
| CL-2 | 구현 | QMS 가 자기 `qms_change`(4M 세부가 있는 ECO) id 를 모아 PLM `EcoLookup.ecos(ids)` 로 상태·대상 품목·적용일을 받아 **대상 품목 = itemId, 승인됨, 승인일 ≥ since** 로 거른다 | QMS 가 PLM 표를 직접 읽지 않음(11장 규칙) |
| CL-3 | **PLM 에 필요한 것** | `EcoView` 에 **승인일(`approvedOn`)** 칸 — 지금은 상태·적용일만 있어 "since 이후 승인" 을 정확히 못 한다. PLM 이 칸을 못 주면 기준을 **적용일**로 바꾸자(CL-1 이름은 `effectiveFor`) | 상위 경유 PLM 요청 |
| CL-4 | 대안(추천 비교) | **B안**: "어떤 ECO 가 그 품목에 승인됐나" 는 PLM 계약(`EcoLookup.approvedFor(itemId, since)`)이 답하고, QMS 는 `ChangeLookup.details(ecoIds)` 로 4M 세부만 — 원본이 한 곳이라 PLM 승인 규칙이 바뀌어도 QMS 가 따라 고치지 않음 | QMS 추천 = **B안**(원본 소유 원칙). 요청 모양(A안)도 CL-2 로 가능 |
| CL-5 | 공통 규칙 | 읽기만·잠금 없음·예외 없음(없는/다른 회사 품목·null·잘못된 날짜 = 빈 값), `NONE`·`of(ObjectProvider)`, 날짜는 회사 시간대 | 14·16·18장과 같은 틀 |

### 20.2 `DocPort` (쓰기 — A4 뒤)

| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| DP-1 | 모양 | ~~`DocDraft(sourceKey, docNo, docType, docNm, changeSummary, fileIds)`~~ → **`DocDraft(sourceKey, docNo(선택), docType, docNm, itemId(선택), changeSummary, files: List<DocFile(fileName, byte[])>)`** → `DocRevRef(docId, docNo, docRevId, rev, status, created)` (2026-10-24 상위 변경) | 새 문서 또는 기존 문서의 새 리비전 하나. itemId = 문서 대상 품목 |
| DP-2 | "publish" 의 뜻 | **결재를 건너뛰지 않는다**: 새 리비전을 **DRAFT 로 만들고**(파일 첨부), 정책 `QMS_DOC_APPROVAL` 이 Y 면 QMS 화면·결재함에서 사람이 올림, N 이면 QMS 화면에서 확정. 확정 여부는 `DocLookup.rev(docRevId)` 로 PMS 가 읽음 | 관리문서 승인·배포 통제(IATF 7.5.3)는 QMS 가 지킨다. 모듈이 대신 확정하면 결재 규칙이 두 곳 |
| DP-3 | 멱등 | `sourceKey`(부르는 쪽 키, 예 `PMS:DELIV:{id}:{rev}`)가 같으면 처음 만든 리비전을 그대로 돌려줌(`sys_inbox` 와 같은 원리, QMS 표에 유일 키 칸 — 마이그레이션 1개) | 재시도·두 번 클릭에 리비전이 둘 생기지 않게 |
| DP-4 | 트랜잭션·예외 | 부르는 쪽 트랜잭션 안(MANDATORY), **쓰기 계약이라 거부는 예외**(`BusinessException`: 없는 문서 번호·사용 안 함 문서·작성 중 리비전이 이미 있음(QMS 규칙)·파일 없음) — 읽기 계약의 "예외 없음" 과 다름 | 부르는 쪽이 실패를 알아야 함 |
| DP-5 | 파일 | ~~플랫폼 파일 id 를 받아 옮겨 붙임~~ → **파일 내용(`DocFile(fileName, byte[])`)을 받아 QMS 가 자기 대상(`qms-doc` × docRevId)으로 `FileService.attachAs`**(#460) — 공통 "옮기기" 기능 없이 됨. 확장자·크기(회사 한도)·시그니처 검사는 attachAs 그대로(2026-10-24 상위 변경) | 플랫폼 첨부 규칙 그대로, 다른 회사 파일 id 문제 자체가 없음 |
| DP-6 | 잠금 순서 | 문서 머리 FOR UPDATE → 리비전 INSERT. 부르는 쪽(PMS)은 자기 행 잠금 **뒤** 부름 — QMS 는 PMS 행을 잠그지 않으므로 순서 충돌 없음 | 13.5-2 |
| DP-7 | 시기 | PMS A4(출력·확장) 뒤 — 지금은 모양 합의만, 구현은 요청 올 때 | 상위 지시(우선순위 낮음) |

### 20.3 질문 (상위·PMS·PLM)

| # | 누구 | 질문 | QMS 추천 |
|---|---|---|---|
| Q-r | 상위·PMS | ChangeLookup 은 A안(QMS 가 PLM 을 읽어 합침) / B안(PLM 이 ECO 목록, QMS 는 4M 세부만) 중 무엇 | B안 |
| Q-s | 상위·PLM | `EcoView` 에 승인일 칸을 줄 수 있나(A안이면 필요, B안이면 PLM 계약 안에서 해결) | 줄 수 있으면 A·B 모두 깔끔 |
| Q-t | 상위·PMS | DocPort 가 DRAFT 까지만 만들고 결재·확정은 QMS 쪽 사람이 하는 것으로 충분한가 | 예(결재 규칙 한 곳) |

### 20.4 검토 (상위 세션·PMS·PLM 작성)

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-23 | 상위 | **결정 대기** — Q-r·Q-s·Q-t 는 PLM `EcoLookup.releasedFor` 제안(docs/39)과 짝이라 두 문서가 다 올라오면 사용자에게 한 번에 묻는다. 그때까지 이 장은 QMS 추천안(**B안**, DocPort 는 **DRAFT 까지**)으로 둔다 |
| 2026-10-23 | 사용자(상위 전달) | **Q-r = B안** — PLM 이 `releasedFor`·`approvedFor` 로 ECO 목록을 주고, QMS 는 `ChangeLookup.details(ecoIds)` 로 4M 세부만. **Q-s** — PLM 이 `EcoView` 에 `approvedOn`·`releasedOn` 을 준다(PLM 계약 안). **Q-t** — `DocPort` 는 DRAFT 까지(결재·확정은 QMS 쪽 사람), 구현은 PMS A4 뒤 |
| 2026-10-24 | 상위 | **DocPort 계약 변경 승인** — DP-5 파일은 내용(`DocFile(fileName, byte[])`)을 받아 QMS 가 attachAs(#460), `DocDraft` 에 itemId 추가. 나머지(DRAFT 까지·sourceKey 멱등·작성 중 리비전 있으면 거부·MANDATORY) 그대로. ①②③ 순서와 별개로 우선 구현(20.6) |

### 20.5 구현 기록 — `ChangeLookup.details` (2026-10-23, 마이그레이션 없음)

| # | 무엇 | 어떻게 |
|---|---|---|
| CI-1 | 계약 | `module.qms.link.ChangeLookup` — `details(Collection<Long> ecoIds)` → `List<ChangeDetail(ecoId, ecoNo, m4Types, beforeDesc, afterDesc, reasonDesc, impactDesc, revertCond, charIds, inspPlanIds)>`, `NONE`·`of(ObjectProvider)` |
| CI-2 | 의미 | 4M 세부(`qms_change`)가 **있는** ECO 만, ECO id 순. ECO 상태·대상 품목·승인일은 보지 않는다(B안 — PLM 계약 몫). 유형은 저장 순서, 영향 특성·검사계획은 행 번호 순 |
| CI-3 | 인자 | null·빈 목록·null 원소·0 이하·중복은 버림, 상한 500 개(작은 id 부터) — 넘는 건 조용히 빠진다(부르는 쪽은 PLM 목록 상한 500 과 같다) |
| CI-4 | 예외 없음 | 매퍼 한 번(SELECT), 부르는 쪽 트랜잭션에서 읽기만. 다른 회사 ECO 는 RLS 로 빠짐 |
| CI-5 | 하지 않은 것 | A안 모양(`approvedFor(itemId, since)` 를 QMS 가 PLM 과 합침), `DocPort`(Q-t, A4 뒤) |


### 20.6 구현 기록 — `DocPort` (2026-10-24, V173·임시 V9224)

| # | 무엇 | 어떻게 |
|---|---|---|
| DI-1 | 계약 | `module.qms.link.DocPort.publish(DocDraft)` — 구현 `QmsDocPort` → `DocService.publishDraft`(MANDATORY, 행위자 = 로그인 사용자). `NONE` 은 IllegalStateException |
| DI-2 | 순서·잠금 | sourceKey advisory 잠금 → 같은 키 리비전 있으면 그대로(created=false, 파일도 다시 안 붙임, 지금 상태) → 새 문서(채번·대상 품목·A0) 또는 기존 문서(번호 대소문자 무시, 머리 FOR UPDATE, 작성 중·결재 중 있으면 `QMS_DOC_OPEN_EXISTS`, 대상에 없는 품목이면 더함) → 리비전 INSERT(DRAFT, source_key) → 파일 attachAs |
| DI-3 | 거부 | 칸 오류: sourceKey 필수·형식, docType 필수(새 문서)·코드·기존 문서와 다름(FIXED), docNm 필수(새 문서), docNo 없음·사용 안 함, itemId 없음·사용 안 함, 파일 21개 이상·이름/내용 없음. 파일 검사 실패(막힌 확장자 등)는 attachAs 예외 — 전체 롤백 |
| DI-4 | 기존 문서 새 리비전의 첨부 | 화면의 "새 리비전"(배포본 첨부 복사)과 달리 **받은 파일만** 붙인다 — 산출물이 그 리비전의 내용 전부라서 |
| DI-5 | 표 | V173(임시 V9224): `mst_doc_rev.source_key`(형식 CHECK, 회사 안 유일 — 값 있을 때만, 바뀌지 않음 트리거 `ck_mst_doc_rev_source_fixed`). 키는 회사마다 따로(B사 같은 키 = B사 새 리비전) |
| DI-6 | 하지 않은 것 | 결재 올리기·배포(Q-t), 기존 리비전 파일 바꾸기, PMS 쪽 호출 코드(PMS A3-3 몫) |

## 21. WMS B3 품질 상태 연동 합의안 검토 (2026-10-24, 문서만 — docs/30 12.15 에 대한 QMS 의견)

> WMS 합의안(docs/30 12.15, #470) — 계약 `module.wms.link.QualityStockLink`(onInspJudged·onInspVoided·onNcrClosed), QMS 는 id 만 넘기고 WMS 가 QMS 표를 읽기만, WMS 409 는 그대로 던짐. 12.15.8 부탁 3가지와 D-B26~D-B30 에 대한 QMS 의견. 코드는 합의·WMS 구현 뒤(QMS 쪽은 호출 4줄 + 확인 문구).

### 21.1 부탁 3가지 — QMS 답

| # | 부탁 | QMS 답 | 근거(지금 코드) |
|---|---|---|---|
| WB-1 | 호출 4곳 | **받음.** `InspService.judge`(끝, `spcEvents.onJudged` 다음) · `InspService.voidInsp`(끝, `onVoided` 다음) · `NcrService.close`(끝, `mapper.close` 다음) · `NcrApprovalDocType.onFinished`(APPROVED 가지, `mapper.close` 다음). 모두 같은 트랜잭션 | judge·voidInsp·close 는 `@Transactional`, 결재 콜백 `onFinished` 는 결재 처리 트랜잭션 안(13.5-10 "결재 콜백은 매퍼·포트만" — 포트 호출이라 규칙 안) |
| WB-2 | 409 그대로 던지기 | **받음.** 네 곳 모두 잡지 않는다 → 판정·무효·종결이 같이 거부, SPC 위반 원장·LOT 보류 해제도 함께 롤백(같은 트랜잭션). **주의 하나**: 특채 결재 승인(`onFinished`)에서 409 가 나면 **결재자의 승인 자체가 거부**된다(결재함에 메시지) — 재고를 먼저 정리해야 승인할 수 있다. 정책상 맞다고 보지만 결재자 화면 문구는 WMS 메시지 그대로라 "재고" 맥락이 보이도록 메시지 키를 WMS 쪽에서 구체적으로(예 "QC·BLOCKED 재고가 모자라 처분을 반영할 수 없습니다 — 재고 이동 화면에서 BLOCKED 로 옮긴 뒤 다시") 부탁 | 결재 엔진은 콜백 예외를 그대로 올린다 |
| WB-3 | 판정 확인 문구 | **받음, 서버 경유로.** 화면 JS 가 WMS API 를 직접 부르지 않고(권한·회사 범위가 화면마다 다름) **QMS 검사 상세 응답에 `stockQc`(그 LOT 의 상태별 수량: QC·AVAILABLE·BLOCKED) 를 WMS 읽기 계약으로 채워** 내려준다. 확인 대화상자: OK = "검사 대기 재고 n 이 가용으로 풀립니다", NG = "검사 대기 재고 n 이 사용 금지로 묶입니다" + (가용 m > 0 이면) "가용 재고 m 은 그대로 — 봉쇄를 검토하세요"(D-B26 과 짝). 스위치 N·비관리 품목·WMS 없음 = 문구 없음 | `StockQuery` 에 LOT 별 상태 수량 메서드(`lotStatusQty(lotId)` 등) 추가가 전제 — WMS 몫 |

### 21.2 질문 D-B26~D-B30 — QMS 의견

| # | WMS 추천 | QMS 의견 |
|---|---|---|
| D-B26 | NG 는 QC 재고만, 이미 AVAILABLE 은 안 끌어내림 | **찬성.** 판정이 "모르는 재고"를 움직이지 않는 게 맞다. 대신 WB-3 처럼 NG 확인 문구·판정 결과에 **가용으로 남은 수량을 보여 주고 봉쇄(qms-contain) 바로가기**를 QMS 가 붙인다(19.5 I-10 과 같은 사람 경로) |
| D-B27 | 비관리 품목 판정은 반영 안 함 | **찬성 + 하나 더**: 같은 이유로 **비관리 품목 NCR 종결(onNcrClosed)도 반영하지 않음**(로그만)으로 12.15.5 에 적어 주기. 지금 문구("BLOCKED 0 인데 USE_AS_IS·SCRAP 이 있으면 409")대로면 비관리 품목 NCR 은 스위치 Y 공장에서 **영영 종결할 수 없다** |
| D-B28 | `WMS_QUALITY_LINK` 따로 | **찬성.** 검사 운영 준비와 투입 차감 준비는 시점이 다르다 |
| D-B29 | 새 유형 `SCRAP` | **찬성(WMS 결정 사항, QMS 영향 없음).** 폐기 집계가 사유 코드에 기대지 않는 편이 QMS 불량 비용 집계(나중)에도 낫다 |
| D-B30 | 무효 때 부족하면 409 | **찬성.** 원장과 판정이 갈라지면 QMS 판정 이력이 거짓이 된다 |

### 21.3 QMS 가 덧붙이는 것

| # | 항목 | 내용 |
|---|---|---|
| WC-1 | 단위 | `qms_ncr.unit_cd` = **LOT 단위**(트리거가 lot_master 와 같게 강제), 처분 수량도 그 단위. WMS 원장은 기본 단위만(D-B1) — LOT 단위가 품목 기본 단위와 다를 수 있으면 **WMS 가 환산하거나 다르면 409** 로 정해 주기(QMS 는 단위를 바꾸지 않는다) |
| WC-2 | 무효와 이미 종결된 NCR | NG 판정 → NCR(USE_AS_IS·SCRAP) 종결 → 그 검사 무효: `reverseAll` 이 BLOCKED 에서 QC 로 되돌리려 해도 처분으로 이미 빠져 **409** — 결과적으로 "종결된 처분이 있는 검사는 무효 불가". QMS 는 지금 근거 NCR 이 있어도 무효를 막지 않는다(사용자 결정 QQ-1). 스위치 Y 공장에선 이 409 가 그 규칙을 뒤집는 효과라 **12.15.4 에 이 경우를 명시**해 주기(메시지도 "처분이 반영된 재고" 로) — QMS 쪽 추가 막음은 하지 않는다 |
| WC-3 | 대상 유형 | RECEIVE·FINAL 만 — 찬성. FIRST_OFF(초품)는 ECO 첫 LOT 판단이라 재고 상태와 무관, PROCESS·PATROL 도 같음 |
| WC-4 | 호출 순서 | 판정: QMS 행 판정 → `mapper.judge` → SPC 위반 기록 → **WMS** (마지막). 무효도 같은 순서. 실패하면 전부 롤백이라 순서는 결과에 영향 없지만, WMS 가 읽는 `qms_insp.status/result/judged_at` 이 이미 갱신된 뒤여야 하므로 **반드시 `mapper.judge` 다음** |
| WC-5 | 시험(QMS 쪽) | 호출 4곳 각각: WMS `NONE`(QMS 단독) 동작 그대로 / WMS 가 409 를 던지면 판정·무효·종결·결재 승인이 롤백(검사 상태·SPC 이벤트·보류 해제 원복)을 QMS 시험으로 고정 — 가짜 `QualityStockLink` 빈으로 |
| WC-6 | 잠금 | 12.15.7 그대로 찬성 — QMS 는 `qms_insp`/`qms_ncr` FOR UPDATE 뒤 WMS 를 부르고, WMS 는 QMS 표를 잠그지 않는다. SPC 위반 기록의 advisory 잠금(단위 키)은 WMS 호출 **전**이라 순서 충돌 없음 |

### 21.4 검토 (상위 세션·WMS 작성)

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-24 | 상위 | QMS 의견 21.1~21.3 그대로 채택. WMS 는 12.15 에 D-B27 확장(비관리 품목 NCR 종결 = 반영 안 함), WC-1(단위 다르면 환산 또는 409), WC-2(처분 반영된 검사 무효 409 명시·메시지), WB-2(결재자용 재고 맥락 메시지 키), WB-3 전제(`StockQuery` LOT 상태 수량 읽기)를 반영한 뒤 B3 구현. QMS 호출 4줄·확인 문구·WC-5 시험은 WMS 구현 병합 뒤 QMS 가 |

### 21.5 QMS 쪽 구현 (2026-10-24, WMS #507 병합 뒤 — 마이그레이션 없음)

| # | 구현 | 근거·시험 |
|---|---|---|
| WB-1·WC-4 | `InspService.judge`(SPC 위반 기록 다음)·`voidInsp`(SPC VOID 다음)·`NcrService.close`(`mapper.close` 다음)·`NcrApprovalDocType.onFinished`(APPROVED, `mapper.close` 다음) 끝에서 `QualityStockLink.of(…)` 호출 — WMS 가 읽는 QMS 행이 이미 갱신된 뒤 | `QmsStockLinkTest` — 네 곳 모두 그 id 로 한 번 |
| WB-2 | 409 를 잡지 않는다 → 판정·무효·종결·특채 승인이 함께 롤백(검사 상태·행 판정·LOT 보류 해제·NCR 종결). 특채 승인은 결재자에게 409, NCR 은 결재 중·결재자 차례 그대로 | 같은 시험 — 가짜 빈이 `WMS_QUALITY_SHORT`·`WMS_QUALITY_DISPOSED` 를 던짐 |
| WB-3 | 검사 상세 응답 `stockQc` = `StockQuery.lotStatusQty(lotId)` — 수입·출하(WC-3)만, 그 밖 유형은 WMS 를 읽지도 않음. 판정 확인 대화상자 뒤에 OK "검사 대기 재고 n 이 가용으로", NG "… 부적합(사용 금지)으로" + 이미 가용 m "그대로 — 봉쇄 검토"(D-B26). 빈 맵(WMS 없음·정책 N·비관리 품목)이면 문구 없음 | 같은 시험(유형 거름·빈 맵) |
| WC-5 | WMS 없는 설치본 = `NONE`(아무것도 안 함) — 기존 QMS 시험 전부가 정책 N(기본) 실제 WMS 빈으로 그대로 통과 | `QmsStockLinkTest.WMS_없는_설치본은_NONE_아무것도_안_함` + 전체 check |
| 잠금 | 판정·무효 = `qms_insp` FU → (WMS) `lot_master` 공유 → 재고 / 종결·승인 = `lot_master` FU(포트 해제) → `qms_ncr` → (WMS) `lot_master` 공유(이미 쥠) → 재고 — `QualityStockLink` 주석의 "qms_ncr 뒤 lot_master" 는 종결에선 LOT 이 먼저지만 같은 트랜잭션이 이미 쥔 LOT 이라 무해. NCR 등록(검사 FOR SHARE → LOT)과도 같은 방향(#512) | 잠금 PR 동시 시험 |

### 21.6 판정된 LOT 에 더 들어온 입고 — QMS 후속 (2026-10-26, B-2 — docs/30 12.15.13, 마이그레이션 없음)

WMS 가 (다) 비대칭을 구현(#538): OK LOT 추가분 = QC(재검사), NG LOT 추가분 = 입고 때 BLOCKED(판정 이어받음). QMS 몫 두 가지:

| # | 구현 | 근거·시험 |
|---|---|---|
| B2-1 | 검사 만들 때 **로트 수량을 비우면**: 수입·출하 검사이고 LOT 단위 = 품목 기본 단위이며 WMS 연동 공장에 그 LOT 의 QC 재고가 있으면 **그 QC 수량(올림)**, 아니면 지금처럼 LOT 생성 수량. 직접 준 값이 늘 이긴다. 도움말 문구에 적음 | 판정은 그 검사 `lot_qty` 표본의 결론 — 재검사 표본이 "더 들어온 몫" 을 대표하게. 단위가 다르면 재고 수량(기본 단위)과 비교할 수 없어 생성 수량. `QmsStockLinkTest.B2_…`(QC 4.2 → 5, 직접 30, QC 없음 → 100, 종물 검사는 WMS 를 읽지 않음) |
| B2-2 | NCR 상세 응답 `blockedQty` = 처리 중(OPEN·PENDING) NCR 의 LOT 부적합(BLOCKED) 재고(WMS 읽기 계약, 단위 같을 때만). 화면이 **BLOCKED > 부적합 수량**이면 "판정 뒤 더 들어온 몫 — 부적합 수량을 고치거나 새 부적합으로" 경고. 종결은 막지 않음(처분 합 = 부적합 수량 규칙 그대로), 종결된 NCR 은 내려주지 않음 | 종결된 NCR 밖의 BLOCKED 는 출하·이동이 막혀 안전(조용히 틀리지 않음) — 새 NCR 로 처리. 같은 시험 |

## 22. SPC 목록 깊은 쪽·전체 집계 — 설계 메모 (2026-10-24, 문서 + 실측 시험만, QA 깊은 offset H3·H5)

> QA 조사(docs/qa/2026-10-24_offset-deep-page.md, #479)가 QMS 에 배정한 두 목록. **H3** `SpcEventMapper#findGrid`(위반 목록 — 상태 = `exists` 3개로 계산, 최근 처리 부질의 2개, 기간 없음) · **H5** `SpcMapper#findUnitGrid`(관리도 단위 — 매 쪽 판정 검사 전체 GROUP BY, 기간 없음). 둘 다 정렬·거름이 계산 값이라 지연 조인만으로는 안 풀린다. 화면 동작이 바뀌므로 안을 먼저 낸다(코드는 결정 뒤).

### 22.1 실측 (`SpcEventTest.실측_H3_위반목록_H5_단위목록`, `-Dhb.perf=true`, Testcontainers PostgreSQL 16, 3번 중 가운데 값)

데이터: 위반 20만(2년에 고루, 50% ACK·그중 40% CLOSE), 판정 검사 10만(계량 행 1 + 계수 행 1). 후보안은 같은 데이터에서 임시 표로 흉내 낸 SQL.

| 목록 | 경우 | 지금 | 안 A | 안 B |
|---|---|---|---|---|
| H3 위반 | 첫 쪽 + 건수 | **1,620 ms** | 49 ms + 건수 59 ms | 92일 건수 49 ms |
| H3 위반 | 첫 쪽(건수 없음) | 340 ms | 49 ms | — |
| H3 위반 | 2,000쪽(offset 99,950) | **2,780 ms** | 102 ms | — |
| H5 단위 | 첫 쪽 + 건수 | 431 ms | **1 ms** | 365일 76 ms |
| H5 단위 | 첫 쪽(건수 없음) | 315 ms | 1 ms | — |

- H3 지금 = 건수(`OPEN_ALL`)에 1.3초 — **홈 카드 "미처리 SPC 위반"(#483)의 집계가 같은 계산**이라 위반 20만이면 카드 시간 제한 2초(`HomeService.CARD_TIMEOUT_MS`)에 가깝다. 이 목록을 고치는 안이 카드도 같이 고쳐야 한다.
- 첫 실행에서 한 번 쿼리 취소(57014)가 났고 같은 크기 재실행에선 재현되지 않았다(기록만).
- H5 는 단위 수(품목×특성×유형×공정)가 적어 깊은 쪽 문제는 없고, **매 쪽 전체 집계**가 판정 검사 수에 비례해 는다.

### 22.2 H3 위반 목록 — 안

| 안 | 내용 | 화면에서 바뀌는 것 | 비용·위험 |
|---|---|---|---|
| **A. 상태 투영 표**(추천) | `qms_spc_event_state`(이벤트 1:1 — status·last_act_id·last_by·last_at). 원장(`qms_spc_event`)·처리 기록은 추가만 그대로, 투영 표는 **트리거만 고친다**(이벤트 INSERT → OPEN 행, 처리 INSERT → 상태 갱신 — 재고 `inv_stock` 과 같은 원장 투영 규칙). 인덱스 (comp_cd, status, occurred_at desc, event_id desc). 목록·건수·홈 카드·착수 게이트 Q9-3 이 투영 표를 본다 + 지연 조인(안쪽 = id·정렬 키, 바깥 = 50행 조인) | **없음** — 상태 칸·정렬·필터 그대로, 기간 없이 오래된 미종결 위반도 보인다 | 마이그레이션 1(V186·임시 V9225: 표·RLS·트리거·기존 행 채우기). 투영이 원장과 어긋나면 안 됨 → 시험: 처리 4종·무효 훅·직접 SQL(투영 표 앱 계정 쓰기 금지) |
| B. 기간 필수 | 다른 원장 화면처럼 발생일 기간 필수(기본 최근 92일) + 지연 조인, 상태는 계산 그대로 | 조회 패널에 기간 칸(기본 92일). **92일보다 오래된 미종결 위반이 기본 목록에서 빠진다** — 미처리가 숨는 방향이라 SPC 원장엔 맞지 않음. 홈 카드 링크(기간 없음)와 숫자가 어긋남 | 마이그레이션 없음. 홈 카드·게이트는 따로 고쳐야 함 |
| C. 처리 lateral 하나로 | `exists` 3개 + 부질의 2개 → 마지막 처리 `lateral` 1개(종결·무효 뒤 추가 금지라 **마지막 처리 유형 = 상태**) | 없음 | 마이그레이션 없음. 줄어드는 건 상수 배 — 상태로 거르고 정렬하는 한 여전히 전체 계산(깊은 쪽·건수 그대로) |

**추천 A** — 숫자가 가장 좋고 화면이 안 바뀌며, 홈 카드·게이트까지 같이 해결된다. C 의 "마지막 처리 = 상태" 성질은 A 의 트리거 규칙으로 쓴다.

### 22.3 H5 관리도 단위 목록 — 안

| 안 | 내용 | 화면에서 바뀌는 것 | 비용·위험 |
|---|---|---|---|
| **A. 단위 요약 표**(추천) | `qms_spc_unit`(단위 키 1행 — 부분군 수·마지막 판정 시각·살아 있는 한계 id). **검사 판정·무효 훅**(`InspService.judge`·`voidInsp` — SPC 위반 기록 자리)이 그 검사의 계량형 행 단위를 다시 센다(그 단위만 집계, advisory 잠금 그대로). 고정 한계 정하기·무효 때 limit id 갱신. 목록 = 요약 표 + 지연 조인 | **없음** — 열·정렬·검색 그대로 | 마이그레이션 1(V9225 에 같이: 표·RLS·기존 판정으로 채우기). 측정값 정정(판정 뒤엔 불가 — Q6)이라 판정·무효 두 곳만 맞추면 된다. 시험: 판정·무효·한계 정하기/무효 뒤 요약 = 지금 쿼리 결과 |
| B. 기간 필수 | 판정일 기간 필수(기본 최근 365일) | 조회 패널에 기간 칸. 365일 안에 판정 없는 단위가 목록에서 빠진다(고정 한계는 살아 있는데 안 보임). 부분군 수의 뜻이 "기간 안" 으로 바뀜 | 마이그레이션 없음. 여전히 매 쪽 집계(76 ms, 데이터에 비례) |
| C. 그대로 + 건수 끄기 | 첫 쪽에서 건수(total)를 안 세고 "더 보기"만 | 총 건수 표시 사라짐 | 315 ms 그대로 — 근본 해결 아님 |

**추천 A** — 판정이 이미 SPC 훅을 지나므로 갱신 자리가 하나뿐이고, 화면 동작이 그대로다.

### 22.4 하나로 묶으면

- 마이그레이션 1개(V186·임시 V9225) — `qms_spc_event_state`·`qms_spc_unit` 두 표(둘 다 회사별 RLS·FORCE·tenant_isolation, 앱 계정 쓰기 금지 = 트리거·서비스 훅만), 기존 행 채우기.
- 코드: 위반 목록·건수·홈 카드·게이트 Q9-3 쿼리를 투영 표로, 단위 목록을 요약 표로, 판정·무효·한계 훅에 요약 갱신 한 줄씩.
- 시험: 투영 = 계산(처리 4종·무효 훅), 요약 = 지금 쿼리(판정·무효·한계), A사로 B사, 직접 SQL(투영·요약 표 앱 계정 쓰기 거부), 실측 다시(같은 크기에서 목표 첫 쪽·깊은 쪽 200 ms 안).

### 22.5 질문 (상위·사용자)

| # | 질문 | QMS 추천 |
|---|---|---|
| Q-v | H3 를 안 A(상태 투영 표)로 할까 — 기간 필수(안 B)는 오래된 미종결 위반이 숨는다 | A |
| Q-w | H5 를 안 A(단위 요약 표)로 할까 | A |
| Q-x | 두 표를 한 PR(마이그레이션 1)로 묶을까 | 예 — 같은 SPC 훅 자리, 같은 시험 틀 |

### 22.6 검토 (상위 세션·사용자 작성)

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|

### 22.7 구현 (2026-10-24, V186·임시 V9225 — 상위 승인 Q-v·Q-w = A, Q-x = 한 PR, 조건 4가지 반영)

> **원장 규약은 그대로다.** `qms_spc_event`·`qms_spc_event_act` 는 추가만(UPDATE·DELETE 차단·REVOKE)이고, 아래 두 표는 원장 규약의 예외가 아니라 **원장에서 파생된 읽기용 사본**이다 — 언제든 원장에서 다시 만들 수 있고, 진실은 원장에 있다.

| # | 결정 | 내용 | 근거·버린 대안 |
|---|---|---|---|
| P-1 | 표 | `qms_spc_event_state`(이벤트 1:1 — 상태·품목·특성·유형·공정·규칙·발생·마지막 처리) · `qms_spc_unit`(단위 1행 — 판정 부분군 수·마지막 검사 시각). 둘 다 회사별 RLS·FORCE·tenant_isolation | 목록·건수·홈 카드·착수 게이트가 거르고 정렬하는 칸만 복사(표시 칸은 바깥 조인) |
| P-2 | 갱신 = DB 트리거만 | 이벤트 INSERT → OPEN 행, 처리 INSERT → 상태(VOID·CLOSE·그 밖 ACKED)·마지막 처리 / `qms_insp` 판정 상태가 JUDGED 로 들어오거나 나가면(판정·무효) 그 검사 계량형 특성 단위를 다시 셈 — 단위 advisory 잠금(`SpcEventService` 와 같은 키, 키 정렬 순)으로 동시 판정이 서로를 본다 | **상위 조건 1(모순)**: 서비스 훅 안을 버리고 둘 다 트리거로. 서비스에서 빠뜨릴 길(직접 SQL·다른 경로의 판정)이 없다 |
| P-3 | 앱 쓰기 막기 = 가드 트리거 | 투영 표 BEFORE 트리거가 트리거 깊이 1(앱이 직접 쓴 것)을 거부(`ck_qms_spc_projection`), DELETE·TRUNCATE 는 REVOKE | **REVOKE INSERT/UPDATE 로 막지 않은 이유**: 트리거 함수는 호출자(앱) 권한으로 돌아 REVOKE 하면 트리거도 못 쓴다. 권한을 넘기는 SECURITY DEFINER 는 RLS 우회 금지 규칙(`TenantCatalogInspector` 허용 목록 비어 있음)이라 쓰지 않는다 — `inv_stock`(V048)과 같은 방식 |
| P-4 | 다시 계산 | `hb_qms_spc_event_state_calc()` · `hb_qms_spc_unit_calc()` — 원장에서 같은 값을 만드는 읽기 함수(STABLE, SECURITY INVOKER, RLS 그대로). 처리 기록은 이벤트별 한 번 묶어 붙임(이벤트마다 부질의면 처리 기록 인덱스가 comp_cd 로 시작해 행마다 훑는다 — 첫 실측에서 25분 넘게 걸려 고침) | **상위 조건 2**: 시험이 "다시 계산 = 지금 표" 를 양쪽 차집합 0 으로 고정. 어긋나면 고치는 길 = 소유자가 가드를 잠시 끄고(`ALTER TABLE … DISABLE TRIGGER tg_*_guard`) 그 회사 범위에서 이 함수 결과로 바꿔 넣은 뒤 켠다(마이그레이션 백필과 같은 함수) |
| P-5 | 목록 | 위반 목록 = 지연 조인: 안쪽은 투영 표만(품목 코드 정렬·검색어일 때만 품목 조인, 검색어일 때만 특성·검사·LOT 조인)으로 50개 id → 바깥이 그 행만 표시 칸 조인. 건수는 검색어가 없으면 투영 표만. 단위 목록 = 요약 표 + 살아 있는 한계(부질의, 단위 수만큼) | `OffsetLedgerGuardTest` 허용 목록에서 H3·H5 제거 |
| P-6 | 홈 카드·게이트 | 홈 카드 숫자 = 위반 목록 건수 SQL(`SpcEventMapper.countGrid`)을 그대로 불러 셈(OPEN_ALL·OPEN), 최근 항목도 투영 표 / 착수 게이트 Q9-3 = 투영 표 부분 인덱스(OPEN·ACKED) | **상위 조건 3**: 카드와 목록 숫자가 같은 SQL 에서 나온다 |
| P-7 | 기존 행 | 마이그레이션이 회사마다 범위를 열어(V109 선례) 다시 계산 함수 결과로 채운 뒤 가드 트리거를 만든다 | — |

**실측**(`SpcEventTest.실측_H3_위반목록_H5_단위목록`, 같은 데이터: 위반 20만·판정 검사 10만, API 응답 시간 — 3번 중 가운데)

| 목록 | 22.1 (바꾸기 전) | 구현 뒤 |
|---|---|---|
| H3 첫 쪽 + 건수 | 1,620 ms | **193 ms** |
| H3 첫 쪽(건수 없음) | 340 ms | 124 ms |
| H3 2,000쪽(offset 99,950) | 2,780 ms | **222 ms** |
| H5 첫 쪽 + 건수 | 431 ms | **29 ms** |

- 깊은 쪽은 목표(200 ms)를 조금 넘는다 — SQL 만(22.1 안 A 흉내 137 ms)과의 차이는 API·인증·직렬화. 2,000쪽(10만 행 건너뛰기)은 현장에서 드문 경로라 이 정도로 둔다
- 홈 카드 집계도 같은 건수 SQL 이라 카드 시간 제한(2초) 걱정이 없어졌다(22.1 메모)

### 22.8 결함 S-1 — 투영 last_by·last_at 이 동시 처리 때 원장 계산과 어긋남 (2026-10-27, V199·임시 V9229 — QA docs/qa/2026-10-27_spc-projection-flaky.md, 상위 배정)

| # | 내용 |
|---|---|
| 현상 | `SpcProjectionQaTest` 무작위 동시 처리에서 1회, 투영(`qms_spc_event_state`)과 계산(`hb_qms_spc_event_state_calc`) 차이 2건. **상태는 같고 `last_by`·`last_at` 만** 다름 — 위반 목록 "마지막 처리자"·상세 머리가 조용히 틀림 |
| 원인 | `act_id` 는 행을 만들 때 받는데 같은 이벤트 줄 세우기(V157 가드 advisory)는 그 뒤 BEFORE 트리거. 번호가 작은 T1 이 가드에서 밀리면 큰 번호 T2 가 먼저 커밋 → T1 의 투영 트리거(V186 `hb_qms_spc_state_on_act`, NEW 그대로)가 덮음 = "마지막 커밋" 기준. 계산 함수는 "act_id 최대" 기준 |
| 고침 | V199(임시 V9229): 투영 트리거가 NEW 대신 **원장을 다시 읽음** — 상태 = VOID > CLOSE > 처리 있음(bool_or), 마지막 = `order by act_id desc limit 1`. 계산 함수와 정의가 같다. 가드가 같은 이벤트를 커밋까지 줄 세우므로 늦은 쪽 트리거는 먼저 커밋된 행을 본다(READ COMMITTED, plpgsql 문장마다 새 스냅숏). 출발점은 V186 정의(그 뒤 다시 정의한 V 없음 — grep 확인) |
| 기존 행 | 같은 마이그레이션에서 회사마다 계산 함수 기준으로 **다른 행만** 한 번 맞춤(가드 트리거는 그동안만 끔, V186 채우기와 같은 회사 범위 열기). 운영 데이터에 남은 틀어진 행도 맞춰진다 |
| 시험 | QA 결정적 재현 `SpcProjectionQaTest.결함S1_번호를_먼저_받은_처리가_늦게_커밋해도_투영의_마지막_처리가_계산과_같다`(QA 문서 부록 A 그대로, 켜짐) — T1 이 번호를 받은 뒤 note 식에서 시험의 advisory 를 기다림 → T2(큰 번호) 커밋 → T1 커밋: 고치기 전 번호 `late,early`·투영 `ACKED:late`·계산 `ACKED:early`(재현), 고친 뒤 둘 다 `ACKED:early`·차이 0. 기존 동시 시험 3·`SpcEventTest` 13 통과. 기존 행 맞춤은 로컬 DB(V198 까지 적용 → 투영을 일부러 틀어 둠 → V9229 적용)에서 `zt1` → `zt2`, 가드 다시 켜짐 확인 |

### 22.9 결함 S-2 — 처리 번호 순서 ≠ 실제 처리 순서 (2026-10-27, V201·임시 V9230 — QA docs/qa/2026-10-27_qms-import-ocap-pda.md 3.3·부록 A, 상위 배정)

| # | 내용 |
|---|---|
| 현상 | S-1(22.8) 수정 뒤 `SpcProjectionQaTest` 무작위 동시 시험 40회 중 4회 "종결·무효 뒤에 다른 처리가 없다" 실패(투영 = 계산은 통과). 원장을 `act_id` 순으로 읽으면 `CLOSE → ACK` — 처리 이력이 거꾸로 보이고 투영 `last_by` 가 종결한 사람이 아니라 확인한 사람. 상태·건수는 맞음(bool_or) |
| 원인 | S-1 과 같은 뿌리 — `act_id` 는 행을 만들 때 받고 같은 이벤트 줄 세우기(V157 가드 advisory)는 그 뒤. 번호를 먼저 받은 종결이 가드 앞에서 밀린 사이 큰 번호의 확인이 먼저 커밋 → 종결은 "종결 뒤 처리" 가 아니라 가드가 허용. 22.8 의 "마지막 = act_id 최대" 정의 자체가 이 순서를 믿고 있었다 |
| 고침 | V201(임시 V9230) = QA 안 (가): 가드가 **잠금을 잡은 뒤** 이벤트 안 순번 `act_seq`(그 이벤트 최대 + 1)를 매김 — 잠금이 커밋까지 가므로 순번 = 실제 처리 순서. 요청이 넣은 값은 덮음. 이력(`SpcEventMapper.findActs`)·OCAP 사본 목록(`OcapMapper.findPicksOfEvent`)·투영 트리거·계산 함수는 `act_seq` 로 정렬. `(comp_cd, event_id, act_seq)` 유일 인덱스(옛 `ix_qms_spc_event_act_event` 대체) — 잠금 밖 경로나 REPEATABLE READ 로 줄 세우기가 깨지면 조용히 틀리지 않고 오류. 출발점은 가드 V157·투영 트리거 V199·계산 함수 V186(각각 마지막 정의 — grep 확인) |
| 안 고른 것 | (나) `acted_at` 기준 — 트랜잭션 시작 시각이라 커밋 순서가 아니어서 같은 문제. (다) "종결·무효가 있으면 그것이 마지막" 으로 정의만 바꿈 — 이력 표시 역전이 남음 |
| 기존 행 | `act_id` 순으로 채우되 **종결·무효는 맨 뒤**. 가드가 종결·무효 뒤 처리를 거부하므로(이벤트마다 한 번) act_id 가 종결보다 큰 처리는 반드시 S-2 로 뒤바뀐 것 — 그래서 이것만은 고칠 수 있다. 처리 중(ACK·ACTION)끼리 뒤바뀐 것은 남은 정보로 가릴 수 없어 act_id 순 그대로(acted_at 도 커밋 순서가 아님). 채우기 뒤 투영을 계산 함수 기준으로 다른 행만 다시 맞춤(22.8 과 같은 블록). 원장 가드·투영 가드는 그 블록 동안만 끔, 회사마다 범위 열기 |
| 함께(O-a) | OCAP 고르기 등 처리 요청이 잠금을 기다리는 사이 다른 사람이 종결·무효 → 가드 `ck_qms_spc_event_act_done` 거부를 일반 `CONFLICT` 대신 화면 사전 확인과 같은 409 `QMS_SPC_EVENT_STATE` 로(`SpcEventService.act`). 문구만 — 처리 행·사본이 안 남는 것은 그대로 |
| 시험 | QA 재현 `결함S2_번호를_먼저_받은_종결이_늦게_들어가도_마지막_처리는_종결이다`(부록 A, 마지막 단언만 act_seq — 켜짐): act_id 순 `CLOSE,ACK`(재현됨) · 투영 `CLOSED:closer` · act_seq 순 `ACK1,CLOSE2`. 순번 위조·수정 거부, V9230 채우기 블록을 파일에서 읽어 실행(뒤바뀐 이벤트 → 종결 맨 뒤·투영 맞춤, 맞던 이벤트 그대로, 가드 다시 켜짐). 무작위 동시 시험 40회 반복 40 통과. `OcapTest` O-a |

## 23. SPC 위반 → LOT 자동 보류 설계 (2026-10-24, 문서만 — 19.1 SV-6 "다음 단계 후보" 를 이어받음, 28 R3)

> 19장은 위반을 원장에 남기고 사람이 NCR·봉쇄를 여는 데서 멈췄다(SV-6 "이 단계에서 하지 않음"). 28 R3 의 나머지 반 — **위반 → 자동 보류, 사람이 해제(사유 필수)** — 를 정책 키 하나로 켜고 끄게 설계한다. MES 계약 `LotHoldPort` 의 출처를 하나 늘려야 해서 MES 합의 항목(23.4)이 따른다. 코드는 사용자 결정·MES 합의 뒤.

### 23.1 정할 것 (추천안)

| # | 항목 | 추천안 | 이유 / 버린 대안 |
|---|---|---|---|
| AH-1 | 켜고 끄기 | 정책 `QMS_SPC_AUTO_HOLD` (ENUM `OFF`·`LIMIT`·`ALL`, **기본 `OFF`**). `LIMIT` = 한계 밖 규칙(WE1·R1)만 보류, `ALL` = 고정 한계에 켜 둔 규칙 전부 | 19.1 SV-6 의 우려(오경보 비용) 그대로 — 관리 상태에서 점 하나가 오경보를 낼 확률은 WE1 하나면 0.27%(평균 370점에 1번), WE1~4 를 다 켜면 평균 약 92점에 1번(Champ·Woodall 1987, NIST e-Handbook 6.3.2). **런 규칙(WE2~4)은 "추세를 보라" 신호**라 LOT 을 세울 근거로는 약하다 → 기본 켜는 값을 고른다면 `LIMIT`. **버린 것**: BOOL(규칙 범위를 못 고름), 품목별 설정(최소형 — 필요해지면 고정 한계 행에 칸 추가) |
| AH-2 | 언제 | 판정 확정(judge) 트랜잭션 안, `SpcEventService.onJudged` 가 이벤트를 INSERT 한 **직후** 그 이벤트 근거로 보류 | 이벤트와 보류가 같이 생기고 같이 롤백 — "위반은 있는데 보류는 없다" 가 생기지 않는다 |
| AH-3 | 무엇을 | 그 검사의 LOT(`qms_insp.lot_id`, NOT NULL 이라 늘 있음) **하나만** | 같은 공정의 앞뒤 LOT 까지 넓히는 건 판단이 필요한 일 — 기존 봉쇄(qms-contain, 13.4) 바로가기(19.5 I-10)가 그 경로. 자동으로 넓히면 오경보 한 번에 공장이 선다 |
| AH-4 | 근거 단위 | 출처 **`QMS_SPC`**, 참조 = `event_id`. 한 판정에서 이벤트가 여럿(행 여러 개·규칙 여러 개)이면 근거도 여럿 — LOT 은 **살아 있는 근거가 하나라도 있으면 보류**(`LotHoldPort` 규칙 그대로) | 이벤트마다 종결이 따로라 근거도 따로가 맞다. **버린 것**: 참조 = `insp_id`(검사당 근거 하나) — "그 검사의 이벤트가 모두 끝났나" 를 세는 코드가 새로 필요하고, 근거 행만 봐서는 어느 위반 때문인지 안 보인다 |
| AH-5 | 해제 | 이벤트 **CLOSE**(조치 메모 필수 — 기존 Q-o) · **VOID**(사유 필수 — 사람 또는 검사 무효 자동 SV-5) 때 그 이벤트 근거만 해제, 같은 트랜잭션. 해제 사유 = "SPC 위반 종결 {규칙} — {메모 앞부분}" / "SPC 위반 무효 — {사유}" | R3 "사람이 해제, 해제 사유 필수" 를 기존 동작(CLOSE·VOID)이 이미 만족한다. **ACK·ACTION 은 해제하지 않음**(알았다·조치 중 ≠ 문제 없음). MES LOT 화면은 QMS 근거를 풀 수 없다(포트 규칙 그대로) |
| AH-6 | NCR·봉쇄와 겹침 | 서로 독립 근거 — 위반에서 NCR 을 만들면 LOT 에 `QMS_SPC`·`QMS_NCR` 두 근거, 하나를 풀어도 다른 근거가 남으면 계속 보류 | 근거별 행이라 순서·중복 처리가 필요 없다. 위반을 종결해도 NCR 처분이 끝나기 전엔 LOT 이 안 풀린다 = 맞는 방향 |
| AH-7 | 재고·생산 영향 | 따로 할 일 없음 — `lot_master.hold_yn` 을 이미 읽는 곳이 막는다: WMS 원장(조정 출고·이동 출고·출하 거부, 상태 바꿈은 허용 — V092·V130 `ck_inv_trx_lot_hold`), MES 투입·계보 | WMS B3(21장)와 겹침: 판정 OK + SPC 보류면 B3 가 QC → AVAILABLE 로 바꿔도(상태 바꿈은 허용) **출하·이동은 보류가 막는다**. B3 쪽 변경 없음 |
| AH-8 | 화면 | 위반 상세에 "LOT 보류: 이 위반 근거로 보류 중 / 해제됨(시각·사람)" 한 줄 + CLOSE 확인 대화상자에 "이 위반 근거의 LOT 보류가 풀립니다(다른 근거 n 건은 남음)". 목록에는 칸을 두지 않음(행마다 보류를 읽으면 22.7 지연 조인을 깨고 offset 가드에 걸린다 — 필요해지면 투영 표 `qms_spc_event_state` 에 보류 여부를 트리거로 복사) | 보류는 `LotHoldPort.holds(lotId)` 읽기로 — QMS 표에 보류 여부를 복사하지 않는다(NCR 의 `hold_yn` 과 달리 정책이 바뀌어도 "근거가 있나" 는 MES 근거 행이 정답) |
| AH-9 | MES 없는 설치본 | 정책이 `OFF` 가 아니고 `LotHoldPort` 가 NONE 이면 **판정을 거부**(`QMS_SPC_HOLD_UNAVAILABLE`, NCR 의 `QMS_NCR_HOLD_UNAVAILABLE` 과 같은 방식) | 조용히 건너뛰면 "보류됐다" 고 믿는다. 정책 화면 설명에 "MES 필요" |
| AH-10 | 잠금 순서 | 23.3 | 이 설계에서 유일하게 까다로운 곳 |
| AH-11 | 하지 않을 것 | 앞뒤 LOT 자동 보류, 설비 정지·작업 지시 멈춤, OCAP 체크리스트(28 R3 의 "조치 체크리스트") | 체크리스트는 이벤트 ACTION 기록(19.1 SV-4)으로 대신 — 표준 조치 목록을 따로 관리하려면 새 기준정보가 필요(다음 후보) |

### 23.2 흐름

| 동작 | 지금(19장) | 정책 `LIMIT`·`ALL` 일 때 더해지는 것 |
|---|---|---|
| 판정 확정 | 검사 머리 잠금 → 판정 → 단위 advisory 잠금 → 이벤트 INSERT → 알림 | 이벤트 규칙이 정책 범위면 `hold(lotId, QMS_SPC, eventId, "SPC 위반 {규칙} — 검사 {번호} 특성 {특성}")` |
| 이벤트 CLOSE | 처리 행 INSERT(트리거가 이벤트 advisory 잠금) | `release(lotId, QMS_SPC, eventId, 사유)` — 근거가 없으면(정책이 OFF 일 때 생긴 이벤트) false, 그대로 |
| 이벤트 VOID(사람) | 처리 행 INSERT | 같음 |
| 검사 무효 | 검사 머리 잠금 → 무효 → 열린 이벤트 VOID | 그 이벤트들 근거 해제 |
| 정책을 OFF 로 바꿈 | — | 이미 잡힌 보류는 그대로(근거가 살아 있다) — 이벤트를 종결해야 풀린다. 정책은 앞으로 생길 이벤트에만 |

### 23.3 잠금 순서 — 검사 → 이벤트 → LOT (2026-10-24 #512 로 맞춤)

`LotHoldPort` 문서는 "포트가 LOT 행을 먼저 잠그고, 부르는 쪽은 그 뒤에 자기 표를" 이다. 판정·무효는 **검사 머리(`qms_insp` FOR UPDATE)를 먼저 잠근 상태**라 이 순서를 지킬 수 없다(머리 잠금이 판정의 출발점). 그래서 QMS 전체 순서를 **근거 검사 → (이벤트) → LOT** 하나로 맞춘다.

| 경로 | 잠금 순서 |
|---|---|
| 판정·무효 + 자동 보류(이 설계) | `qms_insp` FOR UPDATE → 이벤트 INSERT(새 행 — 남과 다투는 잠금 아님) → `lot_master` FOR UPDATE(포트) |
| 이벤트 CLOSE·VOID + 해제 | 이벤트 advisory 잠금(처리 트리거) → `lot_master`(포트) |
| NCR 등록(근거 검사 있음) | **`qms_insp` FOR SHARE**(`NcrMapper.findInspByNo`) → `lot_master`(포트) → `qms_ncr` INSERT |

- NCR 등록은 원래 LOT 을 먼저 잡고 근거 검사를 나중에(FK 확인 KEY SHARE) 봤다 → 무효(검사 → LOT)와 거꾸로라 교착이 나고, 잠그지 않고 읽은 JUDGED 를 믿어 무효로 커밋되는 검사에 NCR 이 붙을 수 있었다. **이 결함은 SV-6 와 무관하게 있었고(WMS B3 원장 트리거도 LOT 을 FOR SHARE) 먼저 고쳤다** — #512(`NcrApiTest.D_근거_검사_무효와_동시에_등록해도_교착_없이_무효_검사_근거_NCR_은_안_남음`, docs/testing/qms.md 결함 기록). `InspMapper.lockInsp` 는 FOR UPDATE 그대로
- 그 밖 경로: LOT 을 먼저 잡고 `qms_insp`·이벤트를 잠그는 경로는 없다(grep: `lockInsp` 호출 3곳 — 판정·무효·측정 열기, 이벤트 잠금은 처리 트리거뿐, 측정 트리거는 머리 FOR SHARE → 행). SV-6 구현 때 "판정 진행 중 + 같은 LOT 의 다른 검사 근거 NCR 등록" 동시 시험을 하나 더 둔다
- 포트 문서 문구는 "부르는 쪽이 **이미** 잡은 자기 행 잠금은 괜찮다 — 단 LOT 을 먼저 잡고 그 행을 잠그는 경로가 없어야 한다" 로 넓힌다(23.4 M-3)

### 23.4 MES 합의 항목 (`module.production.link.LotHoldPort`) — 담당 (2026-10-24 상위 결정 Q-za: 표를 가진 쪽이 바꾼다)

| # | 항목 | 내용 | 담당 | 순서 |
|---|---|---|---|---|
| M-1 | 출처 상수 | `LotHoldPort.QMS_SPC = "QMS_SPC"` 추가, 허용 출처에 포함(지금은 QMS_NCR·QMS_CONTAIN 만, 그 밖 IllegalArgumentException) | **MES** | MES 작은 PR(M-2 와 함께) — QMS 구현보다 먼저 |
| M-2 | 표 검사 | `lot_hold.ck_lot_hold_source` 에 `'QMS_SPC'` 추가 + 컬럼 주석(MES 임시 V 대역). `ref_id` FK 없음 규칙(QMS_* 는 호출 쪽 책임) 그대로 | **MES** | 위와 같은 PR |
| M-3 | 잠금 문구 | 포트 문서를 23.3 마지막 줄대로 넓힘 — "부르는 쪽이 이미 잡은 자기 행 잠금은 괜찮다, 단 LOT 을 먼저 잡고 그 행을 잠그는 경로가 없어야 한다" | 문구는 **이 절로 합의**, 반영은 MES(M-1 PR 에 같이) | 위와 같은 PR |
| M-4 | MES 화면 | LOT 보류 근거 목록의 출처 `QMS_SPC` 문구 키 — ko "SPC 위반" / en "SPC violation". 풀기 버튼 없음(QMS 근거와 같음) | 문구는 **이 절로 합의**, 반영은 MES | M-1 PR 에 같이 또는 바로 다음 |
| — | QMS 구현 | 정책 `QMS_SPC_AUTO_HOLD`·판정 훅 보류·CLOSE/VOID 해제·화면·23.5 시험 | **QMS** | MES PR 병합 뒤 |

### 23.5 시험 (구현 때)

- 정책 `OFF`(기본): 지금 시험 그대로 — 보류 없음
- `LIMIT`: WE1 위반 → 보류 1·근거 `QMS_SPC`/`event_id`; WE2 만 → 보류 없음. `ALL`: WE2 → 보류
- 한 판정 이벤트 2건 → 근거 2, 하나 CLOSE → LOT 아직 보류, 둘 다 → 해제. 위반 + NCR → NCR 근거만 남음
- 검사 무효 → 이벤트 VOID + 근거 해제(같은 트랜잭션), 무효 실패(CONFLICT) 면 보류도 그대로
- 정책 OFF 때 생긴 이벤트를 ON 뒤 CLOSE → release false, 오류 없음
- MES NONE + 정책 ON → 판정 거부(검사 OPEN 그대로, 이벤트 없음)
- 이상 사용: A사로 B사(B사 이벤트 CLOSE → 404, B사 LOT 보류 그대로) · ACK 로 보류 풀기 시도 → 안 풀림 · MES LOT 화면 API 로 `QMS_SPC` 근거 풀기 → 거부(포트 규칙) · 직접 SQL: 앱 계정으로 `lot_hold` 의 `QMS_SPC` 행 released_* 직접 UPDATE → MES 가드
- 교착(23.3): "판정 진행 중(자동 보류) + 같은 LOT 근거 NCR 등록" 동시 → 40P01 없이 한쪽이 기다렸다 끝남

### 23.6 질문 (사용자·상위)

| # | 누구 | 질문 | QMS 추천 |
|---|---|---|---|
| Q-u | 사용자 | SPC 위반 자동 보류를 정책으로 둘까(기본 꺼짐) | 예 — 정책 `QMS_SPC_AUTO_HOLD`, 기본 `OFF` — **결정(2026-10-26 사용자): 추천 그대로** |
| Q-y | 사용자 | 켤 때 범위를 한계 밖(WE1·R1)만 / 켜 둔 규칙 전부 중 고르게 할까 | 예(`LIMIT`·`ALL`) — 런 규칙은 오경보가 4배 가까이 많다 — **결정(2026-10-26 사용자): 추천 그대로** |
| Q-z | 사용자 | 해제는 이벤트 종결(CLOSE)·무효(VOID) 때만으로 할까(ACK·조치 기록은 안 풀림) | 예 — 종결 메모가 해제 사유 — **결정(2026-10-26 사용자): 추천 그대로** |
| Q-za | 상위·MES | M-1~M-4(출처 `QMS_SPC`) 합의, M-2 마이그레이션은 누가 | **결정(2026-10-24 상위)**: 표를 가진 쪽 — M-1·M-2 = MES, M-3·M-4 = 이 절 문구로 MES 반영(23.4 표) |

### 23.7 검토 (상위 세션·사용자 작성)

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|

### 23.8 구현 (2026-10-26, V193·임시 V9226 — MES #562(V191) 뒤, 사용자 결정 Q-u·Q-y·Q-z)

| # | 구현 | 시험 |
|---|---|---|
| I-1 | V193 정책 정의 `QMS_SPC_AUTO_HOLD`(ENUM OFF·LIMIT·ALL, 기본 OFF). 표 변경 없음 — 보류 근거는 MES `lot_hold`(출처 `QMS_SPC`, V191) | `SpcEventTest.자동보류_…` |
| I-2 | `SpcEventService.onJudged`: 새 이벤트(INSERT 성공)마다 정책이 ALL 이거나 LIMIT 이고 규칙이 WE1·R1 이면 `LotHoldPort.hold(검사 LOT, QMS_SPC, 이벤트 id, "SPC 위반 {규칙} — 검사 {번호}")`. MES 없음(NONE → IllegalStateException) = **판정 거부** `QMS_SPC_HOLD_UNAVAILABLE`(409, 검사 OPEN·이벤트 없음 — 같은 트랜잭션 롤백) | OFF → 보류 0 / LIMIT → WE1 만 1 / ALL → 이벤트 수만큼 / MES 없음 → 409 |
| I-3 | 해제: `act` 의 CLOSE·VOID(처리 행 INSERT 뒤) · `onVoided`(검사 무효의 자동 VOID — 닫은 이벤트 id 를 `returning` 으로 받아) — 그 이벤트 근거만 `release`. ACK·ACTION 은 안 풂. MES 가 없으면 잡은 보류도 없어 할 일 없음 | ACK·ACTION 뒤 그대로, 종결 하나 → 그 근거만(LOT 은 다른 근거로 보류), 나머지 무효 → 풀림, 검사 무효 → 풀림, 정책 OFF 로 바꿔도 잡힌 보류는 그대로 |
| I-4 | 상세 응답 `held`(이 이벤트 근거가 살아 있나 — 포트 `holds` 읽기), 화면 "LOT 보류: {LOT} — 이 위반 근거로 보류 중" + 종결·무효 확인에 "이 위반 근거의 {LOT} 보류가 풀립니다". 목록 칸은 두지 않음(AH-8) | `$.data.held` true → 종결 뒤 false |
| I-5 | 잠금 = 검사 머리 → 이벤트(처리 트리거 advisory) → LOT(포트) | `D_자동보류_판정과_같은_LOT_근거_NCR_등록이_동시여도_교착_없음` — 다른 트랜잭션이 LOT 을 쥔 동안 판정(자동 보류)·같은 LOT 근거 NCR 등록이 둘 다 기다렸다 200, 근거 QMS_SPC·QMS_NCR |
| I-6 | A사로 B사 | B사 사용자가 A사 이벤트 종결 → 404, 보류 그대로 |

## 24. 부적합(NCR) 첨부 — 특채 고객 승인 기록 등 (2026-10-26, 마이그레이션 없음 — 12.7 Q8a-5 "나중", 상위 결정)

IATF 16949 8.7.1.1 은 특채(그대로 사용)에 고객 승인이 필요하면 그 기록을 남기게 한다. 결재(Q8a-5)는 사내 승인만 다루므로, 고객이 보낸 승인서·메일을 NCR 에 붙일 자리를 둔다.

| # | 정한 것 | 이유 |
|---|---|---|
| NF-1 | 플랫폼 첨부(`FileService`, 대상 = 화면 `qms-ncr` × `ncr_id`) 그대로 — 새 표·API 없음. 확장자·크기·개수는 플랫폼 정책 그대로 | 8D·관리문서·교정과 같은 경로(권한 = 대상 화면: 목록·내려받기 SEARCH, 올리기·삭제 SAVE) |
| NF-2 | `NcrFileLock`(`CapaFileLock` 과 같은 모양): **처리 중(OPEN)일 때만** 올리기·삭제. 특채 결재 중(PENDING)·종결·취소면 409 `FILE_LOCKED`(읽기는 됨). 반려·회수로 OPEN 이 되면 다시 풀림. 없는 NCR·숫자 아닌 id·다른 회사 NCR = 잠김 | 결재자가 보는 근거가 결재 중에 바뀌지 않게, 종결 뒤 기록이 사라지지 않게(상위 결정 2026-10-26). 다른 회사 NCR 은 RLS 로 "없음" 이라 잠김 → B사가 A사 NCR id 로 못 올림 |
| NF-3 | 잠금 판정은 잠그지 않는 상태 읽기(`NcrMapper.ncrStatus`) — `FileService` 가 받기 전·메타 기록 트랜잭션 안 두 번 부른다 | 종결·상신이 동시에 커밋돼도 "올림 → 종결" 순서와 같다(종결은 첨부를 읽지 않음). 읽기 전용 트랜잭션에서도 불려 `FOR SHARE` 를 쓸 수 없다 |
| NF-4 | 화면 `qms-ncr` 상세에 첨부 영역(8D 와 같은 모양) — 목록은 늘 보이고, 올리기·삭제 버튼은 OPEN + SAVE 권한일 때만 | 숨긴 버튼은 막은 것이 아니므로 서버(NF-2)가 막는다 |

시험 `NcrApiTest.첨부는_처리중만_결재중_종결_취소_뒤_409_A사로_B사`:

- 처리 중 올리기 → 결재 중 올리기·삭제 409(결재자 목록·내려받기 200)
- 반려 → 다시 올리기·삭제 200 → 승인(종결) 뒤 409(첨부 남음)
- 취소 뒤 409, 없는 NCR·`abc` 409
- 조회만 권한: 목록 200·올리기·삭제 403, 권한 없음: 목록 403
- A사로 B사: B사 사용자(전체 권한)가 A사 NCR id 로 올리기 409·목록 0·내려받기·삭제 404, A사 첨부 그대로

## 25. 검사 실행 엑셀 올리기 — 설계 (2026-10-27, 문서만 — 사용자 결정 2026-10-26 "5번 추천안대로", 구현은 검토 뒤)

> 13.4 "검사 실행 엑셀 올리기·측정 장비 직접 수집" 중 **엑셀만** 먼저 한다(장비 수집은 P5 Collector). 지금은 화면 붙여넣기(`insp-exec`)뿐이라, 측정기·스프레드시트에 모아 둔 값을 옮겨 적다 틀리는 일이 생긴다.
> 원칙(상위 전달): 엔진은 플랫폼 `ImportService` 재사용(템플릿 → 행 단위 오류 → 미리보기 → 확정), 검증은 화면 입력과 **같은 서비스 경로**(규칙 두 벌 금지), 측정값은 원장 규약 + 입력 방법 구분, 판정·SPC 자동 보류는 화면 입력과 같은 결과.

### 25.1 정할 것 (추천안)

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| XI-1 | 엔진 | 플랫폼 `ImportService` 그대로 — 템플릿 `QmsInspMeasImportTemplate`(코드 `QMS_INSP_MEAS`, 권한 화면 `qms-insp`: 미리보기 SEARCH·올리기·실행 SAVE). 원본은 `sys_file` 보관, 스테이징 → 미리보기(행 오류) → 실행·대사, 오류 보고서 내려받기 | 새 엔진을 만들지 않는다. 같은 화면 흐름을 품목·거래처 올리기에서 이미 씀 |
| XI-2 | 행 = 샘플 하나 | 열: `inspNo`(검사 번호)·`lineNo`(검사 행 번호)·`sampleNo`·`value`(계량)·`okYn`(계수 Y/N)·`gaugeCd`(계측기 자산 코드, 육안이면 비움)·`measuredAt`(선택, 아래 XI-6). 업무 키 = `inspNo + lineNo + sampleNo` | 화면 `MeasEntry(sampleNo, value, okYn, gaugeAssetId)` 한 개와 1:1 — 같은 검증을 그대로 태울 수 있다. 계측기는 사람이 아는 코드로(id 는 화면만) |
| XI-3 | 같은 검증 경로 | `InspService.measure` 의 **샘플 한 개 검증**(샘플 범위·같은 샘플 두 번·계량 값 형식·계수 Y/N·계측기 필수·클래스 일치·교정 상태 차단)을 메서드 하나로 떼어 화면 `measure` 와 템플릿 `validate` 가 같이 부른다. 오류 코드(`RANGE`·`SAMPLE_TAKEN`·`FORMAT`·`CODE`·`REQUIRED`·`GAUGE_CLASS`·`GAUGE_BLOCKED`·`NOT_FOUND`)도 같다. 실행은 검사·행별로 묶어 **같은 `measure` 경로**(검사 머리 `FOR UPDATE` → 다시 검증 → INSERT)를 부른다 | 미리보기 뒤 실행 사이에 상태가 바뀌어도(다른 사람이 같은 샘플 입력·검사 무효·계측기 NG) 실행이 화면과 같은 규칙으로 막는다 → 전체 롤백 409 `QMS_INSP_IMPORT_STALE`(어느 검사·행·샘플인지) — 25.5 I-3 |
| XI-4 | 이미 있는 샘플 | **새 값만**: 살아 있는 측정값이 있는 키는 오류(구현: 화면과 같은 `SAMPLE_TAKEN` — 25.5 I-2). 고치려면 화면에서 무효(정정) 뒤 다시 입력하거나 다시 올린다. 엔진 `UPSERT` 로 올리면 미리보기에서 같은 오류(템플릿이 갱신을 받지 않음 — 25.3 Q-xb) | 측정값은 원장 — UPDATE 가 없다(V099). 엑셀이 정정 경로가 되면 "누가 왜 고쳤나" 가 사라진다 |
| XI-5 | 멱등 | 같은 파일을 다시 올리면 모든 행이 `SAMPLE_TAKEN` → 실행할 것이 없다(오류 제외 실행이어도 0건). 같은 파일 안 같은 키 두 줄 = 엔진 `FILE_DUPLICATE` | 업무 키가 샘플 단위라 엔진의 기존 행 판정으로 저절로 멱등 |
| XI-6 | 원장 시각 | `occurred_at` = 파일 `measuredAt`(있으면, 회사 시간대로 해석) 또는 실행 시각. `measuredAt` 은 검사 생성 시각 이상·지금 이하(미래 거부). `recorded_at` = DB 시각(그대로) | 원장 규약 occurred/recorded. 장비·스프레드시트 값은 잰 때와 옮긴 때가 다르다 — 관리도(Q10)의 부분군 순서가 잰 시각이면 맞다(19.5 I-3 의 "측정 시각" 과 같은 칸) |
| XI-7 | 입력 방법 | `qms_insp_meas` 에 `entry_method`(`KEY`·`IMPORT`, 기본 `KEY`, CHECK)·`import_job_id`(엑셀이면 `sys_import_job` 참조, 아니면 null) 추가. 무효 행은 `KEY`. **마이그레이션 1개(QMS 표 칸 추가, 임시 V 는 상위 배정 — 25.3 Q-xc)** | 감사에서 "이 값은 어느 파일로 들어왔나" 를 원본 파일까지 따라간다(`sys_file` 보관). 생산 실적·LOT 계보의 `entry_method` 와 같은 이름(값 집합은 측정값에 맞춤) |
| XI-8 | 판정·SPC | 올리기는 **측정만** 한다 — 판정(judge)은 사람이 화면에서(지금과 같음). 그래서 판정 결과·SPC 위반 기록·자동 보류(V193 `QMS_SPC_AUTO_HOLD`)는 입력 방법과 상관없이 같은 judge 경로로 나온다 | 판정 = 사람이 확정하는 시점(Q6). 올리기가 판정까지 하면 "파일 한 번에 LOT 보류" 가 되어 오경보 비용이 커진다(23.1 의 이유와 같음) |
| XI-9 | 상태 | 검사가 처리 중(OPEN)이 아니면(판정·무효 뒤) 그 검사의 행은 미리보기에서 오류(`QMS_INSP_STATE`), 실행 때 바뀌었으면 409 `QMS_INSP_IMPORT_STALE` | 화면 `openHead` 와 같은 조건 |
| XI-10 | 잠금 | 실행은 검사 id 오름차순으로 검사 머리를 잠그며 묶음 처리(한 검사 = 한 `measure` 호출, 행 묶음은 `MAX_ENTRIES` 500 이하로 나눔) | 여러 검사를 담은 두 파일이 동시에 실행돼도 교착 없음(Q8b-9 "서버가 정렬" 과 같은 결) |
| XI-11 | 회사 | 검사 번호·계측기 코드는 RLS 안에서 찾는다 — B사 사용자 파일의 A사 검사 번호는 `NOT_FOUND`(존재 여부도 안 알려 줌) | 1부부터의 원칙 |
| XI-12 | 화면 | `insp-exec` 도구줄에 "엑셀 올리기" — 플랫폼 올리기 대화상자(템플릿 내려받기 · 미리보기 · 실행) 재사용. 안내 시트에 "검사 번호·행 번호는 검사 상세에서 확인" | 새 화면 없음. 검사 번호·행·샘플 번호를 미리 채운 템플릿은 엔진 템플릿 파일이 인자를 받지 않아(공통 변경) 다음 후보 |
| XI-13 | 하지 않을 것 | 판정·무효·검사 만들기 엑셀, 장비 파일 형식(CSV·측정기 고유) 직접 읽기, 값 정정 | 범위 = 측정 입력만. 장비 형식은 P5 |

### 25.2 시험 (구현 때)

- 정상: 검사 2건(계량·계수 섞임, 육안 행 계측기 비움) 올리기 → 미리보기 신규 N → 실행 → 상세 측정값이 화면 입력과 같고 `entry_method = IMPORT`·`import_job_id`, `occurred_at = measuredAt`, 대사 일치
- 같은 결과: 같은 값을 화면으로 넣은 검사와 엑셀로 넣은 검사를 각각 판정 → 판정·SPC 위반·자동 보류(정책 LIMIT) 결과가 같음
- 행 오류 섞임: 샘플 범위 밖·계수에 숫자·계측기 클래스 다름·교정 NG 계측기·미래 `measuredAt`·없는 검사 번호 → 그 행만 오류(화면과 같은 코드), 오류 제외 실행 → 나머지만 들어감(Q-xa 결정에 따라)
- 멱등: 같은 파일 두 번 → 둘째는 전부 `EXISTS`, 측정값 수 그대로 / 파일 안 같은 키 두 줄 → `FILE_DUPLICATE`
- 상태: 판정 뒤 검사 번호 → 미리보기 오류 / 미리보기 뒤 다른 탭에서 판정·같은 샘플 입력 → 실행 409 `IMPORT_STALE`, 하나도 안 들어감
- A사로 B사: B사 사용자가 A사 검사 번호·A사 계측기 코드로 올리기 → `NOT_FOUND`, A사 측정값 그대로
- 권한: `qms-insp` 조회만 → 미리보기만, 실행 403
- 동시: 검사 두 개를 서로 반대 순서로 담은 파일 두 개 동시 실행 → 교착 없이 둘 다 처리(또는 같은 샘플이면 뒤쪽 409)
- 직접 SQL: `entry_method` CHECK 밖 값·`import_job_id` 있는데 `KEY` 거부(CHECK), 측정값 UPDATE 여전히 거부

### 25.3 질문 (사용자·상위)

| # | 누구 | 질문 | QMS 추천 |
|---|---|---|---|
| Q-xd | 사용자 | 한 파일에 검사 여러 건을 받을까 | **예** — 업무 키에 검사 번호가 들어 있어 엔진이 그대로 받는다. 한 번에 받는 행 수는 플랫폼 정책 `IMPORT_MAX_ROWS` |
| Q-xa | 사용자 | 부분 성공(오류 행 빼고 실행)을 허용할까 | **예(엔진 기본 "오류 제외 실행")** — 빠진 샘플은 판정 때 `QMS_INSP_INCOMPLETE` 로 막히므로 덜 들어간 채 판정되는 일은 없다. 오류 보고서로 고쳐 다시 올리면 들어간 행은 `EXISTS` 로 건너뜀 |
| Q-xb | 상위 | 엔진 `UPSERT` 를 이 템플릿에서 막는 방법 — (가) 템플릿이 기존 행을 오류로 돌려줌(공통 변경 없음, 미리보기에서 `EXISTS` 와 같은 문구) / (나) `ImportTemplate` 에 `default boolean allowsUpsert()` 1줄(공통 변경) | (가) — 공통 변경 없이 같은 결과. 다른 원장 템플릿이 생기면 그때 (나) |
| Q-xc | 상위 | 마이그레이션 1개(`qms_insp_meas` 칸 2개) 임시 V 번호 | V9227 은 OCAP(26장 예정) 몫이라 **V9228** 을 받거나, 구현 순서대로 앞선 쪽이 V9227 |
| Q-xe | 사용자 | `measuredAt` 을 필수로 할까 | **선택** — 비우면 실행 시각. 장비 기록이 있으면 넣게 안내 시트에 적음 |

### 25.4 검토 (상위 세션·사용자 작성)

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-27 | 사용자 | **추천대로**: Q-xd 예(한 파일 여러 검사) · Q-xa 예(오류 제외 실행, 빠진 샘플은 판정 때 `QMS_INSP_INCOMPLETE`) · Q-xe 선택(비우면 실행 시각) |
| 2026-10-27 | 상위 | Q-xb (가) 템플릿이 기존 행을 오류로 — 공통 변경 없음. Q-xc **V9228** 배정(V9227 은 OCAP 26장 몫, QMS 다음 임시 번호 V9229). 구현 PR 은 이 장 하나만, 판정·SPC 결과가 화면 입력과 같다는 시험 포함 |

### 25.5 구현 (2026-10-27, V196·임시 V9228 — 25.4 결정 반영)

| # | 구현 | 시험 (`InspMeasImportTest`) |
|---|---|---|
| I-1 | V196(임시 V9228): `qms_insp_meas.entry_method`(KEY·IMPORT, 기본 KEY)·`import_job_id`(FK `sys_import_job`) + CHECK(IMPORT ⇔ 작업 있음, 무효 행은 KEY) + 트리거(작업이 같은 회사·`QMS_INSP_MEAS` 템플릿) | 직접 SQL: 모르는 방법·IMPORT 인데 작업 없음·KEY 인데 작업·B사 작업·무효 행 IMPORT 거부, UPDATE 42501 |
| I-2 | `QmsInspMeasImportTemplate`(`QMS_INSP_MEAS`, 화면 `qms-insp`): 행 = 샘플, 키 = 검사 번호\|행 번호\|샘플 번호. 검증은 `InspService.checkEntry`(화면 `measure` 에서 떼어 낸 샘플 하나 검증 — 같은 메서드)를 부르고 칸 이름만 파일 열로(계측기 id → `gaugeCd`). 이미 값이 있는 샘플은 화면과 같은 `SAMPLE_TAKEN` 오류, `find` 는 언제나 null(Q-xb 가 — 모드와 상관없이 갱신 없음) | 행 오류 11가지가 그 행만(화면과 같은 코드 — 같은 계측기 클래스 오류를 화면 API 로도 확인), 오류 제외 실행 1건·대사 일치, 같은 파일 다시 → 전부 `SAMPLE_TAKEN`·0건, UPSERT 로 올려도 바뀜 0 |
| I-3 | 실행 `insertAll`: 검사 id → 행 id 오름차순으로 묶어 `InspService.measureImported`(화면 `measure` 와 같은 `record` 경로 — 머리 FOR UPDATE → 다시 검증 → INSERT, 출처 IMPORT + 작업). 실패하면 `QMS_INSP_IMPORT_STALE`(검사·행·샘플)로 전체 롤백 | 미리보기 뒤 같은 샘플 화면 입력 → 409(다른 검사 몫도 0), 미리보기 뒤 검사 무효 → 409, 판정된 검사 번호 → 미리보기 `inspNo:STATE`, 반대 순서 두 파일 동시 실행 → 둘 다 200 |
| I-4 | `measuredAt`(선택): `yyyy-MM-dd HH:mm[:ss]`, 회사 시간대, 미래 `FUTURE`·검사 시작(분 단위) 전 `BEFORE_START`, 비우면 검증 시각 → `occurred_at` | 적은 샘플의 `occurred_at` = 적은 분, 미래 → 오류 |
| I-5 | 판정·SPC: 올리기는 측정만 — judge 는 화면 그대로 | 같은 값을 화면·엑셀로 넣은 두 검사를 판정 → 결과·SPC 위반 규칙·자동 보류(정책 LIMIT `Y/1`) 같음, 한 파일의 정상 검사는 위반 없음 |
| I-6 | 화면: `insp-exec` 도구줄 "엑셀 올리기"(SAVE) — 플랫폼 `hb.importDialog`, 반영 뒤 상세·목록 다시 읽기. 상세 응답 측정값에 `entryMethod` | 조회만 권한 → 올리기 403, B사 사용자가 A사 검사 번호 → `inspNo:NOT_FOUND`·0건, A사 사용자는 B사 작업 404 |

### 25.6 QA 후속 결정 (2026-10-27 사용자 "추천대로" — QA docs/qa/2026-10-27_qms-import-ocap-pda.md I-1·I-2, 상위 전달)

| # | 결정 | 구현 · 시험 |
|---|---|---|
| I-1 | 엑셀로 올린 **지난** 측정도 **측정 시각 기준**으로 계측기 교정 상태 판정 — 그 시각(회사 날짜)에 OVERDUE·NG 였으면 `GAUGE_BLOCKED`. 전에는 오늘 기준이라 그때 기한 초과였던 계측기를 오늘 재교정하면 받았다 | `GaugeCalibService.statusAt(asset, at)`: `GaugeMapper.gaugeAsOf` = 교정일 ≤ 그날의 마지막 유효 교정(목록 base 와 같은 순서) + `GaugeStatus.of(…, 그날)`. `InspService.checkEntry` 가 이것을 부르고 캐시 키를 (계측기, 날짜)로. **화면 입력**은 at = 지금이고 교정일은 미래를 받지 않으므로 지금 상태와 같다(의미 불변). 사용 여부는 이력이 없어 지금 값(지금 사용 중지면 지난 측정도 거부). 시험: 370일 전 교정(기한 5일 전)·오늘 재교정 계측기, 검사 시작 3일 전 → 2일 전 측정 거부·비운 시각 통과·화면 입력 통과 |
| I-2 | 검사 시작 전 비교를 **초 단위**로 — 같은 분 안이라도 시작 전이면 `BEFORE_START`(전에는 시작 시각을 분으로 내려 같은 분의 이른 초가 들어갔다) | `QmsInspMeasImportTemplate.measuredAt`: 시작 시각의 초 미만(나노)만 버리고 비교. 안내 문구에 `[:ss]`·초 단위·측정 시각 교정 판정 추가(ko/en). 시험: 시작 30초 전(같은 분) 거부, 10초 뒤 통과(원장 occurred_at = 적은 초) |
| 남는 질문 | 측정 시각 기준 판정은 "그때 알던 교정" 이다. 측정 뒤에 들어온 **NG 교정**의 영향 구간(직전 유효 교정 ~ NG, 12.5 `impact`)에 드는 지난 측정은 지금 규칙으로는 받는다(그 시각엔 OK) — 받은 뒤 영향 LOT 조회에 나온다. 올리는 순간 막을지는 정책 질의로 남김(이번 결정 범위 밖) | — |
| 25.6-Q 결정 (2026-10-27 사용자 "추천대로") | **올리는 순간 막는다** — 엑셀로 올리는 지난 측정의 측정 시각이 그 계측기의 NG 교정 영향 구간(직전 유효 교정 ~ NG, 12.5 `impact`)에 들면 `GAUGE_BLOCKED`(사유: 교정 NG 영향 구간). 업로드 시점엔 이미 그 구간이 불량이었음을 안다 — 받으면 불량 계측기 값으로 판정이 나가 조용히 틀린다. 화면 입력은 at = 지금이라 해당 없음 | **구현(2026-10-27, 마이그레이션 없음)**: `GaugeMapper.inNgWindow(asset, 날짜)` — 무효 아닌 NG 교정 n 중 `n.교정일 ≥ 측정일` · `직전 유효(OK·LIMITED) 교정일(없으면 -∞) ≤ 측정일` 이 하나라도 있으면 영향 구간. `GaugeCalibService.statusAt` 이 그 시각 판정이 쓸 수 있음이어도 구간이면 NG → `checkEntry` 가 `GAUGE_BLOCKED`(문구에 "교정 NG 영향 구간" 추가, ko/en). ~~같은 날 NG 뒤 재교정은 재교정을 따름~~ → **G-1 결정(2026-10-28 사용자, QA docs/qa/2026-10-28_grid-s2-f1-d2-calib.md 안 가)**: NG 날은 같은 날 재교정이 있어도 하루 전체가 영향 구간(교정은 날짜만이라 시각 비교 불가, 회사 시간대 날짜) — 영향 조회(impact)와 같은 경계. 판정 규칙은 화면·엑셀 한 벌이라 **화면 입력도** 오늘 NG 뒤 같은 날 재교정한 계측기는 오늘 거부, 다음 날부터 사용. 시험 `InspMeasImportTest.NG_교정_영향_구간의_…`: 100·30일 전 OK, 5일 전 NG, 2일 전 OK → 35일 전 통과·10일 전 거부·1일 전 통과·화면 통과, 같은 날 NG→OK 계측기 화면 거부(G-1). QA `InspImportQaTest.결정_G1_…`(관찰 시험 뒤집음): 같은 날 재교정이어도 NG 날 측정 거부·영향 조회에도 없음 |

## 26. OCAP 표준 조치 목록 — 설계 (2026-10-27, 문서만 — 사용자 결정 2026-10-26 "2번 추천안대로", 1단계, 구현은 검토 뒤)

> 23.1 AH-11 이 "체크리스트는 이벤트 ACTION 기록으로 대신 — 표준 조치 목록을 따로 관리하려면 새 기준정보가 필요(다음 후보)" 로 남긴 것. OCAP(Out of Control Action Plan) = 관리 이탈 때 현장이 따를 정해진 조치 순서(AIAG SPC 매뉴얼의 관리계획 반응 계획과 같은 결). 1단계는 **SPC 위반 ACTION 에서 고르는 목록**까지.
> 원칙(상위 전달): 새 기준정보 표 1개(규칙별 조치 체크리스트, 회사별 RLS + 감사 칸 + `row_ver`), 임시 **V9227**. 고른 시점의 문구를 사본으로 남겨 목록을 고쳐도 옛 기록은 그대로. 사용 중지는 `use_yn`(지우지 않음).

### 26.1 정할 것 (추천안)

| # | 항목 | 정한 것 | 이유 |
|---|---|---|---|
| OC-1 | 기준정보 표 | `qms_ocap_item`: `ocap_id`, `comp_cd`, `rule_cd`(`WE1`~`WE4`·`R1`, **null = 모든 규칙 공통**), `seq`(표시 순서), `action_text`(varchar 500, 공백만 거부), `use_yn`, 감사 칸, `row_ver`. RLS·FORCE·`tenant_isolation`, 같은 규칙 안 같은 문구 중복 거부(살아 있는 행끼리 — 부분 유니크 `use_yn = 'Y'`) | 규칙마다 해야 할 일이 다르다(WE1 한계 밖 = 즉시 격리·재측정, WE2~4 추세 = 원인 조사). 공통 항목(측정 재확인 등)은 규칙마다 반복해 적지 않게 null |
| OC-2 | 고른 기록 | 새 원장 표 `qms_spc_event_act_ocap`(**추가만**): `act_id`(ACTION 처리 행) × `ocap_id` + **`action_text` 사본**·`rule_cd` 사본. UPDATE/DELETE 차단 트리거, 같은 처리 행에 같은 항목 두 번 거부. 처리 행이 ACTION 이 아니면 거부(트리거) | 상위 원칙 "고른 시점 문구 사본". `qms_spc_event_act` 칸을 늘리지 않고 자식 표로 — 한 번의 조치에서 여러 항목을 체크하는 것이 체크리스트의 모양(Q-oa). 원장 규약(V157 과 같이 추가만) |
| OC-3 | 마이그레이션 | 위 두 표를 **한 파일 V9227**. `qms_spc_event_act` 는 건드리지 않음 | 상위 배정 임시 번호 1개 |
| OC-4 | 고르기 규칙(서버) | ACTION 입력에 `ocapIds`(선택, 최대 20) — 각 id 는 **같은 회사(RLS)·사용 중(`use_yn = 'Y'`)·규칙이 그 이벤트 규칙이거나 공통**이어야 한다. 하나라도 어긋나면 400 칸 오류(`ocapIds[i]` — `NOT_FOUND`·`INACTIVE`·`RULE`), 처리 행도 안 남음(같은 트랜잭션). 메모(`note`)는 지금처럼 필수 | 숨긴 항목은 막은 것이 아니다 — 사용 중지 항목·다른 규칙 항목·B사 항목 id 를 API 로 직접 보내도 서버가 막는다. 메모 필수는 V157 CHECK 그대로(체크만 하고 끝내지 않게 — Q-ob) |
| OC-5 | 사본 시점 | 처리 행 INSERT 와 같은 트랜잭션에서 그 순간의 `action_text`·`rule_cd` 를 읽어 사본으로 넣는다. 목록 행은 `FOR SHARE` 로 읽음 | 고르는 순간 다른 사람이 문구를 고쳐 커밋해도 "고른 사람이 본 문구 또는 고친 문구" 중 하나로 일관 — 반쯤 섞이지 않음. 사용 중지와 동시여도 둘 중 하나가 먼저 |
| OC-6 | 목록 고치기 | 문구·순서·규칙 고치기는 `row_ver` 낙관적 락(옛 값 409), 사용 중지·다시 사용 = `use_yn`. **삭제 API 없음**(앱 계정 DELETE 도 트리거로 거부 — 사본이 남아 있어도 원 항목을 따라갈 수 있게) | 상위 원칙. 고쳐도 옛 사본(OC-2)은 그대로라 감사 기록이 바뀌지 않는다 |
| OC-7 | 화면 — 목록 관리 | 새 화면 `qms-ocap`(sys_prog, 메뉴 품질 기준정보): hb.grid 주 목록(규칙·순서·문구·사용) + 서랍 편집. checklists §2 그대로 — `@ProgAuth`(SEARCH·SAVE), 도움말 4키·`help-flow.json`(ScreenHelpGuardTest), `DevCompanySeed`·`AuditViewService` 등록, 문구 ko/en | 기준정보는 기준정보 화면에서(샘플링·불량 유형과 같은 자리). 관리도 화면에 끼우면 권한(관리도 저장 ≠ 기준정보 저장)이 섞인다 — Q-oc |
| OC-8 | 화면 — 고르기 | `qms-spc` 위반 상세의 처리 폼에서 ACTION 을 고르면 그 이벤트 규칙 + 공통의 사용 중 항목이 체크박스로(순서대로). 처리 이력에는 고른 항목의 **사본 문구**를 보여 준다(목록이 바뀌어도 그때 문구) | 고르는 사람이 지금 목록을, 감사자는 그때 문구를 본다 |
| OC-9 | 조회 계약 | 1단계는 QMS 안에서만(외부 계약 없음) | 다른 모듈이 읽을 수요 없음 |
| OC-10 | 하지 않을 것 | 항목별 담당자·기한·완료 추적, 항목 체크를 종결 조건으로 묶기(모두 체크해야 CLOSE), 관리계획서(APQP) 반응 계획과 자동 연결, 품목별 목록 | 1단계 = 고르기·사본까지. 종결 조건·추적은 사용해 보고 2단계 후보 |

### 26.2 시험 (구현 때)

- 정상: 목록 등록(WE1 2개·공통 1개·R1 1개) → WE1 이벤트 ACTION 에서 WE1 2 + 공통 1 고르기 → 처리 행 1 + 사본 3, 처리 이력에 사본 문구
- 목록 수정 뒤 옛 기록 불변: 문구를 고치고 사용 중지 → 옛 처리 이력은 고치기 전 문구 그대로, 새 고르기 목록에는 바뀐 문구·중지 항목 없음
- 사용 중지 항목 고르기(API 직접) → 400 `INACTIVE`, 처리 행 없음
- 다른 규칙 항목(R1 항목을 WE1 이벤트에) → 400 `RULE`
- A사로 B사: A사 사용자가 B사 항목 id 로 고르기 → 400 `NOT_FOUND`(존재도 안 알려 줌), B사 목록 화면에 A사 항목 0, B사 사용자가 A사 항목 고치기·사용 중지 → 404
- 상태: CLOSE·VOID 된 이벤트에 ACTION(항목 포함) → 지금과 같은 거부, 사본 없음 / ACK·CLOSE·VOID 에 `ocapIds` → 400
- 동시: 고르는 순간 다른 트랜잭션이 문구 수정 → 사본은 둘 중 하나의 문구 그대로(섞임 없음), 고치기 두 건 동시 → 뒤쪽 409
- 이상 입력: 같은 id 두 번·21개·null id·공백 문구·501자 → 400, 같은 규칙 같은 문구 두 번 등록 → 409
- 직접 SQL: 사본 표 UPDATE/DELETE 거부, 목록 DELETE 거부, ACK 처리 행에 사본 INSERT 거부, B사 `comp_cd` 로 INSERT → RLS 거부
- 화면(1366·390): 목록 관리 hb.grid·서랍, 처리 폼 체크박스·이력 사본 문구, 도움말 가드 시험 통과

### 26.3 질문 (사용자·상위)

| # | 누구 | 질문 | QMS 추천 |
|---|---|---|---|
| Q-oa | 사용자 | 한 번의 조치(ACTION)에서 항목을 **여러 개** 고를 수 있게 할까 | **예** — 체크리스트 모양. 사본은 항목마다 한 행 |
| Q-ob | 사용자 | 항목을 골랐으면 메모를 비워도 될까 | **아니요(지금처럼 필수)** — "무엇을 봤고 결과가 어땠나" 는 메모에 남는다. 항목만 체크하고 끝내면 감사 질문(무엇을 했나)에 답이 안 됨 |
| Q-oc | 사용자 | 목록 관리를 새 화면(`qms-ocap`, 품질 기준정보 메뉴)으로 둘까, 관리도 화면 탭으로 둘까 | **새 화면** — 기준정보 권한을 관리도 저장 권한과 나눔 |
| Q-od | 상위 | 마이그레이션 1개에 표 2개(목록·사본)를 둬도 되나(상위 원칙 "새 기준정보 표 1개" 에 사본 원장 표가 하나 더) | 예 — 사본을 `qms_spc_event_act` 칸으로 넣으면 여러 항목을 못 담고 원장 표를 바꿔야 한다. 기준정보 표는 1개 그대로 |

### 26.4 검토 (상위 세션·사용자 작성)

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-27 | 사용자 | **추천대로**: Q-oa 한 조치에서 여러 항목 예 · Q-ob 항목을 골라도 메모 필수(지금처럼) · Q-oc 새 화면 `qms-ocap` |
| 2026-10-27 | 상위 | Q-od 승인 — 임시 **V9227** 하나에 표 2개(기준정보 `qms_ocap_item` + 고른 기록 사본 원장 `qms_spc_event_act_ocap`). 사본 원장은 UPDATE/DELETE 차단, 새 화면은 checklists §2(도움말 4키·help-flow.json) |

### 26.5 구현 (2026-10-27, V198·임시 V9227 — 26.4 결정 반영)

| # | 구현 | 시험 (`OcapTest`) |
|---|---|---|
| I-1 | V198(임시 V9227): `qms_ocap_item`(규칙 null = 공통, 순서·문구·`use_yn`·감사·`row_ver`, 사용 중 같은 규칙·문구 부분 유니크, DELETE 트리거 거부, 감사 트리거) + `qms_spc_event_act_ocap`(추가만 — 가드: 같은 회사 ACTION 처리 행·같은 회사 항목, UPDATE/DELETE 권한 회수). 둘 다 RLS·FORCE. 화면 `qms-ocap`(sys_prog) | 직접 SQL: 사본 UPDATE·DELETE 42501, 목록 DELETE·공백 문구·모르는 규칙·B사 `comp_cd` 거부, ACK 처리 행에 사본·B사 항목 사본 거부 |
| I-2 | `OcapService`·`OcapApiController`(`/api/qms/ocaps` grid·columns·등록·수정 — 삭제 API 없음): 규칙 필터(전체·공통만 `*`·규칙), 사용 여부, 문구 찾기, 낙관적 락 | 목록 4·규칙 2·공통만 1·문구 찾기, 옛 rowVer 409, 같은 규칙 같은 문구 400 `DUPLICATE`(다른 규칙·중지된 문구와는 가능), 이상 입력 칸 오류 |
| I-3 | SPC 위반 처리 `ActForm.ocapIds`: ACTION 만, 최대 20, 같은 id·null 거부 → 항목 `FOR SHARE` → 같은 회사·사용 중·규칙(이벤트 규칙 또는 공통) 확인 → 처리 행 INSERT(`returning act_id`) → 사본(공통 먼저·순서). 어긋나면 400 `ocapIds[i]`(`NOT_FOUND`·`INACTIVE`·`OCAP_RULE`), 처리 행 없음. 메모 필수 그대로(Q-ob) | 사용 중지·다른 규칙·중복·null·없는 id·21개·ACK·CLOSE + ocapIds → 400, 처리 행·사본 0, 종결 뒤 409, 메모 없이 → `note` 오류 |
| I-4 | 상세 응답 처리 이력 `acts[].ocaps`(사본), 고르기 목록 `GET /api/qms/spc/events/{id}/ocaps`(qms-spc 조회, 그 규칙 + 공통 사용 중) | 섞어 보내도 사본은 목록 순서, 문구 수정·사용 중지 뒤 옛 사본 그대로·새 목록은 바뀐 문구·중지 제외, 두 번째 조치는 바뀐 문구 |
| I-5 | 사본 시점(OC-5) — `FOR SHARE` 로 동시 수정과 줄 세움 | `D_…` 다른 트랜잭션이 문구 수정을 쥔 동안 고르기는 기다렸다 커밋 뒤 200, 사본 = 고친 문구 하나 |
| I-6 | 화면: `qms-ocap`(hb.grid + 서랍, 규칙 빈 값 = 공통, 조회 "공통만"), `qms-spc` 조치 폼에 체크 목록(ACTION 일 때만), 처리 이력에 "✓ 사본 문구". 도움말 4키·`help-flow.json`(qms-ocap → qms-spc)·docs/46 한 줄·`DevCompanySeed`·`AuditViewService`·보존 표 | 권한: OCAP 권한 없으면 목록·저장 403, OCAP 조회만이면 수정 403, SPC 조회만이면 고르기 목록 200·조치 403 / A사로 B사: B사 항목 고르기 `NOT_FOUND`·처리 행 0, B사 목록에 A사 0, B사가 A사 항목 고치기 404 |

## 27. 종품(LAST_OFF) 판정 — 같은 지시·공정 (2026-10-29 사용자 결정 "13.4 보류 1 추천대로", 근거 IATF 16949 8.5.1.3)

> 셋업 뒤 첫 제품(초품)과 작업 끝 마지막 제품(종품)을 비교해 공정이 작업 내내 유지됐는지 확인한다(8.5.1.3 "last-off part comparison"). 검사 유형 `LAST_OFF` 와 검사계획은 이미 있다 — 판정 흐름(생성·측정·판정 확정)은 검사 실행(Q6)을 그대로 쓰고, **같은 지시·공정 단위로 묶어 보는 것**과 **공정 완료 때 경고**를 더한다.

### 27.1 정한 것

| # | 결정 | 근거 |
|---|---|---|
| LO-1 | 초품·종품 판정 = `FIRST_OFF`·`LAST_OFF` 유형 검사의 판정 확정(JUDGED, OK/NG). 무효 검사는 보지 않음. 새 표 없음 | 측정 근거·판정자·무효 규칙이 이미 검사 원장에 있다. 셋업 검증(`qms_setup_verif`, ECO × FIRST_LOT, Q7)은 ECO 단위라 그대로 두고 섞지 않는다(어긋남 → 상위 확인 요청 2026-10-29) |
| LO-2 | "같은 지시" = 검사 LOT 의 작업지시 — MES `LotLookup.lots`(R-2) 계약으로만. 지시 없는 LOT(입고·재작업)·MES 없음이면 짝을 찾지 않는다 | MES 표 직접 조회 금지(12.9 G 원칙). 역조회(지시 → LOT) 계약을 새로 만들지 않으려고 같은 품목·공정의 초품·종품 검사를 최근 200건(`CANDIDATE_LIMIT`) 읽어 LOT 의 지시로 거른다 — 넘는 옛 검사는 짝에서 빠질 수 있음(상한은 한 번 조회량) |
| LO-3 | 한쪽 상태 = 가장 최근 판정(judged_at)의 결과(OK·NG), 판정 없이 검사만 있으면 PENDING, 없으면 NONE | 재검사(NG 뒤 다시 OK)면 마지막 판정이 지금 상태 |
| LO-4 | 화면: 검사 실행 상세에서 초품·종품 검사일 때 "같은 지시·공정의 초품 | 종품" 두 칸(좁으면 한 칸), 검사 번호를 누르면 그 검사 | 같은 화면에서 비교(별도 화면 없음) |
| LO-5 | 공정 완료 때 종품 판정이 없거나 NG 면 **경고만**(막지 않음) — MES 확장점(27.3, 계약 대기) | 사용자 결정. 막으면 종품 검사 대기로 완료가 늦어진다 — 경고로 기록·확인 |
| LO-6 | 인덱스 `ix_qms_insp_off`(V202·임시 V9231, 초품·종품만 부분 인덱스) | 짝 후보 조회가 다른 유형(수입·출하·공정)을 훑지 않게 |

### 27.2 1단계 구현 (2026-10-29, V202·임시 V9231)

| 무엇 | 어디 |
|---|---|
| 짝 조회 | `OffInspService.pair(inspId)` · `GET /api/qms/insps/{id}/off-pair`(qms-insp 조회) |
| 완료 경고용 조회 | `OffInspService.lastOff(procId, 산출 LOT 들)` — 그 공정·LOT 의 종품 상태 |
| 화면 | `insp-exec` 상세 `#offPair`, 문구 `js.qms.insp.off*`(ko/en) |
| 시험 | `OffInspTest` 5 — 짝·다른 지시/공정/무효 빠짐 · PENDING·최근 판정·종품 NG · 지시 없는 LOT · 완료 경고 조회(다른 공정 안 셈) · 권한 403 · A사로 B사 404/안 보임 |

### 27.3 2단계 — 공정 완료 경고 (2026-10-29, 마이그레이션 없음 — MES CloseNotice #661 위에)

| # | 내용 |
|---|---|
| CN-1 | MES 확장점 `production.link.CloseNotice`(docs/25 4.46, MES 세션 #656→#661) — 완료판정 미리보기·판정에서 모든 빈을 부르고 `notices` 로만 보인다(막는 이유 `problems` 와 따로), 빈 예외는 세이브포인트로 되돌리고 무시 |
| CN-2 | QMS 구현 `QmsLastOffNotice`(@Order 100): 그 품목·공정에 **오늘 유효한 종품 검사계획이 있을 때만** — 계획이 없는 공정은 종품을 요구하지 않으므로 경고하지 않는다(모든 공정에 경고하면 소음, 착수 게이트 Q9-2 와 같은 계획 고르기) |
| CN-3 | 산출 LOT(CloseContext.outputLotIds)들의 그 공정 종품 상태(`OffInspService.lastOff`): 없음 → `QMS_LAST_PENDING`(`qms.close.lastNone`), 판정 전 → `QMS_LAST_PENDING`(`qms.close.lastPending`, 검사 번호), 가장 최근 판정 NG → `QMS_LAST_NG`(`qms.close.lastNg`, 검사 번호·LOT, ref INSP), OK → 없음 |
| CN-4 | 읽기만·잠금 없음, 문구는 서버 키(ko/en) — MES 가 그 언어로 풀어 "확인 필요:" 줄로 보인다 |
| 시험 | `OffInspTest.공정_완료_경고는_…` — 없음·판정 전·NG(판정 전 검사가 같이 있어도 NG)·재검사 OK 는 없음·계획 없는 공정은 없음·B사 범위는 없음. 브라우저: DEMO 지시 WO2610-0001(진행 중으로 둠) 완료판정 표의 SPOT 공정 두 줄에 경고, 다른 공정(종품 계획 없음)은 없음 |

### 27.4 짝 찾기 — 지시의 LOT 먼저 (2026-10-30 사용자 결정 Q-1 "추천대로", QA docs/qa/2026-10-29_pms-wms-qms-mes.md Q-1)

| # | 내용 |
|---|---|
| LW-1 | 지금까지는 같은 품목·공정의 최근 200건을 읽고 지시로 걸러, 다른 지시 검사가 200건을 넘으면 옛 지시의 짝이 "없음" 으로 보이고 잘림 표시도 없었다 |
| LW-2 | MES 계약 `LotLookup.lotsOfWo(woId, limit)` 추가(상위 승인 A — 이 PR 이 MES 3파일 추가): 지시가 만든 생산 LOT(wo_id 있는 행 = PRODUCED), lot_id 순, limit 1~500 으로 자름, 읽기만·RLS, **기본 구현 = 빈 목록**(NONE·옛 구현 호환) |
| LW-3 | QMS `pair`: ① 지시 LOT 들(최대 500)의 초품·종품을 SQL 에서 바로 거름(`offOfWoLots`, 공정 일치·무효 제외) ② LOT 을 못 받으면 지금 방식(품목·공정 최근 200 → 지시로 거름). ① 이 500 에 걸리면 ② 를 더해 합침(검사 id 로 중복 제거). 응답에 `basis`(WO_LOTS·RECENT)·`truncated` |
| LW-4 | 화면 `insp-exec` 초품·종품 칸: truncated 면 "검사가 많아 일부만 보았습니다 — 오래된 초품·종품이 빠졌을 수 있습니다" |
| 시험 | `OffInspTest.짝은_지시_LOT_으로_먼저_…` — 다른 지시 검사 202건 뒤에도 ① 은 옛 짝을 찾음(truncated false), 옛 구현(lotsOfWo 빈 목록)은 RECENT·truncated·NONE, 상한 밑이면 대체도 찾음 · `LotLookupTest.지시의_LOT_은_…`(MES) — lot_id 순·다른 지시·입고 LOT 빠짐·상한 1/0/99999·없는 지시·B사 범위 빈 목록·NONE |

## 28. SPC 확장 — p 관리도(불량률) (2026-10-29 사용자 결정 "13.4·Q10-9 보류 3 추천대로", V207·임시 V9232)

> 계수형(OK/NG) 특성은 지금까지 관리도가 없었다(12.6 은 X̄-R 만). 사용자 결정: **p 관리도만** — 새 입력 없이 검사 원장의 판정 확정 검사 행으로 그리고, 위반 이벤트 원장·자동 보류·OCAP 경로를 그대로 쓴다.

### 28.1 정한 것

| # | 결정 | 근거 |
|---|---|---|
| PC-1 | 부분군 = 판정 확정 검사의 계수형 행 하나. n = 살아 있는 OK/NG 샘플 수(무효 행·무효된 원래 행 제외), d = NG 수, p = d/n. 진행 중·무효 검사 제외 | X̄-R 과 같은 원장 규칙(12.6). 정정이 섞이면 n·d 가 조용히 틀린다 → 시험으로 고정 |
| PC-2 | 한계 p̄ ± 3√(p̄(1-p̄)/n_i) — 부분군마다 다른 한계(표준식, 하한 0·상한 1 에서 자름). p̄ = Σd/Σn(보이는 구간) 또는 고정 한계 | 부분군 크기가 다르면 평균 n 근사 대신 부분군별 한계가 표준(Montgomery, Introduction to SQC 7장; AIAG SPC 2판 p 관리도) |
| PC-3 | 고정 한계 저장은 X̄ 와 같은 표·모양: `chart_type = P`, center = p̄, sigma = √(p̄(1-p̄)) → 한계 = center ± 3σ/√n. 20 부분군 이상, p̄ 가 0 또는 1 이면 못 냄(`SPC_NO_VARIATION`) | 표·가드·이력 하나로. p̄ = 0 이면 σ = 0 이라 한계가 0 에 붙어 모든 불량 1개가 위반이 된다 |
| PC-4 | 규칙은 **WE1(한계 밖 1점)·WE4(중심 같은 쪽 8점)만**, 표준화 z_i = (p_i - p̄)/√(p̄(1-p̄)/n_i) 로 판정. WE2·WE3(2σ·1σ 구역)은 요청·저장 모두 거부(`SPC_P_RULES`, DB CHECK) | 구역 규칙은 정규분포의 구역 확률을 전제 — p 는 이항분포라 n·p̄ 가 작으면 치우쳐 구역 확률이 설계값과 다르다(오경보·누락). 한계 밖 점과 한쪽 연속(런)은 분포 모양에 덜 민감해 계수형 관리도에 흔히 쓴다 |
| PC-5 | 위반 원장 재사용: `qms_spc_event.chart_type = P`, point_mean = p, point_range = d, n ≥ 1(전수면 수백), center·sigma = 한계 사본. 가드가 한계와 같은 종류인지 확인. 자동 보류(정책 LIMIT 은 WE1)·알림·OCAP·처리 기록은 그대로 | 이벤트 이후 흐름(19·23·26장)을 새로 만들지 않는다 |
| PC-6 | 단위 요약(H5) 투영이 계수형 특성도 센다 — 단위 목록에 "관리도" 칸(X̄-R / p) | 단위 목록이 관리도 입구 |
| PC-7 | **하지 않음(수요 뒤)**: X̄-S·np·c·u 관리도, Nelson 규칙 5~8, p 관리도 공정능력(불량률 → ppm 환산) | 사용자 결정 범위 밖 — 수요가 생기면 이 장에 추가 |

### 28.2 구현 (2026-10-29, V207·임시 V9232)

| 무엇 | 어디 |
|---|---|
| 마이그레이션 | V207(임시 V9232): `qms_spc_limit.chart_type`(+CHECK: P 면 규칙 WE1·WE4 부분집합·0 < center < 1), 한계 가드(관리도 종류 ↔ 특성 자료형, 고정 칸에 chart_type), `qms_spc_event.chart_type`(+n·규칙 CHECK), 이벤트 가드(한계 종류 일치), 단위 요약 계산·트리거 ATTRIBUTE 포함 + 기존 계수형 단위 채우기 |
| 계산 | `SpcCalc.pChart`·`pSigma`·`P_RULES` |
| 조회·한계 | `SpcService.computeP`(chartType P, 점 mean = p·range = d·xUcl/xLcl = 그 점 한계), 고정 한계 저장에 chart_type |
| 판정 훅 | `SpcEventService.onJudgedP` — 고정 p̄ 로 그 행까지 그려 마지막 점 규칙마다 이벤트(P) |
| 화면 | `qms-spc`: p 면 제목 "p 관리도 (불량률)", R 관리도·공정능력 숨김, 규칙 2·3 끔, 표 칸 "불량률 p·불량 수", 위반 상세 "불량률·불량 수·n·z" |
| 시험 | `SpcCalcTest` +2(손 검산·고정 p̄·n 0 제외·WE4·p̄ 0), `SpcPChartTest` 4(손 검산 API·정정 빼기·진행 중 빼기·규칙 400·단위 칸 · 고정 한계·판정 이벤트 P · p̄ 0 · 권한·A사로 B사·직접 SQL), 기존 `SpcApiTest`·`SpcEventTest` 기대 갱신(계수형 400 → p 관리도, 단위 1 → 2) |

## 29. COPQ 품질 비용 1단계 — 품목 표준단가 × 종결 NCR 처리 + 수동 행 (2026-10-29 사용자 결정 "단가 출처 = 품목 표준단가", V209·임시 V9233)

> docs/35 A3·docs/47 4번(Q-Cost/PAF)의 1단계. 품질 문제로 든 비용을 달마다 예방·평가·내부 실패·외부 실패(PAF 4구분, ASQ·Juran/Feigenbaum 분류)로 모아 본다. **회계 원장이 아니라 관리용 추정** — 화면에 그대로 적는다. 수량은 이미 있는 원장에서, 단가는 품목 표준단가(새 칸)에서, 원장에서 못 뽑는 비용(예방·평가 대부분)은 수동 행으로.

### 29.1 정한 것

| # | 결정 | 근거 |
|---|---|---|
| CQ-1 | 금액 = 수량 × 품목 표준단가. 화면 위에 "회계 원장 아님(관리용 추정)". 통화 = 회사 기준 통화(칸 없음 — 회사 통화가 하나라는 전제, 다통화는 하지 않음) | 사용자 결정. 회계 원가(실제 원가·배부)는 ERP 몫 — 품질 회의용 크기 비교가 목적 |
| CQ-2 | `mst_item.std_price numeric(18,4)` — 기본 단위 1개당, 0 이상, **비우면 단가 없음(0 과 다름)**. ERP 주인 필드(`ItemService.ERP_OWNED` 에 추가 — 정책 `ERP_ITEM_SYNC_MODE=PULL` 이면 화면·엑셀에서 바꾸면 400 `ERP_OWNED`), 그 밖은 직접 입력. 품목 화면·목록 칸·엑셀 템플릿 **맨 끝 열** | ERP 품목 주인 규칙(docs/07 3.1·docs/22 2장)을 그대로. 엑셀 열을 끝에 붙여 이미 받아 둔 양식·위치로 채우는 기존 시험이 그대로 맞게(머리글 이름으로 읽지만 순서도 안 바꿈) |
| CQ-3 | **사건 시점 단가**: 단가가 바뀔 때마다 `mst_item_price_hist` 에 한 행(트리거, 비우면 null 행). COPQ 는 NCR **종결 시각**에 유효했던 단가(그 시각 이전 마지막 이력)를 쓴다. 첫 단가 등록 전에 종결된 사건은 "단가 없음" — 나중 단가를 거슬러 쓰지 않는다 | 현재 단가로 다시 계산하면 단가를 고칠 때 **지난 달 금액이 조용히 바뀐다**(보고한 숫자가 바뀜). 이력 표는 추가만·트리거로만 써서 앱이 과거 단가를 고칠 길이 없다 |
| CQ-4 | 단가가 없으면 그 줄 금액을 **비우고 건수만** 센다(`unpricedCnt`). 구분 칸·합계도 줄은 있는데 금액 있는 줄이 없으면 null("—", 0원 아님). 금액은 금액 있는 줄만 더한다 | 사용자 결정 "0원으로 세지 않는다". 단위는 늘 품목 기본 단위 — NCR 단위 = LOT 단위 = 품목 기본 단위(`hb_guard_lot_master`), LOT 이 생긴 품목은 기본 단위를 못 바꿈(`hb_check_item_unit_in_use`) → 환산 없음 |
| CQ-5 | 원장 줄 = 종결(CLOSED) NCR 의 처리 행 중 **폐기·재작업·선별**. 특채(USE_AS_IS)는 비용 없음, 반품(RETURN)은 공급자 부담이라 빼고, 진행 중·취소 NCR 도 뺀다. NCR 출처 CLAIM → 외부 실패, 그 밖 → 내부 실패. 월 = 종결 시각의 회사 날짜 | 처리 합 = 부적합 수량이 종결 조건이라(Q8a) 종결된 처리만 확정 수량. 재작업·선별도 표준단가로 매기므로 **상한 추정**(재작업 원가율은 하지 않음 — CQ-8) |
| CQ-6 | 수동 행 `qms_copq_cost`: 월(1일)·구분(PAF 4)·항목(글자 100)·금액(0 이상, 소수 2)·비고. 고치기(row_ver)·삭제(감사 기록 남음, 권한 DELETE) | 예방(교육·FMEA)·평가(검사 인건비·교정비)·클레임 배상은 원장에 금액이 없다. 항목 코드는 수요가 생기면(지금은 글자 — docs/47 의 대·중·소 분류는 하지 않음) |
| CQ-7 | 줄 금액 = 수량 × 단가를 소수 2자리 반올림(HALF_UP) 뒤 더한다. 조회는 최대 24개월 | 줄별 반올림이 화면 줄 금액과 합계를 맞춘다 |
| CQ-8 | **하지 않음(수요 뒤)**: 생산 실적 폐기·재작업(MES 표 — 아래 Q-C1), 재작업·선별 원가율, 매출 대비 비율(매출 입력처 없음), 다통화, 항목 코드 계층·연도 복사, 원장 파생 행의 손 수정 | 사용자 결정 범위 밖 |

### 29.2 구현 (2026-10-29, V209·임시 V9233)

| 무엇 | 어디 |
|---|---|
| 마이그레이션 | V209(임시 V9233): `mst_item.std_price`(+CHECK), `mst_item_price_hist`(추가만, `pg_trigger_depth` 가드로 직접 INSERT 거부, UPDATE·DELETE·TRUNCATE REVOKE, 변경 감사, RLS), `tg_mst_item_price_hist`(INSERT·UPDATE OF std_price, 값이 바뀔 때만), `qms_copq_cost`(+CHECK 월 1일·구분·금액, 감사, RLS), `ix_qms_ncr_closed`, 공통코드 `QMS_COPQ_CAT`, 화면 `qms-copq`(SEARCH·SAVE·DELETE) |
| 품목(기준정보 공유 파일) | `ItemService`(ERP_OWNED·검증 `price`·`plain`·목록 칸), `ItemDtos`, `ItemMapper.xml`, `ItemImportTemplate`(끝 열), `item-mgt.html`·`item-mgt.js`(표준단가 칸·hb.number), 문구 `mstItem.col.stdPrice`·`priceHint`·`help.mst-item.stdPrice`·`erpHint` |
| QMS | `CopqService`(집계·수동 행 검증), `CopqMapper`(`findNcrLines` — 종결 시각 단가 lateral), `CopqApiController`(`GET /api/qms/copq`, `POST·PUT /costs`, `POST /costs/{id}/delete`), 화면 `qms/copq.html`·`copq.js`(월별 4구분·원장 내역·수동 행), `AuditViewService`·`DevCompanySeed`·`help-flow.json` 등록 |
| 시험 | `CopqTest` 6 — 아래 표 |

### 29.3 질문 (상위·사용자)

| # | 질문 | 추천 |
|---|---|---|
| Q-C1 | 생산 실적의 폐기·재작업(`prd_result.scrap_qty`·`rework_qty`·`repair_qty`, `prd_rework.scrap_qty`)은 MES 표 — QMS 는 MES 표를 직접 읽지 않는다(`production.link` 계약만, #663 과 같은 원칙) | MES 가 계약 `production.link.DefectQtyQuery`(월·품목·종류별 수량·단위, 읽기만, 구현 없음 = 빈)를 내면 다음 PR 에서 원장 줄로 붙인다(출처 `PRD_SCRAP`·`PRD_REWORK`). **결정(2026-10-29 상위): 추천대로 — 상위가 MES 에 계약 요청** |
| Q-C2 | 공정 NCR(source PROCESS)의 처리 폐기와 생산 실적 폐기가 같은 개체를 두 번 셀 수 있다(Q-C1 이후) | 출처별 줄로 따로 보이고 합계에 겹칠 수 있다는 문구. 겹침 제거 규칙(예: 실적 폐기에 NCR 근거가 있으면 실적 쪽 제외)은 데이터 연결(NCR ↔ 실적)이 생긴 뒤. **상위(2026-10-29): 다음 PR 에서 출처별 행 + "같은 불량이 두 출처에 있을 수 있음" 문구로 시작, 정책 질의로 남김** |

### 29.4 2단계 — 생산 실적 불량 (2026-10-30, 사용자 결정 C-1 (라), MES 계약 DefectQtyQuery #675·#698, 마이그레이션 없음)

| # | 결정 | 근거 |
|---|---|---|
| CQ-9 | 원천 = MES 계약 `production.link.DefectQtyQuery.monthly(null, from, to)`(회사 전체 공장) — 월·품목·종류(SCRAP·REWORK·REPAIR)·원천(RESULT·REWORK) 합계. 출처 `PRD_SCRAP`·`PRD_REWORK`·`PRD_REPAIR`·`PRD_RW_SCRAP`(재작업·수리 처리의 폐기), 구분은 내부 실패 | QMS 는 MES 표를 직접 읽지 않는다(#663 원칙). 공장 거르기는 수요 뒤 |
| CQ-10 | 단가 = **그 달 말**(다음 달 1일 0시, 회사 시간대) 직전에 유효했던 표준단가 — 계약이 월 합계만 주므로 사건 시각 단가(CQ-3)를 쓸 수 없다. 달 중에 단가를 고치면 그 달 생산 줄은 그 달 마지막 값 | 지난 달 금액은 그 달 말 단가로 고정 — 나중 단가 변경이 거슬러 바꾸지 않는다(CQ-3 와 같은 목적) |
| CQ-11 | **정정은 정정한 달에 음수로**(C-1 (라)): 계약 수량이 음수면 금액도 음수로 그 달에 더한다. 화면 주석 "실적 정정은 정정한 달에 반영되어 음수로 보일 수 있습니다" | 원장 규약(역분개)과 같음 — 지난 달 보고 숫자가 조용히 바뀌지 않는다 |
| CQ-12 | 수량 단위(지시 단위)가 품목 기본 단위와 다르면 금액을 비우고 `UNIT`(건수에 셈) — 환산하지 않는다 | NCR 줄과 달리 생산 실적은 지시 단위라 다를 수 있다. 0원·임의 환산 대신 드러냄 |
| CQ-13 | 같은 불량이 NCR 처리와 생산 실적 두 출처에 보일 수 있다 — 출처별 줄로 따로 보이고 화면에 "합계가 겹칠 수 있습니다" 주석. 겹침 제거는 NCR ↔ 실적 연결이 생긴 뒤(Q-C2 정책 질의 그대로) | 상위 결정(2026-10-29) |

| 무엇 | 어디 |
|---|---|
| 서비스 | `CopqService.productionLines`·`prdSource`(DefectQtyQuery 는 ObjectProvider — MES 없는 설치본 NONE = 줄 없음) |
| 매퍼 | `CopqMapper.findMonthEndPrices` — 품목 × 달(generate_series) 그 달 말 직전 이력 단가, 다른 회사 품목은 RLS 로 빠짐(줄을 만들지 않음) |
| 화면 | `qms-copq` 원장 내역 제목 "종결 NCR 처리·생산 실적 불량", 생산 출처 이름 4개, 단위 다름 문구, 주석 2줄(그 달 말 단가·정정 음수 / 두 출처 겹침), 도움말 |
| 시험 | `CopqPrdTest` 2 — 아래 testing 표 |

## 30. 관리문서 유형 DFMEA — APQP DFMEA 확정본 배포 계약 (2026-10-29 상위 요청, 사용자 결정 D1~D9 범위, V213·임시 V9234)

> APQP 가 DFMEA(설계 FMEA, A5-2a — V211)를 확정하면 PMS `ApqpDocService` 가 `DocPort.publish` 로 QMS 문서에 올린다. 지금 QMS 문서 유형에 DFMEA 가 없어 PFMEA 로 올리거나 막혔다.

| # | 결정 | 근거 |
|---|---|---|
| DF-1 | `mst_doc.doc_type` 에 **DFMEA** 추가(CHECK 다시 만들기, 새 V — 적용된 V078 은 고치지 않음), 공통코드 `DOC_TYPE.DFMEA`(PFMEA 다음 순서), 문구 ko/en, `DocService.TYPES` | 문서 화면 유형 고르기·조회 거르기는 공통코드로 그리므로 화면 코드는 그대로 |
| DF-2 | 유형은 만든 뒤 고정(V078 규칙 그대로 — 다음 리비전에 다른 유형이면 `FIXED`), 적용 대상(품목·품목 분류·공정)·결재 정책 `QMS_DOC_APPROVAL` 은 유형과 무관 — PFMEA 와 같다 | 요청 "PFMEA 와 같게" |
| DF-3 | PLM 설계 변경 대상 유형(`hb_guard_plm_eco_target`, V187: 도면·WI·CP·INSP_STD·PFMEA)에는 넣지 않는다 | 요청 범위 밖 — 넣을지는 PLM 결정(질문으로 남김) |
| 시험 | `DocPortTest.DFMEA_…` — DocPort 로 DFMEA 새 문서(A0 작성 중)·배포 뒤 다른 유형 → `FIXED`·화면 목록 유형 거르기·코드 이름 · B사 범위는 A사 문서 안 보이고 같은 키로 B사 것 새로 · 직접 SQL 목록 밖 유형 `ck_mst_doc_type` | |

## 31. 출하 추적(리콜) 화면 qms-lot-trace (2026-10-30 상위 배정 — docs/57 2.4 그대로, T-1~T-5 승인, V217·임시 V9235)

> docs/57(WMS 출하 추적 설계)의 QMS 몫(T-4). 문제 LOT → 계보로 이어진 아래 LOT(MES `LotTraceQuery.forward`, #698) → 어느 고객에게 어느 출하로 얼마나 나갔나·어디 남았나(WMS `ShipQuery.byLots`·`lotStock`, #705). **읽기만** — 보류·회수는 LOT 보류·NCR·봉쇄로.

| # | 내용 |
|---|---|
| LT-1 | 입력: LOT 번호(줄바꿈·쉼표, 공백·중복 무시, 1~100개, 60자) + 깊이 0~10(비우면 10). 번호는 `LotLookup.byNo` — 없거나 다른 회사면 "찾지 못한 LOT 번호" 로 보이고 나머지는 계속 |
| LT-2 | 계보 `forward(뿌리, 깊이)` → 닿은 LOT 의 번호·품목은 `LotLookup.lots`, 출하·재고는 `byLots`·`lotStock` — 셋 다 500개씩 나눠 부름. 계약이 비면(MES·WMS 없는 설치본 NONE) 뿌리 자신만·출하 없음 |
| LT-3 | **잘림은 숨기지 않는다**: `TraceResult.truncated` 면 노란 줄 "계보가 상한에 걸려 일부만 — 리콜 범위가 더 넓을 수 있음" |
| LT-4 | 같은 LOT 이 여러 뿌리·경로로 나오면 출하·재고는 LOT 기준 한 번만 조회(고객별 요약의 출하 수·LOT 수도 한 번), 출하 목록은 뿌리별로 다시 묶고 경로 = 가장 얕은 줄의 via 를 거슬러 "뿌리 → … → 출하 LOT" |
| LT-5 | 결과: 고객별 요약(출하 수·관련 LOT 수·단위별 순 수량 — 연락 대상), 출하 목록(뿌리·출하 LOT·깊이·경로·고객·출하 번호(공장)·확정 시각·순 수량·팔레트·SSCC), 창고에 남은 것(LOT·품목·공장·칸(용도)·재고 상태·수량 — 가용 여부 무관). 엑셀 = 출하 목록(EXCEL 권한) |
| LT-6 | API `POST /api/qms/lot-trace`(SEARCH — 번호 목록이 길어 POST, 읽기만), `POST /api/qms/lot-trace/export`(EXCEL). 화면 등록만 V217(임시 V9235)(표 변경 없음) |
| 시험 | `LotTraceTest` 4 — 아래 testing 표. 출하 취소분 제외·역분개 계보 제외·순환은 계약 쪽(WMS `ShipTraceQueryTest`·MES `LotTraceQueryTest`)이 덮는다 |

## 32. 출하 추적 역추적(backward) 모드 (2026-11-01 사용자 결정 Q-2 "추천대로" 1번, 마이그레이션 없음)

> 31장 화면에 방향 하나를 더한다. 클레임 LOT → 그 LOT 을 만드는 데 들어간 위 LOT(MES `LotTraceQuery.backward`, #698 계약에 이미 있음) → 원인 LOT 의 출처·공급사 → 아직 남은 원인 LOT. **읽기만**. 같은 원인으로 다른 출하가 나갔는지는 "원인 LOT 으로 정방향" 으로 이어 본다(리콜 범위).

| # | 내용 |
|---|---|
| LB-1 | 요청에 `direction` = `FORWARD`(비우면)·`BACKWARD`, 그 밖(소문자·뒤 공백 포함)은 `direction` 칸 오류 400. 입력·깊이·잘림 규칙은 LT-1·LT-3 그대로 |
| LB-2 | 역추적 결과 = **원인 LOT 줄**(뿌리마다 닿은 LOT 한 줄, 뿌리 자신 depth 0 포함): 깊이·경로 "뿌리 ← … ← LOT"(가장 얕은 줄)·연결 종류(`LOT_REL_TYPE`)·출처(`LOT_ORIGIN`)·공급사·공급사 LOT 번호·**맨 처음**(leaf = 같은 뿌리의 어느 줄도 이 LOT 을 거쳐 더 올라가지 않음 — 잘림이면 아닐 수 있다는 문구) |
| LB-3 | **공급사별 요약**: 닿은 LOT 중 입고(RECEIVED)만 LOT 기준 한 번, 공급사로 묶어 LOT 수·우리 LOT 번호·공급사 LOT 번호. 공급사 없는 입고는 한 줄로 맨 뒤 |
| LB-4 | 창고에 남은 것 = 닿은 LOT 의 `lotStock`(정방향과 같은 표) — 남은 원인 LOT 을 막을 대상. 역추적은 `byLots`(출하)를 부르지 않는다 |
| LB-5 | 출처·공급사는 MES `LotLookup` 에 **기본 메서드 `origins(lotIds)` 추가**(기본 = 빈 목록 — 옛 구현·NONE 은 출처 칸이 빈다, `lotsOfWo` 와 같은 방식). MES 구현 `MesLotLookup.origins` + `LotLookupMapper.origins`(lot_master ⟕ mst_cust, RLS, 500 상한·중복·null 무시). QMS 는 `lot_master` 를 직접 읽지 않는다 |
| LB-6 | 화면: 방향 선택 → 정방향은 고객·출하 영역, 역추적은 공급사·원인 LOT 영역(재고는 둘 다). 방향을 바꾸면 이전 결과를 비운다(다른 방향 결과로 읽히지 않게). **[원인 LOT 으로 정방향]** = 맨 처음 원인 LOT(뿌리 제외, 중복 없이, 앞 100개 — 넘으면 안내)을 입력에 넣고 정방향 조회 |
| LB-7 | 엑셀: 정방향 = 출하 목록(그대로), 역추적 = 원인 LOT 줄(연결·출처는 코드 이름으로). 권한 EXCEL 그대로 |
| 시험 | `LotTraceTest` +1(역추적 줄·leaf·공급사별·남은 원인 LOT·출하 안 부름·NONE) + 입력 검사(방향)·API(역추적 조회·엑셀·잘못된 방향·A사로 B사) 보강, `LotLookupTest` +1(`origins` — 입고 공급사·공급사 없는 입고·생산 LOT·상한·A사로 B사·NONE) |

## 33. 출하 추적 결과 → 봉쇄·클레임 NCR 바로가기 (2026-11-01 사용자 결정 Q-2 2번, 마이그레이션 없음)

> 31·32장 결과에서 다음 조치 화면으로 바로 간다. **이 화면은 여전히 아무것도 만들지 않는다** — 주소로 값을 넘겨 각 화면의 등록 폼을 미리 채우고, 만들기·권한·회사 범위 검사는 그 화면(서버)이 한다. 주소 모양은 기존 바로가기(SPC 위반 → NCR·봉쇄, 교정 NG 영향 LOT → 봉쇄)와 같다.

| # | 내용 |
|---|---|
| LS-1 | **출하 줄 → [클레임 NCR]**: `/page/qms-ncr?lotNo=<출하 LOT>&source=CLAIM&desc=<고객·출하 번호·경로>` — NCR 화면의 기존 `?lotNo=&source=&desc=` 미리 채움(내용 2,000자까지 자름). 출하 줄마다(같은 LOT 이 여러 고객에게 갔으면 고객별로 따로 등록) |
| LS-2 | **남은 재고 → 품목별 [봉쇄 열기]**: 품목마다 `/page/qms-contain?itemCd=&from=&to=&lots=&reason=` — from·to = 그 품목 남은 LOT 들이 생긴 날 범위, lots = 그 LOT 번호(중복 없이, 500개까지 — 봉쇄 화면 미리 체크 상한). 봉쇄 화면이 기간 후보를 찾고 이 LOT 만 체크, 후보에 없는 번호는 그 화면이 "못 찾음" 으로 보인다(교정 NG 영향 LOT 과 같은 흐름). 정방향·역추적 둘 다(역추적이면 남은 원인 LOT) |
| LS-3 | LOT 이 생긴 날은 서버가 `StockLine.lotDate` 로 준다 — `LotView.occurredAt` 을 **회사 시간대**(`BizClock.zone()`)로 날짜화. 봉쇄 후보 기간(`ContainService.start` = 회사 시간대 0시)과 같은 기준이라 경계 날 LOT 이 빠지지 않는다. LOT 정보를 못 찾은 줄(null)은 링크에서 뺀다 |
| LS-4 | 봉쇄 사유 = "출하 추적(리콜) 뿌리 LOT … — 남은 재고" / 역추적은 "역추적 원인 LOT(뿌리 …) — 남은 재고"(500자까지). 방향을 바꾸면 링크도 비운다 |
| 시험 | `LotTraceTest` 구성 시험에 `lotDate`(회사 시간대 날짜)·`itemCd` 확인 추가. 바로가기 대상 화면의 미리 채움·권한·A사로 B사는 각 화면 시험(NCR `?lotNo` 등록 검사, 봉쇄 후보 RLS)이 덮는다. 링크 모양은 브라우저 확인 |

## 34. COPQ 엑셀 내보내기 (2026-11-01 사용자 결정 Q-2 3번, V221·임시 V9236 — 화면 동작만)

> 29장 화면의 조회 결과를 엑셀로. 제안 때는 "월별 합계·원장 줄 2시트" 였으나 **플랫폼 엑셀(`ExcelExportService`)은 시트 하나**라, 공통 계층을 고치지 않고 한 시트로 낸다(공통 변경은 착수 전 상위 확인 — 2026-11-01 지적).

| # | 내용 |
|---|---|
| CX-1 | `POST /api/qms/copq/export`(EXCEL, 본문 `fromMonth`·`toMonth`) — 조회 `CopqService.summary` 와 **같은 달 범위·같은 계산**(검증·24개월 상한·시점 단가·생산 줄 모두 그대로) |
| CX-2 | 한 시트 = 원장 줄(종류 "원장": 달·구분·출처·품목·수량·단위·표준단가·처리 건수·금액·금액 없음 사유) + 수동 행(종류 "수동": 달·구분·금액·항목·비고). 순서 = 달 → 원장 먼저 → 구분(예방·평가·내부·외부) → 출처 → 품목·항목 |
| CX-3 | 금액 빈칸 = 단가 없음·단위 다름(**0 아님** — 화면과 같은 규칙, 사유 열에 글자). 구분은 코드 이름(`QMS_COPQ_CAT`), 출처·종류·사유는 서버 문구(LABEL) — 엑셀에 코드가 그대로 나오지 않게 |
| CX-4 | 월별 합계 시트는 두지 않는다: 종류·구분·달 열로 피벗하면 화면 월별 합계와 같다(도움말에 적음). 두 시트가 꼭 필요하면 플랫폼 다중 시트 지원을 상위에 제안 |
| CX-5 | `sys_prog` `qms-copq` 동작에 EXCEL 추가(V221·임시 V9236 — `UPDATE … SET actions = ARRAY['SEARCH','SAVE','DELETE','EXCEL']`). 화면은 EXCEL 권한이 있을 때만 [엑셀] 버튼 |
| 시험 | `CopqTest` +1 — 내용(원장·단가 없음 빈칸·수동 행·범위 밖 달 제외·순서·문구), 권한(조회만 403), 잘못된 달 400, A사로 B사(머리 줄만) |

## 35. 다른 화면 → 출하 추적 바로가기 + 주소 진입 (2026-11-01 상위 배정 — Q-2 리콜 흐름 안, 마이그레이션·공통 변경 없음)

> 31~33장 화면을 리콜·클레임이 시작되는 화면에서 바로 연다. 아무것도 만들지 않는 읽기 화면이라 권한은 출하 추적 화면(SEARCH)이 그대로 막는다.

| # | 내용 |
|---|---|
| LG-1 | **주소 진입**: `/page/qms-lot-trace?lotNos=A,B&direction=FORWARD|BACKWARD` — 입력·방향을 채우고 바로 조회(번호 100개까지, 방향은 BACKWARD 외 전부 정방향). 번호·깊이 검증은 서버 그대로(31장 LT-1) |
| LG-2 | **NCR 상세** → [출하 추적](그 LOT 정방향 — 나간 범위·남은 재고)·[역추적](그 LOT 원인·공급사). 봉쇄 버튼 옆 |
| LG-3 | **8D(CAPA) 상세** — 이어진 NCR 이 있으면 [역추적]·[출하 추적]. LOT 번호는 `CapaRow.ncrLotNo` — **상세에서만** `NcrMapper.findById` 로 채움(같은 QMS 매퍼 재사용, 새 표 읽기 없음, 목록은 null) |
| LG-4 | **SPC 위반 상세** → [출하 추적](위반 LOT 정방향) — 기존 NCR 등록·봉쇄 열기 옆 |
| 시험 | `CapaNcrLinkTest` — 상세 `ncrLotNo` = 이어진 NCR 의 LOT, 목록·연결 끊긴 뒤는 없음. 링크·주소 진입은 브라우저 확인 |
| 시험 보강(2026-11-01) | SPC 위반 [출하 추적] 은 DEMO 에 위반이 없어 브라우저 미확인이었다 → `SpcEventTest` 처리 흐름 시험에 상세 `event.lotNo` = 그 검사의 LOT·`itemCd` 확인을 더함(링크가 쓰는 값의 계약). 링크 모양은 NCR·8D 와 같은 `?lotNos=&direction=` |

## 36. COPQ 원장 줄 → NCR 번호 펼치기·바로가기 (2026-11-01 상위 배정, 마이그레이션·공통 변경 없음)

> 원장 줄은 월·구분·출처·품목·적용 단가로 묶은 합계라 "어느 NCR 이 이 금액인가" 가 안 보였다(특히 외부 실패 = 고객 클레임 NCR). 읽기만.

| # | 내용 |
|---|---|
| CN-1 | `CopqMapper.findNcrLines` 에 `string_agg(distinct n.ncr_no, ',' order by n.ncr_no) as ncr_nos` — 같은 묶음 기준(group by 그대로), 금액·건수 계산은 바뀌지 않는다. 생산 실적 줄(29.4)은 MES 월 합계라 NCR 번호 없음(null) |
| CN-2 | 화면: NCR 줄의 출처 칸에 **[NCR n건]** 단추 → 아래 줄에 번호 링크(`/page/qms-ncr?ncrNo=` — 8D 화면과 같은 진입), 한 번에 50개까지 + "외 n건". 펼침 상태는 `aria-expanded` |
| CN-3 | 엑셀(34장)에 "NCR 번호" 열(원장 줄만, ", " 로 이음) — 열 순서: … 사유 → NCR 번호 → 항목 → 비고 |
| 시험 | `CopqTest` — 줄별 `ncrNos`(외부 실패 줄 = 클레임 NCR, 내부 줄 = 공정 NCR), 엑셀 NCR 열(원장 줄 있음·수동 행 빈칸) |

## 37. 출하 추적 LOT 별 QMS 배지 + 클레임 NCR 새 폼 중복 경고 (2026-11-01 상위 배정, 마이그레이션·공통 변경 없음)

> 리콜 때 이미 처리 중인 LOT 에 NCR·봉쇄를 또 만드는 것(조용히 틀림 — 같은 사건이 두 번 세지고 COPQ 도 겹침)을 화면에서 보이게. **막지는 않는다**(다른 고객 클레임·다른 원인일 수 있다).

| # | 내용 |
|---|---|
| LW-1 | `TraceView.qms` = 닿은 LOT 중 일이 있는 것만 `LotQms(lotNo, openNcrNos, containNos)` — 열린 NCR = status OPEN·PENDING(홈 "열린 부적합" 과 같은 범위), 걸린 봉쇄 = `qms_contain_lot.released_at` 없음 + 봉쇄 OPEN. 정방향·역추적 둘 다 |
| LW-2 | 읽기는 새 QMS 매퍼 `QmsLotWorkMapper.findOpenWork`(qms_ncr·qms_contain_lot·qms_contain 만, union all, `ix_qms_ncr_lot`·`ix_qms_contain_lot_lot`), LOT 500개씩. MES 매퍼 `LotTraceMapper` 와 빈 이름이 겹쳐 이름을 달리했다 |
| LW-3 | 화면: 출하 줄·남은 재고·원인 LOT 줄의 LOT 칸 뒤에 노란 배지 "NCR 번호"(→ NCR 화면)·"봉쇄 번호"(→ 봉쇄 화면 `?q.keyword=`) |
| LW-4 | NCR 새 폼을 주소(`?lotNo=` — 출하 추적 클레임 NCR·검사 NG·SPC 위반 등)로 열면 같은 LOT 의 열린 NCR 을 기존 목록 API(`?keyword=&status=OPEN_ALL`, LOT 번호 정확히 같은 것만)로 찾아 노란 줄 + 번호 링크. 등록 단추는 그대로 |
| 시험 | `LotTraceTest` +1 — 열린 NCR(LT-B)·봉쇄(LT-A) 보임, 취소된 NCR(LT-C) 안 보임, 역추적도 같음, A사로 B사(같은 LOT 번호라도 안 보임). 새 폼 경고는 브라우저 확인 |

## 38. 고객 반품 → 클레임 NCR 계약 `NcrPort` (2026-11-01 사용자 결정 R-2 (가), V223·임시 V9237 — docs/59 7장 2단계)

> WMS 고객 반품 입고(docs/59)가 같은 트랜잭션에서 QMS NCR(출처 CLAIM)을 만들고, 번호·상태를 읽고, 반품 취소 때 NCR 을 함께 취소한다. 판정·처분은 기존 NCR 화면·`QualityStockLink.onNcrClosed` 경로 그대로(BLOCKED → USE_AS_IS 가용 / SCRAP 원장). 함께 정해진 것(참고): R-1 근거 = WMS 반품 기록, R-3 shipped_qty 안 줄임, R-5 QC 칸, R-6 취소 = NCR OPEN 일 때만, R-7 근거 없는 반품 거부, R-8 capa_no 번호만.

### 38.1 계약 (`module.qms.link.NcrPort`)

| 메서드 | 내용 |
|---|---|
| `createForClaim(ClaimRequest)` → `Optional<NcrView>` | 요청 = `refType`(지금은 `WMS_CUST_RTN`)·`refId`(반품 id)·`refNo`(반품 번호)·`lotId`·`qty`(LOT 단위 = 품목 기본 단위)·`description`(2,000자 안)·`by`. **부르는 쪽 트랜잭션 안(MANDATORY)**. 입력이 틀리면 칸 오류(BusinessException) — 부르는 쪽 저장도 함께 실패 |
| `ncrStatus(Collection<Long> ncrIds)` → `List<NcrView>` | 읽기만·예외 없음, 500개까지(넘으면 IllegalArgumentException — 호출 코드 오류), 없는·다른 회사 id 는 빠짐 |
| `cancelForClaim(refType, refId, reason, by)` → `boolean` | 반품 취소(R-6). 그 반품의 NCR 이 **OPEN 이면 취소**(이 NCR 이 잡은 LOT 보류도 해제) → true, NCR 이 없으면 false(만들지 않은 설치본 등), **OPEN 이 아니면 409 `QMS_NCR_STATE`** — 처분이 시작된(특채 결재 중·종결) 재고를 반품 취소로 되돌리면 처분 원장과 어긋난다 |
| `NcrView` | `ncrId`·`ncrNo`·`status`(OPEN·PENDING·CLOSED·CANCELLED) |

- 권한(qms-ncr SAVE)은 보지 않는다 — 반품 입고 권한을 가진 WMS 사용자가 부른다(`CapaPort` 와 같은 규칙). 만든 사람 = `by`
- **QMS 없는 설치본 = `NONE`**: create → `Optional.empty()`(NCR 없음 — WMS 는 NCR 칸을 비우고 BLOCKED 재고는 사람이 처리), cancel → false, status → 빈 목록. `CapaPort.NONE` 처럼 예외를 던지지 않는 까닭: 반품 입고 자체는 QMS 없이도 받아야 하고, 반환형이 Optional 이라 "만들었다" 로 착각할 여지가 없다

### 38.2 정한 것

| # | 내용 |
|---|---|
| NP-1 | **멱등**: 같은 (refType, refId) 두 번 = 같은 NCR(상태와 무관하게 처음 것). `qms_ncr.src_type`·`src_id` 추가 + 회사별 유일 인덱스 `uq_qms_ncr_src`. 동시 두 건은 `pg_advisory_xact_lock(hashtext('qms_ncr.src:' || type || ':' || id))` 로 줄 세운 뒤 조회 → 없으면 만들기(유일 인덱스는 최후 방어) |
| NP-2 | **잠금 순서 = 반품 → NCR → LOT 보류**: WMS 가 반품 행을 넣은 뒤 부른다. 계약 안에서는 advisory → NCR INSERT → (정책 Y 면) MES `LotHoldPort.hold`. 손으로 만드는 NCR(보류 → INSERT)과 순서가 다르지만 새 NCR 행은 다른 트랜잭션과 겹치지 않아 교착이 없다(LOT 잠금은 한 번) |
| NP-3 | **보류 정책 그대로**: `QMS_NCR_AUTO_HOLD` = Y 면 MES LOT 보류(출처 QMS_NCR, 참조 = ncr_id) — 손 NCR 과 같다. 반품 재고는 WMS 에서 이미 BLOCKED 라 출하는 막히고, MES 보류는 생산 투입까지 막는다. MES 없는 설치본 + 정책 Y = 409 `QMS_NCR_HOLD_UNAVAILABLE`(손 NCR 과 같음 — 반품 입고도 실패) |
| NP-4 | **불량 유형은 비워 둔다**: 반품 순간에는 왜 돌아왔는지 모른다. `defect_id` 를 계약 NCR(`src_type` 있음)에서만 null 허용(`ck_qms_ncr_defect_src`) — 품질 담당이 NCR 화면에서 저장할 때 고른다(저장은 지금처럼 필수). 상신·종결은 불량 유형이 있어야(트리거 `ck_qms_ncr_defect_required`). NCR 화면·협력사 품질 계약의 불량 조인은 left join 으로 |
| NP-5 | 출처 CLAIM 고정(`ck_qms_ncr_src_claim`), 근거 검사 없음, 수량·단위 = 요청 수량·LOT 단위(트리거가 LOT 과 같은지 이미 본다), 내용 = 요청 서술(비면 "고객 반품 {refNo}") |
| NP-6 | `src_type`·`src_id` 는 정체 칸 — 바꿀 수 없음(트리거 `ck_qms_ncr_immutable` 에 추가) |
| NP-7 | **A사로 B사**: B사 범위에서 A사 LOT id → 칸 오류 lotId NOT_FOUND(RLS), A사 NCR id 상태 조회 → 빠짐, A사 반품 ref 로 취소 → false(NCR 못 찾음). 같은 refId 라도 회사가 다르면 다른 NCR(유일 인덱스가 comp_cd 포함) |

### 38.3 시험 (구현 PR)

| 묶음 | 경우 |
|---|---|
| 만들기 | CLAIM·src·수량·단위·불량 유형 비움·서술, 정책 Y 면 LOT 보류(근거 ncr_id)·N 이면 보류 없음 |
| 멱등·동시 | 같은 ref 두 번 = 같은 id, 동시 두 스레드 = NCR 하나 |
| 입력 | 없는 LOT·수량 0·음수·소수 4자리·서술 2,001자·ref 종류 모름 → 칸 오류, 트랜잭션 없이 부르면 IllegalTransactionStateException |
| 상태·취소 | ncrStatus(없는 id 빠짐·501개 예외), OPEN 취소 → CANCELLED·보류 해제·true, 없으면 false, 종결 뒤 취소 409 |
| 불량 유형 | 불량 유형 없이 상신·종결 → 트리거 거부, 화면 저장은 불량 유형 필수 그대로, NCR 목록·상세가 불량 없는 NCR 도 보인다 |
| A사로 B사·NONE | 위 NP-7, NONE 세 메서드 |

## 39. 반품 NCR 분류 보기 — NCR 목록 근거 필터 + 홈 카드 불량 유형 미지정 (2026-11-02, 마이그레이션·공통 변경 없음)

> 38장 계약으로 고객 반품 NCR 이 불량 유형 없이 쌓이기 시작한다(WMS 반품 입고 뒤). 품질 담당이 "판정 대기" 를 놓치지 않게 목록·홈에서 갈라 본다. 읽기만.

| # | 내용 |
|---|---|
| RB-1 | NCR 목록·그리드·건수에 `basis` 조건 — `CUST_RTN`(src_type = WMS_CUST_RTN) / `NO_DEFECT`(defect_id 없음) / 비움 = 전체. **모르는 값은 0건**(조용히 전체를 보이지 않게 — 상태 칸과 같은 규칙) |
| RB-2 | 조회 패널에 "근거" 선택(전체·고객 반품·불량 유형 미지정(판정 대기)), 그리드에 "근거" 칸(LABEL — 고객 반품 / 빈칸, 숨김 순서 3) |
| RB-3 | 홈 카드 "열린 부적합"에 사실 "불량 유형 미지정 n건"(있으면 경고 색) — `ncrOpenSummary` 에 `count(*) filter (where defect_id is null)` 한 칸 |
| 시험 | `NcrPortTest` +1 — 근거 필터(반품 1·미지정 1·전체 2·모르는 값 0), 그리드 총수·근거 칸, 홈 요약(열린 2·미지정 1), A사로 B사(B사 홈 0) |

## 40. COPQ 원장 줄 출처 — 고객 반품 NCR 구분 `RTN_*` (2026-11-02, 마이그레이션·공통 변경 없음)

> 38장 계약 NCR(src_type `WMS_CUST_RTN`)도 출처 CLAIM 이라 이미 외부 실패로 들어가지만, 원장 줄 출처가 손 NCR 과 같은 `NCR_SCRAP` 등이라 "반품에서 생긴 비용" 을 갈라 볼 수 없었다. 읽기만.

| # | 내용 |
|---|---|
| RC-1 | `CopqMapper.findNcrLines` 출처 = `case when n.src_type = 'WMS_CUST_RTN' then 'RTN_' else 'NCR_' end \|\| d.disp_type` — `RTN_SCRAP`·`RTN_REWORK`·`RTN_SORT`. 분류(외부/내부)는 그대로 NCR 출처 CLAIM 기준(바뀌지 않음) |
| RC-2 | 화면 출처 이름(`js.qms.copq.srcRtn*`)·엑셀 LABEL 값 목록(`CopqExportService.SOURCES`, `qms.copq.src.RTN_*`) 에 3개 추가 — 모르는 출처 값이 엑셀에 코드로 새지 않게 |
| 버린 안 | 손 NCR 을 CLAIM 으로 등록한 경우까지 "반품" 으로 묶기 — 고객 클레임이 반품 없이 오는 경우도 있어 근거(src_type)가 있는 것만 RTN 으로 둔다 |
| 시험 | `NcrPortTest` +1 — 계약 NCR 에 불량 유형·SCRAP 처분 저장 뒤 종결 → COPQ 줄 1, 출처 `RTN_SCRAP`, 분류 EXTERNAL, NCR 번호 그대로 |

## 41. AQL 표 엑셀 올리기 — 정수 칸 쉼표는 천 단위 자리만 (2026-11-02, 마이그레이션·공통 변경 없음)

> 다른 세션 QA(sys N-a 와 같은 모양)에서 넘어온 것: `AqlTableImportTemplate.whole` 이 쉼표를 자리 확인 없이 지워 `0,5` → 5, `1,23` → 123 으로 **조용히** 들어갔다. 표 1(로트 구간 시작·끝)과 표 2(표본 수·Ac·Re, 같은 함수 사용) 모두 해당.

| # | 내용 |
|---|---|
| RA-1 | 쉼표가 있으면 `^[1-9]\d{0,2}(,\d{3})+(\.\d+)?$`(hb.numbers GROUPED, #746 N-a 뒤 규칙 — 앞자리 0 거부) 일 때만 쉼표를 빼고 읽는다. 아니면 칸 오류 `FORMAT`. 소수 부분은 받되 뒤의 정수 검사(SCALE)가 그대로 막는다 — 엑셀 숫자 셀 `"281.0"`·`"1,000.0"` 은 계속 받음 |
| RA-2 | AQL 수치 칸은 원래 쉼표를 지우지 않아(`new BigDecimal`) `0,65` 는 이미 형식 오류 — 바꾸지 않음 |
| 같은 모양 | 금형 수명 타수 `AssetService.GROUPED`(#754, 소수 없음) — 정수만 받는 칸이라 소수 부분을 빼 둔 것, 이 장은 엑셀 숫자 셀 "n.0" 때문에 hb.numbers 원형을 쓴다 |
| 시험 | `QmsAqlImportTest` +1 — 고치기 전 실패 확인(`"0,5"` → 5) 뒤 수정. 받음 `1,000`·`12,345`·`1,000,000`·`1,000.0`·`281.0` / 거부 `0,5`·`1,23`·`012,345`·`1,0000`·`,100`·`100,`·`1,,000`·`1 000`·`1.000,5`, 올리기 경로(표 1 끝값·시작값, 표 2 표본 수) 행 오류 |

## 42. 홈 카드 "미처리 SPC 위반" 링크 — 이미 열린 탭에서도 위반 보기 (2026-11-02, 공통 훅 #756 `onLink` 사용, 마이그레이션 없음)

> 홈 감사(docs/37 22.2 ②): 카드 링크 `{view: events, status: OPEN_ALL}` 중 `view` 는 조회 패널 밖 키다. 처음 여는 탭은 주소 `?q.view` 를 화면이 읽어 됐지만, **이미 열린 탭**은 셸 applyLinkQuery 가 조회 칸만 채우고 조회해 단위 목록이 그대로 남았다(카드 숫자와 다른 목록).

| # | 내용 |
|---|---|
| RS-1 | `spc.js` 조회 패널에 `onLink(q)` — `q.view` 가 `events`·`units` 이고 지금 보기와 다르면 보기 단추까지 바꾸고(`pickView`) `setView` 가 조회 → `true`(공통 조회 생략, 두 번 조회 안 함). 같은 보기면 `false` → 공통 조회(상태 칸은 이미 링크 값) |
| RS-2 | 처음 여는 탭(`?q.view=events`)도 같은 `pickView` 로 — 보기 단추 맞추는 코드 한 곳 |
| 버린 안 | `view` 를 조회 패널 칸(숨김 input)으로 옮기기 — 보기 단추와 값이 둘로 갈라지고 "초기화" 가 보기를 바꾸게 된다. 공통 훅이 이 경우를 위해 생겨 그것을 씀 |
| 시험 | 화면 단위 JS 시험 없음 — 브라우저로 셸과 같은 `hb.applyLinkQuery` 호출 확인(docs/testing/qms.md 42장) |

## 43. 출처가 있는 NCR 은 QMS 에서 취소 안 함 — CR-b (2026-11-02 사용자 결정, 추천안)

> QA(docs/qa/2026-11-02_cust-rtn.md CR-b): QMS NCR 화면 [취소]가 고객 반품이 만든 클레임 NCR 도 취소 → NCR CANCELLED·LOT 보류 해제인데 반품은 RECEIVED·재고 BLOCKED 그대로, 반품 취소는 `cancelForClaim`(OPEN 만)이 영영 409 — 처분 경로 없는 막다른 재고.

| # | 내용 |
|---|---|
| 결정 | (가) **출처가 있는 NCR(`src_type` not null — 지금은 WMS_CUST_RTN)은 QMS 화면·API 취소를 거부**, 취소는 출처 쪽(WMS 반품 [취소] → `NcrPort.cancelForClaim`)으로만. (나) QMS 취소가 반품을 연쇄 취소 — 버림: 모듈 방향(WMS → QMS)이 뒤집히고, 반품 취소의 원장 역분개를 QMS 가 일으키게 된다 |
| CB-1 | `NcrService.cancel`: OPEN·row_ver 확인 뒤 `src_type` 있으면 409 `QMS_NCR_SOURCE_OWNED`(새 오류 코드 — "출처 화면(고객 반품)에서 취소") — 보류 해제 전에 막아 아무것도 바뀌지 않음 |
| CB-2 | 다른 상태 변경(저장·상신·종결·결재)은 그대로 — 품질 판정은 QMS 몫 |
| CB-3 | 화면 `ncr.js`: 출처 있는 NCR 은 [취소] 숨김 + 머리 줄 "취소는 고객 반품 화면에서"(처리 중일 때) — 숨김과 별개로 서버가 막음 |
| 시험 | `CustRtnQaTest` 부록 A-2 를 켬 — **고치기 전 실패 확인**(200) 뒤 수정: 409·NCR OPEN·응답 코드 `QMS_NCR_SOURCE_OWNED`, 이어서 반품 화면 취소 200 → 반품·NCR CANCELLED. 손 NCR 취소는 기존 `NcrApiTest` 그대로 통과 |

## 44. 8D 상세에 묶인 고객 반품 — R-8 후속 (2026-11-02, WMS 계약 `ShipQuery.custReturns` 추가, 마이그레이션 없음)

> docs/59 R-8: 반품에 클레임 번호(`capa_no`)를 사람이 적는다(1차), "CAPA 에 반품 목록 표시는 QMS 몫 후속". 8D 를 보는 품질 담당이 그 클레임으로 들어온 반품을 한곳에서 본다. 읽기만.

| # | 내용 |
|---|---|
| RR-1 | 계약(WMS 공유 파일 — 상위 승인 2026-11-02): `ShipQuery.CustReturn` record + `default custReturns(capaNo, ncrIds)` = 빈 목록(NONE 안전). `WmsShipQuery` 구현 + `WmsLinkMapper.custReturns` SQL 하나 |
| RR-2 | 묶는 규칙 — `btrim(capa_no) = 8D 번호`(**정확히 같음**: 대소문자 그대로, 부분 일치 없음 — 사람이 적은 값이라 넓게 잡으면 남의 반품이 섞인다) **또는** `ncr_id` 가 8D 에 이은 NCR. 취소 반품도 돌려주고 화면이 상태로 표시(흐리게). 받은 시각 desc, 최대 200건, ncrIds 상한 500(LOTS_MAX 와 같음, 넘으면 호출 오류) |
| RR-3 | `CapaService.detail` → `CapaDetail.custReturns`. 화면 `capa-mgt.js` "고객 반품" 읽기 표(반품 번호 → WMS 반품 화면, NCR 번호 → NCR 화면), 없으면 구획 숨김. 취소·수정은 고객 반품 화면에서(43장과 같은 방향) |
| RR-4 | WMS 표는 읽기만, 색인 추가 안 함 — `wms_cust_rtn` 에 capa_no·ncr_id 색인이 없어 회사 안 반품 전체를 훑는다. 반품 건수가 적은 동안은 충분, 늘면 `(comp_cd, btrim(capa_no))`·`(comp_cd, ncr_id)` 부분 색인을 WMS 쪽에 요청(상위에 알림) |
| 버린 안 | 별도 `CustRtnQuery` 계약 — 출하·반품 읽기 계약을 한곳(ShipQuery)에 두는 편이 작다(상위 판단). 대소문자 무시·부분 일치 — RR-2 이유 |
| 시험 | `CapaCustRtnTest` 3 — 번호로 묶인 반품(취소 뒤 CANCELLED 로 보임)·NCR 로 묶인 반품·대소문자 다름/부분 일치/무관 반품 안 보임·계약 앞뒤 공백·빈 입력, A사로 B사(같은 8D 번호·A사 NCR id 로 물어도 B사 반품만/빈 목록, A사 8D 상세 404), NONE 빈 목록·ncrIds 501 호출 오류·중복 무시 |

## 45. MSA GRR ANOVA 방법 — D9 2차 (2026-11-02, V229·임시 V9238, 사용자 결정 불필요 — D9 로 정해짐)

> D9(8장): "AVG_RANGE 먼저, ANOVA 는 같은 원자료로 2차". AIAG MSA 4판의 ANOVA GRR — 평균·범위법이 못 보는 **측정자 × 부품 상호작용**(측정자마다 부품을 다르게 읽음)을 따로 센다.

| # | 내용 |
|---|---|
| AN-1 | 방법 코드 `ANOVA`(MSA_METHOD). 모양·기준·공정 σ·판정 구간은 AVG_RANGE 와 같다(측정자 2~3·부품 2~10·반복 2~3) — 같은 원자료를 두 방법으로 비교 가능, 원자료 입력·붙여넣기 화면 그대로 |
| AN-2 | 계산(`MsaCalc.anova`, 순수 함수): 2원 분산분석 SS·df·MS(부품·측정자·상호작용·반복·합계). 상호작용 F 의 p > **0.25** 면 상호작용을 오차에 합침(풀링 — AIAG 관례, 상수 `POOL_ALPHA`). 분산 성분 σ²반복 = MS오차, σ²상호작용 = (MS상호작용 − MS오차)/r, σ²측정자 = (MS측정자 − 분모)/(n·r), σ²부품 = (MS부품 − 분모)/(k·r) — 분모 = 풀링이면 합친 MS, 아니면 MS상호작용, 음수는 0. EV·AV·INT·GRR·PV·TV·%·ndc·판정은 평균·범위법과 같은 식·같은 기준 |
| AN-3 | F 꼬리 확률 = 정칙 불완전 베타(연분수 Lentz + ln Γ Lanczos) 자체 구현 — 외부 통계 라이브러리 추가 없음(의존성·검증 메타데이터 늘리지 않음). 반복 오차 0 이면 F 무한대 → 표에 빈칸, p 0(효과 있음) 또는 1(효과 없음) |
| AN-4 | 결과 스냅샷(result_json) = 평균·범위법 칸 + `iv`·`pctIv`·`pooled`·`anova`(표). 화면: 결과 표에 INT 줄, 아래 ANOVA 표(p < 0.0001 표기)와 풀링 안내 |
| AN-5 | V9238: 코드 + `ck_qms_msa_study_method` 교체(**기존 의미 + ANOVA 만** — 기존 행이 늘 통과) + 가드 함수 2개(확정 칸 수·원자료 종류)가 ANOVA 를 계량형으로 보게 `CREATE OR REPLACE`. 표·열 추가 없음. **함께 막은 빈틈**: 기존 CHECK 는 계량형 `basis` 가 NULL 이면 `IN` 이 NULL 이라 통과 — 별도 제약 `ck_qms_msa_study_basis_required ... NOT VALID`(새·바뀐 행만 검사). 처음엔 방법 제약 안에 `coalesce` 로 넣었으나 ADD CONSTRAINT 는 기존 행도 검사해 그런 옛 행이 하나라도 있는 DB 에서 마이그레이션이 멈추고, 확정본은 가드 때문에 고칠 수도 없어 바꿈(상위 검토 2026-11-02) |
| 버린 안 | 풀링 기준을 정책값으로 — AIAG 관례 고정값이라 지금은 상수, 바꿀 수요가 생기면 정책으로. 상호작용 항을 늘 따로 셈(풀링 안 함) — 상호작용이 우연 수준일 때 자유도를 버려 EV 추정이 나빠진다. Apache Commons Math 의 F 분포 — 함수 하나를 위해 의존성을 늘리지 않음 |
| 시험 | `MsaCalcTest` +6 — 같은 연구 두 방법 나란히(예제 1 기술 통계·판정 같음 / 예제 3 평균·범위법 13.88 % 조건부 ↔ ANOVA 34.21 % 불합격 — 상호작용을 놓치는 차이), 예제 1(풀링, 표 SS·df·F·p 와 성분·% — 공차 기준 포함), 예제 2(3×10×3·공정 σ), 예제 3(상호작용 유의 → INT 따로, 음수 AV 0), 반복 오차 0(F 빈칸·p 0/1), F 꼬리 확률 5점(scipy.stats.f.sf 값과 1e-10). 기대값은 정의대로 따로 짠 계산(분산분석 손계산 + scipy)과 대조, 표준 원문 예제 수치는 쓰지 않음. `MsaApiTest` — ANOVA 작성·미리 계산·확정 스냅샷(표 5줄·풀링)·목록 방법 거르기, 원자료 종류 트리거(계수형 값 거부), 칸 덜 찬 직접 SQL 확정 거부, CHECK(ANOVA 부품 11·기준 NULL), 부품 11 칸 오류 |

## 46. 데모 이력 보충 — 최종검사 30·SPC 고정 한계 위반·부적합 18·8D·COPQ (2026-11-03 — docs/61 10장 "데모" 결정, docs/29 2026-11-03 사라진 작업 다시 만들기, 마이그레이션 없음)

> 품질 현황판(C2 — 불량 유형 파레토·PPM·COPQ 월 추세·NCR 열림/종결)과 SPC 화면이 그려질 기록. MES 데모 이력(docs/25 4.53)이 만든 LOT 위에서만 돈다. 공정검사 30건·고정 한계·SPC 위반은 `QmsDemoSeeder`(PR #808, 64311 드로잉 공정검사, local)가 이미 넣으므로 이 절은 **원래 요구(최종검사 30·NCR 18·8D·고정 한계 위반·COPQ) 중 그 시더에 없는 것만 보충**한다 — `QmsHistoryDemoSeeder` `@Order(DemoSeedOrder.QMS_HISTORY)`(공정검사 시더 뒤: 그 시더는 검사가 하나라도 있으면 건너뛴다).

| # | 내용 |
|---|---|
| DS-1 | 흐름 `QmsDemoFlow`(module.qms) — 서비스 길만(매퍼 직접 INSERT 없음), local·demo 프로필의 `QmsHistoryDemoSeeder` 가 부름. 공정검사(PROCESS)는 건너뜀 조건에 넣지 않는다(#808 시더가 먼저 넣음). 최종검사(FINAL)·부적합·8D 가 하나라도 있으면 0, MES 데모 LOT(완제품 30개·프레스 10개 이상)이 없으면 0. 한 트랜잭션 |
| DS-2 | 기준: 인장 시험기 `GG-TNS-01`(자산 + 계측기 종류 TENSILE + 사내 교정 150일 전, 주기 365 — 측정 시각 기준 계측기 상태가 "사용 가능"), 완성품 출하검사 계획 A0(공통 시더가 "작성 중"으로 둔 것)의 육안 행 샘플링을 S-ALL → S-5 로 마저 쓰고 유효 시작 오늘로 확정(전수면 LOT 하나에 측정 수백 개), 표준단가 2품목(비어 있고 ERP 가 품목을 주지 않을 때만) |
| DS-3 | 최종검사 30건: 용접 ASSY 완제품 LOT 을 지난 90일에 고르게 골라 `InspService.createAt`(발생 = 그 LOT 생산 + 40분)·`measureAt`(잰 시각) → `judge`(판정 시각은 DB now()). 인발 강도 X̄-R: 25건째 뒤 고정 한계(WE1·WE2, n 25) → 그 뒤 2건은 평균이 규격 안(4.0 kN 이상)이지만 관리 한계 밖 → SPC 위반 이벤트(하나는 확인 ACK, 하나는 열림 — 홈 카드 "미처리 SPC 위반"). 3건은 육안 NG |
| DS-4 | 부적합 18건: 검사 NG 3(근거 검사 번호) + 공정 13(MES 데모 LOT, 불량 유형은 MES 파레토와 같은 쏠림) + 기타 2. 발생 시각은 지난 90일(`NcrService.createAt`), 오래된 것부터 13건 종결(폐기·선별·재작업 처분 — COPQ 원장 줄), 1건 취소(중복 등록), 최근 4건 열림(하나는 처분 일부만). 열린 NCR 의 자동 보류(정책 `QMS_NCR_AUTO_HOLD`)는 그대로 — WMS 시더는 보류 LOT 을 피킹하지 않는다 |
| DS-5 | 8D 3건(발생일 과거): 고객 클레임(S001, 첫 검사 NG 부적합 연결, VERIFIED — 결재 정책 `QMS_DOC_APPROVAL` 이 꺼져 있으면 종결), 내부 드로잉 크랙(ANALYZED), 내부 스패터(OPEN) |
| DS-6 | COPQ 수기 비용: 지난 3달 + 이번 달 예방(PFMEA 회의)·평가(출하검사 인건비, 외부 교정 1회)·내부(재작업 인건비), 외부(클레임 선별·운송)는 8D 발생 달. 생산 폐기 줄은 MES 실적에서 계산되지만 **단가 이력이 지금부터라 지난 달은 "단가 없음"**(29.4 CQ-9 규칙 그대로 — 단가 이력은 append-only·now()) |
| DS-7 | 시더 전용 길(API 없음): `InspService.createAt`·`measureAt`, `NcrService.createAt` — 검증·계획·샘플·보류는 화면 길 그대로, 발생(잰) 시각만 받고 지금보다 뒤는 거부. 판정·종결 시각(judged_at·closed_at)은 DB now() 그대로 — 그래서 **NCR 종결 추세·SPC 이벤트 발생 시각은 오늘 하루에 몰린다**(알려진 한계, 바꾸려면 종결 시각 칸 규칙을 바꿔야 해 범위 밖) |
| 버린 안 | 매퍼로 검사·측정 직접 INSERT — 샘플 수·계측기 상태·판정 규칙을 건너뛴다. 엑셀 올리기 경로(`measureImported`) — 올리기 작업 행(import_job)을 가짜로 만들어야 한다 |
| 시험 | `DemoHistoryFlowTest`(최종검사 30·NG 3·발생/측정 과거, 한계 1·위반 2 이상·ACK 1, NCR 18(+ WMS 반품 클레임 1)·종결 13·취소 1·열림 4, 8D 3(발생일 과거), COPQ 4달, 멱등, A사로 B사) |

## 47. NCR 처분 합 ≤ 부적합 수량 · 클레임 NCR 부적합 수량 ≤ 반품 수량 — R3-c (2026-11-04, V236(임시 V9201) — QA docs/qa/2026-11-02_round3.md R3-c, docs/29 2.6 사라진 작업 다시 만들기)

> QA R3-c: 클레임 NCR 의 부적합 수량을 반품 수량보다 크게 고치면(예 반품 1 = 2, 처분 폐기 3) 종결 때 WMS 가 그 반품 칸을 다 쓰고 남는 1 을 **다른(판정 전) 반품 칸**에서 가져갔다. 또 처분 합 = 부적합 수량은 상신·종결 때만 봐서 OPEN 동안은 처분 합이 수량을 넘은 채 저장됐다.
> 질의 원문의 두 길(QMS 저장 때 막기 / WMS 반영 때 자기 칸만) 중 **QMS 저장 때 막기**로 했다 — 상위 배정 문구 "NCR 처분 수량 합 ≤ NCR 수량(동시 처분 포함 서버에서 막기)". WMS 쪽은 바꾸지 않았다(R3C-6).

| # | 내용 |
|---|---|
| R3C-1 | **처분 합 ≤ 부적합 수량을 OPEN 저장 때도**: `NcrService.save` 가 양수 처분 행 합 > 부적합 수량이면 칸 오류 `disps` `NCR_DISP_OVER`(400, 아무것도 안 바뀜). 모자란 저장(작업 중)은 지금처럼 됨 — 같음은 상신·종결 때(`NCR_DISP_SUM`) |
| R3C-2 | **DB 최종 방어(V236, 임시 V9201)**: 처분 행 INSERT 트리거가 머리를 `FOR UPDATE` 로 잠근 **뒤 따로** 합을 읽어 (합 + 새 행) > 부적합 수량이면 `ck_qms_ncr_disp_over` — 같은 NCR 에 동시 INSERT 는 머리 잠금에서 줄 서고 앞 커밋을 본다(FOR UPDATE 문장 안 집계는 옛 스냅숏 — SKILL §2). 머리 UPDATE 로 부적합 수량을 지금 처분 합보다 **작게 줄이기**도 같은 제약으로 거부. 그래서 서비스 저장 순서를 **처분 행 지우기 → 머리 고치기 → 처분 행 넣기**로 바꿨다(전: 머리 → 지우기 → 넣기) |
| R3C-3 | **클레임 NCR 상한**: `qms_ncr.src_qty`(새 칸) = 계약 `NcrPort.createForClaim` 이 받은 반품 수량 사본, 정체 칸(고정 — 트리거). CHECK `ck_qms_ncr_src_qty`: `nc_qty ≤ src_qty`. 서비스는 저장 때 칸 오류 `ncQty` `NCR_CLAIM_QTY`(문구에 반품 수량 — 서버 값만, 사용자 입력 아님). 409 가 아니라 400 칸 오류로 한 것은 "다른 사용자가 먼저" 가 아니라 입력 값 문제라서. 근거가 있는 NCR 을 근거 수량 없이 INSERT 하면 `ck_qms_ncr_src_qty_required` |
| R3C-4 | 상한은 **반품 수량**이지 처음 부적합 수량이 아니다 — 줄였다가 다시 반품 수량까지 늘리기는 됨. 반품 수량보다 더 들어온 몫은 새 부적합으로(문구 안내) |
| R3C-5 | 이 칸 이전 클레임 NCR 은 `src_qty` NULL(검사 없음) — 마이그레이션(소유자 계정)은 FORCE RLS 로 회사 행을 못 읽어 반품 수량을 채울 수 없다. 운영 DB 에 열린 클레임 NCR 이 있으면 회사 범위로 채우는 일회성 SQL 이 필요(질문 Q-r3c-1) |
| R3C-6 | 하지 않은 것: WMS `QualityStockPosting.onNcrClosed` 의 "자기 반품 칸 먼저, 모자라면 칸 순" 은 그대로 — 이제 처분 합이 반품 수량을 넘지 않으므로 다른 반품 칸에서 가져가는 경우는 "같은 칸에 다른 반품이 같이 있고 자기 몫이 이미 다른 이유로 줄어든" 때뿐(QA 가 말한 데이터 모델 한계, 기록만) |
| 화면 | `ncr.js` 머리 줄에 "부적합 수량 상한 = 반품 수량 n EA"(처리 중일 때) |
| 시험 | `NcrDispCapTest` 7(저장 넘음·모자람·같음 → 종결, 수량만 줄이기 거부·함께 줄이기 됨, 음수·null 행, **동시 INSERT 둘 — 앞 커밋 뒤 뒤쪽 `ck_qms_ncr_disp_over`**, 같은 rowVer 저장 둘 → 200·409·섞임 없음, 직접 SQL, A사로 B사) · `QmsClaimR3Test` 클레임 3(상한 칸 오류·줄였다 늘리기·종결은 자기 반품 칸만(QA 재현 반대 — BLOCKED 4 → 2, 다른 반품 NCR OPEN), 직접 SQL `src_qty` 고정·CHECK·필수, A사로 B사 404) · 기존 `NcrApiTest` 직접 SQL 시험의 부적합 수량 2 → 3(처분 2 + 재작업 칸 시험 1 이 새 상한에 걸리지 않게) |

## 48. 8D 반품 표 — 8D 고객 조건 · 200건 잘림 안내 — R3-d (2026-11-04, 마이그레이션·WMS 변경 없음 — QA 회차 3 R3-d)

> QA R3-d: 반품의 `capa_no` 는 사람이 적는 글자(R-8)라 8D 의 고객과 **다른 고객**의 반품도 같은 번호면 표에 섞인다(조건에 고객 없음). 계약 상한 200건은 잘림 표시가 없다.

| # | 내용 |
|---|---|
| RD-1 | `CapaService.detail`: 8D 에 고객(`cust_cd`)이 있으면 **번호로만 묶인** 반품 중 고객이 다른 것은 뺀다. **8D 에 이은 NCR 이 만든 반품은 고객과 무관하게 남긴다**(사람이 적은 글자가 아니라 연결 — 질문 Q-r3d-1). 고객이 없는 8D(내부·감사)는 거르지 않는다 |
| RD-2 | 응답 `returnsOtherCust`(뺀 건수) · `returnsTruncated`(계약이 돌려준 건수 ≥ `ShipQuery.RETURNS_MAX`) · `returnsMax`. 잘림 판단은 **거르기 전** 건수 — 거른 뒤 200 미만이어도 계약이 200 을 채웠으면 더 있을 수 있다 |
| RD-3 | 화면 `capa-mgt.js` 고객 반품 구획에 안내 줄(`rtnNotice`, `role=status`): "다른 고객 반품 n건 숨김" · "최근 200건만 — 고객 반품 화면에서 이 8D 번호로 찾기". 반품이 0 이어도 숨긴 건수·잘림이 있으면 구획을 보인다 |
| RD-4 | WMS 계약(`ShipQuery.custReturns`)에 고객 조건을 넣지 않았다 — WMS 공유 파일 변경 없이 QMS 안에서. 그래서 다른 고객 반품이 200건을 먼저 채우면 8D 고객 반품이 잘려 안 보일 수 있다(잘림 안내는 나옴). 계약에 `custCd` 를 더할지는 질문 Q-r3d-2 |
| 시험 | `QmsClaimR3Test` 3 — 다른 고객·같은 번호 반품 뺌(건수 1)·NCR 로 이은 다른 고객 반품 남김·고객 없는 8D 는 모두, 200건(SQL 복제 200 + 원래 1) → 200·잘림 true·가장 옛 반품 안 보임, A사로 B사(B사 반품은 A사 표·건수에 0, B사 8D 에선 다른 고객 1건 숨김, A사 8D 상세 404) |

## 49. 필수 사유 칸 보이지 않는 글자 — R4-a 2단계(QMS) (2026-11-04, 마이그레이션 없음 — 제안 docs/qa/2026-11-02_invisible-reason-proposal.md, 플랫폼 `VisibleText` 1단계 #812 위)

| # | 내용 |
|---|---|
| VT-1 | QMS 의 판정 도우미 `SamplingService.blank()`·`text()`(QMS 전 서비스가 부름)를 **지우고** 호출 자리를 플랫폼 `VisibleText` 로: `blank` → `VisibleText.isBlank`, `text(v, f, max, true, e)` → `VisibleText.reason(v, f, 1, max, e)`, `false` → `VisibleText.optional(v, f, max, e)`, 필수 여부가 식인 3곳(4M `revertCond` = TEMP 일 때, 교정 `limitNote` = LIMITED 일 때, SPC 조치 `note` = ACK 아닐 때)은 삼항으로. 선택 메모 3곳(`NcrService` 처분 note·`ChangeService` 영향 특성 note·`CapaService` 확산 note)의 `isBlank()/strip()` 도 `VisibleText.strip` |
| VT-2 | 오류 코드는 그대로 `REQUIRED`·`TOO_LONG` — 화면 문구·기존 시험 기대값 변경 없음. 새로 생기는 것: 가운데 제어 문자(탭·줄바꿈 제외) → `FORMAT`, 최대 길이는 코드 포인트(PostgreSQL varchar 와 같음 — 이모지 500 개가 500 자) |
| VT-3 | 덮는 칸(제안 4장 QMS 행 전부): NCR 설명·취소 사유·근거 취소 사유, 8D 제목·취소 사유·D1~D8, 봉쇄·해제 사유, 검사 무효, 관리한계 무효, SPC 조치 메모, 초품 판정 무효, 교정 제한 메모·무효, 4M 변경 전·후·복귀 조건, OCAP 조치 문구, 문서 이름·변경 요약, CSR, COPQ 비용 항목·메모, 샘플링 이름 — 모두 같은 함수라 한 번에 |
| VT-4 | `VisibleTextGuardTest` 기준선에서 `module/qms/SamplingService.java 2` 줄을 지웠다(QMS 0) |
| 하지 않은 것 | DB CHECK(`length(btrim(x)) > 0`)는 그대로 — 제안 3단계(선택, `hb_visible_len`)는 플랫폼 결정 뒤. 코드·번호 칸(`…Cd`·`ecoNo` 등)의 `isBlank` 뒤 `v.strip()` 은 Java strip 그대로(보이지 않는 글자만 넣은 코드는 어차피 형식·조회 오류) |
| 시험 | `QmsVisibleTextTest` 2 — U+200B·U+00A0+U+200B·U+FEFF·U+00AD·U+3164·U+2060+U+3000 만인 NCR 설명·NCR 취소 사유·OCAP 문구·8D 제목·8D 취소 사유 → 400 `REQUIRED`·행 0, 앞뒤 보이지 않는 글자 걷어내고 저장(가운데 ZWJ 는 남김), 가운데 BEL → `FORMAT`, 여러 줄 사유 됨, 이모지 500 = 통과·501 자 = `TOO_LONG` |

## 50. OCAP — 문서·QA 와 대조 (2026-11-04, 변경 없음)

docs/29 2.6 "사라진 작업" 표의 QMS 행에 OCAP 이 있으나, main 에 26장 I-1~I-6(V198)·QA O-a 수정(`OcapTest.고르기가_기다리는_사이_종결되면_같은_상태_오류`)이 모두 있다. 26.2 시험 목록·26.5 구현 표·QA docs/qa/2026-10-27_qms-import-ocap-pda.md 2장 항목을 코드·시험과 하나씩 대조했고 빠진 것을 찾지 못했다. 이번에 바뀐 것은 문구 칸 판정뿐(49장 — `actionText` 가 보이지 않는 글자만이면 `REQUIRED`). 사라진 작업의 "OCAP" 이 26장 10행 "하지 않을 것"(항목별 담당·기한, 종결 조건) 같은 2단계를 뜻했다면 정의가 없어 질문 Q-ocap-1 로 남긴다.

## 51. 4M 임시 변경 복귀 확인 · 변경 효과 확인 — docs/66 (나)·(다) (2026-11-09, 3차 묶음 `claude/qms-wave3`, V246·임시 V9200)

> 근거: docs/66 검토 절(2026-11-09 사용자 결정 — 9장 추천 그대로) Q4-1 순서 (나) → (다) · **Q4-2 아니요**(PLM 표시 끝을 당기는 계약을 요청하지 않음 — 기록만, 차이는 화면 경고) · **Q4-3 아니요**(검사 강화 없음 — 비교 표로 먼저) · **Q4-6 정책(기본 N)**. 표준 취지: IATF 16949 8.5.6.1.1(임시 변경 — 사용 중 추적, 표준 복귀 확인), 8.5.6(변경 뒤 유효성 확인). (라) 고객 통지·(가) 현장 변경점은 다음 차수.
> 경계(11장 그대로): QMS 는 `plm_eco`·`plm_eco_lot` 에 쓰지 않는다. ECO 머리는 계약 `EcoLookup` 으로만 읽고, DB 트리거만 같은 회사·상태를 잠금 없이 읽는다(12.3 초품과 같은 이유 — MES 표시 경로가 LOT → ECO 순서로 잠근다).

### 51.1 왜 새 표 둘인가 (qms_change 칸 추가가 아닌 이유)

`qms_change` 는 ECO 가 DRAFT 일 때만 고칠 수 있다(V094 트리거 `ck_qms_change_eco_draft`). 복귀·효과는 **적용(RELEASED) 뒤**의 사실이라 그 표에 칸을 더하면 트리거 예외가 생긴다 — 상신 뒤 고정 규칙을 약하게 만드는 대신, 적용 뒤 기록을 **추가만 하는 판정 표**(초품 `qms_setup_verif` 와 같은 모양: 살아 있는 행 하나, 정정 = 무효(사유) 뒤 다시, 삭제 금지)로 나눴다. 두 표 모두 `qms_change(eco_id)` 를 FK 로 가리킨다 — 4M 세부가 없는 ECO 에는 기록할 수 없다(복귀 조건 글이 4M 세부에 있다. 정책 `QMS_4M_REQUIRED = N` 회사의 4M 세부 없는 임시 ECO 는 이 기능 밖 — 51.6).

### 51.2 (나) 임시 변경 복귀 확인 — 정한 것

| # | 정한 것 | 내용 | 이유 |
|---|---|---|---|
| RV-1 | 표 | `qms_change_revert`(revert_id, eco_id → `qms_change`, `revert_type`, `reverted_on`(회사 날짜 — 표준으로 돌아온 날, 사람이 입력), `note`(무엇을 확인했나, 필수 1000), 상태 ACTIVE/VOID + 무효 사유·시각·사람, row_ver, 감사). ECO 마다 살아 있는 행 하나(부분 유일) | T-1. 원장이 아니라 판정 기록 — 초품과 같은 규칙 |
| RV-2 | 분류 = 공통코드 | 새 그룹 **`QMS_4M_REVERT_TYPE`**(시스템, 회사 확장 N): `RESTORED`(표준 방법으로 복귀) · `SUPERSEDED`(정식 변경 ECO 로 대체 — 메모에 ECO 번호). CHECK 는 같은 두 값(값을 읽는 분기가 있으므로 회사 확장 안 함) | 공통코드 전환 세션 방침(새 분류 칸은 공통코드로) |
| RV-3 | 기록 조건 | ECO 가 **TEMP·RELEASED** 일 때만(서비스 409 `QMS_REVERT_STATE`, 트리거 `ck_qms_change_revert_eco` 가 최종). `reverted_on` 은 적용일(`releasedOn`) ≤ 복귀일 ≤ 오늘(회사 날짜) — 칸 오류 `RANGE`. 이미 살아 있는 기록 → 409 `QMS_REVERT_STATE` | 적용 전에는 "돌아올" 임시 변경이 아직 없다 |
| RV-4 | 표시 끝과의 차이 = 경고만(Q4-2) | 상세 응답 `revert.markGap`: 복귀일 < 표시 끝 → `EARLY`("복귀 뒤 LOT 도 표시 끝까지 임시 변경으로 표시된다 — PLM 화면에서 표시 끝을 고친다"), 복귀일 > 표시 끝 → `LATE`("표시 끝 뒤 복귀일까지 만든 LOT 은 표시 없이 임시 방법으로 만들어졌을 수 있다 — 추적 확인"), 같음·표시 끝 없음 → null. **저장하지 않고 조회 때 지금 표시 끝으로 계산**(PLM 이 표시 끝을 고치면 경고가 바로 바뀐다 — SKILL "시간이 만드는 상태는 저장하지 않는다") | 계약 변경 없음(남의 표 변경 없음) |
| RV-5 | 기한 지남 | "복귀 기한 지남" = 4M 세부가 있는 TEMP·RELEASED ECO 중 살아 있는 복귀 기록 없음 + `markUntil` < 오늘(회사 날짜). 판정 함수 하나(`ChangeRecordService.overdueTemps`) — 홈 카드와 목록 필터 `revertDue=OVERDUE` 가 같은 함수라 숫자를 누르면 같은 건수. 후보는 `qms_change` 중 복귀 조건 글이 있고 살아 있는 복귀 기록이 없는 행 → ECO 머리는 계약 `ecos(ids)`(500 단위) | T-2. QMS 매퍼가 plm_eco 를 읽지 않게 |
| RV-6 | 홈 카드 | `qms-temp-overdue`(제목 "복귀 기한 지난 임시 변경", 정렬 665, 건수 > 0 이면 ALERT, 항목 = ECO 번호 · 표시 끝, 링크 → `qms-change?revertDue=OVERDUE`) | 표시 끝이 지나도 아무도 모르는 조용한 실패를 막는다 |
| RV-7 | 전자서명(Q4-6) | 정책 **`QMS_ESIGN_REQUIRED`(기존, 기본 N)** 을 그대로 쓴다 — Y 면 토큰 필수(403 `QMS_ESIGN_REQUIRED`), N 이어도 토큰을 주면 서명(12.10 E-3 과 같음). 의미 `APPROVE`, 대상 `qms_change` + eco_id(기록 id 는 INSERT 뒤에야 생기므로 — 교정 E-4 와 같은 이유), 정규 글자 `qms_change_revert` / `revertId` / `ecoNo` / `revertType` / `revertedOn` / `note`. 무효는 서명 없음(무효 사유가 기록) | 새 정책을 따로 두지 않은 이유: 서명이 필요한 회사(Part 11 고객)는 QMS 판정 전부에 필요하다 — 정책이 둘이면 하나만 켜는 실수가 생긴다 |
| RV-8 | 권한 | 기록 = `qms-change` SAVE, 무효 = `qms-change` DELETE(초품과 같은 짝) | 새 동작 코드 없음(sys_prog 그대로) |

### 51.3 (다) 변경 효과 확인 — 정한 것

| # | 정한 것 | 내용 | 이유 |
|---|---|---|---|
| EF-1 | 비교 표(읽기만) | `GET /api/qms/changes/{ecoId}/effect?n=&days=` — 기준 시각 = ECO 적용일(`releasedOn`) 0시(회사 시간대). 적용 전 ECO 는 `ready = false`(표 없음) | E-1. 새 표 없음 |
| EF-2 | 특성 행 | 영향 행(`qms_change_char`) 중 **특성** 행마다: 계량형 = 기준 앞 마지막 n개 · 뒤 처음 n개 측정값(판정 확정 검사의 **살아 있는 값만** — SPC 와 같은 조건, 12.6)으로 개수·평균·표준편차(표본, n−1)·규격 밖 개수·**Ppk**(전체 σ — 부분군 없이 개별값이라 Cpk 대신, 규격 = 지금 특성 규격). 계수형 = 기준 앞·뒤 판정 수·NG 수·NG 율. 제품 특성은 그 품목, 공정 특성은 ECO 대상 품목의 검사만(대상 품목이 없으면 그 공정의 모든 품목). 검사계획 행은 비교하지 않는다(특성 단위가 아님) | 계산은 순수 함수 `ChangeEffectCalc` + 손 검산 시험(SpcCalc 와 같은 방식) |
| EF-3 | 품목 행 | ECO 대상 품목마다 기준 앞 `days` 일 · 뒤 `days` 일(지금까지): 판정 확정 검사 수·NG 검사 수·NG 율, 부적합(NCR, 취소 제외) 건수·부적합 수량 합(단위별 아님 — 품목 기본 단위). 뒤 기간이 아직 다 안 찼으면 `afterPartial = true` + 지난 날 수 | 기간 길이가 다르면 건수 비교가 틀린다 — 화면이 같이 보여 준다 |
| EF-4 | "모름 ≠ 0" | 값이 없으면 null(평균·σ·Ppk·NG 율 — 분모 0 이거나 n < 2), 화면은 "자료 없음". 규격이 한쪽만이면 Ppk 는 그쪽만으로 | SKILL 업무 함정 "모름 ≠ 정상·0" |
| EF-5 | 범위 | `n` 기본 30(5~200), `days` 기본 30(1~180) — 넘으면 400 `INVALID_VALUE`. 특성 행 상한은 영향 행 상한(200) 그대로 | 조회 한 번에 특성 × 2 SELECT — 상한으로 막음 |
| EF-6 | 판정 기록 | `qms_change_effect`(effect_id, eco_id → `qms_change`, `result`, `note`(필수), 근거 `base_date`·`basis_n`·`basis_days`(판정 때 본 비교 조건 사본), 상태 ACTIVE/VOID + 무효, row_ver, 감사). ECO 마다 살아 있는 판정 하나. ECO 가 **RELEASED** 일 때만(변경 종류 무관) — 409 `QMS_EFFECT_STATE` | E-2. 비교는 계산, 판단은 사람 |
| EF-7 | 분류 = 공통코드 | 새 그룹 **`QMS_4M_EFFECT`**(시스템): `OK`(효과 확인 — 유지) · `NG`(효과 없음·악화 — 후속 조치 필요). NG 의 후속(새 ECO·8D)은 사람이 — 자동 생성 없음 | 판정 칸 ≠ 상태 칸(SKILL) |
| EF-8 | 전자서명 | RV-7 과 같은 정책·대상(`qms_change` + eco_id), 정규 글자 `qms_change_effect` / `effectId` / `ecoNo` / `result` / `baseDate` / `basisN` / `basisDays` / `note` | 한 대상(4M 세부)의 서명 목록에 복귀·효과가 함께 — `current` 는 그 기록들 해시 중 하나와 같음 |
| EF-9 | 검사 강화 없음(Q4-3) | 검사계획·샘플 수·빈도를 바꾸지 않는다. NG 판정도 검사계획을 건드리지 않음 | 사용자 결정 |

### 51.4 화면 (`qms-change` 상세 아래 구획 둘 — 새 화면 없음)

- **임시 변경 복귀** 구획(TEMP ECO 만): 복귀 조건(4M 세부 글) · 표시 끝 · 상태 배지(기한 지남 ALERT / 기록됨 OK / 기한 전 DUE_SOON) · 살아 있는 기록(구분·복귀일·메모·기록자) · 표시 끝 차이 경고(`role=status`) · [복귀 기록]·[무효] · 무효된 기록 이력. RELEASED 가 아니면 "적용 뒤에 기록" 안내만.
- **변경 효과 확인** 구획(RELEASED ECO): 조건(n·일 수) + [비교] → 특성 표(특성 | 앞 n·평균·σ·Ppk·규격 밖 | 뒤 …) · 품목 표(검사 수·NG 율·부적합 건수·수량, 앞/뒤) · 판정 [효과 판정](OK/NG + 메모) · [무효].
- 조회 조건에 "복귀 기한" 고르기(전체 / 기한 지남) — 주소 `?revertDue=OVERDUE` 로 진입(홈 카드).
- 서명은 정책이 Y 일 때만 대화상자(`hb.esign`), 서명 목록은 4M 세부 아래 한 줄씩.

### 51.5 시험 (정상 + 이상 사용 A~K + A사로 B사 + 직접 SQL + 조용히 틀리는 값)

| 묶음 | 내용 |
|---|---|
| 정상 | TEMP ECO 적용 → 기한 지남 카드·목록 필터 1 → 복귀 기록 → 0, 표시 끝 차이 EARLY/LATE/null, 무효 → 다시 기한 지남 → 다시 기록 / 효과 비교 손 검산(평균·σ·Ppk·규격 밖·NG 율·NCR 합) → 판정 OK → 무효 → NG |
| 조용히 틀리는 값 | 무효된 측정값·무효 검사·OPEN 검사가 비교에 안 들어감, 기준 시각 경계(적용일 0시 정각 측정은 "뒤"), 다른 품목 측정이 공정 특성 비교에 안 섞임(ECO 대상 품목만), 취소 NCR 제외, 뒤 기간이 덜 찼으면 afterPartial |
| 이상 사용 | 작성 중·결재 중·DESIGN ECO 에 복귀 기록 409 / 미래·적용일 전 복귀일 400 / 빈·보이지 않는 글자 메모 400 / 같은 기록 두 번 409(동시 둘 → 하나만) / 무효 두 번 409 / rowVer 충돌 409 / n·days 범위 밖 400 / 권한 없음 403(SAVE 없이 기록, DELETE 없이 무효) |
| A사로 B사 | B사 사용자가 A사 ECO 의 복귀·효과 조회·기록·무효 → 404, B사 카드에 A사 기한 지남 0 |
| 직접 SQL | 적용 안 된 ECO·TEMP 아닌 ECO 에 복귀 INSERT 거부, 살아 있는 행 둘 거부, 무효 외 UPDATE 거부, DELETE 거부, 다른 회사 4M 세부 FK |
| 서명 | 정책 Y + 토큰 없음 403·행 0, 토큰 → 서명 1(current), 정책 N + 토큰 없음 = 기록만 |

### 51.6 하지 않은 것 · 질문

- PLM 표시 끝 자동 당기기(Q4-2 아니요) — 경고만. 계약 요청 없음, **남의 표 변경 없음**.
- 4M 세부 없는 임시 ECO(정책 N 회사)의 복귀 추적 — 복귀 조건 글이 없어 기록 대상이 아님. 필요하면 질문 Q-rv-1(4M 세부 없이 복귀만 기록할지).
- 효과 비교의 기준 = 적용일 0시. 적용일보다 늦게 실제 변경된 경우(설비 교체가 다음 날 등)는 사람이 판정 메모로 — 기준 날짜를 고를지 질문 Q-ef-1.
- 비교 결과로 검사 빈도 자동 강화(Q4-3 아니요), 통계 검정(t 검정 등) — 숫자만 나란히.

## 52. 4M 고객 통지 판단 — docs/66 (라) (2026-11-09, 4차 `claude/qms-wave4`, V253·임시 V9200)

> 근거: docs/66 검토 절(2026-11-09 사용자 결정 — 9장 추천) **Q4-4**: 대상 고객 = ECO 의 고객 칸만, 없으면 경고 안 함, 수주 이력으로 추정하지 않음. 표준 취지: IATF 16949 8.5.6.1(고객 요구가 있으면 변경을 고객에게 알리고 승인), AIAG PPAP 4판의 고객 통지 대상 변경. (가) 현장 변경점은 이번 범위가 아니다.
>
> **PCN 기록 위치 — A 채택(기존 결정과 일치, 상위 세션 확인 2026-11-09)**: 4차 지시문은 docs/02 2.4 의 `qms_pcn` 초안을 가리켰지만, 그보다 나중의 결정(11.1 · docs/39 14.5 "PCN 은 PLM(`plm_pcn`), QMS 에 `qms_pcn` 을 만들지 않는다")이 이긴다. `plm_pcn` 에 통지일·응답 기한·상태(SENT/ACK/APPROVED/REJECTED)·응답일이 이미 있고, 고객 담당자 메일(`plm_pcn_mail`, docs/39 23장)도 거기에 붙어 있다. QMS 는 **판단**(통지가 필요한가·승인이 필요한가·기록이 맞게 됐는가)만 맡고 PCN 은 PLM 계약으로 읽기만 한다. 대안 B(`qms_pcn` 새로)는 같은 사실이 두 표에 생기고 메일 이력과 갈라져 버렸다.

### 52.1 정한 것

| # | 정한 것 | 내용 | 이유 |
|---|---|---|---|
| CN-1 | 고객 요구 = CSR 칸 | `qms_cust_csr.notify_req jsonb`(새 칸, 기본 `{}`): 4M 유형 → `NOTIFY`(통지만)·`APPROVE`(승인 필요). 없는 유형 = 요구 없음. CHECK = 키는 4M 유형 8개, 값은 둘(불변 함수 `hb_qms_notify_req_ok`) | C-1. 고객마다 다르다 — 사람이 기억하게 두면 빠진다. CSR 의 override 처럼 한 칸(jsonb)이라 CSR 저장 한 번에 같이 |
| CN-2 | 분류 = 시스템 고정 공통코드 | 새 그룹 **`QMS_4M_NOTIFY`**(`comp_ext_yn = N`): `NONE`(요구 없음 — 화면 선택용, 저장하지 않음)·`NOTIFY`·`APPROVE`. 판정 분기에 쓰이므로 회사 확장 없음(SKILL "상태 머신·분기 값은 시스템 고정"). 화면 선택지는 손으로 적지 않고 `data-code` | 공통코드 세션 Q3(손으로 적은 선택지)와 겹치지 않게 |
| CN-3 | 대상 고객 | ECO 의 고객 칸(`plm_eco.cust_id`)만 — PLM 계약 `EcoLookup.custId(ecoId)` 로 읽는다. 없으면 판정 `NO_CUSTOMER`, **경고 없음**. 수주 이력 추정 안 함 | Q4-4 |
| CN-4 | 맞는 CSR | 그 고객의 CSR 중 사용 중 · 시작일 ≤ 오늘(회사 날짜) · 적용 대상이 없거나(고객 전체) ECO 대상 품목(또는 그 품목 클래스) 하나라도 맞음. 리비전이 여럿이면 모두 본다(요구가 큰 쪽이 이김) | 시정조치 기한 CSR 조회(`findCsrDue`)와 같은 대상 규칙 |
| CN-5 | 요구 수준 | 4M 세부의 유형마다 맞는 CSR 들의 요구 중 가장 큰 것(`APPROVE` > `NOTIFY`) → ECO 전체 = 그중 가장 큰 것. 4M 세부가 없으면 `NO_4M`(판단 불가 — 경고 없음). 판정은 순수 함수 `CustNotifyJudge` | 계산과 판단을 나눔(손 검산 시험) |
| CN-6 | 경고(조회 때 계산, 저장 안 함) | ① `QMS_CUST_APPROVAL_NEEDED` — 요구 APPROVE 인데 ECO "고객 승인 필요" 가 꺼짐(계약의 `custApprovalStatus = NA`) ② `QMS_CUST_PCN_MISSING` — 요구 NOTIFY·APPROVE 인데 그 고객 PCN 이 없음(ECO 가 승인·적용됐을 때만 — 작성·결재 중엔 안내 글만) ③ `QMS_CUST_PCN_OVERDUE` — SENT 인데 응답 기한 < 오늘 ④ `QMS_CUST_PCN_REJECTED` — 마지막 PCN 이 REJECTED | C-2. 기록이 빠진 것·늦은 것을 조용히 두지 않는다 |
| CN-7 | 상신 막기 = 정책 | 정책 **`QMS_CUST_NOTIFY_GATE`**(ENUM `WARN`·`BLOCK`, 기본 `WARN`). BLOCK 이면 ECO 상신 규칙 ④(`EcoSubmitRule`, `ChangeService.check`)가 ① 을 칸 오류로 돌려 상신을 막는다(rowKey `qms4m`, 칸 `custApproval`). WARN 이면 상신 규칙에 넣지 않고 화면 경고만. PCN 경고(②~④)는 상신과 무관(PCN 은 게이트가 아니다 — docs/39 14.5) | "막을지는 정책"(C-2). 기본 WARN — 기존 상신 흐름·PLM 시험을 깨지 않는다 |
| CN-8 | PLM 계약 추가(읽기만, PLM 표 변경 없음) | `EcoLookup` 에 default 메서드 둘 + `PlmEcoLookup` 구현: **`custId(ecoId)`** → `Optional<Long>`(없음·다른 회사·고객 칸 없음 = 빈 값), **`pcns(ecoId)`** → `List<PcnView(pcnId, pcnNo, custId, notifiedOn, responseDue, status, respondedOn)>`(통지일·id 순, 다른 회사 = 빈 목록). 기본 구현(PLM 없음) = 빈 값. QMS 매퍼는 여전히 `plm_*` 를 SELECT 하지 않는다 | 11.3 계약 규칙 그대로. `EcoView` 레코드는 바꾸지 않음(생성자를 쓰는 곳이 많다) |
| CN-9 | PCN 메일과 잇기 | QMS 는 메일을 보내지 않는다. 통지 구획에 "PCN 기록·메일은 설계 변경 지시 화면에서" 안내 + `plm-eco?ecoId=` 링크. PCN 기록·응답·메일은 PLM 한 곳 | E3(docs/39 23장) — 외부 메일 경로를 두 벌 만들지 않는다 |
| CN-10 | 화면 | `qms-csr` 상세에 구획 "4M 변경 고객 통지 요구"(4M 유형 8줄 × 선택 `QMS_4M_NOTIFY`) — CSR 저장에 같이(`notifyReq`, null 이면 그대로). `qms-change` 상세에 구획 "고객 통지"(고객 · 요구 수준 · 맞은 CSR 근거 · ECO 고객 승인 · PCN 표 · 경고). 새 화면 없음 | 공통코드 세션과 겹침 최소 — CSR 화면은 구획 하나 덧붙이기 |
| CN-11 | 권한 | 통지 판단 조회 = `qms-change` SEARCH. CSR 요구 저장 = `qms-csr` SAVE(기존 CSR 저장) | 새 동작 코드 없음 |

### 52.2 시험

정상(요구 NOTIFY·APPROVE·없음, 품목·클래스·고객 전체 CSR, 시작일 미래·사용 안 함 CSR 제외, 4M 유형 둘 중 큰 쪽) · 경고 ①~④ · 고객 없음·4M 없음은 경고 없음 · 정책 BLOCK 이면 상신 400(`qms4m`/`custApproval`), WARN 이면 상신 통과 · CSR 요구 입력(없는 유형·틀린 값 칸 오류, NONE = 저장 안 함, null = 그대로) · 직접 SQL CHECK · A사로 B사(B사로 A사 ECO 판단 404, B사 CSR 이 A사 ECO 판단에 안 섞임, PLM 계약 `custId`·`pcns` 가 B사 범위에서 빈 값) · 순수 함수 손 검산.

### 52.3 하지 않은 것

- `qms_pcn`(A 채택), PCN 기록·응답·메일 화면(PLM 것), 수주 이력으로 고객 추정(Q4-4)
- PCN 을 적용·출하 게이트로 쓰기(docs/39 14.5 — 게이트는 ECO 고객 승인 하나)
- 홈 카드 "응답 기한 지난 PCN" — PLM 목록이 이미 표시(14.5). 필요하면 다음 차수

## 53. 현장 단말(POP) 검사 입력 계약 `FloorInspPort` (2026-11-10, `claude/qms-floor-insp`, V265·임시 V9200 — docs/25 4.57 PI-* 질의에 대한 QMS 답)

> 근거: POP 세션 설계 docs/25 4.57(QMS 계약·표 변경 질의) + 사용자 결정(2026-11-10, 상위 세션 전달) — **검사 시작은 자동**(초물 = 첫 실적 LOT, 중물 = 순회 PATROL, 새 유형 없음) · **판정은 자동**(측정할 때마다 미리보기, 샘플을 다 채워 전부 OK 면 그 자리에서 확정, NG 면 작업자가 [불합격 확정] 한 번) · 측정값 무효·검사 무효는 사무 화면에서만. 화면·`/api/pop/**` 는 POP 세션 몫.

### 53.1 기존 결정과 대조 — 어긋남 없음

| 대조한 결정 | 내용 | 판단 |
|---|---|---|
| Q6-6 판정 규칙 | 판정 확정 = 모든 행이 OK/NG 일 때만, 하나라도 NG 면 검사 NG(`InspJudge`) | 같은 함수·같은 조건. 자동 확정은 "전부 OK" 일 때만, NG 확정도 "모든 행 판정" 이 필요(Re 에 닿아 한 행이 NG 여도 다른 행이 열려 있으면 확정 못 함 — 사무 판정과 같다) |
| 판정 권한 | 사무 `judge` 는 `qms-insp` SAVE(측정과 같은 권한) — 따로 "판정 권한" 결정 없음 | 단말은 화면 권한이 아니라 등록 단말 + 배지 작업자(POP 관례). 판정자 칸(`judged_by`)엔 단말 행위자, 작업자는 새 칸 |
| 전자서명 12.10 E-1 | 서명 지점 = NCR 종결·특채 상신·교정 판정. 검사 판정 확정은 서명 지점이 아님 | 정책 `QMS_ESIGN_REQUIRED` 와 무관 — 어긋남 없음 |
| 판정 후속 | `judge` 끝 = SPC 위반 이벤트(19·23장) + 재고 품질 상태(`QualityStockLink`, WC-4). NCR·LOT 보류는 자동 아님(사람이 NCR 을 연다) | 단말 확정도 **같은 메서드 경로**로 — 후속이 그대로 걸린다 |
| docs/25 PI-4(POP 제안) | "판정은 사무 화면" 추천 | 사용자 결정(자동 판정)이 이긴다 — QMS 결정이 아니라 POP 제안이었음 |

정책 질의로 남김(막지 않음): 단말 확정에 **작업자 검사 자격**(MES 작업자 자격 `mst_worker_qual`) 확인을 붙일지 — 지금 사무 판정도 자격을 보지 않는다(Q-fi-1).

### 53.2 정한 것

| # | 정한 것 | 내용 |
|---|---|---|
| FI-1 | 계약 | `module.qms.link.FloorInspPort`(인터페이스 + `NONE` + `of(ObjectProvider)`, `BreakdownReport` 와 같은 틀). 메서드 `due`·`open`·`detail`·`gauges`·`measure`·`confirmNg`. PI 제안의 `voidMeasure` 는 뺐다(무효는 사무만 — 사용자 결정), `occurredAt` 도 뺐다(측정 시각은 서버 — SKILL "검사 시각은 서버") |
| FI-2 | 키 = 지시 공정 | `woProcId` 로 지시·공정·품목·공장을 QMS 가 정한다(단말이 공정·품목을 따로 보내 어긋나지 않게). 지시 공장 ≠ 단말 공장 → 칸 오류 `FLOOR_PLANT`(due 는 빈 목록) |
| FI-3 | 검사 시작 자동 | `due` = 이 공정·품목에 오늘 유효한 확정 검사계획이 있는 단말 유형(FIRST_OFF·PATROL·LAST_OFF·PROCESS) + 추천 LOT(초물 = 이 지시 공정 **첫** 실적 LOT, 그 밖 = **최근** 실적 LOT — 정정된 실적·역분개 제외) + 열린 검사 + 최근 판정. `open` = 같은 유형·LOT·공정의 **열린 검사가 있으면 그것**(`existing`), 없으면 사무 `create` 와 같은 경로(계획 해석·샘플 수·채번)로 새로. LOT 은 그 지시의 산출 LOT 이어야(`lotNo` 칸 오류 `NOT_WO_LOT`) |
| FI-4 | 판정 자동 | `measure` = 사무 측정과 같은 검증(`checkEntry` — 샘플 번호·값·계측기 클래스·교정 막힘) → 원장 INSERT → `InspJudge` 미리보기. **모든 행 판정 + 전부 OK → 같은 트랜잭션에서 `judge` 경로로 확정**(`judged = true`). NG 가 섞이면 확정하지 않고 `nextAction = CONFIRM_NG`. `confirmNg` = 미리보기 NG·모든 행 판정일 때만 `judge` 경로(아니면 409 `QMS_INSP_STATE` / 400 `QMS_INSP_INCOMPLETE`) |
| FI-5 | 원장 칸(V9200) | `qms_insp_meas` + `performed_by`(작업자 `mst_worker`)·`terminal_id`(`sys_terminal`)·`client_req_id`, `entry_method` 에 `SCAN` 허용(KEY·IMPORT·SCAN), 단말 입력이면 작업자·단말·요청 id 셋 다 있어야(CHECK), 같은 요청 id 로 같은 (행, 샘플) 두 번 금지(부분 유일). `qms_insp` + `open_worker_id`·`open_terminal_id`·`open_req_id`, `judged_worker_id`. 같은 회사 작업자·단말은 트리거(FK 는 RLS 를 못 봄). 원장 규칙(추가만·두 시각·정정 = 무효 행) 그대로 — 무효 행은 사무 화면에서만이라 단말 칸 없음 |
| FI-6 | 멱등 | `sys_inbox` 소비자 `QMS_FLOOR_OPEN`·`QMS_FLOOR_MEAS`·`QMS_FLOOR_NG`(message_id = clientReqId). 재전송 = 처음 결과(`duplicate = true`) — open 은 요청 id 로 찾은 검사, measure·confirmNg 는 지금 화면 |
| FI-7 | 행위자 | `recorded_by`·`created_by`·`judged_by` = `t{단말 id}:{단말 코드}`(POP 관례), 작업자 = 새 칸. 사무 쓰기(`me()` = 사람 로그인)는 그대로 |
| FI-8 | 단말 범위 | `detail`·`measure`·`confirmNg` 는 단말 유형(FI-1 TYPES)·단말 공장 검사만 — 그 밖 404(수입·출하 검사를 단말로 판정하지 않게) |

### 53.3 시험

정상(단말 + 작업자: due → open(자동) → 측정 → 전부 OK 자동 확정, NG → CONFIRM_NG → 불합격 확정, 확정 뒤 SPC·재고 후속 경로) · 열린 검사 재사용 · 등록 안 된(폐기) 단말·없는 작업자·다른 공장 · 같은 clientReqId 두 번 · 수입 검사 단말 접근 404 · A사 단말로 B사 검사 · 직접 SQL(단말 입력 칸 셋 CHECK, 다른 회사 작업자) · 사무 API 회귀(`InspApiTest` 등).
