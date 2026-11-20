-- PLM PPAP 요소 6·7 ← APQP 현행 확정본 (docs/39 38장 Q1~Q7, 상위 채택 2026-10-26 · PMS A3-6 docs/41 11.11) — V194(임시 V9912)
-- 요소 6(공정 FMEA) = PFMEA 리비전, 요소 7(관리계획서) = 관리계획서 리비전을 근거로 가리킨다. 모듈 간 id 라 FK 없음(이력처럼) —
-- 존재·상태는 APQP 계약 ApqpLookup 으로만 읽고, 표시는 연결한 때의 label 스냅샷("번호 rev N[ 단계]")으로 APQP 없는 설치본에서도 보인다.
--
-- 제출 뒤 불변: 가드 hb_guard_plm_ppap_elem(마지막 정의 V082)은 패키지가 DRAFT 가 아니면 행 UPDATE 를 칸 구분 없이 막는다 —
-- 새 칸 셋도 그대로 덮이므로 함수를 다시 정의하지 않는다(시험 PlmPpapApqpTest 가 owner 직접 UPDATE 로 확인).
-- 기존 행은 셋 다 NULL 이라 아래 CHECK 둘을 통과한다(추가만).

ALTER TABLE plm_ppap_elem
    ADD COLUMN apqp_kind    varchar(4),
    ADD COLUMN apqp_rev_id  bigint,
    ADD COLUMN apqp_label   varchar(100);

ALTER TABLE plm_ppap_elem ADD CONSTRAINT ck_plm_ppap_elem_apqp CHECK (
    (apqp_kind IS NULL AND apqp_rev_id IS NULL AND apqp_label IS NULL)
    OR (apqp_kind IS NOT NULL AND apqp_rev_id IS NOT NULL AND apqp_label IS NOT NULL AND length(btrim(apqp_label)) > 0));
ALTER TABLE plm_ppap_elem ADD CONSTRAINT ck_plm_ppap_elem_apqp_kind CHECK (
    apqp_kind IS NULL OR (elem_no = 6 AND apqp_kind = 'FMEA') OR (elem_no = 7 AND apqp_kind = 'CP'));

COMMENT ON COLUMN plm_ppap_elem.apqp_kind IS 'APQP 근거 종류 (docs/39 38장) — 요소 6 = FMEA, 요소 7 = CP';
COMMENT ON COLUMN plm_ppap_elem.apqp_rev_id IS 'APQP 리비전 id (apqp_fmea.fmea_id·apqp_cp.cp_id) — 모듈 간 id 라 FK 없음, 상태는 ApqpLookup.rev';
COMMENT ON COLUMN plm_ppap_elem.apqp_label IS '연결한 때의 표시 스냅샷 "번호 rev N[ 단계]" — 서버가 만든다';
