package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.production.LotDtos.ReceiveForm;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 자재 LOT 등록의 기록자 — 단말 요청 허용 (W-6 사용자 결정 2026-11-01, docs/25 4.51 — WMS PDA 입고의 전제).
 * 단말 요청은 TerminalAuthFilter 처럼 회사 범위 + 단말 행위자 {@code t{id}:{코드}} 를 연 채로 부른다(PDA 입고 API 는 WMS 가 이 뒤에 붙인다).
 * 이상 사용: 단말 아님(SYSTEM·사용자 행위자 흉내) + 비로그인 = 401, 다른 회사 단말로 이 회사 품목 = 품목 없음, 분할 등 다른 LOT 작업은 여전히 로그인만.
 */
@AutoConfigureMockMvc
class LotReceiveActorTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String L = "/api/production/lots";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    LotService lots;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("LR");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "prd-lot", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        item(comp);
    }

    private void item(String c) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RM', 'RM 품목', 'RM', 'BUY', 'EA', 't', 't')""", c);
    }

    private static ReceiveForm form() {
        return new ReceiveForm("RM", "5", null, "SUP-LOT-1", null, null);
    }

    /** TerminalAuthFilter 와 같은 범위(회사 + 행위자)를 열고 LotService.receive 를 부른다 */
    private long asTerminal(String c, String actor) {
        try (TenantContext.Scope ignored = TenantContext.openRequestScope(c, actor)) {
            return lots.receive(form());
        }
    }

    private String createdBy(String c, long lotId) {
        return accounts.query(c, "select created_by from lot_master where lot_id = ?", String.class, lotId);
    }

    @Test
    void 단말_요청은_단말_행위자가_기록자() {
        long id = asTerminal(comp, "t42:PDA-01");
        assertThat(createdBy(comp, id)).isEqualTo("t42:PDA-01");
        assertThat(accounts.query(comp, "select origin || '/' || qty_created::int || '/' || supplier_lot_no from lot_master where lot_id = ?",
                String.class, id)).isEqualTo("RECEIVED/5/SUP-LOT-1");
    }

    @Test
    void 사무_요청은_그대로_로그인_사용자가_기록자() throws Exception {
        MvcResult m = mvc.perform(post(L + "/receive").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemCd\":\"RM\",\"qty\":\"3\"}")).andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        assertThat(createdBy(comp, id)).isEqualTo("admin1");
    }

    @Test
    void 단말_아님_비로그인은_401() throws Exception {
        // 요청 밖 작업(SYSTEM 행위자) — 회사 범위가 있어도 거부
        BusinessException sys = catchThrowableOfType(BusinessException.class, () -> runner.call(comp, () -> lots.receive(form())));
        assertThat(sys.code()).isEqualTo(HbErrorCode.UNAUTHENTICATED);
        // 사용자 행위자 모양만 흉내(로그인 없음) — 거부
        BusinessException fake = catchThrowableOfType(BusinessException.class, () -> asTerminal(comp, "u1:admin1"));
        assertThat(fake.code()).isEqualTo(HbErrorCode.UNAUTHENTICATED);
        // 단말 모양이 아닌 글(공백·빈 코드)
        assertThat(catchThrowableOfType(BusinessException.class, () -> asTerminal(comp, "t42:")).code())
                .isEqualTo(HbErrorCode.UNAUTHENTICATED);
        assertThat(catchThrowableOfType(BusinessException.class, () -> asTerminal(comp, "tX:PDA")).code())
                .isEqualTo(HbErrorCode.UNAUTHENTICATED);
        // 비로그인 HTTP
        mvc.perform(post(L + "/receive").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"itemCd\":\"RM\",\"qty\":\"3\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(accounts.query(comp, "select count(*) from lot_master", Integer.class)).isZero();
    }

    @Test
    void 다른_회사_단말로는_이_회사_품목이_없다() {
        String other = accounts.newComp("LRB");   // 품목 없음
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> asTerminal(other, "t7:PDA-B"));
        assertThat(e.code()).isEqualTo(HbErrorCode.INVALID_VALUE);
        assertThat(e.fieldErrors()).anySatisfy(f -> assertThat(f.field() + ":" + f.code()).isEqualTo("itemCd:NO_SUCH_CODE"));
        assertThat(accounts.query(comp, "select count(*) from lot_master", Integer.class)).isZero();
        assertThat(accounts.query(other, "select count(*) from lot_master", Integer.class)).isZero();
    }

    @Test
    void 분할_등_다른_LOT_작업은_여전히_로그인만() {
        long id = asTerminal(comp, "t42:PDA-01");
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> {
            try (TenantContext.Scope ignored = TenantContext.openRequestScope(comp, "t42:PDA-01")) {
                lots.split(id, new LotDtos.SplitForm(java.util.List.of("1"), "단말 분할", null));
            }
        });
        assertThat(e.code()).isEqualTo(HbErrorCode.UNAUTHENTICATED);
    }
}
