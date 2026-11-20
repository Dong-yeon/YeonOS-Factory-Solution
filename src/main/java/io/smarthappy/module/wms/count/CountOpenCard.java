package io.smarthappy.module.wms.count;

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
import io.smarthappy.module.wms.count.WmsHomeMapper.CountOpenSummary;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "마감 안 된 실사" (docs/37 17.3, 5단계) — 공장 카드. 숫자 = DRAFT·COUNTING·REVIEW. 열린 실사는 재고 마감을 막으므로(기간 마감 전 확인) 볼 것.
 * 카드 숫자 링크는 없다(H25) — 실사 목록이 공장 필수 + 상태 하나라 "열린 것 전부" 를 한 번에 못 건다. 항목마다 그 실사의 공장·상태로 링크.
 */
@Component
@RequiredArgsConstructor
public class CountOpenCard implements HomeCardProvider {

    private final WmsHomeMapper mapper;

    @Override
    public String code() {
        return "wms-count-open";
    }

    @Override
    public String titleKey() {
        return "home.card.wmsCountOpen";
    }

    @Override
    public String progCd() {
        return CountService.PROG;
    }

    @Override
    public int sortNo() {
        return 760;
    }

    @Override
    public boolean plantScoped() {
        return true;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        CountOpenSummary s = mapper.countOpenSummary(ctx.plantCd());
        List<Item> items = mapper.countOpenTop(ctx.plantCd(), HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.countNo(), ctx.msg("home.card.wmsCountOpen.item", r.plantCd(),
                        ctx.msg("code.WMS_COUNT_STATUS." + r.status()),
                        r.createdAt().atZoneSameInstant(ctx.now().getZone()).toLocalDate().toString()),
                        "REVIEW".equals(r.status()) ? Tone.WARN : Tone.NEUTRAL,
                        Map.of("plantCd", r.plantCd(), "status", r.status())))
                .toList();
        Tone review = HomeCard.toneIf(s.reviewCnt(), Tone.WARN);
        return new HomeCard(s.openCnt(), HomeCard.max(HomeCard.toneIf(s.openCnt(), Tone.INFO), review),
                List.of(new Fact(ctx.msg("home.card.wmsCountOpen.review"), s.reviewCnt(), review)), items);
    }
}
