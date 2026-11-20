package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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
 * UI 단계 3 ① 다음 묶음 (docs/33 15.1) — 고장 코드·설비 프로필·카운터·예방점검 계획 grid API: 목록과 같은 조건·같은 기본 순서,
 * 페이지 경계·전체 건수, 정렬 허용 목록, 열 정의, 권한(SEARCH), A사로 B사. 기존 목록 API 는 그대로.
 */
@AutoConfigureMockMvc
class CmsMasterGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String F = "/api/cms/fail-codes";
    private static final String E = "/api/cms/equip-profiles";
    private static final String C = "/api/cms/counters";
    private static final String P = "/api/cms/pm-plans";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("GM");
        long u = accounts.newUser(comp, "admin1", PW);
        grant(comp, u);
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        for (String[] e : new String[][] {{"P2", "PRS-03"}, {"P1", "PRS-02"}, {"P1", "PRS-01"}}) {
            accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, ?, ?, ?, ?, 't', 't')",
                    comp, e[0], e[1], "프레스 " + e[1], cls);
        }
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 고장 코드 3: 유형 → 순서 → 코드
        save(F, "{\"codeType\":\"CAUSE\",\"failCd\":\"WEAR\",\"failNm\":\"마모\",\"failCat\":\"MECH\"}");
        save(F, "{\"codeType\":\"SYMPTOM\",\"failCd\":\"LEAK\",\"failNm\":\"누유\"}");
        save(F, "{\"codeType\":\"SYMPTOM\",\"failCd\":\"NOISE\",\"failNm\":\"소음\"}");
        // 카운터 3
        for (String c : List.of("CNT-C", "CNT-A", "CNT-B")) {
            save(C, "{\"counterCd\":\"" + c + "\",\"counterNm\":\"" + c + "\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"counterKind\":\"SHOTS\",\"scale\":0}");
        }
        // 예방점검 계획 3: 다음 예정이 서로 다르게(첫 예정일 = 다음 예정)
        long tmpl = id(save("/api/cms/chk-tmpls", "{\"tmplCd\":\"PRS-D\",\"tmplNm\":\"프레스 일상\"}"));
        mvc.perform(json(put("/api/cms/chk-tmpls/" + tmpl + "/items?rowVer=0"), "[{\"seq\":1,\"itemNm\":\"클러치 소음\",\"judgeType\":\"OKNG\"}]").with(admin))
                .andExpect(status().isOk());
        int d = 3;
        for (String p : List.of("PM-A", "PM-B", "PM-C")) {
            save(P, "{\"planCd\":\"" + p + "\",\"planNm\":\"" + p + " 점검\",\"targetCd\":\"PRS-0" + d + "\",\"tmplCd\":\"PRS-D\",\"execMode\":\"CHECK\","
                    + "\"calUnit\":\"MONTH\",\"calInterval\":1,\"scheduleBasis\":\"FIXED\",\"anchorDate\":\"" + today.plusDays(d) + "\"}");
            d--;
        }
    }

    private void grant(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-fail-code", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-equip-profile", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-counter", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-chk-tmpl", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-pm-plan", "SEARCH", "SAVE");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions save(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin)).andExpect(status().isOk());
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.data")).longValue();
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

    /** 기본 순서 = 목록 API 순서 */
    private void sameAsList(String url, String field) throws Exception {
        List<String> listed = JsonPath.read(mvc.perform(get(url).with(admin)).andReturn().getResponse().getContentAsString(), "$.data[*]." + field);
        assertThat(listed).isNotEmpty();
        assertThat(read(grid(admin, url, "size", "50"), "$.list[*]." + field)).containsExactlyElementsOf(listed);
    }

    @Test
    void 고장코드_grid_페이지경계_정렬_목록과_같은_조건() throws Exception {
        grid(admin, F, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, F, "size", "2", "page", "1", "sort", "failCd,asc"), "$.list[*].failCd")).containsExactly("LEAK", "NOISE");
        assertThat(read(grid(admin, F, "size", "2", "page", "2", "sort", "failCd,asc"), "$.list[*].failCd")).containsExactly("WEAR");
        assertThat(read(grid(admin, F, "size", "2", "page", "3", "sort", "failCd,asc"), "$.list[*].failCd")).isEmpty();
        sameAsList(F, "failCd");
        grid(admin, F, "codeType", "SYMPTOM").andExpect(jsonPath("$.total").value(2));
        grid(admin, F, "keyword", "wea").andExpect(jsonPath("$.total").value(1));
        grid(admin, F, "codeType", "NOPE").andExpect(status().isBadRequest());
        grid(admin, F, "sort", "remark,asc").andExpect(status().isBadRequest());
        mvc.perform(get(F + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("codeType", "failCd", "useYn")));
    }

    @Test
    void 설비프로필_grid_페이지경계_정렬_목록과_같은_조건() throws Exception {
        grid(admin, E, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, E, "size", "2", "sort", "equipCd,desc"), "$.list[*].equipCd")).containsExactly("PRS-03", "PRS-02");
        assertThat(read(grid(admin, E, "size", "2", "page", "2", "sort", "equipCd,desc"), "$.list[*].equipCd")).containsExactly("PRS-01");
        sameAsList(E, "equipCd");                                                  // 공장 → 설비 코드
        grid(admin, E, "plantCd", "p1").andExpect(jsonPath("$.total").value(2));
        grid(admin, E, "keyOnly", "Y").andExpect(jsonPath("$.total").value(0));
        grid(admin, E, "criticality", "Z").andExpect(status().isBadRequest());
        grid(admin, E, "sort", "openMwoCnt,desc").andExpect(status().isBadRequest());   // 하위 질의 열은 정렬 밖
        mvc.perform(get(E + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("equipCd", "criticality", "openMwoCnt")));
    }

    @Test
    void 카운터_grid_페이지경계_정렬_목록과_같은_조건() throws Exception {
        grid(admin, C, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, C, "size", "2"), "$.list[*].counterCd")).containsExactly("CNT-A", "CNT-B");
        assertThat(read(grid(admin, C, "size", "2", "page", "2"), "$.list[*].counterCd")).containsExactly("CNT-C");
        assertThat(read(grid(admin, C, "size", "3", "sort", "counterNm,desc"), "$.list[*].counterCd")).containsExactly("CNT-C", "CNT-B", "CNT-A");
        sameAsList(C, "counterCd");
        grid(admin, C, "size", "1").andExpect(jsonPath("$.list[0].totalText").value("0")).andExpect(jsonPath("$.list[0].life").exists());
        grid(admin, C, "targetType", "ASSET").andExpect(jsonPath("$.total").value(0));
        grid(admin, C, "targetType", "X").andExpect(status().isBadRequest());
        grid(admin, C, "sort", "totalText,asc").andExpect(status().isBadRequest());
        mvc.perform(get(C + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("counterCd", "totalText", "lastReadAt")));
    }

    @Test
    void 계획_grid_기본순서는_다음예정_상태는_계산값_정렬밖() throws Exception {
        grid(admin, P, "size", "2").andExpect(jsonPath("$.total").value(3));
        // 다음 예정 오름차순: PM-C(+1) → PM-B(+2) → PM-A(+3)
        assertThat(read(grid(admin, P, "size", "2"), "$.list[*].planCd")).containsExactly("PM-C", "PM-B");
        assertThat(read(grid(admin, P, "size", "2", "page", "2"), "$.list[*].planCd")).containsExactly("PM-A");
        assertThat(read(grid(admin, P, "size", "3", "sort", "planCd,asc"), "$.list[*].planCd")).containsExactly("PM-A", "PM-B", "PM-C");
        sameAsList(P, "planCd");
        assertThat(read(grid(admin, P, "size", "3"), "$.list[*].state")).doesNotContainNull();
        grid(admin, P, "plantCd", "P2").andExpect(jsonPath("$.total").value(1));
        grid(admin, P, "useYn", "X").andExpect(status().isBadRequest());
        grid(admin, P, "sort", "state,asc").andExpect(status().isBadRequest());
        mvc.perform(get(P + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("planCd", "nextDueText", "state")));
    }

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        String other = accounts.newComp("GN");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        for (String url : List.of(F, E, C, P)) {
            grid(b, url).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        }
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "cms-mwo", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        for (String url : List.of(F, E, C, P)) {
            grid(n, url).andExpect(status().isForbidden());
            mvc.perform(get(url + "/columns").with(n)).andExpect(status().isForbidden());
        }
    }
}
