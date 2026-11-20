package io.smarthappy.platform.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 코드→글자 칸 (docs/36 18.9 X2·X4) — LABEL 칸은 화면에 내려주는 표(toView)와 엑셀 글자가 같다(ko·en), 쉼표 값은 나눠 바꾸고
 * 표에 없는 값은 그대로. CODE 칸 표시 이름표는 사용 중지된 코드도 담는다(엑셀·이름 API), 선택 목록(codes)은 사용 중만
 */
@AutoConfigureMockMvc
class ColumnLabelsExportTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ExcelExportService excel;

    @Autowired
    CodeService codes;

    @Autowired
    MessageSource messages;

    @Autowired
    MockMvc mvc;

    /** 소유자 계정 — 시스템 코드(sys_code)를 넣고 지우는 시험용(앱 계정은 시스템 코드를 못 바꾼다) */
    org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(jdbcUrl(), OWNER_USER, testPassword()));

    String comp;
    long userId;

    /** LABEL 은 이미 있는 문구 키 yn.Y·yn.N 으로(ko 사용/미사용, en In use/Not in use) */
    static final ColumnSpec FLAG = ColumnSpec.labels("flag", "yn.Y", "yn.", 100, "Y", "N");
    static final ColumnSpec UNIT = ColumnSpec.code("unit", "yn.Y", "UNIT", 80);

    public static class Line {
        private final String flag;
        private final String unit;

        Line(String flag, String unit) {
            this.flag = flag;
            this.unit = unit;
        }

        public String getFlag() {
            return flag;
        }

        public String getUnit() {
            return unit;
        }
    }

    static final List<Line> LINES = List.of(new Line("Y", "EA"), new Line("Y,N", "PAIL"), new Line("N, Z", "GONE"),
            new Line("Z", null));

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("LBL");
        userId = accounts.newUser(comp, "u1", "Quiet-river-77");
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, sort_no, use_yn, created_by, updated_by)
                values (?, 'UNIT', 'PAIL', '페일', 98, 'N', 't', 't')""", comp);   // 사용 중지된 회사 코드
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private List<List<String>> export(Locale locale) throws Exception {
        LocaleContextHolder.setLocale(locale);
        MockHttpServletResponse res = new MockHttpServletResponse();
        runner.run(comp, () -> {
            try {
                excel.export(res, new ExportJob("sys-my-info", "yn.Y", List.of(FLAG, UNIT), LINES.size(), List.of()),
                        w -> LINES.forEach(w));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        List<List<String>> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(res.getContentAsByteArray()))) {
            for (int r = 1; r <= LINES.size(); r++) {
                Row row = wb.getSheetAt(0).getRow(r);
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < 2; c++) {
                    cells.add(row.getCell(c) == null ? null : row.getCell(c).getStringCellValue());
                }
                out.add(cells);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> viewLabels(Locale locale) {
        return (Map<String, String>) FLAG.toView(messages, locale).get("labels");
    }

    @Test
    void LABEL_은_화면_표와_엑셀_글자가_같다_ko_en_쉼표_모르는_값() throws Exception {
        for (Locale locale : List.of(Locale.KOREAN, Locale.ENGLISH)) {
            Map<String, String> view = viewLabels(locale);
            List<List<String>> rows = export(locale);
            for (int i = 0; i < LINES.size(); i++) {
                // 화면 hb.fmt.label 과 같은 규칙(서버 labelText) — 화면 표로 만든 글자 == 엑셀 칸
                assertThat(rows.get(i).get(0)).as("%s %s", locale, LINES.get(i).getFlag())
                        .isEqualTo(ColumnSpec.labelText(view, LINES.get(i).getFlag()));
            }
        }
        assertThat(viewLabels(Locale.KOREAN)).containsExactly(Map.entry("Y", "사용"), Map.entry("N", "미사용"));
        assertThat(viewLabels(Locale.ENGLISH)).containsEntry("Y", "In use");
        List<List<String>> ko = export(Locale.KOREAN);
        assertThat(ko.get(0).get(0)).isEqualTo("사용");
        assertThat(ko.get(1).get(0)).isEqualTo("사용, 미사용");          // 쉼표 값은 나눠 각각
        assertThat(ko.get(2).get(0)).isEqualTo("미사용, Z");             // 공백 정리, 표에 없는 값은 그대로
        assertThat(ko.get(3).get(0)).isEqualTo("Z");
        assertThat(export(Locale.ENGLISH).get(1).get(0)).isEqualTo("In use, Not in use");
    }

    @Test
    void CODE_표시_이름표는_사용_중지된_코드도_담고_선택_목록은_사용_중만() throws Exception {
        List<List<String>> ko = export(Locale.KOREAN);
        assertThat(ko.get(0).get(1)).isNotEqualTo("EA");                 // 시스템 코드 이름
        assertThat(ko.get(1).get(1)).isEqualTo("페일");                  // 사용 중지된 회사 코드 — 예전엔 날 코드 PAIL
        assertThat(ko.get(2).get(1)).isEqualTo("GONE");                  // 없는 코드는 그대로
        assertThat(runner.call(comp, () -> codes.names("UNIT", Locale.KOREAN))).containsEntry("PAIL", "페일");
        assertThat(runner.call(comp, () -> codes.codes("UNIT", Locale.KOREAN))).noneMatch(c -> "PAIL".equals(c.code()));
        assertThat(runner.call(comp, () -> codes.names("NO_SUCH_GRP", Locale.KOREAN))).isEmpty();
        assertThat(runner.call(comp, () -> codes.names("bad grp!", Locale.KOREAN))).isEmpty();
        // 화면 API — 이름표(사용 중지 포함)와 선택 목록(사용 중만)
        mvc.perform(get("/api/platform/codes/names").param("grp", "UNIT").with(accounts.as(comp, userId, "u1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.PAIL").value("페일"));
        mvc.perform(get("/api/platform/codes").param("grp", "UNIT").with(accounts.as(comp, userId, "u1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].code", Matchers.not(Matchers.hasItem("PAIL"))));
    }

    @Test
    void 같은_코드가_시스템_회사_둘_다면_sort_no_가_달라도_시스템_이름_한_번() {
        // 회사가 먼저 만든 코드(sort_no 1)와 같은 시스템 코드(sort_no 99)가 나중에 생긴 경우 — 회사 쪽 트리거는 이 순서를 못 막는다
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, sort_no, created_by, updated_by)
                values (?, 'UNIT', 'ZZDUP', '회사 이름', 1, 't', 't')""", comp);
        jdbc.update("insert into sys_code (grp_cd, code, code_nm_key, sort_no) values ('UNIT', 'ZZDUP', 'code.UNIT.EA', 99)");
        try {
            String sysName = messages.getMessage("code.UNIT.EA", null, Locale.KOREAN);
            assertThat(runner.call(comp, () -> codes.names("UNIT", Locale.KOREAN))).containsEntry("ZZDUP", sysName);
            List<CodeService.CodeItem> list = runner.call(comp, () -> codes.codes("UNIT", Locale.KOREAN));
            assertThat(list).filteredOn(c -> "ZZDUP".equals(c.code())).singleElement()
                    .satisfies(c -> {
                        assertThat(c.system()).isTrue();
                        assertThat(c.name()).isEqualTo(sysName);
                    });
        } finally {
            jdbc.update("delete from sys_code where grp_cd = 'UNIT' and code = 'ZZDUP'");
        }
    }

    @Test
    void A사로_B사_사용_중지_코드는_안_보인다() {
        String other = accounts.newComp("LBB");
        assertThat(runner.call(other, () -> codes.names("UNIT", Locale.KOREAN))).doesNotContainKey("PAIL");
    }

    @Test
    void LABEL_정의_검사_접두어_값_형식() {
        assertThatThrownBy(() -> ColumnSpec.labels("f", "t", "no.dot", 80, "A")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColumnSpec.labels("f", "t", "a.", 80)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColumnSpec.labels("f", "t", "a.", 80, "A,B")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColumnSpec.labels("f", "t", "a.", 80, (String) null)).isInstanceOf(Exception.class);
        // with… 를 거쳐도 LABEL 정보가 남는다
        ColumnSpec c = FLAG.withPriority(1).withSort().withFlex().noExport();
        assertThat(c.type()).isEqualTo(ColumnSpec.ColumnType.LABEL);
        assertThat(c.labelValues()).containsExactly("Y", "N");
        assertThat(ColumnSpec.labelText(Map.of("A", "에이"), null)).isNull();
        assertThat(ColumnSpec.labelText(Map.of("A", "에이"), " , A ,, ")).isEqualTo("에이");
    }
}
