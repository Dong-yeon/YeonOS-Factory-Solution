package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PCN 메일 발송 (docs/39 23장, 사용자 결정 E1~E9) — 고객 담당자에게 플랫폼 MailService(아웃박스 MAIL)로, 매번 발송 이력.
 * 받는 사람은 통지 고객의 사용 중·메일 있는 담당자만, 열린 통지만, 멱등 키 필수, 본문에 사내 화면 주소 없음.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class PlmPcnMailTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";
    private static final String PCN = "/api/plm/pcns";

    @MockitoBean
    JavaMailSender sender;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor owner;
    RequestPostProcessor viewer;
    String today;
    long cA;
    long cB;
    long cNoMail;
    long cUnused;
    long cOther;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PCM");
        long ownerId = accounts.newUser(comp, "eng1", PW);
        long viewerId = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, ownerId, "ENG", "plm-eco", "SEARCH", "SAVE");
        accounts.grant(comp, viewerId, "VIEW", "plm-eco", "SEARCH");
        owner = accounts.as(comp, ownerId, "eng1");
        viewer = accounts.as(comp, viewerId, "view1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        for (String c : new String[] {"C1", "C2"}) {
            accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, ?, ?, 'SALES', 't', 't')",
                    comp, c, c + " 고객");
        }
        cA = contact("C1", "가 대표", "a.main@cust.test", "Y", "Y");
        cB = contact("C1", "나 품질", "b.qa@cust.test", "N", "Y");
        cNoMail = contact("C1", "다 메일없음", null, "N", "Y");
        cUnused = contact("C1", "라 퇴사", "d.left@cust.test", "N", "N");
        cOther = contact("C2", "마 다른고객", "e.other@cust2.test", "Y", "Y");
    }

    private long contact(String custCd, String nm, String email, String main, String use) {
        return accounts.query(comp, """
                insert into mst_cust_contact (comp_cd, cust_id, contact_nm, email, main_yn, use_yn, created_by, updated_by)
                select ?, cust_id, ?, ?, ?, ?, 't', 't' from mst_cust where cust_cd = ? returning contact_id""",
                Long.class, comp, nm, email, main, use, custCd);
    }

    // ---------- 도우미

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long data(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 작성 중 ECO + C1 열린 통지 → pcnId */
    private long pcn(String title) throws Exception {
        long eco = data(mvc.perform(json(post(ECO), "{\"title\":\"" + title + "\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\"}")
                .with(owner)));
        return data(mvc.perform(json(post(ECO + "/" + eco + "/pcns"), "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\""
                + today + "\",\"note\":\"도면 리비전 B\\n수입검사 기준 변경\"}").with(owner)));
    }

    private ResultActions mail(RequestPostProcessor who, long pcn, String ids, String key) throws Exception {
        MockHttpServletRequestBuilder b = json(post(PCN + "/" + pcn + "/mail"), "{\"contactIds\":[" + ids + "]}").with(who);
        return mvc.perform(key == null ? b : b.header("X-Idempotency-Key", key));
    }

    private int mails(long pcn) {
        return accounts.query(comp, "select count(*) from plm_pcn_mail where pcn_id = ?", Integer.class, pcn);
    }

    private int outbox(long pcn) {
        return accounts.query(comp, "select count(*) from sys_outbox where topic = 'MAIL' and biz_key like ?", Integer.class,
                "EXT:PCN:" + pcn + ":%");
    }

    private String pcnNo(long pcn) {
        return accounts.query(comp, "select pcn_no from plm_pcn where pcn_id = ?", String.class, pcn);
    }

    // ---------- 시험

    @Test
    void 고른_담당자에게_한_통씩_보내고_이력이_남으며_본문에_사내_주소가_없다() throws Exception {
        long p = pcn("하우징 재질 변경");
        String no = pcnNo(p);
        // 후보 = 사용 중·메일 있는 C1 담당자만, 대표 먼저 / 미리보기 제목에 통지 번호·지시 제목
        mvc.perform(get(PCN + "/" + p + "/mail-targets").with(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.contacts.length()").value(2))
                .andExpect(jsonPath("$.data.contacts[0].contactId").value(cA))
                .andExpect(jsonPath("$.data.contacts[0].main").value(true))
                .andExpect(jsonPath("$.data.contacts[1].contactId").value(cB))
                .andExpect(jsonPath("$.data.subject").value(Matchers.allOf(Matchers.containsString(no), Matchers.containsString("하우징 재질 변경"))))
                .andExpect(jsonPath("$.data.preview").value(Matchers.not(Matchers.containsString("mes.example.test"))));
        mail(owner, p, cA + "," + cB, UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(2));
        assertThat(mails(p)).isEqualTo(2);
        assertThat(outbox(p)).isEqualTo(2);
        String body = accounts.query(comp, "select payload->>'body' from sys_outbox where biz_key = ?", String.class, "EXT:PCN:" + p + ":" + cA);
        assertThat(body).contains("가 대표", no, "하우징 재질 변경", "도면 리비전 B\n수입검사 기준 변경")
                .doesNotContain("mes.example.test", "/page/");
        assertThat(accounts.query(comp, "select payload->>'to' from sys_outbox where biz_key = ?", String.class, "EXT:PCN:" + p + ":" + cB))
                .isEqualTo("b.qa@cust.test");
        // 목록에 이력(받는 사람 스냅샷·보낸 사람) + 메일 켜짐
        long eco = accounts.query(comp, "select eco_id from plm_pcn where pcn_id = ?", Long.class, p);
        mvc.perform(get(ECO + "/" + eco + "/pcns").with(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].mailEnabled").value(true))
                .andExpect(jsonPath("$.data[0].mails.length()").value(2))
                .andExpect(jsonPath("$.data[0].mails[0].toNm").value("가 대표"))
                .andExpect(jsonPath("$.data[0].mails[0].toAddr").value("a.main@cust.test"))
                .andExpect(jsonPath("$.data[0].mails[0].sentBy").value("eng1"));
        // 담당자 메일이 바뀌어도 이력은 보낸 때 그대로
        accounts.exec(comp, "update mst_cust_contact set email = 'a.new@cust.test' where contact_id = ?", cA);
        mvc.perform(get(ECO + "/" + eco + "/pcns").with(owner)).andExpect(jsonPath("$.data[0].mails[0].toAddr").value("a.main@cust.test"));
        // 다시 보내기(새 요청) 허용 — 이력 한 줄 더
        mail(owner, p, String.valueOf(cA), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(mails(p)).isEqualTo(3);
    }

    @Test
    void 같은_요청_키는_한_번만_키_없으면_400() throws Exception {
        long p = pcn("키 시험");
        String key = UUID.randomUUID().toString();
        mail(owner, p, String.valueOf(cA), key).andExpect(status().isOk());
        mail(owner, p, String.valueOf(cA), key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        mail(owner, p, String.valueOf(cA), null).andExpect(status().isBadRequest());
        assertThat(mails(p)).isEqualTo(1);
        assertThat(outbox(p)).isEqualTo(1);
    }

    @Test
    void E_받는_사람은_이_통지_고객의_사용_중_메일_있는_담당자만_한_번에_10명까지() throws Exception {
        long p = pcn("받는 사람 시험");
        for (String ids : new String[] {String.valueOf(cNoMail), String.valueOf(cUnused), String.valueOf(cOther), cA + "," + cOther,
                "999999999"}) {
            mail(owner, p, ids, UUID.randomUUID().toString()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("contactIds"))
                    .andExpect(jsonPath("$.errors[0].code").value("NOT_PCN_CONTACT"));
        }
        mail(owner, p, "", UUID.randomUUID().toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mail(owner, p, cA + "," + cA, UUID.randomUUID().toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mail(owner, p, "1,2,3,4,5,6,7,8,9,10,11", UUID.randomUUID().toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_MANY_ITEMS"));
        // 통째로 거부 — 한 명도 나가지 않는다
        assertThat(mails(p)).isZero();
        assertThat(outbox(p)).isZero();
    }

    @Test
    void 응답_끝난_통지는_409_조회만_가진_사람은_403() throws Exception {
        long p = pcn("상태 시험");
        mvc.perform(get(PCN + "/" + p + "/mail-targets").with(viewer)).andExpect(status().isForbidden());
        mail(viewer, p, String.valueOf(cA), UUID.randomUUID().toString()).andExpect(status().isForbidden());
        mvc.perform(json(post(PCN + "/" + p + "/response"), "{\"rowVer\":0,\"status\":\"ACK\",\"respondedOn\":\"" + today + "\"}").with(owner))
                .andExpect(status().isOk());
        mail(owner, p, String.valueOf(cA), UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLM_PCN_STATE"));
        assertThat(mails(p)).isZero();
    }

    @Test
    void 제목의_제어_양방향_문자는_걸러진다() throws Exception {
        long p = pcn("정상 제목");
        accounts.exec(comp, "update plm_eco set title = ? where eco_id = (select eco_id from plm_pcn where pcn_id = ?)",
                "브래킷‮변경\r\nBcc: x@evil.test", p);
        mail(owner, p, String.valueOf(cA), UUID.randomUUID().toString()).andExpect(status().isOk());
        String subject = accounts.query(comp, "select subject from plm_pcn_mail where pcn_id = ?", String.class, p);
        assertThat(subject).doesNotContain("‮", "\r", "\n").contains("브래킷");
    }

    @Test
    void 발송_이력은_고치거나_지울_수_없다() throws Exception {
        long p = pcn("이력 시험");
        mail(owner, p, String.valueOf(cA), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThatThrownBy(() -> accounts.exec(comp, "update plm_pcn_mail set to_addr = 'x@y.test' where pcn_id = ?", p))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> accounts.exec(comp, "delete from plm_pcn_mail where pcn_id = ?", p)).isInstanceOf(Exception.class);
        // 다른 고객의 담당자로는 직접 SQL 로도 이력을 만들 수 없다(트리거)
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into plm_pcn_mail (comp_cd, pcn_id, contact_id, to_addr, to_nm, subject, outbox_id, created_by)
                values (?, ?, ?, 'e.other@cust2.test', '마', 's', gen_random_uuid(), 't')""", comp, p, cOther))
                .isInstanceOf(Exception.class);
        assertThat(mails(p)).isEqualTo(1);
    }

    @Test
    void B_A사로_B사_통지에_메일을_보낼_수_없다() throws Exception {
        long p = pcn("격리 시험");
        String compB = accounts.newComp("PCMB");
        long userB = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, userB, "ENG", "plm-eco", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, userB, "engB");
        mvc.perform(get(PCN + "/" + p + "/mail-targets").with(b)).andExpect(status().isNotFound());
        mail(b, p, String.valueOf(cA), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        assertThat(mails(p)).isZero();
        assertThat(accounts.query(compB, "select count(*) from plm_pcn_mail", Integer.class)).isZero();
        assertThat(List.of(outbox(p))).containsExactly(0);
    }
}
