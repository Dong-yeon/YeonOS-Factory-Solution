package io.smarthappy.module.apqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
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
 * 공정 흐름도 PFD (docs/41 13장 A5-1, D1~D3) — 저장 없이 라우팅 단계 순서·우리 기호 4종(가공·검사·외주·포장)·표준 CT,
 * 단계 특성(PFMEA 기능 ∪ 관리계획서 행, 특별특성은 마스터), 관리계획서 행·위험행·AP 높음 수가 원본과 같음, 원본이 바뀌면 바로 따라감,
 * 관리계획서 권한 없으면 숨김, PFMEA 엑셀의 "공정 흐름" 시트, 권한·A사로 B사.
 */
@AutoConfigureMockMvc
class ApqpFlowTest extends PostgresTestBase {

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
    RequestPostProcessor qe;
    long routing;
    long step10;
    long weldChar;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AF");
        long qeId = accounts.newUser(comp, "qe1", PW);
        accounts.grant(comp, qeId, "APQP", "apqp-fmea", "SEARCH", "SAVE", "APPROVE", "PRINT");
        accounts.grant(comp, qeId, "APQP", "apqp-ap", "SEARCH", "SAVE");
        accounts.grant(comp, qeId, "APQP", "apqp-cp", "SEARCH", "SAVE", "APPROVE", "PRINT");
        qe = accounts.as(comp, qeId, "qe1");
        fixture(comp);
    }

    /** 품목 ZT-BRKT · 라우팅 10 용접(CT 45.5) / 20 검사 / 30 외주 가공 / 40 포장 · 용접 공정 특성(특별특성 CC) */
    private void fixture(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'ZT-BRKT', 'ZT 브래킷', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, outsrc_yn, created_by, updated_by) values
                (?, 'ZWELD', 'ZT 용접', 'WELD', 'N', 't', 't'), (?, 'ZINSP', 'ZT 검사', 'INSPECT', 'N', 't', 't'),
                (?, 'ZPLATE', 'ZT 도금', 'OTHER', 'Y', 't', 't'), (?, 'ZPACK', 'ZT 포장', 'PACK', 'N', 't', 't')""", c, c, c, c);
        routing = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'ZT-BRKT'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't')
                returning routing_id""", Long.class, c);
        step10 = accounts.query(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ZWELD'), 45.5, 't', 't') returning step_id""", Long.class, c, routing);
        int op = 20;
        for (String p : List.of("ZINSP", "ZPLATE", "ZPACK")) {
            accounts.exec(c, """
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                    values (?, ?, ?, (select proc_id from mst_proc where proc_cd = ?), 't', 't')""", c, routing, op, p);
            op += 10;
        }
        weldChar = accounts.query(c, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, special_cls, created_by, updated_by)
                values (?, 'PROCESS', (select proc_id from mst_proc where proc_cd = 'ZWELD'), 'ZP1', 'ZT 용접 전류', 'ATTRIBUTE', 'CC', 't', 't')
                returning char_id""", Long.class, c);
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

    private Map<String, Object> flow(long fmea, RequestPostProcessor who) throws Exception {
        return read(F + "/" + fmea + "/flow", "$.data", who);
    }

    /** 단계에서 FROM 루트 구조 → STEP 10 기능(용접 특성)·고장(강도 부족) × 작업요소 고장, 고장 영향 S9 → AP H 위험행 1 */
    private long draftFmea() throws Exception {
        long set = id(send(post("/api/apqp/ap-sets"), "{\"setCd\":\"ZT-EX\",\"setNm\":\"ZT 예시(핸드북 아님)\",\"useYn\":\"Y\",\"rules\":" + RULES + "}", qe));
        long fmea = id(send(post(F), "{\"itemCd\":\"ZT-BRKT\",\"routingId\":" + routing + ",\"title\":\"ZT PFMEA\",\"apSetId\":" + set
                + ",\"fromRouting\":true}", qe));
        List<Map<String, Object>> nodes = read(F + "/" + fmea, "$.data.nodes", qe);
        long item = nodes.stream().filter(n -> "ITEM".equals(n.get("nodeKind"))).map(n -> ((Number) n.get("nodeId")).longValue()).findFirst().orElseThrow();
        long step = nodes.stream().filter(n -> "STEP".equals(n.get("nodeKind")) && Integer.valueOf(10).equals(n.get("opSeq")))
                .map(n -> ((Number) n.get("nodeId")).longValue()).findFirst().orElseThrow();
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

    private void release(String url, String verPath) throws Exception {
        int ver = ((Number) read(url, verPath, qe)).intValue();
        send(post(url + "/release"), "{\"rowVer\":" + ver + "}", qe).andExpect(status().isOk());
    }

    @Test
    void 단계_순서_기호_CT_특성_특별특성_위험행이_원본과_같고_관리계획서를_붙이면_행수와_출처() throws Exception {
        long fmea = draftFmea();
        Map<String, Object> v = flow(fmea, qe);
        assertThat(JsonPath.<List<Object>>read(v, "$.steps[*].opSeq")).containsExactly(10, 20, 30, 40);
        assertThat(JsonPath.<List<Object>>read(v, "$.steps[*].symbol")).containsExactly("PROCESS", "INSPECT", "OUTSOURCE", "PACK");
        assertThat(JsonPath.<Number>read(v, "$.steps[0].stdCtSec").doubleValue()).isEqualTo(45.5);
        assertThat(JsonPath.<List<Object>>read(v, "$.steps[*].riskCnt")).containsExactly(1, 0, 0, 0);
        assertThat(JsonPath.<List<Object>>read(v, "$.steps[*].highCnt")).containsExactly(1, 0, 0, 0);
        Map<String, Object> ch = JsonPath.read(v, "$.steps[0].chars[0]");
        assertThat(ch).containsEntry("charNo", "ZP1").containsEntry("special", true).containsEntry("specialCls", "CC")
                .containsEntry("inFmea", true).containsEntry("inCp", false);
        assertThat(v).containsEntry("cpId", null).containsEntry("cpHidden", false).containsEntry("unplaced", 0);
        // 관리계획서(확정 PFMEA 에서 끌어옴) → 단계 10 행 1, 특성 출처 둘 다
        release(F + "/" + fmea, "$.data.fmea.rowVer");
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PRE_LAUNCH\",\"title\":\"ZT CP\",\"pullFromFmea\":true}", qe));
        String cu = CP + "/" + cp;
        long line = ((Number) read(cu, "$.data.lines[0].lineId", qe)).longValue();
        long risk = ((Number) read(cu, "$.data.lines[0].riskId", qe)).longValue();
        int lv = ((Number) read(cu, "$.data.lines[0].rowVer", qe)).intValue();
        send(put("/api/apqp/cp-lines/" + line), "{\"stepId\":" + step10 + ",\"charId\":" + weldChar + ",\"riskId\":" + risk
                + ",\"ctrlMethod\":\"INSPECT\",\"freqType\":\"EVERY_LOT\",\"evalMethod\":\"육안\",\"sampleN\":5,\"reactionPlan\":\"보류\",\"rowVer\":"
                + lv + "}", qe).andExpect(status().isOk());
        v = flow(fmea, qe);
        assertThat(((Number) v.get("cpId")).longValue()).isEqualTo(cp);
        assertThat(JsonPath.<List<Object>>read(v, "$.steps[*].cpLineCnt")).containsExactly(1, 0, 0, 0);
        assertThat(JsonPath.<Map<String, Object>>read(v, "$.steps[0].chars[0]")).containsEntry("inFmea", true).containsEntry("inCp", true);
        // 관리계획서 화면 권한이 없는 사람 — 있다는 것만
        long viewId = accounts.newUser(comp, "fm1", PW);
        accounts.grant(comp, viewId, "FMV", "apqp-fmea", "SEARCH");
        Map<String, Object> hidden = flow(fmea, accounts.as(comp, viewId, "fm1"));
        assertThat(hidden).containsEntry("cpId", null).containsEntry("cpHidden", true);
        assertThat(JsonPath.<List<Object>>read(hidden, "$.steps[*].cpLineCnt")).containsExactly(0, 0, 0, 0);
    }

    @Test
    void 원본이_바뀌면_바로_따라감_단계추가_외주전환_특별특성_해제() throws Exception {
        long fmea = draftFmea();
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 15, (select proc_id from mst_proc where proc_cd = 'ZINSP'), 't', 't')""", comp, routing);
        accounts.exec(comp, "update mst_proc set outsrc_yn = 'Y' where proc_cd = 'ZWELD'");
        accounts.exec(comp, "update qms_char set special_cls = 'NONE' where char_id = ?", weldChar);
        Map<String, Object> v = flow(fmea, qe);
        assertThat(JsonPath.<List<Object>>read(v, "$.steps[*].opSeq")).containsExactly(10, 15, 20, 30, 40);
        assertThat(JsonPath.<List<Object>>read(v, "$.steps[*].symbol")).containsExactly("OUTSOURCE", "INSPECT", "INSPECT", "OUTSOURCE", "PACK");
        assertThat(JsonPath.<Map<String, Object>>read(v, "$.steps[0].chars[0]")).containsEntry("special", false);
        // 라우팅 없는 PFMEA — 단계 없음, 위험행 0
        long bare = id(send(post(F), "{\"itemCd\":\"ZT-BRKT\",\"title\":\"라우팅 없음\"}", qe));
        assertThat(JsonPath.<List<Object>>read(flow(bare, qe), "$.steps")).isEmpty();
    }

    @Test
    void PFMEA_엑셀에_공정흐름_시트_화면과_같은_값() throws Exception {
        long fmea = draftFmea();
        byte[] b = mvc.perform(post(F + "/" + fmea + "/excel").with(csrf()).with(qe)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(b))) {
            Sheet sh = wb.getSheet("공정 흐름");
            assertThat(sh).isNotNull();
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sh) {
                List<String> cs = new ArrayList<>();
                for (int i = 0; i < row.getLastCellNum(); i++) {
                    Cell c = row.getCell(i);
                    cs.add(c == null ? "" : switch (c.getCellType()) {
                        case NUMERIC -> String.valueOf((long) c.getNumericCellValue());
                        case STRING -> c.getStringCellValue();
                        default -> "";
                    });
                }
                rows.add(cs);
            }
            List<String> head = rows.stream().filter(r -> !r.isEmpty() && r.get(0).equals("공정 순서")).findFirst().orElseThrow();
            assertThat(head).containsExactly("공정 순서", "공정", "구분", "표준 CT(초)", "특성", "관리계획서 행", "위험행", "AP 높음");
            List<String> r10 = rows.stream().filter(r -> !r.isEmpty() && r.get(0).equals("10")).findFirst().orElseThrow();
            assertThat(r10).containsExactly("10", "ZWELD ZT 용접", "가공", "45.5", "ZP1 ZT 용접 전류 [CC(안전·법규)]", "", "1", "1");   // 특별특성 = 공통코드 이름
            List<String> r30 = rows.stream().filter(r -> !r.isEmpty() && r.get(0).equals("30")).findFirst().orElseThrow();
            assertThat(r30.subList(0, 3)).containsExactly("30", "ZPLATE ZT 도금", "외주");
        }
    }

    @Test
    void 권한과_A사로B사() throws Exception {
        long fmea = draftFmea();
        long noneId = accounts.newUser(comp, "cp1", PW);
        accounts.grant(comp, noneId, "CPO", "apqp-cp", "SEARCH");
        mvc.perform(get(F + "/" + fmea + "/flow").with(accounts.as(comp, noneId, "cp1"))).andExpect(status().isForbidden());
        String compB = accounts.newComp("AG");
        long bId = accounts.newUser(compB, "qe1", PW);
        accounts.grant(compB, bId, "APQP", "apqp-fmea", "SEARCH", "PRINT");
        accounts.grant(compB, bId, "APQP", "apqp-cp", "SEARCH");
        RequestPostProcessor b = accounts.as(compB, bId, "qe1");
        mvc.perform(get(F + "/" + fmea + "/flow").with(b)).andExpect(status().isNotFound());
        mvc.perform(post(F + "/" + fmea + "/excel").with(csrf()).with(b)).andExpect(status().isNotFound());
    }
}
