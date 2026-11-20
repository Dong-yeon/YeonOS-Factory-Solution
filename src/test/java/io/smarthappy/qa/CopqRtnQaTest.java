package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.poi.ss.usermodel.DataFormatter;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.qms.link.NcrPort;
import io.smarthappy.module.qms.link.NcrPort.ClaimRequest;
import io.smarthappy.module.qms.link.NcrPort.NcrView;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA 2026-11-02 QMS COPQ 고객 반품 출처(#752, docs/29_P3 40장) — 반품 NCR(RTN_)과 일반 NCR(NCR_ — 공정·손 클레임)이 섞인 달의
 * 합계 = 줄 합 = 손 계산, 엑셀 금액 합 = 화면 합계, 취소된 반품 NCR·반품(RETURN) 처분은 세지 않는다. 결과는 docs/qa/2026-11-02_link-pmsdep-copq.md.
 */
@AutoConfigureMockMvc
class CopqRtnQaTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String C = "/api/qms/copq";
    private static final String N = "/api/qms/ncrs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    NcrPort port;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    String comp;
    RequestPostProcessor admin;
    long defect;
    String month;
    int ref;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CP");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, u, "QA", "qms-copq", "SEARCH", "EXCEL");
        admin = accounts.as(comp, u, "admin1");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_NCR_AUTO_HOLD', 'N', 't', 't')",
                comp);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG1', 'FG1 품목', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        accounts.exec(comp, "update mst_item set std_price = 1000, updated_by = 't' where item_cd = 'FG1'");
        defect = accounts.query(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-Q', '찍힘', 'APPEARANCE', 't', 't') returning defect_id""", Long.class, comp);
        month = YearMonth.from(LocalDate.now(ZoneId.of("Asia/Seoul"))).toString();
    }

    private ResultActions send(MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin));
    }

    private long lot(String no) {
        return accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1' returning lot_id""", Long.class, comp, no);
    }

    private int ver(long ncr) {
        return accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, ncr);
    }

    private <T> T tx(Supplier<T> body) {
        TransactionTemplate t = new TransactionTemplate(txm);
        return runner.call(comp, () -> t.execute(st -> body.get()));
    }

    /** 처리 저장 → 종결. disps = "SCRAP:3,SORT:1" */
    private void dispose(long id, String qty, String disps) throws Exception {
        StringBuilder d = new StringBuilder();
        int k = 0;
        for (String part : disps.split(",")) {
            String[] x = part.split(":");
            d.append(k == 0 ? "" : ",").append("{\"rowKey\":\"k").append(k++).append("\",\"dispType\":\"").append(x[0]).append("\",\"qty\":")
                    .append(x[1]).append(",\"note\":\"MRB\",\"reworkLotNo\":null}");
        }
        MvcResult m = send(put(N + "/" + id), "{\"rowVer\":" + ver(id) + ",\"defectId\":" + defect + ",\"ncQty\":" + qty
                + ",\"description\":\"QA\",\"disps\":[" + d + "]}").andReturn();
        assertThat(m.getResponse().getStatus()).as(m.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult c = send(post(N + "/" + id + "/close"), "{\"rowVer\":" + ver(id) + "}").andReturn();
        assertThat(c.getResponse().getStatus()).as(c.getResponse().getContentAsString()).isEqualTo(200);
    }

    private long handNcr(String lotNo, String source, String qty, String disps) throws Exception {
        lot(lotNo);
        MvcResult m = send(post(N), "{\"lotNo\":\"" + lotNo + "\",\"source\":\"" + source + "\",\"defectId\":" + defect + ",\"ncQty\":" + qty
                + ",\"description\":\"QA\"}").andReturn();
        long id = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        dispose(id, qty, disps);
        return id;
    }

    /** 고객 반품 NCR(계약 — WMS 반품 입고가 부르는 것과 같은 길) */
    private NcrView rtnNcr(String lotNo, String qty) {
        long lot = lot(lotNo);
        int r = ++ref;
        return tx(() -> port.createForClaim(new ClaimRequest(NcrPort.WMS_CUST_RTN, 9000 + r, "CRT-QA-" + r, lot, new BigDecimal(qty), null, "wms1"))
                .orElseThrow());
    }

    private static BigDecimal dec(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }

    @Test
    void 반품_NCR_과_일반_NCR_이_섞인_달_합계_줄_엑셀이_모두_같다() throws Exception {
        handNcr("L-P1", "PROCESS", "3", "SCRAP:3");                       // 내부 NCR_SCRAP 3 × 1000
        handNcr("L-C1", "CLAIM", "2", "SCRAP:2");                         // 손 클레임 = 외부 NCR_SCRAP 2 × 1000
        NcrView r1 = rtnNcr("L-R1", "2");
        dispose(r1.ncrId(), "2", "SCRAP:1,SORT:1");                       // 외부 RTN_SCRAP 1 · RTN_SORT 1
        NcrView r2 = rtnNcr("L-R2", "2");
        dispose(r2.ncrId(), "2", "SCRAP:2");                              // 외부 RTN_SCRAP +2(같은 줄로 묶임)
        NcrView r3 = rtnNcr("L-R3", "2");
        dispose(r3.ncrId(), "2", "SCRAP:1,RETURN:1");                     // RTN_SCRAP +1, RETURN 은 비용 아님
        NcrView r4 = rtnNcr("L-R4", "5");
        assertThat(tx(() -> port.cancelForClaim(NcrPort.WMS_CUST_RTN, 9004, "잘못 받음", "wms1"))).isTrue();   // 취소 = 세지 않음
        assertThat(accounts.query(comp, "select status from qms_ncr where ncr_id = ?", String.class, r4.ncrId())).isEqualTo("CANCELLED");

        MvcResult m = mvc.perform(get(C).param("fromMonth", month).param("toMonth", month).with(admin)).andExpect(status().isOk()).andReturn();
        String js = m.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(dec(JsonPath.read(js, "$.data.months[0].internal"))).isEqualByComparingTo("3000");
        assertThat(dec(JsonPath.read(js, "$.data.months[0].external"))).isEqualByComparingTo("7000");
        assertThat(dec(JsonPath.read(js, "$.data.months[0].total"))).isEqualByComparingTo("10000");
        List<Map<String, Object>> lines = JsonPath.read(js, "$.data.lines");
        assertThat(lines).extracting(l -> l.get("category") + ":" + l.get("source") + ":" + dec(l.get("qty")).stripTrailingZeros().toPlainString()
                + "=" + dec(l.get("amount")).stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("INTERNAL:NCR_SCRAP:3=3000", "EXTERNAL:NCR_SCRAP:2=2000", "EXTERNAL:RTN_SCRAP:4=4000",
                        "EXTERNAL:RTN_SORT:1=1000");
        BigDecimal lineSum = lines.stream().map(l -> dec(l.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lineSum).isEqualByComparingTo("10000");
        // 반품 줄의 NCR 번호 = 반품 NCR 셋(취소 빠짐), 일반 줄에는 반품 NCR 없음
        String rtnNos = lines.stream().filter(l -> "RTN_SCRAP".equals(l.get("source"))).map(l -> (String) l.get("ncrNos")).findFirst().orElseThrow();
        assertThat(rtnNos.split(",")).containsExactlyInAnyOrder(r1.ncrNo(), r2.ncrNo(), r3.ncrNo());
        assertThat(lines.stream().filter(l -> ((String) l.get("source")).startsWith("NCR_")).map(l -> (String) l.get("ncrNos")))
                .noneMatch(s -> s.contains(r1.ncrNo()) || s.contains(r4.ncrNo()));

        // 엑셀: 원장 줄 4, 금액 열 합 = 화면 합계, 출처는 문구(코드 아님)
        byte[] x = send(post(C + "/export"), "{\"fromMonth\":\"" + month + "\",\"toMonth\":\"" + month + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<Row> rows = new ArrayList<>();
        BigDecimal sheetSum = BigDecimal.ZERO;
        DataFormatter f = new DataFormatter();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet sh = wb.getSheetAt(0);
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                if (sh.getRow(i) != null) {
                    rows.add(sh.getRow(i));
                }
            }
            for (Row r : rows) {
                sheetSum = sheetSum.add(BigDecimal.valueOf(r.getCell(10).getNumericCellValue()));
            }
            assertThat(rows).hasSize(4);
            assertThat(sheetSum).isEqualByComparingTo("10000");
            assertThat(rows).extracting(r -> f.formatCellValue(r.getCell(3))).allMatch(s -> !s.isBlank() && !s.startsWith("RTN_") && !s.startsWith("NCR_"));
            assertThat(rows).extracting(r -> f.formatCellValue(r.getCell(2)) + "|" + f.formatCellValue(r.getCell(3))).doesNotHaveDuplicates();   // 반품 폐기 ≠ 클레임 폐기
        }
    }
}
