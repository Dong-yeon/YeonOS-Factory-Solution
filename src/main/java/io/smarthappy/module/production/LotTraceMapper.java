package io.smarthappy.module.production;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.production.link.LotTraceQuery.TraceRow;

/** LOT 계보 추적 계약 조회 (docs/25 4.49 — LotTraceQuery). 회사 조건은 RLS */
@Mapper
public interface LotTraceMapper {

    /** 루트들의 계보 — 루트 줄(depth 0) 포함, limit 줄까지 */
    List<TraceRow> trace(@Param("ids") List<Long> ids, @Param("back") boolean back, @Param("depth") int depth, @Param("limit") int limit);

    /** 이 LOT 들에서 같은 방향으로 더 이어지는 살아 있는 계보가 있나 — 깊이 상한 잘림 판정 */
    boolean more(@Param("ids") List<Long> ids, @Param("back") boolean back);
}
