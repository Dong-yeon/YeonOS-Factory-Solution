package io.smarthappy.module.wms.inv;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCard.Fact;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.home.HomeCardProvider;
import io.smarthappy.module.wms.inv.InvDtos.ReconcileRow;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "재고 대사 불일치" (docs/44 S1) — 회사 카드(대사는 회사마다 한 번). 숫자 = 마지막 대사의 불일치 합
 * (원장 ≠ 현재고 키 + 입고 빠짐·초과·발주 불일치 + 품질 연동 판정 빠짐·무효 남음). 대사는 고치지 않으므로 사람이 볼 곳이 여기.
 * 항목 = 마지막 대사 시각 — 2일 넘게 안 돌았으면 경고(매일 03:40 작업이 멈췄을 수 있음). 링크 없음(현재고 화면 열기)
 */
@Component
@RequiredArgsConstructor
public class ReconcileCard implements HomeCardProvider {

    static final Duration STALE = Duration.ofDays(2);
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final InvMapper mapper;

    @Override
    public String code() {
        return "wms-reconcile";
    }

    @Override
    public String titleKey() {
        return "home.card.wmsReconcile";
    }

    @Override
    public String progCd() {
        return StockService.PROG_STOCK;
    }

    @Override
    public int sortNo() {
        return 750;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        ReconcileRow r = mapper.lastReconcile();
        if (r == null) {
            return new HomeCard(0, Tone.NEUTRAL, List.of(), List.of(new Item(ctx.msg("home.card.wmsReconcile.never"), "", Tone.WARN)));
        }
        long stock = r.getMismatchCount();
        long receipt = n(r.getMissingReceipts()) + n(r.getRcvMatOver()) + n(r.getRcvPoMismatch());
        long quality = n(r.getQcJudged()) + n(r.getQmsVoidLive());
        long total = stock + receipt + quality;
        boolean stale = r.getRunAt().toInstant().isBefore(ctx.now().toInstant().minus(STALE));
        Item last = new Item(r.getRunAt().atZoneSameInstant(ctx.now().getZone()).format(AT),
                ctx.msg(stale ? "home.card.wmsReconcile.stale" : "home.card.wmsReconcile.item", r.getKeyCount(), r.getRunBy()),
                stale ? Tone.WARN : Tone.NEUTRAL);
        return new HomeCard(total, HomeCard.max(HomeCard.toneIf(total, Tone.ALERT), stale ? Tone.WARN : Tone.NEUTRAL),
                List.of(new Fact(ctx.msg("home.card.wmsReconcile.stock"), stock, HomeCard.toneIf(stock, Tone.ALERT)),
                        new Fact(ctx.msg("home.card.wmsReconcile.receipt"), receipt, HomeCard.toneIf(receipt, Tone.ALERT)),
                        new Fact(ctx.msg("home.card.wmsReconcile.quality"), quality, HomeCard.toneIf(quality, Tone.ALERT))),
                List.of(last));
    }

    private static long n(Integer v) {
        return v == null ? 0 : v;
    }
}
