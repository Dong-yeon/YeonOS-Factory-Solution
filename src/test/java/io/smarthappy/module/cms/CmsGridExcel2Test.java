package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
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

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.cms.link.AlarmSink;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C21 grid 엑셀 통일 2묶음 (docs/33 13.8) — 카운터·알람 이력·상태 검토 grid 메뉴 엑셀: grid 와 같은 조건·정렬·보이는 열(요청 순서),
 * 화면이 꾸미는 칸은 사람이 읽는 열(누적 + 단위 칸, 수명 이름, 반복 표시, 시각 = 공장 현지 글자), 허용 목록 밖 열·정렬 400,
 * 공장·기간 필수(알람·검토), EXCEL 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsGridExcel2Test extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String C = "/api/cms/counters";
    private static final String A = "/api/cms/alarms";
    private static final String V = "/api/cms/state-review";
    /** 2026-03-02 (Asia/Seoul) */
    private static final String DAY = "2026-03-02";
    private static final String RANGE = "\"plantCd\":\"P1\",\"from\":\"" + DAY + "\",\"to\":\"" + DAY + "\"";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    AlarmSink sink;

    String comp;
    RequestPostProcessor admin;
    long prs1;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("GX");
        long u = accounts.newUser(comp, "admin1", PW);
        grant(comp, u);
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        prs1 = accounts.query(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', '프레스', ?, 't', 't') returning equip_id",
                Long.class, comp, cls);
    }

    private void grant(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-counter", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "CMS", "cms-alarm", "SEARCH", "EXCEL");
        accounts.grant(c, u, "CMS", "cms-alarm-def", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-state-review", "SEARCH", "SAVE", "EXCEL");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long create(String url, String body) throws Exception {
        String s = mvc.perform(json(post(url), body).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private ResultActions export(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url + "/export"), body).with(who));
    }

    private List<List<String>> sheet(RequestPostProcessor who, String url, String body) throws Exception {
        byte[] x = export(who, url, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<List<String>> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet s = wb.getSheetAt(0);
            int width = s.getRow(0).getLastCellNum();
            for (int i = 0; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                List<String> cells = new ArrayList<>();
                for (int j = 0; j < width; j++) {
                    Cell c = r.getCell(j);
                    cells.add(c == null ? "" : c.getCellType() == CellType.NUMERIC ? String.valueOf((long) c.getNumericCellValue()) : c.getStringCellValue());
                }
                out.add(cells);
            }
        }
        return out;
    }

    private void interval(String cat, String start, String end) {
        accounts.exec(comp, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, reason_id, start_at, end_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, null, ?::timestamptz, ?::timestamptz, 'AUTO', ?::timestamptz, 'AUTO', 'col')""", comp, prs1, cat,
                DAY + "T" + start + ":00+09:00", end == null ? null : DAY + "T" + end + ":00+09:00", DAY + "T" + start + ":00+09:00");
    }

    private void alarm(String cd, String at) {
        Instant t = OffsetDateTime.parse(DAY + "T" + at + ":00+09:00").toInstant();
        runner.call(comp, () -> {
            sink.raise(prs1, cd, null, t, null, "ev-" + t.toEpochMilli() + "-" + cd);
            return null;
        });
    }

    @Test
    void 카운터_grid엑셀은_누적과_단위칸_수명이름_정렬_보이는열() throws Exception {
        accounts.exec(comp, "insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, life_shots, created_by, updated_by) values (?, 'M-100', 'MOLD', 'M-100', 100000, 't', 't')",
                comp);
        long shot = create(C, "{\"counterCd\":\"M100-SHOT\",\"counterNm\":\"금형 타수\",\"targetType\":\"ASSET\",\"targetCd\":\"M-100\",\"counterKind\":\"SHOTS\",\"scale\":0,\"unitNm\":\"타\"}");
        create(C, "{\"counterCd\":\"PRS-HR\",\"counterNm\":\"가동 시간\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"counterKind\":\"RUN_HOURS\",\"scale\":1}");
        mvc.perform(json(post(C + "/" + shot + "/readings"), "{\"deltaQty\":\"1200\"}").with(admin)).andExpect(status().isOk());
        List<List<String>> s = sheet(admin, C, "{\"sort\":[\"counterCd,desc\"],\"columns\":[\"counterCd\",\"totalText\",\"life\"]}");
        assertThat(s.get(0)).containsExactly("카운터 코드", "누적", "단위", "수명");
        assertThat(s.get(1)).containsExactly("PRS-HR", "0.0", "", "");                     // 수명 없음 = 빈 칸
        assertThat(s.get(2)).containsExactly("M100-SHOT", "1200", "타", "수명 100000타 이내"); // 화면과 같은 글자(자릿수 구분 없음)
        assertThat(sheet(admin, C, "{\"targetType\":\"ASSET\",\"columns\":[\"counterCd\"]}")).hasSize(2);
        export(admin, C, "{\"columns\":[\"unitNm\"]}").andExpect(status().isBadRequest());        // 엑셀 전용 이름
        export(admin, C, "{\"columns\":[\"lifeNm\"]}").andExpect(status().isBadRequest());
        export(admin, C, "{\"sort\":[\"life,asc\"]}").andExpect(status().isBadRequest());         // 계산 값은 정렬 밖
        export(admin, C, "{\"targetType\":\"NOPE\"}").andExpect(status().isBadRequest());
    }

    @Test
    void 알람이력_grid엑셀은_현지시각_반복표시_공장기간필수() throws Exception {
        create("/api/cms/alarm-defs", "{\"alarmCd\":\"E-A\",\"alarmNm\":\"유압 저하\",\"severity\":\"WARN\",\"autoBmYn\":\"N\"}");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'CMS_ALARM_REPEAT_COUNT', '2', 't', 't')", comp);
        alarm("E-A", "09:00");
        alarm("E-A", "09:30");
        List<List<String>> s = sheet(admin, A, "{" + RANGE + ",\"sort\":[\"raisedAt,asc\"],\"columns\":[\"raisedAt\",\"alarmCd\",\"repeat\",\"clearedAt\"]}");
        assertThat(s.get(0)).containsExactly("발생", "알람 코드", "반복", "해제");
        assertThat(s).hasSize(3);
        assertThat(s.get(1).get(0)).isEqualTo(DAY + " 09:00");                                    // 공장 현지 시각 글자
        assertThat(s.get(2)).containsExactly(DAY + " 09:30", "E-A", "반복", "");                   // 정책 창 안 두 번째 = 반복, 열림 = 빈 칸
        assertThat(sheet(admin, A, "{" + RANGE + ",\"openOnly\":\"Y\",\"columns\":[\"alarmCd\"]}")).hasSize(3);
        export(admin, A, "{\"columns\":[\"alarmCd\"]}").andExpect(status().isBadRequest());       // 공장·기간 필수(grid 와 같다)
        export(admin, A, "{" + RANGE + ",\"columns\":[\"repeatNm\"]}").andExpect(status().isBadRequest());
        export(admin, A, "{" + RANGE + ",\"sort\":[\"minutes,desc\"]}").andExpect(status().isBadRequest());
    }

    @Test
    void 상태검토_grid엑셀은_현지시각_조건_정렬() throws Exception {
        interval("UDT", "09:00", "09:10");
        interval("PRD", "09:10", "09:40");
        interval("UDT", "09:40", null);
        List<List<String>> s = sheet(admin, V, "{" + RANGE + ",\"sort\":[\"startAt,asc\"],\"columns\":[\"startAt\",\"endAt\",\"effCat\"]}");
        assertThat(s).hasSize(4);
        assertThat(s.get(1).subList(0, 2)).containsExactly(DAY + " 09:00", DAY + " 09:10");
        assertThat(s.get(3).get(1)).isEmpty();                                                     // 열린 구간
        assertThat(sheet(admin, V, "{" + RANGE + ",\"pending\":\"Y\",\"columns\":[\"startAt\"]}")).hasSize(3);  // 사유 없는 정지 2
        assertThat(sheet(admin, V, "{" + RANGE + ",\"effCat\":\"PRD\"}")).hasSize(2);
        export(admin, V, "{\"from\":\"" + DAY + "\",\"to\":\"" + DAY + "\"}").andExpect(status().isBadRequest());   // 공장 필수
        export(admin, V, "{" + RANGE + ",\"columns\":[\"startAtText\"]}").andExpect(status().isBadRequest());
        export(admin, V, "{" + RANGE + ",\"sort\":[\"minutes,desc\"]}").andExpect(status().isBadRequest());
    }

    @Test
    void EXCEL_권한없으면_403_B사는_A사_행을_못받는다() throws Exception {
        create(C, "{\"counterCd\":\"PRS-HR\",\"counterNm\":\"가동 시간\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"counterKind\":\"RUN_HOURS\",\"scale\":1}");
        create("/api/cms/alarm-defs", "{\"alarmCd\":\"E-A\",\"alarmNm\":\"유압 저하\",\"severity\":\"WARN\",\"autoBmYn\":\"N\"}");
        alarm("E-A", "09:00");
        interval("UDT", "09:00", "09:10");
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-counter", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "cms-alarm", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "cms-state-review", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        export(vw, C, "{}").andExpect(status().isForbidden());
        export(vw, A, "{" + RANGE + "}").andExpect(status().isForbidden());
        export(vw, V, "{" + RANGE + "}").andExpect(status().isForbidden());
        // B사: 같은 공장 코드·같은 날이어도 머리만
        String other = accounts.newComp("GY");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        accounts.addPlant(other, "P1", "1공장");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        assertThat(sheet(b, C, "{}")).hasSize(1);
        assertThat(sheet(b, A, "{" + RANGE + "}")).hasSize(1);
        assertThat(sheet(b, V, "{" + RANGE + "}")).hasSize(1);
        assertThat(sheet(admin, C, "{}")).hasSize(2);                                              // A사는 자기 행
    }
}
