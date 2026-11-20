package io.smarthappy.platform.log;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.smarthappy.platform.log.AccessEvent.Severity;
import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.notify.PlatformNotifyEvents;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantAwareTransactionManager;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import jakarta.servlet.http.HttpServletRequest;
import io.smarthappy.platform.metrics.HbMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 접속·보안 로그 기록 + 탐지 규칙 (01 7.3).
 *
 * - 본 요청과 분리된 트랜잭션으로 남긴다 → 본 요청이 롤백·거부돼도 기록은 남는다. 바깥 트랜잭션 안에서 부르면
 *   **그 트랜잭션이 연결을 반납한 뒤** 쓴다(docs/36 15.1 — 요청 하나가 연결 둘을 쥐지 않게). 바깥이 없으면 바로
 * - 기록 실패가 업무 요청을 깨지 않는다 (애플리케이션 로그로만 남김)
 * - 회사가 실제로 있을 때만 남긴다 (없는 회사코드로 로그인 시도를 반복해 로그를 채우는 것 방지)
 * - 비밀번호·토큰·세션 ID 는 남기지 않는다. 사용자가 입력한 값(로그인 ID 등)은 제어문자를 지우고 길이를 자른다
 * - 탐지: TENANT_FORBIDDEN 은 항상 ALERT, 같은 사용자의 권한 거부가 창 안에서 기준 이상이면 ALERT.
 *   ALERT 는 "SECURITY" 로거로도 내보낸다(외부 모니터링·SIEM 연결 지점)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessLogService {

    /** 권한 거부 ALERT 기준: 10분 안에 같은 사용자 10회 */
    static final int DENIED_ALERT_COUNT = 10;
    static final int DENIED_WINDOW_MINUTES = 10;
    /** 클라이언트 구분 헤더 (참고값 — 위조 가능하므로 판정에 쓰지 않는다) */
    public static final String CLIENT_HEADER = "X-HB-Client";
    private static final Set<String> CLIENT_TYPES = Set.of("WEB", "POP", "PDA", "API");
    private static final Logger SECURITY = LoggerFactory.getLogger("SECURITY");
    /** 요청 속성: 이번 요청의 기능 실행 로그(ACTION)에 붙일 상세 — 예: 엑셀 행 수 */
    static final String DETAIL_ATTR = AccessLogService.class.getName() + ".detail";

    private static final String INSERT = """
            insert into sys_access_log (comp_cd, user_id, login_id, event_cd, prog_cd, action_cd, result_cd, severity,
                                        client_type, ip, detail)
            select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
             where exists (select 1 from sys_comp where comp_cd = ?)""";

    /** 보안 경고 알림을 받는 화면 (docs/36 12.3) */
    static final String ALERT_PROG = "sys-access-log";
    private static final java.time.format.DateTimeFormatter HOUR =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(java.time.ZoneOffset.UTC);

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final NotificationService notify;
    private final HbMetrics metrics;

    public enum Result {
        OK, DENIED, FAIL
    }

    /** 로그인 사용자 기준 기록 (요청 안). 로그인 사용자가 없으면 남기지 않는다 */
    public void recordCurrent(AccessEvent event, String progCd, String actionCd, Result result, String detail) {
        LoginUser user = CurrentUser.get().orElse(null);
        if (user == null) {
            return;
        }
        record(event, user.compCd(), user.userId(), user.loginId(), progCd, actionCd, result, detail);
    }

    public void record(AccessEvent event, String compCd, Long userId, String loginId, String progCd, String actionCd,
            Result result, String detail) {
        metrics.accessEvent(event);   // 운영 지표(docs/52 Y2) — 없는 회사코드로 실패한 로그인도 센다(로그 행은 아래에서 거른다)
        if (!TenantContext.isValidCompCd(compCd)) {
            return;
        }
        String ip;
        String clientType;
        try {
            HttpServletRequest request = currentRequest();
            ip = request == null ? null : clip(request.getRemoteAddr(), 45);
            clientType = request == null ? "SYSTEM" : clientTypeOf(request);
        } catch (RuntimeException e) {
            log.error("접속 로그 요청 정보 읽기 실패 event={} compCd={}", event, compCd, e);
            return;
        }
        // 바깥 트랜잭션이 연결을 반납한 뒤에 쓴다(docs/36 15.1) — 롤백·거부여도 쓴다. 바깥 트랜잭션이 없으면 바로
        TenantAwareTransactionManager.afterRelease(() -> write(event, compCd, userId, loginId, progCd, actionCd, result,
                detail, ip, clientType));
    }

    private void write(AccessEvent event, String compCd, Long userId, String loginId, String progCd, String actionCd,
            Result result, String detail, String ip, String clientType) {
        try {
            boolean[] alert = {false};
            runner.runIsolated(compCd, () -> {
                Severity severity = severity(event, compCd, userId, result);
                int n = jdbc.update(INSERT, compCd, userId, clip(loginId, 50), event.name(), clip(progCd, 50),
                        clip(actionCd, 20), result.name(), severity.name(), clientType, ip, clip(detail, 500), compCd);
                if (n > 0 && severity == Severity.ALERT) {
                    SECURITY.warn("보안 경보 event={} compCd={} userId={} prog={} action={} ip={}", event, compCd, userId,
                            progCd, actionCd, ip);
                    alert[0] = true;
                }
            });
            if (alert[0]) {
                notifyAlert(event, compCd, loginId);
            }
        } catch (RuntimeException e) {
            log.error("접속 로그 기록 실패 event={} compCd={}", event, compCd, e);
        }
    }

    /**
     * 보안 경고를 회사의 로그 담당(로그 화면 SEARCH)에게 알림함으로 — 로그가 커밋된 뒤 **따로**(실패해도 로그는 남는다).
     * 같은 이벤트는 한 시간에 한 번(중복 키 = 이벤트 + UTC 시) — 공격 중 알림 폭주 방지(Q19)
     */
    private void notifyAlert(AccessEvent event, String compCd, String loginId) {
        try {
            String hour = HOUR.format(java.time.Instant.now());
            runner.runIsolated(compCd, () -> notify.searchers(ALERT_PROG).forEach(uid -> notify.send(NotifyMessage.of(
                    PlatformNotifyEvents.SECURITY_ALERT, uid, event.name() + ":" + hour, "notify.security.alert",
                    event.name(), loginId == null ? "-" : clip(loginId, 50))
                    .withLink(ALERT_PROG, Map.of("eventCd", event.name(), "severity", Severity.ALERT.name())))));
        } catch (RuntimeException e) {
            log.error("보안 경고 알림 실패 event={} compCd={}", event, compCd, e);
        }
    }

    private Severity severity(AccessEvent event, String compCd, Long userId, Result result) {
        if (result == Result.DENIED && userId != null && event.severity() != Severity.ALERT) {
            Integer recent = jdbc.queryForObject("""
                    select count(*) from sys_access_log
                     where comp_cd = ? and user_id = ? and result_cd = 'DENIED'
                       and occurred_at > now() - make_interval(mins => ?)""",
                    Integer.class, compCd, userId, DENIED_WINDOW_MINUTES);
            if (recent != null && recent + 1 >= DENIED_ALERT_COUNT) {
                return Severity.ALERT;
            }
        }
        return event.severity();
    }

    /** 이번 요청의 ACTION 로그 상세를 정한다 (값은 조건 키·건수 등 — 사용자 입력 값 금지) */
    public static void setDetail(String detail) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            attrs.getRequest().setAttribute(DETAIL_ATTR, detail);
        }
    }

    /** setDetail 로 정한 값 (없으면 null) */
    public static String currentDetail() {
        HttpServletRequest r = currentRequest();
        return r != null && r.getAttribute(DETAIL_ATTR) instanceof String s ? s : null;
    }

    /**
     * 요청의 클라이언트 구분 (docs/50 Q9, QA D-2 사용자 결정 2026-10-27). 위조 가능한 헤더는 판정에 쓰지 않는다.
     * <ol>
     *   <li>인증 주체가 서버가 아는 종류를 가지면(단말 = 등록 유형 POP/PDA) 그 값</li>
     *   <li>사무·포털 세션(LoginUser)은 헤더를 무시하고 WEB</li>
     *   <li>단말 경로(/pop·/api/pop)에서 아직 단말 주체가 없을 때(활성화 — D-1)만 헤더</li>
     *   <li>그 밖(로그인 화면 등 로그인 전 사무 요청)은 헤더가 외부 연계 API 일 때만 API, 나머지 WEB</li>
     * </ol>
     */
    public static String clientTypeOf(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth == null ? null : auth.getPrincipal();
        if (principal instanceof ClientTyped typed && typed.clientType() != null) {
            String v = typed.clientType().trim().toUpperCase(Locale.ROOT);
            if (CLIENT_TYPES.contains(v)) {
                return v;
            }
        }
        if (principal instanceof LoginUser) {
            return "WEB";
        }
        String header = clientType(request.getHeader(CLIENT_HEADER));
        if (isTerminalPath(request)) {
            return header;
        }
        return "API".equals(header) ? "API" : "WEB";
    }

    /** POP 단말 체인 경로 (module.pop PopSecurityConfig 의 securityMatcher 와 같게) */
    static boolean isTerminalPath(HttpServletRequest request) {
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        return uri.equals("/pop") || uri.startsWith("/pop/") || uri.startsWith("/api/pop/");
    }

    static String clientType(String header) {
        if (header == null) {
            return "WEB";
        }
        String v = header.trim().toUpperCase(Locale.ROOT);
        return CLIENT_TYPES.contains(v) ? v : "WEB";
    }

    /** 제어문자 제거 + 길이 제한 (로그 위조·과대 입력 방지) */
    static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").strip();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }
}
