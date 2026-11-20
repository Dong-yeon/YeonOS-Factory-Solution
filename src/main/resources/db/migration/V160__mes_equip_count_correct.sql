-- =====================================================================
-- 수집 COUNT 조회·정정 화면 (docs/25 4.36, 4.27 후속 — 임시 V9118) — 화면 등록만, 표 변경 없음
-- - 원장 cms_equip_count(V139)는 역분개 행(CORRECT·사유·qty = -원 qty)을 이미 받고 V140 트리거가 원 행과 맞는지 본다.
--   없던 것은 사람이 정정할 화면 — 수집기 오작동(중복 이벤트·센서 오카운트)을 되돌리는 길
-- - 표 주인은 CMS(docs/33 14장), 수집기·POP 제안과 같은 MES 코드 자리(module.cms, cms-state 옆)에 둔다
-- =====================================================================

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-equip-count', 'prog.cmsEquipCount', 'cms/equip-count', 'PRD', ARRAY['SEARCH', 'SAVE']::varchar(20)[], 445);
