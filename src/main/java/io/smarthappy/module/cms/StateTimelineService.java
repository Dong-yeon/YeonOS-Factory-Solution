package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CollectorDtos.PlantRow;
import io.smarthappy.module.cms.CollectorDtos.Timeline;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/** 설비 상태 타임라인(사무, cms-state) — 공장 현지 하루 [00:00, 다음날 00:00) */
@Service
@RequiredArgsConstructor
public class StateTimelineService {

    public static final String PROG = "cms-state";

    private final CollectorMapper mapper;
    private final BizClock clock;

    @Transactional(readOnly = true)
    public List<PlantRow> plants() {
        return mapper.plants();
    }

    @Transactional(readOnly = true)
    public Timeline day(String plantCd, String date) {
        if (plantCd == null || plantCd.isBlank()) {
            throw OrderService.fieldError("plantCd", "REQUIRED");
        }
        String plant = plantCd.strip();
        ZoneId zone = clock.zone(plant);
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? clock.instant().atZone(zone).toLocalDate() : LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw OrderService.fieldError("date", "FORMAT");
        }
        var from = day.atStartOfDay(zone).toOffsetDateTime();
        var to = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        return new Timeline(plant, zone.getId(), day.toString(), from, to, mapper.equips(plant), mapper.intervals(plant, from.toInstant(), to.toInstant()),
                mapper.conns(plant, from.toInstant(), to.toInstant()));
    }
}
