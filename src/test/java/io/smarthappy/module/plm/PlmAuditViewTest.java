package io.smarthappy.module.plm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 지시·요청 grid "변경 이력 보기"(docs/39 28장, 플랫폼 docs/09 4.4) — grid 가 부르는 /api/platform/audit 이 PLM 표를 그 화면 권한으로 보여 준다.
 * 다른 화면 권한만이면 403, A사로 B사는 빈 이력.
 */
@AutoConfigureMockMvc
class PlmAuditViewTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Test
    void 지시_요청의_변경_이력을_화면_권한으로_본다() throws Exception {
        String comp = accounts.newComp("PAV");
        long u = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ENG2", "plm-ecr", "SEARCH", "SAVE");
        long ecrOnly = accounts.newUser(comp, "req1", PW);
        accounts.grant(comp, ecrOnly, "REQ", "plm-ecr", "SEARCH");
        RequestPostProcessor me = accounts.as(comp, u, "eng1");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        MvcResult r = mvc.perform(post("/api/plm/ecos").with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"이력 시험\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\"}")).andExpect(status().isOk()).andReturn();
        long eco = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(put("/api/plm/ecos/" + eco).with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"이력 시험 2\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\",\"rowVer\":0}")).andExpect(status().isOk());
        // 최신 먼저 — 고친 것(UPDATE) → 만든 것(INSERT)
        mvc.perform(get("/api/platform/audit").param("table", "plm_eco").param("key", String.valueOf(eco)).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(2))
                .andExpect(jsonPath("$.list[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.list[1].action").value("INSERT"))
                .andExpect(jsonPath("$.list[0].actor").value(org.hamcrest.Matchers.endsWith(":eng1")));
        r = mvc.perform(post("/api/plm/ecrs").with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"요청 이력\",\"reasonCd\":\"QUALITY\"}")).andExpect(status().isOk()).andReturn();
        long ecr = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(get("/api/platform/audit").param("table", "plm_ecr").param("key", String.valueOf(ecr)).with(me))
                .andExpect(status().isOk()).andExpect(jsonPath("$.list.length()").value(1));
        // 요청 화면 권한만 — 지시 이력은 403, 요청 이력은 본다
        RequestPostProcessor req = accounts.as(comp, ecrOnly, "req1");
        mvc.perform(get("/api/platform/audit").param("table", "plm_eco").param("key", String.valueOf(eco)).with(req))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/audit").param("table", "plm_ecr").param("key", String.valueOf(ecr)).with(req))
                .andExpect(status().isOk());
        // A사로 B사 — 같은 키를 물어도 빈 이력(RLS)
        String compB = accounts.newComp("PAVB");
        long b = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, b, "ENG", "plm-eco", "SEARCH");
        mvc.perform(get("/api/platform/audit").param("table", "plm_eco").param("key", String.valueOf(eco)).with(accounts.as(compB, b, "engB")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.list.length()").value(0));
    }
}
