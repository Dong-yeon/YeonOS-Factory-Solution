package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

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
 * docs/40 §10.2 D1 — 산출물(pms-deliverable)·게이트 심사(pms-gate) 화면 위 프로젝트 고르기 grid API.
 * 고르기 목록(deliverable-projects·gate-projects)과 같은 조건·같은 기본 순서, 쪽 경계·전체 건수, 정렬 허용 목록(보이는 다섯 칸만),
 * 열 정의, 화면별 권한(한 화면 권한으로 다른 화면 경로는 403, 프로젝트 화면 권한 없이도 됨), A사로 B사.
 */
@AutoConfigureMockMvc
class PmsPickGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String D = "/api/pms/deliverable-projects";
    private static final String G = "/api/pms/gate-projects";
    private static final List<String> NAMES = List.of("다", "가", "마", "나", "라");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor pm;
    RequestPostProcessor gateOnly;
    RequestPostProcessor delivOnly;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PK");
        long u = accounts.newUser(comp, "pm1", PW);
        accounts.grant(comp, u, "PMS", "pms-project", "SEARCH", "SAVE");
        accounts.grant(comp, u, "PMS", "pms-gate", "SEARCH");
        accounts.grant(comp, u, "PMS", "pms-deliverable", "SEARCH");
        pm = accounts.as(comp, u, "pm1");
        for (String n : NAMES) {
            mvc.perform(post("/api/pms/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"projNm\":\"ZK 프로젝트 " + n + "\",\"projType\":\"APQP\"}").with(pm)).andExpect(status().isOk());
        }
        long g = accounts.newUser(comp, "gate1", PW);
        accounts.grant(comp, g, "PMSG", "pms-gate", "SEARCH");   // 그룹을 나눈다 — 같은 그룹이면 위 pm1 의 화면 권한까지 받는다
        gateOnly = accounts.as(comp, g, "gate1");
        long d = accounts.newUser(comp, "deliv1", PW);
        accounts.grant(comp, d, "PMSD", "pms-deliverable", "SEARCH");
        delivOnly = accounts.as(comp, d, "deliv1");
    }

    private ResultActions grid(String base, RequestPostProcessor who, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(base + "/grid");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private static List<String> col(ResultActions r, String field) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.list[*]." + field);
    }

    private List<String> listed(String base, RequestPostProcessor who, String status) throws Exception {
        return JsonPath.read(mvc.perform(get(base).param("status", status).with(who)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.data[*].projNo");
    }

    @Test
    void 목록과_같은_조건_같은_순서_쪽_경계와_전체건수() throws Exception {
        for (String base : List.of(D, G)) {
            RequestPostProcessor who = D.equals(base) ? delivOnly : gateOnly;
            // 기본 정렬 = 고르기 목록과 같은 순서(번호 내림차순), 진행 중/전체 둘 다
            assertThat(col(grid(base, who, "status", "OPEN", "size", "50"), "projNo")).containsExactlyElementsOf(listed(base, who, "OPEN"));
            assertThat(col(grid(base, who, "status", "", "size", "50"), "projNo")).containsExactlyElementsOf(listed(base, who, ""));
            grid(base, who, "size", "2").andExpect(jsonPath("$.total").value(5));
            assertThat(col(grid(base, who, "size", "2", "page", "1", "sort", "projNm,asc"), "projNm"))
                    .containsExactly("ZK 프로젝트 가", "ZK 프로젝트 나");
            assertThat(col(grid(base, who, "size", "2", "page", "3", "sort", "projNm,asc"), "projNm")).containsExactly("ZK 프로젝트 마");
            assertThat(col(grid(base, who, "size", "2", "page", "4", "sort", "projNm,asc"), "projNm")).isEmpty();
            // 같은 값(상태 전부 DRAFT) 정렬도 PK 로 끝을 정해 쪽 사이 중복·누락 없음
            List<String> a = col(grid(base, who, "size", "2", "page", "1", "sort", "status,asc"), "projNo");
            List<String> b = col(grid(base, who, "size", "2", "page", "2", "sort", "status,asc"), "projNo");
            List<String> c = col(grid(base, who, "size", "2", "page", "3", "sort", "status,asc"), "projNo");
            assertThat(Stream.of(a, b, c).flatMap(List::stream).distinct()).hasSize(5);
            // 같은 조건: 검색어·상태
            assertThat(col(grid(base, who, "status", "OPEN", "keyword", "프로젝트 다"), "projNm")).containsExactly("ZK 프로젝트 다");
            grid(base, who, "status", "CLOSED").andExpect(jsonPath("$.total").value(0));
            grid(base, who, "status", "WIP").andExpect(status().isBadRequest());
        }
    }

    @Test
    void 정렬_허용목록은_보이는_칸만_열정의() throws Exception {
        for (String base : List.of(D, G)) {
            grid(base, pm, "sort", "custNm,desc").andExpect(status().isOk());
            grid(base, pm, "sort", "sopDate,asc").andExpect(status().isOk());
            // 프로젝트 화면 grid 에는 있지만 고르기 grid 에는 안 보이는 칸 → 400
            grid(base, pm, "sort", "lateCnt,desc").andExpect(status().isBadRequest());
            grid(base, pm, "sort", "pmUserNm,asc").andExpect(status().isBadRequest());
            grid(base, pm, "sort", "proj_no;drop table pms_project,asc").andExpect(status().isBadRequest());
            grid(base, pm, "sort", "projNm,asc", "sort", "status,asc", "sort", "projNo,asc", "sort", "sopDate,asc")
                    .andExpect(status().isBadRequest());
            grid(base, pm, "size", "100000").andExpect(status().isBadRequest());
            grid(base, pm, "page", "0").andExpect(status().isBadRequest());
            List<String> fields = JsonPath.read(mvc.perform(get(base + "/columns").with(pm)).andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString(), "$.list[*].field");
            assertThat(fields).containsExactly("projNo", "projNm", "custNm", "sopDate", "status");
        }
    }

    @Test
    void 화면별_권한_A사로_B사_행을_못본다() throws Exception {
        // 프로젝트 화면 권한 없이 자기 화면 경로는 되고, 다른 화면 경로는 403
        grid(G, gateOnly).andExpect(status().isOk());
        grid(D, gateOnly).andExpect(status().isForbidden());
        mvc.perform(get(D + "/columns").with(gateOnly)).andExpect(status().isForbidden());
        grid(D, delivOnly).andExpect(status().isOk());
        grid(G, delivOnly).andExpect(status().isForbidden());
        mvc.perform(get(G + "/columns").with(delivOnly)).andExpect(status().isForbidden());
        // B사 같은 권한 사용자 — A사 프로젝트 0건(RLS)
        String other = accounts.newComp("PL");
        long bu = accounts.newUser(other, "pm1", PW);
        accounts.grant(other, bu, "PMS", "pms-gate", "SEARCH");
        accounts.grant(other, bu, "PMS", "pms-deliverable", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "pm1");
        for (String base : List.of(D, G)) {
            grid(base, b, "status", "").andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
            grid(base, b, "status", "", "keyword", "ZK 프로젝트").andExpect(jsonPath("$.total").value(0));
        }
    }
}
