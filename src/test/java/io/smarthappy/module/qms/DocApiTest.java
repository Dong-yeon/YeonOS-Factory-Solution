package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.tenant.TenantRunner;

/**
 * P3 QMS Q3 관리문서·정합 점검 — 정상 흐름(결재 없는 배포·결재 승인/반려/회수·새 리비전 첨부 복사·배포본 첨부 잠금) + 검사계획 연결 +
 * 정합 점검 3종 + 이상 사용(A~K: A사로 B사, 직접 SQL 상태·불변·잠금, 입력, 권한, 멱등) + PLM 계약(releaseFromChange).
 */
class DocApiTest extends QmsTestBase {

    static final String D = "/api/qms/docs";
    static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    TenantRunner runner;

    @Autowired
    DocService docService;

    @BeforeEach
    void docSetUp() {
        accounts.grant(comp, userId, "QA", "qms-doc", "SEARCH", "SAVE", "APPROVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-consistency", "SEARCH", "EXCEL");
    }

    void approvalPolicy(String c, String yn) {
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_DOC_APPROVAL', ?, 't', 't')",
                c, yn);
    }

    long doc(String type, String targets) throws Exception {
        return id(postJson(D, "{\"docType\":" + q(type) + ",\"docNm\":\"" + type + " 문서\",\"targets\":[" + targets + "]}"));
    }

    static String target(String type, String cd) {
        return "{\"targetType\":" + q(type) + ",\"targetCd\":" + q(cd) + "}";
    }

    long openRev(long docId) {
        return accounts.query(comp, "select doc_rev_id from mst_doc_rev where doc_id = ? and status in ('DRAFT','IN_APPROVAL')", Long.class, docId);
    }

    String revStatus(long revId) {
        return accounts.query(comp, "select status from mst_doc_rev where doc_rev_id = ?", String.class, revId);
    }

    int revVer(long revId) {
        return accounts.query(comp, "select row_ver from mst_doc_rev where doc_rev_id = ?", Integer.class, revId);
    }

    ResultActions revAct(long revId, String act) throws Exception {
        return postJson(D + "/revisions/" + revId + "/" + act, "{\"rowVer\":" + revVer(revId) + "}");
    }

    ResultActions upload(RequestPostProcessor who, long revId) throws Exception {
        return mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "기준서.pdf", "application/pdf", PDF))
                .param("targetType", "qms-doc").param("targetId", String.valueOf(revId)).with(who).with(csrf()));
    }

    long fileId(long revId) {
        return accounts.query(comp, "select max(file_id) from sys_file where target_type = 'qms-doc' and target_id = ? and use_yn = 'Y'",
                Long.class, String.valueOf(revId));
    }

    ResultActions apr(long aprId, String action, RequestPostProcessor who, String comment) throws Exception {
        return mvc.perform(post("/api/platform/approvals/" + aprId + "/" + action).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(comment == null ? "{}" : "{\"comment\":\"" + comment + "\"}"));
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 결재_없는_배포_첨부_잠금_새리비전_첨부_복사_이전_배포본_폐기() throws Exception {
        approvalPolicy(comp, "N");
        long docId = doc("INSP_STD", target("ITEM", "fg1"));
        String d = mvc.perform(get(D + "/" + docId).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(d, "$.data.doc.docNo")).matches("^DOC[0-9]{4}-0001$");
        assertThat((String) JsonPath.read(d, "$.data.revs[0].rev")).isEqualTo("A0");
        assertThat((String) JsonPath.read(d, "$.data.revs[0].status")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(d, "$.data.targets[0].targetCd")).isEqualTo("FG1");
        assertThat((Boolean) JsonPath.read(d, "$.data.approvalRequired")).isFalse();
        long a0 = openRev(docId);
        upload(admin, a0).andExpect(status().isOk());
        putJson(D + "/revisions/" + a0, "{\"changeSummary\":\"최초 제정\",\"rowVer\":" + revVer(a0) + "}").andExpect(status().isOk());
        revAct(a0, "release").andExpect(status().isOk());
        assertThat(revStatus(a0)).isEqualTo("RELEASED");
        // 배포본 첨부: 잠금 표시 + 올리기·지우기 409
        assertThat(accounts.query(comp, "select locked_yn from sys_file where file_id = ?", String.class, fileId(a0))).isEqualTo("Y");
        upload(admin, a0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(post("/api/platform/files/" + fileId(a0) + "/delete").with(admin).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        // 배포본은 내용 수정·다시 배포 불가
        putJson(D + "/revisions/" + a0, "{\"changeSummary\":\"x\",\"rowVer\":" + revVer(a0) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QMS_DOC_STATE"));
        // 새 리비전 A1: 첨부 복사(다른 저장 키, 잠금 없음) → 배포 → A0 폐기, 배포본 하나
        long a1 = id(postJson(D + "/" + docId + "/revisions", "{}"));
        assertThat(accounts.query(comp, "select rev || ':' || source_rev_id from mst_doc_rev where doc_rev_id = ?", String.class, a1))
                .isEqualTo("A1:" + a0);
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'qms-doc' and target_id = ? and locked_yn = 'N'",
                Integer.class, String.valueOf(a1))).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(distinct storage_key) from sys_file where target_type = 'qms-doc'", Integer.class)).isEqualTo(2);
        mvc.perform(get("/api/platform/files/" + fileId(a1)).with(admin)).andExpect(status().isOk());
        postJson(D + "/" + docId + "/revisions", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_DOC_OPEN_EXISTS"));
        // 작성 중 첨부는 지울 수 있다
        mvc.perform(post("/api/platform/files/" + fileId(a1) + "/delete").with(admin).with(csrf())).andExpect(status().isOk());
        revAct(a1, "release").andExpect(status().isOk());
        assertThat(revStatus(a0)).isEqualTo("OBSOLETE");
        assertThat(revStatus(a1)).isEqualTo("RELEASED");
        mvc.perform(get(D).with(admin)).andExpect(jsonPath("$.list[0].releasedRev").value("A1"))
                .andExpect(jsonPath("$.list[0].openRev").doesNotExist());
        // 폐기본 첨부도 읽을 수는 있다(화면이 "폐기본" 표시)
        mvc.perform(get("/api/platform/files").param("targetType", "qms-doc").param("targetId", String.valueOf(a0)).with(admin))
                .andExpect(jsonPath("$.list.length()").value(1));
        // 배포본 폐기 → 현행 없음
        revAct(a1, "obsolete").andExpect(status().isOk());
        mvc.perform(get(D + "/" + docId).with(admin)).andExpect(jsonPath("$.data.doc.releasedRev").doesNotExist());
    }

    @Test
    void 결재_흐름_반려_회수는_작성중으로_승인은_배포_정책Y면_바로배포_불가() throws Exception {
        long approver = accounts.newUser(comp, "lee", PW);
        accounts.grant(comp, approver, "QA2", "qms-doc", "SEARCH");
        RequestPostProcessor lee = accounts.as(comp, approver, "lee");
        long docId = doc("WI", target("PROC", "DRAW"));
        long a0 = openRev(docId);
        revAct(a0, "release").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_DOC_APPROVAL_REQUIRED"));
        String lines = "\"lines\":[{\"userId\":" + approver + ",\"lineType\":\"APPROVE\"}]";
        long apr1 = id(postJson(D + "/revisions/" + a0 + "/submit", "{\"rowVer\":" + revVer(a0) + "," + lines + "}"));
        assertThat(revStatus(a0)).isEqualTo("IN_APPROVAL");
        // 결재 중: 내용·첨부·폐기·다시 상신 불가
        putJson(D + "/revisions/" + a0, "{\"changeSummary\":\"x\",\"rowVer\":" + revVer(a0) + "}").andExpect(jsonPath("$.code").value("QMS_DOC_STATE"));
        upload(admin, a0).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        revAct(a0, "obsolete").andExpect(jsonPath("$.code").value("QMS_DOC_STATE"));
        postJson(D + "/revisions/" + a0 + "/submit", "{\"rowVer\":" + revVer(a0) + "," + lines + "}").andExpect(status().isConflict());
        mvc.perform(get(D + "/" + docId).with(lee)).andExpect(jsonPath("$.data.approval.myTurn").value(true));
        mvc.perform(get(D + "/" + docId).with(admin)).andExpect(jsonPath("$.data.approval.canWithdraw").value(true));
        // 반려 → 작성 중
        apr(apr1, "reject", lee, "근거 부족").andExpect(status().isOk());
        assertThat(revStatus(a0)).isEqualTo("DRAFT");
        upload(admin, a0).andExpect(status().isOk());
        // 다시 상신 → 회수 → 작성 중
        long apr2 = id(postJson(D + "/revisions/" + a0 + "/submit", "{\"rowVer\":" + revVer(a0) + "," + lines + "}"));
        apr(apr2, "withdraw", admin, null).andExpect(status().isOk());
        assertThat(revStatus(a0)).isEqualTo("DRAFT");
        // 다시 상신 → 승인 → 배포 + 첨부 잠금
        long apr3 = id(postJson(D + "/revisions/" + a0 + "/submit", "{\"rowVer\":" + revVer(a0) + "," + lines + "}"));
        apr(apr3, "approve", lee, null).andExpect(status().isOk());
        assertThat(revStatus(a0)).isEqualTo("RELEASED");
        assertThat(accounts.query(comp, "select released_by || ':' || apr_id from mst_doc_rev where doc_rev_id = ?", String.class, a0))
                .isEqualTo("lee:" + apr3);
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_id = ? and target_type = 'qms-doc' and locked_yn = 'Y'",
                Integer.class, String.valueOf(a0))).isEqualTo(1);
        // 결재선 없음 → 결재 엔진 거부
        long a1 = id(postJson(D + "/" + docId + "/revisions", "{}"));
        postJson(D + "/revisions/" + a1 + "/submit", "{\"rowVer\":" + revVer(a1) + ",\"lines\":[]}").andExpect(status().isBadRequest());
        assertThat(revStatus(a1)).isEqualTo("DRAFT");
    }

    @Test
    void PLM_계약_releaseFromChange_는_작성중만_배포한다() throws Exception {
        long docId = doc("DRAWING", target("ITEM", "FG1"));
        long a0 = openRev(docId);
        runner.run(comp, () -> docService.releaseFromChange(a0));
        assertThat(accounts.query(comp, "select status || ':' || released_by from mst_doc_rev where doc_rev_id = ?", String.class, a0))
                .isEqualTo("RELEASED:SYSTEM");
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> runner.run(comp, () -> docService.releaseFromChange(a0)));
        assertThat(t).as("배포본은 다시 배포하지 않는다").isNotNull();
        // 트랜잭션 밖 호출 금지(MANDATORY)
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> docService.releaseFromChange(a0))).isNotNull();
        assertThat(runner.call(comp, () -> docService.revision(a0).getDocType())).isEqualTo("DRAWING");
    }

    // ---------- 검사계획 연결 ----------

    @Test
    void 검사계획에_검사기준서_연결_새리비전은_이어받고_폐기본이면_비운다() throws Exception {
        approvalPolicy(comp, "N");
        long std = doc("INSP_STD", target("ITEM", "FG1"));
        long stdRev = openRev(std);
        long wi = doc("WI", "");
        long p = plan("FINAL", "FG1", null, null);
        putJson(IP + "/" + p + "/doc", "{\"docRevId\":" + openRev(wi) + ",\"rowVer\":" + rowVer(p) + "}")
                .andExpect(jsonPath("$.errors[0].code").value("DOC_NOT_INSP_STD"));
        putJson(IP + "/" + p + "/doc", "{\"docRevId\":999999999,\"rowVer\":" + rowVer(p) + "}").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(get(IP + "/docs").with(admin)).andExpect(jsonPath("$.data[*].docRevId").value(hasItem((int) stdRev)))
                .andExpect(jsonPath("$.data[*].docRevId").value(not(hasItem((int) openRev(wi)))));
        putJson(IP + "/" + p + "/doc", "{\"docRevId\":" + stdRev + ",\"rowVer\":" + rowVer(p) + "}").andExpect(status().isOk());
        mvc.perform(get(IP + "/" + p).with(admin)).andExpect(jsonPath("$.data.plan.docRevId").value((int) stdRev))
                .andExpect(jsonPath("$.data.plan.docRevStatus").value("DRAFT"));
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        putJson(IP + "/" + p + "/doc", "{\"docRevId\":null,\"rowVer\":" + rowVer(p) + "}").andExpect(jsonPath("$.code").value("QMS_PLAN_NOT_DRAFT"));
        // 새 리비전은 연결을 이어받는다
        long p2 = id(postJson(IP + "/" + p + "/new-revision", "{\"rowVer\":" + rowVer(p) + "}"));
        assertThat(accounts.query(comp, "select doc_rev_id from qms_insp_plan where insp_plan_id = ?", Long.class, p2)).isEqualTo(stdRev);
        // 그 리비전을 폐기한 뒤의 새 리비전은 비운다
        postJson(IP + "/" + p2 + "/obsolete", "{\"rowVer\":" + rowVer(p2) + "}").andExpect(status().isOk());
        revAct(stdRev, "obsolete").andExpect(status().isOk());
        long p3 = id(postJson(IP + "/" + p + "/new-revision", "{\"rowVer\":" + rowVer(p) + "}"));
        assertThat(accounts.query(comp, "select doc_rev_id is null from qms_insp_plan where insp_plan_id = ?", Boolean.class, p3)).isTrue();
    }

    // ---------- 정합 점검 ----------

    @Test
    void 정합_점검_특별특성_누락_검사기준서_누락_폐기본_연결() throws Exception {
        approvalPolicy(comp, "N");
        String c = "/api/qms/consistency";
        // FG1-1 은 SC 특별특성 — 확정 검사계획 없음
        mvc.perform(get(c).with(admin)).andExpect(jsonPath("$.data.list[?(@.checkCd == 'SPECIAL_NOT_IN_PLAN')].targetCd").value(hasItem("FG1-1")));
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        mvc.perform(get(c).param("checkCd", "SPECIAL_NOT_IN_PLAN").with(admin)).andExpect(jsonPath("$.data.list.length()").value(0));
        mvc.perform(get(c).param("checkCd", "PLAN_NO_INSP_STD").with(admin)).andExpect(jsonPath("$.data.list[0].refId").value((int) p))
                .andExpect(jsonPath("$.data.list[0].targetCd").value("FG1"));
        // 품목 클래스·다른 품목 대상 기준서는 해당 안 됨, FG1 대상 배포본이면 해소
        long other = doc("INSP_STD", target("ITEM", "RM1"));
        revAct(openRev(other), "release").andExpect(status().isOk());
        mvc.perform(get(c).param("checkCd", "PLAN_NO_INSP_STD").with(admin)).andExpect(jsonPath("$.data.list.length()").value(1));
        long std = doc("INSP_STD", target("ITEM", "FG1"));
        long stdRev = openRev(std);
        revAct(stdRev, "release").andExpect(status().isOk());
        mvc.perform(get(c).param("checkCd", "PLAN_NO_INSP_STD").with(admin)).andExpect(jsonPath("$.data.list.length()").value(0));
        // 작성 중 계획이 가리킨 리비전이 폐기됨
        long draft = plan("PROCESS", "FG1", null, "DRAW");
        putJson(IP + "/" + draft + "/doc", "{\"docRevId\":" + stdRev + ",\"rowVer\":" + rowVer(draft) + "}").andExpect(status().isOk());
        revAct(stdRev, "obsolete").andExpect(status().isOk());
        mvc.perform(get(c).param("checkCd", "PLAN_DOC_OBSOLETE").with(admin)).andExpect(jsonPath("$.data.list[0].refId").value((int) draft));
        mvc.perform(get(c).param("checkCd", "PLAN_NO_INSP_STD").with(admin)).andExpect(jsonPath("$.data.list[0].refId").value((int) p));
        mvc.perform(get(c).param("checkCd", "BAD").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(post(c + "/export"), "{}").with(admin)).andExpect(status().isOk());
    }

    // ---------- 이상 사용 ----------

    @Test
    void 문서_입력_이상_대상_한벌_번호_유형_고정_리비전_형식() throws Exception {
        postJson(D, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field").value(hasItem("docType")));
        postJson(D, "{\"docType\":\"MEMO\",\"docNm\":\"x\"}").andExpect(jsonPath("$.errors[0].field").value("docType"));
        postJson(D, "{\"docNo\":\"bad no\",\"docType\":\"WI\",\"docNm\":\"x\"}").andExpect(jsonPath("$.errors[0].field").value("docNo"));
        long d1 = id(postJson(D, "{\"docNo\":\"wi-001\",\"docType\":\"WI\",\"docNm\":\"x\"}"));
        postJson(D, "{\"docNo\":\"WI-001\",\"docType\":\"WI\",\"docNm\":\"y\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 대상: 없는 코드·같은 대상 두 번·사용 중지 새 대상·유형 없음 → 행 키(순번)마다
        accounts.exec(comp, "update mst_item set use_yn = 'N', updated_by = 't' where item_cd = 'RM2'");
        postJson(D, "{\"docType\":\"WI\",\"docNm\":\"x\",\"targets\":[" + target("ITEM", "NOPE") + "," + target("ITEM", "FG1") + ","
                + target("ITEM", "FG1") + "," + target("ITEM", "RM2") + "," + target("BOM", "FG1") + "," + target("PROC", "") + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(5))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0')].code").value(hasItem("NO_SUCH_CODE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '2')].code").value(hasItem("DUPLICATE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '3')].code").value(hasItem("INACTIVE")));
        // 이미 걸린 대상은 사용 중지돼도 그대로 둘 수 있다
        long d2 = doc("WI", target("ITEM_CLASS", "COIL") + "," + target("PROC", "IQC"));
        accounts.exec(comp, "update mst_proc set use_yn = 'N', updated_by = 't' where proc_cd = 'IQC'");
        int dv = accounts.query(comp, "select row_ver from mst_doc where doc_id = ?", Integer.class, d2);
        putJson(D + "/" + d2, "{\"docNm\":\"바꿈\",\"targets\":[" + target("PROC", "IQC") + "],\"rowVer\":" + dv + "}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from mst_doc_target where doc_id = ?", Integer.class, d2)).isEqualTo(1);
        // 번호·유형 고정, 옛 rowVer
        putJson(D + "/" + d2, "{\"docNo\":\"OTHER\",\"docType\":\"CP\",\"docNm\":\"x\",\"rowVer\":" + (dv + 1) + "}")
                .andExpect(jsonPath("$.errors.length()").value(2));
        putJson(D + "/" + d2, "{\"docNm\":\"x\",\"rowVer\":" + dv + "}").andExpect(status().isConflict());
        // 리비전 형식·중복
        long r1 = openRev(d1);
        putJson(D + "/revisions/" + r1, "{\"rev\":\"a-1\",\"rowVer\":" + revVer(r1) + "}").andExpect(jsonPath("$.errors[0].field").value("rev"));
        putJson(D + "/revisions/" + r1, "{\"rev\":\"R1\",\"changeSummary\":\"" + "x".repeat(1001) + "\",\"rowVer\":" + revVer(r1) + "}")
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        putJson(D + "/revisions/" + r1, "{\"rev\":\"R1\",\"rowVer\":" + revVer(r1) + "}").andExpect(status().isOk());
        putJson(D + "/revisions/" + r1, "{\"rev\":\"R2\",\"rowVer\":0}").andExpect(status().isConflict());
        // 목록 조건 조작
        mvc.perform(get(D).param("docType", "bad type").with(admin)).andExpect(status().isBadRequest());   // 조회 조건은 형식만 — 없는 코드는 빈 결과(docs/68)
        mvc.perform(get(D).param("keyword", "%").with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(D + "/999999999").with(admin)).andExpect(status().isNotFound());
        postJson(D + "/revisions/999999999/release", "{\"rowVer\":0}").andExpect(status().isNotFound());
    }

    @Test
    void 권한별_403_멱등키_409() throws Exception {
        String body = "{\"docType\":\"WI\",\"docNm\":\"x\"}";
        mvc.perform(json(post(D), body).header("X-Idempotency-Key", "qms-doc-0001").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(D), body).header("X-Idempotency-Key", "qms-doc-0001").with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        approvalPolicy(comp, "N");
        long docId = doc("WI", "");
        long rev = openRev(docId);
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "qms-doc", "SEARCH", "SAVE");
        RequestPostProcessor sv = accounts.as(comp, saver, "saver1");
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "qms-doc", "SEARCH");
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        mvc.perform(get(D + "/" + docId).with(rd)).andExpect(status().isOk());
        mvc.perform(json(post(D), body).with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(D + "/" + docId + "/revisions"), "{}").with(rd)).andExpect(status().isForbidden());
        upload(rd, rev).andExpect(status().isForbidden());
        mvc.perform(json(post(D + "/revisions/" + rev + "/release"), "{\"rowVer\":0}").with(sv)).andExpect(status().isForbidden());
        mvc.perform(json(post(D + "/revisions/" + rev + "/obsolete"), "{\"rowVer\":0}").with(sv)).andExpect(status().isForbidden());
        mvc.perform(get("/api/qms/consistency").with(rd)).andExpect(status().isForbidden());
        upload(sv, rev).andExpect(status().isOk());
    }

    @Test
    void B사_사용자는_A사_문서를_보지도_쓰지도_못한다() throws Exception {
        approvalPolicy(comp, "N");
        long docId = doc("INSP_STD", target("ITEM", "FG1"));
        long rev = openRev(docId);
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-doc", "SEARCH", "SAVE", "APPROVE", "DELETE");
        accounts.grant(other, bUser, "QA", "qms-insp-plan", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(D).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(D + "/" + docId).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(D + "/" + docId), "{\"docNm\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(D + "/" + docId + "/revisions"), "{}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(D + "/revisions/" + rev), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(D + "/revisions/" + rev + "/release"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(D + "/revisions/" + rev + "/obsolete"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // A사 리비전에 첨부 → 보이지 않는 대상 = 잠김(존재를 흘리지 않음)
        upload(b, rev).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        // B사 문서에 A사 품목 코드(B사엔 없음) → 칸 오류
        mvc.perform(json(post(D), "{\"docType\":\"WI\",\"docNm\":\"x\",\"targets\":[" + target("ITEM", "FG1") + "]}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 직접 SQL: A사 문서에 대상·리비전, A사 품목으로 B사 대상, B사 계획에 A사 리비전
        long aItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        constraint(other, "fk_mst_doc_target_doc_comp", () -> accounts.exec(other,
                "insert into mst_doc_target (comp_cd, doc_id, target_type, item_id, created_by) values (?, ?, 'ITEM', ?, 't')", other, docId, aItem));
        constraint(other, "fk_mst_doc_rev_doc_comp", () -> accounts.exec(other,
                "insert into mst_doc_rev (comp_cd, doc_id, rev, created_by, updated_by) values (?, ?, 'Z9', 't', 't')", other, docId));
        accounts.exec(other, "insert into mst_doc (comp_cd, doc_no, doc_type, doc_nm, created_by, updated_by) values (?, 'B1', 'WI', 'b', 't', 't')", other);
        long bDoc = accounts.query(other, "select doc_id from mst_doc where doc_no = 'B1'", Long.class);
        constraint(other, "fk_mst_doc_target_comp_ref", () -> accounts.exec(other,
                "insert into mst_doc_target (comp_cd, doc_id, target_type, item_id, created_by) values (?, ?, 'ITEM', ?, 't')", other, bDoc, aItem));
        fixtures(other);
        long bPlan = id(mvc.perform(json(post(IP), "{\"inspType\":\"FINAL\",\"itemCd\":\"FG1\"}").with(b)));
        mvc.perform(json(put(IP + "/" + bPlan + "/doc"), "{\"docRevId\":" + rev + ",\"rowVer\":0}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        constraint(other, "fk_qms_insp_plan_doc_rev_comp", () -> accounts.exec(other,
                "update qms_insp_plan set doc_rev_id = ? where insp_plan_id = ?", rev, bPlan));
        assertThat(accounts.exec(other, "update mst_doc_rev set status = 'OBSOLETE' where doc_rev_id = ?", rev)).isZero();
        assertThat(revStatus(rev)).isEqualTo("DRAFT");
    }

    @Test
    void 직접SQL_문서_리비전_상태_불변_배포본_하나_첨부_잠금() throws Exception {
        approvalPolicy(comp, "N");
        long docId = doc("CP", target("ITEM", "FG1"));
        long a0 = openRev(docId);
        upload(admin, a0).andExpect(status().isOk());
        revAct(a0, "release").andExpect(status().isOk());
        long file = fileId(a0);
        sqlState("42501", () -> accounts.exec(comp, "delete from mst_doc where doc_id = ?", docId));
        sqlState("42501", () -> accounts.exec(comp, "delete from mst_doc_rev where doc_rev_id = ?", a0));
        constraint(comp, "ck_mst_doc_key_immutable", () -> accounts.exec(comp, "update mst_doc set doc_type = 'WI' where doc_id = ?", docId));
        constraint(comp, "ck_mst_doc_rev_locked", () -> accounts.exec(comp,
                "update mst_doc_rev set change_summary = '몰래' where doc_rev_id = ?", a0));
        constraint(comp, "ck_mst_doc_rev_transition", () -> accounts.exec(comp, "update mst_doc_rev set status = 'DRAFT' where doc_rev_id = ?", a0));
        constraint(comp, "ck_mst_doc_rev_locked", () -> accounts.exec(comp, "update mst_doc_rev set released_at = now() - interval '1 day' where doc_rev_id = ?", a0));
        constraint(comp, "ck_mst_doc_rev_draft", () -> accounts.exec(comp,
                "insert into mst_doc_rev (comp_cd, doc_id, rev, status, released_at, released_by, created_by, updated_by) "
                        + "values (?, ?, 'X1', 'RELEASED', now(), 't', 't', 't')", comp, docId));
        // 작성 중 둘, 배포본 둘
        accounts.exec(comp, "insert into mst_doc_rev (comp_cd, doc_id, rev, created_by, updated_by) values (?, ?, 'B0', 't', 't')", comp, docId);
        constraint(comp, "uq_mst_doc_rev_open", () -> accounts.exec(comp,
                "insert into mst_doc_rev (comp_cd, doc_id, rev, created_by, updated_by) values (?, ?, 'B1', 't', 't')", comp, docId));
        long b0 = accounts.query(comp, "select doc_rev_id from mst_doc_rev where doc_id = ? and rev = 'B0'", Long.class, docId);
        constraint(comp, "uq_mst_doc_rev_released", () -> accounts.exec(comp,
                "update mst_doc_rev set status = 'RELEASED', released_at = now(), released_by = 't' where doc_rev_id = ?", b0));
        constraint(comp, "ck_mst_doc_rev_approval", () -> accounts.exec(comp,
                "update mst_doc_rev set status = 'IN_APPROVAL' where doc_rev_id = ?", b0));
        constraint(comp, "ck_mst_doc_rev_released", () -> accounts.exec(comp,
                "update mst_doc_rev set status = 'RELEASED' where doc_rev_id = ?", b0));
        // 대상은 지우고 넣기만
        constraint(comp, "ck_mst_doc_target_no_update", () -> accounts.exec(comp,
                "update mst_doc_target set target_type = 'ITEM' where doc_id = ?", docId));
        // 잠긴 첨부: 사용 중지·해제·이름 변경·삭제 거부
        constraint(comp, "ck_sys_file_locked", () -> accounts.exec(comp, "update sys_file set use_yn = 'N' where file_id = ?", file));
        constraint(comp, "ck_sys_file_locked", () -> accounts.exec(comp, "update sys_file set locked_yn = 'N' where file_id = ?", file));
        constraint(comp, "ck_sys_file_locked", () -> accounts.exec(comp, "update sys_file set orig_name = 'x.pdf' where file_id = ?", file));
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> accounts.exec(comp, "delete from sys_file where file_id = ?", file));
        assertThat(t).as("삭제는 권한 또는 잠금 트리거가 막는다").isNotNull();
        // 검사계획 연결: 폐기본·검사기준서 아닌 리비전 직접 SQL
        long p = plan("FINAL", "FG1", null, null);
        constraint(comp, "ck_qms_insp_plan_doc_rev", () -> accounts.exec(comp, "update qms_insp_plan set doc_rev_id = ? where insp_plan_id = ?", a0, p));
        MvcResult m = mvc.perform(get(D + "/" + docId).with(admin)).andReturn();
        assertThat((Integer) JsonPath.read(m.getResponse().getContentAsString(), "$.data.revs.length()")).isEqualTo(2);
    }
}
