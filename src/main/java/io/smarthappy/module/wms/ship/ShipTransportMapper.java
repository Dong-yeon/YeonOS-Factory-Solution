package io.smarthappy.module.wms.ship;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 출하 운송 정보 (docs/58) — wms_ship_transport, 회사 조건은 RLS */
@Mapper
public interface ShipTransportMapper {

    /** 지시 상태 — 없거나 다른 회사면 null (첨부 잠금 규칙용, 잠그지 않음) */
    String shipStatus(@Param("shipId") long shipId);

    /** 운송 행의 row_ver — 행이 없으면 null. 부른 쪽이 머리를 잠근 뒤 */
    Integer transportRowVer(@Param("shipId") long shipId);

    int insert(@Param("shipId") long shipId, @Param("carrierNm") String carrierNm, @Param("vehicleNo") String vehicleNo,
            @Param("trackingNo") String trackingNo, @Param("by") String by);

    int update(@Param("shipId") long shipId, @Param("rowVer") int rowVer, @Param("carrierNm") String carrierNm,
            @Param("vehicleNo") String vehicleNo, @Param("trackingNo") String trackingNo, @Param("by") String by);
}
