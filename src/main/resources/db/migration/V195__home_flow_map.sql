-- 업무 흐름 지도 화면 (docs/46 12장) — 흐름 9개(F0~F8)를 화면 상자와 화살표로. 조회만(입력 없음).
-- 화살표 원본은 static/js/common/help-flow.json(도움말 대화상자와 같음), 흐름 단계는 static/js/common/flow-map.json.
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('home-flow-map', 'prog.homeFlowMap', 'home/flow-map', 'SYS', ARRAY['SEARCH']::varchar(20)[], 952);
