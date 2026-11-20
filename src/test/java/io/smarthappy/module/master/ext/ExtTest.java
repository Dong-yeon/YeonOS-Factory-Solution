package io.smarthappy.module.master.ext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
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
 * 외부 시스템·외부 ID·코드 매핑·ERP 주인 필드 잠금 (P1 7a, docs/22) — 시스템 안 1:1, 대소문자(외부 코드 유지·코드 매핑 무시),
 * 사용 중지 후 다시, 내부 코드 검사, 정책 PULL 잠금, 직접 SQL, A/B사, 권한.
 */
@AutoConfigureMockMvc
class ExtTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String S = "/api/master/ext-systems";
    private static final String I = "/api/master/items";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long fg1;
    long fg2;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("EX");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-ext-system", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-item", "SEARCH", "SAVE", "DELETE");
        admin = accounts.as(comp, userId, "admin1");
        fg1 = item(comp, "FG1");
        fg2 = item(comp, "FG2");
        accounts.exec(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C001', '가상 완성차', 'SALES', 't', 't')""", comp);
    }

    private long item(String c, String cd) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't') returning item_id""", Long.class, c, cd, cd + " 품목");
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

    private long system(String cd, String type) throws Exception {
        return id(postJson(S, "{\"systemCd\":\"" + cd + "\",\"systemNm\":\"" + cd + " 시스템\",\"systemType\":\"" + type + "\"}"));
    }

    private ResultActions extId(long itemId, String sys, String code) throws Exception {
        return postJson(I + "/" + itemId + "/ext-ids", "{\"systemCd\":\"" + sys + "\",\"externalId\":\"" + code + "\"}");
    }

    // ---------- 시스템 ----------

    @Test
    void 시스템_등록_거래처연결은_고객협력사만_코드고정() throws Exception {
        long erp = system("ecount", "ERP");
        long hmc = id(postJson(S, "{\"systemCd\":\"HMC\",\"systemNm\":\"현대 포털\",\"systemType\":\"CUSTOMER\",\"custCd\":\"c001\"}"));
        postJson(S, "{\"systemCd\":\"ECOUNT\",\"systemNm\":\"x\",\"systemType\":\"ERP\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(S, "{\"systemCd\":\"X 1\",\"systemNm\":\"\",\"systemType\":\"MES\",\"custCd\":\"C001\"}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FORMAT", "REQUIRED", "CODE", "CUST_TYPE")));
        postJson(S, "{\"systemCd\":\"KIA\",\"systemNm\":\"x\",\"systemType\":\"CUSTOMER\",\"custCd\":\"NOPE\"}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        putJson(S + "/" + erp, "{\"systemCd\":\"ECOUNT2\",\"systemNm\":\"x\",\"systemType\":\"ERP\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson(S + "/" + erp, "{\"systemNm\":\"이카운트\",\"systemType\":\"ERP\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(S).with(admin)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.systemCd=='HMC')].custNm").value(hasItem("가상 완성차")));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_ext_system set system_cd = 'Z' where system_id = ?", hmc)))).contains("ck_mst_ext_system_cd_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_ext_system set system_type = 'ERP' where system_id = ?", hmc)))).contains("ck_mst_ext_system_cust");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from mst_ext_system where system_id = ?", erp))))
                .contains("42501");
    }

    // ---------- 외부 ID ----------

    @Test
    void 외부품번은_시스템안에서_1대1_대소문자유지_사용중지후_다시() throws Exception {
        system("ECOUNT", "ERP");
        system("HMC", "CUSTOMER");
        long m1 = id(extId(fg1, "ecount", "ab-0001"));
        extId(fg2, "ECOUNT", "ab-0001").andExpect(jsonPath("$.errors[0].field").value("externalId"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));                      // 같은 외부 코드 → 다른 품목
        extId(fg1, "ECOUNT", "AB-0002").andExpect(jsonPath("$.errors[0].code").value("ONE_PER_SYSTEM"));  // 같은 품목에 둘
        id(extId(fg2, "ECOUNT", "AB-0001"));                                                       // 대소문자 다르면 다른 코드(ERP 그대로)
        id(extId(fg1, "HMC", "84910-2S000"));                                                     // 다른 시스템은 따로
        mvc.perform(get(I + "/" + fg1 + "/ext-ids").with(admin)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.systemCd=='ECOUNT')].externalId").value(hasItem("ab-0001")));
        // 사용 중지 후 다시 넣기·다시 사용하려면 겹침
        putJson(I + "/ext-ids/" + m1, "{\"externalId\":\"ab-0001\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        long m3 = id(extId(fg1, "ECOUNT", "ab-0009"));
        putJson(I + "/ext-ids/" + m1, "{\"externalId\":\"ab-0001\",\"useYn\":\"Y\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("ONE_PER_SYSTEM"));
        putJson(I + "/ext-ids/" + m3, "{\"systemCd\":\"HMC\",\"externalId\":\"ab-0009\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        // 형식: 제어 문자·빈 값·없는 시스템
        extId(fg1, "NOPE", "x").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        extId(fg1, "HMC", "a\\tb").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 사용 중지된 시스템
        long sysOff = system("OLD", "OTHER");
        putJson(S + "/" + sysOff, "{\"systemNm\":\"x\",\"systemType\":\"OTHER\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        extId(fg1, "OLD", "x").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        mvc.perform(get(I + "/ext-systems").with(admin)).andExpect(jsonPath("$.data.length()").value(2));
        // 직접 SQL: 대상 바꾸기·지우기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_ext_id_map set internal_id = ? where map_id = ?", fg2, m3)))).contains("ck_mst_ext_id_map_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from mst_ext_id_map where map_id = ?", m3))))
                .contains("42501");
    }

    // ---------- 코드 매핑 ----------

    @Test
    void 코드매핑_외부표기_대소문자무시_유일_내부코드는_쓸수있는것만() throws Exception {
        long erp = system("ECOUNT", "ERP");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'RM-WH', '원자재 창고', 't', 't')""", comp);
        long c1 = id(postJson(S + "/" + erp + "/code-maps", "{\"codeType\":\"UOM\",\"extCode\":\"개\",\"intCode\":\"ea\"}"));
        id(postJson(S + "/" + erp + "/code-maps", "{\"codeType\":\"UOM\",\"extCode\":\"Box\",\"intCode\":\"BOX\"}"));
        postJson(S + "/" + erp + "/code-maps", "{\"codeType\":\"UOM\",\"extCode\":\"BOX\",\"intCode\":\"EA\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        id(postJson(S + "/" + erp + "/code-maps", "{\"codeType\":\"WH\",\"extCode\":\"BOX\",\"intCode\":\"rm-wh\"}"));   // 유형이 다르면 따로
        id(postJson(S + "/" + erp + "/code-maps", "{\"codeType\":\"ITEM_TYPE\",\"extCode\":\"제품\",\"intCode\":\"FG\"}"));
        postJson(S + "/" + erp + "/code-maps", "{\"codeType\":\"UOM\",\"extCode\":\"kg2\",\"intCode\":\"KGG\"}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(S + "/" + erp + "/code-maps", "{\"codeType\":\"WH\",\"extCode\":\"W9\",\"intCode\":\"NOWH\"}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(S + "/" + erp + "/code-maps", "{\"codeType\":\"TRX\",\"extCode\":\"\",\"intCode\":\"\"}")
                .andExpect(jsonPath("$.errors.length()").value(3));
        putJson(S + "/code-maps/" + c1, "{\"codeType\":\"WH\",\"extCode\":\"개\",\"intCode\":\"EA\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson(S + "/code-maps/" + c1, "{\"extCode\":\"개\",\"intCode\":\"EA\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        id(postJson(S + "/" + erp + "/code-maps", "{\"codeType\":\"UOM\",\"extCode\":\"개\",\"intCode\":\"SET\"}"));   // 끈 뒤 다시
        mvc.perform(get(S + "/" + erp + "/code-maps").with(admin)).andExpect(jsonPath("$.data.length()").value(5));
        mvc.perform(get(S).with(admin)).andExpect(jsonPath("$.data[0].codeMapCnt").value(4));
        // 직접 SQL: 유형 바꾸기·쓸 수 없는 내부 코드
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update if_code_map set code_type = 'WH' where code_map_id = ?", c1)))).contains("ck_if_code_map_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update if_code_map set int_code = 'NOPE' where code_map_id = ?", c1)))).contains("fk_if_code_map_int_code");
    }

    // ---------- ERP 주인 필드 ----------

    @Test
    void 정책_PULL_이면_품목신규_ERP주인칸_변경_거부_MES칸은_가능() throws Exception {
        mvc.perform(get(I + "/settings").with(admin)).andExpect(jsonPath("$.data.erpSyncMode").value("EXCEL"))
                .andExpect(jsonPath("$.data.erpOwnedFields.length()").value(0));
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'ERP_ITEM_SYNC_MODE', 'PULL', 't', 't')""", comp);
        mvc.perform(get(I + "/settings").with(admin)).andExpect(jsonPath("$.data.erpOwnedFields.length()").value(7));   // + 표준단가(docs/29_P3 29장)
        postJson(I, "{\"itemCd\":\"NEW1\",\"itemNm\":\"x\",\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"EA\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ERP_OWNED"));
        putJson(I + "/" + fg1, "{\"itemCd\":\"FG1\",\"itemNm\":\"바꾼 이름\",\"itemType\":\"RM\",\"procType\":\"MAKE\",\"unitCd\":\"KG\","
                + "\"rowVer\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("itemNm", "itemType", "unitCd")))
                .andExpect(jsonPath("$.errors[0].code").value("ERP_OWNED"));
        putJson(I + "/" + fg1, "{\"itemCd\":\"FG1\",\"itemNm\":\"FG1 품목\",\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"EA\","
                + "\"lotCtrlYn\":\"N\",\"netMassKg\":\"1.25\",\"remark\":\"MES 칸\",\"rowVer\":0}").andExpect(status().isOk());
        // 외부 품번은 MES 가 붙인다(ERP 매핑 자체)
        system("ECOUNT", "ERP");
        id(extId(fg1, "ECOUNT", "E-1"));
        // 사용 중지는 MES 도 가능
        postJson(I + "/delete", "{\"items\":[{\"id\":" + fg2 + ",\"rowVer\":0}]}").andExpect(status().isOk());
    }

    // ---------- A/B사 · 권한 ----------

    @Test
    void B사는_A사_시스템_매핑을_못보고_못쓴다_권한() throws Exception {
        long erp = system("ECOUNT", "ERP");
        long m1 = id(extId(fg1, "ECOUNT", "E-1"));
        String other = accounts.newComp("EX");
        long b = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, b, "MASTER", "mst-ext-system", "SEARCH", "SAVE");
        accounts.grant(other, b, "MASTER", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor bu = accounts.as(other, b, "admin1");
        long bItem = item(other, "FG1");
        mvc.perform(get(S).with(bu)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(S + "/" + erp + "/code-maps").with(bu)).andExpect(status().isNotFound());
        mvc.perform(get(I + "/" + fg1 + "/ext-ids").with(bu)).andExpect(status().isNotFound());
        mvc.perform(json(put(I + "/ext-ids/" + m1), "{\"externalId\":\"x\",\"rowVer\":0}").with(bu)).andExpect(status().isNotFound());
        mvc.perform(json(post(I + "/" + bItem + "/ext-ids"), "{\"systemCd\":\"ECOUNT\",\"externalId\":\"E-1\"}").with(bu))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));                      // A사 시스템 코드
        // B사도 같은 코드로 자기 시스템·매핑을 따로 만든다
        mvc.perform(json(post(S), "{\"systemCd\":\"ECOUNT\",\"systemNm\":\"b\",\"systemType\":\"ERP\"}").with(bu)).andExpect(status().isOk());
        mvc.perform(json(post(I + "/" + bItem + "/ext-ids"), "{\"systemCd\":\"ECOUNT\",\"externalId\":\"E-1\"}").with(bu)).andExpect(status().isOk());
        // 직접 SQL: B사 범위에서 A사 시스템·A사 품목
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by)
                values (?, ?, 'ITEM', ?, 'Z', 't', 't')""", other, erp, bItem)))).contains("fk_mst_ext_id_map_system_comp");
        long bSys = accounts.query(other, "select system_id from mst_ext_system", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by)
                values (?, ?, 'ITEM', ?, 'Z', 't', 't')""", other, bSys, fg1)))).contains("fk_mst_ext_id_map_target");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into if_code_map (comp_cd, system_id, code_type, ext_code, int_code, created_by, updated_by)
                values (?, ?, 'UOM', 'z', 'EA', 't', 't')""", other, erp)))).contains("fk_if_code_map_system_comp");
        // 권한: 품목 조회만 → 외부 품번 추가 403, 외부 시스템 화면 API 403, 품목 화면의 시스템 선택지 200
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-item", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(json(post(I + "/" + fg1 + "/ext-ids"), "{\"systemCd\":\"ECOUNT\",\"externalId\":\"Q\"}").with(r)).andExpect(status().isForbidden());
        mvc.perform(get(S).with(r)).andExpect(status().isForbidden());
        mvc.perform(get(I + "/ext-systems").with(r)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(I + "/" + fg1 + "/ext-ids").with(r)).andExpect(jsonPath("$.data.length()").value(1));
    }
}
