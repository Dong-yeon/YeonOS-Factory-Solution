package io.smarthappy.module.master.equip;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.master.equip.AssetDtos.AssetRow;
import io.smarthappy.module.master.equip.AssetDtos.AssetSearch;
import io.smarthappy.module.master.equip.AssetDtos.AssetValue;
import io.smarthappy.module.master.equip.AssetDtos.ClassForm;
import io.smarthappy.module.master.equip.AssetDtos.ClassRow;
import io.smarthappy.module.master.equip.AssetDtos.EquipRow;
import io.smarthappy.module.master.equip.AssetDtos.EquipSearch;
import io.smarthappy.module.master.equip.AssetDtos.InstallRow;
import io.smarthappy.module.master.equip.EquipDtos.NodeRow;

@Mapper
public interface AssetMapper {

    // 설비 클래스
    List<ClassRow> findClasses();

    ClassRow findClass(@Param("classId") long classId);

    long insertClass(@Param("f") ClassForm f, @Param("by") String by);

    int updateClass(@Param("id") long id, @Param("f") ClassForm f, @Param("by") String by);

    // 설비
    List<EquipRow> findEquips(@Param("q") EquipSearch q);

    long countEquips(@Param("q") EquipSearch q);

    Cursor<EquipRow> cursorEquips(@Param("q") EquipSearch q);

    EquipRow findEquip(@Param("equipId") long equipId);

    EquipRow findEquipByCd(@Param("equipCd") String equipCd);

    long insertEquip(@Param("f") EquipValue f, @Param("by") String by);

    int updateEquip(@Param("id") long id, @Param("f") EquipValue f, @Param("by") String by);

    int disableEquip(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    List<NodeRow> placeNodes(@Param("plantCd") String plantCd);

    List<EquipRow> activeEquips();

    // 물리 자산
    List<AssetRow> findAssets(@Param("q") AssetSearch q);

    long countAssets(@Param("q") AssetSearch q);

    Cursor<AssetRow> cursorAssets(@Param("q") AssetSearch q);

    AssetRow findAsset(@Param("assetId") long assetId);

    Long findItemId(@Param("itemCd") String itemCd);

    String itemUseYn(@Param("itemId") long itemId);

    /** 사용 중지·설치 판정 전 대상 행 잠금(FOR UPDATE) — 설치 트리거의 FOR SHARE 와 직렬화. 없으면 null */
    Long lockAsset(@Param("assetId") long assetId);

    Long lockEquip(@Param("equipId") long equipId);

    long insertAsset(@Param("f") AssetValue f, @Param("by") String by);

    int updateAsset(@Param("id") long id, @Param("f") AssetValue f, @Param("by") String by);

    int disableAsset(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    // 설치 이력
    List<InstallRow> findInstalls(@Param("assetId") long assetId);

    InstallRow findInstall(@Param("installId") long installId);

    long insertInstall(@Param("assetId") long assetId, @Param("equipId") long equipId, @Param("at") OffsetDateTime at,
            @Param("remark") String remark, @Param("by") String by);

    int removeInstall(@Param("installId") long installId, @Param("at") OffsetDateTime at, @Param("by") String by);

    int voidInstall(@Param("installId") long installId, @Param("reason") String reason, @Param("by") String by);

    int openInstallsOfEquip(@Param("equipId") long equipId);

    /** 설비 값(검증 후) */
    record EquipValue(String plantCd, String equipCd, String equipNm, long classId, Long nodeId, String maker, String model,
            String serialNo, String assetNo, java.time.LocalDate installDate, String useYn, String remark, Integer rowVer) {
    }
}
