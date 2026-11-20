package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.TestAccounts;

/**
 * 계획 목록 grid (UI 단계 3, docs/29 #45·#47) — 기존 목록 API 는 그대로, 같은 조건 + page·size·sort(허용 목록)·total.
 * 쪽 경계·전체 건수, 정렬(허용 목록 밖·주입 글자·4개 → 400), 기본 순서 = 목록 API, 같은 조건(공장), 기간 필수, 열 정의, 권한 403, A사로 B사.
 */
class ApsGridTest extends ApsTestBase {

    String today;

    @BeforeEach
    void plans() throws Exception {
        today = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + ")::text", String.class);
        // 이름 다·가·마·나·라 순서로 계산 — 계획 번호 순서와 이름 순서가 다르다
        for (String nm : List.of("계획 다", "계획 가", "계획 마", "계획 나", "계획 라")) {
            id(mvc.perform(json(post(A), "{\"plantCd\":\"P1\",\"horizonStart\":\"" + tomorrow + "\",\"horizonDays\":3,\"planNm\":\"" + nm + "\"}")
                    .with(admin)));
        }
    }

    private ResultActions grid(RequestPostProcessor who, String... kv) throws Exception {
        MockHttpServletRequestBuilder b = get(A + "/grid").param("from", today).param("to", today);
        for (int i = 0; i < kv.length; i += 2) {
            b = b.param(kv[i], kv[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private List<String> names(ResultActions r) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.list[*].planNm");
    }

    @Test
    void 쪽_경계와_전체건수_정렬_허용목록_목록과_같은_조건() throws Exception {
        // 이름 오름차순 2개씩: 1쪽 가·나, 2쪽 다·라, 3쪽 마, 4쪽 없음 — 전체 5
        grid(admin, "size", "2", "page", "1", "sort", "planNm,asc").andExpect(jsonPath("$.total").value(5));
        assertThat(names(grid(admin, "size", "2", "page", "1", "sort", "planNm,asc"))).containsExactly("계획 가", "계획 나");
        assertThat(names(grid(admin, "size", "2", "page", "2", "sort", "planNm,asc"))).containsExactly("계획 다", "계획 라");
        assertThat(names(grid(admin, "size", "2", "page", "3", "sort", "planNm,asc"))).containsExactly("계획 마");
        assertThat(names(grid(admin, "size", "2", "page", "4", "sort", "planNm,asc"))).isEmpty();
        assertThat(names(grid(admin, "size", "5", "sort", "planNm,desc"))).containsExactly("계획 마", "계획 라", "계획 다", "계획 나", "계획 가");
        // 계획 번호 오름차순 = 계산한 순서
        assertThat(names(grid(admin, "sort", "planNo,asc"))).containsExactly("계획 다", "계획 가", "계획 마", "계획 나", "계획 라");
        // 같은 값 정렬(상태 모두 계산됨)도 plan_id 로 순서가 고정 — 쪽을 넘겨도 빠지거나 겹치지 않는다
        List<String> byStatus = new ArrayList<>(names(grid(admin, "size", "3", "page", "1", "sort", "status,asc")));
        byStatus.addAll(names(grid(admin, "size", "3", "page", "2", "sort", "status,asc")));
        assertThat(byStatus).containsExactlyInAnyOrder("계획 가", "계획 나", "계획 다", "계획 라", "계획 마");
        // 기본 정렬 = 계산 시각 내림차순(같으면 id 내림차순) — 목록 API 와 같은 순서
        List<String> listed = JsonPath.read(mvc.perform(get(A).param("from", today).param("to", today).with(admin))
                .andReturn().getResponse().getContentAsString(), "$.data.rows[*].planNm");
        assertThat(names(grid(admin, "size", "50"))).containsExactlyElementsOf(listed);
        // 같은 조건: 다른 공장 0건, total=false 면 건수 없음
        grid(admin, "plantCd", "P2").andExpect(jsonPath("$.total").value(0));
        grid(admin, "total", "false").andExpect(jsonPath("$.total").doesNotExist()).andExpect(jsonPath("$.list.length()").value(5));
        // 정렬 허용 목록 밖·주입 글자·정렬 4개·쪽 크기·쪽 번호 → 400
        grid(admin, "sort", "calculatedBy,asc").andExpect(status().isBadRequest());
        grid(admin, "sort", "comp_cd;drop table aps_plan,asc").andExpect(status().isBadRequest());
        grid(admin, "sort", "planNo,asc", "sort", "status,asc", "sort", "planNm,asc", "sort", "plantCd,asc").andExpect(status().isBadRequest());
        grid(admin, "size", "100000").andExpect(status().isBadRequest());
        grid(admin, "page", "0").andExpect(status().isBadRequest());
        // 기간은 목록과 같이 필수, 거꾸로 된 기간은 칸 오류
        mvc.perform(get(A + "/grid").with(admin)).andExpect(status().isBadRequest());
        grid(admin, "from", tomorrow.toString()).andExpect(status().isBadRequest());
        // 열 정의
        mvc.perform(get(A + "/columns").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[*].field").value(hasItems("planNo", "planNm", "status", "calculatedAt")));
    }

    @Test
    void 권한없으면_403_B사는_A사_계획을_못본다() throws Exception {
        String other = accounts.newComp("APSG");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "APS", "aps-plan", "SEARCH");
        grid(accounts.as(other, bu, "admin1")).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        // A사 공장 코드로 물어도 0
        grid(accounts.as(other, bu, "admin1"), "plantCd", "P1").andExpect(jsonPath("$.total").value(0));
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "PRD", "prd-wo", "SEARCH");
        grid(accounts.as(comp, none, "none1")).andExpect(status().isForbidden());
        mvc.perform(get(A + "/columns").with(accounts.as(comp, none, "none1"))).andExpect(status().isForbidden());
    }
}
