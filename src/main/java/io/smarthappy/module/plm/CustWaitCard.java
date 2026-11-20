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
import io.smarthappy.module.plm.PlmHomeMapper.CustWaitSummary;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "고객 응답 대기" (docs/39 20.6·30장) — PPAP 판정 대기(제출됨) 또는 PCN 응답 기한 지남인 지시. 취소된 ECO 것은 세지 않는다.
 * 숫자 = 지시 수 — 목록 status=CUST_WAIT 와 같은 SQL 조건(PlmMapper.countEcos)으로 세서 숫자 링크와 목록 건수가 같다.
 * 보조 숫자는 PPAP·PCN 건수(한 지시에 둘 다·고객별 PCN 여럿일 수 있다). 목록 상한보다 많으면 링크 없음(docs/37 H27). 항목은 그 ECO 번호로 연다
 */
@Component
@RequiredArgsConstructor
public class CustWaitCard implements HomeCardProvider {

    private final PlmHomeMapper mapper;
    private final PlmMapper ecos;

    @Override
    public String code() {
        return "plm-cust-wait";
    }

    @Override
    public String titleKey() {
        return "home.card.plmCustWait";
    }

    @Override
    public String progCd() {
        return EcoService.PROG;
    }

    @Override
    public int sortNo() {
        return 987;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        CustWaitSummary s = mapper.custWaitSummary(ctx.today());
        List<Item> items = mapper.custWaitTop(ctx.today(), HomeService.MAX_ITEMS).stream()
                .map(r -> "PCN".equals(r.kind())
                        ? new Item(r.no(), ctx.msg("home.card.plmCustWait.itemPcn", r.ecoNo(), r.custCd(), r.day().toString()), Tone.WARN,
                                Map.of("keyword", r.ecoNo()))
                        : new Item(r.no(), ctx.msg("home.card.plmCustWait.itemPpap", r.custCd(), r.day().toString()), Tone.NEUTRAL,
                                Map.of("keyword", r.ecoNo())))
                .toList();
        long n = ecos.countEcos(null, null, null, null, ctx.today());
        return new HomeCard(n, HomeCard.toneIf(s.pcnOverdueCnt(), Tone.WARN),
                List.of(new Fact(ctx.msg("home.card.plmCustWait.ppap"), s.ppapCnt(), Tone.NEUTRAL),
                        new Fact(ctx.msg("home.card.plmCustWait.pcn"), s.pcnOverdueCnt(), HomeCard.toneIf(s.pcnOverdueCnt(), Tone.WARN))),
                items, n <= EcoService.LIST_LIMIT ? Map.of("status", EcoService.CUST_WAIT) : null);
    }
}
