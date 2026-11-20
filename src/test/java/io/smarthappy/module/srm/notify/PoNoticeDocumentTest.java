package io.smarthappy.module.srm.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
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

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * SRM S4 발주서·통지 (docs/31 D7·D8) — 발행·취소 통지(아웃박스 → 가짜 메일함, 멱등), 주소 없음 → 실패 → 다시 보내기,
 * 발주서 자료(발행본 스냅샷 / 초안), 엑셀 수식 주입 방지, 권한(PRINT·통지 SAVE), A사로 B사.
 */
@AutoConfigureMockMvc
class PoNoticeDocumentTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String API = "/api/srm/pos";
    private static final String N = "/api/srm/notices";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    OutboxRelay relay;

    @Autowired
    SrmMailbox mailbox;

    String comp;
    long userId;
    RequestPostProcessor me;
    LocalDate today;
    long vMail;
    long vNoMail;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("NT");
        userId = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, userId, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE", "PRINT");
        accounts.grant(comp, userId, "PUR", "srm-notify", "SEARCH", "SAVE");
        me = accounts.as(comp, userId, "buyer1");
        accounts.addPlant(comp, "P1", "1공장");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트 M8', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp);
        vMail = supplier("V100", "sales@v100.example");
        vNoMail = supplier("V200", null);
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", comp);
    }

    private long supplier(String cd, String email) {
        long id = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, email, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', ?, 't', 't') returning cust_id""", Long.class, comp, cd, cd + " 상사", email);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')", id, comp);
        return id;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int rowVer(long po) throws Exception {
        return JsonPath.read(mvc.perform(get(API + "/" + po).with(me)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
    }

    private ResultActions act(long po, String action, String reason) throws Exception {
        return postAs(me, API + "/" + po + "/" + action, "{\"rowVer\":" + rowVer(po) + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private long issuedPo(String cust, String remark) throws Exception {
        long po = id(postAs(me, API, "{\"custCd\":\"" + cust + "\",\"plantCd\":\"P1\",\"remark\":\"" + remark + "\",\"lines\":[{\"itemCd\":\"BOLT-M8\","
                + "\"qty\":\"10\",\"unitPrice\":\"100\",\"dueDate\":\"" + today.plusDays(20) + "\"}]}"));
        act(po, "confirm", null).andExpect(status().isOk());
        act(po, "issue", null).andExpect(status().isOk());
        return po;
    }

    private String notices() throws Exception {
        return mvc.perform(get(N).param("from", today.toString()).param("to", today.toString()).with(me)).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    void 발행하면_통지가_아웃박스로_가짜메일함에_한통_주소는_가려서_취소도_알린다() throws Exception {
        accounts.exec(comp, "insert into mst_cust_contact (comp_cd, cust_id, contact_nm, email, main_yn, created_by, updated_by) values (?, ?, '김영업', 'kim@v100.example', 'Y', 't', 't')",
                comp, vMail);
        long po = issuedPo("V100", "첫 발주");
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'SRM_PO_ISSUED' and biz_key = ?", Integer.class,
                "PO:" + po + ":0")).isEqualTo(1);
        assertThat(accounts.query(comp, "select payload ->> 'to' from sys_outbox where topic = 'SRM_PO_ISSUED'", String.class))
                .isEqualTo("kim@v100.example");     // 주 담당자 메일이 거래처 메일보다 먼저
        act(po, "issue", null).andExpect(jsonPath("$.code").value("SRM_STATE"));     // 두 번 발행 → 통지도 1건
        relay.relay(comp);
        String n = notices();
        assertThat((Integer) JsonPath.read(n, "$.data.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(n, "$.data[0].status")).isEqualTo("SENT");
        assertThat((String) JsonPath.read(n, "$.data[0].toAddr")).isEqualTo("k***@v100.example");
        mvc.perform(get(N + "/mailbox").with(me)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].to").value("k***@v100.example"))
                .andExpect(jsonPath("$.data[0].subject").value(org.hamcrest.Matchers.containsString("발주")));
        // 개정 → 다시 발행 = 개정 1 통지, 발행된 발주 취소 = 취소 통지(사유 포함)
        act(po, "revise", null).andExpect(status().isOk());
        act(po, "confirm", null).andExpect(status().isOk());
        act(po, "issue", null).andExpect(status().isOk());
        act(po, "cancel", "단종").andExpect(status().isOk());
        relay.relay(comp);
        assertThat(accounts.query(comp, "select string_agg(topic || '@' || biz_key, ',' order by outbox_id) from sys_outbox", String.class))
                .isEqualTo("SRM_PO_ISSUED@PO:" + po + ":0,SRM_PO_ISSUED@PO:" + po + ":1,SRM_PO_CANCELLED@PO:" + po + ":1");
        assertThat(mailbox.list(comp)).anySatisfy(m -> assertThat(m.body()).contains("단종"));      // 릴레이 순서는 보장하지 않는다
        // 발행 전 취소는 협력사가 모르는 발주라 통지 없음
        long draft = id(postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\"}"));
        act(draft, "cancel", "잘못 만듦").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from sys_outbox", Integer.class)).isEqualTo(3);
        // 수신측 멱등: 같은 메시지가 두 번 와도 한 통
        UUID id = UUID.randomUUID();
        assertThat(mailbox.deliver(comp, new SrmMailbox.Mail(id, "X", "a@b", "s", "b", java.time.Instant.now()))).isTrue();
        assertThat(mailbox.deliver(comp, new SrmMailbox.Mail(id, "X", "a@b", "s", "b", java.time.Instant.now()))).isFalse();
    }

    @Test
    void 받는주소가_없으면_실패로_남고_주소를_고친뒤_다시보내기_보낸건은_다시보내기_불가() throws Exception {
        long po = issuedPo("V200", "x");
        relay.relay(comp);
        String n = notices();
        assertThat((String) JsonPath.read(n, "$.data[0].status")).isEqualTo("FAIL");
        assertThat((String) JsonPath.read(n, "$.data[0].lastError")).isEqualTo("NO_RECIPIENT");
        long failed = ((Number) JsonPath.read(n, "$.data[0].outboxId")).longValue();
        accounts.exec(comp, "update mst_cust set email = 'buy@v200.example' where cust_id = ?", vNoMail);
        postAs(me, N + "/" + failed + "/resend", "{}").andExpect(status().isOk());
        relay.relay(comp);
        n = notices();
        assertThat((Integer) JsonPath.read(n, "$.data.length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(n, "$.data[0].status")).isEqualTo("SENT");
        long sent = ((Number) JsonPath.read(n, "$.data[0].outboxId")).longValue();
        postAs(me, N + "/" + sent + "/resend", "{}").andExpect(jsonPath("$.code").value("SRM_STATE"));
        // 발주가 이미 취소됐으면 발행 통지 다시 보내기 불가
        act(po, "cancel", "x").andExpect(status().isOk());
        postAs(me, N + "/" + failed + "/resend", "{}").andExpect(jsonPath("$.code").value("SRM_STATE"));
        mvc.perform(get(N).param("from", today.toString()).param("to", today.toString()).param("status", "fail").with(me))
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(N).with(me)).andExpect(status().isBadRequest());
    }

    @Test
    void 발주서는_발행본_스냅샷_발행전은_초안_엑셀은_수식주입_방지() throws Exception {
        long draft = id(postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"remark\":\"=HYPERLINK(\\\"http://x\\\")\",\"lines\":["
                + "{\"itemCd\":\"BOLT-M8\",\"qty\":\"3\",\"unitPrice\":\"0.5\",\"dueDate\":\"" + today.plusDays(9) + "\"}]}"));
        mvc.perform(get(API + "/" + draft + "/document").with(me)).andExpect(jsonPath("$.data.draft").value(true))
                .andExpect(jsonPath("$.data.snapshot.lines[0].amt").value("2"));
        MvcResult x = mvc.perform(get(API + "/" + draft + "/excel").with(me)).andExpect(status().isOk()).andReturn();
        assertThat(x.getResponse().getHeader("Content-Disposition")).contains("_draft.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x.getResponse().getContentAsByteArray()))) {
            Sheet sh = wb.getSheetAt(0);
            String remark = null;
            for (Row r : sh) {
                Cell c0 = r.getCell(0);
                if (c0 != null && "비고".equals(c0.getStringCellValue())) {
                    remark = r.getCell(1).getStringCellValue();
                }
            }
            assertThat(remark).startsWith("'=HYPERLINK");
        }
        // 발행본: 발행 뒤 개정해 고쳐도 문서는 마지막 발행본(개정 0), rev 지정은 그 판, 없는 판은 404
        long po = issuedPo("V100", "원본");
        act(po, "revise", null).andExpect(status().isOk());
        mvc.perform(get(API + "/" + po + "/document").with(me)).andExpect(jsonPath("$.data.draft").value(true))
                .andExpect(jsonPath("$.data.rev").value(1));
        mvc.perform(get(API + "/" + po + "/document").param("rev", "0").with(me)).andExpect(jsonPath("$.data.draft").value(false))
                .andExpect(jsonPath("$.data.snapshot.po.remark").value("원본"));
        mvc.perform(get(API + "/" + po + "/document").param("rev", "7").with(me)).andExpect(status().isNotFound());
        // 권한: PRINT 없으면 403, 통지 조회만이면 다시 보내기 403
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "srm-po", "SEARCH");
        accounts.grant(comp, reader, "READER", "srm-notify", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(get(API + "/" + po + "/excel").with(r)).andExpect(status().isForbidden());
        mvc.perform(get(API + "/" + po + "/document").with(r)).andExpect(status().isForbidden());
        postAs(r, N + "/1/resend", "{}").andExpect(status().isForbidden());
    }

    @Test
    void B사로_A사_발주서_엑셀_통지_다시보내기_메일함_모두_막힘() throws Exception {
        long po = issuedPo("V100", "x");
        relay.relay(comp);
        long outboxId = accounts.query(comp, "select outbox_id from sys_outbox", Long.class);
        String other = accounts.newComp("NB");
        long bu = accounts.newUser(other, "buyer1", PW);
        accounts.grant(other, bu, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE", "PRINT");
        accounts.grant(other, bu, "PUR", "srm-notify", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "buyer1");
        mvc.perform(get(API + "/" + po + "/document").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(API + "/" + po + "/excel").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(N).param("from", today.toString()).param("to", today.toString()).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(json(post(N + "/" + outboxId + "/resend"), "{}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(N + "/mailbox").with(b)).andExpect(jsonPath("$.data.length()").value(0));
    }
}
