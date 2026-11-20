package io.smarthappy.module.wms.label;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.smarthappy.module.wms.scan.ScanKeyResolver;

/**
 * GS1 요소 문자열 → ZPL {@code ^BX} GS1 DataMatrix 데이터 (docs/65 18.5) — **순수 함수**.
 *
 * 입력 = 사람이 읽는 괄호 형식 {@code (01)08801234567893(10)LOT1(17)271231}. AI 표·길이·숫자·검사 숫자는 스캔 해석
 * ({@link ScanKeyResolver})과 같은 표 — 찍은 라벨을 우리 스캐너가 다시 읽을 수 있어야 한다.
 * 출력 = 제어 문자 e 로 {@code e1}(FNC1) + AI 데이터, **가변 AI 뒤에만** {@code e1}(마지막 제외). ^FH 와 섞지 않는다 —
 * 그래서 값 글자를 GS1 문자 집합(82자)으로 묶고 제어 문자·{@code ^}·{@code ~} 는 거부한다(명령 주입 없음).
 */
public final class Gs1Encoder {

    /** GS1 일반 규격 문자 집합 82 (AI 82) */
    static final Pattern CSET82 = Pattern.compile("^[!\"%&'()*+,\\-./0-9:;<=>?A-Z_a-z]*$");
    private static final Pattern PAREN = Pattern.compile("\\((\\d{2,4})\\)([^()]*)");

    /** 인코딩 실패 — 코드는 칸 오류 코드로 쓴다 */
    public static final class Gs1Exception extends RuntimeException {
        private final String code;

        Gs1Exception(String code, String detail) {
            super(code + (detail == null ? "" : ":" + detail));
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private Gs1Encoder() {
    }

    /**
     * @param paren  괄호 형식
     * @param escape ^BX 7번째 인자(제어 문자). GS1 문자 집합 밖이어야 한다(값과 섞이지 않게)
     */
    public static String encode(String paren, char escape) {
        if (paren == null || paren.isBlank()) {
            throw new Gs1Exception("GS1_EMPTY", null);
        }
        if (escape < 0x21 || escape > 0x7e || escape == '^' || escape == '~' || CSET82.matcher(String.valueOf(escape)).matches()) {
            throw new Gs1Exception("GS1_ESCAPE", String.valueOf(escape));
        }
        String s = paren.strip();
        List<String[]> ais = new ArrayList<>();
        Matcher m = PAREN.matcher(s);
        int pos = 0;
        while (m.find()) {
            if (m.start() != pos) {
                throw new Gs1Exception("GS1_FORMAT", null);
            }
            pos = m.end();
            ais.add(new String[] {m.group(1), m.group(2)});
        }
        if (pos != s.length() || ais.isEmpty()) {
            throw new Gs1Exception("GS1_FORMAT", null);
        }
        // 스캔 해석과 같은 검사(지원 AI·길이·숫자·검사 숫자·날짜·중복)
        ScanKeyResolver.Result r = ScanKeyResolver.resolve(s, new ScanKeyResolver.Context(null, ScanKeyResolver.BareAs.NONE, null));
        if (r.type() != ScanKeyResolver.Type.GS1) {
            throw new Gs1Exception("GS1_" + (r.reason() == null ? "FORMAT" : r.reason().name().replace("GS1_", "")),
                    r.detail());
        }
        StringBuilder out = new StringBuilder();
        out.append(escape).append('1');
        for (int i = 0; i < ais.size(); i++) {
            String ai = ais.get(i)[0];
            String v = ais.get(i)[1];
            if (!CSET82.matcher(v).matches() || v.indexOf(escape) >= 0) {
                throw new Gs1Exception("VALUE_CHARSET", ai);
            }
            out.append(ai).append(v);
            boolean variable = ScanKeyResolver.gs1FixedLength(ai) == null;
            if (variable && i < ais.size() - 1) {
                out.append(escape).append('1');
            }
        }
        return out.toString();
    }
}
