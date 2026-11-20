package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.ContainDtos.ContainLotRow;
import io.smarthappy.module.qms.ContainDtos.ContainRow;
import io.smarthappy.module.qms.ContainDtos.ItemRef;
import io.smarthappy.module.qms.ContainDtos.LotRow;
import io.smarthappy.module.qms.ContainDtos.NcrRef;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface ContainMapper {

    List<ContainRow> findList(@Param("keywordLike") String keywordLike, @Param("status") String status, @Param("limit") int limit);

    List<ContainRow> findPage(@Param("keywordLike") String keywordLike, @Param("status") String status, @Param("page") PageQuery page);

    long countList(@Param("keywordLike") String keywordLike, @Param("status") String status);

    ContainRow findById(@Param("id") long id);

    List<ContainLotRow> findLots(@Param("id") long id);

    ItemRef findItem(@Param("itemCd") String itemCd);

    NcrRef findNcrByNo(@Param("ncrNo") String ncrNo);

    /** 품목 × 기간 [from, to) 의 LOT — 발생 시각 순 */
    List<LotRow> findCandidates(@Param("itemId") long itemId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("limit") int limit);

    List<LotRow> findLotsByIds(@Param("ids") Collection<Long> ids);

    long nextId();

    int insert(@Param("id") long id, @Param("no") String no, @Param("itemId") long itemId, @Param("ncrId") Long ncrId,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("reason") String reason, @Param("lotCnt") int lotCnt,
            @Param("by") String by);

    int insertLot(@Param("id") long id, @Param("lotId") long lotId, @Param("by") String by);

    int releaseLot(@Param("id") long id, @Param("lotId") long lotId, @Param("reason") String reason, @Param("by") String by);

    /** 봉쇄 머리 잠금 — LOT 해제(포트) 뒤, 남은 보류를 세기 직전(잠금 순서 LOT → qms_contain) */
    Long lockContain(@Param("id") long id);

    int heldCount(@Param("id") long id);

    int close(@Param("id") long id, @Param("by") String by);
}
