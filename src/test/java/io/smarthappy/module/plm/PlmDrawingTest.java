package io.smarthappy.module.plm;

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
 * PLM 2단계 (docs/39 15장) — 도면 대상(QMS 문서 리비전, 적용 = DocService.releaseFromChange), 상신 뒤 첨부 고정(D7, FileLockRule +
 * locked_yn), 결재 뒤 도면 변경 거부, PPAP 증빙 첨부, 값·A사로 B사·직접 SQL.
 */
@AutoConfigureMockMvc
class PlmDrawingTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";
    private static final String DOC = "/api/qms/docs";
    private static final String APR = "/api/platform/approvals";
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long ownerId;
    long approverId;
    RequestPostProcessor owner;
    RequestPostProcessor approver;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PLD");
        ownerId = accounts.newUser(comp, "eng1", PW);
        approverId = accounts.newUser(comp, "mgr1", PW);
        for (long u : new long[] {ownerId, approverId}) {
            accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
            accounts.grant(comp, u, "ENG", "qms-doc", "SEARCH", "SAVE", "APPROVE", "DELETE");
        }
        owner = accounts.as(comp, ownerId, "eng1");
        approver = accounts.as(comp, approverId, "mgr1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        seed(comp);
    }

    private void seed(String c) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_DOC_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', 'C1 고객', 'SALES', 't', 't')""", c);
    }

    // ---------- 도우미 ----------

    private long id(String c, String sql, Object... args) {
        return accounts.query(c, sql, Long.class, args);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    private static long data(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int ver(String table, String key, long id) {
        return accounts.query(comp, "select row_ver from " + table + " where " + key + " = ?", Integer.class, id);
    }

    private int ecoVer(long eco) {
        return ver("plm_eco", "eco_id", eco);
    }

    private ResultActions upload(RequestPostProcessor who, String targetType, String targetId) throws Exception {
        return mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "도면.pdf", "application/pdf", PDF))
                .param("targetType", targetType).param("targetId", targetId).with(who).with(csrf()));
    }

    /** 문서(유형) + A0 배포 + A1 작성 중(첨부 하나). [docNo, A0, A1] */
    private Object[] drawing(RequestPostProcessor who, String type) throws Exception {
        long docId = data(mvc.perform(json(post(DOC), "{\"docType\":\"" + type + "\",\"docNm\":\"브래킷 도면\",\"targets\":[]}").with(who)));
        long a0 = id(comp, "select doc_rev_id from mst_doc_rev where doc_id = ?", docId);
        upload(who, "qms-doc", String.valueOf(a0)).andExpect(status().isOk());
        postAs(who, DOC + "/revisions/" + a0 + "/release", "{\"rowVer\":" + ver("mst_doc_rev", "doc_rev_id", a0) + "}").andExpect(status().isOk());
        long a1 = data(postAs(who, DOC + "/" + docId + "/revisions", "{\"changeSummary\":\"두께 변경\"}"));
        String no = accounts.query(comp, "select doc_no from mst_doc where doc_id = ?", String.class, docId);
        return new Object[] {no, a0, a1};
    }

    private long newEco() throws Exception {
        return data(postAs(owner, ECO, "{\"title\":\"도면 변경\",\"reasonCd\":\"DESIGN\",\"effectiveDate\":\"" + today + "\"}"));
    }

    private ResultActions saveTargets(long eco, String targets) throws Exception {
        return mvc.perform(json(put(ECO + "/" + eco + "/targets"), "{\"rowVer\":" + ecoVer(eco) + ",\"targets\":" + targets + "}").with(owner));
    }

    private static String docTarget(String docNo) {
        return "{\"rowKey\":\"d\",\"targetType\":\"DOC\",\"itemCd\":\"FG\",\"refCd\":\"" + docNo + "\",\"changeDesc\":\"두께 2.0\"}";
    }

    private ResultActions submit(long eco) throws Exception {
        return postAs(owner, ECO + "/" + eco + "/submit", "{\"rowVer\":" + ecoVer(eco) + ",\"lines\":[{\"userId\":" + approverId + "}]}");
    }

    private long aprId(long eco) {
        return id(comp, "select apr_id from plm_eco where eco_id = ?", eco);
    }

    private String revStatus(long rev) {
        return accounts.query(comp, "select status from mst_doc_rev where doc_rev_id = ?", String.class, rev);
    }

    // ---------- 정상 ----------

    @Test
    void 도면_대상을_적용하면_작성_중_리비전이_배포되고_이전본은_폐기_첨부는_굳는다() throws Exception {
        Object[] d = drawing(owner, "DRAWING");
        long a0 = (long) d[1];
        long a1 = (long) d[2];
        long eco = newEco();
        upload(owner, "plm-eco", String.valueOf(eco)).andExpect(status().isOk());
        saveTargets(eco, "[" + docTarget(((String) d[0]).toLowerCase()) + "]").andExpect(status().isOk());
        mvc.perform(get(ECO + "/" + eco).with(owner))
                .andExpect(jsonPath("$.data.targets[0].targetType").value("DOC"))
                .andExpect(jsonPath("$.data.targets[0].docNo").value(d[0]))
                .andExpect(jsonPath("$.data.targets[0].rev").value("A1"))
                .andExpect(jsonPath("$.data.targets[0].refStatus").value("DRAFT"));
        submit(eco).andExpect(status().isOk());
        // D7: 상신 뒤 ECO 첨부·대상 도면 첨부 올리기 409
        upload(owner, "plm-eco", String.valueOf(eco)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        upload(owner, "qms-doc", String.valueOf(a1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        postAs(approver, APR + "/" + aprId(eco) + "/approve", "{}").andExpect(status().isOk());
        postAs(approver, ECO + "/" + eco + "/release", "{\"rowVer\":" + ecoVer(eco) + "}").andExpect(status().isOk());
        assertThat(revStatus(a1)).isEqualTo("RELEASED");
        assertThat(revStatus(a0)).isEqualTo("OBSOLETE");
        // 도면 첨부(QMS 배포가 표시) + ECO 첨부(PLM 적용이 표시) 모두 잠금 표시 — 직접 SQL 사용 중지 불가
        assertThat(accounts.query(comp, "select string_agg(distinct locked_yn, '') from sys_file where target_type = 'qms-doc' and target_id = ?",
                String.class, String.valueOf(a1))).isEqualTo("Y");
        long ecoFile = id(comp, "select file_id from sys_file where target_type = 'plm-eco' and target_id = ?", String.valueOf(eco));
        assertThat(accounts.query(comp, "select locked_yn from sys_file where file_id = ?", String.class, ecoFile)).isEqualTo("Y");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update sys_file set use_yn = 'N' where file_id = ?", ecoFile))))
                .contains("ck_sys_file_locked");
    }

    @Test
    void 반려하면_첨부가_다시_열리고_승인_뒤_도면이_바뀌면_적용이_거부된다() throws Exception {
        Object[] d = drawing(owner, "DRAWING");
        long a1 = (long) d[2];
        long eco = newEco();
        saveTargets(eco, "[" + docTarget((String) d[0]) + "]").andExpect(status().isOk());
        submit(eco).andExpect(status().isOk());
        upload(owner, "plm-eco", String.valueOf(eco)).andExpect(status().isConflict());
        postAs(approver, APR + "/" + aprId(eco) + "/reject", "{\"comment\":\"치수 누락\"}").andExpect(status().isOk());
        // 작성 중으로 돌아오면 다시 열림
        upload(owner, "plm-eco", String.valueOf(eco)).andExpect(status().isOk());
        upload(owner, "qms-doc", String.valueOf(a1)).andExpect(status().isOk());
        submit(eco).andExpect(status().isOk());
        postAs(approver, APR + "/" + aprId(eco) + "/approve", "{}").andExpect(status().isOk());
        // 승인 뒤 QMS 화면에서 리비전 내용 수정 → 적용 거부, 아무것도 배포·확정되지 않음
        mvc.perform(json(put(DOC + "/revisions/" + a1), "{\"changeSummary\":\"몰래 바꿈\",\"rowVer\":" + ver("mst_doc_rev", "doc_rev_id", a1) + "}")
                .with(owner)).andExpect(status().isOk());
        long target = id(comp, "select target_id from plm_eco_target where eco_id = ?", eco);
        postAs(approver, ECO + "/" + eco + "/release", "{\"rowVer\":" + ecoVer(eco) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLM_TARGET_CHANGED"))
                .andExpect(jsonPath("$.errors[0].rowKey").value("t" + target));
        assertThat(revStatus(a1)).isEqualTo("DRAFT");
        // 취소하면 ECO 첨부는 닫힌 채
        postAs(owner, ECO + "/" + eco + "/cancel", "{\"rowVer\":" + ecoVer(eco) + ",\"reason\":\"재검토\"}").andExpect(status().isOk());
        upload(owner, "plm-eco", String.valueOf(eco)).andExpect(status().isConflict());
    }

    @Test
    void 문서_결재_중이면_적용이_거부되고_전체_롤백() throws Exception {
        // QMS 결재 정책 Y 로 바꿔 ECO 승인 뒤 같은 리비전을 문서 결재에 올린다 — ECO 대상인 동안 첨부는 잠겨도 상신은 QMS 흐름
        Object[] d = drawing(owner, "DRAWING");
        long a1 = (long) d[2];
        long eco = newEco();
        saveTargets(eco, "[" + docTarget((String) d[0]) + "]").andExpect(status().isOk());
        submit(eco).andExpect(status().isOk());
        postAs(approver, APR + "/" + aprId(eco) + "/approve", "{}").andExpect(status().isOk());
        accounts.exec(comp, "update sys_policy set policy_value = 'Y' where policy_key = 'QMS_DOC_APPROVAL'");
        postAs(owner, DOC + "/revisions/" + a1 + "/submit", "{\"rowVer\":" + ver("mst_doc_rev", "doc_rev_id", a1)
                + ",\"lines\":[{\"userId\":" + approverId + "}]}").andExpect(status().isOk());
        postAs(approver, ECO + "/" + eco + "/release", "{\"rowVer\":" + ecoVer(eco) + "}").andExpect(status().isConflict());
        assertThat(revStatus(a1)).isEqualTo("IN_APPROVAL");
        assertThat(accounts.query(comp, "select status from plm_eco where eco_id = ?", String.class, eco)).isEqualTo("APPROVED");
    }

    @Test
    void PPAP_증빙은_작성_중에만_올리고_승인되면_굳는다() throws Exception {
        long eco = data(postAs(owner, ECO, "{\"title\":\"PPAP\",\"reasonCd\":\"DESIGN\",\"effectiveDate\":\"" + today
                + "\",\"custApprovalReqYn\":\"Y\",\"custCd\":\"C1\"}"));
        long ppap = data(postAs(owner, ECO + "/" + eco + "/ppap", "{\"submitLevel\":1}"));
        upload(owner, "plm-eco", "PPAP-" + ppap).andExpect(status().isOk());
        long ev = id(comp, "select file_id from sys_file where target_type = 'plm-eco' and target_id = ?", "PPAP-" + ppap);
        mvc.perform(json(put("/api/plm/ppaps/" + ppap), "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap)
                + ",\"elems\":[{\"elemNo\":18,\"reqCd\":\"S\",\"status\":\"DONE\",\"fileId\":" + ev + "}]}").with(owner)).andExpect(status().isOk());
        postAs(owner, "/api/plm/ppaps/" + ppap + "/submit", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + "}").andExpect(status().isOk());
        upload(owner, "plm-eco", "PPAP-" + ppap).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        postAs(approver, "/api/plm/ppaps/" + ppap + "/decide", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap)
                + ",\"decision\":\"APPROVED\",\"ref\":\"PSW-9\",\"decidedOn\":\"" + today + "\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select locked_yn from sys_file where file_id = ?", String.class, ev)).isEqualTo("Y");
        // 없는 패키지·형식이 틀린 대상 id 는 잠김
        upload(owner, "plm-eco", "PPAP-999999").andExpect(status().isConflict());
        upload(owner, "plm-eco", "PPAP-x").andExpect(status().isConflict());
    }

    @Test
    void 문서_유형_4종_품목_없는_문서_대상과_적용_대상_불일치_경고() throws Exception {
        // 작업표준서(공정·클래스에 걸린 문서) — 품목 없이, 관리계획서 — 품목 있지만 문서 적용 대상은 다른 품목(경고만)
        Object[] wi = drawing(owner, "WI");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG2', 'FG2 품목', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        long cpDoc = data(mvc.perform(json(post(DOC), "{\"docType\":\"CP\",\"docNm\":\"관리계획서\",\"targets\":[{\"targetType\":\"ITEM\",\"targetCd\":\"FG2\"}]}")
                .with(owner)));
        String cpNo = accounts.query(comp, "select doc_no from mst_doc where doc_id = ?", String.class, cpDoc);
        long eco = newEco();
        saveTargets(eco, "[{\"rowKey\":\"w\",\"targetType\":\"DOC\",\"refCd\":\"" + wi[0] + "\"}," + docTarget(cpNo).replace("\"d\"", "\"c\"") + "]")
                .andExpect(status().isOk());
        mvc.perform(get(ECO + "/" + eco).with(owner))
                .andExpect(jsonPath("$.data.targets[0].itemId").doesNotExist())
                .andExpect(jsonPath("$.data.targets[0].docTargetWarn").value(false))
                .andExpect(jsonPath("$.data.targets[1].itemCd").value("FG"))
                .andExpect(jsonPath("$.data.targets[1].docTargetWarn").value(true))
                .andExpect(jsonPath("$.data.quality").doesNotExist());
        // 상신(base_row_ver 고정 — 품목 없는 행도) → 승인 → 적용 = 두 문서 배포
        submit(eco).andExpect(status().isOk());
        postAs(approver, APR + "/" + aprId(eco) + "/approve", "{}").andExpect(status().isOk());
        postAs(approver, ECO + "/" + eco + "/release", "{\"rowVer\":" + ecoVer(eco) + "}").andExpect(status().isOk());
        assertThat(revStatus((long) wi[2])).isEqualTo("RELEASED");
        // 직접 SQL: 문서 아닌 대상에 품목 없음 → CHECK
        long eco2 = newEco();
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, created_by, updated_by)
                values (?, ?, 1, 'ITEM', 't', 't')""", comp, eco2)))).contains("ck_plm_eco_target_item_req");
    }

    @Test
    void PFMEA_문서는_대상으로_지정할_수_있고_목록_밖_유형은_그대로_거부() throws Exception {
        // 36장(PMS E-1): PFMEA 개정을 ECO 로 — API·DB 트리거 모두 허용
        Object[] pf = drawing(owner, "PFMEA");
        long eco = newEco();
        saveTargets(eco, "[" + docTarget((String) pf[0]) + "]").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from plm_eco_target where eco_id = ? and doc_rev_id = ?", Integer.class, eco, (long) pf[2]))
                .isEqualTo(1);
        // 목록 밖(OTHER) — API 칸 오류, 직접 SQL 은 트리거
        Object[] other = drawing(owner, "OTHER");
        long eco2 = newEco();
        saveTargets(eco2, "[" + docTarget((String) other[0]) + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DOC_TYPE"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, doc_rev_id, created_by, updated_by)
                values (?, ?, 1, 'DOC', ?, 't', 't')""", comp, eco2, (long) other[2])))).contains("ck_plm_eco_target_doc_type");
    }

    // ---------- 이상 사용 ----------

    @Test
    void E_도면_대상_값_검사() throws Exception {
        Object[] wi = drawing(owner, "OTHER");
        Object[] drw = drawing(owner, "DRAWING");
        long eco = newEco();
        saveTargets(eco, "[" + docTarget((String) wi[0]) + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DOC_TYPE"));
        saveTargets(eco, "[" + docTarget("NOPE-1") + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        saveTargets(eco, "[{\"rowKey\":\"d\",\"targetType\":\"DOC\",\"itemCd\":\"FG\"}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 없는 품목 코드는 문서 대상에서도 거부(비우는 것만 허용)
        saveTargets(eco, "[{\"rowKey\":\"d\",\"targetType\":\"DOC\",\"itemCd\":\"NOPE\",\"refCd\":\"" + drw[0] + "\"}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("itemCd"));
        // 작성 중 리비전 없음(배포본만)
        long docOnly = data(mvc.perform(json(post(DOC), "{\"docType\":\"DRAWING\",\"docNm\":\"배포만\",\"targets\":[]}").with(owner)));
        long only = id(comp, "select doc_rev_id from mst_doc_rev where doc_id = ?", docOnly);
        postAs(owner, DOC + "/revisions/" + only + "/release", "{\"rowVer\":" + ver("mst_doc_rev", "doc_rev_id", only) + "}").andExpect(status().isOk());
        String onlyNo = accounts.query(comp, "select doc_no from mst_doc where doc_id = ?", String.class, docOnly);
        saveTargets(eco, "[" + docTarget(onlyNo) + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NO_DRAFT_REV"));
        // 같은 리비전 두 지시
        saveTargets(eco, "[" + docTarget((String) drw[0]) + "]").andExpect(status().isOk());
        long eco2 = newEco();
        saveTargets(eco2, "[" + docTarget((String) drw[0]) + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("OPEN_ECO"));
        // 직접 SQL: 도면 아닌 문서 리비전·같은 리비전 다른 지시
        long wiRev = (long) wi[2];
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, doc_rev_id, created_by, updated_by)
                values (?, ?, 1, 'DOC', (select item_id from mst_item where item_cd = 'FG'), ?, 't', 't')""", comp, eco2, wiRev))))
                .contains("ck_plm_eco_target_doc_type");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, doc_rev_id, created_by, updated_by)
                values (?, ?, 1, 'DOC', (select item_id from mst_item where item_cd = 'FG'), ?, 't', 't')""", comp, eco2, (long) drw[2]))))
                .contains("uq_plm_eco_target_open");
    }

    @Test
    void B_A사로_B사_문서_리비전_첨부에_손댈_수_없다() throws Exception {
        String compB = accounts.newComp("PLDB");
        long userB = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, userB, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(compB, userB, "ENG", "qms-doc", "SEARCH", "SAVE", "APPROVE", "DELETE");
        seed(compB);
        RequestPostProcessor b = accounts.as(compB, userB, "engB");
        long docB = data(mvc.perform(json(post(DOC), "{\"docType\":\"DRAWING\",\"docNm\":\"B 도면\",\"targets\":[]}").with(b)));
        long revB = id(compB, "select doc_rev_id from mst_doc_rev where doc_id = ?", docB);
        String noB = accounts.query(compB, "select doc_no from mst_doc where doc_id = ?", String.class, docB);
        long ecoB = data(mvc.perform(json(post(ECO), "{\"title\":\"B\",\"reasonCd\":\"COST\",\"effectiveDate\":\"" + today
                + "\",\"custApprovalReqYn\":\"Y\",\"custCd\":\"C1\"}").with(b)));
        long ppapB = data(mvc.perform(json(post(ECO + "/" + ecoB + "/ppap"), "{}").with(b)));
        int filesB = accounts.query(compB, "select count(*) from sys_file", Integer.class);

        long eco = newEco();
        // A사 지시에 B사 문서 번호 — A사에도 같은 번호 문서가 없으면 NOT_FOUND (번호는 회사마다 채번이라 A사에 만들지 않는다)
        assertThat(accounts.query(comp, "select count(*) from mst_doc where doc_no = ?", Integer.class, noB)).isZero();
        saveTargets(eco, "[" + docTarget(noB) + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 직접 SQL: A사 대상에 B사 문서 리비전
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, doc_rev_id, created_by, updated_by)
                values (?, ?, 1, 'DOC', (select item_id from mst_item where item_cd = 'FG'), ?, 't', 't')""", comp, eco, revB))))
                .contains("fk_plm_eco_target_comp");
        // A사 사용자로 B사 ECO·PPAP·문서 리비전에 올리기 — 거부, B사 파일 그대로
        for (String[] t : new String[][] {{"plm-eco", String.valueOf(ecoB)}, {"plm-eco", "PPAP-" + ppapB}, {"qms-doc", String.valueOf(revB)}}) {
            int st = upload(owner, t[0], t[1]).andReturn().getResponse().getStatus();
            assertThat(st).as("%s %s", t[0], t[1]).isGreaterThanOrEqualTo(400);
        }
        assertThat(accounts.query(compB, "select count(*) from sys_file", Integer.class)).isEqualTo(filesB);
        // 직접 SQL: A사 PPAP 요소에 B사 패키지 증빙 이름의 파일은 A사 것이 아니면 거부(트리거)
        long ppap = data(postAs(owner, ECO + "/" + eco + "/ppap", "{\"custCd\":\"C1\"}"));
        long fileB = id(compB, """
                insert into sys_file (comp_cd, target_type, target_id, orig_name, ext, content_type, size_bytes, sha256, storage_key,
                                      created_by, updated_by)
                values (?, 'plm-eco', ?, 'b.pdf', 'pdf', 'application/pdf', 10, repeat('a', 64), ? || '/2026/10/' || gen_random_uuid(), 't', 't')
                returning file_id""", compB, "PPAP-" + ppap, compB);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update plm_ppap_elem set file_id = ? where ppap_id = ? and elem_no = 18", fileB, ppap)))).contains("fk_plm_ppap_elem_comp");
    }
}
