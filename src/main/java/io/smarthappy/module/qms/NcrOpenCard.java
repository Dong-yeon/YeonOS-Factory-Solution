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
import io.smarthappy.module.qms.QmsHomeMapper.NcrOpenSummary;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "열린 부적합" (docs/37 18.1, 6단계). 숫자 = OPEN·PENDING(특채 결재 중 포함, H30).
 * 숫자 링크 = status=OPEN_ALL(목록의 "열린 것" 묶음 = OPEN·PENDING) — QMS 가 부적합 화면을 조회 패널로 바꾸고 묶음 선택지를 더한 뒤 이었다
 * (H28 후속, 결정 (a)). 목록 상한(500)보다 많으면 링크 없음(H27).
 */
@Component
@RequiredArgsConstructor
public class NcrOpenCard implements HomeCardProvider {

    /** 부적합 목록 상태 칸의 "열린 것" 묶음 값(NcrService) */
    static final String OPEN_ALL = "OPEN_ALL";

    private final QmsHomeMapper mapper;

    @Override
    public String code() {
        return "qms-ncr-open";
    }

    @Override
    public String titleKey() {
        return "home.card.qmsNcrOpen";
    }

    @Override
    public String progCd() {
        return NcrService.PROG;
    }

    @Override
    public int sortNo() {
        return 697;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        NcrOpenSummary s = mapper.ncrOpenSummary();
        List<Item> items = mapper.ncrOpenTop(HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.ncrNo(), ctx.msg("home.card.qmsNcrOpen.item", ctx.msg("code.QMS_NCR_STATUS." + r.status()), r.itemCd(),
                        r.lotNo(), r.occurredAt().atZoneSameInstant(ctx.now().getZone()).toLocalDate().toString()),
                        "PENDING".equals(r.status()) ? Tone.NEUTRAL : Tone.WARN))
                .toList();
        return new HomeCard(s.openCnt(), HomeCard.toneIf(s.openCnt() - s.pendingCnt(), Tone.WARN),
                List.of(new Fact(ctx.msg("code.QMS_NCR_STATUS.PENDING"), s.pendingCnt(), Tone.NEUTRAL),
                        new Fact(ctx.msg("home.card.qmsNcrOpen.hold"), s.holdCnt(), Tone.NEUTRAL),
                        new Fact(ctx.msg("home.card.qmsNcrOpen.noDefect"), s.noDefectCnt(), HomeCard.toneIf(s.noDefectCnt(), Tone.WARN))),
                items, s.openCnt() <= NcrService.LIST_LIMIT ? Map.of("status", OPEN_ALL) : null);
    }
}
