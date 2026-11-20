package io.smarthappy.platform.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 첨부 (P0 6단계): 확장자·크기·시그니처·이름 검증, 대상 화면 권한, 회사 격리 */
@AutoConfigureMockMvc
class FileApiTest extends PostgresTestBase {

    private static final String PROG = "sys-my-info";
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0};

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    long userId;
    RequestPostProcessor writer;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("FL");
        userId = accounts.newUser(comp, "han", "Silver-orchard-27");
        accounts.grant(comp, userId, "WRITER", PROG, "SEARCH", "SAVE");
        writer = accounts.as(comp, userId, "han");
    }

    private ResultActions upload(RequestPostProcessor who, String name, byte[] content) throws Exception {
        return upload(who, PROG, "ROW-1", name, content);
    }

    private ResultActions upload(RequestPostProcessor who, String targetType, String targetId, String name,
            byte[] content) throws Exception {
        return mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", name,
                        "application/pdf", content))
                .param("targetType", targetType).param("targetId", targetId).with(who).with(csrf()));
    }

    private long uploadOk(String name, byte[] content) throws Exception {
        MvcResult r = upload(writer, name, content).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data.fileId")).longValue();
    }

    // ---------- 정상 ----------

    @Test
    void 올리고_목록_보고_그대로_내려받는다() throws Exception {
        long id = uploadOk("검사성적서.pdf", PDF);
        mvc.perform(get("/api/platform/files").param("targetType", PROG).param("targetId", "ROW-1").with(writer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].name").value("검사성적서.pdf"))
                .andExpect(jsonPath("$.list[0].size").value(PDF.length))
                .andExpect(jsonPath("$.list[0].sha256").value(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(PDF))))
                .andExpect(jsonPath("$.list[0].storageKey").doesNotExist());
        MvcResult r = mvc.perform(get("/api/platform/files/" + id).with(writer))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        assertThat(r.getResponse().getContentAsByteArray()).isEqualTo(PDF);
    }

    @Test
    void 삭제하면_목록과_내려받기에서_사라지고_감사에_남는다() throws Exception {
        long id = uploadOk("a.pdf", PDF);
        mvc.perform(post("/api/platform/files/" + id + "/delete").with(writer).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/platform/files/" + id).with(writer)).andExpect(status().isNotFound());
        mvc.perform(post("/api/platform/files/" + id + "/delete").with(writer).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'sys_file' and row_key = ?",
                Integer.class, String.valueOf(id))).isEqualTo(2);
    }

    // ---------- 이상 사용: 형식 ----------

    @Test
    void 확장자만_pdf_로_바꾼_실행파일은_거부() throws Exception {
        upload(writer, "report.pdf", EXE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }

    @Test
    void 실행_스크립트_형식은_회사가_허용해도_거부() throws Exception {
        runner.run(comp, () -> policies.set(Policies.FILE_ALLOWED_EXT, null, "pdf,exe,html,svg,js", "t"));
        upload(writer, "tool.exe", EXE).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        upload(writer, "page.html", "<script>x</script>".getBytes()).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        upload(writer, "logo.svg", "<svg onload=x>".getBytes()).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }

    @Test
    void 이중_확장자와_끝_점_우회는_마지막_확장자로_판정() throws Exception {
        upload(writer, "invoice.pdf.exe", EXE).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        upload(writer, "invoice.exe.", EXE).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        upload(writer, "invoice.exe . .", EXE).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }

    @Test
    void 양방향_제어문자로_확장자를_속인_이름은_실제_확장자로_판정() throws Exception {
        // 화면에는 "invoice‮fdp.exe" 가 "invoiceexe.pdf" 처럼 보인다
        upload(writer, "invoice‮fdp.exe", EXE).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }

    @Test
    void 경로가_섞인_이름은_마지막_이름만_쓰고_저장_경로에_쓰지_않는다() throws Exception {
        long id = uploadOk("../../../etc/passwd.pdf", PDF);
        long id2 = uploadOk("..\\..\\win.pdf", PDF);
        assertThat(accounts.query(comp, "select orig_name from sys_file where file_id = ?", String.class, id))
                .isEqualTo("passwd.pdf");
        assertThat(accounts.query(comp, "select orig_name from sys_file where file_id = ?", String.class, id2))
                .isEqualTo("win.pdf");
        assertThat(accounts.query(comp, "select storage_key from sys_file where file_id = ?", String.class, id))
                .startsWith(comp + "/").doesNotContain("..").doesNotContain("passwd");
    }

    @Test
    void 이름이_없거나_점으로_시작하면_거부() throws Exception {
        upload(writer, "", PDF).andExpect(status().isBadRequest());
        upload(writer, ".pdf", PDF).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        upload(writer, "noext", PDF).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }

    @Test
    void 아주_긴_이름은_확장자를_유지한_채_자른다() throws Exception {
        long id = uploadOk("가".repeat(400) + ".pdf", PDF);
        String name = accounts.query(comp, "select orig_name from sys_file where file_id = ?", String.class, id);
        assertThat(name).hasSize(255).endsWith(".pdf");
    }

    @Test
    void 빈_파일과_정책_크기_초과는_거부() throws Exception {
        upload(writer, "empty.pdf", new byte[0]).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        runner.run(comp, () -> policies.set(Policies.FILE_MAX_MB, null, "1", "t"));
        byte[] big = new byte[1024 * 1024 + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        upload(writer, "big.pdf", big).andExpect(jsonPath("$.code").value("FILE_REJECTED"))
                .andExpect(jsonPath("$.details[0]").value(containsString("1")));
        byte[] exact = new byte[1024 * 1024];
        System.arraycopy(PDF, 0, exact, 0, PDF.length);
        upload(writer, "exact.pdf", exact).andExpect(status().isOk());
    }

    @Test
    void 텍스트_형식에_바이너리를_넣으면_거부() throws Exception {
        upload(writer, "data.csv", new byte[] {'a', ',', 0, 'b'}).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        upload(writer, "data.csv", "품번,수량\nA-1,10\n".getBytes(StandardCharsets.UTF_8)).andExpect(status().isOk());
    }

    @Test
    void 거부는_보안_로그에_남는다() throws Exception {
        upload(writer, "report.pdf", EXE);
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'FILE_REJECTED'",
                Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from sys_file", Integer.class)).isZero();
    }

    // ---------- 이상 사용: 권한·대상 ----------

    @Test
    void 조회_권한만_있으면_내려받기만_되고_올리기_삭제는_403() throws Exception {
        long id = uploadOk("a.pdf", PDF);
        long reader = accounts.newUser(comp, "reader", "Silver-orchard-27");
        accounts.grant(comp, reader, "READER", PROG, "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader");
        mvc.perform(get("/api/platform/files/" + id).with(r)).andExpect(status().isOk());
        upload(r, "b.pdf", PDF).andExpect(status().isForbidden());
        mvc.perform(post("/api/platform/files/" + id + "/delete").with(r).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void 권한_없는_사용자는_목록도_403() throws Exception {
        uploadOk("a.pdf", PDF);
        long nobody = accounts.newUser(comp, "nobody", "Silver-orchard-27");
        mvc.perform(get("/api/platform/files").param("targetType", PROG).param("targetId", "ROW-1")
                .with(accounts.as(comp, nobody, "nobody"))).andExpect(status().isForbidden());
    }

    @Test
    void 없는_화면코드나_잘못된_대상키는_거부() throws Exception {
        upload(writer, "no-such-prog", "ROW-1", "a.pdf", PDF).andExpect(status().isForbidden());
        upload(writer, "SYS_MY_INFO", "ROW-1", "a.pdf", PDF).andExpect(status().isBadRequest());
        upload(writer, PROG, "../ROW-1", "a.pdf", PDF).andExpect(status().isBadRequest());
        upload(writer, PROG, "x".repeat(65), "a.pdf", PDF).andExpect(status().isBadRequest());
    }

    @Test
    void B사_사용자가_A사_첨부_번호로_내려받기_삭제_목록을_시도() throws Exception {
        long id = uploadOk("a.pdf", PDF);
        String other = accounts.newComp("FL");
        long bUser = accounts.newUser(other, "han", "Silver-orchard-27");
        accounts.grant(other, bUser, "WRITER", PROG, "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "han");
        mvc.perform(get("/api/platform/files/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(post("/api/platform/files/" + id + "/delete").with(b).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/platform/files").param("targetType", PROG).param("targetId", "ROW-1").with(b))
                .andExpect(jsonPath("$.list.length()").value(0));
        assertThat(accounts.query(comp, "select use_yn from sys_file where file_id = ?", String.class, id))
                .isEqualTo("Y");
    }

    @Test
    void 파일_없이_올리기_요청은_거부() throws Exception {
        mvc.perform(multipart("/api/platform/files").param("targetType", PROG).param("targetId", "ROW-1")
                .with(writer).with(csrf())).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }

    @Test
    void 비로그인_첨부_요청은_401() throws Exception {
        mvc.perform(get("/api/platform/files/1")).andExpect(status().isUnauthorized());
    }
}
