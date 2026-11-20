package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopWorkDtos.CorrectForm;
import io.smarthappy.module.pop.PopWorkService;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * CMS 2부 C10 (docs/33 11.10) — 카운터 자동 입력: 실적 → SHOTS(÷ 사이클당 수량), 닫힌 PRD 구간 → RUN_HOURS, 금형은 설치 기간 안만,
 * 두 번 세지 않음(재실행·늦게 들어온 원장), 카운터 전 원장은 안 셈, 정정된 실적은 안 셈(셌던 뒤 정정은 그대로 — D25),
 * 사용량 예방점검이 같은 트랜잭션에서 생김, 직접 SQL, A사로 B사(정기 작업 회사별).
 */
@AutoConfigureMockMvc
class CmsCounterAutoTest extends PostgresTestBase {

    private static final String PW = "Hazel-gear-52";
    private static final String C = "/api/cms/counters";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CmsJobs jobs;

    @Autowired
    PopWorkService pop;

    String comp;
    RequestPostProcessor admin;
    long line;
    long prs1;
    long prs2;
    long mold;
    long worker;
    long wo;
    long proc;
    final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("CA");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "CMS", "cms-counter", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "CMS2", "cms-chk-tmpl", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "CMS3", "cms-pm-plan", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long area = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'AREA', 'A1', 'A1', 't', 't') returning node_id""", Long.class, comp);
        line = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LINE', 'L1', 'L1', 't', 't') returning node_id""", Long.class, comp, area);
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        prs1 = equip("PRS-01", cls);
        prs2 = equip("PRS-02", cls);
        mold = accounts.query(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, life_shots, created_by, updated_by)
                values (?, 'M-100', 'MOLD', '금형 100', 100000, 't', 't') returning asset_id""", Long.class, comp);
        worker = accounts.query(comp, "insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, created_by, updated_by) values (?, 'E1', '작업자', 'P1', 't', 't') returning worker_id",
                Long.class, comp);
        accounts.exec(comp, "insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by) values (?, 'D-X', '불량', 'WELD', 't', 't')", comp);
        workOrder();
        long tmpl = id(postJson("/api/cms/chk-tmpls", "{\"tmplCd\":\"PRS-D\",\"tmplNm\":\"프레스 정기\"}"));
        mvc.perform(json(put("/api/cms/chk-tmpls/" + tmpl + "/items?rowVer=0"), "[{\"seq\":1,\"itemNm\":\"클러치\",\"judgeType\":\"OKNG\"}]").with(admin))
                .andExpect(status().isOk());
    }

    private long equip(String cd, long cls) {
        return accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning equip_id""", Long.class, comp, cd, cd + " 설비", cls, line);
    }

    /** 프레스 공정(사이클당 2개 — 금형 캐비티 2) 작업지시 발행 + 공정 시작 */
    private void workOrder() throws Exception {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'RM', 'RM', 'RM', 'BUY', 'EA', 't', 't')""", comp, comp);
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'PRS', '프레스', 'WELD', 't', 't')", comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't') returning routing_id""",
                Long.class, comp);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, units_per_cycle, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'PRS'), ?, 10, 2, 't', 't')""", comp, rid, line);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't') returning bom_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 1, 'EA', 'NORMAL', 'BACKFLUSH', 't', 't')""", comp, bom);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        MvcResult m = mvc.perform(json(post("/api/production/wos"), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"100000\",\"planStart\":\"" + today + "\"}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        Object v = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        wo = v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.woId")).longValue();
        mvc.perform(json(post("/api/production/wos/" + wo + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        proc = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ?", Long.class, wo);
        runner.call(comp, () -> {
            jdbc.update("""
                    insert into prd_wo_proc_event (comp_cd, wo_id, wo_proc_id, event_cd, gate_mode, work_day, client_req_id, occurred_at,
                                                   entry_method, recorded_by, performed_by)
                    values (?, ?, ?, 'START', 'OK', (now() at time zone 'Asia/Seoul')::date, 'REQ-START-CA', now(), 'KEY', 'x', ?)""", comp, wo, proc, worker);
            return jdbc.update("update prd_wo_proc set status = 'RUN' where wo_proc_id = ?", proc);
        });
    }

    private static String ago(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC).toString();
    }

    /** 폐기만 있는 실적(LOT 불필요) — 수량 = 폐기 */
    private long result(long equip, int qty, String at) {
        int n = seq.incrementAndGet();
        return runner.call(comp, () -> {
            long rid = jdbc.queryForObject("""
                    insert into prd_result (comp_cd, wo_id, wo_proc_id, equip_id, good_qty, scrap_qty, rework_qty, repair_qty, unit_cd, work_day,
                                            client_req_id, occurred_at, entry_method, recorded_by, performed_by)
                    values (?, ?, ?, ?, 0, ?, 0, 0, 'EA', (now() at time zone 'Asia/Seoul')::date, ?, ?::timestamptz, 'KEY', 'x', ?) returning result_id""",
                    Long.class, comp, wo, proc, equip, qty, "REQ-RESULT-" + n, at, worker);
            jdbc.update("insert into prd_result_defect (comp_cd, result_id, kind, defect_id, qty) select ?, ?, 'SCRAP', defect_id, ? from qms_defect",
                    comp, rid, qty);
            return rid;
        });
    }

    private long state(long equip, String cat, String start, String end) {
        return accounts.query(comp, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, start_at, end_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, ?::timestamptz, ?::timestamptz, 'AUTO', ?::timestamptz, 'AUTO', 'col') returning state_log_id""",
                Long.class, comp, equip, cat, start, end, start);
    }

    private long counter(String cd, String type, String target, String kind, int scale) throws Exception {
        return id(postJson(C, "{\"counterCd\":\"" + cd + "\",\"counterNm\":\"" + cd + "\",\"targetType\":\"" + type + "\",\"targetCd\":\""
                + target + "\",\"counterKind\":\"" + kind + "\",\"scale\":" + scale + "}"));
    }

    private BigDecimal total(long counterId) {
        return accounts.query(comp, "select coalesce(sum(delta_qty), 0) from cms_counter_reading where counter_id = ? and void_yn = 'N'",
                BigDecimal.class, counterId);
    }

    private int autoReadings(long counterId) {
        return accounts.query(comp, "select count(*) from cms_counter_reading where counter_id = ? and source = 'AUTO'", Integer.class, counterId);
    }

    private CounterAutoService.AutoResult run() {
        return runner.call(comp, () -> runnerAuto.run());
    }

    @Autowired
    CounterAutoService runnerAuto;

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    // ---------- 실적 → 타수 ----------

    @Test
    void 실적은_사이클당수량으로_나눠_세고_두번_안센다_카운터전_원장과_정정된_실적은_안센다() throws Exception {
        long before = result(prs1, 100, ago(120));   // 카운터 만들기 전에 기록 → 안 셈
        long c = counter("PRS1-SHOT", "EQUIP", "PRS-01", "SHOTS", 0);
        result(prs1, 40, ago(60));                     // 40 ÷ 2 = 20 타
        result(prs2, 10, ago(60));                     // 다른 설비
        long corrected = result(prs1, 30, ago(50));    // 세기 전에 정정 → 안 셈
        long term = accounts.query(comp, "insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, created_by, updated_by) values (?, 'TAB-CA', 't', 'P1', 't', 't') returning terminal_id",
                Long.class, comp);
        TerminalPrincipal t = new TerminalPrincipal(comp, term, "TAB-CA", "t", "P1", null, null, null, "POP");
        PopWorker w = new PopWorker(term, worker, "E1", "작업자", 0L, 0L);
        runner.call(comp, () -> pop.correct(t, w, corrected, new CorrectForm("착오")));

        CounterAutoService.AutoResult r1 = run();
        assertThat(total(c)).isEqualByComparingTo("20");
        assertThat(r1.sources()).isEqualTo(1);
        assertThat(accounts.query(comp, "select remark from cms_counter_reading where counter_id = ? and source = 'AUTO'", String.class, c))
                .isEqualTo("AUTO RESULT×1");
        // 다시 돌려도 그대로(새 읽기 없음), 늦게 들어온 실적은 다음 실행에서
        run();
        assertThat(autoReadings(c)).isEqualTo(1);
        long late = result(prs1, 6, ago(90));          // 발생은 앞이지만 늦게 기록
        run();
        assertThat(total(c)).isEqualByComparingTo("23");
        assertThat(autoReadings(c)).isEqualTo(2);
        // 센 뒤 정정은 빼지 않는다(D25 — 사람이 무효·보정)
        runner.call(comp, () -> pop.correct(t, w, late, new CorrectForm("착오")));
        run();
        assertThat(total(c)).isEqualByComparingTo("23");
        assertThat(accounts.query(comp, "select count(*) from cms_counter_src where src_id = ?", Integer.class, before)).isZero();
    }

    // ---------- 상태 구간 → 가동 시간, 금형 설치 기간 ----------

    @Test
    void 닫힌_PRD만_가동시간_열린구간은_닫힌뒤_금형은_설치기간_안만() throws Exception {
        long hours = counter("PRS1-HR", "EQUIP", "PRS-01", "RUN_HOURS", 2);
        long shots = counter("M100-SHOT", "ASSET", "M-100", "SHOTS", 0);
        long moldHours = counter("M100-HR", "ASSET", "M-100", "RUN_HOURS", 2);
        // 카운터는 6시간 전에 만든 것으로(가동 시간은 카운터를 만든 시각 앞을 자른다 — 아래 구간이 모두 그 뒤)
        accounts.exec(comp, "update cms_counter set created_at = now() - interval '6 hours'");
        // 금형: 4시간 전 ~ 1시간 전 PRS-01 에 설치
        accounts.exec(comp, """
                insert into mst_asset_install (comp_cd, asset_id, equip_id, installed_at, removed_at, installed_by, removed_by)
                values (?, ?, ?, ?::timestamptz, ?::timestamptz, 't', 't')""", comp, mold, prs1, ago(240), ago(60));
        state(prs1, "PRD", ago(300), ago(180));        // 2h (금형과는 1h 겹침)
        state(prs1, "UDT", ago(180), ago(170));        // PRD 아님
        long open = state(prs1, "PRD", ago(170), null); // 열린 구간 — 아직 안 셈
        result(prs1, 20, ago(200));                    // 설치 안 → 금형 10 타
        result(prs1, 50, ago(30));                     // 해체 뒤 → 금형 안 셈
        run();
        assertThat(total(hours)).isEqualByComparingTo("2");
        assertThat(total(moldHours)).isEqualByComparingTo("1");
        assertThat(total(shots)).isEqualByComparingTo("10");
        // 구간이 닫히면 다음 실행에서(늦게 닫힌 구간도 놓치지 않음)
        accounts.exec(comp, "update cms_equip_state_log set end_at = ?::timestamptz where state_log_id = ?", ago(110), open);
        run();
        assertThat(total(hours)).isEqualByComparingTo("3");
        assertThat(total(moldHours)).isEqualByComparingTo("2");   // 170분 전 ~ 110분 전 중 설치 끝(60분 전) 안 = 1h
    }

    @Test
    void 카운터_자릿수에_맞추되_나머지는_다음으로_넘긴다() throws Exception {
        long hours = counter("PRS1-HR0", "EQUIP", "PRS-01", "RUN_HOURS", 0);   // 시간 단위(소수 없음)
        accounts.exec(comp, "update cms_counter set created_at = now() - interval '6 hours'");
        state(prs1, "PRD", ago(300), ago(250));        // 50분
        run();
        assertThat(total(hours)).isEqualByComparingTo("0");   // 0.83h → 0 (나머지는 출처에 남음)
        state(prs1, "PRD", ago(240), ago(190));        // 50분 더 = 1.67h
        run();
        assertThat(total(hours)).isEqualByComparingTo("1");
        state(prs1, "PRD", ago(180), ago(160));        // 20분 더 = 2.0h
        run();
        assertThat(total(hours)).isEqualByComparingTo("2");   // 매번 반올림했다면 1 + 1 + 0 = 2 가 아니라 3 이 될 수 있다
        assertThat(accounts.query(comp, "select count(*) from cms_counter_reading where counter_id = ? and source = 'AUTO' and scale(delta_qty) > 0 and delta_qty <> trunc(delta_qty)",
                Integer.class, hours)).isZero();
    }

    // ---------- 사용량 예방점검 ----------

    @Test
    void 정기작업이_넣은_증가량으로_사용량_예방점검이_같은_트랜잭션에서_생긴다() throws Exception {
        long c = counter("PRS1-SHOT", "EQUIP", "PRS-01", "SHOTS", 0);
        id(postJson("/api/cms/pm-plans", "{\"planCd\":\"U1\",\"planNm\":\"타수 점검\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"tmplCd\":\"PRS-D\","
                + "\"execMode\":\"CHECK\",\"cycleType\":\"USAGE\",\"counterCd\":\"PRS1-SHOT\",\"usageInterval\":\"100\",\"usageLead\":\"10\","
                + "\"scheduleBasis\":\"FIXED\"}"));
        result(prs1, 100, ago(30));                     // 50 타 — 예정 100 의 선행 10 전
        jobs.counterAuto();
        assertThat(accounts.query(comp, "select count(*) from cms_chk_run", Integer.class)).isZero();
        result(prs1, 90, ago(20));                      // +45 = 95 ≥ 90(예정 − 선행) → 예정 생성
        assertThat(jobs.counterAuto().allSucceeded()).isTrue();
        assertThat(total(c)).isEqualByComparingTo("95");
        assertThat(accounts.query(comp, "select count(*) from cms_chk_run where due_usage = 100", Integer.class)).isEqualTo(1);
    }

    // ---------- 직접 SQL·A사로 B사 ----------

    @Test
    void 직접SQL_출처는_추가만_수기읽기에_못붙임_A사로_B사() throws Exception {
        long c = counter("PRS1-SHOT", "EQUIP", "PRS-01", "SHOTS", 0);
        long r = result(prs1, 4, ago(10));
        run();
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "update cms_counter_src set qty = 0 where counter_id = ?", c)))).contains("42501");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_counter_src where counter_id = ?", c)))).contains("42501");
        mvc.perform(json(post(C + "/" + c + "/readings"), "{\"gaugeValue\":\"5\"}").with(admin)).andExpect(status().isOk());
        long manual = accounts.query(comp, "select max(reading_id) from cms_counter_reading where counter_id = ? and source = 'MANUAL'", Long.class, c);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into cms_counter_src (comp_cd, counter_id, src_kind, src_id, reading_id, qty) values (?, ?, 'RESULT', 999, ?, 1)", comp, c, manual))))
                .contains("fk_cms_counter_src_ctx_comp");
        // 같은 원장 행 두 번 → PK
        long auto = accounts.query(comp, "select reading_id from cms_counter_reading where counter_id = ? and source = 'AUTO'", Long.class, c);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into cms_counter_src (comp_cd, counter_id, src_kind, src_id, reading_id, qty) values (?, ?, 'RESULT', ?, ?, 1)", comp, c, r, auto))))
                .contains("pk_cms_counter_src");

        // B사: 같은 설비 코드 카운터·실적 없음 → 정기 작업(회사별)이 A사 원장을 B사 카운터에 넣지 않는다
        String b = accounts.newComp("CB");
        accounts.addPlant(b, "P1", "B");
        long bCls = accounts.query(b, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, b);
        long bEq = accounts.query(b, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', 'b', ?, 't', 't') returning equip_id",
                Long.class, b, bCls);
        long bc = accounts.query(b, """
                insert into cms_counter (comp_cd, counter_cd, counter_nm, target_type, equip_id, counter_kind, created_by, updated_by)
                values (?, 'PRS1-SHOT', 'b', 'EQUIP', ?, 'SHOTS', 't', 't') returning counter_id""", Long.class, b, bEq);
        result(prs1, 8, ago(5));
        jobs.counterAuto();
        assertThat(accounts.query(b, "select count(*) from cms_counter_reading where counter_id = ?", Integer.class, bc)).isZero();
        assertThat(total(c)).isEqualByComparingTo("6");    // 2(자동 4 ÷ 2) + 0(첫 계기 값 = 기준) + 4(자동 8 ÷ 2)
    }
}
