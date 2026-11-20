package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PMS 7단계 프로젝트 ↔ PLM ECO (docs/32 PM7·4.9, D17·D20) — PLM 계약 EcoLookup 으로 번호(대소문자 무시) 찾기, 한 ECO 한 프로젝트,
 * 없는 번호·취소 ECO·낡은 rowVer, PLM 권한 없는 PMO, 해제. 산출물 ↔ 프로젝트 ECO 의 PPAP(계약 EcoLookup.ppap — ECO·패키지 없음,
 * 연결 칸 파일·손 승인 거부, 잠정 승인은 반영 안 됨 → 재제출·고객 승인 뒤 반영, PPAP 연결 뒤 ECO 고정), 이슈 LOT 연결(계약 LotLookup —
 * 번호 그대로·없는 LOT·LOT 조건 찾기·목록 표시), A사로 B사(API·직접 SQL).
 */
@AutoConfigureMockMvc
class PmsPlmTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String D = "/api/pms/deliverables";
    private static final String IS = "/api/pms/issues";
    private static final String ECO = "/api/plm/ecos";
    private static final String PPAP = "/api/plm/ppaps";
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    RequestPostProcessor pm;
    /** PMS 는 PMO(모든 프로젝트)지만 PLM 권한이 없다 */
    RequestPostProcessor pmo;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PP");
        pmId = accounts.newUser(comp, "pm1", PW);
        long pmoId = accounts.newUser(comp, "pmo1", PW);
        grantPms(comp, pmId, "PMS");
        grantPms(comp, pmoId, "PMO");
        accounts.grant(comp, pmoId, "PMO", "pms-project", "APPROVE");
        accounts.grant(comp, pmId, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        pm = accounts.as(comp, pmId, "pm1");
        pmo = accounts.as(comp, pmoId, "pmo1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        seed(comp);
    }

    private void grantPms(String c, long user, String grp) {
        for (String prog : List.of("pms-project", "pms-template", "pms-gate", "pms-deliverable", "pms-issue")) {
            accounts.grant(c, user, grp, prog, "SEARCH", "SAVE");
        }
        accounts.grant(c, user, grp, "pms-deliverable", "APPROVE");
    }

    /** 판매 고객 C1(ECO 고객 승인용), 품목 FG(LOT 2개, 번호에 소문자) */
    private void seed(String c) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', '브래킷 ASSY', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        for (String no : List.of("ZT-L001", "zt-l002")) {
            accounts.exec(c, """
                    insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                    select ?, ?, item_id, 'RECEIVED', 10, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG'""", c, no);
        }
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', '미르전장', 'SALES', 't', 't')""", c);
    }

    // ------------------------------------------------------------------ 도우미

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(json(b, body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private static <T> T read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }

    private int ver(String table, String key, long id) {
        return accounts.query(comp, "select row_ver from " + table + " where " + key + " = ?", Integer.class, id);
    }

    private long newEco(RequestPostProcessor who) throws Exception {
        return id(send(post(ECO), "{\"title\":\"브래킷 신규\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today
                + "\",\"custApprovalReqYn\":\"Y\",\"custCd\":\"C1\"}", who));
    }

    private String ecoNo(long eco) {
        return accounts.query(comp, "select eco_no from plm_eco where eco_id = ?", String.class, eco);
    }

    private ResultActions linkEco(RequestPostProcessor who, long proj, String ecoNo) throws Exception {
        int v = ver("pms_project", "proj_id", proj);
        return send(put(P + "/" + proj + "/eco"), "{\"ecoNo\":" + (ecoNo == null ? "null" : "\"" + ecoNo + "\"") + ",\"rowVer\":" + v + "}", who);
    }

    private long apqpProject() throws Exception {
        id(send(post("/api/pms/templates/default-apqp"), "{}", pm));
        return id(send(post(P), "{\"projNm\":\"신규 브래킷\",\"projType\":\"APQP\",\"tmplCd\":\"APQP-STD\",\"sopDate\":\""
                + java.time.LocalDate.parse(today).plusDays(400) + "\"}", pm));
    }

    private Map<String, Object> deliv(long proj, String nm) throws Exception {
        List<Map<String, Object>> rows = read(mvc.perform(get(P + "/" + proj + "/deliverables").with(pm)),
                "$.data.rows[?(@.delivNm == '" + nm + "')]");
        return rows.get(0);
    }

    private static long num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).longValue();
    }

    private ResultActions linkPpap(RequestPostProcessor who, long proj, String nm) throws Exception {
        Map<String, Object> d = deliv(proj, nm);
        return send(post(D + "/" + num(d, "delivId") + "/ppap-link"), "{\"note\":\"PPAP\",\"rowVer\":" + num(d, "rowVer") + "}", who);
    }

    private int sync(long proj) throws Exception {
        return ((Number) read(send(post(P + "/" + proj + "/deliverables/sync-docs"), "{}", pm).andExpect(status().isOk()), "$.data")).intValue();
    }

    /** 레벨 1(PSW 만 S, 첨부 = ECO 첨부)로 맞춰 제출 */
    private void fillAndSubmit(long eco, long ppap) throws Exception {
        long psw = accounts.query(comp, """
                insert into sys_file (comp_cd, target_type, target_id, orig_name, ext, content_type, size_bytes, sha256, storage_key,
                                      created_by, updated_by)
                values (?, 'plm-eco', ?, 'psw.pdf', 'pdf', 'application/pdf', 10, repeat('a', 64), ? || '/2026/10/' || gen_random_uuid(), 't', 't')
                returning file_id""", Long.class, comp, String.valueOf(eco), comp);
        StringBuilder elems = new StringBuilder();
        for (int n = 1; n <= 17; n++) {
            elems.append("{\"elemNo\":").append(n).append(",\"reqCd\":\"").append(n == 13 ? "N" : "R").append("\",\"status\":\"OPEN\"},");
        }
        elems.append("{\"elemNo\":18,\"reqCd\":\"S\",\"status\":\"DONE\",\"fileId\":").append(psw).append("}");
        send(put(PPAP + "/" + ppap), "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + ",\"submitLevel\":1,\"elems\":[" + elems + "]}", pm)
                .andExpect(status().isOk());
        send(post(PPAP + "/" + ppap + "/submit"), "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + "}", pm).andExpect(status().isOk());
    }

    private void decide(long ppap, String decision) throws Exception {
        send(post(PPAP + "/" + ppap + "/decide"), "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + ",\"decision\":\"" + decision
                + "\",\"ref\":\"PSW-1\",\"decidedOn\":\"" + today + "\"}", pm).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ ① 프로젝트 ↔ ECO

    @Test
    void 프로젝트를_ECO_에_연결_번호로_찾고_한_ECO_한_프로젝트_취소_ECO_권한_해제() throws Exception {
        long eco = newEco(pm);
        long proj = id(send(post(P), "{\"projNm\":\"지그 4M\",\"projType\":\"CHANGE\"}", pm));
        linkEco(pm, proj, ecoNo(eco).toLowerCase()).andExpect(status().isOk());
        mvc.perform(get(P + "/" + proj).with(pm)).andExpect(jsonPath("$.data.ecoId").value(eco))
                .andExpect(jsonPath("$.data.ecoNo").value(ecoNo(eco))).andExpect(jsonPath("$.data.ecoStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.ecoTitle").value("브래킷 신규"));
        // 같은 ECO 를 다른 프로젝트에 → 거부, 없는 번호, 낡은 rowVer
        long proj2 = id(send(post(P), "{\"projNm\":\"다른 프로젝트\",\"projType\":\"CHANGE\"}", pm));
        linkEco(pm, proj2, ecoNo(eco)).andExpect(jsonPath("$.errors[0].code").value("PMS_ECO_TAKEN"));
        linkEco(pm, proj2, "NO-SUCH-ECO").andExpect(jsonPath("$.errors[0].field").value("ecoNo"))
                .andExpect(jsonPath("$.errors[0].code").value("PMS_ECO_NOT_FOUND"));
        send(put(P + "/" + proj2 + "/eco"), "{\"ecoNo\":\"" + ecoNo(eco) + "\",\"rowVer\":9}", pm).andExpect(status().isConflict());
        // 취소된 ECO
        long cancelled = newEco(pm);
        send(post(ECO + "/" + cancelled + "/cancel"), "{\"rowVer\":" + ver("plm_eco", "eco_id", cancelled) + ",\"reason\":\"중복\"}", pm)
                .andExpect(status().isOk());
        linkEco(pm, proj2, ecoNo(cancelled)).andExpect(jsonPath("$.errors[0].code").value("PMS_ECO_CANCELLED"));
        // PLM 권한 없는 PMO(D20) → 403
        linkEco(pmo, proj2, ecoNo(eco)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        // 해제 → 다른 프로젝트가 연결할 수 있다
        linkEco(pm, proj, null).andExpect(status().isOk());
        mvc.perform(get(P + "/" + proj).with(pm)).andExpect(jsonPath("$.data.ecoId").doesNotExist());
        linkEco(pm, proj2, ecoNo(eco)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ ② 산출물 ↔ PPAP

    @Test
    void 산출물을_프로젝트_ECO_의_PPAP_에_연결_잠정승인은_반영_안되고_고객_승인_뒤_반영() throws Exception {
        long proj = apqpProject();
        String nm = "PPAP 제출 승인서";
        linkPpap(pm, proj, nm).andExpect(jsonPath("$.errors[0].code").value("PMS_NO_ECO"));
        long eco = newEco(pm);
        linkEco(pm, proj, ecoNo(eco)).andExpect(status().isOk());
        linkPpap(pm, proj, nm).andExpect(jsonPath("$.errors[0].code").value("PMS_NO_PPAP"));
        long ppap = id(send(post(ECO + "/" + eco + "/ppap"), "{}", pm));
        // 작성 중 패키지 → 제출 중, 목록에 ECO 번호·PPAP 상태
        linkPpap(pm, proj, nm).andExpect(jsonPath("$.data.status").value("SUBMITTED")).andExpect(jsonPath("$.data.docStatus").value("DRAFT"));
        Map<String, Object> d = deliv(proj, nm);
        long dId = num(d, "delivId");
        assertThat(d.get("refType")).isEqualTo("PLM_PPAP");
        assertThat(d.get("ppapEcoNo")).isEqualTo(ecoNo(eco));
        assertThat(d.get("ppapStatus")).isEqualTo("DRAFT");
        // 연결 칸: 파일 제출·손 승인 거부, 같은 패키지 다시 연결 거부, 프로젝트 ECO 고정
        mvc.perform(multipart(D + "/" + dId + "/submit").file(new MockMultipartFile("file", "x.pdf", "application/pdf", PDF))
                .param("rowVer", String.valueOf(num(d, "rowVer"))).with(pm).with(csrf())).andExpect(jsonPath("$.code").value("PMS_DELIV_STATE"));
        send(post(D + "/" + dId + "/decide"), "{\"action\":\"APPROVE\",\"rowVer\":" + num(d, "rowVer") + "}", pm)
                .andExpect(jsonPath("$.code").value("PMS_DELIV_STATE"));
        linkPpap(pm, proj, nm).andExpect(jsonPath("$.errors[0].code").value("PMS_PPAP_SAME"));
        linkEco(pm, proj, null).andExpect(jsonPath("$.errors[0].code").value("PMS_ECO_FIXED"));
        Throwable t = catchThrowable(() -> accounts.exec(comp, "update pms_project set eco_id = null where proj_id = ?", proj));
        assertThat(PgErrors.constraint(t)).hasValue("ck_pms_project_eco_fixed");
        // 제출 → 잠정 승인: 승인으로 치지 않는다(D19)
        fillAndSubmit(eco, ppap);
        decide(ppap, "INTERIM");
        assertThat(sync(proj)).isZero();
        assertThat(deliv(proj, nm).get("status")).isEqualTo("SUBMITTED");
        assertThat(deliv(proj, nm).get("ppapStatus")).isEqualTo("INTERIM");
        // 다시 작성 중 → 재제출 → 고객 승인 → 반영(이력: 연결 → 승인)
        send(post(PPAP + "/" + ppap + "/reopen"), "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + "}", pm).andExpect(status().isOk());
        send(post(PPAP + "/" + ppap + "/submit"), "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + "}", pm).andExpect(status().isOk());
        decide(ppap, "APPROVED");
        assertThat(sync(proj)).isEqualTo(1);
        assertThat(deliv(proj, nm).get("status")).isEqualTo("APPROVED");
        List<Map<String, Object>> hist = read(mvc.perform(get(D + "/" + dId + "/history").with(pm)), "$.data");
        assertThat(hist).extracting(h -> h.get("action")).containsExactly("APPROVE", "LINK");
        assertThat(hist.get(0).get("note")).isEqualTo("PPAP APPROVED " + today);
        assertThat(hist.get(1).get("ppapId")).isEqualTo((int) ppap);
        // 이미 고객 승인된 패키지에 다른 칸 연결 → 바로 승인. PLM 권한 없는 PMO → 403
        linkPpap(pmo, proj, "MSA 결과").andExpect(status().isForbidden());
        linkPpap(pm, proj, "MSA 결과").andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(deliv(proj, "MSA 결과").get("status")).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ ③ 이슈 → LOT

    @Test
    void 이슈를_LOT_에_연결_번호는_그대로_없는_LOT_거부_LOT_조건_찾기() throws Exception {
        long proj = id(send(post(P), "{\"projNm\":\"LOT 시험\",\"projType\":\"GENERAL\"}", pm));
        long issue = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"초품 치수\",\"objType\":\"LOT\",\"objCd\":\"zt-l002\"}", pm));
        mvc.perform(get(IS + "/" + issue).with(pm)).andExpect(jsonPath("$.data.objType").value("LOT"))
                .andExpect(jsonPath("$.data.objCd").value("zt-l002")).andExpect(jsonPath("$.data.objNm").value("브래킷 ASSY"));
        // LOT 번호는 대소문자 그대로(대문자로 바꾸면 없는 LOT)
        send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"x\",\"objType\":\"LOT\",\"objCd\":\"ZT-L002\"}", pm)
                .andExpect(jsonPath("$.errors[0].field").value("objCd")).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(get(IS).param("objType", "LOT").param("objCd", "zt-l002").with(pm)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(IS).param("objType", "LOT").param("objCd", "ZT-L001").with(pm)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(IS).param("objType", "LOT").param("objCd", "NONE").with(pm)).andExpect(jsonPath("$.data.length()").value(0));
    }

    // ------------------------------------------------------------------ A사로 B사

    @Test
    void A사로_B사_ECO_연결_불가와_직접_SQL_다른_회사_참조_거부() throws Exception {
        long eco = newEco(pm);
        long aProj = id(send(post(P), "{\"projNm\":\"A\",\"projType\":\"CHANGE\"}", pm));
        linkEco(pm, aProj, ecoNo(eco)).andExpect(status().isOk());

        String other = accounts.newComp("PQ");
        long bId = accounts.newUser(other, "pm1", PW);
        grantPms(other, bId, "ALL");
        accounts.grant(other, bId, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(other, bId, "pm1");
        // A사 프로젝트 → 404, B사 프로젝트에 A사 ECO 번호 → 없음(계약이 RLS 로 빈 값)
        send(put(P + "/" + aProj + "/eco"), "{\"ecoNo\":null,\"rowVer\":1}", b).andExpect(status().isNotFound());
        long bProj = id(send(post(P), "{\"projNm\":\"B\",\"projType\":\"CHANGE\"}", b));
        send(put(P + "/" + bProj + "/eco"), "{\"ecoNo\":\"" + ecoNo(eco) + "\",\"rowVer\":0}", b)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_ECO_NOT_FOUND"));
        mvc.perform(get(P + "/" + aProj).with(b)).andExpect(status().isNotFound());
        // 직접 SQL(B사 범위): A사 ECO 를 가리키면 같은 회사 확인 트리거
        Throwable t = catchThrowable(() -> accounts.exec(other, "update pms_project set eco_id = ? where proj_id = ?", eco, bProj));
        assertThat(PgErrors.constraint(t)).hasValue("fk_pms_project_eco_comp");
        assertThat(accounts.query(comp, "select eco_id from pms_project where proj_id = ?", Long.class, aProj)).isEqualTo(eco);
        // PPAP: B사가 A사 산출물에 연결 → 404. A사 범위라도 ECO 가 다른(없는) 프로젝트 산출물에서 그 PPAP 를 가리키면 거부
        long ppap = id(send(post(ECO + "/" + eco + "/ppap"), "{}", pm));
        long aProj2 = id(send(post(P), "{\"projNm\":\"A2\",\"projType\":\"GENERAL\"}", pm));
        long task = id(send(post(P + "/" + aProj2 + "/tasks"), "{\"taskNm\":\"작업\",\"planStart\":\"" + today + "\",\"planEnd\":\"" + today + "\"}", pm));
        long d2 = id(send(post(P + "/" + aProj2 + "/deliverables"), "{\"taskId\":" + task + ",\"delivNm\":\"PPAP\"}", pm));
        send(post(D + "/" + d2 + "/ppap-link"), "{\"rowVer\":0}", b).andExpect(status().isNotFound());
        Throwable t3 = catchThrowable(() -> accounts.exec(comp, """
                update pms_deliverable set status = 'SUBMITTED', ref_type = 'PLM_PPAP', ref_id = ?, submitted_at = now(), sub_cnt = sub_cnt + 1
                 where deliv_id = ?""", ppap, d2));
        assertThat(PgErrors.constraint(t3)).hasValue("fk_pms_deliverable_ppap");
        // LOT: B사 이슈에 A사 LOT 번호 → 없음(계약이 RLS 로 빈 값), 직접 SQL 로 A사 LOT 을 가리키면 같은 회사 확인 트리거
        long aLot = accounts.query(comp, "select lot_id from lot_master where lot_no = 'ZT-L001'", Long.class);
        send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + bProj + ",\"title\":\"x\",\"objType\":\"LOT\",\"objCd\":\"ZT-L001\"}", b)
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        long bIssue = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + bProj + ",\"title\":\"B사 이슈\"}", b));
        Throwable t4 = catchThrowable(() -> accounts.exec(other, "update pms_issue set obj_type = 'LOT', obj_id = ? where issue_id = ?", aLot, bIssue));
        assertThat(PgErrors.constraint(t4)).hasValue("fk_pms_issue_obj_comp");
    }
}
