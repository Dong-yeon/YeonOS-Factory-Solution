package io.smarthappy.module.master.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.contains;
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
 * 특성 마스터·불량 유형·용어 사전 (P1 6단계, docs/21) — 제품/공정 특성 키·규격 검증·고정 칸, 특별특성 회사 기호,
 * 불량 상위 분류 고정, 동의어 유일(대소문자·공백), 직접 SQL, A/B사, 권한.
 */
@AutoConfigureMockMvc
class QualityTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String C = "/api/master/chars";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QC");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-char", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(comp, userId, "MASTER", "mst-defect", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-term", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        seed(comp);
    }

    private void seed(String c) {
        for (String it : new String[] {"FG1", "FG2"}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", c, it, it + " 품목");
        }
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't'), (?, 'DRAW', '드로잉', 'PRESS', 't', 't')""", c, c);
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

    private ResultActions product(String item, String no, String lsl, String nominal, String usl) throws Exception {
        return postJson(C, "{\"itemCd\":\"" + item + "\",\"procCd\":\"DRAW\",\"charNo\":\"" + no + "\",\"charNm\":\"홀 지름\",\"unitNm\":\"mm\","
                + "\"scale\":2,\"lsl\":" + q(lsl) + ",\"nominal\":" + q(nominal) + ",\"usl\":" + q(usl) + "}");
    }

    private static String q(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    // ---------- 특성 ----------

    @Test
    void 제품_공정_특성_생성_규격글자_번호는_품목_공정_안에서_유일() throws Exception {
        long c1 = id(product("fg1", "12", "9.95", "10", "10.05"));
        id(product("FG1", "13", "440", null, null));                     // 하한만(최소 강도)
        id(product("FG2", "12", null, null, "0.3"));                      // 다른 품목 같은 번호
        product("FG1", "12", "1", null, "2").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        long p1 = id(postJson(C, "{\"charType\":\"PROCESS\",\"procCd\":\"spot\",\"charNo\":\"1\",\"charNm\":\"용접 전류\",\"unitNm\":\"kA\","
                + "\"scale\":1,\"lsl\":\"8.5\",\"usl\":\"9.5\"}"));
        postJson(C, "{\"charType\":\"PROCESS\",\"procCd\":\"SPOT\",\"charNo\":\"1\",\"charNm\":\"x\",\"dataType\":\"ATTRIBUTE\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        id(postJson(C, "{\"charType\":\"PROCESS\",\"procCd\":\"DRAW\",\"charNo\":\"1\",\"charNm\":\"다이 쿠션 압력\",\"lsl\":\"10\",\"scale\":0}"));
        id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"14\",\"charNm\":\"크랙 없음\",\"dataType\":\"ATTRIBUTE\",\"specialCls\":\"SC\"}"));
        mvc.perform(get(C).param("itemCd", "FG1").with(admin))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[*].specText").value(contains("9.95 ~ 10.05 mm", "≥ 440.00 mm", "OK/NG")));
        mvc.perform(get(C + "/" + c1).with(admin)).andExpect(jsonPath("$.data.procCd").value("DRAW"))
                .andExpect(jsonPath("$.data.nominal").value(10));
        mvc.perform(get(C).param("procCd", "SPOT").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(C).param("specialCls", "SC").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(C).param("procCd", "NOPE").with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(C + "/" + p1).with(admin)).andExpect(jsonPath("$.data.specText").value("8.5 ~ 9.5 kA"));
        mvc.perform(json(post(C + "/export"), "{\"itemCd\":\"FG1\"}").with(admin)).andExpect(status().isOk());
    }

    @Test
    void 규격_검증_한번에_칸마다() throws Exception {
        product("FG1", "1", null, "5", null).andExpect(jsonPath("$.errors[0].code").value("SPEC_LIMIT"));
        product("FG1", "1", "2", null, "2").andExpect(jsonPath("$.errors[0].field").value("usl"))
                .andExpect(jsonPath("$.errors[0].code").value("SPEC_RANGE"));
        product("FG1", "1", "1", "3", "2").andExpect(jsonPath("$.errors[0].field").value("nominal"));
        product("FG1", "1", "1.005", null, "2").andExpect(jsonPath("$.errors[0].code").value("SCALE_SHORT"));
        postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"dataType\":\"ATTRIBUTE\",\"lsl\":\"1\"}")
                .andExpect(jsonPath("$.errors[0].code").value("ATTR_NO_SPEC"));
        // 제품인데 품목 없음·번호 형식·이름 없음·없는 특별특성·출처·자릿수·유형
        postJson(C, "{\"charNo\":\"a b\",\"charNm\":\"\",\"specialCls\":\"HMC\",\"source\":\"WEB\",\"scale\":7,\"lsl\":\"1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("REQUIRED", "FORMAT", "REQUIRED", "CODE", "CODE", "CODE")));
        postJson(C, "{\"charType\":\"PROCESS\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}")
                .andExpect(jsonPath("$.errors[0].field").value("procCd")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(C, "{\"charType\":\"LOT\",\"itemCd\":\"NOPE\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("CODE", "NOT_FOUND")));
    }

    @Test
    void 수정_유형_품목_공정특성의_공정은_고정_규격수정_사용중지_버전충돌() throws Exception {
        long c1 = id(product("FG1", "12", "9.95", "10", "10.05"));
        long p1 = id(postJson(C, "{\"charType\":\"PROCESS\",\"procCd\":\"SPOT\",\"charNo\":\"1\",\"charNm\":\"전류\",\"lsl\":\"8\"}"));
        putJson(C + "/" + c1, "{\"charType\":\"PROCESS\",\"itemCd\":\"FG2\",\"charNo\":\"12\",\"charNm\":\"x\",\"lsl\":\"1\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FIXED", "FIXED")));
        putJson(C + "/" + p1, "{\"procCd\":\"DRAW\",\"charNo\":\"1\",\"charNm\":\"전류\",\"lsl\":\"8\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        // 제품 특성: 공정 바꾸기·공정 비우기·규격 바꾸기·번호 바꾸기 허용(감사 이력), 품목 칸을 비우면 그대로
        putJson(C + "/" + c1, "{\"charNo\":\"12A\",\"charNm\":\"홀 지름\",\"procCd\":\"\",\"scale\":3,\"lsl\":\"9.98\",\"usl\":\"10.02\","
                + "\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(C + "/" + c1).with(admin)).andExpect(jsonPath("$.data.charNo").value("12A"))
                .andExpect(jsonPath("$.data.itemCd").value("FG1")).andExpect(jsonPath("$.data.procCd").isEmpty())
                .andExpect(jsonPath("$.data.specText").value("9.980 ~ 10.020"));
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'qms_char' and row_key = ?",
                Long.class, String.valueOf(c1))).isEqualTo(2);
        mvc.perform(get(C).param("itemCd", "FG1").with(admin)).andExpect(jsonPath("$.total").value(0));          // 사용 중지 숨김
        mvc.perform(get(C).param("itemCd", "FG1").param("withUnused", "true").with(admin)).andExpect(jsonPath("$.total").value(1));
        putJson(C + "/" + c1, "{\"charNo\":\"12A\",\"charNm\":\"x\",\"lsl\":\"1\",\"rowVer\":0}").andExpect(status().isConflict());
    }

    @Test
    void 특별특성_고객기호는_회사코드로_추가_B사는_못쓴다_직접SQL() throws Exception {
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by)
                values (?, 'SPECIAL_CHAR', 'HMC-S', 'Ⓢ(현대)', 't', 't')""", comp);
        long c1 = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\",\"specialCls\":\"HMC-S\"}"));
        String other = accounts.newComp("QC");
        long b = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, b, "MASTER", "mst-char", "SEARCH", "SAVE");
        seed(other);
        RequestPostProcessor bu = accounts.as(other, b, "admin1");
        mvc.perform(json(post(C), "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\",\"specialCls\":\"HMC-S\"}").with(bu))
                .andExpect(jsonPath("$.errors[0].field").value("specialCls"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, lsl, special_cls, created_by, updated_by)
                select ?, 'PRODUCT', item_id, '9', 'x', 1, 'HMC-S', 't', 't' from mst_item""", other)))).contains("fk_qms_char_special");
        // B사: A사 특성 상세·목록, A사 품목 id 로 직접 SQL
        mvc.perform(get(C + "/" + c1).with(bu)).andExpect(status().isNotFound());
        mvc.perform(get(C).with(bu)).andExpect(jsonPath("$.total").value(0));
        long aItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, lsl, created_by, updated_by)
                values (?, 'PRODUCT', ?, '9', 'x', 1, 't', 't')""", other, aItem)))).contains("fk_qms_char_item_comp");
        long aProc = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'SPOT'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, lsl, created_by, updated_by)
                values (?, 'PROCESS', ?, '9', 'x', 1, 't', 't')""", other, aProc)))).contains("fk_qms_char_proc_comp");
        // 직접 SQL: 삭제·계수에 수치·공정 특성인데 공정 없음
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from qms_char where char_id = ?", c1))))
                .contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update qms_char set data_type = 'ATTRIBUTE' where char_id = ?", c1)))).contains("ck_qms_char_spec");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update qms_char set char_type = 'PROCESS' where char_id = ?", c1)))).contains("ck_qms_char_target");
    }

    // ---------- 불량 유형 ----------

    @Test
    void 불량유형_상위분류는_표준만_코드고정_상위순서로_목록() throws Exception {
        long d1 = id(postJson("/api/master/defects", "{\"defectCd\":\"spot-miss\",\"defectNm\":\"스폿 누락\",\"catCd\":\"WELD\"}"));
        id(postJson("/api/master/defects", "{\"defectCd\":\"SCRATCH\",\"defectNm\":\"긁힘\",\"catCd\":\"APPEARANCE\"}"));
        postJson("/api/master/defects", "{\"defectCd\":\"SPOT-MISS\",\"defectNm\":\"x\",\"catCd\":\"WELD\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson("/api/master/defects", "{\"defectCd\":\"X 1\",\"defectNm\":\"\",\"catCd\":\"MYCAT\"}")
                .andExpect(jsonPath("$.errors.length()").value(3));
        putJson("/api/master/defects/" + d1, "{\"defectCd\":\"SPOT-X\",\"defectNm\":\"스폿 누락\",\"catCd\":\"WELD\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson("/api/master/defects/" + d1, "{\"defectNm\":\"스폿 누락\",\"catCd\":\"ASSEMBLY\",\"useYn\":\"N\",\"rowVer\":0}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/defects").with(admin)).andExpect(jsonPath("$.data[*].defectCd").value(contains("SCRATCH", "SPOT-MISS")));
        mvc.perform(get("/api/master/defects").param("useYn", "Y").with(admin)).andExpect(jsonPath("$.data.length()").value(1));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update qms_defect set cat_cd = 'MYCAT' where defect_id = ?", d1)))).contains("ck_qms_defect_cat");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from qms_defect where defect_id = ?", d1))))
                .contains("42501");
    }

    // ---------- 용어 사전 ----------

    @Test
    void 동의어는_회사안에서_대소문자_공백무시_유일_회사마다_따로_전역용어는_앱이_못고친다() throws Exception {
        mvc.perform(get("/api/master/terms").with(admin)).andExpect(jsonPath("$.data.length()").value(18))   // + CMS 3차 MTBF_WO·MTTR_WO·MTTA(docs/33 16.3)
                .andExpect(jsonPath("$.data[?(@.termCd=='OEE')].termKo").value(hasItem("설비 종합효율")));
        long a1 = id(postJson("/api/master/terms/aliases", "{\"termCd\":\"DEFECT_RATE\",\"alias\":\" B급률 \"}"));
        postJson("/api/master/terms/aliases", "{\"termCd\":\"DEFECT_RATE\",\"alias\":\"b급률\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson("/api/master/terms/aliases", "{\"termCd\":\"SCRAP_QTY\",\"alias\":\"B급률\"}")      // 다른 용어로도 같은 말 금지
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        id(postJson("/api/master/terms/aliases", "{\"termCd\":\"CYCLE_TIME\",\"alias\":\"택트  타임\"}"));
        postJson("/api/master/terms/aliases", "{\"termCd\":\"CYCLE_TIME\",\"alias\":\"택트 타임\"}")      // 공백 여럿 = 하나
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson("/api/master/terms/aliases", "{\"termCd\":\"NOPE\",\"alias\":\"x\",\"lang\":\"jp\"}")
                .andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(get("/api/master/terms/DEFECT_RATE/aliases").with(admin)).andExpect(jsonPath("$.data[0].alias").value("B급률"));
        putJson("/api/master/terms/aliases/" + a1, "{\"termCd\":\"SCRAP_QTY\",\"alias\":\"B급률\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson("/api/master/terms/aliases/" + a1, "{\"alias\":\"B급률\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get("/api/master/terms").with(admin)).andExpect(jsonPath("$.data[?(@.termCd=='DEFECT_RATE')].aliasCnt").value(hasItem(0)));
        // B사: 같은 말을 따로 쓸 수 있고 A사 동의어는 안 보인다
        String other = accounts.newComp("QC");
        long b = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, b, "MASTER", "mst-term", "SEARCH", "SAVE");
        RequestPostProcessor bu = accounts.as(other, b, "admin1");
        mvc.perform(json(post("/api/master/terms/aliases"), "{\"termCd\":\"DEFECT_RATE\",\"alias\":\"B급률\"}").with(bu)).andExpect(status().isOk());
        mvc.perform(get("/api/master/terms/CYCLE_TIME/aliases").with(bu)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(json(put("/api/master/terms/aliases/" + a1), "{\"alias\":\"y\",\"rowVer\":1}").with(bu)).andExpect(status().isNotFound());
        // 전역 용어는 앱 계정이 쓸 수 없다(Flyway 로만)
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "update sys_term set term_ko = 'x' where term_cd = 'OEE'"))))
                .contains("42501");
    }

    @Test
    void 권한_조회만이면_저장403_특성화면은_공정선택지_엑셀권한() throws Exception {
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-char", "SEARCH");
        accounts.grant(comp, reader, "READER", "mst-term", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(json(post(C), "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}").with(r)).andExpect(status().isForbidden());
        mvc.perform(json(post(C + "/export"), "{}").with(r)).andExpect(status().isForbidden());
        mvc.perform(get(C + "/procs").with(r)).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(C + "/item").param("itemCd", "fg1").with(r)).andExpect(jsonPath("$.data.itemNm").value("FG1 품목"));
        mvc.perform(json(post("/api/master/terms/aliases"), "{\"termCd\":\"OEE\",\"alias\":\"x\"}").with(r)).andExpect(status().isForbidden());
        mvc.perform(get("/api/master/defects").with(r)).andExpect(status().isForbidden());
    }

    // ---------- 측정 단위 공통코드 (docs/68 Q2) ----------

    @Test
    void 측정단위는_MEAS_UNIT_코드_이름은_서버가_채우고_옛글자는_같은이름_코드로() throws Exception {
        long c1 = id(postJson(C, "{\"itemCd\":\"FG1\",\"procCd\":\"DRAW\",\"charNo\":\"U1\",\"charNm\":\"두께\",\"unitCd\":\"UM\",\"unitNm\":\"무시\","
                + "\"scale\":1,\"lsl\":\"1\",\"usl\":\"2\"}"));
        assertThat(accounts.query(comp, "select unit_cd || '/' || unit_nm from qms_char where char_id = ?", String.class, c1)).isEqualTo("UM/µm");
        mvc.perform(get(C + "/" + c1).with(admin)).andExpect(jsonPath("$.data.unitCd").value("UM")).andExpect(jsonPath("$.data.unitNm").value("µm"));
        // 옛 API(글자만): 같은 이름 코드가 하나면 그 코드로, 목록 밖 글자는 코드 없이 그대로(조용히 지우지 않음)
        long c2 = id(product("FG1", "U2", "1", null, "2"));
        assertThat(accounts.query(comp, "select unit_cd || '/' || unit_nm from qms_char where char_id = ?", String.class, c2)).isEqualTo("MM/mm");
        long c3 = id(postJson(C, "{\"itemCd\":\"FG1\",\"procCd\":\"DRAW\",\"charNo\":\"U3\",\"charNm\":\"경도\",\"unitNm\":\"HB\",\"scale\":0,\"lsl\":\"100\"}"));
        assertThat(accounts.query(comp, "select coalesce(unit_cd, '-') || '/' || unit_nm from qms_char where char_id = ?", String.class, c3)).isEqualTo("-/HB");
        // 없는 코드·다른 회사 코드 → unitCd CODE
        String other = accounts.newComp("QT");
        accounts.exec(other, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'MEAS_UNIT', 'BONLY', 'B 단위', 't', 't')", other);
        for (String bad : new String[] {"NOPE", "BONLY", "um"}) {
            postJson(C, "{\"itemCd\":\"FG1\",\"procCd\":\"DRAW\",\"charNo\":\"U9\",\"charNm\":\"x\",\"unitCd\":\"" + bad + "\",\"scale\":1,\"lsl\":\"1\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[?(@.field=='unitCd')].code").value(hasItem("CODE")));
        }
        // 직접 SQL 도 트리거가 거부
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update qms_char set unit_cd = 'BONLY' where char_id = ?", c1))))
                .contains("fk_qms_char_unit_cd_code");
    }
}
