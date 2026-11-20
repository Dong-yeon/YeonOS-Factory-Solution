package io.smarthappy.module.apqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * P6 APQP A4 (docs/41 12장, X1~X8) — PFMEA·관리계획서 엑셀 양식: 머리·위험 표·조치 표·관리계획서 행(코드명·AP·특별특성),
 * 확정본 아님 표시·파일명, 인쇄 설정(가로·A3·머리 반복), 이상 입력(PRINT 없음 403·수식 주입·행 상한), A사로 B사 404.
 */
@AutoConfigureMockMvc
class ApqpA4Test extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String F = "/api/apqp/fmeas";
    private static final String CP = "/api/apqp/cps";
    private static final String RULES = """
            [{"sevFrom":9,"sevTo":10,"occFrom":1,"occTo":10,"detFrom":1,"detTo":10,"ap":"H"},
             {"sevFrom":1,"sevTo":8,"occFrom":6,"occTo":10,"detFrom":1,"detTo":10,"ap":"M"},
             {"sevFrom":1,"sevTo":8,"occFrom":1,"occTo":5,"detFrom":1,"detTo":10,"ap":"L"}]""";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long qeId;
    RequestPostProcessor qe;
    long routing;
    long step10;
    long weldChar;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("A6");
        qeId = accounts.newUser(comp, "qe1", PW);
        grantAll(comp, qeId);
        qe = accounts.as(comp, qeId, "qe1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        long[] fx = fixture(comp);
        routing = fx[0];
        step10 = fx[1];
        weldChar = fx[2];
    }

    private void grantAll(String c, long u) {
        accounts.grant(c, u, "APQP", "apqp-fmea", "SEARCH", "SAVE", "APPROVE", "PRINT");
        accounts.grant(c, u, "APQP", "apqp-ap", "SEARCH", "SAVE");
        accounts.grant(c, u, "APQP", "apqp-cp", "SEARCH", "SAVE", "APPROVE", "PRINT");
    }

    /** 품목 ZT-BRKT · 라우팅(A0, 작성 중) 10 용접 · 공정 특성 1 → [라우팅, 단계, 특성] */
    private long[] fixture(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'ZT-BRKT', 'ZT 브래킷', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'ZWELD', 'ZT 용접', 'WELD', 't', 't')""", c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'ZT-BRKT'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't')
                returning routing_id""", Long.class, c);
        long s10 = accounts.query(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ZWELD'), 't', 't') returning step_id""", Long.class, c, rid);
        long wc = accounts.query(c, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, created_by, updated_by)
                values (?, 'PROCESS', (select proc_id from mst_proc where proc_cd = 'ZWELD'), 'ZP1', 'ZT 용접 전류', 'ATTRIBUTE', 't', 't')
                returning char_id""", Long.class, c);
        return new long[] {rid, s10, wc};
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private long id(ResultActions r) throws Exception {
        String s = r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private <T> T read(String url, String path, RequestPostProcessor who) throws Exception {
        return JsonPath.read(mvc.perform(get(url).with(who)).andReturn().getResponse().getContentAsString(), path);
    }

    private int ver(String url, String path) throws Exception {
        return ((Number) read(url, path, qe)).intValue();
    }

    private void policy(String key, String value) {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')
                on conflict do nothing""", comp, key, value);
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = ? and plant_cd = '*'", value, key);
    }

    /** 작성 중 PFMEA(위험행 1, AP 계산됨) → id */
    private long draftFmea() throws Exception {
        long set = id(send(post("/api/apqp/ap-sets"), "{\"setCd\":\"ZT-EX\",\"setNm\":\"ZT 예시(핸드북 아님)\",\"useYn\":\"Y\",\"rules\":" + RULES + "}", qe));
        long fmea = id(send(post(F), "{\"itemCd\":\"ZT-BRKT\",\"routingId\":" + routing + ",\"title\":\"ZT PFMEA\",\"apSetId\":" + set
                + ",\"fromRouting\":true}", qe));
        List<Map<String, Object>> nodes = read(F + "/" + fmea, "$.data.nodes", qe);
        long item = nodes.stream().filter(n -> "ITEM".equals(n.get("nodeKind"))).map(n -> ((Number) n.get("nodeId")).longValue()).findFirst().orElseThrow();
        long step = nodes.stream().filter(n -> "STEP".equals(n.get("nodeKind"))).map(n -> ((Number) n.get("nodeId")).longValue()).findFirst().orElseThrow();
        long el = id(send(post(F + "/" + fmea + "/nodes"), "{\"parentId\":" + step + ",\"nodeKind\":\"ELEMENT\",\"nodeNm\":\"ZT 용접기\",\"m4\":\"MACHINE\"}", qe));
        long fi = id(send(post(F + "/" + fmea + "/funcs"), "{\"nodeId\":" + item + ",\"funcText\":\"고정\"}", qe));
        long fs = id(send(post(F + "/" + fmea + "/funcs"), "{\"nodeId\":" + step + ",\"funcText\":\"용접\",\"charId\":" + weldChar + "}", qe));
        long fe = id(send(post(F + "/" + fmea + "/funcs"), "{\"nodeId\":" + el + ",\"funcText\":\"전류 유지\"}", qe));
        long e = id(send(post(F + "/" + fmea + "/fails"), "{\"funcId\":" + fi + ",\"failText\":\"이탈\",\"severity\":9}", qe));
        long m = id(send(post(F + "/" + fmea + "/fails"), "{\"funcId\":" + fs + ",\"failText\":\"강도 부족\"}", qe));
        long c = id(send(post(F + "/" + fmea + "/fails"), "{\"funcId\":" + fe + ",\"failText\":\"전류 저하\"}", qe));
        id(send(post(F + "/" + fmea + "/chains"), "{\"fmId\":" + m + ",\"feId\":" + e + "}", qe));
        id(send(post(F + "/" + fmea + "/risks"), "{\"fmId\":" + m + ",\"fcId\":" + c + ",\"detCtrl\":\"파괴 시험\",\"occ\":3,\"det\":4}", qe));
        return fmea;
    }

    private void release(String url) throws Exception {
        send(post(url + "/release"), "{\"rowVer\":" + ver(url, url.startsWith(F) ? "$.data.fmea.rowVer" : "$.data.cp.rowVer") + "}", qe)
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ A4

    private XSSFWorkbook excel(String url, RequestPostProcessor who) throws Exception {
        byte[] b = mvc.perform(post(url + "/excel").with(csrf()).with(who)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/vnd.openxmlformats")))
                .andReturn().getResponse().getContentAsByteArray();
        return new XSSFWorkbook(new ByteArrayInputStream(b));
    }

    private static String fileName(ResultActions r) {
        return r.andReturn().getResponse().getHeader("Content-Disposition");
    }

    /** 시트 → 행 번호마다 칸 글자(빈 행·칸은 비움 — 목록 위치 = 행 번호) */
    private static List<List<String>> cells(Sheet sh) {
        List<List<String>> out = new ArrayList<>();
        for (int n = 0; n <= sh.getLastRowNum(); n++) {
            Row row = sh.getRow(n);
            List<String> cs = new ArrayList<>();
            if (row == null) {
                out.add(cs);
                continue;
            }
            for (int i = 0; i < row.getLastCellNum(); i++) {
                Cell c = row.getCell(i);
                cs.add(c == null ? "" : switch (c.getCellType()) {
                    case NUMERIC -> String.valueOf((long) c.getNumericCellValue());
                    case STRING -> c.getStringCellValue();
                    default -> "";
                });
            }
            out.add(cs);
        }
        return out;
    }

    private static List<String> rowStarting(List<List<String>> rows, String first) {
        return rows.stream().filter(r -> !r.isEmpty() && r.get(0).equals(first)).findFirst().orElseThrow();
    }

    @Test
    void PFMEA_양식_머리_위험표_조치표_확정본아님_표시_인쇄설정() throws Exception {
        long fmea = draftFmea();
        String url = F + "/" + fmea;
        String fmeaNo = read(url, "$.data.fmea.fmeaNo", qe);
        // 작성 중 → "확정본 아님" + 파일명에 상태
        ResultActions r0 = mvc.perform(post(url + "/excel").with(csrf()).with(qe)).andExpect(status().isOk());
        assertThat(fileName(r0)).contains(fmeaNo + "_rev1_DRAFT.xlsx");
        try (XSSFWorkbook wb = excel(url, qe)) {
            List<List<String>> rows = cells(wb.getSheetAt(0));
            assertThat(rows.get(1).get(0)).startsWith("확정본 아님");
        }
        // 조치 하나(수식처럼 보이는 글 — 수식 주입 방지) → 확정
        long risk = ((Number) read(url, "$.data.risks[0].riskId", qe)).longValue();
        id(send(post(url + "/actions"), "{\"riskId\":" + risk + ",\"actionKind\":\"DETECT\",\"actionText\":\"=HYPERLINK(\\\"x\\\")\","
                + "\"ownerLoginId\":\"qe1\",\"dueDate\":\"" + today.plusDays(10) + "\"}", qe));
        release(url);
        ResultActions r1 = mvc.perform(post(url + "/excel").with(csrf()).with(qe)).andExpect(status().isOk());
        assertThat(fileName(r1)).contains(fmeaNo + "_rev1.xlsx").doesNotContain("RELEASED");
        try (XSSFWorkbook wb = excel(url, qe)) {
            // 위험 분석 · 조치 · 공정 흐름(A5-1 D3 — docs/41 13장)
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheetName(2)).isEqualTo("공정 흐름");
            Sheet risks = wb.getSheetAt(0);
            assertThat(wb.getSheetName(0)).isEqualTo("위험 분석");
            List<List<String>> rows = cells(risks);
            assertThat(rows.get(0).get(0)).isEqualTo("공정 FMEA (PFMEA)");
            assertThat(rows.stream().noneMatch(x -> !x.isEmpty() && x.get(0).startsWith("확정본 아님"))).isTrue();
            assertThat(rowStarting(rows, "번호·리비전").get(1)).isEqualTo(fmeaNo + " rev 1");
            assertThat(rowStarting(rows, "품목").get(1)).isEqualTo("ZT-BRKT ZT 브래킷");
            assertThat(rowStarting(rows, "확정일").get(1)).isEqualTo(today.toString());
            List<String> head = rowStarting(rows, "행");
            assertThat(head).containsExactly("행", "공정 단계", "공정 기능(특성)", "고장 영향(FE)", "S", "고장 형태(FM)", "작업요소(4M)",
                    "고장 원인(FC)", "예방 관리", "O", "검출 관리", "D", "AP", "특별특성");
            List<String> line = rowStarting(rows, "1");
            assertThat(line).containsExactly("1", "10 ZT 용접", "용접\n[ZP1 ZT 용접 전류]", "이탈 (S9)", "9", "강도 부족", "ZT 용접기 (설비)",
                    "전류 저하", "", "3", "파괴 시험", "4", "H", "");
            // AP 칸 색(H), 인쇄 설정(가로·A3·머리 행 반복·바닥글)
            int headRow = rows.indexOf(head);
            assertThat(risks.getRow(headRow + 1).getCell(12).getCellStyle().getFillForegroundColor()).isNotEqualTo((short) 64);
            assertThat(risks.getPrintSetup().getLandscape()).isTrue();
            assertThat(risks.getPrintSetup().getPaperSize()).isEqualTo(org.apache.poi.ss.usermodel.PrintSetup.A3_PAPERSIZE);
            assertThat(risks.getRepeatingRows().getFirstRow()).isEqualTo(headRow);
            assertThat(risks.getFooter().getLeft()).isEqualTo(fmeaNo + " rev 1");
            // 조치 표: 위험행 번호·구분 이름·담당, 수식 주입 방지
            List<List<String>> acts = cells(wb.getSheetAt(1));
            List<String> a = rowStarting(acts, "1");
            assertThat(a.subList(0, 4)).containsExactly("1", "검출", "'=HYPERLINK(\"x\")", "qe1 사용자");
            assertThat(wb.getSheetAt(1).getRow(acts.indexOf(a)).getCell(2).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
        }
        // 영어 사용자면 칸 제목도 영어
        try (XSSFWorkbook wb = excel(url, accounts.as(comp, qeId, "qe1", "en"))) {
            assertThat(cells(wb.getSheetAt(0)).get(0).get(0)).isEqualTo("Process FMEA (PFMEA)");
        }
    }

    @Test
    void 관리계획서_양식_행_규격_코드명_권한_상한_A사로B사() throws Exception {
        long fmea = draftFmea();
        release(F + "/" + fmea);
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PRE_LAUNCH\",\"title\":\"ZT 관리계획서\","
                + "\"pullFromFmea\":true}", qe));
        String cu = CP + "/" + cp;
        long line = ((Number) read(cu, "$.data.lines[0].lineId", qe)).longValue();
        long risk = ((Number) read(cu, "$.data.lines[0].riskId", qe)).longValue();
        send(put("/api/apqp/cp-lines/" + line), "{\"stepId\":" + step10 + ",\"charId\":" + weldChar + ",\"riskId\":" + risk
                + ",\"ctrlMethod\":\"INSPECT\",\"freqType\":\"EVERY_LOT\","
                + "\"evalMethod\":\"육안\",\"sampleN\":5,\"reactionPlan\":\"보류\",\"rowVer\":" + ver(cu, "$.data.lines[0].rowVer") + "}", qe)
                .andExpect(status().isOk());
        release(cu);
        String cpNo = read(cu, "$.data.cp.cpNo", qe);
        try (XSSFWorkbook wb = excel(cu, qe)) {
            List<List<String>> rows = cells(wb.getSheetAt(0));
            assertThat(rows.get(0).get(0)).startsWith("관리계획서 — ");
            assertThat(rowStarting(rows, "번호·리비전").get(1)).isEqualTo(cpNo + " rev 1");
            assertThat(rowStarting(rows, "근거 PFMEA").get(1)).endsWith(" rev 1");
            List<String> head = rowStarting(rows, "행");
            assertThat(head).containsExactly("행", "공정 단계", "특성", "특별특성", "규격", "관리 방법", "평가·측정 방법", "계측기", "샘플", "주기",
                    "반응계획", "근거 AP");
            List<String> l = rowStarting(rows, "1");
            assertThat(l.subList(0, 3)).containsExactly("1", "10 ZT 용접", "ZP1 ZT 용접 전류");
            assertThat(l.get(5)).isNotEqualTo("INSPECT");     // 코드명
            assertThat(l.get(6)).isEqualTo("육안");
            assertThat(l.get(8)).isEqualTo("5");
            assertThat(l.get(10)).isEqualTo("보류");
            assertThat(l.get(11)).isEqualTo("H");
        }
        // PRINT 없음(SEARCH 만) → 403, 화면 동작에 PRINT 가 있다
        long viewer = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewer, "APQPV", "apqp-fmea", "SEARCH");
        accounts.grant(comp, viewer, "APQPV", "apqp-cp", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "view1");
        mvc.perform(get(cu).with(v)).andExpect(status().isOk());
        mvc.perform(post(cu + "/excel").with(csrf()).with(v)).andExpect(status().isForbidden());
        mvc.perform(post(F + "/" + fmea + "/excel").with(csrf()).with(v)).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select array_to_string(actions, ',') from sys_prog where prog_cd = 'apqp-cp'", String.class))
                .contains("PRINT");
        // 행 상한(공통 정책, 최소 100) — 새 리비전(작성 중)에 위험행 1 + 조치 100 = 101 > 100 이면 거부
        long r2 = id(send(post(F + "/" + fmea + "/revise"), "", qe));
        accounts.exec(comp, """
                insert into apqp_fmea_action (comp_cd, fmea_id, risk_id, action_kind, action_text, created_by, updated_by)
                select ?, ?, (select risk_id from apqp_fmea_risk where fmea_id = ?), 'PREVENT', 'ZT 조치 ' || g, 't', 't'
                from generate_series(1, 100) g""", comp, r2, r2);
        policy("EXCEL_EXPORT_MAX_ROWS", "100");
        mvc.perform(post(F + "/" + r2 + "/excel").with(csrf()).with(qe)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));
        mvc.perform(post(cu + "/excel").with(csrf()).with(qe)).andExpect(status().isOk());
        // A사로 B사: B사 사용자(PRINT 있음)로 A사 id → 404, 없는 id 404
        String other = accounts.newComp("A7");
        long bu = accounts.newUser(other, "qe1", PW);
        grantAll(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "qe1");
        mvc.perform(post(cu + "/excel").with(csrf()).with(b)).andExpect(status().isNotFound());
        mvc.perform(post(F + "/" + fmea + "/excel").with(csrf()).with(b)).andExpect(status().isNotFound());
        mvc.perform(post(CP + "/999999999/excel").with(csrf()).with(qe)).andExpect(status().isNotFound());
    }
}
