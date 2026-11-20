package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.production.link.LotHoldPort;
import io.smarthappy.module.qms.link.NcrPort;
import io.smarthappy.module.qms.link.NcrPort.ClaimRequest;
import io.smarthappy.module.qms.link.NcrPort.NcrView;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * docs/29_P3 38장 고객 반품 → 클레임 NCR 계약 NcrPort — 만들기(CLAIM·근거·불량 유형 비움·보류 정책), 멱등·동시, 입력, 상태 읽기, 근거 취소(R-6),
 * 불량 유형 없는 상신·종결 거부, A사로 B사, NONE. 39장 — NCR 목록 근거 필터·그리드 근거 칸·홈 카드 불량 유형 미지정 수. 40장 — COPQ 원장 줄 출처 RTN_.
 */
class NcrPortTest extends QmsTestBase {

    @Autowired
    NcrPort port;

    @Autowired
    LotHoldPort holds;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    QmsHomeMapper homeMapper;

    long lot;

    @BeforeEach
    void portSetUp() {
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        lot = lot(comp, "L-RTN-1");
    }

    long lot(String c, String no) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1' returning lot_id""", Long.class, c, no);
    }

    <T> T tx(String c, Supplier<T> body) {
        TransactionTemplate t = new TransactionTemplate(txm);
        return runner.call(c, () -> t.execute(st -> body.get()));
    }

    static ClaimRequest req(long refId, long lotId, String qty) {
        return new ClaimRequest(NcrPort.WMS_CUST_RTN, refId, "RT2611-" + refId, lotId, new BigDecimal(qty), null, "wms1");
    }

    @Test
    void 만들기는_CLAIM_근거_불량유형_비움_정책_Y_면_LOT_보류_같은_근거는_같은_NCR() throws Exception {
        policy("QMS_NCR_AUTO_HOLD", "Y");
        NcrView v = tx(comp, () -> port.createForClaim(req(11, lot, "3")).orElseThrow());
        assertThat(v.status()).isEqualTo("OPEN");
        assertThat(v.ncrNo()).startsWith("NC");
        assertThat(accounts.query(comp, "select source || '|' || src_type || '|' || src_id || '|' || nc_qty::numeric(18,0) || '|' || unit_cd || '|' "
                + "|| coalesce(defect_id::text, '-') || '|' || description || '|' || hold_yn || '|' || created_by from qms_ncr where ncr_id = ?",
                String.class, v.ncrId())).isEqualTo("CLAIM|WMS_CUST_RTN|11|3|EA|-|고객 반품 RT2611-11|Y|wms1");
        assertThat(tx(comp, () -> holds.holds(lot))).extracting(LotHoldPort.HoldView::refId).containsExactly(v.ncrId());
        // 같은 근거 다시 = 같은 NCR(수량이 달라도 처음 것), 새로 만들지 않는다
        NcrView again = tx(comp, () -> port.createForClaim(req(11, lot, "5")).orElseThrow());
        assertThat(again).isEqualTo(v);
        assertThat(accounts.query(comp, "select count(*) from qms_ncr where src_id = 11", Long.class)).isEqualTo(1L);
        // 정책 N 이면 보류 없음
        accounts.exec(comp, "update sys_policy set policy_value = 'N' where policy_key = 'QMS_NCR_AUTO_HOLD'");
        long lot2 = lot(comp, "L-RTN-2");
        NcrView n2 = tx(comp, () -> port.createForClaim(new ClaimRequest(NcrPort.WMS_CUST_RTN, 12, "RT-12", lot2, new BigDecimal("1.5"), " 찍힘 반품 ",
                "wms1")).orElseThrow());
        assertThat(accounts.query(comp, "select hold_yn || '|' || description from qms_ncr where ncr_id = ?", String.class, n2.ncrId()))
                .isEqualTo("N|찍힘 반품");
        assertThat(tx(comp, () -> holds.holds(lot2))).isEmpty();
        // 화면: 목록·상세에 불량 유형 없는 NCR 도 보이고, 상세 안내용 근거 종류가 온다
        mvc.perform(get("/api/qms/ncrs/" + v.ncrId()).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ncr.srcType").value("WMS_CUST_RTN")).andExpect(jsonPath("$.data.ncr.defectId").doesNotExist());
        mvc.perform(get("/api/qms/ncrs").param("status", "OPEN_ALL").with(admin)).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void 같은_근거_동시_두_건도_NCR_하나() throws Exception {
        policy("QMS_NCR_AUTO_HOLD", "N");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<NcrView> a = pool.submit(() -> tx(comp, () -> port.createForClaim(req(21, lot, "1")).orElseThrow()));
            Future<NcrView> b = pool.submit(() -> tx(comp, () -> port.createForClaim(req(21, lot, "1")).orElseThrow()));
            assertThat(a.get().ncrId()).isEqualTo(b.get().ncrId());
        } finally {
            pool.shutdown();
        }
        assertThat(accounts.query(comp, "select count(*) from qms_ncr where src_id = 21", Long.class)).isEqualTo(1L);
    }

    @Test
    void 입력_검사와_트랜잭션_밖_호출() {
        for (ClaimRequest bad : List.of(req(31, 999_999_999L, "1"), req(31, lot, "0"), req(31, lot, "-1"), req(31, lot, "1.2345"),
                new ClaimRequest("SRM_RTN", 31, "x", lot, BigDecimal.ONE, null, "wms1"),
                new ClaimRequest(NcrPort.WMS_CUST_RTN, 31, "x", lot, BigDecimal.ONE, "가".repeat(2001), "wms1"),
                new ClaimRequest(NcrPort.WMS_CUST_RTN, 31, "x", lot, BigDecimal.ONE, null, " "))) {
            Throwable t = catchThrowable(() -> tx(comp, () -> port.createForClaim(bad)));
            assertThat(t).as(String.valueOf(bad)).isInstanceOf(BusinessException.class);
        }
        assertThat(((BusinessException) catchThrowable(() -> tx(comp, () -> port.createForClaim(req(31, 999_999_999L, "1"))))).fieldErrors()
                .get(0).field()).isEqualTo("lotId");
        // 부르는 쪽 트랜잭션 없이 = 호출 코드 오류(MANDATORY — 회사 범위를 열기 전에 막힌다)
        assertThat(catchThrowable(() -> port.createForClaim(req(32, lot, "1")))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(accounts.query(comp, "select count(*) from qms_ncr", Long.class)).isZero();
    }

    @Test
    void 상태_읽기와_근거_취소는_OPEN_일_때만() throws Exception {
        policy("QMS_NCR_AUTO_HOLD", "Y");
        NcrView v = tx(comp, () -> port.createForClaim(req(41, lot, "2")).orElseThrow());
        assertThat(tx(comp, () -> port.ncrStatus(Arrays.asList(v.ncrId(), null, v.ncrId(), 999_999_999L)))).containsExactly(v);
        assertThat(catchThrowable(() -> tx(comp, () -> port.ncrStatus(LongStream.rangeClosed(1, NcrPort.MAX_IDS + 1).boxed().toList()))))
                .isInstanceOf(IllegalArgumentException.class);
        // 없는 근거 = false, OPEN 이면 취소·보류 해제
        assertThat(tx(comp, () -> port.cancelForClaim(NcrPort.WMS_CUST_RTN, 49, "잘못 받음", "wms1"))).isFalse();
        assertThat(tx(comp, () -> port.cancelForClaim(NcrPort.WMS_CUST_RTN, 41, "잘못 받음", "wms1"))).isTrue();
        assertThat(tx(comp, () -> port.ncrStatus(List.of(v.ncrId())))).extracting(NcrView::status).containsExactly("CANCELLED");
        assertThat(tx(comp, () -> holds.holds(lot))).isEmpty();
        assertThat(accounts.query(comp, "select cancel_reason from qms_ncr where ncr_id = ?", String.class, v.ncrId()))
                .isEqualTo("근거 취소(WMS_CUST_RTN): 잘못 받음");
        // 이미 취소 = 409(두 번 취소하지 않는다), 같은 근거로 다시 만들면 취소된 그 NCR(멱등 — 새 반품은 새 id)
        Throwable twice = catchThrowable(() -> tx(comp, () -> port.cancelForClaim(NcrPort.WMS_CUST_RTN, 41, "x", "wms1")));
        assertThat(((BusinessException) twice).code()).isEqualTo(HbErrorCode.QMS_NCR_STATE);
        assertThat(tx(comp, () -> port.createForClaim(req(41, lot, "2")).orElseThrow()).status()).isEqualTo("CANCELLED");
        // 종결된 NCR = 409 — 불량 유형·처분을 채워 화면 경로로 종결
        NcrView c = tx(comp, () -> port.createForClaim(req(42, lot, "1")).orElseThrow());
        long defect = accounts.query(comp, "insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by) "
                + "values (?, 'D-RT', '찍힘', 'APPEARANCE', 't', 't') returning defect_id", Long.class, comp);
        int rv = accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, c.ncrId());
        putJson("/api/qms/ncrs/" + c.ncrId(), "{\"rowVer\":" + rv + ",\"defectId\":" + defect + ",\"ncQty\":1,\"description\":\"반품\","
                + "\"disps\":[{\"rowKey\":\"k0\",\"dispType\":\"SCRAP\",\"qty\":1,\"note\":\"MRB\",\"reworkLotNo\":null}]}")
                .andExpect(status().isOk());
        int rv2 = accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, c.ncrId());
        postJson("/api/qms/ncrs/" + c.ncrId() + "/close", "{\"rowVer\":" + rv2 + "}").andExpect(status().isOk());
        Throwable closed = catchThrowable(() -> tx(comp, () -> port.cancelForClaim(NcrPort.WMS_CUST_RTN, 42, "x", "wms1")));
        assertThat(((BusinessException) closed).code()).isEqualTo(HbErrorCode.QMS_NCR_STATE);
        // 사유 없는 취소 = 칸 오류
        assertThat(catchThrowable(() -> tx(comp, () -> port.cancelForClaim(NcrPort.WMS_CUST_RTN, 42, " ", "wms1"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 불량_유형_없이는_상신_종결이_안_되고_근거_칸은_고정() {
        policy("QMS_NCR_AUTO_HOLD", "N");
        NcrView v = tx(comp, () -> port.createForClaim(req(51, lot, "1")).orElseThrow());
        accounts.exec(comp, "insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values (?, ?, 1, 'SCRAP', 1, 't')", comp,
                v.ncrId());
        constraint(comp, "ck_qms_ncr_defect_required", () -> accounts.exec(comp,
                "update qms_ncr set status = 'CLOSED', closed_at = now(), closed_by = 't' where ncr_id = ?", v.ncrId()));
        constraint(comp, "ck_qms_ncr_immutable", () -> accounts.exec(comp, "update qms_ncr set src_id = 99 where ncr_id = ?", v.ncrId()));
        // 손 NCR 은 불량 유형 없이 못 만든다(근거 없음)
        constraint(comp, "ck_qms_ncr_defect_src", () -> accounts.exec(comp, """
                insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, nc_qty, description, occurred_at, hold_yn, created_by, updated_by)
                select ?, 'NC-X', 'PROCESS', lot_id, item_id, unit_cd, 1, 'x', now(), 'N', 't', 't' from lot_master where lot_id = ?""", comp, lot));
        // 근거는 CLAIM 만(근거 수량 src_qty 는 채움 — 비우면 트리거 ck_qms_ncr_src_qty_required 가 먼저, V236)
        constraint(comp, "ck_qms_ncr_src_claim", () -> accounts.exec(comp, """
                insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, nc_qty, description, occurred_at, hold_yn, src_type, src_id,
                                     src_qty, created_by, updated_by)
                select ?, 'NC-Y', 'PROCESS', lot_id, item_id, unit_cd, 1, 'x', now(), 'N', 'WMS_CUST_RTN', 52, 1, 't', 't' from lot_master where lot_id = ?""",
                comp, lot));
    }

    @Test
    void 목록_근거_필터_그리드_근거_칸_홈_카드_불량_유형_미지정() throws Exception {
        policy("QMS_NCR_AUTO_HOLD", "N");
        NcrView rtn = tx(comp, () -> port.createForClaim(req(71, lot, "1")).orElseThrow());
        long defect = accounts.query(comp, "insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by) "
                + "values (?, 'D-B', '버', 'APPEARANCE', 't', 't') returning defect_id", Long.class, comp);
        long manual = id(postJson("/api/qms/ncrs", "{\"lotNo\":\"L-RTN-1\",\"source\":\"PROCESS\",\"defectId\":" + defect
                + ",\"ncQty\":1,\"description\":\"공정\"}"));
        String manualNo = accounts.query(comp, "select ncr_no from qms_ncr where ncr_id = ?", String.class, manual);
        mvc.perform(get("/api/qms/ncrs").param("basis", "CUST_RTN").with(admin)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].ncrNo").value(rtn.ncrNo()));
        mvc.perform(get("/api/qms/ncrs").param("basis", "NO_DEFECT").with(admin)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].ncrNo").value(rtn.ncrNo()));
        mvc.perform(get("/api/qms/ncrs").with(admin)).andExpect(jsonPath("$.data.length()").value(2));
        // 모르는 값은 0건(조용히 전체를 보이지 않는다)
        mvc.perform(get("/api/qms/ncrs").param("basis", "XX").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        // 그리드: 같은 조건·총수, 근거 칸 값
        mvc.perform(get("/api/qms/ncrs/grid").param("basis", "CUST_RTN").param("total", "true").with(admin))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].srcType").value("WMS_CUST_RTN"));
        mvc.perform(get("/api/qms/ncrs/grid").param("keyword", manualNo).with(admin)).andExpect(jsonPath("$.list[0].srcType").doesNotExist());
        // 홈 카드: 열린 2건 중 불량 유형 미지정 1건
        QmsHomeMapper.NcrOpenSummary sum = tx(comp, () -> homeMapper.ncrOpenSummary());
        assertThat(sum.openCnt()).isEqualTo(2);
        assertThat(sum.noDefectCnt()).isEqualTo(1);
        // A사로 B사: B사 목록·홈 카드에 A사 반품 NCR 없음
        String other = accounts.newComp("QB");
        fixtures(other);
        assertThat(tx(other, () -> homeMapper.ncrOpenSummary()).noDefectCnt()).isZero();
    }

    @Test
    void COPQ_원장_줄_출처는_고객_반품_NCR_이면_RTN_구분은_외부_실패() throws Exception {
        policy("QMS_NCR_AUTO_HOLD", "N");
        accounts.grant(comp, userId, "QA", "qms-copq", "SEARCH");
        NcrView v = tx(comp, () -> port.createForClaim(req(81, lot, "2")).orElseThrow());
        long defect = accounts.query(comp, "insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by) "
                + "values (?, 'D-C', '찍힘', 'APPEARANCE', 't', 't') returning defect_id", Long.class, comp);
        int rv = accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, v.ncrId());
        putJson("/api/qms/ncrs/" + v.ncrId(), "{\"rowVer\":" + rv + ",\"defectId\":" + defect + ",\"ncQty\":2,\"description\":\"반품\","
                + "\"disps\":[{\"rowKey\":\"k0\",\"dispType\":\"SCRAP\",\"qty\":2,\"note\":\"MRB\",\"reworkLotNo\":null}]}")
                .andExpect(status().isOk());
        int rv2 = accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, v.ncrId());
        postJson("/api/qms/ncrs/" + v.ncrId() + "/close", "{\"rowVer\":" + rv2 + "}").andExpect(status().isOk());
        String month = java.time.YearMonth.from(today).toString();
        mvc.perform(get("/api/qms/copq").param("fromMonth", month).param("toMonth", month).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines.length()").value(1))
                .andExpect(jsonPath("$.data.lines[0].source").value("RTN_SCRAP"))
                .andExpect(jsonPath("$.data.lines[0].category").value("EXTERNAL"))
                .andExpect(jsonPath("$.data.lines[0].ncrNos").value(v.ncrNo()));
    }

    @Test
    void A사로_B사와_NONE() {
        policy("QMS_NCR_AUTO_HOLD", "N");
        NcrView v = tx(comp, () -> port.createForClaim(req(61, lot, "1")).orElseThrow());
        String other = accounts.newComp("QN");
        fixtures(other);
        // B사 범위에서 A사 LOT → 칸 오류, A사 NCR 상태 → 빠짐, A사 근거로 취소 → 못 찾음(false)
        Throwable t = catchThrowable(() -> tx(other, () -> port.createForClaim(req(62, lot, "1"))));
        assertThat(((BusinessException) t).fieldErrors().get(0).field()).isEqualTo("lotId");
        assertThat(tx(other, () -> port.ncrStatus(List.of(v.ncrId())))).isEmpty();
        assertThat(tx(other, () -> port.cancelForClaim(NcrPort.WMS_CUST_RTN, 61, "x", "wms1"))).isFalse();
        // 같은 근거 id 라도 회사가 다르면 다른 NCR
        long bLot = lot(other, "L-RTN-B");
        NcrView b = tx(other, () -> port.createForClaim(req(61, bLot, "1")).orElseThrow());
        assertThat(b.ncrId()).isNotEqualTo(v.ncrId());
        assertThat(accounts.query(comp, "select status from qms_ncr where ncr_id = ?", String.class, v.ncrId())).isEqualTo("OPEN");
        // NONE
        assertThat(NcrPort.NONE.createForClaim(req(1, 1, "1"))).isEmpty();
        assertThat(NcrPort.NONE.ncrStatus(List.of(1L))).isEmpty();
        assertThat(NcrPort.NONE.cancelForClaim(NcrPort.WMS_CUST_RTN, 1, "x", "y")).isFalse();
    }
}
