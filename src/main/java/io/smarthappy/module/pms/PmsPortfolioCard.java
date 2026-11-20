package io.smarthappy.module.pms;

import java.time.LocalDate;
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
import io.smarthappy.module.pms.PortfolioDtos.PortRow;
import lombok.RequiredArgsConstructor;

/**
 * PMS 지연 프로젝트 (docs/32 PM5) — 진행 중 프로젝트 중 기준선 대비 늦어졌거나 지연 작업이 있는 것. 보조: 결재 중 게이트, 높음 이슈.
 * 사람별 집계 없음(R9). 항목 = 많이 늦은 순
 */
@Component
@RequiredArgsConstructor
public class PmsPortfolioCard implements HomeCardProvider {

    private final PortfolioMapper pm;
    private final PmsDays days;

    @Override
    public String code() {
        return "pms-portfolio";
    }

    @Override
    public String titleKey() {
        return "home.card.pmsPortfolio";
    }

    @Override
    public String progCd() {
        return PortfolioService.PROG;
    }

    @Override
    public int sortNo() {
        return 805;
    }

    /**
     * 숫자는 목록과 같은 조건(portWhere) 전체를 SQL 로 센다 — 예전에는 목록 500건을 읽어 세서 500 이 넘으면 조용히 작아졌다(홈 감사 ④).
     * 항목 = 지연 프로젝트 많이 늦은 순 상위(같은 조건)
     */
    @Override
    public HomeCard load(HomeCardContext ctx) {
        List<String> open = List.copyOf(TaskService.OPEN);
        LocalDate lateRef = days.lateRef(ctx.today());
        PortfolioDtos.CardStats s = pm.portfolioCardStats(null, null, open, null, null, ctx.today(), lateRef);
        long gates = s.getGateCnt();
        long high = s.getHighCnt();
        // 공수(docs/48 R-2): 확정 공수가 계획을 넘은 프로젝트(소진율 > 100%)
        long overBurn = s.getOverBurnCnt();
        List<Item> items = pm.findDelayedPortfolio(null, null, open, null, null, ctx.today(), lateRef, HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.getProjNo(),
                ctx.msg("home.card.pmsPortfolio.item", r.getProjNm(), r.getSlipDays() == null ? 0 : r.getSlipDays(), r.getLateCnt()),
                r.getSlipDays() != null && r.getSlipDays() > 0 ? Tone.ALERT : Tone.WARN, Map.of("keyword", r.getProjNo()))).toList();
        return new HomeCard(s.getDelayedCnt(), HomeCard.toneIf(s.getDelayedCnt(), Tone.WARN), List.of(
                new Fact(ctx.msg("home.card.pmsPortfolio.active"), s.getActiveCnt(), Tone.NEUTRAL),
                new Fact(ctx.msg("home.card.pmsPortfolio.gates"), gates, HomeCard.toneIf(gates, Tone.INFO)),
                new Fact(ctx.msg("home.card.pmsPortfolio.high"), high, HomeCard.toneIf(high, Tone.ALERT)),
                new Fact(ctx.msg("home.card.pmsPortfolio.overBurn"), overBurn, HomeCard.toneIf(overBurn, Tone.WARN))), items);
    }
}
