package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.qms.MsaDtos.Cell;
import io.smarthappy.module.qms.MsaDtos.CellValue;
import io.smarthappy.module.qms.MsaDtos.Choice;
import io.smarthappy.module.qms.MsaDtos.StudyRow;
import io.smarthappy.module.qms.MsaDtos.StudySearch;
import io.smarthappy.module.qms.MsaDtos.StudyValue;

@Mapper
public interface MsaMapper {

    List<StudyRow> findPage(@Param("q") StudySearch q);

    long count(@Param("q") StudySearch q);

    Cursor<StudyRow> cursor(@Param("q") StudySearch q);

    StudyRow findById(@Param("id") long id);

    /** 상태 전이·저장 전에 머리 잠금 */
    Long lockStudy(@Param("id") long id);

    List<Cell> findCells(@Param("id") long id);

    /** 사용 중 게이지 id (코드로) */
    Long findGaugeId(@Param("assetCd") String assetCd);

    /** 같은 회사 특성의 사용 여부 — 없으면 null */
    String charUseYn(@Param("charId") long charId);

    List<Choice> gaugeChoices();

    List<Choice> charChoices();

    long insert(@Param("f") StudyValue f, @Param("studyNo") String studyNo, @Param("by") String by);

    int update(@Param("id") long id, @Param("f") StudyValue f, @Param("by") String by);

    int deleteCells(@Param("id") long id);

    void insertCells(@Param("id") long id, @Param("cells") List<CellValue> cells, @Param("by") String by);

    int complete(@Param("id") long id, @Param("rowVer") int rowVer, @Param("tolerance") BigDecimal tolerance,
            @Param("resultJson") String resultJson, @Param("grrPct") BigDecimal grrPct, @Param("ndc") Integer ndc,
            @Param("verdict") String verdict, @Param("by") String by);

    int cancel(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);
}
