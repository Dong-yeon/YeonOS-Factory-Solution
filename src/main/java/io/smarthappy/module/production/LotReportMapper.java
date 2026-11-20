package io.smarthappy.module.production;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.production.LotReportDtos.CloseRow;
import io.smarthappy.module.production.LotReportDtos.ParamRow;
import io.smarthappy.module.production.LotReportDtos.ProdRow;
import io.smarthappy.module.production.LotReportDtos.RcvRow;
import io.smarthappy.module.production.LotReportDtos.StateRow;

/** LOT 역추적 보고서 (P2 7b). 읽기만, 회사 조건은 RLS, 절마다 limit = 상한 + 1(잘림 판정) */
@Mapper
public interface LotReportMapper {

    /** 이 LOT 들을 만든 실적·재작업 처리 */
    List<ProdRow> production(@Param("lotIds") List<Long> lotIds, @Param("limit") int limit);

    /** 이 지시 공정들의 파라미터 실측 */
    List<ParamRow> params(@Param("procIds") List<Long> procIds, @Param("limit") int limit);

    /** 이 LOT 들을 만든 지시 공정 id */
    List<Long> procIds(@Param("lotIds") List<Long> lotIds);

    /** 이 LOT 들을 만든 지시 id */
    List<Long> woIds(@Param("lotIds") List<Long> lotIds);

    List<CloseRow> closes(@Param("woIds") List<Long> woIds, @Param("limit") int limit);

    List<StateRow> states(@Param("woIds") List<Long> woIds, @Param("limit") int limit);

    List<RcvRow> receipts(@Param("lotId") long lotId, @Param("limit") int limit);
}
