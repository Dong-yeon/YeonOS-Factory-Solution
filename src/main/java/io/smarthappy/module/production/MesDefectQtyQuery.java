package io.smarthappy.module.production;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.DefectQtyMapper.Row;
import io.smarthappy.module.production.link.DefectQtyQuery;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/** 월별 불량 수량 계약 구현 (docs/25 4.48). 기간은 회사 시간대의 월 경계를 시각으로 바꿔 [시작, 끝) 로 묻는다 */
@Component
@RequiredArgsConstructor
class MesDefectQtyQuery implements DefectQtyQuery {

    private final DefectQtyMapper mapper;
    private final BizClock clock;

    @Override
    @Transactional(readOnly = true)
    public List<DefectQty> monthly(String plantCd, YearMonth from, YearMonth to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("invalid month range: " + from + " ~ " + to);
        }
        if (ChronoUnit.MONTHS.between(from, to) + 1 > MAX_MONTHS) {
            throw new IllegalArgumentException("month range too long: " + from + " ~ " + to + " > " + MAX_MONTHS);
        }
        ZoneId zone = clock.zone();
        Instant start = from.atDay(1).atStartOfDay(zone).toInstant();
        Instant end = to.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        String plant = plantCd == null || plantCd.isBlank() ? null : plantCd.strip();
        return mapper.monthly(plant, start, end, zone.getId()).stream()
                .map(r -> new DefectQty(YearMonth.parse(r.getYm()), r.getItemId(), r.getKind(), r.getQty(), r.getUnitCd(), r.getSource()))
                .toList();
    }
}
