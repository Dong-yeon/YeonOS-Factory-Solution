package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.TestAccounts;

/**
 * P3 QMS 2부 Q8b 봉쇄 — 후보(품목 × 기간, 지금 보류 근거) → 고른 LOT 봉쇄(MES LotHoldPort, QMS_CONTAIN, lot_id 오름차순) → 골라 해제,
 * 모두 풀리면 해제 완료, 다른 근거가 남은 LOT 은 계속 보류 + 역순 요청 동시 봉쇄 교착 없음 + 이상 사용(A~K·A사로 B사·직접 SQL).
 */
class ContainApiTest extends QmsTestBase {

    static final String C = "/api/qms/contains";
    static final String N = "/api/qms/ncrs";

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    long lotA;
    long lotB;
    long lotC;
    long lotOld;
    long defect;

    @BeforeEach
    void containSetUp() {
        accounts.grant(comp, userId, "QA", "qms-contain", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        accounts.exec(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-BUR', '버', 'APPEARANCE', 't', 't')""", comp);
        defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'D-BUR'", Long.class);
        lotA = lot("L-A", "FG1", "now() - interval '3 days'");
        lotB = lot("L-B", "FG1", "now()");
        lotC = lot("L-C", "RM1", "now()");
        lotOld = lot("L-OLD", "FG1", "now() - interval '40 days'");
    }

    long lot(String no, String item, String occurred) {
        return accounts.query(comp, "insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by) "
                + "select ?, ?, item_id, 'RECEIVED', 10, 'EA', " + occurred + ", 't', 't' from mst_item where item_cd = ? returning lot_id",
                Long.class, comp, no, item);
    }

    long ncr(String lotNo) throws Exception {
        return id(postJson(N, "{\"lotNo\":" + q(lotNo) + ",\"source\":\"PROCESS\",\"defectId\":" + defect
                + ",\"ncQty\":1,\"description\":\"버 발생\"}"));
    }

    String ncrNo(long id) {
        return accounts.query(comp, "select ncr_no from qms_ncr where ncr_id = ?", String.class, id);
    }

    String body(String itemCd, String ncrNo, String reason, long... lots) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < lots.length; i++) {
            ids.append(i == 0 ? "" : ",").append(lots[i]);
        }
        return "{\"itemCd\":" + q(itemCd) + ",\"fromDate\":" + q(today.minusDays(7)) + ",\"toDate\":" + q(today) + ",\"ncrNo\":" + q(ncrNo)
                + ",\"reason\":" + q(reason) + ",\"lotIds\":[" + ids + "]}";
    }

    ResultActions release(long id, String reason, long... lots) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < lots.length; i++) {
            ids.append(i == 0 ? "" : ",").append(lots[i]);
        }
        return postJson(C + "/" + id + "/release", "{\"reason\":" + q(reason) + ",\"lotIds\":[" + ids + "]}");
    }

    /** "hold_yn/살아 있는 근거 출처" */
    String lotState(long lotId) {
        return accounts.query(comp, """
                select l.hold_yn || '/' || coalesce((select string_agg(h.source, ',' order by h.hold_id) from lot_hold h
                  where h.lot_id = l.lot_id and h.released_at is null), '') from lot_master l where l.lot_id = ?""", String.class, lotId);
    }

    @Test
    void 후보에서_골라_봉쇄_골라_해제_모두_풀리면_해제완료_다른_근거는_남는다() throws Exception {
        long n = ncr("L-A");
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) values (?, ?, 'MANUAL', '외관 의심', 't')", comp, lotB);
        // 후보: FG1 × 7일 전~오늘 → L-A·L-B(40일 전·다른 품목 제외), 지금 보류 근거 같이
        mvc.perform(get(C + "/candidates").param("itemCd", "FG1").param("from", today.minusDays(7).toString()).param("to", today.toString())
                .with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lots[*].lotNo", Matchers.contains("L-A", "L-B")))
                .andExpect(jsonPath("$.data.holds[*].source", Matchers.containsInAnyOrder("QMS_NCR", "MANUAL")))
                .andExpect(jsonPath("$.data.truncated").value(false));
        // 역순으로 보내도 된다(서버가 오름차순으로 포트 호출)
        long id = id(postJson(C, body("FG1", ncrNo(n), "같은 금형 사용 LOT", lotB, lotA)));
        assertThat(lotState(lotA)).isEqualTo("Y/QMS_NCR,QMS_CONTAIN");
        assertThat(lotState(lotB)).isEqualTo("Y/MANUAL,QMS_CONTAIN");
        assertThat(accounts.query(comp, "select string_agg(ref_id::text, ',') from lot_hold where source = 'QMS_CONTAIN'", String.class))
                .isEqualTo(id + "," + id);
        mvc.perform(get(C + "/" + id).with(admin)).andExpect(jsonPath("$.data.contain.status").value("OPEN"))
                .andExpect(jsonPath("$.data.contain.ncrNo").value(ncrNo(n))).andExpect(jsonPath("$.data.contain.heldCnt").value(2))
                .andExpect(jsonPath("$.data.lots.length()").value(2)).andExpect(jsonPath("$.data.holds.length()").value(4));
        // L-A 만 해제 → NCR 근거가 남아 계속 보류, 봉쇄는 OPEN
        release(id, "선별 OK", lotA).andExpect(status().isOk());
        assertThat(lotState(lotA)).isEqualTo("Y/QMS_NCR");
        release(id, "또", lotA).andExpect(jsonPath("$.errors[0].code").value("CONTAIN_NOT_HELD"));
        mvc.perform(get(C).with(admin)).andExpect(jsonPath("$.data[0].heldCnt").value(1)).andExpect(jsonPath("$.data[0].status").value("OPEN"));
        // L-B 해제 → 모두 풀림 = 해제 완료, L-B 는 MANUAL 로 계속 보류
        release(id, "선별 OK", lotB).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from qms_contain where contain_id = ?", String.class, id)).isEqualTo("CLOSED");
        assertThat(lotState(lotB)).isEqualTo("Y/MANUAL");
        release(id, "또", lotB).andExpect(jsonPath("$.code").value("QMS_CONTAIN_STATE"));
        assertThat(accounts.query(comp, "select string_agg(released_by || ':' || release_reason, ',' order by lot_id) from qms_contain_lot "
                + "where contain_id = ?", String.class, id)).isEqualTo("admin1:선별 OK,admin1:선별 OK");
        mvc.perform(get(C).param("keyword", ncrNo(n)).with(admin)).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void D_역순으로_요청한_동시_봉쇄_두_건도_오름차순이라_교착_없음() throws Exception {
        // 다른 트랜잭션이 L-B 를 쥔 동안 [A,B] 와 [B,A] 를 동시에 — 서버가 정렬하지 않으면 풀린 뒤 A·B 를 서로 기다려 교착(40P01)
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> blocker = pool.submit(() -> runner.run(comp, () -> {
                jdbc.queryForObject("select lot_id from lot_master where lot_id = ? for update", Long.class, lotB);
                locked.countDown();
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<MvcResult>> calls = new ArrayList<>();
            calls.add(pool.submit(() -> mvc.perform(json(post(C), body("FG1", null, "1", lotA, lotB)).with(admin)).andReturn()));
            calls.add(pool.submit(() -> mvc.perform(json(post(C), body("FG1", null, "2", lotB, lotA)).with(admin)).andReturn()));
            for (Future<MvcResult> f : calls) {
                assertThat(f.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            }
            blocker.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from lot_hold where source = 'QMS_CONTAIN' and released_at is null", Integer.class))
                .isEqualTo(4);
        assertThat(lotState(lotA)).isEqualTo("Y/QMS_CONTAIN,QMS_CONTAIN");
    }

    @Test
    void D_같은_봉쇄의_다른_LOT_을_동시에_풀어도_마지막이_해제완료로_닫는다() throws Exception {
        long id = id(postJson(C, body("FG1", null, "의심", lotA, lotB)));
        // 다른 트랜잭션이 봉쇄 머리를 쥔 동안 L-A·L-B 해제가 각자 LOT 을 푼 뒤 머리 잠금에서 기다린다 →
        // 먼저 받은 쪽은 상대 미커밋 해제를 못 봐 "남음 1", 둘째는 첫째 커밋 뒤 새 스냅샷으로 "0" → CLOSED
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> blocker = pool.submit(() -> runner.run(comp, () -> {
                jdbc.queryForObject("select contain_id from qms_contain where contain_id = ? for update", Long.class, id);
                locked.countDown();
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (long lotId : new long[] {lotA, lotB}) {
                calls.add(pool.submit(() -> mvc.perform(json(post(C + "/" + id + "/release"), "{\"reason\":\"선별 OK\",\"lotIds\":[" + lotId + "]}")
                        .with(admin)).andReturn()));
            }
            for (Future<MvcResult> f : calls) {
                assertThat(f.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            }
            blocker.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select status || ':' || lot_cnt from qms_contain where contain_id = ?", String.class, id))
                .isEqualTo("CLOSED:2");
        assertThat(lotState(lotA) + " " + lotState(lotB)).isEqualTo("N/ N/");
    }

    /** 위 결함의 원형(SQL): 두 트랜잭션이 각자 줄을 풀고 머리 잠금 없이 세면 서로의 미커밋 해제를 못 봐 둘 다 1 — 아무도 닫지 않는다 */
    @Test
    void D_잠금_없이_세면_둘_다_남음_1_로_본다_원형() throws Exception {
        long id = id(postJson(C, body("FG1", null, "의심", lotA, lotB)));
        String rel = "update qms_contain_lot set released_at = now(), released_by = 't', release_reason = 'x' where contain_id = ? and lot_id = ?";
        String cnt = "select count(*) from qms_contain_lot where contain_id = ? and released_at is null";
        CountDownLatch bothReleased = new CountDownLatch(2);
        // 둘 다 센 뒤에야 커밋 — 한쪽이 먼저 커밋하면 늦게 세는 쪽은 0 을 본다(원형 재현이 시점에 따라 흔들림)
        CountDownLatch bothCounted = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> counts = new ArrayList<>();
            for (long lotId : new long[] {lotA, lotB}) {
                counts.add(pool.submit(() -> runner.call(comp, () -> {
                    jdbc.update(rel, id, lotId);
                    bothReleased.countDown();
                    try {
                        bothReleased.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    Integer n = jdbc.queryForObject(cnt, Integer.class, id);
                    bothCounted.countDown();
                    try {
                        bothCounted.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return n;
                })));
            }
            assertThat(counts.get(0).get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(counts.get(1).get(20, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, cnt, Integer.class, id)).as("커밋 뒤엔 0 인데 둘 다 1 로 봤다").isZero();
    }

    @Test
    void 이상_입력과_상태() throws Exception {
        postJson(C, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field",
                Matchers.hasItems("itemCd", "fromDate", "toDate", "reason", "lotIds")));
        postJson(C, body("NONE", null, "x", lotA)).andExpect(jsonPath("$.errors[0].field").value("itemCd"));
        postJson(C, body("FG1", null, "x", lotA).replace(q(today.minusDays(7)), q(today.plusDays(1)))).andExpect(jsonPath("$.errors[0].code")
                .value("CONTAIN_RANGE"));
        postJson(C, body("FG1", null, "x", lotA).replace(q(today.minusDays(7)), q(today.minusDays(400))))
                .andExpect(jsonPath("$.errors[0].code").value("CONTAIN_RANGE"));
        postJson(C, body("FG1", "NC-NONE", "x", lotA)).andExpect(jsonPath("$.errors[0].field").value("ncrNo"));
        long otherNcr = ncr("L-C");
        postJson(C, body("FG1", ncrNo(otherNcr), "x", lotA)).andExpect(jsonPath("$.errors[0].code").value("CONTAIN_NCR_ITEM"));
        postJson(C, body("FG1", null, "x", lotA, lotC)).andExpect(jsonPath("$.errors[0].code").value("CONTAIN_LOT_ITEM"));
        postJson(C, body("FG1", null, "x", lotA, 999999999L)).andExpect(jsonPath("$.errors[0].code").value("CONTAIN_LOT_ITEM"));
        postJson(C, body("FG1", null, "x", lotA, lotA)).andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        long[] many = new long[501];
        for (int i = 0; i < many.length; i++) {
            many[i] = i + 1;
        }
        postJson(C, body("FG1", null, "x", many)).andExpect(jsonPath("$.errors[0].code").value("TOO_MANY"));
        assertThat(lotState(lotA)).isEqualTo("N/");
        mvc.perform(get(C + "/candidates").param("itemCd", "FG1").with(admin)).andExpect(status().isBadRequest());
        long id = id(postJson(C, body("FG1", null, "의심", lotA)));
        release(id, " ", lotA).andExpect(status().isBadRequest());
        release(id, "x").andExpect(status().isBadRequest());
        release(id, "x", lotB).andExpect(jsonPath("$.errors[0].code").value("CONTAIN_NOT_HELD"));
        release(999999999L, "x", lotA).andExpect(status().isNotFound());
        mvc.perform(get(C + "/999999999").with(admin)).andExpect(status().isNotFound());
    }

    @Test
    void 권한_A사로_B사_직접SQL() throws Exception {
        long id = id(postJson(C, body("FG1", null, "의심", lotA, lotB)));
        long reader = accounts.newUser(comp, "reader1", PW);
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        mvc.perform(get(C).with(rd)).andExpect(status().isForbidden());
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "QV", "qms-contain", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        mvc.perform(get(C + "/" + id).with(vw)).andExpect(status().isOk());
        mvc.perform(json(post(C), body("FG1", null, "x", lotOld)).with(vw)).andExpect(status().isForbidden());
        mvc.perform(json(post(C + "/" + id + "/release"), "{\"reason\":\"x\",\"lotIds\":[" + lotA + "]}").with(vw)).andExpect(status().isForbidden());
        // A사로 B사
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-contain", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(C).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(C + "/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(C + "/" + id + "/release"), "{\"reason\":\"x\",\"lotIds\":[" + lotA + "]}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(C + "/candidates").param("itemCd", "FG1").param("from", today.toString()).param("to", today.toString()).with(b))
                .andExpect(jsonPath("$.errors[0].field").value("itemCd"));
        mvc.perform(json(post(C), body("FG1", null, "x", lotA)).with(b)).andExpect(jsonPath("$.errors[0].field").value("itemCd"));
        assertThat(lotState(lotA)).isEqualTo("Y/QMS_CONTAIN");
        // 직접 SQL
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_contain where contain_id = ?", id));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_contain_lot where contain_id = ?", id));
        String ins = "insert into qms_contain (comp_cd, contain_no, item_id, ncr_id, from_date, to_date, reason, status, lot_cnt, closed_at, created_by, "
                + "updated_by) select ?, ?, item_id, ?, " + TestAccounts.COMP_TODAY + ", " + TestAccounts.COMP_TODAY + ", 'x', ?, 1, ?, 't', 't' from mst_item where item_cd = 'FG1'";
        constraint(comp, "ck_qms_contain_open", () -> accounts.exec(comp, ins,
                comp, "X1", null, "CLOSED", java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z")));
        long rmNcr = ncr("L-C");
        constraint(comp, "ck_qms_contain_ncr", () -> accounts.exec(comp, ins,
                comp, "X2", rmNcr, "OPEN", null));
        constraint(comp, "ck_qms_contain_lot_item", () -> accounts.exec(comp,
                "insert into qms_contain_lot (comp_cd, contain_id, lot_id, created_by) values (?, ?, ?, 't')", comp, id, lotC));
        constraint(comp, "ck_qms_contain_immutable", () -> accounts.exec(comp, "update qms_contain set reason = 'y' where contain_id = ?", id));
        constraint(comp, "ck_qms_contain_all_released", () -> accounts.exec(comp,
                "update qms_contain set status = 'CLOSED', closed_at = now() where contain_id = ?", id));
        constraint(comp, "ck_qms_contain_lot_release", () -> accounts.exec(comp,
                "update qms_contain_lot set released_at = now(), released_by = 't' where contain_id = ? and lot_id = ?", id, lotA));
        accounts.exec(comp, "update qms_contain_lot set released_at = now(), released_by = 't', release_reason = 'SQL' where contain_id = ? and lot_id = ?",
                id, lotA);
        constraint(comp, "ck_qms_contain_lot_immutable", () -> accounts.exec(comp,
                "update qms_contain_lot set release_reason = 'y' where contain_id = ? and lot_id = ?", id, lotA));
        constraint(other, "ck_qms_contain_lot_open", () -> accounts.exec(other,
                "insert into qms_contain_lot (comp_cd, contain_id, lot_id, created_by) values (?, ?, ?, 't')", other, id, lotOld));
        assertThat(accounts.query(other, "select count(*) from qms_contain_lot", Long.class)).isZero();
        assertThat(accounts.exec(other, "update qms_contain set status = 'CLOSED', closed_at = now()")).isZero();
    }
}
