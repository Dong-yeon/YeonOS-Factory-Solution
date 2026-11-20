package io.smarthappy.module.srm.claim;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import java.time.ZoneId;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 클레임 ↔ 반품 연결 (SRM 2부 R4 뒷정리): 같은 근거 NCR 이면 클레임 상세에 반품 지시, 반품 상세에 클레임이 보인다(취소된 것 제외).
 * 이상 사용: 다른 NCR 은 안 보임, A사로 B사(같은 NCR id 를 쓴 다른 회사 행)는 RLS 로 안 보임, A 협력사로 B 협력사(같은 NCR id)는 연결 질의의
 * cust_id 방어로 안 보임. 정책: 취소된 쪽 상세에도 연결된 상대를 보인다(이력 추적). "한 NCR 은 살아 있는 클레임 하나" 의 live_yn(QC-1, V178).
 */
@AutoConfigureMockMvc
class ClaimReturnLinkTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @MockitoBean
    SupplierQualityQuery quality;

    String comp;
    RequestPostProcessor me;
    long cust;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CL");
        me = user(comp);
        cust = seed(comp);
    }

    private RequestPostProcessor user(String c) {
        long u = accounts.newUser(c, "buyer1", PW);
        accounts.grant(c, u, "PUR", "srm-claim", "SEARCH");
        accounts.grant(c, u, "PUR", "srm-return", "SEARCH");
        return accounts.as(c, u, "buyer1");
    }

    private long seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        return id;
    }

    private long claim(String c, long custId, String no, long ncrId) {
        long id = accounts.query(c, """
                insert into srm_claim (comp_cd, claim_no, cust_id, action_cd, title, request_text, created_by, updated_by)
                values (?, ?, ?, 'CAPA', '녹', '원인·대책', 't', 't') returning claim_id""", Long.class, c, no, custId);
        accounts.exec(c, """
                insert into srm_claim_ncr (comp_cd, claim_id, ncr_id, ncr_no, item_id, nc_qty, unit_cd, created_by, updated_by)
                select ?, ?, ?, ?, item_id, 5, 'EA', 't', 't' from mst_item where item_cd = 'BOLT'""", c, id, ncrId, "NCR-" + ncrId);
        return id;
    }

    private long ret(String c, long custId, String no, long ncrId, int line) {
        return accounts.query(c, """
                insert into srm_return (comp_cd, return_no, cust_id, plant_cd, ncr_id, ncr_no, ncr_line_no, lot_id, lot_no, item_id, unit_cd, qty,
                                        created_by, updated_by)
                select ?, ?, ?, 'P1', ?, ?, ?, 1, 'L1', item_id, 'EA', 1, 't', 't' from mst_item where item_cd = 'BOLT' returning return_id""",
                Long.class, c, no, custId, ncrId, "NCR-" + ncrId, line);
    }

    @Test
    void 같은_NCR_이면_서로_보이고_취소된_것과_다른_NCR_은_안_보인다() throws Exception {
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        long cancelled = claim(comp, cust, "CL-OLD", 501);
        accounts.exec(comp, "update srm_claim set status = 'CANCELLED', cancel_reason = 'x' where claim_id = ?", cancelled);
        long k = claim(comp, cust, "CL-1", 501);
        long other = claim(comp, cust, "CL-2", 502);
        long r1 = ret(comp, cust, "RT-1", 501, 1);
        long r2 = ret(comp, cust, "RT-2", 501, 2);
        accounts.exec(comp, "update srm_return set status = 'CANCELLED', cancel_reason = 'x' where return_id = ?", r2);
        long r3 = ret(comp, cust, "RT-3", 503, 1);
        mvc.perform(get("/api/srm/claims/" + k).with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.returns.length()").value(1))
                .andExpect(jsonPath("$.data.returns[0].id").value(r1))
                .andExpect(jsonPath("$.data.returns[0].no").value("RT-1"))
                .andExpect(jsonPath("$.data.returns[0].ncrNo").value("NCR-501"))
                .andExpect(jsonPath("$.data.returns[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.returns[0].createdOn").value(today));
        mvc.perform(get("/api/srm/returns/" + r1).with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claims.length()").value(1))
                .andExpect(jsonPath("$.data.claims[0].no").value("CL-1"))
                .andExpect(jsonPath("$.data.claims[0].status").value("DRAFT"));
        mvc.perform(get("/api/srm/claims/" + other).with(me)).andExpect(jsonPath("$.data.returns.length()").value(0));
        mvc.perform(get("/api/srm/returns/" + r3).with(me)).andExpect(jsonPath("$.data.claims.length()").value(0));
        // 목록·grid 줄에는 연결을 싣지 않는다(상세만)
        mvc.perform(get("/api/srm/returns/grid?from=" + today + "&to=" + today).with(me))
                .andExpect(jsonPath("$.list[0].claims").value(Matchers.nullValue()));
    }

    @Test
    void A사로_B사_같은_NCR_id_를_써도_다른_회사_행은_안_보인다() throws Exception {
        long k = claim(comp, cust, "CL-1", 701);
        long r = ret(comp, cust, "RT-1", 701, 1);
        String b = accounts.newComp("CM");
        RequestPostProcessor bu = user(b);
        long bCust = seed(b);
        long bk = claim(b, bCust, "CL-B", 701);
        long br = ret(b, bCust, "RT-B", 701, 1);
        mvc.perform(get("/api/srm/claims/" + k).with(me)).andExpect(jsonPath("$.data.returns[*].no").value(Matchers.contains("RT-1")));
        mvc.perform(get("/api/srm/returns/" + r).with(me)).andExpect(jsonPath("$.data.claims[*].no").value(Matchers.contains("CL-1")));
        mvc.perform(get("/api/srm/claims/" + bk).with(bu)).andExpect(jsonPath("$.data.returns[*].no").value(Matchers.contains("RT-B")));
        mvc.perform(get("/api/srm/returns/" + br).with(bu)).andExpect(jsonPath("$.data.claims[*].no").value(Matchers.contains("CL-B")));
        mvc.perform(get("/api/srm/claims/" + k).with(bu)).andExpect(status().isNotFound());
    }

    @Test
    void A_협력사로_B_협력사_같은_NCR_id_여도_연결되지_않는다() throws Exception {
        long other = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V200', 'V200 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')",
                other, comp);
        long k = claim(comp, cust, "CL-1", 801);
        long r = ret(comp, cust, "RT-1", 801, 1);
        long ro = ret(comp, other, "RT-O", 801, 2);
        mvc.perform(get("/api/srm/claims/" + k).with(me)).andExpect(jsonPath("$.data.returns[*].no").value(Matchers.contains("RT-1")));
        mvc.perform(get("/api/srm/returns/" + r).with(me)).andExpect(jsonPath("$.data.claims[*].no").value(Matchers.contains("CL-1")));
        mvc.perform(get("/api/srm/returns/" + ro).with(me)).andExpect(jsonPath("$.data.claims.length()").value(0));
    }

    @Test
    void 취소된_클레임_반품_상세에도_연결된_상대가_보인다() throws Exception {
        long k = claim(comp, cust, "CL-1", 811);
        long r = ret(comp, cust, "RT-1", 811, 1);
        accounts.exec(comp, "update srm_claim set status = 'CANCELLED', cancel_reason = 'x' where claim_id = ?", k);
        mvc.perform(get("/api/srm/claims/" + k).with(me)).andExpect(jsonPath("$.data.returns[*].no").value(Matchers.contains("RT-1")));
        long k2 = claim(comp, cust, "CL-2", 812);
        long r2 = ret(comp, cust, "RT-2", 812, 1);
        accounts.exec(comp, "update srm_return set status = 'CANCELLED', cancel_reason = 'x' where return_id = ?", r2);
        mvc.perform(get("/api/srm/returns/" + r2).with(me)).andExpect(jsonPath("$.data.claims[*].no").value(Matchers.contains("CL-2")));
        // 살아 있는 쪽 목록에서는 취소된 상대가 빠진다
        mvc.perform(get("/api/srm/returns/" + r).with(me)).andExpect(jsonPath("$.data.claims.length()").value(0));
        mvc.perform(get("/api/srm/claims/" + k2).with(me)).andExpect(jsonPath("$.data.returns.length()").value(0));
    }

    @Test
    void 살아_있음_칸은_취소로만_내려가고_직접_N_으로_넣어도_Y() {
        long k = claim(comp, cust, "CL-1", 821);
        assertThat(live(k)).isEqualTo("Y");
        // 작성 중 줄을 N 으로 바꿔 유일 색인을 피하려 해도 Y 로 남는다
        accounts.exec(comp, "update srm_claim_ncr set live_yn = 'N' where claim_id = ?", k);
        assertThat(live(k)).isEqualTo("Y");
        long k2 = accounts.query(comp, """
                insert into srm_claim (comp_cd, claim_no, cust_id, action_cd, title, request_text, created_by, updated_by)
                values (?, 'CL-2', ?, 'CAPA', '녹', '원인·대책', 't', 't') returning claim_id""", Long.class, comp, cust);
        Throwable t = catchThrowable(() -> accounts.exec(comp, """
                insert into srm_claim_ncr (comp_cd, claim_id, ncr_id, ncr_no, item_id, nc_qty, unit_cd, live_yn, created_by, updated_by)
                select ?, ?, 821, 'NCR-821', item_id, 5, 'EA', 'N', 't', 't' from mst_item where item_cd = 'BOLT'""", comp, k2));
        assertThat(PgErrors.constraint(t).orElse(String.valueOf(t))).isEqualTo("uq_srm_claim_ncr_live");
        // 취소하면 N, 같은 NCR 로 새 클레임 가능. 취소된 줄의 다른 칸·되살림은 막힌다
        accounts.exec(comp, "update srm_claim set status = 'CANCELLED', cancel_reason = 'x' where claim_id = ?", k);
        assertThat(live(k)).isEqualTo("N");
        long k3 = claim(comp, cust, "CL-3", 821);
        assertThat(live(k3)).isEqualTo("Y");
        Throwable back = catchThrowable(() -> accounts.exec(comp, "update srm_claim_ncr set live_yn = 'Y' where claim_id = ?", k));
        assertThat(PgErrors.constraint(back).orElse(String.valueOf(back))).isEqualTo("ck_srm_claim_ncr_draft");
        Throwable qty = catchThrowable(() -> accounts.exec(comp, "update srm_claim_ncr set nc_qty = 9 where claim_id = ?", k));
        assertThat(PgErrors.constraint(qty).orElse(String.valueOf(qty))).isEqualTo("ck_srm_claim_ncr_draft");
    }

    private String live(long claimId) {
        return accounts.query(comp, "select live_yn from srm_claim_ncr where claim_id = ?", String.class, claimId);
    }
}
