package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** CMS 1부 C4 DTO — 설비 예비품·작업지시 사용 예비품 (docs/33 4.5). 수량은 응답에서 글자(품목 단위 자릿수로) */
public final class CmsSpareDtos {

    private CmsSpareDtos() {
    }

    @Getter
    @Setter
    public static class SpareRow {
        private long spareId;
        /** EQUIP(설비 한 대) / CLASS(설비 클래스 — 그 클래스 설비 모두) */
        private String targetKind;
        private Long equipId;
        private Long equipClassId;
        private String targetCd;
        private String targetNm;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String spec;
        private String itemType;
        private String unitCd;
        private String itemUseYn;
        private BigDecimal installQty;
        private int scale;
        private String keySpareYn;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;

        public String getInstallQtyText() {
            return CmsPmDtos.text(installQty, scale);
        }
    }

    public record SpareForm(String targetKind, String targetCd, String itemCd, String installQty, String keySpareYn, String useYn,
            String remark, Integer rowVer) {
    }

    /** 품목 찾기 결과 (같은 회사, 사용 여부는 서비스가 본다) */
    @Getter
    @Setter
    public static class ItemRef {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String spec;
        private String itemType;
        private String unitCd;
        private String useYn;
    }

    @Getter
    @Setter
    public static class PartRow {
        private long partId;
        private long mwoId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String spec;
        private BigDecimal qty;
        private String unitCd;
        private int scale;
        private String remark;
        private OffsetDateTime createdAt;
        private String createdBy;
        /** 반납 누계(C18) — 완료·확인 작업지시에서 창고 연계가 알려 준 값. null = 모름(창고 연계 없음·출고 원장 없음·완료 전) */
        private BigDecimal returnedQty;

        public String getQtyText() {
            return CmsPmDtos.text(qty, scale);
        }

        public String getReturnedText() {
            return returnedQty == null ? null : CmsPmDtos.text(returnedQty, scale);
        }

        /** 남은 반납 가능 = 사용 − 누계(안내만 — 상한 검사는 WMS). 모르면 null */
        public String getReturnableText() {
            return returnedQty == null ? null : CmsPmDtos.text(qty.subtract(returnedQty).max(BigDecimal.ZERO), scale);
        }
    }

    public record PartForm(String itemCd, String qty, String remark) {
    }

    /** 작업지시 화면의 품목 후보: 대상 설비·그 클래스의 사용 중 예비품 연결 */
    public record PartOption(String itemCd, String itemNm, String spec, String unitCd, String keySpareYn, String source) {
    }

    /** 사용 이력 행 — 작업지시 하나의 품목 하나 */
    @Getter
    @Setter
    public static class UsageRow {
        private long partId;
        private long mwoId;
        private String mwoNo;
        private String mwoType;
        private String mwoStatus;
        private String targetCd;
        private String targetNm;
        private String itemCd;
        private String itemNm;
        private BigDecimal qty;
        private String unitCd;
        private int scale;
        /** 사용일 = 작업지시 완료 시각(완료 전은 기록 시각) — docs/33 16.1 F1 */
        private OffsetDateTime usedAt;
        private OffsetDateTime createdAt;
        private String createdBy;

        public String getQtyText() {
            return CmsPmDtos.text(qty, scale);
        }

        /** 엑셀(C15) — 사용일 글자(서비스가 회사 시간대로 바꿔 둔 값) */
        public String getUsedAtText() {
            return usedAt == null ? null : usedAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }

        /** 기록 시각 글자 */
        public String getCreatedAtText() {
            return createdAt == null ? null : createdAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    /** 예비품 반납(완료 뒤 덜 쓴 것) — clientReqId 는 두 번 누름 방지(WMS 가 같은 id 면 처음 결과) */
    public record ReturnForm(String qty, String reason, String clientReqId) {
    }

    /** 엑셀(C15) — 연결 표 조건 = 목록 조회와 같다 */
    public record SpareExport(String keyword, String useYn, String targetKind) {
    }

    /** 엑셀(C15) — 사용 이력 조건 = 사용 이력 조회와 같다 */
    public record UsageExport(String from, String to, String equipCd, String classCd, String itemCd) {
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum SpareSort implements io.smarthappy.platform.common.query.SortField {
        TARGET_KIND("targetKind"), TARGET_CD("targetCd"), ITEM_CD("itemCd"), ITEM_NM("itemNm"), INSTALL_QTY("installQty"),
        KEY_SPARE("keySpareYn"), USE_YN("useYn");

        private final String field;

        SpareSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record SparePage(List<SpareRow> list, Long total) {
    }
}
