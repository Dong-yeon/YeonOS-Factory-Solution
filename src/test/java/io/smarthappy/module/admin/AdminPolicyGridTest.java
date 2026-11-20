package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * UI 단계 3 ① 2묶음 (docs/36 17.2) — 정책 grid API: 목록과 같은 행·같은 기본 순서, 쪽 경계, 조건(분류·검색어·바꾼 것만)을
 * 서버에서, 정렬 허용 목록, 권한(SEARCH), A사로 B사(값은 회사별). 기존 목록 API 는 그대로.
 */
@AutoConfigureMockMvc
class AdminPolicyGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/admin/policies";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PG");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-policy", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        mvc.perform(put(P + "/ANDON_BLUE_MEANING").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"\",\"value\":\"MATERIAL\",\"rowVer\":null}").with(admin)).andExpect(status().isOk());
    }

    private ResultActions grid(RequestPostProcessor who, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(P + "/grid");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private static List<String> read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    @Test
    void 정책_grid_목록과_같은_행_쪽을_이으면_목록_순서() throws Exception {
        List<String> all = JsonPath.read(mvc.perform(get(P).with(admin)).andReturn().getResponse().getContentAsString(), "$.list[*].policyKey");
        grid(admin, "size", "7").andExpect(jsonPath("$.total").value(all.size()));
        List<String> paged = new ArrayList<>();
        for (int p = 1; p <= (all.size() + 6) / 7; p++) {
            paged.addAll(read(grid(admin, "size", "7", "page", String.valueOf(p)), "$.list[*].policyKey"));
        }
        assertThat(paged).containsExactlyElementsOf(all);                // 쪽 경계에서 중복·누락 없음
        assertThat(read(grid(admin, "size", "7", "page", "999"), "$.list[*].policyKey")).isEmpty();
        assertThat(read(grid(admin, "sort", "policyKey,desc", "size", "1"), "$.list[*].policyKey"))
                .containsExactly(all.stream().sorted((a, b) -> b.compareTo(a)).findFirst().orElseThrow());
        grid(admin, "sort", "desc,asc").andExpect(status().isOk());
        mvc.perform(get(P + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("policyKey", "desc", "companyText")));
    }

    @Test
    void 조건은_서버에서_분류_검색어_바꾼것만() throws Exception {
        grid(admin, "changedOnly", "Y").andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].policyKey").value("ANDON_BLUE_MEANING"))
                .andExpect(jsonPath("$.list[0].companyValue").value("MATERIAL"))
                .andExpect(jsonPath("$.list[0].companyText").value("MATERIAL"))
                .andExpect(jsonPath("$.list[0].view.values[0].policyValue").value("MATERIAL"));
        List<String> andon = read(grid(admin, "category", "ANDON", "size", "100"), "$.list[*].category");
        assertThat(andon).isNotEmpty().allMatch("ANDON"::equals);
        assertThat(read(grid(admin, "keyword", "andon_blue"), "$.list[*].policyKey")).containsExactly("ANDON_BLUE_MEANING");
        grid(admin, "keyword", "zz%_").andExpect(jsonPath("$.total").value(0));         // % _ 도 글자 그대로(와일드카드 아님)
        // 이상 값
        grid(admin, "changedOnly", "X").andExpect(status().isBadRequest());
        grid(admin, "keyword", "x".repeat(51)).andExpect(status().isBadRequest());
        grid(admin, "category", "x".repeat(31)).andExpect(status().isBadRequest());
        grid(admin, "sort", "valueType,asc").andExpect(status().isBadRequest());
        grid(admin, "sort", "companyValue,asc").andExpect(status().isBadRequest());
        grid(admin, "size", "0").andExpect(status().isBadRequest());
    }

    @Test
    void 권한없으면_403_B사는_A사_값을_못본다() throws Exception {
        String other = accounts.newComp("PH");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "ADM", "sys-policy", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        grid(b, "changedOnly", "Y").andExpect(jsonPath("$.total").value(0));
        grid(b, "keyword", "andon_blue").andExpect(jsonPath("$.list[0].companyValue").doesNotExist())
                .andExpect(jsonPath("$.list[0].companyText").value(org.hamcrest.Matchers.containsString("CALL")));   // 기본값으로
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "sys-user", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        grid(n).andExpect(status().isForbidden());
        mvc.perform(get(P + "/columns").with(n)).andExpect(status().isForbidden());
    }
}
