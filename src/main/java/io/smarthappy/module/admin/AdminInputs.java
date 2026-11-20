package io.smarthappy.module.admin;

import java.util.regex.Pattern;

import io.smarthappy.platform.common.web.FieldErrors;

/** 시스템관리 화면 입력 정리 — 오류는 FieldErrors 에 모은다 */
final class AdminInputs {

    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");

    private AdminInputs() {
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 앞뒤 공백 제거, 제어 문자·길이 검사. 비어 있으면 null(필수면 REQUIRED) */
    static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (CONTROL.matcher(s).find()) {
            e.add(field, "FORMAT");
        } else if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    /** Y/N (비우면 Y) */
    static String yn(String v, String field, FieldErrors e) {
        String s = blank(v) ? "Y" : v;
        if (!s.equals("Y") && !s.equals("N")) {
            e.add(field, "CODE");
        }
        return s;
    }
}
