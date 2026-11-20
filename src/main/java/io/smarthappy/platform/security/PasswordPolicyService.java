package io.smarthappy.platform.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;

/**
 * 비밀번호 정책 (docs/05 D9 — NIST SP 800-63B-4 방향).
 * 기본: 최소 12자, 최대 64자, 흔한·유출 비밀번호 차단, 회사코드·아이디 포함 금지. **조합 규칙·주기 변경은 기본 끔**
 * (고객이 정책으로 켜면 허용된 동작). 반환값은 위반 항목의 메시지 키 목록(비어 있으면 통과).
 */
@Service
public class PasswordPolicyService {

    public static final int MAX_LENGTH = 64;
    /** BCrypt 입력 한도 */
    public static final int MAX_BYTES = 72;

    private final PolicyService policies;
    private final Set<String> blocklist;
    private final List<String> longEntries;

    public PasswordPolicyService(PolicyService policies) {
        this.policies = policies;
        this.blocklist = load();
        this.longEntries = blocklist.stream().filter(s -> s.length() >= 8).toList();
    }

    public record Violation(String messageKey, Object[] args) {
    }

    @Transactional(readOnly = true)
    public List<Violation> check(String password, String compCd, String loginId) {
        List<Violation> v = new ArrayList<>();
        String pw = password == null ? "" : password;
        int min = policies.intValue(Policies.PWD_MIN_LENGTH);
        int len = pw.codePointCount(0, pw.length());
        if (len < min) {
            v.add(new Violation("pwd.tooShort", new Object[] {min}));
        }
        if (len > MAX_LENGTH || pw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            v.add(new Violation("pwd.tooLong", new Object[] {MAX_LENGTH}));
        }
        if (!pw.isEmpty() && pw.codePoints().distinct().count() == 1) {
            v.add(new Violation("pwd.repeated", new Object[0]));
        }
        String lower = pw.toLowerCase(Locale.ROOT);
        if (contains(lower, loginId) || contains(lower, compCd)) {
            v.add(new Violation("pwd.containsId", new Object[0]));
        }
        if (policies.bool(Policies.PWD_BLOCKLIST_ENABLED) && blocked(lower)) {
            v.add(new Violation("pwd.common", new Object[0]));
        }
        if (policies.bool(Policies.PWD_COMPLEXITY_REQUIRED) && classes(pw) < 3) {
            v.add(new Violation("pwd.complexity", new Object[0]));
        }
        return v;
    }

    private boolean blocked(String lower) {
        if (blocklist.contains(lower)) {
            return true;
        }
        // 끝에 붙인 숫자·기호를 떼어 본다 (password123!, qwer1234@ 등)
        String base = lower.replaceAll("[0-9!@#$%^&*.?_~-]+$", "");
        if (base.length() >= 4 && blocklist.contains(base)) {
            return true;
        }
        // 앞에 붙인 숫자·기호도 (!!password, 2026qwerty)
        String core = base.replaceAll("^[0-9!@#$%^&*.?_~-]+", "");
        if (core.length() >= 4 && blocklist.contains(core)) {
            return true;
        }
        // 8자 이상 흔한 패턴은 어디에 끼워 넣어도 막는다 (qwer1234qwer, !!1q2w3e4r5t).
        // 짧은 단어(summer, office 등)는 부분 포함으로 막지 않는다 — 긴 문장형 비밀번호를 해치지 않게
        for (String common : longEntries) {
            if (lower.contains(common)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String lowerPw, String id) {
        return id != null && id.length() >= 3 && lowerPw.contains(id.toLowerCase(Locale.ROOT));
    }

    private static int classes(String pw) {
        int c = 0;
        c += pw.chars().anyMatch(Character::isLowerCase) ? 1 : 0;
        c += pw.chars().anyMatch(Character::isUpperCase) ? 1 : 0;
        c += pw.chars().anyMatch(Character::isDigit) ? 1 : 0;
        c += pw.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch)) ? 1 : 0;
        return c;
    }

    private static Set<String> load() {
        Set<String> set = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("security/common-passwords.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty() && !s.startsWith("#")) {
                    set.add(s);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("비밀번호 차단 목록을 읽지 못했습니다", e);
        }
        return Set.copyOf(set);
    }
}
