package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PCN 메일에 변경 통지서 첨부 (docs/39 33장 F1~F7 + 상위 덧붙임) — 보낼 때 그 시점 통지서를 PCN-{id} 대상에 사본(같은 내용이면 재사용)으로,
 * 아웃박스 첨부 1개, 이력 notice_file_id(insert 때만, 바꿀 수 없음). 결재 전이면 본문 초안 안내. PCN-{id} 는 사용자 올리기·지우기 잠김(API 직접).
 * 사본 내려받기 = plm-eco 조회 권한, A사로 B사 404. 허용 목록에서 xlsx 를 빼면 400 이고 이력·사본 없음.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class PlmPcnMailAttachTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";

    @MockitoBean
    JavaMailSender sender;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    String comp;
    RequestPostProcessor owner;
    RequestPostProcessor viewer;
    String today;
    long cA;
    long cB;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PMA");
        long ownerId = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, ownerId, "ENG", "plm-eco", "SEARCH", "SAVE");
        owner = accounts.as(comp, ownerId, "eng1");
        long viewId = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewId, "VIEW", "plm-eco", "SEARCH");
        viewer = accounts.as(comp, viewId, "view1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C1', 'C1 고객', 'SALES', 't', 't')", comp);
        cA = contact("가 대표", "a.main@cust.test");
        cB = contact("나 품질", "b.qa@cust.test");
    }

    private long contact(String nm, String email) {
        return accounts.query(comp, """
                insert into mst_cust_contact (comp_cd, cust_id, contact_nm, email, main_yn, use_yn, created_by, updated_by)
                select ?, cust_id, ?, ?, 'N', 'Y', 't', 't' from mst_cust where cust_cd = 'C1' returning contact_id""",
                Long.class, comp, nm, email);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long data(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 작성 중 지시 + 열린 통지 → {ecoId, pcnId} */
    private long[] pcn(String title) throws Exception {
        long eco = data(mvc.perform(json(post(ECO), "{\"title\":\"" + title + "\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\"}").with(owner)));
        long pcn = data(mvc.perform(json(post(ECO + "/" + eco + "/pcns"), "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\""
                + today + "\"}").with(owner)));
        return new long[] {eco, pcn};
    }

    private ResultActions mail(long pcn, String ids, Boolean attach) throws Exception {
        String body = "{\"contactIds\":[" + ids + "]" + (attach == null ? "" : ",\"attachNotice\":" + attach) + "}";
        return mvc.perform(json(post("/api/plm/pcns/" + pcn + "/mail"), body).header("X-Idempotency-Key", UUID.randomUUID().toString()).with(owner));
    }

    private String payload(long contactId) {
        return accounts.query(comp, """
                select o.payload::text from sys_outbox o join plm_pcn_mail m on m.outbox_id = o.message_id
                 where m.contact_id = ? order by m.mail_id desc limit 1""", String.class, contactId);
    }

    @Test
    void 통지서_첨부는_PCN_대상_사본_하나를_모두에게_붙이고_이력에_남기며_같은_내용이면_사본을_다시_쓴다() throws Exception {
        long[] p = pcn("첨부 시험");
        mail(p[1], cA + "," + cB, true).andExpect(status().isOk());
        // 사본 = plm-eco × PCN-{id} 한 개, 두 메일 모두 그 file_id 기록·아웃박스 첨부 1개(이름·크기·SHA-256)
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'plm-eco' and target_id = ?", Integer.class, "PCN-" + p[1])).isEqualTo(1);
        long fileId = accounts.query(comp, "select file_id from sys_file where target_type = 'plm-eco' and target_id = ?", Long.class, "PCN-" + p[1]);
        String copyName = accounts.query(comp, "select orig_name from sys_file where file_id = ?", String.class, fileId);
        assertThat(copyName).isEqualTo(accounts.query(comp, "select eco_no from plm_eco where eco_id = ?", String.class, p[0]) + "_notice.xlsx");
        assertThat(accounts.query(comp, "select string_agg(distinct coalesce(notice_file_id::text, '-'), ',') from plm_pcn_mail where pcn_id = ?", String.class, p[1]))
                .isEqualTo(String.valueOf(fileId));
        for (long c : new long[] {cA, cB}) {
            String pl = payload(c);
            List<Map<String, Object>> att = JsonPath.read(pl, "$.attachments");
            assertThat(att).hasSize(1);
            assertThat(att.get(0).get("name")).isEqualTo(copyName);
            assertThat(att.get(0).get("sha256")).isNotNull();
            // 결재 전 지시 — 본문에 초안 안내 한 줄
            assertThat((String) JsonPath.read(pl, "$.body")).contains("아직 결재 전인 초안입니다");
        }
        // 같은 내용으로 다시 보내면 사본은 그대로 하나
        mail(p[1], String.valueOf(cA), true).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'plm-eco' and target_id = ?", Integer.class, "PCN-" + p[1])).isEqualTo(1);
        // 끔 / 비움 → 글자 메일, 기록 null
        mail(p[1], String.valueOf(cB), false).andExpect(status().isOk());
        assertThat(payload(cB)).doesNotContain("attachments");
        mail(p[1], String.valueOf(cB), null).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from plm_pcn_mail where pcn_id = ? and notice_file_id is null", Integer.class, p[1])).isEqualTo(2);
        // 통지 목록 이력에 사본 이름·id
        String list = mvc.perform(get(ECO + "/" + p[0] + "/pcns").with(viewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(list, "$.data[0].mails[*].noticeFileNm").stream().filter(java.util.Objects::nonNull).toList())
                .hasSize(3).allMatch(copyName::equals);
        // 원장 — 기록은 바꿀 수 없다
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update plm_pcn_mail set notice_file_id = null where pcn_id = ?", p[1])))).isNotNull();
        assertThat(accounts.query(comp, "select count(*) from plm_pcn_mail where pcn_id = ? and notice_file_id is not null", Integer.class, p[1])).isEqualTo(3);
    }

    @Test
    void 사본은_조회_권한으로_내려받고_A사로_B사는_404_PCN_대상에는_사용자가_올리거나_지울_수_없다() throws Exception {
        long[] p = pcn("권한 시험");
        mail(p[1], String.valueOf(cA), true).andExpect(status().isOk());
        long fileId = accounts.query(comp, "select notice_file_id from plm_pcn_mail where pcn_id = ?", Long.class, p[1]);
        mvc.perform(get("/api/platform/files/" + fileId).with(viewer)).andExpect(status().isOk());
        // 보낸 사본은 DB 쪽에서도 굳어 있다(QA P-1) — 앱 규칙을 거치지 않는 지우기·바꾸기도 트리거가 막는다
        assertThat(accounts.query(comp, "select locked_yn from sys_file where file_id = ?", String.class, fileId)).isEqualTo("Y");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update sys_file set use_yn = 'N' where file_id = ?", fileId))))
                .contains("ck_sys_file_locked");
        String compB = accounts.newComp("PMAB");
        long b = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, b, "ENG", "plm-eco", "SEARCH", "SAVE");
        RequestPostProcessor bUser = accounts.as(compB, b, "engB");
        mvc.perform(get("/api/platform/files/" + fileId).with(bUser)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/plm/pcns/" + p[1] + "/mail"), "{\"contactIds\":[" + cA + "],\"attachNotice\":true}")
                .header("X-Idempotency-Key", UUID.randomUUID().toString()).with(bUser)).andExpect(status().isNotFound());
        // 사용자가 API 로 직접 PCN-{id} 에 올리기·지우기 → 잠김
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
        mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "가짜.pdf", "application/pdf", pdf))
                .param("targetType", "plm-eco").param("targetId", "PCN-" + p[1]).with(owner).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(post("/api/platform/files/" + fileId + "/delete").with(owner).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        assertThat(accounts.query(comp, "select use_yn from sys_file where file_id = ?", String.class, fileId)).isEqualTo("Y");
    }

    @Test
    void 허용_목록에서_xlsx_를_빼면_400_이고_메일_이력_사본_모두_없다() throws Exception {
        long[] p = pcn("확장자 시험");
        runner.run(comp, () -> policies.set(Policies.FILE_ALLOWED_EXT, null, "pdf", "t"));
        int outbox = accounts.query(comp, "select count(*) from sys_outbox", Integer.class);
        assertThat(mail(p[1], String.valueOf(cA), true).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(accounts.query(comp, "select count(*) from plm_pcn_mail where pcn_id = ?", Integer.class, p[1])).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_id = ?", Integer.class, "PCN-" + p[1])).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_outbox", Integer.class)).isEqualTo(outbox);
        // 첨부 없이는 그대로 보낼 수 있다
        mail(p[1], String.valueOf(cA), false).andExpect(status().isOk());
    }
}
