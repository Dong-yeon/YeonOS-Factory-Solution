package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * OCAP 표준 조치 목록 (docs/29_P3 26장) — 목록 등록·고치기(row_ver)·사용 중지, SPC 위반 ACTION 에서 고르기(사본·순서),
 * 목록을 고쳐도 옛 기록 불변, 고르기 거부(사용 중지·다른 규칙·B사·ACK·중복·개수), 고르는 순간 동시 수정, 권한, 직접 SQL.
 */
class OcapTest extends QmsTestBase {

    static final String O = "/api/qms/ocaps";
    static final String S = "/api/qms/spc";
    static final String E = "/api/qms/spc/events";
    static final String I = "/api/qms/insps";
    static final String G = "/api/qms/gauges";
    static final String[] BASE = {"9.98", "10.00", "10.02", "10.00", "10.00"};
    /** WE1·WE2 (SpcEventTest 와 같은 값) */
    static final String[] HIGH = {"10.03", "10.05", "10.07", "10.05", "10.05"};

    long gauge;
    long item;
    int lotSeq;

    @BeforeEach
    void ocapSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-gauge-calib", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "qms-spc", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-ocap", "SEARCH", "SAVE");
        accounts.exec(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, 'GOC', 'GAUGE', 'OCAP 캘리퍼', 'CALIPER', 365, 't', 't')""", comp);
        gauge = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'GOC'", Long.class);
        postJson(G + "/" + gauge + "/calibs", "{\"calibDate\":" + q(today.minusDays(10)) + ",\"result\":\"OK\",\"agency\":\"INTERNAL\"}")
                .andExpect(status().isOk());
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
    }

    long insp(String... values) throws Exception {
        String no = "L-OC-" + (++lotSeq);
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 50, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1'""", comp, no);
        long id = id(postJson(I, "{\"lotNo\":" + q(no) + ",\"inspType\":\"FINAL\"}"));
        StringBuilder v = new StringBuilder();
        StringBuilder a = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            v.append(i == 0 ? "" : ",").append("{\"sampleNo\":").append(i + 1).append(",\"value\":").append(values[i])
                    .append(",\"gaugeAssetId\":").append(gauge).append("}");
            a.append(i == 0 ? "" : ",").append("{\"sampleNo\":").append(i + 1).append(",\"okYn\":\"Y\"}");
        }
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id, 10) + ",\"entries\":[" + v + "]}").andExpect(status().isOk());
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id, 20) + ",\"entries\":[" + a + "]}").andExpect(status().isOk());
        postJson(I + "/" + id + "/judge", "{\"rowVer\":" + accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, id)
                + "}").andExpect(status().isOk());
        return id;
    }

    long line(long insp, int no) {
        return accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ? and line_no = ?", Long.class, insp, no);
    }

    /** 기준 20군 + 고정 한계 → 위반 검사 하나 → 그 검사의 WE1 이벤트 id */
    long we1Event() throws Exception {
        for (int i = 0; i < 20; i++) {
            insp(BASE);
        }
        id(postJson(S + "/limits", "{\"itemId\":" + item + ",\"charId\":" + fgChar1 + ",\"inspType\":\"FINAL\"}"));
        long insp = insp(HIGH);
        return accounts.query(comp, "select event_id from qms_spc_event where insp_id = ? and rule_cd = 'WE1'", Long.class, insp);
    }

    long ocap(String rule, int seq, String text) throws Exception {
        return id(postJson(O, "{\"ruleCd\":" + q(rule) + ",\"seq\":" + seq + ",\"actionText\":" + q(text) + "}"));
    }

    int ver(long ocap) {
        return accounts.query(comp, "select row_ver from qms_ocap_item where ocap_id = ?", Integer.class, ocap);
    }

    ResultActions edit(long ocap, String rule, int seq, String text, String use, int rowVer) throws Exception {
        return putJson(O + "/" + ocap, "{\"ruleCd\":" + q(rule) + ",\"seq\":" + seq + ",\"actionText\":" + q(text) + ",\"useYn\":" + q(use)
                + ",\"rowVer\":" + rowVer + "}");
    }

    ResultActions pick(long event, String ids) throws Exception {
        return postJson(E + "/" + event + "/acts", "{\"actType\":\"ACTION\",\"note\":\"현장 조치\",\"ocapIds\":" + ids + "}");
    }

    /** 이벤트의 고른 사본 "문구" 목록(처리 행·고른 순) */
    List<String> picks(long event) {
        String s = accounts.query(comp, """
                select coalesce(string_agg(p.action_text, '|' order by a.act_seq, p.act_ocap_id), '') from qms_spc_event_act_ocap p
                  join qms_spc_event_act a on a.act_id = p.act_id where a.event_id = ?""", String.class, event);
        return s.isEmpty() ? List.of() : List.of(s.split("\\|"));
    }

    long acts(long event) {
        return accounts.query(comp, "select count(*) from qms_spc_event_act where event_id = ?", Long.class, event);
    }

    @Test
    void 목록_고르기는_규칙과_공통_사본은_고친_뒤에도_그때_문구() throws Exception {
        long a = ocap("WE1", 1, "같은 LOT 5개 재측정");
        long b = ocap("WE1", 2, "설비 조건 확인");
        long c = ocap(null, 0, "측정기·측정 방법 재확인");
        long d = ocap("R1", 1, "공구 마모 점검");
        // 목록: 전체 4, WE1 2, 공통만 1, 문구 찾기
        mvc.perform(get(O + "/grid").param("total", "true").with(admin)).andExpect(jsonPath("$.total").value(4));
        mvc.perform(get(O + "/grid").param("ruleCd", "WE1").with(admin)).andExpect(jsonPath("$.list[*].ocapId", Matchers.contains((int) a, (int) b)));
        mvc.perform(get(O + "/grid").param("ruleCd", "*").with(admin)).andExpect(jsonPath("$.list[*].ruleKey", Matchers.contains("*")));
        mvc.perform(get(O + "/grid").param("keyword", "공구").with(admin)).andExpect(jsonPath("$.list[*].ocapId", Matchers.contains((int) d)));
        long ev = we1Event();
        // 고르기 목록 = 공통 먼저 + 그 규칙(R1 항목 없음)
        mvc.perform(get(E + "/" + ev + "/ocaps").with(admin)).andExpect(jsonPath("$.data[*].ocapId", Matchers.contains((int) c, (int) a, (int) b)));
        // 순서를 섞어 보내도 사본은 목록 순서
        pick(ev, "[" + b + "," + c + "," + a + "]").andExpect(status().isOk());
        assertThat(picks(ev)).containsExactly("측정기·측정 방법 재확인", "같은 LOT 5개 재측정", "설비 조건 확인");
        mvc.perform(get(E + "/" + ev).with(admin)).andExpect(jsonPath("$.data.acts[0].ocaps[*].actionText",
                Matchers.contains("측정기·측정 방법 재확인", "같은 LOT 5개 재측정", "설비 조건 확인")));
        // 목록 수정·사용 중지 → 옛 기록 그대로, 새 고르기 목록에는 바뀐 문구·중지 항목 없음
        edit(a, "WE1", 1, "같은 LOT 10개 재측정", "Y", ver(a)).andExpect(status().isOk());
        edit(b, "WE1", 2, "설비 조건 확인", "N", ver(b)).andExpect(status().isOk());
        assertThat(picks(ev)).containsExactly("측정기·측정 방법 재확인", "같은 LOT 5개 재측정", "설비 조건 확인");
        mvc.perform(get(E + "/" + ev + "/ocaps").with(admin)).andExpect(jsonPath("$.data[*].actionText",
                Matchers.contains("측정기·측정 방법 재확인", "같은 LOT 10개 재측정")));
        // 두 번째 조치: 바뀐 문구가 사본으로
        pick(ev, "[" + a + "]").andExpect(status().isOk());
        assertThat(picks(ev)).last().isEqualTo("같은 LOT 10개 재측정");
        // 낙관적 락·중복 문구(같은 규칙 사용 중끼리만)·삭제 API 없음
        edit(a, "WE1", 1, "또 바꿈", "Y", 0).andExpect(status().isConflict());
        postJson(O, "{\"ruleCd\":\"WE1\",\"actionText\":\"같은 LOT 10개 재측정\"}").andExpect(jsonPath("$.errors[0].field").value("actionText"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        ocap("WE2", 0, "같은 LOT 10개 재측정");                // 다른 규칙이면 같은 문구 가능
        ocap("WE1", 3, "설비 조건 확인");                     // 중지된 문구와 같은 새 항목 가능
        mvc.perform(post(O + "/" + a + "/delete").with(admin).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is4xxClientError());
        // 화면이 없는 이상 입력: 모르는 규칙·공백 문구·501자·순서 범위
        postJson(O, "{\"ruleCd\":\"WE9\",\"seq\":100000,\"actionText\":\"   \"}").andExpect(jsonPath("$.errors[*].field",
                Matchers.containsInAnyOrder("ruleCd", "seq", "actionText")));
        postJson(O, "{\"actionText\":\"" + "가".repeat(501) + "\"}").andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
    }

    @Test
    void 고르기_거부는_처리_행도_안_남는다_사용중지_다른규칙_ACK_중복_개수_종결뒤() throws Exception {
        long a = ocap("WE1", 1, "재측정");
        long off = ocap("WE1", 2, "중지될 항목");
        edit(off, "WE1", 2, "중지될 항목", "N", ver(off)).andExpect(status().isOk());
        long r1 = ocap("R1", 1, "공구 점검");
        long ev = we1Event();
        pick(ev, "[" + a + "," + off + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ocapIds[1]")).andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        pick(ev, "[" + r1 + "]").andExpect(jsonPath("$.errors[0].code").value("OCAP_RULE"));
        pick(ev, "[" + a + "," + a + "]").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        pick(ev, "[" + a + ",null]").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        pick(ev, "[999999999]").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            many.add(i);
        }
        pick(ev, many.toString()).andExpect(jsonPath("$.errors[0].code").value("TOO_MANY_ITEMS"));
        postJson(E + "/" + ev + "/acts", "{\"actType\":\"ACK\",\"ocapIds\":[" + a + "]}").andExpect(jsonPath("$.errors[0].field").value("ocapIds"));
        postJson(E + "/" + ev + "/acts", "{\"actType\":\"CLOSE\",\"note\":\"끝\",\"ocapIds\":[" + a + "]}").andExpect(status().isBadRequest());
        assertThat(acts(ev)).isZero();
        assertThat(picks(ev)).isEmpty();
        // 메모는 여전히 필수(Q-ob)
        postJson(E + "/" + ev + "/acts", "{\"actType\":\"ACTION\",\"ocapIds\":[" + a + "]}").andExpect(jsonPath("$.errors[0].field").value("note"));
        assertThat(acts(ev)).isZero();
        // 빈 목록·null 은 고르지 않은 것(기존 동작 그대로)
        postJson(E + "/" + ev + "/acts", "{\"actType\":\"ACTION\",\"note\":\"메모만\",\"ocapIds\":[]}").andExpect(status().isOk());
        postJson(E + "/" + ev + "/acts", "{\"actType\":\"CLOSE\",\"note\":\"종결\"}").andExpect(status().isOk());
        pick(ev, "[" + a + "]").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_SPC_EVENT_STATE"));
        assertThat(picks(ev)).isEmpty();
    }

    @Test
    void D_고르는_순간_다른_트랜잭션이_문구를_고치면_기다렸다_고친_문구_하나로() throws Exception {
        long a = ocap("WE1", 1, "옛 문구");
        long ev = we1Event();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("select set_config('app.comp_cd', ?, true)")) {
                ps.setString(1, comp);
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("update qms_ocap_item set action_text = '새 문구', row_ver = row_ver + 1 where ocap_id = ?")) {
                ps.setLong(1, a);
                ps.executeUpdate();
            }
            Future<Integer> f = pool.submit(() -> pick(ev, "[" + a + "]").andReturn().getResponse().getStatus());
            Thread.sleep(800);
            assertThat(f.isDone()).as("고르기는 수정이 끝날 때까지 기다린다(FOR SHARE)").isFalse();
            c.commit();
            assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(picks(ev)).containsExactly("새 문구");
    }

    /**
     * QA O-a(2026-10-27): 고르기가 항목 잠금(FOR SHARE)을 기다리는 사이 다른 사람이 종결 — 시작 때 본 OPEN 이 아니라 처리 행 가드(V157)에 막히는데,
     * 응답도 화면 사전 확인과 같은 409 QMS_SPC_EVENT_STATE(일반 CONFLICT 아님). 처리 행·사본 없음
     */
    @Test
    void 고르기가_기다리는_사이_종결되면_같은_상태_오류() throws Exception {
        long a = ocap("WE1", 1, "재측정");
        long ev = we1Event();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("select set_config('app.comp_cd', ?, true)")) {
                ps.setString(1, comp);
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("select 1 from qms_ocap_item where ocap_id = ? for update")) {
                ps.setLong(1, a);
                ps.execute();
            }
            Future<MvcResult> f = pool.submit(() -> pick(ev, "[" + a + "]").andReturn());
            Thread.sleep(800);
            assertThat(f.isDone()).as("고르기는 항목 잠금을 기다린다").isFalse();
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by) values (?, ?, 'CLOSE', '다른 사람 종결', 'closer')")) {
                ps.setString(1, comp);
                ps.setLong(2, ev);
                ps.executeUpdate();
            }
            c.commit();
            MvcResult r = f.get(30, TimeUnit.SECONDS);
            assertThat(r.getResponse().getStatus()).isEqualTo(409);
            assertThat(r.getResponse().getContentAsString()).contains("\"code\":\"QMS_SPC_EVENT_STATE\"");
        } finally {
            pool.shutdownNow();
        }
        assertThat(acts(ev)).isEqualTo(1);
        assertThat(picks(ev)).isEmpty();
        assertThat(accounts.query(comp, "select status || ':' || last_by from qms_spc_event_state where event_id = ?", String.class, ev))
                .isEqualTo("CLOSED:closer");
    }

    @Test
    void 권한_A사로_B사_직접SQL() throws Exception {
        long a = ocap("WE1", 1, "재측정");
        // 권한: OCAP 화면 권한 없으면 목록·저장 403, SPC 조회만이면 고르기 목록은 보이고 조치는 403
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "QV", "qms-spc", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        mvc.perform(get(O + "/grid").with(vw)).andExpect(status().isForbidden());
        mvc.perform(json(post(O), "{\"actionText\":\"x\"}").with(vw)).andExpect(status().isForbidden());
        long ev = we1Event();
        mvc.perform(get(E + "/" + ev + "/ocaps").with(vw)).andExpect(jsonPath("$.data[*].ocapId", Matchers.contains((int) a)));
        mvc.perform(json(post(E + "/" + ev + "/acts"), "{\"actType\":\"ACTION\",\"note\":\"x\",\"ocapIds\":[" + a + "]}").with(vw))
                .andExpect(status().isForbidden());
        long ocapViewer = accounts.newUser(comp, "oview1", PW);
        accounts.grant(comp, ocapViewer, "QO", "qms-ocap", "SEARCH");
        mvc.perform(json(put(O + "/" + a), "{\"actionText\":\"x\",\"rowVer\":0}").with(accounts.as(comp, ocapViewer, "oview1")))
                .andExpect(status().isForbidden());
        // A사로 B사: B사 항목을 A사 위반에 고르기 → 찾을 수 없음, B사 목록에 A사 항목 없음, B사가 A사 항목 고치기 → 404
        String other = accounts.newComp("QO");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-ocap", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        long bItem = id(mvc.perform(json(post(O), "{\"ruleCd\":\"WE1\",\"actionText\":\"B사 조치\"}").with(b)));
        mvc.perform(get(O + "/grid").param("total", "true").with(b)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(json(put(O + "/" + a), "{\"ruleCd\":\"WE1\",\"actionText\":\"남의 것\",\"useYn\":\"N\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        pick(ev, "[" + bItem + "]").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        assertThat(acts(ev)).isZero();
        assertThat(accounts.query(comp, "select action_text || ':' || use_yn from qms_ocap_item where ocap_id = ?", String.class, a)).isEqualTo("재측정:Y");
        // 직접 SQL
        pick(ev, "[" + a + "]").andExpect(status().isOk());
        sqlState("42501", () -> accounts.exec(comp, "update qms_spc_event_act_ocap set action_text = '고침'"));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_spc_event_act_ocap"));
        constraint(comp, "ck_qms_ocap_item_no_delete", () -> accounts.exec(comp, "delete from qms_ocap_item where ocap_id = ?", a));
        constraint(comp, "ck_qms_ocap_item_text", () -> accounts.exec(comp,
                "insert into qms_ocap_item (comp_cd, action_text, created_by, updated_by) values (?, '  ', 't', 't')", comp));
        constraint(comp, "ck_qms_ocap_item_rule", () -> accounts.exec(comp,
                "insert into qms_ocap_item (comp_cd, rule_cd, action_text, created_by, updated_by) values (?, 'WE9', 'x', 't', 't')", comp));
        sqlState("42501", () -> accounts.exec(comp,
                "insert into qms_ocap_item (comp_cd, action_text, created_by, updated_by) values (?, 'x', 't', 't')", other));
        long ack = accounts.query(comp, "insert into qms_spc_event_act (comp_cd, event_id, act_type, acted_by) values (?, ?, 'ACK', 't') returning act_id",
                Long.class, comp, ev);
        constraint(comp, "ck_qms_spc_event_act_ocap_action", () -> accounts.exec(comp,
                "insert into qms_spc_event_act_ocap (comp_cd, act_id, ocap_id, action_text, created_by) values (?, ?, ?, 'x', 't')", comp, ack, a));
        long actionAct = accounts.query(comp, "select act_id from qms_spc_event_act where event_id = ? and act_type = 'ACTION'", Long.class, ev);
        constraint(comp, "fk_qms_spc_event_act_ocap_item_comp", () -> accounts.exec(comp,
                "insert into qms_spc_event_act_ocap (comp_cd, act_id, ocap_id, action_text, created_by) values (?, ?, ?, 'x', 't')", comp, actionAct, bItem));
        assertThat(accounts.query(other, "select count(*) from qms_spc_event_act_ocap", Long.class)).isZero();
    }
}
