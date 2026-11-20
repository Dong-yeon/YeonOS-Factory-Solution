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
 * 템플릿 경고 (docs/65 18.2) — **순수 함수**. 저장을 막지 않고 알린다(실물 확인 몫).
 *
 * <ul>
 * <li>{@code CJK_FONT_MISSING:{칸}} — 한글이 들어갈 수 있는 칸이 내장 글꼴(^A0·A~H 등)로 찍힌다(^CI28 만으로는 한글이 안 나온다)</li>
 * <li>{@code CJK_FONT_ALIAS:{글꼴}} — 내장 아닌 글꼴 별칭을 쓰는데 템플릿에 ^CW 대응이 없다(프린터에 영구 대응이 없으면 기본 글꼴)</li>
 * <li>{@code CI28_MISSING} — 한글 칸이 있는데 ^CI28(UTF-8)이 없다</li>
 * </ul>
 */
public final class ZplLint {

    /** 한글이 들어갈 수 있는 칸 */
    public static final Set<String> CJK_FIELDS = Set.of("itemNm", "locNm", "custNm", "spec", "path");
    /** 내장(비트맵·확대) 글꼴 — 한글 글리프가 없다 */
    static final String BUILTIN = "ABCDEFGH0PQRSTUV";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]{0,29})}}");
    private static final Pattern FONT = Pattern.compile("\\^A([A-Z0-9@])", Pattern.CASE_INSENSITIVE);
    private static final Pattern CF = Pattern.compile("\\^CF([A-Z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern CW = Pattern.compile("\\^CW([A-Z0-9])", Pattern.CASE_INSENSITIVE);

    private ZplLint() {
    }

    public static List<String> warnings(String body) {
        Set<String> out = new LinkedHashSet<>();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String upper = body.toUpperCase(Locale.ROOT);
        Set<Character> mapped = new LinkedHashSet<>();
        Matcher cw = CW.matcher(body);
        while (cw.find()) {
            mapped.add(Character.toUpperCase(cw.group(1).charAt(0)));
        }
        boolean cjkField = false;
        for (int[] sp : ZplRenderer.fieldSpans(body)) {
            String data = body.substring(sp[0], sp[1]);
            Matcher ph = PLACEHOLDER.matcher(data);
            String field = null;
            while (ph.find()) {
                if (CJK_FIELDS.contains(ph.group(1))) {
                    field = ph.group(1);
                    break;
                }
            }
            if (field == null) {
                continue;
            }
            cjkField = true;
            char font = fontAt(body, upper, sp[0] - 3);
            if (font == '@') {
                continue;                                      // ^A@ = 글꼴 파일 직접
            }
            if (mapped.contains(font)) {
                continue;
            }
            if (BUILTIN.indexOf(font) >= 0) {
                out.add("CJK_FONT_MISSING:" + field);
            } else {
                out.add("CJK_FONT_ALIAS:" + font);
            }
        }
        if (cjkField && !upper.contains("^CI28")) {
            out.add("CI28_MISSING");
        }
        return new ArrayList<>(out);
    }

    /** ^FD 위치의 글꼴 이름 — 그 필드의 마지막 ^A, 없으면 앞쪽 마지막 ^CF, 없으면 0 */
    static char fontAt(String body, String upper, int fdStart) {
        int fieldStart = upper.lastIndexOf("^FS", fdStart);
        fieldStart = fieldStart < 0 ? 0 : fieldStart + 3;
        Character f = null;
        Matcher a = FONT.matcher(body);
        a.region(fieldStart, fdStart);
        while (a.find()) {
            f = Character.toUpperCase(a.group(1).charAt(0));
        }
        if (f != null) {
            return f;
        }
        Matcher c = CF.matcher(body);
        c.region(0, fdStart);
        while (c.find()) {
            f = Character.toUpperCase(c.group(1).charAt(0));
        }
        return f == null ? '0' : f;
    }

    /** 값에 한글(또는 한자·가나 — 내장 글꼴에 없는 글자)이 있나 — 요청 때 경고용 */
    public static boolean hasCjk(Map<String, String> values) {
        if (values == null) {
            return false;
        }
        for (String v : values.values()) {
            if (v != null && v.codePoints().anyMatch(ZplLayout::wide)) {
                return true;
            }
        }
        return false;
    }
}
