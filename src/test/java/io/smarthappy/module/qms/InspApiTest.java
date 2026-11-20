package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * P3 QMS 2부 Q6 검사 실행·측정값 원장 — 정상 흐름(만들기 = 확정 계획·샘플 수 → 측정 → 판정 확정) + 정정(무효 행 뒤 다시) + 계측기 차단(D5)
 * + 이상 사용(A~K: A사로 B사, 직접 SQL 원장·전이·스냅샷, 입력, 권한).
 */
class InspApiTest extends QmsTestBase {

    static final String I = "/api/qms/insps";
    static final String G = "/api/qms/gauges";

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    long gOk;
    long gNg;
    long gOver;
    long gMic;

    @BeforeEach
    void inspSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-gauge-calib", "SEARCH", "SAVE");
        gOk = gauge(comp, "GOK", "CALIPER");
        gNg = gauge(comp, "GNG", "CALIPER");
        gOver = gauge(comp, "GOV", "CALIPER");
        gMic = gauge(comp, "GMI", "MICROMETER");
        calib(gOk, today.minusDays(10), "OK");
        calib(gNg, today.minusDays(5), "NG");
        calib(gOver, today.minusDays(400), "OK");
        calib(gMic, today.minusDays(10), "OK");
        // 출하검사 확정본: FG1-1 계량(캘리퍼, 고정 5) + FG1-2 계수(육안, 고정 5)
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        lot(comp, "L-FG1-001", "FG1", "120");
    }

    long gauge(String c, String cd, String cls) {
        accounts.exec(c, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, ?, 'GAUGE', ?, ?, 365, 't', 't')""", c, cd, cd + " 계측기", cls);
        return accounts.query(c, "select asset_id from mst_phys_asset where asset_cd = ?", Long.class, cd);
    }

    void calib(long assetId, LocalDate date, String result) throws Exception {
        postJson(G + "/" + assetId + "/calibs", "{\"calibDate\":" + q(date) + ",\"result\":" + q(result) + ",\"agency\":\"INTERNAL\"}")
                .andExpect(status().isOk());
    }

    long lot(String c, String no, String item, String qty) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', cast(? as numeric), 'EA', now(), 't', 't' from mst_item where item_cd = ? returning lot_id""",
                Long.class, c, no, qty, item);
    }

    long insp(String lotNo, String extra) throws Exception {
        return id(postJson(I, "{\"lotNo\":" + q(lotNo) + ",\"inspType\":\"FINAL\"" + (extra.isEmpty() ? "" : "," + extra) + "}"));
    }

    long lineId(long insp, int no) {
        return accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ? and line_no = ?", Long.class, insp, no);
    }

    int ver(long insp) {
        return accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, insp);
    }

    ResultActions meas(long insp, long line, String entries) throws Exception {
        return postJson(I + "/" + insp + "/meas", "{\"inspLineId\":" + line + ",\"entries\":[" + entries + "]}");
    }

    static String v(int no, String value, Long gauge) {
        return "{\"sampleNo\":" + no + ",\"value\":" + value + ",\"gaugeAssetId\":" + gauge + "}";
    }

    static String a(int no, String ok) {
        return "{\"sampleNo\":" + no + ",\"okYn\":" + q(ok) + "}";
    }

    String five(Long gauge, String... values) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            b.append(i == 0 ? "" : ",").append(v(i + 1, values[i], gauge));
        }
        return b.toString();
    }

    ResultActions judge(long insp) throws Exception {
        return postJson(I + "/" + insp + "/judge", "{\"rowVer\":" + ver(insp) + "}");
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 만들기_확정계획_샘플수_측정_판정확정_뒤_고정() throws Exception {
        long id = insp("L-FG1-001", "");
        String d = mvc.perform(get(I + "/" + id).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(d, "$.data.insp.inspNo")).matches("^IN[0-9]{4}-00001$");
        assertThat((Integer) JsonPath.read(d, "$.data.insp.lotQty")).isEqualTo(120);
        assertThat((String) JsonPath.read(d, "$.data.insp.planRev")).isNotBlank();
        assertThat((Integer) JsonPath.read(d, "$.data.lines.length()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(d, "$.data.lines[0].sampleSize")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(d, "$.data.lines[0].re")).isEqualTo(1);
        assertThat((String) JsonPath.read(d, "$.data.lines[0].charLabel")).isEqualTo("FG1-1");
        assertThat((String) JsonPath.read(d, "$.data.verdict")).isEqualTo("OPEN");
        // 계량 5개(경계값 포함) + 계수 5개(육안 = 게이지 없음)
        meas(id, lineId(id, 10), five(gOk, "9.95", "10.00", "10.01", "10.02", "10.05")).andExpect(status().isOk());
        meas(id, lineId(id, 20), a(1, "Y") + "," + a(2, "Y") + "," + a(3, "Y") + "," + a(4, "Y") + "," + a(5, "Y")).andExpect(status().isOk());
        mvc.perform(get(I + "/" + id).with(admin)).andExpect(jsonPath("$.data.verdict").value("OK"))
                .andExpect(jsonPath("$.data.lines[0].verdict").value("OK")).andExpect(jsonPath("$.data.lines[0].measured").value(5))
                .andExpect(jsonPath("$.data.meas.length()").value(10));
        judge(id).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status || ':' || result || ':' || judged_by from qms_insp where insp_id = ?", String.class, id))
                .isEqualTo("JUDGED:OK:admin1");
        assertThat(accounts.query(comp, "select string_agg(result, ',' order by line_no) from qms_insp_line where insp_id = ?", String.class, id))
                .isEqualTo("OK,OK");
        // 확정 뒤 측정·무효·다시 확정 거부
        meas(id, lineId(id, 10), v(1, "10.00", gOk)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_INSP_STATE"));
        long anyMeas = accounts.query(comp, "select min(meas_id) from qms_insp_meas", Long.class);
        postJson(I + "/" + id + "/meas/void", "{\"measId\":" + anyMeas + "}").andExpect(jsonPath("$.code").value("QMS_INSP_STATE"));
        judge(id).andExpect(jsonPath("$.code").value("QMS_INSP_STATE"));
        // 목록·필터
        mvc.perform(get(I).param("result", "OK").with(admin)).andExpect(jsonPath("$.data[0].inspId").value((int) id));
        mvc.perform(get(I).param("status", "OPEN").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(I).param("keyword", "L-FG1").with(admin)).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void Re_에_닿으면_바로_NG_정정은_무효뒤_다시_미정이면_확정불가() throws Exception {
        long id = insp("L-FG1-001", "");
        long a = lineId(id, 10);
        meas(id, a, v(1, "10.10", gOk)).andExpect(status().isOk());
        mvc.perform(get(I + "/" + id).with(admin)).andExpect(jsonPath("$.data.lines[0].verdict").value("NG"))
                .andExpect(jsonPath("$.data.lines[0].nonconforming").value(1));
        // 같은 샘플에 다시 → 칸 오류, 무효 뒤 다시
        meas(id, a, v(1, "10.00", gOk)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SAMPLE_TAKEN"));
        long m1 = accounts.query(comp, "select meas_id from qms_insp_meas where insp_line_id = ? and sample_no = 1", Long.class, a);
        postJson(I + "/" + id + "/meas/void", "{\"measId\":" + m1 + "}").andExpect(status().isOk());
        postJson(I + "/" + id + "/meas/void", "{\"measId\":" + m1 + "}").andExpect(status().isNotFound());
        meas(id, a, v(1, "10.00", gOk)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from qms_insp_meas where insp_line_id = ?", Integer.class, a)).as("원값·무효·새값").isEqualTo(3);
        mvc.perform(get(I + "/" + id).with(admin)).andExpect(jsonPath("$.data.lines[0].verdict").value("OPEN"))
                .andExpect(jsonPath("$.data.meas[0].value").value(10.0));
        // 미정 행이 있으면 확정 불가(행 키 = 검사 행 id)
        judge(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QMS_INSP_INCOMPLETE"))
                .andExpect(jsonPath("$.errors[*].rowKey").value(hasItems(String.valueOf(a), String.valueOf(lineId(id, 20)))));
        // 계수 NG 하나 → 검사 NG 로 확정(계량은 다 재야 OK)
        meas(id, a, five(gOk, "10.00", "10.00", "10.00", "10.00", "10.00").replaceFirst("\\{\"sampleNo\":1,[^}]*\\},", "")).andExpect(status().isOk());
        meas(id, lineId(id, 20), a(1, "N")).andExpect(status().isOk());
        judge(id).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select result from qms_insp where insp_id = ?", String.class, id)).isEqualTo("NG");
        assertThat(accounts.query(comp, "select string_agg(result, ',' order by line_no) from qms_insp_line where insp_id = ?", String.class, id))
                .isEqualTo("OK,NG");
    }

    @Test
    void 계측기는_클래스가_맞고_교정이_유효해야_후보에_막힘_표시() throws Exception {
        long id = insp("L-FG1-001", "");
        long a = lineId(id, 10);
        meas(id, a, "{\"sampleNo\":1,\"value\":10.0}").andExpect(jsonPath("$.errors[0].field").value("gaugeAssetId"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        meas(id, a, v(1, "10.0", gNg) + "," + v(2, "10.0", gOver) + "," + v(3, "10.0", gMic) + "," + v(4, "10.0", 999999999L))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(4))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0')].code").value(hasItem("GAUGE_BLOCKED")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '1')].code").value(hasItem("GAUGE_BLOCKED")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '2')].code").value(hasItem("GAUGE_CLASS")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '3')].code").value(hasItem("NOT_FOUND")));
        mvc.perform(get(I + "/gauges").param("gaugeClass", "CALIPER").with(admin))
                .andExpect(jsonPath("$.data[?(@.assetCd == 'GOK')].blocked").value(hasItem(false)))
                .andExpect(jsonPath("$.data[?(@.assetCd == 'GNG')].status").value(hasItem("NG")))
                .andExpect(jsonPath("$.data[?(@.assetCd == 'GOV')].blocked").value(hasItem(true)))
                .andExpect(jsonPath("$.data[?(@.assetCd == 'GMI')]").isEmpty());
        mvc.perform(get(I + "/gauges").with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void 계획없음_로트수량_직접_입력_전수() throws Exception {
        lot(comp, "L-RM2-001", "RM2", "10");
        postJson(I, "{\"lotNo\":\"L-RM2-001\",\"inspType\":\"FINAL\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QMS_INSP_NO_PLAN"));
        // 수입검사 전수(SALL) 계획 → 로트 수량 = 샘플 수
        long p = plan("RECEIVE", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar2, "SALL", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        long id = id(postJson(I, "{\"lotNo\":\"L-FG1-001\",\"inspType\":\"RECEIVE\",\"lotQty\":3}"));
        mvc.perform(get(I + "/" + id).with(admin)).andExpect(jsonPath("$.data.insp.lotQty").value(3))
                .andExpect(jsonPath("$.data.lines[0].sampleSize").value(3));
        lot(comp, "L-FG1-DEC", "FG1", "2.4");
        long dec = id(postJson(I, "{\"lotNo\":\"L-FG1-DEC\",\"inspType\":\"RECEIVE\"}"));
        assertThat(accounts.query(comp, "select lot_qty from qms_insp where insp_id = ?", Long.class, dec)).as("소수 수량은 올림").isEqualTo(3L);
    }

    // ---------- 이상 사용 ----------

    @Test
    void 입력_이상_만들기_측정_조건조작() throws Exception {
        postJson(I, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field").value(hasItems("lotNo", "inspType")));
        postJson(I, "{\"lotNo\":\"NOPE\",\"inspType\":\"AUDIT\",\"procCd\":\"NOPE\",\"lotQty\":0}").andExpect(jsonPath("$.errors.length()").value(4));
        long id = insp("L-FG1-001", "");
        long a = lineId(id, 10);
        long b = lineId(id, 20);
        meas(id, a, v(6, "10.0", gOk) + "," + v(1, "10.0", gOk) + "," + v(1, "10.0", gOk) + "," + v(2, "10.1234567", gOk)
                + ",{\"sampleNo\":3,\"gaugeAssetId\":" + gOk + "}").andExpect(jsonPath("$.errors.length()").value(4))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0')].code").value(hasItem("RANGE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '2')].code").value(hasItem("SAMPLE_TAKEN")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '3')].code").value(hasItem("FORMAT")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '4')].code").value(hasItem("REQUIRED")));
        meas(id, b, a(1, "MAYBE")).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + a + ",\"entries\":[]}").andExpect(status().isBadRequest());
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":999999999,\"entries\":[" + v(1, "1", gOk) + "]}").andExpect(status().isNotFound());
        postJson(I + "/" + id + "/judge", "{}").andExpect(status().isBadRequest());
        postJson(I + "/" + id + "/void", "{\"rowVer\":" + ver(id) + ",\"reason\":\" \"}").andExpect(jsonPath("$.errors[0].field").value("reason"));
        postJson(I + "/" + id + "/void", "{\"rowVer\":99,\"reason\":\"잘못 열림\"}").andExpect(status().isConflict());
        postJson(I + "/" + id + "/void", "{\"rowVer\":" + ver(id) + ",\"reason\":\"잘못 열림\"}").andExpect(status().isOk());
        postJson(I + "/" + id + "/void", "{\"rowVer\":" + ver(id) + ",\"reason\":\"다시\"}").andExpect(jsonPath("$.code").value("QMS_INSP_STATE"));
        meas(id, a, v(1, "10.0", gOk)).andExpect(jsonPath("$.code").value("QMS_INSP_STATE"));
        mvc.perform(get(I).param("inspType", "X").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(I).param("result", "MAYBE").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(I).param("keyword", "%").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(I + "/999999999").with(admin)).andExpect(status().isNotFound());
    }

    @Test
    void 권한별_403() throws Exception {
        long id = insp("L-FG1-001", "");
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "qms-insp", "SEARCH");
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "qms-insp", "SEARCH", "SAVE");
        RequestPostProcessor sv = accounts.as(comp, saver, "saver1");
        mvc.perform(get(I + "/" + id).with(rd)).andExpect(status().isOk());
        mvc.perform(json(post(I), "{\"lotNo\":\"L-FG1-001\",\"inspType\":\"FINAL\"}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(I + "/" + id + "/meas"), "{\"inspLineId\":" + lineId(id, 20) + ",\"entries\":[" + a(1, "Y") + "]}").with(rd))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(I + "/" + id + "/meas"), "{\"inspLineId\":" + lineId(id, 20) + ",\"entries\":[" + a(1, "Y") + "]}").with(sv))
                .andExpect(status().isOk());
        mvc.perform(json(post(I + "/" + id + "/void"), "{\"rowVer\":0,\"reason\":\"x\"}").with(sv)).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select recorded_by from qms_insp_meas", String.class)).isEqualTo("saver1");
    }

    @Test
    void B사_사용자는_A사_검사를_보지도_쓰지도_못한다() throws Exception {
        long id = insp("L-FG1-001", "");
        long a = lineId(id, 10);
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(I).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(I + "/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(I + "/" + id + "/meas"), "{\"inspLineId\":" + a + ",\"entries\":[" + v(1, "10", gOk) + "]}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(I + "/" + id + "/judge"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(I + "/" + id + "/void"), "{\"rowVer\":0,\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(I), "{\"lotNo\":\"L-FG1-001\",\"inspType\":\"FINAL\"}").with(b)).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(get(I + "/gauges").param("gaugeClass", "CALIPER").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // 직접 SQL: B사 검사에 A사 LOT·계획, A사 검사 행에 B사 측정, B사 측정에 A사 게이지
        fixtures(other);
        long aLot = accounts.query(comp, "select lot_id from lot_master where lot_no = 'L-FG1-001'", Long.class);
        long aPlan = accounts.query(comp, "select insp_plan_id from qms_insp where insp_id = ?", Long.class, id);
        long bItem = accounts.query(other, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        constraint(other, "fk_qms_insp_lot_comp", () -> accounts.exec(other,
                "insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, insp_plan_id, lot_qty, occurred_at, created_by, updated_by) "
                        + "values (?, 'X1', 'FINAL', ?, ?, ?, 1, now(), 't', 't')", other, aLot, bItem, aPlan));
        constraint(other, "fk_qms_insp_meas_line_comp", () -> accounts.exec(other,
                "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, value, gauge_asset_id, occurred_at, recorded_by) values (?, ?, 1, 10, ?, now(), 't')",
                other, a, gOk));
        assertThat(accounts.exec(other, "update qms_insp set remark = 'x' where insp_id = ?", id)).isZero();
        assertThat(accounts.query(comp, "select count(*) from qms_insp_meas", Integer.class)).isZero();
    }

    @Test
    void 직접SQL_원장_추가만_살아있는값_하나_전이_스냅샷_고정() throws Exception {
        long id = insp("L-FG1-001", "");
        long a = lineId(id, 10);
        long b = lineId(id, 20);
        meas(id, a, v(1, "10.0", gOk)).andExpect(status().isOk());
        long m1 = accounts.query(comp, "select meas_id from qms_insp_meas where insp_line_id = ?", Long.class, a);
        String ins = "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, value, ok_yn, gauge_asset_id, void_of_id, occurred_at, recorded_by) "
                + "values (?, ?, ?, ?, ?, ?, ?, now(), 't')";
        // 앱 계정은 UPDATE·DELETE 권한부터 없다(REVOKE) — 트리거 ck_qms_insp_meas_append_only 는 소유자 경로의 마지막 방어
        sqlState("42501", () -> accounts.exec(comp, "update qms_insp_meas set value = 9 where meas_id = ?", m1));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_insp_meas where meas_id = ?", m1));
        constraint(comp, "uq_qms_insp_meas_live", () -> accounts.exec(comp, ins, comp, a, 1, new java.math.BigDecimal("10.01"), null, gOk, null));
        constraint(comp, "ck_qms_insp_meas_sample_size", () -> accounts.exec(comp, ins, comp, a, 6, java.math.BigDecimal.ONE, null, gOk, null));
        constraint(comp, "ck_qms_insp_meas_kind", () -> accounts.exec(comp, ins, comp, b, 1, java.math.BigDecimal.ONE, null, null, null));
        constraint(comp, "ck_qms_insp_meas_value", () -> accounts.exec(comp, ins, comp, b, 1, null, null, null, null));
        constraint(comp, "ck_qms_insp_meas_gauge_required", () -> accounts.exec(comp, ins, comp, a, 2, java.math.BigDecimal.ONE, null, null, null));
        constraint(comp, "ck_qms_insp_meas_gauge_class", () -> accounts.exec(comp, ins, comp, a, 2, java.math.BigDecimal.ONE, null, gMic, null));
        constraint(comp, "ck_qms_insp_meas_void_target", () -> accounts.exec(comp, ins, comp, a, 2, null, null, null, m1));
        // 같은 행을 두 번 무효로
        accounts.exec(comp, ins, comp, a, 1, null, null, null, m1);
        constraint(comp, "uq_qms_insp_meas_void", () -> accounts.exec(comp, ins, comp, a, 1, null, null, null, m1));
        // 머리·행 고정
        constraint(comp, "ck_qms_insp_immutable", () -> accounts.exec(comp, "update qms_insp set lot_qty = 5 where insp_id = ?", id));
        constraint(comp, "ck_qms_insp_judged", () -> accounts.exec(comp, "update qms_insp set status = 'JUDGED' where insp_id = ?", id));
        constraint(comp, "ck_qms_insp_line_immutable", () -> accounts.exec(comp, "update qms_insp_line set usl = 99 where insp_line_id = ?", a));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_insp where insp_id = ?", id));
        accounts.exec(comp, "update qms_insp set status = 'JUDGED', result = 'NG', judged_at = now(), judged_by = 't' where insp_id = ?", id);
        constraint(comp, "ck_qms_insp_meas_open", () -> accounts.exec(comp, ins, comp, a, 2, java.math.BigDecimal.ONE, null, gOk, null));
        constraint(comp, "ck_qms_insp_transition", () -> accounts.exec(comp,
                "update qms_insp set status = 'OPEN', result = null, judged_at = null, judged_by = null where insp_id = ?", id));
        constraint(comp, "ck_qms_insp_immutable", () -> accounts.exec(comp, "update qms_insp set result = 'OK' where insp_id = ?", id));
        constraint(comp, "ck_qms_insp_line_immutable", () -> accounts.exec(comp, "update qms_insp_line set result = 'OK' where insp_line_id = ?", a));
        // 만들기: 작성 중 계획·다른 품목 LOT
        long draft = plan("PROCESS", "FG1", null, null);
        long lot = accounts.query(comp, "select lot_id from lot_master where lot_no = 'L-FG1-001'", Long.class);
        long rm1 = accounts.query(comp, "select item_id from mst_item where item_cd = 'RM1'", Long.class);
        long fg1 = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        String hd = "insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, insp_plan_id, lot_qty, occurred_at, created_by, updated_by) "
                + "values (?, ?, ?, ?, ?, ?, 1, now(), 't', 't')";
        constraint(comp, "ck_qms_insp_plan_released", () -> accounts.exec(comp, hd, comp, "X1", "PROCESS", lot, fg1, draft));
        long released = accounts.query(comp, "select insp_plan_id from qms_insp where insp_id = ?", Long.class, id);
        constraint(comp, "ck_qms_insp_lot_item", () -> accounts.exec(comp, hd, comp, "X2", "FINAL", lot, rm1, released));
    }

    @Test
    void D_같은_샘플에_동시에_두_값이면_하나만_살고_나중_것은_기다렸다_거부() throws Exception {
        long id = insp("L-FG1-001", "");
        long a = lineId(id, 10);
        String ins = "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, value, gauge_asset_id, occurred_at, recorded_by) "
                + "values (?, ?, 1, ?, ?, now(), 't')";
        CountDownLatch inserted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 첫째: 샘플 1 을 넣고(검사 행 FOR UPDATE 를 쥔 채) 커밋 전에 머문다
            Future<?> first = pool.submit(() -> runner.run(comp, () -> {
                jdbc.update(ins, comp, a, new java.math.BigDecimal("10.00"), gOk);
                inserted.countDown();
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            // 둘째: 같은 행·같은 샘플 → 행 잠금을 기다렸다가 첫째 커밋 뒤 살아 있는 값을 보고 거부
            long t0 = System.nanoTime();
            Future<Throwable> second = pool.submit(() -> {
                try {
                    runner.run(comp, () -> jdbc.update(ins, comp, a, new java.math.BigDecimal("10.01"), gOk));
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            });
            Throwable t = second.get(20, TimeUnit.SECONDS);
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            first.get(20, TimeUnit.SECONDS);
            assertThat(t).as("둘째는 거부").isNotNull();
            assertThat(PgErrors.constraint(t)).contains("uq_qms_insp_meas_live");
            assertThat(waited).as("첫째 커밋까지 기다렸다").isGreaterThanOrEqualTo(400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from qms_insp_meas where insp_line_id = ?", Integer.class, a)).isEqualTo(1);
        assertThat(accounts.query(comp, "select value::text from qms_insp_meas where insp_line_id = ?", String.class, a)).isEqualTo("10.000000");
    }

    // ---------- 정정 한 번에 · 계획 행 분류·증빙 (docs/72 3.2·3.3) ----------

    @Test
    void 정정은_무효행과_새값을_한번에_틀린_값이면_앞_기록_그대로_A사로_B사_확정뒤_거부() throws Exception {
        long id = insp("L-FG1-001", "");
        long a = lineId(id, 10);
        meas(id, a, v(1, "10.10", gOk)).andExpect(status().isOk());
        long m1 = accounts.query(comp, "select meas_id from qms_insp_meas where insp_line_id = ? and sample_no = 1", Long.class, a);
        // 정정 — 무효 행 + 같은 샘플 번호의 새 값(보낸 샘플 번호는 무시), 앞 기록은 남는다
        correct(id, m1, v(99, "10.00", gOk)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from qms_insp_meas where insp_line_id = ?", Integer.class, a)).as("원값·무효·새값").isEqualTo(3);
        mvc.perform(get(I + "/" + id).with(admin)).andExpect(jsonPath("$.data.meas.length()").value(1))
                .andExpect(jsonPath("$.data.meas[0].sampleNo").value(1)).andExpect(jsonPath("$.data.meas[0].value").value(10.0))
                .andExpect(jsonPath("$.data.lines[0].nonconforming").value(0));
        // 이미 무효가 된 값은 다시 정정할 수 없다(404), 빈 요청은 400
        correct(id, m1, v(1, "10.01", gOk)).andExpect(status().isNotFound());
        postJson(I + "/" + id + "/meas/correct", "{\"measId\":null,\"entry\":null}").andExpect(status().isBadRequest());
        long m2 = accounts.query(comp, "select max(meas_id) from qms_insp_meas where insp_line_id = ? and value is not null", Long.class, a);
        // 새 값이 틀리면(교정 불합격 게이지) 칸 오류이고 앞 값은 살아 있다 — 무효 행만 남는 일이 없다
        correct(id, m2, v(1, "10.02", gNg)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("gaugeAssetId"));
        assertThat(accounts.query(comp, "select count(*) from qms_insp_meas where insp_line_id = ?", Integer.class, a)).isEqualTo(3);
        mvc.perform(get(I + "/" + id).with(admin)).andExpect(jsonPath("$.data.meas[0].value").value(10.0));
        // 다른 검사의 측정 id 로는 못 고친다(404)
        lot(comp, "L-FG1-002", "FG1", "120");
        long id2 = insp("L-FG1-002", "");
        correct(id2, m2, v(1, "10.02", gOk)).andExpect(status().isNotFound());
        // A사 검사를 B사 계정으로 — 404, 기록 그대로
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        String body = "{\"measId\":" + m2 + ",\"entry\":" + v(1, "10.03", gOk) + "}";
        mvc.perform(json(post(I + "/" + id + "/meas/correct"), body).with(b)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from qms_insp_meas where insp_line_id = ?", Integer.class, a)).isEqualTo(3);
        // SAVE 권한이 없으면 403
        long viewer = accounts.newUser(comp, "viewer9", PW);
        accounts.grant(comp, viewer, "VW", "qms-insp", "SEARCH");
        mvc.perform(json(post(I + "/" + id + "/meas/correct"), body).with(accounts.as(comp, viewer, "viewer9"))).andExpect(status().isForbidden());
        // 판정 확정 뒤에는 거부
        meas(id, a, v(2, "10.00", gOk) + "," + v(3, "10.00", gOk) + "," + v(4, "10.00", gOk) + "," + v(5, "10.00", gOk)).andExpect(status().isOk());
        meas(id, lineId(id, 20), a(1, "Y") + "," + a(2, "Y") + "," + a(3, "Y") + "," + a(4, "Y") + "," + a(5, "Y")).andExpect(status().isOk());
        judge(id).andExpect(status().isOk());
        correct(id, m2, v(1, "10.03", gOk)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_INSP_STATE"));
    }

    ResultActions correct(long insp, long measId, String entry) throws Exception {
        return postJson(I + "/" + insp + "/meas/correct", "{\"measId\":" + measId + ",\"entry\":" + entry + "}");
    }

    @Test
    void 계획_행_분류와_증빙은_저장_조회_새_리비전에_남고_안_보내면_NONE_틀린_값은_거부() throws Exception {
        long p = plan("FINAL", "RM1", null, null);
        accounts.exec(comp, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, scale, created_by, updated_by)
                select ?, 'PRODUCT', item_id, '1', '외관', 'ATTRIBUTE', 0, 't', 't' from mst_item where item_cd = 'RM1'""", comp);
        long rmChar = charId(comp, "RM1", null, "1");
        String withEvidence = withExtra(line("a", null, 10, rmChar, "S5", "VISUAL"), "\"grpNm\":\" 외관 \",\"evidenceReq\":\"photo\"");
        lines(p, withEvidence).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select grp_nm || ':' || evidence_req from qms_insp_plan_line where insp_plan_id = ?", String.class, p))
                .isEqualTo("외관:PHOTO");
        mvc.perform(get(IP + "/" + p).with(admin)).andExpect(jsonPath("$.data.lines[0].grpNm").value("외관"))
                .andExpect(jsonPath("$.data.lines[0].evidenceReq").value("PHOTO"));
        // 틀린 증빙 값 → 칸 오류, 저장된 값 그대로
        long lineId = lineId(p, rmChar);
        lines(p, withExtra(line("a", lineId, 10, rmChar, "S5", "VISUAL"), "\"evidenceReq\":\"VIDEO\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("evidenceReq"))
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        assertThat(accounts.query(comp, "select evidence_req from qms_insp_plan_line where line_id = ?", String.class, lineId)).isEqualTo("PHOTO");
        // 직접 SQL 도 CHECK 가 막는다
        constraint(comp, "ck_qms_insp_plan_line_evidence",
                () -> accounts.exec(comp, "update qms_insp_plan_line set evidence_req = 'VIDEO' where line_id = ?", lineId));
        constraint(comp, "ck_qms_insp_plan_line_grp",
                () -> accounts.exec(comp, "update qms_insp_plan_line set grp_nm = '  ' where line_id = ?", lineId));
        // 확정 → 새 리비전에 그대로 복사
        action(p, "release").andExpect(status().isOk());
        long p2 = id(postJson(IP + "/" + p + "/new-revision", "{\"rowVer\":" + rowVer(p) + "}"));
        assertThat(accounts.query(comp, "select grp_nm || ':' || evidence_req from qms_insp_plan_line where insp_plan_id = ?", String.class, p2))
                .isEqualTo("외관:PHOTO");
        // 분류·증빙을 안 보내는 예전 호출 → 분류 없음·NONE 으로 저장
        long l2 = lineId(p2, rmChar);
        lines(p2, line("a", l2, 10, rmChar, "S5", "VISUAL")).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select coalesce(grp_nm, '-') || ':' || evidence_req from qms_insp_plan_line where line_id = ?", String.class, l2))
                .isEqualTo("-:NONE");
        // A사 계획을 B사 계정으로 — 보이지 않고 못 고친다
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-insp-plan", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(IP + "/" + p).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(IP + "/" + p2 + "/lines?rowVer=" + rowVer(p2)), "{\"lines\":[" + withEvidence + "]}").with(b)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select evidence_req from qms_insp_plan_line where line_id = ?", String.class, l2)).isEqualTo("NONE");
    }

    /** 행 JSON 끝에 칸을 더한다 */
    static String withExtra(String lineJson, String fields) {
        return lineJson.substring(0, lineJson.length() - 1) + "," + fields + "}";
    }
}
