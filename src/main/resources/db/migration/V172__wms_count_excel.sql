-- WMS 실사 화면 엑셀 내보내기·붙여넣기(③, docs/30 12.12.14) — 임시 번호(docs/29 2.2)
-- 붙여넣기는 화면 안에서 센 수량 칸을 채우는 것뿐(저장은 기존 SAVE·rowVer) — 새 권한은 내보내기 EXCEL 하나
UPDATE sys_prog SET actions = actions || ARRAY['EXCEL']::varchar(20)[] WHERE prog_cd = 'wms-count' AND NOT ('EXCEL' = ANY (actions));
