package io.smarthappy.module.qms;

import java.time.OffsetDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/**
 * 현장 단말 검사 (docs/29_P3 53장) — QMS 표 + MES 단말·작업자·지시·실적·LOT 은 읽기만(잠금 없음).
 * 검사 쓰기는 InspService 경로(createAs·recordAs·judgeAs)만 쓴다.
 */
@Mapper
public interface FloorInspMapper {

    /** 등록 단말(폐기 안 됨) — RLS 로 다른 회사 단말은 null */
    Terminal findTerminal(@Param("terminalId") long terminalId);

    /** 같은 회사·사용 중 작업자 */
    boolean workerActive(@Param("workerId") long workerId);

    /** 지시 공정 → 지시·공정·품목·공장 */
    WoProc findWoProc(@Param("woProcId") long woProcId);

    /** 추천 LOT — 이 지시 공정 실적의 산출 LOT, first = 첫(아니면 최근). 정정(역분개)된 실적 제외 */
    String suggestedLot(@Param("woProcId") long woProcId, @Param("first") boolean first);

    /** 그 지시의 산출 LOT 인가 — LOT id(아니면 null) */
    Long lotOfWo(@Param("lotNo") String lotNo, @Param("woId") long woId);

    /** 그 유형·LOT 번호·공정의 열린 검사(가장 최근) */
    InspRef findOpen(@Param("type") String type, @Param("lotNo") String lotNo, @Param("procId") Long procId);

    /** 그 지시·공정·유형의 가장 최근 판정 확정 */
    InspRef findLastJudged(@Param("type") String type, @Param("woId") long woId, @Param("procId") Long procId);

    /** 단말에서 연 요청 id 로 */
    InspRef findByOpenReq(@Param("req") String req);

    /** 검사 머리 + 지시 공장(단말 범위 확인) */
    FloorHead findHead(@Param("inspId") long inspId);

    @Getter
    @Setter
    class Terminal {
        private long terminalId;
        private String terminalCd;
        private String plantCd;
    }

    @Getter
    @Setter
    class WoProc {
        private long woProcId;
        private long woId;
        private long procId;
        private String procCd;
        private long itemId;
        private Long itemClassId;
        private String plantCd;
    }

    @Getter
    @Setter
    class InspRef {
        private long inspId;
        private String inspNo;
        private String result;
        private OffsetDateTime judgedAt;
    }

    @Getter
    @Setter
    class FloorHead {
        private long inspId;
        private String inspNo;
        private String inspType;
        private String status;
        private String result;
        private String lotNo;
        private String itemCd;
        private String itemNm;
        private String procCd;
        /** LOT 의 지시 공장(생산 LOT 이 아니면 null — 단말 범위 밖) */
        private String plantCd;
    }
}
