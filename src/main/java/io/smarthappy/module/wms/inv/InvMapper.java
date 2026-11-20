package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.wms.inv.InvDtos.ItemOpt;
import io.smarthappy.module.wms.inv.InvDtos.Mismatch;
import io.smarthappy.module.wms.inv.InvDtos.NodeOpt;
import io.smarthappy.module.wms.inv.InvDtos.ReconcileRow;
import io.smarthappy.module.wms.inv.InvDtos.StockRow;
import io.smarthappy.module.wms.inv.InvDtos.StockSearch;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.InvDtos.TrxRow;
import io.smarthappy.module.wms.inv.InvDtos.TrxSearch;

@Mapper
public interface InvMapper {

    // ---- 게시
    long insertTrx(@Param("plantCd") String plantCd, @Param("t") TrxReq t, @Param("unitCd") String unitCd,
            @Param("clientReqId") String clientReqId, @Param("reqSeq") Integer reqSeq, @Param("by") String by,
            @Param("reversalOf") Long reversalOf);

    List<Long> findReq(@Param("clientReqId") String clientReqId);

    /** 반품 출고 후보 재고(R4-4) — 그 공장·품목, LOT 관리 품목이면 그 LOT, 아니면 LOT 없는 칸. 수량 &gt; 0, 위치 경로 순 */
    List<InvDtos.RtnStock> rtnStock(@Param("plantCd") String plantCd, @Param("itemId") long itemId, @Param("lotId") Long lotId);

    /** 같은 요청 id 의 처음 원장 행 내용(PQ-1 — 사람이 보낸 요청의 다시 보냄 비교) */
    List<ReqRow> findReqRows(@Param("clientReqId") String clientReqId);

    @lombok.Getter
    @lombok.Setter
    class ReqRow {
        private int reqSeq;
        private String trxType;
        private long itemId;
        private long nodeId;
        private String stockStatus;
        private java.math.BigDecimal qty;
        private Long lotId;
        private String srcType;
    }

    TrxRow findTrx(@Param("id") long id);

    List<TrxRow> findMoveGroup(@Param("moveGrpId") java.util.UUID moveGrpId);

    // ---- 참조 확인
    ItemOpt findItem(@Param("id") long id);

    ItemOpt findItemByCode(@Param("itemCd") String itemCd);

    /** LOT 번호 → LOT(품목 정보 포함). P2 lot_master 읽기만 */
    InvDtos.LotOpt findLotByNo(@Param("lotNo") String lotNo);

    NodeOpt findNode(@Param("id") long id, @Param("plantCd") String plantCd);

    NodeOpt findNodeByCode(@Param("plantCd") String plantCd, @Param("nodeCd") String nodeCd);

    String nodePlant(@Param("id") long id);

    boolean plantExists(@Param("plantCd") String plantCd);

    boolean codeUsable(@Param("grpCd") String grpCd, @Param("code") String code);

    String mixItemYn(@Param("nodeId") long nodeId);

    boolean otherItemAt(@Param("nodeId") long nodeId, @Param("itemId") long itemId);

    BigDecimal stockQty(@Param("itemId") long itemId, @Param("nodeId") long nodeId, @Param("status") String status);

    /** 그 시각까지 원장 합 — 실사 장부 수량(W5) */
    BigDecimal ledgerQtyAsOf(@Param("itemId") long itemId, @Param("nodeId") long nodeId, @Param("status") String status,
            @Param("lotId") Long lotId, @Param("at") OffsetDateTime at);

    List<NodeOpt> findStorageNodes(@Param("plantCd") String plantCd);

    // ---- 조회
    List<StockRow> findStock(@Param("q") StockSearch q);

    long countStock(@Param("q") StockSearch q);

    Cursor<StockRow> cursorStock(@Param("q") StockSearch q);

    StockRow findStockRow(@Param("id") long id);

    List<TrxRow> findTrxs(@Param("q") TrxSearch q);

    /**
     * 이 트랜잭션만 custom plan(docs/30 5.1) — findTrxs 의 limit·offset 이 바인드 값이라 같은 문장을 5번 넘게 쓰면 드라이버 준비 문장의
     * 일반 계획(offset 을 모름)으로 바뀌어 100만 행 끝 쪽이 0.1초 → 3초가 된다. is_local = true 라 트랜잭션이 끝나면 돌아간다
     */
    String customPlan();

    long countTrxs(@Param("q") TrxSearch q);

    Cursor<TrxRow> cursorTrxs(@Param("q") TrxSearch q);

    List<TrxRow> findKeyHistory(@Param("itemId") long itemId, @Param("nodeId") long nodeId, @Param("status") String status,
            @Param("lotId") Long lotId, @Param("limit") int limit);

    // ---- 대사
    int countStockKeys();

    List<Mismatch> findMismatches(@Param("limit") int limit);

    int countMismatches();

    long insertReconcile(@Param("keys") int keys, @Param("mismatches") int mismatches, @Param("missing") Integer missing, @Param("rcvOver") Integer rcvOver,
            @Param("poMismatch") Integer poMismatch, @Param("qcJudged") Integer qcJudged, @Param("voidLive") Integer voidLive,
            @Param("detail") String detail,
            @Param("by") String by);

    ReconcileRow lastReconcile();

    // ---------------------------------------------------------------- 자재 입고 (B4a)

    /** 품목 속성 GTIN(앞 0 무시, 14자리 비교) — 둘 이상이면 null(모호) */
    InvDtos.ItemOpt findItemByGtin(@Param("gtin") String gtin);

    /** 노드나 조상이 SCRAP 용도인가 */
    boolean underScrap(@Param("nodeId") long nodeId);

    /** PDA 입고 위치 후보 — 이 공장 사용 중 WH·LOC, 폐기 위치 아래 여부 포함 */
    List<InvDtos.RcvNode> rcvNodes(@Param("plantCd") String plantCd);

    String lotNoOf(@Param("lotId") long lotId);

    /** 같은 요청 id 로 이미 넣은 자재 입고 */
    InvDtos.RcvReplay findRcvByReq(@Param("reqId") String reqId);

    /** 입고 원장 합(역분개 포함) > LOT 생성 수량인 LOT 수 */
    int countRcvMatOver();

    /** SRM 발주 행 입고 수량(투영) ≠ 원장 RCV_PO 발주 단위 합인 발주 행 수(B4b) */
    int countRcvPoMismatch();

    /** 구매 입고 LOT 의 공급사 코드(발주 거래처) */
    String custCdOf(@Param("custId") long custId);

    /** LOT 행을 lot_id 순으로 FOR SHARE(다건 원장 앞 선잠금) — 다른 회사 LOT 은 RLS 로 빠지고 원장 트리거가 거부한다 */
    List<Long> lockLotsShare(@Param("lotIds") List<Long> lotIds);
}
