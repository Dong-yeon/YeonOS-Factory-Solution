# 33. CMS(설비관리) 설계 — 1부: P2 6단계와 병행하는 보전·점검·예비품·안전 대장

작성 2026-10-20. 1부(C1~C6) 완료 2026-10-21. 2부(C7~C10) 설계 = 11장(D18~D24 채택 2026-10-21), C7~C10 완료 2026-10-21 — **2부 끝**.
근거: docs/00 2장(CMS = 설비관리)·5장 테이블 표(`cms_*` 행, 고장 모드 코드 P5)·6장 로드맵 P5 행(설비 상태 화면·알람·예방점검·OEE), 02 2.4(SEMI E10 상태·MTBF)·2.5(ISO 22400 OEE·"합산한 뒤 나눈다"·`sys_kpi_def`), 03 2.7(예지보전 — 고장 모드 코드가 라벨)·326행, 08 4.2·6.1(계측점·구간 사용량 저장), 11 Top #13(닫을 때 필수)·205행(CMS 현장 실패 모습), 17 2장(P1 공통 규칙), 19 4b(설비·물리 자산·설치 이력), 22 5b(설비 클래스 속성), **25 2장 6단계·4장·4.1(P2 가 만드는 `cms_*` 원장과 Collector)**, 27 M7(SEMI E187 설비 보안 대장)·M10(산안법 위험성평가), **29 0장·6장(1부/2부 분할, 병행 규칙)·Q2a(계측기 교정은 QMS)**.
P0 플랫폼 규약(01)·P1 공통 규칙(17 2장)·P2 공통 규칙(25 3장)은 그대로 따른다 — 이 문서는 **CMS 에서 새로 정하는 것**만 적는다. 추정은 [추정], 원문을 다시 봐야 하는 것은 [확인 필요].

---

## 0. 왜 1부·2부로 나누나

CMS 의 "가동" 쪽(상태 타임라인·알람·OEE·MTBF/MTTR)은 **P2 6단계가 만드는 설비 상태 구간 원장과 Collector** 위에 선다. P2 는 7단계라 6단계까지 오래 걸리므로(2026-10-20 사용자) **상태 원장에 기대지 않는 "보전" 쪽을 1부로 떼어 지금 진행**한다 — 29(QMS)와 같은 방식.

| | 1부 (이 문서, 지금) | 2부 (P2 6단계 병합 뒤) |
|---|---|---|
| 기대는 것 | P1 설비·설비 클래스·물리 자산·장비 계층·공정·품목 + P0(첨부·결재·채번·정책·정기 작업·`BizClock`) | P2 `cms_state_cat`·`cms_state_reason`·`cms_equip_state_log`·`cms_equip_conn_log`·Collector·`prd_result` |
| 범위 | 고장 코드, 보전 작업지시(고장 신고·수리·개선), 점검표·예방점검 계획(달력·사용량 주기)·점검 실행 기록, 사용량 카운터(수기), 예비품 연결·사용 기록, 설비 프로필(중요도·핵심 설비), 설비 보안 대장, 위험성평가 대장 | 상태 타임라인 화면, 알람 정의·이력·상위 알람, OEE·MTBF·MTTR(KPI 정의), 카운터 자동 입력(실적 수량·RUN 시간), 고장 작업지시 ↔ UDT 구간 연결 |
| 원칙 | 2부가 붙을 **자리**(카운터의 출처 칸, 작업지시의 정지 구간 칸, 판정 함수)를 1부 테이블에 미리 둔다 — 2부에서 1부 테이블을 고치지 않게 | 1부 판정 함수(`PmDue`, `CounterTotals`)를 그대로 호출 |

**만들지 않는 이름**(P2 6단계 소유, 25 4장): `cms_state_cat`, `cms_state_reason`, `cms_equip_state_log`, `cms_equip_conn_log`, `cms_energy_reading`, `sys_device_key`, `/ext/v1/equip-events`. 1부는 이것들을 읽지도 않는다.

## 1. 결론 먼저

- 1부는 **"설비를 언제, 무엇으로 점검하고, 고장 나면 누가 무엇을 고쳤는지"**까지다. 데모 흐름: 고장 코드(현상·원인·조치) → 설비 프로필(중요도 A, 핵심 설비) → 점검표(항목·판정 기준) → 예방점검 계획(월 1회 고정 일정 / 금형 5만 타마다) → 정기 작업이 예정 작업지시 생성 → 보전 기사가 점검 결과 입력, NG 항목에서 후속 수리 작업지시 한 번에 → 작업자 고장 신고(설비 선택 + 현상 + 사진) → 접수·수리·완료(**닫을 때만 현상·원인·조치 코드 필수**) → 사용 예비품 기록 → 설비 이력 카드(한 설비의 점검·고장·부품·설치 자산 한 화면).
- **보전 작업지시 `cms_mwo` 하나에 세 유형**(BM 고장 수리 / CM 개선 / PM 예방). 원장이 아니다 — 일반 CRUD + 감사 + 상태 전이 트리거, **완료 뒤 내용 불변**(29 8D 와 같은 "종결 뒤 불변").
- **점검 실행 `cms_chk_run` 은 작업지시와 분리** — PM 작업지시는 점검 실행을 품고, 작업자 일상점검(자주보전)은 작업지시 없이 점검 실행만 남긴다(8장 D7). 점검표에는 리비전을 두지 않고 **실행 행이 항목·기준을 스냅샷**한다(특성 마스터 21 과 같은 판단).
- **사용량 주기는 1부에서 수기 카운터로 동작**하고, 2부가 같은 카운터에 자동 출처(실적 수량·RUN 시간)를 더한다. 카운터 값은 08 의 계량기와 같은 이유로 **구간 증가량**으로 저장(리셋·교체에 강함).
- **예비품은 새 마스터를 만들지 않는다** — 품목 마스터(유형 `CON` 소모품 또는 품목 클래스 "예비품")를 쓰고, CMS 는 설비 ↔ 예비품 연결과 작업지시 사용 기록만. 수량·안전재고·차감은 P4 WMS(8장 D9, P2 의 `prd_receipt` 와 같은 "첫 소스" 방식).
- **MTBF/MTTR·OEE 는 2부에서 한 번만 정의**한다(상태 원장 + KPI 정의). 1부에 작업지시 시각으로 만든 "다른 MTTR" 을 두지 않는다 — 00 의 "리포트 숫자 불일치" 결함을 처음부터 피한다(8장 D10).
- 설비 보안 대장(27 M7)과 위험성평가 대장(27 M10)은 **1부 끝 단계**로, 사용자가 원하면 넣는다(8장 D11·D12).

## 2. 범위와 단계

순서 원칙(17·25·29 와 같음): **참조되는 것 먼저**, 매 단계 화면·API·테스트(정상 + 이상 사용 A~K + A사로 B사 + 직접 SQL)까지 끝난 상태로 커밋, 끝나면 7장 표에 ✅·날짜·테스트 수.

| 단계 | 범위 | 첫 화면 | 완료 기준 |
|---|---|---|---|
| **C1** | **고장 코드·설비 프로필·보전 작업지시(BM·CM)**: 전역 상위 분류 `CMS_FAIL_CAT` + 회사 `cms_fail_code`(현상·원인·조치, 설비 클래스 한정 선택), `cms_equip_profile`(중요도 A/B/C·핵심 설비·기본 담당), `cms_mwo`(채번, 상태 `REQ → OPEN → WIP → DONE → CLOSED` + `CANCELLED`)·`cms_mwo_labor`(공수)·첨부, **고장 신고 화면 분리**(신고 권한만 있는 작업자용), 설비 이력 카드 | 보전 작업지시 · 고장 신고 | 상태 전이 우회 불가(직접 SQL 55000), BM 완료 때 코드 3종 필수·도중엔 미강제, 완료 뒤 수정 불가, 시각 순서(신고 ≤ 착수 ≤ 완료, 정지 시작 ≤ 끝), 미래 시각 거부, 사용 중지 설비·코드는 새 참조만 차단, 채번 결번 없음 |
| **C2** | **점검표·예방점검(달력 주기)**: `cms_chk_tmpl`·`cms_chk_tmpl_item`(판정 OKNG/NUM/TEXT, 하한·상한·단위, 사진 필수), `cms_pm_plan`(대상 설비 또는 금형·지그, 실행 방식 WO/CHECK, 달력 주기 FIXED/FLOAT), 판정 함수 `PmDue`, 정기 작업 `CmsPmScheduler`(예정 작업지시·점검 실행 생성, 멱등), `cms_chk_run`·`cms_chk_result`(스냅샷), NG → 후속 작업지시, 기한 준수 표시 | 예방점검 계획 · 점검 실행 | 같은 예정일 두 번 생성 안 됨(작업 2회·동시 실행), FIXED/FLOAT 다음 예정일 표 기반 검산(월말·윤년·공장 시간대), 점검표를 바꿔도 이미 생성된 실행은 그대로, NUM 경계값 판정(= 하한·상한 포함) |
| **C3** | **사용량 주기**: `cms_counter`(설비·금형별 SHOTS/CYCLES/RUN_HOURS …)·`cms_counter_reading`(구간 증가량, 출처 MANUAL — 2부 AUTO, 추가 + 무효만), 수기 입력은 "지금 계기 값" → 증가량 계산(리셋 표시), `pm_plan` 사용량 주기, 금형 수명 타수(P1 `life_shots`) 대비 누적 표시 | 카운터 입력 · 예방점검 계획(사용량) | 계기 값이 줄면 리셋 확인 없이는 거부, 무효 뒤 누적·예정 재계산, 같은 카운터 동시 입력 순서 보장(카운터 행 잠금), 사용량 도달 시 같은 트랜잭션에서 예정 생성 |
| **C4** | **예비품**: `cms_equip_spare`(설비 또는 설비 클래스 × 품목, 설치 수량, 핵심 예비품 표시), `cms_mwo_part`(작업지시 사용 품목·수량·품목 기본 단위), 설비별·품목별 사용 이력 조회 | 설비 예비품 | 사용 중지 품목 새 연결 차단, 수량 BigDecimal·단위 자릿수, 완료된 작업지시의 사용 기록 불변, A사 품목을 B사 작업지시에 못 붙임(같은 회사 트리거) |
| **C5** | **설비 보안 대장**(27 M7, SEMI E187 대비): `cms_equip_sec`(설비 1:1 — 네트워크 연결·OS·OS 지원 종료일·마지막 악성코드 검사일·검사 도구·목표/현재 보안 등급·USB 통제·원격 접속 방식·검토일), 대장 화면 + 경고(지원 종료 OS, 검사 기한 경과 — 정책) | 설비 보안 대장 | 날짜 경계(오늘 = 기한), 연결 안 된 설비는 검사 항목 면제, 엑셀 내려받기 |
| **C6** | **위험성평가 대장**(27 M10, 산안법): `cms_risk_assess`(채번, 유형 최초/정기/수시/상시, 방법, 범위 = 공장·라인·공정·설비, 상태 DRAFT → IN_APPROVAL → RELEASED → OBSOLETE, 결재)·`cms_risk_item`(작업·유해위험요인·분류·현재 조치·가능성×중대성·위험성 수준·개선 대책·담당·기한·완료·증빙·개선 후 위험성)·`cms_risk_member`(참여·주지 기록), 보존 클래스 `SAFETY_RECORD` | 위험성평가 | 확정본 불변, 개선 대책 기한 지연 표시, 위험성 수준 = 정책 행렬로 계산(손 검산), 참여 기록 없는 평가는 확정 불가, 보존 클래스 등록 |

**2부(P2 6단계 병합 뒤, 착수 때 이 문서에 상세 추가)** — C7 상태 타임라인(설비·라인, 비가동 사유 입력은 P2 5b 화면 재사용)·고장 작업지시 ↔ UDT 구간 연결 / C8 알람 정의·이력·상위·반복 알람(03 2.7) / C9 KPI 정의(`sys_kpi_def`·`sys_kpi_policy`, ISO 22400 7종) + OEE·가용률·MTBF·MTTR·PM 준수율 / C10 카운터 자동 입력(실적 수량 → SHOTS·CYCLES, RUN 구간 → RUN_HOURS) + 금형 타수 자동.

## 3. 1부 공통 규칙

| 규칙 | 내용 | 근거 |
|---|---|---|
| 기준정보 | 고장 코드·점검표·PM 계획·카운터·설비 프로필·예비품 연결·보안 대장은 P1 공통 규칙(회사별 RLS·`row_ver`·`tg_audit`·보존·앱 DELETE 회수·사용 중지 = **새 참조만 차단**·코드 대문자 정리) | 17 2장 |
| P1 테이블 | `mst_equip`·`mst_phys_asset`·`mst_equip_class`·`mst_item` 은 **읽기·참조만**. CMS 가 설비에 더할 칸은 1:1 테이블(`cms_equip_profile`·`cms_equip_sec`)로 — P1 컬럼 추가·CHECK 변경 없음 | 병행 규칙(6장) |
| 대상 | 대상은 `target_type` EQUIP(설비) / ASSET(물리 자산 MOLD·JIG) + `equip_id`·`asset_id` 중 정확히 하나(CHECK). **GAUGE 는 대상이 아니다** — 교정은 QMS Q2a(`qms_gauge_calib`·`GaugeStatus`) | 29 Q2a |
| 같은 회사 | 회사별 테이블 간 FK(설비·자산·품목·클래스·사용자·점검표)는 RLS 를 무시하므로 **같은 회사 트리거** — P1 `hb_check_item_unit` 본보기 | SKILL §2 |
| 상태 전이 | 작업지시·점검 실행·위험성평가는 전이 표 하나(서비스 + 트리거 SQLSTATE 55000 → 409), 화면은 허용된 다음 동작만 버튼 | 25 3장 |
| 닫을 때 필수 | 신고·작업 중에는 강제하지 않고 **완료(DONE) 때만**: BM = 현상·원인·조치 코드 + 작업 내용, PM = 모든 필수 점검 항목 결과, CM = 작업 내용. 신고는 설비 + 현상(코드 또는 글) 하나면 된다 | 11 Top #13·205행 |
| 완료 뒤 불변 | DONE 이후 내용·공수·사용 예비품·점검 결과 UPDATE/DELETE 금지(트리거). CLOSED 는 확인 표시만 바꾼다. 잘못 닫은 것은 "재작업 = 새 작업지시(`parent_mwo_id`)" — 되돌리기 없음 | 29 8D, 원장 정신 |
| 시각 | 신고·착수·완료·정지 시각은 **설비 공장 시간대**의 현지 시각 입력 → `BizClock.toInstant`(DST 없는 시각 `DST_GAP`), 저장은 `timestamptz`. 예정일·기한은 공장 현지 `date`. 미래 시각은 정책 허용 오차(25 3장 값 재사용) 밖이면 거부 | 25 D5·4.2 |
| 채번 | 작업지시 `CMS_MWO`, 위험성평가 `CMS_RA` — `NumberingService`(업무 트랜잭션 MANDATORY, 결번 없음) | P0 5단계 |
| 정기 작업 | `CmsPmScheduler` 는 `TenantRunner.forEachActiveComp` + `JobLock.tryLock("cms.pm")`, 회사마다 한 트랜잭션, 생성은 `unique(comp_cd, plan_id, due_date)` + `ON CONFLICT DO NOTHING` 으로 멱등 | P0 6단계 |
| 판정 함수 | `PmDue.next(plan, lastDone, today)`·`PmDue.state(due, today, lead)`(UPCOMING/DUE/OVERDUE), `CounterTotals.since(counter, from)`, `RiskMatrix.level(l, s)` — **순수 함수 + 표 기반 단위 테스트**. 2부·화면은 이 함수만 부른다 | 26·29 3장 |
| 수량·값 | 점검 NUM 값·카운터·예비품 수량은 `numeric` + 서버 `BigDecimal`(Jackson Double 금지, 응답은 글자) | 22 5b QA |
| 정책 | 새 정책 키: `CMS_PM_LEAD_DAYS`(예정 생성 선행일, 기본 7), `CMS_PM_GRACE_DAYS`(기한 준수 허용, 기본 0), `CMS_SEC_SCAN_MAX_DAYS`(보안 검사 주기, 기본 90), `CMS_RA_MATRIX`(위험성 행렬 3x3/5x5, 기본 3x3), `CMS_RA_APPROVAL`(Y/N) | — |
| 보존 | 보전 기록(작업지시·공수·사용 예비품·점검 실행·카운터) = 새 클래스 `MAINT_RECORD`(KEEP — 설비 폐기 뒤에도 이력 카드), 위험성평가 = `SAFETY_RECORD`(OCCURRED_AT, 최소 3년) — 8장 D13 | V007, 27 M10 |
| 권한 | 신고(`cms-breakdown` SAVE)와 작업지시 처리(`cms-mwo` SAVE·APPROVE)를 **다른 화면**으로 — 작업자에게 작업지시 처리 권한을 주지 않고도 신고하게 | P0 4단계 |
| i18n·코드 | 문구 키 `cms.*`·`js.cms.*`, 오류 코드는 기존 `valid.*` 문구가 맞는지 먼저 보고 아니면 새 코드(SKILL §2 "공용 오류 코드") | SKILL §2 |
| 테스트 | 25 3장 그대로 + 날짜 계산 손 검산(월말·윤년·공장 시간대 자정) + 정기 작업 동시 실행 + 직접 SQL(완료 뒤 UPDATE·다른 회사 참조·상태 건너뛰기) | 24 |

## 4. 단계별 데이터 모델 (요약 — 단계 착수 때 상세를 이 문서 해당 절에 추가)

```text
── C1 고장 코드·설비 프로필·보전 작업지시 ─────────────────────
sys_code CMS_FAIL_CAT  전역 상위 분류(회사 추가 불가 — 불량 DEFECT_CAT 와 같은 방식, 회사 간 비교·예지보전 라벨용)
                       MECH 기계 / ELEC 전기 / CTRL 제어·SW / FLUID 유공압 / UTIL 유틸리티 / TOOL 금형·치공구 / OPER 조작 / EXT 외부(정전 등) / UNKNOWN
                       (ISO 14224 고장 모드 표는 옮겨 오지 않는다 — 원문 저작권, 자체 분류만 [추정: 자동차 부품 현장에 충분])
cms_fail_code        (comp, code_type SYMPTOM/CAUSE/ACTION, fail_cd, fail_nm, fail_cat(CMS_FAIL_CAT, 원인은 필수),
                      equip_class_id nullable(그 클래스 작업지시에서만 보임), sort_no, use_yn, row_ver)
                     unique(comp_cd, code_type, fail_cd)
cms_equip_profile    (comp, equip_id unique, criticality A/B/C, key_equip_yn(IATF 8.5.1.5 핵심 설비), owner_user_id(기본 보전 담당),
                      remark, row_ver)   ← 행이 없으면 "C·핵심 아님·담당 없음"
cms_mwo              (comp, mwo_no 채번, mwo_type BM/CM/PM, plant_cd, target_type, equip_id, asset_id, title, priority 1/2/3,
                      status REQ/OPEN/WIP/DONE/CLOSED/CANCELLED,
                      reported_at, reported_by(user), symptom_id, symptom_text, photo는 첨부,
                      assignee_user_id, due_date, started_at, finished_at,
                      down_yn, down_start_at, down_end_at      ← 설비 정지 구간(수기). 2부는 이 칸을 UDT 구간과 대조만, 칸은 그대로
                      cause_id, action_id, work_text, cancel_reason, closed_at, closed_by,
                      pm_plan_id, due_ref_date(PM 예정일), parent_mwo_id(점검 NG·재작업 후속), row_ver, 감사)
                     트리거: 전이 표, DONE 이후 불변, 닫을 때 필수, 시각 순서, 같은 회사(설비·자산·코드·사용자·계획)
                     unique(comp_cd, pm_plan_id, due_ref_date) WHERE pm_plan_id IS NOT NULL   ← 예정 생성 멱등
cms_mwo_labor        (comp, mwo_id, user_id, work_date, minutes 1~1440, remark)      ← 외주 업체 공수는 user 대신 거래처 + 글(선택)
첨부                 FileService target_type = 'cms-mwo' (사진·보고서)

── C2 점검표·예방점검 ───────────────────────────────────────
cms_chk_tmpl         (comp, tmpl_cd, tmpl_nm, target_kind EQUIP/ASSET, equip_class_id nullable, use_yn, row_ver)   ← 리비전 없음(D8)
cms_chk_tmpl_item    (comp, tmpl_id, seq, item_nm, method_text, judge_type OKNG/NUM/TEXT, lsl, usl, unit_nm, scale,
                      required_yn, photo_req_yn, use_yn)      ← NUM 은 하한·상한 중 하나 이상, 하한 < 상한
cms_pm_plan          (comp, plan_cd, plan_nm, target_type, equip_id, asset_id, tmpl_id, exec_mode WO/CHECK,
                      cycle_type CAL/USAGE,
                      cal_unit DAY/WEEK/MONTH/YEAR, cal_interval, schedule_basis FIXED(달력 고정)/FLOAT(완료 기준), anchor_date,
                      counter_id, usage_interval,             ← C3
                      lead_days nullable(없으면 정책), assignee_user_id, est_minutes,
                      next_due_date, last_done_date, last_done_usage, use_yn, row_ver)
                     next_due_date 는 PmDue 가 계산해 저장(목록·정렬용) — 완료·무효·계획 수정 때 같은 트랜잭션에서 다시 계산
cms_chk_run          (comp, plan_id nullable, mwo_id nullable, target_type, equip_id, asset_id, tmpl_id, due_date,
                      status OPEN/DONE/SKIPPED, performed_at, performed_by(user), result OK/NG, skip_reason, row_ver)
                     unique(comp_cd, plan_id, due_date) WHERE plan_id IS NOT NULL AND mwo_id IS NULL   ← CHECK 방식 멱등
cms_chk_result       (comp, run_id, seq, item_nm, method_text, judge_type, lsl, usl, unit_nm, scale, required_yn  ← 생성 때 스냅샷
                      value_num, value_text, judge OK/NG/NA, photo_file_id, remark)
작업                 CmsPmScheduler 매일(공장 시간대 하루 확정 뒤): next_due_date - lead ≤ 오늘인 계획 → exec_mode 에 따라
                     작업지시(PM)+점검 실행 또는 점검 실행만. 지난 예정은 자동 취소하지 않는다(지연으로 보인다)

── C3 사용량 ────────────────────────────────────────────────
cms_counter          (comp, counter_cd, target_type, equip_id, asset_id, counter_kind SHOTS/CYCLES/RUN_HOURS/OTHER, unit_nm, scale, use_yn, row_ver)
cms_counter_reading  (comp, counter_id, read_at, gauge_value nullable(계기 값), delta_qty(구간 증가량, ≥ 0), reset_yn,
                      source MANUAL/AUTO(2부), void_yn, void_reason, recorded_at, recorded_by)
                     추가 + 무효만(4b 설치 이력·29 교정 기록과 같은 "원장 변형"), 누적 = Σ delta (무효 제외) — 08 의 구간 사용량 저장과 같은 이유
                     금형 누적 타수 vs P1 mst_phys_asset.life_shots → 화면 경고만(사용 중지 자동 안 함)

── C4 예비품 ────────────────────────────────────────────────
(품목)               mst_item 그대로 — 유형 CON(소모품) 또는 품목 클래스 "예비품"(7b). P1 변경 없음
cms_equip_spare      (comp, equip_id 또는 equip_class_id(정확히 하나), item_id, install_qty, key_spare_yn, remark, use_yn, row_ver)
cms_mwo_part         (comp, mwo_id, item_id, qty, unit_cd(품목 기본 단위), remark)   ← P4 재고 원장의 출고 소스(6장)

── C5 설비 보안 대장 ───────────────────────────────────────
cms_equip_sec        (comp, equip_id unique, network_yn, os_nm, os_ver, os_eos_date, last_scan_date, scan_tool,
                      target_sl, current_sl(SL0~SL3, SEMI E187·IEC 62443 등급 [확인 필요: 등급 세부]),
                      usb_ctrl_yn, remote_access NONE/VENDOR_VPN/OTHER, review_date, remark, row_ver)

── C6 위험성평가 ─────────────────────────────────────────────
cms_risk_assess      (comp, ra_no 채번, plant_cd, ra_type INITIAL/REGULAR/ADHOC/CONTINUOUS, method FREQ_SEV/THREE_STEP,
                      title, scope_node_id nullable, assess_date, status DRAFT/IN_APPROVAL/RELEASED/OBSOLETE, prev_ra_id, row_ver)
cms_risk_item        (comp, ra_id, seq, task_text(작업·공정 단계), proc_id nullable, equip_id nullable, hazard_cat(공통코드),
                      hazard_text, current_measure, likelihood, severity, risk_level,
                      plan_measure, owner_user_id, due_date, done_date, evidence는 첨부, residual_l, residual_s, residual_level)
cms_risk_member      (comp, ra_id, user_id 또는 worker_id(P2 mst_worker) 또는 이름, role(근로자 대표·관리감독자 등),
                      participated_on, notified_on)   ← 참여·주지 기록(과태료 사유 "미참여" 대비)
```

### 4.1 상태 전이 (C1 작업지시)

| 지금 | 다음 | 동작·권한 | 조건 |
|---|---|---|---|
| (없음) | REQ | 고장 신고 `cms-breakdown` SAVE | 설비 + (현상 코드 또는 현상 글). BM 만 |
| (없음) | OPEN | 작업지시 등록 `cms-mwo` SAVE / 정기 작업(PM) | CM·PM 은 바로 OPEN |
| REQ | OPEN | 접수 SAVE | 담당 지정(없으면 설비 프로필 기본 담당) |
| OPEN | WIP | 착수 SAVE | `started_at` |
| WIP | DONE | 완료 SAVE | 닫을 때 필수(3장), `finished_at ≥ started_at`, 정지 구간 있으면 끝 시각 필수 |
| DONE | CLOSED | 확인 APPROVE | 신고자 또는 APPROVE 권한 — 내용은 바꾸지 않는다 |
| REQ·OPEN | CANCELLED | 취소 DELETE | 사유 필수. PM 취소 = 그 예정은 건너뜀(다음 예정 계산은 계획 방식대로) |

PM 작업지시의 DONE → 계획 `last_done_date`(FLOAT 다음 예정의 기준)·`last_done_usage` 갱신은 같은 트랜잭션.

### 4.2 C1 상세 — 고장 코드·설비 프로필·보전 작업지시 (V041, 2026-10-20)

**만든 것**: `cms_fail_code`·`cms_equip_profile`·`cms_mwo`·`cms_mwo_labor` + 시스템 코드 `CMS_FAIL_CAT`(9)·`CMS_FAIL_TYPE`·`CMS_MWO_TYPE`·`CMS_MWO_STATUS`·`CMS_CRIT` + 보존 클래스 `MAINT_RECORD` + 함수 `hb_user_of_comp`. 화면 4개(`cms-fail-code`·`cms-equip-profile`·`cms-breakdown`·`cms-mwo`), 메뉴 "설비관리"(로컬 시더).

| 규칙 | 내용 |
|---|---|
| 고장 코드 | (유형, 코드) 회사 안 유일 — 유형이 다르면 같은 코드 가능. 원인은 상위 분류 필수(CHECK). 유형·코드 고정(트리거 `ck_cms_fail_code_fixed`). 설비 클래스 한정은 사용 중 클래스만 새로 걸 수 있고, 이미 걸린 사용 중지 클래스는 둔다 |
| 클래스 한정 코드 | 그 클래스 설비 작업지시에서만(`CLASS_MISMATCH`). 금형·지그에는 클래스 한정 코드를 쓰지 않는다. 신고 화면의 현상 타일도 같은 규칙으로 숨긴다 |
| 설비 프로필 | 행이 없으면 C·N·담당 없음. 저장 = rowVer 없으면 새 행(이미 있으면 409), 있으면 수정. 담당은 같은 회사 사용 중 사용자(트리거) |
| 번호 | `CMS_MWO` 규칙이 없는 회사는 처음 등록 때 기본 규칙 `MWO-{yyMM}-{seq:4}`(월 초기화)을 만든다 — 회사가 바꾸려면 채번 규칙만 고친다 |
| 대상 | 설비(사용 중) 또는 금형·지그(사용 중). 게이지는 `NO_SUCH_CODE`. 설비 작업지시의 공장 = 설비의 공장(트리거 `ck_cms_mwo_plant`), 금형·지그는 지금 설치된 설비의 공장(미설치면 없음 → 회사 시간대) |
| 등록 | 고장 신고 = BM·REQ·신고자 나(현상 코드 또는 글 필수, 제목 비우면 현상 이름·글 앞 50자, "멈춤"만 누르면 정지 시작 = 지금). 작업지시 화면 등록 = BM·CM 만, 바로 OPEN, 담당 비우면 설비 프로필 기본 담당. PM 은 `CODE`(C2 계획만) |
| 전이 | 서비스: 행 잠금(`for update`) → rowVer → 허용 상태(아니면 409 `CMS_MWO_STATE`). DB: 전이 표 트리거(`ck_cms_mwo_transition`) + 완료 뒤 불변(`ck_cms_mwo_done_fixed` — DONE → CLOSED 는 상태·확인 칸만). 담당 없이 착수하면 착수한 사람이 담당 |
| 닫을 때 필수 | 완료(WIP → DONE) 요청이 내용을 함께 보내고, 빠진 칸을 모두 칸 오류로(BM: 현상·원인·조치 코드 + 작업 내용, CM: 작업 내용, 정지면 정지 끝). DB CHECK `ck_cms_mwo_done` 가 최종 |
| 시각 | 대상 공장 시간대 현지 시각(`DST_GAP`·`FUTURE`·`FORMAT`), 응답도 그 시간대 + `timeZone`. 신고 ≤ 착수 ≤ 완료(`BEFORE_REPORTED`·`BEFORE_START`), 정지 시작 ≤ 끝. **화면이 분 단위로 다시 보낸 시각이 지금 값과 같은 분이면 지금 값(초 포함)을 둔다** — 안 그러면 손대지 않은 착수 시각이 분으로 잘려 신고보다 앞선다(브라우저 확인에서 발견) |
| 공수 | OPEN·WIP 에서만 추가·삭제(트리거가 작업지시 행을 잠가 완료와 줄 세움), 수정 없음(앱 UPDATE 회수), 1~1440분, 미래 날짜 거부. 작업자 비우면 나 |
| 조회 | 신고 날짜 기간 필수(최대 366일·회사 정책 상한), 1,000건 넘으면 `CMS_TOO_MANY_ROWS`, 상태 여러 개(`REQ,OPEN,WIP`)·유형·공장·검색어·범위(전체/내 담당/내 신고) |
| 권한 | 신고 화면(`cms-breakdown` SEARCH·SAVE)은 남의 신고를 못 본다(404) — 작업자에게 작업지시 권한 없이 신고·확인만. 접수·착수·완료 SAVE, 확인 APPROVE(또는 신고자 확인), 취소 DELETE |
| 첨부 | 작업지시 `cms-mwo`, 신고 사진 `cms-breakdown`(작업지시 화면은 신고 화면 조회 권한이 있을 때만 보여 줌). 완료 뒤에는 화면에서 삭제 버튼을 숨긴다. 서버는 `MwoFileLock`(C13, 13장)이 없는 작업지시·다른 회사·취소·확인을 잠근다(신고 사진은 BM 만) |
| 설비 이력 카드 | 설비 프로필 상세 아래 — 지금 설치된 자산(P1 설치 이력)·최근 1년 작업지시·건수·고장 수·기록된 정지 분 합. MTBF/MTTR 은 내지 않는다(D10) |

### 4.3 C2 상세 — 점검표·예방점검(달력 주기) (V042, 2026-10-20)

| 규칙 | 내용 |
|---|---|
| 점검표 | `cms_chk_tmpl`(코드·이름·대상 종류 EQUIP/ASSET·설비 클래스 선택) + 항목 `cms_chk_tmpl_item`(순번·이름·방법·판정 OKNG/NUM/TEXT·하한·상한·단위·자릿수·필수). 항목은 **한 벌 저장**(`PUT …/{id}/items?rowVer=` — 머리 rowVer 로 동시 편집 409, 서버가 id 로 추가·수정·삭제, 다른 점검표 id 섞이면 400, 순번 중복 칸 오류). 리비전 없음(D8) — 실행 행이 스냅샷하므로 항목 삭제도 과거 기록을 바꾸지 않는다 |
| NUM 판정 | 하한·상한 중 하나 이상, 하한 < 상한, 규격 소수 ≤ 자릿수. 값이 **하한 ≤ 값 ≤ 상한이면 OK**(경계 포함), 값 소수가 자릿수를 넘으면 `SCALE`(반올림하지 않음) |
| 계획 | `cms_pm_plan`: 대상(설비 또는 금형·지그, 게이지 제외) × 점검표(대상 종류 같고, 클래스 한정 점검표는 그 클래스 설비만) × 실행 방식 WO(보전 작업지시 + 점검 실행)/CHECK(점검 실행만 — 일상점검, D7) × 달력 주기(DAY/WEEK/MONTH/YEAR × 간격 1~999) × 기준 FIXED(달력 고정)/FLOAT(완료 기준) + 첫 예정일·선행일(비우면 정책 `CMS_PM_LEAD_DAYS`, 기본 7)·담당. 사용량 주기는 C3 |
| 다음 예정일 | `PmDue`(순수 함수): FIXED 의 k 번째 예정 = 첫 예정일 + k×간격(월·년은 첫 예정일에서 다시 더해 1/31 → 2/28 → 3/31, 날이 밀리지 않음). **한 계획에 열린 예정은 하나** — 끝나야 다음을 만든다(밀린 예정이 쌓이지 않음). 완료 뒤 다음 = FIXED: max(예정일, 완료일)보다 뒤인 첫 고정 예정(늦게 하면 놓친 회차는 건너뜀) / FLOAT: 완료일 + 간격. 건너뛰기·취소 = 그 예정일에 한 것으로 보고 다음 계산(FLOAT 는 예정일 + 간격) |
| 계획 수정 | 주기·기준·첫 예정일을 바꾸면 열린 예정이 없을 때만 다음 예정일을 다시 계산(있으면 그 예정이 끝난 뒤 새 규칙으로). 사용 중지 = 새 예정 안 만듦(열린 것은 그대로) |
| 예정 생성 | `PmGenerator.generate()` — 사용 중 계획 중 **(다음 예정일 − 선행일) ≤ 대상 공장의 오늘**이고 그 예정이 아직 없는 것. WO 방식: 작업지시(PM·OPEN·제목 = 계획 이름·담당 = 계획 담당 또는 설비 기본 담당·기한 = 예정일) + 점검 실행, CHECK 방식: 점검 실행만. 멱등 = `unique(계획, 예정일)` 두 곳(점검 실행·작업지시) + 계획 행 잠금. 정기 작업 `CmsJobs`(매시 5분, 회사마다 `JobLock`) + 화면의 "지금 생성"(같은 함수) |
| 점검 실행 | `cms_chk_run`(계획·작업지시·대상·점검표·예정일·상태 OPEN/DONE/SKIPPED·수행 시각·수행자·결과 OK/NG) + `cms_chk_result`(항목 스냅샷 + 값·판정). 결과는 OPEN 에서만 저장(트리거), 판정은 서버(NUM 은 값으로, OKNG 는 사람이, TEXT 는 판정 없음). 완료 = 필수 항목 모두 결과 있음(아니면 칸 오류 `results[순번]`), 하나라도 NG 면 결과 NG |
| 완료 경로 | CHECK 방식: 점검 실행 화면에서 완료. WO 방식: 작업지시 완료(WIP → DONE)가 같은 트랜잭션에서 점검 실행을 완료 — 필수 결과가 없으면 작업지시도 완료 안 됨. 두 경로 모두 계획 `last_done_date`·다음 예정일 갱신 |
| 건너뛰기 | 점검 실행 SKIPPED(사유 필수, DELETE 권한) — WO 방식은 작업지시 취소가 점검 실행 건너뛰기를 함께. 다음 예정 계산 |
| NG 후속 | 점검 실행의 NG 항목에서 "후속 작업지시" = 고장 수리(BM·OPEN), 현상 글 = 항목 이름·값, 상위 작업지시(WO 방식) + 출처 점검 실행(`cms_mwo.src_run_id`) |
| 기한 준수 | 예정 상태 `PmDue.state`: 오늘 < 예정 − 선행 UPCOMING / ≤ 예정 DUE / 그 뒤 OVERDUE. 완료가 예정 + 정책 `CMS_PM_GRACE_DAYS`(기본 0) 안이면 준수(점검 실행 목록에 표시) |
| 클래스 일괄 | `POST …/pm-plans/bulk` — 클래스의 사용 중 설비마다 계획 생성(코드 = `점검표코드-설비코드`), 이미 있는 코드는 건너뜀(만든 수·건너뛴 수 응답). 만든 뒤는 개별 계획(D5) |

### 4.4 C3 상세 — 사용량 카운터·사용량 주기 (V065(임시 V9600), 2026-10-21)

**만든 것**: `cms_counter`(카운터)·`cms_counter_reading`(읽기 — 원장 변형: 추가 + 무효만) + `cms_pm_plan` 사용량 칸(`counter_id`·`usage_interval`·`usage_lead`·`anchor_usage`·`next_due_usage`·`last_done_usage`) + 예정 사용량 키(`cms_chk_run.due_usage`·`done_usage`, `cms_mwo.due_ref_usage`) + 시스템 코드 `CMS_COUNTER_KIND`·`CMS_CYCLE_TYPE`. 순수 함수 `CounterTotals`(증가량·금형 수명)·`PmDue` 사용량 판정. 화면 `cms-counter`(새) + `cms-pm-plan`(주기 종류 칸)·`cms-chk-run`(예정 사용량 표시).

| 규칙 | 내용 |
|---|---|
| 카운터 | 대상(설비 또는 금형·지그, 게이지 제외) × 종류 SHOTS/CYCLES/RUN_HOURS/OTHER × 단위·자릿수(0~6). 코드·대상·종류·자릿수는 만든 뒤 고정(값의 뜻이 바뀜, 트리거 `ck_cms_counter_fixed`). 사용 중지 = 새 읽기만 막음 |
| 누적 | **언제나 Σ 증가량(무효 제외)** — 카운터 행에 누적 칸을 두지 않는다(캐시가 원장과 어긋날 일이 없게). 목록·계획·점검은 SQL 합, 2부 KPI 용 구간 합 `totalSince` |
| 읽기 입력 | "지금 계기 값" 또는 "증가량 직접" 중 하나(`GAUGE_OR_DELTA`). 계기 값의 증가량 = 이번 − 직전 계기 값(무효 제외 마지막 계기 값). **첫 계기 값은 기준(증가량 0)**, 시스템 전 누적·계기 고장 구간은 증가량 직접으로. 계기 값이 줄면 리셋 확인 없이는 `GAUGE_DECREASE`, 리셋이면 증가량 = 이번 계기 값(0 부터 다시 셈). 증가량 직접은 > 0, 리셋 불가 |
| 사슬 검증(DB) | 트리거가 카운터 행을 잠그고(같은 카운터 입력 줄 세움) 증가량이 직전 계기 값과 맞는지(`ck_cms_counter_reading_chain`)·줄었는데 리셋 아님(`…_decrease`)·읽은 시각이 마지막 유효 읽기보다 앞(`…_order`)을 막는다 — 직접 SQL 로도 사슬을 깨지 못한다 |
| 읽은 시각 | 대상 공장 현지 분 단위(비우면 지금), 미래(허용 5분 넘음) `FUTURE`, 마지막 읽기보다 앞 `BEFORE_LAST`(지난 사용량은 증가량 직접으로). 마지막 읽기와 **같은 분**이면 그 시각으로 맞춘다(C1 작업지시 시각과 같은 규칙) |
| 낡은 화면 | 입력이 `lastReadingId`(화면이 본 마지막 유효 읽기, 없으면 0)를 보내고 다르면 409 — 다른 사람이 그 사이 넣은 값을 기준으로 증가량이 계산되는 것을 막는다 |
| 무효 | DELETE 권한, 사유 필수, **마지막 유효 행부터만**(`VOID_ORDER`, 트리거 `…_void_order`) — 가운데 행을 빼면 다음 행의 증가량(직전 계기 값 기준)이 틀어진다. 무효 뒤 누적·계획 상태는 Σ 라 바로 다시 계산되고, 다음 계기 값은 무효 앞 계기 값에서 잰다. **이미 만든 예방점검 예정은 그대로**(D6 — 사람이 건너뛴다) |
| 금형 수명 | 금형·지그 카운터의 누적 vs P1 `life_shots`: 90% 이상 NEAR, 100% 이상 OVER — 화면 경고만(사용 중지 자동 없음). 90% 는 `CounterTotals.NEAR_RATIO` |
| 사용량 계획 | `cycle_type = USAGE` — 달력 칸(단위·간격·첫 예정일·다음 예정일·선행일)은 비우고(CHECK `ck_cms_pm_plan_cycle`) 카운터(같은 대상·사용 중)·간격(> 0)·선행 사용량(0 이상 간격 미만, 비우면 0)·첫 예정 사용량(등록 때 비우면 지금 누적 + 간격). **주기 종류·카운터는 만든 뒤 고정**(바꾸려면 새 계획). 수치 자릿수 = 카운터 자릿수. 클래스 일괄은 달력만(설비마다 카운터가 달라서) |
| 다음 예정 | `PmDue` 사용량 판정(달력과 같은 규칙을 누적에): FIXED = 첫 예정 + k×간격 중 max(예정, 완료 누적)보다 큰 첫 값(넘겨 쓴 회차는 건너뜀), FLOAT = 완료 누적 + 간격, 건너뛰기·취소 = 예정 사용량에 한 것으로 |
| 예정 생성 | 누적 ≥ 다음 예정 − 선행이고 열린 예정이 없으면 — **읽기 입력과 같은 트랜잭션에서**(`PmGenerator.generateForCounter`), 그리고 정기 작업·"지금 생성"(계획을 나중에 만든 경우). 예정일 = 대상 공장의 오늘, 멱등 키 = (계획, 예정일, **예정 사용량**) — 큰 입력으로 같은 날 두 번 도달해도 각각 한 번(`unique … NULLS NOT DISTINCT`, 달력 주기는 사용량이 비어 C2 와 같다) |
| 상태·준수 | 열린 예정: 누적 < 예정 − 선행 UPCOMING / ≤ 예정 DUE / 넘으면 OVERDUE. **기한 준수 = 완료 때 누적(`done_usage`) ≤ 예정 사용량** — 날짜 허용 일수(`CMS_PM_GRACE_DAYS`)는 쓰지 않는다(D16) |
| 잠금 순서 | 읽기: 카운터 → 계획. 정기 작업·완료: 계획(누적은 잠그지 않고 읽음). 카운터를 잡은 채 계획을 기다리는 방향만 있어 교착이 없다 |
| 2부 자리 | `source` AUTO(C10 자동 입력)·`totalSince`(구간 합) — 1부는 MANUAL 만 쓴다. 자동 입력도 같은 사슬·같은 `generateForCounter` 를 탄다 |

**D16(이 세션 결정 — 사용자 확인 2026-10-22: 추천안)**: 사용량 주기의 기한 준수는 "완료 때 누적 ≤ 예정 사용량" 으로 본다. 대안 "예정이 생긴 날 + 허용 일수" 는 선행 사용량으로 일찍 생긴 예정을 오히려 불리하게 만들고, 금형이 예정 타수를 넘겨 계속 돈 것(IATF 8.5.1.6 이 보려는 것)을 숨긴다. 대가: 선행 사용량 없이 수기로 띄엄띄엄 넣으면 생성 시점에 이미 넘겨 "늦음" 이 될 수 있다 — 화면 도움말이 선행 사용량을 권한다.

### 4.5 C4 상세 — 설비 예비품·작업지시 사용 예비품 (V073(임시 V9601), 2026-10-21)

**만든 것**: `cms_equip_spare`(설비 또는 설비 클래스 × 품목, 설치 수량·핵심 예비품)·`cms_mwo_part`(작업지시 사용 품목·수량·단위 스냅샷). 화면 `cms-spare`(새) + `cms-mwo` 사용 예비품 절.

| 규칙 | 내용 |
|---|---|
| 예비품 = 품목 | 새 마스터 없음(D9) — `mst_item` 그대로. 찾기는 사용 중 품목, 소모품(CON)을 먼저. 수량·안전재고·차감은 WMS |
| 연결 | 설비 한 대 또는 설비 클래스 중 정확히 하나(CHECK) × 품목, unique(대상, 품목) `NULLS NOT DISTINCT` — 같은 품목을 설비와 그 클래스에 따로 걸 수 있다(설비 쪽이 우선). 대상·품목은 만든 뒤 고정(`ck_cms_equip_spare_fixed`), 삭제 없음(사용 중지) |
| 새 참조만 차단 | 등록·다시 사용(N → Y) 때만 사용 중 설비·클래스·품목(트리거). 품목이 나중에 사용 중지돼도 이미 있는 연결은 그대로 보이고 고칠 수 있다(17 2장 "사용 중지 = 새 참조만 차단") |
| 수량 | `numeric` + `BigDecimal`, 자릿수 = 품목 단위(공통코드 `UNIT` attrs.scale, 없으면 6), 반올림 없이 `SCALE`. 응답은 글자 |
| 사용 기록 | 작업지시 OPEN·WIP 에서만 추가·삭제(트리거가 작업지시 행을 잠가 완료와 줄 세움 — 공수와 같은 규칙), 수정 없음, **완료 뒤 불변**. 단위는 그때 품목 기본 단위로 스냅샷(뒤에 품목 단위가 바뀌어도 기록 뜻 유지, 트리거가 "지금 품목 단위와 같음"을 입력 때 확인) |
| 후보 | 작업지시 대상 설비의 연결 + 그 클래스 연결(사용 중 연결·사용 중 품목, 같은 품목은 설비 쪽 하나). 후보 밖 품목도 코드로 넣을 수 있다(고장 때 예상 못 한 부품). 금형·지그 작업지시는 후보 없음 |
| 이력 | 기록일 기간(회사 시간대, 필수·최대 366일) + 설비·클래스(그 클래스 설비들의 작업지시)·품목. 설비 연결은 그 설비, 클래스 연결은 클래스 기준으로 연결 화면에서 바로 |
| WMS 경계 | `cms_mwo_part` 가 P4 재고 원장의 출고 소스(6장) — WMS 설계와 맞출 때 아웃박스 이벤트 또는 WMS 가 읽어 가기 중 결정(지금은 기록만) |

### 4.6 C5 상세 — 설비 보안 대장 (V076(임시 V9602), 2026-10-21)

**만든 것**: `cms_equip_sec`(설비 1:1) + 코드 `CMS_SEC_LEVEL`(SL0~SL3)·`CMS_REMOTE_ACCESS`·`CMS_SEC_WARN`(경고 이름) + 정책 `CMS_SEC_SCAN_MAX_DAYS`(기본 90)·`CMS_SEC_REVIEW_DAYS`(기본 365, 새로 둠 — "미검토" 판정 주기) + 순수 함수 `SecWarn`. 화면 `cms-equip-sec`(SEARCH·SAVE·EXCEL).

| 규칙 | 내용 |
|---|---|
| 대장 | 사용 중 설비 전체에 보안 행을 붙여 보여 준다(행 없음 = **미등록** 경고). 사용 중지 설비는 보안 행이 있을 때만 남고(이력), 새 등록은 막는다(트리거). P1 `mst_equip` 은 고치지 않는다(D11·D15) |
| 저장 | 처음 저장 = rowVer 없이(이미 있으면 409, 동시 처음 등록은 unique → 409), 그 뒤 rowVer. 설비 고정(트리거), 삭제 없음 |
| 칸 규칙 | 검사 도구는 검사일이 있을 때만(CHECK + 칸 오류), 원격 접속 "기타"는 방식 글 필수(CHECK), 등급 SL0~SL3, 검사일·검토일은 미래 불가(설비 공장의 오늘) |
| 경고(계산, 저장 안 함) | `SecWarn.of` — 미등록 / 지원 종료(오늘 > 지원 종료일) / 검사 기한 경과(**네트워크 연결 설비만**, 검사 없음 또는 오늘 > 검사일 + 정책 주기) / 등급 미달(현재 < 목표, 둘 다 있을 때) / 미검토(검토일 없음 또는 오늘 > 검토일 + 정책 주기). **날짜 경계는 모두 "오늘 = 기한이면 아직 아님"**(지원 종료일 = 지원되는 마지막 날). 오늘 = 설비 공장 시간대 |
| 조회·엑셀 | 경고 필터(전체·경고 있음·경고 종류), 엑셀은 같은 조건·같은 계산(경고 글자·검사 기한 칸 포함), 플랫폼 `ExcelExportService`(수식 주입 방지·건수 상한·회사 트랜잭션 안) |
| 등급 표기 | SEMI E187·IEC 62443 SL 이름만 쓴다 [확인 필요: 고객사 감사 양식의 등급 정의] |

### 4.7 C6 상세 — 위험성평가 (V083, 2026-10-21)

**만든 것**: `cms_risk_assess`(머리)·`cms_risk_item`(유해위험요인)·`cms_risk_member`(참여·주지) + 코드 6그룹(유형·방법·상태·수준·유해위험요인 분류·참여 역할) + 정책 `CMS_RA_MATRIX`(3X3/5X5)·`CMS_RA_APPROVAL`(Y) + 보존 클래스 `SAFETY_RECORD`(발생 기준, 최소 3년·기본 5년, 자동 삭제 안 함) + 채번 `CMS_RA`(기본 `RA-{yyyy}-{seq:3}`, 해마다) + 결재 문서 유형 `CMS_RA` + 순수 함수 `RiskMatrix`. 화면 `cms-risk`.

| 규칙 | 내용 |
|---|---|
| 상태 | `DRAFT → IN_APPROVAL → RELEASED → OBSOLETE`, `IN_APPROVAL → DRAFT`(반려·회수), `DRAFT → RELEASED`(결재 없이), `DRAFT → OBSOLETE`(버림) — 전이 표 트리거(55000) |
| 확정 = 결재 | 정책 Y: 결재선 필수 → IN_APPROVAL + 상신, 승인 때 같은 트랜잭션에서 확정(`RiskReleaser`), 반려·회수 = 작성 중. N: APPROVE 권한으로 바로 확정(PMS 게이트와 같은 방식) |
| 확정 검사 | `RiskMatrix.releaseProblems` — 항목 하나 이상, **중간 이상 위험은 개선 대책·담당·기한**, **근로자(또는 근로자 대표) 참여 기록**(참여일). 칸 오류(항목 순번 = 행 키) |
| 수준 | 빈도·강도법: 가능성 × 중대성(1~행렬 크기) → 3X3 1~2 낮음·3~4 중간·6~9 높음 / 5X5 1~4·5~12·15~25 [확인 필요: 사업장 기준표]. 3단계 판단법: 수준을 고른다. **행렬은 평가를 만들 때 정책을 스냅샷**(`matrix_cd`) — 정책이 바뀌어도 그 평가는 그대로, 복사본은 원본 행렬을 잇는다. 방법·행렬은 만든 뒤 고정 |
| 확정본 불변(D17) | RELEASED 뒤에는 머리·요인·참여 내용 불변(트리거) — **개선 진행 칸(완료일·개선 후 위험성)과 주지일만** 채운다(완료일을 다시 비우는 정정도 허용 — 이력은 감사 로그). 개선 후 위험성은 완료했을 때만(CHECK). 개선은 확정 뒤에 하는 일이라 "확정본 불변" 과 "개선 기록" 을 칸으로 나눴다 |
| 지연 | 개선 기한 < 오늘(평가 공장 시간대)이고 미완료 = 지연. 목록에 개선 미완·지연 건수, 상세 항목에 표시 |
| 새 평가 | 확정본만 복사 원본. 복사 = 작성 중 새 평가(`prev_ra_id`) + 요인 복사: **끝낸 개선 대책은 "현재 조치" 로 붙고 개선 후 위험성이 새 위험성**, 못 끝낸 대책은 이어 간다. 참여자는 새로. 새 평가가 확정되면 이전 확정본은 같은 트랜잭션에서 OBSOLETE |
| 같은 회사 | 공장·설비·담당·참여 사용자·이전 평가는 같은 회사(트리거), 요인·참여는 평가 행을 잠가 상신·확정과 줄 세움 |
| 엑셀 | 위험성평가표(평가 하나의 요인 — 플랫폼 내보내기) |
| 남은 것 | ~~증빙 첨부~~ → C12(요인마다 공용 첨부, `RiskFileLock`), ~~근로자 참여~~ → C19(`mst_worker` 고르기, 13.6). 요인 삭제 때 그 첨부 정리 → 13장 12번(C24) |

**D17(이 세션 결정 — 상위 검토 통과 2026-10-21, 확정 뒤 완료일 비우기 = 정정 허용이 의도)**: "확정본 불변" 은 평가 내용(요인·수준·대책·담당·기한·참여)에만 걸고, 확정 뒤에 생기는 **개선 진행(완료일·개선 후 위험성)·주지일** 은 확정본에 기록한다. 대안 "개선 기록을 별도 표로" 는 표가 하나 늘고 엑셀 위험성평가표가 두 표를 합쳐야 한다. 수정 이력은 감사 로그가 남긴다.

## 5. 화면

| 화면 | prog_cd | 액션 | 메모 |
|---|---|---|---|
| 고장 코드 | `cms-fail-code` | SEARCH·SAVE·DELETE·EXCEL | 유형 탭(현상·원인·조치), 상위 분류·설비 클래스 필터 |
| 설비 프로필 | `cms-equip-profile` | SEARCH·SAVE·EXCEL | 설비 목록(P1) + 중요도·핵심·담당 칸, 일괄 지정 |
| 고장 신고 | `cms-breakdown` | SEARCH·SAVE | **좁은 화면 우선**(390 폭): 설비 찾기(코드·이름·위치, `?equip=` 로 설비 고정 — QR 은 이 URL 만 담는다, 인쇄는 하지 않음) → 현상 타일(자주 쓰는 현상 코드) → 사진 → 신고. 내 신고 목록·상태 |
| 보전 작업지시 | `cms-mwo` | SEARCH·SAVE·APPROVE·DELETE·EXCEL | 목록(상태·유형·기한 지연 배지) + 상세 + 동작 버튼(전이 표대로) + 공수 표 + 점검 결과 표(PM) + 사용 예비품 표(C4) + 첨부 |
| 점검표 | `cms-chk-tmpl` | SEARCH·SAVE·DELETE | 머리 + 항목 표(한 벌 저장, 라우팅 단계 방식) |
| 예방점검 계획 | `cms-pm-plan` | SEARCH·SAVE·DELETE·EXCEL | 목록(다음 예정·상태 배지 UPCOMING/DUE/OVERDUE) + 상세 + **예정 미리보기**(앞으로 12회) + 달력 보기(월, 읽기 전용) |
| 점검 실행 | `cms-chk-run` | SEARCH·SAVE | 오늘·지연 실행 목록 → 항목별 결과 입력(OK/NG 큰 버튼, 숫자 칸 판정 즉시 표시), NG → "후속 작업지시" |
| 카운터 | `cms-counter` | SEARCH·SAVE·DELETE(무효) | 카운터 목록(누적·마지막 읽기·금형 수명) + 읽기 기록(이 행까지 누적, 마지막 행 무효), "지금 계기 값" 입력 → 증가량 미리보기, 줄면 리셋 확인 칸 |
| 설비 예비품 | `cms-spare` | SEARCH·SAVE·EXCEL | 연결 표(검색어에 품목 = 쓰는 설비, 역방향) + 상세 + 사용 이력(기간). 엑셀 = 연결 표·사용 이력(C15, 13.3) |
| 설비 이력 카드 | `cms-equip-card` | SEARCH·EXCEL | 설비 하나: 프로필·설치 자산(P1 설치 이력)·작업지시·점검 실행·사용 예비품·카운터 — 읽기 전용, 기간 조건 필수. C1 은 설비 프로필 상세 아래 카드(설치 자산·최근 1년 작업지시, 지금도 그대로), **C17 에서 단독 화면**(13.5) — 프로필 상세의 "이력 카드 열기" |
| 설비 보안 대장 | `cms-equip-sec` | SEARCH·SAVE·EXCEL | 사용 중 설비 전체 + 보안 행, 경고 필터(미등록·지원 종료 OS·검사 기한 경과·등급 미달·미검토·경고 있음), 엑셀 |
| 위험성평가 | `cms-risk` | SEARCH·SAVE·APPROVE·DELETE·EXCEL | 머리 + 유해위험요인 표 + 참여·주지 표 + 개선 대책 지연 배지, 새 평가 = 이전 확정본 복사 |

메뉴 그룹: 새 상위 메뉴 "설비관리(CMS)" — 설비·물리 자산·장비 계층 화면은 기준정보에 그대로 두고 설비관리에서도 바로가기(29 D6 과 같음, 8장 D14).

## 6. 병행 — 겹치는 곳과 규칙

| 대상 | 겹침 | 규칙 |
|---|---|---|
| Flyway `V###` | P2·QMS·WMS·SRM·PMS 와 다음 번호가 겹친다 | 이 브랜치(`claude/p5-cms-d2b0e3`)는 다음 번호(V041~)로 개발, **main 에 늦게 들어가는 쪽이 병합 직전 번호를 다시 매긴다**(29 D10). 로컬 DB 는 `docker compose down -v` 로 초기화 |
| `messages*.properties`, `HbErrorCode`, `Policies`, `DemoDataSeeder`, `sys_prog`·메뉴 시드, `TenantCatalogInspector`·보존 표 | 여러 브랜치가 끝에 줄 추가 | 병합 때 양쪽 다 남김. CMS 키는 `cms.` / `js.cms.` 접두어로 모은다 |
| `TESTING.md`, SKILL.md §2·§3, docs/00·01 표 | 기록 추가 | CMS 는 각자 절, 병합 때 둘 다 |
| 공통 JS(`hb.*`)·CSS | 고칠 일이 생기면 | **고치지 않는다** — 화면 JS 안에서 해결, 공통화는 병합 뒤 |
| P1 테이블 | 설비·자산·품목·클래스 | 읽기·참조만(3장). 칸이 필요하면 CMS 1:1 테이블 |
| **P2 6단계** | `cms_*` 이름 공간 공유 | 25 4장 6단계 이름은 만들지 않는다(0장 표). 알람 원장은 25 4.1 이 "P5 CMS 용 원장(스키마만 P2)" 이라 소유가 애매 — **2부 착수 때 P2 6단계 결과를 보고 정한다**(P2 가 만들었으면 그대로 쓴다) |
| P2 `mst_worker`(1단계 병합됨) | 신고자·위험성평가 참여자 | 1부 신고자는 `sys_user`. POP 신고(작업자 배지)는 P2 5a 병합 뒤 2부. 위험성평가 참여자는 `worker_id` 칸만 열어 둔다 |
| QMS(29) | 게이지 교정, 관리문서 | GAUGE 는 PM 대상 아님. 설비 매뉴얼·도면은 첨부로만(관리문서 `mst_doc` 연결은 병합 뒤) |
| WMS(30) | 예비품 수량 | 1부는 수량을 세지 않는다. `cms_mwo_part` 를 P4 재고 원장의 출고 소스로 넘긴다(D9) — WMS 설계와 맞출 때 이 문서 6장 갱신 |
| SRM(31) | 예비품·외주 수리 구매 | 1부는 구매 요청을 만들지 않는다. 외주 수리 업체는 거래처(`mst_cust`) 참조만 |
| PMS(32) | 설비 개선·도입 프로젝트 | 겹치지 않음. CM 작업지시를 프로젝트에 연결하는 칸은 병합 뒤 필요하면 |
| 다른 회사 데이터 | 모든 테이블 | A사로 B사 설비·코드·품목·사용자를 참조·조회·수정 시험(같은 회사 트리거 + RLS) |

## 7. 진행

| 단계 | 상태 | 날짜 | 테스트 |
|---|---|---|---|
| C2 점검표·예방점검(달력) | | | |
| C1 고장 코드·설비 프로필·보전 작업지시(V041) | ✅ | 2026-10-20 | `./gradlew check` 723건 통과, 신규 `CmsMwoTest` 11. 브라우저(별도 DB) 1366·390 — 화면 결함 4건 발견·수정(TESTING.md). 남은 것: 첨부 서버가 대상 상태를 모름, 공용 `.hb-form` 1fr 넘침(공통 CSS) |
| C2 점검표·예방점검(달력)(V042) | ✅ | 2026-10-20 | `./gradlew check` 734건 통과, 신규 `PmDueTest` 6(DB 없음)·`CmsPmTest` 5. 브라우저(임시 DB) 1366·390 — 화면 결함 2건 발견·수정(항목 편집을 블록으로, 규격 자릿수 표시). 월 달력 보기·사용량 주기는 C3 이후 |
| C3 사용량 주기(V065) | ✅ | 2026-10-21 | `./gradlew check` 1,160건 통과, 신규 `CounterTotalsTest` 5(DB 없음)·`CmsCounterTest` 7. 브라우저(ZT 데이터) 1366·390 — 화면 결함 3건 발견·수정(TESTING.md). D16(사용량 기한 준수 기준) 사용자 확인 2026-10-22: 추천안 |
| C4 예비품(V073) | ✅ | 2026-10-21 | `./gradlew check` 1,164건 통과, 신규 `CmsSpareTest` 4. 브라우저(ZT 데이터) 1366·390 — 화면 결함 1건 발견·수정. 엑셀·WMS 출고 연계는 뒤로 |
| C5 설비 보안 대장(V076) | ✅ | 2026-10-21 | `./gradlew check` 1,342건 통과(main 병합 뒤), 신규 `SecWarnTest` 4(DB 없음)·`CmsEquipSecTest` 4. 브라우저(ZT 데이터) 1366·390, 엑셀 확인 |
| C6 위험성평가(V083) | ✅ | 2026-10-21 | `./gradlew check` 1,350건 통과, 신규 `RiskMatrixTest` 4(DB 없음)·`CmsRiskTest` 4. 브라우저(ZT 데이터) 1366·390 — 결함 1건 발견·수정. D17 상위 검토 통과. **1부 끝** |
| C7 상태 사유·비가동 사유 검토(V093) | ✅ | 2026-10-21 | `./gradlew check` 1,456건 통과, 신규 `CmsStateReviewTest` 6·`AlarmSinkContractTest` 2(DB 없음). 브라우저(ZT 데이터) 1366·390 — 결함 1건 발견·수정(조회 패널 스크립트 누락). D18~D24·Q1~Q3 채택 반영 |
| C8 설비 알람(V097) | ✅ | 2026-10-21 | `./gradlew check` 1,462건 통과, 신규 `CmsAlarmTest` 6. 브라우저(ZT 데이터) 1366·390. 수집 호출 줄(Q2)은 MES 세션 몫 |
| C9 설비 KPI(V103) | ✅ | 2026-10-21 | `./gradlew check` 1,530건 통과, 신규 `EquipKpiTest` 4(DB 없음)·`CmsKpiTest` 5. 브라우저(ZT 데이터) 1366·390 — 결함 1건 발견·수정(미수집 부풀림). MES 검토로 정정 실적 누락 수정 |
| C10 카운터 자동 입력(V105) | ✅ | 2026-10-21 | `./gradlew check` 1,554건 통과, 신규 `CmsCounterAutoTest` 5. 브라우저(ZT 데이터) 1366·390 — 결함 1건 발견·수정(자릿수). D25 사용자 확인(2026-10-21). **2부 끝** |
| C11 작업지시 수집 정지·KPI 추이·엑셀(V115(임시 V9609)) | ✅ | 2026-10-22 | `./gradlew check` 1,647건 통과, `CmsKpiTest` +1·`CmsStateReviewTest` 확장. 브라우저(ZT 데이터) 1366·390. D26·D27 사용자 확인 2026-10-22: 추천안 |
| QA G-2 잔여 — 설비 보안 grid 메모리 경로도 SQL 순서(마이그레이션 없음) | ✅ | 2026-10-28 | `./gradlew check` **2,711건, 실패 0(건너뜀 15)**, JDK 21, `CmsEquipSecTest` +1. 13.18 |
| QA G-2 설비 보안 grid SQL 정렬(마이그레이션 없음) | ✅ | 2026-10-28 | `./gradlew check` **2,710건, 실패 0(건너뜀 15)**, JDK 21, `CmsEquipSecTest` 그대로·QA `GridScreensQaTest` G-2 기대 갱신. 13장 19번, 13.18 |
| C30 점검 실행 grid 끝 쪽 — 계획 조인 제거(마이그레이션 없음) | ✅ | 2026-10-26 | `./gradlew check` **2,585건, 실패 0(건너뜀 15)**, JDK 21, 새 시험 없음(`CmsDeepPageTest`·`CmsPmTest`·`CmsGridExcelTest` 가 같은 결과를 덮음), 실측 `CmsDeepPagePerfTest` H4. 13장 18번, 13.17 |
| C29 카운터 grid 남은 비용 — 안 A(V192·임시 V9622) | ✅ | 2026-10-26 | `./gradlew check` **2,575건, 실패 0(건너뜀 15)**, JDK 21, 새 시험 없음(`CmsCounterTest`·`CmsDeepPageTest` 가 합·수·마지막 계기 값을 덮음), 실측 `CmsDeepPagePerfTest` M1. 13장 17번, 13.16 |
| C28 POP 사진 한도 계약 — CMS 쪽(마이그레이션 없음) | ✅ | 2026-10-26 | `./gradlew check` **2,573건, 실패 0(건너뜀 15)**, JDK 21, `CmsPopPhotoTest` +1(4), 공용 `FileAttachAsTest`·`FileApiTest` 그대로. 화면 없음(MES 몫). 13장 16번, 13.15 |
| C27 예방점검 계획 달력 보기(마이그레이션 없음) | ✅ | 2026-10-26 | `./gradlew check` **2,543건, 실패 0(건너뜀 14)**, JDK 21, 신규 `CmsPmCalendarTest` 4, 기존 `CmsPmTest`·`PmDueTest` 그대로. 브라우저(ZT 계획 3개) 1366 달력 칸·390 날짜 목록 — 결함 2건 발견·수정(testing/cms.md). 13장 15번, 13.14 |
| C26 깊은 offset — 알람 이력·점검 실행·카운터·예방점검 계획 grid 지연 조인(V180·임시 V9621) | ✅ | 2026-10-24 | `./gradlew check` **2,435건 통과(건너뜀 6 — 실측 4 포함)**, JDK 21, 신규 `CmsDeepPageTest` 3·실측 `CmsDeepPagePerfTest`(-Dhb.perf) 4. 전후 수치 13.13. 13장 14번 |
| C25 POP 고장 신고 사진 — CMS 쪽(계약 구현, V174·임시 V9620) | ✅ | 2026-10-24 | `./gradlew check` **2,383건 통과(건너뜀 2)**, JDK 21, main(78db78a, #471) 병합 뒤, 신규 `CmsPopPhotoTest` 3, 기존 `CmsBreakdownReportTest` 5 그대로. 화면 없음(MES 몫 — 사무 "신고 사진"은 기존 화면). 13장 13번, 13.12 |
| C24 위험성평가 요인 삭제 때 첨부 정리(마이그레이션 없음) | ✅ | 2026-10-23 | `./gradlew check` **2,317건 통과(건너뜀 2)**, JDK 21, main(#436) 병합 뒤, `CmsRiskTest` +1(6), `CmsRiskMemberWorkerTest` 그대로. 브라우저(ZT 평가) 1366·390. 13장 12번, 13.11 |
| C23 grid 엑셀 통일 3묶음 — 알람 정의·상태 사유·점검표(V166·임시 V9619) | ✅ | 2026-10-23 | `./gradlew check` **2,302건 통과(건너뜀 2)**, JDK 21, main(7850ce08) 병합 뒤, 신규 `CmsGridExcel3Test` 4. 브라우저(ZT 데이터) 1366·390. 13장 11번, 13.10 |
| C22 작업지시 "POP 신고" 필터(마이그레이션 없음) | ✅ | 2026-10-23 | `./gradlew check` **2,273건 통과(건너뜀 2)**, JDK 21, main(#414) 병합 뒤, 신규 `CmsMwoPopFilterTest` 2, 기존 `CmsMwo*Test`·`CmsGridExcelTest` 그대로. 브라우저(ZT 단말·작업자) POP 신고 → CMS 1366·390. 13장 10번, 13.9 |
| C21 grid 엑셀 통일 2묶음 — 카운터·알람 이력·상태 검토(V155·임시 V9618) | ✅ | 2026-10-23 | `./gradlew check` **2,230건 통과(건너뜀 2)**, JDK 21, main(39a2438a) 병합 뒤, 신규 `CmsGridExcel2Test` 4. 브라우저(ZT 데이터) 1366·390. 13장 9번(2/2), 13.8 |
| C21 grid 엑셀 통일 1묶음 — 작업지시·점검 실행(V152·임시 V9617) | ✅ | 2026-10-23 | `./gradlew check` 2,204건 통과(건너뜀 2), 신규 `CmsGridExcelTest` 3, 기존 `CmsMwoExcelTest` 2 그대로. 브라우저(ZT 데이터) 1366·390. 13장 9번(1/2), 13.7 |
| C20 POP 고장 신고 계약 — CMS 쪽(V151·임시 V9616) | ✅ | 2026-10-23 | `./gradlew check` 2,194건 통과(건너뜀 2), 신규 `CmsBreakdownReportTest` 5, 기존 `CmsMwoTest` 11 그대로. 13장 8번, 11.16 |
| C19 위험성평가 참여자 작업자(V150·임시 V9615) | ✅ | 2026-10-23 | `./gradlew check` 2,155건 통과(건너뜀 2), 신규 `CmsRiskMemberWorkerTest` 2, 기존 `CmsRiskTest` 5 그대로. 브라우저(ZT 작업자) 1366·390. 13장 7번 |
| C18 예비품 반납 누계 — CMS 쪽(계약 + 화면, 마이그레이션 없음) | ✅ | 2026-10-23 | `./gradlew check` 2,129건 통과(건너뜀 3), 신규 `CmsSpareReturnTotalTest` 3(계약은 가짜 — WMS 구현 PR 따로). 브라우저(ZT 데이터) 1366·390 — 창고 구현 전이라 "모름" 표시 확인. 13장 6번, 11.15.2 |
| C17 설비 이력 카드 단독 화면(V146·임시 V9614) | ✅ | 2026-10-23 | `./gradlew check` 2,107건 통과(건너뜀 2), 신규 `CmsEquipCardTest` 5·`GridSweepQaTest` 필수 조건 한 줄. 브라우저(ZT 데이터) 1366·390 — 결함 3건 발견·수정(testing/cms.md). 13장 5번 |
| C16 기준정보 엑셀 3종(V143·임시 V9613) | ✅ | 2026-10-23 | `./gradlew check` 2,069건 통과(건너뜀 2), 신규 `CmsMasterExcelTest` 5. 브라우저(ZT 데이터) 1366·390 — grid 메뉴 엑셀 3화면. 13장 4번 |
| C15 설비 예비품 엑셀(V137, V137·임시 V9611) | ✅ | 2026-10-22 | 신규 `CmsSpareExcelTest` 2. 브라우저(ZT 데이터) 1366·390 — 두 파일 내려받기(이름 구분). 13장 3번 |
| C14 보전 작업지시 엑셀(V136, V136·임시 V9610) | ✅ | 2026-10-22 | 신규 `CmsMwoExcelTest` 2. 브라우저(ZT 데이터) 1366·390 — 내려받기·파일 이름·현지 시각 확인. 13장 2번 |
| C13 작업지시 첨부 서버 잠금(마이그레이션 없음) | ✅ | 2026-10-22 | 신규 `CmsMwoFileTest` 3. 브라우저(ZT 데이터) — 확인된 작업지시는 업로드 칸 숨김·API 409. 13장 1번 |

## 8. 결정 필요 (사용자)

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D1 | 고장 코드 구조 | **현상·원인·조치 3종 회사 코드 + 전역 상위 분류 `CMS_FAIL_CAT`(회사 추가 불가) + 설비 클래스 한정(선택)** — 불량 유형(21)과 같은 방식, 회사 간 비교·예지보전 라벨(03 2.7)이 된다 | 회사 코드 한 목록(구분 없음) — 단순하지만 "원인"만 모아 볼 수 없다 / ISO 14224 표를 시드 — 원문 저작권·과함 |
| D2 | 작업지시 유형·상태 | **BM·CM·PM 한 테이블, `REQ → OPEN → WIP → DONE → CLOSED` + `CANCELLED`**, 완료 뒤 불변, 확인(CLOSED)은 내용 불변 표시 | 상태 3개(OPEN/DONE/CANCELLED) — 신고·접수 지연을 못 본다 / 유형별 테이블 — 이력 카드·KPI 가 합집합이 된다 |
| D3 | 보전 담당·신고자 | **`sys_user`**(보전 기사·관리자는 사무 계정). 1부 신고도 사무 계정(신고 전용 권한 화면). 작업자 배지 신고는 P2 5a 뒤 2부 | 신고에 처음부터 `mst_worker` — POP 이 아직 없어 화면이 없다 |
| D4 | 사용량 주기 1부 포함 | **포함 — 수기 카운터(구간 증가량 저장)**, 2부는 같은 카운터에 AUTO 출처만 더한다. 금형 타수 PM(IATF 8.5.1.6 금형 관리)이 1부에서 동작 | 1부는 달력만, 사용량은 2부 — C3 를 통째로 미루고 2부에서 카운터 설계 |
| D5 | PM 대상·계획 단위 | **설비 + 금형·지그(GAUGE 제외), 계획은 대상 개별.** 클래스 단위는 "클래스의 설비들에 계획 일괄 생성" 도구로(만든 뒤는 개별) | 클래스 계획을 동적 상속 — 설비마다 예외(주기만 다름)를 두기 어렵고 이력이 섞인다 |
| D6 | 예정 생성 방식 | **매일 정기 작업이 선행일(`CMS_PM_LEAD_DAYS`) 안의 예정을 작업지시·점검 실행 행으로 만든다**(멱등 unique), 지난 예정은 자동 취소 안 함 | 조회 때 계산만, 사람이 착수할 때 행 생성 — 행은 적지만 "기한 준수율"·담당 배정·알림이 어렵다 |
| D7 | 일상점검(자주보전) | **작업지시 없이 점검 실행(`exec_mode = CHECK`)만** — 설비 × 날마다 작업지시가 생기면 목록이 묻힌다. 보전 PM 은 작업지시 + 점검 실행 | 모두 작업지시 — 모델은 하나지만 작업지시 수가 수십 배 |
| D8 | 점검표 리비전 | **없음** — 감사 이력 + 생성된 점검 실행 행이 항목·기준을 스냅샷(특성 마스터 21 과 같은 판단) | BOM 규칙 리비전(작성 중·확정) — 점검표 한 장 고치는 데 확정 절차가 생김 |
| D9 | 예비품·재고 경계 | **품목 마스터(유형 CON 또는 품목 클래스) 재사용 + CMS 는 설비 연결·사용 기록만, 수량·안전재고·차감은 P4 WMS**(사용 기록이 출고 소스) | CMS 자체 예비품 마스터 + 간이 재고 원장 — 혼자 완결되지만 WMS 와 재고가 두 벌 |
| D10 | MTBF/MTTR 1부 | **1부에 두지 않는다** — 이력 카드는 건수·정지 시간 합만. 정식 정의는 2부(상태 원장 + `sys_kpi_def`) 한 곳 | 1부에 작업지시 시각 기준 MTBF/MTTR — 빨리 보이지만 2부와 숫자가 달라 "어느 게 맞나" 문제 |
| D11 | 설비 보안 대장(27 M7) | **1부 C5 로 넣는다, 1:1 고정 칸 테이블 `cms_equip_sec`** — 클래스와 무관하게 "지원 종료 OS·검사 기한" 경고를 같은 쿼리로 | 7b 클래스 속성(attrs)으로 — 클래스마다 정의해야 하고 경고 쿼리가 속성 코드 약속에 기댐 / 2차(반도체)로 미룸 |
| D12 | 위험성평가(27 M10) | **1부 C6 로 넣는다(CMS 메뉴 아래 "안전"), 테이블 `cms_risk_*`**, 방법은 빈도·강도법(행렬 정책)과 3단계 판단법 2가지 [확인 필요: 고시 허용 방법 최신본], 확정은 결재(정책 `CMS_RA_APPROVAL`) | 하지 않음 / 별도 안전보건(EHS) 모듈·접두어 — 공정·작업 전체가 대상이라 개념상 맞지만 모듈이 하나 늘어남 |
| D13 | 보존 클래스 | **신설 2개**: `MAINT_RECORD`(KEEP), `SAFETY_RECORD`(OCCURRED_AT, 최소 3년·기본 5년 — 산안법 보존 3년, 중처법 대응 5년 [확인 필요]) | 기존 `MASTER`(KEEP) 재사용 — 위험성평가 보존 기간 근거가 안 보인다 |
| D14 | 메뉴 | **새 상위 메뉴 "설비관리(CMS)"** + 설비·물리 자산은 기준정보에 두고 바로가기 | 설비·물리 자산 화면을 설비관리로 이동 — P1 화면 이동이라 병행 중 충돌 |
| D15 | 설비 중요도·핵심 설비(IATF 8.5.1.5) | **CMS 1:1 테이블 `cms_equip_profile`** — P1 `mst_equip` 을 고치지 않는다 | `mst_equip` 컬럼 추가 — 자연스럽지만 P1 테이블 변경(병행 규칙 위반) / 7b attrs |

## 9. 하지 않을 것 (1부)

- 설비 상태 구간·연결·에너지 원장, Collector, 상태 타임라인, 알람, OEE·MTBF·MTTR — **P2 6단계·2부**
- 계측기(GAUGE) 교정·MSA — **QMS Q2a·Q2b**
- 예비품 재고 수량·안전재고·차감·발주 — **P4 WMS / SRM**
- 예지보전(알람 패턴·RUL), 진동 센서 연동 — **2차**(03 2.7), 결과 수신만 이후
- 설비 도입·감가상각·자산 회계 — ERP 영역
- POP·작업자 배지 고장 신고, 오프라인 신고 대기열 — P2 5a·5b 병합 뒤 2부
- QR·설비 라벨 인쇄 — URL 만, 인쇄 큐는 P4
- 외주 보전 업체 포털, 보전 작업 모바일 네이티브 앱
- 법정 안전검사(프레스·크레인 등) 전용 기능 — **PM 계획(달력 주기 + 점검표)으로 표현**하고 따로 만들지 않는다 [추정: 대상·주기는 고객마다 확인]
- 위험성평가의 화학물질·작업환경측정·MSDS — 범위 밖

## 10. 검토 결과 (원래 세션이 작성)

2026-10-20 사용자: "8장 D1~D15 추천대로 채택하고 C1(고장 코드·설비 프로필·보전 작업지시)부터 구현".

| # | 결정 | 반영 위치 |
|---|---|---|
| D1 | 고장 코드 = 현상·원인·조치 3종 회사 코드 + 전역 상위 분류 `CMS_FAIL_CAT` + 설비 클래스 한정(선택) | C1 |
| D2 | 작업지시 BM·CM·PM 한 테이블, `REQ → OPEN → WIP → DONE → CLOSED` + `CANCELLED`, 완료 뒤 불변 | C1, 4.1 |
| D3 | 보전 담당·신고자 = `sys_user`, 작업자 배지 신고는 2부 | C1 |
| D4 | 사용량 주기 1부 포함(수기 카운터, 구간 증가량) | C3 |
| D5 | PM 대상 = 설비 + 금형·지그(GAUGE 제외), 계획은 대상 개별 + 클래스 일괄 생성 도구 | C2 |
| D6 | 매일 정기 작업이 선행일 안 예정을 행으로 생성(멱등), 지난 예정 자동 취소 없음 | C2 |
| D7 | 일상점검 = 작업지시 없는 점검 실행(`exec_mode = CHECK`) | C2 |
| D8 | 점검표 리비전 없음, 실행 행이 스냅샷 | C2 |
| D9 | 예비품 = 품목 마스터 재사용, CMS 는 연결·사용 기록만, 수량은 P4 | C4, 6장 |
| D10 | MTBF/MTTR 은 2부 한 곳에서만 | 2부 C9 |
| D11 | 설비 보안 대장 C5, 1:1 `cms_equip_sec` | C5 |
| D12 | 위험성평가 C6, `cms_risk_*`, 빈도·강도법 + 3단계 판단법, 결재 정책 | C6 |
| D13 | 보존 클래스 `MAINT_RECORD`(KEEP)·`SAFETY_RECORD` 신설 | C1(MAINT_RECORD), C6 |
| D14 | 새 상위 메뉴 "설비관리(CMS)" + 설비·물리 자산 바로가기 | C1 |
| D15 | 설비 중요도·핵심 설비 = `cms_equip_profile` 1:1 | C1 |

## 11. 2부 설계 — 상태 타임라인·알람·KPI(OEE·MTBF·MTTR)·카운터 자동 (2026-10-21, **D18~D24·Q1~Q3 추천안 채택 2026-10-21**)

근거: 0장 표의 2부 범위, 25 4.8(MES 6단계 — 상태 구간·연결·에너지 원장, `/ext/v1/equip-events`, 화면 `cms-state`), 02 2.4(SEMI E10)·2.5(ISO 22400 "합산한 뒤 나눈다"), 03 2.7(반복 알람·예지보전 라벨), 1부 D10(MTBF/MTTR 은 2부 한 곳에서만)·C3(카운터 `source = AUTO` 자리)·C1(작업지시 정지 구간 칸).

### 11.1 경계 — MES 원장은 읽기만

| 원장·코드 | 주인 | CMS 2부가 하는 일 |
|---|---|---|
| `cms_equip_state_log`(상태 구간, "한 번만 닫기")·`cms_equip_conn_log`·`cms_energy_reading`·`cms_state_reason` 표 구조·`/ext/**`·`EquipEventService` | MES(6단계) | **읽기만**. 칸을 더하지 않는다 |
| `prd_result`(실적 — 설비·양품·불량·시각) | MES(5b) | 읽기만(성능·품질률, 카운터 자동 입력의 출처) |
| `cms_state_reason` **관리 화면** | 25 4.8 "사유 마스터 화면은 CMS 세션" | C7 에서 만든다(표는 MES 것, 화면·API 만 CMS) — **Q1** |
| 알람 원장 | 25 4.1 "P5 CMS 용 원장(스키마만 P2)" → 6단계는 만들지 않음(`ALARM` 은 `sys_inbox` 에 받기만) | C8 에서 CMS 가 만든다. 수집 이벤트를 원장으로 넘기는 한 줄은 MES 코드 — **Q2** |

**원칙**: 사유 사후 지정·수기 정지처럼 "구간에 뭔가 덧붙이는" 일은 MES 원장을 고치지 않고 **CMS 덧붙임 표**(구간 id 참조, 추가 + 무효만)로 한다. KPI 계산은 원장을 그대로 읽고 덧붙임을 겹쳐 본다.

### 11.2 단계

| 단계 | 범위 | 첫 화면 | 완료 기준 |
|---|---|---|---|
| **C7** | **상태 사유·타임라인 보강**: `cms_state_reason` 관리 화면(Q1), **사유 사후 지정** `cms_state_note`(구간 id × 사유·메모, 추가 + 무효만 — 원장 `reason_id` 가 비었거나 틀린 구간에 사람이 단 사유, KPI 는 원장 사유보다 이것을 먼저), **고장 작업지시 ↔ UDT 구간 연결** `cms_mwo_down`(작업지시 × 구간 id, 1부 `down_start/end` 수기 칸과 대조 표시), 타임라인 화면에 사유 지정·작업지시 연결 동작 추가(MES `cms-state` 화면은 그대로 두고 CMS 화면 `cms-state-review` 새로) | 비가동 사유 검토 | 닫힌 구간에만 지정, 같은 구간 유효 지정 하나(무효 후 다시), 다른 회사 구간·작업지시 거부(같은 회사 트리거), 원장 행 수정 0 |
| **C8** | **알람**: `cms_alarm_def`(회사 × 설비 클래스 × 알람 코드 → 이름·심각도·고장 코드(현상) 연결·자동 작업지시 여부), `cms_alarm_log`(원장 — 발생·해제 두 시각, 추가 + "한 번만 해제"), 반복 알람(같은 설비·코드가 기간 안 N회 — 정책) 표시·상위 알람(건수·정지 시간 순위), 심각도 CRITICAL + 자동 작업지시 = 고장 신고(BM·REQ) 같은 트랜잭션 | 알람 이력 | 해제 두 번·해제가 발생보다 앞 거부, 같은 이벤트 한 번(`sys_inbox`), 반복 판정 표 검산, 자동 작업지시 멱등 |
| **C9** | **KPI**: `sys_kpi_def` 대신 CMS 안 **판정 함수 `EquipKpi`**(순수) + 조회 API·화면. 가용률 = 가동(PRD) / (계획 시간 − 계획 정지 SDT), 성능 = (표준 사이클 × 생산 수) / 가동, 품질 = 양품 / 생산, **OEE = 세 값의 곱이 아니라 합산한 분자·분모로**(02 2.5 "합산한 뒤 나눈다"), MTBF = 가동 / 고장 횟수(UDT 구간 수, 사유 사후 지정 반영), MTTR = UDT 시간 합 / 고장 횟수, PM 준수율 = 기한 안 완료 / 끝난 예정(C2·C3 `onTime`). UNKNOWN(통신 끊김) 시간은 분모에서 빼고 따로 보인다 | 설비 KPI | 손 검산 표(하루 경계·공장 시간대 자정·열린 구간은 지금까지·UNKNOWN 제외), 기간 합산 = 일별 합산(분자·분모로), 설비 → 라인 합산이 평균의 평균이 아님 |
| **C10** | **카운터 자동 입력**: 정기 작업(`TenantRunner` + `JobLock`)이 워터마크 뒤 원장을 읽어 C3 카운터에 `source = AUTO` 증가량 행 — SHOTS·CYCLES ← `prd_result`(설비·양품+불량 수), RUN_HOURS ← 상태 구간 PRD 시간(닫힌 구간만). 금형은 그 시각 설치 이력(P1)의 설비 실적 → 설치된 금형 카운터 | (카운터 화면에 출처 표시) | 같은 원장 행 두 번 안 셈(워터마크 + 출처 키 unique), 정정(역분개) 실적은 음수가 아니라 다음 구간에서 빼지 않고 **보정 행**(D-후보), 사용량 예방점검이 같은 트랜잭션에서 생김(C3 `generateForCounter`) |

### 11.3 결정 필요 (사용자·상위 세션)

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D18 | 사유 사후 지정·작업지시 연결을 어디에 | **CMS 덧붙임 표(`cms_state_note`·`cms_mwo_down`)** — MES 원장을 고치지 않는다 | MES 원장에 `note_reason_id` 칸 추가(남의 표 변경, "한 번만 닫기" 원장에 수정 칸이 생김) |
| D19 | 수집기 없는 설비의 가용률 | **계산하지 않는다(빈칸 + "수집 없음")** — 작업지시 정지 구간(수기)은 고장 기록이지 가동 기록이 아니다 | 작업지시 정지 구간을 UDT 로 보고 나머지를 가동으로 — 숫자가 늘 좋게 나온다 |
| D20 | KPI 정의 위치 | **CMS 순수 함수 + 문서(이 절)** — 정의가 바뀌면 코드·시험이 함께 바뀐다. 회사별로 바꿀 것(반복 알람 N, 계획 시간 근거)은 정책 | `sys_kpi_def` 표(정의를 데이터로) — 수식을 데이터로 두면 시험이 어렵고 지금 쓰는 곳이 하나 |
| D21 | 성능률의 표준 사이클 | **라우팅 공정의 표준 시간(P1)** — 확인됨: 작업지시 공정 스냅샷 `prd_wo_proc.std_ct_sec`(초/사이클) ÷ `units_per_cycle`(MES 확인 2026-10-21), 없으면 성능·OEE 빈칸 | 설비 마스터에 이상 사이클 칸 — P1 표 변경 |
| D22 | 알람 → 작업지시 | **알람 정의에 "자동 고장 신고" 표시가 있을 때만, 같은 설비·같은 알람의 열린 신고가 있으면 만들지 않음** | 모든 CRITICAL 알람 — 반복 알람이 신고를 쏟아낸다 |
| D23 | 카운터 자동 입력 주기 | **매시 정기 작업(워터마크)** — 이벤트마다 쓰면 MES 수집 경로에 CMS 쓰기가 끼어든다 | 수집 이벤트(COUNT) 때 바로 — 빠르지만 MES 코드 변경(Q2 와 같은 문제) |
| D24 | 화면 | **새 화면 3개**(`cms-state-review` 비가동 사유 검토·`cms-alarm` 알람 이력·`cms-kpi` 설비 KPI), MES `cms-state` 는 그대로 | `cms-state` 를 고쳐 쓰기 — MES 세션과 같은 파일을 동시에 고친다 |

**채택(2026-10-21 사용자, 상위 세션 경유)**: D18~D24 전부 추천안. C7 은 사유 관리 화면 `cms-state-reason` 을 하나 더 둔다(Q1 — 화면 수는 D24 의 3개 + 1).

### 11.4 MES 세션과 맞출 것 (PR 전에 상위 세션 경유)

| # | 내용 | CMS 추천 |
|---|---|---|
| Q1 | `cms_state_reason` 관리 화면·API 를 CMS 가 만든다(표는 그대로) | 25 4.8 이 이미 "CMS 세션" 이라 적음 — 확인만 |
| Q2 | 수집 `ALARM` 이벤트를 알람 원장으로: `EquipEventService` 의 ALARM 분기가 CMS 의 `AlarmSink.raise/clear`(인터페이스, 같은 트랜잭션)를 부르게 하는 **한 줄** | MES 세션이 그 줄을 넣거나, CMS PR 에 "남의 코드 변경" 으로 적고 MES 세션 확인 |
| Q3 | 5b POP 멈춤 사유 공통코드 → `cms_state_reason` 이전(25 4.8 "7단계 이후") | CMS 는 기다린다 — C7 사유 화면은 `cms_state_reason` 만 다룬다 |

**채택(2026-10-21 사용자)**: Q1 CMS 가 화면·API(표는 MES 그대로). Q2 계약 `AlarmSink`(raise/clear, 같은 트랜잭션)는 CMS 가 정의·구현하고 `EquipEventService` ALARM 분기의 호출 한 줄은 **MES 세션이 넣는다** — 순서: CMS 가 계약(빈 없으면 no-op)을 먼저 main 에 넣고(C7 PR 에 포함) → MES 호출 줄 → CMS C8 구현 빈. 계약은 `module.cms.link`(MES 가 CMS 구현을 직접 의존하지 않게, PLM link 와 같은 모양). Q3 MES 7단계 뒤, CMS 는 기다린다.

### 11.5 C7 상세 — 상태 사유·비가동 사유 검토 (V093, 2026-10-21)

| 규칙 | 내용 |
|---|---|
| MES 원장 | `cms_equip_state_log`·`cms_state_reason` 에 칸·트리거·권한을 더하지 않는다(D18). 사후 사유·연결은 덧붙임 표만, 원장 행 수정 0(시험) |
| 사유 관리 | `cms-state-reason`(SEARCH·SAVE). 코드(대문자·숫자·`_-` 30자)·분류는 만든 뒤 고정 — 원장 트리거가 넣을 때 "사유 분류 = 구간 분류" 를 봤으므로 분류를 바꾸면 지난 구간이 어긋난다. 이름·계획 여부·순서·사용은 수정(계획 여부는 C9 가 읽음 — 바꾸면 지난 KPI 도 바뀐다, 도움말 안내). UNKNOWN 사유 없음, 삭제 없음(사용 중지) |
| 사후 사유 `cms_state_note` | 구간 × 사유·메모, **추가 + 무효만**(무효 사유 필수), 구간당 유효 하나(부분 unique), **닫힌 구간만**, UNKNOWN 구간 제외, 사용 중 사유만. 분류는 **같은 분류만 — 예외 UDT ↔ SDT 재분류**(수집은 고장인데 실제 계획 정비, 또는 반대). 실효 분류 = 유효 사후 사유의 분류, 없으면 원장 분류 — C9 KPI 가 이것으로 센다 |
| 연결 `cms_mwo_down` | 고장 작업지시(BM, 취소 제외, **같은 설비**) × 실효 분류 UDT 구간, 추가 + 무효만, 구간당 유효 하나(작업지시 하나에 구간 여럿), 열린 구간도 연결 가능(수리 중). 작업지시 수기 정지 구간(`down_start/end`)과 수집 구간 시작·끝 차이(분)를 대조 표시만 — 고치지 않는다(D10) |
| 서로 얽힘 | 연결된 구간을 UDT 밖으로 재분류하는 사후 사유 추가·UDT 로 재분류한 사후 사유의 무효는 거부("연결 먼저 무효"). 같은 구간의 지정·연결은 구간 id 기준 advisory 트랜잭션 잠금으로 줄 세운다 — MES 원장 행을 잠그면 수집의 구간 닫기를 막으므로 |
| 같은 회사 | 구간·사유·작업지시가 같은 회사인지 트리거(`*_ctx_comp`), RLS(FORCE) |
| 후보 | 연결 후보 = 같은 설비 BM 중 구간 시작 하루 전 ~ 끝(열렸으면 지금) 하루 뒤 접수. 사유 후보는 화면이 구간 분류로 거르고 최종 판정은 트리거 |
| 조회 | 공장·기간(공장 현지 날짜, 최대 31일)과 겹치는 구간, 설비·실효 분류·"사유 없음만"(원장·사후 사유 모두 없는 PRD·UNKNOWN 밖 구간) |
| 알람 계약 | Q2 `io.smarthappy.module.cms.link.AlarmSink`(raise·clear, 수집 트랜잭션 안, 수집을 막지 않음 — 정의 없는 코드·열린 알람 없는 해제는 C8 이 기록·무시). 빈이 없으면 `NONE`(받기만) — C7 에서 계약만 넣어 MES 가 먼저 호출 줄을 붙일 수 있게 했다 |

### 11.6 C8 상세 — 설비 알람 (V097, 2026-10-21)

| 규칙 | 내용 |
|---|---|
| 경로 | 수집 ALARM → MES `EquipEventService`(본문 `code`·`active`(없으면 true)·`text`, docs/25 4.17) → 계약 `AlarmSink.raise/clear` → `CmsAlarmSink`(MANDATORY, 수집 트랜잭션 안). MES 호출 줄은 MES 세션 몫(Q2) — 그전에는 시험이 계약 빈을 직접 부른다 |
| 정의 `cms_alarm_def` | 회사 × 코드(MES 정규화와 같은 `^[A-Z0-9][A-Z0-9_.:-]{0,49}$`) × 설비 클래스(비우면 전체, `NULLS NOT DISTINCT` unique). 찾기 = 클래스 정의 먼저. 이름·심각도(INFO/WARN/CRITICAL)·현상 코드·자동 고장 신고·사용. 코드·클래스 고정, 삭제 없음 |
| 원장 `cms_alarm_log` | **발생마다 한 행**(발생 수 = 행 수 → 반복 판정, 해제를 안 보내는 장치도 맞게 셈). 해제 = 같은 설비·코드의 열린 행 모두 **한 번만**(열 단위 UPDATE 권한 + 트리거), 열린 행 없으면 아무것도 안 함. 해제 시각 < 발생 시각(장치 시계)이면 발생 시각으로 당기고 `clear_adjusted_yn`. 문구 200자 |
| 막지 않음 | 형식이 틀린 코드·이 회사 것이 아닌 설비는 기록하지 않고 경고 로그 — 수집 트랜잭션을 깨지 않는다. 정의 없는 코드도 원장에 남고 화면 "미정의"(정의를 나중에 만들면 이름이 붙는다 — 정의는 조회 때 붙임) |
| 자동 고장 신고(D22) | 사용 중 정의가 `auto_bm_yn = Y`(**CRITICAL 만**, CHECK)이고 같은 설비·코드 알람이 만든 신고가 열려 있지(REQ·OPEN·WIP) 않을 때만 BM·REQ·우선순위 1·현상 = 정의 현상 코드 + 장치 코드·문구, 같은 트랜잭션. 같은 설비·코드는 advisory 잠금으로 줄 세워 동시 발생이 신고를 둘 만들지 않음. 취소·완료된 뒤 다음 발생은 새 신고 |
| 반복 | 정책 `CMS_ALARM_REPEAT_COUNT`(기본 3)·`CMS_ALARM_REPEAT_HOURS`(24): 발생 시각 앞 N 시간 안(그 행 포함) 같은 설비·코드 발생 수 ≥ 기준 → 반복. 조회 때 계산(정책을 바꾸면 지난 표시도 바뀐다) |
| 상위 알람 | 기간(공장 현지 날짜, 최대 92일)과 겹친 알람을 설비·코드로 묶어 기간 안 발생 수·활성 분(기간으로 자름, 열린 알람은 지금까지)·열린 수, 상위 20 |
| 화면 | `cms-alarm`(이력 + 상위, 조회만), `cms-alarm-def`(정의) |

### 11.8 C9 상세 — 설비 KPI (V103, 2026-10-21)

정의는 순수 함수 `EquipKpi`(D20) — 이 표가 정의서다. 원장은 읽기만: MES 상태 구간·실적, CMS 사후 사유(C7)·예방점검(C2·C3).

| 지표 | 식 | 빈칸이 되는 경우 |
|---|---|---|
| 기간 | 공장 현지 날짜 [from 00:00, to 다음날 00:00) 을 **지금에서 자름**(미래는 세지 않음). 구간은 기간으로 잘라 셈(자정을 넘는 구간은 나뉨), 열린 구간은 지금까지 | — |
| 수집 시간 | 상태 구간이 덮은 시간. 미수집 = 기간 − 수집 시간(따로 보임). **수집 장치가 없는(구간이 하나도 없는) 설비는 미수집에 넣지 않는다** — 넣으면 라인·공장 합계의 미수집이 부풀려진다(브라우저 확인에서 발견) | — |
| 계획 시간 | 수집 시간 − UNKNOWN − NST − SDT, 분류는 **실효**(사후 사유 우선 — 수집 UDT 를 SDT 로 바꾸면 계획에서 빠짐) | — |
| 가용률 | PRD / 계획 | 수집 없음(D19), 계획 0 |
| 성능 | Σ(표준 사이클 ÷ 사이클당 수량 × 생산 수) / PRD. 표준 사이클 = 작업지시 공정 스냅샷 `prd_wo_proc.std_ct_sec`·`units_per_cycle`(D21 — 라우팅 공정 값이 지시 때 복사됨). 생산 수 = 양품 + 폐기 + 재작업 + 수리(품목 기본 단위). **실적은 원래 행만 읽고 정정(역분개)된 원래 행은 통째로 뺀다** — MES 역분개 행은 설비가 비고 시각이 정정 시각이라 설비·기간 합으로는 음수 행이 안 잡힌다(MES 확인, 실적 조회 D27 과 같은 규칙). **설비 없이 넣은 실적은 설비 KPI 에 들어가지 않는다** | 수집 없음, 생산 0, **표준 사이클 없는 실적이 하나라도**(일부만 더하면 성능이 낮게 나온다) |
| 품질 | 양품 / 생산 수 | 생산 0 |
| OEE | Σ(표준 시간 × 양품) / 계획 — 완전 생산 시간 ÷ 계획 시간. 표준 사이클이 하나면 가용 × 성능 × 품질과 같고, 섞이면 이 식이 정의 | 성능과 같음 |
| 고장 | 기간 안에 **시작한** 실효 UDT 구간 수(전날 시작한 고장은 그날 셈) | — |
| MTBF / MTTR | PRD / 고장, UDT 시간 / 고장(UDT 시간은 기간으로 자름) | 수집 없음, 고장 0 |
| PM 준수율 | 기간 안에 끝난 점검 실행(작업지시 없는 CHECK 방식) + PM 작업지시 중 기한 안 완료 비율. 판정은 점검 화면과 같은 `PmDue`(허용 일수 정책, 사용량 주기는 완료 누적 ≤ 예정) | 끝난 예정 0 |
| 합계 | 설비 → 라인(설비 계층 노드) → 공장: **초·수량·횟수를 더한 뒤 나눔**. 기간 합 = 일별 합(시험) | — |

인덱스 `ix_prd_result_equip_time`(MES `prd_result` 에 설비·발생 시각) 하나를 더한다 — 표 구조 변경 없음, PR 에 "남의 표" 로 적음.

### 11.10 C10 상세 — 카운터 자동 입력 (V105, 2026-10-21)

| 규칙 | 내용 |
|---|---|
| 실행 | 정기 작업 `CmsJobs.counterAuto` 매시(:20) — `TenantRunner.forEachActiveComp` + `JobLock`, 회사마다 한 트랜잭션(D23) |
| 출처 | SHOTS·CYCLES ← MES 실적 **원래 행만**(정정된 것 제외 — C9 와 같은 규칙) 생산 수(양품 + 폐기 + 재작업 + 수리) ÷ 사이클당 수량(작업지시 공정 스냅샷 — 금형 캐비티). RUN_HOURS ← **닫힌** PRD 상태 구간 시간. 열린 구간은 닫힌 뒤 다음 실행에서 |
| 금형·지그 | 자산 카운터 = 실적 시각에 그 설비에 설치돼 있던 자산(P1 `mst_asset_install`, 무효 제외). 가동 시간은 설치 기간으로 자르고 구간별로 합친다 |
| 두 번 세지 않기 | `cms_counter_src`(카운터 × 원장 행 PK, 추가만). **시각 워터마크 대신 원장 행 키** — 늦게 커밋된 실적·늦게 닫힌 구간도 다음 실행에서 잡히고, 겹쳐 돌아도 PK 가 막는다. 7일 지난 원장은 보지 않는다(작업이 그만큼 멈췄을 때만 놓침) |
| 읽기 행 | 카운터마다 실행당 AUTO 읽기 한 행(계기 값 없음, 비고 `AUTO RESULT×n STATE×m`), 같은 트랜잭션에서 그 카운터의 사용량 예방점검(C3 `generateForCounter`). **증가량 = 내림(출처 정확한 누적, 카운터 자릿수) − 지금까지 넣은 AUTO 합** — 자릿수(C3, 만든 뒤 고정)를 지키되 매번 반올림하면 쌓여 어긋나므로(0.83h 를 매시 1h) 나머지를 다음으로 넘긴다(브라우저 확인에서 발견: 자릿수 0 카운터에 2.833333) |
| 시작점 | 카운터를 만든 뒤 기록된 실적만, 가동 시간은 카운터를 만든 시각 앞을 자른다 — 지난 이력을 거슬러 채우지 않는다(처음 값은 수기 계기 값으로) |
| 무효 | AUTO 읽기를 무효로 해도 출처는 소비된 채(다시 세지 않음) — 잘못 센 것을 무효로 하는 뜻 |

**D25(이 세션 결정 — 사용자 확인 2026-10-21, 추천안 확정)**: **센 뒤에 정정된 실적은 카운터에서 빼지 않는다.** 카운터는 늘기만 하는 사용량이라 음수 행이 없고(C3 원장), 정정 실적이 실제로 기계가 돌지 않은 것인지(입력 착오) 돌았지만 수량을 잘못 적은 것인지 원장이 구분하지 않는다. 필요하면 사람이 마지막 AUTO 읽기를 무효로 하고 수기로 보정한다. 대안 "정정을 보정 행(감소)으로" 는 C3 의 증가량 ≥ 0 원장 규칙과 사용량 예방점검 판정(누적 감소)을 바꿔야 한다. 세기 **전에** 정정된 실적은 처음부터 안 센다.

### 11.12 C11 — 2부 마무리: 작업지시 수집 정지·KPI 추이·엑셀 (V115(임시 V9609), 2026-10-22)

사용자 지시(2026-10-22, 상위 세션 경유): 멈춘 하위 세션은 추천안으로 진행 — 아래 D26·D27 은 **사용자 확인 2026-10-22: 추천안**.

| 항목 | 내용 |
|---|---|
| 작업지시 상세 | 고장 작업지시(BM) 상세에 "수집 정지 구간·알람" 절(조회만) — C7 에서 연결한 유효 구간(실효 분류·사유), 닫힌 구간 합계 분(수기 정지 구간과 대조), 이 작업지시를 만든 C8 알람. 권한 = 작업지시 조회 |
| KPI 일별 추이 | 공장 합계 또는 설비 하나, 하루씩 C9 와 같은 규칙으로 모음 — 날짜별 원값 합 = 기간 원값(시험). 미래 날은 빼고 오늘은 지금까지 |
| KPI 엑셀 | 화면 표와 같은 조건·같은 계산, 줄 순서 = 공장 합계 → 라인(라인 합계 → 그 라인 설비). 비율은 %(소수 1자리), 시간은 h·분. 동작 EXCEL(화면 등록 갱신) |

| # | 질문 | 추천(채택) | 다른 안 |
|---|---|---|---|
| D26 | 일별 추이 기간 | **31일까지**(하루 한 번 질의 × 일수) — 그 넘으면 조회 거부 | 92일(KPI 표와 같게) — 하루씩 다시 모으는 비용이 3배 |
| D27 | 추이 그래프 | **표로 먼저**(브라우저 그래프 라이브러리는 공통 결정 — 상용 Kendo PoC 뒤) | 자체 SVG 그래프 — 공통 화면 규약 밖 |

### 11.14 C12 — 중대 알람 알림·위험성평가 개선 증빙 (2026-10-22, 마이그레이션 없음)

사용자 지시(2026-10-22, 상위 세션 경유): 멈춘 하위 세션은 추천안으로 진행 — 아래 D28·D29 는 **사용자 확인 2026-10-22: 추천안**. 플랫폼 코드 변경 없음(공통 알림 `NotifyEventSource`·첨부 `FileLockRule` 훅을 그대로 씀).

| 항목 | 내용 |
|---|---|
| 알림 이벤트 | `CMS_ALARM_CRITICAL`(기본 켬) — 알림 설정 화면에 "중대 설비 알람"으로 보인다. 받는 사람이 끌 수 있다(공통 구독 표) |
| 언제 | C8 수집이 알람 원장 행을 넣은 직후(같은 트랜잭션), 그 알람 코드의 정의 중대도가 `CRITICAL` 일 때만. 정의 없는 코드·경고·정보는 보내지 않는다 |
| 누구에게 | 알람 화면(`cms-alarm`) 조회 권한이 있는 사람 전부(공통 `searchers`) — 따로 수신자 표를 만들지 않는다 |
| 중복 | 중복 키 `ALARM:{설비 id}:{알람 코드}:{UTC 시각(시 단위)}` — 같은 설비·같은 코드가 한 시간 안에 반복되면 한 번만(공통 알림이 키로 멱등). 링크 = 알람 화면(공장·설비 코드·열린 것만) |
| 위험성평가 증빙 | 유해위험요인마다 공용 첨부(대상 = 화면 `cms-risk` × 요인 id). 작성·결재 중·확정본은 올리기·삭제(확정본의 개선 증빙은 확정 뒤에 생긴다 — 개선 진행 기록과 같은 자리), **폐기된 평가·없는 요인·다른 회사 요인은 잠김**(`RiskFileLock`, QMS 시정조치 첨부와 같은 방식 — 대상 없는 첨부를 만들지 않는다). 권한은 공통 첨부 규칙(조회 = SEARCH, 올리기·삭제 = SAVE) |
| 함께 고친 것 | 위험성평가(C6)·설비 보안 대장(C5)·고장 코드(C1) 화면의 선택 칸 첫 줄에 `js.common.choose` 키가 보일 수 있던 경합 — 선택 칸을 문구 로드 뒤에 만든다(브라우저 확인에서 발견, 상위 세션 지시로 C12 에 포함) |
| 남은 것 | ~~작성 중 요인을 지우면 그 요인의 첨부는 남은 채 잠긴다~~ → C24(13.11): 요인 삭제가 같은 트랜잭션에서 그 첨부도 지운다 |

| # | 질문 | 추천(채택) | 다른 안 |
|---|---|---|---|
| D28 | 알림 대상 중대도 | **CRITICAL 만**(경고는 화면·홈 카드로) | WARNING 까지 — 반복 알람에 알림 폭주 |
| D29 | 반복 알람 묶기 | **설비 × 코드 × 한 시간**(공통 중복 키) | 열린 알람이 닫힐 때까지 한 번 — 닫힘 판정은 수집 지연에 따라 늦어 다음 발생을 놓친다 |

### 11.15 예비품 WMS 출고 연계 — 계약 (2026-10-22, WMS 1차 의견 반영 · 상위 세션 경유)

1부 D9: CMS 는 사용 기록(`cms_mwo_part`)만, 수량·차감은 WMS. 처음 CMS 초안(CMS 가 바라는 모양)에 WMS 1차 의견을 받아 아래로 고쳤다 — D30~D33 은 **사용자 확인 2026-10-22: 추천안**. 구현은 WMS 가 B4b → B6a 다음에 하고(원장 유형·위치 고르기는 docs/30 으로 옮김), CMS 는 그때 완료 전이에서 계약을 부른다.

#### 지금 CMS 사실

| 대상 | 사실 |
|---|---|
| `cms_mwo_part` | 작업지시 × 품목 × 수량(품목 기본 단위 스냅샷, `qty > 0`). **작업지시가 OPEN·WIP 일 때만 추가·삭제, 수정 없음**, 완료(DONE) 뒤 불변(트리거가 `cms_mwo` 행을 `FOR UPDATE` 로 잡고 상태를 본다) |
| 완료 | `MwoService` 완료 전이 — `cms_mwo FOR UPDATE` → 닫기 검사(현상·원인·조치) → 상태 DONE·`finished_at`. 되돌리기 없음(잘못 닫음 = 재작업 새 작업지시) |
| 공장 | `cms_mwo.plant_cd`(대상 설비의 공장) — 시간대 기준과 같다 |

#### 계약 — `module.cms.link.SpareIssueLink` (WMS 가 구현, `LotStockLink` 와 같은 모양: 인터페이스 + 구현 빈 + `NONE` + `of(ObjectProvider)`)

```java
public interface SpareIssueLink {
    SpareIssueLink NONE = …;    // WMS 없는 설치본·공장 스위치 N → 아무것도 안 함
    /** 출고 — 완료 전이 직후(같은 트랜잭션). WMS 는 cms_* 를 읽지 않는다: 필요한 값을 인자로 넘긴다 */
    void onMwoDone(long mwoId, String plantCd, Instant occurredAt, List<PartIssue> parts, String by);
    /** 반납 — 완료 뒤 덜 쓴 것(부분 가능) */
    void onPartReturn(long partId, BigDecimal qty, String reason, String by);
    record PartIssue(long partId, long itemId, BigDecimal qty) { }   // qty = 품목 기본 단위
    static SpareIssueLink of(ObjectProvider<SpareIssueLink> p) { … }
}
```

| 항목 | 규칙 |
|---|---|
| 인자 = 데이터 | CMS 가 `cms_mwo_part` 를 읽어 `PartIssue` 목록으로 넘긴다 — WMS 는 CMS 표를 읽지 않는다. 수량은 품목 기본 단위(스냅샷 단위 = 기본 단위, C4 트리거) |
| 출고 | **완료(DONE) 순간 한 번**(D30). 유형 `ISSUE_MRO`(−), 출처 = 사용 예비품 줄, **멱등 키 `CMS_PART:{partId}`**(+ 칸 순번). 발생 시각 = `finished_at`, 공장 = `cms_mwo.plant_cd` |
| 반납 | **역분개가 아니라 새 + 행**(D32): 유형 `MRO_RTN`(+), 부분 반납 가능, **누적 반납 ≤ 출고는 WMS 가 검사**, 사유 필수. CMS 화면은 작업지시 상세 사용 예비품 줄의 "반납" 버튼에서 계약 호출 — CMS 표는 바꾸지 않는다 |
| 위치 | 자재·예비품 창고 위치 pick_seq 순, 재고 상태 AVAILABLE(SCRAP·QC 제외). `feed_node_id` 우선은 선택(WMS 판단). CMS 는 위치를 넘기지 않는다 |
| 원장 밖 품목 | **원장에 한 번도 들어온 적 없는 예비품 품목은 건너뛴다**(D33, docs/30 D-B14 와 같은 태도) — 재고를 관리하지 않는 소모품을 막지 않는다 |
| 부족·마감 | 켜진 공장에서 부족 → 409 `INSUFFICIENT_STOCK`, 마감 달 → 409 `PERIOD_CLOSED` — **완료 전체 롤백**(D31, 작업지시는 WIP 그대로). 작업지시 화면은 409 메시지를 그대로 보여 준다 |
| 작업지시 취소(CANCELLED) | OPEN·WIP 에서만 가능 → 아직 출고 전이라 할 일 없음 |
| 공장 스위치 | **별도 공장 정책 `WMS_MRO_LINK`**(기본 N, 투입 차감 `WMS_STOCK_LINK` 와 따로). N 이면 기록만(지금과 같음) — 켜기 전 완료된 작업지시는 이관하지 않는다(기초 재고·실사로 맞춤) |
| 잠금 순서 | `cms_mwo`(완료 `FOR UPDATE`) → `lot_master`(lot_id 순 FOR SHARE) → `inv_stock` 칸(stock_id 순 FOR UPDATE) → 기간 공유. 기간과 `inv_stock` 사이 순서는 **WMS 내부 순서(docs/30 12.4.7)** 를 따른다. WMS 는 그 뒤 CMS 표를 잡지 않는다. 반납도 같은 순서(`cms_mwo` FOR SHARE 로 시작) |
| 시험(WMS·CMS 같이) | 정상·스위치 N·원장 밖 품목 건너뜀·부족 409 롤백·마감·멱등(같은 줄 두 번 = 원장 키)·반납 부분·누적 초과 거부·동시(같은 품목 작업지시 둘 완료)·A사로 B사 |

| # | 질문 | 추천(채택) | 다른 안 |
|---|---|---|---|
| D30 | 언제 차감 | **완료 순간 한 번** — 사용 기록이 그때 고정되고, 추가·삭제마다 원장을 쓰면 역분개가 쌓인다 | 줄 추가 때 바로(삭제 때 역분개) — 재고가 실시간이지만 원장이 지저분 |
| D31 | 동기·비동기 | **같은 트랜잭션 동기 호출**(투입 차감과 같은 모양) — 부족을 그 자리에서 알린다 | 아웃박스 — 완료는 늘 성공하지만 부족이 뒤늦게 "실패 이벤트"로 남는다 |
| D32 | 덜 쓴 것 | **WMS 반납 새 + 행(`MRO_RTN`, 부분 가능)** — CMS 기록은 불변 유지 | CMS 사용 기록 정정 / 출고 역분개 — 완료 뒤 불변 규칙을 깨거나 부분 반납이 안 된다 |
| D33 | 원장에 없던 예비품 품목 | **건너뜀**(D-B14 와 같음) — 재고 관리 안 하는 소모품이 완료를 막지 않게 | 부족 409 — 첫 출고 전에 기초 재고를 강제 |

#### 11.15.1 CMS 호출부 (2026-10-22, 마이그레이션 없음 — WMS 계약 #219 병합 뒤)

| 항목 | 내용 |
|---|---|
| 출고 | `MwoService.finish` 끝(상태 DONE 저장·PM 점검 완료 뒤, 같은 트랜잭션)에서 `cms_mwo_part` 를 `PartIssue` 목록으로 만들어 `onMwoDone(mwoId, plant_cd, finished_at, parts, 로그인 아이디)` — 빈 목록도 그대로. WMS 의 409(부족·마감)는 그대로 던져 **완료 전체 롤백**(작업지시는 WIP, `finished_at` 도 비어 있음) |
| 반납 API | `POST /api/cms/mwos/{mwoId}/parts/{partId}/return` (`cms-mwo` SAVE) — `cms_mwo` **FOR SHARE** → 상태 DONE·CLOSED 확인 → 그 작업지시의 줄인지 확인(아니면 404) → 수량(품목 단위 자릿수·양수)·사유(필수) 칸 검사 → `onPartReturn`. 응답 `data` = WMS 결과(true 반납 / false 연동 안 된 줄). 누적 초과 409 `WMS_MRO_RETURN_OVER`, 사유 길이·요청 id 형식은 WMS 400 칸 오류 |
| 화면 | 작업지시 상세 "사용 예비품" 아래 **덜 쓴 예비품 반납**(완료·확인 상태 + SAVE 만 보임): 줄 고르기·수량·사유. 요청 id 는 누른 순간 만들고 성공·입력 변경 때 버린다(응답이 끊겨 다시 눌러도 한 번). false 면 "재고 연동이 안 된 줄" 안내(오류 아님) |
| 하지 않은 것 | 줄마다 반납 누계 표시 — WMS 읽기 계약이 필요 → **11.15.2(C18)** |

#### 11.15.2 반납 누계 읽기 계약 — C18 (2026-10-23, 상위 배정 A안 · WMS 확인은 상위 경유, 마이그레이션 없음)

CMS 가 계약(`module.cms.link.SpareIssueLink`)에 **읽기 메서드 하나**를 더한다. 구현은 WMS PR 따로(이 계약 PR 은 CMS 쪽만).

```java
/** 줄마다 반납 누계(품목 기본 단위). 출고 원장이 있는 줄만 키를 넣는다(반납 없으면 0). 키 없음 = 모름 */
default Map<Long, BigDecimal> returnedQty(Collection<Long> partIds) { return Map.of(); }
```

| 항목 | 내용 |
|---|---|
| 뜻 | 키 = `cms_mwo_part.part_id`, 값 = 그 줄의 **누적 반납 합**(원장 `MRO_RTN` +, 출처 `CMS_PART` = partId 의 qty 합 ≥ 0). 출고 원장(`ISSUE_MRO`, 같은 출처)이 있는 줄은 **반납이 없어도 0 으로 키를 넣는다** |
| 키 없음 | **모름** — WMS 없는 설치본(NONE·기본 구현), 스위치 꺼짐(`WMS_MRO_LINK`), 원장 밖 품목(D33 건너뜀), 아직 완료 전(출고 없음). CMS 화면은 0 이 아니라 "모름"으로 보인다(상위 조건) |
| 남은 반납 가능 | CMS 가 계산 = 사용 수량(`cms_mwo_part.qty`) − 누계. 화면 안내일 뿐 — **반납 상한 검사는 지금처럼 WMS**(누적 반납 ≤ 출고, 409 `WMS_MRO_RETURN_OVER`) |
| 호출 | CMS `SpareService.parts` — 작업지시가 완료(DONE)·확인(CLOSED)일 때만, 그 작업지시의 줄 id 들로 한 번(줄 수 = 작업지시 하나 분량). 읽기 전용 트랜잭션 안, 잠금 없음 |
| WMS 구현이 지킬 것 | 같은 회사(RLS)만, partIds 가 비면 빈 맵, 없는 id 는 키 없음, 예외를 던지지 않는다(읽기 실패가 작업지시 상세를 막지 않게 — 실패면 빈 맵 = 모름) |
| 기본 구현 | 인터페이스 `default` = 빈 맵 → WMS 구현(`MroIssuePosting`)은 이 PR 만으로 컴파일·동작 그대로(모름 표시), WMS PR 에서 덮어쓴다 |

### 11.16 POP 고장 신고 계약 — C20 (2026-10-23, 상위 채택: MES Q2·Q4·Q5 동의 + 계약 보강 1~7, V151·임시 V9616)

화면은 **MES POP**(작업자 배지·단말 유형 권한·"고장 신고" 버튼), 기록은 **CMS**(보전 작업지시 BM·REQ). 순서: C19 → 이 계약 PR(CMS) → MES 화면 PR.

```java
// module.cms.link.BreakdownReport — ObjectProvider + NONE(AlarmSink 와 같은 모양)
List<Symptom> symptoms(long equipId);          // Symptom(symptomCd, symptomNm)
ReportResult report(Report r);                 // Report(equipId, symptomCd, symptomText, workerId, terminalId, clientReqId)
                                               // ReportResult(reqNo = 작업지시 번호, duplicate)
```

| 항목 | 내용 |
|---|---|
| 트랜잭션 | **POP 서비스 트랜잭션 안(MANDATORY)** — CMS 검사·멱등(`sys_inbox` 소비자 `CMS_POP_BREAKDOWN`, message_id = clientReqId)·채번(`NumberingService`)·작업지시 행이 모두 같은 트랜잭션. 시험으로 확인: 트랜잭션 밖 호출 거부, 호출 쪽이 뒤에서 실패해 롤백하면 작업지시·멱등 기록도 없고 같은 요청 id 로 다시 하면 새로 만든다(보강 5). 알림·아웃박스는 이 신고에 없다(사무 계정 신고와 같음) |
| 설비 | id(보강 1). MES 가 단말 공장 범위를 확인하고 CMS 도 다시: 같은 회사(RLS)·사용 중, **단말 공장 = 설비 공장**(아니면 400 `equipId:PLANT`) |
| 현상 | `symptoms(equipId)` = 현상 유형·사용 중, 클래스 한정 코드는 그 클래스 설비만, 순서 → 코드(보강 2). 신고 입력은 현상 코드 또는 글 하나 이상(400 `symptomCd:REQUIRED`), 글 200자 이하(`symptomText:TOO_LONG`), 코드 검사는 사무 계정 신고와 같다(사용 중지 `INACTIVE`·다른 클래스 `CLASS_MISMATCH`). 제목은 현상 이름·글 앞부분 |
| 신고자 | 작업자 id + 단말 id 따로(보강 3) — `cms_mwo.reported_worker_id`·`reported_terminal_id`, 사무 계정(`requested_by`)은 비움. 감사 행위자 = `t{단말 id}:{단말 코드}`. 작업자는 같은 회사·사용 중, 단말은 같은 회사·같은 공장(트리거 `fk_cms_mwo_reported_*_comp`), 셋(작업자·단말·요청 id)은 함께만·만든 뒤 불변(`ck_cms_mwo_pop_reporter`·`ck_cms_mwo_reporter_fixed`) |
| 멱등 | 같은 clientReqId(8~64자, 영문·숫자·`_.:-`) 재요청 = 처음 번호 + `duplicate = true`(보강 4) — `cms_mwo.report_req_id`(회사 안 고유)로 찾는다. 검사 오류는 멱등 기록 전이라 고쳐서 같은 id 로 다시 보낼 수 있다 |
| 오류 | 칸 오류 400(`equipId`·`symptomCd`·`symptomText`·`workerId`·`terminalId`·`clientReqId`) — MES 가 그대로 화면에 |
| NONE | 구현 빈이 없으면 `NONE`: 선택지 빈 목록, 신고 409 — **NONE 이면 POP 버튼을 숨긴다**(보강 6) |
| 하지 않은 것 | 공정·설비 상태 자동 연결(보강 7 — 정지 기록은 보전 담당이 작업지시에서), 오프라인 대기열·사진(Q5 — 뒤, 사진 계약 제안은 11.17), 단말 유형별 허용(Q4 — MES 결정: 생산 단말만) |
| 화면(CMS) | 작업지시 상세 "신고 …" 에 작업자(POP 단말 코드) |

### 11.13 하지 않을 것(2부)

예지보전 모델(RUL)·진동 분석(2차), 알람 → ERP·메일 직접 발송(아웃박스 연계는 홈 카드·알림 모듈과 맞춘 뒤), 상태 구간 수기 입력(원장은 MES, D19), 에너지 원단위 KPI(08 — ESG 단계).

### 11.17 POP 고장 신고 사진 계약 — C25 (2026-10-24, 제안 #442 → **사용자 결정 Q1~Q4 모두 추천안**, 공용 attachAs #460, CMS 구현 V174·임시 V9620)

11.16 Q5 "사진 — 뒤"의 계약. 화면은 **MES POP**(신고 직후 "사진 붙이기"), 기록은 **CMS**(고장 작업지시의 신고 사진 = 공용 첨부 대상 `cms-breakdown` × 작업지시 id — 사무 신고 사진과 같은 자리라 작업지시 상세 "신고 사진"에 그대로 보인다). **결정(2026-10-24, 사용자 — 상위 전달)**: Q1~Q4 모두 추천안. 공용 `FileService.attachAs`(+ `AttachOptions` onlyExt·maxMb·reuseSameContent, `countAttached`)는 시스템관리가 먼저(#460), CMS 구현은 13.12.

**지금 막힌 곳(코드로 확인)**: 공용 `FileService.upload` 는 `LoginUser` + 대상 화면 SAVE 권한을 요구한다(`requirePermission`). POP 단말은 `TerminalPrincipal`(사무 계정 아님)이라 이 길로 못 올린다. `copyAll`(MANDATORY, 권한은 부르는 쪽이 확인)도 `CurrentUser`(LoginUser)를 요구한다.

| 항목 | 제안 |
|---|---|
| 계약 | `module.cms.link.BreakdownReport` 에 메서드 하나 더(같은 계약 — MES 는 이것만 본다): `PhotoResult attachPhoto(Photo p)`. `Photo(String reqNo, long workerId, long terminalId, String clientReqId, String fileName, long size, InputStream content)` — MultipartFile 대신 이름·크기·스트림(계약이 웹 타입에 묶이지 않게). `PhotoResult(long fileId, boolean duplicate, int count)` |
| 트랜잭션 | 신고와 같다 — **POP 서비스 트랜잭션 안(MANDATORY)**. 실제 파일은 저장소에 먼저 쓰고, 트랜잭션이 커밋되지 않으면 지운다(`copyAll` 과 같은 afterCompletion) |
| 누가·어디에 | 그 작업지시를 **신고한 단말만**(`cms_mwo.reported_terminal_id = terminalId` — 다른 단말·사무 신고 작업지시는 400 `reqNo`). 작업자는 배지로 확인된 같은 회사·사용 중 작업자(신고자와 달라도 됨 — 교대). 대상은 고장(BM) 작업지시 |
| 언제 | **신고(REQ)·접수(OPEN) 상태에서만**(Q3). 착수 뒤 사진은 보전 담당이 사무 화면에서. 잠금 규칙 `MwoFileLock`(취소·확인 = 잠김, BM 만)은 그대로 한 번 더 |
| 형식 | 이미지만: `jpg`·`jpeg`·`png`(+ 회사 허용 목록 `FILE_ALLOWED_EXT` 에도 있어야). 공용 검사 그대로 — 항상 거부 목록, 앞부분 시그니처 = 확장자, SHA-256 |
| 크기 | `min(FILE_MAX_MB, CMS_POP_PHOTO_MB)` — 새 정책 `CMS_POP_PHOTO_MB` 기본 5(1~20). 선언 크기와 실제 바이트 둘 다(공용과 같다) |
| 개수 | 작업지시 하나에 **POP 로 붙인 사진** 최대 `CMS_POP_PHOTO_MAX` 기본 3(1~10). 넘으면 409 `PHOTO_LIMIT`. 사무 화면 첨부는 세지 않는다(행위자 `t…` 로 구분) |
| 재전송 | 같은 작업지시 + 같은 SHA-256(사용 중) = **처음 파일**(`duplicate = true`, 개수 안 늘림). 단말이 응답을 못 받고 다시 보내도 두 장이 안 된다. clientReqId 는 그 신고의 요청 id 와 같아야 한다(다른 신고의 사진 붙이기 방지 — 400 `clientReqId`) |
| 행위자 | `sys_file.created_by` = `t{단말 id}:{단말 코드}`(신고와 같은 관례, 50자 안). 접근 로그 FILE_UPLOAD 도 같은 행위자 |
| 삭제 | POP 에서는 없음(잘못 찍은 사진은 보전 담당이 사무 화면에서 — `cms-breakdown` SAVE). 계약에 삭제 메서드 없음 |
| 오류 | 칸 오류 400(`reqNo`·`clientReqId`·`workerId`·`terminalId`·`file`) + 공용 거부 코드(`file.reject.*` — 크기·형식·내용), 개수 409 `PHOTO_LIMIT`, 잠김 409 `FILE_LOCKED`. MES 가 그대로 화면에 |
| NONE | 구현 없음 = 409(신고 버튼과 같이 숨김) |
| 오프라인 | 없음(11.16 Q5 그대로 — 온라인만). 사진은 신고 성공 뒤에만 |

**공통 변경이 필요하다(Q1)** — 위 계약을 CMS 가 구현하려면 공용 첨부에 "권한·사용자는 부르는 쪽이 확인한 저장" 길이 있어야 한다. 제안: `FileService.attachAs(String targetType, String targetId, String fileName, long size, InputStream content, String actor)` — MANDATORY, 권한 검사 없음(부르는 업무 계약이 확인), **나머지 검사(이름·확장자·크기·시그니처·잠금·SHA-256·접근 로그)는 `upload` 와 같은 코드**, `actor` 50자. 공용 파일이라 상위(플랫폼) 몫.

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| Q1 | 단말 저장 길 | **공용 `FileService.attachAs`(상위 PR)** — 검사 한 곳, CMS 는 계약만 | CMS 가 `sys_file` 에 직접 INSERT — 공용 검사(시그니처·허용 목록·접근 로그)를 복제, 남의 표 직접 쓰기 |
| Q2 | 한도 | 새 정책 `CMS_POP_PHOTO_MB` 5·`CMS_POP_PHOTO_MAX` 3(회사별) | 상수 — 현장 카메라 해상도마다 못 바꿈 |
| Q3 | 붙일 수 있는 때 | **REQ·OPEN**(보전이 착수하기 전 현장 증거) | 완료 전까지 — 작업 중 사진은 보전 담당 몫과 섞인다 |
| Q4 | 붙이는 단말 | **신고한 단말만** | 같은 공장 단말 모두 — 남의 신고에 사진(확인 안 된 증거) |

시험(구현 PR): 신고 → 사진 1·2·3 → 4번째 409, 같은 사진 재전송 = 처음 파일, 다른 단말·사무 신고·다른 신고 요청 id 400, 착수 뒤·취소 뒤 409, 형식(exe 이름·png 이름에 pdf 내용)·크기 넘침 400, 트랜잭션 밖 거부·호출 쪽 롤백이면 파일·행 없음, NONE 409, 사무 화면 "신고 사진"에 보임, A사 단말로 B사 작업지시 번호 400.

## 12. CHECK 의 NULL 구멍 정리 (2026-10-21, V111(임시 V9608) — 상위 세션 점검, MES 발견)

`A OR length(btrim(x)) > 0` 은 x 가 NULL 이면 식 전체가 NULL 이 되고 CHECK 는 NULL 을 통과로 본다. CMS 소유 2곳이 이 모양이었다(화면·API 는 서비스가 막고 있어 직접 SQL·다른 경로만 뚫림).

| 제약 | 뚫렸던 것 | 고친 식 |
|---|---|---|
| `ck_cms_mwo_symptom_any` | 고장 신고(BM)에 현상 코드·현상 글 둘 다 NULL | `… OR (symptom_text IS NOT NULL AND length(btrim(symptom_text)) > 0)` |
| `ck_cms_mwo_done` | BM·CM 완료(DONE·CLOSED)에 작업 내용 NULL | `… OR (work_text IS NOT NULL AND length(btrim(work_text)) > 0)` |

적용: DROP → `ADD … NOT VALID`(새 행·수정 행부터) → `VALIDATE CONSTRAINT`(기존 행까지). 기존 행이 위반하면 마이그레이션은 실패하지 않고 그 제약만 NOT VALID 로 남기며 알림을 낸다 — 그 행을 고친 뒤 `ALTER TABLE cms_mwo VALIDATE CONSTRAINT …` 를 손으로 실행한다(설치본 점검 목록). 다른 CMS 제약은 NOT NULL 칸이거나 `x IS NULL OR …` 모양이라 해당 없음(마이그레이션 전부 훑음).


## 13. 그룹 A — 남은 일 순서 (2026-10-22, 상위 판단(사용자 지시 '멈춘 세션은 추천안으로'))

1·2부와 C12·예비품 연동 뒤 이 문서에 남은 것을 모아 순서를 정했다. 기준: ① 이미 알려진 빈틈(서버가 막지 않는 것) ② 화면 표(5장)에 약속했지만 없는 것 ③ 다른 모듈과 맞춰야 하는 것은 뒤로. PR 1개 = 단계 1개, 마이그레이션이 필요하면 임시 V9610~.

| 순서 | 단계 | 왜 | 겹침 |
|---|---|---|---|
| **1 ✅** | **C13 작업지시 첨부 서버 잠금** — `cms-mwo`·`cms-breakdown` 첨부에 `FileLockRule`(없는 작업지시·다른 회사·취소·확인 = 잠김, 신고 사진은 고장(BM)만) | 7장 C1 "남은 것: 첨부 서버가 대상 상태를 모름" — 지금은 화면이 버튼을 숨길 뿐, API 로는 취소된 작업지시·없는 번호에도 올라간다 | 없음(공통 첨부 훅 사용) |
| **2 ✅** | **C14 보전 작업지시 엑셀** — 목록과 같은 조건·순서, 동작 EXCEL 등록(V136, V136·임시 V9610) | 5장 표에 EXCEL 약속, 가장 많이 쓰는 목록 | 없음 |
| **3 ✅** | **C15 설비 예비품 엑셀** — 연결 표·사용 이력(기간), 동작 EXCEL 등록 | 5장 표 "EXCEL 은 공용 내보내기 붙일 때(C1·C2 와 같이 미룸)" — 공용 내보내기(`ExcelExportService`)가 이제 있다 | 없음 |
| **4 ✅** | **C16 기준정보 엑셀 3종** — 고장 코드·설비 프로필·예방점검 계획, grid 메뉴 엑셀(같은 조건·정렬·보이는 열), 동작 EXCEL 등록(V143·임시 V9613) | 같은 약속, 쓰임이 적은 기준정보 — UI 단계 3 으로 세 화면이 grid 가 되어 공용 grid 엑셀을 그대로 붙일 수 있다 | 없음 |
| **5 ✅** | **C17 설비 이력 카드 단독 화면(`cms-equip-card`)** — 설비 한 대 × 기간 요약 + 이력 한 표(작업지시·점검·예비품·카운터 리셋), 엑셀(V146·임시 V9614) | 지금은 설비 프로필 아래 카드 — 점검·예비품·카운터까지 넣으면 화면 하나 분량 | 없음 |
| **6 ✅(CMS 쪽)** | **C18 줄마다 예비품 반납 누계** — 계약 `SpareIssueLink.returnedQty`(기본 = 빈 맵 = 모름) + 작업지시 상세 "반납" 칸·반납 품목에 남은 양(11.15.2). **WMS 구현 PR 따로**(그 전까지 화면은 "모름") | WMS 읽기 계약 필요 | **WMS** |
| **7 ✅** | **C19 위험성평가 참여자 작업자**(`mst_worker` 고르기, V150·임시 V9615) — 13.6 | 기준정보 읽기(상위 결정) — MES 화면 불필요 | 없음 |
| **8 ✅(CMS 쪽)** | **C20 POP 고장 신고 계약**(`link.BreakdownReport`, V151·임시 V9616) — 11.16. MES POP 화면 PR 따로 | MES Q2·Q4·Q5 동의 + 계약 보강 1~7(상위 채택) | **MES** |
| **9(1/2) ✅** | **C21 grid 엑셀 통일 1묶음** — 작업지시·점검 실행 grid 메뉴 엑셀(V152·임시 V9617) — 13.7 | C16 방식을 목록 화면에도(상위 배정) | 없음 |
| **9(2/2) ✅** | **C21 grid 엑셀 통일 2묶음** — 카운터·알람 이력·상태 검토 grid 메뉴 엑셀(V155·임시 V9618) — 13.8 | 같음 | 없음 |
| **10 ✅** | **C22 작업지시 "POP 신고" 필터** — 목록·grid·두 엑셀 같은 조건, 보고서형 엑셀 신고자 = 작업자(POP 단말) + MES POP 화면(#393) 신고 → CMS 확인(브라우저) — 13.9 | C20 계약 뒤 MES POP 화면 병합(상위 배정 ②) | 없음 |
| **11 ✅** | **C23 grid 엑셀 통일 3묶음** — 알람 정의·상태 사유·점검표 grid 메뉴 엑셀(V166·임시 V9619) — 13.10. 이것으로 CMS 목록(grid) 화면 모두 | C21 다음 남은 3화면(상위 배정) | 없음 |
| **12 ✅** | **C24 위험성평가 요인 삭제 때 그 요인 첨부 정리** — CMS 서비스가 같은 트랜잭션에서(공통 "대상 삭제 훅" 없이, 상위 결정 2026-10-23) — 13.11 | 11.14 "남은 것" | 없음 |
| **13 ✅(CMS 쪽)** | **C25 POP 고장 신고 사진** — 계약 문서(#442, 11.17) → 공용 `attachAs`(#460) → **CMS 구현(13.12, V174·임시 V9620)**. MES 화면 PR 따로 | 11.16 Q5 "뒤"(상위 배정 ③, 2026-10-24) | **MES** |
| **14 ✅** | **C26 깊은 offset** — QA H2(알람 이력)·H4(점검 실행)·M1(카운터)·M6(예방점검 계획) 지연 조인 + 판독 합 부분 색인(V180·임시 V9621) — 13.13 | QA 조사 docs/qa/2026-10-24_offset-deep-page.md(상위 배정) | 없음 |
| **15 ✅** | **C27 예방점검 계획 달력 보기**(월, 읽기 전용) — 공장 × 월, 만든 예정 + 달력 주기 예상(마이그레이션 없음) — 13.14 | 5장 화면 표 약속(7장 C2 "월 달력 보기는 C3 이후"), 상위 배정 2026-10-26 | 없음 |
| **16 ✅(CMS 쪽)** | **C28 POP 사진 한도 계약** `BreakdownReport.photoLimits()` — MES POP 사진 화면이 고르기 전에 알린다(docs/25 4.43), 공용 첨부와 같은 계산 — 13.15. MES 화면 PR 따로 | MES 요청(상위 전달 2026-10-26) | **MES** |
| **17 ✅** | **C29 카운터 grid 남은 비용(QA M1, 안 A)** — 판독 합·수 한 번에 + 최근 계기 값 부분 색인(V192·임시 V9622) — 13.16 | 13.13 "M1 첫 쪽 1.0초" 남은 것, 상위 배정 2026-10-26(안 A 먼저, 모자라면 안 B 제안) | 없음 |
| **18 ✅** | **C30 점검 실행 grid 끝 쪽(QA H4 남은 비용)** — 안쪽 층 계획 조인을 left join 으로(조인 제거), 색인은 효과가 작아 두지 않음 — 13.17 | 13.13 "H4 끝 쪽 1.6초" 남은 것, 상위 배정 2026-10-26(실행계획 먼저, 효과 있을 때만 색인) | 없음 |
| **19 ✅** | **QA G-2 설비 보안 grid SQL 정렬** — 경고 필터·계산 칸 정렬이 없으면 SQL 정렬·쪽(목록과 같은 DB 정렬 규칙) — 13.18 | 사용자 결정(docs/qa/2026-10-28_grid-s2-f1-d2-calib.md G-2), 상위 전달 2026-10-28 | 없음 |
| 하지 않음 | 예지보전(RUL)·진동(2차), 공용 `.hb-form` 넘침(공통 CSS — 상위 몫) | 11.13·9장 | — |

### 13.1 C13 상세 — 작업지시 첨부 서버 잠금 (2026-10-22, 마이그레이션 없음)

| 항목 | 내용 |
|---|---|
| 잠금 | `MwoFileLock implements FileLockRule` — 대상 `cms-mwo`(작업지시 첨부)·`cms-breakdown`(신고 사진), 대상 id = 작업지시 id. **없는 작업지시(다른 회사 = RLS 로 안 보임)·숫자 아닌 id·취소(CANCELLED)·확인(CLOSED) = 잠김**, 신고 사진은 고장(BM) 작업지시가 아니면 잠김 |
| 완료(DONE) | 열어 둔다 — 완료 보고서·사진을 뒤에 붙이는 일이 흔하다. 완료 뒤 삭제는 화면이 버튼을 숨기는 것으로만 막는다(공통 훅은 올리기·삭제를 함께 잠근다 — 둘을 나누려면 공통 변경) |
| 화면 | 취소·확인 작업지시는 업로드 칸을 숨긴다(서버가 잠그므로 눌러도 409) |
| 결정 | 상위 판단(사용자 지시 '멈춘 세션은 추천안으로'): 잠그는 상태 = 취소·확인(추천) / 다른 안 = 완료부터 잠금(완료 보고서를 못 붙임) |

### 13.2 C14 상세 — 보전 작업지시 엑셀 (2026-10-22, V136·임시 V9610)

| 항목 | 내용 |
|---|---|
| 조건·행 | 목록 조회와 **같은 함수**(`MwoService.list`) — 같은 조건(기간 필수·상태·유형·공장·검색어·범위)·같은 상한·같은 순서. 동작 EXCEL(`sys_prog` 갱신) |
| 열 | 번호·유형·상태(코드 이름)·공장·대상·대상 이름·제목·우선순위·신고자·신고 시각·담당·기한·착수·완료·현상(코드 이름, 없으면 현상 설명)·원인·조치·작업 내용·설비 정지(Y/N)·정지 분·공수 분 |
| 시각 | 조회가 이미 대상 공장 시간대로 바꿔 둔 값을 **현지 시각 글자**(`yyyy-MM-dd HH:mm`)로 낸다 — 공용 DATETIME 칸은 회사 시간대로 다시 바꾸므로 공장마다 시간대가 다르면 화면과 어긋난다 |
| 정지 분 | 정지 시작·끝이 둘 다 있을 때만(내림), 공수 분 = 공수 합 |
| 결정 | 상위 판단(사용자 지시 '멈춘 세션은 추천안으로'): 시각 = 공장 현지 글자(추천) / 다른 안 = 공용 DATETIME(회사 시간대, 엑셀 날짜 형식이지만 다공장에서 화면과 다름) |

### 13.3 C15 상세 — 설비 예비품 엑셀 (2026-10-22, V137·임시 V9611)

| 항목 | 내용 |
|---|---|
| 연결 표 | `POST /api/cms/spares/export` — 목록과 같은 함수(`SpareService.list`)·조건(검색어·연결 단위·사용)·상한. 열: 연결 단위·설비·클래스·대상 이름·품목·품목명·규격·설치 수량·단위·핵심 예비품·사용·비고 |
| 사용 이력 | `POST /api/cms/spares/usage/export` — 사용 이력 조회와 같은 함수(기간 필수, 설비·클래스·품목). 화면은 고른 연결 + 기간을 그대로 넘긴다. 열: 기록 시각(회사 시간대 글자)·작업지시 번호·유형·상태·대상·품목·수량·단위·기록자. 파일 이름 "설비 예비품 사용 이력" |
| 수량 | **숫자 칸**(최대 6자리, 끝 0 채우지 않음 — 단위마다 자릿수가 다르다). 합계를 엑셀에서 낼 수 있게 글자로 내지 않는다 |
| 코드 | 연결 단위(`CMS_SPARE_KIND`)·핵심 예비품(`CMS_SPARE_KEY`) 코드 그룹 — 이름 키는 화면 문구를 그대로 쓴다(전역, 회사 추가 불가) |
| 결정 | 상위 판단(사용자 지시 '멈춘 세션은 추천안으로'): 수량 = 숫자 칸(추천) / 다른 안 = 화면과 같은 글자(자릿수는 맞지만 합계 불가) |

### 13.4 C16 상세 — 기준정보 엑셀 3종 (2026-10-23, V143·임시 V9613, 결정 docs/29 #49 뒤 첫 단계)

| 항목 | 내용 |
|---|---|
| 길 | 세 화면 grid 의 **엑셀 메뉴**(`exportUrl`, 공용 grid 규약) → `POST /api/cms/{fail-codes,equip-profiles,pm-plans}/export`(`@ProgAuth EXCEL`). 본문 = grid 조건(`params()`) + 지금 정렬 + 보이는 열 |
| 조건·순서 | grid 와 **같은 검사**(`checkListArgs`)·같은 매퍼 조건 조각(`find*Page`·`count*`)·같은 정렬 허용 목록(`PageQuery.sortOnly`, 밖이면 400)·같은 기본 순서. 행 수 상한은 공용 정책(`EXCEL_EXPORT_MAX_ROWS`, 넘으면 TOO_MANY_ROWS) — 목록 상한(1000)과 다르다 |
| 열 | 요청한 열은 **grid 열 정의로 검사**(없음·중복 = 400) 뒤 그 순서. 엑셀 전용 이름(`keyEquipNm`·`stateNm` 등)은 직접 고를 수 없다 |
| 사람이 읽는 열 | 화면이 행 값으로 꾸미는 칸은 엑셀에서 바꾼다(`CmsSupport.exportColumns` 의 바꿈 표): 핵심 설비 → 예/아니오(공용 YN 문구 "사용/미사용"은 뜻이 달라 안 씀) / 계획 다음 예정 → **다음 예정일(날짜 셀)** + 다음 예정 사용량 + 지금 누적 / 상태 → 이름(내보내는 시각 기준 계산, 화면과 같은 `decorate`) / 주기 → 종류·간격·단위·기준(코드 이름)·카운터·사용량 간격·사용량 단위(사용량 값은 카운터 자릿수 글자 — 화면과 같다). 유형·분류·실행 방식은 코드 이름, 사용 여부는 공용 YN |
| 결정 | 상위 판단 범위(결정 #49 "범위를 짧게 회신 후 진행"): 합친 칸 = 나눔(추천, 엑셀에서 거르고 셀 수 있다) / 다른 안 = 화면 글자 그대로("1개월마다 · 달력 고정" 한 칸 — 거르기 어렵다) |

### 13.5 C17 상세 — 설비 이력 카드 단독 화면 (2026-10-23, V146·임시 V9614)

| 항목 | 내용 |
|---|---|
| 화면 | `cms-equip-card`(SEARCH·EXCEL, 설비관리 메뉴). 조건 = 설비 1대(필수 — 코드·이름으로 좁혀 고름, **사용 중지 설비 포함**) + 기간(공장 현지 날짜, 필수) + 종류. `?equip=설비코드` 로 열면 그 설비로 바로 조회 — 설비 프로필 상세 "이력 카드 열기". 프로필 아래 카드(설치 자산·최근 1년 작업지시)는 그대로 |
| 요약 | 프로필(공장·클래스·중요도·핵심·담당), 작업지시(신고 시각 기준, 취소 제외: 전체·고장·개선·예방·진행 중·기록 정지 분 합), 점검(**예정일 기준**: OK·NG·건너뜀·예정, 지연 = 열림 + 예정일 < 오늘), 사용 예비품(기록 시각 기준: 줄 수·품목 수), 카운터 리셋 수, 지금 설치된 금형·지그·게이지, 카운터 지금 누적(설비 것 + 지금 설치된 자산 것). MTBF/MTTR 은 내지 않는다(D10 — KPI 화면) |
| 이력 한 표 | hb.grid — 작업지시(신고 시각)·점검 실행(실시 시각, 없으면 예정일 0시)·사용 예비품(기록 시각)·카운터 리셋(읽은 시각, 무효 제외)을 한 표로(`UNION ALL`, 종류 조건이면 그 가지만). 열: 시각(공장 현지 글자)·종류(코드 `CMS_HIST_KIND`)·번호·내용·상태·수량(서버가 사용자 언어로)·기록자(이름). 정렬 허용 = 시각·종류·번호, 기본 최신순. 고른 행에 작업지시가 있으면 "작업지시 열기"(`/page/cms-mwo?mwo=id` — 작업지시 화면이 그 상세를 연다, 목록 조건 밖이어도) |
| 엑셀 | grid 메뉴 — 같은 조건·정렬·보이는 열(공용 규약) |
| 기간 | 최대 1년 — 공통 기간 검사(`DateRanges`), 회사 정책 `SEARCH_MAX_RANGE_DAYS` 로 더 좁힐 수 있음 |

| # | 질문 | 채택 | 다른 안 |
|---|---|---|---|
| D38 | 권한 | **카드 SEARCH 만으로 요약·이력 읽기**(상위 채택 2026-10-23, 조건 둘) — 읽기 전용 모음 화면이고 설비 보전 이력은 같은 보전 조직이 본다. 조건 ① 이력 행은 요약 칸만(번호·종류·시각·상태·수량·짧은 제목·기록자 — 작업 내용·원인·비용·첨부는 싣지 않는다) ② 링크한 화면은 원래 권한 그대로(카드 권한으로 우회 안 됨 — `CmsEquipCardTest` 권한 시험: 카드만 가진 사용자는 작업지시 상세·목록 API 403, 화면 4xx) | 섹션마다 해당 화면 SEARCH 요구 — 권한 조합마다 빈 섹션, 설명이 꼬인다 |
| D39 | 기간 상한 | **최대 1년(공통 기간 규칙, 회사 정책으로 더 좁힐 수 있음)** — 공통 `DateRanges` = min(366일, 회사 정책 `SEARCH_MAX_RANGE_DAYS`, 기본 366). 다른 조회 화면과 같은 규칙(상위 확인 2026-10-23) | 정책과 무관한 고정 1년 — 회사가 줄인 정책을 이 화면만 넘는다 |
| D40 | 대상 | **대상 = 설비인 행만**. 금형·지그 대상 작업지시·점검은 그 자산의 기록(설치 이력이 바뀌면 설비 귀속이 흔들린다). 카운터는 지금 설치된 자산 것도 보여 준다(현재 상태) | 설치 기간으로 자산 기록을 설비에 붙임 — 시점별 설치 이력 조인, 이동한 금형의 기록이 두 설비에 걸친다 |

### 13.6 C19 상세 — 위험성평가 참여자 작업자 (2026-10-23, V150·임시 V9615)

| 항목 | 내용 |
|---|---|
| 표 | `cms_risk_member.worker_id`(nullable, FK `mst_worker`) + CHECK `ck_cms_risk_member_who`(사용자·작업자 동시 금지). 가드 함수 교체: 넣거나 바꿀 때 **같은 회사·사용 중 작업자만**(`fk_cms_risk_member_worker_comp`), 확정 뒤 작업자 칸도 불변(사용자 칸과 같은 규칙) |
| 읽기 | `mst_worker` 는 기준정보 — CMS 매퍼가 **직접 읽기만**(설비·품목과 같은 "기준정보 읽기", 쓰기 없음 — 상위 결정 2026-10-23). 고르기 목록 = 사용 중만, 코드순 상한 2000 |
| API·화면 | 참여자 추가 `workerCd`(대소문자 무관) — 사용자(`loginId`)와 둘 중 하나(둘 다 = 400 `USER_OR_WORKER`), 없음·사용 중지·다른 회사 = 400 `NO_SUCH_CODE`. 이름을 비우면 그때 작업자 이름 스냅샷. 참여 표에 "이름 (작업자 코드)". 근로자 역할 + 참여일이면 확정 검사(근로자 참여) 통과 |
| 사용 중지 | 이미 넣은 행은 그대로(기록) — 새로 고를 때만 막는다 |
| 하지 않은 것 | 배지 찍어 참여 확인(POP) — POP 고장 신고와 같이 MES 경계 협의 뒤 |


### 13.7 C21 상세 — grid 엑셀 통일 1묶음: 작업지시·점검 실행 (2026-10-23, V152·임시 V9617)

| 항목 | 내용 |
|---|---|
| 길 | C16 과 같다 — grid 메뉴 엑셀(`exportUrl`) → 본문 = grid 조건 + 지금 정렬 + 보이는 열, 같은 검사(기간 필수)·같은 매퍼 조각(`find*Page`·`count*`)·정렬 허용 목록(`PageQuery.sortOnly`)·열은 grid 열 정의로 검사(`CmsSupport.exportColumns`) |
| 작업지시 | `POST /api/cms/mwos/export` 하나에서 **열 목록이 있으면 grid 메뉴 엑셀**(보이는 열, 우선순위 = 이름 긴급·보통·낮음), **없으면 지금처럼 보고서형 전체 칸**(C14 — 툴바 "엑셀 내려받기", 21칸·목록 순서). 동작 EXCEL 은 V136 그대로 |
| 점검 실행 | `POST /api/cms/chk-runs/export` 새로(`@ProgAuth EXCEL`, V152·임시 V9617 이 `cms-chk-run` 동작에 EXCEL). 예정 상태 = 이름(예정·할 때·지남·기한 안 완료·늦게 완료), 수행 시각 = 공장 현지 글자 |
| 결정 | 상위 배정(C16 방식 그대로). 작업지시 두 엑셀을 하나로 합치지 않은 이유: 보고서형(원인·조치·작업 내용·정지 분·공수 분)은 grid 칸이 아니라 열 고르기로 대신할 수 없고, 화면 표대로의 엑셀은 grid 메뉴가 맡는다 — 같은 끝점·같은 조건 검사 |
### 13.8 C21 상세 — grid 엑셀 통일 2묶음: 카운터·알람 이력·상태 검토 (2026-10-23, V155·임시 V9618)

| 항목 | 내용 |
|---|---|
| 길 | 13.7 과 같다(C16 방식) — grid 조건 + 지금 정렬 + 보이는 열, 같은 검사·같은 매퍼 조각, 열은 `CmsSupport.exportColumns` |
| 카운터 | `POST /api/cms/counters/export`. 누적 = 글자 + **단위 칸 따로**(화면은 "1200 타" 한 칸 — 엑셀은 숫자 글자와 단위를 나눠 거르기 쉽게), 수명 = 화면 글자 그대로(수명 없음 = 빈 칸, 타수 자릿수 구분 없음), 마지막 읽기 = 대상 공장 현지 글자 |
| 알람 이력 | `POST /api/cms/alarms/export`. 공장·기간 필수(grid 와 같다), 발생·해제 = 공장 현지 글자, 반복 = "반복"(정책 창·횟수 판정은 grid 와 같은 매퍼), 열린 알람만 조건 그대로. 화면 JS 가 grid 에 권한 액션을 넘기지 않던 것(`actions: []`)을 페이지 액션으로 — 엑셀 메뉴 노출용 |
| 상태 검토 | `POST /api/cms/state-review/export`. 공장·기간 필수, 시작·끝 = 공장 현지 글자(열린 구간 끝 = 빈 칸), 분류·미검토 조건 그대로 |
| 권한 | V155·임시 V9618 이 `cms-counter`·`cms-alarm`·`cms-state-review` 동작에 EXCEL. 세 끝점 `@ProgAuth EXCEL` |
| 결정 | 계산 값(수명·반복·분)은 grid 처럼 정렬 밖. 엑셀 전용 이름(`unitNm`·`lifeNm`·`repeatNm`·`*Text`)은 요청 열로 받지 않는다(400) — 열 이름은 grid 열 정의만 |

### 13.9 C22 상세 — 작업지시 "POP 신고" 필터 (2026-10-23, 마이그레이션 없음)

MES POP 고장 신고 화면(#393)이 들어와 C20 계약의 CMS 쪽 마무리(상위 배정 ②).

| 항목 | 내용 |
|---|---|
| 조건 | `source` = 없음(전체) / `POP`(POP 고장 신고만 = `report_req_id` 있음 — V151 CHECK 로 작업자·단말·요청 id 가 함께만 있으므로 하나로 판정). 목록·grid·툴바 엑셀(보고서형)·grid 메뉴 엑셀 모두 같은 `MwoCrit`. 다른 값 400 |
| 화면 | 검색 패널 "신고 경로: 전체 / POP 신고"(범위 옆, 같은 분할 버튼 모양) |
| 엑셀 신고자 | 보고서형 엑셀 "신고자" 칸이 사무 계정 이름만 싣고 **POP 신고는 빈 칸**이었다 → 화면 상세와 같은 글자 "작업자(POP 단말)"(`js.cms.mwo.popReporter`). 자동 생성(예방점검·점검 이상)은 그대로 빈 칸 |
| 결정 | "사무 신고만" 값은 두지 않았다 — 사무 계정 신고는 범위 "내 신고"·고장 신고 화면이 이미 있고, 개선·예방은 신고자가 없어 "사무"의 뜻이 흐려진다. 필요해지면 `OFFICE` 를 더한다 |
| 확인 | ZT 단말 등록·활성화 → 배지 → POP "고장 신고"(ZT-PRS-01) → CMS 작업지시 "POP 신고" = 그 1건, 상세 신고자 "ZT 현장작업자(POP ZT-TAB-01)" |

### 13.10 C23 상세 — grid 엑셀 통일 3묶음: 알람 정의·상태 사유·점검표 (2026-10-23, V166·임시 V9619)

| 항목 | 내용 |
|---|---|
| 길 | 13.7·13.8 과 같다(C16 방식) — grid 조건 + 지금 정렬 + 보이는 열, 같은 검사·같은 매퍼 조각, 열은 `CmsSupport.exportColumns` |
| 알람 정의 | `POST /api/cms/alarm-defs/export` — 자동 고장 신고 = 화면처럼 Y 만 "자동 신고"(N = 빈 칸), 심각도 코드 이름 |
| 상태 사유 | `POST /api/cms/state-reasons/export` — 계획 = Y 만 "계획"(화면이 YN 열로 두지 않은 이유와 같음: N 이 "미사용"으로 읽힘), 분류 코드 이름, 기본 순서 분류 → 순서 → 코드 |
| 점검표 | `POST /api/cms/chk-tmpls/export` — 대상 종류 = 이름(설비·금형·지그), 항목 수·계획 수는 계산 값이라 정렬 밖 |
| 권한 | V166·임시 V9619 가 세 화면 동작에 EXCEL. 세 끝점 `@ProgAuth EXCEL` |
| 범위 | 이것으로 CMS 목록(grid) 화면은 모두 grid 메뉴 엑셀. 남은 화면(고장 신고·KPI·상태 타임라인·설비 카드)은 목록이 아니거나 자체 엑셀 |

### 13.11 C24 상세 — 위험성평가 요인 삭제 때 그 요인 첨부 정리 (2026-10-23, 마이그레이션 없음)

| 항목 | 내용 |
|---|---|
| 전 | 작성 중 요인을 지우면 그 요인의 증빙 첨부(`sys_file`, 대상 `cms-risk` × 요인 id)는 사용 중인 채 남고, 잠금 규칙(`RiskFileLock` — 없는 요인 = 잠김) 때문에 아무도 못 지웠다. 화면에는 안 보이지만 파일·용량은 남는다 |
| 후 | `RiskService.deleteItem` — ① 작성 중 잠금 ② **그 평가의 요인인지 먼저 확인**(아니면 404 — 첨부를 건드리지 않는다) ③ 그 요인 첨부를 공용 `FileService.delete` 로 하나씩(사용 중지 + 접근 로그 — 사람이 지운 것과 같은 기록) ④ 요인 삭제. 공용 삭제가 호출 트랜잭션에 참여(REQUIRED)하므로 ④가 실패하면 ③도 롤백 |
| 순서 이유 | 첨부를 먼저 지운다 — 요인을 먼저 지우면 잠금 규칙이 "없는 요인"으로 보고 지우기를 막는다 |
| 결정 | 상위(2026-10-23): 공통 "대상 삭제 훅" 없이 CMS 서비스가 같은 트랜잭션에서. 다른 안 = 공용 첨부에 대상 삭제 훅(모든 모듈 공통) — 공통 변경이라 이번엔 하지 않음 |
| 화면 | 요인 삭제 확인 문구에 "그 요인의 증빙 첨부도 함께 지워집니다" |
| 범위 밖 | 평가 폐기(OBSOLETE)는 요인을 지우지 않으므로 첨부도 그대로(폐기본은 잠김 — C12) |

### 13.12 C25 상세 — POP 고장 신고 사진, CMS 쪽 (2026-10-24, V174·임시 V9620)

계약·결정은 11.17. 여기는 구현.

| 항목 | 내용 |
|---|---|
| 계약 | `BreakdownReport.attachPhoto(Photo)` → `PhotoResult(fileId, duplicate, count)`. `Photo(reqNo, workerId, terminalId, clientReqId, fileName, size, content)` — 웹 타입 없음. NONE = 409 |
| 구현 | `MwoService.attachPopPhoto` (MANDATORY) — ① 칸 검사(reqNo·clientReqId 형식·단말·작업자) ② **작업지시 행 잠금**(`lockPopPhotoTarget`, `FOR UPDATE` — 같은 신고의 동시 사진 줄 세우기) ③ 고장(BM)·**신고한 단말**(`reported_terminal_id`)이 아니면 400 `reqNo`(없음·다른 회사·사무 신고·다른 단말 구분 안 함) ④ 요청 id 다름 400 `clientReqId:MISMATCH` ⑤ REQ·OPEN 아니면 409 `CMS_MWO_STATE` ⑥ 공용 `attachAs`(onlyExt jpg·jpeg·png, maxMb = `CMS_POP_PHOTO_MB`, reuseSameContent) ⑦ 새 사진인데 이미 한도면 409 `file:PHOTO_LIMIT`(저장한 파일·행은 롤백 — attachAs 의 afterCompletion) |
| 개수 | `countAttached(cms-breakdown, 작업지시, "t{단말 id}:")` — 그 단말이 붙인 것만(신고한 단말만 붙이므로 = POP 사진). 접두어를 "t" 하나로 두지 않은 이유: 사무 로그인 아이디가 t 로 시작할 수 있다 |
| 재전송 | 같은 내용(SHA-256)이면 처음 파일·`duplicate`·개수 그대로 — **한도에 닿아 있어도** 재전송은 성공(사진을 새로 만들지 않으므로) |
| 정책 | V174(임시 V9620): `CMS_POP_PHOTO_MB` 5(1~20)·`CMS_POP_PHOTO_MAX` 3(1~10), 문구 ko/en |
| 화면 | CMS 화면 변경 없음 — 작업지시 상세 "신고 사진"(`cms-breakdown` 첨부 목록)에 그대로 보인다. POP 화면은 MES PR |

### 13.13 C26 상세 — 깊은 offset: 지연 조인 (2026-10-24, V180·임시 V9621, QA H2·H4·M1·M6)

QA 조사(docs/qa/2026-10-24_offset-deep-page.md)가 찾은 CMS 4곳. 원인은 같다 — **offset 이 건너뛰는 행마다 select 목록의 상관 부질의를 계산**(알람 반복 창 수 = 같은 원장 count, 점검 실행·계획·카운터 = 계수기 판독 전체 합). 방법은 WMS docs/30 5.1 과 같다.

| 항목 | 내용 |
|---|---|
| 지연 조인 | **안쪽** = 거름·정렬·쪽 고르기에 쓰는 칸만으로 PK 한 쪽(`limit/offset`), **바깥** = `PK in (안쪽)` 행에만 무거운 칸(반복 창 수·판독 합·NG 수·열린 회차·단말·작업지시)을 붙이고 같은 정렬로 다시 줄 세움. 정렬 조각(`alarmSort`·`runSort`·`counterSort`·`planSort`)을 안쪽·바깥이 같이 쓰고 끝에 PK(쪽 경계 고정) |
| 안쪽 = 행별 계산 없음 | QA offset 가드(#484, `OffsetLedgerGuardTest`)가 안쪽 층의 `lateral`·select 목록 부질의를 막는다 → 알람: 정의 `lateral` 대신 두 왼쪽 조인(`dc` 클래스 정의·`dn` 클래스 없는 정의 — `uq_cms_alarm_def_cd` 로 각 한 행 이하, 같은 고르기). 점검 실행: 파생 표 없이 FROM·조건(`runLiteFromWhere`), 자산 설치 공장 부질의는 공장 조건(WHERE)에서만. 계획: 파생 표지만 주 표가 원장 아님. 카운터: 최근 판독 시각은 **그 정렬을 고를 때만 정렬 식**에서 계수기마다 색인 한 건 — 가드가 보지 않는 자리지만 계수기는 수백 개(원장 아님)라 깊은 offset 문제가 아니다. 가드 허용 목록에서 CMS 4줄 삭제 |
| 건수·목록 | 점검 실행·계획 **건수(count)가 무거운 칸까지 다 만든 뒤 셌다** → 가벼운 칸(`runLiteRows`·`planLiteRows`)으로. 목록(행 수 상한 1,000/2,000)도 같은 지연 조인 |
| 판독 합 색인 | V180(임시 V9621) `ix_cms_counter_reading_sum (comp_cd, counter_id) INCLUDE (delta_qty) WHERE void_yn = 'N'` — 계수기별 합·수가 색인만 읽기(가시성 지도가 채워진 뒤 — 운영은 autovacuum) |
| 결정 | 계수기 누계 칸 + 판독 트리거(QA M1 후보)는 고르지 않았다 — 정정(무효)·리셋·동시 입력마다 칸을 맞춰야 하고, 지연 조인 + 색인으로 화면이 1초 안에 든다. 판독이 계수기당 수만 건을 넘어 다시 느려지면 그때 누계 칸 |

실측(`CmsDeepPagePerfTest`, `-Dhb.perf=true`, 행 수 `HB_PERF_ROWS`, 50행 쪽, 세 번 중 최소, Testcontainers PostgreSQL 16 — 절대값보다 전후 비교):

| 화면 | 행 | 전: 첫·가운데·끝 쪽 | 후: 첫·가운데·끝 쪽 |
|---|---|---|---|
| H2 알람 이력 | 10만 | 103 · 620 · 1,067 ms | 79 · 77 · 75 ms |
| H2 알람 이력 | 100만 | 945 · 21,847 ms · **시간 초과**(30초) | 201 · 261 · 299 ms |
| H4 점검 실행 | 10만 | 221 · 7,057 · 13,409 ms | 209 · 291 · 250 ms |
| H4 점검 실행 | 100만 | 271 ms · **시간 초과** · **시간 초과** | 580 · 1,057 · 1,641 ms |
| M1 카운터(200개) | 판독 10만 | 83 · 140 · 290 ms | 88 · 83 · 106 ms |
| M1 카운터(200개) | 판독 100만 | 1,627 · 2,563 · 3,977 ms | 1,031 · 1,005 · 388 ms |
| M6 예방점검 계획 | 334 / 3,334 | 20 · 36 · 60 / 228 · 5,052 · 10,158 ms | 25 · 28 · 30 / 48 · 56 · 35 ms |

- **QA N-1 뒤(2026-10-24)**: 정렬 조각이 키마다 `nulls last` 를 붙여 NOT NULL 키(`raised_at` 등)의 desc 정렬도 색인 역순 스캔을 못 썼다 → null 이 될 수 있는 키의 desc 에만 `desc nulls last`, NOT NULL 키는 `desc`(asc 는 기본이 nulls last 라 같음). 다시 잰 값: H2 알람 이력 100만 **첫 쪽 201 → 22 ms**(기본 정렬 발생 시각 desc), 가운데·끝 305·319 ms, 10만 28·78·83 ms. 점검 실행(기본 예정일 asc)·카운터·계획은 기본 정렬이 asc 라 그대로(672·1,292·1,653 / 1,206·1,242·420 / 57·52·34 ms — 측정 흔들림 범위). QA 가드(#514 `SortNullsLastGuardTest`) 표의 나머지 CMS 6문장(고장 코드·설비 프로필·작업지시·상태 검토·설비 카운트·점검표)도 같은 키별 꼴로 — KNOWN 의 CMS 줄 없음
- H4 100만 끝 쪽 1.6초는 안쪽의 정렬(100만 행 중 기간 안 전체)이 남은 비용 — 기간(366일)·공장 조건이 좁히면 준다. M1 첫 쪽 1.0초는 계수기 50개 × 판독 5천 건 합(시험 DB 는 막 넣은 행이라 색인만 읽기가 덜 먹는다)
- 실측 데이터: 알람 = 설비 50 × 코드 5 × 89일, 점검 실행 = 계획 N/300 × 300일·계수기 50·판독 N/5, 카운터 = 200개·판독 N. 앱 계정 문장 시간 제한(30초) 때문에 2만 행씩 나눠 넣는다


### 13.14 C27 상세 — 예방점검 계획 달력 보기 (2026-10-26, 마이그레이션 없음)

5장 화면 표가 `cms-pm-plan` 에 약속한 "달력 보기(월, 읽기 전용)". C2 때 "사용량 주기(C3) 뒤" 로 미뤘다.

| 항목 | 내용 |
|---|---|
| API | `GET /api/cms/pm-plans/calendar?plantCd&month` — `SEARCH`(`cms-pm-plan`). 공장 필수(그 회사 사용 중 공장이 아니면 400 `plantCd:NO_SUCH_CODE`), 달 `YYYY-MM`(비우면 공장 오늘의 달), **공장 오늘 기준 앞뒤 24개월**(밖이면 400 `month:RANGE`) |
| 시간대 | 달 범위·"오늘"·예정 상태(UPCOMING/DUE/OVERDUE)는 **공장 시간대**(`BizClock.zone(plant)`) — 같은 예정일이 공장마다 "할 때"·"지남" 으로 갈릴 수 있다(시험: 서울 vs 키리티마티 UTC+14) |
| 대상 공장 | 계획·실행 대상의 공장 — 설비는 설비 공장, 금형·지그는 지금 설치된 설비의 공장(계획 목록과 같은 식) |
| 만든 예정(RUN) | 그 달이 예정일인 점검 실행 — 상태 무관(열림·완료·건너뜀). 열린 달력 예정은 예정 상태 배지, 끝난 것은 실행 상태 배지 |
| 예상(FORECAST) | **사용 중 달력 주기 계획만**, 아직 만들지 않은 예정. 시작 = 다음 예정일(열린 예정이 있으면 그것은 RUN 이라 그다음부터). `PmDue.next` 로 이어 감 — **매번 예정일에 했다고 보고**(미리보기와 같음), **지난 예정은 오늘 했다고 보고**(FIXED = 오늘 뒤 첫 고정 예정, 놓친 회차 건너뜀 / FLOAT = 오늘 + 간격). FIXED 는 첫 예정일에서 다시 더한 값이라 달 앞까지 바로 건너뛴다(월말 1/31 → 2/28·윤년 2/29 → 3/31). 같은 계획·같은 날 RUN 이 있으면 예상은 빼고 RUN 만 |
| 사용량 주기 | **날짜를 예측하지 않는다** — 사용 속도가 일정하지 않고(타수·가동 시간), 예측 날짜가 기한처럼 읽힌다. 만든 예정(RUN, 예정 사용량 표시)만, 화면에 "사용량 기준 — 만든 예정만" 문구와 그 공장 사용량 계획 수 |
| 상한 | 계획 1,000개·항목 2,000건 — 넘으면 앞부분만 + `truncated`(화면 "공장을 나눠 보세요"). 계획마다 예상 계산 1,000걸음 상한(FLOAT 날 단위 24개월 ≈ 730) |
| 화면 | 계획 grid 위 "달력 보기" ↔ "목록 보기", 달 이동(‹ 달 ›), 월요일 시작 칸(오늘 테두리), 항목 = 계획 코드 + 배지(예상 = 점선, 만든 예정 = 실선) — 누르면 계획 상세. 한 칸 4건 넘으면 "+n건 더". **좁은 화면(≤ 600px)은 칸 대신 날짜 목록**(같은 내용). 공장은 검색 패널 값 — 비우면 "공장을 고르세요". 스타일은 `css/views/cms.css`(공통 page.css 안 고침) |
| 결정 | 마이그레이션·새 권한 없음(SEARCH). 예상은 저장하지 않고 조회 때 계산 — 계획 규칙이 바뀌면 바로 반영, 저장된 예상과 실제가 어긋날 일이 없다. 새 공통 부품(hb.*) 없이 화면 JS 안에서(공장 휴일 달력과 같은 꼴) |


### 13.15 C28 상세 — POP 사진 한도 계약 (2026-10-26, 마이그레이션 없음, MES 요청)

| 항목 | 내용 |
|---|---|
| 계약 | `BreakdownReport.PhotoLimits(int maxCount, long maxBytes, List<String> exts)` + `photoLimits()` — 호출자 트랜잭션 안(SUPPORTS·읽기 전용, 회사 범위 정책), 예외 없음. **NONE = (0, 0, [])** |
| 값 | maxCount = `CMS_POP_PHOTO_MAX`(한 단말 × 한 신고), maxBytes = min(`FILE_MAX_MB`, `CMS_POP_PHOTO_MB`) × 1MiB(**이 바이트까지 통과**), exts = {jpg, jpeg, png} ∩ 회사 `FILE_ALLOWED_EXT` − 항상 거부 목록(소문자·점 없이·정렬) |
| 같은 계산 | 공용 첨부 `FileService.limitsOf(AttachOptions)` → `AttachLimits(exts, maxMb).maxBytes()` 를 꺼내 **attachAs 의 판정이 같은 메서드를 쓴다**(따로 계산하면 한도 알림과 실제 거부가 어긋난다). CMS 는 `popPhotoOptions()` 하나를 attachPopPhoto·popPhotoLimits 가 함께 |
| 시험 | 한도 바이트 = 통과, +1 바이트 = 거부(FILE_REJECTED), 회사 허용에서 빠진 jpeg = 거부, FILE_MAX_MB 가 더 작으면 그쪽, B사는 B사 정책 |


### 13.16 C29 상세 — 카운터 grid 남은 비용, 안 A (2026-10-26, V192·임시 V9622)

13.13(C26) 뒤에도 판독 100만에서 카운터 grid 가 쪽마다 1초 안팎이었다. 상위 결정: **안 A**(바깥 50행만 집계 + 색인 확인) 먼저 실측, 모자랄 때만 안 B(계수기 누계 칸 — 원장 규약 영향) 제안.

| 항목 | 내용 |
|---|---|
| 원인(실행계획) | ① **마지막 계기 값** 부분질의가 계기 값 없는 판독(증가량 직접·자동 입력)까지 담은 색인으로 계수기의 판독 전체를 훑었다 — 쪽(50개)마다 약 25만 블록, 1.1초. ② 판독 합·판독 수가 같은 행을 두 번 훑었다 |
| ① 색인 | V192(임시 V9622) `ix_cms_counter_reading_gauge (comp_cd, counter_id, read_at, reading_id) WHERE void_yn = 'N' AND gauge_value IS NOT NULL` — 계수기마다 역순 한 건(1.1초 → 0.3ms) |
| ② 한 번에 | `counterFrom` 에 `left join lateral (sum, count)` 하나 — 바깥(그 쪽 계수기)에서만, 안쪽 층은 그대로(offset 가드 통과). 목록·상세·설비 이력 카드도 같은 조각 |
| 결과 | 판독 100만(계수기 200): 첫·가운데·끝 쪽 **1,206 · 1,242 · 420 → 84 · 97 · 54 ms** — 안 A 로 충분, **안 B 는 하지 않는다**(13.13 결정 그대로: 누계 칸은 무효·리셋·동시 입력마다 맞춰야 할 것이 늘어난다) |


### 13.17 C30 상세 — 점검 실행 grid 끝 쪽, 실행계획 먼저 (2026-10-26, 마이그레이션 없음)

상위 결정: 실행계획을 먼저 보고 **효과가 있을 때만** 색인(임시 V9623). 기본 정렬 = 예정일·PK, 기간 366일 안 100만 행.

| 모양(안쪽 쪽 고르기 쿼리, 실행계획) | 첫 쪽 | 가운데 | 끝 쪽 |
|---|---|---|---|
| 그때까지(계획 **inner** join — 행마다 계획 조회) | 26.6 ms | 573 ms | 1,125 ms |
| 계획 **left** join(plan_id NOT NULL·FK 라 결과 같음 → 계획 칸을 안 쓰면 플래너가 조인을 뺀다) | 2.3 | 389 | 723 |
| inner join + 색인 (comp_cd, due_date, run_id) | 0.2 | 434 | 840 |
| left join + 색인 | 0.1 | 294~402 | 571~652 |

- **결정: left join 만, 색인 없음.** 색인은 정렬(Incremental Sort)을 없애 첫 쪽을 상수로 만들지만, 깊은 쪽은 offset 만큼 힙을 읽는 것은 같아 10~20% 남짓 — 쓰기(정기 작업이 만드는 실행 행)마다 색인 하나와 마이그레이션을 더할 만큼은 아니다. 첫 쪽은 left join 만으로 2ms
- grid 전체(바깥 층 포함, `CmsDeepPagePerfTest` H4, 100만): **398 · 730 · 1,123 → 371 · 676 · 986 ms**. 남은 첫 쪽 약 370ms 는 바깥 층의 행별 계수기 판독 합(쪽 50행)이다 — 실측 데이터는 vacuum 전(색인만 읽기에 힙 확인이 붙음)이라 운영(autovacuum)보다 크게 나온다(13.16 과 같은 관찰). 깊은 offset 자체는 쪽 번호에 비례 — 끝까지 넘기는 화면이 아니면(기간·공장·상태 조건) 줄어든다
- 건수(count)·목록도 같은 FROM 이라 같이 가벼워진다

### 13.18 QA G-2 — 설비 보안 grid 정렬을 SQL 로 (2026-10-28, 마이그레이션 없음, 사용자 결정)

| 항목 | 내용 |
|---|---|
| 문제 | 설비 보안 grid(#609, docs/40 10.2)만 목록을 다 읽은 뒤 **Java 문자열 비교로 메모리 정렬** — DB 정렬 규칙이 언어 정렬(glibc `ko_KR.UTF-8`·ICU)이면 기본 순서가 목록·엑셀(SQL `order by e.plant_cd, e.equip_cd`)과 갈린다(예: `A_1, A-2, A.3, A1, AB` vs 바이트 순서 `A-2, A.3, A1, AB, A_1`). 시험 DB(alpine musl)는 바이트 비교라 겉으로 안 보였다(QA 재현-부분) |
| 결정(사용자) | **경고 필터가 없으면 SQL 정렬**(목록과 같은 ORDER BY, 정렬 허용 목록) — 다른 grid 와 같은 방식. 비교를 Collator 로 맞추는 안은 고르지 않았다(DB 규칙과 같다는 보장이 없다) |
| 구현 | 경고 필터 없음 **그리고** 정렬이 저장 칸(공장·설비 코드·이름·OS·EOS·점검일·검토일)뿐 → `findSecPage`(허용 목록 enum 이름, 빈 값 가능 칸은 desc 에서도 맨 뒤 — 메모리 정렬과 같은 규칙, 마지막 설비 id) + `countSecs`. 경고·등급 글자는 그 쪽 행에만 계산. 조건 조각 `secWhere` 를 목록과 공유 |
| 메모리로 남는 것 | 경고 필터(계산 값)·등급/경고 글자 정렬(서버가 만든 글자) — 그 경우만 행 수 상한(MAX_ROWS)도 목록과 같다. SQL 쪽은 상한 없음(쪽만 읽는다) |
| 메모리 경로도 SQL 순서(G-2 잔여, 사용자 결정 2026-10-28, QA 8장) | 경고 필터·계산 칸 정렬일 때도 **행은 SQL 이 저장 칸 정렬(요청 순서 그대로, 마지막 설비 id)로 준다**(`findSecsSorted`, 정렬 조각 `secOrder` 를 SQL 쪽과 공유). 저장 칸은 Java 로 다시 비교하지 않고 그 순서의 **묶음 번호**(앞 칸부터 그 칸까지 값이 같은 줄 = 같은 번호)로, 계산 칸만 값으로 비교해 **안정 정렬** — 같은 값은 SQL 순서 유지. 경고 필터 + 기본 순서 = 같은 필터의 목록·엑셀 |
| 시험 | `CmsEquipSecTest` 의 grid 시험(목록과 같은 행·쪽 경계·계산 칸 정렬·필터·권한·A사로 B사) 그대로 통과, QA `GridScreensQaTest` G-2 관찰 기대를 "SQL 순서 = 목록 순서" 로 |
| 시험(잔여) | `CmsEquipSecTest` +1 — 경고 필터 기본 순서 = 목록 = 엑셀, 이름에 U+FF5E·이모지(UTF-16 과 UTF-8 바이트 순서가 반대)를 넣어 시험 DB(바이트 비교)에서도 Java 비교와 갈리게 한 뒤 "경고 필터 + 이름 정렬 = DB 순서", "경고 글자(같은 값) → 이름 = DB 순서". 옛 코드로는 실패(Java 순서)를 확인 |

그룹 A 의 1~5·7~19(13장)는 여기까지(13·16번은 CMS 쪽). 다음 후보는 13장 "뒤" 줄 — 상위 지시를 받은 뒤.

## 14. MES 가 만든 CMS 표 — `cms_equip_count` (2026-10-22, MES 그룹 A 4번, docs/25 4.27 — V139·임시 V9115)

- **표 주인 = CMS**(이름 `cms_`, 6단계 V051 의 상태·연결·에너지 원장과 같은 자리). 만든 쪽은 MES — 수집 COUNT 를 POP 실적 제안에 쓰려고. 원장 규약(occurred_at·recorded_at·entry_method, 역분개 행만, UPDATE/DELETE 차단), RLS, 설비·장치 회사 트리거.
- 값 = 이벤트마다 **증가분**(`GOOD`·`SCRAP`), 누적 카운터 리셋은 수집기 몫(docs/25 D53).
- **C9 성능 KPI 가 읽을 때(후속)**: 지금 C9 의 생산 수는 확정 실적(`prd_result`)이다. 수집 기준 생산 수가 필요해지면 MES 원장을 직접 읽지 말고 **`module.cms.link.EquipCountQuery`**(설비·기간 → GOOD/SCRAP 합, 역분개 포함, 잠금 없음) 계약을 CMS 가 만든다 — 표는 CMS 것이라 구현도 CMS 패키지에. MES POP 제안은 같은 패키지의 매퍼에서 읽는다(지금 `PopWorkMapper.countSuggest`).

### 14.1 C9 수집 기준 연결 (2026-10-22, 상위 배정 — V141·임시 V9612)

KPI 에 **생산 수 기준** 선택을 둔다: 확정 실적(기본, 지금 동작 그대로) / 수집 카운트. 시간 지표(계획·가동·가용률·MTBF·MTTR·PM 준수율)는 두 기준이 같고, 생산 수에서 나오는 성능·품질·OEE 만 달라진다.

| 항목 | 수집 카운트 기준 |
|---|---|
| 생산 수 | `cms_equip_count` 설비·[from, until) 순합 — GOOD + SCRAP, 역분개 포함(`CmsKpiMapper.countSums`) |
| 성능 | 개당 이론 초 × (양품 + 불량) ÷ 가동(PRD) |
| 품질 | 양품 ÷ (양품 + 불량) |
| OEE | 개당 이론 초 × 양품 ÷ 계획 시간(합산 분자·분모 — 곱 아님, 11.8 과 같음) |
| 이론 초 없음 | 그 설비에 수집 수량이 있는데 `cms_equip_profile.ideal_ct_sec` 가 비면 성능·OEE 빈칸 — 라인·공장 합계도 빈칸(실적 기준 D21 과 같은 규칙). 품질은 낸다 |
| 화면 | 조회 패널 "생산 수 기준" 선택(일별 추이·엑셀도 같은 기준), 설비 프로필에 "이론 사이클(초/개)" 칸 |
| **한계** | 이론 속도는 **설비 하나에 하나**다. 한 설비에서 사이클이 다른 품목을 섞어 만들면 수집 기준 성능은 근사(품목별 표준을 쓰는 실적 기준과 다를 수 있다). 품목 혼합 설비는 실적 기준을 본다 |

| # | 질문 | 채택 | 다른 안 |
|---|---|---|---|
| D34 | 수집 기준의 이론 속도 | **설비 프로필 `ideal_ct_sec numeric(10,3)`**(NULL 허용, > 0) — 설비 고정값(ISO 22400 식), 설명이 쉽다 | 같은 기간 확정 실적의 가중 표준(Σ표준시간 ÷ Σ실적 수량)을 수집 수량에 곱함 — 품목 혼합은 반영되나 실적 없는 기간은 빈칸, 두 출처를 섞어 설명이 꼬인다 |
| D35 | 화면 | **기준 선택**(기본 = 확정 실적, 지금 동작 불변) | 수집 기준으로 바꿈 — 확정 실적 기준 KPI 를 쓰던 화면·엑셀이 바뀐다 |
| D36 | 읽는 길 | **CMS 매퍼(`CmsKpiMapper.countSums`)** — 표 주인이 CMS 이고 읽는 쪽도 CMS(같은 모듈)라 계약이 필요 없다. 다른 모듈이 읽게 되면 그때 `module.cms.link.EquipCountQuery` 계약 | 지금 계약 인터페이스 — 구현 하나·사용처 하나인 간접 층 |
| D37 | 역분개를 어느 날에 | **원 행 발생 시각으로**(원 행 + 그 역분개를 같이 센다) — 정정을 언제 넣든 기간 합이 같다(실적 C9 규칙과 같은 효과). 역분개 행은 MES 수집 카운트 정정 화면(`cms-equip-count`, docs/25 4.36)이 쓴다 — 원 행 시각 그대로(이 규칙) | 역분개 행 자기 시각 — 정정한 날 합이 음수가 될 수 있다 |


## 15. UI 단계 3 — 목록 화면 표준 틀 ②~⑥ (2026-10-22, docs/40 P1·V-7, 결정 docs/29 #44·#45)

CMS 17화면에 서버 변경 없이 적용한 것. ①(일반 표 → hb.grid)은 화면별 API **추가**(`…/grid`·`/columns`)가 필요해 따로(#45 = A안, 주 목록만, 3~4화면씩).

| 항목 | 내용 |
|---|---|
| ② 조회 영역 | 목록이 있는 16화면은 이미 `hb-search` 패널. 고장 신고(`cms-breakdown`)는 좁은 화면 신고가 주 기능이고 "내 신고"는 조건 없는 최근 목록이라 패널 없음 |
| ③ 도구줄 순서 | [신규] … [엑셀 내려받기] — 작업지시·예비품·KPI·보안 대장은 이미 그 순서. 위험성평가 상세는 엑셀을 닫기 앞 끝으로 |
| ④ 위험 동작 | 작업지시 **취소**, 위험성평가 **폐기**, 요인 **삭제**를 저장 줄에서 떼어 별도 도구줄로. 요인 삭제는 확인 대화상자를 새로 붙임(전에는 바로 지움). 외곽선 위험 톤 `hb-btn-danger-outline`(공통 #254) — 처음엔 공통에 없어 기존 외곽선이었다가 병합 뒤 교체 |
| ⑤ 상태 표시 | `hb-status` 배지 — 아래 톤 표(`cms-ui.js` 의 `TONES`). 지연은 `danger` "지연" 배지 |
| ⑥ 대화상자 | `confirm`/`prompt` 47곳 → `hb.dialog`. 31곳은 "저장 안 한 입력 — 이동?" 확인(`cmsUi.leaveOk`), 나머지는 사유 입력 4(필수·500자)·위험 확인 3(파일 삭제·폐기·요인 삭제, 위험 톤 + 기본 포커스 취소)·일반 확인 3(수리 확인·완료·확인). 숫자 입력 prompt 는 CMS 에 없음 |

**CMS 상태 톤 표**(부록 A 의 규칙을 CMS 코드에 적용 — 색은 정하지 않고 톤만)

| 대상 | 코드 → 톤 |
|---|---|
| 작업지시 `CMS_MWO_STATUS` | REQ·OPEN neutral ○ / WIP normal ● / DONE·CLOSED success ✓ / CANCELLED neutral ○(사용자가 고른 결과라 빨강 아님 — A.3 과 같음) |
| 점검 실행 `CMS_RUN_STATUS` | 열림 = 예정 상태(UPCOMING neutral / DUE warning / OVERDUE danger), DONE = 기한 안 success · 늦음 warning, SKIPPED neutral |
| 위험성평가 `CMS_RA_STATUS` | DRAFT neutral / IN_APPROVAL info / RELEASED success / OBSOLETE neutral |
| 위험성 수준 `CMS_RISK_LEVEL` | HIGH danger / MEDIUM warning / LOW neutral |
| 알람 중대도 | CRITICAL alarm-1 ◆ / WARN alarm-2 ▲ / INFO alarm-4 ● — 알람 정의·이력 화면 안에서만(알람 색은 알람 밖에서 쓰지 않는다) |
| 보안 대장 경고 | 경고 있음 = warning ▲ |

결정 — 상위 판단(사용자 지시 '멈춘 세션은 추천안으로'): 알람 중대도 3단을 4단 톤에 붙이는 방법 = 중대 P1·경고 P2·정보 P4(추천) / 다른 안 = 경고 P3(호박) — 경고가 "보통"으로 약해 보인다.

부족한 점(공통 — CMS 는 고치지 않음): 외곽선 위험 톤 버튼(WMS 가 추가 예정), 도구줄 안 "오른쪽으로 밀기" 칸(위험 동작을 같은 줄 끝에 두려면 필요 — 지금은 줄을 나눔).

### 15.1 ① hb.grid 전환 — 서버 쪽 (2026-10-22, 결정 docs/29 #45 = A안)

화면별 API **추가만**(기존 목록 API·엑셀은 그대로), 각 화면 **주 목록만**(상세 행 표는 일반 표 유지), 3~4화면 묶음. 화면 전환은 공통 hb.grid 보강(상태 칸 배지·rowClass 훅·비동기 beforeSelect — 시스템관리 세션) 뒤 — 지금 바꾸면 15장 ⑤ 상태 배지가 글자로 돌아간다.

| 항목 | 규칙 |
|---|---|
| API | `GET …/grid`(목록과 **같은 조건 검증 함수** + `page`·`size`·`sort`·`total` → `{list,total}`), `GET …/columns`(ColumnSpec — 화면 표와 같은 칸). 둘 다 `@ProgAuth(SEARCH)` |
| 정렬 | 화면별 허용 목록 enum(`MwoSort`·`SpareSort`·`RaSort`) — 매퍼는 enum 이름으로 `<choose>`(`${}` 금지), 최대 3개, 마지막에 PK(쪽 경계에서 중복·누락 없음). `sort` 는 `request.getParameterValues` 로 받는다(`@RequestParam List` 는 `field,asc` 를 쉼표로 쪼갬) |
| 같은 조건 | 조건 SQL 은 목록과 `<sql>` 조각 하나를 함께 쓴다 — 목록·grid·엑셀이 다른 행을 내지 않게. 기간 필수·상한도 목록과 같음 |
| 기본 순서 | 목록 API 와 같음(작업지시 신고 시각↓, 예비품 대상→품목, 평가 평가일↓) — 시험으로 고정 |
| 쪽 크기 | 정책 `GRID_PAGE_SIZE_MAX` 이하 |

1묶음: 보전 작업지시(`/api/cms/mwos/grid`)·설비 예비품(`/api/cms/spares/grid`)·위험성평가(`/api/cms/risks/grid`).

1묶음 화면 전환(2026-10-22, 공통 hb.grid 보강 #260 뒤): 세 화면 주 목록 표 → `hb.grid`(서버 페이징·정렬·열 설정). 상태 칸은 `GridOptions.status` 에 15장 톤 표(`cmsUi.TONES`)를 그대로 넘긴다 — 작업지시 상태·기한 지남(기한 칸 = 지연 배지), 예비품 사용 여부·핵심, 평가 상태·높음 요인 수·지연 수. 취소·종결·폐기·미사용 행은 `rowClass` → `hb-tree-dim`. 행 이동 전 미저장 확인은 `beforeSelect` 에 `cmsUi.leaveOk()`(Promise). 엑셀은 기존 버튼·API 그대로(grid 메뉴 엑셀은 붙이지 않음 — 엑셀 조건 형식이 다르다). 상세 행 표(공수·부품·요인 등)는 일반 표 유지.

2묶음 서버 쪽(2026-10-22): 고장 코드(`/api/cms/fail-codes/grid`, 기본 유형→순서→코드)·설비 프로필(`/api/cms/equip-profiles/grid`, 공장→설비 코드)·카운터(`/api/cms/counters/grid`, 카운터 코드)·예방점검 계획(`/api/cms/pm-plans/grid`, 다음 예정↑ 비어 있으면 끝→계획 코드). 정렬 밖으로 둔 열: 진행 작업 수(하위 질의)·카운터 누적·수명(계산 값)·계획 예정 상태(서버가 행마다 계산 — SQL 로 정렬 불가). 카운터·계획의 시각·예정 상태는 목록과 같은 장식(`decorate`)을 거쳐 같은 값을 낸다. 주기·다음 예정(날짜 또는 사용량)·예정 상태 배지는 화면이 행 값으로 만든다.

2묶음 화면 전환(2026-10-22): 고장 코드·설비 프로필·카운터·예방점검 계획 주 목록 → `hb.grid`. `GridOptions.format` 은 칸 값만 받으므로(행을 못 봄) 여러 값을 합친 글자(계획 "1개월 · 달력 고정", 다음 예정 "5000회 · 지금 1200회", 카운터 "수명 10만 타 초과")는 grid 칸에서 만들 수 없다 → 값마다 칸으로 나눔: 계획 = 다음 예정일·다음 예정 사용량·상태를 앞에, 주기 종류·간격·단위·사용량 간격은 뒤(우선순위 3 — 좁으면 먼저 숨김), 카운터 = 누적·단위·수명 표시·수명 타수. 수명 표시는 새 문구 `js.cms.counter.lifeTag.*`(타수 없는 짧은 이름), 핵심 설비는 Y 만 "핵심"(YN 열이면 N 이 "미사용" 으로 보여 뜻이 틀림 → TEXT + format). 카운터 마지막 읽기는 대상 공장 현지 시각 글자(DATETIME 열이면 회사 시간대로 바뀜 → TEXT). 고장 코드는 상세 GET 이 없어 grid 행으로 바로 상세(다른 쪽에 있는 방금 만든 코드는 빈 상세 — 목록 시절엔 전체가 한 번에 와서 없던 일). 공통 hb.grid 에 `format(value, row)` 가 있으면 합친 글자를 다시 쓸 수 있다 — 공통 후보로 기록.

3묶음 서버 쪽(2026-10-22): 점검표(`/api/cms/chk-tmpls/grid`, 코드)·점검 실행(`/api/cms/chk-runs/grid`, 예정일↑ — 기간 필수)·알람 정의(`/api/cms/alarm-defs/grid`, 코드 → 클래스 공통 먼저)·알람 이력(`/api/cms/alarms/grid`, 발생↓ — 공장·기간 필수). 점검 실행·알람 이력은 목록의 검증·정규화를 `criteria`/`period` 로, 행 장식(예정 상태·기한 안 완료 / 반복·공장 현지 시각)을 `decorate` 로 함께 쓴다. 정렬 밖: 점검표 항목·계획 수(하위 질의), 실행 예정 상태(계산), 정의 현상 코드, 이력 분·반복(계산). 알람 정의는 같은 코드에서 클래스 공통(클래스 없음)을 먼저 — 정렬 뒤에 `class_cd nulls first` 를 붙여 목록과 같은 순서.

4묶음 서버 쪽(2026-10-22): 상태 사유(`/api/cms/state-reasons/grid`, 분류 → 순서 → 코드)·상태 검토(`/api/cms/state-review/grid`, 시작↓ — 공장·기간 필수, 기간은 공장 시간대). 정렬 밖: 검토 분(계산)·사유(원장/사후 두 칸). **설비 보안 대장은 grid 서버 페이징을 하지 않았다** — 경고 조건(`warn`)이 SQL 이 아니라 서버 계산(`SecWarn`, 정책 주기·날짜)이라 쪽·전체 건수를 SQL 로 낼 수 없다. **결정(2026-10-22, 상위): 일반 표 유지** — 목록 1,000행 상한, 경고 판정은 `SecWarn` 한 곳에만 둔다(SQL 로 옮기면 규칙이 두 곳, 메모리 페이징은 반쪽 페이징). 설비가 늘어 1,000행에 가까워지면 경고 판정 SQL 화(②)를 다시 본다. 고장 신고(현장 입력)·KPI(차트)·상태 타임라인(막대)은 목록 화면이 아니라 전환 대상이 아니다.

3·4묶음 화면 전환(2026-10-22): 점검표·점검 실행·알람 정의·알람 이력·상태 사유·상태 검토 주 목록 → `hb.grid`. 점검 실행 상태 칸은 열림 = 예정 상태, 완료 = 기한 안·늦음(grid 서버가 `state` 에 ON_TIME·LATE 를 넣는다 — 목록 API 는 `onTime` 그대로). 알람 이력은 상위 알람(일반 표, 20행)과 이력(grid)을 따로 읽는다. 상태 검토의 실효 분류 배지 톤은 `cmsUi.TONES.stateCat`(docs/40 부록 A.2 사무 화면 표현). 알람 이력의 중대 행은 `hb-row-warn`. 계획 여부·핵심 설비처럼 Y 만 뜻이 있는 칸은 YN 열이 아니라 TEXT + format(YN 열이면 N 이 "미사용" 으로 보인다).

**첫 조회 한 번만**(2026-10-22, 13화면 공통): `hb.grid` 는 `ready` 에서 첫 쪽을 이미 읽는다 → 그 뒤에 `load()`·`sp.search()` 를 다시 부르면 같은 조회가 두 번 나간다(1·2묶음 화면에 있던 것 — 요청 기록으로 확인). 처음엔 `grid.ready.then(show(null))` 만.

**합친 글자 칸 되살림**(2026-10-22, 공통 #284 `format(value, row)` 뒤): 2묶음에서 값마다 나눴던 칸을 다시 합친다 — 계획 = 다음 예정 한 칸(`nextDueText`: 달력은 날짜, 사용량은 "예정 누적 (지금 누적)")·주기 한 칸("1개월마다 · 달력 고정"), 카운터 = 누적에 단위, 수명은 수명 타수와 함께("수명 10만 타 초과"). 쪼갤 때 만든 칸(간격·단위·사용량 간격·예정 사용량·카운터 단위·수명 타수)과 문구 키 6개는 걷어 냈다. `format` 은 여전히 TEXT 열에만, 빈 값은 부르지 않는다 → 합칠 칸의 필드는 늘 값이 있는 것으로(다음 예정 정렬 키는 `next_due_date` 그대로).

## 16. 3차 — F1~F3 보정 · G1 고장 분석 · G2 수리 시간 분해 · G3 ISO 14224 칸 (2026-11-09, 브랜치 `claude/cms-wave3`, 임시 V9600~)

근거·결정: docs/64(분석) 10장 — 8장 권고 그대로(Q1 ② 대시보드 탭, Q2 ① 칸 추가·선택 입력, Q3 ① 완료 시각, Q4 ① 고침(시험 먼저), Q5 ① 이름을 나눠 따로, Q6 ② G1+G2+G3).
순서: F1 → F2 → F3 → G1 → G2 → G3. 단계마다 이 절 → V 파일 → 서비스·API·화면 → 시험.

### 16.1 F1 — 예비품 사용일 = 작업지시 완료 시각 (마이그레이션 없음)

- **고친 것**: 사용 이력(`/api/cms/spares/usage`·엑셀)·설비 이력 카드(요약 "사용 예비품"·이력 표 PART 줄)의 날짜·기간이 `cms_mwo_part.created_at`(줄 기록 시각)이었다 → **사용일 = `coalesce(작업지시 finished_at, 줄 created_at)`**. 지난 고장을 사후 입력해도 실제 사용일(= WMS 출고 시각, `onMwoDone(…, finishedAt)`)로 잡힌다.
- 완료 전(OPEN·WIP) 줄은 아직 쓰지 않은 것 — 기록 시각을 잠정 사용일로 두고 작업지시 상태를 같이 보인다(완료하면 완료 시각으로 옮겨 간다). **취소된 작업지시의 줄은 사용 이력에서 뺀다**(OPEN 에서 줄을 넣고 취소하면 출고 없이 "사용" 으로 보였다 — 조용히 틀리는 값).
- 응답에 `usedAt`(사용일)을 더하고 `createdAt`(기록 시각)은 그대로 둔다. 화면·엑셀 첫 칸 = 사용일, 조회 조건 이름 "사용일".
- 세 질의(`CmsSpareMapper.findUsage`·`CmsCardMapper.partSum`·`histFrom` PART)가 같은 식을 쓴다 — 하나만 고치면 카드 요약과 이력 표가 어긋난다(docs/64 6장 짝 규칙).

### 16.2 F2 — PM 작업지시 수행 시각 = 작업지시 완료 시각 (마이그레이션 없음)

- **먼저 시험으로 확인**(`CmsWave3FixTest.F2_*`, 고치기 전 실패 확인): WO 방식 계획의 PM 작업지시를 과거 시각(3일 전)으로 완료하면 점검 실행 `performed_at` = **지금**, 유동(FLOAT) 계획 `last_done_date` = 오늘, 다음 예정 = 오늘 + 간격 — 작업지시 `finished_at`(3일 전)과 달랐다. KPI PM 준수(작업지시 `finished_at`)와 점검 실행 기한 준수(`performed_at`)가 다른 날을 보는 원인.
- 고침: `MwoService.finish` → `ChkRunService.completeForMwo(mwoId, me, finishedAt)` → `finish(…, performedAt = finishedAt)`. 다음 예정·마지막 완료일은 그 공장 현지 날짜.
- 남김(범위 밖·질의): 사용량 주기의 "완료 사용량"은 여전히 **지금 누적**(그 시각의 누적을 원장에서 되짚지 않음) — 사후 입력이 길면 다음 예정 사용량이 늦게 잡힌다. 카운터 읽기 원장에 "시각 기준 누적" 이 생기면 다시 본다.

### 16.3 F3 — 수집 없는 설비의 작업지시 기준 MTBF·MTTR (마이그레이션 없음)

- KPI(상태 원장) 의 MTBF·MTTR 은 그대로(D10·D19 — 작업지시 수기 정지로 채우지 않는다). **수집 없는 설비**(구간 0)에만 **작업지시 기준** 값을 다른 이름으로 따로 낸다: 화면·엑셀 열 "MTBF(작업지시)"·"MTTR(작업지시)"·"고장(작업지시)". 수집 있는 설비 줄은 빈칸(두 숫자가 다투지 않게).
- **작업지시 기준 고장**(G1·G2 와 같은 한 정의, `CmsFailMapper.failWhere`): 고장 수리(BM) · 완료(DONE·CLOSED) · 설비 대상 · 고장 시각 = `coalesce(down_start_at, reported_at)` 가 기간 [from, until) 안 · 공장·설비 글자 조건. 취소·진행 중은 빼다(수리 시간을 모름).
- 정지 시간 = `down_end_at − down_start_at`(정지 기록 `down_yn = Y` 만, 기간으로 자르지 않음 — 그 고장 한 건의 길이).
- **MTTR(작업지시)** = Σ정지 시간 ÷ 정지 기록이 있는 고장 수. **MTBF(작업지시·달력)** = (기간 달력 시간 − Σ정지 시간) ÷ 고장 수 — 가동 시간을 모르므로 달력 시간 기준(그래서 상태 원장 MTBF 와 섞지 않는다). 고장 0 이면 빈칸.
- 라인·공장 합계: 수집 없는 설비만 더한다(분자·분모를 합한 뒤 나눔, EquipKpi 규칙). 수집 없는 설비가 없으면 빈칸.
- 응답: `KpiLine.wo`(`WoKpi`: failures·downSec·downCount·calSec·mtbfMin·mttrMin + G2 의 respSec·respCount·mttaMin). 용어 사전 `MTBF_WO`·`MTTR_WO`·`MTTA` 를 더한다(정의 카드 C4).
- 남김: "UDT 가 사유만 바뀌어 연속 두 구간이면 고장 2건"(docs/64 F3 뒷부분, 추정)은 이번 결정 밖 — 시험으로 확인만 하고 정책 질의로 남긴다(16.8).

### 16.4 G1 — 고장 분석 (대시보드 탭, 마이그레이션 = 화면 등록)

- **대시보드 화면 `cms-dashboard`** 를 처음 등록한다(V231 이 API 만 만들고 화면은 차트 도구 결정 뒤로 미룸 → docs/61 Highcharts·`hb.chart` 병합 뒤). 탭 2개: **개요**(docs/51 C2 — KPI 타일·OEE 추이·비가동 Pareto·계획 대비) / **고장 분석**(G1·G2). 조건(공장·기간·설비 글자·설비 클래스)은 두 탭 공용, 기간 상한은 탭마다(개요 31일 = KPI 추이, 고장 92일 = KPI).
- **권한**: 대시보드 API 를 `cms-kpi` SEARCH → **`cms-dashboard` SEARCH** 로 옮긴다(화면 권한과 API 권한이 같아야 화면만 받은 사람이 빈 화면을 보지 않는다). V231 API 를 쓰던 화면은 없었다. 데모·개발 메뉴는 CMS 배열에 추가.
- API `GET /api/cms/dashboard/failures`(plantCd·from·to·keyword·classCd·axis) — 응답: 합계(고장 수·정지 분), **파레토**(축 = CAUSE 원인(기본)·SYMPTOM 현상·CAT 원인 상위 분류 `CMS_FAIL_CAT`·EQUIP 설비, G3 뒤 MECHANISM·DETECT·IMPACT — 큰 순, 정지 시간 같이), **설비별**(고장 수·정지 분·MTBF/MTTR(작업지시) + 같은 기간 KPI 상태 원장 MTBF/MTTR 를 옆에 — 이름을 나눠), **반복 고장**(같은 설비 × 같은 원인이 N일 안에 2번 이상 — 기간 안 고장 중 바로 앞 같은 설비·원인 고장(기간 앞 N일까지 봄)과의 간격 ≤ N일인 것. N = 공장 정책 `CMS_REPEAT_FAIL_DAYS`, 기본 30).
- **드릴다운** `GET /api/cms/dashboard/failures/mwos`(같은 조건 + axis·key, key `-` = 값 없음) — 막대 하나의 작업지시 목록(최대 500, 넘으면 `truncated`). **같은 `<sql>` 조각(`failWhere`)** 을 써서 막대 값 = 목록 건수(시험). 줄의 작업지시 번호는 작업지시 화면(`cms-mwo`, 자기 권한)으로 연다.
- 차트: 파레토는 `hb.chart.pareto`(접는 "표로 보기" 포함). 차트 라이브러리가 없으면(`vendor-local` 없음) 표만 — 표가 원본.
- 시험: 파레토 합 = 기간 BM 완료 수, 취소·진행 중·CM·PM 제외, 공장 시간대 자정 경계, 드릴다운 건수 = 막대 값, 반복 N 경계(N일 = 반복, N+1일 = 아님), A사로 B사 0건, 권한(화면 권한 없으면 403), 입력 검사(축·기간·클래스).

### 16.5 G2 — 수리 시간 분해 (마이그레이션 없음)

- 고장 한 건(16.3 같은 정의)마다: **대기**(신고 → 착수 `started_at − reported_at`), **수리**(착수 → 완료 `finished_at − started_at`), **정지**(정지 기록만), **공수**(`cms_mwo_labor.minutes` 합). 착수 칸이 빈 건은 대기·수리에서 빼고 건수를 따로.
- 고장 분석 탭 "수리 시간 분해" 표: 설비별 / 담당별(작업지시 담당 `assignee_id`, 없으면 "담당 없음") 전환 — 평균 대기·수리·정지·공수와 건수. 평균 = 합 ÷ 그 값이 있는 건수.
- KPI 화면: MTTR 옆에 **평균 반응(MTTA, 작업지시)** = Σ대기 ÷ 착수 칸이 있는 고장 수 — 수집 유무와 상관없이 모든 설비(원천이 작업지시뿐이라 다툴 숫자가 없다). 공장·라인 합계도 모든 설비.
- 반응 시간 목표는 보류(docs/64 G2 권고 — 값만).

### 16.6 G3 — ISO 14224 고장 기록 칸 (V249·임시 V9602)

- 작업지시에 선택 칸 3개: **메커니즘** `fail_mech_cd`(공통코드 `CMS_FAIL_MECH` — 시스템 = ISO 14224 B.2 고장 메커니즘 상위 6 + 자주 쓰는 하위, **회사 추가 허용** `comp_ext_yn = Y`), **탐지 방법** `fail_detect_cd`(공통코드 `CMS_FAIL_DETECT` — ISO 14224 탐지 방법 목록, 회사 추가 허용), **영향** `fail_impact_cd`(공통코드 `CMS_FAIL_IMPACT` — `STOP` 정지(치명)·`DEGRADED` 성능 저하·`INCIPIENT` 잠재, 회사 추가 불가 — 회사 간 비교 축).
  - 회사별 표(`cms_fail_code`)가 아니라 **공통코드**로 둔 이유: 표준 목록이 있고(회사마다 처음부터 만들 필요 없음), 공통코드 전환 세션의 방향(하드코딩·회사 표 분류 → `sys_code`·`sys_comp_code`)과 맞춘다. 클래스 한정이 필요 없는 축이다.
  - 현상 = ISO 14224 "고장 모드"로 문서상 매핑(화면 라벨 "현상(고장 모드)"), 원인 = 고장 원인 — 칸은 그대로.
- 검사: 서비스가 칸 오류(`CODE`) 먼저(`hb_code_usable` 과 같은 함수), 트리거 `hb_guard_cms_mwo_iso`(새로 넣거나 바꿀 때만 사용 중 코드, 회사 코드는 그 회사 것만 — `fk_cms_mwo_fail_*_code`). 완료 뒤 불변은 기존 `ck_cms_mwo_done_fixed`(행 전체 비교)가 그대로 막는다. 완료 필수 아님(선택 입력, Q2).
- 자동 값: 알람 자동 신고 → 탐지 `CBM`(상태 감시), 점검 NG 후속 → 탐지 `INSPECTION`. 사람이 바꿀 수 있다.
- G1 축에 MECHANISM·DETECT·IMPACT 를 더하고, "점검으로 찾은 고장 비율"(탐지 = INSPECTION·PERIODIC_PM) 을 고장 분석 합계에 보인다.
- 엑셀(C14 작업지시 엑셀)·grid 열에 세 칸 추가(우선순위 3).

### 16.7 공통·남의 것

- 남의 표 변경 없음. 공유 파일: `messages*.properties` CMS 3차 블록, `DevCompanySeed.CMS_MENUS`(CMS 배열), `HbErrorCode` 추가 없음.
- `cms/edge`(장비 연동·보안 세션이 고치는 중)는 건드리지 않는다.

### 16.8 정책 질의로 남긴 것

| # | 무엇 | 지금 |
|---|---|---|
| W3-1 | 사용량 주기 PM 의 완료 사용량 = 지금 누적(16.2) | 그대로 |
| W3-2 | 사유만 바뀐 연속 UDT 두 구간 = 상태 원장 고장 2건(16.3) | 시험으로 확인, 그대로 |
| W3-3 | 반복 고장 축 = 설비 × 원인(현상·메커니즘 축은 아님) | 원인만 |
