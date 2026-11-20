package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.TestAccounts;

/**
 * P3 QMS Q4 시정조치 8D·고객 요구사항 CSR — 정상 흐름(단계·수평전개·결재 없는 종결·결재 승인/반려/회수·첨부 잠금) +
 * CSR override 기한(대상 우선·시작일·스냅샷) + 이상 사용(A~K: A사로 B사, 직접 SQL 전이·불변·단계 CHECK, 입력, 권한, 멱등).
 */
class CapaApiTest extends QmsTestBase {

    static final String A = "/api/qms/capas";
    static final String S = "/api/qms/csrs";
    static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
    static final String D1_3 = "\"d1Team\":\"품질·생산\",\"d2Problem\":\"홀 지름 상한 초과\",\"d3Contain\":\"재고 전수 선별\"";

    @BeforeEach
    void capaSetUp() {
        accounts.grant(comp, userId, "QA", "qms-capa", "SEARCH", "SAVE", "APPROVE", "EXCEL");
        accounts.grant(comp, userId, "QA", "qms-csr", "SEARCH", "SAVE");
        custs(comp);
    }

    void custs(String c) {
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', '고객1', 'SALES', 't', 't'), (?, 'C2', '고객2', 'SALES', 't', 't')""", c, c);
    }

    void approvalPolicy(String c, String yn) {
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_DOC_APPROVAL', ?, 't', 't')",
                c, yn);
    }

    long capa(String source, String cust, String item, String occurredOn) throws Exception {
        return id(postJson(A, "{\"source\":" + q(source) + ",\"custCd\":" + q(cust) + ",\"itemCd\":" + q(item) + ",\"title\":\"클레임\""
                + ",\"occurredOn\":" + q(occurredOn) + "}"));
    }

    String col(long id, String column) {
        return accounts.query(comp, "select " + column + "::text from qms_capa where capa_id = ?", String.class, id);
    }

    int ver(long id) {
        return accounts.query(comp, "select row_ver from qms_capa where capa_id = ?", Integer.class, id);
    }

    /** 화면처럼 폼 전체를 보낸다(고객·품목은 지금 값) */
    ResultActions save(long id, String fields) throws Exception {
        String cust = accounts.query(comp, "select u.cust_cd from qms_capa c left join mst_cust u on u.cust_id = c.cust_id where c.capa_id = ?",
                String.class, id);
        String item = accounts.query(comp, "select i.item_cd from qms_capa c left join mst_item i on i.item_id = c.item_id where c.capa_id = ?",
                String.class, id);
        return putJson(A + "/" + id, "{" + fields + (fields.isEmpty() ? "" : ",") + "\"title\":\"클레임\",\"custCd\":" + q(cust)
                + ",\"itemCd\":" + q(item) + ",\"rowVer\":" + ver(id) + "}");
    }

    ResultActions act(long id, String action) throws Exception {
        return postJson(A + "/" + id + "/" + action, "{\"rowVer\":" + ver(id) + "}");
    }

    static String spread(String type, String cd, String st) {
        return "{\"targetType\":" + q(type) + ",\"targetCd\":" + q(cd) + ",\"status\":" + q(st) + "}";
    }

    ResultActions upload(RequestPostProcessor who, long id) throws Exception {
        return mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "8D.pdf", "application/pdf", PDF))
                .param("targetType", "qms-capa").param("targetId", String.valueOf(id)).with(who).with(csrf()));
    }

    ResultActions apr(long aprId, String action, RequestPostProcessor who, String comment) throws Exception {
        return mvc.perform(post("/api/platform/approvals/" + aprId + "/" + action).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(comment == null ? "{}" : "{\"comment\":\"" + comment + "\"}"));
    }

    /** OPEN → VERIFIED (D1~D7) */
    void toVerified(long id) throws Exception {
        save(id, D1_3 + ",\"d4Cause\":\"금형 마모\",\"d5Action\":\"금형 교체\",\"d6Verify\":\"3로트 Cpk 1.67\",\"d7Prevent\":\"점검 주기 단축\"")
                .andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            act(id, "advance").andExpect(status().isOk());
        }
        assertThat(col(id, "status")).isEqualTo("VERIFIED");
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 단계별_칸_검사_수평전개_결재없는_종결_첨부_잠금() throws Exception {
        approvalPolicy(comp, "N");
        long id = capa("CUSTOMER_CLAIM", "c1", "fg1", today.minusDays(1).toString());
        assertThat(col(id, "capa_no")).matches("^CA[0-9]{4}-0001$");
        assertThat(col(id, "status")).isEqualTo("OPEN");
        // CSR 없음 → 정책 기본(봉쇄 2일, 종결 60일)
        assertThat(col(id, "contain_due")).isEqualTo(today.plusDays(1).toString());
        assertThat(col(id, "close_due")).isEqualTo(today.plusDays(59).toString());
        assertThat(col(id, "owner_user_id")).isEqualTo(String.valueOf(userId));
        // D1~D3 없이 다음 단계 → 칸 오류 3개
        act(id, "advance").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QMS_CAPA_STEP_CHECK"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("d1Team", "d2Problem", "d3Contain")));
        save(id, D1_3 + ",\"spreads\":[" + spread("ITEM", "rm1", "OPEN") + "," + spread("PROC", "DRAW", "NA") + "]").andExpect(status().isOk());
        upload(admin, id).andExpect(status().isOk());
        act(id, "advance").andExpect(status().isOk());
        assertThat(col(id, "status")).isEqualTo("CONTAINED");
        act(id, "advance").andExpect(jsonPath("$.errors[0].field").value("d4Cause"));
        save(id, D1_3 + ",\"d4Cause\":\"금형 마모\"").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from qms_capa_spread where capa_id = ?", Integer.class, id))
                .as("spreads 를 안 보내면 그대로").isEqualTo(2);
        act(id, "advance").andExpect(status().isOk());
        save(id, D1_3 + ",\"d4Cause\":\"금형 마모\",\"d5Action\":\"금형 교체\",\"d6Verify\":\"Cpk 1.67\"").andExpect(status().isOk());
        act(id, "advance").andExpect(status().isOk());
        // 수평전개 진행 중 → 효과 확인 불가, D7 도 비어 있음
        act(id, "advance").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("d7Prevent", "spreads")))
                .andExpect(jsonPath("$.errors[?(@.field == 'spreads')].code").value(hasItem("CAPA_SPREAD_OPEN")));
        String full = D1_3 + ",\"d4Cause\":\"금형 마모\",\"d5Action\":\"금형 교체\",\"d6Verify\":\"Cpk 1.67\",\"d7Prevent\":\"점검 주기\"";
        save(id, full + ",\"spreads\":[" + spread("ITEM", "RM1", "DONE") + "," + spread("PROC", "DRAW", "NA") + "]").andExpect(status().isOk());
        mvc.perform(get(A + "/" + id).with(admin)).andExpect(jsonPath("$.data.capa.spreadOpenCnt").value(0))
                .andExpect(jsonPath("$.data.spreads[0].targetCd").value("RM1"));
        act(id, "advance").andExpect(status().isOk());
        assertThat(col(id, "status")).isEqualTo("VERIFIED");
        act(id, "advance").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_CAPA_STATE"));
        // 효과 확인 뒤 수평전개 고정, 내용은 고칠 수 있다
        save(id, full + ",\"spreads\":[]").andExpect(jsonPath("$.errors[0].code").value("CAPA_SPREAD_LOCKED"));
        // D8 없이 종결 → 칸 오류
        act(id, "close").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("d8Close"));
        save(id, full + ",\"d8Close\":\"팀 인정\"").andExpect(status().isOk());
        act(id, "close").andExpect(status().isOk());
        assertThat(col(id, "status") + ":" + col(id, "closed_by")).isEqualTo("CLOSED:admin1");
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'qms-capa' and target_id = ? and locked_yn = 'Y'",
                Integer.class, String.valueOf(id))).isEqualTo(1);
        upload(admin, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        save(id, full).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_CAPA_STATE"));
        postJson(A + "/" + id + "/cancel", "{\"rowVer\":" + ver(id) + ",\"reason\":\"x\"}").andExpect(jsonPath("$.code").value("QMS_CAPA_STATE"));
        // 목록: 종결은 기본 조회에서 빠지고 포함하면 보인다, 엑셀
        mvc.perform(get(A).with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(A).param("withClosed", "true").with(admin)).andExpect(jsonPath("$.list[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.list[0].dueState").doesNotExist());
        mvc.perform(json(post(A + "/export"), "{\"withClosed\":true}").with(admin)).andExpect(status().isOk());
    }

    @Test
    void 종결_결재_반려_회수는_대책완료로_승인은_종결() throws Exception {
        long approver = accounts.newUser(comp, "lee", PW);
        accounts.grant(comp, approver, "QA2", "qms-capa", "SEARCH");
        RequestPostProcessor lee = accounts.as(comp, approver, "lee");
        long id = capa("INTERNAL", null, null, null);
        assertThat(col(id, "occurred_on")).isEqualTo(today.toString());
        toVerified(id);
        save(id, D1_3 + ",\"d4Cause\":\"c\",\"d5Action\":\"a\",\"d6Verify\":\"v\",\"d7Prevent\":\"p\",\"d8Close\":\"끝\"").andExpect(status().isOk());
        upload(admin, id).andExpect(status().isOk());
        act(id, "close").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_DOC_APPROVAL_REQUIRED"));
        mvc.perform(get(A + "/" + id).with(admin)).andExpect(jsonPath("$.data.approvalRequired").value(true));
        String lines = "\"lines\":[{\"userId\":" + approver + ",\"lineType\":\"APPROVE\"}]";
        long apr1 = id(postJson(A + "/" + id + "/submit", "{\"rowVer\":" + ver(id) + "," + lines + "}"));
        assertThat(col(id, "status")).isEqualTo("CLOSING");
        // 결재 중: 내용·첨부·취소·다시 상신 불가
        save(id, D1_3).andExpect(jsonPath("$.code").value("QMS_CAPA_STATE"));
        upload(admin, id).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        postJson(A + "/" + id + "/cancel", "{\"rowVer\":" + ver(id) + ",\"reason\":\"x\"}").andExpect(jsonPath("$.code").value("QMS_CAPA_STATE"));
        postJson(A + "/" + id + "/submit", "{\"rowVer\":" + ver(id) + "," + lines + "}").andExpect(status().isConflict());
        mvc.perform(get(A + "/" + id).with(lee)).andExpect(jsonPath("$.data.approval.myTurn").value(true));
        mvc.perform(get(A + "/" + id).with(admin)).andExpect(jsonPath("$.data.approval.canWithdraw").value(true));
        // 반려 → 대책 완료(효과 확인부터 다시), 수평전개를 다시 고칠 수 있다
        apr(apr1, "reject", lee, "효과 근거 부족").andExpect(status().isOk());
        assertThat(col(id, "status")).isEqualTo("ACTIONED");
        String full = D1_3 + ",\"d4Cause\":\"c\",\"d5Action\":\"a\",\"d6Verify\":\"v\",\"d7Prevent\":\"p\",\"d8Close\":\"끝\"";
        save(id, full + ",\"spreads\":[" + spread("CUST", "C2", "DONE") + "]").andExpect(status().isOk());
        act(id, "advance").andExpect(status().isOk());
        // 다시 상신 → 회수 → 대책 완료
        long apr2 = id(postJson(A + "/" + id + "/submit", "{\"rowVer\":" + ver(id) + "," + lines + "}"));
        apr(apr2, "withdraw", admin, null).andExpect(status().isOk());
        assertThat(col(id, "status")).isEqualTo("ACTIONED");
        act(id, "advance").andExpect(status().isOk());
        // 다시 상신 → 승인 → 종결 + 첨부 잠금
        long apr3 = id(postJson(A + "/" + id + "/submit", "{\"rowVer\":" + ver(id) + "," + lines + "}"));
        apr(apr3, "approve", lee, null).andExpect(status().isOk());
        assertThat(col(id, "status") + ":" + col(id, "closed_by") + ":" + col(id, "apr_id")).isEqualTo("CLOSED:lee:" + apr3);
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'qms-capa' and target_id = ? and locked_yn = 'Y'",
                Integer.class, String.valueOf(id))).isEqualTo(1);
        // 결재선 없음 → 결재 엔진 거부, 상태 그대로
        long id2 = capa("AUDIT", null, null, null);
        toVerified(id2);
        save(id2, D1_3 + ",\"d4Cause\":\"c\",\"d5Action\":\"a\",\"d6Verify\":\"v\",\"d7Prevent\":\"p\",\"d8Close\":\"끝\"").andExpect(status().isOk());
        postJson(A + "/" + id2 + "/submit", "{\"rowVer\":" + ver(id2) + ",\"lines\":[]}").andExpect(status().isBadRequest());
        assertThat(col(id2, "status")).isEqualTo("VERIFIED");
    }

    @Test
    void 취소는_사유와_함께_종결결재_전까지_취소뒤_불변() throws Exception {
        long id = capa("SUPPLIER", "C2", null, null);
        save(id, D1_3).andExpect(status().isOk());
        act(id, "advance").andExpect(status().isOk());
        postJson(A + "/" + id + "/cancel", "{\"rowVer\":" + ver(id) + ",\"reason\":\" \"}").andExpect(jsonPath("$.errors[0].field").value("reason"));
        postJson(A + "/" + id + "/cancel", "{\"rowVer\":0,\"reason\":\"중복 접수\"}").andExpect(status().isConflict());
        postJson(A + "/" + id + "/cancel", "{\"rowVer\":" + ver(id) + ",\"reason\":\"중복 접수\"}").andExpect(status().isOk());
        assertThat(col(id, "status") + ":" + col(id, "cancel_reason")).isEqualTo("CANCELLED:중복 접수");
        act(id, "advance").andExpect(jsonPath("$.code").value("QMS_CAPA_STATE"));
        upload(admin, id).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(get(A).param("status", "CANCELLED").param("withClosed", "true").with(admin)).andExpect(jsonPath("$.total").value(1));
    }

    // ---------- CSR ----------

    long csr(String cust, String no, String from, String overrides, String targets) throws Exception {
        return id(postJson(S, "{\"custCd\":" + q(cust) + ",\"csrNo\":" + q(no) + ",\"csrRev\":\"A\",\"title\":\"고객 품질 요구\",\"effectiveFrom\":"
                + q(from) + ",\"overrides\":" + overrides + ",\"targets\":[" + targets + "]}"));
    }

    @Test
    void CSR_override_기한_대상있는_CSR_우선_시작일_출처_스냅샷() throws Exception {
        String past = today.minusDays(30).toString();
        long general = csr("C1", "CSR-GEN", past, "{\"CONTAIN_DAYS\":1,\"CLOSE_DAYS\":30}", "");
        long byItem = csr("C1", "CSR-FG1", past, "{\"CLOSE_DAYS\":10}", "{\"targetType\":\"ITEM\",\"targetCd\":\"FG1\"}");
        csr("C1", "CSR-FUT", today.plusDays(5).toString(), "{\"CONTAIN_DAYS\":9,\"CLOSE_DAYS\":99}", "");
        long byClass = csr("C1", "CSR-COIL", past, "{\"CONTAIN_DAYS\":3,\"CLOSE_DAYS\":20,\"RETENTION_YEARS\":15}",
                "{\"targetType\":\"ITEM_CLASS\",\"targetCd\":\"COIL\"}");
        String on = today.toString();
        // 품목 없는 클레임 → 대상 없는 CSR(시작 전 CSR 은 제외)
        long c0 = capa("CUSTOMER_CLAIM", "C1", null, on);
        assertThat(col(c0, "contain_due") + "/" + col(c0, "close_due") + "/" + col(c0, "csr_id"))
                .isEqualTo(today.plusDays(1) + "/" + today.plusDays(30) + "/" + general);
        mvc.perform(get(A + "/" + c0).with(admin)).andExpect(jsonPath("$.data.capa.csrLabel").value("CSR-GEN / A"));
        // FG1 → 품목 CSR(봉쇄는 그 CSR 에 없으니 정책 2일)
        long c1 = capa("CUSTOMER_CLAIM", "C1", "FG1", on);
        assertThat(col(c1, "contain_due") + "/" + col(c1, "close_due") + "/" + col(c1, "csr_id"))
                .isEqualTo(today.plusDays(2) + "/" + today.plusDays(10) + "/" + byItem);
        // RM1(클래스 COIL) → 클래스 CSR
        long c2 = capa("CUSTOMER_CLAIM", "C1", "RM1", on);
        assertThat(col(c2, "close_due") + "/" + col(c2, "csr_id")).isEqualTo(today.plusDays(20) + "/" + byClass);
        // 다른 고객·내부 출처 → 정책
        long c3 = capa("CUSTOMER_CLAIM", "C2", "FG1", on);
        long c4 = capa("INTERNAL", "C1", "FG1", on);
        assertThat(col(c3, "csr_id")).isNull();
        assertThat(col(c4, "close_due")).isEqualTo(today.plusDays(60).toString());
        assertThat(col(c4, "csr_id")).isNull();
        // CSR 을 고쳐도 지난 8D 기한은 그대로(스냅샷), 사용 중지 CSR 은 새 8D 에 안 쓴다
        int v = accounts.query(comp, "select row_ver from qms_cust_csr where csr_id = ?", Integer.class, general);
        putJson(S + "/" + general, "{\"csrNo\":\"CSR-GEN\",\"csrRev\":\"A\",\"title\":\"t\",\"effectiveFrom\":" + q(past)
                + ",\"overrides\":{\"CLOSE_DAYS\":5},\"useYn\":\"N\",\"rowVer\":" + v + "}").andExpect(status().isOk());
        assertThat(col(c0, "close_due")).isEqualTo(today.plusDays(30).toString());
        long c5 = capa("CUSTOMER_CLAIM", "C1", null, on);
        assertThat(col(c5, "csr_id")).isNull();
        // 목록·상세
        mvc.perform(get(S).with(admin)).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get(S).param("withUnused", "true").with(admin)).andExpect(jsonPath("$.data.length()").value(4));
        mvc.perform(get(S + "/" + byClass).with(admin)).andExpect(jsonPath("$.data.csr.overrides.RETENTION_YEARS").value(15))
                .andExpect(jsonPath("$.data.targets[0].targetType").value("ITEM_CLASS"))
                .andExpect(jsonPath("$.data.csr.overridesJson").doesNotExist());
    }

    @Test
    void CSR_입력_이상_키_값_범위_고객고정_중복_대상() throws Exception {
        String past = today.minusDays(1).toString();
        postJson(S, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field")
                .value(hasItems("custCd", "csrNo", "csrRev", "title", "effectiveFrom")));
        postJson(S, "{\"custCd\":\"NOPE\",\"csrNo\":\"X\",\"csrRev\":\"A\",\"title\":\"t\",\"effectiveFrom\":" + q(past)
                + ",\"overrides\":{\"DROP\":1,\"CONTAIN_DAYS\":0,\"CLOSE_DAYS\":\"abc\",\"NOTIFY_HOURS\":1.5,\"RETENTION_YEARS\":101}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(6))
                .andExpect(jsonPath("$.errors[?(@.field == 'custCd')].code").value(hasItem("NO_SUCH_CODE")))
                .andExpect(jsonPath("$.errors[?(@.field == 'overrides')].code").value(hasItem("CSR_OVERRIDE_KEY")))
                .andExpect(jsonPath("$.errors[?(@.field == 'overrides.CONTAIN_DAYS')].code").value(hasItem("RANGE")))
                .andExpect(jsonPath("$.errors[?(@.field == 'overrides.CLOSE_DAYS')].code").value(hasItem("FORMAT")))
                .andExpect(jsonPath("$.errors[?(@.field == 'overrides.NOTIFY_HOURS')].code").value(hasItem("FORMAT")))
                .andExpect(jsonPath("$.errors[?(@.field == 'overrides.RETENTION_YEARS')].code").value(hasItem("RANGE")));
        // 대상: 없는 코드·중복·유형
        postJson(S, "{\"custCd\":\"C1\",\"csrNo\":\"X\",\"csrRev\":\"A\",\"title\":\"t\",\"effectiveFrom\":" + q(past) + ",\"targets\":["
                + "{\"targetType\":\"ITEM\",\"targetCd\":\"NOPE\"},{\"targetType\":\"ITEM\",\"targetCd\":\"FG1\"},{\"targetType\":\"ITEM\",\"targetCd\":\"fg1\"},"
                + "{\"targetType\":\"PROC\",\"targetCd\":\"DRAW\"}]}")
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0')].code").value(hasItem("NO_SUCH_CODE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '2')].code").value(hasItem("DUPLICATE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '3')].code").value(hasItem("CODE")));
        long id = csr("C1", "X", past, "{\"CLOSE_DAYS\":\"45\",\"NOTIFY_HOURS\":\"\"}", "");
        assertThat(accounts.query(comp, "select overrides::text from qms_cust_csr where csr_id = ?", String.class, id)).isEqualTo("{\"CLOSE_DAYS\": 45}");
        // 같은 고객·번호·리비전 → 409, 고객 고정, 옛 rowVer
        postJson(S, "{\"custCd\":\"C1\",\"csrNo\":\"X\",\"csrRev\":\"A\",\"title\":\"t\",\"effectiveFrom\":" + q(past) + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE"));
        putJson(S + "/" + id, "{\"custCd\":\"C2\",\"csrNo\":\"X\",\"csrRev\":\"A\",\"title\":\"t\",\"effectiveFrom\":" + q(past) + ",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].field").value("custCd")).andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson(S + "/" + id, "{\"csrNo\":\"X\",\"csrRev\":\"A\",\"title\":\"t\",\"effectiveFrom\":" + q(past) + ",\"rowVer\":9}")
                .andExpect(status().isConflict());
        mvc.perform(get(S + "/999999999").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(S).param("keyword", "x".repeat(101)).with(admin)).andExpect(status().isBadRequest());
    }

    // ---------- 이상 사용 ----------

    @Test
    void 시정조치_입력_이상_출처_고객_미래_고정값_조건조작() throws Exception {
        postJson(A, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field").value(hasItems("source", "title")));
        postJson(A, "{\"source\":\"RUMOR\",\"title\":\"t\"}").andExpect(jsonPath("$.errors[0].field").value("source"));
        postJson(A, "{\"source\":\"CUSTOMER_CLAIM\",\"title\":\"t\"}").andExpect(jsonPath("$.errors[0].field").value("custCd"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(A, "{\"source\":\"INTERNAL\",\"title\":\"t\",\"occurredOn\":" + q(today.plusDays(1)) + "}")
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        postJson(A, "{\"source\":\"INTERNAL\",\"title\":\"t\",\"occurredOn\":\"2026-13-01\"}").andExpect(jsonPath("$.errors[0].field").value("occurredOn"));
        postJson(A, "{\"source\":\"INTERNAL\",\"title\":\"t\",\"itemCd\":\"NOPE\",\"ownerUserId\":999999999}")
                .andExpect(jsonPath("$.errors.length()").value(2));
        postJson(A, "{\"source\":\"INTERNAL\",\"title\":\"t\",\"d2Problem\":\"" + "x".repeat(4001) + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        accounts.exec(comp, "update mst_cust set use_yn = 'N', updated_by = 't' where cust_cd = 'C2'");
        postJson(A, "{\"source\":\"SUPPLIER\",\"title\":\"t\",\"custCd\":\"C2\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 수평전개 행 오류(행 키 = 순번)
        postJson(A, "{\"source\":\"INTERNAL\",\"title\":\"t\",\"spreads\":[" + spread("ITEM", "NOPE", "OPEN") + "," + spread("BOM", "FG1", "OPEN") + ","
                + spread("PROC", "DRAW", "MAYBE") + "," + spread("CUST", "C1", "OPEN") + "," + spread("CUST", "c1", "DONE") + "]}")
                .andExpect(jsonPath("$.errors.length()").value(4))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0')].code").value(hasItem("NO_SUCH_CODE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '4')].code").value(hasItem("DUPLICATE")));
        // 출처 고정, 옛 rowVer
        long id = capa("INTERNAL", null, null, null);
        putJson(A + "/" + id, "{\"source\":\"AUDIT\",\"title\":\"t\",\"rowVer\":" + ver(id) + "}").andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson(A + "/" + id, "{\"title\":\"t\",\"rowVer\":9}").andExpect(status().isConflict());
        putJson(A + "/" + id, "{\"title\":\"t\"}").andExpect(status().isBadRequest());
        postJson(A + "/" + id + "/advance", "{}").andExpect(status().isBadRequest());
        // 목록 조건 조작
        mvc.perform(get(A).param("status", "X").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(A).param("dueState", "LATE").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(A).param("sort", "title,desc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(A).param("keyword", "%").with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(A + "/999999999").with(admin)).andExpect(status().isNotFound());
        postJson(A + "/999999999/advance", "{\"rowVer\":0}").andExpect(status().isNotFound());
    }

    @Test
    void 기한_판정_봉쇄_지남_종결_지남_목록_필터() throws Exception {
        long late = capa("INTERNAL", null, null, today.minusDays(10).toString());
        long ok = capa("INTERNAL", null, null, null);
        long contained = capa("INTERNAL", null, null, today.minusDays(5).toString());
        save(contained, D1_3).andExpect(status().isOk());
        act(contained, "advance").andExpect(status().isOk());
        long veryLate = capa("INTERNAL", null, null, today.minusDays(70).toString());
        mvc.perform(get(A + "/" + late).with(admin)).andExpect(jsonPath("$.data.capa.dueState").value("CONTAIN_OVERDUE"));
        mvc.perform(get(A + "/" + ok).with(admin)).andExpect(jsonPath("$.data.capa.dueState").value("ON_TIME"));
        mvc.perform(get(A + "/" + contained).with(admin)).andExpect(jsonPath("$.data.capa.dueState").value("ON_TIME"));
        mvc.perform(get(A + "/" + veryLate).with(admin)).andExpect(jsonPath("$.data.capa.dueState").value("CLOSE_OVERDUE"));
        mvc.perform(get(A).param("dueState", "CONTAIN_OVERDUE").with(admin)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].capaId").value((int) late));
        mvc.perform(get(A).param("dueState", "CLOSE_OVERDUE").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(A + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("capaNo", "dueState")));
    }

    @Test
    void 권한별_403_멱등키_409() throws Exception {
        String body = "{\"source\":\"INTERNAL\",\"title\":\"t\"}";
        mvc.perform(json(post(A), body).header("X-Idempotency-Key", "qms-capa-0001").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(A), body).header("X-Idempotency-Key", "qms-capa-0001").with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        String csrBody = "{\"custCd\":\"C1\",\"csrNo\":\"K\",\"csrRev\":\"A\",\"title\":\"t\",\"effectiveFrom\":" + q(today) + "}";
        mvc.perform(json(post(S), csrBody).header("X-Idempotency-Key", "qms-csr-0001").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(S), csrBody).header("X-Idempotency-Key", "qms-csr-0001").with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        approvalPolicy(comp, "N");
        long id = capa("INTERNAL", null, null, null);
        toVerified(id);
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "qms-capa", "SEARCH", "SAVE");
        RequestPostProcessor sv = accounts.as(comp, saver, "saver1");
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "qms-capa", "SEARCH");
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        mvc.perform(get(A + "/" + id).with(rd)).andExpect(status().isOk());
        mvc.perform(json(post(A), body).with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(A + "/" + id + "/advance"), "{\"rowVer\":0}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(A + "/export"), "{}").with(rd)).andExpect(status().isForbidden());
        upload(rd, id).andExpect(status().isForbidden());
        mvc.perform(json(post(A + "/" + id + "/close"), "{\"rowVer\":0}").with(sv)).andExpect(status().isForbidden());
        mvc.perform(get(S).with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(S), csrBody).with(sv)).andExpect(status().isForbidden());
        upload(sv, id).andExpect(status().isOk());
    }

    @Test
    void B사_사용자는_A사_시정조치_CSR_을_보지도_쓰지도_못한다() throws Exception {
        long id = capa("CUSTOMER_CLAIM", "C1", "FG1", null);
        long csrId = csr("C1", "A-CSR", today.toString(), "{\"CLOSE_DAYS\":7}", "");
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-capa", "SEARCH", "SAVE", "APPROVE", "EXCEL");
        accounts.grant(other, bUser, "QA", "qms-csr", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(A).param("withClosed", "true").with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(A + "/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(A + "/" + id), "{\"title\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        for (String act : new String[] {"advance", "close", "submit"}) {
            mvc.perform(json(post(A + "/" + id + "/" + act), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        }
        mvc.perform(json(post(A + "/" + id + "/cancel"), "{\"rowVer\":0,\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(S).param("withUnused", "true").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(S + "/" + csrId).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(S + "/" + csrId), "{\"csrNo\":\"x\",\"csrRev\":\"A\",\"title\":\"t\",\"effectiveFrom\":\"2026-01-01\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        // A사 8D 에 첨부 → 보이지 않는 대상 = 잠김
        upload(b, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        // B사 8D·CSR 에 A사 고객 코드(B사엔 없음) → 칸 오류, B사 고객 클레임 기한에 A사 CSR 이 섞이지 않는다
        mvc.perform(json(post(A), "{\"source\":\"CUSTOMER_CLAIM\",\"title\":\"t\",\"custCd\":\"C1\"}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        custs(other);
        fixtures(other);
        long bId = id(mvc.perform(json(post(A), "{\"source\":\"CUSTOMER_CLAIM\",\"title\":\"t\",\"custCd\":\"C1\"}").with(b)));
        assertThat(accounts.query(other, "select csr_id is null from qms_capa where capa_id = ?", Boolean.class, bId)).isTrue();
        // 직접 SQL: A사 8D 에 B사 수평전개, B사 8D 에 A사 고객·품목, B사 CSR 대상에 A사 품목, A사 행 상태 변경
        long aItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        long aCust = accounts.query(comp, "select cust_id from mst_cust where cust_cd = 'C1'", Long.class);
        constraint(other, "fk_qms_capa_spread_capa_comp", () -> accounts.exec(other,
                "insert into qms_capa_spread (comp_cd, capa_id, line_no, target_type, item_id, created_by) values (?, ?, 1, 'ITEM', ?, 't')",
                other, id, aItem));
        constraint(other, "fk_qms_capa_item_comp", () -> accounts.exec(other, "update qms_capa set item_id = ? where capa_id = ?", aItem, bId));
        constraint(other, "fk_qms_capa_cust_comp", () -> accounts.exec(other, "update qms_capa set cust_id = ? where capa_id = ?", aCust, bId));
        constraint(other, "fk_qms_capa_spread_comp_ref", () -> accounts.exec(other,
                "insert into qms_capa_spread (comp_cd, capa_id, line_no, target_type, cust_id, created_by) values (?, ?, 1, 'CUST', ?, 't')",
                other, bId, aCust));
        long bCsr = id(mvc.perform(json(post(S), "{\"custCd\":\"C1\",\"csrNo\":\"B\",\"csrRev\":\"A\",\"title\":\"t\",\"effectiveFrom\":"
                + q(today) + "}").with(b)));
        constraint(other, "fk_qms_cust_csr_target_comp_ref", () -> accounts.exec(other,
                "insert into qms_cust_csr_target (comp_cd, csr_id, item_id, created_by) values (?, ?, ?, 't')", other, bCsr, aItem));
        constraint(other, "fk_qms_cust_csr_cust_comp", () -> accounts.exec(other,
                "insert into qms_cust_csr (comp_cd, cust_id, csr_no, csr_rev, title, effective_from, created_by, updated_by) "
                        + "values (?, ?, 'Z', 'A', 't', " + TestAccounts.COMP_TODAY + ", 't', 't')", other, aCust));
        assertThat(accounts.exec(other, "update qms_capa set status = 'CANCELLED', cancel_reason = 'x' where capa_id = ?", id)).isZero();
        assertThat(col(id, "status")).isEqualTo("OPEN");
    }

    @Test
    void 직접SQL_전이_단계CHECK_고정값_불변_수평전개_잠금() throws Exception {
        long id = capa("CUSTOMER_CLAIM", "C1", "FG1", null);
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_capa where capa_id = ?", id));
        constraint(comp, "ck_qms_capa_open", () -> accounts.exec(comp,
                "insert into qms_capa (comp_cd, capa_no, source, title, occurred_on, owner_user_id, contain_due, close_due, status, created_by, updated_by) "
                        + "values (?, 'X1', 'INTERNAL', 't', " + TestAccounts.COMP_TODAY + ", ?, " + TestAccounts.COMP_TODAY + ", " + TestAccounts.COMP_TODAY + ", 'CLOSED', 't', 't')", comp, userId));
        constraint(comp, "ck_qms_capa_transition", () -> accounts.exec(comp, "update qms_capa set status = 'ANALYZED' where capa_id = ?", id));
        constraint(comp, "ck_qms_capa_d3", () -> accounts.exec(comp, "update qms_capa set status = 'CONTAINED' where capa_id = ?", id));
        constraint(comp, "ck_qms_capa_due_fixed", () -> accounts.exec(comp,
                "update qms_capa set close_due = close_due + 30 where capa_id = ?", id));
        constraint(comp, "ck_qms_capa_due_fixed", () -> accounts.exec(comp, "update qms_capa set source = 'AUDIT' where capa_id = ?", id));
        constraint(comp, "ck_qms_capa_immutable", () -> accounts.exec(comp, "update qms_capa set capa_no = 'X' where capa_id = ?", id));
        constraint(comp, "ck_qms_capa_cust", () -> accounts.exec(comp, "update qms_capa set cust_id = null where capa_id = ?", id));
        constraint(comp, "ck_qms_capa_cancel", () -> accounts.exec(comp, "update qms_capa set status = 'CANCELLED' where capa_id = ?", id));
        // 수평전개: 한 벌 교체만, 효과 확인 뒤 잠금, OPEN 이 남으면 효과 확인 불가
        save(id, D1_3 + ",\"d4Cause\":\"c\",\"d5Action\":\"a\",\"d6Verify\":\"v\",\"d7Prevent\":\"p\",\"spreads\":[" + spread("ITEM", "RM1", "OPEN") + "]")
                .andExpect(status().isOk());
        constraint(comp, "ck_qms_capa_spread_no_update", () -> accounts.exec(comp,
                "update qms_capa_spread set status = 'DONE' where capa_id = ?", id));
        for (String st : new String[] {"CONTAINED", "ANALYZED", "ACTIONED"}) {
            accounts.exec(comp, "update qms_capa set status = ? where capa_id = ?", st, id);
        }
        constraint(comp, "ck_qms_capa_spread_open", () -> accounts.exec(comp, "update qms_capa set status = 'VERIFIED' where capa_id = ?", id));
        accounts.exec(comp, "delete from qms_capa_spread where capa_id = ?", id);
        accounts.exec(comp, "update qms_capa set status = 'VERIFIED' where capa_id = ?", id);
        constraint(comp, "ck_qms_capa_spread_locked", () -> accounts.exec(comp,
                "insert into qms_capa_spread (comp_cd, capa_id, line_no, target_type, proc_id, created_by) "
                        + "select ?, ?, 1, 'PROC', proc_id, 't' from mst_proc where proc_cd = 'DRAW'", comp, id));
        constraint(comp, "ck_qms_capa_closing", () -> accounts.exec(comp,
                "update qms_capa set d8_close = '끝', status = 'CLOSING' where capa_id = ?", id));
        constraint(comp, "ck_qms_capa_d8", () -> accounts.exec(comp,
                "update qms_capa set status = 'CLOSED', closed_at = now(), closed_by = 't' where capa_id = ?", id));
        accounts.exec(comp, "update qms_capa set d8_close = '끝', status = 'CLOSED', closed_at = now(), closed_by = 't' where capa_id = ?", id);
        constraint(comp, "ck_qms_capa_immutable", () -> accounts.exec(comp, "update qms_capa set title = '몰래' where capa_id = ?", id));
        // CSR: 삭제 불가·고객 고정·정해진 키만·대상 수정 불가
        long csrId = csr("C1", "Q", today.toString(), "{}", "{\"targetType\":\"ITEM\",\"targetCd\":\"FG1\"}");
        long c2 = accounts.query(comp, "select cust_id from mst_cust where cust_cd = 'C2'", Long.class);
        constraint(comp, "ck_qms_cust_csr_key_immutable", () -> accounts.exec(comp, "update qms_cust_csr set cust_id = ? where csr_id = ?", c2, csrId));
        constraint(comp, "ck_qms_cust_csr_overrides", () -> accounts.exec(comp,
                "update qms_cust_csr set overrides = '{\"X\":1}'::jsonb where csr_id = ?", csrId));
        constraint(comp, "ck_qms_cust_csr_target_no_update", () -> accounts.exec(comp,
                "update qms_cust_csr_target set item_id = item_id where csr_id = ?", csrId));
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> accounts.exec(comp, "delete from qms_cust_csr where csr_id = ?", csrId));
        assertThat(t).as("CSR 삭제는 권한 또는 트리거가 막는다").isNotNull();
    }
}
