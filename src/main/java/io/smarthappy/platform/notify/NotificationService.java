package io.smarthappy.platform.notify;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.push.PushService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사내 알림함 (docs/36 11.3). 보내기는 **업무 트랜잭션 안에서 바로 INSERT**(MANDATORY) — 아웃박스 송신기는 DB 를 쓸 수 없고,
 * 업무가 롤백되면 알림도 없어야 한다. 받는 사람이 사용 중이 아니거나 구독을 껐거나, 링크 화면을 볼 수 없으면 보내지 않는다(Q15).
 * 같은 (사람, 이벤트, 중복 키)는 한 번만(유일 인덱스 + on conflict do nothing).
 * 읽기·읽음·구독은 로그인한 "나" 의 것만.
 * 메일(docs/36 18.3): 알림함에 새로 들어간 알림만, 받는 사람이 그 이벤트의 메일을 켰고 메일 주소가 있을 때 — 같은 트랜잭션에서
 * 아웃박스 MAIL 로 발행(업무가 롤백되면 메일도 없다). 제목·본문은 받는 사람 언어로 여기서 만든다(송신기는 DB 를 못 쓴다).
 */
@Service
public class NotificationService {

    /** 보내기 결과 — 업무 코드는 대개 무시한다 */
    public enum Result {
        SENT, DUPLICATE, NO_USER, UNSUBSCRIBED, NO_PERMISSION
    }

    public record InboxLine(String label, long count, String progCd, String progNm) {
    }

    public record InboxItem(long notifyId, String eventCd, String eventNm, String title, String linkProg,
            String linkProgNm, Map<String, String> linkParams, List<InboxLine> lines, OffsetDateTime createdAt,
            OffsetDateTime readAt) {
    }

    /** mailAvailable = 메일 설정이 켜졌고 내 메일 주소가 있다(아니면 화면이 메일 칸을 막는다) */
    public record SubView(String eventCd, String eventNm, boolean defaultOn, String inboxYn, String mailYn, boolean mailAvailable) {
    }

    /** mailYn 없음(null) = 지금 값 그대로 */
    public record SubForm(String eventCd, String inboxYn, String mailYn) {
    }

    static final int MAX_ARGS = 5;
    static final int MAX_ARG_LEN = 200;
    static final int MAX_PARAMS = 10;
    static final int MAX_LINES = 20;
    static final int MAX_PAGE = 50;
    static final Pattern KEY = Pattern.compile("^notify\\.[A-Za-z0-9_.]{1,90}$");
    static final Pattern PARAM = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,29}$");
    static final Pattern PROG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F\\u202A-\\u202E\\u2066-\\u2069]");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final Map<String, NotifyEventDef> events;
    private final ObjectProvider<InboxBadgeSource> badges;
    private final OutboxService outbox;
    private final MailSettings mail;
    private final PushService push;
    private final io.smarthappy.platform.realtime.SseHub realtime;

    public NotificationService(JdbcTemplate jdbc, MessageSource messages, ObjectProvider<NotifyEventSource> sources,
            ObjectProvider<InboxBadgeSource> badges, OutboxService outbox, MailSettings mail, PushService push,
            io.smarthappy.platform.realtime.SseHub realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
        this.push = push;
        this.messages = messages;
        this.badges = badges;
        this.outbox = outbox;
        this.mail = mail;
        Map<String, NotifyEventDef> m = new LinkedHashMap<>();
        sources.orderedStream().flatMap(s -> s.events().stream()).sorted(Comparator.comparingInt(NotifyEventDef::sortNo))
                .forEach(d -> {
                    if (m.putIfAbsent(d.eventCd(), d) != null) {
                        throw new IllegalStateException("알림 이벤트 코드 중복: " + d.eventCd());
                    }
                });
        this.events = Map.copyOf(m);
    }

    public List<NotifyEventDef> events() {
        return events.values().stream().sorted(Comparator.comparingInt(NotifyEventDef::sortNo)).toList();
    }

    // ------------------------------------------------------------------ 보내기

    @Transactional(propagation = Propagation.MANDATORY)
    public Result send(NotifyMessage m) {
        NotifyEventDef def = m == null ? null : events.get(m.eventCd());
        if (def == null) {
            throw new IllegalArgumentException("등록되지 않은 알림 이벤트: " + (m == null ? null : m.eventCd()));
        }
        checkShape(m);
        // 다른 회사 사용자는 RLS 로 안 보인다 → 없는 사람. 협력사 포털 사용자도 알림을 받지 않는다(docs/42 2장)
        List<String> user = jdbc.queryForList("select use_yn from sys_user where user_id = ? and user_type = 'INTERNAL'",
                String.class, m.userId());
        if (user.isEmpty() || !"Y".equals(user.get(0))) {
            return Result.NO_USER;
        }
        List<Map<String, Object>> sub = jdbc.queryForList("select inbox_yn, mail_yn from sys_notify_sub where user_id = ? and event_cd = ?",
                m.userId(), m.eventCd());
        if (sub.isEmpty() ? !def.defaultOn() : !"Y".equals(sub.get(0).get("inbox_yn"))) {
            return Result.UNSUBSCRIBED;
        }
        boolean mailOn = !sub.isEmpty() && "Y".equals(sub.get(0).get("mail_yn"));
        if (m.linkProg() != null && !canSearch(m.userId(), m.linkProg())) {
            return Result.NO_PERMISSION;
        }
        List<String> args = m.titleArgs() == null ? List.of() : m.titleArgs().stream().map(NotificationService::clean).toList();
        List<Long> id = jdbc.queryForList("""
                insert into sys_notify (comp_cd, user_id, event_cd, dedup_key, title_key, title_args, link_prog_cd,
                                        link_params, lines, created_by)
                values (hb_current_comp(), ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?)
                on conflict (comp_cd, user_id, event_cd, dedup_key) do nothing
                returning notify_id""", Long.class,
                m.userId(), m.eventCd(), m.dedupKey(), m.titleKey(), JSON.writeValueAsString(args),
                m.linkProg(), JSON.writeValueAsString(m.linkParams() == null ? Map.of() : m.linkParams()),
                JSON.writeValueAsString(m.lines() == null ? List.of() : m.lines()), TenantContext.currentActor());
        if (id.isEmpty()) {
            return Result.DUPLICATE;
        }
        if (mailOn && mail.enabled()) {
            mail(id.get(0), m, args);
        }
        push(id.get(0), m);
        // 열려 있는 화면(홈)에 "알림함이 바뀜" 신호 — 커밋 뒤, 본문 없이(docs/70 3장)
        realtime.publishAfterCommit(TenantContext.requireCompCd(), m.userId(), "inbox");
        return Result.SENT;
    }

    /** 등록한 기기로 Web Push(docs/45 9장 N3·N4) — 본문은 이벤트 이름만(받는 사람 언어), 문서 제목은 싣지 않는다 */
    private void push(long notifyId, NotifyMessage m) {
        if (!push.enabled()) {
            return;
        }
        String localeCd = jdbc.queryForObject("select locale_cd from sys_user where user_id = ?", String.class, m.userId());
        Locale locale = localeCd == null ? Locale.KOREAN : Locale.forLanguageTag(localeCd);
        push.publish(m.userId(), notifyId, text(events.get(m.eventCd()).nameKey(), locale));
    }

    /** 메일 한 통 발행 — 주소가 없거나 형식이 틀리면 조용히 건너뛴다(알림함은 이미 들어감) */
    private void mail(long notifyId, NotifyMessage m, List<String> args) {
        Map<String, Object> u = jdbc.queryForMap("select email, locale_cd from sys_user where user_id = ?", m.userId());
        String to = u.get("email") == null ? null : ((String) u.get("email")).trim();
        if (!MailSettings.validAddress(to)) {
            return;
        }
        Locale locale = Locale.forLanguageTag((String) u.get("locale_cd"));
        String title = clean(text(m.titleKey(), locale, args.toArray()));
        StringBuilder body = new StringBuilder(title);
        for (NotifyMessage.Line l : m.lines() == null ? List.<NotifyMessage.Line>of() : m.lines()) {
            body.append("\n- ").append(clean(text(l.labelKey(), locale, l.count())));
        }
        body.append("\n\n").append(text("notify.mail.footer", locale));
        if (!mail.baseUrl().isEmpty()) {
            body.append("\n").append(mail.baseUrl()).append(m.linkProg() == null ? "/" : "/page/" + m.linkProg());
        }
        outbox.publish(MailNotifyHandler.TOPIC, "NOTIFY:" + notifyId, Map.of("kind", "NOTIFY", "to", to,
                "subject", text("notify.mail.subject", locale, title), "body", body.toString()));
    }

    /** 프로그래밍 오류는 예외 — 사용자 입력이 아니라 모듈 코드가 만든 값 */
    private static void checkShape(NotifyMessage m) {
        if (m.dedupKey() == null || m.dedupKey().isEmpty() || m.dedupKey().length() > 100) {
            throw new IllegalArgumentException("알림 중복 키는 1~100자");
        }
        if (m.titleKey() == null || !KEY.matcher(m.titleKey()).matches()) {
            throw new IllegalArgumentException("알림 제목 키는 notify. 로 시작: " + m.titleKey());
        }
        if (m.titleArgs() != null && (m.titleArgs().size() > MAX_ARGS || m.titleArgs().stream().anyMatch(a -> a == null))) {
            throw new IllegalArgumentException("알림 인자는 null 없이 " + MAX_ARGS + "개까지");
        }
        if (m.linkProg() != null && !PROG.matcher(m.linkProg()).matches()) {
            throw new IllegalArgumentException("링크 화면 코드 형식: " + m.linkProg());
        }
        Map<String, String> p = m.linkParams() == null ? Map.of() : m.linkParams();
        if (p.size() > MAX_PARAMS || p.entrySet().stream().anyMatch(e -> !PARAM.matcher(e.getKey()).matches()
                || e.getValue() == null || e.getValue().length() > MAX_ARG_LEN)) {
            throw new IllegalArgumentException("링크 조건 형식");
        }
        List<NotifyMessage.Line> lines = m.lines() == null ? List.of() : m.lines();
        if (lines.size() > MAX_LINES || lines.stream().anyMatch(l -> l == null || l.labelKey() == null
                || !KEY.matcher(l.labelKey()).matches() || (l.progCd() != null && !PROG.matcher(l.progCd()).matches()))) {
            throw new IllegalArgumentException("알림 세부 줄 형식");
        }
    }

    /** 인자는 사람이 쓴 글자(결재 제목 등)일 수 있다 — 제어·양방향 문자 제거, 길이 제한 */
    static String clean(String s) {
        String c = CONTROL.matcher(s).replaceAll(" ").strip();
        return c.length() > MAX_ARG_LEN ? c.substring(0, MAX_ARG_LEN) : c;
    }

    /** 받는 사람이 그 화면 SEARCH 를 갖는가 — 권한 판정(AuthMapper)과 같은 규칙: 개인 화면·관리자 그룹·그룹 행, 회사 사용자만 */
    boolean canSearch(long userId, String progCd) {
        return searchQuery(progCd, userId).contains(userId);
    }

    /** 그 화면을 볼 수 있는 사용 중 사용자 — 보안 경고처럼 "역할" 에게 보낼 때 */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> searchers(String progCd) {
        return searchQuery(progCd, null);
    }

    private List<Long> searchQuery(String progCd, Long onlyUser) {
        return jdbc.queryForList("""
                select u.user_id
                  from sys_user u
                  join sys_prog p on p.prog_cd = ? and p.use_yn = 'Y' and 'SEARCH' = any (p.actions)
                 where (?::bigint is null or u.user_id = ?::bigint)
                   and u.user_type = 'INTERNAL'
                   and (p.self_service_yn = 'Y'
                        or exists (select 1 from sys_user_auth ua
                                     join sys_auth_group g on g.comp_cd = ua.comp_cd and g.auth_grp_cd = ua.auth_grp_cd
                                    where ua.user_id = u.user_id and g.use_yn = 'Y'
                                      and (g.admin_yn = 'Y' or exists (select 1 from sys_auth_prog ap
                                             where ap.comp_cd = g.comp_cd and ap.auth_grp_cd = g.auth_grp_cd
                                               and ap.prog_cd = p.prog_cd and ap.action_cd = 'SEARCH'))))
                   and (?::bigint is not null or u.use_yn = 'Y')
                 order by u.user_id""", Long.class, progCd, onlyUser, onlyUser, onlyUser);
    }

    // ------------------------------------------------------------------ 내 알림함

    /** 최신순, beforeId 보다 오래된 것(다음 쪽), 최대 50건 */
    @Transactional(readOnly = true)
    public List<InboxItem> inbox(boolean unreadOnly, Long beforeId, Integer size, Locale locale) {
        LoginUser me = me();
        int limit = size == null ? 30 : Math.max(1, Math.min(size, MAX_PAGE));
        Map<String, String> progNames = new HashMap<>();
        jdbc.query("select prog_cd, prog_nm_key from sys_prog where use_yn = 'Y'",
                rs -> { progNames.put(rs.getString(1), rs.getString(2)); });
        return jdbc.query("""
                select notify_id, event_cd, title_key, title_args::text, link_prog_cd, link_params::text, lines::text,
                       created_at, read_at
                  from sys_notify
                 where user_id = ?
                   and (? = false or read_at is null)
                   and (?::bigint is null or notify_id < ?::bigint)
                 order by notify_id desc
                 limit ?""",
                (rs, i) -> {
                    List<String> args = JSON.readValue(rs.getString(4), new TypeReference<List<String>>() { });
                    String link = rs.getString(5);
                    List<NotifyMessage.Line> raw = JSON.readValue(rs.getString(7),
                            new TypeReference<List<NotifyMessage.Line>>() { });
                    List<InboxLine> lines = new ArrayList<>();
                    for (NotifyMessage.Line l : raw) {
                        lines.add(new InboxLine(text(l.labelKey(), locale, l.count()), l.count(), l.progCd(),
                                progName(progNames, l.progCd(), locale)));
                    }
                    NotifyEventDef def = events.get(rs.getString(2));
                    return new InboxItem(rs.getLong(1), rs.getString(2),
                            def == null ? rs.getString(2) : text(def.nameKey(), locale),
                            text(rs.getString(3), locale, args.toArray()), link, progName(progNames, link, locale),
                            JSON.readValue(rs.getString(6), new TypeReference<Map<String, String>>() { }), lines,
                            rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class));
                },
                me.userId(), unreadOnly, beforeId, beforeId, limit);
    }

    private String progName(Map<String, String> names, String progCd, Locale locale) {
        String key = progCd == null ? null : names.get(progCd);
        return key == null ? null : messages.getMessage(key, null, progCd, locale);
    }

    private String text(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, key, locale);
    }

    /** 안 읽은 수 — 알림 + 공지 등({@link InboxBadgeSource}) */
    @Transactional(readOnly = true)
    public Map<String, Long> unreadCounts() {
        LoginUser me = me();
        Map<String, Long> out = new LinkedHashMap<>();
        Long n = jdbc.queryForObject("select count(*) from sys_notify where user_id = ? and read_at is null",
                Long.class, me.userId());
        out.put("notify", n == null ? 0 : n);
        badges.orderedStream().forEach(b -> out.put(b.code(), b.unread(me.userId())));
        out.put("total", out.values().stream().mapToLong(Long::longValue).sum());
        return out;
    }

    /** 읽음 — 이미 읽었으면 그대로(멱등), 내 것이 아니면 404 */
    @Transactional
    public void markRead(long notifyId) {
        LoginUser me = me();
        int n = jdbc.update("update sys_notify set read_at = now() where notify_id = ? and user_id = ? and read_at is null",
                notifyId, me.userId());
        if (n == 0) {
            Integer mine = jdbc.queryForObject("select count(*) from sys_notify where notify_id = ? and user_id = ?",
                    Integer.class, notifyId, me.userId());
            if (mine == null || mine == 0) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
        }
    }

    @Transactional
    public int markAllRead() {
        return jdbc.update("update sys_notify set read_at = now() where user_id = ? and read_at is null", me().userId());
    }

    // ------------------------------------------------------------------ 구독

    @Transactional(readOnly = true)
    public List<SubView> subscriptions(Locale locale) {
        LoginUser me = me();
        Map<String, String[]> mine = new HashMap<>();
        jdbc.query("select event_cd, inbox_yn, mail_yn from sys_notify_sub where user_id = ?",
                rs -> { mine.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)}); }, me.userId());
        boolean mailAvailable = mail.enabled() && MailSettings.validAddress(jdbc.queryForObject(
                "select btrim(email) from sys_user where user_id = ?", String.class, me.userId()));
        return events().stream().map(d -> {
            String[] row = mine.get(d.eventCd());
            return new SubView(d.eventCd(), text(d.nameKey(), locale), d.defaultOn(),
                    row == null ? (d.defaultOn() ? "Y" : "N") : row[0], row == null ? "N" : row[1], mailAvailable);
        }).toList();
    }

    /** 내 구독 한 벌 저장 — 개인 설정이라 버전 확인 없이 마지막 저장이 이긴다. 바뀐 행만 고친다(감사 잡음 방지) */
    @Transactional
    public void saveSubscriptions(List<SubForm> list) {
        LoginUser me = me();
        if (list == null || list.size() > events.size() * 2) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        for (int i = 0; i < list.size(); i++) {
            SubForm f = list.get(i);
            if (f == null || f.eventCd() == null || !events.containsKey(f.eventCd())) {
                e.add("eventCd", "CODE");
            } else if (!"Y".equals(f.inboxYn()) && !"N".equals(f.inboxYn())) {
                e.add("inboxYn", "CODE");
            } else if (f.mailYn() != null && !"Y".equals(f.mailYn()) && !"N".equals(f.mailYn())) {
                e.add("mailYn", "CODE");
            }
        }
        e.throwIfAny();
        for (SubForm f : list) {
            jdbc.update("""
                    insert into sys_notify_sub (comp_cd, user_id, event_cd, inbox_yn, mail_yn, created_by, updated_by)
                    values (hb_current_comp(), ?, ?, ?, coalesce(?, 'N'), ?, ?)
                    on conflict (comp_cd, user_id, event_cd) do update
                       set inbox_yn = excluded.inbox_yn, mail_yn = coalesce(?, sys_notify_sub.mail_yn),
                           row_ver = sys_notify_sub.row_ver + 1, updated_at = now(), updated_by = excluded.updated_by
                     where sys_notify_sub.inbox_yn <> excluded.inbox_yn
                        or sys_notify_sub.mail_yn <> coalesce(?, sys_notify_sub.mail_yn)""",
                    me.userId(), f.eventCd(), f.inboxYn(), f.mailYn(), me.loginId(), me.loginId(), f.mailYn(), f.mailYn());
        }
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
