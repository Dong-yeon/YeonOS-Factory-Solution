package io.smarthappy.module.wms.labeler;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.labeler.LabelerDtos.BoxForm;
import io.smarthappy.module.wms.labeler.LabelerDtos.BoxRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.BoxSearch;
import io.smarthappy.module.wms.labeler.LabelerDtos.DevLogRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.EventRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.LabelerForm;
import io.smarthappy.module.wms.labeler.LabelerDtos.LabelerRow;

@Mapper
public interface LabelerMapper {

    // ---- 라벨러
    List<LabelerRow> findLabelers(@Param("plantCd") String plantCd);

    LabelerRow findLabeler(@Param("id") long id);

    LabelerRow findByTerminal(@Param("terminalId") long terminalId);

    long insertLabeler(@Param("f") LabelerForm f, @Param("by") String by);

    int updateLabeler(@Param("id") long id, @Param("f") LabelerForm f, @Param("by") String by);

    // ---- 박스
    List<BoxRow> findBoxPage(@Param("q") BoxSearch q);

    long countBoxes(@Param("q") BoxSearch q);

    BoxRow findBox(@Param("id") long id);

    /** 앞단 스캔 값으로 — 끝나지 않은 박스가 먼저, 없으면 가장 최근 끝난 박스(다시 온 박스 판정용) */
    BoxRow lockByCode(@Param("plantCd") String plantCd, @Param("boxCd") String boxCd);

    BoxRow lockBox(@Param("id") long id);

    Long itemIdByCd(@Param("itemCd") String itemCd);

    long insertBox(@Param("plantCd") String plantCd, @Param("f") BoxForm f, @Param("itemId") long itemId, @Param("by") String by);

    int updateBoxInfo(@Param("id") long id, @Param("f") BoxForm f, @Param("by") String by);

    int setBoxState(@Param("id") long id, @Param("status") String status, @Param("sscc") String sscc, @Param("reasonCd") String reasonCd,
            @Param("attemptAdd") int attemptAdd, @Param("prefetchedBy") Long prefetchedBy, @Param("done") boolean done,
            @Param("by") String by);

    List<BoxRow> lockForPrefetch(@Param("plantCd") String plantCd, @Param("max") int max);

    List<BoxRow> lockPrefetchedOlder(@Param("hours") int hours);

    // ---- 원장
    void insertEvent(@Param("labelerId") Long labelerId, @Param("boxId") Long boxId, @Param("requestId") String requestId,
            @Param("eventCd") String eventCd, @Param("actionCd") String actionCd, @Param("reasonCd") String reasonCd,
            @Param("sscc") String sscc, @Param("scanned") String scanned, @Param("elapsedMs") Integer elapsedMs,
            @Param("answer") String answerJson, @Param("detail") String detail, @Param("src") String src,
            @Param("occurredAt") OffsetDateTime occurredAt, @Param("method") String method, @Param("by") String by);

    /** 같은 라벨러·요청의 요청 사건 답(JSON) — 없으면 null */
    String answerOf(@Param("labelerId") long labelerId, @Param("requestId") String requestId);

    /** 요청 사건의 박스 id — 박스 없는 답(no-read·미등록)이면 0 */
    Long requestBox(@Param("labelerId") long labelerId, @Param("requestId") String requestId);

    boolean phaseExists(@Param("labelerId") long labelerId, @Param("requestId") String requestId, @Param("eventCd") String eventCd);

    /** 그 박스의 마지막 요청 id */
    String lastRequestOf(@Param("boxId") long boxId);

    List<EventRow> eventsOfBox(@Param("boxId") long boxId);

    long insertDevLog(@Param("labelerId") long labelerId, @Param("eventCd") String eventCd, @Param("code") String code,
            @Param("detail") String detail, @Param("replyCd") String replyCd, @Param("occurredAt") OffsetDateTime occurredAt,
            @Param("by") String by);

    List<DevLogRow> devLogs(@Param("labelerId") long labelerId, @Param("limit") int limit);

    /** 화면 선택지 — 사용 중 공장 */
    List<java.util.Map<String, Object>> plants();

    boolean codeUsable(@Param("grpCd") String grpCd, @Param("code") String code);
}
