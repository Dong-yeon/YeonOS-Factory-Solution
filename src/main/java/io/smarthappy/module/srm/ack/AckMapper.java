package io.smarthappy.module.srm.ack;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.ack.AckDtos.AckCheck;
import io.smarthappy.module.srm.ack.AckDtos.AckLineRow;
import io.smarthappy.module.srm.ack.AckDtos.AckPoLine;
import io.smarthappy.module.srm.ack.AckDtos.AckCrit;
import io.smarthappy.module.srm.ack.AckDtos.AckPoRow;
import io.smarthappy.module.srm.ack.AckDtos.AckRow;
import io.smarthappy.module.srm.ack.AckDtos.PoLineRef;
import io.smarthappy.module.srm.ack.AckDtos.PoRef;
import io.smarthappy.module.srm.ack.AckDtos.StatusCrit;
import io.smarthappy.module.srm.ack.AckDtos.StatusRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface AckMapper {

    // ---------- 조회 ----------

    /** state: OPEN(ISSUED·ACKED) / NO_ACK(열린 발주 중 유효 회신 없음·거절) / ACKED(유효 회신 수락·부분) / ALL(취소·마감 포함) */
    List<AckPoRow> findPos(@Param("c") AckCrit c);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<AckPoRow> findPosPage(@Param("c") AckCrit c, @Param("page") PageQuery page);

    long countPos(@Param("c") AckCrit c);

    AckPoRow findPo(@Param("poId") long poId, @Param("zone") String zone, @Param("dueDays") int dueDays);

    List<AckPoLine> findPoLines(@Param("poId") long poId);

    List<AckRow> findAcks(@Param("poId") long poId);

    List<AckLineRow> findAckLines(@Param("poId") long poId);

    // ---------- 저장 ----------

    int nextSeq(@Param("poId") long poId);

    long insertAck(@Param("poId") long poId, @Param("rev") int rev, @Param("seq") int seq, @Param("source") String source,
            @Param("status") String status, @Param("rejectReason") String rejectReason, @Param("ackByNm") String ackByNm,
            @Param("ackOn") LocalDate ackOn, @Param("remark") String remark, @Param("jobId") Long jobId, @Param("by") String by);

    void insertAckLine(@Param("ackId") long ackId, @Param("poLineId") long poLineId, @Param("promisedDate") LocalDate promisedDate,
            @Param("promisedQty") BigDecimal promisedQty, @Param("remark") String remark, @Param("by") String by);

    /** 열린 발주 행마다 열린 수량·이 묶음의 약속 합·끝 날 — 상태·경고 계산 */
    List<AckCheck> ackCheck(@Param("ackId") long ackId);

    /** 만든 트랜잭션 안에서만(트리거) 수락 ↔ 부분 */
    int setAckStatus(@Param("ackId") long ackId, @Param("status") String status);

    /** ISSUED ↔ ACKED — 같은 트랜잭션의 새 회신 묶음이 있어야(트리거) */
    int changePoState(@Param("poId") long poId, @Param("from") String from, @Param("to") String to, @Param("by") String by);

    // ---------- 엑셀 회신 ----------

    PoRef findPoRef(@Param("poNo") String poNo);

    PoLineRef findPoLineRef(@Param("poId") long poId, @Param("lineNo") int lineNo);

    long countAckLines();

    // ---------- 진행 현황 ----------

    /** view: ALL / NO_ACK / LATE / DUE_SOON — 열린(ISSUED·ACKED) 발주의 열린 행 */
    List<StatusRow> findStatus(@Param("c") StatusCrit c);

    /** 진행 현황 grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<StatusRow> findStatusPage(@Param("c") StatusCrit c, @Param("page") PageQuery page);

    long countStatus(@Param("c") StatusCrit c);
}
