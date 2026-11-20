package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.production.CompletionDtos.NewClose;
import io.smarthappy.module.production.CompletionDtos.NewRework;
import io.smarthappy.module.production.CompletionDtos.ProcQty;
import io.smarthappy.module.production.CompletionDtos.ReworkRow;

/** 공정 완료판정·재작업/수리 처리·지시 완료·마감 (P2 7a). 회사 조건은 RLS */
@Mapper
public interface CompletionMapper {

    ProcQty procQty(@Param("id") long woProcId);

    /** 지시의 공정 전부(순번 순) */
    List<ProcQty> procQtys(@Param("woId") long woId);

    /** 바로 앞 공정(op_seq 가 작은 것 중 가장 큰 것) — 없으면 null */
    ProcQty prevProc(@Param("woId") long woId, @Param("opSeq") int opSeq);

    /** 이 공정 산출 LOT id(실적 + 재작업/수리 처리, 중복 없음·오름차순) — 완료판정 경고 문맥 */
    List<Long> outputLots(@Param("id") long woProcId);

    /** 산출 LOT 중 보류 중인 수(docs/72 3.1) */
    int heldOutputLots(@Param("id") long woProcId);

    String lockProc(@Param("id") long woProcId);

    int insertClose(@Param("f") NewClose f, @Param("at") Instant at, @Param("by") String by);

    int closeProc(@Param("id") long woProcId, @Param("rowVer") int rowVer, @Param("by") String by);

    ReworkRow findReworkByReq(@Param("req") String clientReqId);

    long insertRework(@Param("f") NewRework f, @Param("at") Instant at, @Param("by") String by);

    int openProcs(@Param("woId") long woId);

    int complete(@Param("woId") long woId, @Param("rowVer") int rowVer, @Param("doneAt") java.time.Instant doneAt, @Param("by") String by);

    int closeWo(@Param("woId") long woId, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 지시 전체 순입고 수량 */
    BigDecimal receivedQty(@Param("woId") long woId);
}
