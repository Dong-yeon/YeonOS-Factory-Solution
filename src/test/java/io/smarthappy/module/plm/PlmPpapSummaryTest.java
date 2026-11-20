package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PPAP 제출 요약서 엑셀 (docs/39 31장) — 머리(지시·고객·레벨·상태·제출·판정) → 요소 18행(이름·요구·상태·파일 이름) → 고객 판정란.
 * 제출 전이면 초안, 취소된 지시면 그 표시. 요소 메모·패키지 비고·파일 경로는 없음, 수식 주입 방지. EXCEL 권한, A사로 B사 404.
 */
@AutoConfigureMockMvc
class PlmPpapSummaryTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    RequestPostProcessor viewer;
    long owner;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PPS");
        owner = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, owner, "ENG", "plm-eco", "SEARCH", "EXCEL");
        me = accounts.as(comp, owner, "eng1");
        long v = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, v, "VIEW", "plm-eco", "SEARCH");
        viewer = accounts.as(comp, v, "view1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C1', '고객 일', 'SALES', 't', 't')", comp);
    }

    /** 지시 + 패키지(레벨 3, 요소 18개: 1~17 S·OPEN, 18 PSW 는 파일 붙여 DONE) — 메모·비고에 사내 글 */
    private long ppap(String ecoNo) {
        long eco = accounts.query(comp, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, ?, '브래킷 두께 변경', 'QUALITY', cast(? as date), ?, 't', 't') returning eco_id""", Long.class, comp, ecoNo, today, owner);
        long ppap = accounts.query(comp, """
                insert into plm_ppap (comp_cd, eco_id, cust_id, submit_level, remark, created_by, updated_by)
                values (?, ?, (select cust_id from mst_cust where cust_cd = 'C1'), 3, '사내 비고 — 고객에게 안 감', 't', 't') returning ppap_id""",
                Long.class, comp, eco);
        accounts.exec(comp, """
                insert into plm_ppap_elem (comp_cd, ppap_id, elem_no, req_cd, note, created_by, updated_by)
                select ?, ?, n, 'S', '사내 메모 ' || n, 't', 't' from generate_series(1, 18) n""", comp, ppap);
        long file = accounts.query(comp, """
                insert into sys_file (comp_cd, target_type, target_id, orig_name, ext, content_type, size_bytes, sha256, storage_key,
                                      created_by, updated_by)
                values (?, 'plm-eco', ?, '=HYPERLINK("x").pdf', 'pdf', 'application/pdf', 10, repeat('a', 64), ? || '/2026/10/' || gen_random_uuid(), 't', 't')
                returning file_id""", Long.class, comp, "PPAP-" + ppap, comp);
        accounts.exec(comp, "update plm_ppap_elem set status = 'DONE', file_id = ? where ppap_id = ? and elem_no = 18", file, ppap);
        accounts.exec(comp, "update plm_ppap_elem set req_cd = 'N', status = 'WAIVED' where ppap_id = ? and elem_no = 17", ppap);
        return ppap;
    }

    private ResultActions summary(RequestPostProcessor who, long ppap) throws Exception {
        return mvc.perform(post("/api/plm/ppaps/" + ppap + "/summary").with(csrf()).with(who));
    }

    /** 첫 시트 → 행별 글자(빈 행은 빈 목록) */
    private List<List<String>> sheet(long ppap, String ecoNo) throws Exception {
        byte[] x = summary(me, ppap).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString(ecoNo + "_ppap.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet s = wb.getSheetAt(0);
            for (int i = 0; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
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
    void 요약서는_머리_요소_18행_고객_판정란_한_장이고_사내_메모는_없다() throws Exception {
        long p = ppap("ECO-P1");
        List<List<String>> s = sheet(p, "ECO-P1");
        assertThat(s.get(0).get(0)).isEqualTo("PPAP 제출 요약 (초안 — 제출 전)");
        assertThat(row(s, "지시 번호")).containsExactly("지시 번호", "ECO-P1");
        assertThat(row(s, "고객")).containsExactly("고객", "고객 일 (C1)");
        assertThat(row(s, "제출 레벨")).containsExactly("제출 레벨", "3");
        // 상태는 코드 이름, 아직 없는 날짜·번호는 빈 칸("-" 아님)
        assertThat(row(s, "패키지 상태").get(1)).isNotBlank().isNotEqualTo("DRAFT");
        assertThat(row(s, "제출일")).containsExactly("제출일", "");
        assertThat(row(s, "판정 번호")).containsExactly("판정 번호", "");
        // 요소 표 — 머리 + 18행, 이름·요구·상태는 코드 이름, 파일은 이름만(수식처럼 보이면 ')
        assertThat(row(s, "번호")).containsExactly("번호", "요소", "요구", "상태", "제출 파일");
        for (int n = 1; n <= 18; n++) {
            List<String> r = row(s, String.valueOf(n));
            assertThat(r).hasSize(5);
            assertThat(r.get(1)).isNotBlank().isNotEqualTo(String.valueOf(n));
        }
        assertThat(row(s, "18").get(4)).isEqualTo("'=HYPERLINK(\"x\").pdf");
        assertThat(row(s, "17").get(2)).isNotEqualTo("N");
        assertThat(row(s, "1").get(4)).isEmpty();
        // 고객 판정란(빈 칸) — 이름 칸 + 빈 칸 4
        assertThat(row(s, "판정(승인·임시 승인·거절)")).containsExactly("판정(승인·임시 승인·거절)", "", "", "", "");
        // 사내 정보 없음 — 요소 메모·패키지 비고·저장 경로
        assertThat(s.stream().flatMap(List::stream).noneMatch(c -> c.contains("사내") || c.contains("/2026/10/"))).isTrue();
    }

    @Test
    void 판정된_패키지는_초안_표시가_없고_판정이_머리에_취소된_지시는_표시() throws Exception {
        long p = ppap("ECO-P2");
        accounts.exec(comp, "update plm_ppap set status = 'SUBMITTED', submitted_on = cast(? as date) - 3 where ppap_id = ?", today, p);
        accounts.exec(comp, "update plm_ppap set status = 'INTERIM', decided_on = cast(? as date), decision_ref = 'PSW-77' where ppap_id = ?", today, p);
        List<List<String>> s = sheet(p, "ECO-P2");
        assertThat(s.get(0).get(0)).isEqualTo("PPAP 제출 요약");
        assertThat(row(s, "판정일")).containsExactly("판정일", today);
        assertThat(row(s, "판정 번호")).containsExactly("판정 번호", "PSW-77");
        // 지시를 취소해도 기록으로 내려받기 — 제목에 표시
        accounts.exec(comp, "update plm_eco set status = 'CANCELLED', cancel_reason = '시험' where eco_no = 'ECO-P2'");
        assertThat(sheet(p, "ECO-P2").get(0).get(0)).isEqualTo("PPAP 제출 요약 (취소된 지시)");
    }

    @Test
    void EXCEL_권한_없으면_403_A사로_B사는_404() throws Exception {
        long p = ppap("ECO-P3");
        summary(viewer, p).andExpect(status().isForbidden());
        String compB = accounts.newComp("PPSB");
        long b = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, b, "ENG", "plm-eco", "SEARCH", "EXCEL");
        summary(accounts.as(compB, b, "engB"), p).andExpect(status().isNotFound());
        summary(me, 999_999_999L).andExpect(status().isNotFound());
        summary(me, p).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'ACTION' and action_cd = 'EXCEL' and detail = ?",
                Integer.class, "ppapSummary;ppapId=" + p)).isEqualTo(1);
    }
}
