package io.smarthappy.module.qms;

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
import io.smarthappy.module.qms.QmsHomeMapper.ContainOpenSummary;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "열린 봉쇄" (docs/37 18.1, 6단계). 숫자 = OPEN 봉쇄, 보조 숫자 = 아직 풀리지 않은 봉쇄 LOT.
 * 숫자 링크 = status=OPEN — QMS 가 봉쇄 화면을 조회 패널(상태 칸 포함)로 바꾼 뒤 이었다(H28 후속). 목록 상한(300)보다 많으면 링크 없음(H27).
 */
@Component
@RequiredArgsConstructor
public class ContainOpenCard implements HomeCardProvider {

    private final QmsHomeMapper mapper;

    @Override
    public String code() {
        return "qms-contain-open";
    }

    @Override
    public String titleKey() {
        return "home.card.qmsContainOpen";
    }

    @Override
    public String progCd() {
        return ContainService.PROG;
    }

    @Override
    public int sortNo() {
        return 698;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        ContainOpenSummary s = mapper.containOpenSummary();
        List<Item> items = mapper.containOpenTop(HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.containNo(), ctx.msg("home.card.qmsContainOpen.item", r.itemCd(), r.reason(), r.heldLotCnt(),
                        r.createdAt().atZoneSameInstant(ctx.now().getZone()).toLocalDate().toString()), Tone.WARN))
                .toList();
        return new HomeCard(s.openCnt(), HomeCard.toneIf(s.openCnt(), Tone.WARN),
                List.of(new Fact(ctx.msg("home.card.qmsContainOpen.lots"), s.heldLotCnt(), HomeCard.toneIf(s.heldLotCnt(), Tone.WARN))), items,
                s.openCnt() <= ContainService.LIST_LIMIT ? Map.of("status", "OPEN") : null);
    }
}
