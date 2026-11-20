package io.smarthappy.platform.grid;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * "변경 이력 보기" (그리드 컨텍스트 메뉴, docs/09 4.4) — sys_audit_log 를 테이블·행 키로 조회.
 * 어떤 테이블을 볼 수 있는지와 그 권한 화면은 아래 목록으로만 (임의 테이블 조회 금지). 권한 = 그 화면 SEARCH.
 * 모듈이 화면을 추가하면 이 목록에 등록한다.
 */
@Service
public class AuditViewService {

    /** 테이블 → 그 데이터를 다루는 화면 */
    static final Map<String, String> TABLE_PROG = Map.ofEntries(
            Map.entry("mst_cust", "mst-cust"),
            Map.entry("sys_user", "sys-user"),
            Map.entry("sys_auth_group", "sys-auth-group"),
            Map.entry("sys_menu", "sys-menu"),
            Map.entry("mst_cust_contact", "mst-cust"),
            Map.entry("mst_item", "mst-item"),
            Map.entry("mst_item_price_hist", "mst-item"),
            Map.entry("mst_unit_conv", "mst-item"),
            Map.entry("mst_bom", "mst-bom"),
            Map.entry("mst_bom_line", "mst-bom"),
            Map.entry("mst_equip_hier", "mst-equip-hier"),
            Map.entry("mst_equip", "mst-equip"),
            Map.entry("mst_phys_asset", "mst-asset"),
            Map.entry("mst_proc", "mst-proc"),
            Map.entry("mst_routing", "mst-routing"),
            Map.entry("mst_routing_step", "mst-routing"),
            Map.entry("mst_routing_param", "mst-routing"),
            Map.entry("qms_char", "mst-char"),
            Map.entry("qms_defect", "mst-defect"),
            Map.entry("mst_term_alias", "mst-term"),
            Map.entry("mst_ext_system", "mst-ext-system"),
            Map.entry("if_code_map", "mst-ext-system"),
            Map.entry("mst_ext_id_map", "mst-item"),
            Map.entry("mst_item_class", "mst-class-prop"),
            Map.entry("mst_class_prop", "mst-class-prop"),
            Map.entry("mst_shift", "mst-shift"),
            Map.entry("mst_qual", "mst-qual"),
            Map.entry("mst_proc_qual_req", "mst-qual"),
            Map.entry("mst_worker", "mst-worker"),
            Map.entry("mst_worker_qual", "mst-worker"),
            Map.entry("sal_order", "sal-order"),
            Map.entry("sal_order_line", "sal-order"),
            Map.entry("prd_wo", "prd-wo"),
            Map.entry("prd_wo_proc", "prd-wo"),
            Map.entry("wms_loc_attr", "wms-loc"),
            Map.entry("wms_printer", "wms-label"),
            Map.entry("wms_label_tpl", "wms-label"),
            Map.entry("wms_print_job", "wms-print-job"),
            Map.entry("cms_fail_code", "cms-fail-code"),
            Map.entry("cms_equip_profile", "cms-equip-profile"),
            Map.entry("cms_mwo", "cms-mwo"),
            Map.entry("cms_mwo_labor", "cms-mwo"),
            Map.entry("cms_chk_tmpl", "cms-chk-tmpl"),
            Map.entry("cms_chk_tmpl_item", "cms-chk-tmpl"),
            Map.entry("cms_pm_plan", "cms-pm-plan"),
            Map.entry("cms_chk_run", "cms-chk-run"),
            Map.entry("cms_chk_result", "cms-chk-run"),
            Map.entry("cms_counter", "cms-counter"),
            Map.entry("cms_counter_reading", "cms-counter"),
            Map.entry("cms_equip_spare", "cms-spare"),
            Map.entry("cms_mwo_part", "cms-mwo"),
            Map.entry("cms_equip_sec", "cms-equip-sec"),
            Map.entry("cms_risk_assess", "cms-risk"),
            Map.entry("cms_risk_item", "cms-risk"),
            Map.entry("cms_risk_member", "cms-risk"),
            Map.entry("cms_state_reason", "cms-state-reason"),
            Map.entry("cms_state_note", "cms-state-review"),
            Map.entry("cms_mwo_down", "cms-state-review"),
            Map.entry("cms_alarm_def", "cms-alarm-def"),
            Map.entry("srm_supplier", "srm-supplier"),
            Map.entry("srm_supplier_cert", "srm-supplier"),
            Map.entry("srm_source", "srm-supplier"),
            Map.entry("srm_price", "srm-supplier"),
            Map.entry("srm_pr", "srm-pr"),
            Map.entry("srm_pr_line", "srm-pr"),
            Map.entry("srm_po", "srm-po"),
            Map.entry("srm_po_line", "srm-po"),
            Map.entry("qms_sampling_plan", "qms-sampling"),
            Map.entry("qms_aql_table", "qms-sampling"),
            Map.entry("qms_aql_plan_row", "qms-sampling"),
            Map.entry("qms_insp_plan", "qms-insp-plan"),
            Map.entry("qms_insp_plan_line", "qms-insp-plan"),
            Map.entry("qms_gauge_calib", "qms-gauge-calib"),
            Map.entry("qms_msa_study", "qms-msa"),
            Map.entry("qms_msa_data", "qms-msa"),
            Map.entry("mst_doc", "qms-doc"),
            Map.entry("mst_doc_target", "qms-doc"),
            Map.entry("mst_doc_rev", "qms-doc"),
            Map.entry("qms_capa", "qms-capa"),
            Map.entry("qms_capa_spread", "qms-capa"),
            Map.entry("qms_cust_csr", "qms-csr"),
            Map.entry("qms_cust_csr_target", "qms-csr"),
            Map.entry("qms_ocap_item", "qms-ocap"),
            Map.entry("qms_copq_cost", "qms-copq"),
            Map.entry("qms_change", "qms-change"),
            Map.entry("qms_change_char", "qms-change"),
            Map.entry("qms_insp", "qms-insp"),
            Map.entry("qms_insp_line", "qms-insp"),
            Map.entry("qms_setup_verif", "qms-setup"),
            Map.entry("qms_spc_limit", "qms-spc"),
            Map.entry("qms_ncr", "qms-ncr"),
            Map.entry("qms_ncr_disp", "qms-ncr"),
            Map.entry("qms_contain", "qms-contain"),
            Map.entry("qms_contain_lot", "qms-contain"),
            // PLM (docs/39)
            Map.entry("plm_ecr", "plm-ecr"),
            Map.entry("plm_eco", "plm-eco"),
            Map.entry("plm_eco_target", "plm-eco"),
            Map.entry("plm_ppap", "plm-eco"),
            Map.entry("plm_ppap_elem", "plm-eco"),
            Map.entry("plm_pcn", "plm-eco"),
            // 시스템관리 1·2·2b·3단계 (docs/36 10장·12장) — 권한 변경 모아 보기(sys_user_auth·sys_auth_prog)도 여기서 화면 권한을 받는다.
            // sys_comp 는 넣지 않는다: 운영자 콘솔 생성 행이 섞이고, 회사 정보 화면은 hb.grid 이력 메뉴가 없다(10장)
            Map.entry("sys_user_auth", "sys-user"),
            Map.entry("sys_auth_prog", "sys-auth-group"),
            Map.entry("sys_dept", "sys-dept"),
            Map.entry("sys_policy", "sys-policy"),
            Map.entry("sys_comp_code", "sys-code"),
            Map.entry("sys_numbering_rule", "sys-numbering"),
            Map.entry("sys_notice", "sys-notice"),
            Map.entry("sys_feedback", "sys-feedback"));

    /** 표 → 화면(없으면 null) — 감사 모아 보기(sys-audit-log)가 표마다 볼 권한을 정할 때 쓴다 */
    public static String progOf(String table) {
        return table == null ? null : TABLE_PROG.get(table);
    }

    /** 화면 대응이 있는 표 전부 */
    public static java.util.Set<String> tables() {
        return TABLE_PROG.keySet();
    }

    public record AuditEntry(String action, String actor, OffsetDateTime occurredAt, String before, String after) {
    }

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public AuditViewService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> history(String table, String rowKey) {
        String prog = table == null ? null : TABLE_PROG.get(table);
        if (prog == null || rowKey == null || !rowKey.matches("^[A-Za-z0-9_.:|-]{1,200}$")) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (!permissions.actions(prog).contains(Action.SEARCH)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        return jdbc.query("""
                select action, actor, occurred_at, before_data::text, after_data::text
                  from sys_audit_log
                 where table_nm = ? and row_key = ?
                 order by audit_id desc
                 limit 200""",
                (rs, i) -> new AuditEntry(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class),
                        rs.getString(4), rs.getString(5)), table, rowKey);
    }
}
