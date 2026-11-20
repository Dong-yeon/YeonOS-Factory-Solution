package io.smarthappy.module.production;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.home.HomeCardProvider;
import io.smarthappy.module.home.HomeService;
import lombok.RequiredArgsConstructor;

/** 보류 LOT (docs/37 6장) — 잔량이 남은 보류 LOT 만(다 쓴 LOT 은 할 일이 아니다). 해제·판정은 LOT 추적 화면에서 */
@Component
@RequiredArgsConstructor
public class LotHoldCard implements HomeCardProvider {

    private final ProductionHomeMapper mapper;

    @Override
    public String code() {
        return "prd-lot-hold";
    }

    @Override
    public String titleKey() {
        return "home.card.prdLotHold";
    }

    @Override
    public String progCd() {
        return LotService.PROG;
    }

    @Override
    public int sortNo() {
        return 410;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        long n = mapper.lotHoldCount();
        List<Item> items = mapper.lotHoldTop(ctx.now().getZone().getId(), HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.lotNo(), ctx.msg("home.card.prdLotHold.item", r.itemCd(), r.balance(), r.unitCd(),
                        r.holdReason()), Tone.WARN,
                        // LOT 추적 조회 조건: LOT 번호 + 만든 날 하루(목록 기간 = 등록일)
                        Map.of("keyword", r.lotNo(), "from", r.createdOn().toString(), "to", r.createdOn().toString())))
                .toList();
        // 카드 숫자 = 목록(docs/37 16.2): 보류만(보류 + 잔량 > 0) + 기간 비움(기본 30일을 지워 열린 기간)
        return new HomeCard(n, HomeCard.toneIf(n, Tone.WARN), List.of(), items, Map.of("holdOnly", "Y", "from", "", "to", ""));
    }
}
