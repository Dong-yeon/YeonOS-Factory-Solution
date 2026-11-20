package io.smarthappy.module.srm.claim;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.claim.ClaimDtos.ClaimCrit;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimNcrRow;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimRow;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimValue;
import io.smarthappy.module.srm.claim.ClaimDtos.NcrValue;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface ClaimMapper {

    List<ClaimRow> findClaims(@Param("c") ClaimCrit c);

    List<ClaimRow> findClaimPage(@Param("c") ClaimCrit c, @Param("page") PageQuery page);

    long countClaims(@Param("c") ClaimCrit c);

    ClaimRow findClaim(@Param("claimId") long claimId);

    ClaimRow lockClaim(@Param("claimId") long claimId);

    List<ClaimNcrRow> findNcrs(@Param("claimId") long claimId);

    /** 살아 있는(취소 아닌) 클레임에 든 NCR — 키 ncr_id, 값 claim_no */
    List<Map<String, Object>> liveClaims(@Param("ncrIds") Collection<Long> ncrIds);

    long insertClaim(@Param("no") String no, @Param("v") ClaimValue v, @Param("by") String by);

    int updateClaim(@Param("claimId") long claimId, @Param("v") ClaimValue v, @Param("rowVer") int rowVer, @Param("by") String by);

    int deleteNcrs(@Param("claimId") long claimId);

    int insertNcr(@Param("claimId") long claimId, @Param("v") NcrValue v, @Param("by") String by);

    int send(@Param("claimId") long claimId, @Param("rowVer") int rowVer, @Param("by") String by);

    int answer(@Param("claimId") long claimId, @Param("rowVer") int rowVer, @Param("cause") String cause, @Param("action") String action,
            @Param("on") java.time.LocalDate on, @Param("by") String by);

    int reject(@Param("claimId") long claimId, @Param("rowVer") int rowVer, @Param("by") String by);

    int close(@Param("claimId") long claimId, @Param("rowVer") int rowVer, @Param("by") String by);

    int cancel(@Param("claimId") long claimId, @Param("rowVer") int rowVer, @Param("reason") String reason, @Param("by") String by);

    /** 통지 재료(송신기는 DB 를 못 쓴다) — 협력사 이름·대표 담당 메일·회사 이름 */
    Map<String, Object> notifyInfo(@Param("claimId") long claimId);

    /** 근거 NCR·협력사가 같은 살아 있는 반품(클레임 상세 연결) */
    List<ReturnDtos.LinkRow> returnsOfClaim(@Param("claimId") long claimId, @Param("zone") String zone);

    int answerFromPortal(@Param("claimId") long claimId, @Param("rowVer") int rowVer, @Param("cause") String cause,
            @Param("action") String action, @Param("on") LocalDate on, @Param("by") String by);
}
