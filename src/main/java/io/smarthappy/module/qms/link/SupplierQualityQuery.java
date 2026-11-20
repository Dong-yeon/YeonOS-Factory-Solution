package io.smarthappy.module.qms.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 협력사 품질 조회 계약 (docs/29_P3 16장, docs/31 4.12.2) — SRM 협력사 평가(수입 PPM)·반품·클레임이 QMS 수입검사·부적합을 읽을 때(pull).
 * QMS 서비스·표를 직접 부르지 않는다. 읽기만·잠금 없음·**예외 없음**(없거나 다른 회사면 빈 값, 잘못된 인자도 빈 값 —
 * 부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다). QMS 가 없는 설치본은 {@link #NONE}.
 *
 * 협력사 = LOT 의 공급사(입고 LOT). 기간은 **회사 시간대 날짜**(양끝 포함), 1년 넘는 구간·연도 1~9999 밖은 빈 값.
 * 수량 단위 = LOT 단위(LOT 이 생길 때의 품목 기본 단위, NCR 은 LOT 단위를 복사) — 품목 + 단위로 묶는다.
 * "불량 수량" = NCR 수량 − 특채(USE_AS_IS) 처리량(NCR 마다 0 미만이 되지 않게), 취소된 NCR 은 빠진다.
 */
public interface SupplierQualityQuery {

    /** 목록 상한 */
    int MAX_ROWS = 500;

    /**
     * 품목·단위별 수입검사 품질 — 대상 = 판정 확정(무효 아님) 수입검사, LOT 마다 가장 최근 판정 하나, 판정 시각이 기간 안.
     * inspectedQty = 그 LOT 들의 LOT 수량 합, inspectionCnt·ngCnt = 판정 수·불합격 판정 수.
     * rejectedQty = 그 LOT 의 출처 INSP NCR 불량 수량 합 — **근거 검사가 다시 검사·무효됐어도 LOT 에 붙여 센다**(기준 시각 = LOT 의 살아 있는
     * 최근 판정, 모두 무효면 근거 검사의 판정 시각). 판정이 모두 무효인데 불량을 낸 LOT 도 그 LOT 수량을 inspectedQty 에 한 번 넣는다
     * (분자·분모 대상 일치 — 판정 수·NG 수에는 들지 않음).
     * openNcrCnt = 그 NCR 중 아직 처리 중(OPEN·PENDING — 처리 줄이 바뀔 수 있음)인 것.
     * otherNcQty = 같은 협력사 LOT 에 검사 없이 생긴 NCR(출처 INSP 아님, 발생 시각이 기간 안)의 불량 수량 합 — PPM 에 넣을지는 부르는 쪽이 정한다.
     * <p><b>소급</b>: "최근 판정" 기준이라 지난 기간의 LOT 을 나중에 다시 판정하면 그 기간 값이 다시 조회할 때 바뀐다 — 평가 시점 결과는 부르는 쪽(SRM)이 저장한다.
     */
    record ItemQuality(long itemId, String itemCd, String unitCd, BigDecimal inspectedQty, BigDecimal rejectedQty, int inspectionCnt, int ngCnt,
            int openNcrCnt, BigDecimal otherNcQty) {
    }

    /** 반품 줄 목록 — truncated = 상한({@link #MAX_ROWS})에서 잘렸음(합을 내면 모자란다 — 기간을 나눠 다시 부른다) */
    record ReturnPage(List<SupplierReturn> rows, boolean truncated) {
        public static final ReturnPage EMPTY = new ReturnPage(List.of(), false);
    }

    /** 닫힌 NCR 의 반품(RETURN) 처리 줄 하나 — poLineId 는 QMS 가 모른다(항상 null, SRM 이 LOT·입고로 찾는다) */
    record SupplierReturn(long ncrId, String ncrNo, int lineNo, long lotId, String lotNo, String supplierLotNo, long itemId, String itemCd,
            BigDecimal qty, String unitCd, String defectCd, String defectNm, OffsetDateTime closedAt, Long poLineId) {
    }

    /**
     * NCR 한 건 — status = OPEN·PENDING·CLOSED·CANCELLED, source = INSP·SETUP·PROCESS·CLAIM·OTHER, returnQty = 반품 처리량 합(없으면 0),
     * inspNo = 근거 검사 번호(없으면 null), occurredAt = 발견 시각, poLineId 는 항상 null
     */
    record NcrDetail(long ncrId, String ncrNo, String status, String source, long lotId, String lotNo, String supplierLotNo, Long supplierCustId,
            long itemId, String itemCd, BigDecimal ncQty, BigDecimal returnQty, String unitCd, String defectCd, String defectNm, String description,
            String inspNo, OffsetDateTime occurredAt, OffsetDateTime closedAt, Long poLineId) {

        /** 발견 시각 칸이 생기기 전 모양(occurredAt = null) — 부르는 쪽 시험 대역이 그대로 쓰게 */
        public NcrDetail(long ncrId, String ncrNo, String status, String source, long lotId, String lotNo, String supplierLotNo,
                Long supplierCustId, long itemId, String itemCd, BigDecimal ncQty, BigDecimal returnQty, String unitCd, String defectCd,
                String defectNm, String description, String inspNo, OffsetDateTime closedAt, Long poLineId) {
            this(ncrId, ncrNo, status, source, lotId, lotNo, supplierLotNo, supplierCustId, itemId, itemCd, ncQty, returnQty, unitCd, defectCd,
                    defectNm, description, inspNo, null, closedAt, poLineId);
        }
    }

    /** NCR 목록 — truncated = 상한({@link #MAX_ROWS})에서 잘렸음(기간을 나눠 다시 부른다) */
    record NcrPage(List<NcrDetail> rows, boolean truncated) {
        public static final NcrPage EMPTY = new NcrPage(List.of(), false);
    }

    /** 조회 없음 — QMS 모듈이 없는 설치본(빈 없음) */
    SupplierQualityQuery NONE = new SupplierQualityQuery() {
        @Override
        public List<ItemQuality> receiptQuality(long custId, LocalDate from, LocalDate to) {
            return List.of();
        }

        @Override
        public ReturnPage returnsToSupplier(long custId, LocalDate from, LocalDate to) {
            return ReturnPage.EMPTY;
        }

        @Override
        public Optional<NcrDetail> ncr(long ncrId) {
            return Optional.empty();
        }

        @Override
        public NcrPage ncrsOfSupplier(long custId, LocalDate from, LocalDate to) {
            return NcrPage.EMPTY;
        }
    };

    /** 협력사의 기간 수입검사 품질(품목 코드·단위 순). 없음·from &gt; to·null·1년 넘는 구간이면 빈 목록 */
    List<ItemQuality> receiptQuality(long custId, LocalDate from, LocalDate to);

    /** 협력사 LOT 의 NCR 중 종결 시각이 기간 안인 것의 반품 처리 줄(종결 시각·NCR·줄 순, 상한 {@link #MAX_ROWS} — 넘으면 truncated) */
    ReturnPage returnsToSupplier(long custId, LocalDate from, LocalDate to);

    /** NCR 한 건 — 없는·다른 회사 id 는 빈 값 */
    Optional<NcrDetail> ncr(long ncrId);

    /**
     * 협력사 LOT 의 NCR 중 **발견 시각**이 기간 안인 것(취소 제외, 상태·출처·반품 여부 무관 — 처리 중·반품 없는 NCR 도), 발견 시각·NCR 순,
     * 상한 {@link #MAX_ROWS} — 넘으면 truncated. 클레임의 근거로 고를 때
     */
    NcrPage ncrsOfSupplier(long custId, LocalDate from, LocalDate to);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static SupplierQualityQuery of(ObjectProvider<SupplierQualityQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
