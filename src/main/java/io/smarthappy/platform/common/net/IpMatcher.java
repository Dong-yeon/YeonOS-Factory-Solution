package io.smarthappy.platform.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * IP·CIDR 목록 (정책 LOGIN_TRUSTED_IPS 등). 예: "203.0.113.10, 198.51.100.0/24, 2001:db8::/32"
 * 호스트 이름은 받지 않는다 — DNS 조회가 일어나지 않게 리터럴 형식부터 확인한다.
 */
public final class IpMatcher {

    public static final int MAX_ENTRIES = 50;
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final List<Entry> entries;

    private record Entry(byte[] network, int prefix) {
    }

    private IpMatcher(List<Entry> entries) {
        this.entries = entries;
    }

    /** 형식이 틀리면 empty (정책 저장 시 검증용) */
    public static Optional<IpMatcher> parse(String list) {
        List<Entry> result = new ArrayList<>();
        if (list == null || list.isBlank()) {
            return Optional.of(new IpMatcher(result));
        }
        String[] parts = list.split(",");
        if (parts.length > MAX_ENTRIES) {
            return Optional.empty();
        }
        for (String raw : parts) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            String addr = s;
            int prefix = -1;
            int slash = s.indexOf('/');
            if (slash >= 0) {
                addr = s.substring(0, slash);
                try {
                    prefix = Integer.parseInt(s.substring(slash + 1));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
            byte[] bytes = literal(addr).orElse(null);
            if (bytes == null) {
                return Optional.empty();
            }
            int max = bytes.length * 8;
            if (prefix == -1) {
                prefix = max;
            }
            if (prefix < 0 || prefix > max) {
                return Optional.empty();
            }
            result.add(new Entry(bytes, prefix));
        }
        return Optional.of(new IpMatcher(result));
    }

    public boolean matches(String ip) {
        byte[] target = ip == null ? null : literal(ip).orElse(null);
        if (target == null) {
            return false;
        }
        for (Entry e : entries) {
            if (e.network.length == target.length && samePrefix(e.network, target, e.prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private static Optional<byte[]> literal(String s) {
        boolean v4 = IPV4.matcher(s).matches();
        boolean v6 = !v4 && s.indexOf(':') >= 0 && IPV6.matcher(s).matches();
        if (!v4 && !v6) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(s).getAddress()); // 리터럴만 통과시켰으므로 DNS 조회 없음
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    private static boolean samePrefix(byte[] a, byte[] b, int prefix) {
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (a[full] & mask) == (b[full] & mask);
    }
}
