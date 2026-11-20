package io.smarthappy.module.wms.loccheck;

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
import io.smarthappy.module.wms.loccheck.LocCheckDtos.CardSummary;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "위치 정확도" (docs/55 2.5) — 공장 카드. 숫자 = 처리 안 된 불일치 줄(사람이 할 일), 보조 = 30일 확인 칸·일치 칸, 첫 항목 = 정확도 %.
 * 정확도만 숫자로 두면 할 일이 안 보인다. 항목 링크 = 그 점검의 공장·"미처리"(점검 화면)
 */
@Component
@RequiredArgsConstructor
public class LocCheckCard implements HomeCardProvider {

    static final int DAYS = 30;

    private final LocCheckMapper mapper;

    @Override
    public String code() {
        return "wms-loc-accuracy";
    }

    @Override
    public String titleKey() {
        return "home.card.wmsLocAccuracy";
    }

    @Override
    public String progCd() {
        return LocCheckService.PROG;
    }

    @Override
    public int sortNo() {
        return 765;
    }

    @Override
    public boolean plantScoped() {
        return true;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        CardSummary s = mapper.cardSummary(ctx.plantCd(), ctx.plantToday().minusDays(DAYS - 1), ctx.plantToday());
        String rate = s.doneCnt() == 0 ? "-" : String.valueOf(Math.round(s.accurateCnt() * 1000.0 / s.doneCnt()) / 10.0);
        List<Item> items = new java.util.ArrayList<>();
        items.add(new Item(ctx.msg("home.card.wmsLocAccuracy.rate", rate, DAYS), ctx.msg("home.card.wmsLocAccuracy.rateDetail", s.accurateCnt(),
                s.doneCnt()), Tone.NEUTRAL));
        mapper.cardOpen(ctx.plantCd(), HomeService.MAX_ITEMS - 1).forEach(r -> items.add(new Item(r.path(),
                ctx.msg("home.card.wmsLocAccuracy.item", r.plantCd(), r.checkDate().toString(), ctx.msg("code.WMS_LOC_CHECK_RESULT." + r.result()),
                        r.itemCd() == null ? "-" : r.itemCd() + (r.lotNo() == null ? "" : " · " + r.lotNo())),
                // 항목 링크 = 그 공장·그 점검일 하루 + 미처리만 — 기간을 넘기지 않으면 화면 기본(최근 7일) 밖의 오래된 항목이 목록에 없다(docs/37 22장)
                Tone.WARN, Map.of("plantCd", r.plantCd(), "openOnly", "true", "status", "", "from", r.checkDate().toString(),
                        "to", r.checkDate().toString()))));
        return new HomeCard(s.openCnt(), HomeCard.toneIf(s.openCnt(), Tone.WARN), List.of(
                new Fact(ctx.msg("home.card.wmsLocAccuracy.checked"), s.doneCnt(), Tone.NEUTRAL),
                new Fact(ctx.msg("home.card.wmsLocAccuracy.accurate"), s.accurateCnt(), Tone.NEUTRAL)), items);
    }
}
