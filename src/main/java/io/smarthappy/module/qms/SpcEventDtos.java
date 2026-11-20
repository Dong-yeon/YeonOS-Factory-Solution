package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** SPC 위반 이벤트 원장 (docs/29_P3 19장) */
public final class SpcEventDtos {

    private SpcEventDtos() {
    }

    /** 판정 확정된 검사의 계량형 행 — 이벤트를 볼 단위 */
    @Getter
    @Setter
    public static class JudgedLine {
        private long inspLineId;
        private long charId;
        private long itemId;
        private String inspType;
        private Long procId;
        private String inspNo;
        private OffsetDateTime judgedAt;
        private long lotId;
        /** VARIABLE · ATTRIBUTE — 계수형은 p 관리도(docs/29_P3 28장) */
        private String dataType;
    }

    /** 목록·상세 머리 — status 는 처리 기록에서 파생(VOID > CLOSED > ACKED > OPEN) */
    @Getter
    @Setter
    public static class EventRow {
        private long eventId;
        private long limitId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private long charId;
        private String charNo;
        private String charNm;
        private String inspType;
        private Long procId;
        private String procCd;
        private long inspId;
        private String inspNo;
        private long lotId;
        private String lotNo;
        private long inspLineId;
        private String ruleCd;
        private int n;
        private BigDecimal pointMean;
        private BigDecimal pointRange;
        /** XBAR_R(평균·범위) · P(불량률·불량 수) */
        private String chartType;
        private BigDecimal z;
        private BigDecimal center;
        private BigDecimal sigma;
        private OffsetDateTime occurredAt;
        private OffsetDateTime recordedAt;
        private String status;
        private String lastBy;
        private OffsetDateTime lastAt;
    }

    @Getter
    @Setter
    public static class ActRow {
        private long actId;
        private String actType;
        private String note;
        private String linkType;
        private String linkNo;
        private OffsetDateTime actedAt;
        private String actedBy;
        /** 이 조치(ACTION)에서 고른 OCAP 항목 — 고른 순간 문구 사본(docs/29_P3 26장 OC-8) */
        private List<OcapDtos.PickRow> ocaps = List.of();
    }

    /** held = 이 위반 근거로 LOT 이 보류 중(SV-6 자동 보류, MES 보류 근거 출처 QMS_SPC × 이벤트 id) */
    public record EventDetail(EventRow event, List<ActRow> acts, boolean held) {
    }

    /** 처리 입력 — actType = ACK·ACTION·CLOSE·VOID, linkType = NCR·CONTAIN·CAPA(ACTION·CLOSE 만, 선택), ocapIds = OCAP 항목(ACTION 만, 선택, 26장) */
    public record ActForm(String actType, String note, String linkType, String linkNo, List<Long> ocapIds) {
    }

    public record EventPage(List<EventRow> list, Long total) {
    }

    /** 그리드 정렬 허용 — field = 그리드 열, 이름 = 매퍼 choose 키 */
    public enum EventSort implements io.smarthappy.platform.common.query.SortField {
        OCCURRED_AT("occurredAt"), ITEM_CD("itemCd"), RULE_CD("ruleCd"), STATUS("status");

        private final String field;

        EventSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
