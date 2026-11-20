package io.smarthappy.module.cms;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.CmsStateDtos.IntervalRef;
import io.smarthappy.module.cms.CmsStateDtos.LinkRow;
import io.smarthappy.module.cms.CmsStateDtos.MwoAlarmRow;
import io.smarthappy.module.cms.CmsStateDtos.MwoDownRow;
import io.smarthappy.module.cms.CmsStateDtos.MwoOption;
import io.smarthappy.module.cms.CmsStateDtos.NoteRow;
import io.smarthappy.module.cms.CmsStateDtos.ReasonRow;
import io.smarthappy.module.cms.CmsStateDtos.ReviewRow;
import io.smarthappy.platform.common.query.PageQuery;

/** CMS 2부 C7 — 상태 사유(MES 표)·사후 사유·작업지시 연결. MES 원장은 읽기만 */
@Mapper
public interface CmsStateMapper {

    // ------------------------------------------------------------------ 사유

    List<ReasonRow> findReasons(@Param("keyword") String keyword, @Param("stateCat") String stateCat, @Param("useYn") String useYn);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<ReasonRow> findReasonPage(@Param("keyword") String keyword, @Param("stateCat") String stateCat, @Param("useYn") String useYn,
            @Param("page") PageQuery page);

    long countReasons(@Param("keyword") String keyword, @Param("stateCat") String stateCat, @Param("useYn") String useYn);

    ReasonRow findReason(@Param("reasonId") long reasonId);

    ReasonRow findReasonByCd(@Param("reasonCd") String reasonCd);

    Long lockReason(@Param("reasonId") long reasonId);

    long insertReason(@Param("r") ReasonRow r, @Param("by") String by);

    /** 이름·계획 여부·순서·사용만 — 코드·분류는 고정(원장 트리거가 넣을 때 분류를 맞춰 봤다) */
    int updateReason(@Param("r") ReasonRow r, @Param("by") String by);

    // ------------------------------------------------------------------ 검토

    List<ReviewRow> findReview(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("keyword") String keyword, @Param("effCat") String effCat, @Param("pending") boolean pending,
            @Param("reasonCd") String reasonCd, @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<ReviewRow> findReviewPage(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("keyword") String keyword, @Param("effCat") String effCat, @Param("pending") boolean pending,
            @Param("reasonCd") String reasonCd, @Param("page") PageQuery page);

    long countReview(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("keyword") String keyword, @Param("effCat") String effCat, @Param("pending") boolean pending,
            @Param("reasonCd") String reasonCd);

    ReviewRow findReviewRow(@Param("stateLogId") long stateLogId);

    IntervalRef findInterval(@Param("stateLogId") long stateLogId);

    List<NoteRow> findNotes(@Param("stateLogId") long stateLogId);

    List<LinkRow> findLinks(@Param("stateLogId") long stateLogId);

    List<MwoOption> mwoOptions(@Param("equipId") long equipId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("limit") int limit);

    long insertNote(@Param("stateLogId") long stateLogId, @Param("reasonId") long reasonId, @Param("noteText") String noteText,
            @Param("by") String by);

    int voidNote(@Param("stateLogId") long stateLogId, @Param("noteId") long noteId, @Param("reason") String reason,
            @Param("by") String by);

    long insertLink(@Param("stateLogId") long stateLogId, @Param("mwoId") long mwoId, @Param("by") String by);

    int voidLink(@Param("stateLogId") long stateLogId, @Param("linkId") long linkId, @Param("reason") String reason,
            @Param("by") String by);

    // ------------------------------------------------------------------ 작업지시 상세(C11)

    List<MwoDownRow> mwoDowns(@Param("mwoId") long mwoId);

    List<MwoAlarmRow> mwoAlarms(@Param("mwoId") long mwoId);
}
