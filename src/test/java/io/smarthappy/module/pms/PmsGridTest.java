package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * UI 단계 3 ② (docs/32 4.11) — PMS 주 목록 grid API(프로젝트·이슈·템플릿·포트폴리오): 목록과 같은 조건, 서버 페이징 경계·전체 건수,
 * 정렬 허용 목록(모르는 열·주입 문자열·4개 이상·쪽 크기 400), 열 정의, 권한(SEARCH 없으면 403), A사로 B사. 기존 목록 API 는 그대로.
 */
@AutoConfigureMockMvc
class PmsGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String IS = "/api/pms/issues";
    private static final String T = "/api/pms/templates";
    private static final String H = "/api/pms/portfolio";
    private static final List<String> NAMES = List.of("다", "가", "마", "나", "라");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor pm;
    long firstProj;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PG");
        long u = accounts.newUser(comp, "pm1", PW);
        accounts.grant(comp, u, "PMS", "pms-project", "SEARCH", "SAVE");
        accounts.grant(comp, u, "PMS", "pms-issue", "SEARCH", "SAVE");
        accounts.grant(comp, u, "PMS", "pms-template", "SEARCH", "SAVE");
        accounts.grant(comp, u, "PMS", "pms-home", "SEARCH");
        pm = accounts.as(comp, u, "pm1");
        for (String n : NAMES) {
            long id = id(post(P), "{\"projNm\":\"ZT 프로젝트 " + n + "\",\"projType\":\"APQP\"}");
            if (firstProj == 0) {
                firstProj = id;
            }
            id(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + firstProj + ",\"title\":\"ZT 이슈 " + n + "\",\"severity\":\"" + ("가".equals(n) ? "HIGH" : "LOW") + "\"}");
            id(post(T), "{\"tmplCd\":\"ZT-" + n.hashCode() + "\",\"tmplNm\":\"ZT 템플릿 " + n + "\",\"projType\":\"APQP\",\"anchor\":\"START\""
                    + ("마".equals(n) ? ",\"useYn\":\"N\"" : "") + "}");
        }
    }

    private long id(MockHttpServletRequestBuilder b, String body) throws Exception {
        String s = mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(pm))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(s, "$.data")).longValue();
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

    private List<String> listed(String url, String field) throws Exception {
        return JsonPath.read(mvc.perform(get(url).with(pm)).andReturn().getResponse().getContentAsString(), "$.data[*]." + field);
    }

    @Test
    void 프로젝트_쪽_경계와_전체건수_정렬_목록과_같은_조건() throws Exception {
        grid(P, pm, "size", "2", "sort", "projNm,asc").andExpect(jsonPath("$.total").value(5));
        assertThat(col(grid(P, pm, "size", "2", "page", "1", "sort", "projNm,asc"), "projNm")).containsExactly("ZT 프로젝트 가", "ZT 프로젝트 나");
        assertThat(col(grid(P, pm, "size", "2", "page", "2", "sort", "projNm,asc"), "projNm")).containsExactly("ZT 프로젝트 다", "ZT 프로젝트 라");
        assertThat(col(grid(P, pm, "size", "2", "page", "3", "sort", "projNm,asc"), "projNm")).containsExactly("ZT 프로젝트 마");
        assertThat(col(grid(P, pm, "size", "2", "page", "4", "sort", "projNm,asc"), "projNm")).isEmpty();
        // 같은 값 정렬(상태 전부 DRAFT)도 PK 로 끝을 정해 쪽 사이 중복·누락 없음
        List<String> a = col(grid(P, pm, "size", "2", "page", "1", "sort", "status,asc"), "projNo");
        List<String> b = col(grid(P, pm, "size", "2", "page", "2", "sort", "status,asc"), "projNo");
        List<String> c = col(grid(P, pm, "size", "2", "page", "3", "sort", "status,asc"), "projNo");
        assertThat(java.util.stream.Stream.of(a, b, c).flatMap(List::stream).distinct()).hasSize(5);
        // 기본 정렬 = 목록 API 와 같은 순서(번호 내림차순), 같은 조건(진행 중 셋·유형)
        assertThat(col(grid(P, pm, "size", "50"), "projNo")).containsExactlyElementsOf(listed(P, "projNo"));
        assertThat(col(grid(P, pm, "status", "OPEN", "projType", "APQP", "keyword", "프로젝트 가"), "projNm")).containsExactly("ZT 프로젝트 가");
        grid(P, pm, "status", "CLOSED").andExpect(jsonPath("$.total").value(0));
        grid(P, pm, "status", "WIP").andExpect(status().isBadRequest());
        // 정렬 허용 목록 밖·주입 문자열·4개·쪽 크기·쪽 번호 → 400
        grid(P, pm, "sort", "updatedBy,asc").andExpect(status().isBadRequest());
        grid(P, pm, "sort", "proj_no;drop table pms_project,asc").andExpect(status().isBadRequest());
        grid(P, pm, "sort", "projNm,asc", "sort", "status,asc", "sort", "projNo,asc", "sort", "sopDate,asc").andExpect(status().isBadRequest());
        grid(P, pm, "size", "100000").andExpect(status().isBadRequest());
        grid(P, pm, "page", "0").andExpect(status().isBadRequest());
        mvc.perform(get(P + "/columns").with(pm)).andExpect(jsonPath("$.list[*].field").value(hasItems("projNo", "status", "lateCnt")));
    }

    @Test
    void 이슈_기본정렬은_목록처럼_심각도_높음_먼저_쪽_경계() throws Exception {
        assertThat(col(grid(IS, pm, "size", "1"), "title")).containsExactly("ZT 이슈 가");
        grid(IS, pm, "size", "2").andExpect(jsonPath("$.total").value(5));
        assertThat(col(grid(IS, pm, "size", "50"), "issueNo")).containsExactlyElementsOf(listed(IS, "issueNo"));
        assertThat(col(grid(IS, pm, "size", "2", "page", "1", "sort", "title,desc"), "title")).containsExactly("ZT 이슈 마", "ZT 이슈 라");
        assertThat(col(grid(IS, pm, "size", "2", "page", "3", "sort", "title,desc"), "title")).containsExactly("ZT 이슈 가");
        // 같은 조건: 심각도·종료·검색어
        grid(IS, pm, "severity", "HIGH").andExpect(jsonPath("$.total").value(1));
        grid(IS, pm, "status", "CLOSED").andExpect(jsonPath("$.total").value(0));
        grid(IS, pm, "keyword", "이슈 나").andExpect(jsonPath("$.total").value(1));
        grid(IS, pm, "sort", "detail,asc").andExpect(status().isBadRequest());
        grid(IS, pm, "sort", "severity").andExpect(status().isOk());
        mvc.perform(get(IS + "/columns").with(pm)).andExpect(jsonPath("$.list[*].field").value(hasItems("issueNo", "severity", "dueDate")));
    }

    @Test
    void 템플릿_사용여부_검색어_정렬() throws Exception {
        grid(T, pm).andExpect(jsonPath("$.total").value(5));
        grid(T, pm, "useYn", "N").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].tmplNm").value("ZT 템플릿 마"));
        grid(T, pm, "keyword", "템플릿 다").andExpect(jsonPath("$.total").value(1));
        assertThat(col(grid(T, pm, "size", "2", "page", "1", "sort", "tmplNm,asc"), "tmplNm")).containsExactly("ZT 템플릿 가", "ZT 템플릿 나");
        assertThat(col(grid(T, pm, "size", "2", "page", "3", "sort", "tmplNm,asc"), "tmplNm")).containsExactly("ZT 템플릿 마");
        assertThat(col(grid(T, pm, "useYn", "Y", "size", "50"), "tmplCd")).containsExactlyElementsOf(
                JsonPath.read(mvc.perform(get(T).param("useYn", "Y").with(pm)).andReturn().getResponse().getContentAsString(), "$.data[*].tmplCd"));
        grid(T, pm, "useYn", "X").andExpect(status().isBadRequest());
        grid(T, pm, "sort", "remark,asc").andExpect(status().isBadRequest());
        mvc.perform(get(T + "/columns").with(pm)).andExpect(jsonPath("$.list[*].field").value(hasItems("tmplCd", "useYn")));
    }

    @Test
    void 포트폴리오_계산칸_정렬_빈값은_끝_목록과_같은_순서() throws Exception {
        grid(H, pm, "size", "2").andExpect(jsonPath("$.total").value(5));
        // 기본 = 목록 API 와 같은 순서(SOP 가까운 순 — 모두 없음 → 번호)
        assertThat(col(grid(H, pm, "size", "50"), "projNo")).containsExactlyElementsOf(listed(H, "projNo"));
        // 열린 이슈 수(계산 칸): 첫 프로젝트 5건, 나머지 0 — 내림차순 맨 앞, 오름차순 맨 뒤
        Number top = JsonPath.read(grid(H, pm, "size", "1", "sort", "openIssueCnt,desc").andReturn().getResponse().getContentAsString(),
                "$.list[0].projId");
        assertThat(top.longValue()).isEqualTo(firstProj);
        Number last = JsonPath.read(grid(H, pm, "size", "1", "page", "5", "sort", "openIssueCnt,asc").andReturn().getResponse()
                .getContentAsString(), "$.list[0].projId");
        assertThat(last.longValue()).isEqualTo(firstProj);
        // 빈 값(준비율 — 필수 산출물 없음)은 방향과 상관없이 끝, 쪽 나눔 중복 없음
        List<String> a = col(grid(H, pm, "size", "3", "page", "1", "sort", "readyPct,desc"), "projNo");
        List<String> b = col(grid(H, pm, "size", "3", "page", "2", "sort", "readyPct,desc"), "projNo");
        assertThat(java.util.stream.Stream.concat(a.stream(), b.stream()).distinct()).hasSize(5);
        assertThat(col(grid(H, pm, "keyword", "프로젝트 나"), "projNm")).containsExactly("ZT 프로젝트 나");
        grid(H, pm, "status", "CLOSED").andExpect(jsonPath("$.total").value(0));
        grid(H, pm, "sort", "status,asc").andExpect(status().isBadRequest());
        grid(H, pm, "sort", "highIssueCnt,desc").andExpect(status().isBadRequest());
        mvc.perform(get(H + "/columns").with(pm)).andExpect(jsonPath("$.list[*].field").value(hasItems("sopDays", "readyPct", "nextGateNm")));
    }

    @Test
    void 권한없으면_403_A사로_B사_행을_못본다() throws Exception {
        // B사 같은 권한 사용자 — A사 프로젝트·이슈·템플릿 0건
        String other = accounts.newComp("PH");
        long bu = accounts.newUser(other, "pm1", PW);
        accounts.grant(other, bu, "PMS", "pms-project", "SEARCH");
        accounts.grant(other, bu, "PMS", "pms-issue", "SEARCH");
        accounts.grant(other, bu, "PMS", "pms-template", "SEARCH");
        accounts.grant(other, bu, "PMS", "pms-home", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "pm1");
        for (String base : List.of(P, IS, T, H)) {
            grid(base, b).andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.list.length()").value(0));
        }
        // B사 사용자가 A사 프로젝트 id 로 이슈 조건 → 0건(RLS)
        grid(IS, b, "projId", String.valueOf(firstProj), "status", "ALL").andExpect(jsonPath("$.total").value(0));
        // 화면 권한 없음 → grid·columns 403
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "pms-worklog", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        for (String base : List.of(P, IS, T, H)) {
            grid(base, n).andExpect(status().isForbidden());
            mvc.perform(get(base + "/columns").with(n)).andExpect(status().isForbidden());
        }
    }
}
