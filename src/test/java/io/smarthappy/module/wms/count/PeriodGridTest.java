package io.smarthappy.module.wms.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 기간 마감 목록 grid (docs/40 §10.2 D1, 결정 docs/29 #45) — 기존 목록 API 와 같은 12달·같은 기본 순서(월 오름차순),
 * 행 없는 달 = OPEN(목록은 null), 쪽 경계·전체 건수, 정렬 허용 목록, 열 정의, 권한(SEARCH), A사로 B사.
 */
@AutoConfigureMockMvc
class PeriodGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/wms/periods";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PG");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-period", "SEARCH");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        // 2024년: 1월 재개, 3월·7월 마감, 나머지는 행 없음(열림)
        close("202401", "2024-02-01T09:00:00+09:00");
        accounts.exec(comp, "update sys_period_close set status = 'REOPENED', reopened_at = '2024-02-10T09:00:00+09:00', reopened_by = 'u2', "
                + "reason = '누락 입고' where period = '202401'");
        close("202403", "2024-04-02T09:00:00+09:00");
        close("202407", "2024-08-01T09:00:00+09:00");
    }

    private void close(String period, String at) {
        accounts.exec(comp, "insert into sys_period_close (comp_cd, plant_cd, module_cd, period, status, closed_at, closed_by, created_by, updated_by) "
                + "values (?, 'P1', 'INV', ?, 'CLOSED', ?::timestamptz, 'u1', 't', 't')", comp, period, at);
    }

    private ResultActions grid(RequestPostProcessor who, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(P + "/grid").param("plantCd", "P1").param("year", "2024");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private static <T> List<T> read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    @Test
    void grid_목록과_같은_12달_같은_순서_쪽경계_건수() throws Exception {
        ResultActions listed = mvc.perform(get(P).param("plantCd", "P1").param("year", "2024").with(admin));
        List<String> all = read(listed, "$.list[*].period");
        assertThat(all).hasSize(12).first().isEqualTo("202401");
        // 기본 순서 = 목록(월 오름차순), 상태는 목록 null ↔ 그리드 OPEN
        ResultActions g = grid(admin, "size", "100").andExpect(jsonPath("$.total").value(12));
        assertThat(read(g, "$.list[*].period")).containsExactlyElementsOf(all);
        List<String> st = read(grid(admin, "size", "100"), "$.list[*].status");
        assertThat(st).containsExactly("REOPENED", "OPEN", "CLOSED", "OPEN", "OPEN", "OPEN", "CLOSED", "OPEN", "OPEN", "OPEN", "OPEN", "OPEN");
        grid(admin, "size", "100").andExpect(jsonPath("$.list[0].reason").value("누락 입고"))
                .andExpect(jsonPath("$.list[2].closedBy").value("u1")).andExpect(jsonPath("$.list[2].rowVer").value(0))
                .andExpect(jsonPath("$.list[1].closeId").doesNotExist());
        // 쪽 경계: 5건씩 3쪽을 이으면 목록과 같다(중복·누락 없음), 넘는 쪽은 빈 목록
        List<String> paged = new ArrayList<>();
        for (int p = 1; p <= 3; p++) {
            paged.addAll(read(grid(admin, "size", "5", "page", String.valueOf(p)), "$.list[*].period"));
        }
        assertThat(paged).containsExactlyElementsOf(all);
        assertThat(read(grid(admin, "size", "5", "page", "3"), "$.list[*].period")).containsExactly("202411", "202412");
        assertThat(read(grid(admin, "size", "5", "page", "4"), "$.list[*].period")).isEmpty();
    }

    @Test
    void 정렬_허용목록_열정의_조건검사() throws Exception {
        assertThat(read(grid(admin, "sort", "period,desc"), "$.list[*].period")).first().isEqualTo("202412");
        // 상태 오름차순: CLOSED(3·7월) → OPEN(월 순) → REOPENED(1월), 같은 값은 월로 고정
        List<String> byStatus = read(grid(admin, "sort", "status,asc"), "$.list[*].period");
        assertThat(byStatus.subList(0, 3)).containsExactly("202403", "202407", "202402");
        assertThat(byStatus).last().isEqualTo("202401");
        assertThat(read(grid(admin, "sort", "closedAt,desc", "size", "3"), "$.list[*].period"))
                .containsExactly("202407", "202403", "202401");                   // 시각 정렬은 빈 값(열림)을 늘 뒤로
        // 허용 목록 밖(사람·사유·식별자), 쪽 크기, 공장·연도 검사는 목록과 같게
        grid(admin, "sort", "closedBy,asc").andExpect(status().isBadRequest());
        grid(admin, "sort", "reason,asc").andExpect(status().isBadRequest());
        grid(admin, "sort", "closeId,asc").andExpect(status().isBadRequest());
        grid(admin, "size", "0").andExpect(status().isBadRequest());
        mvc.perform(get(P + "/grid").param("plantCd", "P1").param("year", "1999").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(P + "/grid").param("year", "2024").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(P + "/columns").with(admin))
                .andExpect(jsonPath("$.list[*].field").value(hasItems("period", "status", "closedAt", "closedBy", "reopenedAt", "reason")));
    }

    @Test
    void 권한없으면_403_A사로_B사_공장은_못읽는다() throws Exception {
        String other = accounts.newComp("PH");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "WMS", "wms-period", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        // B사에는 P1 공장이 없다 → A사 공장 코드로 물어도 공장 없음(400), A사 마감 행은 안 나온다
        grid(b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        // B사에 같은 코드 공장을 만들어도 A사 마감 행은 안 보인다(RLS) — 12달 모두 열림
        accounts.addPlant(other, "P1", "B 1공장");
        assertThat(read(grid(b, "size", "100"), "$.list[*].status")).hasSize(12).containsOnly("OPEN");
        // 권한 없음
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "sys-user", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        grid(n).andExpect(status().isForbidden());
        mvc.perform(get(P + "/columns").with(n)).andExpect(status().isForbidden());
    }
}
