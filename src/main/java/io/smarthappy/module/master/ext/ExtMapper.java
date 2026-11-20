package io.smarthappy.module.master.ext;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.master.ext.ExtDtos.CodeMapForm;
import io.smarthappy.module.master.ext.ExtDtos.CodeMapRow;
import io.smarthappy.module.master.ext.ExtDtos.IdMapRow;
import io.smarthappy.module.master.ext.ExtDtos.IdMapValue;
import io.smarthappy.module.master.ext.ExtDtos.SystemRow;
import io.smarthappy.module.master.ext.ExtDtos.SystemValue;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface ExtMapper {

    // ---------- 시스템 ----------

    List<SystemRow> findSystems(@Param("useYn") String useYn);

    List<SystemRow> findSystemPage(@Param("useYn") String useYn, @Param("page") PageQuery page);

    long countSystems(@Param("useYn") String useYn);

    SystemRow findSystem(@Param("id") long id);

    SystemRow findSystemByCd(@Param("systemCd") String systemCd);

    Long findCustId(@Param("custCd") String custCd);

    String custUseYn(@Param("custId") long custId);

    long insertSystem(@Param("f") SystemValue f, @Param("by") String by);

    int updateSystem(@Param("id") long id, @Param("f") SystemValue f, @Param("by") String by);

    // ---------- 코드 매핑 ----------

    List<CodeMapRow> findCodeMaps(@Param("systemId") long systemId);

    CodeMapRow findCodeMap(@Param("id") long id);

    long insertCodeMap(@Param("systemId") long systemId, @Param("f") CodeMapForm f, @Param("by") String by);

    int updateCodeMap(@Param("id") long id, @Param("f") CodeMapForm f, @Param("by") String by);

    /** 내부 코드가 그 유형에서 지금 쓸 수 있는지 (트리거와 같은 규칙 — 친절한 칸 오류용) */
    boolean intCodeUsable(@Param("codeType") String codeType, @Param("code") String code);

    // ---------- 외부 ID ----------

    List<IdMapRow> findIdMaps(@Param("entityType") String entityType, @Param("internalId") long internalId);

    IdMapRow findIdMap(@Param("id") long id);

    boolean targetExists(@Param("entityType") String entityType, @Param("internalId") long internalId);

    long insertIdMap(@Param("f") IdMapValue f, @Param("by") String by);

    int updateIdMap(@Param("id") long id, @Param("f") IdMapValue f, @Param("by") String by);

    // ---------- 품목 ERP 품번 (docs/17 9장) ----------

    /** 사용 중 ERP 시스템 하나(시스템 코드 순 첫째) — ErpMapper.erpItem 과 같은 규칙. 없으면 null */
    Long findErpSystemId();

    /** 그 시스템의 사용 중 품목 매핑 외부 코드 → 품목 id (엑셀 올리기가 한 번에 읽음) */
    List<java.util.Map<String, Object>> findItemMaps(@Param("systemId") long systemId);

    /** 그 시스템에서 이 외부 코드를 다른 품목이 쓰고 있나 (사용 중 행, 대소문자 구분) */
    boolean itemExtIdTaken(@Param("systemId") long systemId, @Param("externalId") String externalId);
}
