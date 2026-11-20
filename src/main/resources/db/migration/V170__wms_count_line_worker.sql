-- =====================================================================
-- WMS 실사 줄의 센 사람(작업자) — docs/30 12.12.13 (사용자 결정 2026-10-24: 기록함) — 임시 번호(docs/29 2.2)
--
-- - 창고 PDA 로 센 수량은 counted_by 가 단말(t{id}:{cd})까지만 남았다 → 배지 작업자를 counted_worker_id 에
-- - 사무 화면에서 다시 세면 비운다(센 사람 = 로그인 사용자 counted_by) — 줄마다 "마지막으로 센 사람" 하나
-- - 작업자는 같은 회사(트리거 — FK 는 RLS 를 보지 않는다), 센 수량 없이 작업자만 있을 수 없다(CHECK)
-- - 남의 표 변경 없음(mst_worker 는 FK 로 참조만)
-- =====================================================================

ALTER TABLE wms_count_line ADD COLUMN counted_worker_id bigint;
ALTER TABLE wms_count_line ADD CONSTRAINT fk_wms_count_line_worker FOREIGN KEY (counted_worker_id) REFERENCES mst_worker (worker_id);
ALTER TABLE wms_count_line ADD CONSTRAINT ck_wms_count_line_worker CHECK (counted_worker_id IS NULL OR counted_qty IS NOT NULL);
COMMENT ON COLUMN wms_count_line.counted_worker_id IS '센 작업자(창고 PDA 배지) — 사무 화면 입력이면 비움(counted_by = 로그인 사용자)';

CREATE FUNCTION hb_check_wms_count_line_worker() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.counted_worker_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = NEW.counted_worker_id AND w.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'worker % is not of company %', NEW.counted_worker_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_count_line_worker_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_count_line_worker BEFORE INSERT OR UPDATE OF counted_worker_id ON wms_count_line
    FOR EACH ROW EXECUTE FUNCTION hb_check_wms_count_line_worker();
