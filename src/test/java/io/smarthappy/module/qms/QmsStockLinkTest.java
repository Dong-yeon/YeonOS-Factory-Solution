package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.wms.link.QualityStockLink;
import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * WMS B3 품질 상태 연동의 QMS 쪽 (docs/29_P3 21장 WB-1~3·WC-5) — 호출 4곳(판정·무효·NCR 종결·특채 결재 승인)이 같은 트랜잭션 끝에서
 * {@link QualityStockLink} 를 부르고, WMS 409 를 잡지 않아 QMS 쪽 변경(검사 상태·LOT 보류 해제·NCR 종결)이 함께 롤백되는지 —
 * 계약을 가짜 빈으로 바꿔 고정한다(재고 반영 자체는 WMS QualityStockTest). 판정 확인 문구용 stockQc(WB-3) 도.
 */
class QmsStockLinkTest extends QmsTestBase {

    static final String I = "/api/qms/insps";
    static final String N = "/api/qms/ncrs";

    @MockitoBean
    QualityStockLink link;

    @MockitoBean
    StockQuery stocks;

    long lot1;
    long insp;
    long defect;

    @BeforeEach
    void linkSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        accounts.exec(comp, "insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by) "
                + "values (?, 'D-SCR', '스크래치', 'APPEARANCE', 't', 't')", comp);
        defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'D-SCR'", Long.class);
        lot1 = accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'L-S1', item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1' returning lot_id""",
                Long.class, comp);
        // 출하검사(재고를 움직이는 유형) 하나 — 계수 5개 측정까지, 판정은 시험에서
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        insp = id(postJson(I, "{\"lotNo\":\"L-S1\",\"inspType\":\"FINAL\"}"));
        long line = accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ?", Long.class, insp);
        StringBuilder a = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            a.append(i == 1 ? "" : ",").append("{\"sampleNo\":").append(i).append(",\"okYn\":\"Y\"}");
        }
        postJson(I + "/" + insp + "/meas", "{\"inspLineId\":" + line + ",\"entries\":[" + a + "]}").andExpect(status().isOk());
    }

    int inspVer() {
        return accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, insp);
    }

    String inspState() {
        return accounts.query(comp, "select status || ':' || coalesce(result, '-') from qms_insp where insp_id = ?", String.class, insp);
    }

    int ncrVer(long ncr) {
        return accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, ncr);
    }

    String ncrStatus(long ncr) {
        return accounts.query(comp, "select status from qms_ncr where ncr_id = ?", String.class, ncr);
    }

    String lotHold() {
        return accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot1);
    }

    static BusinessException short409() {
        return new BusinessException(HbErrorCode.WMS_QUALITY_SHORT);
    }

    long ncr(String disps) throws Exception {
        long id = id(postJson(N, "{\"lotNo\":\"L-S1\",\"source\":\"CLAIM\",\"defectId\":" + defect + ",\"ncQty\":5,\"description\":\"외관\"}"));
        putJson(N + "/" + id, "{\"rowVer\":" + ncrVer(id) + ",\"defectId\":" + defect + ",\"ncQty\":5,\"description\":\"외관\",\"disps\":["
                + disps + "]}").andExpect(status().isOk());
        return id;
    }

    ResultActions apr(long aprId, RequestPostProcessor who) throws Exception {
        return mvc.perform(post("/api/platform/approvals/" + aprId + "/approve").with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    void 판정과_무효_끝에_부르고_WMS_409_면_판정_무효가_같이_롤백() throws Exception {
        // 판정: WMS 거부 → 409 그대로(코드 유지), 검사는 OPEN·행 판정도 비어 있음
        doThrow(short409()).when(link).onInspJudged(insp);
        postJson(I + "/" + insp + "/judge", "{\"rowVer\":" + inspVer() + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WMS_QUALITY_SHORT"));
        assertThat(inspState()).isEqualTo("OPEN:-");
        assertThat(accounts.query(comp, "select count(*) from qms_insp_line where insp_id = ? and result is not null", Integer.class, insp)).isZero();
        // WMS 받아들임 → 확정, 이 검사 id 로 한 번
        reset(link);
        postJson(I + "/" + insp + "/judge", "{\"rowVer\":" + inspVer() + "}").andExpect(status().isOk());
        assertThat(inspState()).isEqualTo("JUDGED:OK");
        verify(link).onInspJudged(insp);
        // 무효: WMS 거부(처분 반영된 LOT 등) → 409, 검사는 JUDGED 그대로
        doThrow(new BusinessException(HbErrorCode.WMS_QUALITY_DISPOSED)).when(link).onInspVoided(insp);
        postJson(I + "/" + insp + "/void", "{\"rowVer\":" + inspVer() + ",\"reason\":\"잘못 판정\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WMS_QUALITY_DISPOSED"));
        assertThat(inspState()).isEqualTo("JUDGED:OK");
        reset(link);
        postJson(I + "/" + insp + "/void", "{\"rowVer\":" + inspVer() + ",\"reason\":\"잘못 판정\"}").andExpect(status().isOk());
        assertThat(inspState()).startsWith("VOID:");
        verify(link).onInspVoided(insp);
        verify(link, never()).onNcrClosed(anyLong());
    }

    @Test
    void NCR_종결과_특채_승인_끝에_부르고_WMS_409_면_종결_승인이_같이_롤백_보류도_그대로() throws Exception {
        // 결재 없는 종결(폐기 5)
        long id = ncr("{\"rowKey\":\"a\",\"dispType\":\"SCRAP\",\"qty\":5,\"note\":\"MRB\",\"reworkLotNo\":null}");
        assertThat(lotHold()).isEqualTo("Y");
        doThrow(short409()).when(link).onNcrClosed(id);
        postJson(N + "/" + id + "/close", "{\"rowVer\":" + ncrVer(id) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WMS_QUALITY_SHORT"));
        assertThat(ncrStatus(id)).isEqualTo("OPEN");
        assertThat(lotHold()).as("보류 해제도 롤백").isEqualTo("Y");
        reset(link);
        postJson(N + "/" + id + "/close", "{\"rowVer\":" + ncrVer(id) + "}").andExpect(status().isOk());
        assertThat(ncrStatus(id)).isEqualTo("CLOSED");
        assertThat(lotHold()).isEqualTo("N");
        verify(link).onNcrClosed(id);

        // 특채(그대로 사용 5) → 결재 승인 때 WMS 거부 → 승인 자체가 거부(결재자에게 409), NCR 은 결재 중·보류 그대로
        long approver = accounts.newUser(comp, "lee", PW);
        accounts.grant(comp, approver, "QA2", "qms-ncr", "SEARCH");
        RequestPostProcessor lee = accounts.as(comp, approver, "lee");
        long id2 = ncr("{\"rowKey\":\"a\",\"dispType\":\"USE_AS_IS\",\"qty\":5,\"note\":\"고객 승인\",\"reworkLotNo\":null}");
        long aprId = id(postJson(N + "/" + id2 + "/submit", "{\"rowVer\":" + ncrVer(id2) + ",\"lines\":[{\"userId\":" + approver
                + ",\"lineType\":\"APPROVE\"}]}"));
        doThrow(short409()).when(link).onNcrClosed(id2);
        apr(aprId, lee).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_QUALITY_SHORT"));
        assertThat(ncrStatus(id2)).isEqualTo("PENDING");
        assertThat(lotHold()).isEqualTo("Y");
        mvc.perform(get(N + "/" + id2).with(lee)).andExpect(jsonPath("$.data.approval.myTurn").value(true));
        reset(link);
        apr(aprId, lee).andExpect(status().isOk());
        assertThat(ncrStatus(id2)).isEqualTo("CLOSED");
        assertThat(lotHold()).isEqualTo("N");
        verify(link).onNcrClosed(id2);
    }

    @Test
    void 검사_상세의_stockQc_는_WMS_읽기_계약에서_수입_출하만() throws Exception {
        when(stocks.lotStatusQty(lot1)).thenReturn(Map.of("QC", new BigDecimal("40"), "AVAILABLE", new BigDecimal("2")));
        mvc.perform(get(I + "/" + insp).with(admin)).andExpect(jsonPath("$.data.stockQc.QC").value(40))
                .andExpect(jsonPath("$.data.stockQc.AVAILABLE").value(2));
        // WMS 연동이 꺼졌거나(빈 맵) 없음 → 빈 맵(확인 문구 없음)
        reset(stocks);
        mvc.perform(get(I + "/" + insp).with(admin)).andExpect(jsonPath("$.data.stockQc").isEmpty());
        // 재고를 움직이지 않는 유형은 WMS 를 읽지도 않는다
        long p = plan("LAST_OFF", "FG1", null, null);
        lines(p, line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        long last = id(postJson(I, "{\"lotNo\":\"L-S1\",\"inspType\":\"LAST_OFF\"}"));
        reset(stocks);
        when(stocks.lotStatusQty(lot1)).thenReturn(Map.of("QC", BigDecimal.ONE));
        mvc.perform(get(I + "/" + last).with(admin)).andExpect(jsonPath("$.data.stockQc").isEmpty());
        verify(stocks, never()).lotStatusQty(anyLong());
    }

    @Test
    void B2_검사_로트수량_기본값은_QC_수량_NCR_상세는_BLOCKED_초과를_알린다() throws Exception {
        // 판정된 OK LOT 에 더 들어온 몫 재검사(docs/30 12.15.13): 수입·출하 검사는 비운 로트 수량 = 그 LOT 의 QC 재고(올림)
        when(stocks.lotStatusQty(lot1)).thenReturn(Map.of("QC", new BigDecimal("4.2"), "AVAILABLE", new BigDecimal("10")));
        long again = id(postJson(I, "{\"lotNo\":\"L-S1\",\"inspType\":\"FINAL\"}"));
        assertThat(accounts.query(comp, "select lot_qty from qms_insp where insp_id = ?", Long.class, again)).isEqualTo(5L);
        // 직접 준 값이 이긴다 / QC 가 없으면(연동 꺼짐·비관리) LOT 생성 수량
        long given = id(postJson(I, "{\"lotNo\":\"L-S1\",\"inspType\":\"FINAL\",\"lotQty\":30}"));
        assertThat(accounts.query(comp, "select lot_qty from qms_insp where insp_id = ?", Long.class, given)).isEqualTo(30L);
        reset(stocks);
        long plain = id(postJson(I, "{\"lotNo\":\"L-S1\",\"inspType\":\"FINAL\"}"));
        assertThat(accounts.query(comp, "select lot_qty from qms_insp where insp_id = ?", Long.class, plain)).isEqualTo(100L);
        // 재고를 움직이지 않는 유형은 WMS 를 읽지 않는다
        long p = plan("LAST_OFF", "FG1", null, null);
        lines(p, line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        reset(stocks);
        when(stocks.lotStatusQty(lot1)).thenReturn(Map.of("QC", BigDecimal.ONE));
        long last = id(postJson(I, "{\"lotNo\":\"L-S1\",\"inspType\":\"LAST_OFF\"}"));
        assertThat(accounts.query(comp, "select lot_qty from qms_insp where insp_id = ?", Long.class, last)).isEqualTo(100L);
        verify(stocks, never()).lotStatusQty(anyLong());
        reset(stocks);

        // NCR 상세: 처리 중이면 LOT BLOCKED 재고를 내려준다(화면이 부적합 수량보다 많을 때 경고), 종결 뒤면 없음
        long id = ncr("{\"rowKey\":\"a\",\"dispType\":\"SCRAP\",\"qty\":5,\"note\":\"MRB\",\"reworkLotNo\":null}");
        when(stocks.lotStatusQty(lot1)).thenReturn(Map.of("BLOCKED", new BigDecimal("9")));
        mvc.perform(get(N + "/" + id).with(admin)).andExpect(jsonPath("$.data.blockedQty").value(9))
                .andExpect(jsonPath("$.data.ncr.ncQty").value(5));
        postJson(N + "/" + id + "/close", "{\"rowVer\":" + ncrVer(id) + "}").andExpect(status().isOk());
        mvc.perform(get(N + "/" + id).with(admin)).andExpect(jsonPath("$.data.blockedQty").doesNotExist());
    }

    @Test
    void WMS_없는_설치본은_NONE_아무것도_안_함() {
        assertThatCode(() -> {
            QualityStockLink.NONE.onInspJudged(1);
            QualityStockLink.NONE.onInspVoided(1);
            QualityStockLink.NONE.onNcrClosed(1);
        }).doesNotThrowAnyException();
        assertThat(StockQuery.NONE.lotStatusQty(1)).isEmpty();
    }
}
