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
 * 변경 통지서 엑셀 (docs/39 29장) — 고객 제출용 서식 한 장: 머리·변경 내용·대상 표(코드 이름·리비전·공정)·고객 확인란,
 * 결재 전이면 초안 표시, 수식 주입 방지, 사내 정보(결재선·서명) 없음. EXCEL 권한, A사로 B사 404.
 */
@AutoConfigureMockMvc
class PlmEcoNoticeTest extends PostgresTestBase {

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
        comp = accounts.newComp("PNT");
        owner = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, owner, "ENG", "plm-eco", "SEARCH", "EXCEL");
        me = accounts.as(comp, owner, "eng1");
        long v = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, v, "VIEW", "plm-eco", "SEARCH");
        viewer = accounts.as(comp, v, "view1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', '브래킷', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'SPOT', '용접', 'WELD', 't', 't')", comp);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C1', '고객 일', 'SALES', 't', 't')", comp);
    }

    private long eco(String title, String description) {
        long eco = accounts.query(comp, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, description, effective_date, owner_id,
                                     cust_approval_req_yn, cust_id, cust_approval_status, created_by, updated_by)
                select ?, 'ECO-N1', ?, 'QUALITY', ?, cast(? as date), ?, 'Y', cust_id, 'PENDING', 't', 't' from mst_cust where cust_cd = 'C1'
                returning eco_id""", Long.class, comp, title, description, today, owner);
        accounts.exec(comp, "insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, change_desc, created_by, updated_by) values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG'), '두께 2.0 → 2.3', 't', 't')", comp, eco);
        accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, proc_id, change_desc, created_by, updated_by)
                values (?, ?, 2, 'PROC', (select item_id from mst_item where item_cd = 'FG'), (select proc_id from mst_proc where proc_cd = 'SPOT'), '=SUM(1,2)', 't', 't')""", comp, eco);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'B', cast(? as date), 't', 't') returning bom_id""", Long.class, comp, today);
        accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, bom_id, created_by, updated_by)
                values (?, ?, 3, 'BOM', (select item_id from mst_item where item_cd = 'FG'), ?, 't', 't')""", comp, eco, bom);
        return eco;
    }

    private ResultActions notice(RequestPostProcessor who, long eco) throws Exception {
        return mvc.perform(post("/api/plm/ecos/" + eco + "/notice").with(csrf()).with(who));
    }

    /** 첫 시트 → 행별 글자(빈 행은 빈 목록) */
    private List<List<String>> sheet(long eco) throws Exception {
        byte[] x = notice(me, eco).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString("ECO-N1_notice.xlsx")))
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
    void 통지서는_머리_변경_내용_대상_고객_확인란_한_장() throws Exception {
        long eco = eco("브래킷 두께 변경", "두께를 올린다\n도면 Rev B");
        List<List<String>> s = sheet(eco);
        assertThat(s.get(0).get(0)).isEqualTo("설계 변경 통지서 (초안 — 결재 전)");
        assertThat(row(s, "지시 번호")).containsExactly("지시 번호", "ECO-N1");
        // 코드는 이름으로 — 사유 코드 글자(QUALITY)가 아니라 공통코드 이름
        assertThat(row(s, "변경 사유").get(1)).isNotBlank().isNotEqualTo("QUALITY");
        assertThat(row(s, "고객")).containsExactly("고객", "고객 일 (C1)");
        assertThat(row(s, "적용 예정일").get(1)).isEqualTo(today);
        assertThat(s.stream().anyMatch(r -> r.size() == 1 && r.get(0).equals("두께를 올린다\n도면 Rev B"))).isTrue();
        // 대상 표 — 유형은 코드 이름, BOM 은 리비전, 공정은 코드·이름, 수식처럼 보이는 글자는 '
        assertThat(row(s, "순번")).containsExactly("순번", "대상 유형", "품목 코드", "품명", "리비전·설비·공정·문서", "변경 내용");
        assertThat(row(s, "1")).containsExactly("1", "품목", "FG", "브래킷", "", "두께 2.0 → 2.3");
        assertThat(row(s, "2").get(4)).isEqualTo("SPOT 용접");
        assertThat(row(s, "2").get(5)).isEqualTo("'=SUM(1,2)");
        assertThat(row(s, "3").get(4)).isEqualTo("Rev B");
        // 고객 확인란(빈 칸) 그리고 사내 정보 없음
        assertThat(row(s, "판정(확인·승인·거절)")).hasSize(6);
        assertThat(s.stream().flatMap(List::stream).noneMatch(c -> c.contains("결재선") || c.contains("서명 뒤"))).isTrue();
    }

    @Test
    void 승인된_지시는_초안_표시가_없고_고객_없는_지시는_고객_칸이_빈_칸() throws Exception {
        long eco = eco("승인됨", null);
        // 고객 승인이 필요 없는 지시(작성 중에 바꾼 뒤 승인) — 승인 뒤에는 내용이 고정
        accounts.exec(comp, "update plm_eco set cust_approval_req_yn = 'N', cust_id = null, cust_approval_status = 'NA' where eco_id = ?", eco);
        accounts.exec(comp, "update plm_eco set status = 'PENDING' where eco_id = ?", eco);
        accounts.exec(comp, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", eco);
        List<List<String>> s = sheet(eco);
        assertThat(s.get(0).get(0)).isEqualTo("설계 변경 통지서");
        // "-" 같은 글자는 수식 방지로 '- 가 되므로 빈 칸
        assertThat(row(s, "고객")).allMatch(c -> !c.startsWith("'"));
        assertThat(row(s, "고객 승인")).allMatch(c -> !c.startsWith("'"));
    }

    @Test
    void EXCEL_권한_없으면_403_A사로_B사는_404() throws Exception {
        long eco = eco("권한", null);
        notice(viewer, eco).andExpect(status().isForbidden());
        String compB = accounts.newComp("PNTB");
        long b = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, b, "ENG", "plm-eco", "SEARCH", "EXCEL");
        notice(accounts.as(compB, b, "engB"), eco).andExpect(status().isNotFound());
        notice(me, 999_999_999L).andExpect(status().isNotFound());
        // 내려받기 감사(34장 R4) — 접속 로그 ACTION·EXCEL 에 어떤 문서·지시
        notice(me, eco).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'ACTION' and action_cd = 'EXCEL' and detail = ?",
                Integer.class, "notice;ecoId=" + eco)).isEqualTo(1);
    }
}
