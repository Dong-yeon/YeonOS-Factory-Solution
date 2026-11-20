-- =====================================================================
-- V046 SRM 발주서·통지 (P5 SRM 1부 S4 — docs/31 2장, 결정 D7·D8)
--
-- - 발주서 엑셀·인쇄 = 발주 화면 PRINT 권한, 인쇄 전용 화면 srm-po-print(메뉴 없음, 발주 화면에서 연다)
-- - 통지 = 아웃박스 토픽 SRM_PO_ISSUED / SRM_PO_CANCELLED (토픽 이름은 대문자·밑줄만 — ck_sys_outbox_topic)
--   통지 이력은 새 테이블 없이 sys_outbox 를 읽는다(송신기는 DB 를 쓰지 못한다 — OutboxHandler 규칙)
-- ※ 병행 개발(docs/31 6장): main 에 늦게 들어가는 쪽이 번호를 다시 매긴다
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','APPROVE','PRINT']::varchar(20)[] WHERE prog_cd = 'srm-po';

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('srm-po-print', 'prog.srmPoPrint', 'srm/po-print',  'SRM', ARRAY['SEARCH']::varchar(20)[], 521),
    ('srm-notify',   'prog.srmNotify',  'srm/notify-mgt', 'SRM', ARRAY['SEARCH','SAVE']::varchar(20)[], 530);
