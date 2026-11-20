package io.smarthappy.module.srm.supplier;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.fieldError;
import static io.smarthappy.module.srm.SrmChecks.me;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.supplier.SupplierDtos.CertForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.CertRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.CertValue;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierCrit;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierPage;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierSort;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierValue;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserRef;
import lombok.RequiredArgsConstructor;

/**
 * 협력사 정보 (srm-supplier) — SRM S1, docs/31 D2.
 * 협력사 = P1 거래처(구매·겸용) + 1:1 확장 srm_supplier. 거래처 기본정보는 여기서 바꾸지 않는다(ERP 주인일 수 있음).
 * 인증서는 갱신 = 새 행, 판정은 {@link CertStatus}.
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

    public static final String PROG = "srm-supplier";
    static final Set<String> TRADE_STATUS = Set.of("ACTIVE", "HOLD", "BLOCKED");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    private final SupplierMapper mapper;
    private final UserDirectory users;
    private final InboxService inbox;
    private final PolicyService policies;
    private final BizClock clock;

    @Transactional(readOnly = true)
    public List<SupplierRow> list(String keyword, String tradeStatus, String registered) {
        List<SupplierRow> rows = mapper.findSuppliers(criteria(keyword, tradeStatus, registered));
        LocalDate today = clock.today();
        int days = dueSoonDays();
        rows.forEach(r -> fillState(r, today, days));
        return rows;
    }

    /**
     * grid(UI 단계 3 ①, docs/31 5.2) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link SupplierSort}, 최대 3개, 모르는 열 400).
     * 인증 판정(certState)은 쪽마다 자바로 계산 — 목록과 같은 규칙
     */
    @Transactional(readOnly = true)
    public SupplierPage grid(String keyword, String tradeStatus, String registered, Integer page, Integer size, List<String> sort,
            Boolean total) {
        SupplierCrit c = criteria(keyword, tradeStatus, registered);
        PageQuery pq = PageQuery.parse(page, size, sort, total, SupplierSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("custCd,asc"));
        List<SupplierRow> rows = mapper.findSupplierPage(c, pq);
        LocalDate today = clock.today();
        int days = dueSoonDays();
        rows.forEach(r -> fillState(r, today, days));
        return new SupplierPage(rows, pq.isWithTotal() ? mapper.countSuppliers(c) : null);
    }

    /** grid 열(화면 목록과 같은 칸) — 정렬 가능 = {@link SupplierSort} 에 있는 열. 등록·인증은 화면이 이름으로 바꿔 보인다 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("custCd", "srm.sup.col.custCd", 90).withSort().withPriority(1),
            ColumnSpec.text("custNm", "srm.sup.col.custNm", 160).withSort(),
            ColumnSpec.text("registered", "srm.sup.col.registered", 90).withPriority(3),
            ColumnSpec.code("tradeStatus", "srm.sup.col.tradeStatus", "TRADE_STATUS", 90).withSort().withPriority(1),
            ColumnSpec.text("certState", "srm.sup.col.certState", 100),
            ColumnSpec.of("sourceCnt", "srm.sup.col.sourceCnt", ColumnType.INT, 80).withSort().withPriority(3),
            ColumnSpec.text("buyerNm", "srm.sup.col.buyer", 100).withSort().withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    private SupplierCrit criteria(String keyword, String tradeStatus, String registered) {
        String st = code(tradeStatus);
        String reg = code(registered);
        if (keyword != null && keyword.length() > 100 || st != null && !TRADE_STATUS.contains(st)
                || reg != null && !"Y".equals(reg) && !"N".equals(reg)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new SupplierCrit(blank(keyword) ? null : "%" + SrmChecks.like(keyword.strip()) + "%", st, reg);
    }

    /** 구매·겸용 거래처(등록 전 포함). 판매 전용이거나 없으면 404 */
    @Transactional(readOnly = true)
    public SupplierRow get(long custId) {
        SupplierRow r = mapper.findSupplier(custId);
        if (r == null || "SALES".equals(r.getCustType())) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        fillState(r, clock.today(), dueSoonDays());
        return r;
    }

    /** 등록된 협력사만 — 인증서·공급 품목·단가 API 의 입구 */
    @Transactional(readOnly = true)
    public SupplierRow registered(long custId) {
        SupplierRow r = get(custId);
        if (!r.isRegistered()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    private void fillState(SupplierRow r, LocalDate today, int days) {
        if (r.isRegistered()) {
            r.setCertState(CertStatus.ofSupplier(r.getCertCnt(), r.getCertMinExpires(), today, days));
        }
    }

    int dueSoonDays() {
        return policies.intValue(Policies.SRM_CERT_DUE_SOON_DAYS);
    }

    @Transactional
    public long create(SupplierForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_SUPPLIER_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.custId() == null) {
            throw fieldError("custId", "REQUIRED");
        }
        SupplierRow cur = mapper.findSupplier(in.custId());
        if (cur == null) {
            throw fieldError("custId", "NO_SUCH_CODE");
        }
        if ("SALES".equals(cur.getCustType())) {
            throw fieldError("custId", "NOT_SUPPLIER_SRM");
        }
        if (cur.isRegistered()) {
            throw fieldError("custId", "DUPLICATE");
        }
        if (!"Y".equals(cur.getCustUseYn())) {
            throw fieldError("custId", "INACTIVE");
        }
        SupplierValue f = validate(in, cur, false);
        try {
            mapper.insertSupplier(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("custId", "DUPLICATE");
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        return f.custId();
    }

    @Transactional
    public void update(long custId, SupplierForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        SupplierRow cur = registered(custId);
        SupplierValue f = validate(in, cur, true);
        try {
            if (mapper.updateSupplier(f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private SupplierValue validate(SupplierForm in, SupplierRow cur, boolean editing) {
        FieldErrors e = FieldErrors.create();
        Long buyer = null;
        if (!blank(in.buyerLoginId())) {
            String login = in.buyerLoginId().strip();
            UserRef u = users.byLogin(login);   // 회사 사용자만(공통 사용자 고르기) — 협력사 계정은 구매 담당이 아니다
            buyer = u == null ? null : u.userId();
            if (buyer == null) {
                e.add("buyerLoginId", "NO_SUCH_CODE");
            } else if (!u.active() && !(editing && buyer.equals(cur.getBuyerUserId()))) {
                // 새로 고른 담당만 사용 중이어야 한다(잠금·사용 중지) — 그대로 둔 기존 담당은 저장을 막지 않는다
                e.add("buyerLoginId", "INACTIVE");
            }
        }
        String currency = code(in.currencyCd());
        if (currency == null) {
            currency = editing ? null : "KRW";
            if (currency == null) {
                e.add("currencyCd", "REQUIRED");
            }
        }
        if (currency != null && !(editing && currency.equals(cur.getCurrencyCd())) && !usable("CURRENCY", currency)) {
            e.add("currencyCd", "NO_SUCH_CODE");
        }
        String pay = code(in.payTermsCd());
        if (pay != null && !(editing && pay.equals(cur.getPayTermsCd())) && !usable("PAY_TERMS", pay)) {
            e.add("payTermsCd", "NO_SUCH_CODE");
        }
        String country = country(in.countryCd(), "countryCd", e);
        String control = country(in.controlCountryCd(), "controlCountryCd", e);
        String status = code(in.tradeStatus());
        if (status == null) {
            status = "ACTIVE";
        } else if (!TRADE_STATUS.contains(status)) {
            e.add("tradeStatus", "CODE");
        }
        String reason = text(in.statusReason(), "statusReason", 500, false, e);
        if (!"ACTIVE".equals(status) && reason == null) {
            e.add("statusReason", "REQUIRED");
        }
        LocalDate sec = date(in.secAssessedOn(), "secAssessedOn", false, e);
        if (sec != null && sec.isAfter(clock.today())) {
            e.add("secAssessedOn", "FUTURE");
        }
        String remark = text(in.remark(), "remark", 1000, false, e);
        e.throwIfAny();
        return new SupplierValue(cur.getCustId(), buyer, pay, currency, country, control, status, reason, sec, remark,
                editing ? in.rowVer() : null);
    }

    private static String country(String v, String field, FieldErrors e) {
        String c = code(v);
        if (c != null && !COUNTRY.matcher(c).matches()) {
            e.add(field, "FORMAT");
        }
        return c;
    }

    boolean usable(String grp, String code) {
        return code.length() <= 30 && mapper.codeUsable(grp, code);
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_srm_supplier_buyer", "fk_srm_supplier_buyer_comp" -> fieldError("buyerLoginId", "NO_SUCH_CODE");
            case "fk_srm_supplier_currency" -> fieldError("currencyCd", "NO_SUCH_CODE");
            case "fk_srm_supplier_pay_terms" -> fieldError("payTermsCd", "NO_SUCH_CODE");
            case "ck_srm_supplier_cust_type" -> fieldError("custId", "NOT_SUPPLIER_SRM");
            case "fk_srm_supplier_cust", "fk_srm_supplier_cust_comp" -> fieldError("custId", "NO_SUCH_CODE");
            default -> e;
        };
    }

    // ------------------------------------------------------------------ 인증서

    @Transactional(readOnly = true)
    public List<CertRow> certs(long custId) {
        registered(custId);
        List<CertRow> rows = mapper.findCerts(custId);
        LocalDate today = clock.today();
        int days = dueSoonDays();
        Set<String> seen = new HashSet<>();
        for (CertRow r : rows) {      // 유형·발행일 내림차순 — 유형마다 첫 사용 중 행이 현재 인증서
            if (!"Y".equals(r.getUseYn())) {
                r.setState(CertStatus.OFF);
                continue;
            }
            r.setCurrent(seen.add(r.getCertTypeCd()));
            r.setState(CertStatus.of(r.getExpiresOn(), today, days));
        }
        return rows;
    }

    @Transactional
    public long addCert(long custId, CertForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_SUPPLIER_CERT_ADD");
        registered(custId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type = code(in.certTypeCd());
        if (type == null) {
            e.add("certTypeCd", "REQUIRED");
        } else if (!usable("SUPPLIER_CERT", type)) {
            e.add("certTypeCd", "NO_SUCH_CODE");
        }
        LocalDate issued = date(in.issuedOn(), "issuedOn", true, e);
        if (issued != null && issued.isAfter(clock.today())) {
            e.add("issuedOn", "FUTURE");
        }
        CertValue v = certFields(in, type, issued, e);
        e.throwIfAny();
        try {
            return mapper.insertCert(custId, v, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw certTranslate(ex);
        }
    }

    /** 번호·발급 기관·만료일·사용·비고만 — 유형·발행일은 고정(갱신은 새 행) */
    @Transactional
    public void updateCert(long custId, long certId, CertForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        registered(custId);
        CertRow cur = mapper.findCert(certId);
        if (cur == null || cur.getCustId() != custId) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String type = code(in.certTypeCd());
        if (type != null && !type.equals(cur.getCertTypeCd())) {
            e.add("certTypeCd", "FIXED");
        }
        LocalDate issued = date(in.issuedOn(), "issuedOn", false, e);
        if (issued != null && !issued.equals(cur.getIssuedOn())) {
            e.add("issuedOn", "FIXED");
        }
        CertValue v = certFields(in, cur.getCertTypeCd(), cur.getIssuedOn(), e);
        e.throwIfAny();
        try {
            if (mapper.updateCert(certId, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw certTranslate(ex);
        }
    }

    private CertValue certFields(CertForm in, String type, LocalDate issued, FieldErrors e) {
        LocalDate expires = date(in.expiresOn(), "expiresOn", false, e);
        if (issued != null && expires != null && expires.isBefore(issued)) {
            e.add("expiresOn", "INVALID_RANGE");
        }
        String no = text(in.certNo(), "certNo", 100, false, e);
        String issuer = text(in.issuerNm(), "issuerNm", 200, false, e);
        String use = SrmChecks.useYn(in.useYn(), e);
        String remark = text(in.remark(), "remark", 500, false, e);
        return new CertValue(type, no, issuer, issued, expires, use, remark, in.rowVer());
    }

    private static RuntimeException certTranslate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_srm_supplier_cert_type" -> fieldError("certTypeCd", "NO_SUCH_CODE");
            case "ck_srm_supplier_cert_period" -> fieldError("expiresOn", "INVALID_RANGE");
            default -> e;
        };
    }
}
