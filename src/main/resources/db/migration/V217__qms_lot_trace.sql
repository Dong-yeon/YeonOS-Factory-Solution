-- QMS 출하 추적(리콜) 화면 qms-lot-trace (docs/57 2.4, 상위 결정 T-1~T-5 — 2026-10-29). 읽기만: MES LotTraceQuery·LotLookup + WMS ShipQuery.byLots·lotStock
-- 표 변경 없음 — 화면 등록만
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-lot-trace', 'prog.qmsLotTrace', 'qms/lot-trace', 'QMS', ARRAY['SEARCH','EXCEL']::varchar(20)[], 699);
