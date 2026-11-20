package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * CMS 1부 C5 (docs/33 4.6) — 설비 보안 대장: 처음 저장 = 등록(동시 등록 409), 경고 날짜 경계(지원 종료·검사 기한·검토, 정책 주기),
 * 경고 필터, 엑셀(같은 조건·경고 글자), 사용 중지 설비, 직접 SQL, 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsEquipSecTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String S = "/api/cms/equip-secs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    LocalDate today;
    long e1;
    long e2;
    long e3;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SC");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "CMS", "cms-equip-sec", "SEARCH", "SAVE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'CNC', 'CNC', 't', 't') returning class_id",
                Long.class, comp);
        e1 = equip(comp, "CNC-01", cls);
        e2 = equip(comp, "CNC-02", cls);
        e3 = equip(comp, "CNC-03", cls);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private long equip(String c, String cd, long cls) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, cd, cd + " 가공기", cls);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions save(long equipId, String body) throws Exception {
        return mvc.perform(json(put(S + "/" + equipId), body).with(admin));
    }

    private String d(int daysAgo) {
        return today.minusDays(daysAgo).toString();
    }

    @Test
    void 처음저장은_등록_동시등록409_수정_경고_날짜경계와_정책주기_필터() throws Exception {
        mvc.perform(get(S).with(admin)).andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].warns[0]").value("UNREGISTERED")).andExpect(jsonPath("$.data[0].warnText").value("미등록"));
        // e1: 네트워크·검사 90일 전(= 기한 오늘)·지원 종료 오늘·검토 오늘 → 경고 없음
        save(e1, "{\"networkYn\":\"Y\",\"osNm\":\"Windows\",\"osVer\":\"10 IoT\",\"osEosDate\":\"" + d(0) + "\",\"lastScanDate\":\"" + d(90)
                + "\",\"scanTool\":\"V3\",\"targetSl\":\"SL2\",\"currentSl\":\"SL2\",\"reviewDate\":\"" + d(0) + "\"}").andExpect(status().isOk());
        save(e1, "{\"networkYn\":\"N\"}").andExpect(status().isConflict());   // rowVer 없이 또 등록
        mvc.perform(get(S + "/" + e1).with(admin)).andExpect(jsonPath("$.data.warns.length()").value(0))
                .andExpect(jsonPath("$.data.scanDueDate").value(d(0))).andExpect(jsonPath("$.data.rowVer").value(0));
        // e2: 하루씩 넘김 → 지원 종료·검사 기한·등급 미달·미검토
        save(e2, "{\"networkYn\":\"Y\",\"osEosDate\":\"" + d(1) + "\",\"lastScanDate\":\"" + d(91) + "\",\"targetSl\":\"SL2\",\"currentSl\":\"SL1\","
                + "\"reviewDate\":\"" + d(366) + "\"}").andExpect(status().isOk());
        mvc.perform(get(S + "/" + e2).with(admin))
                .andExpect(jsonPath("$.data.warns").value(containsInAnyOrder("EOS", "SCAN_OVERDUE", "SL_GAP", "UNREVIEWED")));
        // 오프라인으로 고치면 검사 경고 빠짐, 옛 rowVer 409
        save(e2, "{\"networkYn\":\"N\",\"osEosDate\":\"" + d(1) + "\",\"targetSl\":\"SL2\",\"currentSl\":\"SL1\",\"reviewDate\":\"" + d(366) + "\",\"rowVer\":0}")
                .andExpect(status().isOk());
        save(e2, "{\"networkYn\":\"N\",\"rowVer\":0}").andExpect(status().isConflict());
        mvc.perform(get(S + "/" + e2).with(admin)).andExpect(jsonPath("$.data.warns").value(containsInAnyOrder("EOS", "SL_GAP", "UNREVIEWED")));
        // 필터
        mvc.perform(get(S).param("warn", "EOS").with(admin)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(S).param("warn", "ANY").with(admin)).andExpect(jsonPath("$.data.length()").value(2));   // e2·e3(미등록)
        mvc.perform(get(S).param("warn", "UNREGISTERED").with(admin)).andExpect(jsonPath("$.data[0].equipCd").value("CNC-03"));
        mvc.perform(get(S).param("warn", "BOGUS").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(S).param("keyword", "windows").with(admin)).andExpect(jsonPath("$.data.length()").value(1));
        // 회사 정책: 검사 주기 30일 → e1(90일 전) 검사 기한 경과
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'CMS_SEC_SCAN_MAX_DAYS', '30', 't', 't')", comp);
        mvc.perform(get(S + "/" + e1).with(admin)).andExpect(jsonPath("$.data.warns[0]").value("SCAN_OVERDUE"))
                .andExpect(jsonPath("$.data.scanDueDate").value(d(60)));
    }

    @Test
    void 입력검증_미래날짜_도구만_등급코드_기타원격_사용중지설비() throws Exception {
        save(e1, "{\"networkYn\":\"X\",\"lastScanDate\":\"" + today.plusDays(1) + "\",\"targetSl\":\"SL9\",\"remoteAccess\":\"OTHER\","
                + "\"reviewDate\":\"2026-13-01\"}")
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("networkYn", "lastScanDate", "targetSl", "remoteText", "reviewDate")));
        save(e1, "{\"scanTool\":\"V3\"}").andExpect(jsonPath("$.errors[0].field").value("lastScanDate"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        save(e1, "{\"remoteAccess\":\"TEAMVIEWER\"}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        save(e1, "{\"remoteAccess\":\"OTHER\",\"remoteText\":\"원격 데스크톱(제조사 점검 때만)\"}").andExpect(status().isOk());
        // 사용 중지 설비: 미등록이면 목록에서 빠지고 새 등록 불가, 이미 등록된 것은 목록에 남고 고칠 수 있다
        accounts.exec(comp, "update mst_equip set use_yn = 'N' where equip_id in (?, ?)", e1, e2);
        mvc.perform(get(S).with(admin)).andExpect(jsonPath("$.data[*].equipCd").value(containsInAnyOrder("CNC-01", "CNC-03")));
        save(e2, "{\"networkYn\":\"N\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        save(e1, "{\"remoteAccess\":\"NONE\",\"rowVer\":0}").andExpect(status().isOk());
        save(999_999_999L, "{}").andExpect(status().isNotFound());
    }

    @Test
    void 엑셀은_같은조건_경고글자_권한() throws Exception {
        save(e1, "{\"networkYn\":\"Y\",\"osNm\":\"=cmd|' /C calc'!A0\",\"lastScanDate\":\"" + d(200) + "\",\"reviewDate\":\"" + d(0) + "\"}")
                .andExpect(status().isOk());
        byte[] x = mvc.perform(json(post(S + "/export"), "{\"warn\":\"ANY\"}").with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getLastRowNum()).isEqualTo(3);   // 머리 + 3대(e1 검사 경과, e2·e3 미등록)
            Row r1 = s.getRow(1);
            assertThat(r1.getCell(1).getStringCellValue()).isEqualTo("CNC-01");
            assertThat(r1.getCell(3).getStringCellValue()).isEqualTo("검사 기한 경과");
            assertThat(r1.getCell(5).getStringCellValue()).startsWith("'=");   // 수식 주입 방지
        }
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-equip-sec", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        mvc.perform(json(post(S + "/export"), "{}").with(vw)).andExpect(status().isForbidden());
        mvc.perform(json(put(S + "/" + e2), "{}").with(vw)).andExpect(status().isForbidden());
    }

    private java.util.List<String> gridCodes(String warn, String sort) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            var req = get(S + "/grid").param("size", "2").param("page", String.valueOf(page)).with(admin);
            if (warn != null) {
                req = req.param("warn", warn);
            }
            if (sort != null) {
                req = req.param("sort", sort.split(";"));
            }
            out.addAll(com.jayway.jsonpath.JsonPath.read(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(), "$.list[*].equipCd"));
        }
        return out;
    }

    /**
     * QA G-2 잔여(docs/33 13.18) — 경고 필터·계산 칸 정렬(메모리 경로)에서도 순서는 SQL(DB 정렬 규칙)이 준다: 기본 순서 = 같은 필터의 목록·엑셀,
     * 저장 칸(설비 이름) 정렬 = DB 순서. 이름에 U+FF5E 와 이모지를 넣어 시험 DB(바이트 비교)에서도 Java 문자열 비교(UTF-16)와 갈리게 한다
     */
    @Test
    void 경고필터_메모리경로도_기본순서는_목록엑셀과_같고_저장칸정렬은_DB순서() throws Exception {
        long cls = accounts.query(comp, "select class_id from mst_equip_class where class_cd = 'CNC'", Long.class);
        long x1 = equip(comp, "CNC-04", cls);
        long x2 = equip(comp, "CNC-05", cls);
        accounts.exec(comp, "update mst_equip set equip_nm = ? where equip_id = ?", "\uFF5E 프레스", x1);
        accounts.exec(comp, "update mst_equip set equip_nm = ? where equip_id = ?", "\uD83D\uDE00 프레스", x2);
        // 기본 순서: grid(경고 필터, 쪽 이어 붙이기) = 목록(같은 필터) = 엑셀(같은 필터)
        java.util.List<String> list = com.jayway.jsonpath.JsonPath.read(mvc.perform(get(S).param("warn", "ANY").with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data[*].equipCd");
        assertThat(list).hasSize(5);
        assertThat(gridCodes("ANY", null)).containsExactlyElementsOf(list);
        byte[] x = mvc.perform(json(post(S + "/export"), "{\"warn\":\"ANY\"}").with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        java.util.List<String> excel = new java.util.ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet sh = wb.getSheetAt(0);
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                excel.add(sh.getRow(i).getCell(1).getStringCellValue());
            }
        }
        assertThat(excel).containsExactlyElementsOf(list);
        // 저장 칸(설비 이름) 정렬 + 경고 필터 = DB 순서. Java 비교(UTF-16)라면 이모지가 U+FF5E 앞으로 온다 — 이 자료는 둘이 갈린다
        String db = accounts.query(comp, "select string_agg(equip_cd, ',' order by equip_nm, equip_id) from mst_equip", String.class);
        String pairs = accounts.query(comp, "select string_agg(equip_cd || '|' || equip_nm, ',' order by equip_id) from mst_equip", String.class);
        java.util.List<String> javaOrder = java.util.Arrays.stream(pairs.split(",")).sorted(java.util.Comparator.comparing(v -> v.substring(v.indexOf('|') + 1)))
                .map(v -> v.substring(0, v.indexOf('|'))).toList();
        assertThat(String.join(",", javaOrder)).as("시험 자료가 DB 순서와 Java 순서를 가른다").isNotEqualTo(db);
        assertThat(gridCodes("ANY", "equipNm,asc")).containsExactly(db.split(","));
        // 계산 칸(경고 글자 — 모두 같은 "미등록") 먼저, 같은 값은 다음 저장 칸(이름)의 DB 순서
        assertThat(gridCodes(null, "warnText,asc;equipNm,asc")).containsExactly(db.split(","));
    }

    /** docs/40 10.2 — 표 → hb.grid: 목록과 같은 행·기본 순서, 쪽 경계·전체 건수, 계산 값(경고·등급 글자) 정렬과 빈 값 맨 뒤, 경고 필터, 열, 권한, B사로 A사 */
    @Test
    void grid는_목록과_같은행_쪽경계_계산값정렬_필터_열_권한_B사로_A사() throws Exception {
        save(e1, "{\"networkYn\":\"N\",\"targetSl\":\"SL2\",\"currentSl\":\"SL2\",\"reviewDate\":\"" + d(0) + "\"}").andExpect(status().isOk());
        save(e2, "{\"networkYn\":\"N\",\"targetSl\":\"SL3\",\"currentSl\":\"SL1\",\"reviewDate\":\"" + d(0) + "\"}").andExpect(status().isOk());
        // 기본 순서 = 목록(공장·설비 코드), 쪽 경계와 전체 건수
        mvc.perform(get(S + "/grid").param("size", "2").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(2)).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[0].equipCd").value("CNC-01")).andExpect(jsonPath("$.list[1].equipCd").value("CNC-02"))
                .andExpect(jsonPath("$.list[1].levelText").value("SL1 / SL3"));
        mvc.perform(get(S + "/grid").param("size", "2").param("page", "2").with(admin))
                .andExpect(jsonPath("$.list.length()").value(1)).andExpect(jsonPath("$.list[0].equipCd").value("CNC-03"))
                .andExpect(jsonPath("$.list[0].warns[0]").value("UNREGISTERED"));
        mvc.perform(get(S + "/grid").param("page", "9").with(admin)).andExpect(jsonPath("$.list.length()").value(0))
                .andExpect(jsonPath("$.total").value(3));
        // 계산 값 정렬: 경고 글자 — 경고 없는 e1 은 방향과 관계없이 맨 뒤
        mvc.perform(get(S + "/grid").param("sort", "warnText,asc").with(admin))
                .andExpect(jsonPath("$.list[2].equipCd").value("CNC-01"));
        mvc.perform(get(S + "/grid").param("sort", "warnText,desc").with(admin))
                .andExpect(jsonPath("$.list[2].equipCd").value("CNC-01"));
        mvc.perform(get(S + "/grid").param("sort", "levelText,desc").with(admin))
                .andExpect(jsonPath("$.list[0].equipCd").value("CNC-01")).andExpect(jsonPath("$.list[2].equipCd").value("CNC-03"));
        // 허용 밖 정렬·잘못된 필터는 400, 필터는 목록과 같은 결과
        mvc.perform(get(S + "/grid").param("sort", "remark,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(S + "/grid").param("warn", "BOGUS").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(S + "/grid").param("warn", "SL_GAP").with(admin)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].equipCd").value("CNC-02"));
        mvc.perform(get(S + "/grid").param("total", "false").with(admin)).andExpect(jsonPath("$.total").doesNotExist());
        mvc.perform(get(S + "/columns").with(admin)).andExpect(jsonPath("$.list.length()").value(9))
                .andExpect(jsonPath("$.list[8].field").value("warnText"));
        // 권한: SEARCH 없으면 403
        long viewer = accounts.newUser(comp, "nosearch", PW);
        accounts.grant(comp, viewer, "CMS2", "cms-equip-sec", "SAVE");
        RequestPostProcessor ns = accounts.as(comp, viewer, "nosearch");
        mvc.perform(get(S + "/grid").with(ns)).andExpect(status().isForbidden());
        mvc.perform(get(S + "/columns").with(ns)).andExpect(status().isForbidden());
        // B사로 A사: 같은 이름 설비가 있어도 B사 grid 에는 B사 것만
        String other = accounts.newComp("SE");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-equip-sec", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        mvc.perform(get(S + "/grid").with(b)).andExpect(jsonPath("$.list.length()").value(0)).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void 직접SQL_고정칸_삭제_칸규칙_그리고_B사로_A사() throws Exception {
        save(e1, "{\"networkYn\":\"Y\"}").andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_equip_sec set equip_id = ? where equip_id = ?", e2, e1))))
                .contains("ck_cms_equip_sec_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_equip_sec where equip_id = ?", e1)))).contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_equip_sec set remote_access = 'OTHER' where equip_id = ?", e1))))
                .contains("ck_cms_equip_sec_remote");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_equip_sec set scan_tool = 'V3' where equip_id = ?", e1))))
                .contains("ck_cms_equip_sec_scan");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_equip_sec set current_sl = 'SL4' where equip_id = ?", e1))))
                .contains("ck_cms_equip_sec_sl");
        // B사
        String other = accounts.newComp("SD");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-equip-sec", "SEARCH", "SAVE", "EXCEL");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        mvc.perform(get(S).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(S + "/" + e1).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(S + "/" + e2), "{\"networkYn\":\"N\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(S + "/" + e1), "{\"networkYn\":\"N\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        byte[] x = mvc.perform(json(post(S + "/export"), "{}").with(b)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isZero();
        }
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_equip_sec (comp_cd, equip_id, created_by, updated_by) values (?, ?, 't', 't')""", other, e2))))
                .contains("fk_cms_equip_sec_equip_comp");
        assertThat(accounts.exec(other, "update cms_equip_sec set network_yn = 'N'")).isZero();
        assertThat(accounts.query(comp, "select network_yn from cms_equip_sec where equip_id = ?", String.class, e1)).isEqualTo("Y");
    }
}
