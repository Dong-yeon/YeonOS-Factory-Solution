package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 장비 시리얼 구성(as-built, docs/77) — 시리얼 만들기·장착·탈거·교체(역분개)·BOM 대비·완료판정 경고(막지 않음)·역추적.
 * 이상 사용: 같은 부품 시리얼 두 장비(순차·동시), 같은 시리얼 동시 저장(덮어쓰기 없음 → 409), A사 계정으로 B사 시리얼·LOT,
 * 권한 없는 계정 403, 원장 직접 수정·삭제, 멱등 키 두 번, 두 번 탈거, 값 오류(LOT 없음·잔량 초과·부품 시리얼 수량 2).
 *
 * 준비: 장비 FG(EA) × 계획 2대, 라우팅 10 조립 · 20 시험, BOM = CPU 1(LOT, 공정 10) · BOLT 4(LOT, 공정 10) ·
 * 대체 그룹 A[FAN 우선 1 비율 100 · FANB 우선 2 비율 0](LOT 비관리, 공정 20). CABLE 은 BOM 밖 부품
 */
@AutoConfigureMockMvc
class SerialAsBuiltTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String S = "/api/production/serials";
    private static final String T = "/api/production/serial-trace";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    RequestPostProcessor viewer;
    long woId;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("SN");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "PRD", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "PRD", "prd-serial", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(comp, userId, "PRD", "prd-serial-trace", "SEARCH", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        long viewerId = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewerId, "VIEW", "prd-serial", "SEARCH");
        viewer = accounts.as(comp, viewerId, "viewer1");
        woId = seed(comp, admin);
    }

    // ---------- 준비 ----------

    private long seed(String c, RequestPostProcessor who) throws Exception {
        accounts.addPlant(c, "P1", "1공장");
        String today = accounts.query(c, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        for (String[] i : new String[][] {{"FG", "Y"}, {"CPU", "Y"}, {"BOLT", "Y"}, {"FAN", "N"}, {"FANB", "N"}, {"CABLE", "N"}, {"CHIP", "Y"}}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', ?, 't', 't')""", c, i[0], i[0] + " 품목", i[1]);
        }
        accounts.exec(c, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) "
                + "values (?, 'ASM', '조립', 'ASSEMBLY', 't', 't'), (?, 'TST', '시험', 'ASSEMBLY', 't', 't')", c, c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                select ?, item_id, 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't' from mst_item where item_cd = 'FG'
                returning routing_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ASM'), 't', 't'),
                       (?, ?, 20, (select proc_id from mst_proc where proc_cd = 'TST'), 't', 't')""", c, rid, c, rid);
        accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long bom = accounts.query(c, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                select ?, item_id, 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't' from mst_item where item_cd = 'FG'
                returning bom_id""", Long.class, c);
        Object[][] lines = {{"CPU", "1", 10, null, null, null}, {"BOLT", "4", 10, null, null, null}, {"FAN", "1", 20, "A", 1, "100"},
            {"FANB", "1", 20, "A", 2, "0"}};
        int no = 10;
        for (Object[] l : lines) {
            accounts.exec(c, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, op_seq, issue_method,
                                              alt_grp, alt_priority, alt_pct, created_by, updated_by)
                    values (?, ?, ?, (select item_id from mst_item where item_cd = ?), ?::numeric, 'EA', 'NORMAL', ?::integer, 'MANUAL',
                            ?, ?::smallint, ?::numeric, 't', 't')""", c, bom, no, l[0], l[1], l[2], l[3], l[4], l[5]);
            no += 10;
        }
        accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        long wo = id(mvc.perform(json(post("/api/production/wos"),
                "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"2\",\"planStart\":\"" + today + "\"}").with(who)));
        mvc.perform(json(post("/api/production/wos/" + wo + "/release"), "{\"rowVer\":0}").with(who)).andExpect(status().isOk());
        for (String[] l : new String[][] {{"L-CPU-A", "CPU", "5"}, {"L-CPU-B", "CPU", "5"}, {"L-BOLT", "BOLT", "100"}, {"L-CHIP", "CHIP", "10"}}) {
            lot(c, l[0], l[1], l[2]);
        }
        return wo;
    }

    private long lot(String c, String no, String item, String qty) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', ?::numeric, 'EA', now(), 't', 't' from mst_item where item_cd = ? returning lot_id""",
                Long.class, c, no, qty, item);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 시리얼 n 대 → id 목록 */
    private List<Long> create(int n) throws Exception {
        MvcResult m = mvc.perform(json(post(S), "{\"woId\":" + woId + ",\"count\":" + n + "}").with(admin))
                .andExpect(status().isOk()).andReturn();
        List<Number> ids = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        return ids.stream().map(Number::longValue).toList();
    }

    private int rowVer(long serialId) {
        return accounts.query(comp, "select row_ver from prd_serial where serial_id = ?", Integer.class, serialId);
    }

    private ResultActions install(RequestPostProcessor who, long serialId, String body) throws Exception {
        return mvc.perform(json(post(S + "/" + serialId + "/parts"), "{\"rowVer\":" + rowVer(serialId) + "," + body + "}").with(who));
    }

    private long installOk(long serialId, String body) throws Exception {
        return id(install(admin, serialId, body));
    }

    private ResultActions remove(long serialId, long partId, String reason) throws Exception {
        return mvc.perform(json(post(S + "/" + serialId + "/parts/" + partId + "/remove"),
                "{\"rowVer\":" + rowVer(serialId) + ",\"reason\":\"" + reason + "\"}").with(admin));
    }

    private String balance(String lotNo) {
        return accounts.query(comp, "select trim_scale(hb_lot_balance(lot_id))::text from lot_master where lot_no = ?", String.class, lotNo);
    }

    // ---------- 정상 ----------

    @Test
    void 시리얼은_채번되고_장비_LOT_이_UNIT_으로_생기며_계획_수량을_넘지_못한다() throws Exception {
        List<Long> ids = create(2);
        assertThat(ids).hasSize(2);
        List<Map<String, Object>> rows = runnerRows();
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat((String) r.get("serial_no")).matches("^SN\\d{4}-\\d{4}$");
            assertThat(r.get("lot_no")).isEqualTo(r.get("serial_no"));
            assertThat(r.get("origin")).isEqualTo("UNIT");
            assertThat(r.get("qty")).isEqualTo("1");
            assertThat(r.get("wo_id")).isNull();          // 장비 LOT 은 생산 LOT(PRODUCED) 집계에 안 섞인다
        });
        // 3대째 = 계획 2대 초과
        mvc.perform(json(post(S), "{\"woId\":" + woId + ",\"count\":1}").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("count")).andExpect(jsonPath("$.errors[0].code").value("SERIAL_OVER_PLAN"));
        // 직접 SQL 도 트리거가 막는다(지시 잠금 + 수량)
        assertThat(io.smarthappy.platform.common.db.PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                with l as (insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                           select ?, 'SN-X', item_id, 'UNIT', 1, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG' returning lot_id, item_id)
                insert into prd_serial (comp_cd, serial_no, wo_id, item_id, unit_lot_id, created_by, updated_by)
                select ?, 'SN-X', ?, item_id, lot_id, 't', 't' from l""", comp, comp, woId)))).contains("ck_prd_serial_count");
        // 번호·지시는 바꿀 수 없다
        assertThat(io.smarthappy.platform.common.db.PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_serial set serial_no = 'SN-Y' where serial_id = ?", ids.get(0))))).contains("ck_prd_serial_fixed");
    }

    private List<Map<String, Object>> runnerRows() {
        return accountsRows("""
                select s.serial_no, l.lot_no, l.origin, trim_scale(l.qty_created)::text as qty, l.wo_id
                  from prd_serial s join lot_master l on l.lot_id = s.unit_lot_id where s.wo_id = ? order by s.serial_no""", woId);
    }

    @Autowired
    io.smarthappy.platform.tenant.TenantRunner runner;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private List<Map<String, Object>> accountsRows(String sql, Object... args) {
        return runner.call(comp, () -> jdbc.queryForList(sql, args));
    }

    @Test
    void 장착_탈거_교체는_반대_행으로_쌓이고_부품_LOT_잔량이_따라간다() throws Exception {
        long s1 = create(1).get(0);
        long p1 = installOk(s1, "\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\",\"opSeq\":10");
        assertThat(balance("L-CPU-A")).isEqualTo("4");
        // 계보: 부품 LOT → 장비 LOT 투입
        assertThat(accounts.query(comp, """
                select count(*) from lot_genealogy g join prd_serial s on s.unit_lot_id = g.child_lot_id
                 where s.serial_id = ? and g.rel_type = 'CONSUME' and g.qty = 1""", Integer.class, s1)).isEqualTo(1);
        remove(s1, p1, "불량 의심").andExpect(status().isOk());
        assertThat(balance("L-CPU-A")).isEqualTo("5");
        // 교체: A → B (탈거 + 새 장착 한 번에)
        long p2 = installOk(s1, "\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\"");
        long p3 = id(mvc.perform(json(post(S + "/" + s1 + "/parts/" + p2 + "/replace"), "{\"rowVer\":" + rowVer(s1)
                + ",\"reason\":\"성능 미달\",\"install\":{\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-B\",\"qty\":\"1\"}}").with(admin)));
        assertThat(balance("L-CPU-A")).isEqualTo("5");
        assertThat(balance("L-CPU-B")).isEqualTo("4");
        // 원장: INSTALL p1, REMOVE p1, INSTALL p2, REMOVE p2, INSTALL p3 — 지운 것 없음, 탈거는 장착의 반대(수량 −, reversal_of_id)
        List<Map<String, Object>> rows = accountsRows("""
                select ev_type, trim_scale(qty)::text as qty, reversal_of_id, reason from prd_serial_part where serial_id = ? order by part_id""", s1);
        assertThat(rows).extracting(r -> r.get("ev_type") + ":" + r.get("qty") + ":" + r.get("reversal_of_id"))
                .containsExactly("INSTALL:1:null", "REMOVE:-1:" + p1, "INSTALL:1:null", "REMOVE:-1:" + p2, "INSTALL:1:null");
        mvc.perform(get(S + "/" + s1).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active.length()").value(1))
                .andExpect(jsonPath("$.data.active[0].partId").value(p3))
                .andExpect(jsonPath("$.data.active[0].partLotNo").value("L-CPU-B"))
                .andExpect(jsonPath("$.data.history.length()").value(5))
                .andExpect(jsonPath("$.data.serial.rowVer").value(4));      // 저장 4번(장착·탈거·장착·교체) = +4
    }

    @Test
    void BOM_대비는_일치_부족_대체_추가_빠짐_초과를_가린다() throws Exception {
        List<Long> ids = create(2);
        long s1 = ids.get(0);
        long s2 = ids.get(1);
        installOk(s1, "\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\"");
        installOk(s1, "\"itemCd\":\"BOLT\",\"lotNo\":\"L-BOLT\",\"qty\":\"3\"");
        installOk(s1, "\"itemCd\":\"FANB\",\"qty\":\"1\"");          // 대체 그룹 A 의 비율 0 대체품 — 스냅샷에 없어도 대체로
        installOk(s1, "\"itemCd\":\"CABLE\",\"qty\":\"2\"");
        mvc.perform(get(S + "/" + s1 + "/compare").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[?(@.itemCd == 'CPU')].status").value(Matchers.contains("OK")))
                .andExpect(jsonPath("$.data.lines[?(@.itemCd == 'BOLT')].status").value(Matchers.contains("SHORT")))
                .andExpect(jsonPath("$.data.lines[?(@.itemCd == 'BOLT')].reqQty").value(Matchers.contains("4")))
                .andExpect(jsonPath("$.data.lines[?(@.altGrp == 'A')].status").value(Matchers.contains("SUBST")))
                .andExpect(jsonPath("$.data.lines[?(@.altGrp == 'A')].itemCd").value(Matchers.contains("FAN")))
                .andExpect(jsonPath("$.data.lines[?(@.itemCd == 'CABLE')].status").value(Matchers.contains("EXTRA")))
                .andExpect(jsonPath("$.data.lines.length()").value(4));
        installOk(s2, "\"itemCd\":\"BOLT\",\"lotNo\":\"L-BOLT\",\"qty\":\"5\"");
        mvc.perform(get(S + "/" + s2 + "/compare").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[?(@.itemCd == 'CPU')].status").value(Matchers.contains("MISSING")))
                .andExpect(jsonPath("$.data.lines[?(@.itemCd == 'BOLT')].status").value(Matchers.contains("OVER")))
                .andExpect(jsonPath("$.data.lines[?(@.altGrp == 'A')].status").value(Matchers.contains("MISSING")));
        // 탈거한 부품은 대비에서 빠진다
        long cpu2 = installOk(s2, "\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-B\",\"qty\":\"1\"");
        remove(s2, cpu2, "오장착").andExpect(status().isOk());
        mvc.perform(get(S + "/" + s2 + "/compare").with(admin))
                .andExpect(jsonPath("$.data.lines[?(@.itemCd == 'CPU')].status").value(Matchers.contains("MISSING")));
    }

    @Test
    void 완료판정_미리보기는_미장착을_경고만_하고_막는_이유에는_넣지_않는다() throws Exception {
        List<Long> ids = create(1);           // 계획 2대 중 1대만
        installOk(ids.get(0), "\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\"");   // BOLT(공정 10) 빠짐
        long op10 = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, woId);
        long op20 = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 20", Long.class, woId);
        mvc.perform(get("/api/production/wos/procs/" + op10 + "/close-check").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notices[*].code").value(Matchers.contains("SERIAL_PART_SHORT")))
                .andExpect(jsonPath("$.data.notices[0].message").value(Matchers.containsString("BOLT")))
                .andExpect(jsonPath("$.data.problems").value(Matchers.not(Matchers.hasItem(Matchers.startsWith("SERIAL")))));
        // 마지막 공정 20: 대체 그룹 A 빠짐 + 시리얼 수 1 < 계획 2
        mvc.perform(get("/api/production/wos/procs/" + op20 + "/close-check").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notices[*].code").value(Matchers.containsInAnyOrder("SERIAL_PART_SHORT", "SERIAL_COUNT_SHORT")));
        // 시리얼이 없는 지시(다른 회사 설정과 같은 구조의 새 지시)는 아무 말 없음
        long other = id(mvc.perform(json(post("/api/production/wos"), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"3\",\"planStart\":\""
                + accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class) + "\"}").with(admin)));
        mvc.perform(json(post("/api/production/wos/" + other + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        long o10 = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, other);
        mvc.perform(get("/api/production/wos/procs/" + o10 + "/close-check").with(admin))
                .andExpect(jsonPath("$.data.notices[*].code").value(Matchers.not(Matchers.hasItem("SERIAL_PART_SHORT"))));
    }

    @Test
    void 역추적은_부품_LOT_과_계보로_이어진_LOT_부품_시리얼에서_장비를_찾고_탈거도_보인다() throws Exception {
        List<Long> ids = create(2);
        // CHIP LOT → CPU LOT(L-CPU-A) 으로 투입된 하위 조립 — CHIP 에서 찾으면 CPU 를 거쳐 장비까지
        accounts.exec(comp, """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method, recorded_by)
                select ?, p.lot_id, c.lot_id, 'CONSUME', 2, 'EA', now(), 'KEY', 't'
                  from lot_master p, lot_master c where p.lot_no = 'L-CHIP' and c.lot_no = 'L-CPU-A'""", comp);
        installOk(ids.get(0), "\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\"");
        long gone = installOk(ids.get(1), "\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\"");
        remove(ids.get(1), gone, "교체 예정").andExpect(status().isOk());
        mvc.perform(get(T + "/grid").param("lotNo", "L-CPU-A").param("total", "true").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list[*].state").value(Matchers.containsInAnyOrder("ACTIVE", "REMOVED")))
                .andExpect(jsonPath("$.list[0].via").doesNotExist());
        mvc.perform(get(T + "/grid").param("lotNo", "L-CHIP").param("total", "true").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list[*].via").value(Matchers.everyItem(Matchers.is("L-CPU-A"))));
        installOk(ids.get(0), "\"itemCd\":\"FAN\",\"partSerialNo\":\"FAN-SN-1\",\"qty\":\"1\"");
        mvc.perform(get(T + "/grid").param("itemCd", "FAN").param("partSerialNo", "FAN-SN-1").param("total", "true").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].serialNo").value(accounts.query(comp, "select serial_no from prd_serial where serial_id = ?",
                        String.class, ids.get(0))));
        // 조건 없음·둘 다 = 400
        mvc.perform(get(T + "/grid").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(T + "/grid").param("lotNo", "L-CPU-A").param("itemCd", "FAN").param("partSerialNo", "X").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SERIAL_TRACE_ONE"));
        // 엑셀
        mvc.perform(json(post(T + "/export"), "{\"lotNo\":\"L-CPU-A\"}").with(admin)).andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentType()).contains("spreadsheetml"));
        mvc.perform(json(post(S + "/export"), "{}").with(admin)).andExpect(status().isOk());
    }

    // ---------- 이상 사용 ----------

    @Test
    void 같은_부품_시리얼은_두_장비에_못_달고_탈거한_뒤에는_된다() throws Exception {
        List<Long> ids = create(2);
        long p = installOk(ids.get(0), "\"itemCd\":\"FAN\",\"partSerialNo\":\"FAN-SN-1\",\"qty\":\"1\"");
        String sn1 = accounts.query(comp, "select serial_no from prd_serial where serial_id = ?", String.class, ids.get(0));
        install(admin, ids.get(1), "\"itemCd\":\"FAN\",\"partSerialNo\":\"FAN-SN-1\",\"qty\":\"1\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SERIAL_PART_SN_USED"))
                .andExpect(jsonPath("$.errors[0].message").value(Matchers.containsString(sn1)));
        // 직접 SQL 도 트리거가 막는다
        assertThat(io.smarthappy.platform.common.db.PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into prd_serial_part (comp_cd, serial_id, ev_type, part_item_id, part_serial_no, qty, unit_cd, occurred_at,
                                             entry_method, recorded_by)
                select ?, ?, 'INSTALL', item_id, 'FAN-SN-1', 1, 'EA', now(), 'KEY', 't' from mst_item where item_cd = 'FAN'""",
                comp, ids.get(1))))).contains("uq_prd_serial_part_sn_active");
        remove(ids.get(0), p, "다른 장비로").andExpect(status().isOk());
        install(admin, ids.get(1), "\"itemCd\":\"FAN\",\"partSerialNo\":\"FAN-SN-1\",\"qty\":\"1\"").andExpect(status().isOk());
    }

    @Test
    void 동시에_같은_부품_시리얼을_두_장비에_달면_하나만_된다() throws Exception {
        List<Long> ids = create(2);
        List<Integer> codes = concurrently(ids.stream().map(s -> (Call) () -> install(admin, s,
                "\"itemCd\":\"FAN\",\"partSerialNo\":\"FAN-SN-9\",\"qty\":\"1\"").andReturn().getResponse().getStatus()).toList());
        assertThat(codes).containsExactlyInAnyOrder(200, 400);
        assertThat(accounts.query(comp, "select count(*) from prd_serial_part where part_serial_no = 'FAN-SN-9'", Integer.class)).isEqualTo(1);
    }

    @Test
    void 같은_시리얼에_같은_rowVer_로_동시에_저장하면_하나는_409_덮어쓰기_없음() throws Exception {
        long s = create(1).get(0);
        String body = "{\"rowVer\":0,\"itemCd\":\"BOLT\",\"lotNo\":\"L-BOLT\",\"qty\":\"1\"}";
        List<Integer> codes = concurrently(List.of(
                () -> mvc.perform(json(post(S + "/" + s + "/parts"), body).with(admin)).andReturn().getResponse().getStatus(),
                () -> mvc.perform(json(post(S + "/" + s + "/parts"), body).with(admin)).andReturn().getResponse().getStatus()));
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(accounts.query(comp, "select count(*) from prd_serial_part where serial_id = ?", Integer.class, s)).isEqualTo(1);
        assertThat(balance("L-BOLT")).isEqualTo("99");
        assertThat(rowVer(s)).isEqualTo(1);
    }

    interface Call {
        int run() throws Exception;
    }

    private List<Integer> concurrently(List<Call> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> fs = calls.stream().map(c -> pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                return c.run();
            })).toList();
            go.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : fs) {
                out.add(f.get(20, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void A사_계정으로_B사_시리얼은_안_보이고_장착도_안_되며_B사_LOT_은_못_쓴다() throws Exception {
        long s = create(1).get(0);
        String compB = accounts.newComp("SB");
        long bId = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, bId, "PRD", "prd-serial", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(compB, bId, "PRD", "prd-serial-trace", "SEARCH", "EXCEL");
        RequestPostProcessor bAdmin = accounts.as(compB, bId, "admin1");
        mvc.perform(get(S + "/" + s).with(bAdmin)).andExpect(status().isNotFound());
        mvc.perform(get(S + "/" + s + "/compare").with(bAdmin)).andExpect(status().isNotFound());
        mvc.perform(json(post(S + "/" + s + "/parts"), "{\"rowVer\":0,\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\"}").with(bAdmin))
                .andExpect(status().isNotFound());
        mvc.perform(get(S + "/grid").param("total", "true").with(bAdmin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(json(post(S), "{\"woId\":" + woId + "}").with(bAdmin)).andExpect(status().isNotFound());
        mvc.perform(get(T + "/grid").param("lotNo", "L-CPU-A").with(bAdmin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // B사 LOT 번호를 A사 장비에 — A사에는 그 LOT 이 없다
        accounts.exec(compB, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'CPU', 'B CPU', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", compB);
        accounts.query(compB, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'L-B-ONLY', item_id, 'RECEIVED', 5, 'EA', now(), 't', 't' from mst_item where item_cd = 'CPU' returning lot_id""",
                Long.class, compB);
        install(admin, s, "\"itemCd\":\"CPU\",\"lotNo\":\"L-B-ONLY\",\"qty\":\"1\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lotNo")).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 직접 SQL: B사 범위에서 A사 시리얼에 장착 행 → 트리거가 회사 불일치로 거부
        assertThat(io.smarthappy.platform.common.db.PgErrors.constraint(catchThrowable(() -> accounts.exec(compB, """
                insert into prd_serial_part (comp_cd, serial_id, ev_type, part_item_id, qty, unit_cd, occurred_at, entry_method, recorded_by)
                select ?, ?, 'INSTALL', item_id, 1, 'EA', now(), 'KEY', 't' from mst_item where item_cd = 'CPU'""", compB, s))))
                .contains("fk_prd_serial_part_comp");
        assertThat(accounts.query(comp, "select count(*) from prd_serial_part where serial_id = ?", Integer.class, s)).isZero();
    }

    @Test
    void 권한_없는_계정은_장착_만들기_엑셀_역추적이_403() throws Exception {
        long s = create(1).get(0);
        mvc.perform(get(S + "/" + s).with(viewer)).andExpect(status().isOk());       // 조회만은 된다
        mvc.perform(json(post(S + "/" + s + "/parts"), "{\"rowVer\":0,\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\"}").with(viewer))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(S), "{\"woId\":" + woId + "}").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(json(post(S + "/export"), "{}").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(get(T + "/grid").param("lotNo", "L-CPU-A").with(viewer)).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select count(*) from prd_serial_part where serial_id = ?", Integer.class, s)).isZero();
    }

    @Test
    void 원장_직접_수정_삭제_두_번_탈거_멱등_키_두_번_값_오류는_거부() throws Exception {
        long s = create(1).get(0);
        long p = installOk(s, "\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\"");
        // 원장: 앱 계정은 UPDATE·DELETE 권한 없음
        assertThat(catchThrowable(() -> accounts.exec(comp, "update prd_serial_part set qty = 2 where part_id = ?", p))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from prd_serial_part where part_id = ?", p))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from prd_serial where serial_id = ?", s))).isNotNull();
        // 두 번 탈거
        remove(s, p, "1차").andExpect(status().isOk());
        remove(s, p, "2차").andExpect(status().isConflict());
        // 낡은 rowVer
        mvc.perform(json(post(S + "/" + s + "/parts"), "{\"rowVer\":0,\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\"}").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        // 같은 멱등 키 두 번
        String key = UUID.randomUUID().toString();
        mvc.perform(json(post(S + "/" + s + "/parts"), "{\"rowVer\":" + rowVer(s) + ",\"itemCd\":\"FAN\",\"qty\":\"1\"}")
                .header("X-Idempotency-Key", key).with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(S + "/" + s + "/parts"), "{\"rowVer\":" + rowVer(s) + ",\"itemCd\":\"FAN\",\"qty\":\"1\"}")
                .header("X-Idempotency-Key", key).with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        // 값 오류: LOT 관리 품목에 LOT 없음, 잔량 초과, 부품 시리얼 + 수량 2, LOT 비관리 품목에 LOT, 없는 공정, 미래 시각
        install(admin, s, "\"itemCd\":\"CPU\",\"qty\":\"1\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SERIAL_LOT_REQUIRED"));
        install(admin, s, "\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-A\",\"qty\":\"6\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("OVER_BALANCE"));
        install(admin, s, "\"itemCd\":\"FAN\",\"partSerialNo\":\"FAN-SN-2\",\"qty\":\"2\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SERIAL_SN_ONE"));
        install(admin, s, "\"itemCd\":\"FAN\",\"lotNo\":\"L-CPU-A\",\"qty\":\"1\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SERIAL_LOT_NOT_ALLOWED"));
        install(admin, s, "\"itemCd\":\"FAN\",\"qty\":\"1\",\"opSeq\":99").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("opSeq"));
        install(admin, s, "\"itemCd\":\"FAN\",\"qty\":\"1\",\"occurredAt\":\"2999-01-01T00:00\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        // 보류 LOT
        accounts.exec(comp, """
                insert into lot_hold (comp_cd, lot_id, source, reason, held_by)
                select ?, lot_id, 'MANUAL', '시험 보류', 't' from lot_master where lot_no = 'L-CPU-B'""", comp);
        install(admin, s, "\"itemCd\":\"CPU\",\"lotNo\":\"L-CPU-B\",\"qty\":\"1\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
    }
}
