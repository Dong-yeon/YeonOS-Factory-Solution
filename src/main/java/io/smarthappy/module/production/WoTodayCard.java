package io.smarthappy.module.production;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCard.Fact;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.home.HomeCardProvider;
import io.smarthappy.module.home.HomeService;
import io.smarthappy.module.production.ProductionHomeMapper.WoTodayRow;
import io.smarthappy.module.production.ProductionHomeMapper.WoTodaySummary;
import lombok.RequiredArgsConstructor;

/**
 * 오늘 작업지시·진행 공정 (docs/37 6장) — 발행·진행 중이고 계획 시작 ≤ 오늘인 지시. 일시정지 공정·계획 종료가 지난 지시가 있으면 확인 필요.
 */
@Component
@RequiredArgsConstructor
public class WoTodayCard implements HomeCardProvider {

    private final ProductionHomeMapper mapper;

    @Override
    public String code() {
        return "prd-wo-today";
    }

    @Override
    public String titleKey() {
        return "home.card.prdWoToday";
    }

    @Override
    public String progCd() {
        return WoService.PROG;
    }

    @Override
    public int sortNo() {
        return 400;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        // 공장 카드(5단계): 고른 공장으로 거르고 공장 날짜. 링크도 같은 공장·같은 날짜라 숫자 = 목록 건수
        LocalDate today = ctx.plantToday();
        WoTodaySummary s = mapper.woTodaySummary(today, ctx.plantCd());
        List<Item> items = mapper.woTodayTop(today, ctx.plantCd(), HomeService.MAX_ITEMS).stream().map(r -> item(r, ctx)).toList();
        Tone pause = HomeCard.toneIf(s.pauseCnt(), Tone.WARN);
        Tone overdue = HomeCard.toneIf(s.overdueCnt(), Tone.WARN);
        Map<String, String> link = new HashMap<>(Map.of("status", "ACTIVE", "from", "", "to", today.toString()));
        if (ctx.plantCd() != null) {
            link.put("plantCd", ctx.plantCd());
        }
        return new HomeCard(s.woCnt(), HomeCard.max(pause, overdue), List.of(
                new Fact(ctx.msg("home.card.prdWoToday.run"), s.runCnt(), Tone.NEUTRAL),
                new Fact(ctx.msg("home.card.prdWoToday.pause"), s.pauseCnt(), pause),
                new Fact(ctx.msg("home.card.prdWoToday.overdue"), s.overdueCnt(), overdue)), items,
                // 카드 숫자 = 목록(docs/37 16.2): 상태 묶음 ACTIVE(발행+진행 중) + 계획 시작 ≤ 오늘(시작 쪽은 비워 열린 기간)
                link);
    }

    @Override
    public boolean plantScoped() {
        return true;
    }

    /** 작업지시 관리 조회 조건: 지시 번호 + 계획 시작일 하루(목록 기간 = 계획 시작) */
    static Map<String, String> link(String woNo, LocalDate planStart) {
        return Map.of("keyword", woNo, "from", planStart.toString(), "to", planStart.toString());
    }

    private static Item item(WoTodayRow r, HomeCardContext ctx) {
        boolean late = r.planEnd().isBefore(ctx.plantToday());
        return new Item(r.woNo(), ctx.msg("home.card.prdWoToday.item", r.itemCd(), r.itemNm(), r.planQty(), r.unitCd(),
                ctx.msg("code.WO_STATUS." + r.status()), r.planEnd().toString()), late ? Tone.WARN : Tone.NEUTRAL,
                link(r.woNo(), r.planStart()));
    }
}
