package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 홈 카드 집계 (docs/37 17.3·18.1) — 시정조치 기한 지남, 검사 대기, 열린 부적합·봉쇄. 회사 조건은 RLS */
@Mapper
public interface QmsHomeMapper {

    CapaDueSummary capaDueSummary(@Param("today") LocalDate today);

    List<CapaDueRow> capaOverdueTop(@Param("today") LocalDate today, @Param("limit") int limit);

    /** closeOverdue = 종결 기한 지남(목록 dueState CLOSE_OVERDUE), containOverdue = 봉쇄 기한만 지남(CONTAIN_OVERDUE) */
    record CapaDueSummary(long closeOverdueCnt, long containOverdueCnt) {
    }

    record CapaDueRow(String capaNo, String title, LocalDate closeDue) {
    }

    // ---------- 6단계 (docs/37 18.1) ----------

    /** 판정 안 된 검사(OPEN) — 목록 InspMapper.findPage(status=OPEN)와 같은 조건. waitedBefore 보다 먼저 연 것 = 오래 대기 */
    InspOpenSummary inspOpenSummary(@Param("waitedBefore") OffsetDateTime waitedBefore);

    List<InspOpenRow> inspOpenTop(@Param("limit") int limit);

    /** 열린 부적합(OPEN·PENDING) */
    NcrOpenSummary ncrOpenSummary();

    List<NcrOpenRow> ncrOpenTop(@Param("limit") int limit);

    /** 열린 봉쇄(OPEN) + 아직 풀리지 않은 LOT 수 */
    ContainOpenSummary containOpenSummary();

    List<ContainOpenRow> containOpenTop(@Param("limit") int limit);

    record InspOpenSummary(long openCnt, long receiveCnt, long longWaitCnt) {
    }

    record InspOpenRow(String inspNo, String inspType, String itemCd, String lotNo, OffsetDateTime occurredAt) {
    }

    /** noDefectCnt = 불량 유형 미지정(고객 반품 NCR 판정 대기 — docs/29_P3 39장) */
    record NcrOpenSummary(long openCnt, long pendingCnt, long holdCnt, long noDefectCnt) {
    }

    record NcrOpenRow(String ncrNo, String status, String itemCd, String lotNo, OffsetDateTime occurredAt) {
    }

    record ContainOpenSummary(long openCnt, long heldLotCnt) {
    }

    // ---------- SPC 위반 원장 (docs/29_P3 19장) ----------

    /** 미처리 SPC 위반(투영 표 OPEN·ACKED) 최근 순 — 숫자는 위반 목록 건수 SQL(SpcEventMapper.countGrid)로 센다 */
    List<SpcOpenRow> spcOpenTop(@Param("limit") int limit);

    record SpcOpenRow(String inspNo, String ruleCd, String itemCd, String charNo, boolean acked, OffsetDateTime occurredAt) {
    }

    record ContainOpenRow(String containNo, String itemCd, String reason, long heldLotCnt, OffsetDateTime createdAt) {
    }
}
