# 테스트 기록 — P2 MES(영업·생산·POP)

> TESTING.md 에서 옮김(2026-10-22, 내용 그대로). 실행 방법·이상 사용 체크리스트 A~K·데이터 규칙은 [TESTING.md](../../TESTING.md).
> 새 기록은 **이 파일 끝에** 덧붙인다(시간순).

## P2 1단계 작업일·근무조·작업자·자격 (2026-10-20)

실행: `./gradlew check`(673건 통과), 신규 `WorkDayResolverTest` 6(DB 없음) + `WorkforceTest` 6 + `DemoDataSeederTest` 1. 브라우저 Playwright 1366·390(데모: 주간·야간 2교대, 자격 3·공정 요구 2, 작업자 6명 — E1002 용접 만료, E1006 자격 없음).

| 항목 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| B | 조 코드 `1조!`·이름 빈 값·`24:00`·`8:00`·오프셋 1 / 시작 = 끝 / 휴게 ≥ 근무 / 사번·이름 NUL / 배지 짧음·한글·공백 | 칸 오류 5개 / `SAME_AS_START` / `BREAK_TOO_LONG` / `FORMAT` / `FORMAT` | |
| F | 야간 20~08 이 있는데 06~10 조출 / 야간 끄고 조출 넣은 뒤 야간 다시 사용 / 공장·조 코드·사번·자격·취득일 바꾸기 / 직접 SQL 겹치는 조·사번 변경·보유 삭제 | `SHIFT_OVERLAP [N]`(자정 넘김 포함) / 다시 사용 때 `SHIFT_OVERLAP` / `FIXED` / `ck_mst_shift_overlap`·`ck_mst_worker_cd_fixed`·42501 | 겹침은 서비스 칸 오류 + 트리거(공장 advisory 잠금) |
| H | 자정 넘는 야간 새벽 03:00 / 3교대 3조(00~08, 오프셋 -1) / 근무 외 시각 / 월말·윤년 2월 29일 / 같은 순간 서울·뉴욕 공장 | 전날 작업일 N / 전날 C / 근무조 없음·현지 날짜 / 12-31·02-29 / 서울 야간·뉴욕 주간 | `WorkDayResolver` |
| H | **DST** 뉴욕 2026-03-08 02:30(없는 시각) / 11-01 01:30(두 번) / 잘못된 공장 시간대 값 `Mars/Base` | `DST_GAP` 칸 오류 / 앞쪽(EDT) — 야간 작업일은 두 번 모두 전날 / 회사 시간대로 | `BizClock.toInstant`. P1 설치 이력도 설비 공장 시간대 + `DST_GAP`(docs/25 D5) |
| E·A/B | 배지: 응답에 해시 없음(`badgeSet` 만)·감사 로그에 해시 없음(`(changed)`)·같은 배지 다른 작업자 `DUPLICATE`·지운 뒤 재사용 / **B사**가 같은 배지 값 | 기대대로 / 허용(해시에 회사 포함) | sha256(회사:배지) |
| A/B | **B사**: A사 작업자 상세·배지·보유·자격·근무조 수정 / 자기 작업자 경로에 A사 보유 id / A사 자격 코드로 보유 / A사 사무 계정 연결 / 직접 SQL A사 자격·작업자·계정 참조 | 404·0건 / 404 / `NO_SUCH_CODE` / `NO_SUCH_CODE` / `fk_mst_worker_qual_qual_comp`·`fk_mst_proc_qual_req_qual_comp`·`fk_mst_worker_user_comp` | 트리거 |
| D | 사용 중지 자격을 새 보유로 / 그 자격 요구 다시 사용 / 사용 중지 공장으로 작업자 | `INACTIVE` / `INACTIVE` / `INACTIVE` | |
| 게이트 | 자격 없음(기본 정책 WARN 이 상한) / 공장 P1 정책 BLOCK / US1(회사 기본) / 유효 개월 24 → 만료 다음 날 / 취득 전 / 갱신 새 행 / 새 행 취소 / 요구 끄기·정책 OFF | WARN / BLOCK / WARN / `EXPIRED` / `NOT_YET` / OK / `REVOKED` / OK | `QualGate` = min(정책, 요구) |
| E | 작업자 조회만: 배지·보유 추가 403, 자격·공정 선택지 200, 근무조 API 403 | 기대대로 | |
| 브라우저 | 근무조 표·시간대 표시·확인 칸(03:00 → 전날 야간)·조출 겹침 칸 오류 / 자격 표·요구 중복 / 작업자 표·만료 자격·게이트(만료·없음)·배지 스캔 Enter·자격 추가 → 통과 | 기대대로, 넘침 없음(1366·390), 콘솔 오류 0(의도한 400 제외) | **발견·수정**: 차단 요구가 "경고" 로 나와 이유가 안 보임 → 결과에 "공장 정책 상한이 경고" 표시 |

**실수 기록**: 브라우저 확인을 데모 작업자 E1002 로 돌려 배지·자격 행이 남았다(TESTING 데이터 규칙 위반) — 소유자 계정으로 되돌렸다. 다음부터 화면 확인은 `ZT` 접두 작업자를 새로 만들어 한다.

## P2 2단계 수주·내시 (2026-10-20, V032)

실행: `./gradlew check`(**731건 통과, 실패 0**), 신규 `SalesOrderTest` 11 + `ForecastTest` 7 + `DemoDataSeederTest` 1. 브라우저 Playwright 1366·390 — 수주는 `ZT` 데이터(고객 `ZT-C1`·품목 `ZT-FG1`), 내시는 데모 S001 보기만.

| 항목 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 새 수주(행 2) → 채번 `SO{yyMM}-0001`·`-0002` / 행 추가(번호 비움) / 수량 변경 / 마감 1·취소 2 | 번호·30·변경 이력 / 머리 OPEN → CLOSED, 끝난 수주에 행 추가 `CLOSED` | 머리 상태는 행에서 계산 |
| B | EA 1.5·KG 1.2345·`1e3`·`-1`·`1,000`·13자리·2026-02-30 / 구매 거래처·사용 중지 거래처·없는 코드 / 행 0개·같은 행 번호 / 발주 번호 제어 문자·한글·앞뒤 공백 / 비고 NUL | `SCALE`×2·`FORMAT`·`POSITIVE`·`FORMAT`·`INT_DIGITS`·`FORMAT` / `NOT_SALES`·`INACTIVE`·`NO_SUCH_CODE` / `NO_LINES`·`DUPLICATE` / `FORMAT`·`FORMAT`·정리 / `FORMAT` | 수량은 글자로 받고 글자로 돌려줌(`999999999999.999` 그대로) |
| B·D | 같은 거래처 같은 발주 번호 / 다른 거래처 같은 번호 / 내시 화요일 주·15일 월·음수·EA 1.5·`YEAR`·`1e2`·같은 키 | `DUPLICATE` / 허용 / `WEEK_START`·`MONTH_START`·`NOT_NEGATIVE`·`SCALE`·`CODE`·`FORMAT`·`DUPLICATE` | 수량 0 은 허용(수요 없음) |
| F | 끝난 행 수량 변경·다시 마감 / 행 품목·행 번호·머리 거래처 바꾸기 / 같은 rowVer 두 번 | `CLOSED` / `FIXED` / 409 | |
| F·직접 SQL | 취소 행 되살리기·품목 바꾸기·사유 없는 취소·행/머리 삭제·머리 거래처 / 내시 행·버전 UPDATE·DELETE·화요일 주 INSERT | `ck_sal_order_line_closed`·`_fixed`·`_cancel`·42501·`ck_sal_order_fixed` / 42501×4·`ck_sal_forecast_line_start` | 트리거 + REVOKE |
| C | 마지막 두 행 동시 마감 ×3회 / 같은 발주 동시 생성 / 같은 거래처 내시 4건 동시 수신 | 머리 CLOSED 매번 / 200·400, 1건, 번호 중복 없음 / 버전 1,2,3,4 | 머리 `FOR UPDATE`, 부분 유일 인덱스, 거래처 advisory 잠금 |
| H | 기간 없음·367일 / 검색어 `%` / 다른 거래처 버전끼리 비교 / 첫 버전 비교 | `REQUIRED`·`RANGE_TOO_LONG` / `PO-%1` 만 / `OTHER_CUST` / 전부 새로 | |
| 엑셀 | 수주: 새 발주 2개(행 4)·구매 거래처·EA 1.5·같은 발주 다른 수주일 → 다시 올려 변경 1·같음 1·새 행 1·끝난 행 변경·품목 바꾸기·있는 수주 수주일 / 내시: 거래처 둘·코드 대신 이름(`주`)·화요일·같은 키 → 같은 내용 다시 | 새 4·오류 3 → 대사 일치 / 변경 1·같음 1·새 1·`CLOSED`·`FIXED`·`FIXED` / 버전 2개(원본 작업·파일명 표시)·오류 3 → 2차(1차 그대로) | `openForExecute(jobId)` |
| A/B | **B사**: A사 수주 상세·수정·행 수정·행 취소·행 추가 / A사 거래처·품목 코드 / 직접 SQL A사 수주에 행·A사 거래처로 수주·A사 행 UPDATE / A사 내시 버전·행·비교·수신 | 404×5 / `NO_SUCH_CODE` / `fk_sal_order_line_order_comp`·`fk_sal_order_cust_comp`·0행 / 404×3·`NO_SUCH_CODE` | |
| E | 조회만: 수주 생성·행 마감, 내시 수신 | 403 | |
| 브라우저 | 새 수주(행 2 모아 두기 → EA 1.5 행 오류 문구 → 빼고 저장) → 행 바로 추가 → 마감(확인)·취소(사유) → 완료, 수주 취소 버튼 숨김 / 내시 S001 1차→2차 비교·이 버전 행 | 기대대로, 넘침 0(1366·390), 콘솔 오류는 의도한 400 과 favicon 404 뿐 | **발견·수정**: 끝난 수주에도 행 폼이 보임(`.hb-form` 의 `display:grid` 가 `hidden` 을 덮음 → 공통 `.hb-form[hidden]`), 목록 수주 번호가 줄바꿈으로 쪼개짐(긴 코드 칸 지정 해제) |

## P2 3단계 작업지시 (2026-10-20, V033)

실행: `./gradlew check`(**744건 통과, 실패 0**), 신규 `WorkOrderTest` 12 + `DemoDataSeederTest` 1. 브라우저 Playwright 1366·390 — `ZT` 품목(`ZT-FG1`·`ZT-RM1`)·라우팅·BOM(리비전 Z0)로 만들기·발행·배정, 데모 지시는 보기만.

| 항목 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 지시 10개(기준 수량 2 BOM: 일반·스크랩 10%·고정 ROLL→KG 8500·가상 조립·부산물·대체 60/40) 만들기 → 발행 | `WO{yyMM}-0001`, 라우팅·BOM 자동, 공정 2·파라미터 2, 소요 SUB 20·RM 8516.5·RM2 2.5(가상 펼침, 경로 `PH`)·부산물 2.5·대체 6/4 | |
| 정상 | 발행 뒤 BOM·라우팅 폐기 / 소수 소요(1/3) | 지시 스냅샷 그대로 / 0.333334(6자리 올림) | |
| B | EA 100.5·지수·완료 < 시작·우선 0 / 사용 중지 공장 / 라우팅 없는 품목·유효 전 날짜 / 다른 품목 BOM / 없는 수주 행·다른 품목 수주 행 | `SCALE`·`FORMAT`·`INVALID_RANGE`·`RANGE` / `INACTIVE` / `NO_ROUTING` / `STALE_REV` / `ORDER_LINE` | |
| F | 만든 뒤 환산 끊김 / 가상 조립 BOM 폐기 / 라우팅 폐기 → 발행 / 연결 수주 행 마감 → 발행 | `WO_NO_CONV`(품목·단위 문구) / `PHANTOM_NO_BOM` / `STALE_REV` / `ORDER_LINE`, 실패 때 스냅샷 0행 | 발행 때 계획 시작일 기준 재확인 |
| F | 두 번 발행 / 발행 뒤 수량·품목 / 일정·우선 / 사유 없는 취소 / 취소 뒤 취소·수정 | `STATE` / `FIXED` / 됨 / `REQUIRED` / `STATE` | |
| 직접 SQL | 계획 지시·지난 발행 지시에 스냅샷 INSERT / 스냅샷 UPDATE·DELETE·지시 DELETE / 공정 CT 변경 / 발행→계획·발행→완료 / 발행 뒤 수량 / 품목 변경 / 다른 품목 라우팅 / 다른 공장 설비 | `ck_prd_wo_snapshot_release`×2 / 42501×5 / `ck_prd_wo_proc_fixed` / `ck_prd_wo_transition`×2 / `ck_prd_wo_released_fixed` / `ck_prd_wo_fixed` / `fk_prd_wo_routing_item` / `fk_prd_wo_proc_equip_plant` | |
| C | 같은 지시 동시 발행 ×3회 | 한쪽만 성공, 공정 2·소요 7 그대로(중복 없음), 500 없음 | 행 잠금 + 조건부 상태 |
| D1 | 공정 설비 배정: 같은 공장 / 다른 공장 / 사용 중지 / 해제 | 됨 / `NO_SUCH_CODE` ×2 / 됨 | |
| H | 기간 없음 / 상태·품목·번호(`_` 글자 그대로) | `REQUIRED` / 기대대로 | |
| A/B | **B사**: A사 지시 상세·발행·취소·수정·공정 배정 / A사 품목으로 만들기 / 직접 SQL A사 품목·라우팅으로 지시 | 404×5 / `NO_SUCH_CODE` / 트리거로 거부, B사 0행 | |
| E | 조회만: 만들기·발행 | 403 | |
| 브라우저 | 새 지시(품목 입력 → 라우팅 Z0·BOM 선택지) → EA 100.5 칸 오류 → 100 저장 → 발행(확인) → 공정·파라미터·소요 260 KG(2.5 × 100 × 1.04) → 수량 칸 잠김·발행 버튼 숨김 → 설비 SPOT-101 배정 / 데모 발행 지시: 수주 연결·소요 4,824 EA(4 × 1200 × 1.005) | 기대대로, 넘침 0(1366·390), 콘솔 오류는 의도한 400 뿐 | **발견·수정**: 기본 조회 기간(지난 30일)이 내일 시작하는 데모 지시를 숨김 → 지난 7일 ~ 앞으로 30일 |

## P2 4단계 LOT·계보 원장 (2026-10-20, V034)

실행: `./gradlew check`(**754건 통과, 실패 0**), 신규 `LotTest` 10 + `DemoDataSeederTest` 1(LOT 단언 추가). 브라우저 Playwright 1366·390 — `ZT-RM1` 자재 LOT 로 등록·분할·병합, 데모 LOT 은 보기만.
**실측**: 10단 × 1,000 LOT(뿌리 1 + 단마다 100) 역추적 **560ms**(MockMvc 왕복 포함, 1,000줄·깊이 10), 정추적 10단 46ms. 깊이 정책 3 → 300줄.

| 항목 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 자재 LOT 등록(KG 1250.5, 공급사) / 분할 30·20.5 → 두 자식 병합(20 만) / 라벨 바꿈 / 보류·해제 | `L{yyMMdd}-0001`, 잔량 49.5 → 0·0.5, 정추적 4줄·역추적 4줄(원 LOT 이 두 경로의 끝, 수량 30·20·20.5) / 원 LOT 잔량 0 / 감사 2건 | 왕복 일치 |
| 원장 | 투입 12.5 → 잔량 87.5·역추적 / 역분개 → 원래 행 그대로 + `-12.5`·CORRECT·원래 id, 잔량 100, 추적에서 빠짐 / 두 번째 역분개·역의 역·분할 역분개 | 기대대로 / 거부 / `NOT_REVERSIBLE` | |
| B | KG 1.2345·판매 거래처·한글 라벨·먼 미래 / 형식 틀린 시각 / 같은 공급사 같은 라벨(다른 공급사는 가능) / 병합 1개·같은 LOT 두 번·다른 품목·잔량 초과 / 보류 사유 없음·보류 LOT 분할·병합·이미 보류 / 잔량 0 라벨 바꿈 | `SCALE`·`NOT_SUPPLIER_LOT`·`FORMAT`·`FUTURE` / `FORMAT` / `DUPLICATE` / `MERGE_MIN`·`DUPLICATE`·`SAME_ITEM_LOT`·`OVER_BALANCE` / `REQUIRED`·`LOT_HOLD`×2·`STATE` / `EMPTY_LOT` | |
| 정책 | `LOT_NO_MODE` ITEM_SEQ / WO(생산 LOT 두 개) / WO 인데 자재 LOT | `RM-L…-0001` / `WO-T1`·`WO-T1-2` / 일련 | |
| 직접 SQL | 계보 UPDATE·DELETE·LOT DELETE / 잔량 초과 / 다른 품목 분할 / 양수 CORRECT / 반만 역분개 / 자기 자신 / 보류 부모 / LOT 수량 변경 / 맞는 역분개 두 번 | 42501×3 / `ck_lot_genealogy_balance` / `_item` / `_qty` / `_reversal` / `_self` / `_hold` / `ck_lot_master_fixed` / 첫 번째 됨·두 번째 23505 | 트리거가 부모 잠금 후 검사 |
| C | 같은 LOT(100) 에서 60 씩 동시 분할 ×3회 | 매번 200·400, 잔량 40 | 부모 `FOR UPDATE` |
| A/B | **B사**: A사 LOT 상세·추적·분할·라벨·보류·번호 찾기 / A사 LOT 을 섞은 병합 / 직접 SQL A사 LOT → B사 LOT 계보 | 404×6 / 404 / `fk_lot_genealogy_lot_comp`, A사 잔량 그대로 | |
| E | 조회만: 추적 200, 등록·분할 403 | 기대대로 | |
| 브라우저 | 자재 LOT 등록(100.0005 KG 칸 오류 → 100) → 분할(30, 20) → 정추적 2줄 → 두 자식 골라 병합 → 역추적 4줄 / 데모 생산 LOT 역추적(너트 1,608 EA·와이어 20 KG) | 기대대로, 넘침 0(1366·390), 콘솔 오류는 의도한 400 뿐 | **발견·수정**: 목록 LOT 번호가 하이픈에서 쪼개짐(긴 코드 칸 지정 해제 — 3단계와 같은 교훈) |

## P2 5a POP 기반 — 단말·배지·hb.pop·작업장 홈 (2026-10-20, V035)

실행: `./gradlew check`(**765건 통과, 실패 0**), 신규 `PopTerminalTest` 11. 브라우저 Playwright — 사무 1366(단말 등록), 태블릿 1280×800·세로 800×1280·휴대폰 390(ZT 단말·ZT 작업자 `ZT-W1`, 데모 지시는 보기만). 시험 단말은 끝난 뒤 폐기.

| 항목 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 단말 등록(라인 L1) → 등록 코드 `XXXXX-XXXXX` → 소문자·공백 섞어 활성화 → 배지 → 홈 | 코드는 해시만(원문 없음), 쿠키 HttpOnly·SameSite=Strict·400일, 값 `회사.id.키` 의 키 해시 = DB, 홈: 아래 셀 C1 의 SPOT 만(L2 의 INSP·계획 지시 제외), 자격 없음 WARN → 자격 주면 OK | |
| 정상 | 작업장 없는 단말 / 두 단말에 같은 작업자 / 작업자 내리기 / 언어 en | 공장 전체(SPOT·INSP) / 둘 다 유지 / 단말은 그대로 / 세션에만, vi 400 | |
| A | 같은 등록 코드 두 번 / 만료 코드 / 없는 회사·형식 아닌 회사·`0O1I` | `TERMINAL_ACTIVATION_FAILED`(구분 없음) | 한 번만, 24시간 |
| A | 틀린 등록 코드 반복(같은 IP) | 20회 뒤 429, 막힌 동안 맞는 코드도 429, 다른 IP 는 됨, `TERMINAL_DENIED` 로그 | `LoginAttemptLimiter` 재사용 |
| E | 다시 발급 → 옛 쿠키 / 키 한 글자 변경 / `garbage` / 없는 단말 id / 회사 정지 / 공장 사용 중지 / 폐기 | 모두 401 `TERMINAL_UNAUTHENTICATED` + 쿠키 지움, 되살리면 다시 됨(폐기 제외) | 인증을 세션에 저장하지 않음 |
| F | 폐기 사유 빈칸 / 폐기 뒤 다시 발급·수정 | `REQUIRED` / 409 | 되돌리지 않음 |
| B | 단말 코드 소문자·공백·빈 이름·없는 공장·PC 유형 / 구역(AREA)을 작업장으로 / 같은 코드(소문자) | 칸 오류 4 / `nodeId NO_SUCH_CODE` / `DUPLICATE` | |
| 배지 | 없는 배지·사용 중지 작업자·4자 미만 | 같은 `BADGE_UNKNOWN`(어느 쪽인지 안 알림) | |
| PIN | 정책 Y + PIN 없음 / PIN `12a4`·`123` / 배지만 → PIN 필요 / 틀린 PIN ×4 / 5번째 / 잠긴 동안 맞는 PIN / 관리자 재지정 → 맞는 PIN / PIN 지우기 | `PIN_NOT_SET` / `FORMAT` / `needPin`(작업자 안 바뀜) / `PIN_WRONG` 남은 4·3·2·1 / 423 `PIN_LOCKED` + 15분 + 로그 / 423 / 됨(세션 ID 바뀜, 횟수 0) / `PIN_NOT_SET` | 틀린 횟수는 분리 트랜잭션(오류 롤백과 무관) |
| A | 틀린 배지 10회(단말) | 11번째 맞는 배지도 429 `BADGE_THROTTLED`, 다른 단말 영향 없음, 1분 뒤 풀림 | 단말별 창 |
| 비활동 | 29분에 사용 → 그로부터 31분 / 사무에서 작업자 사용 중지 | 작업자만 내려감(단말 유지) / 다음 요청에서 내려감·할 일 0 | |
| E | 사무 사용자 → POP API / 단말 쿠키 → 사무 API·`/main` / 쿠키 없이 `/pop`·`/api/pop/home` / CSRF 없이 배지·활성화 | 401·403 / 401·로그인으로 / 활성화 화면·401 / 403 | 경로별 체인 |
| A/B | **B사**: A사 단말 목록·단건·다시 발급·폐기 / A사 작업장으로 등록 / 쿠키의 회사만 B로 / B사 코드를 A사 회사로 / 서로의 배지 / A사 작업자 세션을 B사 단말로 / 직접 SQL A사 행 갱신·A사 회사로 INSERT·A사 작업장 | 0건·404×3 / 칸 오류 / 401 / 실패 / `BADGE_UNKNOWN`×2 / 작업자 없음·할 일 0 / 0건·42501·`fk_sys_terminal_node_plant` | 회사를 쿠키 앞에 두고 그 범위에서만 비교 |
| 직접 SQL | 단말 코드 변경 / 사유 없는 폐기 / 키 남긴 폐기 / 해시 형식 / 만료만 지우기 / 창고·사용 중지 라인을 작업장 / DELETE / 폐기 행 되살리기 / PIN 횟수 -1 | `ck_sys_terminal_fixed` / `_revoke`×2 / `_hash` / `_activation` / `fk_sys_terminal_node_plant`×2 / 거부 / `ck_sys_terminal_revoked` / `ck_mst_worker_pin_fail` | 감사에 키 해시 없음(가림) |
| 브라우저 K | 활성화(소문자 입력) → 숨은 스캔 칸 포커스 → 키보드 웨지 배지 → 내 할 일 3건(WD-L1 아래 셀 + 라인, 자격 경고·OK) / 1280·800·390 / PIN 키패드만으로 / 확정 3연타 / 두 단말 같은 작업자 / 언어 1탭 / 사무에서 폐기 → 새로 보기 | 넘침 0, 48px 미만 버튼 0(키패드 64px) / 틀린 PIN 안내 → 맞는 PIN 로 전환 / 배지 요청 1건 / 둘 다 표시 / en ↔ 한국어 / 활성화 화면으로 | **발견·수정**: 머리띠 근무조가 코드 `D` 만 → "작업일 … · 주간", 단말 관리 시각이 브라우저 로캘(`10/21/2026, 7:50 AM`) → 공통 `hb.fmt.datetime` |
| 기타 | 전체 check 에서 `CustApiTest` 동시 내보내기 시험이 한 번 429 | 잠금을 쥔 스레드가 끝나기 전에 마지막 내보내기를 해서 생긴 경쟁(단독 28/28 통과) | 시험이 스레드 종료를 기다리게 함 |

## P2 5b 착수·실적·투입 (2026-10-20, V036)

실행: `./gradlew check`(**773건 통과, 실패 0**), 신규 `PopWorkTest` 8. 브라우저 Playwright 태블릿 1280×800·800×1280·390 — ZT 지시(`ZT-FG1` 50 EA, 새로 만들어 발행)·ZT 자재 LOT·ZT 작업자, 시험 단말은 끝난 뒤 폐기.

| 항목 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 자격 없음 착수 → 확인 → 착수 / 파라미터 9.2·10 / 실적 양품 10·폐기 2(기공) + 자재 15 KG 투입 / 멈춤(자재 대기) 중 실적 → 재개 → 종료 → 다시 착수 / 다음 공정이 앞 공정 LOT 8 투입 | 409 `QUAL_WARN_ACK` → RUN·지시 진행·이벤트 WARN / OK·NG(최근 값 NG) / 산출 LOT 10·자재 잔량 85·오늘 10/100·직전 불량 기공 / 15 → READY → RUN, 이벤트 5 / 앞 LOT 잔량 2 | 공정 LOT 사슬 |
| 대리 입력 | 실적·투입 계보 | `recorded_by = t{id}:TAB-01`, `performed_by = 작업자`, 계보 `SCAN` | |
| 멱등 | 같은 id 재전송(다른 내용) / 같은 id 다른 공정 / 같은 id 동시 2회 ×3 / 이벤트 재전송 | 처음 행·`replayed`·오늘 합 그대로 / 409 `DUPLICATE_REQUEST` / 둘 다 200·같은 행·행 1 / 같은 이벤트·상태 READY | unique 위반은 새 트랜잭션에서 다시 읽음 |
| 시각 | 발행 직후 시각으로 늦게 도착 / 900초 미래 / 발행 전 / 형식 | 그 시각 그대로·기록 시각은 서버 / `FUTURE` / `RANGE` / `FORMAT` | 정책 `POP_FUTURE_TOLERANCE_SEC` |
| B | 전부 0 / EA 1.5·음수 / 불량 없음·합 다름·종류·없는 유형·중복 / 두 유형 나눔 / 양품 없이 투입 / 없는 LOT·같은 LOT 두 번 / 잔량 초과 / 자릿수 / 보류 LOT | `REQUIRED` / `SCALE`·`NOT_NEGATIVE` / `DEFECT_SUM`×2·`FORMAT`·`NO_SUCH_CODE`·`DUPLICATE` / 됨 / `INPUT_NEEDS_GOOD` / 행 0·2 칸 오류 / 행 0 `OVER_BALANCE` / `SCALE` / `LOT_HOLD`. 실패 요청은 LOT·계보·실적 0(결번 없음) | |
| F | 실적 정정(사유 빈칸 → 사유) / 다시 정정 / 역분개 행 정정 / 뒤 공정이 쓴 LOT 의 실적 / 파라미터 정정 두 번 | 역분개 -10·-1 + 불량 -1 + 투입 역분개(자재 100) + 산출 LOT 보류, 오늘 합 0 / `STATE` / 404 / `LOT_USED` / 두 번째 `STATE`, 최근 값 없음 | |
| C | 두 단말이 같은 공정 동시 착수 ×3회 | 매번 200·400(`STATE`), 착수 이벤트 3 | 공정 행 `FOR UPDATE` |
| E·A/B | 작업자 없이 / 작업장 밖 공정 / 없는 동작 / **B사 단말**: A사 공정 보기·실적·정정·LOT 스캔 / 직접 SQL B사 범위로 A사 공정에 이벤트·실적 | 428 `POP_NO_WORKER` / 404 / 404 / 404×4 / `fk_prd_wo_proc_event_ctx_comp`·`fk_prd_result_ctx_comp` | 멱등 키는 회사 안 |
| 직접 SQL | 원장 4종 UPDATE·DELETE / 이벤트 없이 공정 상태 / RUN → DONE / 진행 아닌 공정에 실적 / 불량 합 다름(커밋 때) / 양품 있는데 LOT 없음 / 거울 아닌 역분개 / CORRECT 인데 원래 행 없음 / 불량 음수 / 사유 없는 멈춤 / 다른 공정 파라미터 | 42501×8 / `ck_prd_wo_proc_event_required` / `ck_prd_wo_proc_transition` / `ck_prd_result_proc_running` / `ck_prd_result_defect_sum` / `ck_prd_result_lot` / `_reversal` / `_qty` / `_defect_sign` / `ck_prd_wo_proc_event_reason` / `fk_prd_param_actual_ctx_comp` | |
| 브라우저 K | 활성화 → 배지 → ZT 지시 카드 → 착수(자격 경고 확인) → 키패드로 양품 10·폐기 1 + 불량 유형 + 스캐너로 자재 LOT·투입 2 → 확정 3연타 / 오프라인 양품 3 확정 → 다시 연결 / 멈춤 사유 6개 → 재개 → 정정 → 종료 / 1280·800·390 | 요청 1건, "오늘 10 / 계획 50 · 산출 LOT · 직전 불량" / "단말에 보관"·"보낼 실적 1건"·오프라인 표시 → 자동 전송·대기 0·오늘 13 / 멈춤 → 정정 줄 취소선·오늘 10 → 준비 / 넘침 0, 48px 미만 버튼 0 | 오류는 의도한 409(자격 확인) 뿐 |

## MES P2 6단계 설비 상태·연결·Collector (2026-10-20, V051(임시 V9101), 브랜치 `claude/mes-work`)

`origin/wip/p2-6-collector`(서비스·수신 API·체인만, 시험 0)를 이어받아 완성. 설계 docs/25 4.8.
전체 `./gradlew check`: **1,055건 통과**(건너뜀 2, 실패 0) + JS 타입 검사·단위 시험. 신규 `EquipEventTest` 9건, `LotTest`·`PopWorkTest`·`FlywayMigrationGuardTest` 단언 추가.

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 정상: PRD → 같은 신호 반복 → UDT(사유) → UDT(사유 없음) → OFFLINE ×2 → ONLINE → PRD | 구간 `PRD@0~10, UDT@10~15, UDT@15~20, UNKNOWN@20~30, PRD@31~…` — 끊김은 비가동이 아니라 UNKNOWN, 다시 연결 뒤 다음 STATE 까지 구간 없음 | — |
| **B 순서·늦게 옴**: 열린 구간 시작보다 이른 STATE(다른 분류·같은 분류·같은 시각), 다시 연결 뒤 마지막 끝보다 이른 STATE | 모두 `LATE`, 원장 그대로 | 과거를 다시 쓰지 않는다(결정 D11) |
| **D 중복**: 같은 eventId 재전송, 배열 안 같은 eventId 두 번 | `duplicates` 로 세고 원장 그대로. 거부(롤백)된 eventId 는 기록이 없어 고쳐서 다시 보내면 받아짐 | `sys_inbox` 는 처리 트랜잭션 안 |
| 배열 5건 중 없는 설비 1·글자 원소 1 | 나머지 저장(받음 2·중복 1), 거부 `EQUIP`·`FORMAT` | 이벤트마다 트랜잭션 |
| seq 1,2,5,3,5,9 | GAP `3-4`·`6-8`, 마지막 seq 9, 재전송(3·5)은 누락 아님. 거부된 이벤트의 seq 도 도착으로 셈 | — |
| PARAM: 진행 중 공정 없음 → 착수 → 스냅샷 파라미터·모르는 메트릭·`1e3`·종료 뒤 | `NO_RUNNING_PROC` → 9.2 OK·9.6 NG(source COLLECTOR, AUTO, 기록 `d{id}:코드`, 작업자 없음), 모르는 것만이면 `NO_PARAM`, `METRIC`, 다시 `NO_RUNNING_PROC`. 진행 중 지시가 상태 구간 `wo_id` 에 | — |
| METER_READING: 겹침·다른 계측기·길이 0·수량 0 | `OVERLAP`·저장·`PERIOD`·`METRIC` | — |
| **A 입력**: `{`·글자·빈 배열·501건·짧은 eventId·모르는 type·미래 시각·시각 형식·state 없음·online=maybe | 400 `BAD_REQUEST`/`TOO_MANY_ROWS`, 건별 `EVENT_ID`·`TYPE`·`FUTURE`·`OCCURRED_AT`·`STATE`·`CONN` | — |
| **G 범위**: 장치 작업장(L1) 밖 설비·사용 중지 설비·없는 설비·설비 없는 STATE | 모두 `EQUIP`, 원장·inbox 0건. 공장 전체 장치는 L2 설비도 됨 | — |
| **E 인증**: 키 없음·틀림·끝 두 글자 바꿈·Basic·사람 세션·POP 쿠키로 /ext·수집 장치 키를 POP 쿠키로 | 전부 401. 응답에 Set-Cookie 없음, CSP 헤더 있음 | — |
| 다시 발급 / 폐기 | 옛 키 즉시 401·새 키 됨 / 폐기 즉시 401·다시 발급 409. 거부는 접속 로그 `TERMINAL_DENIED`(BEARER) | — |
| **F 흐름 우회**: 수정 화면·직접 SQL 로 수집 장치 → POP | 400 `terminalType` `FIXED` / `ck_sys_terminal_collector_fixed` | **발견·수정**(WIP 는 바꿀 수 있었다) |
| **C 동시**: 빈 설비에 STATE 두 건 동시 ×3회 | 둘 다 받거나(순서대로) 늦은 쪽 `LATE` — `OVERLAP`·`ERROR` 없음 | **발견·수정**: WIP 는 한쪽이 배제 제약에 걸려 사라졌다 → 설비 단위 advisory 잠금 |
| **A사로 B사**: A 키로 같은 설비 코드 / 키 앞 회사를 B 로 바꾼 위조 키 / B 화면 같은 공장 코드 / B 장치 seq | A 설비에만 · 401 · A 구간 0건 · seq 행은 장치·회사별 | — |
| 직접 SQL(B 범위): A 설비·A 장치로 원장 행, 회사 코드를 A 로 쓴 사유 | `fk_cms_equip_state_log_ctx_comp`·`fk_cms_equip_conn_log_ctx_comp`·RLS 위반 | — |
| 직접 SQL 원장: 분류 UPDATE·DELETE / 닫힌 구간 다시 닫기·되열기 / 시작보다 앞에서 닫기 / 겹치는 구간·두 번째 열린 구간 / UNKNOWN 에 사유 / 연결·에너지 UPDATE·DELETE / 설비 있는 GAP | 42501(열 권한은 `end_at` 만) / `ck_cms_equip_state_log_close_once` / `ck_…_range` / `ex_cms_equip_state_log_overlap` / 거부 / 42501 / `ck_cms_equip_conn_log_gap` | — |
| 직접 SQL 에너지 역분개: 양수·사유 NULL·정상·두 번째 | `ck_cms_energy_reading_qty` ×2 · 저장 · `uq_cms_energy_reading_reversal` | — |
| **직접 SQL: 사유 NULL 인 역분개**(`lot_genealogy`·`prd_result`·`prd_param_actual`) | 처음 제약은 통과시켰다(`length(btrim(NULL)) > 0` = NULL → CHECK 통과) | **발견·수정**: V051 이 `ck_*_reversal_reason` 추가, LotTest·PopWorkTest 에 단언 |

#### 브라우저 확인 (local, ZT 데이터: 라인 ZT-L1·설비 ZT-EQ1·ZT-EQ2·사유 ZT-JAM·수집 장치 ZT-COL-01)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 단말 관리 1366: 신규 → 유형 "수집 장치" → 작업장 ZT-L1 → 저장 | 제목 "API 키 (지금 한 번만 보입니다)", 키 50자 줄 바꿈 표시, 버튼 "API 키 다시 발급", 유형 선택지 POP·PDA 잠김 | — |
| 가짜 수집기 `tools/fake-collector.mjs` 8시간치(실제 HTTP) | 195건 → 받음 141, `NO_RUNNING_PROC` 54(ZT 설비에 진행 중 지시 없음 — 정상), 건너뛴 seq 4개 → 연결 로그 "수집 누락" | — |
| 같은 수집기 2시간치 다시 | `LATE` 8·`NO_RUNNING_PROC` 6, 나머지는 끝 이후 새 구간 | 수집기는 시각 순서로만 다시 보낸다(D11) |
| 틀린 키 | `HTTP 401 UNAUTHENTICATED` | — |
| 타임라인 1366·390 | 막대 41구간(PRD·SBY·UDT·UNKNOWN 회색), 범례 글자, 설비별 분류 분 합계, 구간 표 41행, 연결 로그(연결·끊김·수집 누락), 가로 넘침 없음 | **발견·수정**: 처음에 `hb.input.js` 를 안 실어 `hb.searchPanel is not a function`(시험으로는 못 잡음) |
| 콘솔 오류 | `/favicon.ico` 404 하나 — 모든 화면 공통(이번 변경 아님) | 남김 |

남은 것: 사유 마스터 화면(CMS 세션), POP 멈춤 사유 공통코드 → `cms_state_reason` 이관(7단계 이후), COUNT → 실적 제안(7단계).

## MES P2 7a 완료판정·지시 완료·마감·생산 입고·재작업/수리 처리 (2026-10-20, V055(임시 V9102), 브랜치 `claude/mes-work`)

설계 docs/25 4.9(결정 D14~D16). 전체 `./gradlew check`: **1,075건 중 1건 실패 → 고친 뒤 해당 클래스 재실행 통과**(아래 "발견"), 나머지 1,074건 통과. 신규 `CompletionReceiptTest` 5건.

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 정상: SPOT 양품 10·폐기 1·재작업 2 → 종료 → 대기 2 라 완료 불가 → 처리(양품 1 = 새 LOT, 폐기 1) → 완료판정(첫 공정 투입 = 처리 합계 13, 유효 양품 11) | 판정 행 `13/11/Y/admin1`, 공정 DONE, DONE 공정 재착수 `STATE` | — |
| INSP 양품 9·폐기 1 = 10 ≠ 투입 11(허용 0%) → 공장 정책 10% | `BALANCE`(문구에 투입·처리·허용) → 차이 −1 ≤ 1.1 이라 완료 | — |
| 입고: 만든 양(9) 넘게·6·같은 요청 id 재전송·지시 완료 뒤 3·정정(사유 빈칸·정상·두 번째)·마감(미입고 6)·마감 뒤 입고·정정 | `OVER_RECEIPT`·저장·`replayed`·저장·`REQUIRED`/저장/`STATE`·마감됨·`STATE`·`STATE` | 미입고는 마감을 막지 않는다(D16) |
| **F 순서 우회**: 진행 중(RUN) 공정 판정·앞 공정 미완료·실적 없음·공정 미완료 지시 완료·완료 전 마감 | `STATE`·미리보기 `STATE, PREV_OPEN, NO_RESULT`·`PROCS_OPEN`·`STATE` | 실적 없는 공정에는 보존식 이유를 붙이지 않는다(엉뚱한 "불일치" 방지) |
| **F 되돌리기**: 처리된 재작업의 실적 정정 / 완료된 공정의 실적 정정 / 완료된 공정에 처리 | 둘 다 4xx·역분개 행 0 / `STATE` | 트리거 `ck_prd_result_rework_disposed`·`ck_prd_result_proc_done` |
| 입고 대상 아님: 첫 공정 LOT·창고 아닌 노드·보류 LOT | `LAST_OP`·`NOT_WH`·`LOT_HOLD` | — |
| **D 중복**: 재작업 처리 같은 요청 id(같은 공정 / 다른 공정) | 처음 응답 `replayed` / 409 `DUPLICATE_REQUEST` | — |
| **C 동시**: LOT 10 에 6 + 6 동시 입고 ×3회 | 매번 하나만 200, LOT 합 6 | 트리거가 LOT 행 FOR UPDATE 뒤 판정 |
| **A사로 B사**: B 관리자가 A 지시 완료판정 조회·판정·처리·완료·입고 화면·입고 | 모두 404, B 창고 목록 0건 | — |
| 직접 SQL(B 범위): A 지시·공정·LOT 로 처리·입고·판정 행 | `fk_prd_rework_ctx_comp`·`fk_prd_receipt_ctx_comp`·`fk_prd_wo_proc_close_ctx_comp` | — |
| 직접 SQL 원장: 판정 행 없이 상태만 DONE / 대기 남은 판정 / 칸 값 지어낸 판정 / WAIT 공정 판정 / 공정 미완료 지시 DONE / DONE → READY | `ck_prd_wo_proc_close_required`·`…_pending`·`…_values`·`…_state`·`ck_prd_wo_procs_done`·`ck_prd_wo_proc_transition` | — |
| 직접 SQL 처리: 양품인데 LOT 없음·수량 합 다름·대기 넘김 / 원장 3종 UPDATE·DELETE | `ck_prd_rework_lot`·`…_qty`·`…_pending` / 42501 | BEFORE 트리거가 CHECK 보다 먼저 — 시험 순서를 맞춤 |
| 직접 SQL 입고: 다른 품목·LOT 초과·거울 아닌 역분개·사유 없는 역분개·두 번째 역분개 | `ck_prd_receipt_wo_match`·`…_over`·`…_reversal`·`…_qty`·23505 | — |
| **발견**: 전체 check 에서 `WorkOrderTest` 1건 실패 — 직접 SQL RELEASED → DONE 의 오류가 전이 표(`ck_prd_wo_transition`) 대신 새 "공정 미완료" 로 나옴 | BEFORE 트리거는 **이름순** — 새 `tg_prd_wo_done` 이 `tg_prd_wo_guard` 보다 먼저 돌았다 | 새 트리거 이름을 기존 가드 뒤로(`tg_prd_wo_status_done`·`tg_prd_result_reversal_guard`) |

#### 브라우저 확인 (local, DB 다시 만든 뒤, ZT 데이터: 품목 ZT-FG·공정 ZT-SPOT/ZT-INSP·라인 ZT-L7·창고 ZT-WH/ZT-LOC1·작업자 ZT-W1)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 지시 발행 → POP API 로 SPOT 실적(10/1/재작업 2)·종료 → 작업지시 상세 1366 | 완료판정 표: SPOT "재작업·수리 대기" + [처리], INSP "종료 전·앞 공정 미완료·실적 없음" | — |
| [처리] 양품 1·폐기 1 → [완료판정](확인 창에 첫 공정 안내) | "새 LOT L261020-0005", SPOT 완료 `13* / 10(+1)` | — |
| INSP 9 + 폐기 1 | "보존식 불일치 −1 (±0)" | — |
| INSP 다시 착수 → 양품 1 → 종료 → 완료판정(투입 11·처리 11·차이 0) → [지시 완료] | 마지막 공정 양품 10 · 미입고 10, [마감] 나타남 | — |
| 생산 입고 1366: LOT 2개(9·1), ZT-LOC1 에 99 → 6 → 정정(사유) → 4 | "남은 입고 수량(9)보다 많습니다" → 이력 3줄(정정됨·−6·4), 가로 넘침 없음 | — |
| [마감] 확인 창(미입고 6 안내) → 마감 뒤 API 입고 | 400 | **발견·수정**: 확인 문구가 "…없습니다.미입고" 로 붙음 — `.properties` 값 앞 공백은 잘린다 → JS 에서 공백 |
| 390: 생산 입고 서랍·작업지시 상세 | **발견·수정**: 입고 서랍에서 표가 서랍보다 넓어 오른쪽(닫기·상태 칸)이 잘림 → 표를 스크롤 상자 안으로. 다시 재서 서랍 0~390·닫기 버튼 안 | — |
| 콘솔 | `/favicon.ico` 404(전 화면 공통) + 의도한 400 두 건(초과 입고·마감 뒤 입고) | — |

## MES P2 7b LOT 역추적 보고서·LOT 단위 재작업/수리 (2026-10-20, V056(임시 V9103), 브랜치 `claude/mes-work`)

설계 docs/25 4.10(결정 D17·D18). 전체 `./gradlew check`(최신 main 병합 뒤, 다른 작업 없이 단독 실행): **1,105건 통과**(건너뜀 2, 실패 0) + JS 타입 검사·단위 시험. 신규 `LotReportTest` 3건. 마지막 화면 수정(인쇄 버튼 위치·표 최소 폭) 뒤 가드 시험·LotReportTest 다시 실행 통과.

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 정상: 자재 15 KG → SPOT 양품 10(파라미터 OK·NG) → INSP(SPOT LOT 10 투입) → 두 공정 완료판정 → 입고 7 → 완제품 LOT 보고서 | 역추적 SPOT LOT ← 자재 LOT(15), 생산 이력 SPOT·INSP(작업자·단말), 파라미터 OK·NG(하한 8.5), 판정 2건(INSP 투입 10), 입고 WH1-A01, 정추적 없음, 만든 사람·시간대 | — |
| 자재 LOT 보고서 | 생산 이력 없음, 정추적 SPOT LOT → 완제품 LOT | — |
| 없는 LOT 번호·빈칸 | 404 | — |
| **권한**: 보고서 권한 없는 사용자 | 403 | — |
| **A사로 B사**: A 의 LOT 번호로 B 조회 → B 가 같은 번호 LOT 을 가진 뒤 조회 | 404 → B 의 LOT 만(B 품목), 역추적·생산 이력·입고 0건 — A 기록 섞이지 않음 | — |
| 보류 LOT: 분할 / 재작업 종류 SORT·사유 없음·잔량 넘김 / 재작업 3 / 수량 비우고 수리 / 잔량 0 에서 다시 | `LOT_HOLD` / `CODE`·`REQUIRED`·`OVER_BALANCE` / 새 LOT `REWORK/N/3`·계보 REWORK·부모 잔량 7·보류 그대로 / `REPAIR7` / `EMPTY_LOT` | 보류의 출구는 재작업·수리만(D18) |
| 재작업 LOT 보고서 | 역추적 3단(보류 완제품 ← SPOT ← 자재), 생산 이력 SPOT·INSP | 생산 이력 대상 = 이 LOT 과 역추적의 생산 LOT |
| 직접 SQL: 보류 LOT 에서 분할 / 재작업 LOT 을 분할로 태어나게 / 보류 LOT 에서 재작업 | `ck_lot_genealogy_hold` / `ck_lot_genealogy_origin` / 허용 | — |
| 재작업 LOT 을 생산 입고 | `LAST_OP` | 재작업 LOT 의 입고 경로는 P4 |
| LOT 목록 출처 REWORK 필터 | 200(전에는 출처 목록 밖) | `LotService.ORIGINS` 에 추가 |

#### 브라우저 확인 (local, DB 다시 만든 뒤, ZT 데이터 — 7a 와 같은 품목·공정 + ZT 자재 LOT·단말 ZT-TAB-7B)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 자재 LOT 등록 → 지시 발행 → POP API 로 SPOT(자재 6 KG 투입)·INSP(SPOT LOT 투입) → 완료판정 → 입고 5 → 보고서 1366 | 절 8개: 역추적 2줄(생산 ← 자재 입고), 생산 이력 2·판정 2·입고 1, 들여쓰기, 가로 넘침 없음 | — |
| 인쇄 미리보기(print 미디어)·PDF | 조회 칸·도구·인쇄 버튼 숨김, 표 전체, A4 PDF 생성(292 KB) | — |
| 390 | **발견·수정**: 머리 도구에 [인쇄]까지 세 개라 버튼 글자가 한 자씩 접힘 → [인쇄]를 보고서 위 도구 줄로. 표가 글자 단위로 접힘 → 표 최소 폭 720px + 상자 안 가로 스크롤(인쇄 때는 해제). 다시 재서 넘침 없음 | — |
| LOT 추적: 완제품 LOT [보류] → [분할] 숨김·[재작업] 보임 → 재작업 2(사유) → 보고서 링크 | 새 LOT 생성, 부모 잔량 3·보류 유지(보고서 머리에 "보류 — ZT 확인 보류"), 링크 `/page/prd-lot-report?lotNo=…` | — |
| 콘솔 | `/favicon.ico` 404 하나(전 화면 공통) | — |

## MES P2 7c ErpPort·가짜 ERP·ERP 업로드용 엑셀·데모 관통 시드 (2026-10-20, V066(임시 V9104), 브랜치 `claude/mes-work`)

설계 docs/25 4.11(결정 D19~D21). 전체 `./gradlew check`(최신 main 18b9a41 병합 뒤 — 홈 3단계 V058·APS 2단계 V059·PLM 3b V060·WMS W3 포함, 2026-10-21 단독 실행): **1,189건 통과**(건너뜀 2, 실패 0) + JS 타입 검사·단위 시험(`-Phb.requireNode=true`). 첫 기록은 main 0e165e1 기준 1,146건 — 차이는 main 에 들어온 시스템관리 2단계·문구 중복 키 가드 시험. 신규 `ErpTest` 7건, `DemoDataSeederTest` 관통 1건. 마지막 화면 수정(JS 한 줄) 뒤 타입 검사·단위 시험·브라우저 다시 확인.

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 정상(FAKE): 지시 완료 → `ERP_WO_RESULT` 본문(ERP 품목 코드·마지막 공정 유효 양품 10·폐기 1·지시 번호), 입고 3 → 정정 → `ERP_RECEIPT` 2건(3·-3, 창고 코드) → 릴레이 | SENT 2, 가짜 ERP 전표 키별 1건 | 송신기는 DB 없이 본문만(아웃박스 규칙) |
| 정책 EXCEL·NONE 에서 완료·입고 | 아웃박스 ERP 행 0 | 엑셀 화면은 정책과 무관하게 열림(D21) |
| ERP 품목 매핑 없음 → 송신 | FAIL `REJECTED: 400 ITEM_UNMAPPED`, 전표 0 | 사람이 매핑 후 처리 |
| 호출 제한: 모든 호출 429 → 제한 풀고 재시도 시각 당김 | 1회차 NEW(`RATE_LIMITED`, 재시도 대기)·전표 0 → 2회차 SENT·전표 1 | — |
| **저장 뒤 타임아웃(멱등 없는 상대)** → 다시 릴레이 | FAIL `UNKNOWN`, 상대 전표 1(들어갔음) → 다시 집지 않음·전표 그대로 1 | 자동 재전송 없음(D19) — 중복 전표 방지 |
| 엑셀 미리보기: 지시 실적·입고(정정은 음수·입력 방식 정정) / 매핑을 끄면 | 건수·수량·ERP 코드 일치 / 경고 3건·ERP 코드 칸 비움 | — |
| 엑셀 파일: 머리 열·ERP 코드·숫자 셀(-6)·창고 코드 | 일치(Apache POI 로 읽어 확인) | — |
| **같은 기간 두 번 내보내기** | 둘 다 200, 기록 2건(종류·건수·사람), 미리보기에 이전 내보내기 2 — 다른 종류엔 0 | 막지 않고 알림(D20) |
| 조건 오류: 종류 오타·빠짐 / 기간 거꾸로·93일 넘음·시작 빠짐 / 거꾸로 기간으로 엑셀 | `kind` `CODE`·`REQUIRED` / `INVALID_RANGE`·`RANGE_TOO_LONG`·`INVALID_RANGE` / 400·기록 0 | — |
| **권한**: 조회만 → 엑셀 / 권한 없음 → 조회 | 403 / 403 | — |
| **A사로 B사**: B 미리보기·엑셀·이전 내보내기 / B 릴레이 / 가짜 ERP B 키 / 직접 SQL B 범위에서 A 회사 코드로 기록 | 0건·빈 파일·0 / A 메시지 안 집음 / 0 / 42501(RLS) | — |
| 직접 SQL: 종류·기간 거꾸로·음수 건수 / 기록 UPDATE·DELETE / 진행 중 지시에 `done_at` | `ck_prd_erp_export_kind`·`_range`·`_cnt` / 42501 / `ck_prd_wo_done_at` | — |
| 데모 시더 관통(빈 회사): 두 번 실행 | 1회차 실적 2·판정 2·완료(`done_at`)·입고 596·강판 LOT 계보·정책 FAKE·ERP 메시지 2(매핑 있음) / 2회차 0 | — |

#### 브라우저·실제 기동 확인 (local, DB 다시 만든 뒤, ZT 데이터 — 7a 와 같은 품목·공정, 단말 ZT-7C-*)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 기동 시 데모 관통 시드 → 5초 릴레이 | `ERP_WO_RESULT WO:WO2610-0002`·`ERP_RECEIPT RCV:1` SENT(1회) | — |
| ZT 지시: 자재 LOT → SPOT·INSP 실적 → 완료판정 → 완료 → 입고 3 → 정정 → 입고 5 → ERP 엑셀 화면 1366(기본 지시 실적·지난 7일) | 데모 행(E00003) + ZT 행 "(매핑 없음)" 경고색, 경고 문구, 정책 FAKE 표시, 넘침 없음 | ZT 품목은 매핑을 일부러 넣지 않음 |
| 종류를 입고로 바꿈 → 조회 전 [엑셀] / 조회 → [엑셀] | 비활성 / 내려받기·이전 내보내기 목록 갱신 | — |
| 내려받은 xlsx | 11열, 행 4(데모 1 + ZT 3), 정정 -3, 입력 방식 이름 | — |
| 390 | 조회 칸 세로 배치, 표는 상자 안 가로 스크롤, 페이지 넘침 없음 | — |
| **발견·수정**: [엑셀] 뒤 이전 내보내기 목록을 갱신하려 다시 조회하면 "엑셀 파일을 내려받았습니다." 가 바로 지워짐 | 다시 조회가 끝난 뒤 알림 → 문구 유지·목록 3건 | — |
| 관찰: Playwright 가 내려받은 이름을 `download` 로 보고 | 응답 헤더 `filename*=UTF-8''ERP 업로드용 엑셀_….xlsx` 정상, 품목 엑셀과 같은 공통 경로 | 조치 없음(브라우저 자동화 쪽 표시) |
| 관찰: 390 에서 기간 라벨 "기간" 이 두 줄로 접힘 | 공통 `hb.dateRange` 표시 — 활용 현황 화면과 같음 | 화면 공통 세션 몫 |
| 콘솔 | `/favicon.ico` 404 하나(전 화면 공통) | — |

## MES P2 3c PLM 연결 — 계보 설계 변경 표시 사본·새 LOT 세 곳 표시 (2026-10-21, V077(임시 V9105), 브랜치 `claude/mes-work`)

설계 docs/25 4.12(docs/39 12.3·13장의 MES 몫). 전체 `./gradlew check`(최신 main V073 병합 뒤, 단독 실행): **1,348건 통과**(건너뜀 2, 실패 0) + JS 타입 검사·단위 시험. 신규 `PlmChangeLinkTest` 5건 — 실적은 실제 POP API, 7a 처리·7b LOT 재작업은 사무 API, ECO 는 PLM API(작성 → 결재 → 적용). 품목만 대상인 ECO(유효일 오늘 + 첫 N).

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 정상: 첫 4 ECO — 5b 투입 있는 실적 → 7a 재작업 처리 → 5b 투입 없는 실적 → 7b LOT 재작업 | 네 LOT 모두 `plm_eco_lot` 표시. 투입 CONSUME·REWORK 계보 `change_id` = ECO, 투입 없는 실적은 계보 행 0(표시만), LOT 상세 응답 `children[].changeId` | — |
| 첫 N 넘음: 다섯째 투입 실적 | 표시 없음, `change_id` NULL, 실적 정상 | 표시는 게이트가 아님 |
| 실적 정정(POP 역분개) | 투입 역분개 행 `change_id` = 원래 값, 표시는 남음(LOT 보류여도) | 서비스 역분개가 원래 값을 싣는다 |
| 직접 SQL: B사 ECO id·없는 id 로 계보 / 역분개를 `change_id` NULL 로 / 계보 `change_id` UPDATE | `fk_lot_genealogy_ctx_comp` ×2 / `ck_lot_genealogy_reversal` / 거부(원장) | 트리거가 FK 보다 먼저 회사 확인 |
| **D 잠금 순서**: 자재 LOT 을 쥔 트랜잭션(곧 같은 ECO 에 표시)과 그 자재를 투입하는 POP 실적이 동시에 | 둘 다 성공, 표시 2건, 계보 `change_id` = ECO | **옛 순서(산출 LOT → 표시 → 투입)로 되돌려 돌리면 교착 40P01 → 500** — 투입 자재 LOT 을 id 순으로 먼저 잠그도록(`lockForConsume`) 해서 MES → ECO 순서 유지 |
| **A사로 B사**: B사 범위 표시 / A사 사용자로 B사 LOT 상세·LOT 재작업 | 빈 값(같은 품목 코드여도 A사 ECO 안 보임)·A사 ECO 표시 0 / 404·404, B사 계보 0 | — |
| 회귀: LotTest·PopWorkTest·CompletionReceiptTest·LotReportTest·PlmEcoLotTest·DemoDataSeederTest | 통과 | — |

- 미확인: PLM 이 없는 설치본(`ChangeMarker.NONE`)은 플랫폼 계약 시험(`PlmLinkContractTest`)에 맡겼다 — 이 저장소는 PLM 빈이 늘 있다.
- 화면 변경 없음(응답에 `changeId` 칸만 추가) — 브라우저 확인 안 함.

## MES 홈 카드 링크용 조회 칸 — prd-wo·prd-lot·sal-order (2026-10-21, V081(임시 V9106), 브랜치 `claude/mes-work`)

설계 docs/25 4.13(홈 H17, docs/37 16장). 전체 `./gradlew check`(최신 main V078 병합 뒤, 단독 실행): **1,392건 통과**(건너뜀 2, 실패 0) + JS 타입 검사·단위 시험. 신규 `HomeLinkFilterTest` 5건 — 카드 숫자는 실제 홈 API(`/api/home/cards/{code}`)로 읽어 링크 조건 목록 건수와 비교.

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 지시: 카드 `prd-wo-today` vs `status=ACTIVE&to=오늘`(시작 비움) | 둘 다 2(400일 전 발행 지시 — 366일 상한 밖 — 포함), 묶음만이면 앞날 발행까지·계획 상태 제외, 닫힌 기간·번호와 함께도 | — |
| LOT: 카드 `prd-lot-hold` vs `holdOnly=Y`(기간 없음) | 둘 다 1(500일 전 등록 보류 LOT), 잔량 0 보류 LOT 제외, 최근 30일과 함께면 0, `N` 은 기존 그대로 | — |
| 수주: 카드 `sal-due-soon`(열린 행 수) vs `dueTo=오늘+7` | 목록 수주 2건 + `dueLineCnt` 3 = 카드, 취소 행 납기는 안 셈, 수주일 기간과 함께면 그 수주 행만, 납기 조건 없으면 `dueLineCnt` 없음 | 목록은 머리 단위라 행 수를 따로 — 카드와 같은 단위 |
| A 값: 상태 `active`(소문자)·`holdOnly=yes`·날짜 형식·순서 거꾸로·지시 400일·납기 거꾸로 | 400 `status CODE`·`holdOnly CODE`·`FORMAT`·`INVALID_RANGE`·`RANGE_TOO_LONG`·`dueTo INVALID_RANGE` | 순서·상한은 두 끝이 다 있을 때만 |
| 권한 없는 사용자 | 403 | — |
| **A사로 B사**: 같은 데이터 두 회사 + B사만 보류 하나 더 | 카드·목록 모두 자기 회사 숫자(A 1·B 2), 지시·수주도 A 그대로 | — |
| 기존 시험 `WorkOrderTest`·`SalesOrderTest` 의 "기간 빠지면 REQUIRED" | 열린 기간으로 규칙이 바뀌어 "빈 기간 = 전체(최근 순)" 로 단언 변경 | 설계 변경(4.13) — 의도된 회귀 |

#### 브라우저 (local, 전용 DB 컨테이너, DEMO 회사 + ZT 데이터: 400일 전 발행 지시·500일 전 보류 LOT·400일 전 수주의 열린 행)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 홈 링크 주소 그대로 `/page/prd-lot?q.holdOnly=Y&q.from=&q.to=` · `/page/sal-order?q.dueTo=…&q.from=&q.to=` (1366·390) | 기간 칸 비워짐·스위치 켜짐·납기 끝 채워짐, 카드 숫자 = 목록("1건" / "1건 · 납기 행 2건"), 가로 넘침 없음. 기간 두 개짜리 조회 패널 정상 | — |
| `/page/prd-wo?q.status=ACTIVE&q.from=&q.to=오늘` | **처음엔 기간이 지난 7일~앞 30일로 덮여 카드 1 ≠ 목록 2** | 화면이 `sp.ready`(링크 적용) **뒤에** 기본 기간을 넣고 있었다 → 기본 기간을 ready 전에. 고친 뒤 시작 빈칸·끝 오늘, 카드 1 = 목록 1. 홈 1단계 **항목** 링크(번호 + 계획 시작 하루)도 같은 이유로 덮이던 것 → 이제 그대로 적용 |
| 링크 없이 열기 | 지시 기본 기간(지난 7일~앞 30일)·수주 기본(지난 30일, 납기 빈칸) 그대로 | — |
| 관찰: 390 에서 "납기(열린 행)" 라벨 두 줄 | 공통 `hb.dateRange` 표시(기존 "기간" 라벨과 같음) | 조치 없음 |
| 콘솔 | `/favicon.ico` 404 하나(공통) | — |

## MES 생산 입고 → 재고 원장 연결 (2026-10-21, 마이그레이션 없음, 브랜치 `claude/mes-work`)

설계 docs/25 4.14(docs/30 11장 WMS B1 계약). 전체 `./gradlew check`(main V081 병합 뒤, 단독 실행): **1,406건 통과**(건너뜀 2, 실패 0) + JS 검사. 그 뒤 main(PLM 2단계 V082)을 병합하고 문구 가드·입고·Flyway 가드 시험만 다시 돌림(통과). `CompletionReceiptTest` 에 1건 — 실제 MES 입고·정정 API 끝에서 끝까지(WMS 자체 시험 `ReceiptPostingTest` 는 `post()` 를 직접 부른다).

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 정상: 마지막 공정 LOT 5 입고 | `inv_trx` RCV_PRD 5·AVAILABLE·같은 LOT | — |
| D 같은 요청 id 다시 | 처음 응답, 재고 원장도 1행 | 멱등(MES 요청 id + WMS 키) |
| C 그 사이 4 를 내보낸 뒤 정정(-5) | 409 `INSUFFICIENT_STOCK` + 칸 오류 `STOCK_MOVED`("되돌린 뒤 정정"), 정정 행 0·재고 그대로 | WMS 오류를 MES 문구로 감쌈 |
| 되돌려 놓은 뒤 정정 | 200, 재고 역분개, 순재고 0 | — |
| 재고 안 둠 위치(`wms_loc_attr.stock_yn='N'`)로 입고 | 400 `nodeId`/`STOCK_NOT_ALLOWED`, 입고 행 안 남음 | 칸 이름이 같아 변환 없음 |
| 회귀: 데모 시더(관통 시드 입고)·`ReceiptPostingTest`·`ErpTest` | 통과 | — |

- A사로 B사·마감 달·동시 입고는 WMS `ReceiptPostingTest` 와 기존 `CompletionReceiptTest`(A사로 B사·동시 입고)가 맡는다 — 이번 변경은 그 경로에 호출만 더했다.
- 브라우저 미확인: 입고 화면은 오류의 칸 문구를 알림 줄에 잇는 기존 `errText` 를 그대로 쓴다(화면 코드 변경 없음).

## MES 공장 휴일 달력 (2026-10-21, V086(임시 V9107))

설계 docs/25 4.15(docs/35 A19, docs/38 A20, 결정 D22~D25). 전체 `./gradlew check`(main V083 병합 뒤, 단독 실행): **1,428건 통과**(건너뜀 2, 실패 0) + JS 검사. 그 뒤 main(2944c08 — 홈 H17 카드 링크·PMS V084)을 병합하고 홈·링크·달력·가드 시험을 다시 돌림(통과). 신규 `WorkCalendarTest` 3(순수 계산)·`WorkCalApiTest` 5(실제 DB·API).

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 층 우선순위(순수): 기본 / 주간 SUN·SAT_SUN·모르는 값 / 회사 휴일 위 공장 대체 근무 / 공장 근무조 특근(주간 휴무 위) / 평일 야간조만 휴무 / 다른 공장 행 무시 / 회사 층만 읽기 | 근무조 > 공장 하루 > 회사 > 주간 > 기본 순으로 판정, 특근 날은 그 근무조만 | — |
| 한 달 조회: 회사 정책 SAT_SUN + P1 정책 SUN(공장 덮어쓰기) | 회사 화면 토·일 쉼·회사 휴일, P1 화면 토요일 근무·대체 근무·일요일 주간조만 | 정책 서비스의 공장 값 → 회사 값 |
| 읽기 계약 `isWorkday`(회사·공장)·`shiftsOn`·`range` 400일 / 401일 | 같은 판정, 401일 거부 | — |
| 고치기: 날짜를 바꿔 보내도 구분·사유만 / 옛 rowVer / 사용 중지 → 다시 같은 날 | 날짜 그대로 / 409 / 다시 넣기 가능(사용 중 행만 유일), 사용 중지 행 다시 끄기 409 | — |
| A 값: 회사 범위 근무조·없는 조·사용 중지 조·다른 공장 조·없는 공장·날짜 형식·구분 코드·빈 사유·200자 초과·달 형식·달 빠짐 / 중복 / 권한 없음 | `SHIFT_NEEDS_PLANT`·`NO_SUCH_CODE`·`INACTIVE`·`NO_SUCH_CODE`·`plantCd NO_SUCH_CODE`·`FORMAT`·`CODE`·`REQUIRED`·`TOO_LONG`·`FORMAT`·`REQUIRED` / `calDate DUPLICATE`(회사·공장·하루·근무조는 서로 다른 칸) / 403 | — |
| C 직접 SQL: 날짜 바꾸기 / 다른 공장 근무조 / 공장 없는 근무조 / 삭제 | `ck_mst_work_cal_key_fixed` / `fk_mst_work_cal_shift` / `ck_mst_work_cal_shift_plant` / 거부(트리거 + 권한 회수) | **처음엔 BEFORE 트리거가 CHECK 보다 먼저 돌아 "공장 없는 근무조" 가 소속 오류로 보였다** → 트리거는 공장이 있을 때만 소속 확인 |
| **A사로 B사**: B 조회 / B 가 A 행 고치기·끄기 / B 범위 읽기 계약 / B 범위에서 A 회사 코드로 넣기 | 0건·A 휴일 안 보임 / 404·404 / 영향 없음 / 거부 | — |
| 회귀: `WorkforceTest`·`RetentionTest`·`I18nKeyGuardTest`·`ProgAuthCoverageTest`·`TemplateSecurityGuardTest`·`DesignTokenGuardTest`·`FlywayMigrationGuardTest` | 통과 | — |

#### 브라우저 (local, 전용 DB 컨테이너, DEMO + ZT 데이터, 1366·390)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 첫 열기 | **"Cannot read properties of undefined (reading 'today')"** — 템플릿에 `hb.dates.js` 를 안 실음(타입 검사는 전역 선언이라 못 잡는다) | 스크립트 추가 |
| P1·2026-12: 12-24 눌러 하루 쉼 저장 → 12-27(일) 눌러 근무조 D·일함·사유 저장 → 신규로 12-24 또 | 저장 2건, 달력 칸 회색+사유 / 목록 2행 / 중복은 `calDate` 칸 오류 | — |
| 근무조 예외만 있는 날의 칸 | **처음엔 사유가 안 보였다**(하루 예외 사유만 표시) | 칸에 근무조 예외 사유도 표시 |
| 회사 정책 `WEEKLY_OFF=SAT_SUN` 으로 바꾼 뒤 | 토·일 음영 + "주간 휴무", 12-27(일)은 주간조 특근으로 일하는 날·근무조 D 만 | — |
| 390 | 도구줄 접힘, 달력 7칸 유지, 가로 넘침 없음 | — |
| 콘솔 | 중복 저장의 400(의도) + `/favicon.ico` 404(공통) | — |

## MES 사무 실적 조회 prd-result (2026-10-21, V090(임시 V9108))

설계 docs/25 4.16(결정 D26~D29 — 사용자 확인 2026-10-21). 전체 `./gradlew check`(main 62fc6b4 병합 뒤, 단독 실행): **1,453건 통과**(건너뜀 2, 실패 0) + JS 검사. 신규 `ResultViewTest` 5 — 실적은 실제 POP API(작업자 E1·E2, 폐기·재작업 불량, 1건 정정)로 남겼다.

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 보기 5종: 상세 / 지시 / 공정 / 작업자 / 작업일·근무조 | 상세 4줄(정정 줄 `corrected`·사유, 역분개 행은 따로 없음) / 지시 3건 양품 10·폐기 1·재작업 2·불량률 23.08 / 공정 순번·코드 / E1 8·E2 2 / 근무조 합 3건 | 줄 기준 = 원래 실적, 묶음 = 정정 제외 순수량(D27) |
| 불량 유형 합계 | 재작업 D-PIT 2·폐기 D-PIT 1(정정 제외) | — |
| 조건: 작업자(소문자)·없는 작업자·품목·다른 품목·없는 품목·공정·없는 공장·`%` 지시 번호·근무조·어제 | 1·0·4·0·0·4·0·0(글자로)·4·0 | 없는 코드는 빈 결과(다른 목록과 같음) |
| 엑셀: 작업자별 / 상세 | 머리 "작업자 코드", 2행, E1 건수 2·양품 8(숫자 셀) / 4행 | 플랫폼 `ExcelExportService` |
| A 값: 보기 코드·기간 빠짐·거꾸로·93일·지시 번호 51자·엑셀 보기 코드 | `view CODE`·`REQUIRED`·`INVALID_RANGE`·`RANGE_TOO_LONG`·`TOO_LONG`·400 | — |
| 권한: 조회만 → 엑셀 / 권한 없음 → 조회 | 403 / 403 | — |
| **A사로 B사**: 같은 준비의 B사 사용자 — 묶음·작업자 조건·엑셀 | 0·0·빈 파일, A 는 그대로 4 | — |
| 회귀: `ProgAuthCoverageTest`·`I18nKeyGuardTest`·`TemplateSecurityGuardTest`·`FlywayMigrationGuardTest`·`RetentionTest` | 통과 | — |

#### 브라우저 (local, 전용 DB 컨테이너, DEMO 관통 시드 실적 — 조회만, 1366·390)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 보기 5종 전환 → 조회 | 머리 칸·첫 줄이 보기마다 맞음(지시 1줄 양품 1,196·폐기 8·불량률 0.66%), 불량 합계 BURR 8 | — |
| [엑셀] → 조건 바꿈 | 내려받음·"내려받았습니다" / 엑셀 버튼 비활성 | 마지막 조회 조건으로만 |
| 링크 주소 `?q.plantCd=P1&q.view=SHIFT` / 근무조 D 선택 | 공장·보기 채워짐, 근무조 선택지 D·N / 1줄 | — |
| 390 | **처음엔 페이지 가로 넘침**(불량 합계 표에 붙인 `hb-rpt-table` 최소 720px 이 스크롤 상자 밖), **보기 세그먼트 5칸이 패널 폭을 넘어 글자 잘림** | 불량 합계는 최소 폭 없이, 보기는 선택 칸으로 → 넘침 없음 |
| 콘솔 | `/favicon.ico` 404(공통) | — |

## MES 지시별 남은 자재 소요 조회 계약 WoMaterialQuery (2026-10-21, V096 인덱스만(임시 V9109), APS 5단계)

## MES 지시별 남은 자재 소요 조회 계약 WoMaterialQuery (2026-10-21, 임시 V9109 인덱스만, APS 5단계)


설계 docs/25 4.18(결정 D30~D32). 화면·API 없음(모듈 간 읽기 계약). 전체 `./gradlew check`(main 7c58e80 병합 뒤): **1,484건 통과**(건너뜀 2, 실패 0) + JS 검사. 신규 `WoMaterialQueryTest` 7 — 지시는 실제 작업지시 API 로 만들고 발행(스냅샷), 투입·정정은 `LotService.consume`·`reverse`.
BOM: 완제품 1개당 RM 2 KG(공정 10)·RM 1 KG(공정 20)·PART 3 EA(LOT 비관리, 공정 없음)·SCRAP 0.5 KG 부산물.

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 투입 전: 발행 10개 / 계획 5개 / BOM 없는 계획 | RM@10 20·RM@20 10·PART 30 / 10·5·15(발행과 같은 전개) / 줄 없음·`exploded` true. 부산물 줄 없음 | — |
| 일부 투입: 공정 20 에 8 → 공정 10 에 15 → 공정 없이 6 → 공정 10 에 4(초과) | 5·2·30 → RM@20 1·PART 30 → PART 30 만 | 같은 공정 먼저, 남는 양은 공정 순서, 0 아래 없음(D31) |
| 정정: 12.5 + 3 투입 → 12.5 역분개 | 4.5·10·30 → 17·10·30 | **역분개 행은 `wo_id` 가 없다**(`LotService.reverse`) — `wo_id` 합으로 짰다면 정정이 반영되지 않음 → 되돌려진 원래 행 제외로 계산 |
| LOT 비관리 자재 | 투입과 무관하게 전량 30 | 보수적(D30) |
| E 상태: 환산 없는 BOM 계획 지시 / 취소(계획·발행 뒤) | `exploded` false·줄 없음 / 빠짐 | 한 건 실패로 전체 예외 아님(D32) |
| A 값: 없는 id·중복·null·빈 목록·null 목록 | 빠짐·한 번·무시·빈·빈 | — |
| 상한: 501건 / 500건 | `IllegalArgumentException` / 통과 | `MAX_IDS` |
| **A사로 B사**: 같은 준비의 B사 발행 지시를 A사 범위에서 / B사 범위에서 A사 지시 | A사 것만 / B사 것만(투입도 RLS) | — |
| 계약 없음: 빈 없는 공장 `of` / `NONE` | `NONE` / 빈 목록 | — |
| 실행계획(시험 DB, 0행) | 투입 조회 `ix_lot_genealogy_wo`·`pk_lot_master`·`uq_lot_genealogy_reversal` 인덱스 스캔 | 0행 통계라 `wo_id` 가 필터로 잡힘 — 실데이터 계획은 미검증 |

## MES 수집 ALARM → CMS AlarmSink 연결 (2026-10-21, 마이그레이션 없음, docs/25 4.17)

CMS 계약 `AlarmSink`(V093) 호출 줄. 전체 `./gradlew check`(main 21b57c5 병합 뒤): **1,494건 통과**(건너뜀 2, 실패 0) + JS 검사. `EquipEventTest` +3 — 계약 빈은 `@MockitoBean` 가짜로 바꿔 호출 인자를 확인(C8 구현 빈이 생겨도 이 시험에서는 가짜가 들어간다).

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 발생: 코드 ` e-100 `·`active` true·문구 250자 | `raise(설비, "E-100", 200자, 발생 시각, 장치, eventId)` | 코드는 대문자 정리, 문구는 자름(사실 기록이라 거부 안 함) |
| `active` 생략·문구 없음 / `active` FALSE | `raise(…, null, …)` / `clear(설비, "E-100", …)` | 생략 = 발생(6단계처럼 code 만 보내는 장치) |
| B 같은 eventId 다시 | accepted 0, 호출 없음 | sys_inbox |
| A 형식: 코드 없음·51자·공백·첫 글자 기호 / `active` "maybe" | `ALARM_CODE` ×4 / `ALARM_ACTIVE`, 호출 없음 | 그 이벤트만 거부 |
| 장치 작업장 밖 설비·사용 중지 설비 | `EQUIP`, 호출 없음 | — |
| **A사로 B사**: A사 장치가 B사에만 있는 설비 코드로 ALARM | `EQUIP`, 호출 없음 | 설비는 이 회사·이 장치 범위로만 찾는다 |
| 빈 없음 | `AlarmSink.of` = `NONE`, 호출해도 아무 일 없음 | 지금처럼 받기만 |

## MES 역분개 행 맥락 점검 — LOT 추적 부분 정정 (2026-10-21, 마이그레이션 없음, 상위 세션 요청)

전체 `./gradlew check`(main 2771a4e 병합 뒤): **1,516건 통과**(건너뜀 2, 실패 0) + JS 검사. `lot_genealogy` 를 지시·공정으로 거르는 곳 전수 확인(POP `inputEdges`·`WoMaterialQuery` 는 되돌려진 원래 행 제외, LOT 상세 목록은 표시만 — 역분개 행 지시 번호 빈칸). 규칙은 docs/25 4.5 역분개 줄에 추가. `LotTest` +1.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 같은 (자재 LOT, 생산 LOT) 투입 12.5 + 3 → 추적 | 한 줄 15.5 | — |
| 12.5 만 역분개 → 역추적 / 정추적 / 잔량 | 한 줄 3 / 한 줄 3 / 97 | 순회는 살아 있는 행, 수량은 역분개 합산 순수량 — 결함 없음(시험 공백만 메움) |
| 역분개 행에 원래 행 맥락 복사(제안 B) | 하지 않음 | 사용자 결정 대기 |

## MES LOT 보류 근거 원장 lot_hold·계약 LotHoldPort (2026-10-21, V109(임시 V9110), docs/25 4.19 — QMS Q8 경계, 사용자 결정 2026-10-21)

전체 `./gradlew check`(main 3088297 병합 뒤): **1,610건 통과**(건너뜀 2, 실패 0) + JS 검사. 신규 `LotHoldTest` 6 + `PopWorkTest` 근거 단언 + `LotTest` 회사 시간대 경계 1. `lot_master.hold_yn` 을 직접 UPDATE 하던 시험(MES·WMS·홈 9곳)은 근거 행 추가·해제로 바꿈(WMS `owner()` 는 트리거를 끈 연결이라 근거 행 + LOT 칸을 한 문장으로).

| 해 본 것 (A~K) | 결과 | 조치 |
|---|---|---|
| 수동 보류 → NCR 근거(포트) → 수동 해제 → NCR 해제 | Y "외관 의심" → Y "외관 의심 / NCR-7 치수 불량" → Y "NCR-7 치수 불량" → N | 마지막 근거가 풀릴 때만 해제, 사유 = 살아 있는 근거 요약 |
| 상세 응답 `holds` / 수동 근거가 있을 때 [보류] 다시 / 수동 해제 두 번 | 2개(보류 순) / `STATE` / `STATE` | — |
| E 화면에서 NCR 근거 해제 | `HOLD_SOURCE` | D34 |
| 해제 기록 | 같은 행 `released_*` (MANUAL:admin1:외관 합격, QMS_NCR:qms1:MRB 특채 승인) | D33 |
| 포트: 같은 근거 두 번 보류·해제 / 같은 번호 다른 출처 / 없는 근거 해제 | true→false / 따로 살아 있음 / false | 멱등 |
| A 포트: MANUAL·MES_CORRECT 출처, 빈 사유, 501자, 없는 LOT, 501건 조회 / 트랜잭션 밖 | `IllegalArgumentException` / `IllegalTransactionStateException` | MANDATORY |
| C 동시: 같은 LOT 에 NCR-1·NCR-2 동시 보류 | 둘 다 살아 있음, 요약에 둘 다 | 동기화 트리거가 LOT 행을 먼저 잠근 뒤 요약(잠금 대기 뒤 새 스냅샷) |
| 직접 SQL: hold_yn·hold_reason 직접 변경 / 보류 상태로 새 LOT / 근거 사유 고치기 / 삭제 / 두 번째 살아 있는 수동 근거 / 출처 밖 / 수동인데 참조 / 없는 실적 참조 / 해제 상태로 추가 | `ck_lot_master_hold_basis` ×4 / `ck_lot_hold_fixed` / 거부 / `uq_lot_hold_live` / `ck_lot_hold_source` / `ck_lot_hold_ref` / `fk_lot_hold_ref_comp` / `ck_lot_hold_new` | D36 |
| 해제 사유 없이 해제 / 해제 두 번 | **처음엔 통과**(`length(btrim(NULL)) > 0` 이 NULL → CHECK 통과) → `ck_lot_hold_release` / `ck_lot_hold_fixed` | CHECK 에 `release_reason IS NOT NULL` 추가 |
| 화면: 이미 풀린 근거 / 남의 LOT 근거 id / 빈 사유 / 옛 rowVer | `STATE` / 404 / `REQUIRED` / 409 | — |
| POP 실적 정정 | 생산 LOT 보류 + 근거 `MES_CORRECT/{실적 id}/사유` | — |
| **A사로 B사**: 포트 보류·해제·조회 / 화면 근거 해제·수동 해제 / 직접 SQL B사 근거로 A사 LOT·A사 근거 해제·회사 코드 A사 | IAE ×2·빈 목록 / 404 ×2 / `fk_lot_hold_lot_comp`·0행·`fk_lot_hold_lot_comp`(같은 회사 트리거가 RLS 보다 먼저), A사 그대로 | — |
| 계약 없음 | `NONE` 보류·해제 = `IllegalStateException`, 조회 = 빈 목록 | 상위 검토: 조용한 false 는 "이미 보류됨" 과 같아 보인다 |
| 트리거를 끈 소유자 연결(replica)로 보류 + 사유 NULL | `ck_lot_master_hold_reason` | V034 CHECK 의 NULL 구멍 막음(NOT VALID → VALIDATE) |
| 이관(임시 시험, 커밋 안 함): 빈 DB 를 V106 까지 → 두 회사에 보류 LOT 2·보류 아님 1 → V109 / 사유 NULL 보류 LOT·`updated_by` 공백 | 보류 LOT 마다 MANUAL 1행(사유·사람 = 기존 값), 보류 아님은 근거 없음, 사유 그대로 / `이관(사유 없음)`·`migration` 으로 채워 이관·VALIDATE 통과(빈 칸 사유는 옛 CHECK 도 막아 존재 불가) | 마이그레이션 계정이 슈퍼유저인 시험 컨테이너 — 비슈퍼유저 소유자 경로는 V084 선례 그대로 |
| LOT 목록 기간 회사 시간대 경계(KST 오늘 00:30 포함·어제 23:30 제외) | 옛 코드 실패 확인 → main 9a94132 수정으로 통과 | 수정은 상위 세션, 시험만 추가 |

#### 브라우저 (local, 전용 DB 컨테이너, DEMO + ZT 자재 LOT, 1366·390)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| ZT LOT 에 NCR 근거(SQL) → 상세 | "보류 근거: 부적합(NCR) — ZT NCR 치수 불량 · 시각 · qms-zt (품질 처분으로 해제)", [보류] 보임 | — |
| [보류] → 사유 입력 | 수동 근거 줄 + [해제], [보류] 숨김 | — |
| 수동 근거 [해제] → 사유 | "저장했습니다", NCR 근거만 남고 DB `Y/ZT NCR 치수 불량` | — |
| 390 | 가로 넘침 0, 근거 두 줄이 칸 안에서 접힘 | — |
| 서버 시각 UTC 22:00(KST 07:00) 에 오늘 기간 LOT 목록 | 오늘 만든 LOT 8건 보임 | 시간대 수정 확인 |
| 콘솔 | `/favicon.ico` 404(공통) | — |

## MES 역분개 행 맥락 복사 + 회사 날짜 정리 (2026-10-21, V110(임시 V9111), 사용자 결정 2026-10-21)

전체 `./gradlew check`(main e1a36e4 병합 뒤) 1,613건, **main 45db31b 병합 뒤(LOT 보류 이관 방어·NONE 거부 포함) 1,628건 통과**(건너뜀 2, 실패 0) + JS 검사. 설계 docs/25 4.5 역분개 줄. 새 역분개 행은 원래 행 맥락(계보 지시·공정·설비, 실적 설비)을 갖고, 다르면 AFTER INSERT 트리거가 거부(기존 거울·사유 CHECK 오류가 먼저 나오게). 발생 시각은 정정 시각 그대로. 집계 규칙("되돌려진 원래 행 제외")은 그대로 — 옛 행은 NULL.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| LOT 투입 역분개(`LotService.reverse`) | 역분개 행 `wo_id` = 원래 지시 | — |
| 직접 SQL: 지시를 비운 계보 역분개 | `ck_lot_genealogy_reversal_ctx` | — |
| POP 실적 정정(공정에 설비 배정) | 실적 역분개 `equip_id` = 원래, 발생 시각은 정정 시각(원래보다 뒤), 투입 계보 역분개도 지시·공정·설비 같음 | — |
| 직접 SQL: 설비를 비운 실적 역분개 | `ck_prd_result_reversal_ctx` | — |
| 기존 조회 결과 불변 | WoMaterialQuery·ResultView·LOT 추적·CMS KPI 시험 그대로 통과(KPI 정정 제외 그대로) | `CmsKpiTest` 의 "역분개 행 설비 없음" 단언을 "원래 설비" 로(규칙 변경 반영), 직접 넣는 역분개도 설비 복사 |
| 홈 보류 카드 항목 링크 날짜(회사 시간대 00:30 등록 LOT) | 같은 결함을 홈 세션이 #147 로 먼저 고쳐 main 쪽 수정·회귀 시험(HomeCardTest)을 택함 — 이 PR 의 같은 수정·시험은 병합 때 버림 | — |
| 시험 데이터 `current_date` → 회사 날짜 | 상위 세션 목록 14개 + EquipEventTest | 텍스트 블록 `(now() at time zone 'Asia/Seoul')::date`, 자바 문자열 `TestAccounts.COMP_TODAY` |
| MES 매퍼 점검(회사 날짜 LocalDate ↔ timestamptz) | LotMapper(main 9a94132 수정) 외 없음 — ERP 는 Instant, BOM·라우팅은 date 칸끼리 | — |

## MES B5 ① POP 오프라인 대기열 거부 보존 (2026-10-22, 마이그레이션 없음, docs/25 4.20 — WMS B5 공장 스위치의 전제)

전체 `./gradlew check`(main 3fdc283 기준) 1,638건, **main f444176 병합 뒤 1,642건 통과**(건너뜀 2, 실패 0) + JS 타입 검사·단위 시험. 신규 JS 단위 시험 `src/test/js/hb.pop.queue.test.js` 5(node --test, 대기열 본체 `makeQueue` 에 메모리 저장소·가짜 서버).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 오류 분류: 끊김·타임아웃·5xx·401 / 400·403·404·409 | 다시 보냄 / 서버 거부 | 401(단말 세션 만료)은 **전에는 지워졌다** → 다시 보낼 오류로 |
| 오프라인 확정 2건 → 재전송: A 재고 부족 409, B 성공 | A "거부됨" 으로 남음(본문·사유 보관), B 보냄, 보낼 건수 0·거부 1 | **전에는 A 가 지워졌다**(현장 실적 유실) |
| 거부된 건 자동 재전송 | 안 함 | 같은 거부 되풀이 안 함 |
| 다시 보내기: 마감 409 → 재고 409 → 성공 | 사유 갱신 → 갱신 → 사라짐, 매번 같은 clientReqId | 서버 멱등 |
| 끊김 건이 뒤에 있으면 | 거부 건 다음에서 멈춤(순서 유지), 버리기는 고른 건만 | — |
| 바로 보낼 때 400 | 대기열에 남기지 않고 화면 오류 | 그대로 |
| flush 동시 호출 | 한 번만 보냄 | — |

#### 브라우저 (local, 전용 DB 컨테이너, ZT POP 단말, 1280×800·800×1280·390)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 보낼 실적 1건(IndexedDB) + 재전송에 409 `INSUFFICIENT_STOCK` 응답(route 흉내) → 화면 열기 | 자동 재전송 → 머리 "보내지 못한 실적 1건", 알림 "서버가 받지 않았습니다" | — |
| 목록 | 확정 시각 · 수량 · 투입 LOT 수량 + 사유(빨강) + [다시 보내기]·[버리기] | — |
| [다시 보내기] → 또 409 / [버리기] → 확인 | 목록에 남음 / 비고 머리 버튼 숨김 | — |
| 넘침·터치 대상 | 가로 넘침 0, 48px 미만 버튼 0 | — |
| (발견) 머리 버튼 색 | 처음엔 머리 공통 버튼 규칙(투명 배경)에 덮여 눈에 안 띔 | `.hb-pop-head .hb-pop-rejected` 로 |
| (발견) 영어 "1 results not sent" | 복수형 어긋남 | "Not sent: {0}" |
| 콘솔 | 흉내 409 2건(의도), favicon 404 | — |

## MES B5 ②③ 칸 오류 없는 거부 그대로 · 정정 LOT id 순 잠금 (2026-10-22, 마이그레이션 없음, docs/25 4.20)

전체 `./gradlew check`(main f444176 병합 뒤): **1,644건 통과**(건너뜀 2, 실패 0). `PopWorkTest` +2(`LotService` 를 `@MockitoSpyBean` 으로 — ② 시험에서만 consume 이 409 를 던지게).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 투입 중 칸 오류 없는 거부(`INSUFFICIENT_STOCK` — WMS 훅 모양) | 409 `INSUFFICIENT_STOCK` 그대로, 실적·산출 LOT·계보 롤백, 자재 잔량 그대로 | **전에는 400 `lotNo` `STATE` 로 바뀌어 원인이 사라졌다** |
| 칸 오류 있는 거부(잔량 초과) | 지금처럼 투입 행 `OVER_BALANCE`(rowKey 0) | — |
| C 정정 잠금 순서: 투입 [B, A](계보 순 B → A, LOT id A < B), 다른 트랜잭션이 A 를 잡은 동안 정정 | 정정은 A 에서 기다리고 B 는 비어 있음(`FOR UPDATE NOWAIT` 성공) → A 풀리면 정정 200, 잔량 원복 | **옛 코드는 B 를 먼저 잡고 A 에서 기다렸다**(시험이 옛 코드에서 실패하는 것 확인) — 투입+산출 LOT 을 LOT id 순으로 먼저 잠금 |

## MES B5 ④ LotStockLink 훅 (2026-10-22, 마이그레이션 없음, docs/25 4.20 ④ — WMS 계약 main #164)

전체 `./gradlew check`(main 24a8438 병합 뒤): **1,651건 통과**(건너뜀 2, 실패 0). 신규 `LotStockLinkHookTest` 3 — 계약 빈은 `@MockitoBean` 가짜(WMS 구현 빈이 생겨도 이 시험에서는 가짜).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| LOT 만들기(입고) / 분할 2개 / 투입 / 역분개 | 호출 없음 / onEdge(분할 계보 id) 2번 순서대로 / onEdge(투입 계보 id) / onReverse(역분개 행 id), 그 밖 호출 없음 | 계보 행을 만드는 곳은 edge()·reverse() 둘뿐(매퍼 전수 확인) |
| WMS 가 onEdge 에서 재고 부족(409) | 분할 409 `INSUFFICIENT_STOCK` 그대로, 계보·자식 LOT 없음, 잔량 그대로 | 같은 트랜잭션 |
| WMS 가 onReverse 에서 마감(409) | 칸 오류 없는 예외 그대로, 역분개 행 없음 | — |
| WMS 없는 설치본 | `of(빈 공급자)` = `NONE`, 호출해도 아무 일 없음 | — |

## MES B5 ②③④ 새 PR — main 3c4078a 병합 뒤 전체 check (2026-10-22)

전체 `./gradlew check`: **1,652건 통과**(건너뜀 2, 실패 0) + JS 검사.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| (발견) 전체 check 1건 실패 — `TenantIsolationTest` "remaining connection slots are reserved for roles with the SUPERUSER attribute" | 단독 실행은 통과. 시험 컨텍스트마다 연결 풀(최대 10)이 생기고, `@MockitoBean`·`@MockitoSpyBean` 쓰는 클래스(이번 묶음 PopWorkTest·LotStockLinkHookTest 포함)가 컨텍스트를 늘려 기본 `max_connections` 100 을 넘음 | 공통 `PostgresTestBase` 시험 컨테이너를 `max_connections=300` 으로 → 통과 |

## MES 착수 게이트 StartGate + LOT 조회 LotLookup (2026-10-22, V120(임시 V9112), docs/25 4.21 — QMS Q9 경계)

전체 `./gradlew check`(main 30ebaf9 병합 뒤) 1,673건, **main 32be787 병합 뒤 1,687건 통과**(건너뜀 2, 실패 0) + JS 검사. `PopWorkTest` +2(가짜 `StartGate` 빈 2개 QMS·AUX, `@Order` 순), 신규 `LotLookupTest` 4.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 공정 화면 미리보기(QMS WARN) | `startGate.mode` WARN, 이유(출처·코드·로캘 문구), 빈이 받은 맥락 preview = true·지시 공정·지시·품목·작업자 | — |
| 착수: 자격 WARN + QMS WARN, 확인 없이 | 409 `START_GATE_WARN_ACK`, 두 이유 한 문구, 공정 대기 그대로 | 한 번의 확인 |
| AUX BLOCK + QMS WARN, 확인 있어도 | 409 `START_GATE_BLOCKED`, 이벤트 없음 | 가장 나쁜 mode |
| BLOCK 풀린 뒤 확인 착수 | 200, `gate_mode` WARN, `gate_detail` = `QUAL:WELD, QMS:QMS_FIRST_PENDING(LOT 12)` | 코드로 근거 |
| E 빈이 SQL 오류(0 나누기)·모르는 mode | 정책 기본 BLOCK → 409 `START_GATE_BLOCKED`(500 아님) / 정책 WARN → 확인 뒤 착수, 근거 `QMS:GATE_ERROR, AUX:GATE_ERROR` | **세이브포인트를 빼면 500**(트랜잭션 aborted) — 시험이 옛 모양에서 실패하는 것 확인 |
| 직접 SQL: OK 착수에 근거 | `ck_prd_wo_proc_event_gate_detail` | — |
| LotLookup: 여러 LOT(역순·중복·null·없는 id) / 빈·null / 501건 | lot_id 순 2건(품목 코드·이름, 지시 없음) / 빈 / IAE | 상한 500 |
| LotLookup.byNo: 앞뒤 공백 / null·빈·없는 번호·61자 | 찾음 / empty | 예외 없음 |
| 없는 번호 찾은 뒤 같은 트랜잭션에서 쓰기 | 커밋됨 | rollback-only 안 남김(PMS 가 REQUIRES_NEW 를 쓰던 이유 제거) |
| **A사로 B사** | B사 범위에서 A사 LOT id·번호 → 빈·empty | RLS |
| MES 없는 설치본 | `LotLookup.NONE`(빈·empty) | — |
| 브라우저 | 실제 게이트 빈(QMS)이 아직 없어 화면 변화 없음(이유 0건이면 지금 문구 그대로) — **미검증**: 게이트 이유 표시는 QMS 빈이 붙은 뒤 QMS PR 에서 | JS 타입 검사만 |

## 영업 출하 게이트 OrderShipGate (2026-10-22, V123(임시 V9113), docs/25 4.22 — WMS B6 경계)

전체 `./gradlew check`(main a880060 — V120 병합 뒤): **1,713건 통과**(건너뜀 2, 실패 0) + JS 검사. 신규 `OrderShipGateTest` 10건, 기존 `SalesOrderTest` 11건 그대로.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 부분 출하 4 / 같은 행 3+3 + 다른 행 5 한 번에 | `OPEN/N/4` → `DONE/Y/10`, 다른 행 `DONE/Y/5`, 머리 CLOSED, row_ver 매번 +1, 화면 `shippedQty`·`doneByShipYn` | 같은 행 합침 |
| 초과 10.5(정책 0) / 정책 10% 에서 10 + 1, 그 뒤 0.000001 | 409 `OVER_SHIP`·변화 없음 / 자동 마감 행에 더 나가 11 / 409 | 기본 0 |
| 취소 행·사람 마감 행 출하(다른 열린 행과 한 번에) | 409 `ORDER_LINE_CLOSED`, 열린 행도 그대로 | 전체 롤백 |
| 없는 행 / 0 / 출하보다 큰 취소 / 트랜잭션 밖 | `NOT_FOUND` / IAE / IAE / `IllegalTransactionStateException` | MANDATORY |
| 출하 취소: 자동 마감 행 −3, 사람 마감 행 −4 | `OPEN/N/7`, `DONE/N/0`, 머리 OPEN → 다시 3 나가면 `DONE/Y`·CLOSED / 사람 마감만 있는 수주 되돌림은 머리 CLOSED 그대로 | D41 |
| 화면: 출하 6 아래로 수량 5 / 6 으로 / 행 취소 / 수주 취소 / 사람 마감 | 400 `qty` `BELOW_SHIPPED` / 200(열린 채) / `SHIPPED` / `SHIPPED`(다른 행 그대로) / 200 `DONE/N/6` | 영업 규칙 |
| 직접 SQL(앱 계정): 수량 < 출하, 출하 행 취소, 출하 −1, Y 를 열린 행에, 출하 있는 새 행 | `ck_sal_order_line_qty_shipped` / `_cancel_shipped` / `_shipped` / `_done_by_ship` / `_status_new` | 트리거·CHECK |
| 직접 SQL: 사람 마감 행 다시 열기·수량, 열린 행 없는 머리 OPEN, 행 삭제 | `ck_sal_order_line_closed` / `ck_sal_order_closed` / 42501 | 행 삭제 차단 확인 |
| 수주 행에만 쓰인 품목의 기본 단위 변경 | `fk_mst_item_unit_in_use`(안 쓰인 품목은 됨) | V091 에 없던 대상 → V123(임시 V9113) |
| 두 수주 행을 엇갈린 순서로 2 스레드 × 5회 | 교착 없음, 두 행 다 10 | 머리 → 행 id 순 잠금 |
| **A사로 B사** | B사 범위에서 A사 행 출하 → `NOT_FOUND`, 직접 UPDATE 0행, A사 행 그대로 | RLS |
| 영업 없는 설치본 | `OrderShipGate.NONE` 아무 일 없음 | — |
| 브라우저 | 수주 화면 출하 수량 열(빈 값)만 추가 — 실제 출하는 WMS B6 화면이 생긴 뒤. **미검증**(JS 타입 검사만) | — |
| (발견) 전체 check 두 개를 동시에 돌림 → 8건 실패, 그 뒤 `:test` `EOFException` | 같은 build 폴더·Docker 를 함께 써서 생긴 일(시험 결함 아님). 강제 종료가 시험 결과 파일을 깨뜨림 | 하나만 남기고 `build/test-results` 지운 뒤 다시 → 통과 |

## QB-1 재고 칸 교착 — 투입·되돌림을 (품목, LOT) 순으로 (2026-10-22, QA 결함 QB-1)

전체 `./gradlew check`(main aee6fabc 병합 뒤) 1,748건, **main 86ead1e5(QMS 착수 게이트 빈 V125) 병합 뒤 1,752건 통과**(건너뜀 2, 실패 0) + JS 검사 — 진짜 QMS 빈과 함께 `PopWorkTest` 12·`QmsStartGateTest` 4·`PopWmsStockLinkQaTest` 7 통과.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| QA `PopWmsStockLinkQaTest.결함QB1_…`(정정이 NUT → BOLT, 다른 라인 실적이 BOLT → NUT, 둘 다 첫 칸을 쥘 때까지 래치) — **수정 빼고** | [정정, 실적] = [200, **500**], 로그 `40P01 deadlock detected` | 재현 확인 |
| 같은 시험 — 수정 넣고(`@Disabled` 해제) | [200, 200], NUT·BOLT 재고 각 98 | `PopWorkService.result` consume 을 (item_id, lot_id) 순, `inputEdges`(정정 되돌림)를 parent item_id → lot_id → gen_id 순 |
| 칸 오류 행 번호 | 정렬 뒤에도 요청 행 번호(r[2]) 그대로 | 기존 `PopWorkTest` 통과 |
| 같은 (품목, LOT) 안 위치 순서 | WMS 차감(LINESIDE → pick_seq) 몫 — MES 는 품목 → LOT 까지 | WMS `CountService.post` 는 WMS 세션이 같은 규칙으로 |

## 수주 행 읽기 계약 OrderLineQuery (2026-10-22, docs/25 4.23 — WMS B6a, 마이그레이션 없음)

전체 `./gradlew check`(**JDK 21**, main fc6a20db — WMS B6a #216 병합 뒤): **1,784건 통과**(건너뜀 2, 실패 0) + JS 검사. 신규 `OrderLineQueryTest` 5건.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 거래처 열린 행: 두 수주·납기 섞임 + 자동 마감 행·취소 행·다른 거래처 행 | 납기 → 수주번호 → 행번호 순 3건, 끝난 행·다른 거래처 빠짐, 출하 투영 4·발주번호 null 그대로 | — |
| 없는 거래처 / 열린 행 없는 거래처 | 빈 목록 | 예외 없음 |
| 여러 행: 역순·중복·null·없는 id / 빈·null / 501건 / 500건 | line_id 순 2건(자동 마감 행 DONE·Y 포함) / 빈 / IAE / 통과 | 상한 500 |
| 읽은 트랜잭션을 연 채 다른 트랜잭션이 같은 행 출하 | 기다리지 않고 커밋, 다시 읽으면 출하 1 | 잠금 없음 |
| **A사로 B사** | B사 범위에서 A사 거래처 열린 행·A사 행 id → 빈 | RLS |
| 영업 없는 설치본 | `OrderLineQuery.NONE` 빈 목록 | — |

## 공휴일 엑셀 올리기 MST_WORK_CAL (2026-10-22, docs/25 4.24 — 그룹 A 1번, 마이그레이션 없음)

전체 `./gradlew check`(JDK 21): **1,828건 통과**(건너뜀 2, 실패 0) + JS 검사. 신규 `WorkCalImportTest` 3건, 기존 `WorkCalApiTest` 5건 그대로.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 새 예외 3(회사 공통·공장 하루·공장 근무조, 소문자 코드·사유 앞뒤 공백) | 새 3, 실행 3 삽입, 코드 대문자·사유 정리, 달 조회 화면 API 에 3행 | — |
| 행 오류: 13월·1999년·없는 공장·공장 없는 근무조·사용 중지 근무조·없는 근무조·구분 HOLIDAY·사유 없음 | FORMAT·FORMAT·NO_SUCH_CODE·SHIFT_NEEDS_PLANT·INACTIVE·NO_SUCH_CODE·CODE·REQUIRED | 화면 저장과 같은 코드 |
| 파일 안 같은 키 두 행 | 둘 다 FILE_DUPLICATE, 반영 없음 | 엔진 규칙 |
| 다시 올림: 같은 행 / 새 공장 층 / 사용 중지한 키 | 같음 1·새 2 — 사용 중지 행은 그대로 남고 새 행 | — |
| 구분·사유 바꿔 올림 | 변경 1 → 실행 갱신 1 | 대상은 키(고정) |
| 새 것만 모드에 있는 키 | 오류 1(EXISTS) | — |
| **A사로 B사** | B사에서 A사 공장 코드 → NO_SUCH_CODE, A사 예외와 같은 날 회사 공통은 B사의 새 행, 두 회사 행 수 각 1 | RLS |
| 조회 권한만 | 올리기 403(템플릿은 받음) | **발견(시험)**: 조회 사용자를 저장 사용자와 같은 권한 그룹에 넣으면 저장 권한을 물려받아 200 — 그룹을 사용자마다 따로 |
| 브라우저 | **미검증** — 화면에 "엑셀 올리기" 버튼만 추가(공통 `hb.importDialog`), JS 타입 검사 통과 | — |

## 영업 화면 테스트(500건) — 수주·내시 (2026-10-22, 화면 조작, 접두어 `ZT-SAL-`)

로컬 서버(8090)·DEMO 회사, 내장 브라우저 탭 1개. DB 직접 INSERT 없음 — 기준정보·수주는 화면 버튼(신규·행 추가·저장·마감·취소·수주 취소)을 손으로 수십 건 누른 뒤 같은 버튼을 페이지 스크립트로 반복, 내시·수주 일부는 화면의 **엑셀 올리기**(템플릿 형식 xlsx 를 브라우저에서 만들어 대화상자에 넣음). API 직접 호출은 이상 사용(C·F) 확인에만.
첫 시도(13:5x)는 10개 세션 동시 실행 중 DB 연결 풀 고갈(`Could not open JDBC Connection`, health DOWN → 응답 없음)로 로그인 단계에서 중단 → 재기동 뒤 3개 세션만으로 재개.

**만든 것**: 거래처 6(매출 C01~C05, 매입 P06) · 품목 35(FG001~032 EA, KG001~003 KG) · **수주 567건 / 행 1,385**(진행 512, 완료 30, 취소 25 — 화면 저장 540 + 엑셀 올리기 23 + 이상 사용 API 4) · 상태 변경: 행 마감 75, 행 취소 20, 수주 취소 20, 행 수량·납기 변경 15 + 머리 비고 변경 15 · **내시 버전 14 / 행 약 540**(엑셀 올리기 10 + 동시 수신 API 4).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 정상: 신규 → 행 2개 화면에 모음 → 저장(손) | SO2610-0001, 행 10·20, 목록·상세 즉시 반영 | — |
| 정상: 같은 화면 조작 반복 540건(1~4행, 납기 +7~+126일, 거래처 5) | 실패 0, 건당 약 0.5초 | — |
| **B** 납기 < 수주일(수주 2026-10-22, 납기 2026-01-05) — 화면·엑셀 올리기 둘 다 | **저장됨**(경고 없음) | **정책 질의**: 납기 ≥ 수주일 검사 또는 경고 |
| **B** 납기 연도 오타 2062-10-30 / 수주일 2999-12-31 / 내시 DAY 2062-11-02 | 모두 저장. 수주일 2999 수주는 기본 조회(최근 30일)에 **안 보임** | **정책 질의**: 수주일 미래 한도(예: 오늘+N일)·납기 합리 범위 경고 |
| **H** 수량 0 / -5 / EA 1.5 / 지수 1e3 / 전각 １２ / 13자리 | 0·음수 POSITIVE, 1.5 SCALE(0자리), 나머지 FORMAT, 13자리 INT_DIGITS | 정상 |
| **H** 수량 `1,200`(천 단위 쉼표) | FORMAT 거부 — 목록·상세는 쉼표로 **보여 주면서** 입력은 거부 | **정책 질의**: 입력 칸에서 쉼표 제거 허용(hb.numbers) |
| **H** KG 12.345 / 999,999,999,999(12자리) | 저장, 상세에 `999,999,999,999 EA` 정상 표시 | — |
| **H** 검색어 `%`·`_`·소문자·51자 | 0건·0건(이스케이프 정상)·대소문자 무시 1건·400 | 정상 |
| **H/I** 비고 `<img src=x onerror=…>`·엑셀 비고 `=HYPERLINK(…)` | 글자 그대로 저장·표시(textContent), 스크립트 실행 없음 | 정상(수주 엑셀 내려받기는 없음) |
| **C** 저장 버튼 5연타(화면) | POST 1건(hb.busy) | 정상 |
| **C** 같은 본문 POST 2건 동시(발주 번호 없음, API) | **수주 2건 생성** | **고칠 것**: 화면이 `X-Idempotency-Key` 를 안 보냄 — 서버는 키가 있으면 2번째를 409 `DUPLICATE_REQUEST`(확인함). 시간 초과 재시도·두 번 누름이 화면 밖에서 나면 중복 수주 |
| **C** 같은 발주 번호로 동시 3건 | 1건 OK, 2건 `custPoNo DUPLICATE` | 정상(부분 유일 인덱스) |
| **C** 두 탭 동시 수정: 탭 B 가 머리·행 저장 → 낡은 탭 A 가 머리·행 저장 | 둘 다 409 "다른 사용자가 먼저 수정", 탭 B 값 유지(조용한 덮어쓰기 없음) | 정상. 화면은 409 뒤에도 낡은 값(수량 10)을 그대로 보여 줌 — 자동 재조회 제안 |
| **C** 내시 같은 거래처 동시 수신 3건 | 버전 3·4·5 겹침 없음(advisory 잠금) | 정상. 같은 내용 3번도 버전 3개 — 멱등 키 없음(수주와 같은 성격) |
| **F** 완료 수주 머리 수정·완료 행 수정·행 추가·완료 행 취소·완료 수주 취소(API) | 모두 400 `status:CLOSED` | 정상 |
| **F** 요청에 `status:'CLOSED'`·`shippedQty:'999'` 끼워 넣기 | 무시(OPEN·출하 0 그대로) | 정상 |
| **F** 낡은 rowVer / rowVer 없이 수주 취소 | 409 CONFLICT | 정상 |
| **F** 거래처·품목·행 번호 바꾸기 / 사유 공백 취소 / 수량 0 으로 변경 | FIXED·FIXED·FIXED / REQUIRED / POSITIVE | 정상 |
| **F** 엑셀 올리기(새 것+변경)로 취소된 수주에 행 추가·취소된 행 수량 변경·품목 바꾸기 | 각 행 오류(끝난 수주·행, 품목 고정), 나머지 43행만 반영 | 정상 |
| **F** 완료 수주를 열고 [저장] | 입력 칸은 잠기지만 **저장 버튼이 보이고**, 누르면 "입력값이 올바르지 않습니다."만(어느 칸인지 없음) | **고칠 것(화면)**: 진행 아닌 수주는 저장 숨김 또는 "끝난 수주는 바꿀 수 없습니다" |
| **D** 수주 뒤 거래처 C04·품목 FG031/032 사용 중지 → 수주 다시 열기 | 열림(중지 거래처를 그 수주만 위해 선택지에 넣음) | 정상 |
| **D** 중지 거래처 수주: 머리 수정·중지 품목 행 수량 변경·**새 행 추가**·행 마감 | 모두 저장됨 | **정책 질의**: 사용 중지 거래처의 열린 수주에 새 행을 받아도 되는지(신규 수주는 INACTIVE 로 막음) |
| **D** 열린 수주에 중지 품목 행 추가 / 새 수주에 중지 거래처·품목·매입처 / 내시 수신 | INACTIVE·INACTIVE·NOT_SALES, 칸 옆에 "사용 중지된 항목입니다." | 정상 |
| **D** 내시 화면 거래처 선택지 | 사용 중지 C04 도 보임(과거 버전 조회용 `findSalesCusts(false)`) — 수신은 INACTIVE | 정상(의도) |
| 내시 엑셀: 주 시작 화요일·월 시작 15일·음수·중지 품목·쉼표·중복 키·중지 거래처 | 8행 오류로 걸러 81행 반영, "대사 일치" 표시 | 정상. 중복 키 오류가 "거래처 코드: 같은 코드가 두 번"으로 나옴 — 키(거래처·품목·기간) 문구 제안 |
| 내시 엑셀 확정 칸 `X` | N 으로 저장(API 는 같은 값 400 CODE) | 정책: `ImportCodes.yn` 이 O/X 를 Y/N 별칭으로 받음(의도) |
| 내시 버전 비교: 다른 거래처 버전끼리 | 400 `OTHER_CUST` | 정상 |
| 없는 수주 id / 없는 거래처 코드 | 404 / NO_SUCH_CODE | 정상(A사로 B사는 자동 시험이 담당 — 이 세션은 회사 1개) |
| **500건 목록** 조회(기본 30일·진행) | API 30ms·152KB, 화면 500행 그리기 약 0.1초, "앞의 500건만 보입니다" | **정책 질의/범위**: 페이지·정렬·**엑셀 내려받기 없음**(기준정보 화면은 있음) — 501번째부터는 조건을 좁혀야만 보임 |
| 검색: 발주번호 앞부분 / 수주번호 / 거래처 / 품목 / 납기 7일 | 19~32ms, 100·41·111·53·89건 | 정상 |
| 내시 비교 131행 | 28ms | 정상 |
| **A** 775px | 상세 서랍 가장자리가 목록 오른쪽(상태 열)·[조회]·[의견 보내기]를 가림 | 디자인 관찰 참고 |
| 수주 출하 화면 | 출하는 WMS(B6) 화면 — 이 세션 범위 밖, 출하 수량 열은 0 표시만 확인 | 범위 밖 |
| (환경) 10세션 동시 실행 | Hikari 기본 10 연결 고갈 → 로그인 500·health DOWN·응답 없음 | 범위 밖(로컬 부하). 운영은 `DB_POOL_SIZE`·세션 수 기준 문서화 제안 |

### 디자인 관찰 (관찰만, 수정 없음)

| 화면 | 본 것 | 제안 |
|---|---|---|
| 내시 수신 | 거래처 `<select>` 가 툴바(460px) 안에서 **2,571px** 로 늘어나 오른쪽 상세 위로 선이 지나감 — 기준정보 세션이 만든 214자 거래처명 탓 | `.hb-toolbar select` 최대 폭 + 말줄임(긴 이름 = H) |
| 내시 수신 | 조회 조건이 회색 조회 패널(hb-search)이 아니라 툴바의 맨 select — 수주·기준정보 화면과 모양이 다름 | 같은 조회 패널 쓰기 |
| 내시 수신 | 직접 입력 폼 없음(엑셀 올리기·API 만) | 소량 정정용 행 입력은 정책 결정 사항 |
| 수주 관리 | 목록 7열이 좁아 수주번호·발주번호·날짜가 2~4줄로 꺾임(1024px) | 날짜·번호 열 `nowrap` + 최소 폭, 거래처는 코드만/이름 말줄임 |
| 수주 관리 | 행 버튼(수정·마감·취소) 3개가 세로로 쌓여 행 높이가 커짐 | 한 줄 아이콘 버튼 또는 행 선택 뒤 위쪽 툴바 |
| 수주 관리 | 거래처 선택이 일반 select(90여 개, 검색 없음) | 검색되는 콤보(코드·이름) — 거래처가 늘면 입력 부담(J) |
| 수주 관리 | 행 오류 문구가 `행 오류(lines[0].qty): …` 처럼 내부 키 노출 | "1번째 행 수량: …" |
| 수주 관리 | 납기 < 수주일 행도 다른 행과 똑같이 보임 | 납기 칸 경고 색 |
| 수주 관리 | 409 뒤 화면이 낡은 값 유지 | 409 시 상세 자동 재조회 |
| 수주 관리(J) | 2행 수주 1건 = 신규 1 + 거래처 1 + 발주 1 + (품목·수량·납기·행 추가) × 2 + 저장 1 ≈ 12번 조작, 화면 이동 0 | 기준선. 품목 datalist 는 2글자부터 — 같은 품목 반복 시 이전 행 복사 버튼 제안 |
| 엑셀 올리기 대화상자 | 미리보기 단계엔 [닫기]가 없고 [작업 취소]만 — 닫으려면 작업을 지워야 함 | [닫기(작업 유지)] 추가 또는 문구로 안내 |
| 공통(775px) | 상세 서랍이 열린 채로 남아 목록 오른쪽·상단 버튼을 덮음 | 서랍 전환 경계에서 닫힘 상태 확인 |

## 생산·POP 화면 테스트(500건) — 작업지시·LOT·POP·실적·입고·역추적·ERP (2026-10-22, 화면 조작, 접두어 `ZT-MES-`)

local 서버(사용자 기동, DEMO 회사, admin)·내장 브라우저. DB 직접 INSERT 없음 — 데이터는 화면 폼을 페이지 안에서 구동(신규 → 칸 입력·input/change 이벤트 → 저장 버튼 → 화면 메시지 확인)해서 만들었다. API 직접 호출은 E·F·C 시험에만. 13:53~15:00 Docker Desktop(WSL) 장애로 DB 가 내려가 중단(모든 API 500 `Could not open JDBC Connection`, health 30초 시간 초과) — 환경 문제, 재기동 뒤 이어서.

**만든 것(화면별)**

| 화면 | 건수 |
|---|---|
| 품목(mst-item) | 2 (ZT-MES-FG1 제품, ZT-MES-RM1 원자재) |
| 장비 계층(mst-equip-hier) | 6 (구역 A·라인 L1·셀 C1·C2·창고 WH·로케이션 LOC1) |
| 공정·설비·작업자 | 공정 2(ASM·INS), 설비 2(EQ1@C1·EQ2@C2), 작업자 2(W1·W2, 배지) |
| 라우팅·BOM | FG1/A 확정(10 ASM@C1 CT30, 20 INS@C2 CT20), BOM FG1/A 확정(RM1 ×2) |
| 작업지시(prd-wo) | **522** (WO2610-0001~0522) — 발행 62, 취소 1, 완료 23, 마감 13 |
| LOT(prd-lot) | 자재 LOT 등록 7, 분할 6, 병합 1, 보류 1 / 생산 LOT(POP) 약 52 — 합계 65 |
| POP 단말(sys-terminal) | 3 (TAB1·TAB2 = C1, TAB3 = C2) |
| POP 실적 | 지시 26건 진행, 24건은 공정 10·20 모두 착수→실적→종료(화면 구동), 실적 54행 |
| 생산 입고(prd-receipt) | 25 입고 + 정정 1 |

**500건 상태 목록·검색·엑셀 속도**

| 화면 | 결과 |
|---|---|
| prd-wo 목록 | API 15~55ms(500행). 화면 표는 **500행 상한**("앞의 500건만 보입니다") — 페이징·정렬·엑셀 없음 |
| prd-wo 저장(폼 구동) | 1건 0.2~0.5s. 370건 근처부터 한동안 3.0s/건(단독 재현 시 0.5s — 다른 세션 부하 추정), 2건은 라우팅 선택지가 15s 안에 안 와 실패 |
| prd-result | 조회 33~59ms, 엑셀 5보기 74~170ms(4.7~9.2KB) |
| prd-erp-export | 조회 31ms, 엑셀 75~83ms |
| prd-lot 목록(65) | 17~28ms |
| POP 한 지시 끝까지(착수→수량→LOT 스캔→투입→확정→종료) | 1.3~2.0s/건(외 6.8·11.2s 각 1회) |

**해 본 것 / 결과 / 조치**

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| C 작업지시 저장 5연타 | 1건만 생성 | 정상 |
| F 발행 뒤 수량 PUT / 낡은 rowVer | `planQty` `FIXED` / `CONFLICT` | 정상 |
| F 재발행·계획 지시 완료·마감·사유 없는 취소·취소 지시 발행·수정·재취소 | 모두 `STATE`(취소 사유 없으면 `REQUIRED`) | 정상 |
| H 수량 0·−5·`1,000`·`１０`·`1e3`·소수·20자리, 종료<시작, 9999-12-31, 2020년(라우팅 없음) | 모두 칸 오류(라우팅 없음은 `routingId` 칸) | 정상 |
| H 검색: 품목 `ZT%`·`%`, 키워드 `%`·`_`·51자, status=FOO | 0건·0건·TOO_LONG·CODE — 와일드카드 이스케이프 | 정상 |
| I 저장형 XSS: 지시 비고 `<img onerror>`·`=HYPERLINK`, 공급사 LOT `<script>` | 글자로만 표시, 실행 없음 | 정상 |
| LOT 분할: 잔량 초과·0·`1.5`·`１００` | 각각 거부 | 정상 |
| **LOT 분할 prompt 에 `1,500`** | **LOT 1EA + 500EA 두 개 생성**(L261022-0010·0011). 쉼표가 "여러 개" 구분자. `1,000` 은 "0보다 큰 값" 엉뚱한 메시지. 같은 화면 목록은 `4,850` 처럼 쉼표 표시 | **고칠 것(F2, 조용히 틀림)** |
| LOT 보류 LOT 분할·병합, 빈 LOT 분할, 같은 LOT 두 번 병합, 잔량 초과 병합, 다른 품목 병합 | LOT_HOLD·EMPTY_LOT·DUPLICATE·OVER_BALANCE·품목 거부 | 정상 |
| LOT 전량 분할(5000 → 5000) | 허용, 모 LOT 0 "소진" | 정책 질의 |
| 분할로 생긴 LOT 의 공급사 LOT 번호 | 비어 있음(이어받지 않음) | 정책 질의 |
| 제품(자체 생산) 품목을 "자재 LOT 등록" | 허용(L261022-0005) — 지시 없이 완제품 LOT·재고 생성 경로 | 정책 질의 |
| K 단말 활성화 코드 소문자·하이픈 없이 | 허용(정규화) | 정상 |
| C·K 착수 5연타 / 같은 clientReqId 재전송 / RUN 중 새 착수 | 요청 1회 / `replayed:true` / `STATE` | 정상 |
| C·K 두 단말(TAB1 화면 + TAB2 curl, 같은 셀) 동시 착수 6요청 | 1건 START, 5건 `STATE` | 정상 |
| K 다른 셀 단말이 공정 조회 | 404 | 정상 |
| K 스캔: 보류 LOT·없는 LOT·같은 LOT 두 번 | "보류 중"·"없습니다"·"이미 읽었습니다" | 정상 |
| H·K 투입 수량 > LOT 잔량(3 에 5) | 거부 "LOT 잔량보다 많습니다" — **어느 LOT 인지 표시 없음**(3개 스캔 상태) | 디자인(아래) |
| H 불량 유형 합 ≠ 폐기 수량, 없는 불량 코드, 음수·0·소수·13자리·전각, 미래 시각 | DEFECT_SUM·NO_SUCH_CODE·NOT_NEGATIVE·REQUIRED·SCALE·INT_DIGITS·FORMAT·FUTURE | 정상 |
| H 과거 시각(2020) | 거부되나 메시지가 **`{0}~{1} 사이로 입력해 주세요.`**(자리표시자 그대로) | 고칠 것(i18n) |
| H **불량 > 작업수량**: 계획 20 에 양품 0·폐기 50 / 양품 1,000 | 둘 다 경고 없이 확정(WO0522 공정10 양품 1,018) | 정책 질의(H1) |
| H 제품 FG1 LOT 을 FG1 공정 투입(BOM 외·자기 품목) | 허용, 계보에 FG1 → FG1 | 정책 질의 |
| K **순서 실수**: 공정10 양품 0 인 WO0027 의 공정20 착수·양품 3 → 50 추가 | 둘 다 확정, 완제품 LOT 0039·0040 생성. 완료판정은 `PREV_OPEN`·`BALANCE`(차이 53)로 막지만 **진행 중 지시라 입고가 됨 → LOT 0039 3EA 재고 입고 성공** | **고칠 것 후보(K1)** — 앞 공정 양품 한도 또는 BALANCE 있는 공정 LOT 입고 차단 |
| K 멈춤(사유 버튼) → 재개 4연타 → 종료 4연타 | 요청 각 1회, confirm 1회 | 정상 |
| F 종료 뒤 실적 / 종료 뒤 재착수 | `STATE` / 허용(종료 = 작업 구간 끝, 공정 닫기는 완료판정) | 정상(설계) |
| 완료판정 → 지시 완료 (24건 화면 구동) | 22건 완료. WO0522(공정20 실적 없음)·WO0297(공정10 123/계획 23 — 첫 구동 스크립트의 수량 해석 실수로 입력, 서버가 초과 생산으로 받아들임) 은 보존식으로 막힘 | 정상(차단 동작 확인) |
| **생산 입고 기본 수량(잔량 ≥ 1,000)** | 폼이 `1,018` 처럼 쉼표 넣어 채움 → 그대로 [입고] 하면 "형식이 올바르지 않습니다" — 사용자가 쉼표를 지워야 함 | **고칠 것(R1)** — 기본값을 쉼표 없이 |
| C 입고 5연타 / 잔량 초과 / 0 | 1건 / "남은 입고 수량(10)보다 많습니다" / 거부 | 정상 |
| 입고 정정(prompt 사유) | −228 역분개 행 + 원행 "정정됨" | 정상 |
| 미입고 228EA 있는 지시 마감 | 경고 문구 후 허용 | 정책(허용) |
| **F 마감 지시 재오픈**: 입고·입고 정정·발행·취소·완료·재마감·수정·status 끼워 넣기 | 모두 `STATE`, 값 그대로 — **재오픈 경로 자체가 없음** | 정책 질의(정정은 어디서?) |
| F 완료 지시 공정에 POP 착수·실적 | 404(할 일에서 빠짐) | 정상 |
| **역추적: 2공정 지시의 완제품 LOT(공정20)** | "어디서 왔나 — 기록 없음". 공정10 LOT 은 자재까지 이어지지만 공정20 LOT 과 연결 없음, 공정10 LOT 잔량이 소비되지 않고 남음(같은 FG1 LOT 이 지시마다 2개, 재고·LOT 목록 이중) | **정책 질의(높음)** — 다음 공정이 앞 공정 LOT 을 자동 소비·연결하는지, 스캔 강제인지 |
| 설비 배정(WO 공정 설비 칸, EQ1) 뒤 POP 착수·멈춤(설비 고장)·재개·종료 | cms-state 타임라인에 아무 구간 없음 — 설비 상태는 수집 장치 API 로만 | 정책 질의(POP 사유가 CMS 가동률에 안 들어감) |
| ERP 내보내기 | 완료 23·입고 27행, "ERP 품목 매핑 없음 23건" 경고 | 정상 |
| E A사로 B사 | 이번 시험은 DEMO 한 회사만 — **미실시**(자동 테스트 범위) | — |
| (환경) DB 장애 중 500 Whitelabel 화면에 스택 전체 노출 | application.yml 은 never — bootRun 의 DevTools 기본값 추정(코드 미확인) | 범위 밖(로컬만) |
| (기준정보) 장비 계층에서 셀 고르고 [하위 추가] | 알림만 뜨고 폼은 그 셀 "수정 중" 그대로 → 새 코드 넣고 저장하면 **기존 셀 이름·코드가 바뀜**(ZT-MES-C1 → C2 실제 재현) | 고칠 것 후보(UX, 조용히 덮어씀) |

**디자인 관찰**

| 화면 | 본 것 | 제안 |
|---|---|---|
| POP 실적(1280×800) | 결과 배너가 화면 맨 위에 끼어들어 모든 버튼이 약 45px 아래로 밀림 | 배너 자리를 고정(오버레이·고정 높이) — 누르려던 순간 버튼이 움직여 오탭 |
| POP 실적 | [멈춤]·[종료] 가 같은 흰 외곽선 버튼, [종료]는 확인창만 | 종료를 색·위치로 구분 |
| POP 실적 | 잔량 초과 거부 때 어느 투입 LOT 행인지 표시 없음 | 서버 행 번호로 해당 행 빨간 테두리 |
| POP 실적 | 수량 칸이 접근성 이름 없는 `<button>`(스크린리더·자동화가 "button" 으로만 읽음) | aria-label(양품·폐기…) |
| POP 할 일 | 50건 상한, "더 있음"·검색 없음. 정렬이 우선순위 → 날짜라 한 달 뒤 우선순위 1 이 오늘 우선순위 5 보다 앞 | "50건 중 일부" 표시·오늘 것 먼저 |
| POP 할 일 | 카드가 상태(대기·진행·준비)와 관계없이 똑같음 — 상태는 14px 회색 글자 | 상태 색 띠·뱃지 |
| POP 할 일 | 모든 카드에 같은 공정명이 18px 굵게, 고를 때 보는 지시번호·수량은 15px | 지시번호·수량을 제목으로 |
| POP 할 일 | 공정10 을 다 끝낸 지시가 "준비"로 계속 남음(완료판정 전까지) | "실적 있음" 구분 또는 아래로 |
| POP 배지 | 화면에 입력 칸 없음(스캐너 전용), 배지 대소문자 구분, 같은 작업자 두 단말 동시 로그인 허용 | 스캐너 고장 대비 수동 입력(관리자 확인) 여부 결정 |
| POP 크기 | 버튼 48px 이상(착수 140×64, 키패드 약 94×64), 본문 15px, 불량 유형 native select 220×48. 세로 800×1280 에서도 깨짐 없음(아래 40% 빈 공간) | 크기는 충분, 세로에선 키패드를 키워도 됨 |
| 사무 공통 | 확정·발행·완료·마감·보류·분할·정정이 브라우저 기본 `confirm`/`prompt` — 입력 검증·천단위·터치 불편, 분할 쉼표 사고의 원인 | 화면 안 대화상자 |
| prd-wo | 표가 500행 상한·페이징/정렬/엑셀 없음(hb.grid 화면과 다름), 1024 폭에서 행이 3~4줄로 접혀 6행만 보임 | hb.grid 로 통일 |
| prd-wo | 발행 뒤 읽기 전용 text 칸(수량·품목)이 편집 가능한 칸과 똑같이 보임(select 만 회색) | disabled 스타일 통일 |
| prd-lot | 목록 열이 좁아 LOT·품목이 4줄로 접힘 | 열 폭·말줄임 |
| sys-terminal | [신규]가 목록 위, [저장]이 폼 아래 — 다른 화면은 상세 위에 둘 다 | 버튼 위치 통일 |
| mst-proc / mst-routing | 공정 화면은 조회 칸 없음, 라우팅은 첫 그리기 때 조회 칸이 위 라벨형 → 인라인형으로 바뀜(레이아웃 흔들림) | 조회 패널 통일 |
| 문구 | "ZT-MES 검사 **을** 완료판정할까요?" — 받침 없는 말 뒤 조사 고정 | 을/를 처리 |
| J 입력 부담(기준선) | 지시 1건 만들기: 신규 1 + 품목 입력 + 수량 + 날짜 2 + 저장 1 = 클릭 약 6. POP 한 공정 끝까지: 카드 1·착수 1·양품 칸 1+숫자 n·스캔 1·투입 칸 1+숫자 n·확정 1·종료 1+확인 1 ≈ 9 + 숫자 | — |
## 같은 공급사 LOT 추가 입고 = 새 내부 LOT (2026-10-22, docs/25 4.25 — 결정 #43, 임시 V9114)

전체 `./gradlew check`(JDK 21, main 2fbc9c6b 병합 뒤): **1,835건 통과**(건너뜀 2, 실패 0) + JS 검사. `LotTest` +1·단정 변경, WMS `MaterialReceiptTest` 단정 변경, WMS `AsnReceiptTest` +1.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 같은 공급사·같은 라벨 다시 등록(LOT 화면) | 예전 `DUPLICATE` → **새 내부 LOT**, 공급사 LOT 같음 | `LotTest` 단정 변경 |
| 같은 공급사 LOT 세 번(10·20·5) | 내부 LOT 3개, 잔량은 LOT 마다(더하지 않음), 검색어(공급사 LOT)로 3건 | 리콜 조회 |
| 유일 인덱스 / 조회 인덱스 | `uq_lot_master_supplier_lot` 없음, `ix_lot_master_supplier_lot` 일반 인덱스 | V135(임시 V9114) |
| **A사로 B사** | B사가 같은 공급사 LOT 번호로 받아도 A사 검색 3건 그대로, B사 1건 | RLS |
| WMS 입고 같은 공급사 LOT 두 번 | 예전 400 DUPLICATE → 200 두 번, LOT 2·원장 2·공급사 LOT 1종 | `MaterialReceiptTest` 단정 변경 |
| WMS ASN 행 하나를 같은 공급사 LOT 으로 40 + 20 | ASN RECEIVED:60, 내부 LOT 2 — **V135(임시 V9114) 를 빼면 이 시험이 실패**(재현 확인) | `AsnReceiptTest` +1 |

## 작업지시 엑셀 올리기 PRD_WO (2026-10-22, docs/25 4.26 — 그룹 A 3번, 마이그레이션 없음)

전체 `./gradlew check`(JDK 21, main 042252a0 병합 뒤): **1,848건 통과**(건너뜀 2, 실패 0) + JS 검사. 신규 `WoImportTest` 4건, 기존 `WorkOrderTest` 12건 그대로.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 새 지시 2(소문자 코드, KG 소수 3자리) | 채번 `WO{yyMM}-####`, 완료일 = 시작일, 우선 5, 기본 라우팅 A0, 화면 목록 2건 | — |
| 행 오류 10: 공장 없음·없는 공장·사용 중지 공장·없는 품목·EA 1.5·2월 30일·완료가 앞·우선 10·라우팅 없는 품목·없는 지시 번호 | REQUIRED·NO_SUCH_CODE·INACTIVE·NO_SUCH_CODE·SCALE·FORMAT·INVALID_RANGE·RANGE·NO_ROUTING·NO_SUCH_CODE | 화면 저장과 같은 검증 |
| 계획 지시 수량·완료일만 고치기(번호 소문자) | 변경 1 → 수량·완료일만, 우선·비고·라우팅 그대로 | D51 |
| 같은 번호 두 행 / 발행된 지시 / 없는 번호 | FILE_DUPLICATE ×2 / STATE / NO_SUCH_CODE | D52 |
| 공장·품목 바꾸기 / 같은 값 | FIXED / 같음 | — |
| 미리보기 뒤 화면에서 발행 → 실행 | 지시는 RELEASED·수량 100 그대로 | 갱신 0 |
| **A사로 B사** | B사에서 A사 지시 번호 → NO_SUCH_CODE, A사 지시 그대로, B사 새 지시 1 | RLS |
| 조회 권한만 | 올리기 403 | 권한 그룹은 사용자마다 따로 |
| 브라우저 | **미검증** — 버튼만 추가(공통 `hb.importDialog`), JS 타입 검사 통과 | — |

## 수집 COUNT → POP 실적 자동 제안 (2026-10-22, docs/25 4.27 — 그룹 A 4번, V139·임시 V9115)

전체 `./gradlew check`(JDK 21, main 8f0adb93 병합 뒤): **1,855건 통과**(건너뜀 2, 실패 0) + JS 검사. `EquipEventTest` +1(기존 12건 그대로), `PopWorkTest` 12건 그대로.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 착수 전 COUNT 99 / 착수 전 공정 화면 | 원장에는 쌓임 / 제안 없음(WAIT) | since = 착수 |
| 착수 뒤 goodCount 7·scrapCount 1 + `count` 3 | 원장 4행, 제안 양품 10·폐기 1 | `count` = goodCount(D56) |
| 제안 그대로 확정(fromCount) | `entry_method` AUTO, 다음 제안 0 → COUNT 4 뒤 4 | since = 마지막 실적 |
| 사람이 고쳐 확정(fromCount 없음) | KEY | 서버는 값을 바꾸지 않음 |
| 같은 설비에 다른 지시 공정 착수 | 제안 없음 | D54 |
| goodCount -1 / 아는 이름 없음(speed) | METRIC 거부, 원장 그대로 | — |
| 직접 SQL: UPDATE·DELETE / 다른 회사로 A사 설비 행 넣기 | 거부 / `fk_cms_equip_count_ctx_comp`, B사 0행 | 원장 규약·회사 트리거 |
| 기존 COUNT 시험(`count` 1, seq 누락·인증) | 그대로 통과 | — |
| 브라우저 | **미검증** — POP 화면에 제안 줄·[수집 값 쓰기] 추가, JS 타입 검사 통과 | — |
| (발견) 첫 전체 check 1건 실패 — `RetentionTest` "hb_audit 트리거가 없는 회사별 테이블: cms_equip_count" | 원장은 감사 트리거 대신 예외 목록에 근거와 함께(같은 원장 `cms_energy_reading` 과 같은 규칙) | 예외 목록 추가 → 통과 |

## COUNT 원장 역분개 행 검사 (2026-10-22, docs/25 4.27 후속 — V140·임시 V9116)

전체 `./gradlew check`(JDK 21, main 149c775d 병합 뒤): **1,856건 통과**(건너뜀 2, 실패 0) + JS 검사. `EquipEventTest` +1(기존 13건 그대로).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 원 행 GOOD 5 를 −4 / SCRAP 로 / 다른 설비로 되돌리기 | `ck_cms_equip_count_reversal_match` | 트리거 |
| 맞는 역분개(−5) → 한 번 더 | 성공 → `uq_cms_equip_count_reversal` | V139 유일 인덱스 |
| 역분개 행을 다시 되돌리기(qty 0) | `ck_cms_equip_count_reversal_match` | 원 행이 역분개 행이면 거부 |
| **A사로 B사**: B사가 자기 설비로 A사 원 행 id 를 가리킴 | `ck_cms_equip_count_reversal_match`(RLS 로 원 행이 안 보임), A사 행 그대로 | — |
| V140(임시 V9116) 을 빼고 같은 시험 | 실패(틀린 역분개가 들어간다) | 재현 확인 |

## UI 단계 2 — POP 표시 규칙 (2026-10-22, docs/25 4.28 · docs/40 P2 — 마이그레이션 없음)

브라우저(Playwright, 실제 기동 + DEMO 데이터, **ZT 단말·ZT 배지**만 새로 만듦) 1366×768·390×844. 가드 시험(디자인 토큰·템플릿 보안·i18n) 통과, JS 타입 검사·단위 시험 통과. 전체 check 결과는 아래.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 바탕·카드 | 바탕 rgb(233,236,240)(`--hb-bg-muted`), 카드 흰색 | `data-surface="pop"` |
| 할 일 카드 | 제목 "WO2610-0001 · 1,200 EA", 상태 배지 대기 neutral ○ / 작업 중 normal ● | 부록 A.3 |
| 착수 뒤 결과 배너가 뜰 때 공정 화면 위치 | 1366: 126.8 → 126.8, 390: 144.8 → 144.8 — 밀리지 않음 | 배너 자리 고정 |
| 공정 상태 | "작업 중" = normal ●(색 없음) | V-1 현장 화면 |
| 종료 버튼 | 글자·테두리 위험 빨강, 바탕 흰색, 주동작(멈춤)과 1366 896px·390 98px 떨어짐 | `hb-pop-danger` |
| 넘침·누르는 크기 | 가로 넘침 없음, 48px 미만 버튼 0 | — |
| 콘솔 오류 | `/favicon.ico` 404 하나(이번 변경과 무관, 원래 없음) | 범위 밖 |

## UI 단계 3 ②~⑥ — MES 목록 화면 틀 (2026-10-22, docs/25 4.29 · docs/40 단계 3 — 마이그레이션 없음)

브라우저(Playwright, 실제 기동 + DEMO·ZT 데이터, **저장하는 동작은 하지 않음** — 대화상자는 모두 취소로 닫음) 1366×860·390×860, 10화면. JS 타입 검사·단위 시험 통과. 전체 check 결과는 PR 본문.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 브라우저 기본 창(`window.confirm/prompt`) | 10화면 모두 0번(대화상자 이벤트 감시) | `hb.dialog` |
| 남은 confirm/prompt(소스 grep) | production·sales·MES 몫 기준정보 0. BOM·라우팅·설비·품목·BOM 바꿈은 기준정보 몫이라 범위 밖 | — |
| 미저장 이동 | 지시 비고 입력 뒤 다른 행 → 화면 안 "이동" 확인, 취소하면 입력 그대로 | `mesUi.leaveOk` |
| 사유 필수 | 수주 취소 대화상자에서 빈 사유로 [취소하기] → 닫히지 않음 | `required` |
| 위험 버튼 위치 | 지시 취소·LOT 보류·수주 취소·단말 다시 발급/폐기·배지/PIN 지우기가 저장 줄과 다른 줄, 외곽선 빨강 | ④ |
| 위험 버튼 숨김 | 진행 중 지시: 지시 취소 숨김 + 줄도 숨김(빈 줄 없음) | 줄 같이 숨김 |
| 상태 배지 | 지시·입고·수주(목록·상세·행)·단말·근무조·작업자·자격 배지 표시 | 부록 A.3 |
| 390 폭 | 가로 넘침 없음, 대화상자 화면 안 | — |
| 콘솔 오류 | `/favicon.ico` 404(원래 있던 것) 외 없음 | 범위 밖 |
| 빈 도구줄(1366) | 입고·단말 상세 위 [닫기]만 있는 줄 — [닫기]는 좁은 화면 전용이라 넓은 화면에서 빈 줄(입고는 원래 있던 것) | 범위 밖(공통 `hb-drawer-close` 줄 처리) |

## 영업 화면 UI 단계 3 목록 틀 ②~⑥ (2026-10-22)

내시 화면 — 기록은 `docs/testing/master.md` 같은 날짜 절. 수주 화면 ②~⑥ 은 MES 몫.

## UI 단계 3 ① — MES 주 목록 → hb.grid (2026-10-22, docs/25 4.30 — 마이그레이션 없음)

자동 시험 `MesGridTest` 4개(Testcontainers). 브라우저(Playwright, 실제 기동 + DEMO·ZT 데이터, 저장 동작 없음) 1366×860·390×860.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 지시 grid 페이지 경계·전체 건수 | size 2: 1쪽 W-D·W-C, 2쪽 W-B·W-A, 3쪽 빈 목록, total 4 | — |
| 기본 순서 = 목록 API | 지시(조건 없음·ACTIVE), 입고 대상(공장 P1), 단말 — grid 와 목록 순서 같음 | 기본 정렬을 목록과 같게 |
| 정렬 허용 목록 밖 | 지시 `remark`·`planQty`, 입고 `receivedQty`(계산 칸), 단말 `state`(계산 칸) → 400 | enum 허용 목록 |
| 조건 검사 | 지시 상태 `X`·뒤집힌 기간 → 400, 없는 품목 코드 → 0건, 입고 공장 없음 → 400, 상태 `PLANNED` → 400 | 목록과 같은 검사 |
| 단말 폐기 위치 | 코드 내림차순이어도 폐기 단말이 끝 | D61 |
| 단말 상태 계산 | 등록 대기 = WAITING, 기한 지남 = EXPIRED, 폐기 = REVOKED | 서버 `state` |
| A사로 B사 | B사 사용자: 세 grid 모두 0건, A사 지시 번호로 찾아도 0건 | RLS |
| 권한 없음 | 다른 화면 권한만 가진 사용자: 세 grid·columns 모두 403 | `@ProgAuth` |
| 브라우저 grid | 세 화면 열 머리·상태 배지·바닥 건수 표시, 행 고르면 상세 열림, 390 가로 넘침 없음 | — |
| 미저장 이동(grid) | 지시 비고 입력 뒤 다른 행 → 화면 안 "이동" 확인, 취소하면 같은 행·입력 그대로, [이동] 이면 옮김 | `beforeSelect` |
| POP 종료 확인 | 화면 안 대화상자, 버튼 48px, 취소하면 작업 그대로(종료 버튼 남음), 브라우저 기본 창 0번 | `hb.dialog` |
| POP 정정 사유 | 오늘 실적이 없는 단말이라 브라우저 미확인 — 같은 `hb.dialog.prompt`(필수) 경로는 생산 화면에서 확인 | 미검증 |
| 콘솔 오류 | `/favicon.ico` 404(원래 있던 것) 외 없음 | 범위 밖 |

## ResultViewTest 자정~06:00 KST 실패 (2026-10-23 00:13 KST, docs/25 D26)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 원래 시험을 00:15 KST 에 실행 | 5건 중 4건 실패(보기 다섯 가지·조건·엑셀·A사로 B사) — 조회 0건 | 재현 |
| 원인 | 시험 조회 기간 = 달력 오늘(`COMP_TODAY`). 실적은 저장 때 근무조 기준 작업일(`WorkDayResolver`) — 야간조 18:00~06:00 의 자정 뒤 실적은 **전날** 작업일(D26) | 시험 쪽 오류, 제품 규칙은 그대로 |
| 고친 뒤 같은 시각(00:16 KST) 실행 | 5건 통과 | 조회 기준일 = POP 세션 `workDay`(서버가 정한 작업일), 지시 계획 시작일만 달력 날짜 |
| 고정 시계(`BizClock.setClock`)를 쓰지 않은 이유 | 실적 발생 시각·단말 활성화 기한 등은 DB `now()` 와 섞여 있어, BizClock 만 낮 시간으로 옮기면 서로 어긋난다 | 서버가 고른 작업일을 그대로 읽는 쪽 |

## UI 단계 3 ① — LOT 관리 주 목록 → hb.grid (2026-10-23, docs/25 4.31 · D60 갱신 · D64)

자동 시험 `MesGridTest` LOT 1개 추가 + 권한·A사로 B사에 LOT 추가(모두 5개), 기존 `LotTest` 통과. 브라우저 1366×860·390×860(DEMO 데이터, 저장 동작 없음).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 페이지 경계·기본 순서 | size 2: 1쪽 L-B·L-HELD, 3쪽 L-SINK, total 5 — 목록 API 와 같은 순서 | 등록 최신 |
| 상태 계산 | 보류 = HELD, 다 쓴 LOT = EMPTY, 그 밖 ACTIVE | DTO `state` |
| 조건·정렬 | 보류만·기간·번호·없는 품목 → 목록과 같게, 출처 `X`·보류만 `X` → 400, 잔량·상태 정렬 → 400 | 허용 목록 |
| 체크로 막힌 LOT 병합(직접 API) | 보류 LOT 포함 → 400 `LOT_HOLD`, 잔량 0 포함 → 400 `EMPTY_LOT` | 서버가 지킴(D64) |
| A사로 B사 | B사 사용자: LOT grid 0건, A사 LOT 번호로 찾아도 0건 | RLS |
| 권한 없음 | LOT grid·columns 403 | `@ProgAuth` |
| 브라우저: 체크 2개 | [고른 2개 병합] 켜짐, 바닥 "선택 2건" | `checkable` |
| 브라우저: 보류 LOT 하나 더 체크 | [고른 3개 병합] 꺼짐 + "보류·잔량 0 LOT 은 병합할 수 없습니다 — 체크를 빼 주세요: …" → 체크를 빼면 안내 지워지고 다시 켜짐. DEMO 에 보류 LOT 이 없어 grid 응답 한 행을 보류로 바꿔(브라우저 안에서만, DB 그대로) 확인 | D64 |
| 390 폭·콘솔 | 가로 넘침 없음, 오류 없음, 브라우저 기본 창 0번 | — |

## LOT 병합 고르기 — 보류·잔량 0 행 체크 비활성 (2026-10-23, docs/25 D64 갱신 · hb.grid checkableRow #320)

브라우저 1366×860·390×860(DEMO 데이터, 저장 동작 없음). DEMO 에 보류·잔량 0 LOT 이 없어 grid 응답 2·3번째 행을 보류·잔량 0 으로 바꿔(브라우저 안에서만, DB 그대로) 확인. 서버 거부 시험은 `MesGridTest`(보류 `LOT_HOLD`·잔량 0 `EMPTY_LOT`)에 이미 있음.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 체크칸 | 보류·잔량 0 행만 비활성(6행 중 2행) | `checkableRow` |
| 머리 칸 "모두" | 사용 가능 4행만 체크, 바닥 "선택 4건", [고른 4개 병합] 켜짐 | 공통 규칙 |
| 임시 안내 | #312 의 "병합할 수 없습니다" 안내·문구 키 지움(체크 자체가 막힘) | D64 갱신 |
| 콘솔·기본 창 | 오류 없음, 0번 | — |

## 품목 단위 변경 금지에 LOT 사용 (2026-10-23, docs/25 4.32 — V142·임시 V9117)

자동 시험 `LotUnitLockTest` 4개. 관련 `ItemUnitLockTest`(WMS)·`master.item.*` 통과.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 자재 LOT 만 있는 품목 단위 변경(화면 API) | 409 칸 오류 `unitCd` `IN_USE`, 단위 그대로 EA | 앱 검사 + LOT |
| 같은 변경을 직접 SQL | 23503 `fk_mst_item_unit_in_use` | DB 트리거 + LOT |
| LOT 없는 품목·이름만 바꾸기 | 단위 변경 됨 / 이름 저장 됨 | — |
| A사로 B사 | B사 LOT 은 A사 같은 코드 품목의 단위를 막지 않음, B사 품목은 막힘 | 회사 조건 |
| 동시: LOT 등록 먼저(커밋 전) → 단위 변경 | 변경이 기다렸다가 23503 으로 막힘, LOT·품목 모두 EA | `FOR SHARE` |
| 동시: 단위 변경 먼저(커밋 전) → LOT 등록(EA) | 등록이 기다렸다가 새 단위(KG)를 보고 23503 `fk_lot_master_item_comp`, LOT 0건 | `FOR SHARE` |
| `FOR SHARE` 를 빼고 같은 시험 | 동시 2건 실패(둘 다 통과해 EA LOT·KG 품목) — 시험이 결함을 잡는 것 확인 | 원래대로 |

## 단말 유형별 경로 — PDA(창고) 경계 (2026-10-23, docs/25 4.33 — 마이그레이션 없음)

자동 시험 `PopTerminalTypeTest` 4개 + JS `hb.pop.queue` label 1개. 기존 POP 시험(`module.pop.*`)·수집 장치 `EquipEventTest` 모두 통과. 브라우저 POP 흐름(ZT 단말·배지) 1366·390 그대로.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| PDA → 창고 API | 통과(WMS 컨트롤러 없어 404) | PDA 경로 |
| PDA → 할 일·공정·실적·투입·정정 API | 403 `FORBIDDEN` | POP 경로 |
| PDA → `/pop` | `/pop/wh` 로 | 홈 분기 |
| POP → 창고 API / 창고 화면 | 403 / `/pop` 로 | — |
| 둘 다 쓰는 배지 | PDA 도 체인 통과(없는 배지 = 업무 오류, FORBIDDEN 아님) | — |
| 세션 응답 | `terminalType` POP·PDA, PDA 작업장 없음 | — |
| 쿠키 없음 · 위조 쿠키 · 폐기한 PDA | 401 `TERMINAL_UNAUTHENTICATED` / 화면은 활성화로 | 같은 체인 |
| 사무 로그인으로 창고 API | 막힘(시험 도구는 인증을 바로 넣어 403, 실제 세션 쿠키는 이 체인에서 인증 안 됨) | — |
| CSRF 없는 창고 쓰기 | 403 | 쿠키 CSRF |
| A사 단말 id + B사 회사 코드 쿠키 | 401 | — |
| 유형 규칙을 빼고 같은 시험 | 교차 호출 2건 실패 — 시험이 잡는 것 확인 | 원래대로 |
| 대기열 label | 거부·다시 보내기에도 유지, label 없는 POP 건은 null(본문으로 그림) | D67 |

## POP·PDA 셸 공용 도우미 (2026-10-23, docs/25 4.34 — 마이그레이션 없음)

JS 단위 시험 `hb.pop.badge.test.js` 5개(+ 기존 57). 브라우저(Playwright, 실제 기동 + ZT 단말·배지, 저장 동작 없음).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 배지만으로 로그인 | 인사 → onSignedIn, 받아 둔 배지 없음(단위) | `makeBadgeLogin` |
| PIN 요구 → PIN 확정 | PIN 화면, 받아 둔 배지 + PIN 으로 보냄(단위) | — |
| 틀린 PIN / 잠김 | 틀림 = PIN 화면 머묾(배지 유지), 잠김 = 배지 화면(단위) | — |
| 배지 없이 키패드 확정 · 취소 | 아무것도 안 보냄 / 배지 버림(단위) | — |
| 작업자 끊김 | `POP_NO_WORKER` 만 배지 화면 + onGone(단위) | — |
| PDA 브라우저 390·1280 | `/pop` → `/pop/wh`, 없는 배지 = 오류·배지 화면 그대로, ZT 배지 = 인사·메뉴, [작업자 바꾸기] → 배지 화면, 기본 창 0 | — |
| POP 브라우저 1366·390 | 배지 → 할 일 → 착수 → 종료 확인(48px) 그대로 | — |
| PIN 화면(브라우저) | ZT 배지가 PIN 을 요구하지 않아 미확인 — 단위 시험으로 | 미검증 |

## POP 고장 신고 (2026-10-23, docs/25 4.35 — 마이그레이션 없음, CMS 계약 docs/33 11.16)

신규 `PopBreakdownTest` 6·`PopBreakdownNoneTest` 2. 브라우저(Playwright, 실제 기동 + DEMO 설비·ZT 단말·ZT 현상 코드·ZT 배지) 1366·390.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 정상 신고 | CMS 작업지시 BM·REQ, 설비·현상·작업자·단말 저장, 사무 계정(requested_by) 비움, 행위자 `t{id}:TAB-01`. 글만으로도 됨 | — |
| 설비 선택지 | 라인 단말 = 라인 + 아래 셀 설비만(다른 라인·사용 중지·미설치·다른 공장 없음), 공장 단말 = 공장 전체(미설치 포함) | D68 |
| 같은 clientReqId 두 번 · 동시 두 번 | 처음 번호 + duplicate, 작업지시 1건 | 계약 멱등 |
| 칸 오류 뒤 고쳐 같은 id | 새로 만든다(검사 오류는 멱등 기록 전) | — |
| 범위 밖 설비(API 직접) | 다른 공장 `PLANT`, 다른 라인·미설치 `EQUIP_NODE`, 사용 중지 `INACTIVE`, 없는 id `NO_SUCH_CODE` — 현상 조회도 같음, 작업지시 0 | 고칠 것 → 서비스 검사 |
| A사 단말로 B사 설비 id | `NO_SUCH_CODE`(RLS), A·B 둘 다 작업지시 0 | — |
| CMS 칸 오류 그대로 | 글 201자 `symptomText:TOO_LONG`, 요청 id 형식 `clientReqId`, 없는 현상 `symptomCd`, 현상·글 없음 `symptomCd:REQUIRED`, 본문 없음 400 | — |
| 작업자 없음 · PDA | 428 `POP_NO_WORKER` / PDA 세션 breakdown=false, 세 API 403(배지 있어도) | — |
| CMS 구현 없음(NONE, 단위) | 버튼 플래그 거짓, 현상 빈 목록, 신고 409 | — |
| 브라우저: 설비 안 고르고 보내기 · 현상·글 없이 | "설비를 고르세요" / "현상을 고르거나 내용을 적어 주세요" — 요청 안 감 | — |
| 브라우저: 오프라인 | "온라인일 때만" 안내, 요청 안 감, 초안 유지 | D70 |
| 브라우저: 응답 유실(서버는 받음) → 다시 누름 | 같은 요청 id 재전송 → "이미 접수된 신고 · 요청 번호 MWO-…"(같은 번호), 내 할 일로 | D69 |
| 브라우저: 공정 화면에서 신고 | 공정 설비(SPOT-101) 미리 고름, 글만 → 접수, 공정 화면으로 돌아감 | — |
| 브라우저: 화면 | 390 가로 넘침 없음, 고른 설비·현상 굵은 테두리. **발견·수정**: 빈 안내 줄이 현상 버튼 위 틈을 만듦 → 비면 숨김, 공정에서 열어도 뒤로 버튼이 "← 내 할 일" → "← 공정" | 고침 |
| 콘솔 | 404 1건(파비콘 추정 — 기존 POP 화면과 같음), ERR_FAILED 1건(시험이 일부러 끊은 응답) | — |

- 브라우저 확인용으로 로컬 DB 의 DEMO 공정 하나(wo_proc_id 1)에 설비를 잠깐 붙였다가 되돌렸다. ZT 현상 코드 2개·ZT 작업지시는 로컬 DB 에 남아 있다(커밋 대상 아님).

## 수집 카운트 조회·정정 (2026-10-23, docs/25 4.36 — V160·임시 V9118 화면 등록)

신규 `EquipCountTest` 6. 브라우저(Playwright, 실제 기동 + DEMO 설비·ZT 수집 행 24건) 1366·390.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 공장 현지 하루 | P1(서울) 20일 = 00:30 행 포함·19일 23:30 빠짐, P2(UTC) 는 UTC 하루 | — |
| 설비·구분 조건, 정렬 | 설비+양품 = 1행, `sort=equipCd,desc&sort=occurredAt,asc` 순서, 허용 밖 정렬(qty) 400 | — |
| 조건 검사 | 공장 없음·기간 없음(REQUIRED)·뒤집힘(INVALID_RANGE)·92일 초과(RANGE_TOO_LONG)·구분 COUNT(CODE)·날짜 형식(FORMAT) 400 | — |
| 정정 | 역분개 행 1: 같은 설비·구분, qty −12, CORRECT, 사유, 기록자 admin1, **발생 시각 = 원 행**. 원 행 수량·방식 그대로, 둘의 합 0 | D71 |
| 두 번 정정 · 정정 행 정정 | 409 `countId:STATE`, 역분개 1건 그대로 | — |
| 동시 두 번 | 200 하나 + 409 하나, 역분개 1건(유일 인덱스) | — |
| 사유 공백·없음·501자·본문 없음 · 없는 행 | 400 REQUIRED / 400 / TOO_LONG / 400 · 404 | — |
| A사로 B사 | 같은 공장·설비 코드의 B사 행이 A사 조회에 없음, 상세·정정 404, B사 원장 그대로 | — |
| 권한 | 조회만 = 정정 403, 권한 없음 = 조회 403 | — |
| 직접 SQL(원장) | 원 행과 안 맞는 역분개·UPDATE/DELETE 거부 — 기존 `EquipEventTest`(V140) | — |
| 브라우저 | 목록 24행·바닥 건수, 행 → 상세, [정정] → 화면 안 대화상자(빈 사유면 안 닫힘) → 정정됨·[정정] 숨김·정정 행 흐리게, 390 가로 넘침 없음. **발견·수정**: `hb.input.js` 누락으로 조회 패널이 안 뜸(`hb.searchPanel is not a function`) → 스크립트 추가, 상태 배지 "수집 그대로" 가 잘림 → "정상" + 칸 110 | 고침 |
| 콘솔 | 404 1건(파비콘 추정 — 다른 화면과 같음) | — |

- 브라우저용 ZT 수집 행 24건과 정정 행 2건은 로컬 DB 에만 있다(커밋 대상 아님).

## POP 감독자 승인 (2026-10-24, docs/25 4.37 — V169·임시 V9119)

신규 `PopApprovalTest` 7. 기존 POP 시험(`PopWorkTest` 등)·정정을 부르는 CMS 시험 그대로 통과(정책 끔 = 지금 동작). 브라우저(Playwright, 실제 기동 + DEMO 공정·ZT 단말·ZT 감독자 작업자·ZT 자격, 로컬 정책 잠깐 켬) 1366·390.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 정책 끔 | 승인 없이 정정, 승인 원장 0 | P1 기본 N |
| 정책 켬, 토큰 없음 | 403 `POP_APPROVAL_REQUIRED`, 역분개 0. 사유 공백은 그보다 먼저 400 | — |
| 감독자 승인 → 정정 | 역분개 1 + `pop_approval` 1(단말·요청 E1·승인 S1·동작·원 행·역분개 행·사유·행위자 t{id}:코드) | — |
| 쓴 토큰 재사용 | 다른 실적에 403(성공 뒤 지움) | — |
| 감독자 아님 · 자격 만료 · 자격 정책 빈 값 | 403 `POP_APPROVER_NOT_QUALIFIED` | P2 |
| 본인 배지 | 400 `POP_APPROVER_SELF` | P3 |
| 없는 배지 · PIN 없는 감독자 · 동작 코드 틀림 | 400 `BADGE_UNKNOWN` · 409 `PIN_NOT_SET` · 400 칸 `action` | — |
| PIN 4회 틀림 → 5번째 | 400 `PIN_WRONG`(남은 횟수) → 423 잠김, 잠긴 뒤 맞는 PIN 도 423(배지 로그인과 같은 카운터) | P4 |
| 단말 배지 제한 | 감독자 아닌 배지 10번 → 승인 429, 배지 로그인도 429(합산) | P4 |
| 접속 로그 | `POP_APPROVAL_FAIL` 10건 이상 | — |
| 토큰 범위 | 다른 대상·다른 동작(실적 토큰으로 파라미터)·3분 뒤·다른 단말 쿠키·작업자 바뀜 = 거부. 파라미터 토큰으로 파라미터 정정은 됨 | — |
| 같은 토큰 동시 두 번 | 200 하나, 역분개 1·원장 1 | — |
| A사로 B사 | B사의 같은 배지 글자 감독자는 A사 단말에서 안 됨, B사 실적 id 는 토큰을 받아도 정정 404, B사 원장 0 | — |
| 직접 SQL | `pop_approval` UPDATE·DELETE 거부, 자기 승인 행 `23514 ck_pop_approval_self`, B사 작업자 승인자 `23503 fk_pop_approval_ctx_comp` | — |
| PDA | `/api/pop/approvals` 403 `FORBIDDEN` | — |
| 브라우저 | [정정] → 사유 → 감독자 승인 화면(정정할 실적 요약) → 본인 배지 = 거부 문구·배지 단계로, 감독자 배지 → 틀린 PIN = "4번 더" 문구·PIN 화면 유지, 맞는 PIN = "정정했습니다 · 승인 ZT 감독자 …"·공정 화면·취소선 행 +1, 390 가로 넘침 없음, 브라우저 기본 창 0 | — |
| 콘솔 | 404 1(파비콘 추정), 403 1·400 2(승인 필요·본인·틀린 PIN — 의도한 거부) | — |

- 브라우저용 ZT 감독자 작업자·ZT 자격·ZT 단말·정정 행은 로컬 DB 에만 있다. 켰던 DEMO 정책 행은 확인 뒤 지웠다.

## POP 파라미터 정정 버튼 (2026-10-24, docs/25 4.38 — 마이그레이션 없음)

`PopApprovalTest` +1(최근 값 정정 → 그 앞 값이 최근, 정책 켬 → 승인 뒤 정정). 브라우저(Playwright, 실제 기동 + DEMO 공정 WELD_CURRENT·ZT 단말·ZT 감독자, 로컬 정책 잠깐 켬) 1366·390.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 실측 9.0 → 9.9(NG) 뒤 최근 정정 | `lastActualId` 가 9.0 행으로, 판정 OK | — |
| 정책 켬 | 토큰 없으면 403, 감독자 승인 뒤 정정, 원장 1 | 4.37 |
| 브라우저 | 최근 7.7 NG → [정정] → 대화상자(파라미터·값) → 감독자 승인 화면("정정할 파라미터: WELD_CURRENT · 7.7") → 감독자 배지·PIN → "정정했습니다 · 승인 …" → 공정 화면, 최근 9.1 OK. 390 넘침 없음. **발견·수정**: [정정] 이 값 칸과 저장 사이에 들어가 390 에서 저장이 다음 줄로 밀림 → 저장 뒤 줄 끝으로 | 고침 |
| 콘솔 | 404 1(파비콘 추정), 403 1(승인 필요 — 의도) | — |

- 브라우저용 실측·정정 행은 로컬 DB 에만, 켰던 DEMO 정책 행은 지웠다.

## 실적 조회 정정 승인자 (2026-10-24, docs/25 4.39 — 마이그레이션 없음)

`PopApprovalTest` +1(승인 정정 = 승인자 이름·코드, 승인 없는 정정 = 비어 있음). 기존 `ResultViewTest` 5 그대로. 브라우저(Playwright, 실제 기동 + DEMO, 앞선 ZT 승인 정정 행) 1366·390 + 엑셀.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 감독자 승인 정정 | 상세 행 `approverCd` S1·`approverNm` "S1 작업자" | — |
| 정책 끔 정정 | `corrected` true, 승인자 null | — |
| 브라우저 | "정정됨 13:32 · ZT 승인 확인 1366 · 승인 ZT 감독자 …(ZT-SUP…)", 390 넘침 없음 | — |
| 엑셀 | 시트에 승인자 코드·승인자 칸과 값 | — |

## MES grid 엑셀 묶음 (2026-10-24, docs/25 4.40 — V177·임시 V9120)

`MesGridTest` +2(네 목록 엑셀·권한·B사), `EquipCountTest` +1. 브라우저(Playwright, 실제 기동 + DEMO) 1366 다섯 화면 + 390.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 작업지시 | 기본 계획 시작 최신, 상태 = 코드명, 상태 조건·정렬·열 고르기, 뒤집힌 기간·허용 밖 정렬·grid 밖 열 400 | — |
| 입고 대상 | 공장 없으면 400, 기본 진행 중·완료 | — |
| 단말 | 폐기는 끝, 상태 "폐기"·"활성화 대기", 작업장 없음 "(공장 전체)" | — |
| LOT | 상태 "보류"·"사용 가능", 보류만 조건, 출처 틀림 400 | — |
| 수집 카운트 | 상태 "정상"·"정정됨"·"정정 행", 구분 "양품"·"불량", 기간 없음·설비 id 형식 400, 조회만 403 | — |
| 모든 열(수량 DEC 칸 포함) | 지시 계획 수량·LOT 잔량 숫자 셀 | **발견·수정**: 브라우저에서 지시·입고·LOT 500(글자 수량을 숫자 칸으로) → 숫자 속성으로 |
| 권한 · B사 | EXCEL 없음 = 403(조회는 됨), B사 = 머리줄만 | — |
| 브라우저 1366 | 다섯 화면 grid 오른쪽 클릭 → [엑셀로 내보내기] → 파일(화면 이름_날짜.xlsx)·"엑셀 파일을 내려받았습니다" | — |
| 브라우저 390 | 오른쪽 클릭이 행을 골라 상세 서랍이 메뉴를 덮음 — 기준정보 품목·설비 화면도 같음 | 범위 밖(공통 후보) |


## LOT·입고 대상 grid 깊은 offset (2026-10-24, docs/25 4.41 — 마이그레이션 없음)

`MesGridTest` +1(쪽 경계: 정렬 값이 같은 행), `MesOffsetPerfTest` 2(실측, `-Dhb.perf=true` 일 때만 — 크기는 환경 변수 `HB_PERF_ROWS`). 기존 LOT·입고 시험 그대로 통과.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| LOT 10만, 끝 쪽(2000) | 전 3,694ms → 지연 조인 58ms → nulls last 뺌 24ms | 지연 조인 + NOT NULL 키 nulls last 삭제 |
| LOT 10만, 1쪽 | 122ms → 55ms → 10ms | 〃 |
| LOT 100만 | 채우기가 2시간 안에 안 끝나 못 잼 — QA N-1 실측(1쪽 0.4초 → 0.4ms)을 근거로 | 범위 밖(실측 틀 채우기 속도) |
| 입고 대상 10만, 끝 쪽 | 전 690ms → 105ms → 77ms | 〃 |
| 정렬 nulls last 가드 | `SortNullsLastGuardTest` 통과(두 문장 KNOWN 삭제) | — |
| 보류만(잔량 조건) + 건수 | 10만 49ms → 43ms(보류 부분 색인) | 잔량 칸 두지 않음(4.41) |
| 같은 등록 시각 LOT 7개, 3행 쪽 3번 | 7개 한 번씩, 빠짐·겹침 없음 | 정렬 끝에 `lot_id` |
| 같은 계획 시작일 지시 7개 | 빠짐·겹침 없음, 같은 날짜는 id 큰 순 | 정렬 끝에 `wo_id` |
| 품목 정렬 가운데 쪽 | 10만 2,001ms → 490ms | 남은 비용(다른 표 값 정렬) — 그대로 |
| A사로 B사 | 기존 `MesGridTest` B사 시험 그대로 통과 — B사로 LOT·입고 대상 0건, A사 LOT 번호 검색 0건(안쪽 부질의도 RLS 안) | — |

## POP PIN 확인 연결 겹침 (2026-10-24, docs/25 4.42 — 마이그레이션 없음)

`PopPinPoolTest` 4(풀 4·연결 대기 2초 문맥). 고치기 전 코드로 먼저 돌려 4건 모두 실패(연결 3건 `Could not open JDBC Connection`, 잠김 비교 1건)를 확인한 뒤 고쳤다. 기존 POP 시험 44개 그대로 통과.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 풀 4, 단말 8대 동시에 서로 다른 작업자로 틀린 PIN | 전: 연결 대기 시간 초과 / 후: 8건 모두 PIN_WRONG, 실패 수 각 1 | 바깥 트랜잭션 삭제, 짧은 트랜잭션 차례로 |
| 이어서 8대 동시 맞는 PIN | 8건 모두 들어옴, 실패 수 0 | — |
| 같은 작업자를 단말 8대가 동시에 틀림 | PIN_WRONG 4 + PIN_LOCKED 4, 실패 수 5, PIN_LOCKED 로그 1건 | 조건부 UPDATE 선점(횟수가 사라지거나 넘치지 않음) |
| 잠긴 뒤 맞는 PIN | PIN_LOCKED, 그래도 해시 비교 1번(응답 시간으로 잠김 안 알림) | 잠겨도 비교 |
| 잠긴 뒤 PIN 없이 배지만 | PIN_LOCKED | — |
| 15분 지난 뒤 한 번 틀림 | PIN_WRONG(남은 4), 실패 수 1 → 맞는 PIN 으로 0·잠금 해제 | 잠금 끝나면 1부터 |
| 형식 틀린 PIN(12ab) | 실패로 셈(1) | — |
| 감독자 승인, 풀 4·8대 동시 틀림 | PIN_WRONG 4 + PIN_LOCKED 4, 배지 로그인과 같은 카운터 5 | 같은 선점 |
## 정렬 nulls last — MES·기준정보·영업·APS 나머지 (2026-10-24, docs/qa/2026-10-24_sort-nulls-last.md — 마이그레이션 없음)

`SortNullsLastGuardTest` 목록에서 6문장 삭제(POP 단말 목록은 PopMapper 가 겹치는 #533 병합 뒤에 이어서). 결과 순서는 같다 — 기존 목록 시험 그대로 통과.

| 문장 | 바꾼 것 | nulls last 남긴 키(빈 값 있음) |
|---|---|---|
| `production/WoMapper#findWoPage` | 모든 키에서 뺌(전부 NOT NULL) | — |
| `aps/ApsMapper#findPlanPage` | 키별로 desc·nulls last 고름(WorkCalMapper 꼴) | `a.plan_nm` |
| `master/AssetMapper#findAssets` | 〃 | 다음 교정일(계산식) |
| `master/WorkforceMapper#findQualPage` | 이미 빈 값 키에만 붙어 있었음 — 가드가 읽는 꼴(when 안)로 옮김 | `q.valid_months` |
| `sales/SalesMapper#findOrderPage` | 〃 | `next_due`(계산 열) |
| `pop/PopMapper#findTerminalPage` | 키별로 desc·nulls last | `t.last_seen_at`(한 번도 안 쓴 단말) |

## LOT 보류 출처 QMS_SPC (2026-10-26, docs/25 4.43 — V191·임시 V9121)

`LotHoldTest` +1. 기존 LOT 보류 시험 그대로 통과, QMS 패키지·가드(i18n 키 집합·Flyway) 통과.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 포트 QMS_SPC 보류(이벤트 41)·같은 근거 다시 | true → false(멱등) | M-1 |
| 같은 번호 다른 출처(QMS_NCR 41) | 둘 다 살아 있음, 사유 요약 둘 다 | — |
| 상세 보류 근거 | source QMS_SPC, refId 41 | — |
| 화면으로 QMS_SPC 근거 풀기 | 400 `HOLD_SOURCE` | 화면은 MANUAL·MES_CORRECT 만(그대로) |
| 포트로 해제 두 번 | true → false, 남은 NCR 근거로 보류 유지 | — |
| 직접 SQL: QMS_SPC 참조 없음 / 'QMS_SPC2' | `ck_lot_hold_ref` / `ck_lot_hold_source` | M-2 |
| A사로 B사(B사 범위에서 A사 LOT 보류) | IllegalArgumentException, A사 행 그대로 | — |

## POP PIN 맞는 비교 중 끼어든 실패 (2026-10-26, docs/25 4.42 보강 — QA O-1, 마이그레이션 없음)

`PopPinPoolTest` +1(맞는 PIN 의 해시 비교만 스파이로 멈추고 그사이 다른 단말로 틀린 시도). 고치기 전 코드로 먼저 돌려 실패(`expected: 3 but was: 0`)를 확인한 뒤 고쳤다. 기존 POP 시험 그대로 통과. QA `PopPinQaTest`(#549)의 관찰 2건은 기대를 뒤집어 고친 동작을 고정한다(`O1_…지우지_않는다`·`O1_…풀지_않는다`).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 맞는 PIN 비교 중 틀림 3번(선점 1 + 3 = 4) | 전: 0 으로 덮어씀(예산 다시 가득) / 후: 맞는 PIN 은 들어오고 실패 수 3, 다음 틀림 2번째에 잠금 | `pinOk` = 선점 수 그대로일 때만 0, 아니면 자기 몫 − 1 |
| 맞는 PIN 비교 중 틀림 4번(선점 5번째 = 잠금) | 전: 잠금 풀림 / 후: 맞는 PIN 은 들어오고 잠금 그대로(실패 수 4), 다음 맞는 PIN 은 PIN_LOCKED | 잠금 시각은 그대로 |
| 끼어듦 없음(앞선 실패 2 + 맞는 PIN) | 실패 수 0 — 지금 동작 그대로 | — |
| 5번째 시도가 맞는 PIN(자기 선점이 잠금) | 0·해제 — 지금 동작 그대로(잠긴 동안 온 시도는 선점 0건이라 수가 안 바뀜) | — |

## POP 고장 신고 사진 (2026-10-26, docs/25 4.44 — CMS C25 계약, 마이그레이션 없음)

`PopBreakdownPhotoTest` 3. 기존 POP 신고 시험(`PopBreakdownTest` 6·`PopBreakdownNoneTest` 2) 그대로 통과. 판정 세부(크기·정책·잠김·롤백)는 CMS `CmsPopPhotoTest` 가 덮는다. 브라우저(Playwright, 실제 기동 + DEMO 설비 + ZT 단말·ZT 배지·ZT 현상) 1366·390.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 신고 응답 사진 한도 | photoMax 3, photoBytes ≤ 5MiB, photoExts jpg·jpeg·png(CMS photoLimits) | — |
| 사진 1·2·3 → 넷째 | count 1·2·3 → 409 `file:PHOTO_LIMIT` | — |
| 같은 바이트 재전송 | 같은 fileId, duplicate, 수 그대로 | — |
| 저장 자리·행위자·접속 로그 | `cms-breakdown` × 작업지시 3장, created_by `t{id}:TAB-01`, FILE_UPLOAD client_type POP 3건 | `hb.pop.upload` 가 POP 헤더 |
| 작업자 없음 / PDA / 파일 없음 | 428 `POP_NO_WORKER` / 403 / 400 `file` | — |
| 같은 회사 다른 POP 단말 / 다른 신고 요청 id | 400 `reqNo` / 400 `clientReqId` | — |
| png 이름에 pdf 내용 / exe | 400(공용 거부) | — |
| 접수(OPEN) 때 붙임 → 착수(WIP) 뒤 | 200 → 409 `CMS_MWO_STATE` | — |
| A사 단말로 B사 번호·요청 id | 400 `reqNo`, 두 회사 사진 0 | — |
| 브라우저: 14MB 4000×3000 JPEG(EXIF 표지) | 약 1MB 로 보냄, 서버 파일 1600×1200·EXIF 없음 | 늘 다시 인코딩(D74) |
| 브라우저: 응답 유실(서버는 받음) | "보내지 못함" + [다시 보내기] → "이미 붙어 있음", 2 / 3 | 같은 바이트 재전송 |
| 브라우저: 글자 파일 .jpg / 오프라인 | "사진을 읽을 수 없습니다" / "사진은 온라인일 때만", 요청 0 | — |
| 브라우저: 셋째 PNG(1200×900) | JPEG 로 다시 인코딩해 붙음, 3 / 3 → [사진 찍기] 숨김 | — |
| 브라우저: 390 폭 | 가로 넘침 없음, 브라우저 기본 창 0 | — |
| 브라우저: 오프라인 안내 뒤 성공해도 오류 문구가 남음 | — | **발견·수정**: 성공 안내("사진을 붙였습니다 n / 최대")로 덮고, 사진 전용 오프라인 문구로 |

## 생산 입고 위치 — WMS 판정 (2026-10-27, docs/25 4.45 — WMS S2 계약, 마이그레이션 없음)

`CompletionReceiptTest` +1. 기존 입고 시험(재고 안 둠 저장 거부·A사로 B사 등) 그대로 통과. 브라우저(Playwright, 실제 기동 + DEMO + ZT 로케이션 2개) 1366·390 — 입고 폼은 완료 지시(WO2)의 LOT 응답만 "남음 5"로 바꿔 열었다(위치 목록은 진짜 서버 판정).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 지시·LOT 기준 목록 | 보통 칸 AVAILABLE, 기본 상태 QC 칸 QC, 재고 안 둠 칸 사유 `STOCK_NOT_ALLOWED` | WMS ReceiptLocQuery |
| 지시 없이 공장만(예전 호출) | 사유·상태 없음 | 그대로 |
| 재고 안 둠 칸을 API 로 직접 | 400 `nodeId:STOCK_NOT_ALLOWED`, 입고 행 없음 | 저장 전 같은 판정 |
| QC 칸으로 저장 | 재고 원장 상태 QC | — |
| NONE(WMS 없음) | 목록 사유·상태 없음, 저장은 post 가 400 으로 막음 | 서버 검증 그대로 |
| A사로 B사(B사가 A사 지시로 위치) | 404 | — |
| 브라우저 1366·390 | 재고 안 둠 칸 비활성 "— 재고 안 둠", QC 칸 "— 입고 상태 검사 대기", 넘침·콘솔 오류 없음 | — |
| 관찰: 출하 대기(STAGING) 칸도 받을 수 있음 | WMS 판정 그대로 | 범위 밖 — WMS F1 과 함께 |

## WMS F1 뒤 확인 — 출하 대기 칸 입고·투입 (2026-10-28, docs/25 4.45 보강 — WMS #634, 마이그레이션 없음)

`CompletionReceiptTest`(입고 위치 시험에 STAGING 칸 추가), `MesStagingInputQaTest` 1(실제 POP + 실제 WMS, QA 고정물 사용 — MES 소유). 브라우저(실제 기동 + DEMO + ZT 칸) 1366·390.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 입고 위치 목록의 STAGING 칸 | 사유 `STAGING_NOT_ALLOWED`, 화면 비활성 "— 출하 대기 칸" | 사유 문구 키 추가 |
| STAGING 칸을 API 로 직접 입고 | 400 `nodeId:STAGING_NOT_ALLOWED`, 입고 행 없음 | — |
| POP 투입 — LOT 재고가 출하 대기 칸에만 | 409 `INSUFFICIENT_STOCK` + details "(10) … 먼저 일반 칸으로 옮기세요", 실적·계보 없음, 재고 그대로 | — |
| 일반 칸으로 바꾼 뒤 같은 요청 id 다시 | 투입·차감(10 → 7) | — |
| 브라우저: POP 오류 줄 | 고치기 전 details 를 안 보여 안내가 사라짐 → "재고가 부족합니다. — … 먼저 일반 칸으로 옮기세요." | **발견·수정**: `home.js` fail·`hb.pop.js` 대기열 거부 사유에 details |
| 브라우저: DEMO FG-B01("출하 대기 B01") | 받을 수 있음 — 칸 속성 없음(STORAGE) | 데이터 — 상위에 알림 |

## 완료판정 경고 확장점 CloseNotice (2026-10-29, docs/25 4.46 — 마이그레이션 없음)

`CloseNoticeTest` 5(시험용 CloseNotice 빈 — 경고·없음·실행 오류·SQL 오류 모드), `CompletionReceiptTest` 1줄(빈 없음 = `notices` 빈 목록).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 빈 없음(공용 문맥) | close-check `notices` = [] | — |
| 빈이 산출 LOT 마다 경고 | close-check·지시 완료판정 목록에 경고 2(code·문구·LOT 참조), problems 는 비어 있음 | — |
| 빈이 받은 문맥 | 공장·지시·공정·공정 기준정보·품목·산출 LOT 2(실적 2건)·preview true, 회사 범위 = 부른 회사 | — |
| 경고가 있는데 판정 | 200 DONE + 응답 notices 2, preview false, 판정 행 1 | 막지 않음 |
| 끝난 공정 다시 열기 | 경고 없음, 빈 안 불림 | 다시 계산 안 함 |
| 빈 예외(실행 오류) | 미리보기·판정 모두 성공, 경고 없음, 로그 | 무시 |
| 빈 SQL 오류(`1/0`) | 판정 성공·판정 행 1(트랜잭션 aborted 아님) | 빈마다 세이브포인트 |
| A사로 B사 공정(close-check·completion·close) | 404, 빈 안 불림 | — |
| B사 자기 공정 | B사 범위로 빈이 돌고 산출 LOT 없음 → 경고 없음 | — |
| 브라우저 1366·390(DEMO WO2610-0001, 응답에 경고를 끼워 표시만) | 판정 칸에 "확인 필요: …"(경고 색), 판정 뒤 안내 줄 "10 공정을 완료판정했습니다. — 확인 필요: …", 가로 넘침·콘솔 오류 없음 | 판정 요청은 응답만 꾸며 DEMO 데이터 그대로 |

## 단말 스캔 입력 hb.scan 위로 — C6 (2026-10-29, docs/25 4.47 — docs/50 7장 C6, 마이그레이션 없음)

`hb.pop.scanner.test.js` 5(node --test, 실제 `hb.scan` 위 — 가짜 문서·창). 브라우저(Playwright, 실제 기동 + DEMO + ZT 단말) POP 작업장 홈·창고 PDA × 1366·390.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 호출 모양·반환 | `(input, onScan)` → `onScan(값)`, `{pause, resume}` 그대로, 호출처 무변경 | — |
| 웨지(숨은 칸 + Enter) | 정리된 값 한 번, 칸 비움, Enter 막음, 빈 값 버림 | — |
| native(`hb:scan` 이벤트) | 같은 onScan 으로 한 번 | — |
| pause 중 웨지·native·클릭 | 받지 않음, 포커스 안 가져감 → resume 뒤 칸 비우고 다시(두 번 resume 해도 한 번만) | 예전엔 pause 가 포커스만 놓음 |
| 다른 입력 칸에 포커스 | 빼앗지 않음 | — |
| `hb.scan.js` 없이 부름 | 바로 오류 | 조용히 스캔 안 되는 것 방지 |
| 브라우저: 화면 시작·빈 곳 클릭 | 숨은 칸(`#scan`)에 포커스 | — |
| 브라우저: 웨지로 없는 배지 | "배지를 다시 스캔해 주세요.", 배지 화면 그대로, 칸 비움 | — |
| 브라우저: native 로 맞는 배지(앞뒤 공백·제어 문자 섞음) | 정리된 `ZT-BADGE-01` 한 번만 보냄 → POP 할 일·PDA 메뉴 | — |
| 브라우저: 넘침·콘솔 | 가로 넘침 없음, 콘솔 오류는 일부러 낸 400·파비콘 404 뿐 | — |

## 월별 불량 수량 계약 DefectQtyQuery (2026-10-29, docs/25 4.48 — QMS COPQ 의 MES 몫, 마이그레이션 없음)

`DefectQtyQueryTest` 7. 지금 달은 실제 POP 실적·정정·사무 재작업 처리 API, 지난 달 경계 행은 진행 중 공정에 직접 SQL(발생 시각 지정, 트리거 검사 그대로).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 서울 1/31 23:59 폐기 2·수리 1, 2/1 00:00 폐기 3 | 1월 SCRAP 2·REPAIR 1, 2월 SCRAP 3 | 회사 시간대 월 경계 |
| 회사 시간대를 LA 로 | 둘 다 1월(SCRAP 5) | 회사 시간대를 실제로 씀 |
| 실적 폐기 4 → 같은 달 정정(역분개) | 그 달 0(상쇄) → 다시 넣은 폐기 1 만 | — |
| 1월 실적 폐기 6 → 이번 달 정정 → 다시 넣음 2 | 1월 6 그대로, 이번 달 −6 → −4 — 원래 시각 재입력은 지시 발행 전이라 400 RANGE | 사용자 결정 C-1 (라)(2026-10-30) — 처음 안(1월에서 빠짐)을 바꿈 |
| 재작업 3 → 처리(양품 1·폐기 2), 폐기 0 인 처리 | RESULT REWORK 4 + REWORK SCRAP 2, 폐기 0 처리는 줄 없음 | 원천 분리 |
| 공장 P1·P2·" P2 "·없는 공장 | 2 / 7(공백 정리) / 없음, 전체 9 | 지시 공장 |
| A사로 B사 | A사는 자기 2만, B사는 자기 9만(품목 id 다름) | RLS |
| NONE·from > to·null·37개월 | NONE 빈 결과, IllegalArgumentException, 36개월은 됨 | — |

## LOT 계보 추적 계약 LotTraceQuery (2026-10-29, docs/25 4.49 — WMS W-5 의 전제, 마이그레이션 없음)

`LotTraceQueryTest` 7. 계보는 직접 SQL(원장 트리거 — 같은 회사·잔량·품목 — 그대로 받음).

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| RM1 투입→FG1 분할→FG2·FG3, FG3+FG4 병합→FG5 정방향·역방향 | 루트 depth 0 + via·종류·깊이, 경로 순 | — |
| 여러 루트·중복·null·없는 id | 루트 id 순, 한 번·무시, 없는 id 는 루트 아님 | — |
| 깊이 0 | 루트만, 이어지는 계보 있으면 잘림 / 끝 LOT 이면 잘림 아님 | — |
| 역분개된 계보 | 정·역방향 모두 빠짐 | 살아 있는 계보만 |
| 사슬 5단계를 깊이 3 / 5 로 | 4줄 + 잘림 / 6줄 잘림 아님 | 깊이 잘림 표시 |
| 자식 5,000(줄 5,001) | 5,000줄 + 잘림 | 줄 잘림 표시 |
| A→B→A 순환 | 둘째 A 줄 cycle, 멈춤, 잘림 아님 | — |
| A사로 B사 LOT | 루트 안 됨·계보 안 보임, B사 자기 것은 보임 | RLS |
| NONE·501개·깊이 11·-1·빈 목록·null | NONE 빈 결과, IllegalArgumentException, 500개는 됨 | — |

## ERP 송신 응답 본문 저장 안 함 (2026-10-30, docs/25 4.50 — 사용자 결정 Q-O1, 마이그레이션 없음)

`ErpResponseBodyTest` 2(정해 둔 결과를 돌려주는 어댑터로 송신기만), 기존 `ErpTest` 7 그대로 통과.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| detail 에 JSON 본문(표시 글자·이름·전화) | "REJECTED: HTTP 400 (detail omitted)", 표시 글자·전화 없음 | 송신기 reason |
| HTML 본문·여러 줄·80자 넘는 글 | "HTTP 503/429/400 (detail omitted)" | — |
| 상태 숫자 없는 한글 본문 | "detail omitted", 이름 없음 | — |
| 짧은 사유(400 ITEM_UNMAPPED 등)·빈 값 | 그대로(앞뒤 공백 정리)·"-" | 계약 javadoc |

## 자재 LOT 등록 단말 행위자 (2026-11-01, docs/25 4.51 — 사용자 결정 W-6, 마이그레이션 없음)

`LotReceiveActorTest` 5(단말 요청은 TerminalAuthFilter 와 같은 범위 — 회사 + 단말 행위자 — 로 서비스 호출, PDA 입고 API 는 WMS 가 뒤에), 기존 `LotTest` 13 그대로.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 단말 범위(t42:PDA-01)로 입고 | RECEIVED LOT, created_by = t42:PDA-01 | receiver() |
| 사무 API(로그인) | created_by = admin1 | 그대로 |
| 요청 밖 SYSTEM·사용자 모양 흉내(u1:admin1)·잘못된 단말 모양(t42:·tX:PDA)·비로그인 HTTP | 401, LOT 없음 | 단말 모양만 허용 |
| 다른 회사 단말로 이 회사 품목 | 400 itemCd:NO_SUCH_CODE, 두 회사 LOT 없음 | RLS |
| 단말 범위로 분할 | 401 | 넓힌 곳은 receive 하나 |
| 배지 없음 | MES 는 작업자를 받지 않음 — PDA 입고 API(WMS)가 428 로 막을 몫 | 문서(4.51)·상위 질의 |

## 키오스크 빠져나오기 API — K5 (2026-11-02, docs/25 4.52 — docs/50 K4·K5·Q8, V226·임시 V9122 이벤트 코드)

`PopKioskExitTest` 6, 승인 확인을 공용으로 묶은 뒤 `PopApprovalTest` 9·`PopPinPoolTest` 5 그대로 통과.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 감독자 S1 배지 + PIN — 작업자 없음·본인이 작업자·다른 작업자 로그인 중 | 200 S1·relockAfterSec 600, 접속 로그 POP_KIOSK_EXIT OK worker=S1·행위자 t…:TAB-01·POP | — |
| 자격 없음·자격 만료·없는 배지·PIN 틀림·PIN 없음·빈 본문 | 403 NOT_QUALIFIED·400 BADGE_UNKNOWN·400 PIN_WRONG·409 PIN_NOT_SET·400, 실패 로그 사유 | — |
| 자격 정책 비움 | 아무도 못 빠져나옴 | — |
| PIN 5회 틀림 | 다섯째 423 잠김, 맞는 PIN 도 잠김 | 승인·배지 로그인과 같은 카운터 |
| 배지 로그인 틀림 6 + 빠져나오기 틀림 4 | 맞는 감독자도 429 | 단말 배지 제한 공유 |
| PDA 단말 | 200, 로그 client_type PDA | 단말 공용 |
| 쿠키 없음 / 사무 사용자 | 401 / 거부(시험 도구는 403) | POP 체인 |
| A사 단말에 B사 감독자 배지 | 400 BADGE_UNKNOWN, B사 로그 없음 | 회사 범위 |
| 전체 check 첫 실행 | `SysLogAdminTest` 실패 — 새 AccessEvent 에 ACCESS_EVENT 코드·문구 없음 | **고침**: 임시 V9122 코드 두 줄 + ko/en 문구 |

## 작업지시 [초기화] 가 처음 기본 기간으로 — L-c (2026-11-02, 사용자 결정 "처음 기본 기간 복원", 스키마 변경 없음)

작업지시(prd-wo)는 조회 패널을 만든 뒤 기본 기간(오늘-7 ~ 오늘+30)을 `sp.setValues` 로 넣는데, 기간 컴포넌트의 [초기화]·링크 되돌림은 `data-default` 프리셋(last30 = 오늘-30 ~ 오늘)으로 가서 처음 기간과 달랐다. #769 의 `setDefault(name, value)` 는 기간 컴포넌트에는 값만 넣는다(프리셋 기본 그대로 — hb.input 주석) → 화면이 공개 계약 `onReset` 에서 처음 기간을 다시 넣는다. 링크(sp.link)는 되돌린 뒤 링크 값을 채우므로 링크 기간이 이긴다. 오늘은 그때마다 다시 계산.

| 해 본 것 (브라우저 1366·390, DEMO) | 결과 | 조치 |
|---|---|---|
| 처음 열기 | 오늘-7 ~ 오늘+30 | 그대로 |
| 기간을 바꾼 뒤 [초기화] | 고치기 전 last30(오늘-30 ~ 오늘) → 오늘-7 ~ 오늘+30 | onReset |
| 홈 링크 주소 q.from·q.to(오늘-3 ~ +3) | 링크 기간 그대로 | — |
| 링크 뒤 [초기화] | 오늘-7 ~ 오늘+30 | — |
| 가로 넘침·콘솔 | 없음, 파비콘 404 뿐 | — |

## POP 홈에 키오스크 고정(hb.kiosk) 붙이기 — K5 껍데기 (2026-11-02, sys #779 공통 모듈, 스키마 변경 없음)

POP 홈(pop/home.html·home.js)이 `hb.kiosk.attach()` 를 부른다. 화면에 들어간 것은 셋: 렌더에서 `kiosk.worker(s.workerCd)`(작업자가 바뀌면 — 내려감 포함 — 다시 잠금), 스캐너 맨 앞 `kiosk.isOpen()` 이면 스캔 = 감독자 배지(한 화면에 스캔 듣는 곳 하나 — hb.scan C1). 다리(APK 웹뷰의 YeonShell)가 없는 브라우저에서는 attach 가 아무 일도 하지 않는 객체를 돌려준다. 해제 API·잠금 규칙은 4.52(K5) 그대로.

| 해 본 것 (브라우저 1366·390, DEMO, ZT 단말) | 결과 | 조치 |
|---|---|---|
| 다리 없음(크롬): 홈 열기 → 배지 로그인 → 교대 | 기존 그대로, 다리 호출 0, 배지 화면 복귀 | — |
| 가짜 다리: 홈 열기 | 고정 켬 1회 | — |
| 가짜 다리: 배지 로그인(작업자 없음 → 있음) | 다시 고정 켬 | 작업자 바뀜 = 다시 잠금 |
| 가짜 다리: 교대(작업자 내려감) | 다시 고정 켬 | — |
| 가로 넘침·콘솔 | 없음, 파비콘 404 뿐 | — |
| 해제 대화상자·서버 응답 뒤에만 풀기·다시 잠금 타이머 | sys `src/test/js/hb.kiosk.test.js`(npm test 124 통과) | 공통 모듈 몫 |

## 데모 이력 시더 MesDemoFlow (2026-11-03, docs/25 4.53, 마이그레이션 없음)

`DemoHistoryFlowTest` 3건(신규, MES·QMS·WMS 공용). 시드 한 번(시험 컨테이너): CMS 11~13초 → MES 61~74초(지시 129·실적 771·파라미터 640, NG 7~9·완제품 입고 129) → QMS 3초 → WMS 1초.

| 항목 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 서비스 길 | 지시 `createAs`→`releaseAs`→`update`(계획일)→`assign`, 공정 `eventAt`·`resultImport`·`paramImport`→`closeAs`, `completeAs`→`receiveAt`→`closeWo` | 보존식·게이트·LOT·재고 원장 통과 | 시더 전용 길 4개(API 없음) |
| 원장 두 시각 | 데모 지시 실적·이벤트 occurred_at / recorded_at | 발생 = 지난 91일 안·지금 이전, 기록 ≥ 시험 시작. 실적·파라미터 entry_method 전부 IMPORT | 발행 시각은 지금(V033 트리거 그대로) |
| 상태 | 7일 넘은 지시 / 최근 / 오늘 | CLOSED 100 넘음 / DONE 6~12 / 진행 중 1(평일 09시 뒤) | |
| 근무조 | 실적 shift_cd 종류 | 주간·야간 둘 | |
| 파레토 | 폐기 수량 1위 | SPOT-MISS(처음엔 드로잉 CRACK 이 1위 — 프레스 양이 4배라 폐기율 조정 0.6% → 0.25%, 스폿 0.8% → 1.2%) | 고정 씨앗 |
| CMS KPI(90일) | `KpiService.report` | PR-1200 성능 85%·품질 99.8%·OEE 84%, SPOT-101 86%·98.8%·84%, SPOT-102 75%·98.8%·74%, CO2-201 성능 빈칸(실적 없음 — D21) | |
| 멱등 | 두 번째 시드 / 실제 앱 두 번째 기동 | 0, 흔적 합 그대로 / 표 9개 건수 그대로(131·771·30·19·153·3·43·3·15) | 데모 설비 실적 또는 "데모 이력" 지시가 있으면 건너뜀 |
| A사로 B사 | 같은 기준정보의 B사 — A 시드 뒤 B 흔적, A 지시 id 를 B 범위로 | 그대로, 0 | |
| 기준정보 없음 | 회사·공장·관리자만 | 0, 흔적 없음 | |
| 미래 거부 | `eventAt`·`receiveAt` 지금 + 1시간 | INVALID_VALUE | |
| 브라우저 | 전용 컨테이너(5444)·jar(8097, local) 첫 기동 → `cms-kpi` | 설비마다 성능·품질·OEE 채워짐(최근 7일 공장 합계 OEE 58.4%, CO2 빈칸), 기동 로그 "MES … 129건, 110초"(local SQL 디버그 로그 포함) | |

## MES 2차 — KS9 키오스크 풀기 실패 알림 · QA R4-a 2단계(MES·POP·영업) (2026-11-04, docs/25 4.54, V235(임시 V9101))

`PopKioskExitTest` +5(기존 첫 시험 detail 단언에 `ref=` 반영), `LotTest`·`LotHoldTest`·`PopTerminalTest` 보이지 않는 사유 단언 추가, `VisibleTextGuardTest` 기준선 갱신. K-e(해제 대화상자 웨지 스캔)는 main 의 `hb.kiosk.js` KS10(sys `93b40853`)으로 이미 고쳐졌고 POP 홈(MES) 쪽 붙임 줄은 바꿀 것이 없음 — 이번 브랜치 변경 없음.

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 감독자 배지 + PIN 빠져나오기 | 응답 `exitRef` 22자(빠져나올 때마다 다름), 접속 로그 `worker=S1 ref=…` | — |
| 정상 | 받은 exitRef 로 `unlock-failed` stale | 200, `POP_KIOSK_UNLOCK_FAIL` FAIL `worker=S1 ref=… reason=stale`, 행위자 t…:TAB-01·POP | — |
| B 연타·재전송 | 같은 exitRef 두 번째(refused·stale 다시) | 200 아무것도 안 함, 기록 1 | sys_inbox |
| B 동시 | 같은 exitRef 두 스레드 동시 | 둘 다 200, 기록 1 | — |
| C 입력 | reason `other`·`STALE`·없음 / exitRef 없음 / 빈 본문 | 400(reason CODE·exitRef REQUIRED) | — |
| C 입력 | exitRef `%`·밑줄 22개(LIKE 와일드카드)·21자·모르는 22자 | 404, 기록 없음 — 그 뒤 진짜 알림은 그대로 200 | 끝 일치 `right()` |
| E 권한 | 쿠키 없음 / 사무 사용자 로그인 | 401 / 403 | POP 체인 |
| F 남의 것 | 같은 회사 다른 단말(TAB-02)이 TAB-01 의 exitRef / POP 단말이 PDA 의 exitRef | 404, 기록·멱등 흔적 없음 → 받은 단말은 200 | 행위자 비교 |
| **A사로 B사** | B사 단말(같은 코드 TAB-01)로 A사 exitRef | 404, B사 접속 로그·sys_inbox 0, A사 0 → A사 단말은 200 | RLS |
| R4-a | LOT 보류 사유 `U+200B U+00A0` (화면 API) | 400 REQUIRED(전에는 200) | `OrderService.text` → `VisibleText.optional` |
| R4-a | QMS 출처 보류 계약 `MesLotHoldPort.hold` 사유 `U+200B U+00A0` | IllegalArgumentException(계약 위반) | `VisibleText.strip` |
| R4-a | 단말 폐기 사유 `U+200B U+FEFF U+3000` | 400 REQUIRED | `VisibleText.strip` |
| R4-a 범위 밖 | 마스터 근무조·작업자(`ShiftService`·`WorkerService`) 기준선 3건 | 그대로 | 마스터 몫(MES 1단계 화면이지만 module.master) — 보고에 적음 |

## MES POP 현장 단말 1 — 일일 배정 순서 (2026-11-10, docs/25 4.56, 마이그레이션 없음)

`PopDispatchTest` 5(신규) + 공용 바닥 `PopFloor`. 범위 시험: `io.smarthappy.module.pop.*` 68건 실패 0(기존 63 + 5), `io.smarthappy.guard.*` 73건 실패 0, JS `tsc` 오류 0·`node --test` 180건 통과. 전체 check 는 돌리지 않음(Jenkins 병합 묶음 몫, docs/29 7.12). APS 계약은 가짜 빈(@Primary) — 계약 자체는 APS `ApsFenceDispatchTest`.

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 현행 계획 없음(자리 표 빔) — 내 할 일·공정 보기 | 지금 순서(우선순위·계획일·지시·공정 순) 그대로, `plan` null | 처음엔 공정 보기 500(자리 없는 공정에서 null 을 스트림에 실음) → `findFirst` 뒤 `map` 으로 고침, 시험이 잡음 |
| 정상 | 계획에 두 공정(B 1번 08시, A 2번 09시) | B → A → 계획 밖 둘은 원래 순서로 뒤, 순번·계획 번호·자원 코드·앞선 미착수 1 | — |
| 정책(AP-10) | 앞 순번이 대기인데 뒤 순번 착수 | 200 RUN(막지 않음), 목록·공정 보기에 `early` 참·`ahead` 1 → 앞 순번도 착수하면 풀림 | 표시만 |
| E 권한 | 단말 쿠키 없음 / 배지 없는 세션 | 401 / 목록 0건·공정 보기 428 | 기존 체인 |
| F 남의 것 | 같은 회사 L2 단말(계획에 L1 공정이 있어도) | 목록 0건, 공정 직접 열기 404 | 단말 작업장 조건 |
| **A사로 B사** | B사 단말로 A사 공정 id(가짜 계획에 A사 자리가 있어도) | 404, B사 목록에 A사 공정 없음 — 계약에는 자기 목록의 id 만 묻는다(단언) | RLS + 목록 기준 |
| 순수 함수 | 자원 둘·DONE·READY·RUN·PAUSE 섞음 | 자원별로 따로 셈, DONE 은 시작한 것, READY 는 미착수(APS 배정표와 같은 기준), 계획 밖은 맨 뒤 | — |
| 기존 회귀 | `PopWorkTest` 12 · `PopApprovalTest` 9 등 pop 패키지 전부 | 통과 | — |
| 브라우저 1366×768 | 전용 컨테이너(5447)·bootRun(8099, local). DEMO 에 ZT 데이터(작업자 `ZT-MES-W1`·단말 `ZT-MES-TAB1`(용접 1라인)·지시 3건 비고 `ZT-MES-POP`) → APS 계획 계산·반영(현행 계획) → `/pop` 배지 | "15건 · 계획 APS261110-002 순서", 카드마다 `계획 n번째 · 월-일 시:분~ · 설비`, 계획 밖 5건은 "계획에 없음"으로 뒤. 앞 순번이 대기인 공정을 착수 → 막지 않음(자격 경고 확인만 기존대로), 공정 화면·카드에 "계획 순서보다 먼저 시작 (앞선 작업 1건 대기)" 경고 톤 | 처음엔 앞선 미착수가 있는 카드가 전부 경고 톤(뒤 순번이 다 노랗다) → 경고 톤은 먼저 시작한 공정만, 앞선 건수는 글자만 |
| 브라우저 390×844 | 같은 화면 | 가로 넘침 없음(scrollWidth 390), 계획 줄 줄바꿈 정상 | — |

## MES POP 현장 단말 3 — 설비 점검 (2026-11-10, docs/25 4.58, V266(임시 V9600, CMS 대역))

`PopPmTest` 7(신규 — CMS 계약 `PmCheckPort` 실제 구현까지 관통). 범위 시험: `io.smarthappy.module.pop.*` + CMS `CmsPm*`·`CmsBreakdown*`·`CmsDemoFlowTest`·`CmsGridExcelTest` + `io.smarthappy.guard.*` = 169건 실패 0, JS `tsc` 오류 0·`node --test` 180건.

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | L1 단말: 열린 점검 목록 → 상세(항목 3 + 건너뛰기 사유) → 결과(5.0·양호·메모) + 완료 한 요청 | 목록은 C1 설비의 CHECK 점검 1건만(WO 방식·L2 설비 제외), DONE·OK, `floor_worker_id` = 작업자·`floor_terminal_id` = TAB-01·`performed_by` null·`updated_by` = `t{id}:TAB-01`, 다음 예정 = 완료일 + 1, 사무 점검 실행 상세의 수행자 = 작업자 이름·코드 | — |
| 경계 | 수치 상한 5.0 / 5.1 | 5.0 합격(경계 포함), 5.1 불량 → 실행 NG, 불량 항목 이름 돌려줌 | 판정은 CMS 한 벌 |
| C 입력 | 필수 둘 빠짐 / 4.55(자릿수 넘침)·판정 `MAYBE` / 없는 순번 9 / 요청 id 없음·공백 포함 | 400 — 행 오류(행 키 = 순번) 2·2, 400, `clientReqId` 칸 오류. 실행 OPEN 그대로·결과 0행(같은 트랜잭션 롤백) | — |
| B 재전송·동시 | 같은 요청 id 로 완료 두 스레드 동시 / 값만 바꿔 재전송 | 둘 다 200·duplicate 하나만 참·`row_ver` 1, 재전송 = 처음 결과(OK), 다음 예정이 두 번 밀리지 않음 | 실행 행 잠금 뒤 요청 id 비교 |
| B | 같은 요청 id 를 다른 실행에 / 끝난 실행을 다른 요청 id 로 / 낡은 rowVer | 409 `DUPLICATE_REQUEST`(그 실행 OPEN 그대로) / 409 `CMS_RUN_STATE` / 409 | — |
| 정책(사용자 결정) | 건너뛰기: 사유 없음 / 없는 코드 / `NO_PROD` | 400 REQUIRED / 400 CODE / SKIPPED·`skip_reason_cd`·글자 = 코드 이름·작업자·단말 기록, 재전송 = duplicate, 다음 예정 = 예정 + 1 | 사유 없이는 못 건너뜀 |
| E 권한 | 배지 없음 / 쿠키 없음 / 사무 로그인 / PDA 단말(배지 있음) | 428 `POP_NO_WORKER` / 401 / 401·403 / 세션 `pm` 거짓·403 | POP 체인 |
| F 남의 것 | L2 단말이 L1 설비 점검 보기·완료·건너뛰기 / WO 방식 실행 / 없는 id | 404(자기 작업장 설비 점검만 목록에) / 404 / 404, 실행 OPEN 그대로 | 작업장 설비 범위 + CMS 공장·방식 |
| **A사로 B사** | B사 단말(같은 구조·같은 코드)로 A사 실행 보기·완료·건너뛰기, A사가 쓴 요청 id 를 B사가 사용 | 404·A사 실행 OPEN·현장 칸 비어 있음 / 서로 모름(유일 키는 회사 안) | RLS |
| 직접 SQL | 열린 실행에 작업자 칸만 / 다른 회사 작업자로 끝내기 | 거부(가드·CHECK) / 거부 "not an active worker" | V9600 |
| 사무 회귀 | `CmsPmTest` 6·`CmsPmCalendarTest` 4·`CmsDemoFlowTest` 3·`CmsGridExcelTest` 3·`CmsBreakdownReportTest` 5 | 통과 — 사무 저장·완료·건너뛰기·작업지시 경로가 같은 `finish`·`skipRun` 을 탄다 | — |
| 브라우저 1366×768 | 전용 컨테이너(5447)·bootRun(8099). ZT 점검표 `ZT-MES-CHK`·계획 `ZT-MES-PM1`(SPOT-101)·`PM2`(SPOT-102) → `/pop` [설비 점검] | 목록 4건(데모 2 + ZT 2), 항목 화면: 수치 칸 → 키패드 5.3 → "기준 밖" 표시, 양호/불량 버튼 413×64, [점검 완료] → "불량 항목: 팁 직경, 냉각수 누수" → [고장 신고로 이어 가기] → 고장 신고에 SPOT-101 선택·내용 "점검 불량: 팁 직경, 냉각수 누수" 채워짐(보내지는 않음). DB: DONE·NG·작업자 7·단말 `t4:ZT-MES-TAB1` | 처음엔 항목 화면 뒤에 목록 카드가 그대로 보임(`.hb-pop-tasks` 의 display 가 hidden 을 이김) → `[hidden]` 규칙 추가. 상태 이름 DUE "오늘까지" → "점검할 때"(선행 기간 안이라는 뜻) |
| 브라우저 390×844 | 건너뛰기: 사유 없이 [건너뛰기 확정] → 안내, "비가동(생산 없음)" 고르고 확정 | "건너뛰는 이유를 먼저 고르세요" / "이번 점검을 건너뛰었습니다", DB SKIPPED·NO_PROD. 가로 넘침 없음(390), 양호/불량 172×64 | — |
| 브라우저 못 본 것 | 설비 바코드 스캔으로 점검 열기 | 확인 중 앱 창이 가려져 키 입력 도구가 동작하지 않음 — 스캔 경로(`hb.pop.scanner` → 열린 패널의 `onScan`)는 배지 스캔과 같은 길이고 배지는 앞 단계에서 확인 | 다음 브라우저 확인 때 다시 |

## MES POP 현장 단말 2 — 검사 입력 MES 몫 (2026-11-10, docs/25 4.57, 마이그레이션 없음)

`PopInspTest` 5(신규, 계약 `FloorInspPort` 는 가짜 빈). 범위 시험: `io.smarthappy.module.pop.*` 80건 + `io.smarthappy.guard.*` 73건 = 153건 실패 0, JS `tsc` 오류 0·`node --test` 180건.

| 분류 | 해 본 것 | 결과 | 조치 |
|---|---|---|---|
| 정상 | 공정 보기 / 열 수 있는 유형 | `insp` 참 / 유형 2 + 유형 이름(공통코드), 계약에 공정 id·단말 id | — |
| 정상 | 열기 → 보기 → 계측기 → 측정(값 10.01·-0.5, SCAN) → 불합격 확정 | 계약에 작업자·단말·요청 id·값 그대로(단언), 응답의 자동 확정(`judged`)·화면 그대로 | — |
| 오류 전달 | 계약이 409 `QMS_INSP_STATE` / 칸 오류(행 1 `GAUGE_BLOCKED`) / 400 `QMS_INSP_NO_PLAN` | 그대로 409 / 400 + 행 키·칸·코드 / 400 | — |
| C 입력 | 측정 본문에 줄 없음 / 열기 본문 없음 | 400, 계약 호출 없음 | — |
| E 권한 | 배지 없음 / 쿠키 없음 / 사무 로그인 / PDA 단말 | 428 / 401 / 401·403 / 403 — 계약 호출 0 | POP 체인 |
| F 남의 것 | L2 단말이 L1 공정의 검사 유형·열기·측정·확정 / 내 공정 경로에 다른 공정·다른 품목의 검사 id / 없는 검사 id | 404(계약 호출 0) / 404 — 측정·확정은 계약에 닿지 않음 / 404 | 공정 범위 + 공정 코드·품목 비교 |
| **A사로 B사** | B사 단말로 A사 공정 id 의 검사 유형·열기·측정 | 404, 계약 호출 0 | RLS(공정 조회) |
| 관통(로컬 병합) | QMS 브랜치 `claude/qms-floor-insp`(4064be71)를 로컬에서만 합친 상태(push 안 함)로 `pop.*`·`guard.*`·`qms.FloorInsp*` | 157건 실패 0 — 가짜 빈(@Primary)과 실제 빈이 같이 있어도 시험이 선다. 충돌은 messages 두 파일 끝 블록뿐(둘 다 남김) | 병합 묶음에서도 같은 자리 |
| 브라우저 1366×768 (실제 QMS 구현) | 전용 컨테이너(5447)·bootRun(8099). ZT 검사계획(초품: 인발 강도 계량 TENSILE + 스폿 개수 계수 VISUAL / 순회: 스폿 개수, 샘플 5·Ac 0) → 공정 `WO2611-0165` 20.SPOT [검사] | 실적 전: 두 유형 모두 "아직 실적이 없습니다…"(눌리지 않음) → 첫 실적 5 EA 확정 뒤 "LOT … 로 검사 시작". 초품: 3.5 입력 즉시 규격 밖 표시, 계측기 없이 저장 → "계측기를 먼저 고르세요", [계측기 고르기] → GG-TNS-01, 다시 그려도 입력값 유지, 4.5·4.2·4.8·5·4.1 저장 → 줄 "합격", 계수 줄 [남은 샘플 모두 합격] → 저장 → **"합격 확정 — 모든 특성이 합격"**(자동). 키패드 버튼 96×64. DB: `JUDGED OK`, 측정 15행 `performed_by` 7·`terminal_id` 4·`recorded_by t4:ZT-MES-TAB1`·KEY | — |
| 브라우저 390×844 (실제 QMS 구현) | 순회(중물): 샘플 1 불합격 + 나머지 합격 → 저장 → [불합격 확정] | "불합격입니다 — 값을 확인한 뒤 [불합격 확정]…" + 버튼 나타남 → 확인 대화상자("…단말에서 되돌릴 수 없습니다") → "불합격 확정", DB `JUDGED NG`. 유형 목록에 "최근 합격 11-10 15:20". 가로 넘침 없음(390), 합격/불합격 버튼 156×64 | — |
| 브라우저 못 본 것 | 숫자 스캔(디지털 게이지 → SCAN)·계측기 코드 스캔 | 앱 창이 가려져 키 입력 도구가 동작하지 않음 — `PopInspTest` 가 `entryMethod` SCAN 전달을 본다. 화면 쪽 스캔 분기는 눈으로 못 봄 | 다음 브라우저 확인 때 |

## MES 코어 확장점 3개·완료판정 LOT 보류 차단 (2026-11-12, 마이그레이션 없음)

| 분류(A~K) | 해 본 것 | 결과 | 시험 |
|---|---|---|---|
| 빈 없음 = 기존 동작 | 구현 빈이 없는 공용 문맥의 기존 시험(`WorkOrderTest`·`PopWorkTest`·`CloseNoticeTest`·`CompletionReceiptTest` 등 `production.*`·`pop.*`) | 수정 없이 통과 | 기존 시험 |
| 추가 BOM 꺼짐/켜짐 | 확장점이 빈 목록 → 본체 BOM 없는 지시 발행 / 추가 BOM(3개분) / 같은 BOM 두 번 | 스냅숏 0줄 / PART 6 EA 한 줄 + 출처 `src_bom_id` = 그 BOM / 줄 번호 이어서 2줄(2·4) | `ProdExtensionPointTest` |
| B 상태(조용히 틀림) | 추가 BOM 이 작성 중 → 발행 | 400 칸 `bomId`, 지시 상태·스냅숏 그대로(전부 롤백). 확장점을 끄면 같은 지시가 발행됨 | 같은 시험 |
| **A사로 B사** | A사 지시 발행에 B사 확정 BOM id 를 추가 BOM 으로 | 404(회사 범위 밖이라 안 보임), A사 지시·스냅숏 그대로 | 같은 시험 |
| LOT 출처 꺼짐/켜짐 | POP 실적(해당 없음) → 정해 준 LOT 으로 실적 | 새 생산 LOT 1개 → 두 번째 실적은 정해 준 LOT 에 붙고 LOT 은 늘지 않음 | 같은 시험 |
| 막는 이유 꺼짐/켜짐 | 코드 `ZT_PART_MISSING` → 미리보기·판정 / 코드 없음 → 판정 / 끝난 공정 미리보기 | problems 에 코드·판정 400(코드 그대로)·공정 READY·판정 행 0 / DONE / `DONE` 만, 빈 안 불림 | 같은 시험 |
| **A사로 B사** | A사 공정의 미리보기·판정을 B사 계정으로(막는 빈 켠 상태) | 404·404, 빈 호출 0. 빈은 부른 회사 범위에서 돈다(`hb_current_comp()` 단언) | 같은 시험 |
| LOT 보류 차단 | 산출 LOT 보류 → 미리보기·판정 API 직접 / 보류 해제 → 판정 | problems `LOT_HOLD`·400·공정 READY / DONE | `ProcCloseHoldTest` |

시험이 찾은 결함: 없음. 정책 질의: `LOT_HOLD` 를 정책으로 끌 수 있게 할지(docs/72 6장 1 — 지금은 항상 막음). 범위 밖: 완료판정 표 DB 트리거(직접 SQL)는 보류를 보지 않는다.

검증(2026-11-12, `--no-daemon` 범위 시험): `guard.*`·`module.production.*`·`module.pop.*`·`module.wms.ship.*`·`qms.Insp*`·`qms.FloorInsp*`·`QmsAbuseTest`·`ApqpCpTest`·`qa.InspImportQaTest` **75 클래스 398건 통과·실패 0·건너뜀 2** + checkNoDollarSql·typecheckJs·testJs 통과. 전체 check 는 돌리지 않음(상위 Jenkins). 브라우저 확인은 하지 않음.

## MES 장비 시리얼 구성(as-built) (2026-11-16, docs/77, 임시 V9970)

사용자 결정(2026-11-16): 필수 부품 미장착은 **경고만**(CloseNotice) · 시리얼 번호 **항상 자동 채번** · 1지시 여러 대(`UnitLotSource` 안 씀) · **장착 = 계보 투입**(시리얼마다 장비 LOT `UNIT`).

| 분류(A~K) | 해 본 것 | 결과 | 시험 |
|---|---|---|---|
| 정상 | 계획 2대 지시에 시리얼 2대 → 3대째 / 직접 SQL 3대째 / 시리얼 번호 UPDATE | 번호 `SN{yyMM}-{seq}`·장비 LOT = 같은 번호·UNIT·수량 1·`wo_id` 없음 / 400 `SERIAL_OVER_PLAN` / `ck_prd_serial_count` / `ck_prd_serial_fixed` | `SerialAsBuiltTest` |
| 원장(역분개) | 장착 → 탈거 → 장착 → 교체(A → B) | 원장 5행 INSTALL·REMOVE(−1, reversal_of_id)·INSTALL·REMOVE·INSTALL, 지운 행 없음. 부품 LOT 잔량 5→4→5→4→5 / B 4, 계보 CONSUME + 역분개, `row_ver` +4 | 같은 시험 |
| BOM 대비 | CPU 1·BOLT 3·FANB(비율 0 대체품)·CABLE / 다른 시리얼 BOLT 5 / 장착 후 탈거 | OK·SHORT(1대 소요 4)·SUBST(주 품목 FAN)·EXTRA / MISSING·OVER / 탈거분은 빠져 MISSING | 같은 시험, `SerialCompareTest`(스크랩 빼기·같은 품목 합·대체 SHORT 우선) |
| 완료판정 연결 | 공정 10 미리보기(BOLT 빠짐) / 마지막 공정 20(대체 그룹 빠짐 + 시리얼 1/2대) / 시리얼 없는 지시 | 경고 `SERIAL_PART_SHORT`(문구에 BOLT), 막는 이유(problems)에는 없음 / `SERIAL_PART_SHORT`·`SERIAL_COUNT_SHORT` / 경고 없음 | 같은 시험 |
| 역추적 | 부품 LOT(장착 중 1 + 탈거 1) / 하위 LOT(CHIP → CPU LOT 투입) / 품목 × 부품 시리얼 / 조건 없음·둘 다 / 엑셀 | 2줄 ACTIVE·REMOVED, 거친 LOT 비움 / 2줄 거친 LOT = CPU LOT / 1줄 / 400·`SERIAL_TRACE_ONE` / 200 xlsx | 같은 시험 |
| C 동시 | 같은 부품 시리얼을 두 장비에 동시에 | 200 하나·400 하나, 원장 1행 | 같은 시험 |
| C 동시(덮어쓰기) | 같은 시리얼에 같은 `rowVer` 로 두 요청 동시 장착 | 200 하나·409 하나, 원장 1행·LOT 잔량 −1 만·`row_ver` 1 | 같은 시험 |
| E 값 | 같은 부품 시리얼을 다른 장비에(순차) / 직접 SQL / 탈거 뒤 | 400 `SERIAL_PART_SN_USED`(문구에 장착 중 시리얼) / `uq_prd_serial_part_sn_active` / 200 | 같은 시험 |
| E 값 | LOT 관리 품목에 LOT 없음, 잔량 초과, 부품 시리얼 + 수량 2, LOT 비관리 품목에 LOT, 없는 공정, 미래 시각, 보류 LOT | `SERIAL_LOT_REQUIRED`·`OVER_BALANCE`·`SERIAL_SN_ONE`·`SERIAL_LOT_NOT_ALLOWED`·칸 opSeq·`FUTURE`·`LOT_HOLD` 모두 400 | 같은 시험 |
| D 상태 | 두 번 탈거 / 낡은 `rowVer` | 409 / 409 `CONFLICT` | 같은 시험 |
| H 멱등 | 같은 `X-Idempotency-Key` 두 번 | 200 → 409 `DUPLICATE_REQUEST` | 같은 시험 |
| G 원장 | 장착 행 UPDATE·DELETE, 시리얼 DELETE 직접 SQL(앱 계정) | 모두 거부 | 같은 시험 |
| **A사로 B사** | B사 계정으로 A사 시리얼 상세·대비·장착·시리얼 만들기(A사 지시), 목록, 역추적 / A사 장비에 B사 LOT 번호 / B사 범위 직접 SQL 로 A사 시리얼에 장착 행 | 404·404·404·404, 목록 0건, 역추적 400 NOT_FOUND / 400 lotNo NOT_FOUND / `fk_prd_serial_part_comp`, A사 원장 0행 | 같은 시험 |
| A 권한 | 조회 권한만 있는 계정으로 장착·만들기·엑셀, 역추적 화면 권한 없음 | 403 ×4, 저장 0행(조회는 200) | 같은 시험 |

시험이 찾은 결함(고침): ① 멱등 키 작업 이름을 `prd-serial.install` 로 써서 `sys_inbox.consumer_cd` CHECK(대문자) 에 걸려 **헤더가 있는 요청은 전부 400** — 화면은 늘 헤더를 보내므로 화면에서 저장이 안 됐을 것. `PRD_SERIAL_*` 로 고침 ② 시리얼 목록이 행마다 장비 LOT 을 읽어 `OffsetLedgerGuardTest` 에 걸림 → 지연 조인으로 고침.
정책 질의: docs/77 7장(POP 투입과 이중 차감, 시리얼 취소, 고정 수량 BOM 행, 하위 조립품 시리얼, 현장 교체, ChangeMarker). 범위 밖: 출하 뒤 현장 서비스.

검증(2026-11-16, `--no-daemon` 범위 시험): `guard.*`·`module.production.*`·`module.pop.*`·`IntegrityCheckTest`·`RetentionTest`·`MenuTreeSeedTest`·`ProgAuthCoverageTest`·`TenantCatalog*`·`qms.OffInspTest`·`AppliedMigration*` **69 클래스 337건 통과·실패 0·건너뜀 2**(신규 `SerialAsBuiltTest` 11·`SerialCompareTest` 3). JS `npm run typecheck` 오류 0·`npm test` 186건 통과. 전체 check 는 돌리지 않음(Jenkins 병합 묶음).

### 브라우저 확인 (2026-11-16, 전용 DB 컨테이너 5448 · 앱 8098 · DEMO 회사 ZT 데이터, 1366×641 · 911×427 · 800×600)

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| 지시 찾기 → 2대 만들기 / 장착(CPU·볼트 3·대체 팬 + 부품 시리얼) / 탈거(사유) / 교체(LOT A → B) | 번호 SN2611-0001·0002, 목록 장착 수·BOM 대비(일치·부족·빠짐·대체품·추가)·이력(탈거된 장착 흐리게) 즉시 갱신, 툴팁 표시 | — |
| 화면 뒤에서 같은 시리얼을 먼저 저장 → 화면에서 장착 | 409 안내 + 상세 다시 읽기, 입력값 유지 | 목록의 장착 수가 그대로 → 목록도 다시 읽게 고침 |
| 같은 부품 시리얼을 다른 장비에 | 칸 아래 "장비 SN2611-0001 에 장착 중" | — |
| 장착 중 부품 표(1366, 상세 패널) | [탈거]·[교체]가 세로로 쌓이고 붙음, "1 EA" 줄바꿈 | 화면 전용 `prd-serial.css`(줄바꿈 없음·버튼 간격) |
| 다른 시리얼로 옮김 | 앞 시리얼에 넣던 장착 값이 폼에 남음(잘못 장착 위험) | 시리얼이 바뀌면 폼 비움 |
| [교체] | 교체품 장착 공정이 비어 새 장착에 공정이 빠짐 | 원래 행 공정을 채움 |
| 역추적 1366 | 그리드 가로 스크롤 | 열 폭 줄임(합 1,280) |
| 역추적 → [시리얼 구성 열기] / `?q.lotNo=` 주소로 열기 | 걸러지기만 하고 상세 안 열림 / 칸만 채워지고 조회 안 됨 | `&open=시리얼 id` 로 상세 바로 열기 / 조건이 있으면 바로 조회 |
| 엑셀 2종(API) · 800 폭 서랍 · 콘솔 | 200 xlsx · 서랍 정상 · 이 화면 JS 오류 없음 | — |

고친 뒤 범위 시험: `module.production.Serial*`·`guard.*` 27 클래스 87건 통과, JS typecheck 오류 0·`npm test` 186건 통과.
