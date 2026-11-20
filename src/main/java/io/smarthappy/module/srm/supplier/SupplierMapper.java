package io.smarthappy.module.srm.supplier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierCrit;
import io.smarthappy.module.srm.supplier.SupplierDtos.CertRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.CertValue;
import io.smarthappy.module.srm.supplier.SupplierDtos.ItemOption;
import io.smarthappy.module.srm.supplier.SupplierDtos.PriceRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.SourceRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.SourceValue;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierValue;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface SupplierMapper {

    // ---------- 공통 ----------

    boolean codeUsable(@Param("grp") String grp, @Param("code") String code);


    ItemOption findItemByCd(@Param("itemCd") String itemCd);

    List<ItemOption> searchItems(@Param("keywordLike") String keywordLike);

    Integer unitScale(@Param("unitCd") String unitCd);

    // ---------- 협력사 ----------

    List<SupplierRow> findSuppliers(@Param("c") SupplierCrit c);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<SupplierRow> findSupplierPage(@Param("c") SupplierCrit c, @Param("page") PageQuery page);

    long countSuppliers(@Param("c") SupplierCrit c);

    /** 구매·겸용이 아닌 거래처도 돌려준다(서비스가 NOT_SUPPLIER_SRM 판정) */
    SupplierRow findSupplier(@Param("custId") long custId);

    int insertSupplier(@Param("f") SupplierValue f, @Param("by") String by);

    int updateSupplier(@Param("f") SupplierValue f, @Param("by") String by);

    // ---------- 인증서 ----------

    List<CertRow> findCerts(@Param("custId") long custId);

    CertRow findCert(@Param("id") long id);

    long insertCert(@Param("custId") long custId, @Param("f") CertValue f, @Param("by") String by);

    int updateCert(@Param("id") long id, @Param("f") CertValue f, @Param("by") String by);

    // ---------- 공급 품목 ----------

    List<SourceRow> findSources(@Param("custId") long custId);

    SourceRow findSource(@Param("id") long id);

    long insertSource(@Param("custId") long custId, @Param("f") SourceValue f, @Param("by") String by);

    int updateSource(@Param("id") long id, @Param("f") SourceValue f, @Param("by") String by);

    // ---------- 단가 ----------

    List<PriceRow> findPrices(@Param("custId") long custId, @Param("itemId") Long itemId);

    PriceRow findPrice(@Param("id") long id);

    /** 같은 협력사·품목·통화의 열린(끝 없는) 사용 중 단가 중 새 시작일보다 먼저 시작한 행을 새 시작일 전날로 마감 */
    int closeOpenPrice(@Param("custId") long custId, @Param("itemId") long itemId, @Param("currencyCd") String currencyCd,
            @Param("newFrom") LocalDate newFrom, @Param("by") String by);

    long insertPrice(@Param("custId") long custId, @Param("itemId") long itemId, @Param("currencyCd") String currencyCd,
            @Param("unitPrice") BigDecimal unitPrice, @Param("validFrom") LocalDate validFrom,
            @Param("validTo") LocalDate validTo, @Param("remark") String remark, @Param("by") String by);

    int updatePrice(@Param("id") long id, @Param("validTo") LocalDate validTo, @Param("useYn") String useYn,
            @Param("remark") String remark, @Param("rowVer") int rowVer, @Param("by") String by);

    BigDecimal effectivePrice(@Param("custId") long custId, @Param("itemId") long itemId,
            @Param("currencyCd") String currencyCd, @Param("day") LocalDate day);

    /** 품질 요약 카드 — 열린 클레임(작성 중·보냄·회신 받음) 수 */
    int countOpenClaims(@Param("custId") long custId);

    /** 품질 요약 카드 — 확정된(출고 대기) 반품 수 */
    int countReturnsToShip(@Param("custId") long custId);
}
