package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.production.SerialDtos.AltCand;
import io.smarthappy.module.production.SerialDtos.CompareLine;
import io.smarthappy.module.production.SerialDtos.Installed;
import io.smarthappy.module.production.SerialDtos.WoBomLine;

/** BOM 대비 순수 함수 (docs/77 5.2) — DB 없음 */
class SerialCompareTest {

    private static WoBomLine line(long item, String cd, String req, Integer op, String alt, Integer pri, String scrap) {
        WoBomLine l = new WoBomLine();
        l.setItemId(item);
        l.setItemCd(cd);
        l.setItemNm(cd);
        l.setUnitCd("EA");
        l.setReqQty(new BigDecimal(req));
        l.setOpSeq(op);
        l.setAltGrp(alt);
        l.setAltPriority(pri);
        l.setScrapPct(new BigDecimal(scrap));
        l.setSrcBomId(1);
        return l;
    }

    private static Installed got(long item, String cd, String qty) {
        Installed i = new Installed();
        i.setItemId(item);
        i.setItemCd(cd);
        i.setItemNm(cd);
        i.setUnitCd("EA");
        i.setQty(new BigDecimal(qty));
        return i;
    }

    private static AltCand cand(long item, String cd, int pri) {
        AltCand a = new AltCand();
        a.setBomId(1);
        a.setAltGrp("A");
        a.setItemId(item);
        a.setItemCd(cd);
        a.setItemNm(cd);
        a.setAltPriority(pri);
        return a;
    }

    @Test
    void 일대_소요는_스크랩을_빼고_계획_수량으로_나눈다_같은_품목_여러_행은_합() {
        // 계획 4대, 스크랩 10% → 스냅샷 4.4 → 1대 1. 같은 품목 두 행(공정 10·20) → 합 2, 공정은 앞쪽
        List<CompareLine> r = SerialCompare.compare(new BigDecimal("4"),
                List.of(line(1, "P", "4.4", 20, null, null, "10"), line(1, "P", "4", 10, null, null, "0")), List.of(), List.of(got(1, "P", "2")));
        assertThat(r).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("OK");
            assertThat(c.reqQty()).isEqualTo("2");
            assertThat(c.opSeq()).isEqualTo(10);
        });
    }

    @Test
    void 대체_그룹은_합으로_비율_0_대체품도_알아보고_주_품목이_아니면_SUBST() {
        List<WoBomLine> bom = List.of(line(1, "MAIN", "2", 10, "A", 1, "0"));
        List<AltCand> alts = List.of(cand(1, "MAIN", 1), cand(2, "ALT", 2));
        assertThat(SerialCompare.compare(new BigDecimal("2"), bom, alts, List.of(got(2, "ALT", "1"))))
                .singleElement().extracting(CompareLine::status).isEqualTo("SUBST");
        assertThat(SerialCompare.compare(new BigDecimal("2"), bom, alts, List.of(got(1, "MAIN", "1"))))
                .singleElement().extracting(CompareLine::status).isEqualTo("OK");
        // 둘이 반씩이라도 합이 모자라면 SHORT 가 먼저
        assertThat(SerialCompare.compare(new BigDecimal("1"), List.of(line(1, "MAIN", "2", 10, "A", 1, "0")), alts,
                List.of(got(2, "ALT", "1")))).singleElement().extracting(CompareLine::status).isEqualTo("SHORT");
    }

    @Test
    void 빠짐_초과_추가() {
        List<CompareLine> r = SerialCompare.compare(BigDecimal.ONE, List.of(line(1, "A", "1", null, null, null, "0"),
                line(2, "B", "1", null, null, null, "0")), List.of(), List.of(got(2, "B", "3"), got(9, "X", "1")));
        assertThat(r).extracting(c -> c.itemCd() + ":" + c.status()).containsExactly("A:MISSING", "B:OVER", "X:EXTRA");
        assertThat(SerialCompare.isShort(r.get(0))).isTrue();
        assertThat(SerialCompare.isShort(r.get(1))).isFalse();
    }
}
