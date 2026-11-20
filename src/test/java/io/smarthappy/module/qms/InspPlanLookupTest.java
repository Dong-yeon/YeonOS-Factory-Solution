package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.qms.link.InspPlanLookup;
import io.smarthappy.module.qms.link.InspPlanLookup.PlanKey;
import io.smarthappy.module.qms.link.InspPlanLookup.PlanLine;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * QMS 공개 계약 검사계획 조회 (docs/29_P3 18장, docs/41 A2) — APQP 관리계획서 대조가 읽는 값.
 * 그 날 유효한 확정본 하나(품목 > 클래스, 같은 공정 > 공정 무관) / 확정 스냅샷 규격 / 작성 중·기간 밖·공정 안 맞음은 빈 목록 /
 * 잘못된 인자 빈 값·예외 없음 / A사로 B사 / NONE.
 */
class InspPlanLookupTest extends QmsTestBase {

    @Autowired
    InspPlanLookup lookup;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    <T> T tx(String c, Supplier<T> s) {
        TransactionTemplate t = new TransactionTemplate(txm);
        return runner.call(c, () -> t.execute(st -> s.get()));
    }

    long item(String cd) {
        return accounts.query(comp, "select item_id from mst_item where item_cd = ?", Long.class, cd);
    }

    long proc(String cd) {
        return accounts.query(comp, "select proc_id from mst_proc where proc_cd = ?", Long.class, cd);
    }

    static String row(PlanLine l) {
        return l.lineNo() + " " + l.charNo() + " " + l.charNm() + " " + l.dataType() + " " + l.specialCls() + " "
                + (l.nominal() == null ? "-" : l.lsl().stripTrailingZeros().toPlainString() + "/" + l.nominal().stripTrailingZeros().toPlainString()
                        + "/" + l.usl().stripTrailingZeros().toPlainString() + l.unitNm())
                + " " + l.gaugeClass() + " " + l.samplingPlanCd() + " " + l.freqType() + (l.freqN() == null ? "" : ":" + l.freqN());
    }

    long finalPlan;
    long classPlan;
    long fg1;
    long rm1Item;
    long rm2Item;
    long draw;
    long iqc;

    @BeforeEach
    void lookupSetUp() throws Exception {
        fg1 = item("FG1");
        rm1Item = item("RM1");
        rm2Item = item("RM2");
        draw = proc("DRAW");
        iqc = proc("IQC");
        // FG1 출하검사(공정 무관): 홀 지름(계량, SC) 5개마다 + 외관(계수) 매 LOT
        finalPlan = plan("FINAL", "FG1", null, null);
        lines(finalPlan, line("a", null, 10, fgChar1, "S5", "CALIPER", "EVERY_N", 5), line("b", null, 20, fgChar2, "SALL", "VISUAL"))
                .andExpect(status().isOk());
        action(finalPlan, "release").andExpect(status().isOk());
        // 코일 클래스 수입검사(IQC 공정)
        classPlan = plan("RECEIVE", null, "COIL", "IQC");
        lines(classPlan, line("a", null, 10, iqcChar, "S5", "VISUAL")).andExpect(status().isOk());
        action(classPlan, "release").andExpect(status().isOk());
    }

    @Test
    void 그날_유효한_확정본_행_스냅샷_공정_클래스_고르기() throws Exception {
        List<PlanLine> fin = tx(comp, () -> lookup.lines(fg1, null, "FINAL", today));
        assertThat(fin).extracting(InspPlanLookupTest::row).containsExactly(
                "10 1 홀 지름 VARIABLE SC 9.95/10/10.05mm CALIPER S5 EVERY_N:5",
                "20 2 외관 ATTRIBUTE NONE - VISUAL SALL EVERY_LOT");
        assertThat(fin.get(0).inspPlanId()).isEqualTo(finalPlan);
        assertThat(fin.get(0).rev()).isNotBlank();
        assertThat(fin.get(0).methodNm()).isEqualTo("측정");
        assertThat(fin.get(0).reactionPlan()).isEqualTo("격리");
        assertThat(fin.get(0).scale()).isEqualTo(2);
        // 공정 무관 계획은 어느 공정으로 물어도 / 공정 계획은 그 공정으로만
        assertThat(tx(comp, () -> lookup.lines(fg1, draw, "FINAL", today))).hasSize(2);
        assertThat(tx(comp, () -> lookup.lines(rm1Item, iqc, "RECEIVE", today))).extracting(InspPlanLookupTest::row)
                .containsExactly("10 1 녹 없음 ATTRIBUTE NONE - VISUAL S5 EVERY_LOT");
        assertThat(tx(comp, () -> lookup.lines(rm1Item, null, "RECEIVE", today))).isEmpty();
        assertThat(tx(comp, () -> lookup.lines(rm1Item, draw, "RECEIVE", today))).isEmpty();
        // 클래스 없는 품목·다른 유형 / 기간 밖(확정 전 날)
        assertThat(tx(comp, () -> lookup.lines(fg1, iqc, "RECEIVE", today))).isEmpty();
        assertThat(tx(comp, () -> lookup.lines(fg1, null, "FINAL", today.minusDays(400)))).isEmpty();
        // 품목 계획이 클래스 계획보다 먼저: RM1 품목 수입검사 확정 → RM1 은 품목 계획, RM2 는 여전히 클래스 계획
        long rm1 = plan("RECEIVE", "RM1", null, "IQC");
        lines(rm1, line("a", null, 30, iqcChar, "SALL", "VISUAL")).andExpect(status().isOk());
        // 작성 중이면 아직 클래스 계획
        assertThat(tx(comp, () -> lookup.lines(rm1Item, iqc, "RECEIVE", today))).extracting(PlanLine::inspPlanId)
                .containsExactly(classPlan);
        action(rm1, "release").andExpect(status().isOk());
        assertThat(tx(comp, () -> lookup.lines(rm1Item, iqc, "RECEIVE", today))).extracting(l -> l.inspPlanId() + ":" + l.lineNo())
                .containsExactly(rm1 + ":30");
        assertThat(tx(comp, () -> lookup.lines(rm2Item, iqc, "RECEIVE", today))).extracting(PlanLine::inspPlanId)
                .containsExactly(classPlan);
        // 확정 뒤 특성 마스터를 바꿔도 확정 스냅샷 그대로
        accounts.exec(comp, "update qms_char set nominal = 11, usl = 11.5 where char_id = ?", fgChar1);
        assertThat(tx(comp, () -> lookup.lines(fg1, null, "FINAL", today)).get(0).nominal()).isEqualByComparingTo("10");
    }

    @Test
    void 잘못된_인자는_빈값_예외없음_A사로_B사_NONE() throws Exception {
        String after = tx(comp, () -> {
            assertThat(lookup.lines(fg1, null, "XX", today)).isEmpty();
            assertThat(lookup.lines(fg1, null, null, today)).isEmpty();
            assertThat(lookup.lines(fg1, null, "FINAL", null)).isEmpty();
            assertThat(lookup.lines(999_999_999L, null, "FINAL", today)).isEmpty();
            return lookup.lines(fg1, null, "FINAL", today).size() + "";   // 같은 트랜잭션 계속
        });
        assertThat(after).isEqualTo("2");
        // B사 범위에서 A사 품목 → 빈 목록, B사는 자기 확정본만
        String other = accounts.newComp("QP");
        fixtures(other);
        assertThat(tx(other, () -> lookup.lines(fg1, null, "FINAL", today))).isEmpty();
        long bItem = accounts.query(other, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        assertThat(tx(comp, () -> lookup.lines(bItem, null, "FINAL", today))).isEmpty();
        assertThat(InspPlanLookup.NONE.lines(fg1, null, "FINAL", today)).isEmpty();
    }

    static String key(PlanKey k) {
        return k.inspType() + " " + k.itemId() + " " + k.itemClassId() + " " + k.procId() + " " + k.altNo() + " " + k.rev() + " " + k.status();
    }

    /** keys(PMS M4) — 상태 무관(DRAFT·RELEASED), id 순, null·중복·없는 id 거름, 500 넘으면 나눠 읽어 모두, A사로 B사, NONE */
    @Test
    void 키_조회는_상태무관_없는_다른회사_빠짐_501개_이상도_모두_NONE() throws Exception {
        long draft = plan("PROCESS", "FG1", null, "DRAW");
        long classId = accounts.query(comp, "select class_id from mst_item_class where class_cd = 'COIL'", Long.class);
        List<Long> in = new ArrayList<>(Arrays.asList(classPlan, null, draft, finalPlan, finalPlan, 999_999_999L));
        assertThat(tx(comp, () -> lookup.keys(in))).extracting(PlanKey::inspPlanId).containsExactly(finalPlan, classPlan, draft);
        assertThat(tx(comp, () -> lookup.keys(in))).extracting(InspPlanLookupTest::key).containsExactly(
                "FINAL " + fg1 + " null null 1 A0 RELEASED",
                "RECEIVE null " + classId + " " + iqc + " 1 A0 RELEASED",
                "PROCESS " + fg1 + " null " + draw + " 1 A0 DRAFT");
        assertThat(tx(comp, () -> lookup.keys(null))).isEmpty();
        assertThat(tx(comp, () -> lookup.keys(List.of()))).isEmpty();
        // 501개 이상: 없는 id 600개(음수 — 정렬하면 앞) 뒤에 진짜 id → 둘째 묶음에 있어도 모두
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 600; i++) {
            many.add(-i);
        }
        many.addAll(List.of(draft, finalPlan, classPlan));
        assertThat(tx(comp, () -> lookup.keys(many))).extracting(PlanKey::inspPlanId).containsExactly(finalPlan, classPlan, draft);
        // A사로 B사: B사 범위에선 A사 id 가 빠지고, A사 범위에선 B사 id 가 빠짐
        String other = accounts.newComp("QK");
        fixtures(other);
        long bPlan = accounts.query(other, "insert into qms_insp_plan (comp_cd, insp_type, item_id, rev, valid_from, created_by, updated_by) "
                + "select ?, 'FINAL', item_id, 'A0', current_date, 't', 't' from mst_item where item_cd = 'FG1' returning insp_plan_id", Long.class, other);
        assertThat(tx(other, () -> lookup.keys(List.of(finalPlan, draft, bPlan)))).extracting(PlanKey::inspPlanId).containsExactly(bPlan);
        assertThat(tx(comp, () -> lookup.keys(List.of(finalPlan, bPlan)))).extracting(PlanKey::inspPlanId).containsExactly(finalPlan);
        assertThat(InspPlanLookup.NONE.keys(List.of(finalPlan))).isEmpty();
    }
}
