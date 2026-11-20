package io.smarthappy.platform.push;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web Push 구독·발행 (docs/45 9장 N2~N8). 구독·해지·조회는 로그인한 "나" 의 기기만(RLS + user_id 조건).
 * <ul>
 *   <li>기기 등록 = 켜기(N3) — 같은 엔드포인트는 회사 안에서 하나: 다시 등록하면(다른 사람이 같은 브라우저로 로그인해 켠 경우 포함) 그 사람 것으로 바뀌고 만료 90일 연장</li>
 *   <li>사람마다 기기 10개까지 — 넘으면 가장 오래 갱신 안 된 기기를 지운다</li>
 *   <li>발행(N4)은 알림함에 새 알림이 들어간 같은 트랜잭션에서, 만료 전 기기마다 WEB_PUSH 한 건 — 본문은 이벤트 이름과 셸 주소만</li>
 *   <li>로그아웃해도 기기 구독은 남는다 — 개인 기기 전제(A-2), 세션 만료(12시간)마다 다시 켜지 않게. 공유 기기는 내 정보 [이 기기 알림 끄기]</li>
 * </ul>
 */
@Service
public class PushService {

    static final int EXPIRE_DAYS = 90;
    static final int MAX_DEVICES = 10;
    static final String OPEN_URL = "/main";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final PushSettings settings;
    private final OutboxService outbox;

    public PushService(JdbcTemplate jdbc, PushSettings settings, OutboxService outbox) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.outbox = outbox;
    }

    public record Device(long pushSubId, String deviceLabel, OffsetDateTime createdAt, OffsetDateTime expiresAt,
            OffsetDateTime lastSentAt) {
    }

    public record Status(boolean enabled, String publicKey, List<Device> devices) {
    }

    public boolean enabled() {
        return settings.enabled();
    }

    @Transactional(readOnly = true)
    public Status status() {
        LoginUser me = me();
        List<Device> devices = jdbc.query("""
                select push_sub_id, device_label, created_at, expires_at, last_sent_at from sys_push_sub
                 where user_id = ? order by updated_at desc""",
                (rs, i) -> new Device(rs.getLong(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class),
                        rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class)), me.userId());
        return new Status(settings.enabled(), settings.publicKeyB64(), devices);
    }

    /** 이 기기 구독 저장(있으면 갱신) → 구독 id */
    @Transactional
    public long subscribe(String endpoint, String p256dh, String auth, String label) {
        LoginUser me = me();
        if (!settings.enabled()) {
            throw new BusinessException(HbErrorCode.PUSH_DISABLED);
        }
        String ep = endpoint == null ? "" : endpoint.strip();
        if (!settings.allowedEndpoint(ep)) {
            throw new BusinessException(HbErrorCode.PUSH_ENDPOINT);
        }
        String key = p256dh == null ? "" : p256dh.strip();
        String secret = auth == null ? "" : auth.strip();
        try {
            WebPushCrypto.publicKey(WebPushCrypto.unb64(key));        // 곡선 위 점까지
            if (WebPushCrypto.unb64(secret).length != 16) {
                throw new IllegalArgumentException("auth 16바이트");
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        key = WebPushCrypto.b64(WebPushCrypto.unb64(key));   // 패딩·공백 없이 한 모양으로
        secret = WebPushCrypto.b64(WebPushCrypto.unb64(secret));
        String lbl = cleanLabel(label);
        Long id = jdbc.queryForObject("""
                insert into sys_push_sub (comp_cd, user_id, endpoint, p256dh, auth_secret, device_label, expires_at, created_by, updated_by)
                values (hb_current_comp(), ?, ?, ?, ?, ?, now() + make_interval(days => ?), ?, ?)
                on conflict (comp_cd, endpoint) do update
                   set user_id = excluded.user_id, p256dh = excluded.p256dh, auth_secret = excluded.auth_secret,
                       device_label = excluded.device_label, expires_at = excluded.expires_at,
                       updated_at = now(), updated_by = excluded.updated_by
                returning push_sub_id""", Long.class,
                me.userId(), ep, key, secret, lbl, EXPIRE_DAYS, me.loginId(), me.loginId());
        jdbc.update("""
                delete from sys_push_sub where push_sub_id in (
                    select push_sub_id from sys_push_sub where user_id = ? order by updated_at desc, push_sub_id desc offset ?)""",
                me.userId(), MAX_DEVICES);
        return id;
    }

    /** 내 기기 하나 해지 — 엔드포인트 또는 id. 내 것이 아니면(다른 사람·다른 회사) 404 */
    @Transactional
    public void unsubscribe(String endpoint, Long pushSubId) {
        LoginUser me = me();
        int n = pushSubId != null
                ? jdbc.update("delete from sys_push_sub where push_sub_id = ? and user_id = ?", pushSubId, me.userId())
                : jdbc.update("delete from sys_push_sub where endpoint = ? and user_id = ?", endpoint == null ? "" : endpoint.strip(),
                        me.userId());
        if (n == 0) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    /**
     * 새 알림함 알림 하나 → 받는 사람의 기기마다 WEB_PUSH (업무 트랜잭션 안, N4). 꺼져 있거나 기기가 없으면 아무것도 안 한다 → 발행 건수.
     * title = 이벤트 이름(받는 사람 언어) — 문서 제목은 싣지 않는다(잠금 화면)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int publish(long userId, long notifyId, String title) {
        if (!settings.enabled()) {
            return 0;
        }
        List<Map<String, Object>> subs = jdbc.queryForList("""
                select push_sub_id, endpoint, p256dh, auth_secret from sys_push_sub
                 where user_id = ? and expires_at > now() order by push_sub_id""", userId);
        String body = JSON.writeValueAsString(Map.of("t", title == null ? "" : title, "u", OPEN_URL));
        int n = 0;
        for (Map<String, Object> s : subs) {
            String ep = (String) s.get("endpoint");
            if (!settings.allowedEndpoint(ep)) {
                continue;   // 설정에서 호스트를 뺀 뒤 남은 구독 — 보내지 않는다(정리 작업이 만료로 지운다)
            }
            outbox.publish(WebPushHandler.TOPIC, "PUSH:" + notifyId + ":" + s.get("push_sub_id"), Map.of(
                    "endpoint", ep, "p256dh", s.get("p256dh"), "auth", s.get("auth_secret"), "body", body));
            n++;
        }
        if (n > 0) {
            jdbc.update("update sys_push_sub set last_sent_at = now() where user_id = ? and expires_at > now()", userId);
        }
        return n;
    }

    /**
     * 정리(매일, 회사 범위 안 — PushJobs): 만료 구독 삭제 + 푸시 서비스가 "사라짐"(404·410)이라 한 구독 삭제와 그 아웃박스 행 수동 종결(N8).
     * → [지운 구독, 종결한 행]
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int[] cleanup() {
        int expired = jdbc.update("delete from sys_push_sub where expires_at <= now()");
        List<Map<String, Object>> gone = jdbc.queryForList("""
                select outbox_id, payload ->> 'endpoint' as endpoint from sys_outbox
                 where topic = ? and status = 'FAIL' and close_apr_id is null and last_error like ?
                 order by outbox_id for update""", WebPushHandler.TOPIC, WebPushHandler.GONE + ":%");
        int removed = expired;
        int closed = 0;
        for (Map<String, Object> g : gone) {
            removed += jdbc.update("delete from sys_push_sub where endpoint = ?", g.get("endpoint"));
            closed += jdbc.update("""
                    update sys_outbox set status = 'CLOSED_MANUAL', closed_at = now(), closed_by = 'SYSTEM',
                           close_reason = '구독 만료 — 푸시 서비스가 사라진 구독으로 응답(404·410), 구독을 지움'
                     where outbox_id = ? and status = 'FAIL' and close_apr_id is null""", g.get("outbox_id"));
        }
        return new int[] {removed, closed};
    }

    static String cleanLabel(String label) {
        if (label == null) {
            return null;
        }
        String s = label.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").strip();
        return s.isEmpty() ? null : s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
