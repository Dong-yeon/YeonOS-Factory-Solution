-- =====================================================================
-- MES 공장 휴일 달력 (docs/25 4.15, docs/35 A19, docs/38 A20) — 근무조(V030) 위의 휴일·특근
-- - 주간 쉬는 날 = 정책 WEEKLY_OFF(공장 범위, 기본 NONE — 지금 동작 그대로)
-- - 날짜별 예외 = mst_work_cal: 회사 공통(plant NULL) / 공장 하루 / 공장 근무조. 위가 이긴다: 근무조 > 공장 하루 > 회사 > 주간 > 기본
-- - 날짜는 작업일(WorkDayResolver 와 같은 뜻). 원장·작업일 판정은 바꾸지 않는다(D24 — 계획·주기·지연 계산용)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('WEEKLY_OFF', 'ENUM', 'NONE', ARRAY['NONE','SUN','SAT_SUN'], NULL, NULL, 'Y', 'policy.WEEKLY_OFF');

CREATE TABLE mst_work_cal (
    cal_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    plant_cd    varchar(20),
    cal_date    date          NOT NULL,
    shift_cd    varchar(10),
    day_type    varchar(4)    NOT NULL,
    reason      varchar(200)  NOT NULL,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_work_cal PRIMARY KEY (cal_id),
    CONSTRAINT fk_mst_work_cal_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_work_cal_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT ck_mst_work_cal_type CHECK (day_type IN ('OFF', 'WORK')),
    -- 근무조 예외는 공장 행에만(근무조는 공장 소속)
    CONSTRAINT ck_mst_work_cal_shift_plant CHECK (shift_cd IS NULL OR plant_cd IS NOT NULL),
    CONSTRAINT ck_mst_work_cal_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_mst_work_cal_year CHECK (cal_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT ck_mst_work_cal_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE mst_work_cal IS '공장 휴일 달력 예외 (docs/25 4.15). plant NULL = 회사 공통, shift NULL = 하루 전체. 날짜 = 작업일';

-- 사용 중 행 중 (회사, 공장, 날짜, 근무조) 하나 — NULL 칸은 빈 글자로 묶는다. 범위 조회(달·기간)도 이 인덱스
CREATE UNIQUE INDEX uq_mst_work_cal ON mst_work_cal (comp_cd, cal_date, coalesce(plant_cd, ''), coalesce(shift_cd, ''))
    WHERE use_yn = 'Y';

-- FK 는 RLS 를 무시한다 → 공장은 위 FK(comp_cd 포함)로 같은 회사, 근무조는 같은 회사·공장의 근무조인지 여기서.
-- 근무조를 다른 코드로 바꾸지는 않는다(mst_shift.shift_cd 는 화면에서 고정) — 사용 중지된 근무조의 예외는 남아도 읽기에서 빠진다
CREATE FUNCTION hb_guard_work_cal() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- 공장 없는 근무조는 CHECK ck_mst_work_cal_shift_plant 가 알린다(BEFORE 트리거가 CHECK 보다 먼저 돌므로 여기서 건너뛴다)
    IF NEW.shift_cd IS NOT NULL AND NEW.plant_cd IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.shift_cd IS DISTINCT FROM OLD.shift_cd
                                     OR NEW.plant_cd IS DISTINCT FROM OLD.plant_cd)
       AND NOT EXISTS (SELECT 1 FROM mst_shift s
                        WHERE s.comp_cd = NEW.comp_cd AND s.plant_cd = NEW.plant_cd AND s.shift_cd = NEW.shift_cd) THEN
        RAISE EXCEPTION 'shift % is not of plant %', NEW.shift_cd, NEW.plant_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_work_cal_shift';
    END IF;
    -- 무엇에 대한 예외인지(공장·날짜·근무조)는 고정 — 바꾸려면 사용 중지하고 새로
    IF TG_OP = 'UPDATE' AND (NEW.plant_cd IS DISTINCT FROM OLD.plant_cd OR NEW.cal_date <> OLD.cal_date
                             OR NEW.shift_cd IS DISTINCT FROM OLD.shift_cd) THEN
        RAISE EXCEPTION 'work calendar key is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_work_cal_key_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_work_cal_guard BEFORE INSERT OR UPDATE ON mst_work_cal FOR EACH ROW EXECUTE FUNCTION hb_guard_work_cal();
CREATE TRIGGER tg_mst_work_cal_no_delete BEFORE DELETE ON mst_work_cal FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_work_cal FOR EACH ROW EXECUTE FUNCTION hb_audit('cal_id', '', '');

ALTER TABLE mst_work_cal ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_work_cal FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_work_cal USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON mst_work_cal FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('mst-work-cal', 'prog.mstWorkCal', 'master/work-cal-mgt', 'MST', ARRAY['SEARCH','SAVE']::varchar(20)[], 183);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('mst_work_cal', 'MASTER');
