package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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
 * 사무 실적 조회 (prd-result, docs/25 4.16) — 실적은 실제 POP API(작업자 2명·불량·정정 1건)로 남기고 보기 5종·조건·불량 합계·엑셀을 본다.
 * 줄의 기준 = 원래 실적, 정정된 실적은 상세에서 표시·묶음에서 제외(순수량). 이상 사용: 보기 코드·기간·권한, A사로 B사.
 */
@AutoConfigureMockMvc
class ResultViewTest extends PostgresTestBase {

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

    // ---------- 정상 ----------

    @Test
    void 보기_다섯가지는_원래_실적_기준이고_묶음은_정정을_뺀_순수량() throws Exception {
        long r3 = seedResults();
        // 상세: 원래 실적 4줄(역분개 행은 따로 없다), 정정된 줄은 표시·사유
        view(admin, "DETAIL", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.rows.length()").value(4))
                .andExpect(jsonPath("$.data.rows[?(@.resultId == " + r3 + ")].corrected").value(Matchers.contains(true)))
                .andExpect(jsonPath("$.data.rows[?(@.resultId == " + r3 + ")].correctReason").value(Matchers.contains("수량 잘못")))
                .andExpect(jsonPath("$.data.rows[?(@.workerCd == 'E2')].goodQtyText").value(Matchers.contains("2")))
                .andExpect(jsonPath("$.data.rows[0].shiftCd").value(Matchers.oneOf("D", "N")));
        // 지시별: 정정 빼고 3건 — 양품 10·폐기 1·재작업 2, 불량률 3/13
        view(admin, "WO", null).andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].cnt").value(3))
                .andExpect(jsonPath("$.data.rows[0].goodQtyText").value("10"))
                .andExpect(jsonPath("$.data.rows[0].scrapQtyText").value("1"))
                .andExpect(jsonPath("$.data.rows[0].reworkQtyText").value("2"))
                .andExpect(jsonPath("$.data.rows[0].unitCd").value("EA"))
                .andExpect(jsonPath("$.data.rows[0].defectRateText").value("23.08"));
        view(admin, "PROC", null).andExpect(jsonPath("$.data.rows[0].opSeq").value(10))
                .andExpect(jsonPath("$.data.rows[0].procCd").value("SPOT")).andExpect(jsonPath("$.data.rows[0].goodQtyText").value("10"));
        view(admin, "WORKER", null).andExpect(jsonPath("$.data.rows[*].workerCd", Matchers.contains("E1", "E2")))
                .andExpect(jsonPath("$.data.rows[0].goodQtyText").value("8")).andExpect(jsonPath("$.data.rows[1].goodQtyText").value("2"));
        // 작업일·근무조: 근무조가 하루를 다 덮으므로 근무조 밖 없음, 합계 3건
        MvcResult sh = view(admin, "SHIFT", null).andExpect(jsonPath("$.data.rows[*].shiftCd", Matchers.everyItem(Matchers.notNullValue())))
                .andReturn();
        java.util.List<Integer> cnts = JsonPath.read(sh.getResponse().getContentAsString(), "$.data.rows[*].cnt");
        assertThat(cnts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
        // 불량 유형 합계(정정 제외)
        view(admin, "DETAIL", null).andExpect(jsonPath("$.data.defects[*].kind", Matchers.contains("REWORK", "SCRAP")))
                .andExpect(jsonPath("$.data.defects[?(@.kind == 'SCRAP')].qtyText").value(Matchers.contains("1")))
                .andExpect(jsonPath("$.data.defects[?(@.kind == 'REWORK')].qtyText").value(Matchers.contains("2")))
                .andExpect(jsonPath("$.data.defects[0].defectCd").value("D-PIT"));
    }

    @Test
    void 조건은_작업자_품목_공정_지시_공장_근무조_기간으로_좁히고_없는_코드는_빈_결과() throws Exception {
        seedResults();
        view(admin, "DETAIL", "workerCd=e2").andExpect(jsonPath("$.data.total").value(1));
        view(admin, "DETAIL", "workerCd=NOPE").andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.defects.length()").value(0));
        view(admin, "DETAIL", "itemCd=fg").andExpect(jsonPath("$.data.total").value(4));
        view(admin, "DETAIL", "itemCd=RM").andExpect(jsonPath("$.data.total").value(0));
        view(admin, "DETAIL", "itemCd=NOPE").andExpect(jsonPath("$.data.total").value(0));
        view(admin, "DETAIL", "procCd=spot").andExpect(jsonPath("$.data.total").value(4));
        view(admin, "DETAIL", "plantCd=P9").andExpect(jsonPath("$.data.total").value(0));
        view(admin, "DETAIL", "keyword=%25").andExpect(jsonPath("$.data.total").value(0));   // % 는 글자로
        String shift = accounts.query(comp, "select shift_cd from prd_result where reversal_of_id is null limit 1", String.class);
        view(admin, "DETAIL", "shiftCd=" + shift.toLowerCase()).andExpect(jsonPath("$.data.total").value(4));
        String yesterday = LocalDate.parse(today).minusDays(1).toString();
        mvc.perform(get(RES + "?from=" + yesterday + "&to=" + yesterday).with(admin)).andExpect(jsonPath("$.data.total").value(0));
        // 공장 근무조 선택지(사용 중지 포함)
        mvc.perform(get(RES + "/shifts?plantCd=p1").with(admin)).andExpect(jsonPath("$.data[*].shiftCd", Matchers.contains("D", "N")));
    }

    @Test
    void 엑셀은_지금_보기_그대로_숫자_칸() throws Exception {
        seedResults();
        MvcResult m = mvc.perform(json(post(RES + "/excel"), "{\"view\":\"WORKER\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(m.getResponse().getContentAsByteArray()))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getRow(0).getCell(0).getStringCellValue()).isEqualTo("작업자 코드");
            assertThat(sh.getLastRowNum()).isEqualTo(2);
            Row e1 = sh.getRow(1);
            assertThat(e1.getCell(0).getStringCellValue()).isEqualTo("E1");
            assertThat(e1.getCell(2).getNumericCellValue()).isEqualTo(2);       // 건수(정정 제외)
            assertThat(e1.getCell(3).getNumericCellValue()).isEqualTo(8);       // 양품
        }
        MvcResult d = mvc.perform(json(post(RES + "/excel"), "{\"view\":\"DETAIL\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(d.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(4);
        }
    }

    // ---------- 이상 사용 ----------

    @Test
    void A_보기_코드_기간_권한() throws Exception {
        view(admin, "BAD", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("view"))
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        mvc.perform(get(RES).with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        String later = LocalDate.parse(today).plusDays(1).toString();
        mvc.perform(get(RES + "?from=" + later + "&to=" + today).with(admin)).andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        String long93 = LocalDate.parse(today).minusDays(92).toString();
        mvc.perform(get(RES + "?from=" + long93 + "&to=" + today).with(admin)).andExpect(jsonPath("$.errors[0].code").value("RANGE_TOO_LONG"));
        mvc.perform(get(RES + "?from=" + today + "&to=" + today + "&keyword=" + "가".repeat(51)).with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        mvc.perform(json(post(RES + "/excel"), "{\"view\":\"BAD\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}").with(admin))
                .andExpect(status().isBadRequest());
        // 조회만 → 엑셀 403, 권한 없음 → 조회 403
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", ResultViewService.PROG, "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        view(v, "DETAIL", null).andExpect(status().isOk());
        mvc.perform(json(post(RES + "/excel"), "{\"view\":\"DETAIL\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}").with(v))
                .andExpect(status().isForbidden());
        long none = accounts.newUser(comp, "none", PW);
        view(accounts.as(comp, none, "none"), "DETAIL", null).andExpect(status().isForbidden());
    }

    @Test
    void B_A사로_B사_조회_엑셀_선택지() throws Exception {
        seedResults();
        String other = accounts.newComp("RX");
        RequestPostProcessor b = seed(other);
        mvc.perform(get(RES + "?view=WO&from=" + today + "&to=" + today).with(b)).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.defects.length()").value(0));
        mvc.perform(get(RES + "?from=" + today + "&to=" + today + "&workerCd=E1").with(b)).andExpect(jsonPath("$.data.total").value(0));
        MvcResult m = mvc.perform(json(post(RES + "/excel"), "{\"view\":\"DETAIL\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}")
                .with(b)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(m.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isZero();
        }
        // A 는 그대로
        view(admin, "DETAIL", null).andExpect(jsonPath("$.data.total").value(4));
    }
}
