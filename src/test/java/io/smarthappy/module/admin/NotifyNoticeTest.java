package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.notify.NotifyDigestService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.PlatformNotifyEvents;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.TestMessaging.TestDocType;

/**
 * 시스템관리 2b (docs/36 11장) — 알림 구독·사내 알림함·공지.
 * 정상 흐름 + 이상 사용(A사로 B사, E 권한, B 입력, F 낡은 버전, 직접 SQL, 멱등·중복)
 */
class NotifyNoticeTest extends SysAdminTestSupport {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final byte[] PDF = "%PDF-1.4\n% notice\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    TenantRunner runner;

    @Autowired
    NotificationService notify;

    @Autowired
    NotifyDigestService digest;

    @Autowired
    ApprovalService approvals;

    @Autowired
    BizClock clock;

    @BeforeEach
    void setUp() {
        setUpCompany("NT");
    }

    @AfterEach
    void resetClock() {
        clock.setClock(Clock.systemUTC());
    }

    private <T> T as(String compCd, long userId, String loginId, Supplier<T> work) {
        LoginUser user = new LoginUser(userId, compCd, loginId, loginId, "ko", false);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            return runner.call(compCd, work);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private NotificationService.Result send(String compCd, NotifyMessage m) {
        return runner.call(compCd, () -> notify.send(m));
    }

    private static NotifyMessage msg(long userId, String key) {
        return NotifyMessage.of(PlatformNotifyEvents.APR_DONE, userId, key, "notify.apr.approved", "제목 " + key);
    }

    private int notifyCount(String compCd, long userId) {
        return accounts.query(compCd, "select count(*) from sys_notify where user_id = ?", Integer.class, userId);
    }

    private static String constraintOf(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return PgErrors.constraint(e).orElse("(none) " + e.getMessage());
        }
        return "(no error)";
    }

    private long createNotice(String title, LocalDate from, LocalDate to) throws Exception {
        MvcResult r = mvc.perform(json(post("/api/admin/notices"), noticeBody(title, from, to, "Y", null)).with(admin))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private static String noticeBody(String title, LocalDate from, LocalDate to, String useYn, Integer rowVer) {
        return "{\"catCd\":\"NOTICE\",\"title\":\"" + title + "\",\"body\":\"첫 줄\\n둘째 줄\",\"pinYn\":\"N\",\"postFrom\":"
                + (from == null ? "null" : "\"" + from + "\"") + ",\"postTo\":" + (to == null ? "null" : "\"" + to + "\"")
                + ",\"useYn\":\"" + useYn + "\",\"rowVer\":" + rowVer + "}";
    }

    private LocalDate today() {
        return LocalDate.now(SEOUL);   // 회사 시간대 = 기본 Asia/Seoul (테스트 JVM 은 UTC — TestAccounts.COMP_TODAY 와 같은 이유)
    }

    // ================================================================== 개인 화면

    @Test
    void 개인_화면은_권한_행_없이_모든_사용자가_연다() throws Exception {
        long nobody = accounts.newUser(comp, "nobody", PW);   // 권한그룹 없음
        var who = accounts.as(comp, nobody, "nobody");
        mvc.perform(get("/page/sys-inbox").with(who)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/inbox").with(who)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/platform/inbox/count").with(who)).andExpect(jsonPath("$.data.total").value(0));
        // 내 메뉴에도 화면 목록에도 보이고, 다른 화면 권한은 생기지 않는다
        mvc.perform(get("/page/sys-notice").with(who)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/notices").with(who)).andExpect(status().isForbidden());
        // 회사(운영자)가 화면을 끄면 개인 화면도 닫힌다 — sys_prog 는 전역이라 직접 확인만
        assertThat(accounts.query(comp, "select self_service_yn from sys_prog where prog_cd = 'sys-inbox'", String.class))
                .isEqualTo("Y");
    }

    // ================================================================== 보내기 규칙

    @Test
    void 보내기는_구독_권한_사용자_상태를_보고_같은_키는_한_번만() throws Exception {
        assertThat(send(comp, msg(workerId, "K1"))).isEqualTo(NotificationService.Result.SENT);
        assertThat(send(comp, msg(workerId, "K1"))).isEqualTo(NotificationService.Result.DUPLICATE);
        assertThat(notifyCount(comp, workerId)).isEqualTo(1);
        // 링크 화면을 볼 수 없는 사람에게는 보내지 않는다(제목으로 내용이 새지 않게, Q15)
        assertThat(send(comp, msg(workerId, "K2").withLink("sys-user", Map.of()))).isEqualTo(NotificationService.Result.NO_PERMISSION);
        assertThat(send(comp, msg(hrId, "K2").withLink("sys-user", Map.of("keyword", "kim")))).isEqualTo(NotificationService.Result.SENT);
        assertThat(send(comp, msg(adminId, "K2").withLink("sys-user", Map.of()))).isEqualTo(NotificationService.Result.SENT);
        // 구독 끄기 → 안 옴, 다시 켜기 → 옴
        mvc.perform(json(put("/api/platform/inbox/subscriptions"), "[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"N\"}]").with(worker))
                .andExpect(status().isOk());
        mvc.perform(get("/api/platform/inbox/subscriptions").with(worker))
                .andExpect(jsonPath("$.list[?(@.eventCd=='APR_DONE')].inboxYn").value(Matchers.contains("N")))
                .andExpect(jsonPath("$.list[?(@.eventCd=='APR_TODO')].inboxYn").value(Matchers.contains("Y")));
        assertThat(send(comp, msg(workerId, "K3"))).isEqualTo(NotificationService.Result.UNSUBSCRIBED);
        // 사용 중지 사용자·다른 회사 사용자 → 없는 사람
        accounts.setUserUse(comp, hrId, "N");
        assertThat(send(comp, msg(hrId, "K4"))).isEqualTo(NotificationService.Result.NO_USER);
        String compB = accounts.newComp("NTB");
        long bUser = accounts.newUser(compB, "buser", PW);
        assertThat(send(comp, msg(bUser, "K5"))).isEqualTo(NotificationService.Result.NO_USER);
        assertThat(notifyCount(compB, bUser)).isZero();
        // 등록 안 된 이벤트·형식이 틀린 키는 프로그래밍 오류
        assertThatThrownBy(() -> send(comp, NotifyMessage.of("NO_SUCH", workerId, "x", "notify.apr.approved")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> send(comp, NotifyMessage.of(PlatformNotifyEvents.APR_DONE, workerId, "x", "error.notFound")))
                .isInstanceOf(IllegalArgumentException.class);
        // 트랜잭션 밖 호출 금지(업무와 같은 트랜잭션에서만)
        assertThatThrownBy(() -> notify.send(msg(workerId, "K6"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void 결재_차례와_결과가_알림함에_오고_결재가_롤백되면_알림도_없다() throws Exception {
        accounts.exec(comp, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, use_yn, created_by, updated_by)
                values (?, 'NTDOC', 'n', 'N', 't', 't')""", comp);
        long apr = as(comp, hrId, "hr", () -> approvals.submit(TestDocType.DOC_TYPE, "NTDOC", "구매 <b>품의</b>",
                List.of(new LineSpec(workerId, LineType.APPROVE))));
        mvc.perform(get("/api/platform/inbox").with(worker)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].eventCd").value("APR_TODO"))
                .andExpect(jsonPath("$.list[0].title").value("결재 요청: 구매 <b>품의</b> (hr 사용자)"))
                .andExpect(jsonPath("$.list[0].readAt").doesNotExist());
        as(comp, workerId, "worker", () -> { approvals.approve(apr, "ok"); return null; });
        mvc.perform(get("/api/platform/inbox").with(hr)).andExpect(jsonPath("$.list[0].eventCd").value("APR_DONE"))
                .andExpect(jsonPath("$.list[0].title").value("결재 승인: 구매 <b>품의</b>"));
        // 영어 사용자는 영어로(저장은 키 + 인자)
        mvc.perform(get("/api/platform/inbox").with(accounts.as(comp, hrId, "hr", "en")))
                .andExpect(jsonPath("$.list[0].title").value("Approved: 구매 <b>품의</b>"));
        // 결재 처리가 업무 오류로 롤백되면 결과 알림도 없다
        long apr2 = as(comp, hrId, "hr", () -> approvals.submit(TestDocType.DOC_TYPE, "EXPLODE", "폭발",
                List.of(new LineSpec(workerId, LineType.APPROVE))));
        int before = notifyCount(comp, hrId);
        assertThatThrownBy(() -> as(comp, workerId, "worker", () -> { approvals.approve(apr2, null); return null; }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(notifyCount(comp, hrId)).isEqualTo(before);
    }

    // ================================================================== 알림함 API

    @Test
    void 알림함_읽음_모두_읽음_쪽_넘기기_안_읽은_수() throws Exception {
        for (int i = 1; i <= 5; i++) {
            send(comp, msg(workerId, "P" + i));
        }
        MvcResult r = mvc.perform(get("/api/platform/inbox").param("size", "2").with(worker))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.list[0].title").value("결재 승인: 제목 P5"))
                .andReturn();
        long last = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.list[1].notifyId")).longValue();
        mvc.perform(get("/api/platform/inbox").param("size", "2").param("before", String.valueOf(last)).with(worker))
                .andExpect(jsonPath("$.list[0].title").value("결재 승인: 제목 P3"));
        mvc.perform(get("/api/platform/inbox").param("size", "999").with(worker)).andExpect(jsonPath("$.total").value(5));
        long first = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.list[0].notifyId")).longValue();
        mvc.perform(post("/api/platform/inbox/" + first + "/read").with(csrf()).with(worker)).andExpect(status().isOk());
        mvc.perform(post("/api/platform/inbox/" + first + "/read").with(csrf()).with(worker)).andExpect(status().isOk());   // 멱등
        mvc.perform(get("/api/platform/inbox/count").with(worker)).andExpect(jsonPath("$.data.notify").value(4))
                .andExpect(jsonPath("$.data.total").value(4));
        mvc.perform(get("/api/platform/inbox").param("unread", "true").with(worker)).andExpect(jsonPath("$.total").value(4));
        mvc.perform(post("/api/platform/inbox/read-all").with(csrf()).with(worker)).andExpect(jsonPath("$.data").value(4));
        mvc.perform(get("/api/platform/inbox/count").with(worker)).andExpect(jsonPath("$.data.total").value(0));
        // 다른 사람 알림은 읽음 처리할 수 없다(존재를 알리지 않고 404), 내 알림 수는 남의 것을 세지 않는다
        send(comp, msg(hrId, "H1"));
        long hrNotify = accounts.query(comp, "select max(notify_id) from sys_notify where user_id = ?", Long.class, hrId);
        mvc.perform(post("/api/platform/inbox/" + hrNotify + "/read").with(csrf()).with(worker)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select read_at is null from sys_notify where notify_id = ?", Boolean.class, hrNotify)).isTrue();
    }

    @Test
    void 하루_요약은_07시_뒤_하루_한_번() {
        accounts.exec(comp, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, use_yn, created_by, updated_by)
                values (?, 'NTD1', 'n', 'N', 't', 't'), (?, 'NTD2', 'n', 'N', 't', 't')""", comp, comp);
        as(comp, hrId, "hr", () -> approvals.submit(TestDocType.DOC_TYPE, "NTD1", "a", List.of(new LineSpec(workerId, LineType.APPROVE))));
        as(comp, hrId, "hr", () -> approvals.submit(TestDocType.DOC_TYPE, "NTD2", "b", List.of(new LineSpec(workerId, LineType.APPROVE))));
        LocalDate day = today();
        clock.setClock(Clock.fixed(ZonedDateTime.of(day.atTime(6, 30), SEOUL).toInstant(), SEOUL));
        assertThat(runner.call(comp, digest::runForCompany)).isZero();
        clock.setClock(Clock.fixed(ZonedDateTime.of(day.atTime(7, 40), SEOUL).toInstant(), SEOUL));
        assertThat(runner.call(comp, digest::runForCompany)).isEqualTo(1);
        assertThat(runner.call(comp, digest::runForCompany)).isZero();   // 매시 다시 돌아도 하루 한 번
        assertThat(accounts.query(comp, """
                select lines::text from sys_notify where user_id = ? and event_cd = 'DUE_DIGEST' and dedup_key = ?""",
                String.class, workerId, day.toString())).contains("notify.digest.aprTodo").contains("\"count\": 2");
        // 구독을 끈 사람은 요약도 없다
        clock.setClock(Clock.fixed(ZonedDateTime.of(day.plusDays(1).atTime(8, 0), SEOUL).toInstant(), SEOUL));
        accounts.exec(comp, """
                insert into sys_notify_sub (comp_cd, user_id, event_cd, inbox_yn, created_by, updated_by)
                values (?, ?, 'DUE_DIGEST', 'N', 't', 't')""", comp, workerId);
        assertThat(runner.call(comp, digest::runForCompany)).isZero();
    }

    @Test
    void 구독_입력_검사() throws Exception {
        mvc.perform(json(put("/api/platform/inbox/subscriptions"), "[{\"eventCd\":\"NO_SUCH\",\"inboxYn\":\"N\"}]").with(worker))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/platform/inbox/subscriptions"), "[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"X\"}]").with(worker))
                .andExpect(status().isBadRequest());
        String many = "[" + String.join(",", java.util.Collections.nCopies(500, "{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"N\"}")) + "]";
        mvc.perform(json(put("/api/platform/inbox/subscriptions"), many).with(worker)).andExpect(status().isBadRequest());
        // 같은 값 다시 저장은 행을 고치지 않는다(감사 잡음 없음)
        mvc.perform(json(put("/api/platform/inbox/subscriptions"), "[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"N\"}]").with(worker));
        mvc.perform(json(put("/api/platform/inbox/subscriptions"), "[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"N\"}]").with(worker));
        assertThat(accounts.query(comp, "select row_ver from sys_notify_sub where user_id = ?", Integer.class, workerId)).isZero();
    }

    // ================================================================== 공지

    @Test
    void 공지_게시_기간_읽음_안_읽은_수() throws Exception {
        long now = createNotice("정기 점검 안내", today(), null);
        long later = createNotice("다음 주 행사", today().plusDays(7), null);
        mvc.perform(get("/api/admin/notices").with(admin)).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list[?(@.noticeId==" + later + ")].stateCd").value(Matchers.contains("SCHEDULED")));
        mvc.perform(get("/api/admin/notices").param("stateCd", "POSTED").with(admin)).andExpect(jsonPath("$.total").value(1));
        // 읽는 사람: 게시 중만, 게시 전은 404
        mvc.perform(get("/api/platform/notices").with(worker)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].unread").value(true));
        mvc.perform(get("/api/platform/inbox/count").with(worker)).andExpect(jsonPath("$.data.notice").value(1));
        mvc.perform(get("/api/platform/notices/" + later).with(worker)).andExpect(status().isNotFound());
        mvc.perform(get("/api/platform/notices/" + now).with(worker)).andExpect(jsonPath("$.data.body").value("첫 줄\n둘째 줄"));
        mvc.perform(get("/api/platform/notices/" + now).with(worker)).andExpect(status().isOk());   // 두 번 읽어도 한 행
        assertThat(accounts.query(comp, "select count(*) from sys_notice_read where notice_id = ?", Integer.class, now)).isEqualTo(1);
        mvc.perform(get("/api/platform/inbox/count").with(worker)).andExpect(jsonPath("$.data.notice").value(0));
        // 사용 중지·끝난 공지는 읽는 쪽에서 사라진다 (끝 = 어제 — 직접 고쳐 과거 기간을 만든다)
        int ver = accounts.query(comp, "select row_ver from sys_notice where notice_id = ?", Integer.class, now);
        mvc.perform(json(put("/api/admin/notices/" + now), noticeBody("정기 점검 안내", today(), null, "N", ver)).with(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/platform/notices").with(worker)).andExpect(jsonPath("$.total").value(0));
        accounts.exec(comp, "update sys_notice set use_yn = 'Y', post_from = ?, post_to = ? where notice_id = ?",
                today().minusDays(5), today().minusDays(1), now);
        mvc.perform(get("/api/platform/notices/" + now).with(worker)).andExpect(status().isNotFound());
        // 낡은 버전 → 409
        mvc.perform(json(put("/api/admin/notices/" + now), noticeBody("x", today(), null, "Y", ver)).with(admin))
                .andExpect(status().isConflict());
    }

    @Test
    void 공지_입력_검사와_권한() throws Exception {
        mvc.perform(json(post("/api/admin/notices"), noticeBody("지난 공지", today().minusDays(1), null, "Y", null)).with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("postFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("PAST"));
        mvc.perform(json(post("/api/admin/notices"), noticeBody("기간", today().plusDays(3), today().plusDays(1), "Y", null)).with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        mvc.perform(json(post("/api/admin/notices"), noticeBody("제어\\u0007문자", today(), null, "Y", null)).with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        mvc.perform(json(post("/api/admin/notices"), "{\"title\":\"t\",\"postFrom\":\"" + today() + "\",\"body\":\""
                + "가".repeat(20001) + "\"}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        mvc.perform(json(post("/api/admin/notices"), "{\"title\":\"t\",\"catCd\":\"EVIL\",\"postFrom\":\"" + today() + "\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].field").value("catCd"));
        // 양방향 제어 문자(표시 속임) 거부
        mvc.perform(json(post("/api/admin/notices"), "{\"title\":\"t\",\"body\":\"a\\u202Eb\",\"postFrom\":\"" + today() + "\"}")
                .with(admin)).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // E: 공지 관리 권한 없는 사람은 쓰기·관리 목록 불가
        mvc.perform(json(post("/api/admin/notices"), noticeBody("x", today(), null, "Y", null)).with(worker))
                .andExpect(status().isForbidden());
        // HTML 은 그대로 글자로 저장·반환(화면은 textContent)
        long id = createNotice("<script>alert(1)</script>", today(), null);
        mvc.perform(get("/api/platform/notices/" + id).with(worker)).andExpect(jsonPath("$.data.title").value("<script>alert(1)</script>"));
    }

    @Test
    void 공지_첨부는_게시_중이면_누구나_읽고_올리기는_관리_권한만() throws Exception {
        long now = createNotice("양식", today(), null);
        long later = createNotice("예정", today().plusDays(3), null);
        MockMultipartFile f = new MockMultipartFile("file", "form.pdf", "application/pdf", PDF);
        long fileId = ((Number) JsonPath.read(mvc.perform(multipart("/api/platform/files").file(f).param("targetType", "sys-notice")
                .param("targetId", String.valueOf(now)).with(csrf()).with(admin)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(), "$.data.fileId")).longValue();
        long laterFile = ((Number) JsonPath.read(mvc.perform(multipart("/api/platform/files").file(f).param("targetType", "sys-notice")
                .param("targetId", String.valueOf(later)).with(csrf()).with(admin)).andReturn()
                .getResponse().getContentAsString(), "$.data.fileId")).longValue();
        // 게시 중 → 공지 관리 권한 없는 작업자도 목록·내려받기
        mvc.perform(get("/api/platform/files").param("targetType", "sys-notice").param("targetId", String.valueOf(now)).with(worker))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/platform/files/" + fileId).with(worker)).andExpect(status().isOk());
        // 게시 전 → 403(권한 규칙 그대로), 올리기·삭제는 언제나 SAVE
        mvc.perform(get("/api/platform/files/" + laterFile).with(worker)).andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/files").param("targetType", "sys-notice").param("targetId", "0" + now).with(worker))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/platform/files").file(f).param("targetType", "sys-notice").param("targetId", String.valueOf(now))
                .with(csrf()).with(worker)).andExpect(status().isForbidden());
        mvc.perform(post("/api/platform/files/" + fileId + "/delete").with(csrf()).with(worker)).andExpect(status().isForbidden());
        // 다른 화면 첨부에는 이 허락이 넓어지지 않는다
        mvc.perform(get("/api/platform/files").param("targetType", "sys-user").param("targetId", String.valueOf(now)).with(worker))
                .andExpect(status().isForbidden());
    }

    // ================================================================== A사로 B사

    @Test
    void A사로_B사_알림_공지_첨부() throws Exception {
        long aNotice = createNotice("A사 공지", today(), null);
        MockMultipartFile f = new MockMultipartFile("file", "a.pdf", "application/pdf", PDF);
        long aFile = ((Number) JsonPath.read(mvc.perform(multipart("/api/platform/files").file(f).param("targetType", "sys-notice")
                .param("targetId", String.valueOf(aNotice)).with(csrf()).with(admin)).andReturn()
                .getResponse().getContentAsString(), "$.data.fileId")).longValue();
        send(comp, msg(workerId, "A1"));
        long aNotify = accounts.query(comp, "select max(notify_id) from sys_notify where user_id = ?", Long.class, workerId);

        String compB = accounts.newComp("NTB");
        long bAdmin = accounts.newUser(compB, "badmin", PW);
        adminGroup(compB, "ADMIN", bAdmin);
        var b = accounts.as(compB, bAdmin, "badmin");
        mvc.perform(get("/api/platform/notices").with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/platform/notices/" + aNotice).with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/notices/" + aNotice).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/admin/notices/" + aNotice), noticeBody("탈취", today(), null, "Y", 0)).with(b))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/platform/files/" + aFile).with(b)).andExpect(status().isNotFound());
        mvc.perform(post("/api/platform/inbox/" + aNotify + "/read").with(csrf()).with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/platform/inbox").with(b)).andExpect(jsonPath("$.total").value(0));
        // B사 관리자가 A사 사용자 id 로 보내기 → 없는 사람
        assertThat(send(compB, msg(workerId, "X"))).isEqualTo(NotificationService.Result.NO_USER);
        assertThat(accounts.query(comp, "select title from sys_notice where notice_id = ?", String.class, aNotice)).isEqualTo("A사 공지");
    }

    // ================================================================== 직접 SQL

    @Test
    void 직접_SQL_알림함은_추가와_읽음만_공지는_삭제_불가() throws Exception {
        long aNotice = createNotice("공지", today(), null);
        send(comp, msg(workerId, "S1"));
        long id = accounts.query(comp, "select max(notify_id) from sys_notify where user_id = ?", Long.class, workerId);
        assertThatThrownBy(() -> accounts.exec(comp, "update sys_notify set title_key = 'notify.apr.todo' where notify_id = ?", id))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from sys_notify where notify_id = ?", id))
                .rootCause().hasMessageContaining("permission denied");
        accounts.exec(comp, "update sys_notify set read_at = now() where notify_id = ?", id);
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_notify set read_at = null where notify_id = ?", id)))
                .isEqualTo("ck_sys_notify_read_once");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from sys_notice where notice_id = ?", aNotice))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from sys_notice_read")).rootCause().hasMessageContaining("permission denied");
        // 다른 회사 사용자·공지를 가리키는 행
        String compB = accounts.newComp("NTB");
        long bUser = accounts.newUser(compB, "buser", PW);
        assertThat(constraintOf(() -> accounts.exec(comp, """
                insert into sys_notify (comp_cd, user_id, event_cd, dedup_key, title_key, created_by)
                values (?, ?, 'APR_DONE', 'x', 'notify.apr.approved', 't')""", comp, bUser))).isEqualTo("fk_user_comp");
        assertThat(constraintOf(() -> accounts.exec(compB, """
                insert into sys_notice_read (comp_cd, notice_id, user_id) values (?, ?, ?)""", compB, aNotice, bUser)))
                .isEqualTo("fk_sys_notice_read_notice_comp");
        assertThat(constraintOf(() -> accounts.exec(compB, """
                insert into sys_notify_sub (comp_cd, user_id, event_cd, inbox_yn, created_by, updated_by)
                values (?, ?, 'APR_DONE', 'N', 't', 't')""", compB, workerId))).isEqualTo("fk_user_comp");
        // 제목 키는 notify. 로만(다른 문구 키로 오류·관리자 문구를 흉내 내지 못하게)
        assertThat(constraintOf(() -> accounts.exec(comp, """
                insert into sys_notify (comp_cd, user_id, event_cd, dedup_key, title_key, created_by)
                values (?, ?, 'APR_DONE', 'y', 'error.notFound', 't')""", comp, workerId))).isEqualTo("ck_sys_notify_title_key");
        // 앱 계정은 개인 화면 표시를 바꿀 수 없다(전역 표)
        assertThatThrownBy(() -> accounts.exec(comp, "update sys_prog set self_service_yn = 'Y' where prog_cd = 'sys-user'"))
                .rootCause().hasMessageContaining("permission denied");
    }
}
