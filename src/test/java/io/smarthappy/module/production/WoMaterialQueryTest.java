package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.production.link.WoMaterialQuery;
import io.smarthappy.module.production.link.WoMaterialQuery.WoNeeds;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 지시별 남은 자재 소요 계약 (docs/25 4.18, APS 5단계) — 투입 전(발행 스냅샷·계획 전개), 일부 투입(같은 공정 먼저 → 남는 양은 공정 순서),
 * 정정(역분개) 뒤, LOT 아닌 자재는 전량, 부산물 제외, 전개 실패, 끝난 지시 빠짐, A사로 B사, 상한, 계약 없음(NONE).
 * 새 지시 가정(APS 6단계 CTP, docs/38 15장): routing·explode 가 지시 생성·계획 지시 전개와 같은 결과.
 */
@AutoConfigureMockMvc
class WoMaterialQueryTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String W = "/api/production/wos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    LotService lots;

    @Autowired
    WoMaterialQuery query;

    @Autowired
    ObjectProvider<WoMaterialQuery> provider;

    String comp;
    RequestPostProcessor admin;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("WM");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "PRD", "prd-wo", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        seed(comp);
    }

    /** FG 기준 1: RM 2 KG(공정 10) · RM 1 KG(공정 20) · PART 3 EA(LOT 비관리, 공정 지정 없음) · SCRAP 0.5 KG 부산물. NC 는 환산 없는 BOM */
    private void seed(String c) {
        for (String[] i : new String[][] {{"FG", "EA", "Y"}, {"RM", "KG", "Y"}, {"PART", "EA", "N"}, {"SCRAP", "KG", "Y"},
            {"NC", "EA", "Y"}}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', ?, ?, 't', 't')""", c, i[0], i[0] + " 품목", i[1], i[2]);
        }
        accounts.exec(c, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) "
                + "values (?, 'CUT', '절단', 'ASSEMBLY', 't', 't'), (?, 'ASM', '조립', 'ASSEMBLY', 't', 't')", c, c);
        for (String item : new String[] {"FG", "NC"}) {
            long rid = accounts.query(c, """
                    insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                    select ?, item_id, 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't' from mst_item where item_cd = ? returning routing_id""",
                    Long.class, c, item);
            accounts.exec(c, """
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                    values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'CUT'), 't', 't'),
                           (?, ?, 20, (select proc_id from mst_proc where proc_cd = 'ASM'), 't', 't')""", c, rid, c, rid);
            accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        }
        bom(c, "FG", new Object[][] {{"RM", "2", "KG", "NORMAL", 10}, {"RM", "1", "KG", "NORMAL", 20}, {"PART", "3", "EA", "NORMAL", null},
            {"SCRAP", "0.5", "KG", "BYPRODUCT", null}});
        bom(c, "NC", new Object[][] {{"RM", "1", "ROLL", "NORMAL", 10}});
    }

    private void bom(String c, String item, Object[][] lines) {
        long id = accounts.query(c, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                select ?, item_id, 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't' from mst_item where item_cd = ? returning bom_id""",
                Long.class, c, item);
        int no = 10;
        for (Object[] l : lines) {
            accounts.exec(c, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, op_seq, issue_method,
                                              created_by, updated_by)
                    values (?, ?, ?, (select item_id from mst_item where item_cd = ?), ?::numeric, ?, ?, ?::integer, ?, 't', 't')""",
                    c, id, no, l[0], l[1], l[2], l[3], l[4], "NORMAL".equals(l[3]) ? "MANUAL" : null);
            no += 10;
        }
        accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", id);
    }

    private long wo(String item, String qty) throws Exception {
        MvcResult m = mvc.perform(post(W).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"itemCd\":\"" + item + "\",\"planQty\":\"" + qty + "\",\"planStart\":\"" + today + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long released(String qty) throws Exception {
        long w = wo("FG", qty);
        int ver = accounts.query(comp, "select row_ver from prd_wo where wo_id = ?", Integer.class, w);
        mvc.perform(post(W + "/" + w + "/release").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + ver + "}")).andExpect(status().isOk());
        return w;
    }

    private long rmLot(String qty) {
        return accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'RM-' || gen_random_uuid(), item_id, 'RECEIVED', ?::numeric, 'KG', now(), 't', 't' from mst_item where item_cd = 'RM'
                returning lot_id""", Long.class, comp, qty);
    }

    private Long proc(long woId, int opSeq) {
        return accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = ?", Long.class, woId, opSeq);
    }

    private long consume(long rm, long fg, String qty, long woId, Long woProcId) {
        return runner.call(comp, () -> lots.consume(rm, fg, new BigDecimal(qty), woId, woProcId, null, null, "SCAN", Instant.now(), "t"));
    }

    private List<WoNeeds> remaining(Long... ids) {
        return runner.call(comp, () -> query.remaining(Arrays.asList(ids)));
    }

    /** "품목@공정=수량 단위" 목록 */
    private List<String> needs(WoNeeds w) {
        return w.needs().stream().map(n -> accounts.query(comp, "select item_cd from mst_item where item_id = ?", String.class, n.itemId())
                + "@" + n.opSeq() + "=" + n.remainingQty().toPlainString() + " " + n.unitCd()).toList();
    }

    // ---------- 정상 ----------

    @Test
    void 투입_전_발행은_스냅샷_계획은_같은_전개_부산물_빠짐() throws Exception {
        long r = released("10");
        long p = wo("FG", "5");
        long none = accounts.query(comp, """
                insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, created_by, updated_by)
                select ?, 'WO-NOBOM', 'P1', w.item_id, 1, w.unit_cd, w.plan_start, w.plan_end, w.routing_id, 't', 't' from prd_wo w where w.wo_id = ?
                returning wo_id""", Long.class, comp, p);
        List<WoNeeds> out = remaining(p, r, none);
        assertThat(out).extracting(WoNeeds::woId).containsExactly(Math.min(r, p), Math.max(r, p), none);
        WoNeeds rel = out.stream().filter(x -> x.woId() == r).findFirst().orElseThrow();
        WoNeeds pl = out.stream().filter(x -> x.woId() == p).findFirst().orElseThrow();
        assertThat(rel.status()).isEqualTo("RELEASED");
        assertThat(rel.exploded()).isTrue();
        assertThat(needs(rel)).containsExactly("RM@10=20 KG", "RM@20=10 KG", "PART@null=30 EA");
        assertThat(pl.status()).isEqualTo("PLANNED");
        assertThat(needs(pl)).containsExactly("RM@10=10 KG", "RM@20=5 KG", "PART@null=15 EA");
        // BOM 없는 계획 지시 = 소요 없음(전개 실패 아님)
        assertThat(out.get(2).exploded()).isTrue();
        assertThat(out.get(2).needs()).isEmpty();
    }

    @Test
    void 일부_투입은_같은_공정에서_먼저_남는_양은_공정_순서로_빼고_LOT_아닌_자재는_전량() throws Exception {
        long w = released("10");
        long fg = runner.call(comp, () -> lots.createProduced(w, BigDecimal.TEN, Instant.now(), "t"));
        long rm = rmLot("100");
        consume(rm, fg, "8", w, proc(w, 20));      // 공정 20 줄 10 → 2
        consume(rm, fg, "15", w, proc(w, 10));     // 공정 10 줄 20 → 5
        assertThat(needs(remaining(w).get(0))).containsExactly("RM@10=5 KG", "RM@20=2 KG", "PART@null=30 EA");
        consume(rm, fg, "6", w, null);             // 공정 없음 → 공정 10 의 5, 남는 1 은 공정 20
        assertThat(needs(remaining(w).get(0))).containsExactly("RM@20=1 KG", "PART@null=30 EA");
        consume(rm, fg, "4", w, proc(w, 10));      // 초과 투입 — 0 아래로 내려가지 않는다(넘친 양은 버림)
        assertThat(needs(remaining(w).get(0))).containsExactly("PART@null=30 EA");
    }

    @Test
    void 정정_역분개_뒤에는_되돌린_투입이_다시_남은_소요() throws Exception {
        long w = released("10");
        long fg = runner.call(comp, () -> lots.createProduced(w, BigDecimal.TEN, Instant.now(), "t"));
        long rm = rmLot("100");
        long g = consume(rm, fg, "12.5", w, proc(w, 10));
        consume(rm, fg, "3", w, proc(w, 10));
        assertThat(needs(remaining(w).get(0))).containsExactly("RM@10=4.5 KG", "RM@20=10 KG", "PART@null=30 EA");
        runner.call(comp, () -> lots.reverse(g, "다른 코일을 스캔함", "t"));
        assertThat(needs(remaining(w).get(0))).containsExactly("RM@10=17 KG", "RM@20=10 KG", "PART@null=30 EA");
    }

    // ---------- 이상 사용 ----------

    @Test
    void 전개_실패는_exploded_false_끝난_지시_없는_id_중복_null_은_빠진다() throws Exception {
        long nc = wo("NC", "1");            // ROLL → KG 환산 없음: 발행도 막히는 BOM
        long done = released("1");          // 발행 뒤 취소 — 스냅샷이 있어도 빠진다
        long cancelled = wo("FG", "1");
        accounts.exec(comp, "update prd_wo set status = 'CANCELLED', cancel_reason = '테스트' where wo_id = ?", done);
        accounts.exec(comp, "update prd_wo set status = 'CANCELLED', cancel_reason = '테스트' where wo_id = ?", cancelled);
        List<Long> ids = new ArrayList<>(Arrays.asList(nc, nc, done, cancelled, 999_999_999L, null));
        List<WoNeeds> out = runner.call(comp, () -> query.remaining(ids));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).woId()).isEqualTo(nc);
        assertThat(out.get(0).exploded()).isFalse();
        assertThat(out.get(0).needs()).isEmpty();
        assertThat(runner.call(comp, () -> query.remaining(List.of()))).isEmpty();
        assertThat(runner.call(comp, () -> query.remaining(null))).isEmpty();
    }

    @Test
    void 상한_초과는_IllegalArgumentException() {
        List<Long> many = LongStream.rangeClosed(1, WoMaterialQuery.MAX_IDS + 1).boxed().toList();
        assertThat(catchThrowable(() -> runner.call(comp, () -> query.remaining(many)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(runner.call(comp, () -> query.remaining(many.subList(0, WoMaterialQuery.MAX_IDS)))).isEmpty();
    }

    @Test
    void A사로_B사_지시는_빠지고_B사_투입도_섞이지_않는다() throws Exception {
        long mine = released("10");
        String other = accounts.newComp("WMB");
        long otherUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, otherUser, "PRD", "prd-wo", "SEARCH", "SAVE");
        accounts.addPlant(other, "P1", "1공장");
        seed(other);
        String keep = comp;
        RequestPostProcessor keepAdmin = admin;
        comp = other;
        admin = accounts.as(other, otherUser, "admin1");
        long theirs = released("10");
        comp = keep;
        admin = keepAdmin;
        assertThat(remaining(mine, theirs)).extracting(WoNeeds::woId).containsExactly(mine);
        assertThat(runner.call(other, () -> query.remaining(List.of(mine, theirs)))).extracting(WoNeeds::woId).containsExactly(theirs);
    }

    @Test
    void 새_지시_가정_라우팅과_전개는_지시_생성_계획_지시와_같다() throws Exception {
        long w = wo("FG", "10");   // 계획 지시(오늘) — 지시 생성이 고른 라우팅·계획 지시 전개를 기준으로
        long fg = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG'", Long.class);
        LocalDate on = LocalDate.parse(today);
        assertThat(runner.call(comp, () -> query.routing(fg, "P1", on)))
                .isEqualTo(accounts.query(comp, "select routing_id from prd_wo where wo_id = ?", Long.class, w));
        WoNeeds virt = runner.call(comp, () -> query.explode(fg, new BigDecimal("10"), on));
        assertThat(virt.woId()).isZero();
        assertThat(virt.status()).isEqualTo("PLANNED");
        assertThat(virt.exploded()).isTrue();
        assertThat(needs(virt)).isEqualTo(needs(remaining(w).get(0)));
        // 전개 실패(환산 없음) = exploded false, BOM 없는 품목 = 줄 없음
        long nc = accounts.query(comp, "select item_id from mst_item where item_cd = 'NC'", Long.class);
        assertThat(runner.call(comp, () -> query.explode(nc, BigDecimal.ONE, on)).exploded()).isFalse();
        long part = accounts.query(comp, "select item_id from mst_item where item_cd = 'PART'", Long.class);
        WoNeeds none = runner.call(comp, () -> query.explode(part, BigDecimal.ONE, on));
        assertThat(none.exploded()).isTrue();
        assertThat(none.needs()).isEmpty();
        // 라우팅 없음: 다른 공장, 유효 시작 전 날짜, BOM 만 있는 품목
        assertThat(runner.call(comp, () -> query.routing(fg, "P9", on))).isNull();
        assertThat(runner.call(comp, () -> query.routing(fg, "P1", on.minusDays(60)))).isNull();
        assertThat(runner.call(comp, () -> query.routing(part, "P1", on))).isNull();
        // A사로 B사: B사 범위에서 A사 품목 id 로 물으면 라우팅 없음·소요 없음(RLS)
        String other = accounts.newComp("WMC");
        accounts.addPlant(other, "P1", "1공장");
        assertThat(runner.call(other, () -> query.routing(fg, "P1", on))).isNull();
        assertThat(runner.call(other, () -> query.explode(fg, BigDecimal.TEN, on)).needs()).isEmpty();
        // 계약 없음(NONE): 라우팅 없음, 소요 없음
        assertThat(WoMaterialQuery.NONE.routing(fg, "P1", on)).isNull();
        assertThat(WoMaterialQuery.NONE.explode(fg, BigDecimal.TEN, on).needs()).isEmpty();
    }

    @Test
    void 계약_없음_NONE_과_of() {
        assertThat(WoMaterialQuery.NONE.remaining(List.of(1L, 2L))).isEmpty();
        assertThat(WoMaterialQuery.of(provider)).isSameAs(query);
        assertThat(WoMaterialQuery.of(new DefaultListableBeanFactory().getBeanProvider(WoMaterialQuery.class))).isSameAs(WoMaterialQuery.NONE);
    }
}
