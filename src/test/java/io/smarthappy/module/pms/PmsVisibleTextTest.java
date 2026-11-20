package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PMS 필수 글 칸의 보이지 않는 글자 (QA R3-a·R4-a 2단계, docs/qa/2026-11-02_invisible-reason-proposal.md 4장 PMS 행) — 판정은 플랫폼 VisibleText,
 * 오류 코드는 그대로(REQUIRED). 계획 변경 사유(planReason)·기준선 사유·밀기 사유·작업 이름·프로젝트 상태 사유. 정상 글(가운데 ZWJ 포함)은 그대로 저장.
 * 선행 면제 사유(depReason)는 PmsDepQaTest(부록 A)·PmsDepTypeTest.
 */
@AutoConfigureMockMvc
class PmsVisibleTextTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String K = "/api/pms/tasks";
    /** JSON 안 이스케이프 — 제로폭 공백·NBSP·BOM·전각 공백·한글 채움 */
    private static final String[] INVISIBLE = {"\\u200B", "\\u00A0\\u00A0", "\\uFEFF", "\\u3000", "\\u3164", " \\u200B\\t"};

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor pm;
    long proj;
    long a;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PV");
        long pmId = accounts.newUser(comp, "pm1", PW);
        accounts.grant(comp, pmId, "PM", "pms-project", "SEARCH", "SAVE");
        pm = accounts.as(comp, pmId, "pm1");
        proj = id(send(post(P), "{\"projNm\":\"ZT 사유\",\"projType\":\"GENERAL\"}", pm));
        a = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-02\",\"planEnd\":\"2026-03-06\"}", pm));
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

    private Map<String, Object> row(long taskId) throws Exception {
        List<Map<String, Object>> rows = JsonPath.read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)).andReturn().getResponse()
                .getContentAsString(), "$.data.tasks[?(@.taskId == " + taskId + ")]");
        return rows.get(0);
    }

    @Test
    void 기준선_뒤_계획변경_사유가_보이지_않는_글자뿐이면_REQUIRED_이력없음() throws Exception {
        for (String s : INVISIBLE) {
            send(post(P + "/" + proj + "/baselines"), "{\"reason\":\"" + s + "\"}", pm).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("reason")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        assertThat(accounts.query(comp, "select count(*) from pms_baseline where proj_id = ?", Long.class, proj)).isZero();
        id(send(post(P + "/" + proj + "/baselines"), "{\"reason\":\"\\u200B고객 합의\\u00A0\"}", pm));
        // 앞뒤 보이지 않는 글자는 걷어낸다
        assertThat(accounts.query(comp, "select reason from pms_baseline where proj_id = ?", String.class, proj)).isEqualTo("고객 합의");
        for (String s : INVISIBLE) {
            send(put(K + "/" + a), "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-03\",\"planEnd\":\"2026-03-06\",\"rowVer\":" + row(a).get("rowVer")
                    + ",\"planReason\":\"" + s + "\"}", pm).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("planReason")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        assertThat(row(a)).containsEntry("planStart", "2026-03-02");
        assertThat(accounts.query(comp, "select count(*) from pms_task_plan_change where task_id = ?", Long.class, a)).isZero();
        // 가운데 형식 문자(ZWJ)는 내용이라 그대로
        send(put(K + "/" + a), "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-03\",\"planEnd\":\"2026-03-06\",\"rowVer\":" + row(a).get("rowVer")
                + ",\"planReason\":\"금형\\u200D수정\"}", pm).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select reason from pms_task_plan_change where task_id = ?", String.class, a)).isEqualTo("금형\u200D수정");
    }

    @Test
    void 이름_칸과_상태_사유도_같은_판정() throws Exception {
        send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"\\u200B\\u3000\",\"planStart\":\"2026-03-02\"}", pm).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("taskNm")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(post(P), "{\"projNm\":\"\\uFEFF\",\"projType\":\"GENERAL\"}", pm).andExpect(jsonPath("$.errors[0].field").value("projNm"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 가운데 줄바꿈·탭은 이름 칸에서 공백으로(예전 그대로)
        long t = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"시작품\\n검증\",\"planStart\":\"2026-03-02\"}", pm));
        assertThat(row(t)).containsEntry("taskNm", "시작품 검증");
        // 프로젝트 취소 사유(보이지 않는 글자뿐) → 지금과 같은 칸 오류, 상태 그대로
        int rv = JsonPath.read(mvc.perform(get(P + "/" + proj).with(pm)).andReturn().getResponse().getContentAsString(), "$.data.rowVer");
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"\\u200B\",\"rowVer\":" + rv + "}", pm)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        assertThat((String) JsonPath.read(mvc.perform(get(P + "/" + proj).with(pm)).andReturn().getResponse().getContentAsString(), "$.data.status"))
                .isEqualTo("DRAFT");
    }
}
