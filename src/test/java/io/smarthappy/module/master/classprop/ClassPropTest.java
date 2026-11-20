package io.smarthappy.module.master.classprop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 품목 클래스·클래스 속성·attrs (P1 7b, docs/22 5b) — 정의 형식별 검증·고정 칸, 값 형식·필수·선택지·자릿수,
 * 사용 중지 속성, 나중에 추가한 필수 속성, 설비 클래스 변경, 품목 저장이 attrs 를 건드리지 않음, 직접 SQL, A/B사, 권한.
 */
@AutoConfigureMockMvc
class ClassPropTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/master/class-props";
    private static final String I = "/api/master/items";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long fg1;
    long coil;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("CP");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-class-prop", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-item", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-equip", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        fg1 = item(comp, "RM1");
        coil = id(postJson(P + "/item-classes", "{\"classCd\":\"coil\",\"classNm\":\"코일\"}"));
        prop("ITEM", coil, "{\"propCd\":\"thickness\",\"propNm\":\"두께\",\"dataType\":\"NUM\",\"scale\":2,\"unitNm\":\"mm\",\"requiredYn\":\"Y\"}");
        prop("ITEM", coil, "{\"propCd\":\"GRADE\",\"propNm\":\"강종\",\"dataType\":\"LIST\",\"options\":[\"SPFC590\",\" SAPH440 \",\"SPFC590\",\"\"]}");
        prop("ITEM", coil, "{\"propCd\":\"MILL_DATE\",\"propNm\":\"제조일\",\"dataType\":\"DATE\"}");
        prop("ITEM", coil, "{\"propCd\":\"OILED\",\"propNm\":\"방청유\",\"dataType\":\"BOOL\"}");
        prop("ITEM", coil, "{\"propCd\":\"MAKER\",\"propNm\":\"제철소\",\"dataType\":\"TEXT\"}");
    }

    private long item(String c, String cd) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', 'KG', 't', 't') returning item_id""", Long.class, c, cd, cd + " 품목");
    }

    private long prop(String type, long classId, String body) throws Exception {
        return id(postJson(P, body.replaceFirst("\\{", "{\"classType\":\"" + type + "\",\"classId\":" + classId + ",")));
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

    private int itemVer(long itemId) {
        return accounts.query(comp, "select row_ver from mst_item where item_id = ?", Integer.class, itemId);
    }

    private ResultActions saveAttrs(long itemId, Long classId, String attrs) throws Exception {
        return putJson(I + "/" + itemId + "/attrs", "{\"classId\":" + classId + ",\"attrs\":" + attrs + ",\"rowVer\":" + itemVer(itemId) + "}");
    }

    // ---------- 정의 ----------

    @Test
    void 속성정의_형식별_검증_선택지_정리_코드형식_고정칸() throws Exception {
        mvc.perform(get(P).param("classType", "ITEM").param("classId", String.valueOf(coil)).with(admin))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[?(@.propCd=='GRADE')].options[*]").value(containsInAnyOrder("SPFC590", "SAPH440")));
        postJson(P + "/item-classes", "{\"classCd\":\"COIL\",\"classNm\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        prop0("{\"propCd\":\"thickness\",\"propNm\":\"x\",\"dataType\":\"TEXT\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        prop0("{\"propCd\":\"1AB\",\"propNm\":\"\",\"dataType\":\"JSON\"}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FORMAT", "REQUIRED", "CODE")));
        prop0("{\"propCd\":\"COLOR\",\"propNm\":\"색\",\"dataType\":\"LIST\",\"options\":[\" \"]}")
                .andExpect(jsonPath("$.errors[0].field").value("options"));
        prop0("{\"propCd\":\"W\",\"propNm\":\"폭\",\"dataType\":\"NUM\",\"scale\":7}").andExpect(jsonPath("$.errors[0].field").value("scale"));
        long thick = accounts.query(comp, "select prop_id from mst_class_prop where prop_cd = 'THICKNESS'", Long.class);
        putJson(P + "/" + thick, "{\"propCd\":\"T2\",\"propNm\":\"두께\",\"dataType\":\"TEXT\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FIXED", "FIXED")));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_class_prop set data_type = 'TEXT', scale = null where prop_id = ?", thick)))).contains("ck_mst_class_prop_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from mst_class_prop where prop_id = ?", thick))))
                .contains("42501");
    }

    private ResultActions prop0(String body) throws Exception {
        return postJson(P, body.replaceFirst("\\{", "{\"classType\":\"ITEM\",\"classId\":" + coil + ","));
    }

    // ---------- 값 ----------

    @Test
    void 값_저장_형식대로_정리_칸마다_오류_필수() throws Exception {
        saveAttrs(fg1, coil, "{\"THICKNESS\":\"1.60\",\"GRADE\":\"SPFC590\",\"MILL_DATE\":\"2026-09-01\",\"OILED\":true,\"MAKER\":\"  가상제철 \"}")
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select attrs::text from mst_item where item_id = ?", String.class, fg1))
                .isEqualTo("{\"GRADE\": \"SPFC590\", \"MAKER\": \"가상제철\", \"OILED\": true, \"MILL_DATE\": \"2026-09-01\", \"THICKNESS\": 1.6}");
        mvc.perform(get(I + "/" + fg1 + "/attrs").with(admin)).andExpect(jsonPath("$.data.classId").value(coil))
                .andExpect(jsonPath("$.data.attrs.THICKNESS").value(1.6)).andExpect(jsonPath("$.data.props.length()").value(5));
        // 칸마다: 자릿수·숫자 아님·없는 날짜·참거짓 아님·선택지 밖·없는 속성 + 필수 빠짐
        saveAttrs(fg1, coil, "{\"THICKNESS\":\"1.605\",\"GRADE\":\"SS400\",\"MILL_DATE\":\"2026-02-30\",\"OILED\":\"Y\",\"COLOR\":\"red\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("attrs.THICKNESS", "attrs.GRADE", "attrs.MILL_DATE",
                        "attrs.OILED", "attrs.COLOR")));
        saveAttrs(fg1, coil, "{\"THICKNESS\":\"abc\"}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        saveAttrs(fg1, coil, "{\"GRADE\":\"SAPH440\"}").andExpect(jsonPath("$.errors[0].field").value("attrs.THICKNESS"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 클래스 없음에 값 / 클래스 비우기(값도 비움) / 낡은 버전
        saveAttrs(fg1, null, "{\"MAKER\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("NO_CLASS"));
        saveAttrs(fg1, null, "{\"MAKER\":null}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select coalesce(item_class_id, 0) || attrs::text from mst_item where item_id = ?", String.class, fg1))
                .isEqualTo("0{}");
        putJson(I + "/" + fg1 + "/attrs", "{\"classId\":" + coil + ",\"attrs\":{\"THICKNESS\":1},\"rowVer\":0}").andExpect(status().isConflict());
    }

    @Test
    void 사용중지_속성은_값만_남고_못바꾼다_나중에_추가한_필수는_다음_저장때_품목저장은_attrs를_건드리지않는다() throws Exception {
        saveAttrs(fg1, coil, "{\"THICKNESS\":2,\"MAKER\":\"A제철\"}").andExpect(status().isOk());
        long maker = accounts.query(comp, "select prop_id from mst_class_prop where prop_cd = 'MAKER'", Long.class);
        putJson(P + "/" + maker, "{\"propNm\":\"제철소\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(I + "/" + fg1 + "/attrs").with(admin)).andExpect(jsonPath("$.data.props.length()").value(5));   // 값 있어 보임
        saveAttrs(fg1, coil, "{\"THICKNESS\":2,\"MAKER\":\"A제철\"}").andExpect(status().isOk());                    // 그대로 OK
        saveAttrs(fg1, coil, "{\"THICKNESS\":2,\"MAKER\":\"B제철\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 필수 속성을 나중에 추가 — 품목 기본 저장은 그대로 되고(attrs 안 건드림), 속성 저장 때 요구
        prop("ITEM", coil, "{\"propCd\":\"WIDTH\",\"propNm\":\"폭\",\"dataType\":\"NUM\",\"scale\":0,\"requiredYn\":\"Y\"}");
        putJson(I + "/" + fg1, "{\"itemCd\":\"RM1\",\"itemNm\":\"이름 변경\",\"itemType\":\"RM\",\"procType\":\"BUY\",\"unitCd\":\"KG\",\"rowVer\":"
                + itemVer(fg1) + "}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select attrs ->> 'MAKER' from mst_item where item_id = ?", String.class, fg1)).isEqualTo("A제철");
        saveAttrs(fg1, coil, "{\"THICKNESS\":2,\"MAKER\":\"A제철\"}").andExpect(jsonPath("$.errors[0].field").value("attrs.WIDTH"));
        // 사용 중지 클래스로는 새로 못 바꾼다
        long bolt = id(postJson(P + "/item-classes", "{\"classCd\":\"BOLT\",\"classNm\":\"볼트\",\"useYn\":\"N\"}"));
        saveAttrs(fg1, bolt, "{}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
    }

    @Test
    void 설비_속성과_클래스변경_속성이_안맞으면_칸오류() throws Exception {
        accounts.addPlant(comp, "P1", "1공장");
        long press = accounts.query(comp, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'PRESS', '프레스', 't', 't') returning class_id""", Long.class, comp);
        long weld = accounts.query(comp, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'WELD', '용접기', 't', 't') returning class_id""", Long.class, comp);
        prop("EQUIP", press, "{\"propCd\":\"MAX_TON\",\"propNm\":\"최대 톤수\",\"dataType\":\"NUM\",\"scale\":0,\"requiredYn\":\"Y\"}");
        long eq = accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', 'PR-1', '프레스 1', ?, 't', 't') returning equip_id""", Long.class, comp, press);
        // 필수 속성이 있는 클래스로도 설비는 만들 수 있다(속성은 나중에) — 속성 값을 저장할 때 필수
        int v0 = accounts.query(comp, "select row_ver from mst_equip where equip_id = ?", Integer.class, eq);
        putJson("/api/master/equips/" + eq + "/attrs", "{\"attrs\":{},\"rowVer\":" + v0 + "}")
                .andExpect(jsonPath("$.errors[0].field").value("attrs.MAX_TON"));
        mvc.perform(get(P + "/classes").param("classType", "EQUIP").with(admin))
                .andExpect(jsonPath("$.data[?(@.classCd=='PRESS')].propCnt").value(org.hamcrest.Matchers.hasItem(1)));
        int ver = accounts.query(comp, "select row_ver from mst_equip where equip_id = ?", Integer.class, eq);
        putJson("/api/master/equips/" + eq + "/attrs", "{\"attrs\":{\"MAX_TON\":\"1200\"},\"rowVer\":" + ver + "}").andExpect(status().isOk());
        // 숫자는 일반 표기 글자로 내려 준다(7단계 QA 결함 #2 — 브라우저 Double 손실 방지)
        mvc.perform(get("/api/master/equips/" + eq + "/attrs").with(admin)).andExpect(jsonPath("$.data.attrs.MAX_TON").value("1200"));
        // 설비 폼에서 클래스를 용접기로 — MAX_TON 이 용접기에 없다
        ver = accounts.query(comp, "select row_ver from mst_equip where equip_id = ?", Integer.class, eq);
        putJson("/api/master/equips/" + eq, "{\"plantCd\":\"P1\",\"equipCd\":\"PR-1\",\"equipNm\":\"프레스 1\",\"classId\":" + weld
                + ",\"rowVer\":" + ver + "}").andExpect(jsonPath("$.errors[0].field").value("classId"))
                .andExpect(jsonPath("$.errors[0].code").value("ATTRS_CLASS"));
        // 설비 속성 API 는 클래스와 새 클래스 값을 한 번에 바꾼다(7단계 QA 결함 #3) — 새 클래스에 없는 속성은 칸 오류, 비우면 옮겨진다
        putJson("/api/master/equips/" + eq + "/attrs", "{\"classId\":" + weld + ",\"attrs\":{\"MAX_TON\":1000},\"rowVer\":" + ver + "}")
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PROP"));
        putJson("/api/master/equips/" + eq + "/attrs", "{\"classId\":" + weld + ",\"attrs\":{},\"rowVer\":" + ver + "}")
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select class_id from mst_equip where equip_id = ?", Long.class, eq)).isEqualTo(weld);
    }

    // ---------- 직접 SQL · A/B사 · 권한 ----------

    @Test
    void 직접SQL_A사B사_권한() throws Exception {
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_item set item_class_id = ?, attrs = '{\"NOPE\":1,\"THICKNESS\":1}' where item_id = ?", coil, fg1))))
                .contains("ck_attrs_unknown");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_item set item_class_id = ?, attrs = '{\"GRADE\":\"SPFC590\"}' where item_id = ?", coil, fg1))))
                .contains("ck_attrs_required");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_item set item_class_id = ?, attrs = '{\"THICKNESS\":\"1.6\"}' where item_id = ?", coil, fg1))))
                .contains("ck_attrs_value");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_item set attrs = '{\"THICKNESS\":1}' where item_id = ?", fg1)))).contains("ck_attrs_no_class");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_item set attrs = '[]' where item_id = ?", fg1)))).contains("ck_attrs_object");
        // B사
        String other = accounts.newComp("CP");
        long b = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, b, "MASTER", "mst-class-prop", "SEARCH", "SAVE");
        accounts.grant(other, b, "MASTER", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor bu = accounts.as(other, b, "admin1");
        long bItem = item(other, "RM1");
        mvc.perform(get(P + "/classes").param("classType", "ITEM").with(bu)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(P).param("classType", "ITEM").param("classId", String.valueOf(coil)).with(bu)).andExpect(status().isNotFound());
        mvc.perform(json(post(P), "{\"classType\":\"ITEM\",\"classId\":" + coil + ",\"propCd\":\"X\",\"propNm\":\"x\",\"dataType\":\"TEXT\"}")
                .with(bu)).andExpect(status().isNotFound());
        mvc.perform(get(I + "/" + fg1 + "/attrs").with(bu)).andExpect(status().isNotFound());
        mvc.perform(json(put(I + "/" + bItem + "/attrs"), "{\"classId\":" + coil + ",\"attrs\":{},\"rowVer\":0}").with(bu))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into mst_class_prop (comp_cd, class_type, class_id, prop_cd, prop_nm, data_type, created_by, updated_by)
                values (?, 'ITEM', ?, 'X', 'x', 'TEXT', 't', 't')""", other, coil)))).contains("fk_mst_class_prop_class");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "update mst_item set item_class_id = ? where item_id = ?", coil, bItem)))).contains("fk_mst_item_class_comp");
        // 권한: 품목 조회만 → 속성 저장 403, 정의 화면 API 403, 품목 화면 클래스·속성 목록 200
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-item", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(json(put(I + "/" + fg1 + "/attrs"), "{\"classId\":null,\"attrs\":{},\"rowVer\":0}").with(r)).andExpect(status().isForbidden());
        mvc.perform(get(P + "/classes").param("classType", "ITEM").with(r)).andExpect(status().isForbidden());
        mvc.perform(get(I + "/classes").with(r)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(I + "/class-props").param("classId", String.valueOf(coil)).with(r)).andExpect(jsonPath("$.data.length()").value(5));
    }
}
