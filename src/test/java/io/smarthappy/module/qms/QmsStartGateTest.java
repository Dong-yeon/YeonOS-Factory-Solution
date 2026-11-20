package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.production.link.StartGate;
import io.smarthappy.module.production.link.StartGate.GateReason;
import io.smarthappy.module.production.link.StartGate.GateVerdict;
import io.smarthappy.module.production.link.StartGate.StartContext;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.TestAccounts;

/**
 * P3 QMS 2부 Q9 착수 게이트 QMS 규칙 (docs/29_P3 12.9·12.11) — MES StartGate 구현 빈을 착수 트랜잭션처럼 불러 본다.
 * Q9-1 초품 미판정(다른 지시가 만든 첫 LOT 만, 판정·무효·지시 자신 제외, 이유 상한) / Q9-2 계측기(공정 검사계획의 클래스 × 쓸 수 있는 게이지,
 * 작업일 기준 기한) / 정책 OFF·WARN·BLOCK / 문구 키 ko·en / A사로 B사(같은 품목 id 로 B사 범위 → 없음).
 */
class QmsStartGateTest extends QmsTestBase {

    @Autowired
    StartGate gate;     // 이 앱에서 StartGate 빈은 QMS 하나

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    MessageSource messages;

    long item;
    long draw;
    long eco;
    long woMine;
    long lReceived;
    long lMine;

    @BeforeEach
    void gateSetUp() throws Exception {
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        draw = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'DRAW'", Long.class);
        eco = releasedEco(comp, "ECO-G-1");
        woMine = owner("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, status, "
                + "released_at, created_by, updated_by) values (?, 'WO-G1', 'P1', ?, 100, 'EA', " + TestAccounts.COMP_TODAY + ", "
                + TestAccounts.COMP_TODAY + ", 0, 'RELEASED', now(), 't', 't') returning wo_id", comp, item);
        lReceived = lot("L-G-R", null);
        lMine = lot("L-G-M", woMine);
        mark(eco, lReceived, 1);
        mark(eco, lMine, 2);
    }

    long releasedEco(String c, String no) {
        long e = accounts.query(c, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, ?, '도면 개정', 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, 't', 't') returning eco_id""", Long.class, c, no, userId);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG1'), 't', 't')""", c, e);
        accounts.exec(c, "update plm_eco set status = 'PENDING' where eco_id = ?", e);
        accounts.exec(c, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", e);
        accounts.exec(c, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", e);
        return e;
    }

    /** 생산 LOT(지시 있음 — 발행 지시 확인 트리거를 건너뛰려고 소유자·replica) 또는 입고 LOT(지시 없음) */
    long lot(String no, Long wo) throws Exception {
        return owner("insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, wo_id, occurred_at, created_by, updated_by) "
                + "values (?, ?, ?, ?, 10, 'EA', ?, now(), 't', 't') returning lot_id", comp, no, item, wo == null ? "RECEIVED" : "PRODUCED", wo);
    }

    void mark(long e, long lot, int seq) {
        accounts.exec(comp, "insert into plm_eco_lot (comp_cd, eco_id, lot_id, mark_reason, seq_no, occurred_at, recorded_by) "
                + "values (?, ?, ?, 'FIRST_LOT', ?, now(), 't')", comp, e, lot, seq);
    }

    /** 초품 판정 한 줄(근거 검사 없이 — 게이트는 살아 있는 판정 여부만 본다) */
    long verif(long e, long lot, String status) throws Exception {
        return verif(e, lot, status, "OK");
    }

    long verif(long e, long lot, String status, String result) throws Exception {
        return owner("insert into qms_setup_verif (comp_cd, eco_id, lot_id, insp_id, result, status, void_reason, voided_at, voided_by, "
                + "created_by, updated_by) values (?, ?, ?, 0, ?, ?, ?, ?, ?, 't', 't') returning verif_id", comp, e, lot, result, status,
                "VOID".equals(status) ? "다시" : null, "VOID".equals(status) ? java.sql.Timestamp.from(Instant.now()) : null,
                "VOID".equals(status) ? "t" : null);
    }

    static long owner(String sql, Object... args) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            try (Statement st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    StartContext ctx(long woId, LocalDate day) {
        return new StartContext("P1", woId, 1, draw, item, null, userId, 1, day, Instant.now(), false);
    }

    GateVerdict check(String c, StartContext ctx) {
        TransactionTemplate tx = new TransactionTemplate(txm);
        return runner.call(c, () -> tx.execute(s -> gate.check(ctx)));
    }

    static List<String> refs(GateVerdict v) {
        return v.reasons().stream().map(r -> r.code() + ":" + r.args()).toList();
    }

    // ---------- Q9-1 초품 미판정 ----------

    @Test
    void 초품_미판정_다른_지시의_첫LOT_만_판정_있으면_빠지고_무효면_다시() throws Exception {
        assertThat(gate.key()).isEqualTo("QMS");
        // 착수 지시 자신이 만든 첫 LOT(L-G-M)은 막지 않는다 → 입고 LOT 하나만
        GateVerdict v = check(comp, ctx(woMine, today));
        assertThat(v.mode()).isEqualTo(StartGate.WARN);
        assertThat(refs(v)).containsExactly("QMS_FIRST_PENDING:[L-G-R, ECO-G-1]");
        assertThat(v.reasons().get(0).refType() + ":" + v.reasons().get(0).refId()).isEqualTo("LOT:" + lReceived);
        // 다른 지시가 착수하면 둘 다(최근 표시부터)
        assertThat(refs(check(comp, ctx(woMine + 1000, today)))).containsExactly("QMS_FIRST_PENDING:[L-G-M, ECO-G-1]",
                "QMS_FIRST_PENDING:[L-G-R, ECO-G-1]");
        // 판정이 있으면 빠지고, 무효(VOID)만 남으면 다시 나온다
        long vf = verif(eco, lReceived, "ACTIVE");
        assertThat(check(comp, ctx(woMine, today))).isEqualTo(GateVerdict.PASS);
        owner("update qms_setup_verif set status = 'VOID', void_reason = '근거 오류', voided_at = now(), voided_by = 't' where verif_id = ? "
                + "returning verif_id", vf);
        assertThat(refs(check(comp, ctx(woMine, today)))).containsExactly("QMS_FIRST_PENDING:[L-G-R, ECO-G-1]");
        // 다른 ECO 의 판정은 이 ECO 첫 LOT 을 덮지 않는다
        long eco2 = releasedEco(comp, "ECO-G-2");
        verif(eco2, lReceived, "ACTIVE");
        assertThat(refs(check(comp, ctx(woMine, today)))).hasSize(1);
        // 문구 키는 QMS ko/en 에 있다(MES 가 요청 로캘로 푼다)
        GateReason r = check(comp, ctx(woMine, today)).reasons().get(0);
        assertThat(messages.getMessage(r.messageKey(), r.args().toArray(), Locale.KOREAN)).contains("L-G-R").contains("ECO-G-1");
        assertThat(messages.getMessage(r.messageKey(), r.args().toArray(), Locale.ENGLISH)).contains("L-G-R").contains("first-article");
    }

    @Test
    void 이유는_LOT_3개_뒤_n건_더_정책_OFF_BLOCK() throws Exception {
        for (int i = 1; i <= 4; i++) {
            mark(eco, lot("L-G-X" + i, null), 10 + i);
        }
        GateVerdict v = check(comp, ctx(woMine, today));
        assertThat(v.reasons()).hasSize(4);
        assertThat(refs(v).get(3)).isEqualTo("QMS_FIRST_PENDING:[2]");    // 입고 5개 중 3개 낱낱이 + 2건 더
        assertThat(v.reasons().get(3).messageKey()).isEqualTo("qms.gate.firstPendingMore");
        policy("QMS_GATE_FIRST_PENDING", "BLOCK");
        assertThat(check(comp, ctx(woMine, today)).mode()).isEqualTo(StartGate.BLOCK);
        accounts.exec(comp, "update sys_policy set policy_value = 'OFF' where policy_key = 'QMS_GATE_FIRST_PENDING'");
        assertThat(check(comp, ctx(woMine, today))).isEqualTo(GateVerdict.PASS);
        // 미리보기도 같은 판정(읽기만)
        accounts.exec(comp, "update sys_policy set policy_value = 'WARN' where policy_key = 'QMS_GATE_FIRST_PENDING'");
        StartContext p = new StartContext("P1", woMine, 1, draw, item, null, userId, 1, today, Instant.now(), true);
        assertThat(check(comp, p).reasons()).hasSize(4);
    }

    /** 부적합(근거 검사·불량 유형 없이 — 게이트는 LOT 의 열린 NCR 만 본다) */
    long ncr(String no, long lot) throws Exception {
        return owner("insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, defect_id, nc_qty, description, occurred_at, status, "
                + "hold_yn, created_by, updated_by) values (?, ?, 'SETUP', ?, ?, 'EA', 0, 1, '초품 NG', now(), 'OPEN', 'N', 't', 't') returning ncr_id",
                comp, no, lot, item);
    }

    @Test
    void 초품_NG_인_첫LOT_에_열린_부적합이_있으면_경고_종결되면_통과_착수_지시_LOT_도() throws Exception {
        // 두 첫 LOT 모두 판정: 입고 LOT = OK, 착수 지시가 만든 LOT = NG → NCR 없으면 통과
        verif(eco, lReceived, "ACTIVE", "OK");
        verif(eco, lMine, "ACTIVE", "NG");
        assertThat(check(comp, ctx(woMine, today))).isEqualTo(GateVerdict.PASS);
        // NG LOT 에 열린 부적합 → 경고(착수 지시가 만든 LOT 이어도), 이유에 NCR 번호
        long n1 = ncr("NC-G-1", lMine);
        GateVerdict v = check(comp, ctx(woMine, today));
        assertThat(v.mode()).isEqualTo(StartGate.WARN);
        assertThat(refs(v)).containsExactly("QMS_FIRST_NG:[L-G-M, ECO-G-1, NC-G-1]");
        assertThat(v.reasons().get(0).refType() + ":" + v.reasons().get(0).refId()).isEqualTo("LOT:" + lMine);
        GateReason r = v.reasons().get(0);
        assertThat(messages.getMessage(r.messageKey(), r.args().toArray(), Locale.KOREAN)).contains("NC-G-1").contains("L-G-M");
        assertThat(messages.getMessage(r.messageKey(), r.args().toArray(), Locale.ENGLISH)).contains("NC-G-1");
        // 같은 정책 수준을 따른다(BLOCK)
        policy("QMS_GATE_FIRST_PENDING", "BLOCK");
        assertThat(check(comp, ctx(woMine, today)).mode()).isEqualTo(StartGate.BLOCK);
        accounts.exec(comp, "update sys_policy set policy_value = 'WARN' where policy_key = 'QMS_GATE_FIRST_PENDING'");
        // 부적합이 종결(·취소)되면 사라진다 — 둘 열려 있으면 하나만 닫아도 남는다
        long n2 = ncr("NC-G-2", lMine);
        owner("update qms_ncr set status = 'CANCELLED', cancel_reason = '중복' where ncr_id = ? returning ncr_id", n1);
        assertThat(refs(check(comp, ctx(woMine, today)))).containsExactly("QMS_FIRST_NG:[L-G-M, ECO-G-1, NC-G-2]");
        owner("update qms_ncr set status = 'CANCELLED', cancel_reason = '처분 끝' where ncr_id = ? returning ncr_id", n2);
        assertThat(check(comp, ctx(woMine, today))).isEqualTo(GateVerdict.PASS);
        // 판정 OK 인 LOT 의 열린 부적합은 이 규칙 밖(초품과 무관한 부적합)
        ncr("NC-G-3", lReceived);
        assertThat(check(comp, ctx(woMine, today))).isEqualTo(GateVerdict.PASS);
        // 미판정 규칙은 그대로 — 새 첫 LOT(미판정) + NG 열린 부적합이 함께면 두 줄, 미판정이 먼저
        long lNew = lot("L-G-N", null);
        mark(eco, lNew, 3);
        ncr("NC-G-4", lMine);
        assertThat(refs(check(comp, ctx(woMine, today)))).containsExactly("QMS_FIRST_PENDING:[L-G-N, ECO-G-1]",
                "QMS_FIRST_NG:[L-G-M, ECO-G-1, NC-G-4]");
        // A사로 B사: 같은 id 로 B사 범위에서 부르면 아무것도 안 보임
        assertThat(check(accounts.newComp("QZ"), ctx(woMine, today))).isEqualTo(GateVerdict.PASS);
    }

    // ---------- Q9-2 계측기 ----------

    @Test
    void 계측기_공정_검사계획_클래스에_쓸_수_있는_게이지가_없으면_작업일_기준() throws Exception {
        policy("QMS_GATE_FIRST_PENDING", "OFF");
        // 계획 없음 → 판정 안 함
        assertThat(check(comp, ctx(woMine, today))).isEqualTo(GateVerdict.PASS);
        long p = plan("PROCESS", "FG1", null, "DRAW");
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        // 클래스 CALIPER 게이지 없음(육안 VISUAL 행은 게이지가 필요 없다)
        GateVerdict v = check(comp, ctx(woMine, today));
        assertThat(v.mode()).isEqualTo(StartGate.WARN);
        assertThat(refs(v)).containsExactly("QMS_NO_GAUGE:[CALIPER]");
        // 다른 공정이면 이 계획이 아니다
        long iqc = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'IQC'", Long.class);
        assertThat(check(comp, new StartContext("P1", woMine, 1, iqc, item, null, userId, 1, today, Instant.now(), false)))
                .isEqualTo(GateVerdict.PASS);
        // 게이지는 있지만 교정 기록 없음 → 여전히 없음, 교정 OK → 통과, 작업일이 기한 뒤면 다시
        accounts.grant(comp, userId, "QA", "qms-gauge-calib", "SEARCH", "SAVE");
        accounts.exec(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, 'GG-C1', 'GAUGE', '캘리퍼스', 'CALIPER', 30, 't', 't')""", comp);
        long g = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'GG-C1'", Long.class);
        assertThat(refs(check(comp, ctx(woMine, today)))).containsExactly("QMS_NO_GAUGE:[CALIPER]");
        postJson("/api/qms/gauges/" + g + "/calibs", "{\"calibDate\":" + q(today.minusDays(1)) + ",\"result\":\"OK\",\"agency\":\"INTERNAL\"}")
                .andExpect(status().isOk());
        assertThat(check(comp, ctx(woMine, today))).isEqualTo(GateVerdict.PASS);
        assertThat(refs(check(comp, ctx(woMine, today.plusDays(40))))).containsExactly("QMS_NO_GAUGE:[CALIPER]");
        // 사용 중지 게이지는 셈에 넣지 않는다
        accounts.exec(comp, "update mst_phys_asset set use_yn = 'N' where asset_id = ?", g);
        assertThat(refs(check(comp, ctx(woMine, today)))).containsExactly("QMS_NO_GAUGE:[CALIPER]");
        // 정책 BLOCK / 문구
        policy("QMS_GATE_GAUGE", "BLOCK");
        GateVerdict b = check(comp, ctx(woMine, today));
        assertThat(b.mode()).isEqualTo(StartGate.BLOCK);
        assertThat(messages.getMessage(b.reasons().get(0).messageKey(), b.reasons().get(0).args().toArray(), Locale.KOREAN)).contains("CALIPER");
    }

    // ---------- A사로 B사 ----------

    @Test
    void A사_품목_id_로_B사_범위에서_부르면_아무것도_보이지_않는다() throws Exception {
        String other = accounts.newComp("QY");
        assertThat(check(comp, ctx(woMine, today)).reasons()).isNotEmpty();
        assertThat(check(other, ctx(woMine, today))).isEqualTo(GateVerdict.PASS);
        // B사에 같은 품목 id 를 가리키는 표시를 직접 넣으려 해도 회사 확인에 막힌다
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> accounts.exec(other, "insert into plm_eco_lot (comp_cd, eco_id, lot_id, mark_reason, seq_no, occurred_at, recorded_by) "
                + "values (?, ?, ?, 'FIRST_LOT', 1, now(), 't')", other, eco, lReceived))).isNotNull();
    }
}
