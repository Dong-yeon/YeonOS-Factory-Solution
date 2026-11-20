package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 기한 지남 알림 (docs/39 26장) — 홈 카드와 같은 조건(승인됐는데 유효일 지남·열린 PCN 응답 기한 지남, 취소 지시 제외)으로
 * 지시 담당에게 사내 알림, 처음 한 번(대상 + 기준 날짜). 화면 권한·구독 규칙은 플랫폼 그대로, A사로 B사.
 */
class PlmOverdueNotifyTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlmOverdueNotifier notifier;

    @Autowired
    PlmJobs jobs;

    @Autowired
    io.smarthappy.platform.time.BizClock clock;

    String comp;
    long owner;
    long other;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PON");
        owner = accounts.newUser(comp, "eng1", PW);
        other = accounts.newUser(comp, "eng2", PW);
        accounts.grant(comp, owner, "ENG", "plm-eco", "SEARCH");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C1', 'C1', 'SALES', 't', 't')", comp);
    }

    private long eco(String no, String status, int effOffset, long ownerId) {
        long id = accounts.query(comp, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, ?, ?, 'QUALITY', cast(? as date) + ?, ?, 't', 't') returning eco_id""", Long.class,
                comp, no, no + " 제목", today, effOffset, ownerId);
        if (!"DRAFT".equals(status)) {
            accounts.exec(comp, "update plm_eco set status = 'PENDING' where eco_id = ?", id);
            accounts.exec(comp, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", id);
        }
        if ("RELEASED".equals(status)) {
            accounts.exec(comp, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", id);
        }
        if ("CANCELLED".equals(status)) {
            accounts.exec(comp, "update plm_eco set status = 'CANCELLED', cancel_reason = '시험' where eco_id = ?", id);
        }
        return id;
    }

    private long pcn(long ecoId, String no, int dueOffset) {
        return accounts.query(comp, """
                insert into plm_pcn (comp_cd, pcn_no, eco_id, cust_id, notified_on, response_due, created_by, updated_by)
                select ?, ?, ?, cust_id, cast(? as date) + least(?, 0), cast(? as date) + ?, 't', 't' from mst_cust where cust_cd = 'C1'
                returning pcn_id""", Long.class, comp, no, ecoId, today, dueOffset, today, dueOffset);
    }

    private List<String> notes(long userId) {
        String all = accounts.query(comp, "select coalesce(string_agg(event_cd || '|' || dedup_key, ',' order by notify_id), '') from sys_notify where user_id = ?",
                String.class, userId);
        return all.isEmpty() ? List.of() : List.of(all.split(","));
    }

    private PlmOverdueNotifier.Sent run(String c) {
        return runner.call(c, () -> notifier.run());
    }

    @Test
    void 유효일_지난_승인_지시와_기한_지난_통지만_담당에게_한_번() {
        long late = eco("E-LATE", "APPROVED", -1, owner);
        eco("E-TODAY", "APPROVED", 0, owner);          // 오늘 = 아직 안 지남
        eco("E-REL", "RELEASED", -5, owner);           // 적용됨
        eco("E-DRAFT", "DRAFT", -5, owner);            // 작성 중
        long open = eco("E-OPEN", "DRAFT", 3, owner);
        long cancelled = eco("E-CAN", "DRAFT", 3, owner);
        long p1 = pcn(open, "PCN-1", -1);              // 기한 어제 — 대상
        long p2 = pcn(eco("E-OPEN2", "DRAFT", 3, owner), "PCN-2", 0);   // 기한 오늘 — 아직 안 지남(임박 — 35장)
        long answered = eco("E-OPEN3", "DRAFT", 3, owner);
        pcn(answered, "PCN-4", -3);
        accounts.exec(comp, "update plm_pcn set status = 'ACK', responded_on = notified_on where pcn_no = 'PCN-4'");   // 응답 받음 — 제외
        pcn(cancelled, "PCN-3", -2);
        accounts.exec(comp, "update plm_eco set status = 'CANCELLED', cancel_reason = '시험' where eco_id = ?", cancelled);   // 취소 지시의 통지 — 제외

        PlmOverdueNotifier.Sent s = run(comp);
        assertThat(s).isEqualTo(new PlmOverdueNotifier.Sent(1, 1, 1));
        String effLate = accounts.query(comp, "select effective_date::text from plm_eco where eco_id = ?", String.class, late);
        String dueP1 = accounts.query(comp, "select response_due::text from plm_pcn where pcn_id = ?", String.class, p1);
        String dueP2 = accounts.query(comp, "select response_due::text from plm_pcn where pcn_id = ?", String.class, p2);
        assertThat(notes(owner)).containsExactly("PLM_ECO_OVERDUE|ECO:" + late + ":" + effLate, "PLM_PCN_OVERDUE|PCN:" + p1 + ":" + dueP1,
                "PLM_PCN_DUE_SOON|PCN-SOON:" + p2 + ":" + dueP2);
        // 링크 = 지시 화면 + 번호 검색
        assertThat(accounts.query(comp, "select link_prog_cd || ' ' || (link_params->>'keyword') from sys_notify where user_id = ? and event_cd = 'PLM_ECO_OVERDUE'",
                String.class, owner)).isEqualTo("plm-eco E-LATE");
        // 다시 돌려도(매시 작업) 같은 알림은 한 번
        assertThat(run(comp)).isEqualTo(new PlmOverdueNotifier.Sent(0, 0, 0));
        assertThat(notes(owner)).hasSize(3);
    }

    @Test
    void 기한_임박은_오늘부터_이틀_뒤까지_열린_통지만_한_번_지나면_지남_알림으로() {
        long open = eco("E-SOON", "DRAFT", 9, owner);
        long d2 = pcn(open, "PCN-D2", 2);                      // 이틀 뒤 — 임박
        pcn(eco("E-FAR", "DRAFT", 9, owner), "PCN-D3", 3);     // 사흘 뒤 — 아직
        long acked = eco("E-ACK", "DRAFT", 9, owner);
        pcn(acked, "PCN-ACK", 1);
        accounts.exec(comp, "update plm_pcn set status = 'ACK', responded_on = notified_on where pcn_no = 'PCN-ACK'");   // 응답 받음 — 제외
        long canc = eco("E-CANC", "DRAFT", 9, owner);
        pcn(canc, "PCN-CANC", 1);
        accounts.exec(comp, "update plm_eco set status = 'CANCELLED', cancel_reason = '시험' where eco_id = ?", canc);   // 취소 지시 — 제외
        assertThat(run(comp)).isEqualTo(new PlmOverdueNotifier.Sent(0, 0, 1));
        String due = accounts.query(comp, "select response_due::text from plm_pcn where pcn_id = ?", String.class, d2);
        assertThat(notes(owner)).containsExactly("PLM_PCN_DUE_SOON|PCN-SOON:" + d2 + ":" + due);
        assertThat(accounts.query(comp, "select link_prog_cd || ' ' || (link_params->>'keyword') from sys_notify where event_cd = 'PLM_PCN_DUE_SOON'",
                String.class)).isEqualTo("plm-eco E-SOON");
        // 다시 돌려도 한 번
        assertThat(run(comp)).isEqualTo(new PlmOverdueNotifier.Sent(0, 0, 0));
        // B사 범위 실행은 A사 통지를 보지 않는다
        assertThat(run(accounts.newComp("PONC"))).isEqualTo(new PlmOverdueNotifier.Sent(0, 0, 0));
        assertThat(notes(owner)).hasSize(1);
    }

    @Test
    void 경계_기한_오늘은_임박_다음_날은_지남_같은_통지는_두_알림을_각각_한_번() {
        long p = pcn(eco("E-EDGE", "DRAFT", 9, owner), "PCN-EDGE", 0);   // 기한 = 오늘
        String due = accounts.query(comp, "select response_due::text from plm_pcn where pcn_id = ?", String.class, p);
        assertThat(run(comp)).isEqualTo(new PlmOverdueNotifier.Sent(0, 0, 1));
        // 다음 날 — 기한 = 어제: 지남 알림(키 PCN:), 임박(키 PCN-SOON:)은 다시 오지 않는다
        clock.setClock(java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofDays(1)));
        assertThat(run(comp)).isEqualTo(new PlmOverdueNotifier.Sent(0, 1, 0));
        assertThat(run(comp)).isEqualTo(new PlmOverdueNotifier.Sent(0, 0, 0));
        assertThat(notes(owner)).containsExactly("PLM_PCN_DUE_SOON|PCN-SOON:" + p + ":" + due, "PLM_PCN_OVERDUE|PCN:" + p + ":" + due);
    }

    @org.junit.jupiter.api.AfterEach
    void resetClock() {
        clock.setClock(java.time.Clock.systemUTC());
    }

    @Test
    void 화면_권한_없는_담당_구독_끈_담당은_받지_않는다() {
        eco("E-OTHER", "APPROVED", -1, other);          // other 는 plm-eco 권한 없음
        long mine = eco("E-MINE", "APPROVED", -1, owner);
        accounts.exec(comp, "insert into sys_notify_sub (comp_cd, user_id, event_cd, inbox_yn, created_by, updated_by) values (?, ?, 'PLM_ECO_OVERDUE', 'N', 't', 't')",
                comp, owner);
        assertThat(run(comp)).isEqualTo(new PlmOverdueNotifier.Sent(0, 0, 0));
        assertThat(notes(other)).isEmpty();
        assertThat(notes(owner)).isEmpty();
        assertThat(mine).isPositive();
    }

    @Test
    void B_A사로_B사_다른_회사_범위의_실행은_A사_대상을_보지_않는다() {
        eco("E-LATE", "APPROVED", -1, owner);
        String compB = accounts.newComp("PONB");
        assertThat(run(compB)).isEqualTo(new PlmOverdueNotifier.Sent(0, 0, 0));
        assertThat(notes(owner)).isEmpty();
        // 정기 작업(모든 회사)은 A사 담당에게 한 번
        jobs.overdue();
        assertThat(notes(owner)).hasSize(1);
    }
}
