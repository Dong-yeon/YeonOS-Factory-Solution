package io.smarthappy.module.srm.asn;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.asn.AsnDtos.AsnCrit;
import io.smarthappy.module.srm.asn.AsnDtos.AsnLineRow;
import io.smarthappy.module.srm.asn.AsnDtos.AsnLineValue;
import io.smarthappy.module.srm.asn.AsnDtos.AsnRow;
import io.smarthappy.module.srm.asn.AsnDtos.AsnValue;
import io.smarthappy.module.srm.asn.AsnDtos.LimitRow;
import io.smarthappy.module.srm.asn.AsnDtos.PlantOption;
import io.smarthappy.module.srm.asn.AsnDtos.PoLineOption;
import io.smarthappy.module.srm.asn.AsnDtos.PoLineRef;
import io.smarthappy.module.srm.asn.AsnDtos.SupplierRef;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface AsnMapper {

    List<PlantOption> findPlants();

    List<SupplierRef> findSuppliers();

    SupplierRef findSupplier(@Param("custCd") String custCd);

    String plantUseYn(@Param("plantCd") String plantCd);

    /** late = true 면 도착 예정일 &lt; today 이고 남은 행이 있는 확정·입고 중만 */
    List<AsnRow> findAsns(@Param("c") AsnCrit c);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록), 지연 조인 */
    List<AsnRow> findAsnPage(@Param("c") AsnCrit c, @Param("page") PageQuery page);

    long countAsns(@Param("c") AsnCrit c);

    AsnRow findAsn(@Param("asnId") long asnId, @Param("today") LocalDate today);

    AsnRow lockAsn(@Param("asnId") long asnId);

    List<AsnLineRow> findLines(@Param("asnId") long asnId);

    List<PoLineOption> poLineOptions(@Param("custId") long custId, @Param("plantCd") String plantCd, @Param("asnId") Long asnId);

    PoLineRef findPoLine(@Param("poLineId") long poLineId);

    /** 엑셀: 발주 번호·행 → 발주 행 + 통지 가능 양(notifiable) */
    PoLineRef findPoLineByNo(@Param("poNo") String poNo, @Param("lineNo") int lineNo);

    boolean supplierRefUsed(@Param("custId") long custId, @Param("ref") String ref);

    long countLines();

    long insertAsn(@Param("asnNo") String asnNo, @Param("v") AsnValue v, @Param("source") String source, @Param("jobId") Long jobId,
            @Param("by") String by);

    int updateAsn(@Param("asnId") long asnId, @Param("v") AsnValue v, @Param("rowVer") int rowVer, @Param("by") String by);

    int deleteLines(@Param("asnId") long asnId);

    int insertLine(@Param("asnId") long asnId, @Param("lineNo") int lineNo, @Param("v") AsnLineValue v, @Param("by") String by);

    /** 확정 한도 판정(R3-4) — 이 ASN 의 발주 행마다 */
    List<LimitRow> limits(@Param("asnId") long asnId, @Param("plantCd") String plantCd);

    int send(@Param("asnId") long asnId, @Param("rowVer") int rowVer, @Param("by") String by);

    int changeStatus(@Param("asnId") long asnId, @Param("from") String from, @Param("to") String to, @Param("rowVer") int rowVer,
            @Param("cancelReason") String cancelReason, @Param("by") String by);
}
