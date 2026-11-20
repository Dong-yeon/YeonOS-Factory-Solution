package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** CMS 1부 C5 DTO — 설비 보안 대장 (docs/33 4.6) */
public final class CmsSecDtos {

    private CmsSecDtos() {
    }

    /** 대장 한 행 = 사용 중 설비 + 보안 행(없으면 secId 없음 = 미등록). warns·warnText 는 조회 때 계산 */
    @Getter
    @Setter
    public static class SecRow {
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String plantCd;
        private String classNm;
        private Long secId;
        private String networkYn;
        private String osNm;
        private String osVer;
        private LocalDate osEosDate;
        private LocalDate lastScanDate;
        private String scanTool;
        private String targetSl;
        private String currentSl;
        private String usbCtrlYn;
        private String remoteAccess;
        private String remoteText;
        private LocalDate reviewDate;
        private String remark;
        private Integer rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** 계산: 경고 코드(CMS_SEC_WARN)·글자(엑셀)·검사 기한(검사일 + 주기)·등급 글자(현재 / 목표 — grid 칸) */
        private List<String> warns;
        private String warnText;
        private LocalDate scanDueDate;
        private String levelText;
    }

    /** 저장 — rowVer 없으면 새 행(이미 있으면 409), 있으면 수정 */
    public record SecForm(String networkYn, String osNm, String osVer, String osEosDate, String lastScanDate, String scanTool,
            String targetSl, String currentSl, String usbCtrlYn, String remoteAccess, String remoteText, String reviewDate, String remark,
            Integer rowVer) {
    }

    /** 엑셀 조건 (조회와 같은 조건) */
    public record SecExport(String keyword, String plantCd, String warn) {
    }

    /** grid 정렬 허용 목록(docs/40 10.2) — 경고는 조회 때 계산이라 grid 는 메모리에서 정렬한다(행 수 = 사용 중 설비, 상한 MAX_ROWS) */
    public enum SecSort implements SortField {
        PLANT_CD("plantCd"), EQUIP_CD("equipCd"), EQUIP_NM("equipNm"), OS_NM("osNm"), OS_EOS_DATE("osEosDate"),
        LAST_SCAN_DATE("lastScanDate"), LEVEL_TEXT("levelText"), REVIEW_DATE("reviewDate"), WARN_TEXT("warnText");

        private final String field;

        SecSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 한 쪽 */
    public record SecPage(List<SecRow> list, Long total) {
    }
}
