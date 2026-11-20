# 54. 데이터 무결성(ALCOA+) · 21 CFR Part 11 대응표 + 기록 무결성 점검 (2026-10-27)

> docs/52 Y1(Q1 결정 "Y10·Y2·Y1 먼저")의 문서 몫이다. **표준·공개 자료와 이 저장소의 코드·시험**만 근거로 쓴다.
> "장치"는 YeonOS 가 하는 것, "남는 운영 책임"은 고객·운영자가 해야 하는 것이다. 인증·적합 판정이 아니다(docs/52 Z3 — 인증 취득은 하지 않는다).
> 근거 열의 약어: M = `src/main/java/io/smarthappy/`, T = `src/test/java/io/smarthappy/`, V = Flyway 마이그레이션.

## 1. 한 줄 결론

ALCOA+ 9원칙은 모두 **장치가 있다**(Original·Complete 은 DB 트리거·권한으로 강제). 빈 곳은 ① 출력물(엑셀·인쇄)의 **서명 표시**, ② Part 11 의 **시스템 검증(11.10(a))·교육 기록(11.10(i))** — 둘 다 고객 책임으로 남는다. 이번에 **다시 실행할 수 있는 점검**(sys-integrity)을 더했다 — 기동 가드·시험이 지키는 규칙을 운영 중에 버튼 하나로 다시 돌린다.

## 2. ALCOA+ 9원칙 대응표

| 원칙 | 뜻(요약) | YeonOS 장치 | 근거(파일·트리거·시험) | 남는 운영 책임(고객) |
|---|---|---|---|---|
| **A**ttributable | 누가 했는지 | 로그인 사용자·단말·작업자 분리 기록(`recorded_by` 단말·`performed_by` 작업자), 트랜잭션마다 `app.actor`(감사 트리거가 읽음), 전자서명 원장(서명자 이름 사본·의미·인증 방법) | M/platform/tenant/TenantAwareTransactionManager.java, V007 `hb_audit()`, M/platform/esign/ESignService.java, M/module/pop/PopWorkService.java | 계정 공유 금지·퇴사자 계정 잠금(사용자 관리 운영) |
| **L**egible | 읽을 수 있는지 | 화면·감사 보기의 사람 글자(코드 → 이름표), 엑셀 내보내기에 **출력 정보 시트**(누가·언제·조건·행 수) | M/platform/excel/ExcelExportService.java(`excel.meta.sheet`), M/platform/grid/AuditViewService.java | 보관 매체·형식 이전 계획 |
| **C**ontemporaneous | 그때 기록했는지 | 원장 두 시각 — 서버 `recorded_at`(바꿀 수 없음) vs 현장 `occurred_at`(입력, 미래 시각은 입력 화면마다 거부), 회사 시간대 `BizClock` | 원장 규약(`TenantCatalogInspector` 원장 칸 검사), M/platform/time/BizClock.java, `ZonelessNowGuardTest` | 현장 입력 지연 관리(교대 마감 전 입력) |
| **O**riginal | 처음 기록이 남는지 | 원장은 **추가만** — 수정·삭제 차단 트리거 `hb_forbid_update_delete` + 앱 계정 UPDATE/DELETE 회수, 정정은 역분개 행 | V006, `TenantCatalogGuard`(기동 중단), **sys-integrity LEDGER**(운영 중) | DB 소유자 자격증명 보관(앱은 갖지 않음 — docs/01 7.1) |
| **A**ccurate | 맞는지 | 수치 판정은 서버(`BigDecimal`, 자릿수 초과는 오류·반올림 금지), 칸 오류 `FieldErrors`, 멱등키(재전송이 두 번 기록되지 않음) | M/platform/common/web/FieldErrors.java, `InboxService.claimRequestKey`, POP `client_req_id` | 계측기 교정(QMS 교정 화면)·기준정보 정확성 |
| **C**omplete | 빠짐없는지 | 회사별 표마다 **변경 감사 트리거**(전·후 값), 접속·보안 로그, 보존 클래스·**법적 보류**(정리 제외), 로그 **일 다이제스트 체인** | V007 `hb_audit`·`hb_purge_logs`, `RetentionTest`(감사·보존 커버리지 가드), `LogDigestService`, **sys-integrity DIGEST·AUDIT** | 다이제스트 불변 저장소 반출(docs/05 D6) |
| **C**onsistent | 순서·시각이 일관한지 | 회사 시간대 하나(`BizClock`), 시험 JVM UTC 고정, 해시 계산은 트랜잭션 UTC 고정 | M/platform/time/BizClock.java, build.gradle `user.timezone=UTC`, `LogDigestService.compute` | 서버 시각 동기(NTP) |
| **E**nduring | 오래 남는지 | 보존 클래스(KEEP·OCCURRED_AT), 정리는 소유자 프로시저만, 백업 상태 감시(`BackupHealthService`, 지표 `hb_backup_age_hours`) | V007, M/platform/ops/BackupHealthService.java, deploy/backup | PITR·불변 사본·복원 시험(docs/05 3장) |
| **A**vailable | 볼 수 있는지 | 감사 보기(변경 이력 화면), 접속 로그 화면, 대상 상세의 서명 목록, 엑셀 내보내기 | M/platform/grid/AuditViewService.java, sys-audit-log·sys-access-log | 보존 기간 안 조회 요청 응대 |

## 3. 21 CFR Part 11 소항목

"있음" = 제품 장치, "고객" = 고객 절차·운영, "없음" = 아직 없음.

| 조항 | 요구(요약) | 판정 | YeonOS 쪽 |
|---|---|---|---|
| 11.10(a) | 시스템 검증(정확·신뢰·일관 성능) | 고객 | 가드 시험·전체 check·CI(docs/52 Y10)가 **근거 자료**. 설치 환경에서의 IQ/OQ/PQ 는 고객 |
| 11.10(b) | 사람이 읽을 수 있는 사본 | 있음 | 화면·엑셀(출력 정보 시트)·감사 보기 |
| 11.10(c) | 기록 보호·보존 | 있음 | 보존 클래스·법적 보류·추가만 원장 |
| 11.10(d) | 접근 제한 | 있음 | 회사 RLS + 화면·액션 권한(`@ProgAuth`) |
| 11.10(e) | 감사 추적(누가·언제·무엇, 이전 값 보존) | 있음 | `hb_audit`(전·후 값), 접속 로그, 원장 두 시각, **sys-integrity AUDIT** |
| 11.10(f) | 작업 순서 강제 | 있음 | 상태 전이(순수 함수 + 조건부 UPDATE), 결재·품질 게이트 |
| 11.10(g) | 권한 확인 | 있음 | `@ProgAuth`·`ProgAuthCoverageTest` |
| 11.10(h) | 입력 장치 확인 | 있음(일부) | POP·PDA 단말 등록·단말 체인 |
| 11.10(i)·(j) | 교육·책임 정책 | 고객 | — |
| 11.10(k) | 문서 관리 | 있음(일부) | 관리문서 리비전(QMS), 설계 문서는 저장소 |
| 11.50 | 서명 표시(이름·일시·의미) | **있음(화면) / 없음(출력물)** | 4장 |
| 11.70 | 서명–기록 결합 | 있음 | 서명 원장에 대상 + **내용 해시**, 바뀌면 "서명 뒤 바뀜" 표시, **sys-integrity ESIGN** |
| 11.100 | 서명 유일·본인 확인 | 있음 | 사용자 하나 = 계정 하나, 서명 때 재인증 |
| 11.200 | 두 요소(아이디 + 비밀번호), 연속 서명 규칙 | 있음 | 서명마다 비밀번호 재확인(+MFA 를 쓰는 사람은 인증 코드), 5회 틀리면 세션 종료, 토큰 5분·한 번 |
| 11.300 | 아이디·비밀번호 관리 | 있음(일부) | 비밀번호 정책(NIST 800-63B-4)·잠금. 주기 점검은 고객 |

## 4. 서명 표시 확인 (11.50) — 화면 · 출력물

| 서명 대상 | 화면(상세) | 엑셀·인쇄 출력물 |
|---|---|---|
| QMS NCR(qms_ncr) | 있음 — 서명자·의미·인증 방법·일시 + 바뀌었으면 빨간 표시 (js/views/qms/ncr.js) | **없음** — NCR 상세 인쇄물 없음, 목록 엑셀에 서명 칸 없음 |
| QMS 계측기 교정(qms_gauge) | 있음 — 교정 행 옆에 서명 (js/views/qms/gauge-calib.js) | **없음** |
| PLM ECO(plm_eco) | 있음 — 서명·의미·방법·일시 + 바뀜 (js/views/plm/plm-common.js) | **없음** |
| PLM PPAP(plm_ppap) | 있음 — 같은 공통 표시 | **없음** — PPAP 제출 메일 본문에도 서명 정보 없음 |

**빠진 곳**: 출력물 4곳 모두. 출력물이 서명 기록의 사본으로 쓰일 때(고객 제출) 이름·일시·의미가 함께 찍혀야 한다 → 후보(다음): 상세 인쇄 화면에 서명 블록 + 엑셀 출력 정보 시트에 "서명" 줄. 지금은 화면으로 확인한다.

## 5. 기록 무결성 점검 (sys-integrity)

시스템관리 → **기록 무결성 점검**. [점검 실행] 하나로 아래 넷을 이 회사 범위에서 돌리고, 결과를 `sys_integrity_run`(추가만 — 앱 UPDATE·DELETE 회수)에 남긴다. 실행은 접속 로그 ACTION(FN1), 결과 엑셀은 EXCEL 로 남는다.

| 점검 | 무엇을 | 이상일 때 | 근거 |
|---|---|---|---|
| DIGEST | 로그 일 다이제스트 체인을 처음부터 다시 계산(`LogDigestService.verify`) | 날짜별 FAIL — 그날 이후 로그가 바뀌었거나 체인이 끊김 | M/platform/log/LogDigestService.java |
| LEDGER | 원장 표마다 차단 트리거(**꺼진 트리거는 없는 것**)·앱 수정/삭제 권한·두 시각 칸 | 표별 FAIL | `TenantCatalogInspector.ledgers()` — 기동 가드와 같은 규칙 |
| ESIGN | 서명 원장의 내용 해시를 대상의 지금 내용으로 다시 계산 | 서명 뒤 바뀜·대상 없음·계약 없는 유형 = WARN(업무상 정상일 수 있음) | `ESignContentSource`(모듈 계약: qms_ncr·qms_gauge·plm_eco·plm_ppap) |
| AUDIT | 회사별 표 중 감사 트리거(꺼진 것 포함) 없는 표 — 근거 있는 예외 밖 | 표별 FAIL | `AuditCoverage.EXEMPT` = `RetentionTest.AUDIT_EXEMPT`(시험이 맞춤) |

**설계 판단**: 시장 제품은 이것을 "검증 문서 패키지"로 판다. 1인 제품은 문서보다 **다시 실행할 수 있는 점검**이 더 강한 증빙이다 — 새 기능이 아니라 이미 있는 가드들의 런타임판이다. 덤으로 기동 가드도 **꺼진 차단 트리거**(`ALTER TABLE … DISABLE TRIGGER`)를 없는 것으로 보게 됐다(전에는 트리거가 있기만 하면 통과).

**시험**(T/module/admin/IntegrityCheckTest): 정상 OK·이력·엑셀·ACTION 로그 / 권한 없이 API 직접 403 / A사 결과를 B사가 번호로 404·B사 서명이 A사 점검에 안 섞임 / 소유자로 `inv_trx` 차단 트리거를 끄면 LEDGER FAIL / 틀린 다이제스트 FAIL / 대상 없음·계약 없는 유형 WARN / 결과 행 수정·삭제 거부. 실제 모듈 계약은 T/module/qms/GaugeCalibTest(서명 2건 일치 → 교정 칸을 바꾸면 "서명 뒤 바뀜").

## 6. 남은 것

- 출력물 서명 표시(4장) — 후보, 사용자 결정 뒤.
- 점검 결과 알림: "이상"이면 관리자 알림함·운영 지표로(docs/52 Y2 의 `hb_*` 와 같은 결) — 지금은 화면·접속 로그만.
- 정기 자동 점검(월 1회 작업) — 지금은 사람이 누른다. 결과 표가 있으니 작업으로 돌리기는 쉽다.
- 다이제스트 불변 반출(docs/05 D6 운영)이 없으면 DB 관리자 권한 공격자는 다이제스트도 같이 고칠 수 있다 — 점검은 "앱 경로 밖 변경"을 잡는 것이지 DBA 를 막는 것이 아니다.

## 7. 출처

- MHRA, 'GXP' Data Integrity Guidance and Definitions (Rev.1, 2018-03) — ALCOA+ 정의
- FDA, Data Integrity and Compliance With Drug CGMP — Questions and Answers (Guidance for Industry, 2018-12)
- 21 CFR Part 11 — Electronic Records; Electronic Signatures (eCFR, 11.10·11.50·11.70·11.100·11.200·11.300)
- PIC/S PI 041-1, Good Practices for Data Management and Integrity in Regulated GMP/GDP Environments (2021-07)

## 8. 검토

<!-- 사용자 검토 결과를 적는다 -->
