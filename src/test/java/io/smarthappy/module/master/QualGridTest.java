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
 * 자격 주 목록 그리드 API (UI 단계 3 ① 묶음 5, 결정 docs/29 #45·#48) — `/api/master/quals/grid` + `/columns`.
 * 기존 목록 API 는 그대로(작업자 화면 자격 고르기도 씀), 새 API 는 같은 조건 + 페이지 경계·정렬 허용 열·건수, 조회 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class QualGridTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String QUALS = "/api/master/quals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QG");
        admin = user(comp, "admin1", true);
        seed(comp, 4);
    }

    private RequestPostProcessor user(String c, String id, boolean search) {
        long u = accounts.newUser(c, id, PW);
        if (search) {
            accounts.grant(c, u, "MST", "mst-qual", "SEARCH");
        }
        return accounts.as(c, u, id);
    }

    /** 자격 Q-1..Q-n(순서 = n-번호 → 기본 정렬은 역순, 유효 개월 = 번호×6, 마지막 미사용) + Q-X(만료 없음, 순서 99) */
    private void seed(String c, int n) {
        for (int i = 1; i <= n; i++) {
            accounts.exec(c, """
                    insert into mst_qual (comp_cd, qual_cd, qual_nm, valid_months, sort_no, use_yn, created_by, updated_by)
                    values (?, ?, ?, ?, ?, ?, 't', 't')""", c, "Q-" + i, "자격 " + i, i * 6, n - i, i == n ? "N" : "Y");
        }
        accounts.exec(c, """
                insert into mst_qual (comp_cd, qual_cd, qual_nm, sort_no, created_by, updated_by)
                values (?, 'Q-X', '만료 없는 자격', 99, 't', 't')""", c);
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

    @Test
    void 그리드는_순서_코드_순_페이지_경계에서_빠지거나_겹치지_않고_기존_목록과_같은_순서() throws Exception {
        MvcResult p1 = ok(admin, QUALS + "/grid?page=1&size=2");
        assertThat(field(p1, "$.list[*].qualCd")).containsExactly("Q-4", "Q-3");
        assertThat(total(p1)).isEqualTo(5);
        assertThat(field(ok(admin, QUALS + "/grid?page=2&size=2"), "$.list[*].qualCd")).containsExactly("Q-2", "Q-1");
        assertThat(field(ok(admin, QUALS + "/grid?page=3&size=2"), "$.list[*].qualCd")).containsExactly("Q-X");
        assertThat(field(ok(admin, QUALS + "/grid?page=4&size=2"), "$.list[*].qualCd")).isEmpty();
        assertThat(field(ok(admin, QUALS + "/grid?size=100"), "$.list[*].qualCd"))
                .containsExactlyElementsOf(field(ok(admin, QUALS), "$.data[*].qualCd"));
        assertThat(field(ok(admin, QUALS + "/grid?useYn=Y&size=100"), "$.list[*].qualCd"))
                .containsExactlyElementsOf(field(ok(admin, QUALS + "?useYn=Y"), "$.data[*].qualCd"));
        mvc.perform(req(QUALS + "/grid?total=false").with(admin)).andExpect(jsonPath("$.total").doesNotExist());
    }

    @Test
    void 정렬_허용_열_유효_개월_없음은_어느_방향이든_끝() throws Exception {
        assertThat(field(ok(admin, QUALS + "/grid?sort=qualCd,asc&size=2"), "$.list[*].qualCd")).containsExactly("Q-1", "Q-2");
        assertThat(field(ok(admin, QUALS + "/grid?sort=validText,asc"), "$.list[*].qualCd")).containsExactly("Q-1", "Q-2", "Q-3", "Q-4", "Q-X");
        assertThat(field(ok(admin, QUALS + "/grid?sort=validText,desc"), "$.list[*].qualCd")).containsExactly("Q-4", "Q-3", "Q-2", "Q-1", "Q-X");
        assertThat(field(ok(admin, QUALS + "/grid?sort=qualNm,desc&size=1"), "$.list[*].qualCd")).containsExactly("Q-4");
        MvcResult cols = ok(admin, QUALS + "/columns");
        assertThat(field(cols, "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("qualCd", "qualNm", "validText", "holderCnt", "procCnt");
        assertThat(field(cols, "$.list[?(@.field == 'qualCd')].title")).containsExactly("자격 코드");
        // 만료 없음 = 빈 글자(그리드 칸), 숫자 칸 validMonths 는 그대로 null
        MvcResult x = ok(admin, QUALS + "/grid?sort=qualCd,desc&size=1");
        assertThat(field(x, "$.list[*].validText")).containsExactly("");
        assertThat((List<Object>) JsonPath.read(x.getResponse().getContentAsString(), "$.list[*].validMonths")).containsExactly((Object) null);
    }

    @Test
    void B_정렬_허용_밖_잘못된_방향_페이지_크기_사용_값은_400() throws Exception {
        for (String q : List.of("sort=remark,asc", "sort=qual_cd,asc", "sort=qualCd,up", "page=0", "size=0", "size=100000",
                "sort=qualCd,asc&sort=qualCd,desc", "useYn=X")) {
            mvc.perform(req(QUALS + "/grid?" + q).with(admin)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void A사로_B사_그리드는_자기_회사_것만_보이고_조회_권한이_없으면_403() throws Exception {
        String b = accounts.newComp("QB");
        RequestPostProcessor bAdmin = user(b, "admin1", true);
        seed(b, 1);
        MvcResult r = ok(bAdmin, QUALS + "/grid");
        assertThat(field(r, "$.list[*].qualCd")).containsExactly("Q-1", "Q-X");
        assertThat(total(r)).isEqualTo(2);
        assertThat(total(ok(admin, QUALS + "/grid"))).isEqualTo(5);
        RequestPostProcessor noAuth = user(comp, "viewer", false);
        mvc.perform(get(QUALS + "/grid").with(noAuth)).andExpect(status().isForbidden());
        mvc.perform(get(QUALS + "/columns").with(noAuth)).andExpect(status().isForbidden());
    }
}
