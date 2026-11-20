package io.smarthappy.module.qms;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.home.HomeCardProvider;
import io.smarthappy.module.home.HomeService;
import io.smarthappy.module.plm.link.EcoLookup.EcoView;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "복귀 기한 지난 임시 변경" (docs/29_P3 51.2 RV-5·RV-6, docs/66 T-2). 숫자 = 표시 끝이 지났는데 표준 복귀 기록이 없는
 * 임시(TEMP) 변경 — 목록 필터 revertDue=OVERDUE 와 같은 함수(ChangeRecordService.overdueTemps)라 숫자를 누르면 같은 건수.
 */
@Component
@RequiredArgsConstructor
public class TempRevertOverdueCard implements HomeCardProvider {

    static final String DUE = "OVERDUE";

    private final ChangeRecordService records;

    @Override
    public String code() {
        return "qms-temp-overdue";
    }

    @Override
    public String titleKey() {
        return "home.card.qmsTempOverdue";
    }

    @Override
    public String progCd() {
        return ChangeService.PROG;
    }

    @Override
    public int sortNo() {
        return 665;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        List<EcoView> over = records.overdueTemps(ctx.today());
        List<Item> items = over.stream().limit(HomeService.MAX_ITEMS)
                .map(v -> new Item(v.ecoNo(), ctx.msg("home.card.qmsTempOverdue.item", v.title(), v.markUntil().toString()), Tone.ALERT,
                        Map.of("keyword", v.ecoNo(), "revertDue", DUE)))
                .toList();
        return new HomeCard(over.size(), HomeCard.toneIf(over.size(), Tone.ALERT), List.of(), items, Map.of("revertDue", DUE));
    }
}
