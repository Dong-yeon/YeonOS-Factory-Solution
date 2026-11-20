-- =====================================================================
-- PMS 산출물 파일 잠금을 공통 첨부 잠금 규칙으로 (docs/32 PM3 후속, 상위 세션 요청 2026-10-21)
-- - PM3 의 대상 한정 트리거(sys_file 에 pms-deliverable 전용, 제출 이력 조회)를 없앤다.
--   이제 제출 때 파일에 sys_file.locked_yn = 'Y' 를 찍고(DeliverableService), 공통 트리거 tg_sys_file_locked 가
--   사용 중지·내용 변경·해제를 막는다. 올리기 가능 여부(프로젝트 진행 중)는 앱 쪽 FileLockRule 빈(DeliverableFileLock)
-- - 이미 제출된 파일(제출 이력에 있는 파일)은 여기서 locked_yn = 'Y' 로 채운다
-- 남의 표: sys_file — PM3 에서 넣은 트리거·함수 삭제 + 기존 행 locked_yn 채움(칸은 공통 규칙이 만든 것)
-- =====================================================================

DROP TRIGGER tg_sys_file_pms_deliverable ON sys_file;
DROP FUNCTION hb_guard_sys_file_pms_deliverable();

-- 마이그레이션 계정이 슈퍼유저가 아니면 FORCE RLS 를 받으므로 회사마다 범위를 열고 바꾼다(회사 목록은 먼저 배열로)
DO $$
DECLARE
    v_comps varchar[];
    v_comp  varchar;
BEGIN
    PERFORM set_config('app.scope', 'SYSTEM', true);
    SELECT coalesce(array_agg(comp_cd), ARRAY[]::varchar[]) INTO v_comps FROM sys_comp;
    PERFORM set_config('app.scope', '', true);
    FOREACH v_comp IN ARRAY v_comps LOOP
        PERFORM set_config('app.comp_cd', v_comp, true);
        UPDATE sys_file f
           SET locked_yn = 'Y', row_ver = f.row_ver + 1, updated_at = now(), updated_by = 'SYSTEM'
         WHERE f.comp_cd = v_comp AND f.target_type = 'pms-deliverable' AND f.locked_yn = 'N'
           AND EXISTS (SELECT 1 FROM pms_deliverable_hist h WHERE h.file_id = f.file_id AND h.comp_cd = f.comp_cd);
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;

COMMENT ON TABLE pms_deliverable_hist IS '산출물 제출·결정 이력 — INSERT 만. SUBMIT 행의 파일은 제출 때 잠긴다(sys_file.locked_yn, 공통 트리거)';
