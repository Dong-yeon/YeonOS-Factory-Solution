package io.smarthappy.module.srm.supplier;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.decimal;
import static io.smarthappy.module.srm.SrmChecks.fieldError;
import static io.smarthappy.module.srm.SrmChecks.me;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.supplier.SupplierDtos.ItemOption;
import io.smarthappy.module.srm.supplier.SupplierDtos.PriceForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.PriceRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.SourceForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.SourceRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.SourceValue;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 공급 품목·단가 (srm-supplier 탭) — SRM S1, docs/31 D3.
 * 단가는 품목 기본 단위당, 단가·통화·시작일은 고정(새 단가 = 새 행, 열린 이전 행은 새 시작일 전날로 마감).
 * 같은 협력사·품목·통화의 사용 중 단가 기간 겹침은 배제 제약 ex_srm_price_period 가 최종 판정.
 */
@Service
@RequiredArgsConstructor
public class SourcePriceService {

    /** 수량(최소 발주·배수) — numeric(18,6), 단가 — numeric(18,4) */
    static final int QTY_INT_DIGITS = 12;
    static final int PRICE_SCALE = 4;
    static final int PRICE_INT_DIGITS = 14;

    private final SupplierService suppliers;
    private final SupplierMapper mapper;
    private final InboxService inbox;
    private final BizClock clock;

    @Transactional(readOnly = true)
    public List<ItemOption> items(String keyword) {
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.searchItems(blank(keyword) ? null : "%" + SrmChecks.like(keyword.strip()) + "%");
    }

    // ------------------------------------------------------------------ 공급 품목

    @Transactional(readOnly = true)
    public List<SourceRow> sources(long custId) {
        suppliers.registered(custId);
        return mapper.findSources(custId);
    }

    @Transactional
    public long addSource(long custId, SourceForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_SOURCE_ADD");
        suppliers.registered(custId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ItemOption item = item(in.itemCd(), true, e);
        SourceValue v = sourceFields(in, item, e);
        e.throwIfAny();
        try {
            return mapper.insertSource(custId, v, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("itemCd", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            throw itemTranslate(ex);
        }
    }

    @Transactional
    public void updateSource(long custId, long sourceId, SourceForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        suppliers.registered(custId);
        SourceRow cur = mapper.findSource(sourceId);
        if (cur == null || cur.getCustId() != custId) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String cd = code(in.itemCd());
        if (cd != null && !cd.equals(cur.getItemCd())) {
            e.add("itemCd", "FIXED");
        }
        ItemOption item = new ItemOption();
        item.setItemId(cur.getItemId());
        item.setUnitCd(cur.getUnitCd());
        SourceValue v = sourceFields(in, item, e);
        // 다시 사용(N→Y)은 사용 중 품목만 — 사용 중지 = 새 참조만 차단
        if ("Y".equals(v.useYn()) && !"Y".equals(cur.getUseYn()) && !"Y".equals(cur.getItemUseYn())) {
            e.add("itemCd", "INACTIVE");
        }
        e.throwIfAny();
        try {
            if (mapper.updateSource(sourceId, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw itemTranslate(ex);
        }
    }

    private SourceValue sourceFields(SourceForm in, ItemOption item, FieldErrors e) {
        String supCd = text(in.supplierItemCd(), "supplierItemCd", 60, false, e);
        String supNm = text(in.supplierItemNm(), "supplierItemNm", 200, false, e);
        int lead = in.leadDays() == null ? 0 : in.leadDays();
        if (lead < 0 || lead > 365) {
            e.add("leadDays", "RANGE", 0, 365);
        }
        int priority = in.priority() == null ? 1 : in.priority();
        if (priority < 1 || priority > 9) {
            e.add("priority", "RANGE", 1, 9);
        }
        int scale = qtyScale(item);
        BigDecimal moq = decimal(in.moq(), "moq", scale, QTY_INT_DIGITS, false, e);
        BigDecimal mult = decimal(in.orderMult(), "orderMult", scale, QTY_INT_DIGITS, false, e);
        String use = SrmChecks.useYn(in.useYn(), e);
        String remark = text(in.remark(), "remark", 500, false, e);
        return new SourceValue(item == null ? 0 : item.getItemId(), supCd, supNm, lead, moq, mult, priority, use, remark,
                in.rowVer());
    }

    /** 품목 기본 단위의 소수 자릿수(없으면 6) — EA 에 0.5 개 최소 발주를 막는다 */
    private int qtyScale(ItemOption item) {
        if (item == null || item.getUnitCd() == null) {
            return 6;
        }
        Integer s = mapper.unitScale(item.getUnitCd());
        return s == null ? 6 : Math.min(s, 6);
    }

    private ItemOption item(String raw, boolean activeOnly, FieldErrors e) {
        String cd = code(raw);
        if (cd == null) {
            e.add("itemCd", "REQUIRED");
            return null;
        }
        ItemOption item = cd.length() > 40 ? null : mapper.findItemByCd(cd);
        if (item == null) {
            e.add("itemCd", "NO_SUCH_CODE");
            return null;
        }
        if (activeOnly && !"Y".equals(item.getUseYn())) {
            e.add("itemCd", "INACTIVE");
        }
        return item;
    }

    private static RuntimeException itemTranslate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_srm_source_item_active" -> fieldError("itemCd", "INACTIVE");
            case "fk_srm_source_item", "fk_srm_source_item_comp", "fk_srm_price_item", "fk_srm_price_item_comp" ->
                    fieldError("itemCd", "NO_SUCH_CODE");
            case "fk_srm_price_currency" -> fieldError("currencyCd", "NO_SUCH_CODE");
            case "ex_srm_price_period" -> fieldError("validFrom", "PRICE_OVERLAP");
            case "ck_srm_price_period" -> fieldError("validTo", "INVALID_RANGE");
            default -> e;
        };
    }

    // ------------------------------------------------------------------ 단가

    @Transactional(readOnly = true)
    public List<PriceRow> prices(long custId, String itemCd) {
        suppliers.registered(custId);
        Long itemId = null;
        String cd = code(itemCd);
        if (cd != null) {
            ItemOption item = cd.length() > 40 ? null : mapper.findItemByCd(cd);
            if (item == null) {
                return List.of();
            }
            itemId = item.getItemId();
        }
        LocalDate today = clock.today();
        List<PriceRow> rows = mapper.findPrices(custId, itemId);
        rows.forEach(r -> r.setState(priceState(r, today)));
        return rows;
    }

    static String priceState(PriceRow r, LocalDate today) {
        if (!"Y".equals(r.getUseYn())) {
            return "OFF";
        }
        if (r.getValidFrom().isAfter(today)) {
            return "FUTURE";
        }
        return r.getValidTo() != null && r.getValidTo().isBefore(today) ? "PAST" : "CURRENT";
    }

    @Transactional
    public long addPrice(long custId, PriceForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_PRICE_ADD");
        SupplierDtos.SupplierRow sup = suppliers.registered(custId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ItemOption item = item(in.itemCd(), true, e);
        String currency = code(in.currencyCd());
        if (currency == null) {
            currency = sup.getCurrencyCd();     // 비우면 협력사 기본 통화
        }
        if (currency == null || !suppliers.usable("CURRENCY", currency)) {
            e.add("currencyCd", "NO_SUCH_CODE");
        }
        BigDecimal price = decimal(in.unitPrice(), "unitPrice", PRICE_SCALE, PRICE_INT_DIGITS, true, e);
        LocalDate from = date(in.validFrom(), "validFrom", true, e);
        LocalDate to = date(in.validTo(), "validTo", false, e);
        if (from != null && to != null && to.isBefore(from)) {
            e.add("validTo", "INVALID_RANGE");
        }
        if (from != null && from.getYear() < 2000) {
            e.add("validFrom", "FORMAT");
        }
        String use = SrmChecks.useYn(in.useYn(), e);
        if (!"Y".equals(use)) {
            e.add("useYn", "CODE");     // 처음부터 사용 중지인 단가는 만들지 않는다
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        try {
            // 새 단가가 끝 없는 단가면 열린 이전 단가를 새 시작일 전날로 마감(같은 트랜잭션).
            // 끝이 있는 단가(한시 단가)는 이전 단가를 자르지 않는다 — 겹치면 배제 제약이 PRICE_OVERLAP
            if (to == null) {
                mapper.closeOpenPrice(custId, item.getItemId(), currency, from, me.loginId());
            }
            return mapper.insertPrice(custId, item.getItemId(), currency, price, from, to, remark, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw itemTranslate(ex);
        }
    }

    /** 끝일·사용·비고만 — 단가·통화·시작일·품목은 고정 */
    @Transactional
    public void updatePrice(long custId, long priceId, PriceForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        suppliers.registered(custId);
        PriceRow cur = mapper.findPrice(priceId);
        if (cur == null || cur.getCustId() != custId) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String cd = code(in.itemCd());
        if (cd != null && !cd.equals(cur.getItemCd())) {
            e.add("itemCd", "FIXED");
        }
        String currency = code(in.currencyCd());
        if (currency != null && !currency.equals(cur.getCurrencyCd())) {
            e.add("currencyCd", "FIXED");
        }
        BigDecimal price = decimal(in.unitPrice(), "unitPrice", PRICE_SCALE, PRICE_INT_DIGITS, false, e);
        if (price != null && price.compareTo(new BigDecimal(cur.getUnitPrice())) != 0) {
            e.add("unitPrice", "FIXED");
        }
        LocalDate from = date(in.validFrom(), "validFrom", false, e);
        if (from != null && !from.equals(cur.getValidFrom())) {
            e.add("validFrom", "FIXED");
        }
        LocalDate to = date(in.validTo(), "validTo", false, e);
        if (to != null && to.isBefore(cur.getValidFrom())) {
            e.add("validTo", "INVALID_RANGE");
        }
        String use = SrmChecks.useYn(in.useYn(), e);
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        try {
            if (mapper.updatePrice(priceId, to, use, remark, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw itemTranslate(ex);
        }
    }

    /** 기준일 단가 — 발주(S3) 행 단가 기본값. 없으면 빈 값 */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> effectivePrice(long custId, long itemId, String currencyCd, LocalDate day) {
        return Optional.ofNullable(mapper.effectivePrice(custId, itemId, currencyCd, day));
    }
}
