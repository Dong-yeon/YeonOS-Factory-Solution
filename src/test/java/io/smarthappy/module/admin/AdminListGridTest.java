package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * UI 단계 3 ① 1묶음 (docs/36 17.1) — 권한그룹·공통코드·채번 grid API: 목록과 같은 행·같은 기본 순서, 페이지 경계·전체 건수,
 * 정렬 허용 목록(계산 값·문구 키 이름은 정렬 안 됨), 검색어, 열 정의, 권한(SEARCH), A사로 B사. 기존 목록 API 는 그대로.
 */
@AutoConfigureMockMvc
class AdminListGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String G = "/api/admin/auth-groups";
    private static final String C = "/api/admin/codes";
    private static final String N = "/api/admin/numbering";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("AG");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-auth-group", "SEARCH");
        accounts.grant(comp, u, "ADM", "sys-code", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "sys-numbering", "SEARCH");
        admin = accounts.as(comp, u, "admin1");
        int sort = 50;
        for (String g : List.of("ZTG-C", "ZTG-A", "ZTG-E", "ZTG-B", "ZTG-D")) {
            accounts.exec(comp, "insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, sort_no, created_by, updated_by) values (?, ?, ?, ?, 't', 't')",
                    comp, g, "그룹 " + g.substring(4), sort--);
        }
        for (String r : List.of("ZTR_B", "ZTR_C", "ZTR_A")) {
            accounts.exec(comp, "insert into sys_numbering_rule (comp_cd, rule_cd, rule_nm, pattern, created_by, updated_by) values (?, ?, ?, 'ZT-{seq:4}', 't', 't')",
                    comp, r, "규칙 " + r.substring(4));
        }
        for (String c : List.of("ZTU3", "ZTU1", "ZTU2")) {
            mvc.perform(post(C + "/UNIT").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + c + "\",\"codeNm\":\"단위 " + c + "\",\"sortNo\":900,\"scale\":0}").with(admin))
                    .andExpect(status().isOk());
        }
    }

    private ResultActions grid(RequestPostProcessor who, String url, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(url + "/grid");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private static List<String> read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    private List<String> listed(String url, String path) throws Exception {
        return JsonPath.read(mvc.perform(get(url).with(admin)).andReturn().getResponse().getContentAsString(), path);
    }

    @Test
    void 권한그룹_grid_페이지경계_정렬허용목록_검색어() throws Exception {
        grid(admin, G, "keyword", "ztg", "size", "2").andExpect(jsonPath("$.total").value(5));
        assertThat(read(grid(admin, G, "keyword", "ztg", "size", "2", "sort", "authGrpCd,asc"), "$.list[*].authGrpCd"))
                .containsExactly("ZTG-A", "ZTG-B");
        assertThat(read(grid(admin, G, "keyword", "ztg", "size", "2", "page", "3", "sort", "authGrpCd,asc"), "$.list[*].authGrpCd"))
                .containsExactly("ZTG-E");
        assertThat(read(grid(admin, G, "keyword", "ztg", "size", "2", "page", "4", "sort", "authGrpCd,asc"), "$.list[*].authGrpCd")).isEmpty();
        assertThat(read(grid(admin, G, "keyword", "ztg", "sort", "authGrpNm,desc"), "$.list[*].authGrpCd"))
                .containsExactly("ZTG-E", "ZTG-D", "ZTG-C", "ZTG-B", "ZTG-A");
        // 기본 순서 = 목록 API(정렬 번호 → 코드), 고칠 수 있는가도 같이 온다
        assertThat(read(grid(admin, G, "size", "100"), "$.list[*].authGrpCd")).containsExactlyElementsOf(listed(G, "$.list[*].authGrpCd"));
        grid(admin, G, "keyword", "ztg-a").andExpect(jsonPath("$.list[0].editable").exists());
        // 검색어의 % _ 는 글자 그대로
        grid(admin, G, "keyword", "%").andExpect(jsonPath("$.total").value(0));
        // 허용 목록 밖·쪽 크기·검색어 길이
        grid(admin, G, "sort", "adminYn,asc").andExpect(status().isBadRequest());
        grid(admin, G, "sort", "homeProgCd,asc").andExpect(status().isBadRequest());
        grid(admin, G, "size", "0").andExpect(status().isBadRequest());
        grid(admin, G, "keyword", "x".repeat(51)).andExpect(status().isBadRequest());
        mvc.perform(get(G + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("authGrpCd", "memberCnt", "useYn")));
    }

    @Test
    void 공통코드_grid_목록과_같은_행_쪽을_이으면_목록_순서() throws Exception {
        List<String> all = listed(C + "/UNIT", "$.list[*].code");
        grid(admin, C, "grpCd", "UNIT", "size", "3").andExpect(jsonPath("$.total").value(all.size()));
        List<String> paged = new ArrayList<>();
        for (int p = 1; p <= (all.size() + 2) / 3; p++) {
            paged.addAll(read(grid(admin, C, "grpCd", "UNIT", "size", "3", "page", String.valueOf(p)), "$.list[*].code"));
        }
        assertThat(paged).containsExactlyElementsOf(all);          // 쪽 경계에서 중복·누락 없음
        assertThat(read(grid(admin, C, "grpCd", "UNIT", "size", "100", "sort", "code,desc"), "$.list[*].code")).first()
                .isEqualTo(all.stream().sorted((a, b) -> b.compareTo(a)).findFirst().orElseThrow());
        grid(admin, C, "grpCd", "UNIT", "sort", "code,asc", "size", "100").andExpect(jsonPath("$.list[?(@.code=='ZTU1')].system").value(false));
        // 이름은 시스템 코드가 문구 키라 정렬하지 않는다, 회사 코드를 못 넣는 그룹·그룹 없음은 목록과 같게 거절
        grid(admin, C, "grpCd", "UNIT", "sort", "name,asc").andExpect(status().isBadRequest());
        grid(admin, C, "grpCd", "CUST_TYPE").andExpect(status().isNotFound());
        assertThat(grid(admin, C).andReturn().getResponse().getStatus()).isBetween(400, 404);
        mvc.perform(get(C + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("code", "name", "system", "useYn")));
        // 그룹 이름 목록(/api/admin/codes)·그룹 코드(/api/admin/codes/UNIT)는 그대로
        mvc.perform(get(C).with(admin)).andExpect(status().isOk());
    }

    @Test
    void 채번_grid_계산값은_이쪽만_정렬은_허용목록() throws Exception {
        grid(admin, N, "keyword", "ztr").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, N, "keyword", "ztr", "size", "2"), "$.list[*].ruleCd")).containsExactly("ZTR_A", "ZTR_B");
        assertThat(read(grid(admin, N, "keyword", "ztr", "size", "2", "page", "2"), "$.list[*].ruleCd")).containsExactly("ZTR_C");
        assertThat(read(grid(admin, N, "keyword", "ztr", "sort", "ruleNm,desc"), "$.list[*].ruleCd")).containsExactly("ZTR_C", "ZTR_B", "ZTR_A");
        grid(admin, N, "keyword", "ztr_a").andExpect(jsonPath("$.list[0].nextPreview").value("ZT-0001"))
                .andExpect(jsonPath("$.list[0].pattern").value("ZT-{seq:4}"));
        List<String> all = JsonPath.read(mvc.perform(get(N).with(admin)).andReturn().getResponse().getContentAsString(), "$.list[*].rule.ruleCd");
        assertThat(read(grid(admin, N, "size", "100"), "$.list[*].ruleCd")).containsExactlyElementsOf(all);
        grid(admin, N, "sort", "lastNo,desc").andExpect(status().isBadRequest());
        grid(admin, N, "sort", "pattern,asc").andExpect(status().isBadRequest());
        mvc.perform(get(N + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("ruleCd", "lastNo", "nextPreview")));
    }

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        String other = accounts.newComp("AH");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "ADM", "sys-auth-group", "SEARCH");
        accounts.grant(other, bu, "ADM", "sys-code", "SEARCH");
        accounts.grant(other, bu, "ADM", "sys-numbering", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        grid(b, G, "keyword", "ztg").andExpect(jsonPath("$.total").value(0));
        grid(b, N, "keyword", "ztr").andExpect(jsonPath("$.total").value(0));
        assertThat(read(grid(b, C, "grpCd", "UNIT", "size", "100"), "$.list[*].code")).doesNotContain("ZTU1", "ZTU2", "ZTU3");
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "sys-user", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        for (String url : List.of(G, C, N)) {
            grid(n, url, "grpCd", "UNIT").andExpect(status().isForbidden());
            mvc.perform(get(url + "/columns").with(n)).andExpect(status().isForbidden());
        }
    }
}
