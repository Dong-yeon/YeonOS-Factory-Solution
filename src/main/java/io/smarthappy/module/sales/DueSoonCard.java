package io.smarthappy.module.sales;

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
import io.smarthappy.module.sales.SalesHomeMapper.DueRow;
import io.smarthappy.module.sales.SalesHomeMapper.DueSummary;
import lombok.RequiredArgsConstructor;

/** 납기 임박 수주 (docs/37 6장) — 열린 행 중 납기가 지났거나 {@link #DAYS}일 안. 지난 것이 있으면 지연(ALERT) */
@Component
@RequiredArgsConstructor
public class DueSoonCard implements HomeCardProvider {

    /** 납기 임박 기준(일) — 정책으로 뺄지는 docs/37 H4 */
    static final int DAYS = 7;

    private final SalesHomeMapper mapper;

    @Override
    public String code() {
        return "sal-due-soon";
    }

    @Override
    public String titleKey() {
        return "home.card.salDueSoon";
    }

    @Override
    public String progCd() {
        return OrderService.PROG;
    }

    @Override
    public int sortNo() {
        return 300;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        DueSummary s = mapper.dueSummary(ctx.today(), ctx.today().plusDays(DAYS));
        List<Item> items = mapper.dueTop(ctx.today().plusDays(DAYS), HomeService.MAX_ITEMS).stream().map(r -> item(r, ctx)).toList();
        long total = s.overdueCnt() + s.todayCnt() + s.soonCnt();
        Tone overdue = HomeCard.toneIf(s.overdueCnt(), Tone.ALERT);
        return new HomeCard(total, HomeCard.max(overdue, HomeCard.toneIf(total, Tone.WARN)), List.of(
                new Fact(ctx.msg("home.card.salDueSoon.overdue"), s.overdueCnt(), overdue),
                new Fact(ctx.msg("home.card.salDueSoon.today"), s.todayCnt(), HomeCard.toneIf(s.todayCnt(), Tone.WARN)),
                new Fact(ctx.msg("home.card.salDueSoon.soon", DAYS), s.soonCnt(), Tone.NEUTRAL)), items,
                // 카드 숫자 = 목록의 "납기 행 m건"(docs/37 16.2): 열린 행 납기 ≤ 오늘+7, 수주 상태 전체(카드는 행 상태만 본다), 수주일 기간 비움
                Map.of("dueFrom", "", "dueTo", ctx.today().plusDays(DAYS).toString(), "status", "", "from", "", "to", ""));
    }

    private static Item item(DueRow r, HomeCardContext ctx) {
        Tone tone = r.dueDate().isBefore(ctx.today()) ? Tone.ALERT : r.dueDate().isEqual(ctx.today()) ? Tone.WARN : Tone.NEUTRAL;
        return new Item(r.orderNo() + "-" + r.lineNo(),
                ctx.msg("home.card.salDueSoon.item", r.custNm(), r.itemCd(), r.qty(), r.unitCd(), r.dueDate().toString()), tone,
                // 수주 관리 조회 조건: 수주 번호 + 수주일 하루(목록 기간 = 수주일)
                Map.of("keyword", r.orderNo(), "from", r.orderDate().toString(), "to", r.orderDate().toString()));
    }
}
