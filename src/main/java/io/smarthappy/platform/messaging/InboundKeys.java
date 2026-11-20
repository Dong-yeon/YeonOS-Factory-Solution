package io.smarthappy.platform.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;

/**
 * 업무 키·내용 정규화 (docs/65 16.2) — 순수 함수. 같은 뜻이면 같은 글자가 되게:
 * 글자는 앞뒤 공백 제거, 숫자는 1.50 = 1.5 = "1.5"(글자로 온 숫자는 글자 그대로), 참·거짓은 true/false, 없음·null 은 빈 글자,
 * 배열은 순서대로, 객체는 칸 이름순.
 */
public final class InboundKeys {

    private InboundKeys() {
    }

    /** 칸 값 하나의 정규 글자 */
    public static String canonical(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "";
        }
        if (v.isTextual()) {
            return v.asText().strip();
        }
        if (v.isIntegralNumber()) {
            return v.bigIntegerValue().toString();
        }
        if (v.isNumber()) {
            String s = v.decimalValue().stripTrailingZeros().toPlainString();
            return s.equals("-0") ? "0" : s;
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? "true" : "false";
        }
        if (v.isArray()) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            v.forEach(x -> j.add(canonical(x)));
            return j.toString();
        }
        if (v.isObject()) {
            List<String> names = new ArrayList<>();
            v.propertyNames().iterator().forEachRemaining(names::add);
            names.sort(null);
            StringJoiner j = new StringJoiner(",", "{", "}");
            for (String n : names) {
                j.add(n + "=" + canonical(v.get(n)));
            }
            return j.toString();
        }
        return v.asText();
    }

    /** 칸 목록 → {@code 칸=값|칸=값} */
    public static String join(JsonNode body, List<String> fields) {
        StringJoiner j = new StringJoiner("|");
        for (String f : fields) {
            j.add(f + "=" + canonical(body == null ? null : body.get(f)));
        }
        return j.toString();
    }

    /** 칸 목록 중 값이 빈 첫 칸(없으면 null) */
    public static String firstMissing(JsonNode body, List<String> fields) {
        for (String f : fields) {
            if (canonical(body == null ? null : body.get(f)).isEmpty()) {
                return f;
            }
        }
        return null;
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
