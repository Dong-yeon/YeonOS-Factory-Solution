package io.smarthappy.platform.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.notify.PlatformNotifyEvents;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web Push 구독·발행·정리 (docs/45 9장). 키는 이 시험이 실행 때 만든 일회용(저장소·문서에 실제 키 없음, N7),
 * 푸시 서비스는 가짜(PushTransport) — 실제 네트워크 없이 보낸 머리·본문을 받아 기기 쪽 복호화로 내용을 확인한다.
 * 이상 사용: 허용 밖 엔드포인트(SSRF), 곡선 밖 키, 남의 구독·다른 회사 구독 해지(404), 알림함 끈 이벤트, 사라진 구독(410) 정리
 */
@AutoConfigureMockMvc
class PushTest extends PostgresTestBase {

    static final KeyPair VAPID = WebPushCrypto.newKeyPair();
    static final String FCM = "https://fcm.googleapis.com/fcm/send/";

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry r) {
        r.add("happyboot.push.vapid-public-key", () -> WebPushCrypto.b64(WebPushCrypto.raw((ECPublicKey) VAPID.getPublic())));
        r.add("happyboot.push.vapid-private-key", () -> WebPushCrypto.b64(WebPushCrypto.raw((ECPrivateKey) VAPID.getPrivate())));
        r.add("happyboot.push.subject", () -> "mailto:ops@example.test");
    }

    @MockitoBean
    PushTransport transport;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    NotificationService notify;

    @Autowired
    OutboxRelay relay;

    @Autowired
    PushJobs jobs;

    String comp;
    long userId;
    RequestPostProcessor me;

    /** 기기(브라우저) 쪽 키 — 시험마다 새로 */
    record Device(KeyPair keys, byte[] auth, String endpoint) {
        String p256dh() {
            return WebPushCrypto.b64(WebPushCrypto.raw((ECPublicKey) keys.getPublic()));
        }

        String json(String label) {
            return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"" + p256dh() + "\",\"auth\":\"" + WebPushCrypto.b64(auth)
                    + "\"},\"label\":\"" + label + "\"}";
        }
    }

    static Device device(String endpoint) {
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        return new Device(WebPushCrypto.newKeyPair(), auth, endpoint);
    }

    @BeforeEach
    void setUp() throws Exception {
        reset(transport);
        when(transport.post(anyString(), anyMap(), any())).thenReturn(201);
        comp = accounts.newComp("PU");
        userId = accounts.newUser(comp, "pu1", "Push-test-pw-1!");
        me = accounts.as(comp, userId, "pu1");
    }

    ResultActions subscribe(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(post("/api/platform/push/subscriptions").with(who).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions unsubscribe(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(delete("/api/platform/push/subscriptions").with(who).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    int subs(String c) {
        return accounts.query(c, "select count(*) from sys_push_sub", Integer.class);
    }

    void sendNotify(long uid, String eventCd, String dedup) {
        runner.run(comp, () -> notify.send(NotifyMessage.of(eventCd, uid, dedup, "notify.apr.todo", "극비 문서 제목", "요청자")));
    }

    // ------------------------------------------------------------------

    @Test
    void 켜짐_상태와_공개_키_구독_다시_구독하면_한_행() throws Exception {
        mvc.perform(get("/api/platform/push").with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.publicKey").value(WebPushCrypto.b64(WebPushCrypto.raw((ECPublicKey) VAPID.getPublic()))))
                .andExpect(jsonPath("$.data.devices.length()").value(0));
        Device d = device(FCM + "abc123");
        subscribe(me, d.json("Android Chrome")).andExpect(status().isOk());
        accounts.exec(comp, "update sys_push_sub set expires_at = now() + interval '1 day'");
        subscribe(me, d.json("Android\\u0000 Chrome\\u202e")).andExpect(status().isOk());   // 같은 기기 — 갱신
        assertThat(subs(comp)).isEqualTo(1);
        assertThat(accounts.query(comp, "select expires_at > now() + interval '80 days' from sys_push_sub", Boolean.class)).isTrue();
        assertThat(accounts.query(comp, "select device_label from sys_push_sub", String.class)).isEqualTo("Android  Chrome");
        mvc.perform(get("/api/platform/push").with(me)).andExpect(jsonPath("$.data.devices.length()").value(1))
                .andExpect(jsonPath("$.data.devices[0].endpoint").doesNotExist());
        // 감사 행에 엔드포인트·키는 가려진다
        assertThat(accounts.query(comp, """
                select count(*) from sys_audit_log where table_nm = 'sys_push_sub'
                   and (coalesce(after_data::text, '') like ? or coalesce(after_data::text, '') like ?)""", Integer.class,
                "%abc123%", "%" + d.p256dh() + "%")).isZero();
    }

    @Test
    void 허용_밖_엔드포인트와_틀린_키는_거부_SSRF() throws Exception {
        for (String ep : List.of("http://fcm.googleapis.com/x", "https://10.0.0.5/x", "https://127.0.0.1/x", "https://localhost/x",
                "https://fcm.googleapis.com:8443/x", "https://user@fcm.googleapis.com/x", "https://evil.example/fcm.googleapis.com",
                "https://fcm.googleapis.com.evil.example/x", "https://notify.windows.com/x", "https://xnotify.windows.com/x",
                "ftp://fcm.googleapis.com/x", "https://fcm.googleapis.com/x#frag")) {
            subscribe(me, device(ep).json("x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PUSH_ENDPOINT"));
        }
        // 허용: 하위 도메인 와일드카드
        subscribe(me, device("https://wns2-par02p.notify.windows.com/w/?token=1").json("w")).andExpect(status().isOk());
        // 곡선 밖 공개 키·auth 길이·빈 값
        byte[] off = new byte[65];
        off[0] = 4;
        off[64] = 1;
        Device good = device(FCM + "k");
        subscribe(me, "{\"endpoint\":\"" + FCM + "k\",\"keys\":{\"p256dh\":\"" + WebPushCrypto.b64(off) + "\",\"auth\":\"" +
                WebPushCrypto.b64(new byte[16]) + "\"}}").andExpect(status().isBadRequest());
        subscribe(me, "{\"endpoint\":\"" + FCM + "k\",\"keys\":{\"p256dh\":\"" + good.p256dh() + "\",\"auth\":\"" +
                WebPushCrypto.b64(new byte[8]) + "\"}}").andExpect(status().isBadRequest());
        subscribe(me, "{}").andExpect(status().isBadRequest());
        assertThat(subs(comp)).isEqualTo(1);
        verify(transport, times(0)).post(anyString(), anyMap(), any());
    }

    @Test
    void 남의_구독과_다른_회사_구독은_해지할_수_없다() throws Exception {
        Device mine = device(FCM + "mine");
        subscribe(me, mine.json("a")).andExpect(status().isOk());
        long other = accounts.newUser(comp, "pu2", "Push-test-pw-2!");
        RequestPostProcessor them = accounts.as(comp, other, "pu2");
        unsubscribe(them, "{\"endpoint\":\"" + FCM + "mine\"}").andExpect(status().isNotFound());
        long id = accounts.query(comp, "select push_sub_id from sys_push_sub", Long.class);
        unsubscribe(them, "{\"pushSubId\":" + id + "}").andExpect(status().isNotFound());
        // A사로 B사
        String b = accounts.newComp("PV");
        long bu = accounts.newUser(b, "pv1", "Push-test-pw-3!");
        unsubscribe(accounts.as(b, bu, "pv1"), "{\"pushSubId\":" + id + "}").andExpect(status().isNotFound());
        mvc.perform(get("/api/platform/push").with(accounts.as(b, bu, "pv1"))).andExpect(jsonPath("$.data.devices.length()").value(0));
        assertThat(subs(comp)).isEqualTo(1);
        unsubscribe(me, "{\"endpoint\":\"" + FCM + "mine\"}").andExpect(status().isOk());
        assertThat(subs(comp)).isZero();
    }

    @Test
    void 새_알림은_기기마다_암호화해_보내고_본문은_알림_종류만() throws Exception {
        Device phone = device(FCM + "phone");
        Device pc = device("https://updates.push.services.mozilla.com/wpush/v2/pc");
        subscribe(me, phone.json("phone")).andExpect(status().isOk());
        subscribe(me, pc.json("pc")).andExpect(status().isOk());
        sendNotify(userId, PlatformNotifyEvents.APR_TODO, "A1");
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'WEB_PUSH'", Integer.class)).isEqualTo(2);
        relay.relay(comp);

        ArgumentCaptor<String> ep = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hs = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(transport, times(2)).post(ep.capture(), hs.capture(), body.capture());
        int i = ep.getAllValues().indexOf(FCM + "phone");
        Map<String, String> h = hs.getAllValues().get(i);
        assertThat(h).containsEntry("Content-Encoding", "aes128gcm").containsEntry("TTL", "86400");
        assertThat(h.get("Authorization")).startsWith("vapid t=").contains(", k=" + WebPushCrypto.b64(WebPushCrypto.raw((ECPublicKey) VAPID.getPublic())));
        byte[] plain = WebPushCryptoTest.decrypt(body.getAllValues().get(i), (ECPrivateKey) phone.keys().getPrivate(),
                WebPushCrypto.raw((ECPublicKey) phone.keys().getPublic()), phone.auth());
        String text = new String(plain, StandardCharsets.UTF_8);
        JsonNode j = JsonMapper.builder().build().readTree(text);
        assertThat(j.get("u").asString()).isEqualTo("/main");
        assertThat(j.get("t").asString()).isNotBlank();
        assertThat(text).doesNotContain("극비", "요청자");   // 잠금 화면에 문서 제목이 가지 않는다
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'WEB_PUSH' and status = 'SENT'", Integer.class)).isEqualTo(2);
        assertThat(accounts.query(comp, "select count(*) from sys_push_sub where last_sent_at is not null", Integer.class)).isEqualTo(2);
    }

    @Test
    void 알림함을_끈_이벤트와_기기가_없는_사람에게는_푸시가_없다() throws Exception {
        subscribe(me, device(FCM + "p").json("p")).andExpect(status().isOk());
        accounts.exec(comp, """
                insert into sys_notify_sub (comp_cd, user_id, event_cd, inbox_yn, mail_yn, created_by, updated_by)
                values (?, ?, ?, 'N', 'N', 't', 't')""", comp, userId, PlatformNotifyEvents.APR_DONE);
        sendNotify(userId, PlatformNotifyEvents.APR_DONE, "D1");
        long other = accounts.newUser(comp, "pu3", "Push-test-pw-4!");
        sendNotify(other, PlatformNotifyEvents.APR_TODO, "T1");
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'WEB_PUSH'", Integer.class)).isZero();
    }

    @Test
    void 사라진_구독은_정리되고_아웃박스는_수동_종결_다른_실패는_남는다() throws Exception {
        subscribe(me, device(FCM + "gone").json("g")).andExpect(status().isOk());
        subscribe(me, device(FCM + "bad").json("b")).andExpect(status().isOk());
        when(transport.post(org.mockito.ArgumentMatchers.eq(FCM + "gone"), anyMap(), any())).thenReturn(410);
        when(transport.post(org.mockito.ArgumentMatchers.eq(FCM + "bad"), anyMap(), any())).thenReturn(400);
        sendNotify(userId, PlatformNotifyEvents.APR_TODO, "G1");
        relay.relay(comp);
        assertThat(accounts.query(comp, "select string_agg(status || ':' || left(last_error, 4), ',' order by biz_key) from sys_outbox where topic = 'WEB_PUSH'",
                String.class)).contains("FAIL:GONE", "FAIL:HTTP");
        // 만료 구독 하나 더
        subscribe(me, device(FCM + "old").json("o")).andExpect(status().isOk());
        accounts.exec(comp, "update sys_push_sub set expires_at = now() - interval '1 minute' where endpoint like '%old'");

        assertThat(jobs.cleanup().allSucceeded()).isTrue();
        assertThat(accounts.query(comp, "select string_agg(endpoint, ',') from sys_push_sub", String.class)).isEqualTo(FCM + "bad");
        assertThat(accounts.query(comp, """
                select status || '|' || closed_by from sys_outbox where topic = 'WEB_PUSH' and last_error like 'GONE%'""", String.class))
                .isEqualTo("CLOSED_MANUAL|SYSTEM");
        assertThat(accounts.query(comp, "select status from sys_outbox where topic = 'WEB_PUSH' and last_error like 'HTTP%'", String.class))
                .isEqualTo("FAIL");
        // 연계 현황 상세에서 엔드포인트·키가 가려진다(N9)
        long ob = accounts.query(comp, "select outbox_id from sys_outbox where topic = 'WEB_PUSH' and last_error like 'HTTP%'", Long.class);
        accounts.grant(comp, userId, "OB", "sys-outbox", "SEARCH");
        String detail = mvc.perform(get("/api/admin/outbox/" + ob).with(me)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(detail).doesNotContain(FCM + "bad").contains("\"endpoint\":\"h***\"", "\"auth\":\"");
    }

    @Test
    void 사람마다_기기는_10개까지_넘으면_오래된_것부터_지운다() throws Exception {
        for (int i = 0; i < 12; i++) {
            subscribe(me, device(FCM + "d" + i).json("d" + i)).andExpect(status().isOk());
        }
        assertThat(subs(comp)).isEqualTo(10);
        assertThat(accounts.query(comp, "select count(*) from sys_push_sub where endpoint in (?, ?)", Integer.class, FCM + "d0", FCM + "d1")).isZero();
    }
}
