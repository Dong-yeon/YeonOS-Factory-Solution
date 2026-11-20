package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.module.qms.link.SupplierQualityQuery.ItemQuality;
import io.smarthappy.module.qms.link.SupplierQualityQuery.NcrDetail;
import io.smarthappy.module.qms.link.SupplierQualityQuery.SupplierReturn;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * QMS 공개 계약 협력사 품질 조회 (docs/29_P3 16장, docs/31 4.12.2) — SRM 수입 PPM·반품·클레임이 읽는 값.
 * 품목·단위별 검사 수량·불량 수량(NCR − 특채)·판정 수·처리 중 NCR·검사 없는 NCR / 다시 검사하면 마지막 판정만 / 무효 판정·취소 NCR 제외 /
 * 반품 = 닫힌 NCR 의 RETURN 줄만 / NCR 한 건 / 기간 = 회사 날짜·양끝 포함·잘못된 구간은 빈 값 / A사로 B사 / 예외 없음(rollback-only 아님).
 */
class SupplierQualityQueryTest extends QmsTestBase {

    static final String I = "/api/qms/insps";
    static final String N = "/api/qms/ncrs";

    @Autowired
    SupplierQualityQuery query;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    long s1;
    long s2;
    long s3;
    long defect;

    @BeforeEach
    void receiptSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        s1 = cust(comp, "SUP1");
        s2 = cust(comp, "SUP2");
        s3 = cust(comp, "SUP3");
        defect = accounts.query(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-RCV', '녹', 'APPEARANCE', 't', 't') returning defect_id""", Long.class, comp);
        // 수입검사 확정본: FG1 × IQC 공정 "녹 없음"(계수, 고정 5) / 출하검사 확정본도(수입검사 아닌 판정은 빠지는지)
        long p = plan("RECEIVE", "FG1", null, "IQC");
        lines(p, line("a", null, 10, iqcChar, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        long f = plan("FINAL", "FG1", null, null);
        lines(f, line("b", null, 10, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(f, "release").andExpect(status().isOk());
    }

    long cust(String c, String cd) {
        return accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, cd, cd + " 협력사");
    }

    long lot(String c, String no, long supplier, String qty) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, supplier_cust_id, supplier_lot_no, occurred_at,
                                        created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', ?::numeric, 'EA', ?, 'S' || ?, now(), 't', 't' from mst_item where item_cd = 'FG1' returning lot_id""",
                Long.class, c, no, qty, supplier, no);
    }

    /** 검사 하나 만들고 5개 모두 OK 또는 하나 NG 로 판정 확정 → 검사 번호 */
    String judged(String lotNo, String type, boolean ok) throws Exception {
        long id = id(postJson(I, "{\"lotNo\":" + q(lotNo) + ",\"inspType\":" + q(type) + ("RECEIVE".equals(type) ? ",\"procCd\":\"IQC\"" : "") + "}"));
        long line = accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ?", Long.class, id);
        StringBuilder a = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            a.append(i == 1 ? "" : ",").append("{\"sampleNo\":").append(i).append(",\"okYn\":\"").append(!ok && i == 1 ? "N" : "Y").append("\"}");
        }
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line + ",\"entries\":[" + a + "]}").andExpect(status().isOk());
        postJson(I + "/" + id + "/judge", "{\"rowVer\":" + ver(id) + "}").andExpect(status().isOk());
        return accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, id);
    }

    int ver(long insp) {
        return accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, insp);
    }

    /** inspNo 가 null 이면 고객 클레임(검사 없는 NCR) */
    long ncr(String lotNo, String inspNo, String qty) throws Exception {
        return id(postJson(N, "{\"lotNo\":" + q(lotNo) + ",\"source\":" + q(inspNo == null ? "CLAIM" : "INSP") + ",\"inspNo\":" + q(inspNo)
                + ",\"defectId\":" + defect + ",\"ncQty\":" + qty + ",\"description\":\"녹 발생\"}"));
    }

    int nver(long ncr) {
        return accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, ncr);
    }

    /** 처리 줄 통째 저장 — disps = "유형:수량" 들 */
    void disps(long ncr, String qty, String... disps) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < disps.length; i++) {
            String[] d = disps[i].split(":");
            b.append(i == 0 ? "" : ",").append("{\"rowKey\":\"k").append(i).append("\",\"dispType\":\"").append(d[0]).append("\",\"qty\":")
                    .append(d[1]).append(",\"note\":\"MRB\"}");
        }
        putJson(N + "/" + ncr, "{\"rowVer\":" + nver(ncr) + ",\"defectId\":" + defect + ",\"ncQty\":" + qty
                + ",\"description\":\"녹 발생\",\"disps\":[" + b + "]}").andExpect(status().isOk());
    }

    void close(long ncr) throws Exception {
        postJson(N + "/" + ncr + "/close", "{\"rowVer\":" + nver(ncr) + "}").andExpect(status().isOk());
    }

    /** 판정 시각 옮기기(고정 칸 트리거를 건너뛰려고 소유자·replica) */
    static void ownerExec(String sql, Object... args) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
        }
    }

    <T> T tx(String c, Supplier<T> s) {
        TransactionTemplate t = new TransactionTemplate(txm);
        return runner.call(c, () -> t.execute(st -> s.get()));
    }

    static String row(ItemQuality q) {
        return q.itemCd() + " " + q.unitCd() + " " + n(q.inspectedQty()) + "/" + n(q.rejectedQty()) + " " + q.inspectionCnt() + "/" + q.ngCnt()
                + " open" + q.openNcrCnt() + " other" + n(q.otherNcQty());
    }

    static String n(java.math.BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    @Test
    void 수입검사_품질_불량은_NCR빼기_특채_다시검사는_마지막만_반품은_닫힌_RETURN_줄() throws Exception {
        lot(comp, "R-A", s1, "100");
        lot(comp, "R-B", s1, "50.5");
        lot(comp, "R-C", s1, "20");
        lot(comp, "R-D", s2, "30");
        lot(comp, "R-G", s3, "40");
        judged("R-A", "RECEIVE", true);
        String inspB = judged("R-B", "RECEIVE", false);
        // R-B: NCR 10(특채 4 + 반품 6, 처리 중) → 6 / NCR 7 취소 → 빠짐 / NCR 5(반품 5, 종결) → 5
        long open = ncr("R-B", inspB, "10");
        disps(open, "10", "USE_AS_IS:4", "RETURN:6");
        long cancelled = ncr("R-B", inspB, "7");
        postJson(N + "/" + cancelled + "/cancel", "{\"rowVer\":" + nver(cancelled) + ",\"reason\":\"중복\"}").andExpect(status().isOk());
        long closed = ncr("R-B", inspB, "5");
        disps(closed, "5", "RETURN:5");
        close(closed);
        // R-C: 처음 NG(NCR 9 — 무효 판정 근거라 빠짐) → 무효 → 다시 OK(마지막 판정만)
        String firstC = judged("R-C", "RECEIVE", false);
        long firstCId = accounts.query(comp, "select insp_id from qms_insp where insp_no = ?", Long.class, firstC);
        long voidNcr = ncr("R-C", firstC, "9");
        postJson(N + "/" + voidNcr + "/cancel", "{\"rowVer\":" + nver(voidNcr) + ",\"reason\":\"재검사\"}").andExpect(status().isOk());
        postJson(I + "/" + firstCId + "/void", "{\"rowVer\":" + ver(firstCId) + ",\"reason\":\"시료 바뀜\"}").andExpect(status().isOk());
        judged("R-C", "RECEIVE", true);
        judged("R-D", "RECEIVE", false);
        // 검사 없는 NCR(고객 클레임) — R-A 2 → otherNcQty / 협력사 3 은 검사 없이 클레임만 → 검사 0 줄
        long claim = ncr("R-A", null, "2");
        ncr("R-G", null, "4");
        // 수입검사 아닌 판정(출하검사 NG)은 빠진다
        lot(comp, "R-E", s1, "999");
        judged("R-E", "FINAL", false);

        assertThat(tx(comp, () -> query.receiptQuality(s1, today, today))).extracting(SupplierQualityQueryTest::row)
                .containsExactly("FG1 EA 170.5/11 3/1 open1 other2");
        assertThat(tx(comp, () -> query.receiptQuality(s2, today, today))).extracting(SupplierQualityQueryTest::row)
                .containsExactly("FG1 EA 30/0 1/1 open0 other0");
        assertThat(tx(comp, () -> query.receiptQuality(s3, today, today))).extracting(SupplierQualityQueryTest::row)
                .containsExactly("FG1 EA 0/0 0/0 open0 other4");

        // 반품 = 닫힌 NCR 의 RETURN 줄만(처리 중 NCR 의 반품 6 은 아직 아님)
        List<SupplierReturn> ret = tx(comp, () -> query.returnsToSupplier(s1, today, today).rows());
        assertThat(ret).extracting(r -> r.ncrId() + ":" + r.lineNo() + ":" + r.lotNo() + ":" + r.supplierLotNo() + ":" + n(r.qty()) + ":" + r.unitCd()
                + ":" + r.defectCd() + ":" + r.poLineId()).containsExactly(closed + ":1:R-B:SR-B:5:EA:D-RCV:null");
        assertThat(ret.get(0).closedAt()).isNotNull();
        assertThat(tx(comp, () -> query.returnsToSupplier(s2, today, today).rows())).isEmpty();

        NcrDetail d = tx(comp, () -> query.ncr(closed)).orElseThrow();
        assertThat(d.status() + ":" + d.source() + ":" + d.inspNo() + ":" + n(d.ncQty()) + ":" + n(d.returnQty()) + ":" + d.supplierCustId() + ":"
                + d.description() + ":" + d.defectNm()).isEqualTo("CLOSED:INSP:" + inspB + ":5:5:" + s1 + ":녹 발생:녹");
        NcrDetail c = tx(comp, () -> query.ncr(claim)).orElseThrow();
        assertThat(c.status() + ":" + c.source() + ":" + c.inspNo() + ":" + n(c.returnQty()) + ":" + c.closedAt() + ":" + c.lotNo())
                .isEqualTo("OPEN:CLAIM:null:0:null:R-A");
        assertThat(tx(comp, () -> query.ncr(999_999_999L))).isEmpty();
    }

    @Test
    void 기간은_회사날짜_양끝포함_잘못된구간은_빈값_예외없음() throws Exception {
        lot(comp, "R-A", s1, "10");
        String insp = judged("R-A", "RECEIVE", false);
        long ncr = ncr("R-A", insp, "3");
        disps(ncr, "3", "RETURN:3");
        close(ncr);
        assertThat(tx(comp, () -> query.receiptQuality(s1, today.minusDays(3), today))).hasSize(1);
        assertThat(tx(comp, () -> query.receiptQuality(s1, today.plusDays(1), today.plusDays(5)))).isEmpty();
        assertThat(tx(comp, () -> query.receiptQuality(s1, today.minusDays(5), today.minusDays(1)))).isEmpty();
        assertThat(tx(comp, () -> query.returnsToSupplier(s1, today.minusDays(5), today.minusDays(1)).rows())).isEmpty();
        // 잘못된 구간(from > to·null·1년 넘음)은 예외 없이 빈 값 — 부르는 쪽 트랜잭션은 이어서 쓴다
        String after = tx(comp, () -> {
            assertThat(query.receiptQuality(s1, today, today.minusDays(1))).isEmpty();
            assertThat(query.receiptQuality(s1, null, today)).isEmpty();
            assertThat(query.returnsToSupplier(s1, today.minusYears(2), today).rows()).isEmpty();
            assertThat(query.returnsToSupplier(s1, today, null).rows()).isEmpty();
            assertThat(query.receiptQuality(999_999L, today, today)).isEmpty();
            assertThat(query.receiptQuality(s1, today.minusYears(1), today)).hasSize(1);   // 딱 1년은 받는다
            return "ok";   // 커밋까지 끝남 = rollback-only 아님
        });
        assertThat(after).isEqualTo("ok");
        // 경계: 판정·종결 시각을 어제 23:59(회사 시간대)로 옮기면 오늘 구간에서 빠지고 어제 구간에 든다
        String yesterday2359 = "((" + io.smarthappy.support.TestAccounts.COMP_TODAY + ")::timestamp - interval '1 minute') at time zone 'Asia/Seoul'";
        ownerExec("update qms_insp set judged_at = " + yesterday2359 + " where comp_cd = ? and insp_type = 'RECEIVE'", comp);
        ownerExec("update qms_ncr set closed_at = " + yesterday2359 + " where comp_cd = ?", comp);
        assertThat(tx(comp, () -> query.receiptQuality(s1, today, today))).isEmpty();
        assertThat(tx(comp, () -> query.returnsToSupplier(s1, today, today).rows())).isEmpty();
        assertThat(tx(comp, () -> query.receiptQuality(s1, today.minusDays(1), today.minusDays(1)))).extracting(SupplierQualityQueryTest::row)
                .containsExactly("FG1 EA 10/3 1/1 open0 other0");
        assertThat(tx(comp, () -> query.returnsToSupplier(s1, today.minusDays(1), today.minusDays(1)).rows())).hasSize(1);
    }

    @Test
    void A사로_B사_다른_회사_범위에서는_보이지_않는다() throws Exception {
        lot(comp, "R-A", s1, "10");
        String insp = judged("R-A", "RECEIVE", false);
        long ncr = ncr("R-A", insp, "3");
        disps(ncr, "3", "RETURN:3");
        close(ncr);
        String other = accounts.newComp("QR");
        assertThat(tx(other, () -> query.receiptQuality(s1, today, today))).isEmpty();
        assertThat(tx(other, () -> query.returnsToSupplier(s1, today, today).rows())).isEmpty();
        assertThat(tx(other, () -> query.ncr(ncr))).isEmpty();
        // B사 자기 협력사·LOT 은 B사 범위에서만
        fixtures(other);
        long bs = cust(other, "SUP1");
        lot(other, "R-A", bs, "5");
        assertThat(tx(comp, () -> query.receiptQuality(bs, today, today))).isEmpty();
        assertThat(tx(comp, () -> query.returnsToSupplier(bs, today, today).rows())).isEmpty();
        assertThat(SupplierQualityQuery.NONE.receiptQuality(s1, today, today)).isEmpty();
        assertThat(SupplierQualityQuery.NONE.returnsToSupplier(s1, today, today).rows()).isEmpty();
        assertThat(SupplierQualityQuery.NONE.ncr(ncr)).isEmpty();
    }
    /**
     * QQ-1(사용자 결정 = NCR 기준 집계 + 무효 경고): 근거 검사를 다시 검사(무효 없이)하거나 무효로 해도 그 LOT 의 출처 INSP NCR 은 센다.
     * 무효 때 처리 중 근거 NCR 은 막지 않고 상세(openNcrNos)로 경고, 무효 사유 끝에 번호를 남긴다.
     */
    @Test
    void 근거검사를_다시검사_무효해도_LOT_의_INSP_NCR_은_센다_무효는_경고와_이력() throws Exception {
        lot(comp, "Q-L1", s1, "100");
        lot(comp, "Q-L2", s2, "50");
        // ① L1: NG → NCR 9(처리 중) → 무효 없이 두 번째 검사 OK — 판정 1·NG 0 이지만 불량 9·처리 중 1 은 남는다
        String ng1 = judged("Q-L1", "RECEIVE", false);
        ncr("Q-L1", ng1, "9");
        judged("Q-L1", "RECEIVE", true);
        assertThat(tx(comp, () -> query.receiptQuality(s1, today, today))).extracting(SupplierQualityQueryTest::row)
                .containsExactly("FG1 EA 100/9 1/0 open1 other0");
        // ② L2: NG → NCR 6(처리 중) → 상세에 경고 번호 → 무효(막지 않음) → 사유 끝에 번호, 판정은 없어도 불량 6 은 근거 검사 판정 시각으로 센다
        String ng2 = judged("Q-L2", "RECEIVE", false);
        long n2 = ncr("Q-L2", ng2, "6");
        String n2No = accounts.query(comp, "select ncr_no from qms_ncr where ncr_id = ?", String.class, n2);
        long insp2 = accounts.query(comp, "select insp_id from qms_insp where insp_no = ?", Long.class, ng2);
        mvc.perform(get(I + "/" + insp2).with(admin)).andExpect(jsonPath("$.data.openNcrNos[0]").value(n2No))
                .andExpect(jsonPath("$.data.openNcrNos.length()").value(1));
        postJson(I + "/" + insp2 + "/void", "{\"rowVer\":" + ver(insp2) + ",\"reason\":\"시료 바뀜\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status || ':' || void_reason from qms_insp where insp_id = ?", String.class, insp2))
                .isEqualTo("VOID:시료 바뀜 [OPEN NCR " + n2No + "]");
        assertThat(tx(comp, () -> query.receiptQuality(s2, today, today))).extracting(SupplierQualityQueryTest::row)
                .containsExactly("FG1 EA 50/6 0/0 open1 other0");
        // NCR 을 취소하면 빠진다 / 근거 NCR 이 없는 무효는 사유 그대로
        postJson(N + "/" + n2 + "/cancel", "{\"rowVer\":" + nver(n2) + ",\"reason\":\"검사 무효\"}").andExpect(status().isOk());
        assertThat(tx(comp, () -> query.receiptQuality(s2, today, today))).isEmpty();
        String ok1 = accounts.query(comp, "select insp_no from qms_insp where lot_id = (select lot_id from lot_master where lot_no = 'Q-L1') "
                + "and result = 'OK'", String.class);
        long okId = accounts.query(comp, "select insp_id from qms_insp where insp_no = ?", Long.class, ok1);
        postJson(I + "/" + okId + "/void", "{\"rowVer\":" + ver(okId) + ",\"reason\":\"재검\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select void_reason from qms_insp where insp_id = ?", String.class, okId)).isEqualTo("재검");
        // 사유가 길어도 칸(500) 안
        String longReason = "가".repeat(495);
        assertThat(InspService.withOpenNcrs(longReason, List.of("NC2610-0001", "NC2610-0002"))).hasSize(500).endsWith("[OPEN NCR NC2610-0001 +1]");
        assertThat(InspService.withOpenNcrs("r", List.of("A", "B"))).isEqualTo("r [OPEN NCR A,B]");
    }

    /** QQ-2: 연도 1~9999 밖(LocalDate.MAX·DB 범위 밖)도 예외 없이 빈 값, 같은 트랜잭션 계속 / QQ-3: 반품 501 줄 → 500 줄 + truncated */
    @Test
    void 극단_날짜는_빈값_트랜잭션_계속_반품_상한은_잘림_표시() throws Exception {
        lot(comp, "Q-X", s1, "10");
        judged("Q-X", "RECEIVE", false);
        String after = tx(comp, () -> {
            assertThat(query.returnsToSupplier(s1, LocalDate.of(300_000, 1, 1), LocalDate.of(300_000, 1, 2)).rows()).isEmpty();
            assertThat(query.receiptQuality(s1, LocalDate.MAX, LocalDate.MAX)).isEmpty();
            assertThat(query.receiptQuality(s1, LocalDate.MIN, LocalDate.MIN)).isEmpty();
            assertThat(query.receiptQuality(s1, LocalDate.of(-5000, 1, 1), LocalDate.of(-5000, 1, 2))).isEmpty();
            assertThat(query.receiptQuality(s1, LocalDate.of(9999, 12, 31), LocalDate.of(9999, 12, 31))).isEmpty();   // 범위 끝은 받는다
            return row(query.receiptQuality(s1, today, today).get(0));
        });
        assertThat(after).isEqualTo("FG1 EA 10/0 1/1 open0 other0");
        // 반품 줄 501 개(닫힌 NCR 하나 — 처리 줄 상한 트리거를 건너뛰려고 소유자·replica)
        lot(comp, "Q-R", s3, "1000");
        ownerExec("""
                insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, defect_id, nc_qty, description, occurred_at, status, hold_yn,
                                     closed_at, closed_by, created_by, updated_by)
                select l.comp_cd, 'Q-NCR-R', 'CLAIM', l.lot_id, l.item_id, l.unit_cd, ?, 501, '반품', now(), 'CLOSED', 'N', now(), 't', 't', 't'
                  from lot_master l where l.comp_cd = ? and l.lot_no = 'Q-R'""", defect, comp);
        ownerExec("""
                insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by)
                select n.comp_cd, n.ncr_id, g, 'RETURN', 1, 't' from qms_ncr n, generate_series(1, 501) g where n.comp_cd = ? and n.ncr_no = 'Q-NCR-R'""",
                comp);
        SupplierQualityQuery.ReturnPage page = tx(comp, () -> query.returnsToSupplier(s3, today, today));
        assertThat(page.rows()).hasSize(SupplierQualityQuery.MAX_ROWS);
        assertThat(page.truncated()).isTrue();
        assertThat(page.rows().get(499).lineNo()).isEqualTo(500);
        ownerExec("delete from qms_ncr_disp where comp_cd = ? and line_no = 501", comp);
        page = tx(comp, () -> query.returnsToSupplier(s3, today, today));
        assertThat(page.rows()).hasSize(SupplierQualityQuery.MAX_ROWS);
        assertThat(page.truncated()).isFalse();
    }
    /** ncrsOfSupplier: 발견 시각 기준·취소 제외·처리 중/반품 없는 NCR 포함·발견 시각 순·상한 + truncated·잘못된 인자 빈 값·A사로 B사 */
    @Test
    void 협력사_NCR_목록_발견시각_취소제외_처리중포함_잘림표시() throws Exception {
        lot(comp, "N-A", s1, "10");
        lot(comp, "N-B", s2, "10");
        String insp = judged("N-A", "RECEIVE", false);
        long closed = ncr("N-A", insp, "3");
        disps(closed, "3", "RETURN:3");
        close(closed);
        long open = ncr("N-A", null, "2");                // 고객 클레임, 처리 중·반품 없음
        long cancelled = ncr("N-A", null, "1");
        postJson(N + "/" + cancelled + "/cancel", "{\"rowVer\":" + nver(cancelled) + ",\"reason\":\"중복\"}").andExpect(status().isOk());
        ncr("N-B", null, "5");                              // 다른 협력사
        SupplierQualityQuery.NcrPage page = tx(comp, () -> query.ncrsOfSupplier(s1, today, today));
        assertThat(page.truncated()).isFalse();
        assertThat(page.rows()).extracting(d -> d.ncrId() + ":" + d.status() + ":" + d.source() + ":" + n(d.returnQty()))
                .containsExactly(closed + ":CLOSED:INSP:3", open + ":OPEN:CLAIM:0");
        assertThat(page.rows().get(0).occurredAt()).isNotNull();
        assertThat(page.rows().get(0).inspNo()).isEqualTo(insp);
        assertThat(tx(comp, () -> query.ncr(open)).orElseThrow().occurredAt()).isNotNull();
        // 발견 시각 경계: 어제 23:59(회사 시간대)로 옮기면 오늘 구간 밖·어제 구간 안
        ownerExec("update qms_ncr set occurred_at = ((" + io.smarthappy.support.TestAccounts.COMP_TODAY
                + ")::timestamp - interval '1 minute') at time zone 'Asia/Seoul' where ncr_id = ?", open);
        assertThat(tx(comp, () -> query.ncrsOfSupplier(s1, today, today).rows())).extracting(SupplierQualityQuery.NcrDetail::ncrId)
                .containsExactly(closed);
        assertThat(tx(comp, () -> query.ncrsOfSupplier(s1, today.minusDays(1), today.minusDays(1)).rows()))
                .extracting(SupplierQualityQuery.NcrDetail::ncrId).containsExactly(open);
        // 잘못된 인자 빈 값·트랜잭션 계속 / A사로 B사 / NONE
        String after = tx(comp, () -> {
            assertThat(query.ncrsOfSupplier(s1, today, today.minusDays(1)).rows()).isEmpty();
            assertThat(query.ncrsOfSupplier(s1, LocalDate.MAX, LocalDate.MAX).rows()).isEmpty();
            assertThat(query.ncrsOfSupplier(s1, today.minusYears(2), today).rows()).isEmpty();
            return query.ncrsOfSupplier(s1, today, today).rows().size() + "";
        });
        assertThat(after).isEqualTo("1");
        String other = accounts.newComp("QN");
        assertThat(tx(other, () -> query.ncrsOfSupplier(s1, today, today).rows())).isEmpty();
        assertThat(SupplierQualityQuery.NONE.ncrsOfSupplier(s1, today, today).rows()).isEmpty();
        // 상한: NCR 501 건(소유자·replica) → 500 건 + truncated
        lot(comp, "N-R", s3, "1000");
        ownerExec("""
                insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, defect_id, nc_qty, description, occurred_at, status, hold_yn,
                                     created_by, updated_by)
                select l.comp_cd, 'N-' || g, 'CLAIM', l.lot_id, l.item_id, l.unit_cd, ?, 1, '클레임', now(), 'OPEN', 'N', 't', 't'
                  from lot_master l, generate_series(1, 501) g where l.comp_cd = ? and l.lot_no = 'N-R'""", defect, comp);
        SupplierQualityQuery.NcrPage big = tx(comp, () -> query.ncrsOfSupplier(s3, today, today));
        assertThat(big.rows()).hasSize(SupplierQualityQuery.MAX_ROWS);
        assertThat(big.truncated()).isTrue();
    }
}
