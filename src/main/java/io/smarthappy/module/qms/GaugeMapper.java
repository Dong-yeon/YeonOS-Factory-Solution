package io.smarthappy.module.qms;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.qms.GaugeDtos.CalibRow;
import io.smarthappy.module.qms.GaugeDtos.CalibValue;
import io.smarthappy.module.qms.GaugeDtos.GaugeRow;
import io.smarthappy.module.qms.GaugeDtos.GaugeSearch;

@Mapper
public interface GaugeMapper {

    List<GaugeRow> findPage(@Param("q") GaugeSearch q);

    long count(@Param("q") GaugeSearch q);

    Cursor<GaugeRow> cursor(@Param("q") GaugeSearch q);

    /** 게이지 하나(사용 중지 포함) — 다른 유형·다른 회사는 null */
    GaugeRow findGauge(@Param("q") GaugeSearch q, @Param("assetId") long assetId);

    List<CalibRow> findHistory(@Param("assetId") long assetId);

    CalibRow findCalib(@Param("calibId") long calibId);

    /** 그 날짜 전 마지막 유효 교정(OK·LIMITED, 무효 아님)일 — 2부 Q9 영향 구간 시작 */
    boolean inNgWindow(@Param("assetId") long assetId, @Param("day") java.time.LocalDate day);

    GaugeDtos.GaugeAsOf gaugeAsOf(@Param("assetId") long assetId, @Param("day") java.time.LocalDate day);

    java.time.LocalDate prevGoodCalibDate(@Param("assetId") long assetId, @Param("before") java.time.LocalDate before);

    /** 구간 [from, to) 에 그 게이지로 잰 살아 있는 측정값 → LOT·검사별 (from null = 처음부터) */
    List<io.smarthappy.module.qms.GaugeDtos.ImpactRow> impactLots(@Param("assetId") long assetId, @Param("from") java.time.OffsetDateTime from,
            @Param("to") java.time.OffsetDateTime to, @Param("limit") int limit);

    /** 사용 중 거래처 id (외부 교정 기관) */
    Long findActiveCust(@Param("custCd") String custCd);

    long insertCalib(@Param("f") CalibValue f, @Param("by") String by);

    int voidCalib(@Param("calibId") long calibId, @Param("reason") String reason, @Param("by") String by);

    int updateProfile(@Param("assetId") long assetId, @Param("gaugeClass") String gaugeClass,
            @Param("cycleDays") Integer cycleDays, @Param("rowVer") int rowVer, @Param("by") String by);
}
