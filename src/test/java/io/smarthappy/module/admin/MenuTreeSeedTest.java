package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.platform.security.DefaultMenuTree;
import io.smarthappy.platform.security.DevCompanySeed;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * 기본 메뉴 트리 (docs/75) — DefaultMenuTree 한 벌을 시드·운영 콘솔이 같이 쓴다.
 * 정상: 모든 화면이 트리에(인쇄 팝업 제외)·하위 폴더 자리·두 번 돌려도 같음 / 옛 배치 옮기기: 시드가 만든 그대로인 행만 옮기고
 * 회사가 메뉴 관리에서 고친 행(row_ver 증가)·회사가 만든 폴더는 그대로 / 권한: 메뉴 API 는 권한 있는 화면만.
 * A사로 B사: 시드는 그 회사 범위 안에서만 돈다 — 다른 회사 메뉴 행 수가 그대로인지 본다.
 * 화면 정리(docs/79): 메뉴에서 뺀 화면(NOT_IN_MENU)은 새 회사에 없고, 옛 시드 행은 손대지 않은 것만 지운다 —
 * 회사가 고친 행은 그대로. [관련 화면] API 는 물은 화면 중 권한 있는 것만(없는 화면·이상한 코드는 조용히 뺀다).
 */
class MenuTreeSeedTest extends SysAdminTestSupport {

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        setUpCompany("MT");
    }

    private void seed(String compCd) {
        runner.run(compCd, () -> DevCompanySeed.ensureAdminGroupAndMenus(jdbc, compCd, "t"));
    }

    /** 화면의 메뉴 자리 "폴더키>…>progCd" (본 자리 = 가장 먼저 만든 행) */
    private String pathOf(String progCd) {
        return accounts.query(comp, """
                with recursive up(menu_id, parent_id, label, lvl) as (
                    select m.menu_id, m.parent_id, m.prog_cd::text, 0 from sys_menu m
                     where m.menu_id = (select min(menu_id) from sys_menu where prog_cd = ?)
                    union all
                    select p.menu_id, p.parent_id, p.menu_nm_key::text, u.lvl + 1 from up u join sys_menu p on p.menu_id = u.parent_id
                )
                select string_agg(label, '>' order by lvl desc) from up""", String.class, progCd);
    }

    @Test
    void 마이그레이션의_모든_화면은_트리에_있거나_메뉴_밖_목록에_있다() {
        Set<String> all = new HashSet<>(jdbc.queryForList("select prog_cd from sys_prog where use_yn = 'Y'", String.class));
        Set<String> placed = DefaultMenuTree.progCds();
        Set<String> missing = new HashSet<>(all);
        missing.removeAll(placed);
        missing.removeAll(DefaultMenuTree.NOT_IN_MENU);
        assertThat(missing).as("DefaultMenuTree 에 자리가 없는 화면 — 트리에 넣거나 NOT_IN_MENU 에 근거와 함께").isEmpty();
        Set<String> unknown = new HashSet<>(placed);
        unknown.removeAll(all);
        assertThat(unknown).as("트리에 있지만 sys_prog 에 없는 화면").isEmpty();
        assertThat(DefaultMenuTree.folderKeys()).as("폴더 이름 키는 하나씩").doesNotHaveDuplicates();
    }

    @Test
    void 새_회사는_하위_폴더_트리로_두번_돌려도_같다() {
        seed(comp);
        assertThat(pathOf("wms-ship")).isEqualTo("menu.wms>menu.wms.flow>wms-ship");
        assertThat(pathOf("apqp-fmea")).isEqualTo("menu.qms>menu.qms.apqp>apqp-fmea");
        assertThat(pathOf("sys-approval")).isEqualTo("menu.my>sys-approval");
        assertThat(pathOf("sys-mobile-app")).isEqualTo("menu.field>sys-mobile-app");
        assertThat(pathOf("cms-state")).isEqualTo("menu.cms>menu.cms.monitor>cms-state");
        assertThat(pathOf("home-main")).isEqualTo("home-main");
        // 바로가기: 품질 폴더의 특성, 내 업무의 내 공수
        assertThat(accounts.query(comp, """
                select count(*) from sys_menu m join sys_menu f on f.menu_id = m.parent_id
                 where m.prog_cd = 'pms-worklog' and f.menu_nm_key in ('menu.pms', 'menu.my')""", Long.class)).isEqualTo(2);
        long rows = accounts.query(comp, "select count(*) from sys_menu", Long.class);
        long progRows = accounts.query(comp, "select count(*) from sys_menu where prog_cd is not null", Long.class);
        seed(comp);
        assertThat(accounts.query(comp, "select count(*) from sys_menu", Long.class)).isEqualTo(rows);
        // 본 자리 수 + 바로가기 9개(내 공수·특성·불량 유형·설비·금형·BOM·라우팅 + docs/79 Q8: 품질의 LOT 추적·구매의 연계 현황)
        assertThat(progRows).isEqualTo(DefaultMenuTree.progCds().size() + 9L);
        // 깊이 3 이하
        assertThat(accounts.query(comp, """
                with recursive d(menu_id, lvl) as (
                    select menu_id, 1 from sys_menu where parent_id is null
                    union all select m.menu_id, d.lvl + 1 from d join sys_menu m on m.parent_id = d.menu_id)
                select max(lvl) from d""", Integer.class)).isLessThanOrEqualTo(3);
    }

    @Test
    void 옛_배치는_시드가_만든_그대로인_행만_옮기고_회사가_고친_행은_둔다() {
        String other = accounts.newComp("MU");
        seed(other);
        long otherRows = accounts.query(other, "select count(*) from sys_menu", Long.class);
        // 예전 시드 모양: 시스템 폴더 1단에 내 알림·공지, 생산 폴더에 POP 진입, 회사가 만든 폴더 하나
        accounts.exec(comp, """
                insert into sys_menu (comp_cd, menu_nm_key, sort_no, created_by, updated_by) values
                    (?, 'menu.system', 900, 't', 't'), (?, 'menu.production', 300, 't', 't')""", comp, comp);
        accounts.exec(comp, """
                insert into sys_menu (comp_cd, parent_id, prog_cd, sort_no, created_by, updated_by)
                select ?, f.menu_id, x.prog_cd, x.sort_no, 't', 't'
                  from sys_menu f join (values ('menu.system', 'sys-inbox', 5), ('menu.system', 'sys-notice', 939),
                                               ('menu.production', 'prd-pop', 95)) x(fk, prog_cd, sort_no) on x.fk = f.menu_nm_key
                 where f.comp_cd = ?""", comp, comp);
        accounts.exec(comp, "insert into sys_menu (comp_cd, menu_nm, sort_no, created_by, updated_by) values (?, '우리 회사 즐겨찾기', 5, 'boss', 'boss')", comp);
        // 회사가 메뉴 관리에서 공지를 고쳤다(row_ver 증가) — 자기 폴더로 옮겼다고 친다
        accounts.exec(comp, """
                update sys_menu set parent_id = (select menu_id from sys_menu where comp_cd = ? and menu_nm = '우리 회사 즐겨찾기'),
                       row_ver = row_ver + 1, updated_by = 'boss' where comp_cd = ? and prog_cd = 'sys-notice'""", comp, comp);

        seed(comp);

        assertThat(pathOf("sys-inbox")).isEqualTo("menu.my>sys-inbox");
        // POP 진입은 메뉴에서 뺀 화면(docs/79 M1) — 시드가 만든 그대로인 옛 행은 지워진다
        assertThat(accounts.query(comp, "select count(*) from sys_menu where prog_cd = 'sys-inbox'", Long.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from sys_menu where prog_cd = 'prd-pop'", Long.class)).isZero();
        // 회사가 고친 행·회사 폴더는 그대로(공지는 즐겨찾기 아래, 새로 만들지 않음)
        assertThat(accounts.query(comp, """
                select f.menu_nm from sys_menu m join sys_menu f on f.menu_id = m.parent_id where m.prog_cd = 'sys-notice'""",
                String.class)).isEqualTo("우리 회사 즐겨찾기");
        assertThat(accounts.query(comp, "select count(*) from sys_menu where prog_cd = 'sys-notice'", Long.class)).isEqualTo(1);
        // A사 시드는 B사 메뉴를 건드리지 않는다
        assertThat(accounts.query(other, "select count(*) from sys_menu", Long.class)).isEqualTo(otherRows);
    }

    @Test
    void 메뉴에서_뺀_화면은_새_회사에_없고_바로가기는_원래_폴더에() {
        seed(comp);
        String[] out = DefaultMenuTree.NOT_IN_MENU.toArray(String[]::new);
        assertThat(accounts.query(comp, "select count(*) from sys_menu where prog_cd = any (?)", Long.class, (Object) out)).isZero();
        assertThat(DefaultMenuTree.NOT_IN_MENU).contains("cms-kpi", "qms-lot-trace", "mst-term", "wms-asrs", "prd-pop", "srm-notify");
        assertThat(DefaultMenuTree.NOT_IN_MENU_WHY.values()).allSatisfy(why -> assertThat(why).isNotBlank());
        // 다른 모듈 화면으로 합친 것은 원래 폴더에 남는 화면 바로가기(docs/79 Q8)
        assertThat(accounts.query(comp, """
                select string_agg(f.menu_nm_key || '>' || m.prog_cd, ',' order by m.prog_cd) from sys_menu m join sys_menu f on f.menu_id = m.parent_id
                 where (m.prog_cd = 'prd-lot' and f.menu_nm_key = 'menu.qms.ncr') or (m.prog_cd = 'sys-outbox' and f.menu_nm_key = 'menu.srm.po')""",
                String.class)).isEqualTo("menu.qms.ncr>prd-lot,menu.srm.po>sys-outbox");
        assertThat(pathOf("prd-lot")).isEqualTo("menu.production>menu.production.lot>prd-lot");
    }

    @Test
    void 옛_시드_행_정리는_손대지_않은_행만_회사가_고친_행은_둔다() {
        String other = accounts.newComp("MV");
        seed(other);
        // 옛 시드 모양: 설비 모니터링 폴더에 KPI·사유 검토, 기준정보에 용어 사전(모두 시드 그대로) + 회사가 고친 자동창고 행
        accounts.exec(comp, "insert into sys_menu (comp_cd, menu_nm_key, sort_no, created_by, updated_by) values (?, 'menu.cms', 600, 't', 't')", comp);
        accounts.exec(comp, """
                insert into sys_menu (comp_cd, parent_id, prog_cd, sort_no, created_by, updated_by)
                select ?, f.menu_id, x.prog_cd, x.sort_no, 't', 't'
                  from sys_menu f, (values ('cms-kpi', 60), ('cms-state-review', 50), ('mst-term', 30), ('wms-asrs', 70)) x(prog_cd, sort_no)
                 where f.comp_cd = ? and f.menu_nm_key = 'menu.cms'""", comp, comp);
        accounts.exec(comp, "update sys_menu set row_ver = row_ver + 1, updated_by = 'boss' where comp_cd = ? and prog_cd = 'wms-asrs'", comp);
        long otherRows = accounts.query(other, "select count(*) from sys_menu", Long.class);

        seed(comp);

        assertThat(accounts.query(comp, "select count(*) from sys_menu where prog_cd in ('cms-kpi', 'cms-state-review', 'mst-term')",
                Long.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_menu where prog_cd = 'wms-asrs'", Long.class))
                .as("회사가 메뉴 관리에서 고친 행은 시드가 지우지 않는다").isEqualTo(1);
        // 두 번 돌려도 같다
        long rows = accounts.query(comp, "select count(*) from sys_menu", Long.class);
        seed(comp);
        assertThat(accounts.query(comp, "select count(*) from sys_menu", Long.class)).isEqualTo(rows);
        assertThat(accounts.query(other, "select count(*) from sys_menu", Long.class)).isEqualTo(otherRows);
    }

    @Test
    void 관련_화면_API_는_물은_화면_중_권한_있는_것만_이상한_코드는_뺀다() throws Exception {
        seed(comp);
        String q = "/api/platform/prog-links?progs=cms-kpi, mst-cust ,no-such-prog,../x,MST-CUST,mst-cust";
        mvc.perform(get(q).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].progCd").value(Matchers.contains("cms-kpi", "mst-cust")))
                .andExpect(jsonPath("$.data[0].name").value(Matchers.not(Matchers.emptyOrNullString())))
                .andExpect(jsonPath("$.data[0].name").value(Matchers.not("cms-kpi")));
        mvc.perform(get(q).with(worker)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].progCd").value(Matchers.contains("mst-cust")));
        // 메뉴에 없어도 화면 주소는 권한 그대로 — 관리자는 열리고 권한 없는 사용자는 403
        mvc.perform(get("/page/cms-kpi").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/page/cms-kpi").with(worker)).andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/menu").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$..progCd").value(Matchers.not(Matchers.hasItem("cms-kpi"))));
        // 11개 이상 물으면 앞 10개(서로 다른 화면)만 본다
        List<String> ten = DefaultMenuTree.progCds().stream().filter(c -> !c.equals("sys-user")).limit(10).toList();
        mvc.perform(get("/api/platform/prog-links?progs=" + String.join(",", ten) + ",sys-user").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data[*].progCd").value(Matchers.not(Matchers.hasItem("sys-user"))));
    }

    @Test
    void 메뉴_API_는_권한_있는_화면만_모바일_안내는_누구나() throws Exception {
        seed(comp);
        mvc.perform(get("/api/platform/menu").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$..progCd").value(Matchers.hasItems("wms-ship", "apqp-fmea", "sys-mobile-app")));
        mvc.perform(get("/api/platform/menu").with(worker)).andExpect(status().isOk())
                .andExpect(jsonPath("$..progCd").value(Matchers.hasItems("mst-cust", "sys-mobile-app")))
                .andExpect(jsonPath("$..progCd").value(Matchers.not(Matchers.hasItem("wms-ship"))));
        for (var who : List.of(admin, worker)) {
            mvc.perform(get("/page/sys-mobile-app").with(who)).andExpect(status().isOk());
        }
    }
}
