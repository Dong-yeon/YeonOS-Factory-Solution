package io.smarthappy.module.wms.count;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.count.CountDtos.PeriodCheck;
import io.smarthappy.module.wms.count.CountDtos.PeriodForm;
import io.smarthappy.module.wms.count.CountDtos.PeriodRow;
import io.smarthappy.module.wms.inv.InvDtos.ReconcileRow;
import io.smarthappy.module.wms.inv.StockService;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 재고 기간 마감 (docs/30 W5, D10) — 공장 × 월. 마감된 월의 발생 시각으로는 원장이 못 들어간다(inv_trx 트리거 — 역분개 포함).
 * 마감은 **지난 달까지만**(공장 시간대), 재개는 사유. 둘 다 APPROVE(컨트롤러) + 감사 트리거로 남는다.
 * 마감·재개는 그 공장·월 잠금을 배타로 잡아, 진행 중인 그 월 원장이 커밋될 때까지 기다린다(트리거가 공유로 잡는다).
 */
@Service
@RequiredArgsConstructor
public class PeriodService {

    static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final CountMapper mapper;
    private final StockService stock;
    private final BizClock clock;
    private final PolicyService policy;

    /**
     * 그리드 열(docs/40 §10.2 D1). 상태는 CODE 가 아니라 TEXT — 행 없는 달(그리드는 'OPEN', 목록은 null)은 공통코드
     * PERIOD_STATUS 에 없어 이름을 화면이 붙이고(format),
     * 배지 톤도 화면(GridOptions.status). 마감·재개는 시각과 사람을 따로 열로 둔다(정렬·엑셀).
     */
    static final List<ColumnSpec> PERIOD_COLUMNS = List.of(
            ColumnSpec.text("period", "wmsPeriod.col.period", 90).withSort().withPriority(1),
            ColumnSpec.text("status", "wmsPeriod.col.status", 90).withSort().withPriority(1),
            ColumnSpec.of("closedAt", "wmsPeriod.col.closedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(2),
            ColumnSpec.text("closedBy", "wmsPeriod.col.closedBy", 100).withPriority(2),
            ColumnSpec.of("reopenedAt", "wmsPeriod.col.reopenedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3),
            ColumnSpec.text("reopenedBy", "wmsPeriod.col.reopenedBy", 100).withPriority(3),
            ColumnSpec.text("reason", "wmsPeriod.reason", 200).withFlex().withMinWidth(120).withPriority(3));

    /** 그리드 기본 정렬 = 기존 목록과 같은 순서(월 오름차순) */
    static final List<String> PERIOD_SORT = List.of("period,asc");

    /** 그 해 12달(없는 달은 열림 — 행 없음) */
    @Transactional(readOnly = true)
    public List<PeriodRow> year(String plantCd, Integer year) {
        String plant = plant(plantCd);
        int y = yearOf(plant, year);
        Map<String, PeriodRow> rows = mapper.findPeriods(plant, y + "01", y + "12").stream()
                .collect(Collectors.toMap(PeriodRow::getPeriod, Function.identity()));
        List<PeriodRow> out = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            String p = YearMonth.of(y, m).format(YM);
            PeriodRow r = rows.get(p);
            if (r == null) {
                r = new PeriodRow();
                r.setPlantCd(plant);
                r.setPeriod(p);
            }
            out.add(r);
        }
        return out;
    }

    /** 그리드 — 조건은 year 와 같고 페이징·정렬(허용 열만)·건수. 기존 year 목록 API 는 그대로(결정 docs/29 #45) */
    @Transactional(readOnly = true)
    public CountService.Page<PeriodRow> grid(String plantCd, Integer year, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, CountDtos.PeriodSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                PERIOD_SORT);
        String plant = plant(plantCd);
        int y = yearOf(plant, year);
        // 건수는 늘 12(1~12월을 만들어 붙이므로) — 따로 세지 않는다
        return new CountService.Page<>(mapper.findPeriodPage(plant, String.valueOf(y), pq), pq.isWithTotal() ? 12L : null);
    }

    public List<ColumnSpec> columns() {
        return PERIOD_COLUMNS;
    }

    /** 연도 — 비면 이번 해(공장 시간대), 2000~2999 만 */
    private int yearOf(String plant, Integer year) {
        int y = year == null ? thisMonth(plant).getYear() : year;
        if (y < 2000 || y > 2999) {
            throw CountService.fieldError("year", "FORMAT");
        }
        return y;
    }

    /** 마감 전 점검 — 미확정 실사·마지막 대사 불일치(경고만) */
    @Transactional(readOnly = true)
    public PeriodCheck check(String plantCd, String period) {
        String plant = plant(plantCd);
        YearMonth ym = period(period);
        ReconcileRow last = stock.lastReconcile();
        return new PeriodCheck(plant, ym.format(YM), mapper.countOpen(plant), last == null ? null : last.getMismatchCount(),
                last == null ? null : last.getRunAt(), !ym.isBefore(thisMonth(plant)));
    }

    @Transactional
    public PeriodRow close(PeriodForm f) {
        LoginUser me = CountService.me();
        String plant = plant(f == null ? null : f.plantCd());
        YearMonth ym = period(f.period());
        if (!ym.isBefore(thisMonth(plant))) {
            throw CountService.fieldError("period", "NOT_PAST_MONTH");    // 이번 달·미래는 마감하지 않는다
        }
        String p = ym.format(YM);
        mapper.lockPeriod(plant, p);
        PeriodRow cur = mapper.findPeriod(plant, p);
        if (cur == null) {
            try {
                mapper.insertClose(plant, p, me.loginId());
            } catch (DuplicateKeyException e) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } else if ("CLOSED".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.CONFLICT);           // 이미 마감(다른 사람이 먼저)
        } else if (f.rowVer() == null || mapper.reclose(cur.getCloseId(), f.rowVer(), me.loginId()) != 1) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return mapper.findPeriod(plant, p);
    }

    @Transactional
    public PeriodRow reopen(PeriodForm f) {
        LoginUser me = CountService.me();
        String plant = plant(f == null ? null : f.plantCd());
        String p = period(f.period()).format(YM);
        String reason = f.reason() == null ? "" : f.reason().strip();
        FieldErrors e = FieldErrors.create();
        if (reason.length() < 2) {
            e.add("reason", "REQUIRED");
        } else if (reason.length() > 500) {
            e.add("reason", "TOO_LONG", 500);
        }
        if (f.rowVer() == null) {
            e.add("rowVer", "REQUIRED");
        }
        e.throwIfAny();
        mapper.lockPeriod(plant, p);
        PeriodRow cur = mapper.findPeriod(plant, p);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.reopen(cur.getCloseId(), f.rowVer(), reason, me.loginId()) != 1) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return mapper.findPeriod(plant, p);
    }

    private YearMonth thisMonth(String plant) {
        ZoneId zone = clock.zone(plant);
        return YearMonth.from(clock.instant().atZone(zone));
    }

    private static YearMonth period(String v) {
        if (v == null || v.isBlank()) {
            throw CountService.fieldError("period", "REQUIRED");
        }
        String s = v.strip().replace("-", "");
        if (!s.matches("^[0-9]{4}(0[1-9]|1[0-2])$")) {
            throw CountService.fieldError("period", "FORMAT");
        }
        return YearMonth.parse(s, YM);
    }

    private String plant(String plantCd) {
        if (plantCd == null || plantCd.isBlank()) {
            throw CountService.fieldError("plantCd", "REQUIRED");
        }
        String p = plantCd.strip().toUpperCase(Locale.ROOT);
        if (p.length() > 20 || !mapper.plantExists(p)) {
            throw CountService.fieldError("plantCd", "NOT_FOUND");
        }
        return p;
    }
}
