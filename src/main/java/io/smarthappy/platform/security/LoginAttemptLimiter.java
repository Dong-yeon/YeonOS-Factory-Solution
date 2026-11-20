package io.smarthappy.platform.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.config.HappyBootProperties;

/**
 * 접속 주소 기준 로그인 시도 제한 (계정 잠금과 별개, docs/04 6.2-7).
 *
 * - 키 2개: "IP|회사코드"(같은 회사 계정을 무차별 시도) / "IP"(회사코드를 바꿔 가며 시도)
 * - 실패만 센다. 성공하면 <b>그 계정이 같은 IP|회사에서 낸 실패만</b> 지운다(docs/67 F5) — 예전에는 IP|회사 키를 통째로 비워서,
 *   그 회사 계정 하나를 가진 사람이 로그인 성공으로 다른 계정들에 대한 시도 한도를 되돌릴 수 있었다(스프레이).
 *   계정을 모르는 호출(POP 등록 코드)은 예전처럼 IP|회사 키를 비운다.
 * - IPv6 는 /64 단위로 센다 — 한 회선이 /64 를 통째로 받으므로 주소만 바꿔 가며 한도를 피하지 못하게(docs/67 F5)
 * - 창(window) 안 실패가 한도를 넘으면 창이 끝날 때까지 차단 — 비밀번호 확인·DB 조회 전에 막는다
 * - 인메모리: 인스턴스마다 따로 센다(서버 여러 대면 한도가 대수만큼 느슨해짐 — 문서화된 한계).
 *   Redis 를 두지 않는 결정(docs/04 3장)에 따른 것. 메모리는 키 수 상한으로 묶고, 넘으면 끝나는 시각이 이른 창부터 버린다
 *   (예전처럼 전부 비우면 키를 부풀리는 것만으로 진행 중 차단이 풀렸다).
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_KEYS = 50_000;
    /** 상한을 넘었을 때 이 크기까지 줄인다 */
    static final int TRIM_TO = MAX_KEYS * 9 / 10;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final HappyBootProperties.Security conf;
    private Clock clock = Clock.systemUTC();

    public LoginAttemptLimiter(HappyBootProperties props) {
        this.conf = props.security();
    }

    /** 테스트용 시계 교체 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 테스트용 — 지금 들고 있는 키 수 */
    int size() {
        return windows.size();
    }

    public boolean isBlocked(String ip, String compCd) {
        return isBlocked(ip, compCd, false);
    }

    /**
     * @param trustedIp 회사가 신뢰 IP 로 등록한 주소(정책 LOGIN_TRUSTED_IPS, 공장 NAT) — IP 단독 한도를 적용하지 않는다.
     *                  IP+회사 한도는 그대로 (신뢰 IP 에서의 무차별 대입도 막는다)
     */
    public boolean isBlocked(String ip, String compCd, boolean trustedIp) {
        String src = source(ip);
        return over(ipCompKey(src, compCd), conf.ipCompMaxFail()) || (!trustedIp && over(ipKey(src), conf.ipMaxFail()));
    }

    public void recordFailure(String ip, String compCd) {
        recordFailure(ip, compCd, null, false);
    }

    public void recordFailure(String ip, String compCd, boolean trustedIp) {
        recordFailure(ip, compCd, null, trustedIp);
    }

    /** @param loginId 시도한 아이디(없으면 null) — 성공했을 때 그 계정 몫만 빼려고 따로 센다 */
    public void recordFailure(String ip, String compCd, String loginId, boolean trustedIp) {
        if (windows.size() > MAX_KEYS) {
            purge();
        }
        String src = source(ip);
        String ipComp = ipCompKey(src, compCd);
        increment(ipComp);
        if (loginId != null) {
            increment(accountKey(ipComp, loginId));
        }
        if (!trustedIp) {
            increment(ipKey(src));
        }
    }

    /** 계정을 모르는 성공(POP 등록 코드) — IP|회사 키를 비운다 */
    public void recordSuccess(String ip, String compCd) {
        windows.remove(ipCompKey(source(ip), compCd));
    }

    /** 그 계정이 이 IP|회사에서 낸 실패만 지운다 — 다른 계정에 대한 실패는 그대로 남는다 */
    public void recordSuccess(String ip, String compCd, String loginId) {
        if (loginId == null) {
            recordSuccess(ip, compCd);
            return;
        }
        String ipComp = ipCompKey(source(ip), compCd);
        Window mine = windows.remove(accountKey(ipComp, loginId));
        if (mine == null || mine.expired(now())) {
            return;
        }
        windows.computeIfPresent(ipComp, (k, w) -> {
            int left = w.count - mine.count;
            return left <= 0 ? null : new Window(w.until, left);
        });
    }

    private boolean over(String key, int max) {
        Window w = windows.get(key);
        return w != null && !w.expired(now()) && w.count >= max;
    }

    private void increment(String key) {
        Instant now = now();
        Duration window = Duration.ofMinutes(conf.ipWindowMinutes());
        windows.compute(key, (k, w) -> (w == null || w.expired(now)) ? new Window(now.plus(window), 1)
                : new Window(w.until, w.count + 1));
    }

    private void purge() {
        Instant now = now();
        for (Iterator<Window> it = windows.values().iterator(); it.hasNext();) {
            if (it.next().expired(now)) {
                it.remove();
            }
        }
        if (windows.size() > MAX_KEYS) {
            // 키 폭증(공격) — 메모리 보호가 우선이지만 전부 비우지 않고 곧 끝날 창부터 버린다
            List<Map.Entry<String, Window>> all = new ArrayList<>(windows.entrySet());
            all.sort(Comparator.comparing(e -> e.getValue().until()));
            int drop = all.size() - TRIM_TO;
            for (int i = 0; i < drop; i++) {
                windows.remove(all.get(i).getKey(), all.get(i).getValue());
            }
        }
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 세는 단위 — IPv4 는 주소 그대로, IPv6 는 앞 64비트(/64). 글자는 getRemoteAddr() 의 숫자 주소라 DNS 를 타지 않는다
     * ({@link InetAddress#ofLiteral} 은 숫자 주소만 받는다). 숫자 주소가 아니면 글자 그대로.
     */
    public static String source(String ip) {
        if (ip == null || ip.indexOf(':') < 0) {
            return String.valueOf(ip);
        }
        try {
            InetAddress a = InetAddress.ofLiteral(ip.strip());
            if (a instanceof Inet6Address) {
                byte[] b = a.getAddress();
                return "v6:" + HexFormat.of().formatHex(b, 0, 8) + "/64";
            }
            return a.getHostAddress();   // ::ffff:1.2.3.4 같은 IPv4 대응 주소
        } catch (IllegalArgumentException e) {
            return ip.length() > 64 ? ip.substring(0, 64) : ip;
        }
    }

    private static String ipKey(String src) {
        return src;
    }

    private static String ipCompKey(String src, String compCd) {
        String comp = compCd == null ? "" : compCd;
        return src + "|" + (comp.length() > 20 ? comp.substring(0, 20) : comp);
    }

    private static String accountKey(String ipComp, String loginId) {
        String id = loginId.strip().toLowerCase(Locale.ROOT);
        return ipComp + "|u:" + (id.length() > 50 ? id.substring(0, 50) : id);
    }

    private record Window(Instant until, int count) {
        boolean expired(Instant now) {
            return !now.isBefore(until);
        }
    }
}
