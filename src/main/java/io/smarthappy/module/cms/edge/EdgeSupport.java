package io.smarthappy.module.cms.edge;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

import io.smarthappy.module.pop.TerminalPrincipal;
import tools.jackson.databind.JsonNode;

/** 장비 연동 E0 도우미 — 본문 칸 읽기·정규화 */
final class EdgeSupport {

    static final Pattern EQUIP_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,29}$");
    static final Pattern DECIMAL = Pattern.compile("^-?\\d{1,12}(\\.\\d{1,6})?$");

    private EdgeSupport() {
    }

    /** 장치 행위자 = 수집 장치와 같은 표기(d{id}:{코드}) */
    static String actor(TerminalPrincipal d) {
        return "d" + d.terminalId() + ":" + d.terminalCd();
    }

    static String text(JsonNode n, String field, int max) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText().strip();
        return s.isEmpty() ? null : s.length() > max ? s.substring(0, max) : s;
    }

    static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    static Boolean bool(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        String s = v.asText().strip().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") ? Boolean.TRUE : s.equals("false") || s.equals("0") ? Boolean.FALSE : null;
    }

    /**
     * 태그 형식에 맞춘 정규 값 — NUM 은 1.50 → "1.5", BOOL 은 true/false. 형식이 틀리면 null.
     * 의도 값과 다시 읽은 값을 같은 함수로 맞춰 비교한다
     */
    static String normalize(String dataType, String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if ("BOOL".equals(dataType)) {
            String b = s.toLowerCase(Locale.ROOT);
            return b.equals("true") || b.equals("1") ? "true" : b.equals("false") || b.equals("0") ? "false" : null;
        }
        if (!DECIMAL.matcher(s).matches()) {
            return null;
        }
        String p = new BigDecimal(s).stripTrailingZeros().toPlainString();
        return p.equals("-0") ? "0" : p;
    }

    static String like(String s) {
        return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
