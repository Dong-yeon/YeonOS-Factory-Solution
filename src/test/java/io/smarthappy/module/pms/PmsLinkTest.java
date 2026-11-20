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
import java.time.LocalDate;
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
 * PMS 6단계 병합 뒤 연결 (docs/32 PM6·4.8, D6·D10·D13~D16) — 산출물 ↔ QMS 문서 리비전(배포본 = 승인, 작성 중 → 반영·게이트 상신 때 승인,
 * 파일·손 승인 거부, 폐기본·같은 리비전·없는 문서), 이슈 → QMS 시정조치(한 번만, 품목·제목·D2, qms-capa 권한), 지연 판정의 회사 휴일
 * (기본 = 지금과 같음 회귀, 휴일이면 다음 근무일이 지나야 지연), A사로 B사(API·직접 SQL).
 */
@AutoConfigureMockMvc
class PmsLinkTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String D = "/api/pms/deliverables";
    private static final String R = "/api/pms/gate-reviews";
    private static final String IS = "/api/pms/issues";
    private static final String DOC = "/api/qms/docs";
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    long pmoId;
    RequestPostProcessor pm;
    /** PMS 는 모든 프로젝트 관리(PMO)지만 QMS 권한은 없다 */
    RequestPostProcessor pmo;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PL");
        pmId = accounts.newUser(comp, "pm1", PW);
        pmoId = accounts.newUser(comp, "pmo1", PW);
        grantPms(comp, pmId, "PMS");
        grantPms(comp, pmoId, "PMO");
        accounts.grant(comp, pmoId, "PMO", "pms-project", "APPROVE");
        accounts.grant(comp, pmId, "QA", "qms-doc", "SEARCH", "SAVE", "APPROVE");
        pm = accounts.as(comp, pmId, "pm1");
        pmo = accounts.as(comp, pmoId, "pmo1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        policy(comp, "QMS_DOC_APPROVAL", "N");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'ZT-BRKT', '브래킷', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", comp);
    }

    private void grantPms(String c, long user, String grp) {
        for (String prog : List.of("pms-project", "pms-template", "pms-gate", "pms-deliverable", "pms-issue", "pms-home")) {
            accounts.grant(c, user, grp, prog, "SEARCH", "SAVE");
        }
        accounts.grant(c, user, grp, "pms-deliverable", "APPROVE");
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

    private void policy(String c, String key, String value) {
        accounts.exec(c, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')
                on conflict do nothing""", c, key, value);
        accounts.exec(c, "update sys_policy set policy_value = ? where policy_key = ? and plant_cd = '*'", value, key);
    }

    private long apqpProject(RequestPostProcessor who) throws Exception {
        id(send(post("/api/pms/templates/default-apqp"), "{}", who));
        return id(send(post(P), "{\"projNm\":\"신규 브래킷\",\"projType\":\"APQP\",\"tmplCd\":\"APQP-STD\",\"sopDate\":\""
                + today.plusDays(400) + "\"}", who));
    }

    private Map<String, Object> deliv(long proj, String nm) throws Exception {
        List<Map<String, Object>> rows = read(mvc.perform(get(P + "/" + proj + "/deliverables").with(pm)),
                "$.data.rows[?(@.delivNm == '" + nm + "')]");
        return rows.get(0);
    }

    private static long num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).longValue();
    }

    private ResultActions link(RequestPostProcessor who, long proj, String nm, String docNo, String rev) throws Exception {
        Map<String, Object> d = deliv(proj, nm);
        return send(post(D + "/" + num(d, "delivId") + "/doc-link"), "{\"docNo\":\"" + docNo + "\",\"rev\":" + (rev == null ? "null" : "\"" + rev + "\"")
                + ",\"note\":\"연결\",\"rowVer\":" + num(d, "rowVer") + "}", who);
    }

    /** QMS 문서(대상 없음) → [문서 id, 문서 번호] — 첫 리비전 A0 작성 중 */
    private Object[] qmsDoc(String type) throws Exception {
        long docId = id(send(post(DOC), "{\"docType\":\"" + type + "\",\"docNm\":\"" + type + " 문서\",\"targets\":[]}", pm));
        String no = read(mvc.perform(get(DOC + "/" + docId).with(pm)), "$.data.doc.docNo");
        return new Object[] {docId, no};
    }

    private long openRev(long docId) {
        return accounts.query(comp, "select doc_rev_id from mst_doc_rev where doc_id = ? and status in ('DRAFT','IN_APPROVAL')", Long.class, docId);
    }

    private void release(long revId) throws Exception {
        int ver = accounts.query(comp, "select row_ver from mst_doc_rev where doc_rev_id = ?", Integer.class, revId);
        send(put(DOC + "/revisions/" + revId), "{\"changeSummary\":\"제정\",\"rowVer\":" + ver + "}", pm).andExpect(status().isOk());
        ver = accounts.query(comp, "select row_ver from mst_doc_rev where doc_rev_id = ?", Integer.class, revId);
        send(post(DOC + "/revisions/" + revId + "/release"), "{\"rowVer\":" + ver + "}", pm).andExpect(status().isOk());
    }

    private int syncDocs(long proj) throws Exception {
        return ((Number) read(send(post(P + "/" + proj + "/deliverables/sync-docs"), "{}", pm).andExpect(status().isOk()), "$.data")).intValue();
    }

    // ------------------------------------------------------------------ ① 산출물 ↔ QMS 문서 리비전

    @Test
    void 산출물을_QMS_문서에_연결_배포본은_바로_승인_작성중은_반영_때_승인_파일과_손승인은_거부() throws Exception {
        long proj = apqpProject(pm);
        Object[] pf = qmsDoc("PFMEA");
        long a0 = openRev((Long) pf[0]);
        // 작성 중 리비전에 연결 → 제출 중, 목록에 문서·리비전·상태
        link(pm, proj, "PFMEA", ((String) pf[1]).toLowerCase(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED")).andExpect(jsonPath("$.data.rev").value("A0"));
        Map<String, Object> d = deliv(proj, "PFMEA");
        long dId = num(d, "delivId");
        assertThat(d.get("refType")).isEqualTo("QMS_DOC_REV");
        assertThat(d.get("docNo")).isEqualTo(pf[1]);
        assertThat(d.get("docStatus")).isEqualTo("DRAFT");
        // 연결된 칸: 파일 제출·손 승인 거부, 첨부 API 로 올리기 잠김, 같은 리비전 다시 연결 거부
        mvc.perform(multipart(D + "/" + dId + "/submit").file(new MockMultipartFile("file", "x.pdf", "application/pdf", PDF))
                .param("rowVer", String.valueOf(num(d, "rowVer"))).with(pm).with(csrf())).andExpect(jsonPath("$.code").value("PMS_DELIV_STATE"));
        send(post(D + "/" + dId + "/decide"), "{\"action\":\"APPROVE\",\"rowVer\":" + num(d, "rowVer") + "}", pm)
                .andExpect(jsonPath("$.code").value("PMS_DELIV_STATE"));
        mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF))
                .param("targetType", "pms-deliverable").param("targetId", "D" + dId).with(csrf()).with(pm))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        link(pm, proj, "PFMEA", (String) pf[1], "A0").andExpect(jsonPath("$.errors[0].code").value("PMS_DOC_SAME"));
        // 아직 배포 전 → 반영 0건, 배포 뒤 → 1건 승인(이력: 연결 → 승인)
        assertThat(syncDocs(proj)).isZero();
        release(a0);
        assertThat(syncDocs(proj)).isEqualTo(1);
        assertThat(deliv(proj, "PFMEA").get("status")).isEqualTo("APPROVED");
        assertThat(syncDocs(proj)).isZero();
        List<Map<String, Object>> hist = read(mvc.perform(get(D + "/" + dId + "/history").with(pm)), "$.data");
        assertThat(hist).extracting(h -> h.get("action")).containsExactly("APPROVE", "LINK");
        assertThat(hist.get(1).get("docLabel")).isEqualTo(pf[1] + " A0");
        // 이미 배포본이 있는 문서에 연결 → 바로 승인
        Object[] cp = qmsDoc("CP");
        release(openRev((Long) cp[0]));
        link(pm, proj, "관리계획서(양산 전)", (String) cp[1], null).andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(deliv(proj, "관리계획서(양산 전)").get("status")).isEqualTo("APPROVED");
        // 새 리비전 배포 → 앞 배포본 폐기 → 폐기본 연결 거부, 없는 문서·리비전
        send(post(DOC + "/" + cp[0] + "/revisions"), "{}", pm).andExpect(status().isOk());
        release(openRev((Long) cp[0]));
        link(pm, proj, "DFMEA", (String) cp[1], "A0").andExpect(jsonPath("$.errors[0].code").value("PMS_DOC_OBSOLETE"));
        link(pm, proj, "DFMEA", "NO-SUCH-DOC", null).andExpect(jsonPath("$.errors[0].field").value("docNo"))
                .andExpect(jsonPath("$.errors[0].code").value("PMS_DOC_NOT_FOUND"));
        link(pm, proj, "DFMEA", (String) cp[1], "Z9").andExpect(jsonPath("$.errors[0].field").value("rev"))
                .andExpect(jsonPath("$.errors[0].code").value("PMS_DOC_REV_NOT_FOUND"));
        // 승인된 칸을 새 배포본으로 다시 연결 → 바로 승인(새 리비전)
        link(pm, proj, "관리계획서(양산 전)", (String) cp[1], null).andExpect(jsonPath("$.data.rev").value("A1"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        // QMS 문서 조회 권한 없는 PMO 는 연결 못 한다(D14)
        link(pmo, proj, "DFMEA", (String) cp[1], null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        // 면제는 된다 — 연결 칸도 손 승인·반려만 막힌다
        Object[] df = qmsDoc("PFMEA");
        link(pm, proj, "DFMEA", (String) df[1], null).andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        Map<String, Object> dfm = deliv(proj, "DFMEA");
        send(post(D + "/" + num(dfm, "delivId") + "/decide"), "{\"action\":\"REJECT\",\"note\":\"x\",\"rowVer\":" + num(dfm, "rowVer") + "}", pm)
                .andExpect(jsonPath("$.code").value("PMS_DELIV_STATE"));
        send(post(D + "/" + num(dfm, "delivId") + "/decide"), "{\"action\":\"WAIVE\",\"note\":\"고객 면제\",\"rowVer\":" + num(dfm, "rowVer") + "}", pm)
                .andExpect(status().isOk());
        assertThat(deliv(proj, "DFMEA").get("status")).isEqualTo("WAIVED");
    }

    @Test
    void 게이트_상신_때_배포된_문서_연결_산출물이_자동으로_승인된다() throws Exception {
        policy(comp, "PMS_GATE_APPROVAL", "N");
        accounts.grant(comp, pmId, "PMS", "pms-gate", "APPROVE");
        long proj = apqpProject(pm);
        Object[] a = qmsDoc("OTHER");
        Object[] b = qmsDoc("OTHER");
        link(pm, proj, "고객 요구사항 검토서", (String) a[1], null).andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        link(pm, proj, "타당성 검토서", (String) b[1], null).andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        long g1 = ((Number) ((List<?>) read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)),
                "$.data.tasks[?(@.taskNm == 'G1 개발 착수 심사')].taskId")).get(0)).longValue();
        long rv = id(send(post("/api/pms/gates/" + g1 + "/reviews"), "{}", pm));
        String checks = "[{\"checkNm\":\"a\",\"result\":\"OK\"},{\"checkNm\":\"b\",\"result\":\"OK\"},{\"checkNm\":\"c\",\"result\":\"NA\"}]";
        send(put(R + "/" + rv), "{\"reviewDate\":\"" + today + "\",\"decision\":\"GO\",\"summary\":\"s\",\"checks\":" + checks
                + ",\"conds\":[],\"rowVer\":0}", pm).andExpect(status().isOk());
        int ver = accounts.query(comp, "select row_ver from pms_gate_review where review_id = ?", Integer.class, rv);
        // 배포 전: 준비 안 됨
        send(post(R + "/" + rv + "/submit"), "{\"rowVer\":" + ver + "}", pm).andExpect(jsonPath("$.code").value("PMS_GATE_NOT_READY"));
        release(openRev((Long) a[0]));
        release(openRev((Long) b[0]));
        // 상신(바로 결정) 직전 반영 → 통과
        assertThat(id(send(post(R + "/" + rv + "/submit"), "{\"rowVer\":" + ver + "}", pm))).isZero();
        assertThat(deliv(proj, "고객 요구사항 검토서").get("status")).isEqualTo("APPROVED");
        assertThat(deliv(proj, "타당성 검토서").get("status")).isEqualTo("APPROVED");
        assertThat(accounts.query(comp, "select status from pms_task where task_id = ?", String.class, g1)).isEqualTo("DONE");
    }

    // ------------------------------------------------------------------ ② 이슈 → QMS 시정조치

    @Test
    void 이슈를_시정조치로_올리기_한_번만_품목_제목_D2_실리고_QMS_권한_필요() throws Exception {
        long proj = id(send(post(P), "{\"projNm\":\"연결 시험\",\"projType\":\"GENERAL\"}", pm));
        long issue = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"금형 치수 불량\",\"detail\":\"T1 시사출 치수 벗어남\",\"severity\":\"HIGH\","
                + "\"ownerLoginId\":\"pm1\",\"objType\":\"ITEM\",\"objCd\":\"zt-brkt\"}", pm));
        String no = accounts.query(comp, "select issue_no from pms_issue where issue_id = ?", String.class, issue);
        // qms-capa 권한 없으면 403
        send(post(IS + "/" + issue + "/escalate"), "{\"rowVer\":0}", pm).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        accounts.grant(comp, pmId, "QA", "qms-capa", "SEARCH", "SAVE");
        send(post(IS + "/" + issue + "/escalate"), "{\"rowVer\":5}", pm).andExpect(status().isConflict());
        long capa = id(send(post(IS + "/" + issue + "/escalate"), "{\"rowVer\":0}", pm));
        assertThat(accounts.query(comp, "select source || '|' || title || '|' || coalesce(i.item_cd, '-') || '|' || c.owner_user_id"
                + " from qms_capa c left join mst_item i on i.item_id = c.item_id where c.capa_id = ?", String.class, capa))
                .isEqualTo("INTERNAL|금형 치수 불량|ZT-BRKT|" + pmId);
        assertThat(accounts.query(comp, "select d2_problem from qms_capa where capa_id = ?", String.class, capa))
                .startsWith("PMS " + no + " / ").contains("T1 시사출 치수 벗어남");
        // 이슈: 시정조치 번호·상태, 품목 연결 그대로, 상태 그대로
        mvc.perform(get(IS + "/" + issue).with(pm)).andExpect(jsonPath("$.data.capaId").value(capa))
                .andExpect(jsonPath("$.data.capaNo").value(accounts.query(comp, "select capa_no from qms_capa where capa_id = ?", String.class, capa)))
                .andExpect(jsonPath("$.data.capaStatus").isNotEmpty())
                .andExpect(jsonPath("$.data.objCd").value("ZT-BRKT")).andExpect(jsonPath("$.data.status").value("OPEN"));
        // 두 번 올리기 거부, 해결된 이슈는 올리지 못한다
        send(post(IS + "/" + issue + "/escalate"), "{\"rowVer\":1}", pm).andExpect(jsonPath("$.code").value("PMS_ISSUE_STATE"));
        long other = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"x\"}", pm));
        send(post(IS + "/" + other + "/status"), "{\"status\":\"RESOLVED\",\"resolution\":\"해결\",\"rowVer\":0}", pm).andExpect(status().isOk());
        send(post(IS + "/" + other + "/escalate"), "{\"rowVer\":1}", pm).andExpect(jsonPath("$.code").value("PMS_ISSUE_STATE"));
        // 직접 SQL: 연결 바꾸기·지우기 거부
        Throwable t = catchThrowable(() -> accounts.exec(comp, "update pms_issue set capa_id = null where issue_id = ?", issue));
        assertThat(PgErrors.constraint(t)).hasValue("ck_pms_issue_capa_fixed");
        assertThat(accounts.query(comp, "select count(*) from qms_capa", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ ③ 지연 판정의 회사 휴일

    @Test
    void 지연은_기본이면_지금과_같고_회사_휴일이면_다음_근무일이_지나야_지연() throws Exception {
        long proj = id(send(post(P), "{\"projNm\":\"휴일 시험\",\"projType\":\"GENERAL\"}", pm));
        for (int back : new int[] {2, 3}) {
            send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"끝 D-" + back + "\",\"ownerLoginId\":\"pm1\",\"planStart\":\""
                    + today.minusDays(10) + "\",\"planEnd\":\"" + today.minusDays(back) + "\"}", pm).andExpect(status().isOk());
        }
        long issue = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"기한 D-2\",\"dueDate\":\"" + today.minusDays(2) + "\"}", pm));
        // 기본(주간 쉬는 날 NONE, 휴일 없음) = PM5 까지와 같다: 오늘보다 앞 계획 종료는 모두 지연
        assertLate(proj, issue, true, true, 2);
        // 어제·오늘이 회사 휴일 → 기준일 = 그저께: 그저께 끝난 작업은 아직 지연 아님(다음 근무일이 지나지 않음), 사흘 전 것은 지연
        for (int back : new int[] {0, 1}) {
            accounts.exec(comp, "insert into mst_work_cal (comp_cd, cal_date, day_type, reason, created_by, updated_by) values (?, ?, 'OFF', '휴무', 't', 't')",
                    comp, today.minusDays(back));
        }
        assertLate(proj, issue, false, true, 1);
        // 휴일을 지우면 다시 같다
        accounts.exec(comp, "update mst_work_cal set use_yn = 'N' where comp_cd = ?", comp);
        assertLate(proj, issue, true, true, 2);
    }

    /** 작업 표·포트폴리오·내 할 일·이슈가 같은 기준일 */
    private void assertLate(long proj, long issue, boolean d2, boolean d3, int lateCnt) throws Exception {
        ResultActions t = mvc.perform(get(P + "/" + proj + "/tasks").with(pm));
        assertThat(((List<?>) read(t, "$.data.tasks[?(@.taskNm == '끝 D-2')].late")).get(0)).isEqualTo(d2);
        t = mvc.perform(get(P + "/" + proj + "/tasks").with(pm));
        assertThat(((List<?>) read(t, "$.data.tasks[?(@.taskNm == '끝 D-3')].late")).get(0)).isEqualTo(d3);
        mvc.perform(get(P + "/" + proj).with(pm)).andExpect(jsonPath("$.data.lateCnt").value(lateCnt));
        mvc.perform(get("/api/pms/portfolio").param("status", "ALL").with(pm)).andExpect(jsonPath("$.data[0].lateCnt").value(lateCnt));
        List<?> todo = read(mvc.perform(get("/api/pms/my-todo").with(pm)), "$.data.tasks[?(@.late == true)]");
        assertThat(todo).hasSize(lateCnt);
        mvc.perform(get(IS + "/" + issue).with(pm)).andExpect(jsonPath("$.data.late").value(d2));
    }

    // ------------------------------------------------------------------ A사로 B사

    @Test
    void A사로_B사_문서_연결_시정조치_올리기_불가와_직접_SQL_다른_회사_참조_거부() throws Exception {
        long proj = apqpProject(pm);
        Object[] doc = qmsDoc("PFMEA");
        long aRev = openRev((Long) doc[0]);
        long aDeliv = num(deliv(proj, "PFMEA"), "delivId");
        long aIssue = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"A사 이슈\"}", pm));
        accounts.grant(comp, pmId, "QA", "qms-capa", "SEARCH", "SAVE");
        long aCapa = id(send(post(IS + "/" + aIssue + "/escalate"), "{\"rowVer\":0}", pm));

        String other = accounts.newComp("PM");
        long bId = accounts.newUser(other, "pm1", PW);
        grantPms(other, bId, "ALL");
        accounts.grant(other, bId, "ALL", "qms-doc", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(other, bId, "ALL", "qms-capa", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bId, "pm1");
        // A사 산출물·이슈를 B사가: 404
        send(post(D + "/" + aDeliv + "/doc-link"), "{\"docNo\":\"" + doc[1] + "\",\"rowVer\":0}", b).andExpect(status().isNotFound());
        send(post(P + "/" + proj + "/deliverables/sync-docs"), "{}", b).andExpect(status().isNotFound());
        send(post(IS + "/" + aIssue + "/escalate"), "{\"rowVer\":1}", b).andExpect(status().isNotFound());
        // B사 산출물에 A사 문서 번호: 문서 없음(RLS)
        id(send(post("/api/pms/templates/default-apqp"), "{}", b));
        long bProj = id(send(post(P), "{\"projNm\":\"B\",\"projType\":\"APQP\",\"tmplCd\":\"APQP-STD\",\"sopDate\":\"" + today.plusDays(400) + "\"}", b));
        List<Map<String, Object>> rows = read(mvc.perform(get(P + "/" + bProj + "/deliverables").with(b)), "$.data.rows[?(@.delivNm == 'PFMEA')]");
        long bDeliv = num(rows.get(0), "delivId");
        send(post(D + "/" + bDeliv + "/doc-link"), "{\"docNo\":\"" + doc[1] + "\",\"rowVer\":0}", b)
                .andExpect(jsonPath("$.errors[0].field").value("docNo")).andExpect(jsonPath("$.errors[0].code").value("PMS_DOC_NOT_FOUND"));
        // 직접 SQL(B사 범위): A사 리비전·시정조치를 가리키면 같은 회사 확인 트리거
        Throwable t1 = catchThrowable(() -> accounts.exec(other, """
                update pms_deliverable set status = 'SUBMITTED', ref_type = 'QMS_DOC_REV', ref_id = ?, submitted_at = now(), sub_cnt = sub_cnt + 1
                 where deliv_id = ?""", aRev, bDeliv));
        assertThat(PgErrors.constraint(t1)).hasValue("fk_pms_deliverable_doc_rev");
        long bIssue = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + bProj + ",\"title\":\"B사 이슈\"}", b));
        Throwable t2 = catchThrowable(() -> accounts.exec(other, "update pms_issue set capa_id = ? where issue_id = ?", aCapa, bIssue));
        assertThat(PgErrors.constraint(t2)).hasValue("fk_pms_issue_capa_comp");
        assertThat(accounts.query(other, "select count(*) from qms_capa", Integer.class)).isZero();
    }
}
