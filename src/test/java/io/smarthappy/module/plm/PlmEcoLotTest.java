package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import io.smarthappy.module.plm.link.ChangeMarker;
import io.smarthappy.module.plm.link.EcoTrace;
import io.smarthappy.module.plm.link.EcoTrace.EcoRef;
import io.smarthappy.module.plm.link.EcoTrace.Via;
import io.smarthappy.module.production.LotService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 3b 설계 변경 ↔ 생산 LOT 표시 (docs/39 13장) — plm_eco_lot 원본, 실제 ChangeMarker·EcoTrace.
 * 첫 N LOT(도착 순·정확히 N·투입 스캔 없이), 품목만 ECO 의 날짜 판정, 4M 설비·공정 구간(지시 없는 LOT 포함),
 * 동시 표시, 잠금 순서(지시 행을 쥔 트랜잭션이 있어도 표시가 기다리지 않음), 값·상태, 추가만, A사로 B사.
 */
@AutoConfigureMockMvc
class PlmEcoLotTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";
    private static final String APR = "/api/platform/approvals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ChangeMarker marker;

    @Autowired
    EcoTrace trace;

    @Autowired
    LotService lots;

    String comp;
    long ownerId;
    long approverId;
    RequestPostProcessor owner;
    RequestPostProcessor approver;
    String today;
    OffsetDateTime now;
    int lotSeq;
    int woSeq;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PLL");
        // QMS 4M 상신 규칙(정책 QMS_4M_REQUIRED, 기본 Y)은 이 시험 범위 밖 — QMS 쪽 시험이 따로 본다(docs/29_P3 12.1)
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_4M_REQUIRED', 'N', 't', 't')", comp);
        ownerId = accounts.newUser(comp, "eng1", PW);
        approverId = accounts.newUser(comp, "mgr1", PW);
        for (long u : new long[] {ownerId, approverId}) {
            accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        }
        owner = accounts.as(comp, ownerId, "eng1");
        approver = accounts.as(comp, approverId, "mgr1");
        accounts.addPlant(comp, "P1", "1공장");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        // 회사 시간대(Asia/Seoul) 오늘 정오 — 발생일 판정이 날짜 경계에 걸리지 않게
        now = OffsetDateTime.parse(today + "T12:00:00+09:00");
        seedMaster(comp);
    }

    /** FG ← RM. BOM·라우팅 A0 확정 + A1 작성 중(공정 PLATE), 설비 EQ1·EQ2 */
    private void seedMaster(String c) {
        for (String i : new String[] {"FG", "RM", "RM2"}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", c, i, i + " 품목");
        }
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, outsrc_yn, created_by, updated_by)
                values (?, 'PLATE', '외주 도금', 'PAINT', 'Y', 't', 't')""", c);
        for (String rev : new String[] {"A0", "A1"}) {
            long b = accounts.query(c, """
                    insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                    values (?, (select item_id from mst_item where item_cd = 'FG'), ?,
                            case when ? = 'A0' then (now() at time zone 'Asia/Seoul')::date - 30 else (now() at time zone 'Asia/Seoul')::date + 1 end, 't', 't') returning bom_id""",
                    Long.class, c, rev, rev);
            accounts.exec(c, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method,
                                              created_by, updated_by)
                    values (?, ?, 10, (select item_id from mst_item where item_cd = ?), 2, 'EA', 'NORMAL', 'BACKFLUSH', 't', 't')""",
                    c, b, "A0".equals(rev) ? "RM" : "RM2");
            long r = accounts.query(c, """
                    insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                    values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', ?,
                            case when ? = 'A0' then (now() at time zone 'Asia/Seoul')::date - 30 else (now() at time zone 'Asia/Seoul')::date + 1 end, 't', 't') returning routing_id""",
                    Long.class, c, rev, rev);
            accounts.exec(c, """
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                    values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'PLATE'), 't', 't')""", c, r);
            if ("A0".equals(rev)) {
                accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", b);
                accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", r);
            }
        }
        long cls = accounts.query(c, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'PLATING', '도금기', 't', 't') returning class_id""", Long.class, c);
        for (String e : new String[] {"EQ1", "EQ2"}) {
            accounts.exec(c, """
                    insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                    values (?, 'P1', ?, ?, ?, 't', 't')""", c, e, e + " 도금기", cls);
        }
    }

    // ---------- 도우미 ----------

    private long id(String c, String sql, Object... args) {
        return accounts.query(c, sql, Long.class, args);
    }

    private long rev(String c, String table, String rev) {
        return id(c, "select " + ("mst_bom".equals(table) ? "bom_id" : "routing_id") + " from " + table + " where rev = ?", rev);
    }

    private long equip(String c, String cd) {
        return id(c, "select equip_id from mst_equip where equip_cd = ?", cd);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    private static long data(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int ecoVer(long ecoId) {
        return accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, ecoId);
    }

    private ResultActions createEco(String extra) throws Exception {
        return postAs(owner, ECO, "{\"title\":\"변경\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\""
                + (extra == null ? "" : "," + extra) + "}");
    }

    private ResultActions saveTargets(long ecoId, String targetsJson) throws Exception {
        return mvc.perform(json(put(ECO + "/" + ecoId + "/targets"), "{\"rowVer\":" + ecoVer(ecoId) + ",\"targets\":" + targetsJson + "}")
                .with(owner));
    }

    private long approvedEco(String extra, String targetsJson) throws Exception {
        long eco = data(createEco(extra));
        saveTargets(eco, targetsJson).andExpect(status().isOk());
        postAs(owner, ECO + "/" + eco + "/submit", "{\"rowVer\":" + ecoVer(eco)
                + ",\"lines\":[{\"userId\":" + approverId + ",\"lineType\":\"APPROVE\"}]}").andExpect(status().isOk());
        long apr = id(comp, "select apr_id from plm_eco where eco_id = ?", eco);
        postAs(approver, APR + "/" + apr + "/approve", "{}").andExpect(status().isOk());
        return eco;
    }

    private long releasedEco(String extra, String targetsJson) throws Exception {
        long eco = approvedEco(extra, targetsJson);
        postAs(approver, ECO + "/" + eco + "/release", "{\"rowVer\":" + ecoVer(eco) + "}").andExpect(status().isOk());
        return eco;
    }

    private static String bomTarget(long bomId) {
        return "{\"rowKey\":\"b\",\"targetType\":\"BOM\",\"refId\":" + bomId + "}";
    }

    private static String routingTarget(long routingId) {
        return "{\"rowKey\":\"r\",\"targetType\":\"ROUTING\",\"refId\":" + routingId + "}";
    }

    private static String itemTarget(String itemCd) {
        return "{\"rowKey\":\"i\",\"targetType\":\"ITEM\",\"itemCd\":\"" + itemCd + "\"}";
    }

    private static String refTarget(String type, String itemCd, String refCd) {
        return "{\"rowKey\":\"" + type + refCd + "\",\"targetType\":\"" + type + "\",\"itemCd\":\"" + itemCd
                + "\",\"refCd\":\"" + refCd + "\"}";
    }

    /** 발행된 지시(FG) — 발행 트랜잭션에서 지시 공정 스냅샷(PLATE)까지 */
    private long releasedWo(String c, long bomId, long routingId) {
        String no = "WO-" + (++woSeq);
        return runner.call(c, () -> {
            long wo = jdbc.queryForObject("""
                    insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, bom_id,
                                        created_by, updated_by)
                    select ?, ?, 'P1', item_id, 100, unit_cd, (now() at time zone 'Asia/Seoul')::date, (now() at time zone 'Asia/Seoul')::date, ?, ?, 't', 't' from mst_item where item_cd = 'FG'
                    returning wo_id""", Long.class, c, no, routingId, bomId);
            jdbc.update("update prd_wo set status = 'RELEASED', released_at = now(), released_by = 't' where wo_id = ?", wo);
            jdbc.update("""
                    insert into prd_wo_proc (comp_cd, wo_id, op_seq, proc_id, proc_cd, proc_nm, units_per_cycle, setup_min, src_step_id,
                                             created_by, updated_by)
                    select ?, ?, s.op_seq, s.proc_id, p.proc_cd, p.proc_nm, 1, 0, s.step_id, 't', 't'
                      from mst_routing_step s join mst_proc p on p.proc_id = s.proc_id where s.routing_id = ?""", c, wo, routingId);
            return wo;
        });
    }

    /** 생산 LOT(지시 있음) 또는 LOT 단위 재작업 LOT(지시 없음) — 투입 계보 없이 lot_master 한 행 */
    private long lot(String c, Long woId, OffsetDateTime at) {
        String no = "L" + (++lotSeq);
        return id(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, wo_id, occurred_at, created_by, updated_by)
                select ?, ?, item_id, ?, 10, unit_cd, ?, ?, 't', 't' from mst_item where item_cd = 'FG' returning lot_id""",
                c, no, woId == null ? "REWORK" : "PRODUCED", woId, at);
    }

    private Optional<Long> mark(String c, long lotId, Long woId, Long equipId, OffsetDateTime at) {
        return runner.call(c, () -> marker.mark(lotId, woId, equipId, at));
    }

    private int marks(long ecoId) {
        return accounts.query(comp, "select count(*) from plm_eco_lot where eco_id = ?", Integer.class, ecoId);
    }

    // ---------- 정상 ----------

    @Test
    void 설계_변경은_새_리비전_지시의_첫_N_LOT_만_도착_순으로_표시하고_계보_스캔이_없어도_된다() throws Exception {
        long bomA0 = rev(comp, "mst_bom", "A0");
        long routingA0 = rev(comp, "mst_routing", "A0");
        long bomA1 = rev(comp, "mst_bom", "A1");
        long routingA1 = rev(comp, "mst_routing", "A1");
        // 이전 리비전 지시(유효일 전에 발행) — 적용 뒤에 만든 LOT 이라도 변경 뒤 LOT 이 아니다
        long oldWo = releasedWo(comp, bomA0, routingA0);
        // ITEM 대상(ECR 접수 때 자동으로 들어가는 주 품목)이 같이 있어도 리비전 대상이 우선
        long eco = releasedEco("\"markLots\":2", "[" + itemTarget("FG") + "," + bomTarget(bomA1) + "," + routingTarget(routingA1) + "]");
        long newWo = releasedWo(comp, bomA1, routingA1);

        long oldLot = lot(comp, oldWo, now);
        assertThat(mark(comp, oldLot, oldWo, null, now)).isEmpty();
        long l1 = lot(comp, newWo, now);
        long l2 = lot(comp, newWo, now.plusMinutes(5));
        // 오프라인 재전송: 발생은 더 이르지만 늦게 도착 → 도착 순으로 세서 3번째
        long late = lot(comp, newWo, now.minusHours(1));
        assertThat(mark(comp, l1, newWo, null, now)).contains(eco);
        assertThat(mark(comp, l2, newWo, null, now.plusMinutes(5))).contains(eco);
        assertThat(mark(comp, late, newWo, null, now.minusHours(1))).isEmpty();
        // 같은 LOT 을 다시 불러도 그대로(멱등)
        assertThat(mark(comp, l1, newWo, null, now)).contains(eco);
        assertThat(marks(eco)).isEqualTo(2);
        assertThat(accounts.query(comp, "select string_agg(lot_id || ':' || seq_no || ':' || mark_reason, ',' order by seq_no) "
                + "from plm_eco_lot where eco_id = ?", String.class, eco)).isEqualTo(l1 + ":1:FIRST_LOT," + l2 + ":2:FIRST_LOT");
        // 행위자 = 부른 트랜잭션의 사용자(TenantRunner = SYSTEM)
        assertThat(accounts.query(comp, "select distinct recorded_by from plm_eco_lot where eco_id = ?", String.class, eco))
                .isEqualTo("SYSTEM");

        // (가) 리비전 경로는 표시가 없어도 찾는다, (가)+(나) 는 한 줄
        String ecoNo = accounts.query(comp, "select eco_no from plm_eco where eco_id = ?", String.class, eco);
        List<EcoRef> refs = runner.call(comp, () -> trace.ecosOfLot(l1));
        assertThat(refs).containsExactly(new EcoRef(eco, ecoNo, "변경", java.time.LocalDate.parse(today), "NA", Via.REVISION));
        assertThat(runner.call(comp, () -> trace.ecosOfLot(late))).extracting(EcoRef::ecoId).containsExactly(eco);
        assertThat(runner.call(comp, () -> trace.ecosOfLot(oldLot))).isEmpty();

        // 화면: 표시된 LOT (도착 순), 지시 상세의 표시 수
        mvc.perform(get(ECO + "/" + eco + "/lots").with(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].lotNo").value("L2"))
                .andExpect(jsonPath("$.data.rows[0].woNo").value("WO-2"))
                .andExpect(jsonPath("$.data.rows[1].seqNo").value(2));
        mvc.perform(get(ECO + "/" + eco).with(owner))
                .andExpect(jsonPath("$.data.eco.changeKind").value("DESIGN"))
                .andExpect(jsonPath("$.data.eco.markLots").value(2))
                .andExpect(jsonPath("$.data.eco.markCnt").value(2));
    }

    @Test
    void 품목만_있는_지시는_유효일부터_정책_개수만큼_표시한다() throws Exception {
        // 도면만 바뀐 ECO(대상 = 품목만), N 비움 → 정책 PLM_MARK_FIRST_LOTS 기본 1
        long eco = releasedEco(null, "[" + itemTarget("FG") + "]");
        long before = lot(comp, null, now.minusDays(1));
        assertThat(mark(comp, before, null, null, now.minusDays(1))).isEmpty();
        long first = lot(comp, null, now);
        long second = lot(comp, null, now);
        assertThat(mark(comp, first, null, null, now)).contains(eco);
        assertThat(mark(comp, second, null, null, now)).isEmpty();
        assertThat(marks(eco)).isEqualTo(1);
        // 리비전 경로가 없는 LOT 은 표시로만 찾는다
        assertThat(runner.call(comp, () -> trace.ecosOfLot(first))).extracting(EcoRef::via).containsExactly(Via.MARK);
        // 회사 정책 0 = 첫 LOT 표시 끔
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'PLM_MARK_FIRST_LOTS', '0', 't', 't')", comp);
        long eco2 = releasedEco(null, "[" + itemTarget("RM") + "]");
        long rm = id(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'RM-1', item_id, 'REWORK', 1, unit_cd, ?, 't', 't' from mst_item where item_cd = 'RM' returning lot_id""",
                comp, now);
        assertThat(mark(comp, rm, null, null, now)).isEmpty();
        assertThat(marks(eco2)).isZero();
    }

    @Test
    void 설비_4M_구간은_지시_없는_LOT_도_구간_안이면_모두_표시하고_공정_대상은_지시_공정으로() throws Exception {
        String until = java.time.LocalDate.parse(today).plusDays(2).toString();
        long eq1 = equip(comp, "EQ1");
        long eq2 = equip(comp, "EQ2");
        long eco = releasedEco("\"changeKind\":\"PROCESS_4M\",\"markUntil\":\"" + until + "\"",
                "[" + refTarget("EQUIP", "FG", "eq1") + "]");
        mvc.perform(get(ECO + "/" + eco).with(owner))
                .andExpect(jsonPath("$.data.targets[0].targetType").value("EQUIP"))
                .andExpect(jsonPath("$.data.targets[0].equipCd").value("EQ1"));
        // LOT 단위 재작업(지시 없음) 세 개 — 구간 안 EQ1 은 개수 제한 없이, EQ2·구간 밖은 표시 안 함
        for (int i = 0; i < 3; i++) {
            long l = lot(comp, null, now.plusHours(i));
            assertThat(mark(comp, l, null, eq1, now.plusHours(i))).contains(eco);
        }
        long other = lot(comp, null, now);
        assertThat(mark(comp, other, null, eq2, now)).isEmpty();
        long after = lot(comp, null, now.plusDays(3));
        assertThat(mark(comp, after, null, eq1, now.plusDays(3))).isEmpty();
        assertThat(accounts.query(comp, "select string_agg(distinct mark_reason, ',') from plm_eco_lot where eco_id = ?", String.class, eco))
                .isEqualTo("4M_WINDOW");
        assertThat(marks(eco)).isEqualTo(3);

        // 임시 변경 + 공정 대상: 지시 공정에 PLATE 가 있는 LOT. 두 ECO 에 걸리면 둘 다 남고 돌려주는 값은 나중에 적용한 것
        long temp = releasedEco("\"changeKind\":\"TEMP\",\"markUntil\":\"" + today + "\"", "[" + refTarget("PROC", "FG", "PLATE") + "]");
        long wo = releasedWo(comp, rev(comp, "mst_bom", "A0"), rev(comp, "mst_routing", "A0"));
        long both = lot(comp, wo, now);
        assertThat(mark(comp, both, wo, eq1, now)).contains(temp);
        assertThat(accounts.query(comp, "select string_agg(eco_id || ':' || mark_reason, ',' order by eco_id) from plm_eco_lot where lot_id = ?",
                String.class, both)).isEqualTo(eco + ":4M_WINDOW," + temp + ":TEMP");
        assertThat(runner.call(comp, () -> trace.ecosOfLot(both))).extracting(EcoRef::ecoId).containsExactlyInAnyOrder(eco, temp);
        // 지시 없는 LOT 은 공정 대상에 맞지 않는다
        long noWo = lot(comp, null, now);
        assertThat(mark(comp, noWo, null, null, now)).isEmpty();
    }

    @Test
    void MES_3c_경로로_만든_LOT_은_계보_사본이_표시와_같고_EcoTrace_는_리비전과_표시를_한_줄로() throws Exception {
        // MES 3c 가 실제로 부르는 순서: createProduced → LotService.mark(Instant, UTC) → consume(changeId = 표시 ECO)
        long bomA1 = rev(comp, "mst_bom", "A1");
        long routingA1 = rev(comp, "mst_routing", "A1");
        long eco = releasedEco("\"markLots\":1", "[" + bomTarget(bomA1) + "," + routingTarget(routingA1) + "]");
        long wo = releasedWo(comp, bomA1, routingA1);
        long rm = id(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'RM-3C', item_id, 'RECEIVED', 100, unit_cd, now(), 't', 't' from mst_item where item_cd = 'RM2' returning lot_id""", comp);
        long[] made = new long[2];
        for (int i = 0; i < 2; i++) {
            int n = i;
            made[n] = runner.call(comp, () -> {
                long fg = lots.createProduced(wo, new BigDecimal("10"), now.toInstant(), "t");
                Long change = lots.mark(fg, wo, null, now.toInstant());
                lots.consume(rm, fg, new BigDecimal("2"), wo, null, null, null, "SCAN", now.toInstant(), change, "t");
                return fg;
            });
        }
        // 첫 1 LOT: 표시 원본(plm_eco_lot) = 계보 사본(lot_genealogy.change_id)
        assertThat(accounts.query(comp, "select change_id from lot_genealogy where child_lot_id = ?", Long.class, made[0])).isEqualTo(eco);
        assertThat(accounts.query(comp, "select eco_id from plm_eco_lot where lot_id = ?", Long.class, made[0])).isEqualTo(eco);
        // 둘째 LOT: 첫 N 밖이라 표시·사본 없음
        assertThat(accounts.query(comp, "select change_id from lot_genealogy where child_lot_id = ?", Long.class, made[1])).isNull();
        assertThat(accounts.query(comp, "select count(*) from plm_eco_lot where lot_id = ?", Integer.class, made[1])).isZero();
        // EcoTrace: 둘 다 리비전 경로로 같은 ECO 한 줄(표시 여부와 무관) — 출하 게이트·감사는 이 조회 하나로 본다
        for (long lot : made) {
            assertThat(runner.call(comp, () -> trace.ecosOfLot(lot))).extracting(EcoRef::ecoId, EcoRef::via)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(eco, Via.REVISION));
        }
        // 화면 "표시된 LOT" 에 MES LOT 번호·지시 번호가 보인다
        mvc.perform(get(ECO + "/" + eco + "/lots").with(owner))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].lotId").value(made[0]))
                .andExpect(jsonPath("$.data.rows[0].woNo").value("WO-1"));
    }

    // ---------- 이상 사용 ----------

    @Test
    void 출하_확인은_리비전_경로와_표시_LOT_의_PPAP_미승인만_돌려주고_임시_승인은_경고() throws Exception {
        accounts.exec(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', 'C1 고객', 'SALES', 't', 't')""", comp);
        long bomA1 = rev(comp, "mst_bom", "A1");
        long routingA1 = rev(comp, "mst_routing", "A1");
        long oldWo = releasedWo(comp, rev(comp, "mst_bom", "A0"), rev(comp, "mst_routing", "A0"));
        // 설계 변경(리비전 경로) — 표시 없는 LOT(첫 N 뒤)도 대상
        long design = releasedEco("\"markLots\":1", "[" + bomTarget(bomA1) + "," + routingTarget(routingA1) + "]");
        long newWo = releasedWo(comp, bomA1, routingA1);
        assertThat(mark(comp, lot(comp, newWo, now), newWo, null, now)).contains(design);
        long revLot = lot(comp, newWo, now);
        assertThat(mark(comp, revLot, newWo, null, now)).isEmpty();
        // 4M(설비) — 지시 없는 LOT 은 표시로만 걸린다
        long fourM = releasedEco("\"changeKind\":\"PROCESS_4M\",\"markUntil\":\"" + today + "\"", "[" + refTarget("EQUIP", "FG", "eq1") + "]");
        long markLot = lot(comp, null, now);
        assertThat(mark(comp, markLot, null, equip(comp, "EQ1"), now)).contains(fourM);
        long oldLot = lot(comp, oldWo, now);
        List<Long> ids = java.util.Arrays.asList(revLot, markLot, oldLot, null, revLot, 999_999_999L);

        // PPAP 없음 → 확인할 것 없음
        assertThat(runner.call(comp, () -> trace.shipChecks(ids))).isEmpty();
        long p1 = ppap(design);
        long p2 = ppap(fourM);
        String designNo = accounts.query(comp, "select eco_no from plm_eco where eco_id = ?", String.class, design);
        assertThat(runner.call(comp, () -> trace.shipChecks(ids))).containsExactly(
                new EcoTrace.ShipCheck(revLot, design, designNo, "PPAP_OPEN", "DRAFT", true),
                new EcoTrace.ShipCheck(markLot, fourM, accounts.query(comp, "select eco_no from plm_eco where eco_id = ?", String.class, fourM),
                        "PPAP_OPEN", "DRAFT", true));
        // 제출 → 임시 승인(경고만) / 승인(빠짐)
        accounts.exec(comp, "update plm_ppap set status = 'SUBMITTED', submitted_on = ?::date where ppap_id in (?, ?)", today, p1, p2);
        accounts.exec(comp, "update plm_ppap set status = 'INTERIM', decided_on = ?::date where ppap_id = ?", today, p2);
        accounts.exec(comp, "update plm_ppap set status = 'APPROVED', decided_on = ?::date, decision_ref = 'PSW-1' where ppap_id = ?", today, p1);
        assertThat(runner.call(comp, () -> trace.shipChecks(ids))).extracting(EcoTrace.ShipCheck::lotId, EcoTrace.ShipCheck::reason,
                EcoTrace.ShipCheck::ppapStatus, EcoTrace.ShipCheck::block).containsExactly(org.assertj.core.groups.Tuple.tuple(markLot, "PPAP_INTERIM", "INTERIM", false));
        // 거절 → 다시 차단
        accounts.exec(comp, "update plm_ppap set status = 'DRAFT', decided_on = null where ppap_id = ?", p2);
        accounts.exec(comp, "update plm_ppap set status = 'SUBMITTED', submitted_on = ?::date where ppap_id = ?", today, p2);
        accounts.exec(comp, "update plm_ppap set status = 'REJECTED', decided_on = ?::date where ppap_id = ?", today, p2);
        assertThat(runner.call(comp, () -> trace.shipChecks(List.of(markLot)))).extracting(EcoTrace.ShipCheck::reason, EcoTrace.ShipCheck::block)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PPAP_OPEN", true));

        // 값: 빈 목록·null 만·상한 초과
        assertThat(runner.call(comp, () -> trace.shipChecks(List.of()))).isEmpty();
        assertThat(runner.call(comp, () -> trace.shipChecks(java.util.Collections.singletonList(null)))).isEmpty();
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, io.smarthappy.module.plm.link.EcoLookup.MAX_IDS + 1).boxed().toList();
        assertThat(catchThrowable(() -> runner.call(comp, () -> trace.shipChecks(tooMany)))).isInstanceOf(IllegalArgumentException.class);
        // A사로 B사: B사 범위에서 A사 LOT id → 빈 목록(RLS)
        String compB = accounts.newComp("PLLS");
        assertThat(runner.call(compB, () -> trace.shipChecks(List.of(revLot, markLot)))).isEmpty();
    }

    private long ppap(long ecoId) {
        return id(comp, """
                insert into plm_ppap (comp_cd, eco_id, cust_id, created_by, updated_by)
                values (?, ?, (select cust_id from mst_cust where cust_cd = 'C1'), 't', 't') returning ppap_id""", comp, ecoId);
    }

    @Test
    void D_첫_1_자리에_동시에_두_LOT_이면_하나만_표시된다() throws Exception {
        long bomA1 = rev(comp, "mst_bom", "A1");
        long routingA1 = rev(comp, "mst_routing", "A1");
        long eco = releasedEco("\"markLots\":1", "[" + bomTarget(bomA1) + "," + routingTarget(routingA1) + "]");
        long wo = releasedWo(comp, bomA1, routingA1);
        long l1 = lot(comp, wo, now);
        long l2 = lot(comp, wo, now);
        CountDownLatch firstMarked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 첫째: 표시(ECO 행 잠금) 뒤 커밋 전에 머문다 → 둘째는 잠금을 기다렸다가 센다
            Future<Optional<Long>> a = pool.submit(() -> runner.call(comp, () -> {
                Optional<Long> r = marker.mark(l1, wo, null, now);
                firstMarked.countDown();
                sleep(500);
                return r;
            }));
            Future<Optional<Long>> b = pool.submit(() -> {
                firstMarked.await(10, TimeUnit.SECONDS);
                return runner.call(comp, () -> marker.mark(l2, wo, null, now));
            });
            assertThat(a.get(20, TimeUnit.SECONDS)).contains(eco);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEmpty();
        } finally {
            pool.shutdownNow();
        }
        assertThat(marks(eco)).isEqualTo(1);
    }

    @Test
    void D_지시_행을_쥔_트랜잭션이_있어도_표시는_기다리지_않는다_잠금_순서() throws Exception {
        // plm_eco_lot 은 MES 표에 FK 를 두지 않는다(13.2) — FK 였다면 KEY SHARE 가 FOR UPDATE 에 막혀 lock_timeout
        long bomA1 = rev(comp, "mst_bom", "A1");
        long routingA0 = rev(comp, "mst_routing", "A0");
        long eco = releasedEco("\"markLots\":1", "[" + bomTarget(bomA1) + "]");
        long wo = releasedWo(comp, bomA1, routingA0);
        long l1 = lot(comp, wo, now);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> runner.run(comp, () -> {
                jdbc.queryForList("select wo_id from prd_wo where wo_id = ? for update", wo);
                locked.countDown();
                try {
                    done.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Optional<Long> r = runner.call(comp, () -> {
                jdbc.execute("set local lock_timeout = '2s'");
                return marker.mark(l1, wo, null, now);
            });
            assertThat(r).contains(eco);
            done.countDown();
            holder.get(20, TimeUnit.SECONDS);
        } finally {
            done.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void C_적용_전_지시는_표시하지_않고_작성_중이_아니면_종류_구간을_바꿀_수_없다() throws Exception {
        long eco = approvedEco(null, "[" + itemTarget("FG") + "]");
        long l = lot(comp, null, now);
        assertThat(mark(comp, l, null, null, now)).isEmpty();
        mvc.perform(json(put(ECO + "/" + eco), "{\"title\":\"변경\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today
                + "\",\"changeKind\":\"TEMP\",\"markUntil\":\"" + today + "\",\"rowVer\":" + ecoVer(eco) + "}").with(owner))
                .andExpect(status().isConflict());
        Throwable t = catchThrowable(() -> accounts.exec(comp, "update plm_eco set mark_lots = 5 where eco_id = ?", eco));
        assertThat(PgErrors.constraint(t)).contains("ck_plm_eco_fixed");
        // 승인된 지시에 직접 SQL 로 표시 행 → 적용(RELEASED)된 지시만
        Throwable t2 = catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_lot (comp_cd, eco_id, lot_id, mark_reason, seq_no, occurred_at, recorded_by)
                values (?, ?, ?, 'FIRST_LOT', 1, now(), 't')""", comp, eco, l));
        assertThat(PgErrors.constraint(t2)).contains("fk_plm_eco_lot_ref_comp");
    }

    @Test
    void C_표시_행은_추가만_직접_SQL_수정_삭제_불가() throws Exception {
        long eco = releasedEco(null, "[" + itemTarget("FG") + "]");
        long l = lot(comp, null, now);
        mark(comp, l, null, null, now);
        assertThat(catchThrowable(() -> accounts.exec(comp, "update plm_eco_lot set seq_no = 9 where eco_id = ?", eco)))
                .rootCause().hasMessageContaining("permission denied");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from plm_eco_lot where eco_id = ?", eco)))
                .rootCause().hasMessageContaining("permission denied");
        assertThat(marks(eco)).isEqualTo(1);
        // 표시는 MES 트랜잭션 안에서만
        assertThat(catchThrowable(() -> marker.mark(l, null, null, now)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void E_값_검사_변경_종류_표시_구간_설비_공정_대상() throws Exception {
        String yesterday = java.time.LocalDate.parse(today).minusDays(1).toString();
        createEco("\"changeKind\":\"TEMP\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'markUntil')].code").value("REQUIRED"));
        createEco("\"markUntil\":\"" + today + "\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'markUntil')].code").value("MARK_UNTIL_KIND"));
        createEco("\"changeKind\":\"PROCESS_4M\",\"markUntil\":\"" + yesterday + "\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'markUntil')].code").value("BEFORE_EFFECTIVE"));
        createEco("\"markLots\":0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'markLots')].code").value("RANGE"));
        createEco("\"markLots\":101").andExpect(status().isBadRequest());
        createEco("\"changeKind\":\"X\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'changeKind')].code").value("CODE"));
        // 직접 SQL: 임시 변경에 끝 없음
        long eco = data(createEco("\"changeKind\":\"PROCESS_4M\""));
        Throwable t = catchThrowable(() -> accounts.exec(comp, "update plm_eco set change_kind = 'TEMP' where eco_id = ?", eco));
        assertThat(PgErrors.constraint(t)).contains("ck_plm_eco_mark_until");
        // 설비·공정 대상: 코드 없음·없는 코드·같은 대상 두 번
        saveTargets(eco, "[{\"rowKey\":\"a\",\"targetType\":\"EQUIP\",\"itemCd\":\"FG\"}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("refCd")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        saveTargets(eco, "[" + refTarget("PROC", "FG", "NOPE") + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        saveTargets(eco, "[" + refTarget("EQUIP", "FG", "EQ1") + "," + refTarget("EQUIP", "FG", "EQ1").replace("EQUIPEQ1", "x") + "]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].rowKey").value("x"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 설비 + 공정 + 품목은 한 지시에 함께, 상신(리비전 대상 없음)도 된다
        saveTargets(eco, "[" + refTarget("EQUIP", "FG", "EQ1") + "," + refTarget("PROC", "FG", "PLATE") + "," + itemTarget("RM") + "]")
                .andExpect(status().isOk());
        postAs(owner, ECO + "/" + eco + "/submit", "{\"rowVer\":" + ecoVer(eco)
                + ",\"lines\":[{\"userId\":" + approverId + "}]}").andExpect(status().isOk());
        // 직접 SQL: 유형과 칸 불일치
        Throwable t3 = catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 9, 'EQUIP', (select item_id from mst_item where item_cd = 'FG'), 't', 't')""", comp, eco));
        assertThat(t3).isNotNull();
    }

    @Test
    void B_A사로_B사_LOT_지시_설비에_표시할_수_없고_조회도_안_된다() throws Exception {
        String compB = accounts.newComp("PLLB");
        accounts.addPlant(compB, "P1", "1공장");
        seedMaster(compB);
        long userB = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, userB, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        // B사: 품목만 대상인 지시를 직접 SQL 로 적용 상태까지(소유자 아님 — 앱 계정, 트리거 전이 그대로)
        long ecoB = id(compB, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, 'ECO-B', 'B', 'COST', (now() at time zone 'Asia/Seoul')::date, ?, 't', 't') returning eco_id""", compB, userB);
        accounts.exec(compB, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG'), 't', 't')""", compB, ecoB);
        accounts.exec(compB, "update plm_eco set status = 'PENDING' where eco_id = ?", ecoB);
        accounts.exec(compB, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", ecoB);
        accounts.exec(compB, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", ecoB);
        long lotB = lot(compB, null, now);
        long eqB = equip(compB, "EQ1");

        // A사 범위로 B사 LOT 표시 → 없는 LOT(빈 값), 아무것도 안 남는다
        assertThat(mark(comp, lotB, null, null, now)).isEmpty();
        assertThat(accounts.query(compB, "select count(*) from plm_eco_lot", Integer.class)).isZero();
        assertThat(runner.call(comp, () -> trace.ecosOfLot(lotB))).isEmpty();
        // A사 LOT 에 B사 설비 id → 설비는 없는 것으로(오류 없이), A사 지시만 판정
        long ecoA = releasedEco("\"changeKind\":\"PROCESS_4M\",\"markUntil\":\"" + today + "\"", "[" + refTarget("EQUIP", "FG", "EQ1") + "]");
        long lotA = lot(comp, null, now);
        assertThat(mark(comp, lotA, null, eqB, now)).isEmpty();
        assertThat(marks(ecoA)).isZero();
        // A사 사용자로 B사 지시 표시 목록 → 404
        mvc.perform(get(ECO + "/" + ecoB + "/lots").with(owner)).andExpect(status().isNotFound());
        // 직접 SQL(A사 범위): B사 지시·B사 LOT·B사 설비를 가리키는 표시 행
        long markedA = lot(comp, null, now);
        assertThat(mark(comp, markedA, null, equip(comp, "EQ1"), now)).contains(ecoA);
        for (Object[] args : new Object[][] {{ecoB, lotA, null}, {ecoA, lotB, null}, {ecoA, lotA, eqB}}) {
            Throwable t = catchThrowable(() -> accounts.exec(comp, """
                    insert into plm_eco_lot (comp_cd, eco_id, lot_id, mark_reason, seq_no, equip_id, occurred_at, recorded_by)
                    values (?, ?, ?, '4M_WINDOW', 7, ?, now(), 't')""", comp, args[0], args[1], args[2]));
            assertThat(PgErrors.constraint(t)).as("%s", java.util.Arrays.toString(args)).contains("fk_plm_eco_lot_ref_comp");
        }
        // B사 comp_cd 로 넣기 — RLS WITH CHECK 또는 참조 트리거
        Throwable t = catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_lot (comp_cd, eco_id, lot_id, mark_reason, seq_no, occurred_at, recorded_by)
                values (?, ?, ?, 'FIRST_LOT', 1, now(), 't')""", compB, ecoB, lotB));
        assertThat(t).isNotNull();
        assertThat(accounts.query(compB, "select count(*) from plm_eco_lot", Integer.class)).isZero();
        // A사 지시 대상에 B사만 가진 설비 코드 → 없음
        accounts.exec(compB, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', 'ONLYB', 'B 설비', (select class_id from mst_equip_class where class_cd = 'PLATING'), 't', 't')""", compB);
        long draft = data(createEco("\"changeKind\":\"PROCESS_4M\""));
        saveTargets(draft, "[" + refTarget("EQUIP", "FG", "ONLYB") + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 직접 SQL: A사 대상에 B사 설비 id
        Throwable t4 = catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, equip_id, created_by, updated_by)
                values (?, ?, 1, 'EQUIP', (select item_id from mst_item where item_cd = 'FG'), ?, 't', 't')""", comp, draft, eqB));
        assertThat(PgErrors.constraint(t4)).contains("fk_plm_eco_target_comp");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
