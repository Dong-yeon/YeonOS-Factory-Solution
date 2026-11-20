package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 실적 조회 그리드 (prd-result, docs/40 §10.2 D1 · docs/29 #45) — /grid·/columns·/defects 추가, 기존 목록·엑셀 API 는 그대로.
 * 보기마다 목록과 같은 행·같은 기본 순서, 쪽 경계·전체 건수, 보기별 정렬 허용 목록, 조건, 보기별 열, 92일 규칙, 권한(SEARCH), A사로 B사.
 * 실적은 ResultViewTest 와 같이 실제 POP API 로 남긴다(작업자 2명·불량·정정 1건).
 */
@AutoConfigureMockMvc
class ResultViewGridTest extends PostgresTestBase {

    private static final String PW = "Hazel-orbit-46";
    private static final String RES = "/api/production/results";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    /** 조회 기준일 = 실적이 들어가는 작업일(POP 세션 workDay). 달력 날짜가 아니다 — 야간조(18:00~06:00) 자정 뒤 실적은 전날 작업일(docs/25 D26) */
    String today;
    long spot;
    String cookie;
    MockHttpSession s;
    String ip;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("RV");
        ip = "10.83." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        admin = seed(comp);
        String calendarDay = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long wo = id(mvc.perform(json(post("/api/production/wos"), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"100\",\"planStart\":\""
                + calendarDay + "\"}").with(admin)));
        mvc.perform(json(post("/api/production/wos/" + wo + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        spot = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, wo);
        cookie = terminal("TAB-01");
        s = new MockHttpSession();
        badge("BADGE-0001");
        // 작업일은 근무조 기준으로 서버가 정한다(WorkDayResolver) — 시험도 같은 값으로 조회해야 자정~06:00 KST 에도 맞는다
        today = JsonPath.read(mvc.perform(get("/api/pop/session").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.workDay");
        pop(post("/api/pop/procs/" + spot + "/events/start"), "{\"clientReqId\":\"" + req() + "\",\"ack\":true}").andExpect(status().isOk());
    }

    /** 회사 준비 — 권한 사용자, 공장·라인, 근무조 2개(하루를 다 덮음), 작업자 2명, FG 라우팅(SPOT)·BOM, 불량 유형 */
    private RequestPostProcessor seed(String c) {
        long u = accounts.newUser(c, "admin1", PW);
        accounts.grant(c, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(c, u, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(c, u, "ADM", ResultViewService.PROG, "SEARCH", "EXCEL");
        accounts.addPlant(c, "P1", "1공장");
        long area = accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'AREA', 'A1', 'A1', 't', 't') returning node_id""", Long.class, c);
        long line = accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LINE', 'L1', 'L1', 't', 't') returning node_id""", Long.class, c, area);
        accounts.exec(c, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, created_by, updated_by)
                values (?, 'P1', 'D', '주간', '06:00', '18:00', 't', 't'), (?, 'P1', 'N', '야간', '18:00', '06:00', 't', 't')""", c, c);
        accounts.exec(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't'), (?, 'E2', '김작업', 'P1', ?, 't', 't')""",
                c, WorkerService.badgeHash(c, "BADGE-0001"), c, WorkerService.badgeHash(c, "BADGE-0002"));
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'RM', 'RM 품목', 'FG', 'MAKE', 'KG', 't', 't')""", c, c);
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't')""", c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), ?, 30, 't', 't')""", c, rid, line);
        accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long bom = accounts.query(c, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't')
                returning bom_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 1, 'KG', 'NORMAL', 'BACKFLUSH', 't', 't')""", c, bom);
        accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        accounts.exec(c, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-PIT', '기공', 'WELD', 't', 't')""", c);
        return accounts.as(c, u, "admin1");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String terminal(String cd) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private void badge(String badge) throws Exception {
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"" + badge + "\"}").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(status().isOk());
    }

    private static String req() {
        return UUID.randomUUID().toString();
    }

    /** POP 실적 — 폐기·재작업은 불량 D-PIT. 실적 id */
    private long result(int good, int scrap, int rework) throws Exception {
        String r = req();
        StringBuilder d = new StringBuilder();
        if (scrap > 0) {
            d.append("{\"kind\":\"SCRAP\",\"defectCd\":\"D-PIT\",\"qty\":\"").append(scrap).append("\"}");
        }
        if (rework > 0) {
            d.append(d.length() == 0 ? "" : ",").append("{\"kind\":\"REWORK\",\"defectCd\":\"D-PIT\",\"qty\":\"").append(rework).append("\"}");
        }
        pop(post("/api/pop/procs/" + spot + "/results"), "{\"clientReqId\":\"" + r + "\",\"goodQty\":\"" + good + "\",\"scrapQty\":\"" + scrap
                + "\",\"reworkQty\":\"" + rework + "\",\"defects\":[" + d + "]}").andExpect(status().isOk());
        return accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, r);
    }

    private ResultActions view(RequestPostProcessor who, String view, String extra) throws Exception {
        return mvc.perform(get(RES + "?view=" + view + "&from=" + today + "&to=" + today + (extra == null ? "" : "&" + extra)).with(who));
    }

    /** E1: 5·폐기1 / 3·재작업2 / 4(정정) → E2: 2 */
    private long seedResults() throws Exception {
        result(5, 1, 0);
        result(3, 0, 2);
        long r3 = result(4, 0, 0);
        pop(post("/api/pop/results/" + r3 + "/correct"), "{\"reason\":\"수량 잘못\"}").andExpect(status().isOk());
        badge("BADGE-0002");
        result(2, 0, 0);
        return r3;
    }

    private ResultActions grid(RequestPostProcessor who, String view, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(RES + "/grid").param("from", today).param("to", today);
        if (view != null) {
            b = b.param("view", view);
        }
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private static <T> List<T> read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    /** 기존 목록 API 응답에서 읽기 */
    private <T> List<T> listed(String view, String path) throws Exception {
        return JsonPath.read(view(admin, view, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    // ---------- 정상 ----------

    @Test
    void 보기마다_목록과_같은_행_같은_순서_쪽을_이어도_같다() throws Exception {
        seedResults();
        // 보기마다 기본 순서 = 목록 API(묶음 키·건수·양품이 같은 줄 순서로)
        for (String v : List.of("DETAIL", "WO", "PROC", "WORKER", "SHIFT")) {
            List<Object> all = listed(v, "$.data.rows[*].goodQtyText");
            grid(admin, v, "size", "100").andExpect(jsonPath("$.total").value(all.size()));
            List<Object> got = read(grid(admin, v, "size", "100"), "$.list[*].goodQtyText");
            assertThat(got).as(v).containsExactlyElementsOf(all);
        }
        assertThat(read(grid(admin, "SHIFT", "size", "100"), "$.list[*].shiftCd"))
                .containsExactlyElementsOf(listed("SHIFT", "$.data.rows[*].shiftCd"));
        // 상세: 같은 실적 id·같은 순서, 쪽 경계에서 중복·누락 없음
        List<Integer> ids = listed("DETAIL", "$.data.rows[*].resultId");
        assertThat(read(grid(admin, "DETAIL", "size", "100"), "$.list[*].resultId")).containsExactlyElementsOf(ids);
        List<Integer> paged = new ArrayList<>();
        for (int p = 1; p <= 2; p++) {
            paged.addAll(read(grid(admin, "DETAIL", "size", "3", "page", String.valueOf(p)), "$.list[*].resultId"));
        }
        assertThat(paged).containsExactlyElementsOf(ids);
        grid(admin, "DETAIL", "size", "3").andExpect(jsonPath("$.total").value(4)).andExpect(jsonPath("$.list.length()").value(3));
        assertThat(read(grid(admin, "DETAIL", "size", "3", "page", "3"), "$.list[*]")).isEmpty();
        grid(admin, "DETAIL", "total", "false").andExpect(jsonPath("$.total").doesNotExist());
        // 정정 표시는 그리드에도(흐리게 그릴 근거)
        grid(admin, "DETAIL").andExpect(jsonPath("$.list[?(@.corrected == true)].correctReason").value(Matchers.contains("수량 잘못")));
        // 작업자별: 목록과 같은 작업자 순서·순수량(정정 제외)
        assertThat(read(grid(admin, "WORKER"), "$.list[*].workerCd")).containsExactly("E1", "E2");
        grid(admin, "WORKER").andExpect(jsonPath("$.list[0].goodQtyText").value("8")).andExpect(jsonPath("$.list[0].cnt").value(2));
    }

    @Test
    void 정렬은_보기별_허용목록만() throws Exception {
        seedResults();
        // 상세 양품 5·3·4(정정)·2 → 내림차순·오름차순
        assertThat(read(grid(admin, "DETAIL", "sort", "goodQty,desc"), "$.list[*].goodQtyText")).containsExactly("5", "4", "3", "2");
        assertThat(read(grid(admin, "DETAIL", "sort", "goodQty,asc"), "$.list[*].goodQtyText")).containsExactly("2", "3", "4", "5");
        assertThat(read(grid(admin, "WORKER", "sort", "goodQty,asc"), "$.list[*].workerCd")).containsExactly("E2", "E1");
        assertThat(read(grid(admin, "WORKER", "sort", "workerCd,desc"), "$.list[*].workerCd")).containsExactly("E2", "E1");
        // 그 보기에 없는 칸(묶음의 LOT·지시, 상세의 건수)·정렬 안 하는 칸·모르는 방향·4개 이상·쪽 크기 0 은 400
        grid(admin, "WO", "sort", "lotNo,asc").andExpect(status().isBadRequest());
        grid(admin, "WORKER", "sort", "woNo,asc").andExpect(status().isBadRequest());
        grid(admin, "DETAIL", "sort", "cnt,desc").andExpect(status().isBadRequest());
        grid(admin, "DETAIL", "sort", "resultId,asc").andExpect(status().isBadRequest());
        grid(admin, "DETAIL", "sort", "correctReason,asc").andExpect(status().isBadRequest());
        grid(admin, "DETAIL", "sort", "goodQty,up").andExpect(status().isBadRequest());
        grid(admin, "DETAIL", "sort", "goodQty", "sort", "scrapQty", "sort", "woNo", "sort", "lotNo").andExpect(status().isBadRequest());
        grid(admin, "DETAIL", "size", "0").andExpect(status().isBadRequest());
    }

    @Test
    void 조건은_목록과_같고_없는_코드는_빈_쪽() throws Exception {
        seedResults();
        grid(admin, "DETAIL", "workerCd", "e2").andExpect(jsonPath("$.total").value(1));
        grid(admin, "DETAIL", "workerCd", "NOPE").andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        grid(admin, "DETAIL", "itemCd", "RM").andExpect(jsonPath("$.total").value(0));
        grid(admin, "DETAIL", "procCd", "spot").andExpect(jsonPath("$.total").value(4));
        grid(admin, "DETAIL", "keyword", "%").andExpect(jsonPath("$.total").value(0));   // % 는 글자로
        grid(admin, "WO", "plantCd", "P9").andExpect(jsonPath("$.total").value(0));
        // 불량 합계 API = 목록 API 의 defects
        List<Object> defects = listed("DETAIL", "$.data.defects[*].qtyText");
        mvc.perform(get(RES + "/defects?view=DETAIL&from=" + today + "&to=" + today).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].qtyText", Matchers.contains(defects.toArray())))
                .andExpect(jsonPath("$.data[*].kind", Matchers.contains("REWORK", "SCRAP")));
        mvc.perform(get(RES + "/defects?from=" + today + "&to=" + today + "&workerCd=NOPE").with(admin))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 열은_보기마다_정렬표시는_서버_허용목록과_같다() throws Exception {
        mvc.perform(get(RES + "/columns?view=WORKER").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[*].field", Matchers.contains("workerCd", "workerNm", "cnt", "goodQty", "scrapQty", "reworkQty",
                        "repairQty", "unitCd", "defectRate")))
                .andExpect(jsonPath("$.list[0].title").value("작업자 코드"));
        mvc.perform(get(RES + "/columns?view=DETAIL").with(admin))
                .andExpect(jsonPath("$.list[*].field", Matchers.hasItems("workDay", "woNo", "lotNo", "correctReason", "approverNm")));
        mvc.perform(get(RES + "/columns").with(admin)).andExpect(jsonPath("$.list[0].field").value("workDay"));   // 기본 = 상세
        mvc.perform(get(RES + "/columns?view=BAD").with(admin)).andExpect(status().isBadRequest());
        // 화면이 정렬을 고를 수 있는 칸 = 서버가 받는 칸(보기마다)
        for (String v : List.of("DETAIL", "WO", "PROC", "WORKER", "SHIFT")) {
            List<String> sortable = JsonPath.read(mvc.perform(get(RES + "/columns?view=" + v).with(admin)).andReturn().getResponse()
                    .getContentAsString(), "$.list[?(@.sortable == true)].field");
            Set<String> allowed = Arrays.stream(ResultViewService.SORTS.get(v)).map(ResultViewDtos.ResultSort::field).collect(Collectors.toSet());
            assertThat(Set.copyOf(sortable)).as(v).isEqualTo(allowed);
        }
    }

    @Test
    void 그리드_우클릭_엑셀은_정렬_열_목록이_붙어도_받는다() throws Exception {
        seedResults();
        // hb.grid 의 엑셀 메뉴는 조건 + sort·columns 를 함께 보낸다 — 기존 엑셀 API 는 그 칸을 쓰지 않고 지금 보기 그대로
        MvcResult m = mvc.perform(json(post(RES + "/excel"), "{\"view\":\"DETAIL\",\"from\":\"" + today + "\",\"to\":\"" + today
                + "\",\"sort\":[\"goodQty,desc\"],\"columns\":[\"woNo\"]}").with(admin)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(m.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(4);
        }
    }

    // ---------- 이상 사용 ----------

    @Test
    void A_보기_코드_기간_권한() throws Exception {
        grid(admin, "BAD").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("view"));
        mvc.perform(get(RES + "/grid").with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        String long93 = LocalDate.parse(today).minusDays(92).toString();
        mvc.perform(get(RES + "/grid?from=" + long93 + "&to=" + today).with(admin)).andExpect(jsonPath("$.errors[0].code").value("RANGE_TOO_LONG"));
        mvc.perform(get(RES + "/defects?from=" + long93 + "&to=" + today).with(admin)).andExpect(jsonPath("$.errors[0].code").value("RANGE_TOO_LONG"));
        String later = LocalDate.parse(today).plusDays(1).toString();
        mvc.perform(get(RES + "/grid?from=" + later + "&to=" + today).with(admin)).andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        grid(admin, "DETAIL", "keyword", "가".repeat(51)).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        // 조회 권한 = SEARCH, 없으면 grid·columns·defects 모두 403
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", ResultViewService.PROG, "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        grid(v, "DETAIL").andExpect(status().isOk());
        mvc.perform(get(RES + "/columns?view=WO").with(v)).andExpect(status().isOk());
        long none = accounts.newUser(comp, "none", PW);
        accounts.grant(comp, none, "OTHER", "prd-wo", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none");
        grid(n, "DETAIL").andExpect(status().isForbidden());
        mvc.perform(get(RES + "/columns?view=WO").with(n)).andExpect(status().isForbidden());
        mvc.perform(get(RES + "/defects?from=" + today + "&to=" + today).with(n)).andExpect(status().isForbidden());
    }

    @Test
    void B_A사로_B사_그리드_불량합계() throws Exception {
        seedResults();
        String other = accounts.newComp("RY");
        RequestPostProcessor b = seed(other);
        for (String v : List.of("DETAIL", "WO", "PROC", "WORKER", "SHIFT")) {
            grid(b, v).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        }
        grid(b, "DETAIL", "workerCd", "E1").andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(RES + "/defects?from=" + today + "&to=" + today).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // A 는 그대로
        grid(admin, "DETAIL").andExpect(jsonPath("$.total").value(4));
    }
}
