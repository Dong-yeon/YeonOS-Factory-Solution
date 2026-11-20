-- 초품·종품 나란히(docs/29_P3 27장): 같은 품목·공정의 FIRST_OFF·LAST_OFF 검사를 최근부터 찾는 부분 인덱스.
-- InspMapper.offCandidates 가 품목·공정으로 거르고 occurred_at desc 로 상한까지 읽는다 — 다른 유형(수입·공정·출하)이 많아도 훑지 않게.
CREATE INDEX ix_qms_insp_off ON qms_insp (comp_cd, item_id, proc_id, occurred_at DESC, insp_id DESC)
    WHERE insp_type IN ('FIRST_OFF', 'LAST_OFF');
