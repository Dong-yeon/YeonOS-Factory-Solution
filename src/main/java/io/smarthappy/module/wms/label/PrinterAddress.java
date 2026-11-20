package io.smarthappy.module.wms.label;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 프린터 주소 검사 (docs/30 D6) — 서버가 고객 내부망으로 TCP 를 여는 기능이라 **사설 대역만** 보낸다(SSRF 방지).
 *
 * <ul>
 * <li>허용: IPv4 10/8 · 172.16/12 · 192.168/16, IPv6 ULA fc00::/7 (+ 설정으로 루프백 — 테스트·데모의 가짜 프린터)</li>
 * <li>거부: 공인 주소, 링크로컬 169.254/16(클라우드 메타데이터 포함)·fe80::/10, 0.0.0.0·::, 멀티캐스트, CGNAT 100.64/10, 루프백(기본)</li>
 * <li>이름은 저장 때 형식만 보고, **보낼 때마다 해석해 모든 주소를 검사**한 뒤 검사한 그 주소로 연결한다(DNS 재바인딩 방지)</li>
 * </ul>
 */
public final class PrinterAddress {

    static final Pattern HOST_NAME = Pattern.compile(
            "^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");
    static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    public enum Problem {
        FORMAT, NOT_PRIVATE, UNRESOLVED, PORT_NOT_ALLOWED
    }

    /** 이름 해석기 — 테스트가 바꾼다 */
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public static final Resolver SYSTEM = InetAddress::getAllByName;

    private PrinterAddress() {
    }

    /** 저장·요청 때: 형식 + (IP 글자면) 사설 여부 + 포트. DNS 를 부르지 않는다(트랜잭션 안에서 외부 호출 금지). 문제 없으면 null */
    public static Problem checkStatic(String host, int port, Set<Integer> allowedPorts, boolean allowLoopback) {
        if (host == null || host.isBlank()) {
            return Problem.FORMAT;
        }
        String h = host.strip();
        if (port < 1 || port > 65535 || (allowedPorts != null && !allowedPorts.contains(port))) {
            return Problem.PORT_NOT_ALLOWED;
        }
        InetAddress literal = literal(h);
        if (literal != null) {
            return isAllowed(literal, allowLoopback) ? null : Problem.NOT_PRIVATE;
        }
        if (h.indexOf(':') >= 0 || IPV4.matcher(h).matches() || !HOST_NAME.matcher(h).matches()) {
            return Problem.FORMAT;      // 잘못된 IP 글자(300.1.1.1 등)·이름 형식 밖
        }
        if ("localhost".equals(h.toLowerCase(Locale.ROOT)) && !allowLoopback) {
            return Problem.NOT_PRIVATE;
        }
        return null;
    }

    /** 보낼 때: 해석한 모든 주소가 허용 대역이어야 하고, 연결할 주소(첫 번째)를 돌려준다 */
    public static InetAddress resolveChecked(String host, boolean allowLoopback, Resolver resolver) throws AddressException {
        String h = host == null ? "" : host.strip();
        InetAddress literal = literal(h);
        InetAddress[] all;
        if (literal != null) {
            all = new InetAddress[] {literal};
        } else {
            if (!HOST_NAME.matcher(h).matches()) {
                throw new AddressException(Problem.FORMAT, h);
            }
            try {
                all = resolver.resolve(h);
            } catch (UnknownHostException e) {
                throw new AddressException(Problem.UNRESOLVED, h);
            }
        }
        if (all == null || all.length == 0) {
            throw new AddressException(Problem.UNRESOLVED, h);
        }
        for (InetAddress a : all) {
            if (!isAllowed(a, allowLoopback)) {
                throw new AddressException(Problem.NOT_PRIVATE, a.getHostAddress());
            }
        }
        return all[0];
    }

    static boolean isAllowed(InetAddress a, boolean allowLoopback) {
        if (a.isLoopbackAddress()) {
            return allowLoopback;
        }
        if (a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int o0 = b[0] & 0xff;
            int o1 = b[1] & 0xff;
            return o0 == 10 || (o0 == 172 && o1 >= 16 && o1 <= 31) || (o0 == 192 && o1 == 168);
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc;     // fc00::/7 ULA (사이트로컬 fec0::/10 은 폐기된 대역이라 받지 않는다)
        }
        return false;
    }

    /** IP 글자면 주소, 아니면 null — 이름 해석을 일으키지 않는다 */
    static InetAddress literal(String h) {
        try {
            Matcher4 m = Matcher4.of(h);
            if (m != null) {
                return InetAddress.getByAddress(m.bytes);
            }
            if (h.indexOf(':') >= 0 && h.matches("^[0-9A-Fa-f:.]{2,45}$")) {
                return InetAddress.getByName(h);     // ':' 가 있는 글자는 IPv6 글자로만 해석된다(DNS 조회 없음)
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    private record Matcher4(byte[] bytes) {
        static Matcher4 of(String h) {
            java.util.regex.Matcher m = IPV4.matcher(h);
            if (!m.matches()) {
                return null;
            }
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) {
                String part = m.group(i + 1);
                int v = Integer.parseInt(part);
                if (v > 255 || (part.length() > 1 && part.charAt(0) == '0')) {
                    return null;              // 300.x · 010.x(8진수로 읽는 구현이 있다)는 IP 로 보지 않는다
                }
                b[i] = (byte) v;
            }
            return new Matcher4(b);
        }
    }

    public static final class AddressException extends Exception {
        private final Problem problem;

        public AddressException(Problem problem, String detail) {
            super(problem + ": " + detail);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }
}
