-- =====================================================================
-- CMS 1부 C4 — 설비 예비품 연결·작업지시 사용 예비품 (docs/33 4.5, 결정 D9)
--
-- - 예비품 마스터는 따로 두지 않는다 — 품목 마스터(mst_item) 그대로. 수량·안전재고·차감은 P4 WMS
-- - cms_equip_spare: 설비 또는 설비 클래스 × 품목, 설치 수량·핵심 예비품. 사용 중지 = 새 참조만 차단
-- - cms_mwo_part: 작업지시가 쓴 품목·수량(품목 기본 단위 스냅샷). 작업지시가 OPEN·WIP 일 때만 추가·삭제, 수정 없음
--   → P4 재고 원장의 출고 소스(docs/33 6장)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 설비 예비품 연결
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_spare (
    spare_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    equip_id        bigint,
    equip_class_id  bigint,
    item_id         bigint         NOT NULL,
    install_qty     numeric(18,6)  NOT NULL,
    key_spare_yn    char(1)        NOT NULL DEFAULT 'N',
    use_yn          char(1)        NOT NULL DEFAULT 'Y',
    remark          varchar(500),
    row_ver         integer        NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    created_by      varchar(50)    NOT NULL,
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    updated_by      varchar(50)    NOT NULL,
    CONSTRAINT pk_cms_equip_spare PRIMARY KEY (spare_id),
    CONSTRAINT fk_cms_equip_spare_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_equip_spare_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_equip_spare_class FOREIGN KEY (equip_class_id) REFERENCES mst_equip_class (class_id),
    CONSTRAINT fk_cms_equip_spare_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    -- 설비 또는 클래스 중 정확히 하나
    CONSTRAINT ck_cms_equip_spare_target CHECK ((equip_id IS NULL) <> (equip_class_id IS NULL)),
    CONSTRAINT ck_cms_equip_spare_qty CHECK (install_qty > 0),
    CONSTRAINT ck_cms_equip_spare_key CHECK (key_spare_yn IN ('Y', 'N')),
    CONSTRAINT ck_cms_equip_spare_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE UNIQUE INDEX uq_cms_equip_spare ON cms_equip_spare (comp_cd, equip_id, equip_class_id, item_id) NULLS NOT DISTINCT;
CREATE INDEX ix_cms_equip_spare_item ON cms_equip_spare (comp_cd, item_id);
COMMENT ON TABLE cms_equip_spare IS '설비(또는 설비 클래스) 예비품 (docs/33 C4, D9) — 품목 마스터 재사용, 수량·재고는 WMS';
COMMENT ON COLUMN cms_equip_spare.install_qty IS '설비 한 대에 들어가는 수량(품목 기본 단위)';

CREATE FUNCTION hb_guard_cms_equip_spare() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.comp_cd <> OLD.comp_cd OR NEW.equip_id IS DISTINCT FROM OLD.equip_id
                             OR NEW.equip_class_id IS DISTINCT FROM OLD.equip_class_id OR NEW.item_id <> OLD.item_id) THEN
        RAISE EXCEPTION 'spare target and item are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_equip_spare_fixed';
    END IF;
    -- 새 참조(등록·다시 사용): 같은 회사·사용 중인 설비(클래스)·품목만. 사용 중지는 이미 있는 연결을 그대로 둔다
    IF TG_OP = 'INSERT' OR (NEW.use_yn = 'Y' AND OLD.use_yn = 'N') THEN
        IF NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id
                AND e.comp_cd = NEW.comp_cd AND e.use_yn = 'Y') THEN
            RAISE EXCEPTION 'equipment % is not an active equipment of company %', NEW.equip_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_spare_equip_comp';
        END IF;
        IF NEW.equip_class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip_class c WHERE c.class_id = NEW.equip_class_id
                AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y') THEN
            RAISE EXCEPTION 'equipment class % is not an active class of company %', NEW.equip_class_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_spare_class_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd AND i.use_yn = 'Y') THEN
            RAISE EXCEPTION 'item % is not an active item of company %', NEW.item_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_spare_item_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_spare_guard BEFORE INSERT OR UPDATE ON cms_equip_spare FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_equip_spare();
CREATE TRIGGER tg_cms_equip_spare_no_delete BEFORE DELETE ON cms_equip_spare FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 작업지시 사용 예비품
-- ---------------------------------------------------------------------
CREATE TABLE cms_mwo_part (
    part_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    mwo_id      bigint         NOT NULL,
    item_id     bigint         NOT NULL,
    qty         numeric(18,6)  NOT NULL,
    unit_cd     varchar(30)    NOT NULL,
    remark      varchar(500),
    row_ver     integer        NOT NULL DEFAULT 0,
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  varchar(50)    NOT NULL,
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    updated_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_cms_mwo_part PRIMARY KEY (part_id),
    CONSTRAINT fk_cms_mwo_part_mwo FOREIGN KEY (mwo_id) REFERENCES cms_mwo (mwo_id),
    CONSTRAINT fk_cms_mwo_part_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT ck_cms_mwo_part_qty CHECK (qty > 0)
);
CREATE INDEX ix_cms_mwo_part_mwo ON cms_mwo_part (comp_cd, mwo_id);
CREATE INDEX ix_cms_mwo_part_item ON cms_mwo_part (comp_cd, item_id);
COMMENT ON TABLE cms_mwo_part IS '작업지시 사용 예비품 — 작업지시가 OPEN·WIP 일 때만 추가·삭제(수정 없음), 완료 뒤 불변. WMS 출고 소스';
COMMENT ON COLUMN cms_mwo_part.unit_cd IS '쓸 때의 품목 기본 단위(스냅샷) — 뒤에 품목 단위가 바뀌어도 기록의 뜻은 그대로';

CREATE FUNCTION hb_guard_cms_mwo_part() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r record;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'part rows are not updated (delete and add)' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_part_no_update';
    END IF;
    r := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    -- 작업지시 행을 잠가 완료와 줄 세운다(공수와 같은 규칙)
    PERFORM 1 FROM cms_mwo m WHERE m.mwo_id = r.mwo_id AND m.comp_cd = r.comp_cd AND m.status IN ('OPEN', 'WIP') FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'parts can change only while the work order is OPEN or WIP' USING ERRCODE = '55000',
            CONSTRAINT = 'ck_cms_mwo_part_status';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd
                                           AND i.use_yn = 'Y' AND i.unit_cd = NEW.unit_cd) THEN
        RAISE EXCEPTION 'item % is not an active item of company % in unit %', NEW.item_id, NEW.comp_cd, NEW.unit_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_part_item_comp';
    END IF;
    RETURN r;
END
$$;
CREATE TRIGGER tg_cms_mwo_part_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_mwo_part
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_mwo_part();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_equip_spare FOR EACH ROW EXECUTE FUNCTION hb_audit('spare_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_mwo_part FOR EACH ROW EXECUTE FUNCTION hb_audit('part_id', '', '');

ALTER TABLE cms_equip_spare ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_spare FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_spare USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_mwo_part ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_mwo_part FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_mwo_part USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON cms_equip_spare FROM ${app_role};
REVOKE TRUNCATE ON cms_mwo_part FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-spare', 'prog.cmsSpare', 'cms/spare-mgt', 'CMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 570);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('cms_equip_spare', 'MASTER'), ('cms_mwo_part', 'MAINT_RECORD');
