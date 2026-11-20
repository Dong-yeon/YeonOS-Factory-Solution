package io.smarthappy.module.wms.loc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 로케이션 속성·스캔 확인 (P4 W1, docs/30) — 정상 + 이상 사용(A~K) + A사로 B사 + 직접 SQL(트리거·권한).
 * 노드는 장비 계층 화면 대신 직접 넣는다(이 화면은 노드를 만들지 않는다).
 */
@AutoConfigureMockMvc
class LocAttrTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String API = "/api/wms/loc";
    private static final String GS = "\u001d";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor admin;
    long area;
    long wh;
    long rack;
    long bin1;
    long bin2;
    long sideWh;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("WL");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "WMS", "wms-loc", "SEARCH", "SAVE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        area = node(comp, "P1", null, "AREA", "BODY");
        node(comp, "P1", area, "LINE", "L1");
        sideWh = node(comp, "P1", area, "WH", "LINESIDE");        // 구역 아래 라인사이드 창고
        wh = node(comp, "P1", null, "WH", "RM-WH");
        rack = node(comp, "P1", wh, "LOC", "A-01");
        bin1 = node(comp, "P1", rack, "LOC", "A-01-01");
        bin2 = node(comp, "P1", rack, "LOC", "A-01-02");
    }

    long node(String c, String plant, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, plant, parent, type, cd, cd + " 이름");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions save(long id, String body) throws Exception {
        return mvc.perform(json(put(API + "/" + id), body).with(admin));
    }

    private ResultActions scan(String plant, String text) throws Exception {
        String t = text == null ? "null" : "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace(GS, "\\u001d")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
        return mvc.perform(json(post(API + "/scan"), "{\"plantCd\":\"" + plant + "\",\"text\":" + t + "}").with(admin));
    }

    private String attr(long id, String col) {
        return accounts.query(comp, "select " + col + "::text from wms_loc_attr where node_id = ?", String.class, id);
    }

    private int attrCount() {
        return accounts.query(comp, "select count(*) from wms_loc_attr", Integer.class);
    }

    private String sqlError(String c, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(c, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 트리는_창고_로케이션만_구역아래_창고도_속성없으면_기본값() throws Exception {
        MvcResult r = mvc.perform(get(API).param("plantCd", "P1").with(admin)).andExpect(status().isOk()).andReturn();
        String b = r.getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(b, "$.data[*].path"))
                .containsExactly("P1/BODY/LINESIDE", "P1/RM-WH", "P1/RM-WH/A-01", "P1/RM-WH/A-01/A-01-01", "P1/RM-WH/A-01/A-01-02");
        assertThat(JsonPath.<List<Integer>>read(b, "$.data[*].lvl")).containsExactly(1, 1, 2, 3, 3);
        assertThat(JsonPath.<List<Boolean>>read(b, "$.data[*].attrSet")).containsOnly(false);
        assertThat(JsonPath.<List<String>>read(b, "$.data[*].locUsage")).containsOnly("STORAGE");
        assertThat(JsonPath.<List<Object>>read(b, "$.data[*].rowVer")).containsOnly((Object) null);
        mvc.perform(get(API).param("plantCd", "P2").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(API + "/plants").with(admin)).andExpect(jsonPath("$.data[*].plantCd").value(containsInAnyOrder("P1", "P2")));
        mvc.perform(get(API + "/columns").with(admin)).andExpect(status().isOk());
    }

    @Test
    void 처음_저장하면_만들어지고_다음은_rowVer_로_수정() throws Exception {
        save(rack, "{\"locUsage\":\"QC\",\"stockYn\":\"N\",\"pickSeq\":5,\"mixItemYn\":\"N\",\"mixLotYn\":\"N\","
                + "\"defaultStatus\":\"QC\",\"remark\":\"검사 랙\",\"rowVer\":null}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.childrenApplied").value(0));
        assertThat(attr(rack, "loc_usage")).isEqualTo("QC");
        assertThat(attr(rack, "stock_yn")).isEqualTo("N");
        assertThat(attr(rack, "row_ver")).isEqualTo("0");
        save(rack, "{\"locUsage\":\"STORAGE\",\"rowVer\":0}").andExpect(status().isOk());
        assertThat(attr(rack, "loc_usage")).isEqualTo("STORAGE");
        assertThat(attr(rack, "default_status")).isNull();       // 비우면 지정 안 함
        assertThat(attr(rack, "stock_yn")).isEqualTo("Y");       // 안 보내면 기본값 — 화면은 항상 전부 보낸다
        assertThat(attr(rack, "row_ver")).isEqualTo("1");
        // 변경 감사가 남는다
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'wms_loc_attr'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void 하위에도_적용은_용도_혼적_기본상태만_사용중지_노드는_새로_붙이지_않음() throws Exception {
        save(bin1, "{\"stockYn\":\"Y\",\"pickSeq\":7,\"remark\":\"그대로\",\"rowVer\":null}").andExpect(status().isOk());
        long bin3 = node(comp, "P1", rack, "LOC", "A-01-03");
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", bin3);
        save(wh, "{\"locUsage\":\"RECEIVING\",\"stockYn\":\"N\",\"mixLotYn\":\"N\",\"defaultStatus\":\"QC\",\"rowVer\":null,"
                + "\"applyToChildren\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.childrenApplied").value(3));   // rack, bin1, bin2
        for (long id : new long[] {rack, bin1, bin2}) {
            assertThat(attr(id, "loc_usage")).isEqualTo("RECEIVING");
            assertThat(attr(id, "mix_lot_yn")).isEqualTo("N");
            assertThat(attr(id, "default_status")).isEqualTo("QC");
        }
        assertThat(attr(rack, "stock_yn")).isEqualTo("Y");         // 재고 둠은 하위에 안 퍼진다
        assertThat(attr(bin1, "pick_seq")).isEqualTo("7");
        assertThat(attr(bin1, "remark")).isEqualTo("그대로");
        assertThat(accounts.query(comp, "select count(*) from wms_loc_attr where node_id = ?", Integer.class, bin3)).isZero();
        // 같은 값으로 다시 적용하면 바뀐 곳 0 (row_ver·감사를 헛되이 올리지 않는다)
        save(wh, "{\"locUsage\":\"RECEIVING\",\"stockYn\":\"N\",\"mixLotYn\":\"N\",\"defaultStatus\":\"QC\",\"rowVer\":0,"
                + "\"applyToChildren\":true}").andExpect(jsonPath("$.data.childrenApplied").value(0));
    }

    @Test
    void 피킹_순서는_트리_순서대로_간격_10() throws Exception {
        long bin0 = node(comp, "P1", rack, "LOC", "A-01-00");
        mvc.perform(json(post(API + "/" + wh + "/renumber-pick"), "{}").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(4));
        assertThat(attr(rack, "pick_seq")).isEqualTo("10");
        assertThat(attr(bin0, "pick_seq")).isEqualTo("20");
        assertThat(attr(bin1, "pick_seq")).isEqualTo("30");
        assertThat(attr(bin2, "pick_seq")).isEqualTo("40");
        assertThat(accounts.query(comp, "select count(*) from wms_loc_attr where node_id = ?", Integer.class, wh)).isZero();
        mvc.perform(json(post(API + "/" + wh + "/renumber-pick"), "{\"step\":10}").with(admin)).andExpect(jsonPath("$.data").value(0));
        mvc.perform(json(post(API + "/" + rack + "/renumber-pick"), "{\"step\":5}").with(admin)).andExpect(jsonPath("$.data").value(3));
        assertThat(attr(bin2, "pick_seq")).isEqualTo("15");
    }

    @Test
    void 스캔_확인_로케이션_품목_GS1() throws Exception {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RM-100', '강판', 'RM', 'BUY', 'EA', 't', 't')""", comp);
        scan("P1", "LOC:P1:A-01-02").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true)).andExpect(jsonPath("$.data.type").value("LOC"))
                .andExpect(jsonPath("$.data.nodeId").value(bin2)).andExpect(jsonPath("$.data.path").value("P1/RM-WH/A-01/A-01-02"));
        scan("P1", "a-01-01\r\n").andExpect(jsonPath("$.data.nodeId").value(bin1)).andExpect(jsonPath("$.data.bare").value(true));
        scan("P1", "ITEM:rm-100").andExpect(jsonPath("$.data.ok").value(true)).andExpect(jsonPath("$.data.itemNm").value("강판"));
        scan("P1", "]C1" + "0109501101530003" + "17271231" + "10LOT-7" + GS + "3712")
                .andExpect(jsonPath("$.data.type").value("GS1")).andExpect(jsonPath("$.data.gtin").value("09501101530003"))
                .andExpect(jsonPath("$.data.batch").value("LOT-7")).andExpect(jsonPath("$.data.expiry").value("2027-12-31"))
                .andExpect(jsonPath("$.data.qty").value(12));
        scan("P1", "LOT:R2610-1").andExpect(jsonPath("$.data.type").value("LOT")).andExpect(jsonPath("$.data.ok").value(true));
    }

    @Test
    void 엑셀() throws Exception {
        save(rack, "{\"locUsage\":\"QC\",\"rowVer\":null}").andExpect(status().isOk());
        mvc.perform(json(post(API + "/export"), "{\"plantCd\":\"P1\"}").with(admin)).andExpect(status().isOk());
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void B_입력값_한번에_모아서_칸오류() throws Exception {
        save(rack, "{\"locUsage\":\"XX\",\"stockYn\":\"y\",\"pickSeq\":1000000,\"mixItemYn\":\"1\",\"mixLotYn\":\"\","
                + "\"defaultStatus\":\"GOOD\",\"remark\":\"" + "가".repeat(501) + "\",\"rowVer\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("locUsage", "stockYn", "pickSeq", "mixItemYn",
                        "defaultStatus", "remark")));
        save(rack, "{\"pickSeq\":-1,\"rowVer\":null}").andExpect(jsonPath("$.errors[0].field").value("pickSeq"));
        save(rack, "{\"pickSeq\":\"abc\",\"rowVer\":null}").andExpect(status().isBadRequest());     // 숫자 칸에 글자
        mvc.perform(json(put(API + "/" + rack), "").with(admin)).andExpect(status().isBadRequest());
        // 비고의 태그는 글자 그대로 저장(화면은 textContent)
        save(rack, "{\"remark\":\"<script>alert(1)</script>\",\"rowVer\":null}").andExpect(status().isOk());
        assertThat(attr(rack, "remark")).isEqualTo("<script>alert(1)</script>");
        assertThat(attrCount()).isEqualTo(1);
    }

    @Test
    void F_구역_라인_노드_없는_노드_사용중지_노드에_처음_붙이기() throws Exception {
        save(area, "{\"rowVer\":null}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_STORAGE_NODE"));
        save(999999999L, "{\"rowVer\":null}").andExpect(status().isNotFound());
        mvc.perform(json(post(API + "/" + area + "/renumber-pick"), "{}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_STORAGE_NODE"));
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", bin2);
        save(bin2, "{\"rowVer\":null}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 이미 속성이 있던 노드는 사용 중지된 뒤에도 고칠 수 있다(새 참조만 차단)
        save(bin1, "{\"rowVer\":null}").andExpect(status().isOk());
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", bin1);
        save(bin1, "{\"locUsage\":\"SCRAP\",\"rowVer\":0}").andExpect(status().isOk());
        // 직접 SQL 도 트리거가 막는다
        long line = accounts.query(comp, "select node_id from mst_equip_hier where node_cd = 'L1'", Long.class);
        assertThat(sqlError(comp, "insert into wms_loc_attr (node_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')", line, comp))
                .isEqualTo("23514 ck_wms_loc_attr_node_type");
        assertThat(sqlError(comp, "insert into wms_loc_attr (node_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')", bin2, comp))
                .isEqualTo("23514 ck_wms_loc_attr_node_use");
        assertThat(sqlError(comp, "update wms_loc_attr set node_id = ? where node_id = ?", rack, bin1))
                .isEqualTo("55000 ck_wms_loc_attr_node_fixed");
        assertThat(sqlError(comp, "delete from wms_loc_attr where node_id = ?", bin1)).startsWith("42501");
        assertThat(sqlError(comp, "update wms_loc_attr set loc_usage = 'DOCK' where node_id = ?", bin1)).isEqualTo("23514 ck_wms_loc_attr_usage");
    }

    @Test
    void C_오래된_rowVer_빠진_rowVer_새것에_rowVer_는_409() throws Exception {
        save(rack, "{\"rowVer\":null}").andExpect(status().isOk());
        save(rack, "{\"locUsage\":\"QC\",\"rowVer\":null}").andExpect(status().isConflict());   // 이미 있는데 새로 만들기
        save(rack, "{\"locUsage\":\"QC\",\"rowVer\":5}").andExpect(status().isConflict());
        save(bin1, "{\"rowVer\":0}").andExpect(status().isConflict());                          // 없는데 수정
        assertThat(attr(rack, "loc_usage")).isEqualTo("STORAGE");
        assertThat(attrCount()).isEqualTo(1);
    }

    @Test
    void C_두_사람이_같은_노드를_동시에_처음_저장하면_뒤쪽은_409() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            jdbc.update("insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'QC', 't', 't')",
                    rack, comp);
            inserted.countDown();
            sleep(1500);
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        MvcResult r = mvc.perform(json(put(API + "/" + rack), "{\"locUsage\":\"SCRAP\",\"rowVer\":null}").with(admin)).andReturn();
        first.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(attr(rack, "loc_usage")).isEqualTo("QC");
    }

    @Test
    void D_피킹순서_간격_범위() throws Exception {
        mvc.perform(json(post(API + "/" + wh + "/renumber-pick"), "{\"step\":0}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("step"));
        mvc.perform(json(post(API + "/" + wh + "/renumber-pick"), "{\"step\":1001}").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(post(API + "/" + bin1 + "/renumber-pick"), "{}").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(0));                  // 하위 없음
        assertThat(attrCount()).isZero();
    }

    @Test
    void E_스캔_이상값() throws Exception {
        scan("P1", "LOC:P2:A-01").andExpect(status().isOk()).andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.reason").value("OTHER_PLANT"));
        scan("P1", "LOC:P1:NOPE").andExpect(jsonPath("$.data.reason").value("NOT_FOUND"));
        scan("P1", "BODY").andExpect(jsonPath("$.data.reason").value("NOT_STORAGE_NODE"));     // 구역 코드
        scan("P1", "ITEM:NONE-1").andExpect(jsonPath("$.data.reason").value("NOT_FOUND"));
        scan("P1", "x".repeat(10_000)).andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("TOO_LONG"));
        scan("P1", null).andExpect(jsonPath("$.data.reason").value("EMPTY"));
        scan("P1", "]C1" + "0109501101530004").andExpect(jsonPath("$.data.reason").value("GS1_CHECK_DIGIT"))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("01")));
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", bin2);
        scan("P1", "LOC:P1:A-01-02").andExpect(jsonPath("$.data.ok").value(false)).andExpect(jsonPath("$.data.reason").value("INACTIVE"));
        scan("", "A-01").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        scan("P9", "A-01").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 정책: 접두어 없는 코드 거부
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'WMS_SCAN_BARE_AS', 'NONE', 't', 't')""", comp);
        scan("P1", "A-01").andExpect(jsonPath("$.data.reason").value("BARE_NOT_ALLOWED"));
        scan("P1", "LOC:P1:A-01").andExpect(jsonPath("$.data.ok").value(true));
    }

    @Test
    void A사로_B사_노드_공장_보기_고치기_스캔_직접SQL() throws Exception {
        String other = accounts.newComp("WB");
        accounts.addPlant(other, "P1", "B사 1공장");
        accounts.addPlant(other, "BP", "B사 전용");
        long bWh = node(other, "P1", null, "WH", "B-WH");
        long bLoc = node(other, "P1", bWh, "LOC", "SECRET-01");
        // A사 사용자로 B사 노드 id → 없는 것과 같다
        save(bLoc, "{\"locUsage\":\"SCRAP\",\"rowVer\":null}").andExpect(status().isNotFound());
        mvc.perform(json(post(API + "/" + bWh + "/renumber-pick"), "{}").with(admin)).andExpect(status().isNotFound());
        // 같은 공장 코드 P1 이어도 A사 트리에 B사 노드가 안 보인다, B사 전용 공장은 없는 공장
        MvcResult r = mvc.perform(get(API).param("plantCd", "P1").with(admin)).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("SECRET-01").doesNotContain("B-WH");
        mvc.perform(get(API).param("plantCd", "BP").with(admin)).andExpect(status().isBadRequest());
        scan("P1", "LOC:P1:SECRET-01").andExpect(jsonPath("$.data.reason").value("NOT_FOUND"));
        scan("BP", "A-01").andExpect(status().isBadRequest());
        // 직접 SQL: A사 범위에서 B사 노드에 속성 붙이기 / B사 회사 코드로 넣기
        assertThat(sqlError(comp, "insert into wms_loc_attr (node_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')", bLoc, comp))
                .isEqualTo("23503 fk_wms_loc_attr_node_comp");
        assertThat(sqlError(comp, "insert into wms_loc_attr (node_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')", bLoc, other))
                .isEqualTo("23503 fk_wms_loc_attr_node_comp");   // BEFORE 트리거가 RLS WITH CHECK 보다 먼저 — B사 노드는 안 보인다
        assertThat(sqlError(comp, "insert into wms_loc_attr (node_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')", rack, other))
                .isEqualTo("23503 fk_wms_loc_attr_node_comp");   // 자기 노드에 회사 코드만 B사로 — 노드 회사와 달라 트리거가 먼저 거부
        // B사가 자기 노드에 붙인 속성을 A사는 못 본다·못 고친다
        accounts.exec(other, "insert into wms_loc_attr (node_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')", bLoc, other);
        assertThat(accounts.exec(comp, "update wms_loc_attr set loc_usage = 'SCRAP' where node_id = ?", bLoc)).isZero();
        assertThat(attrCount()).isZero();
        assertThat(accounts.query(other, "select loc_usage from wms_loc_attr where node_id = ?", String.class, bLoc)).isEqualTo("STORAGE");
    }

    @Test
    void 권한_조회만_있으면_저장_불가_권한_없으면_스캔도_불가() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "wms-loc", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        mvc.perform(get(API).param("plantCd", "P1").with(v)).andExpect(status().isOk());
        mvc.perform(json(put(API + "/" + rack), "{\"rowVer\":null}").with(v)).andExpect(status().isForbidden());
        mvc.perform(json(post(API + "/" + wh + "/renumber-pick"), "{}").with(v)).andExpect(status().isForbidden());
        mvc.perform(json(post(API + "/export"), "{\"plantCd\":\"P1\"}").with(v)).andExpect(status().isForbidden());
        long none = accounts.newUser(comp, "none", PW);
        RequestPostProcessor n = accounts.as(comp, none, "none");
        mvc.perform(json(post(API + "/scan"), "{\"plantCd\":\"P1\",\"text\":\"A-01\"}").with(n)).andExpect(status().isForbidden());
        mvc.perform(get(API).param("plantCd", "P1")).andExpect(status().isUnauthorized());
        assertThat(attrCount()).isZero();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
