package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.link.DefectQtyQuery;
import io.smarthappy.module.qms.CopqDtos.CopqView;
import io.smarthappy.module.qms.CopqDtos.CostForm;
import io.smarthappy.module.qms.CopqDtos.CostRow;
import io.smarthappy.module.qms.CopqDtos.CostValue;
import io.smarthappy.module.qms.CopqDtos.ItemPrice;
import io.smarthappy.module.qms.CopqDtos.LineRow;
import io.smarthappy.module.qms.CopqDtos.MonthRow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.common.text.VisibleText;
import lombok.RequiredArgsConstructor;

/**
 * COPQ(품질 비용) 월별 집계 (qms-copq) — docs/29_P3 29장 1단계. 회계 원장 아님(관리용 추정).
 * - 원장 줄: 종결 NCR 의 처리(폐기·재작업·선별) 수량 × 그 시각의 품목 표준단가(CQ-3·5). 고객 클레임 출처는 외부 실패, 그 밖은 내부 실패
 * - 생산 실적 불량(29.4): MES DefectQtyQuery 의 월·품목·종류별 수량 × 그 달 말 표준단가 — 정정은 정정한 달에 음수(C-1 (라)), 단위가 다르면 UNIT
 * - 단가가 없으면 금액을 비우고 건수만 센다 — 0원으로 더하지 않는다(CQ-4). 단위는 늘 품목 기본 단위다(NCR 단위 = LOT 단위 = 품목 기본 단위,
 *   LOT 이 생긴 품목은 기본 단위를 못 바꾼다 — hb_guard_lot_master·hb_check_item_unit_in_use)
 * - 수동 행: 원장에서 못 뽑는 비용(예방·평가 등) — 월·구분·항목·금액
 * - 금액 = 수량 × 단가를 줄마다 소수 2자리 반올림(HALF_UP) 뒤 더한다
 */
@Service
@RequiredArgsConstructor
public class CopqService {

    static final String PROG = "qms-copq";
    static final List<String> CATEGORIES = List.of("PREVENTION", "APPRAISAL", "INTERNAL", "EXTERNAL");
    static final Set<String> CATEGORY_SET = Set.copyOf(CATEGORIES);
    static final int MAX_MONTHS = 24;
    static final int AMOUNT_SCALE = 2;
    static final int AMOUNT_INT_DIGITS = 16;
    static final Pattern MONTH = Pattern.compile("^[0-9]{4}-[0-9]{2}$");
    static final Pattern DECIMAL = Pattern.compile("^-?[0-9]{1,30}(\\.[0-9]{1,30})?$");

    private final CopqMapper mapper;
    private final BizClock clock;
    private final InboxService inbox;
    private final ObjectProvider<DefectQtyQuery> defects;

    /** 생산 불량 원천·종류 → 원장 줄 출처(29.4 CQ-9) */
    static String prdSource(String source, String kind) {
        if (DefectQtyQuery.REWORK.equals(source)) {
            return "PRD_RW_SCRAP";   // 재작업·수리 처리에서 나온 폐기
        }
        return switch (kind) {
            case "SCRAP" -> "PRD_SCRAP";
            case "REWORK" -> "PRD_REWORK";
            default -> "PRD_REPAIR";
        };
    }

    // ------------------------------------------------------------------ 조회

    /** from~to(YYYY-MM, 양끝 포함, 24개월까지). 비우면 이번 달 */
    @Transactional(readOnly = true)
    public CopqView summary(String fromMonth, String toMonth) {
        YearMonth now = YearMonth.from(clock.today());
        YearMonth from = VisibleText.isBlank(fromMonth) ? now : month(fromMonth);
        YearMonth to = VisibleText.isBlank(toMonth) ? from : month(toMonth);
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        long span = ChronoUnit.MONTHS.between(from, to) + 1;
        if (span > MAX_MONTHS) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("toMonth", "MONTHS_TOO_LONG", MAX_MONTHS, span).list());
        }
        ZoneId zone = clock.zone();
        OffsetDateTime fromAt = from.atDay(1).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toAt = to.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime();
        List<LineRow> lines = new ArrayList<>(mapper.findNcrLines(fromAt, toAt, zone.getId()));
        lines.forEach(CopqService::price);
        lines.addAll(productionLines(from, to, zone));
        List<CostRow> costs = mapper.findCosts(from.atDay(1), to.plusMonths(1).atDay(1));
        return build(from, to, lines, costs);
    }

    /**
     * 생산 실적 불량(MES DefectQtyQuery — 29.4): 월·품목·종류별 수량(정정은 정정한 달에 음수, C-1 (라)) × 그 달 말의 표준단가.
     * 수량 단위가 품목 기본 단위와 다르면(지시 단위) 금액을 비우고 UNIT. 다른 회사·없는 품목 줄은 빠진다(RLS — 계약도 같은 회사만 준다)
     */
    List<LineRow> productionLines(YearMonth from, YearMonth to, ZoneId zone) {
        List<DefectQtyQuery.DefectQty> rows = DefectQtyQuery.of(defects).monthly(null, from, to);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> itemIds = rows.stream().map(DefectQtyQuery.DefectQty::itemId).distinct().toList();
        Map<String, ItemPrice> prices = new java.util.HashMap<>();
        mapper.findMonthEndPrices(itemIds, from.atDay(1), to.atDay(1), zone.getId())
                .forEach(p -> prices.put(p.getItemId() + "@" + p.getMonth(), p));
        List<LineRow> out = new ArrayList<>();
        for (DefectQtyQuery.DefectQty r : rows) {
            ItemPrice p = prices.get(r.itemId() + "@" + r.month());
            if (p == null) {
                continue;
            }
            LineRow l = new LineRow();
            l.setMonth(r.month().toString());
            l.setCategory("INTERNAL");
            l.setSource(prdSource(r.source(), r.kind()));
            l.setItemId(r.itemId());
            l.setItemCd(p.getItemCd());
            l.setItemNm(p.getItemNm());
            l.setUnitCd(r.unitCd());
            l.setStdPrice(p.getStdPrice());
            l.setQty(r.qty());
            l.setLineCnt(1);
            if (!Objects.equals(r.unitCd(), p.getUnitCd())) {
                l.setReason("UNIT");
            } else {
                price(l);
            }
            out.add(l);
        }
        return out;
    }

    /** 줄 금액 — 단가 없음이면 null + 이유 */
    static void price(LineRow l) {
        if (l.getStdPrice() == null) {
            l.setReason("NO_PRICE");
        } else {
            l.setAmount(l.getQty().multiply(l.getStdPrice()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
        }
    }

    /** 월 × 4구분 합계. 구분 칸 null = 그 구분에 줄은 있는데 금액 있는 줄이 없음 */
    static CopqView build(YearMonth from, YearMonth to, List<LineRow> lines, List<CostRow> costs) {
        Map<String, Acc> byMonth = new LinkedHashMap<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            byMonth.put(m.toString(), new Acc());
        }
        Acc all = new Acc();
        for (LineRow l : lines) {
            Acc a = byMonth.get(l.getMonth());
            if (a != null) {
                a.add(l.getCategory(), l.getAmount(), l.getAmount() == null ? l.getLineCnt() : 0);
                all.add(l.getCategory(), l.getAmount(), l.getAmount() == null ? l.getLineCnt() : 0);
            }
        }
        for (CostRow c : costs) {
            Acc a = byMonth.get(c.getCostMonth());
            if (a != null) {
                a.add(c.getCategory(), c.getAmount(), 0);
                all.add(c.getCategory(), c.getAmount(), 0);
            }
        }
        List<MonthRow> months = new ArrayList<>();
        byMonth.forEach((m, a) -> months.add(a.row(m)));
        return new CopqView(from.toString(), to.toString(), months, all.row(null), lines, costs);
    }

    /** 한 달(또는 전체)의 구분별 금액 — seen = 줄이 하나라도 있었나, amount = 금액 있는 줄의 합 */
    private static final class Acc {
        final Map<String, BigDecimal> amount = new LinkedHashMap<>();
        final Set<String> seen = new java.util.HashSet<>();
        int unpriced;

        void add(String category, BigDecimal value, int unpricedCnt) {
            seen.add(category);
            unpriced += unpricedCnt;
            if (value != null) {
                amount.merge(category, value, BigDecimal::add);
            }
        }

        BigDecimal cell(String category) {
            BigDecimal v = amount.get(category);
            if (v != null) {
                return v;
            }
            return seen.contains(category) ? null : BigDecimal.ZERO.setScale(AMOUNT_SCALE);
        }

        MonthRow row(String month) {
            // 합계도 구분 칸과 같게 — 줄은 있는데 금액 있는 줄이 하나도 없으면 null(0원 아님)
            BigDecimal total = amount.isEmpty() && !seen.isEmpty() ? null
                    : amount.values().stream().reduce(BigDecimal.ZERO.setScale(AMOUNT_SCALE), BigDecimal::add);
            return new MonthRow(month, cell("PREVENTION"), cell("APPRAISAL"), cell("INTERNAL"), cell("EXTERNAL"), total, unpriced);
        }
    }

    // ------------------------------------------------------------------ 수동 행

    @Transactional
    public long createCost(CostForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_COPQ_COST_CREATE");
        return mapper.insertCost(validate(in, false), me.loginId());
    }

    @Transactional
    public void updateCost(long id, CostForm in) {
        LoginUser me = SamplingService.me();
        CostValue v = validate(in, true);
        if (mapper.findCost(id) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.updateCost(id, v, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    @Transactional
    public void deleteCost(long id, Integer rowVer) {
        SamplingService.me();
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.findCost(id) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.deleteCost(id, rowVer) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    static CostValue validate(CostForm in, boolean update) {
        if (in == null || (update && in.rowVer() == null)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        YearMonth m = null;
        if (VisibleText.isBlank(in.costMonth())) {
            e.add("costMonth", "REQUIRED");
        } else if ((m = month(in.costMonth())) == null) {
            e.add("costMonth", "FORMAT");
        }
        String cat = VisibleText.isBlank(in.category()) ? null : in.category().strip();
        if (cat == null) {
            e.add("category", "REQUIRED");
        } else if (!CATEGORY_SET.contains(cat)) {
            e.add("category", "CODE");
        }
        String item = VisibleText.reason(in.costItem(), "costItem", 1, 100, e);
        BigDecimal amount = amount(in.amount(), e);
        String note = VisibleText.optional(in.note(), "note", 500, e);
        e.throwIfAny();
        return new CostValue(m.atDay(1), cat, item, amount, note, in.rowVer());
    }

    /** 금액: 필수, 10진 문자열만·0 이상·소수 2자리·정수 16자리, 반올림하지 않는다 */
    static BigDecimal amount(String raw, FieldErrors e) {
        if (VisibleText.isBlank(raw)) {
            e.add("amount", "REQUIRED");
            return null;
        }
        String s = raw.strip();
        if (s.length() > 40 || !DECIMAL.matcher(s).matches()) {
            e.add("amount", "FORMAT");
            return null;
        }
        BigDecimal v = new BigDecimal(s);
        if (v.signum() < 0) {
            e.add("amount", "NOT_NEGATIVE");
            return null;
        }
        if (v.stripTrailingZeros().scale() > AMOUNT_SCALE) {
            e.add("amount", "SCALE", AMOUNT_SCALE);
            return null;
        }
        if (v.precision() - v.scale() > AMOUNT_INT_DIGITS) {
            e.add("amount", "INT_DIGITS", AMOUNT_INT_DIGITS);
            return null;
        }
        return v.setScale(AMOUNT_SCALE);
    }

    /** YYYY-MM → 달, 형식·범위(2000~2999년)가 틀리면 null */
    static YearMonth month(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (!MONTH.matcher(s).matches()) {
            return null;
        }
        try {
            YearMonth m = YearMonth.parse(s);
            return m.getYear() < 2000 || m.getYear() > 2999 ? null : m;
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
