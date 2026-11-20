-- =====================================================================
-- 조회 그리드 엑셀 전 화면 (docs/73 — 임시 번호, 병합 때 이름만 바뀐다). 표 변경 없음
-- - hb.grid 가 있는 사무 화면 가운데 엑셀이 없던 54개 화면에 EXCEL 동작(grid 메뉴 "엑셀", POST …/export — 공통 도우미 GridExcel)
-- - 이미 EXCEL 이 있는 화면(상세 문서 내보내기 등)은 그대로(조건으로 건너뜀)
-- - 권한 행(sys_auth_prog)은 건드리지 않는다 — 지금까지의 엑셀 묶음(V152·V177 등)과 같이 회사 관리자가 권한그룹 화면에서 준다
-- =====================================================================

UPDATE sys_prog SET actions = actions || ARRAY['EXCEL']::varchar(20)[]
 WHERE prog_cd IN (
        -- 기준정보
        'mst-class-prop', 'mst-plant', 'mst-ext-system', 'mst-defect', 'mst-term', 'mst-proc', 'mst-work-cal', 'mst-qual',
        'mst-shift', 'mst-worker',
        -- 생산·영업
        'sal-order', 'sal-forecast',
        -- 품질
        'qms-csr', 'qms-change', 'qms-contain', 'qms-doc', 'qms-insp', 'qms-ncr', 'qms-ocap', 'qms-spc',
        -- 창고
        'wms-count', 'wms-period', 'wms-label', 'wms-print-job', 'wms-labeler', 'wms-loc-check', 'wms-ship', 'wms-ship-note',
        -- 구매
        'srm-ack', 'srm-po-status', 'srm-asn', 'srm-claim', 'srm-return', 'srm-eval', 'srm-eval-tmpl', 'srm-notify', 'srm-po',
        'srm-pr', 'srm-supplier',
        -- 계획
        'aps-plan', 'aps-item-param',
        -- 설비
        'cms-risk',
        -- 프로젝트
        'pms-project', 'pms-template',
        -- 시스템관리
        'sys-auth-group', 'sys-feedback', 'sys-access-log', 'sys-audit-log', 'sys-notice', 'sys-outbox', 'sys-code',
        'sys-numbering', 'sys-policy', 'sys-user')
   AND NOT ('EXCEL' = ANY (actions));
