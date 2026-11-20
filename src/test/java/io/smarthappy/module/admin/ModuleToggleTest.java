package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.stream.Collectors;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.platform.module.ModuleCatalog;
import io.smarthappy.platform.security.DevCompanySeed;
import io.smarthappy.platform.tenant.TenantRunner;
import jakarta.servlet.http.Cookie;

/**
 * 회사별 사용 모듈 (docs/80). 정상: 기존 회사 = 전부 켜짐, 켜고 끄기, 다시 켜면 데이터 그대로, 시드 기본값.
 * 이상 사용: 꺼진 모듈 메뉴 숨김·화면·API 직접 호출 403(관리자도) / 필수 짝 깨는 조합 거부(켜기·끄기) / 목록 밖 코드 /
 * 권한 없는 계정 403 / 같은 요청 키 두 번 409 / 현장 단말(POP·PDA) 경로 403 / <b>A사가 끈 모듈이 B사에는 그대로</b>.
 */
class ModuleToggleTest extends SysAdminTestSupport {

    private static final String API = "/api/admin/modules";
    private static final List<String> ALL = ModuleCatalog.toggleableCodes();

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        setUpCompany("MT");
        runner.run(comp, () -> DevCompanySeed.ensureMenuTree(jdbc, comp, "t"));
    }

    private static String body(List<String> on) {
        return "{\"on\":[" + on.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(",")) + "]}";
    }

    private static List<String> without(String... off) {
        List<String> drop = List.of(off);
        return ALL.stream().filter(c -> !drop.contains(c)).toList();
    }

    private void save(RequestPostProcessor who, List<String> on) throws Exception {
        mvc.perform(json(put(API), body(on)).with(who)).andExpect(status().isOk());
    }

    @Test
    void 기존_회사는_행이_없어도_전부_켜짐_목록_메뉴_API_그대로() throws Exception {
        assertThat(accounts.query(comp, "select count(*) from sys_comp_module", Integer.class)).isZero();
        mvc.perform(get(API).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[?(@.on == false)]").isEmpty())
                .andExpect(jsonPath("$.list[?(@.code == 'SYS')].toggleable").value(Matchers.contains(false)))
                .andExpect(jsonPath("$.list[?(@.code == 'QMS')].requires[*]").value(Matchers.contains("MST", "PRD", "WMS")));
        mvc.perform(get("/api/platform/menu").with(admin))
                .andExpect(jsonPath("$..progCd").value(Matchers.hasItems("sal-order", "aps-plan", "plm-eco", "pms-project", "sys-module")));
        mvc.perform(get("/api/sales/orders/customers").with(admin)).andExpect(status().isOk());
    }

    @Test
    void 끈_모듈은_메뉴에서_숨고_화면_API_는_관리자도_403_다시_켜면_데이터_그대로() throws Exception {
        long custId = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', '한빛상사', 'SALES', 't', 't') returning cust_id""", Long.class, comp);
        // 영업 끄기 — 계획(APS)은 영업이 필수라 같이 끈다
        save(admin, without("SAL", "APS"));
        mvc.perform(get("/api/platform/menu").with(admin))
                .andExpect(jsonPath("$..progCd").value(Matchers.not(Matchers.hasItems("sal-order"))))
                .andExpect(jsonPath("$..progCd").value(Matchers.not(Matchers.hasItems("aps-plan"))))
                .andExpect(jsonPath("$..progCd").value(Matchers.hasItems("mst-cust", "prd-wo", "sys-module")))
                .andExpect(jsonPath("$..name").value(Matchers.not(Matchers.hasItem("영업"))));   // 빈 폴더도 숨는다
        mvc.perform(get("/page/sal-order").with(admin)).andExpect(status().isForbidden()).andExpect(view().name("error/module-disabled"));
        mvc.perform(get("/api/sales/orders/customers").with(admin)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        // 다른 모듈은 그대로, 권한이 없는 것은 원래대로 NO_PERMISSION
        mvc.perform(get("/page/prd-wo").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/sales/orders/customers").with(worker)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        // 기준정보까지 전부 끄기 → 거래처 API 403, 다시 켜면 같은 거래처가 그대로
        save(admin, List.of());
        mvc.perform(get("/api/master/customers/" + custId).with(worker)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        mvc.perform(get("/api/platform/menu").with(worker)).andExpect(jsonPath("$..progCd").value(Matchers.not(Matchers.hasItem("mst-cust"))));
        assertThat(accounts.query(comp, "select count(*) from mst_cust", Integer.class)).isEqualTo(1);   // 끄기는 숨김
        save(admin, ALL);
        mvc.perform(get("/api/master/customers/" + custId).with(worker)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cust.custNm").value("한빛상사"));
        mvc.perform(get("/api/platform/menu").with(admin)).andExpect(jsonPath("$..progCd").value(Matchers.hasItems("sal-order", "aps-plan")));
        // 감사: 누가 언제 껐다 켰는지
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'sys_comp_module' and action = 'UPDATE'",
                Integer.class)).isPositive();
    }

    @Test
    void 필수_짝이_깨지는_조합은_켜기도_끄기도_거부하고_아무것도_바뀌지_않는다() throws Exception {
        // 끄기 방향: 창고만 끄면 품질·구매가 창고를 필수로 써서 거부
        mvc.perform(json(put(API), body(without("WMS"))).with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY"))
                .andExpect(jsonPath("$.details").value(Matchers.hasSize(2)))
                .andExpect(jsonPath("$.details[0]").value(Matchers.containsString("창고")));
        assertThat(accounts.query(comp, "select count(*) from sys_comp_module", Integer.class)).isZero();
        // 켜기 방향: 생산만 꺼진 상태에서 품질을 켜려 함(생산 없이 품질) → 거부, 앞 상태 그대로
        save(admin, without("PRD", "QMS", "APS", "PLM"));
        mvc.perform(json(put(API), body(without("PRD", "APS", "PLM"))).with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY"));
        assertThat(accounts.query(comp, "select use_yn from sys_comp_module where module_cd = 'QMS'", String.class)).isEqualTo("N");
        // 목록 밖 코드 400, SYS 는 보내도 무시(항상 켜짐), on 없음 400
        mvc.perform(json(put(API), body(List.of("MST", "XYZ"))).with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE")).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(json(put(API), "{}").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        save(admin, List.of("SYS", "MST"));
        assertThat(accounts.query(comp, "select count(*) from sys_comp_module where module_cd = 'SYS'", Integer.class)).isZero();
        mvc.perform(get("/page/sys-module").with(admin)).andExpect(status().isOk());   // 시스템은 항상
        // DB 도 SYS 행을 받지 않는다
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> accounts.exec(comp, """
                insert into sys_comp_module (comp_cd, module_cd, use_yn, created_by, updated_by) values (?, 'SYS', 'N', 't', 't')""", comp))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 사용_모듈_권한이_없으면_화면_API_403_같은_요청_키_두_번은_409() throws Exception {
        mvc.perform(get("/page/sys-module").with(hr)).andExpect(status().isForbidden()).andExpect(view().name("error/page-forbidden"));
        mvc.perform(get(API).with(worker)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        mvc.perform(json(put(API), body(List.of())).with(hr)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        assertThat(accounts.query(comp, "select count(*) from sys_comp_module", Integer.class)).isZero();
        // 조회만 가진 사용자는 저장 403
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "sys-module", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        mvc.perform(get(API).with(v)).andExpect(status().isOk());
        mvc.perform(json(put(API), body(List.of())).with(v)).andExpect(status().isForbidden());
        // 연타·재전송
        mvc.perform(json(put(API), body(without("PLM"))).header("X-Idempotency-Key", "mt-key-1").with(admin)).andExpect(status().isOk());
        mvc.perform(json(put(API), body(ALL)).header("X-Idempotency-Key", "mt-key-1").with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select use_yn from sys_comp_module where module_cd = 'PLM'", String.class)).isEqualTo("N");
    }

    @Test
    void A사가_끈_모듈이_B사에는_그대로() throws Exception {
        String compB = accounts.newComp("MTB");
        long adminB = accounts.newUser(compB, "admin", PW);
        adminGroup(compB, "ADMIN", adminB);
        RequestPostProcessor b = accounts.as(compB, adminB, "admin");
        save(admin, without("SAL", "APS"));
        mvc.perform(get("/api/sales/orders/customers").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/sales/orders/customers").with(b)).andExpect(status().isOk());
        mvc.perform(get(API).with(b)).andExpect(jsonPath("$.list[?(@.on == false)]").isEmpty());
        assertThat(accounts.query(compB, "select count(*) from sys_comp_module", Integer.class)).isZero();
        // B사 저장이 A사 행을 바꾸지 않는다
        save(b, without("PLM"));
        assertThat(accounts.query(comp, "select use_yn from sys_comp_module where module_cd = 'PLM'", String.class)).isEqualTo("Y");
        assertThat(accounts.query(comp, "select use_yn from sys_comp_module where module_cd = 'SAL'", String.class)).isEqualTo("N");
        // A사 회사 범위로 B사 comp_cd 행을 넣으면 RLS 거부
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> accounts.exec(comp, """
                insert into sys_comp_module (comp_cd, module_cd, use_yn, created_by, updated_by) values (?, 'QMS', 'N', 't', 't')""", compB))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void 데모_시드는_행이_없을_때만_핵심_다섯_개와_구매를_켠다() {
        runner.run(comp, () -> DevCompanySeed.ensureModules(jdbc, comp, "t"));
        assertThat(accounts.query(comp, "select string_agg(module_cd, ',' order by module_cd) from sys_comp_module where use_yn = 'Y'",
                String.class)).isEqualTo("CMS,MST,PRD,QMS,SRM,WMS");
        assertThat(accounts.query(comp, "select string_agg(module_cd, ',' order by module_cd) from sys_comp_module where use_yn = 'N'",
                String.class)).isEqualTo("APS,PLM,PMS,SAL");
        // 관리자가 바꾼 값은 다음 기동 시드가 되돌리지 않는다
        accounts.exec(comp, "update sys_comp_module set use_yn = 'Y' where module_cd = 'SAL'");
        runner.run(comp, () -> DevCompanySeed.ensureModules(jdbc, comp, "t"));
        assertThat(accounts.query(comp, "select use_yn from sys_comp_module where module_cd = 'SAL'", String.class)).isEqualTo("Y");
        assertThat(ModuleCatalog.brokenPairs(ModuleCatalog.DEMO_DEFAULT_ON)).isEmpty();
    }

    // ---------- 현장 단말 ----------

    private Cookie terminal(String cd, String type) throws Exception {
        if (accounts.query(comp, "select count(*) from sys_plant where plant_cd = 'P1'", Integer.class) == 0) {
            accounts.addPlant(comp, "P1", "1공장");
        }
        String ip = "10.86." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"" + type + "\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return new Cookie(PopAuthService.COOKIE, set.substring(set.indexOf('=') + 1, set.indexOf(';')));
    }

    @Test
    void 현장_단말은_꺼진_모듈_경로가_403_공통_경로는_그대로() throws Exception {
        Cookie pop = terminal("POP-01", "POP");
        mvc.perform(get("/api/pop/session").cookie(pop)).andExpect(status().isOk()).andExpect(jsonPath("$.data.pm").value(true));
        // 설비를 끄면 고장 신고·점검 단추가 숨고(세션 깃발) API 는 403
        save(admin, without("CMS"));
        mvc.perform(get("/api/pop/session").cookie(pop)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pm").value(false)).andExpect(jsonPath("$.data.breakdown").value(false));
        mvc.perform(get("/api/pop/pm/runs").cookie(pop)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        mvc.perform(get("/api/pop/breakdown/equips").cookie(pop)).andExpect(status().isForbidden());
        mvc.perform(get("/api/pop/home").cookie(pop)).andExpect(status().isOk());
        // 생산을 끄면(품질·계획도 함께) 공정 단말 홈·실적 API 403, 화면은 안내, 세션·언어는 그대로
        save(admin, without("CMS", "PRD", "QMS", "APS", "PLM"));
        mvc.perform(get("/api/pop/home").cookie(pop)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        mvc.perform(get("/api/pop/procs/1").cookie(pop)).andExpect(status().isForbidden());
        mvc.perform(get("/api/pop/procs/1/insp").cookie(pop)).andExpect(status().isForbidden());
        mvc.perform(get("/pop").cookie(pop)).andExpect(status().isForbidden()).andExpect(view().name("error/module-disabled"));
        mvc.perform(get("/api/pop/session").cookie(pop)).andExpect(status().isOk());
        // 창고 단말(PDA): 창고를 끄면(품질·구매도) 창고 경로 403, 다시 켜면 열림
        Cookie pda = terminal("PDA-01", "PDA");
        mvc.perform(get("/api/pop/wh/counts").cookie(pda)).andExpect(jsonPath("$.code").value("POP_NO_WORKER"));   // 모듈 문은 통과(작업자 없음 428)
        save(admin, List.of("MST", "PRD", "SAL"));
        mvc.perform(get("/api/pop/wh/counts").cookie(pda)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        mvc.perform(get("/pop/wh").cookie(pda)).andExpect(status().isForbidden()).andExpect(view().name("error/module-disabled"));
        save(admin, ALL);
        mvc.perform(get("/api/pop/wh/counts").cookie(pda)).andExpect(jsonPath("$.code").value("POP_NO_WORKER"));   // 모듈 문은 통과(작업자 없음 428)
        mvc.perform(get("/pop").cookie(pop)).andExpect(status().isOk()).andExpect(view().name("pop/home"));
    }

    @Test
    void 모바일_웹앱도_꺼진_모듈_조각은_403() throws Exception {
        mvc.perform(get("/m/find").with(admin)).andExpect(status().isOk());
        save(admin, without("CMS", "PRD", "QMS", "APS", "PLM"));   // LOT(생산)·설비 상태(설비) 둘 다 꺼짐
        mvc.perform(get("/m/find").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/m").with(admin)).andExpect(status().isOk());
    }
}
