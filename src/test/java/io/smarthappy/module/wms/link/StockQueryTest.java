package io.smarthappy.module.wms.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 재고 조회 계약 StockQuery.available (docs/30 13장, APS 자재 가용) — AVAILABLE 만·보류 LOT·SCRAP 위치(조상 포함) 제외,
 * 공장 전체 합, 다른 공장, 재고 없는 품목 0, A사로 B사, WMS 없는 설치본 NONE.
 */
@AutoConfigureMockMvc
class StockQueryTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<StockQuery> provider;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long a2;
    long scrapLoc;
    long lotItem;
    long plain;
    long empty;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("SQ");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-move", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long wh = node(null, "WH", "WH");
        a1 = node(wh, "LOC", "A-01");
        a2 = node(wh, "LOC", "A-02");
        // 폐기 창고 — 속성은 창고에만, 아래 로케이션도 빠져야 한다
        long scrapWh = node(null, "WH", "SCRAP");
        scrapLoc = node(scrapWh, "LOC", "S-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'SCRAP', 't', 't')",
                scrapWh, comp);
        lotItem = item("RM-L", "Y");
        plain = item("RM-P", "N");
        empty = item("RM-E", "N");
        owner("insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by) "
                + "select ?, x, ?, 'RECEIVED', 1000, 'EA', now(), 't', 't' from unnest(array['L-1', 'L-2']) x", comp, lotItem);
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    long item(String cd, String lotYn) {
        return accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', 'EA', ?, 't', 't') returning item_id""", Long.class, comp, cd, cd + " 이름", lotYn);
    }

    static void owner(String sql, Object... args) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            try (Statement st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.execute();
            }
        }
    }

    void adjust(long node, long item, String status, String lotNo, String delta) throws Exception {
        mvc.perform(post("/api/wms/move/adjust").with(csrf()).contentType(MediaType.APPLICATION_JSON).with(admin)
                .content("{\"plantCd\":\"P1\",\"nodeId\":" + node + ",\"itemId\":" + item + ",\"stockStatus\":\"" + status + "\",\"delta\":"
                        + delta + ",\"reasonCd\":\"FOUND\"" + (lotNo == null ? "" : ",\"lotNo\":\"" + lotNo + "\"") + "}"))
                .andExpect(status().isOk());
    }

    Map<Long, BigDecimal> available(String c, String plantCd, Long... itemIds) {
        StockQuery q = StockQuery.of(provider);
        return runner.call(c, () -> q.available(plantCd, Arrays.asList(itemIds)));
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 가용은_AVAILABLE_만_보류_LOT_과_SCRAP_위치를_빼고_공장_전체를_더한다() throws Exception {
        assertThat(StockQuery.of(provider)).isNotSameAs(StockQuery.NONE);
        adjust(a1, plain, "AVAILABLE", null, "7");
        adjust(a2, plain, "AVAILABLE", null, "2.5");
        adjust(a1, plain, "QC", null, "3");
        adjust(a1, plain, "HOLD", null, "4");
        adjust(scrapLoc, plain, "AVAILABLE", null, "100");
        adjust(a1, lotItem, "AVAILABLE", "L-1", "10");
        adjust(a2, lotItem, "AVAILABLE", "L-2", "5");
        // 보류 근거 + LOT 칸 — owner() 는 트리거를 끈 연결(replica)이라 둘 다 직접(docs/25 4.19)
        owner("with h as (insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '시험 보류', 't' "
                + "from lot_master where comp_cd = ? and lot_no = 'L-2' returning lot_id) "
                + "update lot_master set hold_yn = 'Y', hold_reason = '시험 보류' where lot_id in (select lot_id from h)", comp);

        Map<Long, BigDecimal> m = available(comp, "P1", plain, lotItem, empty);
        assertThat(m.keySet()).containsExactly(plain, lotItem, empty);
        assertThat(m.get(plain)).isEqualByComparingTo("9.5");
        assertThat(m.get(lotItem)).isEqualByComparingTo("10");
        assertThat(m.get(empty)).isEqualByComparingTo("0");

        // 보류 풀면 그 LOT 도 가용 — 그 순간의 투영을 읽는다
        owner("with h as (update lot_hold set released_at = now(), released_by = 't', release_reason = '시험 해제' where released_at is null "
                + "and lot_id = (select lot_id from lot_master where comp_cd = ? and lot_no = 'L-2') returning lot_id) "
                + "update lot_master set hold_yn = 'N', hold_reason = null where lot_id in (select lot_id from h)", comp);
        assertThat(available(comp, "P1", lotItem).get(lotItem)).isEqualByComparingTo("15");
    }

    @Test
    void 출고로_0_이_된_재고와_다른_공장은_0() throws Exception {
        adjust(a1, plain, "AVAILABLE", null, "3");
        adjust(a1, plain, "AVAILABLE", null, "-3");
        accounts.addPlant(comp, "P2", "2공장");
        assertThat(available(comp, "P1", plain).get(plain)).isEqualByComparingTo("0");
        adjust(a1, plain, "AVAILABLE", null, "4");
        assertThat(available(comp, "P2", plain).get(plain)).isEqualByComparingTo("0");
        assertThat(available(comp, "NOPE", plain).get(plain)).isEqualByComparingTo("0");
    }

    @Test
    void 빈_목록_null_중복() {
        StockQuery q = StockQuery.of(provider);
        assertThat(runner.call(comp, () -> q.available("P1", List.of()))).isEmpty();
        assertThat(runner.call(comp, () -> q.available("P1", null))).isEmpty();
        assertThat(available(comp, "P1", plain, null, plain).keySet()).containsExactly(plain);
        assertThat(available(comp, null, plain).get(plain)).isEqualByComparingTo("0");
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    /** docs/53 F2·F-a(2026-10-29) — 가용 정의 하나(AvailSql): 출하 대기(STAGING)·검사 용도(QC) 칸의 AVAILABLE 재고는 APS 가용에서 빠진다 */
    @Test
    void 출하_대기_칸과_검사_용도_칸의_AVAILABLE_재고는_가용이_아니다_A사로_B사도_0() throws Exception {
        long stg = node(a1, "LOC", "STG-01");
        long qcLoc = node(a1, "LOC", "QC-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't'), (?, ?, 'QC', 't', 't')",
                stg, comp, qcLoc, comp);
        adjust(a2, plain, "AVAILABLE", null, "2");
        adjust(stg, plain, "AVAILABLE", null, "5");
        adjust(qcLoc, plain, "AVAILABLE", null, "6");
        assertThat(available(comp, "P1", plain).get(plain)).isEqualByComparingTo("2");
        // 검사 용도 칸의 부모(A-01, 보관)에 둔 재고는 센다 — QC·STAGING 은 칸 자신만, SCRAP 만 조상까지
        adjust(a1, plain, "AVAILABLE", null, "1");
        assertThat(available(comp, "P1", plain).get(plain)).isEqualByComparingTo("3");
        String b = accounts.newComp("SQB");
        accounts.addPlant(b, "P1", "B 1공장");
        assertThat(available(b, "P1", plain).get(plain)).isEqualByComparingTo("0");
    }

    @Test
    void A사로_B사_품목_id_를_넣어도_0() throws Exception {
        adjust(a1, plain, "AVAILABLE", null, "7");
        String b = accounts.newComp("SQB");
        accounts.addPlant(b, "P1", "B 1공장");
        // B사 범위에서 A사 품목 id·같은 공장 코드 → RLS 로 보이지 않음
        assertThat(available(b, "P1", plain).get(plain)).isEqualByComparingTo("0");
        assertThat(available(comp, "P1", plain).get(plain)).isEqualByComparingTo("7");
    }

    @Test
    void WMS_없는_설치본은_NONE_으로_모두_0() {
        DefaultListableBeanFactory none = new DefaultListableBeanFactory();
        StockQuery q = StockQuery.of(none.getBeanProvider(StockQuery.class));
        assertThat(q).isSameAs(StockQuery.NONE);
        Map<Long, BigDecimal> m = q.available("P1", List.of(3L, 1L));
        assertThat(m.keySet()).containsExactly(3L, 1L);
        assertThat(m.values()).allMatch(v -> v.signum() == 0);
        assertThat(q.available("P1", null)).isEmpty();
    }
}
