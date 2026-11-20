package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * LOT·계보 원장 (P2 4단계, docs/25 4.5) — 자재 LOT 간이 등록, 분할·병합 왕복(정·역추적 일치), 라벨 바꿈, 보류, 투입·역분개(원장),
 * LOT 번호 방식(SEQ·ITEM_SEQ·WO), 잔량을 넘는 출고(서비스·트리거·동시), 직접 SQL 원장 규칙, A사로 B사, 10단 × 1,000 LOT 추적 실측.
 */
@AutoConfigureMockMvc
class LotTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String L = "/api/production/lots";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    LotService lots;

    @Autowired
    PolicyService policies;

    String comp;
    long userId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("LT");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "PRD", "prd-lot", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        for (String[] i : new String[][] {{"RM", "KG"}, {"RM2", "KG"}, {"FG", "EA"}}) {
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'RM', 'BUY', ?, 't', 't')""", comp, i[0], i[0] + " 품목", i[1]);
        }
        for (String[] c : new String[][] {{"V-1", "PURCHASE"}, {"V-2", "BOTH"}, {"C-1", "SALES"}}) {
            accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, ?, ?, ?, 't', 't')",
                    comp, c[0], c[0] + " 상사", c[1]);
        }
    }

    // ---------- 도우미 ----------

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

    private long receive(String item, String qty, String supplier, String supLot) throws Exception {
        return id(postJson(L + "/receive", "{\"itemCd\":\"" + item + "\",\"qty\":\"" + qty + "\""
                + (supplier == null ? "" : ",\"supplierCd\":\"" + supplier + "\"")
                + (supLot == null ? "" : ",\"supplierLotNo\":\"" + supLot + "\"") + "}"));
    }

    private BigDecimal balance(long lotId) {
        return accounts.query(comp, "select hb_lot_balance(?)", BigDecimal.class, lotId);
    }

    private long lotId(String lotNo) {
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, lotNo);
    }

    private int rowVer(long lotId) {
        return accounts.query(comp, "select row_ver from lot_master where lot_id = ?", Integer.class, lotId);
    }

    // ---------- 등록 ----------

    @Test
    void 자재_LOT_간이_등록_번호_공급사_라벨_중복_검증() throws Exception {
        long a = receive("rm", "1250.5", "V-1", "COIL-7781");
        mvc.perform(get(L + "/" + a).with(admin))
                .andExpect(jsonPath("$.data.lot.lotNo").value(Matchers.matchesPattern("^L\\d{6}-0001$")))
                .andExpect(jsonPath("$.data.lot.origin").value("RECEIVED"))
                .andExpect(jsonPath("$.data.lot.balance").value("1250.5"))
                .andExpect(jsonPath("$.data.lot.unitCd").value("KG"))
                .andExpect(jsonPath("$.data.lot.supplierCd").value("V-1"));
        receive("RM", "10", "V-2", "COIL-7781");                    // 다른 공급사면 같은 라벨 번호 가능
        // 같은 공급사·같은 라벨을 다시 받으면 새 내부 LOT(결정 #43) — 공급사 LOT 은 같다
        long again = receive("RM", "5", "V-1", "COIL-7781");
        assertThat(again).isNotEqualTo(a);
        assertThat(accounts.query(comp, "select supplier_lot_no from lot_master where lot_id = ?", String.class, again)).isEqualTo("COIL-7781");
        postJson(L + "/receive", "{\"itemCd\":\"RM\",\"qty\":\"1.2345\",\"supplierCd\":\"C-1\",\"supplierLotNo\":\"코일\","
                + "\"occurredAt\":\"2999-01-01T00:00\"}")
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("SCALE", "NOT_SUPPLIER_LOT", "FORMAT", "FUTURE")));
        postJson(L + "/receive", "{\"itemCd\":\"FG\",\"qty\":\"1\",\"occurredAt\":\"2026/01/01\"}")
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 조회·번호로 찾기
        String no = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, a);
        mvc.perform(get(L + "/by-no").param("lotNo", no).with(admin)).andExpect(jsonPath("$.data.lotId").value((int) a));
        mvc.perform(get(L + "/by-no").param("lotNo", "NOPE").with(admin)).andExpect(status().isNotFound());
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        mvc.perform(get(L).param("from", today).param("to", today).param("keyword", "7781").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(3));                   // 공급사 LOT 으로 찾으면 세 내부 LOT 모두
    }

    // ---------- 분할·병합 왕복 ----------

    @Test
    void 분할했다_다시_병합하면_정추적과_역추적이_왕복_일치한다() throws Exception {
        long p = receive("RM", "100", "V-1", "C-1");
        MvcResult r = postJson(L + "/" + p + "/split", "{\"qtys\":[\"30\",\"20.5\"],\"reason\":\"두 라인 분배\"}")
                .andExpect(status().isOk()).andReturn();
        List<String> kids = JsonPath.read(r.getResponse().getContentAsString(), "$.data");
        assertThat(kids).hasSize(2);
        assertThat(balance(p)).isEqualByComparingTo("49.5");
        postJson(L + "/" + p + "/split", "{\"qtys\":[\"50\"]}").andExpect(jsonPath("$.errors[0].code").value("OVER_BALANCE"));
        long a = lotId(kids.get(0));
        long b = lotId(kids.get(1));
        long m = id(postJson(L + "/merge", "{\"parts\":[{\"lotId\":" + a + "},{\"lotId\":" + b + ",\"qty\":\"20\"}],\"reason\":\"재포장\"}"));
        assertThat(accounts.query(comp, "select qty_created from lot_master where lot_id = ?", BigDecimal.class, m))
                .isEqualByComparingTo("50");
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualByComparingTo("0.5");
        // 정추적(원 LOT → 분할 2 → 병합 1): 4줄 — 병합 LOT 은 두 경로로 두 번 나온다
        mvc.perform(get(L + "/" + p + "/trace").param("dir", "FWD").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(4))
                .andExpect(jsonPath("$.data.rows[0].lotId").value((int) a))
                .andExpect(jsonPath("$.data.rows[0].relType").value("SPLIT"))
                .andExpect(jsonPath("$.data.rows[0].qty").value("30"))
                .andExpect(jsonPath("$.data.rows[1].lotId").value((int) m))
                .andExpect(jsonPath("$.data.rows[1].lvl").value(2));
        // 역추적(병합 LOT → 분할 2 → 원 LOT): 원 LOT 이 두 경로 모두의 끝
        mvc.perform(get(L + "/" + m + "/trace").param("dir", "BACK").with(admin))
                .andExpect(jsonPath("$.data.rows[*].lotId").value(Matchers.contains((int) a, (int) p, (int) b, (int) p)))
                .andExpect(jsonPath("$.data.rows[1].qty").value("30"))
                .andExpect(jsonPath("$.data.rows[2].qty").value("20"))
                .andExpect(jsonPath("$.data.rows[3].qty").value("20.5"));
        mvc.perform(get(L + "/" + m).with(admin))
                .andExpect(jsonPath("$.data.parents.length()").value(2))
                .andExpect(jsonPath("$.data.children.length()").value(0));
    }

    @Test
    void 병합_검증과_라벨_바꿈_보류() throws Exception {
        long r1 = receive("RM", "10", null, null);
        long r2 = receive("RM2", "10", null, null);
        long r3 = receive("RM", "5", null, null);
        postJson(L + "/merge", "{\"parts\":[{\"lotId\":" + r1 + "}]}").andExpect(jsonPath("$.errors[0].code").value("MERGE_MIN"));
        postJson(L + "/merge", "{\"parts\":[{\"lotId\":" + r1 + "},{\"lotId\":" + r1 + "}]}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(L + "/merge", "{\"parts\":[{\"lotId\":" + r1 + "},{\"lotId\":" + r2 + "}]}").andExpect(jsonPath("$.errors[0].code").value("SAME_ITEM_LOT"));
        postJson(L + "/merge", "{\"parts\":[{\"lotId\":" + r1 + ",\"qty\":\"11\"},{\"lotId\":" + r3 + "}]}")
                .andExpect(jsonPath("$.errors[0].code").value("OVER_BALANCE"));
        // 보류: 사유 필수, 보류 LOT 은 분할·병합·라벨 바꿈 불가, 해제 뒤 됨
        mvc.perform(json(put(L + "/" + r1 + "/hold"), "{\"holdYn\":\"Y\",\"rowVer\":0}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mvc.perform(json(put(L + "/" + r1 + "/hold"), "{\"holdYn\":\"Y\",\"reason\":\"\u200B\u00A0\",\"rowVer\":0}").with(admin))   // QA R4-a
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mvc.perform(json(put(L + "/" + r1 + "/hold"), "{\"holdYn\":\"Y\",\"reason\":\"외관 불량 의심\",\"rowVer\":0}").with(admin))
                .andExpect(status().isOk());
        postJson(L + "/" + r1 + "/split", "{\"qtys\":[\"1\"]}").andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        postJson(L + "/merge", "{\"parts\":[{\"lotId\":" + r1 + "},{\"lotId\":" + r3 + "}]}").andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        mvc.perform(json(put(L + "/" + r1 + "/hold"), "{\"holdYn\":\"Y\",\"reason\":\"또\",\"rowVer\":1}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("STATE"));
        mvc.perform(json(put(L + "/" + r1 + "/hold"), "{\"holdYn\":\"N\",\"reason\":\"검사 합격\",\"rowVer\":1}").with(admin))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'lot_master' and action = 'UPDATE'",
                Integer.class)).isEqualTo(2);
        // 라벨 바꿈: 잔량 전부 → 원 LOT 잔량 0, 다시 하면 EMPTY_LOT
        postJson(L + "/" + r1 + "/relabel", "{}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        long nl = id(postJson(L + "/" + r1 + "/relabel", "{\"reason\":\"라벨 훼손\"}"));
        assertThat(balance(r1)).isZero();
        assertThat(balance(nl)).isEqualByComparingTo("10");
        postJson(L + "/" + r1 + "/relabel", "{\"reason\":\"또\"}").andExpect(jsonPath("$.errors[0].code").value("EMPTY_LOT"));
    }

    // ---------- 투입·역분개 (5b 가 부르는 서비스) ----------

    private long releasedWo(String item) {
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'ASM', '조립', 'ASSEMBLY', 't', 't')", comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                select ?, item_id, 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 1, 't', 't' from mst_item where item_cd = ? returning routing_id""",
                Long.class, comp, item);
        accounts.exec(comp, "insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by) "
                + "values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ASM'), 't', 't')", comp, rid);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long wo = accounts.query(comp, """
                insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id,
                                    created_by, updated_by)
                select ?, 'WO-T1', 'P1', item_id, 100, unit_cd, (now() at time zone 'Asia/Seoul')::date, (now() at time zone 'Asia/Seoul')::date, ?, 't', 't' from mst_item where item_cd = ?
                returning wo_id""", Long.class, comp, rid, item);
        accounts.exec(comp, "update prd_wo set status = 'RELEASED', released_at = now(), released_by = 't' where wo_id = ?", wo);
        return wo;
    }

    @Test
    void 기간은_회사_시간대_날짜_자정_경계() throws Exception {
        // 회사 시간대(Asia/Seoul) 오늘 00:30 = UTC 전날 15:30 — DB 세션(UTC) 날짜로 비교하면 빠지던 LOT
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        for (String[] l : new String[][] {{"TZ-IN", "00:30"}, {"TZ-PREV", "-00:30"}}) {
            accounts.exec(comp, """
                    insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_at, created_by, updated_by)
                    select ?, ?, item_id, 'RECEIVED', 1, 'KG', now(), (?::date + ?::interval) at time zone 'Asia/Seoul', 't', 't'
                      from mst_item where item_cd = 'RM'""", comp, l[0], today, l[1]);
        }
        mvc.perform(get(L).param("from", today).param("to", today).param("keyword", "TZ-").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].lotNo").value("TZ-IN"));
    }

    @Test
    void 같은_연결의_투입_두_건_중_한_건만_정정하면_추적은_남은_순수량_한_줄() throws Exception {
        long rm = receive("RM", "100", null, null);
        long wo = releasedWo("FG");
        long fg = runner.call(comp, () -> lots.createProduced(wo, new BigDecimal("50"), Instant.now(), "t"));
        long g1 = runner.call(comp, () -> lots.consume(rm, fg, new BigDecimal("12.5"), wo, null, null, null, "SCAN", Instant.now(), "t"));
        runner.call(comp, () -> lots.consume(rm, fg, new BigDecimal("3"), wo, null, null, null, "SCAN", Instant.now(), "t"));
        mvc.perform(get(L + "/" + fg + "/trace").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].qty").value("15.5"));
        runner.call(comp, () -> lots.reverse(g1, "다른 코일을 스캔함", "t"));
        // 역추적·정추적 모두 남은 한 건(3)만 — 순회는 살아 있는 행, 수량은 역분개를 더한 순수량
        mvc.perform(get(L + "/" + fg + "/trace").param("dir", "BACK").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].lotId").value((int) rm))
                .andExpect(jsonPath("$.data.rows[0].qty").value("3"));
        mvc.perform(get(L + "/" + rm + "/trace").param("dir", "FWD").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].lotId").value((int) fg))
                .andExpect(jsonPath("$.data.rows[0].qty").value("3"));
        assertThat(balance(rm)).isEqualByComparingTo("97");
    }

    @Test
    void 투입과_역분개는_원장으로_잔량과_추적에_반영되고_한_번만_되돌린다() throws Exception {
        long rm = receive("RM", "100", null, null);
        long wo = releasedWo("FG");
        long fg = runner.call(comp, () -> lots.createProduced(wo, new BigDecimal("50"), Instant.now(), "t"));
        long g = runner.call(comp, () -> lots.consume(rm, fg, new BigDecimal("12.5"), wo, null, null, null, "SCAN", Instant.now(), "t"));
        assertThat(balance(rm)).isEqualByComparingTo("87.5");
        mvc.perform(get(L + "/" + fg + "/trace").with(admin))
                .andExpect(jsonPath("$.data.rows[0].lotId").value((int) rm))
                .andExpect(jsonPath("$.data.rows[0].relType").value("CONSUME"))
                .andExpect(jsonPath("$.data.rows[0].qty").value("12.5"))
                .andExpect(jsonPath("$.data.rows[0].unitCd").value("KG"));
        // 잔량을 넘는 투입
        assertThat(catchThrowable(() -> runner.call(comp, () -> lots.consume(rm, fg, new BigDecimal("90"), wo, null, null, null, "KEY",
                Instant.now(), "t")))).isInstanceOf(BusinessException.class);
        // 역분개: 원래 행 그대로 + 반대 행, 잔량 복구, 추적에서 빠짐, 두 번은 안 됨
        long rev = runner.call(comp, () -> lots.reverse(g, "다른 코일을 스캔함", "t"));
        assertThat(accounts.query(comp, "select qty::text || '/' || entry_method || '/' || reversal_of_id from lot_genealogy where gen_id = ?",
                String.class, rev)).isEqualTo("-12.500000/CORRECT/" + g);
        // 역분개 행도 원래 행의 지시를 갖는다(docs/25 4.5, V110) — 맥락을 비운 역분개는 거부
        assertThat(accounts.query(comp, "select wo_id from lot_genealogy where gen_id = ?", Long.class, rev)).isEqualTo(wo);
        assertThat(balance(rm)).isEqualByComparingTo("100");
        mvc.perform(get(L + "/" + fg + "/trace").with(admin)).andExpect(jsonPath("$.data.rows.length()").value(0));
        mvc.perform(get(L + "/" + fg).with(admin))
                .andExpect(jsonPath("$.data.parents.length()").value(2))
                .andExpect(jsonPath("$.data.parents[0].reversed").value(true));
        assertThat(catchThrowable(() -> runner.call(comp, () -> lots.reverse(g, "또", "t")))).isInstanceOf(BusinessException.class);
        assertThat(catchThrowable(() -> runner.call(comp, () -> lots.reverse(rev, "역의 역", "t")))).isInstanceOf(BusinessException.class);
        long g2 = runner.call(comp, () -> lots.consume(rm, fg, new BigDecimal("1"), wo, null, null, null, "SCAN", Instant.now(), "t"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method,
                                           recorded_by, reason, reversal_of_id)
                select comp_cd, parent_lot_id, child_lot_id, rel_type, -qty, unit_cd, now(), 'CORRECT', 't', '지시 비움', gen_id
                  from lot_genealogy where gen_id = ?""", g2)))).contains("ck_lot_genealogy_reversal_ctx");
        runner.call(comp, () -> lots.reverse(g2, "되돌림", "t"));
        // 분할은 역분개가 아니라 반대 동작으로
        long kid = lotId(JsonPath.<List<String>>read(postJson(L + "/" + rm + "/split", "{\"qtys\":[\"1\"]}").andReturn()
                .getResponse().getContentAsString(), "$.data").get(0));
        long sg = accounts.query(comp, "select gen_id from lot_genealogy where child_lot_id = ?", Long.class, kid);
        BusinessException ex = (BusinessException) catchThrowable(() -> runner.call(comp, () -> lots.reverse(sg, "되돌림", "t")));
        assertThat(ex.fieldErrors().get(0).code()).isEqualTo("NOT_REVERSIBLE");
    }

    @Test
    void LOT_번호_방식_SEQ_ITEM_SEQ_WO() throws Exception {
        runner.run(comp, () -> policies.set("LOT_NO_MODE", null, "ITEM_SEQ", "t"));
        long a = receive("RM", "1", null, null);
        assertThat(accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, a)).matches("^RM-L\\d{6}-0001$");
        runner.run(comp, () -> policies.set("LOT_NO_MODE", null, "WO", "t"));
        long wo = releasedWo("FG");
        long p1 = runner.call(comp, () -> lots.createProduced(wo, BigDecimal.TEN, Instant.now(), "t"));
        long p2 = runner.call(comp, () -> lots.createProduced(wo, BigDecimal.TEN, Instant.now(), "t"));
        assertThat(accounts.query(comp, "select string_agg(lot_no, ',' order by lot_id) from lot_master where lot_id in (?, ?)",
                String.class, p1, p2)).isEqualTo("WO-T1,WO-T1-2");
        long b = receive("RM", "1", null, null);       // 생산이 아닌 LOT 은 WO 방식이어도 일련
        assertThat(accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, b)).matches("^L\\d{6}-0002$");
    }

    // ---------- 직접 SQL (원장 규칙) ----------

    @Test
    void 직접_SQL_원장은_수정_삭제_불가_잔량_보류_역분개_모양을_트리거가_지킨다() throws Exception {
        long a = receive("RM", "10", null, null);
        long b = receive("RM", "10", null, null);
        long other = receive("RM2", "10", null, null);
        String ins = """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method,
                                           recorded_by, reversal_of_id, reason)
                values (?, ?, ?, ?, ?, 'KG', now(), ?, 't', ?, ?)""";
        long g = accounts.query(comp, ins.replace("values", "values") + " returning gen_id", Long.class,
                comp, a, b, "SPLIT", 3, "KEY", null, null);
        for (String sql : new String[] {"update lot_genealogy set qty = 1 where gen_id = " + g, "delete from lot_genealogy where gen_id = " + g,
            "delete from lot_master where lot_id = " + a}) {
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, sql)))).as(sql).contains("42501");
        }
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, a, b, "SPLIT", 8, "KEY", null, null))))
                .contains("ck_lot_genealogy_balance");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, a, other, "SPLIT", 1, "KEY", null, null))))
                .contains("ck_lot_genealogy_item");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, a, b, "SPLIT", 1, "CORRECT", null, "x"))))
                .contains("ck_lot_genealogy_qty");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, a, b, "SPLIT", -2, "CORRECT", g, "반만"))))
                .contains("ck_lot_genealogy_reversal");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, a, a, "SPLIT", 1, "KEY", null, null))))
                .contains("ck_lot_genealogy_self");
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '의심', 't' from lot_master where lot_id = ?", a);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, a, b, "SPLIT", 1, "KEY", null, null))))
                .contains("ck_lot_genealogy_hold");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update lot_master set qty_created = 999 where lot_id = ?", a)))).contains("ck_lot_master_fixed");
        // 사유 없는(NULL) 역분개 — 처음 제약은 NULL 을 통과시켰다(6단계에서 발견, 사유 제약 추가)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, a, b, "SPLIT", -3, "CORRECT", g, null))))
                .contains("ck_lot_genealogy_reversal_reason");
        // 역분개 모양이 맞으면 직접 SQL 도 됨(보류여도 — 되돌리는 것은 막지 않는다), 두 번째는 유일 위반
        accounts.exec(comp, ins, comp, a, b, "SPLIT", -3, "CORRECT", g, "정정");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, ins, comp, a, b, "SPLIT", -3, "CORRECT", g, "정정2"))))
                .contains("23505");
        assertThat(balance(a)).isEqualByComparingTo("10");
    }

    // ---------- 동시성 (C) ----------

    @Test
    void 같은_LOT_을_동시에_분할하면_잔량을_넘지_않는다() throws Exception {
        for (int round = 0; round < 3; round++) {
            long p = receive("RM", "100", null, null);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> c = () -> postJson(L + "/" + p + "/split", "{\"qtys\":[\"60\"]}").andReturn().getResponse().getStatus();
                List<Future<Integer>> fs = pool.invokeAll(List.of(c, c));
                assertThat(List.of(fs.get(0).get(), fs.get(1).get())).containsExactlyInAnyOrder(200, 400);
            } finally {
                pool.shutdown();
            }
            assertThat(balance(p)).isEqualByComparingTo("40");
        }
    }

    // ---------- A사로 B사 · 권한 ----------

    @Test
    void 같은_공급사_LOT_을_나눠_받으면_내부_LOT_이_여럿_리콜_조회는_모두_B사_것과는_섞이지_않는다() throws Exception {
        // 결정 #43(V135): 입고마다 새 내부 LOT, 공급사 LOT 은 속성
        long a = receive("RM", "10", "V-1", "COIL-55");
        long b = receive("RM", "20", "V-1", "COIL-55");
        long c = receive("RM", "5", "V-1", "COIL-55");
        assertThat(List.of(a, b, c)).doesNotHaveDuplicates();
        assertThat(balance(a)).isEqualByComparingTo("10");                     // 만든 양은 LOT 마다 그대로(더하지 않는다)
        assertThat(balance(b)).isEqualByComparingTo("20");
        assertThat(accounts.query(comp, "select count(distinct lot_no) from lot_master where supplier_lot_no = 'COIL-55'", Integer.class))
                .isEqualTo(3);
        // 리콜: 공급사 LOT 으로 찾으면 세 내부 LOT 모두
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        mvc.perform(get(L).param("from", today).param("to", today).param("keyword", "COIL-55").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(3));
        // 유일 인덱스는 없고 같은 열의 조회 인덱스가 있다
        assertThat(accounts.query(comp, "select count(*) from pg_indexes where indexname = 'uq_lot_master_supplier_lot'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select indexdef from pg_indexes where indexname = 'ix_lot_master_supplier_lot'", String.class))
                .doesNotContain("UNIQUE").contains("supplier_lot_no");
        // A사로 B사: B사가 같은 공급사 LOT 번호로 받아도 A사 조회에 섞이지 않는다
        String other = accounts.newComp("LS");
        accounts.exec(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RM', 'RM 품목', 'RM', 'BUY', 'KG', 't', 't')""", other);
        accounts.exec(other, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'V-1', 'V-1 상사', 'PURCHASE', 't', 't')",
                other);
        runner.call(other, () -> lots.receiveAs("RM", new BigDecimal("7"), "V-1", "COIL-55", "t"));
        mvc.perform(get(L).param("from", today).param("to", today).param("keyword", "COIL-55").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(3));
        assertThat(accounts.query(other, "select count(*) from lot_master where supplier_lot_no = 'COIL-55'", Integer.class)).isEqualTo(1);
    }

    @Test
    void B사는_A사_LOT_을_보거나_쓰거나_계보로_잇지_못한다() throws Exception {
        long a = receive("RM", "10", null, null);
        String other = accounts.newComp("LB");
        long ou = accounts.newUser(other, "b1", PW);
        accounts.grant(other, ou, "PRD", "prd-lot", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, ou, "b1");
        mvc.perform(get(L + "/" + a).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(L + "/" + a + "/trace").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(L + "/" + a + "/split"), "{\"qtys\":[\"1\"]}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(L + "/" + a + "/relabel"), "{\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(L + "/" + a + "/hold"), "{\"holdYn\":\"Y\",\"reason\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        String no = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, a);
        mvc.perform(get(L + "/by-no").param("lotNo", no).with(b)).andExpect(status().isNotFound());
        accounts.exec(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RM', 'B 원자재', 'RM', 'BUY', 'KG', 't', 't')""", other);
        long bl = accounts.query(other, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'B-1', item_id, 'RECEIVED', 5, 'KG', now(), 't', 't' from mst_item where item_cd = 'RM' returning lot_id""",
                Long.class, other);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, 'CONSUME', 1, 'KG', now(), 'KEY', 't')""", other, a, bl)))).contains("fk_lot_genealogy_lot_comp");
        mvc.perform(json(post(L + "/merge"), "{\"parts\":[{\"lotId\":" + a + "},{\"lotId\":" + bl + "}]}").with(b))
                .andExpect(status().isNotFound());
        assertThat(balance(a)).isEqualByComparingTo("10");
    }

    @Test
    void 조회_권한만_있으면_등록_분할은_403() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "prd-lot", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        long a = receive("RM", "10", null, null);
        mvc.perform(get(L + "/" + a + "/trace").with(v)).andExpect(status().isOk());
        mvc.perform(json(post(L + "/" + a + "/split"), "{\"qtys\":[\"1\"]}").with(v)).andExpect(status().isForbidden());
        mvc.perform(json(post(L + "/receive"), "{\"itemCd\":\"RM\",\"qty\":\"1\"}").with(v)).andExpect(status().isForbidden());
    }

    // ---------- 실측 (docs/25 2장 4단계 완료 기준) ----------

    @Test
    void 십단_천_LOT_역추적_실측() throws Exception {
        // 뿌리 1 + 10단 × 100 LOT. 단 k 의 i 번째 LOT 이 단 k-1 의 (i % 단 크기) 번째 LOT 으로 투입된다 → 연결 1,000
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'PF-' || lv || '-' || n, (select item_id from mst_item where item_cd = 'RM'), 'RECEIVED', 1000, 'KG', now(), 't', 't'
                  from generate_series(0, 10) lv, generate_series(1, 100) n where lv > 0 or n = 1""", comp);
        accounts.exec(comp, """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method, recorded_by)
                select ?, p.lot_id, c.lot_id, 'CONSUME', 1, 'KG', now(), 'AUTO', 't'
                  from generate_series(1, 10) lv cross join generate_series(1, 100) n
                  join lot_master p on p.lot_no = 'PF-' || lv || '-' || n
                  join lot_master c on c.lot_no = 'PF-' || (lv - 1) || '-' || (case when lv = 1 then 1 else n end)""", comp);
        long root = lotId("PF-0-1");
        long t0 = System.nanoTime();
        MvcResult r = mvc.perform(get(L + "/" + root + "/trace").param("dir", "BACK").with(admin)).andExpect(status().isOk()).andReturn();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String body = r.getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.data.rows.length()")).isEqualTo(1000);
        assertThat(JsonPath.<List<Integer>>read(body, "$.data.rows[*].lvl").stream().mapToInt(Integer::intValue).max().orElse(0)).isEqualTo(10);
        assertThat(JsonPath.<Boolean>read(body, "$.data.truncated")).isFalse();
        long t1 = System.nanoTime();
        mvc.perform(get(L + "/" + lotId("PF-10-1") + "/trace").param("dir", "FWD").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(10));
        long fms = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("[실측] 10단 × 1,000 LOT 역추적 " + ms + "ms, 정추적(10단) " + fms + "ms");
        assertThat(ms).isLessThan(5000);
        // 깊이 상한: 정책 3 이면 3단까지만
        runner.run(comp, () -> policies.set("LOT_TRACE_DEPTH", null, "3", "t"));
        mvc.perform(get(L + "/" + root + "/trace").param("dir", "BACK").with(admin))
                .andExpect(jsonPath("$.data.depthLimit").value(3))
                .andExpect(jsonPath("$.data.rows.length()").value(300));
    }
}
