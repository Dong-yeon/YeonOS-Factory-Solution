package io.smarthappy.module.qms.preship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 출하 전 검수 (docs/78) — 양식 → 검수 시작 → 판정·사진 → 미결 닫기/조건부 허용 → 완료 → WMS 출하 확정(확장점 PreshipShipCheck).
 * 이상 사용: 검수 없이·미완료·미결 있음 확정 → 409, 사진 없이 OK → 400, 같은 줄 동시 판정(옛 rowVer) → 409·덮어쓰기 없음,
 * A사 계정으로 B사 검수 → 404(+ 직접 SQL 다른 회사 참조 거부), 권한 없음 → 403, 정책 N·양식 없음 → 지금 동작 그대로.
 */
@AutoConfigureMockMvc
class PreshipTest extends PostgresTestBase {

    private static final String PW = "Harbor-cedar-64";
    private static final String S = "/api/wms/ships";
    private static final String B = "/api/qms/preship";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long stg;
    long fgLot;
    long fgPlain;
    int seq;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PS");
        admin = user(comp, "admin1", true);
        seed(comp);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        // 정책 기본값은 N(docs/79 Q4) — 검수를 보는 시험은 회사가 켠 상태에서
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_PRESHIP_REQUIRED', 'Y', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't'), (?, 'SUP1', '공급사', 'PURCHASE', 't', 't')""", c, c);
        accounts.exec(c, "insert into mst_item_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'EQ', '장비', 't', 't')", c);
        fgLot = accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-L', 'FG LOT 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't') returning item_id""", Long.class, c);
        fgPlain = accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-N', 'FG 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't') returning item_id""", Long.class, c);
        long wh = node(c, null, "WH", "FG-WH");
        a1 = node(c, wh, "LOC", "A-01");
        stg = node(c, wh, "LOC", "STG-01");
        accounts.exec(c, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, c);
    }

    private RequestPostProcessor user(String c, String id, boolean approve) {
        long u = accounts.newUser(c, id, PW);
        accounts.grant(c, u, "SALES", "sal-order", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-ship", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        if (approve) {
            accounts.grant(c, u, "QMS", "qms-preship-tpl", "SEARCH", "SAVE", "EXCEL");
            accounts.grant(c, u, "QMS", "qms-preship", "SEARCH", "SAVE", "APPROVE", "EXCEL");
            accounts.grant(c, u, "QMS", "qms-preship-punch", "SEARCH", "SAVE", "APPROVE", "EXCEL");
        }
        return accounts.as(c, u, id);
    }

    private long node(String c, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, parent, type, cd, cd);
    }

    private ResultActions postJson(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String req() {
        return "preship-" + (++seq) + "-" + System.nanoTime();
    }

    private static Object read(MvcResult r, String path) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), path);
    }

    private static long num(MvcResult r, String path) throws Exception {
        return ((Number) read(r, path)).longValue();
    }

    private int shipVer(long id) {
        return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
    }

    /** 양식 — 대상 품목 코드, 항목 [확인 내용, 사진 필수 Y/N, 해당 없음 허용 Y/N] */
    private long tpl(String cd, String itemCd, String classCd, String[]... lines) throws Exception {
        StringBuilder ls = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            ls.append(i == 0 ? "" : ",").append("{\"seq\":").append((i + 1) * 10).append(",\"checkText\":\"").append(lines[i][0])
                    .append("\",\"photoReqYn\":\"").append(lines[i][1]).append("\",\"naAllowedYn\":\"").append(lines[i][2]).append("\"}");
        }
        MvcResult r = postJson(B + "/tpls", "{\"tplCd\":\"" + cd + "\",\"tplNm\":\"" + cd + " 양식\",\"itemCd\":" + (itemCd == null ? "null" : "\"" + itemCd + "\"")
                + ",\"classCd\":" + (classCd == null ? "null" : "\"" + classCd + "\"") + ",\"lines\":[" + ls + "]}", admin)
                .andExpect(status().isOk()).andReturn();
        return num(r, "$.data");
    }

    /** LOT 품목 입고 5 → 수주 5 → 출하 지시 → 피킹. 반환 = [출하 id, 수주 행 id] */
    private long[] pickedLot() throws Exception {
        MvcResult r = postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + fgLot
                + ",\"qty\":5,\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"" + req() + "\"}", admin)
                .andExpect(status().isOk()).andReturn();
        String lotNo = (String) read(r, "$.data.lotNo");
        return shipAndPick("FG-L", lotNo);
    }

    /** LOT 관리 안 하는 품목 — 조정 입고 5 → 수주 → 출하 → 피킹(LOT 없음) */
    private long[] pickedPlain() throws Exception {
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 5, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, fgPlain, a1);
        return shipAndPick("FG-N", null);
    }

    private long[] shipAndPick(String itemCd, String lotNo) throws Exception {
        MvcResult o = postJson("/api/sales/orders", "{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"" + itemCd + "\",\"qty\":\"5\",\"dueDate\":\"2026-11-30\"}]}",
                admin).andExpect(status().isOk()).andReturn();
        long orderId = num(o, "$.data");
        long ol = accounts.query(comp, "select line_id from sal_order_line where order_id = ?", Long.class, orderId);
        MvcResult sh = postJson(S, "{\"plantCd\":\"P1\",\"custCd\":\"C-001\",\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":[{\"orderLineId\":"
                + ol + ",\"qty\":5}]}", admin).andExpect(status().isOk()).andReturn();
        long id = num(sh, "$.data.head.shipId");
        long sl = accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ?", Long.class, id);
        postJson(S + "/" + id + "/pick", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + req() + "\",\"picks\":[{\"shipLineId\":" + sl
                + ",\"fromNodeId\":" + a1 + ",\"lotNo\":" + (lotNo == null ? "null" : "\"" + lotNo + "\"") + ",\"qty\":5}]}", admin)
                .andExpect(status().isOk());
        return new long[] {id, ol};
    }

    private ResultActions confirm(long id) throws Exception {
        return postJson(S + "/" + id + "/confirm", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + req() + "\"}", admin);
    }

    private String shipped(long ol) {
        return accounts.query(comp, "select trim_scale(shipped_qty)::text from sal_order_line where line_id = ?", String.class, ol);
    }

    private long start(long shipId) throws Exception {
        MvcResult r = postJson(B + "/insps", "{\"shipId\":" + shipId + "}", admin).andExpect(status().isOk()).andReturn();
        return num(r, "$.data.insp.inspId");
    }

    /** 판정 줄 확인 내용(순번 순) */
    private List<String> results(long inspId) {
        return split(accounts.query(comp, "select string_agg(check_text, '|' order by seq, result_id) from qms_preship_result where insp_id = ?",
                String.class, inspId));
    }

    private static List<String> split(String s) {
        return s == null ? List.of() : List.of(s.split("\\|"));
    }

    private long resultId(long inspId, int i) {
        return accounts.query(comp, "select result_id from qms_preship_result where insp_id = ? order by seq, result_id offset ? limit 1", Long.class,
                inspId, i);
    }

    private int resultVer(long resultId) {
        return accounts.query(comp, "select row_ver from qms_preship_result where result_id = ?", Integer.class, resultId);
    }

    private int inspVer(long inspId) {
        return accounts.query(comp, "select row_ver from qms_preship_insp where insp_id = ?", Integer.class, inspId);
    }

    private ResultActions judge(long inspId, long resultId, String judge, String remark, RequestPostProcessor who) throws Exception {
        return postJson(B + "/insps/" + inspId + "/judge", "{\"resultId\":" + resultId + ",\"rowVer\":" + resultVer(resultId) + ",\"judge\":\"" + judge
                + "\",\"remark\":" + (remark == null ? "null" : "\"" + remark + "\"") + "}", who);
    }

    private ResultActions complete(long inspId, RequestPostProcessor who) throws Exception {
        return postJson(B + "/insps/" + inspId + "/complete", "{\"rowVer\":" + inspVer(inspId) + ",\"inspectorNm\":\"김검수\",\"witnessNm\":\"고객 입회자\"}", who);
    }

    private ResultActions photo(long resultId, RequestPostProcessor who) throws Exception {
        return mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "검수.png", "image/png", PNG))
                .param("targetType", "qms-preship").param("targetId", String.valueOf(resultId)).with(who).with(csrf()));
    }

    private long openPunch(long inspId) {
        return accounts.query(comp, "select punch_id from qms_preship_punch where insp_id = ? and status = 'OPEN'", Long.class, inspId);
    }

    private int punchVer(long punchId) {
        return accounts.query(comp, "select row_ver from qms_preship_punch where punch_id = ?", Integer.class, punchId);
    }

    // =================================================================== 출하 확정 연결

    @Test
    void 양식_없는_출하는_검수_없이_그대로_확정된다() throws Exception {
        long[] x = pickedLot();
        confirm(x[0]).andExpect(status().isOk());
        assertThat(shipped(x[1])).isEqualTo("5");
    }

    @Test
    void 검수_없이_확정하면_막히고_미완료도_막힌다() throws Exception {
        tpl("T-1", "FG-L", null, new String[] {"외관", "N", "N"});
        long[] x = pickedLot();
        String lotNo = accounts.query(comp, "select lot_no from lot_master where item_id = ?", String.class, fgLot);
        confirm(x[0]).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_CHECK_BLOCKED"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("LOT " + lotNo)))
                .andExpect(jsonPath("$.errors[0].rowKey").value(lotNo));
        long insp = start(x[0]);
        confirm(x[0]).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_CHECK_BLOCKED"));
        assertThat(shipped(x[1])).isEqualTo("0");
        assertThat(accounts.query(comp, "select status from wms_ship where ship_id = ?", String.class, x[0])).isEqualTo("PICKING");
        // 판정만 하고 완료 안 함 → 여전히 막힘, 완료하면 확정
        judge(insp, resultId(insp, 0), "OK", null, admin).andExpect(status().isOk());
        confirm(x[0]).andExpect(status().isConflict());
        complete(insp, admin).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.witnessNm").value("고객 입회자"));
        confirm(x[0]).andExpect(status().isOk());
        assertThat(shipped(x[1])).isEqualTo("5");
        // 출하 확정 뒤 완료 취소 409
        postJson(B + "/insps/" + insp + "/reopen", "{\"rowVer\":" + inspVer(insp) + "}", admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QMS_PRESHIP_STATE"));
    }

    @Test
    void LOT_없는_품목_출하도_막고_행_키는_출하_번호() throws Exception {
        tpl("T-N", "FG-N", null, new String[] {"포장", "N", "N"});
        long[] x = pickedPlain();
        String shipNo = accounts.query(comp, "select ship_no from wms_ship where ship_id = ?", String.class, x[0]);
        confirm(x[0]).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_CHECK_BLOCKED"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString(shipNo)))
                .andExpect(jsonPath("$.errors[0].rowKey").value(shipNo));
        long insp = start(x[0]);
        judge(insp, resultId(insp, 0), "OK", null, admin).andExpect(status().isOk());
        complete(insp, admin).andExpect(status().isOk());
        confirm(x[0]).andExpect(status().isOk());
        assertThat(shipped(x[1])).isEqualTo("5");
    }

    @Test
    void 품목군_양식도_걸리고_품목_양식이_먼저다() throws Exception {
        accounts.exec(comp, "update mst_item set item_class_id = (select class_id from mst_item_class where class_cd = 'EQ') where item_id = ?", fgLot);
        tpl("T-CLS", null, "EQ", new String[] {"품목군 항목", "N", "N"});
        long[] x = pickedLot();
        long insp = start(x[0]);
        assertThat(results(insp)).containsExactly("품목군 항목");
        postJson(B + "/insps/" + insp + "/cancel", "{\"rowVer\":" + inspVer(insp) + ",\"reason\":\"양식 바꿈\"}", admin).andExpect(status().isOk());
        tpl("T-ITEM", "FG-L", null, new String[] {"품목 항목 1", "N", "N"}, new String[] {"품목 항목 2", "N", "Y"});
        long insp2 = start(x[0]);
        assertThat(results(insp2)).containsExactly("품목 항목 1", "품목 항목 2");
        // 같은 대상 사용 중 양식 둘 → 400 DUPLICATE
        postJson(B + "/tpls", "{\"tplCd\":\"T-DUP\",\"tplNm\":\"중복\",\"itemCd\":\"FG-L\",\"lines\":[{\"checkText\":\"x\"}]}", admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("itemCd"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }

    @Test
    void 정책_기본값은_꺼짐이라_회사가_켜지_않으면_검수_없이_확정된다() throws Exception {
        assertThat(accounts.query(comp, "select default_value from sys_policy_def where policy_key = 'QMS_PRESHIP_REQUIRED'", String.class))
                .isEqualTo("N");
        tpl("T-1", "FG-L", null, new String[] {"외관", "N", "N"});
        accounts.exec(comp, "delete from sys_policy where policy_key = 'QMS_PRESHIP_REQUIRED'");
        long[] x = pickedLot();
        confirm(x[0]).andExpect(status().isOk());
        assertThat(shipped(x[1])).isEqualTo("5");
    }

    @Test
    void 정책을_끄면_검수_없이_확정된다() throws Exception {
        tpl("T-1", "FG-L", null, new String[] {"외관", "N", "N"});
        accounts.exec(comp, "update sys_policy set policy_value = 'N' where policy_key = 'QMS_PRESHIP_REQUIRED'");
        long[] x = pickedLot();
        confirm(x[0]).andExpect(status().isOk());
        assertThat(shipped(x[1])).isEqualTo("5");
    }

    // =================================================================== 판정·미결

    @Test
    void NG_는_미결이_되고_미결이_있으면_완료_확정_막힘_닫으면_이력이_남고_확정() throws Exception {
        tpl("T-1", "FG-L", null, new String[] {"외관", "N", "N"}, new String[] {"기능", "N", "N"});
        long[] x = pickedLot();
        long insp = start(x[0]);
        long r1 = resultId(insp, 0);
        long r2 = resultId(insp, 1);
        // NG 는 비고 필수
        judge(insp, r1, "NG", null, admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("remark"));
        judge(insp, r1, "NG", "긁힘", admin).andExpect(status().isOk()).andExpect(jsonPath("$.data.punchId").isNumber());
        judge(insp, r2, "OK", null, admin).andExpect(status().isOk());
        // 열린 미결이 있는 줄은 판정을 바꾸지 않는다
        judge(insp, r1, "OK", null, admin).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_PRESHIP_PUNCH_OPEN"));
        complete(insp, admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PRESHIP_PUNCH_OPEN"));
        confirm(x[0]).andExpect(status().isConflict());
        // 닫기 = 조치 + 재확인 OK
        long p = openPunch(insp);
        postJson(B + "/punches/" + p + "/close", "{\"rowVer\":" + punchVer(p) + ",\"actionText\":\"재도장\"}", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        assertThat(accounts.query(comp, "select judge from qms_preship_result where result_id = ?", String.class, r1)).isEqualTo("OK");
        // 같은 줄이 다시 NG → 새 미결(닫힌 미결은 이력으로 남음)
        judge(insp, r1, "NG", "다시 긁힘", admin).andExpect(status().isOk());
        long p2 = openPunch(insp);
        assertThat(p2).isNotEqualTo(p);
        postJson(B + "/punches/" + p2 + "/close", "{\"rowVer\":" + punchVer(p2) + ",\"actionText\":\"재도장 2회\"}", admin).andExpect(status().isOk());
        assertThat(split(accounts.query(comp, "select string_agg(status || ':' || issue_text || ':' || action_text || ':' || closed_by, '|' order by punch_id)"
                + " from qms_preship_punch where result_id = ?", String.class, r1)))
                .containsExactly("CLOSED:긁힘:재도장:admin1", "CLOSED:다시 긁힘:재도장 2회:admin1");
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'qms_preship_punch'", Integer.class)).isGreaterThanOrEqualTo(4);
        // 닫힌 미결은 직접 SQL 로도 되살리지 못한다
        assertThatThrownBy(() -> accounts.exec(comp, "update qms_preship_punch set status = 'OPEN', closed_at = null, closed_by = null, action_text = null where punch_id = ?", p))
                .hasMessageContaining("closed punch item is fixed");
        complete(insp, admin).andExpect(status().isOk());
        confirm(x[0]).andExpect(status().isOk());
    }

    @Test
    void 승인자가_사유와_함께_허용하면_미결이_열린_채_출하되고_출하_뒤_닫을_수_있다() throws Exception {
        tpl("T-1", "FG-L", null, new String[] {"외관", "N", "N"});
        long[] x = pickedLot();
        long insp = start(x[0]);
        judge(insp, resultId(insp, 0), "NG", "라벨 오타", admin).andExpect(status().isOk());
        long p = openPunch(insp);
        // SAVE 만 있는 사람은 허용 403
        RequestPostProcessor saver = saveOnly("saver1");
        postJson(B + "/punches/" + p + "/allow", "{\"rowVer\":" + punchVer(p) + ",\"reason\":\"고객 합의\"}", saver).andExpect(status().isForbidden());
        // 사유 없이 400
        postJson(B + "/punches/" + p + "/allow", "{\"rowVer\":" + punchVer(p) + ",\"reason\":\" \"}", admin).andExpect(status().isBadRequest());
        postJson(B + "/punches/" + p + "/allow", "{\"rowVer\":" + punchVer(p) + ",\"reason\":\"고객 합의 — 현지에서 라벨 교체\"}", admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shipAllowYn").value("Y")).andExpect(jsonPath("$.data.status").value("OPEN"));
        complete(insp, admin).andExpect(status().isOk());
        confirm(x[0]).andExpect(status().isOk());
        assertThat(shipped(x[1])).isEqualTo("5");
        // 출하 뒤 조치·재확인으로 닫는다(검수 DONE)
        postJson(B + "/punches/" + p + "/close", "{\"rowVer\":" + punchVer(p) + ",\"actionText\":\"현지 라벨 교체 완료\"}", admin).andExpect(status().isOk());
        // 허용은 되돌리지 않는다(직접 SQL)
        assertThatThrownBy(() -> accounts.exec(comp, "update qms_preship_punch set ship_allow_yn = 'N', allow_reason = null, allowed_at = null, allowed_by = null where punch_id = ?", p))
                .hasMessageContaining("ship allowance is fixed");
    }

    @Test
    void 사진_필수_항목은_사진_없이_OK_거부_올리면_OK() throws Exception {
        tpl("T-1", "FG-L", null, new String[] {"명판 사진", "Y", "N"}, new String[] {"선택 항목", "N", "N"});
        long[] x = pickedLot();
        long insp = start(x[0]);
        long r1 = resultId(insp, 0);
        judge(insp, r1, "OK", null, admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PHOTO_REQUIRED"));
        // NA 는 허용 안 된 항목이면 400
        judge(insp, resultId(insp, 1), "NA", null, admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("PRESHIP_NA_NOT_ALLOWED"));
        photo(r1, admin).andExpect(status().isOk());
        judge(insp, r1, "OK", null, admin).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.fileCnt").value(1));
        judge(insp, resultId(insp, 1), "OK", null, admin).andExpect(status().isOk());
        complete(insp, admin).andExpect(status().isOk());
        // 완료 뒤에는 사진이 잠긴다(열린 미결 없는 줄)
        photo(r1, admin).andExpect(status().is4xxClientError());
    }

    @Test
    void 같은_항목을_동시에_판정하면_하나만_들어가고_덮어쓰지_않는다() throws Exception {
        tpl("T-1", "FG-L", null, new String[] {"외관", "N", "N"});
        long[] x = pickedLot();
        long insp = start(x[0]);
        long r1 = resultId(insp, 0);
        int ver = resultVer(r1);
        String body1 = "{\"resultId\":" + r1 + ",\"rowVer\":" + ver + ",\"judge\":\"NG\",\"remark\":\"A 가 본 흠\"}";
        String body2 = "{\"resultId\":" + r1 + ",\"rowVer\":" + ver + ",\"judge\":\"OK\",\"remark\":null}";
        RequestPostProcessor other = user(comp, "admin2", true);
        postJson(B + "/insps/" + insp + "/judge", body1, admin).andExpect(status().isOk());
        postJson(B + "/insps/" + insp + "/judge", body2, other).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select judge || ':' || judged_by from qms_preship_result where result_id = ?", String.class, r1)).isEqualTo("NG:admin1");
        assertThat(accounts.query(comp, "select count(*) from qms_preship_punch where result_id = ?", Integer.class, r1)).isEqualTo(1);
    }

    @Test
    void 미판정이_있으면_완료_거부_검수자_이름_필수() throws Exception {
        tpl("T-1", "FG-L", null, new String[] {"외관", "N", "N"});
        long[] x = pickedLot();
        long insp = start(x[0]);
        complete(insp, admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PRESHIP_UNJUDGED"));
        judge(insp, resultId(insp, 0), "OK", null, admin).andExpect(status().isOk());
        postJson(B + "/insps/" + insp + "/complete", "{\"rowVer\":" + inspVer(insp) + ",\"inspectorNm\":\" \"}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("inspectorNm"));
        // 출하가 살아 있는데 같은 출하로 두 번 시작 → 409
        postJson(B + "/insps", "{\"shipId\":" + x[0] + "}", admin).andExpect(status().isConflict());
        // 완료 뒤 판정 409, 완료 취소(출하 전) 뒤 다시 판정
        complete(insp, admin).andExpect(status().isOk());
        judge(insp, resultId(insp, 0), "NG", "x", admin).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_PRESHIP_STATE"));
        postJson(B + "/insps/" + insp + "/reopen", "{\"rowVer\":" + inspVer(insp) + "}", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        judge(insp, resultId(insp, 0), "NG", "다시 봄", admin).andExpect(status().isOk());
    }

    // =================================================================== 회사·권한

    @Test
    void A사_계정으로_B사_검수는_404이고_직접_SQL_다른_회사_참조도_거부() throws Exception {
        tpl("T-1", "FG-L", null, new String[] {"외관", "N", "N"});
        long[] x = pickedLot();
        long insp = start(x[0]);
        long r1 = resultId(insp, 0);
        judge(insp, r1, "NG", "흠", admin).andExpect(status().isOk());
        long p = openPunch(insp);
        String other = accounts.newComp("PT");
        RequestPostProcessor b = user(other, "badmin", true);
        mvc.perform(get(B + "/ships/" + x[0]).with(b)).andExpect(status().isNotFound());
        postJson(B + "/insps", "{\"shipId\":" + x[0] + "}", b).andExpect(status().isNotFound());
        postJson(B + "/insps/" + insp + "/judge", "{\"resultId\":" + r1 + ",\"rowVer\":" + resultVer(r1) + ",\"judge\":\"OK\"}", b)
                .andExpect(status().isNotFound());
        postJson(B + "/insps/" + insp + "/complete", "{\"rowVer\":" + inspVer(insp) + ",\"inspectorNm\":\"x\"}", b).andExpect(status().isNotFound());
        postJson(B + "/punches/" + p + "/close", "{\"rowVer\":" + punchVer(p) + ",\"actionText\":\"x\"}", b).andExpect(status().isNotFound());
        postJson(B + "/punches/" + p + "/allow", "{\"rowVer\":" + punchVer(p) + ",\"reason\":\"x\"}", b).andExpect(status().isNotFound());
        mvc.perform(get(B + "/punches/grid").with(b)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        // B사 사진을 A사 판정 줄에 — 대상이 안 보여 잠김
        photo(r1, b).andExpect(status().is4xxClientError());
        assertThat(accounts.query(comp, "select status from qms_preship_punch where punch_id = ?", String.class, p)).isEqualTo("OPEN");
        assertThat(accounts.query(comp, "select judge from qms_preship_result where result_id = ?", String.class, r1)).isEqualTo("NG");
        // 직접 SQL: B사 범위에서 A사 출하로 검수 머리 → 트리거 23503(RLS 로 출하가 안 보임) 또는 RLS
        assertThatThrownBy(() -> accounts.exec(other, "insert into qms_preship_insp (comp_cd, insp_no, ship_id, created_by, updated_by) values (?, 'X-1', ?, 't', 't')",
                other, x[0])).hasMessageContaining("does not belong to company");
    }

    private RequestPostProcessor saveOnly(String id) {
        long u = accounts.newUser(comp, id, PW);
        // 관리자 권한그룹(QMS)과 다른 그룹 — 같은 그룹 코드를 쓰면 APPROVE 까지 받는다
        accounts.grant(comp, u, "QMS_SAVE", "qms-preship", "SEARCH", "SAVE");
        accounts.grant(comp, u, "QMS_SAVE", "qms-preship-punch", "SEARCH", "SAVE");
        return accounts.as(comp, u, id);
    }

    @Test
    void 권한이_없으면_403() throws Exception {
        tpl("T-1", "FG-L", null, new String[] {"외관", "N", "N"});
        long[] x = pickedLot();
        RequestPostProcessor none = user(comp, "nobody", false);
        mvc.perform(get(B + "/ships/grid").with(none)).andExpect(status().isForbidden());
        postJson(B + "/insps", "{\"shipId\":" + x[0] + "}", none).andExpect(status().isForbidden());
        postJson(B + "/tpls", "{\"tplCd\":\"T-X\",\"tplNm\":\"x\",\"itemCd\":\"FG-N\",\"lines\":[{\"checkText\":\"x\"}]}", none).andExpect(status().isForbidden());
        long insp = start(x[0]);
        judge(insp, resultId(insp, 0), "OK", null, none).andExpect(status().isForbidden());
        // SAVE 만 있으면 판정은 되고 완료는 403
        RequestPostProcessor saver = saveOnly("saver2");
        judge(insp, resultId(insp, 0), "OK", null, saver).andExpect(status().isOk());
        complete(insp, saver).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select status from qms_preship_insp where insp_id = ?", String.class, insp)).isEqualTo("OPEN");
    }

    // =================================================================== 양식·목록

    @Test
    void 양식_항목_한_벌_저장_빠진_항목은_사용_중지_시작한_검수는_사본_그대로() throws Exception {
        long t = tpl("T-1", "FG-L", null, new String[] {"외관", "N", "N"}, new String[] {"기능", "N", "N"});
        long[] x = pickedLot();
        long insp = start(x[0]);
        long keep = accounts.query(comp, "select tpl_line_id from qms_preship_tpl_line where tpl_id = ? order by seq limit 1", Long.class, t);
        int ver = accounts.query(comp, "select row_ver from qms_preship_tpl where tpl_id = ?", Integer.class, t);
        mvc.perform(put(B + "/tpls/" + t).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tplCd\":\"T-1\",\"tplNm\":\"고친 양식\",\"itemCd\":\"FG-L\",\"rowVer\":" + ver + ",\"lines\":[{\"tplLineId\":" + keep
                        + ",\"seq\":10,\"checkText\":\"외관(고침)\"},{\"seq\":30,\"checkText\":\"포장\"}]}"))
                .andExpect(status().isOk());
        assertThat(split(accounts.query(comp, "select string_agg(check_text || ':' || use_yn, '|' order by seq) from qms_preship_tpl_line where tpl_id = ?",
                String.class, t))).containsExactly("외관(고침):Y", "기능:N", "포장:Y");
        assertThat(results(insp)).containsExactly("외관", "기능");
        // 옛 rowVer 로 다시 저장 → 409
        mvc.perform(put(B + "/tpls/" + t).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tplCd\":\"T-1\",\"tplNm\":\"x\",\"itemCd\":\"FG-L\",\"rowVer\":" + ver + ",\"lines\":[{\"checkText\":\"x\"}]}"))
                .andExpect(status().isConflict());
        // 목록·엑셀 열
        mvc.perform(get(B + "/tpls/grid").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].lineCnt").value(2));
        mvc.perform(get(B + "/ships/grid").with(admin).param("from", "2026-11-01").param("to", "2026-11-30").param("onlyRequired", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].inspStatus").value("OPEN"))
                .andExpect(jsonPath("$.list[0].requiredYn").value("Y"));
        mvc.perform(post(B + "/ships/export").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"params\":{\"from\":\"2026-11-01\",\"to\":\"2026-11-30\"}}")).andExpect(status().isOk());
    }
}
