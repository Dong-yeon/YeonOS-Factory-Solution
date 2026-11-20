package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
 * CMS 예비품 출고·반납 호출부 (docs/33 11.15) — 보전 작업지시 완료 전이가 WMS 계약 {@code SpareIssueLink.onMwoDone} 을 같은 트랜잭션에서 부르고,
 * 완료 뒤 반납 API 가 {@code onPartReturn} 을 부른다. WMS 원장 규칙 자체(칸 순서·LOT·마감·동시)는 WMS 시험(MroIssuePostingTest)이 맡고,
 * 여기서는 CMS 화면 API 를 거친 흐름·롤백·상태·권한·A사로 B사를 본다.
 */
@AutoConfigureMockMvc
class CmsSpareIssueTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long sp;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SI");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', '프레스', ?, 't', 't')",
                comp, cls);
        long wh = node(null, "WH", "SP-WH");
        a1 = node(wh, "LOC", "A-01");
        sp = item("SP-1");
        item("SP-X");                                   // 원장에 들어온 적 없는 품목 — 출고 건너뜀(D33)
        adjIn(sp, a1, "5");
        policy("Y");
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    long item(String cd) {
        return accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'CON', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, comp, cd, cd);
    }

    void adjIn(long item, long node, String qty) {
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', ?::numeric, 'EA', 'MANUAL', now() - interval '1 day', 'KEY', 't')""",
                comp, item, node, qty);
    }

    void policy(String yn) {
        accounts.exec(comp, "delete from sys_policy where policy_key = 'WMS_MRO_LINK'");
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'WMS_MRO_LINK', ?, 't', 't')",
                comp, yn);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        Object v = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.mwoId")).longValue();
    }

    private int ver(long mwo) {
        return accounts.query(comp, "select row_ver from cms_mwo where mwo_id = ?", Integer.class, mwo);
    }

    private String st(long mwo) {
        return accounts.query(comp, "select status from cms_mwo where mwo_id = ?", String.class, mwo);
    }

    /** 착수한 CM 작업지시 + 사용 예비품 줄들 */
    private long wip(String... itemQty) throws Exception {
        long m = id(postJson(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"정비\"}"));
        for (int i = 0; i < itemQty.length; i += 2) {
            id(postJson(M + "/" + m + "/parts", "{\"itemCd\":\"" + itemQty[i] + "\",\"qty\":\"" + itemQty[i + 1] + "\"}"));
        }
        postJson(M + "/" + m + "/start", "{\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        return m;
    }

    private ResultActions finish(long m) throws Exception {
        return postJson(M + "/" + m + "/finish", "{\"title\":\"정비\",\"workText\":\"베어링 교체\",\"rowVer\":" + ver(m) + "}");
    }

    private long part(long m, String itemCd) {
        return accounts.query(comp, "select p.part_id from cms_mwo_part p join mst_item i on i.item_id = p.item_id where p.mwo_id = ? and i.item_cd = ?",
                Long.class, m, itemCd);
    }

    private ResultActions ret(RequestPostProcessor who, long m, long partId, String qty, String reason, String req) throws Exception {
        return mvc.perform(json(post(M + "/" + m + "/parts/" + partId + "/return"),
                "{\"qty\":" + (qty == null ? "null" : "\"" + qty + "\"") + ",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"")
                        + ",\"clientReqId\":\"" + req + "\"}").with(who));
    }

    private BigDecimal stock() {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, a1, sp)
                .stripTrailingZeros();
    }

    private int rows(String type) {
        return accounts.query(comp, "select count(*) from inv_trx where trx_type = ?", Integer.class, type);
    }

    @Test
    void 완료하면_사용예비품을_출고하고_원장밖_품목은_건너뛰며_빈_작업지시는_그대로_완료() throws Exception {
        long m = wip("SP-1", "3", "SP-X", "1");
        finish(m).andExpect(status().isOk());
        assertThat(st(m)).isEqualTo("DONE");
        assertThat(stock()).isEqualByComparingTo("2");
        assertThat(rows("ISSUE_MRO")).isEqualTo(1);
        // 출고 원장 = 작업지시 줄·완료 시각·처리자
        assertThat(accounts.query(comp, "select src_type || ':' || src_id || ':' || recorded_by from inv_trx where trx_type = 'ISSUE_MRO'", String.class))
                .isEqualTo("CMS_PART:" + part(m, "SP-1") + ":admin1");
        assertThat(accounts.query(comp, "select (t.occurred_at = m.finished_at) from inv_trx t, cms_mwo m where t.trx_type = 'ISSUE_MRO' and m.mwo_id = ?",
                Boolean.class, m)).isTrue();
        // 사용 예비품 없는 작업지시
        long empty = wip();
        finish(empty).andExpect(status().isOk());
        assertThat(rows("ISSUE_MRO")).isEqualTo(1);
    }

    @Test
    void 재고가_모자라면_409_완료가_통째로_롤백되어_작업중_그대로() throws Exception {
        long m = wip("SP-1", "4", "SP-1", "2");             // 합 6 > 5
        finish(m).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(st(m)).isEqualTo("WIP");
        assertThat(accounts.query(comp, "select finished_at is null from cms_mwo where mwo_id = ?", Boolean.class, m)).isTrue();
        assertThat(rows("ISSUE_MRO")).isZero();
        assertThat(stock()).isEqualByComparingTo("5");
        // 줄을 고친 뒤 다시 완료
        postJson(M + "/" + m + "/parts/" + accounts.query(comp, "select max(part_id) from cms_mwo_part where mwo_id = ?", Long.class, m) + "/delete", "")
                .andExpect(status().isOk());
        finish(m).andExpect(status().isOk());
        assertThat(stock()).isEqualByComparingTo("1");
    }

    @Test
    void 공장_스위치가_꺼져_있으면_기록만_반납은_연동안된줄_false() throws Exception {
        policy("N");
        long m = wip("SP-1", "3");
        finish(m).andExpect(status().isOk());
        assertThat(rows("ISSUE_MRO")).isZero();
        assertThat(stock()).isEqualByComparingTo("5");
        ret(admin, m, part(m, "SP-1"), "1", "덜 씀", "req-off-0001").andExpect(status().isOk()).andExpect(jsonPath("$.data").value(false));
        assertThat(rows("MRO_RTN")).isZero();
    }

    @Test
    void 반납은_완료뒤만_같은요청은_한번_누적은_출고를_못넘고_입력검사_권한() throws Exception {
        long m = wip("SP-1", "3");
        long p = part(m, "SP-1");
        // 완료 전(WIP) 반납 → 상태 오류
        ret(admin, m, p, "1", "덜 씀", "req-wip-0001").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        finish(m).andExpect(status().isOk());
        // 입력: 수량 없음·0·자릿수(EA 0자리)·사유 없음
        ret(admin, m, p, null, "덜 씀", "req-bad-0001").andExpect(jsonPath("$.errors[0].field").value("qty"));
        ret(admin, m, p, "0", "덜 씀", "req-bad-0002").andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        ret(admin, m, p, "0.5", "덜 씀", "req-bad-0003").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        ret(admin, m, p, "1", " ", "req-bad-0004").andExpect(jsonPath("$.errors[0].field").value("reason"));
        // 정상 → 같은 요청 id 다시 → 처음 결과(한 번만)
        ret(admin, m, p, "2", "덜 씀", "req-ok-00001").andExpect(jsonPath("$.data").value(true));
        ret(admin, m, p, "2", "덜 씀", "req-ok-00001").andExpect(jsonPath("$.data").value(true));
        assertThat(rows("MRO_RTN")).isEqualTo(1);
        assertThat(stock()).isEqualByComparingTo("4");
        // 누적 반납 > 출고(3) → 409, 확인(CLOSED) 뒤에도 남은 1 은 반납 가능
        ret(admin, m, p, "2", "덜 씀", "req-over-0001").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_MRO_RETURN_OVER"));
        postJson(M + "/" + m + "/close", "{\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        ret(admin, m, p, "1", "덜 씀", "req-closed-01").andExpect(jsonPath("$.data").value(true));
        assertThat(stock()).isEqualByComparingTo("5");
        // 다른 작업지시의 줄 번호로 → 404, 조회만 권한 → 403
        long other = wip();
        finish(other).andExpect(status().isOk());
        ret(admin, other, p, "1", "덜 씀", "req-other-001").andExpect(status().isNotFound());
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-mwo", "SEARCH");
        ret(accounts.as(comp, viewer, "viewer1"), m, p, "1", "덜 씀", "req-view-0001").andExpect(status().isForbidden());
    }

    @Test
    void B사로_A사_작업지시_줄은_반납할_수_없다() throws Exception {
        long m = wip("SP-1", "3");
        finish(m).andExpect(status().isOk());
        String other = accounts.newComp("SJ");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-mwo", "SEARCH", "SAVE");
        ret(accounts.as(other, bu, "admin1"), m, part(m, "SP-1"), "1", "덜 씀", "req-b-000001").andExpect(status().isNotFound());
        assertThat(rows("MRO_RTN")).isZero();
        assertThat(stock()).isEqualByComparingTo("2");
    }
}
