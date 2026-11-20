package io.smarthappy.module.srm.supplier;

import java.time.LocalDate;

/**
 * 협력사 인증서 판정 (docs/31 S1) — 순수 함수. 협력사 평가(S6 CERT_VALID)와 목록 배지가 같은 함수를 쓴다.
 * 만료일 당일까지 유효, 만료일까지 남은 날이 임박 일수 이하면 DUE_SOON. 만료일이 없으면 VALID.
 */
public final class CertStatus {

    public static final String VALID = "VALID";
    public static final String DUE_SOON = "DUE_SOON";
    public static final String EXPIRED = "EXPIRED";
    /** 현재 인증서가 하나도 없음 */
    public static final String NONE = "NONE";
    /** 사용 중지된 행 */
    public static final String OFF = "OFF";

    private CertStatus() {
    }

    public static String of(LocalDate expiresOn, LocalDate today, int dueSoonDays) {
        if (expiresOn == null) {
            return VALID;
        }
        if (expiresOn.isBefore(today)) {
            return EXPIRED;
        }
        return expiresOn.isAfter(today.plusDays(dueSoonDays)) ? VALID : DUE_SOON;
    }

    /** 협력사 전체: 현재 인증서 수 + 그중 가장 이른 만료일(없으면 전부 무기한) */
    public static String ofSupplier(int currentCnt, LocalDate minExpires, LocalDate today, int dueSoonDays) {
        return currentCnt == 0 ? NONE : of(minExpires, today, dueSoonDays);
    }
}
