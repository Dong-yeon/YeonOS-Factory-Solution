package io.smarthappy.module.aps;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.aps.DispatchDtos.CurrentPlan;
import io.smarthappy.module.aps.DispatchDtos.SlotRow;

/** 일일 작업 배정 (docs/38 18장) — 읽기만. 지시·공정 표는 지금 상태를 함께 보이려고 읽는다 */
@Mapper
public interface DispatchMapper {

    CurrentPlan findCurrent(@Param("plantCd") String plantCd);

    /** day 가 있으면 그날 시작 공정, woProcIds 가 있으면 그 지시 공정 — 둘 중 하나만 */
    List<SlotRow> findSlots(@Param("planId") long planId, @Param("day") LocalDate day, @Param("woProcIds") Collection<Long> woProcIds);
}
