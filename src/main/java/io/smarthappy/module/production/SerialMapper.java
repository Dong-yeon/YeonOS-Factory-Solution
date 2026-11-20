package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.production.SerialDtos.AltCand;
import io.smarthappy.module.production.SerialDtos.Installed;
import io.smarthappy.module.production.SerialDtos.LockedSerial;
import io.smarthappy.module.production.SerialDtos.PartItem;
import io.smarthappy.module.production.SerialDtos.PartRow;
import io.smarthappy.module.production.SerialDtos.SerialRow;
import io.smarthappy.module.production.SerialDtos.SerialWo;
import io.smarthappy.module.production.SerialDtos.TraceRow;
import io.smarthappy.module.production.SerialDtos.WoBomLine;
import io.smarthappy.platform.common.query.PageQuery;

/** 장비 시리얼 구성 (docs/77). 회사 조건은 RLS. 잠금 순서 = 시리얼 머리 → 부품 LOT(계보 트리거) */
@Mapper
public interface SerialMapper {

    // ---------- 목록 ----------

    List<SerialRow> findSerialPage(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("zone") String zone,
            @Param("keyword") String keyword, @Param("itemId") Long itemId, @Param("page") PageQuery page);

    long countSerials(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("zone") String zone,
            @Param("keyword") String keyword, @Param("itemId") Long itemId);

    SerialRow findSerial(@Param("serialId") long serialId);

    List<SerialRow> findSerialsOfWo(@Param("woId") long woId);

    // ---------- 등록 ----------

    /** 시리얼을 만들 수 있는 지시(발행·진행) — 번호 앞부분 일치, 최근 순 */
    List<SerialWo> findWos(@Param("woNo") String woNo, @Param("limit") int limit);

    /** 지시 잠금(시리얼 수 확인 전) */
    SerialWo lockWo(@Param("woId") long woId);

    long insertSerial(@Param("serialNo") String serialNo, @Param("woId") long woId, @Param("itemId") long itemId,
            @Param("unitLotId") long unitLotId, @Param("remark") String remark, @Param("by") String by);

    boolean lotNoExists(@Param("lotNo") String lotNo);

    // ---------- 장착 ----------

    LockedSerial lockSerial(@Param("serialId") long serialId);

    /** row_ver 가 같을 때만 +1 — 0 이면 남이 먼저 저장(409) */
    int bumpSerial(@Param("serialId") long serialId, @Param("rowVer") int rowVer, @Param("by") String by);

    PartItem findItem(@Param("itemCd") String itemCd);

    Long findLotId(@Param("lotNo") String lotNo, @Param("itemId") long itemId);

    Long findWoProc(@Param("woId") long woId, @Param("opSeq") int opSeq);

    Long findWorker(@Param("workerCd") String workerCd);

    /** 같은 품목 × 부품 시리얼이 장착 중인 시리얼 번호(없으면 null) — 서비스가 먼저 본다(트리거가 최종) */
    String activeSerialOf(@Param("itemId") long itemId, @Param("partSerialNo") String partSerialNo);

    /** 부품 시리얼 줄 세우기(트리거와 같은 키) — 서비스가 확인 전에 */
    String lockPartSerial(@Param("itemId") long itemId, @Param("partSerialNo") String partSerialNo);

    long insertPart(@Param("serialId") long serialId, @Param("evType") String evType, @Param("itemId") long itemId,
            @Param("lotId") Long lotId, @Param("partSerialNo") String partSerialNo, @Param("qty") BigDecimal qty,
            @Param("unitCd") String unitCd, @Param("woProcId") Long woProcId, @Param("genId") Long genId, @Param("reason") String reason,
            @Param("occurredAt") Instant occurredAt, @Param("entryMethod") String entryMethod, @Param("by") String by,
            @Param("workerId") Long workerId, @Param("reversalOfId") Long reversalOfId);

    PartRow findPart(@Param("partId") long partId);

    /** 이 시리얼의 장착 원장 전부(오래된 순) */
    List<PartRow> partsOf(@Param("serialId") long serialId);

    // ---------- BOM 대비 ----------

    List<WoBomLine> woBom(@Param("woId") long woId);

    List<AltCand> altCands(@Param("bomIds") List<Long> bomIds);

    /** 장착 중 수량 — 품목별. serialId 가 null 이면 그 지시의 모든 시리얼 */
    List<Installed> installed(@Param("serialId") Long serialId, @Param("woId") Long woId);

    // ---------- 완료판정 경고 ----------

    Integer opSeqOf(@Param("woProcId") long woProcId);

    Integer lastOpSeq(@Param("woId") long woId);

    // ---------- 역추적 ----------

    Long findLotIdByNo(@Param("lotNo") String lotNo);

    /** 시작 LOT 과 계보로 나간 자식 LOT 들(살아 있는 연결만, 깊이 상한) 을 부품 LOT 으로 쓴 장착 행 + 그 LOT 이 장비 LOT 인 시리얼 */
    List<TraceRow> traceLot(@Param("lotId") long lotId, @Param("depth") int depth, @Param("limit") int limit);

    List<TraceRow> traceSerial(@Param("itemId") long itemId, @Param("partSerialNo") String partSerialNo, @Param("limit") int limit);
}
