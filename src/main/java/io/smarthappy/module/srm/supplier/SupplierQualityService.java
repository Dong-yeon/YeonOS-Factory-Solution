package io.smarthappy.module.srm.supplier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.module.qms.link.SupplierQualityQuery.ItemQuality;
import io.smarthappy.module.qms.link.SupplierQualityQuery.NcrPage;
import io.smarthappy.module.srm.supplier.SupplierDtos.QualitySummary;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 협력사 상세 품질 요약 카드 (srm-supplier, SRM 2부 R4 뒷정리 — docs/31 4.12) — 보기만, 저장하지 않는다.
 * QMS 값은 공개 계약 {@link SupplierQualityQuery} 로만(없는 설치본은 NONE → 0·PPM 없음). 클레임·반품은 SRM 표.
 * PPM 은 평가 RECV_PPM 과 같은 계산·같은 "단위 둘 이상이면 없음" 규칙 — 카드와 평가 점수가 다른 말을 하지 않게.
 */
@Service
@RequiredArgsConstructor
public class SupplierQualityService {

    static final int DAYS = 90;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final SupplierService suppliers;
    private final SupplierMapper mapper;
    private final ObjectProvider<SupplierQualityQuery> quality;
    private final BizClock clock;

    @Transactional(readOnly = true)
    public QualitySummary summary(long custId) {
        suppliers.registered(custId);                 // 없거나 다른 회사·미등록 = 404
        LocalDate to = clock.today();
        LocalDate from = to.minusDays(DAYS - 1);
        SupplierQualityQuery q = SupplierQualityQuery.of(quality);
        List<ItemQuality> rows = q.receiptQuality(custId, from, to);
        BigDecimal inspected = rows.stream().map(ItemQuality::inspectedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> units = rows.stream().filter(r -> r.inspectedQty().signum() > 0).map(ItemQuality::unitCd).distinct().sorted().toList();
        String ppm = null;
        String note;
        if (inspected.signum() <= 0) {
            note = "NONE";
        } else if (units.size() > 1) {
            note = "UNITS";
        } else {
            BigDecimal rejected = rows.stream().map(ItemQuality::rejectedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            ppm = plain(rejected.multiply(MILLION).divide(inspected, 2, RoundingMode.HALF_UP));
            note = null;
        }
        NcrPage ncrs = q.ncrsOfSupplier(custId, from, to);
        int openNcr = (int) ncrs.rows().stream().filter(n -> "OPEN".equals(n.status()) || "PENDING".equals(n.status())).count();
        return new QualitySummary(from, to, units.size() == 1 ? plain(inspected) : null, units.size() == 1 ? units.get(0) : null, ppm, note,
                rows.stream().mapToInt(ItemQuality::inspectionCnt).sum(), rows.stream().mapToInt(ItemQuality::ngCnt).sum(), openNcr,
                ncrs.truncated(), mapper.countOpenClaims(custId), mapper.countReturnsToShip(custId));
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }
}
