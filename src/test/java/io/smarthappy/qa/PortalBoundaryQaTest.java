package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — 협력사 포털 경계(docs/42 3장, main V167 #425·#436·#444) 이상 사용. 모듈 시험(PortalSrmTest·PortalShellTest·PortalUserTest)은
 * 인증 객체를 바로 끼우므로, 여기서는 **실제 로그인 세션**으로 요청 필터(계정·협력사 유효 확인)까지 지난다.
 * <ul>
 * <li>전수: 회사 체인의 모든 핸들러(포털·허용 목록·다른 체인 제외)를 협력사 세션으로 → API 403 PORTAL_FORBIDDEN · 화면 → /portal</li>
 * <li>전수: 회사 관리자(모든 화면 권한) 세션으로 포털 API·화면 → 403</li>
 * <li>경로 변형(대소문자·끝 슬래시·인코딩·세미콜론·..·접두어 닮은꼴)으로 내부 API 에 닿지 않음</li>
 * <li>세션 유형 유지: 강제 비밀번호 변경·포털 비밀번호 변경 뒤에도 협력사 / 회사 사용자 언어 변경 뒤에도 회사</li>
 * <li>CSRF 없는 포털 쓰기 403 · 협력사 사용 중지(mst_cust use_yn N) 다음 요청 401 · 본문 custId·custCd 위조 무시 · 남의 발주 행으로 회신 거부</li>
 * </ul>
 */
@AutoConfigureMockMvc
class PortalBoundaryQaTest extends PostgresTestBase {

    static final String PW = "Qa-portal-fence-27";
    static final String NEW_PW = "Qa-portal-fence-28!";
    /** 협력사에게 열린 포털 밖 경로(PortalBoundaryFilter.SUPPLIER_PATHS)와 다른 보안 체인(POP·운영자), 로그인 전 공개 경로 */
    static final Set<String> SKIP = Set.of("/api/platform/session", "/api/platform/messages", "/error", "/password-change",
            "/api/platform/password-change", "/mfa", "/api/platform/mfa/verify", "/api/platform/mfa/enroll/start",
            "/api/platform/mfa/enroll/confirm", "/api/platform/mfa/status", "/login");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    String comp;
    String ip;
    long custA;
    long custB;
    long poA;
    long poB;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QV");
        ip = "10.93." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor admin = accounts.as(comp, u, "buyer1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", comp);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'QV-BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp);
        custA = supplierCust("QV-A", "가람");
        custB = supplierCust("QV-B", "나래");
        poA = issued(admin, "QV-A");
        poB = issued(admin, "QV-B");
        supplierUser("supa", custA, "N");
        supplierUser("supb", custB, "N");
    }

    // ------------------------------------------------------------------ 도우미

    long supplierCust(String cd, String nm) {
        long id = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp, cd, nm);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')",
                id, comp);
        return id;
    }

    long supplierUser(String login, long cust, String pwdChangeReq) {
        return accounts.query(comp, """
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, user_type, cust_id, pwd_change_req_yn, created_by, updated_by)
                values (?, ?, ?, ?, 'SUPPLIER', ?, ?, 't', 't') returning user_id""", Long.class, comp, login, login, encoder.encode(PW), cust,
                pwdChangeReq);
    }

    long issued(RequestPostProcessor admin, String cust) throws Exception {
        String due = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 20)::text", String.class);
        MvcResult m = mvc.perform(post("/api/srm/pos").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"custCd\":\"" + cust + "\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"QV-BOLT\",\"qty\":\"10\",\"unitPrice\":\"1\","
                        + "\"dueDate\":\"" + due + "\"}]}")).andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        for (String act : new String[] {"confirm", "issue"}) {
            int rv = accounts.query(comp, "select row_ver from srm_po where po_id = ?", Integer.class, id);
            mvc.perform(post("/api/srm/pos/" + id + "/" + act).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"rowVer\":" + rv + "}")).andExpect(status().isOk());
        }
        return id;
    }

    RequestPostProcessor from() {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    /** 실제 로그인(/login) → 세션 */
    MockHttpSession login(String loginId, String pw) throws Exception {
        MvcResult r = mvc.perform(post("/login").with(csrf()).with(from()).param("compCd", comp).param("loginId", loginId).param("password", pw))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).with(from()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 발주 목록은 기간 필수 */
    String pos(String base) {
        return base + "?from=" + today(-30) + "&to=" + today(30);
    }

    long poLine(long po) {
        return accounts.query(comp, "select po_line_id from srm_po_line where po_id = ? and line_no = 1", Long.class, po);
    }

    String today(int plus) {
        return accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + " + plus + ")::text", String.class);
    }

    /** 회사 체인 핸들러 [방법, 경로(경로 변수 = 1)] — 포털·허용 목록·POP·운영자 제외 */
    List<String[]> companyHandlers() {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<RequestMethod> ms = info.getMethodsCondition().getMethods();
            for (String p : info.getPatternValues()) {
                String path = p.replaceAll("\\{[^}]+}", "1").replace("/**", "/x");
                if (path.equals("/portal") || path.startsWith("/portal/") || path.startsWith("/api/portal") || path.startsWith("/pop")
                        || path.startsWith("/api/pop") || path.startsWith("/ops") || path.startsWith("/api/ops") || path.startsWith("/ext/")
                        || SKIP.contains(path)) {
                    continue;
                }
                for (RequestMethod m : ms.isEmpty() ? Set.of(RequestMethod.GET) : ms) {
                    if (seen.add(m + " " + path)) {
                        out.add(new String[] {m.name(), path});
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 시험

    /** 협력사 실제 세션으로 회사 체인의 모든 핸들러 → API 403 PORTAL_FORBIDDEN, 화면 302 /portal. 500·200 없음 */
    @Test
    void 협력사_세션으로_회사_화면_API_전수는_403_또는_포털로() throws Exception {
        MockHttpSession s = login("supa", PW);
        List<String> bad = new ArrayList<>();
        List<String[]> all = companyHandlers();
        for (String[] h : all) {
            MvcResult r = mvc.perform(request(HttpMethod.valueOf(h[0]), h[1]).session(s).with(csrf()).with(from())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
            int st = r.getResponse().getStatus();
            String body = r.getResponse().getContentAsString();
            boolean ok = h[1].startsWith("/api/") ? st == 403 && body.contains("PORTAL_FORBIDDEN")
                    : st == 302 && "/portal".equals(r.getResponse().getRedirectedUrl());
            if (!ok) {
                bad.add(h[0] + " " + h[1] + " → " + st + " " + (body.length() > 80 ? body.substring(0, 80) : body));
            }
        }
        assertThat(all).hasSizeGreaterThan(300);
        assertThat(bad).as("협력사가 닿은 회사 핸들러").isEmpty();
        // 세션은 그대로 살아 있다(거부가 세션을 끊지 않음) — 포털은 된다
        mvc.perform(get(pos("/api/portal/srm/pos")).session(s).with(from())).andExpect(status().isOk());
    }

    /** 회사 관리자(모든 화면 권한)가 포털 API·화면 → 403 */
    @Test
    void 회사_관리자_세션으로_포털_API_화면은_403() throws Exception {
        long admin = accounts.newUser(comp, "admin9", PW);
        accounts.exec(comp, "insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, admin_yn, created_by, updated_by) "
                + "values (?, 'QVADM', '관리', 'Y', 't', 't')", comp);
        accounts.exec(comp, "insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, 'QVADM', 't')", comp, admin);
        MockHttpSession s = login("admin9", PW);
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String p : info.getPatternValues()) {
                if (!(p.equals("/portal") || p.startsWith("/portal/") || p.startsWith("/api/portal/"))) {
                    continue;
                }
                String path = p.replaceAll("\\{[^}]+}", "1");
                Set<RequestMethod> ms = info.getMethodsCondition().getMethods();
                for (RequestMethod m : ms.isEmpty() ? Set.of(RequestMethod.GET) : ms) {
                    n++;
                    MvcResult r = mvc.perform(request(HttpMethod.valueOf(m.name()), path).session(s).with(csrf()).with(from())
                            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
                    // 화면은 관리자 MFA 등록 강제(/mfa)가 경계보다 먼저일 수 있다 — 포털 화면이 열리지만 않으면 된다
                    boolean pageToMfa = !path.startsWith("/api/") && r.getResponse().getStatus() == 302
                            && "/mfa".equals(r.getResponse().getRedirectedUrl());
                    if (r.getResponse().getStatus() != 403 && !pageToMfa) {
                        bad.add(m + " " + path + " → " + r.getResponse().getStatus() + " " + r.getResponse().getRedirectedUrl());
                    }
                }
            }
        }
        assertThat(n).isGreaterThan(10);
        assertThat(bad).isEmpty();
    }

    /** 경로 변형으로 내부 API 에 닿지 않는다(200 없음) — 방화벽 400·없는 경로 404·경계 403 중 하나 */
    @Test
    void 경로_변형으로_내부_API에_닿지_않는다() throws Exception {
        MockHttpSession s = login("supa", PW);
        List<String> paths = List.of("/api/srm/pos/", "/API/srm/pos", "/api/SRM/pos", "/api/portal/../srm/pos", "/api/portal/%2e%2e/srm/pos",
                "/api/portal;x=1/srm/pos", "/api/srm;x=1/pos", "/api/portalx/srm/pos", "//api/srm/pos", "/api/%73rm/pos",
                "/portal/../api/srm/pos", "/api/platform/session/../../srm/pos", "/api/srm/pos?x=/api/portal");
        List<String> reached = new ArrayList<>();
        for (String p : paths) {
            int st;
            try {
                st = mvc.perform(get(p).session(s).with(from())).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                st = 400;   // 방화벽(RequestRejectedException)
            }
            if (st == 200 || st >= 500) {
                reached.add(p + " → " + st);
            }
        }
        assertThat(reached).isEmpty();
    }

    /** 강제 비밀번호 변경 → 포털 비밀번호 변경 뒤에도 협력사 세션: 포털 200, 내부 403 */
    @Test
    void 비밀번호_변경_뒤에도_협력사_세션_유형_유지() throws Exception {
        supplierUser("supc", custA, "Y");
        MockHttpSession s = login("supc", PW);
        mvc.perform(get(pos("/api/portal/srm/pos")).session(s).with(from())).andExpect(status().is4xxClientError());   // 변경 전엔 막힘
        mvc.perform(json(post("/api/platform/password-change"), "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + NEW_PW + "\"}")
                .session(s)).andExpect(status().isOk());
        mvc.perform(get(pos("/api/portal/srm/pos")).session(s).with(from())).andExpect(status().isOk());
        mvc.perform(get(pos("/api/srm/pos")).session(s).with(from())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PORTAL_FORBIDDEN"));
        mvc.perform(json(post("/api/portal/me/password"), "{\"currentPassword\":\"" + NEW_PW + "\",\"newPassword\":\"Zx-harbor-lamp-93!\"}")
                .session(s)).andExpect(status().isOk());
        mvc.perform(get(pos("/api/srm/pos")).session(s).with(from())).andExpect(status().isForbidden());
        mvc.perform(get(pos("/api/portal/srm/pos")).session(s).with(from())).andExpect(status().isOk());
        // 포털에는 언어 바꾸기가 없다 — 회사 사용자의 언어 API 는 협력사에게 403
        mvc.perform(json(post("/api/platform/my-info/locale"), "{\"localeCd\":\"en\"}").session(s)).andExpect(status().isForbidden());
    }

    /** 회사 사용자는 언어를 바꿔도 회사 세션 — 포털 403 */
    @Test
    void 회사_사용자_언어_변경_뒤에도_포털_403() throws Exception {
        long u = accounts.query(comp, "select user_id from sys_user where login_id = 'buyer1'", Long.class);
        accounts.grant(comp, u, "PUR", "sys-my-info", "SEARCH", "SAVE");
        MockHttpSession s = login("buyer1", PW);
        mvc.perform(json(post("/api/platform/my-info/locale"), "{\"localeCd\":\"en\"}").session(s)).andExpect(status().isOk());
        mvc.perform(get(pos("/api/portal/srm/pos")).session(s).with(from())).andExpect(status().isForbidden());
        mvc.perform(get(pos("/api/srm/pos")).session(s).with(from())).andExpect(status().isOk());
    }

    /** CSRF 없는 포털 쓰기 403(ASN 0건) / 협력사 사용 중지 → 다음 요청 401(세션 끊김) */
    @Test
    void CSRF_없음과_협력사_사용_중지() throws Exception {
        MockHttpSession s = login("supa", PW);
        String asn = "{\"plantCd\":\"P1\",\"shipDate\":\"" + today(0) + "\",\"etaDate\":\"" + today(2) + "\",\"lines\":[{\"poLineId\":"
                + poLine(poA) + ",\"qty\":\"1\"}]}";
        mvc.perform(post("/api/portal/srm/asns").with(from()).contentType(MediaType.APPLICATION_JSON).content(asn).session(s))
                .andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select count(*) from srm_asn", Integer.class)).isZero();
        accounts.exec(comp, "update mst_cust set use_yn = 'N' where cust_id = ?", custA);
        mvc.perform(get(pos("/api/portal/srm/pos")).session(s).with(from())).andExpect(status().isUnauthorized());
        mvc.perform(get(pos("/api/portal/srm/pos")).session(s).with(from())).andExpect(status().isUnauthorized());
    }

    /** 본문 위조: ASN 에 custId·custCd 를 B 로 넣어도 A 소속으로 / 내 발주 회신에 B 발주 행 → 거부, B 회신 0 */
    @Test
    void 본문_custId_위조와_남의_발주_행_회신() throws Exception {
        MockHttpSession s = login("supa", PW);
        MvcResult m = mvc.perform(json(post("/api/portal/srm/asns"), "{\"custId\":" + custB + ",\"custCd\":\"QV-B\",\"plantCd\":\"P1\","
                + "\"shipDate\":\"" + today(0) + "\",\"etaDate\":\"" + today(2) + "\",\"lines\":[{\"poLineId\":" + poLine(poA)
                + ",\"qty\":\"1\"}]}").session(s)).andExpect(status().isOk()).andReturn();
        long asn = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        assertThat(accounts.query(comp, "select cust_id from srm_asn where asn_id = ?", Long.class, asn)).isEqualTo(custA);
        MvcResult r = mvc.perform(json(post("/api/portal/srm/pos/" + poA + "/ack"), "{\"custId\":" + custB + ",\"reject\":false,\"lines\":[{"
                + "\"poLineId\":" + poLine(poB) + ",\"promisedDate\":\"" + today(10) + "\",\"promisedQty\":\"10\"}]}").session(s)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(accounts.query(comp, "select count(*) from srm_po_ack where po_id in (?, ?)", Integer.class, poA, poB)).isZero();
        // B 발주 id 로 A 세션 회신 → 404
        mvc.perform(json(post("/api/portal/srm/pos/" + poB + "/ack"), "{\"reject\":false,\"lines\":[{\"poLineId\":" + poLine(poB)
                + ",\"promisedDate\":\"" + today(10) + "\",\"promisedQty\":\"10\"}]}").session(s)).andExpect(status().isNotFound());
    }
}
