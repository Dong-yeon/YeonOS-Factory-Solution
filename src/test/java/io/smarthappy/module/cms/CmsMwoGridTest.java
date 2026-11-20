package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * UI 단계 3 ① (docs/33 15.1) — 보전 작업지시 grid API: 목록과 같은 조건, 서버 페이징 경계·전체 건수, 정렬 허용 목록(모르는 열·4개 이상 400),
 * 열 정의, 권한(SEARCH), A사로 B사. 기존 목록 API 는 그대로.
 */
@AutoConfigureMockMvc
class CmsMwoGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String today;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("MG");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', '프레스', ?, 't', 't')",
                comp, cls);
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        for (String t : List.of("다", "가", "마", "나", "라")) {
            mvc.perform(json(post(M), "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"정비 " + t + "\"}").with(admin))
                    .andExpect(status().isOk());
        }
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions grid(RequestPostProcessor who, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(M + "/grid").param("from", today).param("to", today);
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private List<String> titles(ResultActions r) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.list[*].title");
    }

    @Test
    void 페이지_경계와_전체건수_정렬_허용목록_목록과_같은_조건() throws Exception {
        // 제목 오름차순 2개씩: 1쪽 가·나, 2쪽 다·라, 3쪽 마, 4쪽 없음 — 전체 5
        grid(admin, "size", "2", "page", "1", "sort", "title,asc").andExpect(jsonPath("$.total").value(5));
        assertThat(titles(grid(admin, "size", "2", "page", "1", "sort", "title,asc"))).containsExactly("정비 가", "정비 나");
        assertThat(titles(grid(admin, "size", "2", "page", "2", "sort", "title,asc"))).containsExactly("정비 다", "정비 라");
        assertThat(titles(grid(admin, "size", "2", "page", "3", "sort", "title,asc"))).containsExactly("정비 마");
        assertThat(titles(grid(admin, "size", "2", "page", "4", "sort", "title,asc"))).isEmpty();
        assertThat(titles(grid(admin, "size", "5", "sort", "title,desc"))).containsExactly("정비 마", "정비 라", "정비 다", "정비 나", "정비 가");
        // 기본 정렬 = 신고 시각 내림차순(같으면 id 내림차순) — 목록 API 와 같은 순서
        List<String> listed = JsonPath.read(mvc.perform(get(M).param("from", today).param("to", today).with(admin))
                .andReturn().getResponse().getContentAsString(), "$.data[*].title");
        assertThat(titles(grid(admin, "size", "50"))).containsExactlyElementsOf(listed);
        // 같은 조건: 상태 WIP 0건
        grid(admin, "status", "WIP").andExpect(jsonPath("$.total").value(0));
        // 정렬 허용 목록 밖·정렬 4개·쪽 크기·쪽 번호 → 400
        grid(admin, "sort", "updatedBy,asc").andExpect(status().isBadRequest());
        grid(admin, "sort", "comp_cd;drop table x,asc").andExpect(status().isBadRequest());
        grid(admin, "sort", "title,asc", "sort", "status,asc", "sort", "mwoNo,asc", "sort", "dueDate,asc").andExpect(status().isBadRequest());
        grid(admin, "size", "100000").andExpect(status().isBadRequest());
        grid(admin, "page", "0").andExpect(status().isBadRequest());
        // 기간은 목록과 같이 필수
        mvc.perform(get(M + "/grid").with(admin)).andExpect(status().isBadRequest());
        // 열 정의
        mvc.perform(get(M + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("mwoNo", "status", "reportedAtText")));
    }

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        String other = accounts.newComp("MH");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-mwo", "SEARCH");
        grid(accounts.as(other, bu, "admin1")).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "cms-spare", "SEARCH");
        grid(accounts.as(comp, none, "none1")).andExpect(status().isForbidden());
        mvc.perform(get(M + "/columns").with(accounts.as(comp, none, "none1"))).andExpect(status().isForbidden());
    }
}
