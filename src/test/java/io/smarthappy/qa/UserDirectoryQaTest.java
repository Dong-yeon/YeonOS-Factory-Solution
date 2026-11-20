package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — 공통 사용자 고르기 UserDirectory(#472, docs/42 15장) 이상 사용: 협력사·잠금·사용 중지·다른 회사 사용자가 모듈 API 의 담당자로
 * 끼어드는 길. 아이디로 받는 곳(SRM 구매 담당·CMS 위험성평가 구성원)과 id 로 받는 곳(QMS CAPA 담당)을 직접 API 로 보낸다.
 * 다른 모듈(PMS·CMS PM·설비 담당·보전 지시·PLM·APQP)은 "바꾸지 않은 사람 외에는 사용 중만" 규칙을 [코드]로 확인 — docs/qa 표.
 */
@AutoConfigureMockMvc
class UserDirectoryQaTest extends PostgresTestBase {

    static final String PW = "Qa-user-pick-48";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    String comp;
    RequestPostProcessor me;
    long supplierUser;
    long otherCompUser;
    long purchaseCust;
    long ra;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QU");
        long u = accounts.newUser(comp, "qa1", PW);
        accounts.grant(comp, u, "QA", "srm-supplier", "SEARCH", "SAVE");
        accounts.grant(comp, u, "QA", "cms-risk", "SEARCH", "SAVE");
        accounts.grant(comp, u, "QA", "qms-capa", "SEARCH", "SAVE");
        me = accounts.as(comp, u, "qa1");
        accounts.addPlant(comp, "P1", "1공장");
        long locked = accounts.newUser(comp, "locked1", PW);
        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = null where user_id = ?", locked);
        long off = accounts.newUser(comp, "off1", PW);
        accounts.setUserUse(comp, off, "N");
        purchaseCust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'QU-V', 'QU 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        supplierUser = accounts.query(comp, """
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, user_type, cust_id, created_by, updated_by)
                values (?, 'sup1', 'sup1', ?, 'SUPPLIER', ?, 't', 't') returning user_id""", Long.class, comp, encoder.encode(PW), purchaseCust);
        String b = accounts.newComp("QW");
        otherCompUser = accounts.newUser(b, "bq1", PW);
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        MvcResult m = post("/api/cms/risks", "{\"raType\":\"REGULAR\",\"method\":\"FREQ_SEV\",\"title\":\"QA 평가\",\"plantCd\":\"P1\","
                + "\"assessDate\":\"" + today + "\"}");
        assertThat(m.getResponse().getStatus()).as(m.getResponse().getContentAsString()).isEqualTo(200);
        ra = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    MvcResult post(String url, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(csrf()).with(me)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    /** 응답 "상태 첫 칸 오류 코드" */
    static String code(MvcResult r) throws Exception {
        String b = r.getResponse().getContentAsString();
        int st = r.getResponse().getStatus();
        if (st != 400) {
            return String.valueOf(st);
        }
        try {
            return st + " " + JsonPath.read(b, "$.errors[0].field") + ":" + JsonPath.read(b, "$.errors[0].code");
        } catch (RuntimeException e) {
            return st + " " + b;
        }
    }

    String supplierBody(String buyer) {
        return "{\"custId\":" + purchaseCust + ",\"buyerLoginId\":\"" + buyer + "\"}";
    }

    String memberBody(String login) {
        return "{\"loginId\":\"" + login + "\",\"roleCd\":\"WORKER\"}";
    }

    // ------------------------------------------------------------------ 시험

    /** 협력사 계정·다른 회사 계정은 아이디로도 id 로도 담당자가 될 수 없다 */
    @Test
    void 협력사와_다른_회사_사용자는_담당자로_못_들어온다() throws Exception {
        assertThat(code(post("/api/srm/suppliers", supplierBody("sup1")))).isEqualTo("400 buyerLoginId:NO_SUCH_CODE");
        assertThat(code(post("/api/srm/suppliers", supplierBody("bq1")))).isEqualTo("400 buyerLoginId:NO_SUCH_CODE");
        assertThat(code(post("/api/cms/risks/" + ra + "/members", memberBody("sup1")))).isEqualTo("400 loginId:NO_SUCH_CODE");
        assertThat(code(post("/api/cms/risks/" + ra + "/members", memberBody("bq1")))).isEqualTo("400 loginId:NO_SUCH_CODE");
        for (long id : new long[] {supplierUser, otherCompUser}) {
            MvcResult r = post("/api/qms/capas", "{\"source\":\"INTERNAL\",\"title\":\"QA\",\"ownerUserId\":" + id + "}");
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
        }
        assertThat(accounts.query(comp, "select count(*) from srm_supplier", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from cms_risk_member", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from qms_capa", Integer.class)).isZero();
    }

    /** 잠금·사용 중지 계정 — CAPA 담당(id)은 거부(대조) */
    @Test
    void CAPA_담당은_잠금_사용중지_계정을_거부() throws Exception {
        for (String login : new String[] {"locked1", "off1"}) {
            long id = accounts.query(comp, "select user_id from sys_user where login_id = ?", Long.class, login);
            MvcResult r = post("/api/qms/capas", "{\"source\":\"INTERNAL\",\"title\":\"QA\",\"ownerUserId\":" + id + "}");
            assertThat(r.getResponse().getStatus()).as(login + " " + r.getResponse().getContentAsString()).isEqualTo(400);
        }
    }

    /**
     * U-1: SRM 협력사 구매 담당(buyerLoginId)은 {@code byLogin} 만 보고 사용 중인지 보지 않는다 — 관리자 잠금·사용 중지 계정이 새 담당으로 들어간다.
     * 다른 모듈(PMS·CMS PM 담당·설비 담당·보전 지시·위험성평가 담당·PLM·APQP·CAPA)은 "바꾸지 않은 사람 외에는 사용 중만" 이다.
     */
    @Test
    void 결함U1_구매_담당은_사용_중_계정만() throws Exception {
        assertThat(code(post("/api/srm/suppliers", supplierBody("locked1")))).isEqualTo("400 buyerLoginId:INACTIVE");
        assertThat(code(post("/api/srm/suppliers", supplierBody("off1")))).isEqualTo("400 buyerLoginId:INACTIVE");
    }

    /** U-2: CMS 위험성평가 구성원(loginId)도 사용 중인지 보지 않는다 — 같은 화면의 평가 담당(ownerLoginId)은 INACTIVE 로 거부 */
    @Test
    void 결함U2_위험성평가_구성원은_사용_중_계정만() throws Exception {
        assertThat(code(post("/api/cms/risks/" + ra + "/members", memberBody("locked1")))).isEqualTo("400 loginId:INACTIVE");
        assertThat(code(post("/api/cms/risks/" + ra + "/members", memberBody("off1")))).isEqualTo("400 loginId:INACTIVE");
    }
}
