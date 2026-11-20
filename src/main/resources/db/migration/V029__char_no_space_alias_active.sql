-- V029 특성 번호 공간·동의어 유일 범위 (6단계 QA 결함 #4·#7 — docs/qa/char-6.md, docs/21 검토)
--
-- #4 특성 번호: V024 는 "품목이 있으면 품목 안 유일, 없으면 공정 안 유일" 이라
--    품목을 붙인 공정 특성이 그 품목의 도면 풍선 번호(제품 특성)·다른 공정 특성과 같은 번호 공간을 썼고,
--    반대로 품목 없는 공정 특성과는 같은 공정에 같은 번호가 둘 생겼다.
--    → 유형으로 나눈다: 제품 특성 = 품목 안 유일(도면 풍선 번호), 공정 특성 = **공정 안 유일**(품목 유무와 관계없이).
--      한 공정의 "#2" 는 하나 — 품목별 조건이 다르면 번호를 달리 붙인다(관리계획서 공정 단위 번호).
-- #7 동의어: 사용 중지한 동의어도 유일 인덱스에 남아, 잘못된 용어에 붙인 말을 바른 용어에 다시 쓸 수 없었다
--    (삭제·용어 변경은 막혀 있다). → **사용 중인 동의어만** 유일. 옛 행을 다시 사용하려 하면 그때 중복.

DROP INDEX uq_qms_char_item_no;
DROP INDEX uq_qms_char_proc_no;
CREATE UNIQUE INDEX uq_qms_char_product_no ON qms_char (comp_cd, item_id, char_no) WHERE char_type = 'PRODUCT';
CREATE UNIQUE INDEX uq_qms_char_process_no ON qms_char (comp_cd, proc_id, char_no) WHERE char_type = 'PROCESS';

DROP INDEX uq_mst_term_alias;
CREATE UNIQUE INDEX uq_mst_term_alias ON mst_term_alias (comp_cd, lower(btrim(alias))) WHERE use_yn = 'Y';
