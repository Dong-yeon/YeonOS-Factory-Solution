package io.smarthappy.module.admin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 연계 현황 상세의 payload 가림 (docs/36 19장 O4) — **화면만**, DB 원문은 그대로(재전송에 필요).
 * <ul>
 *   <li>키 이름을 낱말로 나눠(낙타 표기·밑줄·하이픈·점) 사람 정보 낱말이 하나라도 있으면(예 custNm·toAddr·phoneNo·recipientList)
 *       그 아래 값 전부를 가린다 — 배열·중첩 객체 포함. 대문자·소문자로만 붙여 쓴 키(TOADDR)는 4글자 이상 낱말로 끝나면 가린다</li>
 *   <li>키와 상관없이 메일 주소 모양의 글자는 가린다(본문·목록 속 주소)</li>
 *   <li>메일 = 앞 1글자 + {@code ***@도메인}, 그 밖 = 앞 1글자 + {@code ***}. 숫자·참거짓도 가리면 {@code ***}</li>
 * </ul>
 * 키 허용 목록이 아니라 금지 목록 + 끝맺음인 이유: 주제가 늘 때마다 허용 목록을 고치다 빠뜨리면 그대로 열린다 — 넘치게 가리는 쪽이 안전
 */
final class OutboxPayloadMask {

    static final List<String> PII_KEYS = List.of("name", "nm", "phone", "tel", "mobile", "email", "mail", "addr", "address",
            "to", "cc", "bcc", "recipient", "contact",
            // Web Push 구독(docs/45 9장 N9) — 기기 주소·키
            "endpoint", "p256dh", "auth");
    static final String STARS = "***";
    /** 낱말 경계: 밑줄·하이픈·점·공백, 소문자/숫자 → 대문자, 대문자 연속 → 대문자+소문자(XMLName → XML·Name) */
    private static final Pattern WORD_SPLIT = Pattern.compile("[_\\-.\\s]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@([^@\\s]+)$");
    private static final Pattern EMAIL_IN_TEXT = Pattern.compile("[^@\\s<>\"',;:()]+@[^@\\s<>\"',;:()]+\\.[A-Za-z]{2,}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OutboxPayloadMask() {
    }

    /** jsonb 글자 → 가린 트리(Map·List·값). 읽을 수 없으면 null */
    static Object mask(String json) {
        if (json == null) {
            return null;
        }
        try {
            return walk(JSON.readTree(json), false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean piiKey(String key) {
        for (String word : WORD_SPLIT.split(key)) {
            if (PII_KEYS.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        String k = key.toLowerCase(Locale.ROOT);
        for (String p : PII_KEYS) {
            if (p.length() >= 4 && k.endsWith(p)) {   // 짧은 낱말(to·cc·nm·tel)로 끝맺음까지 보면 photo·hotel 같은 일반 키가 걸린다
                return true;
            }
        }
        return false;
    }

    private static Object walk(JsonNode n, boolean masked) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Iterator<String> it = n.propertyNames().iterator(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, walk(n.get(k), masked || piiKey(k)));
            }
            return out;
        }
        if (n.isArray()) {
            List<Object> out = new ArrayList<>();
            n.forEach(c -> out.add(walk(c, masked)));
            return out;
        }
        if (n.isString()) {
            return masked ? maskText(n.asString()) : maskEmails(n.asString());
        }
        if (masked) {
            return STARS;
        }
        return n.isNumber() ? n.numberValue() : n.isBoolean() ? n.asBoolean() : n.asString();
    }

    static String maskText(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        var m = EMAIL.matcher(s.strip());
        if (m.matches()) {
            return s.strip().substring(0, 1) + STARS + "@" + m.group(1);
        }
        return s.codePoints().limit(1).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append) + STARS;
    }

    /** 키가 사람 정보가 아니어도 글자 속 메일 주소는 가린다 */
    static String maskEmails(String s) {
        return EMAIL_IN_TEXT.matcher(s).replaceAll(r -> java.util.regex.Matcher.quoteReplacement(maskText(r.group())));
    }
}
