package io.smarthappy.module.aps;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.aps.MrpDtos.CustSrc;
import io.smarthappy.module.aps.MrpDtos.ElemInsert;
import io.smarthappy.module.aps.MrpDtos.ElemRow;
import io.smarthappy.module.aps.MrpDtos.ExcInsert;
import io.smarthappy.module.aps.MrpDtos.ExcRow;
import io.smarthappy.module.aps.MrpDtos.ItemInsert;
import io.smarthappy.module.aps.MrpDtos.ItemRow;
import io.smarthappy.module.aps.MrpDtos.ItemSrc;
import io.smarthappy.module.aps.MrpDtos.MpsCellSrc;
import io.smarthappy.module.aps.MrpDtos.MrpInsert;
import io.smarthappy.module.aps.MrpDtos.MrpRow;
import io.smarthappy.module.aps.MrpDtos.ParamSrc;
import io.smarthappy.module.aps.MrpDtos.PegRow;
import io.smarthappy.module.aps.MrpDtos.WoSrc;

/** MRP (docs/38 19장) — 회사 조건은 RLS. 지시·입고·MPS·기준정보는 읽기만, aps_mrp* 에만 쓴다 */
@Mapper
interface MrpMapper {

    // ---------- 계산 입력 ----------

    /** 확정 MPS 칸 중 계획 수량 > 0 */
    List<MpsCellSrc> findMpsCells(@Param("mpsId") long mpsId);

    /** 그 공장 계획 기준정보 사용 중 행의 품목 */
    List<Long> findParamItems(@Param("plantCd") String plantCd);

    /** 그 공장 열린 지시(PLANNED·RELEASED·IN_PROGRESS) — 남은 = 계획 수량 − 생산 입고 순수량 */
    List<WoSrc> findOpenWos(@Param("plantCd") String plantCd);

    List<ItemSrc> findItems(@Param("ids") Collection<Long> itemIds);

    List<ParamSrc> findParams(@Param("plantCd") String plantCd, @Param("ids") Collection<Long> itemIds);

    List<CustSrc> findCusts(@Param("ids") Collection<Long> custIds);

    // ---------- 저장 ----------

    long insertMrp(@Param("m") MrpInsert m, @Param("by") String by);

    int insertItems(@Param("mrpId") long mrpId, @Param("rows") List<ItemInsert> rows);

    int insertElems(@Param("mrpId") long mrpId, @Param("rows") List<ElemInsert> rows);

    int insertPegs(@Param("mrpId") long mrpId, @Param("rows") List<MrpEngine.Peg> rows);

    int insertExcs(@Param("mrpId") long mrpId, @Param("rows") List<ExcInsert> rows);

    String lockMrp(@Param("id") long mrpId);

    int discard(@Param("id") long mrpId, @Param("reason") String reason, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------- 조회 ----------

    List<MrpRow> findList(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("limit") int limit);

    MrpRow findMrp(@Param("id") long mrpId);

    List<ItemRow> findItemRows(@Param("mrpId") long mrpId, @Param("itemId") Long itemId);

    List<ExcRow> findExcs(@Param("mrpId") long mrpId, @Param("limit") int limit);

    List<ElemRow> findOrders(@Param("mrpId") long mrpId, @Param("itemId") Long itemId, @Param("kind") String kind, @Param("limit") int limit);

    List<ElemRow> findElems(@Param("mrpId") long mrpId, @Param("itemId") Long itemId, @Param("seqs") Collection<Integer> seqs);

    /** 요소들에 걸린 페깅(수요 쪽 또는 공급 쪽) */
    List<PegRow> findPegs(@Param("mrpId") long mrpId, @Param("demandSeqs") Collection<Integer> demandSeqs,
            @Param("supplySeqs") Collection<Integer> supplySeqs);
}
