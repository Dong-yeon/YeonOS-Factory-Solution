package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 사내 지시 기록서 엑셀 (docs/39 34장 R1~R5) — 머리·대상 → 결재선(결재자·결정·의견) → 전자서명(의미·서명자) → 고객 승인·PPAP·PCN.
 * 보는 사람 언어(영어 사용자는 영어), 내려받기는 접속 로그 ACTION·EXCEL(detail record;ecoId). EXCEL 권한, A사로 B사 404.
 */
@AutoConfigureMockMvc
class PlmEcoRecordTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long ownerId;
    long mgrId;
    RequestPostProcessor owner;
    RequestPostProcessor mgr;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PRC");
        ownerId = accounts.newUser(comp, "eng1", PW);
        mgrId = accounts.newUser(comp, "mgr1", PW);
        for (long u : new long[] {ownerId, mgrId}) {
            accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE", "EXCEL");
        }
        owner = accounts.as(comp, ownerId, "eng1");
        mgr = accounts.as(comp, mgrId, "mgr1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_4M_REQUIRED', 'N', 't', 't')", comp);
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'PLM_ESIGN_REQUIRED', 'Y', 't', 't')", comp);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C1', 'C1 고객', 'SALES', 't', 't')", comp);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private int ecoVer(long ecoId) {
        return accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, ecoId);
    }

    private static long data(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 품목 대상 지시 → 상신(결재 mgr1) → mgr1 이 전자서명으로 승인 → 고객 통지 하나 */
    private long approvedEco() throws Exception {
        long eco = data(mvc.perform(json(post(ECO), "{\"title\":\"기록 시험\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today
                + "\",\"description\":\"두께 변경\"}").with(owner)));
        mvc.perform(json(put(ECO + "/" + eco + "/targets"), "{\"rowVer\":" + ecoVer(eco)
                + ",\"targets\":[{\"rowKey\":\"i\",\"targetType\":\"ITEM\",\"itemCd\":\"FG\",\"changeDesc\":\"=1+1\"}]}").with(owner)).andExpect(status().isOk());
        mvc.perform(json(post(ECO + "/" + eco + "/submit"), "{\"rowVer\":" + ecoVer(eco) + ",\"lines\":[{\"userId\":" + mgrId
                + ",\"lineType\":\"APPROVE\"}]}").with(owner)).andExpect(status().isOk());
        MockHttpSession s = new MockHttpSession();
        String token = JsonPath.read(mvc.perform(json(post("/api/platform/esign/token"), "{\"meaningCd\":\"APPROVE\",\"targetType\":\"plm_eco\",\"targetId\":"
                + eco + ",\"password\":\"" + PW + "\"}").with(mgr).session(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.token");
        mvc.perform(json(post(ECO + "/" + eco + "/approve"), "{\"comment\":\"확인함\",\"esignToken\":\"" + token + "\"}").with(mgr).session(s)
                .header("X-Idempotency-Key", java.util.UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(json(post(ECO + "/" + eco + "/pcns"), "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\"" + today + "\"}")
                .with(owner)).andExpect(status().isOk());
        return eco;
    }

    private ResultActions record(RequestPostProcessor who, long eco, String lang) throws Exception {
        MockHttpServletRequestBuilder b = post(ECO + "/" + eco + "/record").with(csrf()).with(who);
        return mvc.perform(lang == null ? b : b.header("Accept-Language", lang));
    }

    /** 첫 시트 → 행별 글자 */
    private List<List<String>> sheet(long eco, String lang) throws Exception {
        String no = accounts.query(comp, "select eco_no from plm_eco where eco_id = ?", String.class, eco);
        byte[] x = record(owner, eco, lang).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString(no + "_record.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet sh = wb.getSheetAt(0);
            for (int i = 0; i <= sh.getLastRowNum(); i++) {
                Row r = sh.getRow(i);
                List<String> cells = new ArrayList<>();
                if (r != null) {
                    for (int j = 0; j < r.getLastCellNum(); j++) {
                        Cell c = r.getCell(j);
                        cells.add(c == null ? "" : c.getStringCellValue());
                    }
                }
                out.add(cells);
            }
        }
        return out;
    }

    private static List<String> row(List<List<String>> s, String first) {
        return s.stream().filter(r -> !r.isEmpty() && r.get(0).equals(first)).findFirst().orElseThrow();
    }

    @Test
    void 기록서는_결재선_전자서명_통지까지_사내_정보를_담고_내려받기는_접속_로그에_남는다() throws Exception {
        long eco = approvedEco();
        String mgrNm = accounts.query(comp, "select user_nm from sys_user where user_id = ?", String.class, mgrId);
        List<List<String>> s = sheet(eco, "ko");
        assertThat(s.get(0).get(0)).startsWith("설계 변경 지시 기록서(사내)");
        assertThat(row(s, "상태").get(1)).isNotEqualTo("APPROVED").isNotBlank();
        assertThat(row(s, "승인").get(1)).isNotBlank();
        // 대상 — 수식처럼 보이는 글자는 '
        assertThat(row(s, "1").get(6)).isEqualTo("'=1+1");
        // 결재선 — 결재자·유형·결정·의견
        List<String> line = s.stream().filter(r -> r.size() >= 6 && mgrNm.equals(r.get(1))).findFirst().orElseThrow();
        assertThat(line.subList(2, 4)).containsExactly("결재", "승인");
        assertThat(line.get(5)).isEqualTo("확인함");
        // 전자서명 — 의미·서명자
        assertThat(s.stream().anyMatch(r -> r.size() >= 2 && "승인".equals(r.get(0)) && mgrNm.equals(r.get(1)))).isTrue();
        // 고객 통지 한 줄
        assertThat(s.stream().anyMatch(r -> !r.isEmpty() && r.get(0).startsWith("PCN") && r.size() >= 2 && "C1".equals(r.get(1)))).isTrue();
        // 감사: 접속 로그 ACTION·EXCEL + 어떤 문서·지시
        assertThat(accounts.query(comp, """
                select count(*) from sys_access_log where event_cd = 'ACTION' and action_cd = 'EXCEL' and prog_cd = 'plm-eco' and detail = ?""",
                Integer.class, "record;ecoId=" + eco)).isEqualTo(1);
    }

    @Test
    void 보는_사람_언어로_나온다() throws Exception {
        long eco = approvedEco();
        // 언어는 로그인 사용자 정보(locale_cd)로 정해진다 — 영어 사용자로 로그인
        long en = accounts.newUser(comp, "eng2", PW);
        accounts.grant(comp, en, "ENG2", "plm-eco", "SEARCH", "EXCEL");
        byte[] x = record(accounts.as(comp, en, "eng2", "en"), eco, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<List<String>> s = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            for (Row r : wb.getSheetAt(0)) {
                List<String> cells = new ArrayList<>();
                r.forEach(c -> cells.add(c.getStringCellValue()));
                s.add(cells);
            }
        }
        assertThat(s.get(0).get(0)).startsWith("Engineering Change Order Record (internal)");
        assertThat(s.stream().anyMatch(r -> !r.isEmpty() && "Approval line".equals(r.get(0)))).isTrue();
    }

    @Test
    void EXCEL_권한_없으면_403_A사로_B사는_404() throws Exception {
        long eco = approvedEco();
        long v = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, v, "VIEW", "plm-eco", "SEARCH");
        record(accounts.as(comp, v, "view1"), eco, null).andExpect(status().isForbidden());
        String compB = accounts.newComp("PRCB");
        long b = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, b, "ENG", "plm-eco", "SEARCH", "EXCEL");
        record(accounts.as(compB, b, "engB"), eco, null).andExpect(status().isNotFound());
        record(owner, 999_999_999L, null).andExpect(status().isNotFound());
    }
}
