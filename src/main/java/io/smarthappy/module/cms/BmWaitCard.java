package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.cms.CmsHomeMapper.BmWaitSummary;
import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCard.Fact;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.home.HomeCardProvider;
import io.smarthappy.module.home.HomeService;
import io.smarthappy.platform.common.query.DateRanges;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "진행 중 고장" (docs/37 17.3, 5단계) — 공장 카드(공장 조건만, 날짜는 회사). 숫자 = 고장(BM) 작업 중 신고·접수·작업 중(REQ·OPEN·WIP)
 * 이고 신고일이 창 [오늘 − (상한−1), 오늘] 안 — 작업 목록의 상태 선택지 "진행 중"(REQ,OPEN,WIP)과 같은 묶음이라 숫자 링크가 그대로 열린다(H24 수정).
 * 아직 손대지 않은 것(REQ·OPEN)과 설비 정지 중은 보조 숫자. 작업 목록이 신고 시각을 <b>회사 시간대</b>로 자르므로 창도 회사 날짜·회사 시간대(H21).
 * 창보다 오래된 대기는 보조 숫자(ALERT)로 따로 — 숫자 링크와 섞지 않는다(H22).
 */
@Component
@RequiredArgsConstructor
public class BmWaitCard implements HomeCardProvider {

    static final String STATUSES = "REQ,OPEN,WIP";

    private final CmsHomeMapper mapper;
    private final DateRanges ranges;

    @Override
    public String code() {
        return "cms-bm-open";
    }

    @Override
    public String titleKey() {
        return "home.card.cmsBmOpen";
    }

    @Override
    public String progCd() {
        return MwoService.PROG;
    }

    @Override
    public int sortNo() {
        return 530;
    }

    @Override
    public boolean plantScoped() {
        return true;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        ZoneId zone = ctx.now().getZone();
        LocalDate to = ctx.today();
        LocalDate from = to.minusDays(ranges.maxDays(MwoService.MAX_DAYS) - 1L);
        OffsetDateTime fromAt = from.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toAt = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        BmWaitSummary s = mapper.bmWaitSummary(fromAt, toAt, ctx.plantCd());
        List<Item> items = mapper.bmWaitTop(fromAt, toAt, ctx.plantCd(), HomeService.MAX_ITEMS).stream().map(r -> {
            String day = r.reportedAt().atZoneSameInstant(zone).toLocalDate().toString();
            boolean down = "Y".equals(r.downYn());
            return new Item(r.mwoNo(), ctx.msg(down ? "home.card.cmsBmOpen.itemDown" : "home.card.cmsBmOpen.item", r.title(),
                    ctx.msg("code.CMS_MWO_STATUS." + r.status()), day), down ? Tone.ALERT : "WIP".equals(r.status()) ? Tone.NEUTRAL : Tone.WARN,
                    ChkDueCard.withPlant(Map.of("keyword", r.mwoNo(), "status", STATUSES, "mwoType", "BM", "from", day, "to", day), ctx));
        }).toList();
        Tone down = HomeCard.toneIf(s.downCnt(), Tone.ALERT);
        Tone before = HomeCard.toneIf(s.beforeCnt(), Tone.ALERT);
        Tone untouched = HomeCard.toneIf(s.untouchedCnt(), Tone.WARN);
        List<Fact> facts = new java.util.ArrayList<>(List.of(new Fact(ctx.msg("home.card.cmsBmOpen.untouched"), s.untouchedCnt(), untouched),
                new Fact(ctx.msg("home.card.cmsBmOpen.down"), s.downCnt(), down)));
        if (s.beforeCnt() > 0) {
            facts.add(new Fact(ctx.msg("home.card.cmsBmOpen.before", from.toString()), s.beforeCnt(), before));
        }
        return new HomeCard(s.openCnt(), HomeCard.max(HomeCard.toneIf(s.openCnt(), Tone.INFO), untouched, down, before), facts, items,
                ChkDueCard.withPlant(Map.of("status", STATUSES, "mwoType", "BM", "scope", "ALL", "from", from.toString(),
                        "to", to.toString()), ctx));
    }
}
