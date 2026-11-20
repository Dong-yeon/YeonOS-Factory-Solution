-- =====================================================================
-- CMS C15 — 설비 예비품 엑셀(연결 표·사용 이력) (docs/33 13장 3번). 표 변경 없음
-- - cms-spare 화면에 EXCEL 동작
-- - 엑셀 칸 이름용 코드: 연결 단위(설비·클래스)·핵심 예비품 여부 — 이름 키는 화면 문구를 그대로 쓴다
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[] WHERE prog_cd = 'cms-spare';

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CMS_SPARE_KIND', 'code.grp.CMS_SPARE_KIND', 'N'),
    ('CMS_SPARE_KEY',  'code.grp.CMS_SPARE_KEY',  'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CMS_SPARE_KIND', 'EQUIP', 'cms.spare.kind.EQUIP', 1),
    ('CMS_SPARE_KIND', 'CLASS', 'cms.spare.kind.CLASS', 2),
    ('CMS_SPARE_KEY',  'Y',     'cms.spare.key.Y',      1),
    ('CMS_SPARE_KEY',  'N',     'cms.spare.key.N',      2);
