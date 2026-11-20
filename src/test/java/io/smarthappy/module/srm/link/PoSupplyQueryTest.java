package io.smarthappy.module.srm.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.srm.link.PoSupplyQuery.Supply;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 발주 공급 조회 계약 (docs/31 4.8, APS 용) — 상태별 포함·제외, 회신 분할 약속 + 나머지 납기일, 거절이면 납기일,
 * 기본 단위 환산(환산 없으면 inBaseUnit=false), 품목·날짜·공장 조건, A사로 B사, 빈 없으면 NONE.
 */
@AutoConfigureMockMvc
class PoSupplyQueryTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<PoSupplyQuery> provider;

    String comp;
    RequestPostProcessor me;
    LocalDate today;
    long bolt;
    long resin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PS");
        me = buyer(comp);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        seed(comp);
        bolt = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
        resin = accounts.query(comp, "select item_id from mst_item where item_cd = 'RESIN-A'", Long.class);
    }

    private RequestPostProcessor buyer(String c) {
        long u = accounts.newUser(c, "buyer1", PW);
        accounts.grant(c, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-ack", "SEARCH", "SAVE");
        return accounts.as(c, u, "buyer1");
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't'), (?, 'RESIN-A', '수지', 'RM', 'BUY', 'KG', 'N', 't', 't')""", c, c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void act(RequestPostProcessor who, long id, String action) throws Exception {
        int rv = JsonPath.read(mvc.perform(get(PO + "/" + id).with(who)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
        postAs(who, PO + "/" + id + "/" + action, "{\"rowVer\":" + rv + (action.equals("cancel") ? ",\"reason\":\"x\"" : "") + "}")
                .andExpect(status().isOk());
    }

    /** 발주(행: BOLT 100 EA, RESIN 2.5 KG, 요구 납기 +20) — issue 면 확정·발행까지 */
    private long po(RequestPostProcessor who, String plant, boolean issue) throws Exception {
        String due = today.plusDays(20).toString();
        String body = mvc.perform(post(PO).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(
                "{\"custCd\":\"V100\",\"plantCd\":\"" + plant + "\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\",\"unitPrice\":\"1\",\"dueDate\":\""
                        + due + "\"},{\"itemCd\":\"RESIN-A\",\"qty\":\"2.5\",\"unitPrice\":\"1\",\"dueDate\":\"" + due + "\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data")).longValue();
        if (issue) {
            act(who, id, "confirm");
            act(who, id, "issue");
        }
        return id;
    }

    private long lineOf(String c, long poId, int lineNo) {
        return accounts.query(c, "select po_line_id from srm_po_line where po_id = ? and line_no = ?", Long.class, poId, lineNo);
    }

    private List<Supply> open(String c, String plant, List<Long> items, LocalDate to) {
        return runner.call(c, () -> PoSupplyQuery.of(provider).open(plant, items, to));
    }

    @Test
    void 발행_회신_발주만_분할약속은_약속마다_나머지는_납기일_거절이면_납기일_품목과_날짜와_공장_조건() throws Exception {
        po(me, "P1", false);                                  // 작성 중 — 제외
        long cancelled = po(me, "P1", true);
        act(me, cancelled, "cancel");                         // 취소 — 제외
        long other = po(me, "P2", true);                      // 다른 공장 — 제외
        long issued = po(me, "P1", true);                     // 회신 없음 → 납기일
        long acked = po(me, "P1", true);
        // 회신: BOLT 60@+5 · 30@+8 (10 은 약속 안 함), RESIN 약속 없음
        postAs(me, "/api/srm/acks/pos/" + acked, "{\"rev\":0,\"lines\":[{\"poLineId\":" + lineOf(comp, acked, 1) + ",\"promisedDate\":\""
                + today.plusDays(5) + "\",\"promisedQty\":\"60\"},{\"poLineId\":" + lineOf(comp, acked, 1) + ",\"promisedDate\":\""
                + today.plusDays(8) + "\",\"promisedQty\":\"30\"}]}").andExpect(status().isOk());
        List<Supply> all = open(comp, "P1", List.of(bolt, resin), null);
        assertThat(all).extracting(Supply::poId).containsOnly(issued, acked).doesNotContain(cancelled, other);
        assertThat(all).filteredOn(s -> s.poId() == acked && s.itemId() == bolt)
                .extracting(s -> s.supplyDate() + ":" + s.qty().toPlainString() + ":" + s.promised())
                .containsExactly(today.plusDays(5) + ":60:true", today.plusDays(8) + ":30:true", today.plusDays(20) + ":10:false");
        assertThat(all).filteredOn(s -> s.poId() == issued)
                .extracting(s -> s.supplyDate() + ":" + s.qty().toPlainString() + ":" + s.unitCd() + ":" + s.inBaseUnit())
                .containsExactly(today.plusDays(20) + ":100:EA:true", today.plusDays(20) + ":2.5:KG:true");
        assertThat(all).isSortedAccordingTo(java.util.Comparator.comparing(Supply::supplyDate));
        // 품목·날짜 조건, 빈 품목 목록 = 빈 결과, 공장 P2
        assertThat(open(comp, "P1", List.of(resin), null)).extracting(Supply::itemId).containsOnly(resin);
        assertThat(open(comp, "P1", List.of(bolt), today.plusDays(7))).extracting(s -> s.qty().toPlainString()).containsExactly("60");
        assertThat(open(comp, "P1", List.of(), null)).isEmpty();
        assertThat(open(comp, "P2", List.of(bolt, resin), null)).extracting(Supply::poId).containsOnly(other);
        // 재회신이 거절 → 약속 무시, 납기일 한 줄
        postAs(me, "/api/srm/acks/pos/" + acked, "{\"rev\":0,\"reject\":true,\"rejectReason\":\"생산 불가\"}").andExpect(status().isOk());
        assertThat(open(comp, "P1", List.of(bolt), null)).filteredOn(s -> s.poId() == acked)
                .extracting(s -> s.supplyDate() + ":" + s.qty().toPlainString()).containsExactly(today.plusDays(20) + ":100");
        // 마감되면 제외
        act(me, issued, "close");
        assertThat(open(comp, "P1", List.of(bolt, resin), null)).extracting(Supply::poId).doesNotContain(issued);
    }

    @Test
    void 기본단위가_바뀌면_환산표로_환산_환산이_없으면_행단위_그대로_inBaseUnit_false() throws Exception {
        long id = po(me, "P1", true);
        // 볼트 기본 단위를 BOX(시스템 단위)로(발주 행은 EA 스냅샷), 환산 EA → BOX 0.01 (100 EA = 1 BOX) / 수지도 BOX 로 바꾸고 환산 없음
        accounts.exec(comp, "update mst_item set unit_cd = 'BOX' where item_id = ?", bolt);
        accounts.exec(comp, "update mst_item set unit_cd = 'BOX' where item_id = ?", resin);
        accounts.exec(comp, "insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by) "
                + "values (?, ?, 'EA', 'BOX', 0.01, 't', 't')", comp, bolt);
        List<Supply> s = open(comp, "P1", List.of(bolt, resin), null);
        assertThat(s).filteredOn(x -> x.itemId() == bolt).extracting(x -> x.qty().toPlainString() + ":" + x.unitCd() + ":" + x.inBaseUnit())
                .containsExactly("1:BOX:true");
        // 발주 단위 칸(WMS 구매 입고 화면용): 기본 단위로 바뀌어도 발주 행 단위·환산 전 수량 그대로
        assertThat(s).filteredOn(x -> x.itemId() == bolt).extracting(x -> x.openPoQty().toPlainString() + ":" + x.poUnitCd())
                .containsExactly("100:EA");
        assertThat(s).filteredOn(x -> x.itemId() == resin).extracting(x -> x.openPoQty().toPlainString() + ":" + x.poUnitCd())
                .containsExactly("2.5:KG");
        assertThat(s).filteredOn(x -> x.itemId() == resin).extracting(x -> x.qty().toPlainString() + ":" + x.unitCd() + ":" + x.inBaseUnit())
                .containsExactly("2.5:KG:false");
        assertThat(s).allMatch(x -> x.poId() == id);
    }

    @Test
    void A사로_B사_같은_공장코드여도_자기회사_발주만_빈이_없으면_NONE() throws Exception {
        long a = po(me, "P1", true);
        String other = accounts.newComp("PB");
        RequestPostProcessor b = buyer(other);
        seed(other);
        long bPo = po(b, "P1", true);
        long bBolt = accounts.query(other, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
        assertThat(open(other, "P1", List.of(bBolt), null)).extracting(Supply::poId).containsOnly(bPo);
        // B사 범위에서 A사 품목 id 로 물어도 빈 결과
        assertThat(open(other, "P1", List.of(bolt, resin), null)).isEmpty();
        assertThat(open(comp, "P1", List.of(bBolt), null)).isEmpty();
        assertThat(open(comp, "P1", List.of(bolt, resin), null)).extracting(Supply::poId).containsOnly(a);
        // SRM 빈이 없는 설치본 → NONE(빈 목록), 있으면 SRM 구현
        ObjectProvider<PoSupplyQuery> none = new StaticListableBeanFactory().getBeanProvider(PoSupplyQuery.class);
        assertThat(PoSupplyQuery.of(none)).isSameAs(PoSupplyQuery.NONE);
        assertThat(PoSupplyQuery.NONE.open("P1", List.of(1L), null)).isEmpty();
        // 품목 없이(null) 부르면 거부 — 회사·공장 전체를 상한 없이 읽지 않는다
        assertThatThrownBy(() -> open(comp, "P1", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PoSupplyQuery.of(provider)).isInstanceOf(SrmPoSupplyQuery.class);
    }
}
