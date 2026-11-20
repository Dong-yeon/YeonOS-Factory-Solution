package io.smarthappy.module.aps;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 일일 작업 배정 (docs/38 18.4·18.5) 응답·내부 행 */
public final class DispatchDtos {

    private DispatchDtos() {
    }

    /** 현행 계획 머리 */
    @Getter
    @Setter
    public static class CurrentPlan {
        private long planId;
        private String planNo;
        private String timeZone;
        private OffsetDateTime appliedAt;
        private String appliedBy;
    }

    /** 현행 계획 공정 한 행 — seq 는 SQL 창 함수(자원 × 그날 안 시작 순) */
    @Getter
    @Setter
    public static class SlotRow {
        private long planId;
        private String planNo;
        private int seq;
        private String resKind;
        private Long equipId;
        private String equipCd;
        private Long nodeId;
        private String nodeCd;
        private long woId;
        private String woNo;
        private Long woProcId;
        private int opSeq;
        private String procCd;
        private String procNm;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
        private String itemCd;
        private String itemNm;
        private String qty;
        private String unitCd;
        private String woStatus;
        private String procStatus;
        private String curEquipCd;
    }

    /** 배정표 행 — outOfOrder = 같은 자원 앞 순번이 아직 대기인데 이 행이 이미 착수(AP-10 표시만) */
    public record BoardRow(int seq, String resKind, String resCd, long woId, String woNo, Long woProcId, int opSeq, String procCd,
            String procNm, OffsetDateTime startAt, OffsetDateTime endAt, String itemCd, String itemNm, String qty, String unitCd,
            String woStatus, String procStatus, String curEquipCd, boolean outOfOrder) {
    }

    /** 배정표 — planId null = 현행 계획 없음 */
    public record Board(String plantCd, LocalDate day, Long planId, String planNo, OffsetDateTime appliedAt, String timeZone,
            List<BoardRow> rows) {
    }
}
