package io.smarthappy.module.master.cust;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.master.cust.CustDtos.ContactRow;
import io.smarthappy.module.master.cust.CustDtos.CustForm;
import io.smarthappy.module.master.cust.CustDtos.CustRow;
import io.smarthappy.module.master.cust.CustDtos.CustSearch;

@Mapper
public interface CustMapper {

    List<CustRow> findPage(@Param("q") CustSearch q);

    long count(@Param("q") CustSearch q);

    /** 엑셀용 — findPage 와 같은 조건·정렬 SQL(include), 페이지 없이 나눠 읽기 */
    Cursor<CustRow> cursor(@Param("q") CustSearch q);

    CustRow findById(@Param("id") long id);

    boolean existsCd(@Param("custCd") String custCd, @Param("exceptId") Long exceptId);

    long insert(@Param("f") CustForm f, @Param("by") String by);

    int update(@Param("id") long id, @Param("f") CustForm f, @Param("by") String by);

    int disable(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    List<ContactRow> findContacts(@Param("custId") long custId);

    long insertContact(@Param("custId") long custId, @Param("c") CustDtos.ContactItem c, @Param("by") String by);

    int updateContact(@Param("custId") long custId, @Param("c") CustDtos.ContactItem c, @Param("by") String by);

    int disableContact(@Param("custId") long custId, @Param("id") long id, @Param("rowVer") int rowVer,
            @Param("by") String by);

    /** 거래처 변경 시각·버전을 올린다 (담당자 변경도 거래처의 변경으로 본다 — 동시 편집 충돌 감지) */
    int touch(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);
}
