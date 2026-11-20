package io.smarthappy.module.srm.pr;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.pr.PrDtos.ItemRef;
import io.smarthappy.module.srm.pr.PrDtos.PlantOption;
import io.smarthappy.module.srm.pr.PrDtos.PrLineRow;
import io.smarthappy.module.srm.pr.PrDtos.PrLineValue;
import io.smarthappy.module.srm.pr.PrDtos.PrCrit;
import io.smarthappy.module.srm.pr.PrDtos.PrRow;
import io.smarthappy.module.srm.pr.PrDtos.PrValue;
import io.smarthappy.module.srm.pr.PrDtos.SupplierRef;
import io.smarthappy.module.srm.supplier.SupplierDtos.ItemOption;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface PrMapper {

    // ---------- 선택지 ----------

    List<PlantOption> findPlants();

    String plantUseYn(@Param("plantCd") String plantCd);

    ItemRef findItem(@Param("itemCd") String itemCd);

    Integer unitScale(@Param("unitCd") String unitCd);

    SupplierRef findSupplier(@Param("custCd") String custCd);

    List<ItemOption> searchItems(@Param("keywordLike") String keywordLike);

    /** 희망 협력사 선택지 — 등록된 협력사 중 거래 정지 아닌 것 */
    List<SupplierRef> findOpenSuppliers();

    // ---------- 구매요청 ----------

    List<PrRow> findPrs(@Param("c") PrCrit c);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<PrRow> findPrPage(@Param("c") PrCrit c, @Param("page") PageQuery page);

    long countPrs(@Param("c") PrCrit c);

    PrRow findPr(@Param("id") long id);

    /** 상태 전이·한 벌 저장 전에 머리를 잠근다(같은 요청을 두 사람이 동시에 승인·수정) */
    PrRow lockPr(@Param("id") long id);

    List<PrLineRow> findLines(@Param("prId") long prId);

    long insertPr(@Param("prNo") String prNo, @Param("prDate") LocalDate prDate, @Param("reqUserId") long reqUserId,
            @Param("f") PrValue f, @Param("by") String by);

    int updatePr(@Param("id") long id, @Param("f") PrValue f, @Param("by") String by);

    int nextLineNo(@Param("prId") long prId);

    void insertLine(@Param("prId") long prId, @Param("lineNo") int lineNo, @Param("f") PrLineValue f, @Param("by") String by);

    int updateLine(@Param("f") PrLineValue f, @Param("by") String by);

    int deleteLine(@Param("prLineId") long prLineId);

    /** 이 요청 행을 가리키는 살아 있는(취소 아닌) 발주 행 수 */
    int countLivePoLines(@Param("prId") long prId);

    int changeStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to,
            @Param("rowVer") int rowVer, @Param("decidedBy") Long decidedBy, @Param("rejectReason") String rejectReason,
            @Param("cancelReason") String cancelReason, @Param("by") String by);
}
