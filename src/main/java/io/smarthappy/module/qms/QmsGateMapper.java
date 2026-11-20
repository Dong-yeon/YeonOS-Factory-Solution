package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 착수 게이트 QMS 규칙(Q9, docs/29_P3 12.11) 읽기 — 잠금 없음 */
@Mapper
public interface QmsGateMapper {

    /** 살아 있는 초품 판정 한 줄 (ECO × LOT) */
    @Getter
    @Setter
    class VerifKey {
        private long ecoId;
        private long lotId;
        private String result;
    }

    /** 종결 안 된 부적합(OPEN·PENDING) — LOT 마다 오래된 것부터 */
    @Getter
    @Setter
    class OpenNcr {
        private long lotId;
        private String ncrNo;
    }

    /** 게이지 하나의 판정 재료 — 최근 유효 교정(GaugeMapper 와 같은 순서) */
    @Getter
    @Setter
    class GaugeRef {
        private String gaugeClass;
        private String useYn;
        private String lastResult;
        private LocalDate nextDueDate;
    }

    List<VerifKey> findLiveVerifs(@Param("lotIds") Collection<Long> lotIds);

    List<OpenNcr> findOpenNcrs(@Param("lotIds") Collection<Long> lotIds);

    Long findItemClassId(@Param("itemId") long itemId);

    List<String> findPlanGaugeClasses(@Param("planId") long planId);

    List<GaugeRef> findGauges(@Param("classes") Collection<String> classes);
}
