package io.smarthappy.module.qms;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.ChangeDtos.ChangeHead;
import io.smarthappy.module.qms.ChangeDtos.ChangeRow;
import io.smarthappy.module.qms.ChangeDtos.ChangeValue;
import io.smarthappy.module.qms.ChangeDtos.CharRow;
import io.smarthappy.module.qms.ChangeDtos.CharValue;
import io.smarthappy.module.qms.ChangeDtos.Choice;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface ChangeMapper {

    List<ChangeRow> findPage(@Param("keywordLike") String keywordLike, @Param("m4Type") String m4Type, @Param("ecoIds") Collection<Long> ecoIds, @Param("limit") int limit);

    List<ChangeRow> findGrid(@Param("keywordLike") String keywordLike, @Param("m4Type") String m4Type, @Param("ecoIds") Collection<Long> ecoIds, @Param("page") PageQuery page);

    long countList(@Param("keywordLike") String keywordLike, @Param("m4Type") String m4Type, @Param("ecoIds") Collection<Long> ecoIds);

    ChangeHead findHead(@Param("ecoId") long ecoId);

    List<CharRow> findChars(@Param("ecoId") long ecoId);

    /** ECO 상태를 FOR SHARE 로 읽는다 — PLM 상신(FOR UPDATE)과 직렬화하는 유일한 plm_eco 직접 읽기(docs/29_P3 11.4 합의) */
    String lockEcoStatus(@Param("ecoId") long ecoId);

    List<Choice> charChoices(@Param("itemIds") Collection<Long> itemIds, @Param("procIds") Collection<Long> procIds);

    List<Choice> planChoices(@Param("itemIds") Collection<Long> itemIds, @Param("procIds") Collection<Long> procIds);

    Long charOfComp(@Param("id") long id);

    Long planOfComp(@Param("id") long id);

    int insertChange(@Param("ecoId") long ecoId, @Param("f") ChangeValue f, @Param("by") String by);

    int updateChange(@Param("ecoId") long ecoId, @Param("f") ChangeValue f, @Param("by") String by);

    int deleteChars(@Param("ecoId") long ecoId);

    void insertChars(@Param("ecoId") long ecoId, @Param("rows") List<CharValue> rows, @Param("by") String by);

    int deleteChange(@Param("ecoId") long ecoId, @Param("rowVer") int rowVer);

    boolean exists(@Param("ecoId") long ecoId);

    int charCount(@Param("ecoId") long ecoId);
}
