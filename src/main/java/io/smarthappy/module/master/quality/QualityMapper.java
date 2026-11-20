package io.smarthappy.module.master.quality;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.master.quality.QualityDtos.AliasForm;
import io.smarthappy.module.master.quality.QualityDtos.AliasRow;
import io.smarthappy.module.master.quality.QualityDtos.CharRow;
import io.smarthappy.module.master.quality.QualityDtos.CharSearch;
import io.smarthappy.module.master.quality.QualityDtos.CharValue;
import io.smarthappy.module.master.quality.QualityDtos.DefectForm;
import io.smarthappy.module.master.quality.QualityDtos.DefectRow;
import io.smarthappy.module.master.quality.QualityDtos.ItemLookup;
import io.smarthappy.module.master.quality.QualityDtos.ProcLookup;
import io.smarthappy.module.master.quality.QualityDtos.TermRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface QualityMapper {

    // ---------- 특성 ----------

    List<CharRow> findChars(@Param("q") CharSearch q);

    long countChars(@Param("q") CharSearch q);

    Cursor<CharRow> cursorChars(@Param("q") CharSearch q);

    CharRow findChar(@Param("id") long id);

    long insertChar(@Param("f") CharValue f, @Param("by") String by);

    int updateChar(@Param("id") long id, @Param("f") CharValue f, @Param("by") String by);

    ItemLookup findItemByCd(@Param("itemCd") String itemCd);

    ProcLookup findProcByCd(@Param("procCd") String procCd);

    List<ProcLookup> findProcs();

    /** 시스템 코드 또는 그 회사 코드로 사용 중인지 (특별특성 고객 기호) */
    boolean codeUsable(@Param("grp") String grp, @Param("code") String code);

    // ---------- 불량 유형 ----------

    List<DefectRow> findDefects(@Param("useYn") String useYn);

    List<DefectRow> findDefectPage(@Param("useYn") String useYn, @Param("kw") String keywordLike, @Param("page") PageQuery page);

    long countDefects(@Param("useYn") String useYn, @Param("kw") String keywordLike);

    DefectRow findDefect(@Param("id") long id);

    long insertDefect(@Param("f") DefectForm f, @Param("by") String by);

    int updateDefect(@Param("id") long id, @Param("f") DefectForm f, @Param("by") String by);

    // ---------- 용어 사전 ----------

    List<TermRow> findTerms();

    List<TermRow> findTermPage(@Param("kw") String keywordLike, @Param("page") PageQuery page);

    long countTerms(@Param("kw") String keywordLike);

    boolean termExists(@Param("termCd") String termCd);

    List<AliasRow> findAliases(@Param("termCd") String termCd);

    AliasRow findAlias(@Param("id") long id);

    long insertAlias(@Param("f") AliasForm f, @Param("by") String by);

    int updateAlias(@Param("id") long id, @Param("f") AliasForm f, @Param("by") String by);
}
