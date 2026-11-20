package io.smarthappy.module.wms.label;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZPL 배치 계산 (docs/65 18.3) — **순수 함수**. 렌더된 ZPL(값이 들어간 것)에서 요소(TEXT·BOX·BARCODE·QR·MATRIX)의 점 단위
 * 위치·크기를 근사하고 넘침 경고를 낸다. 화면은 이 목록을 SVG 로 그린다(외부 렌더러에 운영 데이터를 보내지 않는다).
 *
 * 다루는 명령: ^XA ^XZ ^PW ^LL ^LH ^FO ^FT ^A ^CF ^FB ^GB ^BY ^BC ^BQ ^BX ^FD ^FS ^FH ^FR ^PQ — 그 밖은 건너뛴다.
 * 글자 폭: 한글·전각 = 글자 높이, 그 밖 = 폭 인자(없으면 높이) × 0.6. **근사값 — 최종은 실물.**
 */
public final class ZplLayout {

    /** 요소 하나 — 좌표·크기는 점(dot) */
    public record Element(String kind, int x, int y, int w, int h, String text, String field, boolean overflow) {
    }

    /** 배치 결과: 라벨 크기(점) + 요소 + 경고(코드:칸) */
    public record Layout(int widthDots, int heightDots, List<Element> elements, List<String> warnings) {
    }

    private static final Pattern CMD = Pattern.compile("[\\^~]([A-Za-z@][A-Za-z0-9@]?)");
    private static final Pattern PLACEHOLDER_MARK = Pattern.compile("\u0001([A-Za-z0-9]{1,30})\u0002");

    private ZplLayout() {
    }

    /** 템플릿 폭·높이(mm)·dpi 로 기본 크기를 잡는다. ^PW·^LL 이 있으면 그 값 */
    public static Layout layout(String zpl, BigDecimal widthMm, BigDecimal heightMm, int dpi) {
        int defW = dots(widthMm, dpi);
        int defH = dots(heightMm, dpi);
        return layout(zpl, defW, defH);
    }

    static int dots(BigDecimal mm, int dpi) {
        if (mm == null) {
            return 0;
        }
        return mm.multiply(BigDecimal.valueOf(dpi)).divide(BigDecimal.valueOf(25.4), 0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * @param zpl 렌더된 ZPL. 칸 이름을 알고 싶으면 값 앞뒤에 \u0001이름\u0002 표시를 넣은 것(경고 칸 이름용) — 표시는 글자에서 뺀다
     */
    public static Layout layout(String zpl, int defW, int defH) {
        List<Element> out = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        if (zpl == null) {
            return new Layout(defW, defH, out, warns);
        }
        int pw = defW;
        int ll = defH;
        int lhX = 0;
        int lhY = 0;
        int foX = 0;
        int foY = 0;
        boolean ft = false;
        int fontH = 9;
        int fontW = 0;
        int cfH = 9;
        int cfW = 0;
        int fbW = 0;
        int fbLines = 1;
        int byW = 2;
        String pending = null;           // 다음 ^FD 를 받을 바코드·QR 종류
        int bcH = 10;
        int qrMag = 2;
        int bxH = 10;
        Matcher m = CMD.matcher(zpl);
        int pos = 0;
        while (m.find(pos)) {
            String c = m.group(1).toUpperCase(Locale.ROOT);
            int argStart = m.end();
            int next = nextCommand(zpl, argStart);
            String args = zpl.substring(argStart, next);
            pos = next;
            if (c.startsWith("A") && c.length() == 2) {
                // ^Afo,h,w — f = 글꼴 이름 글자(c 의 두 번째 글자 또는 args 첫 글자)
                String a = c.length() == 2 ? c.substring(1) + args : args;
                String[] p = a.length() > 0 ? a.substring(1).split(",", -1) : new String[0];
                int h = num(p, 1, cfH);
                fontH = h;
                fontW = num(p, 2, 0);
                continue;
            }
            switch (c) {
                case "XA" -> {
                    foX = 0; foY = 0; fbW = 0; fbLines = 1; pending = null;
                }
                case "PW" -> pw = Math.max(1, num(args.split(",", -1), 0, pw));
                case "LL" -> ll = Math.max(1, num(args.split(",", -1), 0, ll));
                case "LH" -> {
                    String[] p = args.split(",", -1);
                    lhX = num(p, 0, 0);
                    lhY = num(p, 1, 0);
                }
                case "FO", "FT" -> {
                    String[] p = args.split(",", -1);
                    foX = lhX + num(p, 0, 0);
                    foY = lhY + num(p, 1, 0);
                    ft = "FT".equals(c);
                }
                case "CF" -> {
                    String[] p = args.split(",", -1);
                    cfH = num(p, 1, cfH);
                    cfW = num(p, 2, cfW);
                    fontH = cfH;
                    fontW = cfW;
                }
                case "FB" -> {
                    String[] p = args.split(",", -1);
                    fbW = num(p, 0, 0);
                    fbLines = Math.max(1, num(p, 1, 1));
                }
                case "BY" -> byW = Math.max(1, num(args.split(",", -1), 0, 2));
                case "BC" -> {
                    pending = "BARCODE";
                    bcH = num(args.split(",", -1), 1, 10 * byW * 5);
                }
                case "BQ" -> {
                    pending = "QR";
                    qrMag = Math.max(1, num(args.split(",", -1), 2, 2));
                }
                case "BX" -> {
                    pending = "MATRIX";
                    bxH = Math.max(1, num(args.split(",", -1), 1, 10));
                }
                case "GB" -> {
                    String[] p = args.split(",", -1);
                    int w = num(p, 0, 1);
                    int h = num(p, 1, 1);
                    int y = ft ? foY - h : foY;
                    boolean over = foX + w > pw || y + h > ll || foX < 0 || y < 0;
                    out.add(new Element("BOX", foX, y, w, h, null, null, over));
                    if (over) {
                        warns.add("OFF_LABEL:box");
                    }
                }
                case "FD" -> {
                    String raw = args;
                    String field = fieldName(raw);
                    String text = PLACEHOLDER_MARK.matcher(raw).replaceAll("");
                    String shown = unescapeFh(text);
                    if (pending == null) {
                        int w = textWidth(shown, fontH, fontW);
                        int h = fontH;
                        boolean over = false;
                        if (fbW > 0) {
                            int lines = Math.max(1, (int) Math.ceil((double) w / fbW));
                            if (lines > fbLines) {
                                over = true;
                                warns.add("FB_LINES:" + (field == null ? "text" : field));
                            }
                            w = fbW;
                            h = fontH * Math.min(lines, fbLines);
                        }
                        int y = ft ? foY - fontH : foY;
                        if (foX + w > pw) {
                            over = true;
                            warns.add("OVERFLOW_X:" + (field == null ? "text" : field));
                        }
                        if (y + h > ll || y < 0) {
                            over = true;
                            warns.add("OFF_LABEL:" + (field == null ? "text" : field));
                        }
                        out.add(new Element("TEXT", foX, y, w, h, shown, field, over));
                    } else {
                        int w;
                        int h;
                        if ("BARCODE".equals(pending)) {
                            // Code 128 근사: (문자 수 × 11 + 35) 모듈 × 좁은 막대 폭
                            w = (shown.length() * 11 + 35) * byW;
                            h = bcH;
                        } else if ("QR".equals(pending)) {
                            String d = shown.length() > 3 && shown.charAt(2) == ',' ? shown.substring(3) : shown;
                            int modules = 21 + 4 * Math.min(39, d.length() / 14);
                            w = modules * qrMag;
                            h = w;
                        } else {
                            int modules = 10 + 2 * Math.min(66, (int) Math.ceil(shown.length() / 3.0));
                            w = modules * bxH;
                            h = w;
                        }
                        int y = ft ? foY - h : foY;
                        boolean over = foX + w > pw || y + h > ll;
                        if (over) {
                            warns.add((foX + w > pw ? "OVERFLOW_X:" : "OFF_LABEL:") + (field == null ? pending.toLowerCase(Locale.ROOT) : field));
                        }
                        out.add(new Element(pending, foX, y, w, h, shown, field, over));
                    }
                    pending = null;
                    fbW = 0;
                    fbLines = 1;
                }
                default -> {
                    // 그 밖 명령은 배치에 영향 없음(근사)
                }
            }
        }
        return new Layout(pw, ll, out, warns.stream().distinct().toList());
    }

    /** 다음 ^ 또는 ~ 위치 — ^FD 데이터 안의 ^FS 까지 */
    private static int nextCommand(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '^' || c == '~') {
                return i;
            }
        }
        return s.length();
    }

    private static String fieldName(String raw) {
        Matcher m = PLACEHOLDER_MARK.matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    /** ^FH_ 16진 이스케이프(_5E 등)를 글자로 되돌린다 — 화면 표시용 */
    static String unescapeFh(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                b.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    /** 글자 폭(점) 근사 */
    static int textWidth(String s, int h, int w) {
        double total = 0;
        double narrow = (w > 0 ? w : h) * 0.6;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            total += wide(cp) ? h : narrow;
            i += Character.charCount(cp);
        }
        return (int) Math.ceil(total);
    }

    static boolean wide(int cp) {
        Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
        return sc == Character.UnicodeScript.HANGUL || sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
                || sc == Character.UnicodeScript.KATAKANA || (cp >= 0xFF01 && cp <= 0xFF60);
    }

    private static int num(String[] p, int i, int def) {
        if (p == null || i >= p.length) {
            return def;
        }
        String s = p[i].strip();
        if (s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 한 부(copy)에 나오는 라벨 장 수 = 서식(^XA 블록)마다 ^PQ 수량(없으면 1)의 합. 확인(odometer) 계산용 */
    public static int labelsPerCopy(String zpl) {
        if (zpl == null || zpl.isEmpty()) {
            return 1;
        }
        String u = zpl.toUpperCase(Locale.ROOT);
        int total = 0;
        int i = 0;
        while (true) {
            int xa = u.indexOf("^XA", i);
            if (xa < 0) {
                break;
            }
            int xz = u.indexOf("^XZ", xa);
            String block = u.substring(xa, xz < 0 ? u.length() : xz);
            int pq = block.indexOf("^PQ");
            int q = 1;
            if (pq >= 0) {
                Matcher d = Pattern.compile("^(\\d{1,8})").matcher(block.substring(pq + 3));
                if (d.find()) {
                    q = Math.max(1, Integer.parseInt(d.group(1)));
                }
            }
            total += q;
            if (xz < 0) {
                break;
            }
            i = xz + 3;
        }
        return Math.max(1, Math.min(total, 100_000));
    }
}
