package io.smarthappy.module.apqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

import io.smarthappy.module.apqp.link.ApqpLookup;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * P6 APQP A3 1차 (docs/41 11장, L1~L7) — PMS 산출물 ← PFMEA·관리계획서 리비전(한 번이라도 확정 = 승인, 작성 중 → 확정 뒤 반영),
 * 라우팅 새 리비전 재검토 표시, 결재(정책 Y: 상신 → 결재 중 고정 → 승인 = 확정 · 반려 = 작성 중, 정책과 다른 확정 방법 거부),
 * 이상 입력(폐기본·같은 리비전·없는 번호·종류·권한), A사로 B사.
 * A3 2차(11.6) — 확정 뒤 그 품목에 적용된 ECO·승인된 4M ECO(PLM EcoLookup + QMS ChangeLookup) 재검토 표시.
 * A3-5(11.7) — PFMEA 조치 → PMS 이슈(한 번 복사, 조치당 하나, 상태 따로), 프로젝트 규칙·권한·A사로 B사.
 * A3-3(11.8) — 확정 PFMEA·관리계획서 → QMS 관리문서 작성 중 리비전(양식 엑셀 첨부, 같은 번호 = 같은 문서, 멱등, 권한, A사로 B사).
 * 11.9 — 새 리비전에서 라우팅 바꾸기(단계 다시 잇기: 같은 op_seq + 같은 공정만, 못 옮기면 글자 단계·감사 기록), 관리계획서는 근거 PFMEA 의 라우팅을 따름.
 */
@AutoConfigureMockMvc
class ApqpA3Test extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String F = "/api/apqp/fmeas";
    private static final String CP = "/api/apqp/cps";
    private static final String P = "/api/pms/projects";
    private static final String D = "/api/pms/deliverables";
    private static final String RULES = """
            [{"sevFrom":9,"sevTo":10,"occFrom":1,"occTo":10,"detFrom":1,"detTo":10,"ap":"H"},
             {"sevFrom":1,"sevTo":8,"occFrom":6,"occTo":10,"detFrom":1,"detTo":10,"ap":"M"},
             {"sevFrom":1,"sevTo":8,"occFrom":1,"occTo":5,"detFrom":1,"detTo":10,"ap":"L"}]""";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    ApqpLookup lookup;

    @Autowired
    TenantRunner runner;

    String comp;
    long qeId;
    RequestPostProcessor qe;
    long routing;
    long step10;
    long weldChar;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("A3");
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
        for (String prog : List.of("pms-project", "pms-template", "pms-gate", "pms-deliverable", "pms-issue", "pms-home")) {
            accounts.grant(c, u, "PMS", prog, "SEARCH", "SAVE");
        }
        accounts.grant(c, u, "PMS", "pms-deliverable", "APPROVE");
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

    // ------------------------------------------------------------------ A3-1 PMS 산출물

    private long project() throws Exception {
        id(send(post("/api/pms/templates/default-apqp"), "{}", qe));
        return id(send(post(P), "{\"projNm\":\"ZT 신규 브래킷\",\"projType\":\"APQP\",\"tmplCd\":\"APQP-STD\",\"sopDate\":\""
                + today.plusDays(400) + "\"}", qe));
    }

    private Map<String, Object> deliv(long proj, String nm) throws Exception {
        List<Map<String, Object>> rows = read(P + "/" + proj + "/deliverables", "$.data.rows[?(@.delivNm == '" + nm + "')]", qe);
        return rows.get(0);
    }

    private ResultActions link(long proj, String nm, String kind, String no, Integer rev, RequestPostProcessor who) throws Exception {
        Map<String, Object> d = deliv(proj, nm);
        return send(post(D + "/" + ((Number) d.get("delivId")).longValue() + "/apqp-link"), "{\"kind\":\"" + kind + "\",\"no\":\"" + no
                + "\",\"rev\":" + rev + ",\"note\":\"연결\",\"rowVer\":" + ((Number) d.get("rowVer")).intValue() + "}", who);
    }

    @Test
    void 산출물_연결_작성중은_확정뒤_반영_확정본은_바로_승인_폐기돼도_승인유지() throws Exception {
        long proj = project();
        long fmea = draftFmea();
        String fmeaNo = read(F + "/" + fmea, "$.data.fmea.fmeaNo", qe);
        // 작성 중 리비전 연결 → 제출 중, 반영해도 그대로
        link(proj, "PFMEA", "fmea", fmeaNo, null, qe).andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.docStatus").value("DRAFT"));
        assertThat((String) deliv(proj, "PFMEA").get("docNo")).isEqualTo(fmeaNo);
        send(post(P + "/" + proj + "/deliverables/sync-docs"), "{}", qe).andExpect(jsonPath("$.data").value(0));
        // 같은 리비전 다시 → 거부
        link(proj, "PFMEA", "FMEA", fmeaNo, null, qe).andExpect(jsonPath("$.errors[0].code").value("PMS_APQP_SAME"));
        // 확정 → 반영 = 승인, 이력 LINK 행에 "번호 rev 1"
        release(F + "/" + fmea);
        send(post(P + "/" + proj + "/deliverables/sync-docs"), "{}", qe).andExpect(jsonPath("$.data").value(1));
        Map<String, Object> d = deliv(proj, "PFMEA");
        assertThat(d.get("status")).isEqualTo("APPROVED");
        List<String> labels = read(D + "/" + ((Number) d.get("delivId")).longValue() + "/history", "$.data[*].docLabel", qe);
        assertThat(labels).contains(fmeaNo + " rev 1");
        // 새 리비전 확정 → rev 1 폐기, 산출물 승인은 그대로(L2)
        long r2 = id(send(post(F + "/" + fmea + "/revise"), "", qe));
        release(F + "/" + r2);
        assertThat(deliv(proj, "PFMEA").get("status")).isEqualTo("APPROVED");
        assertThat(deliv(proj, "PFMEA").get("docStatus")).isEqualTo("OBSOLETE");
        // 관리계획서 확정본 연결 → 바로 승인
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + r2 + ",\"phase\":\"PRE_LAUNCH\",\"title\":\"ZT 관리계획서\",\"pullFromFmea\":true}", qe));
        long line = ((Number) read(CP + "/" + cp, "$.data.lines[0].lineId", qe)).longValue();
        send(put("/api/apqp/cp-lines/" + line), "{\"stepId\":" + step10 + ",\"charId\":" + weldChar + ",\"ctrlMethod\":\"INSPECT\",\"freqType\":\"EVERY_LOT\","
                + "\"reactionPlan\":\"보류\",\"rowVer\":" + ver(CP + "/" + cp, "$.data.lines[0].rowVer") + "}", qe).andExpect(status().isOk());
        release(CP + "/" + cp);
        String cpNo = read(CP + "/" + cp, "$.data.cp.cpNo", qe);
        link(proj, "관리계획서(양산 전)", "CP", cpNo, null, qe).andExpect(jsonPath("$.data.status").value("APPROVED"));
        // 이상: 폐기본 지정 · 없는 번호 · 없는 리비전 · 종류 · 그 APQP 화면 조회 권한 없음
        link(proj, "DFMEA", "FMEA", fmeaNo, 1, qe).andExpect(jsonPath("$.errors[0].code").value("PMS_APQP_OBSOLETE"));
        link(proj, "DFMEA", "FMEA", "FM-0000-0000", null, qe).andExpect(jsonPath("$.errors[0].code").value("PMS_APQP_NOT_FOUND"));
        link(proj, "DFMEA", "FMEA", fmeaNo, 9, qe).andExpect(jsonPath("$.errors[0].code").value("PMS_APQP_REV_NOT_FOUND"));
        link(proj, "DFMEA", "XX", fmeaNo, null, qe).andExpect(jsonPath("$.errors[0].field").value("kind"));
        long pm = accounts.newUser(comp, "pm1", PW);
        for (String prog : List.of("pms-project", "pms-deliverable")) {
            accounts.grant(comp, pm, "PMS", prog, "SEARCH", "SAVE");
        }
        accounts.grant(comp, pm, "PMS", "pms-project", "APPROVE");
        accounts.exec(comp, "update pms_project set pm_user_id = ? where proj_id = ?", pm, proj);
        link(proj, "DFMEA", "FMEA", fmeaNo, null, accounts.as(comp, pm, "pm1")).andExpect(status().isForbidden());
        // A사로 B사: B사 프로젝트에서 A사 번호 → 없음, 직접 SQL 로 A사 리비전을 가리키기 → 트리거
        String other = accounts.newComp("A4");
        long bu = accounts.newUser(other, "qe1", PW);
        grantAll(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "qe1");
        id(send(post("/api/pms/templates/default-apqp"), "{}", b));
        long bProj = id(send(post(P), "{\"projNm\":\"B\",\"projType\":\"APQP\",\"tmplCd\":\"APQP-STD\",\"sopDate\":\"" + today.plusDays(400) + "\"}", b));
        List<Map<String, Object>> bRows = JsonPath.read(mvc.perform(get(P + "/" + bProj + "/deliverables").with(b)).andReturn().getResponse()
                .getContentAsString(), "$.data.rows[?(@.delivNm == 'PFMEA')]");
        Map<String, Object> bd = bRows.get(0);
        send(post(D + "/" + ((Number) bd.get("delivId")).longValue() + "/apqp-link"), "{\"kind\":\"FMEA\",\"no\":\"" + fmeaNo + "\",\"rowVer\":"
                + ((Number) bd.get("rowVer")).intValue() + "}", b).andExpect(jsonPath("$.errors[0].code").value("PMS_APQP_NOT_FOUND"));
        assertThat(catchThrowable(() -> accounts.exec(other, "update pms_deliverable set ref_type = 'APQP_FMEA', ref_id = ?, status = 'SUBMITTED' "
                + "where deliv_id = ?", fmea, ((Number) bd.get("delivId")).longValue()))).hasMessageContaining("is not of company");
    }

    // ------------------------------------------------------------------ A3-2 ① 라우팅 새 리비전

    /** 같은 품목·공장·대안의 더 늦은 확정 리비전 A1(지금 리비전 A0 은 끝을 정해 확정 — 기간 겹침 금지) → 재검토 ① */
    private void newerRouting() {
        accounts.exec(comp, "update mst_routing set valid_to = ?, status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?",
                today.plusDays(30), routing);
        accounts.exec(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'ZT-BRKT'), 'P1', 'A1', ?, 't', 't')""", comp, today.plusDays(30));
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, (select routing_id from mst_routing where rev = 'A1'), 10, (select proc_id from mst_proc where proc_cd = 'ZWELD'), 't', 't')""",
                comp);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where rev = 'A1'");
    }

    @Test
    void 라우팅_새_리비전이_확정되면_재검토_표시만() throws Exception {
        long fmea = draftFmea();
        release(F + "/" + fmea);
        assertThat((Boolean) read(F + "/" + fmea, "$.data.fmea.routingStale", qe)).isFalse();
        newerRouting();
        assertThat((Boolean) read(F + "/" + fmea, "$.data.fmea.routingStale", qe)).isTrue();
        mvc.perform(get(F + "/grid").with(qe)).andExpect(jsonPath("$.list[0].routingStale").value(true))
                .andExpect(jsonPath("$.list[0].review").value("ROUTING"));
        // M2 목록 칸 — 열 정의에 있고 정렬 키가 아니다
        mvc.perform(get(F + "/columns").with(qe))
                .andExpect(jsonPath("$.list[?(@.field == 'review')].sortable").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.list[?(@.field == 'review')].type").value(org.hamcrest.Matchers.contains("LABEL")))
                .andExpect(jsonPath("$.list[?(@.field == 'review')].title").value(org.hamcrest.Matchers.contains("재검토")))
                // 글자는 열 정의의 문구 표(X2, 보는 사람 언어) — 화면·엑셀 같은 문구
                .andExpect(jsonPath("$.list[?(@.field == 'review')].labels.ROUTING").value(org.hamcrest.Matchers.contains("라우팅 새 리비전")));
        mvc.perform(get(F + "/columns").with(accounts.as(comp, qeId, "qe1", "en")))
                .andExpect(jsonPath("$.list[?(@.field == 'review')].labels.ROUTING").value(org.hamcrest.Matchers.contains("New routing revision")));
        // 표시만 — 확정본은 그대로, 작성 중 CP 도 만들 수 있다(같은 표시)
        assertThat((String) read(F + "/" + fmea, "$.data.fmea.status", qe)).isEqualTo("RELEASED");
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PROTO\",\"title\":\"ZT\"}", qe));
        assertThat((Boolean) read(CP + "/" + cp, "$.data.cp.routingStale", qe)).isTrue();
        mvc.perform(get(CP + "/grid").with(qe)).andExpect(jsonPath("$.list[0].review").value("ROUTING"));
        // 근거 PFMEA 새 리비전 확정 → 관리계획서 칸에 둘 다, 폐기된 PFMEA rev 1 은 칸 없음
        long r2 = id(send(post(F + "/" + fmea + "/revise"), "", qe));
        release(F + "/" + r2);
        mvc.perform(get(CP + "/grid").with(qe)).andExpect(jsonPath("$.list[0].review").value("ROUTING,FMEA"));
        mvc.perform(get(CP + "/columns").with(qe))
                .andExpect(jsonPath("$.list[?(@.field == 'review')].labels.FMEA").value(org.hamcrest.Matchers.contains("근거 PFMEA 새 리비전")));
        mvc.perform(get(F + "/grid").param("status", "ALL").with(qe)).andExpect(jsonPath("$.list[0].fmeaId").value(r2))
                .andExpect(jsonPath("$.list[0].review").value("ROUTING"))
                .andExpect(jsonPath("$.list[1].status").value("OBSOLETE")).andExpect(jsonPath("$.list[1].review").doesNotExist());
        // 라우팅 없는 FMEA 는 표시 없음
        long noRouting = id(send(post(F), "{\"itemCd\":\"ZT-BRKT\",\"title\":\"ZT 라우팅 없음\"}", qe));
        assertThat((Boolean) read(F + "/" + noRouting, "$.data.fmea.routingStale", qe)).isFalse();
    }

    private XSSFWorkbook listExcel(String base, String body, RequestPostProcessor who) throws Exception {
        byte[] b = send(post(base + "/export"), body, who).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return new XSSFWorkbook(new ByteArrayInputStream(b));
    }

    /** 첫 시트에서 머리 글자 → 열 번호, 행마다 {머리: 값} */
    private static List<Map<String, String>> sheetRows(XSSFWorkbook wb) {
        var sh = wb.getSheetAt(0);
        var head = sh.getRow(0);
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (int r = 1; r <= sh.getLastRowNum(); r++) {
            var row = sh.getRow(r);
            Map<String, String> m = new java.util.LinkedHashMap<>();
            for (int c = 0; c < head.getLastCellNum(); c++) {
                var cell = row == null ? null : row.getCell(c);
                m.put(head.getCell(c).getStringCellValue(), cell == null ? null : cell.toString());
            }
            out.add(m);
        }
        return out;
    }

    @Test
    void 목록_엑셀_재검토는_화면과_같은_글자_로케일_EXCEL권한_A사로B사() throws Exception {
        accounts.grant(comp, qeId, "APQP", "apqp-fmea", "EXCEL");
        accounts.grant(comp, qeId, "APQP", "apqp-cp", "EXCEL");
        long fmea = draftFmea();
        String fmeaNo = read(F + "/" + fmea, "$.data.fmea.fmeaNo", qe);
        release(F + "/" + fmea);
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PROTO\",\"title\":\"ZT\"}", qe));
        String cpNo = read(CP + "/" + cp, "$.data.cp.cpNo", qe);
        // 재검토 없음 = 빈 칸
        try (XSSFWorkbook wb = listExcel(F, "{}", qe)) {
            assertThat(sheetRows(wb)).singleElement().satisfies(r -> assertThat(r.get("재검토")).isNull());
        }
        newerRouting();
        long r2 = id(send(post(F + "/" + fmea + "/revise"), "", qe));
        release(F + "/" + r2);
        // 화면과 같은 글자(코드 아님) — 폐기본은 빈 칸, 관리계획서는 두 가지를 ", " 로
        try (XSSFWorkbook wb = listExcel(F, "{\"status\":\"ALL\"}", qe)) {
            assertThat(sheetRows(wb)).extracting(r -> r.get("상태") + "|" + r.get("재검토"))
                    .containsExactly("확정|라우팅 새 리비전", "폐기|null");
        }
        try (XSSFWorkbook wb = listExcel(CP, "{}", qe)) {
            assertThat(sheetRows(wb)).singleElement().satisfies(r -> {
                assertThat(r.get("관리계획서 번호")).isEqualTo(cpNo);
                assertThat(r.get("재검토")).isEqualTo("라우팅 새 리비전, 근거 PFMEA 새 리비전");
            });
        }
        // 요청 로케일(en) — 머리·글자 모두 영어, 열 고르기(hb.grid 가 보낸 순서)
        try (XSSFWorkbook wb = listExcel(F, "{\"columns\":[\"fmeaNo\",\"review\"]}", accounts.as(comp, qeId, "qe1", "en"))) {
            assertThat(sheetRows(wb)).singleElement().satisfies(r -> {
                assertThat(r.keySet()).hasSize(2).contains("Review");
                assertThat(r.get("Review")).isEqualTo("New routing revision");
                assertThat(r.values()).contains(fmeaNo);
            });
        }
        // 모르는 열·정렬 키 → 400, EXCEL 없음 → 403
        send(post(F + "/export"), "{\"columns\":[\"nope\"]}", qe).andExpect(status().isBadRequest());
        send(post(CP + "/export"), "{\"sort\":[\"review,asc\"]}", qe).andExpect(status().isBadRequest());
        long viewer = accounts.newUser(comp, "qe2", PW);
        accounts.grant(comp, viewer, "APQPVIEW", "apqp-fmea", "SEARCH");
        send(post(F + "/export"), "{}", accounts.as(comp, viewer, "qe2")).andExpect(status().isForbidden());
        // A사로 B사: B사 사용자(EXCEL 있음) 내려받기에 A사 행 없음
        String b = accounts.newComp("A7");
        long bu = accounts.newUser(b, "qe1", PW);
        grantAll(b, bu);
        accounts.grant(b, bu, "APQP", "apqp-fmea", "EXCEL");
        try (XSSFWorkbook wb = listExcel(F, "{\"status\":\"ALL\"}", accounts.as(b, bu, "qe1"))) {
            assertThat(sheetRows(wb)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ A3-7 결재

    @Test
    void 결재_정책_켬_상신_고정_승인은_확정_반려는_작성중_정책과_다른_확정은_거부() throws Exception {
        long fmea = draftFmea();
        String url = F + "/" + fmea;
        long approverId = accounts.newUser(comp, "boss1", PW);
        RequestPostProcessor boss = accounts.as(comp, approverId, "boss1");
        String lines = "[{\"userId\":" + approverId + ",\"lineType\":\"APPROVE\"}]";
        // 정책 끔(기본): 상신은 거부
        send(post(url + "/submit"), "{\"rowVer\":" + ver(url, "$.data.fmea.rowVer") + ",\"lines\":" + lines + "}", qe)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APQP_APPROVAL_POLICY"));
        policy("APQP_RELEASE_APPROVAL", "Y");
        assertThat((Boolean) read(url, "$.data.approvalOn", qe)).isTrue();
        // 정책 켬: 바로 확정은 거부, 상신 → 결재 중(내용 고정 — API 409, 직접 SQL 거부)
        send(post(url + "/release"), "{\"rowVer\":" + ver(url, "$.data.fmea.rowVer") + "}", qe)
                .andExpect(jsonPath("$.code").value("APQP_APPROVAL_POLICY"));
        long aprId = id(send(post(url + "/submit"), "{\"rowVer\":" + ver(url, "$.data.fmea.rowVer") + ",\"lines\":" + lines + "}", qe));
        assertThat((String) read(url, "$.data.fmea.status", qe)).isEqualTo("IN_APPROVAL");
        assertThat((Boolean) read(url, "$.data.editable", qe)).isFalse();
        assertThat(((Number) read(url, "$.data.fmea.aprId", qe)).longValue()).isEqualTo(aprId);
        List<Map<String, Object>> nodes = read(url, "$.data.nodes", qe);
        long item = ((Number) nodes.get(0).get("nodeId")).longValue();
        send(post(url + "/nodes"), "{\"parentId\":" + item + ",\"nodeKind\":\"STEP\",\"nodeNm\":\"x\"}", qe)
                .andExpect(jsonPath("$.code").value("APQP_FMEA_STATE"));
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_fmea set title = 'x' where fmea_id = ?", fmea)))
                .hasMessageContaining("IN_APPROVAL");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_fmea_risk set occ = 1 where fmea_id = ?", fmea)))
                .hasMessageContaining("IN_APPROVAL");
        // 결재 중이면 같은 번호 새 리비전 없음(작성 중 하나 규칙) — 목록 현행에는 보인다
        mvc.perform(get(F + "/grid").with(qe)).andExpect(jsonPath("$.list[0].status").value("IN_APPROVAL"));
        // 승인 = 확정
        mvc.perform(post("/api/platform/approvals/" + aprId + "/approve").with(boss).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk());
        assertThat((String) read(url, "$.data.fmea.status", qe)).isEqualTo("RELEASED");
        assertThat((Object) read(url, "$.data.fmea.releasedAt", qe)).isNotNull();
        // 관리계획서: 상신 → 반려 = 작성 중(다시 고칠 수 있다)
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PROTO\",\"title\":\"ZT\",\"pullFromFmea\":true}", qe));
        String cu = CP + "/" + cp;
        long line = ((Number) read(cu, "$.data.lines[0].lineId", qe)).longValue();
        send(put("/api/apqp/cp-lines/" + line), "{\"stepId\":" + step10 + ",\"charId\":" + weldChar + ",\"ctrlMethod\":\"INSPECT\",\"freqType\":\"EVERY_LOT\","
                + "\"reactionPlan\":\"보류\",\"rowVer\":" + ver(cu, "$.data.lines[0].rowVer") + "}", qe).andExpect(status().isOk());
        long cpApr = id(send(post(cu + "/submit"), "{\"rowVer\":" + ver(cu, "$.data.cp.rowVer") + ",\"lines\":" + lines + "}", qe));
        assertThat((String) read(cu, "$.data.cp.status", qe)).isEqualTo("IN_APPROVAL");
        assertThat((String) read(cu, "$.data.lines[0].dataType", qe)).isEqualTo("ATTRIBUTE");   // 상신 때 스냅샷
        send(post(cu + "/lines"), "{\"stepText\":\"x\",\"charText\":\"x\",\"ctrlMethod\":\"OTHER\"}", qe).andExpect(jsonPath("$.code").value("APQP_CP_STATE"));
        mvc.perform(post("/api/platform/approvals/" + cpApr + "/reject").with(boss).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"반응계획 보완\"}")).andExpect(status().isOk());
        assertThat((String) read(cu, "$.data.cp.status", qe)).isEqualTo("DRAFT");
        assertThat((Boolean) read(cu, "$.data.editable", qe)).isTrue();
        // 확정 조건 미달이면 상신 전에 거부(결재 요청이 생기지 않는다)
        id(send(post(cu + "/lines"), "{\"stepText\":\"ZT 포장\",\"charText\":\"ZT 라벨\",\"ctrlMethod\":\"OTHER\"}", qe));
        send(post(cu + "/submit"), "{\"rowVer\":" + ver(cu, "$.data.cp.rowVer") + ",\"lines\":" + lines + "}", qe)
                .andExpect(jsonPath("$.code").value("APQP_RELEASE_CHECK"));
        assertThat(accounts.query(comp, "select count(*) from apr_request where doc_type = 'APQP_CP'", Long.class)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ A3-2 ②③ ECO 적용·4M 승인 (2차)

    private long eco(String c, String no, String kind, String status, String at) {
        return eco(c, no, kind, status, at, null);
    }

    /** ECO(작성 중, ITEM 대상, m4 = QMS 4M 세부 — 작성 중에만 넣을 수 있다) → 상태를 직접 옮긴다(PLM 시험과 같은 방식) */
    private long eco(String c, String no, String kind, String status, String at, String m4, long... chars) {
        long owner = accounts.query(c, "select min(user_id) from sys_user", Long.class);
        long eco = accounts.query(c, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, change_kind, mark_until, created_by, updated_by)
                values (?, ?, ?, 'QUALITY', DATE '2026-09-01', ?, ?, case when ? = 'PROCESS_4M' then DATE '2026-12-31' end, 't', 't')
                returning eco_id""", Long.class, c, no, "ZT " + no, owner, kind, kind);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'ZT-BRKT'), 't', 't')""", c, eco);
        if (m4 != null) {
            accounts.exec(c, "insert into qms_change (eco_id, comp_cd, eco_no, m4_types, before_desc, after_desc, created_by, updated_by) "
                    + "values (?, ?, ?, string_to_array(?, ',')::varchar(10)[], 'ZT 구 지그', 'ZT 신 지그', 't', 't')", eco, c, no, m4);
            int n = 0;
            for (long ch : chars) {   // 영향 특성(QMS 4M 세부 — 작성 중에만)
                accounts.exec(c, "insert into qms_change_char (comp_cd, eco_id, line_no, char_id, created_by) values (?, ?, ?, ?, 't')", c, eco, ++n, ch);
            }
        }
        if (!"DRAFT".equals(status)) {
            accounts.exec(c, "update plm_eco set status = 'PENDING' where eco_id = ?", eco);
            accounts.exec(c, "update plm_eco set status = 'APPROVED', approved_at = " + at + " where eco_id = ?", eco);
        }
        if ("RELEASED".equals(status)) {
            accounts.exec(c, "update plm_eco set status = 'RELEASED', released_at = " + at + " where eco_id = ?", eco);
        }
        if ("CANCELLED".equals(status)) {
            accounts.exec(c, "update plm_eco set status = 'CANCELLED', cancel_reason = 'ZT 취소' where eco_id = ?", eco);
        }
        return eco;
    }

    private List<String> changeNos(String url) throws Exception {
        return read(url, "$.data.changes.rows[*].ecoNo", qe);
    }

    @Test
    void 확정뒤_적용된_ECO와_승인된_4M은_재검토_표시만() throws Exception {
        long fmea = draftFmea();
        String url = F + "/" + fmea;
        // 작성 중은 보지 않는다
        eco(comp, "ECO-ZT-1", "DESIGN", "RELEASED", "now()");
        assertThat(changeNos(url)).isEmpty();
        release(url);
        // 확정 전(5일 전) 적용 = 빠짐, 확정일(오늘) 적용 = 들어옴(같은 날은 포함 — 날짜만 비교)
        eco(comp, "ECO-ZT-0", "DESIGN", "RELEASED", "now() - interval '5 days'");
        long e2 = eco(comp, "ECO-ZT-2", "PROCESS_4M", "APPROVED", "now()", "MACHINE,METHOD");   // 4M 승인(적용 전) = 들어옴 + 4M 세부
        eco(comp, "ECO-ZT-3", "DESIGN", "APPROVED", "now()");             // 설계 변경 승인만 = 빠짐(적용돼야 ②)
        eco(comp, "ECO-ZT-4", "PROCESS_4M", "CANCELLED", "now()");        // 승인 뒤 취소 = 빠짐
        eco(comp, "ECO-ZT-5", "PROCESS_4M", "DRAFT", null);               // 작성 중 = 빠짐
        // 다른 품목 ECO = 빠짐
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'ZT-OTHER', 'ZT 다른 품목', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        long other = eco(comp, "ECO-ZT-6", "DESIGN", "DRAFT", null);
        accounts.exec(comp, "update plm_eco_target set item_id = (select item_id from mst_item where item_cd = 'ZT-OTHER') where eco_id = ?", other);
        accounts.exec(comp, "update plm_eco set status = 'PENDING' where eco_id = ?", other);
        accounts.exec(comp, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", other);
        accounts.exec(comp, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", other);

        assertThat(changeNos(url)).containsExactly("ECO-ZT-1", "ECO-ZT-2");
        assertThat((List<String>) read(url, "$.data.changes.rows[1].m4Types", qe)).containsExactly("MACHINE", "METHOD");
        assertThat((List<String>) read(url, "$.data.changes.rows[0].m4Types", qe)).isEmpty();
        assertThat((Object) read(url, "$.data.changes.rows[1].releasedOn", qe)).isNull();
        assertThat((String) read(url, "$.data.changes.rows[0].releasedOn", qe)).isEqualTo(today.toString());
        assertThat((Boolean) read(url, "$.data.changes.truncated", qe)).isFalse();
        // 표시만 — 확정본 그대로, 조치는 계속 고칠 수 있다
        assertThat((String) read(url, "$.data.fmea.status", qe)).isEqualTo("RELEASED");
        assertThat((Boolean) read(url, "$.data.actionEditable", qe)).isTrue();
        // 4M 이 적용되면 한 줄 그대로(②③ 겹침은 한 번), 적용일이 붙는다
        accounts.exec(comp, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", e2);
        assertThat(changeNos(url)).containsExactly("ECO-ZT-1", "ECO-ZT-2");
        assertThat((String) read(url, "$.data.changes.rows[1].releasedOn", qe)).isEqualTo(today.toString());
        // 관리계획서도 같은 규칙: 작성 중 빈 값 → 확정 뒤 같은 목록
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PROTO\",\"title\":\"ZT\",\"pullFromFmea\":true}", qe));
        String cu = CP + "/" + cp;
        assertThat(changeNos(cu)).isEmpty();
        long line = ((Number) read(cu, "$.data.lines[0].lineId", qe)).longValue();
        send(put("/api/apqp/cp-lines/" + line), "{\"stepId\":" + step10 + ",\"charId\":" + weldChar + ",\"ctrlMethod\":\"INSPECT\",\"freqType\":\"EVERY_LOT\","
                + "\"reactionPlan\":\"보류\",\"rowVer\":" + ver(cu, "$.data.lines[0].rowVer") + "}", qe).andExpect(status().isOk());
        release(cu);
        assertThat(changeNos(cu)).containsExactly("ECO-ZT-1", "ECO-ZT-2");
        // 새 리비전 확정 → 옛 리비전(폐기)은 빈 값, 새 리비전은 자기 확정일 기준
        long r2 = id(send(post(url + "/revise"), "", qe));
        assertThat(changeNos(F + "/" + r2)).isEmpty();
        release(F + "/" + r2);
        assertThat(changeNos(url)).isEmpty();
        // A사로 B사: B사 같은 품번에 적용된 ECO 는 A사 목록에 없다, B사 범위에서 A사 FMEA 는 404
        String b = accounts.newComp("A5");
        long bu = accounts.newUser(b, "qe1", PW);
        grantAll(b, bu);
        fixture(b);
        eco(b, "ECO-ZT-B", "DESIGN", "RELEASED", "now()");
        assertThat(changeNos(F + "/" + r2)).doesNotContain("ECO-ZT-B");
        mvc.perform(get(F + "/" + r2).with(accounts.as(b, bu, "qe1"))).andExpect(status().isNotFound());
    }

    @Test
    void 재검토_4M_영향특성_겹침_특별특성은_마스터_자기가_배포한_ECO는_따로_A사로B사() throws Exception {
        accounts.grant(comp, qeId, "QA", "qms-doc", "SEARCH", "SAVE", "APPROVE");
        policy("QMS_DOC_APPROVAL", "N");
        long fmea = draftFmea();                                   // 기능 특성 = ZP1(weldChar)
        String url = F + "/" + fmea;
        release(url);
        long other = accounts.query(comp, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, created_by, updated_by)
                values (?, 'PROCESS', (select proc_id from mst_proc where proc_cd = 'ZWELD'), 'ZP9', 'ZT 문서에 없는 특성', 'ATTRIBUTE', 't', 't')
                returning char_id""", Long.class, comp);
        eco(comp, "ECO-ZT-M1", "PROCESS_4M", "APPROVED", "now()", "MACHINE", weldChar, other);   // 겹침 ZP1 하나(문서에 없는 ZP9 는 빠짐)
        eco(comp, "ECO-ZT-M2", "PROCESS_4M", "APPROVED", "now()", "METHOD", other);              // 겹침 없음(빈 목록)
        eco(comp, "ECO-ZT-M3", "DESIGN", "RELEASED", "now()");                                   // 4M 세부 없음 = 비교 못 함(null)
        assertThat(changeNos(url)).containsExactly("ECO-ZT-M1", "ECO-ZT-M2", "ECO-ZT-M3");
        assertThat((List<String>) read(url, "$.data.changes.rows[0].overlap[*].charNo", qe)).containsExactly("ZP1");
        assertThat((List<String>) read(url, "$.data.changes.rows[0].overlap[*].charNm", qe)).containsExactly("ZT 용접 전류");
        assertThat((List<Boolean>) read(url, "$.data.changes.rows[0].overlap[*].special", qe)).containsExactly(false);
        assertThat((List<Object>) read(url, "$.data.changes.rows[1].overlap", qe)).isEmpty();
        assertThat((Object) read(url, "$.data.changes.rows[2].overlap", qe)).isNull();
        // 특별특성 = 마스터 원본(확정 뒤 마스터가 바뀌어도 지금 값)
        accounts.exec(comp, "update qms_char set special_cls = 'SC' where char_id = ?", weldChar);
        assertThat((List<Boolean>) read(url, "$.data.changes.rows[0].overlap[*].special", qe)).containsExactly(true);

        // E-3: 관리계획서 → QMS 문서(작성 중 리비전) → 그 리비전을 DOC 대상으로 담은 ECO(품목도 적음)가 적용됨
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PROTO\",\"title\":\"ZT 관리계획서\",\"pullFromFmea\":true}", qe));
        String cu = CP + "/" + cp;
        long line = ((Number) read(cu, "$.data.lines[0].lineId", qe)).longValue();
        send(put("/api/apqp/cp-lines/" + line), "{\"stepId\":" + step10 + ",\"charId\":" + weldChar + ",\"ctrlMethod\":\"INSPECT\",\"freqType\":\"EVERY_LOT\","
                + "\"reactionPlan\":\"보류\",\"rowVer\":" + ver(cu, "$.data.lines[0].rowVer") + "}", qe).andExpect(status().isOk());
        release(cu);
        publishDoc(cu, qe).andExpect(status().isOk());
        long cpRev = accounts.query(comp, "select doc_rev_id from apqp_qms_doc where src_kind = 'CP' and src_id = ?", Long.class, cp);
        long own = eco(comp, "ECO-ZT-D", "DESIGN", "DRAFT", null);
        accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, doc_rev_id, created_by, updated_by)
                values (?, ?, 2, 'DOC', (select item_id from mst_item where item_cd = 'ZT-BRKT'), ?, 't', 't')""", comp, own, cpRev);
        accounts.exec(comp, "update plm_eco set status = 'PENDING' where eco_id = ?", own);
        accounts.exec(comp, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", own);
        accounts.exec(comp, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", own);
        // 그 관리계획서: 재검토에서 빠지고 "배포한 ECO" 로, 겹침은 행 특성(ZP1)으로 같은 규칙
        assertThat(changeNos(cu)).containsExactly("ECO-ZT-M1", "ECO-ZT-M2", "ECO-ZT-M3");
        assertThat((List<String>) read(cu, "$.data.changes.own[*].ecoNo", qe)).containsExactly("ECO-ZT-D");
        assertThat((List<String>) read(cu, "$.data.changes.rows[0].overlap[*].charNo", qe)).containsExactly("ZP1");
        // 같은 품목의 다른 문서(PFMEA — 그 ECO 가 담은 문서가 아님)에는 그대로 재검토
        assertThat(changeNos(url)).contains("ECO-ZT-D");
        assertThat((List<String>) read(url, "$.data.changes.own", qe)).isEmpty();

        // A사로 B사: B사 ECO 는 A사 문서 리비전을 DOC 대상으로 담을 수 없다(PLM 트리거) — A사 표시도 그대로
        String b = accounts.newComp("A6");
        long bu = accounts.newUser(b, "qe1", PW);
        grantAll(b, bu);
        fixture(b);
        long be = eco(b, "ECO-ZT-B", "DESIGN", "DRAFT", null);
        assertThatThrownBy(() -> accounts.exec(b, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, doc_rev_id, created_by, updated_by)
                values (?, ?, 2, 'DOC', ?, 't', 't')""", b, be, cpRev)).hasMessageContaining("not of company");
        assertThat((List<String>) read(cu, "$.data.changes.own[*].ecoNo", qe)).containsExactly("ECO-ZT-D");
        mvc.perform(get(cu).with(accounts.as(b, bu, "qe1"))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ M4 확장 — 4M 영향 검사계획 겹침(11.6.2)

    /** QMS 검사계획(qa 사용자): 유형·공정(null = 공정 무관)·특성 한 줄 → 확정, 확정본 id */
    private long releasedPlan(RequestPostProcessor qa, String inspType, String procCd) throws Exception {
        long plan = id(send(post("/api/qms/insp-plans"), "{\"inspType\":\"" + inspType + "\",\"itemCd\":\"ZT-BRKT\""
                + (procCd == null ? "" : ",\"procCd\":\"" + procCd + "\"") + "}", qa));
        String ip = "/api/qms/insp-plans/" + plan;
        send(put(ip + "/lines?rowVer=" + planVer(ip, qa)), "{\"lines\":[{\"rowKey\":\"a\",\"lineNo\":10,\"charId\":" + weldChar
                + ",\"samplingPlanCd\":\"ZT-S5\",\"gaugeClass\":\"CALIPER\",\"freqType\":\"EVERY_LOT\",\"methodNm\":\"측정\",\"reactionPlan\":\"격리\"}]}", qa)
                .andExpect(status().isOk());
        send(post(ip + "/release"), "{\"rowVer\":" + planVer(ip, qa) + "}", qa).andExpect(status().isOk());
        return plan;
    }

    private int planVer(String ip, RequestPostProcessor who) throws Exception {
        return ((Number) JsonPath.read(mvc.perform(get(ip).with(who)).andReturn().getResponse().getContentAsString(), "$.data.plan.rowVer")).intValue();
    }

    /** 4M ECO(승인) — 영향 검사계획 리비전 id 들(QMS 4M 세부, 작성 중에만 넣을 수 있다) */
    private void ecoPlans(String c, String no, long... planIds) {
        long e = eco(c, no, "PROCESS_4M", "DRAFT", null, "MEASURE");
        int n = 0;
        for (long pid : planIds) {
            accounts.exec(c, "insert into qms_change_char (comp_cd, eco_id, line_no, insp_plan_id, created_by) values (?, ?, ?, ?, 't')", c, e, ++n, pid);
        }
        accounts.exec(c, "update plm_eco set status = 'PENDING' where eco_id = ?", e);
        accounts.exec(c, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", e);
    }

    /** ECO 한 줄의 검사계획 겹침 → "hits=[proc 유형 rev, …] unknown=n unrelated=m" (없으면 "none") — B·A 같은 틀 */
    private String planOf(String url, String ecoNo) throws Exception {
        List<Map<String, Object>> po = read(url, "$.data.changes.rows[?(@.ecoNo == '" + ecoNo + "')].planOverlap", qe);
        if (po.isEmpty() || po.get(0) == null) {
            return "none";
        }
        Map<String, Object> m = po.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) m.get("hits");
        return "hits=" + hits.stream().map(h -> h.get("procCd") + " " + h.get("inspType") + " " + h.get("rev")).toList()
                + " unknown=" + m.get("unknown") + " unrelated=" + m.get("unrelated");
    }

    @Test
    void 검사계획_겹침_같은_계획은_리비전_달라도_겹침_다른_계획은_무관_PFMEA는_없음_A사로B사() throws Exception {
        long qaId = accounts.newUser(comp, "qa1", PW);
        accounts.grant(comp, qaId, "QA", "qms-insp-plan", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor qa = accounts.as(comp, qaId, "qa1");
        accounts.exec(comp, """
                insert into qms_sampling_plan (comp_cd, plan_cd, plan_nm, method, fixed_n, ac, re, created_by, updated_by)
                values (?, 'ZT-S5', 'ZT 고정 5', 'FIXED', 5, 0, 1, 't', 't')""", comp);
        long proc = releasedPlan(qa, "PROCESS", "ZWELD");           // 관리계획서 대조가 볼 계획
        long fin = releasedPlan(qa, "FINAL", null);                 // 관리계획서에 출하검사 행 없음 = 이 관리계획서와 무관
        long procDraft = id(send(post("/api/qms/insp-plans/" + proc + "/new-revision"),
                "{\"rowVer\":" + planVer("/api/qms/insp-plans/" + proc, qa) + "}", qa));   // 같은 계획의 작성 중 새 리비전
        String procRev = JsonPath.read(mvc.perform(get("/api/qms/insp-plans/" + proc).with(qa)).andReturn().getResponse().getContentAsString(),
                "$.data.plan.rev");
        String draftRev = JsonPath.read(mvc.perform(get("/api/qms/insp-plans/" + procDraft).with(qa)).andReturn().getResponse().getContentAsString(),
                "$.data.plan.rev");
        long fmea = draftFmea();
        String url = F + "/" + fmea;
        release(url);
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PROTO\",\"title\":\"ZT\",\"pullFromFmea\":true}", qe));
        String cu = CP + "/" + cp;
        long line = ((Number) read(cu, "$.data.lines[0].lineId", qe)).longValue();
        send(put("/api/apqp/cp-lines/" + line), "{\"stepId\":" + step10 + ",\"charId\":" + weldChar + ",\"ctrlMethod\":\"INSPECT\",\"inspType\":\"PROCESS\","
                + "\"gaugeClass\":\"CALIPER\",\"freqType\":\"EVERY_LOT\",\"reactionPlan\":\"보류\",\"rowVer\":" + ver(cu, "$.data.lines[0].rowVer") + "}", qe)
                .andExpect(status().isOk());
        release(cu);
        assertThat((List<Object>) read(cu, "$.data.compare.plans[*].inspPlanId", qe)).containsExactly((int) proc);
        ecoPlans(comp, "ECO-ZT-P1", proc);              // 대조가 본 확정본 그대로
        ecoPlans(comp, "ECO-ZT-P2", procDraft);         // 같은 계획의 작성 중 새 리비전
        ecoPlans(comp, "ECO-ZT-P3", fin);               // 다른 계획(출하검사)
        ecoPlans(comp, "ECO-ZT-P4", fin, proc);         // 섞임(같은 계획 두 번은 QMS 가 막음 — uq_qms_change_char_ref)
        eco(comp, "ECO-ZT-P5", "PROCESS_4M", "APPROVED", "now()", "MAN");   // 검사계획 없음
        // A안(QMS keys — 같은 키 = 겹침, 다른 키 = 무관). B안(id 만 비교)에서는 P2 = unknown 1, P3·P4 의 출하검사 = unknown 이었다
        Map<String, String> expect = Map.of(
                "ECO-ZT-P1", "hits=[ZWELD PROCESS " + procRev + "] unknown=0 unrelated=0",
                "ECO-ZT-P2", "hits=[ZWELD PROCESS " + draftRev + "] unknown=0 unrelated=0",   // 같은 계획의 작성 중 새 리비전 — 그 rev 로
                "ECO-ZT-P3", "hits=[] unknown=0 unrelated=1",
                "ECO-ZT-P4", "hits=[ZWELD PROCESS " + procRev + "] unknown=0 unrelated=1",
                "ECO-ZT-P5", "none");
        for (var en : expect.entrySet()) {
            assertThat(planOf(cu, en.getKey())).as(en.getKey()).isEqualTo(en.getValue());
        }
        // PFMEA 는 검사계획 연결이 없어 늘 none(같은 ECO 가 재검토로 뜨더라도)
        assertThat((List<Object>) read(url, "$.data.changes.rows[?(@.ecoNo == 'ECO-ZT-P1')].planOverlap", qe)).containsExactly((Object) null);
        // A사로 B사: B사 4M(B사 자기 검사계획)은 A사 관리계획서에 없다, B사 사용자는 A사 관리계획서 404
        String b = accounts.newComp("A9");
        long bu = accounts.newUser(b, "qe1", PW);
        grantAll(b, bu);
        fixture(b);
        ecoPlans(b, "ECO-ZT-PB");
        assertThat(changeNos(cu)).doesNotContain("ECO-ZT-PB");
        mvc.perform(get(cu).with(accounts.as(b, bu, "qe1"))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ A3-6 준비 — 품목의 현행 확정본(계약)

    private List<String> current(String c, long itemId) {
        return runner.call(c, () -> lookup.current(itemId)).stream()
                .map(v -> v.rev().kind() + ":" + v.rev().no() + ":" + v.rev().rev() + ":" + v.phase() + ":" + (v.docRevId() != null)).toList();
    }

    @Test
    void 현행_확정본_계약_확정만_번호마다_하나_단계순_QMS문서_A사로B사() throws Exception {
        accounts.grant(comp, qeId, "QA", "qms-doc", "SEARCH", "SAVE", "APPROVE");
        policy("QMS_DOC_APPROVAL", "N");
        long item = accounts.query(comp, "select item_id from mst_item where item_cd = 'ZT-BRKT'", Long.class);
        // 작성 중만 = 빈 목록
        long fmea = draftFmea();
        String fmeaNo = read(F + "/" + fmea, "$.data.fmea.fmeaNo", qe);
        assertThat(current(comp, item)).isEmpty();
        release(F + "/" + fmea);
        // 관리계획서: 양산(작성 중 — 빠짐) / 시작품(확정 + QMS 문서)
        long prod = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PRODUCTION\",\"title\":\"ZT 양산\"}", qe));
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PROTO\",\"title\":\"ZT 시작품\",\"pullFromFmea\":true}", qe));
        String cu = CP + "/" + cp;
        String cpNo = read(cu, "$.data.cp.cpNo", qe);
        long line = ((Number) read(cu, "$.data.lines[0].lineId", qe)).longValue();
        send(put("/api/apqp/cp-lines/" + line), "{\"stepId\":" + step10 + ",\"charId\":" + weldChar + ",\"ctrlMethod\":\"INSPECT\",\"freqType\":\"EVERY_LOT\","
                + "\"reactionPlan\":\"보류\",\"rowVer\":" + ver(cu, "$.data.lines[0].rowVer") + "}", qe).andExpect(status().isOk());
        release(cu);
        publishDoc(cu, qe).andExpect(status().isOk());
        assertThat(prod).isPositive();
        assertThat(current(comp, item)).containsExactly("FMEA:" + fmeaNo + ":1:null:false", "CP:" + cpNo + ":1:PROTO:true");
        // 새 리비전 작성 중 = 확정 rev 1 그대로, 확정되면 rev 2 만(번호마다 하나 — 폐기 빠짐)
        long r2 = id(send(post(F + "/" + fmea + "/revise"), "", qe));
        assertThat(current(comp, item)).first().isEqualTo("FMEA:" + fmeaNo + ":1:null:false");
        release(F + "/" + r2);
        assertThat(current(comp, item)).containsExactly("FMEA:" + fmeaNo + ":2:null:false", "CP:" + cpNo + ":1:PROTO:true");
        // 없는 품목 = 빈 목록(예외 없음)
        assertThat(current(comp, -1L)).isEmpty();
        // A사로 B사: B사 범위에서 A사 품목 id = 빈 목록(RLS), B사 같은 품번은 따로
        String b = accounts.newComp("A8");
        long bu = accounts.newUser(b, "qe1", PW);
        grantAll(b, bu);
        fixture(b);
        assertThat(current(b, item)).isEmpty();
    }

    // ------------------------------------------------------------------ A3-5 조치 → PMS 이슈

    private long action(String url, String text) throws Exception {
        long risk = ((Number) read(url, "$.data.risks[0].riskId", qe)).longValue();
        return id(send(post(url + "/actions"), "{\"riskId\":" + risk + ",\"actionKind\":\"DETECT\",\"actionText\":\"" + text + "\","
                + "\"ownerLoginId\":\"qe1\",\"dueDate\":\"" + today.plusDays(20) + "\"}", qe));
    }

    private ResultActions toIssue(long actionId, String body, RequestPostProcessor who) throws Exception {
        return send(post("/api/pms/issues/from-apqp/" + actionId), body, who);
    }

    @Test
    void 조치를_이슈로_한번_복사_조치당_하나_상태는_따로_프로젝트규칙_권한_A사로B사() throws Exception {
        long proj = project();
        long fmea = draftFmea();
        String url = F + "/" + fmea;
        accounts.exec(comp, "update apqp_fmea set proj_id = ? where fmea_id = ?", proj, fmea);
        String fmeaNo = read(url, "$.data.fmea.fmeaNo", qe);
        long a1 = action(url, "ZT 지그 위치 검출");
        assertThat((Boolean) read(url, "$.data.issueView", qe)).isTrue();
        assertThat((Boolean) read(url, "$.data.issueSave", qe)).isTrue();
        // 만들기 → 복사된 칸(제목·심각도 AP H → HIGH·담당·기한·품목·출처)
        long issue = id(toIssue(a1, "{}", qe));
        String row = accounts.query(comp, """
                select concat_ws('|', i.title, i.severity, i.source, i.apqp_action_id, i.proj_id, i.obj_type, u.login_id, i.due_date, i.status,
                                 i.obj_id = f.item_id)
                  from pms_issue i join apqp_fmea f on f.fmea_id = ? left join sys_user u on u.user_id = i.owner_user_id
                 where i.issue_id = ?""", String.class, fmea, issue);
        assertThat(row).isEqualTo(String.join("|", "[" + fmeaNo + " rev 1] ZT 지그 위치 검출", "HIGH", "APQP", String.valueOf(a1),
                String.valueOf(proj), "ITEM", "qe1", today.plusDays(20).toString(), "OPEN", "t"));
        // FMEA 화면 표시용 — 조치 id 로 연결 이슈
        mvc.perform(get("/api/pms/issues/by-apqp-actions").param("ids", a1 + ",999999999").with(qe))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].actionId").value(a1))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"));
        // 조치당 하나(두 번째 409 + 기존 번호), 직접 SQL 로 근거 조치 바꾸기 → 고정
        toIssue(a1, "{}", qe).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PMS_APQP_ISSUE_EXISTS"));
        assertThat(catchThrowable(() -> accounts.exec(comp, "update pms_issue set apqp_action_id = ? where issue_id = ?", a1 + 1, issue)))
                .hasMessageContaining("fixed");
        // 상태는 따로: 이슈 해결 → 조치는 그대로 열림(L7)
        send(post("/api/pms/issues/" + issue + "/status"), "{\"status\":\"RESOLVED\",\"resolution\":\"지그 교체\",\"rowVer\":0}", qe)
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from apqp_fmea_action where action_id = ?", String.class, a1)).isEqualTo("OPEN");
        // 프로젝트 규칙: FMEA 에 프로젝트가 있으면 다른 projId 거부, 없으면 요청 projId 필수
        long a2 = action(url, "ZT 토크 관리");
        toIssue(a2, "{\"projId\":999999999}", qe).andExpect(jsonPath("$.errors[0].code").value("PMS_APQP_PROJ_MISMATCH"));
        accounts.exec(comp, "update apqp_fmea set proj_id = null where fmea_id = ?", fmea);
        toIssue(a2, "{}", qe).andExpect(jsonPath("$.errors[0].field").value("projId"));
        toIssue(a2, "{\"projId\":999999999}", qe).andExpect(jsonPath("$.errors[0].field").value("projId"));
        id(toIssue(a2, "{\"projId\":" + proj + "}", qe));
        // 열린 조치만
        long a3 = action(url, "ZT 끝난 조치");
        accounts.exec(comp, "update apqp_fmea_action set status = 'DONE', done_on = current_date where action_id = ?", a3);
        toIssue(a3, "{\"projId\":" + proj + "}", qe).andExpect(jsonPath("$.errors[0].code").value("PMS_APQP_ACTION_CLOSED"));
        // 권한: pms-issue SAVE 있어도 PFMEA 조회 권한 없으면 403 / pms-issue SAVE 없으면 403, 없는 조치 404
        long a4 = action(url, "ZT 권한 시험");
        long pm = accounts.newUser(comp, "pm1", PW);
        accounts.grant(comp, pm, "PMS", "pms-issue", "SEARCH", "SAVE");
        toIssue(a4, "{\"projId\":" + proj + "}", accounts.as(comp, pm, "pm1")).andExpect(status().isForbidden());
        long viewer = accounts.newUser(comp, "fv1", PW);
        accounts.grant(comp, viewer, "APQPV", "apqp-fmea", "SEARCH");
        accounts.grant(comp, viewer, "APQPV", "pms-issue", "SEARCH");
        toIssue(a4, "{\"projId\":" + proj + "}", accounts.as(comp, viewer, "fv1")).andExpect(status().isForbidden());
        assertThat((Boolean) read(url, "$.data.issueSave", accounts.as(comp, viewer, "fv1"))).isFalse();
        toIssue(999_999_999L, "{\"projId\":" + proj + "}", qe).andExpect(status().isNotFound());
        // A사로 B사: B사 사용자로 A사 조치 → 404, B사 범위에서 연결 이슈 조회 → 빈 값, 직접 SQL 로 B사 이슈에 A사 조치 → 트리거
        String other = accounts.newComp("A8");
        long bu = accounts.newUser(other, "qe1", PW);
        grantAll(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "qe1");
        id(send(post("/api/pms/templates/default-apqp"), "{}", b));
        long bProj = id(send(post(P), "{\"projNm\":\"B\",\"projType\":\"APQP\",\"tmplCd\":\"APQP-STD\",\"sopDate\":\"" + today.plusDays(400) + "\"}", b));
        toIssue(a4, "{\"projId\":" + bProj + "}", b).andExpect(status().isNotFound());
        mvc.perform(get("/api/pms/issues/by-apqp-actions").param("ids", String.valueOf(a1)).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(catchThrowable(() -> accounts.exec(other, """
                insert into pms_issue (comp_cd, issue_no, proj_id, title, source, apqp_action_id, created_by, updated_by)
                values (?, 'ZT-B-1', ?, 'x', 'APQP', ?, 't', 't')""", other, bProj, a4))).hasMessageContaining("is not of company");
    }

    // ------------------------------------------------------------------ A3-3 QMS 관리문서로 올리기

    private ResultActions publishDoc(String url, RequestPostProcessor who) throws Exception {
        return send(post(url + "/qms-doc"), "{}", who);
    }

    @Test
    void 확정본만_QMS_문서로_같은번호는_같은문서_새리비전_멱등_권한_A사로B사() throws Exception {
        accounts.grant(comp, qeId, "QA", "qms-doc", "SEARCH", "SAVE", "APPROVE");
        policy("QMS_DOC_APPROVAL", "N");
        long fmea = draftFmea();
        String url = F + "/" + fmea;
        String fmeaNo = read(url, "$.data.fmea.fmeaNo", qe);
        // 작성 중: 올릴 수 없음(단추 없음 + API 409)
        mvc.perform(get(url + "/qms-doc").with(qe)).andExpect(jsonPath("$.data.canPublish").value(false))
                .andExpect(jsonPath("$.data.linked").doesNotExist());
        publishDoc(url, qe).andExpect(jsonPath("$.code").value("APQP_FMEA_STATE"));
        release(url);
        mvc.perform(get(url + "/qms-doc").with(qe)).andExpect(jsonPath("$.data.canPublish").value(true));
        // 올리기 → 새 QMS 문서(유형 PFMEA·이름 = 제목·대상 품목), 작성 중 리비전, 양식 엑셀 1개
        String docNo = JsonPath.read(publishDoc(url, qe).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.docNo");
        long revId = accounts.query(comp, "select doc_rev_id from apqp_qms_doc where src_kind = 'FMEA' and src_id = ?", Long.class, fmea);
        assertThat(accounts.query(comp, """
                select concat_ws('|', d.doc_type, d.doc_nm, r.status, (select count(*) from mst_doc_target t join mst_item i on i.item_id = t.item_id
                                                                      where t.doc_id = d.doc_id and i.item_cd = 'ZT-BRKT'))
                  from mst_doc d join mst_doc_rev r on r.doc_id = d.doc_id where r.doc_rev_id = ?""", String.class, revId))
                .isEqualTo("PFMEA|ZT PFMEA|DRAFT|1");
        assertThat(accounts.query(comp, "select concat_ws('|', count(*), min(ext), min(orig_name)) from sys_file where target_type = 'qms-doc' "
                + "and target_id = ? and use_yn = 'Y'", String.class, String.valueOf(revId))).isEqualTo("1|xlsx|" + fmeaNo + "_rev1.xlsx");
        // 멱등: 다시 눌러도 같은 리비전·파일 그대로, 화면 = 연결 표시·단추 없음
        publishDoc(url, qe).andExpect(jsonPath("$.data.docRevId").value(revId));
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'qms-doc' and target_id = ?", Long.class,
                String.valueOf(revId))).isEqualTo(1L);
        mvc.perform(get(url + "/qms-doc").with(qe)).andExpect(jsonPath("$.data.canPublish").value(false))
                .andExpect(jsonPath("$.data.linked.docNo").value(docNo)).andExpect(jsonPath("$.data.linked.status").value("DRAFT"));
        // 새 리비전 확정 → QMS 에 작성 중이 남아 있으면 거부(행 없음), QMS 에서 배포한 뒤엔 같은 문서의 새 리비전
        long r2 = id(send(post(url + "/revise"), "", qe));
        release(F + "/" + r2);
        publishDoc(F + "/" + r2, qe).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_DOC_OPEN_EXISTS"));
        assertThat(accounts.query(comp, "select count(*) from apqp_qms_doc where src_id = ?", Long.class, r2)).isZero();
        int ver = accounts.query(comp, "select row_ver from mst_doc_rev where doc_rev_id = ?", Integer.class, revId);
        send(post("/api/qms/docs/revisions/" + revId + "/release"), "{\"rowVer\":" + ver + "}", qe).andExpect(status().isOk());
        publishDoc(F + "/" + r2, qe).andExpect(jsonPath("$.data.docNo").value(docNo));
        assertThat(accounts.query(comp, "select count(*) from mst_doc where doc_type = 'PFMEA'", Long.class)).isEqualTo(1L);
        assertThat(accounts.query(comp, "select count(*) from mst_doc_rev r join mst_doc d on d.doc_id = r.doc_id where d.doc_no = ?", Long.class,
                docNo)).isEqualTo(2L);
        // 폐기된 rev 1 은 더 올릴 수 없음(이미 올린 것은 표시만)
        mvc.perform(get(url + "/qms-doc").with(qe)).andExpect(jsonPath("$.data.canPublish").value(false));
        // 관리계획서도 같은 흐름(유형 CP)
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + r2 + ",\"phase\":\"PROTO\",\"title\":\"ZT 관리계획서\",\"pullFromFmea\":true}", qe));
        String cu = CP + "/" + cp;
        long line = ((Number) read(cu, "$.data.lines[0].lineId", qe)).longValue();
        send(put("/api/apqp/cp-lines/" + line), "{\"stepId\":" + step10 + ",\"charId\":" + weldChar + ",\"ctrlMethod\":\"INSPECT\",\"freqType\":\"EVERY_LOT\","
                + "\"reactionPlan\":\"보류\",\"rowVer\":" + ver(cu, "$.data.lines[0].rowVer") + "}", qe).andExpect(status().isOk());
        release(cu);
        publishDoc(cu, qe).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from mst_doc where doc_type = 'CP'", Long.class)).isEqualTo(1L);
        // 권한: QMS 문서 SAVE 없음 → 403(화면 단추 없음), PRINT 없음 → 403
        long u2 = accounts.newUser(comp, "fx1", PW);
        accounts.grant(comp, u2, "APQP2", "apqp-fmea", "SEARCH", "SAVE", "APPROVE", "PRINT");
        RequestPostProcessor x = accounts.as(comp, u2, "fx1");
        long r3 = id(send(post(url.replace(String.valueOf(fmea), String.valueOf(r2)) + "/revise"), "", qe));
        release(F + "/" + r3);
        mvc.perform(get(F + "/" + r3 + "/qms-doc").with(x)).andExpect(jsonPath("$.data.canPublish").value(false));
        publishDoc(F + "/" + r3, x).andExpect(status().isForbidden());
        long u3 = accounts.newUser(comp, "fx2", PW);
        accounts.grant(comp, u3, "APQP3", "apqp-fmea", "SEARCH");
        accounts.grant(comp, u3, "APQP3", "qms-doc", "SEARCH", "SAVE");
        publishDoc(F + "/" + r3, accounts.as(comp, u3, "fx2")).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select count(*) from apqp_qms_doc where src_id = ?", Long.class, r3)).isZero();
        // 연결된 QMS 문서가 사용 중지되면 막기는 그대로, 알아볼 수 있는 코드·문구(QA #495 b) — 연결 행 없음
        accounts.exec(comp, "update mst_doc set use_yn = 'N' where doc_no = ?", docNo);
        publishDoc(F + "/" + r3, qe).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APQP_QMS_DOC_INACTIVE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(docNo)));
        assertThat(accounts.query(comp, "select count(*) from apqp_qms_doc where src_id = ?", Long.class, r3)).isZero();
        // 넣기만: 직접 SQL 로 고치기·지우기 거부, 작성 중 원본으로 넣기 거부
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_qms_doc set doc_no = 'X' where src_id = ?", fmea))).hasMessageContaining("fixed");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from apqp_qms_doc where src_id = ?", fmea))).hasMessageContaining("fixed");
        // A사로 B사: B사 사용자로 A사 FMEA → 404, B사 범위에서 A사 연결 행 0건, 직접 SQL 로 A사 FMEA 를 원본으로 → 트리거
        String other = accounts.newComp("A9");
        long bu = accounts.newUser(other, "qe1", PW);
        grantAll(other, bu);
        accounts.grant(other, bu, "QA", "qms-doc", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "qe1");
        publishDoc(F + "/" + r3, b).andExpect(status().isNotFound());
        mvc.perform(get(F + "/" + r3 + "/qms-doc").with(b)).andExpect(status().isNotFound());
        assertThat(accounts.query(other, "select count(*) from apqp_qms_doc", Long.class)).isZero();
        assertThat(catchThrowable(() -> accounts.exec(other, """
                insert into apqp_qms_doc (comp_cd, src_kind, src_id, src_no, src_rev, doc_id, doc_no, doc_rev_id, created_by, updated_by)
                values (?, 'FMEA', ?, ?, 3, 1, 'X', 1, 't', 't')""", other, r3, fmeaNo))).hasMessageContaining("is not a released revision");
    }

    // ------------------------------------------------------------------ 11.9 라우팅 단계 다시 잇기

    private long routingRev(String rev, String validFrom) {
        return accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'ZT-BRKT'), 'P1', ?, ?::date, 't', 't') returning routing_id""",
                Long.class, comp, rev, validFrom);
    }

    private long step(long routingId, int opSeq, String procCd) {
        return accounts.query(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, ?, (select proc_id from mst_proc where proc_cd = ?), 't', 't') returning step_id""", Long.class, comp, routingId,
                opSeq, procCd);
    }

    private ResultActions relink(long fmea, long routingId, int rowVer) throws Exception {
        return send(post(F + "/" + fmea + "/routing"), "{\"routingId\":" + routingId + ",\"rowVer\":" + rowVer + "}", qe);
    }

    @Test
    void 새_리비전에서_라우팅_바꾸기_같은순서_같은공정만_잇고_나머지는_글자_관리계획서는_따라감() throws Exception {
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'ZPAINT', 'ZT 도장', 'PAINT', 't', 't')",
                comp);
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'ZASSY', 'ZT 조립', 'ASSEMBLY', 't', 't')",
                comp);
        long a0s20 = step(routing, 20, "ZPAINT");
        long fmea = draftFmea();                                   // 단계 노드 10(용접)·20(도장) — 라우팅 A0
        String url = F + "/" + fmea;
        release(url);
        // 관리계획서(A0): 10 용접 행(특성) + 20 도장 행(글자 특성) → 확정
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"PROTO\",\"title\":\"ZT\",\"pullFromFmea\":true}", qe));
        String cu = CP + "/" + cp;
        long line = ((Number) read(cu, "$.data.lines[0].lineId", qe)).longValue();
        send(put("/api/apqp/cp-lines/" + line), "{\"stepId\":" + step10 + ",\"charId\":" + weldChar + ",\"ctrlMethod\":\"INSPECT\",\"freqType\":\"EVERY_LOT\","
                + "\"reactionPlan\":\"보류\",\"rowVer\":" + ver(cu, "$.data.lines[0].rowVer") + "}", qe).andExpect(status().isOk());
        id(send(post(cu + "/lines"), "{\"stepId\":" + a0s20 + ",\"charText\":\"ZT 도막\",\"ctrlMethod\":\"INSPECT\",\"freqType\":\"EVERY_LOT\","
                + "\"reactionPlan\":\"재도장\"}", qe));
        release(cu);
        // 라우팅 새 리비전: A0 끝 → A1(10 용접 그대로, 20 은 조립으로 바뀜) 확정, A2 는 작성 중
        accounts.exec(comp, "update mst_routing set valid_to = ?, status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?",
                today.plusDays(30), routing);
        long a1 = routingRev("A1", today.plusDays(30).toString());
        long a1s10 = step(a1, 10, "ZWELD");
        step(a1, 20, "ZASSY");
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", a1);
        long a2 = routingRev("A2", today.plusDays(60).toString());
        step(a2, 10, "ZWELD");
        // 새 PFMEA 리비전(작성 중, A0) — 재검토 표시가 떠 있다
        long r2 = id(send(post(url + "/revise"), "", qe));
        String u2 = F + "/" + r2;
        assertThat((Boolean) read(u2, "$.data.fmea.routingStale", qe)).isTrue();
        // 미리보기: 10 은 짝, 20 은 글자("20 ZT 도장") — 바꾸지 않는다
        mvc.perform(get(u2 + "/routing-relink").param("routingId", String.valueOf(a1)).with(qe))
                .andExpect(jsonPath("$.data.matched").value(1)).andExpect(jsonPath("$.data.unmatched").value(1))
                .andExpect(jsonPath("$.data.steps[?(@.opSeq == 10)].toStepId").value(org.hamcrest.Matchers.contains((int) a1s10)))
                .andExpect(jsonPath("$.data.steps[?(@.opSeq == 20)].textNm").value(org.hamcrest.Matchers.contains("20 ZT 도장")));
        assertThat(((Number) read(u2, "$.data.fmea.routingId", qe)).longValue()).isEqualTo(routing);
        // 고르기 규칙: 작성 중 라우팅 / 지금 라우팅 / 없는 라우팅 / 빈 값
        mvc.perform(get(u2 + "/routing-relink").param("routingId", String.valueOf(a2)).with(qe))
                .andExpect(jsonPath("$.errors[0].code").value("APQP_ROUTING_NOT_RELEASED"));
        mvc.perform(get(u2 + "/routing-relink").param("routingId", String.valueOf(routing)).with(qe))
                .andExpect(jsonPath("$.errors[0].code").value("APQP_ROUTING_SAME"));
        mvc.perform(get(u2 + "/routing-relink").param("routingId", "999999999").with(qe)).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 적용: 미리보기 뒤 PFMEA 가 고쳐졌으면(rowVer 다름) 409
        int rv = ver(u2, "$.data.fmea.rowVer");
        relink(r2, a1, rv - 1).andExpect(status().isConflict());
        int risks = this.<List<Object>>read(u2, "$.data.risks", qe).size();
        relink(r2, a1, rv).andExpect(status().isOk()).andExpect(jsonPath("$.data.matched").value(1));
        assertThat(((Number) read(u2, "$.data.fmea.routingId", qe)).longValue()).isEqualTo(a1);
        assertThat((Boolean) read(u2, "$.data.fmea.routingStale", qe)).isFalse();
        List<Map<String, Object>> steps = read(u2, "$.data.nodes[?(@.nodeKind == 'STEP')]", qe);
        assertThat(steps).extracting(n -> n.get("stepId") == null ? null : ((Number) n.get("stepId")).longValue()).containsExactly(a1s10, null);
        assertThat(steps.get(1).get("nodeNm")).isEqualTo("20 ZT 도장");
        assertThat(this.<List<Object>>read(u2, "$.data.risks", qe)).hasSize(risks);
        // 감사 기록: 글자로 바뀐 노드의 옛 단계 id 가 남아 다시 이을 수 있다
        long n20 = ((Number) steps.get(1).get("nodeId")).longValue();
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'apqp_fmea_node' and action = 'UPDATE' "
                + "and row_key like ? and before_data::text like ?", Long.class, "%" + n20, "%\"step_id\": " + a0s20 + "%")).isEqualTo(1L);
        // 확정 뒤에는 바꾸기 거부
        release(u2);
        relink(r2, a1, ver(u2, "$.data.fmea.rowVer")).andExpect(jsonPath("$.code").value("APQP_FMEA_STATE"));
        // 관리계획서 새 리비전 + 근거를 새 PFMEA 로 → 라우팅 A1, 10 행은 새 단계, 20 행은 글자 단계
        long cp2 = id(send(post(cu + "/revise"), "{\"fmeaId\":" + r2 + "}", qe));
        String cu2 = CP + "/" + cp2;
        assertThat(((Number) read(cu2, "$.data.cp.routingId", qe)).longValue()).isEqualTo(a1);
        List<Map<String, Object>> lines = read(cu2, "$.data.lines", qe);
        assertThat(lines).extracting(l -> l.get("stepId") == null ? null : ((Number) l.get("stepId")).longValue()).containsExactly(a1s10, null);
        assertThat(lines.get(1).get("stepText")).isEqualTo("20 ZT 도장");
        // 원본 리비전 행은 그대로(옛 단계)
        assertThat(this.<List<Object>>read(cu, "$.data.lines[?(@.stepId == " + a0s20 + ")]", qe)).hasSize(1);
        // A사로 B사: B사 라우팅 id → 없음
        String other = accounts.newComp("AB");
        long[] bfx = fixture(other);
        long r3 = id(send(post(u2 + "/revise"), "", qe));
        mvc.perform(get(F + "/" + r3 + "/routing-relink").param("routingId", String.valueOf(bfx[0])).with(qe))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
    }
}
