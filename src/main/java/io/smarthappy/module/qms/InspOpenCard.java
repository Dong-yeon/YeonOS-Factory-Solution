package io.smarthappy.module.qms;

import java.time.Duration;
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
import io.smarthappy.module.qms.QmsHomeMapper.InspOpenSummary;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "판정 대기 검사" (docs/37 18.1, 6단계). 숫자 = 판정 안 된 검사(OPEN) — 검사 실행 목록의 status=OPEN 과 같은 조건.
 * 목록은 최근 500건까지라, 숫자가 그보다 크면 숫자 링크를 두지 않는다(H27 — 잘린 목록이 숫자와 다르다).
 */
@Component
@RequiredArgsConstructor
public class InspOpenCard implements HomeCardProvider {

    static final Duration LONG_WAIT = Duration.ofDays(1);

    private final QmsHomeMapper mapper;

    @Override
    public String code() {
        return "qms-insp-open";
    }

    @Override
    public String titleKey() {
        return "home.card.qmsInspOpen";
    }

    @Override
    public String progCd() {
        return InspService.PROG;
    }

    @Override
    public int sortNo() {
        return 690;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        InspOpenSummary s = mapper.inspOpenSummary(ctx.now().minus(LONG_WAIT).toOffsetDateTime());
        List<Item> items = mapper.inspOpenTop(HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.inspNo(), ctx.msg("home.card.qmsInspOpen.item", ctx.msg("code.INSP_TYPE." + r.inspType()), r.itemCd(),
                        r.lotNo(), r.occurredAt().atZoneSameInstant(ctx.now().getZone()).toLocalDate().toString()),
                        r.occurredAt().isBefore(ctx.now().minus(LONG_WAIT).toOffsetDateTime()) ? Tone.WARN : Tone.NEUTRAL,
                        Map.of("keyword", r.inspNo(), "status", "OPEN")))
                .toList();
        Tone longWait = HomeCard.toneIf(s.longWaitCnt(), Tone.WARN);
        return new HomeCard(s.openCnt(), HomeCard.max(HomeCard.toneIf(s.openCnt(), Tone.INFO), longWait),
                List.of(new Fact(ctx.msg("code.INSP_TYPE.RECEIVE"), s.receiveCnt(), Tone.NEUTRAL),
                        new Fact(ctx.msg("home.card.qmsInspOpen.longWait"), s.longWaitCnt(), longWait)),
                items, s.openCnt() <= InspService.LIST_LIMIT ? Map.of("status", "OPEN") : null);
    }
}
