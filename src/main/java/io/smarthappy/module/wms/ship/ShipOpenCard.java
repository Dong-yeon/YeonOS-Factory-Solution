package io.smarthappy.module.wms.ship;

import java.time.LocalDate;
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
import io.smarthappy.module.wms.ship.ShipHomeMapper.ShipOpenSummary;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "출하 대기" (docs/37 20장, 8단계) — 공장 카드. 숫자 = DRAFT·PICKING 출하 지시. 계획일이 공장 오늘보다 앞이면 늦은 출하(WARN).
 * 카드 숫자 링크는 없다(H32) — 출하 목록이 공장 필수 + 상태 하나라 "대기 전부" 를 한 번에 못 건다(실사 카드 H25 와 같다). 항목마다 그 지시의 공장·상태로 링크.
 */
@Component
@RequiredArgsConstructor
public class ShipOpenCard implements HomeCardProvider {

    private final ShipHomeMapper mapper;

    @Override
    public String code() {
        return "wms-ship-open";
    }

    @Override
    public String titleKey() {
        return "home.card.wmsShipOpen";
    }

    @Override
    public String progCd() {
        return ShipService.PROG;
    }

    @Override
    public int sortNo() {
        return 770;
    }

    @Override
    public boolean plantScoped() {
        return true;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        LocalDate today = ctx.plantToday();
        ShipOpenSummary s = mapper.shipOpenSummary(ctx.plantCd(), today);
        List<Item> items = mapper.shipOpenTop(ctx.plantCd(), HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.shipNo(), ctx.msg("home.card.wmsShipOpen.item", r.plantCd(), r.custNm(),
                        ctx.msg("code.WMS_SHIP_STATUS." + r.status()), r.planDate().toString()),
                        r.planDate().isBefore(today) ? Tone.WARN : Tone.NEUTRAL,
                        Map.of("plantCd", r.plantCd(), "status", r.status())))
                .toList();
        Tone late = HomeCard.toneIf(s.lateCnt(), Tone.WARN);
        return new HomeCard(s.openCnt(), HomeCard.max(HomeCard.toneIf(s.openCnt(), Tone.INFO), late), List.of(
                new Fact(ctx.msg("home.card.wmsShipOpen.picking"), s.pickingCnt(), Tone.NEUTRAL),
                new Fact(ctx.msg("home.card.wmsShipOpen.late"), s.lateCnt(), late)), items);
    }
}
