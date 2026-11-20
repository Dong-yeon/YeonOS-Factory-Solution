package io.smarthappy.module.aps;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.aps.MpsDtos.CellInsert;
import io.smarthappy.module.aps.MpsDtos.CellLock;
import io.smarthappy.module.aps.MpsDtos.CellSrc;
import io.smarthappy.module.aps.MpsDtos.FirmedCellSrc;
import io.smarthappy.module.aps.MpsDtos.ItemLookup;
import io.smarthappy.module.aps.MpsDtos.ItemRow;
import io.smarthappy.module.aps.MpsDtos.MpsInsert;
import io.smarthappy.module.aps.MpsDtos.MpsItemSrc;
import io.smarthappy.module.aps.MpsDtos.MpsRow;
import io.smarthappy.module.aps.MpsDtos.RowInsert;
import io.smarthappy.module.aps.MpsDtos.RowSrc;
import io.smarthappy.module.aps.MpsDtos.WoSupplySrc;

/** MPS 기준생산계획 (docs/38 16장) — 회사 조건은 RLS. 지시·입고는 읽기만 */
@Mapper
interface MpsMapper {

    // ---------- MPS 품목 ----------

    List<io.smarthappy.module.master.equip.EquipDtos.PlantRow> findPlants();

    List<ItemRow> findItems(@Param("plantCd") String plantCd);

    ItemRow findItem(@Param("id") long mpsItemId);

    ItemLookup lookupItem(@Param("itemCd") String itemCd);

    long insertItem(@Param("plantCd") String plantCd, @Param("itemId") long itemId, @Param("remark") String remark,
            @Param("useYn") String useYn, @Param("by") String by);

    int updateItem(@Param("id") long mpsItemId, @Param("remark") String remark, @Param("useYn") String useYn,
            @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------- 계산 입력 ----------

    String plantUseYn(@Param("plantCd") String plantCd);

    List<MpsItemSrc> findMpsItems(@Param("plantCd") String plantCd);

    List<WoSupplySrc> findWoSupply(@Param("plantCd") String plantCd, @Param("itemIds") Collection<Long> itemIds,
            @Param("last") LocalDate last);

    MpsRow findCurrent(@Param("plantCd") String plantCd);

    List<FirmedCellSrc> findFirmedCells(@Param("mpsId") long mpsId);

    // ---------- 저장 ----------

    long insertMps(@Param("m") MpsInsert m, @Param("by") String by);

    long insertRow(@Param("mpsId") long mpsId, @Param("r") RowInsert r);

    void insertCells(@Param("mpsId") long mpsId, @Param("cells") List<CellInsert> cells);

    // ---------- 조회 ----------

    List<MpsRow> findList(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("limit") int limit);

    MpsRow findMps(@Param("id") long mpsId);

    List<RowSrc> findRows(@Param("id") long mpsId);

    List<CellSrc> findCells(@Param("id") long mpsId);

    // ---------- 편집·확정·폐기 ----------

    String lockMps(@Param("id") long mpsId);

    String lockFirmPlant(@Param("plantCd") String plantCd);

    List<CellLock> lockCells(@Param("mpsId") long mpsId, @Param("ids") Collection<Long> cellIds);

    void updateCell(@Param("id") long cellId, @Param("planQty") java.math.BigDecimal planQty, @Param("reason") String reason);

    int touch(@Param("id") long mpsId, @Param("rowVer") int rowVer, @Param("by") String by);

    int supersede(@Param("id") long mpsId, @Param("by") String by);

    int firm(@Param("id") long mpsId, @Param("rowVer") int rowVer, @Param("by") String by);

    int discard(@Param("id") long mpsId, @Param("reason") String reason, @Param("rowVer") int rowVer, @Param("by") String by);
}
