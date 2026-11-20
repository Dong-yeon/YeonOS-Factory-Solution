package io.smarthappy.module.plm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.module.plm.link.EcoLookup.EcoPage;
import io.smarthappy.module.plm.link.EcoLookup.EcoView;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 품목별 ECO 계약 (docs/39 24장, 사용자 결정 2026-10-23) — releasedFor = 적용(RELEASED)·적용 시각, approvedFor = APPROVED·RELEASED·승인 시각,
 * since 는 회사 날짜(포함)·회사 시간대, 품목 범위 = 대상 행의 품목(EcoView.itemIds 정의), 상한 500 + truncated, EcoView 에 승인일·적용일.
 */
class PlmEcoForItemTest extends PostgresTestBase {

    private static final LocalDate D = LocalDate.parse("2026-09-10");

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    EcoLookup lookup;

    String comp;
    long owner;
    long x;
    long y;
    long proc;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PFI");
        owner = accounts.newUser(comp, "eng1", "Maple-anchor-63");
        for (String i : new String[] {"X", "Y"}) {
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", comp, i, i);
        }
        x = id("select item_id from mst_item where item_cd = 'X'");
        y = id("select item_id from mst_item where item_cd = 'Y'");
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'SPOT', '용접', 'WELD', 't', 't')", comp);
        proc = id("select proc_id from mst_proc where proc_cd = 'SPOT'");
    }

    private long id(String sql, Object... args) {
        return accounts.query(comp, sql, Long.class, args);
    }

    /** 작성 중 ECO + 대상 한 줄(ITEM 또는 PROC) */
    private long eco(String no, long item, boolean procTarget) {
        long eco = id("""
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, ?, ?, 'QUALITY', DATE '2026-09-01', ?, 't', 't') returning eco_id""", comp, no, no + " 제목", owner);
        if (procTarget) {
            accounts.exec(comp, """
                    insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, proc_id, created_by, updated_by)
                    values (?, ?, 1, 'PROC', ?, ?, 't', 't')""", comp, eco, item, proc);
        } else {
            accounts.exec(comp, """
                    insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                    values (?, ?, 1, 'ITEM', ?, 't', 't')""", comp, eco, item);
        }
        return eco;
    }

    private void approve(long eco, String at) {
        accounts.exec(comp, "update plm_eco set status = 'PENDING' where eco_id = ?", eco);
        accounts.exec(comp, "update plm_eco set status = 'APPROVED', approved_at = ?::timestamptz where eco_id = ?", at, eco);
    }

    private void release(long eco, String at) {
        accounts.exec(comp, "update plm_eco set status = 'RELEASED', released_at = ?::timestamptz where eco_id = ?", at, eco);
    }

    private EcoPage released(String c, long item, LocalDate since) {
        return runner.call(c, () -> lookup.releasedFor(item, since));
    }

    private EcoPage approved(String c, long item, LocalDate since) {
        return runner.call(c, () -> lookup.approvedFor(item, since));
    }

    private static List<String> nos(EcoPage p) {
        return p.rows().stream().map(EcoView::ecoNo).toList();
    }

    @Test
    void 적용_목록은_적용_시각_승인_목록은_승인_시각으로_회사_날짜_since_포함() throws Exception {
        long e1 = eco("E1", x, false);
        approve(e1, "2026-09-09T10:00:00+09:00");
        release(e1, "2026-09-10T00:00:00+09:00");           // 그날 0시 — 포함
        long e2 = eco("E2", x, true);                        // 공정 대상의 품목 = X
        approve(e2, "2026-09-10T08:00:00+09:00");            // 승인만
        long e3 = eco("E3", x, false);
        approve(e3, "2026-09-09T09:00:00+09:00");
        release(e3, "2026-09-09T23:59:59+09:00");           // 전날 끝 — 제외
        long e4 = eco("E4", x, false);
        approve(e4, "2026-09-11T09:00:00+09:00");
        accounts.exec(comp, "update plm_eco set status = 'CANCELLED', cancel_reason = '보류' where eco_id = ?", e4);   // 승인 뒤 취소 — 제외
        long e5 = eco("E5", y, false);
        approve(e5, "2026-09-12T09:00:00+09:00");
        release(e5, "2026-09-12T10:00:00+09:00");           // 다른 품목 — 제외
        eco("E6", x, false);                                  // 작성 중 — 제외
        long e7 = eco("E7", x, false);
        approve(e7, "2026-09-10T12:00:00+09:00");
        release(e7, "2026-09-10T15:30:00Z");                 // UTC 로는 9/10, 회사(서울)로는 9/11 00:30

        EcoPage rel = released(comp, x, D);
        assertThat(nos(rel)).containsExactly("E1", "E7");
        assertThat(rel.truncated()).isFalse();
        EcoView v1 = rel.rows().get(0);
        assertThat(v1.approvedOn()).isEqualTo(LocalDate.parse("2026-09-09"));
        assertThat(v1.releasedOn()).isEqualTo(D);
        assertThat(v1.itemIds()).containsExactly(x);
        assertThat(rel.rows().get(1).releasedOn()).isEqualTo(LocalDate.parse("2026-09-11"));   // 회사 시간대 날짜

        assertThat(nos(approved(comp, x, D))).containsExactly("E2", "E7");
        // 하루 뒤로 — 9/11 0시 이후 적용은 E7 뿐, 승인은 없음(E4 는 취소)
        assertThat(nos(released(comp, x, D.plusDays(1)))).containsExactly("E7");
        assertThat(nos(approved(comp, x, D.plusDays(1)))).isEmpty();
        // 기존 eco(id)·ecos(ids) 에도 날짜 칸 — 승인 전은 null
        EcoView e2v = runner.call(comp, () -> lookup.eco(e2)).orElseThrow();
        assertThat(e2v.approvedOn()).isEqualTo(D);
        assertThat(e2v.releasedOn()).isNull();
        long e6 = id("select eco_id from plm_eco where eco_no = 'E6'");
        assertThat(runner.call(comp, () -> lookup.ecos(List.of(e6))).get(0).approvedOn()).isNull();
    }

    @Test
    void since_null_없는_품목_다른_회사는_빈_페이지_예외_없음() {
        long e1 = eco("E1", x, false);
        approve(e1, "2026-09-10T10:00:00+09:00");
        release(e1, "2026-09-10T11:00:00+09:00");
        assertThat(released(comp, x, null)).isEqualTo(EcoPage.EMPTY);
        assertThat(approved(comp, x, null)).isEqualTo(EcoPage.EMPTY);
        assertThat(released(comp, 999_999_999L, D).rows()).isEmpty();
        // A사로 B사 — B사 범위에서 A사 품목 id 를 물어도 빈 페이지(RLS)
        String compB = accounts.newComp("PFIB");
        assertThat(released(compB, x, D).rows()).isEmpty();
        assertThat(approved(compB, x, D).rows()).isEmpty();
        assertThat(nos(released(comp, x, D))).containsExactly("E1");
    }

    @Test
    void 상한을_넘으면_앞쪽_500건과_truncated() {
        accounts.exec(comp, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                select ?, 'B' || lpad(g::text, 4, '0'), '대량 ' || g, 'QUALITY', DATE '2026-09-01', ?, 't', 't' from generate_series(1, 501) g""",
                comp, owner);
        accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                select ?, eco_id, 1, 'ITEM', ?, 't', 't' from plm_eco where eco_no like 'B%'""", comp, x);
        accounts.exec(comp, "update plm_eco set status = 'PENDING' where eco_no like 'B%'");
        accounts.exec(comp, """
                update plm_eco set status = 'APPROVED',
                       approved_at = timestamptz '2026-09-10 09:00:00+09' + (substr(eco_no, 2)::int * interval '1 second')
                 where eco_no like 'B%'""");
        EcoPage p = approved(comp, x, D);
        assertThat(p.rows()).hasSize(EcoLookup.MAX_IDS);
        assertThat(p.truncated()).isTrue();
        // 승인 시각 순 — 처음과 500번째
        assertThat(p.rows().get(0).ecoNo()).isEqualTo("B0001");
        assertThat(p.rows().get(EcoLookup.MAX_IDS - 1).ecoNo()).isEqualTo("B0500");
        assertThat(released(comp, x, D)).isEqualTo(EcoPage.EMPTY);
    }
}
