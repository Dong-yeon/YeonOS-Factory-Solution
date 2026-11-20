package io.smarthappy.module.wms.scan;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스캔 키 규칙 (docs/30 W1, 결정 D7) — 바코드 글자 → 대상 유형·키. **순수 함수**: DB 를 보지 않는다(존재 확인은 부른 서비스가).
 *
 * <ul>
 * <li>내부 형식: {@code LOC:{공장}:{코드}} · {@code ITEM:{품번}} · {@code LOT:{LOT번호}} · {@code PLT:{파렛트번호}} (LOT·PLT 는 2부가 쓴다)
 *     · {@code ASN:{ASN번호}}(SRM 납품서 바코드 — 2부 R3b, SRM AsnQuery.SCAN_PREFIX 와 같다)</li>
 * <li>GS1-128 / GS1 DataMatrix: 심볼 식별자({@code ]C1 ]d2 ]Q3})나 GS(0x1D) 구분자가 있거나, 사람이 읽는 괄호 형식 {@code (01)…(10)…}
 *     일 때만 GS1 로 본다 — 숫자만 있는 로케이션 코드를 GS1 로 잘못 읽지 않게. 지원 AI: 00 01 02 10 11 15 17 21 30 37 310n
 *     (310n·15 는 docs/65 18.5). 스캐너가 GS 를 글자 {@code <GS>}·{@code {GS}} 로 바꿔 보내면 GS 로 되돌리고 경고를 남긴다</li>
 * <li>한 박스의 바코드 두 개(식별용 + 중량·기한·LOT)는 {@link #combine} 으로 합친다 — 같은 AI 값이 다르면 다른 박스 바코드가 섞인 것</li>
 * <li>접두어 없는 코드: 정책 {@code WMS_SCAN_BARE_AS}(LOC/ITEM/NONE)에 따라</li>
 * </ul>
 */
public final class ScanKeyResolver {

    public static final int MAX_LEN = 512;
    static final char GS = '\u001d';

    /** P1 장비 계층 노드 코드·품번과 같은 형식(V020·V013) — 대문자로 바꾼 뒤 검사 */
    static final Pattern NODE_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,29}$");
    static final Pattern PLANT_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    static final Pattern ITEM_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._/-]{0,39}$");
    /** LOT·파렛트 번호 규칙은 P2(채번 패턴)가 정한다 — 여기서는 글자 종류·길이만, 대소문자 유지 */
    static final Pattern LOT_NO = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/#-]{0,59}$");
    private static final Pattern INTERNAL = Pattern.compile("^(LOC|ITEM|LOT|PLT|ASN):(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAREN_AI = Pattern.compile("\\((\\d{2,4})\\)([^()]*)");

    public enum Type {
        LOC, ITEM, LOT, PLT, ASN, GS1, UNKNOWN
    }

    public enum BareAs {
        LOC, ITEM, NONE
    }

    /** UNKNOWN 의 이유 — 화면 문구 키 wmsScan.reason.{이름} */
    public enum Reason {
        EMPTY, TOO_LONG, BAD_CHAR, FORMAT, OTHER_PLANT, NO_PLANT, BARE_NOT_ALLOWED,
        GS1_UNKNOWN_AI, GS1_LENGTH, GS1_CHECK_DIGIT, GS1_DATE, GS1_DUPLICATE_AI, GS1_NUMERIC,
        /** 가변 AI 값이 너무 길고 끝에 다른 AI 모양이 붙어 있다 — 스캐너가 FNC1(GS)을 빼먹었다 (docs/65 18.5) */
        GS1_FNC1_MISSING,
        /** 두 바코드 결합: 같은 AI 의 값이 다르다(다른 박스 바코드가 섞임) */
        GS1_MISMATCH,
        /** 두 바코드 결합: 둘 다 GS1 이 아니다 */
        GS1_NOT_GS1
    }

    /** GS1 해석 경고(결과는 OK) — 화면 문구 키 wmsScan.warn.{이름} */
    public static final String WARN_FNC1_SUBSTITUTED = "FNC1_SUBSTITUTED";
    /** 마지막 가변 AI 값 끝이 다른 AI 모양 — GS 를 빼먹었을 수 있다(길이는 맞아 받는다) */
    public static final String WARN_FNC1_MISSING_SUSPECT = "FNC1_MISSING_SUSPECT";

    /**
     * @param plantCd 지금 공장(단말·화면). 내부 형식 LOC 의 공장이 다르면 OTHER_PLANT. 접두어 없는 LOC 는 이 공장으로 본다
     * @param today   GS1 날짜(YY)의 세기 결정 기준(GS1 일반 규격 7.12 — 오늘 기준 -49년 ~ +50년)
     */
    public record Context(String plantCd, BareAs bareAs, LocalDate today) {
    }

    /**
     * GS1 에서 읽은 값 — 없는 AI 는 null.
     *
     * @param netWeightKg AI 310n(순중량 kg, n = 소수 자리)
     * @param bestBefore  AI 15(소비기한)
     * @param warnings    해석은 됐지만 알릴 것(FNC1 글자 대체 등)
     */
    public record Gs1(String sscc, String gtin, String batch, LocalDate expiry, LocalDate prodDate, String serial,
            BigDecimal qty, Map<String, String> raw, BigDecimal netWeightKg, LocalDate bestBefore, List<String> warnings) {
    }

    /**
     * @param type  판별 유형
     * @param plantCd LOC 일 때 공장
     * @param key   LOC = 로케이션 코드, ITEM = 품번, LOT/PLT/ASN = 번호, GS1 = GTIN(없으면 SSCC)
     * @param gs1   GS1 일 때 읽은 값
     * @param bare  접두어 없이 정책으로 읽었나
     */
    public record Result(Type type, String plantCd, String key, Gs1 gs1, boolean bare, Reason reason, String detail) {

        static Result unknown(Reason reason, String detail) {
            return new Result(Type.UNKNOWN, null, null, null, false, reason, detail);
        }

        public boolean ok() {
            return type != Type.UNKNOWN;
        }
    }

    private ScanKeyResolver() {
    }

    public static Result resolve(String text, Context ctx) {
        if (text == null) {
            return Result.unknown(Reason.EMPTY, null);
        }
        // 키보드 웨지가 붙이는 줄바꿈·탭·앞뒤 공백만 걷어 낸다(GS 는 남긴다)
        String s = stripEdges(text);
        if (s.isEmpty()) {
            return Result.unknown(Reason.EMPTY, null);
        }
        if (s.length() > MAX_LEN) {
            return Result.unknown(Reason.TOO_LONG, null);
        }
        List<String> warns = new ArrayList<>();
        String sub = substituteGs(s);
        if (!sub.equals(s)) {
            s = sub;
            warns.add(WARN_FNC1_SUBSTITUTED);
        }
        boolean symbology = false;
        if (s.startsWith("]C1") || s.startsWith("]d2") || s.startsWith("]Q3") || s.startsWith("]e0")) {
            s = s.substring(3);
            symbology = true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 0x20 && c != GS) || c == 0x7f) {
                return Result.unknown(Reason.BAD_CHAR, "0x" + Integer.toHexString(c));
            }
        }
        if (symbology || s.indexOf(GS) >= 0) {
            return withWarnings(parseGs1Raw(s, ctx), warns);
        }
        if (s.startsWith("(")) {
            return withWarnings(parseGs1Paren(s, ctx), warns);
        }
        Matcher m = INTERNAL.matcher(s);
        if (m.matches()) {
            return internal(m.group(1).toUpperCase(Locale.ROOT), m.group(2), ctx);
        }
        return bare(s, ctx);
    }

    // ------------------------------------------------------------------ 내부 형식

    private static Result internal(String type, String body, Context ctx) {
        switch (type) {
            case "LOC": {
                int p = body.indexOf(':');
                if (p < 0) {
                    return Result.unknown(Reason.FORMAT, "LOC:{plant}:{code}");
                }
                String plant = body.substring(0, p).strip().toUpperCase(Locale.ROOT);
                String code = body.substring(p + 1).strip().toUpperCase(Locale.ROOT);
                if (!PLANT_CD.matcher(plant).matches() || !NODE_CD.matcher(code).matches()) {
                    return Result.unknown(Reason.FORMAT, null);
                }
                if (ctx != null && ctx.plantCd() != null && !ctx.plantCd().equalsIgnoreCase(plant)) {
                    return Result.unknown(Reason.OTHER_PLANT, plant);
                }
                return new Result(Type.LOC, plant, code, null, false, null, null);
            }
            case "ITEM": {
                String code = body.strip().toUpperCase(Locale.ROOT);
                return ITEM_CD.matcher(code).matches() ? new Result(Type.ITEM, null, code, null, false, null, null)
                        : Result.unknown(Reason.FORMAT, null);
            }
            default: {
                String no = body.strip();
                return LOT_NO.matcher(no).matches() ? new Result(Type.valueOf(type), null, no, null, false, null, null)
                        : Result.unknown(Reason.FORMAT, null);
            }
        }
    }

    private static Result bare(String s, Context ctx) {
        BareAs as = ctx == null || ctx.bareAs() == null ? BareAs.LOC : ctx.bareAs();
        String code = s.toUpperCase(Locale.ROOT);
        switch (as) {
            case LOC:
                if (ctx == null || ctx.plantCd() == null) {
                    return Result.unknown(Reason.NO_PLANT, null);
                }
                return NODE_CD.matcher(code).matches()
                        ? new Result(Type.LOC, ctx.plantCd().toUpperCase(Locale.ROOT), code, null, true, null, null)
                        : Result.unknown(Reason.FORMAT, null);
            case ITEM:
                return ITEM_CD.matcher(code).matches() ? new Result(Type.ITEM, null, code, null, true, null, null)
                        : Result.unknown(Reason.FORMAT, null);
            default:
                return Result.unknown(Reason.BARE_NOT_ALLOWED, null);
        }
    }

    // ------------------------------------------------------------------ GS1

    /** AI → 고정 길이(값 부분). 0 = 가변(최대 길이는 VAR_MAX) */
    private static final Map<String, Integer> FIXED = Map.ofEntries(Map.entry("00", 18), Map.entry("01", 14), Map.entry("02", 14),
            Map.entry("11", 6), Map.entry("15", 6), Map.entry("17", 6),
            Map.entry("3100", 6), Map.entry("3101", 6), Map.entry("3102", 6), Map.entry("3103", 6), Map.entry("3104", 6),
            Map.entry("3105", 6));
    private static final Map<String, Integer> VAR_MAX = Map.of("10", 20, "21", 20, "30", 8, "37", 8);
    /** 가변 값 끝에 붙어 있으면 FNC1 을 빼먹은 것으로 의심하는 고정 AI 모양 */
    private static final Pattern FNC1_SUSPECT = Pattern.compile(".+?((?:1[157]\\d{6})|(?:310[0-5]\\d{6})|(?:0[012]\\d{14}))$");

    /** 지원 AI 의 고정 길이(값 부분) — 가변이거나 모르면 null. 라벨 GS1 인코더(docs/65 18.5)가 같은 표를 쓴다 */
    public static Integer gs1FixedLength(String ai) {
        return FIXED.get(ai);
    }

    /** 지원 가변 AI 의 최대 길이 — 고정이거나 모르면 null */
    public static Integer gs1VarMax(String ai) {
        return VAR_MAX.get(ai);
    }

    /** 숫자만 받는 AI 인가 */
    public static boolean gs1Numeric(String ai) {
        return FIXED.containsKey(ai) || "30".equals(ai) || "37".equals(ai);
    }

    /** GS1 mod-10 검사 숫자가 맞나(GTIN·SSCC) — 라벨 GS1 인코더가 쓴다 */
    public static boolean gs1CheckDigit(String digits) {
        return digits != null && !digits.isEmpty() && digits.chars().allMatch(c -> c >= '0' && c <= '9') && checkDigitOk(digits);
    }

    /** 스캐너가 GS 를 글자로 바꿔 보낸 것({@code <GS>}·{@code {GS}}, 대소문자 무시)을 GS 로 */
    static String substituteGs(String s) {
        if (s.indexOf('<') < 0 && s.indexOf('{') < 0) {
            return s;
        }
        return s.replaceAll("(?i)<GS>|\\{GS}", String.valueOf(GS));
    }

    private static Result withWarnings(Result r, List<String> warns) {
        if (warns.isEmpty() || r.gs1() == null) {
            return r;
        }
        Gs1 g = r.gs1();
        List<String> all = new ArrayList<>(g.warnings());
        for (String w : warns) {
            if (!all.contains(w)) {
                all.add(w);
            }
        }
        Gs1 n = new Gs1(g.sscc(), g.gtin(), g.batch(), g.expiry(), g.prodDate(), g.serial(), g.qty(), g.raw(), g.netWeightKg(),
                g.bestBefore(), List.copyOf(all));
        return new Result(r.type(), r.plantCd(), r.key(), n, r.bare(), r.reason(), r.detail());
    }

    /**
     * 한 박스의 바코드 두 개를 합친다(docs/65 6.6) — 둘 다 GS1 이어야 하고, 같은 AI 의 값이 다르면 {@code GS1_MISMATCH}
     * (다음 박스 바코드가 섞임 — 조용히 틀린 입고를 막는다). 같은 AI·같은 값은 하나로.
     */
    public static Result combine(Result a, Result b, Context ctx) {
        if (a == null || b == null || a.type() != Type.GS1 || b.type() != Type.GS1 || a.gs1() == null || b.gs1() == null) {
            return Result.unknown(Reason.GS1_NOT_GS1, null);
        }
        Map<String, String> all = new LinkedHashMap<>(a.gs1().raw());
        for (Map.Entry<String, String> e : b.gs1().raw().entrySet()) {
            String prev = all.putIfAbsent(e.getKey(), e.getValue());
            if (prev != null && !prev.equals(e.getValue())) {
                return Result.unknown(Reason.GS1_MISMATCH, e.getKey());
            }
        }
        List<String> warns = new ArrayList<>(a.gs1().warnings());
        for (String w : b.gs1().warnings()) {
            if (!warns.contains(w)) {
                warns.add(w);
            }
        }
        return withWarnings(gs1(all, ctx), warns);
    }

    private static Result parseGs1Raw(String s, Context ctx) {
        Map<String, String> ais = new LinkedHashMap<>();
        List<String> warns = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == GS) {        // 연속·앞쪽 GS 는 무시
                i++;
                continue;
            }
            if (i + 2 > s.length()) {
                return Result.unknown(Reason.GS1_LENGTH, s.substring(i));
            }
            String ai = s.substring(i, i + 2);
            i += 2;
            if ("31".equals(ai)) {                 // 310n — AI 가 4자리
                if (i + 2 > s.length()) {
                    return Result.unknown(Reason.GS1_LENGTH, ai);
                }
                ai = ai + s.substring(i, i + 2);
                i += 2;
            }
            Integer fixed = FIXED.get(ai);
            String value;
            if (fixed != null) {
                if (i + fixed > s.length()) {
                    return Result.unknown(Reason.GS1_LENGTH, ai);
                }
                value = s.substring(i, i + fixed);
                i += fixed;
                if (value.indexOf(GS) >= 0) {
                    return Result.unknown(Reason.GS1_LENGTH, ai);
                }
            } else if (VAR_MAX.containsKey(ai)) {
                int end = s.indexOf(GS, i);
                if (end < 0) {
                    end = s.length();
                }
                value = s.substring(i, end);
                i = end;
                boolean last = end == s.length();
                if (last && FNC1_SUSPECT.matcher(value).matches()) {
                    if (value.length() > VAR_MAX.get(ai)) {
                        return Result.unknown(Reason.GS1_FNC1_MISSING, ai);      // 길이로 확실 — 거부
                    }
                    warns.add(WARN_FNC1_MISSING_SUSPECT);                       // 맞는 값일 수도 — 받되 알린다
                }
            } else {
                return Result.unknown(Reason.GS1_UNKNOWN_AI, ai);
            }
            if (ais.putIfAbsent(ai, value) != null) {
                return Result.unknown(Reason.GS1_DUPLICATE_AI, ai);
            }
        }
        return withWarnings(gs1(ais, ctx), warns);
    }

    private static Result parseGs1Paren(String s, Context ctx) {
        Map<String, String> ais = new LinkedHashMap<>();
        Matcher m = PAREN_AI.matcher(s);
        int pos = 0;
        while (m.find()) {
            if (m.start() != pos) {
                return Result.unknown(Reason.FORMAT, null);
            }
            pos = m.end();
            String ai = m.group(1);
            if (!FIXED.containsKey(ai) && !VAR_MAX.containsKey(ai)) {
                return Result.unknown(Reason.GS1_UNKNOWN_AI, ai);
            }
            if (ais.putIfAbsent(ai, m.group(2)) != null) {
                return Result.unknown(Reason.GS1_DUPLICATE_AI, ai);
            }
        }
        if (pos != s.length() || ais.isEmpty()) {
            return Result.unknown(Reason.FORMAT, null);
        }
        return gs1(ais, ctx);
    }

    private static Result gs1(Map<String, String> ais, Context ctx) {
        if (ais.isEmpty()) {
            return Result.unknown(Reason.EMPTY, null);
        }
        for (Map.Entry<String, String> e : ais.entrySet()) {
            String ai = e.getKey();
            String v = e.getValue();
            Integer fixed = FIXED.get(ai);
            if (fixed != null && v.length() != fixed) {
                return Result.unknown(Reason.GS1_LENGTH, ai);
            }
            if (fixed == null && (v.isEmpty() || v.length() > VAR_MAX.get(ai))) {
                return Result.unknown(Reason.GS1_LENGTH, ai);
            }
            boolean numeric = fixed != null || "30".equals(ai) || "37".equals(ai);
            if (numeric && !v.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return Result.unknown(Reason.GS1_NUMERIC, ai);
            }
            if (!numeric && !v.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
                return Result.unknown(Reason.BAD_CHAR, ai);    // GS1 AI 82 문자 집합보다 넓지만, 공백·한글·제어문자는 거부
            }
            if (("00".equals(ai) || "01".equals(ai) || "02".equals(ai)) && !checkDigitOk(v)) {
                return Result.unknown(Reason.GS1_CHECK_DIGIT, ai);
            }
        }
        LocalDate today = ctx == null || ctx.today() == null ? null : ctx.today();
        LocalDate expiry = null;
        LocalDate prod = null;
        try {
            if (ais.containsKey("17")) {
                expiry = gs1Date(ais.get("17"), today);
            }
            if (ais.containsKey("11")) {
                prod = gs1Date(ais.get("11"), today);
            }
        } catch (DateTimeException e) {
            return Result.unknown(Reason.GS1_DATE, null);
        }
        LocalDate best = null;
        try {
            if (ais.containsKey("15")) {
                best = gs1Date(ais.get("15"), today);
            }
        } catch (DateTimeException e) {
            return Result.unknown(Reason.GS1_DATE, null);
        }
        BigDecimal net = null;
        for (int n = 0; n <= 5; n++) {
            String v = ais.get("310" + n);
            if (v != null) {
                if (net != null) {
                    return Result.unknown(Reason.GS1_DUPLICATE_AI, "310n");
                }
                net = new BigDecimal(v).movePointLeft(n);
            }
        }
        if (ais.containsKey("30") && ais.containsKey("37") && !ais.get("30").equals(ais.get("37"))) {
            return Result.unknown(Reason.GS1_DUPLICATE_AI, "30/37");
        }
        String qtyText = ais.containsKey("37") ? ais.get("37") : ais.get("30");
        BigDecimal qty = qtyText == null ? null : new BigDecimal(qtyText);
        String gtin = ais.containsKey("01") ? ais.get("01") : ais.get("02");
        Gs1 g = new Gs1(ais.get("00"), gtin, ais.get("10"), expiry, prod, ais.get("21"), qty,
                Collections.unmodifiableMap(new LinkedHashMap<>(ais)), net, best, List.of());
        return new Result(Type.GS1, null, gtin != null ? gtin : ais.get("00"), g, false, null, null);
    }

    /** GS1 mod-10 검사 숫자 (GTIN-14·SSCC-18 공통: 오른쪽에서 두 번째 자리부터 3,1,3,1…) */
    static boolean checkDigitOk(String digits) {
        int sum = 0;
        int n = digits.length();
        for (int i = n - 2, w = 3; i >= 0; i--, w = 4 - w) {
            sum += (digits.charAt(i) - '0') * w;
        }
        return (10 - sum % 10) % 10 == digits.charAt(n - 1) - '0';
    }

    /**
     * YYMMDD. DD = 00 은 그 달 마지막 날(GS1 규격). 세기는 오늘 기준 -49 ~ +50 년 창(GS1 일반 규격 7.12),
     * 오늘을 모르면 2000년대로 본다.
     */
    static LocalDate gs1Date(String yymmdd, LocalDate today) {
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int mm = Integer.parseInt(yymmdd.substring(2, 4));
        int dd = Integer.parseInt(yymmdd.substring(4, 6));
        int year;
        if (today == null) {
            year = 2000 + yy;
        } else {
            int cur = today.getYear();
            int diff = yy - cur % 100;
            if (diff >= 51) {
                diff -= 100;
            } else if (diff <= -50) {
                diff += 100;
            }
            year = cur + diff;
        }
        YearMonth ym = YearMonth.of(year, mm);       // 월 범위 밖이면 DateTimeException
        return dd == 0 ? ym.atEndOfMonth() : ym.atDay(dd);
    }

    private static String stripEdges(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && isEdge(s.charAt(b))) {
            b++;
        }
        while (e > b && isEdge(s.charAt(e - 1))) {
            e--;
        }
        return s.substring(b, e);
    }

    private static boolean isEdge(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ' ' || c == '　';
    }
}
