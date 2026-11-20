package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.portal.PlatformPortalMenu;
import io.smarthappy.platform.portal.PortalMenuItem;
import io.smarthappy.platform.portal.PortalMenuSource;
import io.smarthappy.platform.portal.PortalMenus;
import io.smarthappy.platform.security.LoginUser;

/**
 * 협력사 포털 셸 (docs/42 7장 2): 홈·내 정보 화면, 고정 메뉴, 자기 비밀번호 바꾸기.
 * 이상 사용: E(회사 사용자·CSRF 없음), A사로 B사(머리글 소속), 비밀번호 틀림이 실패 횟수로 남는지(트랜잭션 함정)
 */
class PortalShellTest extends SysAdminTestSupport {

    private static final String PWD_API = "/api/portal/me/password";
    private static final AtomicInteger IP = new AtomicInteger();

    @Autowired
    PasswordEncoder encoder;

    long cust;
    long supId;
    RequestPostProcessor sup;

    @BeforeEach
    void setUp() {
        setUpCompany("PSH");
        cust = cust(comp, "S1", "한빛부품");
        supId = supplier(comp, "sup1", cust);
        sup = supplierAuth(comp, supId, "sup1", cust);
    }

    private long cust(String compCd, String cd, String nm) {
        return accounts.query(compCd, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, compCd, cd, nm);
    }

    private long supplier(String compCd, String login, long custId) {
        return accounts.query(compCd, """
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, user_type, cust_id, created_by, updated_by)
                values (?, ?, ?, ?, 'SUPPLIER', ?, 't', 't') returning user_id""", Long.class,
                compCd, login, login + " 협력사", encoder.encode(PW), custId);
    }

    private static RequestPostProcessor supplierAuth(String compCd, long id, String login, long custId) {
        return SecurityMockMvcRequestPostProcessors.authentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(id, compCd, login, login, "ko", false, LoginUser.SUPPLIER, custId), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private String loginRedirect(String password) throws Exception {
        String ip = "10.43." + (IP.incrementAndGet() % 250 + 1) + ".7";
        return mvc.perform(post("/login").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
                .param("compCd", comp).param("loginId", "sup1").param("password", password))
                .andReturn().getResponse().getRedirectedUrl();
    }

    private ResultActions changePwd(RequestPostProcessor who, String cur, String next) throws Exception {
        return mvc.perform(post(PWD_API).with(who).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + cur + "\",\"newPassword\":\"" + next + "\"}"));
    }

    @Test
    void 홈과_내_정보는_소속_협력사와_고정_메뉴를_보여_준다() throws Exception {
        mvc.perform(get("/portal").with(sup)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("한빛부품")))
                .andExpect(content().string(Matchers.containsString("href=\"/portal/me\"")))
                .andExpect(content().string(Matchers.containsString("aria-current=\"page\"")))
                .andExpect(content().string(Matchers.containsString("action=\"/logout\"")));
        mvc.perform(get("/portal/me").with(sup)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("한빛부품 (S1)")))
                .andExpect(content().string(Matchers.containsString("sup1")));
        // 회사 사용자는 포털 화면을 쓰지 않는다
        mvc.perform(get("/portal/me").with(admin)).andExpect(status().isForbidden());
    }

    @Test
    void A사로_B사_머리글은_자기_회사_자기_소속만() throws Exception {
        String other = accounts.newComp("PSI");
        long otherCust = cust(other, "S1", "다른회사부품");
        long otherSup = supplier(other, "sup1", otherCust);
        mvc.perform(get("/portal").with(supplierAuth(other, otherSup, "sup1", otherCust))).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("다른회사부품")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("한빛부품"))));
    }

    @Test
    void 자기_비밀번호_바꾸기_틀리면_실패_횟수가_남고_바꾸면_새_비밀번호로_로그인() throws Exception {
        changePwd(sup, "wrong-pass-1", "Copper-lantern-4821").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        // 바깥 트랜잭션으로 감싸면 이 1이 롤백돼 0 이 된다(5회 잠금이 안 걸림) — PortalService 주석
        assertThat(accounts.query(comp, "select login_fail_cnt from sys_user where user_id = ?", Integer.class, supId)).isEqualTo(1);
        changePwd(sup, PW, "short").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_POLICY"));
        changePwd(sup, PW, "Copper-lantern-4821").andExpect(status().isOk());
        assertThat(loginRedirect(PW)).isEqualTo("/login?error=bad");
        assertThat(loginRedirect("Copper-lantern-4821")).isEqualTo("/portal");
    }

    @Test
    void 회사_사용자_CSRF_없음은_거부() throws Exception {
        changePwd(admin, PW, "Copper-lantern-4821").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PORTAL_FORBIDDEN"));
        mvc.perform(post(PWD_API).with(sup).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"Copper-lantern-4821\"}")).andExpect(status().isForbidden());
        assertThat(encoder.matches(PW, accounts.query(comp, "select pwd_hash from sys_user where user_id = ?", String.class, supId))).isTrue();
    }

    @Test
    void 포털_메뉴는_포털_경로만_코드_경로_중복이면_기동_실패() {
        assertThatThrownBy(() -> new PortalMenuItem("srm-po", "portal.menu.po", "/page/srm-po", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortalMenuItem("srm-po", "srm.menu.po", "/portal/po", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortalMenuItem("srm-po", "portal.menu.po", "/portal/../main", 100))
                .isInstanceOf(IllegalArgumentException.class);
        PortalMenuSource po = () -> List.of(new PortalMenuItem("srm-po", "portal.menu.po", "/portal/po", 100));
        PortalMenuSource dup = () -> List.of(new PortalMenuItem("srm-po2", "portal.menu.po", "/portal/po", 200));
        assertThat(menus(new PlatformPortalMenu(), po).items()).extracting(PortalMenuItem::code).containsExactly("home", "srm-po", "me");
        assertThatThrownBy(() -> menus(new PlatformPortalMenu(), po, dup)).isInstanceOf(IllegalStateException.class);
    }

    private static PortalMenus menus(PortalMenuSource... sources) {
        StaticListableBeanFactory f = new StaticListableBeanFactory();
        for (int i = 0; i < sources.length; i++) {
            f.addBean("src" + i, sources[i]);
        }
        return new PortalMenus(f.getBeanProvider(PortalMenuSource.class));
    }
}
