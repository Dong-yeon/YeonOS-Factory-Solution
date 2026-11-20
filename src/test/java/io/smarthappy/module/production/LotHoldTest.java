package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.IllegalTransactionStateException;

import io.smarthappy.module.production.link.LotHoldPort;
import io.smarthappy.module.production.link.LotHoldPort.HoldView;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * LOT 보류 근거 원장 lot_hold·계약 LotHoldPort (docs/25 4.19, QMS Q8 경계) — 근거 여러 개(마지막 근거가 풀릴 때만 해제), 사유 요약,
 * 화면은 QMS 근거를 못 풂, 포트는 QMS 출처만·멱등·MANDATORY, 동시 보류, 직접 SQL 우회(hold_yn 직접 변경·해제 두 번·삭제·새 LOT 보류),
 * A사로 B사, NONE.
 */
@AutoConfigureMockMvc
class LotHoldTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String L = "/api/production/lots";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    LotHoldPort port;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("LH");
        admin = adminOf(comp);
        item(comp);
    }

    // ---------- 도우미 ----------

    private RequestPostProcessor adminOf(String c) {
        long u = accounts.newUser(c, "admin1", PW);
        accounts.grant(c, u, "PRD", "prd-lot", "SEARCH", "SAVE");
        return accounts.as(c, u, "admin1");
    }

    private void item(String c) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RM', 'RM 품목', 'RM', 'BUY', 'KG', 't', 't')""", c);
    }

    private long lot(String c, String no) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 10, 'KG', now(), 't', 't' from mst_item where item_cd = 'RM' returning lot_id""",
                Long.class, c, no);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private int ver(long lotId) {
        return accounts.query(comp, "select row_ver from lot_master where lot_id = ?", Integer.class, lotId);
    }

    private ResultActions screenHold(long lotId, String yn, String reason) throws Exception {
        return mvc.perform(json(put(L + "/" + lotId + "/hold"), "{\"holdYn\":\"" + yn + "\",\"reason\":\"" + reason + "\",\"rowVer\":"
                + ver(lotId) + "}").with(admin));
    }

    private ResultActions screenRelease(long lotId, long holdId, String reason) throws Exception {
        return mvc.perform(json(post(L + "/" + lotId + "/holds/" + holdId + "/release"), "{\"reason\":\"" + reason + "\",\"rowVer\":"
                + ver(lotId) + "}").with(admin));
    }

    /** "hold_yn/hold_reason" */
    private String state(long lotId) {
        return accounts.query(comp, "select hold_yn || '/' || coalesce(hold_reason, '') from lot_master where lot_id = ?", String.class, lotId);
    }

    private long holdId(long lotId, String source) {
        return accounts.query(comp, "select hold_id from lot_hold where lot_id = ? and source = ? and released_at is null", Long.class,
                lotId, source);
    }

    // ---------- 정상 ----------

    @Test
    void 근거가_여럿이면_마지막_근거가_풀릴_때만_해제되고_사유는_살아_있는_근거_요약() throws Exception {
        long a = lot(comp, "L-A");
        screenHold(a, "Y", "외관 의심").andExpect(status().isOk());
        assertThat(state(a)).isEqualTo("Y/외관 의심");
        // QA R4-a — 보이지 않는 글자(U+200B·NBSP)만인 사유는 계약 위반(QMS 쪽 오류)
        assertThat(catchThrowable(() -> runner.call(comp, () -> port.hold(a, LotHoldPort.QMS_NCR, 7L, "\u200B\u00A0", "qms1"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runner.call(comp, () -> port.hold(a, LotHoldPort.QMS_NCR, 7L, "NCR-7 치수 불량", "qms1"))).isTrue();
        assertThat(state(a)).isEqualTo("Y/외관 의심 / NCR-7 치수 불량");
        // 상세: 살아 있는 근거 2개(보류 시각 순), 화면 [보류]는 수동 근거가 있으면 다시 못 건다
        mvc.perform(get(L + "/" + a).with(admin))
                .andExpect(jsonPath("$.data.holds.length()").value(2))
                .andExpect(jsonPath("$.data.holds[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.data.holds[1].source").value("QMS_NCR"))
                .andExpect(jsonPath("$.data.holds[1].refId").value(7));
        screenHold(a, "Y", "또").andExpect(jsonPath("$.errors[0].code").value("STATE"));
        // 수동 해제 → NCR 이 남아 보류 그대로
        screenHold(a, "N", "외관 합격").andExpect(status().isOk());
        assertThat(state(a)).isEqualTo("Y/NCR-7 치수 불량");
        screenHold(a, "N", "또").andExpect(jsonPath("$.errors[0].code").value("STATE"));
        // 화면은 QMS 근거를 풀 수 없다(D34)
        screenRelease(a, holdId(a, "QMS_NCR"), "그냥").andExpect(jsonPath("$.errors[0].code").value("HOLD_SOURCE"));
        // 포트로 마지막 근거 해제 → 보류 해제, 사유 비움
        assertThat(runner.call(comp, () -> port.release(a, LotHoldPort.QMS_NCR, 7L, "MRB 특채 승인", "qms1"))).isTrue();
        assertThat(state(a)).isEqualTo("N/");
        // 해제 기록은 같은 행에 한 번(D33)
        assertThat(accounts.query(comp, """
                select string_agg(source || ':' || released_by || ':' || release_reason, ',' order by hold_id) from lot_hold where lot_id = ?""",
                String.class, a)).isEqualTo("MANUAL:admin1:외관 합격,QMS_NCR:qms1:MRB 특채 승인");
        // 화면 낙관적 락: 옛 rowVer
        mvc.perform(json(put(L + "/" + a + "/hold"), "{\"holdYn\":\"Y\",\"reason\":\"x\",\"rowVer\":0}").with(admin))
                .andExpect(status().isConflict());
    }

    @Test
    void 포트는_QMS_출처만_멱등_트랜잭션_안에서만_조회는_여러_LOT() throws Exception {
        long a = lot(comp, "L-A");
        long b = lot(comp, "L-B");
        assertThat(runner.call(comp, () -> port.hold(a, LotHoldPort.QMS_CONTAIN, 3L, "봉쇄 3", "qms1"))).isTrue();
        assertThat(runner.call(comp, () -> port.hold(a, LotHoldPort.QMS_CONTAIN, 3L, "봉쇄 3 다시", "qms1"))).isFalse();
        assertThat(runner.call(comp, () -> port.hold(a, LotHoldPort.QMS_NCR, 3L, "같은 번호 다른 출처", "qms1"))).isTrue();
        assertThat(runner.call(comp, () -> port.hold(b, LotHoldPort.QMS_CONTAIN, 3L, "봉쇄 3", "qms1"))).isTrue();
        assertThat(runner.call(comp, () -> port.release(a, LotHoldPort.QMS_CONTAIN, 99L, "없는 근거", "qms1"))).isFalse();
        List<HoldView> v = runner.call(comp, () -> port.holds(List.of(b, a, a, 999_999L)));
        assertThat(v).extracting(h -> h.lotId() + ":" + h.source() + ":" + h.refId())
                .containsExactly(a + ":QMS_CONTAIN:3", a + ":QMS_NCR:3", b + ":QMS_CONTAIN:3");
        assertThat(runner.call(comp, () -> port.release(a, LotHoldPort.QMS_CONTAIN, 3L, "봉쇄 해제", "qms1"))).isTrue();
        assertThat(runner.call(comp, () -> port.release(a, LotHoldPort.QMS_CONTAIN, 3L, "또", "qms1"))).isFalse();
        assertThat(state(a)).isEqualTo("Y/같은 번호 다른 출처");
        // 화면용 출처·빈 사유·없는 LOT → IllegalArgumentException
        for (Supplier<Boolean> bad : List.<Supplier<Boolean>>of(() -> port.hold(a, "MANUAL", 1L, "x", "q"),
                () -> port.hold(a, "MES_CORRECT", 1L, "x", "q"), () -> port.hold(a, LotHoldPort.QMS_NCR, 1L, " ", "q"),
                () -> port.hold(a, LotHoldPort.QMS_NCR, 1L, "x".repeat(501), "q"), () -> port.hold(999_999L, LotHoldPort.QMS_NCR, 1L, "x", "q"))) {
            assertThat(catchThrowable(() -> runner.call(comp, bad))).isInstanceOf(IllegalArgumentException.class);
        }
        // MANDATORY — 트랜잭션 밖 호출 거부
        assertThat(catchThrowable(() -> port.hold(a, LotHoldPort.QMS_NCR, 1L, "x", "q"))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(catchThrowable(() -> runner.call(comp, () -> port.holds(java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SPC_출처_QMS_SV6_포트로_보류_해제_화면은_못_풀고_직접_SQL_도_규칙_그대로_B사_LOT_은_없음() throws Exception {
        long a = lot(comp, "L-A");
        // 포트: SPC 이벤트 id 를 참조로 보류·멱등·다른 출처와 함께 살아 있음
        assertThat(runner.call(comp, () -> port.hold(a, LotHoldPort.QMS_SPC, 41L, "SPC WE1 한계 밖 — 이벤트 41", "qms1"))).isTrue();
        assertThat(runner.call(comp, () -> port.hold(a, LotHoldPort.QMS_SPC, 41L, "다시", "qms1"))).isFalse();
        assertThat(runner.call(comp, () -> port.hold(a, LotHoldPort.QMS_NCR, 41L, "같은 번호 NCR", "qms1"))).isTrue();
        assertThat(state(a)).isEqualTo("Y/SPC WE1 한계 밖 — 이벤트 41 / 같은 번호 NCR");
        mvc.perform(get(L + "/" + a).with(admin))
                .andExpect(jsonPath("$.data.holds[0].source").value("QMS_SPC"))
                .andExpect(jsonPath("$.data.holds[0].refId").value(41));
        // 화면은 QMS 근거를 풀 수 없다(풀기 버튼 없음 — 23.4 M-4)
        screenRelease(a, holdId(a, "QMS_SPC"), "그냥").andExpect(jsonPath("$.errors[0].code").value("HOLD_SOURCE"));
        assertThat(runner.call(comp, () -> port.release(a, LotHoldPort.QMS_SPC, 41L, "이벤트 41 종결", "qms1"))).isTrue();
        assertThat(runner.call(comp, () -> port.release(a, LotHoldPort.QMS_SPC, 41L, "또", "qms1"))).isFalse();
        assertThat(state(a)).isEqualTo("Y/같은 번호 NCR");
        // 직접 SQL: 새 출처도 참조 필수(ck_lot_hold_ref), 비슷한 이름은 출처 밖(ck_lot_hold_source)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) values (?, ?, 'QMS_SPC', 'x', 't')", comp, a))))
                .contains("ck_lot_hold_ref");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into lot_hold (comp_cd, lot_id, source, ref_id, reason, held_by) values (?, ?, 'QMS_SPC2', 1, 'x', 't')", comp, a))))
                .contains("ck_lot_hold_source");
        // A사로 B사: B사 범위에서 A사 LOT 은 없는 LOT
        String other = accounts.newComp("LHS");
        assertThat(catchThrowable(() -> runner.call(other, () -> port.hold(a, LotHoldPort.QMS_SPC, 1L, "B", "b"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.query(comp, "select count(*) from lot_hold where lot_id = ? and source = 'QMS_SPC'", Integer.class, a)).isEqualTo(1);
    }

    @Test
    void 동시에_두_근거를_잡아도_둘_다_살아_있고_요약에_둘_다() throws Exception {
        long a = lot(comp, "L-A");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> fs = List.of(1L, 2L).stream().map(ref -> pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                return runner.call(comp, () -> port.hold(a, LotHoldPort.QMS_NCR, ref, "NCR-" + ref, "qms1"));
            })).toList();
            go.countDown();
            for (Future<Boolean> f : fs) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from lot_hold where lot_id = ? and released_at is null", Integer.class, a)).isEqualTo(2);
        assertThat(state(a)).isIn("Y/NCR-1 / NCR-2", "Y/NCR-2 / NCR-1");
    }

    // ---------- 이상 사용 ----------

    @Test
    void 직접_SQL_보류_칸_직접_변경_해제_두_번_삭제_새_LOT_보류는_거부() throws Exception {
        long a = lot(comp, "L-A");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update lot_master set hold_yn = 'Y', hold_reason = '몰래' where lot_id = ?", a)))).contains("ck_lot_master_hold_basis");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, hold_yn, hold_reason, created_by, updated_by)
                select ?, 'L-HELD', item_id, 'RECEIVED', 1, 'KG', now(), 'Y', '처음부터', 't', 't' from mst_item where item_cd = 'RM'""", comp))))
                .contains("ck_lot_master_hold_basis");
        screenHold(a, "Y", "의심").andExpect(status().isOk());
        long h = holdId(a, "MANUAL");
        // 사유만 몰래 바꾸기·보류 풀기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update lot_master set hold_reason = '바꿈' where lot_id = ?", a)))).contains("ck_lot_master_hold_basis");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update lot_master set hold_yn = 'N', hold_reason = null where lot_id = ?", a)))).contains("ck_lot_master_hold_basis");
        // 근거 행: 사유 고치기·삭제·두 번째 살아 있는 수동 근거·출처 밖·수동인데 참조·없는 실적 참조
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update lot_hold set reason = 'x' where hold_id = ?", h))))
                .contains("ck_lot_hold_fixed");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from lot_hold where hold_id = ?", h))).isNotNull();
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) values (?, ?, 'MANUAL', '둘째', 't')", comp, a))))
                .contains("uq_lot_hold_live");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into lot_hold (comp_cd, lot_id, source, ref_id, reason, held_by) values (?, ?, 'WMS', 1, 'x', 't')", comp, a))))
                .contains("ck_lot_hold_source");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into lot_hold (comp_cd, lot_id, source, ref_id, reason, held_by) values (?, ?, 'MANUAL', 1, 'x', 't')", comp, a))))
                .contains("ck_lot_hold_ref");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into lot_hold (comp_cd, lot_id, source, ref_id, reason, held_by) values (?, ?, 'MES_CORRECT', 999999, 'x', 't')",
                comp, a)))).contains("fk_lot_hold_ref_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into lot_hold (comp_cd, lot_id, source, ref_id, reason, held_by, released_at, released_by, release_reason) "
                        + "values (?, ?, 'QMS_NCR', 5, 'x', 't', now(), 't', '끝')", comp, a)))).contains("ck_lot_hold_new");
        // 해제 사유 없이·해제 두 번
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update lot_hold set released_at = now(), released_by = 't' where hold_id = ?", h)))).contains("ck_lot_hold_release");
        accounts.exec(comp, "update lot_hold set released_at = now(), released_by = 't', release_reason = '합격' where hold_id = ?", h);
        assertThat(state(a)).isEqualTo("N/");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update lot_hold set released_at = now(), released_by = 'u', release_reason = '또' where hold_id = ?", h))))
                .contains("ck_lot_hold_fixed");
        // 화면: 이미 풀린 근거·남의 LOT 근거 id
        long b = lot(comp, "L-B");
        screenRelease(a, h, "또").andExpect(jsonPath("$.errors[0].code").value("STATE"));
        screenRelease(b, h, "엉뚱").andExpect(status().isNotFound());
        screenRelease(a, h, " ").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void 트리거를_끈_소유자_연결도_사유_없는_보류는_CHECK_가_막는다() throws Exception {
        long a = lot(comp, "L-A");
        // V034 ck_lot_master_hold 는 사유 NULL 을 통과시켰다(length(btrim(NULL)) > 0 = NULL) → V109 ck_lot_master_hold_reason
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = c.createStatement()) {
            st.execute("set session_replication_role = replica");
            Throwable t = catchThrowable(() -> st.executeUpdate("update lot_master set hold_yn = 'Y', hold_reason = null where lot_id = " + a));
            assertThat(t).isInstanceOf(SQLException.class).hasMessageContaining("ck_lot_master_hold_reason");
        }
        assertThat(state(a)).isEqualTo("N/");
    }

    @Test
    void A사로_B사_포트_화면_직접_SQL() throws Exception {
        long a = lot(comp, "L-A");
        screenHold(a, "Y", "A 의심").andExpect(status().isOk());
        long h = holdId(a, "MANUAL");
        String other = accounts.newComp("LHB");
        RequestPostProcessor bAdmin = adminOf(other);
        item(other);
        // B사 범위: A사 LOT 보류·조회·해제
        assertThat(catchThrowable(() -> runner.call(other, () -> port.hold(a, LotHoldPort.QMS_NCR, 1L, "B", "b")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> runner.call(other, () -> port.release(a, LotHoldPort.QMS_NCR, 1L, "B", "b"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runner.call(other, () -> port.holds(List.of(a)))).isEmpty();
        mvc.perform(json(post(L + "/" + a + "/holds/" + h + "/release"), "{\"reason\":\"B\",\"rowVer\":1}").with(bAdmin))
                .andExpect(status().isNotFound());
        mvc.perform(json(put(L + "/" + a + "/hold"), "{\"holdYn\":\"N\",\"reason\":\"B\",\"rowVer\":1}").with(bAdmin))
                .andExpect(status().isNotFound());
        // 직접 SQL(B사 범위): A사 LOT 에 B사 근거 / A사 근거 해제 / 회사 코드 A사
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) values (?, ?, 'MANUAL', 'B', 't')", other, a))))
                .contains("fk_lot_hold_lot_comp");
        assertThat(accounts.exec(other, "update lot_hold set released_at = now(), released_by = 'b', release_reason = 'B' where hold_id = ?", h))
                .isZero();
        // 회사 코드를 A사로 써도 B사 범위에서는 A사 LOT 이 안 보여 같은 회사 트리거가 먼저 막는다(RLS 보다 먼저 — CMS C7 과 같음)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) values (?, ?, 'MANUAL', 'B', 't')", comp, a))))
                .contains("fk_lot_hold_lot_comp");
        assertThat(accounts.query(comp, "select count(*) from lot_hold where lot_id = ?", Integer.class, a)).isEqualTo(1);
        assertThat(state(a)).isEqualTo("Y/A 의심");
    }

    @Test
    void 계약_없음_NONE은_보류_해제를_거부하고_조회만_빈_목록() {
        LotHoldPort none = LotHoldPort.of(new DefaultListableBeanFactory().getBeanProvider(LotHoldPort.class));
        assertThat(none).isSameAs(LotHoldPort.NONE);
        // 조용한 false 는 "이미 보류됨" 과 구분이 안 된다 — 보류·해제는 예외, 조회만 빈 목록
        assertThat(catchThrowable(() -> none.hold(1L, LotHoldPort.QMS_NCR, 1L, "x", "q"))).isInstanceOf(IllegalStateException.class);
        assertThat(catchThrowable(() -> none.release(1L, LotHoldPort.QMS_NCR, 1L, "x", "q"))).isInstanceOf(IllegalStateException.class);
        assertThat(none.holds(1L)).isEmpty();
    }

    // ---------- 보류 사유 코드 (docs/68 Q1) ----------

    private ResultActions codeHold(long lotId, String code, String memo) throws Exception {
        return mvc.perform(json(put(L + "/" + lotId + "/hold"), "{\"holdYn\":\"Y\",\"reasonCd\":" + (code == null ? "null" : "\"" + code + "\"")
                + ",\"reason\":" + (memo == null ? "null" : "\"" + memo + "\"") + ",\"rowVer\":" + ver(lotId) + "}").with(admin));
    }

    @Test
    void 수동보류_사유코드_메모없으면_코드이름_사용중지_다른회사_코드는_거부_코드는_못바꾼다() throws Exception {
        long a = lot(comp, "L-RC1");
        long b = lot(comp, "L-RC2");
        long c = lot(comp, "L-RC3");
        // 코드 + 메모 / 코드만(사유 글자 = 코드 이름 — 요약도 글자로)
        codeHold(a, "SPEC_OUT", "치수 상한 초과").andExpect(status().isOk());
        codeHold(b, "CUSTOMER", null).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select reason_cd || '/' || reason from lot_hold where lot_id = ?", String.class, a)).isEqualTo("SPEC_OUT/치수 상한 초과");
        assertThat(accounts.query(comp, "select reason_cd || '/' || reason from lot_hold where lot_id = ?", String.class, b)).isEqualTo("CUSTOMER/고객 요청");
        assertThat(state(b)).isEqualTo("Y/고객 요청");
        mvc.perform(get(L + "/" + a).with(admin)).andExpect(jsonPath("$.data.holds[0].reasonCd").value("SPEC_OUT"));
        // 옛 API(글자만)는 미분류로 받는다
        screenHold(c, "Y", "현장 판단").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select coalesce(reason_cd, '-') || '/' || reason from lot_hold where lot_id = ?", String.class, c)).isEqualTo("-/현장 판단");
        // 이상: 없는 코드·사용 중지 회사 코드·다른 회사 코드 → reasonCd CODE, 보류 안 됨
        long d = lot(comp, "L-RC4");
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, use_yn, created_by, updated_by) values (?, 'LOT_HOLD_REASON', 'OLD', '옛 사유', 'N', 't', 't')", comp);
        String other = accounts.newComp("LH");
        accounts.exec(other, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'LOT_HOLD_REASON', 'BONLY', 'B 사유', 't', 't')", other);
        for (String bad : new String[] {"NOPE", "OLD", "BONLY"}) {
            codeHold(d, bad, "x").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reasonCd"))
                    .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        }
        assertThat(state(d)).isEqualTo("N/");
        // 회사 코드(사용 중)는 된다
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'LOT_HOLD_REASON', 'PAINT', '도장 얼룩', 't', 't')", comp);
        codeHold(d, "PAINT", null).andExpect(status().isOk());
        // 직접 SQL: 근거 행의 코드는 넣은 뒤 못 바꾼다, 다른 회사 코드는 트리거가 거부
        long h = holdId(a, "MANUAL");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update lot_hold set reason_cd = 'OTHER' where hold_id = ?", h))))
                .contains("ck_lot_hold_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into lot_hold (comp_cd, lot_id, source, reason_cd, reason, held_by) values (?, ?, 'MANUAL', 'BONLY', 'x', 't')""", comp,
                lot(comp, "L-RC5"))))).contains("fk_lot_hold_reason_cd_code");
        // 해제는 사유 글자만(코드 없음) — 그대로
        screenRelease(a, h, "재측정 합격").andExpect(status().isOk());
        assertThat(state(a)).isEqualTo("N/");
    }
}
