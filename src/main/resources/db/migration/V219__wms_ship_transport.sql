-- =====================================================================
-- WMS 출하 운송 정보 (docs/58, 사용자 결정 W-7·W-8 2026-11-01) — 임시 번호(docs/29 2.2)
--
-- - wms_ship_transport: 출하 지시 1 : 0..1 — 운송사·차량 번호·송장 번호. 지시 머리(wms_ship)와 따로 둔 까닭:
--   운송 정보를 고쳐도 머리 row_ver 가 오르지 않는다(피킹·확정·PDA 가 머리 row_ver 로 낙관적 잠금 — 사무가 송장 번호를 적는 사이
--   PDA 피킹이 409 로 튕기면 안 된다). 행 자체의 row_ver 로 운송 정보끼리만 겨룬다
-- - 고칠 수 있는 상태: DRAFT·PICKING·SHIPPED(송장 번호는 보통 출하 뒤에 나온다). CANCELLED·없는 지시는 서비스가 막는다(409/404)
-- - 사진(증빙)은 플랫폼 첨부 sys_file(target_type = 'wms-ship', target_id = ship_id) — 새 표 없음, 보존은 출하 기록과 같음(W-8)
-- - 운전자 이름·연락처는 받지 않는다(개인정보 최소화 — docs/58 3장)
-- - 남의 표 변경 없음(wms_ship 에 회사+id 유일 제약만 더함 — FK 대상)
-- =====================================================================

-- 회사까지 맞춘 FK 의 대상 — ship_id 만 FK 면 FK 검사는 RLS 를 보지 않아 B사 세션이 A사 지시 id 로 행을 넣어(comp_cd = B)
-- A사 자기 운송 행을 PK 충돌로 막을 수 있다. (comp_cd, ship_id) 로 묶어 다른 회사 지시는 참조 자체가 안 되게
ALTER TABLE wms_ship ADD CONSTRAINT uq_wms_ship_comp_id UNIQUE (comp_cd, ship_id);

CREATE TABLE wms_ship_transport (
    ship_id        bigint         NOT NULL,
    comp_cd        varchar(20)    NOT NULL,
    carrier_nm     varchar(100),
    vehicle_no     varchar(30),
    tracking_no    varchar(60),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_ship_transport PRIMARY KEY (ship_id),
    CONSTRAINT fk_wms_ship_transport_ship FOREIGN KEY (comp_cd, ship_id) REFERENCES wms_ship (comp_cd, ship_id),
    -- 빈 문자열·공백만은 저장하지 않는다(서비스가 null 로 바꿈 — DB 최종 방어)
    CONSTRAINT ck_wms_ship_transport_carrier CHECK (carrier_nm IS NULL OR length(btrim(carrier_nm)) > 0),
    CONSTRAINT ck_wms_ship_transport_vehicle CHECK (vehicle_no IS NULL OR length(btrim(vehicle_no)) > 0),
    CONSTRAINT ck_wms_ship_transport_tracking CHECK (tracking_no IS NULL OR length(btrim(tracking_no)) > 0)
);
COMMENT ON TABLE wms_ship_transport IS '출하 운송 정보 (docs/58) — 출하 지시 1:0..1. 머리 row_ver 와 따로(피킹 낙관적 잠금과 겨루지 않음)';
COMMENT ON COLUMN wms_ship_transport.carrier_nm IS '운송사(자유 입력 — 운송사 기준정보는 아직 없음)';
COMMENT ON COLUMN wms_ship_transport.vehicle_no IS '차량 번호';
COMMENT ON COLUMN wms_ship_transport.tracking_no IS '송장(운송장) 번호';

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_ship_transport FOR EACH ROW EXECUTE FUNCTION hb_audit('ship_id', '', '');

ALTER TABLE wms_ship_transport ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_ship_transport FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_ship_transport USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON wms_ship_transport FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('wms_ship_transport', 'QUALITY_RECORD');
