package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PCN 메일 발송 결과 표시 (docs/39 32장 M1~M6) — 통지 목록의 발송 이력 줄에 플랫폼 MailService.statuses 결과(상태·이유 코드·문구).
 * 보내는 중·보냄(메일 서버 접수 시각)·보내지 못함(이유), 결과를 못 찾으면 비움, 아웃박스 id·받는 주소 원문은 응답에 없음.
 * A사로 B사: 다른 회사 아웃박스 id 가 섞여도 결과가 나오지 않는다(18.5 R1), B사 사용자는 A사 지시 404.
 * 정기 작업은 테스트에서 꺼져 있어(application-test.yml) 아웃박스 상태는 시험이 직접 바꾼다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class PlmPcnMailStatusTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";

    @MockitoBean
    JavaMailSender sender;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor owner;
    String today;
    long cA;
    long cB;
    long cC;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PMS");
        long ownerId = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, ownerId, "ENG", "plm-eco", "SEARCH", "SAVE");
        owner = accounts.as(comp, ownerId, "eng1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C1', 'C1 고객', 'SALES', 't', 't')", comp);
        cA = contact("가 대표", "a.main@cust.test");
        cB = contact("나 품질", "b.qa@cust.test");
        cC = contact("다 구매", "c.buy@cust.test");
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

    private UUID outboxOf(long contactId) {
        return accounts.query(comp, "select outbox_id from plm_pcn_mail where contact_id = ? order by mail_id desc limit 1", UUID.class, contactId);
    }

    private List<Map<String, Object>> mailsOf(long eco) throws Exception {
        String body = mvc.perform(get(ECO + "/" + eco + "/pcns").with(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("outboxId");
        return JsonPath.read(body, "$.data[0].mails[*]");
    }

    private static Map<String, Object> to(List<Map<String, Object>> mails, String nm) {
        return mails.stream().filter(m -> nm.equals(m.get("toNm"))).findFirst().orElseThrow();
    }

    @Test
    void 이력_줄마다_보내는_중_보냄_보내지_못함과_이유가_붙고_다른_회사_id_는_결과가_없다() throws Exception {
        long eco = data(mvc.perform(json(post(ECO), "{\"title\":\"메일 결과\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\"}").with(owner)));
        long pcn = data(mvc.perform(json(post(ECO + "/" + eco + "/pcns"), "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\""
                + today + "\"}").with(owner)));
        mvc.perform(json(post("/api/plm/pcns/" + pcn + "/mail"), "{\"contactIds\":[" + cA + "," + cB + "," + cC + "]}")
                .header("X-Idempotency-Key", UUID.randomUUID().toString()).with(owner)).andExpect(status().isOk());
        // 가 = 메일 서버 접수, 나 = 받는 주소 거절(영구 실패), 다 = 그대로(보내는 중)
        accounts.exec(comp, "update sys_outbox set status = 'SENT', attempts = 1, sent_at = now() where message_id = ?", outboxOf(cA));
        accounts.exec(comp, "update sys_outbox set status = 'FAIL', attempts = 1, last_error = 'REJECTED: recipient refused' where message_id = ?", outboxOf(cB));

        List<Map<String, Object>> mails = mailsOf(eco);
        assertThat(mails).hasSize(3);
        Map<String, Object> a = to(mails, "가 대표");
        assertThat(a.get("mailState")).isEqualTo("SENT");
        assertThat((String) a.get("stateNm")).isEqualTo("보냄(메일 서버 접수)");
        assertThat(a.get("acceptedAt")).isNotNull();
        assertThat(a.get("mailReason")).isNull();
        Map<String, Object> b = to(mails, "나 품질");
        assertThat(b.get("mailState")).isEqualTo("FAILED");
        assertThat(b.get("mailReason")).isEqualTo("REJECTED");
        assertThat((String) b.get("reasonNm")).isEqualTo("메일 서버가 받는 주소를 거절했습니다").doesNotContain("@");
        Map<String, Object> c = to(mails, "다 구매");
        assertThat(c.get("mailState")).isEqualTo("PENDING");
        assertThat(c.get("acceptedAt")).isNull();

        // 일시 오류로 상한까지 → 상한(MAX_ATTEMPTS)
        accounts.exec(comp, "update sys_outbox set status = 'FAIL', attempts = 10, last_error = 'TEMPORARY: SocketTimeoutException' where message_id = ?", outboxOf(cC));
        assertThat(to(mailsOf(eco), "다 구매").get("mailReason")).isEqualTo("MAX_ATTEMPTS");

        // A사로 B사 — B사의 외부 메일 id 를 A사 이력이 가리켜도 결과가 나오지 않는다(지금 회사만, 18.5 R1)
        String compB = accounts.newComp("PMSB");
        UUID bMsg = UUID.randomUUID();
        accounts.exec(compB, """
                insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, attempts, sent_at, created_by)
                values (?, ?, 'MAIL', 'EXT:B', '{"kind":"EXTERNAL","to":"x@b.test"}'::jsonb, 'SENT', 1, now(), 't')""", compB, bMsg);
        accounts.exec(comp, """
                insert into plm_pcn_mail (comp_cd, pcn_id, contact_id, to_addr, to_nm, subject, outbox_id, created_by)
                values (?, ?, ?, 'a.main@cust.test', '라 남의 id', '시험', ?, 't')""", comp, pcn, cA, bMsg);
        Map<String, Object> foreign = to(mailsOf(eco), "라 남의 id");
        assertThat(foreign.get("mailState")).isNull();
        assertThat(foreign.get("stateNm")).isNull();

        // B사 사용자는 A사 지시의 통지 목록 자체가 404
        long bUser = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, bUser, "ENG", "plm-eco", "SEARCH");
        mvc.perform(get(ECO + "/" + eco + "/pcns").with(accounts.as(compB, bUser, "engB"))).andExpect(status().isNotFound());
    }

    @Test
    void 발송_이력이_없는_통지는_빈_목록() throws Exception {
        long eco = data(mvc.perform(json(post(ECO), "{\"title\":\"메일 없음\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\"}").with(owner)));
        mvc.perform(json(post(ECO + "/" + eco + "/pcns"), "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\"" + today + "\"}")
                .with(owner)).andExpect(status().isOk());
        assertThat(mailsOf(eco)).isEmpty();
    }
}
