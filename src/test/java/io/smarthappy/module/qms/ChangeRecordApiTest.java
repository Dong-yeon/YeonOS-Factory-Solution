package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

/**
 * QMS 4M 3차 — 임시 변경 복귀 확인 · 변경 효과 확인 (docs/29_P3 51장, docs/66 (나)·(다)).
 * 정상 흐름(기한 지남 카드·목록 → 복귀 기록 → 표시 끝 차이 경고 → 무효 뒤 다시 / 비교 표 손 검산 → 판정 → 무효 → 다시) +
 * 조용히 틀리는 값(무효 측정값·무효 검사·열린 검사·취소 NCR·기준 시각 경계·뒤 기간 덜 참) + 이상 사용 A~K(상태·날짜·입력·동시·권한) +
 * A사로 B사 + 직접 SQL + 서명 정책.
 */
class ChangeRecordApiTest extends QmsTestBase {

    static final String C = "/api/qms/changes";
    static final String CARDS = "/api/home/cards";
    /** 회사 시간대 오늘 0시(적용일 = 오늘인 ECO 의 비교 기준 시각) */
    static final String BASE = "(date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul')";

    long plan;
    long lot;
    long defect;
    int seq;

    @BeforeEach
    void recordSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-change", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "home-main", "SEARCH");
        plan = plan("FINAL", "FG1", null, null);
        lines(plan, line("a", null, 10, fgChar1, "S5", "VISUAL"), line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(plan, "release").andExpect(status().isOk());
        lot = accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'L-1', item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1' returning lot_id""",
                Long.class, comp);
        defect = accounts.query(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-1', '치수', 'DIMENSION', 't', 't') returning defect_id""", Long.class, comp);
    }

    // ---------- 준비 ----------

    /** 작성 중 ECO — FG1 품목 + FG1 × DRAW 공정 대상, 표시 끝 = 회사 오늘 + markOffset(DESIGN 은 null) */
    long eco(String c, long owner, String no, String kind, int markOffset) {
        long e = accounts.query(c, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, change_kind, mark_until, created_by, updated_by)
                values (?, ?, '임시 지그', 'QUALITY', (now() at time zone 'Asia/Seoul')::date - 10, ?, ?,
                        case when ? = 'DESIGN' then null else (now() at time zone 'Asia/Seoul')::date + ? end, 't', 't')
                returning eco_id""", Long.class, c, no, owner, kind, kind, markOffset);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG1'), 't', 't')""", c, e);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, proc_id, created_by, updated_by)
                values (?, ?, 2, 'PROC', (select item_id from mst_item where item_cd = 'FG1'), (select proc_id from mst_proc where proc_cd = 'DRAW'), 't', 't')""",
                c, e);
        return e;
    }

    /** 4M 세부(작성 중일 때 직접) + 영향 특성 FG1-1(계량)·FG1-2(계수) */
    void change(String c, long e, String revertCond) {
        accounts.exec(c, """
                insert into qms_change (eco_id, comp_cd, eco_no, m4_types, before_desc, after_desc, revert_cond, created_by, updated_by)
                values (?, ?, '-', array['METHOD']::varchar(10)[], '표준 지그', '임시 지그', ?, 't', 't')""", e, c, revertCond);
        accounts.exec(c, "insert into qms_change_char (comp_cd, eco_id, line_no, char_id, created_by) values (?, ?, 1, ?, 't'), (?, ?, 2, ?, 't')",
                c, e, charId(c, "FG1", null, "1"), c, e, charId(c, "FG1", null, "2"));
    }

    void release(String c, long e) {
        accounts.exec(c, "update plm_eco set status = 'PENDING' where eco_id = ?", e);
        accounts.exec(c, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", e);
        accounts.exec(c, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", e);
    }

    /** 적용된 임시 변경(4M 세부 있음) */
    long temp(String no, int markOffset) {
        long e = eco(comp, userId, no, "TEMP", markOffset);
        change(comp, e, "표준 지그 수리 완료 후 복귀");
        release(comp, e);
        return e;
    }

    ResultActions revert(long e, String type, String on, String note) throws Exception {
        return postJson(C + "/" + e + "/reverts", "{\"revertType\":" + q(type) + ",\"revertedOn\":" + q(on) + ",\"note\":" + q(note) + "}");
    }

    String todayS() {
        return today.toString();
    }

    String home() throws Exception {
        return mvc.perform(get(CARDS).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    static long cardCount(String json) {
        List<Object> v = JsonPath.read(json, "$.data.cards[?(@.code == 'qms-temp-overdue')].count");
        return ((Number) v.get(0)).longValue();
    }

    int ver(String table, String key, long id) {
        return accounts.query(comp, "select row_ver from " + table + " where " + key + " = ?", Integer.class, id);
    }

    /**
     * 검사 한 건을 직접 SQL 로 — 발생 시각 at(SQL 식), FG1-1 계량값·FG1-2 OK/NG, 끝 상태(JUDGED 결과 / OPEN / VOID).
     * 측정 원장·검사 시각을 정해야 기준 시각 앞뒤를 시험할 수 있다(API 는 시각을 서버가 정한다)
     */
    long insp(String at, String[] values, String[] oks, String end) {
        long id = accounts.query(comp, "insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, insp_plan_id, lot_qty, occurred_at, "
                + "created_by, updated_by) values (?, ?, 'FINAL', ?, (select item_id from lot_master where lot_id = ?), ?, 100, " + at
                + ", 't', 't') returning insp_id", Long.class, comp, "I-" + (++seq), lot, lot, plan);
        long l1 = accounts.query(comp, "insert into qms_insp_line (comp_cd, insp_id, line_no, plan_line_id, char_id, data_type, gauge_class, "
                + "sample_size, ac, re, created_by) values (?, ?, 10, ?, ?, 'VARIABLE', 'VISUAL', 10, 0, 1, 't') returning insp_line_id",
                Long.class, comp, id, lineId(plan, fgChar1), fgChar1);
        long l2 = accounts.query(comp, "insert into qms_insp_line (comp_cd, insp_id, line_no, plan_line_id, char_id, data_type, gauge_class, "
                + "sample_size, ac, re, created_by) values (?, ?, 20, ?, ?, 'ATTRIBUTE', 'VISUAL', 10, 0, 1, 't') returning insp_line_id",
                Long.class, comp, id, lineId(plan, fgChar2), fgChar2);
        for (int i = 0; i < values.length; i++) {
            accounts.exec(comp, "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, value, occurred_at, recorded_by) values (?, ?, ?, ?::numeric, "
                    + at + ", 't')", comp, l1, i + 1, values[i]);
        }
        for (int i = 0; i < oks.length; i++) {
            accounts.exec(comp, "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, ok_yn, occurred_at, recorded_by) values (?, ?, ?, ?, "
                    + at + ", 't')", comp, l2, i + 1, oks[i]);
        }
        if ("VOID".equals(end)) {
            accounts.exec(comp, "update qms_insp set status = 'VOID', void_reason = '오입력', voided_at = now(), voided_by = 't' where insp_id = ?", id);
        } else if (!"OPEN".equals(end)) {
            accounts.exec(comp, "update qms_insp set status = 'JUDGED', result = ?, judged_at = now(), judged_by = 't' where insp_id = ?", end, id);
        }
        return id;
    }

    long line1(long insp) {
        return accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ? and line_no = 10", Long.class, insp);
    }

    long ncr(String at, int qty) {
        return accounts.query(comp, "insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, defect_id, nc_qty, description, occurred_at, "
                + "status, hold_yn, created_by, updated_by) select ?, ?, 'OTHER', ?, item_id, 'EA', ?, ?, '치수', " + at + ", 'OPEN', 'N', 't', 't' "
                + "from mst_item where item_cd = 'FG1' returning ncr_id", Long.class, comp, "N-" + (++seq), lot, defect, qty);
    }

    static String[] a(String... v) {
        return v;
    }

    // ---------- 정상 흐름: 복귀 ----------

    @Test
    void 임시변경_기한지남_카드_목록_복귀기록_표시끝차이_무효뒤_다시() throws Exception {
        long over = temp("ECO-T-1", -2);
        long open = temp("ECO-T-2", 5);
        long same = temp("ECO-T-3", 0);
        // 세지 않는 것: 작성 중 임시, 4M 세부 없는 적용 임시, 적용된 4M(임시 아님 — 복귀 조건 글이 있어도)
        long draft = eco(comp, userId, "ECO-T-D", "TEMP", -2);
        change(comp, draft, "복귀");
        long bare = eco(comp, userId, "ECO-T-N", "TEMP", -2);
        release(comp, bare);
        long proc = eco(comp, userId, "ECO-P-1", "PROCESS_4M", -2);
        change(comp, proc, "복귀 조건(임시 아님)");
        release(comp, proc);

        mvc.perform(get(C + "/" + over + "/records").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revert.temp").value(true))
                .andExpect(jsonPath("$.data.revert.ready").value(true))
                .andExpect(jsonPath("$.data.revert.due").value("OVERDUE"))
                .andExpect(jsonPath("$.data.revert.revertCond").value("표준 지그 수리 완료 후 복귀"))
                .andExpect(jsonPath("$.data.revert.markUntil").value(today.minusDays(2).toString()))
                .andExpect(jsonPath("$.data.revert.live").doesNotExist())
                .andExpect(jsonPath("$.data.esignRequired").value(false));
        mvc.perform(get(C + "/" + open + "/records").with(admin)).andExpect(jsonPath("$.data.revert.due").value("DUE"));
        mvc.perform(get(C + "/" + proc + "/records").with(admin)).andExpect(jsonPath("$.data.revert.temp").value(false))
                .andExpect(jsonPath("$.data.revert.due").doesNotExist());
        mvc.perform(get(C + "/" + bare + "/records").with(admin)).andExpect(jsonPath("$.data.hasChange").value(false))
                .andExpect(jsonPath("$.data.revert.ready").value(false));

        // 카드 = 목록 필터(같은 함수)
        String h = home();
        assertThat(cardCount(h)).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(h, "$.data.cards[?(@.code == 'qms-temp-overdue')].items[*].title")).containsExactly("ECO-T-1");
        assertThat(JsonPath.<List<String>>read(h, "$.data.cards[?(@.code == 'qms-temp-overdue')].link.revertDue")).containsExactly("OVERDUE");
        mvc.perform(get(C).param("revertDue", "OVERDUE").with(admin)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].ecoNo").value("ECO-T-1"));
        mvc.perform(get(C + "/grid").param("revertDue", "OVERDUE").param("total", "true").with(admin))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(C).with(admin)).andExpect(jsonPath("$.data.length()").value(5));

        // 복귀 기록(오늘) — 표시 끝(오늘 − 2) 보다 늦음 → LATE, 카드 0
        long r1 = id(revert(over, "RESTORED", todayS(), "표준 지그 재장착·초품 확인"));
        mvc.perform(get(C + "/" + over + "/records").with(admin))
                .andExpect(jsonPath("$.data.revert.due").value("DONE"))
                .andExpect(jsonPath("$.data.revert.markGap").value("LATE"))
                .andExpect(jsonPath("$.data.revert.live.revertId").value((int) r1))
                .andExpect(jsonPath("$.data.revert.live.revertType").value("RESTORED"))
                .andExpect(jsonPath("$.data.revert.live.createdBy").value("admin1"));
        assertThat(cardCount(home())).isZero();
        mvc.perform(get(C).param("revertDue", "OVERDUE").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        // 표시 끝보다 일찍 → EARLY, 같은 날 → 경고 없음
        revert(open, "SUPERSEDED", todayS(), "ECO-NEW-9 로 정식 변경").andExpect(status().isOk());
        mvc.perform(get(C + "/" + open + "/records").with(admin)).andExpect(jsonPath("$.data.revert.markGap").value("EARLY"));
        revert(same, "RESTORED", todayS(), "복귀").andExpect(status().isOk());
        mvc.perform(get(C + "/" + same + "/records").with(admin)).andExpect(jsonPath("$.data.revert.markGap").doesNotExist());
        // PLM 표시 끝은 그대로(QMS 는 쓰지 않는다 — Q4-2)
        assertThat(accounts.query(comp, "select mark_until from plm_eco where eco_id = ?", java.time.LocalDate.class, open))
                .isEqualTo(today.plusDays(5));

        // 두 번 → 409, 무효 → 다시 기한 지남 → 다시 기록
        revert(over, "RESTORED", todayS(), "또").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_REVERT_STATE"));
        postJson(C + "/reverts/" + r1 + "/void", "{\"rowVer\":" + ver("qms_change_revert", "revert_id", r1) + ",\"reason\":\"복귀일 오기\"}")
                .andExpect(status().isOk());
        assertThat(cardCount(home())).isEqualTo(1);
        long r2 = id(revert(over, "RESTORED", todayS(), "다시 확인"));
        mvc.perform(get(C + "/" + over + "/records").with(admin))
                .andExpect(jsonPath("$.data.revert.history.length()").value(2))
                .andExpect(jsonPath("$.data.revert.history[0].revertId").value((int) r2))
                .andExpect(jsonPath("$.data.revert.history[1].status").value("VOID"))
                .andExpect(jsonPath("$.data.revert.history[1].voidReason").value("복귀일 오기"));
        assertThat(cardCount(home())).isZero();
    }

    // ---------- 이상 사용: 복귀 ----------

    @Test
    void 이상_복귀_상태_날짜_입력_무효_권한() throws Exception {
        long t = temp("ECO-T-1", -1);
        long draft = eco(comp, userId, "ECO-T-D", "TEMP", -1);
        change(comp, draft, "복귀");
        long proc = eco(comp, userId, "ECO-P-1", "PROCESS_4M", -1);
        change(comp, proc, null);
        release(comp, proc);
        long bare = eco(comp, userId, "ECO-T-N", "TEMP", -1);
        release(comp, bare);
        // 상태: 작성 중·임시 아님·4M 세부 없음 → 409, 없는 ECO → 404
        revert(draft, "RESTORED", todayS(), "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_REVERT_STATE"));
        revert(proc, "RESTORED", todayS(), "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_REVERT_STATE"));
        revert(bare, "RESTORED", todayS(), "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_REVERT_STATE"));
        revert(9_999_999L, "RESTORED", todayS(), "x").andExpect(status().isNotFound());
        // 날짜: 내일(미래)·적용일(오늘) 전·형식
        revert(t, "RESTORED", today.plusDays(1).toString(), "x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("revertedOn")).andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        revert(t, "RESTORED", today.minusDays(1).toString(), "x").andExpect(jsonPath("$.errors[0].code").value("BEFORE_RELEASED"));
        revert(t, "RESTORED", "2026-13-01", "x").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 입력: 구분 없음·틀린 코드, 보이지 않는 글자만인 메모 → 끝까지 모아서
        revert(t, null, null, "​ ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(3));
        revert(t, "LATER", todayS(), "x").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        postJson(C + "/" + t + "/reverts", "{\"revertType\":\"RESTORED\",\"revertedOn\":\"" + todayS() + "\",\"note\":\"" + "가".repeat(1001) + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        assertThat(accounts.query(comp, "select count(*) from qms_change_revert", Integer.class)).isZero();

        long r = id(revert(t, "RESTORED", todayS(), "복귀"));
        int v = ver("qms_change_revert", "revert_id", r);
        // 무효: 사유 없음 400, rowVer 다름 409, 두 번 409
        postJson(C + "/reverts/" + r + "/void", "{\"rowVer\":" + v + ",\"reason\":\"​\"}").andExpect(status().isBadRequest());
        postJson(C + "/reverts/" + r + "/void", "{\"reason\":\"x\"}").andExpect(status().isBadRequest());
        postJson(C + "/reverts/" + r + "/void", "{\"rowVer\":" + (v + 5) + ",\"reason\":\"x\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        postJson(C + "/reverts/" + r + "/void", "{\"rowVer\":" + v + ",\"reason\":\"x\"}").andExpect(status().isOk());
        postJson(C + "/reverts/" + r + "/void", "{\"rowVer\":" + (v + 1) + ",\"reason\":\"x\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QMS_REVERT_STATE"));

        // 권한: 조회만 → 기록 403, 저장만 → 무효 403 (화면이 숨긴 버튼을 API 로 직접)
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "qms-change", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        mvc.perform(json(post(C + "/" + t + "/reverts"), "{\"revertType\":\"RESTORED\",\"revertedOn\":\"" + todayS() + "\",\"note\":\"x\"}").with(vw))
                .andExpect(status().isForbidden());
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "qms-change", "SEARCH", "SAVE");
        RequestPostProcessor sv = accounts.as(comp, saver, "saver1");
        long r2 = id(mvc.perform(json(post(C + "/" + t + "/reverts"), "{\"revertType\":\"RESTORED\",\"revertedOn\":\"" + todayS()
                + "\",\"note\":\"x\"}").with(sv)));
        mvc.perform(json(post(C + "/reverts/" + r2 + "/void"), "{\"rowVer\":0,\"reason\":\"x\"}").with(sv)).andExpect(status().isForbidden());
        mvc.perform(get(C + "/" + t + "/effect").param("n", "4").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(C + "/" + t + "/effect").param("days", "181").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(C).param("revertDue", "SOON").with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void 동시_복귀기록_둘이면_하나만() throws Exception {
        long t = temp("ECO-T-1", -1);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int k = i;
                Callable<Integer> call = () -> {
                    go.await();
                    return revert(t, "RESTORED", todayS(), "동시 " + k).andReturn().getResponse().getStatus();
                };
                fs.add(pool.submit(call));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : fs) {
                codes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from qms_change_revert where eco_id = ? and status = 'ACTIVE'", Integer.class, t))
                .isEqualTo(1);
    }

    // ---------- 정상 흐름 + 조용히 틀리는 값: 효과 ----------

    @Test
    void 효과비교_손검산_무효값_열린검사_취소NCR_경계_판정_무효뒤_다시() throws Exception {
        long e = eco(comp, userId, "ECO-P-1", "PROCESS_4M", 30);
        change(comp, e, null);
        mvc.perform(get(C + "/" + e + "/effect").with(admin)).andExpect(jsonPath("$.data.ready").value(false));
        postJson(C + "/" + e + "/effects", "{\"result\":\"OK\",\"note\":\"x\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QMS_EFFECT_STATE"));
        release(comp, e);

        insp(BASE + " - interval '10 days'", a("9.00"), a("Y"), "OK");                                  // 앞, n=5 이면 밖
        insp(BASE + " - interval '1 day'", a("10.00", "10.02", "10.04", "10.06", "10.03"), a("Y", "Y", "N", "Y", "Y"), "NG");
        insp(BASE + " - interval '2 days'", a("5.00"), a("N"), "VOID");                                  // 무효 검사 — 어디에도 안 셈
        insp(BASE, a("10.00", "10.01"), a("Y", "Y", "Y"), "OK");                                          // 기준 정각 = 뒤
        long a2 = insp("now()", a("9.99", "10.00", "10.00", "12.00"), a("Y", "Y"), "OPEN");
        // 12.00 은 무효(정정) — 무효 행·무효된 원래 행 모두 빠진다
        long l = line1(a2);
        long m12 = accounts.query(comp, "select meas_id from qms_insp_meas where insp_line_id = ? and sample_no = 4", Long.class, l);
        accounts.exec(comp, "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, void_of_id, occurred_at, recorded_by) values (?, ?, 4, ?, now(), 't')",
                comp, l, m12);
        accounts.exec(comp, "update qms_insp set status = 'JUDGED', result = 'OK', judged_at = now(), judged_by = 't' where insp_id = ?", a2);
        insp("now()", a("9.00"), a("N"), "OPEN");                                                         // 열린 검사 — 안 셈
        ncr(BASE + " - interval '3 days'", 2);
        ncr("now()", 3);
        long canc = ncr("now()", 5);
        accounts.exec(comp, "update qms_ncr set status = 'CANCELLED', cancel_reason = '오등록' where ncr_id = ?", canc);

        String body = mvc.perform(get(C + "/" + e + "/effect").param("n", "5").param("days", "30").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.baseDate").value(todayS()))
                .andExpect(jsonPath("$.data.afterPartial").value(true))
                .andExpect(jsonPath("$.data.afterDaysElapsed").value(0))
                .andExpect(jsonPath("$.data.chars.length()").value(2))
                .andExpect(jsonPath("$.data.chars[0].refLabel").value("FG1-1"))
                .andExpect(jsonPath("$.data.chars[0].beforeVar.n").value(5))
                .andExpect(jsonPath("$.data.chars[0].beforeVar.outOfSpec").value(1))
                .andExpect(jsonPath("$.data.chars[0].afterVar.n").value(5))
                .andExpect(jsonPath("$.data.chars[0].afterVar.outOfSpec").value(0))
                .andExpect(jsonPath("$.data.chars[1].dataType").value("ATTRIBUTE"))
                .andExpect(jsonPath("$.data.chars[1].beforeAttr.n").value(5))
                .andExpect(jsonPath("$.data.chars[1].beforeAttr.ng").value(1))
                .andExpect(jsonPath("$.data.chars[1].afterAttr.n").value(5))
                .andExpect(jsonPath("$.data.chars[1].afterAttr.ng").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].itemCd").value("FG1"))
                .andExpect(jsonPath("$.data.items[0].before.inspCnt").value(2))
                .andExpect(jsonPath("$.data.items[0].before.inspNg").value(1))
                .andExpect(jsonPath("$.data.items[0].before.ncrCnt").value(1))
                .andExpect(jsonPath("$.data.items[0].before.ncrQty").value("2"))
                .andExpect(jsonPath("$.data.items[0].after.inspCnt").value(2))
                .andExpect(jsonPath("$.data.items[0].after.inspNg").value(0))
                .andExpect(jsonPath("$.data.items[0].after.ncrCnt").value(1))
                .andExpect(jsonPath("$.data.items[0].after.ncrQty").value("3"))
                .andReturn().getResponse().getContentAsString();
        // 손 검산(ChangeEffectCalcTest 와 같은 값): 앞 평균 10.03·σ √0.0005·Ppk 0.29814, 뒤 평균 10.00·σ √0.00005·Ppk 2.35702
        assertThat(JsonPath.<Number>read(body, "$.data.chars[0].beforeVar.mean").doubleValue()).isCloseTo(10.03, within(1e-9));
        assertThat(JsonPath.<Number>read(body, "$.data.chars[0].beforeVar.sd").doubleValue()).isCloseTo(Math.sqrt(0.0005), within(1e-9));
        assertThat(JsonPath.<Number>read(body, "$.data.chars[0].beforeVar.ppk").doubleValue()).isCloseTo(0.02 / (3 * Math.sqrt(0.0005)), within(1e-9));
        assertThat(JsonPath.<Number>read(body, "$.data.chars[0].afterVar.mean").doubleValue()).isCloseTo(10.0, within(1e-9));
        assertThat(JsonPath.<Number>read(body, "$.data.chars[0].afterVar.ppk").doubleValue()).isCloseTo(0.05 / (3 * Math.sqrt(0.00005)), within(1e-9));
        assertThat(JsonPath.<Number>read(body, "$.data.chars[1].beforeAttr.rate").doubleValue()).isEqualTo(0.2);
        assertThat(JsonPath.<Number>read(body, "$.data.items[0].before.ngRate").doubleValue()).isEqualTo(0.5);
        assertThat(JsonPath.<Number>read(body, "$.data.items[0].after.ngRate").doubleValue()).isEqualTo(0.0);
        // n = 30 이면 10일 전 9.00 도 앞에 들어간다(앞 = 기준 앞 마지막 n개)
        mvc.perform(get(C + "/" + e + "/effect").with(admin)).andExpect(jsonPath("$.data.n").value(30))
                .andExpect(jsonPath("$.data.chars[0].beforeVar.n").value(6))
                .andExpect(jsonPath("$.data.chars[0].beforeVar.outOfSpec").value(2));

        // 판정 → 두 번 409 → 무효 → NG
        long f1 = id(postJson(C + "/" + e + "/effects", "{\"result\":\"OK\",\"note\":\"Ppk 0.30 → 2.36, NG 율 50% → 0%\",\"n\":5,\"days\":30}"));
        mvc.perform(get(C + "/" + e + "/records").with(admin)).andExpect(jsonPath("$.data.effect.ready").value(true))
                .andExpect(jsonPath("$.data.effect.live.result").value("OK"))
                .andExpect(jsonPath("$.data.effect.live.baseDate").value(todayS()))
                .andExpect(jsonPath("$.data.effect.live.basisN").value(5))
                .andExpect(jsonPath("$.data.revert.temp").value(false));
        postJson(C + "/" + e + "/effects", "{\"result\":\"NG\",\"note\":\"x\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QMS_EFFECT_STATE"));
        postJson(C + "/" + e + "/effects", "{\"result\":\"MAYBE\",\"note\":\"\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        postJson(C + "/" + e + "/effects", "{\"result\":\"OK\",\"note\":\"x\",\"n\":201}").andExpect(status().isBadRequest());
        postJson(C + "/effects/" + f1 + "/void", "{\"rowVer\":" + ver("qms_change_effect", "effect_id", f1) + ",\"reason\":\"기간 짧음\"}")
                .andExpect(status().isOk());
        postJson(C + "/" + e + "/effects", "{\"result\":\"NG\",\"note\":\"30일 뒤 다시 보니 악화\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(result || ':' || status, ',' order by effect_id) from qms_change_effect", String.class))
                .isEqualTo("OK:VOID,NG:ACTIVE");
        // 검사 강화 없음(Q4-3) — 검사계획은 그대로
        assertThat(accounts.query(comp, "select count(*) from qms_insp_plan", Integer.class)).isEqualTo(1);
    }

    // ---------- 서명 ----------

    @Test
    void 서명_정책Y_토큰없으면403_토큰이면서명_current() throws Exception {
        policy("QMS_ESIGN_REQUIRED", "Y");
        long t = temp("ECO-T-1", -1);
        mvc.perform(get(C + "/" + t + "/records").with(admin)).andExpect(jsonPath("$.data.esignRequired").value(true));
        revert(t, "RESTORED", todayS(), "복귀").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QMS_ESIGN_REQUIRED"));
        assertThat(accounts.query(comp, "select count(*) from qms_change_revert", Integer.class)).as("롤백").isZero();
        MockHttpSession s = new MockHttpSession();
        String tok = esignToken(s, "APPROVE", "qms_change", t);
        postJson(s, C + "/" + t + "/reverts", "{\"revertType\":\"RESTORED\",\"revertedOn\":\"" + todayS() + "\",\"note\":\"복귀\",\"esignToken\":\"" + tok + "\"}")
                .andExpect(status().isOk());
        // 같은 토큰 다시 → 403(효과 판정에 재사용 불가)
        postJson(s, C + "/" + t + "/effects", "{\"result\":\"OK\",\"note\":\"x\",\"esignToken\":\"" + tok + "\"}").andExpect(status().isForbidden());
        String tok2 = esignToken(s, "APPROVE", "qms_change", t);
        postJson(s, C + "/" + t + "/effects", "{\"result\":\"OK\",\"note\":\"효과 있음\",\"esignToken\":\"" + tok2 + "\"}").andExpect(status().isOk());
        assertThat(esignCount("qms_change", t)).isEqualTo(2);
        mvc.perform(get(C + "/" + t + "/records").with(admin)).andExpect(jsonPath("$.data.signatures.length()").value(2))
                .andExpect(jsonPath("$.data.signatures[0].current").value(true))
                .andExpect(jsonPath("$.data.signatures[1].current").value(true))
                .andExpect(jsonPath("$.data.signatures[0].meaningCd").value("APPROVE"));
    }

    @Test
    void 서명_정책N_토큰주면_서명_없으면_기록만() throws Exception {
        long t = temp("ECO-T-1", -1);
        long u = temp("ECO-T-2", -1);
        revert(t, "RESTORED", todayS(), "복귀").andExpect(status().isOk());
        assertThat(esignCount("qms_change", t)).isZero();
        MockHttpSession s = new MockHttpSession();
        String tok = esignToken(s, "APPROVE", "qms_change", u);
        postJson(s, C + "/" + u + "/reverts", "{\"revertType\":\"RESTORED\",\"revertedOn\":\"" + todayS() + "\",\"note\":\"복귀\",\"esignToken\":\"" + tok + "\"}")
                .andExpect(status().isOk());
        assertThat(esignCount("qms_change", u)).isEqualTo(1);
    }

    // ---------- A사로 B사 ----------

    @Test
    void B사는_A사_복귀효과를_보지도_쓰지도_무효하지도_못한다() throws Exception {
        long over = temp("ECO-T-1", -2);
        long t = temp("ECO-T-2", -2);
        long r = id(revert(t, "RESTORED", todayS(), "복귀"));
        long f = id(postJson(C + "/" + t + "/effects", "{\"result\":\"OK\",\"note\":\"x\"}"));
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-change", "SEARCH", "SAVE", "DELETE");
        accounts.grant(other, bUser, "QA", "home-main", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(C + "/" + t + "/records").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(C + "/" + t + "/effect").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(C + "/" + over + "/reverts"), "{\"revertType\":\"RESTORED\",\"revertedOn\":\"" + todayS() + "\",\"note\":\"x\"}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(C + "/" + over + "/effects"), "{\"result\":\"OK\",\"note\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(C + "/reverts/" + r + "/void"), "{\"rowVer\":0,\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(C + "/effects/" + f + "/void"), "{\"rowVer\":0,\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(C).param("revertDue", "OVERDUE").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        String bh = mvc.perform(get(CARDS).with(b)).andReturn().getResponse().getContentAsString();
        assertThat(cardCount(bh)).as("A사 기한 지남은 B사 카드에 0").isZero();
        assertThat(cardCount(home())).isEqualTo(1);
        // 직접 SQL: B사로 A사 4M 세부에 기록 → 같은 회사 확인, A사 기록 무효 → 0행
        constraint(other, "fk_qms_change_revert_change_comp", () -> accounts.exec(other,
                "insert into qms_change_revert (comp_cd, eco_id, revert_type, reverted_on, note, created_by, updated_by) values (?, ?, 'RESTORED', current_date, 'x', 't', 't')",
                other, over));
        assertThat(accounts.exec(other, "update qms_change_revert set status = 'VOID', void_reason = 'x', voided_at = now(), voided_by = 't' where revert_id = ?", r))
                .isZero();
        assertThat(accounts.query(comp, "select status from qms_change_revert where revert_id = ?", String.class, r)).isEqualTo("ACTIVE");
    }

    // ---------- 직접 SQL ----------

    @Test
    void 직접SQL_적용전_임시아님_살아있는행둘_무효외수정_삭제_거부() throws Exception {
        long t = temp("ECO-T-1", -1);
        long draft = eco(comp, userId, "ECO-T-D", "TEMP", -1);
        change(comp, draft, "복귀");
        long proc = eco(comp, userId, "ECO-P-1", "PROCESS_4M", -1);
        change(comp, proc, null);
        release(comp, proc);
        String ins = "insert into qms_change_revert (comp_cd, eco_id, revert_type, reverted_on, note, status, created_by, updated_by) "
                + "values (?, ?, 'RESTORED', current_date, 'x', ?, 't', 't')";
        constraint(comp, "ck_qms_change_revert_eco", () -> accounts.exec(comp, ins, comp, draft, "ACTIVE"));
        constraint(comp, "ck_qms_change_revert_eco", () -> accounts.exec(comp, ins, comp, proc, "ACTIVE"));
        constraint(comp, "ck_qms_change_revert_active", () -> accounts.exec(comp, ins, comp, t, "VOID"));
        constraint(comp, "ck_qms_change_revert_type", () -> accounts.exec(comp, ins.replace("'RESTORED'", "'LATER'"), comp, t, "ACTIVE"));
        constraint(comp, "ck_qms_change_effect_eco", () -> accounts.exec(comp,
                "insert into qms_change_effect (comp_cd, eco_id, result, note, base_date, basis_n, basis_days, created_by, updated_by) "
                        + "values (?, ?, 'OK', 'x', current_date, 30, 30, 't', 't')", comp, draft));
        accounts.exec(comp, ins, comp, t, "ACTIVE");
        constraint(comp, "uq_qms_change_revert_live", () -> accounts.exec(comp, ins, comp, t, "ACTIVE"));
        long r = accounts.query(comp, "select revert_id from qms_change_revert where eco_id = ?", Long.class, t);
        constraint(comp, "ck_qms_change_revert_immutable", () -> accounts.exec(comp, "update qms_change_revert set note = 'y' where revert_id = ?", r));
        constraint(comp, "ck_qms_change_revert_immutable", () -> accounts.exec(comp,
                "update qms_change_revert set reverted_on = reverted_on - 1, status = 'VOID', void_reason = 'x', voided_at = now(), voided_by = 't' where revert_id = ?", r));
        constraint(comp, "ck_qms_change_revert_void", () -> accounts.exec(comp, "update qms_change_revert set status = 'VOID' where revert_id = ?", r));
        accounts.exec(comp, "update qms_change_revert set status = 'VOID', void_reason = 'x', voided_at = now(), voided_by = 't' where revert_id = ?", r);
        constraint(comp, "ck_qms_change_revert_immutable", () -> accounts.exec(comp,
                "update qms_change_revert set status = 'ACTIVE', void_reason = null, voided_at = null, voided_by = null where revert_id = ?", r));
        // 앱 계정은 DELETE 권한부터 없다(REVOKE)
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_change_revert where revert_id = ?", r));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_change_effect"));
    }
}
