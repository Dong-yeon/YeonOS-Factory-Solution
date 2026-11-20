package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
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
 * QMS Q1 테스트 공통 데이터 (설정은 PostgresTestBase 그대로 — 컨텍스트 하나).
 * 품목 FG1(클래스 없음)·RM1·RM2(클래스 COIL), 공정 DRAW·IQC, 특성 FG1-1(계량 SC)·FG1-2(계수)·DRAW-1(공정 계량)·IQC-1(공정 계수),
 * 가짜 AQL 표 FAKE(SamplingPlanTest 와 같은 값, 보통 1.0 만), 샘플링 S5(고정 5)·SALL(전수)·SAQL(FAKE II 보통 1.0).
 */
@AutoConfigureMockMvc
abstract class QmsTestBase extends PostgresTestBase {

    static final String PW = "Maple-anchor-63";
    static final String SP = "/api/qms/sampling-plans";
    static final String IP = "/api/qms/insp-plans";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;
    LocalDate today;
    long fgChar1;
    long fgChar2;
    long drawChar;
    long iqcChar;

    @BeforeEach
    void baseSetUp() {
        comp = accounts.newComp("QM");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "QA", "qms-sampling", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(comp, userId, "QA", "qms-insp-plan", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        fixtures(comp);
        fgChar1 = charId(comp, "FG1", null, "1");
        fgChar2 = charId(comp, "FG1", null, "2");
        drawChar = charId(comp, null, "DRAW", "1");
        iqcChar = charId(comp, null, "IQC", "1");
    }

    /** 회사 하나에 같은 기준 데이터 (A사·B사 비교용으로 다시 부른다) */
    void fixtures(String c) {
        accounts.exec(c, """
                insert into mst_item_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'COIL', '코일', 't', 't')""", c);
        for (String[] it : new String[][] {{"FG1", null}, {"RM1", "COIL"}, {"RM2", "COIL"}}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, item_class_id, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', (select class_id from mst_item_class where class_cd = ?), 't', 't')""",
                    c, it[0], it[0] + " 품목", it[1]);
        }
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'DRAW', '드로잉', 'PRESS', 't', 't'), (?, 'IQC', '수입 검사', 'INSPECT', 't', 't')""", c, c);
        accounts.exec(c, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, unit_nm, scale, lsl, nominal, usl,
                                      special_cls, created_by, updated_by)
                select ?, 'PRODUCT', item_id, '1', '홀 지름', 'VARIABLE', 'mm', 2, 9.95, 10.00, 10.05, 'SC', 't', 't'
                  from mst_item where item_cd = 'FG1'""", c);
        accounts.exec(c, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, scale, created_by, updated_by)
                select ?, 'PRODUCT', item_id, '2', '외관', 'ATTRIBUTE', 0, 't', 't' from mst_item where item_cd = 'FG1'""", c);
        accounts.exec(c, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, unit_nm, scale, lsl, usl, created_by, updated_by)
                select ?, 'PROCESS', proc_id, '1', '쿠션 압력', 'VARIABLE', 'MPa', 1, 1.0, 2.0, 't', 't' from mst_proc where proc_cd = 'DRAW'""", c);
        accounts.exec(c, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, scale, created_by, updated_by)
                select ?, 'PROCESS', proc_id, '1', '녹 없음', 'ATTRIBUTE', 0, 't', 't' from mst_proc where proc_cd = 'IQC'""", c);
        accounts.exec(c, """
                insert into qms_aql_table (comp_cd, std_cd, insp_level, lot_from, lot_to, code_letter, created_by, updated_by)
                values (?, 'FAKE', 'II', 1, 8, 'A', 't', 't'), (?, 'FAKE', 'II', 9, 50, 'B', 't', 't'),
                       (?, 'FAKE', 'II', 51, 500, 'C', 't', 't'), (?, 'FAKE', 'II', 501, null, 'D', 't', 't')""", c, c, c, c);
        accounts.exec(c, """
                insert into qms_aql_plan_row (comp_cd, std_cd, severity, code_letter, aql, sample_size, ac, re, created_by, updated_by)
                values (?, 'FAKE', 'NORMAL', 'A', 1.0, 2, 0, 1, 't', 't'), (?, 'FAKE', 'NORMAL', 'B', 1.0, 8, 0, 1, 't', 't'),
                       (?, 'FAKE', 'NORMAL', 'C', 1.0, 32, 1, 2, 't', 't'), (?, 'FAKE', 'NORMAL', 'D', 1.0, 80, 2, 3, 't', 't')""",
                c, c, c, c);
        accounts.exec(c, """
                insert into qms_sampling_plan (comp_cd, plan_cd, plan_nm, method, fixed_n, std_cd, insp_level, severity, aql, ac, re,
                                               created_by, updated_by)
                values (?, 'S5', '고정 5', 'FIXED', 5, null, null, null, null, 0, 1, 't', 't'),
                       (?, 'SALL', '전수', 'ALL', null, null, null, null, null, 0, 1, 't', 't'),
                       (?, 'SAQL', 'AQL 1.0', 'AQL', null, 'FAKE', 'II', 'NORMAL', 1.0, null, null, 't', 't')""", c, c, c);
    }

    long charId(String c, String itemCd, String procCd, String no) {
        return itemCd != null
                ? accounts.query(c, "select char_id from qms_char ch join mst_item i on i.item_id = ch.item_id where i.item_cd = ? and char_no = ?",
                        Long.class, itemCd, no)
                : accounts.query(c, "select char_id from qms_char ch join mst_proc p on p.proc_id = ch.proc_id "
                        + "where p.proc_cd = ? and ch.item_id is null and char_no = ?", Long.class, procCd, no);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    /** 전자서명 토큰(docs/36 16장) — 같은 세션으로 업무 요청을 보내야 쓸 수 있다 */
    String esignToken(MockHttpSession s, String meaning, String type, long target) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/platform/esign/token"), "{\"meaningCd\":\"" + meaning + "\",\"targetType\":\"" + type
                + "\",\"targetId\":" + target + ",\"password\":\"" + PW + "\"}").with(admin).session(s)).andExpect(status().isOk()).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.token");
    }

    ResultActions postJson(MockHttpSession s, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin).session(s));
    }

    void policy(String key, String value) {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')",
                comp, key, value);
    }

    long esignCount(String type, long target) {
        return accounts.query(comp, "select count(*) from sys_esign where target_type = ? and target_id = ?", Long.class, type, target);
    }

    ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(admin));
    }

    static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    static String q(Object v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    /** 새 검사계획: 유형, 품목 코드, 클래스 코드, 공정 코드 */
    long plan(String type, String itemCd, String classCd, String procCd) throws Exception {
        return id(postJson(IP, "{\"inspType\":" + q(type) + ",\"itemCd\":" + q(itemCd) + ",\"classCd\":" + q(classCd)
                + ",\"procCd\":" + q(procCd) + "}"));
    }

    int rowVer(long planId) throws Exception {
        MvcResult m = mvc.perform(get(IP + "/" + planId).with(admin)).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.plan.rowVer");
    }

    ResultActions lines(long planId, String... lines) throws Exception {
        return putJson(IP + "/" + planId + "/lines?rowVer=" + rowVer(planId), "{\"lines\":[" + String.join(",", lines) + "]}");
    }

    static String line(String key, Long lineId, int no, Long charId, String sampling, String gauge) {
        return line(key, lineId, no, charId, sampling, gauge, null, null);
    }

    static String line(String key, Long lineId, int no, Long charId, String sampling, String gauge, String freq, Integer freqN) {
        return "{\"rowKey\":\"" + key + "\",\"lineId\":" + lineId + ",\"lineNo\":" + no + ",\"charId\":" + charId
                + ",\"samplingPlanCd\":" + q(sampling) + ",\"gaugeClass\":" + q(gauge) + ",\"freqType\":" + q(freq)
                + ",\"freqN\":" + freqN + ",\"methodNm\":\"측정\",\"reactionPlan\":\"격리\"}";
    }

    ResultActions action(long planId, String act) throws Exception {
        return postJson(IP + "/" + planId + "/" + act, "{\"rowVer\":" + rowVer(planId) + "}");
    }

    long lineId(long planId, long charId) {
        return accounts.query(comp, "select line_id from qms_insp_plan_line where insp_plan_id = ? and char_id = ?", Long.class,
                planId, charId);
    }

    void constraint(String c, String name, Runnable sql) {
        Throwable t = catchThrowable(sql::run);
        assertThat(t).as("DB 가 막아야 한다: " + name).isNotNull();
        assertThat(PgErrors.constraint(t)).as(String.valueOf(t)).contains(name);
    }

    void sqlState(String state, Runnable sql) {
        Throwable t = catchThrowable(sql::run);
        assertThat(t).as("DB 가 막아야 한다: " + state).isNotNull();
        assertThat(PgErrors.sqlState(t)).as(String.valueOf(t)).contains(state);
    }
}
