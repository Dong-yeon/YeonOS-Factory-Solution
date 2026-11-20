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
 * 외부 시스템·클래스(속성 정의 화면의 클래스 표)·내시 버전 주 목록 그리드 API (UI 단계 3 ① 묶음 4, 결정 docs/29 #45·#48).
 * 기존 목록 API 는 그대로, 새 API 는 같은 조건 + 페이지 경계·정렬 허용 열·건수, 조회 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class ExtClassForecastGridTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String SYS = "/api/master/ext-systems";
    private static final String CLS = "/api/master/class-props/classes";
    private static final String REVS = "/api/sales/forecasts/revs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("XG");
        admin = user(comp, "admin1", true);
        seed(comp, 3);
    }

    private RequestPostProcessor user(String c, String id, boolean search) {
        long u = accounts.newUser(c, id, PW);
        if (search) {
            accounts.grant(c, u, "MST", "mst-ext-system", "SEARCH");
            accounts.grant(c, u, "MST", "mst-class-prop", "SEARCH");
            accounts.grant(c, u, "SALES", "sal-forecast", "SEARCH");
        }
        return accounts.as(c, u, id);
    }

    /**
     * 외부 시스템 S1..Sn(ERP) + Z9(OTHER, 미사용). 품목 클래스 IC1(순서 2)·IC2(1)·IC3(1, 미사용), 설비 클래스 EC1.
     * 거래처 C-F 의 내시 버전 1..n(받은 시각 = 버전 순).
     */
    private void seed(String c, int n) {
        for (int i = 1; i <= n; i++) {
            accounts.exec(c, """
                    insert into mst_ext_system (comp_cd, system_cd, system_nm, system_type, created_by, updated_by)
                    values (?, ?, ?, 'ERP', 't', 't')""", c, "S" + i, "시스템 " + i);
        }
        accounts.exec(c, """
                insert into mst_ext_system (comp_cd, system_cd, system_nm, system_type, use_yn, created_by, updated_by)
                values (?, 'Z9', '기타', 'OTHER', 'N', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_item_class (comp_cd, class_cd, class_nm, sort_no, use_yn, created_by, updated_by)
                values (?, 'IC1', '코일', 2, 'Y', 't', 't'), (?, 'IC2', '판재', 1, 'Y', 't', 't'), (?, 'IC3', '폐기 클래스', 1, 'N', 't', 't')""",
                c, c, c);
        accounts.exec(c, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'EC1', '프레스', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-F', '내시 고객', 'SALES', 't', 't')""", c);
        for (int i = 1; i <= n; i++) {
            accounts.exec(c, """
                    insert into sal_forecast_rev (comp_cd, cust_id, rev_no, received_at, source, created_by)
                    select ?, cust_id, ?, now() - make_interval(days => ?), 'MANUAL', 't' from mst_cust where cust_cd = 'C-F'""",
                    c, i, 10 - i);
        }
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

    private static <T> List<T> field(MvcResult r, String path) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), path);
    }

    private static int total(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.total")).intValue();
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 외부_시스템_그리드는_유형_코드_순_페이지와_건수_기존_목록과_같은_순서() throws Exception {
        MvcResult p1 = ok(admin, SYS + "/grid?page=1&size=3");
        assertThat(field(p1, "$.list[*].systemCd")).containsExactly("S1", "S2", "S3");
        assertThat(total(p1)).isEqualTo(4);
        assertThat(field(ok(admin, SYS + "/grid?page=2&size=3"), "$.list[*].systemCd")).containsExactly("Z9");
        assertThat(field(ok(admin, SYS + "/grid?page=3&size=3"), "$.list[*].systemCd")).isEmpty();
        assertThat(field(ok(admin, SYS + "/grid?size=100"), "$.list[*].systemCd"))
                .containsExactlyElementsOf(field(ok(admin, SYS), "$.data[*].systemCd"));
        assertThat(field(ok(admin, SYS + "/grid?useYn=Y"), "$.list[*].systemCd")).containsExactly("S1", "S2", "S3");
        assertThat(field(ok(admin, SYS + "/grid?sort=systemNm,desc&size=1"), "$.list[*].systemCd")).containsExactly("S3");
        mvc.perform(req(SYS + "/grid?total=false").with(admin)).andExpect(jsonPath("$.total").doesNotExist());
    }

    @Test
    void 클래스_그리드는_유형별_순서_코드_순이고_기존_목록과_같다() throws Exception {
        MvcResult r = ok(admin, CLS + "/grid?classType=ITEM");
        assertThat(field(r, "$.list[*].classCd")).containsExactly("IC2", "IC3", "IC1");
        assertThat(field(r, "$.list[*].classCd"))
                .containsExactlyElementsOf(field(ok(admin, CLS + "?classType=ITEM"), "$.data[*].classCd"));
        assertThat(total(r)).isEqualTo(3);
        assertThat(field(ok(admin, CLS + "/grid?classType=ITEM&page=2&size=2"), "$.list[*].classCd")).containsExactly("IC1");
        assertThat(field(ok(admin, CLS + "/grid?classType=ITEM&sort=classNm,asc"), "$.list[*].classCd"))
                .containsExactly("IC1", "IC2", "IC3");                                                // 코일 < 판재 < 폐기
        assertThat(field(ok(admin, CLS + "/grid?classType=EQUIP"), "$.list[*].classCd")).containsExactly("EC1");
    }

    @Test
    void 내시_버전_그리드는_최신_버전부터_페이지와_받은_시각_정렬() throws Exception {
        MvcResult r = ok(admin, REVS + "/grid?custCd=c-f&size=2");                                 // 코드는 대문자로
        assertThat(field(r, "$.list[*].revNo")).containsExactly(3, 2);
        assertThat(total(r)).isEqualTo(3);
        assertThat(field(ok(admin, REVS + "/grid?custCd=C-F&size=2&page=2"), "$.list[*].revNo")).containsExactly(1);
        assertThat(field(ok(admin, REVS + "/grid?custCd=C-F"), "$.list[*].revNo"))
                .containsExactlyElementsOf(field(ok(admin, REVS + "?custCd=C-F"), "$.data[*].revNo"));
        assertThat(field(ok(admin, REVS + "/grid?custCd=C-F&sort=receivedAt,asc"), "$.list[*].revNo")).containsExactly(1, 2, 3);
    }

    @Test
    void 열_정의는_정렬_표시가_정렬_허용_열과_같고_문구는_로캘을_따른다() throws Exception {
        MvcResult s = ok(admin, SYS + "/columns");
        assertThat(field(s, "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("systemCd", "systemNm", "systemType", "mapCnt", "codeMapCnt");
        assertThat(field(s, "$.list[?(@.field == 'systemCd')].title")).containsExactly("시스템 코드");
        assertThat(field(ok(admin, CLS + "/columns"), "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("classCd", "classNm", "propCnt", "targetCnt");
        assertThat(field(ok(admin, REVS + "/columns"), "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("revNo", "receivedAt");
    }

    // =====================================================================
    // 거부 · A사로 B사 · 권한
    // =====================================================================

    @Test
    void B_정렬_허용_밖_페이지_크기_사용_값_클래스_유형_거래처_없음은_400_없는_거래처는_404() throws Exception {
        for (String q : List.of("sort=remark,asc", "sort=system_cd,asc", "sort=systemCd,up", "page=0", "size=0", "size=100000",
                "sort=systemCd,asc&sort=systemCd,desc", "useYn=X")) {
            mvc.perform(req(SYS + "/grid?" + q).with(admin)).andExpect(status().isBadRequest());
        }
        mvc.perform(req(CLS + "/grid").with(admin)).andExpect(status().isBadRequest());                    // 유형 필수
        mvc.perform(req(CLS + "/grid?classType=SUPPLIER").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req(CLS + "/grid?classType=ITEM&sort=remark,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req(REVS + "/grid").with(admin)).andExpect(status().isBadRequest());                   // 거래처 필수
        mvc.perform(req(REVS + "/grid?custCd=C-F&sort=source,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req(REVS + "/grid?custCd=NOPE").with(admin)).andExpect(status().isNotFound());
    }

    @Test
    void A사로_B사_그리드는_자기_회사_것만_보이고_조회_권한이_없으면_403() throws Exception {
        String b = accounts.newComp("XB");
        RequestPostProcessor bAdmin = user(b, "admin1", true);
        seed(b, 1);
        assertThat(field(ok(bAdmin, SYS + "/grid"), "$.list[*].systemCd")).containsExactly("S1", "Z9");
        assertThat(total(ok(admin, SYS + "/grid"))).isEqualTo(4);
        assertThat(total(ok(bAdmin, CLS + "/grid?classType=ITEM"))).isEqualTo(3);
        assertThat(field(ok(bAdmin, REVS + "/grid?custCd=C-F"), "$.list[*].revNo")).containsExactly(1);
        // B사에만 있는 거래처 — A사에서는 없는 거래처(404), B사 버전이 보이지 않는다
        accounts.exec(b, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C-BONLY', 'B사', 'SALES', 't', 't')", b);
        mvc.perform(req(REVS + "/grid?custCd=C-BONLY").with(admin)).andExpect(status().isNotFound());
        RequestPostProcessor noAuth = user(comp, "viewer", false);
        for (String u : List.of(SYS + "/grid", SYS + "/columns", CLS + "/grid?classType=ITEM", CLS + "/columns",
                REVS + "/grid?custCd=C-F", REVS + "/columns")) {
            mvc.perform(req(u).with(noAuth)).andExpect(status().isForbidden());
        }
    }
}
