package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.smarthappy.module.qms.QmsLinkMapper.NcrRow;
import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.platform.time.BizClock;

/**
 * 계약 {@link SupplierQualityQuery} 구현 (docs/29_P3 16장) — 매퍼만, 트랜잭션을 따로 열지 않는다(부르는 쪽 트랜잭션에서 읽기, 예외 없음).
 * 회사 범위는 RLS 라 다른 회사 LOT·검사·NCR 은 보이지 않는다(빈 값). 기간 날짜는 회사 시간대(BizClock)로 시각 구간 [시작, 끝 다음 날) 이 된다.
 */
@Component
public class QmsSupplierQualityQuery implements SupplierQualityQuery {

    private final QmsLinkMapper mapper;
    private final BizClock clock;

    public QmsSupplierQualityQuery(QmsLinkMapper mapper, BizClock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public List<ItemQuality> receiptQuality(long custId, LocalDate from, LocalDate to) {
        if (!valid(from, to)) {
            return List.of();
        }
        return mapper.receiptQuality(custId, start(from), start(to.plusDays(1))).stream()
                .map(r -> new ItemQuality(r.getItemId(), r.getItemCd(), r.getUnitCd(), r.getInspectedQty(), r.getRejectedQty(), r.getInspectionCnt(),
                        r.getNgCnt(), r.getOpenNcrCnt(), r.getOtherNcQty()))
                .toList();
    }

    @Override
    public ReturnPage returnsToSupplier(long custId, LocalDate from, LocalDate to) {
        if (!valid(from, to)) {
            return ReturnPage.EMPTY;
        }
        // 하나 더 읽어 잘렸는지 안다
        List<SupplierReturn> rows = mapper.supplierReturns(custId, start(from), start(to.plusDays(1)), MAX_ROWS + 1).stream()
                .map(r -> new SupplierReturn(r.getNcrId(), r.getNcrNo(), r.getLineNo(), r.getLotId(), r.getLotNo(), r.getSupplierLotNo(), r.getItemId(),
                        r.getItemCd(), r.getQty(), r.getUnitCd(), r.getDefectCd(), r.getDefectNm(), r.getClosedAt(), null))
                .toList();
        return rows.size() > MAX_ROWS ? new ReturnPage(rows.subList(0, MAX_ROWS), true) : new ReturnPage(rows, false);
    }

    @Override
    public Optional<NcrDetail> ncr(long ncrId) {
        return Optional.ofNullable(mapper.ncrDetail(ncrId)).map(QmsSupplierQualityQuery::view);
    }

    @Override
    public NcrPage ncrsOfSupplier(long custId, LocalDate from, LocalDate to) {
        if (!valid(from, to)) {
            return NcrPage.EMPTY;
        }
        List<NcrDetail> rows = mapper.supplierNcrs(custId, start(from), start(to.plusDays(1)), MAX_ROWS + 1).stream()
                .map(QmsSupplierQualityQuery::view).toList();
        return rows.size() > MAX_ROWS ? new NcrPage(rows.subList(0, MAX_ROWS), true) : new NcrPage(rows, false);
    }

    private static NcrDetail view(NcrRow r) {
        return new NcrDetail(r.getNcrId(), r.getNcrNo(), r.getStatus(), r.getSource(), r.getLotId(), r.getLotNo(), r.getSupplierLotNo(),
                r.getSupplierCustId(), r.getItemId(), r.getItemCd(), r.getNcQty(), r.getReturnQty(), r.getUnitCd(), r.getDefectCd(), r.getDefectNm(),
                r.getDescription(), r.getInspNo(), r.getOccurredAt(), r.getClosedAt(), null);
    }

    /**
     * 양끝 포함, 1년 넘는 구간은 받지 않는다(평가 주기는 분기·반기·연 — 집계 한 번의 상한). 연도 1~9999 밖도 빈 값 —
     * 날짜 계산 예외(LocalDate.MAX)나 DB timestamptz 범위 밖 오류로 부르는 쪽 트랜잭션을 깨지 않게(QQ-2)
     */
    static boolean valid(LocalDate from, LocalDate to) {
        return from != null && to != null && inRange(from) && inRange(to) && !from.isAfter(to) && !to.isAfter(from.plusYears(1));
    }

    private static boolean inRange(LocalDate d) {
        return d.getYear() >= 1 && d.getYear() <= 9999;
    }

    private OffsetDateTime start(LocalDate d) {
        return d.atStartOfDay(clock.zone()).toOffsetDateTime();
    }
}
