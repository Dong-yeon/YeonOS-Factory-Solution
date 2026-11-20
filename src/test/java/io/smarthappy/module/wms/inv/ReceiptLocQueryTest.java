package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;

import io.smarthappy.module.qms.link.InspPlanLookup;
import io.smarthappy.module.wms.link.ReceiptLocQuery;
import io.smarthappy.module.wms.link.ReceiptLocQuery.ReceiptLoc;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 입고 가능 위치 계약 (docs/44 S2, MES 요구 2026-10-24) — <b>목록에서 받을 수 있다고 한 칸 = 생산 입고 게시가 받는 칸</b>, 상태도 같다.
 * 공장 안 모든 창고·로케이션에 실제로 게시해 본다(정상·기본 상태 QC 칸·재고 안 둠·사용 중지·스위치 Y QC 고정·NG LOT 이어받기) + MANDATORY + A사로 B사
 */
class ReceiptLocQueryTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ReceiptLocQuery query;

    @Autowired
    ProductionReceiptPosting posting;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    InspPlanLookup plans;

    String comp;
    long fgl;
    long wo;
    long lot;
    int seq;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("RL");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        long wh = node("P1", "WH", null, "WH");
        node("P1", "LOC", wh, "A-01");
        long qc = node("P1", "LOC", wh, "QC-01");
        long ns = node("P1", "LOC", wh, "NS-01");
        long off = node("P1", "LOC", wh, "OFF-01");
        long stg = node("P1", "LOC", wh, "STG-01");
        long area = node("P1", "AREA", null, "AR-1");                      // 창고가 아닌 노드 — 목록에 없고 게시도 거부
        node("P1", "LINE", area, "LN-1");
        long wh2 = node("P2", "WH", null, "WH2");
        node("P2", "LOC", wh2, "B-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, default_status, created_by, updated_by) values (?, ?, 'QC', 'QC', 't', 't')",
                qc, comp);
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, stock_yn, created_by, updated_by) values (?, ?, 'STORAGE', 'N', 't', 't')",
                ns, comp);
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", off);
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')",
                stg, comp);
        fgl = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FGL', 'FGL', 'FG', 'MAKE', 'EA', 'Y', 't', 't') returning item_id""", Long.class, comp);
        wo = owner("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, status, released_at, "
                + "created_by, updated_by) values (?, 'WO-1', 'P1', ?, 100, 'EA', " + TestAccounts.COMP_TODAY + ", " + TestAccounts.COMP_TODAY
                + ", 0, 'IN_PROGRESS', now(), 't', 't') returning wo_id", comp, fgl);
        lot = owner("insert into lot_master (comp_cd, lot_no, item_id, origin, wo_id, qty_created, unit_cd, occurred_at, created_by, updated_by) "
                + "values (?, 'L-1', ?, 'PRODUCED', ?, 100, 'EA', now(), 't', 't') returning lot_id", comp, fgl, wo);
        when(plans.lines(any(Long.class), isNull(), any(), any())).thenReturn(List.of());
    }

    long node(String plant, String type, Long parent, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, plant, parent, type, cd, cd);
    }

    static long owner(String sql, Object... args) throws Exception {
        return owner(true, sql, args);
    }

    /** 소유자 연결 — replica = 트리거 끔(MES 판정 없이 입고 행만), false = MES 입고 행 트리거까지 켬(실제 경로) */
    static long owner(boolean replica, String sql, Object... args) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            if (replica) {
                try (Statement st = c.createStatement()) {
                    st.execute("set session_replication_role = replica");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    List<ReceiptLoc> list(String c, String plant, long item, Long lotId) {
        return runner.call(c, () -> query.productionReceipt(plant, item, lotId));
    }

    /** 그 칸에 생산 입고 1건을 게시해 본다 → 들어간 상태, 거부면 "!" + 칸 오류 코드 */
    String postAt(long nodeId) throws Exception {
        return postAt(nodeId, true);
    }

    /** guarded = MES 입고 행 트리거(ck_prd_receipt_node — 이 공장·WH/LOC·사용 중)까지 거친다. 거기서 막히면 "!MES" */
    String postAt(long nodeId, boolean replica) throws Exception {
        long r;
        try {
            r = owner(replica, "insert into prd_receipt (comp_cd, plant_cd, wo_id, item_id, lot_id, node_id, qty, unit_cd, client_req_id, occurred_at, "
                + "entry_method, recorded_by) values (?, 'P1', ?, ?, ?, ?, 1, 'EA', ?, now(), 'SCAN', 'mes-user') returning receipt_id",
                comp, wo, fgl, lot, nodeId, "rl-" + (++seq) + "-" + System.nanoTime());
        } catch (java.sql.SQLException e) {
            return "!MES " + e.getMessage().lines().findFirst().orElse("");
        }
        long rid = r;
        Throwable t = catchThrowable(() -> runner.callAs(comp, "mes-user", () -> posting.post(rid)));
        if (t != null) {
            return "!" + (t instanceof BusinessException b && !b.fieldErrors().isEmpty() ? b.fieldErrors().get(0).code() : t.getClass().getSimpleName());
        }
        return accounts.query(comp, "select t.stock_status from inv_trx t where t.src_type = 'PRD_RECEIPT' and t.src_id = ?", String.class, rid);
    }

    /**
     * 목록에 없는 이 회사의 모든 노드(다른 공장·창고 아닌 노드) — 실제 경로(MES 입고 행 트리거 → WMS 게시)에서 받히지 않아야 한다.
     * WMS 게시만 보면 다른 공장 창고도 받는다(원장 공장 = 위치의 공장) — 입고 행의 공장·위치 일치는 MES 트리거 ck_prd_receipt_node 가 막는다
     */
    void unlistedRejected(List<ReceiptLoc> locs) throws Exception {
        List<Long> listed = locs.stream().map(ReceiptLoc::nodeId).toList();
        List<Long> all = runner.call(comp, () -> jdbc.queryForList("select node_id from mst_equip_hier order by node_id", Long.class));
        int n = 0;
        for (long id : all) {
            if (!listed.contains(id)) {
                String what = accounts.query(comp, "select plant_cd || '/' || node_type || '/' || node_cd from mst_equip_hier where node_id = ?", String.class, id);
                assertThat(postAt(id, false)).as("목록 밖 노드 " + what).startsWith("!");
                n++;
            }
        }
        assertThat(n).as("목록 밖 노드(AR-1·LN-1·WH2·B-01)").isEqualTo(4);
    }

    /** 목록의 모든 칸에 게시해 보고 "받을 수 있음 ⇔ 게시 성공, 예측 상태 = 들어간 상태" */
    void sameAsPost(List<ReceiptLoc> locs) throws Exception {
        for (ReceiptLoc l : locs) {
            String got = postAt(l.nodeId());
            if (l.ok()) {
                assertThat(got).as(l.nodeCd()).isEqualTo(l.status());
            } else {
                assertThat(got).as(l.nodeCd()).isEqualTo("!" + l.reasonCd());          // 같은 판정 — 사유 코드까지 같다
            }
        }
    }

    @Test
    void 목록의_받을_수_있는_칸은_게시가_받는_칸이고_상태도_같다() throws Exception {
        List<ReceiptLoc> locs = list(comp, "P1", fgl, lot);
        Map<String, String> view = locs.stream().collect(Collectors.toMap(ReceiptLoc::nodeCd, l -> l.ok() ? l.status() : "!" + l.reasonCd()));
        assertThat(view).containsExactlyInAnyOrderEntriesOf(Map.of("WH", "AVAILABLE", "A-01", "AVAILABLE", "QC-01", "QC",
                "NS-01", "!STOCK_NOT_ALLOWED", "OFF-01", "!NODE_INACTIVE", "STG-01", "!STAGING_NOT_ALLOWED"));                      // 다른 공장·창고 아닌 노드는 없음
        sameAsPost(locs);
        unlistedRejected(locs);
    }

    @Test
    void 스위치_Y면_출하검사_대상은_QC_NG_판정_LOT은_BLOCKED_그대로_게시와_같다() throws Exception {
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'P1', 'WMS_QUALITY_LINK', 'Y', 't', 't')", comp);
        when(plans.lines(eq(fgl), isNull(), eq("FINAL"), any())).thenReturn(List.of(new InspPlanLookup.PlanLine(1L, "A", 1, 1L, "C1", "외관",
                null, "ATTRIBUTE", null, null, null, null, null, null, "VISUAL", null, "EVERY_LOT", null, null)));
        List<ReceiptLoc> qc = list(comp, "P1", fgl, lot);
        assertThat(qc.stream().filter(ReceiptLoc::ok).map(ReceiptLoc::status)).containsOnly("QC");
        sameAsPost(qc);

        owner("""
                insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, insp_plan_id, lot_qty, status, result, occurred_at,
                                      judged_at, judged_by, created_by, updated_by)
                values (?, 'QI-NG', 'FINAL', ?, ?, 0, 10, 'JUDGED', 'NG', now(), now(), 'qa1', 'qa1', 'qa1') returning insp_id""", comp, lot, fgl);
        List<ReceiptLoc> ng = list(comp, "P1", fgl, lot);
        assertThat(ng.stream().filter(ReceiptLoc::ok).map(ReceiptLoc::status)).containsOnly("BLOCKED");
        sameAsPost(ng);
        assertThat(list(comp, "P1", fgl, null).stream().filter(ReceiptLoc::ok).map(ReceiptLoc::status)).containsOnly("QC");   // LOT 없이 = 이어받기 안 봄
    }

    @Test
    void 트랜잭션_밖이면_거부_A사로_B사_품목과_공장은_빈_목록_없는_품목도_빈_목록() {
        assertThat(catchThrowable(() -> query.productionReceipt("P1", fgl, lot))).isInstanceOf(IllegalTransactionStateException.class);
        String other = accounts.newComp("RM");
        accounts.addPlant(other, "P1", "B사 1공장");
        assertThat(list(other, "P1", fgl, lot)).as("B사 범위로 A사 품목").isEmpty();
        assertThat(list(comp, "P9", fgl, lot)).isEmpty();
        assertThat(list(comp, "P1", 999_999_999L, null)).isEmpty();
        assertThat(ReceiptLocQuery.NONE.productionReceipt("P1", fgl, lot)).isEmpty();
    }
}
