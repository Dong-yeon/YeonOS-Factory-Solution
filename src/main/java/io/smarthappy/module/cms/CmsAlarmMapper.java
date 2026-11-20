package io.smarthappy.module.cms;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.CmsAlarmDtos.AlarmRow;
import io.smarthappy.module.cms.CmsAlarmDtos.DefRow;
import io.smarthappy.module.cms.CmsAlarmDtos.EquipRef;
import io.smarthappy.module.cms.CmsAlarmDtos.TopRow;
import io.smarthappy.platform.common.query.PageQuery;

/** CMS 2부 C8 — 알람 정의·알람 원장 */
@Mapper
public interface CmsAlarmMapper {

    // ------------------------------------------------------------------ 정의

    List<DefRow> findDefs(@Param("keyword") String keyword, @Param("classId") Long classId, @Param("useYn") String useYn);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<DefRow> findDefPage(@Param("keyword") String keyword, @Param("classId") Long classId, @Param("useYn") String useYn,
            @Param("page") PageQuery page);

    long countDefs(@Param("keyword") String keyword, @Param("classId") Long classId, @Param("useYn") String useYn);

    DefRow findDef(@Param("defId") long defId);

    Long lockDef(@Param("defId") long defId);

    long insertDef(@Param("d") DefRow d, @Param("by") String by);

    int updateDef(@Param("d") DefRow d, @Param("by") String by);

    Long findSymptomId(@Param("symptomCd") String symptomCd);

    List<io.smarthappy.module.cms.CmsDtos.IdOption> symptoms();

    // ------------------------------------------------------------------ 수집(AlarmSink)

    String lockKey(@Param("key") String key);

    EquipRef findEquip(@Param("equipId") long equipId);

    /** 사용 중 정의: 설비 클래스가 맞는 것 먼저, 없으면 클래스 없는 것 */
    DefRow defFor(@Param("alarmCd") String alarmCd, @Param("classId") Long classId);

    /** 같은 설비·코드의 알람이 만든 고장 신고가 아직 열려 있나(REQ·OPEN·WIP) */
    boolean hasOpenAutoBm(@Param("equipId") long equipId, @Param("alarmCd") String alarmCd);

    long insertLog(@Param("equipId") long equipId, @Param("alarmCd") String alarmCd, @Param("alarmText") String alarmText,
            @Param("raisedAt") Instant raisedAt, @Param("terminalId") Long terminalId, @Param("eventId") String eventId,
            @Param("mwoId") Long mwoId, @Param("by") String by);

    /** 열린 행 모두 해제 — 해제 시각이 발생보다 앞이면 발생 시각으로 당기고 표시 */
    int clearOpen(@Param("equipId") long equipId, @Param("alarmCd") String alarmCd, @Param("clearedAt") Instant clearedAt,
            @Param("eventId") String eventId);

    // ------------------------------------------------------------------ 조회

    List<AlarmRow> findAlarms(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("keyword") String keyword, @Param("openOnly") boolean openOnly, @Param("hours") int hours, @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<AlarmRow> findAlarmPage(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("keyword") String keyword, @Param("openOnly") boolean openOnly, @Param("hours") int hours, @Param("page") PageQuery page);

    long countAlarms(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("keyword") String keyword, @Param("openOnly") boolean openOnly);

    List<TopRow> top(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("now") OffsetDateTime now, @Param("limit") int limit);
}
