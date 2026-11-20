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
import io.smarthappy.module.qms.QmsHomeMapper.CapaDueSummary;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "시정조치 기한 지남" (docs/37 17.3, 5단계). 숫자 = 종결 기한이 지났는데 종결·취소되지 않은 시정조치(H23) —
 * 목록 dueState=CLOSE_OVERDUE 와 같은 조건이라 숫자를 누르면 같은 건수. 봉쇄 기한만 지난 것은 보조 숫자.
 * 날짜는 회사 날짜(목록이 BizClock.today() 로 판정 — H21).
 */
@Component
@RequiredArgsConstructor
public class CapaOverdueCard implements HomeCardProvider {

    static final String DUE_STATE = "CLOSE_OVERDUE";

    private final QmsHomeMapper mapper;

    @Override
    public String code() {
        return "qms-capa-overdue";
    }

    @Override
    public String titleKey() {
        return "home.card.qmsCapaOverdue";
    }

    @Override
    public String progCd() {
        return CapaService.PROG;
    }

    @Override
    public int sortNo() {
        return 660;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        CapaDueSummary s = mapper.capaDueSummary(ctx.today());
        List<Item> items = mapper.capaOverdueTop(ctx.today(), HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.capaNo(), ctx.msg("home.card.qmsCapaOverdue.item", r.title(), r.closeDue().toString()), Tone.ALERT,
                        Map.of("keyword", r.capaNo(), "dueState", DUE_STATE)))
                .toList();
        Tone contain = HomeCard.toneIf(s.containOverdueCnt(), Tone.WARN);
        return new HomeCard(s.closeOverdueCnt(), HomeCard.max(HomeCard.toneIf(s.closeOverdueCnt(), Tone.ALERT), contain),
                List.of(new Fact(ctx.msg("home.card.qmsCapaOverdue.contain"), s.containOverdueCnt(), contain)), items,
                Map.of("dueState", DUE_STATE));
    }
}
