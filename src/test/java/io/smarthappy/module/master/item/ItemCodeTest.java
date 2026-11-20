package io.smarthappy.module.master.item;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
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
 * 품목코드 = 시스템 코드 + ERP·고객 품번 매핑 (docs/17 9장) — 정상 흐름 + 이상 사용.
 * 채번(AUTO)·결번·동시 신규, 코드 고정(API·직접 SQL), ERP 품번 중복·검색, A사로 B사, 기존 품목 회귀, PULL, 엑셀 올리기
 */
@AutoConfigureMockMvc
class ItemCodeTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String BASE = "/api/master/items";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("IC");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-item", "SEARCH", "SAVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
    }

    // ---------- 도우미 ----------

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    private List<String> list(String sql, Object... args) {
        return runner.call(comp, () -> jdbc.query(sql, (rs, i) -> rs.getString(1), args));
    }

    private void policy(String c, String key, String value) {
        accounts.exec(c, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')
                on conflict (comp_cd, plant_cd, policy_key) do update set policy_value = excluded.policy_value""", c, key, value);
    }

    private long erpSystem(String c, String cd) {
        return accounts.query(c, """
                insert into mst_ext_system (comp_cd, system_cd, system_nm, system_type, created_by, updated_by)
                values (?, ?, ?, 'ERP', 't', 't') returning system_id""", Long.class, c, cd, cd + " ERP");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(String cd, String nm, String extra) {
        return "{" + (cd == null ? "" : "\"itemCd\":\"" + cd + "\",") + "\"itemNm\":\"" + nm
                + "\",\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"EA\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private ResultActions create(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(json(post(BASE), body).with(who));
    }

    private long createOk(RequestPostProcessor who, String body) throws Exception {
        MvcResult r = create(who, body).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String code(long id) {
        return accounts.query(comp, "select item_cd from mst_item where item_id = ?", String.class, id);
    }

    // ---------- (a) 채번 ----------

    @Test
    void AUTO_면_코드없이_만들면_채번되고_코드를_보내면_거부한다() throws Exception {
        policy(comp, "ITEM_CD_MODE", "AUTO");
        mvc.perform(get(BASE + "/settings").with(admin)).andExpect(jsonPath("$.data.itemCdMode").value("AUTO"));
        long a = createOk(admin, body(null, "자동1", null));
        long b = createOk(admin, body("", "자동2", null));                 // 빈 글자도 "비움"
        assertThat(code(a)).isEqualTo("IT000001");
        assertThat(code(b)).isEqualTo("IT000002");
        // 코드를 보내면 조용히 버리지 않고 칸 오류 — 번호도 쓰지 않는다
        create(admin, body("MY-1", "직접", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("itemCd"))
                .andExpect(jsonPath("$.errors[0].code").value("AUTO_NUMBERED"));
        // 검증 오류(이름 없음)도 번호를 쓰지 않는다 → 다음은 3 (결번 없음)
        create(admin, "{\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"EA\"}").andExpect(status().isBadRequest());
        assertThat(code(createOk(admin, body(null, "자동3", null)))).isEqualTo("IT000003");
        // 규칙은 회사에 기본으로 만들어졌다 — 관리자가 바꿀 수 있다
        assertThat(accounts.query(comp, "select pattern from sys_numbering_rule where rule_cd = 'ITEM'", String.class))
                .isEqualTo("IT{seq:6}");
    }

    @Test
    void 이미_있는_코드는_넘기고_롤백된_신규는_번호를_되돌린다() throws Exception {
        createOk(admin, body("IT000001", "MANUAL 때 직접 넣은 같은 번호", null));    // 기본 MANUAL
        policy(comp, "ITEM_CD_MODE", "AUTO");
        assertThat(code(createOk(admin, body(null, "자동", null)))).isEqualTo("IT000002");
        // ERP 품번 매핑 단계에서 실패(그 사이 다른 품목이 같은 ERP 품번) → 품목·번호 모두 롤백
        long sys = erpSystem(comp, "ERP1");
        long other = accounts.query(comp, "select item_id from mst_item where item_cd = 'IT000001'", Long.class);
        accounts.exec(comp, """
                insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by)
                values (?, ?, 'ITEM', ?, 'E-1', 't', 't')""", comp, sys, other);
        create(admin, body(null, "중복 ERP", "\"erpItemCd\":\"E-1\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("erpItemCd"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        assertThat(code(createOk(admin, body(null, "다음", null)))).isEqualTo("IT000003");
        assertThat(accounts.query(comp, "select count(*) from mst_item", Integer.class)).isEqualTo(3);
    }

    @Test
    void 동시에_만들어도_번호가_겹치거나_비지_않는다() throws Exception {
        policy(comp, "ITEM_CD_MODE", "AUTO");
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                jobs.add(() -> create(admin, body(null, "동시" + k, null)).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(jobs)) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        List<String> codes = list("select item_cd from mst_item order by item_cd");
        assertThat(codes).containsExactly("IT000001", "IT000002", "IT000003", "IT000004", "IT000005", "IT000006",
                "IT000007", "IT000008");
    }

    @Test
    void 규칙이_품목코드_형식에_안_맞으면_409() throws Exception {
        policy(comp, "ITEM_CD_MODE", "AUTO");
        accounts.exec(comp, """
                insert into sys_numbering_rule (comp_cd, rule_cd, rule_nm, pattern, reset_cycle, created_by, updated_by)
                values (?, 'ITEM', '품목', '_X{seq:3}', 'NONE', 't', 't')""", comp);
        create(admin, body(null, "규칙 오류", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_CD_RULE"));
        assertThat(accounts.query(comp, "select count(*) from mst_item", Integer.class)).isZero();
    }

    // ---------- (b) 코드 고정 ----------

    @Test
    void 사용중_품목의_코드_변경은_API도_직접_SQL도_막힌다() throws Exception {
        long id = createOk(admin, body("FG-1", "제품", null));
        long id2 = createOk(admin, body("FG-2", "제품2", null));
        // 사용 중으로 만든다(단위 환산이 걸림 — 기본 단위도 못 바꾸는 품목). 코드 고정은 사용 여부와 관계없다
        accounts.exec(comp, """
                insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by)
                values (?, ?, 'BOX', 'EA', 10, 't', 't')""", comp, id);
        // API: 다른 코드 → 400 FIXED, 다른 품목의 코드로 바꾸기(중복)도 FIXED 가 먼저
        mvc.perform(json(put(BASE + "/" + id), body("FG-9", "제품", "\"rowVer\":0")).with(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("itemCd"))
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        mvc.perform(json(put(BASE + "/" + id), body("fg-2", "제품", "\"rowVer\":0")).with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        // 같은 코드(소문자로 보내도 정리 후 같음)·비움 = 그대로
        mvc.perform(json(put(BASE + "/" + id), body("fg-1", "제품 A", "\"rowVer\":0")).with(admin)).andExpect(status().isOk());
        mvc.perform(json(put(BASE + "/" + id), body(null, "제품 B", "\"rowVer\":1")).with(admin)).andExpect(status().isOk());
        assertThat(code(id)).isEqualTo("FG-1");
        // 직접 SQL — 트리거(55000)
        Throwable t = catchThrowable(() -> accounts.exec(comp, "update mst_item set item_cd = 'FG-X' where item_id = ?", id));
        assertThat(PgErrors.constraint(t)).contains("ck_mst_item_cd_fixed");
        assertThat(PgErrors.sqlState(t)).contains("55000");
        // 같은 값 SET 은 통과(다른 칸 수정 문장)
        assertThat(accounts.exec(comp, "update mst_item set item_cd = item_cd, item_nm = 'x' where item_id = ?", id2)).isEqualTo(1);
        // 잘못 만든 품목 = 새 품목 + 옛 품목 사용 중지 (docs/17 9.2 (b'))
        createOk(admin, body("FG-9", "다시 만든 제품", null));
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":2}]}").with(admin))
                .andExpect(status().isOk());
        assertThat(code(id)).isEqualTo("FG-1");
    }

    // ---------- (c)(d) ERP·고객 품번 ----------

    @Test
    void ERP_품번을_신규와_함께_저장하고_시스템_ERP_고객_어느_번호로도_찾는다() throws Exception {
        create(admin, body("A-1", "ERP 없음", "\"erpItemCd\":\"E-100\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("erpItemCd"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_ERP_SYSTEM"));
        erpSystem(comp, "ERP1");
        long cust = accounts.query(comp, """
                insert into mst_ext_system (comp_cd, system_cd, system_nm, system_type, created_by, updated_by)
                values (?, 'HMC', '가상 완성차', 'CUSTOMER', 't', 't') returning system_id""", Long.class, comp);
        long id = createOk(admin, body("A-1", "브래킷", "\"erpItemCd\":\"Erp-100\""));     // 대소문자 그대로
        mvc.perform(get(BASE + "/" + id).with(admin)).andExpect(jsonPath("$.data.erpItemCd").value("Erp-100"));
        // 같은 ERP 품번을 다른 품목에 — 400 DUPLICATE, 품목도 안 생긴다
        create(admin, body("A-2", "중복", "\"erpItemCd\":\"Erp-100\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        assertThat(accounts.query(comp, "select count(*) from mst_item where item_cd = 'A-2'", Integer.class)).isZero();
        // 형식: 앞뒤 유니코드 공백·제어 문자 거부(7a 규칙 그대로)
        create(admin, body("A-3", "형식", "\"erpItemCd\":\"E\\u0001X\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 고객 품번(외부 품번 API)
        mvc.perform(json(post(BASE + "/" + id + "/ext-ids"), "{\"systemCd\":\"HMC\",\"externalId\":\"84910-2S000\"}").with(admin))
                .andExpect(status().isOk());
        createOk(admin, body("B-1", "다른 품목", null));
        for (String q : List.of("a-1", "erp-1", "84910", "ERP-100")) {
            mvc.perform(get(BASE).param("itemCd", q).with(admin)).andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.list[0].itemCd").value("A-1"))
                    .andExpect(jsonPath("$.list[0].erpItemCd").value("Erp-100"));
        }
        // 와일드카드는 글자로
        mvc.perform(get(BASE).param("itemCd", "%").with(admin)).andExpect(jsonPath("$.total").value(0));
        // 끈 외부 품번으로는 찾지 않는다
        long mapId = accounts.query(comp, "select map_id from mst_ext_id_map where system_id = ?", Long.class, cust);
        mvc.perform(json(put(BASE + "/ext-ids/" + mapId), "{\"externalId\":\"84910-2S000\",\"useYn\":\"N\",\"rowVer\":0}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(get(BASE).param("itemCd", "84910").with(admin)).andExpect(jsonPath("$.total").value(0));
    }

    // ---------- A사로 B사 ----------

    @Test
    void A사로_B사_품목_매핑을_조회하거나_쓸_수_없다() throws Exception {
        erpSystem(comp, "ERP1");
        long id = createOk(admin, body("A-1", "A사 품목", "\"erpItemCd\":\"E-1\""));
        String compB = accounts.newComp("ICB");
        long userB = accounts.newUser(compB, "adminb", PW);
        accounts.grant(compB, userB, "MASTER", "mst-item", "SEARCH", "SAVE", "DELETE", "EXCEL");
        RequestPostProcessor b = accounts.as(compB, userB, "adminb");
        erpSystem(compB, "ERP1");
        mvc.perform(get(BASE + "/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/" + id + "/ext-ids").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(BASE + "/" + id + "/ext-ids"), "{\"systemCd\":\"ERP1\",\"externalId\":\"X\"}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(put(BASE + "/" + id), body("A-1", "가로채기", "\"rowVer\":0")).with(b)).andExpect(status().isNotFound());
        // A사 ERP 품번으로 찾아도 안 나오고, B사는 같은 ERP 품번을 자기 품목에 쓸 수 있다(회사마다 따로)
        mvc.perform(get(BASE).param("itemCd", "E-1").with(b)).andExpect(jsonPath("$.total").value(0));
        createOk(b, body("A-1", "B사 품목", "\"erpItemCd\":\"E-1\""));
        assertThat(accounts.query(comp, "select item_nm from mst_item where item_id = ?", String.class, id)).isEqualTo("A사 품목");
        // B사 AUTO 채번은 A사 번호와 따로
        policy(comp, "ITEM_CD_MODE", "AUTO");
        policy(compB, "ITEM_CD_MODE", "AUTO");
        assertThat(code(createOk(admin, body(null, "A 자동", null)))).isEqualTo("IT000001");
        long bId = createOk(b, body(null, "B 자동", null));
        assertThat(accounts.query(compB, "select item_cd from mst_item where item_id = ?", String.class, bId)).isEqualTo("IT000001");
    }

    // ---------- (f) 기존 품목 회귀 · (g) PULL ----------

    @Test
    void 기존_품목은_AUTO_로_바꿔도_코드_그대로_수정된다() throws Exception {
        long id = createOk(admin, body("84910-2S000", "기존", null));      // MANUAL(기본)
        policy(comp, "ITEM_CD_MODE", "AUTO");
        mvc.perform(json(put(BASE + "/" + id), body("84910-2S000", "기존 이름 변경", "\"rowVer\":0")).with(admin))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + id).with(admin)).andExpect(jsonPath("$.data.itemCd").value("84910-2S000"))
                .andExpect(jsonPath("$.data.itemNm").value("기존 이름 변경"));
        // MANUAL 로 돌리면 다시 직접 입력 필수
        policy(comp, "ITEM_CD_MODE", "MANUAL");
        create(admin, body(null, "코드 없음", null)).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void PULL_은_지금처럼_신규_409_코드_변경은_ERP_OWNED() throws Exception {
        long id = createOk(admin, body("P-1", "제품", null));
        policy(comp, "ERP_ITEM_SYNC_MODE", "PULL");
        policy(comp, "ITEM_CD_MODE", "AUTO");
        create(admin, body(null, "신규", null)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ERP_OWNED"));
        mvc.perform(json(put(BASE + "/" + id), body("P-2", "제품", "\"rowVer\":0")).with(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("itemCd"))
                .andExpect(jsonPath("$.errors[0].code").value("ERP_OWNED"));
        // MES 주인 칸은 그대로 수정
        mvc.perform(json(put(BASE + "/" + id), body("P-1", "제품", "\"remark\":\"현장 메모\",\"rowVer\":0")).with(admin))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from sys_numbering_seq where rule_cd = 'ITEM'", Integer.class)).isZero();
    }

    // ---------- 엑셀 올리기 ----------

    private byte[] fill(List<String[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post("/api/platform/import/templates/MST_ITEM/file").with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getLastCellNum()).isEqualTo((short) 13);           // 버전 2: 끝 열 ERP 품번
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

    /** 코드, 이름, ERP 품번 (나머지 = FG·MAKE·EA) */
    private static String[] row(String cd, String nm, String erp) {
        String[] r = new String[13];
        r[0] = cd;
        r[1] = nm;
        r[3] = "FG";
        r[4] = "MAKE";
        r[5] = "EA";
        r[12] = erp;
        return r;
    }

    private MvcResult upload(byte[] file) throws Exception {
        return mvc.perform(multipart("/api/platform/import/templates/MST_ITEM/jobs")
                .file(new MockMultipartFile("file", "i.xlsx", XLSX, file)).param("mode", "UPSERT").with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn();
    }

    private ResultActions execute(long job, boolean skip) throws Exception {
        int ver = accounts.query(comp, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post("/api/platform/import/jobs/" + job + "/execute").with(csrf()).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + ver + ",\"skipErrors\":" + skip + "}"));
    }

    @Test
    void 올리기_AUTO_빈_코드는_실행때_채번하고_ERP_품번을_붙인다() throws Exception {
        erpSystem(comp, "ERP1");
        long old = createOk(admin, body("OLD-1", "기존", "\"erpItemCd\":\"E-OLD\""));
        long bare = createOk(admin, body("OLD-2", "ERP 없는 기존", null));
        policy(comp, "ITEM_CD_MODE", "AUTO");
        MvcResult up = upload(fill(List.of(
                row(null, "새1", "E-1"),
                row(null, "새2", null),
                row("NEW-9", "AUTO 인데 새 코드", null),       // AUTO_NUMBERED
                row(null, "파일 안 중복", "E-1"),               // 둘째 E-1 → FILE_DUPLICATE
                row(null, "남의 ERP 품번", "E-OLD"),            // DUPLICATE
                row("OLD-1", "기존 이름 바꿈", "E-OTHER"),      // 이미 다른 ERP 품번 → FIXED
                row("OLD-2", "기존에 ERP 붙임", "E-2"))));
        long job = ((Number) JsonPath.read(up.getResponse().getContentAsString(), "$.data.jobId")).longValue();
        List<String> errs = list("""
                select row_no || ':' || (e ->> 'field') || ':' || (e ->> 'code') from sys_import_row r,
                       jsonb_array_elements(r.errors) e where r.job_id = ? order by row_no""", job);
        assertThat(errs).containsExactly("4:itemCd:AUTO_NUMBERED", "5:erpItemCd:FILE_DUPLICATE", "6:erpItemCd:DUPLICATE",
                "7:erpItemCd:FIXED");
        // 미리보기는 번호를 쓰지 않는다
        assertThat(accounts.query(comp, "select count(*) from sys_numbering_seq where rule_cd = 'ITEM'", Integer.class)).isZero();
        execute(job, true).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(2))
                .andExpect(jsonPath("$.data.result.updated").value(1));
        assertThat(list("""
                select i.item_cd || '=' || coalesce(m.external_id, '-') from mst_item i
                  left join mst_ext_id_map m on m.internal_id = i.item_id and m.entity_type = 'ITEM' and m.use_yn = 'Y'
                 order by i.item_cd"""))
                .containsExactly("IT000001=E-1", "IT000002=-", "OLD-1=E-OLD", "OLD-2=E-2");
        assertThat(code(old)).isEqualTo("OLD-1");
        assertThat(code(bare)).isEqualTo("OLD-2");
    }

    @Test
    void 올리기_MANUAL_은_코드_필수_그대로() throws Exception {
        MvcResult up = upload(fill(List.of(row(null, "코드 없음", null), row("M-1", "직접", null))));
        long job = ((Number) JsonPath.read(up.getResponse().getContentAsString(), "$.data.jobId")).longValue();
        assertThat(list("""
                select (e ->> 'code') from sys_import_row r, jsonb_array_elements(r.errors) e where r.job_id = ?""",
                job)).containsExactly("REQUIRED");
        execute(job, true).andExpect(jsonPath("$.data.result.inserted").value(1));
        assertThat(accounts.query(comp, "select item_cd from mst_item", String.class)).isEqualTo("M-1");
    }
}
