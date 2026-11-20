package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** P3 QMS 2부 Q7 초품(셋업 검증) DTO (docs/29_P3 12.3) */
public final class SetupDtos {

    private SetupDtos() {
    }

    /** 판정 한 줄(살아 있는 것 또는 목록의 최근 것) */
    @Getter
    @Setter
    public static class VerdictRow {
        private long verifId;
        private long ecoId;
        private long lotId;
        private String lotNo;
        private long inspId;
        private String inspNo;
        private String inspType;
        private String result;
        private String status;
        private String note;
        private String voidReason;
        private OffsetDateTime createdAt;
        private String createdBy;
        private int rowVer;
        // 목록에서 계약으로
        private String ecoNo;
        private String ecoTitle;
    }

    /** 근거 후보 — 그 LOT 의 판정 확정 검사 */
    @Getter
    @Setter
    public static class InspOption {
        private long inspId;
        private long lotId;
        private String inspNo;
        private String inspType;
        private String result;
        private OffsetDateTime judgedAt;
    }

    /** 첫 LOT 한 줄 = PLM 표시 + 살아 있는 판정 + 근거 후보 */
    public record FirstLot(long lotId, String lotNo, int seqNo, OffsetDateTime occurredAt, VerdictRow verdict, List<InspOption> candidates) {
    }

    public record EcoHead(long ecoId, String ecoNo, String title, String changeKind, String status, LocalDate effectiveDate) {
    }

    public record SetupDetail(EcoHead eco, List<FirstLot> lots, int ok, int ng, int pending) {
    }

    @Getter
    @Setter
    public static class InspRef {
        private long inspId;
        private long lotId;
        private String status;
        private String result;
    }

    public record RecordForm(Long lotId, Long inspId, String note) {
    }

    public record VoidForm(Integer rowVer, String reason) {
    }

    /** 판정 수(살아 있는 것) */
    @Getter
    @Setter
    public static class Counts {
        private int ok;
        private int ng;
    }
}
