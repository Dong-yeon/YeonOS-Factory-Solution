package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA 2026-10-22 ⑤ — UI 단계 3 의 새 목록 API(`/grid`, docs/29 #45·#47)와 같은 정렬 파서를 쓰는 WMS 목록 이상 사용.
 * 모두 `PageQuery.parse` 를 거치므로 끝점 표 하나로 같은 규칙을 돌린다: 허용 밖 정렬·정렬 4개·같은 키 두 번·방향 형식,
 * page/size 경계·size 상한(정책 GRID_PAGE_SIZE_MAX 기본 500)·숫자 아님, 권한 없는 사용자, 정렬 키 하나(쉼표) 200, A사로 B사.
 * 분석 docs/qa/2026-10-22_ui-stage3.md.
 */
@AutoConfigureMockMvc
class GridApiQaTest extends PostgresTestBase {

    static final String PW = "Harbor-violet-52";

    /** 경로 · 화면 코드 · 허용 정렬 키 둘 · 기간 필수(from/to — 없으면 400 INVALID_RANGE, 설계대로) */
    record Ep(String path, String prog, String sortA, String sortB, boolean range) {
        Ep(String path, String prog, String sortA, String sortB) {
            this(path, prog, sortA, sortB, false);
        }

        @Override
        public String toString() {
            return path;
        }
    }

    static Stream<Ep> endpoints() {
        return Stream.of(
                new Ep("/api/cms/mwos/grid", "cms-mwo", "mwoNo", "status", true),
                new Ep("/api/cms/risks/grid", "cms-risk", "raNo", "status", true),
                new Ep("/api/cms/spares/grid", "cms-spare", "itemCd", "targetCd"),
                new Ep("/api/wms/counts/grid", "wms-count", "countNo", "status"),
                new Ep("/api/wms/labels/grid", "wms-label", "tplCd", "labelType"),
                new Ep("/api/wms/printers/grid", "wms-label", "printerCd", "plantCd"),
                new Ep("/api/wms/ships/grid", "wms-ship", "shipNo", "status"),
                new Ep("/api/wms/print-jobs", "wms-print-job", "requestedAt", "jobId", true),
                new Ep("/api/wms/stock", "wms-stock", "itemCd", "qty"),
                new Ep("/api/wms/trx", "wms-trx", "occurredAt", "trxId", true));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;
    RequestPostProcessor nobody;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QG");
        accounts.addPlant(comp, "P1", "1공장");
        userId = accounts.newUser(comp, "admin1", PW);
        endpoints().map(Ep::prog).distinct().forEach(p -> accounts.grant(comp, userId, "QA", p, "SEARCH", "SAVE"));
        admin = accounts.as(comp, userId, "admin1");
        long none = accounts.newUser(comp, "none1", PW);
        nobody = accounts.as(comp, none, "none1");
    }

    private MockHttpServletRequestBuilder grid(Ep e, String... params) {
        MockHttpServletRequestBuilder b = get(e.path()).param("plantCd", "P1");
        if (e.range()) {
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));   // 회사 시간대(기본)
            b = b.param("from", today.minusDays(7).toString()).param("to", today.toString());
        }
        for (int i = 0; i + 1 < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return b;
    }

    private int code(Ep e, String... params) throws Exception {
        return mvc.perform(grid(e, params).with(admin)).andReturn().getResponse().getStatus();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 정렬_키_하나_쉼표_방향_대문자_방향_없음_세_키는_200(Ep e) throws Exception {
        mvc.perform(grid(e, "sort", e.sortA() + ",desc").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("OK")).andExpect(jsonPath("$.total").isNumber());
        assertThat(code(e, "sort", e.sortA() + ",DESC")).isEqualTo(200);
        assertThat(code(e, "sort", e.sortA())).isEqualTo(200);
        assertThat(code(e, "sort", " " + e.sortA() + " , asc ")).isEqualTo(200);
        assertThat(code(e, "sort", e.sortA() + ",asc", "sort", e.sortB() + ",desc")).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 허용_밖_정렬_4개_같은_키_두번_방향_형식은_400_INVALID_VALUE(Ep e) throws Exception {
        List<String[]> bad = new ArrayList<>();
        bad.add(new String[] {"sort", "pwdHash,asc"});
        bad.add(new String[] {"sort", "comp_cd"});
        bad.add(new String[] {"sort", e.sortA() + ";drop table x"});
        bad.add(new String[] {"sort", e.sortA() + ",sideways"});
        bad.add(new String[] {"sort", e.sortA() + ",asc,desc"});
        bad.add(new String[] {"sort", e.sortA() + ",asc", "sort", e.sortA() + ",desc"});
        bad.add(new String[] {"sort", e.sortA(), "sort", e.sortB(), "sort", "x1", "sort", "x2"});
        bad.add(new String[] {"sort", "a".repeat(81)});
        for (String[] p : bad) {
            mvc.perform(grid(e, p).with(admin)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void page_size_경계(Ep e) throws Exception {
        for (String[] p : new String[][] {{"page", "0"}, {"page", "-1"}, {"page", "100001"}, {"size", "0"}, {"size", "-5"},
                {"size", "501"}, {"size", "2147483647"}}) {
            assertThat(code(e, p)).as(String.join("=", p)).isEqualTo(400);
        }
        // 끝 페이지 너머·상한 size 는 정상(빈 목록)
        mvc.perform(grid(e, "page", "100000", "size", "500").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(0));
        assertThat(code(e, "size", "1")).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 숫자_아님_불린_아님은_400_이고_500_이_아니다(Ep e) throws Exception {
        for (String[] p : new String[][] {{"page", "abc"}, {"size", "1e3"}, {"page", "99999999999"}, {"total", "maybe"}}) {
            assertThat(code(e, p)).as(String.join("=", p)).isEqualTo(400);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 권한_없는_사용자는_403_비로그인은_401(Ep e) throws Exception {
        mvc.perform(grid(e).with(nobody)).andExpect(status().isForbidden());
        mvc.perform(grid(e)).andExpect(status().isUnauthorized());
    }

    @Test
    void A사로_B사_프린터_목록은_보이지_않는다() throws Exception {
        mvc.perform(post("/api/wms/printers").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"printerCd\":\"PRN-A\",\"printerNm\":\"A사\",\"host\":\"10.0.0.1\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/wms/printers/grid").with(admin)).andExpect(jsonPath("$.total").value(1));
        String b = accounts.newComp("QGB");
        accounts.addPlant(b, "P1", "B공장");
        long bUser = accounts.newUser(b, "admin1", PW);
        accounts.grant(b, bUser, "QA", "wms-label", "SEARCH");
        RequestPostProcessor bAdmin = accounts.as(b, bUser, "admin1");
        mvc.perform(get("/api/wms/printers/grid").param("plantCd", "P1").with(bAdmin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        // 회사를 요청 값으로 바꿔 볼 길(compCd 인자)은 무시된다
        mvc.perform(get("/api/wms/printers/grid").param("compCd", comp).with(bAdmin)).andExpect(jsonPath("$.total").value(0));
    }
}
