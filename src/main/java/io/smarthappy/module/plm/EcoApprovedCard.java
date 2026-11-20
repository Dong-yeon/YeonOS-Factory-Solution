package io.smarthappy.module.plm;

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
import io.smarthappy.module.plm.PlmHomeMapper.ApprovedSummary;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "적용 대기 설계 변경" (docs/39 20.6). 숫자 = 결재 승인됐는데 아직 적용 안 한 ECO.
 * 유효일이 지난 것은 적용하면 거부되므로(D5) 경고. 숫자 링크 = status=APPROVED(ECO 목록 조회 조건 그대로), 목록 상한보다 많으면 없음
 */
@Component
@RequiredArgsConstructor
public class EcoApprovedCard implements HomeCardProvider {

    private final PlmHomeMapper mapper;

    @Override
    public String code() {
        return "plm-eco-approved";
    }

    @Override
    public String titleKey() {
        return "home.card.plmEcoApproved";
    }

    @Override
    public String progCd() {
        return EcoService.PROG;
    }

    @Override
    public int sortNo() {
        return 986;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        ApprovedSummary s = mapper.approvedSummary(ctx.today());
        List<Item> items = mapper.approvedTop(HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.ecoNo(), ctx.msg("home.card.plmEcoApproved.item", r.title(), r.effectiveDate().toString()),
                        r.effectiveDate().isBefore(ctx.today()) ? Tone.WARN : Tone.NEUTRAL, Map.of("keyword", r.ecoNo())))
                .toList();
        return new HomeCard(s.approvedCnt(), HomeCard.toneIf(s.pastCnt(), Tone.WARN),
                List.of(new Fact(ctx.msg("home.card.plmEcoApproved.past"), s.pastCnt(), HomeCard.toneIf(s.pastCnt(), Tone.WARN)),
                        new Fact(ctx.msg("home.card.plmEcoApproved.cust"), s.custWaitCnt(), Tone.NEUTRAL),
                        new Fact(ctx.msg("home.card.plmEcoApproved.stock"), s.stockOutCnt(), Tone.NEUTRAL)),
                items, s.approvedCnt() <= EcoService.LIST_LIMIT ? Map.of("status", "APPROVED") : null);
    }
}
