package io.smarthappy.platform.numbering;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채번 패턴. 예: "WO-{yyMM}-{seq:4}" → WO-2610-0001
 *
 * 토큰: {yyyy} {yy} {MM} {dd} {yyMM} {yyMMdd} {yyyyMMdd} {plant} {seq:N}(N = 1~12, 정확히 1개)
 * 토큰 밖 글자: 영문 대문자·숫자·- _ / . 만 (번호가 URL·파일명·바코드에 쓰이므로)
 * 초기화 주기와 날짜 토큰이 맞아야 한다 — 예: MONTH 초기화인데 패턴에 월이 없으면 매달 같은 번호가 다시 나온다.
 */
public final class NumberingPattern {

    private static final Pattern TOKEN = Pattern.compile("\\{(yyyyMMdd|yyMMdd|yyMM|yyyy|yy|MM|dd|plant|seq:(\\d{1,2}))}");
    private static final Pattern LITERAL = Pattern.compile("^[A-Z0-9_./-]*$");
    private static final int MAX_LENGTH = 50;

    private final List<Object> parts; // String(리터럴) 또는 Token
    private final int seqLength;
    private final boolean hasYear;
    private final boolean hasMonth;
    private final boolean hasDay;
    private final boolean hasPlant;

    private record Token(String name) {
    }

    private NumberingPattern(List<Object> parts, int seqLength, boolean y, boolean m, boolean d, boolean plant) {
        this.parts = parts;
        this.seqLength = seqLength;
        this.hasYear = y;
        this.hasMonth = m;
        this.hasDay = d;
        this.hasPlant = plant;
    }

    /** 형식이 틀리면 empty */
    public static Optional<NumberingPattern> parse(String pattern, String resetCycle) {
        if (pattern == null || pattern.isEmpty() || pattern.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        List<Object> parts = new ArrayList<>();
        Matcher m = TOKEN.matcher(pattern);
        int pos = 0;
        int seqCount = 0;
        int seqLen = 0;
        boolean y = false;
        boolean mo = false;
        boolean d = false;
        boolean plant = false;
        while (m.find()) {
            String literal = pattern.substring(pos, m.start());
            if (!LITERAL.matcher(literal).matches()) {
                return Optional.empty();
            }
            if (!literal.isEmpty()) {
                parts.add(literal);
            }
            String name = m.group(1);
            if (name.startsWith("seq:")) {
                seqLen = Integer.parseInt(m.group(2));
                if (seqLen < 1 || seqLen > 12) {
                    return Optional.empty();
                }
                seqCount++;
                parts.add(new Token("seq"));
            } else {
                y |= name.contains("yy");
                mo |= name.contains("MM");
                d |= name.contains("dd");
                plant |= name.equals("plant");
                parts.add(new Token(name));
            }
            pos = m.end();
        }
        String tail = pattern.substring(pos);
        if (!LITERAL.matcher(tail).matches() || tail.contains("{") || tail.contains("}")) {
            return Optional.empty();
        }
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        if (seqCount != 1) {
            return Optional.empty();
        }
        boolean consistent = switch (resetCycle == null ? "" : resetCycle) {
            case "NONE" -> true;
            case "YEAR" -> y;
            case "MONTH" -> y && mo;
            case "DAY" -> y && mo && d;
            default -> false;
        };
        return consistent ? Optional.of(new NumberingPattern(parts, seqLen, y, mo, d, plant)) : Optional.empty();
    }

    public int seqLength() {
        return seqLength;
    }

    public boolean needsPlant() {
        return hasPlant;
    }

    /** 이 순번이 자릿수를 넘으면 true (예: {seq:4} 에 10000) */
    public boolean overflows(long seq) {
        return Long.toString(seq).length() > seqLength;
    }

    public String format(LocalDate date, String plantCd, long seq) {
        StringBuilder b = new StringBuilder();
        for (Object p : parts) {
            if (p instanceof String s) {
                b.append(s);
                continue;
            }
            switch (((Token) p).name()) {
                case "yyyy" -> b.append(String.format("%04d", date.getYear()));
                case "yy" -> b.append(String.format("%02d", date.getYear() % 100));
                case "MM" -> b.append(String.format("%02d", date.getMonthValue()));
                case "dd" -> b.append(String.format("%02d", date.getDayOfMonth()));
                case "yyMM" -> b.append(String.format("%02d%02d", date.getYear() % 100, date.getMonthValue()));
                case "yyMMdd" -> b.append(String.format("%02d%02d%02d", date.getYear() % 100, date.getMonthValue(),
                        date.getDayOfMonth()));
                case "yyyyMMdd" -> b.append(String.format("%04d%02d%02d", date.getYear(), date.getMonthValue(),
                        date.getDayOfMonth()));
                case "plant" -> b.append(plantCd);
                case "seq" -> b.append(String.format("%0" + seqLength + "d", seq));
                default -> throw new IllegalStateException(((Token) p).name());
            }
        }
        return b.toString();
    }

    /** 초기화 주기별 시퀀스 키 */
    public static String periodKey(String resetCycle, LocalDate date) {
        return switch (resetCycle) {
            case "YEAR" -> String.format("%04d", date.getYear());
            case "MONTH" -> String.format("%04d%02d", date.getYear(), date.getMonthValue());
            case "DAY" -> String.format("%04d%02d%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
            default -> "-";
        };
    }
}
