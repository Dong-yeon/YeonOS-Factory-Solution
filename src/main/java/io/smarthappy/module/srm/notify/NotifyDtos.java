package io.smarthappy.module.srm.notify;

import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** 통지 이력 DTO (SRM S4) — sys_outbox 의 SRM_PO_* 토픽을 읽는다. 받는 주소는 가려서 내보낸다 */
public final class NotifyDtos {

    private NotifyDtos() {
    }

    @Getter
    @Setter
    public static class NoticeRow {
        private long outboxId;
        private String topic;
        private String bizKey;
        private Long poId;
        /** 클레임 통지(SRM_CLAIM_SENT)면 클레임 id — 그때 poNo 칸은 클레임 번호 */
        private Long claimId;
        private String poNo;
        private Integer rev;
        private String custNm;
        private String toAddr;
        private String status;
        private int attempts;
        private String lastError;
        private OffsetDateTime createdAt;
        private OffsetDateTime sentAt;

        /** grid 표시용 받는 주소 — hb.grid 는 null 칸에 format 을 부르지 않으므로 없으면 빈 글자(화면이 "(주소 없음)") */
        public String getToAddrText() {
            return toAddr == null ? "" : toAddr;
        }
    }

    /** 목록·grid 공통 조건(검증된 값) — MyBatis OGNL 이 읽게 getter 클래스. from·to = 회사 시간대 하루 경계 */
    @Getter
    @AllArgsConstructor
    public static class NoticeCrit {
        private OffsetDateTime from;
        private OffsetDateTime to;
        private String status;
        private Long poId;
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum NoticeSort implements SortField {
        CREATED_AT("createdAt"), TOPIC("topic"), PO_NO("poNo"), SUPPLIER("custNm"), STATUS("status");

        private final String field;

        NoticeSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record NoticePage(List<NoticeRow> list, Long total) {
    }
}
