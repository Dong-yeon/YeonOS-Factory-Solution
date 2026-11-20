package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
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

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C16 기준정보 엑셀 3종 (docs/33 13장) — 고장 코드·설비 프로필·예방점검 계획 엑셀: grid 와 같은 조건·정렬, 보이는 열만(요청 순서),
 * 화면이 합쳐 보이는 칸은 사람이 읽는 열로 나눔(코드 이름·예/아니오·날짜 셀), 허용 목록 밖 열·정렬 400, EXCEL 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsMasterExcelTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String F = "/api/cms/fail-codes";
    private static final String E = "/api/cms/equip-profiles";
    private static final String P = "/api/cms/pm-plans";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    LocalDate today;
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
        for (String e : List.of("PRS-02", "PRS-01")) {
            accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', ?, ?, ?, 't', 't')",
                    comp, e, "프레스 " + e, cls);
        }
        prs1 = accounts.query(comp, "select equip_id from mst_equip where equip_cd = 'PRS-01'", Long.class);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        save(F, "{\"codeType\":\"CAUSE\",\"failCd\":\"WEAR\",\"failNm\":\"마모\",\"failCat\":\"MECH\"}");
        save(F, "{\"codeType\":\"SYMPTOM\",\"failCd\":\"LEAK\",\"failNm\":\"누유\"}");
        save(F, "{\"codeType\":\"SYMPTOM\",\"failCd\":\"NOISE\",\"failNm\":\"=HYPERLINK(\\\"x\\\")\"}");   // 수식 주입 시도
        mvc.perform(json(put(E + "/" + prs1), "{\"criticality\":\"A\",\"keyEquipYn\":\"Y\"}").with(admin)).andExpect(status().isOk());
        long tmpl = id(save("/api/cms/chk-tmpls", "{\"tmplCd\":\"PRS-D\",\"tmplNm\":\"프레스 일상\"}"));
        mvc.perform(json(put("/api/cms/chk-tmpls/" + tmpl + "/items?rowVer=0"), "[{\"seq\":1,\"itemNm\":\"클러치 소음\",\"judgeType\":\"OKNG\"}]").with(admin))
                .andExpect(status().isOk());
        // 계획 2: PM-A 는 30일 뒤(예정), PM-B 는 오늘(할 때)
        plan("PM-A", "PRS-01", today.plusDays(30));
        plan("PM-B", "PRS-02", today);
    }

    private void grant(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-fail-code", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "CMS", "cms-equip-profile", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "CMS", "cms-chk-tmpl", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-pm-plan", "SEARCH", "SAVE", "EXCEL");
    }

    private void plan(String cd, String target, LocalDate anchor) throws Exception {
        save(P, "{\"planCd\":\"" + cd + "\",\"planNm\":\"" + cd + " 점검\",\"targetCd\":\"" + target + "\",\"tmplCd\":\"PRS-D\",\"execMode\":\"CHECK\","
                + "\"calUnit\":\"MONTH\",\"calInterval\":1,\"scheduleBasis\":\"FIXED\",\"anchorDate\":\"" + anchor + "\"}");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions save(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin)).andExpect(status().isOk());
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    private ResultActions export(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url + "/export"), body).with(who));
    }

    /** 첫 시트 → 행별 글자(날짜 셀은 LocalDate 글자) */
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
                    cells.add(c == null ? "" : c.getCellType() == CellType.NUMERIC
                            ? (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(c) ? c.getLocalDateTimeCellValue().toLocalDate().toString()
                                    : String.valueOf((long) c.getNumericCellValue()))
                            : c.getStringCellValue());
                }
                out.add(cells);
            }
        }
        return out;
    }

    @Test
    void 고장코드_엑셀은_grid_조건_정렬_보이는열_순서_코드이름() throws Exception {
        List<List<String>> s = sheet(admin, F, "{\"codeType\":\"SYMPTOM\",\"sort\":[\"failCd,desc\"],\"columns\":[\"failCd\",\"codeType\",\"failNm\",\"useYn\"]}");
        assertThat(s.get(0)).containsExactly("코드", "유형", "이름", "사용");
        assertThat(s).hasSize(3);                                                   // 머리 + 현상 2
        assertThat(s.get(1).subList(0, 2)).containsExactly("NOISE", "현상");         // 정렬 = 코드 내림, 유형 = 코드 이름
        assertThat(s.get(1).get(2)).startsWith("'=");                               // 수식 주입 방지
        assertThat(s.get(1).get(3)).isEqualTo("사용");
        assertThat(s.get(2).get(0)).isEqualTo("LEAK");
        // 열 지정 없으면 grid 전체 열, 본문 없어도 기본 조건(전체 3)
        assertThat(sheet(admin, F, "{}").get(0)).hasSize(7);
        export(admin, F, "").andExpect(status().isOk());
        assertThat(sheet(admin, F, "{\"keyword\":\"wea\"}")).hasSize(2);
    }

    @Test
    void 설비프로필_엑셀_핵심설비는_예_아니오_핵심만_조건() throws Exception {
        List<List<String>> s = sheet(admin, E, "{\"columns\":[\"equipCd\",\"criticality\",\"keyEquipYn\"]}");
        assertThat(s.get(0)).containsExactly("설비 코드", "중요도", "핵심 설비");
        assertThat(s.get(1)).containsExactly("PRS-01", "A", "예");                   // 기본 정렬 = 공장 → 설비
        assertThat(s.get(2)).containsExactly("PRS-02", "C", "아니오");               // 프로필 없음 = C·아니오(목록과 같다)
        assertThat(sheet(admin, E, "{\"keyOnly\":\"Y\"}")).hasSize(2);
        assertThat(sheet(admin, E, "{\"sort\":[\"equipCd,desc\"],\"columns\":[\"equipCd\"]}").get(1)).containsExactly("PRS-02");
    }

    @Test
    void 계획_엑셀은_주기_다음예정_상태를_사람이_읽는_열로_나눈다() throws Exception {
        List<List<String>> s = sheet(admin, P, "{\"columns\":[\"planCd\",\"nextDueText\",\"state\",\"cycleType\"]}");
        assertThat(s.get(0)).containsExactly("계획 코드", "다음 예정일", "다음 예정 사용량(누적)", "지금 누적(사용량)", "상태", "주기 종류", "간격", "단위", "기준", "카운터",
                "사용량 간격", "사용량 단위");
        // 기본 정렬 = 다음 예정 빠른 순 → PM-B(오늘, 할 때) · PM-A(30일 뒤, 예정)
        assertThat(s.get(1)).containsExactly("PM-B", today.toString(), "", "", "할 때", "달력", "1", "개월", "달력 고정", "", "", "");
        assertThat(s.get(2).subList(0, 5)).containsExactly("PM-A", today.plusDays(30).toString(), "", "", "예정");
        assertThat(sheet(admin, P, "{\"sort\":[\"planCd,asc\"],\"columns\":[\"planCd\"]}").get(1)).containsExactly("PM-A");
        assertThat(sheet(admin, P, "{\"keyword\":\"pm-a\"}")).hasSize(2);
    }

    @Test
    void 허용목록_밖_열_정렬_조건은_400() throws Exception {
        export(admin, F, "{\"columns\":[\"remark\"]}").andExpect(status().isBadRequest());               // grid 열에 없는 칸
        export(admin, F, "{\"columns\":[\"failCd\",\"failCd\"]}").andExpect(status().isBadRequest());    // 중복
        export(admin, F, "{\"sort\":[\"remark,asc\"]}").andExpect(status().isBadRequest());
        export(admin, F, "{\"codeType\":\"NOPE\"}").andExpect(status().isBadRequest());
        export(admin, E, "{\"columns\":[\"keyEquipNm\"]}").andExpect(status().isBadRequest());           // 엑셀 전용 이름은 직접 못 고른다
        export(admin, E, "{\"criticality\":\"Z\"}").andExpect(status().isBadRequest());
        export(admin, P, "{\"columns\":[\"stateNm\"]}").andExpect(status().isBadRequest());
        export(admin, P, "{\"sort\":[\"state,asc\"]}").andExpect(status().isBadRequest());               // 계산 값은 정렬 밖
        export(admin, P, "{\"useYn\":\"X\"}").andExpect(status().isBadRequest());
    }

    @Test
    void EXCEL_권한_없으면_403_B사는_A사_행을_못받는다() throws Exception {
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-fail-code", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "cms-equip-profile", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "cms-pm-plan", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        export(vw, F, "{}").andExpect(status().isForbidden());
        export(vw, E, "{}").andExpect(status().isForbidden());
        export(vw, P, "{}").andExpect(status().isForbidden());
        String other = accounts.newComp("GY");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        accounts.addPlant(other, "P1", "1공장");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        assertThat(sheet(b, F, "{}")).hasSize(1);                                   // 머리만
        assertThat(sheet(b, E, "{\"plantCd\":\"P1\"}")).hasSize(1);
        assertThat(sheet(b, P, "{}")).hasSize(1);
    }
}
