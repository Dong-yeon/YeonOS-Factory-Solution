package io.smarthappy.module.cms;

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
 * UI 단계 3 ① 4묶음 (docs/33 15.1) — 상태 사유·상태 검토 grid API: 목록과 같은 조건·같은 기본 순서, 페이지 경계·전체 건수,
 * 정렬 허용 목록(분·사유는 400), 열 정의, 권한(SEARCH), A사로 B사.
 */
@AutoConfigureMockMvc
class CmsStateGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String R = "/api/cms/state-reasons";
    private static final String V = "/api/cms/state-review";
    private static final String DAY = "2026-03-02";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("GS");
        long u = accounts.newUser(comp, "admin1", PW);
        grant(comp, u);
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        long equip = accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', 'PRS-01', '프레스', ?, 't', 't') returning equip_id""", Long.class, comp, cls);
        // 사유 3: 분류 → 순서 → 코드 = SDT·PM-C, UDT·BRK-A, UDT·BRK-B
        save("{\"reasonCd\":\"PM-C\",\"reasonNm\":\"정기 보전\",\"stateCat\":\"SDT\",\"plannedYn\":\"Y\"}");
        save("{\"reasonCd\":\"BRK-B\",\"reasonNm\":\"모터 고장\",\"stateCat\":\"UDT\",\"plannedYn\":\"N\"}");
        save("{\"reasonCd\":\"BRK-A\",\"reasonNm\":\"금형 고장\",\"stateCat\":\"UDT\",\"plannedYn\":\"N\"}");
        // 원장 구간 3 (09:00~09:10 정지, 09:10~09:40 가동, 09:40~ 열린 정지)
        interval(equip, "UDT", "09:00", "09:10");
        interval(equip, "PRD", "09:10", "09:40");
        interval(equip, "UDT", "09:40", null);
    }

    private void grant(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-state-reason", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-state-review", "SEARCH", "SAVE");
    }

    private void save(String body) throws Exception {
        mvc.perform(post(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin)).andExpect(status().isOk());
    }

    private void interval(long equip, String cat, String start, String end) {
        accounts.query(comp, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, reason_id, start_at, end_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, null, ?::timestamptz, ?::timestamptz, 'AUTO', ?::timestamptz, 'AUTO', 'col')
                returning state_log_id""", Long.class, comp, equip, cat, DAY + "T" + start + ":00+09:00",
                end == null ? null : DAY + "T" + end + ":00+09:00", DAY + "T" + start + ":00+09:00");
    }

    private ResultActions grid(RequestPostProcessor who, String url, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(url + "/grid");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private static <T> List<T> read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    @Test
    void 상태사유_grid_기본은_분류_순서_코드_목록과_같은_순서() throws Exception {
        grid(admin, R, "size", "2").andExpect(jsonPath("$.total").value(3));
        List<String> listed = JsonPath.read(mvc.perform(get(R).with(admin)).andReturn().getResponse().getContentAsString(), "$.data[*].reasonCd");
        assertThat(read(grid(admin, R, "size", "50"), "$.list[*].reasonCd")).containsExactlyElementsOf(listed);
        assertThat(read(grid(admin, R, "size", "2", "sort", "reasonCd,asc"), "$.list[*].reasonCd")).containsExactly("BRK-A", "BRK-B");
        assertThat(read(grid(admin, R, "size", "2", "page", "2", "sort", "reasonCd,asc"), "$.list[*].reasonCd")).containsExactly("PM-C");
        grid(admin, R, "stateCat", "UDT").andExpect(jsonPath("$.total").value(2));
        grid(admin, R, "stateCat", "NOPE").andExpect(status().isBadRequest());
        grid(admin, R, "sort", "updatedBy,asc").andExpect(status().isBadRequest());
        mvc.perform(get(R + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("stateCat", "reasonCd", "plannedYn")));
    }

    @Test
    void 상태검토_grid_공장기간필수_기본은_시작최신순_목록과_같은_조건() throws Exception {
        grid(admin, V, "plantCd", "P1", "from", DAY, "to", DAY, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, V, "plantCd", "P1", "from", DAY, "to", DAY, "size", "2"), "$.list[*].startAt"))
                .containsExactly(DAY + "T09:40:00+09:00", DAY + "T09:10:00+09:00");                 // 공장 현지 시각
        assertThat(read(grid(admin, V, "plantCd", "P1", "from", DAY, "to", DAY, "size", "2", "page", "2"), "$.list[*].effCat"))
                .containsExactly("UDT");
        List<Object> listed = JsonPath.read(mvc.perform(get(V).param("plantCd", "P1").param("from", DAY).param("to", DAY).with(admin))
                .andReturn().getResponse().getContentAsString(), "$.data[*].stateLogId");
        assertThat(read(grid(admin, V, "plantCd", "P1", "from", DAY, "to", DAY), "$.list[*].stateLogId")).containsExactlyElementsOf(listed);
        grid(admin, V, "plantCd", "P1", "from", DAY, "to", DAY, "pending", "Y").andExpect(jsonPath("$.total").value(2));   // 사유 없는 정지 2
        grid(admin, V, "plantCd", "P1", "from", DAY, "to", DAY, "effCat", "PRD").andExpect(jsonPath("$.total").value(1));
        grid(admin, V, "from", DAY, "to", DAY).andExpect(status().isBadRequest());                                        // 공장 필수
        grid(admin, V, "plantCd", "P1", "from", DAY, "to", DAY, "sort", "minutes,desc").andExpect(status().isBadRequest());
        mvc.perform(get(V + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("equipCd", "effCat", "minutes")));
    }

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        String other = accounts.newComp("GT");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        accounts.addPlant(other, "P1", "1공장");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        grid(b, R).andExpect(jsonPath("$.total").value(0));
        grid(b, V, "plantCd", "P1", "from", DAY, "to", DAY).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "cms-mwo", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        grid(n, R).andExpect(status().isForbidden());
        grid(n, V, "plantCd", "P1", "from", DAY, "to", DAY).andExpect(status().isForbidden());
        mvc.perform(get(R + "/columns").with(n)).andExpect(status().isForbidden());
        mvc.perform(get(V + "/columns").with(n)).andExpect(status().isForbidden());
    }
}
