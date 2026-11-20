package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.production.link.DefectQtyQuery;
import io.smarthappy.module.production.link.DefectQtyQuery.DefectQty;
import io.smarthappy.module.qms.CopqDtos.CopqView;
import io.smarthappy.module.qms.CopqDtos.LineRow;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;

/**
 * P3 QMS 29.4 COPQ 2단계 — 생산 실적 불량(MES DefectQtyQuery) × 그 달 말 표준단가: 손 계산·종류별 출처·정정 음수·단위 다름·단가 없는 달·
 * 달 중 단가 변경(그 달 말 값), A사로 B사(다른 회사 품목 줄은 빠짐), MES 없음(NONE). 계약의 월 경계·정정 규칙 자체는 MES DefectQtyQueryTest 가 덮는다.
 */
class CopqPrdTest extends QmsTestBase {

    @Autowired
    CopqMapper copqMapper;

    @Autowired
    BizClock clock;

    @Autowired
    InboxService inbox;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    long fg1;
    YearMonth month;
    final List<DefectQty> stub = new ArrayList<>();

    @BeforeEach
    void prdSetUp() {
        fg1 = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        month = YearMonth.from(today);
        stub.clear();
    }

    CopqService service(DefectQtyQuery q) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        if (q != null) {
            bf.registerSingleton("defects", q);
        }
        return new CopqService(copqMapper, clock, inbox, bf.getBeanProvider(DefectQtyQuery.class));
    }

    CopqView summary(String c, DefectQtyQuery q, YearMonth from, YearMonth to) {
        TransactionTemplate tx = new TransactionTemplate(txm);
        return runner.call(c, () -> tx.execute(s -> service(q).summary(from.toString(), to.toString())));
    }

    void price(String p) {
        accounts.exec(comp, "update mst_item set std_price = ?::numeric, updated_by = 't' where item_id = ?", p, fg1);
    }

    DefectQty row(YearMonth m, String kind, String qty, String unit, String source) {
        return new DefectQty(m, fg1, kind, new BigDecimal(qty), unit, source);
    }

    static String key(LineRow l) {
        return l.getMonth() + ":" + l.getSource() + ":" + l.getQty().stripTrailingZeros().toPlainString() + ":" + l.getUnitCd() + "="
                + (l.getAmount() == null ? l.getReason() : l.getAmount().stripTrailingZeros().toPlainString());
    }

    @Test
    void 생산_불량_곱하기_그_달_말_단가_정정_음수_단위_다름_단가_없는_달() {
        YearMonth prev = month.minusMonths(1);
        price("1000");   // 이번 달에 처음 넣은 단가 — 지난달 말에는 단가가 없다
        stub.add(row(month, "SCRAP", "5", "EA", DefectQtyQuery.RESULT));
        stub.add(row(month, "REWORK", "2", "EA", DefectQtyQuery.RESULT));
        stub.add(row(month, "REPAIR", "-1", "EA", DefectQtyQuery.RESULT));      // 지난 실적의 정정 — 정정한 달에 음수(C-1 (라))
        stub.add(row(month, "SCRAP", "3", "EA", DefectQtyQuery.REWORK));        // 재작업·수리 처리의 폐기
        stub.add(row(month, "SCRAP", "4", "KG", DefectQtyQuery.RESULT));        // 지시 단위가 품목 기본 단위(EA)와 다름
        stub.add(row(prev, "SCRAP", "2", "EA", DefectQtyQuery.RESULT));
        DefectQtyQuery q = (plant, from, to) -> stub.stream().filter(r -> !r.month().isBefore(from) && !r.month().isAfter(to)).toList();

        CopqView v = summary(comp, q, prev, month);
        assertThat(v.lines()).extracting(CopqPrdTest::key).containsExactlyInAnyOrder(
                month + ":PRD_SCRAP:5:EA=5000", month + ":PRD_REWORK:2:EA=2000", month + ":PRD_REPAIR:-1:EA=-1000",
                month + ":PRD_RW_SCRAP:3:EA=3000", month + ":PRD_SCRAP:4:KG=UNIT", prev + ":PRD_SCRAP:2:EA=NO_PRICE");
        assertThat(v.months().get(1).internal()).as("5+2-1+3 = 9 × 1000").isEqualByComparingTo("9000");
        assertThat(v.months().get(1).unpricedCnt()).as("단위 다른 줄 1").isEqualTo(1);
        assertThat(v.months().get(0).internal()).as("지난달은 단가 없음 — null(0 아님)").isNull();
        assertThat(v.months().get(0).unpricedCnt()).isEqualTo(1);
        assertThat(v.lines()).allMatch(l -> "INTERNAL".equals(l.getCategory()) && "FG1".equals(l.getItemCd()));

        // 달 중에 단가를 고치면 그 달 줄은 그 달 말(= 지금 마지막) 단가로
        price("2000");
        CopqView again = summary(comp, q, month, month);
        assertThat(again.months().get(0).internal()).isEqualByComparingTo("18000");
    }

    @Test
    void A사로_B사_다른_회사_품목_줄은_빠지고_MES_없으면_생산_줄_없음() {
        price("1000");
        stub.add(row(month, "SCRAP", "5", "EA", DefectQtyQuery.RESULT));
        DefectQtyQuery q = (plant, from, to) -> List.copyOf(stub);
        String other = accounts.newComp("QP");
        fixtures(other);
        // B사 범위에서 A사 품목 id 가 온다면(계약은 RLS 로 주지 않지만) — 품목·단가를 못 읽어 줄을 만들지 않는다
        CopqView b = summary(other, q, month, month);
        assertThat(b.lines()).isEmpty();
        assertThat(b.months().get(0).total()).isEqualByComparingTo("0");
        // MES 없는 설치본(NONE) — 생산 줄 없음, NCR·수동 행만
        CopqView none = summary(comp, null, month, month);
        assertThat(none.lines()).isEmpty();
        // 실제 MES 계약 빈(생산 실적 없음)도 줄 없음
        assertThat(runner.call(comp, () -> new TransactionTemplate(txm).execute(s -> copqService.summary(month.toString(), month.toString())))
                .lines()).isEmpty();
    }

    @Autowired
    CopqService copqService;
}
