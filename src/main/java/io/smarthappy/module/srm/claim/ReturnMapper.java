package io.smarthappy.module.srm.claim;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.claim.ReturnDtos.PlantOption;
import io.smarthappy.module.srm.claim.ReturnDtos.PoLineOption;
import io.smarthappy.module.srm.claim.ReturnDtos.PoLineRef;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnCrit;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnRow;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnValue;
import io.smarthappy.module.srm.claim.ReturnDtos.SupplierRef;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface ReturnMapper {

    List<PlantOption> findPlants();

    List<SupplierRef> findSuppliers();

    SupplierRef findSupplier(@Param("custCd") String custCd);

    String plantUseYn(@Param("plantCd") String plantCd);

    List<ReturnRow> findReturns(@Param("c") ReturnCrit c);

    List<ReturnRow> findReturnPage(@Param("c") ReturnCrit c, @Param("page") PageQuery page);

    long countReturns(@Param("c") ReturnCrit c);

    ReturnRow findReturn(@Param("returnId") long returnId);

    ReturnRow lockReturn(@Param("returnId") long returnId);

    /** 살아 있는 반품 번호 — 키 "ncrId:lineNo" 와 번호(반품 대상 목록에 붙임) */
    List<java.util.Map<String, Object>> liveReturns(@Param("custId") long custId, @Param("ncrIds") Collection<Long> ncrIds);

    List<PoLineOption> poLineOptions(@Param("custId") long custId, @Param("itemId") long itemId);

    /** 발주 행 + 그 단위·반품 단위(unitCd)의 기본 단위 환산(같으면 1, 없으면 null)·발주 단위 자릿수 */
    PoLineRef findPoLine(@Param("poLineId") long poLineId, @Param("unitCd") String unitCd);

    long insertReturn(@Param("no") String no, @Param("v") ReturnValue v, @Param("by") String by);

    int updateReturn(@Param("returnId") long returnId, @Param("v") ReturnValue v, @Param("rowVer") int rowVer, @Param("by") String by);

    int confirm(@Param("returnId") long returnId, @Param("rowVer") int rowVer, @Param("by") String by);

    int cancel(@Param("returnId") long returnId, @Param("rowVer") int rowVer, @Param("reason") String reason, @Param("by") String by);

    /** 그 NCR 을 근거로 한 같은 협력사의 살아 있는 클레임(반품 상세 연결) */
    List<ReturnDtos.LinkRow> claimsOfNcr(@Param("ncrId") long ncrId, @Param("custId") long custId, @Param("zone") String zone);
}
