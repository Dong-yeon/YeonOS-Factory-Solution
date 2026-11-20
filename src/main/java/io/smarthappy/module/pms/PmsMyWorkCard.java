package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.util.ArrayList;
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
import io.smarthappy.module.pms.PortfolioDtos.MyDeliv;
import io.smarthappy.module.pms.PortfolioDtos.MyTask;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * PMS 내 할 일 (docs/32 PM5, docs/37 4장) — 로그인 사용자 본인 것만: 지연·임박 작업, 미제출·반려 산출물(임박), 열린 이슈,
 * 확정 안 한 지난 평일. 끝난·취소된 것과 닫힌 프로젝트는 세지 않는다(R8). 지연이 있으면 ALERT
 */
@Component
@RequiredArgsConstructor
public class PmsMyWorkCard implements HomeCardProvider {

    private final PortfolioMapper pm;
    private final PortfolioService portfolio;
    private final PolicyService policy;
    private final PmsDays days;

    @Override
    public String code() {
        return "pms-my-work";
    }

    @Override
    public String titleKey() {
        return "home.card.pmsMyWork";
    }

    @Override
    public String progCd() {
        return PortfolioService.PROG;
    }

    @Override
    public int sortNo() {
        return 800;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        LocalDate today = ctx.today();
        LocalDate until = today.plusDays(policy.intValue(Policies.PMS_DUE_SOON_DAYS));
        LocalDate lateRef = days.lateRef(today);
        long user = ctx.userId();
        long tasks = pm.countMyTasks(user, lateRef, until, false);
        long lateTasks = pm.countMyTasks(user, lateRef, until, true);
        long delivs = pm.countMyDelivs(user, until);
        long issues = pm.countMyIssues(user);
        List<LocalDate> days = portfolio.unconfirmedDays(user, today);
        long total = tasks + delivs + issues + days.size();
        List<Item> items = new ArrayList<>();
        for (MyTask t : pm.findMyTasks(user, lateRef, until, HomeService.MAX_ITEMS)) {
            items.add(new Item(t.getProjNo(), ctx.msg("home.card.pmsMyWork.task", t.getTaskNm(), t.getPlanEnd().toString()),
                    t.isLate() ? Tone.ALERT : Tone.WARN, Map.of("keyword", t.getProjNo())));
        }
        for (MyDeliv d : pm.findMyDelivs(user, lateRef, until, HomeService.MAX_ITEMS)) {
            items.add(new Item(d.getProjNo(), ctx.msg("home.card.pmsMyWork.deliv", d.getDelivNm(), d.getDue().toString()),
                    d.isLate() ? Tone.ALERT : Tone.WARN, Map.of("keyword", d.getProjNo())));
        }
        if (!days.isEmpty()) {
            items.add(new Item(ctx.msg("home.card.pmsMyWork.hours"), days.get(days.size() - 1) + " ~ " + days.get(0), Tone.WARN));
        }
        Tone late = HomeCard.toneIf(lateTasks, Tone.ALERT);
        return new HomeCard(total, HomeCard.max(late, HomeCard.toneIf(total, Tone.WARN)), List.of(
                new Fact(ctx.msg("home.card.pmsMyWork.lateTasks"), lateTasks, late),
                new Fact(ctx.msg("home.card.pmsMyWork.delivs"), delivs, HomeCard.toneIf(delivs, Tone.WARN)),
                new Fact(ctx.msg("home.card.pmsMyWork.issues"), issues, Tone.NEUTRAL)),
                items.stream().limit(HomeService.MAX_ITEMS).toList());
    }
}
