package io.smarthappy.module.production;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.production.LotDtos.EdgeRow;
import io.smarthappy.module.production.LotDtos.LockedLot;
import io.smarthappy.module.production.LotDtos.LotRow;
import io.smarthappy.module.production.LotDtos.NewEdge;
import io.smarthappy.module.production.LotDtos.NewLot;
import io.smarthappy.module.production.LotDtos.TraceRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface LotMapper {

    /** from·to 는 회사 날짜 — zone(회사 시간대)으로 하루 경계를 잡는다 */
    List<LotRow> findLots(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("zone") String zone,
            @Param("itemId") Long itemId,
            @Param("keyword") String keyword, @Param("origin") String origin, @Param("woId") Long woId,
            @Param("holdOnly") boolean holdOnly, @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + 페이지·정렬(허용 목록) */
    List<LotRow> findLotPage(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("zone") String zone,
            @Param("itemId") Long itemId, @Param("keyword") String keyword, @Param("origin") String origin, @Param("woId") Long woId,
            @Param("holdOnly") boolean holdOnly, @Param("page") PageQuery page);

    long countLots(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("zone") String zone,
            @Param("itemId") Long itemId, @Param("keyword") String keyword, @Param("origin") String origin, @Param("woId") Long woId,
            @Param("holdOnly") boolean holdOnly);

    LotRow findLot(@Param("id") long id);

    LotRow findLotByNo(@Param("lotNo") String lotNo);

    /** 부모로 쓰기 전에 잠근다(잔량 확인 — 트리거도 잠그지만 서비스가 먼저 칸 오류로 알리려고) */
    LockedLot lockLot(@Param("id") long id);

    boolean lotNoExists(@Param("lotNo") String lotNo);

    int countWoLots(@Param("woId") long woId);

    long insertLot(@Param("f") NewLot f, @Param("by") String by);

    long insertEdge(@Param("f") NewEdge f, @Param("by") String by);

    EdgeRow findEdge(@Param("id") long id);

    List<EdgeRow> parentsOf(@Param("lotId") long lotId);

    List<EdgeRow> childrenOf(@Param("lotId") long lotId);

    /** 역추적(BACK: 이 LOT 에 들어간 것) · 정추적(FWD: 이 LOT 이 들어간 것) — 재귀 CTE, 역분개된 행은 빠진다 */
    List<TraceRow> trace(@Param("lotId") long lotId, @Param("back") boolean back, @Param("depth") int depth,
            @Param("limit") int limit);
}
