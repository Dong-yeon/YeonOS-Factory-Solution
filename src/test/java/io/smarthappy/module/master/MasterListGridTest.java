package io.smarthappy.module.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 기준정보 표 화면 주 목록 그리드 API (UI 단계 3 ①, 결정 docs/29 #45·#48) — 불량 유형·공정·용어 `/grid` + `/columns`.
 * 기존 목록 API 는 그대로, 새 API 는 페이지 경계·정렬 허용 열·조건·빠른 찾기(LIKE 특수 문자는 글자)·건수, 조회 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class MasterListGridTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String DEFECTS = "/api/master/defects";
    private static final String PROCS = "/api/master/procs";
    private static final String TERMS = "/api/master/terms";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("MG");
        admin = user(comp, "admin1", true);
        seed(comp, 5);
    }

    private RequestPostProcessor user(String c, String id, boolean search) {
        long u = accounts.newUser(c, id, PW);
        if (search) {
            accounts.grant(c, u, "MST", "mst-defect", "SEARCH");
            accounts.grant(c, u, "MST", "mst-proc", "SEARCH");
            accounts.grant(c, u, "MST", "mst-term", "SEARCH");
        }
        return accounts.as(c, u, id);
    }

    /**
     * 불량: 외관(APPEARANCE, 상위 순서 1) n 개 D-1..D-n(순서 = 번호) + 치수(DIMENSION, 2) "X_1" 하나(미사용).
     * 공정: P-1..P-n(순서 = 번호, 마지막 미사용). 동의어: GOOD_QTY 에 "합격수" 하나.
     */
    private void seed(String c, int n) {
        for (int i = 1; i <= n; i++) {
            accounts.exec(c, """
                    insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, sort_no, created_by, updated_by)
                    values (?, ?, ?, 'APPEARANCE', ?, 't', 't')""", c, "D-" + i, "외관 불량 " + i, i);
            accounts.exec(c, """
                    insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, sort_no, use_yn, created_by, updated_by)
                    values (?, ?, ?, 'PRESS', ?, ?, 't', 't')""", c, "P-" + i, "프레스 " + i, i, i == n ? "N" : "Y");
        }
        accounts.exec(c, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, sort_no, use_yn, created_by, updated_by)
                values (?, 'X_1', '치수 100%', 'DIMENSION', 1, 'N', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_term_alias (comp_cd, term_cd, alias, created_by, updated_by)
                values (?, 'GOOD_QTY', '합격수', 't', 't')""", c);
    }

    /** 조회 — 쿼리 문자열은 param 으로(정렬 값 "필드,방향" 의 쉼표가 목록 구분으로 나뉘지 않게, hb.grid 가 보내는 모양과 같음) */
    private MockHttpServletRequestBuilder req(String url) {
        int q = url.indexOf('?');
        MockHttpServletRequestBuilder b = get(q < 0 ? url : url.substring(0, q));
        if (q >= 0) {
            for (String kv : url.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                b = b.param(kv.substring(0, e), kv.substring(e + 1));
            }
        }
        return b;
    }

    private MvcResult ok(RequestPostProcessor who, String url) throws Exception {
        MvcResult r = mvc.perform(req(url).with(who)).andReturn();
        assertThat(r.getResponse().getStatus()).as(url + " → " + r.getResponse().getContentAsString()).isEqualTo(200);
        return r;
    }

    private static List<String> field(MvcResult r, String path) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), path);
    }

    private static int total(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.total")).intValue();
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 불량_그리드는_기존_목록과_같은_순서로_페이지를_나누고_경계에서_빠지거나_겹치지_않는다() throws Exception {
        MvcResult p1 = ok(admin, DEFECTS + "/grid?page=1&size=2");
        assertThat(field(p1, "$.list[*].defectCd")).containsExactly("D-1", "D-2");
        assertThat(total(p1)).isEqualTo(6);
        assertThat(field(ok(admin, DEFECTS + "/grid?page=2&size=2"), "$.list[*].defectCd")).containsExactly("D-3", "D-4");
        assertThat(field(ok(admin, DEFECTS + "/grid?page=3&size=2"), "$.list[*].defectCd")).containsExactly("D-5", "X_1");
        assertThat(field(ok(admin, DEFECTS + "/grid?page=4&size=2"), "$.list[*].defectCd")).isEmpty();
        // 기존 목록 API 와 같은 순서·같은 조건(사용만)
        assertThat(field(ok(admin, DEFECTS + "/grid?useYn=Y&size=100"), "$.list[*].defectCd"))
                .containsExactlyElementsOf(field(ok(admin, DEFECTS + "?useYn=Y"), "$.data[*].defectCd"));
        // 정렬 허용 열·여러 키, total=false
        assertThat(field(ok(admin, DEFECTS + "/grid?sort=catCd,desc&size=1"), "$.list[*].defectCd")).containsExactly("X_1");
        assertThat(field(ok(admin, DEFECTS + "/grid?sort=defectNm,desc&sort=defectCd,asc&size=1"), "$.list[*].defectCd"))
                .containsExactly("X_1");                                                    // "치수" > "외관"
        assertThat(field(ok(admin, DEFECTS + "/grid?sort=sortNo,desc&size=1"), "$.list[*].defectCd")).containsExactly("D-5");
        mvc.perform(req(DEFECTS + "/grid?total=false").with(admin)).andExpect(jsonPath("$.total").doesNotExist());
    }

    @Test
    void 빠른_찾기는_코드와_이름에서_찾고_LIKE_특수_문자는_글자로_본다() throws Exception {
        assertThat(field(ok(admin, DEFECTS + "/grid?keyword=불량 3"), "$.list[*].defectCd")).containsExactly("D-3");
        assertThat(field(ok(admin, DEFECTS + "/grid?keyword=d-"), "$.list[*].defectCd")).hasSize(5);      // 대소문자 무시
        assertThat(field(ok(admin, DEFECTS + "/grid?keyword=_"), "$.list[*].defectCd")).containsExactly("X_1"); // _ 는 아무 한 글자가 아님
        assertThat(field(ok(admin, DEFECTS + "/grid?keyword=%"), "$.list[*].defectCd")).containsExactly("X_1"); // % 도 글자
        assertThat(field(ok(admin, DEFECTS + "/grid?keyword=  D-2  &useYn=Y"), "$.list[*].defectCd")).containsExactly("D-2");
        assertThat(field(ok(admin, PROCS + "/grid?keyword=프레스 4"), "$.list[*].procCd")).containsExactly("P-4");
        assertThat(field(ok(admin, PROCS + "/grid?keyword=p-5&useYn=Y"), "$.list[*].procCd")).isEmpty();   // 미사용은 조건대로 빠짐
    }

    @Test
    void 공정_그리드는_순서_코드_순_페이지와_건수_사용_조건과_정렬_허용_열() throws Exception {
        MvcResult p = ok(admin, PROCS + "/grid?page=2&size=2");
        assertThat(field(p, "$.list[*].procCd")).containsExactly("P-3", "P-4");
        assertThat(total(p)).isEqualTo(5);
        assertThat(field(ok(admin, PROCS + "/grid?page=3&size=2"), "$.list[*].procCd")).containsExactly("P-5");
        assertThat(total(ok(admin, PROCS + "/grid?useYn=Y"))).isEqualTo(4);
        assertThat(field(ok(admin, PROCS + "/grid?sort=procCd,desc&size=2"), "$.list[*].procCd")).containsExactly("P-5", "P-4");
        assertThat(field(ok(admin, PROCS + "/grid?sort=routingCnt,desc&sort=procNm,asc&size=1"), "$.list[*].procCd")).containsExactly("P-1");
        // 기존 목록 API 는 그대로(전체, 같은 순서)
        assertThat(field(ok(admin, PROCS + "/grid?size=100"), "$.list[*].procCd"))
                .containsExactlyElementsOf(field(ok(admin, PROCS), "$.data[*].procCd"));
    }

    @Test
    void 용어_그리드는_전역_용어_전체와_이_회사_동의어_수_동의어로도_찾는다() throws Exception {
        long all = accounts.query(comp, "select count(*) from sys_term", Long.class);
        MvcResult r = ok(admin, TERMS + "/grid?size=3");
        assertThat(total(r)).isEqualTo((int) all);
        assertThat(field(r, "$.list[*].termCd"))
                .containsExactlyElementsOf(field(ok(admin, TERMS), "$.data[*].termCd").subList(0, 3));   // 기존 목록과 같은 순서
        MvcResult a = ok(admin, TERMS + "/grid?keyword=합격");
        assertThat(field(a, "$.list[*].termCd")).containsExactly("GOOD_QTY");
        assertThat((List<Integer>) JsonPath.read(a.getResponse().getContentAsString(), "$.list[*].aliasCnt")).containsExactly(1);
        assertThat(field(ok(admin, TERMS + "/grid?keyword=good quantity"), "$.list[*].termCd")).contains("GOOD_QTY");
        assertThat(field(ok(admin, TERMS + "/grid?sort=aliasCnt,desc&size=1"), "$.list[*].termCd")).containsExactly("GOOD_QTY");
    }

    @Test
    void 열_정의는_정렬_표시가_정렬_허용_열과_같고_문구는_로캘을_따른다() throws Exception {
        MvcResult d = ok(admin, DEFECTS + "/columns");
        assertThat(field(d, "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("catCd", "defectCd", "defectNm", "sortNo", "updatedAt");
        assertThat(field(d, "$.list[?(@.field == 'defectCd')].title")).containsExactly("불량 코드");
        assertThat(field(ok(admin, PROCS + "/columns"), "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("procCd", "procNm", "procCat", "routingCnt", "sortNo");
        assertThat(field(ok(admin, TERMS + "/columns"), "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("category", "termKo", "termEn", "aliasCnt");
    }

    // =====================================================================
    // 거부 · A사로 B사 · 권한
    // =====================================================================

    @Test
    void B_정렬_허용_밖_열_잘못된_방향_페이지_0_크기_초과_사용_값_긴_찾기어는_400() throws Exception {
        for (String q : List.of("sort=remark,asc", "sort=defect_cd,asc", "sort=defectCd,up", "page=0", "size=0", "size=100000",
                "sort=defectCd,asc&sort=defectCd,desc", "useYn=X", "keyword=" + "가".repeat(101))) {
            mvc.perform(req(DEFECTS + "/grid?" + q).with(admin)).andExpect(status().isBadRequest());
        }
        mvc.perform(req(PROCS + "/grid?sort=outsrcYn,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req(PROCS + "/grid?useYn=y").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req(TERMS + "/grid?sort=defKo,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req(TERMS + "/grid?keyword=" + "a".repeat(101)).with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void A사로_B사_그리드는_자기_회사_것만_보이고_조회_권한이_없으면_403() throws Exception {
        String b = accounts.newComp("MB");
        RequestPostProcessor bAdmin = user(b, "admin1", true);
        seed(b, 2);
        MvcResult r = ok(bAdmin, DEFECTS + "/grid");
        assertThat(field(r, "$.list[*].defectCd")).containsExactly("D-1", "D-2", "X_1");
        assertThat(total(r)).isEqualTo(3);
        assertThat(total(ok(admin, DEFECTS + "/grid"))).isEqualTo(6);
        assertThat(field(ok(bAdmin, PROCS + "/grid"), "$.list[*].procCd")).containsExactly("P-1", "P-2");
        assertThat(field(ok(bAdmin, PROCS + "/grid?keyword=P-5"), "$.list[*].procCd")).isEmpty();          // A사 공정은 찾기로도 안 보임
        // 동의어 수·동의어 찾기도 자기 회사 것만 — B사 동의어를 더해도 A사 수는 1
        accounts.exec(b, "insert into mst_term_alias (comp_cd, term_cd, alias, created_by, updated_by) values (?, 'GOOD_QTY', 'B사전용', 't', 't')", b);
        assertThat((List<Integer>) JsonPath.read(ok(admin, TERMS + "/grid?keyword=합격").getResponse().getContentAsString(), "$.list[*].aliasCnt"))
                .containsExactly(1);
        assertThat(field(ok(admin, TERMS + "/grid?keyword=B사전용"), "$.list[*].termCd")).isEmpty();
        assertThat(field(ok(bAdmin, TERMS + "/grid?keyword=B사전용"), "$.list[*].termCd")).containsExactly("GOOD_QTY");
        RequestPostProcessor noAuth = user(comp, "viewer", false);
        for (String u : List.of(DEFECTS + "/grid", DEFECTS + "/columns", PROCS + "/grid", PROCS + "/columns", TERMS + "/grid", TERMS + "/columns")) {
            mvc.perform(get(u).with(noAuth)).andExpect(status().isForbidden());
        }
    }
}
