package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * 공통코드 다음 묶음 (docs/68 Q4·Q5) — 회사별 시스템 코드 숨김(선택 목록에서만 빠짐), 단위 칸 너비 30.
 * 정상 흐름 + 이상 사용: 분기에 쓰이는 코드·회사 확장 아닌 그룹 숨기기 거부, 빈 값으로 되살리기 거부, A사 숨김이 B사에 안 번짐(API·직접 SQL),
 * 숨긴 코드로 저장한 행은 그대로 저장·표시.
 */
class CommonCodeHideTest extends SysAdminTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    String compB;
    RequestPostProcessor adminB;

    @BeforeEach
    void setUp() {
        setUpCompany("CH");
        compB = accounts.newComp("CHB");
        long b = accounts.newUser(compB, "admin", PW);
        adminGroup(compB, "ADMIN", b);
        adminB = accounts.as(compB, b, "admin");
    }

    private org.springframework.test.web.servlet.ResultActions shown(RequestPostProcessor who, String grp, String code, String body)
            throws Exception {
        return mvc.perform(json(put("/api/admin/codes/" + grp + "/" + code + "/shown"), body).with(who));
    }

    private org.springframework.test.web.servlet.ResultActions pick(RequestPostProcessor who, String grp) throws Exception {
        return mvc.perform(get("/api/platform/codes").param("grp", grp).with(who));
    }

    @Test
    void 시스템코드를_숨기면_이_회사_선택목록에서만_빠지고_되살리면_돌아온다() throws Exception {
        // 관리 목록: 숨길 수 있는 코드(hideable)와 분기 코드(고정)
        mvc.perform(get("/api/admin/codes/PROC_CAT").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[?(@.code=='PRESS')].hideable").value(Matchers.hasItem(true)))
                .andExpect(jsonPath("$.list[?(@.code=='INSPECT')].hideable").value(Matchers.hasItem(false)));
        shown(admin, "PROC_CAT", "PRESS", "{\"useYn\":\"N\"}").andExpect(status().isOk());
        shown(admin, "PROC_CAT", "PRESS", "{\"useYn\":\"N\"}").andExpect(status().isOk());   // 두 번 = 같은 결과
        pick(admin, "PROC_CAT").andExpect(jsonPath("$.data[*].code").value(Matchers.not(Matchers.hasItem("PRESS"))))
                .andExpect(jsonPath("$.data[*].code").value(Matchers.hasItem("WELD")));
        // B사 목록에는 그대로
        pick(adminB, "PROC_CAT").andExpect(jsonPath("$.data[*].code").value(Matchers.hasItem("PRESS")));
        // 관리 목록은 숨긴 코드도 보이고 사용 여부 N
        mvc.perform(get("/api/admin/codes/PROC_CAT").with(admin))
                .andExpect(jsonPath("$.list[?(@.code=='PRESS')].useYn").value(Matchers.hasItem("N")));
        mvc.perform(get("/api/admin/codes/PROC_CAT").with(adminB))
                .andExpect(jsonPath("$.list[?(@.code=='PRESS')].useYn").value(Matchers.hasItem("Y")));
        // 이름표(옛 행 표시)는 그대로
        mvc.perform(get("/api/platform/codes/names").param("grp", "PROC_CAT").with(admin)).andExpect(jsonPath("$.data.PRESS").exists());
        // 되살리기
        shown(admin, "PROC_CAT", "PRESS", "{\"useYn\":\"Y\"}").andExpect(status().isOk());
        pick(admin, "PROC_CAT").andExpect(jsonPath("$.data[*].code").value(Matchers.hasItem("PRESS")));
    }

    @Test
    void 숨긴_코드로_저장한_행은_그대로_저장된다_저장검사는_숨김을_보지_않는다() throws Exception {
        shown(admin, "PROC_CAT", "PAINT", "{\"useYn\":\"N\"}").andExpect(status().isOk());
        // 숨겨도 저장 검사(hb_code_usable)는 그대로 — 기존 행·직접 입력이 깨지지 않게
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/master/procs"),
                "{\"procCd\":\"ZTPAINT\",\"procNm\":\"ZT 도장\",\"procCat\":\"PAINT\",\"outsrcYn\":\"N\"}").with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select proc_cat from mst_proc where proc_cd = 'ZTPAINT'", String.class)).isEqualTo("PAINT");
    }

    @Test
    void 분기코드_회사확장아닌_그룹_없는코드_빈값은_거부한다() throws Exception {
        // 분기에 쓰이는 시스템 코드(fixed) → useYn CODE_FIXED
        for (String[] gc : new String[][] {{"PROC_CAT", "INSPECT"}, {"DOC_TYPE", "DRAWING"}, {"SPECIAL_CHAR", "NONE"}}) {
            shown(admin, gc[0], gc[1], "{\"useYn\":\"N\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("useYn")).andExpect(jsonPath("$.errors[0].code").value("CODE_FIXED"));
        }
        // 회사 확장이 아닌 그룹(WO_STATUS) → 404, 없는 코드 → 404
        shown(admin, "WO_STATUS", "RELEASED", "{\"useYn\":\"N\"}").andExpect(status().isNotFound());
        shown(admin, "PROC_CAT", "NOPE", "{\"useYn\":\"N\"}").andExpect(status().isNotFound());
        // 빈 값은 "보이기"로 읽지 않는다
        shown(admin, "PROC_CAT", "PRESS", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        shown(admin, "PROC_CAT", "PRESS", "{\"useYn\":\"X\"}").andExpect(status().isBadRequest());
        // 회사 코드는 이 길이 아니라 기존 수정(useYn)으로
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'PROC_CAT', 'ZTC', '회사', 't', 't')", comp);
        shown(admin, "PROC_CAT", "ZTC", "{\"useYn\":\"N\"}").andExpect(status().isNotFound());
        // 권한: 저장 권한 없는 사용자 → 403
        shown(worker, "PROC_CAT", "PRESS", "{\"useYn\":\"N\"}").andExpect(status().isForbidden());
    }

    @Test
    void 직접SQL_숨김표는_회사별이고_분기코드_비확장그룹은_트리거가_거부() {
        accounts.exec(comp, "insert into sys_comp_code_hide (comp_cd, grp_cd, code, created_by) values (?, 'PROC_CAT', 'WELD', 't')", comp);
        // B사 범위에서는 A사 숨김이 안 보이고, A사 회사코드로 넣으면 RLS 가 막는다
        assertThat(accounts.query(compB, "select count(*) from sys_comp_code_hide", Long.class)).isZero();
        assertThat(PgErrors.sqlState(org.assertj.core.api.Assertions.catchThrowable(() -> accounts.exec(compB,
                "insert into sys_comp_code_hide (comp_cd, grp_cd, code, created_by) values (?, 'PROC_CAT', 'PRESS', 't')", comp)))).contains("42501");
        assertThat(accounts.query(compB, "select count(*) from sys_comp_code_hide where grp_cd = 'PROC_CAT'", Long.class)).isZero();
        assertThat(PgErrors.constraint(org.assertj.core.api.Assertions.catchThrowable(() -> accounts.exec(comp,
                "insert into sys_comp_code_hide (comp_cd, grp_cd, code, created_by) values (?, 'PROC_CAT', 'INSPECT', 't')", comp))))
                .contains("ck_sys_comp_code_hide_fixed");
        assertThat(PgErrors.constraint(org.assertj.core.api.Assertions.catchThrowable(() -> accounts.exec(comp,
                "insert into sys_comp_code_hide (comp_cd, grp_cd, code, created_by) values (?, 'WO_STATUS', 'RELEASED', 't')", comp))))
                .contains("ck_sys_comp_code_hide_grp_ext");
    }

    @Test
    void 단위_코드_칸은_모두_30자_이상이다() {
        // docs/68 Q5 — UNIT 회사 코드는 30자까지라 단위 칸이 그보다 좁으면 그 품목의 등록이 22001 로 실패한다
        List<String> narrow = jdbc.queryForList("""
                select table_name || '.' || column_name from information_schema.columns
                 where table_schema = 'public' and column_name = 'unit_cd' and data_type = 'character varying'
                   and character_maximum_length < 30 order by 1""", String.class);
        assertThat(narrow).isEmpty();
    }
}
