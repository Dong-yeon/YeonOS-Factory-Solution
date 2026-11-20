# 41. P6 APQP — PFMEA·관리계획서 설계 (초안)

작성: 2026-10-23, PMS 세션(결정 docs/29 #49 "PMS 다음 = P6 APQP"). **상태: 8장 결정 확정(2026-10-23 사용자 — P1 새 모듈 apqp, P2~P8 추천 그대로). A1 구현 = 4.1절(main V145), 데이터 접근 jOOQ 교체·비교 = 4.2절. A2 설계 = 5장, 8.1절 C1~C8 추천안 확정(10절), A2 구현 = 5.7절(검사계획 대조 포함). A3 연결 범위 = 11장(L1~L7 추천안 확정), A3 1차 구현 = 11.5절, A3 2차(재검토 ②③) = 11.6절, A3-5 조치 → PMS 이슈 = 11.7절, A3-3 QMS 문서 배포 = 11.8절(B1~B7 승인, 구현 11.8.5). A3-4 ECO 대상 L4 검토 = 11.10절(DOC 대상으로 충분). A3-6 준비(현행 확정본 계약) = 11.11절. A4 엑셀 양식 = 12장(X1~X8 추천안 확정, 구현 12.5절). A5 공정 흐름도·DFMEA = 13장(설계 초안, D1~D9 결정 대기).**
근거: docs/00 6장 P6 행(APQP = PFMEA·관리계획서·PPAP·CQI), 02 2.3(표 초안 `qms_fmea*`·`qms_cp*`, **AP 표는 시드 금지 — 저작권**), 20(라우팅 리비전), 21(특성 마스터 `qms_char`), 29 D3·9장·11.2(AQL 표 시드 금지·P6 경계·CP 문서 리비전), 32 D5·6장(PMS = 일정·관문·산출물 칸, 내용 = P6), 35 #69(PFMEA/CP 편집 = P6 1순위), 39 D8·15장(설계 리비전 = 도면 문서 리비전, ECO 대상 문서 유형).
**외부 근거는 공개 요약만**: AIAG & VDA FMEA Handbook(2019) 공개 소개 자료의 7단계 방법·구조(공정 아이템 › 공정 단계 › 작업요소 4M)·고장사슬(FE–FM–FC)·RPN 대신 조치 우선순위(AP H/M/L), AIAG APQP 3판·Control Plan 1판(2024) 공개 소개(관리계획서 단계 = 시작품·양산 전·양산, FMEA 와 연결), IATF 16949 8.5.1.1(관리계획 — FMEA 연결·개정 사유) 공개 요지. **핸드북의 AP 표 값·양식·문구는 넣지 않는다**(유료·저작권 — 02·29 D3 와 같은 판단). 회사·고객사 자료는 복사하지 않는다.

## 1. 결론 먼저

- **P6 = "PFMEA 와 관리계획서의 내용"**. 일정·관문·산출물 칸은 PMS(이미 있음), 문서 파일·리비전·배포는 QMS 관리문서(`mst_doc_rev`, 이미 있음), 검사 실행은 QMS 검사계획(`qms_insp_plan*`, 이미 있음), 설계 변경은 PLM ECO(이미 있음). P6 는 이것들을 **새로 만들지 않고 가리킨다**.
- 특성(`qms_char`)·불량 유형(`qms_defect`)·공정(`mst_proc`)·라우팅 리비전(`mst_routing`·`mst_routing_step`)은 **P1 에서 키를 이미 잡아 두었다**(02 의 걱정 "P1~P3 에서 키를 안 잡으면 갈아엎는다" 는 해소됨). P6 는 이 키에 FK 로 붙는다.
- **관리계획서 행 = QMS 검사계획 행의 상위 집합**. 검사계획 행(특성·방법·계측기 클래스·샘플링·주기·반응계획)은 이미 있으므로 CP 는 "공정 관리 전체(검사 + 오류방지·SPC·셋업 검증·작업 표준)" 를 담고, **검사 행은 QMS 검사계획과 맞는지 대조**한다(2단계 — 같은 데이터를 두 곳에 손으로 쓰지 않게).
- 단계: **A1 PFMEA**(구조·기능·고장사슬·위험·AP·조치·개정) → **A2 관리계획서**(PFMEA 에서 끌어오기 + 검사계획 대조) → **A3 연결**(PMS 산출물·QMS 문서 리비전·PLM ECO·4M 변경) → **A4 출력·확장**(엑셀 양식·공정 흐름도·DFMEA). **1단계 = A1.**

## 2. 의존 확인 (2026-10-23 main ae1215e, 코드로 확인)

| 필요 | 있는 것 | 위치 | P6 가 쓰는 방법 |
|---|---|---|---|
| 특성(요구사항·특별특성) | `qms_char` — 제품(품목)·공정(`mst_proc`) 특성, `special_cls`(코드 `SPECIAL_CHAR` NONE/CC/SC + 회사 고객기호), `source` 에 **FMEA 값이 이미 있음**, 삭제 금지·RLS. 행 리비전 없음(감사·도면 rev·4M 으로 추적, 소비자가 스냅샷) | V024·V029, 코드는 기준정보 모듈(`module.master.quality`) | FK `char_id` + 같은 회사 트리거. 조회는 기준정보 표 직접 조인(선례: QMS 검사계획 매퍼·PMS 이슈 매퍼가 `mst_item` 직접 조인) — **계약 없음**(8장 P4) |
| 공정 단계 | `mst_routing`(품목 × 공장 × 대안 × **rev**, DRAFT/RELEASED/OBSOLETE, 기간 겹침 금지, 확정 뒤 불변) · `mst_routing_step`(op_seq·`proc_id`·작업장 `node_id`) · `mst_routing_param`(공정 파라미터 상하한) | V022, docs/20 | PFMEA 머리가 **라우팅 리비전 하나**를 가리키고, 구조 트리의 공정 단계가 `step_id` 를 가리킨다(8장 P2) |
| 작업요소(4M) | 설비 `mst_equip`·자산(금형·지그·게이지) `mst_phys_asset`·작업장 계층 | V020·V021 | 작업요소는 글자 + 4M 구분이 기본, 설비·자산은 선택 FK(A1 은 글자만, 8장 P5) |
| 고장 형태 사전 | `qms_defect`(불량 유형, 분류 `DEFECT_CAT`) — 고장 형태 라이브러리는 아님 | V024 | FM 이 선택적으로 `defect_id` 를 가리킨다(현장 불량 집계 ↔ FMEA 대조용) |
| 설계 리비전 | 품목 리비전 표 **없음**. 설계 리비전 = 도면 문서 리비전(`mst_doc` DRAWING, docs/39 D8) | V078 | 머리에 도면 리비전 FK 는 두지 않고 글자 기록(A1), A3 에서 `doc_rev_id` 검토 |
| 문서 리비전·배포 | `mst_doc` 유형 **PFMEA·CP 이미 있음**, `mst_doc_rev` 결재 배포. 계약 `DocLookup`(읽기) | V078, `module.qms.link` | A3: 확정된 PFMEA·CP 를 문서 리비전으로 "배포" — 쓰기 계약이 QMS 에 필요(8장 P6) |
| 검사 실행 | `qms_insp_plan`(리비전·확정) · `qms_insp_plan_line`(특성·방법·계측기 클래스·샘플링·주기·반응계획 + 확정 때 규격 스냅샷) | V047 | A2: CP 검사 행 ↔ 검사계획 행 대조(읽기 계약 필요) |
| 변경 | PLM `plm_eco`(+ 대상 DOC: DRAWING·WI·CP·INSP_STD, **PFMEA 없음**), QMS 4M `qms_change`·`qms_change_char` | V050·V060·V082·V088·V094 | A3: ECO·4M 변경이 특성·공정을 바꾸면 "FMEA 재검토 필요" 표시 |
| 프로젝트 | PMS 산출물 `ref_type` FILE·QMS_DOC_REV·PLM_PPAP, 기본 APQP 템플릿 작업 "공정 FMEA"(산출물 PFMEA) | V063·V098·V121 | A3: `ref_type = APQP_FMEA`·`APQP_CP` — 확정 = 산출물 승인 파생(PMS 안, 내 모듈) |
| PPAP | PLM `plm_ppap`·`plm_ppap_elem`(18요소) — 02 의 `qms_ppap_*` 초안을 대신함 | V069 | P6 는 PPAP 를 만들지 않는다. PFMEA·CP 확정본이 PPAP 요소 근거가 되는 연결은 A3 후보 |

부족한 것: 기준정보(특성·라우팅·불량 유형) **조회 계약 없음**, 품목 리비전 없음, ECO 대상 문서 유형에 PFMEA 없음, QMS 문서·검사계획 **쓰기 계약 없음**.

## 3. 단계

| 단계 | 범위 | 다른 모듈 의존 | 새 표(초안 이름, 8장 P1) |
|---|---|---|---|
| **A1 PFMEA** | 머리(품목 × 라우팅 리비전, 프로젝트 선택), 구조 트리(공정 아이템 › 공정 단계 › 작업요소 4M), 기능(+ 특성 FK), 고장(FE·FM·FC, FM ↔ FE·FC 연결 = 고장사슬), 위험(S·O·D·예방·검출 관리, **AP = 회사가 입력한 AP 규칙표로 계산**), 조치(최적화: 담당·기한·상태·조치 뒤 O·D·AP), 개정(DRAFT → RELEASED, 확정본 불변, 새 리비전 = 복사), AP 규칙표 편집 화면 | **읽기만**: `mst_item`·`mst_routing(_step)`·`qms_char`·`qms_defect` FK·조인. 쓰기 없음 | `apqp_fmea`·`apqp_fmea_node`·`apqp_fmea_func`·`apqp_fmea_fail`·`apqp_fmea_chain`·`apqp_fmea_risk`·`apqp_fmea_action`·`apqp_ap_set`·`apqp_ap_rule` |
| A2 관리계획서 | 머리(품목 × 라우팅 리비전 × **단계** 시작품/양산 전/양산, 근거 PFMEA 리비전), 행(공정 단계·특성·특별특성 스냅샷·관리 방법·계측기 클래스·샘플 크기·주기·반응계획·근거 위험행), **PFMEA 에서 끌어오기**(검출 관리·특별특성), 검사계획 대조(빠진 특성·다른 주기) | QMS 검사계획 **읽기 계약**(QMS 세션 구현) | `apqp_cp`·`apqp_cp_line` |
| A3 연결 | PMS 산출물 `ref_type` APQP_FMEA/APQP_CP, 확정 → QMS 문서 리비전 배포, PLM ECO 대상에 PFMEA 추가·영향 표시, 4M 변경 → 재검토 표시, 조치 → PMS 이슈(선택) | QMS 문서 **쓰기 계약**, PLM 대상 유형 확장(남의 표), QMS 4M 읽기 | 없음(또는 연결 칸) |
| A4 출력·확장 | 엑셀 양식(우리 배치, 핸드북 양식 복사 아님), 공정 흐름도(라우팅에서), DFMEA(설계 구조), 패밀리 FMEA·기본 FMEA 복사 | — | 필요 때 |

## 4. A1 데이터 모델 (초안)

모든 표: `comp_cd` + RLS(`tenant_isolation`) + 감사 칸 + `row_ver`, 같은 회사 FK 트리거. **확정(RELEASED) 리비전의 행은 바꿀 수 없다**(트리거 — 라우팅·검사계획과 같은 규칙).

| 표 | 칸(요지) | 규칙 |
|---|---|---|
| `apqp_fmea` | `fmea_id`, `fmea_no`(채번 `APQP_FMEA`), `rev`, `fmea_type`(PFMEA, 뒤에 DFMEA), `item_id`, `routing_id`(nullable — 라우팅 없는 신규 품목은 글자 단계로 시작), `proj_id`(nullable, PMS 프로젝트), `title`, `scope`, `team`, `drawing_rev`(글자), `ap_set_id`, `status` DRAFT/RELEASED/OBSOLETE, `released_at/by`, `prev_fmea_id` | 회사 안 `(fmea_no, rev)` 유일, 같은 번호에 DRAFT 하나·RELEASED 하나까지. 새 리비전 확정 = 이전 RELEASED → OBSOLETE |
| `apqp_fmea_node` | `node_id`, `fmea_id`, `parent_id`, `node_kind` ITEM/STEP/ELEMENT, `seq`, `node_nm`, `step_id`(STEP 만, FK `mst_routing_step` — 머리 라우팅의 단계여야), `m4`(ELEMENT 만: MAN/MACHINE/MATERIAL/METHOD/ENV/MEASURE) | 깊이 3 고정(ITEM › STEP › ELEMENT), 루트 ITEM 하나 |
| `apqp_fmea_func` | `func_id`, `node_id`, `func_text`, `char_id`(nullable FK `qms_char` — 요구사항이 특성이면) | 특성은 머리 품목의 제품 특성이거나 단계 공정의 공정 특성 |
| `apqp_fmea_fail` | `fail_id`, `func_id`, `fail_kind` FE/FM/FC, `fail_text`, `severity`(FE 만 1~10), `defect_id`(FM 만, nullable FK `qms_defect`) | FE = ITEM 기능, FM = STEP 기능, FC = ELEMENT 기능(구조 수준 규칙 — 종류는 서버가 노드 수준으로 정한다) |
| `apqp_fmea_chain` | `fm_id`, `fe_id` | FM ↔ FE N:M(같은 FMEA 안) |
| `apqp_fmea_risk` | `risk_id`, `fm_id`, `fc_id`, `prev_ctrl`, `det_ctrl`, `occ`(1~10), `det`(1~10), `sev`(= 연결 FE 의 최댓값, 저장 = 확정 때 스냅샷), `ap` H/M/L(규칙표 계산, 저장), `special_cls`(제안값, 코드 `SPECIAL_CHAR`) | (FM, FC) 한 쌍에 한 행. S 가 바뀌면 AP 다시 계산 |
| `apqp_fmea_action` | `action_id`, `risk_id`, `action_kind` PREVENT/DETECT, `action_text`, `owner_user_id`, `due_date`, `status` OPEN/DONE/CANCELLED, `done_on`, `evidence`, `new_occ`·`new_det`·`new_ap` | 확정 리비전에서도 **상태·증빙만** 갱신 가능(조치는 리비전 뒤에도 진행 — 8장 P7) |
| `apqp_ap_set` / `apqp_ap_rule` | 규칙표 이름·버전·사용 / `s_from..s_to`·`o_from..o_to`·`d_from..d_to` → `ap` | **시드 없음**. 1,000 조합(S·O·D 각 1~10)이 빠짐·겹침 없이 덮여야 "사용" 가능(검사 함수). 데모 회사는 "예시 — 핸드북 표 아님" 이름의 단순 규칙으로 시드(8장 P3) |

화면(A1): `apqp-fmea`(목록 grid → 상세: 왼쪽 구조 트리 `hb.tree`, 오른쪽 기능·고장·위험 표, 위험 표는 FM 기준 행 + FC·관리·O·D·AP 칸, AP 배지 = H danger·M warning·L neutral), `apqp-ap`(규칙표 편집 + 덮임 검사 결과). 권한 SEARCH·SAVE·APPROVE(확정). 엑셀 내려받기는 위험 표 그대로(A4 에서 양식).

### 4.1 A1 구현 결과 (2026-10-23, V145 — #351 → main #359)

| 영역 | 한 것 |
|---|---|
| 표 | 4장 9개 그대로 + 공통코드 `APQP_FMEA_STATUS`·`APQP_M4`·`APQP_AP`·`APQP_ACTION_STATUS`, 채번 `APQP_FMEA`(FM-yyyy-nnnn), 화면 `apqp-fmea`(846, SEARCH·SAVE·APPROVE)·`apqp-ap`(847, SEARCH·SAVE). 규칙 칸 이름은 `sev_from`… (Lombok 게터 `getSFrom` → JSON `sfrom` 이 되는 것을 피함) |
| 트리거(직접 SQL 도 막는다) | 같은 회사(품목·라우팅 = 머리 품목의 것·프로젝트·규칙표·특성·불량 유형·담당), 구조 수준(ITEM › STEP › ELEMENT, 단계 = 머리 라우팅의 단계), 고장 종류 = 노드 수준, 사슬 FM → FE, 위험 짝(FC 는 FM 단계 바로 아래 작업요소), 확정본 불변(머리는 RELEASED → OBSOLETE 와 row_ver 만), 확정본 조치는 진행 칸만·같은 번호 작성 중이 있으면 거부, 확정 FMEA 가 쓴 규칙표의 규칙 고정, 위험행 AP 없이 확정 거부 |
| 서비스 | 칸 오류로 먼저 알림(`valid.APQP_*`), 하위 행을 바꿀 때마다 머리 잠금(`for update`)·row_ver 올림, S·AP 는 Java(`FmeaCalc`·`ApRules`)가 계산해 저장(파생 값은 행 row_ver 를 올리지 않음), 확정 조건 미달 = `APQP_RELEASE_CHECK` + 행 키(`FAIL:id`·`RISK:id`), 새 리비전 = 노드·기능·고장·사슬·위험·조치 복사 |
| 화면 | `apqp-fmea`: 조회 영역 + hb.grid(클릭·Enter = 상세, 32 4.11 G1 과 같은 이유) → 머리·구조 트리·고른 요소의 기능·고장·고른 단계의 위험 분석표(영향 연결·원인 추가)·위험행·조치. `apqp-ap`: 규칙 행 편집 + 덮임을 화면에서 바로 셈(서버가 저장 때 다시 판정) |
| 데모 | DEMO 회사에 "예시 규칙표(핸드북 아님)" 1개(`sample_yn = Y`, S 9~10 = H · S 1~8 · O 6~10 = M · 나머지 L — 우리가 정한 단순 예시) |
| 데이터 접근(결정 docs/29 #52 — APQP = jOOQ 시범 모듈) | 서비스는 `ApqpRepository` 인터페이스에만 기댄다. 처음 구현 = MyBatis(#351), **2026-10-23 jOOQ 구현 `JooqApqpRepository` 로 교체, MyBatis 매퍼·XML 삭제**(4.2절 — `ApqpFmeaTest` 무수정 통과 + 두 구현 공통 `ApqpLookupTest`) |
| 하지 않은 것 | 결재선(P8 — A3), 엑셀 양식(A4), DFMEA, 관리계획서(A2) |

### 4.2 데이터 접근 MyBatis → jOOQ 교체와 비교 (2026-10-23, 결정 docs/29 #52 · docs/43)

같은 `ApqpRepository`(메서드 59개)의 두 구현을 같은 시험으로 맞춘 기록. 기준 커밋 = main 599c288(MyBatis 구현 + jOOQ 기반) → 이 브랜치(jOOQ 구현, MyBatis 매퍼·XML 삭제).

**동등성 근거**
- `ApqpFmeaTest` **무수정**(main 과 diff 없음) 6건 — 두 구현 모두 통과.
- 이 시험이 덮지 않던 조회(고르기 목록 7종·계산 칸 정렬·표시 칸)는 새 `ApqpLookupTest` 2건으로 고정했다. 이 시험도 **MyBatis 구현(main 599c288 worktree)과 jOOQ 구현 양쪽에서 통과**를 확인한 뒤 넣었다.
- 처음 쓴 기대값 하나(특성 순서 `char_type desc` = PRODUCT 먼저)가 틀렸는데, 두 구현이 같은 순서를 냈다. 구현 차이가 아니라 시험 쪽 오류였다.

| 항목 | MyBatis | jOOQ |
|---|---|---|
| 손으로 쓴 데이터 접근 코드 | XML 408줄 + `ApqpMapper.java` 240줄(인터페이스 메서드를 `@Param` 때문에 다시 선언) = **648줄, 파일 2개** | `JooqApqpRepository.java` **671줄, 파일 1개** |
| 생성 코드 | 없음 | `src/main/jooq` 21표 14,503줄(커밋, 리뷰 대상 아님). 이 PR 에서 4표 추가: `mst_proc`·`sys_numbering_rule`·`sys_code`·`sys_comp_code` |
| 칸 이름 바꿈 실험(생성물에서 `apqp_fmea.title` 이름을 바꿔 컴파일) | 컴파일 0건. 그 SQL 5곳은 실행될 때 실패하고, 시험이 그 문을 지나야만 잡힌다 | **컴파일 오류 5건**, 쓰는 곳 줄 번호 그대로(조회 칸·검색 조건·정렬·등록·수정) |
| 형 바꿈 실험(`risk.occ` 를 문자로) | 컴파일 0건, 실행 때 변환 오류 또는 조용한 변환 | **컴파일 오류 2건**(등록·수정) |
| 처음 작성 때 컴파일이 잡은 것 | — | 첫 컴파일에 통과. 다만 점수 칸 12개가 `smallint` → `Short` 라서 `Integer` 를 그대로 넣을 수 없었다(생성물을 보고 `sh()` 변환을 미리 둠). MyBatis 는 이 차이를 말없이 변환한다 |
| 동적 조건(목록 검색) | `<where><if>` + `<foreach>` — 조건 이름이 문자열(`f.keywordLike`)이라 오타는 실행 때 | `Condition` 을 `noCondition()` 에 이어 붙이는 Java 코드 — 필터 record 접근자라 오타는 컴파일 |
| 정렬(허용 목록) | `<choose>` 에 enum **이름 문자열**(`s.key == 'HIGH_CNT'`). enum 에 값을 더하고 XML 을 잊으면 조용히 기본 정렬 | enum `switch` 식(default 없음) — 값을 더하고 잊으면 **컴파일 오류**. 계산 칸은 별칭 칸 객체를 그대로 `orderBy`(MyBatis 처럼 `select * from (…) x` 로 감쌀 필요 없음) |
| 하위 행 종류별 조회(`fmeaOf`) | `<choose>` 6갈래 | `switch` 6갈래 — 차이 없음 |
| 재귀 CTE | A1 에는 없다(구조 깊이 3 고정, 트리를 한 번 읽어 Java 에서 맞춤). 쓰면 SQL 그대로 | A1 에는 없다. 임시 시험으로 `withRecursive`(노드 하위 트리)가 3.19.30 + PG 16 에서 동작함을 확인(커밋 안 함). CTE 의 자기 칸(`t.node_id`·`depth`)은 생성물이 아니라 `DSL.name` + 형 지정이라 **그 부분은 컴파일 검사가 없다** — 가장 큰 쿼리일수록 이점이 줄어든다 |
| DB 함수 | `hb_current_comp()`·`hb_code_usable()` 을 SQL 에서 그대로 | 생성 대상에서 루틴을 뺐다(docs/43 3장). 그래서 INSERT 의 `comp_cd` 는 `TenantContext` 값으로 넣는다(다른 값이면 RLS WITH CHECK 가 막는다 — 안전성은 같다). 코드 사용 판정은 `sys_code`·`sys_comp_code` EXISTS 두 개로 **DB 함수 로직을 다시 썼다** → 함수가 바뀌면 어긋날 수 있다(아래 불편 1) |
| SQL 읽기 | XML 이 곧 SQL — 리뷰·실행계획 복사가 쉽다 | 머릿속 번역이 필요하다. 실제 SQL 은 `getSQL()`·로그로 본다 |
| 빌드·도구 | 없음 | 스키마를 바꾸면 Docker 로 `jooqGenerate` → 생성물 커밋. 잊으면 어긋남 가드가 `check` 를 막는다 |

**불편했던 점(시범 판단 Q3 재료)**
1. **DB 함수를 못 부른다**: 플랫폼 함수(`hb_code_usable` 등)를 Java 로 다시 쓰게 된다. 함수 몇 개만 생성 대상에 넣을지(`withIncludeRoutines` + 허용 목록)는 시스템관리 판단 — 상위에 질문 후보. → **해소(2026-10-23, #383)**: 판정용 순수 함수만 허용 목록(`JooqCodegen.ROUTINES` = `hb_code_usable`, 가드 `JooqRoutinesGuardTest`)으로 생성, `codeUsable` = `Routines.hbCodeUsable(...)` 호출로 바꾸고 Java 재작성·`sys_code`·`sys_comp_code` 생성물을 지웠다(A2 PR). `hb_current_comp` 는 여전히 부르지 않는다(INSERT 의 `comp_cd` = `TenantContext`)
2. **생성 범위 확장이 PR 마다 따라온다**: 조인 하나에 `INCLUDES` 수정 → Docker 재생성 → 생성물 diff(이번에 `mst_routing_step`·`qms_char` 파일도 `mst_proc` 로 가는 FK 경로가 생겨 바뀜).
3. **`smallint` → `Short`**: 서비스·DTO 는 `Integer` 라 쓰는 곳마다 변환. 생성기 강제 형(`forcedTypes`)으로 풀 수 있으나 공통 설정이라 이번엔 손대지 않음.
4. 생성기 경고 1건(`fk_apqp_fmea_node_parent` 자기 참조 FK 의 역방향 메서드 이름 겹침) — 동작 영향 없음, 기록만.

**결론(이 모듈 크기에서)**: 손으로 쓰는 양은 비슷하다(648 → 671줄). 이득은 **스키마·형·정렬 허용 목록의 어긋남이 컴파일에서 잡힌다**는 것이다. 비용은 생성 파이프라인(Docker)과 DB 함수 재작성이다. 넓힐지는 docs/43 Q3 에서 A2(관리계획서, 처음부터 jOOQ) 뒤 다시 본다.

## 5. A2 관리계획서 설계 (2026-10-23 — 8.1절 C1~C8 추천안 확정, 10절)

근거(공개 요지만): AIAG Control Plan 1판(2024)·APQP 3판 공개 소개 — 관리계획서는 단계(시작품·양산 전·양산)마다 두고, 행은 "공정 단계 × 특성(제품·공정) × 관리 방법"이며 PFMEA 와 연결된다. IATF 16949 8.5.1.1 공개 요지 — 관리계획은 PFMEA 와 연결되고, 특별특성을 담고, 개정 사유를 남긴다. **AIAG 양식의 칸 배치·문구는 복사하지 않는다**(엑셀 양식은 A4 에서 우리 배치로).

### 5.1 의존 확인 (2026-10-23 main fc142ac, 코드로 확인)

| 필요 | 있는 것 | 쓰는 방법 |
|---|---|---|
| 근거 PFMEA | `apqp_fmea`(RELEASED 만)·`apqp_fmea_risk`(`det_ctrl`·`prev_ctrl`·`special_cls`·`ap`)·FM·FC 의 기능 → `char_id` | 같은 모듈 — 직접 |
| 공정 단계·특성 | `mst_routing_step`·`qms_char`(규격·`special_cls`·`data_type`) | 직접 조인 + FK(P4 와 같음) |
| 계측기 종류 | 공통코드 `GAUGE_CLASS`(VISUAL·CALIPER·…, 회사 추가 가능) | 코드 그대로 씀(`hb_code_usable`) |
| 주기 | `qms_insp_plan_line.freq_type` EVERY_LOT·START·END·EVERY_N·EVERY_HOURS + `freq_n` (CHECK 로만 정의, 공통코드 아님) | **같은 값·같은 CHECK** 를 CP 행에 둔다 — 대조가 값 비교로 끝나게 |
| 샘플링 | `qms_sampling_plan`(회사 표, QMS 소유) | CP 는 글자 + 수(`sample_n`) 만, FK 없음(8.1 C4) |
| 검사계획 | `qms_insp_plan`(유형 RECEIVE·PROCESS·FINAL·FIRST_OFF·LAST_OFF·PATROL × 품목/품목군 × 공정 × 대안, 기간, RELEASED) · `qms_insp_plan_line`(특성 유일·계측기 클래스·샘플링·주기·반응계획) | **읽기 계약 필요** — 지금 `module.qms.link` 에는 `DocLookup`·`CapaPort`·`SupplierQualityQuery` 뿐, 검사계획 조회 없음(8.1 C5) |

### 5.2 데이터 모델 (초안)

모든 표: `comp_cd` + RLS + 감사 칸 + `row_ver` + 같은 회사 FK 트리거, 확정본 불변(A1 과 같은 트리거 틀 — `hb_apqp_cp_editable`).

| 표 | 칸(요지) | 규칙 |
|---|---|---|
| `apqp_cp` | `cp_id`, `cp_no`(채번 `APQP_CP` CP-yyyy-nnnn), `rev`, `phase` PROTO/PRE_LAUNCH/PRODUCTION(코드 `APQP_CP_PHASE`), `item_id`, `routing_id`(nullable, 머리 품목의 것), `fmea_id`(nullable — 근거 PFMEA 리비전, 같은 품목·RELEASED 여야, 라우팅이 있으면 같은 라우팅), `proj_id`(nullable), `title`, `team`, `change_reason`(rev ≥ 2 확정 때 필수 — IATF 개정 사유), `status` DRAFT/RELEASED/OBSOLETE, `released_at/by`, `prev_cp_id` | `(cp_no, rev)` 유일, 같은 번호에 DRAFT 하나·RELEASED 하나(A1 과 같은 부분 유일 색인). **단계는 번호마다 고정**(8.1 C1) |
| `apqp_cp_line` | `line_id`, `cp_id`, `line_no`, `step_id`(nullable, 머리 라우팅의 단계) 또는 `step_text`(라우팅 없을 때), `char_id`(nullable FK `qms_char` — 머리 품목의 제품 특성 또는 그 단계 공정의 공정 특성) 또는 `char_text`, `ctrl_method`(코드 `APQP_CTRL_METHOD`: INSPECT·SPC·ERROR_PROOF·SETUP_VERIFY·WORK_STD·OTHER), `eval_method`(평가·측정 방법 글자), `gauge_class`(nullable, `GAUGE_CLASS`), `insp_type`(nullable — INSPECT·SPC 행이 대조할 검사 유형, 검사계획과 같은 6값), `sample_n`(nullable 1~1,000,000)·`sample_text`, `freq_type`·`freq_n`(검사계획과 같은 CHECK), `reaction_plan`, `risk_id`(nullable — 근거 위험행, 머리 `fmea_id` 의 것), **확정 때 스냅샷** `special_cls`·`nominal`·`lsl`·`usl`·`unit_nm`·`data_type`(검사계획 행과 같은 방식: 작성 중엔 비고 화면은 특성을 따라간다) | 단계·특성 중 하나 이상은 키 또는 글자. `(cp_id, line_no)` 유일(DEFERRABLE — 순서 맞바꿈). 같은 `(step, char, ctrl_method)` 중복 금지 |

### 5.3 흐름

1. **만들기**: 품목(+ 라우팅·근거 PFMEA·단계) 고르기. 근거 PFMEA 를 고르면 라우팅은 그 FMEA 의 것으로 정해진다.
2. **PFMEA 에서 끌어오기**(작성 중만, 여러 번 해도 안전): 근거 FMEA 의 위험행마다 → 단계 = FM 이 달린 STEP 노드의 `step_id`(없으면 노드 이름을 `step_text`), 특성 = FC 기능의 특성 → 없으면 FM 기능의 특성, `ctrl_method` = 검출 관리가 있으면 INSPECT·없고 예방 관리만 있으면 WORK_STD(사람이 고침), `eval_method` = `det_ctrl`, `special_cls` 제안 = 위험행의 값, `risk_id`. **이미 같은 `risk_id` 행이 있으면 건너뛴다**(사람이 고친 행을 덮지 않는다 — "조용히 틀리기" 방지). 결과 = 추가 n·건너뜀 m 을 알림.
3. **검사계획 대조**(읽기 전용 표, 저장하지 않음 — 볼 때마다 계산): CP 의 `insp_type` 이 있는 행 ↔ 같은 품목(또는 품목군)·같은 공정(`step` 의 `proc_id`)·같은 유형의 **기준일 유효 RELEASED 검사계획** 행(같은 `char_id`).
   - `MISSING_IN_PLAN`: CP 에 있는데 검사계획에 없음
   - `DIFF`: 계측기 클래스·주기(`freq_type`·`freq_n`)가 다름(샘플링은 글자라 표시만)
   - `MISSING_IN_CP`: 검사계획에는 있는데 CP 에 없음(같은 공정 범위 안)
   - 대조는 **알림** — 고치는 것은 각자 화면(검사계획은 QMS 소유, P6 는 쓰지 않는다)
4. **확정**(APPROVE): 검사 조건 미달이면 `APQP_RELEASE_CHECK` + 행 키(`LINE:id`, 빠진 특성은 `CHAR:id`).
   - 근거 PFMEA 의 위험행·기능 특성 중 **특별특성(NONE 아님)** 은 모두 CP 행에 있어야 한다(IATF 공개 요지 — 특별특성은 관리계획에)
   - 행마다 관리 방법·반응계획 필수, INSPECT·SPC 는 주기 필수
   - rev ≥ 2 는 `change_reason` 필수
   - 단계 PRODUCTION 은 대조 `MISSING_IN_PLAN` 이 없어야(8.1 C6)
   - 확정 = 이전 RELEASED → OBSOLETE, 스냅샷 칸 채움
5. **새 리비전** = 행 전체 복사(스냅샷 비움), **다음 단계로** = 새 번호·새 단계로 복사(`prev_cp_id` 로 이어짐, 8.1 C1).
6. **PFMEA 가 새로 확정되면**: 근거 FMEA 가 OBSOLETE 가 된 CP 에 "근거 FMEA 새 리비전 있음" 표시(목록 칸·상세 배너). 자동으로 바꾸지 않는다 — 새 CP 리비전에서 근거를 바꾸고 끌어오기를 다시(A3 의 재검토 표시와 같은 모양).

### 5.4 화면

`apqp-cp`(sort_no **848**, PMS 메뉴, SEARCH·SAVE·APPROVE): 목록 hb.grid(번호·리비전·단계·품목·근거 FMEA·상태·"근거 새 리비전" 표시, 클릭·Enter = 상세 — G1) → 상세: 머리 + 행 표(공정 단계 순 → 행 번호, 특별특성 배지, 근거 위험행 AP 배지) + "PFMEA 에서 끌어오기" + 대조 탭(3종 묶음, 행 키로 해당 행 강조). FMEA 화면에서 "이 FMEA 로 관리계획서" 버튼(A2 의 작은 연결). 엑셀은 A4.

### 5.5 데이터 접근

A2 는 **처음부터 jOOQ**(#52 시범 범위 = `module.apqp` 전체): 같은 `ApqpRepository` 를 넓히지 않고 `CpRepository` 를 따로 두고 jOOQ 구현만 만든다 — MyBatis 로 먼저 쓰고 다시 옮기는 이중 작업을 하지 않는다. 그래서 A2 구현은 jOOQ 기반 병합 → PR2(A1 교체) 뒤(8.1 C8).

### 5.6 시험 계획 (A2)

정상: 만들기(근거 FMEA → 라우팅 결정), 끌어오기(특성 결정 순서·건너뜀·두 번 해도 같은 결과), 대조 3종(검사계획을 직접 SQL 로 준비), 확정 검사(특별특성 빠짐·반응계획 빠짐·개정 사유), 확정 → 불변·스냅샷, 새 리비전·다음 단계, 근거 FMEA 새 리비전 표시.
이상 사용(A~K): 남의 품목 라우팅·다른 품목의 FMEA·작성 중 FMEA 를 근거로, 라우팅 밖 단계, 다른 공정의 공정 특성, 다른 FMEA 의 위험행 `risk_id`, 범위 밖 주기·샘플 수, 확정본 수정(API·직접 SQL), 끌어오기를 확정본에, 동시 수정 `row_ver`, 권한(SAVE 없이 끌어오기·APPROVE 없이 확정), **A사로 B사**(B사 CP 에 A사 특성·단계·FMEA·위험행 → 트리거·RLS, 대조가 B사 검사계획을 읽지 않음), 대량(행 1,000 × 대조 실측).

### 5.7 A2 구현 결과 (2026-10-23, V153·임시 V9409)

| 영역 | 한 것 |
|---|---|
| 표 | 5.2 그대로 `apqp_cp`·`apqp_cp_line` + 공통코드 `APQP_CP_PHASE`·`APQP_CTRL_METHOD`, 채번 `APQP_CP`(CP-yyyy-nnnn), 화면 `apqp-cp`(848, SEARCH·SAVE·APPROVE). 주기·검사 유형·계측기·특별특성은 기존 코드(`INSP_FREQ`·`INSP_TYPE`·`GAUGE_CLASS`·`SPECIAL_CHAR`) |
| 트리거(직접 SQL 도 막는다) | 같은 회사(품목·라우팅 = 머리 품목의 것·근거 FMEA = 같은 품목·같은 라우팅·새로 가리킬 때 RELEASED·프로젝트), 행 단계 = 머리 라우팅의 단계, 특성 대상(제품 = 머리 품목, 공정 = 행 단계의 공정), 근거 위험행 = 머리 근거 FMEA 의 것, 계측기·특별특성 코드 사용 가능, 확정본 불변(머리는 RELEASED → OBSOLETE 만, 행은 작성 중에만), 확정 = 행 1개 이상 + 특성 행 스냅샷 있음, rev 2+ 확정은 개정 사유(CHECK), 근거 위험행·라우팅 단계를 가리키는 행이 있으면 근거·라우팅 변경 거부 |
| 서비스 | 끌어오기(C7) — 위험행마다 단계 = FM 의 STEP 노드, 특성 = FC 기능 → 없으면 FM 기능의 특성(없으면 FM 기능 글자), 검출 관리 있음 = INSPECT·예방만 = WORK_STD·둘 다 없음 = OTHER, 특별특성 제안 = 위험행 값. **건너뜀 = 같은 근거 위험행 또는 같은 단계·특성·관리 방법**(5.3 의 "같은 risk_id" 에 키 중복을 더함 — 유일 색인과 부딪히지 않게, 새 근거로 복사한 리비전에서 다시 끌어와도 사람 행을 덮지 않게). 확정 검사 = 행마다 반응계획·INSPECT/SPC 는 주기·근거 FMEA 폐기 아님·rev 2+ 개정 사유·특별특성 빠짐(행 키 `LINE:id`·`RISK:id`·`CHAR:id`). 스냅샷 특별특성 = 행에 정한 값, 없으면 특성 마스터 값 |
| 복사 | 새 리비전·다음 단계(C1 — 시작품 → 양산 전 → 양산 순서만, 새 번호, `prev_cp_id`). 근거 FMEA 가 폐기됐으면 지정해야 하고(화면은 같은 번호의 확정 리비전을 자동 지정), 근거가 바뀌면 행의 근거 위험행은 비운다 |
| 화면 | `apqp-cp`: 조회 영역(단계·상태) + hb.grid(클릭·Enter = 상세) → 머리(근거 PFMEA 를 고르면 라우팅 잠김)·근거 새 리비전 안내·관리 항목이 없는 특별특성(항목 추가 단추)·행 표(작성 중 = 특성 마스터 규격, 확정 = 스냅샷)·행 편집(작성 중만). 검사 유형 고르기에서 수입검사는 뺐다(관리계획서는 공정 관리 — API 는 받는다) |
| 데이터 접근 | `CpRepository` + `JooqCpRepository`(C8, 처음부터 jOOQ — UPDATE … FROM 으로 스냅샷). 기준정보·PFMEA 조회는 `ApqpRepository` 그대로 |
| 검사계획 대조(C5·C6) | QMS 계약 `module.qms.link.InspPlanLookup`(#386) — 검사 유형·특성 키가 있는 행만, 공정 = 행 단계의 공정(글자 단계 = 공정 무관 계획), 기준일 = 오늘(회사 날짜), 같은 (공정, 검사 유형) 은 한 번만 조회. `MISSING_IN_PLAN`·`DIFF`(계측기·주기 — 샘플링은 CP 가 글자라 표시만)·`MISSING_IN_CP`(조회한 공정·유형 범위 안). **양산 단계만** `MISSING_IN_PLAN` 이 있으면 확정 거부(`APQP_CP_NOT_IN_PLAN`, 행 키 `LINE:id`), 그 밖은 알림. QMS 가 없는 설치본(`NONE`)은 대조·거부 없음. 저장하지 않고 볼 때마다 계산, 검사계획은 고쳐 쓰지 않는다 |
| 하지 않은 것 | 엑셀 양식(A4), 결재선(A3), 검사계획 생성(QMS 쓰기 계약 — A3 후보) |

## 6. 경계

| 것 | P6 | 다른 곳 |
|---|---|---|
| APQP 일정·관문·산출물 칸 | 안 함 | PMS(산출물 `ref_type` 으로 P6 확정 상태를 끌어감 — A3) |
| PFMEA·CP 파일 리비전·배포 | 확정본을 문서로 "배포 요청"만(A3) | QMS 관리문서 |
| 검사 실행·측정·SPC | 안 함 | QMS 검사계획·검사·SPC |
| 특성·공정·라우팅·불량 유형 원본 | 안 함(가리키기만) | 기준정보 |
| PPAP 18요소·PSW | 안 함 | PLM `plm_ppap` |
| 설계 변경 | 영향 표시만(A3) | PLM ECO·QMS 4M |

## 7. 시험 계획 (A1)

정상: 구조 트리 만들기·순서, 기능·고장·사슬, 위험 → AP 계산(규칙표), S 변경 → AP 재계산, 확정 → 불변(직접 SQL 포함)·새 리비전 복사(트리·사슬·위험 그대로, 조치 상태 유지 여부 8장 P7), 이전 확정본 OBSOLETE.
이상 사용(A~K): 깊이·종류 규칙 위반(FC 를 STEP 에), 다른 FMEA 의 노드로 사슬, 라우팅 밖 단계 `step_id`, 남의 품목 특성, S·O·D 범위 밖, 덮임 불완전 규칙표 사용, 확정본 수정(API·직접 SQL), 동시 수정 `row_ver`, 권한(SAVE 없이 저장·APPROVE 없이 확정), **A사로 B사**(B사 FMEA 에 A사 특성·라우팅 단계·불량 유형·규칙표 → FK 트리거·RLS), 대량(작업요소 500 × 위험 2,000 행 조회 시간 실측).

## 8. 결정 필요 (상위·사용자)

| # | 정할 것 | 추천 | 버린 안 |
|---|---|---|---|
| P1 | 표 접두어·패키지·화면·메뉴 | **새 대역 `apqp_` / `module.apqp` / 화면 `apqp-`**, 메뉴는 "프로젝트관리(PMS)" 아래(`sort_no` 846·847 — PMS 대역 800~849 의 빈 번호, 이미 800·820·830·840·845 사용), 맡는 세션 = PMS. 이유: 내용은 품질 코어 툴이라 `pms_` 가 어색하고, `qms_` 는 QMS 세션 대역이라 동시 작업에서 부딪힌다. 문서 번호 = 41 | `qms_fmea*`(02 초안) — 이름은 맞지만 QMS 세션 대역 침범. `pms_fmea*` — 일정 모듈에 품질 내용이 섞인다 |
| P2 | PFMEA 가 무엇에 묶이나 | **품목 × 라우팅 리비전**(nullable — 라우팅 전 신규 품목은 글자 단계). 라우팅 새 리비전이 확정되면 "FMEA 재검토" 표시(A3) | 품목만 — 공정 단계를 글자로 다시 써서 라우팅과 어긋난다. 공정(`mst_proc`)별 — 공정 FMEA 를 품목마다 만들 수 없다(패밀리는 A4) |
| P3 | AP 규칙표 | **회사 입력, 시드 없음**(02·29 D3). 1,000 조합 덮임 검사. 데모 회사만 "예시(핸드북 아님)" 단순 규칙 시드 | 핸드북 표 시드 — 저작권. RPN(S×O×D) 으로 대신 — 2019 핸드북이 RPN 을 버린 이유(같은 RPN 다른 위험)를 거스름 |
| P4 | 기준정보 조회 방법 | **직접 조인 + FK**(기준정보는 모든 모듈의 공통 바탕 — 검사계획·PMS 이슈 선례). 계약은 만들지 않음 | 기준정보에 `CharLookup` 계약 신설 — 남의 모듈 코드 추가, 이점 적음 |
| P5 | 작업요소 ↔ 설비·자산 FK | **A1 은 글자 + 4M**, 설비·금형 FK 는 A4 | A1 에 FK — 신규 라인은 설비 마스터가 아직 없다 |
| P6 | 확정본의 문서 배포(A3) | QMS 에 **쓰기 계약** 요청(`DocPort.publish(type, targetItem, file)` 같은 모양) — QMS 세션 구현 | P6 가 `mst_doc_rev` 에 직접 INSERT — 남의 표 쓰기 |
| P7 | 리비전 뒤 조치 | 조치는 **리비전에 매이지 않고 이어짐**(새 리비전 복사 때 열린 조치 따라감), 확정본에서도 조치 상태·증빙만 갱신 | 조치도 불변 — 확정 뒤 조치 완료를 못 적는다 |
| P8 | 결재 | A1 확정 = **APPROVE 권한자 확정**(플랫폼 결재는 A3 에서 문서 배포와 함께) | 처음부터 결재선 — 1단계가 커진다 |

### 8.1 A2 결정 (2026-10-23 사용자 — 모두 추천안)

| # | 정할 것 | 추천 | 버린 안 |
|---|---|---|---|
| C1 | 단계(시작품·양산 전·양산)를 어떻게 | **번호마다 단계 고정**, 다음 단계 = 새 번호로 복사(`prev_cp_id`). 단계마다 따로 확정·배포되는 문서라서 | 한 번호 안에서 단계를 리비전처럼 올림 — "양산 rev 1" 과 "양산 전 rev 3" 이 같은 줄에 섞여 리비전 의미가 흐려진다 |
| C2 | 근거 PFMEA | **확정본 하나를 리비전으로 가리킴**(nullable — 시작품은 FMEA 전에 시작 가능), 새 FMEA 확정 = 표시만 | 번호만 가리켜 항상 최신 — 확정 CP 의 근거가 몰래 바뀐다 |
| C3 | CP 행의 특성 | **`qms_char` FK 또는 글자**(공정 특성이 아직 마스터에 없을 때), 확정 때 규격·특별특성 스냅샷 | 특성 FK 필수 — 시작품 단계에서 막힌다 |
| C4 | 샘플링 | **글자 + 수(`sample_n`)**, QMS 샘플링 계획 FK 는 두지 않음 | `qms_sampling_plan` FK — 남의 모듈 표에 FK(기준정보가 아님, P4 의 예외 근거가 없다) |
| C5 | 검사계획 대조 방법 | **QMS 에 읽기 계약 요청** `InspPlanLookup.lines(itemId, procId, inspType, onDate)` → 행(특성·계측기 클래스·주기·샘플링 이름·계획 번호/리비전), 예외 없음·없으면 빈 목록, QMS 없는 설치본 `NONE`(`DocLookup` 과 같은 틀). QMS 세션 구현 | `qms_insp_plan*` 직접 조인 — 기준정보가 아닌 남의 업무 표(P4 는 기준정보만). 계약 오기 전 A2 는 대조 탭이 "QMS 계약 대기" 로 빈 채 동작 |
| C6 | 대조가 확정을 막나 | **단계 PRODUCTION 만 `MISSING_IN_PLAN` 이 있으면 거부**, `DIFF`·`MISSING_IN_CP` 와 다른 단계는 알림 | 전부 알림 — 양산 CP 가 실행되지 않는 검사를 약속할 수 있다. 전부 거부 — 시작품 단계엔 검사계획이 없다 |
| C7 | 끌어오기 덮어쓰기 | **같은 `risk_id` 행이 있으면 건너뜀**(추가만) | 다시 끌어오면 갈아엎음 — 사람이 고친 관리 방법이 몰래 사라진다 |
| C8 | A2 구현 시점·데이터 접근 | **jOOQ 기반 병합 → A1 PR2 뒤, 처음부터 jOOQ**(`CpRepository` 별도). 그 전에는 C5 계약 요청만 QMS 에 | 지금 MyBatis 로 구현 → 뒤에 다시 jOOQ — 같은 일을 두 번, 비교 근거도 A1 하나로 충분 |

## 9. 하지 않을 것 (P6)

핸드북 AP 표·양식·문구·평가 기준표(S·O·D 등급 설명) 시드 — 등급 설명은 회사가 입력(규칙표와 같은 화면, 선택), FMEA-MSR, 소프트웨어 FMEA, AI 자동 고장 생성(나중 후보, docs/03), PPAP 18요소(PLM 에 있음), 검사계획 대체.

## 10. 검토 (상위 세션·사용자 작성)

| 날짜 | 결정 | 결과 |
|---|---|---|
| 2026-10-23 | 8장 P1~P8 (docs/29 #51) | P1 = 새 모듈 `apqp_`·`module.apqp`·화면 `apqp-`(846·847), P2~P8 추천 그대로 |
| 2026-10-23 | 데이터 접근 (docs/29 #52) | APQP = jOOQ 시범. A1 은 MyBatis 구현 + `ApqpRepository` 로 먼저(#351 → #359), jOOQ 기반 병합 뒤 PR2 에서 jOOQ 구현으로 교체·비교 절 |
| 2026-10-23 | 8.1절 C1~C8 (사용자, 상위 전달) | **모두 추천안**. C5 `InspPlanLookup.lines(itemId, procId, inspType, onDate)` 은 상위가 QMS 세션에 요청 전달. A2 구현 = jOOQ 기반 → PR2 병합 뒤, 처음부터 jOOQ |
| 2026-10-23 | A2 구현 (상위 전달: QMS 계약 #386·jOOQ 루틴 #383 병합) | 5.7절 — C5·C6 까지 한 PR, `hb_code_usable` 은 생성된 루틴 호출로 |
| 2026-10-23 | 11.3절 L1~L7 (사용자, 상위 전달) | **모두 추천안**. A3 1차 = A3-1(`ApqpLookup` + PMS `ref_type` APQP_FMEA·APQP_CP)·A3-2 ①(라우팅 새 리비전 재검토 표시)·A3-7(결재, 정책 기본 끔). PLM `EcoLookup.releasedFor`·QMS `ChangeLookup.approvedFor` 요청은 상위가 전달(받으면 A3-2 ②③) |
| 2026-10-23 | A3-2 ②③ 계약 (사용자, 상위 전달) | **B안** — PLM `EcoLookup.releasedFor(itemId, since)`·`approvedFor` → `EcoPage`(행 `EcoView` 에 `approvedOn`·`releasedOn`, `truncated`), QMS `ChangeLookup.details(ecoIds)` 는 4M 세부만(QMS 가 PLM 승인 규칙을 다시 구현하지 않음). QMS `DocPort` 는 작성 중까지(A4 뒤). PLM #419·QMS #417 병합(#423) 뒤 A3 2차 = 11.6절 |
| 2026-10-24 | 11.8절 B1~B7 (상위 승인, L3 범위) | **모두 추천안** — 단추로 올리기, DocPort 가 바이트를 받아 QMS 가 `attachAs`(DP-5 변경·`DocDraft.itemId` 추가는 상위가 QMS 에 요청), 같은 번호면 같은 QMS 문서의 새 리비전, APQP PRINT + QMS 문서 SAVE, 현행 확정본만. 구현 = QMS DocPort 병합 뒤 |

## 11. A3 연결 범위 (2026-10-23 — 결정 L1~L7 추천안 확정, 10절)

A1(PFMEA)·A2(관리계획서)는 다른 모듈에 쓰지 않았다. A3 는 그 확정본을 **PMS 산출물·QMS 문서·PLM 변경**에 잇는다. 원칙은 지금과 같다 — 남의 표에 직접 쓰지 않고, 남의 모듈은 `module.*.link` 계약으로 읽거나 부르고, 계약 빈이 없는 설치본(`NONE`)에서도 APQP 는 혼자 동작한다.

### 11.1 의존 확인 (2026-10-23 main 077dd74, 코드로 확인)

| 연결 | 지금 있는 것 | 위치 | 모자란 것 |
|---|---|---|---|
| PMS 산출물 | `pms_deliverable.ref_type` = FILE·QMS_DOC_REV·PLM_PPAP(CHECK), `DeliverableDocSync` 가 "QMS 배포 = 승인"·"PPAP 고객 승인 = 승인" 을 계약(`DocLookup`·`EcoLookup.ppap`)으로 다시 읽어 반영. 기본 APQP 템플릿 작업 "공정 FMEA"(산출물 PFMEA) | V063·V098·V121, `module.pms` | `ref_type` 에 APQP 값 없음, APQP 조회 계약 없음(`module.apqp.link` 없음) |
| QMS 문서 | `mst_doc.doc_type` 에 **PFMEA·CP 이미 있음**, `mst_doc_rev` 결재 배포(정책 `QMS_DOC_APPROVAL`), 읽기 계약 `DocLookup`(rev·revsByDocNo) | V078, `module.qms.link` | **쓰기 계약 없음**(문서 리비전 만들기·파일 붙이기·배포 요청), 배포할 파일(APQP 엑셀 양식 = A4) 없음 |
| PLM ECO | `plm_eco_target` 대상 = ITEM·BOM·ROUTING·EQUIP·PROC·DOC, DOC 는 유형 DRAWING·WI·**CP**·INSP_STD 만(트리거), 읽기 계약 `EcoLookup`(eco·byNo·ecos·ppap — **ECO id 로만**), PPAP 요소 6 = 공정 FMEA·7 = 관리계획서 | V050·V060·V082·V088, `module.plm.link` | 품목·라우팅으로 "확정 뒤 RELEASED 된 ECO" 를 찾는 조회 없음, PFMEA 대상 유형 없음 |
| 라우팅 새 리비전 | `mst_routing` rev·status·valid_from | V022(기준정보) | 없음 — P4 처럼 직접 읽으면 된다 |
| QMS 4M 변경 | `qms_change`·`qms_change_char` | V094, `module.qms` | 읽기 계약 없음 |
| 결재 | 플랫폼 결재(`ApprovalDocType` 구현으로 문서 유형 등록) | `platform.approval` | APQP 문서 유형 없음(지금 확정 = APPROVE 권한자 바로, P8) |

### 11.2 후보 범위와 계약 후보

| # | 연결 | 하는 일 | 계약·표(누가) | 의존 |
|---|---|---|---|---|
| A3-1 | **PMS 산출물 ← APQP 확정** | 산출물 칸에 PFMEA·관리계획서 리비전을 연결, 그 리비전이 확정(RELEASED, 이후 OBSOLETE 포함)되면 칸을 승인으로(`DeliverableDocSync` 와 같은 모양) | APQP 가 **읽기 계약** `module.apqp.link.ApqpLookup`(fmea(id)·cp(id) → 번호·리비전·상태·확정일, 예외 없음·`NONE`) 공개 + PMS `ref_type` 에 `APQP_FMEA`·`APQP_CP`(PMS 표 — **이 세션이 PMS 도 맡으므로 남의 표 아님**) | 없음(이 세션 안) |
| A3-2 | **재검토 표시** | 확정 PFMEA·CP 에 "재검토 필요" 배지: ① 같은 품목의 라우팅 새 리비전이 확정됨(직접 읽기) ② 확정일 뒤 RELEASED 된 ECO 가 그 품목·라우팅·공정·CP 문서를 대상으로 함 ③ 확정일 뒤 승인된 4M 변경이 그 품목·특성을 건드림 | ① 없음 ② PLM 에 **조회 메서드 추가 요청** `EcoLookup.releasedFor(itemId, since)` ③ QMS 에 **읽기 계약 요청** `ChangeLookup.approvedFor(itemId, since)` | ② PLM 세션 ③ QMS 세션 |
| A3-3 | **QMS 문서 배포** | 확정 PFMEA·CP 를 `mst_doc`(유형 PFMEA·CP) 리비전으로 "배포 요청" — QMS 결재·배포는 QMS 가 | QMS 에 **쓰기 계약 요청** `DocPort.publish(docType, docNo, title, fileId) → docRevId`(문서 없으면 만들기, 결재 정책 따름) — 남의 표 직접 INSERT 안 함(8장 P6) | QMS 세션 + **A4 엑셀 양식**(배포할 파일) |
| A3-4 | **ECO 대상에 APQP** | ECO 대상으로 PFMEA·CP 리비전을 고름(설계 변경이 FMEA 를 바꿀 때) | PLM `plm_eco_target` 대상 유형 확장(남의 표 — PLM 세션) 또는 A3-3 뒤 문서(CP·PFMEA 문서 리비전)로 DOC 대상 사용 | PLM 세션 (A3-3 이 되면 DOC 대상으로 충분할 수 있음) |
| A3-5 | **조치 → PMS 이슈** | 열린 FMEA 조치를 프로젝트 이슈로(담당·기한 같이) | PMS 안(이 세션) — 이슈에 `src_type = APQP_ACTION` 같은 출처 칸 | 없음 |
| A3-6 | **PPAP 요소 근거** | PPAP 요소 6(공정 FMEA)·7(관리계획서)에 확정 리비전을 근거로 | PLM `plm_ppap_elem` 근거 칸(남의 표) 또는 PLM 이 `ApqpLookup` 을 읽음 | PLM 세션 |
| A3-7 | **결재선** | 확정 = 플랫폼 결재(상신 → 승인 → 확정), 회사 정책으로 켜고 끔(지금은 APPROVE 권한자 바로) | `ApprovalDocType` 구현 2개(APQP 안) + 정책 키 | 없음(플랫폼 계약 이미 있음) |

### 11.3 결정 (2026-10-23 사용자 — 모두 추천안)

| # | 정할 것 | 추천 | 버린 안 |
|---|---|---|---|
| L1 | A3 1차 범위 | **A3-1(PMS 산출물) + A3-2 ①(라우팅 새 리비전 재검토 표시) + A3-7(결재선, 정책으로)** — 이 세션 안에서 끝나는 것만. ②③은 계약 요청만 보내고 받으면 2차 | 전부 한 번에 — QMS·PLM 세션 기다림이 섞여 PR 이 오래 열린다 |
| L2 | 산출물이 가리키는 것 | **리비전 id**(QMS_DOC_REV 와 같은 방식), 승인 = 그 리비전이 RELEASED 또는 OBSOLETE(한 번 확정됨) | 번호를 가리켜 "현행 확정본이 있으면 승인" — 산출물 승인의 근거 리비전이 몰래 바뀐다 |
| L3 | QMS 문서 배포(A3-3) 시점 | **A4(엑셀 양식) 뒤** — 파일 없는 문서 리비전은 배포 의미가 없다. 지금은 QMS 에 계약 모양만 미리 합의 | A3 에서 파일 없이 메타만 배포 — QMS 문서 화면에 빈 리비전이 쌓인다 |
| L4 | ECO 대상(A3-4) | **A3-3 뒤 DOC 대상(CP·PFMEA 문서 리비전)으로 충분한지 먼저 본다** — PLM 표에 새 유형을 늘리지 않는 쪽 | 지금 `plm_eco_target` 에 APQP_FMEA·APQP_CP 유형 추가 — 남의 표·트리거 변경 |
| L5 | 재검토 표시가 막는가 | **표시만**(배지 + 목록 칸), 새 리비전에서 풀린다 — C2 와 같은 판단 | 재검토 필요면 산출물 승인·PPAP 근거를 막음 — 현장 일정이 표시 하나로 멈춘다 |
| L6 | 결재선(A3-7) 기본값 | **정책 기본 끔**(지금처럼 APPROVE 권한자 확정), 켜면 결재 승인 = 확정 | 기본 켬 — 1인·소규모 회사가 혼자 상신·승인해야 한다 |
| L7 | 조치 → 이슈(A3-5) | **2차(선택)** — 조치 화면에서 "이슈로" 단추, 이슈 완료가 조치를 닫지는 않음(양쪽 상태를 따로) | 상태 동기화 — 두 곳에서 같은 일을 고쳐 어긋난다 |

### 11.4 시험 계획 (A3 1차)

정상: 산출물에 PFMEA·CP 리비전 연결 → 확정 전 제출 중 → 확정 뒤 [QMS 상태 반영]·게이트 상신 직전에 승인, 폐기된 리비전도 승인 유지, 라우팅 새 리비전 확정 → 재검토 배지·새 리비전에서 사라짐, 결재 정책 켬 → 상신·승인 = 확정·반려 = 작성 중.
이상 사용: 다른 프로젝트·다른 회사 리비전 연결(**A사로 B사**), 작성 중 리비전 연결은 되지만 승인 안 됨, 결재 중 수정·확정 API 직접 호출(결재 켬), APQP 없는 설치본(`ApqpLookup.NONE`)에서 산출물 화면.

### 11.5 A3 1차 구현 결과 (2026-10-23, V158·임시 V9410)

| 영역 | 한 것 |
|---|---|
| A3-1 PMS 산출물 | 계약 `module.apqp.link.ApqpLookup`(rev(kind, id)·revsByNo(kind, no) — 예외 없음·`NONE`, jOOQ 구현). PMS `ref_type` 에 `APQP_FMEA`·`APQP_CP`(CHECK·같은 회사 트리거), `POST /api/pms/deliverables/{id}/apqp-link`(담당·PM + 그 APQP 화면 SEARCH), 리비전 비우면 확정본 → 작성·결재 중, 폐기본 지정·같은 리비전 거부. 승인 = 한 번이라도 확정(L2) — 연결 때 바로 또는 `DeliverableDocSync`([QMS·PPAP·APQP 상태 반영]·게이트 상신 직전). 이력 LINK 행에 `fmea_id`·`cp_id`(**FK 없음** — 작성 중 리비전을 지울 수 있게, `ref_id` 와 같은 방식). 화면: 산출물 상세에 "PFMEA·관리계획서 연결"(종류·번호·리비전) |
| A3-2 ① 재검토 표시 | 계산 칸 `routingStale` = 같은 품목·공장·대안에 더 늦은 시작일의 확정 라우팅 리비전이 있음(PFMEA·관리계획서 목록·상세). 표시만(L5) — PFMEA 목록은 라우팅 칸 경고 배지, 상세는 안내 줄. 새 리비전에서 라우팅을 바꾸는 "단계 다시 잇기" 는 11.9(2026-10-24) |
| A3-7 결재 | 정책 `APQP_RELEASE_APPROVAL`(기본 N, L6). Y 면 바로 확정 거부·상신(`/submit`, SAVE 권한) → 상태 `IN_APPROVAL`(새 상태 코드, 상태 칸 varchar(12)) — 트리거가 내용 고정(머리·하위 행), 승인 = 확정(이전 확정본 폐기), 반려·회수 = 작성 중. 결재 문서 유형 `APQP_FMEA`·`APQP_CP`(저장소만 쓰는 빈 — 순환 방지). "같은 번호 작성 중 하나" 에 결재 중 포함(부분 유일 색인·확정본 조치 진행 판정). 관리계획서는 상신 때 규격 스냅샷(결재 중엔 행을 못 고치므로). 상신 전 확정 조건 검사가 먼저(미달이면 결재 요청이 생기지 않음). 화면: 결재선(결재·합의·참조) 고르기 + 상신, 결재 중 안내 — 승인·반려는 공통 결재함 |
| 하지 않은 것 | A3-2 ②③(PLM `EcoLookup.releasedFor`·QMS `ChangeLookup.approvedFor` — 상위가 요청 전달), A3-3 QMS 문서 배포(A4 뒤, L3), A3-4 ECO 대상(L4), A3-5 조치 → 이슈(L7, 2차), A3-6 PPAP 요소 근거 |

### 11.6 A3 2차 — 재검토 표시 ②③ (2026-10-23, 마이그레이션 없음)

계약 두 개를 각자 원본으로 쓴다(B안): "그 품목에 어떤 ECO 가 적용·승인됐나" = PLM `EcoLookup`, "그 ECO 의 4M 세부" = QMS `ChangeLookup`. APQP 는 합치기만 한다(`ApqpChangeWatch`).

| # | 정한 것 | 이유 | 버린 안 |
|---|---|---|---|
| M1 | **현행 확정본(RELEASED)만** 본다. since = 그 리비전의 확정 시각을 회사 날짜로(`BizClock`), 포함 | 작성 중·결재 중은 아직 내용을 고치는 중이라 "재검토" 가 의미 없고, 폐기본은 새 리비전이 이어받는다 | 작성 중에도 직전 확정일 기준으로 표시 — 고치는 중에 안내가 남아 혼란 |
| M2 | **상세 한 건에만** 부른다(목록 칸 없음) | 목록은 행마다 모듈 간 조회 2~3번 — 페이지 50행이면 100회 이상. 목록의 재검토 배지는 같은 모듈 안 계산인 ①(라우팅)만 | 목록에서 품목 묶음 조회 — PLM 계약에 품목 여러 개 메서드가 없음(필요해지면 PLM 에 요청) |
| M3 | ② = `releasedFor` 의 **모든 변경 종류**(DESIGN·PROCESS_4M·TEMP), ③ = `approvedFor` 중 **PROCESS_4M 만**(적용 전 승인도) | 적용된 설계 변경은 고장·특성이 바뀔 수 있고, 4M 은 적용 전에 공정 FMEA·관리계획서를 미리 고치는 것이 IATF 8.5.6.1 의 취지(변경 전 검증). 설계 변경은 승인만으로는 아직 계획 | 승인된 설계 변경도 표시 — 적용 안 될 수도 있는 계획이 안내에 섞임 |
| M4 | ECO 하나에 **한 줄**(②③ 겹치면 한 번), 순서 = 적용일(없으면 승인일) → eco_id. 4M 세부는 4M 유형 이름만(전후 내용은 QMS·PLM 화면) | 안내는 "무엇을 볼지" 만 — 세부 화면을 복제하지 않는다 | 영향 특성(charIds)과 문서 특성 겹침 표시 — 2차 범위 밖(필요하면 A3 3차) |
| M5 | **같은 날은 포함**(날짜만 비교 — 계약이 날짜를 준다) | 같은 날 확정 전에 적용된 ECO 도 보일 수 있지만, 놓치는 것보다 한 번 더 보는 편이 안전 | 시각 비교 — PLM 계약에 시각이 없고, 늘리려면 PLM 계약 변경 |
| M6 | 표시만(L5) — 상태·편집·조치·산출물 승인에 영향 없음, PLM 상한(500)에서 잘리면 "앞쪽 500건만" | C2·L5 와 같은 판단 | — |
| M7 | ECO 번호·제목·종류·날짜를 **APQP 화면 권한만으로** 보여 준다 | PMS 프로젝트 상세(PM7)가 ECO 번호·상태를 보여 주는 것과 같은 수준 — ECO 내용(대상·사유·전후)은 PLM·QMS 화면 권한 | PLM `plm-eco` SEARCH 가 있을 때만 — APQP 담당이 PLM 권한이 없으면 재검토 이유를 모른다 |

| 영역 | 한 것 |
|---|---|
| 서버 | `ApqpChangeWatch.since(status, itemId, releasedAt)` → `ChangeNotes(rows, truncated)`, 행 `ChangeNote(ecoId, ecoNo, title, changeKind, status, approvedOn, releasedOn, m4Types)`. `FmeaDetail`·`CpDetail` 끝에 `changes`. 두 계약 모두 `ObjectProvider` + `NONE`(PLM·QMS 없는 설치본 = 빈 값), 예외 없음 |
| 화면 | PFMEA·관리계획서 상세 라우팅 안내 아래 `#changeNotice`: "확정 뒤 ECO·4M 변경 n건" + ECO 한 줄씩(번호 · 제목 · 종류 · 적용/승인일 · 4M 유형). 이름은 공통코드 `PLM_CHANGE_KIND`·`QMS_4M_TYPE` |
| 하지 않은 것 | 목록 배지(M2), 영향 특성 겹침(M4), A3-3 QMS 문서 배포(A4 뒤), A3-5 조치 → 이슈, 새 리비전에서 라우팅 바꿀 때 단계 다시 잇기 |

#### 11.6.1 M4 특성 겹침 · M2 목록 칸 · E-3 자기 배포 ECO (2026-10-24, 마이그레이션 없음 — 상위 지시 "3 → 1·2", 11.10 E-3 채택)

| # | 정한 것 | 이유 | 버린 안 |
|---|---|---|---|
| M4-1 | 4M 세부(`ChangeDetail.charIds`)의 영향 특성을 이 문서 특성과 **같은 id** 로 비교. 문서 특성 = PFMEA 기능에 붙은 특성 / 관리계획서 행 특성. 결과는 문서 특성 순서, 같은 id 한 번 | 특성 원본은 마스터 하나(H6) — 번호·이름 비교는 틀린다 | 4M 이 아닌 ECO(설계 변경)도 비교 — 세부가 없어 계약에 특성이 없다 |
| M4-2 | 세 상태: **세부 없음 = 표시 안 함**(null) / **겹침 없음 = "특성 겹침 없음(다른 영향은 상세에서 확인)"** / 겹침 = "겹치는 특성 번호 이름(특별특성)" | 상위 지시 — "겹침 없음" 이 "검토 안 해도 됨" 으로 읽히지 않게. 4M 의 영향은 특성 말고도(전후 내용·검사계획) 있다 | 겹침 없는 ECO 를 숨기거나 흐리게 — 놓치는 쪽으로 틀린다 |
| M4-3 | 특별특성 표시는 **마스터 원본**(`qms_char.special_cls`, 관리계획서는 마스터가 없으면 행 스냅샷) | 확정 뒤 마스터가 특별특성으로 바뀌었으면 지금 그 사실을 보여 주는 쪽이 재검토 목적에 맞다 | 확정 때 스냅샷 — 이미 지난 판단 |
| M2-1 | 목록 "재검토" 칸 = **같은 모듈 안 계산만**: PFMEA = ROUTING(①), 관리계획서 = ROUTING·FMEA(근거 PFMEA 새 리비전, C2). 폐기본은 빈 칸. **정렬 키 아님**(정렬·offset 가드 무관) | 이미 목록 SQL 이 계산하던 값(`routing_stale`·`fmea_stale`) — 새 조회·색인 없음. 색으로만 보이던 라우팅 칸 경고를 글자로(색 의존 줄임) | ECO·4M(②③)까지 목록에 — 행마다 모듈 간 조회(M2 원래 이유 그대로) |
| E-3 | 이 리비전의 `apqp_qms_doc.doc_rev_id` 가 `EcoView.docRevIds` 에 있는 ECO 는 재검토(rows)에서 빼고 `own`("이 리비전을 배포한 ECO: 번호")으로 한 줄. 같은 품목의 **다른 문서**에는 그대로 재검토 | 자기가 배포한 변경을 다시 보라고 하는 것은 조용히 틀린 안내(11.10 E-3). 다른 문서는 그 ECO 가 담지 않았으므로 봐야 한다 | 품목 기준 목록에서 DOC 대상 ECO 를 모두 뺌 — 다른 문서의 재검토를 놓친다 |

| 영역 | 한 것 |
|---|---|
| 서버 | `ApqpChangeWatch.since(kind, id, status, itemId, releasedAt, chars)` → `ChangeNotes(rows, truncated, own)`, `ChangeNote.overlap`(`CharRef` 목록 또는 null), `CharRef(charId, charNo, charNm, specialCls, special)`. `FmeaRow`·`CpRow.getReview()`(목록 칸, 저장 안 함), 열 `review`(정렬 없음) |
| 화면 | 상세 ECO 줄 끝에 겹침 글자, `#ownEcoNotice`("이 리비전을 배포한 ECO: … (재검토 목록에서 뺐습니다)"), 목록 "재검토" 칸(경고 배지, 글자 = 라우팅 새 리비전 / 근거 PFMEA 새 리비전) |
| 하지 않은 것 | 겹침 순서로 다시 정렬(날짜 순 그대로), 검사계획(`inspPlanIds`) 겹침, 목록 칸의 ECO·4M |

**목록 엑셀(2026-10-24, V188·임시 V9414 — 상위 지시 "안 A")**: 상위 지적 — 재검토 칸이 코드값이라 목록 엑셀을 붙이면 코드가 나간다(그때는 엑셀 메뉴 자체가 없어 실제 노출은 없었음).

| # | 정한 것 | 이유 | 버린 안 |
|---|---|---|---|
| X-1 | PFMEA·관리계획서 목록에 **엑셀 내보내기**(hb.grid `exportUrl`, `POST …/export`, 화면 동작 **EXCEL** — V188·임시 V9414, 기존 권한자 자동 부여 없음) | 다른 목록과 같은 공통 `ExcelExportService`(상한·동시 제한·접속 로그·코드명 변환) | 양식 엑셀(PRINT, 12장)에 목록 시트 추가 — 반출 성격이 다름 |
| X-2 | 재검토 칸 = **플랫폼 LABEL 열**(`ColumnSpec.labels("review", …, "apqp.review.", …)`, docs/36 18.9 X2 — 2026-10-26 옮김). 열 정의가 값→문구 표를 보는 사람 언어로 내려 주고(화면 `hb.fmt.label`), 엑셀도 같은 표로 쓴다. 쉼표 값은 나눠 바꾸고 ", " 로. 화면 JS 는 배지 색만 | 화면과 엑셀이 한 원본(열 정의)을 쓴다. 처음엔 서버 `reviewNm` 칸(ⓑ, #545)으로 했다가 X2 병합 뒤 옮김 — 행마다 글자를 싣지 않아도 됨 | 화면 JS format + 엑셀 별도 변환(처음 안), 공통코드 그룹(안 B) |
| X-3 | 조건·정렬은 grid 와 같은 검사(정렬 허용 목록 — 재검토는 정렬 키 아님 → 400, 모르는 열 → 400) | 같은 SQL 조건을 grid·엑셀이 함께 씀(`filter` 하나) | — |

코드 → 글자 표준(docs/36 18.9 X2)으로 옮김(2026-10-26) — `reviewNm` 칸·`ReviewText` 는 지움.


#### 11.6.2 M4 확장 — 4M 영향 검사계획 겹침 (2026-10-26, 마이그레이션 없음 — B안 #584 → A안 전환)

사실: 4M 영향 검사계획(`ChangeDetail.inspPlanIds`)은 **검사계획 리비전 id** 이고, 4M 화면은 작성 중·확정 리비전을 모두 고를 수 있다(QMS `ChangeMapper#planChoices`). 관리계획서 검사계획 대조(C5, `InspPlanLookup.lines`)가 아는 것은 **그 날 유효한 확정본 id** 뿐 — 같은 계획의 다른 리비전을 같은 것으로 볼 키(검사 유형·품목/클래스·공정·대체 번호)가 계약에 없다. PFMEA 에는 검사계획 연결이 없다.

| # | 정한 것 | 이유 | 버린 안 |
|---|---|---|---|
| P-1 | **관리계획서만**. `ChangeNote.planOverlap = PlanOverlap(hits, unknown, unrelated)` — 4M 에 검사계획이 없으면 null(표시 없음). PFMEA 는 늘 null | 검사계획은 관리계획서 행(C5)과만 이어진다 | — |
| P-2 | **B안(지금)**: 대조(C5)가 본 확정본과 **리비전 id 가 같을 때만** hits. 다른 id 는 전부 **unknown("검사계획 비교 불가 n건 — 같은 계획의 다른 리비전일 수 있음")**, unrelated 는 0. 그래서 "검사계획 겹침 없음" 은 B 에서 나오지 않는다 | 흔한 경우(4M 이 작성 중 새 리비전을 가리킴)를 "겹침 없음" 으로 조용히 틀리게 말하지 않는다(상위 지시) | id 만 비교하고 나머지를 "겹침 없음" — 틀린 안내 |
| P-3 | **A안(QMS `InspPlanLookup.keys(ids)` 병합 뒤)**: 같은 키 = hits(리비전이 다르면 그 rev 표시), 다른 키 = unrelated. hits·unknown 이 없고 unrelated 만 있으면 "검사계획 겹침 없음(다른 영향은 상세에서 확인)" | 결과 구조(hits·unknown·unrelated)를 지금 정해 두어 A 는 계산만 바꾼다. 시험도 같은 틀(ECO 별 한 줄 기대값 — A 에서 바뀌는 줄은 주석) | — |
| P-4 | 대조가 본 검사계획 = `CompareResult.plans`(새 칸, `PlanRef(inspPlanId, rev, inspType, procCd)`) — 대조가 이미 묻는 것을 모은 것이라 추가 조회 없음 | 같은 원본을 두 번 묻지 않는다 | — |

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-26 | 상위 | A 로 정함 — QMS 에 `keys(ids)` 요청, 기다리는 동안 B 로 올림, 같은 시험 틀 |
| 2026-10-26 | PMS | **A안 전환**(QMS `InspPlanLookup.keys` #588 뒤) — 4M 영향 계획·대조가 본 계획의 키를 한 번에 읽어, 같은 id 또는 같은 키 = hits(다른 리비전이면 4M 이 가리킨 그 rev), 다른 키 = unrelated, 키를 못 읽음(QMS 없음·없는 id) = unknown. hits·unknown 없이 unrelated 만이면 "검사계획 겹침 없음(다른 영향은 상세에서 확인)" |

### 11.7 A3-5 — PFMEA 조치 → PMS 이슈 (2026-10-24, V171·임시 V9412)

L7 범위 그대로(단추로 한 번 복사, 상태 동기화 없음). 2026-10-24 상위 확인: 조치당 이슈 하나는 `(comp_cd, apqp_action_id)` 부분 유일 색인, 만들 때 조치의 FMEA·요청 프로젝트가 같은 회사·접근 가능한지 서버가 확인.

| 영역 | 한 것 |
|---|---|
| 방향 | **PMS 가 만든다** — `POST /api/pms/issues/from-apqp/{actionId}`(pms-issue SAVE + apqp-fmea SEARCH). APQP 읽기 계약에 `ApqpLookup.action(id)`(조치·FMEA 번호·rev·상태·프로젝트·품목·FM·FC·AP, 예외 없음·APQP 없으면 빈 값). APQP 서버는 PMS 를 모른다(모듈 순환 없음) — FMEA 화면이 PMS API `GET /api/pms/issues/by-apqp-actions?ids=` 로 연결 이슈를 읽어 표시 |
| 표 | V171·임시 V9412(PMS 표, 이 세션 소유): `pms_issue.source` 에 APQP, `apqp_action_id`(FK 없음 — 작성 중 FMEA 삭제를 막지 않게, 이력과 같은 방식), `source = APQP ⇔ apqp_action_id 있음`, 부분 유일 색인, 트리거 = 같은 회사 조치만·한 번 정하면 고정 |
| 복사 | 제목 `[번호 rev n] 조치 글`(200자), 내용 `PFMEA 번호 rev / FM / FC / AP / 구분: 조치`, 심각도 AP H·M·L → HIGH·MID·LOW(없으면 MID), 담당·기한, 대상 = 품목(ITEM). 그 뒤 따로 — 이슈를 해결·종료해도 조치는 그대로(L7) |
| 규칙 | 열린(OPEN) 조치만(`PMS_APQP_ACTION_CLOSED`), 조치당 하나(409 `PMS_APQP_ISSUE_EXISTS` + 기존 번호), 프로젝트 = FMEA 의 프로젝트 — 없으면 요청 projId 필수, 있는데 다른 projId 면 `PMS_APQP_PROJ_MISMATCH`, 프로젝트는 같은 회사·열린 것(`lockOpen` — 다른 회사 = 없음) |
| 화면 | PFMEA 조치 상세 아래: 연결 이슈 "PMS 이슈 번호 (상태) · 프로젝트", 없으면 [이슈로](확인 대화) — PMS 이슈 SAVE·열린 조치·FMEA 프로젝트 있을 때. 프로젝트가 없으면 안내만(API 는 projId 로 가능) |
| 한계 | 새 리비전은 조치가 새 id 로 복사되므로 연결 표시는 **원래 리비전의 조치에만** 보인다(이슈는 그대로). 리비전을 넘는 조치 계보는 필요해지면 따로 |

### 11.8 A3-3 — 확정 PFMEA·관리계획서 → QMS 관리문서 (설계, 2026-10-24 — **B1~B7 추천안 승인**(상위, L3 범위), 구현 = 11.8.5, V176·임시 V9413)

전제: A4 양식 엑셀(12장, `ApqpFormService` — 결과 = 바이트), 공용 `FileService.attachAs`(#460 — 업무 트랜잭션 안·권한 검사 없음·확장자 허용 목록·시그니처·크기·SHA-256 같은 검사), QMS `DocPort` 모양 합의(docs/29_P3 20.2 — **DRAFT 까지**, 결재·확정은 QMS 쪽 사람, Q-t).

#### 11.8.1 사실 (코드 확인)

| # | 사실 | 위치 | 뜻 |
|---|---|---|---|
| G1 | `mst_doc.doc_type` 에 PFMEA·CP 있음, 문서 대상 `mst_doc_target`(ITEM·ITEM_CLASS·PROC) | V078 | 새 문서는 유형 PFMEA·CP + 대상 = FMEA 품목 |
| G2 | 리비전 첨부 = 대상 `qms-doc` × `doc_rev_id`, 작성 중 리비전에만 올리고 지움(FileLockRule), 배포 때 잠금 | V078 | QMS 가 자기 대상으로 붙이는 편이 규칙과 맞음 |
| G3 | `attachAs(targetType, targetId, name, size, InputStream, actor)` — MANDATORY·권한 검사 없음(부르는 계약이 확인) | platform/file (#460) | 서버가 만든 바이트를 그대로 붙일 수 있음. xlsx 는 회사 허용 목록·시그니처를 통과해야 함 |
| G4 | DP-5(합의 초안) = "플랫폼 파일 id 를 받아 옮겨 붙임" | docs/29_P3 20.2 | 파일을 먼저 어디에(대상?) 올려야 하는지가 비어 있음 — 아래 B2 로 바꾸자 제안 |

#### 11.8.2 결정 (2026-10-24 상위 승인 — 추천안)

| # | 정할 것 | 추천 | 대안(안 하는 이유) |
|---|---|---|---|
| B1 | 언제 | **단추**(확정 PFMEA·CP 상세 [QMS 문서로 올리기]) — 확정과 따로 | 확정 때 자동 — 결재(A3-7)·QMS 쪽 사람 일정과 엮여 QMS 화면에 원치 않는 작성 중 리비전이 쌓임 |
| B2 | 파일 넘기기 | **DocPort 가 바이트를 받는다**: `DocDraft(…, DocFile(fileName, byte[] content))` → QMS 가 자기 리비전 대상(`qms-doc` × docRevId)으로 `attachAs`. (QMS 계약 DP-5 변경 요청) | 파일 id 를 받아 옮기기(DP-5 원안) — APQP 가 임시 대상에 먼저 올리고 QMS 가 대상만 바꾸는 "옮기기" 기능이 플랫폼에 없음(새 공통 코드) |
| B3 | 어떤 문서에 | 같은 PFMEA·CP **번호**의 이전 리비전이 올린 문서가 있으면 **그 문서의 새 리비전**, 없으면 **새 문서**(유형 PFMEA·CP, 이름 = 제목, 대상 ITEM = FMEA 품목) | 리비전마다 새 문서 — QMS 문서 목록이 리비전 수만큼 늘어남 |
| B4 | 기록 | ~~`apqp_fmea`·`apqp_cp` 에 `doc_rev_id`~~ **2026-10-24 변경(상위 승인): 칸 → 새 표 `apqp_qms_doc`(넣기만)** — 확정본 행은 가드 트리거가 칸 변경을 막고 올리기는 확정 뒤라, 가드 함수를 다시 쓰지 않으려고(11.8.5). QMS id 는 FK 없음(모듈 간 id 는 이력처럼). 상태 표시는 `DocLookup.rev(docRevId)`(배포됐는지) | APQP 가 QMS 표를 읽기 — 금지(계약으로만) |
| B5 | 멱등 | `sourceKey = APQP:FMEA:{fmeaId}`·`APQP:CP:{cpId}`(DP-3) — 두 번 눌러도 같은 리비전. 이미 올렸으면 단추 대신 "QMS 문서 번호 rev n (상태)" | — |
| B6 | 권한 | APQP 화면 **PRINT**(반출과 같은 성격 — X4) + QMS 문서 화면 SAVE(작성 중 리비전을 만드는 것이므로) | APQP 권한만 — QMS 문서를 만들 권한 없는 사람이 QMS 에 행을 만듦 |
| B7 | 무엇을 올리나 | **현행 확정본(RELEASED)만** — 작성 중·결재 중·폐기는 거부 | 작성 중도 — QMS 문서에 미확정 내용이 배포 후보로 감 |

#### 11.8.3 QMS 에 맞출 것 (2026-10-24 상위가 QMS 에 요청 — DP-5 → DocFile(byte[]) + attachAs, DocDraft.itemId)

1. DP-5 → B2: `DocDraft` 에 `DocFile(fileName, byte[])`(하나), QMS 가 `attachAs(qms-doc, docRevId, …)`. 크기·확장자·시그니처 검사는 attachAs 가 그대로.
2. `DocDraft` 에 대상 품목(`itemId`, 새 문서일 때 `mst_doc_target` ITEM 한 줄) — 없으면 대상 없이.
3. 기존 문서에 새 리비전을 만들 때 그 문서에 **작성 중 리비전이 이미 있으면** 거부(DP-4) — APQP 화면은 그 오류를 "QMS 에 작성 중 리비전이 있습니다" 로 보여 준다.
4. 반환 `DocRevRef(docId, docNo, docRevId, rev, status)` 그대로.

#### 11.8.4 시험 계획
정상: 확정 PFMEA → 올리기 → QMS 새 문서(유형 PFMEA·대상 품목)·작성 중 리비전·첨부 xlsx 1개, APQP 행에 doc_rev_id, 다시 눌러도 같은 리비전(멱등). 새 PFMEA 리비전 확정 → 올리기 → 같은 QMS 문서의 다음 리비전. 관리계획서도 같은 흐름.
이상: 작성 중·폐기 리비전 거부, PRINT 없음·QMS 문서 SAVE 없음 403, QMS 에 작성 중 리비전이 이미 있음 → 거부(행 변화 없음), 첨부 실패(확장자 허용 목록에 xlsx 없음) → 전체 롤백(QMS 행·APQP doc_rev_id 없음), **A사로 B사**: B사 사용자로 A사 FMEA id → 404, QMS 문서·파일이 B사 범위에 안 보임.


#### 11.8.5 구현 결과 (2026-10-24, V176·임시 V9413 — QMS DocPort #474 위)

| 영역 | 한 것 |
|---|---|
| **B4 바꿈** | `apqp_fmea`·`apqp_cp` 에 칸을 더하지 않고 **새 표 `apqp_qms_doc`**(넣기만 — 원본 종류·id·번호·리비전 + QMS doc_id·doc_no·doc_rev_id, `(comp_cd, src_kind, src_id)` 유일). 이유: 확정본 행은 가드 트리거가 상태·row_ver 밖의 칸 변경을 막는데, 올리기는 확정 **뒤**에 일어나므로 칸으로 두면 가드 함수 두 개를 다시 써야 한다. 표는 RLS·감사·보존 클래스·REVOKE TRUNCATE, 트리거 = 고치기·지우기 거부 + 원본은 같은 회사·같은 번호·리비전의 **확정본**만(B7 이중 확인) |
| 서버 | `ApqpDocService`: QMS 문서 SAVE 확인(B6 — 계약이 부르는 쪽 확인을 요구) → APQP 행 잠금(잠금 순서 APQP → QMS, DP-6) → 이미 올렸으면 그대로 돌려줌 → 확정본 확인(아니면 409 `APQP_FMEA_STATE`·`APQP_CP_STATE`) → A4 양식 바이트(`ApqpFormService`) → 같은 번호의 이전 연결 문서 번호(B3) → `DocPort.publish(DocDraft("APQP:{종류}:{id}", docNo, PFMEA·CP, 제목, 품목, "번호 rev n", [양식 xlsx]))` → `apqp_qms_doc` 한 줄. QMS 가 거부하면(`QMS_DOC_OPEN_EXISTS` 등) 함께 롤백 — 첨부 실제 파일도 attachAs 가 지움 |
| API | `GET /api/apqp/fmeas|cps/{id}/qms-doc`(SEARCH) → 연결(QMS 문서 번호·rev·상태 — `DocLookup.rev`)·canPublish(권한·확정본·아직 안 올림), `POST` 같은 경로(PRINT + 서비스에서 QMS 문서 SAVE) |
| 화면 | PFMEA·관리계획서 상세 도구 줄: [QMS 문서로 올리기](canPublish, 확인 대화) 또는 "QMS 문서 번호 rev n (상태)" |
| 한계 | 같은 번호의 이전 리비전을 올린 QMS 문서에 작성 중·결재 중 리비전이 남아 있으면 올릴 수 없다(QMS 규칙) — QMS 에서 배포·폐기한 뒤 다시 |


### 11.9 새 리비전에서 라우팅 바꾸기 — 단계 다시 잇기 (2026-10-24, 마이그레이션 없음)

빈틈(11.5 A3-2 ① 의 "하지 않은 것"): 라우팅 새 리비전이 확정돼 재검토 표시가 떠도, 작성 중 PFMEA 는 단계를 가리키는 노드가 있으면 라우팅을 못 바꾸고(`ck_apqp_fmea_routing_used`), 관리계획서는 근거 PFMEA 와 라우팅이 같아야 해서(`ck_apqp_cp_fmea_routing`) 새 근거로 옮길 수 없었다. 2026-10-24 상위 승인(조건: 적용은 서버가 다시 계산·rowVer 409, 글자로 바뀐 것은 기록, 확정 라우팅만).

| 영역 | 한 것 |
|---|---|
| 짝 규칙 | `RoutingRelink.pair` — 옛 단계 → 새 단계는 **같은 op_seq + 같은 공정(proc_id)** 일 때만. 추측 짝(공정만·순서만 같음) 없음. 짝 없으면 글자 단계 "op 공정명" |
| PFMEA | `GET /api/apqp/fmeas/{id}/routing-relink?routingId=`(SEARCH, 미리보기 — 바꾸지 않음), `POST /api/apqp/fmeas/{id}/routing`(SAVE, routingId·rowVer). 작성 중만, **같은 품목의 확정 라우팅 리비전만**(작성 중 = `APQP_ROUTING_NOT_RELEASED`, 지금 것 = `APQP_ROUTING_SAME`, 다른 품목·회사 = NOT_FOUND). 적용은 **미리보기 결과를 받지 않고 서버가 다시 계산**, 머리 rowVer 가 다르면 409. 순서(트리거 그대로 통과): 단계 노드의 단계 비우기(짝 없는 노드는 이름을 글자 단계로) → 머리 라우팅 → 짝 있는 노드에 새 단계. 기능·고장·위험행·조치는 그대로 |
| 기록 | 별도 표 없이 **감사 기록**(`apqp_fmea_node`·`apqp_fmea` 의 hb_audit) — 글자로 바뀐 노드의 UPDATE 전 값에 옛 `step_id` 가 남아 사람이 다시 이을 수 있다(시험으로 확인) |
| 관리계획서 | 새 리비전·다음 단계에서 근거 PFMEA 를 라우팅이 다른 확정본으로 바꾸면 **새 관리계획서는 그 라우팅을 따른다**(이전에는 `APQP_CP_FMEA_ROUTING` 거부). 행 단계는 같은 짝 규칙으로 다시 잇고, 짝 없으면 `step_text` "op 공정명". 원본 리비전 행은 옛 단계 그대로 남는다(새 행이라). 관리계획서에 따로 단추는 없다(라우팅은 근거 PFMEA 를 따름) |
| 화면 | 작성 중 PFMEA 상세 "라우팅 바꾸기": 확정 라우팅(지금 것 제외) 고르기 → [미리보기](잇기 n · 글자 m, 단계별 줄) → [바꾸기](확인 대화, 서버 재계산) |
| 마이그레이션 | **없음** — 기존 트리거 순서로 처리 |

### 11.10 L4 검토 — ECO 대상(A3-4)은 DOC 대상으로 충분한가 (2026-10-24, 문서만 — main fe18225 코드로 확인)

질문(L4): 설계 변경이 PFMEA·관리계획서를 바꿀 때, ECO 가 그 APQP 리비전을 대상으로 가리키게 하려면 `plm_eco_target` 에 APQP 유형을 늘려야 하나, 아니면 A3-3 으로 생긴 QMS 문서 리비전을 **DOC 대상**으로 지정하면 되나.

#### 11.10.1 사실 (코드 확인)

| # | 사실 | 위치 | 뜻 |
|---|---|---|---|
| E1 | DOC 대상 = `doc_rev_id` → `mst_doc_rev`. **작성 중(DRAFT) 리비전만**, 같은 회사, 열린 ECO 하나(행 잠금으로 직렬화). 품목은 선택(화면 입력) | V082·V088 `hb_guard_plm_eco_target`, `EcoService` DOC 분기 | APQP 가 올린 QMS 리비전(DocPort = DRAFT 까지, 11.8 B2)을 그대로 지정할 수 있다 |
| E2 | DOC 대상 문서 유형 = **DRAWING·WI·CP·INSP_STD** — **PFMEA 없음**. 같은 목록이 두 곳: 트리거 함수(V088)와 `EcoService.DOC_TYPES` | V088 67행, `EcoService` 98행 | 관리계획서는 지금 바로 되고, **PFMEA 문서는 ECO 대상으로 고를 수 없다** |
| E3 | ECO 적용 → `DocService.releaseFromChange(docRevId)` — 문서 결재 없이 작성 중 리비전을 배포(결재 중이면 409) | `EcoService` 818행, `DocService` 406행 | ECO 결재가 QMS 문서 배포를 대신한다. APQP 리비전 확정(A3-7)은 그 앞에 따로 끝나 있다 |
| E4 | `apqp_qms_doc` = APQP 원본(종류·id·번호·리비전) ↔ QMS `doc_rev_id` 한 줄 | V176 | QMS 리비전에서 APQP 리비전을 거꾸로 찾을 수 있다(APQP 안에서) |
| E5 | `EcoView.docRevIds`·`itemIds` 가 계약에 있다. `releasedFor`·`approvedFor` 는 **대상 행의 품목**으로 고른다(DOC 대상도 품목을 적으면 포함) | `EcoLookup`, `PlmLinkMapper#ecoHeadsForItem` | 아래 E-3 의 원인 |

#### 11.10.2 흐름 (관리계획서 — 지금 코드로 되는 것)

APQP 새 리비전 작성 → 확정(APQP APPROVE 또는 결재선) → [QMS 문서로 올리기] → QMS 작성 중 리비전(xlsx 첨부) → PLM ECO 에 DOC 대상으로 그 문서 번호 → ECO 결재·적용 → QMS 리비전 배포.
APQP 표·PLM 표를 바꾸지 않고, 계약도 새로 늘지 않는다. "ECO 가 이 관리계획서 변경을 담는다" 는 **ECO → QMS 문서 리비전 → (`apqp_qms_doc`) → APQP 리비전** 으로 이어진다.

#### 11.10.3 빈틈과 처리

| # | 빈틈 | 처리(추천) | 누구 |
|---|---|---|---|
| E-1 | PFMEA 문서를 ECO 대상으로 못 고름(E2) | **PLM 에 요청**: DOC 대상 유형에 `PFMEA` 추가 — 트리거 함수 목록(새 V 로 `CREATE OR REPLACE`)과 `EcoService.DOC_TYPES` 두 곳, 거부 시험 1건. `plm_eco_target` 의 칸·CHECK·유형 코드(`PLM_TARGET_TYPE`)는 그대로 | PLM 세션(상위 경유) |
| E-2 | 순서: APQP 확정이 ECO 결재보다 먼저다(DocPort 는 확정본만 올림 — B7, DOC 대상은 작성 중 리비전만 — E1). ECO 가 취소되면 APQP 는 새 리비전이 현행인데 QMS 는 이전 리비전이 배포 상태로 남는다 | **표시로 충분**(L5 와 같은 판단): APQP 상세의 QMS 문서 칸이 이미 QMS 상태(작성 중)를 보여 준다(11.8 `DocView.status`). 막지 않는다. "APQP 확정을 ECO 결재에 묶기" 는 결재 두 벌이 되므로 하지 않는다 | — (기록만) |
| E-3 | 자기 표시: DOC 대상에 품목을 적으면, 그 ECO 가 적용된 뒤 **자기가 배포한 바로 그 관리계획서** 에 "재검토"(11.6 ②)로 뜬다 — 조용히 틀리는 쪽(현장이 이미 반영한 변경을 다시 보라고 함) | **APQP 안에서 고친다**: `ApqpChangeWatch` 에서 `EcoView.docRevIds` 가 이 APQP 리비전의 `apqp_qms_doc.doc_rev_id` 를 담은 ECO 는 재검토 목록에서 빼고, 대신 "이 리비전을 배포한 ECO" 로 한 줄 표시. 마이그레이션·계약 변경 없음 — M4·M2 PR 에 같이 넣는다 | PMS(이 세션) |
| E-4 | 거꾸로 찾기: APQP 리비전 → "이 리비전을 담은 ECO" 를 PLM 에 묻는 계약(`docRevId` 로 ECO 찾기)이 없다 | **지금은 만들지 않는다** — E-3 은 품목 기준 목록 안에서 `docRevIds` 로 충분하다. 품목을 비운 DOC 대상까지 찾아야 하면 그때 PLM 계약 요청 | — (필요해지면) |

#### 11.10.4 결론

**DOC 대상으로 충분하다 — `plm_eco_target` 에 APQP_FMEA·APQP_CP 유형을 늘리지 않는다(L4 추천 유지).** 남는 일은 두 개다. ① PLM 에 PFMEA 문서 유형 허용 요청(E-1, PLM 의 트리거 함수·상수 — 표 칸 변경 없음). ② APQP 자기 표시 제외(E-3, 이 세션, 다음 PR).
A3-4 를 "ECO 대상 유형 확장" 이 아니라 "**QMS 문서 리비전 경유**" 로 닫는다. A3-6(PPAP 요소 근거)은 이 검토와 따로다(PLM 이 `ApqpLookup` 을 읽는 쪽 — 11.2).

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-24 | PMS | 검토 — DOC 대상으로 충분, E-1 은 상위 경유 PLM 요청, E-3 은 M4·M2 PR 에 포함 |
| 2026-10-24 | 상위 | 결론·E-3 채택(#525 병합). E-1 은 상위가 PLM 에 전달. E-3 구현 = 11.6.1 |

### 11.11 A3-6 준비 — 품목의 현행 확정본 계약 `ApqpLookup.current` (2026-10-26, 마이그레이션 없음 — 상위 지시 순서 ②)

A3-6(11.2): PLM PPAP 요소 6(공정 FMEA)·7(관리계획서)의 근거를 APQP 확정본으로. 11.2 의 두 길 중 **PLM 이 `ApqpLookup` 을 읽는 쪽** — PLM 표(`plm_ppap_elem`)에 칸을 늘리는 일은 PLM 이 정한다. 이 절은 APQP 쪽 계약만.

| # | 정한 것 | 이유 |
|---|---|---|
| P1 | `List<CurrentView> current(long itemId)` — 그 품목의 **현행 확정(RELEASED)** PFMEA·관리계획서만, 번호마다 하나. 작성 중·결재 중·폐기 제외 | PPAP 근거는 "지금 유효한 확정본". 폐기본은 새 리비전이 이어받았다 |
| P2 | `CurrentView(RevView rev, String phase, Long docRevId)` — phase = 관리계획서 단계(PFMEA 는 null), docRevId = A3-3 으로 올린 QMS 관리문서 리비전(없으면 null) | 요소 7 은 보통 **양산 단계** 관리계획서 — 고르는 것은 PLM(단계 판단을 APQP 가 대신하지 않음). 문서 리비전은 PPAP 첨부(파일)와 이어 보기 위해 — 상태는 QMS `DocLookup` |
| P3 | 순서 = PFMEA(번호) → 관리계획서(단계 PROTO·PRE_LAUNCH·PRODUCTION → 번호). 읽기만·잠금 없음·예외 없음, 다른 회사·없는 품목 = 빈 목록(RLS). 기본 구현(APQP 없음) = 빈 목록 | 기존 계약(`rev`·`revsByNo`·`action`)과 같은 규칙 |
| P4 | 상한 없음 — 품목 하나의 현행 확정본은 번호 수만큼(보통 몇 건) | 목록 화면용이 아니라 PPAP 패키지 한 건을 열 때 부름 |

**PLM 요청 문안(상위 경유)**: "PPAP 패키지 화면에서 요소 6·7 을 채울 때 `ApqpLookup.of(provider).current(eco 대상 품목 id)` 를 불러 후보를 보여 주세요(요소 6 = kind FMEA, 요소 7 = kind CP — 단계는 사용자가 고르거나 PRODUCTION 우선). 근거로 남길 것은 PLM 이 정하되, 남긴다면 APQP 리비전 id 를 FK 없이(모듈 간 id 는 이력처럼) — 또는 `docRevId` 가 있으면 그 QMS 문서 리비전의 첨부를 ECO 첨부로 가져오는 길. APQP 확정본이 폐기돼도 PPAP 근거는 그대로 두고 `rev(kind, id)` 로 상태(OBSOLETE)를 표시하면 됩니다. 계약은 예외를 내지 않으며 APQP 가 없는 설치본은 빈 목록입니다."

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-26 | PMS | 계약 추가(P1~P4), PLM 요청 문안 — 상위 전달 대기 |

## 12. A4 — PFMEA·관리계획서 엑셀 양식 (2026-10-23 설계, 2026-10-24 **X1~X8 추천안 확정**·구현 = 12.5절)

목적: 확정 PFMEA·관리계획서를 **고객·심사에 내는 문서 한 벌**로 내려받는다. A3-3(QMS 문서 배포 `DocPort`)이 배포할 파일의 전제(L3).
지금: APQP 에는 엑셀이 없다(목록 엑셀도 없음). 서식 엑셀 선례 = SRM 발주서(`PoDocumentService` — XSSF 서식 고정, `PRINT` 권한, 발행 전 "초안", 수식 주입 방지 `ExcelExportService.safeText`, 서버 PDF 없음 — docs/31 D7).

### 12.1 사실 (코드 확인)

| # | 사실 | 위치 | 뜻 |
|---|---|---|---|
| F1 | 화면 동작 = SEARCH·SAVE·APPROVE 뿐(PRINT·EXCEL 없음) | V145·V153 `sys_prog.actions` | 출력 권한을 따로 두려면 `sys_prog` 갱신 = 마이그레이션(V168·임시 V9411) |
| F2 | 목록 엑셀 공통(`ExcelExportService.export`)은 **행 스트리밍 목록 전용**(열 = ColumnSpec) | platform/excel | 양식(머리 블록·여러 표·병합·인쇄 설정)은 공통 경로가 아님 — SRM 처럼 모듈 안 XSSF |
| F3 | `FileService.upload` 는 `MultipartFile` 만 받음 | platform/file | 서버가 만든 파일을 저장(A3-3)하려면 플랫폼에 "바이트로 저장" 이 필요 — A4 에서는 안 함 |
| F4 | 상세 자료(`FmeaDetail`·`CpDetail`)에 양식에 필요한 것 거의 다 있음(구조·기능·고장·사슬·위험·조치 / 행·특성 스냅샷·규격·특별특성) | A1·A2 | 양식 = 상세 자료 그대로 다시 배치(새 조회 최소) |

### 12.2 결정 (X1~X8) — 2026-10-24 사용자: **모두 추천안**

| # | 정할 것 | 추천 | 대안(안 하는 이유) |
|---|---|---|---|
| X1 | A4 1차 범위 | **PFMEA 양식 + 관리계획서 양식 엑셀**만. 공정 흐름도·DFMEA·패밀리 FMEA·인쇄 전용 화면은 뒤 | 흐름도까지 — 라우팅 그림은 그리기 규칙(기호·분기)을 따로 정해야 해 범위가 두 배 |
| X2 | 양식 배치 | **우리 배치**: 공개 요지에 나오는 항목 이름만 쓰고(AIAG·VDA 양식의 칸 순서·문구·색 복사 금지 — 02·29 D3 와 같은 판단). PFMEA = 머리(번호·rev·품목·라우팅·팀·확정일·AP 규칙표 이름) + 위험 표(단계·작업요소(4M)·기능·FE(S)·FM·FC·예방관리·O·검출관리·D·AP·특별특성) + 조치 표(위험행 번호·조치·담당·기한·상태·조치 후 S/O/D/AP). 관리계획서 = 머리(번호·rev·단계·품목·근거 PFMEA·팀·개정 사유) + 행 표(행 번호·공정 단계·특성(제품/공정)·특별특성·규격·평가 방법·계측기·샘플·주기·관리 방법·반응계획·근거 위험행 AP) | 핸드북 양식 복제 — 유료·저작권, 이직 포트폴리오에 남기면 안 됨 |
| X3 | 어떤 리비전·상태 | **id 로 그 리비전 그대로**(작성 중·결재 중·폐기도 가능), 확정본이 아니면 머리 첫 줄·파일명·시트 바닥글에 "확정본 아님(작성 중/결재 중/폐기)" 표시 | 확정본만 — 검토 회의에 작성 중 리비전을 들고 갈 수 없음 |
| X4 | 권한 | **PRINT 동작 추가**(V168·임시 V9411 로 `apqp-fmea`·`apqp-cp` actions 에 PRINT, 기존 권한자에게 자동 부여 없음 — 관리자가 줌) | SEARCH 로 — 화면 조회와 "고객에 낼 문서 반출" 을 한 권한으로 묶게 됨(SRM 발주서와 다름) |
| X5 | 구현 위치 | **apqp 모듈 안** `ApqpFormWriter`(XSSF, 순수 쓰기: 자료 → OutputStream) + 셀 글자는 `ExcelExportService.safeText`. 공통 "양식 도우미" 는 SRM·APQP 두 곳 패턴이 굳으면 뽑을 후보(지금은 공통 변경 없음) | 플랫폼에 양식 엔진 — 두 번째 사용처에서 바로 일반화하면 모양이 틀어지기 쉽다 |
| X6 | 행 상한 | 공통 정책 `EXCEL_EXPORT_MAX_ROWS` 를 그대로 상한으로(넘으면 `TOO_MANY_ROWS`) — FMEA 위험행·CP 행 수 | 상한 없음 — XSSF 는 메모리에 다 올림 |
| X7 | 셀 병합·인쇄 | **병합 안 함**(단계·기능 글자를 행마다 반복 — 엑셀 필터·정렬이 되게), 단계가 바뀌는 행에 굵은 위 테두리. 인쇄 설정: A3 가로·폭 1장 맞춤·머리 행 반복·바닥글 "번호 rev n · 출력 일시(회사 시간대) · 쪽" | 종이 양식처럼 병합 — 필터·정렬이 깨지고 행 추가 때 다시 병합해야 함 |
| X8 | 언어·코드 | 칸 제목 = 화면 언어(i18n 키), 코드 값 = 코드명(4M·특별특성·관리 방법·주기·상태), AP 셀은 H·M·L 글자 + 배경색(H 빨강·M 노랑·L 없음 — 우리 색) | 코드 값 그대로 — 고객 문서에 SPECIAL_CC 같은 코드가 나감 |

### 12.3 시험 계획
정상: 확정 PFMEA·CP 내려받기 → POI 로 다시 읽어 머리·표·행 수·AP·특별특성·코드명 확인, 작성 중 리비전 = "확정본 아님" 표시·파일명. 인쇄 설정(가로·반복 행·바닥글) 확인.
이상: PRINT 없는 사용자 403(SEARCH 만 있음), 수식 주입(`=cmd|...` 로 시작하는 기능·조치 글) → 앞에 `'`, 상한 초과 → TOO_MANY_ROWS, **A사로 B사** id → 404, 없는 id 404.

### 12.4 검토

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-23 | PMS | 초안 — X1~X8 추천, 상위가 사용자 결정 요청. 구현은 결정 뒤(V168·임시 V9411 = X4 PRINT 동작) |
| 2026-10-24 | 사용자(상위 전달) | **X1~X8 모두 추천안**(PRINT 동작 새로, V168·임시 V9411). 구현 = 12.5절 |

### 12.5 A4 구현 결과 (2026-10-24, V168·임시 V9411)

| 영역 | 한 것 |
|---|---|
| 권한 | V168·임시 V9411: `apqp-fmea`·`apqp-cp` actions 에 PRINT(자동 부여 없음). `POST /api/apqp/fmeas/{id}/excel`·`/api/apqp/cps/{id}/excel` = PRINT |
| 서버 | `ApqpFormService` — 상세 자료(`FmeaService.get`·`CpService.get`, 회사 범위·권한 그대로)를 XSSF 로 다시 배치, 결과 = 바이트(A3-3 에서 파일 저장에 그대로). 행 상한 = 공통 `EXCEL_EXPORT_MAX_ROWS`(PFMEA 는 위험행 + 조치 수) |
| PFMEA | 시트 2개 — **위험 분석**(제목·확정본 아님 줄·출력 일시 → 머리 칸(번호·rev·제목·품목·라우팅·프로젝트·팀·범위·도면 리비전·AP 규칙표·상태·확정일) → 표: 행·공정 단계·공정 기능(특성)·FE(S)·S·FM·작업요소(4M 이름)·FC·예방·O·검출·D·AP·특별특성), **조치**(위험행 번호·구분·조치·담당·기한·상태·완료일·근거·조치 후 O/D/AP). 순서 = 구조 트리 → FM → FC |
| 관리계획서 | 시트 1개 — 제목(단계 이름) → 머리(번호·rev·단계·제목·품목·라우팅·근거 PFMEA·프로젝트·팀·개정 사유·상태·확정일) → 표: 행·공정 단계·특성·특별특성·규격(작성 중 = 특성 마스터 값, 그 뒤 = 확정 스냅샷 — 화면과 같은 규칙)·관리 방법·평가 방법·계측기·샘플·주기·반응계획·근거 AP |
| 모양 | 병합 없음, 공정 단계가 바뀌는 행 굵은 위 테두리, AP H·M 칸 색(우리 색), A3 가로·폭 1장·머리 행 반복·틀 고정, 바닥글 = 번호 rev(확정본 아니면 상태) · 쪽/전체. 글자 칸 `safeText`(수식 주입 방지), 칸 제목 = 사용자 언어 |
| 확정본 아님 | 확정본이 아니면 제목 아래 "확정본 아님 — 상태", 바닥글, 파일명 `번호_rev n_상태.xlsx`(확정본은 `번호_rev n.xlsx`) |
| 화면 | PFMEA·관리계획서 상세 도구 줄에 [엑셀 양식](PRINT 있을 때) |
| 하지 않은 것 | 공정 흐름도·DFMEA·인쇄 전용 화면(X1), 파일 저장·QMS 배포(A3-3 — `FileService` 바이트 저장은 그때 플랫폼에 요청) |

## 13. A5 — 공정 흐름도(PFD)·DFMEA 설계 (2026-10-24 초안, 2026-10-29 **D1~D9 추천안 확정** — 13.5)

목적: APQP 산출물 사슬 **PFD → PFMEA → 관리계획서** 의 앞자리(PFD)와, 설계 쪽 위험 분석(DFMEA)을 지금 구조 위에 얹는다. 새로 만들기보다 **이미 있는 원본(라우팅·BOM·도면 리비전·특성 마스터)을 가리키는** 방향(1장 원칙 그대로).

**근거(공개 요지만)**: AIAG APQP 3판 공개 소개 — 공정 흐름도는 공정 설계 단계 산출물이고 PFMEA·관리계획서와 같은 공정 단계를 따라 이어진다. AIAG & VDA FMEA Handbook(2019) 공개 소개 — DFMEA 도 7단계 방법, 구조는 시스템 › 하위 시스템 › 구성품, 기능·고장 사슬·조치 우선순위(AP)는 PFMEA 와 같은 틀. IATF 16949 8.3.5.2(제조 공정 설계 출력 — 공정 흐름도·PFMEA·관리계획서), 8.3.3.3(특별특성) 공개 요지. **하지 않음**: 핸드북·AIAG 양식의 칸 배치·문구·색·기호·표 값(AP 표·심각도 표 등) 복사 — 02·29 D3·12장 X2 와 같은 판단(유료·저작권). 회사·고객사 자료 복사 없음.

### 13.1 사실 (코드 확인)

| # | 사실 | 위치 | 뜻 |
|---|---|---|---|
| H1 | 라우팅 = 리비전·확정·유효기간(`mst_routing`) + 단계(`mst_routing_step`: op_seq·공정·표준 CT·셋업·비고), 공정(`mst_proc`)에 **분류 `proc_cat`**(PRESS·MACHINE·WELD·ASSEMBLY·PAINT·HEAT·INSPECT·PACK·OTHER)·**외주 `outsrc_yn`** | V022 | PFD 의 "단계·순서·검사 지점·외주" 는 이미 라우팅에 있다. 분기·재작업 고리·이동·보관은 없다 |
| H2 | PFMEA 구조 노드 STEP = 라우팅 단계, 기능에 특성(`qms_char` — PRODUCT 는 품목, PROCESS 는 공정), 관리계획서 행 = 단계 × 특성 × 관리 방법 | V145·V153 | 단계별 "입력·출력 특성" 은 PFMEA 기능·CP 행에서 모을 수 있다 |
| H3 | `apqp_fmea.fmea_type` 칸이 이미 있고 CHECK 는 PFMEA 만, 노드 종류 ITEM·STEP·ELEMENT | V145 | DFMEA 는 같은 표에 종류를 넓히는 길이 열려 있다(이 세션 소유 표) |
| H4 | BOM = 리비전·확정(`mst_bom`) + 줄(`mst_bom_line`, 다단), 품목엔 리비전 없음(설계 변경 = BOM·라우팅·도면 리비전, docs/17 P1-3·39) | V015 | DFMEA 의 "설계 리비전" = 확정 BOM 리비전(구조) + 도면 리비전(문서) |
| H5 | 도면 리비전 = QMS 관리문서 `mst_doc_rev`(유형 DRAWING), PLM ECO 대상 DOC(docs/39 D8). PFMEA 의 `drawing_rev` 는 지금 **글자** | V082·V145 | DFMEA 는 글자 대신 도면 리비전 id 를 가리킬 수 있다(QMS `DocLookup` 으로 읽기) |
| H6 | 특별특성은 특성 마스터 `qms_char.special_cls` 하나가 원본(PFMEA 위험행·CP 행은 확정 때 스냅샷) | V024·V153 | DFMEA → PFMEA 특성 "이어받기" 는 복사가 아니라 **같은 특성 id** 를 쓰는 것으로 충분 |

### 13.2 결정 질문 (D1~D9) — 추천과 대안

| # | 정할 것 | 추천 | 대안(안 하는 이유) |
|---|---|---|---|
| D1 | PFD 를 저장하나 | **저장하지 않고 만들어 보여 준다**: 라우팅(확정 리비전) + 그 라우팅의 PFMEA·관리계획서로 화면·양식을 그때 만든다 — 원본 한 곳, 라우팅이 바뀌면 PFD 도 바로 따라감 | PFD 표 따로(단계·기호·분기·고리 편집) — 라우팅과 이중 관리, 어긋남을 또 잡아야 함 |
| D2 | PFD 에 담을 것 | 단계마다: op·공정명·**우리 기호 4종**(가공 / 검사(proc_cat INSPECT) / 외주(outsrc_yn) / 포장(PACK) — 기호 모양은 우리 것, 표준 기호 그림 복사 아님), 표준 CT, 그 단계의 **제품·공정 특성**(PFMEA 기능 특성 + CP 행 특성, 특별특성 배지), 관리 방법 수(CP 행), PFMEA 위험행 수·AP 높음 수 | 이동·보관·분기·재작업 고리까지 — 라우팅에 없는 정보라 따로 입력이 필요(D1 대안과 같은 문제). 필요해지면 2차에 "단계 메모" 만 |
| D3 | PFD 출력 | **화면**(세로 흐름 목록 — 그림 라이브러리 없이 CSS, 390 폭에서도 한 줄 흐름) + **A4 양식에 시트 하나**(PFMEA 엑셀에 "공정 흐름" 시트) | SVG 그림 라이브러리 — vendor 추가·CSP 검토, 지금 정보량엔 과함 |
| D4 | DFMEA 를 어디에 | **같은 표 `apqp_fmea` + `fmea_type` = DFMEA**, 노드 종류에 SYSTEM·SUBSYSTEM·COMPONENT 추가(DFMEA 전용), 기능·고장·사슬·위험·AP·조치·리비전·결재(A3-7)·엑셀(A4)·QMS 문서(A3-3) 재사용 | 별도 표 묶음 `apqp_dfmea_*` — 같은 규칙(트리거·결재·양식)을 두 벌 유지 |
| D5 | DFMEA 대상 | **품목 + 확정 BOM 리비전(구조 원본) + 도면 리비전(QMS DRAWING `mst_doc_rev` id, 선택)**. PFMEA 의 라우팅 자리에 BOM, `drawing_rev` 글자 자리에 도면 리비전 id(DFMEA 만) | 품목만 + 글자 — 설계 변경(BOM·도면 새 리비전)과 이어지지 않아 재검토 표시를 못 함 |
| D6 | DFMEA 구조 끌어오기·재검토 | PFMEA 가 라우팅에서 STEP 을 끌어오듯 **BOM 1~2단계 → SUBSYSTEM·COMPONENT 노드**(최상위 = SYSTEM = 품목). 같은 품목의 BOM 새 리비전이 확정되면 "재검토" 표시(A3-2 ① 과 같은 계산), 도면 새 리비전 배포도 같은 표시. 바꾸기는 11.9 와 같은 "다시 잇기"(같은 자품목만 짝) | 끌어오기 없음(손으로 구조 입력) — BOM 과 어긋남 |
| D7 | PFMEA 와의 연결 | **특성은 같은 id 를 쓴다**(DFMEA 기능의 제품 특성 = `qms_char` PRODUCT, 특별특성은 마스터 원본 그대로). PFMEA 화면에서 "이 품목의 확정 DFMEA 가 정한 특성" 을 보여 주고 고르게(복사 없음). PFMEA 고장 영향(FE) 후보로 DFMEA 고장 형태를 **제안만**(고르면 글자 복사 + 출처 표시) | DFMEA 위험행·특성을 PFMEA 로 통째 복사 — 두 문서가 따로 놀고 특별특성이 두 곳에 생김 |
| D8 | 화면·권한 | **새 화면 `apqp-dfmea`**(설계팀과 공정팀 권한을 나눌 수 있게) — 코드는 PFMEA 화면을 종류별로 재사용 | 같은 화면 `apqp-fmea` 에 종류 필터 — 권한이 하나라 설계·공정 담당을 못 나눔 |
| D9 | 순서 | **A5-1 PFD**(마이그레이션 없음 예상 — 읽기·양식만) → **A5-2 DFMEA**(마이그레이션: `fmea_type`·노드 종류 CHECK, `bom_id`·`doc_rev_id` 칸, 화면 `apqp-dfmea`) | DFMEA 먼저 — PFD 가 더 작고 지금 데이터만으로 됨 |

### 13.3 하지 않을 것 (명시)
- AIAG·VDA 양식·표·기호·문구·색 복사, 핸드북 AP 표·심각도/발생도/검출도 표 값 시드(지금처럼 회사가 넣는 AP 규칙표만 — 4장).
- PFD 의 그림 편집기(도형 끌어 놓기)·분기/재작업 고리 편집, 블록 다이어그램·P-다이어그램 그림 편집기, CAD 연동, 패밀리 FMEA·기본(Foundation) FMEA 라이브러리.

### 13.4 시험 계획 (구현 때)
PFD: 확정 라우팅 + PFMEA·CP → 단계 순서·기호(검사·외주·포장)·특성·특별특성·위험행 수가 원본과 같음, 라우팅 다시 잇기(11.9) 뒤 바로 따라감, 엑셀 시트, 390 폭. DFMEA: BOM 끌어오기(SYSTEM·SUBSYSTEM·COMPONENT), 확정·결재·양식·QMS 문서 재사용, BOM·도면 새 리비전 → 재검토, PFMEA 에서 DFMEA 특성 고르기(같은 id), 권한 나눔(apqp-dfmea), **A사로 B사**(다른 회사 BOM·도면 리비전 지정 거부).

### 13.5 검토

| 날짜 | 검토자 | 결정·의견 |
|---|---|---|
| 2026-10-24 | PMS | 초안 — D1~D9 추천, 상위가 사용자 결정 요청 |
| 2026-10-29 | 사용자(상위 경유) | **D1~D9 모두 추천대로**. 순서 PMS PR 1~3 뒤 A5-1 PFD → A5-2 DFMEA. 13.3·13.4 그대로, `module.apqp` jOOQ 규칙 그대로 |

### 13.6 A5-1 PFD 구현 (2026-10-29, 마이그레이션 없음)

| # | 구현 | 메모 |
|---|---|---|
| F1 | `GET /api/apqp/fmeas/{id}/flow`(apqp-fmea SEARCH) → `FlowView` — `ApqpFlowService` 가 그때 만든다(D1): PFMEA 의 라우팅(그 리비전) 단계 순서, 단계마다 기호·표준 CT·특성·관리계획서 행 수·위험행 수·AP H 수 | 저장 표 없음. 라우팅·위험행·관리계획서·특성 마스터가 바뀌면 다음 조회에 바로 반영 |
| F2 | 기호 4종(D2): 외주(`outsrc_yn` Y)가 먼저, 그다음 `proc_cat` INSPECT = 검사, PACK = 포장, 나머지 = 가공. 화면 모양은 우리 CSS(둥근·각진·점선·굵은 칸), 표준 기호 그림 아님 | 외주이면서 검사인 공정은 "외주" — 어디서 하느냐가 흐름에서 먼저 보여야 함 |
| F3 | 특성 = PFMEA 기능(그 단계 STEP 노드와 아래 작업요소)에 붙은 특성 ∪ 관리계획서 행 특성, 같은 id 는 한 번(출처 inFmea·inCp). 특별특성 = 마스터 `special_cls`(D2·H6) | 스냅샷 아닌 마스터 — 흐름도는 "지금" 을 보여 준다 |
| F4 | 관리계획서 = 이 PFMEA 를 가리키는 것 중 폐기 아닌 가장 높은 리비전. apqp-cp SEARCH 가 없으면 "있음(볼 권한 없음)" 만, 행 수·특성 출처는 PFMEA 만 | 화면 권한 그대로 |
| F5 | 위험행은 고장 형태(FM)의 기능 노드 → 위로 올라가 라우팅 단계. 라우팅에 없는 단계(다시 잇기 전 옛 단계·손으로 만든 단계)에 놓인 위험행은 `unplaced` 로 따로 세어 보인다 | 조용히 빠지지 않게 |
| F6 | 화면: PFMEA 상세 [공정 흐름](라우팅 있을 때) → 세로 흐름 목록(CSS, 390 폭 한 줄). 엑셀(A4): PFMEA 양식에 3번째 시트 "공정 흐름" — 같은 `FlowView` | D3. QMS 문서로 올리는 양식(A3-3)에도 같이 들어간다 |

### 13.7 A5-2a DFMEA 구현 (2026-10-29, V211·임시 V9417) — 2a/2b 나눔

상위 승인(2026-10-29): **2a** = 스키마·BOM 끌어오기·화면·같은 서비스 재사용·A사로 B사 / **2b** = BOM·도면 새 리비전 재검토 + BOM 다시 잇기, PFMEA 에서 DFMEA 특성 고르기·FE 제안(D7), DFMEA QMS 문서(QMS `mst_doc` 유형에 DFMEA 가 없어 QMS 계약 요청 먼저 — 상위 경유).
수준 짝: **SYSTEM = FE · SUBSYSTEM = FM · COMPONENT = FC**(PFMEA 의 ITEM·STEP·ELEMENT 자리).

| # | 구현 | 메모 |
|---|---|---|
| G1 | V211(임시 V9417): `fmea_type` IN (PFMEA, DFMEA), `bom_id`(mst_bom)·`doc_rev_id`(mst_doc_rev) 칸, `ck_apqp_fmea_target`(PFMEA = BOM·도면 없음 / DFMEA = 라우팅 없음), 노드 `ref_item_id`(mst_item)·종류 SYSTEM·SUBSYSTEM·COMPONENT(`node_kind` varchar 8 → 10) | 같은 표(D4). 기존 행은 전부 PFMEA |
| G2 | 트리거: 유형 바꾸기 금지, BOM = 머리 품목 것, 도면 리비전 = 같은 회사 DRAWING(`mst_doc` 유형 확인 — QMS 표 변경 없음), 자품목 노드가 있으면 BOM 못 바꿈, 노드 종류 = 유형 것·3단 수준, 자품목 = 같은 회사, DFMEA 기능 특성 = PRODUCT 이고 머리 품목 또는 그 노드 자품목 것, 고장 수준(FE·FM·FC), 관리계획서 근거 = PFMEA 만(`ck_apqp_cp_fmea_type`) | 서비스가 먼저 칸 오류로, 트리거가 최종 |
| G3 | 같은 서비스 `FmeaService` — 유형별 뿌리·2단·3단 종류, 채번 `APQP_DFMEA`(DF-yyyy-nnnn), [BOM 1~2단계로 시작]: 1단계 = 고른 BOM 의 줄 → SUBSYSTEM(이름 "품번 품명", 자품목 연결), 2단계 = 그 자품목의 **오늘 유효한 확정 BOM(대체 1)** 줄 → COMPONENT | 2단계 BOM 이 없으면 하위 시스템만. 노드 수 상한 그대로(2,000) |
| G4 | API `/api/apqp/dfmeas`·`/api/apqp/dfmea-{nodes,funcs,fails,chains,risks,actions}`·고르기 `/api/apqp/dfmea/*`(품목·BOM·도면 리비전·결함·프로젝트·사용자·규칙표) — 모두 `@ProgAuth("apqp-dfmea")`. `FmeaTypeGuard`: 경로의 유형과 기록 유형이 다르면 **404**(PFMEA 경로·권한으로 DFMEA 를 읽거나 고치지 못함, 그 반대도) | D8 권한 나눔. 하위 행은 그 행의 FMEA 유형으로 판정 |
| G5 | 재사용: 기능·고장·사슬·위험·AP·조치·확정·새 리비전(자품목 연결 복사)·결재(문서 유형 `APQP_DFMEA`, 결재함 링크 apqp-dfmea)·엑셀 양식(같은 양식, 머리 BOM·도면 문서, 칸 이름 하위 시스템·구성품, 공정 흐름 시트 없음) | QMS 문서 올리기·라우팅 바꾸기·공정 흐름은 PFMEA 만 |
| G6 | 다른 모듈 연결은 PFMEA 만: `ApqpLookup`(PMS 산출물·PLM current·조치 → 이슈)·관리계획서 근거 후보·`findReleasedFmeaIdByNo` 에 `fmea_type = PFMEA`. DFMEA 상세의 이슈 칸(issueView·issueSave)은 false | DFMEA 를 PMS 산출물·이슈에 잇는 것은 필요해지면 따로(계약 변경) |
| G7 | 화면 `apqp/dfmea-mgt`(sys_prog apqp-dfmea, 메뉴 정렬 845) — PFMEA 화면 스크립트 `fmea-mgt.js` 를 `data-prog` 로 갈래(경로·수준 이름·머리 칸만 다름). 머리 = 품번 → 확정 BOM 고르기, 도면 문서 번호 → 리비전 고르기 | 목록 칸: 라우팅 자리에 BOM |

### 13.8 A5-2b DFMEA 재검토·BOM 다시 잇기·PFMEA 연결 (2026-10-29, V212·임시 V9418)

| # | 구현 | 메모 |
|---|---|---|
| H1 | **재검토 표시(D6)**: `bomStale` = 같은 품목·대체 번호에 더 늦은(valid_from) 확정 BOM 리비전(조회 SQL, 라우팅 ① 과 같은 계산). `drawingStale` = 지금 도면 리비전보다 나중의 확정 리비전이 같은 문서에 있음 — QMS 계약 `DocLookup.revsByDocNo`(최근 것부터)로 서비스가 채운다(QMS 표를 APQP SQL 로 읽지 않음). 목록·엑셀 재검토 칸 = ROUTING·BOM·DRAWING(쉼표), 상세 알림 줄 | 표시만 — 자동으로 바꾸지 않는다. 목록·엑셀은 쪽 안에서 같은 리비전을 한 번만 묻는다 |
| H2 | **BOM 다시 잇기(D6)**: `GET /api/apqp/dfmeas/{id}/bom-relink?bomId=` 미리보기 → `POST …/bom {bomId,rowVer}` 적용(서버가 다시 계산, rowVer 다르면 409). 짝 = 하위 시스템의 자품목이 새 BOM 1단계 줄에 있음(같은 품목만 — 추측 짝 없음). 짝 없는 하위 시스템은 자품목 연결만 끊고 이름·기능·고장은 그대로(글자 노드), 새 BOM 줄 중 구조에 없는 자품목은 하위 시스템으로 더한다(구성품은 사람이) | 라우팅 다시 잇기(11.9)와 같은 틀. 머리 폼에서 BOM 바꾸기는 여전히 APQP_BOM_USED → [BOM 바꾸기] 안내 |
| H3 | V212(임시 V9418) 트리거: BOM 바꾸기 규칙을 "자품목 노드가 있으면 금지" → **"자품목을 가리키는 하위 시스템은 모두 새 BOM 의 줄"** 로. 구성품의 자품목은 하위 시스템 품목의 BOM 에서 온 것이라 머리 BOM 과 무관 | 직접 SQL 로 짝 없는 채 BOM 을 바꾸면 거부 |
| H4 | **PFMEA 의 DFMEA 특성(D7)**: PFMEA 특성 후보에 `inDfmea`(같은 품목 확정 DFMEA 기능에 붙은 특성 id) 표시 — 같은 id, 복사 없음 | 고르는 것은 PFMEA 담당 |
| H5 | **고장 영향 제안(D7)**: `GET /api/apqp/fmeas/{id}/dfmea-sources`(apqp-fmea SEARCH) = 같은 품목 확정 DFMEA 의 고장 형태. 고르면 화면이 글자를 복사하고 `apqp_fmea_fail.src_fail_id` 에 출처를 남긴다(칩 "출처 DF-… rev n"). 트리거: 출처는 PFMEA 의 FE 만, 같은 회사·같은 품목·확정(또는 폐기) DFMEA 의 FM | 원본 DFMEA 가 새 리비전으로 폐기돼도 붙은 출처는 그대로(새로 고를 때만 확정본) |
| H6 | 하지 않음: DFMEA 확정본 → QMS 문서(QMS `mst_doc` 유형에 DFMEA 추가 계약 먼저 — 상위 경유), 구성품 단계의 BOM 재검토(하위 시스템 품목 BOM 새 리비전) | 필요해지면 따로 |

### 13.9 확정 DFMEA → QMS 관리문서 (2026-10-29, 마이그레이션 없음 — QMS V213 문서 유형 DFMEA 뒤)

| # | 구현 | 메모 |
|---|---|---|
| Q1 | `GET·POST /api/apqp/dfmeas/{id}/qms-doc`(apqp-dfmea SEARCH·PRINT + QMS 문서 SAVE) — A3-3 `ApqpDocService` 그대로. 종류 = FMEA(같은 표 `apqp_fmea`, `apqp_qms_doc.src_kind` 그대로), QMS 문서 유형 = 기록 유형(PFMEA·DFMEA), 변경 요약 "DFMEA DF-… rev n" | `apqp_qms_doc` CHECK·트리거 변경 없음(종류 FMEA 는 원래 apqp_fmea 의 확정본) |
| Q2 | 같은 번호의 이전 리비전이 올린 문서가 있으면 그 문서의 새 리비전(B3), 멱등(B5), 현행 확정본만(B7) — PFMEA 와 같음. 원본 화면 PRINT 는 기록 유형의 화면(apqp-dfmea)으로 본다 | PFMEA 경로로 DFMEA 는 404(FmeaTypeGuard) |
| Q3 | 화면: DFMEA 상세 [QMS 문서로 올리기]·올린 문서 표시(PFMEA 와 같은 스크립트) | |
