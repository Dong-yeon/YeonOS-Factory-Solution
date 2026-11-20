package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.plm.link.EcoLookup;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * QMS 4M 4차 — 고객 통지 판단 (docs/29_P3 52장, docs/66 (라)).
 * 정상(맞는 CSR 만 — 고객 전체·품목·클래스, 시작일 미래·사용 안 함·다른 품목·다른 고객 제외, 유형 중 큰 요구) · 경고 4종 · 고객 없음·4M 없음 ·
 * 상신 정책 BLOCK/WARN · CSR 요구 입력(칸 오류·NONE·null 그대로) · 직접 SQL · A사로 B사(판단 404, PLM 계약 custId·pcns 빈 값) · 권한.
 */
class CustNotifyApiTest extends QmsTestBase {

    static final String C = "/api/qms/changes";
    static final String S = "/api/qms/csrs";
    static final String ECO = "/api/plm/ecos";

    @Autowired
    TenantRunner runner;

    @Autowired
    EcoLookup ecoLookup;

    long approverId;
    int seq;

    @BeforeEach
    void notifySetUp() {
        accounts.grant(comp, userId, "QA", "qms-change", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-csr", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        approverId = accounts.newUser(comp, "mgr1", PW);
        accounts.grant(comp, approverId, "MGR", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        custs(comp);
    }

    void custs(String c) {
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', '고객1', 'SALES', 't', 't'), (?, 'C2', '고객2', 'SALES', 't', 't')""", c, c);
    }

    long custId(String c, String cd) {
        return accounts.query(c, "select cust_id from mst_cust where cust_cd = ?", Long.class, cd);
    }

    /** 작성 중 PROCESS_4M ECO — FG1 품목 + FG1 × DRAW 공정, 고객 칸 cust(null 이면 없음) */
    long eco(String c, String cust) {
        long e = accounts.query(c, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, change_kind, mark_until, cust_id, created_by, updated_by)
                values (?, ?, '금형 교체', 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, 'PROCESS_4M', (now() at time zone 'Asia/Seoul')::date + 7,
                        (select cust_id from mst_cust where cust_cd = ?), 't', 't') returning eco_id""",
                Long.class, c, "ECO-N-" + (++seq), userId, cust);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG1'), 't', 't')""", c, e);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, proc_id, created_by, updated_by)
                values (?, ?, 2, 'PROC', (select item_id from mst_item where item_cd = 'FG1'), (select proc_id from mst_proc where proc_cd = 'DRAW'), 't', 't')""",
                c, e);
        return e;
    }

    void change(String c, long e, String... types) {
        accounts.exec(c, "insert into qms_change (eco_id, comp_cd, eco_no, m4_types, before_desc, after_desc, created_by, updated_by) "
                + "values (?, ?, '-', ?::varchar(10)[], '구 금형', '신 금형', 't', 't')", e, c, "{" + String.join(",", types) + "}");
    }

    void release(String c, long e) {
        accounts.exec(c, "update plm_eco set status = 'PENDING' where eco_id = ?", e);
        accounts.exec(c, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", e);
        accounts.exec(c, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", e);
    }

    long pcn(String c, long e, String cust, int dueOffset, String status) {
        long id = accounts.query(c, """
                insert into plm_pcn (comp_cd, pcn_no, eco_id, cust_id, notified_on, response_due, created_by, updated_by)
                values (?, ?, ?, (select cust_id from mst_cust where cust_cd = ?), ?, ?, 't', 't') returning pcn_id""",
                Long.class, c, "PCN-" + (++seq), e, cust, today.minusDays(10), today.plusDays(dueOffset));
        if (!"SENT".equals(status)) {
            accounts.exec(c, "update plm_pcn set status = ?, responded_on = ? where pcn_id = ?", status, today.minusDays(1), id);
        }
        return id;
    }

    /** CSR 만들기 — targets = JSON 배열 글자(빈 배열 = 고객 전체), from = 시작일 */
    long csr(String cust, String no, String from, String notifyReq, String targets) throws Exception {
        return id(postJson(S, "{\"custCd\":" + q(cust) + ",\"csrNo\":" + q(no) + ",\"csrRev\":\"A\",\"title\":\"고객 요구\",\"effectiveFrom\":"
                + q(from) + ",\"notifyReq\":" + notifyReq + ",\"targets\":" + targets + "}"));
    }

    ResultActions view(long e) throws Exception {
        return mvc.perform(get(C + "/" + e + "/cust-notify").with(admin));
    }

    int ecoVer(long e) {
        return accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, e);
    }

    ResultActions submit(long e) throws Exception {
        return postJson(ECO + "/" + e + "/submit", "{\"rowVer\":" + ecoVer(e) + ",\"lines\":[{\"userId\":" + approverId + "}]}");
    }

    // ---------- 정상 ----------

    @Test
    void 맞는_CSR만_유형중_큰_요구_근거_승인꺼짐_경고() throws Exception {
        String past = today.minusDays(30).toString();
        csr("C1", "CSR-ALL", past, "{\"MACHINE\":\"NOTIFY\",\"MAN\":\"APPROVE\"}", "[]");                                      // 고객 전체
        csr("C1", "CSR-FG1", past, "{\"MATERIAL\":\"APPROVE\"}", "[{\"targetType\":\"ITEM\",\"targetCd\":\"FG1\"}]");          // 품목 맞음
        csr("C1", "CSR-RM1", past, "{\"MACHINE\":\"APPROVE\"}", "[{\"targetType\":\"ITEM\",\"targetCd\":\"RM1\"}]");           // 다른 품목
        csr("C1", "CSR-COIL", past, "{\"MACHINE\":\"APPROVE\"}", "[{\"targetType\":\"ITEM_CLASS\",\"targetCd\":\"COIL\"}]");   // FG1 은 클래스 없음
        csr("C1", "CSR-FUT", today.plusDays(1).toString(), "{\"MACHINE\":\"APPROVE\"}", "[]");                               // 시작일 미래
        long off = csr("C1", "CSR-OFF", past, "{\"MACHINE\":\"APPROVE\"}", "[]");
        accounts.exec(comp, "update qms_cust_csr set use_yn = 'N' where csr_id = ?", off);                                 // 사용 안 함
        csr("C2", "CSR-C2", past, "{\"MACHINE\":\"APPROVE\"}", "[]");                                                        // 다른 고객
        long e = eco(comp, "C1");
        change(comp, e, "MACHINE", "MATERIAL");
        view(e).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.custCd").value("C1"))
                .andExpect(jsonPath("$.data.state").value("JUDGED"))
                .andExpect(jsonPath("$.data.level").value("APPROVE"))
                .andExpect(jsonPath("$.data.basis.length()").value(2))
                .andExpect(jsonPath("$.data.basis[0].m4Type").value("MACHINE"))
                .andExpect(jsonPath("$.data.basis[0].csrNo").value("CSR-ALL"))
                .andExpect(jsonPath("$.data.basis[1].m4Type").value("MATERIAL"))
                .andExpect(jsonPath("$.data.basis[1].csrNo").value("CSR-FG1"))
                .andExpect(jsonPath("$.data.custApprovalStatus").value("NA"))
                .andExpect(jsonPath("$.data.warnings.length()").value(1))
                .andExpect(jsonPath("$.data.warnings[0].code").value("QMS_CUST_APPROVAL_NEEDED"))
                .andExpect(jsonPath("$.data.warnings[0].message").isNotEmpty())
                .andExpect(jsonPath("$.data.gate").value("WARN"));
        // ECO 고객 승인을 켜면 경고 없음
        accounts.exec(comp, "update plm_eco set cust_approval_req_yn = 'Y', cust_approval_status = 'PENDING' where eco_id = ?", e);
        view(e).andExpect(jsonPath("$.data.warnings.length()").value(0)).andExpect(jsonPath("$.data.level").value("APPROVE"));
        // MAN 만이면 고객 전체 CSR 의 APPROVE, ENV 만이면 요구 없음
        long e2 = eco(comp, "C1");
        change(comp, e2, "ENV");
        view(e2).andExpect(jsonPath("$.data.level").value("NONE")).andExpect(jsonPath("$.data.basis.length()").value(0));
    }

    @Test
    void 고객없음_4M없음은_경고없음() throws Exception {
        csr("C1", "CSR-ALL", today.minusDays(1).toString(), "{\"MACHINE\":\"APPROVE\"}", "[]");
        long none = eco(comp, null);
        change(comp, none, "MACHINE");
        view(none).andExpect(jsonPath("$.data.state").value("NO_CUSTOMER")).andExpect(jsonPath("$.data.custId").doesNotExist())
                .andExpect(jsonPath("$.data.level").doesNotExist()).andExpect(jsonPath("$.data.warnings.length()").value(0));
        long no4m = eco(comp, "C1");
        view(no4m).andExpect(jsonPath("$.data.state").value("NO_4M")).andExpect(jsonPath("$.data.warnings.length()").value(0));
        view(9_999_999L).andExpect(status().isNotFound());
    }

    @Test
    void PCN_없음은_적용뒤만_기한지남_마지막반려_다른고객PCN은_따로() throws Exception {
        csr("C1", "CSR-ALL", today.minusDays(1).toString(), "{\"MACHINE\":\"NOTIFY\"}", "[]");
        long e = eco(comp, "C1");
        change(comp, e, "MACHINE");
        view(e).andExpect(jsonPath("$.data.level").value("NOTIFY")).andExpect(jsonPath("$.data.warnings.length()").value(0));
        release(comp, e);
        view(e).andExpect(jsonPath("$.data.warnings[*].code").value(hasItem("QMS_CUST_PCN_MISSING")));
        pcn(comp, e, "C2", -5, "SENT");                                     // 다른 고객 — 판단에 안 넣음
        view(e).andExpect(jsonPath("$.data.warnings[*].code").value(hasItem("QMS_CUST_PCN_MISSING")))
                .andExpect(jsonPath("$.data.otherCustPcns").value(1)).andExpect(jsonPath("$.data.pcns.length()").value(0));
        // 같은 ECO·고객의 열린(SENT) PCN 은 하나(PLM uq_plm_pcn_open) — 하나씩 응답하며 본다
        pcn(comp, e, "C1", -1, "SENT");                                     // 기한 어제 — 지남
        view(e).andExpect(jsonPath("$.data.pcns.length()").value(1))
                .andExpect(jsonPath("$.data.pcns[0].overdue").value(true))
                .andExpect(jsonPath("$.data.warnings.length()").value(1))
                .andExpect(jsonPath("$.data.warnings[0].code").value("QMS_CUST_PCN_OVERDUE"));
        String ack = "update plm_pcn set status = 'ACK', responded_on = ? where eco_id = ? and cust_id = ? and status = 'SENT'";
        accounts.exec(comp, ack, today, e, custId(comp, "C1"));
        pcn(comp, e, "C1", 0, "SENT");                                      // 기한 오늘 — 지남 아님
        view(e).andExpect(jsonPath("$.data.pcns.length()").value(2))
                .andExpect(jsonPath("$.data.pcns[1].overdue").value(false))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
        accounts.exec(comp, ack, today, e, custId(comp, "C1"));
        pcn(comp, e, "C1", 30, "REJECTED");
        view(e).andExpect(jsonPath("$.data.warnings.length()").value(1))
                .andExpect(jsonPath("$.data.warnings[0].code").value("QMS_CUST_PCN_REJECTED"))
                .andExpect(jsonPath("$.data.pcns[2].status").value("REJECTED"));
        // PLM 계약(읽기만) — 고객 id·PCN 목록(통지일·id 순, 다른 고객 포함)
        assertThat(runner.call(comp, () -> ecoLookup.custId(e))).contains(custId(comp, "C1"));
        assertThat(runner.call(comp, () -> ecoLookup.pcns(e))).hasSize(4);
    }

    // ---------- 상신 정책 ----------

    @Test
    void 정책BLOCK이면_승인요구_어긴_상신_400_WARN이면_통과() throws Exception {
        csr("C1", "CSR-ALL", today.minusDays(1).toString(), "{\"MACHINE\":\"APPROVE\"}", "[]");
        long e = eco(comp, "C1");
        change(comp, e, "MACHINE");
        policy("QMS_CUST_NOTIFY_GATE", "BLOCK");
        mvc.perform(get(C + "/" + e).with(admin)).andExpect(jsonPath("$.data.issues[*].code").value(hasItem("QMS_CUST_APPROVAL_NEEDED")));
        submit(e).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey == 'qms4m' && @.field == 'custApproval')].code").value(hasItem("QMS_CUST_APPROVAL_NEEDED")));
        assertThat(accounts.query(comp, "select status from plm_eco where eco_id = ?", String.class, e)).isEqualTo("DRAFT");
        // 고객 승인을 켜면 BLOCK 이어도 통과
        accounts.exec(comp, "update plm_eco set cust_approval_req_yn = 'Y', cust_approval_status = 'PENDING' where eco_id = ?", e);
        submit(e).andExpect(status().isOk());
        // WARN(정책 바꿈): 승인 꺼진 ECO 도 상신된다(경고는 화면만)
        accounts.exec(comp, "update sys_policy set policy_value = 'WARN' where policy_key = 'QMS_CUST_NOTIFY_GATE'");
        long w = eco(comp, "C1");
        change(comp, w, "MACHINE");
        submit(w).andExpect(status().isOk());
        view(w).andExpect(jsonPath("$.data.warnings[0].code").value("QMS_CUST_APPROVAL_NEEDED"));
    }

    // ---------- CSR 요구 입력 ----------

    @Test
    void CSR_요구_입력_칸오류_NONE은_저장안함_null이면_그대로() throws Exception {
        String past = today.minusDays(1).toString();
        postJson(S, "{\"custCd\":\"C1\",\"csrNo\":\"X1\",\"csrRev\":\"A\",\"title\":\"t\",\"effectiveFrom\":" + q(past)
                + ",\"notifyReq\":{\"RAIN\":\"NOTIFY\",\"MAN\":\"MAYBE\"}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("notifyReq")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("notifyReq.MAN")));
        long id = csr("C1", "X2", past, "{\"MAN\":\"NONE\",\"MACHINE\":\"APPROVE\",\"ENV\":\"\"}", "[]");
        assertThat(accounts.query(comp, "select notify_req::text from qms_cust_csr where csr_id = ?", String.class, id))
                .isEqualTo("{\"MACHINE\": \"APPROVE\"}");
        mvc.perform(get(S + "/" + id).with(admin)).andExpect(jsonPath("$.data.csr.notifyReq.MACHINE").value("APPROVE"))
                .andExpect(jsonPath("$.data.csr.notifyReq.MAN").doesNotExist());
        int v = accounts.query(comp, "select row_ver from qms_cust_csr where csr_id = ?", Integer.class, id);
        // notifyReq 없이 고치면 그대로, 모두 NONE 이면 비움
        putJson(S + "/" + id, "{\"csrNo\":\"X2\",\"csrRev\":\"A\",\"title\":\"t2\",\"effectiveFrom\":" + q(past) + ",\"rowVer\":" + v + "}")
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select notify_req ->> 'MACHINE' from qms_cust_csr where csr_id = ?", String.class, id)).isEqualTo("APPROVE");
        putJson(S + "/" + id, "{\"csrNo\":\"X2\",\"csrRev\":\"A\",\"title\":\"t2\",\"effectiveFrom\":" + q(past) + ",\"notifyReq\":{\"MACHINE\":\"NONE\"},\"rowVer\":"
                + (v + 1) + "}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select notify_req::text from qms_cust_csr where csr_id = ?", String.class, id)).isEqualTo("{}");
        // 직접 SQL — 정해진 키·값만
        constraint(comp, "ck_qms_cust_csr_notify_req", () -> accounts.exec(comp,
                "update qms_cust_csr set notify_req = '{\"MAN\":\"LATER\"}'::jsonb where csr_id = ?", id));
        constraint(comp, "ck_qms_cust_csr_notify_req", () -> accounts.exec(comp,
                "update qms_cust_csr set notify_req = '{\"RAIN\":\"NOTIFY\"}'::jsonb where csr_id = ?", id));
        constraint(comp, "ck_qms_cust_csr_notify_req", () -> accounts.exec(comp,
                "update qms_cust_csr set notify_req = '[\"MAN\"]'::jsonb where csr_id = ?", id));
        constraint(comp, "ck_qms_cust_csr_notify_req", () -> accounts.exec(comp,
                "update qms_cust_csr set notify_req = '{\"MAN\":1}'::jsonb where csr_id = ?", id));
    }

    // ---------- A사로 B사 · 권한 ----------

    @Test
    void B사는_A사_ECO_통지판단을_못보고_PLM계약도_빈값_B사CSR은_안섞임() throws Exception {
        long e = eco(comp, "C1");
        change(comp, e, "MACHINE");
        pcn(comp, e, "C1", 5, "SENT");
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-change", "SEARCH");
        accounts.grant(other, bUser, "QA", "qms-csr", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(C + "/" + e + "/cust-notify").with(b)).andExpect(status().isNotFound());
        assertThat(runner.call(other, () -> ecoLookup.custId(e))).isEmpty();
        assertThat(runner.call(other, () -> ecoLookup.pcns(e))).isEmpty();
        // B사에 같은 고객 코드 C1 의 APPROVE CSR — A사 판단은 그대로 요구 없음
        fixtures(other);
        custs(other);
        accounts.exec(other, """
                insert into qms_cust_csr (comp_cd, cust_id, csr_no, csr_rev, title, effective_from, notify_req, created_by, updated_by)
                values (?, (select cust_id from mst_cust where cust_cd = 'C1'), 'B-CSR', 'A', 't', current_date - 1, '{"MACHINE":"APPROVE"}'::jsonb, 't', 't')""", other);
        view(e).andExpect(jsonPath("$.data.level").value("NONE")).andExpect(jsonPath("$.data.warnings.length()").value(0));
        // 권한: qms-change SEARCH 없는 사용자
        long noView = accounts.newUser(comp, "csr1", PW);
        accounts.grant(comp, noView, "CSR", "qms-csr", "SEARCH");
        mvc.perform(get(C + "/" + e + "/cust-notify").with(accounts.as(comp, noView, "csr1"))).andExpect(status().isForbidden());
    }
}
