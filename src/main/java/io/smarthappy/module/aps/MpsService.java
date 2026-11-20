package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.aps.MpsDtos.CellEdit;
import io.smarthappy.module.aps.MpsDtos.CellInsert;
import io.smarthappy.module.aps.MpsDtos.CellLock;
import io.smarthappy.module.aps.MpsDtos.CellSrc;
import io.smarthappy.module.aps.MpsDtos.CellView;
import io.smarthappy.module.aps.MpsDtos.CellsForm;
import io.smarthappy.module.aps.MpsDtos.ItemForm;
import io.smarthappy.module.aps.MpsDtos.ItemLookup;
import io.smarthappy.module.aps.MpsDtos.ItemRow;
import io.smarthappy.module.aps.MpsDtos.MpsDetail;
import io.smarthappy.module.aps.MpsDtos.MpsForm;
import io.smarthappy.module.aps.MpsDtos.MpsInsert;
import io.smarthappy.module.aps.MpsDtos.MpsItemSrc;
import io.smarthappy.module.aps.MpsDtos.MpsList;
import io.smarthappy.module.aps.MpsDtos.MpsRow;
import io.smarthappy.module.aps.MpsDtos.RowInsert;
import io.smarthappy.module.aps.MpsDtos.RowSrc;
import io.smarthappy.module.aps.MpsDtos.RowVerForm;
import io.smarthappy.module.aps.MpsDtos.RowView;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.module.sales.link.ForecastQuery;
import io.smarthappy.module.sales.link.OrderLineQuery;
import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 기준생산계획 MPS (aps-mps) — APS 7단계, docs/38 16장.
 *
 * - 계산 = 한 트랜잭션(반복 읽기): MPS 품목 → 수주·내시(영업 계약)·가용 재고(WMS 계약)·열린 지시 → {@link MpsCalculator} → 새 초안
 * - 초안은 계획 수량·사유만 고친다, 확정은 공장당 하나(이전 확정 대체), 지시·구매 요청은 만들지 않는다(AP-4)
 */
@Service
@RequiredArgsConstructor
public class MpsService {

    static final String PROG = "aps-mps";
    static final String RULE = "APS_MPS";
    static final int MIN_DAYS = 7;
    static final int MAX_DAYS = 92;
    static final int DEFAULT_DAYS = 28;
    static final int MAX_ITEMS = 300;
    static final int LIST_LIMIT = 500;
    static final int MAX_PERIOD_DAYS = 366;
    /** 예측 소진에 쓰는 수주 납기 상한 = 마지막 날 + 한 달(월 예측 기간이 기간 밖으로 걸칠 수 있다) */
    private static final int ORDER_TAIL_DAYS = 31;
    private static final int CHUNK = 500;

    private final MpsMapper mapper;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;
    private final OrderService orders;
    private final ObjectProvider<OrderLineQuery> orderQueries;
    private final ObjectProvider<ForecastQuery> forecastQueries;
    private final ObjectProvider<StockQuery> stockQueries;

    // ------------------------------------------------------------------ MPS 품목

    @Transactional(readOnly = true)
    public List<io.smarthappy.module.master.equip.EquipDtos.PlantRow> plants() {
        return mapper.findPlants();
    }

    @Transactional(readOnly = true)
    public List<ItemRow> items(String plantCd) {
        return mapper.findItems(VisibleText.isBlank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT));
    }

    /** 등록(id null) 또는 수정 — 공장·품목은 등록 때만 */
    @Transactional
    public ItemRow saveItem(Long mpsItemId, ItemForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ItemRow cur = null;
        String plant = null;
        ItemLookup item = null;
        if (mpsItemId != null) {
            cur = mapper.findItem(mpsItemId);
            if (cur == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            if (in.rowVer() == null) {
                e.add("rowVer", "REQUIRED");
            }
        } else {
            if (VisibleText.isBlank(in.plantCd())) {
                e.add("plantCd", "REQUIRED");
            } else {
                plant = in.plantCd().strip().toUpperCase(Locale.ROOT);
                if (!"Y".equals(mapper.plantUseYn(plant))) {
                    e.add("plantCd", "NO_SUCH_CODE");
                }
            }
            if (VisibleText.isBlank(in.itemCd())) {
                e.add("itemCd", "REQUIRED");
            } else {
                item = mapper.lookupItem(in.itemCd().strip().toUpperCase(Locale.ROOT));
                if (item == null) {
                    e.add("itemCd", "NO_SUCH_CODE");
                } else if (!"Y".equals(item.getUseYn())) {
                    e.add("itemCd", "INACTIVE");
                } else if (!"MAKE".equals(item.getProcType())) {
                    e.add("itemCd", "APS_MPS_NOT_MAKE");
                }
            }
        }
        // 안전재고·로트는 계획 기준정보(aps_item_param — docs/38 17장)에서 — 이 폼은 대상 지정만
        String remark = OrderService.text(in.remark(), "remark", 500, e);
        String use = VisibleText.isBlank(in.useYn()) ? "Y" : in.useYn().strip().toUpperCase(Locale.ROOT);
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "FORMAT");
        }
        e.throwIfAny();
        long id;
        try {
            if (cur == null) {
                id = mapper.insertItem(plant, item.getItemId(), remark, use, me.loginId());
            } else {
                if (mapper.updateItem(mpsItemId, remark, use, in.rowVer(), me.loginId()) == 0) {
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
                id = mpsItemId;
            }
        } catch (DataIntegrityViolationException ex) {
            if ("uq_aps_mps_item".equals(PgErrors.constraint(ex).orElse(""))) {
                throw new BusinessException(HbErrorCode.CONFLICT)
                        .withFieldErrors(FieldErrors.create().add("itemCd", "APS_MPS_ITEM_DUP").list());
            }
            throw ex;
        }
        return mapper.findItem(id);
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public MpsList list(String plantCd, String from, String to) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = OrderService.date(from, "from", true, e);
        LocalDate t = OrderService.date(to, "to", true, e);
        if (f != null && t != null) {
            if (f.isAfter(t)) {
                e.add("to", "INVALID_RANGE");
            } else if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_PERIOD_DAYS) {
                e.add("to", "RANGE_TOO_LONG", ChronoUnit.DAYS.between(f, t) + 1, MAX_PERIOD_DAYS);
            }
        }
        e.throwIfAny();
        ZoneId zone = clock.zone();
        List<MpsRow> rows = mapper.findList(VisibleText.isBlank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT),
                f.atStartOfDay(zone).toOffsetDateTime(), t.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), LIST_LIMIT + 1);
        boolean more = rows.size() > LIST_LIMIT;
        return new MpsList(more ? rows.subList(0, LIST_LIMIT) : rows, more);
    }

    /** 머리 + 품목 행 + 칸(예상 재고는 지금 계획 수량으로 계산) */
    @Transactional(readOnly = true)
    public MpsDetail detail(long mpsId) {
        MpsRow m = mps(mpsId);
        Map<Long, List<CellSrc>> cells = new LinkedHashMap<>();
        mapper.findCells(mpsId).forEach(c -> cells.computeIfAbsent(c.getMpsRowId(), k -> new ArrayList<>()).add(c));
        List<RowView> rows = new ArrayList<>();
        for (RowSrc r : mapper.findRows(mpsId)) {
            List<CellSrc> cs = cells.getOrDefault(r.getMpsRowId(), List.of());
            List<BigDecimal> pab = MpsCalculator.pab(new BigDecimal(r.getStockQty()), cs.stream().map(c -> dec(c.getSupplyWo())).toList(),
                    cs.stream().map(c -> dec(c.getDemandOrder()).add(dec(c.getDemandFcst()))).toList(),
                    cs.stream().map(c -> dec(c.getPlanQty())).toList());
            List<CellView> views = new ArrayList<>(cs.size());
            for (int i = 0; i < cs.size(); i++) {
                CellSrc c = cs.get(i);
                views.add(new CellView(c.getMpsCellId(), c.getBucketDate(), c.getDemandOrder(), c.getDemandFcst(), c.getPastDueQty(),
                        c.getSupplyWo(), c.getPropQty(), c.getBaseQty(), c.getFencedYn(), c.getPlanQty(), c.getChgReason(),
                        pab.get(i).stripTrailingZeros().toPlainString()));
            }
            rows.add(new RowView(r.getMpsRowId(), r.getItemId(), r.getItemCd(), r.getItemNm(), r.getUnitCd(), r.getStockQty(),
                    r.getSafetyQty(), r.getLotRule(), r.getLotQty(), r.getLotMult(), views));
        }
        MpsRow cur = mapper.findCurrent(m.getPlantCd());
        return new MpsDetail(m, cur == null ? null : cur.getMpsId(), rows);
    }

    // ------------------------------------------------------------------ 계산

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public long calculate(MpsForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APS_MPS_CALC");
        return calculate(in, me.loginId());
    }

    /** 계산 본체 — 회사 범위·트랜잭션은 호출자 */
    long calculate(MpsForm in, String by) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = null;
        if (VisibleText.isBlank(in.plantCd())) {
            e.add("plantCd", "REQUIRED");
        } else {
            plant = in.plantCd().strip().toUpperCase(Locale.ROOT);
        }
        LocalDate first = OrderService.date(in.horizonStart(), "horizonStart", false, e);
        int days = in.horizonDays() == null ? DEFAULT_DAYS : in.horizonDays();
        if (days < MIN_DAYS || days > MAX_DAYS) {
            e.add("horizonDays", "RANGE", MIN_DAYS, MAX_DAYS);
        }
        String nm = OrderService.text(in.mpsNm(), "mpsNm", 100, e);
        e.throwIfAny();
        if (!"Y".equals(mapper.plantUseYn(plant))) {
            throw OrderService.fieldError("plantCd", "NO_SUCH_CODE");
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.zone(plant));
        if (first == null) {
            first = today;
        } else if (first.isBefore(today)) {
            throw OrderService.fieldError("horizonStart", "PAST");
        } else if (first.isAfter(LocalDate.of(2999, 9, 1))) {
            throw OrderService.fieldError("horizonStart", "FORMAT");
        }
        List<MpsItemSrc> items = mapper.findMpsItems(plant);
        if (items.isEmpty()) {
            throw OrderService.fieldError("plantCd", "APS_MPS_NO_ITEM");
        }
        if (items.size() > MAX_ITEMS) {
            throw OrderService.fieldError("plantCd", "APS_TOO_LARGE");
        }
        LocalDate last = first.plusDays(days - 1L);
        int fenceDays = Integer.parseInt(policies.value(Policies.APS_MPS_FENCE_DAYS, plant));
        LocalDate fenceEnd = today.plusDays(fenceDays);
        List<Long> ids = items.stream().map(MpsItemSrc::getItemId).toList();

        Map<Long, List<MpsCalculator.Order>> ord = new HashMap<>();
        for (OrderLineQuery.OrderLineView l : OrderLineQuery.of(orderQueries).openLinesByItems(ids, last.plusDays(ORDER_TAIL_DAYS))) {
            BigDecimal rest = l.qty().subtract(l.shippedQty() == null ? BigDecimal.ZERO : l.shippedQty());
            if (rest.signum() > 0) {
                ord.computeIfAbsent(l.itemId(), k -> new ArrayList<>()).add(new MpsCalculator.Order(l.custId(), l.dueDate(), rest));
            }
        }
        Map<Long, List<MpsCalculator.Forecast>> fc = new HashMap<>();
        for (ForecastQuery.ForecastLineView f : ForecastQuery.of(forecastQueries).latestLines(ids, first, last)) {
            fc.computeIfAbsent(f.itemId(), k -> new ArrayList<>())
                    .add(new MpsCalculator.Forecast(f.custId(), f.bucketStart(), f.bucketEnd(), f.qty()));
        }
        Map<Long, List<MpsCalculator.Supply>> sup = new HashMap<>();
        mapper.findWoSupply(plant, ids, last).forEach(s -> sup.computeIfAbsent(s.getItemId(), k -> new ArrayList<>())
                .add(new MpsCalculator.Supply(s.getPlanEnd(), s.getRemainQty())));
        Map<Long, BigDecimal> stock = StockQuery.of(stockQueries).available(plant, ids);
        // 확정 구간 이월(M5): 현행 확정 MPS 의 기간·칸
        MpsRow cur = mapper.findCurrent(plant);
        Map<Long, Map<LocalDate, BigDecimal>> firmed = new HashMap<>();
        if (cur != null) {
            mapper.findFirmedCells(cur.getMpsId()).forEach(c -> firmed.computeIfAbsent(c.getItemId(), k -> new HashMap<>())
                    .put(c.getBucketDate(), c.getPlanQty()));
        }
        Map<String, Integer> scales = orders.unitScales();

        String no = numbering.nextOrDefault(RULE, "기준생산계획 번호", "MPS{yyMMdd}-{seq:3}", "DAY");
        long mpsId = mapper.insertMps(new MpsInsert(no, plant, nm, first, days, fenceDays, fenceEnd, cur == null ? null : cur.getMpsId(),
                items.size()), by);
        List<CellInsert> cells = new ArrayList<>();
        for (MpsItemSrc it : items) {
            BigDecimal st = stock.getOrDefault(it.getItemId(), BigDecimal.ZERO);
            st = st == null ? BigDecimal.ZERO : st;
            MpsCalculator.Firmed fm = cur == null ? null
                    : new MpsCalculator.Firmed(cur.getHorizonStart(), cur.getHorizonStart().plusDays(cur.getHorizonDays() - 1L),
                            firmed.getOrDefault(it.getItemId(), Map.of()));
            List<MpsCalculator.Cell> out = MpsCalculator.calculate(new MpsCalculator.ItemIn(it.getItemId(),
                    OrderService.scaleOf(scales, it.getUnitCd()), st, it.getSafetyQty(), it.getLotRule(), it.getLotQty(), it.getLotMult(),
                    ord.getOrDefault(it.getItemId(), List.of()), fc.getOrDefault(it.getItemId(), List.of()),
                    sup.getOrDefault(it.getItemId(), List.of()), fm), first, days, fenceEnd);
            long rowId = mapper.insertRow(mpsId, new RowInsert(it.getItemId(), it.getUnitCd(), st, it.getSafetyQty(), it.getLotRule(),
                    it.getLotQty(), it.getLotMult()));
            for (MpsCalculator.Cell c : out) {
                cells.add(new CellInsert(rowId, c.date(), c.demandOrder(), c.demandFcst(), c.pastDue(), c.supplyWo(), c.prop(), c.base(),
                        c.fenced() ? "Y" : "N"));
            }
        }
        for (int i = 0; i < cells.size(); i += CHUNK) {
            mapper.insertCells(mpsId, cells.subList(i, Math.min(cells.size(), i + CHUNK)));
        }
        return mpsId;
    }

    // ------------------------------------------------------------------ 편집

    /** 초안 칸 편집 — 머리 잠금·rowVer → 칸 잠금 → 검사 전부 → 바뀐 칸만 저장, 머리 rowVer 하나 올림 */
    @Transactional
    public int saveCells(long mpsId, CellsForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null || in.cells() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String status = mapper.lockMps(mpsId);
        if (status == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"DRAFT".equals(status)) {
            throw OrderService.fieldError("status", "STATE");
        }
        if (mps(mpsId).getRowVer() != in.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        List<Long> ids = in.cells().stream().map(CellEdit::cellId).filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.size() != in.cells().size() || ids.size() > 5000) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (ids.isEmpty()) {
            return in.rowVer();
        }
        Map<Long, CellLock> locked = new HashMap<>();
        mapper.lockCells(mpsId, ids).forEach(c -> locked.put(c.getMpsCellId(), c));
        if (locked.size() != ids.size()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        Map<String, Integer> scales = orders.unitScales();
        FieldErrors e = FieldErrors.create();
        List<Object[]> changes = new ArrayList<>();
        for (CellEdit c : in.cells()) {
            CellLock cur = locked.get(c.cellId());
            FieldErrors one = FieldErrors.create();
            BigDecimal q = OrderService.qty(c.planQty(), "planQty", OrderService.scaleOf(scales, cur.getUnitCd()), false, one);
            if (q == null && VisibleText.isBlank(c.planQty())) {
                one.add("planQty", "REQUIRED");
            }
            String reason = OrderService.text(c.reason(), "reason", 500, one);
            if (q != null && "Y".equals(cur.getFencedYn()) && q.compareTo(new BigDecimal(cur.getBaseQty())) != 0 && reason == null) {
                one.add("reason", "APS_MPS_FENCE_REASON");
            }
            if (!one.isEmpty()) {
                one.list().forEach(fe -> e.row(String.valueOf(c.cellId())).add(fe.field(), fe.code(), fe.args()));
                continue;
            }
            boolean same = q.compareTo(new BigDecimal(cur.getPlanQty())) == 0 && java.util.Objects.equals(reason, cur.getChgReason());
            if (!same) {
                changes.add(new Object[] {c.cellId(), q, reason});
            }
        }
        e.throwIfAny();
        for (Object[] ch : changes) {
            mapper.updateCell((Long) ch[0], (BigDecimal) ch[1], (String) ch[2]);
        }
        if (mapper.touch(mpsId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return in.rowVer() + 1;
    }

    // ------------------------------------------------------------------ 확정·폐기

    /** 확정 — 같은 공장 확정 줄 세우기 → 초안·rowVer → 낡음(기준 MPS ≠ 현행) → 현행 대체 → 이 초안 확정 */
    @Transactional
    public void firm(long mpsId, RowVerForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        inbox.claimRequestKey("APS_MPS_FIRM");
        MpsRow m = mps(mpsId);
        mapper.lockFirmPlant(m.getPlantCd());
        if (mapper.lockMps(mpsId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        m = mps(mpsId);
        if (!"DRAFT".equals(m.getStatus())) {
            throw OrderService.fieldError("status", "STATE");
        }
        if (m.getRowVer() != in.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        MpsRow cur = mapper.findCurrent(m.getPlantCd());
        Long curId = cur == null ? null : cur.getMpsId();
        if (!java.util.Objects.equals(curId, m.getBaseMpsId())) {
            throw new BusinessException(HbErrorCode.APS_MPS_STALE);
        }
        if (cur != null) {
            mapper.lockMps(cur.getMpsId());
            mapper.supersede(cur.getMpsId(), me.loginId());
        }
        if (mapper.firm(mpsId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    @Transactional
    public void discard(long mpsId, ApsDtos.DiscardForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String reason = OrderService.text(in.reason(), "reason", 500, e);
        if (reason == null) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        MpsRow m = mps(mpsId);
        if (!"DRAFT".equals(m.getStatus())) {
            throw OrderService.fieldError("status", "STATE");
        }
        if (mapper.discard(mpsId, reason, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 도우미

    private MpsRow mps(long mpsId) {
        MpsRow m = mapper.findMps(mpsId);
        if (m == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return m;
    }

    private static BigDecimal dec(String s) {
        return s == null ? BigDecimal.ZERO : new BigDecimal(s);
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
