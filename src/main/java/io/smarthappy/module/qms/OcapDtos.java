package io.smarthappy.module.qms;

import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** OCAP 표준 조치 목록 (docs/29_P3 26장) */
public final class OcapDtos {

    private OcapDtos() {
    }

    /** ruleCd null = 모든 규칙 공통 */
    @Getter
    @Setter
    public static class OcapRow {
        private long ocapId;
        private String ruleCd;
        private int seq;
        private String actionText;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;

        /** 목록 표시·정렬용 — 공통이면 "*" (화면이 "공통" 으로) */
        public String getRuleKey() {
            return ruleCd == null ? "*" : ruleCd;
        }
    }

    /** 저장 입력 — ruleCd 비우면 공통, rowVer 는 수정 때만 */
    public record OcapForm(String ruleCd, Integer seq, String actionText, String useYn, Integer rowVer) {
    }

    /** SPC 위반 처리 이력에 붙는 고른 항목 사본(고른 순간 문구·규칙) */
    @Getter
    @Setter
    public static class PickRow {
        private long actId;
        private long ocapId;
        private String ruleCd;
        private String actionText;
    }

    public record OcapPage(List<OcapRow> list, Long total) {
    }

    public enum OcapSort implements SortField {
        RULE("ruleKey"), SEQ("seq"), TEXT("actionText"), UPDATED_AT("updatedAt");

        private final String field;

        OcapSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
