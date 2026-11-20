package io.smarthappy.module.production;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.production.link.LotLookup.LotOrigin;
import io.smarthappy.module.production.link.LotLookup.LotView;

/** LOT 조회 계약 (LotLookup, docs/25 4.21) — 읽기만. 회사 조건은 RLS */
@Mapper
public interface LotLookupMapper {

    List<LotView> lots(@Param("ids") List<Long> ids);

    /** 출처·공급사(RECEIVED 만) */
    List<LotOrigin> origins(@Param("ids") List<Long> ids);

    LotView byNo(@Param("lotNo") String lotNo);

    /** 지시의 생산 LOT(wo_id 가 있는 행 = PRODUCED, ck_lot_master_produced) — ix_lot_master_wo */
    List<LotView> lotsOfWo(@Param("woId") long woId, @Param("limit") int limit);
}
