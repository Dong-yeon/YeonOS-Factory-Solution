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
import io.smarthappy.module.qms.SpcEventMapper.EventSearch;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "미처리 SPC 위반" (docs/29_P3 19장). 숫자 = 종결·무효 처리가 없는 위반(OPEN·ACKED), 보조 숫자 = 아직 아무도 확인하지 않은 것(OPEN).
 * 숫자 링크 = SPC 화면 위반 보기 + 상태 미종결(OPEN_ALL) — 숫자는 위반 목록의 건수 SQL({@link SpcEventMapper#countGrid}, 투영 표)을
 * 그대로 불러 세므로 숫자와 목록 건수가 같은 SQL 에서 나온다(docs/29_P3 22장).
 * 항목 톤 = 미확인은 확인 필요(WARN), 처리 중은 볼 것 있음(INFO). 항목은 링크 없음(위반 하나를 주소로 여는 조건이 없다).
 */
@Component
@RequiredArgsConstructor
public class SpcOpenCard implements HomeCardProvider {

    private final QmsHomeMapper mapper;
    private final SpcEventMapper events;

    @Override
    public String code() {
        return "qms-spc-open";
    }

    @Override
    public String titleKey() {
        return "home.card.qmsSpcOpen";
    }

    @Override
    public String progCd() {
        return SpcService.PROG;
    }

    @Override
    public int sortNo() {
        return 699;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        long open = events.countGrid(new EventSearch(null, List.of("OPEN", "ACKED"), null, null, null, null, false, false));
        long fresh = events.countGrid(new EventSearch(null, List.of("OPEN"), null, null, null, null, false, false));
        List<Item> items = mapper.spcOpenTop(HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.inspNo(), ctx.msg("home.card.qmsSpcOpen.item", r.ruleCd(), r.itemCd(), r.charNo(),
                        r.occurredAt().atZoneSameInstant(ctx.now().getZone()).toLocalDate().toString()), r.acked() ? Tone.INFO : Tone.WARN))
                .toList();
        return new HomeCard(open, HomeCard.toneIf(open, Tone.WARN),
                List.of(new Fact(ctx.msg("home.card.qmsSpcOpen.new"), fresh, HomeCard.toneIf(fresh, Tone.ALERT))), items,
                Map.of("view", "events", "status", "OPEN_ALL"));
    }
}
