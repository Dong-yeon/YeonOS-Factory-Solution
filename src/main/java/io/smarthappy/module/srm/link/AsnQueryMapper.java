package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.link.AsnQuery.AsnHead;
import io.smarthappy.module.srm.link.AsnQuery.AsnLine;
import lombok.Getter;
import lombok.Setter;

@Mapper
public interface AsnQueryMapper {

    List<Head> expected(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("limit") int limit);

    /** 번호 정확히, 없으면 납품서 번호(대상 상태만) — 2개까지 읽어 겹침을 본다 */
    List<Head> findByKey(@Param("key") String key);

    List<Line> lines(@Param("asnId") long asnId);

    @Getter
    @Setter
    class Head {
        private long asnId;
        private String asnNo;
        private long custId;
        private String custCd;
        private String custNm;
        private String plantCd;
        private String supplierRef;
        private LocalDate shipDate;
        private LocalDate etaDate;
        private String status;
        private int lineCnt;
        private int openLineCnt;

        AsnHead view() {
            return new AsnHead(asnId, asnNo, custId, custCd, custNm, plantCd, supplierRef, shipDate, etaDate, status, lineCnt, openLineCnt);
        }
    }

    @Getter
    @Setter
    class Line {
        private long asnLineId;
        private int lineNo;
        private long poLineId;
        private long poId;
        private String poNo;
        private int poLineNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private BigDecimal qty;
        private BigDecimal recvQty;
        private BigDecimal remainQty;
        private String supplierLotNo;
        private LocalDate mfgDate;
        private LocalDate expiryDate;

        AsnLine view() {
            return new AsnLine(asnLineId, lineNo, poLineId, poId, poNo, poLineNo, itemId, itemCd, itemNm, unitCd, strip(qty), strip(recvQty),
                    strip(remainQty), supplierLotNo, mfgDate, expiryDate);
        }

        private static BigDecimal strip(BigDecimal v) {
            BigDecimal s = v.stripTrailingZeros();
            return s.scale() < 0 ? s.setScale(0) : s;
        }
    }
}
