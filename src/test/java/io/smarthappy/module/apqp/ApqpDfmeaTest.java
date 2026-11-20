package io.smarthappy.module.apqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * DFMEA (docs/41 13장 A5-2a) — 같은 표·같은 서비스, 화면 apqp-dfmea.
 * BOM 1~2단계로 시스템 › 하위 시스템 › 구성품 시작(자품목 연결), 도면 리비전(QMS DRAWING), 제품 특성만(머리 품목·노드 자품목),
 * 기능·고장·사슬·위험행·확정·새 리비전·엑셀 재사용, 관리계획서 근거는 PFMEA 만, 화면·경로 권한 분리(유형 다르면 404), 트리거 직접 SQL, A사로 B사.
 */
@AutoConfigureMockMvc
class ApqpDfmeaTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String D = "/api/apqp/dfmeas";
    private static final String F = "/api/apqp/fmeas";
    private static final String RULES = """
            [{"sevFrom":9,"sevTo":10,"occFrom":1,"occTo":10,"detFrom":1,"detTo":10,"ap":"H"},
             {"sevFrom":1,"sevTo":8,"occFrom":6,"occTo":10,"detFrom":1,"detTo":10,"ap":"M"},
             {"sevFrom":1,"sevTo":8,"occFrom":1,"occTo":5,"detFrom":1,"detTo":10,"ap":"L"}]""";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor de;
    Fx fx;

    /** 회사 하나의 기준 데이터 — 품목·BOM·특성·도면 */
    record Fx(long assy, long sub1, long sub2, long part, long bom, long otherBom, long assyChar, long partChar, long procChar,
            long drawingRev, long wiRev, long routing) {
    }

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AD");
        long deId = accounts.newUser(comp, "de1", PW);
        accounts.grant(comp, deId, "DESIGN", "apqp-dfmea", "SEARCH", "SAVE", "APPROVE", "PRINT", "EXCEL");
        accounts.grant(comp, deId, "DESIGN", "apqp-ap", "SEARCH", "SAVE");
        accounts.grant(comp, deId, "DESIGN", "apqp-cp", "SEARCH", "SAVE");
        de = accounts.as(comp, deId, "de1");
        fx = fixture(comp);
    }

    private long q(String c, String sql, Object... args) {
        return accounts.query(c, sql, Long.class, args);
    }

    /**
     * ZT-ASSY = ZT-SUB1 + ZT-SUB2(확정 BOM A), ZT-SUB1 = ZT-PART(확정 BOM A) · 제품 특성 ZD1(ASSY)·ZD2(PART)·공정 특성 ZP1 ·
     * 도면 ZT-DWG(DRAWING, rev A 작성 중 — 리비전 상태는 고르는 조건 아님)·작업표준 ZT-WI(WI) · ZT-SUB2 의 BOM(다른 품목 BOM) · 라우팅(DFMEA 에 붙이면 안 됨)
     */
    private Fx fixture(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by) values
                (?, 'ZT-ASSY', 'ZT 조립품', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'ZT-SUB1', 'ZT 하우징', 'SFG', 'MAKE', 'EA', 't', 't'),
                (?, 'ZT-SUB2', 'ZT 커버', 'SFG', 'MAKE', 'EA', 't', 't'), (?, 'ZT-PART', 'ZT 볼트', 'RM', 'BUY', 'EA', 't', 't')""", c, c, c, c);
        long assy = q(c, "select item_id from mst_item where item_cd = 'ZT-ASSY'");
        long sub1 = q(c, "select item_id from mst_item where item_cd = 'ZT-SUB1'");
        long sub2 = q(c, "select item_id from mst_item where item_cd = 'ZT-SUB2'");
        long part = q(c, "select item_id from mst_item where item_cd = 'ZT-PART'");
        long bom = bom(c, assy, sub1, sub2);
        bom(c, sub1, part);
        long otherBom = bom(c, sub2, part);
        accounts.exec(c, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, outsrc_yn, created_by, updated_by) values (?, 'ZWELD', 'ZT 용접', 'WELD', 'N', 't', 't')", c);
        long assyChar = q(c, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, special_cls, created_by, updated_by)
                values (?, 'PRODUCT', ?, 'ZD1', 'ZT 조립 토크', 'ATTRIBUTE', 'CC', 't', 't') returning char_id""", c, assy);
        long partChar = q(c, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, created_by, updated_by)
                values (?, 'PRODUCT', ?, 'ZD2', 'ZT 볼트 경도', 'ATTRIBUTE', 't', 't') returning char_id""", c, part);
        long procChar = q(c, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, created_by, updated_by)
                values (?, 'PROCESS', (select proc_id from mst_proc where proc_cd = 'ZWELD'), 'ZP1', 'ZT 전류', 'ATTRIBUTE', 't', 't')
                returning char_id""", c);
        long drawingRev = docRev(c, "ZT-DWG", "DRAWING");
        long wiRev = docRev(c, "ZT-WI", "WI");
        long routing = q(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, ?, 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't') returning routing_id""", c, assy);
        return new Fx(assy, sub1, sub2, part, bom, otherBom, assyChar, partChar, procChar, drawingRev, wiRev, routing);
    }

    /** 확정 BOM 리비전 A(어제부터) — 줄은 작성 중에 넣고 확정 */
    private long bom(String c, long parent, long... children) {
        long b = q(c, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, ?, 'A', (now() at time zone 'Asia/Seoul')::date - 1, 't', 't') returning bom_id""", c, parent);
        int no = 0;
        for (long ch : children) {
            no += 10;
            accounts.exec(c, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                    values (?, ?, ?, ?, 1, 'EA', 'MANUAL', 't', 't')""", c, b, no, ch);
        }
        accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", b);
        return b;
    }

    private long docRev(String c, String no, String type) {
        long doc = q(c, "insert into mst_doc (comp_cd, doc_no, doc_type, doc_nm, created_by, updated_by) values (?, ?, ?, ?, 't', 't') returning doc_id",
                c, no, type, no + " 문서");
        return q(c, """
                insert into mst_doc_rev (comp_cd, doc_id, rev, created_by, updated_by)
                values (?, ?, 'A', 't', 't') returning doc_rev_id""", c, doc);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private long id(ResultActions r) throws Exception {
        String s = r.andReturn().getResponse().getContentAsString();
        assertThat(r.andReturn().getResponse().getStatus()).as(s).isEqualTo(200);
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private <T> T read(String url, String path, RequestPostProcessor who) throws Exception {
        return JsonPath.read(mvc.perform(get(url).with(who)).andReturn().getResponse().getContentAsString(), path);
    }

    private long apSet(RequestPostProcessor who) throws Exception {
        return id(send(post("/api/apqp/ap-sets"), "{\"setCd\":\"ZT-EX\",\"setNm\":\"ZT 예시(핸드북 아님)\",\"useYn\":\"Y\",\"rules\":" + RULES + "}", who));
    }

    private long create(long set) throws Exception {
        return id(send(post(D), "{\"itemCd\":\"ZT-ASSY\",\"bomId\":" + fx.bom() + ",\"docRevId\":" + fx.drawingRev()
                + ",\"title\":\"ZT DFMEA\",\"apSetId\":" + set + ",\"fromBom\":true}", de));
    }

    private List<Map<String, Object>> nodes(long fmea) throws Exception {
        return read(D + "/" + fmea, "$.data.nodes", de);
    }

    private static long node(List<Map<String, Object>> nodes, String kind, String refItemCd) {
        return nodes.stream().filter(n -> kind.equals(n.get("nodeKind")) && (refItemCd == null || refItemCd.equals(n.get("refItemCd"))))
                .map(n -> ((Number) n.get("nodeId")).longValue()).findFirst().orElseThrow();
    }

    /** SYSTEM 기능(ZD1)·영향 S9 / SUB1 기능·형태 / PART 구성품 기능(ZD2)·원인 → 사슬·위험행(AP H) */
    private long riskReady(long fmea) throws Exception {
        List<Map<String, Object>> ns = nodes(fmea);
        String u = D + "/" + fmea;
        long fs = id(send(post(u + "/funcs"), "{\"nodeId\":" + node(ns, "SYSTEM", null) + ",\"funcText\":\"체결 유지\",\"charId\":" + fx.assyChar() + "}", de));
        long fm = id(send(post(u + "/funcs"), "{\"nodeId\":" + node(ns, "SUBSYSTEM", "ZT-SUB1") + ",\"funcText\":\"볼트 고정\"}", de));
        long fc = id(send(post(u + "/funcs"), "{\"nodeId\":" + node(ns, "COMPONENT", "ZT-PART") + ",\"funcText\":\"경도 유지\",\"charId\":"
                + fx.partChar() + "}", de));
        long e = id(send(post(u + "/fails"), "{\"funcId\":" + fs + ",\"failText\":\"풀림\",\"severity\":9}", de));
        long m = id(send(post(u + "/fails"), "{\"funcId\":" + fm + ",\"failText\":\"체결력 부족\"}", de));
        long c = id(send(post(u + "/fails"), "{\"funcId\":" + fc + ",\"failText\":\"경도 미달\"}", de));
        id(send(post(u + "/chains"), "{\"fmId\":" + m + ",\"feId\":" + e + "}", de));
        return id(send(post(u + "/risks"), "{\"fmId\":" + m + ",\"fcId\":" + c + ",\"prevCtrl\":\"재질 규격\",\"detCtrl\":\"경도 시험\",\"occ\":3,\"det\":4}", de));
    }

    private static List<List<String>> cells(Sheet sh) {
        List<List<String>> out = new ArrayList<>();
        for (Row r : sh) {
            List<String> row = new ArrayList<>();
            for (Cell c : r) {
                row.add(c.toString());
            }
            out.add(row);
        }
        return out;
    }

    @Test
    void BOM_1_2단계로_시스템_하위시스템_구성품_시작_자품목_연결_도면_리비전() throws Exception {
        long fmea = create(apSet(de));
        Map<String, Object> f = read(D + "/" + fmea, "$.data.fmea", de);
        assertThat(f.get("fmeaType")).isEqualTo("DFMEA");
        assertThat((String) f.get("fmeaNo")).startsWith("DF-");
        assertThat(f.get("routingId")).isNull();
        assertThat(((Number) f.get("bomId")).longValue()).isEqualTo(fx.bom());
        assertThat((String) f.get("docRevText")).startsWith("ZT-DWG rev A");
        List<Map<String, Object>> ns = nodes(fmea);
        assertThat(ns).extracting(n -> n.get("nodeKind") + ":" + n.get("refItemCd"))
                .containsExactlyInAnyOrder("SYSTEM:null", "SUBSYSTEM:ZT-SUB1", "SUBSYSTEM:ZT-SUB2", "COMPONENT:ZT-PART", "COMPONENT:ZT-PART");
        // ZT-SUB2 에도 자기 BOM(otherBom = ZT-PART)이 있어 구성품 하나씩
        long sub1 = node(ns, "SUBSYSTEM", "ZT-SUB1");
        assertThat(ns.stream().filter(n -> "COMPONENT".equals(n.get("nodeKind")) && ((Number) n.get("parentId")).longValue() == sub1)).hasSize(1);
        // 손으로 더하기: 하위 시스템은 시스템 아래, 구성품은 하위 시스템 아래 / PFMEA 종류는 안 됨
        String u = D + "/" + fmea + "/nodes";
        long root = node(ns, "SYSTEM", null);
        id(send(post(u), "{\"parentId\":" + root + ",\"nodeKind\":\"SUBSYSTEM\",\"nodeNm\":\"ZT 배선\"}", de));
        send(post(u), "{\"parentId\":" + root + ",\"nodeKind\":\"STEP\",\"nodeNm\":\"x\"}", de).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        send(post(u), "{\"parentId\":" + root + ",\"nodeKind\":\"COMPONENT\",\"nodeNm\":\"x\"}", de)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_LEVEL"));
        // 뿌리 삭제 불가, BOM 자품목을 가리키는 구조가 있으면 BOM 못 바꿈
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/apqp/dfmea-nodes/" + root + "?rowVer=0")
                .with(csrf()).with(de)).andExpect(jsonPath("$.errors[0].code").value("APQP_LEVEL"));
        int ver = ((Number) f.get("rowVer")).intValue();
        send(put(D + "/" + fmea), "{\"itemCd\":\"ZT-ASSY\",\"title\":\"ZT DFMEA\",\"rowVer\":" + ver + "}", de)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_BOM_USED"));
        // 고르기 목록: BOM·도면 리비전
        assertThat((List<?>) read("/api/apqp/dfmea/items/" + fx.assy() + "/boms", "$.data", de)).hasSize(1);
        assertThat((List<?>) read("/api/apqp/dfmea/drawing-revs?docNo=zt-dwg", "$.data", de)).hasSize(1);
    }

    @Test
    void 제품특성만_머리품목과_노드_자품목_것() throws Exception {
        long fmea = create(apSet(de));
        List<Map<String, Object>> chars = read(D + "/" + fmea + "/chars", "$.data", de);
        assertThat(chars).extracting(c -> c.get("charNo")).containsExactlyInAnyOrder("ZD1", "ZD2");
        List<Map<String, Object>> ns = nodes(fmea);
        String u = D + "/" + fmea + "/funcs";
        // 볼트 특성을 커버(하위 시스템, 자품목 SUB2)에 / 공정 특성 → 막음, 구성품(PART)에는 됨
        send(post(u), "{\"nodeId\":" + node(ns, "SUBSYSTEM", "ZT-SUB2") + ",\"funcText\":\"x\",\"charId\":" + fx.partChar() + "}", de)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_CHAR_TARGET"));
        send(post(u), "{\"nodeId\":" + node(ns, "SYSTEM", null) + ",\"funcText\":\"x\",\"charId\":" + fx.procChar() + "}", de)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_CHAR_TARGET"));
        id(send(post(u), "{\"nodeId\":" + node(ns, "COMPONENT", "ZT-PART") + ",\"funcText\":\"경도\",\"charId\":" + fx.partChar() + "}", de));
        // 직접 SQL — 트리거가 최종 판정
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_fmea_func (comp_cd, fmea_id, node_id, seq, func_text, char_id, created_by, updated_by)
                values (?, ?, ?, 9, 'x', ?, 't', 't')""", comp, fmea, node(ns, "SUBSYSTEM", "ZT-SUB2"), fx.partChar())))
                .hasMessageContaining("does not belong to this item/component");
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_fmea_func (comp_cd, fmea_id, node_id, seq, func_text, char_id, created_by, updated_by)
                values (?, ?, ?, 9, 'x', ?, 't', 't')""", comp, fmea, node(ns, "SYSTEM", null), fx.procChar())))
                .hasMessageContaining("does not belong to this item/component");
    }

    @Test
    void 위험행_확정_새리비전_엑셀_재사용_관리계획서_근거는_PFMEA만() throws Exception {
        long fmea = create(apSet(de));
        long risk = riskReady(fmea);
        assertThat((String) read(D + "/" + fmea, "$.data.risks[0].ap", de)).isEqualTo("H");
        assertThat(risk).isPositive();
        int ver = ((Number) read(D + "/" + fmea, "$.data.fmea.rowVer", de)).intValue();
        send(post(D + "/" + fmea + "/release"), "{\"rowVer\":" + ver + "}", de).andExpect(status().isOk());
        assertThat((String) read(D + "/" + fmea, "$.data.fmea.status", de)).isEqualTo("RELEASED");
        // 엑셀 — 같은 양식, DFMEA 글자, 공정 흐름 시트 없음
        byte[] x = mvc.perform(post(D + "/" + fmea + "/excel").with(csrf()).with(de)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            List<List<String>> rows = cells(wb.getSheetAt(0));
            assertThat(rows.get(0).get(0)).isEqualTo("설계 FMEA (DFMEA)");
            assertThat(rows.stream().filter(r -> !r.isEmpty() && "BOM".equals(r.get(0))).findFirst().orElseThrow().get(1)).isEqualTo("1 · A");
            List<String> head = rows.stream().filter(r -> !r.isEmpty() && "행".equals(r.get(0))).findFirst().orElseThrow();
            assertThat(head).contains("하위 시스템", "하위 시스템 기능(특성)", "구성품").doesNotContain("공정 단계", "작업요소(4M)");
        }
        // 새 리비전 — 자품목 연결 그대로
        long rev2 = id(send(post(D + "/" + fmea + "/revise"), "{}", de));
        assertThat(nodes(rev2)).extracting(n -> n.get("nodeKind") + ":" + n.get("refItemCd")).contains("SUBSYSTEM:ZT-SUB1", "COMPONENT:ZT-PART");
        assertThat((String) read(D + "/" + rev2, "$.data.fmea.fmeaNo", de)).isEqualTo(read(D + "/" + fmea, "$.data.fmea.fmeaNo", de));
        // 관리계획서: 확정 DFMEA 를 근거로 못 고름(API · 직접 SQL), 근거 후보에도 없음
        send(post("/api/apqp/cps"), "{\"itemCd\":\"ZT-ASSY\",\"fmeaId\":" + fmea + ",\"phase\":\"PROTO\",\"title\":\"x\"}", de)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_cp (comp_cd, cp_no, phase, item_id, fmea_id, title, created_by, updated_by)
                values (?, 'ZT-X', 'PROTO', ?, ?, 'x', 't', 't')""", comp, fx.assy(), fmea))).hasMessageContaining("non-PFMEA");
    }

    @Test
    void 대상_검사_라우팅_도면유형_다른품목_BOM_PFMEA에_BOM() throws Exception {
        long set = apSet(de);
        send(post(D), "{\"itemCd\":\"ZT-ASSY\",\"routingId\":" + fx.routing() + ",\"title\":\"x\",\"apSetId\":" + set + "}", de)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_DFMEA_NO_ROUTING"));
        send(post(D), "{\"itemCd\":\"ZT-ASSY\",\"bomId\":" + fx.otherBom() + ",\"title\":\"x\",\"apSetId\":" + set + "}", de)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        send(post(D), "{\"itemCd\":\"ZT-ASSY\",\"docRevId\":" + fx.wiRev() + ",\"title\":\"x\",\"apSetId\":" + set + "}", de)
                .andExpect(jsonPath("$.errors[0].field").value("docRevId")).andExpect(jsonPath("$.errors[0].code").value("APQP_NOT_DRAWING"));
        // PFMEA 에 BOM 은 안 붙는다(같은 사람이 PFMEA 권한도 가질 때)
        long deId = accounts.query(comp, "select user_id from sys_user where login_id = 'de1'", Long.class);
        accounts.grant(comp, deId, "DESIGN", "apqp-fmea", "SEARCH", "SAVE");
        send(post(F), "{\"itemCd\":\"ZT-ASSY\",\"bomId\":" + fx.bom() + ",\"title\":\"x\",\"apSetId\":" + set + "}", de)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_PFMEA_NO_BOM"));
        // 직접 SQL — 유형·대상·노드 종류
        long fmea = create(set);
        long pf = q(comp, """
                insert into apqp_fmea (comp_cd, fmea_no, rev, item_id, title, created_by, updated_by)
                values (?, 'ZT-PF', 1, ?, 'x', 't', 't') returning fmea_id""", comp, fx.assy());
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_fmea set fmea_type = 'PFMEA' where fmea_id = ?", fmea)))
                .hasMessageContaining("type is fixed");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_fmea set bom_id = ? where fmea_id = ?", fx.bom(), pf)))
                .hasMessageContaining("ck_apqp_fmea_target");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_fmea set routing_id = ? where fmea_id = ?", fx.routing(), fmea)))
                .hasMessageContaining("ck_apqp_fmea_target");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_fmea set doc_rev_id = ? where fmea_id = ?", fx.wiRev(), fmea)))
                .hasMessageContaining("not a drawing");
        long root = node(nodes(fmea), "SYSTEM", null);
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_fmea_node (comp_cd, fmea_id, parent_id, node_kind, node_nm, created_by, updated_by)
                values (?, ?, ?, 'STEP', 'x', 't', 't')""", comp, fmea, root))).hasMessageContaining("is not for DFMEA");
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_fmea_node (comp_cd, fmea_id, parent_id, node_kind, node_nm, created_by, updated_by)
                values (?, ?, ?, 'COMPONENT', 'x', 't', 't')""", comp, fmea, root))).hasMessageContaining("breaks the 3 levels");
        // FE 는 시스템, FM 은 하위 시스템에만
        long subFunc = id(send(post(D + "/" + fmea + "/funcs"), "{\"nodeId\":" + node(nodes(fmea), "SUBSYSTEM", "ZT-SUB1") + ",\"funcText\":\"x\"}", de));
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_fmea_fail (comp_cd, fmea_id, func_id, fail_kind, seq, fail_text, severity, created_by, updated_by)
                values (?, ?, ?, 'FE', 1, 'x', 5, 't', 't')""", comp, fmea, subFunc))).hasMessageContaining("does not belong to a SUBSYSTEM node");
    }

    @Test
    void 화면_권한_분리_유형이_다르면_404_목록도_유형별() throws Exception {
        long set = apSet(de);
        long dfmea = create(set);
        long sub = node(nodes(dfmea), "SUBSYSTEM", "ZT-SUB1");
        // PFMEA 만 가진 사람
        long peId = accounts.newUser(comp, "pe1", PW);
        accounts.grant(comp, peId, "PROC", "apqp-fmea", "SEARCH", "SAVE", "APPROVE", "PRINT");
        RequestPostProcessor pe = accounts.as(comp, peId, "pe1");
        mvc.perform(get(D + "/grid").with(pe)).andExpect(status().isForbidden());
        mvc.perform(get(F + "/" + dfmea).with(pe)).andExpect(status().isNotFound());
        mvc.perform(get(F + "/" + dfmea + "/flow").with(pe)).andExpect(status().isNotFound());
        mvc.perform(post(F + "/" + dfmea + "/excel").with(csrf()).with(pe)).andExpect(status().isNotFound());
        send(put("/api/apqp/fmea-nodes/" + sub), "{\"nodeNm\":\"바꿈\",\"rowVer\":0}", pe).andExpect(status().isNotFound());
        send(post(F + "/" + dfmea + "/funcs"), "{\"nodeId\":" + sub + ",\"funcText\":\"x\"}", pe).andExpect(status().isNotFound());
        assertThat((String) read(D + "/" + dfmea, "$.data.nodes[?(@.nodeId == " + sub + ")].nodeNm", de).toString()).doesNotContain("바꿈");
        // PFMEA 를 DFMEA 경로로
        long pfmea = id(send(post(F), "{\"itemCd\":\"ZT-ASSY\",\"title\":\"ZT PFMEA\",\"apSetId\":" + set + "}", pe));
        long pfRoot = node(read(F + "/" + pfmea, "$.data.nodes", pe), "ITEM", null);
        mvc.perform(get(D + "/" + pfmea).with(de)).andExpect(status().isNotFound());
        send(post(D + "/" + pfmea + "/funcs"), "{\"nodeId\":" + pfRoot + ",\"funcText\":\"x\"}", de).andExpect(status().isNotFound());
        send(put("/api/apqp/dfmea-nodes/" + pfRoot), "{\"nodeNm\":\"x\",\"rowVer\":0}", de).andExpect(status().isNotFound());
        // 목록은 유형별
        List<Map<String, Object>> dl = read(D + "/grid?status=ALL", "$.list", de);
        assertThat(dl).extracting(r -> ((Number) r.get("fmeaId")).longValue()).containsExactly(dfmea);
        List<Map<String, Object>> pl = read(F + "/grid?status=ALL", "$.list", pe);
        assertThat(pl).extracting(r -> ((Number) r.get("fmeaId")).longValue()).containsExactly(pfmea);
        // DFMEA 조치는 PMS 이슈 연결 대상 아님(ApqpLookup = PFMEA 만)
        assertThat((Boolean) read(D + "/" + dfmea, "$.data.issueView", de)).isFalse();
    }

    @Test
    void A사로_B사_BOM_도면_기록() throws Exception {
        long fmea = create(apSet(de));
        String compB = accounts.newComp("AE");
        long bId = accounts.newUser(compB, "de1", PW);
        accounts.grant(compB, bId, "DESIGN", "apqp-dfmea", "SEARCH", "SAVE", "APPROVE", "PRINT");
        accounts.grant(compB, bId, "DESIGN", "apqp-ap", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, bId, "de1");
        Fx fb = fixture(compB);
        long setB = apSet(b);
        mvc.perform(get(D + "/" + fmea).with(b)).andExpect(status().isNotFound());
        mvc.perform(post(D + "/" + fmea + "/excel").with(csrf()).with(b)).andExpect(status().isNotFound());
        assertThat((List<?>) read("/api/apqp/dfmea/items/" + fx.assy() + "/boms", "$.data", b)).isEmpty();
        assertThat((List<?>) read(D + "/grid?status=ALL", "$.list", b)).isEmpty();
        // B 품목에 A 의 BOM·도면 리비전
        send(post(D), "{\"itemCd\":\"ZT-ASSY\",\"bomId\":" + fx.bom() + ",\"title\":\"x\",\"apSetId\":" + setB + "}", b)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        send(post(D), "{\"itemCd\":\"ZT-ASSY\",\"docRevId\":" + fx.drawingRev() + ",\"title\":\"x\",\"apSetId\":" + setB + "}", b)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 직접 SQL(B 범위) — A 의 BOM·도면·자품목
        long own = id(send(post(D), "{\"itemCd\":\"ZT-ASSY\",\"bomId\":" + fb.bom() + ",\"title\":\"B\",\"apSetId\":" + setB + "}", b));
        assertThat(catchThrowable(() -> accounts.exec(compB, "update apqp_fmea set bom_id = ? where fmea_id = ?", fx.bom(), own)))
                .hasMessageContaining("is not of item");
        assertThat(catchThrowable(() -> accounts.exec(compB, "update apqp_fmea set doc_rev_id = ? where fmea_id = ?", fx.drawingRev(), own)))
                .hasMessageContaining("not a drawing");
        long rootB = q(compB, "select node_id from apqp_fmea_node where fmea_id = ? and node_kind = 'SYSTEM'", own);
        assertThat(catchThrowable(() -> accounts.exec(compB, """
                insert into apqp_fmea_node (comp_cd, fmea_id, parent_id, node_kind, node_nm, ref_item_id, created_by, updated_by)
                values (?, ?, ?, 'SUBSYSTEM', 'x', ?, 't', 't')""", compB, own, rootB, fx.sub1()))).hasMessageContaining("is not of company");
    }

    // ------------------------------------------------------------------ A5-2b: 재검토·BOM 다시 잇기·PFMEA 연결

    /** ZT-ASSY 의 BOM 새 리비전 B(오늘부터, 줄 = ZT-SUB1 + ZT-PART) 확정 — 리비전 A 는 오늘로 끝 */
    private long newBomB(String c, Fx x) {
        accounts.exec(c, "update mst_bom set valid_to = (now() at time zone 'Asia/Seoul')::date where bom_id = ?", x.bom());
        long b = q(c, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, ?, 'B', (now() at time zone 'Asia/Seoul')::date, 't', 't') returning bom_id""", c, x.assy());
        accounts.exec(c, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                values (?, ?, 10, ?, 1, 'EA', 'MANUAL', 't', 't'), (?, ?, 20, ?, 2, 'EA', 'MANUAL', 't', 't')""", c, b, x.sub1(), c, b, x.part());
        accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", b);
        return b;
    }

    private String review(long fmea) throws Exception {
        List<Map<String, Object>> rows = read(D + "/grid?status=ALL", "$.list", de);
        return (String) rows.stream().filter(r -> ((Number) r.get("fmeaId")).longValue() == fmea).findFirst().orElseThrow().get("review");
    }

    @Test
    void 재검토_BOM_새리비전_도면_새리비전_표시만() throws Exception {
        long fmea = create(apSet(de));
        assertThat(review(fmea)).isNull();
        newBomB(comp, fx);
        assertThat((Boolean) read(D + "/" + fmea, "$.data.fmea.bomStale", de)).isTrue();
        assertThat(review(fmea)).isEqualTo("BOM");
        // 도면: 지금 리비전 A 를 확정 → 아직 같음 / 작성 중 B → 같음 / A 폐기·B 확정 → 재검토
        long doc = q(comp, "select doc_id from mst_doc_rev where doc_rev_id = ?", fx.drawingRev());
        accounts.exec(comp, "update mst_doc_rev set status = 'RELEASED', released_at = now(), released_by = 't' where doc_rev_id = ?", fx.drawingRev());
        long revB = q(comp, "insert into mst_doc_rev (comp_cd, doc_id, rev, created_by, updated_by) values (?, ?, 'B', 't', 't') returning doc_rev_id",
                comp, doc);
        assertThat((Boolean) read(D + "/" + fmea, "$.data.fmea.drawingStale", de)).isFalse();
        accounts.exec(comp, "update mst_doc_rev set status = 'OBSOLETE', obsoleted_at = now(), obsoleted_by = 't' where doc_rev_id = ?", fx.drawingRev());
        accounts.exec(comp, "update mst_doc_rev set status = 'RELEASED', released_at = now(), released_by = 't' where doc_rev_id = ?", revB);
        assertThat((Boolean) read(D + "/" + fmea, "$.data.fmea.drawingStale", de)).isTrue();
        assertThat(review(fmea)).isEqualTo("BOM,DRAWING");
        // 목록 엑셀도 같은 글자
        byte[] x = mvc.perform(post(D + "/export").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ALL\"}").with(de))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            assertThat(cells(wb.getSheetAt(0)).stream().flatMap(List::stream).anyMatch(v -> v.contains("BOM 새 리비전") && v.contains("도면 새 리비전")))
                    .isTrue();
        }
    }

    @Test
    void BOM_다시잇기_짝은_자품목_끊긴것은_글자로_새줄은_하위시스템으로() throws Exception {
        long fmea = create(apSet(de));
        long bomB = newBomB(comp, fx);
        String u = D + "/" + fmea;
        long sub2 = node(nodes(fmea), "SUBSYSTEM", "ZT-SUB2");
        // 머리에서 바꾸기 · 직접 SQL — 막힘
        int ver = ((Number) read(u, "$.data.fmea.rowVer", de)).intValue();
        send(put(u), "{\"itemCd\":\"ZT-ASSY\",\"bomId\":" + bomB + ",\"title\":\"ZT DFMEA\",\"rowVer\":" + ver + "}", de)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_BOM_USED"));
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_fmea set bom_id = ? where fmea_id = ?", bomB, fmea)))
                .hasMessageContaining("subsystems not in bom");
        // 미리보기
        mvc.perform(get(u + "/bom-relink?bomId=" + fx.bom()).with(de)).andExpect(jsonPath("$.errors[0].code").value("APQP_BOM_SAME"));
        mvc.perform(get(u + "/bom-relink?bomId=" + fx.otherBom()).with(de)).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        Map<String, Object> plan = read(u + "/bom-relink?bomId=" + bomB, "$.data", de);
        assertThat(plan.get("matched")).isEqualTo(1);
        assertThat(plan.get("unmatched")).isEqualTo(1);
        assertThat((List<Map<String, Object>>) plan.get("added")).extracting(c -> c.get("itemCd")).containsExactly("ZT-PART");
        assertThat(((Number) read(u, "$.data.fmea.bomId", de)).longValue()).isEqualTo(fx.bom());
        // 낡은 rowVer → 409, 적용
        send(post(u + "/bom"), "{\"bomId\":" + bomB + ",\"rowVer\":" + (ver + 5) + "}", de).andExpect(status().isConflict());
        send(post(u + "/bom"), "{\"bomId\":" + bomB + ",\"rowVer\":" + ver + "}", de).andExpect(status().isOk());
        Map<String, Object> f = read(u, "$.data.fmea", de);
        assertThat(((Number) f.get("bomId")).longValue()).isEqualTo(bomB);
        assertThat(f.get("bomStale")).isEqualTo(false);
        List<Map<String, Object>> ns = nodes(fmea);
        Map<String, Object> s2 = ns.stream().filter(n -> ((Number) n.get("nodeId")).longValue() == sub2).findFirst().orElseThrow();
        assertThat(s2.get("refItemId")).isNull();
        assertThat((String) s2.get("nodeNm")).startsWith("ZT-SUB2");
        assertThat(ns).extracting(n -> n.get("nodeKind") + ":" + n.get("refItemCd")).contains("SUBSYSTEM:ZT-SUB1", "SUBSYSTEM:ZT-PART",
                "COMPONENT:ZT-PART");
        // PFMEA 를 DFMEA 경로로 → 404
        long deId = accounts.query(comp, "select user_id from sys_user where login_id = 'de1'", Long.class);
        accounts.grant(comp, deId, "DESIGN", "apqp-fmea", "SEARCH", "SAVE");
        long pf = id(send(post(F), "{\"itemCd\":\"ZT-ASSY\",\"title\":\"ZT PFMEA\"}", de));
        mvc.perform(get(D + "/" + pf + "/bom-relink?bomId=" + bomB).with(de)).andExpect(status().isNotFound());
    }

    @Test
    void PFMEA_에서_확정_DFMEA_특성_표시와_고장영향_제안_출처() throws Exception {
        long set = apSet(de);
        long dfmea = create(set);
        riskReady(dfmea);
        // 확정 전에는 제안 없음
        long deId = accounts.query(comp, "select user_id from sys_user where login_id = 'de1'", Long.class);
        accounts.grant(comp, deId, "DESIGN", "apqp-fmea", "SEARCH", "SAVE");
        long pf = id(send(post(F), "{\"itemCd\":\"ZT-ASSY\",\"title\":\"ZT PFMEA\",\"apSetId\":" + set + "}", de));
        assertThat((List<?>) read(F + "/" + pf + "/dfmea-sources", "$.data", de)).isEmpty();
        long draftFm = q(comp, "select fail_id from apqp_fmea_fail where fmea_id = ? and fail_kind = 'FM'", dfmea);
        int ver = ((Number) read(D + "/" + dfmea, "$.data.fmea.rowVer", de)).intValue();
        send(post(D + "/" + dfmea + "/release"), "{\"rowVer\":" + ver + "}", de).andExpect(status().isOk());
        // 특성: 머리 품목 제품 특성 ZD1 이 DFMEA 기능에 붙어 있음
        List<Map<String, Object>> chars = read(F + "/" + pf + "/chars", "$.data", de);
        assertThat(chars.stream().filter(c -> "ZD1".equals(c.get("charNo"))).findFirst().orElseThrow().get("inDfmea")).isEqualTo(true);
        // 제안 = 확정 DFMEA 의 고장 형태
        List<Map<String, Object>> sources = read(F + "/" + pf + "/dfmea-sources", "$.data", de);
        assertThat(sources).extracting(x -> x.get("failText")).containsExactly("체결력 부족");
        long fm = ((Number) sources.get(0).get("failId")).longValue();
        assertThat(fm).isEqualTo(draftFm);
        long item = node(read(F + "/" + pf, "$.data.nodes", de), "ITEM", null);
        long fn = id(send(post(F + "/" + pf + "/funcs"), "{\"nodeId\":" + item + ",\"funcText\":\"체결 유지\"}", de));
        long fe = id(send(post(F + "/" + pf + "/fails"), "{\"funcId\":" + fn + ",\"failText\":\"체결력 부족\",\"severity\":8,\"srcFailId\":" + fm + "}", de));
        Map<String, Object> row = read(F + "/" + pf, "$.data.fails[?(@.failId == " + fe + ")]", de) instanceof List<?> l
                ? (Map<String, Object>) l.get(0) : Map.of();
        assertThat(((Number) row.get("srcFailId")).longValue()).isEqualTo(fm);
        assertThat((String) row.get("srcText")).endsWith(" rev 1").startsWith("DF-");
        // 출처가 고장 원인 / DFMEA 고장 형태가 아닌 것 → 막음
        long fc = q(comp, "select fail_id from apqp_fmea_fail where fmea_id = ? and fail_kind = 'FC'", dfmea);
        send(post(F + "/" + pf + "/fails"), "{\"funcId\":" + fn + ",\"failText\":\"x\",\"severity\":5,\"srcFailId\":" + fc + "}", de)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_fmea_fail (comp_cd, fmea_id, func_id, fail_kind, seq, fail_text, severity, src_fail_id, created_by, updated_by)
                values (?, ?, ?, 'FE', 9, 'x', 5, ?, 't', 't')""", comp, pf, fn, fc))).hasMessageContaining("not a released DFMEA failure mode");
        // DFMEA 쪽 고장 영향에는 출처를 못 붙인다
        long sysFunc = q(comp, "select func_id from apqp_fmea_func f join apqp_fmea_node n on n.node_id = f.node_id where n.fmea_id = ? and n.node_kind = 'SYSTEM'",
                dfmea);
        long rev2 = id(send(post(D + "/" + dfmea + "/revise"), "{}", de));
        long sysFunc2 = q(comp, "select func_id from apqp_fmea_func f join apqp_fmea_node n on n.node_id = f.node_id where n.fmea_id = ? and n.node_kind = 'SYSTEM'",
                rev2);
        assertThat(sysFunc2).isNotEqualTo(sysFunc);
        send(post(D + "/" + rev2 + "/fails"), "{\"funcId\":" + sysFunc2 + ",\"failText\":\"x\",\"severity\":5,\"srcFailId\":" + fm + "}", de)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_LEVEL"));
        // DFMEA 새 리비전 확정(rev1 폐기) 뒤에도 이미 붙은 출처는 그대로 고칠 수 있다
        // 새 리비전은 위험행이 복사돼 있어 바로 확정할 수 있다
        assertThat((List<?>) read(D + "/" + rev2, "$.data.risks", de)).isNotEmpty();
        int v2 = ((Number) read(D + "/" + rev2, "$.data.fmea.rowVer", de)).intValue();
        send(post(D + "/" + rev2 + "/release"), "{\"rowVer\":" + v2 + "}", de).andExpect(status().isOk());
        int fv = ((Number) row.get("rowVer")).intValue();
        send(put("/api/apqp/fmea-fails/" + fe), "{\"failText\":\"체결력 부족(고침)\",\"severity\":8,\"srcFailId\":" + fm + ",\"rowVer\":" + fv + "}", de)
                .andExpect(status().isOk());
    }

    @Test
    void A사로_B사_출처와_BOM_다시잇기() throws Exception {
        long set = apSet(de);
        long dfmea = create(set);
        riskReady(dfmea);
        int ver = ((Number) read(D + "/" + dfmea, "$.data.fmea.rowVer", de)).intValue();
        send(post(D + "/" + dfmea + "/release"), "{\"rowVer\":" + ver + "}", de).andExpect(status().isOk());
        long fmA = q(comp, "select fail_id from apqp_fmea_fail where fmea_id = ? and fail_kind = 'FM'", dfmea);
        String compB = accounts.newComp("AF");
        long bId = accounts.newUser(compB, "de1", PW);
        accounts.grant(compB, bId, "DESIGN", "apqp-dfmea", "SEARCH", "SAVE");
        accounts.grant(compB, bId, "DESIGN", "apqp-fmea", "SEARCH", "SAVE");
        accounts.grant(compB, bId, "DESIGN", "apqp-ap", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, bId, "de1");
        Fx fb = fixture(compB);
        long setB = apSet(b);
        long pfB = id(send(post(F), "{\"itemCd\":\"ZT-ASSY\",\"title\":\"B PFMEA\",\"apSetId\":" + setB + "}", b));
        assertThat((List<?>) read(F + "/" + pfB + "/dfmea-sources", "$.data", b)).isEmpty();
        long itemB = node(read(F + "/" + pfB, "$.data.nodes", b), "ITEM", null);
        long fnB = id(send(post(F + "/" + pfB + "/funcs"), "{\"nodeId\":" + itemB + ",\"funcText\":\"x\"}", b));
        send(post(F + "/" + pfB + "/fails"), "{\"funcId\":" + fnB + ",\"failText\":\"x\",\"severity\":5,\"srcFailId\":" + fmA + "}", b)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        assertThat(catchThrowable(() -> accounts.exec(compB, """
                insert into apqp_fmea_fail (comp_cd, fmea_id, func_id, fail_kind, seq, fail_text, severity, src_fail_id, created_by, updated_by)
                values (?, ?, ?, 'FE', 9, 'x', 5, ?, 't', 't')""", compB, pfB, fnB, fmA))).hasMessageContaining("not a released DFMEA failure mode");
        // B 의 DFMEA 를 A 의 BOM 으로 다시 잇기 / A 의 DFMEA 를 B 가 다시 잇기
        long dfB = id(send(post(D), "{\"itemCd\":\"ZT-ASSY\",\"bomId\":" + fb.bom() + ",\"title\":\"B\",\"apSetId\":" + setB
                + ",\"fromBom\":true}", b));
        long bomBofA = newBomB(comp, fx);
        mvc.perform(get(D + "/" + dfB + "/bom-relink?bomId=" + bomBofA).with(b)).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        int vb = ((Number) read(D + "/" + dfB, "$.data.fmea.rowVer", b)).intValue();
        send(post(D + "/" + dfB + "/bom"), "{\"bomId\":" + bomBofA + ",\"rowVer\":" + vb + "}", b).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(get(D + "/" + dfmea + "/bom-relink?bomId=" + bomBofA).with(b)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ 13.9 확정 DFMEA → QMS 관리문서(문서 유형 DFMEA)

    @Test
    void 확정_DFMEA_를_QMS_문서로_유형_DFMEA_새리비전은_같은_문서() throws Exception {
        long fmea = create(apSet(de));
        riskReady(fmea);
        String u = D + "/" + fmea;
        // 작성 중 → 못 올림, QMS 문서 저장 권한 없음 → 못 올림
        assertThat((Boolean) read(u + "/qms-doc", "$.data.canPublish", de)).isFalse();
        int ver = ((Number) read(u, "$.data.fmea.rowVer", de)).intValue();
        send(post(u + "/release"), "{\"rowVer\":" + ver + "}", de).andExpect(status().isOk());
        assertThat((Boolean) read(u + "/qms-doc", "$.data.canPublish", de)).isFalse();
        send(post(u + "/qms-doc"), "{}", de).andExpect(status().isForbidden());
        long deId = accounts.query(comp, "select user_id from sys_user where login_id = 'de1'", Long.class);
        accounts.grant(comp, deId, "DESIGN", "qms-doc", "SEARCH", "SAVE");
        assertThat((Boolean) read(u + "/qms-doc", "$.data.canPublish", de)).isTrue();
        String fmeaNo = read(u, "$.data.fmea.fmeaNo", de);
        Map<String, Object> v = JsonPath.read(send(post(u + "/qms-doc"), "{}", de).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.data");
        String docNo = (String) v.get("docNo");
        assertThat(accounts.query(comp, "select doc_type from mst_doc where doc_no = ?", String.class, docNo)).isEqualTo("DFMEA");
        assertThat(accounts.query(comp, """
                select r.change_summary from mst_doc_rev r join mst_doc d on d.doc_id = r.doc_id where d.doc_no = ?""", String.class, docNo))
                .isEqualTo("DFMEA " + fmeaNo + " rev 1");
        // 다시 눌러도 같은 것(멱등), 상태는 올림 표시
        Map<String, Object> again = JsonPath.read(send(post(u + "/qms-doc"), "{}", de).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.data");
        assertThat(again.get("docRevId")).isEqualTo(v.get("docRevId"));
        assertThat((Boolean) read(u + "/qms-doc", "$.data.canPublish", de)).isFalse();
        assertThat((String) read(u + "/qms-doc", "$.data.linked.docNo", de)).isEqualTo(docNo);
        // 새 리비전 확정 → 같은 문서의 새 리비전(QMS 쪽 이전 리비전을 배포해 두어야 새 작성 중을 만들 수 있다)
        accounts.exec(comp, "update mst_doc_rev set status = 'RELEASED', released_at = now(), released_by = 't' where doc_rev_id = ?",
                ((Number) v.get("docRevId")).longValue());
        long rev2 = id(send(post(u + "/revise"), "{}", de));
        int v2 = ((Number) read(D + "/" + rev2, "$.data.fmea.rowVer", de)).intValue();
        send(post(D + "/" + rev2 + "/release"), "{\"rowVer\":" + v2 + "}", de).andExpect(status().isOk());
        Map<String, Object> next = JsonPath.read(send(post(D + "/" + rev2 + "/qms-doc"), "{}", de).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(), "$.data");
        assertThat(next.get("docNo")).isEqualTo(docNo);
        assertThat(next.get("docRevId")).isNotEqualTo(v.get("docRevId"));
        // PFMEA 경로로 DFMEA / DFMEA 권한 없는 사람 / A사로 B사
        accounts.grant(comp, deId, "DESIGN", "apqp-fmea", "SEARCH", "PRINT");
        mvc.perform(get(F + "/" + rev2 + "/qms-doc").with(de)).andExpect(status().isNotFound());
        send(post(F + "/" + rev2 + "/qms-doc"), "{}", de).andExpect(status().isNotFound());
        long peId = accounts.newUser(comp, "pe2", PW);
        accounts.grant(comp, peId, "PROC", "apqp-fmea", "SEARCH", "PRINT");
        accounts.grant(comp, peId, "PROC", "qms-doc", "SEARCH", "SAVE");
        send(post(D + "/" + rev2 + "/qms-doc"), "{}", accounts.as(comp, peId, "pe2")).andExpect(status().isForbidden());
        String compB = accounts.newComp("AG");
        long bId = accounts.newUser(compB, "de1", PW);
        accounts.grant(compB, bId, "DESIGN", "apqp-dfmea", "SEARCH", "PRINT");
        accounts.grant(compB, bId, "DESIGN", "qms-doc", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, bId, "de1");
        mvc.perform(get(D + "/" + rev2 + "/qms-doc").with(b)).andExpect(status().isNotFound());
        send(post(D + "/" + rev2 + "/qms-doc"), "{}", b).andExpect(status().isNotFound());
    }
}
