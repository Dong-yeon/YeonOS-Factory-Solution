package io.smarthappy.module.srm.portal;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.portal.PortalSrmDtos.AckLine;
import io.smarthappy.module.srm.portal.PortalSrmDtos.AckRow;
import io.smarthappy.module.srm.portal.PortalSrmDtos.AsnLine;
import io.smarthappy.module.srm.portal.PortalSrmDtos.AsnRow;
import io.smarthappy.module.srm.portal.PortalSrmDtos.OwnAsn;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PlantOption;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PoLine;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PoLineOption;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PoRow;
import io.smarthappy.module.srm.portal.PortalSrmDtos.SupplierInfo;

/**
 * 협력사 포털 SRM 질의 (docs/42 4장) — 모든 문장이 #{custId} 로 협력사를 거른다(가드 PortalMapperGuardTest).
 * custId 는 PortalSrmService 가 PortalUser.current() 로만 넘긴다. 회사 범위는 RLS. 내부 SRM 매퍼를 쓰지 않는다.
 */
@Mapper
public interface PortalSrmMapper {

    SupplierInfo supplier(@Param("custId") long custId);

    List<PoRow> findPos(@Param("custId") long custId, @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("open") boolean open,
            @Param("keywordLike") String keywordLike, @Param("zone") String zone, @Param("dueDays") int dueDays);

    PoRow findPo(@Param("custId") long custId, @Param("poId") long poId, @Param("zone") String zone, @Param("dueDays") int dueDays);

    List<PoLine> findPoLines(@Param("custId") long custId, @Param("poId") long poId);

    List<AckRow> findAcks(@Param("custId") long custId, @Param("poId") long poId);

    List<AckLine> findAckLines(@Param("custId") long custId, @Param("poId") long poId);

    /** 내 발행된 발주 머리 FOR UPDATE — 없거나 남의 것이면 null */
    Long lockOwnPo(@Param("custId") long custId, @Param("poId") long poId);

    List<AsnRow> findAsns(@Param("custId") long custId, @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("status") String status,
            @Param("keywordLike") String keywordLike);

    AsnRow findAsn(@Param("custId") long custId, @Param("asnId") long asnId);

    List<AsnLine> findAsnLines(@Param("custId") long custId, @Param("asnId") long asnId);

    /** 내 ASN FOR UPDATE — 없거나 남의 것이면 null */
    OwnAsn lockOwnAsn(@Param("custId") long custId, @Param("asnId") long asnId);

    List<PlantOption> plants(@Param("custId") long custId);

    List<PoLineOption> poLineOptions(@Param("custId") long custId, @Param("plantCd") String plantCd, @Param("asnId") Long asnId);

    List<PortalSrmDtos.ClaimRow> findClaims(@Param("custId") long custId, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("status") String status, @Param("zone") String zone);

    PortalSrmDtos.ClaimRow findClaim(@Param("custId") long custId, @Param("claimId") long claimId);

    List<PortalSrmDtos.ClaimNcr> findClaimNcrs(@Param("custId") long custId, @Param("claimId") long claimId);

    /** 내게 보낸 클레임 FOR UPDATE — 없거나 남의 것·작성 중이면 null */
    Long lockOwnClaim(@Param("custId") long custId, @Param("claimId") long claimId);
}
