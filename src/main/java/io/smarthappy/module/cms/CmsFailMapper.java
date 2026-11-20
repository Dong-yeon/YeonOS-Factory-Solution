package io.smarthappy.module.cms;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/**
 * 작업지시 기준 고장 (docs/33 16.3~16.6) — KPI 의 작업지시 기준 MTBF·MTTR·MTTA(F3·G2)와 고장 분석(G1)이 **같은 고장 정의**
 * ({@code failWhere}: 고장 수리 BM · 완료 DONE·CLOSED · 설비 대상 · 고장 시각 = coalesce(정지 시작, 신고) 가 [from, until))를 쓴다.
 * 상태 원장(cms_equip_state_log)은 읽지 않는다 — 그 값은 {@link CmsKpiMapper}.
 */
@Mapper
public interface CmsFailMapper {

    /** 설비 한 대의 작업지시 기준 합 — 정지는 기록 있는 것(down_yn = Y)만, 반응(신고 → 착수)은 착수 칸이 있는 것만 */
    @Getter
    @Setter
    class WoStatRow {
        private long equipId;
        private long failures;
        private long downSec;
        private long downCount;
        private long respSec;
        private long respCount;
    }

    List<WoStatRow> woStats(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("until") OffsetDateTime until,
            @Param("keyword") String keyword, @Param("classId") Long classId);

    /** 파레토 막대 한 줄 — keyCd null = 값 없음 */
    @Getter
    @Setter
    class BarRow {
        private String keyCd;
        private String keyNm;
        private long failures;
        private long downSec;
    }

    List<BarRow> pareto(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("until") OffsetDateTime until,
            @Param("keyword") String keyword, @Param("classId") Long classId, @Param("axis") String axis);

    /** 수리 시간 분해 한 묶음(G2) — 합과 그 값이 있는 건수 */
    @Getter
    @Setter
    class GroupRow {
        private String keyCd;
        private String keyNm;
        private Long equipId;
        private long failures;
        private long downSec;
        private long downCount;
        private long waitSec;
        private long waitCount;
        private long repairSec;
        private long repairCount;
        private long laborMin;
        private long laborCount;
    }

    List<GroupRow> breakdown(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("until") OffsetDateTime until,
            @Param("keyword") String keyword, @Param("classId") Long classId, @Param("group") String group);

    /** 반복 고장 — 설비 × 원인 */
    @Getter
    @Setter
    class RepeatRow {
        private String equipCd;
        private String equipNm;
        private String causeCd;
        private String causeNm;
        private long repeats;
        private OffsetDateTime lastAt;
        private long minGapSec;
    }

    List<RepeatRow> repeats(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime lookFrom, @Param("periodFrom") OffsetDateTime periodFrom,
            @Param("until") OffsetDateTime until, @Param("keyword") String keyword, @Param("classId") Long classId, @Param("days") int days);

    /** 드릴다운 한 줄 */
    @Getter
    @Setter
    class FailMwoRow {
        private long mwoId;
        private String mwoNo;
        private String status;
        private String title;
        private String equipCd;
        private String equipNm;
        private OffsetDateTime failAt;
        private String symptomCd;
        private String symptomNm;
        private String causeCd;
        private String causeNm;
        private String actionCd;
        private String actionNm;
        private Long downSec;
        private Long waitSec;
        private Long repairSec;
        private String assigneeNm;
    }

    List<FailMwoRow> mwos(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("until") OffsetDateTime until,
            @Param("keyword") String keyword, @Param("classId") Long classId, @Param("axis") String axis, @Param("key") String key,
            @Param("keyNone") boolean keyNone, @Param("limit") int limit);
}
