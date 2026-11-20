# 25. P2 MES 코어 설계

작성 2026-10-20. 8장 결정 10건은 **추천안 그대로 채택**(2026-10-20 사용자, 10장).
근거: docs/00 5장 테이블 표(02·03 검토 추가분)·6장 로드맵 P2 행, 01 3.1(원장 공통 규약)·5.7(`client_req_id`)·6장(POP 인증 D4)·7.4(Collector 보안), 02 2.1(디스패치)·2.2(Collector 표준 이벤트)·2.4(SEMI E10 상태 원장)·2.5(실적 수량 분리)·6.2 결정표, 03 1.3·6.1(파라미터 실측 원장, 내시 버전, 알람 이벤트), 04 2.4·2.7·5번 결정(멱등·POP 클라이언트·`WorkDayResolver`), 06 M2·M3(감사 대응 보고서, 작업자 계정 무료), 07 D4·D7(ErpPort·엑셀 내보내기 어댑터), 08 D2·D4(에너지 원장, 상태 구간 `wo_id`), 09(POP 그리드 변형·keyset 페이징), 11 D1·D4~D8·D12·D13(entry_method·단말·`hb.pop`·오프라인·"입력하면 돌려준다"·대리 입력·닫을 때 필수), 16 9장 7단계(POP 입력 변형), **24 7장(POP 착수 체크리스트 K·E)**.
P0 플랫폼 규약(01)과 P1 공통 규칙(17 2장)은 그대로 따른다 — 이 문서는 **P2 에서 새로 정하는 것**만 적는다.

---

## 1. 결론 먼저

- P2 는 **"지시를 내리고, 현장이 실적을 남기고, LOT 이 이어진다"** 까지다. 데모 흐름: 수주 → 작업지시(라우팅·BOM·파라미터 스냅샷) → POP 착수(자격 게이트·자재 LOT 스캔 투입) → 실적(양품/폐기/재작업/수리 + 불량 유형) → 완료판정 → 생산 입고 → LOT 정·역추적 보고서.
- **원장 6종 중 P2 가 4종을 만든다**: 생산 실적 `prd_result`, LOT 계보 `lot_genealogy`, 설비 상태 구간 `cms_equip_state_log`, 공정 파라미터 실측 `prd_param_actual` (+ 08 D2 에너지 계측 `cms_energy_reading`). 모두 01 3.1 규약(두 시각·`entry_method`·`recorded_by`/`performed_by`·역분개·`hb_forbid_update_delete()`).
- 설비 연동은 **표준 이벤트 스키마 확정 + 수신 API + 가짜 수집기**까지(02 6.2 결정). 실제 프로토콜(Modbus·OPC UA·MQTT)은 P6.
- **POP 은 별도 화면 계열**(`/pop/**`, 단말 세션, 큰 터치 UI `hb.pop`). 사무 화면 셸·메뉴를 쓰지 않는다. 24 7장 K 체크리스트를 POP 각 단계의 완료 기준에 넣는다.
- 재고 수량(현재고·차감 전략)은 **P4 WMS** 그대로 — P2 는 재고를 세지 않는다(8장 D2 추천안).

## 2. 범위와 단계

순서 원칙(17 1장과 같음): **참조되는 것 먼저**, 매 단계가 화면·API·테스트(정상 + 이상 사용 A~K + A사/B사)까지 끝난 상태로 커밋, 끝나면 7장 표에 ✅·날짜·테스트 수.

| 단계 | 범위 | 첫 화면 | 완료 기준 |
|---|---|---|---|
| **1** | **작업일·근무조·작업자·자격**: 공장 시간대(`sys_plant.time_zone`) + 근무조(`mst_shift`, 자정 넘는 조) + `WorkDayResolver`(발생 시각 → 작업일·근무조), 작업자 `mst_worker`(로그인 계정 아님, 배지), 자격 `mst_qual`·`mst_worker_qual`(유효기간)·`mst_proc_qual_req`(게이트 WARN/BLOCK/OFF) | 근무조·작업자·자격 관리 | 자정 넘는 조·DST 시간대의 없는·겹치는 시각·공장마다 다른 시간대 시험, 만료 자격 판정 |
| **2** | **수주·내시**: `sal_order`·`sal_order_line`(거래처·품목·납기·수량, 상태), 내시 `sal_forecast_rev`(수신 버전마다 보관, 덮어쓰기 금지)·`sal_forecast_line`, 엑셀 올리기 템플릿 2종 | 수주 관리 · 내시 수신 | 내시 같은 기간 재수신 = 새 버전(이전 그대로), 버전 비교 조회 |
| **3** | **작업지시**: `prd_wo`(품목·수량·계획일·라우팅/BOM 리비전·수주 행 연결 선택) + **스냅샷** `prd_wo_proc`(공정 단계)·`prd_wo_param`(파라미터)·`prd_wo_bom`(자재 소요) + 상태 `PLANNED → RELEASED → IN_PROGRESS → DONE → CLOSED`(+`CANCELLED`) | 작업지시 관리 | 발행 뒤 라우팅·BOM 이 바뀌어도 지시는 스냅샷대로, 확정 리비전만, 채번 결번 없음 |
| **4** | **LOT·계보 원장**: `lot_master`(LOT 번호 규칙 = 채번 패턴 + 정책), `lot_genealogy`(원장: `rel_type` CONSUME/SPLIT/MERGE/REWORK/REPAIR/SORT/RELABEL, 수량·단위, `wo_id`·공정·설비·`change_id`), 자재 LOT 간이 등록(8장 D2), 정·역추적(재귀 CTE, 깊이 상한·순환 방지) | LOT 추적 조회 | 분할·병합·재작업 계보 왕복 일치, 10단 × 1,000 LOT 추적 실측 |
| **5a** | **POP 기반**: 단말 등록 `sys_terminal`(키 해시·공장·작업장 바인딩·폐기) + 단말 세션 + 배지 전환(2초, 정책 PIN), `hb.pop` 컴포넌트(스캔 입력·숫자 키패드·큰 확정·피드백·언어 1탭·오프라인 표시), 작업장 홈("내 할 일") | POP 홈 | 24 7장 K 항목(단말 크기·누르는 크기·키보드 없이·연타·두 태블릿) |
| **5b** | **착수·실적·투입**: 착수/종료(`prd_wo_proc` 진행), **자격 게이트**, 자재 LOT 스캔 투입(→ 계보 CONSUME), 실적 원장 `prd_result`(`good_qty`·`scrap_qty`·`rework_qty`·`repair_qty` + 불량 유형 행 `prd_result_defect`), `client_req_id` 멱등, 정정 = 역분개, 파라미터 수동 입력(→ `prd_param_actual`), 비가동 사유 입력, "입력하면 돌려준다"(오늘 실적/목표·직전 불량) | POP 실적 | 같은 `client_req_id` 재전송 → 처음 응답 그대로, 오프라인 대기열 재전송, 공용 단말 대리 입력이 `recorded_by`/`performed_by` 로 드러남 |
| **6** | **설비 상태·연결·Collector**: `cms_state_cat`(전역 SEMI E10 6 + UNKNOWN)·`cms_state_reason`(회사)·`cms_equip_state_log`(구간 원장, 겹침 금지 배제 제약, `wo_id`)·`cms_equip_conn_log`, `cms_energy_reading`(구간 사용량 원장), 수신 `POST /ext/v1/equip-events`(기기 API 키, 202 + `sys_inbox`, `seq` 누락 감지) + **가짜 수집기**(테스트·데모용) | 설비 상태 타임라인(최소형) | 통신 끊김이 비가동이 아니라 UNKNOWN, 늦게 온 이벤트·순서 뒤바뀜·중복, 구간 겹침 거부 |
| **7** | **완료판정·입고·재작업·연계**: 공정 완료판정(보존식: 투입 = 양품 + 폐기 + 재작업 대기 + 수리 대기, 정책 허용 오차), 지시 완료·마감, 생산 입고 기록(8장 D2), 재작업·수리 흐름(계보 REWORK/REPAIR), `ErpPort` + "ERP 업로드용 엑셀 내보내기" 어댑터 + 가짜 ERP(07 D4), **감사 대응 보고서**(LOT 역추적 한 화면 + PDF, 06 M2), 데모 시나리오 관통 시드 | LOT 역추적 보고서 | 데모 시나리오 1개가 수주부터 보고서까지 끊김 없이, 보존식 위반 지시는 완료 불가 |

## 3. P2 공통 규칙 (원장·현장 입력)

| 규칙 | 내용 | 근거 |
|---|---|---|
| 원장 테이블 | `occurred_at`(현장 발생) + `recorded_at`(default now()) + `entry_method`(AUTO/SCAN/KEY/IMPORT/CORRECT) + `recorded_by`(입력 사람·단말) + `performed_by`(실제 작업자) + `reversal_of_id` + `hb_forbid_update_delete()` + 앱 계정 UPDATE·DELETE REVOKE. 카탈로그 원장 목록(`TenantCatalogInspector`)에 등록 | 01 3.1, 08 D2 |
| 정정 | 원래 행 그대로 + 역분개 행(수량 부호 반대, `entry_method = CORRECT`, 사유 필수). 화면의 "수정" 버튼은 내부적으로 역분개 + 새 행 | 01 3.1 |
| 멱등 | POP·수집기 저장은 업무 행 `client_req_id` + `unique(comp_cd, client_req_id)` — 중복 도착은 **처음 응답 그대로**(409 아님), 동시 도착은 unique 위반을 잡아 먼저 저장된 행 반환. 수집기 이벤트는 `sys_inbox`(`eventId`) | 01 5.7, 04 2.4 |
| 시각 | 발생 시각은 클라이언트가 보낸 값(오프라인 대기 뒤 재전송 포함), 기록 시각은 서버. 작업일·근무조는 저장 때 `WorkDayResolver` 로 계산해 행에 남긴다(나중에 근무조 정의가 바뀌어도 과거 행은 그대로). 미래 발생 시각은 정책 허용 오차(예 5분) 밖이면 거부 | 04 5번, 01 BizClock |
| 조회 | 원장 목록은 **기간 조건 필수**(기본 오늘·이번 근무조) + keyset 페이징(`(occurred_at, id) <`) | 09, 24 4장 |
| 수량 | `numeric`, 단위는 품목 기본 단위(실적) / 투입은 행 단위 + 환산(BOM 단위 환산 재사용, 질량 환산 가능 보장) | 17 2장, 08 D4 |
| 상태 전이 | 지시·공정 진행 상태는 전이 표 하나(서비스 + 트리거 55000), 화면은 허용된 다음 동작만 버튼으로 | 00 결함 5 |
| 닫을 때 필수 | 입력 도중엔 강제하지 않고 **종료·확정 때만** 필수(비가동 사유, 폐기 사유 코드, 완료판정) | 11 D13 |
| 외부 송신 | ERP·라벨·알림은 아웃박스(3단 릴레이) — 트랜잭션 안에서 외부 호출 금지 | 01, 07 하지 말 것 7 |
| 테스트 | 정상 + 이상 사용 체크리스트 A~K(특히 C 동시성·F 흐름 우회·K 현장 단말) + **A사로 B사** + 직접 SQL(원장 UPDATE/DELETE 42501·트리거) | CLAUDE.md, 24 |

## 4. 단계별 데이터 모델 (요약 — 단계 착수 때 상세 설계를 이 문서 해당 절에 추가)

```text
── 1단계 ─────────────────────────────────────────────────
sys_plant.time_zone  이미 있음(V001, null = 회사 시간대) — 지금은 저장만 되고 어디에서도 안 씀(4단계 QA 관찰) → P2 부터 사용(8장 D5)
                     DST 시간대의 없는 시각(봄)·겹치는 시각(가을)은 WorkDayResolver 가 거부/앞쪽 선택을 명시적으로 — 조용히 옮기지 않는다
mst_shift            (comp, plant, shift_cd, shift_nm, start_time, end_time, crosses_midnight, break_min, valid_from/to, use_yn)
                     작업일 기준: 근무조 시작 시각이 속한 날(정책 WORK_DAY_ANCHOR = SHIFT_START)
mst_worker           (comp, worker_cd 사번, worker_nm, plant, badge_hash, pin_hash nullable, user_id nullable, use_yn)
                     로그인 계정이 아니다 — POP 은 단말 세션 + 배지 전환. 사무 계정과 연결은 선택
mst_qual             (comp, qual_cd, qual_nm, valid_months nullable)
mst_worker_qual      (comp, worker_id, qual_id, acquired_on, expires_on, cert_file_id) — 갱신은 새 행
mst_proc_qual_req    (comp, proc_id, qual_id, gate_mode WARN/BLOCK) + 정책 QUAL_GATE_DEFAULT(OFF/WARN/BLOCK)

── 2단계 ─────────────────────────────────────────────────
sal_order / sal_order_line   (cust, order_no 채번, cust_po_no, line: item, qty, due_date, status OPEN/PARTIAL/DONE/CANCELLED)
sal_forecast_rev     (comp, cust, rev_no, received_at, source FILE/EDI/MANUAL, file_id)   ← 덮어쓰기 금지, 원장 성격(UPDATE 차단)
sal_forecast_line    (rev_id, item, bucket_start, bucket_type DAY/WEEK/MONTH, qty, firm_yn)

── 3단계 ─────────────────────────────────────────────────
prd_wo               (comp, plant, wo_no 채번, item, plan_qty, unit, plan_start/end, routing_id, bom_id, order_line_id nullable,
                      status, priority, row_ver)
prd_wo_proc          (wo_id, op_seq, proc, node(작업장), std_ct_sec, setup_min, status WAIT/READY/RUN/PAUSE/DONE)  ← 라우팅 스냅샷
prd_wo_param         (wo_proc_id, param_cd, target, lsl, usl, unit_nm)                                            ← 파라미터 스냅샷
prd_wo_bom           (wo_id, op_seq nullable, item, req_qty, unit, issue_method, alt_grp)                         ← BOM 전개 스냅샷
                     발행(RELEASED) 때 스냅샷 생성, 그 뒤 원본 변경과 무관. 확정(RELEASED) 리비전·적용 기간 안만

── 4단계 ─────────────────────────────────────────────────
lot_master           (comp, lot_no, item, origin PRODUCED/RECEIVED/SPLIT/MERGE, qty_created, unit, wo_id nullable,
                      cust_lot_no / supplier_lot_no nullable, status ACTIVE/HOLD/CONSUMED/SCRAPPED)   ← 상태는 원장에서 파생 가능해야 함
lot_genealogy        원장 (parent_lot, child_lot, rel_type, qty, unit, wo_id, wo_proc_id, equip_id, change_id nullable, + 원장 공통)
                     역추적: 재귀 CTE(깊이 상한 정책, 경로 배열로 순환 방지), 결과 캐시 테이블 없음

── 5단계 ─────────────────────────────────────────────────
sys_terminal         (comp, terminal_cd, key_hash, plant, node, terminal_type POP/PDA, revoked_at)
prd_result           원장 (wo_proc_id, lot_id(산출 LOT), worker_id(performed_by), terminal_id,
                      good_qty, scrap_qty, rework_qty, repair_qty, started_at, ended_at, work_day, shift_cd,
                      client_req_id, + 원장 공통)
prd_result_defect    (result_id, defect_id(qms_defect), qty)  ← 폐기·재작업 수량의 유형별 내역, 합계 = 해당 수량
prd_param_actual     원장 (wo_proc_id, equip_id, lot_id nullable, param_cd, value, source MANUAL/COLLECTOR, + 원장 공통)
prd_wo_proc_event    원장 (wo_proc_id, event START/PAUSE/RESUME/END, worker_id, equip_id, + 원장 공통)  ← 착수·종료 이력

── 6단계 ─────────────────────────────────────────────────
cms_state_cat        전역 시드 PRD/SBY/ENG/SDT/UDT/NST + UNKNOWN
cms_state_reason     (comp, reason_cd, reason_nm, state_cat, planned_yn)
cms_equip_state_log  원장 (equip_id, state_cat, reason_id nullable, start_at, end_at nullable, wo_id nullable, source AUTO/MANUAL)
                     EXCLUDE USING gist (comp_cd WITH =, equip_id WITH =, tstzrange(start_at, end_at) WITH &&)
                     ※ 열린 구간(end_at null)을 닫는 것은 "구간 닫기" 한 번만 허용하는 예외 — 4단계 설치 이력과 같은 방식(8장 D8)
cms_equip_conn_log   원장 (equip_id, conn ONLINE/OFFLINE, occurred_at, source)
cms_energy_reading   원장 (meter_cd, period_start, period_end, qty, reading_src COLLECTOR/MANUAL/BILL)
sys_device_key       (comp, device_cd, key_hash, bound_equip_ids, revoked_at)   ← /ext/** 기기 API 키

── 7단계 ─────────────────────────────────────────────────
prd_receipt          원장 (wo_id, lot_id, qty, wh/loc, + 원장 공통)   ← P4 재고 원장의 첫 소스(8장 D2)
erp_export_job       (comp, system_id(mst_ext_system), kind RESULT/RECEIPT, period, file_id, status)  ← 아웃박스 소비자가 만든다
```

### 4.1 Collector 표준 이벤트 (6단계에서 확정 — 02 2.2(a) 를 그대로 출발점으로)

`type` = `COUNT | STATE | ALARM | PARAM | CONN | METER_READING`(03 6.1·08 D2 로 ALARM·METER_READING 추가). `metrics[]` 는 Sparkplug 메트릭(이름·타입·시각·품질) 1:1. `eventId` 멱등(`sys_inbox`), `seq` 는 수집기 단위 순번 → 누락은 `cms_equip_conn_log` 에 "누락 구간" 으로. COUNT → 실적 자동 제안(사람이 확정, 11 D1 `entry_method = AUTO`), STATE → 상태 구간, PARAM → `prd_param_actual`, CONN → 연결 로그(상태 구간 UNKNOWN), ALARM → P5 CMS 용 원장(스키마만 P2).

### 4.2 1단계 상세 — 작업일·근무조·작업자·자격 (V030)

**공장 시간대**: `BizClock.zone(plantCd)` = `sys_plant.time_zone`, 없거나 잘못된 값이면 회사 시간대. 화면이 보내는 "현지 시각"(`yyyy-MM-ddTHH:mm`)은 `BizClock.toInstant(local, zone)` 로만 바꾼다 —
DST **없는 시각(봄)은 `DST_GAP` 칸 오류**, **겹치는 시각(가을)은 앞쪽(서머타임) 시각**(ISO·java.time 기본과 같음, 이 문서에 적어 둔다). P1 설치 이력(`AssetService`)도 설비 공장 시간대로 바꾼다(D5).

**근무조 `mst_shift`** (공장별)

| 칸 | 규칙 |
|---|---|
| `shift_cd`·`shift_nm` | 공장 안 유일, 코드 `^[A-Z0-9][A-Z0-9_-]{0,9}$` |
| `start_time`·`end_time` | 분 단위 현지 시각. `end <= start` 이면 자정을 넘는 조(22:00~06:00). 같으면 거부(24시간 조는 만들지 않는다) |
| `day_offset` | 작업일 = **근무조 시작 날짜 + day_offset**(0 또는 -1). 예: 주간 08~20(0), 야간 20~08(0 → 시작한 날), 3교대의 3조 00~08(-1 → 전날 작업일) |
| `break_min` | 0~600, 근무 시간보다 작게 — OEE 계획 가동시간(P5)의 재료 |
| 겹침 | 같은 공장의 **사용 중** 근무조끼리 시간이 겹치면 거부(자정 넘김 포함, 서비스 + 트리거 `ck_mst_shift_overlap`, 공장 advisory 잠금) |
| 변경 | 시간·오프셋을 바꾸면 **앞으로 기록할 행부터** 적용 — 원장은 저장 때 계산한 작업일·근무조를 들고 있다(3장) |

**`WorkDayResolver.resolve(plantCd, Instant)`** → `(workDay, shiftCd | null, zone)`. 공장 현지 시각의 분 m 이 어느 근무조 [start, end) 에 드는지(자정 넘는 조는 다음 날 앞부분 포함) → 시작 날짜 + offset. **어느 조에도 안 들면 근무조 null, 작업일 = 현지 날짜**(근무 외 시간 실적도 잃지 않는다).

**작업자 `mst_worker`** — 로그인 계정이 아니다(D3)

| 칸 | 규칙 |
|---|---|
| `worker_cd` 사번 | 회사 안 유일, 대문자 |
| `plant_cd` | 소속 공장(선택) |
| `badge_hash` | 배지 값은 저장하지 않고 `sha256(회사코드 + ':' + 배지)` hex. 회사 안 유일(같은 배지 두 사람 금지), **응답에 내보내지 않는다**(설정 여부만). 지정·지우기는 별도 API(SAVE) |
| `pin_hash` | 칸만 둔다 — 지정·확인은 5a(POP) |
| `user_id` | 사무 계정 연결(선택, 회사 안 1:1) |

**자격** — `mst_qual`(코드·이름·유효 개월 선택), `mst_worker_qual`(작업자 × 자격 × 취득일·만료일·취소일, **갱신은 새 행** — 이력이 남는다, 삭제 없음), `mst_proc_qual_req`(공정 × 자격 × `gate_mode` WARN/BLOCK, 사용 여부).
만료일을 비우면 `취득일 + 유효 개월`(유효 개월도 없으면 만료 없음). 어떤 날의 유효 = 그 날 기준 취득 ≤ 날 ≤ 만료, 취소 안 됨인 행이 하나라도 있으면.

**자격 게이트 `QualGate.check(plantCd, procId, workerId, workDay)`** → `(mode OK/WARN/BLOCK, 빠진 자격[코드·이유 NONE/EXPIRED/REVOKED/NOT_YET])`.
공장 정책 `QUAL_GATE_MODE`(OFF/WARN/BLOCK, 기본 WARN)가 **상한**: 실제 모드 = min(정책, 요구 행의 `gate_mode`). 1단계는 판정 API·화면 미리보기까지, 착수 차단은 5b.

화면: 근무조 `mst-shift`(공장 선택 → 조 표 + 폼 + "시각 → 작업일·근무조" 확인 칸), 자격 `mst-qual`(자격 표 + 공정별 필요 자격), 작업자 `mst-worker`(목록 + 상세 + 배지 지정 + 보유 자격 표 + 공정 선택 → 게이트 미리보기).

### 4.3 2단계 상세 — 수주·내시 (V032)

**한 줄**: 수주는 "고객이 확정한 주문"(바뀌면 고친다, 행은 지우지 않고 끝낸다), 내시는 "고객이 보낸 예상"(받을 때마다 **새 버전**, 이전 버전은 그대로 — 비교가 목적).

**수주 `sal_order`(머리) · `sal_order_line`(행)**

| 칸 | 규칙 |
|---|---|
| `order_no` | 채번 `SAL_ORDER` — 회사에 규칙이 없으면 기본 패턴 `SO{yyMM}-{seq:4}`(매월)으로 만든 뒤 번호(`NumberingService.nextOrDefault`, 동시 첫 호출은 `on conflict do nothing`). 업무 트랜잭션 안에서만(결번 없음) |
| `cust_id` | 사용 중 **판매** 거래처(SALES·BOTH — 구매 거래처는 `NOT_SALES`), 같은 회사(트리거 `fk_sal_order_cust_comp`). 만든 뒤 바꾸지 않는다 |
| `cust_po_no` | 고객 발주 번호(선택, 출력 가능한 ASCII·앞뒤 공백 없음). 입력하면 **거래처 안 유일**(부분 유일 인덱스) — 엑셀 올리기의 업무 키. 다른 거래처는 같은 번호 가능 |
| `order_date` | 비우면 회사 오늘(`BizClock`) |
| 머리 `status` | `OPEN` / `CLOSED` / `CANCELLED` — **행에서 계산**: 열린 행이 있으면 OPEN, 모두 취소면 CANCELLED, 그 밖 CLOSED. 끝난 머리는 바꾸지 않는다(트리거 `ck_sal_order_closed`). 사람이 하는 동작은 "수주 취소"(열린 행 모두 취소, 사유 필수)뿐 |
| 행 `line_no` | 수주 안 유일, 비우면 다음 10 단위. 바꾸지 않는다 |
| 행 `item_id` | 사용 중 품목(같은 회사 트리거), 만든 뒤 바꾸지 않는다(다른 품목이면 행 취소 + 새 행 — `ck_sal_order_line_fixed`) |
| 행 `qty` | `> 0`, 품목 기본 단위의 **단위 자릿수**(공통코드 UNIT `attrs.scale`, EA 0·KG 3)를 넘으면 반올림하지 않고 `SCALE`, 정수 12자리. 요청·응답 모두 **글자**(응답은 `trim_scale(qty)::text`) — Double 을 거치지 않는다(7단계 QA #2) |
| 행 `due_date` | 필수. 과거 납기도 받는다(이관) |
| 행 `status` | `OPEN → DONE`(마감) / `OPEN → CANCELLED`(사유 필수, CHECK 로 사유와 짝). 되돌리지 않는다. 끝난 행은 수량·납기 변경 불가(트리거 `ck_sal_order_line_closed`). `PARTIAL` 은 만들지 않는다 — 3단계 이후 지시·입고 연결 때 정한다 |
| 삭제 | 머리·행 모두 DELETE 금지(`hb_forbid_delete_master` + REVOKE) — 취소로만 |
| 동시성 | 행을 바꾸는 모든 동작은 **머리 행을 먼저 잠근다**(`FOR UPDATE`) — 마지막 두 행을 동시에 마감해도 머리가 CLOSED 가 되고, 행 번호 자동 부여가 겹치지 않는다. 같은 발주 동시 생성은 부분 유일 인덱스가 한쪽을 `DUPLICATE` 로 |

수주 행 수량·납기 변경은 감사 트리거(`hb_audit`)로 전후가 남는다. 3단계 작업지시는 `order_line_id`(선택)로 행을 가리킨다.

**내시 `sal_forecast_rev`(버전) · `sal_forecast_line`(행)** — 덮어쓰기 금지

| 칸 | 규칙 |
|---|---|
| `rev_no` | 거래처별 1, 2, 3 … — 같은 거래처 수신은 advisory 잠금으로 줄 세운 뒤 max + 1, 유일 제약이 마지막 방어 |
| `received_at`·`source` | 서버 시각. `FILE`(엑셀 — `import_job_id` 로 원본 파일 추적, 같은 회사 작업만)·`MANUAL`(API)·`EDI`(P6 자리) |
| 행 | (품목, `bucket_type` DAY/WEEK/MONTH, `bucket_start`, `qty ≥ 0`, `firm_yn`) — 버전 안 (품목·유형·시작) 유일. **주는 월요일, 월은 1일 시작**(CHECK + 서비스 `WEEK_START`·`MONTH_START`) — 같은 주를 두 날짜로 적어 비교가 어긋나지 않게 |
| 0 수량 | 0 은 "그 기간 수요 없음"(이전 버전 대비 사라진 수요를 명시). 빈 칸 = 행 없음 |
| 불변 | 두 테이블 모두 `hb_forbid_update_delete` + 앱 계정 UPDATE/DELETE REVOKE. 정정 = 새 버전이라 `reversal_of_id` 는 두지 않는다(원장 6종 아님 — 카탈로그 원장 검사 대상 밖). 감사 트리거 대신 행 자체가 이력(받은 사람·시각·원본 작업) — `RetentionTest.AUDIT_EXEMPT` 에 근거 |

**비교 조회** `GET /api/sales/forecasts/compare?toRevId=&fromRevId=` — `fromRevId` 를 비우면 바로 앞 버전(첫 버전이면 빈 쪽과). (품목, 유형, 시작) 전체 외부 결합 → 이전·이번 수량(글자)·확정. 다른 거래처 버전끼리는 `OTHER_CUST`. 차이 계산은 화면이 10진 글자로(BigInt) — Number 로 바꾸지 않는다.

**엑셀 올리기 2종** (P1 2단계 엔진 재사용)

| 템플릿 | 업무 키 | 실행 |
|---|---|---|
| `SAL_ORDER` | 거래처 \| 고객 발주 번호 \| 행 번호 (발주 번호 필수) | 새 (거래처, 발주) → 수주 머리 채번 + 행, 있는 발주의 새 행 → 행 추가(끝난 수주면 `CLOSED`), 있는 행 → 수량·납기·비고 변경(끝난 행은 값이 다를 때만 `CLOSED` — 같으면 SAME). 품목이 다르면 `FIXED`, 수주일은 새 수주에만(있는 수주와 다르면 `FIXED`, 파일 안 같은 발주끼리 다르면 `SAME_ORDER`) |
| `SAL_FORECAST` | 거래처 \| 품목 \| 유형 \| 시작 | 기존 행과 비교하지 않고 언제나 새 행 — **파일 하나 = 거래처별 새 버전 하나**(파일에 거래처가 둘이면 버전 둘). 버전이 원본 작업을 가리키도록 엔진에 `ImportTemplate.openForExecute(jobId)` 를 더했다(기본은 `open()`) |

**화면**: 메뉴 "영업"(로컬 시더). 수주 관리 `sal-order`(조회 조건: 수주일 기간 필수·최대 366일·거래처·상태·수주/발주 번호·품목 → 수주 표(500건 상한, 넘으면 "조건을 좁혀 달라") + 상세: 머리 폼 + 행 표(수정·마감·취소) + 행 폼(품목 찾기 보조), 새 수주는 행을 화면에 모아 [저장] 한 번, 엑셀 올리기), 내시 수신 `sal-forecast`(거래처 → 버전 목록(출처·파일·행 수·기간) → "이전 버전과 비교"(늘어남·줄어듦·새로 생김·사라짐 요약, 바뀐 것만) / "이 버전 행", 엑셀 올리기).

**하지 않은 것(2단계)**: 수주 → 출하·입고 연결과 `PARTIAL`(3·7단계·P4), 내시 → 생산 계획 반영(APS 범위 밖, 9장), 내시 직접 입력 화면(API 만 — 받기는 엑셀·EDI 가 정상 경로), 수주 엑셀 내보내기.

### 4.4 3단계 상세 — 작업지시 (V033)

**한 줄**: 작업지시는 "무엇을 얼마나 언제까지, **어떤 기준정보로** 만들라" 이다. 발행(RELEASED) 순간의 라우팅·파라미터·BOM 을 지시 안에 **복사(스냅샷)** 해 두므로, 그 뒤 기준정보가 바뀌어도 현장은 발행된 그대로 일한다(D1: 디스패치 단위 = 지시 × 공정 `prd_wo_proc`).

**지시 `prd_wo`**

| 칸 | 규칙 |
|---|---|
| `wo_no` | 채번 `PRD_WO`(기본 `WO{yyMM}-{seq:4}`, 매월, 회사에 규칙이 없으면 만든 뒤 — 수주와 같은 `nextOrDefault`). 만들 때 번호가 붙고 취소돼도 번호는 남는다(결번 없음) |
| `plant_cd`·`item_id` | 사용 중 공장·품목. 만든 뒤 바꾸지 않는다(다르면 취소 + 새 지시) |
| `plan_qty`·`unit_cd` | `> 0`, 품목 기본 단위(만들 때 복사)·단위 자릿수(반올림 금지). 발행 뒤에는 바꾸지 않는다 — 자재 소요가 수량에서 나왔다 |
| `plan_start`·`plan_end` | 계획 시작일 ≤ 계획 완료일. **라우팅·BOM 의 유효 기준일 = 계획 시작일**. 일정 조정(계획일·우선순위·비고)은 발행 뒤에도 된다 — 단 계획 시작일을 옮겨도 스냅샷은 그대로 |
| `routing_id` | 필수 — 그 품목·공장의 **확정 리비전**, 계획 시작일에 유효. 비우면 대체 번호 1 의 유효 리비전을 고른다 |
| `bom_id` | 선택(BOM 없는 품목) — 그 품목의 확정 리비전, 계획 시작일에 유효. 비우면 대체 번호 1 의 유효 리비전(있으면) |
| `order_line_id` | 선택 — 같은 품목의 **진행 중** 수주 행. 한 수주 행에 지시 여러 개 가능(분할 생산) |
| `priority` | 1(급)~9, 기본 5 |
| `status` | 전이 표 하나(서비스 + 트리거 55000 `ck_prd_wo_transition`): `PLANNED → RELEASED`(발행) · `PLANNED/RELEASED → CANCELLED`(사유 필수) · `RELEASED → IN_PROGRESS`(5b 첫 착수) · `IN_PROGRESS → DONE`(7) · `DONE → CLOSED`(7). 3단계 화면이 하는 것은 발행·취소뿐. 발행 취소(되돌리기)는 없다 — 취소하고 새 지시 |
| 삭제 | 없음(취소). 지시·스냅샷 모두 DELETE 금지 |

**발행** — 한 트랜잭션: (1) 지시를 `PLANNED → RELEASED` 조건부 UPDATE(rowVer — 두 번 눌러도 한 번), (2) 라우팅·BOM 이 **지금도** 확정·계획 시작일 유효인지 다시 확인(만든 뒤 폐기됐으면 `STALE_REV`), (3) 스냅샷 3종을 넣는다.

| 스냅샷 | 내용 | 바뀌는 칸 |
|---|---|---|
| `prd_wo_proc` | 라우팅 단계 그대로: 공정순서·공정(코드·이름 복사)·작업장·표준 CT·사이클당 수량·준비 시간 + 상태 `WAIT/READY/RUN/PAUSE/DONE`(5b) | 상태·배정 설비(`equip_id`, D1 선택 칸 — 같은 공장 사용 중 설비)만. 스냅샷 칸은 트리거가 막는다 |
| `prd_wo_param` | 단계 파라미터 그대로(코드·이름·단위·목표·하한·상한) | 없음(추가만) |
| `prd_wo_bom` | **1단 소요량** = (고정이면 행 수량, 아니면 행 수량 ÷ 기준 수량 × 지시 수량) × (1 + 스크랩%) × 단위 환산 × 대체 비율 → **자품목 기본 단위**, 소수 6자리 올림. **가상 조립(PHANTOM)** 행은 그 품목의 유효 BOM 으로 펼쳐 자재만 남긴다(10단 상한·순환 차단). 부산물(BYPRODUCT)은 유형 그대로(산출 예정). 원래 BOM 행(수량·단위·출처 BOM)도 복사 | 없음(추가만) |

환산이 없는 행(`NO_CONV`)·유효 BOM 없는 가상 조립(`PHANTOM_NO_BOM`)은 발행을 막는다 — 소요량을 모르는 지시를 현장에 내리지 않는다.

**화면**: 작업지시 관리 `prd-wo`(메뉴 "생산") — 조회(계획 시작일 기간 필수·공장·상태·품목·지시 번호) → 지시 표 → 상세: 폼(공장·품목·수량·계획일·라우팅·BOM 리비전 선택지(품목·공장·계획 시작일로 받아 옴)·수주 행·우선순위) + [저장]·[발행]·[취소], 발행 뒤에는 "공정(파라미터)" 과 "자재 소요" 스냅샷 표, 공정별 설비 배정.

**하지 않은 것(3단계)**: 착수·실적(5b), 일정 계획(APS — 9장), 지시 분할·병합, 자재 LOT 할당(4·5b), 엑셀 올리기(지시는 화면·수주에서 — 필요해지면 후속).

### 4.5 4단계 상세 — LOT·계보 원장 (V034)

**한 줄**: LOT 은 "같은 조건으로 만들어지거나 들어온 한 묶음", 계보는 "어떤 LOT 이 어떤 LOT 으로 얼마나 갔는가" 의 **원장**이다. 추적(정·역)은 결과 캐시 없이 계보 원장을 재귀로 따라간다(9장). P2 는 재고를 세지 않는다(D2) — LOT 잔량은 "만든 양 − 계보로 나간 양" 의 계산값이고, 로케이션별 재고는 P4.

**LOT `lot_master`**

| 칸 | 규칙 |
|---|---|
| `lot_no` | 회사 안 유일. 정책 `LOT_NO_MODE`(D4): `SEQ` = 채번 `LOT`(기본 `L{yyMMdd}-{seq:4}`, 매일) / `ITEM_SEQ` = 품목 코드 + `-` + 채번(현장이 라벨에서 품목을 바로 읽게) / `WO` = 지시 번호(생산 LOT, 같은 지시 두 번째부터 `-2`…; 생산이 아닌 LOT 은 SEQ). 자재 LOT 은 공급사 LOT 번호를 별도 칸에 둔다 |
| `origin` | `RECEIVED`(자재 간이 등록, D2) · `PRODUCED`(지시 산출 — 5b 실적이 만든다, 4단계는 서비스 메서드만) · `SPLIT` · `MERGE` · `RELABEL` |
| `item_id`·`qty_created`·`unit_cd` | 품목 기본 단위, 단위 자릿수. 만든 뒤 바뀌지 않는다(트리거 `ck_lot_master_fixed`) |
| `wo_id` | 생산 LOT 의 지시(같은 회사·같은 품목·발행 이후 지시) |
| `supplier_cust_id`·`supplier_lot_no` | 자재 LOT: 구매 거래처(PURCHASE·BOTH)·공급사 LOT 번호. ~~같은 공급사·품목 안 **유일**~~ → **2026-10-22 결정 #43(4.25, V135, 임시 V9114)**: 유일하지 않다 — 같은 공급사 LOT 을 다시 받으면 새 내부 LOT, 공급사 LOT 은 속성(리콜은 공급사 LOT 으로 찾아 전부) |
| `hold_yn`·`hold_reason` | 보류(품질 의심) — 보류 LOT 은 분할·병합·투입의 **부모로 쓸 수 없다**(트리거). 해제도 사유와 함께 감사 |
| 잔량 | `qty_created − Σ(이 LOT 이 부모인 계보의 순수량)` — 원장에서 계산, 칸으로 저장하지 않는다. 잔량 0 이면 화면에서 "소진" |

**계보 `lot_genealogy`** — 01 3.1 원장(이 프로젝트 첫 원장 테이블)

| 칸 | 규칙 |
|---|---|
| 기본 | (부모 LOT, 자식 LOT, `rel_type`, `qty` = 부모 LOT 에서 나간 양(부모 단위), `unit_cd`) — 같은 회사 LOT 끼리, 자기 자신 금지 |
| `rel_type` | `CONSUME`(투입, 5b) · `SPLIT` · `MERGE` · `RELABEL` · `REWORK`·`REPAIR`·`SORT`(7단계 흐름, 자리만) |
| 맥락 | `wo_id`·`wo_proc_id`·`equip_id`(선택, 같은 회사), `change_id`(4M 변경 — P3 자리), `reason` |
| 원장 공통 | `occurred_at`(현장 발생) + `recorded_at`(서버) + `entry_method` KEY/SCAN/AUTO/IMPORT/CORRECT + `recorded_by` + `performed_by`(작업자, 선택) + `reversal_of_id`. `hb_forbid_update_delete` + 앱 계정 UPDATE/DELETE REVOKE + 카탈로그 원장 검사 |
| 역분개 | 원래 행과 같은 부모·자식·유형, 수량 부호 반대, `entry_method = CORRECT`, 사유 필수, **한 행에 한 번**(부분 유일). 4단계는 `CONSUME` 정정만 허용 — 분할·병합을 되돌리는 것은 반대 동작(병합·분할)으로 한다(자식 LOT 이 이미 만들어져 있어서). **역분개 행의 맥락**: V110(사용자 결정 2026-10-21) 부터 새 역분개 행은 원래 행의 `wo_id`·`wo_proc_id`·`equip_id` 를 그대로 갖고(실적 `prd_result` 는 `equip_id`), 다르면 AFTER INSERT 트리거가 거부(`ck_lot_genealogy_reversal_ctx`·`ck_prd_result_reversal_ctx`). `occurred_at` 은 원장 규약대로 정정 시각 — 복사하지 않는다. **그 전 역분개 행은 맥락이 NULL** 이라 지시·공정·설비 기준 순수량은 계속 `wo_id` 합이 아니라 되돌려진 원래 행을 빼서 계산한다(`not exists (… v.reversal_of_id = g.gen_id)`: POP `inputEdges`·`WoMaterialQuery`·CMS KPI). LOT 기준(부모·자식) 합은 역분개를 더하면 순수량 |
| 수량 검사 | 부모 잔량을 넘겨 내보내지 못한다 — 서비스가 부모 LOT 행을 `FOR UPDATE` 로 잠근 뒤 잔량 확인(동시 분할 두 건이 같은 잔량을 쓰지 않게) |

**동작(4단계)**: 자재 LOT 간이 등록(RECEIVED), **분할**(한 LOT → 자식 n 개, 합 ≤ 잔량), **병합**(같은 품목 LOT 여러 개 → 새 LOT, 각 부모의 잔량 전부 또는 지정량), **라벨 바꿈**(RELABEL — 잔량 전부를 새 번호로), 보류·해제. 투입(CONSUME)·생산 LOT 은 서비스 메서드(5b 실적이 부른다) + 데모 시드.

**추적** `GET /api/production/lots/{id}/trace?dir=BACK|FWD` — 재귀 CTE, 경로 배열로 순환 차단, 깊이 상한 정책 `LOT_TRACE_DEPTH`(기본 30), 행 상한 5,000(넘으면 잘렸다고 표시). 같은 부모·자식·유형의 계보는 순수량(역분개 합산)으로 묶고 순수량 0 인 연결은 숨긴다. 결과: 깊이·LOT·품목·유형·순수량·지시.

**화면**: LOT 추적 `prd-lot`(메뉴 "생산") — 조회(생성일 기간·품목·LOT/공급사 LOT 번호·출처) → LOT 표(잔량) → 상세: LOT 정보 + [분할]·[병합 대상 추가]·[라벨 바꿈]·[보류/해제] + "어디서 왔나(역추적)"·"어디로 갔나(정추적)" 들여쓰기 표, 자재 LOT 등록 폼.

**하지 않은 것(4단계)**: 재고 위치·이동(P4), 라벨 인쇄(아웃박스 topic 만 — P4), 투입 화면(5b POP), 재작업·수리·선별 흐름(7), LOT 속성(유효기간·등급 — P3/P4).

### 4.6 5a 상세 — POP 기반: 단말·배지·`hb.pop`·작업장 홈 (V035)

**한 줄**: POP 은 사람 로그인이 아니라 **등록된 단말**이 들어오고, 그 단말 앞의 **작업자가 배지로 2초 안에 바뀐다**(01 D4, 11 D4). 사무 화면(셸·메뉴·`/api/**`)과 인증 체인부터 분리한다.

**단말 `sys_terminal`** (사무 화면 "POP 단말 관리" `sys-terminal`)

| 칸·동작 | 규칙 |
|---|---|
| 등록 | 관리자가 단말 코드·이름·공장·**작업장**(그 공장의 사용 중 LINE/CELL)·유형(POP/PDA)을 만든다 → 서버가 **등록 코드**(영문 대문자·숫자 10자, 한 번만 화면에 보임, 24시간)를 만들고 해시만 저장 |
| 활성화 | 태블릿에서 `/pop/activate` → 회사 코드 + 등록 코드 → 서버가 **단말 키**(32바이트 난수)를 만들어 해시만 저장하고 쿠키로 내려준다(`HB_TERMINAL`, HttpOnly·SameSite=Strict·Secure(https)·경로 `/`·400일). 등록 코드는 그 자리에서 지운다(한 번만). 쿠키 값 = `회사코드.단말id.키` — 회사를 먼저 알아 그 회사 범위(RLS)에서만 해시를 비교한다(회사 넘는 전역 조회 없음) |
| 매 요청 | 쿠키 → 회사 범위에서 단말 행 → 키 해시 일치·폐기 안 됨·공장·회사 사용 중이면 `ROLE_TERMINAL`. `last_seen_at` 은 1분에 한 번만 갱신(감사 제외 칸) |
| 키 다시 발급 | 새 등록 코드 → **기존 단말 키 즉시 무효**(분실·교체). 폐기는 되돌리지 않는다(사유 필수, 새 단말로 등록) |
| 등록 코드·키 시도 | 등록 코드 틀림은 IP 기준 시도 제한(로그인과 같은 `LoginAttemptLimiter`), 잘못된 단말 쿠키는 401 + 쿠키 지움 |

**작업자 배지 전환** — 단말 세션 안의 "지금 작업자"

| 규칙 | 내용 |
|---|---|
| 배지 | 스캐너(키보드 웨지: 글자 + Enter)로 읽은 값 → `sha256(회사:배지)` 로 사용 중 작업자 찾기(1단계 해시 그대로). 없거나 사용 중지면 같은 문구("배지를 다시 스캔")로 — 어느 쪽인지 알려 주지 않는다 |
| PIN | 공장 정책 `POP_PIN_REQUIRED`(기본 N) 이면 배지 뒤 화면 키패드로 PIN(숫자 4~8자리, BCrypt). **5번 틀리면 15분 잠금**(`mst_worker.pin_fail_cnt`·`pin_locked_until`). PIN 은 사무 작업자 화면에서 지정·지우기 |
| 비활동 | 정책 `POP_WORKER_IDLE_MIN`(기본 30분) 동안 요청이 없으면 작업자만 내려간다(단말은 그대로) — 공용 단말에 남은 작업자 이름으로 기록되지 않게 |
| 시도 제한 | 단말마다 1분에 틀린 배지 10번 → 1분 막음(배지 맞히기 방지) |
| 기록 | 접속 로그 `client_type = POP`: 단말 활성화·배지 성공·실패·PIN 잠금, 행위자 = `t{단말}:{코드}` + 작업자 사번. 이후 원장은 `recorded_by` = 단말, `performed_by` = 작업자(5b) |

**보안 체인** `PopSecurityConfig`(@Order(0), `/pop/**`·`/api/pop/**`): 단말 쿠키 필터 → `ROLE_TERMINAL`, 활성화 화면·API 만 permitAll. 회사 사용자(ROLE_USER)는 POP API 를 못 쓰고, 단말은 사무 API 를 못 쓴다(경로가 다른 체인). CSRF(쿠키 → 헤더)·CSP·보안 헤더는 사무 체인과 같다. POP API 는 `@ProgAuthExempt`(근거: 단말 체인)이고 업무 권한은 "단말 + 작업자" 로 서비스가 본다.

**`hb.pop`** (`/js/common/hb.pop.js`, 키오스크 레이아웃 — 셸·메뉴 없음): 스캔 입력(화면에 숨은 칸이 항상 포커스, 웨지 입력·Enter, 사람 손 입력과 구분 없이 받음), 숫자 키패드(48px 이상, 지우기·확정), 큰 확정 버튼(연타 잠금 `hb.busy`), 결과 피드백(색 + 진동 `navigator.vibrate`, 3초), 언어 1탭(ko/en — vi 는 문구 추가 때), 온라인/오프라인 표시(오프라인 대기 건수 자리 — 5b D6).

**작업장 홈** `/pop`: 작업자가 없으면 배지 화면, 있으면 "내 할 일" — 그 단말 작업장(LINE 이면 아래 CELL 포함)의 **발행·진행 지시 공정**(완료 아님), 우선순위·계획 시작일 순, 행마다 지시·품목·수량·공정·배정 설비 + **자격 게이트 미리보기**(1단계 `QualGate` — 지금 작업자로 OK/경고/차단). 착수 버튼은 5b.

**5a 완료 기준(24 7장 K)**: 태블릿 1280×800·세로 800×1280·휴대폰 390 에서 넘침 없음, 누르는 대상 48px 이상, 키보드 없이 PIN 까지(배지는 스캐너), 확정 연타 1회, 두 단말에서 같은 작업자, 비활동 내려감, 폐기된 단말 즉시 끊김.

**하지 않은 것(5a)**: 착수·실적·투입(5b), 오프라인 대기열(5b D6), 베트남어 문구, 단말 화면 잠금 해제용 관리자 비밀번호(E 항목 — 5b 정정 승인과 같이), PDA 화면(P4).

### 4.7 5b 상세 — 착수·실적·투입 (V036)

**한 줄**: 단말 앞 작업자가 "내 할 일" 카드에서 **착수 → (멈춤·재개) → 실적 확정(양품·폐기·재작업·수리 + 불량 유형, 자재 LOT 스캔 투입) → 종료** 를 하고, 모든 입력은 원장(수정·삭제 없음)에 `recorded_by = 단말`, `performed_by = 작업자` 로 남는다. 네트워크가 끊겨도 실적 확정은 단말에 쌓았다가 같은 요청 id 로 다시 보낸다.

**원장 4종** (01 3.1 공통 칸: `occurred_at`·`recorded_at`·`entry_method`·`recorded_by`·`performed_by`·`reversal_of_id`, UPDATE/DELETE 차단 + REVOKE, 보존 PRODUCTION_RECORD)

| 테이블 | 한 행 = | 핵심 규칙 |
|---|---|---|
| `prd_wo_proc_event` | 공정 착수·멈춤·재개·종료 한 번 | `event_cd` START/PAUSE/RESUME/END, 멈춤은 비가동 사유(공통코드 `DOWNTIME_REASON`, 회사 확장) 필수, 착수 때 자격 게이트 결과(OK/WARN) 기록. 정정 없음(다음 이벤트로 바로잡는다) |
| `prd_result` | 실적 확정 한 번 | 양품·폐기·재작업·수리 수량(지시 단위, 0 이상·합 > 0, 단위 자릿수), 작업일·근무조(저장 때 계산), 산출 LOT, 정정 = 역분개(모든 수량 부호 반대 + 사유, 한 번만) |
| `prd_result_defect` | 실적의 불량 유형별 수량 | 종류 SCRAP/REWORK/REPAIR 마다 합 = 실적의 그 수량(**확정 때 필수**, 지연 제약 트리거가 커밋 때 확인), 역분개 때 같이 반대 |
| `prd_param_actual` | 파라미터 실측 한 번(수동) | 지시 파라미터 스냅샷(`prd_wo_param`)만, 판정 OK/NG(상하한), 정정 = 그 행을 가리키는 CORRECT 행(값은 그대로, 조회에서 둘 다 빠짐) |

**공정 상태 전이**(트리거, 이벤트 없이 상태만 바꾸는 직접 SQL 은 거부 — 같은 트랜잭션에 그 이벤트 행이 있어야 한다)

| 지금 | 동작 | 다음 | 지시 |
|---|---|---|---|
| WAIT·READY | 착수 START | RUN | 발행 → 진행(첫 착수) |
| RUN | 멈춤 PAUSE(사유) | PAUSE | |
| PAUSE | 재개 RESUME | RUN | |
| RUN·PAUSE | 종료 END | READY | 공정 완료(DONE)는 7단계 완료판정 |

한 공정에 동시에 두 단말이 착수하면 공정 행 `FOR UPDATE` 로 한쪽만(다른 쪽 `STATE`). 실적은 RUN·PAUSE 일 때만.

**실적 확정 = 한 트랜잭션**: 공정 잠금 → 수량·불량 내역 검증 → 양품 > 0 이면 **산출 LOT**(지시 품목, `LOT_NO_MODE` 그대로, 수량 = 양품) 생성 → 스캔한 자재 LOT 마다 **계보 CONSUME**(자재 LOT → 산출 LOT, 자재 LOT 단위 수량, 잔량·보류는 4단계 트리거) → `prd_result` + `prd_result_defect`. 투입이 있는데 양품이 0 이면 거부(`INPUT_NEEDS_GOOD` — 폐기에 들어간 자재는 7단계 보존식에서). 다음 공정은 앞 공정의 산출 LOT 을 투입으로 스캔할 수 있다(공정 LOT 사슬).

**정정(역분개)**: 단말에서 오늘 실적 → "정정" → 사유 → 역분개 행(수량 반대) + 불량 내역 반대 + 그 실적의 투입 계보 역분개 + 산출 LOT 보류(사유 "실적 정정"). 산출 LOT 이 이미 다른 데로 나갔으면 거부(`LOT_USED`) — 뒤 공정을 먼저 정정.

**멱등**: 모든 POP 저장은 `client_req_id`(단말이 만든 UUID) + `unique(comp_cd, client_req_id)`. 같은 id 가 다시 오면 **처음 저장한 행으로 같은 응답**(409 아님), 동시에 두 번 오면 unique 위반을 잡아 먼저 저장된 행을 돌려준다. 같은 id 에 다른 내용이면 처음 것이 이긴다(본문 비교 안 함 — 단말이 id 를 재사용하지 않는다).

**시각**: 발생 시각은 단말이 보낸 값(오프라인 대기 뒤 재전송 포함, 없으면 서버 지금), 정책 `POP_FUTURE_TOLERANCE_SEC`(기본 300초)보다 미래면 거부, 지시 발행 전이면 거부. 작업일·근무조는 발생 시각으로 `WorkDayResolver`.

**오프라인 대기열(D6)**: 실적 확정만. 보내기 전에 IndexedDB 에 먼저 쓰고(write-ahead) 성공하면 지운다. 네트워크 오류·5xx·타임아웃이면 남겨 두고 온라인이 되면 같은 `client_req_id` 로 순서대로 다시 보낸다. 4xx(검증 오류)는 대기열에서 빼고 화면에 남긴다. 머리띠에 대기 건수. 착수·멈춤·종료는 온라인일 때만(상태가 엇갈리면 안 되므로).

**입력하면 돌려준다**: 실적 확정 응답 = 그 공정의 오늘(작업일) 양품 합 / 계획 수량, 이 실적의 산출 LOT 번호, 직전 불량 유형.

**보안·권한**: 모든 동작은 단말(ROLE_TERMINAL) + 지금 작업자 필수(없으면 401 `POP_NO_WORKER` → 배지 화면). 단말 작업장(라인 + 아래 셀) 밖 공정은 404 처럼(남의 공정 id 를 넣어도). 자격 게이트 BLOCK 이면 착수 거부(`QUAL_BLOCKED`), WARN 이면 확인(`ack`) 뒤 착수하고 이벤트에 WARN 이 남는다.

**5b 완료 기준**: 같은 `client_req_id` 재전송 → 처음 응답 그대로·행 1개, 동시 두 번 → 행 1개, 오프라인 → 온라인 재전송, 공용 단말 대리 입력이 `recorded_by`(단말)/`performed_by`(작업자)로 드러남, 원장 직접 SQL UPDATE/DELETE 42501, A사 단말로 B사 공정.

**하지 않은 것(5b)**: 공정 완료판정·지시 완료(7단계), 설비 상태 구간(6단계 — 멈춤 사유는 공통코드로 받고 6단계가 `cms_state_reason` 으로 옮긴다), 사무 실적 조회 화면(7단계 보고서와 함께), 투입 단위 환산(자재 LOT 단위 그대로).

### 4.8 6단계 상세 — 설비 상태·연결·Collector (V051)

**한 줄**: 수집기가 `POST /ext/v1/equip-events` 로 표준 이벤트(4.1)를 보내면, MES 는 **상태 구간 원장**(겹침 없음)·**연결 로그**(끊김·누락)·**에너지 원장**·**파라미터 실측**으로 나눠 남긴다. 통신이 끊긴 시간은 비가동이 아니라 **UNKNOWN** 이다(02 2.2). 실제 프로토콜은 P6, 지금은 **가짜 수집기**(실제 HTTP)로 데모·시험한다.

**수집 장치 = 단말의 한 유형** (`sys_terminal.terminal_type = COLLECTOR`, 5a 재사용 — 별도 `sys_device_key` 를 만들지 않는다)

| 칸·동작 | 규칙 |
|---|---|
| 등록·다시 발급 | 사무 "POP 단말 관리" 에서 유형 COLLECTOR 로 만들면 등록 코드 대신 **API 키**(`회사.id.키`, 한 번만 보임, 해시만)를 준다. 다시 발급 = 기존 키 무효, 폐기 즉시 끊김(5a 와 같음) |
| 인증 | `Authorization: Bearer 회사.id.키` → 그 회사 범위에서 해시 비교 → `ROLE_DEVICE`, 행위자 `d{id}:{코드}`. POP 쿠키로는 COLLECTOR 를, Bearer 로는 POP 단말을 쓸 수 없다 |
| 범위 | 그 장치의 공장(작업장이 있으면 그 아래 노드)에 있는 **사용 중 설비만**. 밖의 설비 이벤트는 그 건만 거부 |
| 보안 체인 | `ExtSecurityConfig`(@Order(0), `/ext/**`): 세션 없음, CSRF 없음(쿠키를 안 쓰므로), 보안 헤더 같음. 요청 하나 최대 500건. 거부는 접속 로그 `TERMINAL_DENIED`(구분 BEARER) |
| 유형 고정 | 수집 장치 ↔ POP·PDA 는 바꾸지 않는다(서비스 `FIXED` + 트리거 `ck_sys_terminal_collector_fixed`) — 바꾸면 쿠키 키가 Bearer 로, Bearer 키가 쿠키로 쓰인다 |

**원장·마스터 (V051)**

| 테이블 | 성격 | 핵심 규칙 |
|---|---|---|
| 상태 분류 | 전역 공통코드 `STATE_CAT`(Flyway 로만, 회사 추가 불가) + 각 표 CHECK | SEMI E10 6분류 PRD·SBY·ENG·SDT·UDT·NST + UNKNOWN(통신 끊김). 4장 요약의 `cms_state_cat` 표 대신 코드 — 이름만 필요하고 회사가 늘리면 안 되는 고정 7개라 표·FK 가 얻는 것이 없다 |
| `cms_state_reason` | 회사 마스터 | 사유 코드 → 상태 분류·계획 여부. 수집기 STATE 이벤트의 `reason` 메트릭으로 연결(5b 의 POP 멈춤 사유 공통코드는 7단계 이후 이 표로 옮긴다) |
| `cms_equip_state_log` | 구간 원장 | (설비, [start_at, end_at)) **겹침 배제 제약**(btree_gist), 열린 구간은 설비당 하나. **"한 번만 닫기"**(D8): `end_at` null → 값 1회만(시작보다 뒤), 그 밖 UPDATE·DELETE 차단 — 앱 계정은 `end_at` 칸만 UPDATE 권한(열 권한), 트리거가 나머지를 막는다. `wo_id` = 그때 그 설비에서 진행 중인 지시 |
| `cms_equip_conn_log` | 원장 | ONLINE·OFFLINE·**GAP**(seq 누락 `seq_from~seq_to`). 수집 장치별 마지막 seq 는 `cms_collector_seq`(원장 아님) |
| `cms_energy_reading` | 구간 사용량 원장 | (계측기 코드, [period_start, period_end)) 겹침 배제, 수량 > 0, 출처 COLLECTOR/MANUAL/BILL, 정정 = 역분개 |

**이벤트별 처리** — ~~이벤트마다 각자 트랜잭션~~ → **묶음 요청 하나 = 트랜잭션 하나, 건마다 저장점**(한 건이 틀려도 그 건만 되돌리고 나머지는 저장, 2026-11-16 docs/70 W1·W3 — COUNT 는 멱등 기록·원장을 묶음 끝 한 문장씩), 같은 `eventId` 는 ~~`sys_inbox`(소비자 `EQUIP_EVENT`)~~ → `cms_equip_inbox`(보존 클래스 EQUIP_INBOX 기본 7일, docs/70 W2)로 한 번만, 응답 202 `{accepted, duplicates, rejected[{eventId, code}]}`

| type | 처리 |
|---|---|
| STATE | 메트릭 `state`(분류) + 선택 `reason`(사유 코드). 열린 구간과 같은 분류·사유면 그대로(중복 신호), 다르면 열린 구간을 그 시각에 닫고 새 구간을 연다. **열린 구간 시작보다 이른 이벤트(늦게 온·순서 뒤바뀐)** 는 과거 구간을 다시 쓰지 않고 `LATE` 로 거부(원장은 과거를 고치지 않는다 — 수집기 버퍼 재전송은 순서대로 보내야 한다) |
| CONN | 메트릭 `online`. OFFLINE → 연결 로그 + 열린 구간 닫고 **UNKNOWN** 구간 시작. ONLINE → 연결 로그 + UNKNOWN 구간 닫기(다음 STATE 가 새 구간) |
| PARAM | 그 설비에서 **진행 중(RUN)인 지시 공정**의 스냅샷 파라미터와 이름이 같은 메트릭만 `prd_param_actual`(source COLLECTOR, entry AUTO, 판정 상하한). 진행 중 공정이 없으면 `NO_RUNNING_PROC` |
| METER_READING | 이벤트 `periodStart` + 메트릭(이름 = 계측기 코드, 값 = 구간 사용량, `unit`) → `cms_energy_reading`. 같은 계측기 구간 겹침은 `OVERLAP` |
| COUNT·ALARM | ~~받기만(`sys_inbox`)~~ → COUNT 는 원장 `cms_equip_count`·POP 실적 제안(4.27, 2026-10-22), ALARM 은 CMS `AlarmSink`(4.17) |

**줄 세우기**: STATE·CONN 은 설비 단위 advisory 잠금(`hashtext('cms_equip_state'), 설비`) 뒤 열린 구간을 읽는다 — 열린 구간이 없을 때 동시 두 이벤트가 둘 다 "없음" 을 보고 여는 것을 막는다(배제 제약은 마지막 방어). 뒤집혀 도착한 쪽은 `LATE`.

**seq 누락**: 장치별 마지막 seq 보다 2 이상 크면 그 사이를 GAP 으로 기록하고 마지막 seq 를 올린다. 더 작으면(재전송·순서 뒤바뀜) 누락 기록 없이 처리만.

**화면** `cms-state`(설비 상태 타임라인, 최소형): 공장·날짜 → 설비마다 하루 막대(분류 색 + 끊김 UNKNOWN 빗금 대신 회색) + 구간 표(시작·끝·분류·사유·지시) + 연결 로그(끊김·누락). 막대 폭은 CSSOM(`el.style.width`, CSP 허용)으로.

**가짜 수집기** `tools/fake-collector.mjs`(Node, 의존성 없음): API 키·설비 코드를 받아 STATE 순환(PRD → SBY → UDT …)·CONN 끊김·seq 건너뛰기·PARAM·METER_READING 을 실제 HTTP 로 보낸다. 시험은 같은 이벤트를 MockMvc 로.

**6단계 완료 기준**: 통신 끊김이 UNKNOWN, 늦게 온 이벤트 `LATE`·순서 뒤바뀜·중복(`sys_inbox`), 구간 겹침 거부(배제 제약), 닫힌 구간 수정·두 번 닫기 거부, 장치 범위 밖 설비 거부, A사 장치로 B사 설비.

**하지 않은 것(6단계)**: 상태 구간 수동 입력·사유 사후 지정(P5 CMS), **사유 마스터 화면**(`cms_state_reason` 은 표·트리거만 — 관리 화면은 CMS 세션, 그때까지는 직접 등록), OEE 계산(P5), 태그 매핑 `cms_tag_map`(P6), COUNT → 실적 자동 제안(7단계), 알람 원장(P5).

**구현하며 바꾼 것·발견한 것 (2026-10-20, MES 세션)**

| # | 내용 | 조치 |
|---|---|---|
| F1 | **역분개 사유 필수가 NULL 사유를 통과**: `… AND length(btrim(reason)) > 0` 은 reason 이 NULL 이면 NULL 이고 CHECK 는 NULL 을 통과로 본다 — `lot_genealogy`(4단계)·`prd_result`·`prd_param_actual`(5b) 원장에 직접 SQL 로 사유 없는 정정 행이 들어갈 수 있었다(서비스는 늘 사유를 넣는다) | V051 이 같은 뜻의 제약 `ck_*_reversal_reason`(`coalesce(length(btrim(reason)), 0) > 0`)을 덧붙임, 에너지 원장은 처음부터 이 모양. 시험 LotTest·PopWorkTest·EquipEventTest 직접 SQL |
| F2 | 수집 장치 ↔ POP 유형을 수정 화면에서 바꿀 수 있었다(WIP) | 위 "유형 고정" |
| F3 | 열린 구간이 없을 때 동시 STATE 두 건이 배제 제약 `OVERLAP` 으로 하나를 잃었다(WIP) | 설비 단위 advisory 잠금 |
| F4 | Flyway 이름 가드가 세 자리 번호만 받아 임시 번호 V9101 이 실패 | 가드가 `V9xxx` 4자리도 허용(공통 변경) |
| F5 | CMS 1부(main)가 같은 패키지에 `CmsMapper`·`CmsDtos` 를 먼저 병합 | 수집기 쪽을 `CollectorMapper`·`CollectorDtos` 로 |

### 4.9 7a 상세 — 공정 완료판정·지시 완료·마감·생산 입고·재작업/수리 처리 (V055)

**한 줄**: 공정은 POP 에서 종료(READY)한 뒤 사무 화면에서 **완료판정**(보존식)으로 DONE, 모든 공정이 DONE 이면 지시 **완료**(DONE) → **마감**(CLOSED). 마지막 공정의 양품 LOT 을 창고·로케이션으로 **생산 입고**(`prd_receipt`, 재고는 세지 않는다 — WMS 2부가 이 원장을 첫 소스로 이관, docs/30 D2). 공정의 재작업·수리 **대기 수량**은 처리 원장(`prd_rework`)으로 양품(새 산출 LOT)·폐기로 나눈다.
7단계 나눔: **7a**(이 절) / **7b** ErpPort·엑셀 내보내기·가짜 ERP, LOT 역추적 보고서(화면 + PDF), LOT 단위 재작업(계보 REWORK/REPAIR), 데모 관통 시드.

**수량 정의** (공정 하나, 실적 원장 역분개 포함 순합)

| 이름 | 계산 |
|---|---|
| 실적 합 | G(양품)·S(폐기)·RW(재작업)·RP(수리) = `prd_result` 순합 |
| 처리됨 | `prd_rework` 의 종류별 처리 수량 합(양품 g′ + 폐기 s′) |
| 대기 | RW − 처리됨(REWORK), RP − 처리됨(REPAIR) — 0 미만 불가 |
| 유효 양품 | G + 처리 양품 g′ (다음 공정의 투입) |
| 투입 | 첫 공정: 처리 합계 그 자체(아래 D15) / 그 밖: **앞 공정(op_seq 바로 앞)의 유효 양품** |
| 보존식 | 투입 = G + S + RW + RP, 허용 오차 = 투입 × 정책 `PROC_BALANCE_TOL_PCT`(%, 공장별, 기본 0) |

**완료판정** `POST /api/production/wos/procs/{id}/close`(prd-wo SAVE) — 미리보기 `GET …/close-check` 와 같은 계산

| 조건 | 오류 |
|---|---|
| 지시 IN_PROGRESS, 공정 READY(POP 종료 뒤) — RUN·PAUSE 는 먼저 종료, WAIT(착수 없음)는 판정 대상 아님 | `STATE` |
| 앞 공정이 DONE | `PREV_OPEN` |
| 재작업·수리 대기 0 ("닫을 때 필수", 11 D13) | `PENDING` |
| 처리 합계 > 0 | `NO_RESULT` |
| 보존식(첫 공정 제외) | `BALANCE`(투입·처리·차이·허용 오차를 문구에) |

판정 결과는 원장 `prd_wo_proc_close`(투입·G·S·RW·RP·유효 양품·차이·허용 % + 판정자·시각)에 남고, 같은 트랜잭션에서 공정 READY → **DONE**. `hb_guard_wo_proc_status` 를 바꿔(5b 가 비워 둔 자리) DONE 은 **같은 트랜잭션의 판정 행**이 있을 때만 — 직접 SQL 로 상태만 바꾸기 거부. DONE 은 끝(되돌리기 없음).
DONE 뒤 막는 것(트리거): 그 공정의 착수·실적(이미 READY 라 막힘), **실적 정정(역분개)** `ck_prd_result_proc_done`, 재작업 처리.

**지시 완료·마감** `POST /api/production/wos/{id}/complete`·`/close`(rowVer): 완료 = 모든 공정 DONE(트리거 `ck_prd_wo_procs_done`), 마감 = DONE 에서. 마감 뒤 입고·입고 정정 거부. 미입고 양품은 마감 화면에 보여만 준다(마감을 막지 않는다 — 불량 판정·샘플 등 입고하지 않는 양품이 있다).

**재작업·수리 처리** `prd_rework` (원장): (공정, 종류 REWORK/REPAIR, 수량 = 양품 + 폐기, 산출 LOT, 사유) — 양품 > 0 이면 그 지시의 생산 LOT 을 새로 만든다(`LotService.createProduced`). 트리거가 **공정 행을 FOR UPDATE** 로 잠그고 대기 ≥ 수량·공정 DONE 아님을 확인. 역분개 없음(D14). 실적 정정이 대기를 0 미만으로 만들면 거부 `ck_prd_result_rework_disposed`(처리된 재작업의 실적을 되돌리려면 — 7a 범위 밖, 산출 LOT 보류로 대응).

**생산 입고** `prd_receipt` (원장 — WMS `inv_trx` 로 이관될 칸: 품목·LOT·공장·노드·수량·단위·발생 시각)

| 규칙 | 내용 |
|---|---|
| 대상 LOT | 그 지시의 생산 LOT 중 **마지막 공정**에서 나온 것(실적 또는 재작업 처리 산출), 보류 아님 |
| 위치 | 같은 공장의 사용 중 WH·LOC 노드 |
| 수량 | > 0, 단위 = 지시 단위, LOT 별 순입고 합 ≤ 만든 양 — 트리거가 LOT 행 FOR UPDATE 뒤 확인(동시 입고) |
| 지시 상태 | IN_PROGRESS·DONE (마감·취소 거부) |
| 정정 | 역분개(같은 LOT·위치, 수량 반대, 사유, 한 번), 마감 전만 |
| 멱등 | `client_req_id`(화면 연타·재전송) |

**화면**: 작업지시 관리(`prd-wo`) 상세 공정 표에 공정마다 "완료판정"(미리보기 → 확인)·"재작업/수리 처리", 머리에 "지시 완료"·"마감". 새 화면 **생산 입고**(`prd-receipt`): 지시 조회(진행·완료) → 입고 가능 LOT(유효 양품·입고됨·남음) → 창고/로케이션·수량 → 입고, 입고 이력 + 정정.

**7a 완료 기준**: 보존식 위반 공정은 완료 불가, 대기 남은 공정 완료 불가, 앞 공정 미완료 순서 위반, 공정 미완료 지시는 완료 불가, DONE 공정 실적 정정 거부, LOT 별 입고 초과·동시 입고, 마감 뒤 입고, A사로 B사, 직접 SQL(상태만 DONE·원장 수정 삭제).

**하지 않은 것(7a)**: POP 에서 완료판정(사무만 — 판정은 감독자 권한), 처리 정정, LOT 단위 재작업(7b), 재고 수량(P4 2부 이관), 라벨 인쇄(P4).

### 4.10 7b 상세 — LOT 역추적 보고서(감사 대응)·LOT 단위 재작업/수리 (V056)

**한 줄**: LOT 번호 하나로 **"이 LOT 은 무엇으로·누가·어느 설비에서·어떤 조건으로 만들었고, 어디로 갔나"** 를 한 화면에 모으고(06 M2 — 고객 감사·클레임 대응), 인쇄(브라우저 PDF 저장)로 넘긴다. 보류된 LOT 을 재작업·수리해 **새 LOT 으로 내보내는** 계보 REWORK/REPAIR 를 연다.
7단계 나머지: **7c** `ErpPort` + ERP 업로드용 엑셀 내보내기 + 가짜 ERP(07 D4), 데모 관통 시드(수주 → 보고서).

**보고서 `prd-lot-report`** (조회만, `GET /api/production/lot-report?lotNo=`) — 각 절은 기존 원장을 읽기만 한다(결과 캐시 테이블 없음, 9장)

| 절 | 내용 | 원천 |
|---|---|---|
| 머리 | LOT·품목·출처·만든 양·잔량·보류(사유)·입고 합, 만든 시각, 보고서 만든 사람·시각(회사 시간대) | `lot_master`·`hb_lot_balance`·`prd_receipt` |
| 어디서 왔나 / 어디로 갔나 | 4단계 추적 그대로(깊이 정책 `LOT_TRACE_DEPTH`, 5,000행 상한 — 넘으면 "잘림" 표시) | `LotService.trace` |
| 생산 이력 | 이 LOT 과 역추적에 나온 **생산 LOT** 을 만든 실적·재작업 처리: 지시·공정·양품/폐기/재작업/수리·작업자·단말·설비·작업일·근무조·입력 방식, 정정 행 표시 | `prd_result`·`prd_rework` |
| 공정 조건 | 그 실적들의 공정 파라미터 실측(값·판정·출처 MANUAL/COLLECTOR, 정정 표시) | `prd_param_actual` |
| 완료판정 | 그 지시들의 공정 판정(투입·처리·차이·허용) | `prd_wo_proc_close` |
| 설비 상태 | 그 지시에 걸린 상태 구간(분류·사유·시작·끝) | `cms_equip_state_log.wo_id` |
| 입고 | 이 LOT 의 입고·정정 | `prd_receipt` |

절마다 행 상한(500)과 "잘림" 표시. **인쇄**: 화면의 [인쇄] = `window.print()` + 인쇄용 CSS(조회 칸·버튼 숨김, 표 전체 펼침, 쪽 나눔은 절 단위). 서버 PDF 는 만들지 않는다(D17).

**LOT 단위 재작업·수리** (`POST /api/production/lots/{id}/rework`, prd-lot SAVE): 부모 LOT(보류여도 됨) → 새 LOT(출처 `REWORK`/`REPAIR`, 같은 품목·단위, 보류 아님) + 계보 행(rel_type 같은 이름, 수량 ≤ 잔량, 사유 필수). 4단계 트리거는 **보류 LOT 을 부모로 쓰지 못하게** 하는데, 재작업·수리는 보류의 정상 출구라 이 두 유형만 연다(D18). 폐기는 여기서 하지 않는다(P3 부적합 처분). 재작업 산출 LOT 은 생산 입고 대상이 아니다(입고는 지시 마지막 공정 산출만 — 재작업 LOT 의 입고 경로는 P4 WMS).

**7b 완료 기준**: 2단계 이상 공정 LOT 의 보고서가 자재 LOT·공급사 LOT·실적·작업자·설비·파라미터·판정·상태 구간·입고를 한 번에, 잘림 표시, A사로 B사(같은 LOT 번호), 보류 LOT 재작업 → 새 LOT, 보류 LOT 의 분할·투입은 여전히 거부, 직접 SQL.

### 4.11 7c 상세 — `ErpPort`·ERP 업로드용 엑셀 내보내기·가짜 ERP·데모 관통 시드 (V066)

**한 줄**: 07 D4 변경 채택 그대로 — 실제 ERP 는 붙이지 않고, **어댑터 경계(`ErpPort`)** 와 **실패 처리 규칙**을 가짜 ERP 로 시험하며, 고객이 바로 쓰는 경로는 **"ERP 업로드용 엑셀 내보내기"** 다. 마지막으로 데모 회사에 수주 → 보고서까지 이어지는 지시 하나를 시드한다.

**회사 정책 `ERP_SYSTEM`** = NONE(기본) / EXCEL(엑셀만 — 자동 송신 없음) / FAKE(가짜 ERP 로 자동 송신, 시험·데모). 실제 어댑터(이카운트 등)는 P6 — 같은 값 목록에 추가.

**자동 송신(FAKE)** — 업무 트랜잭션 안에서 아웃박스 발행(01 5.8), 송신은 릴레이 2단(트랜잭션 밖, 회사 범위 없음 — 핸들러에서 DB 금지라 **필요한 값은 발행 때 본문에 다 싣는다**)

| 토픽 | 언제 | biz_key | 본문 |
|---|---|---|---|
| `ERP_WO_RESULT` | 지시 완료(7a) | `WO:{지시 번호}` | 지시·품목(내부 코드 + ERP 코드)·마지막 공정 유효 양품·전 공정 폐기 합·완료 시각 |
| `ERP_RECEIPT` | 입고·입고 정정(7a) | `RCV:{입고 id}` | 입고 id·지시·품목(ERP 코드)·LOT·창고 코드·수량(정정은 음수)·발생 시각 |

ERP 품목 코드 = 회사의 **사용 중 ERP 시스템**(`mst_ext_system.system_type = ERP`, 하나만 — 여럿이면 코드 순 첫째)의 `mst_ext_id_map`(대상 ITEM). 매핑이 없으면 본문에 `erpItemCd = null` 로 싣고 어댑터가 거부(07 정책 `ERP_SEND_ON_UNMAPPED` 의 FAIL 쪽 — HOLD 는 실제 어댑터 때).

**`ErpPort` 결과 → 아웃박스 결과** (플랫폼 아웃박스 상태 NEW/SENDING/SENT/FAIL 를 바꾸지 않는다)

| ErpPort | 뜻 | 아웃박스 |
|---|---|---|
| OK | 전표 생성(외부 번호) | SENT |
| RETRY / RATE_LIMITED | 일시 오류 / 호출 제한(429) | RETRY(릴레이 지연·최대 횟수 그대로) |
| REJECTED | 4xx — 매핑 없음·형식 | FAIL(사람이 고쳐야 한다) |
| UNKNOWN | 읽기 타임아웃 — **상대가 받았을 수 있음**, 가짜 ERP 는 멱등이 없다 | FAIL("UNKNOWN") — **자동 재전송하지 않는다**(07 D3: 상대 멱등이 확인된 채널만 타임아웃 재시도). 사람이 `lookup(biz_key)` 로 들어갔는지 확인 |

**가짜 ERP** `FakeErpAdapter`(메모리, 기동마다 비움): 받은 전표를 biz_key 별로 쌓되 **같은 biz_key 를 또 받으면 전표가 하나 더 생긴다**(멱등 없음 — 실제 중소 ERP 의 흔한 모양), 시험 조절: N 번째마다 429, N 번째마다 "저장한 뒤" 타임아웃, 매핑 없는 품목 4xx. `lookup(biz_key)` = 그 키의 전표 수.

**엑셀 내보내기** 화면 `prd-erp-export`(조회·EXCEL): 종류 RESULT(완료된 지시 — 완료일 기준) / RECEIPT(입고·정정 행 — 발생일 기준) × 기간(정책 `SEARCH_MAX_RANGE_DAYS`) → 미리보기 표 → [엑셀](플랫폼 `ExcelExportService` — 건수 상한·수식 주입 방지·출력 정보 시트). 열 = ERP 업로드 양식(일자·ERP 품목 코드·품목명·수량·단위·창고·LOT·MES 번호). 내보낼 때마다 **`prd_erp_export` 기록**(종류·기간·건수·사람·시각) — 화면은 "이 기간은 이미 내보냄(언제·누가)" 을 보여 준다(ERP 이중 업로드 방지는 사람 판단, 막지는 않는다).

**데모 관통 시드**(local·demo 프로필, 완료된 지시가 없을 때만 — 기존 시더 규칙): 계획 상태 데모 지시(64311-DM100 사이드 멤버 INR, BLANK·DRAW 2공정) → 발행 → POP 착수·실적(공급사 강판 LOT 투입, 폐기는 불량 유형으로)·종료 → 공정 완료판정 → 지시 완료 → 입고. **화면과 같은 서비스 길**(POP 은 `PopWorkService`, 판정·완료·입고는 `closeAs`·`completeAs`·`receiveAs` — 로그인 없이 시더 계정으로 부르는 얇은 진입점, `WoService.createAs` 와 같은 방식). 회사 정책 `ERP_SYSTEM` 이 비어 있으면 FAKE 로 둬서 완료·입고가 아웃박스 → 가짜 ERP 로 간다. 수주 연결 지시(64310, 외주 공정 EDP 포함)는 흘리지 않는다 — 외주 공정 실적 경로가 P3 범위.

**구현 메모(as-built)**
- 지시 완료 시각 `prd_wo.done_at`(완료 때 한 번, CHECK: DONE·CLOSED 일 때만) — 내보내기 "완료일" 기준. 이 버전 전에 완료된 지시는 비어 있다(개발 데이터만, 백필하지 않음).
- 기간 = 회사 시간대 날짜(끝 날 포함), 화면 상한 92일과 회사 정책 `SEARCH_MAX_RANGE_DAYS` 중 작은 값(플랫폼 `DateRanges`). 미리보기 500행 + 전체 건수 + **ERP 매핑 없는 행 수**(경고 — 엑셀에는 ERP 코드 칸이 빈 채로 나간다).
- 엑셀 버튼은 마지막으로 조회한 조건으로만(조건을 바꾸면 다시 조회) — 미리보기와 다른 파일이 나가지 않게. 기록은 내보내기와 같은 트랜잭션(건수 상한 초과면 기록도 없음).
- `lookup` 은 아직 화면이 없다 — FAIL UNKNOWN 은 플랫폼 아웃박스 목록에서 보이고, 실제 어댑터(P6)에서 상대 조회 API 와 함께 화면을 붙인다.

**7c 완료 기준**: FAKE 정책에서 지시 완료·입고가 아웃박스로 나가고 가짜 ERP 가 받음, 429 → 재시도 후 성공, 매핑 없음 → FAIL, 타임아웃 → FAIL UNKNOWN·자동 재전송 없음·lookup 이 전표 1건, NONE·EXCEL 은 발행 없음, 엑셀 내보내기 열·건수·기록, A사로 B사.

### 4.12 3c 상세 — PLM 연결: `lot_genealogy.change_id` → ECO, 새 LOT 세 곳 `ChangeMarker.mark` (V077)

**한 줄**: docs/39 12.3·13장 합의의 MES 몫이다. 원본 표시는 PLM 의 `plm_eco_lot`(LOT 단위)이고, MES 는 새 LOT 을 만든 트랜잭션 안에서 `ChangeMarker.mark` 를 부른다. 돌려받은 ECO id 는 **그 LOT 이 자식인 계보 행**의 `change_id` 에 사본으로 적는다. MES 는 PLM 표를 읽지 않는다.

**새 LOT 을 만드는 세 곳**

| 곳 | 부르는 값 `mark(lotId, woId, equipId, occurredAt)` | `change_id` 를 적는 계보 |
|---|---|---|
| 5b 실적 확정(`PopWorkService`, 양품 > 0) | 산출 LOT · 지시 · 실적 설비 · 실적 발생 시각 | 이 실적의 투입 CONSUME 행 전부(투입이 없으면 계보 행 없음 — 표시는 `plm_eco_lot` 에만) |
| 7a 재작업·수리 처리(`CompletionService.rework`, 양품 > 0) | 새 산출 LOT · 지시 · **지시 공정 배정 설비**(`prd_wo_proc.equip_id`, 없으면 null) · 처리 시각 | 없음(처리 LOT 은 계보 부모가 없다 — 7a 그대로) |
| 7b LOT 단위 재작업·수리(`LotService.rework`) | 새 LOT · null(지시 없음) · null · 발생 시각 | REWORK/REPAIR 행 |

- 데모 시더의 4단계 시드(`createProduced` + `consume` 직접)는 표시하지 않는다 — 시드 시점에 적용된 ECO 가 없다. 7c 관통 시드는 POP 경로라 저절로 지난다.
- 표시는 게이트가 아니다 — 빈 값이면 `change_id` NULL 로 실적 그대로. PLM 이 없는 설치본은 `ChangeMarker.NONE`(`ChangeMarker.of(ObjectProvider)`).

**잠금 순서 MES 행 → ECO 행 (docs/39 Q3)**
- `mark` 는 후보 ECO 를 `FOR NO KEY UPDATE` 로 잠근다. 그 뒤에 MES 행을 새로 잠그면 순서가 뒤집힌다. 5b 는 투입 CONSUME 트리거가 **자재 LOT 을 `FOR UPDATE`** 로 잠그므로, 투입이 있으면 **자재 LOT 들을 id 순으로 먼저 잠그고**(`lockLot`) → 산출 LOT → `mark` → 투입 행 순서로 한다. 트리거의 다시 잠금은 같은 트랜잭션이라 기다리지 않는다.
- 7a 는 지시·지시 공정 행(`lockWo`·`lockProc`)을, 7b 는 부모 LOT 을 이미 먼저 잠근다.
- `change_id` FK(→ `plm_eco`)는 계보 INSERT 때 ECO 행에 **KEY SHARE** 를 건다. 그 ECO 는 같은 트랜잭션이 바로 앞 `mark` 에서 `FOR NO KEY UPDATE` 로 이미 쥔 행이라(돌려받은 id 만 적는다) **새 대기가 생기지 않는다**. PLM 의 ECO 상태 변경은 ECO 를 `FOR UPDATE` 로 잠그지만(`PlmMapper` lockEco) MES 표를 잠그지 않으므로, MES 쪽이 그 ECO 를 기다릴 수는 있어도 서로 기다리는 고리는 없다 — 이 대기는 `mark` 자체가 이미 갖는 것이다. `plm_eco_lot` 이 MES 표에 FK 를 두지 않은 이유(13.2 — ECO 잠금 **뒤에** MES 행을 KEY SHARE)와는 방향이 반대다.

**DB (V077)**
- `fk_lot_genealogy_change` : `change_id` → `plm_eco(eco_id)`. 지금 데이터는 전부 NULL(F1)이라 검증 비용 없음.
- `hb_guard_lot_genealogy` : 맥락 같은 회사 확인(`fk_lot_genealogy_ctx_comp`)에 `change_id` 의 회사 한 줄. 역분개 거울 검사(`ck_lot_genealogy_reversal`)에 `change_id` 추가 — 역분개 행은 원래 행과 같은 `change_id`(F3). 서비스의 역분개도 원래 값을 그대로 싣는다.
- 남의 표 변경 없음(`lot_genealogy` 는 MES 표, `plm_eco` 는 FK 로 참조만).

**3c 완료 기준**: 투입 있는 실적 → 계보 `change_id` = 표시 ECO / 투입 없는 실적·7a 처리 LOT 도 `plm_eco_lot` 표시 / 7b LOT 재작업 행 `change_id` / 첫 N 넘으면 NULL / 역분개가 같은 값, 다른 값 직접 SQL 거부 / 다른 회사 ECO·없는 ECO 직접 SQL 거부 / 같은 자재 LOT 을 투입하는 동시 실적 2건이 교착 없이 첫 1 만 표시 / A사로 B사.

### 4.13 홈 카드 링크용 조회 칸 — `prd-wo`·`prd-lot`·`sal-order` (홈 H17, V081)

**한 줄**: 홈 카드 숫자를 누르면 그 숫자의 목록이 열리도록(docs/37 16.2 — "화면이 카드 조건을 **모두** 받을 수 있을 때만 링크") 세 목록에 칸을 더한다. 칸 이름은 홈 링크 규약 `?q.이름=값`(이름 `^[a-zA-Z][a-zA-Z0-9]{0,29}$`, 6개, 값 200자)으로 넘긴다. 링크는 조회 조건만 채우고, 결과는 여는 사람 권한으로 조회된다.

| 카드 | 카드 조건 | 화면에 더한 것 | 링크 예(홈 세션이 붙인다) |
|---|---|---|---|
| `prd-wo-today` | 상태 RELEASED·IN_PROGRESS + 계획 시작 ≤ 오늘 | 상태 세그먼트에 **`status=ACTIVE`**(발행+진행 중), 기간(계획 시작)을 **열린 기간**으로(시작·끝 각각 비울 수 있음) | `status=ACTIVE, from=, to=오늘` (+ `plantCd=, itemCd=, keyword=` 로 열린 탭의 다른 칸 비우기 — 6개) |
| `prd-lot-hold` | 보류 + 잔량 > 0 | 체크 **`holdOnly=Y`**(보류 중이고 잔량 있는 LOT 만), 기간(등록일)을 열린 기간으로 | `holdOnly=Y, from=, to=` (+ `keyword=, itemCd=, origin=`) |
| `sal-due-soon` | 열린 수주 행 + 납기 ≤ 오늘+7 (**행 수**) | 두 번째 기간 **`dueFrom`~`dueTo`**(열린 행의 납기, 기본 빈 값), 수주일 기간도 열린 기간으로. 납기 조건이 있으면 목록 위에 **"납기 행 n건"**(카드와 같은 단위)도 보인다 | `dueTo=오늘+7, from=, to=` (+ `custCd=, keyword=, itemCd=`) |

- **열린 기간**: 공통 `hb.dateRange` 의 `data-required="false"` — 빈 칸은 그쪽 끝이 없다는 뜻이다. 서버는 둘 다 있을 때만 순서·366일 상한을 본다. 기본값(지난 30일)은 그대로라 평소 화면은 달라지지 않는다. 둘 다 비우면 전체에서 최근 순 500행 + "더 있음" — 다른 목록과 같은 상한이다.
- **수주는 목록이 머리(수주) 단위**다. 카드는 행 수를 센다. 그래서 납기 조건이 있을 때는 조건에 맞는 열린 행 수를 따로 세서 목록 정보 줄에 함께 보인다(카드 숫자 = 행 수). 수주 상태 칸은 그대로다 — 열린 행이 있으면 머리는 OPEN 이므로 기본값(진행)과 어긋나지 않는다.
- `status=ACTIVE` 는 코드표(`WO_STATUS`) 값이 아니라 조회 묶음이다(저장되지 않음). 다른 묶음이 필요해지면 같은 자리에 더한다.
- 이름 충돌 없음: 세 화면 모두 `from`·`to`·`keyword`·`itemCd` 는 기존 이름 그대로, 새 이름은 `holdOnly`·`dueFrom`·`dueTo`·값 `ACTIVE`.

**실행계획(인덱스, V081)**
- `lot_master` 는 기간 조회 인덱스가 없었다(품목·지시 인덱스만) — 지금도 등록일 조건은 회사 전체 행을 걸러 정렬한다. 열린 기간이면 전체를 읽는다. → `ix_lot_master_created (comp_cd, created_at, lot_id)`: 최근 순 `limit 501` 이 인덱스 역순으로 끝난다. 보류는 `ix_lot_master_hold (comp_cd, lot_id) WHERE hold_yn = 'Y'`(부분) — 보류 LOT 은 적어서 `hb_lot_balance` 계산이 보류 행에만 돈다(홈 카드 `lotHoldCount` 도 같은 인덱스).
- `prd_wo`: `ix_prd_wo_active (comp_cd, plan_start) WHERE status IN ('RELEASED','IN_PROGRESS')`(부분) — 홈 카드와 `ACTIVE` 조회가 끝난 지시를 훑지 않는다(docs/37 H5 가 예고한 인덱스). 그 밖 조회는 기존 `ix_prd_wo_plan`.
- `sal_order_line`: 기존 부분 인덱스 `ix_sal_order_line_due (comp_cd, due_date) WHERE status = 'OPEN'` 로 납기 `exists`·행 수 모두 된다 — 새 인덱스 없음.
- 실측 실행계획은 시험 데이터가 작아 의미가 없다 — 인덱스 존재·사용은 모양으로만 판단(미실측).

**4.13 완료 기준**: 세 칸 조합(단독·기존 조건과 함께·열린 기간)·형식 오류(값 코드·날짜·순서)·카드 숫자 = 링크 조건 목록 건수(지시·LOT) / 납기 행 수(수주)·A사로 B사.

### 4.14 생산 입고 → 재고 원장 연결 (WMS B1 계약, docs/30 11장 — 마이그레이션 없음)

**한 줄**: `ReceiptService.receiveAs`·`correct` 가 `prd_receipt` 를 넣은 직후, ERP 발행 전에 같은 트랜잭션으로 WMS `ProductionReceiptPosting.post(id)` 를 부른다(MES 답 2026-10-21 그대로). MES 표·트리거는 바꾸지 않는다. 의존 방향은 production → wms 하나다.

- 잠금 순서: 입고 트리거의 `lot_master FOR UPDATE` → WMS 기간 공유 잠금 → `inv_stock`(docs/30 11.6). MES 의 다른 경로는 재고 행을 잡지 않는다.
- 오류는 잡지 않는다 — 입고와 재고가 같이 롤백된다. 화면 표시:
  - 재고 안 둠 위치: WMS 가 `nodeId` 칸 오류 `STOCK_NOT_ALLOWED` 로 던진다. 입고 폼 칸 이름도 `nodeId` 라 그대로 칸에 보인다.
  - 정정 때 재고가 이미 옮겨졌거나 쓰였으면 WMS 409 `INSUFFICIENT_STOCK` → MES 가 칸 오류 `status`/`STOCK_MOVED`("이미 옮기거나 쓴 재고입니다 — 재고를 되돌린 뒤 정정하세요.")를 붙여 다시 던진다. 409 코드는 그대로.
  - 입고 뒤 품목 기본 단위가 바뀌면 WMS 칸 오류 `unitCd`/`UNIT`(MES Q1 답의 빈틈) — 입고 화면은 오류 문구를 알림 줄에 보인다.
- 데모 시더(`receiveAs`)도 같은 길을 지난다 — 데모 회사 입고가 재고 원장에 들어간다.

### 4.15 공장 휴일 달력 — 근무조 위의 휴일·특근 (docs/35 A19, docs/38 A20, V086(임시 V9107))

**한 줄**: 근무조(1단계)는 "하루의 모양"이고 달력은 "그날 일하는가"다. **주간 쉬는 날 = 정책**, **날짜별 예외(휴일·특근) = 표 하나**, 읽기는 `WorkDayResolver` 옆의 `WorkCalendar` 한 곳이다. APS·CMS·PMS 는 이 서비스만 읽는다(각자 달력 표를 만들지 않는다 — docs/38 A4). 원장(실적 등)은 그대로 — 휴일에 찍힌 실적도 잃지 않고, 작업일 판정(`WorkDayResolver`)도 바꾸지 않는다.

**층과 우선순위** (위가 이긴다)

| # | 층 | 어디 | 예 |
|---|---|---|---|
| 1 | 공장 · 날짜 · **근무조** 예외 | `mst_work_cal`(plant, shift) | 일요일 특근 — 주간조만 WORK |
| 2 | 공장 · 날짜 예외(하루 전체) | `mst_work_cal`(plant, shift 없음) | 공장 하계 휴가 OFF / 대체 근무일 WORK |
| 3 | 회사 · 날짜 예외 | `mst_work_cal`(plant 없음) | 설날·추석·법정 공휴일 OFF |
| 4 | 주간 쉬는 날 | 정책 `WEEKLY_OFF`(공장 값 → 회사 값 → 기본) `NONE`·`SUN`·`SAT_SUN` | 주 5일 공장 = `SAT_SUN` |
| 5 | 기본 | — | 일한다 |

- **기본값 `WEEKLY_OFF = NONE`** — 지금 동작(매일 같은 근무조, APS 1~3단계)과 같다. 회사가 정해야 바뀐다.
- 날짜는 **작업일**(`WorkDayResolver` 의 작업일과 같은 뜻). 자정을 넘는 야간조는 시작한 날(+ day_offset)의 행으로 판정한다.
- 근무조 예외는 공장 행에만(근무조는 공장 소속). 하루 OFF 인데 근무조 WORK 가 있으면 **그 근무조만** 일한다(특근). 하루 WORK(대체 근무일)면 그날 모든 사용 중 근무조가 일한다.
- 회사 층은 공장이 없는 모듈(PMS 프로젝트 일정)이 읽는다 — `isWorkday(null, 날짜)` = 3·4(회사 값)·5.

**표 `mst_work_cal`** (회사별 RLS·감사·보존 MASTER·row_ver)
- 칸: `comp_cd`, `plant_cd`(NULL = 회사 공통), `cal_date`, `shift_cd`(NULL = 하루 전체, 있으면 `plant_cd` 필수), `day_type`(`OFF`·`WORK`), `reason`(필수 — "추석", "하계 휴가", "주말 특근"), `use_yn`, 감사 칸.
- 유일: 사용 중 행 중 (회사, 공장, 날짜, 근무조) 하나 — NULL 칸은 `coalesce(…, '')` 로 부분 유일 인덱스.
- 같은 회사 공장·근무조인지는 트리거(FK 가 RLS 를 무시 — SKILL 규칙), 근무조는 `mst_shift`(공장, 근무조 코드) 참조. 삭제 = `use_yn='N'`(앱 DELETE 회수, 기준정보 규칙).
- 원장이 아니다(계획용 기준정보) — 바꾸면 감사 기록만 남고, 이미 저장된 원장 행은 그대로다.

**읽기 계약 `WorkCalendar`** (`module.master.workforce`, 트랜잭션 안 — 읽기 전용)
```java
boolean isWorkday(String plantCd /* null = 회사 */, LocalDate day);
List<ShiftDay> shiftsOn(String plantCd, LocalDate day);                       // 그날 일하는 사용 중 근무조(시작·끝·휴식·작업일)
Map<LocalDate, DayPlan> range(String plantCd, LocalDate from, LocalDate to); // 기간 한 번에(APS·CMS) — 쿼리 2번, 최대 400일
```
- APS `ShiftCalendar.build` 는 매일 같은 근무조 대신 `range` 의 근무조를 펼치면 된다(APS 세션 몫 — A20). CMS 주기·PMS 지연도 같은 서비스(각 세션 몫).

**화면 `mst-work-cal`** (조회·저장, 근무조 화면 옆 메뉴): 조회 = 공장(또는 "회사 공통") × 연·월 → 달력(주간 쉬는 날 음영, 예외 표시, 그날 일하는 근무조) + 그 달 예외 목록. 오른쪽 폼 = 날짜·근무조(선택)·OFF/WORK·사유 → 저장/사용 중지. 공휴일 일괄 입력(엑셀 올리기)은 이번에 하지 않는다 — 한 해 15일 남짓이라 손 입력으로 충분, 필요하면 같은 표에 `ImportTemplate` 하나.

**결정 — 추천안 그대로 사용자 확인(2026-10-21)**

| # | 결정 | 추천(채택) | 다른 안 |
|---|---|---|---|
| D22 | 주간 쉬는 날 저장 | 정책 ENUM `WEEKLY_OFF`(공장 범위) — 형식 검사·공장 덮어쓰기가 정책 서비스에 이미 있다 | 요일별 표 — 교대 근무 공장의 "격주 토요일" 같은 규칙까지 가면 필요. 지금은 날짜 예외로 처리 |
| D23 | 회사 공통 층 | 둔다(plant NULL) — 공휴일을 공장마다 넣지 않게, PMS 처럼 공장 없는 읽기가 있다 | 공장만 — 공장 수 × 공휴일 입력, PMS 가 "대표 공장" 을 골라야 한다 |
| D24 | 휴일 실적 | 막지 않는다(원장·작업일 판정 불변). 달력은 계획·주기·지연 계산용 | 휴일 실적 경고·차단 — 특근을 달력에 늦게 넣으면 실적이 막힌다 |
| D25 | 기본 `NONE` | 지금 동작 유지 | `SAT_SUN` — 이미 쓰는 APS 계획이 갑자기 바뀐다 |

**4.15 완료 기준**: 층 우선순위(근무조 > 공장 하루 > 회사 > 주간 > 기본) 순수 함수 시험, 야간조 작업일 판정, API(조회·저장·사용 중지·rowVer 409·중복 409·근무조는 공장 행만·없는 근무조·사유 필수), 정책 공장 덮어쓰기, A사로 B사(조회·저장·직접 SQL 다른 회사 공장·근무조), 브라우저 1366·390.

### 4.16 사무 실적 조회 — `prd-result` (docs/25 11.4 후보 1, 사용자 2026-10-21, V090)

**한 줄**: 실적 원장(`prd_result`·`prd_result_defect`)을 **작업일 기준**으로 사무에서 보는 화면. 지금 실적은 POP(내 할 일)·LOT 보고서로만 보인다. 읽기 전용이며, 저장·정정은 그대로 POP 몫이다. 근무조·작업일은 저장 때 `WorkDayResolver` 가 행에 남긴 값(`work_day`·`shift_cd`)을 그대로 쓴다 — 조회에서 다시 계산하지 않는다(근무조 정의가 바뀌어도 과거 행은 그대로라는 원장 규칙).

**조회 조건**(`hb.searchPanel`, 홈 링크 규약의 칸 이름): 작업일 `from`~`to`(필수, 기본 지난 7일, 상한 92일과 정책 `SEARCH_MAX_RANGE_DAYS` 중 작은 값), 공장 `plantCd`, 근무조 `shiftCd`(공장을 고르면 그 공장 사용 중 근무조), 지시 번호 `keyword`, 품목 `itemCd`, 공정 `procCd`, 작업자 `workerCd`, 보기 `view`.

**보기 `view`** (서버 enum 화이트리스트 — SQL 은 `<choose>`, 문자열 치환 없음)

| 값 | 한 줄 = | 칸 |
|---|---|---|
| `DETAIL`(기본) | 원래 실적 한 건 + 정정 여부(정정 시각·사유 — 정정된 줄은 흐리게) | 작업일·근무조·지시·공정·품목·설비·작업자·양품·폐기·재작업·수리·단위·발생 시각·입력(단말)·LOT |
| `WO` | 지시 × 단위 | 지시·품목·단위·건수·양품·폐기·재작업·수리·불량률 |
| `PROC` | 지시 × 공정 × 단위 | + 공정 순번·공정 |
| `WORKER` | 작업자 × 단위 | 작업자·건수·… |
| `SHIFT` | 작업일 × 근무조 × 단위 | 작업일·근무조·건수·… |

- 정정은 언제나 **실적 전체의 역분개**(5b)다. 그래서 줄의 기준은 원래 실적이고, 역분개 행은 따로 세지 않고 "그 실적이 정정됐는가" 로 붙인다(`uq_prd_result_reversal` 로 한 번에). 묶음 보기는 정정된 실적을 빼고 더한다 = **순수량**. 이렇게 해야 정정을 다른 날·다른 단말에서 해도 원래 작업일·근무조·작업자에 붙는다(역분개 행의 작업일로 세면 정정한 날에 음수가 생긴다).
- 단위가 다른 품목은 합치지 않는다(묶음 키에 단위).
- 불량률 = (폐기 + 재작업 + 수리) / (양품 + 폐기 + 재작업 + 수리), 분모 0 이면 빈칸. 서버가 계산해 글자로 보낸다(소수 2자리 %).
- **불량 유형 합계**(오른쪽 표): 같은 조건·정정 안 된 실적의 `prd_result_defect` 를 종류(SCRAP·REWORK·REPAIR) × 불량 유형으로 합한다.
- 미리보기 500행 + 전체 건수 + "잘림". [엑셀]은 지금 보기 그대로 플랫폼 `ExcelExportService`(건수 상한·수식 주입 방지·출력 정보 시트)로 내보내고, 마지막으로 조회한 조건으로만 내보낸다(7c 와 같은 규칙).
- 권한: `prd-result` SEARCH·EXCEL. 작업자는 이름·코드만(배지 해시는 나가지 않는다).

**실행계획**: 기간 조건은 `ix_prd_result_day (comp_cd, work_day, result_id)` 범위 → 지시·공정·작업자는 PK 조인. 묶음은 그 범위 안에서 해시 집계다. 불량 합계는 `prd_result_defect` 의 실적 FK 인덱스로 붙인다(없으면 V090 에 `(comp_cd, result_id)` 추가). 92일 상한이 범위를 묶는다.

**결정 — 사용자 확인(2026-10-21)**. D27 은 처음 문구("상세 = 원래 + 역분개 행")를 구현 때 다듬었고, 다듬은 구현(역분개를 별도 줄이 아니라 원래 줄의 "정정됨(시각·사유)" 표시, 묶음 = 순수량)을 **사용자가 그대로 승인**했다(2026-10-21).

| # | 결정 | 추천 | 다른 안 |
|---|---|---|---|
| D26 | 기간 기준 | **작업일**(`work_day`, 저장 때 고정) — 근무조 집계와 같은 날짜, 야간조가 자정을 넘어도 한 날 | 발생 시각 — 야간조 실적이 두 날로 쪼개진다 |
| D27 | 정정 | 줄 = 원래 실적, 정정은 그 줄의 표시. 묶음 = 정정된 실적 제외(순수량), 원래 작업일·근무조·작업자 기준 | 역분개 행을 그 행의 작업일로 따로 세기 — 정정한 날에 음수가 생기고 근무조 집계가 어긋난다 |
| D28 | 단위 | 묶음 키에 단위 — 다른 단위는 다른 줄 | 기본 단위 환산 — 실적은 이미 지시 단위(= 품목 기본 단위)라 이득이 없다 |
| D29 | 기간 상한 | 92일(ERP 엑셀과 같음) + 정책 `SEARCH_MAX_RANGE_DAYS` | 366일 — 상세 보기에서 행이 너무 많다(묶음 보기만 길게는 후속) |

**4.16 완료 기준**: 보기 5종 × 조건 조합, 순수량(정정 반영)·불량 유형 합계, 엑셀(열·건수·순수량), 형식 오류(보기 코드·기간·상한), 권한(조회만 → 엑셀 403), A사로 B사, 브라우저 1366·390.

### 4.17 수집 ALARM → CMS 알람 원장 연결 (docs/33 11.4 Q2, 마이그레이션 없음 — 구현 2026-10-21, CMS 계약 V093 병합 뒤)

**한 줄**: `EquipEventService` 의 ALARM 분기가 CMS 계약 `io.smarthappy.module.cms.link.AlarmSink`(raise·clear)를 **수집 트랜잭션 안에서** 부른다. 계약·구현은 CMS 몫(C7 계약 → C8 구현 빈), MES 는 호출 줄과 본문 해석만. 빈이 없으면 `AlarmSink.NONE`(지금처럼 받기만). 이벤트 멱등은 MES `sys_inbox` 가 이미 봤다.

**ALARM 본문(메트릭) — 6단계에서 정하지 않았던 칸을 여기서 정한다(CMS 동의 2026-10-21: C8 `alarm_cd` varchar(50)·같은 정규식, `alarm_text` varchar(200))**

| 메트릭 | 값 | 없으면 |
|---|---|---|
| `code` | 장치 알람 코드. 대문자로 정리, 1~50자 `[A-Z0-9][A-Z0-9_.:-]*` | 거부 `ALARM_CODE` |
| `active` | `true` = 발생(raise) / `false` = 해제(clear). Sparkplug 의 불리언 알람 메트릭 모양 | `true`(발생) — 6단계 시험처럼 `code` 만 보내는 장치를 깨지 않는다 |
| `text` | 장치가 보낸 문구(정의 없는 코드일 때 화면에 보임) | null. 200자 넘으면 자른다(문구는 사실 기록이라 거부하지 않음) |

- 형식이 틀리면 다른 유형과 같이 그 이벤트만 거부(배치의 나머지는 받음 — 6단계 규칙). `active` 가 `true`/`false` 가 아니면 `ALARM_ACTIVE`.
- 정의 없는 코드·열린 알람 없는 해제·같은 알람 두 번 발생은 **CMS 가 기록·무시**(계약 주석) — MES 는 거르지 않는다.
- 설비는 이미 이 회사·이 장치 범위로 찾은 `equip_id`, 장치 = 수집 단말 id, `eventId` 그대로.

**완료 기준**: 발생·해제·`active` 생략 = 발생이 계약으로 넘어감(시험용 가짜 `AlarmSink` 빈으로 호출 인자 확인), 형식 오류 거부, 같은 `eventId` 두 번 = 한 번만 호출(sys_inbox), 빈 없음(`NONE`)이면 지금처럼 받기만, A사 장치가 B사 설비 코드로 보내면 설비 못 찾음(호출 없음).

### 4.18 지시별 남은 자재 소요 조회 계약 — `WoMaterialQuery` (APS 5단계, 사용자 2026-10-21, V096 인덱스만)

**한 줄**: APS 가 지시의 자재 필요량을 MES 표 직접 조회 없이 읽는 계약 `io.smarthappy.module.production.link.WoMaterialQuery.remaining(Collection<Long> woIds)`. 구현 빈 `MesWoMaterialQuery`(production 패키지 — 계획 지시 전개에 `WoService.explode` 를 패키지 밖에 열지 않고 그대로 쓰려고), 빈 없는 설치본은 `NONE`(빈 목록), 받는 쪽은 `WoMaterialQuery.of(ObjectProvider)`.

**돌려주는 모양**: 지시마다 `WoNeeds(woId, status, exploded, needs)`, 줄은 `Need(itemId, opSeq, remainingQty, unitCd)` — (품목, 투입 공정 순번) 합계, 수량은 **자품목 기본 단위**, 0 인 줄은 빠짐, `opSeq` null = BOM 줄에 공정 지정 없음. 순서 = wo_id → 품목 → 공정(없음은 맨 뒤).

| 지시 상태 | 남은 소요 |
|---|---|
| 발행·진행 중 | 발행 스냅샷 `prd_wo_bom`(부산물 `BYPRODUCT` 제외) − 이 지시의 LOT 투입 순수량 |
| 계획 | 발행 때와 같은 전개(BOM·계획 수량·계획 시작일, 가상 조립 펼침·환산·스크랩·대체 비율). BOM 없음 = 줄 없음. 전개 실패(환산 없음·가상 조립 BOM 없음 — 발행도 막히는 경우) = `exploded = false`, 줄 없음 |
| 완료·마감·취소, 없는·다른 회사 id | 빠진다(RLS). 중복·null id 는 한 번·무시 |

**투입 빼기 규칙**
- 투입 = `lot_genealogy` CONSUME, 이 지시(`wo_id`), 부모 LOT 품목 = 자재 품목, 부모 LOT 단위(= 품목 기본 단위, LOT 트리거) 로 같은 단위 줄에서만 뺀다.
- **정정**: 역분개 행은 `wo_id`·`wo_proc_id` 를 갖지 않는다(`LotService.reverse`). 정정은 언제나 한 행 전체이므로 **되돌려진 원래 행을 뺀다**(`uq_lot_genealogy_reversal` 로 조인) — `wo_id` 로 합을 내면 역분개가 빠져 투입이 줄지 않던 함정.
- 같은 공정 순번(`wo_proc_id` → `op_seq`) 줄에서 먼저 빼고, 남는 양(공정 없는 투입 포함)은 같은 품목 다른 줄에서 공정 순서대로. 0 아래로 내려가지 않는다(초과 투입은 버림 — APS 에는 "남은 소요 없음" 이 보수적).
- **LOT 비관리 자재**(`lot_ctrl_yn = N`)는 투입 기록이 없어 **전량 남은 소요**(보수적 — 실제보다 많게 잡혀도 자재 부족 경고가 늦지 않다). 차감은 비관리 자재 투입 원장이 생기면(P4 WMS 출고) 그때.

**상한·성능**: 한 번에 500 지시(`MAX_IDS`, 넘으면 `IllegalArgumentException` — 받는 쪽이 나눠 부른다). 조회 3번(머리·스냅샷·투입, `in (...)`) + 계획 지시마다 전개(BOM 줄 조회 — 발행과 같은 비용). 투입 조회는 V096 부분 인덱스 `ix_lot_genealogy_wo (comp_cd, wo_id) WHERE wo_id IS NOT NULL` — 그 전에는 지시 기준 인덱스가 없어 계보 원장 전체를 훑었다. 잠금 없음(계획용 읽기, 다음 조회에서 맞춰진다), `@Transactional(readOnly)` — 회사 범위가 없으면 RLS 로 빈 목록.

| # | 결정 | 고른 안 | 다른 안 |
|---|---|---|---|
| D30 | 비관리 자재 | 전량 남은 소요 | 진행률(실적 ÷ 계획)로 비례 차감 — 실제 투입이 아닌 추정이라 원장 근거가 없다 |
| D31 | 공정이 다른 투입 | 같은 공정 먼저, 남는 양은 공정 순서 | 품목 합계 한 줄 — APS 가 공정별 자재 도착 시점을 볼 수 없다 |
| D32 | 계획 지시 전개 실패 | `exploded = false` 로 알림 | 예외 — 지시 한 건 때문에 500건 조회가 통째로 실패한다 |

**완료 기준(WoMaterialQueryTest 7)**: 투입 전(발행 스냅샷·계획 전개·부산물 빠짐·BOM 없음), 일부 투입(같은 공정 → 공정 순서 → 공정 없는 투입·초과 투입 0), 정정 뒤(되돌린 투입이 다시 남은 소요), 전개 실패·끝난 지시·없는 id·중복·null, 상한, A사로 B사(지시도 투입도 섞이지 않음), `NONE`·`of`.

### 4.19 LOT 보류 근거 원장 `lot_hold`·계약 `LotHoldPort` (QMS Q8 경계, docs/29_P3 12.4 — 사용자 결정 2026-10-21, V109(임시 V9110))

> 상태: **구현**(2026-10-21, V109(임시 V9110) + 포트 + 화면·POP). 사용자가 근거별 해제로 바뀌는 화면 동작을 확인했다.

**문제**: 보류는 `lot_master.hold_yn`·`hold_reason` 칸 하나다. 쓰는 곳은 둘이다: 화면 수동 보류(`LotService.hold`), **POP 실적 정정의 자동 보류**(`PopWorkService` 정정 — 생산 LOT). QMS Q8(NCR·봉쇄)이 같은 LOT 을 함께 잡으면 하나를 풀 때 나머지까지 풀린다.

**한 줄**: 보류 근거를 행으로 남긴다(`lot_hold`, 살아 있는 근거는 (출처, 참조)마다 하나). `hold_yn` = 살아 있는 근거 ≥ 1 은 트리거가 맞춘다. 근거별로 풀고, 마지막 근거가 풀릴 때만 해제된다. `hold_yn` 을 읽는 곳(계보 트리거 V034·V056·V077, 입고 V055, WMS B2 V092 `FOR SHARE`, `WmsLinkMapper`, 홈 카드, `ix_lot_master_hold`)은 그대로 둔다.

**표 `lot_hold`** (회사별 — `comp_cd` + RLS 표준 세트 + 같은 회사 트리거)

| 칸 | 내용 |
|---|---|
| `hold_id`, `comp_cd`, `lot_id` | LOT 은 FK + 같은 회사 |
| `source` | `MANUAL`(화면) · `MES_CORRECT`(POP 실적 정정, ref = `result_id`) · `QMS_NCR` · `QMS_CONTAIN` |
| `ref_id` | 출처의 행 id. MANUAL 은 null. 출처마다 표가 달라 FK 는 걸지 않는다 — MES_CORRECT 는 트리거가 `prd_result` 존재·같은 LOT 을 확인, QMS_* 는 포트를 부르는 쪽 책임(QMS 표가 아직 없음) |
| `reason`, `held_at`, `held_by` | 보류 사유 필수 |
| `released_at`, `released_by`, `release_reason` | 해제 — 셋이 함께 채워지고 사유 필수 |

- **해제는 한 번만**: 가드 트리거 — INSERT 는 자유, UPDATE 는 `released_*` 를 null → 값으로 한 번만, 나머지 칸 고정, DELETE 금지. 선례는 CMS 상태 구간 `end_at` 닫기·`prd_wo_proc` 스냅샷 가드. 해제를 별도 행(역분개식)으로 두지 않는다(D33).
- **살아 있는 근거 하나씩**: `uq_lot_hold_live ON lot_hold (comp_cd, lot_id, source, ref_id) NULLS NOT DISTINCT WHERE released_at IS NULL` — MANUAL 은 LOT 당 하나.

**`lot_master` 동기화·우회 차단**
- `lot_hold` AFTER INSERT/UPDATE 트리거가 **LOT 행을 먼저 잠근 뒤**(동시에 들어온 근거가 요약에서 빠지지 않게) 그 LOT 의 `hold_yn`(살아 있는 근거 ≥ 1)·`hold_reason`(살아 있는 근거 사유를 `held_at` 순으로 ` / ` 로 이은 요약, 500자에서 자름 — 출처는 화면 근거 목록에 따로 보이므로 붙이지 않는다. 근거 하나면 예전과 같은 글자)·`row_ver + 1` 을 맞춘다(`hb_lot_hold_summary`).
- `lot_master` 가드에 추가: `hold_yn`·`hold_reason` 이 바뀌면 살아 있는 근거(요약)와 같아야 하고, 새 LOT 은 보류 없이 만든다(`ck_lot_master_hold_basis`). `lot_hold` 를 거치지 않은 직접 UPDATE 는 거부된다(플래그 없이 일관성 검사). `LotMapper.updateHold` 는 없앴다.

**계약 `io.smarthappy.module.production.link.LotHoldPort`** (구현 = MES 빈, 없으면 `NONE`, `of(ObjectProvider)`)

| 메서드 | 동작 |
|---|---|
| `boolean hold(long lotId, String source, long refId, String reason, String by)` | 새 근거면 true, 같은 근거가 이미 살아 있으면 false(멱등) |
| `boolean release(long lotId, String source, long refId, String reason, String by)` | 해제하면 true, 살아 있는 근거가 없으면 false(멱등) |
| `List<HoldView> holds(long lotId)` / `holds(Collection<Long> lotIds)` | 살아 있는 근거(일괄은 상한 500) |

- `QMS_NCR`·`QMS_CONTAIN` 만 받는다(MANUAL·MES_CORRECT 거부 — MES 안 경로). `by` 를 받는 이유: 요청 밖(`TenantRunner`)에서도 부르게.
- 부르는 쪽 트랜잭션 안(MANDATORY). 다른 회사·없는 LOT 은 예외(빈 값이면 NCR 이 "보류됐다"고 착각한다). 같은 이유로 `NONE`(MES 없는 설치본)의 `hold`·`release` 는 `IllegalStateException` — 조용한 `false` 는 "이미 보류됨" 과 구분되지 않는다. `holds` 만 빈 목록.
- **잠금 순서**: 포트가 `lot_master` FOR UPDATE → `lot_hold`. QMS 는 LOT 다음에 `qms_ncr`(Q8-C). 봉쇄 일괄은 **lot_id 오름차순**으로 부른다(교착 방지, 계약 주석). WMS B2 트리거의 `FOR SHARE` 는 같은 LOT 행 하나라 대기만 생기고 순환은 없다.

**MES 화면·POP**
- LOT 상세: 살아 있는 근거 목록(출처·사유·시각·사람) + **근거별 [해제]**(사유 필수). 화면에서 풀 수 있는 것은 `MANUAL`·`MES_CORRECT` 뿐 — `QMS_*` 는 서비스가 거부(`HOLD_SOURCE`)하고 화면은 "(품질 처분으로 해제)" 로만 보인다. 트리거는 부르는 경로를 모르므로 막지 않는다(직접 SQL 은 앱 계정 권한 문제 — 원장 직접 SQL 과 같은 선). 이렇게 하지 않으면 NCR 처분을 우회한다.
- [보류]는 MANUAL 근거 추가(이미 살아 있으면 숨김·`STATE`). API: `PUT …/lots/{id}/hold` 는 그대로(`Y` = 수동 근거 추가, `N` = 수동 근거 해제 — 기존 호출 호환), 근거 하나 해제는 `POST …/lots/{id}/holds/{holdId}/release`(사유·LOT `rowVer`). 상세 응답 `holds` = 살아 있는 근거.
- POP 실적 정정: `updateHold` 대신 `MES_CORRECT` 근거(ref = 정정한 실적)를 추가한다. 정정 시험은 근거 행·`hold_yn` 둘 다 확인한다.

**기존 보류 이관(같은 마이그레이션)**: V084 선례대로 회사마다 `set_config('app.comp_cd', …, true)` 로 범위를 연다. `hold_yn = 'Y'` LOT 마다 `MANUAL` 근거 1행(사유 = 기존 `hold_reason`, `held_at`·`held_by` = `lot_master.updated_at`·`updated_by`)을 넣은 뒤 `hold_reason` 을 요약 형식으로 다시 맞춘다. 지금은 정정 자동 보류와 수동 보류를 구분할 수 없어 **전부 MANUAL** 로 넣는다(D35). V034 `ck_lot_master_hold` 가 사유 NULL 을 통과시켰으므로(`length(btrim(NULL)) > 0` = NULL) 사유 NULL·빈 사람은 `이관(사유 없음)`·`migration` 으로 채워 이관이 기동을 막지 않게 하고, 이관 뒤 `ck_lot_master_hold_reason`(보류면 사유 필수, NULL 포함)을 `NOT VALID` → `VALIDATE` 로 건다(이관 뒤 보류 LOT 사유는 근거 요약이라 위반 0).

| # | 결정 | 고른 안 | 다른 안 |
|---|---|---|---|
| D33 | 해제 기록 | 같은 행의 `released_*` 를 한 번만 채움(가드) | 해제 행을 따로 추가 — "살아 있음" 판정이 매번 not exists 조인이 되고, 근거는 정정 대상도 아니다 |
| D34 | QMS 근거 해제 | QMS 포트로만 | MES 화면에서도 — NCR 처분 없이 보류가 풀린다 |
| D35 | 기존 보류 이관 | 전부 MANUAL 1행 | 정정 자동 보류를 사유 문구로 추정 — 근거 없는 분류 |
| D36 | `hold_yn` 직접 변경 | 근거 존재와 다르면 거부(가드) | 앱 코드 규칙만 — 다른 모듈·SQL 이 칸을 고치면 원장과 어긋난다 |

**WMS 입고 LOT 취소(WMS 질문, 2026-10-21)**: 원장 역분개 때 LOT 을 "보류 + 사유" 로 두는 규칙이면 출처 하나를 더한다 — `WMS_RCV_CANCEL`(ref = 취소한 입고 원장 행). 다만 근거는 MES 표라 출처 CHECK 를 바꾸는 마이그레이션 + 포트 허용 목록 추가가 필요하고, 해제 경로(누가 풀지)를 WMS 가 먼저 정해야 한다. 그 전까지 WMS 가 보류가 필요하면 담당자가 화면에서 MANUAL 로 건다. 출처를 미리 넣지 않는다(쓰는 곳 없는 값은 CHECK 에 두지 않음).

**완료 기준(착수 뒤)**:
- 근거 2개 중 하나를 풀면 보류가 유지되고, 마지막 근거를 풀면 해제된다.
- 화면에서 QMS 근거 해제는 거부되고, 포트에 MANUAL 출처를 넣어도 거부된다.
- 멱등(같은 근거 두 번 보류·해제).
- 동시 보류는 잠금으로 줄을 선다(교착 없음, 일괄은 lot_id 순).
- 직접 SQL `hold_yn` 변경·해제 두 번·DELETE 는 거부된다.
- POP 정정 자동 보류가 근거로 남는다.
- 이관 전후 보류 건수가 같고 사유 요약이 맞다.
- **A사로 B사**(포트·화면·직접 SQL), 빈 없음 `NONE`.

### 4.20 WMS B5 연결 MES 몫 — ① POP 오프라인 대기열 거부 보존 (docs/30 12.4, 사용자 확정 2026-10-22)

> B5(투입 차감·LOT 변환 → 재고 원장)의 MES 몫은 네 가지다: ① POP 대기열 거부 보존(이 절, 단독 PR — **공장 스위치 `WMS_STOCK_LINK` 를 켜기 위한 전제**) ② `PopWorkService.result` 가 칸 오류 없는 `BusinessException`(409 `INSUFFICIENT_STOCK`·`PERIOD_CLOSED`)을 그대로 다시 던짐 ③ 정정 앞 투입 LOT id 순 잠금 ④ `LotService.edge()`·`reverse()` 뒤 `LotStockLink.onEdge/onReverse`(계약은 WMS PR 이 먼저). ②~④ 는 다음 PR.

**문제**: `hb.pop.queue.flush`(다시 보내기)가 5xx·끊김이 아닌 오류를 모두 **지웠다**("검증 오류는 버린다"). 재전송은 같은 본문이라 거부되면 늘 거부되는 검증 오류뿐이라고 본 것인데, B5 의 재고 부족·마감(409)은 **상태에 따라 달라지는 거부**다. 오프라인 중 확정한 실적이 입고 전에 재전송되면 409 → 현장 실적이 작업자 모르게 사라진다.

**바뀐 것**(`hb.pop.js` — 대기열 본체를 `makeQueue(io)` 로 분리해 node 단위 시험)

| 경우 | 전 | 후 |
|---|---|---|
| 바로 보낼 때(`send`) 4xx | 대기열에서 빼고 화면 오류 | 그대로(작업자가 화면에서 본다) |
| 다시 보낼 때(`flush`) 끊김·5xx | 남기고 멈춤(순서 유지) | 그대로. **401(단말 세션 만료)도 다시 보낼 오류**로(전에는 지웠다) |
| 다시 보낼 때 그 밖 4xx(409·400·403·404) | **지움** | **"거부됨" 으로 남김**(사유 = 서버 메시지·칸 오류), 다음 건은 계속 보냄. 거부된 건은 자동 재전송하지 않음(같은 거부 되풀이 안 함) |
| 거부된 건 | — | 머리의 "보내지 못한 실적 n건"(빨강, 48px) → 목록(확정 시각·수량·투입 LOT·사유) → [다시 보내기](같은 `clientReqId` — 서버 멱등) / [버리기](확인) |

| # | 결정 | 고른 안 | 다른 안 |
|---|---|---|---|
| D37 | 재전송 4xx | 모두 남기고 작업자가 고름 | 409 만 남김 — 400·404(공정 끝남 등)도 현장 실적이 사라지는 건 같다 |

**완료 기준(B5 스위치 전제)**: 오프라인 확정 → 재고 없음(409) 재전송 → 대기열에 남음·사유 보임 → 다시 보내기(또 거부면 사유 갱신, 받으면 사라짐) → 버리기는 확인 뒤에만. 단위 시험 5(node) + 브라우저(1280·800·390).

**② 칸 오류 없는 거부는 그대로**: `PopWorkService.result` 의 투입 루프가 `consume` 의 `BusinessException` 을 모두 투입 행 칸 오류(`lotNo` `STATE`)로 바꿔 원인(WMS 409 `INSUFFICIENT_STOCK`·`PERIOD_CLOSED`)을 지웠다 → 칸 오류가 없는 예외는 그대로 다시 던진다(잔량 초과·보류 등 칸 오류는 지금처럼 행 칸 오류). POP 은 409 메시지를 그대로 보이고, 오프라인 재전송이면 ① 의 "거부됨" 으로 남는다.

**③ 정정 잠금 순서**: 정정이 산출 LOT → 투입 계보 id 순으로 투입 LOT 을 하나씩 잠갔다(계보 가드 트리거가 부모 LOT FOR UPDATE). 투입 순서가 엇갈린 두 실적을 동시에 정정하면 서로 기다릴 수 있었다 → 정정 앞에 **투입 자재 LOT + 산출 LOT 을 LOT id 순으로 한꺼번에** 잠근다(`lockForConsume`, 실적 확정과 같은 규칙). B5 에서 정정 뒤 WMS 가 기간 → inv_stock 을 잡으므로 MES 쪽 순서가 먼저 고정돼야 한다.

**④ LotStockLink 훅**(WMS 계약 `module.wms.link.LotStockLink`, main #164): `LotService` 가 계보 행을 만드는 곳은 `edge()` 하나(투입·분할·병합·라벨 바꿈·재작업·수리)와 `reverse()`(역분개)뿐 — 각각 INSERT 직후 `LotStockLink.of(provider).onEdge(gen)`·`onReverse(rev)` 한 줄. 같은 트랜잭션이라 WMS 의 409(`INSUFFICIENT_STOCK`·`PERIOD_CLOSED`)는 칸 오류 없는 예외로 올라가 계보·자식 LOT·실적이 함께 되돌아간다(② 와 이어짐). 빈이 없으면 `NONE`, 공장 정책 `WMS_STOCK_LINK` = N 이면 WMS 가 아무것도 하지 않는다. 잠금은 MES 가 잡은 LOT(·공정·ECO) 뒤에 WMS 가 기간 → inv_stock(docs/30 11.6).

### 4.21 착수 게이트 확장점 `StartGate` + LOT 조회 계약 `LotLookup` (QMS Q9 경계, docs/29_P3 12.9 — MES 합의 2026-10-22, 임시 V9112)

**한 줄**: POP 착수(START)에서 자격 게이트(`QualGate`) 바로 다음·이벤트 기록 전에, 다른 모듈(QMS)이 붙인 `StartGate` 빈들을 `@Order` 순으로 부르고 **가장 나쁜 mode**(BLOCK > WARN > OK)를 쓴다. MES 는 계약·호출만, 규칙은 구현 모듈 몫.

**계약** `io.smarthappy.module.production.link.StartGate` — `key()`·`check(StartContext)` → `GateVerdict(mode, reasons)`, `GateReason(code, messageKey, args, refType, refId)`. `StartContext` = 공장·지시·지시 공정·공정·품목·설비·작업자·단말·작업일·지금·`preview`. 구현은 읽기만·잠금 없음(공정 행 FOR UPDATE 뒤), MES 표는 계약으로만 읽는다(→ `LotLookup`).

| 경우 | 동작 |
|---|---|
| 게이트 빈 없음·모두 OK | 지금과 같다 — 자격만 걸리면 `QUAL_BLOCKED`/`QUAL_WARN_ACK` |
| 게이트 이유가 하나라도 | `START_GATE_BLOCKED`(확인해도 거부) / `START_GATE_WARN_ACK`(자격 경고와 **같은 한 번의 확인**) — 문구 = 자격 부족 + 게이트 이유(`messageKey` 를 요청 로캘로, 키는 구현 모듈 ko/en 에) |
| WARN 확인 뒤 착수 | 이벤트 `gate_mode = WARN` + **`gate_detail`**(V120(임시 V9112), 500자) = `출처:코드(참조)` 나열(예 `QUAL:WELD, QMS:QMS_FIRST_PENDING(LOT 12)`) — 문구가 아니라 코드라 로캘·문구가 바뀌어도 근거가 남는다. OK 착수·그 밖 이벤트는 NULL(CHECK). BLOCK 은 지금처럼 이벤트가 남지 않는다 |
| 빈 예외·null·모르는 mode | 빈마다 **세이브포인트**(`PROPAGATION_NESTED`)로 되돌린 뒤 이유 `GATE_ERROR` + 정책 `START_GATE_ON_ERROR`(회사, 기본 BLOCK, WARN 선택). 세이브포인트가 없으면 빈의 SQL 오류가 착수 트랜잭션을 aborted 로 만들어 500 이 된다(시험으로 확인) |
| 미리보기 | **공정 화면만**(`view`, 대기·준비 공정, `preview = true`) — `ProcView.startGate(mode, reasons)`, POP 화면은 자격 문구 뒤에 이어 붙이고 더 나쁜 색. 내 할 일 목록에는 부르지 않는다(N × 빈 조회) |
| 같은 요청 id 재전송 | 다시 판정하지 않고 처음 응답(지금 규칙) |

**계약** `io.smarthappy.module.production.link.LotLookup`(R-2, PMS PM7): `lots(ids)` → `LotView(lotId, lotNo, itemId, itemCd, itemNm, woId, occurredAt)`(lot_id 순, 상한 500) · `byNo(lotNo)` → `Optional`. **예외 없이 빈 값** — PMS 가 `LotService.byNo`(404)를 부르고 rollback-only 를 피하려 REQUIRES_NEW 를 쓰던 것을 대신한다. 읽기만, 잠금 없음, RLS.

| # | 결정 | 고른 안 | 다른 안 |
|---|---|---|---|
| D38 | 게이트 빈 오류 | 세이브포인트 + 정책(기본 BLOCK) | 그대로 실패(500) — 품질 빈 결함 하나가 공장 착수를 원인 모를 오류로 막는다 |
| D39 | 경고 근거 기록 | 코드 나열 `gate_detail` | 문구 저장 — 로캘·문구 변경에 근거가 흔들린다 |
| D40 | 오류 코드 | 게이트 이유가 있을 때만 새 코드 | 늘 새 코드 — 자격만 쓰는 설치본·기존 시험·화면이 바뀐다 |

### 4.22 영업 출하 게이트 `OrderShipGate` (WMS B6 경계, docs/30 12.5 — MES 보완 확정 2026-10-22, V123(임시 V9113))

**한 줄**: WMS 출하(`SHIP` 원장)가 같은 트랜잭션에서 `OrderShipGate.ship(List<ShipQty(orderLineId, qty)>)` 를 한 번 부르면, 영업이 수주 행의 **출하 수량 투영 `shipped_qty`** 를 올리거나(양수) 내리고(음수 = 출하 취소) 행·머리 상태를 따라 바꾼다. 원본은 WMS 원장, 영업 칸은 투영이다.

**계약** `io.smarthappy.module.sales.link.OrderShipGate` — `ship(List<ShipQty>)`, `NONE`(영업 없는 설치본은 아무 일 없음), `of(ObjectProvider)`. 구현 `SalesOrderShipGate`(`PROPAGATION_MANDATORY` — 트랜잭션 밖 호출은 예외). 수량은 품목 기본 단위(환산 없음), 게이트 멱등 없음(멱등은 WMS 출하 문서 몫).

| 경우 | 동작 |
|---|---|
| 잠금 | 같은 행은 합친 뒤 머리 **order_id 순 `FOR UPDATE`** → 행 **line_id 순 `FOR UPDATE`**(기존 영업 흐름의 머리 → 행과 같은 순서 — 엇갈린 입력 순서 동시 출하에서 교착 없음, 시험) |
| 출하(양수) | 열린 행, 또는 출하로 자동 마감된 행(초과율 안)만. 취소 행·사람이 닫은 행 → 409 **`ORDER_LINE_CLOSED`**(수주번호-행번호). 누적이 `수량 × (1 + SAL_OVERSHIP_PCT/100)` 를 넘으면 409 **`OVER_SHIP`**(남은 허용 수량). 칸 오류 없는 예외 → WMS 는 그대로 던져 출하 전체 롤백 |
| 다 나감 | 누적 ≥ 수량이면 `DONE` + **`done_by_ship_yn = Y`**(PARTIAL 상태 없음). 머리는 기존 계산(`refreshOrderStatus`) 그대로 CLOSED |
| 출하 취소(음수) | 0 아래는 IAE. 자동 마감 행이 미결이 되면 `OPEN`·N 으로 다시 열고 머리 CLOSED → OPEN. **사람이 닫은 행(DONE·N)은 닫힌 채 수량만** 되돌린다(사람 판단 우선) |
| 정책 | `SAL_OVERSHIP_PCT`(회사, INT 0~100, 기본 0) |
| 영업 규칙 | 열린 행 수량은 **출하 수량 아래로 못 줄인다**(`BELOW_SHIPPED`), 출하가 있는 행·그런 행이 있는 수주는 **취소 못 한다**(`SHIPPED`, 출하 취소가 먼저). 사람 마감(부분 출하 뒤 짧게 닫기)은 된다. `shipped_qty` 는 화면·API 입력에 없다(보기 열만). 모든 변경은 `row_ver` + 1 |
| DB 가드(V123, 임시 V9113) | 행 트리거: 끝난 행은 (1) DONE 행의 출하 수량만 바꾸기, (2) 자동 마감 행을 미결로 다시 열기만 허용 / `ck_sal_order_line_qty_shipped` / `ck_sal_order_line_cancel_shipped` / 새 행은 미출하로. 머리 트리거: CLOSED → OPEN 은 열린 행이 있고 다른 칸이 그대로일 때만. CHECK `shipped_qty ≥ 0`, `done_by_ship_yn = Y` 는 DONE 만 |
| 지우기 | 수주 머리·행 DELETE 는 앱 계정 권한이 없다(42501, 기존) — 투영이 있는 행을 지워 원장과 어긋나게 할 수 없음(시험) |
| 기본 단위 잠금 | V091 `hb_check_item_unit_in_use` 에 **수주 행이 없었다**(확인) → V123(임시 V9113) 이 더함(`ix_sal_order_line_item`). 수주·출하 수량이 기본 단위라 단위가 바뀌면 투영이 틀린다 |
| 대사 | 원본과 투영의 일치는 WMS 읽기 계약 `ShipQuery.shippedByLine(lineIds)` 가 생긴 뒤 MES 대사 시험·화면 경고로(이 PR 밖) |

| # | 결정 | 고른 안 | 다른 안 |
|---|---|---|---|
| D41 | 마감 출처 | `done_by_ship_yn` 칸 — 출하 취소가 자동 마감만 다시 연다 | 상태 PARTIAL 추가(영업 화면·집계·가져오기 전부 바뀜) / 취소 때 늘 다시 열기(사람이 짧게 닫은 행이 살아난다) |
| D42 | 초과 출하 | 회사 정책 % (기본 0 = 막음) | 행마다 허용량 칸(입력 부담) / 늘 허용(투영이 수량을 넘어 미결 계산이 음수) |
| D43 | 머리 다시 열기 | 게이트가 직접(가드는 열린 행이 있을 때만 허용) | `refreshOrderStatus` 를 CLOSED 에도 — 사람이 닫은 수주까지 계산이 건드린다 |

### 4.23 수주 행 읽기 계약 `OrderLineQuery` (WMS B6a, docs/30 12.7.5 — 사용자 결정 2026-10-22, 마이그레이션 없음)

**한 줄**: WMS B6a(#211)가 출하 지시 화면에서 `sal_order`·`sal_order_line` 을 직접 읽던 것을, 영업(MES)이 내주는 읽기 계약으로 바꾼다. 쓰기는 지금처럼 `OrderShipGate`(4.22) 만.

**계약** `io.smarthappy.module.sales.link.OrderLineQuery` — `NONE`(영업 없는 설치본), `of(ObjectProvider)`. 구현 `SalesOrderLineQuery`(`readOnly`), 잠금 없음, 회사는 RLS, 예외 없이 빈 값(LotLookup 4.21 과 같은 규칙).

| 메서드 | 돌려주는 것 |
|---|---|
| `openLines(custId)` | 그 거래처의 **OPEN 행만**, 납기 → 수주번호 → 행번호 순. 없는·다른 회사 거래처 = 빈 목록 |
| `lines(lineIds)` | 상태 무관(끝난 행도), line_id 순, null·중복 뺌, **상한 500**(넘으면 IAE), 없는·다른 회사 id 는 빠짐 |
| `OrderLineView` | 행·수주 id, 수주번호·행번호, 거래처 id·고객 발주번호, 품목 id·코드·이름·단위, 납기, 수량·출하 수량(기본 단위), 상태, `doneByShipYn` |

- **WMS 몫**: "다른 열린 출하 지시에 잡힌 수량"은 WMS 표(`wms_ship_line`)라 계약 밖 — WMS 가 `openLines` 결과에서 빼고 남은 수량 > 0 을 거른다. 지시 상세는 WMS 행을 읽은 뒤 `lines(orderLineIds)` 로 붙인다(SQL join → 자바 병합).
- **인덱스**: 열린 행은 부분 인덱스 `ix_sal_order_line_due`(status = 'OPEN')로 훑고 거래처로 거른다. `sal_order` 에 거래처 인덱스가 없다 — 열린 행이 많아져 느려지면 `(comp_cd, cust_id) WHERE status = 'OPEN'` 추가(후속, 이 PR 은 마이그레이션 없음).

| # | 결정 | 고른 안 | 다른 안 |
|---|---|---|---|
| D44 | WMS 가 잡아 둔 수량 | 계약 밖(WMS 가 뺀다) | 계약이 `wms_ship_line` 을 읽기 — 영업이 WMS 표를 알게 된다(방향 역전) |
| D45 | 조회 단위 | 거래처의 열린 행 + id 목록 2개 | 범용 검색(필터 여러 개) — 쓰는 곳이 둘뿐, 필요해지면 메서드 추가 |

### 4.24 공휴일 엑셀 올리기 `MST_WORK_CAL` (11.4a 1번, 2026-10-22 — 마이그레이션 없음)

**한 줄**: 공장 휴일 달력(4.15) 화면에 "엑셀 올리기"를 붙인다. 공통 가져오기 엔진(P1 2단계)의 템플릿 하나 — 열 = 날짜·공장 코드·근무조·구분·사유.

| 규칙 | 내용 |
|---|---|
| 키 | 날짜 + 공장(빈 칸 = 회사 공통) + 근무조(빈 칸 = 하루 전체) — 사용 중 행 유일(`uq_mst_work_cal`)과 같다 |
| 검증 | 화면 저장(`WorkCalService.create`)과 같은 규칙을 **행마다 모아서**: 날짜 ISO·2000~2999, 공장 있음·사용 중, 근무조는 공장 필수·그 공장 소속·사용 중, 구분 OFF/WORK, 사유 필수·200자. 코드는 대문자로 정리 |
| 변경 | 같은 키가 있으면 구분·사유만(대상은 키라 고정 — 화면과 같다). 사용 중지한 키는 "있는 키"가 아니라 새 행 |
| 사용 중지 | 엑셀로는 하지 않는다(화면에서) — 실수로 휴일이 사라지는 것을 막는다 |
| 권한 | 템플릿·미리보기 = 조회, 올리기·실행 = 저장(엔진 규칙) |

| # | 결정 | 고른 안 | 다른 안 |
|---|---|---|---|
| D46 | 검증 위치 | 템플릿 안에 화면과 같은 규칙(오류를 모으기 위해) — 서비스 `create` 는 첫 오류에서 멈추는 곳이 있다(공장) | 서비스를 "모으는" 검증으로 바꾸기 — 화면 오류 목록이 바뀌어 기존 시험·화면 회귀 |
| D47 | 엑셀 사용 중지 | 하지 않음 | `useYn` 열 — 실수 한 칸이 회사 휴일을 지운다 |
### 4.25 같은 공급사 LOT 추가 입고 = 새 내부 LOT (docs/29 결정 #43, 그룹 A 2번 — 임시 V9114)

**한 줄**: V034 의 "공급사·품목별 공급사 LOT 은 한 번" 유일 인덱스를 지운다. 입고할 때마다 **새 내부 LOT** 을 만들고 공급사 LOT 번호는 그 LOT 의 속성으로 둔다.

**왜**: WMS R3b(ASN 입고) 시험에서, 같은 ASN 행의 나머지를 같은 공급사 LOT 으로 다시 받으면 `supplierLotNo DUPLICATE` 로 거부되는 것이 드러났다. 현장에서는 한 공급사 LOT(코일·로트 라벨)이 여러 번 나눠 들어온다(분할 납품·ASN 행 나눠 받기). P2 4단계에서 유일로 둔 이유("같은 라벨을 두 번 등록하지 않게")는 **입고 실수 방지**였지만, 그것을 DB 유일로 막으면 정상 흐름(나눠 받기)까지 막는다.

| 무엇 | 어떻게 |
|---|---|
| 스키마(V135, 임시 V9114) | `uq_lot_master_supplier_lot` DROP → 같은 열(회사·공급사·품목·공급사 LOT, 공급사 LOT 있는 행만)로 **일반 인덱스** `ix_lot_master_supplier_lot`. V034 는 고치지 않는다(적용된 파일) |
| `LotService.receive` | `DuplicateKeyException → supplierLotNo DUPLICATE` 처리 삭제. 이 INSERT 에서 남는 유일 위반은 LOT 번호(`uq_lot_master_no`)뿐이고 채번 결함이라 칸 오류로 숨기지 않는다(500) |
| 리콜·조회 | 공급사 LOT 으로 찾으면(LOT 화면 검색어·역추적 보고서) 관련 내부 LOT 이 모두 나온다 — 각 LOT 의 만든 양·잔량·계보는 따로 |
| WMS | 입고 화면·ASN 입고는 코드 변경 없음 — LOT 서비스가 더는 거부하지 않을 뿐(시험만 바꿈) |

| # | 결정 | 고른 안 | 버린 안과 이유 |
|---|---|---|---|
| D48 | 같은 공급사 LOT 의 두 번째 입고 | **새 내부 LOT**(결정 #43) | ① 기존 LOT 에 수량 더하기 — `qty_created` 는 고정 칸(V034 가드)이라 바꾸려면 "추가 입고" 원장 유형이 새로 필요하고, 재고 원장(WMS) 대사·라벨·계보(어느 입고분이 어디로 갔나)가 한 LOT 안에서 섞인다. ② 경고만 띄우고 사람이 확인 — 화면마다(LOT 등록·WMS 입고·ASN) 확인 흐름이 생기고, 나눠 받기는 정상이라 경고가 매번 뜬다(무시하는 습관). ③ 유일 키에 입고 순번 추가 — 사람이 정하는 값이 아니라 의미 없는 칸 |
| D49 | 지운 유일의 대신 | 같은 열 **일반 인덱스** | 인덱스 없음 — 리콜 조회(공급사·품목·공급사 LOT)가 LOT 전체를 훑는다 |
| D50 | 남은 유일 위반 | 칸 오류로 바꾸지 않음(500) | `supplierLotNo DUPLICATE` 그대로 — 이제 사용자가 고칠 수 있는 원인이 아닌데 사용자 오류처럼 보인다 |

- **스터디 W1 사례**: 구매 입고 워크스루(#237)의 "같은 공급사 LOT 을 나눠 받으면?" 질문이 이 결정의 현장 근거로 읽힌다.

### 4.26 작업지시 엑셀 올리기 `PRD_WO` (11.4a 3번, 2026-10-22 — 마이그레이션 없음)

**한 줄**: 작업지시 관리 화면에 "엑셀 올리기". 열 = 지시 번호·공장·품목·수량·계획 시작/완료·우선·비고. 지시 번호를 비우면 새 지시(채번), 쓰면 그 지시를 고친다.

| 규칙 | 내용 |
|---|---|
| 검증 | 화면 저장과 **같은 함수** `WoService.validate` — 이 함수는 칸 오류를 모두 모은 뒤 던지므로 템플릿이 받아 행 오류로 옮긴다(4.24 의 D46 과 달리 따로 쓸 필요가 없다) |
| 새 지시 | 라우팅·BOM = 계획 시작일에 유효한 **기본 확정본**(화면에서 고르지 않았을 때와 같다). 라우팅이 없으면 `NO_ROUTING` |
| 고치기 | **계획(PLANNED) 지시만**. 빈 칸은 지금 값, 라우팅·BOM·수주 연결은 **지금 것을 그대로 넘긴다**(넘기지 않으면 서비스가 기본 리비전을 다시 골라 조용히 바뀐다). 공장·품목은 `FIXED` |
| 발행·취소된 지시 | 행 오류 `STATE` — 현장에 나간 지시는 엑셀로 바꾸지 않는다(일정·우선순위는 화면에서) |
| 미리보기 뒤 화면에서 발행 | 실행 때 다시 읽어 PLANNED 가 아니면 갱신 0 → 엔진이 "그 사이 바뀜" |
| 하지 않은 것 | 수주 행 연결·라우팅/BOM 리비전 고르기·발행·취소 — 화면에서(사람 판단) |

| # | 결정 | 고른 안 | 다른 안 |
|---|---|---|---|
| D51 | 고치는 지시의 라우팅·BOM | 지금 것 그대로 넘김 | 비워 두기 — 서비스가 기본 리비전을 다시 골라, 수량만 고친 행이 라우팅까지 바꾼다(조용히 틀림) |
| D52 | 발행된 지시 | 엑셀로는 거부(`STATE`) | 화면처럼 일정·우선만 허용 — 한 파일에서 행마다 바뀌는 칸이 달라 결과를 예측하기 어렵다 |

### 4.27 수집 COUNT → POP 실적 자동 제안 (11.4a 4번, 2026-10-22 — V139·임시 V9115)

**한 줄**: 6단계는 COUNT 를 받기만 했다. 이제 증가분을 원장 `cms_equip_count` 에 쌓고, POP 공정 화면이 "설비 수집 {since} 이후: 양품 n · 폐기 m" 과 **[수집 값 쓰기]** 를 보인다. 확정은 사람이 한다(4.1, 11 D1).

| 무엇 | 어떻게 |
|---|---|
| 원장(V139, 임시 V9115) | `cms_equip_count`(설비·`GOOD`/`SCRAP`·증가분·occurred_at·recorded_at·entry_method AUTO·event_id·장치) — 원장 규약(역분개 행만, UPDATE/DELETE 차단), RLS, 설비·장치는 같은 회사(트리거), 인덱스 (회사·설비·시각) |
| 수신 | 메트릭 `goodCount`·`scrapCount`(그리고 6단계부터 쓰던 `count` = goodCount) — 0 이상 숫자. 셋 다 없거나 음수·형식 오류는 `METRIC` 거부. 다른 이름 메트릭은 무시 |
| 제안 | 공정이 진행(RUN·PAUSE)·설비 배정·**그 설비에 진행 중 공정이 이것 하나**일 때만. since = 그 공정의 마지막 착수와 마지막(역분개 안 된) 실적 중 늦은 것. 제안 = since 뒤 ~ 지금까지 증가분 합(역분개 행 포함) |
| 확정 | [수집 값 쓰기] 가 양품·폐기 칸에 넣기만(폐기는 불량 유형을 골라야 확정). 그대로 확정하면 `fromCount: true` → `prd_result.entry_method = AUTO`, 칸을 고치면 KEY. 서버는 값을 바꾸지 않는다(사람이 확정한 값 그대로) |
| 정정 | 실적을 정정(역분개)하면 since 가 앞 실적으로 돌아가 그 구간의 COUNT 가 다시 제안된다 |
| 역분개 행 검사(V140·임시 V9116, 2026-10-22 후속) | 트리거가 원 행을 확인: 같은 회사(RLS — 다른 회사 행은 안 보인다)·원 행이 역분개 행이 아님·같은 설비·같은 메트릭·qty = −원 qty, 어기면 `ck_cms_equip_count_reversal_match`. 한 원 행에 역분개 하나는 V139 의 유일 인덱스. V139 CHECK 는 모양(CORRECT·qty ≤ 0·사유)만 봤다 |

| # | 결정 | 고른 안(상위 판단: 추천안대로) | 다른 안 |
|---|---|---|---|
| D53 | COUNT 값의 뜻 | **증가분**(그 이벤트 동안 늘어난 수) — 누적 카운터의 리셋·되감김은 수집기 몫 | 누적값 + MES 가 차이 계산 — 설비 리셋·카운터 넘침·수집기 재시작을 MES 가 설비마다 알아야 한다 |
| D54 | 설비 하나에 진행 공정 여럿 | 제안 안 함 | 나눠 주기(시간·비율) — 어느 공정 몫인지 근거가 없어 조용히 틀린다 |
| D55 | 제안을 칸에 넣는 시점 | 사람이 [수집 값 쓰기] 를 누를 때 | 화면을 열면 자동으로 채움 — 확정 3연타 습관 때문에 확인 없이 들어간다 |
| D56 | 이름 `count` | goodCount 와 같게 받는다 | 거부 — 6단계 가짜 수집기·기존 시험·설치본 수집기가 모두 이 이름을 보내고 있다 |

- **하지 않은 것**: COUNT 정정 화면(역분개 행은 원장이 받지만 입력 화면은 없다), 사이클·`units_per_cycle` 환산(카운터 = 개수로 본다), CMS KPI(C9 성능)가 이 원장을 읽는 것 — CMS 세션 몫.
- **표 주인 = CMS**(docs/33 14장). CMS C9 성능 KPI 가 수집 생산 수를 쓰게 되면 조회 계약 `module.cms.link.EquipCountQuery`(설비·기간 → GOOD/SCRAP 합)를 CMS 가 만든다 — 위치 메모만(상위 판단 2026-10-22).

### 4.28 UI 단계 2 — POP 표시 규칙 (docs/40 10.1 단계 2·7장 P2, 결정 V-1·V-4, 2026-10-22 — 마이그레이션 없음)

| 무엇 | 어떻게 |
|---|---|
| 표면 | `body.hb-pop[data-surface="pop"]`(작업장 홈·단말 활성화) → 바탕 `--hb-bg-muted`(밝은 회색), 카드는 그대로 `--hb-bg-surface`(흰색). 의미 토큰만 |
| 결과 배너 | `#feedback` 자리를 늘 같은 높이로(비어도 접지 않음) — 나타나고 사라질 때 아래 버튼이 움직이지 않는다(누르려던 자리에 다른 버튼이 오지 않게) |
| 위험 동작 | 종료·정정·버리기 = `hb-pop-danger`(외곽선 위험 톤, 채우지 않음) + 주동작과 떨어진 줄 끝(`margin-left: auto`) |
| 상태 | 할 일 카드·공정 화면의 공정 상태 = 공통 배지 `hb.status`(부록 A.3): 대기·준비 neutral ○, **작업 중 normal ● — 색 없음**(V-1 현장 화면: 정상은 색을 쓰지 않는다), 일시 정지 warning ▲. 옛 `.hb-pop-status`(작업 중 = 초록)는 지움 |
| 할 일 카드 제목 | 지시 번호 · 수량(현장이 지시표와 맞춰 보는 값), 공정·품목·설비는 아래 줄 |
| CSS | 배지 규칙은 `page.css` 공통 그대로 — POP 도 `page.css` 를 `pop.css` 앞에 싣는다(배지 규칙을 복사하지 않는다). `page.css` 의 전역 규칙은 `* { box-sizing }` 하나뿐이고 POP 은 화면 넘침·48px 미만 버튼 없음으로 확인 |

- 공정 상태 이름(QA 2회차 QU-3, 2026-10-23): POP·지시 공정 표의 `js.pop.st.*` 를 공통코드 `WO_PROC_STATUS` 이름과 같게 — 작업 중·일시 정지(옛 `진행`·`멈춤`). 짧은 이름을 따로 두지 않는다: "멈춤" 은 같은 화면 [멈춤] 버튼(동작)과 겹치고, 사무 지시 화면도 같은 키를 써서 같은 상태가 화면마다 다른 이름이 된다. 영어는 이미 같았다
- 설비 "가동" 색(V-1): POP 은 지금 설비 상태 구간을 보이지 않는다(공정 상태만). 설비 상태를 POP 에 보이게 되면 `--hb-equip-field-*` + 글자.

### 4.29 UI 단계 3 ②~⑥ — MES 목록 화면 틀 (docs/40 10.1 단계 3, 결정 #44·#45·#47, 2026-10-22 — 마이그레이션 없음)

대상: production(지시·LOT·입고·단말) · sales(수주·수요 예측) · 기준정보 중 MES 몫(근무조·작업자·자격·공휴일 달력) 10화면. ① 표 → `hb.grid` 는 다음 PR(3~4화면씩).

| 무엇 | 어떻게 |
|---|---|
| 공용 도우미 | `js/views/mes/mes-ui.js`(`window.mesUi`, CMS `cms-ui.js` 와 같은 모양) — `leaveOk()`(미저장 이동 확인), `ask(제목키, 문장, 확인키, 위험)`, `reason(…)`(사유 필수·500자), `badge(종류, 코드, 글자, title)`. 타입은 `types/mes-ui.d.ts` |
| ⑥ confirm/prompt | 모두 `hb.dialog`(화면 안) — 미저장 이동은 Promise 로 바꾸고 부르는 곳(행 고르기·신규·조회 `onSearch`)을 `.then` 으로. 공장·월 같은 선택 칸 변경을 취소하면 **보이는 값으로 되돌린다**(목록과 칸이 어긋나지 않게). 사유 입력은 서버가 필수인 곳만 `required`(병합 사유만 선택). 위험 확인은 기본 포커스 = 취소 |
| ⑤ 상태 | `hb.status` 배지(부록 A.3). 톤 표 아래 |
| ④ 위험 버튼 | `hb-btn-danger-outline` + 저장·작업 줄과 **떨어진 줄**(지시 취소·LOT 보류·수주 취소·단말 다시 발급/폐기·작업자 배지/PIN 지우기·달력 사용 중지). 표 행 안 버튼(입고 정정·수주 행 취소·자격 취소)은 행 끝 칸 그대로 class 만. 떨어진 줄은 버튼이 숨으면 **줄도 같이 숨긴다**(빈 줄 여백) |
| ②③ 조회 틀·도구줄 | 조회 조건이 있는 화면(지시·LOT·입고·수주·작업자)은 이미 `hb-search` + [신규][엑셀 올리기] 순서 — 바꾼 것 없음 |

톤(부록 A.3 — 정상 흐름은 색 없음, 끝난 것 success, 확인 필요 warning, 꺼진 것 neutral):

| 종류 | 코드 → 톤 |
|---|---|
| 지시 `wo` | 계획·발행 neutral, 진행 normal, 완료·마감 success, 취소 neutral |
| 공정 `proc` | 대기·준비 neutral, 작업 중 normal, 일시 정지 warning, 완료 success |
| 수주 `order`·행 `line` | 진행 normal, 마감 success, 취소 neutral(행 취소 사유 = title) |
| LOT 보류 `hold` | 보류 warning(보류 아닌 상태는 글자 그대로) |
| 자격 `qual` | 유효 normal, 시작 전 info, 만료 warning, 취소·없음 neutral |
| 사용 `use` | 사용 normal, 안 씀 neutral |
| 단말(직접 `hb.status`) | 사용 중 normal, 등록 대기 info, 코드 만료·키 없음 warning, 폐기 neutral |

| # | 정한 것 | 고른 것 | 버린 것 · 이유 |
|---|---|---|---|
| D57 | 조회 조건이 없는 화면(단말·근무조·자격·공휴일 달력·수요 예측)의 ② | 그대로 둠 | 빈 `hb-search` 추가 — 공장 하나 고르면 몇 행인 작은 목록·달력이라 조회 버튼이 한 번 더 누를 일만 만든다(CMS 같은 판단) |
| D58 | 단말 [다시 발급] | 위험 동작(외곽선 + 떨어진 줄 + 위험 확인) | 보통 버튼 — 누르는 순간 지금 쓰는 단말·수집기가 끊긴다 |
| D59 | LOT 분할 수량 입력 | 글자 칸(쉼표로 여러 수), 검증은 서버 | 숫자 칸 — 수가 여러 개라 하나짜리 숫자 칸으로 못 받는다 |

### 4.30 UI 단계 3 ① — MES 주 목록 → hb.grid (docs/40 10.1 단계 3, 결정 #45·#47·#48, 2026-10-22 — 마이그레이션 없음)

대상(#48 MES 몫 = production·pop): 작업지시(prd-wo)·생산 입고(prd-receipt)·POP 단말(sys-terminal). LOT 관리는 D60 으로 표 유지.

| 무엇 | 어떻게 |
|---|---|
| API | 기존 목록 API 는 그대로 두고 `/grid`·`/columns` 만 추가 — 지시 `/api/production/wos/grid`, 입고 `/api/production/receipts/wos/grid`, 단말 `/api/production/terminals/grid`. 조건 검사는 목록과 같은 메서드(지시는 `listCond` 로 묶음), `sort` 는 `getParameterValues` → `PageQuery.parse`(결정 #47), 정렬은 허용 목록 enum(`WoSort`·`ReceiptWoSort`·`TerminalSort`) 이름으로만 매퍼 choose, 마지막에 PK |
| 기본 순서 | 목록 API 와 같게 — 지시 = 계획 시작 최신 → 우선, 입고 대상 = 계획 시작 최신, 단말 = 공장 → 코드(폐기는 D61) |
| 열 | 서버 `ColumnSpec`(화면 표와 같은 칸). 상태 칸은 상세가 열려 목록이 좁아도 보이게 앞쪽(품목·수량·계획 시작 다음). 수량은 `dec(6).withMinScale(0)`(저장 자릿수 그대로), 지시 수주 칸 = `orderRef`(번호-행), 단말 작업장 = `nodeRef`(코드 · 이름) — DTO 계산 getter |
| 상태 배지 | `GridOptions.status` — 지시·입고 = `mesUi.TONES.wo`(4.29 톤 표), 단말 = 서버 계산 `state`(REVOKED·ACTIVE·KEY_MISSING·WAITING·EXPIRED, 화면 `state()` 와 같은 순서) + `format` 으로 이름 |
| 흐린 행 | `rowClass` → `hb-tree-dim`: 지시 취소·마감, 입고 대상 마감, 단말 폐기 |
| 미저장 이동 | `beforeSelect: mesUi.leaveOk`(Promise) — 조회(`onSearch`)도 같은 확인 뒤 `grid.reload(true)` |
| 저장 뒤 | `grid.reload(false, id)` — 그 행이 지금 페이지에 있으면 grid 가 골라 상세를 열고, 없으면(새 지시·다른 페이지) 상세만 다시 읽는다. 단말 등록 코드(한 번만 보임)는 다시 고른 행 상세에 이어 붙인다 |
| 목록 건수 | grid 바닥 줄(총 n건)로 — 화면의 "n건"·"앞의 500건만" 글자와 그 문구 키 7개는 지움 |
| POP 대화상자 | 같은 PR(QA 지적): POP 실적 정정 사유·작업 종료·실적 버리기·자격/착수 경고 확인 → `hb.dialog`(정정 사유 필수, 종료·버리기·정정은 위험 톤). POP 화면은 `hb.dialog.js` 를 새로 싣는다. 대화상자 버튼은 POP 규칙대로 48px |

| # | 정한 것 | 고른 것 | 버린 것 · 이유 |
|---|---|---|---|
| D60 | LOT 관리 주 목록 | 처음엔 **표 유지**(상위 결정 (A)) → **2026-10-23 grid 로 전환**(4.31, hb.grid 행 체크박스 #307) | (전환 전) grid — 병합할 LOT 을 여러 개 고르는 행 체크박스가 hb.grid 에 없었다. 상세로 "병합에 담기" 옮기기는 쓰는 방식이 바뀐다 |
| D61 | 단말 grid 의 폐기 단말 위치 | 정렬과 관계없이 **늘 끝** | 사용자 정렬 그대로 — 쓰지 않는 단말이 사이에 섞여 지금 쓰는 단말을 찾기 어렵다(목록 API 순서와도 같게) |
| D62 | 입고 대상 grid 기본 정렬 | 계획 시작 열(`planStart`)을 응답·열에 **추가**해 정렬 키로 | 지시 번호 순 — 목록 API(계획 시작 최신)와 순서가 달라진다 |
| D63 | 실적 조회·ERP 엑셀·LOT 역추적 보고서의 ① | grid 전환 **안 함**(2026-10-22 상위 동의) | grid — 실적 조회는 보기(한 건씩·지시별·공정별)마다 열이 바뀌는 집계 표, ERP 엑셀은 내보내기 전 미리보기, LOT 보고서는 인쇄용 절 8개로 "행 고르기 → 상세" 주 목록이 아니다. confirm/prompt 없음·hb-search 이미 사용이라 ②~⑥ 도 할 것 없음 |

### 4.31 UI 단계 3 ① — LOT 관리 주 목록 → hb.grid (D60 갱신, 2026-10-23 — 마이그레이션 없음)

hb.grid 행 체크박스(`checkable`·`onCheck`·`getChecked`·`clearChecked`, main #307)가 들어와 D60 의 표 유지를 풀었다.

| 무엇 | 어떻게 |
|---|---|
| API | `GET /api/production/lots/grid`·`/columns` 추가만(기존 목록 유지). 조건 검사는 목록과 같은 `listCond`, 정렬 허용 목록 `LotSort`(LOT 번호·품목·출처·등록 일시), 잔량(원장 계산 `hb_lot_balance`)과 상태는 정렬 밖. 기본 = 등록 최신(목록과 같게) |
| 열 | LOT 번호·품목 코드·품목 이름·**상태**(앞쪽)·잔량·생성량·단위·출처(`LOT_ORIGIN`)·지시·공급사 LOT(`source`)·등록 일시. 상태 = DTO 계산 `state`(HELD·EMPTY·ACTIVE) |
| 상태 배지 | 보류만 warning 배지(4.29: 확인 필요), 잔량 0·사용 가능은 글자. 보류·잔량 0 행은 `hb-tree-dim` |
| 병합 고르기 | `checkable` — 체크는 쪽·정렬을 넘어 key 로 남고, 새 조회(`reload(true)`)면 비운다. 병합 뒤 `clearChecked()`(병합한 LOT 은 잔량 0). [병합(n)] = 체크한 수 |
| 미저장 이동 | 자재 LOT 등록 폼 — `beforeSelect`·조회 모두 `mesUi.leaveOk` |
| 목록 건수 | grid 바닥 줄(총 n건·선택 n건). 옛 "n건·앞의 500건만" 글자·고름 머리 칸과 문구 키 6개 지움 |

| # | 정한 것 | 고른 것 | 버린 것 · 이유 |
|---|---|---|---|
| D64 | 보류·잔량 0 LOT 의 체크 | **체크칸 비활성**(`checkableRow: row.state === 'ACTIVE'`, 2026-10-23 #320 뒤) — 머리 칸 "모두"·Space 도 건너뛴다. 서버도 `LOT_HOLD`·`EMPTY_LOT` 으로 거부(시험) | (#320 전 임시) 체크는 되고 [병합] 을 막고 이유를 안내 — 행마다 막는 기능이 들어와 지웠다(문구 키 `js.prdLot.mergeUnusable` 도) |

### 4.32 품목 기본 단위 변경 금지에 LOT 사용 (11.4 남은 것 · WMS 합의 Q1, 2026-10-23 — V142·임시 V9117)

**한 줄**: 지시 없이 자재 LOT 만 등록된 품목도 기본 단위를 바꿀 수 없게 한다. 지금까지 `hb_check_item_unit_in_use` 는 BOM·환산·지시·재고 원장·수주만 보아, 공급사 LOT 만 있는 품목은 EA → KG 로 바뀌었고 LOT 수량·잔량·계보 수량의 뜻이 조용히 바뀌었다.

| 무엇 | 어떻게 |
|---|---|
| DB 검사 | `hb_check_item_unit_in_use` 에 `lot_master` 를 더한다(V123 조건 모두 유지, 제약 이름 `fk_mst_item_unit_in_use` 그대로). 인덱스는 V034 `ix_lot_master_item` 이 있다 |
| 동시 실행 | `hb_guard_lot_master` INSERT 가 품목 행을 `FOR SHARE` 로 읽는다(V091 `inv_trx` 와 같은 줄 세움). 없으면 커밋 전 단위 변경과 LOT 등록이 서로를 못 보고 둘 다 통과해 EA LOT·KG 품목이 남는다 — 시험이 양쪽 순서 모두 이 경우를 잡는다(FOR SHARE 를 빼면 2건 실패 확인) |
| 화면·엑셀 | 품목 화면 `ItemMapper.unitInUse`·엑셀 올리기 `ItemImportTemplate` 도 같은 조건(+ LOT·수주) — DB 오류 전에 칸 오류 `IN_USE`. 수주(V123)가 앱 쪽 검사에서 빠져 있던 것도 같이 맞춤 |

| # | 정한 것 | 고른 것 | 버린 것 · 이유 |
|---|---|---|---|
| D65 | LOT 이 하나라도 있으면 | 단위 변경 금지(지시·원장과 같은 규칙) | 잔량 있는 LOT 만 — 다 쓴 LOT 도 계보·보고서 수량이 그 단위로 남아 뜻이 바뀐다 |

### 4.33 단말 유형별 경로 — PDA(창고) 경계 (WMS B7, 2026-10-23 — 마이그레이션 없음)

WMS 창고 단말(PDA)이 POP 셸(단말 활성화·쿠키·배지·키패드·오프라인 대기열)을 같이 쓴다. 화면·API 주인은 나누고 보안 체인은 하나.

| 무엇 | 어떻게 |
|---|---|
| 경로 | 창고 홈 `/pop/wh`(아래 `/pop/wh/move`·`/count`·…), API `/api/pop/wh/**` — 템플릿·JS·컨트롤러는 WMS(module/wms/pda). 공용은 `hb.pop.js` 만 |
| 홈 분기 | `GET /pop` 이 단말 유형을 보고 PDA 면 `/pop/wh` 로(서버 리다이렉트) — WMS 가 MES `pop/home.js` 를 고치지 않는다 |
| 유형 권한 | `TerminalAuthFilter` 가 `ROLE_TERMINAL` + `ROLE_TERMINAL_{유형}`. 체인(`PopSecurityConfig`): `/pop/wh`·`/api/pop/wh/**` = PDA, 할 일·공정·실적·투입·파라미터·정정 API(`/api/pop/home`·`procs/**`·`lots`·`results/**`·`params/**`) = POP, 세션·배지·작업자 로그아웃·언어 = 둘 다. 다른 유형 = API 403 `FORBIDDEN`, 화면 = `/pop`(자기 홈) |
| 인증·CSRF | 창고 경로도 같은 체인(`/pop/**`·`/api/pop/**`) — 단말 쿠키·쿠키 CSRF·CSP·미인증 401/활성화 화면 그대로. 수집 장치(COLLECTOR)는 다른 체인(`/ext/**` Bearer)이라 영향 없음 |
| 세션 | `/api/pop/session` 에 `terminalType` |
| 대기열 | `hb.pop.queue.send(url, body, {label})` — 보낸 화면이 만든 요약 글자를 저장(거부·다시 보내기에도 유지). POP 거부 목록은 label 이 있으면 그것, 없으면 기존처럼 실적 본문 |
| 작업장 | PDA 는 작업장 없이 공장만(`node_id` null) — 지금도 된다(V035). 창고 노드(WH·LOC)에 묶기는 요청 없음 — 필요해지면 V035 트리거를 유형별 허용 노드로 넓히는 V(MES 몫) |

| # | 정한 것 | 고른 것 | 버린 것 · 이유 |
|---|---|---|---|
| D66 | 유형별로 막는 자리 | 보안 체인(경로 규칙 + 유형 권한) | 서비스마다 유형 검사 — WMS 컨트롤러가 늘 때마다 빠뜨리기 쉽고, 막힌 요청이 서비스까지 들어온다 |
| D67 | 거부 목록 표시 | 보낸 화면의 요약 글자(label) | 거부 목록이 URL 로 화면마다 그리기 — 셸이 모든 모듈 본문 모양을 알아야 한다 |

### 4.34 POP·PDA 셸 공용 도우미 — 배지 로그인·PIN·거부 목록 (WMS B7-1 뒤, 2026-10-23 — 마이그레이션 없음)

POP 작업장 홈(`pop/home.js`)과 WMS PDA 창고 홈(`wms/pda-home.js`)에 같은 코드(배지 → PIN → 인사, 틀린 PIN 머묾, 작업자 끊김, PIN 점, 거부 목록 그리기)가 두 벌 있던 것을 `hb.pop` 으로 꺼냈다. 두 화면은 화면마다 다른 것만 넘긴다.

| 도우미 | 화면이 넘기는 것 | 규칙 |
|---|---|---|
| `hb.pop.badgeLogin({url, pad, pinWho, feedback, showPanel, onSignedIn, onGone, fail})` → `{submit, onPin, cancel, pending, clear, gone}` | 로그인 뒤 할 일(POP = 홈 새로 읽기, PDA = 메뉴 + 세션 새로 읽기) | 배지 값은 PIN 을 누르는 동안만 메모리. 틀린 PIN(`PIN_WRONG`) = PIN 화면에 머묾, 그 밖 오류 = 배지 화면. `gone(err)` = `POP_NO_WORKER` 면 배지 화면 + onGone. 흐름 본체 `makeBadgeLogin(deps)` 는 node 단위 시험 |
| `hb.pop.pinDots(el)` | PIN 점 칸 | 누른 자리 수만큼 점(숫자 안 보임) |
| `hb.pop.rejectedList(ul, {describe, keys, onRetried})` | 줄 글자(POP = label 없으면 실적 본문, PDA = label), 문구 키, 다시 보낸 뒤 알림(POP 만) | [다시 보내기]·[버리기](위험 확인) — 4.33 label 과 같이 |

- 화면 템플릿·패널 이름(`badge`·`pin`)·문구 키는 그대로. 공용 문구는 `js.pop.hello` 하나(인사).

### 4.35 POP 고장 신고 화면 (CMS C20 계약 뒤, 2026-10-23 — 마이그레이션 없음)

POP 작업자가 설비 고장을 알린다. 기록은 CMS 보전 작업지시(BM·REQ)로 남고, 계약은 `module.cms.link.BreakdownReport`(docs/33 11.16)다. MES 는 화면과 범위 확인만 맡는다.

| 항목 | 내용 |
|---|---|
| API | `GET /api/pop/breakdown/equips`(설비 선택지), `GET /api/pop/breakdown/symptoms?equipId=`(CMS 현상), `POST /api/pop/breakdown` `{equipId, symptomCd, symptomText, clientReqId}` → `{reqNo, duplicate}` |
| 단말 유형 | `/api/pop/breakdown`·`/**` 를 `POP_PATHS` 에 넣었다 — PDA 는 403(4.33 D66 과 같은 자리). 세션 `breakdown` = POP 단말이고 CMS 구현이 있을 때만 참, 거짓이면 버튼을 숨긴다(계약 NONE) |
| 작업자 | 지금 작업자 필수(`POP_NO_WORKER` 428). 신고자 = 작업자 id + 단말 id(CMS 가 저장, 감사 행위자 `t{id}:{코드}`) |
| 설비 범위 | 단말 공장의 사용 중 설비. 단말에 작업장이 있으면 그 노드 + 바로 아래 노드(공정 범위 `findProc` 와 같은 규칙) — 미설치(노드 없음) 설비는 공장 단말에서만. 범위 밖은 칸 오류 `equipId`: 없음·다른 회사 `NO_SUCH_CODE`, 사용 중지 `INACTIVE`, 다른 공장 `PLANT`, 작업장 밖 `EQUIP_NODE`. CMS 도 공장을 다시 본다 |
| 트랜잭션 | POP 서비스 `@Transactional` 안에서 `report()`(계약 MANDATORY) — CMS 검사·멱등·채번·작업지시가 한 트랜잭션 |
| 오류 | CMS 칸 오류(`symptomCd`·`symptomText`·`clientReqId`·…)를 그대로 돌려주고 화면은 메시지를 이어 보인다 |
| 화면 | [고장 신고] 버튼 = 내 할 일 도구줄 + 공정 화면(그 공정 설비를 미리 고름). 설비 버튼 → 현상 버튼(다시 누르면 취소) + 글(200자) → [신고 보내기]. 성공 = "요청 번호 … 접수", 같은 요청 재전송 = "이미 접수된 신고" + 같은 번호. 신고 화면에서는 스캔을 무시하고(배지가 읽혀 작업자가 바뀌지 않게), 자동 새로 보기가 화면을 바꾸지 않는다 |
| 하지 않은 것 | 오프라인 대기열·사진(계약 Q5), 공정·설비 상태 자동 연결(보강 7 — 멈춤은 공정 화면에서 따로) |

| # | 정한 것 | 고른 것 | 버린 것 · 이유 |
|---|---|---|---|
| D68 | 신고할 수 있는 설비 | 단말 작업장(+ 바로 아래) 설비 전부, 공정 화면에서 열면 그 공정 설비를 미리 고름 | 공정에 배정된 설비만 — 설비 배정이 없는 공정이 많고(DEMO 지시도 미배정), 고장은 공정과 무관하게 난다 |
| D69 | 요청 id(clientReqId) 재사용 | 같은 내용을 다시 보내면 같은 id(응답 유실 → 처음 번호), 설비·현상·글을 바꾸면 새 id | 패널을 열 때 한 번만 — 응답 유실 뒤 내용을 고쳐 보내면 고친 신고가 처음 신고 번호로 묻힌다 |
| D70 | 오프라인일 때 | 보내지 않고 "온라인일 때만" 안내(초안 유지) | 실적처럼 대기열 — 고장은 지금 알려야 의미가 있고, 계약도 온라인만(Q5) |

### 4.36 수집 카운트 조회·정정 화면 (4.27 후속, 2026-10-23 — V160·임시 V9118: 화면 등록만)

4.27 의 "하지 않은 것" — 원장 `cms_equip_count` 는 역분개 행을 받지만(V139 모양 CHECK, V140 원 행 대조 트리거) 사람이 넣을 화면이 없었다. 수집기 중복 이벤트·센서 오카운트를 되돌리는 길이다.

| 항목 | 내용 |
|---|---|
| 화면 | `cms-equip-count`(수집 카운트, 생산 메뉴 — 설비 상태 타임라인 옆, module_cd PRD, SEARCH·SAVE). 코드 자리 = `module.cms`(수집기·타임라인과 같은 MES 코드 자리, 표 주인 CMS — docs/33 14장) |
| 조회 | 공장(필수)·발생일 기간(공장 시간대 [from 00:00, to 다음날 00:00), 최대 92일 + 정책)·설비·구분(양품·불량) → grid(서버 페이징, 정렬 허용 목록 발생·기록 시각·설비·구분, 기본 발생 시각 최신). 상태 = 정상 / 정정됨 / 정정 행 — 정정됨·정정 행은 흐리게 |
| 정정 | 상세 [정정] → 사유(필수, 500자) 대화상자 → 역분개 행 하나: `CORRECT`, qty = −원 qty, `reversal_of_id`, 기록자 = 로그인 아이디. 원 행은 그대로 |
| 막는 것 | 이미 정정된 행·정정 행을 다시 = 409 `countId:STATE`, 동시에 두 번 = 유일 인덱스(`uq_cms_equip_count_reversal`)가 하나만 → 나머지 409. 원 행과 맞는지(설비·구분·수량)는 V140 트리거가 한 번 더 |
| 읽는 쪽 | POP 실적 제안(`countSuggest`)·CMS KPI(`countSums`)는 역분개 행을 더해 읽는다 — 고칠 것 없음 |

| # | 정한 것 | 고른 것 | 버린 것 · 이유 |
|---|---|---|---|
| D71 | 정정 행의 발생 시각 | **원 행의 발생 시각 그대로**(기록 시각 = 지금) — 그 이벤트를 지우는 것이라 같은 시간 구간에서 상쇄된다 | 정정한 지금 — 어제 잘못 센 수가 오늘 POP 제안(마지막 실적 뒤 합)·오늘 KPI 에서 빠져 엉뚱한 구간이 줄고, 원래 구간은 그대로 틀린 채 남는다 |
| D72 | 화면 자리 | MES 새 화면(수집기 코드 옆) | CMS 상태 검토 화면에 붙이기 — 표 주인은 CMS 지만 원장을 만들고 쓰는 코드(수집·POP 제안)가 MES 라 같은 자리에서 고치는 편이 덜 흩어진다. CMS 가 원하면 화면 이동은 메뉴만 |

### 4.37 POP 감독자 승인 (2026-10-23 설계 · 2026-10-24 사용자 결정 P1~P6 추천안 · 구현 V169·임시 V9119)

5a "하지 않은 것" 의 **E 항목(화면 안 비밀번호 승인 잠금)** 이다. 5a 메모는 "단말 화면 잠금 해제용 관리자 비밀번호 — 5b 정정 승인과 같이" 였다. 지금 POP 단말에는 잠글 설정 화면이 없다. 작업자가 혼자 되돌릴 수 있는 위험 동작은 **실적 정정·파라미터 정정**(역분개)이고, 지금은 사유만 있으면 된다(4.7·4.19). 그래서 이 절은 "단말 잠금 해제" 를 **"POP 위험 동작에 감독자 승인을 요구할 수 있게"** 로 다시 정의해 제안한다. 아래 **결정 필요 P1~P6** 이 정해지면 구현 PR(V169·임시 V9119)로 간다.

#### 4.37.1 지금 있는 것

| 무엇 | 지금 | 이 설계와의 관계 |
|---|---|---|
| POP 실적 정정 | 지금 작업자 + 사유(필수) → 역분개 행(`entry_method = CORRECT`, `performed_by` = 작업자, `recorded_by` = 단말) | 승인 대상 1순위 |
| POP 파라미터 정정 | 같음(`prd_param_actual` 역분개) | 승인 대상 |
| 작업자 PIN | 배지 + PIN(정책 `POP_PIN_REQUIRED`), 5회 틀리면 15분 잠금(`mst_worker.pin_fail_cnt`·`pin_locked_until`, V035) | 승인자 확인에 그대로 쓸 수 있다 |
| 플랫폼 전자서명 | `sys_esign` + `ESignService`(docs/36 16장) — 서명자 = **사무 계정**(`sys_user`), 비밀번호(+MFA) → 5분 토큰 → 업무 트랜잭션 안 `consume` | **토큰 2단계 모양만 빌린다.** 표·서비스는 못 쓴다 — 현장 감독자(반장)는 사무 계정이 없는 경우가 많고, 단말에서 MFA 를 받을 수 없다 |
| 자격 게이트 | 착수 때 BLOCK·WARN(작업자 확인) | 이번 범위 밖(P6) |

#### 4.37.2 제안하는 흐름

1. 작업자가 [정정] 을 누르고 사유를 쓴다(지금과 같음).
2. 그 동작이 승인 대상이면(정책), 화면이 **"감독자 승인"** 대화상자를 연다: 감독자 배지 스캔 → PIN 키패드.
3. `POST /api/pop/approvals` `{badge, pin, action, targetId}` → 서버가 승인자를 확인하고 **2분·한 번 쓰는 승인 토큰**을 단말 세션에 둔다(응답은 토큰 id 와 승인자 이름).
4. 화면이 원래 정정 요청에 `approvalToken` 을 실어 보낸다. 정정 서비스가 **같은 트랜잭션 안에서** 토큰을 꺼내 지우고(동작·대상·만료·같은 단말 확인) 승인 원장 행을 남긴 뒤 역분개한다. 토큰이 없거나 맞지 않으면 403 `POP_APPROVAL_REQUIRED`, 화면은 2번으로 돌아간다.
5. 토큰 2단계로 나누는 까닭은 플랫폼 서명과 같다: BCrypt 비교와 실패 기록을 업무 트랜잭션 밖에서 하고(연결 하나), 승인을 그 동작·그 대상에만 묶는다.

#### 4.37.3 결정 (2026-10-24 사용자: P1~P6 모두 추천안 채택)

| # | 질문 | 추천 | 다른 안 · 버리는 이유 |
|---|---|---|---|
| P1 | 승인 대상 동작 | **실적 정정·파라미터 정정** 두 가지, 동작마다 BOOL 정책(`POP_APPROVAL_RESULT_CORRECT`·`POP_APPROVAL_PARAM_CORRECT`, **기본 N**) — 켜지 않으면 지금 동작 그대로 | 작업 종료·멈춤까지 — 매일 여러 번 하는 동작이라 감독자 찾기가 마찰이 된다(docs/11 "감독자를 찾으세요로 끝나는 예외는 우회된다") |
| P2 | 감독자는 누구 | **작업자(`mst_worker`) 중 정책 `POP_APPROVER_QUAL`(자격 코드)을 오늘 유효하게 가진 사람**. 확인 = 배지 + PIN(PIN 이 없는 감독자는 승인 불가) | 사무 계정 아이디 + 비밀번호 — 반장은 계정이 없는 경우가 많고 단말에서 MFA 를 받을 수 없다 / 작업자 표에 감독자 칸 추가 — 자격(유효기간·회수)이 이미 있다 |
| P3 | 자기 승인 | **금지** — 승인자 ≠ 지금 작업자(400 `POP_APPROVER_SELF`) | 허용(PIN 재입력) — 혼자인 야간조에는 편하지만 "다른 사람 눈" 이라는 목적이 사라진다. 혼자인 조는 정책을 끄면 된다 |
| P4 | 실패·잠금 | 감독자 PIN 실패는 **그 작업자의 PIN 잠금**(5회 → 15분, 기존 `pin_fail_cnt` 공유) + 틀린·감독자 아닌 배지는 **기존 단말 배지 제한**(단말마다 1분 10회 → 1분 막음, `PopAuthService.BADGE_MAX_FAIL`)에 함께 센다(여러 배지로 돌려 찍기 방지). 접속 로그 `POP_APPROVAL_FAIL` | 승인 전용 카운터 — 같은 PIN 을 두 경로로 따로 맞혀 볼 수 있게 된다 |
| P5 | 기록 | **승인 원장 `pop_approval`**(추가만, RLS): 단말·요청 작업자·승인자·동작·대상(유형·id)·결과 행 id·사유·시각. 역분개 행에는 칸을 더하지 않는다 | 실적·파라미터 표에 `approved_by` 칸 — 원장 두 개를 고치고, 동작이 늘 때마다 표가 바뀐다 |
| P6 | 이번에 하지 않을 것 | 자격 게이트 BLOCK 우회(감독자 승인으로 착수), 사무 화면 원격 승인, 오프라인 승인, PDA(창고) 동작 | BLOCK 우회는 자격 규칙의 뜻을 바꾸므로 따로 결정 — 필요하면 같은 토큰을 동작 하나 추가로 붙인다 |

#### 4.37.4 구현 범위

- V(V169·임시 V9119): 정책 3개(`POP_APPROVAL_RESULT_CORRECT`·`POP_APPROVAL_PARAM_CORRECT`·`POP_APPROVER_QUAL`), `pop_approval`(추가만 — `hb_forbid_update_delete` + REVOKE, 같은 회사 단말·작업자 트리거, 보존 PRODUCTION_RECORD), 접속 로그 이벤트 코드 `ACCESS_EVENT.POP_APPROVAL_FAIL`(V116 `ESIGN_FAIL` 과 같은 자리).
- 서버: `PopApprovalService.issue`(바깥 트랜잭션 없음 — 조회 → 연결 없이 PIN 비교 → 실패 기록 → 세션 토큰), `consume`(MANDATORY). `PopWorkService.correct`·`correctParam` 이 정책을 보고 `consume`. 경로 `/api/pop/approvals` 는 `POP_PATHS`(PDA 403).
- 화면: `hb.pop` 에 감독자 승인 대화상자(배지 입력 + PIN 키패드 + `pinDots` 재사용), POP 정정 두 곳에서 403 `POP_APPROVAL_REQUIRED` 를 받으면 열고 다시 보낸다.
- 시험: 정책 끔(지금 그대로)·켬(토큰 없음 403), 감독자 아님·자격 만료·자기 승인·PIN 틀림·잠김, 단말 배지 제한(로그인과 합산), 토큰 재사용·다른 대상·다른 동작·만료·다른 단말, 동시 두 정정에 토큰 하나, **A사로 B사**(B사 배지·B사 결과 id), 직접 SQL(`pop_approval` UPDATE/DELETE 거부), PDA 403.

#### 4.37.5 구현 결과 (2026-10-24, V169·임시 V9119)

- **V169·임시 V9119**: 정책 `POP_APPROVAL_RESULT_CORRECT`·`POP_APPROVAL_PARAM_CORRECT`(공장별 BOOL, 기본 N)·`POP_APPROVER_QUAL`(회사 STRING, 기본 빈 값 = 감독자 없음), `pop_approval`(추가만·RLS·회사 트리거 `fk_pop_approval_ctx_comp`·자기 승인 CHECK `ck_pop_approval_self`·대상 유일 `uq_pop_approval_target`·보존 PRODUCTION_RECORD), 접속 로그 코드 `POP_APPROVAL_FAIL`.
- **서버**: `POST /api/pop/approvals`(`POP_PATHS` — PDA 403) → `PopAuthService.approve`. 검사 순서: 작업자 있음 → 동작·대상 → 단말 배지 제한 → 배지(없음·사용 중지 = `BADGE_UNKNOWN`) → 본인(`POP_APPROVER_SELF` 400) → 자격(`POP_APPROVER_NOT_QUALIFIED` 403, 공장 날짜 기준 유효) → PIN 없음 409·잠김 423·틀림 400(남은 횟수). 토큰은 단말 세션에 하나(새 승인이 앞 것을 덮음), 작업자가 바뀌거나 로그아웃하면 버린다.
- `PopWorkService.correct`·`correctParam`: 사유·상태 검사 **뒤에** 정책을 보고(사유 오류가 승인 요구보다 먼저), 승인이 없으면 403 `POP_APPROVAL_REQUIRED`, 있으면 역분개 후 같은 트랜잭션에서 `pop_approval`. 컨트롤러가 성공 뒤 토큰을 지운다 — 정정이 실패하면 토큰은 남아 2분 안 같은 대상에 다시 쓸 수 있다.
- **설계와 다른 점**: ① 승인 발급은 배지 로그인과 같은 방식(업무 트랜잭션 안 BCrypt, 실패 횟수만 분리 트랜잭션) — 4.37.2 의 "바깥 트랜잭션 없음" 대신 기존 POP 인증 코드와 한 길로 ② 승인 화면은 공용 `hb.pop` 이 아니라 POP 홈(`pop/home.js`·`#approvalPanel`)에 — 쓰는 화면이 하나 ③ 파라미터 정정은 POP 화면 버튼이 없어(API 만) 승인도 API 로만 시험했다 ④ 본인 배지는 PIN 을 누른 뒤 서버가 거부한다(배지 → 작업자는 서버만 안다).
- **화면**: 오늘 실적 [정정] → 사유 → 403 이면 "감독자 승인" 화면(정정할 실적 요약) → 감독자 배지 스캔(스캐너 칸 그대로) → PIN 키패드 → 정정 다시 → "정정했습니다 · 승인 {이름}" 후 공정 화면. 틀린 PIN 은 PIN 화면에 머물고, 배지 쪽 오류는 배지부터. 취소 = 공정 화면.


### 4.38 POP 파라미터 정정 버튼 (2026-10-24 — 마이그레이션 없음)

4.37 구현 때 파라미터 정정은 POP 화면에 버튼이 없어 API 로만 됐다. 공정 화면 파라미터 줄의 "최근 값" 에 **[정정]** 을 붙였다.

| 항목 | 내용 |
|---|---|
| 대상 | 그 파라미터의 **최근 유효 실측**(`params[].lastActualId` — 정정된 것 제외). 정정하면 그 앞 유효 값이 "최근" 이 되고, 버튼도 그 값을 가리킨다. 서버 변경 없음(기존 `POST /api/pop/params/{id}/correct`) |
| 흐름 | [정정] → 화면 안 대화상자(파라미터·최근 값, 사유 필수, 위험 톤) → 정정. 공장 정책 `POP_APPROVAL_PARAM_CORRECT` 면 403 → 4.37 감독자 승인 화면("정정할 파라미터: 코드 · 값") → 정정 다시 |
| 자리 | 저장(주동작) 뒤 줄 끝 — 4.28 위험 동작 규칙. 390 폭에서 값·저장이 한 줄에 남는다 |
| 하지 않은 것 | 최근 값보다 앞 실측을 골라 정정(목록) — 잘못 넣은 직후 고치는 경우가 대부분이고, 앞 값은 최근 값을 정정하면 차례로 드러난다 |

### 4.39 실적 조회 정정 승인자 (2026-10-24 — 마이그레이션 없음)

사무 실적 조회(prd-result, 4.16) "실적 한 건씩" 의 정정된 실적에 **승인한 감독자**를 보인다(4.37 감사 추적). 화면 "정정" 칸 = "정정됨 {시각} · {사유} · 승인 {이름}({코드})", 엑셀 칸 `승인자 코드`·`승인자` 추가.

| 항목 | 내용 |
|---|---|
| 조회 | `ResultViewMapper` 상세 보기만 `pop_approval`(동작 `RESULT_CORRECT`, 대상 = 원 실적) → 승인자 작업자 — 정정 하나에 승인 하나(`uq_pop_approval_target`)라 행이 늘지 않는다. 묶음 보기(지시·공정·작업자·근무조)는 그대로 |
| 비어 있음 | 승인 정책이 꺼진 공장에서 한 정정·정책을 켜기 전 정정은 승인자 없음(지금처럼) |
| 하지 않은 것 | 파라미터 정정 승인자 — 실적 조회에 파라미터 실측이 없다(필요하면 파라미터 조회 화면과 함께) |

### 4.40 MES grid 엑셀 묶음 (2026-10-24 — V177·임시 V9120: sys_prog 동작만)

기준정보·CMS 목록과 같이 MES 생산 목록 5화면에 grid 메뉴 **[엑셀로 내보내기]** 를 붙였다. 길은 하나(`ExcelExportService` — 건수 상한·출력 정보 시트·수식 주입 방지·코드명).

| 화면 | API(`POST …/export`, 권한 EXCEL) | 조건(grid 와 같은 검사) | 이름 칸 |
|---|---|---|---|
| 작업지시 `prd-wo` | `/api/production/wos/export` | 공장·기간·상태·품목·지시 번호 | 상태 = 코드명(WO_STATUS) |
| 생산 입고 대상 `prd-receipt` | `/api/production/receipts/wos/export` | 공장(필수)·상태·지시 번호 | 상태 = 코드명 |
| LOT 추적 `prd-lot` | `/api/production/lots/export` | 기간·품목·LOT 번호·출처·보류만 | 상태 = 보류·소진·사용 가능 |
| POP 단말 `sys-terminal` | `/api/production/terminals/export` | 없음(폐기는 늘 끝) | 상태 = 사용 중 등, 작업장 없음 = (공장 전체) |
| 수집 카운트 `cms-equip-count` | `/api/cms/equip-counts/export` | 공장·기간(필수)·설비·구분 | 상태 = 정상·정정됨·정정 행, 구분 = 양품·불량 |

- **열·정렬**: grid 의 보이는 열(`columns`)·정렬(`sort`, 허용 목록)을 그대로 받는다(`ColumnSpec.selectExportable` — grid 열 밖·중복은 400). 조회는 grid 쿼리를 정렬만 페이지(`PageQuery.sortOnly`)로 다시 쓴다.
- **이름 칸**: 화면이 코드 값을 이름으로 바꿔 보이는 글자 칸은 엑셀에서도 이름으로 — `MesExport.named`(열을 이름 속성으로 바꿈) + 서비스가 행마다 채움(문구 키는 화면과 같은 `js.*`).
- **수량 칸**: 화면 응답은 수량을 글자로 담는다(planQty·balance 등) — 엑셀 DEC 칸은 숫자여야 해서 숫자 속성(`…Num`)으로 바꿔 내보낸다. **브라우저 확인에서 500("String cannot be cast to Number")으로 발견** — 시험이 글자 칸만 골라 내보내 놓쳤다 → 모든 열 내보내기 시험 추가.
- **좁은 폭(390)**: 행을 오른쪽 클릭하면 행이 골라져 상세 서랍이 열리고 grid 메뉴를 덮어 메뉴를 누를 수 없다 — 기준정보(품목·설비) 화면도 같다(공용 `hb.grid`·`hb.page`). 이 PR 범위 밖 — 공통 후보로 보고.

### 4.41 LOT·입고 대상 grid 깊은 offset — 지연 조인 (2026-10-24 — 마이그레이션 없음)

QA 분석 docs/qa/2026-10-24_offset-deep-page.md 의 **H1**(`LotMapper#findLotPage`)과 **M2**(`ReceiptMapper#woPage`). 둘 다 바깥 칸 계산(행마다 `hb_lot_balance` = 계보 합, `hb_proc_qty` = 실적 합 + 입고 합)이 offset 으로 버리는 앞쪽 행에도 돌았다 — 끝 쪽일수록 느려진다.

**고침**: 지연 조인(WMS docs/30 5.1 과 같은 규칙).
- 안쪽 = `lot_id`/`wo_id` 만 — 거름(`lotWhere`/`woWhere`)·정렬·`limit/offset`. 입고 대상은 품목 코드 검색·정렬 때문에 안쪽에도 `mst_item` 조인.
- 바깥 = 그 50행에만 조인(품목·지시·거래처)·잔량·공정 수량 함수, 같은 정렬을 다시.
- **정렬 끝에 id**(`m.lot_id desc`·`w.wo_id desc`): 정렬 값이 같은 행이 많으면(같은 시각 일괄 입고, 같은 계획 시작일) 쪽 경계에서 행이 빠지거나 겹친다. 안쪽·바깥 정렬이 같아야 한 쪽 안 순서도 같다.

**실측**(`MesOffsetPerfTest`, `-Dhb.perf=true`, 50행 쪽, 3번 중 최소, Testcontainers PostgreSQL 16 · 통계 갱신 뒤):

| 목록 · 크기 | 1쪽 | 가운데 | 끝 | 품목 정렬 가운데 | 보류만 + 건수 | 1쪽 + 건수 |
|---|---|---|---|---|---|---|
| LOT 10만 · 전 | 122 | 1,965 | 3,694 | 2,001 | 49 | 139 |
| LOT 10만 · 지연 조인 | 55 | 59 | 58 | 490 | 43 | 63 |
| LOT 10만 · + nulls last 뺌 | 10 | 18 | 24 | 493 | 46 | 70 |
| 입고 대상 10만 · 전 | 100 | 397 | 690 | — | — | 171 |
| 입고 대상 10만 · 지연 조인 | 62 | 95 | 105 | — | — | 91 |
| 입고 대상 10만 · + nulls last 뺌 | 9 | 72 | 77 | — | — | 40 |

(ms. LOT 10% 는 다음 LOT 으로 소비 계보, 0.5% 보류. 입고 대상은 지시마다 공정 1개.)

- **nulls last**: 정렬 키가 모두 NOT NULL 이라 `desc nulls last` 를 뺐다(결과 순서 같음 — QA docs/qa/2026-10-24_sort-nulls-last.md, 가드 `SortNullsLastGuardTest` 목록에서 두 문장 삭제). `desc nulls last` 는 기본 색인 역순(= DESC NULLS FIRST)과 맞지 않아 1쪽도 후보 전체를 정렬했다.
- **100만**: LOT 100만 채우기가 2시간 안에 끝나지 않아(10만 5분 — 채우는 쪽이 선형보다 빨리 늘어남) 이 저장소에서는 재지 못했다. 100만 근거는 QA 실측(docs/qa/2026-10-24_generic-plan.md N-1 — 같은 지연 조인 LOT 1쪽, nulls last 를 빼면 0.4초 → 0.4ms)으로 둔다. 실측 시험은 크기를 `HB_PERF_ROWS` 로 받는다.

- **잔량 칸·부분 색인은 두지 않는다**(QA 분석의 후보 기각). 잔량 조건(`hb_lot_balance > 0`)은 "보류만" 조건에만 있고, 그때는 보류 부분 색인 `ix_lot_master_hold`(`hold_yn='Y'`)가 후보를 보류 LOT(전체의 일부)로 먼저 줄인다 — 실측 43ms. 잔량 칸을 `lot_master` 에 두면 계보 원장 INSERT 마다 LOT 행을 UPDATE 해야 하고(원장은 INSERT 만이지만 LOT 마스터는 갱신 가능 — 규약 위반은 아님), 그 대신 계보 동시 기록이 같은 LOT 행 잠금으로 줄을 서고 칸과 원장 합이 어긋날 길이 생긴다. 지금 조회 크기에서 얻는 것이 없어 기각. 보류 LOT 이 수만 개로 늘면 다시 잰다.
- **남은 비용 — 품목 정렬**: 안쪽 정렬 키가 품목 코드면 모든 후보 행에 품목 코드가 필요하다(부질의). 10만에서 490ms — 계산 칸 때문이 아니라 정렬 자체의 비용이고, 기간 조건(최대 366일)이 후보를 줄인다. 색인으로 풀 수 없는 형태(다른 표의 값으로 정렬)라 그대로 둔다.
- **건수**: 건수 문장(`count(*)`)은 원래 칸 계산이 없어 고칠 것이 없다.
- **시험**: `MesGridTest` 쪽 경계 시험 — 같은 등록 시각 LOT 7개를 3행 쪽 3번(7개 모두 한 번씩, 첫 행 = 가장 큰 id), 품목 정렬 2쪽, 같은 계획 시작일 지시 7개. 실측 시험은 가운데 쪽 첫 LOT 번호가 순번과 맞는지 본다.

### 4.42 POP PIN 확인 — 연결 겹쳐 잡지 않기 (2026-10-24 — 마이그레이션 없음, 시스템관리 발견·상위 배정)

`PopAuthService.badge`·`approve` 는 바깥 `@Transactional` 안에서 틀린 PIN 을 분리 트랜잭션(`runner.runIsolated` → `pinFailed`)으로 남겼다. 그래서 단말 요청 하나가 DB 연결 2개를 잡았다. 단말 여러 대가 동시에 틀린 PIN 을 보내면 모두 첫 연결을 쥔 채 두 번째를 기다리고, 풀이 바닥나면 앱 전체가 멈춘다. 재현: 풀 4 + 단말 8대 동시 → `Could not open JDBC Connection for transaction`(고치기 전 코드로 `PopPinPoolTest` 4건 중 4건 실패).

**고침**: 바깥 트랜잭션을 없애고 짧은 트랜잭션을 차례로 쓴다(MfaService.check·OpsAuthService 와 같은 모양, docs/36 14.4.1 ②).
1. **조회**: 배지 → 작업자, PIN 정책(승인은 감독자 자격·공장 날짜까지) — 한 트랜잭션.
2. **선점**: 조건부 UPDATE 한 문장 `pinClaim` 이 이번 시도를 실패로 먼저 센다. 동시 시도는 행 잠금으로 한 줄로 서고, 잠긴 동안 온 시도는 0건(→ `PIN_LOCKED`) — 커밋.
3. **비교**: bcrypt 를 연결 없이 한다. **잠겨 있어도 비교한다**(응답 시간으로 잠김을 알리지 않게). 형식이 틀린 PIN 은 빈 문자열로 비교해 같은 시간을 쓴다.
4. **맞으면**: `pinOk` 로 실패 수 0·잠금 해제 — 커밋. **단 그사이 아무도 끼어들지 않았을 때만**(2026-10-26 QA O-1): 실패 수가 이번 선점이 돌려준 수 그대로면 0·해제, 다른 단말의 틀린 시도가 그사이 선점됐으면 **자기 선점분만 − 1**(0 아래 없음)하고 잠금 시각은 그대로 둔다. 로그인은 성공(맞는 PIN). 예전에는 0 으로 덮어써 남의 실패가 사라지고(시도 예산이 다시 5) 그사이 걸린 잠금도 풀었다 — 맞는 PIN 을 아는 사람만 일으키는 경우라 심각도 낮음.

- `runner.call` 은 열린 트랜잭션 안에서 부르면 거부한다 — 바깥에 `@Transactional` 을 다시 거는 회귀를 시험이 바로 잡는다.
- **규칙 변화 하나**: 잠글 때 실패 수를 0 으로 돌리지 않고 5 로 둔다(MFA 와 같음). 잠금이 끝난 뒤 첫 시도는 1부터 센다(`pin_locked_until` 이 남아 있으면 1, 해제 직후 한 번 틀려 다시 잠기지 않게). 사무 PIN 재지정(`WorkforceMapper`)은 그대로 둘 다 지운다.
- 시각은 서비스 시계(잠금 확인과 같은 시계)로 넘긴다 — 예전 `now()`(DB 시각)와 서비스 시계가 섞여 있던 것을 하나로.
- 맞는 PIN 은 쓰기가 2번(선점 + 0)이 된다. 5번째 시도가 맞으면 선점이 잠금을 걸었다가 `pinOk` 가 바로 푼다 — 그 사이에 같은 작업자로 온 다른 시도는 잠김으로 답한다(MFA 와 같은 비용).
- 그대로 둔 것: 5회 잠금 15분, 단말 배지 제한(1분 10회), 작업자 바뀌면 세션 ID 바꾸기, 앞 작업자 승인 버리기, PIN 없이 배지만 보내면 needPin(잠겨 있으면 잠김 안내).
- 접속 로그는 플랫폼이 연결 반납 뒤에 쓴다(#520 B1) — 이 고침은 PIN 선점 부분만.

### 4.43 LOT 보류 출처 QMS_SPC — QMS SV-6 MES 몫 (2026-10-26 — V191·임시 V9121: CHECK 값 하나)

QMS SV-6(SPC 위반 자동 LOT 보류, docs/29_P3 23장)의 MES 합의 항목 M-1~M-4(23.4 — 표를 가진 쪽이 바꾼다). QMS 구현(정책 `QMS_SPC_AUTO_HOLD`·판정 훅 보류·CLOSE/VOID 해제)은 이 PR 병합 뒤 QMS 가.

| # | 반영 |
|---|---|
| M-1 | `LotHoldPort.QMS_SPC = "QMS_SPC"`(refId = SPC 이벤트 id), `MesLotHoldPort` 허용 출처에 추가 — 그 밖은 지금처럼 IllegalArgumentException |
| M-2 | `ck_lot_hold_source` 에 `'QMS_SPC'`(최신 정의 V109 에서 출발 — 그 뒤 바꾼 마이그레이션 없음) + 컬럼 주석. `ck_lot_hold_ref`(MANUAL 만 참조 없음)·QMS_* FK 없음은 그대로 |
| M-3 | 포트 잠금 문구: "부르는 쪽이 **이미** 잡은 자기 행 잠금은 괜찮다 — 단 LOT 을 먼저 잡고 그 행을 잠그는 경로가 없어야 한다"(23.3) |
| M-4 | LOT 화면 보류 근거 출처 문구 `js.prdLot.src.QMS_SPC` = "SPC 위반" / "SPC violation". 풀기 버튼 없음 — 화면은 MANUAL·MES_CORRECT 만(서버 `HOLD_SOURCE` 도 그대로) |

- QMS 화면(봉쇄·NCR)의 출처 문구 맵에도 `QMS_SPC` 가 필요하면 QMS 구현 PR 이 같은 키를 쓴다(키는 이 PR 이 만든다).

### 4.44 POP 고장 신고 사진 (2026-10-26 설계 — CMS C25 계약 docs/33 11.17·#485, 마이그레이션 없음)

4.35 고장 신고 뒤 "사진 붙이기". 기록은 CMS(`BreakdownReport.attachPhoto` → 공용 첨부 `cms-breakdown` × 작업지시 — 사무 작업지시 상세 "신고 사진"에 그대로 보인다). MES 는 화면·API·범위 확인만.

**흐름(단말)**
1. 신고가 접수되면(새 번호든 `duplicate` 든) 신고 화면이 **사진 단계**로 바뀐다 — 요청 번호, 붙인 수 `n / 최대`, [사진 찍기], [끝내기].
2. [사진 찍기] = `<input type="file" accept="image/jpeg,image/png" capture="environment">`(태블릿 카메라 바로 열림, 갤러리에서도 고를 수 있음).
3. 고른 사진을 **단말에서 줄인다**: `createImageBitmap` → canvas(긴 변 1600px 이하) → JPEG 0.85(회사 허용 형식에 jpg 가 없으면 PNG). **늘 다시 인코딩**한다 — 한도 안의 원본도 그대로 보내지 않는다. 줄인 결과가 한도(바이트)를 넘으면 보내지 않고 안내.
   - 다시 인코딩하면 EXIF(촬영 위치·기기 등)가 빠진다 — 현장 사진에 위치가 남지 않는다. 서버는 EXIF 를 기대하지 않는다.
   - CSP 는 `img-src 'self' data:` — `blob:` URL 을 `<img>` 에 못 쓴다. 그래서 `createImageBitmap`(URL 없이 디코드)으로 그리고, 미리보기는 작은 canvas 의 `data:` URL. CSP 공용 설정은 바꾸지 않는다.
4. `hb.pop.upload`(= `hb.upload` + `X-HB-Client: POP` — 접속 로그 구분)로 `POST /api/pop/breakdown/photos`(multipart: `reqNo`, `clientReqId`, `file`) → `{fileId, duplicate, count}`. 미리보기 줄에 썸네일 + "붙음"(또는 "이미 붙어 있음"). 한도에 닿으면 [사진 찍기] 를 숨긴다.
5. **재전송**: 끊김·시간 초과면 그 사진(줄인 바이트 그대로)을 들고 [다시 보내기]. 같은 바이트 = 같은 SHA-256 → CMS 가 처음 파일을 돌려준다(`duplicate = true`, 수 안 늘림) — 응답을 못 받은 사진을 다시 보내도 두 장이 안 된다. 다시 줄이지 않는 것이 핵심(다시 인코딩하면 바이트가 달라져 두 장이 된다).
6. 오프라인이면 [사진 찍기] 가 "온라인일 때만" 안내(신고와 같음 — 11.16 Q5 오프라인 대기열 없음).
7. [끝내기] → 신고를 연 화면(내 할 일 / 공정)으로.

**서버(MES `module/pop`)**
- `PopBreakdownApiController` + `PopBreakdownService.attachPhoto` — `@Transactional`(계약 MANDATORY). 경로 `/api/pop/breakdown/**` 는 이미 `POP_PATHS`(PDA 403).
- 지금 작업자 필수(없으면 428 `POP_NO_WORKER`) — 붙이는 사람 = 배지로 확인된 작업자(신고자와 달라도 됨, 교대 — 계약 그대로).
- 넘기는 값: `Photo(reqNo, workerId, terminalId, clientReqId, 원래 파일 이름, 선언 크기, 스트림)`. 범위(신고한 단말만·REQ/OPEN·BM)·형식·크기·개수·잠김은 **CMS 가 판정**하고 MES 는 오류를 그대로 돌려준다(칸 오류 400 `reqNo`·`clientReqId`·`workerId`·`terminalId`·`file`, 공용 거부 `file.reject.*`, 409 `PHOTO_LIMIT`·`CMS_MWO_STATE`·`FILE_LOCKED`). 같은 검사를 MES 에 복제하지 않는다(규칙이 두 곳이면 어긋난다).
- MES 가 먼저 막는 것은 두 가지만: 파일 없음·빈 파일 → 400 `file`, 요청 크기 상한(`spring.servlet.multipart` 100MB 그대로 — 정책 한도는 CMS).
- 화면에 보일 한도: **CMS 계약의 읽기 메서드** `BreakdownReport.photoLimits()` → `PhotoLimits(int maxCount, long maxBytes, List<String> exts)`(CMS 가 `CMS_POP_PHOTO_MAX`, `min(FILE_MAX_MB, CMS_POP_PHOTO_MB)`, jpg·jpeg·png ∩ 회사 `FILE_ALLOWED_EXT` 를 계산). MES 는 CMS 정책 키를 직접 읽지 않는다(모듈 경계 — 상위 결정). 신고 응답에 `photoMax`·`photoBytes`·`photoExts` 로 실어 단말이 미리 줄이고 미리 막는 데만 쓰고, 최종 판정은 `attachPhoto`. `maxCount = 0`(NONE 이거나 회사가 막음)이면 사진 단계를 숨긴다.
- NONE(CMS 없음): 신고 버튼부터 숨김(4.35) → 사진 단계도 없음. API 는 409. CMS `photoLimits()`(#560, C28)는 `attachPhoto` 판정과 같은 계산이다.

**결정(2026-10-26 상위 검토 — D73 수정 채택, D74·D75 추천안)**

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D73 | 한도를 화면에 어떻게 알리나 | **채택(상위 수정, 2026-10-26)**: CMS 계약 `photoLimits()` 읽기 → 신고 응답 `photoMax`·`photoBytes`·`photoExts`. 정책 키는 CMS 만 읽는다 | MES 가 CMS 정책을 직접 읽기(모듈 경계 넘음 — 기각) / 서버 거절로만(큰 사진을 다 보낸 뒤 거절) |
| D74 | 단말에서 줄이기 | **채택**: 긴 변 1600px·JPEG 0.85, 늘 다시 인코딩(EXIF 위치 정보 빠짐), 재전송은 줄인 바이트 그대로 | 원본 그대로 — 휴대 카메라 4~8MB 가 5MB 한도에 자주 걸림 |
| D75 | 지난 신고에 사진 | **채택**: 이번 신고 직후 사진 단계에서만(그 화면을 닫으면 끝) | 단말의 지난 신고 목록 — 계약은 허용하지만 화면·목록이 하나 더(필요하면 뒤에) |

**시험(구현 PR)**: 신고 → 사진 1·2·3 → 4번째 409 `PHOTO_LIMIT`, 같은 바이트 재전송 = 같은 fileId·`duplicate`, 다른 단말 400 `reqNo`, 다른 신고 요청 id 400 `clientReqId`, 작업자 없음 428, PDA 403, 보전 착수(IN_PROGRESS) 뒤 409, 형식(png 이름에 pdf 내용)·크기 넘침 400, 파일 없음 400, **A사 단말로 B사 작업지시 번호 400**, 호출 쪽 롤백이면 파일 행 없음(MANDATORY), 사무 작업지시 상세 "신고 사진"에 보임. 브라우저: 카메라 대신 파일 고르기(Playwright `setInputFiles`), 큰 사진 줄이기·썸네일, 응답 유실 뒤 [다시 보내기] = "이미 붙어 있음", 오프라인 안내, 390 폭.

**구현(2026-10-26)**
- 신고 응답: `ReportResult` 그대로 → MES `BreakdownReply(reqNo, duplicate, photoMax, photoBytes, photoExts)`(같은 트랜잭션에서 `photoLimits()`). 화면이 읽던 `reqNo`·`duplicate` 는 그대로.
- 공통 변경 하나: `hb.upload(url, formData, extraHeaders?)` — 더할 헤더(선택). 지금 호출은 그대로, `hb.pop.upload` 가 POP 헤더를 단다(`types/hb.d.ts` 계약도).
- 오류 → 화면: 끊김·시간 초과 = 그 사진 "보내지 못함" + [다시 보내기], `PHOTO_LIMIT` = [사진 찍기] 숨김 + "n장까지", `CMS_MWO_STATE`·`FILE_LOCKED` = "보전이 이미 처리를 시작" + 숨김, 그 밖(형식·크기 등 칸 오류) = 그 사진을 빼고 서버 문구 그대로.

### 4.45 생산 입고 위치 — WMS 판정으로 거르기 (2026-10-27 — WMS S2 계약 #619, 마이그레이션 없음)

생산 입고 화면(`prd-receipt`)의 창고·로케이션 목록이 공장의 사용 중 WH·LOC 전부라, 고를 수 있어도 저장하면 WMS `post` 가 거절하는 칸(재고 안 둠 등)이 있었다. WMS 계약 `ReceiptLocQuery.productionReceipt(plantCd, itemId, lotId)`(저장과 같은 판정, MANDATORY, WMS 없음 = NONE)로 거른다. 생산 입고는 이 사무 화면 한 곳뿐이다(POP·PDA 생산 입고 경로 없음 — 코드로 확인).

- **목록**: `GET …/receipts/locations?woId=&lotId=` — 지시의 공장·품목, 고른 LOT(그 지시의 입고 대상 LOT 일 때만 — NG 이어받기)으로 칸마다 `status`(받으면 들어갈 재고 상태)·`reasonCd`(받을 수 없는 사유: `STOCK_NOT_ALLOWED`·`NODE_INACTIVE`, WMS 목록에 없으면 `NOT_WH`). 예전 호출(`plantCd` 만)은 그대로(판정 없음).
- **화면**: 받을 수 없는 칸은 고를 수 없게(옵션 비활성 + "— 재고 안 둠"), 가용이 아닌 상태로 들어갈 칸은 "— 입고 상태 검사 대기"(코드명 `STOCK_STATUS`). LOT 을 바꾸면 다시 묻는다(상태가 LOT 판정을 이어받을 수 있어).
- **저장**: 화면 숨김만으로 막은 것으로 보지 않는다 — `receiveAs` 가 저장 전에 같은 계약으로 다시 판정해 칸 오류(`nodeId`·사유 코드). 최종은 `post` 그대로(같은 판정이라 두 번째 방어).
- **NONE(WMS 없음)**: 목록은 지금 그대로(사유·상태 없음), 저장 전 판정도 건너뛴다(`post` 가 없으면 재고 원장 자체가 없다).
- 관찰(범위 밖 — WMS 규칙): DEMO 의 출하 대기(STAGING) 칸도 "받을 수 있음"으로 나온다 — 판정은 WMS(사용 중·재고 둠)이고 MES 는 그대로 보인다. WMS F1(생산 투입이 STAGING 칸을 꺼냄)과 같은 질문이라 WMS 쪽 설계에 맡긴다.
- **WMS F1 뒤(#634, 2026-10-28)**: 출하 대기(STAGING) 칸은 생산 입고 사유 `STAGING_NOT_ALLOWED`("— 출하 대기 칸", 비활성), 생산 투입은 STAGING 칸을 꺼내지 않고 거기에만 있으면 409 + 안내(응답 `details`). POP 화면의 오류 줄·대기열 거부 사유가 `details` 를 보이지 않아 안내가 사라지던 것을 고쳤다(`home.js` fail · `hb.pop.js` 거부 기록).
- DEMO 시드의 FG-B01(이름 "출하 대기 B01")은 WMS 칸 속성이 없어(용도 기본 STORAGE) 받을 수 있음으로 나온다 — 이름만 출하 대기인 데이터(시드 몫, 상위에 알림).

### 4.46 완료판정 경고 확장점 `CloseNotice` (2026-10-29 — 사용자 결정 QMS 보류 1 "종품 판정 — 공정 완료 때 경고만"의 MES 몫, 상위 승인, 마이그레이션 없음)

QMS 가 "이 공정 산출 LOT 의 판정이 아직" 같은 것을 공정 완료판정 때 **경고만** 하려면 MES 에 끼울 자리가 필요하다. 착수 게이트(`StartGate`, 4.21)와 같은 모양의 MES 정의 확장점을 두고 구현은 다른 모듈이 빈으로 넣는다(없으면 경고 없음).

- **계약** `production.link.CloseNotice`: `List<Notice> notices(CloseContext ctx)`, `CloseContext(plantCd, woId, woProcId, procId, itemId, List<Long> outputLotIds, boolean preview)`, `Notice(code, messageKey, args, refType, refId)`. `outputLotIds` = 이 공정 산출 LOT(실적 양품 LOT + 재작업/수리 처리 양품 LOT, 중복 없음·오름차순 — `CompletionMapper.outputLots`).
- **부르는 곳**: 미리보기 = 지시 완료판정 목록(`GET …/wos/{id}/completion`, 화면이 쓰는 것)·공정 `close-check`(읽기 전용 트랜잭션, `preview = true`), 판정 = `close`(판정 트랜잭션 안·판정 행을 쓴 뒤, `preview = false`). 끝난(DONE) 공정은 다시 부르지 않는다(목록을 열 때마다 끝난 공정 경고를 다시 계산하지 않게).
- **막지 않는다**: 결과는 `CloseCheck.notices`(`NoticeLine(code, message, refType, refId)`, 문구는 요청 로캘로 MES 가 푼다)로만 — 막는 이유 `problems` 와 섞지 않는다. 판정은 경고가 있어도 그대로 된다.
- **구현 빈 규칙**(javadoc): 읽기만·잠금 없음(FOR UPDATE·FOR SHARE 안 씀), 다른 모듈 표는 계약으로만, 예외를 던지지 않게. 던지면 MES(`CloseNotices`)가 빈마다 세이브포인트(NESTED)로 되돌리고 로그만 — 그 빈의 경고 없음, 판정 트랜잭션은 aborted 가 되지 않는다(SQL 오류도 시험).
- **착수 게이트와 다른 점**: 게이트는 BLOCK·WARN(확인)·오류 정책(`START_GATE_ON_ERROR`)이 있지만, 이것은 경고뿐이라 mode·정책·`key()` 가 없다(오류 = 경고 없음). 화면 확인(ack)도 없다 — 목록에 "확인 필요: …"(`js.prdWo.notice`), 판정 뒤 안내 줄에 같은 문구.
- 다음: QMS 가 구현 빈(종품 판정 미완 경고)을 넣는다 — 그 문구 키는 QMS 메시지에.

### 4.47 단말 스캔 입력을 `hb.scan` 위로 — C6 (2026-10-29 — docs/50 7장 C6, 사용자 결정 2026-10-29, 시스템관리 C1~C4 #662 뒤, 마이그레이션 없음)

POP 작업장 홈·창고 PDA 의 스캔 입력 `hb.pop.scanner(input, onScan)` 을 화면 공통 `hb.scan`(시스템관리 소유) 위의 얇은 감싸기로 바꿨다. APK 스캐너 SDK(native, `hb:scan` 이벤트 — C4)가 들어오면 단말 화면도 따로 고치지 않고 같은 경로로 받는다.

- **호출 모양 그대로**: `(input, onScan)` → `onScan(값)`, 반환 `{pause, resume}`. 호출처 `pop/home.js`·`wms/pda-home.js` 는 고치지 않았다.
- **안**: 받기는 `hb.scan.listen(r => onScan(r.value), {input})`(웨지 = 그 칸 + Enter, native = 이벤트), 여기는 숨은 칸 포커스 되돌리기만 남는다. 값은 `hb.scan` 정리 규칙(제어 문자 제거·GS 유지·200자)을 따른다 — 예전엔 앞뒤 공백만 잘랐다. 서버가 다시 검증한다.
- **pause**: 듣기를 해제한다(native 도 받지 않음) — 예전엔 포커스만 놓아서 이벤트형 스캔이면 pause 중에도 들어올 수 있었다. resume 은 칸을 비우고 다시 듣는다(두 번 불러도 한 번만). 지금 호출처는 pause/resume 을 쓰지 않는다.
- **템플릿**: `pop/home.html`·`wms/pda-home.html` 에 `/js/common/hb.scan.js` 를 `hb.js` 바로 뒤에. `hb.scan.js` 없이 부르면 바로 오류(조용히 스캔이 안 되는 것보다) — `pop/activate.html` 은 `hb.pop.js` 를 쓰지만 스캐너를 부르지 않아 넣지 않았다.
- 한 화면에 듣는 곳 하나(C1) — 두 화면 모두 스캐너 하나라 충돌 없음. 카메라(C5)·`camera=(self)`(K9)는 C5 PR 에서.

### 4.48 월별 불량 수량 계약 `DefectQtyQuery` (2026-10-29 — 사용자 결정 QMS 보류 4 COPQ 의 MES 몫, 상위 배정, 마이그레이션 없음)

QMS 가 품질 비용(COPQ)을 셀 때 MES 의 불량 수량을 MES 표를 직접 조회하지 않고 읽는 계약이다. 읽기만, MES 가 없으면 `NONE`(빈 결과).

- **계약** `production.link.DefectQtyQuery`: `List<DefectQty> monthly(String plantCd /*null = 전체*/, YearMonth from, YearMonth to)`, `DefectQty(month, itemId, kind, qty, unitCd, source)`. `of(ObjectProvider)` 로 빈 또는 `NONE`.
- **원천 두 가지**: 실적 불량 `prd_result_defect`(kind = SCRAP·REWORK·REPAIR, source `RESULT`) + 재작업/수리 처리의 폐기 `prd_rework.scrap_qty`(kind `SCRAP`, source `REWORK`). 처리 폐기는 실적에서 이미 재작업·수리로 센 수량의 **결과**라 원천을 나눠 준다 — 둘을 더할지(이중 계산 여부)는 COPQ 계산(QMS)이 정한다.
- **정정은 정정한 달에 음수로(사용자 결정 C-1 (라), 2026-10-30)**: 원래 실적·역분개 행·다시 넣은 실적을 걸러 내지 않고 각자 발생 시각의 달에 더한다. 정정은 역분개 행만 쓰고 대체 행이 없으며(`PopWorkService.correct`), 역분개 행의 발생 시각은 정정 시각(V110 — 기록 시각과 같은 순간)이다. 그래서 **원래 달 = 원래 값 그대로**, **정정한 달 = −원래 + (그 달에 다시 넣은 값)**, 같은 달 안 정정은 상쇄되어 줄이 없다. qty 는 음수일 수 있다. 지난 달 COPQ 가 나중에 조용히 바뀌지 않게 하려는 결정이다(처음 PR 안은 사무 실적 조회처럼 정정된 실적을 두 달 모두에서 빼서 지난 달이 바뀌었다 — 상위 확인으로 드러나 사용자 결정으로 바꿈). 사무 실적 조회(4.16)는 화면 목적상 "정정된 실적 제외" 그대로다 — 두 규칙이 다르다는 것을 계약 javadoc 에 적었다.
- 다시 넣은 실적의 발생 시각은 넣는 때다(POP 화면이 지금 시각을 보냄, API 는 지시 발행 시각 이후 과거만 받음 — `occurredAt` RANGE). 처리(prd_rework)는 정정하지 않는다(D14).
- **월** = 회사 시간대(`BizClock.zone()`)로 본 발생 시각(`occurred_at`)의 연월. 서비스가 월 경계를 시각 [시작, 끝)으로 바꿔 묻고, 묶음도 같은 시간대로(DB 세션 시간대 안 씀). 공장 시간대가 아니라 회사 시간대다(상위 지정).
- **범위**: from ≤ to, 최대 36개월 — 어기면 IllegalArgumentException(호출 쪽 코드 오류). 공장 = 지시의 공장, 품목 = 지시 품목, 수량·단위 = 실적 단위 그대로(환산 없음), 0 인 줄 없음, 순서 = 월 → 품목 → 원천 → 종류 → 단위.
- **트랜잭션**: 다른 MES 계약(`WoMaterialQuery`)과 같이 `@Transactional(readOnly = true)` — 부르는 쪽 트랜잭션에 들어가고 없으면 읽기 전용으로 연다. 잠금 없음. 회사는 RLS.
- **실행계획 메모**: 회사 전체 `occurred_at` 범위 인덱스는 없다(`ix_prd_result_proc`·`_equip_time` 은 공정·설비가 앞). 지금은 회사 행을 `comp_cd` 앞 인덱스로 읽고 기간을 거른다(DEMO EXPLAIN — 6행이라 규모 판단은 아님). 월 보고용이라 지금은 두고, 실적이 커지면 `(comp_cd, occurred_at)` 인덱스(새 V)를 후보로 — 이 PR 에서는 마이그레이션을 만들지 않았다.

### 4.49 LOT 계보 추적 계약 `LotTraceQuery` (2026-10-29 — WMS 출하 추적, 사용자 결정 W-5 "SN 대신 LOT 추적"의 전제, 상위 배정, 마이그레이션 없음)

WMS 가 출하한 LOT 의 앞뒤 계보(리콜 범위)를 MES 표를 직접 읽지 않고 받는 계약이다. 사무 LOT 추적(`LotMapper.trace`, 4.5)과 같은 재귀·규칙을 여러 루트로 넓혀 계약 뒤에 두었다. 읽기만, MES 없음 = `NONE`(빈 결과).

- **계약** `production.link.LotTraceQuery`: `forward(lotIds, maxDepth)`(어디로 갔나)·`backward(lotIds, maxDepth)`(어디서 왔나) → `TraceResult(rows, truncated)`, `TraceRow(rootLotId, lotId, depth, viaLotId, relType, cycle)`.
- **줄**: 루트마다 자기 자신이 depth 0(via·relType null). 없는·다른 회사 LOT 은 루트가 되지 않는다(RLS, 예외 없음 — `LotLookup` 과 같음). 같은 (부모, 자식, 종류) 여러 계보 행은 한 줄, 순서 = 루트 id → 경로(깊이 우선).
- **살아 있는 계보만**: 역분개 행·역분개된 원래 행 제외(`LotMapper.live` 조각을 그대로 include — 규칙 두 벌 안 만듦).
- **순환**: 경로에 이미 있는 LOT 에 닿으면 그 줄에 `cycle = true`, 더 따라가지 않는다(무한 재귀 없음).
- **잘림은 조용히 넘기지 않는다(상위 보충)**: 줄이 `MAX_ROWS` 를 넘으면 그 수까지 + `truncated`, 깊이 `maxDepth` 에 닿은 LOT(순환 줄 제외)에서 더 이어지는 살아 있는 계보가 있으면 `truncated`. 리콜 조회가 덜 보인 채 끝나지 않게 부르는 쪽이 표시한다. 상한을 넘는 것은 예외가 아니라 이 플래그다.
- **상한**(상위 제안 그대로): LOT `MAX_IDS = 500`(= `LotLookup.MAX_IDS`), 깊이 `MAX_DEPTH = 10`(0 = 루트만), 줄 `MAX_ROWS = 5,000`(사무 LOT 추적 화면과 같음, 모든 루트 합). LOT 수·깊이가 범위 밖이면 IllegalArgumentException(호출 코드 오류).
- **사무 화면과 다른 점**: 화면은 깊이를 정책 `LOT_TRACE_DEPTH` 로, 줄 잘림만 표시. 계약은 부르는 쪽이 깊이를 정하고 깊이 잘림도 표시한다. 화면 쪽은 바꾸지 않았다.
- **트랜잭션·인덱스**: `@Transactional(readOnly = true)`(부르는 쪽 안 / 없으면 읽기 전용), 잠금 없음. 재귀는 `ix_lot_genealogy_parent`·`_child`(사무 추적과 같은 경로), 깊이 잘림 판정은 끝 LOT 들에 `exists` 한 번(MAX_ROWS 단위로 나눠).

### 4.50 ERP 송신 응답 본문 저장 안 함 — `ErpResult.detail` 짧은 사유만 (2026-10-30 — 사용자 결정 Q-O1, 시스템관리 발견 O13-c, 상위 배정, 마이그레이션 없음)

ERP 송신기(`ErpOutboundHandler`)가 어댑터의 `ErpResult.detail` 을 그대로 아웃박스 `last_error`(연계 현황 화면)에 실었다. 실제 어댑터가 상대 응답 본문을 넣으면 거래처·개인정보가 그대로 남는다. 계약과 송신기 양쪽에서 막는다.

- **계약(javadoc)**: `detail` = 짧은 사유(사유 코드·HTTP 상태 수준 — "400 ITEM_UNMAPPED"·"429 too many requests"·"read timeout after store"), **응답 본문 금지**. 어댑터가 버린다.
- **송신기 한 번 더**: `ErpOutboundHandler.reason(detail)` — 허용 모양(글자·숫자·공백·`_ . : / ( ) -`, 80자까지, 한 줄)이면 그대로, 아니면 응답 본문으로 보고 앞의 HTTP 상태 숫자만 "HTTP 400 (detail omitted)", 숫자도 없으면 "detail omitted". 빈 값은 "-".
- 한글 사유도 본문으로 본다(허용 글자 밖) — 사유는 영문 코드로 쓰는 것이 DB 규칙(상태값 영문 코드)과도 맞다. 가짜 ERP(`FakeErpAdapter`)의 사유는 모두 허용 모양이라 그대로 남는다(기존 ErpTest 기대 그대로).
- 시스템관리 쪽(릴레이 공통 HTTP 예외·메일·Web Push)은 `OutboxResponseBodyTest`, ERP 는 `ErpResponseBodyTest` 가 덮는다.

### 4.51 자재 LOT 등록 — 단말 요청 행위자 허용 (2026-11-01 — 사용자 결정 W-6, WMS PDA 입고의 전제, docs/30 12.12.9 보류 해제, 마이그레이션 없음)

사무 입고(`MaterialReceiptService.receive`)는 입고마다 `LotService.receive` 로 RECEIVED LOT 을 만드는데, 그 서비스가 로그인 사용자만 받아 단말 요청은 401 이었다(docs/30 12.12.9 "입고 보류"). PDA 입고를 붙일 수 있게 기록자 규칙을 넓힌다.

- **기록자**(`LotService.receiver()`): 로그인 사용자가 있으면 그 사용자(사무 입고 그대로). 없으면 회사 범위의 행위자가 **단말 모양** `t{단말 id}:{단말 코드}`(`TerminalPrincipal.actor()`, `TerminalAuthFilter` 가 범위를 연다)일 때만 그 행위자 → `lot_master.created_by`. WMS `InventoryService.me()`(12.12.9 "로그인 없으면 요청 행위자")와 같은 방식이되, **SYSTEM(요청 밖)·사용자 모양 흉내는 401** — 단말 요청만 넓혔다.
- 판별은 행위자 글 모양(정규식)으로 한다 — production 이 pop 패키지의 `TerminalPrincipal` 을 import 하지 않게(모듈 의존 방향 그대로). 행위자는 서버가 단말 쿠키로 만든 값이라 요청이 바꿀 수 없다.
- **넓힌 곳은 receive 하나**: 분할·병합·라벨 바꿈 등 다른 LOT 작업은 지금처럼 로그인만(`me()`).
- **배지 작업자(performed_by)**: `lot_master` 에는 작업자 칸이 없다(마스터 행 — created_by 만). 실제 작업자는 부르는 쪽 원장(WMS 입고·재고 원장 `performed_by`)에 남는다. 그래서 MES 는 작업자를 받지 않고, **작업자 필수(배지 없음 = 428)는 PDA 입고 API(WMS)가 지금 POP·PDA 경로처럼 `currentWorker` 로 막는다**. `lot_master` 에 작업자 칸을 더할지는 상위에 질의(지금은 더하지 않음 — 마이그레이션 없음).
- 다른 회사 단말: 범위가 그 단말 회사라 이 회사 품목 코드는 RLS 로 안 보인다(`itemCd:NO_SUCH_CODE`).

### 4.52 키오스크 빠져나오기 API — K5 (2026-11-02 — docs/50 K4·K5·Q8 확정, 상위 배정, V226·임시 V9122: 접속 로그 이벤트 코드 둘)

APK 껍데기가 POP 화면을 화면 고정(lock task, docs/50 K3)으로 띄울 때, 감독자가 고정을 푸는 서버 계약이다. 주인은 MES(POP 체인·감독자 확인, K5), 시스템관리는 껍데기 다리 `YeonShell.kiosk(false)` 만 부른다(이 계약 병합 뒤 상위가 넘김). 화면(구석 길게 누르기 → 감독자 대화상자 → 다리 호출)은 그 연결 때.

- **API** `POST /api/pop/kiosk/exit` `{badge, pin}` → `{approverCd, approverNm, relockAfterSec}`. POP 체인 기본(ROLE_TERMINAL) — **POP·PDA 단말 공용**(K5). 단말 쿠키가 없으면 401, 사무 로그인은 이 체인에서 읽히지 않는다.
- **누가 (Q8 확정)**: 감독자 = 정책 `POP_APPROVER_QUAL` 자격을 오늘(공장 날짜) 유효하게 가진 사용 중 작업자, 배지 + PIN 을 **서버가** 확인. 자격 정책이 비면 아무도 못 빠져나온다. 서버에 닿지 않으면 못 빠져나온다(MDM 해제 — 설치 안내).
- **승인(4.37)과 같은 확인 하나**: `PopAuthService.supervisor(...)` 로 묶었다 — 배지 형식·사용 여부·자격·PIN·**실패 카운터 공유**(틀린·감독자 아닌 배지 = 단말 배지 제한 1분 10회, 틀린 PIN = 그 작업자 PIN 5회 → 15분). 빠져나오기 경로로 PIN 을 따로 맞혀 보지 못한다. 승인 동작은 그대로(시험 그대로 통과).
- **승인과 다른 점**: 지금 작업자가 없어도 된다(배지 화면에서), 지금 작업자가 감독자 본인이어도 된다 — 자기 동작을 승인하는 것이 아니라 단말 고정을 푸는 것이라 P3(본인 금지)를 두지 않았다.
- **기록**: 접속 로그(`sys_access_log`) `POP_KIOSK_EXIT`(OK, `worker=감독자 코드`, 행위자 = 단말 `t{id}:{코드}`, client_type = 단말 유형) / 실패 `POP_KIOSK_EXIT_FAIL`(FAIL, 사유 qual·badge·pin·locked·pin-not-set — 승인 실패와 같은 사유 글). 새 표 없음. 접속 로그 화면 조건·표시용 공통코드 `ACCESS_EVENT` 두 줄과 ko/en 문구를 임시 V9122 로 더했다(가드 `SysLogAdminTest` — AccessEvent 와 코드 집합이 같아야 한다).
- **`TerminalPrincipal` 과**: 요청의 단말(쿠키 → TerminalAuthFilter)이 회사 범위·행위자·client_type(#604 ClientTyped — 등록 유형 POP/PDA)을 정한다. 감독자는 그 회사 범위에서만 찾는다(다른 회사 배지 = 없는 배지).
- **다시 잠그기**: 서버는 잠금 상태를 갖지 않는다. 응답 `relockAfterSec`(지금 600초 고정) 뒤·작업자가 바뀔 때·홈이 다시 열릴 때(K3 — 홈이 `kiosk(true)`) 화면이 다시 잠근다. 다시 잠그기에는 승인이 없다. 다리 호출은 위조돼도 "고정을 푼다" 뿐이라 서버 판정을 껍데기가 다시 검사하지 않는다(K6).
- **사용자 결정 2026-11-02(추천안 그대로)**: ① 다시 잠그는 시간 = 10분 고정(공장 정책·수동만 아님) ② 감독자 본인이 지금 작업자여도 허용 ③ 기록 = 접속 로그만(별도 원장 표 없음) ④ PDA 단말 허용(PDA 키오스크는 Q7 로 기본 꺼짐).
- **QA #775 정책 질의 — 사용자 결정 2026-11-02(추천안 = 현행 유지, 코드 변경 없음)**: K-a "감독자 아님" 403 NOT_QUALIFIED 와 "없는 배지" 400 BADGE_UNKNOWN 을 합치지 않는다 — 현장에서 알아듣기 쉬운 쪽이 낫고, 배지 존재 확인은 단말 인증(쿠키) 뒤에서만·단말 배지 제한 1분 10회로 묶인다. K-b 제한(429) 뒤 시도는 `BADGE_THROTTLED` 로만 남기고 `POP_KIOSK_EXIT_FAIL` 을 겹쳐 쓰지 않는다 — 중복 기록 없이, 접속 로그 화면에서 두 이벤트를 함께 조회하도록 안내.
- **화면 연결(2026-11-02)**: POP 홈이 sys 공통 `hb.kiosk`(#779)를 붙였다 — `attach()`(열릴 때 잠금 + 구석 길게 누르기 대화상자), 렌더마다 `worker(s.workerCd)`(작업자 바뀜 = 다시 잠금), 대화상자가 열려 있으면 스캔 = 감독자 배지. 다리 없는 브라우저에서는 아무 일도 하지 않는다(docs/testing/mes.md).

### 4.53 데모 이력 시더 — 지난 90일 지시·실적·파라미터·완제품 입고 (2026-11-03 — docs/61 10장 "데모" 결정, docs/29 2026-11-03 사라진 작업 다시 만들기, 마이그레이션 없음)

화면을 채울 이력이 없어 데모를 돌릴 수 없었다. 생산 현황판(C1, docs/61)과 CMS KPI 의 성능·품질·OEE(docs/64 7장 빈칸)가 같은 이력을 읽는다. CMS 시더 `CmsDemoFlow` 와 같은 방식(local·demo 프로필만, 서비스 길, 비어 있을 때만, `TenantRunner`, `BizClock` 기준 지난 N일)으로 다시 만들었다.

| 항목 | 설계 |
|---|---|
| 흐름 | `MesDemoFlow`(module.production) — 데모 품목 2개: 프레스 `64311-DM100`(블랭킹·드로잉, PR-1200)·용접 ASSY `64310-DM100`(스폿 1·2 = SPOT-101·102, 전착(외주), 최종 검사). 지난 90일 평일마다 지시 2건 |
| 지시 | 오늘 계획으로 `createAs` → `releaseAs`(라우팅·BOM 이 오늘 유효 — 데모 기준정보는 첫 기동일부터 유효) → 발행 뒤 일정 변경(`WoService.update`)으로 계획일 = 그날 → 공정 위치의 데모 설비 배정(`assign`). **발행 시각은 지금**이다 — V033 스냅샷 트리거가 `released_at = now()` 를 "발행 트랜잭션" 표식으로 쓴다(가드를 약하게 하지 않음) |
| 실적 | 공정마다 착수·종료 = `PopWorkService.eventAt`, 실적·파라미터 = `resultImport`·`paramImport`(시더 전용 — 아래). 근무조 실적 둘(주간 19:55 = 85%, 야간 21:50 = 나머지). 착수 게이트(자격·QMS)는 그대로 돈다(ack) |
| 양 | 그날 그 설비 가동(PRD) 초 × 성능(80~93%, 고정 씨앗) ÷ 표준 사이클 — CMS 시더가 먼저 넣은 상태 원장이 있으면 그 시간, 없으면 15.5시간. 앞 공정 양품 = 다음 공정 처리 합(보존식 허용 0% 통과). 폐기는 공정별 불량 유형 가중치(파레토 1위 = 스폿 누락, 드로잉 크랙이 다음) |
| 파라미터 | 스폿 공정 용접 전류 3번·통전 시간·가압력 1번, 대체로 규격 안, 1/60 정도 규격 밖 → NG |
| 끝맺음 | 공정 완료판정(`closeAs`) → 지시 완료(`completeAs`, ERP 정책이 있으면 그 송신 그대로) → 용접 ASSY 마지막 공정 양품 LOT 을 그날 22:10 으로 FG-A01 입고(`ReceiptService.receiveAt`, 재고 연령) → 7일 넘은 지시 마감(CLOSED) |
| 오늘 | 평일 09시 뒤 기동이면 용접 지시 1건 진행 중(첫 공정 착수 + 주간 부분 실적) |
| 건너뜀 | 데모 설비(PR-1200·SPOT-101·SPOT-102)에 실적이 하나라도 있거나 "데모 이력" 비고 지시가 있으면 0. 데모 설비·확정 라우팅 2개·작업자·FG-A01 이 없으면 0. 한 트랜잭션 |
| 시더 전용 길(API 없음) | ① `PopWorkService.eventAt(…, at)` — 전이·게이트·멱등 그대로, 발생 시각만(지금보다 뒤 거부). ② `resultImport`·`paramImport` — 검증·LOT·계보·불량 합 그대로, 발생 시각 필수·지시 발행 전 시각 허용·`entry_method = IMPORT`(이관 표시 — 원장 CHECK 가 이미 허용). ③ `ReceiptService.receiveAt(…, at)` — 입고·재고 원장 발생 시각만, ERP 송신 없음, entry_method 는 KEY(`prd_receipt` CHECK 에 IMPORT 없음 — V055, 바꾸지 않음). 모두 `ChkRunService.completeAt`(CMS 시더) 와 같은 모양 |
| 순서 | `MesDemoSeeder`(local·demo, ApplicationReadyEvent `@Order(DemoSeedOrder.MES)`) — 공용 순서 상수 `DemoSeedOrder`: CMS(100) → **MES(150)** → QMS 공정검사(200) → QMS 이력 보충(210) → WMS(300) → PMS → SRM → PLM. CMS 뒤인 이유 = 가동 시간을 CMS 상태 원장에서 읽음, QMS·WMS 앞인 이유 = 그쪽이 MES LOT·완제품 재고를 씀. 실행자는 `DemoSeedSupport.admin` |
| 버린 대안 | (가) 스냅샷 트리거에 "과거 발행" 예외(설정값 GUC) — 발행 가드가 약해진다. (나) 매퍼로 원장 직접 INSERT — 서비스 규칙(보존식·게이트·LOT·재고)을 건너뛰어 화면과 다른 모양이 생긴다. (다) 실적만 넣고 이벤트·완료판정 생략 — 공정 상태·지시 상태가 화면에서 어긋난다 |

시험: `DemoHistoryFlowTest`(3 — CMS→MES→QMS→WMS 한 회사, 원장 두 시각·IMPORT, KPI 성능·품질·OEE(PR-1200·SPOT-101·102, CO2-201 은 실적 없음 = 빈칸), 파레토, 멱등, A사로 B사 / 기준정보 없는 회사 0 / 시더 전용 길 미래 거부). 시드 한 번 ≈ MES 60~75초(지시 129·실적 771·파라미터 640).

### 4.54 키오스크 풀기 실패 알림 — KS9 서버 계약 (2026-11-04 — docs/50 7.2 KS9 계약, docs/29 2.6 사라진 작업 다시 만들기, 임시 V235(임시 V9101): 접속 로그 이벤트 코드 하나)

QA K-d: 서버는 `POP_KIOSK_EXIT` OK 로 적었는데 화면이 풀지 못하는 경우가 있다 — 응답을 기다리는 사이 작업자가 바뀌어 다시 잠갔거나(`stale`, KS8) 기기가 `kiosk(false)` 를 거절(`refused`). 화면 `hb.kiosk`(sys)는 이미 응답의 `exitRef` 로 알림을 한 번 보낸다(없으면 안 보냄). 이 절은 그 서버 쪽이다. 계약은 docs/50 KS9 그대로이고, 아래 "정한 것" 은 계약이 비워 둔 구현 방식이다.

| 항목 | 정한 것 |
|---|---|
| `exitRef` | `/api/pop/kiosk/exit` 응답에 추가. **128비트 임의 값(base64url 22자)** — 빠져나올 때마다 새 값. 접속 로그 id 는 쓰지 않았다(순번이라 추측 가능, 로그 쓰기가 연결 반납 뒤라 응답 때 id 를 모름) |
| 가리키기 | 빠져나오기 기록 `POP_KIOSK_EXIT` 의 detail 끝에 `ref=…` (`worker=S1 ref=…`). 새 표 없음 — 4.52 ③ "기록 = 접속 로그만" 을 지킨다 |
| `POST /api/pop/kiosk/unlock-failed` `{exitRef, reason}` | POP 체인(단말 쿠키, POP·PDA 공용), 작업자 로그인 불필요(배지 화면에서도). `reason` ∈ {`stale`, `refused`} 정확히(그 밖·없음 400 `reason`/CODE), `exitRef` 없음 400 REQUIRED, 모양이 다르면 404 |
| 같은 단말만 | 그 회사(RLS) 접속 로그에서 **행위자 = 이 단말**(`t{id}:{코드}`)·`POP_KIOSK_EXIT` OK·**최근 1시간**·detail 끝 ` ref={exitRef}` 인 행을 찾는다 — 없으면 404(다른 단말·다른 회사·모르는 값·1시간 지난 값 구분 없음). 끝 일치는 `right()` 로(LIKE 와일드카드 없음), 기간은 시간 인덱스(comp_cd, occurred_at). 1시간 = 다시 잠그기 상한(`hb.kiosk` 3600초) — 알림은 응답 직후라 넉넉하다 |
| 멱등 | `sys_inbox`(소비자 `POP_KIOSK_UNLOCK_FAIL`, 메시지 = exitRef) — 두 번째는 사유가 달라도 200 아무것도 안 함, 동시 두 번도 기록 하나. 404 인 시도는 멱등 기록을 남기지 않는다(남의 시도가 내 알림을 막지 못함) |
| 기록 | 접속 로그 `POP_KIOSK_UNLOCK_FAIL`(FAIL, 심각도 WARN), detail `worker={감독자} ref={exitRef} reason={stale\|refused}`, 행위자·client_type = 단말. 공통코드 `ACCESS_EVENT` 한 줄 + ko/en 문구(임시 V9101) |
| 응답 | `{"result":"OK","data":null}` — 화면은 본문을 쓰지 않는다 |

버린 대안: (가) exitRef = 서명 토큰(HMAC, 표 없이 확인) — 서버 비밀 키 관리가 새로 생기고, 멱등은 어차피 저장이 필요하다. (나) 단말 세션 속성에 보관 — 작업자 바뀜·세션 만료와 엮여 "늦은 응답" 경우에 오히려 잃을 수 있다. (다) 새 표 — 4.52 ③ 결정(접속 로그만)과 어긋난다.

시험: `PopKioskExitTest` +5(한 번만·두 번째 무시·다른 빠져나오기 따로 / 입력 400·404·단말 아님 401·사무 403 / 다른 단말·PDA / A사로 B사 / 동시 두 번 기록 하나), 기존 첫 시험의 detail 단언에 `ref=` 반영.

**QA R4-a 2단계(MES·POP·영업, 같은 날)**: 필수 사유 칸 판정을 플랫폼 `VisibleText` 로 — `LotService`·`WoService`·`ResultViewService`·`WoImportTemplate`·`OrderService` 의 자체 `blank()` 를 지우고 `VisibleText.isBlank`, 공용 입구 `OrderService.text` 는 `VisibleText.optional` 로 위임(입고·계보 정정, LOT 보류·해제·재라벨·재작업, POP 실적·파라미터 정정, 작업지시 취소, 수주 행 마감·취소 사유가 이 입구 — APS·PLM 도 부르므로 이름을 남겼다, 가드 기준선 1), `MesLotHoldPort`(QMS 출처 보류 사유)·`TerminalService.revoke`(단말 폐기 사유)·`PopWorkService` 멈춤 사유 코드는 `VisibleText.strip`/`isBlank`. 오류 코드는 그대로(REQUIRED·FORMAT·TOO_LONG). 바뀐 것: U+200B·NBSP·U+3000 만인 사유 = REQUIRED, 글자 수 상한은 코드 포인트, 앞뒤의 보이지 않는 글자(제어 문자 포함)는 걷어낸다(전에는 앞뒤 제어 문자가 FORMAT). `VisibleTextGuardTest` 기준선에서 pop·production 7줄 지움, `OrderService` 2 → 1.

### 4.55 현장 단말 화면 현황 — POP·PDA 에 있는 것과 없는 것 (2026-11-10 조사, 기준 = 병합 묶음 e · V260)

사용자 지시 "현장 단말용 화면도 개발해야 한다"(2026-11-10)의 출발점. 코드로 확인한 것만 적는다(시험 수는 `@Test` 개수).

**POP(`/pop`, 생산 단말 — `module/pop`, `templates/pop/home.html`, `static/js/pop/home.js`)** — 화면은 하나이고 일은 패널로 바뀐다.

| 일 | 화면(패널) | API | 시험 | 상태 |
|---|---|---|---|---|
| 단말 등록 | `/pop/activate` | `POST /api/pop/activate` | `PopTerminalTest` 11 · `PopTerminalTypeTest` 4 | 있음 |
| 배지(+PIN)·언어·작업자 바꾸기 | 배지·PIN 패널 | `/api/pop/badge`·`/worker/logout`·`/lang`·`/session` | `PopTerminalTest` · `PopPinPoolTest` 6 | 있음 |
| 내 할 일 | 작업 목록 | `GET /api/pop/home` | `PopTerminalTest`·`PopWorkTest` 13 | 있음 — **정렬은 우선순위·계획일(APS 순서 아님)** → 4.56 |
| 착수·멈춤·재개·종료 | 공정 패널 | `POST /api/pop/procs/{id}/events/{event}` | `PopWorkTest` | 있음 |
| 실적 확정·자재 LOT 투입·정정 | 공정 패널 | `/procs/{id}/results`·`/results/{id}/correct`·`/lots` | `PopWorkTest` · `PopApprovalTest` 9 | 있음(실적만 오프라인 대기열) |
| 파라미터 실측·정정 | 공정 패널 | `/procs/{id}/params`·`/params/{id}/correct` | `PopWorkTest`·`PopApprovalTest` | 있음 |
| 감독자 승인 | 승인 패널 | `POST /api/pop/approvals` | `PopApprovalTest` | 있음 |
| 고장 신고(+사진) | 고장 신고 패널 | `/api/pop/breakdown/**` | `PopBreakdownTest` 6 · `PopBreakdownPhotoTest` 3 · `PopBreakdownNoneTest` 2 | 있음 |
| 키오스크 빠져나오기 | `hb.kiosk` 대화상자 | `/api/pop/kiosk/exit`·`/unlock-failed` | `PopKioskExitTest` 11 | 있음 |
| **일일 배정 순서** | — | — | — | **없음 → 4.56(이번에 구현)** |
| **검사 입력(초·중·종물, 공정 검사)** | — (사무 `qms-insp` 만) | — | — | **없음 → 4.57(QMS 계약 필요 — 설계·질의)** |
| **설비 점검(예방점검 실행)** | — (사무 `cms-chk-run`·`cms-mwo` 만) | — | — | **없음 → 4.58(CMS 계약 필요 — 설계·질의)** |
| 공정 완료판정 | — (사무 `prd-wo` 완료판정) | — | — | 없음 — 설계상 사무 몫(4.9), 이번 범위 아님 |

**창고 PDA(`/pop/wh`, `module/wms/pda`, `templates/wms/pda-home.html`, `static/js/views/wms/pda-home.js`·`pda-loccheck.js`)** — 구현은 이번 범위가 아니다(장비 연동 세션이 E3·E4 뒤). 빈 곳만 적는다.

| 창고 일 | PDA | API(`/api/pop/wh`) | 시험 | 사무 화면 | 빈 곳 |
|---|---|---|---|---|---|
| 입고 | 있음 | `/rcv/**`(스캔·ASN·발주 행·위치) + `POST /rcv` | `PdaReceiveTest` 6 | `wms-rcv` | 입고 라벨 인쇄, 발주 없는 입고의 공급사 고르기(docs/30 12.12.15 남은 것) |
| 이동(적치) | 있음 | `/scan`, `POST /moves`(오프라인 대기열·재전송) | `PdaWarehouseTest` 7 · `PutawayCheckTest` 4 | `wms-move` | 적치 위치 지시 없음(경고만), 이동 정정은 사무 |
| 피킹·출하 | 부분 | `/ships/**`, `POST /ships/{id}/picks`, 특이사항 확인 | `PdaPickTest` 4 · `ShipNoteTest` 일부 | `wms-ship`·`wms-ship-note` | 팔레트 구성·출하 확정은 사무 |
| 실사 | 부분 | `/counts/**`(수량 입력) | `PdaWarehouseTest` | `wms-count` | 제출·반영·재실사는 사무 |
| 정위치 점검 | 있음 | `/loc-checks/**` | `PdaLocCheckTest` 8 | `wms-loc-check` | 정정 이동·확정은 사무 |
| 반품 | 부분 | `/returns/**`(공급사 반품 출고) | `PdaReturnTest` 4 | `wms-rtn-issue`·`wms-cust-rtn` | 고객 반품 입고 없음 |
| 라벨 재발행 | 없음 | — | — | `wms-label`·`wms-print-job` | PDA 인쇄 전체 |

그 밖: 이동만 오프라인 대기열(나머지는 온라인만), 카메라 바코드(docs/50 C5)는 없음. docs/46 11.2 의 "PDA 입고 없음"은 2026-11-01 구현(docs/30 12.12.15)으로 낡았다 — 그 문서는 이 세션 범위가 아니라 고치지 않고 보고에 적는다.

### 4.56 POP 일일 배정 순서 — APS 읽기 계약 연결 (2026-11-10 — docs/63 G5·AP-10 ⓐ, docs/38 18.4, 마이그레이션 없음)

APS 9단계가 낸 읽기 계약 `ApsDispatchQuery`(현행 계획 = 공장의 마지막 반영 계획)를 POP "내 할 일"에 연결한다. APS 표·매퍼는 건드리지 않는다(AP-10 ⓐ — MES 표도 무변경).

| 항목 | 정한 것 |
|---|---|
| 어디서 부르나 | 새 `PopDispatch`(module.pop) — `PopService.home`·`PopWorkService.view` 의 읽기 트랜잭션 안에서 `ApsDispatchQuery.of(provider).slots(공장, 이 단말 목록의 공정 id)`. APS 없는 설치본은 `NONE` → 아래 "계획 없음"과 같다 |
| 정렬 | 계획에 있는 공정이 먼저 — **계획 시작 시각 → 자원 코드 → 순번**. 계획에 없는 공정(계획 뒤에 발행된 지시 등)은 그 뒤에 **지금 순서 그대로**(우선순위·계획일·지시 번호·공정 순) |
| 계획이 없으면 | 자리 표가 비면 받은 목록 그대로 — 화면도 예전과 한 글자도 다르지 않다 |
| 표시 | 카드에 `계획 n번째 · 시작~끝 · 자원`(오늘이면 시:분, 다른 날이면 월-일 시:분 — 공장 시간대), 목록 머리에 `n건 · 계획 {번호} 순서`. 계획이 걸린 목록에서 계획 밖 공정은 "계획에 없음" |
| 순서 어김 | **막지 않는다(AP-10)**. 같은 자원에서 계획상 앞인 공정이 아직 시작 전이면 `앞선 작업 n건이 아직 시작 전`(보통 글자), 그런데도 이 공정이 시작됐으면 `계획 순서보다 먼저 시작`(경고 톤 — 뒤 순번 카드가 전부 노랗게 되지 않게 어긴 공정에만) — 글자뿐, 착수 확인 대화상자도 더하지 않는다(장갑 낀 손에 누름 한 번을 늘리지 않는다). 공정 화면에도 같은 줄 |
| "시작했다" | RUN·PAUSE·DONE — APS 배정표(`ApsDispatchService.STARTED`)와 같은 기준이라 사무 배정표와 단말이 같은 공정을 다르게 말하지 않는다. READY(종료 뒤 완료판정 대기)는 APS 와 같이 미착수로 센다(아래 한계) |
| 응답 | `TaskRow.plan`·`ProcView.plan` = `PlanSlot(planNo, seq, resCd, startAt, endAt, ahead, early)` — 없으면 null |

한계(적어 둠): ① 목록 상한 50은 정렬 **전**(우선순위·계획일 순)에 걸린다 — 한 작업장에 열린 공정이 50을 넘으면 계획상 이른 공정이 잘릴 수 있다(지금도 같은 상한, 넘으면 다음 과제) ② `ahead` 는 이 단말 목록 안에서만 센다 ③ 종료(READY) 뒤 완료판정 전인 앞 공정은 "아직 시작 전"으로 세어진다 — APS 배정표와 같은 정의를 따른 결과이고, 바꾸려면 APS 와 같이 바꾼다 ④ 순서·시각은 계산 때 값(계약 주석 그대로) — 반영 뒤 사람이 지시를 옮기면 다음 반영까지 다를 수 있다.

버린 대안: (가) `prd_wo_proc.plan_start_at` 칸(AP-10 ⓑ, 이미 기각) (나) 착수 때 순서 어김을 이벤트 원장 `gate_detail` 에 적기 — 원장 뜻(게이트 근거)이 흐려지고 AP-10 은 "표시만". 어김 집계가 필요해지면 APS 배정표가 이미 계산한다 (다) 계획 밖 공정을 우선순위로 계획 사이에 끼우기 — 두 정렬 기준이 섞여 작업자가 순서를 설명할 수 없다.

시험: `PopDispatchTest` 5(계획 없음 = 지금 순서 / 계획 순서 + 계획 밖 뒤 / 순서 어김 착수 200·표시·풀림 / 다른 작업장·**A사로 B사**·배지 없음 428·쿠키 없음 401 / 순수 정렬). 계약은 가짜 빈(@Primary) — 계약 자체(현행 계획·공장·RLS)는 APS `ApsFenceDispatchTest`.

### 4.57 POP 검사 입력 — 초·종물·순회·공정 검사 (2026-11-10 설계 — **QMS 계약·표 변경이 필요해 구현 전 질의**)

**지금 있는 것(코드 확인)**: 검사 실행은 사무 화면 `qms-insp`(`InspService`) 하나. ① 모든 쓰기가 `SamplingService.me()` = 사람 로그인(`LoginUser`)을 요구 — 단말 요청(`TerminalPrincipal`)은 401 ② `qms_insp` 는 **LOT 기준**(`lot_id NOT NULL`)이고 지시 공정·설비 칸이 없다(지시는 `lot_master.wo_id` 로만 닿는다) ③ 측정 원장 `qms_insp_meas` 에 `performed_by`(작업자)·`client_req_id` 가 없고 `entry_method` 는 KEY·IMPORT 뿐 ④ 검사 유형은 RECEIVE·PROCESS·FINAL·FIRST_OFF(초물)·LAST_OFF(종물)·PATROL(순회) — **"중물" 유형은 없다** ⑤ `module/qms/link` 에 POP 용 계약이 없다. 그래서 MES 만으로는 만들 수 없다.

**제안 — QMS 가 낼 계약 `FloorInspPort`(module.qms.link, 구현은 QMS)**. 고장 신고(`BreakdownReport`)와 같은 틀: POP 트랜잭션 안에서 부르고(MANDATORY), 행위자는 단말(`t{id}:{코드}`), 작업자·단말 id 를 받아 QMS 가 같은 회사·공장을 다시 본다. 없는 설치본은 `NONE` → POP 이 버튼을 숨긴다.

| 메서드 | 뜻 |
|---|---|
| `due(DueQuery(plantCd, woId, woProcId, procId, itemId))` → `List<DueInsp>` | 이 공정에 **오늘 유효한 검사계획**이 있는 유형(FIRST_OFF·PATROL·LAST_OFF·PROCESS)과 그 유형의 열린 검사(이 지시의 LOT 기준)·마지막 판정 |
| `open(OpenForm(inspType, lotNo, procId, workerId, terminalId, clientReqId))` → `InspRef(inspId, inspNo, duplicate)` | 검사 만들기(계획 스냅샷·샘플 수는 지금 `create` 그대로). 같은 `clientReqId` = 처음 검사 |
| `detail(inspId, terminalId)` → 특성 줄(규격·샘플 수·계측기 종류)·살아 있는 측정값·미리 본 판정 | 단말 공장 밖 검사는 NOT_FOUND |
| `gauges(gaugeClass)` | 계측기 선택지(스캔으로도 고름 — 자산 코드) |
| `measure(MeasureForm(inspId, inspLineId, entries[sampleNo, value/okYn, gaugeAssetId], occurredAt, entryMethod, workerId, terminalId, clientReqId))` → `MeasureResult(duplicate, 줄 미리보기)` | 측정 원장에 추가. 칸 오류는 지금 `checkEntry` 그대로 |
| `voidMeasure(inspId, measId, workerId, terminalId, clientReqId)` | 무효 행(정정 = 무효 뒤 다시 입력 — 원장 규칙 그대로) |

**QMS 표 변경(QMS 대역 임시 V92xx — QMS 몫)**: `qms_insp_meas` 에 `performed_by bigint`(작업자, `mst_worker`)·`terminal_id`·`client_req_id`(부분 유일) 추가, `entry_method` 에 SCAN(계측기 스캔·디지털 게이지 입력) 허용 여부. `qms_insp` 에 만든 작업자·요청 id. `recorded_by` 는 지금도 글자 칸이라 단말 행위자가 그대로 들어간다.

**MES(POP) 몫**: 공정 패널에 [검사] 버튼(계약이 NONE 아니고 `due` 가 있을 때) → 검사 패널(고장 신고와 같은 틀: 도구 줄 + 카드). 특성마다 샘플 칸(계량 = 공용 키패드, 계수 = 합격/불합격 두 큰 버튼), 계측기는 스캔(`hb.scan`) 또는 선택, 규격 밖 값은 저장 즉시 경고 톤. API `/api/pop/insp/**`(`@ProgAuthExempt`, 단말 작업장 + 지금 작업자), 멱등 `client_req_id`, 온라인만(오프라인 대기열은 실적만 — 4.20).

**정해야 할 것(사용자·QMS)**

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| PI-1 | QMS 쪽 계약·표 변경을 누가 하나 | **QMS 세션**이 `FloorInspPort` + V92xx 를 내고, MES 는 그 뒤 화면·API(가짜 빈으로 먼저 개발 가능) | 이 세션이 QMS 패키지·대역까지 한 번에(빠르지만 "남의 표·매퍼" 규칙 예외) |
| PI-2 | "중물" | 새 유형을 만들지 않고 **순회(PATROL)** 를 중물로 쓴다(검사계획 주기 `EVERY_N`·`EVERY_HOURS` 가 이미 있음) | 유형 `MID_OFF` 추가(QMS CHECK·코드·계획 화면 변경) |
| PI-3 | 초물은 LOT 이 생기기 전에 잰다 — 검사는 LOT 필수 | 지금 모델 유지: **첫 실적(소량)을 확정해 생긴 산출 LOT** 으로 초물 검사를 연다(화면이 "방금 만든 LOT" 을 미리 고름). 착수 게이트(Q9-1)는 그대로 | `qms_insp.lot_id` 를 비울 수 있게 하고 지시 공정 칸 추가(QMS 모델 변경 큼) |
| PI-4 | 판정(OK/NG 확정)을 누가 | 단말은 **측정 입력까지**, 판정은 사무 `qms-insp`(품질 담당 권한 `SAVE`) — 판정이 재고·SPC·NCR 로 이어진다. 단말에는 미리 본 판정만 보인다 | 샘플이 다 차면 단말에서 자동 판정(자주검사 현장에 맞지만 권한 경계가 흐려짐) |
| PI-5 | 계측기 | 검사계획이 VISUAL 이 아니면 계측기 필수(지금 규칙) — 단말은 스캔 우선, 교정 지난 계측기는 QMS 가 거부(`GAUGE_BLOCKED`) | — |

**검토·결정 (2026-11-10, 상위 세션 경유 — 사용자가 직접 답함)**

| # | 결정 |
|---|---|
| PI-1 | **추천안(A)** — QMS 세션(브랜치 `claude/qms-floor-insp`)이 `FloorInspPort` 와 QMS 대역 마이그레이션을 낸다. MES 는 그 인터페이스를 받아 가짜 빈으로 POP 화면·API 를 먼저 만든다 |
| PI-2 | **추천대로** — 중물 = 순회(PATROL), 새 유형 없음 |
| PI-3 | **추천대로(자동)** — 초물은 첫 실적으로 생긴 산출 LOT 으로 검사가 열린다 |
| PI-4 | **추천에서 바꿈** — 사용자: "측정값을 입력하면 합격·불합격은 자동으로 판정되는 것 아니냐". 측정값을 넣을 때마다 자동 판정을 보여 주고, 샘플을 다 채워 **전부 OK 면 그 자리에서 자동 확정**, **NG 면 작업자가 [불합격 확정]을 한 번 눌러야 확정**(오타 방지). 측정값 무효·검사 무효는 사무에서만. 규격 밖 값은 화면에 바로 표시 |
| PI-5 | 추천대로 |

**구현 결과 — MES 몫 (2026-11-10, 마이그레이션 없음 · 계약 파일은 QMS 브랜치 `claude/qms-floor-insp` 의 것을 그대로 받음)**

| 자리 | 한 것 |
|---|---|
| 계약 | QMS 가 낸 `qms/link/FloorInspPort`(docs/29_P3 53장) — `due`·`open`·`detail`·`gauges`·`measure`·`confirmNg`. 4.57 제안과 다른 점은 QMS 설계대로: 키 = 지시 공정(`woProcId`), 발생 시각은 서버, 단말용 무효 없음, 멱등은 `sys_inbox` |
| POP API | `/api/pop/procs/{공정}/insp`(열 수 있는 유형 + 유형 이름)·`/open`·`/gauges`·`/{검사}`·`/{검사}/meas`·`/{검사}/confirm-ng` — 공정 경로 아래라 POP 단말만(PDA 403). `PopInspService` 가 지금 작업자(428)·단말 작업장 공정(404 — 착수·실적과 같은 조회)을 보고 작업자 id·단말 id·요청 id 를 계약에 넘긴다 |
| 범위 | 검사는 늘 공정을 거쳐 연다 — 경로의 공정과 검사의 **공정 코드·품목**이 다르면 404(다른 작업장 공정의 검사 id 를 넣어 보는 것). 공장은 QMS 가 다시 본다(FI-2·FI-8) |
| 공정 화면 | `ProcView.insp`(QMS 구현 있음) → 화면이 열 수 있는 유형을 따로 물어 하나라도 있으면 [검사] 버튼 |
| 화면 | `inspPanel` + `static/js/pop/insp.js`(`// @ts-check`, `window.popHome` 으로 홈과 붙음). 유형 카드(초품·순회 · 중물·종품·공정 — 이름은 공통코드 `INSP_TYPE`): 열린 검사가 있으면 "이어 하기", 없으면 추천 LOT 으로 시작, 실적이 없으면 눌리지 않고 이유를 적는다. 특성 줄마다 규격·샘플 수·판정 배지, 계량 = 샘플 칸 + 공용 키패드(+ 부호 바꾸기), 계수 = 합격/불합격 큰 버튼 + [남은 샘플 모두 합격], 계측기 = 스캔 또는 고르기(교정 막힌 것은 못 고름, 같은 종류 다음 줄에 이어 씀). **규격 밖 값은 넣는 즉시 위험 톤**, 저장된 값도 규격 밖이면 위험 톤 |
| 저장 단위 | 줄마다 [이 줄 저장] — 저장한 값은 단말에서 지울 수 없어(무효는 사무) 샘플을 누르는 즉시 보내지 않고 한 번 모아 보낸다. 응답(미리보기)이 곧 새 화면 |
| 판정 | 서버가 전부 OK 로 자동 확정하면 "합격 확정" 안내. NG 면 화면 위에 [불합격 확정](위험 톤) + 확인 대화상자 한 번 |
| 스캔(`hb.scan`) | 숫자 = 고른 샘플 칸의 값(디지털 게이지 — 그 줄 `entryMethod` SCAN), 그 밖 = 계측기 코드(고른 칸의 줄, 없으면 계측기가 필요한 첫 줄). 검사 화면이 열려 있는 동안 스캔은 배지로 읽지 않는다 |
| 재전송 | 열기·줄 저장·불합격 확정 모두 같은 내용이면 같은 요청 id(응답을 못 받았을 때 다시 누르면 처음 결과), 값·계측기를 바꾸면 새 id |

시험: `PopInspTest` 5 — 가짜 계약 빈(@Primary): 검사 플래그·유형·이름 / 열기·측정·불합격 확정이 작업자·단말·요청 id·값(음수·SCAN)을 그대로 넘김 / QMS 상태·칸 오류 그대로(409·400) / 작업자 없음·쿠키 없음·사무 로그인·PDA / 작업장 밖 공정·다른 공정의 검사·**A사 공정을 B사 단말로** = 404(계약에 닿지 않음). 검사 만들기·측정 원장·자동 판정·멱등·RLS 는 QMS 의 계약 시험(docs/29_P3 53.3). 실제 구현과의 관통은 QMS 브랜치(`4064be71`)를 **로컬에서만** 합쳐(push 안 함) 브라우저로 확인했다 — 초품 자동 합격 확정·순회 불합격 확정, 기록은 docs/testing/mes.md. 합친 상태에서 `pop.*`·`guard.*`·`qms.FloorInsp*` 157건 통과(메시지 파일 끝 블록이 서로 부딪히는 것 말고 충돌 없음).

PI-4 가 바뀌어 계약에 판정이 더해진다: `measure` 응답이 자동 확정 여부를 돌려주고(전부 OK = 확정), NG 는 `confirmNg(inspId, rowVer, workerId, terminalId, clientReqId)` 로 확정한다. 단말용 `voidMeasure` 는 뺀다(무효는 사무). 서명은 QMS 세션과 맞춘다.

### 4.58 POP 설비 점검 — 예방점검 실행 (2026-11-10 설계 — **CMS 계약·표 변경이 필요해 구현 전 질의**)

**지금 있는 것(코드 확인)**: 예방점검 실행은 사무 `cms-chk-run`(CHECK 방식)·`cms-mwo`(WO 방식, `ChkRunService`). ① 결과 저장·완료·건너뛰기가 모두 `CmsSupport.me()` = 사람 로그인 — 단말은 401 ② `cms_chk_run.performed_by` 는 `sys_user` FK(작업자·단말 칸 없음), 요청 id 없음(멱등은 `row_ver` 뿐) ③ 실행 행은 예정 생성 때 이미 있다(점검표 항목 스냅샷 포함, "시작" 단계 없음) ④ 수치 판정은 서버(경계 포함), 필수 항목은 완료 때 확인 ⑤ WO 방식은 보전 작업지시 완료가 점검을 끝낸다(보전 담당 일).

**제안 — CMS 가 낼 계약 `PmCheckPort`(module.cms.link, 구현은 CMS)** — `BreakdownReport` 와 같은 틀(MANDATORY·단말 행위자·작업자/단말 id·같은 공장 확인·`NONE`).

| 메서드 | 뜻 |
|---|---|
| `due(plantCd, equipIds)` → `List<DueRun(runId, planNm, equipId·코드·이름, dueDate, state UPCOMING/DUE/OVERDUE, rowVer)>` | 그 설비들의 **CHECK 방식** 열린 점검(예정일이 선행 기간 안) |
| `detail(runId, terminalId)` → 항목(이름·방법·판정 방식·상하한·단위·필수)·입력된 값·`rowVer` | 단말 공장 밖은 NOT_FOUND |
| `save(SaveForm(runId, rowVer, results[seq, value/judge/valueText, remark], workerId, terminalId))` → 새 `rowVer`·항목별 판정 | 지금 `saveResults` 규칙 그대로(수치 판정은 서버) |
| `complete(CompleteForm(runId, rowVer, workerId, terminalId, clientReqId))` → `Completed(result OK/NG, duplicate)` | 완료(필수 항목 확인 → 다음 예정 계산). 같은 `clientReqId` = 처음 결과 |

**CMS 표 변경(CMS 대역 임시 V96xx — CMS 몫, V151 이 `cms_mwo` 에 한 것과 같은 모양)**: `cms_chk_run` 에 `performed_worker_id`(`mst_worker`)·`performed_terminal_id`(`sys_terminal`)·`complete_req_id`(부분 유일) + "사람(`performed_by`) 또는 작업자·단말 한쪽" CHECK, 같은 회사·공장 확인 트리거.

**MES(POP) 몫**: 작업 목록 도구 줄에 [설비 점검](고장 신고 옆, 계약이 NONE 아니고 PDA 아님) → 고장 신고와 **같은 틀**의 패널: 설비(단말 작업장 설비 — 고장 신고의 설비 범위 조회를 그대로 씀, 설비 바코드 스캔으로도 고름) → 열린 점검 → 항목마다 큰 버튼(양호/불량)·키패드(수치)·글 → [점검 완료]. 불량 항목이 있으면 완료 뒤 [고장 신고]로 이어 가기(그 설비를 미리 고름). API `/api/pop/pm/**`, 온라인만.

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| PP-1 | CMS 쪽 계약·표 변경을 누가 하나 | **CMS 세션**이 `PmCheckPort` + V96xx, MES 는 화면·API | 이 세션이 CMS 패키지·대역까지 |
| PP-2 | 단말에서 할 점검 범위 | **CHECK 방식만**(작업자 자주 점검). WO 방식은 보전 담당이 사무 `cms-mwo` 에서(부품·시간·작업지시 완료와 묶임) | WO 방식도 단말에서 결과 입력만 |
| PP-3 | 건너뛰기 | 단말에서는 **못 한다**(사무는 DELETE 권한 필요 — 같은 무게) | 감독자 승인(4.37)으로 허용 |
| PP-4 | 불량(NG) 항목 뒤 | 완료는 그대로 NG 로 닫고, 화면이 [고장 신고]로 이어 준다(후속 작업지시 `followUp` 은 사무) | 단말에서 후속 보전 작업지시 자동 생성 |
| PP-5 | 사용량 주기(USAGE) 점검 | CHECK 방식이면 날짜 주기와 같이 목록에 보인다(완료 때 사용량 값은 CMS 가 지금처럼 읽음) | 단말에서 제외 |

**검토·결정 (2026-11-10, 상위 세션 경유 — 사용자가 직접 답함)**

| # | 결정 |
|---|---|
| PP-1 | **다른 안(B) 승인** — CMS 세션이 보관돼 없으므로 이 세션이 `module.cms` 의 계약 `PmCheckPort` 와 CMS 대역 임시 V 까지 만든다. 범위는 계약과 `cms_chk_run` 의 작업자·단말·요청 id 칸에 한정, `cms/edge` 는 건드리지 않는다(장비 연동 세션 E4 작업 중) |
| PP-2 | 추천대로 — CHECK 방식만 |
| PP-3 | **추천과 다름** — 단말에서도 **사유 코드를 고르면 건너뛸 수 있다**(공통코드 `CMS_SKIP_REASON`, V258). 사유 없이는 불가, 누가·언제 건너뛰었는지 기록 |
| PP-4 | 추천대로 — NG 는 고장 신고로 잇기(설비·내용이 채워진 채, 신고 여부는 작업자가 고른다) |
| PP-5 | 추천대로 |

**구현 결과 (2026-11-10, V266·임시 V9600 — CMS 대역, 병합 때 재번호)**

| 자리 | 한 것 |
|---|---|
| 계약(CMS) | `cms/link/PmCheckPort` — `due(공장, 설비 id들)`·`detail(runId, terminalId)`·`complete(…)`·`skip(…)` + `NONE`. 설계안에서 바뀐 점: **결과 저장과 완료를 한 요청으로**(`complete` 가 항목 결과를 같이 받는다) — 항목마다 저장하면 `rowVer` 가 매번 바뀌어 재전송 멱등이 깨지고, 중간까지만 저장된 점검이 남는다. 점검표는 짧아 한 번에 보내도 된다 |
| 표(CMS, V266) | `cms_chk_run` 에 `floor_worker_id`(`mst_worker`)·`floor_terminal_id`(`sys_terminal`)·`floor_req_id`(회사 안 유일). CHECK: 셋이 함께·끝난 실행(DONE·SKIPPED)에만·`performed_by`(사무 계정) 없음. 가드 트리거: 작업자는 같은 회사·사용 중, 단말은 같은 회사 + 그 설비의 공장. 끝난 실행은 읽기 전용(V065 가드 그대로)이라 따로 고정 규칙이 필요 없다 |
| 서비스(CMS) | `ChkRunService` — 끝내는 주체를 `By`(사무 계정 또는 작업자 + 단말 + 요청 id)로 묶어 `finish`·`skipRun`·`advance` 가 한 길을 쓴다(판정·필수 검사·다음 예정은 한 벌). 결과 검증은 `applyResults` 로 빼 사무 `saveResults` 와 단말 `floorComplete` 가 같이 쓴다. `floor*` 메서드는 MANDATORY(호출자 트랜잭션). 구현 빈 `CmsPmCheckPort` |
| 사무 화면(CMS) | 점검 실행 목록·상세의 수행자 = 사무 계정이 없으면 **작업자 이름·코드**(`runCols` coalesce) — 단말에서 한 점검이 빈칸으로 보이지 않게 |
| 멱등 | 실행 행을 잠근 뒤 판단: 이미 끝났고 `floor_req_id` 가 같으면 처음 결과(`duplicate`), 다르면 409 `CMS_RUN_STATE`. 같은 요청 id 가 다른 실행을 끝냈으면 409 `DUPLICATE_REQUEST`. 동시 두 번도 잠금 순서로 하나만 끝낸다(`sys_inbox` 를 쓰지 않음 — 키가 행 안에 있다) |
| 범위 | CMS: 단말 공장 = 설비 공장, 설비 대상·CHECK 방식만(그 밖은 NOT_FOUND). MES: 단말 작업장 설비(고장 신고와 같은 조회) 밖이면 404 |
| POP API | `GET /api/pop/pm/runs`·`GET …/{id}`(+ 건너뛰기 사유 선택지)·`POST …/{id}/complete`·`POST …/{id}/skip` — `@ProgAuthExempt`, POP 단말만(`POP_PATHS`, PDA 403), 지금 작업자 필수(428). 세션에 `pm` 플래그(CMS 없음·PDA = 거짓 → 버튼 숨김) |
| 화면 | `pop/home.html` 의 [설비 점검] 버튼 + `pmPanel`(고장 신고와 같은 틀: 도구 줄 + 카드), `static/js/pop/pm.js`(`// @ts-check`). 양호/불량 큰 버튼(64px)·수치는 공용 키패드·글 항목, 기준 밖 값은 바로 "기준 밖"(판정은 서버), 설비 바코드 스캔으로 그 설비 점검 열기, [이번 점검 건너뛰기] → 사유 버튼 + 메모 → [건너뛰기 확정], 끝난 뒤 불량이면 [고장 신고로 이어 가기](설비 선택·내용 "점검 불량: 항목…" 채움) |
| 홈과 붙는 방법 | `home.js` 가 `window.popHome`(패널 등록·전환·피드백·스캔 넘김·키패드·고장 신고 열기)을 내고 붙는 화면 파일이 그것만 쓴다 — 892줄 파일에 화면을 더 얹지 않고, 붙는 파일은 타입 검사 대상이 된다. 검사 입력(4.57)도 같은 문을 쓴다 |

하지 않은 것: 자산(금형·지그) 대상 점검은 단말에서 안 보인다(설비 대상만 — 자산은 설치된 설비를 따라가야 해서 다음), 항목별 임시 저장 없음(화면을 떠나면 입력이 사라진다), 오프라인 대기열 없음, 사진 없음.

시험: `PopPmTest` 7(목록 범위·완료·작업자/단말 기록·다음 예정·사무 화면 수행자 / 필수·형식·규격 밖 NG·낡은 rowVer / 재전송·동시·다른 실행에 같은 id / 건너뛰기 사유 필수 / 작업자 없음·쿠키 없음·사무 로그인·PDA·작업장 밖·WO 방식·없는 id / **A사로 B사** / 직접 SQL). CMS 기존 시험(`CmsPm*`·`CmsBreakdown*`·`CmsDemoFlowTest`·`CmsGridExcelTest`)으로 사무 경로 회귀 확인.

## 5. 화면 계열

| 계열 | 화면 | 메모 |
|---|---|---|
| 사무(셸) | 근무조·작업자·자격, 수주·내시, 작업지시(목록·상세·발행·스냅샷 보기), LOT 추적, 설비 상태 타임라인, LOT 역추적 보고서, 원장 조회(실적·계보) | 기존 표준 화면 구조 + 원장 조회는 기간 조건 필수 |
| POP(`/pop/**`) | 단말 시작(작업장 바인딩) → 작업자 배지 → 작업장 홈(대기 지시·진행 중) → 착수(자격 게이트·자재 스캔) → 실적 입력(양품/불량 키패드, 불량 유형 버튼) → 종료 → 결과 요약 | `hb.pop`, 터치 대상 최소 48px, 키보드 없이 완결, 오프라인 대기 건수 상시 표시, 언어 1탭(ko/en/vi) |

## 6. 다른 단계와의 경계

| 대상 | P2 에서 | 넘기는 곳 |
|---|---|---|
| 재고 수량·차감 전략·로케이션 이동 | 세지 않는다(입고는 `prd_receipt` 기록만) | P4 WMS |
| 검사·품질 게이트(초품 미판정·교정 만료) | 게이트 **자리**(착수 게이트 확장점)만, 자격 게이트 하나만 구현 | P3 QMS |
| 라벨 인쇄 | LOT 라벨 출력 요청을 아웃박스 topic 으로만(인쇄 큐는 P4) | P4 |
| CMS 화면·OEE·알람 분석 | 원장·이벤트 스키마만 | P5 |
| 실제 설비 프로토콜·태그 매핑 | 가짜 수집기만 | P6 |
| ERP 실제 어댑터(이카운트 등) | 엑셀 내보내기 어댑터 + 가짜 ERP | P6 |

## 7. 진행

| 단계 | 상태 | 날짜 | 테스트 |
|---|---|---|---|
| 1 작업일·근무조·작업자·자격(V030) | ✅ | 2026-10-20 | 673건, 신규 WorkDayResolverTest 6·WorkforceTest 6·시드 1. 브라우저 1366·390, 게이트 결과에 정책 상한 표시 |
| 2 수주·내시(V032) | ✅ | 2026-10-20 | 731건, 신규 SalesOrderTest 11·ForecastTest 7·시드 1. 브라우저 1366·390(ZT 데이터): EA 1.5 칸 오류 → 저장 → 행 추가·마감·취소 → 완료, 내시 1차→2차 비교(늘어남 2·줄어듦 4·새로 1·사라짐 1). **발견·수정**: 끝난 수주에 행 폼이 보임(`.hb-form[hidden]`) |
| 3 작업지시(V033) | ✅ | 2026-10-20 | 744건, 신규 WorkOrderTest 12·시드 1. 브라우저 1366·390(ZT 품목·라우팅·BOM): EA 100.5 칸 오류 → 저장 → 발행(공정·파라미터·소요 260 KG = 2.5 × 100 × 1.04) → 설비 배정. **발견·수정**: 기본 조회 기간(지난 30일)이 내일 시작 지시를 숨김 → 지난 7일 ~ 앞으로 30일 |
| 4 LOT·계보 원장(V034) | ✅ | 2026-10-20 | 754건, 신규 LotTest 10·시드 1. **10단 × 1,000 LOT 역추적 560ms**(정추적 10단 46ms). 브라우저 1366·390(ZT 자재 LOT): 등록 → 분할 30·20 → 병합 → 역추적 4줄, 데모 생산 LOT ← 너트 1,608 EA·와이어 20 KG |
| 5a POP 기반(V035) | ✅ | 2026-10-20 | 765건, 신규 PopTerminalTest 11. 브라우저 태블릿 1280×800·800×1280·390(ZT 단말·작업자): 활성화 → 배지 스캔 → 내 할 일 3건(자격 경고·OK), PIN 키패드·확정 3연타 → 요청 1건, 두 단말 같은 작업자, 폐기 즉시 끊김, 넘침 0·48px 미만 0. **발견·수정**: 근무조 코드만 보임 → 이름, 시각이 브라우저 로캘 → 회사 시간대 |
| 5b 착수·실적·투입(V036) | ✅ | 2026-10-20 | 773건, 신규 PopWorkTest 8. 브라우저 태블릿 1280·800·390(ZT 지시·자재 LOT): 착수(자격 경고 확인) → 키패드 실적 + 불량 유형 + 스캔 투입 → 확정 3연타 요청 1건, 오프라인 확정 → 재연결 자동 전송, 멈춤 사유 → 재개 → 정정(취소선) → 종료, 넘침 0·48px 미만 0 |
| 6 설비 상태·연결·Collector(V051) | ✅(main 병합) | 2026-10-20 | 1,055건, 신규 EquipEventTest 9 + LotTest·PopWorkTest 사유 제약 단언 + 이름 가드 3. 브라우저 1366·390(ZT 라인·설비·수집 장치): 수집 장치 등록 → API 키 한 번 → 가짜 수집기 8시간치(실제 HTTP) → 타임라인 41구간·UNKNOWN 회색·수집 누락 로그. **발견·수정**: 타임라인이 `hb.input.js` 를 안 실음, 역분개 사유 NULL 통과(F1) |
| 7a 완료판정·지시 완료·마감·생산 입고·재작업 처리(V055) | ✅(main 병합) | 2026-10-20 | 1,075건(트리거 이름순 실패 1건 고친 뒤 해당 클래스 재실행), 신규 CompletionReceiptTest 5. 브라우저 1366·390(ZT 지시): 처리 → 새 LOT, 보존식 불일치 표시, 완료판정·지시 완료·입고·정정·마감. **발견·수정**: BEFORE 트리거 이름순, 확인 문구 공백, 입고 서랍 표 넘침 |
| 7b LOT 역추적 보고서·LOT 재작업/수리(V056) | ✅(main 병합) | 2026-10-20 | 1,105건(최신 main 병합 뒤 단독 실행), 신규 LotReportTest 3. 브라우저 1366·390·인쇄(ZT 자재 → 2공정 → 입고): 보고서 8절, A4 PDF, 보류 → 재작업 새 LOT. **발견·수정**: 390 에서 머리 버튼·표 칸이 글자 단위로 접힘 |
| 7c ErpPort·가짜 ERP·ERP 업로드용 엑셀·데모 관통 시드(V066) | ✅(main 병합) | 2026-10-20 | 1,189건(main 18b9a41 병합 뒤, 2026-10-21), 신규 ErpTest 7 + DemoDataSeederTest 관통 1. 실제 기동: 데모 관통 시드 → 아웃박스 2건 SENT(가짜 ERP). 브라우저 1366·390(ZT 지시 → 완료·입고·정정 → 화면·엑셀). **발견·수정**: 엑셀 뒤 다시 조회가 "내려받았습니다" 문구를 지움 |
| 3c PLM 연결 — `lot_genealogy.change_id` → ECO·새 LOT 세 곳 `ChangeMarker.mark`(V077) | ✅(main 병합) | 2026-10-21 | 1,348건(main V073 병합 뒤), 신규 PlmChangeLinkTest 5. **잠금 순서 시험이 옛 순서(표시 뒤 자재 LOT 잠금)에서 교착 40P01 을 재현** → 자재 LOT 먼저 잠금으로 통과 |
| 홈 링크 조회 칸 3개 — `prd-wo` `status=ACTIVE`·`prd-lot` `holdOnly`·`sal-order` `dueFrom`~`dueTo`, 세 목록 열린 기간(V081 인덱스) | ✅(main 병합) | 2026-10-21 | 1,392건(main V078 병합 뒤), 신규 HomeLinkFilterTest 5(카드 숫자 = 링크 목록 건수) + 기존 WorkOrderTest·SalesOrderTest "기간 필수" 단언을 열린 기간으로. 브라우저 1366·390(ZT). **발견·수정**: 작업지시 화면이 링크 적용 뒤 기본 기간을 다시 넣어 홈 링크 기간을 덮음(1단계 항목 링크부터 있던 결함) |
| 입고 → 재고 원장 연결(WMS B1 계약, 마이그레이션 없음) | ✅(main 병합) | 2026-10-21 | 1,406건(main V081 병합 뒤), CompletionReceiptTest +1(입고·같은 요청·옮긴 재고 정정 409 STOCK_MOVED·되돌린 뒤 정정·재고 안 둠 위치 nodeId) |
| 공장 휴일 달력 — 정책 `WEEKLY_OFF`·`mst_work_cal`·읽기 계약 `WorkCalendar`·화면 `mst-work-cal`(V086) | ✅(main 병합) | 2026-10-21 | 1,428건(main V083 병합 뒤), 신규 WorkCalendarTest 3(층 우선순위 순수 계산)·WorkCalApiTest 5. 브라우저 1366·390(ZT: 하루 휴무·주말 근무조 특근·중복 칸 오류). **발견·수정**: BEFORE 트리거가 CHECK 보다 먼저 돌아 "공장 없는 근무조" 가 소속 오류로 보이던 것, 화면 스크립트 `hb.dates` 누락(타입 검사로 못 잡음), 근무조 예외 사유가 달력 칸에 안 보이던 것 |
| **P2 완료** — 11장 완료 검토. 다음 MES 작업은 사용자 결정 대기(11.6 후보) | — | 2026-10-21 | 마지막 전체 check 1,439건(상위 세션, main c81edfa) |
| 사무 실적 조회 `prd-result`(V090) | ✅(main 병합) | 2026-10-21 | 1,453건(main 62fc6b4 병합 뒤), 신규 ResultViewTest 5(실제 POP 실적·정정 → 보기 5종·조건·불량 합계·엑셀). 브라우저 1366·390(DEMO 관통 시드 실적 — 조회만). **발견·수정**: 390 에서 불량 합계 표(`hb-rpt-table` 최소 720px)가 스크롤 상자 밖이라 페이지가 넓어짐, 보기 세그먼트 5칸이 패널 폭을 넘어 글자 잘림 → 선택 칸 |
| 지시별 남은 자재 소요 조회 계약 `WoMaterialQuery`(APS 5단계, V096 인덱스만) | ✅ | 2026-10-21 | 1,484건(main 7c58e80 병합 뒤), 신규 WoMaterialQueryTest 7(투입 전·일부 투입·정정 뒤·전개 실패·상한·A사로 B사·NONE). **발견**: 역분개 행은 `wo_id` 가 없어 지시 기준 합으로는 정정이 안 빠진다 → 되돌려진 원래 행 제외 |
| 수집 ALARM → CMS `AlarmSink` 호출(4.17, 마이그레이션 없음) | ✅ | 2026-10-21 | EquipEventTest +3(가짜 `AlarmSink` 빈 — 발생·해제·`active` 생략·문구 자름·형식 오류 `ALARM_CODE`/`ALARM_ACTIVE`·같은 eventId 한 번·범위 밖 설비·A사 장치로 B사 설비 코드·빈 없음 `NONE`) |
| LOT 보류 근거 원장 `lot_hold`·계약 `LotHoldPort`(4.19, V109(임시 V9110)) | ✅ | 2026-10-21 | 1,610건(main 3088297 병합 뒤), 신규 LotHoldTest 6 + PopWorkTest·LotTest 단언. 브라우저 1366·390(ZT LOT: NCR 근거 + 수동 근거 → 수동 해제 뒤 보류 유지). **발견·수정**: 해제 사유 CHECK 가 NULL 을 통과(`length(btrim(NULL)) > 0`) |
| 역분개 행 맥락 복사(4.5, V110(임시 V9111)) + 회사 날짜 정리(시험 데이터 — 홈 보류 카드 링크는 #147) | ✅ | 2026-10-21 | 1,613건(main e1a36e4 병합 뒤), LotTest·PopWorkTest 단언 추가, CmsKpiTest 기대값(정정 행 설비) 갱신 |
| B5 ① POP 오프라인 대기열 거부 보존(4.20, 마이그레이션 없음) | ✅ | 2026-10-22 | JS 단위 시험 hb.pop.queue 5(node). 브라우저 POP 1280×800·800×1280·390(ZT 단말, 재전송 409 흉내): 거부 보존·사유·다시 보내기·버리기, 넘침 0·48px 미만 0. **발견·수정**: 머리 버튼 색이 머리 공통 규칙에 덮임, 영어 복수형 문구 |
| B5 ②③④ 칸 오류 없는 거부 그대로·정정 LOT id 순 잠금·LotStockLink 훅(4.20, 마이그레이션 없음) | ✅ | 2026-10-22 | PopWorkTest +2(스파이 409 주입·잠금 순서 결정적 — 옛 코드 실패 확인), 신규 LotStockLinkHookTest 3(가짜 계약 빈) |
| 착수 게이트 `StartGate` + `LotLookup`(4.21, V120(임시 V9112)) | ✅ | 2026-10-22 | PopWorkTest +2(가짜 게이트 빈 2개: 미리보기·가장 나쁜 mode·한 번의 확인·근거 코드·세이브포인트 — 세이브포인트 없으면 500 확인), 신규 LotLookupTest 4 |
| 공휴일 엑셀 올리기 `MST_WORK_CAL`(4.24, 그룹 A 1번, 마이그레이션 없음) | ✅ | 2026-10-22 | 신규 WorkCalImportTest 3(새 예외·행 오류 10종·파일 안 같은 키, 같음·변경·사용 중지 키·새 것만 모드, A사로 B사·조회 권한 403) |
| 작업지시 엑셀 올리기 `PRD_WO`(4.26, 그룹 A 3번, 마이그레이션 없음) | ✅ | 2026-10-22 | 신규 WoImportTest 4(새 지시·행 오류 10종, 계획 지시 고치기·발행 지시·없는 번호·파일 안 같은 키·FIXED·같음, 미리보기 뒤 발행, A사로 B사·조회 권한 403) |
| 수집 COUNT → POP 실적 자동 제안(4.27, 그룹 A 4번, V139·임시 V9115) | ✅ | 2026-10-22 | EquipEventTest +1(원장·제안 합·AUTO·since 갱신·KEY·같은 설비 공정 둘·METRIC·직접 SQL·다른 회사 설비) |
| COUNT 원장 역분개 행 검사(4.27 후속, V140·임시 V9116) | ✅ | 2026-10-22 | 1,856건(main 149c775d 병합 뒤), EquipEventTest +1(수량·메트릭·설비 틀림, 두 번, 역분개의 역분개, A사 원 행을 B사가 — V9116 빼면 실패 확인) |
| UI 단계 2 POP 표시 규칙(4.28, 마이그레이션 없음) | ✅ | 2026-10-22 | 브라우저 1366·390(DEMO + ZT 단말·배지): 회색 바탕·흰 카드, 배지, 배너 자리 고정(공정 화면 위치 그대로), 종료 외곽선 위험 톤·떨어진 자리, 넘침 0·48px 미만 0 |

## 8. 결정 필요 (사용자)

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| D1 | 디스패치 단위(02 2.1 보류, 정책 `MES_DISPATCH_MODE`) | **지시 × 공정(`prd_wo_proc`) = 디스패치.** 설비·교대 배정은 `prd_wo_proc` 의 선택 칸. `mes_dispatch` 테이블은 만들지 않는다 | 별도 `mes_dispatch`(지시 × 공정 × 설비 × 교대) — APS 붙일 때 유리, P2 규모엔 과함 |
| D2 | 재고 경계 — P2 "입고" 와 자재 LOT 출처 | **P2 는 재고를 세지 않는다.** 생산 입고 = `prd_receipt` 원장, 자재 LOT = "자재 LOT 간이 등록"(공급사 LOT·수량, 라벨 스캔 대체). P4 재고 원장이 둘을 첫 소스로 이관 | 재고 원장 `inv_trx` 최소형(입고·투입 2유형)을 P2 로 당김 — 투입 때 잔량 검사 가능하지만 P4 설계를 앞당겨 굳힘 |
| D3 | 작업자 모델 | **`mst_worker` 별도(로그인 계정 아님)**, 사무 계정 연결은 선택 — 작업자 계정 무료 과금(06 M3)·배지 전환(01 D4)과 맞음 | `sys_user` 에 작업자 유형 — 계정 수·비밀번호 정책이 현장과 안 맞음 |
| D4 | LOT 번호 규칙 | **채번 패턴(`NumberingService`) + 정책 `LOT_NO_RULE`**(예 `{item}-{yyMMdd}-{seq3}`), 지시 번호 = LOT(관통) 는 정책 선택지로 | 전략 클래스(`LotPolicy`) 여러 개 — 고객별 코드가 늘어남 |
| D5 | 공장 시간대(4단계 QA 가 남긴 결정) | **`sys_plant.time_zone` 사용**(null = 회사 시간대). 설치 이력 등 P1 시각 입력도 같은 해석으로 맞춘다. 작업일·근무조·원장 조회 기본 기간은 공장 기준 | 회사 시간대 하나 — 해외 공장 고객이면 근무조 경계가 틀린다 |
| D6 | POP 오프라인 대기열 | **5b 에 포함**(실적 확정만, IndexedDB write-ahead + 같은 `client_req_id` 재전송, 수량은 증감 이벤트) | P4 PDA 때 같이 — POP 이 Wi-Fi 음영에서 입력을 잃는다 |
| D7 | Collector 범위 | **스키마 + 수신 API + 가짜 수집기**(테스트·데모가 실제 HTTP 로 이벤트를 보냄) | 수신 API 만 — 데모에서 설비 상태 타임라인이 비어 보인다 |
| D8 | 열린 구간 닫기(상태 구간 `end_at`) | **"한 번만 닫기" 트리거 예외**(4단계 설치 이력과 같은 방식: end_at null → 값 1회만, 그 밖 UPDATE 차단) | 닫을 때 시작·끝 두 이벤트 행으로만(구간은 조회 때 계산) — 겹침 배제 제약을 못 쓴다 |
| D9 | 24장 화면 쪽 의심 3건(상세 늦은 응답·파일 드롭·세션 만료 중 저장) | **P2 1단계 전에 공통 헬퍼로 먼저 고친다** — POP 은 같은 문제가 더 크게 난다(공용 단말·음영) | P2 와 병행 |
| D10 | 24 6장 체크리스트 저장소 사본 | 24 의 추천(A: TESTING.md 에 A~K 제목·한 줄 요약) — 7단계 QA 세션이 이미 기준 없이 돌고 있다 | B·C |
| D11 | (6단계) 늦게 온 STATE(열린 구간 시작보다 이른 시각) | **`LATE` 로 거부 — 원장 과거를 다시 쓰지 않는다.** 수집기는 버퍼를 시각 순서로 다시 보낸다(가짜 수집기도 그렇게) | 과거 구간을 쪼개 끼워 넣기 — "한 번만 닫기"(D8)를 깨고 이미 계산한 가동률이 바뀐다 |
| D12 | (6단계) 상태 분류 저장 | **전역 공통코드 `STATE_CAT` + CHECK**(고정 7개) | 전역 표 `cms_state_cat` — 4장 요약 그대로, 얻는 것 없이 표·FK 하나 더 |
| D13 | (6단계) 타임라인 화면 위치 | **생산 메뉴(`cms-state`, PRD, MES 대역 sort 440)** — 6단계 원장은 MES 가 만든다. CMS 메뉴 바로가기는 CMS 세션이 정한다 | 설비관리(CMS) 메뉴 |
| D14 | (7a) 재작업·수리 처리의 정정 | **역분개 없음** — 처리는 공정 완료 전 감독자가 하고, 잘못 처리한 산출 LOT 은 보류로 막는다. 정정이 필요해지면 그때 원장 거울 규칙 추가 | 역분개 + 산출 LOT 되돌림 — 산출 LOT 이 다음 공정에 쓰였는지까지 따라가야 해 7a 범위를 넘는다 |
| D15 | (7a) 첫 공정의 투입(보존식 기준) | **검사하지 않는다**(처리 합계를 투입으로 기록) — 지시 계획 수량은 목표이지 실제 투입이 아니다(초과·부족 생산) | 계획 수량 = 투입 — 계획과 다르게 만든 지시는 모두 완료가 막힌다 |
| D16 | (7a) 마감과 미입고 양품 | **마감을 막지 않고 보여만 준다** | 미입고 0 일 때만 마감 — 샘플·보관 양품 때문에 마감이 영영 안 된다 |
| D17 | (7b) 보고서 PDF | **브라우저 인쇄(PDF 저장)** — 인쇄용 CSS 만. 서버 PDF 라이브러리(OpenPDF·PDFBox)는 의존성·체크섬·한글 글꼴 반입이 따라오고, 화면과 PDF 두 벌을 맞춰야 한다 | 서버 PDF — 파일로 보관·전자 서명이 필요해지면(고객 요구) 그때 |
| D18 | (7b) 보류 LOT 의 재작업·수리 | **REWORK·REPAIR 만 보류 LOT 을 부모로 허용**, 새 LOT 은 보류 아님 — 보류의 정상 출구 | 보류를 먼저 풀고 재작업 — 풀린 순간 다른 공정이 투입할 수 있다 |
| D19 | (7c) ERP 타임아웃(상대가 받았을 수 있음) | **FAIL("UNKNOWN") + 자동 재전송 없음**, 사람이 lookup — 플랫폼 아웃박스 상태는 그대로(07 D3 의 `UNKNOWN` 상태 추가는 실제 어댑터 때) | 아웃박스에 UNKNOWN 상태 추가 — 플랫폼 표 변경(다른 세션 회귀)인데 지금 쓰는 채널이 가짜 하나 |
| D20 | (7c) 같은 기간 엑셀 두 번 내보내기 | **기록하고 화면에 알리기만**(막지 않음) — 다시 받는 정당한 경우(파일 분실·양식 수정)가 있다 | 기간 잠금 — ERP 업로드 실패 뒤 재작업을 막는다 |
| D21 | (7c) `ERP_SYSTEM` 기본값 | **NONE**(설치 직후 아무것도 나가지 않음), 데모 회사만 시더가 FAKE | EXCEL 기본 — 값의 차이가 "엑셀 화면이 있다"뿐이라 의미가 없다(엑셀 화면은 정책과 무관하게 열린다) |

## 9. 하지 않을 것 (P2)

- APS·유한 용량 스케줄링, 간트 편집(지시 계획일만)
- 재고 수량·차감(6장, D2), 출하·납품(P4)
- 설비 실제 프로토콜·태그 매핑·명령(P6), MQTT 브로커를 코어 경로로(00 하지 않는 것)
- 결과 캐시 테이블(LOT 추적·OEE) — 정합성 우선, 재귀 CTE·조인 파생(02)
- 전 도메인 이벤트 소싱 — 원장 6종만(01 3.1)
- POP 네이티브 앱 — PWA(브라우저)만

## 10. 검토 결과 (원래 세션이 작성)

2026-10-20 사용자: "추천대로 D1~D10 진행".

| # | 결정 | 반영 위치 |
|---|---|---|
| D1 | 디스패치 = 지시 × 공정(`prd_wo_proc`), `mes_dispatch` 없음 — `MES_DISPATCH_MODE` 정책 키도 만들지 않는다(필요해지면 그때) | 02 6.2 결정표 "보류" 행 → 이 문서, 4장 3단계 |
| D2 | P2 는 재고를 세지 않는다. 생산 입고 `prd_receipt` 원장 + 자재 LOT 간이 등록, P4 재고 원장이 첫 소스로 이관 | 4장 4·7단계, 6장 |
| D3 | 작업자 `mst_worker`(로그인 계정 아님, 배지·선택 PIN), 사무 계정 연결은 선택 | 4장 1단계 |
| D4 | LOT 번호 = 채번 패턴 + 정책 `LOT_NO_RULE`, 관통(지시 번호 = LOT)은 정책 선택지 | 4장 4단계 |
| D5 | 공장 시간대 `sys_plant.time_zone` 사용(null = 회사), P1 시각 입력(설치 이력 등)도 같은 해석으로 — 4단계 QA 관찰 해소는 1단계에서 | 4장 1단계, docs/qa/equip-4.md 남은 것 |
| D6 | POP 오프라인 대기열은 5b 에 포함(실적 확정만) | 2장 5b |
| D7 | Collector = 스키마 + 수신 API + 가짜 수집기 | 2장 6단계, 4.1 |
| D8 | 상태 구간 `end_at` 은 "한 번만 닫기" 트리거 예외 | 4장 6단계 |
| D9 | docs/24 2.1~2.3 을 P2 1단계 전에 공통으로 수정 — **완료**(`hb.latest`·드롭 차단·세션 만료 확인, TESTING.md 같은 날 절) | hb.js, shell.js, 화면 13개 |
| D10 | TESTING.md 에 A~K 요약 절, QA 세션 지시문은 "TESTING.md 체크리스트 절 기준" | TESTING.md, 7단계 QA 세션에 전달 |

## 11. P2 완료 검토 (MES 하위 세션, 2026-10-21)

> P2 MES 코어(1~7단계)와 그 뒤 상위 세션이 지시한 연결 작업(7c·PLM 3c·홈 링크 칸·입고 → 재고·휴일 달력)이 모두 main 에 들어갔다(c81edfa, 상위 세션 전체 check 1,439건). 이 절은 **한 것·버린 것·남긴 것·교훈**을 한 곳에 모은다 — 근거는 각 단계 상세(4.2~4.15)·결정(8장 D1~D25)·TESTING.md 의 같은 이름 절.

### 11.1 한 것 — 단계·V 번호·시험

| 단계 | main V | main 병합 | 그때 전체 check | 신규 시험(클래스) |
|---|---|---|---|---|
| 1 작업일·근무조·작업자·자격 | V030 | 병렬 운영 전 | 673 | WorkDayResolverTest 6·WorkforceTest 6 |
| 2 수주·내시 | V032 | 〃 | 731 | SalesOrderTest 11·ForecastTest 7 |
| 3 작업지시 | V033 | 〃 | 744 | WorkOrderTest 12 |
| 4 LOT·계보 원장 | V034 | 〃 | 754 | LotTest 10(10단 × 1,000 LOT 역추적 560ms) |
| 5a POP 기반 | V035 | 〃 | 765 | PopTerminalTest 11 |
| 5b 착수·실적·투입 | V036 | 〃 | 773 | PopWorkTest 8 |
| 6 설비 상태·Collector | V051 | #25(#28 이름 고침) | 1,055 | EquipEventTest 9 |
| 7a 완료판정·입고·재작업 처리 | V055 | #29 | 1,075 | CompletionReceiptTest 5 |
| 7b LOT 역추적 보고서·LOT 재작업 | V056 | #29 | 1,105 | LotReportTest 3 |
| 7c ErpPort·ERP 엑셀·데모 관통 | V066 | #56 | 1,189 | ErpTest 7·DemoDataSeederTest +1 |
| 3c PLM 연결(계보 change_id) | V077 | #68 | 1,348 | PlmChangeLinkTest 5 |
| 홈 링크 조회 칸 3개 | V081(인덱스) | #76 | 1,392 | HomeLinkFilterTest 5 |
| 입고 → 재고 원장 연결 | 없음 | #84 | 1,406 | CompletionReceiptTest +1 |
| 공장 휴일 달력 | V086 | #89 | 1,428 | WorkCalendarTest 3·WorkCalApiTest 5 |

- 위 V 파일 기준(2026-10-21 마이그레이션에서 셈): **표 30개**(원장·추가만: 계보·실적·불량 내역·공정 이벤트·파라미터 실측·공정 완료판정·재작업 처리·입고·ERP 내보내기 기록 + 설비 상태·연결·에너지 구간), **사무 화면 13개**(`sys_prog`) + **POP 화면 2개**(단말 활성화·작업장 홈 — 실적·투입은 홈 안 패널), **정책 키 9개**, 결정 25개(8장 D1~D21 + 4.15 D22~D25).
- 매 단계 정상 + 이상 사용 A~K + A사로 B사 + 브라우저(1366·390, POP 은 태블릿 3크기) — 각 TESTING.md 절.

### 11.2 설계에서 지킨 것 (P2 가 플랫폼 규칙을 실제로 쓴 자리)

| 규칙 | P2 에서 | 시험으로 고정한 것 |
|---|---|---|
| 원장은 추가만, 정정 = 역분개 | 계보·실적·공정 이벤트·파라미터·입고·공정 완료판정 | 직접 SQL UPDATE/DELETE 42501, 역분개 거울 검사(3c 에 `change_id` 까지) |
| 회사 격리 = RLS + FK 의 회사 확인 트리거 | 모든 회사별 표 | 표마다 "A사로 B사"(조회 0·404·직접 SQL 거부) |
| 외부 송신 = 아웃박스 | ERP 송신(7c) — 핸들러는 DB 를 못 쓰니 외부 코드까지 발행 때 본문에 | 타임아웃 = FAIL UNKNOWN·자동 재전송 없음(D19) |
| 현장 입력 멱등 | POP 모든 저장 `client_req_id`, 사무 입고·처리도 | 같은 요청 재전송 = 처음 응답, 동시 = 한 건 |
| 상태 변경은 같은 트랜잭션의 이벤트 행 필요 | 공정 상태·지시 스냅샷(`released_at = now()` 트릭) | 직접 SQL 로 상태만 바꾸기 거부 |
| 다른 모듈은 공개 계약으로만 | PLM `ChangeMarker`(3c), WMS `ProductionReceiptPosting`(입고), 달력 `WorkCalendar`(→ APS·CMS·PMS) | 잠금 순서 MES 행 → 다른 모듈 행(3c 교착 재현 시험) |

### 11.3 버린 것 (다른 안을 고르지 않은 이유는 8장 각 결정의 "다른 안" 칸)

- 디스패치 표 `mes_dispatch`(D1) — 지시 × 공정이 곧 디스패치. 필요해지면 정책 키부터.
- P2 에서 재고 세기(D2) — 입고 원장만 두고 P4 가 첫 소스로 이관. **실제로 WMS B1 이 이 원장을 그대로 읽어 이관·연동했다**(입고 → 재고 연결, 마이그레이션 없음) — 경계를 미리 그은 값이 났다.
- 서버 PDF(D17) — 브라우저 인쇄로 충분, 의존성·한글 글꼴 반입 비용.
- ERP 실제 어댑터·`UNKNOWN` 아웃박스 상태(D19) — 가짜 ERP 로 실패 규칙만, 플랫폼 표는 그대로.
- 휴일 실적 차단(D24) — 달력은 계획·주기·지연용, 원장은 사실 기록.

### 11.4 남긴 것 (다음 후보 — 결정은 사용자. 2026-10-23 상태 갱신: ✅ = 끝남, 나머지는 다른 세션 몫)

| 후보 | 출처 | 비고 |
|---|---|---|
| 사무 실적 조회 화면(지시·공정·작업자·근무조별, 엑셀) | 5b "하지 않은 것" | ✅ 4.16(V090, 2026-10-21) |
| COUNT → 실적 자동 제안 | 6단계 | ✅ 4.27(V139·V140, 2026-10-22) |
| CMS 알람 원장 연결(`AlarmSink` 호출 한 줄) | CMS 2부 Q2 | ✅ 4.17(2026-10-21, CMS 계약 V093 뒤 — `EquipEventService` ALARM → `AlarmSink`) |
| 작업지시 엑셀 올리기·수주 엑셀 내보내기 | 2·3단계 | 지시 올리기 ✅ 4.26(2026-10-22). 수주 내보내기는 영업(#48 뒤 WMS) 몫 — 공통 그리드 엑셀 |
| 공휴일 엑셀 올리기 | 4.15 | ✅ 4.24(2026-10-22) |
| LOT → LOT 변환(분할·병합·라벨 바꿈·LOT 재작업)의 재고 반영 | WMS 합의 Q4 답 | WMS 2부 뒤 단계 몫 — MES 는 위치를 모른다 |
| 품목 기본 단위 변경 금지에 지시·LOT 사용 포함 | WMS 합의 Q1 답 | ✅ 지시 V091(WMS), LOT 4.32(V142, 2026-10-23) |
| ERP `lookup` 화면 | 7c | 실제 어댑터(P6)와 함께 |
| 각 모듈의 달력 읽기 전환 | 4.15 | APS·CMS·PMS 세션 몫(계약은 main) |

### 11.4a 그룹 A 추천 순서 (2026-10-22, docs/29 #42 — 상위 판단: 추천안대로, PR 1개 = 단계 1개, MES 임시 V9115~)

11.4 남은 후보 가운데 MES 혼자 끝나고 작은 것부터. 다른 세션과 겹치는 것(LOT 변환 재고 = WMS, ERP lookup = P6, 달력 읽기 전환 = 각 모듈)과 사용자가 직접 하는 결정 #43(공급사 LOT 추가 입고)은 뺀다.

| 순서 | 일 | 크기 | 비고 |
|---|---|---|---|
| 1 | **공휴일 엑셀 올리기** — `mst_work_cal` 에 `ImportTemplate` `MST_WORK_CAL` | 작음, 마이그레이션 없음 | 화면 저장과 같은 검증. 키 = 공장·날짜·근무조(바꿀 수 있는 것은 OFF/WORK·사유) |
| 2 | **작업지시 엑셀 올리기** — 작성(PLANNED) 지시만 새로·변경 | 중간 | 발행된 지시는 행 오류. 채번·라우팅 스냅샷은 화면 저장 흐름 그대로 |
| 3 | **COUNT → 실적 자동 제안** — 수집 COUNT 를 POP 실적 기본값으로 | 중간 | 기본값만(확정은 사람), 원장·실적 규칙은 그대로 |

뒤로 미룬 것: 수주 엑셀 내보내기(목록 엑셀은 공통 그리드 기능을 쓰는 편이 낫다), `sal_order` 거래처 인덱스(4.23 — 느려질 때).

### 11.5 교훈 (skill 반영 후보는 PR 본문 — 상위 세션이 SKILL.md 에)

1. **트리거는 이름순·CHECK 보다 먼저 돈다** — 7a(전이 가드보다 앞 이름의 새 트리거가 엉뚱한 오류), 휴일 달력(트리거가 CHECK 의 전제를 먼저 검사). 새 트리거는 기존 가드 뒤 이름으로, CHECK 가 지키는 전제가 없으면 건너뛴다.
2. **잠금 순서는 뒤에 오는 트리거의 잠금까지 센다** — 3c: 표시(ECO 잠금) 뒤에 투입 트리거가 자재 LOT 을 잠가 순서가 뒤집혔다. 고치기 전 순서로 되돌려 교착 40P01 이 재현되는 것까지 확인해야 "시험이 결함을 잡는다".
3. **화면 버그의 절반은 브라우저에서만 보였다** — 기본 기간이 링크 조건을 덮음(1단계부터 있던 결함, 홈 링크 확인에서 발견), 스크립트 누락(타입 검사는 전역 선언이라 못 잡음), `hidden` 을 덮는 `display`, 390 에서 글자 단위 접힘. API 시험이 통과해도 화면 확인을 건너뛰지 않는다.
4. **"카드 숫자 = 목록 건수" 는 단위부터** — 수주 목록은 머리, 카드는 행. 행 수를 따로 세서 같은 화면에 보였다.
5. **경계를 미리 긋고 계약으로 넘긴다** — D2(재고 안 셈)·ChangeMarker·ProductionReceiptPosting·WorkCalendar. 계약이 먼저 main 에 있으면 상대 세션이 병렬로 구현할 수 있었다(PLM 3a 계약 → MES 3c, WMS 합의안 → B1 → MES 두 줄).
6. **병렬 운영의 비용은 병합** — 하루에 main 이 10번 넘게 바뀌어 매번 merge + 전체 check(약 8분). 충돌은 거의 전부 공유 파일 끝 블록(TESTING.md·messages·RetentionTest)이었다 — "끝에 덧붙이기" 규칙 덕에 기계적으로 풀렸다. 클라우드 세션에서는 Docker 데몬이 조용히 죽어 DB 시험 전부가 실패로 보인 적이 두 번(셸 아래 `nohup &` 로 띄운 데몬 — `setsid` 로 떼어 띄운 뒤로는 없음) — 실패 수가 비정상적으로 크면 데몬부터.

### 11.6 docs/01 9장·SKILL §3 반영 후보 (상위 세션)

- docs/01 9장: "P2 MES 코어 ✅ 2026-10-21, 마지막 전체 check 1,439건(main c81edfa), V030~V036·V051·V055·V056·V066·V077·V081·V086".
- SKILL §3 로드맵 줄: "P2 MES ✅(1~7단계 + 7c·PLM 연결·홈 링크·입고 → 재고·휴일 달력) — 다음은 사용자 결정(11.4)".
- SKILL §2 P2 블록 끝(현행 규칙 후보): "근무일은 모듈마다 세지 않는다 — `WorkCalendar`(주간 = 정책 `WEEKLY_OFF`, 날짜 예외 = `mst_work_cal`, 우선순위 근무조 > 공장 하루 > 회사 > 주간 > 기본)", "MES 가 새 LOT 을 만들면 같은 트랜잭션에서 `ChangeMarker.mark`(잠금: 자재 LOT 먼저)", "생산 입고·정정은 같은 트랜잭션에서 `ProductionReceiptPosting.post`".

## 12. UI 단계 3 목록 틀 — 영업 화면 (2026-10-22)

내시(sal-forecast) 화면의 목록 틀 적용(조회 패널, 다음은 그리드 전환)은 **docs/17 8장**에 기준정보 화면과 함께 적는다. 수주(sal-order)의 ②~⑥ 은 MES 몫(공용 mes-ui.js), ① 그리드 전환은 MES 병합 뒤 이 세션이 한다. MES 몫 기준정보 화면 중 작업자·근무조의 ① 그리드 전환은 docs/17 8.4(2026-10-22, `mesUi` 그대로 사용). 수주의 ① 그리드 전환은 docs/17 8.5. 내시 버전은 8.6, 휴일 달력은 달력 화면이라 ① 제외(8.6).
