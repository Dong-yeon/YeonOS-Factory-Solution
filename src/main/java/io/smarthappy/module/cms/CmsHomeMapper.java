package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 홈 카드 집계 (docs/37 17.3) — 점검 예정·고장 처리 대기. 회사 조건은 RLS, plantCd null = 전체 공장 */
@Mapper
public interface CmsHomeMapper {

    /** 열린 점검: 창 [from, to] 안 지남·오늘·7일 안 + 창보다 오래 밀린 것(before = from 전) */
    ChkDueSummary chkDueSummary(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("today") LocalDate today,
            @Param("plantCd") String plantCd);

    List<ChkDueRow> chkDueTop(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("plantCd") String plantCd,
            @Param("limit") int limit);

    /** 진행 중 고장(BM, REQ·OPEN·WIP): 신고 시각 창 [from, to) 안 + 그중 손대지 않은 것(REQ·OPEN)·설비 정지 + 창보다 오래된 것 */
    BmWaitSummary bmWaitSummary(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to, @Param("plantCd") String plantCd);

    List<BmWaitRow> bmWaitTop(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to, @Param("plantCd") String plantCd,
            @Param("limit") int limit);

    /**
     * 사유 없는 정지·대기 구간(6단계, docs/37 18.1) — [from, to) 과 겹치는 구간, 원장 사유·사후 사유 모두 없음, PRD·UNKNOWN 제외
     * (CmsStateMapper.findReview 의 pending + 겹침 조건과 같다). plantCd null = 전체 공장
     */
    StatePendingSummary statePendingSummary(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("plantCd") String plantCd);

    List<StatePendingRow> statePendingTop(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("plantCd") String plantCd, @Param("limit") int limit);

    record StatePendingSummary(long pendingCnt, long openCnt) {
    }

    record StatePendingRow(String plantCd, String equipCd, String stateCat, OffsetDateTime startAt, OffsetDateTime endAt) {
    }

    record ChkDueSummary(long overdueCnt, long todayCnt, long soonCnt, long beforeCnt) {
    }

    record ChkDueRow(String targetCd, String planNm, LocalDate dueDate) {
    }

    record BmWaitSummary(long openCnt, long untouchedCnt, long downCnt, long beforeCnt) {
    }

    record BmWaitRow(String mwoNo, String title, String status, String downYn, OffsetDateTime reportedAt) {
    }
}
