package io.smarthappy.module.home;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

/** 업무 흐름 지도 (home-flow-map, docs/46 12장) — 화면 목록(전역 sys_prog) */
@Mapper
public interface FlowMapMapper {

    /** 사용 중 화면 전부 — 코드 순 */
    List<ProgRow> findProgs();

    record ProgRow(String progCd, String progNmKey) {
    }
}
