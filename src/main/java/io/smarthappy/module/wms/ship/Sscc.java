package io.smarthappy.module.wms.ship;

/**
 * GS1 SSCC(Serial Shipping Container Code, AI (00)) 18자리 — 확장 자리 + GS1 업체코드 + 일련번호 + 검사 숫자 (GS1 일반 규격 2.2.1·7.9).
 * 순수 함수(B6b, docs/30 12.10). 업체코드는 회사 정책 WMS_GS1_COMPANY_PREFIX.
 */
public final class Sscc {

    private Sscc() {
    }

    /** 업체코드가 쓸 수 있는 모양인가 — 숫자 6~12자리 */
    public static boolean prefixOk(String prefix) {
        return prefix != null && prefix.matches("^[0-9]{6,12}$");
    }

    /** 일련번호에 남는 자리 수 = 17 − 확장 1 − 업체코드 */
    public static int serialDigits(String prefix) {
        return 16 - prefix.length();
    }

    /**
     * @param extension 확장 자리 0~9
     * @param serial    0 이상, 남는 자리 안
     * @return 18자리 SSCC, 일련번호가 자리를 넘으면 null
     */
    public static String build(int extension, String prefix, long serial) {
        if (extension < 0 || extension > 9 || !prefixOk(prefix) || serial < 0) {
            throw new IllegalArgumentException("SSCC 입력");
        }
        String s = Long.toString(serial);
        int width = serialDigits(prefix);
        if (s.length() > width) {
            return null;
        }
        String body = extension + prefix + "0".repeat(width - s.length()) + s;
        return body + checkDigit(body);
    }

    /** GS1 mod 10 검사 숫자 — 오른쪽 끝부터 3·1 가중(GTIN·SSCC 공통) */
    public static int checkDigit(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            sum += (i % 2 == 0) ? d * 3 : d;
        }
        return (10 - sum % 10) % 10;
    }

    /** 18자리이고 검사 숫자가 맞는가 */
    public static boolean valid(String sscc) {
        return sscc != null && sscc.matches("^[0-9]{18}$") && checkDigit(sscc.substring(0, 17)) == sscc.charAt(17) - '0';
    }
}
