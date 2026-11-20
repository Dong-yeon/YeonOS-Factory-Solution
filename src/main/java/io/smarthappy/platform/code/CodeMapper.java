package io.smarthappy.platform.code;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CodeMapper {

    boolean groupExists(@Param("grpCd") String grpCd);

    List<CodeRow> findCodes(@Param("grpCd") String grpCd);

    /** 표시용 — 사용 중지 포함 (docs/36 18.9 X4) */
    List<CodeRow> findCodeNames(@Param("grpCd") String grpCd);

    /** 시스템 코드 또는 이 회사 코드 중 사용 중 — DB 함수 hb_code_usable(트리거와 같은 판정, docs/68) */
    boolean usable(@Param("grpCd") String grpCd, @Param("code") String code);
}
