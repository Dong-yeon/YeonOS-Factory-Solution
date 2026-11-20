package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.cms.CmsHomeMapper.ChkDueSummary;
import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCard.Fact;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.home.HomeCardProvider;
import io.smarthappy.module.home.HomeService;
import io.smarthappy.platform.common.query.DateRanges;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "점검 예정" (docs/37 17.3, 5단계) — 공장 카드. 숫자 = 열린 점검(OPEN) 중 예정일이 창 [오늘+7 − (상한−1), 오늘+7] 안.
 * 창 상한 = 점검 실행 목록과 같은 DateRanges.maxDays(366)(회사 정책이 더 짧으면 그것) — 숫자 링크가 400 없이 같은 건수를 연다(H22).
 * 창보다 오래 밀린 점검은 숫자에 넣지 않고 보조 숫자(ALERT)로 따로 알린다.
 */
@Component
@RequiredArgsConstructor
public class ChkDueCard implements HomeCardProvider {

    static final int AHEAD_DAYS = 7;

    private final CmsHomeMapper mapper;
    private final DateRanges ranges;

    @Override
    public String code() {
        return "cms-chk-due";
    }

    @Override
    public String titleKey() {
        return "home.card.cmsChkDue";
    }

    @Override
    public String progCd() {
        return ChkRunService.PROG;
    }

    @Override
    public int sortNo() {
        return 560;
    }

    @Override
    public boolean plantScoped() {
        return true;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        LocalDate today = ctx.plantToday();
        LocalDate to = today.plusDays(AHEAD_DAYS);
        LocalDate from = to.minusDays(ranges.maxDays(ChkRunService.MAX_DAYS) - 1L);
        ChkDueSummary s = mapper.chkDueSummary(from, to, today, ctx.plantCd());
        List<Item> items = mapper.chkDueTop(from, to, ctx.plantCd(), HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.targetCd(), ctx.msg("home.card.cmsChkDue.item", r.planNm(), r.dueDate().toString()),
                        r.dueDate().isBefore(today) ? Tone.ALERT : r.dueDate().isEqual(today) ? Tone.WARN : Tone.NEUTRAL,
                        withPlant(Map.of("keyword", r.targetCd(), "status", "OPEN", "from", r.dueDate().toString(),
                                "to", r.dueDate().toString()), ctx)))
                .toList();
        long n = s.overdueCnt() + s.todayCnt() + s.soonCnt();
        Tone overdue = HomeCard.toneIf(s.overdueCnt(), Tone.ALERT);
        Tone before = HomeCard.toneIf(s.beforeCnt(), Tone.ALERT);
        List<Fact> facts = new java.util.ArrayList<>(List.of(
                new Fact(ctx.msg("home.card.cmsChkDue.overdue"), s.overdueCnt(), overdue),
                new Fact(ctx.msg("home.card.cmsChkDue.today"), s.todayCnt(), HomeCard.toneIf(s.todayCnt(), Tone.WARN))));
        if (s.beforeCnt() > 0) {
            facts.add(new Fact(ctx.msg("home.card.cmsChkDue.before", from.toString()), s.beforeCnt(), before));
        } else {
            facts.add(new Fact(ctx.msg("home.card.cmsChkDue.soon", AHEAD_DAYS), s.soonCnt(), Tone.NEUTRAL));
        }
        return new HomeCard(n, HomeCard.max(overdue, before, HomeCard.toneIf(s.todayCnt(), Tone.WARN)), facts, items,
                withPlant(Map.of("status", "OPEN", "from", from.toString(), "to", to.toString()), ctx));
    }

    /** 공장을 골랐으면 목록 공장 칸도 같은 공장으로 */
    static Map<String, String> withPlant(Map<String, String> base, HomeCardContext ctx) {
        if (ctx.plantCd() == null) {
            return base;
        }
        Map<String, String> m = new HashMap<>(base);
        m.put("plantCd", ctx.plantCd());
        return m;
    }
}
