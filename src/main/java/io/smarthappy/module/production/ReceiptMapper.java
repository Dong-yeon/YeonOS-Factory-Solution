package io.smarthappy.module.production;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.production.ReceiptDtos.LocOption;
import io.smarthappy.module.production.ReceiptDtos.NewReceipt;
import io.smarthappy.module.production.ReceiptDtos.ReceiptLot;
import io.smarthappy.module.production.ReceiptDtos.ReceiptRef;
import io.smarthappy.module.production.ReceiptDtos.ReceiptRow;
import io.smarthappy.module.production.ReceiptDtos.ReceiptWo;
import io.smarthappy.platform.common.query.PageQuery;

/** 생산 입고 (P2 7a). 회사 조건은 RLS */
@Mapper
public interface ReceiptMapper {

    List<ReceiptWo> wos(@Param("plantCd") String plantCd, @Param("status") String status, @Param("keyword") String keyword,
            @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + 페이지·정렬(허용 목록) */
    List<ReceiptWo> woPage(@Param("plantCd") String plantCd, @Param("status") String status, @Param("keyword") String keyword,
            @Param("page") PageQuery page);

    long countWos(@Param("plantCd") String plantCd, @Param("status") String status, @Param("keyword") String keyword);

    ReceiptWo wo(@Param("woId") long woId);

    List<ReceiptLot> lots(@Param("woId") long woId);

    List<ReceiptRow> history(@Param("woId") long woId);

    List<LocOption> locations(@Param("plantCd") String plantCd);

    ReceiptRef findByReq(@Param("req") String clientReqId);

    ReceiptRef find(@Param("id") long receiptId);

    long insert(@Param("f") NewReceipt f, @Param("at") Instant at, @Param("by") String by);
}
