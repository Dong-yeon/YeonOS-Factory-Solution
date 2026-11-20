package io.smarthappy.module.wms.ship;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.home.HomeCardProvider;
import io.smarthappy.module.home.HomeService;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "예약 초과 품목" (docs/53 (가) 계산형 소프트 예약, L1-4) — 공장 카드. 숫자 = 열린 출하 지시(DRAFT·PICKING)의 미피킹 합이 가용보다 큰
 * 공장 × 품목 수. 막지 않는 경고라(L1-3) 사람이 볼 곳이 여기 — 입고를 당기거나 지시를 나눈다. 링크 없음(출하 화면에서 줄마다 같은 숫자)
 */
@Component
@RequiredArgsConstructor
public class ShipReserveCard implements HomeCardProvider {

    private final ShipHomeMapper mapper;

    @Override
    public String code() {
        return "wms-ship-reserve";
    }

    @Override
    public String titleKey() {
        return "home.card.wmsShipReserve";
    }

    @Override
    public String progCd() {
        return ShipService.PROG;
    }

    @Override
    public int sortNo() {
        return 775;
    }

    @Override
    public boolean plantScoped() {
        return true;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        long n = mapper.reserveOverCount(ctx.plantCd());
        List<Item> items = n == 0 ? List.of() : mapper.reserveOverTop(ctx.plantCd(), HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.itemCd() + " " + r.itemNm(), ctx.msg("home.card.wmsShipReserve.item", r.plantCd(), q(r.reserved()),
                        q(r.avail()), q(r.reserved().subtract(r.avail())), r.unitCd()), Tone.WARN))
                .toList();
        return new HomeCard(n, HomeCard.toneIf(n, Tone.WARN), List.of(), items);
    }

    private static String q(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
