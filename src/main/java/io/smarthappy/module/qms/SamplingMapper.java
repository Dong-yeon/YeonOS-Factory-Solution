package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.qms.QmsDtos.AqlLetterRow;
import io.smarthappy.module.qms.QmsDtos.AqlPlanRowView;
import io.smarthappy.module.qms.QmsDtos.SamplingRow;
import io.smarthappy.module.qms.QmsDtos.SamplingSearch;
import io.smarthappy.module.qms.QmsDtos.SamplingValue;

@Mapper
public interface SamplingMapper {

    // ---------- 샘플링 계획 ----------

    List<SamplingRow> findPage(@Param("q") SamplingSearch q);

    long count(@Param("q") SamplingSearch q);

    Cursor<SamplingRow> cursor(@Param("q") SamplingSearch q);

    SamplingRow findById(@Param("id") long id);

    SamplingRow findByCd(@Param("planCd") String planCd);

    /** 검사계획 행 선택지 — 사용 중 계획 */
    List<SamplingRow> findActive();

    long insert(@Param("f") SamplingValue f, @Param("by") String by);

    int update(@Param("id") long id, @Param("f") SamplingValue f, @Param("by") String by);

    // ---------- AQL 표 ----------

    List<String> findStds();

    List<AqlLetterRow> findLetters(@Param("stdCd") String stdCd, @Param("activeOnly") boolean activeOnly);

    List<AqlPlanRowView> findPlanRows(@Param("stdCd") String stdCd, @Param("activeOnly") boolean activeOnly);

    boolean hasLetters(@Param("stdCd") String stdCd, @Param("inspLevel") String inspLevel);

    boolean hasPlanRows(@Param("stdCd") String stdCd, @Param("severity") String severity, @Param("aql") BigDecimal aql);
}
