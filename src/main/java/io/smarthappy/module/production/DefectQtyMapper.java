package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 월별 불량 수량 (docs/25 4.48 — DefectQtyQuery). 회사 조건은 RLS */
@Mapper
public interface DefectQtyMapper {

    @Getter
    @Setter
    class Row {
        /** YYYY-MM (회사 시간대) */
        private String ym;
        private long itemId;
        private String kind;
        private BigDecimal qty;
        private String unitCd;
        private String source;
    }

    List<Row> monthly(@Param("plantCd") String plantCd, @Param("start") Instant start, @Param("end") Instant end, @Param("zone") String zone);
}
