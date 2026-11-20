package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * 공통코드 전환 (docs/68) — 회사가 늘릴 수 있게 바꾼 6개 그룹(공지 분류·공정 분류·고객 반품 사유·유해위험 분류·프로젝트 유형·문서 유형).
 * 정상 흐름 + 이상 사용: 사용 중지 코드·없는 코드 거부, A사 회사 코드를 B사가 쓰기 거부(API·직접 SQL), 길이 초과, 기존 행은 그대로.
 */
class CommonCodeExtTest extends SysAdminTestSupport {

    private static final List<String> CONVERTED = List.of("NOTICE_CAT", "PROC_CAT", "WMS_CUST_RTN_REASON", "CMS_HAZARD_CAT",
            "PMS_PROJ_TYPE", "DOC_TYPE");

    @Autowired
    TenantRunner runner;

    @Autowired
    CodeService codes;

    @Autowired
    JdbcTemplate jdbc;

    String compB;
    long adminB;

    @BeforeEach
    void setUp() {
        setUpCompany("CX");
        compB = accounts.newComp("CXB");
        adminB = accounts.newUser(compB, "admin", PW);
        adminGroup(compB, "ADMIN", adminB);
    }

    private static String constraintOf(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return PgErrors.constraint(e).orElse("(none) " + e.getMessage());
        }
        return "(no error)";
    }

    private void addCode(String compCd, String grp, String code, String useYn) {
        accounts.exec(compCd, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, use_yn, created_by, updated_by)
                values (?, ?, ?, ?, ?, 't', 't')""", compCd, grp, code, "회사 " + code, useYn);
    }

    private static String procBody(String cd, String cat) {
        return "{\"procCd\":\"" + cd + "\",\"procNm\":\"ZT 공정 " + cd + "\",\"procCat\":\"" + cat + "\",\"outsrcYn\":\"N\",\"sortNo\":10,"
                + "\"useYn\":\"Y\"}";
    }

    private static String noticeBody(String cat) {
        return "{\"catCd\":\"" + cat + "\",\"title\":\"ZT 공지\",\"body\":\"본문\",\"pinYn\":\"N\",\"postFrom\":\""
                + LocalDate.now(ZoneId.of("Asia/Seoul")) + "\",\"postTo\":null,\"useYn\":\"Y\",\"rowVer\":null}";
    }

    // ================================================================== 관리 화면(그룹·길이)

    @Test
    void 전환한_그룹은_회사코드_관리_목록에_나오고_칸_너비만큼만_받는다() throws Exception {
        mvc.perform(get("/api/admin/codes").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[*].grpCd").value(Matchers.hasItems(CONVERTED.toArray())))
                .andExpect(jsonPath("$.list[?(@.grpCd=='PROC_CAT')].maxLen").value(Matchers.hasItem(10)))
                .andExpect(jsonPath("$.list[?(@.grpCd=='CMS_HAZARD_CAT')].maxLen").value(Matchers.hasItem(5)))
                .andExpect(jsonPath("$.list[?(@.grpCd=='WMS_CUST_RTN_REASON')].maxLen").value(Matchers.hasItem(20)))
                .andExpect(jsonPath("$.list[?(@.grpCd=='UNIT')].maxLen").value(Matchers.hasItem(30)));
        // 칸 너비(5)를 넘는 코드 → 400 TOO_LONG(최대값 함께). 딱 맞는 길이는 된다
        mvc.perform(json(post("/api/admin/codes/CMS_HAZARD_CAT"), "{\"code\":\"FALLS\",\"codeNm\":\"추락\",\"sortNo\":80}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/admin/codes/CMS_HAZARD_CAT"), "{\"code\":\"FIRE01\",\"codeNm\":\"화재\",\"sortNo\":90}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        // 직접 SQL 도 DB 트리거가 막는다
        assertThat(constraintOf(() -> addCode(comp, "PROC_CAT", "CASTING_01", "Y"))).isEqualTo("(no error)");
        assertThat(constraintOf(() -> addCode(comp, "PROC_CAT", "INJECTION01", "Y"))).isEqualTo("ck_sys_comp_code_len");
        // 시스템 코드와 같은 코드는 여전히 거부
        mvc.perform(json(post("/api/admin/codes/PROC_CAT"), "{\"code\":\"PRESS\",\"codeNm\":\"프레스\"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 회사 코드는 공통코드 API(선택 목록)에 시스템 코드와 함께 — B사에는 안 보인다
        mvc.perform(get("/api/platform/codes").param("grp", "PROC_CAT").with(admin))
                .andExpect(jsonPath("$.data[*].code").value(Matchers.hasItems("PRESS", "CASTING_01")));
        mvc.perform(get("/api/platform/codes").param("grp", "PROC_CAT").with(accounts.as(compB, adminB, "admin")))
                .andExpect(jsonPath("$.data[*].code").value(Matchers.not(Matchers.hasItem("CASTING_01"))));
    }

    // ================================================================== 판정 함수

    @Test
    void usable_은_시스템과_자기_회사_사용중_코드만_참() {
        addCode(comp, "DOC_TYPE", "MANUAL", "Y");
        addCode(comp, "DOC_TYPE", "OLDSPEC", "N");
        AtomicBoolean[] r = {new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean()};
        runner.run(comp, () -> {
            r[0].set(codes.usable("DOC_TYPE", "WI"));
            r[1].set(codes.usable("DOC_TYPE", "MANUAL"));
            r[2].set(codes.usable("DOC_TYPE", "OLDSPEC"));
            r[3].set(codes.usable("DOC_TYPE", "NOPE"));
            r[4].set(codes.usable("DOC_TYPE", "bad code';--"));
        });
        assertThat(r[0]).isTrue();
        assertThat(r[1]).isTrue();
        assertThat(r[2]).isFalse();   // 사용 중지
        assertThat(r[3]).isFalse();   // 없는 코드
        assertThat(r[4]).isFalse();   // 형식 밖은 DB 까지 가지 않는다
        AtomicBoolean other = new AtomicBoolean(true);
        runner.run(compB, () -> other.set(codes.usable("DOC_TYPE", "MANUAL")));
        assertThat(other).as("A사 회사 코드를 B사 범위에서").isFalse();
        assertThat(CodeService.isCodeFormat(null)).isTrue();
        assertThat(CodeService.isCodeFormat("OLD-1")).isTrue();
        assertThat(CodeService.isCodeFormat("a b")).isFalse();
    }

    // ================================================================== API (서비스 검증)

    @Test
    void 공정_분류_회사코드로_저장_사용중지_없는_코드_남의_코드는_거부() throws Exception {
        addCode(comp, "PROC_CAT", "CASTING", "Y");
        addCode(comp, "PROC_CAT", "FORGE", "N");
        addCode(compB, "PROC_CAT", "BONLY", "Y");
        mvc.perform(json(post("/api/master/procs"), procBody("ZTCAST", "CASTING")).with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select proc_cat from mst_proc where proc_cd = 'ZTCAST'", String.class)).isEqualTo("CASTING");
        mvc.perform(get("/api/master/procs/grid").with(admin)).andExpect(status().isOk());
        for (String bad : List.of("FORGE", "NOPE", "BONLY")) {
            mvc.perform(json(post("/api/master/procs"), procBody("ZTX" + bad.length(), bad)).with(admin))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("procCat"))
                    .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        }
        // 시스템 코드는 그대로
        mvc.perform(json(post("/api/master/procs"), procBody("ZTPRESS", "PRESS")).with(admin)).andExpect(status().isOk());
    }

    @Test
    void 공지_분류_회사코드로_저장하고_사용중지_뒤에도_옛_분류로_찾는다() throws Exception {
        addCode(comp, "NOTICE_CAT", "SAFETY", "Y");
        addCode(compB, "NOTICE_CAT", "BONLY", "Y");
        mvc.perform(json(post("/api/admin/notices"), noticeBody("SAFETY")).with(admin)).andExpect(status().isOk());
        mvc.perform(json(post("/api/admin/notices"), noticeBody("BONLY")).with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("catCd"))
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where grp_cd = 'NOTICE_CAT' and code = 'SAFETY'");
        mvc.perform(json(post("/api/admin/notices"), noticeBody("SAFETY")).with(admin)).andExpect(status().isBadRequest());
        // 조회 조건은 형식만 — 사용 중지한 분류로도 옛 공지를 찾는다, 형식 밖은 400
        mvc.perform(get("/api/admin/notices").param("catCd", "SAFETY").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/notices").param("catCd", "x y").with(admin)).andExpect(status().isBadRequest());
        // 표시 이름표에는 사용 중지 코드도(옛 행이 날 코드로 보이지 않게)
        mvc.perform(get("/api/platform/codes/names").param("grp", "NOTICE_CAT").with(admin))
                .andExpect(jsonPath("$.data.SAFETY").value("회사 SAFETY"));
        // 옛 행의 다른 칸 고치기는 된다(값이 그대로면 코드 검사를 다시 하지 않는다)
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_notice set title = 'ZT 공지 2' where cat_cd = 'SAFETY'")))
                .isEqualTo("(no error)");
    }

    // ================================================================== 직접 SQL(트리거)

    @Test
    void 직접_SQL_로도_회사코드_규칙은_막힌다() {
        addCode(comp, "PROC_CAT", "CASTING", "Y");
        addCode(comp, "PROC_CAT", "FORGE", "N");
        addCode(compB, "PROC_CAT", "BONLY", "Y");
        String ins = "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, ?, 'ZT', ?, 't', 't')";
        assertThat(constraintOf(() -> accounts.exec(comp, ins, comp, "ZT1", "CASTING"))).isEqualTo("(no error)");
        assertThat(constraintOf(() -> accounts.exec(comp, ins, comp, "ZT2", "FORGE"))).isEqualTo("fk_mst_proc_proc_cat_code");
        assertThat(constraintOf(() -> accounts.exec(comp, ins, comp, "ZT3", "NOPE"))).isEqualTo("fk_mst_proc_proc_cat_code");
        assertThat(constraintOf(() -> accounts.exec(comp, ins, comp, "ZT4", "BONLY"))).as("A사로 B사 코드")
                .isEqualTo("fk_mst_proc_proc_cat_code");
        assertThat(constraintOf(() -> accounts.exec(comp, ins, comp, "ZT5", "bad cat"))).isEqualTo("fk_mst_proc_proc_cat_code");   // BEFORE 트리거가 CHECK 보다 먼저
        // 바꾸기도 같은 검사, 사용 중지 뒤 다른 칸 고치기는 된다
        assertThat(constraintOf(() -> accounts.exec(comp, "update mst_proc set proc_cat = 'BONLY' where proc_cd = 'ZT1'")))
                .isEqualTo("fk_mst_proc_proc_cat_code");
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where grp_cd = 'PROC_CAT' and code = 'CASTING'");
        assertThat(constraintOf(() -> accounts.exec(comp, "update mst_proc set proc_nm = 'ZT 주조' where proc_cd = 'ZT1'")))
                .isEqualTo("(no error)");
        assertThat(constraintOf(() -> accounts.exec(comp, "update mst_proc set proc_cat = proc_cat where proc_cd = 'ZT1'")))
                .isEqualTo("(no error)");
    }

    @Test
    void 전환한_칸마다_코드_트리거가_걸리고_목록_CHECK_는_풀렸다() {
        List<String[]> cols = List.of(
                new String[] {"sys_notice", "cat_cd", "NOTICE_CAT"},
                new String[] {"mst_proc", "proc_cat", "PROC_CAT"},
                new String[] {"wms_cust_rtn", "reason_cd", "WMS_CUST_RTN_REASON"},
                new String[] {"cms_risk_item", "hazard_cat", "CMS_HAZARD_CAT"},
                new String[] {"pms_template", "proj_type", "PMS_PROJ_TYPE"},
                new String[] {"pms_project", "proj_type", "PMS_PROJ_TYPE"},
                new String[] {"mst_doc", "doc_type", "DOC_TYPE"});
        for (String[] c : cols) {
            Integer triggers = jdbc.queryForObject("""
                    select count(*)::int from pg_trigger t join pg_proc p on p.oid = t.tgfoid
                     where t.tgrelid = ?::regclass and not t.tgisinternal and p.proname = 'hb_check_code_col'
                       and position(? in encode(t.tgargs, 'escape')) = 1 and position(? in encode(t.tgargs, 'escape')) > 0""",
                    Integer.class, c[0], c[2], c[1]);
            assertThat(triggers).as(c[0] + "." + c[1]).isEqualTo(1);
            Integer inChecks = jdbc.queryForObject("""
                    select count(*)::int from pg_constraint k
                     where k.conrelid = ?::regclass and k.contype = 'c' and pg_get_constraintdef(k.oid) ~ ('\\m' || ? || '\\M.*= ANY')""",
                    Integer.class, c[0], c[1]);
            assertThat(inChecks).as(c[0] + "." + c[1] + " IN 목록 CHECK").isZero();
            String ext = jdbc.queryForObject("select comp_ext_yn from sys_code_grp where grp_cd = ?", String.class, c[2]);
            assertThat(ext).isEqualTo("Y");
        }
    }

    @Test
    void 회사코드_관리에서_사용_중지하면_선택_목록에서_빠진다() throws Exception {
        mvc.perform(json(post("/api/admin/codes/WMS_CUST_RTN_REASON"), "{\"code\":\"DAMAGED\",\"codeNm\":\"운송 파손\",\"sortNo\":5}")
                .with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/codes").param("grp", "WMS_CUST_RTN_REASON").with(admin))
                .andExpect(jsonPath("$.data[*].code").value(Matchers.contains("QUALITY", "WRONG_SHIP", "OVER_QTY", "OTHER", "DAMAGED")));
        mvc.perform(json(put("/api/admin/codes/WMS_CUST_RTN_REASON/DAMAGED"), "{\"codeNm\":\"운송 파손\",\"sortNo\":0,\"useYn\":\"N\","
                + "\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/codes").param("grp", "WMS_CUST_RTN_REASON").with(admin))
                .andExpect(jsonPath("$.data[*].code").value(Matchers.not(Matchers.hasItem("DAMAGED"))));
        // 다른 회사(B사)는 A사 코드를 고칠 수 없다(RLS — 없는 코드)
        mvc.perform(json(put("/api/admin/codes/WMS_CUST_RTN_REASON/DAMAGED"), "{\"codeNm\":\"x\",\"sortNo\":0,\"useYn\":\"Y\","
                + "\"rowVer\":1}").with(accounts.as(compB, adminB, "admin"))).andExpect(status().isNotFound());
    }
}
