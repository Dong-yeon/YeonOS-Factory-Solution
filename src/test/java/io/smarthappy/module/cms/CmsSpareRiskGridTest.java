package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * UI 단계 3 ① (docs/33 15.1) — 설비 예비품·위험성평가 grid API: 목록과 같은 조건·같은 기본 순서, 페이지 경계·전체 건수, 정렬 허용 목록,
 * 열 정의, 권한(SEARCH), A사로 B사. 기존 목록 API 는 그대로.
 */
@AutoConfigureMockMvc
class CmsSpareRiskGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String S = "/api/cms/spares";
    private static final String R = "/api/cms/risks";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String today;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("GR");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-spare", "SEARCH", "SAVE");
        accounts.grant(comp, u, "CMS", "cms-risk", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', '프레스', ?, 't', 't')",
                comp, cls);
        for (String i : List.of("C", "A", "E", "B", "D")) {
            accounts.exec(comp, "insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by) values (?, ?, ?, 'CON', 'BUY', 'EA', 't', 't')",
                    comp, "SP-" + i, "부품 " + i);
            post(S, "{\"targetKind\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"itemCd\":\"SP-" + i + "\",\"installQty\":\"1\",\"keySpareYn\":\"N\"}");
        }
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        for (String t : List.of("다", "가", "나")) {
            post(R, "{\"raType\":\"REGULAR\",\"method\":\"FREQ_SEV\",\"title\":\"평가 " + t + "\",\"assessDate\":\"" + today + "\"}");
        }
    }

    private void post(String url, String body) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body).with(admin)).andExpect(status().isOk());
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

    @Test
    void 예비품_grid_페이지경계_정렬허용목록_목록과_같은_조건() throws Exception {
        grid(admin, S, "size", "2").andExpect(jsonPath("$.total").value(5));
        assertThat(read(grid(admin, S, "size", "2", "page", "1", "sort", "itemCd,asc"), "$.list[*].itemCd")).containsExactly("SP-A", "SP-B");
        assertThat(read(grid(admin, S, "size", "2", "page", "3", "sort", "itemCd,asc"), "$.list[*].itemCd")).containsExactly("SP-E");
        assertThat(read(grid(admin, S, "size", "2", "page", "4", "sort", "itemCd,asc"), "$.list[*].itemCd")).isEmpty();
        assertThat(read(grid(admin, S, "size", "5", "sort", "itemNm,desc"), "$.list[*].itemCd")).containsExactly("SP-E", "SP-D", "SP-C", "SP-B", "SP-A");
        // 기본 순서 = 목록 API(대상 → 품목)
        List<String> listed = JsonPath.read(mvc.perform(get(S).with(admin)).andReturn().getResponse().getContentAsString(), "$.data[*].itemCd");
        assertThat(read(grid(admin, S, "size", "50"), "$.list[*].itemCd")).containsExactlyElementsOf(listed);
        // 같은 조건: 검색어·사용 여부
        grid(admin, S, "keyword", "sp-c").andExpect(jsonPath("$.total").value(1));
        grid(admin, S, "useYn", "N").andExpect(jsonPath("$.total").value(0));
        grid(admin, S, "useYn", "X").andExpect(status().isBadRequest());
        // 허용 목록 밖·쪽 크기
        grid(admin, S, "sort", "remark,asc").andExpect(status().isBadRequest());
        grid(admin, S, "sort", "spec,asc").andExpect(status().isBadRequest());
        grid(admin, S, "size", "0").andExpect(status().isBadRequest());
        mvc.perform(get(S + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("targetKind", "itemCd", "installQty")));
    }

    @Test
    void 위험성평가_grid_페이지경계_정렬_기간필수() throws Exception {
        grid(admin, R, "from", today, "to", today, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, R, "from", today, "to", today, "size", "2", "sort", "title,asc"), "$.list[*].title"))
                .containsExactly("평가 가", "평가 나");
        assertThat(read(grid(admin, R, "from", today, "to", today, "size", "2", "page", "2", "sort", "title,asc"), "$.list[*].title"))
                .containsExactly("평가 다");
        List<String> listed = JsonPath.read(mvc.perform(get(R).param("from", today).param("to", today).with(admin))
                .andReturn().getResponse().getContentAsString(), "$.data[*].raNo");
        assertThat(read(grid(admin, R, "from", today, "to", today), "$.list[*].raNo")).containsExactlyElementsOf(listed);
        grid(admin, R, "from", today, "to", today, "status", "RELEASED").andExpect(jsonPath("$.total").value(0));
        grid(admin, R).andExpect(status().isBadRequest());                                     // 기간 필수(목록과 같음)
        grid(admin, R, "from", today, "to", today, "sort", "itemCnt,desc").andExpect(status().isBadRequest());
        mvc.perform(get(R + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("raNo", "status", "overdueCnt")));
    }

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        String other = accounts.newComp("GS");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-spare", "SEARCH");
        accounts.grant(other, bu, "CMS", "cms-risk", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        grid(b, S).andExpect(jsonPath("$.total").value(0));
        grid(b, R, "from", today, "to", today).andExpect(jsonPath("$.total").value(0));
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "cms-mwo", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        grid(n, S).andExpect(status().isForbidden());
        grid(n, R, "from", today, "to", today).andExpect(status().isForbidden());
        mvc.perform(get(S + "/columns").with(n)).andExpect(status().isForbidden());
        mvc.perform(get(R + "/columns").with(n)).andExpect(status().isForbidden());
    }
}
