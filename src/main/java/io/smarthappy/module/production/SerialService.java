package io.smarthappy.module.production;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.LotDtos.LockedLot;
import io.smarthappy.module.production.LotDtos.NewLot;
import io.smarthappy.module.production.SerialDtos.CompareView;
import io.smarthappy.module.production.SerialDtos.CreateForm;
import io.smarthappy.module.production.SerialDtos.InstallForm;
import io.smarthappy.module.production.SerialDtos.LockedSerial;
import io.smarthappy.module.production.SerialDtos.PartItem;
import io.smarthappy.module.production.SerialDtos.PartRow;
import io.smarthappy.module.production.SerialDtos.RemoveForm;
import io.smarthappy.module.production.SerialDtos.ReplaceForm;
import io.smarthappy.module.production.SerialDtos.SerialDetail;
import io.smarthappy.module.production.SerialDtos.SerialExport;
import io.smarthappy.module.production.SerialDtos.SerialPage;
import io.smarthappy.module.production.SerialDtos.SerialRow;
import io.smarthappy.module.production.SerialDtos.SerialSort;
import io.smarthappy.module.production.SerialDtos.SerialWo;
import io.smarthappy.module.production.SerialDtos.TraceExport;
import io.smarthappy.module.production.SerialDtos.TracePage;
import io.smarthappy.module.production.SerialDtos.TraceRow;
import io.smarthappy.module.production.SerialDtos.TraceSort;
import io.smarthappy.module.production.SerialDtos.WoBomLine;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 장비 시리얼 구성(as-built) — prd-serial·prd-serial-trace, docs/77.
 *
 * - 시리얼 = 지시 1건에 계획 수량(정수)만큼, 번호는 항상 채번(SERIAL), 시리얼마다 장비 LOT(UNIT, 수량 1, 번호 = 시리얼)
 * - 장착 = 장착 원장 INSTALL + (LOT 관리 품목) 계보 CONSUME 부품 LOT → 장비 LOT, 한 트랜잭션
 * - 탈거 = REMOVE(장착 행의 반대) + 계보 역분개 / 교체 = 탈거 + 새 장착
 * - 저장은 모두 시리얼 머리 FOR UPDATE → row_ver 확인(다르면 409, 남의 저장 덮어쓰기 방지) → 쓰기 → row_ver + 1
 * - 잠금 순서: 시리얼 머리 → (부품 시리얼 advisory) → 부품 LOT(계보 트리거) — 장착·탈거·교체 모두 같은 순서
 */
@Service
@RequiredArgsConstructor
public class SerialService {

    static final String PROG = "prd-serial";
    static final String TRACE_PROG = "prd-serial-trace";
    static final String RULE = "SERIAL";
    static final String UNIT = "UNIT";
    static final int MAX_CREATE = 100;
    static final int WO_LIMIT = 50;
    static final int TRACE_LIMIT = 5000;
    static final Set<String> METHODS = Set.of("KEY", "SCAN");
    /** 부품 시리얼 — 출력 가능한 ASCII, 앞뒤 공백 없음(DB CHECK 와 같다) */
    static final Pattern PART_SN = Pattern.compile("^[\\x21-\\x7E]([\\x20-\\x7E]*[\\x21-\\x7E])?$");
    static final Pattern LOCAL_MIN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$");

    private final SerialMapper mapper;
    private final LotMapper lotMapper;
    private final LotService lots;
    private final NumberingService numbering;
    private final PolicyService policies;
    private final InboxService inbox;
    private final BizClock clock;
    private final ExcelExportService excel;

    // ------------------------------------------------------------------ 목록

    static final List<ColumnSpec> SERIAL_COLUMNS = List.of(
            ColumnSpec.text("serialNo", "prdSerial.col.serialNo", 140).withSort().withPriority(1),
            ColumnSpec.text("woNo", "prdSerial.col.woNo", 130).withSort().withPriority(1),
            ColumnSpec.text("itemCd", "prdSerial.col.itemCd", 120).withSort(),
            ColumnSpec.text("itemNm", "prdSerial.col.itemNm", 160).withFlex().withPriority(3),
            ColumnSpec.of("partCnt", "prdSerial.col.partCnt", ColumnType.INT, 90),
            ColumnSpec.text("plantCd", "prdSerial.col.plantCd", 80).withPriority(3),
            ColumnSpec.of("createdAt", "prdSerial.col.createdAt", ColumnType.DATETIME, 130).withSort().withPriority(3),
            ColumnSpec.text("createdBy", "prdSerial.col.createdBy", 100).withPriority(3));

    static final List<ColumnSpec> TRACE_COLUMNS = List.of(
            ColumnSpec.text("serialNo", "prdSerial.col.serialNo", 130).withSort().withPriority(1),
            ColumnSpec.labels("state", "prdSerial.col.state", "prdSerial.state.", 80, "ACTIVE", "REMOVED").withPriority(1),
            ColumnSpec.text("woNo", "prdSerial.col.woNo", 120),
            ColumnSpec.text("itemCd", "prdSerial.col.itemCd", 110).withPriority(3),
            ColumnSpec.text("partItemCd", "prdSerial.col.partItemCd", 110),
            ColumnSpec.text("partLotNo", "prdSerial.col.partLotNo", 120),
            ColumnSpec.text("partSerialNo", "prdSerial.col.partSerialNo", 120),
            ColumnSpec.dec("qty", "prdSerial.col.qty", 6, 70).withMinScale(0),
            ColumnSpec.text("unitCd", "prdSerial.col.unitCd", 50).withPriority(3),
            ColumnSpec.of("occurredAt", "prdSerial.col.installedAt", ColumnType.DATETIME, 125).withSort(),
            ColumnSpec.of("removedAt", "prdSerial.col.removedAt", ColumnType.DATETIME, 125).withPriority(3),
            ColumnSpec.text("via", "prdSerial.col.via", 120).withFlex().withPriority(3));

    public List<ColumnSpec> serialColumns() {
        return SERIAL_COLUMNS;
    }

    public List<ColumnSpec> traceColumns() {
        return TRACE_COLUMNS;
    }

    private record Cond(LocalDate from, LocalDate to, String keyword, Long itemId, boolean noItem) {
    }

    private Cond cond(String from, String to, String keyword, String itemCd) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = OrderService.date(from, "from", false, e);
        LocalDate t = OrderService.date(to, "to", false, e);
        if (f != null && t != null && f.isAfter(t)) {
            e.add("to", "INVALID_RANGE");
        }
        String kw = OrderService.text(keyword, "keyword", 60, e);
        e.throwIfAny();
        Long itemId = null;
        if (!isBlank(itemCd)) {
            PartItem i = mapper.findItem(code(itemCd));
            if (i == null) {
                return new Cond(f, t, null, null, true);
            }
            itemId = i.getItemId();
        }
        return new Cond(f, t, kw == null ? null : like(kw), itemId, false);
    }

    @Transactional(readOnly = true)
    public SerialPage grid(String from, String to, String keyword, String itemCd, Integer page, Integer size, List<String> sort,
            Boolean total) {
        Cond c = cond(from, to, keyword, itemCd);
        PageQuery pq = PageQuery.parse(page, size, sort, total, SerialSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("createdAt,desc"));
        if (c.noItem()) {
            return new SerialPage(List.of(), pq.isWithTotal() ? 0L : null);
        }
        String zone = clock.zone().getId();
        return new SerialPage(mapper.findSerialPage(c.from(), c.to(), zone, c.keyword(), c.itemId(), pq),
                pq.isWithTotal() ? mapper.countSerials(c.from(), c.to(), zone, c.keyword(), c.itemId()) : null);
    }

    @Transactional(readOnly = true)
    public long export(SerialExport req, HttpServletResponse response) throws java.io.IOException {
        SerialExport r = req == null ? new SerialExport(null, null, null, null, null, null) : req;
        Cond c = cond(r.from(), r.to(), r.keyword(), r.itemCd());
        List<ColumnSpec> cols = ColumnSpec.selectExportable(SERIAL_COLUMNS, r.columns());
        PageQuery pq = PageQuery.sortOnly(r.sort(), SerialSort.values(), List.of("createdAt,desc"));
        String zone = clock.zone().getId();
        long count = c.noItem() ? 0 : mapper.countSerials(c.from(), c.to(), zone, c.keyword(), c.itemId());
        return excel.export(response, new ExcelExportService.ExportJob(PROG, "prog.prdSerial", cols, count,
                keys(new String[][] {{"from", r.from()}, {"to", r.to()}, {"keyword", r.keyword()}, {"itemCd", r.itemCd()}})), w -> {
                    if (!c.noItem()) {
                        mapper.findSerialPage(c.from(), c.to(), zone, c.keyword(), c.itemId(), pq).forEach(w::accept);
                    }
                });
    }

    /** 시리얼을 만들 수 있는 지시(발행·진행) — 번호 앞부분 */
    @Transactional(readOnly = true)
    public List<SerialWo> wos(String woNo) {
        FieldErrors e = FieldErrors.create();
        String no = OrderService.text(woNo, "woNo", 50, e);
        e.throwIfAny();
        return mapper.findWos(no == null ? null : like(no), WO_LIMIT);
    }

    @Transactional(readOnly = true)
    public SerialDetail detail(long serialId) {
        SerialRow s = mapper.findSerial(serialId);
        if (s == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<PartRow> all = mapper.partsOf(serialId);
        List<PartRow> active = all.stream().filter(p -> "INSTALL".equals(p.getEvType()) && !p.isRemoved()).toList();
        return new SerialDetail(s, active, all);
    }

    @Transactional(readOnly = true)
    public CompareView compare(long serialId) {
        SerialRow s = mapper.findSerial(serialId);
        if (s == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new CompareView(s.getSerialNo(), s.getWoNo(), s.getPlanQty(), compareLines(s.getWoId(), new BigDecimal(s.getPlanQty()),
                mapper.installed(serialId, null)));
    }

    /** 대비 계산(완료판정 경고와 같은 함수) — installed 는 한 시리얼분 */
    List<SerialDtos.CompareLine> compareLines(long woId, BigDecimal planQty, List<SerialDtos.Installed> installed) {
        List<WoBomLine> bom = mapper.woBom(woId);
        List<Long> bomIds = bom.stream().filter(l -> l.getAltGrp() != null).map(WoBomLine::getSrcBomId).distinct().toList();
        return SerialCompare.compare(planQty, bom, bomIds.isEmpty() ? List.of() : mapper.altCands(bomIds), installed);
    }

    // ------------------------------------------------------------------ 등록

    /** 시리얼 count 대 — 채번 + 장비 LOT. 지시를 잠그고 남은 수량 안에서(트리거도 같은 검사). 만든 시리얼 id 들 */
    @Transactional
    public List<Long> create(CreateForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PRD_SERIAL_CREATE");
        if (in == null || in.woId() == null) {
            throw OrderService.fieldError("woId", "REQUIRED");
        }
        int count = in.count() == null ? 1 : in.count();
        FieldErrors e = FieldErrors.create();
        if (count < 1 || count > MAX_CREATE) {
            e.add("count", "RANGE", 1, MAX_CREATE);
        }
        String remark = OrderService.text(in.remark(), "remark", 1000, e);
        e.throwIfAny();
        SerialWo w = mapper.lockWo(in.woId());
        if (w == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"RELEASED".equals(w.getStatus()) && !"IN_PROGRESS".equals(w.getStatus())) {
            throw OrderService.fieldError("woId", "STATE");
        }
        BigDecimal plan = new BigDecimal(w.getPlanQty());
        if (plan.stripTrailingZeros().scale() > 0) {
            throw OrderService.fieldError("woId", "SERIAL_WHOLE_QTY");
        }
        int left = plan.intValueExact() - w.getSerialCnt();
        if (count > left) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("count", "SERIAL_OVER_PLAN", Math.max(left, 0)).list());
        }
        Instant now = clock.instant();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String no = serialNo();
            try {
                long lot = lotMapper.insertLot(new NewLot(no, w.getItemId(), UNIT, BigDecimal.ONE, w.getUnitCd(), null, null, null, now, null),
                        me.loginId());
                ids.add(mapper.insertSerial(no, w.getWoId(), w.getItemId(), lot, remark, me.loginId()));
            } catch (DataIntegrityViolationException ex) {
                throw translate(ex);
            }
        }
        return ids;
    }

    /** 시리얼 번호 = 장비 LOT 번호 — 이미 있는 LOT 번호(다른 경로로 만든 LOT)와 겹치면 다음 번호 */
    private String serialNo() {
        for (int i = 0; i < 100; i++) {
            String no = numbering.nextOrDefault(RULE, "장비 시리얼 번호", "SN{yyMM}-{seq:4}", "MONTH");
            if (!mapper.lotNoExists(no)) {
                return no;
            }
        }
        throw new BusinessException(HbErrorCode.NUMBERING_EXHAUSTED);
    }

    // ------------------------------------------------------------------ 장착·탈거·교체

    @Transactional
    public long install(long serialId, InstallForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PRD_SERIAL_INSTALL");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LockedSerial s = lockFresh(serialId, in.rowVer());
        long partId = installLocked(s, in, me.loginId());
        bump(s, me);
        return partId;
    }

    @Transactional
    public long remove(long serialId, long partId, RemoveForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PRD_SERIAL_REMOVE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LockedSerial s = lockFresh(serialId, in.rowVer());
        long id = removeLocked(s, partId, in.reason(), Boolean.TRUE.equals(in.correct()), in.occurredAt(), me.loginId());
        bump(s, me);
        return id;
    }

    /** 교체 = 탈거 + 새 장착(한 트랜잭션). 새 장착 행 id */
    @Transactional
    public long replace(long serialId, long partId, ReplaceForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PRD_SERIAL_REPLACE");
        if (in == null || in.install() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LockedSerial s = lockFresh(serialId, in.rowVer());
        removeLocked(s, partId, in.reason(), false, in.install().occurredAt(), me.loginId());
        long id = installLocked(s, in.install(), me.loginId());
        bump(s, me);
        return id;
    }

    /** 머리 잠금 → row_ver 확인. 없거나 다른 회사면 404, 남이 먼저 저장했으면 409 */
    private LockedSerial lockFresh(long serialId, Integer rowVer) {
        if (rowVer == null) {
            throw OrderService.fieldError("rowVer", "REQUIRED");
        }
        LockedSerial s = mapper.lockSerial(serialId);
        if (s == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (s.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return s;
    }

    private void bump(LockedSerial s, LoginUser me) {
        if (mapper.bumpSerial(s.getSerialId(), s.getRowVer(), me.loginId()) != 1) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private long installLocked(LockedSerial s, InstallForm in, String by) {
        FieldErrors e = FieldErrors.create();
        PartItem item = null;
        if (isBlank(in.itemCd())) {
            e.add("itemCd", "REQUIRED");
        } else {
            item = mapper.findItem(code(in.itemCd()));
            if (item == null) {
                e.add("itemCd", "NOT_FOUND");
            } else if (!"Y".equals(item.getUseYn())) {
                e.add("itemCd", "INACTIVE");
            }
        }
        BigDecimal qty = OrderService.qty(in.qty(), "qty", 6, true, e);
        if (qty == null && isBlank(in.qty())) {
            e.add("qty", "REQUIRED");
        }
        String sn = null;
        if (!isBlank(in.partSerialNo())) {
            sn = in.partSerialNo().strip();
            if (sn.length() > 60 || !PART_SN.matcher(sn).matches()) {
                e.add("partSerialNo", "FORMAT");
                sn = null;
            } else if (qty != null && qty.compareTo(BigDecimal.ONE) != 0) {
                e.add("qty", "SERIAL_SN_ONE");
            }
        }
        Long lotId = null;
        if (item != null && "Y".equals(item.getUseYn())) {
            if ("Y".equals(item.getLotCtrlYn())) {
                if (isBlank(in.lotNo())) {
                    e.add("lotNo", "SERIAL_LOT_REQUIRED");
                } else {
                    lotId = mapper.findLotId(in.lotNo().strip(), item.getItemId());
                    if (lotId == null) {
                        e.add("lotNo", "NOT_FOUND");
                    }
                }
            } else if (!isBlank(in.lotNo())) {
                e.add("lotNo", "SERIAL_LOT_NOT_ALLOWED");
            }
        }
        Long woProcId = null;
        if (in.opSeq() != null) {
            woProcId = mapper.findWoProc(s.getWoId(), in.opSeq());
            if (woProcId == null) {
                e.add("opSeq", "NOT_FOUND");
            }
        }
        Long workerId = null;
        if (!isBlank(in.workerCd())) {
            workerId = mapper.findWorker(code(in.workerCd()));
            if (workerId == null) {
                e.add("workerCd", "NOT_FOUND");
            }
        }
        String method = isBlank(in.entryMethod()) ? "KEY" : in.entryMethod().strip();
        if (!METHODS.contains(method)) {
            e.add("entryMethod", "CODE");
        }
        Instant at = occurredAt(in.occurredAt(), s.getPlantCd(), e);
        e.throwIfAny();
        // 같은 품목 × 부품 시리얼은 장착 중 한 대에만 — 트리거와 같은 키로 줄 세운 뒤 확인(칸 오류로 먼저, 트리거가 최종)
        if (sn != null) {
            mapper.lockPartSerial(item.getItemId(), sn);
            String other = mapper.activeSerialOf(item.getItemId(), sn);
            if (other != null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE)
                        .withFieldErrors(FieldErrors.create().add("partSerialNo", "SERIAL_PART_SN_USED", other).list());
            }
        }
        Long genId = null;
        if (lotId != null) {
            // 부품 LOT 잠금 → 보류·잔량 확인(칸 이름은 이 화면 것으로) → 계보 투입(트리거가 같은 잠금으로 다시 확인)
            lots.lockForConsume(List.of(lotId));
            LockedLot l = lotMapper.lockLot(lotId);
            if ("Y".equals(l.getHoldYn())) {
                throw OrderService.fieldError("lotNo", "LOT_HOLD");
            }
            if (l.getBalance().compareTo(qty) < 0) {
                throw OrderService.fieldError("qty", "OVER_BALANCE");
            }
            genId = lots.consume(lotId, s.getUnitLotId(), qty, s.getWoId(), woProcId, null, workerId, method, at, by);
        }
        try {
            return mapper.insertPart(s.getSerialId(), "INSTALL", item.getItemId(), lotId, sn, qty, item.getUnitCd(), woProcId, genId, null,
                    at, method, by, workerId, null);
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    private long removeLocked(LockedSerial s, long partId, String reason, boolean correct, String occurredAt, String by) {
        PartRow p = mapper.findPart(partId);
        if (p == null || p.getSerialId() != s.getSerialId()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"INSTALL".equals(p.getEvType())) {
            throw OrderService.fieldError("partId", "NOT_REVERSIBLE");
        }
        if (p.isRemoved()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        String r = OrderService.text(reason, "reason", 500, e);
        if (r == null) {
            e.add("reason", "REQUIRED");
        }
        Instant at = occurredAt(occurredAt, s.getPlantCd(), e);
        if (at != null && at.isBefore(p.getOccurredAt().toInstant().truncatedTo(ChronoUnit.MINUTES))) {
            e.add("occurredAt", "SERIAL_BEFORE_INSTALL");
        }
        e.throwIfAny();
        Long rev = p.getGenId() == null ? null : lots.reverse(p.getGenId(), r, by);
        try {
            return mapper.insertPart(s.getSerialId(), "REMOVE", p.getPartItemId(), p.getPartLotId(), p.getPartSerialNo(),
                    new BigDecimal(p.getQty()).negate(), p.getUnitCd(), p.getWoProcId(), rev, r, at, correct ? "CORRECT" : "KEY", by, null,
                    p.getPartId());
        } catch (DataIntegrityViolationException ex) {
            if ("uq_prd_serial_part_reversal".equals(PgErrors.constraint(ex).orElse(""))) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            throw translate(ex);
        }
    }

    /** 공장 현지 "yyyy-MM-ddTHH:mm" → 시각, 비면 지금. 미래(1분 넘게)·없는 시각(DST)은 칸 오류 */
    private Instant occurredAt(String raw, String plantCd, FieldErrors e) {
        if (isBlank(raw)) {
            return clock.instant();
        }
        String v = raw.strip();
        if (!LOCAL_MIN.matcher(v).matches()) {
            e.add("occurredAt", "FORMAT");
            return null;
        }
        LocalDateTime local;
        try {
            local = LocalDateTime.parse(v);
        } catch (DateTimeParseException ex) {
            e.add("occurredAt", "FORMAT");
            return null;
        }
        Instant at = BizClock.toInstant(local, clock.zone(plantCd));
        if (at == null) {
            e.add("occurredAt", "DST_GAP");
            return null;
        }
        if (at.isAfter(clock.instant().plus(1, ChronoUnit.MINUTES))) {
            e.add("occurredAt", "FUTURE");
            return null;
        }
        return at;
    }

    // ------------------------------------------------------------------ 역추적

    private List<TraceRow> traceRows(String lotNo, String itemCd, String partSerialNo) {
        boolean byLot = !isBlank(lotNo);
        boolean bySn = !isBlank(itemCd) || !isBlank(partSerialNo);
        if (byLot == bySn) {
            throw OrderService.fieldError("lotNo", byLot ? "SERIAL_TRACE_ONE" : "REQUIRED");
        }
        if (byLot) {
            Long lotId = mapper.findLotIdByNo(lotNo.strip());
            if (lotId == null) {
                throw OrderService.fieldError("lotNo", "NOT_FOUND");
            }
            return mapper.traceLot(lotId, policies.intValue("LOT_TRACE_DEPTH"), TRACE_LIMIT + 1);
        }
        FieldErrors e = FieldErrors.create();
        if (isBlank(itemCd)) {
            e.add("itemCd", "REQUIRED");
        }
        if (isBlank(partSerialNo)) {
            e.add("partSerialNo", "REQUIRED");
        }
        e.throwIfAny();
        PartItem item = mapper.findItem(code(itemCd));
        if (item == null) {
            throw OrderService.fieldError("itemCd", "NOT_FOUND");
        }
        return mapper.traceSerial(item.getItemId(), partSerialNo.strip(), TRACE_LIMIT + 1);
    }

    /** 역추적 grid — 줄 상한 안에서 정렬·쪽(서버 쪽 나눔, 상한을 넘으면 truncated) */
    @Transactional(readOnly = true)
    public TracePage trace(String lotNo, String itemCd, String partSerialNo, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, TraceSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("serialNo,asc"));
        List<TraceRow> rows = sorted(traceRows(lotNo, itemCd, partSerialNo), pq);
        boolean truncated = rows.size() > TRACE_LIMIT;
        List<TraceRow> all = truncated ? rows.subList(0, TRACE_LIMIT) : rows;
        int fromIdx = (int) Math.min(pq.getOffset(), all.size());
        int toIdx = (int) Math.min(fromIdx + (long) pq.getSize(), all.size());
        return new TracePage(new ArrayList<>(all.subList(fromIdx, toIdx)), pq.isWithTotal() ? (long) all.size() : null, truncated);
    }

    @Transactional(readOnly = true)
    public long exportTrace(TraceExport req, HttpServletResponse response) throws java.io.IOException {
        TraceExport r = req == null ? new TraceExport(null, null, null, null, null) : req;
        List<ColumnSpec> cols = MesExport.named(ColumnSpec.selectExportable(TRACE_COLUMNS, r.columns()), java.util.Map.of("qty", "qtyNum"));
        PageQuery pq = PageQuery.sortOnly(r.sort(), TraceSort.values(), List.of("serialNo,asc"));
        List<TraceRow> rows = sorted(traceRows(r.lotNo(), r.itemCd(), r.partSerialNo()), pq);
        List<TraceRow> all = rows.size() > TRACE_LIMIT ? rows.subList(0, TRACE_LIMIT) : rows;
        return excel.export(response, new ExcelExportService.ExportJob(TRACE_PROG, "prog.prdSerialTrace", cols, all.size(),
                keys(new String[][] {{"lotNo", r.lotNo()}, {"itemCd", r.itemCd()}, {"partSerialNo", r.partSerialNo()}})),
                w -> all.forEach(w::accept));
    }

    private static List<TraceRow> sorted(List<TraceRow> rows, PageQuery pq) {
        Comparator<TraceRow> cmp = null;
        for (PageQuery.Sort o : pq.getSorts()) {
            Comparator<TraceRow> c = "OCCURRED_AT".equals(o.getKey())
                    ? Comparator.comparing(TraceRow::getOccurredAt)
                    : Comparator.comparing(TraceRow::getSerialNo);
            if (o.isDesc()) {
                c = c.reversed();
            }
            cmp = cmp == null ? c : cmp.thenComparing(c);
        }
        Comparator<TraceRow> last = Comparator.comparingLong(TraceRow::getPartId);
        List<TraceRow> out = new ArrayList<>(rows);
        out.sort(cmp == null ? last : cmp.thenComparing(last));
        return out;
    }

    // ------------------------------------------------------------------ 도우미

    static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "uq_prd_serial_part_sn_active" -> OrderService.fieldError("partSerialNo", "SERIAL_PART_SN_USED");
            case "ck_prd_serial_part_lot_req" -> OrderService.fieldError("lotNo", "SERIAL_LOT_REQUIRED");
            case "fk_prd_serial_part_item_active" -> OrderService.fieldError("itemCd", "INACTIVE");
            case "ck_prd_serial_count" -> OrderService.fieldError("count", "SERIAL_OVER_PLAN");
            case "ck_prd_serial_wo_state" -> OrderService.fieldError("woId", "STATE");
            case "uq_prd_serial_no", "uq_lot_master_no" -> new BusinessException(HbErrorCode.DUPLICATE);
            default -> LotService.translate(e);
        };
    }

    private static List<String> keys(String[][] pairs) {
        List<String> out = new ArrayList<>();
        for (String[] x : pairs) {
            if (x[1] != null && !x[1].isBlank()) {
                out.add(x[0]);
            }
        }
        return out;
    }

    private static String code(String s) {
        return s.strip().toUpperCase(Locale.ROOT);
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
