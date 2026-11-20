package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** CMS 2부 C10 — 카운터 자동 입력: 아직 세지 않은 원장 행 찾기·출처 기록. 원장(MES 실적·상태 구간, P1 설치 이력)은 읽기만 */
@Mapper
public interface CmsCounterAutoMapper {

    /** 카운터 하나 × 원장 행 하나의 증가량 */
    @Getter
    @Setter
    class Src {
        private long counterId;
        private String srcKind;
        private long srcId;
        private BigDecimal qty;
    }

    /** 사용 중 카운터의 아직 세지 않은 원장 행 — 카운터를 만든 뒤 기록됐고 since 뒤에 일어난 것만 */
    List<Src> pending(@Param("since") OffsetDateTime since, @Param("limit") int limit);

    long insertAutoReading(@Param("counterId") long counterId, @Param("readAt") OffsetDateTime readAt, @Param("delta") BigDecimal delta,
            @Param("remark") String remark, @Param("by") String by);

    int insertSrc(@Param("s") Src s, @Param("readingId") long readingId);

    /** 카운터 소수 자릿수(C3, 만든 뒤 고정) */
    int scaleOf(@Param("counterId") long counterId);

    /** 지금까지 소비한 출처의 정확한 합 */
    BigDecimal srcTotal(@Param("counterId") long counterId);

    /** 지금까지 넣은 AUTO 증가량 합(무효 포함 — 무효로 한 것을 다시 넣지 않는다) */
    BigDecimal autoCredited(@Param("counterId") long counterId);
}
