package io.smarthappy.module.master.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

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
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 공정·라우팅 (P1 5단계, docs/20) — 공정 마스터, 라우팅 한 벌 저장(id 맞춤·순번 교환·바뀐 행만), 확정 조건, 불변·자동 마감,
 * 작업장 규칙(같은 공장 LINE·CELL, 확정 라우팅 작업장 사용 중지 거부), BOM 연결 점검, 직접 SQL, A/B사, 권한.
 */
@AutoConfigureMockMvc
class RoutingTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String R = "/api/master/routings";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;
    long line;
    long cell;
    long wh;
    long p2line;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("RT");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-proc", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-routing", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        accounts.grant(comp, userId, "MASTER", "mst-equip-hier", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        long area = node("P1", null, "AREA", "BODY");
        line = node("P1", area, "LINE", "WL1");
        cell = node("P1", line, "CELL", "WL1-C1");
        wh = node("P1", null, "WH", "WH1");
        long a2 = node("P2", null, "AREA", "A2");
        p2line = node("P2", a2, "LINE", "L9");
        item("FG1");
        item("RM1");
        proc("PRESS", "PRESS", "N");
        proc("WELD", "WELD", "N");
        proc("PLATING", "OTHER", "Y");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private long node(String plant, Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, plant, parent, type, cd, cd);
    }

    private void item(String cd) {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", comp, cd, cd + " 품목");
    }

    private long proc(String cd, String cat, String outsrc) throws Exception {
        return id(postJson("/api/master/procs", "{\"procCd\":\"" + cd + "\",\"procNm\":\"" + cd + " 공정\",\"procCat\":\"" + cat
                + "\",\"outsrcYn\":\"" + outsrc + "\"}"));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long routing(String itemCd, String plant) throws Exception {
        return id(postJson(R, "{\"itemCd\":\"" + itemCd + "\",\"plantCd\":\"" + plant + "\"}"));
    }

    private int rowVer(long routingId) throws Exception {
        MvcResult m = mvc.perform(get(R + "/" + routingId).with(admin)).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.routing.rowVer");
    }

    private ResultActions save(long routingId, String steps) throws Exception {
        return putJson(R + "/" + routingId + "/steps?rowVer=" + rowVer(routingId), "{\"steps\":[" + steps + "]}");
    }

    private static String step(String key, Long stepId, int seq, String proc, Long node, String ct, String params) {
        return "{\"rowKey\":\"" + key + "\",\"stepId\":" + stepId + ",\"opSeq\":" + seq + ",\"procCd\":\"" + proc + "\",\"nodeId\":"
                + node + ",\"stdCtSec\":" + (ct == null ? "null" : "\"" + ct + "\"") + ",\"params\":[" + params + "]}";
    }

    private static String param(String key, Long paramId, String cd, String lsl, String target, String usl) {
        return "{\"rowKey\":\"" + key + "\",\"paramId\":" + paramId + ",\"paramCd\":\"" + cd + "\",\"paramNm\":\"" + cd
                + "\",\"unitNm\":\"kA\",\"lsl\":" + q(lsl) + ",\"targetVal\":" + q(target) + ",\"usl\":" + q(usl) + "}";
    }

    private static String q(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    private long stepId(long routingId, int opSeq) {
        return accounts.query(comp, "select step_id from mst_routing_step where routing_id = ? and op_seq = ?", Long.class,
                routingId, opSeq);
    }

    private ResultActions action(long routingId, String act) throws Exception {
        return postJson(R + "/" + routingId + "/" + act, "{\"rowVer\":" + rowVer(routingId) + "}");
    }

    // ---------- 공정 ----------

    @Test
    void 공정_등록_중복_형식_코드고정_외주는_확정라우팅이_쓰면_못바꾼다() throws Exception {
        postJson("/api/master/procs", "{\"procCd\":\"press\",\"procNm\":\"x\",\"procCat\":\"PRESS\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson("/api/master/procs", "{\"procCd\":\"a b\",\"procNm\":\"\",\"procCat\":\"TOOL\",\"outsrcYn\":\"X\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(4));
        long weld = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'WELD'", Long.class);
        putJson("/api/master/procs/" + weld, "{\"procCd\":\"WELD2\",\"procNm\":\"용접\",\"procCat\":\"WELD\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "WELD", line, "45", "")).andExpect(status().isOk());
        action(r, "release").andExpect(status().isOk());
        putJson("/api/master/procs/" + weld, "{\"procNm\":\"용접\",\"procCat\":\"WELD\",\"outsrcYn\":\"Y\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("OUTSRC_IN_USE"));
        // 사용 중지는 허용(새 참조만 막힘), 이름 변경도 허용
        putJson("/api/master/procs/" + weld, "{\"procNm\":\"용접(스폿)\",\"procCat\":\"WELD\",\"useYn\":\"N\",\"rowVer\":0}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/procs").with(admin)).andExpect(jsonPath("$.data[?(@.procCd=='WELD')].routingCnt").value(hasItem(1)));
        mvc.perform(get(R + "/procs").with(admin)).andExpect(jsonPath("$.data[*].procCd").value(org.hamcrest.Matchers.not(hasItem("WELD"))));
    }

    // ---------- 머리·구조 저장 ----------

    @Test
    void 라우팅_생성_단계_파라미터_저장_상세_작성중_하나_공장별_따로() throws Exception {
        long r = routing("fg1", "P1");
        postJson(R, "{\"itemCd\":\"FG1\",\"plantCd\":\"P1\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTING_DRAFT_EXISTS"));
        routing("FG1", "P2");                                                   // 공장이 다르면 따로
        postJson(R, "{\"itemCd\":\"NOPE\",\"plantCd\":\"P9\"}").andExpect(jsonPath("$.errors[*].code").value(hasItems("NOT_FOUND")))
                .andExpect(jsonPath("$.errors.length()").value(2));
        save(r, step("a", null, 10, "PRESS", line, "12.5", param("p1", null, "TONNAGE", "180", "200", "220"))
                + "," + step("b", null, 20, "WELD", cell, "45", param("p2", null, "CURRENT", "8.5", null, "9.5")
                        + "," + param("p3", null, "time_ms", null, "300", null))
                + "," + step("c", null, 30, "plating", null, null, "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps").value(3)).andExpect(jsonPath("$.data.params").value(3));
        mvc.perform(get(R + "/" + r).with(admin))
                .andExpect(jsonPath("$.data.routing.rev").value("A0"))
                .andExpect(jsonPath("$.data.routing.stepCnt").value(3))
                .andExpect(jsonPath("$.data.routing.totalCtSec").value(57.5))
                .andExpect(jsonPath("$.data.steps[1].nodePath").value("P1/BODY/WL1/WL1-C1"))
                .andExpect(jsonPath("$.data.steps[1].params[1].paramCd").value("TIME_MS"))
                .andExpect(jsonPath("$.data.steps[2].outsrcYn").value("Y"));
        mvc.perform(get(R).param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(json(post(R + "/export"), "{\"plantCd\":\"P1\"}").with(admin)).andExpect(status().isOk());
    }

    @Test
    void 구조저장_검증_순번교환_바뀐행만_빠진단계_삭제_다른라우팅_id_거부_버전충돌() throws Exception {
        long r = routing("FG1", "P1");
        // 칸 오류: 순번 중복·없는 공정·다른 공장 작업장·창고 작업장·사이클타임 0·캐비티 0·준비 -1·조건 중복·범위·값 없음
        save(r, step("a", null, 10, "PRESS", p2line, "0", param("p1", null, "R", "5", "1", "9") + "," + param("p2", null, "t", "1", null, "2")
                        + "," + param("p4", null, "T", null, "1", null))
                + "," + step("b", null, 10, "NOPE", wh, "10", param("p3", null, "X", null, null, null))
                + ",{\"rowKey\":\"c\",\"opSeq\":30,\"procCd\":\"WELD\",\"unitsPerCycle\":0,\"setupMin\":\"-1\",\"params\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='a')].code").value(hasItems("NOT_FOUND", "POSITIVE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='p1')].code").value(hasItem("PARAM_RANGE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='p2')].code").value(hasItem("DUPLICATE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='b')].code").value(hasItems("NOT_FOUND", "WORK_CENTER")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='p3')].code").value(hasItem("PARAM_VALUE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='c')].code").value(hasItems("CODE", "NOT_NEGATIVE")));
        assertThat(accounts.query(comp, "select count(*) from mst_routing_step where routing_id = ?", Long.class, r)).isZero();

        save(r, step("a", null, 10, "PRESS", line, "12", param("p1", null, "TONNAGE", null, "200", null))
                + "," + step("b", null, 20, "WELD", cell, "45", "")).andExpect(status().isOk());
        long s10 = stepId(r, 10);
        long s20 = stepId(r, 20);
        long p1 = accounts.query(comp, "select param_id from mst_routing_param where step_id = ?", Long.class, s10);
        // 순번 10↔20 교환(커밋 시점 유일 검사) — 두 단계만 바뀌고 파라미터는 그대로
        save(r, step("S" + s10, s10, 20, "PRESS", line, "12", param("P" + p1, p1, "TONNAGE", null, "200", null))
                + "," + step("S" + s20, s20, 10, "WELD", cell, "45", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps").value(2)).andExpect(jsonPath("$.data.params").value(0));
        assertThat(accounts.query(comp, "select op_seq from mst_routing_step where step_id = ?", Integer.class, s10)).isEqualTo(20);
        // 같은 내용 다시 저장 → 바뀐 행 0
        save(r, step("S" + s10, s10, 20, "PRESS", line, "12.000", param("P" + p1, p1, "TONNAGE", null, "200.0", null))
                + "," + step("S" + s20, s20, 10, "WELD", cell, "45", "")).andExpect(jsonPath("$.data.steps").value(0))
                .andExpect(jsonPath("$.data.params").value(0));
        assertThat(accounts.query(comp, "select row_ver from mst_routing_step where step_id = ?", Integer.class, s20)).isEqualTo(1);
        // 단계를 빼면 그 파라미터와 함께 삭제
        save(r, step("S" + s20, s20, 10, "WELD", cell, "45", "")).andExpect(jsonPath("$.data.steps").value(1))
                .andExpect(jsonPath("$.data.params").value(1));
        assertThat(accounts.query(comp, "select count(*) from mst_routing_param", Long.class)).isZero();
        // 다른 라우팅의 단계 id 를 섞으면 거부
        long other = routing("RM1", "P1");
        save(other, step("x", s20, 10, "WELD", cell, "45", "")).andExpect(status().isBadRequest());
        // 낡은 머리 버전 → 409
        putJson(R + "/" + r + "/steps?rowVer=0", "{\"steps\":[]}").andExpect(status().isConflict());
    }

    // ---------- 확정 ----------

    @Test
    void 확정_조건_작업장_사이클타임_외주는_면제_사용중지공정_불변() throws Exception {
        long r = routing("FG1", "P1");
        action(r, "release").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ROUTING_RELEASE_CHECK"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_STEPS"));
        save(r, step("a", null, 10, "PRESS", null, null, "") + "," + step("b", null, 20, "PLATING", null, null, ""))
                .andExpect(status().isOk());
        long s10 = stepId(r, 10);
        long s20 = stepId(r, 20);
        action(r, "release").andExpect(jsonPath("$.errors[?(@.rowKey=='S" + s10 + "')].field").value(hasItems("nodeId", "stdCtSec")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='S" + s20 + "')]").isEmpty());          // 외주는 작업장·CT 없어도 됨
        save(r, step("S" + s10, s10, 10, "PRESS", line, "12", "") + "," + step("S" + s20, s20, 20, "PLATING", null, null, ""))
                .andExpect(status().isOk());
        // 공정을 사용 중지하면 확정 거부(이미 들어 있는 단계는 저장은 됨)
        accounts.exec(comp, "update mst_proc set use_yn = 'N' where proc_cd = 'PLATING'");
        action(r, "release").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        accounts.exec(comp, "update mst_proc set use_yn = 'Y' where proc_cd = 'PLATING'");
        action(r, "release").andExpect(status().isOk());
        // 확정본: API 수정 409, 직접 SQL 도 불변
        save(r, "").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTING_NOT_DRAFT"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_routing_step set std_ct_sec = 1 where step_id = ?", s10)))).contains("ck_mst_routing_step_draft");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_routing set remark = 'x' where routing_id = ?", r)))).contains("ck_mst_routing_immutable");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_routing set plant_cd = 'P2' where routing_id = ?", r)))).contains("ck_mst_routing_key_immutable");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from mst_routing where routing_id = ?", r))))
                .contains("42501");
        // 작성 중이어도 공장은 직접 SQL 로 못 바꾼다(작업장 같은 공장 규칙 우회 방지)
        long d = routing("RM1", "P1");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_routing set plant_cd = 'P2' where routing_id = ?", d)))).contains("ck_mst_routing_key_immutable");
    }

    @Test
    void 새리비전은_단계_파라미터_복사_확정하면_이전본_자동마감_같은날_겹침_거부() throws Exception {
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "PRESS", line, "12", param("p1", null, "TONNAGE", "180", "200", "220"))).andExpect(status().isOk());
        action(r, "release").andExpect(status().isOk());
        long r2 = id(action(r, "new-revision"));
        mvc.perform(get(R + "/" + r2).with(admin))
                .andExpect(jsonPath("$.data.routing.rev").value("A1"))
                .andExpect(jsonPath("$.data.routing.validFrom").value(today.plusDays(1).toString()))
                .andExpect(jsonPath("$.data.steps[0].params[0].targetVal").value(200));
        action(r2, "release").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select valid_to::text from mst_routing where routing_id = ?", String.class, r))
                .isEqualTo(today.plusDays(1).toString());
        // 시작일을 이전 확정본과 같은 날로 직접 정한 새 리비전 → 겹침
        long r3 = id(postJson(R + "/" + r2 + "/new-revision", "{\"rowVer\":" + rowVer(r2) + ",\"validFrom\":\""
                + today.plusDays(1) + "\",\"rev\":\"b0\"}"));
        action(r3, "release").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTING_PERIOD_OVERLAP"));
        postJson(R + "/" + r3 + "/new-revision", "{\"rowVer\":" + rowVer(r3) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTING_DRAFT_EXISTS"));
    }

    // ---------- 작업장 ----------

    @Test
    void 확정라우팅의_작업장은_사용중지_거부_폐기하면_가능_작성중은_막지않음() throws Exception {
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "WELD", cell, "45", "") + "," + step("b", null, 20, "WELD", line, "30", "")).andExpect(status().isOk());
        // 작성 중 라우팅만 쓰는 작업장은 사용 중지 가능(확정 때 다시 본다) — 되돌려 둔다
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", cell);
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'Y' where node_id = ?", cell);
        action(r, "release").andExpect(status().isOk());
        int ver = accounts.query(comp, "select row_ver from mst_equip_hier where node_id = ?", Integer.class, cell);
        putJson("/api/master/equip-hier/" + cell, "{\"parentId\":" + line + ",\"nodeCd\":\"WL1-C1\",\"nodeNm\":\"셀\",\"useYn\":\"N\",\"rowVer\":" + ver + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("HAS_ROUTING"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_equip_hier set node_type = 'WH' where node_id = ?", line)))).contains("ck_mst_equip_hier_type_fixed");   // V023: 유형 자체가 불변(QA #2) — 라우팅 검사보다 먼저 걸림
        action(r, "obsolete").andExpect(status().isOk());
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", cell);
    }

    @Test
    void BOM_연결점검_라우팅에_없는_공정순번을_가리키는_BOM행() throws Exception {
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                select ?, item_id, 'A0', ?, 't', 't' from mst_item where item_cd = 'FG1' returning bom_id""", Long.class, comp, today);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, op_seq, created_by, updated_by)
                select ?, ?, n, item_id, 1, 'EA', 'BACKFLUSH', s, 't', 't' from mst_item, (values (10, 10), (20, 30)) v(n, s)
                 where item_cd = 'RM1'""", comp, bom);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "PRESS", line, "12", "") + "," + step("b", null, 20, "WELD", cell, "45", "")).andExpect(status().isOk());
        mvc.perform(get(R + "/" + r).with(admin))
                .andExpect(jsonPath("$.data.unmatchedBomOps.length()").value(1))
                .andExpect(jsonPath("$.data.unmatchedBomOps[0].opSeq").value(30))
                .andExpect(jsonPath("$.data.unmatchedBomOps[0].itemCd").value("RM1"));
    }

    // ---------- A/B사 · 권한 ----------

    @Test
    void B사_사용자는_A사_라우팅_공정_작업장을_못쓴다_권한() throws Exception {
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "PRESS", line, "12", "")).andExpect(status().isOk());
        long s10 = stepId(r, 10);
        String other = accounts.newComp("RT");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-routing", "SEARCH", "SAVE", "APPROVE");
        accounts.addPlant(other, "P1", "B사 공장");
        accounts.exec(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG1', 'B사 품목', 'FG', 'MAKE', 'EA', 't', 't')""", other);
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(R + "/" + r).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(R).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(R + "/procs").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(json(put(R + "/" + r + "/steps?rowVer=1"), "{\"steps\":[]}").with(b)).andExpect(status().isNotFound());
        long br = id(mvc.perform(json(post(R), "{\"itemCd\":\"FG1\",\"plantCd\":\"P1\"}").with(b)));
        // A사 공정 코드·A사 작업장 id 로 B사 단계
        mvc.perform(json(put(R + "/" + br + "/steps?rowVer=0"), "{\"steps\":[" + step("a", null, 10, "PRESS", line, "12", "") + "]}")
                .with(b)).andExpect(jsonPath("$.errors[*].code").value(hasItems("NOT_FOUND")))
                .andExpect(jsonPath("$.errors.length()").value(2));
        // 직접 SQL: B사 범위에서 A사 라우팅에 단계, A사 공정·작업장으로 B사 단계, A사 단계에 파라미터
        long aProc = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'PRESS'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 20, ?, 't', 't')""", other, r, aProc)))).contains("fk_mst_routing_step_routing_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, ?, 't', 't')""", other, br, aProc)))).contains("fk_mst_routing_step_proc_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into mst_routing_param (comp_cd, step_id, param_cd, param_nm, target_val, created_by, updated_by)
                values (?, ?, 'X', 'x', 1, 't', 't')""", other, s10)))).contains("fk_mst_routing_param_step_comp");
        // 권한: 라우팅 조회+저장만 → 확정·폐기 403, 공정 관리 API 403, 라우팅 화면의 공정 선택지는 200
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-routing", "SEARCH", "SAVE");
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        mvc.perform(json(post(R + "/" + r + "/release"), "{\"rowVer\":" + rowVer(r) + "}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(R + "/" + r + "/obsolete"), "{\"rowVer\":" + rowVer(r) + "}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(get("/api/master/procs").with(rd)).andExpect(status().isForbidden());
        mvc.perform(get(R + "/procs").with(rd)).andExpect(status().isOk());
        mvc.perform(json(post(R + "/export"), "{}").with(rd)).andExpect(status().isForbidden());
    }

    // ---------- 공학 단위 공통코드 (docs/68 Q2) ----------

    private static String unitParam(String key, String cd, String unitCd, String unitNm) {
        return "{\"rowKey\":\"" + key + "\",\"paramId\":null,\"paramCd\":\"" + cd + "\",\"paramNm\":\"" + cd + "\",\"unitCd\":"
                + q(unitCd) + ",\"unitNm\":" + q(unitNm) + ",\"targetVal\":\"10\"}";
    }

    private String units(long routingId) {
        return accounts.query(comp, """
                select string_agg(m.param_cd || '=' || coalesce(m.unit_cd, '-') || '/' || coalesce(m.unit_nm, '-'), ',' order by m.param_cd)
                  from mst_routing_param m join mst_routing_step s on s.step_id = m.step_id where s.routing_id = ?""",
                String.class, routingId);
    }

    @Test
    void 공학단위는_MEAS_UNIT_코드이고_옛글자는_같은이름_코드로_목록밖_글자는_그대로_남는다() throws Exception {
        long r = routing("FG1", "P1");
        // 코드 → 이름 사본은 서버가(보낸 unitNm 은 무시), 옛 글자 "ms" → 같은 이름 코드 MS, 목록 밖 글자는 코드 없이 그대로
        save(r, step("s1", null, 10, "WELD", line, "30", unitParam("a", "CUR", "KA", "아무거나") + "," + unitParam("b", "TIME", null, "ms")
                + "," + unitParam("c", "PRS", null, "kgf/cm2"))).andExpect(status().isOk());
        assertThat(units(r)).isEqualTo("CUR=KA/kA,PRS=-/kgf/cm2,TIME=MS/ms");
        // 회사 단위 코드 — 이 회사 것만. 사용 중지·없는 코드·다른 회사 코드·이름이 20자를 넘는 코드 → unitCd 오류
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'MEAS_UNIT', 'NM_DEG', 'N·m/°', 't', 't')", comp);
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, use_yn, created_by, updated_by) values (?, 'MEAS_UNIT', 'OLDU', '옛 단위', 'N', 't', 't')", comp);
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'MEAS_UNIT', 'LONGNM', '이름이 아주 아주 아주 길어서 이십 자를 넘는 단위', 't', 't')", comp);
        String other = accounts.newComp("RT");
        accounts.exec(other, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'MEAS_UNIT', 'BONLY', 'B 단위', 't', 't')", other);
        long s1 = stepId(r, 10);
        save(r, step("s1", s1, 10, "WELD", line, "30", unitParam("d", "TQ", "NM_DEG", null) + "," + unitParam("e", "X1", "OLDU", null)
                + "," + unitParam("f", "X2", "NOPE", null) + "," + unitParam("g", "X3", "BONLY", null) + "," + unitParam("h", "X4", "LONGNM", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e')].code").value(hasItem("CODE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='f')].code").value(hasItem("CODE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='g')].code").value(hasItem("CODE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='h')].code").value(hasItem("CODE_NAME_LONG")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='d')]").isEmpty());
        save(r, step("s1", s1, 10, "WELD", line, "30", unitParam("d", "TQ", "NM_DEG", null))).andExpect(status().isOk());
        assertThat(units(r)).isEqualTo("TQ=NM_DEG/N·m/°");
        // 그 코드를 사용 중지해도 같은 행의 다른 칸은 고친다(코드가 그대로면 다시 보지 않음 — 트리거와 같은 규칙)
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where grp_cd = 'MEAS_UNIT' and code = 'NM_DEG'");
        long pid = accounts.query(comp, "select param_id from mst_routing_param where step_id = ?", Long.class, s1);
        save(r, step("s1", s1, 10, "WELD", line, "30", "{\"rowKey\":\"d\",\"paramId\":" + pid
                + ",\"paramCd\":\"TQ\",\"paramNm\":\"토크 바꿈\",\"unitCd\":\"NM_DEG\",\"targetVal\":\"12\"}")).andExpect(status().isOk());
        // 직접 SQL — 다른 회사 코드·없는 코드는 트리거가 거부
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update mst_routing_param set unit_cd = 'BONLY' where param_id = ?", pid))))
                .contains("fk_mst_routing_param_unit_cd_code");
        // B사는 A사 단위 코드를 선택 목록에서 보지 못한다
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "MASTER", "mst-routing", "SEARCH");
        mvc.perform(get("/api/platform/codes").param("grp", "MEAS_UNIT").with(accounts.as(other, bu, "admin1")))
                .andExpect(jsonPath("$.data[*].code").value(hasItems("KA", "BONLY")))
                .andExpect(jsonPath("$.data[*].code").value(org.hamcrest.Matchers.not(hasItem("NM_DEG"))));
    }
}
