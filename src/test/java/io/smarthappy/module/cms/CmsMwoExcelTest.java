package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C14 (docs/33 13장) — 보전 작업지시 엑셀: 목록과 같은 조건·같은 행, 코드는 이름, 시각은 대상 공장 현지, 닫을 때 기록·정지 분·공수 분.
 * 권한(EXCEL), 기간 상한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsMwoExcelTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("MX");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-breakdown", "SEARCH", "SAVE");
        accounts.grant(comp, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', '프레스', ?, 't', 't')",
                comp, cls);
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
    }

    private ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        Object v = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.mwoId")).longValue();
    }

    private int ver(long mwo) {
        return accounts.query(comp, "select row_ver from cms_mwo where mwo_id = ?", Integer.class, mwo);
    }

    /** 머리 줄 제외, 행마다 칸 글자(숫자는 정수 글자) */
    private List<List<String>> rows(RequestPostProcessor who, String body) throws Exception {
        byte[] x = postJson(who, M + "/export", body).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<List<String>> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet sh = wb.getSheetAt(0);
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                Row r = sh.getRow(i);
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < MwoService.COLUMNS.size(); c++) {
                    Cell cell = r.getCell(c);
                    cells.add(cell == null ? "" : switch (cell.getCellType()) {
                        case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                        default -> cell.getStringCellValue();
                    });
                }
                out.add(cells);
            }
        }
        return out;
    }

    private int col(String field) {
        for (int i = 0; i < MwoService.COLUMNS.size(); i++) {
            if (MwoService.COLUMNS.get(i).field().equals(field)) {
                return i;
            }
        }
        throw new IllegalArgumentException(field);
    }

    @Test
    void 목록과_같은_조건_코드는_이름_시각은_공장현지_닫을때_기록과_정지분_공수분() throws Exception {
        String day = LocalDate.parse(today).minusDays(1).toString();   // 오전 시각을 써도 시험 시각과 무관하게(미래 시각 거부 회피)
        long cm = id(postJson(admin, M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"베어링 교체\",\"priority\":2,\"reportedAt\":\"" + day + "T07:50\","
                + "\"downYn\":\"Y\",\"downStartAt\":\"" + day + "T08:00\",\"downEndAt\":\"" + day + "T08:45\"}"));
        postJson(admin, M + "/" + cm + "/start", "{\"rowVer\":" + ver(cm) + ",\"startedAt\":\"" + day + "T08:05\"}").andExpect(status().isOk());
        postJson(admin, M + "/" + cm + "/labor", "{\"loginId\":\"admin1\",\"workDate\":\"" + day + "\",\"minutes\":30}").andExpect(status().isOk());
        postJson(admin, M + "/" + cm + "/finish", "{\"title\":\"베어링 교체\",\"workText\":\"6204 교체\",\"priority\":2,\"downYn\":\"Y\",\"downStartAt\":\""
                + day + "T08:00\",\"downEndAt\":\"" + day + "T08:45\",\"finishedAt\":\"" + day + "T08:40\",\"rowVer\":" + ver(cm) + "}")
                .andExpect(status().isOk());
        id(postJson(admin, "/api/cms/breakdowns", "{\"targetCd\":\"PRS-01\",\"symptomText\":\"유압 누유\"}"));
        String range = "\"from\":\"" + day + "\",\"to\":\"" + today + "\"";
        // 목록과 같은 행 수
        int listed = JsonPath.read(mvc.perform(get(M).param("from", day).param("to", today)
                .with(admin)).andReturn().getResponse().getContentAsString(), "$.data.length()");
        List<List<String>> all = rows(admin, "{" + range + "}");
        assertThat(all).hasSize(listed).hasSize(2);
        List<String> done = all.stream().filter(r -> r.get(col("title")).equals("베어링 교체")).findFirst().orElseThrow();
        assertThat(done.get(col("mwoType"))).isEqualTo("개선");
        assertThat(done.get(col("status"))).isEqualTo("완료");
        assertThat(done.get(col("targetCd"))).isEqualTo("PRS-01");
        assertThat(done.get(col("startedAtText"))).isEqualTo(day + " 08:05");     // 공장 현지(Asia/Seoul) 시각 그대로
        assertThat(done.get(col("finishedAtText"))).isEqualTo(day + " 08:40");
        assertThat(done.get(col("workText"))).isEqualTo("6204 교체");
        assertThat(done.get(col("downMinutes"))).isEqualTo("45");
        assertThat(done.get(col("laborMin"))).isEqualTo("30");
        List<String> bm = all.stream().filter(r -> r.get(col("mwoType")).equals("고장 수리")).findFirst().orElseThrow();
        assertThat(bm.get(col("status"))).isEqualTo("신고");
        assertThat(bm.get(col("symptomLabel"))).isEqualTo("유압 누유");
        // 조건: 유형·상태
        assertThat(rows(admin, "{" + range + ",\"mwoType\":\"BM\"}")).hasSize(1);
        assertThat(rows(admin, "{" + range + ",\"status\":\"DONE\"}")).hasSize(1);
    }

    @Test
    void 조회만_권한은_403_기간_상한_넘으면_400_B사는_A사_행을_못본다() throws Exception {
        id(postJson(admin, M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"정비\"}"));
        String range = "{\"from\":\"" + today + "\",\"to\":\"" + today + "\"}";
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-mwo", "SEARCH");
        postJson(accounts.as(comp, viewer, "viewer1"), M + "/export", range).andExpect(status().isForbidden());
        postJson(admin, M + "/export", "{\"from\":\"2020-01-01\",\"to\":\"" + today + "\"}").andExpect(status().isBadRequest());
        postJson(admin, M + "/export", "{\"from\":\"" + today + "\",\"to\":\"" + today + "\",\"status\":\"NOPE\"}").andExpect(status().isBadRequest());
        String other = accounts.newComp("MY");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-mwo", "SEARCH", "EXCEL");
        assertThat(rows(accounts.as(other, bu, "admin1"), range)).isEmpty();
    }
}
