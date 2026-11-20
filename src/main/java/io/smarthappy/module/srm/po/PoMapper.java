package io.smarthappy.module.srm.po;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.po.PoDtos.ItemRef;
import io.smarthappy.module.srm.po.PoDtos.PoLineRow;
import io.smarthappy.module.srm.po.PoDtos.PoLineValue;
import io.smarthappy.module.srm.po.PoDtos.PoCrit;
import io.smarthappy.module.srm.po.PoDtos.PoRow;
import io.smarthappy.module.srm.po.PoDtos.PoValue;
import io.smarthappy.module.srm.po.PoDtos.PrLineRef;
import io.smarthappy.module.srm.po.PoDtos.RevRow;
import io.smarthappy.module.srm.po.PoDtos.SupplierRef;
import io.smarthappy.module.srm.po.PoDtos.WhOption;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface PoMapper {

    // ---------- 참조 ----------

    SupplierRef findSupplier(@Param("custCd") String custCd);

    /** 품목 + 이 협력사의 공급 품목 품번(사용 중일 때) */
    ItemRef findItem(@Param("itemCd") String itemCd, @Param("custId") long custId);

    Integer unitScale(@Param("unitCd") String unitCd);

    Integer currencyScale(@Param("currencyCd") String currencyCd);

    String plantUseYn(@Param("plantCd") String plantCd);

    Long findWh(@Param("plantCd") String plantCd, @Param("whCd") String whCd);

    List<WhOption> findWhs(@Param("plantCd") String plantCd);

    boolean codeUsable(@Param("grp") String grp, @Param("code") String code);

    /** 승인된 구매요청의 열린 행과 남은 수량(살아 있는 발주 행 합을 뺀 값) */
    List<PrLineRef> findOpenPrLines(@Param("itemCd") String itemCd, @Param("keywordLike") String keywordLike);

    /** 발주 행이 가리킬 구매요청 행 잠금(번호 순) — 남은 수량은 잠근 뒤 findPrLineRefs 로(같은 문장이면 옛 스냅샷) */
    List<Long> lockPrLines(@Param("ids") List<Long> ids);

    /** 남은 수량 = PR 수량 − 살아 있는 발주 행 합, exceptPoId 의 행은 빼고 센다(한 벌 저장으로 바뀔 행) */
    List<PrLineRef> findPrLineRefs(@Param("ids") List<Long> ids, @Param("exceptPoId") Long exceptPoId);

    // ---------- 발주 ----------

    List<PoRow> findPos(@Param("c") PoCrit c);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<PoRow> findPoPage(@Param("c") PoCrit c, @Param("page") PageQuery page);

    long countPos(@Param("c") PoCrit c);

    PoRow findPo(@Param("id") long id);

    PoRow lockPo(@Param("id") long id);

    List<PoLineRow> findLines(@Param("poId") long poId);

    List<RevRow> findRevs(@Param("poId") long poId);

    long insertPo(@Param("poNo") String poNo, @Param("poDate") LocalDate poDate, @Param("custId") long custId,
            @Param("currencyCd") String currencyCd, @Param("buyerUserId") long buyerUserId, @Param("f") PoValue f,
            @Param("by") String by);

    int updatePo(@Param("id") long id, @Param("f") PoValue f, @Param("by") String by);

    int nextLineNo(@Param("poId") long poId);

    void insertLine(@Param("poId") long poId, @Param("lineNo") int lineNo, @Param("f") PoLineValue f, @Param("by") String by);

    int updateLine(@Param("f") PoLineValue f, @Param("by") String by);

    int deleteLine(@Param("poLineId") long poLineId);

    int changeStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("rowVer") int rowVer,
            @Param("aprRequestId") Long aprRequestId, @Param("cancelReason") String cancelReason, @Param("by") String by);

    /** 발행: APPROVED → ISSUED + 발행 시각 */
    int issue(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 개정: ISSUED·ACKED → DRAFT, rev + 1 / APPROVED → DRAFT, rev 그대로 */
    int revise(@Param("id") long id, @Param("from") String from, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 지금 머리 + 행을 발행본 스냅샷으로 */
    void insertRevSnapshot(@Param("id") long id, @Param("by") String by);

    /** 발행 전 미리보기(지금 머리 + 행, 발행본과 같은 모양 jsonb 글자) */
    String currentSnapshot(@Param("id") long id);

    String revSnapshot(@Param("id") long id, @Param("rev") int rev);

    /** 결재 핸들러용 — 결재 상태 전이(rowVer 없이, 상태로만 조건) */
    int finishApproval(@Param("id") long id, @Param("to") String to, @Param("by") String by);

    /** 결재 중 요청의 현재 차례 결재자·상신자 — {current, requester} */
    java.util.Map<String, Object> aprTurn(@Param("aprId") long aprId);

    /** 통지 본문 재료 — 받는 주소 = 사용 중 주 담당자 메일, 없으면 거래처 메일 */
    java.util.Map<String, Object> notifyInfo(@Param("id") long id);

    BigDecimal sumAmt(@Param("poId") long poId);

    // ---------- ERP 발주 받기 (S3b) ----------

    /** 사용 중 ERP 유형 외부 시스템 id */
    Long findErpSystem(@Param("systemCd") String systemCd);

    /** 외부 ID 매핑으로 내부 id — 없으면 null */
    Long findMappedId(@Param("systemId") long systemId, @Param("entityType") String entityType, @Param("externalId") String externalId);

    /** 등록된 협력사 — 거래처 코드 또는 cust_id */
    SupplierRef findSupplierById(@Param("custId") long custId);

    String findItemCd(@Param("itemId") long itemId);

    /** 이미 받은 ERP 발주 행(대조용 정규화 값) */
    java.util.Map<String, Object> findErpLine(@Param("systemId") long systemId, @Param("erpPoNo") String erpPoNo,
            @Param("lineNo") int lineNo);

    boolean erpPoExists(@Param("systemId") long systemId, @Param("erpPoNo") String erpPoNo);

    long insertErpPo(@Param("poNo") String poNo, @Param("poDate") LocalDate poDate, @Param("custId") long custId,
            @Param("currencyCd") String currencyCd, @Param("buyerUserId") long buyerUserId, @Param("f") PoValue f,
            @Param("by") String by);

    void insertExtMap(@Param("systemId") long systemId, @Param("poId") long poId, @Param("erpPoNo") String erpPoNo,
            @Param("by") String by);

    long countErpLines();
}
