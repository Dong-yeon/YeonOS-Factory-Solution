package io.smarthappy.module.sales;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;
import static io.smarthappy.module.sales.OrderService.me;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.sales.SalesDtos.CompareView;
import io.smarthappy.module.sales.SalesDtos.CustOption;
import io.smarthappy.module.sales.SalesDtos.CustRef;
import io.smarthappy.module.sales.SalesDtos.FcstForm;
import io.smarthappy.module.sales.SalesDtos.FcstLineForm;
import io.smarthappy.module.sales.SalesDtos.FcstLineRow;
import io.smarthappy.module.sales.SalesDtos.FcstLineValue;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.module.sales.SalesDtos.RevRow;
import io.smarthappy.module.sales.SalesDtos.RevSort;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 내시 수신 (sal-forecast) — P2 2단계, docs/25 4.3.
 *
 * 받을 때마다 거래처별 새 버전(rev_no). 이전 버전은 수정·삭제하지 않는다(DB 가 막는다) — 정정도 새 버전.
 * 버전 번호는 같은 거래처 수신을 advisory 잠금으로 줄 세운 뒤 max + 1 (유일 제약이 마지막 방어).
 */
@Service
@RequiredArgsConstructor
public class ForecastService {

    static final String PROG = "sal-forecast";
    static final int MAX_LINES = 5000;
    static final Set<String> BUCKETS = Set.of("DAY", "WEEK", "MONTH");
    static final Set<String> SOURCES = Set.of("FILE", "MANUAL", "EDI");

    private final SalesMapper mapper;
    private final OrderService orders;
    private final InboxService inbox;
    private final PolicyService policies;

    /** 버전 그리드 기본 정렬 = 기존 목록 순서(최신 버전) */
    static final List<String> REV_SORT = List.of("revNo,desc");

    /** 버전·기간 칸은 글자로(화면이 "v.n"·"처음 ~ 끝" 을 붙인다, format) */
    static final List<ColumnSpec> REV_COLUMNS = List.of(
            ColumnSpec.text("revNo", "salFcst.col.revNo", 70).withSort().withPriority(1),
            ColumnSpec.of("receivedAt", "salFcst.col.receivedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(1),
            ColumnSpec.code("source", "salFcst.col.source", "FCST_SOURCE", 80).withPriority(2),
            ColumnSpec.text("fileNm", "salFcst.col.file", 160).withPriority(3).withFlex().withMinWidth(100),
            ColumnSpec.of("lineCnt", "salFcst.col.lineCnt", ColumnSpec.ColumnType.INT, 70).withPriority(2),
            ColumnSpec.text("firstBucket", "salFcst.col.span", 190).withPriority(2),
            ColumnSpec.text("createdBy", "salFcst.col.createdBy", 100).withPriority(3));

    public record RevPage(List<RevRow> list, Long total) {
    }

    @Transactional(readOnly = true)
    public List<CustOption> custs() {
        return mapper.findSalesCusts(false);
    }

    @Transactional(readOnly = true)
    public List<RevRow> revs(String custCd) {
        if (isBlank(custCd)) {
            throw OrderService.fieldError("custCd", "REQUIRED");
        }
        CustRef c = mapper.findCust(custCd.strip().toUpperCase(Locale.ROOT));
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.findRevs(c.getCustId());
    }

    /** 버전 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 기존 목록과 같은 조건(거래처 필수, 없는 거래처 404) + 페이지·정렬·건수 */
    @Transactional(readOnly = true)
    public RevPage revGrid(String custCd, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, RevSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), REV_SORT);
        if (isBlank(custCd)) {
            throw OrderService.fieldError("custCd", "REQUIRED");
        }
        CustRef c = mapper.findCust(custCd.strip().toUpperCase(Locale.ROOT));
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new RevPage(mapper.findRevPage(c.getCustId(), pq), pq.isWithTotal() ? mapper.countRevs(c.getCustId()) : null);
    }

    public List<ColumnSpec> revColumns() {
        return REV_COLUMNS;
    }

    @Transactional(readOnly = true)
    public List<FcstLineRow> lines(long revId) {
        if (mapper.findRev(revId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.findFcstLines(revId);
    }

    /** 두 버전 비교 — 같은 거래처끼리만. fromRevId 가 없으면 toRev 바로 앞 버전(첫 버전이면 빈 쪽과 비교) */
    @Transactional(readOnly = true)
    public CompareView compare(Long fromRevId, long toRevId) {
        RevRow to = mapper.findRev(toRevId);
        if (to == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        RevRow from;
        if (fromRevId == null) {
            from = mapper.findRevs(to.getCustId()).stream().filter(r -> r.getRevNo() < to.getRevNo()).findFirst().orElse(null);
        } else {
            from = mapper.findRev(fromRevId);
            if (from == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            if (from.getCustId() != to.getCustId()) {
                throw OrderService.fieldError("fromRevId", "OTHER_CUST");
            }
        }
        return new CompareView(from, to, mapper.compare(from == null ? -1 : from.getRevId(), toRevId));
    }

    /** 내시 직접 수신(API·EDI 자리) — 행 전부를 검증한 뒤 새 버전 하나 */
    @Transactional
    public long receive(FcstForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SAL_FORECAST_RECEIVE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        CustRef cust = orders.cust(in.custCd(), e);
        String remark = OrderService.text(in.remark(), "remark", 500, e);
        List<FcstLineForm> forms = in.lines() == null ? List.of() : in.lines();
        if (forms.isEmpty()) {
            e.add("lines", "NO_LINES");
        } else if (forms.size() > MAX_LINES) {
            e.add("lines", "RANGE", 1, MAX_LINES);
        }
        e.throwIfAny();
        List<FcstLineValue> lines = new ArrayList<>();
        Map<String, Integer> scales = orders.unitScales();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < forms.size(); i++) {
            FcstLineForm f = forms.get(i);
            if (f == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            String p = "lines[" + i + "].";
            FcstLineValue v = line(f.itemCd(), f.bucketType(), f.bucketStart(), f.qty(), f.firmYn(), p, scales, e);
            if (v != null && !keys.add(v.itemId() + "|" + v.bucketType() + "|" + v.bucketStart())) {
                e.add(p + "bucketStart", "DUPLICATE");
            }
            lines.add(v);
        }
        e.throwIfAny();
        return store(cust.getCustId(), "MANUAL", null, remark, lines, me.loginId());
    }

    /** 새 버전 + 행 저장(올리기 실행도 이 길) */
    long store(long custId, String source, Long jobId, String remark, List<FcstLineValue> lines, String by) {
        mapper.lockCustRev(custId);
        int revNo = mapper.nextRevNo(custId);
        try {
            long revId = mapper.insertRev(custId, revNo, source, jobId, remark, by);
            for (int i = 0; i < lines.size(); i += 500) {
                mapper.insertFcstLines(revId, lines.subList(i, Math.min(lines.size(), i + 500)));
            }
            return revId;
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        } catch (DataIntegrityViolationException ex) {
            String c = PgErrors.constraint(ex).orElse("");
            if (c.startsWith("fk_sal_forecast_rev_cust")) {
                throw OrderService.fieldError("custCd", "INACTIVE");
            }
            if (c.startsWith("fk_sal_forecast_line_item")) {
                throw OrderService.fieldError("itemCd", "INACTIVE");
            }
            throw ex;
        }
    }

    /** 한 행 검증 — 품목(사용 중), 기간 유형, 시작(주 = 월요일, 월 = 1일), 수량 ≥ 0(단위 자릿수), 확정 Y/N */
    FcstLineValue line(String itemCd, String bucketType, String bucketStart, String qty, String firmYn, String p,
            Map<String, Integer> scales, FieldErrors e) {
        int before = e.list().size();
        ItemOption item = orders.item(itemCd, p + "itemCd", e);
        String type = isBlank(bucketType) ? null : bucketType.strip().toUpperCase(Locale.ROOT);
        if (type == null) {
            e.add(p + "bucketType", "REQUIRED");
        } else if (!BUCKETS.contains(type)) {
            e.add(p + "bucketType", "CODE");
        }
        LocalDate start = OrderService.date(bucketStart, p + "bucketStart", true, e);
        if (start != null && "WEEK".equals(type) && start.getDayOfWeek() != DayOfWeek.MONDAY) {
            e.add(p + "bucketStart", "WEEK_START");
        } else if (start != null && "MONTH".equals(type) && start.getDayOfMonth() != 1) {
            e.add(p + "bucketStart", "MONTH_START");
        }
        BigDecimal q = OrderService.qty(qty, p + "qty", OrderService.scaleOf(scales, item == null ? null : item.getUnitCd()), false, e);
        if (q == null && isBlank(qty)) {
            e.add(p + "qty", "REQUIRED");
        }
        String firm = isBlank(firmYn) ? "N" : firmYn.strip().toUpperCase(Locale.ROOT);
        if (!"Y".equals(firm) && !"N".equals(firm)) {
            e.add(p + "firmYn", "CODE");
        }
        if (e.list().size() > before) {
            return null;
        }
        return new FcstLineValue(item.getItemId(), type, start, q, firm);
    }
}
