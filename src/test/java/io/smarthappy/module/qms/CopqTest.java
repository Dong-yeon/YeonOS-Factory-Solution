package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/**
 * P3 QMS 29장 COPQ 1단계 — 종결 NCR 처리 × 그때 품목 표준단가(손 계산·시점 단가·외부 실패), 단가 없음은 빈칸 + 건수,
 * 수동 비용 행, 품목 표준단가(이력·ERP 잠금), 이상 사용(입력·권한·A사로 B사·직접 SQL), 엑셀(34장 — 원장 줄 + 수동 행 한 시트).
 */
class CopqTest extends QmsTestBase {

    static final String C = "/api/qms/copq";
    static final String N = "/api/qms/ncrs";
    static final String ITEM = "/api/master/items";

    long defect;
    String month;

    @BeforeEach
    void copqSetUp() {
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "qms-copq", "SEARCH", "SAVE", "DELETE", "EXCEL");
        policy("QMS_NCR_AUTO_HOLD", "N");
        defect = defect(comp);
        // 이 회사(시험 회사 시간대 Asia/Seoul)의 이번 달 — NCR 종결 시각이 지금이라 이 달로 잡힌다
        month = YearMonth.from(today).toString();
    }

    long defect(String c) {
        return accounts.query(c, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-COPQ', '치수 불량', 'DIMENSION', 't', 't') returning defect_id""", Long.class, c);
    }

    void lot(String c, String no, String itemCd, String unit) {
        accounts.exec(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 100, ?, now(), 't', 't' from mst_item where item_cd = ?""", c, no, unit, itemCd);
    }

    void price(String itemCd, String p) {
        accounts.exec(comp, "update mst_item set std_price = ?::numeric, updated_by = 't' where item_cd = ?", p, itemCd);
    }

    int ver(long ncr) {
        return accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, ncr);
    }

    /** NCR 등록 → 처리 저장 → 종결. disps = "SCRAP:3,REWORK:2" */
    long closedNcr(String lotNo, String source, String qty, String disps) throws Exception {
        long id = id(postJson(N, "{\"lotNo\":" + q(lotNo) + ",\"source\":" + q(source) + ",\"defectId\":" + defect + ",\"ncQty\":" + qty
                + ",\"description\":\"COPQ 시험\"}"));
        StringBuilder d = new StringBuilder();
        int k = 0;
        for (String part : disps.split(",")) {
            String[] x = part.split(":");
            d.append(k == 0 ? "" : ",").append("{\"rowKey\":\"k").append(k++).append("\",\"dispType\":\"").append(x[0]).append("\",\"qty\":")
                    .append(x[1]).append(",\"note\":\"MRB\",\"reworkLotNo\":").append(x[0].equals("REWORK") ? "\"R-1\"" : "null").append("}");
        }
        putJson(N + "/" + id, "{\"rowVer\":" + ver(id) + ",\"defectId\":" + defect + ",\"ncQty\":" + qty
                + ",\"description\":\"COPQ 시험\",\"disps\":[" + d + "]}").andExpect(status().isOk());
        postJson(N + "/" + id + "/close", "{\"rowVer\":" + ver(id) + "}").andExpect(status().isOk());
        return id;
    }

    ResultActions summary(String from, String to) throws Exception {
        return mvc.perform(get(C).param("fromMonth", from).param("toMonth", to).with(admin));
    }

    static <T> T at(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    static BigDecimal dec(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }

    // =====================================================================
    // 1. 원장 줄 — 손 계산·시점 단가·구분
    // =====================================================================

    @Test
    void 종결_NCR_처리_수량_곱하기_종결_때_단가_특채_반품_진행중은_빼고_클레임은_외부_실패() throws Exception {
        lot(comp, "L-C1", "FG1", "EA");
        lot(comp, "L-C2", "FG1", "EA");
        lot(comp, "L-C3", "FG1", "EA");
        price("FG1", "1000");
        // 공정 NCR: 폐기 3·재작업 2·선별 4·반품 1(반품은 비용 아님) → 내부 실패 9 × 1000
        long n1 = closedNcr("L-C1", "PROCESS", "10", "SCRAP:3,REWORK:2,SORT:4,RETURN:1");
        price("FG1", "2000");
        // 고객 클레임 NCR: 폐기 2 × 2000(종결 때 단가) → 외부 실패
        long n2 = closedNcr("L-C2", "CLAIM", "2", "SCRAP:2");
        // 진행 중(종결 안 함) NCR 은 세지 않는다
        id(postJson(N, "{\"lotNo\":\"L-C3\",\"source\":\"OTHER\",\"defectId\":" + defect + ",\"ncQty\":5,\"description\":\"진행 중\"}"));

        ResultActions r = summary(month, month).andExpect(status().isOk());
        assertThat(dec(at(r, "$.data.months[0].internal"))).isEqualByComparingTo("9000");
        assertThat(dec(at(r, "$.data.months[0].external"))).isEqualByComparingTo("4000");
        assertThat(dec(at(r, "$.data.months[0].prevention"))).isEqualByComparingTo("0");
        assertThat(dec(at(r, "$.data.months[0].total"))).isEqualByComparingTo("13000");
        assertThat((Integer) at(r, "$.data.months[0].unpricedCnt")).isZero();
        List<Map<String, Object>> lines = at(r, "$.data.lines");
        assertThat(lines).extracting(l -> l.get("category") + ":" + l.get("source") + ":" + dec(l.get("qty")).stripTrailingZeros().toPlainString()
                + "@" + dec(l.get("stdPrice")).stripTrailingZeros().toPlainString() + "=" + dec(l.get("amount")).stripTrailingZeros().toPlainString())
                .containsExactly("EXTERNAL:NCR_SCRAP:2@2000=4000", "INTERNAL:NCR_REWORK:2@1000=2000", "INTERNAL:NCR_SCRAP:3@1000=3000",
                        "INTERNAL:NCR_SORT:4@1000=4000");
        // 줄마다 묶인 NCR 번호(36장) — 외부 실패 줄 = 클레임 NCR, 내부 줄 = 공정 NCR
        String no1 = accounts.query(comp, "select ncr_no from qms_ncr where ncr_id = ?", String.class, n1);
        String no2 = accounts.query(comp, "select ncr_no from qms_ncr where ncr_id = ?", String.class, n2);
        assertThat(lines).extracting(l -> (String) l.get("ncrNos")).containsExactly(no2, no1, no1, no1);

        // 단가를 다시 고쳐도 지난 사건의 금액은 그대로(시점 단가 — CQ-3)
        price("FG1", "5000");
        ResultActions again = summary(month, month);
        assertThat(dec(at(again, "$.data.months[0].total"))).isEqualByComparingTo("13000");
        assertThat(accounts.query(comp, "select count(*) from mst_item_price_hist h join mst_item i on i.item_id = h.item_id where i.item_cd = 'FG1'",
                Long.class)).isEqualTo(3L);
    }

    @Test
    void 단가_없으면_금액을_비우고_건수만_0원으로_세지_않는다() throws Exception {
        lot(comp, "L-R1", "RM1", "EA");
        lot(comp, "L-F1", "FG1", "EA");
        lot(comp, "L-F2", "FG1", "EA");
        lot(comp, "L-F3", "FG1", "EA");
        // RM1 은 단가가 없다 → 단가 없음
        closedNcr("L-R1", "PROCESS", "2", "SCRAP:2");
        ResultActions only = summary(month, month);
        assertThat((Object) at(only, "$.data.months[0].internal")).as("줄은 있는데 금액 있는 줄이 없음 = null(0 아님)").isNull();
        assertThat((Object) at(only, "$.data.months[0].total")).as("합계도 null(0원 아님)").isNull();
        assertThat((Integer) at(only, "$.data.months[0].unpricedCnt")).isEqualTo(1);
        assertThat((Object) at(only, "$.data.lines[0].amount")).isNull();
        assertThat((String) at(only, "$.data.lines[0].reason")).isEqualTo("NO_PRICE");

        // FG1: 단가를 넣기 전 종결 → 단가 없음(나중에 넣은 단가를 거슬러 쓰지 않는다), 넣은 뒤 종결 → 금액
        closedNcr("L-F1", "PROCESS", "4", "SCRAP:1,SORT:3");
        price("FG1", "100");
        closedNcr("L-F2", "PROCESS", "3", "SCRAP:3");
        // 단가를 지우면(이력에 null) 그 뒤 종결은 단가 없음
        price("FG1", null);
        closedNcr("L-F3", "PROCESS", "1", "SCRAP:1");

        ResultActions r = summary(month, month);
        assertThat(dec(at(r, "$.data.months[0].internal"))).as("단가 있는 줄만 더한다").isEqualByComparingTo("300");
        assertThat((Integer) at(r, "$.data.months[0].unpricedCnt")).as("RM1 1 + 단가 전 2 + 단가 지운 뒤 1").isEqualTo(4);
        List<Map<String, Object>> lines = at(r, "$.data.lines");
        assertThat(lines).extracting(l -> l.get("itemCd") + ":" + l.get("source") + ":" + l.get("lineCnt") + ":" + l.get("reason"))
                .containsExactlyInAnyOrder("FG1:NCR_SCRAP:1:null", "FG1:NCR_SCRAP:2:NO_PRICE", "FG1:NCR_SORT:1:NO_PRICE", "RM1:NCR_SCRAP:1:NO_PRICE");
    }

    // =====================================================================
    // 2. 수동 비용 행
    // =====================================================================

    String cost(String m, String cat, String item, String amount, Integer rowVer) {
        return "{\"costMonth\":" + q(m) + ",\"category\":" + q(cat) + ",\"costItem\":" + q(item) + ",\"amount\":" + q(amount)
                + ",\"note\":null,\"rowVer\":" + rowVer + "}";
    }

    @Test
    void 수동_비용_등록_고치기_삭제와_월_합계() throws Exception {
        YearMonth prev = YearMonth.from(today).minusMonths(1);
        long a = id(postJson(C + "/costs", cost(month, "PREVENTION", "품질 교육", "150000.5", null)));
        id(postJson(C + "/costs", cost(month, "APPRAISAL", "수입검사 인건비", "80000", null)));
        id(postJson(C + "/costs", cost(prev.toString(), "EXTERNAL", "고객 클레임 배상", "1000000", null)));

        ResultActions r = summary(prev.toString(), month);
        assertThat((List<?>) at(r, "$.data.months")).hasSize(2);
        assertThat((String) at(r, "$.data.months[0].month")).isEqualTo(prev.toString());
        assertThat(dec(at(r, "$.data.months[0].external"))).isEqualByComparingTo("1000000");
        assertThat(dec(at(r, "$.data.months[1].prevention"))).isEqualByComparingTo("150000.50");
        assertThat(dec(at(r, "$.data.months[1].appraisal"))).isEqualByComparingTo("80000");
        assertThat(dec(at(r, "$.data.sum.total"))).isEqualByComparingTo("1230000.50");

        int rv = accounts.query(comp, "select row_ver from qms_copq_cost where cost_id = ?", Integer.class, a);
        putJson(C + "/costs/" + a, cost(month, "PREVENTION", "품질 교육(외부)", "200000", rv)).andExpect(status().isOk());
        putJson(C + "/costs/" + a, cost(month, "PREVENTION", "덮어쓰기", "1", rv)).andExpect(status().isConflict());
        assertThat(dec(at(summary(month, month), "$.data.months[0].prevention"))).isEqualByComparingTo("200000");
        postJson(C + "/costs/" + a + "/delete", "{\"rowVer\":" + rv + "}").andExpect(status().isConflict());
        postJson(C + "/costs/" + a + "/delete", "{\"rowVer\":" + (rv + 1) + "}").andExpect(status().isOk());
        assertThat(dec(at(summary(month, month), "$.data.months[0].prevention"))).isEqualByComparingTo("0");
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'qms_copq_cost' and row_key = ?", Long.class,
                String.valueOf(a))).as("지워도 감사 기록").isGreaterThanOrEqualTo(3L);
    }

    @Test
    void 이상_입력_달_범위_구분_금액() throws Exception {
        postJson(C + "/costs", cost("2026-13", "XX", " ", "-1", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'costMonth')].code").value("FORMAT"))
                .andExpect(jsonPath("$.errors[?(@.field == 'category')].code").value("CODE"))
                .andExpect(jsonPath("$.errors[?(@.field == 'costItem')].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')].code").value("NOT_NEGATIVE"));
        postJson(C + "/costs", cost(month, "INTERNAL", "x", "1.234", null)).andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        postJson(C + "/costs", cost(month, "INTERNAL", "x", "1e5", null)).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        postJson(C + "/costs", cost(month, "INTERNAL", "x", "12345678901234567", null)).andExpect(jsonPath("$.errors[0].code").value("INT_DIGITS"));
        postJson(C + "/costs", cost("1999-12", "INTERNAL", "x", "1", null)).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        postJson(C + "/costs", cost(month, "INTERNAL", "x".repeat(101), "1", null)).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        putJson(C + "/costs/1", cost(month, "INTERNAL", "x", "1", null)).andExpect(status().isBadRequest());
        putJson(C + "/costs/999999999", cost(month, "INTERNAL", "x", "1", 0)).andExpect(status().isNotFound());
        postJson(C + "/costs/999999999/delete", "{\"rowVer\":0}").andExpect(status().isNotFound());
        postJson(C + "/costs/1/delete", "{}").andExpect(status().isBadRequest());
        // 조회 달: 24개월 넘음·거꾸로·형식
        summary("2024-01", "2025-12").andExpect(status().isOk());
        summary("2024-01", "2026-01").andExpect(jsonPath("$.errors[0].code").value("MONTHS_TOO_LONG"));
        summary("2026-05", "2026-04").andExpect(status().isBadRequest());
        summary("2026-5", "2026-06").andExpect(status().isBadRequest());
        // 비우면 이번 달
        mvc.perform(get(C).with(admin)).andExpect(jsonPath("$.data.fromMonth").value(month)).andExpect(jsonPath("$.data.toMonth").value(month));
    }

    // =====================================================================
    // 3. 품목 표준단가 — 이력·검증·ERP 잠금
    // =====================================================================

    ResultActions saveItem(long itemId, String stdPrice) throws Exception {
        int rv = accounts.query(comp, "select row_ver from mst_item where item_id = ?", Integer.class, itemId);
        return putJson(ITEM + "/" + itemId, "{\"itemCd\":\"FG1\",\"itemNm\":\"FG1 품목\",\"itemType\":\"FG\",\"procType\":\"MAKE\",\"unitCd\":\"EA\","
                + "\"lotCtrlYn\":\"Y\",\"useYn\":\"Y\",\"stdPrice\":" + q(stdPrice) + ",\"rowVer\":" + rv + "}");
    }

    @Test
    void 품목_표준단가_저장_이력_검증_ERP_주인이면_못_바꿈() throws Exception {
        accounts.grant(comp, userId, "QA", "mst-item", "SEARCH", "SAVE");
        long fg = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        saveItem(fg, "1234.5").andExpect(status().isOk());
        mvc.perform(get(ITEM + "/" + fg).with(admin)).andExpect(jsonPath("$.data.stdPrice").value(1234.5));
        saveItem(fg, "1234.50").andExpect(status().isOk());                  // 같은 값 — 이력 안 늘어남
        saveItem(fg, "").andExpect(status().isOk());                         // 비우면 단가 없음(null 이력)
        assertThat(accounts.query(comp, "select string_agg(coalesce(std_price::numeric(18,1)::text, 'null'), ',' order by hist_id) "
                + "from mst_item_price_hist where item_id = ?", String.class, fg)).isEqualTo("1234.5,null");
        saveItem(fg, "1.23456").andExpect(jsonPath("$.errors[0].field").value("stdPrice")).andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        saveItem(fg, "-1").andExpect(jsonPath("$.errors[0].code").value("NOT_NEGATIVE"));
        saveItem(fg, "1,000").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        saveItem(fg, "0").andExpect(status().isOk());                        // 0 은 단가(무상) — 비움과 다름
        assertThat(accounts.query(comp, "select std_price::text from mst_item where item_id = ?", String.class, fg)).isEqualTo("0.0000");

        // ERP 가 품목 주인(PULL): 단가도 ERP 주인 필드 — 바꾸면 400, 그대로면 통과
        policy("ERP_ITEM_SYNC_MODE", "PULL");
        saveItem(fg, "500").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("stdPrice"))
                .andExpect(jsonPath("$.errors[0].code").value("ERP_OWNED"));
        saveItem(fg, "0.00").andExpect(status().isOk());
        mvc.perform(get(ITEM + "/settings").with(admin)).andExpect(jsonPath("$.data.erpOwnedFields[6]").value("stdPrice"));
    }

    // =====================================================================
    // 4. 권한·A사로 B사·직접 SQL
    // =====================================================================

    /** 엑셀 첫 시트의 데이터 줄(머리 줄 뺌) */
    static List<Row> sheetRows(byte[] xlsx) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sh = wb.getSheetAt(0);
            List<Row> out = new java.util.ArrayList<>();
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                if (sh.getRow(i) != null) {
                    out.add(sh.getRow(i));
                }
            }
            assertThat(sh.getRow(0).getLastCellNum()).isEqualTo((short) CopqExportService.COLUMNS.size());
            return out;
        }
    }

    static String text(Row r, int c) {
        return r.getCell(c) == null ? "" : new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(r.getCell(c));
    }

    @Test
    void 엑셀은_조회와_같은_달_범위로_원장_줄과_수동_행을_한_시트에_권한_A사로_B사() throws Exception {
        lot(comp, "L-X1", "FG1", "EA");
        lot(comp, "L-X2", "RM1", "EA");
        price("FG1", "1000");
        closedNcr("L-X1", "PROCESS", "3", "SCRAP:3");                // 3 × 1000 = 3000
        closedNcr("L-X2", "PROCESS", "2", "REWORK:2");                // RM1 단가 없음 → 금액 빈칸
        postJson(C + "/costs", cost(month, "PREVENTION", "품질 교육", "150000.5", null).replace("\"note\":null", "\"note\":\"외부 강사\""))
                .andExpect(status().isOk());
        String prev = YearMonth.parse(month).minusMonths(1).toString();
        postJson(C + "/costs", cost(prev, "APPRAISAL", "지난달 비용", "1", null)).andExpect(status().isOk());   // 범위 밖

        String body = "{\"fromMonth\":" + q(month) + ",\"toMonth\":" + q(month) + "}";
        byte[] x = mvc.perform(json(post(C + "/export"), body).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<Row> rows = sheetRows(x);
        // 순서: 원장(구분 → 출처 코드 NCR_REWORK < NCR_SCRAP → 품목) → 수동. 열: 종류0 달1 구분2 출처3 품목코드4 품목명5 수량6 단위7 단가8 건수9 금액10 사유11 NCR12 항목13 비고14
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> text(r, 1) + "|" + text(r, 4) + "|" + text(r, 13)).containsExactly(
                month + "|RM1|", month + "|FG1|", month + "||품질 교육");
        assertThat(text(rows.get(0), 10)).isEmpty();                     // 단가 없음 = 빈칸(0 아님)
        assertThat(text(rows.get(0), 11)).isNotEmpty();                  // 사유 글자
        assertThat(rows.get(1).getCell(10).getNumericCellValue()).isEqualTo(3000.0);
        assertThat(rows.get(1).getCell(6).getNumericCellValue()).isEqualTo(3.0);
        assertThat(rows.get(2).getCell(10).getNumericCellValue()).isEqualTo(150000.5);
        assertThat(text(rows.get(2), 14)).isEqualTo("외부 강사");
        assertThat(text(rows.get(1), 12)).startsWith("NC");               // 원장 줄의 NCR 번호(36장)
        assertThat(text(rows.get(2), 12)).isEmpty();
        assertThat(text(rows.get(0), 0)).isNotEqualTo(text(rows.get(2), 0));   // 종류 원장 ≠ 수동(문구)
        assertThat(text(rows.get(1), 3)).isNotEqualTo("NCR_SCRAP");      // 출처는 코드가 아니라 문구

        // 권한: 조회만 있으면 엑셀 403, 잘못된 달 400
        long viewer = accounts.newUser(comp, "cx1", PW);
        accounts.grant(comp, viewer, "QV", "qms-copq", "SEARCH");
        mvc.perform(json(post(C + "/export"), body).with(accounts.as(comp, viewer, "cx1"))).andExpect(status().isForbidden());
        postJson(C + "/export", "{\"fromMonth\":\"2026-13\",\"toMonth\":\"2026-13\"}").andExpect(status().isBadRequest());
        // A사로 B사: B사 엑셀에는 A사 줄이 없다(머리 줄만)
        String other = accounts.newComp("QX");
        fixtures(other);
        long bu = accounts.newUser(other, "qx1", PW);
        accounts.grant(other, bu, "QA", "qms-copq", "SEARCH", "EXCEL");
        byte[] bx = mvc.perform(json(post(C + "/export"), body).with(accounts.as(other, bu, "qx1"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(sheetRows(bx)).isEmpty();
    }

    @Test
    void 권한_A사로_B사_직접SQL() throws Exception {
        lot(comp, "L-P1", "FG1", "EA");
        price("FG1", "10");
        closedNcr("L-P1", "PROCESS", "1", "SCRAP:1");
        long a = id(postJson(C + "/costs", cost(month, "PREVENTION", "교육", "100", null)));

        long viewer = accounts.newUser(comp, "cv1", PW);
        accounts.grant(comp, viewer, "QV", "qms-copq", "SEARCH");
        long none = accounts.newUser(comp, "cn1", PW);
        mvc.perform(get(C).with(accounts.as(comp, none, "cn1"))).andExpect(status().isForbidden());
        mvc.perform(get(C).with(accounts.as(comp, viewer, "cv1"))).andExpect(status().isOk());
        mvc.perform(json(post(C + "/costs"), cost(month, "PREVENTION", "x", "1", null)).with(accounts.as(comp, viewer, "cv1")))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(C + "/costs/" + a + "/delete"), "{\"rowVer\":0}").with(accounts.as(comp, viewer, "cv1"))).andExpect(status().isForbidden());

        String other = accounts.newComp("QC");
        fixtures(other);
        long bu = accounts.newUser(other, "qc1", PW);
        accounts.grant(other, bu, "QA", "qms-copq", "SEARCH", "SAVE", "DELETE");
        var b = accounts.as(other, bu, "qc1");
        mvc.perform(get(C).param("fromMonth", month).param("toMonth", month).with(b))
                .andExpect(jsonPath("$.data.lines.length()").value(0)).andExpect(jsonPath("$.data.costs.length()").value(0))
                .andExpect(jsonPath("$.data.months[0].total").value(0));
        mvc.perform(json(put(C + "/costs/" + a), cost(month, "PREVENTION", "남의 것", "1", 0)).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(C + "/costs/" + a + "/delete"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select cost_item from qms_copq_cost where cost_id = ?", String.class, a)).isEqualTo("교육");

        // 직접 SQL: 이력은 mst_item 트리거로만·고치기/지우기 권한 없음, 달은 1일, 금액·단가 음수 거부
        long fg = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        constraint(comp, "ck_mst_item_price_hist_by_trigger", () -> accounts.exec(comp,
                "insert into mst_item_price_hist (comp_cd, item_id, std_price, changed_by) values (?, ?, 1, 't')", comp, fg));
        sqlState("42501", () -> accounts.exec(comp, "update mst_item_price_hist set std_price = 1"));
        sqlState("42501", () -> accounts.exec(comp, "delete from mst_item_price_hist"));
        constraint(comp, "ck_mst_item_std_price", () -> accounts.exec(comp, "update mst_item set std_price = -1 where item_id = ?", fg));
        constraint(comp, "ck_qms_copq_cost_month", () -> accounts.exec(comp, """
                insert into qms_copq_cost (comp_cd, cost_month, category, cost_item, amount, created_by, updated_by)
                values (?, date '2026-10-15', 'INTERNAL', 'x', 1, 't', 't')""", comp));
        constraint(comp, "ck_qms_copq_cost_amount", () -> accounts.exec(comp, """
                insert into qms_copq_cost (comp_cd, cost_month, category, cost_item, amount, created_by, updated_by)
                values (?, date '2026-10-01', 'INTERNAL', 'x', -1, 't', 't')""", comp));
        // B사 범위에서는 A사 이력·비용 행이 안 보인다
        assertThat(accounts.query(other, "select count(*) from mst_item_price_hist", Long.class)).isZero();
        assertThat(accounts.query(other, "select count(*) from qms_copq_cost", Long.class)).isZero();
    }
}
