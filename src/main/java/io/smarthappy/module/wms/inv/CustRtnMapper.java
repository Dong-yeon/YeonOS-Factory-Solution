package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.inv.CustRtnDtos.Rtn;
import io.smarthappy.module.wms.inv.CustRtnDtos.Source;

/** 고객 반품 입고 (docs/59) — 회사 조건은 RLS */
@Mapper
public interface CustRtnMapper {

    /** 근거 찾기 — 공장 + (출하 번호 | LOT 번호 | 고객 코드 + 출하 기간[fromAt, toAt) — 공장 시간대로 서비스가 계산) 중 하나, 받을 수 있는 양 > 0 만, 최근 출하 먼저 */
    List<Source> sources(@Param("plantCd") String plantCd, @Param("shipNo") String shipNo, @Param("lotNo") String lotNo,
            @Param("custCd") String custCd, @Param("fromAt") OffsetDateTime fromAt, @Param("toAt") OffsetDateTime toAt, @Param("limit") int limit);

    /** 근거 한 줄(출하 행 × LOT) — 없거나 다른 회사·다른 공장이면 null */
    Source source(@Param("plantCd") String plantCd, @Param("shipLineId") long shipLineId, @Param("lotId") Long lotId);

    /** 출하 머리 FOR SHARE — 출하 취소(FOR UPDATE)와 줄 세움 */
    String lockShipShare(@Param("shipId") long shipId);

    /** 출하 행 advisory(트랜잭션) — 같은 출하 행 반품끼리 줄 세움 */
    void lockShipLine(@Param("key") String key);

    Rtn findByReq(@Param("reqId") String reqId);

    long insert(@Param("rtnNo") String rtnNo, @Param("s") Source s, @Param("qty") BigDecimal qty, @Param("reasonCd") String reasonCd,
            @Param("nodeId") long nodeId, @Param("remark") String remark, @Param("capaNo") String capaNo, @Param("reqId") String reqId,
            @Param("performedBy") Long performedBy, @Param("by") String by);

    int setNcr(@Param("rtnId") long rtnId, @Param("ncrId") long ncrId, @Param("ncrNo") String ncrNo);

    Rtn find(@Param("rtnId") long rtnId);

    Rtn lock(@Param("rtnId") long rtnId);

    int markCancelled(@Param("rtnId") long rtnId, @Param("reason") String reason, @Param("by") String by);

    /** 반품의 살아 있는 원장 행(역분개 안 된 RCV_RTN) */
    List<Long> liveTrxOf(@Param("rtnId") long rtnId);

    List<Rtn> list(@Param("plantCd") String plantCd, @Param("status") String status, @Param("fromAt") OffsetDateTime fromAt,
            @Param("toAt") OffsetDateTime toAt, @Param("limit") int limit);
}
