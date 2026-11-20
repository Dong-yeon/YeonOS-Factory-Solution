package io.smarthappy.module.production;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.WoDtos.ActForm;
import io.smarthappy.module.production.WoDtos.AssignForm;
import io.smarthappy.module.production.WoDtos.EquipOption;
import io.smarthappy.module.production.WoDtos.ParamRow;
import io.smarthappy.module.production.WoDtos.ProcRow;
import io.smarthappy.module.production.WoDtos.RefRow;
import io.smarthappy.module.production.WoDtos.RevOption;
import io.smarthappy.module.production.WoDtos.SnapBom;
import io.smarthappy.module.production.WoDtos.SrcBomLine;
import io.smarthappy.module.production.WoDtos.WoDetail;
import io.smarthappy.module.production.WoDtos.WoForm;
import io.smarthappy.module.production.WoDtos.WoList;
import io.smarthappy.module.production.WoDtos.WoOptions;
import io.smarthappy.module.production.WoDtos.WoPage;
import io.smarthappy.module.production.WoDtos.WoRow;
import io.smarthappy.module.production.WoDtos.WoSort;
import io.smarthappy.module.production.WoDtos.WoValue;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 작업지시 관리 (prd-wo) — P2 3단계, docs/25 4.4.
 *
 * - 만들 때 채번(PRD_WO), 라우팅·BOM 을 비우면 계획 시작일에 유효한 대체 번호 1 확정 리비전
 * - 발행 = 한 트랜잭션: 조건부 상태 변경(rowVer) → 기준정보가 지금도 유효한지 다시 확인 → 스냅샷 3종
 * - 자재 소요 = BOM 1단, 가상 조립은 펼친다(10단·순환 차단), 자품목 기본 단위로 환산(없으면 발행 막음), 소수 6자리 올림
 */
@Service
@RequiredArgsConstructor
public class WoService {

    static final String PROG = "prd-wo";
    static final String RULE = "PRD_WO";
    static final int LIST_LIMIT = 500;
    static final int MAX_PERIOD_DAYS = 366;
    static final int PHANTOM_DEPTH = 10;
    static final Set<String> STATUSES = Set.of("PLANNED", "RELEASED", "IN_PROGRESS", "DONE", "CLOSED", "CANCELLED");
    /** 목록 조회 묶음(저장 값 아님) — 발행+진행 중(홈 카드 prd-wo-today 링크, docs/25 4.13) */
    static final String ACTIVE = "ACTIVE";
    private static final MathContext MC = new MathContext(34);

    private final WoMapper mapper;
    private final OrderService orders;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;
    private final io.smarthappy.platform.excel.ExcelExportService excel;
    /** 발행 때 추가 BOM 확장 지점(docs/72 2.4). 구현 없으면 비어 있다 */
    private final org.springframework.beans.factory.ObjectProvider<WoBomExtension> bomExtensions;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<io.smarthappy.module.master.equip.EquipDtos.PlantRow> plants() {
        return mapper.findPlants();
    }

    @Transactional(readOnly = true)
    public WoList list(String plantCd, String from, String to, String status, String itemCd, String keyword) {
        ListCond c = listCond(plantCd, from, to, status, itemCd, keyword);
        if (c.noItem()) {
            return new WoList(List.of(), false);
        }
        List<WoRow> rows = mapper.findWos(c.plantCd(), c.from(), c.to(), c.status(), c.itemId(), c.keyword(), null, LIST_LIMIT + 1);
        boolean more = rows.size() > LIST_LIMIT;
        return new WoList(more ? rows.subList(0, LIST_LIMIT) : rows, more);
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link WoSort}). 기본 정렬 = 목록과 같은 계획 시작 최신·우선 */
    @Transactional(readOnly = true)
    public WoPage grid(String plantCd, String from, String to, String status, String itemCd, String keyword,
            Integer page, Integer size, List<String> sort, Boolean total) {
        ListCond c = listCond(plantCd, from, to, status, itemCd, keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, WoSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("planStart,desc", "priority,asc"));
        if (c.noItem()) {
            return new WoPage(List.of(), pq.isWithTotal() ? 0L : null);
        }
        List<WoRow> rows = mapper.findWoPage(c.plantCd(), c.from(), c.to(), c.status(), c.itemId(), c.keyword(), null, pq);
        return new WoPage(rows, pq.isWithTotal()
                ? mapper.countWos(c.plantCd(), c.from(), c.to(), c.status(), c.itemId(), c.keyword(), null) : null);
    }

    /** grid 열(화면 지시 표와 같은 칸) — 상태는 화면이 배지로(GridOptions.status) */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("woNo", "prdWo.col.woNo", 130).withSort(),
            ColumnSpec.text("itemCd", "prdWo.col.itemCd", 120).withSort(),
            ColumnSpec.text("itemNm", "prdWo.col.itemNm", 180).withFlex().withPriority(3),
            ColumnSpec.dec("planQty", "prdWo.col.planQty", 6, 100).withMinScale(0),
            ColumnSpec.text("unitCd", "prdWo.col.unit", 60).withPriority(3),
            ColumnSpec.of("planStart", "prdWo.col.planStart", ColumnType.DATE, 100).withSort(),
            ColumnSpec.code("status", "prdWo.col.status", "WO_STATUS", 90).withSort(),   // 상세가 열려 목록이 좁아도 보이게 앞쪽
            ColumnSpec.of("priority", "prdWo.col.priority", ColumnType.INT, 60).withSort().withPriority(3),
            ColumnSpec.of("planEnd", "prdWo.col.planEnd", ColumnType.DATE, 100).withSort().withPriority(3),
            ColumnSpec.text("orderRef", "prdWo.col.order", 130).withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** grid 엑셀(docs/25 4.40) — 목록·grid 와 같은 조건 검사, grid 정렬(허용 목록)·보이는 열. 상태는 코드명으로(엑셀 서비스) */
    @Transactional(readOnly = true)
    public long export(WoDtos.WoExport req, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        WoDtos.WoExport r = req == null ? new WoDtos.WoExport(null, null, null, null, null, null, null, null) : req;
        ListCond c = listCond(r.plantCd(), r.from(), r.to(), r.status(), r.itemCd(), r.keyword());
        List<ColumnSpec> cols = MesExport.named(ColumnSpec.selectExportable(GRID_COLUMNS, r.columns()), java.util.Map.of("planQty", "planQtyNum"));
        PageQuery pq = PageQuery.sortOnly(r.sort(), WoSort.values(), List.of("planStart,desc", "priority,asc"));
        List<String> keys = new ArrayList<>();
        for (String[] x : new String[][] {{"plantCd", c.plantCd()}, {"from", r.from()}, {"to", r.to()}, {"status", c.status()},
                {"itemCd", isBlank(r.itemCd()) ? null : r.itemCd()}, {"keyword", c.keyword()}}) {
            if (x[1] != null && !x[1].isBlank()) {
                keys.add(x[0]);
            }
        }
        long count = c.noItem() ? 0 : mapper.countWos(c.plantCd(), c.from(), c.to(), c.status(), c.itemId(), c.keyword(), null);
        return excel.export(response, new io.smarthappy.platform.excel.ExcelExportService.ExportJob(PROG, "prog.prdWo", cols, count, keys),
                w -> {
                    if (!c.noItem()) {
                        mapper.findWoPage(c.plantCd(), c.from(), c.to(), c.status(), c.itemId(), c.keyword(), null, pq).forEach(w);
                    }
                });
    }

    /** 목록·grid 공통 조건 검사 — 품목 코드가 없는 품목이면 noItem(빈 결과) */
    private ListCond listCond(String plantCd, String from, String to, String status, String itemCd, String keyword) {
        FieldErrors e = FieldErrors.create();
        // 열린 기간 — 한쪽을 비우면 그쪽 끝이 없다(홈 링크 "계획 시작 ≤ 오늘"). 순서·상한은 둘 다 있을 때만
        LocalDate f = date(from, "from", false, e);
        LocalDate t = date(to, "to", false, e);
        if (f != null && t != null) {
            if (f.isAfter(t)) {
                e.add("to", "INVALID_RANGE");
            } else if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_PERIOD_DAYS) {
                e.add("to", "RANGE_TOO_LONG", ChronoUnit.DAYS.between(f, t) + 1, MAX_PERIOD_DAYS);
            }
        }
        if (!isBlank(status) && !STATUSES.contains(status) && !ACTIVE.equals(status)) {
            e.add("status", "CODE");
        }
        if (!isBlank(keyword) && keyword.strip().length() > 50) {
            e.add("keyword", "TOO_LONG", 50);
        }
        e.throwIfAny();
        Long itemId = null;
        if (!isBlank(itemCd)) {
            ItemOption i = mapper.findItem(itemCd.strip().toUpperCase(Locale.ROOT));
            if (i == null) {
                return new ListCond(null, f, t, null, null, null, true);
            }
            itemId = i.getItemId();
        }
        return new ListCond(isBlank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT), f, t, isBlank(status) ? null : status,
                itemId, isBlank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)), false);
    }

    private record ListCond(String plantCd, LocalDate from, LocalDate to, String status, Long itemId, String keyword, boolean noItem) {
    }

    @Transactional(readOnly = true)
    public WoDetail detail(long woId) {
        WoRow w = mapper.findWo(woId);
        if (w == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<ProcRow> procs = mapper.findProcs(woId);
        Map<Long, List<ParamRow>> byProc = new LinkedHashMap<>();
        mapper.findParams(woId).forEach(p -> byProc.computeIfAbsent(p.getWoProcId(), k -> new ArrayList<>()).add(p));
        procs.forEach(p -> p.setParams(byProc.getOrDefault(p.getWoProcId(), List.of())));
        return new WoDetail(w, procs, mapper.findBoms(woId));
    }

    /** 폼 선택지: 품목 단위·확정 라우팅(공장)·확정 BOM·진행 중 수주 행 — 기준일 = 계획 시작일(비우면 오늘) */
    @Transactional(readOnly = true)
    public WoOptions options(String itemCd, String plantCd, String date) {
        FieldErrors e = FieldErrors.create();
        ItemOption item = orders.item(itemCd, "itemCd", e);
        LocalDate d = isBlank(date) ? clock.today() : date(date, "planStart", true, e);
        e.throwIfAny();
        String plant = isBlank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT);
        return new WoOptions(item.getItemCd(), item.getItemNm(), item.getUnitCd(),
                plant == null ? List.of() : mapper.routingOptions(item.getItemId(), plant, d),
                mapper.bomOptions(item.getItemId(), d), mapper.orderLineOptions(item.getItemId()));
    }

    /** 품목 찾기(입력 보조) — 수주 화면과 같은 규칙(코드 앞부분·이름, 사용 중, 20개) */
    @Transactional(readOnly = true)
    public List<ItemOption> items(String q) {
        return orders.items(q);
    }

    @Transactional(readOnly = true)
    public List<EquipOption> equips(String plantCd) {
        if (isBlank(plantCd)) {
            throw OrderService.fieldError("plantCd", "REQUIRED");
        }
        return mapper.equipOptions(plantCd.strip().toUpperCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ 만들기·고치기

    @Transactional
    public long create(WoForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PRD_WO_CREATE");
        WoValue v = validate(in, null);
        return insert(v, me.loginId());
    }

    /** 요청 밖(데모 시더)에서 만들기 — 화면과 같은 검증, 회사 범위·트랜잭션은 호출자(TenantRunner) */
    public long createAs(WoForm in, String by) {
        return insert(validate(in, null), by);
    }

    /** 요청 밖(데모 시더)에서 발행 */
    public void releaseAs(long woId, String by) {
        WoRow w = mapper.findWo(woId);
        if (w == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        release(woId, w.getRowVer(), by);
    }

    /** 만들기 — 채번은 업무 트랜잭션 안에서 */
    long insert(WoValue v, String by) {
        String no = numbering.nextOrDefault(RULE, "작업지시 번호", "WO{yyMM}-{seq:4}", "MONTH");
        try {
            return mapper.insertWo(no, v, by);
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 계획 상태면 전부, 발행 뒤(RELEASED·IN_PROGRESS)면 일정·우선순위·비고만 */
    @Transactional
    public void update(long woId, WoForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        WoRow cur = mapper.findWo(woId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        boolean planned = "PLANNED".equals(cur.getStatus());
        if (!planned && !"RELEASED".equals(cur.getStatus()) && !"IN_PROGRESS".equals(cur.getStatus())) {
            throw OrderService.fieldError("status", "STATE");
        }
        WoValue v = validate(in, cur);
        try {
            int n = planned ? mapper.updatePlanned(woId, v, in.rowVer(), me.loginId())
                    : mapper.updateSchedule(woId, v, in.rowVer(), me.loginId());
            if (n == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    // ------------------------------------------------------------------ 발행·취소·배정

    @Transactional
    public void release(long woId, ActForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        release(woId, in.rowVer(), me.loginId());
    }

    /** 발행 — 조건부 상태 변경 뒤 스냅샷 */
    void release(long woId, int rowVer, String by) {
        String st = mapper.lockWo(woId);
        if (st == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"PLANNED".equals(st)) {
            throw OrderService.fieldError("status", "STATE");
        }
        WoRow w = mapper.findWo(woId);
        if (w.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        // 만든 뒤 기준정보가 폐기·기간 변경됐을 수 있다 — 계획 시작일 기준으로 다시
        FieldErrors e = FieldErrors.create();
        checkRef(mapper.findRouting(w.getRoutingId()), w.getItemId(), w.getPlantCd(), w.getPlanStart(), "routingId", e);
        RefRow bom = w.getBomId() == null ? null : mapper.findBom(w.getBomId());
        if (bom != null) {
            checkRef(bom, w.getItemId(), null, w.getPlanStart(), "bomId", e);
        }
        if (w.getOrderLineId() != null && mapper.orderLineOptions(w.getItemId()).stream()
                .noneMatch(o -> o.getLineId() == w.getOrderLineId())) {
            e.add("orderLineId", "ORDER_LINE");
        }
        e.throwIfAny();
        List<SnapBom> lines = bom == null ? List.of() : explode(bom, new BigDecimal(w.getPlanQty()), w.getPlanStart());
        lines = withExtraBoms(woId, w, lines);
        if (mapper.release(woId, rowVer, by) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (mapper.insertProcs(woId, w.getRoutingId(), by) == 0) {
            throw OrderService.fieldError("routingId", "NO_STEPS");
        }
        mapper.insertParams(woId);
        for (int i = 0; i < lines.size(); i += 500) {
            mapper.insertBoms(woId, lines.subList(i, Math.min(lines.size(), i + 500)));
        }
    }

    @Transactional
    public void cancel(long woId, ActForm in) {
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
        String st = mapper.lockWo(woId);
        if (st == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"PLANNED".equals(st) && !"RELEASED".equals(st)) {
            throw OrderService.fieldError("status", "STATE");
        }
        if (mapper.cancel(woId, reason, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 지시 공정에 설비 배정(D1 선택 칸) — 비우면 해제. 같은 공장의 사용 중 설비만 */
    @Transactional
    public void assign(long woProcId, AssignForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Long woId = mapper.procWoId(woProcId);
        if (woId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String st = mapper.lockWo(woId);
        if (!"RELEASED".equals(st) && !"IN_PROGRESS".equals(st)) {
            throw OrderService.fieldError("status", "STATE");
        }
        Long equipId = null;
        if (!isBlank(in.equipCd())) {
            EquipOption eq = mapper.findEquip(mapper.findWo(woId).getPlantCd(), in.equipCd().strip().toUpperCase(Locale.ROOT));
            if (eq == null) {
                throw OrderService.fieldError("equipCd", "NO_SUCH_CODE");
            }
            equipId = eq.getEquipId();
        }
        try {
            if (mapper.assignEquip(woProcId, equipId, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    // ------------------------------------------------------------------ 자재 소요(스냅샷)

    /**
     * 확장 지점이 준 추가 BOM 을 본체 줄 뒤에 이어 붙인다(docs/72 2.4). 줄 번호만 다시 매기고 출처는 그대로.
     * 추가 BOM 은 확정·기준일 유효여야 한다(본체와 같은 checkRef)
     */
    private List<SnapBom> withExtraBoms(long woId, WoRow w, List<SnapBom> base) {
        List<WoBomExtension.ExtraBom> extras = new ArrayList<>();
        bomExtensions.orderedStream().forEach(x -> extras.addAll(x.extraBoms(woId, w.getPlanStart(), new BigDecimal(w.getPlanQty()))));
        if (extras.isEmpty()) {
            return base;
        }
        List<SnapBom> out = new ArrayList<>(base);
        for (WoBomExtension.ExtraBom x : extras) {
            RefRow b = mapper.findBom(x.bomId());
            FieldErrors e = FieldErrors.create();
            if (b == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            checkRef(b, b.getItemId(), null, w.getPlanStart(), "bomId", e);
            e.throwIfAny();
            for (SnapBom s : explode(b, x.qty(), w.getPlanStart())) {
                out.add(new SnapBom(out.size() + 1, s.itemId(), s.lineType(), s.reqQty(), s.unitCd(), s.opSeq(), s.issueMethod(), s.altGrp(),
                        s.altPriority(), s.altPct(), s.srcBomId(), s.srcLineId(), s.srcQty(), s.srcUnitCd(), s.scrapPct(), s.phantomPath()));
            }
        }
        return out;
    }

    /** BOM 1단 소요량 — 가상 조립은 그 품목의 유효 BOM 으로 펼친다. 환산·BOM 없음은 발행을 막는다 */
    List<SnapBom> explode(RefRow bom, BigDecimal qty, LocalDate date) {
        List<SnapBom> out = new ArrayList<>();
        FieldErrors e = FieldErrors.create();
        explode(bom, qty, date, null, new HashSet<>(Set.of(bom.getItemId())), 1, out, e);
        e.throwIfAny();
        return out;
    }

    private void explode(RefRow bom, BigDecimal parentQty, LocalDate date, String path, Set<Long> visiting, int depth,
            List<SnapBom> out, FieldErrors e) {
        for (SrcBomLine l : mapper.bomLines(bom.getId())) {
            if (l.getFactor() == null) {
                e.add("bomId", "WO_NO_CONV", l.getItemCd(), l.getUnitCd(), l.getItemUnit());
                continue;
            }
            BigDecimal base = "Y".equals(l.getFixedYn()) ? l.getQty() : l.getQty().divide(bom.getBaseQty(), MC).multiply(parentQty, MC);
            BigDecimal req = base.multiply(BigDecimal.ONE.add(l.getScrapPct().movePointLeft(2)), MC).multiply(l.getFactor(), MC);
            if (l.getAltPct() != null) {
                req = req.multiply(l.getAltPct().movePointLeft(2), MC);
            }
            if ("PHANTOM".equals(l.getLineType())) {
                if (depth >= PHANTOM_DEPTH || visiting.contains(l.getItemId())) {
                    e.add("bomId", "PHANTOM_DEPTH", l.getItemCd());
                    continue;
                }
                RefRow sub = mapper.effectiveBom(l.getItemId(), date);
                if (sub == null) {
                    e.add("bomId", "PHANTOM_NO_BOM", l.getItemCd());
                    continue;
                }
                visiting.add(l.getItemId());
                explode(sub, req, date, path == null ? l.getItemCd() : path + " > " + l.getItemCd(), visiting, depth + 1, out, e);
                visiting.remove(l.getItemId());
                continue;
            }
            if (req.signum() <= 0) {
                continue;           // 대체 비율 0 행 — 이번 지시에는 쓰지 않는다
            }
            BigDecimal rounded = req.setScale(6, RoundingMode.UP).stripTrailingZeros();
            out.add(new SnapBom(out.size() + 1, l.getItemId(), l.getLineType(), rounded.scale() < 0 ? rounded.setScale(0) : rounded,
                    l.getItemUnit(), l.getOpSeq(), l.getIssueMethod(), l.getAltGrp(), l.getAltPriority(), l.getAltPct(),
                    l.getBomId(), l.getLineId(), l.getQty(), l.getUnitCd(), l.getScrapPct(), path));
        }
    }

    // ------------------------------------------------------------------ 검증

    /** 화면 저장·엑셀 올리기 공용 검증 — 칸 오류를 모두 모은 뒤 한 번에 던진다 */
    WoValue validate(WoForm in, WoRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        boolean planned = cur == null || "PLANNED".equals(cur.getStatus());
        // 공장·품목: 만든 뒤 고정
        String plant;
        ItemOption item;
        if (cur != null) {
            plant = cur.getPlantCd();
            if (!isBlank(in.plantCd()) && !plant.equals(in.plantCd().strip().toUpperCase(Locale.ROOT))) {
                e.add("plantCd", "FIXED");
            }
            item = mapper.findItem(cur.getItemCd());
            if (!isBlank(in.itemCd()) && !cur.getItemCd().equals(in.itemCd().strip().toUpperCase(Locale.ROOT))) {
                e.add("itemCd", "FIXED");
            }
        } else {
            plant = isBlank(in.plantCd()) ? null : in.plantCd().strip().toUpperCase(Locale.ROOT);
            if (plant == null) {
                e.add("plantCd", "REQUIRED");
            } else {
                String use = plant.length() > 20 ? null : mapper.plantUseYn(plant);
                if (use == null) {
                    e.add("plantCd", "NO_SUCH_CODE");
                    plant = null;
                } else if (!"Y".equals(use)) {
                    e.add("plantCd", "INACTIVE");
                    plant = null;
                }
            }
            item = orders.item(in.itemCd(), "itemCd", e);
        }
        // 수량: 품목 기본 단위 자릿수 — 발행 뒤에는 고정
        BigDecimal qty;
        if (planned) {
            qty = OrderService.qty(in.planQty(), "planQty", item == null ? 6 : OrderService.scaleOf(orders.unitScales(),
                    item.getUnitCd()), true, e);
            if (qty == null && isBlank(in.planQty())) {
                e.add("planQty", "REQUIRED");
            }
        } else {
            qty = new BigDecimal(cur.getPlanQty());
            if (!isBlank(in.planQty()) && new BigDecimal(cur.getPlanQty()).compareTo(parseOrNull(in.planQty())) != 0) {
                e.add("planQty", "FIXED");
            }
        }
        LocalDate start = date(in.planStart(), "planStart", true, e);
        LocalDate end = isBlank(in.planEnd()) ? start : date(in.planEnd(), "planEnd", true, e);
        if (start != null && end != null && end.isBefore(start)) {
            e.add("planEnd", "INVALID_RANGE");
        }
        int priority = in.priority() == null ? 5 : in.priority();
        if (priority < 1 || priority > 9) {
            e.add("priority", "RANGE", 1, 9);
        }
        String remark = OrderService.text(in.remark(), "remark", 1000, e);
        Long routingId = cur == null ? null : cur.getRoutingId();
        Long bomId = cur == null ? null : cur.getBomId();
        Long orderLineId = cur == null ? null : cur.getOrderLineId();
        if (planned && item != null && plant != null && start != null) {
            routingId = pick(in.routingId(), mapper.routingOptions(item.getItemId(), plant, start), "routingId", true, e);
            bomId = pick(in.bomId(), mapper.bomOptions(item.getItemId(), start), "bomId", false, e);
            orderLineId = in.orderLineId();
            if (orderLineId != null && mapper.orderLineOptions(item.getItemId()).stream()
                    .noneMatch(o -> o.getLineId() == in.orderLineId())) {
                e.add("orderLineId", "ORDER_LINE");
            }
        } else if (!planned) {
            if (in.routingId() != null && !in.routingId().equals(cur.getRoutingId())) {
                e.add("routingId", "FIXED");
            }
            if (in.bomId() != null && !in.bomId().equals(cur.getBomId())) {
                e.add("bomId", "FIXED");
            }
            if (in.orderLineId() != null && !in.orderLineId().equals(cur.getOrderLineId())) {
                e.add("orderLineId", "FIXED");
            }
        }
        e.throwIfAny();
        return new WoValue(plant, item.getItemId(), qty, item.getUnitCd(), start, end, routingId, bomId, orderLineId, priority,
                remark);
    }

    /** 고른 리비전이 선택지(확정·기준일 유효)에 있어야 한다. 비우면 대체 번호가 가장 작은 것(필수인데 없으면 NO_ROUTING) */
    private static Long pick(Long chosen, List<RevOption> options, String field, boolean required, FieldErrors e) {
        if (chosen == null) {
            if (options.isEmpty()) {
                if (required) {
                    e.add(field, "NO_ROUTING");
                }
                return null;
            }
            return options.get(0).getId();
        }
        if (options.stream().noneMatch(o -> o.getId() == chosen)) {
            e.add(field, "STALE_REV");
            return null;
        }
        return chosen;
    }

    private static void checkRef(RefRow r, long itemId, String plantCd, LocalDate date, String field, FieldErrors e) {
        boolean ok = r != null && r.getItemId() == itemId && "RELEASED".equals(r.getStatus())
                && (plantCd == null || plantCd.equals(r.getPlantCd()))
                && !r.getValidFrom().isAfter(date) && (r.getValidTo() == null || date.isBefore(r.getValidTo()));
        if (!ok) {
            e.add(field, "STALE_REV");
        }
    }

    private static BigDecimal parseOrNull(String s) {
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException ex) {
            return BigDecimal.valueOf(-1);
        }
    }

    static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_prd_wo_routing_item" -> OrderService.fieldError("routingId", "STALE_REV");
            case "fk_prd_wo_bom_item" -> OrderService.fieldError("bomId", "STALE_REV");
            case "fk_prd_wo_order_line_item" -> OrderService.fieldError("orderLineId", "ORDER_LINE");
            case "fk_prd_wo_item_comp" -> OrderService.fieldError("itemCd", "INACTIVE");
            case "fk_prd_wo_plant" -> OrderService.fieldError("plantCd", "NO_SUCH_CODE");
            case "fk_prd_wo_proc_equip_plant" -> OrderService.fieldError("equipCd", "NO_SUCH_CODE");
            case "ck_prd_wo_transition", "ck_prd_wo_closed", "ck_prd_wo_released_fixed" -> OrderService.fieldError("status", "STATE");
            default -> e;
        };
    }

    private static LocalDate date(String v, String field, boolean required, FieldErrors e) {
        return OrderService.date(v, field, required, e);
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
