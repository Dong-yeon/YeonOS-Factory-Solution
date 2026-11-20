package io.smarthappy.module.srm.asn;

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
import io.smarthappy.module.srm.asn.AsnHomeMapper.AsnLateSummary;
import io.smarthappy.platform.common.query.DateRanges;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "늦은 ASN" (docs/37 23장) — 협력사가 보냈다고 알렸는데 도착 예정일이 지나도 덜 받은 ASN. 협력사 독촉 또는 입고 누락 확인.
 * 숫자 = ASN 목록 "늦은 ASN 만" + 기간(목록 상한 창, 회사 오늘까지)과 같은 건수 — 목록이 회사 날짜·공장 조건 없이 계산하므로 회사 카드(H21).
 * 창보다 오래 늦은 것은 보조 숫자로 따로 보인다(점검 카드 H22 와 같은 방식).
 */
@Component
@RequiredArgsConstructor
public class AsnLateCard implements HomeCardProvider {

    /** 목록 API 행 상한(AsnMapper findAsns) — 넘으면 숫자 링크를 주지 않는다 */
    static final int LIST_LIMIT = 2000;

    private final AsnHomeMapper mapper;
    private final DateRanges ranges;

    @Override
    public String code() {
        return "srm-asn-late";
    }

    @Override
    public String titleKey() {
        return "home.card.srmAsnLate";
    }

    @Override
    public String progCd() {
        return AsnService.PROG;
    }

    @Override
    public int sortNo() {
        return 860;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        LocalDate today = ctx.today();
        // 목록 기간 = 출하~도착 구간이 기간과 겹치는 ASN. 늦은 ASN 은 출하 ≤ 도착 < 오늘이라 "기간 끝 = 오늘" 이면 도착일만 본다
        LocalDate from = today.minusDays(ranges.maxDays(AsnService.SEARCH_MAX_DAYS) - 1L);
        AsnLateSummary s = mapper.asnLateSummary(from, today);
        List<Item> items = mapper.asnLateTop(today, HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.asnNo(), ctx.msg("home.card.srmAsnLate.item", r.custNm(), r.plantCd(), r.etaDate().toString(),
                        r.openLineCnt()), Tone.ALERT,
                        // 그 ASN 번호 + 도착 예정일 하루(겹침 조건이라 그 ASN 이 든다). 항목은 모두 늦은 ASN 이라 "늦은 ASN 만" 을 켜도 든다 —
                        // 빈 값은 목록 API 의 boolean 칸이 400 으로 거른다
                        Map.of("keyword", r.asnNo(), "status", "", "late", "true", "from", r.etaDate().toString(), "to", r.etaDate().toString())))
                .toList();
        Tone late = HomeCard.toneIf(s.lateCnt(), Tone.ALERT);
        Tone older = HomeCard.toneIf(s.olderCnt(), Tone.ALERT);
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact(ctx.msg("home.card.srmAsnLate.today"), s.todayCnt(), HomeCard.toneIf(s.todayCnt(), Tone.INFO)));
        if (s.olderCnt() > 0) {
            facts.add(new Fact(ctx.msg("home.card.srmAsnLate.older", from.toString()), s.olderCnt(), older));
        }
        Map<String, String> link = s.lateCnt() > 0 && s.lateCnt() <= LIST_LIMIT
                ? Map.of("late", "true", "status", "", "keyword", "", "from", from.toString(), "to", today.toString())
                : null;
        return new HomeCard(s.lateCnt(), HomeCard.max(late, older), facts, items, link);
    }
}
