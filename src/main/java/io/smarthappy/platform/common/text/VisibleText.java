package io.smarthappy.platform.common.text;

import io.smarthappy.platform.common.web.FieldErrors;

/**
 * 사람이 적는 필수 글 칸(사유·설명·의견)의 "보이는 글자가 있는가" 판정 — QA R3-a·R4-a, 제안 docs/qa/2026-11-02_invisible-reason-proposal.md 1단계.
 *
 * <p>{@code String.isBlank()}·{@code strip()} 은 Java 공백({@code Character.isWhitespace})만 빼서 U+200B(제로폭 공백)·U+FEFF·U+00AD 같은
 * 형식 문자와 U+00A0(NBSP) 한 글자로 필수 칸이 통과한다 → 감사·정정 기록에 빈 사유가 남는다(조용히 틀림). 판정은 여기 하나로 모은다.
 *
 * <p>보이지 않는 글자(코드 포인트 기준):
 * <ul>
 *   <li>Java 공백, 구분자 {@code \p{Z}}(NBSP·U+2000~200A·U+2028·2029·U+3000 포함), 형식 문자 {@code \p{Cf}}(U+200B~200F·U+202A~202E·
 *       U+2060~2064·U+FEFF·U+00AD·U+180E 등), 제어 문자 {@code \p{Cc}}</li>
 *   <li>한글 채움 문자 U+115F·U+1160·U+3164·U+FFA0, 점자 빈칸 U+2800 — 범주는 글자(Lo·So)지만 화면에 아무것도 그리지 않아
 *       "빈 이름" 에 흔히 쓰인다(제안 V-2 의 정의에 더한 것)</li>
 * </ul>
 * NFKC 정규화는 하지 않는다 — 사유는 사람이 읽는 글이라 원문을 바꾸지 않는다. 글 <b>가운데</b> 형식 문자(ZWJ·ZWNJ 등)는 그대로 둔다
 * (일부 문자·이모지에 필요 — 지우면 내용이 바뀐다). 앞뒤의 보이지 않는 글자만 걷어낸다.
 *
 * <p>모듈 칸 적용(2단계)은 각 모듈의 {@code blank()}·{@code text()} 를 이것으로 바꾼다 — 오류 코드는 지금과 같은 {@code REQUIRED}·
 * {@code FORMAT}·{@code TOO_LONG} 이라 화면 문구·시험 기대값이 바뀌지 않는다. "2자 이상" 규칙은 {@code minVisible = 2}(보이는 글자 수).
 */
public final class VisibleText {

    private VisibleText() {
    }

    /** 이 코드 포인트가 화면에 아무것도 그리지 않는가 */
    public static boolean invisible(int cp) {
        if (Character.isWhitespace(cp)) {
            return true;
        }
        switch (Character.getType(cp)) {
            case Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.FORMAT, Character.CONTROL -> {
                return true;
            }
            default -> {
                return cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800;
            }
        }
    }

    /** null 이거나 보이는 글자가 하나도 없으면 true — {@code s == null || s.isBlank()} 대신 */
    public static boolean isBlank(String s) {
        return visibleLength(s) == 0;
    }

    /** 보이는 글자 수(코드 포인트 기준 — 한글 한 음절·이모지 하나 = 1). null 은 0 */
    public static int visibleLength(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!invisible(cp)) {
                n++;
            }
            i += Character.charCount(cp);
        }
        return n;
    }

    /** 앞뒤의 보이지 않는 글자를 걷어낸 값. 보이는 글자가 없으면 null — {@code strip()} 대신 */
    public static String strip(String s) {
        if (s == null) {
            return null;
        }
        int from = 0;
        int to = s.length();
        while (from < to) {
            int cp = s.codePointAt(from);
            if (!invisible(cp)) {
                break;
            }
            from += Character.charCount(cp);
        }
        while (to > from) {
            int cp = s.codePointBefore(to);
            if (!invisible(cp)) {
                break;
            }
            to -= Character.charCount(cp);
        }
        return from == to ? null : s.substring(from, to);
    }

    /**
     * 필수 사유 칸 표준 검사 — 앞뒤 정리 뒤 보이는 글자가 {@code minVisible} 보다 적으면 {@code REQUIRED}, 가운데 제어 문자(탭·줄바꿈 제외)는
     * {@code FORMAT}, 코드 포인트 수가 {@code max} 를 넘으면 {@code TOO_LONG(max)}(PostgreSQL varchar(n) 도 문자 수). 오류는 {@code e} 에 모은다.
     *
     * @return 정리한 값(보이는 글자가 없으면 null) — 오류가 있어도 돌려준다(호출한 쪽이 {@code e.throwIfAny()})
     */
    public static String reason(String raw, String field, int minVisible, int max, FieldErrors e) {
        String s = strip(raw);
        if (visibleLength(s) < Math.max(1, minVisible)) {
            e.add(field, "REQUIRED");
            return s;
        }
        check(s, field, max, e);
        return s;
    }

    /** 선택 글 칸 — 보이지 않는 글자뿐이면 null(오류 없음), 그 밖은 {@link #reason} 과 같은 FORMAT·TOO_LONG 검사 */
    public static String optional(String raw, String field, int max, FieldErrors e) {
        String s = strip(raw);
        if (s != null) {
            check(s, field, max, e);
        }
        return s;
    }

    private static void check(String s, String field, int max, FieldErrors e) {
        if (hasControl(s)) {
            e.add(field, "FORMAT");
        } else if (s.codePointCount(0, s.length()) > max) {
            e.add(field, "TOO_LONG", max);
        }
    }

    /** 탭·줄바꿈(\t \n \r)을 뺀 제어 문자가 있는가 — 여러 줄 사유는 허용 */
    public static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || (c >= 0x7F && c <= 0x9F)) {
                return true;
            }
        }
        return false;
    }
}
