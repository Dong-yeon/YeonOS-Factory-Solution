package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.InspDtos.ActionForm;
import io.smarthappy.module.qms.InspDtos.CorrectForm;
import io.smarthappy.module.qms.InspDtos.CreateForm;
import io.smarthappy.module.qms.InspDtos.GaugeOption;
import io.smarthappy.module.qms.InspDtos.InspDetail;
import io.smarthappy.module.qms.InspDtos.InspPage;
import io.smarthappy.module.qms.InspDtos.InspRow;
import io.smarthappy.module.qms.InspDtos.InspSort;
import io.smarthappy.module.qms.InspDtos.LineRow;
import io.smarthappy.module.qms.InspDtos.LineValue;
import io.smarthappy.module.qms.InspDtos.LotRef;
import io.smarthappy.module.qms.InspDtos.MeasEntry;
import io.smarthappy.module.qms.InspDtos.MeasForm;
import io.smarthappy.module.qms.InspDtos.MeasRow;
import io.smarthappy.module.qms.InspDtos.MeasSource;
import io.smarthappy.module.qms.InspDtos.MeasValue;
import io.smarthappy.module.qms.InspDtos.PlanLine;
import io.smarthappy.module.qms.InspDtos.VoidMeasForm;
import io.smarthappy.module.qms.InspJudge.LineResult;
import io.smarthappy.module.qms.InspJudge.Meas;
import io.smarthappy.module.qms.InspJudge.Spec;
import io.smarthappy.module.qms.InspJudge.Verdict;
import io.smarthappy.module.qms.QmsDtos.SamplingRow;
import io.smarthappy.module.wms.link.QualityStockLink;
import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.common.text.VisibleText;
import lombok.RequiredArgsConstructor;

/**
 * 검사 실행·측정값 원장 (qms-insp) — P3 QMS 2부 Q6, docs/29_P3 12.2.
 *
 * - 만들기: LOT 번호 + 유형(+ 공정) → 오늘 유효한 확정 검사계획(InspPlanResolver) → 행마다 샘플 수(SamplingPlan.sampleSize(로트 수량))
 * - 측정: 원장 추가만, 정정 = 무효 행. 계측기 클래스가 VISUAL 이 아니면 게이지 필수, GaugeStatus 가 막힌 상태면 거부(1부 D5)
 * - 판정 확정: 모든 행이 OK/NG(InspJudge) 일 때만, 확정 뒤 측정 고정. 무효 = 사유(DELETE 권한)
 * - 잠금 순서: 검사 머리 → 검사 행(측정 트리거와 같게)
 */
@Service
@RequiredArgsConstructor
public class InspService {

    static final String PROG = "qms-insp";
    static final String RULE = "QMS_INSP";
    static final Set<String> TYPES = Set.of("RECEIVE", "PROCESS", "FINAL", "FIRST_OFF", "LAST_OFF", "PATROL");
    static final Set<String> STATUSES = Set.of("OPEN", "JUDGED", "VOID");
    static final Set<String> RESULTS = Set.of("OK", "NG");
    /** WMS 재고 상태를 움직이는 검사 유형(B3 — WMS 와 같은 집합, docs/29_P3 21.3 WC-3) */
    static final Set<String> STOCK_TYPES = Set.of("RECEIVE", "FINAL");
    /** 측정에 쓸 수 없는 게이지 상태 (1부 D5) */
    static final Set<GaugeStatus.Status> BLOCKED = Set.of(GaugeStatus.Status.INACTIVE, GaugeStatus.Status.NO_RECORD, GaugeStatus.Status.NG,
            GaugeStatus.Status.OVERDUE);
    static final int LIST_LIMIT = 500;
    static final List<String> DEFAULT_SORT = List.of("occurredAt,desc");

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("inspNo", "qms.insp.col.inspNo", 120).withSort().withPriority(1),
            ColumnSpec.code("status", "qms.insp.col.status", "QMS_INSP_STATUS", 90).withSort().withPriority(1),
            ColumnSpec.code("result", "qms.insp.col.result", "QMS_INSP_RESULT", 70).withPriority(1),
            ColumnSpec.code("inspType", "qms.insp.col.inspType", "INSP_TYPE", 90).withSort().withPriority(1),
            ColumnSpec.text("lotNo", "qms.insp.col.lotNo", 130).withSort().withPriority(1),
            ColumnSpec.text("itemCd", "qms.insp.col.item", 120).withPriority(2),
            ColumnSpec.text("itemNm", "qms.insp.col.itemNm", 160).withFlex().withMinWidth(100).withPriority(3),
            ColumnSpec.text("procCd", "qms.insp.col.procCd", 80).withPriority(3),
            ColumnSpec.of("occurredAt", "qms.insp.col.occurredAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(2));
    static final int MAX_ENTRIES = 500;
    static final long MAX_LOT = SamplingPlan.MAX_LOT;

    private final InspMapper mapper;
    private final InspPlanMapper planMapper;
    private final SamplingMapper samplingMapper;
    private final SamplingService sampling;
    private final GaugeCalibService gauges;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;
    /** SPC 위반 원장(docs/29_P3 19장) — 판정 확정·무효 때 같은 트랜잭션에서 */
    private final SpcEventService spcEvents;
    /** WMS 품질 상태 연동(B3, docs/29_P3 21장) — 판정·무효 끝에 같은 트랜잭션, 409 는 잡지 않는다(판정·무효가 같이 거부) */
    private final ObjectProvider<QualityStockLink> stockLink;
    /** 판정 확인 문구용 LOT 재고 상태 수량(WB-3) — 읽기만 */
    private final ObjectProvider<StockQuery> stocks;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<InspRow> list(String keyword, String inspType, String status, String result) {
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        String t = VisibleText.isBlank(inspType) ? null : inspType.strip();
        String st = VisibleText.isBlank(status) ? null : status.strip();
        String r = VisibleText.isBlank(result) ? null : result.strip();
        if ((kw != null && kw.length() > 100) || (t != null && !TYPES.contains(t)) || (st != null && !STATUSES.contains(st))
                || (r != null && !RESULTS.contains(r))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findPage(kw == null ? null : "%" + BomService.likeEscape(kw) + "%", t, st, r, LIST_LIMIT);
    }

    /** 그리드(UI 단계 3, 결정 #45) — 목록과 같은 조건·검증 + 쪽·정렬, 기본 = 검사 시각 최근 */
    @Transactional(readOnly = true)
    public InspPage grid(String keyword, String inspType, String status, String result, Integer page, Integer size, List<String> sort,
            Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, InspSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        String t = VisibleText.isBlank(inspType) ? null : inspType.strip();
        String st = VisibleText.isBlank(status) ? null : status.strip();
        String r = VisibleText.isBlank(result) ? null : result.strip();
        if ((kw != null && kw.length() > 100) || (t != null && !TYPES.contains(t)) || (st != null && !STATUSES.contains(st))
                || (r != null && !RESULTS.contains(r))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String like = kw == null ? null : "%" + BomService.likeEscape(kw) + "%";
        return new InspPage(mapper.findGrid(like, t, st, r, pq), pq.isWithTotal() ? mapper.countList(like, t, st, r) : null);
    }

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public InspDetail detail(long id) {
        InspRow h = find(id);
        List<LineRow> lines = mapper.findLines(id);
        List<MeasRow> meas = mapper.findLiveMeas(id);
        List<Verdict> verdicts = preview(lines, meas);
        return new InspDetail(h, lines, meas, InspJudge.head(verdicts).name(), mapper.findOpenNcrNos(id), stockQc(h));
    }

    /** 그 행 계측기 클래스의 사용 중 게이지 + 교정 상태(막힌 게이지는 고를 수 없게 표시) */
    @Transactional(readOnly = true)
    public List<GaugeOption> gaugeOptions(String gaugeClass) {
        if (VisibleText.isBlank(gaugeClass) || gaugeClass.length() > 30) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<GaugeOption> out = mapper.findGauges(gaugeClass.strip());
        for (GaugeOption g : out) {
            GaugeStatus.Status s = gauges.status(g.getAssetId()).status();
            g.setStatus(s.name());
            g.setBlocked(BLOCKED.contains(s));
        }
        return out;
    }

    /** 오늘 유효한 확정 검사계획이 있나 — 만들기(create)와 같은 해석(InspPlanResolver). 현장 단말 due(docs/29_P3 53장 FI-3) */
    boolean hasPlan(String type, long itemId, Long itemClassId, Long procId) {
        List<InspPlanResolver.Candidate> cands = planMapper.findCandidates(type, itemId, itemClassId).stream()
                .map(c -> new InspPlanResolver.Candidate(c.getInspPlanId(), c.getInspType(), c.getItemId(), c.getItemClassId(), c.getProcId(),
                        c.getAltNo(), c.getRev(), c.getStatus(), c.getValidFrom(), c.getValidTo()))
                .toList();
        return new InspPlanResolver(cands).effective(new InspPlanResolver.ItemRef(itemId, itemClassId), type, procId, clock.today()).isPresent();
    }

    // ------------------------------------------------------------------ 만들기

    @Transactional
    public long create(CreateForm in) {
        return create(in, null);
    }

    /**
     * 지난 검사를 그 발생 시각으로 — 데모 이력 시더(QmsDemoFlow) 전용, API 없음. 검증·계획(오늘 유효한 확정 계획)·샘플 수는 {@link #create} 와 같고
     * 발생 시각(occurred_at)만 받는다. 지금보다 뒤면 거부
     */
    @Transactional
    public long createAt(CreateForm in, OffsetDateTime at) {
        if (at == null || at.toInstant().isAfter(clock.instant())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return create(in, at);
    }

    private long create(CreateForm in, OffsetDateTime at) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_INSP_CREATE");
        return createAs(in, at, me.loginId(), MeasSource.KEY);
    }

    /**
     * 검사 만들기 본체 — 사무(사람 로그인)와 현장 단말(docs/29_P3 53장 FI-3)이 같은 경로(검증·오늘 유효한 계획·샘플 수·채번).
     * actor = 기록 행위자(사무 = 로그인 아이디, 단말 = t{id}:{코드}), floor = 단말이면 작업자·단말·요청 id(사무는 KEY — 셋 다 비움)
     */
    long createAs(CreateForm in, OffsetDateTime at, String actor, MeasSource floor) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type = VisibleText.isBlank(in.inspType()) ? null : in.inspType().strip();
        if (type == null) {
            e.add("inspType", "REQUIRED");
        } else if (!TYPES.contains(type)) {
            e.add("inspType", "CODE");
        }
        LotRef lot = null;
        String lotNo = VisibleText.isBlank(in.lotNo()) ? null : in.lotNo().strip();
        if (lotNo == null) {
            e.add("lotNo", "REQUIRED");
        } else if (lotNo.length() > 60 || (lot = mapper.findLot(lotNo)) == null) {
            e.add("lotNo", "NO_SUCH_CODE");
        }
        Long procId = null;
        if (!VisibleText.isBlank(in.procCd())) {
            String cd = in.procCd().strip().toUpperCase(Locale.ROOT);
            procId = cd.length() > 20 ? null : mapper.findProc(cd);
            if (procId == null) {
                e.add("procCd", "NO_SUCH_CODE");
            }
        }
        long lotQty = 0;
        if (in.lotQty() != null) {
            if (in.lotQty() < 1 || in.lotQty() > MAX_LOT) {
                e.add("lotQty", "RANGE", 1, MAX_LOT);
            } else {
                lotQty = in.lotQty();
            }
        } else if (lot != null) {
            lotQty = Math.min(MAX_LOT, Math.max(1, defaultLotQty(lot, type).setScale(0, RoundingMode.CEILING).longValueExact()));
        }
        String remark = VisibleText.optional(in.remark(), "remark", 1000, e);
        e.throwIfAny();
        // 오늘 유효한 확정 검사계획
        LotRef l = lot;
        Long proc = procId;
        List<InspPlanResolver.Candidate> cands = planMapper.findCandidates(type, l.getItemId(), l.getItemClassId()).stream()
                .map(c -> new InspPlanResolver.Candidate(c.getInspPlanId(), c.getInspType(), c.getItemId(), c.getItemClassId(), c.getProcId(),
                        c.getAltNo(), c.getRev(), c.getStatus(), c.getValidFrom(), c.getValidTo()))
                .toList();
        long planId = new InspPlanResolver(cands).effective(new InspPlanResolver.ItemRef(l.getItemId(), l.getItemClassId()), type, proc, clock.today())
                .map(InspPlanResolver.Candidate::inspPlanId)
                .orElseThrow(() -> new BusinessException(HbErrorCode.QMS_INSP_NO_PLAN));
        List<LineValue> lines = new ArrayList<>();
        for (PlanLine pl : mapper.findPlanLines(planId)) {
            SamplingRow sr = samplingMapper.findById(pl.getSamplingPlanId());
            SamplingPlan.Sample s = sampling.plan(sr).sampleSize(lotQty);
            lines.add(new LineValue(pl.getLineNo(), pl.getLineId(), pl.getCharId(), pl.getDataType(), pl.getNominal(), pl.getLsl(), pl.getUsl(),
                    pl.getScale(), pl.getUnitNm(), pl.getGaugeClass(), s.sampleSize(), s.ac(), s.re()));
        }
        if (lines.isEmpty()) {
            throw new BusinessException(HbErrorCode.QMS_INSP_NO_PLAN);
        }
        String no = numbering.nextOrDefault(RULE, "검사 번호", "IN{yyMM}-{seq:5}", "MONTH");
        long id = mapper.insertInsp(no, type, l.getLotId(), l.getItemId(), proc, planId, lotQty, at == null ? clock.now().toOffsetDateTime() : at,
                remark, actor, floor);
        mapper.insertLines(id, lines, actor);
        return id;
    }

    // ------------------------------------------------------------------ 측정

    /** 한 행의 샘플 값 여러 개 — 행 키 = 배열 순번 */
    @Transactional
    public void measure(long id, MeasForm in) {
        if (in == null || in.inspLineId() == null || in.entries() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        OffsetDateTime now = clock.now().toOffsetDateTime();
        record(id, in.inspLineId(), in.entries(), in.entries().stream().map(x -> now).toList(), MeasSource.KEY);
    }

    /**
     * 지난 측정을 잰 시각으로 — 데모 이력 시더(QmsDemoFlow) 전용, API 없음. 검증(샘플 번호·계측기 상태는 그 시각 기준)은 {@link #measure} 와 같고
     * 잰 시각만 받는다(기록 시각 recorded_at 은 지금). 지금보다 뒤면 거부
     */
    @Transactional
    public void measureAt(long id, MeasForm in, OffsetDateTime at) {
        if (in == null || in.inspLineId() == null || in.entries() == null || at == null || at.toInstant().isAfter(clock.instant())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        record(id, in.inspLineId(), in.entries(), in.entries().stream().map(x -> at).toList(), MeasSource.KEY);
    }

    /**
     * 엑셀 올리기 실행(docs/29_P3 25장 XI-3) — 화면 {@link #measure} 와 **같은 경로**(머리 잠금 → 샘플 검증 → INSERT), 출처만 IMPORT + 작업.
     * at = 샘플마다 잰 시각(entries 와 같은 순서). 부르는 쪽(올리기 템플릿)의 트랜잭션 안에서
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void measureImported(long id, long inspLineId, List<MeasEntry> entries, List<OffsetDateTime> at, long jobId) {
        record(id, inspLineId, entries, at, MeasSource.importJob(jobId));
    }

    private void record(long id, long inspLineId, List<MeasEntry> entries, List<OffsetDateTime> at, MeasSource src) {
        recordAs(id, inspLineId, entries, at, src, SamplingService.me().loginId());
    }

    /** 측정 본체 — 사무·엑셀·현장 단말이 같은 경로(머리 잠금 → 샘플 검증 → 원장 INSERT). actor = 기록 행위자 */
    void recordAs(long id, long inspLineId, List<MeasEntry> entries, List<OffsetDateTime> at, MeasSource src, String actor) {
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES || entries.stream().anyMatch(x -> x == null) || at.size() != entries.size()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        openHead(id);
        LineRow line = mapper.findLines(id).stream().filter(x -> x.getInspLineId() == inspLineId).findFirst()
                .orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        Set<Integer> taken = takenSamples(id, inspLineId);
        FieldErrors e = FieldErrors.create();
        List<MeasValue> rows = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        Map<GaugeDay, GaugeStatus.Status> gaugeCache = new java.util.HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            MeasValue v = checkEntry(line, entries.get(i), at.get(i), taken, seen, gaugeCache, e.row(String.valueOf(i)));
            if (v != null) {
                rows.add(v);
            }
        }
        e.throwIfAny();
        mapper.insertMeas(rows, src, actor);
    }

    /** 그 행에 살아 있는 값이 있는 샘플 번호 */
    public Set<Integer> takenSamples(long inspId, long inspLineId) {
        return mapper.findLiveMeas(inspId).stream().filter(m -> m.getInspLineId() == inspLineId)
                .map(MeasRow::getSampleNo).collect(Collectors.toSet());
    }

    /**
     * 샘플 하나 검증 — 화면 입력과 엑셀 올리기(미리보기·실행)가 같이 쓴다(규칙 한 벌, docs/29_P3 25장 XI-3).
     * 오류는 row 에 칸 오류로(sampleNo·value·okYn·gaugeAssetId), 통과하면 넣을 값. seen = 같은 요청 안 샘플 번호(중복 검사용, 채워 감).
     * 계측기 상태는 측정 시각(at) 기준(QA I-1) — 화면은 at = 지금이라 지금 상태와 같다
     */
    public MeasValue checkEntry(LineRow line, MeasEntry m, OffsetDateTime at, Set<Integer> taken, Set<Integer> seen,
            Map<GaugeDay, GaugeStatus.Status> gaugeCache, FieldErrors.RowScope row) {
        boolean variable = "VARIABLE".equals(line.getDataType());
        boolean visual = "VISUAL".equals(line.getGaugeClass());
        Integer no = m.sampleNo();
        if (no == null || no < 1 || no > line.getSampleSize()) {
            row.add("sampleNo", "RANGE", 1, line.getSampleSize());
            return null;
        }
        if (!seen.add(no) || taken.contains(no)) {
            row.add("sampleNo", "SAMPLE_TAKEN");
            return null;
        }
        BigDecimal v = null;
        String ok = null;
        if (variable) {
            if (m.value() == null) {
                row.add("value", "REQUIRED");
                return null;
            }
            if (m.value().scale() > 6 || m.value().abs().compareTo(new BigDecimal("999999999999")) > 0) {
                row.add("value", "FORMAT");
                return null;
            }
            v = m.value();
        } else {
            ok = VisibleText.isBlank(m.okYn()) ? null : m.okYn().strip();
            if (!"Y".equals(ok) && !"N".equals(ok)) {
                row.add("okYn", ok == null ? "REQUIRED" : "CODE");
                return null;
            }
        }
        Long gauge = m.gaugeAssetId();
        if (gauge == null && !visual) {
            row.add("gaugeAssetId", "REQUIRED");
            return null;
        }
        if (gauge != null) {
            String cls = mapper.gaugeClassOf(gauge);
            if (cls == null) {
                row.add("gaugeAssetId", "NOT_FOUND");
                return null;
            }
            if (!cls.equals(line.getGaugeClass())) {
                row.add("gaugeAssetId", "GAUGE_CLASS");
                return null;
            }
            GaugeStatus.Status s = gaugeCache.computeIfAbsent(new GaugeDay(gauge, at.atZoneSameInstant(clock.zone()).toLocalDate()),
                    k -> gaugeStatus(k.assetId(), at));
            if (s == null) {
                row.add("gaugeAssetId", "NOT_FOUND");
                return null;
            }
            if (BLOCKED.contains(s)) {
                row.add("gaugeAssetId", "GAUGE_BLOCKED");
                return null;
            }
        }
        return new MeasValue(line.getInspLineId(), no, v, ok, gauge, at);
    }

    /** 측정값 무효(정정) — 다시 잴 수 있게 */
    @Transactional
    public void voidMeas(long id, VoidMeasForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.measId() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        openHead(id);
        if (mapper.insertVoid(id, in.measId(), clock.now().toOffsetDateTime(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    /**
     * 정정 한 번에(docs/72 3.3) — 그 샘플의 살아 있는 값을 무효 행으로 막고 같은 샘플 번호로 새 값. 앞 기록은 원장에 그대로 남는다
     * (무효 행 + 새 값 행이 쌓임). 검증은 {@link #checkEntry} 그대로(게이지·자릿수), 잠금 순서 머리 → 행
     */
    @Transactional
    public void correct(long id, CorrectForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.measId() == null || in.entry() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        openHead(id);
        MeasRow old = mapper.findLiveMeas(id).stream().filter(m -> m.getMeasId() == in.measId()).findFirst()
                .orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        LineRow line = mapper.findLines(id).stream().filter(x -> x.getInspLineId() == old.getInspLineId()).findFirst()
                .orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        OffsetDateTime now = clock.now().toOffsetDateTime();
        Set<Integer> taken = new HashSet<>(takenSamples(id, old.getInspLineId()));
        taken.remove(old.getSampleNo());
        FieldErrors e = FieldErrors.create();
        MeasEntry entry = new MeasEntry(old.getSampleNo(), in.entry().value(), in.entry().okYn(), in.entry().gaugeAssetId());
        MeasValue v = checkEntry(line, entry, now, taken, new HashSet<>(), new java.util.HashMap<>(), e.row("0"));
        e.throwIfAny();
        if (mapper.insertVoid(id, old.getMeasId(), now, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.insertMeas(List.of(v), MeasSource.KEY, me.loginId());
    }

    // ------------------------------------------------------------------ 판정 · 무효

    @Transactional
    public void judge(long id, ActionForm in) {
        LoginUser me = SamplingService.me();
        judgeAs(id, ver(in), me.loginId(), null, null);
    }

    /**
     * 판정 확정 본체 — 사무 [판정 확정]과 현장 단말 자동 확정·불합격 확정(docs/29_P3 53장 FI-4)이 같은 경로(후속 SPC·재고 그대로).
     *
     * @param ver      사무 = 화면이 본 rowVer, 단말 = null(지금 잠근 행 그대로 — 같은 트랜잭션에서 방금 잰 값으로 판정)
     * @param workerId 단말 = 확정한 작업자(사무 null)
     * @param expect   단말 불합격 확정 = "NG"(미리보기가 NG 일 때만), 그 밖 null
     * @return 확정 결과 OK·NG
     */
    String judgeAs(long id, Integer ver, String actor, Long workerId, String expect) {
        InspRow h = mapper.lockInsp(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"OPEN".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_INSP_STATE);
        }
        if (ver != null && h.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        List<LineRow> lines = mapper.findLines(id);
        List<Verdict> verdicts = preview(lines, mapper.findLiveMeas(id));
        FieldErrors e = FieldErrors.create();
        for (LineRow l : lines) {
            if (Verdict.OPEN.name().equals(l.getVerdict())) {
                e.row(String.valueOf(l.getInspLineId())).add("result", "INSP_LINE_OPEN");
            }
        }
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.QMS_INSP_INCOMPLETE).withFieldErrors(e.list());
        }
        String head = InspJudge.head(verdicts).name();
        if (expect != null && !expect.equals(head)) {
            throw new BusinessException(HbErrorCode.QMS_INSP_STATE);
        }
        for (LineRow l : lines) {
            mapper.setLineResult(l.getInspLineId(), l.getVerdict());
        }
        if (mapper.judge(id, head, h.getRowVer(), actor, workerId) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        spcEvents.onJudged(id, actor);
        // WMS 가 읽는 qms_insp 판정이 갱신된 뒤 마지막(WC-4). 잠금 순서 qms_insp → lot_master(공유) → 재고
        QualityStockLink.of(stockLink).onInspJudged(id);
        return head;
    }

    @Transactional
    public void voidInsp(long id, ActionForm in) {
        LoginUser me = SamplingService.me();
        int ver = ver(in);
        FieldErrors e = FieldErrors.create();
        String reason = VisibleText.reason(in.reason(), "reason", 1, 500, e);
        e.throwIfAny();
        InspRow h = mapper.lockInsp(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if ("VOID".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_INSP_STATE);
        }
        // 근거 NCR 이 처리 중이어도 막지 않는다(사용자 QQ-1) — 화면은 확인을 받고, 이력(무효 사유 끝)에 그 번호를 남긴다.
        // 그 NCR 불량은 계약 SupplierQualityQuery 에서 LOT 기준으로 계속 센다
        List<String> open = mapper.findOpenNcrNos(id);
        if (!open.isEmpty()) {
            reason = withOpenNcrs(reason, open);
        }
        if (mapper.voidInsp(id, reason, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        spcEvents.onVoided(id, h.getInspNo(), me.loginId());
        QualityStockLink.of(stockLink).onInspVoided(id);
    }

    // ------------------------------------------------------------------ 안쪽

    /**
     * 로트 수량을 비우면: 재고를 움직이는 유형(수입·출하)이고 WMS 연동 공장에 그 LOT 의 검사 대기(QC) 재고가 있으면 그 수량
     * (판정된 OK LOT 에 더 들어온 몫을 재검사할 때 표본이 그 몫을 대표하게 — docs/30 12.15.13 B-2), 아니면 LOT 생성 수량.
     * LOT 단위가 품목 기본 단위와 다르면 재고 수량과 단위가 달라 생성 수량 그대로
     */
    private BigDecimal defaultLotQty(LotRef lot, String type) {
        if (type != null && STOCK_TYPES.contains(type) && lot.isBaseUnit()) {
            BigDecimal qc = StockQuery.of(stocks).lotStatusQty(lot.getLotId()).get("QC");
            if (qc != null && qc.signum() > 0) {
                return qc;
            }
        }
        return lot.getQtyCreated();
    }

    /** 판정이 재고를 움직이는 유형(수입·출하, WC-3)만 — 그 밖·WMS 없음·정책 N·비관리 품목은 빈 맵(확인 문구 없음) */
    private Map<String, BigDecimal> stockQc(InspRow h) {
        return STOCK_TYPES.contains(h.getInspType()) ? StockQuery.of(stocks).lotStatusQty(h.getLotId()) : Map.of();
    }

    /** 무효 사유 끝에 처리 중 NCR 번호 — 칸 길이(500) 안에서, 넘치면 앞 번호만 + 개수 */
    static String withOpenNcrs(String reason, List<String> ncrNos) {
        String tail = " [OPEN NCR " + String.join(",", ncrNos) + "]";
        if (reason.length() + tail.length() > 500) {
            tail = " [OPEN NCR " + ncrNos.get(0) + (ncrNos.size() > 1 ? " +" + (ncrNos.size() - 1) : "") + "]";
        }
        return reason.length() + tail.length() > 500 ? reason.substring(0, 500 - tail.length()) + tail : reason + tail;
    }

    /** 행마다 살아 있는 값으로 판정해 행 DTO 에 채우고 판정 목록을 돌려준다 */
    static List<Verdict> preview(List<LineRow> lines, List<MeasRow> meas) {
        Map<Long, List<MeasRow>> byLine = meas.stream().collect(Collectors.groupingBy(MeasRow::getInspLineId));
        List<Verdict> out = new ArrayList<>();
        for (LineRow l : lines) {
            Spec spec = new Spec(l.getDataType(), l.getLsl(), l.getUsl(), l.getSampleSize(), l.getAc(), l.getRe());
            List<Meas> live = byLine.getOrDefault(l.getInspLineId(), List.of()).stream()
                    .map(m -> new Meas(m.getValue(), m.getOkYn() == null ? null : "Y".equals(m.getOkYn()))).toList();
            LineResult r = InspJudge.line(spec, live);
            l.setMeasured(r.measured());
            l.setNonconforming(r.nonconforming());
            l.setVerdict(r.verdict().name());
            out.add(r.verdict());
        }
        return out;
    }

    /** checkEntry 계측기 판정 캐시 키 — 판정은 날짜 단위라 같은 계측기·같은 날(회사 시간대)이면 한 번 */
    public record GaugeDay(long assetId, java.time.LocalDate day) {
    }

    private GaugeStatus.Status gaugeStatus(long assetId, OffsetDateTime at) {
        try {
            return gauges.statusAt(assetId, at);
        } catch (BusinessException ex) {
            return null;
        }
    }

    /** 검사 머리를 잠그고 열려 있는지 — 측정 트리거와 같은 순서(머리 → 행) */
    private void openHead(long id) {
        InspRow h = mapper.lockInsp(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"OPEN".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_INSP_STATE);
        }
    }

    private InspRow find(long id) {
        InspRow h = mapper.findById(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return h;
    }

    private static int ver(ActionForm in) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return in.rowVer();
    }
}
