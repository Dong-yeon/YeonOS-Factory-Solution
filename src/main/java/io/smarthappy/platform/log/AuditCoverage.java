package io.smarthappy.platform.log;

import java.util.Set;

/**
 * 변경 감사 트리거(hb_audit) 예외 표 — 운영 중 "기록 무결성 점검"(docs/52 Y1)이 쓰는 이름 목록.
 * 근거(왜 감사가 필요 없는가)는 시험 쪽 {@code RetentionTest.AUDIT_EXEMPT} 에 있고, 두 목록이 같은지 시험이 본다
 * ({@code IntegrityCheckTest}) — 새 예외는 두 곳에 함께 넣는다.
 */
public final class AuditCoverage {

    public static final Set<String> EXEMPT = Set.of(
            "sys_access_log", "sys_esign", "sys_audit_log", "sys_log_digest", "sys_numbering_seq", "sys_outbox", "sys_inbox",
            "apr_history", "sys_grid_state", "sys_usage_daily", "sys_import_row", "sal_forecast_rev", "sal_forecast_line",
            "prd_wo_param", "prd_wo_bom", "lot_genealogy", "prd_wo_proc_event", "prd_result", "prd_result_defect",
            "prd_param_actual", "qms_insp_meas", "qms_spc_event", "qms_spc_event_act_ocap", "qms_spc_event_act", "inv_trx",
            "inv_stock", "qms_spc_event_state", "qms_spc_unit", "cms_equip_state_log", "cms_counter_src", "cms_alarm_log",
            "cms_equip_conn_log", "cms_energy_reading", "cms_equip_count", "cms_collector_seq", "aps_plan_wo", "aps_plan_op", "aps_mps_row",
            "prd_wo_proc_close", "prd_rework", "pop_approval", "prd_receipt", "prd_erp_export", "plm_eco_lot", "sys_notify",
            "sys_notice_read", "sys_user_mfa_recovery", "aps_plan_mat", "aps_plan_supply",
            // 시스템관리 Y1 (docs/52) — 점검 기록 자체(추가만)
            "sys_integrity_run",
            // 장비 연동 E0 (docs/65 16장)
            "sys_inbound_msg", "cms_equip_cmd", "cms_equip_cmd_step", "cms_equip_link_log", "cms_equip_consumable", "cms_edge_session",
            // 설비 이벤트 수신 멱등 기록 (docs/70 W2 — 추가 + 보존 정리만)
            "cms_equip_inbox",
            // 장비 연동 E1·E2 라벨 (docs/65 18·19장)
            "wms_printer_status", "wms_print_job_event", "wms_print_dispatch",
            // 장비 연동 E3 오토라벨러 (docs/65 21장)
            "wms_labeler_event", "wms_labeler_dev_log",
            // 장비 연동 E4 자동창고 (docs/65 23장)
            "wms_asrs_event", "wms_asrs_recon_part",
            // APS 10단계 MRP (docs/38 19.7) — 계산 결과 사본(추가만, 계산한 사람·시각은 머리 aps_mrp 의 감사)
            "aps_mrp_item", "aps_mrp_elem", "aps_mrp_peg", "aps_mrp_exc",
            // MES 장비 시리얼 구성 (docs/77) — 장착 원장(추가만)
            "prd_serial_part");

    private AuditCoverage() {
    }
}
