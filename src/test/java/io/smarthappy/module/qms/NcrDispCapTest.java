package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * QMS R3-c(QA docs/qa/2026-11-02_round3.md, docs/29_P3 47장) — 손 NCR: 처분 수량 합 ≤ 부적합 수량을 OPEN 저장 때도(종결 때 = 는 그대로),
 * 부적합 수량을 처분 합보다 작게 줄이기 거부, 처분 행 동시 INSERT(머리 잠금으로 줄 섬), 직접 SQL, A사로 B사.
 * 클레임 NCR(반품 수량 상한)은 {@code QmsClaimR3Test}.
 */
class NcrDispCapTest extends QmsTestBase {

    static final String N = "/api/qms/ncrs";

    long defect;

    @BeforeEach
    void capSetUp() {
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        accounts.exec(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-SCR', '스크래치', 'APPEARANCE', 't', 't')""", comp);
        defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'D-SCR'", Long.class);
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'L-C1', item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1'""", comp);
    }

    long ncr(String qty) throws Exception {
        return id(postJson(N, "{\"lotNo\":\"L-C1\",\"source\":\"PROCESS\",\"defectId\":" + defect + ",\"ncQty\":" + qty
                + ",\"description\":\"외관 스크래치\"}"));
    }

    int ver(long ncr) {
        return accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, ncr);
    }

    String state(long ncr) {
        return accounts.query(comp, """
                select trim_scale(n.nc_qty)::text || '/' || coalesce((select string_agg(d.disp_type || ':' || trim_scale(d.qty)::text, ',' order by d.line_no)
                  from qms_ncr_disp d where d.ncr_id = n.ncr_id), '') from qms_ncr n where n.ncr_id = ?""", String.class, ncr);
    }

    ResultActions save(long ncr, String qty, String disps) throws Exception {
        return putJson(N + "/" + ncr, body(ver(ncr), qty, disps));
    }

    String body(int rowVer, String qty, String disps) {
        return "{\"rowVer\":" + rowVer + ",\"defectId\":" + defect + ",\"ncQty\":" + qty + ",\"description\":\"외관 스크래치\",\"disps\":[" + disps + "]}";
    }

    static String disp(String key, String type, String qty) {
        return "{\"rowKey\":\"" + key + "\",\"dispType\":\"" + type + "\",\"qty\":" + qty + ",\"note\":\"MRB\"}";
    }

    @Test
    void 처분_합은_저장_때도_부적합_수량을_넘지_못하고_모자란_저장은_된다() throws Exception {
        long id = ncr("5");
        // 넘음 → 칸 오류, 아무것도 안 바뀜
        save(id, "5", disp("a", "SCRAP", "3") + "," + disp("b", "SORT", "2.001")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("disps")).andExpect(jsonPath("$.errors[0].code").value("NCR_DISP_OVER"));
        assertThat(state(id)).isEqualTo("5/");
        // 모자람(작업 중) → 저장됨, 종결은 지금처럼 NCR_DISP_SUM
        save(id, "5", disp("a", "SCRAP", "3")).andExpect(status().isOk());
        postJson(N + "/" + id + "/close", "{\"rowVer\":" + ver(id) + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NCR_DISP_SUM"));
        // 같음 → 저장 → 종결
        save(id, "5", disp("a", "SCRAP", "3") + "," + disp("b", "SORT", "2")).andExpect(status().isOk());
        assertThat(state(id)).isEqualTo("5/SCRAP:3,SORT:2");
        postJson(N + "/" + id + "/close", "{\"rowVer\":" + ver(id) + "}").andExpect(status().isOk());
    }

    @Test
    void 부적합_수량과_처분을_함께_줄이면_되고_수량만_줄여_처분_합_아래로는_안_된다() throws Exception {
        long id = ncr("5");
        save(id, "5", disp("a", "SCRAP", "3") + "," + disp("b", "SORT", "2")).andExpect(status().isOk());
        // 처분은 그대로 두고 수량만 4 로 → 처분 합 5 > 4
        save(id, "4", disp("a", "SCRAP", "3") + "," + disp("b", "SORT", "2")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NCR_DISP_OVER"));
        assertThat(state(id)).isEqualTo("5/SCRAP:3,SORT:2");
        // 함께 줄임 → 됨(서비스가 처분 행을 먼저 지우고 머리를 고친다 — 머리 트리거 V236 과 같은 순서)
        save(id, "4", disp("a", "SCRAP", "3") + "," + disp("b", "SORT", "1")).andExpect(status().isOk());
        assertThat(state(id)).isEqualTo("4/SCRAP:3,SORT:1");
        // 수량 늘리기는 처분과 무관하게 됨(손 NCR — 상한 없음)
        save(id, "9", disp("a", "SCRAP", "3")).andExpect(status().isOk());
        assertThat(state(id)).isEqualTo("9/SCRAP:3");
        mvc.perform(get(N + "/" + id).with(admin)).andExpect(jsonPath("$.data.ncr.srcQty").doesNotExist());
    }

    @Test
    void 이상_입력_음수_0_null_처분은_합에_넣지_않고_행_오류() throws Exception {
        long id = ncr("2");
        // 음수 행으로 합을 깎아 넘기기 → 그 행이 INVALID, 합 검사는 양수 행만이라 넘음도 같이 보인다
        save(id, "2", disp("a", "SCRAP", "3") + "," + disp("b", "SORT", "-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey == 'b')].code").value(org.hamcrest.Matchers.hasItem("INVALID")))
                .andExpect(jsonPath("$.errors[?(@.field == 'disps')].code").value(org.hamcrest.Matchers.hasItem("NCR_DISP_OVER")));
        save(id, "2", disp("a", "SCRAP", "null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 부적합 수량이 비면 그 칸 오류만(합 비교 안 함)
        save(id, "null", disp("a", "SCRAP", "3")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.contains("ncQty")));
        assertThat(state(id)).isEqualTo("2/");
    }

    /** 동시: 다른 트랜잭션이 처분 3 을 넣고 커밋 전 — 같은 NCR 에 처분 3 INSERT 는 머리 잠금에서 기다렸다가, 커밋을 본 뒤 합 6 > 5 로 거부 */
    @Test
    void D_처분_행_동시_INSERT_는_머리_잠금으로_줄_서고_합을_넘으면_뒤쪽이_거부() throws Exception {
        long id = ncr("5");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            insertDisp(c, id, 1, "3");
            Future<String> f = pool.submit(() -> {
                try (Connection c2 = appConnection()) {
                    c2.setAutoCommit(false);
                    try {
                        insertDisp(c2, id, 2, "3");
                        c2.commit();
                        return "OK";
                    } catch (SQLException ex) {
                        c2.rollback();
                        return PgErrors.constraint(ex).orElse(ex.getSQLState());
                    }
                }
            });
            Thread.sleep(800);
            assertThat(f.isDone()).as("둘째 INSERT 는 첫째가 쥔 머리 잠금을 기다린다").isFalse();
            c.commit();
            assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo("ck_qms_ncr_disp_over");
        } finally {
            pool.shutdownNow();
        }
        assertThat(state(id)).isEqualTo("5/SCRAP:3");
    }

    /** 동시 저장 둘(같은 rowVer) — 하나만 된다, 처분 합이 둘의 합으로 섞이지 않음 */
    @Test
    void D_같은_rowVer_저장_둘은_하나만_되고_처분이_섞이지_않는다() throws Exception {
        long id = ncr("5");
        int v = ver(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> f1 = pool.submit(() -> putJson(N + "/" + id, body(v, "5", disp("a", "SCRAP", "4"))).andReturn().getResponse().getStatus());
            Future<Integer> f2 = pool.submit(() -> putJson(N + "/" + id, body(v, "5", disp("a", "SORT", "4"))).andReturn().getResponse().getStatus());
            assertThat(java.util.List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(state(id)).matches("5/(SCRAP|SORT):4");
    }

    @Test
    void 직접SQL_처분_합_넘김_수량_줄이기_근거_수량_거부() throws Exception {
        long id = ncr("2");
        accounts.exec(comp, "insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values (?, ?, 1, 'SCRAP', 2, 't')", comp, id);
        constraint(comp, "ck_qms_ncr_disp_over", () -> accounts.exec(comp,
                "insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values (?, ?, 2, 'SORT', 0.001, 't')", comp, id));
        constraint(comp, "ck_qms_ncr_disp_over", () -> accounts.exec(comp, "update qms_ncr set nc_qty = 1.999 where ncr_id = ?", id));
        // 손 NCR 에 근거 수량을 붙이기 — 정체 칸(고정)
        constraint(comp, "ck_qms_ncr_immutable", () -> accounts.exec(comp, "update qms_ncr set src_qty = 5 where ncr_id = ?", id));
        // 근거 없이 근거 수량만 가진 INSERT
        constraint(comp, "ck_qms_ncr_src_qty", () -> accounts.exec(comp, """
                insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, defect_id, nc_qty, description, occurred_at, hold_yn,
                                     src_qty, created_by, updated_by)
                select ?, 'X-SQ', 'OTHER', lot_id, item_id, 'EA', ?, 1, 'x', now(), 'N', 3, 't', 't' from lot_master where lot_no = 'L-C1'""",
                comp, defect));
        assertThat(state(id)).isEqualTo("2/SCRAP:2");
    }

    @Test
    void A사로_B사_다른_회사_NCR_에_처분_저장은_안_되고_A사_처분은_그대로() throws Exception {
        long id = ncr("5");
        save(id, "5", disp("a", "SCRAP", "2")).andExpect(status().isOk());
        String other = accounts.newComp("QR");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        int v = ver(id);
        // B사에는 그 불량 유형도 NCR 도 없다 — 칸 오류(불량 유형) 또는 404, 어느 쪽이든 A사 행은 그대로
        int st = mvc.perform(json(put(N + "/" + id), body(v, "5", disp("a", "SCRAP", "5"))).with(b)).andReturn().getResponse().getStatus();
        assertThat(st).isIn(400, 404);
        assertThat(state(id)).isEqualTo("5/SCRAP:2");
        // B사 범위에서 A사 NCR 에 처분 행 직접 INSERT — 머리가 안 보여 ck_qms_ncr_disp_open
        constraint(other, "ck_qms_ncr_disp_open", () -> accounts.exec(other,
                "insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values (?, ?, 9, 'SCRAP', 1, 't')", other, id));
        assertThat(state(id)).isEqualTo("5/SCRAP:2");
    }

    Connection appConnection() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl(), APP_USER, testPassword());
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.comp_cd', ?, false)")) {
            ps.setString(1, comp);
            ps.execute();
        }
        return c;
    }

    void insertDisp(Connection c, long ncr, int line, String qty) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values (?, ?, ?, 'SCRAP', ?::numeric, 't')")) {
            ps.setString(1, comp);
            ps.setLong(2, ncr);
            ps.setInt(3, line);
            ps.setString(4, qty);
            ps.executeUpdate();
        }
    }
}
