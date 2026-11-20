package io.smarthappy.module.master.ext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
 * QA P1 7a — 외부 시스템·외부 ID·코드 매핑·ERP 주인 필드 잠금 이상 사용 (docs/qa/ext-class-7.md). ExtTest 에 없는 것만:
 * 경계값·유니코드·제어 문자, 1:1 경계(수정·다시 사용·대소문자), 사용 중지 대상에 새 참조, ERP PULL 정책(API 정규화·엑셀 올리기·전환 중 실행),
 * 동시성, 직접 SQL, A사로 B사(모든 API·참조 코드), 권한 조합·변경 이력.
 *
 * - {@code 결함n_…} = 고친 뒤의 기대 동작. 개발 세션이 고치고 @Disabled 를 풀었다(2026-10-20, V031) — 옛 동작을 고정하던 관찰_결함n 은 지웠다.
 */
@AutoConfigureMockMvc
class ExtAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String S = "/api/master/ext-systems";
    private static final String I = "/api/master/items";
    private static final String IMP = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

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
    long fg1;
    long fg2;
    long erp;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("EQ");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-ext-system", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-item", "SEARCH", "SAVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        fg1 = item(comp, "FG1", "SPEC-A");
        fg2 = item(comp, "FG2", null);
        accounts.exec(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C001', '가상 완성차', 'SALES', 't', 't'), (?, 'C002', '가상 협력사', 'PURCHASE', 't', 't')""", comp, comp);
        erp = system("ERP", "ERP");
    }

    // ---------- 도우미 ----------

    private long item(String c, String cd, String spec) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, spec, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, ?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't') returning item_id""", Long.class, c, cd, cd + " 품목", spec);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
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

    private ResultActions codeMap(long systemId, String type, String ext, String internal) throws Exception {
        return postJson(S + "/" + systemId + "/code-maps", "{\"codeType\":\"" + type + "\",\"extCode\":\"" + ext + "\",\"intCode\":\"" + internal + "\"}");
    }

    private int ver(String table, String key, long id) {
        return accounts.query(comp, "select row_ver from " + table + " where " + key + " = ?", Integer.class, id);
    }

    private void pull() {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'ERP_ITEM_SYNC_MODE', 'PULL', 't', 't')""", comp);
    }

    private String sqlError(String compCd, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(compCd, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }

    private CompletableFuture<Void> holdTx(Runnable work, CountDownLatch done, long holdMs) {
        return CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            work.run();
            done.countDown();
            try {
                Thread.sleep(holdMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private CompletableFuture<MvcResult> async(MockHttpServletRequestBuilder req) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(req).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ---------- 엑셀 올리기 도우미 (품목 템플릿: 코드, 이름, 규격, 유형, 조달, 단위, LOT, 순질량, HS, 비고, 사용) ----------

    private byte[] fill(List<String[]> rows) throws Exception {
        byte[] template = mvc.perform(post(IMP + "/templates/MST_ITEM/file").with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            Sheet s = wb.getSheetAt(0);
            int r = 1;
            for (String[] values : rows) {
                Row row = s.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] != null) {
                        row.createCell(c).setCellValue(values[c]);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String[] row(String cd, String nm, String spec, String mass) {
        return new String[] {cd, nm, spec, "FG", "MAKE", "EA", null, mass, null, null, null};
    }

    private MvcResult upload(byte[] file) throws Exception {
        return mvc.perform(multipart(IMP + "/templates/MST_ITEM/jobs").file(new MockMultipartFile("file", "x.xlsx", XLSX, file))
                .param("mode", "UPSERT").with(csrf()).with(admin)).andExpect(status().isOk()).andReturn();
    }

    private ResultActions execute(long job) throws Exception {
        int rv = accounts.query(comp, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(IMP + "/jobs/" + job + "/execute").with(csrf()).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + rv + ",\"skipErrors\":true}"));
    }

    private static Object at(MvcResult r, String path) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    // =====================================================================
    // 1. 경계값·유니코드
    // =====================================================================

    @Test
    void 외부코드_길이_UTF16_앞뒤_공백_전각공백_제거_대소문자() throws Exception {
        id(extId(fg1, "ERP", "A".repeat(100)));
        extId(fg2, "ERP", "B".repeat(101)).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        extId(fg2, "ERP", "😀".repeat(51)).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));     // UTF-16 102 (DB 는 51자) — 안전한 쪽
        long m = id(extId(fg2, "ERP", "\\u3000 AB-1 \\t"));                                          // 전각 공백·탭은 앞뒤 제거
        assertThat(accounts.query(comp, "select external_id from mst_ext_id_map where map_id = ?", String.class, m)).isEqualTo("AB-1");
        extId(fg2, "ERP", " ").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 외부 이름 200자 / 201자, 시스템 이름 100자 / 101자
        long fg3 = item(comp, "FG3", null);
        id(postJson(I + "/" + fg3 + "/ext-ids", "{\"systemCd\":\"ERP\",\"externalId\":\"X3\",\"externalNm\":\"" + "가".repeat(200) + "\"}"));
        long fg4 = item(comp, "FG4", null);
        postJson(I + "/" + fg4 + "/ext-ids", "{\"systemCd\":\"ERP\",\"externalId\":\"X4\",\"externalNm\":\"" + "가".repeat(201) + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        postJson(S, "{\"systemCd\":\"N100\",\"systemNm\":\"" + "가".repeat(101) + "\",\"systemType\":\"ERP\"}")
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        // 시스템 코드 경계: 20자 / 21자 / 소문자 → 대문자 중복
        system("A".repeat(20), "OTHER");
        postJson(S, "{\"systemCd\":\"" + "B".repeat(21) + "\",\"systemNm\":\"x\",\"systemType\":\"OTHER\"}")
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        postJson(S, "{\"systemCd\":\" erp \",\"systemNm\":\"x\",\"systemType\":\"ERP\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(S, "{\"systemCd\":\"ERP\",\"systemNm\":\"x\",\"systemType\":\"erp\"}")
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.hasItem("CODE")));
    }

    /** 관찰: 시스템 코드 `ﬁx` 는 대문자 변환으로 `FIX` 가 되어 저장(6단계 QA 관찰과 같은 규칙) */
    @Test
    void 관찰_시스템코드_합자는_대문자변환으로_바뀐다() throws Exception {
        long s = id(postJson(S, "{\"systemCd\":\"ﬁx\",\"systemNm\":\"x\",\"systemType\":\"OTHER\"}"));
        assertThat(accounts.query(comp, "select system_cd from mst_ext_system where system_id = ?", String.class, s)).isEqualTo("FIX");
    }

    // ---------- 결함8: 보이지 않는 문자 ----------

    /** 결함8: NBSP·폭 없는 공백·전각 글자가 섞인 외부 코드·코드 매핑 표기는 "같은 모양의 다른 코드"로 들어간다 — 1:1·대소문자 무시 유일을 우회 */
    @Test
    void 결함8_보이지않는_문자_섞인_외부코드는_거부되거나_같은코드로_본다() throws Exception {
        id(extId(fg1, "ERP", "AB-1"));
        extId(fg2, "ERP", "AB-1\\u00a0").andExpect(jsonPath("$.errors[0].field").value("externalId"));
        extId(fg2, "ERP", "AB-1\\u200b").andExpect(jsonPath("$.errors[0].field").value("externalId"));
        id(codeMap(erp, "UOM", "Box", "BOX"));
        codeMap(erp, "UOM", "Box\\u00a0", "EA").andExpect(jsonPath("$.errors[0].field").value("extCode"));
        codeMap(erp, "UOM", "Ｂｏｘ", "EA").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }


    // ---------- 결함9: 제어 문자 ----------

    /** 결함9: 외부 이름·시스템 이름·비고는 제어 문자를 저장하고, NUL·C1(U+0085) 은 칸 없는 400(DB 가 거부) */
    @Test
    void 결함9_외부이름_시스템이름_제어문자는_칸오류() throws Exception {
        postJson(I + "/" + fg1 + "/ext-ids", "{\"systemCd\":\"ERP\",\"externalId\":\"Z1\",\"externalNm\":\"a\\u0001b\"}")
                .andExpect(jsonPath("$.errors[0].field").value("externalNm"));
        postJson(S, "{\"systemCd\":\"N2\",\"systemNm\":\"a\\u0007b\",\"systemType\":\"ERP\"}").andExpect(jsonPath("$.errors[0].field").value("systemNm"));
        postJson(S, "{\"systemCd\":\"N3\",\"systemNm\":\"a\\u0000b\",\"systemType\":\"ERP\"}").andExpect(jsonPath("$.errors[0].field").value("systemNm"));
        extId(fg2, "ERP", "AB\\u0085").andExpect(jsonPath("$.errors[0].field").value("externalId"));
        codeMap(erp, "UOM", "a\\u0085", "EA").andExpect(jsonPath("$.errors[0].field").value("extCode"));
    }


    // =====================================================================
    // 2. 1:1 경계 — 수정·다시 사용·대소문자
    // =====================================================================

    @Test
    void 일대일_수정으로_충돌_다시사용_충돌_대소문자() throws Exception {
        long m1 = id(extId(fg1, "ERP", "E-1"));
        long m2 = id(extId(fg2, "ERP", "E-2"));
        putJson(I + "/ext-ids/" + m2, "{\"externalId\":\"E-1\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].field").value("externalId"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        putJson(I + "/ext-ids/" + m2, "{\"externalId\":\"e-1\",\"rowVer\":0}").andExpect(status().isOk());       // 대소문자 다르면 다른 코드
        // 끄고 같은 코드를 다른 품목에 → 옛 행 다시 사용은 외부 코드 충돌
        putJson(I + "/ext-ids/" + m1, "{\"externalId\":\"E-1\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        long fg3 = item(comp, "FG3", null);
        id(extId(fg3, "ERP", "E-1"));
        putJson(I + "/ext-ids/" + m1, "{\"externalId\":\"E-1\",\"useYn\":\"Y\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].field").value("externalId")).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 사용 중지 행끼리는 같은 코드 여러 개 가능
        putJson(I + "/ext-ids/" + m1, "{\"externalId\":\"E-1\",\"useYn\":\"N\",\"rowVer\":1}").andExpect(status().isOk());
        // 낡은 버전·없는 id·사용 여부 소문자·rowVer 없음
        putJson(I + "/ext-ids/" + m1, "{\"externalId\":\"E-1\",\"rowVer\":0}").andExpect(status().isConflict());
        putJson(I + "/ext-ids/999999999", "{\"externalId\":\"E-1\",\"rowVer\":0}").andExpect(status().isNotFound());
        putJson(I + "/ext-ids/" + m1, "{\"externalId\":\"E-1\",\"useYn\":\"y\",\"rowVer\":2}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        putJson(I + "/ext-ids/" + m1, "{\"externalId\":\"E-1\"}").andExpect(status().isBadRequest());
        // 품목 경로 숫자 아님·음수
        mvc.perform(get(I + "/abc/ext-ids").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(I + "/-1/ext-ids").with(admin)).andExpect(status().isNotFound());
        // 코드 매핑: 수정·다시 사용으로 대소문자 무시 충돌
        long c1 = id(codeMap(erp, "UOM", "ea", "EA"));
        long c2 = id(codeMap(erp, "UOM", "pcs", "EA"));
        putJson(S + "/code-maps/" + c2, "{\"extCode\":\"EA\",\"intCode\":\"EA\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        putJson(S + "/code-maps/" + c1, "{\"extCode\":\"ea\",\"intCode\":\"EA\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        id(codeMap(erp, "UOM", "Ea", "BOX"));
        putJson(S + "/code-maps/" + c1, "{\"extCode\":\"ea\",\"intCode\":\"EA\",\"useYn\":\"Y\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 내부 코드 앞뒤 공백·소문자 → 대문자 정리
        long c4 = id(codeMap(erp, "UOM", "킬로", " kg "));
        assertThat(accounts.query(comp, "select int_code from if_code_map where code_map_id = ?", String.class, c4)).isEqualTo("KG");
    }

    @Test
    void 코드매핑_내부코드가_나중에_사용중지되면_그대로_수정은_가능_다시사용은_막힘() throws Exception {
        accounts.addPlant(comp, "P1", "1공장");
        long wh = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'RM-WH', '원자재 창고', 't', 't') returning node_id""", Long.class, comp);
        long c = id(codeMap(erp, "WH", "W01", "RM-WH"));
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", wh);
        putJson(S + "/code-maps/" + c, "{\"extCode\":\"W-01\",\"intCode\":\"RM-WH\",\"rowVer\":0}").andExpect(status().isOk());   // 그대로면 통과
        putJson(S + "/code-maps/" + c, "{\"extCode\":\"W-01\",\"intCode\":\"RM-WH\",\"useYn\":\"N\",\"rowVer\":1}").andExpect(status().isOk());
        putJson(S + "/code-maps/" + c, "{\"extCode\":\"W-01\",\"intCode\":\"RM-WH\",\"useYn\":\"Y\",\"rowVer\":2}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));                          // 다시 사용은 다시 검사
        assertThat(sqlError(comp, "update if_code_map set use_yn = 'Y' where code_map_id = ?", c)).isEqualTo("23503 fk_if_code_map_int_code");
        codeMap(erp, "ITEM_TYPE", "제품", "fg").andExpect(status().isOk());
        codeMap(erp, "ITEM_TYPE", "반제품", "XX").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
    }

    // =====================================================================
    // 3. 사용 중지 대상에 새 참조 — 결함7
    // =====================================================================

    /** 결함7: 사용 중지 시스템에 코드 매핑 추가, 사용 중지 시스템의 외부 ID·코드 매핑 다시 사용, 사용 중지 거래처로 시스템 연결이 된다 */
    @Test
    void 결함7_사용중지_시스템_거래처에_새참조는_INACTIVE() throws Exception {
        long m = id(extId(fg1, "ERP", "E-1"));
        long c = id(codeMap(erp, "UOM", "ea", "EA"));
        putJson(I + "/ext-ids/" + m, "{\"externalId\":\"E-1\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(S + "/code-maps/" + c, "{\"extCode\":\"ea\",\"intCode\":\"EA\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(S + "/" + erp, "{\"systemNm\":\"e\",\"systemType\":\"ERP\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        codeMap(erp, "UOM", "pcs", "EA").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        putJson(I + "/ext-ids/" + m, "{\"externalId\":\"E-1\",\"useYn\":\"Y\",\"rowVer\":1}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        putJson(S + "/code-maps/" + c, "{\"extCode\":\"ea\",\"intCode\":\"EA\",\"useYn\":\"Y\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        accounts.exec(comp, "update mst_cust set use_yn = 'N' where cust_cd = 'C002'");
        postJson(S, "{\"systemCd\":\"OEM2\",\"systemNm\":\"k\",\"systemType\":\"CUSTOMER\",\"custCd\":\"C002\"}")
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
    }


    /** 관찰: 사용 중지 품목에도 외부 품번을 붙일 수 있다(대상 사용 여부를 보지 않음) — ERP 에 남은 단종 품번 매핑으로는 맞을 수 있어 설계 확인 */
    @Test
    void 관찰_사용중지_품목에_외부품번_추가() throws Exception {
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_id = ?", fg2);
        id(extId(fg2, "ERP", "OLD-2"));
    }

    /** 관찰: 대상 유형 CUST 는 서비스·DB 가 받지만 API 경로가 없다(TESTING 7a "API 는 CUST 지원" 과 다름) */
    @Test
    void 관찰_거래처_외부코드_API_경로가_없다() throws Exception {
        long cust = accounts.query(comp, "select cust_id from mst_cust where cust_cd = 'C001'", Long.class);
        mvc.perform(get("/api/master/custs/" + cust + "/ext-ids").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/master/ext-systems/ext-ids").param("entityType", "CUST").param("internalId", String.valueOf(cust)).with(admin))
                .andExpect(status().is4xxClientError());
    }

    // =====================================================================
    // 4. ERP 주인 필드 (PULL)
    // =====================================================================

    @Test
    void PULL_정규화된_같은값은_변경아님_단위사용중지_그대로_LOT_사용여부_속성() throws Exception {
        pull();
        // 소문자 코드·앞뒤 공백 이름·빈 규격 → 정리 후 같으면 통과
        putJson(I + "/" + fg1, "{\"itemCd\":\" fg1 \",\"itemNm\":\"  FG1 품목 \",\"spec\":\" SPEC-A \",\"itemType\":\"FG\",\"procType\":\"MAKE\","
                + "\"unitCd\":\" EA \",\"lotCtrlYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(I + "/" + fg2, "{\"itemCd\":\"FG2\",\"itemNm\":\"FG2 품목\",\"spec\":\"   \",\"itemType\":\"FG\",\"procType\":\"MAKE\","
                + "\"unitCd\":\"EA\",\"remark\":\"r\",\"rowVer\":0}").andExpect(status().isOk());
        // 규격을 빼고 보내면 "비우기" = 변경
        putJson(I + "/" + fg1, "{\"itemCd\":\"FG1\",\"itemNm\":\"FG1 품목\",\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"EA\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].field").value("spec")).andExpect(jsonPath("$.errors[0].code").value("ERP_OWNED"));
        // 이름 가운데 공백 하나 더 = 변경
        putJson(I + "/" + fg1, "{\"itemCd\":\"FG1\",\"itemNm\":\"FG1  품목\",\"spec\":\"SPEC-A\",\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"EA\","
                + "\"rowVer\":1}").andExpect(jsonPath("$.errors[0].code").value("ERP_OWNED"));
        // 단위가 나중에 사용 중지돼도(그대로) MES 칸 저장
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by)
                values (?, 'UNIT', 'DRUM', '드럼', 't', 't')""", comp);
        accounts.exec(comp, "update mst_item set unit_cd = 'DRUM' where item_id = ?", fg2);
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where code = 'DRUM'");
        putJson(I + "/" + fg2, "{\"itemCd\":\"FG2\",\"itemNm\":\"FG2 품목\",\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"DRUM\","
                + "\"netMassKg\":\"2\",\"rowVer\":" + ver("mst_item", "item_id", fg2) + "}").andExpect(status().isOk());
        // 사용 중지 → 다시 사용(MES 가능)
        postJson(I + "/delete", "{\"items\":[{\"id\":" + fg1 + ",\"rowVer\":1}]}").andExpect(status().isOk());
        putJson(I + "/" + fg1, "{\"itemCd\":\"FG1\",\"itemNm\":\"FG1 품목\",\"spec\":\"SPEC-A\",\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"EA\","
                + "\"useYn\":\"Y\",\"rowVer\":2}").andExpect(status().isOk());
        // 정책 값은 요청마다 읽는다: 다시 EXCEL 로 바꾸면 바로 신규 가능
        mvc.perform(get(I + "/settings").with(admin)).andExpect(jsonPath("$.data.erpSyncMode").value("PULL"));
        accounts.exec(comp, "update sys_policy set policy_value = 'EXCEL' where policy_key = 'ERP_ITEM_SYNC_MODE'");
        postJson(I, "{\"itemCd\":\"NEW1\",\"itemNm\":\"x\",\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"EA\"}").andExpect(status().isOk());
    }

    // ---------- 결함5: PULL 올리기의 빈 칸 ----------

    /** 결함5: PULL 에서 변경 행의 규격 칸을 비우면(= 기존 값 유지 규칙) ERP_OWNED 오류 — 검증이 빈 칸을 "비우기"로 비교 */
    @Test
    void 결함5_PULL_올리기_빈칸은_유지라_ERP주인칸_변경이_아니다() throws Exception {
        pull();
        MvcResult r = upload(fill(List.<String[]>of(row("FG1", "FG1 품목", null, "2.5"))));
        assertThat(at(r, "$.data.errorCount")).isEqualTo(0);
        assertThat(at(r, "$.data.changeCount")).isEqualTo(1);
        execute(((Number) at(r, "$.data.jobId")).longValue()).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select spec || '/' || net_mass_kg from mst_item where item_id = ?", String.class, fg1))
                .isEqualTo("SPEC-A/2.500000");
    }


    // ---------- 결함6: 정책 전환 중 실행 ----------

    /** 결함6: EXCEL 에서 검증한 올리기 작업을 PULL 로 바꾼 뒤 실행하면 새 품목·ERP 주인 칸 변경이 그대로 들어간다 */
    @Test
    void 결함6_검증뒤_PULL로_바뀌면_실행은_거부() throws Exception {
        MvcResult r = upload(fill(List.<String[]>of(row("NEW1", "새 품목", null, null), row("FG1", "이름 바꿈", "SPEC-A", null))));
        assertThat(at(r, "$.data.newCount")).isEqualTo(1);
        pull();
        execute(((Number) at(r, "$.data.jobId")).longValue()).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select count(*) from mst_item where item_cd = 'NEW1'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select item_nm from mst_item where item_id = ?", String.class, fg1)).isEqualTo("FG1 품목");
    }


    @Test
    void 품목_올리기는_클래스_속성을_건드리지_않는다() throws Exception {
        accounts.exec(comp, """
                insert into mst_item_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'COIL', '코일', 't', 't')""", comp);
        long cls = accounts.query(comp, "select class_id from mst_item_class where class_cd = 'COIL'", Long.class);
        accounts.exec(comp, """
                insert into mst_class_prop (comp_cd, class_type, class_id, prop_cd, prop_nm, data_type, required_yn, created_by, updated_by)
                values (?, 'ITEM', ?, 'MAKER', '제철소', 'TEXT', 'Y', 't', 't')""", comp, cls);
        accounts.exec(comp, "update mst_item set item_class_id = ?, attrs = '{\"MAKER\":\"가상제철\"}' where item_id = ?", cls, fg1);
        MvcResult r = upload(fill(List.<String[]>of(row("FG1", "FG1 새이름", "SPEC-A", "3"))));
        execute(((Number) at(r, "$.data.jobId")).longValue()).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select item_nm || '/' || item_class_id || '/' || attrs::text from mst_item where item_id = ?",
                String.class, fg1)).isEqualTo("FG1 새이름/" + cls + "/{\"MAKER\": \"가상제철\"}");
    }

    // =====================================================================
    // 5. 동시성
    // =====================================================================

    @Test
    void 동시_같은_외부코드를_다른_품목에_뒤쪽은_기다린뒤_DUPLICATE() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by)
                values (hb_current_comp(), ?, 'ITEM', ?, 'E-9', 't', 't')""", erp, fg1), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post(I + "/" + fg2 + "/ext-ids"), "{\"systemCd\":\"ERP\",\"externalId\":\"E-9\"}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("\"externalId\"").contains("DUPLICATE");
    }

    @Test
    void 동시_같은_품목에_같은시스템_두코드_뒤쪽은_ONE_PER_SYSTEM_같은버전_수정은_409() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by)
                values (hb_current_comp(), ?, 'ITEM', ?, 'E-1', 't', 't')""", erp, fg1), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post(I + "/" + fg1 + "/ext-ids"), "{\"systemCd\":\"ERP\",\"externalId\":\"E-2\"}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        assertThat(second.get(10, TimeUnit.SECONDS).getResponse().getContentAsString()).contains("ONE_PER_SYSTEM");
        long m = accounts.query(comp, "select map_id from mst_ext_id_map where internal_id = ?", Long.class, fg1);
        putJson(I + "/ext-ids/" + m, "{\"externalId\":\"E-1a\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(I + "/ext-ids/" + m, "{\"externalId\":\"E-1b\",\"rowVer\":0}").andExpect(status().isConflict());
        putJson(S + "/" + erp, "{\"systemNm\":\"a\",\"systemType\":\"ERP\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(S + "/" + erp, "{\"systemNm\":\"b\",\"systemType\":\"ERP\",\"rowVer\":0}").andExpect(status().isConflict());
    }

    @Test
    void 같은_요청키_두번이면_DUPLICATE_REQUEST() throws Exception {
        mvc.perform(json(post(I + "/" + fg1 + "/ext-ids"), "{\"systemCd\":\"ERP\",\"externalId\":\"K-1\"}").header("X-Idempotency-Key", "qa-ext-key-0001")
                .with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(I + "/" + fg2 + "/ext-ids"), "{\"systemCd\":\"ERP\",\"externalId\":\"K-2\"}").header("X-Idempotency-Key", "qa-ext-key-0001")
                .with(admin)).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
    }

    // =====================================================================
    // 6. 직접 SQL (앱 계정)
    // =====================================================================

    @Test
    void 직접SQL_우회_시도() throws Exception {
        long m1 = id(extId(fg1, "ERP", "E-1"));
        long m2 = id(extId(fg2, "ERP", "E-2"));
        assertThat(sqlError(comp, "update mst_ext_id_map set external_id = 'E-1' where map_id = ?", m2)).isEqualTo("23505 uq_mst_ext_id_map_ext");
        assertThat(sqlError(comp, "update mst_ext_id_map set external_id = ' E-3' where map_id = ?", m2)).isEqualTo("23514 ck_mst_ext_id_map_ext");
        assertThat(sqlError(comp, "update mst_ext_id_map set external_id = 'E' || chr(10) where map_id = ?", m2)).isEqualTo("23514 ck_mst_ext_id_map_ext");
        assertThat(sqlError(comp, "update mst_ext_id_map set entity_type = 'CUST' where map_id = ?", m2)).isEqualTo("55000 ck_mst_ext_id_map_fixed");
        assertThat(sqlError(comp, "update mst_ext_id_map set system_id = system_id where map_id = ?", m2)).isEqualTo("OK");
        assertThat(sqlError(comp, "update mst_ext_id_map set comp_cd = 'ZZ' where map_id = ?", m1)).startsWith("55000");   // 트리거가 먼저
        assertThat(sqlError(comp, "update mst_ext_system set comp_cd = 'ZZ' where system_id = ?", erp)).startsWith("42501");
        assertThat(sqlError(comp, """
                insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by)
                values (?, ?, 'CUST', ?, 'C-1', 't', 't')""", comp, erp, fg1)).isEqualTo("23503 fk_mst_ext_id_map_target");   // 품목 id 를 거래처로
        assertThat(sqlError(comp, """
                insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by)
                values (?, ?, 'WH', ?, 'C-1', 't', 't')""", comp, erp, fg1)).isEqualTo("23514 ck_mst_ext_id_map_entity");
        assertThat(sqlError(comp, "update mst_ext_system set system_type = 'OTHER', cust_id = (select cust_id from mst_cust where cust_cd = 'C001') "
                + "where system_id = ?", erp)).isEqualTo("23514 ck_mst_ext_system_cust");
        long c = id(codeMap(erp, "UOM", "ea", "EA"));
        assertThat(sqlError(comp, "update if_code_map set ext_code = 'EA ' where code_map_id = ?", c)).isEqualTo("23514 ck_if_code_map_ext");
        assertThat(sqlError(comp, "update if_code_map set system_id = system_id + 0 where code_map_id = ?", c)).isEqualTo("OK");
        assertThat(sqlError(comp, "delete from if_code_map where code_map_id = ?", c)).startsWith("42501");
        assertThat(sqlError(comp, "truncate mst_ext_id_map")).startsWith("42501");
    }

    // =====================================================================
    // 7. A사로 B사
    // =====================================================================

    @Test
    void B사는_A사_시스템_코드매핑_외부품번_정책_이력을_모든_API와_직접SQL로_못본다_못쓴다() throws Exception {
        long m1 = id(extId(fg1, "ERP", "E-1"));
        long c1 = id(codeMap(erp, "UOM", "ea", "EA"));
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'A-WH', 'A사 창고', 't', 't')""", comp);
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by)
                values (?, 'UNIT', 'APACK', 'A사 단위', 't', 't')""", comp);
        long aCust = accounts.query(comp, "select cust_id from mst_cust where cust_cd = 'C001'", Long.class);
        pull();                                                    // A사만 PULL

        String other = accounts.newComp("EQ");
        long b = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, b, "MASTER", "mst-ext-system", "SEARCH", "SAVE");
        accounts.grant(other, b, "MASTER", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor bu = accounts.as(other, b, "admin1");
        long bItem = item(other, "FG1", null);
        long bSys = id(mvc.perform(json(post(S), "{\"systemCd\":\"ERP\",\"systemNm\":\"b\",\"systemType\":\"ERP\"}").with(bu)));

        mvc.perform(json(put(S + "/" + erp), "{\"systemNm\":\"B사\",\"systemType\":\"ERP\",\"useYn\":\"N\",\"rowVer\":0}").with(bu))
                .andExpect(status().isNotFound());
        mvc.perform(json(put(S + "/code-maps/" + c1), "{\"extCode\":\"zz\",\"intCode\":\"EA\",\"rowVer\":0}").with(bu))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(S + "/" + erp + "/code-maps"), "{\"codeType\":\"UOM\",\"extCode\":\"zz\",\"intCode\":\"EA\"}").with(bu))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(I + "/" + fg1 + "/ext-ids"), "{\"systemCd\":\"ERP\",\"externalId\":\"B-1\"}").with(bu))
                .andExpect(status().isNotFound());                                   // A사 품목 id
        mvc.perform(get(I + "/ext-systems").with(bu)).andExpect(jsonPath("$.data.length()").value(1));
        // B사 정책은 A사 PULL 과 무관
        mvc.perform(get(I + "/settings").with(bu)).andExpect(jsonPath("$.data.erpSyncMode").value("EXCEL"));
        mvc.perform(json(post(I), "{\"itemCd\":\"NEW1\",\"itemNm\":\"x\",\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"EA\"}").with(bu))
                .andExpect(status().isOk());
        // A사의 참조 코드(거래처·창고·회사 단위)
        mvc.perform(json(post(S), "{\"systemCd\":\"OEM1\",\"systemNm\":\"h\",\"systemType\":\"CUSTOMER\",\"custCd\":\"C001\"}").with(bu))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(json(post(S + "/" + bSys + "/code-maps"), "{\"codeType\":\"WH\",\"extCode\":\"W\",\"intCode\":\"A-WH\"}").with(bu))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(json(post(S + "/" + bSys + "/code-maps"), "{\"codeType\":\"UOM\",\"extCode\":\"pk\",\"intCode\":\"APACK\"}").with(bu))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 변경 이력
        mvc.perform(get("/api/platform/audit").param("table", "mst_ext_id_map").param("key", String.valueOf(m1)).with(bu))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/platform/audit").param("table", "if_code_map").param("key", String.valueOf(c1)).with(bu))
                .andExpect(jsonPath("$.total").value(0));
        // 직접 SQL (B사 범위)
        assertThat(accounts.exec(other, "update mst_ext_id_map set use_yn = 'N' where map_id = ?", m1)).isZero();
        assertThat(accounts.exec(other, "update if_code_map set int_code = 'BOX' where code_map_id = ?", c1)).isZero();
        assertThat(accounts.exec(other, "update sys_policy set policy_value = 'EXCEL' where comp_cd = ?", comp)).isZero();
        assertThat(sqlError(other, "update mst_ext_system set system_type = 'CUSTOMER', cust_id = ? where system_id = ?", aCust, bSys))
                .isEqualTo("23503 fk_mst_ext_system_cust_comp");
        assertThat(sqlError(other, """
                insert into if_code_map (comp_cd, system_id, code_type, ext_code, int_code, created_by, updated_by)
                values (?, ?, 'WH', 'W', 'A-WH', 't', 't')""", other, bSys)).isEqualTo("23503 fk_if_code_map_int_code");
        assertThat(sqlError(other, """
                insert into if_code_map (comp_cd, system_id, code_type, ext_code, int_code, created_by, updated_by)
                values (?, ?, 'UOM', 'pk', 'APACK', 't', 't')""", other, bSys)).isEqualTo("23503 fk_if_code_map_int_code");
        assertThat(sqlError(other, """
                insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by)
                values (?, ?, 'CUST', ?, 'Z', 't', 't')""", other, bSys, aCust)).isEqualTo("23503 fk_mst_ext_id_map_target");
        assertThat(sqlError(other, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'ERP_ITEM_SYNC_MODE', 'EXCEL', 't', 't')", comp)).startsWith("42501");
        // A사 데이터 그대로
        assertThat(accounts.query(comp, "select use_yn from mst_ext_id_map where map_id = ?", String.class, m1)).isEqualTo("Y");
        assertThat(accounts.query(comp, "select int_code from if_code_map where code_map_id = ?", String.class, c1)).isEqualTo("EA");
        mvc.perform(get(I + "/settings").with(admin)).andExpect(jsonPath("$.data.erpSyncMode").value("PULL"));
        assertThat(bItem).isPositive();
    }

    // =====================================================================
    // 8. 권한 조합 · 변경 이력
    // =====================================================================

    @Test
    void 권한조합_변경이력() throws Exception {
        long m = id(extId(fg1, "ERP", "E-1"));
        putJson(I + "/ext-ids/" + m, "{\"externalId\":\"E-1-REV\",\"externalNm\":\"고친 이름\",\"rowVer\":0}").andExpect(status().isOk());
        long c = id(codeMap(erp, "UOM", "ea", "EA"));
        // 외부 품번 이력은 품목 권한으로, 코드 매핑 이력은 외부 시스템 화면 권한으로
        MvcResult h = mvc.perform(get("/api/platform/audit").param("table", "mst_ext_id_map").param("key", String.valueOf(m)).with(admin))
                .andExpect(jsonPath("$.total").value(2)).andReturn();
        assertThat(h.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("E-1-REV").contains("고친 이름").contains("admin1");
        long itemUser = accounts.newUser(comp, "item1", PW);
        accounts.grant(comp, itemUser, "ITEMONLY", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor iu = accounts.as(comp, itemUser, "item1");
        mvc.perform(get("/api/platform/audit").param("table", "mst_ext_id_map").param("key", String.valueOf(m)).with(iu))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/platform/audit").param("table", "if_code_map").param("key", String.valueOf(c)).with(iu))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/audit").param("table", "mst_ext_system").param("key", String.valueOf(erp)).with(iu))
                .andExpect(status().isForbidden());
        // 품목 저장 권한만으로 외부 품번 추가·수정 가능(외부 시스템 화면 권한 불필요), 코드 매핑은 403
        id(mvc.perform(json(post(I + "/" + fg2 + "/ext-ids"), "{\"systemCd\":\"ERP\",\"externalId\":\"E-2\"}").with(iu)));
        mvc.perform(json(post(S + "/" + erp + "/code-maps"), "{\"codeType\":\"UOM\",\"extCode\":\"x\",\"intCode\":\"EA\"}").with(iu))
                .andExpect(status().isForbidden());
        mvc.perform(get(S + "/" + erp + "/code-maps").with(iu)).andExpect(status().isForbidden());
        // 외부 시스템 화면 조회만: 시스템·코드 매핑 저장 403, 품목 외부 품번 보기 403
        long sysRead = accounts.newUser(comp, "sys1", PW);
        accounts.grant(comp, sysRead, "SYSREAD", "mst-ext-system", "SEARCH");
        RequestPostProcessor sr = accounts.as(comp, sysRead, "sys1");
        mvc.perform(get(S).with(sr)).andExpect(status().isOk());
        mvc.perform(json(post(S), "{\"systemCd\":\"X\",\"systemNm\":\"x\",\"systemType\":\"ERP\"}").with(sr)).andExpect(status().isForbidden());
        mvc.perform(json(put(S + "/code-maps/" + c), "{\"extCode\":\"ea\",\"intCode\":\"EA\",\"useYn\":\"N\",\"rowVer\":0}").with(sr))
                .andExpect(status().isForbidden());
        mvc.perform(get(I + "/" + fg1 + "/ext-ids").with(sr)).andExpect(status().isForbidden());
        mvc.perform(get(I + "/settings").with(sr)).andExpect(status().isForbidden());
    }
}
