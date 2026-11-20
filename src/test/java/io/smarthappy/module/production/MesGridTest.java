package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.pop.TerminalService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * UI 단계 3 ① MES 주 목록 (docs/25 4.30·4.31) — 지시·입고 대상 지시·POP 단말·LOT grid API: 목록과 같은 조건·같은 기본 순서,
 * 페이지 경계·전체 건수, 정렬 허용 목록(그 밖은 400), 열 정의, 권한(SEARCH), A사로 B사. 기존 목록 API 는 그대로.
 */
@AutoConfigureMockMvc
class MesGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String WO = "/api/production/wos";
    private static final String RCV = "/api/production/receipts/wos";
    private static final String TERM = "/api/production/terminals";
    private static final String LOT = "/api/production/lots";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("MG");
        long u = accounts.newUser(comp, "admin1", PW);
        grant(comp, u);
        admin = accounts.as(comp, u, "admin1");
        seedMaster(comp);
        today = LocalDate.parse(accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class));
        // 지시 4: 계획 시작이 서로 다르게, 같은 날은 없음. 진행 중 2개 = 입고 대상(기본 상태 = 진행 중·완료)
        wo(comp, "W-A", today, 5, null);
        wo(comp, "W-B", today.plusDays(1), 1, "RELEASED");
        wo(comp, "W-C", today.plusDays(2), 3, "IN_PROGRESS");
        wo(comp, "W-D", today.plusDays(3), 2, "IN_PROGRESS");
        // 단말 3: 하나는 폐기(정렬과 관계없이 늘 끝)
        terminal("POP-B");
        terminal("POP-A");
        long revoked = terminal("POP-0");
        mvc.perform(json(post(TERM + "/" + revoked + "/revoke"), "{\"reason\":\"분실\",\"rowVer\":0}").with(admin)).andExpect(status().isOk());
    }

    private void grant(String c, long u) {
        accounts.grant(c, u, "MES", WoService.PROG, "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "MES", ReceiptService.PROG, "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "MES", TerminalService.PROG, "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "MES", LotService.PROG, "SEARCH", "SAVE", "EXCEL");
    }

    /** 공장 P1 + 품목 FG·확정 라우팅 */
    private void seedMaster(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿', 'WELD', 't', 't')""", c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 900, 't', 't')
                returning routing_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), 't', 't')""", c, rid);
        accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
    }

    private void wo(String c, String no, LocalDate start, int priority, String status) {
        long id = accounts.query(c, """
                insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, priority,
                                    created_by, updated_by)
                select ?, ?, 'P1', i.item_id, 10, 'EA', ?, ?, r.routing_id, ?, 't', 't'
                  from mst_item i join mst_routing r on r.item_id = i.item_id where i.item_cd = 'FG'
                returning wo_id""", Long.class, c, no, start, start, priority);
        if (status != null) {
            accounts.exec(c, "update prd_wo set status = 'RELEASED', released_at = now(), released_by = 't' where wo_id = ?", id);
            if ("IN_PROGRESS".equals(status)) {
                accounts.exec(c, "update prd_wo set status = 'IN_PROGRESS' where wo_id = ?", id);
            }
        }
    }

    private long terminal(String cd) throws Exception {
        ResultActions r = mvc.perform(json(post(TERM), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd + "\",\"plantCd\":\"P1\","
                + "\"terminalType\":\"POP\"}").with(admin)).andExpect(status().isOk());
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.data.terminalId")).longValue();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions grid(RequestPostProcessor who, String url, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(url + "/grid");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private static List<String> read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    /** 기본 순서 = 목록 API 순서(같은 조건) */
    private void sameAsList(String listUrl, String listPath, String gridUrl, String field, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(listUrl);
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        List<String> listed = JsonPath.read(mvc.perform(b.with(admin)).andReturn().getResponse().getContentAsString(), listPath);
        assertThat(listed).isNotEmpty();
        String[] withSize = java.util.Arrays.copyOf(params, params.length + 2);
        withSize[params.length] = "size";
        withSize[params.length + 1] = "50";
        assertThat(read(grid(admin, gridUrl, withSize), "$.list[*]." + field)).containsExactlyElementsOf(listed);
    }

    @Test
    void 지시_grid_페이지경계_정렬_목록과_같은_조건() throws Exception {
        grid(admin, WO, "size", "2").andExpect(jsonPath("$.total").value(4));
        // 기본 = 계획 시작 최신 → 우선
        assertThat(read(grid(admin, WO, "size", "2"), "$.list[*].woNo")).containsExactly("W-D", "W-C");
        assertThat(read(grid(admin, WO, "size", "2", "page", "2"), "$.list[*].woNo")).containsExactly("W-B", "W-A");
        assertThat(read(grid(admin, WO, "size", "2", "page", "3"), "$.list[*].woNo")).isEmpty();
        assertThat(read(grid(admin, WO, "size", "4", "sort", "priority,asc"), "$.list[*].woNo")).containsExactly("W-B", "W-D", "W-C", "W-A");
        assertThat(read(grid(admin, WO, "size", "4", "sort", "woNo,asc"), "$.list[*].woNo")).containsExactly("W-A", "W-B", "W-C", "W-D");
        sameAsList(WO, "$.data.rows[*].woNo", WO, "woNo");
        sameAsList(WO, "$.data.rows[*].woNo", WO, "woNo", "status", "ACTIVE");
        grid(admin, WO, "status", "ACTIVE").andExpect(jsonPath("$.total").value(3));
        grid(admin, WO, "from", today.plusDays(1).toString(), "to", today.plusDays(2).toString()).andExpect(jsonPath("$.total").value(2));
        grid(admin, WO, "keyword", "w-c").andExpect(jsonPath("$.total").value(1));
        grid(admin, WO, "itemCd", "NOPE").andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        grid(admin, WO, "status", "X").andExpect(status().isBadRequest());
        grid(admin, WO, "from", today.toString(), "to", today.minusDays(1).toString()).andExpect(status().isBadRequest());
        grid(admin, WO, "sort", "remark,asc").andExpect(status().isBadRequest());                // 허용 목록 밖
        grid(admin, WO, "sort", "planQty,desc").andExpect(status().isBadRequest());
        mvc.perform(get(WO + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("woNo", "planQty", "orderRef", "status")));
    }

    @Test
    void 입고대상_grid_공장필수_기본은_진행중과_완료() throws Exception {
        grid(admin, RCV).andExpect(status().isBadRequest());                                     // 공장 필수(목록과 같게)
        grid(admin, RCV, "plantCd", "P1").andExpect(jsonPath("$.total").value(2));
        assertThat(read(grid(admin, RCV, "plantCd", "P1", "size", "1"), "$.list[*].woNo")).containsExactly("W-D");
        assertThat(read(grid(admin, RCV, "plantCd", "P1", "size", "1", "page", "2"), "$.list[*].woNo")).containsExactly("W-C");
        assertThat(read(grid(admin, RCV, "plantCd", "P1", "sort", "woNo,asc"), "$.list[*].woNo")).containsExactly("W-C", "W-D");
        sameAsList(RCV, "$.data[*].woNo", RCV, "woNo", "plantCd", "P1");
        grid(admin, RCV, "plantCd", "P1", "status", "CLOSED").andExpect(jsonPath("$.total").value(0));
        grid(admin, RCV, "plantCd", "P1", "status", "PLANNED").andExpect(status().isBadRequest());
        grid(admin, RCV, "plantCd", "P1", "sort", "receivedQty,desc").andExpect(status().isBadRequest());   // 계산 칸은 정렬 밖
        grid(admin, RCV, "plantCd", "P1", "size", "1").andExpect(jsonPath("$.list[0].receivedQty").value("0"))
                .andExpect(jsonPath("$.list[0].planStart").value(today.plusDays(3).toString()));
        mvc.perform(get(RCV + "/columns").with(admin))
                .andExpect(jsonPath("$.list[*].field").value(hasItems("woNo", "lastGoodQty", "receivedQty", "status")));
    }

    @Test
    void 단말_grid_폐기는_늘_끝_상태는_서버계산() throws Exception {
        grid(admin, TERM, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, TERM, "size", "2"), "$.list[*].terminalCd")).containsExactly("POP-A", "POP-B");
        assertThat(read(grid(admin, TERM, "size", "2", "page", "2"), "$.list[*].terminalCd")).containsExactly("POP-0");
        // 코드 내림차순이어도 폐기(POP-0)는 끝
        assertThat(read(grid(admin, TERM, "sort", "terminalCd,desc"), "$.list[*].terminalCd")).containsExactly("POP-B", "POP-A", "POP-0");
        assertThat(read(grid(admin, TERM, "sort", "terminalCd,asc"), "$.list[*].state")).containsExactly("WAITING", "WAITING", "REVOKED");
        sameAsList(TERM, "$.data[*].terminalCd", TERM, "terminalCd");
        // 등록 코드 기한이 지나면 EXPIRED
        accounts.exec(comp, "update sys_terminal set activation_expires_at = now() - interval '1 minute' where terminal_cd = 'POP-A'");
        grid(admin, TERM, "size", "1").andExpect(jsonPath("$.list[0].state").value("EXPIRED"))
                .andExpect(jsonPath("$.list[0].nodeRef").value(""));   // null 이면 화면 format("(공장 전체)")이 불리지 않는다
        grid(admin, TERM, "sort", "state,asc").andExpect(status().isBadRequest());               // 계산 칸은 정렬 밖
        mvc.perform(get(TERM + "/columns").with(admin))
                .andExpect(jsonPath("$.list[*].field").value(hasItems("terminalCd", "nodeRef", "state", "lastSeenAt")));
    }

    /** LOT — 등록일(회사 날짜 정오)·수량 */
    private long lot(String no, int qty, LocalDate createdOn) {
        return accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', ?, unit_cd, now(), (cast(? as date) + time '12:00') at time zone 'Asia/Seoul', 't', 't'
                  from mst_item where item_cd = 'FG' returning lot_id""", Long.class, comp, no, qty, createdOn);
    }

    @Test
    void LOT_grid_기본은_등록최신_상태는_보류_잔량0_정렬밖() throws Exception {
        lot("L-OLD", 5, today.minusDays(3));
        lot("L-B", 4, today.minusDays(1));
        long held = lot("L-HELD", 3, today.minusDays(2));
        long empty = lot("L-EMPTY", 2, today.minusDays(4));
        long sink = lot("L-SINK", 1, today.minusDays(5));
        accounts.exec(comp, """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, 'CONSUME', 2, 'EA', now(), 'KEY', 't')""", comp, empty, sink);
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) values (?, ?, 'MANUAL', '검사', 't')", comp, held);

        grid(admin, LOT, "size", "2").andExpect(jsonPath("$.total").value(5));
        // 기본 = 등록 최신(목록과 같게)
        assertThat(read(grid(admin, LOT, "size", "2"), "$.list[*].lotNo")).containsExactly("L-B", "L-HELD");
        assertThat(read(grid(admin, LOT, "size", "2", "page", "3"), "$.list[*].lotNo")).containsExactly("L-SINK");
        assertThat(read(grid(admin, LOT, "sort", "lotNo,asc"), "$.list[*].lotNo")).containsExactly("L-B", "L-EMPTY", "L-HELD", "L-OLD", "L-SINK");
        sameAsList(LOT, "$.data.rows[*].lotNo", LOT, "lotNo");
        // 상태 = 보류 HELD · 잔량 0 EMPTY · 그 밖 ACTIVE(화면이 병합 체크를 가린다), 출처 칸 = 공급사 LOT·지시 번호
        assertThat(read(grid(admin, LOT, "sort", "lotNo,asc"), "$.list[*].state")).containsExactly("ACTIVE", "EMPTY", "HELD", "ACTIVE", "ACTIVE");
        grid(admin, LOT, "holdOnly", "Y").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].lotNo").value("L-HELD"));
        grid(admin, LOT, "from", today.minusDays(2).toString(), "to", today.toString()).andExpect(jsonPath("$.total").value(2));
        grid(admin, LOT, "keyword", "L-O").andExpect(jsonPath("$.total").value(1));
        grid(admin, LOT, "itemCd", "NOPE").andExpect(jsonPath("$.total").value(0));
        grid(admin, LOT, "origin", "X").andExpect(status().isBadRequest());
        grid(admin, LOT, "holdOnly", "X").andExpect(status().isBadRequest());
        grid(admin, LOT, "sort", "balance,desc").andExpect(status().isBadRequest());                  // 원장 계산 칸은 정렬 밖
        grid(admin, LOT, "sort", "state,asc").andExpect(status().isBadRequest());
        mvc.perform(get(LOT + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("lotNo", "state", "balance", "origin")));
        // 체크로 보류 LOT 을 골라 병합을 보내도 서버가 막는다(화면은 버튼을 막을 뿐, D64)
        long ok = lot("L-OK", 2, today);
        mvc.perform(json(post(LOT + "/merge"), "{\"parts\":[{\"lotId\":" + ok + "},{\"lotId\":" + held + "}],\"reason\":\"t\"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        mvc.perform(json(post(LOT + "/merge"), "{\"parts\":[{\"lotId\":" + ok + "},{\"lotId\":" + empty + "}],\"reason\":\"t\"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("EMPTY_LOT"));
    }

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        String other = accounts.newComp("MH");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        accounts.addPlant(other, "P1", "1공장");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        grid(b, WO).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        grid(b, RCV, "plantCd", "P1").andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        grid(b, TERM).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        lot("L-A", 1, today);
        grid(b, LOT).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        grid(b, LOT, "keyword", "L-A").andExpect(jsonPath("$.total").value(0));
        // A사 지시 번호로 찾아도 없음
        grid(b, WO, "keyword", "W-A").andExpect(jsonPath("$.total").value(0));

        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "sal-order", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        for (String[] g : new String[][] {{WO}, {RCV, "plantCd", "P1"}, {TERM}, {LOT}}) {
            grid(n, g[0], java.util.Arrays.copyOfRange(g, 1, g.length)).andExpect(status().isForbidden());
            mvc.perform(get(g[0] + "/columns").with(n)).andExpect(status().isForbidden());
        }
    }

    // ---------- 쪽 경계 (docs/25 4.41 지연 조인) ----------

    @Test
    void 정렬_값이_같은_행이_많아도_쪽마다_빠짐_겹침_없음() throws Exception {
        // 등록 시각이 모두 같은 LOT 7개 — 안쪽(지연 조인)·바깥 순서가 lot_id 로 끝까지 정해져야 쪽 경계에서 빠지거나 겹치지 않는다
        for (int i = 1; i <= 7; i++) {
            lot("L-T" + i, i, today);
        }
        java.util.List<String> seen = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            seen.addAll(read(grid(admin, LOT, "size", "3", "page", String.valueOf(page)), "$.list[*].lotNo"));
        }
        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        // 내림차순 기본(등록 최신, 같으면 id 큰 것 먼저) — 마지막에 만든 L-T7 이 맨 앞
        assertThat(seen.get(0)).isEqualTo("L-T7");
        assertThat(read(grid(admin, LOT, "size", "3", "page", "2", "sort", "itemCd,asc"), "$.list[*].lotNo")).hasSize(3);
        // 입고 대상: 계획 시작이 같은 진행 중 지시 5개 + setUp 의 2개 — 쪽 3개를 이어 붙이면 7개, 겹침 없음
        for (int i = 1; i <= 5; i++) {
            wo(comp, "W-T" + i, today.plusDays(9), 1, "IN_PROGRESS");
        }
        java.util.List<String> wos = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            wos.addAll(read(grid(admin, RCV, "plantCd", "P1", "size", "3", "page", String.valueOf(page)), "$.list[*].woNo"));
        }
        assertThat(wos).hasSize(7).doesNotHaveDuplicates();
        assertThat(wos.subList(0, 5)).containsExactly("W-T5", "W-T4", "W-T3", "W-T2", "W-T1");
    }

    // ---------- grid 엑셀 (docs/25 4.40) ----------

    private ResultActions export(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url + "/export"), body).with(who));
    }

    /** 첫 시트 → 행별 글자(숫자는 정수 글자, 날짜 셀은 날짜 글자) */
    private List<List<String>> sheet(RequestPostProcessor who, String url, String body) throws Exception {
        byte[] x = export(who, url, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<List<String>> out = new java.util.ArrayList<>();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(x))) {
            org.apache.poi.ss.usermodel.Sheet sh = wb.getSheetAt(0);
            int width = sh.getRow(0).getLastCellNum();
            for (int i = 0; i <= sh.getLastRowNum(); i++) {
                org.apache.poi.ss.usermodel.Row r = sh.getRow(i);
                List<String> cells = new java.util.ArrayList<>();
                for (int j = 0; j < width; j++) {
                    org.apache.poi.ss.usermodel.Cell c = r.getCell(j);
                    cells.add(c == null ? "" : c.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            ? (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(c) ? c.getLocalDateTimeCellValue().toLocalDate().toString()
                                    : String.valueOf((long) c.getNumericCellValue()))
                            : c.getStringCellValue());
                }
                out.add(cells);
            }
        }
        return out;
    }

    @Test
    void 엑셀_네_목록_grid_와_같은_조건_정렬_열_상태는_이름() throws Exception {
        // 지시: 기본 = 계획 시작 최신, 상태 = 코드명, 조건·열 고르기
        List<List<String>> w = sheet(admin, WO, "{\"columns\":[\"woNo\",\"status\"]}");
        assertThat(w).hasSize(5);
        assertThat(w.get(0)).hasSize(2);
        assertThat(w.subList(1, 5).stream().map(r -> r.get(0)).toList()).containsExactly("W-D", "W-C", "W-B", "W-A");
        assertThat(w.get(1).get(1)).isNotEqualTo("IN_PROGRESS").isNotBlank();
        assertThat(sheet(admin, WO, "{\"status\":\"ACTIVE\",\"sort\":[\"woNo,asc\"],\"columns\":[\"woNo\"]}").subList(1, 4))
                .containsExactly(List.of("W-B"), List.of("W-C"), List.of("W-D"));
        export(admin, WO, "{\"from\":\"2026-10-20\",\"to\":\"2026-10-01\"}").andExpect(status().isBadRequest());
        export(admin, WO, "{\"sort\":[\"itemNm,asc\"]}").andExpect(status().isBadRequest());           // 정렬 허용 목록 밖
        export(admin, WO, "{\"columns\":[\"rowVer\"]}").andExpect(status().isBadRequest());           // grid 열 밖
        // 입고 대상: 공장 필수, 기본 상태 = 진행 중·완료
        export(admin, RCV, "{}").andExpect(status().isBadRequest());
        assertThat(sheet(admin, RCV, "{\"plantCd\":\"P1\",\"columns\":[\"woNo\"]}")).hasSize(3);
        // 단말: 폐기는 끝, 상태·작업장은 화면과 같은 이름
        List<List<String>> t = sheet(admin, TERM, "{\"columns\":[\"terminalCd\",\"state\",\"nodeRef\"]}");
        assertThat(t).hasSize(4);
        assertThat(t.get(3).get(0)).isEqualTo("POP-0");
        assertThat(t.get(3).get(1)).isEqualTo("폐기");
        assertThat(t.get(1).get(1)).isEqualTo("활성화 대기");
        assertThat(t.get(1).get(2)).isEqualTo("(공장 전체)");
        // LOT: 상태 이름(보류), 보류만 조건
        long held = lot("L-HELD", 3, today.minusDays(1));
        lot("L-OK", 2, today);
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) values (?, ?, 'MANUAL', '검사', 't')", comp, held);
        List<List<String>> l = sheet(admin, LOT, "{\"sort\":[\"lotNo,asc\"],\"columns\":[\"lotNo\",\"state\"]}");
        assertThat(l.subList(1, 3)).containsExactly(List.of("L-HELD", "보류"), List.of("L-OK", "사용 가능"));
        assertThat(sheet(admin, LOT, "{\"holdOnly\":\"Y\",\"columns\":[\"lotNo\"]}")).hasSize(2);
        export(admin, LOT, "{\"origin\":\"X\"}").andExpect(status().isBadRequest());
        // 모든 열(수량 DEC 칸 포함) — 화면 응답은 수량을 글자로 담지만 엑셀은 숫자 셀(브라우저 확인에서 500 으로 발견, 4.40)
        List<List<String>> wAll = sheet(admin, WO, "{\"sort\":[\"woNo,asc\"]}");
        assertThat(wAll.get(0)).hasSize(10);
        assertThat(wAll.get(1).get(3)).isEqualTo("10");                                    // planQty 숫자 셀
        assertThat(sheet(admin, RCV, "{\"plantCd\":\"P1\"}")).hasSize(3);
        List<List<String>> lAll = sheet(admin, LOT, "{\"sort\":[\"lotNo,asc\"]}");
        assertThat(lAll.get(1).get(4)).isEqualTo("3");                                    // balance 숫자 셀
        assertThat(sheet(admin, TERM, "{}")).hasSize(4);
    }

    @Test
    void 엑셀_권한_EXCEL_없으면_403_B사는_머리줄만() throws Exception {
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", WoService.PROG, "SEARCH");
        accounts.grant(comp, viewer, "VIEW", ReceiptService.PROG, "SEARCH");
        accounts.grant(comp, viewer, "VIEW", TerminalService.PROG, "SEARCH");
        accounts.grant(comp, viewer, "VIEW", LotService.PROG, "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer1");
        for (String[] e : new String[][] {{WO, "{}"}, {RCV, "{\"plantCd\":\"P1\"}"}, {TERM, "{}"}, {LOT, "{}"}}) {
            grid(v, e[0], e[0].equals(RCV) ? new String[] {"plantCd", "P1"} : new String[0]).andExpect(status().isOk());
            export(v, e[0], e[1]).andExpect(status().isForbidden());
        }
        String other = accounts.newComp("MI");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        accounts.addPlant(other, "P1", "1공장");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        lot("L-A", 1, today);
        for (String[] e : new String[][] {{WO, "{}"}, {RCV, "{\"plantCd\":\"P1\"}"}, {TERM, "{}"}, {LOT, "{}"}}) {
            assertThat(sheet(b, e[0], e[1])).as(e[0]).hasSize(1);
        }
    }
}
