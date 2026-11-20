package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 프로젝트 → 템플릿으로 저장 + 템플릿 선후행 (docs/32 4.18, 사용자 결정 P-7) — 상대 일정·표준 공수·선후행·산출물 칸·서식·점검 문구,
 * 템플릿 → 프로젝트 복사에 선후행, 템플릿 선후행 규칙(자기·요약·중복·순환·lag·의존 걸린 작업의 하위·작업 삭제), 입력·권한, 직접 SQL,
 * A사로 B사.
 */
@AutoConfigureMockMvc
class PmsTmplSaveTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String T = "/api/pms/templates";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    RequestPostProcessor pm;
    String formNo;
    long src;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PT");
        pmId = accounts.newUser(comp, "pm1", PW);
        for (String prog : List.of("pms-project", "pms-template", "pms-deliverable", "qms-doc")) {
            accounts.grant(comp, pmId, "PMS", prog, "SEARCH", "SAVE");
        }
        pm = accounts.as(comp, pmId, "pm1");
        long docId = id(send(post("/api/qms/docs"), "{\"docType\":\"CP\",\"docNm\":\"ZT 검토서 서식\",\"targets\":[]}", pm));
        formNo = read(mvc.perform(get("/api/qms/docs/" + docId).with(pm)), "$.data.doc.docNo");
        src = source();
    }

    // ------------------------------------------------------------------ 도우미

    private ResultActions send(MockHttpServletRequestBuilder r, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(r.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andReturn();
        String s = m.getResponse().getContentAsString();
        assertThat(m.getResponse().getStatus()).as(s).isEqualTo(200);
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private static <T> T read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }

    /** 원본: 시드 템플릿(단계 > 설계 16h·산출물 칸 + 서식 / 게이트 점검 문구 2) → 프로젝트(3/2) + 시작품(3/16~3/18, 8h)·선후행 lag 2 */
    private long source() throws Exception {
        long t = id(send(post(T), "{\"tmplCd\":\"SEED\",\"tmplNm\":\"시드\",\"projType\":\"GENERAL\"}", pm));
        long ph = id(send(post(T + "/" + t + "/tasks"), "{\"taskNm\":\"설계 단계\",\"durationDays\":1}", pm));
        long a = id(send(post(T + "/" + t + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"설계\",\"durationDays\":5,\"stdHours\":16}", pm));
        long g = id(send(post(T + "/" + t + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"설계 게이트\",\"taskType\":\"GATE\",\"offsetDays\":8}", pm));
        send(post(T + "/tasks/" + a + "/items"), "{\"kind\":\"DELIV\",\"itemNm\":\"설계 검토서\",\"formDocNo\":\"" + formNo + "\"}", pm)
                .andExpect(status().isOk());
        send(post(T + "/tasks/" + g + "/items"), "{\"kind\":\"CHECK\",\"itemNm\":\"도면 승인\"}", pm).andExpect(status().isOk());
        send(post(T + "/tasks/" + g + "/items"), "{\"kind\":\"CHECK\",\"itemNm\":\"원가 확인\"}", pm).andExpect(status().isOk());
        long p = id(send(post(P), "{\"projNm\":\"ZT 원본\",\"projType\":\"CHANGE\",\"tmplCd\":\"SEED\",\"startDate\":\"2026-03-02\"}", pm));
        long b = id(send(post(P + "/" + p + "/tasks"), "{\"taskNm\":\"시작품\",\"planStart\":\"2026-03-16\",\"planEnd\":\"2026-03-18\","
                + "\"planHours\":8,\"ownerLoginId\":\"pm1\"}", pm));
        long design = accounts.query(comp, "select task_id from pms_task where proj_id = ? and task_nm = '설계'", Long.class, p);
        id(send(post(P + "/" + p + "/deps"), "{\"predTaskId\":" + design + ",\"succTaskId\":" + b + ",\"lagDays\":2}", pm));
        id(send(post(P + "/" + p + "/deliverables"), "{\"taskId\":" + b + ",\"delivNm\":\"시작품 성적서\",\"requiredYn\":\"N\"}", pm));
        return p;
    }

    private Map<String, Object> view(long tmplId) throws Exception {
        return read(mvc.perform(get(T + "/" + tmplId).with(pm)), "$.data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> tasksByName(long tmplId) throws Exception {
        return ((List<Map<String, Object>>) view(tmplId).get("tasks")).stream()
                .collect(Collectors.toMap(x -> (String) x.get("taskNm"), x -> x));
    }

    private long tt(long tmplId, String nm) throws Exception {
        return ((Number) tasksByName(tmplId).get(nm).get("ttId")).longValue();
    }

    private ResultActions save(long proj, String body, RequestPostProcessor who) throws Exception {
        return send(post(T + "/from-project/" + proj), body, who);
    }

    // ------------------------------------------------------------------ 템플릿으로 저장

    @Test
    void 프로젝트를_템플릿으로_상대일정_공수_선후행_산출물칸_점검문구_다시_프로젝트로() throws Exception {
        long t = id(save(src, "{\"tmplCd\":\"zt-save\"}", pm));
        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) view(t).get("template");
        // 코드 대문자, 이름·유형 = 원본 프로젝트, 기준일 = 시작일
        assertThat(head).containsEntry("tmplCd", "ZT-SAVE").containsEntry("tmplNm", "ZT 원본").containsEntry("projType", "CHANGE")
                .containsEntry("anchor", "START").containsEntry("useYn", "Y");
        Map<String, Map<String, Object>> k = tasksByName(t);
        assertThat(k.keySet()).containsExactlyInAnyOrder("설계 단계", "설계", "설계 게이트", "시작품");
        // 상대 일정 = 첫 시작(3/2)부터 며칠 뒤, 기간 = 계획 일수, 마일스톤·게이트 = 0, 표준 공수 = 잎 계획 공수
        assertThat(k.get("설계")).containsEntry("offsetDays", 0).containsEntry("durationDays", 5).containsEntry("stdHours", 16.0)
                .containsEntry("wbsNo", "1.1");
        assertThat(k.get("설계 게이트")).containsEntry("offsetDays", 8).containsEntry("durationDays", 0).containsEntry("taskType", "GATE");
        assertThat(k.get("시작품")).containsEntry("offsetDays", 14).containsEntry("durationDays", 3).containsEntry("stdHours", 8.0)
                .containsEntry("wbsNo", "2");
        assertThat(k.get("설계 단계")).containsEntry("isSummary", "Y").containsEntry("stdHours", null);
        // 선후행
        List<Map<String, Object>> deps = JsonPath.read(view(t), "$.deps");
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0)).containsEntry("predTtId", (int) tt(t, "설계")).containsEntry("succTtId", (int) tt(t, "시작품"))
                .containsEntry("lagDays", 2);
        // 산출물 칸(필수·서식)·점검 문구
        List<Map<String, Object>> items = read(mvc.perform(get(T + "/" + t + "/items").with(pm)), "$.data");
        assertThat(items).extracting(i -> i.get("kind") + ":" + i.get("itemNm") + ":" + i.get("requiredYn") + ":" + i.get("formDocNo"))
                .containsExactlyInAnyOrder("DELIV:설계 검토서:Y:" + formNo, "DELIV:시작품 성적서:N:null", "CHECK:도면 승인:Y:null",
                        "CHECK:원가 확인:Y:null");
        // 이 템플릿으로 새 프로젝트 → 일정·선후행·산출물·점검 문구 그대로
        long p2 = id(send(post(P), "{\"projNm\":\"ZT 재사용\",\"projType\":\"CHANGE\",\"tmplCd\":\"ZT-SAVE\",\"startDate\":\"2026-06-01\"}", pm));
        List<Map<String, Object>> tasks = read(mvc.perform(get(P + "/" + p2 + "/tasks").with(pm)), "$.data.tasks");
        Map<String, Map<String, Object>> byNm = tasks.stream().collect(Collectors.toMap(x -> (String) x.get("taskNm"), x -> x));
        assertThat(byNm.get("설계")).containsEntry("planStart", "2026-06-01").containsEntry("planEnd", "2026-06-05")
                .containsEntry("ownerLoginId", null);
        assertThat(byNm.get("시작품")).containsEntry("planStart", "2026-06-15").containsEntry("planEnd", "2026-06-17");
        List<Map<String, Object>> pdeps = read(mvc.perform(get(P + "/" + p2 + "/tasks").with(pm)), "$.data.deps");
        assertThat(pdeps).hasSize(1);
        assertThat(pdeps.get(0)).containsEntry("lagDays", 2);
        assertThat(accounts.query(comp, "select count(*) from pms_task_check where proj_id = ?", Long.class, p2)).isEqualTo(2L);
        assertThat(accounts.query(comp, "select form_doc_no from pms_deliverable where proj_id = ? and deliv_nm = '설계 검토서'",
                String.class, p2)).isEqualTo(formNo);
    }

    @Test
    void 입력오류_중복코드_형식_작업없음_기간초과_아무것도_안남음_권한() throws Exception {
        id(save(src, "{\"tmplCd\":\"ZT2\"}", pm));
        save(src, "{\"tmplCd\":\"zt2\"}", pm).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        save(src, "{\"tmplCd\":\"한글\"}", pm).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        save(src, "{}", pm).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        long empty = id(send(post(P), "{\"projNm\":\"빈\",\"projType\":\"GENERAL\"}", pm));
        save(empty, "{\"tmplCd\":\"ZT3\"}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_NO_TASKS"));
        // 3650일 넘는 프로젝트 → 칸 오류, 중간까지 만든 템플릿도 남지 않는다(한 트랜잭션)
        long longP = id(send(post(P), "{\"projNm\":\"긴\",\"projType\":\"GENERAL\"}", pm));
        id(send(post(P + "/" + longP + "/tasks"), "{\"taskNm\":\"a\",\"planStart\":\"2026-01-01\",\"planEnd\":\"2026-01-02\"}", pm));
        id(send(post(P + "/" + longP + "/tasks"), "{\"taskNm\":\"b\",\"planStart\":\"2037-01-01\",\"planEnd\":\"2037-01-02\"}", pm));
        save(longP, "{\"tmplCd\":\"ZT4\"}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_TMPL_SPAN"));
        assertThat(accounts.query(comp, "select count(*) from pms_template where tmpl_cd in ('ZT3','ZT4')", Long.class)).isZero();
        save(99999999L, "{\"tmplCd\":\"ZT5\"}", pm).andExpect(status().isNotFound());
        // 권한: 템플릿 저장만(프로젝트 조회 없음) / 프로젝트만(템플릿 저장 없음)
        long tOnly = accounts.newUser(comp, "tmpl1", PW);
        accounts.grant(comp, tOnly, "TMPL", "pms-template", "SEARCH", "SAVE");
        save(src, "{\"tmplCd\":\"ZT6\"}", accounts.as(comp, tOnly, "tmpl1")).andExpect(status().isForbidden());
        long pOnly = accounts.newUser(comp, "proj1", PW);
        accounts.grant(comp, pOnly, "PROJ", "pms-project", "SEARCH", "SAVE");
        save(src, "{\"tmplCd\":\"ZT7\"}", accounts.as(comp, pOnly, "proj1")).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select count(*) from pms_template where tmpl_cd in ('ZT6','ZT7')", Long.class)).isZero();
    }

    // ------------------------------------------------------------------ 템플릿 선후행

    @Test
    void 템플릿_선후행_규칙_자기_요약_중복_순환_lag_하위추가_작업삭제() throws Exception {
        long t = accounts.query(comp, "select tmpl_id from pms_template where tmpl_cd = 'SEED'", Long.class);
        long ph = tt(t, "설계 단계");
        long a = tt(t, "설계");
        long g = tt(t, "설계 게이트");
        long c = id(send(post(T + "/" + t + "/tasks"), "{\"taskNm\":\"양산 준비\",\"offsetDays\":12,\"durationDays\":3}", pm));
        String dep = T + "/" + t + "/deps";
        send(post(dep), "{\"predTtId\":" + a + ",\"succTtId\":" + a + "}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_SELF"));
        send(post(dep), "{\"predTtId\":" + ph + ",\"succTtId\":" + c + "}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_SUMMARY"));
        send(post(dep), "{\"predTtId\":" + a + ",\"succTtId\":" + g + ",\"lagDays\":400}", pm).andExpect(jsonPath("$.errors[0].field").value("lagDays"));
        long d1 = id(send(post(dep), "{\"predTtId\":" + a + ",\"succTtId\":" + g + ",\"lagDays\":1}", pm));
        id(send(post(dep), "{\"predTtId\":" + g + ",\"succTtId\":" + c + "}", pm));
        send(post(dep), "{\"predTtId\":" + a + ",\"succTtId\":" + g + "}", pm).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        send(post(dep), "{\"predTtId\":" + c + ",\"succTtId\":" + a + "}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_CYCLE"));
        // 의존 걸린 작업 아래 하위 추가 → 거부
        send(post(T + "/" + t + "/tasks"), "{\"parentId\":" + c + ",\"taskNm\":\"x\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_HAS_DEPS"));
        // 선후행 지우기 / 작업 지우면 그 작업의 선후행도
        mvc.perform(delete(T + "/deps/" + d1).with(csrf()).with(pm)).andExpect(status().isOk());
        mvc.perform(delete(T + "/tasks/" + c).with(csrf()).with(pm)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from pms_template_dep where tmpl_id = ?", Long.class, t)).isZero();
        mvc.perform(delete(T + "/deps/" + d1).with(csrf()).with(pm)).andExpect(status().isNotFound());
    }

    @Test
    void 직접SQL_고치기불가_순환_다른템플릿_작업_의존걸린_부모() throws Exception {
        long t = accounts.query(comp, "select tmpl_id from pms_template where tmpl_cd = 'SEED'", Long.class);
        long a = tt(t, "설계");
        long g = tt(t, "설계 게이트");
        long d = id(send(post(T + "/" + t + "/deps"), "{\"predTtId\":" + a + ",\"succTtId\":" + g + "}", pm));
        // 고치기는 앱 계정 권한부터 없다(REVOKE UPDATE) — 트리거(ck_pms_template_dep_fixed)는 그 뒤 두 번째 벽
        assertThat(catchThrowable(() -> accounts.exec(comp, "update pms_template_dep set lag_days = 3 where tdep_id = ?", d)))
                .hasRootCauseMessage("ERROR: permission denied for table pms_template_dep");
        assertThat(catchThrowable(() -> accounts.exec(comp, "insert into pms_template_dep (comp_cd, tmpl_id, pred_tt_id, succ_tt_id, created_by) "
                + "values (?, ?, ?, ?, 't')", comp, t, g, a))).hasMessageContaining("makes a cycle");
        long other = id(save(src, "{\"tmplCd\":\"OTHER\"}", pm));
        long foreign = tt(other, "시작품");
        assertThat(catchThrowable(() -> accounts.exec(comp, "insert into pms_template_dep (comp_cd, tmpl_id, pred_tt_id, succ_tt_id, created_by) "
                + "values (?, ?, ?, ?, 't')", comp, t, a, foreign))).hasMessageContaining("must be leaf tasks");
        assertThat(catchThrowable(() -> accounts.exec(comp, "insert into pms_template_task (comp_cd, tmpl_id, parent_id, seq, task_nm, created_by, "
                + "updated_by) values (?, ?, ?, 99, 'x', 't', 't')", comp, t, a))).hasMessageContaining("cannot have sub-tasks");
    }

    @Test
    void A사로_B사_프로젝트로_템플릿_404_A사_템플릿_선후행_404() throws Exception {
        long t = accounts.query(comp, "select tmpl_id from pms_template where tmpl_cd = 'SEED'", Long.class);
        long a = tt(t, "설계");
        long g = tt(t, "설계 게이트");
        long d = id(send(post(T + "/" + t + "/deps"), "{\"predTtId\":" + a + ",\"succTtId\":" + g + "}", pm));
        String compB = accounts.newComp("PU");
        long bId = accounts.newUser(compB, "pm1", PW);
        for (String prog : List.of("pms-project", "pms-template")) {
            accounts.grant(compB, bId, "PMS", prog, "SEARCH", "SAVE");
        }
        RequestPostProcessor b = accounts.as(compB, bId, "pm1");
        save(src, "{\"tmplCd\":\"STOLEN\"}", b).andExpect(status().isNotFound());
        assertThat(accounts.query(compB, "select count(*) from pms_template", Long.class)).isZero();
        send(post(T + "/" + t + "/deps"), "{\"predTtId\":" + g + ",\"succTtId\":" + a + "}", b).andExpect(status().isNotFound());
        mvc.perform(delete(T + "/deps/" + d).with(csrf()).with(b)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from pms_template_dep where tmpl_id = ?", Long.class, t)).isEqualTo(1L);
    }
}
