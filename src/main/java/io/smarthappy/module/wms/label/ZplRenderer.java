package io.smarthappy.module.wms.label;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZPL 템플릿 검사·렌더 (docs/30 W2) — **순수 함수**.
 *
 * <ul>
 * <li>자리표시자 {@code {{name}}} 는 라벨 유형별 허용 목록({@link LabelFields}) 안의 이름만, 그리고 {@code ^FD … ^FS} 안에만 둔다
 *     (명령 인자 자리에 값이 들어가면 크기·위치·명령 자체를 바꿀 수 있다)</li>
 * <li>렌더 때 값이 들어간 필드 앞에 {@code ^FH_} 를 붙이고 값의 {@code _ ^ ~} 를 {@code _5F _5E _7E} 로 바꾼다 —
 *     값으로 ZPL 명령을 끼워 넣을 수 없다(라벨 명령 주입 방지)</li>
 * <li>제어문자를 바꾸는 명령(^CC ^CT ^CD), 설정 저장·초기화(^JU ~JR), 저장 객체 삭제(^ID), ^FH 는 템플릿에 쓰지 않는다
 *     — 이스케이프 규칙이 깨지거나 프린터 설정이 바뀐다</li>
 * </ul>
 */
public final class ZplRenderer {

    public static final int MAX_BODY = 32_768;
    public static final int MAX_RENDERED = 49_152;
    public static final int MAX_VALUE = 200;

    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]{0,29})}}");
    static final List<String> FORBIDDEN = List.of("^CC", "~CC", "^CT", "~CT", "^CD", "~CD", "^JU", "~JR", "^ID", "^FH");

    /** 템플릿 문제 — 칸 오류 코드로 쓴다 */
    public enum Problem {
        EMPTY, TOO_LONG, NOT_ZPL, FORBIDDEN_CMD, UNKNOWN_FIELD, OUTSIDE_FD, BAD_PLACEHOLDER,
        /** {{gs1}} 는 제어 문자(7번째 인자)가 있는 ^BX 필드에 그것만 (docs/65 18.5) */
        GS1_FIELD
    }

    public record Issue(Problem problem, String detail) {
    }

    /** 렌더 실패 (값 문제) */
    public static final class RenderException extends RuntimeException {
        private final String code;
        private final String field;

        RenderException(String code, String field) {
            super(code + ":" + field);
            this.code = code;
            this.field = field;
        }

        public String code() {
            return code;
        }

        public String field() {
            return field;
        }
    }

    private ZplRenderer() {
    }

    /** 템플릿 검사. 문제가 없으면 빈 목록 */
    public static List<Issue> validate(String body, Set<String> allowed) {
        List<Issue> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            out.add(new Issue(Problem.EMPTY, null));
            return out;
        }
        if (body.length() > MAX_BODY) {
            out.add(new Issue(Problem.TOO_LONG, String.valueOf(MAX_BODY)));
            return out;
        }
        String t = body.strip();
        String upper = t.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("^XA") || !upper.endsWith("^XZ")) {
            out.add(new Issue(Problem.NOT_ZPL, null));
        }
        for (String f : FORBIDDEN) {
            if (upper.contains(f)) {
                out.add(new Issue(Problem.FORBIDDEN_CMD, f));
            }
        }
        List<int[]> spans = fieldSpans(body);
        Set<String> unknown = new LinkedHashSet<>();
        Set<String> outside = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(body);
        while (m.find()) {
            String name = m.group(1);
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
            if (!inside(spans, m.start(), m.end())) {
                outside.add(name);
            }
        }
        unknown.forEach(n -> out.add(new Issue(Problem.UNKNOWN_FIELD, n)));
        outside.forEach(n -> out.add(new Issue(Problem.OUTSIDE_FD, n)));
        // GS1 DataMatrix: {{gs1}} 는 ^BX(제어 문자 있음) 필드에 그것만 — ^FH 없이 인코더가 FNC1 을 넣는다
        for (int[] sp : spans) {
            String data = body.substring(sp[0], sp[1]);
            if (data.contains("{{" + GS1 + "}}")) {
                Character esc = bxEscape(body, sp[0] - 3);
                if (esc == null || esc == 0 || esc == '^' || esc == '~' || esc < 0x21 || esc > 0x7e
                        || Gs1Encoder.CSET82.matcher(String.valueOf(esc)).matches() || !data.equals("{{" + GS1 + "}}")) {
                    out.add(new Issue(Problem.GS1_FIELD, GS1));
                }
            }
        }
        // 짝이 안 맞는 {{ / }} — 오타로 자리표시자가 글자 그대로 찍히는 것을 막는다
        String stripped = PLACEHOLDER.matcher(body).replaceAll("");
        if (stripped.contains("{{") || stripped.contains("}}")) {
            out.add(new Issue(Problem.BAD_PLACEHOLDER, null));
        }
        return out;
    }

    /**
     * 렌더. 템플릿은 저장 때 {@link #validate} 를 통과한 것이어야 한다(여기서도 다시 본다).
     * 없는 값은 빈 글자, 허용 목록 밖 값 이름은 거부(부르는 쪽 버그를 숨기지 않는다).
     */
    public static String render(String body, Map<String, String> values, Set<String> allowed) {
        return render(body, values, allowed, false);
    }

    /** 배치 계산용(docs/65 18.3) — 값 앞에 \u0001칸\u0002 표시를 넣어 경고에 칸 이름을 붙인다. 프린터로 보내지 않는다 */
    public static String renderMarked(String body, Map<String, String> values, Set<String> allowed) {
        return render(body, values, allowed, true);
    }

    private static String render(String body, Map<String, String> values, Set<String> allowed, boolean marked) {
        List<Issue> issues = validate(body, allowed);
        if (!issues.isEmpty()) {
            throw new RenderException("TEMPLATE_" + issues.get(0).problem().name(), issues.get(0).detail());
        }
        Map<String, String> v = values == null ? Map.of() : values;
        for (Map.Entry<String, String> e : v.entrySet()) {
            if (!allowed.contains(e.getKey())) {
                throw new RenderException("UNKNOWN_FIELD", e.getKey());
            }
            String s = e.getValue();
            if (s != null && s.length() > MAX_VALUE) {
                throw new RenderException("VALUE_TOO_LONG", e.getKey());
            }
            if (s != null && s.codePoints().anyMatch(Character::isISOControl)) {
                throw new RenderException("VALUE_CONTROL_CHAR", e.getKey());
            }
        }
        StringBuilder out = new StringBuilder(body.length() + 64);
        int pos = 0;
        for (int[] span : fieldSpans(body)) {
            String data = body.substring(span[0], span[1]);           // ^FD 다음 ~ ^FS 앞
            if (!PLACEHOLDER.matcher(data).find()) {
                continue;
            }
            int fd = span[0] - 3;                                      // "^FD" 시작
            if (data.equals("{{" + GS1 + "}}")) {
                // GS1 DataMatrix(검사에서 ^BX + 제어 문자 확인됨) — ^FH 없이 인코더가 FNC1 을 넣는다. 값은 GS1 문자 집합만
                String val = v.get(GS1);
                String enc;
                try {
                    enc = Gs1Encoder.encode(val, bxEscape(body, fd));
                } catch (Gs1Encoder.Gs1Exception e) {
                    throw new RenderException(e.code(), GS1);
                }
                out.append(body, pos, fd).append("^FD").append(marked ? "\u0001" + GS1 + "\u0002" : "").append(enc);
                pos = span[1];
                continue;
            }
            out.append(body, pos, fd).append("^FH_^FD");
            // ^FH_ 를 붙인 필드는 템플릿 글자 쪽의 '_' 도 이스케이프 문자로 읽히므로 글자 부분도 같이 바꾼다
            Matcher m = PLACEHOLDER.matcher(data);
            int last = 0;
            while (m.find()) {
                String val = v.get(m.group(1));
                out.append(escape(data.substring(last, m.start())));
                if (marked) {
                    out.append('\u0001').append(m.group(1)).append('\u0002');
                }
                out.append(escape(val == null ? "" : val));
                last = m.end();
            }
            out.append(escape(data.substring(last)));
            pos = span[1];
        }
        out.append(body, pos, body.length());
        if (out.length() > MAX_RENDERED) {
            throw new RenderException("RENDERED_TOO_LONG", null);
        }
        return out.toString();
    }

    /** GS1 DataMatrix 칸 이름 */
    static final String GS1 = "gs1";

    /**
     * ^FD(fdStart) 가 속한 필드의 ^BX 제어 문자 — 그 필드(앞 ^FS 다음부터)에 ^BX 가 없으면 null, 있는데 7번째 인자가 없으면 0
     */
    static Character bxEscape(String body, int fdStart) {
        String upper = body.toUpperCase(Locale.ROOT);
        int fieldStart = upper.lastIndexOf("^FS", fdStart);
        fieldStart = fieldStart < 0 ? 0 : fieldStart + 3;
        int bx = upper.lastIndexOf("^BX", fdStart);
        if (bx < fieldStart) {
            return null;
        }
        int end = body.indexOf('^', bx + 3);
        String args = body.substring(bx + 3, end < 0 || end > fdStart ? fdStart : end);
        String[] p = args.split(",", -1);
        if (p.length < 7 || p[6].length() != 1) {
            return (char) 0;
        }
        return p[6].charAt(0);
    }

    /** ^FH_ 와 함께 쓰는 값 이스케이프 */
    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '_' -> b.append("_5F");
                case '^' -> b.append("_5E");
                case '~' -> b.append("_7E");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** ^FD 데이터 구간 [시작, 끝) 목록 — ^FD 바로 뒤부터 다음 ^FS 앞까지(없으면 끝까지). 대소문자 무시 */
    static List<int[]> fieldSpans(String body) {
        List<int[]> spans = new ArrayList<>();
        String upper = body.toUpperCase(Locale.ROOT);
        int i = 0;
        while (true) {
            int fd = upper.indexOf("^FD", i);
            if (fd < 0) {
                break;
            }
            int start = fd + 3;
            int fs = upper.indexOf("^FS", start);
            int end = fs < 0 ? body.length() : fs;
            spans.add(new int[] {start, end});
            i = end;
        }
        return spans;
    }

    private static boolean inside(List<int[]> spans, int s, int e) {
        for (int[] sp : spans) {
            if (s >= sp[0] && e <= sp[1]) {
                return true;
            }
        }
        return false;
    }
}
