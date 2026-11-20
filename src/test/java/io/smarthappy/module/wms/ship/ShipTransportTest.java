package io.smarthappy.module.wms.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

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

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 출하 운송 정보·사진 (docs/58, 사용자 결정 W-7·W-8). 운송사·차량·송장 번호 저장(처음·고침·지움), 출하 확정 뒤에도 고침, 머리 rowVer 와
 * 따로(운송 저장 뒤에도 피킹이 그 전 rowVer 로 통과), 운송 rowVer 충돌, 취소 지시 409, 입력 검사 400, 사진 첨부(올리기·목록·잠금),
 * 권한 없음 403, 직접 SQL(빈 값·다른 회사 지시 id), A사로 B사.
 */
@AutoConfigureMockMvc
class ShipTransportTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String S = "/api/wms/ships";
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long stg;
    long fg;
    int seq;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("ST");
        admin = user(comp, "admin1", "SEARCH", "SAVE", "APPROVE");
        seed(comp);
    }

    private RequestPostProcessor user(String c, String id, String... wmsActions) {
        long u = accounts.newUser(c, id, PW);
        accounts.grant(c, u, "SALES", "sal-order", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS_" + id.toUpperCase(java.util.Locale.ROOT), "wms-ship", wmsActions);    // 사용자마다 역할 — 같은 역할이면 권한이 합쳐진다
        return accounts.as(c, u, id);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-100', 'FG 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't')""", c);
        long wh = node(c, null, "WH", "FG-WH");
        a1 = node(c, wh, "LOC", "A-01");
        stg = node(c, wh, "LOC", "STG-01");
        accounts.exec(c, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, c);
        fg = accounts.query(c, "select item_id from mst_item where item_cd = 'FG-100'", Long.class);
        accounts.exec(c, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 30, 'EA', 'MANUAL', now(), 'KEY', 't')""", c, fg, a1);
    }

    private long node(String c, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, parent, type, cd, cd);
    }

    private ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String req() {
        return "trans-" + (++seq) + "-" + System.nanoTime();
    }

    /** 수주 C-001 FG-100 10 → 출하 지시(DRAFT) */
    private long newShip(RequestPostProcessor who, String c) throws Exception {
        MvcResult m = postJson(who, "/api/sales/orders", "{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"FG-100\",\"qty\":\"10\",\"dueDate\":\"2026-11-30\"}]}")
                .andExpect(status().isOk()).andReturn();
        long order = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        long ol = accounts.query(c, "select line_id from sal_order_line where order_id = ? and line_no = 10", Long.class, order);
        long stage = accounts.query(c, "select node_id from mst_equip_hier where node_cd = 'STG-01'", Long.class);
        MvcResult s = postJson(who, S, "{\"plantCd\":\"P1\",\"custCd\":\"C-001\",\"stageNodeId\":" + stage
                + ",\"planDate\":\"2026-11-20\",\"lines\":[{\"orderLineId\":" + ol + ",\"qty\":10}]}").andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(s.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
    }

    private int headVer(long id) {
        return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
    }

    private ResultActions transport(RequestPostProcessor who, long id, Integer rowVer, String carrier, String vehicle, String tracking)
            throws Exception {
        return postJson(who, S + "/" + id + "/transport", "{\"rowVer\":" + rowVer + ",\"carrierNm\":" + js(carrier) + ",\"vehicleNo\":" + js(vehicle)
                + ",\"trackingNo\":" + js(tracking) + "}");
    }

    private static String js(String v) {
        return v == null ? "null" : "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\u0007", "\\u0007") + "\"";
    }

    private ResultActions upload(RequestPostProcessor who, String targetId) throws Exception {
        return mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "상차.pdf", "application/pdf", PDF))
                .param("targetType", "wms-ship").param("targetId", targetId).with(csrf()).with(who));
    }

    private ResultActions pick(long id, int rowVer, String qty) throws Exception {
        long line = accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ? and line_no = 1", Long.class, id);
        return postJson(admin, S + "/" + id + "/pick", "{\"rowVer\":" + rowVer + ",\"clientReqId\":\"" + req() + "\",\"picks\":[{\"shipLineId\":" + line
                + ",\"fromNodeId\":" + a1 + ",\"lotNo\":null,\"qty\":" + qty + "}]}");
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 운송_정보를_처음_저장하고_고치고_지우며_머리_rowVer_는_그대로라_피킹이_튕기지_않는다() throws Exception {
        long id = newShip(admin, comp);
        int head = headVer(id);
        transport(admin, id, null, "  한빛운송 ", "12가3456", "TRK-0001").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.head.carrierNm").value("한빛운송"))
                .andExpect(jsonPath("$.data.head.vehicleNo").value("12가3456"))
                .andExpect(jsonPath("$.data.head.trackingNo").value("TRK-0001"))
                .andExpect(jsonPath("$.data.head.transportRowVer").value(0))
                .andExpect(jsonPath("$.data.head.transportBy").value("admin1"))
                .andExpect(jsonPath("$.data.head.rowVer").value(head));
        assertThat(headVer(id)).isEqualTo(head);
        // 머리 rowVer 는 그대로 — 운송 저장 전에 읽은 rowVer 로 피킹이 통과한다(PDA 가 튕기지 않음)
        pick(id, head, "4").andExpect(status().isOk()).andExpect(jsonPath("$.data.head.status").value("PICKING"));
        // 고침(rowVer 0) · 빈 칸은 지움
        transport(admin, id, 0, "한빛운송", "  ", "TRK-0002").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.head.vehicleNo").doesNotExist())
                .andExpect(jsonPath("$.data.head.trackingNo").value("TRK-0002"))
                .andExpect(jsonPath("$.data.head.transportRowVer").value(1));
        // 목록 그리드에도
        mvc.perform(get(S + "/grid").param("plantCd", "P1").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].trackingNo").value("TRK-0002")).andExpect(jsonPath("$.list[0].carrierNm").value("한빛운송"));
        mvc.perform(get(S + "/columns").with(admin)).andExpect(jsonPath("$.list[?(@.field == 'trackingNo')]").exists());
        // 변경 감사
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'wms_ship_transport'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void 출하_확정_뒤에도_송장_번호를_적을_수_있고_사진도_올린다() throws Exception {
        long id = newShip(admin, comp);
        pick(id, headVer(id), "10").andExpect(status().isOk());
        postJson(admin, S + "/" + id + "/confirm", "{\"rowVer\":" + headVer(id) + ",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from wms_ship where ship_id = ?", String.class, id)).isEqualTo("SHIPPED");
        transport(admin, id, null, "한빛운송", null, "TRK-9").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.head.status").value("SHIPPED")).andExpect(jsonPath("$.data.head.trackingNo").value("TRK-9"));
        upload(admin, String.valueOf(id)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/files").param("targetType", "wms-ship").param("targetId", String.valueOf(id)).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.list.length()").value(1)).andExpect(jsonPath("$.list[0].name").value("상차.pdf"));
    }

    // =====================================================================
    // 거부 · 이상 사용
    // =====================================================================

    @Test
    void 운송_rowVer_가_낡으면_409_취소된_지시는_운송도_사진도_잠긴다() throws Exception {
        long id = newShip(admin, comp);
        transport(admin, id, null, "A운송", null, null).andExpect(status().isOk());
        // 다른 사람이 먼저 처음 저장 — 나는 행이 없다고 보고(null) 보냄 → 409, 낡은 번호도 409
        transport(admin, id, null, "B운송", null, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        transport(admin, id, 5, "B운송", null, null).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select carrier_nm from wms_ship_transport where ship_id = ?", String.class, id)).isEqualTo("A운송");
        // 사진은 열린 지시에 올리고, 지시를 취소하면 올리기·지우기가 잠긴다
        MvcResult up = upload(admin, String.valueOf(id)).andExpect(status().isOk()).andReturn();
        long fileId = ((Number) JsonPath.read(up.getResponse().getContentAsString(), "$.data.fileId")).longValue();
        postJson(admin, S + "/" + id + "/cancel", "{\"rowVer\":" + headVer(id) + ",\"reason\":\"취소\"}").andExpect(status().isOk());
        transport(admin, id, 0, "C운송", null, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_STATE"));
        upload(admin, String.valueOf(id)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(post("/api/platform/files/" + fileId + "/delete").with(csrf()).with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        // 취소된 지시도 읽기는 된다(증빙 보존)
        mvc.perform(get("/api/platform/files").param("targetType", "wms-ship").param("targetId", String.valueOf(id)).with(admin))
                .andExpect(jsonPath("$.list.length()").value(1));
    }

    @Test
    void 입력_검사_길이_제어문자_없는_지시_권한() throws Exception {
        long id = newShip(admin, comp);
        transport(admin, id, null, "가".repeat(101), null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("carrierNm"));
        transport(admin, id, null, null, "1".repeat(31), "T".repeat(61)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        transport(admin, id, null, null, null, "TRK\u0007").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("trackingNo"));
        postJson(admin, S + "/" + id + "/transport", "").andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select count(*) from wms_ship_transport", Integer.class)).isZero();
        transport(admin, 999_999_999L, null, "A운송", null, null).andExpect(status().isNotFound());
        // 없는 지시·숫자 아닌 대상으로 사진 → 잠김
        upload(admin, "999999999").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        upload(admin, "abc").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        // 조회 권한만 있으면 저장·올리기 403(화면에서 숨긴 버튼을 API 로 직접)
        RequestPostProcessor viewer = user(comp, "viewer1", "SEARCH");
        transport(viewer, id, null, "A운송", null, null).andExpect(status().isForbidden());
        upload(viewer, String.valueOf(id)).andExpect(status().isForbidden());
    }

    @Test
    void 직접_SQL_빈_값과_다른_회사_지시_id_는_DB_가_막는다() throws Exception {
        long id = newShip(admin, comp);
        assertThat(constraint(() -> accounts.exec(comp, """
                insert into wms_ship_transport (ship_id, comp_cd, carrier_nm, created_by, updated_by) values (?, ?, '  ', 't', 't')""", id, comp)))
                .isEqualTo("ck_wms_ship_transport_carrier");
        // B사 세션이 A사 지시 id 로 자기 회사 행 → 회사+id FK 가 막는다(A사 행을 PK 로 선점 못 함)
        String b = accounts.newComp("SU");
        Throwable t = catchThrowable(() -> accounts.exec(b, """
                insert into wms_ship_transport (ship_id, comp_cd, carrier_nm, created_by, updated_by) values (?, ?, 'X', 't', 't')""", id, b));
        assertThat(PgErrors.constraint(t)).hasValue("fk_wms_ship_transport_ship");
        // 지우기는 앱 계정에 권한 없음
        transport(admin, id, null, "A운송", null, null).andExpect(status().isOk());
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from wms_ship_transport where ship_id = ?", id))).isNotNull();
    }

    @Test
    void A사로_B사_지시에_운송_정보와_사진을_넣을_수_없다() throws Exception {
        long id = newShip(admin, comp);
        transport(admin, id, null, "A운송", null, "TRK-A").andExpect(status().isOk());
        upload(admin, String.valueOf(id)).andExpect(status().isOk());
        String b = accounts.newComp("SV");
        RequestPostProcessor other = user(b, "admin1", "SEARCH", "SAVE", "APPROVE");
        transport(other, id, 0, "B운송", null, "TRK-B").andExpect(status().isNotFound());
        upload(other, String.valueOf(id)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(get("/api/platform/files").param("targetType", "wms-ship").param("targetId", String.valueOf(id)).with(other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.list.length()").value(0));
        assertThat(accounts.query(comp, "select carrier_nm || '/' || tracking_no from wms_ship_transport where ship_id = ?", String.class, id))
                .isEqualTo("A운송/TRK-A");
        assertThat(accounts.query(b, "select count(*) from wms_ship_transport", Integer.class)).isZero();
    }

    private static String constraint(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isNotNull();
        return PgErrors.constraint(t).orElse(t.getMessage());
    }
}
