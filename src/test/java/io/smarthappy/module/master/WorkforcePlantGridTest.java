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
 * 작업자·근무조·공장 주 목록 그리드 API (UI 단계 3 ① 묶음 2, 결정 docs/29 #45·#48) — `/grid` + `/columns`.
 * 기존 목록 API 는 그대로, 새 API 는 페이지 경계·정렬 허용 열·조건·건수, 조회 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class WorkforcePlantGridTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String WORKERS = "/api/master/workers";
    private static final String SHIFTS = "/api/master/shifts";
    private static final String PLANTS = "/api/master/plants";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("WG");
        admin = user(comp, "admin1", true);
        seed(comp, 5);
    }

    private RequestPostProcessor user(String c, String id, boolean search) {
        long u = accounts.newUser(c, id, PW);
        if (search) {
            accounts.grant(c, u, "MST", "mst-worker", "SEARCH");
            accounts.grant(c, u, "MST", "mst-shift", "SEARCH");
            accounts.grant(c, u, "MST", "mst-plant", "SEARCH");
        }
        return accounts.as(c, u, id);
    }

    /**
     * 공장 P1(1공장)·P2(2공장, 미사용, 순서 0 → 앞). 작업자 W-1..W-n(P1, 부서 "조립", 마지막 미사용) + "X_1"(P2, 부서 "도장 100%").
     * 근무조: P1 에 A(06~10, 순서 2)·B(10~14, 순서 1)·C(14~18, 순서 1, 미사용) — 겹치지 않게.
     */
    private void seed(String c, int n) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, "update sys_plant set use_yn = 'N', sort_no = 0 where plant_cd = 'P2'");
        accounts.exec(c, "update sys_plant set sort_no = 1 where plant_cd = 'P1'");
        for (int i = 1; i <= n; i++) {
            accounts.exec(c, """
                    insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, dept_nm, use_yn, created_by, updated_by)
                    values (?, ?, ?, 'P1', '조립', ?, 't', 't')""", c, "W-" + i, "작업자 " + i, i == n ? "N" : "Y");
        }
        accounts.exec(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, dept_nm, created_by, updated_by)
                values (?, 'X_1', '도장공', 'P2', '도장 100%', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, sort_no, use_yn, created_by, updated_by)
                values (?, 'P1', 'A', '주간 A', '06:00', '10:00', 2, 'Y', 't', 't'),
                       (?, 'P1', 'B', '주간 B', '10:00', '14:00', 1, 'Y', 't', 't'),
                       (?, 'P1', 'C', '오후', '14:00', '18:00', 1, 'N', 't', 't')""", c, c, c);
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
    void 작업자_그리드는_사번_순_페이지_경계에서_빠지거나_겹치지_않고_기존_목록과_같은_순서() throws Exception {
        MvcResult p1 = ok(admin, WORKERS + "/grid?page=1&size=2");
        assertThat(field(p1, "$.list[*].workerCd")).containsExactly("W-1", "W-2");
        assertThat(total(p1)).isEqualTo(6);
        assertThat(field(ok(admin, WORKERS + "/grid?page=2&size=2"), "$.list[*].workerCd")).containsExactly("W-3", "W-4");
        assertThat(field(ok(admin, WORKERS + "/grid?page=3&size=2"), "$.list[*].workerCd")).containsExactly("W-5", "X_1");
        assertThat(field(ok(admin, WORKERS + "/grid?page=4&size=2"), "$.list[*].workerCd")).isEmpty();
        assertThat(field(ok(admin, WORKERS + "/grid?size=100"), "$.list[*].workerCd"))
                .containsExactlyElementsOf(field(ok(admin, WORKERS), "$.data[*].workerCd"));
        // 조건: 공장·사용·찾기(사번·이름·부서, LIKE 특수 문자는 글자)
        assertThat(total(ok(admin, WORKERS + "/grid?plantCd=p2"))).isEqualTo(1);                       // 공장 코드는 대문자로
        assertThat(total(ok(admin, WORKERS + "/grid?useYn=Y"))).isEqualTo(5);
        assertThat(field(ok(admin, WORKERS + "/grid?keyword=작업자 3"), "$.list[*].workerCd")).containsExactly("W-3");
        assertThat(field(ok(admin, WORKERS + "/grid?keyword=_"), "$.list[*].workerCd")).containsExactly("X_1");
        assertThat(field(ok(admin, WORKERS + "/grid?keyword=%"), "$.list[*].workerCd")).containsExactly("X_1");
        // 정렬 허용 열·여러 키
        assertThat(field(ok(admin, WORKERS + "/grid?sort=workerCd,desc&size=1"), "$.list[*].workerCd")).containsExactly("X_1");
        assertThat(field(ok(admin, WORKERS + "/grid?sort=plantNm,desc&sort=workerCd,asc&size=1"), "$.list[*].workerCd"))
                .containsExactly("X_1");
        mvc.perform(req(WORKERS + "/grid?total=false").with(admin)).andExpect(jsonPath("$.total").doesNotExist());
    }

    @Test
    void 근무조_그리드는_공장별_기존_목록_순서_순서_시작_코드와_페이지() throws Exception {
        MvcResult r = ok(admin, SHIFTS + "/grid?plantCd=P1");
        assertThat(field(r, "$.list[*].shiftCd")).containsExactly("B", "C", "A");                       // 순서 1(10시·14시) → 2
        assertThat(field(r, "$.list[*].shiftCd"))
                .containsExactlyElementsOf(field(ok(admin, SHIFTS + "?plantCd=P1"), "$.data[*].shiftCd"));
        assertThat(total(r)).isEqualTo(3);
        assertThat(field(ok(admin, SHIFTS + "/grid?plantCd=P1&page=2&size=2"), "$.list[*].shiftCd")).containsExactly("A");
        assertThat(field(ok(admin, SHIFTS + "/grid?plantCd=P1&sort=startTime,asc"), "$.list[*].shiftCd")).containsExactly("A", "B", "C");
        assertThat(field(ok(admin, SHIFTS + "/grid?plantCd=P1&sort=shiftNm,desc&size=1"), "$.list[*].shiftCd")).containsExactly("B");
        assertThat(field(ok(admin, SHIFTS + "/grid?plantCd=P2"), "$.list[*].shiftCd")).isEmpty();
    }

    @Test
    void 공장_그리드는_순서_코드_순_사용_조건과_정렬_허용_열() throws Exception {
        MvcResult r = ok(admin, PLANTS + "/grid");
        assertThat(field(r, "$.list[*].plantCd")).containsExactly("P2", "P1");
        assertThat(field(r, "$.list[*].plantCd")).containsExactlyElementsOf(field(ok(admin, PLANTS), "$.data[*].plantCd"));
        assertThat(field(ok(admin, PLANTS + "/grid?useYn=Y"), "$.list[*].plantCd")).containsExactly("P1");
        assertThat(field(ok(admin, PLANTS + "/grid?sort=plantCd,asc&size=1"), "$.list[*].plantCd")).containsExactly("P1");
        assertThat(total(ok(admin, PLANTS + "/grid?size=1"))).isEqualTo(2);
    }

    @Test
    void 열_정의는_정렬_표시가_정렬_허용_열과_같고_문구는_로캘을_따른다() throws Exception {
        MvcResult w = ok(admin, WORKERS + "/columns");
        assertThat(field(w, "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("workerCd", "workerNm", "plantNm", "deptNm", "qualCnt");
        assertThat(field(w, "$.list[?(@.field == 'workerCd')].title")).containsExactly("사번");
        assertThat(field(ok(admin, SHIFTS + "/columns"), "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("shiftCd", "shiftNm", "startTime", "sortNo");
        assertThat(field(ok(admin, PLANTS + "/columns"), "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("plantCd", "plantNm", "nodeCnt", "sortNo");
    }

    // =====================================================================
    // 거부 · A사로 B사 · 권한
    // =====================================================================

    @Test
    void B_정렬_허용_밖_잘못된_방향_페이지_0_크기_초과_사용_값_긴_찾기어_공장_없음은_400() throws Exception {
        for (String q : List.of("sort=remark,asc", "sort=worker_cd,asc", "sort=workerCd,up", "page=0", "size=0", "size=100000",
                "sort=workerCd,asc&sort=workerCd,desc", "useYn=X", "keyword=" + "가".repeat(101))) {
            mvc.perform(req(WORKERS + "/grid?" + q).with(admin)).andExpect(status().isBadRequest());
        }
        mvc.perform(req(SHIFTS + "/grid").with(admin)).andExpect(status().isBadRequest());                 // 공장 필수
        mvc.perform(req(SHIFTS + "/grid?plantCd=P1&sort=breakMin,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req(PLANTS + "/grid?sort=timeZone,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req(PLANTS + "/grid?useYn=y").with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void A사로_B사_그리드는_자기_회사_것만_보이고_조회_권한이_없으면_403() throws Exception {
        String b = accounts.newComp("WB");
        RequestPostProcessor bAdmin = user(b, "admin1", true);
        seed(b, 2);
        MvcResult r = ok(bAdmin, WORKERS + "/grid");
        assertThat(field(r, "$.list[*].workerCd")).containsExactly("W-1", "W-2", "X_1");
        assertThat(total(r)).isEqualTo(3);
        assertThat(total(ok(admin, WORKERS + "/grid"))).isEqualTo(6);
        assertThat(field(ok(bAdmin, WORKERS + "/grid?keyword=W-5"), "$.list[*].workerCd")).isEmpty();      // A사 사번으로 찾아도 없음
        assertThat(total(ok(bAdmin, SHIFTS + "/grid?plantCd=P1"))).isEqualTo(3);
        assertThat(total(ok(bAdmin, PLANTS + "/grid"))).isEqualTo(2);
        // B사에만 있는 공장 — A사에서는 없는 공장(404), B사 근무조가 보이지 않는다
        accounts.addPlant(b, "PB", "B사 공장");
        mvc.perform(req(SHIFTS + "/grid?plantCd=PB").with(admin)).andExpect(status().isNotFound());
        RequestPostProcessor noAuth = user(comp, "viewer", false);
        for (String u : List.of(WORKERS + "/grid", WORKERS + "/columns", SHIFTS + "/grid?plantCd=P1", SHIFTS + "/columns",
                PLANTS + "/grid", PLANTS + "/columns")) {
            mvc.perform(req(u).with(noAuth)).andExpect(status().isForbidden());
        }
    }
}
