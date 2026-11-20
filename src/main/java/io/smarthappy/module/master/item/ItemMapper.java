package io.smarthappy.module.master.item;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.master.item.ItemDtos.ItemRow;
import io.smarthappy.module.master.item.ItemDtos.ItemSearch;
import io.smarthappy.module.master.item.ItemDtos.ItemValue;

@Mapper
public interface ItemMapper {

    List<ItemRow> findPage(@Param("q") ItemSearch q);

    long count(@Param("q") ItemSearch q);

    /** 엑셀용 — findPage 와 같은 조건·정렬 SQL(include) */
    Cursor<ItemRow> cursor(@Param("q") ItemSearch q);

    ItemRow findById(@Param("id") long id);

    boolean existsCd(@Param("itemCd") String itemCd, @Param("exceptId") Long exceptId);

    /** 시스템 단위 또는 이 회사가 추가한 단위 중 사용 중인 것 (회사 코드는 RLS 로 이 회사 것만 보인다) */
    boolean unitUsable(@Param("unitCd") String unitCd);

    /** BOM 머리·행·사용 중 단위 환산에 쓰여 기본 단위를 바꿀 수 없는가 (V018) */
    boolean unitInUse(@Param("itemId") long itemId);

    long insert(@Param("f") ItemValue f, @Param("by") String by);

    int update(@Param("id") long id, @Param("f") ItemValue f, @Param("by") String by);

    int disable(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);
}
