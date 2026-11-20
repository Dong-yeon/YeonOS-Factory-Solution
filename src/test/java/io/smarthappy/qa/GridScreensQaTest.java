package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — #609 일반 표 주 목록 5개 hb.grid(docs/40 10.2) 이상 사용. 모듈 시험(CmsEquipSecTest·PmsPickGridTest·ResultViewGridTest·PeriodGridTest)이
 * 목록 = grid 행·기본 순서·쪽 경계·건수, 정렬 허용 목록 400, 권한 403, A사로 B사를 덮는다. 여기서는 다섯 중 유일하게 **메모리에서 정렬**하는
 * 설비 보안 grid 의 기본 순서가, 목록(SQL order by — DB 정렬 규칙)과 구두점이 섞인 코드에서도 같은지. 분석 docs/qa/2026-10-28_grid-s2-f1-d2-calib.md
 */
@AutoConfigureMockMvc
class GridScreensQaTest extends PostgresTestBase {

    static final String PW = "Qa-grid-five-71";
    static final String S = "/api/cms/equip-secs";
    /** 영문 대문자·숫자·구두점 — 바이트 순서(Java String·C 정렬)와 언어 정렬(ICU·glibc)이 갈리는 짝: AB 와 A_1 */
    static final List<String> CODES = List.of("AB", "A_1", "A-2", "A1", "A.3");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QG");
        long u = accounts.newUser(comp, "qg1", PW);
        accounts.grant(comp, u, "CMS", "cms-equip-sec", "SEARCH");
        admin = accounts.as(comp, u, "qg1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'QG', 'QG', 't', 't') returning class_id",
                Long.class, comp);
        for (String cd : CODES) {
            accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', ?, ?, ?, 't', 't')",
                    comp, cd, cd + " 설비", cls);
        }
    }

    List<String> codes(String body, String path) {
        return JsonPath.read(body, path);
    }

    /**
     * 시험 DB(정렬 규칙 확인 후)에서 목록 = grid 기본 순서를 단언하고, 같은 코드를 ICU 언어 정렬로 줄 세운 순서도 찍는다.
     * 관찰 G-2: grid 가 Java 문자열 비교였을 때는 운영 DB 의 정렬 규칙이 C 가 아니면(설치형 glibc ko_KR·en_US 등) 목록(SQL)과 갈렸다.
     * 사용자 결정(2026-10-28) → 경고 필터·계산 칸 정렬이 없으면 grid 도 SQL 정렬(CMS docs/33 13.18) — 이제 DB 규칙이 무엇이든 목록 = grid
     */
    @Test
    void 관찰_설비보안_grid_기본순서와_목록순서_DB정렬규칙() throws Exception {
        String collate = jdbc.queryForObject("select datcollate from pg_database where datname = current_database()", String.class);
        List<String> list = codes(mvc.perform(get(S).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data[*].equipCd");
        List<String> grid = codes(mvc.perform(get(S + "/grid").param("size", "2").with(admin)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.list[*].equipCd");
        List<String> grid2 = codes(mvc.perform(get(S + "/grid").param("size", "2").param("page", "2").with(admin)).andReturn().getResponse()
                .getContentAsString(), "$.list[*].equipCd");
        List<String> grid3 = codes(mvc.perform(get(S + "/grid").param("size", "2").param("page", "3").with(admin)).andReturn().getResponse()
                .getContentAsString(), "$.list[*].equipCd");
        String icu = jdbc.queryForObject("select string_agg(c, ',' order by c collate \"und-x-icu\") from unnest(?::text[]) c", String.class,
                (Object) CODES.toArray(new String[0]));
        List<String> paged = new java.util.ArrayList<>(grid);
        paged.addAll(grid2);
        paged.addAll(grid3);
        System.out.println("[QG] datcollate=" + collate + " 목록=" + list + " grid=" + paged + " ICU=" + icu);
        // G-2 고친 뒤: grid 기본 순서도 SQL(목록과 같은 ORDER BY) — 시험 DB(postgres:16-alpine, musl 바이트 비교)든 언어 정렬 DB 든 목록 = grid
        assertThat(paged).as("grid 쪽을 이은 순서 = 목록(SQL) 순서").containsExactlyElementsOf(list);
        assertThat(paged).as("목록 순서 = 이 DB 의 정렬 규칙(SQL) — Java 비교가 아님").containsExactlyElementsOf(codes(jdbc.queryForObject(
                "select json_agg(c order by c) from unnest(?::text[]) c", String.class, (Object) CODES.toArray(new String[0])), "$[*]"));
        assertThat(icu).as("언어 정렬(ICU)에서는 바이트 순서와 다르다 — 운영 DB 가 언어 정렬이면 목록 ≠ grid")
                .isNotEqualTo(String.join(",", CODES.stream().sorted().toList()));
    }
}
