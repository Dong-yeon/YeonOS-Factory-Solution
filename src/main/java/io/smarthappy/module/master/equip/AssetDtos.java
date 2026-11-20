package io.smarthappy.module.master.equip;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 설비·설비 클래스·물리 자산·설치 이력 DTO (P1 4b, docs/19) */
public final class AssetDtos {

    private AssetDtos() {
    }

    @Getter
    @Setter
    public static class ClassRow {
        private long classId;
        private String classCd;
        private String classNm;
        private int sortNo;
        private String useYn;
        private String remark;
        private int equipCnt;
        private int rowVer;
    }

    public record ClassForm(String classCd, String classNm, Integer sortNo, String useYn, String remark, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class EquipRow {
        private long equipId;
        private String plantCd;
        private String equipCd;
        private String equipNm;
        private long classId;
        private String classCd;
        private String classNm;
        private Long nodeId;
        private String nodePath;
        private String maker;
        private String model;
        private String serialNo;
        private String assetNo;
        private LocalDate installDate;
        private String useYn;
        private String remark;
        /** 지금 설치된 물리 자산 수 */
        private int assetCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    public static class EquipSearch {
        private final String plantCd;
        private final Long classId;
        private final String cdLike;
        private final String nmLike;
        private final String useYn;
        private final PageQuery page;

        public EquipSearch(String plantCd, Long classId, String cdLike, String nmLike, String useYn, PageQuery page) {
            this.plantCd = plantCd;
            this.classId = classId;
            this.cdLike = cdLike;
            this.nmLike = nmLike;
            this.useYn = useYn;
            this.page = page;
        }
    }

    public record EquipForm(String plantCd, String equipCd, String equipNm, Long classId, Long nodeId, String maker,
            String model, String serialNo, String assetNo, String installDate, String useYn, String remark, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class AssetRow {
        private long assetId;
        private String assetCd;
        private String assetType;
        private String assetNm;
        private String serialNo;
        private Long itemId;
        private String itemCd;
        private String itemNm;
        private Long lifeShots;
        private Integer calibCycleDays;
        private LocalDate lastCalibDate;
        private LocalDate nextCalibDate;
        private String useYn;
        private String remark;
        private Long curInstallId;
        private String curEquipCd;
        private String curEquipNm;
        private OffsetDateTime curInstalledAt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    public static class AssetSearch {
        private final String assetType;
        private final String cdLike;
        private final String nmLike;
        private final String useYn;
        private final PageQuery page;

        public AssetSearch(String assetType, String cdLike, String nmLike, String useYn, PageQuery page) {
            this.assetType = assetType;
            this.cdLike = cdLike;
            this.nmLike = nmLike;
            this.useYn = useYn;
            this.page = page;
        }
    }

    public record AssetForm(String assetCd, String assetType, String assetNm, String serialNo, String itemCd,
            String lifeShots, Integer calibCycleDays, String lastCalibDate, String useYn, String remark, Integer rowVer) {
    }

    /** 검증된 자산 값 (itemId 로 바꾼 것) */
    public record AssetValue(String assetCd, String assetType, String assetNm, String serialNo, Long itemId, Long lifeShots,
            Integer calibCycleDays, LocalDate lastCalibDate, String useYn, String remark, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class InstallRow {
        private long installId;
        private long assetId;
        private long equipId;
        private String equipCd;
        private String equipNm;
        /** 설비 공장 — 설치·해체 시각은 이 공장 시간대로 입력·표시(docs/25 D5) */
        private String plantCd;
        private String timeZone;
        private String nodePath;
        private OffsetDateTime installedAt;
        private OffsetDateTime removedAt;
        private String installedBy;
        private String removedBy;
        private OffsetDateTime recordedAt;
        private String voidYn;
        private String voidReason;
        private String remark;
    }

    /** 설치: 설비 코드 + 설치 시각(비우면 지금, 설비 공장 시간대 "yyyy-MM-ddTHH:mm" — 공장 시간대가 없으면 회사) */
    public record InstallRequest(String equipCd, String installedAt, String remark) {
    }

    public record RemoveRequest(String removedAt) {
    }

    public record VoidRequest(String reason) {
    }

    public record IdVer(Long id, Integer rowVer) {
    }

    public record DeleteRequest(List<IdVer> items) {
    }

    public record EquipExport(String plantCd, Long classId, String equipCd, String equipNm, String useYn, List<String> sort,
            List<String> columns) {
    }

    public record AssetExport(String assetType, String assetCd, String assetNm, String useYn, List<String> sort,
            List<String> columns) {
    }

    public enum EquipSort implements SortField {
        EQUIP_CD("equipCd"), EQUIP_NM("equipNm"), CLASS_CD("classCd"), UPDATED_AT("updatedAt");

        private final String field;

        EquipSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public enum AssetSort implements SortField {
        ASSET_CD("assetCd"), ASSET_NM("assetNm"), ASSET_TYPE("assetType"), NEXT_CALIB_DATE("nextCalibDate"),
        UPDATED_AT("updatedAt");

        private final String field;

        AssetSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
