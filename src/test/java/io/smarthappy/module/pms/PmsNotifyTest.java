package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
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

import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyDigestService;
import io.smarthappy.platform.notify.NotifyEventDef;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PMS 알림 (docs/48 R-4, docs/32 4.14) — 즉시 이벤트 7종(기본 켜짐) 발행·받는 사람·링크, 자기 행동·같은 담당 재지정은 없음,
 * 결재로 가는 일정변경은 결재 알림과 겹치지 않음, 사람별 끄기(플랫폼 구독), 하루 요약(지연·임박 2일, 작업 + 산출물 한 묶음,
 * 회사 휴일 반영, 하루 한 번), A사로 B사.
 */
@AutoConfigureMockMvc
class PmsNotifyTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String K = "/api/pms/tasks";
    private static final String IS = "/api/pms/issues";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    NotifyDigestService digest;

    @Autowired
    NotificationService notifications;

    @Autowired
    BizClock clock;

    String comp;
    long pmId;
    long memId;
    RequestPostProcessor pm;
    RequestPostProcessor mem;
    LocalDate today;
    long proj;
    String projNo;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PN");
        pmId = accounts.newUser(comp, "pm1", PW);
        memId = accounts.newUser(comp, "mem1", PW);
        for (long u : List.of(pmId, memId)) {
            String g = u == pmId ? "PMS" : "MEM";
            accounts.grant(comp, u, g, "pms-project", "SEARCH", "SAVE");
            accounts.grant(comp, u, g, "pms-deliverable", "SEARCH", "SAVE");
            accounts.grant(comp, u, g, "pms-issue", "SEARCH", "SAVE");
            accounts.grant(comp, u, g, "pms-gate", "SEARCH");
            accounts.grant(comp, u, g, "pms-home", "SEARCH");
            accounts.grant(comp, u, g, "sys-approval", "SEARCH");
        }
        accounts.grant(comp, pmId, "PMS", "pms-deliverable", "APPROVE");
        accounts.grant(comp, pmId, "PMS", "pms-gate", "SAVE", "APPROVE");
        pm = accounts.as(comp, pmId, "pm1");
        mem = accounts.as(comp, memId, "mem1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        proj = id(send(post(P), "{\"projNm\":\"알림\",\"projType\":\"GENERAL\"}", pm));
        projNo = read(mvc.perform(get(P + "/" + proj).with(pm)), "$.data.projNo");
    }

    @AfterEach
    void resetClock() {
        clock.setClock(Clock.systemUTC());
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

    private long task(String nm, String owner, LocalDate end) throws Exception {
        return id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"" + nm + "\",\"planStart\":\"" + end.minusDays(5) + "\",\"planEnd\":\""
                + end + "\"" + (owner == null ? "" : ",\"ownerLoginId\":\"" + owner + "\"") + "}", pm));
    }

    private Map<String, Object> row(long taskId) throws Exception {
        List<Map<String, Object>> rows = read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.tasks[?(@.taskId == " + taskId + ")]");
        return rows.get(0);
    }

    private ResultActions setOwner(long taskId, String owner) throws Exception {
        Map<String, Object> t = row(taskId);
        return send(put(K + "/" + taskId), "{\"taskNm\":\"" + t.get("taskNm") + "\",\"planStart\":\"" + t.get("planStart") + "\",\"planEnd\":\""
                + t.get("planEnd") + "\",\"ownerLoginId\":\"" + owner + "\",\"rowVer\":" + t.get("rowVer") + "}", pm);
    }

    private long count(String c, long user, String event) {
        return accounts.query(c, "select count(*) from sys_notify where user_id = ? and event_cd = ?", Long.class, user, event);
    }

    /** 마지막 알림의 제목 키 | 인자 | 화면 | 조건 */
    private String last(long user, String event) {
        return accounts.query(comp, """
                select title_key || '|' || title_args::text || '|' || link_prog_cd || '|' || link_params::text
                  from sys_notify where user_id = ? and event_cd = ? order by notify_id desc limit 1""", String.class, user, event);
    }

    private void policy(String key, String value) {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')
                on conflict do nothing""", comp, key, value);
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = ? and plant_cd = '*'", value, key);
    }

    private void morning(LocalDate day) {
        clock.setClock(Clock.fixed(ZonedDateTime.of(day.atTime(8, 0), SEOUL).toInstant(), SEOUL));
    }

    // ------------------------------------------------------------------ 즉시 알림

    @Test
    void 이벤트_7종은_기본_켜짐으로_등록되고_코드가_겹치지_않는다() {
        List<String> pms = notifications.events().stream().map(NotifyEventDef::eventCd).filter(c -> c.startsWith("PMS_")).toList();
        assertThat(pms).containsExactly("PMS_TASK_ASSIGNED", "PMS_DELIV_REJECTED", "PMS_ISSUE_ASSIGNED", "PMS_ISSUE_RESOLVED",
                "PMS_GATE_DECIDED", "PMS_CHG_REQUESTED", "PMS_CHG_RESULT");
        assertThat(notifications.events().stream().filter(e -> e.eventCd().startsWith("PMS_")).allMatch(NotifyEventDef::defaultOn)).isTrue();
    }

    @Test
    void 작업_담당_지정_알림_자기지정_같은담당_다시저장은_없고_다시_바뀌면_또() throws Exception {
        long a = task("설계", "mem1", today.plusDays(10));
        assertThat(count(comp, memId, "PMS_TASK_ASSIGNED")).isEqualTo(1);
        assertThat(last(memId, "PMS_TASK_ASSIGNED")).isEqualTo("notify.pms.taskAssigned|[\"" + projNo + "\", \"설계\"]|pms-project|{\"keyword\": \""
                + projNo + "\"}");
        task("PM 본인", "pm1", today.plusDays(10));
        assertThat(count(comp, pmId, "PMS_TASK_ASSIGNED")).isZero();
        // 같은 담당으로 다시 저장 → 없음, 다른 사람(본인) → 없음, 다시 mem1 → 새 알림
        setOwner(a, "mem1").andExpect(status().isOk());
        assertThat(count(comp, memId, "PMS_TASK_ASSIGNED")).isEqualTo(1);
        setOwner(a, "pm1").andExpect(status().isOk());
        setOwner(a, "mem1").andExpect(status().isOk());
        assertThat(count(comp, memId, "PMS_TASK_ASSIGNED")).isEqualTo(2);
        // 저장이 실패하면 알림도 없다(같은 트랜잭션) — 옛 rowVer
        send(put(K + "/" + a), "{\"taskNm\":\"설계\",\"planStart\":\"" + today.plusDays(5) + "\",\"planEnd\":\"" + today.plusDays(10)
                + "\",\"ownerLoginId\":\"pm1\",\"rowVer\":0}", pm).andExpect(status().isConflict());
        // 사람별 끄기 = 플랫폼 구독(이벤트별 알림함 N)
        accounts.exec(comp, """
                insert into sys_notify_sub (comp_cd, user_id, event_cd, inbox_yn, created_by, updated_by)
                values (?, ?, 'PMS_TASK_ASSIGNED', 'N', 't', 't')""", comp, memId);
        task("끈 뒤", "mem1", today.plusDays(10));
        assertThat(count(comp, memId, "PMS_TASK_ASSIGNED")).isEqualTo(2);
    }

    @Test
    void 산출물_반려_이슈_담당_해결_게이트_결정_알림() throws Exception {
        long a = task("설계", "mem1", today.plusDays(10));
        // 산출물(담당 비움 = 작업 담당) → 담당 제출 → PM 반려
        long d = id(send(post(P + "/" + proj + "/deliverables"), "{\"taskId\":" + a + ",\"delivNm\":\"설계 검토서\"}", pm));
        mvc.perform(multipart("/api/pms/deliverables/" + d + "/submit").file(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF))
                .param("rowVer", "0").with(mem).with(csrf())).andExpect(status().isOk());
        send(post("/api/pms/deliverables/" + d + "/decide"), "{\"action\":\"REJECT\",\"note\":\"치수 누락\",\"rowVer\":1}", pm)
                .andExpect(status().isOk());
        assertThat(last(memId, "PMS_DELIV_REJECTED")).startsWith("notify.pms.delivRejected|[\"" + projNo + "\", \"설계 검토서\"]|pms-deliverable");
        // 이슈: PM 이 mem1 담당으로 → 배정 알림, mem1 이 해결 → 올린 사람(PM) 에게 한 번(올린 사람 = PM)
        long is = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"치구 간섭\",\"ownerLoginId\":\"mem1\"}", pm));
        String issueNo = accounts.query(comp, "select issue_no from pms_issue where issue_id = ?", String.class, is);
        assertThat(last(memId, "PMS_ISSUE_ASSIGNED")).isEqualTo("notify.pms.issueAssigned|[\"" + issueNo + "\", \"치구 간섭\"]|pms-issue|{\"keyword\": \""
                + issueNo + "\"}");
        send(post(IS + "/" + is + "/status"), "{\"status\":\"RESOLVED\",\"resolution\":\"치구 수정\",\"rowVer\":0}", mem).andExpect(status().isOk());
        assertThat(count(comp, pmId, "PMS_ISSUE_RESOLVED")).isEqualTo(1);
        assertThat(count(comp, memId, "PMS_ISSUE_RESOLVED")).isZero();
        // 게이트(담당 mem1) 결재 없이 불가 결정 → PM 은 본인이 결정, 게이트 담당에게만
        policy("PMS_GATE_APPROVAL", "N");
        long g = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"G1\",\"taskType\":\"GATE\",\"planStart\":\"" + today.plusDays(11)
                + "\",\"ownerLoginId\":\"mem1\"}", pm));
        long rv = id(send(post("/api/pms/gates/" + g + "/reviews"), "{}", pm));
        int ver = read(mvc.perform(get("/api/pms/gate-reviews/" + rv).with(pm)), "$.data.review.rowVer");
        send(put("/api/pms/gate-reviews/" + rv), "{\"reviewDate\":\"" + today + "\",\"decision\":\"NO_GO\",\"summary\":\"보류\",\"checks\":[],"
                + "\"conds\":[],\"rowVer\":" + ver + "}", pm).andExpect(status().isOk());
        assertThat(id(send(post("/api/pms/gate-reviews/" + rv + "/submit"), "{\"rowVer\":" + (ver + 1) + "}", pm))).isZero();
        assertThat(last(memId, "PMS_GATE_DECIDED")).startsWith("notify.pms.gateNoGo|[\"" + projNo + "\", \"G1\"]|pms-gate");
        assertThat(count(comp, pmId, "PMS_GATE_DECIDED")).isZero();
    }

    @Test
    void 일정변경_바로처리는_요청과_결과_결재로_가면_결재_알림과_겹치지_않음() throws Exception {
        long a = task("설계", "mem1", today.plusDays(10));
        policy("PMS_TASK_CHG_APPROVAL", "N");
        int ver = ((Number) row(a).get("rowVer")).intValue();
        long r1 = id(send(post(K + "/" + a + "/chg-reqs"), "{\"newStart\":\"" + today.plusDays(5) + "\",\"newEnd\":\"" + today.plusDays(12)
                + "\",\"reason\":\"지연\",\"rowVer\":" + ver + "}", mem));
        assertThat(last(pmId, "PMS_CHG_REQUESTED")).endsWith("|pms-project|{\"chgReq\": \"" + r1 + "\"}");
        send(post("/api/pms/chg-reqs/" + r1 + "/decide"), "{\"approve\":true,\"rowVer\":0}", pm).andExpect(status().isOk());
        assertThat(last(memId, "PMS_CHG_RESULT")).startsWith("notify.pms.chgApproved|");
        // 결재로(결재자 = PM): PM 은 결재 차례 알림만, 요청자는 결재 결과 알림만
        policy("PMS_TASK_CHG_APPROVAL", "Y");
        ver = ((Number) row(a).get("rowVer")).intValue();
        long r2 = id(send(post(K + "/" + a + "/chg-reqs"), "{\"newStart\":\"" + today.plusDays(5) + "\",\"newEnd\":\"" + today.plusDays(14)
                + "\",\"reason\":\"재지연\",\"rowVer\":" + ver + "}", mem));
        assertThat(count(comp, pmId, "PMS_CHG_REQUESTED")).isEqualTo(1);
        assertThat(count(comp, pmId, "APR_TODO")).isEqualTo(1);
        long apr = accounts.query(comp, "select apr_id from pms_task_chg_req where req_id = ?", Long.class, r2);
        send(post("/api/platform/approvals/" + apr + "/reject"), "{\"comment\":\"불가\"}", pm).andExpect(status().isOk());
        assertThat(count(comp, memId, "PMS_CHG_RESULT")).isEqualTo(1);
        assertThat(count(comp, memId, "APR_DONE")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 하루 요약

    @Test
    void 하루_요약은_지연과_임박2일을_작업_산출물_한_묶음으로_하루_한번_회사휴일_반영() throws Exception {
        task("지연 작업", "mem1", today.minusDays(1));
        long soon = task("임박 작업", "mem1", today.plusDays(2));
        task("먼 작업", "mem1", today.plusDays(3));
        task("남의 작업", "pm1", today.minusDays(1));
        // 산출물 기한 어제(담당 = 작업 담당 mem1) → 지연 1 더
        id(send(post(P + "/" + proj + "/deliverables"), "{\"taskId\":" + soon + ",\"delivNm\":\"검토서\",\"dueDate\":\"" + today.minusDays(1)
                + "\"}", pm));
        morning(today);
        assertThat(runner.call(comp, digest::runForCompany)).isEqualTo(2);
        String lines = accounts.query(comp, "select lines::text from sys_notify where user_id = ? and event_cd = 'DUE_DIGEST'", String.class, memId);
        assertThat(lines).contains("notify.digest.pmsLate").contains("notify.digest.pmsDueSoon");
        assertThat(accounts.query(comp, """
                select (select (l ->> 'count')::int from jsonb_array_elements(lines) l where l ->> 'labelKey' = 'notify.digest.pmsLate')
                       || '/' ||
                       (select (l ->> 'count')::int from jsonb_array_elements(lines) l where l ->> 'labelKey' = 'notify.digest.pmsDueSoon')
                  from sys_notify where user_id = ? and event_cd = 'DUE_DIGEST'""", String.class, memId)).isEqualTo("2/1");
        // 즉시 알림으로는 가지 않는다(지연·임박 이벤트 없음), 다시 돌아도 하루 한 번
        assertThat(accounts.query(comp, "select count(*) from sys_notify where user_id = ? and event_cd like 'PMS_%' and event_cd <> 'PMS_TASK_ASSIGNED'",
                Long.class, memId)).isZero();
        assertThat(runner.call(comp, digest::runForCompany)).isZero();
        // 회사 휴일: 오늘이 휴일이면 지연 기준일 = 어제 → 어제 기한은 아직 지연 아님(임박으로)
        accounts.exec(comp, "insert into mst_work_cal (comp_cd, cal_date, day_type, reason, created_by, updated_by) values (?, ?, 'OFF', '휴무', 't', 't')",
                comp, today);
        var due = runner.call(comp, () -> digestSource.due(today));
        assertThat(due.stream().filter(x -> x.userId() == memId && x.labelKey().equals("notify.digest.pmsLate")).count()).isZero();
        assertThat(due.stream().filter(x -> x.userId() == memId && x.labelKey().equals("notify.digest.pmsDueSoon"))
                .mapToLong(x -> x.count()).sum()).isEqualTo(3);
    }

    @Autowired
    PmsDigestSource digestSource;

    @Test
    void A사로_B사_알림과_요약은_회사를_넘지_않는다() throws Exception {
        task("A 지연", "mem1", today.minusDays(1));
        String compB = accounts.newComp("PO");
        long bUser = accounts.newUser(compB, "mem1", PW);
        accounts.grant(compB, bUser, "MEM", "pms-home", "SEARCH");
        morning(today);
        assertThat(runner.call(compB, digest::runForCompany)).isZero();
        assertThat(count(compB, bUser, "DUE_DIGEST")).isZero();
        assertThat(runner.call(compB, () -> digestSource.due(today))).isEmpty();
        assertThat(runner.call(comp, digest::runForCompany)).isEqualTo(1);
        assertThat(count(compB, bUser, "PMS_TASK_ASSIGNED")).isZero();
        // B사 범위에서는 A사 알림이 0건(RLS)
        assertThat(accounts.query(compB, "select count(*) from sys_notify where event_cd like 'PMS_%' or event_cd = 'DUE_DIGEST'", Long.class))
                .isZero();
        // B사 사용자가 A사 작업 담당으로 지정될 수 없다(다른 회사 아이디 = 없는 사람)
        send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"x\",\"planStart\":\"" + today + "\",\"ownerLoginId\":\"nobody-b\"}", pm)
                .andExpect(status().isBadRequest());
    }
}
