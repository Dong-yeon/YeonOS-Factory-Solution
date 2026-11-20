package io.smarthappy.module.apqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * P6 APQP A1 PFMEA (docs/41 4·7장) — AP 규칙표 덮임·사용·확정본 보호, 구조·기능·고장·사슬·위험·AP 계산, 확정·새 리비전·폐기,
 * 확정본 불변(API·직접 SQL), 조치 진행(P7), 이상 입력(수준·짝·라우팅 밖 단계·남의 특성·등급 범위), 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class ApqpFmeaTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String F = "/api/apqp/fmeas";
    private static final String AP = "/api/apqp/ap-sets";

    /** 예시 규칙(핸드북 표 아님): S 9~10 = H / S 1~8 · O 6~10 = M / S 1~8 · O 1~5 = L — 1,000 조합을 정확히 한 번 덮는다 */
    private static final String RULES = """
            [{"sevFrom":9,"sevTo":10,"occFrom":1,"occTo":10,"detFrom":1,"detTo":10,"ap":"H"},
             {"sevFrom":1,"sevTo":8,"occFrom":6,"occTo":10,"detFrom":1,"detTo":10,"ap":"M"},
             {"sevFrom":1,"sevTo":8,"occFrom":1,"occTo":5,"detFrom":1,"detTo":10,"ap":"L"}]""";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor qe;
    long routingId;
    long step10;
    long step20;
    long prodChar;
    long weldChar;
    long inspChar;
    long defectId;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AQ");
        long u = accounts.newUser(comp, "qe1", PW);
        accounts.grant(comp, u, "APQP", "apqp-fmea", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "APQP", "apqp-ap", "SEARCH", "SAVE");
        qe = accounts.as(comp, u, "qe1");
        Fixture fx = fixture(comp);
        routingId = fx.routing;
        step10 = fx.step10;
        step20 = fx.step20;
        prodChar = fx.prodChar;
        weldChar = fx.weldChar;
        inspChar = fx.inspChar;
        defectId = fx.defect;
    }

    private record Fixture(long routing, long step10, long step20, long prodChar, long weldChar, long inspChar, long defect) {
    }

    /** 품목 ZT-BRKT, 라우팅(10 용접 · 20 검사), 제품 특성 1·공정 특성 2, 불량 유형 1 */
    private Fixture fixture(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'ZT-BRKT', 'ZT 브래킷', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'ZT-OTHER', 'ZT 다른 품목', 'FG', 'MAKE', 'EA', 't', 't')""",
                c, c);
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'ZWELD', 'ZT 용접', 'WELD', 't', 't'), (?, 'ZINSP', 'ZT 검사', 'INSPECT', 't', 't')""", c, c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'ZT-BRKT'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't')
                returning routing_id""", Long.class, c);
        long s10 = accounts.query(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ZWELD'), 't', 't') returning step_id""", Long.class, c, rid);
        long s20 = accounts.query(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 20, (select proc_id from mst_proc where proc_cd = 'ZINSP'), 't', 't') returning step_id""", Long.class, c, rid);
        long pc = accounts.query(c, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, special_cls, created_by, updated_by)
                values (?, 'PRODUCT', (select item_id from mst_item where item_cd = 'ZT-BRKT'), 'ZC1', 'ZT 용접 강도', 'ATTRIBUTE', 'CC', 't', 't')
                returning char_id""", Long.class, c);
        long wc = accounts.query(c, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, created_by, updated_by)
                values (?, 'PROCESS', (select proc_id from mst_proc where proc_cd = 'ZWELD'), 'ZP1', 'ZT 용접 전류', 'ATTRIBUTE', 't', 't')
                returning char_id""", Long.class, c);
        long ic = accounts.query(c, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, created_by, updated_by)
                values (?, 'PROCESS', (select proc_id from mst_proc where proc_cd = 'ZINSP'), 'ZP2', 'ZT 검사 조도', 'ATTRIBUTE', 't', 't')
                returning char_id""", Long.class, c);
        long d = accounts.query(c, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'ZD1', 'ZT 용접 미용착', 'WELD', 't', 't') returning defect_id""", Long.class, c);
        return new Fixture(rid, s10, s20, pc, wc, ic, d);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private long id(ResultActions r) throws Exception {
        String s = r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private <T> T read(String url, String path) throws Exception {
        return JsonPath.read(mvc.perform(get(url).with(qe)).andReturn().getResponse().getContentAsString(), path);
    }

    private int rowVer(String url, String path) throws Exception {
        return ((Number) read(url, path)).intValue();
    }

    private long apSet(String cd, String use) throws Exception {
        return id(send(post(AP), "{\"setCd\":\"" + cd + "\",\"setNm\":\"ZT 예시(핸드북 아님)\",\"useYn\":\"" + use + "\",\"rules\":" + RULES + "}", qe));
    }

    /** 표준 FMEA 한 벌: 라우팅에서 단계 2개, 용접 단계 아래 설비 작업요소, FE(S 9)·FM(불량 유형)·FC, 사슬, 위험(O 3·D 4) */
    private record Doc(long fmea, long item, long stepNode, long element, long fe, long fm, long fc, long risk) {
    }

    private Doc doc(long set) throws Exception {
        long fmea = id(send(post(F), "{\"itemCd\":\"ZT-BRKT\",\"routingId\":" + routingId + ",\"title\":\"ZT 브래킷 PFMEA\",\"apSetId\":" + set
                + ",\"fromRouting\":true}", qe));
        List<Map<String, Object>> nodes = read(F + "/" + fmea, "$.data.nodes");
        long item = nodeId(nodes, "ITEM", null);
        long stepNode = nodeId(nodes, "STEP", step10);
        long el = id(send(post(F + "/" + fmea + "/nodes"), "{\"parentId\":" + stepNode + ",\"nodeKind\":\"ELEMENT\",\"nodeNm\":\"ZT 용접기\",\"m4\":\"MACHINE\"}", qe));
        long fItem = id(send(post(F + "/" + fmea + "/funcs"), "{\"nodeId\":" + item + ",\"funcText\":\"차체 고정\",\"charId\":" + prodChar + "}", qe));
        long fStep = id(send(post(F + "/" + fmea + "/funcs"), "{\"nodeId\":" + stepNode + ",\"funcText\":\"규정 강도로 용접\",\"charId\":" + weldChar + "}", qe));
        long fEl = id(send(post(F + "/" + fmea + "/funcs"), "{\"nodeId\":" + el + ",\"funcText\":\"설정 전류 유지\"}", qe));
        long fe = id(send(post(F + "/" + fmea + "/fails"), "{\"funcId\":" + fItem + ",\"failText\":\"주행 중 이탈\",\"severity\":9}", qe));
        long fm = id(send(post(F + "/" + fmea + "/fails"), "{\"funcId\":" + fStep + ",\"failText\":\"용접 강도 부족\",\"defectId\":" + defectId + "}", qe));
        long fc = id(send(post(F + "/" + fmea + "/fails"), "{\"funcId\":" + fEl + ",\"failText\":\"전류 저하\"}", qe));
        id(send(post(F + "/" + fmea + "/chains"), "{\"fmId\":" + fm + ",\"feId\":" + fe + "}", qe));
        long risk = id(send(post(F + "/" + fmea + "/risks"), "{\"fmId\":" + fm + ",\"fcId\":" + fc
                + ",\"prevCtrl\":\"전류 감시\",\"detCtrl\":\"파괴 시험\",\"occ\":3,\"det\":4,\"specialCls\":\"SC\"}", qe));
        return new Doc(fmea, item, stepNode, el, fe, fm, fc, risk);
    }

    private static long nodeId(List<Map<String, Object>> nodes, String kind, Long stepId) {
        return nodes.stream().filter(n -> kind.equals(n.get("nodeKind"))
                        && (stepId == null || stepId.equals(((Number) n.get("stepId")).longValue())))
                .map(n -> ((Number) n.get("nodeId")).longValue()).findFirst().orElseThrow();
    }

    private int headVer(long fmea) throws Exception {
        return rowVer(F + "/" + fmea, "$.data.fmea.rowVer");
    }

    // ------------------------------------------------------------------ AP 규칙표

    @Test
    void AP_규칙표는_빠짐_겹침_없이_덮어야_사용_확정FMEA가_쓴_표는_못바꾼다() throws Exception {
        // 빠짐(S 9~10 만) → 사용 불가
        send(post(AP), "{\"setCd\":\"ZT-MISS\",\"setNm\":\"x\",\"useYn\":\"Y\",\"rules\":"
                + "[{\"sevFrom\":9,\"sevTo\":10,\"occFrom\":1,\"occTo\":10,\"detFrom\":1,\"detTo\":10,\"ap\":\"H\"}]}", qe)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("APQP_AP_INCOMPLETE"));
        // 겹침(전체 H + S 9~10 H) → 사용 불가, 사용 안 함이면 저장은 된다(작성 중 표)
        String overlap = "[{\"sevFrom\":1,\"sevTo\":10,\"occFrom\":1,\"occTo\":10,\"detFrom\":1,\"detTo\":10,\"ap\":\"L\"},"
                + "{\"sevFrom\":9,\"sevTo\":10,\"occFrom\":1,\"occTo\":10,\"detFrom\":1,\"detTo\":10,\"ap\":\"H\"}]";
        send(post(AP), "{\"setCd\":\"ZT-OVER\",\"setNm\":\"x\",\"useYn\":\"Y\",\"rules\":" + overlap + "}", qe)
                .andExpect(jsonPath("$.code").value("APQP_AP_INCOMPLETE"));
        long draftSet = id(send(post(AP), "{\"setCd\":\"ZT-OVER\",\"setNm\":\"x\",\"useYn\":\"N\",\"rules\":" + overlap + "}", qe));
        assertThat((Integer) read(AP + "/" + draftSet, "$.data.coverage.overlap")).isEqualTo(200);
        // 행 오류: 범위 밖·시작 > 끝·AP 코드 — 행 번호(rowKey)로
        send(post(AP), "{\"setCd\":\"ZT-BAD\",\"setNm\":\"x\",\"rules\":[{\"sevFrom\":0,\"sevTo\":10,\"occFrom\":5,\"occTo\":2,"
                + "\"detFrom\":1,\"detTo\":10,\"ap\":\"X\"}]}", qe)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("sevFrom", "occTo", "ap")))
                .andExpect(jsonPath("$.errors[0].rowKey").value("1"));
        // 완전한 표 → 사용, 같은 코드 중복
        long set = apSet("ZT-EX", "Y");
        assertThat((Integer) read(AP + "/" + set, "$.data.coverage.missing")).isZero();
        send(post(AP), "{\"setCd\":\"zt-ex\",\"setNm\":\"x\"}", qe).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 확정 FMEA 가 쓰면 규칙은 못 바꾼다(API·직접 SQL), 이름은 바꾼다 → 복사해서 새 표
        Doc d = doc(set);
        send(post(F + "/" + d.fmea() + "/release"), "{\"rowVer\":" + headVer(d.fmea()) + "}", qe).andExpect(status().isOk());
        int v = rowVer(AP + "/" + set, "$.data.set.rowVer");
        send(put(AP + "/" + set), "{\"setNm\":\"ZT 예시 2\",\"useYn\":\"Y\",\"rowVer\":" + v + ",\"rules\":"
                + RULES.replace("\"ap\":\"L\"", "\"ap\":\"M\"") + "}", qe).andExpect(jsonPath("$.code").value("APQP_AP_SET_USED"));
        send(put(AP + "/" + set), "{\"setNm\":\"ZT 예시 2\",\"useYn\":\"Y\",\"rowVer\":" + v + "}", qe).andExpect(status().isOk());
        Throwable t = catchThrowable(() -> accounts.exec(comp, "delete from apqp_ap_rule where set_id = ?", set));
        assertThat(t).hasMessageContaining("used by a released fmea");
        long copy = id(send(post(AP + "/" + set + "/copy"), "{\"setCd\":\"ZT-EX2\"}", qe));
        assertThat((Integer) read(AP + "/" + copy, "$.data.set.ruleCnt")).isEqualTo(3);
        assertThat((String) read(AP + "/" + copy, "$.data.set.useYn")).isEqualTo("N");
    }

    // ------------------------------------------------------------------ 정상 흐름

    @Test
    void 구조_고장사슬_위험_AP계산_확정_새리비전_폐기() throws Exception {
        long set = apSet("ZT-EX", "Y");
        Doc d = doc(set);
        String url = F + "/" + d.fmea();
        // 라우팅에서 단계 2개 + 아이템 + 작업요소 = 4 노드, S = 연결 FE 9 → AP H(S 9~10)
        assertThat((List<?>) read(url, "$.data.nodes")).hasSize(4);
        assertThat((Integer) read(url, "$.data.risks[0].sev")).isEqualTo(9);
        assertThat((String) read(url, "$.data.risks[0].ap")).isEqualTo("H");
        assertThat((String) read(url, "$.data.fails[?(@.failKind == 'FM')].defectCd").toString()).contains("ZD1");
        // FE 심각도 9 → 5: S 5 · O 3 → L (다시 계산)
        int fv = ((Number) ((List<?>) read(url, "$.data.fails[?(@.failId == " + d.fe() + ")].rowVer")).get(0)).intValue();
        send(put("/api/apqp/fmea-fails/" + d.fe()), "{\"failText\":\"주행 중 이탈\",\"severity\":5,\"rowVer\":" + fv + "}", qe)
                .andExpect(status().isOk());
        assertThat((String) read(url, "$.data.risks[0].ap")).isEqualTo("L");
        // O 3 → 7: S 5 · O 7 → M
        int rv = rowVer(url, "$.data.risks[0].rowVer");
        send(put("/api/apqp/fmea-risks/" + d.risk()), "{\"occ\":7,\"det\":4,\"rowVer\":" + rv + "}", qe).andExpect(status().isOk());
        assertThat((String) read(url, "$.data.risks[0].ap")).isEqualTo("M");
        // 조치(검출 강화) — 조치 뒤 O 2 → 조치 뒤 AP L
        long act = id(send(post(url + "/actions"), "{\"riskId\":" + d.risk() + ",\"actionKind\":\"PREVENT\",\"actionText\":\"전류 이상 정지\","
                + "\"ownerLoginId\":\"qe1\",\"newOcc\":2}", qe));
        assertThat((String) read(url, "$.data.actions[0].newAp")).isEqualTo("L");
        // 확정 → 상태 RELEASED, 목록 grid 에 AP 높음 0·열린 조치 1
        send(post(url + "/release"), "{\"rowVer\":" + headVer(d.fmea()) + "}", qe).andExpect(status().isOk());
        assertThat((String) read(url, "$.data.fmea.status")).isEqualTo("RELEASED");
        assertThat((Boolean) read(url, "$.data.editable")).isFalse();
        mvc.perform(get(F + "/grid").param("keyword", "ZT 브래킷").with(qe)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].openActionCnt").value(1)).andExpect(jsonPath("$.list[0].highCnt").value(0));
        // 확정본은 고칠 수 없다(API 409 · 직접 SQL 거부)
        send(post(url + "/nodes"), "{\"parentId\":" + d.item() + ",\"nodeKind\":\"STEP\",\"nodeNm\":\"x\"}", qe)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APQP_FMEA_STATE"));
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_fmea_risk set occ = 1 where risk_id = ?", d.risk())))
                .hasMessageContaining("is RELEASED");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_fmea set title = 'x' where fmea_id = ?", d.fmea())))
                .hasMessageContaining("is RELEASED");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from apqp_fmea_node where node_id = ?", d.element())))
                .hasMessageContaining("is RELEASED");
        // 확정본 조치 진행(P7): 완료 기록은 된다, 내용은 못 바꾼다
        int av = rowVer(url, "$.data.actions[0].rowVer");
        send(put("/api/apqp/fmea-actions/" + act), "{\"actionKind\":\"PREVENT\",\"actionText\":\"다른 내용\",\"ownerLoginId\":\"qe1\","
                + "\"status\":\"DONE\",\"doneOn\":\"2026-10-23\",\"rowVer\":" + av + "}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        send(put("/api/apqp/fmea-actions/" + act), "{\"actionKind\":\"PREVENT\",\"actionText\":\"전류 이상 정지\",\"ownerLoginId\":\"qe1\","
                + "\"status\":\"DONE\",\"doneOn\":\"2026-10-23\",\"evidence\":\"시험 성적\",\"newOcc\":2,\"rowVer\":" + av + "}", qe)
                .andExpect(status().isOk());
        // 새 리비전 = 복사(노드·고장·사슬·위험·조치), 그동안 확정본 조치는 새 리비전에서만
        long rev2 = id(send(post(url + "/revise"), "{}", qe));
        String url2 = F + "/" + rev2;
        assertThat((Integer) read(url2, "$.data.fmea.rev")).isEqualTo(2);
        assertThat((List<?>) read(url2, "$.data.nodes")).hasSize(4);
        assertThat((List<?>) read(url2, "$.data.chains")).hasSize(1);
        assertThat((String) read(url2, "$.data.risks[0].ap")).isEqualTo("M");
        assertThat((String) read(url2, "$.data.actions[0].status")).isEqualTo("DONE");
        send(post(url + "/revise"), "{}", qe).andExpect(jsonPath("$.code").value("APQP_FMEA_STATE"));
        send(put("/api/apqp/fmea-actions/" + act), "{\"actionKind\":\"PREVENT\",\"actionText\":\"전류 이상 정지\",\"status\":\"OPEN\","
                + "\"rowVer\":" + (av + 1) + "}", qe).andExpect(jsonPath("$.code").value("APQP_FMEA_STATE"));
        // 새 리비전 확정 → 이전 확정본 폐기
        send(post(url2 + "/release"), "{\"rowVer\":" + headVer(rev2) + "}", qe).andExpect(status().isOk());
        assertThat((String) read(url, "$.data.fmea.status")).isEqualTo("OBSOLETE");
        mvc.perform(get(F + "/grid").param("keyword", "ZT 브래킷").with(qe)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].rev").value(2));
        mvc.perform(get(F + "/grid").param("keyword", "ZT 브래킷").param("status", "ALL").with(qe)).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get(F + "/columns").with(qe)).andExpect(jsonPath("$.list[*].field").value(hasItems("fmeaNo", "status", "highCnt")));
    }

    @Test
    void 확정_조건_미달은_행마다_사유_작성중은_지울수있다() throws Exception {
        long set = apSet("ZT-EX", "Y");
        Doc d = doc(set);
        // 사슬 없는 고장 형태 + 원인 없는 고장 형태 + O 없는 위험행
        long fStep = ((Number) ((List<?>) read(F + "/" + d.fmea(), "$.data.funcs[?(@.nodeId == " + d.stepNode() + ")].funcId")).get(0))
                .longValue();
        long fm2 = id(send(post(F + "/" + d.fmea() + "/fails"), "{\"funcId\":" + fStep + ",\"failText\":\"위치 틀어짐\"}", qe));
        int rv = rowVer(F + "/" + d.fmea(), "$.data.risks[0].rowVer");
        send(put("/api/apqp/fmea-risks/" + d.risk()), "{\"det\":4,\"rowVer\":" + rv + "}", qe).andExpect(status().isOk());
        send(post(F + "/" + d.fmea() + "/release"), "{\"rowVer\":" + headVer(d.fmea()) + "}", qe)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("APQP_RELEASE_CHECK"))
                .andExpect(jsonPath("$.errors[*].rowKey").value(hasItems("FAIL:" + fm2, "RISK:" + d.risk())))
                .andExpect(jsonPath("$.errors[*].code").value(hasItems("APQP_NO_CHAIN", "APQP_NO_RISK", "REQUIRED")));
        // 작성 중 리비전은 통째로 지운다(하위 행 모두), 낡은 rowVer 는 409
        send(delete(F + "/" + d.fmea()).param("rowVer", "0"), "", qe).andExpect(status().isConflict());
        send(delete(F + "/" + d.fmea()).param("rowVer", String.valueOf(headVer(d.fmea()))), "", qe).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from apqp_fmea_node where fmea_id = ?", Long.class, d.fmea())).isZero();
        mvc.perform(get(F + "/" + d.fmea()).with(qe)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ 이상 사용

    @Test
    void 구조수준_짝_라우팅밖단계_남의특성_등급범위_동시수정() throws Exception {
        long set = apSet("ZT-EX", "Y");
        Doc d = doc(set);
        String url = F + "/" + d.fmea();
        // 작업요소를 아이템 아래에 / 단계를 단계 아래에 / 4M 없음
        send(post(url + "/nodes"), "{\"parentId\":" + d.item() + ",\"nodeKind\":\"ELEMENT\",\"nodeNm\":\"x\",\"m4\":\"MAN\"}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_LEVEL"));
        send(post(url + "/nodes"), "{\"parentId\":" + d.stepNode() + ",\"nodeKind\":\"STEP\",\"nodeNm\":\"x\"}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_LEVEL"));
        send(post(url + "/nodes"), "{\"parentId\":" + d.stepNode() + ",\"nodeKind\":\"ELEMENT\",\"nodeNm\":\"x\"}", qe)
                .andExpect(jsonPath("$.errors[0].field").value("m4"));
        // 다른 라우팅의 단계
        long otherStep = accounts.query(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                select comp_cd, ?, 90, proc_id, 't', 't' from mst_proc where proc_cd = 'ZINSP' returning step_id""", Long.class,
                accounts.query(comp, """
                        insert into mst_routing (comp_cd, item_id, plant_cd, alt_no, rev, valid_from, created_by, updated_by)
                        values (?, (select item_id from mst_item where item_cd = 'ZT-OTHER'), 'P1', 1, 'A0',
                                (now() at time zone 'Asia/Seoul')::date, 't', 't') returning routing_id""", Long.class, comp));
        send(post(url + "/nodes"), "{\"parentId\":" + d.item() + ",\"nodeKind\":\"STEP\",\"stepId\":" + otherStep + "}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_STEP_ROUTING"));
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_fmea_node (comp_cd, fmea_id, parent_id, node_kind, node_nm, step_id, created_by, updated_by)
                values (?, ?, ?, 'STEP', 'x', ?, 't', 't')""", comp, d.fmea(), d.item(), otherStep)))
                .hasMessageContaining("is not of the fmea routing");
        // 검사 공정 특성을 용접 단계 기능에 / 다른 품목 제품 특성
        long otherChar = accounts.query(comp, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, created_by, updated_by)
                values (?, 'PRODUCT', (select item_id from mst_item where item_cd = 'ZT-OTHER'), 'ZC9', 'x', 'ATTRIBUTE', 't', 't')
                returning char_id""", Long.class, comp);
        send(post(url + "/funcs"), "{\"nodeId\":" + d.stepNode() + ",\"funcText\":\"x\",\"charId\":" + inspChar + "}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_CHAR_TARGET"));
        send(post(url + "/funcs"), "{\"nodeId\":" + d.item() + ",\"funcText\":\"x\",\"charId\":" + otherChar + "}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_CHAR_TARGET"));
        // 고장 종류는 구조 수준이 정한다(아이템 기능에 FM 요청) · FE 심각도 범위 · 사슬 반대로
        long fItem = ((Number) ((List<?>) read(url, "$.data.funcs[?(@.nodeId == " + d.item() + ")].funcId")).get(0)).longValue();
        send(post(url + "/fails"), "{\"funcId\":" + fItem + ",\"failKind\":\"FM\",\"failText\":\"x\"}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_LEVEL"));
        send(post(url + "/fails"), "{\"funcId\":" + fItem + ",\"failText\":\"x\",\"severity\":11}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        send(post(url + "/chains"), "{\"fmId\":" + d.fe() + ",\"feId\":" + d.fm() + "}", qe).andExpect(jsonPath("$.errors[0].code").value("APQP_LEVEL"));
        send(post(url + "/chains"), "{\"fmId\":" + d.fm() + ",\"feId\":" + d.fe() + "}", qe).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 다른 단계 아래 원인과 짝(검사 단계 작업요소의 FC)
        long inspNode = nodeId(read(url, "$.data.nodes"), "STEP", step20);
        long el2 = id(send(post(url + "/nodes"), "{\"parentId\":" + inspNode + ",\"nodeKind\":\"ELEMENT\",\"nodeNm\":\"검사자\",\"m4\":\"MAN\"}", qe));
        long f2 = id(send(post(url + "/funcs"), "{\"nodeId\":" + el2 + ",\"funcText\":\"판정\"}", qe));
        long fc2 = id(send(post(url + "/fails"), "{\"funcId\":" + f2 + ",\"failText\":\"놓침\"}", qe));
        send(post(url + "/risks"), "{\"fmId\":" + d.fm() + ",\"fcId\":" + fc2 + "}", qe).andExpect(jsonPath("$.errors[0].code").value("APQP_PAIR"));
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_fmea_risk (comp_cd, fmea_id, fm_id, fc_id, created_by, updated_by) values (?, ?, ?, ?, 't', 't')""",
                comp, d.fmea(), d.fm(), fc2))).hasMessageContaining("risk must pair");
        // 등급 범위·특별특성 코드·같은 짝 다시
        send(post(url + "/risks"), "{\"fmId\":" + d.fm() + ",\"fcId\":" + d.fc() + ",\"occ\":0}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        int rv = rowVer(url, "$.data.risks[0].rowVer");
        send(put("/api/apqp/fmea-risks/" + d.risk()), "{\"occ\":0,\"det\":11,\"specialCls\":\"ZZZ\",\"rowVer\":" + rv + "}", qe)
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("occ", "det", "specialCls")));
        // 동시 수정: 같은 rowVer 두 번 → 두 번째 409
        send(put("/api/apqp/fmea-risks/" + d.risk()), "{\"occ\":2,\"det\":4,\"rowVer\":" + rv + "}", qe).andExpect(status().isOk());
        send(put("/api/apqp/fmea-risks/" + d.risk()), "{\"occ\":5,\"det\":4,\"rowVer\":" + rv + "}", qe).andExpect(status().isConflict());
        // 단계를 지우면 아래 작업요소·기능·고장·위험·조치까지(작성 중)
        int sv = ((Number) ((List<?>) read(url, "$.data.nodes[?(@.nodeId == " + d.stepNode() + ")].rowVer")).get(0)).intValue();
        send(delete("/api/apqp/fmea-nodes/" + d.stepNode()).param("rowVer", String.valueOf(sv)), "", qe).andExpect(status().isOk());
        assertThat((List<?>) read(url, "$.data.risks")).isEmpty();
        assertThat((List<?>) read(url, "$.data.chains")).isEmpty();
        // 아이템 노드는 지우지 않는다
        int iv = ((Number) ((List<?>) read(url, "$.data.nodes[?(@.nodeId == " + d.item() + ")].rowVer")).get(0)).intValue();
        send(delete("/api/apqp/fmea-nodes/" + d.item()).param("rowVer", String.valueOf(iv)), "", qe)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_LEVEL"));
    }

    @Test
    void 권한_SAVE_APPROVE_없으면_403() throws Exception {
        long set = apSet("ZT-EX", "Y");
        Doc d = doc(set);
        long viewer = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewer, "VIEW", "apqp-fmea", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "view1");
        mvc.perform(get(F + "/" + d.fmea()).with(v)).andExpect(status().isOk()).andExpect(jsonPath("$.data.editable").value(false));
        send(post(F), "{\"itemCd\":\"ZT-BRKT\",\"title\":\"x\"}", v).andExpect(status().isForbidden());
        send(post(F + "/" + d.fmea() + "/release"), "{\"rowVer\":" + headVer(d.fmea()) + "}", v).andExpect(status().isForbidden());
        send(post(AP), "{\"setCd\":\"ZT-V\",\"setNm\":\"x\"}", v).andExpect(status().isForbidden());
        long saver = accounts.newUser(comp, "save1", PW);
        accounts.grant(comp, saver, "SAVE", "apqp-fmea", "SEARCH", "SAVE");
        send(post(F + "/" + d.fmea() + "/release"), "{\"rowVer\":" + headVer(d.fmea()) + "}", accounts.as(comp, saver, "save1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void A사로_B사_FMEA_규칙표_특성_불량유형을_못쓴다() throws Exception {
        long set = apSet("ZT-EX", "Y");
        Doc d = doc(set);
        String other = accounts.newComp("AR");
        long bu = accounts.newUser(other, "qe1", PW);
        accounts.grant(other, bu, "APQP", "apqp-fmea", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(other, bu, "APQP", "apqp-ap", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "qe1");
        Fixture fb = fixture(other);
        // 목록·상세·규칙표 — B사에서 A사 것은 안 보인다
        mvc.perform(get(F + "/grid").with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(F + "/" + d.fmea()).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(AP + "/" + set).with(b)).andExpect(status().isNotFound());
        // A사 FMEA 에 B사 사용자가 행 추가·확정·삭제 → 404(없는 것으로)
        send(post(F + "/" + d.fmea() + "/nodes"), "{\"parentId\":" + d.item() + ",\"nodeKind\":\"STEP\",\"nodeNm\":\"x\"}", b)
                .andExpect(status().isNotFound());
        send(put("/api/apqp/fmea-risks/" + d.risk()), "{\"occ\":1,\"det\":1,\"rowVer\":0}", b).andExpect(status().isNotFound());
        send(delete(F + "/" + d.fmea()).param("rowVer", "0"), "", b).andExpect(status().isNotFound());
        // B사 FMEA 에 A사 규칙표·A사 라우팅·A사 특성·A사 불량 유형
        send(post(F), "{\"itemCd\":\"ZT-BRKT\",\"title\":\"x\",\"apSetId\":" + set + "}", b).andExpect(jsonPath("$.errors[0].field").value("apSetId"));
        send(post(F), "{\"itemCd\":\"ZT-BRKT\",\"title\":\"x\",\"routingId\":" + routingId + "}", b)
                .andExpect(jsonPath("$.errors[0].field").value("routingId"));
        long bFmea = id(send(post(F), "{\"itemCd\":\"ZT-BRKT\",\"routingId\":" + fb.routing() + ",\"title\":\"B 사 PFMEA\",\"fromRouting\":true}", b));
        List<Map<String, Object>> bNodes = JsonPath.read(mvc.perform(get(F + "/" + bFmea).with(b)).andReturn().getResponse()
                .getContentAsString(), "$.data.nodes");
        long bItem = nodeId(bNodes, "ITEM", null);
        long bStep = nodeId(bNodes, "STEP", fb.step10());
        send(post(F + "/" + bFmea + "/funcs"), "{\"nodeId\":" + bItem + ",\"funcText\":\"x\",\"charId\":" + prodChar + "}", b)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_CHAR_TARGET"));
        long bFunc = id(send(post(F + "/" + bFmea + "/funcs"), "{\"nodeId\":" + bStep + ",\"funcText\":\"x\"}", b));
        send(post(F + "/" + bFmea + "/fails"), "{\"funcId\":" + bFunc + ",\"failText\":\"x\",\"defectId\":" + defectId + "}", b)
                .andExpect(jsonPath("$.errors[0].field").value("defectId"));
        // 직접 SQL: B사 범위에서 A사 FMEA 노드·A사 특성·A사 규칙표 → 트리거·RLS
        assertThat(catchThrowable(() -> accounts.exec(other, """
                insert into apqp_fmea_node (comp_cd, fmea_id, parent_id, node_kind, node_nm, created_by, updated_by)
                values (?, ?, ?, 'STEP', 'x', 't', 't')""", other, d.fmea(), d.item()))).hasMessageContaining("is not of company");
        assertThat(catchThrowable(() -> accounts.exec(other, """
                insert into apqp_fmea_func (comp_cd, fmea_id, node_id, func_text, char_id, created_by, updated_by)
                values (?, ?, ?, 'x', ?, 't', 't')""", other, bFmea, bItem, prodChar))).hasMessageContaining("is not of company");
        assertThat(catchThrowable(() -> accounts.exec(other, "update apqp_fmea set ap_set_id = ? where fmea_id = ?", set, bFmea)))
                .hasMessageContaining("ap set ");
        assertThat(accounts.exec(other, "update apqp_fmea set title = 'x' where fmea_id = ?", d.fmea())).isZero();
        assertThat(accounts.exec(other, "delete from apqp_ap_rule where set_id = ?", set)).isZero();
    }
}
