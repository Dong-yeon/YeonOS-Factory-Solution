package io.smarthappy.module.wms.label;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 프린터 상태 응답 해석 (docs/65 18.1) — **순수 함수**. 입력 = 프린터가 돌려준 글자(설치형 확인 작업) 또는 에이전트가 보낸 값.
 *
 * <ul>
 * <li>{@code ~HQES}: {@code ERRORS: f gggggggg gggggggg} / {@code WARNINGS: …} — f = 있음 표시, 뒤 두 묶음 = 16진 비트(앞 = 높은 32비트)</li>
 * <li>SGD {@code getvar "odometer.total_label_count"}: 따옴표 안 숫자</li>
 * </ul>
 * 비트 이름은 Zebra ZPL 문서(~HQ) 기준. 모르는 비트는 {@code ERR_x{16진}}/{@code WARN_x{16진}} 그대로 남긴다(버리지 않는다).
 */
public final class ZebraStatus {

    static final String[][] ERROR_BITS = {
            {"1", "MEDIA_OUT"}, {"2", "RIBBON_OUT"}, {"4", "HEAD_OPEN"}, {"8", "CUTTER_FAULT"},
            {"10", "HEAD_OVER_TEMP"}, {"20", "MOTOR_OVER_TEMP"}, {"40", "BAD_HEAD_ELEMENT"}, {"80", "HEAD_DETECT"}};
    static final String[][] WARNING_BITS = {
            {"1", "CALIBRATE"}, {"2", "CLEAN_HEAD"}, {"4", "REPLACE_HEAD"}, {"8", "PAPER_NEAR_END"}};

    private static final Pattern LINE = Pattern.compile("(ERRORS|WARNINGS):\\s*([01])\\s+([0-9A-Fa-f]{8})\\s+([0-9A-Fa-f]{8})");
    private static final Pattern ODOMETER = Pattern.compile("\"(\\d{1,15})\"");

    /** 상태 확인 한 번의 결과 */
    public record Probe(boolean reachable, List<String> errors, List<String> warnings, Long odometer, String detail) {

        public static Probe offline(String detail) {
            return new Probe(false, List.of(), List.of(), null, detail);
        }

        /** OFFLINE(연결 안 됨) > ERROR > WARN > OK, 아무것도 못 읽었으면 UNKNOWN */
        public String state() {
            if (!reachable) {
                return "OFFLINE";
            }
            if (!errors.isEmpty()) {
                return "ERROR";
            }
            if (!warnings.isEmpty()) {
                return "WARN";
            }
            return odometer == null && detail != null ? "UNKNOWN" : "OK";
        }
    }

    private ZebraStatus() {
    }

    /** ~HQES 와 odometer 응답이 섞인 글자 하나를 해석한다. 둘 다 못 찾으면 detail = "NO_STATUS" */
    public static Probe parse(String text) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean sawStatus = false;
        if (text != null) {
            Matcher m = LINE.matcher(text);
            while (m.find()) {
                sawStatus = true;
                long bits = (Long.parseLong(m.group(3), 16) << 32) | Long.parseLong(m.group(4), 16);
                boolean err = "ERRORS".equals(m.group(1));
                if (!"1".equals(m.group(2)) && bits == 0) {
                    continue;
                }
                (err ? errors : warnings).addAll(names(bits, err ? ERROR_BITS : WARNING_BITS, err ? "ERR_x" : "WARN_x"));
            }
        }
        Long odo = null;
        if (text != null) {
            Matcher o = ODOMETER.matcher(text);
            if (o.find()) {
                odo = Long.parseLong(o.group(1));
            }
        }
        return new Probe(true, List.copyOf(errors), List.copyOf(warnings), odo, sawStatus || odo != null ? null : "NO_STATUS");
    }

    /** 에이전트가 보낸 코드 목록 정리 — 형식 밖 코드는 버린다(사건 사유 형식과 같게) */
    public static List<String> cleanCodes(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        for (String s : in) {
            if (s == null) {
                continue;
            }
            String c = s.strip().toUpperCase(Locale.ROOT);
            if (c.matches("^[A-Z][A-Z0-9_]{0,29}$") && !out.contains(c) && out.size() < 10) {
                out.add(c);
            }
        }
        return out;
    }

    static List<String> names(long bits, String[][] table, String unknownPrefix) {
        List<String> out = new ArrayList<>();
        long left = bits;
        for (String[] b : table) {
            long v = Long.parseLong(b[0], 16);
            if ((bits & v) != 0) {
                out.add(b[1]);
                left &= ~v;
            }
        }
        for (int i = 0; i < 64 && left != 0; i++) {
            long v = 1L << i;
            if ((left & v) != 0) {
                out.add(unknownPrefix + Long.toHexString(v).toUpperCase(Locale.ROOT));
                left &= ~v;
            }
        }
        return out;
    }

    /** 코드 목록 → 저장 글자(쉼표, 200자 안) */
    static String join(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        String s = String.join(",", codes);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
