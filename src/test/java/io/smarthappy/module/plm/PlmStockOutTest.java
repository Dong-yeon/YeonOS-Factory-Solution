package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 적용 조건 "재고 소진" (docs/39 18장) — 소진 품목의 공장 가용 재고(WMS 계약 StockQuery)가 0 이어야 적용.
 * 재고는 WMS 조정 API 로 넣고 뺀다(PLM 은 inv_* 를 읽지 않는다).
 */
@AutoConfigureMockMvc
class PlmStockOutTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";
    private static final String APR = "/api/platform/approvals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long ownerId;
    long approverId;
    RequestPostProcessor owner;
    RequestPostProcessor approver;
    String today;
    long loc;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PSO");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_4M_REQUIRED', 'N', 't', 't')", comp);
        ownerId = accounts.newUser(comp, "eng1", PW);
        approverId = accounts.newUser(comp, "mgr1", PW);
        for (long u : new long[] {ownerId, approverId}) {
            accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        }
        // 재고 조정은 시험 준비용 — PLM 화면 사용자에게 WMS 권한이 없어도 소진 판정은 계약으로 읽는다
        accounts.grant(comp, ownerId, "WMS", "wms-move", "SEARCH", "SAVE");
        owner = accounts.as(comp, ownerId, "eng1");
        approver = accounts.as(comp, approverId, "mgr1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        seed(comp);
        long wh = node(comp, null, "WH", "WH1");
        loc = node(comp, wh, "LOC", "A-01");
    }

    /** FG(BOM A0 확정 + A1 작성 중: RM → RM2), RM·RM2 는 LOT 관리 안 함 */
    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        for (String i : new String[] {"FG", "RM", "RM2"}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    values (?, ?, ?, ?, ?, 'EA', 'N', 't', 't')""", c, i, i + " 품목", "FG".equals(i) ? "FG" : "RM", "FG".equals(i) ? "MAKE" : "BUY");
        }
        for (String rev : new String[] {"A0", "A1"}) {
            long b = accounts.query(c, """
                    insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                    values (?, (select item_id from mst_item where item_cd = 'FG'), ?,
                            case when ? = 'A0' then (now() at time zone 'Asia/Seoul')::date - 30 else (now() at time zone 'Asia/Seoul')::date + 1 end, 't', 't') returning bom_id""",
                    Long.class, c, rev, rev);
            accounts.exec(c, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                    values (?, ?, 10, (select item_id from mst_item where item_cd = ?), 2, 'EA', 'NORMAL', 'MANUAL', 't', 't')""",
                    c, b, "A0".equals(rev) ? "RM" : "RM2");
            if ("A0".equals(rev)) {
                accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", b);
            }
        }
    }

    private long node(String c, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, parent, type, cd, cd + " 창고");
    }

    // ---------- 도우미

    private long id(String sql, Object... args) {
        return accounts.query(comp, sql, Long.class, args);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private int ecoVer(long ecoId) {
        return accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, ecoId);
    }

    private void adjust(String delta) throws Exception {
        mvc.perform(json(post("/api/wms/move/adjust"), "{\"plantCd\":\"P1\",\"nodeId\":" + loc + ",\"itemId\":"
                + id("select item_id from mst_item where item_cd = 'RM'") + ",\"stockStatus\":\"AVAILABLE\",\"delta\":" + delta
                + ",\"reasonCd\":\"FOUND\"}").with(owner)).andExpect(status().isOk());
    }

    private ResultActions create(String extra) throws Exception {
        return postAs(owner, ECO, "{\"title\":\"RM 소진 뒤 RM2\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\""
                + (extra == null ? "" : "," + extra) + "}");
    }

    private static final String STOCK_OUT = "\"applyCond\":\"STOCK_OUT\",\"stockItemCd\":\"rm\",\"stockPlantCd\":\"p1\"";

    private static long data(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** A1 BOM 대상 + 상신 + 승인 */
    private long approved(String extra) throws Exception {
        return approved(extra, "{\"rowKey\":\"b\",\"targetType\":\"BOM\",\"refId\":" + id("select bom_id from mst_bom where rev = 'A1'") + "}");
    }

    private long approved(String extra, String target) throws Exception {
        long eco = data(create(extra));
        mvc.perform(json(put(ECO + "/" + eco + "/targets"), "{\"rowVer\":" + ecoVer(eco)
                + ",\"targets\":[" + target + "]}").with(owner)).andExpect(status().isOk());
        postAs(owner, ECO + "/" + eco + "/submit", "{\"rowVer\":" + ecoVer(eco) + ",\"lines\":[{\"userId\":" + approverId
                + ",\"lineType\":\"APPROVE\"}]}").andExpect(status().isOk());
        long apr = id("select apr_id from plm_eco where eco_id = ?", eco);
        postAs(approver, APR + "/" + apr + "/approve", "{}").andExpect(status().isOk());
        return eco;
    }

    private ResultActions release(long eco) throws Exception {
        return postAs(approver, ECO + "/" + eco + "/release", "{\"rowVer\":" + ecoVer(eco) + "}");
    }

    // ---------- 시험

    @Test
    void 소진_품목의_가용_재고가_남으면_적용을_거부하고_0_이면_적용한다() throws Exception {
        adjust("5");
        long eco = approved(STOCK_OUT);
        mvc.perform(get(ECO + "/" + eco).with(approver)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eco.applyCond").value("STOCK_OUT"))
                .andExpect(jsonPath("$.data.eco.stockItemCd").value("RM"))
                .andExpect(jsonPath("$.data.eco.stockPlantCd").value("P1"))
                .andExpect(jsonPath("$.data.eco.stockAvailable").value(5));
        release(eco).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLM_STOCK_REMAIN"))
                .andExpect(jsonPath("$.errors[0].field").value("stockItemCd"))
                .andExpect(jsonPath("$.errors[0].code").value("STOCK_REMAIN"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("5")));
        // 거부는 전부 롤백 — ECO 승인됨, BOM 작성 중 그대로
        assertThat(accounts.query(comp, "select status from plm_eco where eco_id = ?", String.class, eco)).isEqualTo("APPROVED");
        assertThat(accounts.query(comp, "select status from mst_bom where rev = 'A1'", String.class)).isEqualTo("DRAFT");
        // 다 쓰면(0) 적용 — 보류·검사 대기는 StockQuery 가 세지 않는다(WMS 시험 몫)
        adjust("-5");
        mvc.perform(get(ECO + "/" + eco).with(approver)).andExpect(jsonPath("$.data.eco.stockAvailable").value(0));
        release(eco).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status || ' ' || valid_from from mst_bom where rev = 'A1'", String.class))
                .isEqualTo("RELEASED " + today);
        // 적용 뒤에는 재고를 보여 주지 않는다
        mvc.perform(get(ECO + "/" + eco).with(approver)).andExpect(jsonPath("$.data.eco.stockAvailable").doesNotExist());
    }

    @Test
    void 유효일이_지난_뒤_소진되면_적용한_날부터_유효하고_날짜_조건은_그대로_거부() throws Exception {
        long stock = approved(STOCK_OUT);
        long date = approved(null, "{\"rowKey\":\"i\",\"targetType\":\"ITEM\",\"itemCd\":\"FG\"}");
        // 결재 뒤 날짜가 흘렀다 — 유효일을 어제로. 고정 트리거는 앱 계정으로 넘을 수 없어 소유자 연결 한 세션만 트리거를 끈다(시험 준비)
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                java.sql.Statement st = c.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.executeUpdate("update plm_eco set effective_date = effective_date - 1 where eco_id in (" + stock + ", " + date + ")");
        }
        release(date).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_EFFECTIVE_PAST"));
        release(stock).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status || ' ' || valid_from from mst_bom where rev = 'A1'", String.class))
                .isEqualTo("RELEASED " + today);
        // ECO 의 유효일(결재 내용)은 바꾸지 않는다
        assertThat(accounts.query(comp, "select (effective_date < " + TestAccounts.COMP_TODAY + ")::text from plm_eco where eco_id = ?",
                String.class, stock)).isEqualTo("true");
    }

    @Test
    void E_값_검사_적용_조건_소진_품목_공장() throws Exception {
        create("\"applyCond\":\"NOPE\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'applyCond')].code").value("CODE"));
        create("\"applyCond\":\"STOCK_OUT\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'stockItemCd')].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'stockPlantCd')].code").value("REQUIRED"));
        create("\"applyCond\":\"STOCK_OUT\",\"stockItemCd\":\"NOPE\",\"stockPlantCd\":\"P9\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'stockItemCd')].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[?(@.field == 'stockPlantCd')].code").value("NOT_FOUND"));
        // 날짜 조건에 소진 품목을 적으면 칸 오류(조용히 버리지 않는다)
        create("\"stockItemCd\":\"RM\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'stockItemCd')].code").value("STOCK_COND"));
        // 비우면 DATE
        long eco = data(create(null));
        assertThat(accounts.query(comp, "select apply_cond || coalesce(stock_plant_cd, '-') from plm_eco where eco_id = ?", String.class, eco))
                .isEqualTo("DATE-");
        // 직접 SQL: 조건만 STOCK_OUT(품목 없음) → CHECK
        Throwable t = catchThrowable(() -> accounts.exec(comp, "update plm_eco set apply_cond = 'STOCK_OUT' where eco_id = ?", eco));
        assertThat(PgErrors.constraint(t)).contains("ck_plm_eco_stock_out");
    }

    @Test
    void C_상신_뒤에는_적용_조건을_바꿀_수_없다() throws Exception {
        long eco = approved(STOCK_OUT);
        Throwable t = catchThrowable(() -> accounts.exec(comp,
                "update plm_eco set apply_cond = 'DATE', stock_item_id = null, stock_plant_cd = null where eco_id = ?", eco));
        assertThat(PgErrors.constraint(t)).contains("ck_plm_eco_fixed");
        t = catchThrowable(() -> accounts.exec(comp,
                "update plm_eco set stock_item_id = (select item_id from mst_item where item_cd = 'RM2') where eco_id = ?", eco));
        assertThat(PgErrors.constraint(t)).contains("ck_plm_eco_fixed");
    }

    @Test
    void B_A사로_B사_소진_품목은_자기_회사_것만이고_다른_회사_재고는_세지_않는다() throws Exception {
        String compB = accounts.newComp("PSOB");
        long userB = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, userB, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(compB, userB, "engB");
        // B사에는 RM 이 없다 → A사 품목 코드는 찾지 못함
        mvc.perform(json(post(ECO), "{\"title\":\"x\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\"," + STOCK_OUT + "}").with(b))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'stockItemCd')].code").value("NOT_FOUND"));
        // 직접 SQL: B사 ECO 에 A사 품목 id → 같은 회사 확인
        long ecoB = accounts.query(compB, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, 'ECO-B', 'B', 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, 't', 't') returning eco_id""", Long.class, compB, userB);
        long rmA = id("select item_id from mst_item where item_cd = 'RM'");
        Throwable t = catchThrowable(() -> accounts.exec(compB,
                "update plm_eco set apply_cond = 'STOCK_OUT', stock_item_id = ?, stock_plant_cd = 'P1' where eco_id = ?", rmA, ecoB));
        assertThat(PgErrors.constraint(t)).contains("fk_plm_eco_stock_item_comp");
        // A사 재고 5 가 있어도 B사 RM 의 소진 판정은 B사 재고(0)만 본다
        adjust("5");
        seed(compB);
        long ecoB2 = data(mvc.perform(json(post(ECO), "{\"title\":\"x\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\","
                + STOCK_OUT + "}").with(b)));
        mvc.perform(get(ECO + "/" + ecoB2).with(b)).andExpect(jsonPath("$.data.eco.stockAvailable").value(0));
        // A사로 B사 ECO 상세 → 404
        mvc.perform(get(ECO + "/" + ecoB2).with(owner)).andExpect(status().isNotFound());
    }
}
