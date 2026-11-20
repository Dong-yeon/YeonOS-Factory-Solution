package io.smarthappy.platform.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.support.DemoCompanies;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 조회 그리드 엑셀 전 화면(docs/73) — 가드 + 모든 grid 엑셀 API 를 실제로 불러 본다.
 *
 * 가드(규칙을 사람 기억이 아니라 시험이 지킨다)
 * - 내보내기 API(POST …/export·…/excel)는 모두 {@code @ProgAuth(action = EXCEL)}
 * - 열 정의 API(GET …/columns)가 있으면 = 조회 그리드 → 같은 화면 권한의 내보내기 API 가 짝으로 있다(예외는 이유와 함께 아래 표)
 * - 내보내기 API 의 화면은 sys_prog.actions 에 EXCEL 이 있다(마이그레이션 누락 방지 — 없으면 권한을 줄 수도 없다)
 * - 화면 JS 의 hb.grid 는 exportUrl 이 있다(예외는 이유와 함께)
 *
 * 전 화면 호출(공통 도우미 GridExcel 로 붙인 API 전부): 데모 데이터 회사 A 와 빈 회사 B 로
 * ① EXCEL 권한 없는 계정 403 ② 파일 행 수 = 같은 계정의 목록 건수(A·B 각각 — B 파일에 A 행이 섞이면 어긋난다) ③ 조건 없는 목록과 같은 결과.
 */
@AutoConfigureMockMvc
class GridExcelAllScreensTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    /** 열 정의는 있지만 grid 메뉴 엑셀을 두지 않는 목록 — 기준 경로 → 이유 (docs/73 2.3) */
    private static final Map<String, String> NO_EXPORT = Map.of(
            "/api/pms/deliverable-projects", "산출물 화면의 프로젝트 고르기(선택 목록) — 같은 목록은 pms-project 에서 내려받는다",
            "/api/pms/gate-projects", "게이트 심의 화면의 프로젝트 고르기(선택 목록) — 같은 목록은 pms-project 에서 내려받는다",
            "/api/production/results", "실적 조회 — 보기(실적·작업자·공정)마다 열이 달라 화면 엑셀 버튼(POST …/excel, EXCEL 권한)으로 내려받는다",
            "/api/admin/depts", "부서 트리 화면(hb.grid 아님)",
            "/api/admin/menus", "메뉴 트리 화면(hb.grid 아님)");

    /** 목록 엑셀이 아니라서 EXCEL 권한이 아닌 내보내기 — 경로 → 이유 */
    private static final Map<String, String> OTHER_AUTH = Map.of(
            "/api/apqp/fmeas/{id}/excel", "FMEA 양식 출력(문서 한 건) — PRINT 권한",
            "/api/apqp/dfmeas/{id}/excel", "DFMEA 양식 출력(문서 한 건) — PRINT 권한",
            "/api/apqp/cps/{id}/excel", "관리계획서 양식 출력(문서 한 건) — PRINT 권한",
            "/api/platform/import/jobs/{jobId}/errors/export", "엑셀 올리기 오류 보고서 — 올린 사람·대상 화면 권한을 ImportService 가 작업 단위로 본다");

    /** hb.grid 에 exportUrl 이 없어도 되는 화면 JS — 파일 → 이유 */
    private static final Map<String, String> JS_NO_EXPORT = Map.of(
            "views/pms/deliverable-mgt.js", "프로젝트 고르기(선택 목록)",
            "views/pms/gate-review.js", "프로젝트 고르기(선택 목록)",
            "views/production/result-view.js", "화면 엑셀 버튼(보기별 열)",
            "views/cms/equip-sec-mgt.js", "화면 엑셀 버튼(같은 조건, POST …/export)",
            "dev/grid-poc.js", "개발용 PoC");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    DemoCompanies demo;

    @Autowired
    io.smarthappy.module.cms.CmsDemoFlow cmsFlow;

    @Autowired
    io.smarthappy.module.production.MesDemoFlow mesFlow;

    @Autowired
    io.smarthappy.module.qms.QmsDemoFlow qmsFlow;

    @Autowired
    io.smarthappy.module.wms.WmsDemoFlow wmsFlow;

    @Autowired
    io.smarthappy.module.pms.PmsDemoFlow pmsFlow;

    @Autowired
    io.smarthappy.module.srm.SrmDemoFlow srmFlow;

    @Autowired
    io.smarthappy.module.aps.ApsDemoFlow apsFlow;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    private record Api(String path, HandlerMethod handler) {
        ProgAuth auth() {
            return handler.getMethod().getAnnotation(ProgAuth.class);
        }
    }

    private List<Api> apis(RequestMethod method, String... suffixes) {
        List<Api> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            if (!e.getKey().getMethodsCondition().getMethods().contains(method)) {
                continue;
            }
            for (String p : e.getKey().getPatternValues()) {
                if (p.startsWith("/api/") && Stream.of(suffixes).anyMatch(p::endsWith)) {
                    out.add(new Api(p, e.getValue()));
                }
            }
        }
        out.sort((a, b) -> a.path().compareTo(b.path()));
        return out;
    }

    private Api find(RequestMethod method, String path) {
        return apis(method, path).stream().filter(a -> a.path().equals(path)).findFirst().orElse(null);
    }

    /** 공통 도우미로 붙인 내보내기 API — 본문을 Map 으로 받는다 */
    private List<Api> gridExcelApis() {
        return apis(RequestMethod.POST, "/export").stream()
                .filter(a -> Stream.of(a.handler().getMethod().getParameterTypes()).anyMatch(Map.class::equals)).toList();
    }

    // ------------------------------------------------------------------ 가드

    @Test
    void 내보내기_API는_모두_EXCEL_권한() {
        List<Api> exports = apis(RequestMethod.POST, "/export", "/excel");
        assertThat(exports.size()).as("내보내기 API 를 못 찾으면 가드가 무의미").isGreaterThan(80);
        List<String> bad = exports.stream().filter(a -> !OTHER_AUTH.containsKey(a.path()))
                .filter(a -> a.auth() == null || a.auth().action() != Action.EXCEL)
                .map(a -> a.path() + " " + a.handler().getShortLogMessage()).toList();
        assertThat(bad).as("@ProgAuth(action = EXCEL) 없는 내보내기 API").isEmpty();
    }

    @Test
    void 열_정의가_있는_목록은_같은_화면_권한의_내보내기_API가_있다() {
        List<String> missing = new ArrayList<>();
        Set<String> exemptUsed = new TreeSet<>();
        for (Api col : apis(RequestMethod.GET, "/columns")) {
            String base = col.path().substring(0, col.path().length() - "/columns".length());
            if (NO_EXPORT.containsKey(base)) {
                exemptUsed.add(base);
                continue;
            }
            Api exp = find(RequestMethod.POST, base + "/export");
            if (exp == null || exp.auth() == null || col.auth() == null || !exp.auth().prog().equals(col.auth().prog())) {
                missing.add(base + (exp == null ? " — 내보내기 API 없음" : " — 화면 권한이 열 정의와 다름"));
            }
        }
        assertThat(missing).as("grid 메뉴 엑셀이 없는 조회 그리드(붙이거나 NO_EXPORT 에 이유와 함께)").isEmpty();
        assertThat(exemptUsed).as("NO_EXPORT 에 없어진 경로가 남아 있다").containsExactlyInAnyOrderElementsOf(NO_EXPORT.keySet());
    }

    @Test
    void 내보내기_화면은_프로그램_정의에_EXCEL_동작이_있다() {
        Set<String> progs = new TreeSet<>();
        apis(RequestMethod.POST, "/export", "/excel").stream().filter(a -> !OTHER_AUTH.containsKey(a.path()))
                .forEach(a -> progs.add(a.auth().prog()));
        List<String> noExcel = progs.stream().filter(p -> Boolean.FALSE.equals(jdbc.queryForObject(
                "select coalesce((select 'EXCEL' = any (actions) from sys_prog where prog_cd = ?), false)", Boolean.class, p))).toList();
        assertThat(noExcel).as("sys_prog.actions 에 EXCEL 이 없는 화면 — 마이그레이션에 더할 것").isEmpty();
    }

    @Test
    void 화면_JS의_hb_grid는_exportUrl이_있다() throws IOException {
        Path root = Path.of("src/main/resources/static/js");
        Pattern call = Pattern.compile("hb\\.grid\\(\\s*[^,)]+,\\s*\\{");
        List<String> missing = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".js")).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = call.matcher(src);
                while (m.find()) {
                    String body = src.substring(m.start(), callEnd(src, m.start()));
                    if (!body.contains("columnsUrl")) {
                        continue;   // 주석 속 사용 예
                    }
                    calls++;
                    if (!body.contains("exportUrl") && !JS_NO_EXPORT.containsKey(rel)) {
                        missing.add(rel);
                    }
                }
            }
        }
        assertThat(calls).as("hb.grid 호출을 못 찾으면 가드가 무의미").isGreaterThan(80);
        assertThat(missing).as("exportUrl 없는 hb.grid(붙이거나 JS_NO_EXPORT 에 이유와 함께)").isEmpty();
    }

    /** 여는 괄호의 짝 — 문자열 안 괄호는 세지 않는다 */
    private static int callEnd(String s, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = s.indexOf('(', from); i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i + 1;
            }
        }
        return s.length();
    }

    // ------------------------------------------------------------------ 전 화면 호출

    @Test
    void 공통_도우미로_붙인_모든_화면_권한_403_회사별_행수는_목록과_같다() throws Exception {
        List<Api> list = gridExcelApis();
        assertThat(list.size()).as("공통 도우미 내보내기 API").isGreaterThanOrEqualTo(56);

        DemoCompanies.Demo a = demo.seeded("GA");          // 기준정보·MES 데모 데이터가 있는 회사
        // 모듈 데모 흐름(개발·데모 시더가 부르는 것과 같은 빈, 같은 순서)으로 품질·창고·구매·설비·프로젝트·계획 목록에도 행을 넣는다
        Map<String, java.util.function.ToIntBiFunction<String, io.smarthappy.platform.security.LoginUser>> flows = new java.util.LinkedHashMap<>();
        flows.put("CMS", cmsFlow::seed);
        flows.put("MES", mesFlow::seed);
        flows.put("QMS", qmsFlow::seed);
        flows.put("WMS", wmsFlow::seed);
        flows.put("PMS", pmsFlow::seed);
        flows.put("SRM", srmFlow::seed);
        flows.put("APS", apsFlow::seed);
        Map<String, String> seeded = new TreeMap<>();
        flows.forEach((name, f) -> {
            try {
                seeded.put(name, String.valueOf(f.applyAsInt(a.comp(), a.admin())));
            } catch (RuntimeException e) {
                seeded.put(name, "실패 " + e);
            }
        });
        System.out.println("모듈 데모 흐름: " + seeded);
        DemoCompanies.Demo b = demo.empty("GB");           // 빈 회사
        long ua = accounts.newUser(a.comp(), "excel1", PW);
        long va = accounts.newUser(a.comp(), "viewer1", PW);
        long ub = accounts.newUser(b.comp(), "excel1", PW);
        for (Api api : list) {
            accounts.grant(a.comp(), ua, "XLS", api.auth().prog(), "SEARCH", "EXCEL");
            accounts.grant(a.comp(), va, "VIEW", api.auth().prog(), "SEARCH", "SAVE");
            accounts.grant(b.comp(), ub, "XLS", api.auth().prog(), "SEARCH", "EXCEL");
        }
        // 쪽 크기를 가장 작게 — 10행이 넘는 목록은 여러 쪽으로 읽힌다
        for (String c : List.of(a.comp(), b.comp())) {
            accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) "
                    + "values (?, 'GRID_PAGE_SIZE_MAX', '10', 't', 't')", c);
        }
        RequestPostProcessor excelA = accounts.as(a.comp(), ua, "excel1");
        // 설비 위험성평가는 데모 흐름에 없다 — 두 건 만든다
        long saver = accounts.newUser(a.comp(), "saver1", PW);
        accounts.grant(a.comp(), saver, "SAVER", "cms-risk", "SEARCH", "SAVE");
        for (String title : List.of("프레스 정기 평가", "용접 수시 평가")) {
            mvc.perform(post("/api/cms/risks").with(csrf()).with(accounts.as(a.comp(), saver, "saver1")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"raType\":\"REGULAR\",\"title\":\"" + title + "\",\"plantCd\":\"P1\",\"assessDate\":\""
                            + LocalDate.now(ZoneId.of("Asia/Seoul")) + "\"}")).andExpect(
                                    org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        }
        RequestPostProcessor viewerA = accounts.as(a.comp(), va, "viewer1");
        RequestPostProcessor excelB = accounts.as(b.comp(), ub, "excel1");

        Map<String, String> result = new TreeMap<>();
        Set<String> needParams = new TreeSet<>();
        // 조건이 필수인 목록에 넣을 기본 조건 — 목록에는 쿼리 파라미터로, 내보내기에는 같은 이름의 본문 값으로(모르는 이름은 양쪽 다 무시)
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Map<String, String> common = Map.of("from", today.minusDays(89).toString(), "to", today.toString(), "plantCd", "P1",
                "month", today.toString().substring(0, 7), "classType", "ITEM",
                "grpCd", jdbc.queryForObject("select min(grp_cd) from sys_code_grp where comp_ext_yn = 'Y'", String.class));
        Map<String, String> condA = new TreeMap<>(common);
        condA.put("custCd", accounts.query(a.comp(), "select min(cust_cd) from mst_cust", String.class));
        Map<String, String> condB = new TreeMap<>(common);
        condB.put("custCd", "NONE");
        List<String> problems = new ArrayList<>();
        int withRows = 0;
        int multiPage = 0;
        for (Api api : list) {
            String base = api.path().substring(0, api.path().length() - "/export".length());
            String listUrl = find(RequestMethod.GET, base + "/grid") != null ? base + "/grid" : base;
            // ① 권한
            int denied = export(viewerA, api.path()).getStatus();
            if (denied != 403) {
                problems.add(api.path() + " EXCEL 권한 없는 계정 " + denied);
            }
            // ②③ 회사별 행 수 = 목록 건수 — 조건 없이, 그리고 기본 조건(기간·공장 등)을 넣어서
            long[] rows = new long[2];
            int k = 0;
            for (RequestPostProcessor who : List.of(excelA, excelB)) {
                long n = compare(api.path(), listUrl, who, Map.of(), problems);
                if (n < 0) {
                    needParams.add(api.path());
                    n = compare(api.path(), listUrl, who, common, problems);
                    if (n < 0) {
                        n = compare(api.path(), listUrl, who, k == 0 ? condA : condB, problems);   // 거래처가 필수인 목록
                    }
                    if (n < 0 && k == 0) {
                        problems.add(api.path() + " 기본 조건으로도 목록이 안 열린다 — 시험의 기본 조건에 더할 것");
                    }
                }
                rows[k++] = n;
            }
            withRows += rows[0] > 0 ? 1 : 0;
            multiPage += rows[0] > 10 ? 1 : 0;
            result.put(api.path(), rows[0] + "/" + rows[1]);
        }
        System.out.println("조건 없이는 안 되는 목록: " + needParams);
        System.out.println("grid 엑셀 전 화면: " + list.size() + "개, 행 있는 화면 " + withRows + ", 여러 쪽 " + multiPage + " — A/B 행 수 " + result);
        assertThat(problems).isEmpty();
        assertThat(withRows).as("데모 데이터가 있는 화면이 몇 개는 있어야 의미가 있다").isGreaterThanOrEqualTo(5);
        // 모듈마다 행이 있는 대표 화면이 하나는 있다(0행 = 0행 비교만으로 통과하지 않게)
        for (String module : List.of("master", "sales", "qms", "wms", "srm", "aps", "cms", "pms", "admin")) {
            assertThat(result.entrySet().stream().filter(e -> e.getKey().startsWith("/api/" + module + "/"))
                    .anyMatch(e -> !e.getValue().startsWith("0/") && !e.getValue().startsWith("-1/")))
                    .as(module + " 모듈에 행이 있는 내보내기 화면").isTrue();
        }
    }

    private MockHttpServletResponse export(RequestPostProcessor who, String path) throws Exception {
        return export(who, path, Map.of());
    }

    private MockHttpServletResponse export(RequestPostProcessor who, String path, Map<String, String> cond) throws Exception {
        return mvc.perform(post(path).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON)
                .content(new tools.jackson.databind.ObjectMapper().writeValueAsString(cond))).andReturn().getResponse();
    }

    /**
     * 같은 조건으로 목록과 파일을 불러 견준다 — 목록이 200 이면 파일 행 수 = 목록 건수, 아니면 파일도 같은 상태(목록과 같은 검사).
     * @return 파일 행 수, 목록이 안 열리면 -1
     */
    private long compare(String exportPath, String listUrl, RequestPostProcessor who, Map<String, String> cond, List<String> problems)
            throws Exception {
        var req = get(listUrl).with(who);
        for (Map.Entry<String, String> e : cond.entrySet()) {
            req = req.param(e.getKey(), e.getValue());
        }
        MockHttpServletResponse l = mvc.perform(req).andReturn().getResponse();
        MockHttpServletResponse x = export(who, exportPath, cond);
        if (l.getStatus() != 200) {
            if (x.getStatus() != l.getStatus()) {
                problems.add(exportPath + " 목록 " + l.getStatus() + " 인데 파일 " + x.getStatus() + " " + cond.keySet());
            }
            return -1;
        }
        if (x.getStatus() != 200) {
            problems.add(exportPath + " 파일 " + x.getStatus() + " " + x.getContentAsString() + " " + cond.keySet());
            return -1;
        }
        long expected = ((Number) com.jayway.jsonpath.JsonPath.read(l.getContentAsString(), "$.total")).longValue();
        long actual = dataRows(x.getContentAsByteArray());
        if (expected != actual) {
            problems.add(exportPath + " 목록 " + expected + "건인데 파일 " + actual + "행 " + cond.keySet());
        }
        return actual;
    }

    private static long dataRows(byte[] xlsx) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            return wb.getSheetAt(0).getLastRowNum();
        }
    }
}
