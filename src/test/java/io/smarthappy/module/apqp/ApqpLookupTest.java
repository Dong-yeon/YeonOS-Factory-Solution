package io.smarthappy.module.apqp;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * APQP 조회 쪽 — 고르기 목록(품목·라우팅·특성·불량 유형·사용자)과 목록 grid 의 계산 칸 정렬·표시 칸.
 * ApqpFmeaTest 가 덮지 않는 SQL 을 API 로 고정한다(docs/41 4.2 — MyBatis 구현과 jOOQ 구현에서 같은 결과를 확인한 시험).
 */
@AutoConfigureMockMvc
class ApqpLookupTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String F = "/api/apqp/fmeas";
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
    long itemId;
    long routingId;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AL");
        long u = accounts.newUser(comp, "qe1", PW);
        accounts.grant(comp, u, "APQP", "apqp-fmea", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "APQP", "apqp-ap", "SEARCH", "SAVE");
        qe = accounts.as(comp, u, "qe1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'ZT-BRKT', 'ZT 브래킷', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'ZT-BOLT', 'ZT 100% 볼트', 'FG', 'MAKE', 'EA', 't', 't')""",
                comp, comp);
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'ZWELD', 'ZT 용접', 'WELD', 't', 't'), (?, 'ZPAINT', 'ZT 도장', 'PAINT', 't', 't')""", comp, comp);
        itemId = accounts.query(comp, "select item_id from mst_item where item_cd = 'ZT-BRKT'", Long.class);
        routingId = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, ?, 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't') returning routing_id""", Long.class, comp, itemId);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ZWELD'), 't', 't')""", comp, routingId);
        // 제품 특성 1, 라우팅 공정의 공정 특성 1, 라우팅 밖 공정의 공정 특성 1(안 나와야), 쓰지 않는 제품 특성 1(안 나와야)
        accounts.exec(comp, """
                insert into qms_char (comp_cd, char_type, item_id, proc_id, char_no, char_nm, data_type, special_cls, use_yn, created_by, updated_by)
                values (?, 'PRODUCT', ?, null, 'ZC1', 'ZT 강도', 'ATTRIBUTE', 'CC', 'Y', 't', 't'),
                       (?, 'PRODUCT', ?, null, 'ZC9', 'ZT 안 씀', 'ATTRIBUTE', 'NONE', 'N', 't', 't'),
                       (?, 'PROCESS', null, (select proc_id from mst_proc where proc_cd = 'ZWELD'), 'ZP1', 'ZT 전류', 'ATTRIBUTE', 'NONE', 'Y', 't', 't'),
                       (?, 'PROCESS', null, (select proc_id from mst_proc where proc_cd = 'ZPAINT'), 'ZP2', 'ZT 막 두께', 'ATTRIBUTE', 'NONE', 'Y', 't', 't')""",
                comp, itemId, comp, itemId, comp, comp);
        accounts.exec(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'ZD1', 'ZT 미용착', 'WELD', 't', 't')""", comp);
    }

    private long id(String url, String body) throws Exception {
        String s = mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(qe))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    @Test
    void 고르기_목록() throws Exception {
        // 품목: 대소문자 무시 검색, % 는 글자 그대로(이스케이프)
        mvc.perform(get("/api/apqp/items").param("keyword", "zt-b").with(qe))
                .andExpect(jsonPath("$.data[*].itemCd").value(contains("ZT-BOLT", "ZT-BRKT")));
        mvc.perform(get("/api/apqp/items").param("keyword", "0%").with(qe)).andExpect(jsonPath("$.data[*].itemCd").value(contains("ZT-BOLT")));
        mvc.perform(get("/api/apqp/items/" + itemId + "/routings").with(qe))
                .andExpect(jsonPath("$.data[0].routingId").value(routingId)).andExpect(jsonPath("$.data[0].plantCd").value("P1"))
                .andExpect(jsonPath("$.data[0].altNo").value(1)).andExpect(jsonPath("$.data[0].rev").value("A0"));
        mvc.perform(get("/api/apqp/defects").with(qe)).andExpect(jsonPath("$.data[*].defectCd").value(contains("ZD1")));
        mvc.perform(get("/api/apqp/users").with(qe)).andExpect(jsonPath("$.data[*].loginId").value(hasItem("qe1")));

        // 특성: 제품 특성(사용 중만) 먼저, 그다음 공정 특성(이 라우팅의 공정만)
        long set = id("/api/apqp/ap-sets", "{\"setCd\":\"ZTL\",\"setNm\":\"ZT 예시(핸드북 아님)\",\"useYn\":\"Y\",\"rules\":" + RULES + "}");
        long fmea = id(F, "{\"itemCd\":\"ZT-BRKT\",\"routingId\":" + routingId + ",\"title\":\"ZT 가\",\"apSetId\":" + set + ",\"fromRouting\":true}");
        mvc.perform(get(F + "/" + fmea + "/chars").with(qe))
                .andExpect(jsonPath("$.data[*].charNo").value(contains("ZC1", "ZP1")))
                .andExpect(jsonPath("$.data[0].specialCls").value("CC"))
                .andExpect(jsonPath("$.data[1].procCd").value("ZWELD"));
        // 구조 노드에 라우팅 단계의 공정이 붙어 나온다
        mvc.perform(get(F + "/" + fmea).with(qe))
                .andExpect(jsonPath("$.data.nodes[?(@.nodeKind == 'STEP')].procCd").value(contains("ZWELD")))
                .andExpect(jsonPath("$.data.nodes[?(@.nodeKind == 'STEP')].opSeq").value(contains(10)));

        // A사로 B사: 다른 회사 사용자에게는 이 회사 품목·라우팅·불량 유형이 없다
        String other = accounts.newComp("AM");
        long ou = accounts.newUser(other, "qe2", PW);
        accounts.grant(other, ou, "APQP", "apqp-fmea", "SEARCH");
        RequestPostProcessor b = accounts.as(other, ou, "qe2");
        mvc.perform(get("/api/apqp/items").param("keyword", "ZT").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/apqp/items/" + itemId + "/routings").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/apqp/defects").with(b)).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 목록_계산_칸_정렬과_표시() throws Exception {
        long set = id("/api/apqp/ap-sets", "{\"setCd\":\"ZTL\",\"setNm\":\"ZT 예시(핸드북 아님)\",\"useYn\":\"Y\",\"rules\":" + RULES + "}");
        long a = id(F, "{\"itemCd\":\"ZT-BRKT\",\"routingId\":" + routingId + ",\"title\":\"ZT 가\",\"apSetId\":" + set + ",\"fromRouting\":true}");
        id(F, "{\"itemCd\":\"ZT-BRKT\",\"title\":\"ZT 나\",\"apSetId\":" + set + "}");
        // a 에만 AP H 위험행 하나(S 9)
        List<Map<String, Object>> nodes = JsonPath.read(mvc.perform(get(F + "/" + a).with(qe)).andReturn().getResponse().getContentAsString(),
                "$.data.nodes");
        long item = ((Number) nodes.stream().filter(n -> "ITEM".equals(n.get("nodeKind"))).findFirst().orElseThrow().get("nodeId")).longValue();
        long step = ((Number) nodes.stream().filter(n -> "STEP".equals(n.get("nodeKind"))).findFirst().orElseThrow().get("nodeId")).longValue();
        long el = id(F + "/" + a + "/nodes", "{\"parentId\":" + step + ",\"nodeKind\":\"ELEMENT\",\"nodeNm\":\"ZT 용접기\",\"m4\":\"MACHINE\"}");
        long fi = id(F + "/" + a + "/funcs", "{\"nodeId\":" + item + ",\"funcText\":\"고정\"}");
        long fs = id(F + "/" + a + "/funcs", "{\"nodeId\":" + step + ",\"funcText\":\"용접\"}");
        long fe = id(F + "/" + a + "/funcs", "{\"nodeId\":" + el + ",\"funcText\":\"전류 유지\"}");
        long e = id(F + "/" + a + "/fails", "{\"funcId\":" + fi + ",\"failText\":\"이탈\",\"severity\":9}");
        long m = id(F + "/" + a + "/fails", "{\"funcId\":" + fs + ",\"failText\":\"강도 부족\"}");
        long c = id(F + "/" + a + "/fails", "{\"funcId\":" + fe + ",\"failText\":\"전류 저하\"}");
        id(F + "/" + a + "/chains", "{\"fmId\":" + m + ",\"feId\":" + e + "}");
        id(F + "/" + a + "/risks", "{\"fmId\":" + m + ",\"fcId\":" + c + ",\"occ\":3,\"det\":4}");

        mvc.perform(get(F + "/grid").param("sort", "highCnt,desc").with(qe)).andExpect(jsonPath("$.list[*].title").value(contains("ZT 가", "ZT 나")))
                .andExpect(jsonPath("$.list[0].highCnt").value(1)).andExpect(jsonPath("$.list[0].riskCnt").value(1))
                .andExpect(jsonPath("$.list[0].routingText").value("P1 · 1 · A0"))
                .andExpect(jsonPath("$.list[0].apSetCd").value("ZTL"));
        mvc.perform(get(F + "/grid").param("sort", "highCnt,asc").with(qe)).andExpect(jsonPath("$.list[*].title").value(contains("ZT 나", "ZT 가")));
        mvc.perform(get(F + "/grid").param("sort", "title,desc").with(qe)).andExpect(jsonPath("$.list[*].title").value(contains("ZT 나", "ZT 가")));
        mvc.perform(get(F + "/grid").param("sort", "title,asc").param("size", "1").param("page", "2").with(qe))
                .andExpect(jsonPath("$.list[*].title").value(contains("ZT 나")));
    }
}
