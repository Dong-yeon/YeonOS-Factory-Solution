package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
 * 선후행 FS·SS·FF (docs/32 4.27, 사용자 결정 P-15) — 종류 저장·입력 검사·순환·직접 SQL, 종류별 위반 표시·밀기 미리보기, BLOCK 실적 제약
 * (SS = 시작 때 선행 시작, FF = 완료 때 선행 완료 — 담당자 거부·PM 사유·보이지 않는 사유·기록), 의존 추가 사유(P-b) 종류별, WBS 복사·템플릿이
 * 종류를 가져감, A사로 B사.
 */
@AutoConfigureMockMvc
class PmsDepTypeTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String K = "/api/pms/tasks";
    private static final String T = "/api/pms/templates";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor pm;
    RequestPostProcessor mem;
    LocalDate today;
    long proj;
    long a;
    long b;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PS");
        long pmId = accounts.newUser(comp, "pm1", PW);
        long memId = accounts.newUser(comp, "mem1", PW);
        accounts.grant(comp, pmId, "PM", "pms-project", "SEARCH", "SAVE");
        accounts.grant(comp, pmId, "PMT", "pms-template", "SEARCH", "SAVE");
        accounts.grant(comp, memId, "MEM", "pms-project", "SEARCH", "SAVE");
        pm = accounts.as(comp, pmId, "pm1");
        mem = accounts.as(comp, memId, "mem1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        proj = id(send(post(P), "{\"projNm\":\"ZT 선후행\",\"projType\":\"GENERAL\"}", pm));
        a = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"설계\",\"planStart\":\"" + today.minusDays(10) + "\",\"planEnd\":\""
                + today.minusDays(5) + "\"}", pm));
        b = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"시작품\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today.minusDays(4)
                + "\",\"planEnd\":\"" + today + "\"}", pm));
    }

    private ResultActions send(MockHttpServletRequestBuilder r, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(r.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andReturn();
        String s = m.getResponse().getContentAsString();
        assertThat(m.getResponse().getStatus()).as(s).isEqualTo(200);
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private String view(long p) throws Exception {
        return mvc.perform(get(P + "/" + p + "/tasks").with(pm)).andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> row(long taskId) throws Exception {
        List<Map<String, Object>> rows = JsonPath.read(view(proj), "$.data.tasks[?(@.taskId == " + taskId + ")]");
        return rows.get(0);
    }

    private ResultActions dep(long pred, long succ, String type, int lag, String reason) throws Exception {
        return send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + pred + ",\"succTaskId\":" + succ + ",\"lagDays\":" + lag
                + (type == null ? "" : ",\"depType\":\"" + type + "\"") + (reason == null ? "" : ",\"depReason\":\"" + reason + "\"") + "}", pm);
    }

    private void policy(String value) {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'PMS_DEP_ENFORCE', ?, 't', 't')
                on conflict do nothing""", comp, value);
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = 'PMS_DEP_ENFORCE' and plant_cd = '*'", value);
    }

    /** 작업 실적 저장(상세 폼) — 계획·담당 그대로, actEnd null 이면 진행 */
    private ResultActions actual(long taskId, String nm, String owner, LocalDate s, LocalDate e, RequestPostProcessor who, String depReason)
            throws Exception {
        Map<String, Object> r = row(taskId);
        return send(put(K + "/" + taskId), "{\"taskNm\":\"" + nm + "\"" + (owner == null ? "" : ",\"ownerLoginId\":\"" + owner + "\"")
                + ",\"planStart\":\"" + r.get("planStart") + "\",\"planEnd\":\"" + r.get("planEnd") + "\",\"actStart\":\"" + s + "\""
                + (e == null ? ",\"progressPct\":10" : ",\"actEnd\":\"" + e + "\"") + ",\"rowVer\":" + r.get("rowVer")
                + (depReason == null ? "" : ",\"depReason\":\"" + depReason + "\"") + "}", who);
    }

    private long waives(long taskId) {
        return accounts.query(comp, "select count(*) from pms_task_dep_waive where task_id = ?", Long.class, taskId);
    }

    @Test
    void 종류_저장_입력검사_순환_직접SQL() throws Exception {
        long d = id(dep(a, b, "ss", 1, null));      // 소문자도 받는다
        assertThat((List<String>) JsonPath.read(view(proj), "$.data.deps[?(@.depId == " + d + ")].depType")).containsExactly("SS");
        long c = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"검증\",\"planStart\":\"" + today + "\",\"planEnd\":\"" + today + "\"}", pm));
        id(dep(b, c, null, 0, null));                // 비우면 FS
        assertThat(accounts.query(comp, "select dep_type from pms_task_dep where succ_task_id = ?", String.class, c)).isEqualTo("FS");
        dep(a, c, "SF", 0, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("depType"))
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        // 순환은 종류와 무관: a →(SS) b →(FS) c 에 c →(FF) a
        dep(c, a, "FF", 0, null).andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_CYCLE"));
        // 같은 짝에 종류만 다른 의존 둘은 안 된다
        dep(a, b, "FF", 0, null).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 직접 SQL: SF·소문자 거부, 종류 바꾸기(UPDATE) 거부
        Throwable t1 = catchThrowable(() -> accounts.exec(comp, "insert into pms_task_dep (comp_cd, proj_id, pred_task_id, succ_task_id, dep_type, "
                + "created_by) values (?, ?, ?, ?, 'SF', 't')", comp, proj, a, c));
        assertThat(PgErrors.constraint(t1)).hasValue("ck_pms_task_dep_type");
        Throwable t2 = catchThrowable(() -> accounts.exec(comp, "update pms_task_dep set dep_type = 'FF' where dep_id = ?", d));
        assertThat(PgErrors.constraint(t2)).hasValue("ck_pms_task_dep_fixed");
    }

    @Test
    void 위반표시와_밀기_미리보기는_종류별() throws Exception {
        // a = today-10~today-5. b(today-4~today) SS lag 7 → 시작이 today-3 이어야 → 위반, 미리보기 = today-3~today+1
        long d = id(dep(a, b, "SS", 7, null));
        assertThat((List<Boolean>) JsonPath.read(view(proj), "$.data.deps[?(@.depId == " + d + ")].violated")).containsExactly(true);
        String pv = mvc.perform(get(P + "/" + proj + "/push-preview").with(pm)).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(pv, "$.data.moves[0].newStart")).isEqualTo(today.minusDays(3).toString());
        assertThat((String) JsonPath.read(pv, "$.data.moves[0].newEnd")).isEqualTo(today.plusDays(1).toString());
        // FF lag 0: c(today-12~today-6, 7일)는 종료가 today-5 이상이어야 → 위반, 하루 밀기
        long c = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"FF후행\",\"planStart\":\"" + today.minusDays(12) + "\",\"planEnd\":\""
                + today.minusDays(6) + "\"}", pm));
        long d2 = id(dep(a, c, "FF", 0, null));
        assertThat((List<Boolean>) JsonPath.read(view(proj), "$.data.deps[?(@.depId == " + d2 + ")].violated")).containsExactly(true);
        assertThat(row(c)).containsEntry("depViolation", true);
        String pv2 = mvc.perform(get(P + "/" + proj + "/push-preview").with(pm)).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(pv2, "$.data.moves[?(@.taskId == " + c + ")].newEnd")).containsExactly(today.minusDays(5).toString());
        // FF 는 시작이 선행보다 빨라도 끝만 맞으면 위반 아님(FS 였다면 위반)
        long e = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"FF맞음\",\"planStart\":\"" + today.minusDays(12) + "\",\"planEnd\":\""
                + today.minusDays(5) + "\"}", pm));
        long d3 = id(dep(a, e, "FF", 0, null));
        assertThat((List<Boolean>) JsonPath.read(view(proj), "$.data.deps[?(@.depId == " + d3 + ")].violated")).containsExactly(false);
    }

    @Test
    void BLOCK_SS_는_선행이_시작해야_후행_시작() throws Exception {
        policy("BLOCK");
        id(dep(a, b, "SS", 0, null));
        actual(b, "시작품", "mem1", today, null, mem, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PMS_DEP_NOT_DONE"))
                .andExpect(jsonPath("$.message", containsString("1 설계 (SS)")));
        // 선행이 시작만 하면(완료 아님) 통과 — FS 였다면 막힌다
        actual(a, "설계", null, today.minusDays(1), null, pm, null).andExpect(status().isOk());
        actual(b, "시작품", "mem1", today, null, mem, null).andExpect(status().isOk());
        assertThat(waives(b)).isZero();
    }

    @Test
    void BLOCK_FF_는_완료때_검사_PM_사유_보이지않는_사유는_빈사유() throws Exception {
        policy("BLOCK");
        id(dep(a, b, "FF", 0, null));
        // 시작은 FF 가 보지 않는다
        actual(b, "시작품", "mem1", today.minusDays(1), null, mem, null).andExpect(status().isOk());
        // 완료: 담당자 거부(상세 폼·내 작업 줄 둘 다)
        actual(b, "시작품", "mem1", today.minusDays(1), today, mem, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PMS_DEP_NOT_FINISHED"))
                .andExpect(jsonPath("$.message", containsString("1 설계 (FF)")));
        send(post(K + "/" + b + "/actual"), "{\"actStart\":\"" + today.minusDays(1) + "\",\"actEnd\":\"" + today + "\",\"rowVer\":"
                + row(b).get("rowVer") + "}", mem).andExpect(jsonPath("$.code").value("PMS_DEP_NOT_FINISHED"));
        assertThat(row(b)).containsEntry("status", "IN_PROGRESS");
        // PM: 사유 없음·보이지 않는 글자뿐(U+200B·NBSP) → 칸 오류, 사유 → 완료 + 기록
        actual(b, "시작품", "mem1", today.minusDays(1), today, pm, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("depReason"))
                .andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_FINISH_REASON"));
        actual(b, "시작품", "mem1", today.minusDays(1), today, pm, "\\u200B\\u00A0").andExpect(jsonPath("$.errors[0].code")
                .value("PMS_DEP_FINISH_REASON"));
        assertThat(waives(b)).isZero();
        actual(b, "시작품", "mem1", today.minusDays(1), today, pm, "ZT 고객 확인 먼저").andExpect(status().isOk());
        assertThat(row(b)).containsEntry("status", "DONE");
        assertThat(accounts.query(comp, "select open_preds || '|' || reason from pms_task_dep_waive where task_id = ?", String.class, b))
                .isEqualTo("1 설계 (FF)|ZT 고객 확인 먼저");
        // 선행이 끝났으면 FF 후행 완료는 그냥 통과
        long c = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"마무리\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today.minusDays(2)
                + "\",\"planEnd\":\"" + today + "\"}", pm));
        id(dep(b, c, "FF", 0, null));
        actual(c, "마무리", "mem1", today.minusDays(1), today, mem, null).andExpect(status().isOk());
        assertThat(waives(c)).isZero();
    }

    @Test
    void Pb_의존추가_사유는_종류별() throws Exception {
        policy("BLOCK");
        // 완료한 c 에 끝나지 않은 a 를 FF 로 → 사유, FS 로 거는 것도(시작한 후행) 사유
        long c = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"끝난 일\",\"planStart\":\"" + today.minusDays(3) + "\",\"planEnd\":\""
                + today.minusDays(1) + "\",\"actStart\":\"" + today.minusDays(3) + "\",\"actEnd\":\"" + today.minusDays(1) + "\"}", pm));
        dep(a, c, "FF", 0, null).andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_ADD_REASON"))
                .andExpect(jsonPath("$.errors[0].message", containsString("1 설계 (FF)")));
        dep(a, c, "FF", 0, "\\uFEFF").andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_ADD_REASON"));
        id(dep(a, c, "FF", 0, "ZT 사후 연결"));
        assertThat(accounts.query(comp, "select open_preds from pms_task_dep_waive where task_id = ?", String.class, c)).isEqualTo("1 설계 (FF)");
        // 시작만 한 d 에 시작 안 한 a 를 SS 로 → 사유. 시작한 e 를 SS 선행으로 → 대상 아님
        long d = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"병행\",\"planStart\":\"" + today + "\",\"planEnd\":\"" + today.plusDays(2)
                + "\",\"actStart\":\"" + today + "\"}", pm));
        dep(a, d, "SS", 0, null).andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_ADD_REASON"));
        long e = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"진행 중 선행\",\"planStart\":\"" + today + "\",\"planEnd\":\""
                + today.plusDays(2) + "\",\"actStart\":\"" + today + "\"}", pm));
        id(dep(e, d, "SS", 0, null));
        // 시작 안 한 f 에 FF 로 미완 a → 대상 아님(완료 때 검사)
        long f = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"미착수\",\"planStart\":\"" + today + "\",\"planEnd\":\"" + today + "\"}", pm));
        id(dep(a, f, "FF", 0, null));
        assertThat(accounts.query(comp, "select count(*) from pms_task_dep_waive where proj_id = ?", Long.class, proj)).isEqualTo(1L);
    }

    @Test
    void WBS_복사와_템플릿은_종류를_가져간다() throws Exception {
        id(dep(a, b, "FF", 2, null));
        long copied = id(send(post(P + "/" + proj + "/copy"), "{\"projNm\":\"ZT 복사\",\"projType\":\"GENERAL\",\"startDate\":\"" + today + "\"}", pm));
        assertThat(accounts.query(comp, "select dep_type || ':' || lag_days from pms_task_dep where proj_id = ?", String.class, copied))
                .isEqualTo("FF:2");
        long t = id(send(post(T + "/from-project/" + proj), "{\"tmplCd\":\"ZT-DT\"}", pm));
        assertThat((List<String>) JsonPath.read(mvc.perform(get(T + "/" + t).with(pm)).andReturn().getResponse().getContentAsString(),
                "$.data.deps[*].depType")).containsExactly("FF");
        // 템플릿에 SS 의존 추가 → 그 템플릿으로 만든 프로젝트에 SS
        long t1 = id(send(post(T + "/" + t + "/tasks"), "{\"taskNm\":\"추가\",\"durationDays\":2}", pm));
        long tt = accounts.query(comp, "select tt_id from pms_template_task where tmpl_id = ? and task_nm = '설계'", Long.class, t);
        send(post(T + "/" + t + "/deps"), "{\"predTtId\":" + tt + ",\"succTtId\":" + t1 + ",\"depType\":\"XX\"}", pm)
                .andExpect(jsonPath("$.errors[0].field").value("depType"));
        id(send(post(T + "/" + t + "/deps"), "{\"predTtId\":" + tt + ",\"succTtId\":" + t1 + ",\"depType\":\"SS\"}", pm));
        long p2 = id(send(post(P), "{\"projNm\":\"ZT 템플릿에서\",\"projType\":\"GENERAL\",\"tmplCd\":\"ZT-DT\",\"startDate\":\"2026-06-01\"}", pm));
        assertThat(accounts.query(comp, "select string_agg(dep_type, ',' order by dep_type) from pms_task_dep where proj_id = ?", String.class, p2))
                .isEqualTo("FF,SS");
        Throwable x = catchThrowable(() -> accounts.exec(comp, "insert into pms_template_dep (comp_cd, tmpl_id, pred_tt_id, succ_tt_id, dep_type, "
                + "created_by) values (?, ?, ?, ?, 'SF', 't')", comp, t, tt, t1));
        assertThat(PgErrors.constraint(x)).hasValue("ck_pms_template_dep_type");
    }

    @Test
    void A사로_B사_다른회사_작업에_종류붙여_의존_추가_불가() throws Exception {
        String compB = accounts.newComp("PY");
        long bId = accounts.newUser(compB, "pm1", PW);
        accounts.grant(compB, bId, "PM", "pms-project", "SEARCH", "SAVE");
        RequestPostProcessor bu = accounts.as(compB, bId, "pm1");
        send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + b + ",\"depType\":\"SS\"}", bu)
                .andExpect(status().isNotFound());
        // B사 자기 프로젝트에 A사 작업 id 를 선행으로 → 없음
        long bp = id(send(post(P), "{\"projNm\":\"ZT B사\",\"projType\":\"GENERAL\"}", bu));
        long bt = id(send(post(P + "/" + bp + "/tasks"), "{\"taskNm\":\"B작업\",\"planStart\":\"" + today + "\",\"planEnd\":\"" + today + "\"}", bu));
        send(post(P + "/" + bp + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + bt + ",\"depType\":\"FF\"}", bu)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        assertThat(accounts.query(comp, "select count(*) from pms_task_dep where proj_id = ?", Long.class, proj)).isZero();
    }
}
