package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.notify.MailService;
import io.smarthappy.platform.notify.MailStatus;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 연계 현황·실패 재전송 (sys-outbox, docs/36 19장). 정상 흐름 + 이상 사용:
 * 권한 없이 API 직접(재시도·종결 403, SAVE 만으로 종결 403), FAIL 이 아닌 행(SENT·NEW·CANCELED·CLOSED_MANUAL) 409, 사유 없음·501자 400,
 * 동시에 재시도·종결(한쪽만), 결재 대기 중 재시도·종결 409·릴레이가 보내지 않음, A사로 B사(404·B사 행 그대로), 가림(대소문자·중첩·배열·본문 속 메일),
 * 수동 종결은 릴레이가 집지 않고 홈 카드·운영 지표에서 빠짐, 메일 상태 = 멈춤(대기로 보이지 않음).
 */
@AutoConfigureMockMvc
class OutboxAdminTest extends PostgresTestBase {

    static final String API = "/api/admin/outbox";
    static final String PW = "Outbox-adm-pw-1!";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    OutboxRelay relay;

    @Autowired
    TenantRunner runner;

    @Autowired
    MailService mail;

    String comp;
    long adminId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("OB");
        adminId = accounts.newUser(comp, "ob1", PW);
        accounts.grant(comp, adminId, "OBA", OutboxAdminService.PROG, "SEARCH", "SAVE", "FN1");
        accounts.grant(comp, adminId, "OBA", "home-main", "SEARCH");
        admin = accounts.as(comp, adminId, "ob1");
    }

    // ------------------------------------------------------------------ 도우미

    long row(String c, String topic, String status, int attempts, String payload, String bizKey) {
        String closed = "CLOSED_MANUAL".equals(status) ? "now()" : "null";
        return accounts.query(c, """
                insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, attempts, last_error, created_by,
                                        closed_at, closed_by, close_reason)
                values (?, gen_random_uuid(), ?, ?, ?::jsonb, ?, ?, 'boom: connection refused', 't',
                        """ + closed + ", " + ("CLOSED_MANUAL".equals(status) ? "'t', 'seed'" : "null, null") + """
                )
                returning outbox_id""", Long.class, c, topic, bizKey, payload, status, attempts);
    }

    long fail(String topic) {
        return row(comp, topic, "FAIL", 10, "{\"k\":1}", "BK-" + topic);
    }

    String st(long id) {
        return accounts.query(comp, "select status from sys_outbox where outbox_id = ?", String.class, id);
    }

    ResultActions call(MockHttpServletRequestBuilder b, RequestPostProcessor who) throws Exception {
        return mvc.perform(b.with(who).with(csrf()).contentType(MediaType.APPLICATION_JSON));
    }

    ResultActions retry(long id, RequestPostProcessor who) throws Exception {
        return call(post(API + "/" + id + "/retry"), who);
    }

    ResultActions close(long id, String body, RequestPostProcessor who) throws Exception {
        return call(post(API + "/" + id + "/close").content(body), who);
    }

    static String reason(String r) {
        return "{\"reason\":\"" + r + "\"}";
    }

    RequestPostProcessor userWith(String login, String... actions) {
        long u = accounts.newUser(comp, login, PW);
        if (actions.length > 0) {
            accounts.grant(comp, u, "G" + login.toUpperCase(), OutboxAdminService.PROG, actions);
        }
        return accounts.as(comp, u, login);
    }

    void approvalOn() {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'OUTBOX_CLOSE_APPROVAL', 'Y', 't', 't')""", comp);
    }

    // ------------------------------------------------------------------ 조회

    @Test
    void 기간_주제_상태_업무키로_조회하고_칸은_문구로_온다() throws Exception {
        long f = fail("NOTIFY");
        long s = row(comp, "MAIL", "SENT", 1, "{}", "PO-2026-01");
        row(comp, "MAIL", "CLOSED_MANUAL", 3, "{}", "PO-2026-02");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);

        String all = mvc.perform(get(API).param("from", today).param("to", today).param("total", "true").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(all, "$.total")).isEqualTo(3);
        mvc.perform(get(API).param("from", today).param("to", today).param("status", "FAIL").with(admin))
                .andExpect(jsonPath("$.list.length()").value(1)).andExpect(jsonPath("$.list[0].outboxId").value(f))
                .andExpect(jsonPath("$.list[0].tries").value("10/10"))
                // 다음 시도는 대기·보내는 중일 때만(실패 행에 시각이 보이면 곧 다시 나가는 것처럼 읽힌다)
                .andExpect(jsonPath("$.list[0].nextRetryAt").doesNotExist());
        mvc.perform(get(API).param("from", today).param("to", today).param("topic", "MAIL").param("bizKey", "PO-2026-0").with(admin))
                .andExpect(jsonPath("$.list.length()").value(2));
        mvc.perform(get(API).param("from", today).param("to", today).param("bizKey", "PO-2026-01").with(admin))
                .andExpect(jsonPath("$.list[0].outboxId").value(s));
        // LIKE 특수문자는 글자 그대로(전부 일치로 새지 않는다)
        mvc.perform(get(API).param("from", today).param("to", today).param("bizKey", "%").with(admin))
                .andExpect(jsonPath("$.list.length()").value(0));

        // 칸: 상태·주제는 서버 문구
        mvc.perform(get(API + "/columns").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[?(@.field == 'status')].labels.CLOSED_MANUAL").value("수동 종결"))
                .andExpect(jsonPath("$.list[?(@.field == 'topic')].labels.LABEL_PRINT").value("라벨 인쇄"));
        mvc.perform(get(API + "/topics").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[?(@.code == 'ERP_SHIP')].name").value("ERP 출하 송신"));
    }

    @Test
    void 조회_조건이_틀리면_400() throws Exception {
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        mvc.perform(get(API).param("from", today).param("to", today).param("status", "DONE").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(API).param("from", today).param("to", today).param("topic", "NOPE").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(API).param("from", today).param("to", today).param("bizKey", "x".repeat(101)).with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API).param("from", "2026-01-01").param("to", "2026-06-30").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(API).with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void 상세는_사람_정보를_가리고_DB_원문은_그대로() throws Exception {
        String payload = """
                {"To":"kim@example.com","custNm":"홍길동","jobId":5,"enabled":true,
                 "items":[{"Phone":"010-1234-5678","qty":3}],"contact":{"office":"02-555-0000","memo":"x"},
                 "note":"담당 lee.k@corp.co.kr 확인","ccList":["a@b.com","c@d.org"],"phoneNo":1012345678}""";
        long id = row(comp, "MAIL", "FAIL", 10, payload, "M-1");
        String json = mvc.perform(get(API + "/" + id).with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(json, "$.data.payload.To")).isEqualTo("k***@example.com");
        assertThat(JsonPath.<String>read(json, "$.data.payload.custNm")).isEqualTo("홍***");
        assertThat(JsonPath.<Integer>read(json, "$.data.payload.jobId")).isEqualTo(5);
        assertThat(JsonPath.<Boolean>read(json, "$.data.payload.enabled")).isTrue();
        assertThat(JsonPath.<String>read(json, "$.data.payload.items[0].Phone")).isEqualTo("0***");
        assertThat(JsonPath.<Integer>read(json, "$.data.payload.items[0].qty")).isEqualTo(3);
        assertThat(JsonPath.<String>read(json, "$.data.payload.contact.office")).isEqualTo("0***");
        assertThat(JsonPath.<String>read(json, "$.data.payload.note")).isEqualTo("담당 l***@corp.co.kr 확인");
        assertThat(JsonPath.<List<String>>read(json, "$.data.payload.ccList")).containsExactly("a***@b.com", "c***@d.org");
        assertThat(JsonPath.<String>read(json, "$.data.payload.phoneNo")).isEqualTo("***");
        assertThat(json).doesNotContain("kim@", "길동", "5678", "lee.k@", "0000");
        // 오류 전문·닫기 결재 꺼짐·인쇄 링크 없음(메일)
        assertThat(JsonPath.<String>read(json, "$.data.lastError")).isEqualTo("boom: connection refused");
        assertThat(JsonPath.<Boolean>read(json, "$.data.closeNeedsApproval")).isFalse();
        assertThat(JsonPath.<Object>read(json, "$.data.linkProg")).isNull();
        // DB 원문은 그대로 — 재전송은 원문으로
        assertThat(accounts.query(comp, "select payload ->> 'To' from sys_outbox where outbox_id = ?", String.class, id))
                .isEqualTo("kim@example.com");
        // 라벨 인쇄 상세 = WMS 인쇄 이력 링크
        long lp = row(comp, "LABEL_PRINT", "FAIL", 10, "{\"jobId\":7}", "J-7");
        mvc.perform(get(API + "/" + lp).with(admin)).andExpect(jsonPath("$.data.linkProg").value("wms-print-job"));
    }

    // ------------------------------------------------------------------ 재시도

    @Test
    void 재시도하면_처음부터_다시_보내고_기록이_남는다() throws Exception {
        long id = fail("NOTIFY");
        retry(id, admin).andExpect(status().isOk());
        assertThat(accounts.query(comp, """
                select concat_ws('|', status, attempts, retry_cnt, retried_by, retried_at is not null, next_retry_at <= now())
                  from sys_outbox where outbox_id = ?""", String.class, id)).isEqualTo("NEW|0|1|ob1|t|t");
        // 접속 로그 ACTION(SAVE)
        assertThat(accounts.query(comp, """
                select count(*) from sys_access_log where event_cd = 'ACTION' and prog_cd = ? and action_cd = 'SAVE' and login_id = 'ob1'""",
                Integer.class, OutboxAdminService.PROG)).isEqualTo(1);
        // 릴레이가 다시 보낸다
        relay.relay(comp);
        assertThat(st(id)).isEqualTo("SENT");
        // 이미 보낸 것은 다시 재시도할 수 없다
        retry(id, admin).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUTBOX_STATE"));
    }

    @Test
    void FAIL_이_아닌_행은_재시도_종결_모두_409_없는_행은_404() throws Exception {
        for (String s : List.of("NEW", "SENT", "CANCELED", "CLOSED_MANUAL")) {
            long id = row(comp, "NOTIFY", s, 1, "{}", "S-" + s);
            retry(id, admin).andExpect(status().isConflict());
            close(id, reason("x"), admin).andExpect(status().isConflict());
            assertThat(st(id)).as(s).isEqualTo(s);
        }
        retry(987654321L, admin).andExpect(status().isNotFound());
        close(987654321L, reason("x"), admin).andExpect(status().isNotFound());
        mvc.perform(get(API + "/987654321").with(admin)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ 수동 종결

    @Test
    void 수동_종결은_사유가_필수이고_릴레이가_집지_않으며_실패_집계에서_빠진다() throws Exception {
        long id = fail("NOTIFY");
        close(id, "{}", admin).andExpect(status().isBadRequest());
        close(id, reason("   "), admin).andExpect(status().isBadRequest());
        close(id, reason("\\u200B\\uFEFF\\u00A0"), admin).andExpect(status().isBadRequest());   // 보이지 않는 글자뿐(QA R4-a)
        close(id, reason("가".repeat(501)), admin).andExpect(status().isBadRequest());
        assertThat(st(id)).isEqualTo("FAIL");

        close(id, reason("ERP 에 직접 입력함"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.data.closed").value(true));
        assertThat(st(id)).isEqualTo("CLOSED_MANUAL");
        assertThat(accounts.query(comp, "select closed_by || '|' || close_reason || '|' || (closed_at is not null) from sys_outbox where outbox_id = ?",
                String.class, id)).isEqualTo("ob1|ERP 에 직접 입력함|true");
        assertThat(accounts.query(comp, """
                select count(*) from sys_access_log where event_cd = 'ACTION' and prog_cd = ? and action_cd = 'FN1' and result_cd = 'OK'""",
                Integer.class, OutboxAdminService.PROG)).isEqualTo(1);
        // 다시 종결·재시도 409
        close(id, reason("again"), admin).andExpect(status().isConflict());
        retry(id, admin).andExpect(status().isConflict());
        // 릴레이는 집지 않는다(다음 시도 시각이 지나도)
        accounts.exec(comp, "update sys_outbox set next_retry_at = now() - interval '1 hour' where outbox_id = ?", id);
        assertThat(relay.relay(comp).total()).isZero();
        assertThat(st(id)).isEqualTo("CLOSED_MANUAL");
        // 종결 칸 없이 CLOSED_MANUAL 로는 못 바꾼다(CHECK)
        long other = fail("MAIL");
        assertThat(accounts.query(comp, """
                select count(*) from sys_outbox where outbox_id = ? and status = 'FAIL'""", Integer.class, other)).isEqualTo(1);
        try {
            accounts.exec(comp, "update sys_outbox set status = 'CLOSED_MANUAL' where outbox_id = ?", other);
            org.junit.jupiter.api.Assertions.fail("CHECK 없이 수동 종결됨");
        } catch (RuntimeException e) {
            assertThat(e).hasMessageContaining("ck_sys_outbox_closed");
        }
    }

    @Test
    void 동시에_재시도와_종결을_눌러도_한쪽만_된다() throws Exception {
        for (int round = 0; round < 5; round++) {
            long id = fail("NOTIFY");
            CyclicBarrier go = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> a = pool.submit(() -> {
                    go.await();
                    return retry(id, admin).andReturn().getResponse().getStatus();
                });
                Future<Integer> b = pool.submit(() -> {
                    go.await();
                    return close(id, reason("race"), admin).andReturn().getResponse().getStatus();
                });
                List<Integer> codes = new ArrayList<>(List.of(a.get(), b.get()));
                assertThat(codes).as("round " + round).containsExactlyInAnyOrder(200, 409);
                String s = st(id);
                assertThat(s).isIn("NEW", "CLOSED_MANUAL");
                assertThat(s.equals("NEW") ? codes.get(0) : codes.get(1)).isEqualTo(200);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void 메일을_수동_종결하면_메일_상태는_멈춤이다() throws Exception {
        long id = row(comp, "MAIL", "FAIL", 10, "{\"kind\":\"EXTERNAL\"}", "PCN-1");
        java.util.UUID msgId = accounts.query(comp, "select message_id from sys_outbox where outbox_id = ?", java.util.UUID.class, id);
        close(id, reason("수신자 주소 오류 — 직접 전달"), admin).andExpect(status().isOk());
        MailStatus ms = runner.call(comp, () -> mail.statuses(List.of(msgId)).get(msgId));
        assertThat(ms.state()).isEqualTo(MailStatus.State.STOPPED);
    }

    // ------------------------------------------------------------------ 결재 정책

    @Test
    void 정책이_결재면_승인될_때까지_FAIL_로_남고_대기_중엔_재시도_종결_불가() throws Exception {
        approvalOn();
        long approver = accounts.newUser(comp, "boss", PW);
        RequestPostProcessor boss = accounts.as(comp, approver, "boss");
        long id = fail("NOTIFY");
        mvc.perform(get(API + "/" + id).with(admin)).andExpect(jsonPath("$.data.closeNeedsApproval").value(true));
        // 결재선 없음 400
        close(id, reason("직접 처리"), admin).andExpect(status().isBadRequest());
        String res = close(id, "{\"reason\":\"직접 처리\",\"lines\":[{\"userId\":" + approver + ",\"lineType\":\"APPROVE\"}]}", admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.closed").value(false))
                .andReturn().getResponse().getContentAsString();
        long aprId = ((Number) JsonPath.read(res, "$.data.aprId")).longValue();
        assertThat(st(id)).isEqualTo("FAIL");
        assertThat(accounts.query(comp, "select close_apr_id from sys_outbox where outbox_id = ?", Long.class, id)).isEqualTo(aprId);
        // 대기 중: 재시도·다시 종결 409, 릴레이도 보내지 않음
        retry(id, admin).andExpect(status().isConflict());
        close(id, "{\"reason\":\"또\",\"lines\":[{\"userId\":" + approver + "}]}", admin).andExpect(status().isConflict());
        relay.relay(comp);   // 결재 알림(NOTIFY)은 나가도 이 행은 그대로
        assertThat(accounts.query(comp, "select status || '|' || attempts from sys_outbox where outbox_id = ?", String.class, id))
                .isEqualTo("FAIL|10");
        mvc.perform(get(API + "/" + id).with(admin)).andExpect(jsonPath("$.data.closeAprStatus").value("PENDING"));
        // 승인 = 종결(종결자 = 요청자)
        mvc.perform(post("/api/platform/approvals/" + aprId + "/approve").with(boss).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        assertThat(st(id)).isEqualTo("CLOSED_MANUAL");
        assertThat(accounts.query(comp, "select closed_by || '|' || close_reason from sys_outbox where outbox_id = ?", String.class, id))
                .isEqualTo("ob1|직접 처리");
    }

    @Test
    void 결재가_반려되면_대기가_풀려_다시_재시도할_수_있다() throws Exception {
        approvalOn();
        long approver = accounts.newUser(comp, "boss2", PW);
        long id = fail("NOTIFY");
        String res = close(id, "{\"reason\":\"닫자\",\"lines\":[{\"userId\":" + approver + "}]}", admin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long aprId = ((Number) JsonPath.read(res, "$.data.aprId")).longValue();
        mvc.perform(post("/api/platform/approvals/" + aprId + "/reject").with(accounts.as(comp, approver, "boss2")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"재시도 먼저\"}")).andExpect(status().isOk());
        assertThat(st(id)).isEqualTo("FAIL");
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where outbox_id = ? and close_apr_id is null and close_reason is null",
                Integer.class, id)).isEqualTo(1);
        retry(id, admin).andExpect(status().isOk());
        assertThat(st(id)).isEqualTo("NEW");
    }

    // ------------------------------------------------------------------ 권한·회사

    @Test
    void 권한이_없으면_API_를_직접_불러도_막힌다() throws Exception {
        long id = fail("NOTIFY");
        RequestPostProcessor viewer = userWith("view1", "SEARCH");
        RequestPostProcessor saver = userWith("save1", "SEARCH", "SAVE");
        RequestPostProcessor nobody = userWith("none1");
        retry(id, viewer).andExpect(status().isForbidden());
        close(id, reason("x"), viewer).andExpect(status().isForbidden());
        close(id, reason("x"), saver).andExpect(status().isForbidden());
        mvc.perform(get(API + "/approvers").with(saver)).andExpect(status().isForbidden());
        mvc.perform(get(API + "/" + id).with(nobody)).andExpect(status().isForbidden());
        mvc.perform(get(API + "/" + id)).andExpect(status().isUnauthorized());
        assertThat(st(id)).isEqualTo("FAIL");
        // SAVE 만으로 재시도는 된다
        retry(id, saver).andExpect(status().isOk());
    }

    @Test
    void A사_관리자가_B사_행_번호로_보고_재시도_종결해도_404_이고_B사_행은_그대로() throws Exception {
        String b = accounts.newComp("OC");
        long bRow = row(b, "NOTIFY", "FAIL", 10, "{\"to\":\"b@b.com\"}", "B-1");
        mvc.perform(get(API + "/" + bRow).with(admin)).andExpect(status().isNotFound());
        retry(bRow, admin).andExpect(status().isNotFound());
        close(bRow, reason("x"), admin).andExpect(status().isNotFound());
        assertThat(accounts.query(b, "select status || '|' || retry_cnt from sys_outbox where outbox_id = ?", String.class, bRow))
                .isEqualTo("FAIL|0");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        mvc.perform(get(API).param("from", today).param("to", today).with(admin)).andExpect(jsonPath("$.list.length()").value(0));
    }

    // ------------------------------------------------------------------ 홈 카드

    @Test
    void 홈_카드는_FAIL_만_세고_수동_종결과_취소는_빼며_화면_권한이_없으면_안_보인다() throws Exception {
        fail("NOTIFY");
        fail("MAIL");
        row(comp, "MAIL", "FAIL", 10, "{}", "M-2");
        row(comp, "MAIL", "CLOSED_MANUAL", 10, "{}", "M-3");
        row(comp, "MAIL", "CANCELED", 0, "{}", "M-4");
        String h = mvc.perform(get("/api/home/cards").with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String card = "$.data.cards[?(@.code == 'outbox-fail')]";
        assertThat(JsonPath.<List<Number>>read(h, card + ".count").get(0).longValue()).isEqualTo(3);
        assertThat(JsonPath.<List<String>>read(h, card + ".facts[0].label")).containsExactly("메일");
        assertThat(JsonPath.<List<Number>>read(h, card + ".facts[0].value").get(0).longValue()).isEqualTo(2);
        assertThat(JsonPath.<List<Object>>read(h, card + ".items[*]")).hasSize(3);
        // 운영 지표도 FAIL 만(수동 종결은 장애가 아니다)
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where status = 'FAIL'", Integer.class)).isEqualTo(3);

        long other = accounts.newUser(comp, "home1", PW);
        accounts.grant(comp, other, "HM", "home-main", "SEARCH");
        String h2 = mvc.perform(get("/api/home/cards").with(accounts.as(comp, other, "home1"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(h2, card)).isEmpty();
    }

    @Test
    void 홈_카드는_종결_결재_대기_FAIL_을_주_숫자_주제_목록에서_빼고_보조_숫자로_따로_O10() throws Exception {
        approvalOn();
        long approver = accounts.newUser(comp, "boss2", PW);
        long waiting = row(comp, "MAIL", "FAIL", 10, "{}", "WAIT-1");
        row(comp, "MAIL", "FAIL", 10, "{}", "OPEN-1");
        row(comp, "LABEL_PRINT", "FAIL", 10, "{}", "OPEN-2");
        close(waiting, "{\"reason\":\"직접 처리\",\"lines\":[{\"userId\":" + approver + ",\"lineType\":\"APPROVE\"}]}", admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.closed").value(false));
        assertThat(st(waiting)).isEqualTo("FAIL");   // 결재 대기 = FAIL 그대로(재시도·종결 409)

        String card = "$.data.cards[?(@.code == 'outbox-fail')]";
        String h = mvc.perform(get("/api/home/cards").with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(h, card + ".count").get(0).longValue()).isEqualTo(2);   // 손댈 수 있는 FAIL 만
        assertThat(JsonPath.<List<String>>read(h, card + ".facts[*].label")).containsExactly("라벨 인쇄", "메일", "종결 결재 대기");
        assertThat(JsonPath.<List<Number>>read(h, card + ".facts[?(@.label == '메일')].value").get(0).longValue()).isEqualTo(1);
        assertThat(JsonPath.<List<Number>>read(h, card + ".facts[?(@.label == '종결 결재 대기')].value").get(0).longValue()).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(h, card + ".items[*]")).hasSize(2);
        assertThat(h).doesNotContain("WAIT-1").contains("OPEN-1").contains("OPEN-2");
        // 운영 지표(장애 수)는 FAIL 전체 그대로
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where status = 'FAIL'", Integer.class)).isEqualTo(3);
    }

    @Test
    void 홈_카드_A사로_B사_B사_FAIL_은_A사_숫자_주제_목록_어디에도_없다() throws Exception {
        fail("MAIL");
        String b = accounts.newComp("OBB");
        long bAdmin = accounts.newUser(b, "obb1", PW);
        accounts.grant(b, bAdmin, "OBA", OutboxAdminService.PROG, "SEARCH");
        accounts.grant(b, bAdmin, "OBA", "home-main", "SEARCH");
        row(b, "LABEL_PRINT", "FAIL", 10, "{}", "B-SECRET-1");
        row(b, "LABEL_PRINT", "FAIL", 10, "{}", "B-SECRET-2");
        row(b, "MAIL", "FAIL", 10, "{}", "B-SECRET-3");

        String card = "$.data.cards[?(@.code == 'outbox-fail')]";
        String h = mvc.perform(get("/api/home/cards").with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(h, card + ".count").get(0).longValue()).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(h, card + ".facts[*].label")).containsExactly("메일");
        assertThat(h).doesNotContain("B-SECRET");
        // B사 카드는 B사 것만(A사 1건이 더해지지 않는다)
        String hb = mvc.perform(get("/api/home/cards").with(accounts.as(b, bAdmin, "obb1"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(hb, card + ".count").get(0).longValue()).isEqualTo(3);
        assertThat(hb).doesNotContain("BK-MAIL");
    }

    @Test
    void 홈_카드는_화면_SEARCH_가_있어야_하고_SAVE_FN1_만으로는_안_보인다_R1() throws Exception {
        fail("MAIL");
        long u = accounts.newUser(comp, "save2", PW);
        accounts.grant(comp, u, "SV", "home-main", "SEARCH");
        accounts.grant(comp, u, "SV", OutboxAdminService.PROG, "SAVE", "FN1");   // 재시도·종결은 있지만 조회가 없음
        String card = "$.data.cards[?(@.code == 'outbox-fail')]";
        String h = mvc.perform(get("/api/home/cards").with(accounts.as(comp, u, "save2"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(h, card)).isEmpty();
        assertThat(h).doesNotContain("BK-MAIL");   // 숫자도 항목도 새지 않는다
    }

    // ------------------------------------------------------------------ 가림 단위

    @Test
    void 가림_키_규칙() {
        assertThat(OutboxPayloadMask.piiKey("custNm")).isTrue();
        assertThat(OutboxPayloadMask.piiKey("TOADDR")).isTrue();
        assertThat(OutboxPayloadMask.piiKey("recipientList")).isTrue();
        assertThat(OutboxPayloadMask.piiKey("phone_no")).isTrue();
        assertThat(OutboxPayloadMask.piiKey("photo")).isFalse();
        assertThat(OutboxPayloadMask.piiKey("hotelCd")).isFalse();
        assertThat(OutboxPayloadMask.piiKey("total")).isFalse();
        assertThat(OutboxPayloadMask.piiKey("topic")).isFalse();
        assertThat(OutboxPayloadMask.piiKey("bcc")).isTrue();
        assertThat(OutboxPayloadMask.piiKey("qty")).isFalse();
        assertThat(OutboxPayloadMask.maskText("a")).isEqualTo("a***");
        assertThat(OutboxPayloadMask.maskText("")).isEmpty();
        assertThat(OutboxPayloadMask.maskText("😀abc")).isEqualTo("😀***");
        assertThat(OutboxPayloadMask.mask("not json")).isNull();
    }
}
