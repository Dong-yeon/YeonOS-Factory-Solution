package io.smarthappy.module.master.equip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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

/** 공장·장비 계층 (P1 4a, docs/19) — 부모 규칙·순환·깊이·사용 중지·공장 고정·A/B사·권한, 직접 SQL 도 트리거가 막는다 */
@AutoConfigureMockMvc
class EquipHierTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String HIER = "/api/master/equip-hier";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("EQ");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-plant", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-equip-hier", "SEARCH", "SAVE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions create(String plant, Long parent, String type, String cd) throws Exception {
        return mvc.perform(json(post(HIER), "{\"plantCd\":\"" + plant + "\",\"parentId\":" + parent + ",\"nodeType\":\""
                + type + "\",\"nodeCd\":\"" + cd + "\",\"nodeNm\":\"" + cd + " 이름\"}").with(admin));
    }

    private long node(String plant, Long parent, String type, String cd) throws Exception {
        MvcResult r = create(plant, parent, type, cd).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int ver(long id) {
        return accounts.query(comp, "select row_ver from mst_equip_hier where node_id = ?", Integer.class, id);
    }

    private ResultActions update(long id, Long parent, String cd, String useYn) throws Exception {
        return mvc.perform(json(put(HIER + "/" + id), "{\"parentId\":" + parent + ",\"nodeCd\":\"" + cd
                + "\",\"nodeNm\":\"" + cd + " 이름\",\"useYn\":\"" + useYn + "\",\"rowVer\":" + ver(id) + "}").with(admin));
    }

    // ---------- 정상 ----------

    @Test
    void 구역_라인_셀_창고_로케이션_트리를_경로_순서로() throws Exception {
        long body = node("P1", null, "AREA", "body");
        long l2 = node("P1", body, "LINE", "L2");
        node("P1", l2, "CELL", "C3");
        long l1 = node("P1", body, "LINE", "L1");
        node("P1", l1, "CELL", "C1");
        long wh = node("P1", null, "WH", "RM-WH");
        long rack = node("P1", wh, "LOC", "A-01");
        node("P1", rack, "LOC", "A-01-03");
        node("P1", body, "WH", "LINESIDE");                   // 구역 아래 라인사이드 창고
        MvcResult r = mvc.perform(get(HIER).param("plantCd", "P1").with(admin)).andExpect(status().isOk()).andReturn();
        String b = r.getResponse().getContentAsString();
        List<String> paths = JsonPath.read(b, "$.data[*].path");
        assertThat(paths).containsExactly("P1/BODY", "P1/BODY/L1", "P1/BODY/L1/C1", "P1/BODY/L2", "P1/BODY/L2/C3",
                "P1/BODY/LINESIDE", "P1/RM-WH", "P1/RM-WH/A-01", "P1/RM-WH/A-01/A-01-03");
        assertThat(JsonPath.<List<Integer>>read(b, "$.data[*].lvl")).containsExactly(1, 2, 3, 2, 3, 2, 1, 2, 3);
        // 다른 공장은 비어 있다, 같은 코드도 다른 공장이면 가능
        mvc.perform(get(HIER).param("plantCd", "P2").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        node("P2", null, "AREA", "BODY");
    }

    @Test
    void 부모_규칙_위반은_칸_오류() throws Exception {
        long area = node("P1", null, "AREA", "A1");
        long line = node("P1", area, "LINE", "L1");
        long cell = node("P1", line, "CELL", "C1");
        long wh = node("P1", null, "WH", "W1");
        create("P1", null, "LINE", "X1").andExpect(status().isBadRequest())       // 공장 바로 아래 라인
                .andExpect(jsonPath("$.errors[0].code").value("PARENT_TYPE"));
        create("P1", cell, "CELL", "X2").andExpect(jsonPath("$.errors[0].code").value("PARENT_TYPE"));
        create("P1", line, "WH", "X3").andExpect(jsonPath("$.errors[0].code").value("PARENT_TYPE"));
        create("P1", wh, "LINE", "X4").andExpect(jsonPath("$.errors[0].code").value("PARENT_TYPE"));
        create("P1", null, "LOC", "X5").andExpect(jsonPath("$.errors[0].code").value("PARENT_TYPE"));
        create("P1", null, "SITE", "X6").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        create("P1", null, "AREA", "a b").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        create("P1", null, "AREA", "a1").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));   // 대문자로 같은 코드
        create("P1", 999999999L, "LINE", "X7").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        assertThat(accounts.query(comp, "select count(*) from mst_equip_hier", Integer.class)).isEqualTo(4);
    }

    @Test
    void 이동_자기하위_아래로는_순환_다른공장_부모는_불가_유형은_고정() throws Exception {
        long w1 = node("P1", null, "WH", "W1");
        long a = node("P1", w1, "LOC", "LA");
        long b = node("P1", a, "LOC", "LB");
        long c = node("P1", b, "LOC", "LC");
        update(a, c, "LA", "Y").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
        long w2 = node("P1", null, "WH", "W2");
        update(b, w2, "LB", "Y").andExpect(status().isOk());                       // 하위(LC)와 함께 이동
        mvc.perform(get(HIER).param("plantCd", "P1").with(admin))
                .andExpect(jsonPath("$.data[?(@.nodeCd=='LC')].path").value(org.hamcrest.Matchers.hasItem("P1/W2/LB/LC")));
        long otherPlant = node("P2", null, "WH", "PW");
        update(c, otherPlant, "LC", "Y").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(json(put(HIER + "/" + c), "{\"parentId\":" + b + ",\"nodeType\":\"WH\",\"nodeCd\":\"LC\",\"nodeNm\":\"x\",\"rowVer\":"
                + ver(c) + "}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("FIXED"));
    }

    @Test
    void 깊이_10단_초과_불가() throws Exception {
        Long parent = node("P1", null, "WH", "D0");
        for (int i = 1; i < 10; i++) {
            parent = node("P1", parent, "LOC", "D" + i);                         // 10단까지 허용
        }
        create("P1", parent, "LOC", "D10").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DEPTH"));
    }

    @Test
    void 사용중지_하위가_사용중이면_거부_중지된_부모_아래_추가_불가_공장도() throws Exception {
        long area = node("P1", null, "AREA", "A1");
        long line = node("P1", area, "LINE", "L1");
        update(area, null, "A1", "N").andExpect(jsonPath("$.errors[0].code").value("CHILDREN_ACTIVE"));
        update(line, area, "L1", "N").andExpect(status().isOk());
        update(area, null, "A1", "N").andExpect(status().isOk());
        create("P1", area, "LINE", "L9").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        update(line, area, "L1", "Y").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));   // 부모가 중지
        // 기본 조회는 사용 중만, withUnused 면 모두
        mvc.perform(get(HIER).param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(HIER).param("plantCd", "P1").param("withUnused", "true").with(admin))
                .andExpect(jsonPath("$.data.length()").value(2));
        // 공장: 사용 중 노드가 있으면 사용 중지 거부
        node("P1", null, "WH", "W1");
        int pv = accounts.query(comp, "select row_ver from sys_plant where plant_cd = 'P1'", Integer.class);
        mvc.perform(json(put("/api/master/plants/P1"), "{\"plantNm\":\"1공장\",\"useYn\":\"N\",\"rowVer\":" + pv + "}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("CHILDREN_ACTIVE"));
    }

    @Test
    void 직접_SQL도_트리거가_막는다_삭제_공장이동_순환_부모규칙() throws Exception {
        long w = node("P1", null, "WH", "W1");
        long a = node("P1", w, "LOC", "LA");
        long b = node("P1", a, "LOC", "LB");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from mst_equip_hier where node_id = ?", b))))
                .contains("42501");                                                 // 앱 계정 DELETE 권한 없음
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_equip_hier set parent_id = ? where node_id = ?", b, a)))).contains("ck_mst_equip_hier_cycle");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_equip_hier set plant_cd = 'P2' where node_id = ?", b)))).contains("ck_mst_equip_hier_plant_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_equip_hier set node_type = 'LINE' where node_id = ?", b)))).contains("ck_mst_equip_hier_type_fixed");   // V023: 유형 불변(QA #2)
    }

    @Test
    void 공장_관리_등록_수정_중복_시간대() throws Exception {
        mvc.perform(json(post("/api/master/plants"), "{\"plantCd\":\"p3\",\"plantNm\":\"3공장\",\"timeZone\":\"Asia/Ho_Chi_Minh\"}")
                .with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value("P3"));
        mvc.perform(json(post("/api/master/plants"), "{\"plantCd\":\"P3\",\"plantNm\":\"x\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mvc.perform(json(post("/api/master/plants"), "{\"plantCd\":\"P4\",\"plantNm\":\"x\",\"timeZone\":\"Mars/Base\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        mvc.perform(json(put("/api/master/plants/P3"), "{\"plantNm\":\"3공장(베트남)\",\"sortNo\":3,\"rowVer\":0}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/master/plants/P3"), "{\"plantNm\":\"옛 버전\",\"rowVer\":0}").with(admin))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/master/plants").with(admin)).andExpect(jsonPath("$.data[*].plantCd")
                .value(org.hamcrest.Matchers.hasItems("P1", "P2", "P3")));
    }

    @Test
    void B사_사용자는_A사_공장_노드를_못보고_못고친다_권한() throws Exception {
        long area = node("P1", null, "AREA", "A1");
        String other = accounts.newComp("EQ");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-equip-hier", "SEARCH", "SAVE");
        accounts.addPlant(other, "P1", "B사 1공장");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(HIER).param("plantCd", "P1").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // B사 노드의 부모로 A사 노드 id
        mvc.perform(json(post(HIER), "{\"plantCd\":\"P1\",\"parentId\":" + area + ",\"nodeType\":\"LINE\",\"nodeCd\":\"X\",\"nodeNm\":\"x\"}")
                .with(b)).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(json(put(HIER + "/" + area), "{\"nodeCd\":\"A1\",\"nodeNm\":\"탈취\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        // 직접 SQL: B사 범위에서 A사 노드를 부모로
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LINE', 'X', 'x', 't', 't')""", other, area)))).contains("fk_mst_equip_hier_parent_plant");
        assertThat(accounts.query(comp, "select node_nm from mst_equip_hier where node_id = ?", String.class, area)).isEqualTo("A1 이름");
        // 권한: 계층 조회만 → 저장 403, 공장 관리 권한 없음 → 공장 목록 403(계층 화면의 공장 선택은 됨)
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-equip-hier", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(json(post(HIER), "{\"plantCd\":\"P1\",\"nodeType\":\"AREA\",\"nodeCd\":\"Z\",\"nodeNm\":\"z\"}").with(r))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/master/plants").with(r)).andExpect(status().isForbidden());
        mvc.perform(get(HIER + "/plants").with(r)).andExpect(status().isOk());
    }
}
