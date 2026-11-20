package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 발주 공급 조회 계약 (docs/31 4.8) — APS 가 "언제 얼마가 들어오나" 를 볼 때 쓴다. SRM 표를 직접 조회하지 않는다.
 * 열린 발주(발행·회신·입고 중 상태)의 열린 행을 날짜별 공급으로 돌려준다. 같은 회사만(RLS), 부르는 쪽 트랜잭션 안에서 읽기만.
 *
 * - 남은 수량 = 수량 − 취소 수량 − 입고 수량(2부 R1 {@link PoReceiptGate} 투영). 받은 양은 이른 약속부터 채운 것으로 보고 지운다
 * - 날짜 = **ASN 도착 예정일**(확정·입고 중 ASN 남은 양, R3-6 — promised = true) → 유효 회신(지금 개정의 최신 묶음, 거절 아님)의 약속일 — 분할 약속은 약속마다 한 줄, 약속하지 않은 나머지는 요구 납기일 한 줄.
 *   유효 회신이 없거나 거절이면 요구 납기일 한 줄
 * - 수량·단위(qty·unitCd) = 품목 **지금 기본 단위**로 환산(행 단위 = 기본 단위면 그대로, 아니면 품목 단위 환산표). 환산이 없으면 행 단위 그대로
 *   {@code inBaseUnit = false} — 버리지 않고 알린다
 * - SRM 이 없는 설치본은 {@link #NONE}(빈 목록) — {@link #of(ObjectProvider)} 로 받는다
 */
public interface PoSupplyQuery {

    /**
     * 공급 한 줄.
     * @param promised   true = 회신 약속일, false = 요구 납기일(약속 없음·약속 안 한 나머지)
     * @param inBaseUnit false = 기본 단위로 환산할 수 없어 qty·unitCd 가 발주 행 단위
     * @param poUnitCd   발주 행 단위(스냅샷) — WMS 구매 입고 화면처럼 발주 단위로 입력받는 쪽용(2026-10-22 WMS 제안)
     * @param openPoQty  이 줄의 수량을 발주 단위로(환산 전) — qty 와 같은 줄, 단위만 다름
     */
    record Supply(long poId, String poNo, long poLineId, int lineNo, long custId, long itemId, LocalDate supplyDate,
            BigDecimal qty, String unitCd, boolean promised, boolean inBaseUnit, String poUnitCd, BigDecimal openPoQty) {
    }

    /** 조회 없음 — SRM 모듈이 없는 설치본(빈 없음) */
    PoSupplyQuery NONE = (plantCd, itemIds, toDate) -> List.of();

    /**
     * @param plantCd 발주 공장
     * @param itemIds 품목 — 필수(null 이면 IllegalArgumentException: 회사·공장 전체를 상한 없이 읽지 않게), 빈 목록 = 빈 결과
     * @param toDate  이 날까지의 공급(포함, null = 끝 없음) — 지난 날짜(밀린 공급)도 포함
     * @return 날짜·발주 번호·행 순
     */
    List<Supply> open(String plantCd, Collection<Long> itemIds, LocalDate toDate);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static PoSupplyQuery of(ObjectProvider<PoSupplyQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
