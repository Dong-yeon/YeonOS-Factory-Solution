package io.smarthappy.module.master.cust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.transaction.IllegalTransactionStateException;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 거래처관리 (P0 8단계 표준 참조 화면) API + 엑셀·그리드 설정·피드백·변경 이력 */
@AutoConfigureMockMvc
class CustApiTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String BASE = "/api/master/customers";
    private static final String PROG = "mst-cust";
    /** 검증식이 맞는 사업자번호 */
    private static final String BIZ_OK = "1248100998";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    @Autowired
    JobLock jobLock;

    @Autowired
    ExcelExportService excel;

    String comp;
    long userId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CU");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", PROG, "SEARCH", "SAVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions create(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(json(post(BASE), body).with(who));
    }

    private long createOk(String cd, String nm) throws Exception {
        MvcResult r = create(admin, "{\"custCd\":\"" + cd + "\",\"custNm\":\"" + nm + "\",\"custType\":\"SALES\"}")
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int rowVer(long id) {
        return accounts.query(comp, "select row_ver from mst_cust where cust_id = ?", Integer.class, id);
    }

    private XSSFWorkbook export(RequestPostProcessor who, String body) throws Exception {
        MvcResult r = mvc.perform(json(post(BASE + "/export"), body).with(who))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", ExcelExportServiceTestAccess.MEDIA_TYPE))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andReturn();
        return new XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()));
    }

    // ---------- 정상 ----------

    @Test
    void 등록_목록_상세_수정_사용중지() throws Exception {
        long id = createOk("c-001", "한빛정밀");
        mvc.perform(get(BASE).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].custCd").value("C-001"))   // 대문자로 정리
                .andExpect(jsonPath("$.list[0].contactCnt").value(0));
        mvc.perform(json(put(BASE + "/" + id), "{\"custCd\":\"C-001\",\"custNm\":\"한빛정밀(주)\",\"custType\":\"BOTH\","
                + "\"bizNo\":\"124-81-00998\",\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + id).with(admin))
                .andExpect(jsonPath("$.data.cust.custNm").value("한빛정밀(주)"))
                .andExpect(jsonPath("$.data.cust.bizNo").value(BIZ_OK))
                .andExpect(jsonPath("$.data.cust.rowVer").value(1));
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":1}]}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(get(BASE).param("useYn", "Y").with(admin)).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void 페이징과_정렬_마지막엔_PK_로_고정() throws Exception {
        for (int i = 1; i <= 5; i++) {
            createOk("P" + i, i % 2 == 0 ? "같은이름" : "이름" + i);
        }
        mvc.perform(get(BASE).param("page", "2").param("size", "2").param("sort", "custCd,desc").with(admin))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.list[0].custCd").value("P3"))
                .andExpect(jsonPath("$.list[1].custCd").value("P2"));
        mvc.perform(get(BASE).param("total", "false").with(admin)).andExpect(jsonPath("$.total").doesNotExist());
    }

    @Test
    void 열_정의는_사용자_언어로_내려온다() throws Exception {
        mvc.perform(get(BASE + "/columns").with(admin))
                .andExpect(jsonPath("$.list[0].field").value("custCd"))
                .andExpect(jsonPath("$.list[0].title").value("거래처코드"))
                .andExpect(jsonPath("$.list[2].codeGrp").value("CUST_TYPE"));
        mvc.perform(get(BASE + "/columns").with(accounts.as(comp, userId, "admin1", "en")))
                .andExpect(jsonPath("$.list[0].title").value("Code"));
    }

    @Test
    void 엑셀은_목록과_같은_조건_정렬이고_코드명_출력정보_시트가_있다() throws Exception {
        createOk("E1", "가나");
        createOk("E2", "다라");
        createOk("X9", "마바");
        try (XSSFWorkbook wb = export(admin, "{\"custCd\":\"E\",\"sort\":[\"custCd,desc\"],"
                + "\"columns\":[\"custCd\",\"custNm\",\"custType\",\"updatedAt\"]}")) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("거래처코드");
            assertThat(s.getLastRowNum()).isEqualTo(2);
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("E2");
            assertThat(s.getRow(1).getCell(2).getStringCellValue()).isEqualTo("매출처");
            assertThat(s.getRow(1).getCell(3).getLocalDateTimeCellValue()).isNotNull();
            assertThat(wb.getSheetAt(1).getRow(3).getCell(1).getStringCellValue()).isEqualTo("2");
            assertThat(wb.getSheetAt(1).getRow(4).getCell(1).getStringCellValue()).isEqualTo("custCd");
        }
        assertThat(accounts.query(comp, """
                select detail from sys_access_log where event_cd = 'ACTION' and action_cd = 'EXCEL'""", String.class))
                .isEqualTo("rows=2;cols=4;keys=custCd");
    }

    @Test
    void 담당자_일괄_저장() throws Exception {
        long id = createOk("D1", "담당자테스트");
        MvcResult r = mvc.perform(json(post(BASE + "/" + id + "/contacts/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"n1\",\"contactNm\":\"김과장\",\"mainYn\":\"Y\",\"email\":\"kim@ex.co.kr\"},"
                        + "{\"rowKey\":\"n2\",\"contactNm\":\"이대리\"}]}").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.created.length()").value(2)).andReturn();
        long c2 = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data.created[1].id")).longValue();
        mvc.perform(json(post(BASE + "/" + id + "/contacts/batch").param("rowVer", "1"),
                "{\"updated\":[{\"rowKey\":\"u1\",\"contactId\":" + c2 + ",\"rowVer\":0,\"contactNm\":\"이과장\"}]}")
                .with(admin)).andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + id).with(admin))
                .andExpect(jsonPath("$.data.contacts.length()").value(2))
                .andExpect(jsonPath("$.data.contacts[0].mainYn").value("Y"))
                .andExpect(jsonPath("$.data.cust.contactCnt").value(2));
    }

    // ---------- 이상 사용: 검증 ----------

    @Test
    void 검증_오류는_한_번에_모두_필드별로() throws Exception {
        create(admin, "{\"custCd\":\" \",\"custNm\":\"" + "가".repeat(201) + "\",\"custType\":\"VIP\","
                + "\"bizNo\":\"1234567890\",\"email\":\"not-mail\",\"tel\":\"010<script>\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItems("custCd", "custNm", "custType", "bizNo", "email", "tel")))
                .andExpect(jsonPath("$.errors[?(@.field=='bizNo')].code").value("CHECKSUM"))
                .andExpect(jsonPath("$.errors[?(@.field=='custNm')].message").value("200자 이하로 입력해 주세요."));
    }

    @Test
    void 코드는_대소문자_구분_없이_중복_불가() throws Exception {
        createOk("ABC", "첫째");
        create(admin, "{\"custCd\":\"abc\",\"custNm\":\"둘째\",\"custType\":\"SALES\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        create(admin, "{\"custCd\":\"A B\",\"custNm\":\"공백\",\"custType\":\"SALES\"}")
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
    }

    @Test
    void 오래된_rowVer_수정은_409_rowVer_없으면_400() throws Exception {
        long id = createOk("V1", "버전");
        String body = "{\"custCd\":\"V1\",\"custNm\":\"A\",\"custType\":\"SALES\",\"rowVer\":0}";
        mvc.perform(json(put(BASE + "/" + id), body).with(admin)).andExpect(status().isOk());
        mvc.perform(json(put(BASE + "/" + id), body).with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(json(put(BASE + "/" + id), "{\"custCd\":\"V1\",\"custNm\":\"A\",\"custType\":\"SALES\"}")
                .with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":0}]}").with(admin))
                .andExpect(status().isConflict());
    }

    @Test
    void 정렬_페이지_파라미터_조작은_400() throws Exception {
        for (String bad : List.of("cust_nm;drop table mst_cust", "custNm,sideways", "password,asc", "custNm,asc,x")) {
            mvc.perform(get(BASE).param("sort", bad).with(admin)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE).param("sort", "custNm").param("sort", "custCd").param("sort", "useYn")
                .param("sort", "updatedAt").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("sort", "custNm").param("sort", "custNm,desc").with(admin))
                .andExpect(status().isBadRequest());
        for (String[] p : new String[][] {{"size", "0"}, {"size", "100000"}, {"page", "0"}, {"page", "-1"},
                {"size", "abc"}}) {
            mvc.perform(get(BASE).param(p[0], p[1]).with(admin)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE).param("custType", "SALES' or '1'='1").with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void 검색어의_와일드카드는_글자로_취급() throws Exception {
        createOk("W1", "100%정밀");
        createOk("W2", "백퍼센트");
        createOk("W_3", "밑줄");
        createOk("WX3", "엑스");
        mvc.perform(get(BASE).param("custNm", "%").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(BASE).param("custCd", "W_").with(admin)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].custCd").value("W_3"));
    }

    @Test
    void 같은_멱등키로_두_번_등록하면_한_건만() throws Exception {
        String body = "{\"custCd\":\"IDEM\",\"custNm\":\"연타\",\"custType\":\"SALES\"}";
        mvc.perform(json(post(BASE), body).header("X-Idempotency-Key", "cust-create-0001").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(post(BASE), body.replace("IDEM", "IDEM2")).header("X-Idempotency-Key", "cust-create-0001")
                .with(admin)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from mst_cust", Integer.class)).isEqualTo(1);
    }

    // ---------- 이상 사용: 권한·회사 ----------

    @Test
    void 조회_권한만_있으면_등록_수정_삭제_엑셀_403() throws Exception {
        long id = createOk("R1", "읽기");
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", PROG, "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(get(BASE).with(r)).andExpect(status().isOk());
        create(r, "{\"custCd\":\"R2\",\"custNm\":\"x\",\"custType\":\"SALES\"}").andExpect(status().isForbidden());
        mvc.perform(json(put(BASE + "/" + id), "{\"custCd\":\"R1\",\"custNm\":\"x\",\"custType\":\"SALES\",\"rowVer\":0}")
                .with(r)).andExpect(status().isForbidden());
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":0}]}").with(r))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(BASE + "/export"), "{}").with(r)).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, """
                select count(*) from sys_access_log where event_cd = 'PERM_DENIED' and action_cd = 'EXCEL'""",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void 저장_권한만_있으면_담당자_삭제는_403() throws Exception {
        long id = createOk("S1", "삭제권한");
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", PROG, "SEARCH", "SAVE");
        mvc.perform(json(post(BASE + "/" + id + "/contacts/batch").param("rowVer", "0"),
                "{\"deleted\":[{\"rowKey\":\"d1\",\"contactId\":1,\"rowVer\":0}]}").with(accounts.as(comp, saver, "saver1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void B사_사용자가_A사_거래처를_조회_수정_삭제_담당자_엑셀() throws Exception {
        long id = createOk("A1", "A사거래처");
        String other = accounts.newComp("CU");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", PROG, "SEARCH", "SAVE", "DELETE", "EXCEL");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");

        mvc.perform(get(BASE).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(BASE + "/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(BASE + "/" + id), "{\"custCd\":\"A1\",\"custNm\":\"탈취\",\"custType\":\"SALES\",\"rowVer\":0}")
                .with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":0}]}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(BASE + "/" + id + "/contacts/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"x1\",\"contactNm\":\"침입\"}]}").with(b)).andExpect(status().isNotFound());
        // B사에도 같은 코드는 따로 만들 수 있다 (회사별 유일)
        create(b, "{\"custCd\":\"A1\",\"custNm\":\"B사거래처\",\"custType\":\"SALES\"}").andExpect(status().isOk());
        try (XSSFWorkbook wb = export(b, "{}")) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("B사거래처");
        }
        assertThat(accounts.query(comp, "select cust_nm from mst_cust where cust_id = ?", String.class, id))
                .isEqualTo("A사거래처");
    }

    // ---------- 이상 사용: 담당자 일괄 저장 ----------

    @Test
    void 주_담당자_두_명은_행별_오류로_전부_취소() throws Exception {
        long id = createOk("M1", "주담당");
        mvc.perform(json(post(BASE + "/" + id + "/contacts/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"a\",\"contactNm\":\"하나\",\"mainYn\":\"Y\"},"
                        + "{\"rowKey\":\"b\",\"contactNm\":\"둘\",\"mainYn\":\"Y\"},{\"rowKey\":\"c\",\"contactNm\":\" \"}]}")
                .with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='a')].code").value("ONE_MAIN"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='c')].code").value("REQUIRED"));
        assertThat(accounts.query(comp, "select count(*) from mst_cust_contact", Integer.class)).isZero();
        assertThat(rowVer(id)).isZero();
    }

    @Test
    void 같은_거래처를_두_사람이_고치면_뒤쪽은_409() throws Exception {
        long id = createOk("T1", "동시");
        mvc.perform(json(post(BASE + "/" + id + "/contacts/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"a\",\"contactNm\":\"먼저\"}]}").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(BASE + "/" + id + "/contacts/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"a\",\"contactNm\":\"나중\"}]}").with(admin)).andExpect(status().isConflict());
    }

    @Test
    void 다른_거래처의_담당자_번호로_수정하면_409_행키() throws Exception {
        long a = createOk("K1", "가");
        long b = createOk("K2", "나");
        MvcResult r = mvc.perform(json(post(BASE + "/" + a + "/contacts/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"a\",\"contactNm\":\"가담당\"}]}").with(admin)).andReturn();
        long contactOfA = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data.created[0].id")).longValue();
        mvc.perform(json(post(BASE + "/" + b + "/contacts/batch").param("rowVer", "0"),
                "{\"updated\":[{\"rowKey\":\"zz\",\"contactId\":" + contactOfA + ",\"rowVer\":0,\"contactNm\":\"가로채기\"}]}")
                .with(admin)).andExpect(status().isConflict()).andExpect(jsonPath("$.errors[0].rowKey").value("zz"));
        assertThat(accounts.query(comp, "select contact_nm from mst_cust_contact where contact_id = ?", String.class,
                contactOfA)).isEqualTo("가담당");
    }

    @Test
    void 중복_행키와_빈_행은_400() throws Exception {
        long id = createOk("RK", "행키");
        mvc.perform(json(post(BASE + "/" + id + "/contacts/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"a\",\"contactNm\":\"x\"},{\"rowKey\":\"a\",\"contactNm\":\"y\"}]}")
                .with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(post(BASE + "/" + id + "/contacts/batch").param("rowVer", "0"),
                "{\"created\":[null]}").with(admin)).andExpect(status().isBadRequest());
        runner.run(comp, () -> policies.set(Policies.GRID_BATCH_MAX_ROWS, null, "2", "t"));
        mvc.perform(json(post(BASE + "/" + id + "/contacts/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"a\",\"contactNm\":\"x\"},{\"rowKey\":\"b\",\"contactNm\":\"y\"},"
                        + "{\"rowKey\":\"c\",\"contactNm\":\"z\"}]}").with(admin)).andExpect(status().isBadRequest());
    }

    // ---------- 이상 사용: 엑셀 ----------

    @Test
    void 엑셀_수식_주입은_글자로_막는다() throws Exception {
        String[] names = {"=HYPERLINK(\\\"http://evil\\\",\\\"click\\\")", "+1+1", "-2+3", "@SUM(A1)", "정상"};
        for (int i = 0; i < names.length; i++) {
            createOk("F" + i, names[i]);
        }
        try (XSSFWorkbook wb = export(admin, "{\"columns\":[\"custNm\"]}")) {
            List<String> cells = new ArrayList<>();
            Sheet s = wb.getSheetAt(0);
            for (int r = 1; r <= s.getLastRowNum(); r++) {
                cells.add(s.getRow(r).getCell(0).getStringCellValue());
            }
            assertThat(cells).containsExactly("'=HYPERLINK(\"http://evil\",\"click\")", "'+1+1", "'-2+3", "'@SUM(A1)", "정상");
            assertThat(s.getRow(1).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
        }
    }

    @Test
    void 정의_밖_열이나_중복_열은_400() throws Exception {
        for (String cols : List.of("[\"custCd\",\"pwdHash\"]", "[\"custNm; drop table x\"]", "[\"custCd\",\"custCd\"]")) {
            mvc.perform(json(post(BASE + "/export"), "{\"columns\":" + cols + "}").with(admin))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void 상한을_넘으면_파일_대신_400() throws Exception {
        runner.run(comp, () -> policies.set(Policies.EXCEL_EXPORT_MAX_ROWS, null, "100", "t"));
        accounts.exec(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                select ?, 'BULK' || g, '대량' || g, 'SALES', 't', 't' from generate_series(1, 101) g""", comp);
        mvc.perform(json(post(BASE + "/export"), "{}").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"))
                .andExpect(jsonPath("$.message").value(containsString("101")));
        try (XSSFWorkbook wb = export(admin, "{\"custCd\":\"BULK1\"}")) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(13); // BULK1, BULK10~19, BULK100, BULK101
        }
    }

    @Test
    void 회사의_동시_내보내기_자리가_차면_429() throws Exception {
        runner.run(comp, () -> policies.set(Policies.EXCEL_EXPORT_CONCURRENT_PER_COMP, null, "1", "t"));
        createOk("Z1", "동시");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> runner.call(comp, () -> {
                jobLock.tryLock("excel-export-0");
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            mvc.perform(json(post(BASE + "/export"), "{}").with(admin)).andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("EXPORT_BUSY"));
            // 다른 회사는 영향 없음
            String other = accounts.newComp("CU");
            long bUser = accounts.newUser(other, "admin1", PW);
            accounts.grant(other, bUser, "MASTER", PROG, "SEARCH", "EXCEL");
            export(accounts.as(other, bUser, "admin1"), "{}").close();
        } finally {
            release.countDown();
            pool.shutdown();
            // 잠금을 쥔 트랜잭션이 끝난 뒤에 다시 내보낸다(기다리지 않으면 가끔 아직 잠겨 429 — 2026-10-20 전체 check 에서 발견)
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        export(admin, "{}").close();
    }

    @Test
    void 내보내기를_트랜잭션_밖에서_부르면_0건_파일_대신_예외() {
        assertThatThrownBy(() -> excel.export(null, null, w -> { }))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---------- 그리드 설정·피드백·변경 이력 ----------

    @Test
    void 그리드_설정_저장_복원_기본값() throws Exception {
        String url = "/api/platform/grid-state/" + PROG + "/main";
        mvc.perform(json(put(url), "{\"defHash\":\"abcdef12\",\"state\":{\"cols\":[{\"f\":\"custNm\",\"w\":5000,"
                + "\"h\":false,\"evil\":\"<script>\"},{\"f\":\"custCd\",\"h\":true}],\"sort\":[{\"f\":\"custNm\",\"d\":true}],"
                + "\"pageSize\":100,\"script\":\"alert(1)\"}}").with(admin)).andExpect(status().isOk());
        mvc.perform(get(url).with(admin))
                .andExpect(jsonPath("$.data.state.cols[0].w").value(1000))
                .andExpect(jsonPath("$.data.state.cols[0].evil").doesNotExist())
                .andExpect(jsonPath("$.data.state.cols[1].h").value(true))
                .andExpect(jsonPath("$.data.state.script").doesNotExist())
                .andExpect(jsonPath("$.data.state.pageSize").value(100));
        long other = accounts.newUser(comp, "other1", PW);
        accounts.grant(comp, other, "MASTER", PROG, "SEARCH");
        mvc.perform(get(url).with(accounts.as(comp, other, "other1"))).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(delete(url).with(csrf()).with(admin)).andExpect(status().isOk());
        mvc.perform(get(url).with(admin)).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 그리드_설정_조작은_400_권한_없으면_403() throws Exception {
        String url = "/api/platform/grid-state/" + PROG + "/main";
        mvc.perform(json(put(url), "{\"defHash\":\"abcdef12\",\"state\":{\"cols\":[{\"f\":\"a;drop\"}]}}").with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put(url), "{\"defHash\":\"ZZZ\",\"state\":{}}").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/platform/grid-state/" + PROG + "/Main..x"), "{\"defHash\":\"abcdef12\",\"state\":{}}")
                .with(admin)).andExpect(status().isBadRequest());
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            many.append(i == 0 ? "" : ",").append("{\"f\":\"c").append(i).append("\"}");
        }
        mvc.perform(json(put(url), "{\"defHash\":\"abcdef12\",\"state\":{\"cols\":" + many + "]}}").with(admin))
                .andExpect(status().isBadRequest());
        long nobody = accounts.newUser(comp, "nobody1", PW);
        mvc.perform(get(url).with(accounts.as(comp, nobody, "nobody1"))).andExpect(status().isForbidden());
    }

    @Test
    void 화면_피드백과_도배_방지() throws Exception {
        String body = "{\"progCd\":\"" + PROG + "\",\"content\":\"검색 버튼이 너무 작아요\"}";
        for (int i = 0; i < 10; i++) {
            mvc.perform(json(post("/api/platform/feedback"), body).with(admin)).andExpect(status().isOk());
        }
        mvc.perform(json(post("/api/platform/feedback"), body).with(admin)).andExpect(status().isTooManyRequests());
        mvc.perform(json(post("/api/platform/feedback"), "{\"progCd\":\"" + PROG + "\",\"content\":\"  \"}").with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/platform/feedback"), "{\"progCd\":\"" + PROG + "\",\"content\":\""
                + "가".repeat(2001) + "\"}").with(admin)).andExpect(status().isBadRequest());
        long nobody = accounts.newUser(comp, "nobody2", PW);
        mvc.perform(json(post("/api/platform/feedback"), body).with(accounts.as(comp, nobody, "nobody2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 변경_이력은_등록된_테이블만_권한자만_자기_회사만() throws Exception {
        long id = createOk("H1", "이력");
        mvc.perform(json(put(BASE + "/" + id), "{\"custCd\":\"H1\",\"custNm\":\"이력2\",\"custType\":\"SALES\",\"rowVer\":0}")
                .with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/audit").param("table", "mst_cust").param("key", String.valueOf(id)).with(admin))
                .andExpect(jsonPath("$.list.length()").value(2))
                .andExpect(jsonPath("$.list[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.list[0].actor").value("u" + userId + ":admin1"));
        mvc.perform(get("/api/platform/audit").param("table", "sys_comp").param("key", "1").with(admin))
                .andExpect(status().isBadRequest());
        // sys_user 는 사용자 관리(docs/36) 화면에 등록됨 — 그 화면 조회 권한이 없으면 403
        mvc.perform(get("/api/platform/audit").param("table", "sys_user").param("key", "1").with(admin))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/audit").param("table", "mst_cust").param("key", "1' or '1'='1").with(admin))
                .andExpect(status().isBadRequest());
        long nobody = accounts.newUser(comp, "nobody3", PW);
        mvc.perform(get("/api/platform/audit").param("table", "mst_cust").param("key", String.valueOf(id))
                .with(accounts.as(comp, nobody, "nobody3"))).andExpect(status().isForbidden());
        String other = accounts.newComp("CU");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", PROG, "SEARCH");
        mvc.perform(get("/api/platform/audit").param("table", "mst_cust").param("key", String.valueOf(id))
                .with(accounts.as(other, bUser, "admin1"))).andExpect(jsonPath("$.list.length()").value(0));
    }

    @Test
    void 사업자번호_검증식() {
        assertThat(CustService.bizNoChecksum(BIZ_OK)).isTrue();
        assertThat(CustService.bizNoChecksum("1248100999")).isFalse();
    }

    /** 테스트에서 상수 접근용 */
    static final class ExcelExportServiceTestAccess {
        static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
}
