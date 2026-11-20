package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.plm.link.EcoQualityView;
import io.smarthappy.module.plm.link.EcoQualityView.Summary;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * P3 QMS 2부 Q7 초품(셋업 검증) — 첫 LOT(FIRST_LOT)만 대상, 근거 = 판정 확정 검사(결과 복사), 정정 = 무효 뒤 다시, 요약 계약 ③ 초품 수
 * + 이상 사용(A~K: A사로 B사, 직접 SQL, 입력, 권한).
 */
class SetupApiTest extends QmsTestBase {

    static final String S = "/api/qms/setups";
    static final String I = "/api/qms/insps";

    @Autowired
    TenantRunner runner;

    @Autowired
    EcoQualityView qualityView;

    long eco;
    long l1;
    long l2;
    long lWin;

    @BeforeEach
    void setupSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-setup", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "plm-eco", "SEARCH");
        // 출하검사 확정본: FG1-2 계수(육안, 고정 5)
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("b", null, 10, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        eco = releasedEco(comp, userId, "ECO-D-1");
        l1 = lot(comp, "L-1");
        l2 = lot(comp, "L-2");
        lWin = lot(comp, "L-W");
        mark(comp, eco, l1, "FIRST_LOT", 1);
        mark(comp, eco, l2, "FIRST_LOT", 2);
        mark(comp, eco, lWin, "4M_WINDOW", 3);
    }

    /** 설계 변경 ECO(적용됨, FG1 품목 대상) */
    long releasedEco(String c, long owner, String no) {
        long e = accounts.query(c, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, ?, '도면 개정', 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, 't', 't') returning eco_id""", Long.class, c, no, owner);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG1'), 't', 't')""", c, e);
        accounts.exec(c, "update plm_eco set status = 'PENDING' where eco_id = ?", e);
        accounts.exec(c, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", e);
        accounts.exec(c, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", e);
        return e;
    }

    long lot(String c, String no) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'REWORK', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1' returning lot_id""", Long.class, c, no);
    }

    void mark(String c, long e, long lot, String reason, int seq) {
        accounts.exec(c, """
                insert into plm_eco_lot (comp_cd, eco_id, lot_id, mark_reason, seq_no, occurred_at, recorded_by)
                values (?, ?, ?, ?, ?, now(), 't')""", c, e, lot, reason, seq);
    }

    /** 그 LOT 출하검사 — ok 면 5개 OK, 아니면 NG 하나로 확정. judge = false 면 열어 두기만 */
    long inspected(String lotNo, boolean ok, boolean judge) throws Exception {
        long id = id(postJson(I, "{\"lotNo\":" + q(lotNo) + ",\"inspType\":\"FINAL\"}"));
        long line = accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ?", Long.class, id);
        String entries = ok ? "{\"sampleNo\":1,\"okYn\":\"Y\"},{\"sampleNo\":2,\"okYn\":\"Y\"},{\"sampleNo\":3,\"okYn\":\"Y\"},{\"sampleNo\":4,\"okYn\":\"Y\"},{\"sampleNo\":5,\"okYn\":\"Y\"}"
                : "{\"sampleNo\":1,\"okYn\":\"N\"}";
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line + ",\"entries\":[" + entries + "]}").andExpect(status().isOk());
        if (judge) {
            int ver = accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, id);
            postJson(I + "/" + id + "/judge", "{\"rowVer\":" + ver + "}").andExpect(status().isOk());
        }
        return id;
    }

    ResultActions record(long lot, long insp) throws Exception {
        return postJson(S + "/" + eco + "/verdicts", "{\"lotId\":" + lot + ",\"inspId\":" + insp + ",\"note\":\"치수 이상 없음\"}");
    }

    int verVer(long verif) {
        return accounts.query(comp, "select row_ver from qms_setup_verif where verif_id = ?", Integer.class, verif);
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 첫LOT_만_대상_근거검사_결과복사_무효뒤_다시_요약계약_초품수() throws Exception {
        long i1 = inspected("L-1", true, true);
        long i2 = inspected("L-2", false, true);
        mvc.perform(get(S + "/" + eco).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eco.ecoNo").value("ECO-D-1"))
                .andExpect(jsonPath("$.data.lots.length()").value(2))
                .andExpect(jsonPath("$.data.lots[0].lotNo").value("L-1"))
                .andExpect(jsonPath("$.data.lots[0].candidates[0].inspId").value((int) i1))
                .andExpect(jsonPath("$.data.pending").value(2));
        // 4M 세부 없는 설계 변경도 첫 LOT 이 있으면 요약이 나온다
        Summary before = runner.call(comp, () -> qualityView.summary(eco)).orElseThrow();
        assertThat(before.hasChange()).isFalse();
        assertThat(before.firstPending()).isEqualTo(2);
        long v1 = id(record(l1, i1));
        long v2 = id(record(l2, i2));
        assertThat(accounts.query(comp, "select string_agg(result, ',' order by verif_id) from qms_setup_verif", String.class)).isEqualTo("OK,NG");
        mvc.perform(get(S + "/" + eco).with(admin)).andExpect(jsonPath("$.data.ok").value(1)).andExpect(jsonPath("$.data.ng").value(1))
                .andExpect(jsonPath("$.data.pending").value(0)).andExpect(jsonPath("$.data.lots[1].verdict.inspNo").exists());
        mvc.perform(get("/api/plm/ecos/" + eco).with(admin)).andExpect(jsonPath("$.data.quality.firstOk").value(1))
                .andExpect(jsonPath("$.data.quality.firstNg").value(1)).andExpect(jsonPath("$.data.quality.firstPending").value(0));
        mvc.perform(get(S).with(admin)).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].ecoNo").value("ECO-D-1"));
        // 이미 판정 → 409, 무효 → 다시(새 OK 검사)
        record(l2, i2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_SETUP_DONE"));
        postJson(S + "/verdicts/" + v2 + "/void", "{\"rowVer\":" + verVer(v2) + ",\"reason\":\"재측정\"}").andExpect(status().isOk());
        postJson(S + "/verdicts/" + v2 + "/void", "{\"rowVer\":" + verVer(v2) + ",\"reason\":\"다시\"}").andExpect(jsonPath("$.code").value("QMS_SETUP_DONE"));
        long i2b = inspected("L-2", true, true);
        record(l2, i2b).andExpect(status().isOk());
        Summary after = runner.call(comp, () -> qualityView.summary(eco)).orElseThrow();
        assertThat(after.firstOk()).isEqualTo(2);
        assertThat(after.firstNg()).isZero();
        assertThat(accounts.query(comp, "select count(*) from qms_setup_verif where lot_id = ?", Integer.class, l2)).as("무효 + 새 판정").isEqualTo(2);
        mvc.perform(get(S + "/by-no").param("ecoNo", "ECO-D-1").with(admin)).andExpect(jsonPath("$.data").value((int) eco));
        assertThat(v1).isPositive();
    }

    // ---------- 이상 사용 ----------

    @Test
    void 입력_이상_첫LOT_아님_근거_틀림_조건() throws Exception {
        long iWin = inspected("L-W", true, true);
        long i1open = inspected("L-1", true, false);
        long i2 = inspected("L-2", true, true);
        postJson(S + "/" + eco + "/verdicts", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(2));
        record(lWin, iWin).andExpect(jsonPath("$.errors[0].code").value("NOT_FIRST_LOT"));
        record(l1, i2).andExpect(jsonPath("$.errors[0].code").value("SETUP_EVIDENCE"));
        record(l1, i1open).andExpect(jsonPath("$.errors[0].code").value("SETUP_EVIDENCE"));
        record(l1, 999999999L).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        postJson(S + "/999999999/verdicts", "{\"lotId\":" + l1 + ",\"inspId\":" + i2 + "}").andExpect(status().isNotFound());
        mvc.perform(get(S + "/999999999").with(admin)).andExpect(status().isNotFound());
        long v = id(record(l2, i2));
        postJson(S + "/verdicts/" + v + "/void", "{\"rowVer\":" + verVer(v) + ",\"reason\":\" \"}").andExpect(jsonPath("$.errors[0].field").value("reason"));
        postJson(S + "/verdicts/" + v + "/void", "{\"rowVer\":9,\"reason\":\"x\"}").andExpect(status().isConflict());
        postJson(S + "/verdicts/" + v + "/void", "{\"reason\":\"x\"}").andExpect(status().isBadRequest());
        postJson(S + "/verdicts/999999999/void", "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test
    void 권한별_403() throws Exception {
        long i1 = inspected("L-1", true, true);
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "qms-setup", "SEARCH");
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "qms-setup", "SEARCH", "SAVE");
        RequestPostProcessor sv = accounts.as(comp, saver, "saver1");
        mvc.perform(get(S + "/" + eco).with(rd)).andExpect(status().isOk());
        mvc.perform(json(post(S + "/" + eco + "/verdicts"), "{\"lotId\":" + l1 + ",\"inspId\":" + i1 + "}").with(rd)).andExpect(status().isForbidden());
        long v = id(mvc.perform(json(post(S + "/" + eco + "/verdicts"), "{\"lotId\":" + l1 + ",\"inspId\":" + i1 + "}").with(sv)));
        mvc.perform(json(post(S + "/verdicts/" + v + "/void"), "{\"rowVer\":0,\"reason\":\"x\"}").with(sv)).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select created_by from qms_setup_verif", String.class)).isEqualTo("saver1");
    }

    @Test
    void B사_사용자는_A사_초품을_보지도_쓰지도_못한다() throws Exception {
        long i1 = inspected("L-1", true, true);
        long v = id(record(l1, i1));
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-setup", "SEARCH", "SAVE", "DELETE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(S).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(S + "/" + eco).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(S + "/" + eco + "/verdicts"), "{\"lotId\":" + l1 + ",\"inspId\":" + i1 + "}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(S + "/verdicts/" + v + "/void"), "{\"rowVer\":0,\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(S + "/by-no").param("ecoNo", "ECO-D-1").with(b)).andExpect(status().isNotFound());
        assertThat(runner.call(other, () -> qualityView.summary(eco))).isEmpty();
        // 직접 SQL: B사 판정에 A사 ECO·LOT·검사
        constraint(other, "fk_qms_setup_verif_eco_comp", () -> accounts.exec(other,
                "insert into qms_setup_verif (comp_cd, eco_id, lot_id, insp_id, result, created_by, updated_by) values (?, ?, ?, ?, 'OK', 't', 't')",
                other, eco, l1, i1));
        assertThat(accounts.exec(other, "update qms_setup_verif set status = 'VOID', void_reason = 'x', voided_at = now(), voided_by = 't' where verif_id = ?", v))
                .isZero();
    }

    @Test
    void 직접SQL_첫LOT_근거_일치_살아있는판정_하나_무효만() throws Exception {
        long i1 = inspected("L-1", true, true);
        long iWin = inspected("L-W", true, true);
        long iNg = inspected("L-2", false, true);
        String ins = "insert into qms_setup_verif (comp_cd, eco_id, lot_id, insp_id, result, created_by, updated_by) values (?, ?, ?, ?, ?, 't', 't')";
        constraint(comp, "ck_qms_setup_verif_first_lot", () -> accounts.exec(comp, ins, comp, eco, lWin, iWin, "OK"));
        constraint(comp, "ck_qms_setup_verif_evidence", () -> accounts.exec(comp, ins, comp, eco, l1, i1, "NG"));
        constraint(comp, "ck_qms_setup_verif_evidence", () -> accounts.exec(comp, ins, comp, eco, l1, iNg, "NG"));
        accounts.exec(comp, ins, comp, eco, l1, i1, "OK");
        constraint(comp, "uq_qms_setup_verif_live", () -> accounts.exec(comp, ins, comp, eco, l1, i1, "OK"));
        long v = accounts.query(comp, "select verif_id from qms_setup_verif", Long.class);
        constraint(comp, "ck_qms_setup_verif_immutable", () -> accounts.exec(comp, "update qms_setup_verif set note = '몰래' where verif_id = ?", v));
        constraint(comp, "ck_qms_setup_verif_void", () -> accounts.exec(comp, "update qms_setup_verif set status = 'VOID' where verif_id = ?", v));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_setup_verif where verif_id = ?", v));
        accounts.exec(comp, "update qms_setup_verif set status = 'VOID', void_reason = 'x', voided_at = now(), voided_by = 't' where verif_id = ?", v);
        constraint(comp, "ck_qms_setup_verif_immutable", () -> accounts.exec(comp,
                "update qms_setup_verif set status = 'ACTIVE', void_reason = null, voided_at = null, voided_by = null where verif_id = ?", v));
        constraint(comp, "ck_qms_setup_verif_active", () -> accounts.exec(comp,
                "insert into qms_setup_verif (comp_cd, eco_id, lot_id, insp_id, result, status, void_reason, voided_at, voided_by, created_by, updated_by) "
                        + "values (?, ?, ?, ?, 'OK', 'VOID', 'x', now(), 't', 't', 't')", comp, eco, l1, i1));
    }
}
