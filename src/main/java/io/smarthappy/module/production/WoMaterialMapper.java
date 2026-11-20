package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 지시별 남은 자재 소요 (계약 WoMaterialQuery, docs/25 4.18) — 잠금 없는 조회만. 회사 조건은 RLS */
@Mapper
public interface WoMaterialMapper {

    /** 남은 소요가 있을 수 있는 지시(계획·발행·진행 중), wo_id 순 */
    List<WoHead> heads(@Param("ids") List<Long> ids);

    /** 발행 스냅샷 소요 — (지시, 품목, 투입 공정, 단위) 합계, 부산물 제외 */
    List<NeedRow> snapshot(@Param("ids") List<Long> ids);

    /** LOT 투입 순수량(되돌려진 투입 제외) — (지시, 자재 품목, 투입한 공정 순번, 단위) 합계 */
    List<NeedRow> consumed(@Param("ids") List<Long> ids);

    @Getter
    @Setter
    class WoHead {
        private long woId;
        private String status;
        private Long bomId;
        private BigDecimal planQty;
        private LocalDate planStart;
    }

    @Getter
    @Setter
    class NeedRow {
        private long woId;
        private long itemId;
        private Integer opSeq;
        private String unitCd;
        private BigDecimal qty;
    }
}
