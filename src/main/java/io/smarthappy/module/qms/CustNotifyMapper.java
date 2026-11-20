package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 4M 고객 통지 판단 (docs/29_P3 52장) — QMS·기준정보 표만. ECO·PCN 은 PLM 계약 EcoLookup 으로 읽는다 */
@Mapper
public interface CustNotifyMapper {

    /** 그 고객의 맞는 CSR(사용 중·시작일 ≤ on·요구 있음·대상 없음 또는 ECO 품목/클래스 일치) — CN-4 */
    List<CsrReq> matchedCsrs(@Param("custId") long custId, @Param("itemIds") Collection<Long> itemIds, @Param("on") LocalDate on);

    CustRef findCust(@Param("custId") long custId);

    @Getter
    @Setter
    class CsrReq {
        private long csrId;
        private String csrNo;
        private String csrRev;
        private String notifyReqJson;
    }

    @Getter
    @Setter
    class CustRef {
        private long custId;
        private String custCd;
        private String custNm;
    }
}
