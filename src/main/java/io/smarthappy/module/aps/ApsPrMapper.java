package io.smarthappy.module.aps;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.aps.ApsPrDtos.LinkInsert;
import io.smarthappy.module.aps.ApsPrDtos.MatSrc;
import io.smarthappy.module.aps.ApsPrDtos.PlanHead;
import io.smarthappy.module.aps.ApsPrDtos.PrLineRef;
import io.smarthappy.module.aps.ApsPrDtos.SourceSrc;

/** 부족 → 구매 요청 (docs/38 17.4) — APS 표만 쓴다. srm_source·srm_pr·srm_pr_line 은 읽기만(요청은 PrService 가 만든다) */
@Mapper
public interface ApsPrMapper {

    PlanHead findPlan(@Param("id") long planId);

    /** 계획 머리 잠금 — 같은 계획의 동시 요청을 줄 세운다 */
    String lockPlan(@Param("id") long planId);

    List<MatSrc> findMats(@Param("planId") long planId, @Param("ids") Collection<Long> matIds);

    List<SourceSrc> findSources(@Param("itemIds") Collection<Long> itemIds);

    List<PrLineRef> findPrLines(@Param("prId") long prId);

    String findPrNo(@Param("prId") long prId);

    int insertLinks(@Param("planId") long planId, @Param("prId") long prId, @Param("rows") List<LinkInsert> rows, @Param("by") String by);
}
