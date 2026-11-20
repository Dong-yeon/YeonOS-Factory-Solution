package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.cms.EquipKpi.Raw;
import io.smarthappy.module.cms.EquipKpi.Result;

/** 설비 KPI 판정 손 검산 (docs/33 11.8) — DB 없음 */
class EquipKpiTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** 하루 8시간 수집: PRD 6h, SDT 0.5h, UDT 1h(고장 2회), UNKNOWN 0.5h. 생산 500(양품 480), 표준 36초/1개 */
    private static Raw day() {
        long h = 3600;
        return new Raw(24 * h, 8 * h, h / 2, 0, h / 2, h, 6 * h, 2, d("18000"), d("17280"), 0, d("480"), d("500"), 4, 3);
    }

    @Test
    void 손검산_가용_성능_품질_OEE_MTBF_MTTR_PM() {
        Result r = EquipKpi.of(day());
        // 계획 = 8h − 0.5h − 0 − 0.5h = 7h, 가용 = 6/7
        assertThat(r.plannedSec()).isEqualTo(7 * 3600);
        assertThat(r.availability()).isEqualByComparingTo("0.8571");
        // 성능 = 500×36 / 6h = 18000/21600
        assertThat(r.performance()).isEqualByComparingTo("0.8333");
        assertThat(r.quality()).isEqualByComparingTo("0.9600");
        // OEE = 480×36 / 7h = 17280/25200 (= 가용×성능×품질 0.857142…×0.8333…×0.96 = 0.6857)
        assertThat(r.oee()).isEqualByComparingTo("0.6857");
        assertThat(r.mtbfMin()).isEqualByComparingTo("180.0");
        assertThat(r.mttrMin()).isEqualByComparingTo("30.0");
        assertThat(r.pmCompliance()).isEqualByComparingTo("0.7500");
        assertThat(r.uncoveredSec()).isEqualTo(16 * 3600);
    }

    @Test
    void 수집없으면_시간지표_빈칸_품질은_실적으로() {
        Raw noCollector = new Raw(86400, 0, 0, 0, 0, 0, 0, 0, d("3600"), d("3500"), 0, d("97"), d("100"), 0, 0);
        Result r = EquipKpi.of(noCollector);
        assertThat(r.collected()).isFalse();
        assertThat(r.availability()).isNull();
        assertThat(r.performance()).isNull();
        assertThat(r.oee()).isNull();
        assertThat(r.mtbfMin()).isNull();
        assertThat(r.quality()).isEqualByComparingTo("0.9700");
        assertThat(r.pmCompliance()).isNull();
    }

    @Test
    void 표준사이클_없는_실적이_있으면_성능_OEE_빈칸_고장0이면_MTBF_빈칸() {
        Raw r0 = day();
        Raw missing = new Raw(r0.periodSec(), r0.coveredSec(), r0.unknownSec(), r0.nstSec(), r0.sdtSec(), 0, r0.prdSec(), 0, r0.idealSec(),
                r0.idealGoodSec(), 1, r0.goodQty(), r0.totalQty(), 0, 0);
        Result r = EquipKpi.of(missing);
        assertThat(r.performance()).isNull();
        assertThat(r.oee()).isNull();
        assertThat(r.availability()).isNotNull();
        assertThat(r.mtbfMin()).isNull();
        assertThat(r.mttrMin()).isNull();
    }

    @Test
    void 합산은_분자분모로_평균의평균이_아니다() {
        // A: 가동 1h / 계획 1h = 100%, B: 가동 1h / 계획 9h ≈ 11.1% → 평균의 평균 55.6% 가 아니라 2/10 = 20%
        Raw a = new Raw(3600, 3600, 0, 0, 0, 0, 3600, 0, d("3600"), d("3600"), 0, d("100"), d("100"), 0, 0);
        Raw b = new Raw(32400, 32400, 0, 0, 0, 0, 3600, 0, d("3600"), d("1800"), 0, d("50"), d("100"), 0, 0);
        Result sum = EquipKpi.of(a.plus(b));
        assertThat(sum.availability()).isEqualByComparingTo("0.2000");
        assertThat(sum.quality()).isEqualByComparingTo("0.7500");
        assertThat(sum.oee()).isEqualByComparingTo("0.1500");   // (3600 + 1800) / 36000
        // 일별 합산 = 기간 합산: 같은 날 두 번 나눠 더해도 결과가 같다
        assertThat(EquipKpi.of(Raw.ZERO.plus(day()).plus(day()))).usingRecursiveComparison()
                .ignoringFields("plannedSec", "uncoveredSec").isEqualTo(EquipKpi.of(day()));
    }
}
