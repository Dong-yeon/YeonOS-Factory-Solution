package io.smarthappy.module.production;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.production.WoDtos.BomRow;
import io.smarthappy.module.production.WoDtos.EquipOption;
import io.smarthappy.module.production.WoDtos.OrderLineOption;
import io.smarthappy.module.production.WoDtos.ParamRow;
import io.smarthappy.module.production.WoDtos.ProcRow;
import io.smarthappy.module.production.WoDtos.RefRow;
import io.smarthappy.module.production.WoDtos.RevOption;
import io.smarthappy.module.production.WoDtos.SnapBom;
import io.smarthappy.module.production.WoDtos.SrcBomLine;
import io.smarthappy.module.production.WoDtos.WoRow;
import io.smarthappy.module.production.WoDtos.WoValue;
import io.smarthappy.module.sales.SalesDtos.ItemOption;

@Mapper
public interface WoMapper {

    // ---------- 선택지 ----------

    List<PlantRow> findPlants();

    String plantUseYn(@Param("plantCd") String plantCd);

    ItemOption findItem(@Param("itemCd") String itemCd);

    /** 품목·공장의 확정 라우팅 중 기준일에 유효한 것(대체 번호 순) */
    List<RevOption> routingOptions(@Param("itemId") long itemId, @Param("plantCd") String plantCd, @Param("date") LocalDate date);

    List<RevOption> bomOptions(@Param("itemId") long itemId, @Param("date") LocalDate date);

    List<OrderLineOption> orderLineOptions(@Param("itemId") long itemId);

    List<EquipOption> equipOptions(@Param("plantCd") String plantCd);

    EquipOption findEquip(@Param("plantCd") String plantCd, @Param("equipCd") String equipCd);

    RefRow findRouting(@Param("id") long id);

    RefRow findBom(@Param("id") long id);

    /** 가상 조립 펼치기: 품목의 기준일 유효 확정 BOM(대체 번호 1) */
    RefRow effectiveBom(@Param("itemId") long itemId, @Param("date") LocalDate date);

    // ---------- 지시 ----------

    List<WoRow> findWos(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("status") String status, @Param("itemId") Long itemId, @Param("keyword") String keyword,
            @Param("orderLineId") Long orderLineId, @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + 페이지·정렬(허용 목록) */
    List<WoRow> findWoPage(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("status") String status, @Param("itemId") Long itemId, @Param("keyword") String keyword,
            @Param("orderLineId") Long orderLineId, @Param("page") PageQuery page);

    long countWos(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("status") String status, @Param("itemId") Long itemId, @Param("keyword") String keyword,
            @Param("orderLineId") Long orderLineId);

    WoRow findWo(@Param("id") long id);

    String lockWo(@Param("id") long id);

    long insertWo(@Param("woNo") String woNo, @Param("f") WoValue f, @Param("by") String by);

    /** 계획 상태: 수량·기준정보·일정 모두 */
    int updatePlanned(@Param("id") long id, @Param("f") WoValue f, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 발행 뒤: 일정·우선순위·비고만 */
    int updateSchedule(@Param("id") long id, @Param("f") WoValue f, @Param("rowVer") int rowVer, @Param("by") String by);

    int release(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    int cancel(@Param("id") long id, @Param("reason") String reason, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------- 스냅샷 ----------

    int insertProcs(@Param("woId") long woId, @Param("routingId") long routingId, @Param("by") String by);

    int insertParams(@Param("woId") long woId);

    List<SrcBomLine> bomLines(@Param("bomId") long bomId);

    int insertBoms(@Param("woId") long woId, @Param("lines") List<SnapBom> lines);

    List<ProcRow> findProcs(@Param("woId") long woId);

    List<ParamRow> findParams(@Param("woId") long woId);

    List<BomRow> findBoms(@Param("woId") long woId);

    ProcRow findProc(@Param("id") long id);

    Long procWoId(@Param("id") long id);

    int assignEquip(@Param("id") long id, @Param("equipId") Long equipId, @Param("rowVer") int rowVer, @Param("by") String by);
}
