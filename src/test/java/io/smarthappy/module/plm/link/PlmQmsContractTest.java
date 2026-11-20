package io.smarthappy.module.plm.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.plm.link.EcoLookup.EcoView;
import io.smarthappy.module.plm.link.EcoTrace.MarkedLot;
import io.smarthappy.platform.common.web.FieldErrors.FieldError;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QMS 2부와 맞닿는 PLM 계약 (docs/39 16장, docs/29_P3 11.3) — ① EcoLookup ② EcoTrace.markedLots ④ EcoSubmitRule(시험용 빈).
 * 다른 회사·없는 ECO 는 빈 값, 상신 규칙 오류는 ECO 칸 오류로 합쳐 400 이고 ECO 는 작성 중 그대로.
 */
@AutoConfigureMockMvc
@Import(PlmQmsContractTest.RuleConfig.class)
class PlmQmsContractTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    /** 시험용 상신 규칙 — 값이 있으면 그 ECO 를 거부(QMS 4M 세부 없음 흉내), 규칙이 본 ECO 상태도 남긴다 */
    static final AtomicReference<Long> REJECT = new AtomicReference<>();
    static final AtomicReference<String> SEEN_STATUS = new AtomicReference<>();

    @TestConfiguration
    static class RuleConfig {
        @Bean
        EcoSubmitRule testSubmitRule(JdbcTemplate jdbc) {
            return ecoId -> {
                // 규칙은 ECO 행 잠금 뒤·상태 변경 전에 불린다 → 같은 트랜잭션에서 DRAFT 가 보여야 한다
                SEEN_STATUS.set(jdbc.queryForObject("select status from plm_eco where eco_id = ?", String.class, ecoId));
                return Long.valueOf(ecoId).equals(REJECT.get())
                        ? List.of(new FieldError("qms4m", "fourM", "REQUIRED"), new FieldError(null, "qmsChange", "REQUIRED"))
                        : List.of();
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    EcoLookup lookup;

    @Autowired
    EcoTrace trace;

    String comp;
    long ownerId;
    long approverId;
    RequestPostProcessor owner;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PQC");
        // QMS 4M 상신 규칙(정책 QMS_4M_REQUIRED, 기본 Y)은 이 시험 범위 밖 — QMS 쪽 시험이 따로 본다(docs/29_P3 12.1)
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_4M_REQUIRED', 'N', 't', 't')", comp);
        ownerId = accounts.newUser(comp, "eng1", PW);
        approverId = accounts.newUser(comp, "mgr1", PW);
        accounts.grant(comp, ownerId, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        owner = accounts.as(comp, ownerId, "eng1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        seed(comp);
    }

    @AfterEach
    void reset() {
        REJECT.set(null);
        SEEN_STATUS.set(null);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        for (String i : new String[] {"FG", "FG2"}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", c, i, i);
        }
        accounts.exec(c, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'SPOT', '용접', 'WELD', 't', 't')", c);
        long cls = accounts.query(c, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'W', '용접기', 't', 't') returning class_id",
                Long.class, c);
        accounts.exec(c, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'EQ1', '용접기 1', ?, 't', 't')",
                c, cls);
    }

    private long id(String c, String sql, Object... args) {
        return accounts.query(c, sql, Long.class, args);
    }

    /** 4M ECO(작성 중) — 대상: FG 설비 EQ1, FG 공정 SPOT, FG2 품목 */
    private long eco4m(String c, long owner) {
        long eco = id(c, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, change_kind, mark_until, created_by, updated_by)
                values (?, 'ECO-4M', '용접기 교체', 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, 'PROCESS_4M', (now() at time zone 'Asia/Seoul')::date + 7, 't', 't') returning eco_id""", c, owner);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, equip_id, created_by, updated_by)
                values (?, ?, 1, 'EQUIP', (select item_id from mst_item where item_cd = 'FG'), (select equip_id from mst_equip where equip_cd = 'EQ1'), 't', 't')""", c, eco);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, proc_id, created_by, updated_by)
                values (?, ?, 2, 'PROC', (select item_id from mst_item where item_cd = 'FG'), (select proc_id from mst_proc where proc_cd = 'SPOT'), 't', 't')""", c, eco);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 3, 'ITEM', (select item_id from mst_item where item_cd = 'FG2'), 't', 't')""", c, eco);
        return eco;
    }

    private void release(String c, long eco) {
        accounts.exec(c, "update plm_eco set status = 'PENDING' where eco_id = ?", eco);
        accounts.exec(c, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", eco);
        accounts.exec(c, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", eco);
    }

    private long lot(String c, String no) {
        return id(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'REWORK', 1, unit_cd, now(), 't', 't' from mst_item where item_cd = 'FG' returning lot_id""", c, no);
    }

    @Test
    void 조회_계약_EcoLookup_은_머리와_유형별_대상_id_markedLots_는_도착_순() {
        long eco = eco4m(comp, ownerId);
        EcoView v = runner.call(comp, () -> lookup.eco(eco)).orElseThrow();
        assertThat(v.ecoNo()).isEqualTo("ECO-4M");
        assertThat(v.changeKind()).isEqualTo("PROCESS_4M");
        assertThat(v.status()).isEqualTo("DRAFT");
        assertThat(v.markLots()).isNull();
        assertThat(v.markUntil()).isEqualTo(LocalDate.parse(accounts.query(comp, "select ((now() at time zone 'Asia/Seoul')::date + 7)::text", String.class)));
        assertThat(v.itemIds()).containsExactly(id(comp, "select item_id from mst_item where item_cd = 'FG'"),
                id(comp, "select item_id from mst_item where item_cd = 'FG2'"));
        assertThat(v.equipIds()).containsExactly(id(comp, "select equip_id from mst_equip where equip_cd = 'EQ1'"));
        assertThat(v.procIds()).containsExactly(id(comp, "select proc_id from mst_proc where proc_cd = 'SPOT'"));
        assertThat(v.docRevIds()).isEmpty();
        assertThat(v.custApprovalStatus()).isEqualTo("NA");

        // 표시 LOT — 적용 뒤, 도착 순
        release(comp, eco);
        long l2 = lot(comp, "L-B");
        long l1 = lot(comp, "L-A");
        for (long[] m : new long[][] {{l2, 1}, {l1, 2}}) {
            accounts.exec(comp, """
                    insert into plm_eco_lot (comp_cd, eco_id, lot_id, mark_reason, seq_no, occurred_at, recorded_by)
                    values (?, ?, ?, '4M_WINDOW', ?, now(), 't')""", comp, eco, m[0], (int) m[1]);
        }
        List<MarkedLot> marks = runner.call(comp, () -> trace.markedLots(eco));
        assertThat(marks).extracting(MarkedLot::lotNo).containsExactly("L-B", "L-A");
        assertThat(marks).extracting(MarkedLot::reason).containsOnly("4M_WINDOW");
        assertThat(marks).extracting(MarkedLot::seqNo).containsExactly(1, 2);
        // 번호로(대소문자 무시)·여러 개 한 번에(없는 id·중복은 빠짐)
        assertThat(runner.call(comp, () -> lookup.byNo(" eco-4m "))).map(EcoView::ecoId).contains(eco);
        assertThat(runner.call(comp, () -> lookup.byNo("NOPE"))).isEmpty();
        assertThat(runner.call(comp, () -> lookup.byNo(null))).isEmpty();
        assertThat(runner.call(comp, () -> lookup.ecos(java.util.Arrays.asList(eco, 999_999_999L, eco, null))))
                .extracting(EcoView::ecoId).containsExactly(eco);
        assertThat(runner.call(comp, () -> lookup.ecos(List.of())).isEmpty()).isTrue();
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, EcoLookup.MAX_IDS + 1).boxed().toList();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> runner.call(comp, () -> lookup.ecos(tooMany))))
                .isInstanceOf(IllegalArgumentException.class);
        // PPAP 패키지(PMS PM7) — 없으면 빈 값, 있으면 상태·판정일
        assertThat(runner.call(comp, () -> lookup.ppap(eco))).isEmpty();
        accounts.exec(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', 'C1 고객', 'SALES', 't', 't')""", comp);
        long ppap = id(comp, """
                insert into plm_ppap (comp_cd, eco_id, cust_id, created_by, updated_by)
                values (?, ?, (select cust_id from mst_cust where cust_cd = 'C1'), 't', 't') returning ppap_id""", comp, eco);
        assertThat(runner.call(comp, () -> lookup.ppap(eco))).contains(new EcoLookup.PpapView(ppap, "DRAFT", null));
        accounts.exec(comp, "update plm_ppap set status = 'SUBMITTED', submitted_on = " + TestAccounts.COMP_TODAY + " where ppap_id = ?", ppap);
        accounts.exec(comp, "update plm_ppap set status = 'INTERIM', decided_on = " + TestAccounts.COMP_TODAY + " where ppap_id = ?", ppap);
        assertThat(runner.call(comp, () -> lookup.ppap(eco))).map(EcoLookup.PpapView::status).contains("INTERIM");
        assertThat(runner.call(comp, () -> lookup.ppap(eco))).map(EcoLookup.PpapView::decidedOn).contains(LocalDate.parse(today));
        // A사로 B사: B사 범위에서 A사 ECO 의 PPAP → 빈 값
        String compP = accounts.newComp("PQCP");
        assertThat(runner.call(compP, () -> lookup.ppap(eco))).isEmpty();
        // 없는 ECO
        assertThat(runner.call(comp, () -> lookup.ppap(999_999_999L))).isEmpty();
        assertThat(runner.call(comp, () -> lookup.eco(999_999_999L))).isEmpty();
        assertThat(runner.call(comp, () -> trace.markedLots(999_999_999L))).isEmpty();
    }

    @Test
    void 품목의_첫_LOT_표시는_적용된_ECO_의_FIRST_LOT_만_최근부터_상한까지() {
        // ECO 두 개(적용), FG LOT 3개 FIRST_LOT + 1개 4M_WINDOW, 다른 품목 LOT 1개 FIRST_LOT
        long e1 = eco4m(comp, ownerId);
        release(comp, e1);
        long e2 = id(comp, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, 'ECO-D', '도면', 'DESIGN', (now() at time zone 'Asia/Seoul')::date, ?, 't', 't') returning eco_id""", comp, ownerId);
        accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG'), 't', 't')""", comp, e2);
        release(comp, e2);
        long a = lot(comp, "FL-A");
        long b = lot(comp, "FL-B");
        long c = lot(comp, "FL-C");
        long w = lot(comp, "WIN-1");
        long other = id(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'FG2-1', item_id, 'REWORK', 1, unit_cd, now(), 't', 't' from mst_item where item_cd = 'FG2' returning lot_id""", comp);
        Object[][] marks = {{e1, a, "FIRST_LOT", 1}, {e1, w, "4M_WINDOW", 2}, {e2, b, "FIRST_LOT", 1}, {e2, other, "FIRST_LOT", 2},
            {e2, c, "FIRST_LOT", 3}};
        for (Object[] m : marks) {
            accounts.exec(comp, """
                    insert into plm_eco_lot (comp_cd, eco_id, lot_id, mark_reason, seq_no, occurred_at, recorded_by)
                    values (?, ?, ?, ?, ?, now(), 't')""", comp, m[0], m[1], m[2], m[3]);
        }
        long fg = id(comp, "select item_id from mst_item where item_cd = 'FG'");
        List<EcoTrace.FirstLot> all = runner.call(comp, () -> trace.firstLotsOfItem(fg, 10));
        // 최근 표시부터(넣은 순서의 반대), 4M_WINDOW·다른 품목 제외
        assertThat(all).extracting(EcoTrace.FirstLot::lotNo).containsExactly("FL-C", "FL-B", "FL-A");
        assertThat(all.get(0).ecoId()).isEqualTo(e2);
        assertThat(all.get(0).ecoNo()).isEqualTo("ECO-D");
        assertThat(all.get(0).seqNo()).isEqualTo(3);
        assertThat(runner.call(comp, () -> trace.firstLotsOfItem(fg, 2))).extracting(EcoTrace.FirstLot::lotNo).containsExactly("FL-C", "FL-B");
        assertThat(runner.call(comp, () -> trace.firstLotsOfItem(999_999_999L, 10))).isEmpty();
        for (int bad : new int[] {0, EcoTrace.MAX_FIRST_LOTS + 1}) {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> runner.call(comp, () -> trace.firstLotsOfItem(fg, bad))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // A사로 B사: B사 범위에서 A사 품목 id → 빈 목록
        String compB = accounts.newComp("PQCF");
        assertThat(runner.call(compB, () -> trace.firstLotsOfItem(fg, 10))).isEmpty();
    }

    @Test
    void B_A사로_B사_ECO_는_조회_계약에서_빈_값() {
        String compB = accounts.newComp("PQCB");
        long userB = accounts.newUser(compB, "engB", PW);
        seed(compB);
        long ecoB = eco4m(compB, userB);
        release(compB, ecoB);
        long lotB = lot(compB, "LB");
        accounts.exec(compB, """
                insert into plm_eco_lot (comp_cd, eco_id, lot_id, mark_reason, seq_no, occurred_at, recorded_by)
                values (?, ?, ?, '4M_WINDOW', 1, now(), 't')""", compB, ecoB, lotB);
        assertThat(runner.call(compB, () -> lookup.eco(ecoB))).isPresent();
        assertThat(runner.call(comp, () -> lookup.eco(ecoB))).isEmpty();
        assertThat(runner.call(comp, () -> lookup.byNo("ECO-4M"))).isEmpty();
        assertThat(runner.call(comp, () -> lookup.ecos(List.of(ecoB)))).isEmpty();
        assertThat(runner.call(comp, () -> trace.markedLots(ecoB))).isEmpty();
    }

    @Test
    void 상신_규칙_오류는_ECO_칸_오류로_합쳐지고_ECO_는_작성_중_그대로() throws Exception {
        long eco = eco4m(comp, ownerId);
        REJECT.set(eco);
        int ver = accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, eco);
        mvc.perform(post("/api/plm/ecos/" + eco + "/submit").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + ver + ",\"lines\":[{\"userId\":" + approverId + "}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey == 'qms4m')].field").value("fourM"))
                .andExpect(jsonPath("$.errors[?(@.field == 'qmsChange')].code").value("REQUIRED"));
        assertThat(SEEN_STATUS.get()).isEqualTo("DRAFT");
        assertThat(accounts.query(comp, "select status from plm_eco where eco_id = ?", String.class, eco)).isEqualTo("DRAFT");
        assertThat(accounts.query(comp, "select count(*) from plm_eco_target where eco_id = ? and base_row_ver is not null",
                Integer.class, eco)).isZero();
        // 규칙이 통과하면 그대로 상신
        REJECT.set(null);
        mvc.perform(post("/api/plm/ecos/" + eco + "/submit").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + ver + ",\"lines\":[{\"userId\":" + approverId + "}]}"))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from plm_eco where eco_id = ?", String.class, eco)).isEqualTo("PENDING");
    }
}
