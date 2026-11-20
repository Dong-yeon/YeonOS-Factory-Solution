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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PPAP 제출 메일 (docs/39 37장 P1~P9, 상위 필수 시험) — 제출된 패키지를 그 고객 담당자에게, 요약서 사본(PPAPMAIL-{id}, DB 잠금) 첨부, 아웃박스 경유.
 * 다른 거래처·사용 중지 담당자 거부, 같은 멱등 키 한 번, SUBMITTED 아니면 거부, A사로 B사, 하나라도 실패하면 이력·아웃박스·사본이 함께 롤백.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class PlmPpapMailTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

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
    long cOther;
    long cUnused;
    long cBad;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PPM");
        long ownerId = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, ownerId, "ENG", "plm-eco", "SEARCH", "SAVE");
        owner = accounts.as(comp, ownerId, "eng1");
        long viewId = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewId, "VIEW", "plm-eco", "SEARCH");
        viewer = accounts.as(comp, viewId, "view1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        for (String c : new String[] {"C1", "C2"}) {
            accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, ?, ?, 'SALES', 't', 't')",
                    comp, c, c + " 고객");
        }
        cA = contact("C1", "가 대표", "a.main@cust.test", "Y", "Y");
        cB = contact("C1", "나 품질", "b.qa@cust.test", "N", "Y");
        cOther = contact("C2", "다 다른고객", "c.other@cust2.test", "Y", "Y");
        cUnused = contact("C1", "라 퇴사", "d.left@cust.test", "N", "N");
        cBad = contact("C1", "마 주소오류", "bad-address", "N", "Y");   // 담당자 표는 형식 검사 없음 — 플랫폼이 보낼 때 거부
    }

    private long contact(String custCd, String nm, String email, String main, String use) {
        return accounts.query(comp, """
                insert into mst_cust_contact (comp_cd, cust_id, contact_nm, email, main_yn, use_yn, created_by, updated_by)
                select ?, cust_id, ?, ?, ?, ?, 't', 't' from mst_cust where cust_cd = ? returning contact_id""",
                Long.class, comp, nm, email, main, use, custCd);
    }

    /** 지시 + C1 패키지(레벨 3, 요소 18 — 1·18 은 제출 S, 나머지 보관 R, 메모·비고에 사내 글) → status 까지. {ecoId, ppapId} */
    private long[] ppap(String ecoNo, String status) {
        long eco = accounts.query(comp, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, ?, '브래킷 두께 변경', 'QUALITY', cast(? as date), (select user_id from sys_user where login_id = 'eng1' and comp_cd = ?), 't', 't')
                returning eco_id""", Long.class, comp, ecoNo, today, comp);
        long p = accounts.query(comp, """
                insert into plm_ppap (comp_cd, eco_id, cust_id, submit_level, remark, created_by, updated_by)
                values (?, ?, (select cust_id from mst_cust where cust_cd = 'C1'), 3, '사내 비고', 't', 't') returning ppap_id""", Long.class, comp, eco);
        accounts.exec(comp, """
                insert into plm_ppap_elem (comp_cd, ppap_id, elem_no, req_cd, status, note, created_by, updated_by)
                select ?, ?, n, case when n in (1, 18) then 'S' else 'R' end, case when n in (1, 18) then 'DONE' else 'OPEN' end, '사내 메모', 't', 't'
                  from generate_series(1, 18) n""", comp, p);
        if (!"DRAFT".equals(status)) {
            accounts.exec(comp, "update plm_ppap set status = 'SUBMITTED', submitted_on = cast(? as date) where ppap_id = ?", today, p);
        }
        if ("REJECTED".equals(status)) {
            accounts.exec(comp, "update plm_ppap set status = 'REJECTED', decided_on = cast(? as date) where ppap_id = ?", today, p);
        }
        return new long[] {eco, p};
    }

    private ResultActions mail(RequestPostProcessor who, long ppap, String ids, String key) throws Exception {
        return mvc.perform(post("/api/plm/ppaps/" + ppap + "/mail").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactIds\":[" + ids + "]}").header("X-Idempotency-Key", key).with(who));
    }

    private ResultActions mail(long ppap, String ids) throws Exception {
        return mail(owner, ppap, ids, UUID.randomUUID().toString());
    }

    private int count(String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }

    @Test
    void 제출된_패키지는_고객_담당자에게_요약서_사본_하나를_붙여_아웃박스로_보내고_이력을_남긴다() throws Exception {
        long[] p = ppap("ECO-M1", "SUBMITTED");
        mail(p[1], cA + "," + cB).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(2));
        // 사본 = PPAPMAIL-{id} 한 개, DB 잠금, 두 메일 모두 같은 사본
        assertThat(count("select count(*) from sys_file where target_type = 'plm-eco' and target_id = ?", "PPAPMAIL-" + p[1])).isEqualTo(1);
        long copy = accounts.query(comp, "select file_id from sys_file where target_id = ?", Long.class, "PPAPMAIL-" + p[1]);
        assertThat(accounts.query(comp, "select orig_name || '|' || locked_yn from sys_file where file_id = ?", String.class, copy))
                .isEqualTo("ECO-M1_ppap.xlsx|Y");
        assertThat(accounts.query(comp, "select string_agg(distinct summary_file_id::text, ',') from plm_ppap_mail where ppap_id = ?", String.class, p[1]))
                .isEqualTo(String.valueOf(copy));
        // 아웃박스 MAIL — 받는 주소·첨부 1개, 본문에 제출 요소, 사내 메모·비고 없음
        String pl = accounts.query(comp, """
                select o.payload::text from sys_outbox o join plm_ppap_mail m on m.outbox_id = o.message_id
                 where m.contact_id = ?""", String.class, cA);
        assertThat((String) JsonPath.read(pl, "$.to")).isEqualTo("a.main@cust.test");
        assertThat(JsonPath.<List<Map<String, Object>>>read(pl, "$.attachments")).hasSize(1);
        String body = JsonPath.read(pl, "$.body");
        assertThat(body).contains("제출 요소").contains(" - 1. ").contains(" - 18. ").doesNotContain("사내").doesNotContain("https://");
        // 상세 이력(+ 결과 배지 칸), 아웃박스 id 는 응답에 없음
        String d = mvc.perform(get("/api/plm/ecos/" + p[0] + "/ppap").with(viewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(d, "$.data.mails[*].summaryFileNm")).containsExactly("ECO-M1_ppap.xlsx", "ECO-M1_ppap.xlsx");
        assertThat(JsonPath.<List<String>>read(d, "$.data.mails[*].mailState")).containsOnly("PENDING");
        assertThat(d).doesNotContain("outboxId");
        // 다시 보냄 — 사본 그대로 하나
        mail(p[1], String.valueOf(cA)).andExpect(status().isOk());
        assertThat(count("select count(*) from sys_file where target_id = ?", "PPAPMAIL-" + p[1])).isEqualTo(1);
        assertThat(count("select count(*) from plm_ppap_mail where ppap_id = ?", p[1])).isEqualTo(3);
        // 원장 — 이력은 바꿀 수 없다
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update plm_ppap_mail set to_addr = 'x@y.test' where ppap_id = ?", p[1]))))
                .isNotNull();
    }

    @Test
    void 다른_거래처_담당자_사용_중지_담당자_목록_밖은_거부하고_아무것도_남지_않는다() throws Exception {
        long[] p = ppap("ECO-M2", "SUBMITTED");
        int outbox = count("select count(*) from sys_outbox");
        mail(p[1], String.valueOf(cOther)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_PPAP_CONTACT"));
        mail(p[1], String.valueOf(cUnused)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_PPAP_CONTACT"));
        mail(p[1], cA + "," + cA).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mail(p[1], "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        assertThat(count("select count(*) from plm_ppap_mail")).isZero();
        assertThat(count("select count(*) from sys_outbox")).isEqualTo(outbox);
        // 받는 사람 후보에도 없다(다른 고객·사용 중지), 메일 있는 사용 중 담당자만·대표 먼저
        String t = mvc.perform(get("/api/plm/ppaps/" + p[1] + "/mail-targets").with(owner)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(t, "$.data.contacts[*].contactId")).first().isEqualTo((int) cA);
        assertThat(JsonPath.<List<Integer>>read(t, "$.data.contacts[*].contactId")).doesNotContain((int) cOther, (int) cUnused);
    }

    @Test
    void 같은_멱등_키는_한_번만_보낸다() throws Exception {
        long[] p = ppap("ECO-M3", "SUBMITTED");
        String key = UUID.randomUUID().toString();
        mail(owner, p[1], String.valueOf(cA), key).andExpect(status().isOk());
        mail(owner, p[1], String.valueOf(cA), key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(count("select count(*) from plm_ppap_mail where ppap_id = ?", p[1])).isEqualTo(1);
        // 키 없음 — 400
        mvc.perform(post("/api/plm/ppaps/" + p[1] + "/mail").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactIds\":[" + cA + "]}").with(owner)).andExpect(status().isBadRequest());
    }

    @Test
    void SUBMITTED_가_아니면_거부_작성_중_판정_뒤_취소된_지시() throws Exception {
        mail(ppap("ECO-D", "DRAFT")[1], String.valueOf(cA)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_PPAP_STATE"));
        mail(ppap("ECO-R", "REJECTED")[1], String.valueOf(cA)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_PPAP_STATE"));
        long[] c = ppap("ECO-C", "SUBMITTED");
        accounts.exec(comp, "update plm_eco set status = 'CANCELLED', cancel_reason = '시험' where eco_id = ?", c[0]);
        mail(c[1], String.valueOf(cA)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_PPAP_STATE"));
        assertThat(count("select count(*) from plm_ppap_mail")).isZero();
    }

    @Test
    void 하나라도_실패하면_발송_이력_아웃박스_사본이_함께_롤백된다() throws Exception {
        long[] p = ppap("ECO-RB", "SUBMITTED");
        int outbox = count("select count(*) from sys_outbox");
        // 가(정상)는 먼저 발행되고, 마(주소 형식 오류)에서 플랫폼이 거부 → 트랜잭션 전체 롤백
        mail(p[1], cA + "," + cBad).andExpect(status().isBadRequest());
        assertThat(count("select count(*) from plm_ppap_mail where ppap_id = ?", p[1])).isZero();
        assertThat(count("select count(*) from sys_outbox")).isEqualTo(outbox);
        assertThat(count("select count(*) from sys_file where target_id = ?", "PPAPMAIL-" + p[1])).isZero();
        // 같은 요청을 고쳐 보내면 정상
        mail(p[1], String.valueOf(cA)).andExpect(status().isOk());
        assertThat(count("select count(*) from sys_outbox")).isEqualTo(outbox + 1);
    }

    @Test
    void A사로_B사_조회_권한만_사본_대상_잠김() throws Exception {
        long[] p = ppap("ECO-AB", "SUBMITTED");
        mail(p[1], String.valueOf(cA)).andExpect(status().isOk());
        long copy = accounts.query(comp, "select file_id from sys_file where target_id = ?", Long.class, "PPAPMAIL-" + p[1]);
        // 조회 권한만 — 후보·보내기 403, 사본 내려받기는 됨
        mvc.perform(get("/api/plm/ppaps/" + p[1] + "/mail-targets").with(viewer)).andExpect(status().isForbidden());
        mail(viewer, p[1], String.valueOf(cA), UUID.randomUUID().toString()).andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/files/" + copy).with(viewer)).andExpect(status().isOk());
        // B사 사용자 — 후보·보내기·사본 모두 404
        String compB = accounts.newComp("PPMB");
        long b = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, b, "ENG", "plm-eco", "SEARCH", "SAVE");
        RequestPostProcessor bUser = accounts.as(compB, b, "engB");
        mvc.perform(get("/api/plm/ppaps/" + p[1] + "/mail-targets").with(bUser)).andExpect(status().isNotFound());
        mail(bUser, p[1], String.valueOf(cA), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        mvc.perform(get("/api/platform/files/" + copy).with(bUser)).andExpect(status().isNotFound());
        assertThat(accounts.query(compB, "select count(*) from plm_ppap_mail", Integer.class)).isZero();
        // 사용자가 API 로 PPAPMAIL-{id} 에 올리기·사본 지우기 → 잠김
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
        mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "가짜.pdf", "application/pdf", pdf))
                .param("targetType", "plm-eco").param("targetId", "PPAPMAIL-" + p[1]).with(owner).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(post("/api/platform/files/" + copy + "/delete").with(owner).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
    }
}
