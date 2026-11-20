package io.smarthappy.module.srm.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * SRM 2부 R4c·R4d 표 골격 (docs/31 4.12.4, 임시 V9507) — 서비스 전이라 DB 규칙만 직접 SQL(앱 계정·회사 범위)로 본다.
 * 반품: 시작 상태·전이·확정 뒤 편집·근거 고정·교체 요구엔 발주 행·발주 행 품목·NCR 처리 줄당 살아 있는 반품 하나·취소 사유·삭제 권한 없음.
 * 클레임: 금액·통화·회신 칸 규칙·근거 NCR 없이 보내기·보낸 뒤 요구 고정·회신 반려·종결 뒤 회신 고정·NCR 은 살아 있는 클레임 하나·통화 코드.
 * A사로 B사: 다른 회사 협력사·발주 행·클레임, RLS.
 */
@AutoConfigureMockMvc
class ReturnClaimSchemaTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String RET = """
            insert into srm_return (comp_cd, return_no, cust_id, plant_cd, ncr_id, ncr_no, ncr_line_no, lot_id, lot_no, po_line_id,
                                    item_id, unit_cd, qty, replace_yn, created_by, updated_by)
            values (?, ?, ?, 'P1', ?, 'NCR-' || ?, 1, 500, 'L500', ?, ?, 'EA', 3, ?, 't', 't') returning return_id""";
    private static final String CLM = """
            insert into srm_claim (comp_cd, claim_no, cust_id, action_cd, title, request_text, charge_amt, currency_cd, created_by, updated_by)
            values (?, ?, ?, ?, '녹 불량', '원인과 대책을 회신 바랍니다', ?::numeric, ?, 't', 't') returning claim_id""";
    private static final String NCR = """
            insert into srm_claim_ncr (comp_cd, claim_id, ncr_id, ncr_no, item_id, nc_qty, unit_cd, created_by, updated_by)
            values (?, ?, ?, 'NCR-' || ?, ?, 10, 'EA', 't', 't')""";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    long cust;
    long item;
    long item2;
    long poLine;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("RC");
        long buyer = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, buyer, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        me = accounts.as(comp, buyer, "buyer1");
        Object[] s = seed(comp);
        cust = (Long) s[0];
        item = (Long) s[1];
        item2 = (Long) s[2];
        poLine = issuedPoLine(me);
    }

    /** 공장 P1·품목 BOLT·NUT·협력사 V100 → {cust, bolt, nut} */
    private Object[] seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        long bolt = accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, c);
        long nut = accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'NUT', '너트', 'RM', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        return new Object[] {id, bolt, nut};
    }

    private long issuedPoLine(RequestPostProcessor who) throws Exception {
        LocalDate due = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(20);
        String r = mvc.perform(post("/api/srm/pos").with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT\",\"qty\":\"10\",\"unitPrice\":\"1\",\"dueDate\":\"" + due + "\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long po = ((Number) JsonPath.read(r, "$.data")).longValue();
        String d = mvc.perform(get("/api/srm/pos/" + po).with(who)).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(d, "$.data.lines[0].poLineId")).longValue();
    }

    private String err(String c, String sql, Object... args) {
        return PgErrors.constraint(catchThrowable(() -> accounts.exec(c, sql, args))).orElse("(none)");
    }

    private long ret(String c, String no, long ncr, Long po, long it, String replace) {
        return accounts.query(c, RET, Long.class, c, no, cust, ncr, ncr, po, it, replace);
    }

    // ---------- 반품 ----------

    @Test
    void 반품_시작상태_전이_확정뒤_편집막힘_근거고정_교체요구엔_발주행_NCR줄당_하나_취소사유_삭제없음() throws Exception {
        long r = ret(comp, "R1", 77, poLine, item, "Y");
        // 같은 NCR 처리 줄에 두 번째 살아 있는 반품
        assertThat(err(comp, RET.replace(" returning return_id", ""), comp, "R2", cust, 77, 77, null, item, "N")).contains("uq_srm_return_ncr_line");
        // 교체 요구인데 발주 행 없음 / 발주 행과 다른 품목 / 처음부터 확정
        assertThat(err(comp, RET.replace(" returning return_id", ""), comp, "R3", cust, 78, 78, null, item, "Y")).contains("ck_srm_return_replace_po");
        assertThat(err(comp, RET.replace(" returning return_id", ""), comp, "R4", cust, 79, 79, poLine, item2, "N")).contains("ck_srm_return_po_item");
        assertThat(err(comp, RET.replace(" returning return_id", "").replace("created_by, updated_by)", "created_by, updated_by, status, confirmed_at)")
                .replace("'t', 't')", "'t', 't', 'CONFIRMED', now())"), comp, "R5", cust, 80, 80, null, item, "N")).contains("ck_srm_return_transition");
        // 작성 중: 수량 수정 가능, 작성 중 → 출고 건너뛰기 불가, 사유 없는 취소 불가
        accounts.exec(comp, "update srm_return set qty = 2 where return_id = ?", r);
        assertThat(err(comp, "update srm_return set status = 'SHIPPED', shipped_at = now() where return_id = ?", r)).contains("ck_srm_return_transition");
        assertThat(err(comp, "update srm_return set status = 'CANCELLED' where return_id = ?", r)).contains("ck_srm_return_cancel");
        // 확정 → 편집·근거 변경 막힘
        accounts.exec(comp, "update srm_return set status = 'CONFIRMED', confirmed_at = now(), confirmed_by = 't' where return_id = ?", r);
        assertThat(err(comp, "update srm_return set qty = 1 where return_id = ?", r)).contains("ck_srm_return_draft");
        assertThat(err(comp, "update srm_return set replace_yn = 'N' where return_id = ?", r)).contains("ck_srm_return_draft");
        assertThat(err(comp, "update srm_return set ncr_id = 99 where return_id = ?", r)).contains("ck_srm_return_fixed");
        // 출고는 WMS 반품 출고 게이트만 — 출고 근거(ship_ref) 없이 거부, 근거가 있어도 반품 출고 원장이 없으면 커밋 때 거부(R4-4 V9313·V9314).
        // 출고 뒤 취소 불가는 WMS ReturnIssueTest 에서(원장과 함께 출고한 반품으로)
        assertThat(err(comp, "update srm_return set status = 'SHIPPED', shipped_at = now() where return_id = ?", r)).contains("ck_srm_return_ship_ref");
        assertThat(err(comp, "update srm_return set status = 'SHIPPED', shipped_at = now(), ship_ref = 'RTN_ISS:T1' where return_id = ?", r))
                .contains("ck_srm_return_shipped_ledger");
        assertThat(accounts.query(comp, "select status from srm_return where return_id = ?", String.class, r)).isEqualTo("CONFIRMED");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from srm_return where return_id = ?", r))).rootCause()
                .hasMessageContaining("permission denied");
        // 취소하면 같은 NCR 줄로 다시 만들 수 있다
        long r2 = ret(comp, "R6", 81, null, item, "N");
        accounts.exec(comp, "update srm_return set status = 'CANCELLED', cancel_reason = '수량 잘못' where return_id = ?", r2);
        ret(comp, "R7", 81, null, item, "N");
        assertThat(err(comp, "update srm_return set status = 'DRAFT', cancel_reason = null where return_id = ?", r2)).contains("ck_srm_return_transition");
    }

    // ---------- 클레임 ----------

    @Test
    void 클레임_금액통화_근거없이_보내기_보낸뒤_요구고정_회신필수_반려_종결뒤_고정_NCR은_살아있는_클레임하나() {
        String clm = CLM.replace(" returning claim_id", "");
        assertThat(err(comp, clm, comp, "C0", cust, "CAPA", "1000", "KRW")).contains("ck_srm_claim_charge");
        assertThat(err(comp, clm, comp, "C0", cust, "CHARGE", "1000", null)).contains("ck_srm_claim_charge");
        assertThat(err(comp, clm, comp, "C0", cust, "BOTH", "0", "KRW")).contains("ck_srm_claim_charge");
        assertThat(err(comp, clm, comp, "C0", cust, "CHARGE", "1000", "XXX")).contains("fk_srm_claim_currency");
        long c = accounts.query(comp, CLM, Long.class, comp, "C1", cust, "BOTH", "150000", "KRW");
        // 작성 중엔 회신 칸 없음, 근거 NCR 없이 보낼 수 없음
        assertThat(err(comp, "update srm_claim set answer_cause = 'x' where claim_id = ?", c)).contains("ck_srm_claim_answer_state");
        assertThat(err(comp, "update srm_claim set status = 'SENT', sent_at = now() where claim_id = ?", c)).contains("ck_srm_claim_ncrs");
        accounts.exec(comp, NCR, comp, c, 77, 77, item);
        assertThat(err(comp, NCR, comp, c, 77, 77, item)).contains("uq_srm_claim_ncr");
        accounts.exec(comp, "update srm_claim set status = 'SENT', sent_at = now(), sent_by = 't' where claim_id = ?", c);
        // 보낸 뒤: 요구·금액·근거 고정, 회신 없이 ANSWERED 불가, 작성 중으로 못 돌아감
        assertThat(err(comp, "update srm_claim set charge_amt = 1 where claim_id = ?", c)).contains("ck_srm_claim_draft");
        assertThat(err(comp, NCR, comp, c, 78, 78, item)).contains("ck_srm_claim_ncr_draft");
        assertThat(err(comp, "delete from srm_claim_ncr where claim_id = ?", c)).contains("ck_srm_claim_ncr_draft");
        assertThat(err(comp, "update srm_claim set status = 'ANSWERED' where claim_id = ?", c)).contains("ck_srm_claim_answer");
        assertThat(err(comp, "update srm_claim set status = 'DRAFT' where claim_id = ?", c)).contains("ck_srm_claim_transition");
        accounts.exec(comp, "update srm_claim set status = 'ANSWERED', answer_cause = '열처리 부족', answer_action = '공정 조건 변경', "
                + "answered_on = current_date where claim_id = ?", c);
        // 반려(다시 요구) → 다시 회신 → 종결 → 회신 고정, 종결에서 못 나감
        accounts.exec(comp, "update srm_claim set status = 'SENT' where claim_id = ?", c);
        accounts.exec(comp, "update srm_claim set status = 'ANSWERED', answer_action = '공정 조건 변경 + 전수 검사' where claim_id = ?", c);
        assertThat(err(comp, "update srm_claim set status = 'CLOSED' where claim_id = ?", c)).contains("ck_srm_claim_closed");
        accounts.exec(comp, "update srm_claim set status = 'CLOSED', closed_at = now(), closed_by = 't' where claim_id = ?", c);
        assertThat(err(comp, "update srm_claim set answer_cause = 'y' where claim_id = ?", c)).contains("ck_srm_claim_answer_state");
        assertThat(err(comp, "update srm_claim set status = 'SENT', closed_at = null where claim_id = ?", c)).contains("ck_srm_claim_transition");
        assertThat(err(comp, "update srm_claim set claim_no = 'C9' where claim_id = ?", c)).contains("ck_srm_claim_fixed");
        // NCR 77 은 종결된(살아 있는) 클레임에 있다 → 다른 클레임에 못 넣음, 취소된 클레임의 NCR 은 다시 쓸 수 있다
        long c2 = accounts.query(comp, CLM, Long.class, comp, "C2", cust, "CAPA", null, null);
        assertThat(err(comp, NCR, comp, c2, 77, 77, item)).contains("uq_srm_claim_ncr_live");
        accounts.exec(comp, NCR, comp, c2, 88, 88, item);
        accounts.exec(comp, "update srm_claim set status = 'CANCELLED', cancel_reason = '중복' where claim_id = ?", c2);
        long c3 = accounts.query(comp, CLM, Long.class, comp, "C3", cust, "CAPA", null, null);
        accounts.exec(comp, NCR, comp, c3, 88, 88, item);
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from srm_claim where claim_id = ?", c3))).rootCause()
                .hasMessageContaining("permission denied");
    }

    // ---------- A사로 B사 ----------

    @Test
    void B사_범위에서_A사_협력사_발주행_클레임_막힘_RLS() throws Exception {
        long aRet = ret(comp, "R1", 77, poLine, item, "Y");
        long aClaim = accounts.query(comp, CLM, Long.class, comp, "C1", cust, "CAPA", null, null);
        String other = accounts.newComp("RD");
        Object[] s = seed(other);
        long bCust = (Long) s[0];
        long bItem = (Long) s[1];
        // B사 범위: 반품·클레임 0건
        assertThat(accounts.query(other, "select count(*) from srm_return", Long.class)).isZero();
        assertThat(accounts.query(other, "select count(*) from srm_claim", Long.class)).isZero();
        // A사 협력사로 B사 반품·클레임
        assertThat(err(other, RET.replace(" returning return_id", ""), other, "R1", cust, 77, 77, null, bItem, "N"))
                .contains("fk_srm_return_supplier_comp");
        assertThat(err(other, CLM.replace(" returning claim_id", ""), other, "C1", cust, "CAPA", null, null)).contains("fk_srm_claim_supplier_comp");
        // B사 반품에 A사 발주 행
        assertThat(err(other, RET.replace(" returning return_id", "").replace("values (?, ?, ?,", "values (?, ?, " + bCust + ","),
                other, "R1", 77, 77, poLine, bItem, "Y")).contains("fk_srm_return_po_line_comp");
        // A사 클레임에 B사 줄
        assertThat(err(other, NCR, other, aClaim, 77, 77, bItem)).contains("fk_srm_claim_ncr_claim_comp");
        // B사 범위에서 A사 회사코드로 쓰기 → RLS, A사 행 수정 0건
        assertThat(catchThrowable(() -> accounts.exec(other, CLM.replace(" returning claim_id", ""), comp, "C2", cust, "CAPA", null, null)))
                .isNotNull();
        accounts.exec(other, "update srm_return set qty = 1 where return_id = ?", aRet);
        assertThat(accounts.query(comp, "select qty::int from srm_return where return_id = ?", Integer.class, aRet)).isEqualTo(3);
    }
}
