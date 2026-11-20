package io.smarthappy.module.wms.ship;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** ERP 출하 전표용 — 사용 중 ERP 시스템 하나(코드 순 첫째)의 품목 매핑(P1 mst_ext_id_map, P2 ErpMapper 와 같은 규칙) */
@Mapper
public interface ShipErpMapper {

    String erpItemCd(@Param("itemId") long itemId);
}
