package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.cms.CmsHomeMapper.StatePendingSummary;
import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCard.Fact;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.home.HomeCardProvider;
import io.smarthappy.module.home.HomeService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "사유 미입력 비가동" (docs/37 18.1, 6단계) — 공장 카드. 숫자 = 최근 7일(오늘 포함)과 겹치는, 원장 사유·사후 사유가 모두 없는
 * 정지·대기 구간(PRD·UNKNOWN 제외) — 상태 사유 검토 목록의 "사유 없음만" 과 같은 조건(H29).
 * 목록은 공장 필수 + 공장 현지 날짜 기간(최대 31일)이라 숫자 링크는 공장을 골랐을 때만, 그 공장 시간대 7일로.
 * 전체 공장이면 회사 시간대 7일로 세고 숫자 링크 없음. 항목 링크는 그 행의 공장·그 공장 날짜 하루로 연다.
 */
@Component
@RequiredArgsConstructor
public class StatePendingCard implements HomeCardProvider {

    static final int DAYS = 7;

    private final CmsHomeMapper mapper;
    private final BizClock clock;

    @Override
    public String code() {
        return "cms-state-pending";
    }

    @Override
    public String titleKey() {
        return "home.card.cmsStatePending";
    }

    @Override
    public String progCd() {
        return StateReviewService.PROG;
    }

    @Override
    public int sortNo() {
        return 610;
    }

    @Override
    public boolean plantScoped() {
        return true;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        ZoneId zone = ctx.plantNow().getZone();
        LocalDate to = ctx.plantToday();
        LocalDate from = to.minusDays(DAYS - 1L);
        var fromAt = from.atStartOfDay(zone).toOffsetDateTime();
        var toAt = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        StatePendingSummary s = mapper.statePendingSummary(fromAt, toAt, ctx.plantCd());
        Map<String, ZoneId> zones = new HashMap<>();
        List<Item> items = mapper.statePendingTop(fromAt, toAt, ctx.plantCd(), HomeService.MAX_ITEMS).stream().map(r -> {
            ZoneId z = zones.computeIfAbsent(r.plantCd(), clock::zone);
            String day = r.startAt().atZoneSameInstant(z).toLocalDate().toString();
            boolean open = r.endAt() == null;
            // 구간이 창 시작 전에 시작했으면 그 날이 목록 기간 밖 — 링크 날짜는 창 시작일로(겹침이라 그 날에도 걸린다)
            String linkDay = day.compareTo(from.toString()) < 0 && ctx.plantCd() != null ? from.toString() : day;
            return new Item(r.equipCd(), ctx.msg(open ? "home.card.cmsStatePending.itemOpen" : "home.card.cmsStatePending.item",
                    ctx.msg("code.STATE_CAT." + r.stateCat()), r.plantCd(), day), open ? Tone.WARN : Tone.NEUTRAL,
                    Map.of("plantCd", r.plantCd(), "from", linkDay, "to", linkDay, "keyword", r.equipCd(), "pending", "Y"));
        }).toList();
        Tone openTone = HomeCard.toneIf(s.openCnt(), Tone.WARN);
        Map<String, String> link = ctx.plantCd() == null ? null
                : Map.of("plantCd", ctx.plantCd(), "from", from.toString(), "to", to.toString(), "pending", "Y");
        return new HomeCard(s.pendingCnt(), HomeCard.max(HomeCard.toneIf(s.pendingCnt(), Tone.INFO), openTone),
                List.of(new Fact(ctx.msg("home.card.cmsStatePending.open"), s.openCnt(), openTone)), items, link);
    }
}
