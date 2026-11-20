package io.smarthappy.module.qms;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.QmsDtos.ActionRequest;
import io.smarthappy.module.qms.QmsDtos.CharChoice;
import io.smarthappy.module.qms.QmsDtos.DocChoice;
import io.smarthappy.module.qms.QmsDtos.EffectiveResult;
import io.smarthappy.module.qms.QmsDtos.HeaderForm;
import io.smarthappy.module.qms.QmsDtos.HeaderValue;
import io.smarthappy.module.qms.QmsDtos.ItemLookup;
import io.smarthappy.module.qms.QmsDtos.LineItem;
import io.smarthappy.module.qms.QmsDtos.LineRow;
import io.smarthappy.module.qms.QmsDtos.LineValue;
import io.smarthappy.module.qms.QmsDtos.LinkDocForm;
import io.smarthappy.module.qms.QmsDtos.LinesForm;
import io.smarthappy.module.qms.QmsDtos.PlanDetail;
import io.smarthappy.module.qms.QmsDtos.PlanExportRequest;
import io.smarthappy.module.qms.QmsDtos.PlanRow;
import io.smarthappy.module.qms.QmsDtos.PlanSearch;
import io.smarthappy.module.qms.QmsDtos.PlanSort;
import io.smarthappy.module.qms.QmsDtos.SamplingRow;
import io.smarthappy.module.qms.QmsDtos.SaveResult;
import io.smarthappy.module.qms.QmsDtos.TargetLookup;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import io.smarthappy.platform.common.text.VisibleText;
import lombok.RequiredArgsConstructor;

/**
 * 검사계획 관리 (qms-insp-plan) — P3 QMS Q1, docs/29 (D1·D2).
 *
 * - 머리 = 검사 유형 × (품목 | 품목 클래스) × 공정(선택) × 대체 × 리비전. 상태·기간·자동 마감·새 리비전·자동 리비전은 라우팅(RoutingService)과
 *   같은 규칙 — 공통 헬퍼로 빼지 않고 따로 둔다(P2 병행 중 BOM·라우팅 코드를 건드리지 않음, docs/29 6장)
 * - 행은 "한 벌" 저장(라우팅 단계와 같음): 화면이 보낸 목록과 DB 를 id 로 맞춰 추가·수정·삭제, 바뀐 행만 UPDATE
 * - 특성은 계획 대상과 맞아야 한다: 제품 특성 = 계획 품목, 공정 특성 = 계획 공정과 같거나 계획에 공정 없음(클래스 계획은 공정 특성만)
 * - 확정 때 특성 규격을 행에 스냅샷 — 머리 트리거가 한다(어떤 경로로 확정해도). 확정 뒤 특성 규격이 바뀌면 "새 리비전 필요" 표시
 * - 사용 중지 특성·샘플링 계획·계측기 클래스는 새 참조만 막는다(이미 든 행은 저장되고, 확정 검사가 알린다)
 */
@Service
@RequiredArgsConstructor
public class InspPlanService {

    static final String PROG = "qms-insp-plan";
    static final Pattern ROW_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    static final Set<String> TYPES = Set.of("RECEIVE", "PROCESS", "FINAL", "FIRST_OFF", "LAST_OFF", "PATROL");
    /** 계획 행 증빙(docs/72 3.2) */
    static final Set<String> EVIDENCES = Set.of("NONE", "PHOTO");
    static final Set<String> STATUSES = Set.of("DRAFT", "RELEASED", "OBSOLETE");
    static final Set<String> FREQS = Set.of("EVERY_LOT", "START", "END", "EVERY_N", "EVERY_HOURS");
    static final List<String> DEFAULT_SORT = List.of("targetCd,asc", "inspType,asc", "rev,desc");
    static final int MAX_LINES = 200;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.code("inspType", "qms.plan.col.inspType", "INSP_TYPE", 90).withSort().withPriority(1),
            ColumnSpec.text("targetCd", "qms.plan.col.targetCd", 140).withSort().withPriority(1),
            ColumnSpec.text("targetNm", "qms.plan.col.targetNm", 180).withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.text("procCd", "qms.plan.col.procCd", 80).withSort().withPriority(2),
            ColumnSpec.of("altNo", "qms.plan.col.altNo", ColumnSpec.ColumnType.INT, 50).withPriority(3),
            ColumnSpec.text("rev", "qms.plan.col.rev", 60).withSort().withPriority(1),
            ColumnSpec.code("status", "qms.plan.col.status", "BOM_STATUS", 70).withSort().withPriority(1),
            ColumnSpec.of("validFrom", "qms.plan.col.validFrom", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(2),
            ColumnSpec.of("validTo", "qms.plan.col.validTo", ColumnSpec.ColumnType.DATE, 100).withPriority(3),
            ColumnSpec.of("lineCnt", "qms.plan.col.lineCnt", ColumnSpec.ColumnType.INT, 60).withPriority(2),
            ColumnSpec.of("specChangedCnt", "qms.plan.col.specChangedCnt", ColumnSpec.ColumnType.INT, 80).withPriority(2),
            ColumnSpec.text("changeNo", "qms.plan.col.changeNo", 100).withPriority(3),
            ColumnSpec.of("releasedAt", "qms.plan.col.releasedAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(3),
            ColumnSpec.of("updatedAt", "qms.plan.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));

    private final InspPlanMapper mapper;
    private final SamplingMapper samplings;
    private final CodeService codes;
    private final PolicyService policies;
    private final InboxService inbox;
    private final ExcelExportService excel;
    private final BizClock clock;

    public record Page(List<PlanRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String inspType, String target, String procCd, String status, Boolean withObsolete, Boolean specChangedOnly,
            Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, PlanSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                DEFAULT_SORT);
        PlanSearch q = search(inspType, target, procCd, status, withObsolete, specChangedOnly, pq);
        return new Page(mapper.findPage(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public PlanDetail detail(long planId) {
        return new PlanDetail(head(planId), lines(planId));
    }

    private List<LineRow> lines(long planId) {
        List<LineRow> lines = mapper.findLines(planId);
        lines.forEach(l -> {
            l.setSpecText(spec(l.getDataType(), l.getLsl(), l.getUsl(), l.getUnitNm(), l.getScale()));
            l.setCurrentSpecText(spec(l.getCurDataType(), l.getCurLsl(), l.getCurUsl(), l.getCurUnitNm(), l.getCurScale()));
        });
        return lines;
    }

    @Transactional(readOnly = true)
    public ItemLookup item(String itemCd) {
        ItemLookup it = VisibleText.isBlank(itemCd) || itemCd.length() > 40 ? null
                : mapper.findItemByCd(itemCd.strip().toUpperCase(Locale.ROOT));
        if (it == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return it;
    }

    @Transactional(readOnly = true)
    public List<TargetLookup> classes() {
        return mapper.findClasses();
    }

    @Transactional(readOnly = true)
    public List<TargetLookup> procs() {
        return mapper.findProcs();
    }

    /** 행 샘플링 선택지 — 사용 중 계획 (샘플링 화면 권한 없이도 검사계획은 만든다) */
    @Transactional(readOnly = true)
    public List<SamplingRow> samplingChoices() {
        return samplings.findActive();
    }

    /** 이 계획에 넣을 수 있는 특성 */
    @Transactional(readOnly = true)
    public List<CharChoice> charChoices(long planId) {
        PlanRow p = head(planId);
        List<CharChoice> list = mapper.findCharChoices(p.getItemId(), p.getProcId());
        list.forEach(c -> c.setSpecText(spec(c.getDataType(), c.getLsl(), c.getUsl(), c.getUnitNm(), c.getScale())));
        return list;
    }

    /** 기준일에 유효한 확정 검사계획 (InspPlanResolver — 2부가 같은 함수를 부른다) */
    @Transactional(readOnly = true)
    public EffectiveResult effective(String itemCd, String inspType, String procCd, String date) {
        FieldErrors e = FieldErrors.create();
        if (VisibleText.isBlank(inspType) || !TYPES.contains(inspType)) {
            e.add("inspType", "CODE");
        }
        LocalDate at = VisibleText.isBlank(date) ? clock.today() : BomService.date(date, "date", e);
        e.throwIfAny();
        ItemLookup it = item(itemCd);
        Long procId = null;
        if (!VisibleText.isBlank(procCd)) {
            TargetLookup p = procCd.length() > 20 ? null : mapper.findProcByCd(procCd.strip().toUpperCase(Locale.ROOT));
            if (p == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            procId = p.getId();
        }
        List<InspPlanResolver.Candidate> cands = mapper.findCandidates(inspType, it.getItemId(), it.getItemClassId()).stream()
                .map(c -> new InspPlanResolver.Candidate(c.getInspPlanId(), c.getInspType(), c.getItemId(), c.getItemClassId(),
                        c.getProcId(), c.getAltNo(), c.getRev(), c.getStatus(), c.getValidFrom(), c.getValidTo()))
                .toList();
        return new InspPlanResolver(cands).effective(new InspPlanResolver.ItemRef(it.getItemId(), it.getItemClassId()), inspType,
                procId, at)
                .map(c -> new EffectiveResult(c.inspPlanId(), c.rev(), c.itemId() != null ? "ITEM" : "CLASS", c.procId()))
                .orElse(new EffectiveResult(null, null, null, null));
    }

    // ------------------------------------------------------------------ 머리

    /** 새 검사계획(작성 중). 리비전을 비우면 다음 자동 리비전, 시작일을 비우면 오늘 */
    @Transactional
    public long create(HeaderForm form) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_INSP_PLAN_CREATE");
        HeaderValue v = validateHeader(form, null);
        if (mapper.findDraftId(v) != null) {
            throw new BusinessException(HbErrorCode.QMS_PLAN_DRAFT_EXISTS);
        }
        try {
            return mapper.insertHeader(v, me.loginId());
        } catch (DuplicateKeyException e) {
            if (VisibleText.isBlank(form.rev()) || PgErrors.constraint(e).filter("uq_qms_insp_plan_draft"::equals).isPresent()) {
                // 같은 대상을 동시에 신규 — 사용자가 비운 리비전의 충돌이 아니다 (BOM QA 3a #7 과 같은 경우)
                throw new BusinessException(HbErrorCode.QMS_PLAN_DRAFT_EXISTS);
            }
            throw SamplingService.fieldError("rev", "DUPLICATE");
        }
    }

    /** 머리 수정 — 작성 중만. 대상 키(유형·품목|클래스·공정·대체)는 바꾸지 않는다 */
    @Transactional
    public void update(long planId, HeaderForm form) {
        LoginUser me = SamplingService.me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PlanRow cur = draft(planId);
        HeaderValue v = validateHeader(form, cur);
        try {
            if (mapper.updateHeader(planId, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException e) {
            throw SamplingService.fieldError("rev", "DUPLICATE");
        }
    }

    // ------------------------------------------------------------------ 행 (한 벌)

    @Transactional
    public SaveResult saveLines(long planId, int headRowVer, LinesForm form) {
        LoginUser me = SamplingService.me();
        List<LineItem> items = form == null || form.lines() == null ? List.of() : form.lines();
        if (items.size() > MAX_LINES || items.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PlanRow head = draft(planId);
        if (mapper.touch(planId, headRowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        Map<Long, LineRow> existing = new HashMap<>();
        mapper.findLines(planId).forEach(l -> existing.put(l.getLineId(), l));

        FieldErrors errors = FieldErrors.create();
        Set<String> keys = new HashSet<>();
        Set<Long> seen = new HashSet<>();
        Map<Long, CharChoice> chars = new HashMap<>();
        Map<String, SamplingRow> plans = new HashMap<>();
        Set<String> gauges = codes.codes("GAUGE_CLASS", Locale.KOREAN).stream().map(CodeService.CodeItem::code)
                .collect(Collectors.toSet());
        List<LineValue> values = new ArrayList<>();
        for (LineItem in : items) {
            if (in.rowKey() == null || !ROW_KEY.matcher(in.rowKey()).matches() || !keys.add(in.rowKey())) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            if (in.lineId() != null && (!existing.containsKey(in.lineId()) || !seen.add(in.lineId()))) {
                // 다른 계획의 행 id, 또는 같은 행을 두 번 — 화면이 만들 수 없는 요청
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            LineValue v = validateLine(in, head, in.lineId() == null ? null : existing.get(in.lineId()), chars, plans, gauges,
                    errors.row(in.rowKey()));
            if (v != null) {
                values.add(v);
            }
        }
        Map<Integer, Long> nos = values.stream().collect(Collectors.groupingBy(LineValue::lineNo, Collectors.counting()));
        Map<Long, Long> charCnt = values.stream().collect(Collectors.groupingBy(LineValue::charId, Collectors.counting()));
        values.stream().filter(v -> nos.get(v.lineNo()) > 1).forEach(v -> errors.row(v.rowKey()).add("lineNo", "DUPLICATE"));
        values.stream().filter(v -> charCnt.get(v.charId()) > 1).forEach(v -> errors.row(v.rowKey()).add("charId", "DUPLICATE"));
        errors.throwIfAny();

        int changes = 0;
        try {
            for (LineRow old : existing.values()) {
                if (!seen.contains(old.getLineId())) {
                    changes += mapper.deleteLine(planId, old.getLineId());
                }
            }
            for (LineValue v : values) {
                if (v.lineId() == null) {
                    mapper.insertLine(planId, v, me.loginId());
                    changes++;
                } else if (changed(existing.get(v.lineId()), v)) {
                    mapper.updateLine(planId, v, me.loginId());
                    changes++;
                }
            }
        } catch (DataIntegrityViolationException e) {
            // 서비스 검사 뒤 다른 트랜잭션이 특성·샘플링 계획·코드를 사용 중지한 경우 — 트리거가 막는다
            String c = PgErrors.constraint(e).orElse("");
            switch (c) {
                case "ck_qms_insp_plan_line_char_active" -> throw SamplingService.fieldError("charId", "INACTIVE");
                case "ck_qms_insp_plan_line_sampling_active" -> throw SamplingService.fieldError("samplingPlanCd", "INACTIVE");
                case "fk_qms_insp_plan_line_gauge_class" -> throw SamplingService.fieldError("gaugeClass", "CODE");
                default -> throw e;
            }
        }
        return new SaveResult(changes);
    }

    private LineValue validateLine(LineItem in, PlanRow head, LineRow cur, Map<Long, CharChoice> chars,
            Map<String, SamplingRow> plans, Set<String> gauges, FieldErrors.RowScope row) {
        FieldErrors e = FieldErrors.create();
        if (in.lineNo() == null) {
            e.add("lineNo", "REQUIRED");
        } else if (in.lineNo() < 1 || in.lineNo() > 9999) {
            e.add("lineNo", "RANGE", 1, 9999);
        }
        CharChoice ch = null;
        if (in.charId() == null) {
            e.add("charId", "REQUIRED");
        } else {
            ch = chars.computeIfAbsent(in.charId(), mapper::findChar);
            boolean fresh = cur == null || cur.getCharId() != in.charId();
            if (ch == null) {
                e.add("charId", "NOT_FOUND");
            } else if (!fits(ch, head)) {
                e.add("charId", "CHAR_TARGET");
            } else if (fresh && !"Y".equals(ch.getUseYn())) {
                // 새로 고른 특성만 사용 중 검사 — 이미 든 행은 확정 검사가 본다
                e.add("charId", "INACTIVE");
            }
        }
        String gauge = VisibleText.isBlank(in.gaugeClass()) ? null : in.gaugeClass().strip().toUpperCase(Locale.ROOT);
        if (gauge == null) {
            e.add("gaugeClass", "REQUIRED");
        } else if (!gauges.contains(gauge) && (cur == null || !gauge.equals(cur.getGaugeClass()))) {
            e.add("gaugeClass", "CODE");
        }
        SamplingRow sp = null;
        String spCd = VisibleText.isBlank(in.samplingPlanCd()) ? null : in.samplingPlanCd().strip().toUpperCase(Locale.ROOT);
        if (spCd == null) {
            e.add("samplingPlanCd", "REQUIRED");
        } else {
            sp = spCd.length() > 20 ? null : plans.computeIfAbsent(spCd, samplings::findByCd);
            if (sp == null) {
                e.add("samplingPlanCd", "NOT_FOUND");
            } else if ((cur == null || cur.getSamplingPlanId() != sp.getSamplingPlanId()) && !"Y".equals(sp.getUseYn())) {
                e.add("samplingPlanCd", "INACTIVE");
            }
        }
        String freq = VisibleText.isBlank(in.freqType()) ? "EVERY_LOT" : in.freqType().strip();
        Integer freqN = null;
        if (!FREQS.contains(freq)) {
            e.add("freqType", "CODE");
        } else if ("EVERY_N".equals(freq) || "EVERY_HOURS".equals(freq)) {
            int max = "EVERY_N".equals(freq) ? 1_000_000 : 168;
            freqN = in.freqN();
            if (freqN == null) {
                e.add("freqN", "REQUIRED");
            } else if (freqN < 1 || freqN > max) {
                e.add("freqN", "RANGE", 1, max);
            }
        }
        String method = VisibleText.optional(in.methodNm(), "methodNm", 200, e);
        String reaction = VisibleText.optional(in.reactionPlan(), "reactionPlan", 500, e);
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        String grp = VisibleText.optional(in.grpNm(), "grpNm", 60, e);
        String evidence = VisibleText.isBlank(in.evidenceReq()) ? "NONE" : in.evidenceReq().strip().toUpperCase(Locale.ROOT);
        if (!EVIDENCES.contains(evidence)) {
            e.add("evidenceReq", "CODE");
        }
        e.list().forEach(fe -> row.add(fe.field(), fe.code(), fe.args()));
        if (!e.isEmpty()) {
            return null;
        }
        return new LineValue(in.rowKey(), in.lineId(), in.lineNo(), ch.getCharId(), method, gauge, sp.getSamplingPlanId(), freq,
                freqN, reaction, remark, grp, evidence);
    }

    /** 특성이 계획 대상과 맞는지 — DB 트리거 ck_qms_insp_plan_line_char_target 와 같은 규칙 */
    static boolean fits(CharChoice ch, PlanRow head) {
        if (ch.getItemId() != null) {
            return ch.getItemId().equals(head.getItemId());
        }
        return head.getProcId() == null || head.getProcId().equals(ch.getProcId());
    }

    private static boolean changed(LineRow o, LineValue v) {
        return o.getLineNo() != v.lineNo() || o.getCharId() != v.charId() || !Objects.equals(o.getMethodNm(), v.methodNm())
                || !Objects.equals(o.getGaugeClass(), v.gaugeClass()) || o.getSamplingPlanId() != v.samplingPlanId()
                || !Objects.equals(o.getFreqType(), v.freqType()) || !Objects.equals(o.getFreqN(), v.freqN())
                || !Objects.equals(o.getReactionPlan(), v.reactionPlan()) || !Objects.equals(o.getRemark(), v.remark())
                || !Objects.equals(o.getGrpNm(), v.grpNm()) || !Objects.equals(o.getEvidenceReq(), v.evidenceReq());
    }

    // ------------------------------------------------------------------ 상태

    /** 확정 — 특성 규격 스냅샷(트리거) + 이전 확정본 종료일 자동 마감 */
    @Transactional
    public void release(long planId, ActionRequest req) {
        LoginUser me = SamplingService.me();
        int ver = rowVer(req);
        PlanRow head = draft(planId);
        if (head.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        List<LineRow> lines = mapper.findLines(planId);
        Set<String> gauges = codes.codes("GAUGE_CLASS", Locale.KOREAN).stream().map(CodeService.CodeItem::code)
                .collect(Collectors.toSet());
        FieldErrors errors = FieldErrors.create();
        if (head.getValidFrom().isBefore(clock.today())) {
            errors.add("validFrom", "PAST");
        }
        if (head.getItemId() != null && !"Y".equals(head.getItemUseYn())) {
            errors.add("itemCd", "INACTIVE");
        }
        if (head.getItemClassId() != null && !"Y".equals(head.getClassUseYn())) {
            errors.add("classCd", "INACTIVE");
        }
        if (head.getProcId() != null && !"Y".equals(head.getProcUseYn())) {
            errors.add("procCd", "INACTIVE");
        }
        if (lines.isEmpty()) {
            errors.add("lines", "NO_LINES");
        }
        for (LineRow l : lines) {
            FieldErrors.RowScope row = errors.row(lineKey(l));
            if (!"Y".equals(l.getCharUseYn())) {
                row.add("charId", "INACTIVE");
            }
            if (!"Y".equals(l.getSamplingUseYn())) {
                row.add("samplingPlanCd", "INACTIVE");
            }
            if (!gauges.contains(l.getGaugeClass())) {
                row.add("gaugeClass", "CODE");
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(HbErrorCode.QMS_PLAN_RELEASE_CHECK).withFieldErrors(errors.list());
        }
        HeaderValue key = key(head);
        LocalDate from = head.getValidFrom();
        for (PlanRow prev : mapper.findReleasedOverlaps(key, from, planId)) {
            if (!prev.getValidFrom().isBefore(from)) {
                throw new BusinessException(HbErrorCode.QMS_PLAN_PERIOD_OVERLAP, prev.getRev());
            }
            mapper.closeValidTo(prev.getInspPlanId(), from, me.loginId());
        }
        if (mapper.release(planId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    @Transactional
    public void obsolete(long planId, ActionRequest req) {
        LoginUser me = SamplingService.me();
        int ver = rowVer(req);
        PlanRow head = head(planId);
        if ("OBSOLETE".equals(head.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_PLAN_NOT_DRAFT);
        }
        if (mapper.obsolete(planId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /**
     * 새 리비전 — 원본(어느 상태든) 행을 복사한 작성 중 계획. 스냅샷은 복사하지 않는다(작성 중은 지금 특성을 따라감 — 규격이 바뀐 특성은
     * 새 리비전에서 새 규격으로 보이고 확정 때 다시 스냅샷). 사용 중지 특성·샘플링도 복사(같은 대상 키의 다른 리비전이 쓰던 참조 — 트리거 허용,
     * 확정 검사가 알린다). 시작일을 비우면 오늘(원본 확정본이 오늘 이후 시작이면 그다음 날 — 라우팅과 같음)
     */
    @Transactional
    public long newRevision(long planId, ActionRequest req) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_INSP_PLAN_NEWREV");
        int ver = rowVer(req);
        PlanRow src = head(planId);
        if (src.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        HeaderValue key = key(src);
        if (mapper.findDraftId(key) != null) {
            throw new BusinessException(HbErrorCode.QMS_PLAN_DRAFT_EXISTS);
        }
        FieldErrors e = FieldErrors.create();
        LocalDate from = BomService.date(req.validFrom(), "validFrom", e);
        String rev = VisibleText.isBlank(req.rev()) ? null : req.rev().strip().toUpperCase(Locale.ROOT);
        if (rev != null && !BomService.REV.matcher(rev).matches()) {
            e.add("rev", "FORMAT");
        }
        e.throwIfAny();
        if (from == null) {
            LocalDate today = clock.today();
            from = "RELEASED".equals(src.getStatus()) && !src.getValidFrom().isBefore(today) ? src.getValidFrom().plusDays(1) : today;
            if (from.getYear() > 2999) {
                throw SamplingService.fieldError("validFrom", "FORMAT");
            }
        }
        HeaderValue v = new HeaderValue(key.inspType(), key.itemId(), key.itemClassId(), key.procId(), key.altNo(),
                rev != null ? rev : nextRev(key), from, null, null, src.getRemark(), null);
        long id;
        try {
            id = mapper.insertHeader(v, me.loginId());
        } catch (DuplicateKeyException ex) {
            if (rev != null && PgErrors.constraint(ex).filter("uq_qms_insp_plan_rev"::equals).isPresent()) {
                throw SamplingService.fieldError("rev", "DUPLICATE");
            }
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.copyLines(planId, id, me.loginId());
        mapper.copyDoc(planId, id);
        return id;
    }

    // ------------------------------------------------------------------ 검사기준서 연결 (Q3)

    @Transactional(readOnly = true)
    public List<DocChoice> docChoices() {
        return mapper.docChoices();
    }

    /** 작성 중 계획에 검사기준서 리비전을 걸거나(docRevId) 푼다(null). 같은 회사·검사기준서·폐기본 아님은 트리거가 최종 */
    @Transactional
    public void linkDoc(long planId, LinkDocForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        int ver = in.rowVer();
        PlanRow cur = head(planId);
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_PLAN_NOT_DRAFT);
        }
        try {
            if (mapper.linkDoc(planId, in.docRevId(), ver, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            String c = PgErrors.constraint(e).orElse("");
            if ("fk_qms_insp_plan_doc_rev_comp".equals(c) || "fk_qms_insp_plan_doc_rev".equals(c)) {
                throw SamplingService.fieldError("docRevId", "NOT_FOUND");
            }
            if ("ck_qms_insp_plan_doc_rev".equals(c)) {
                throw SamplingService.fieldError("docRevId", "DOC_NOT_INSP_STD");
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(PlanExportRequest req, HttpServletResponse response) throws IOException {
        PlanExportRequest r = req == null ? new PlanExportRequest(null, null, null, null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, r.columns());
        PlanSearch q = search(r.inspType(), r.target(), r.procCd(), r.status(), r.withObsolete(), r.specChangedOnly(),
                PageQuery.sortOnly(r.sort(), PlanSort.values(), DEFAULT_SORT));
        List<String> keys = new ArrayList<>();
        if (q.getInspType() != null) {
            keys.add("inspType");
        }
        if (q.getTargetLike() != null) {
            keys.add("target");
        }
        if (q.getProcCd() != null) {
            keys.add("procCd");
        }
        if (q.getStatus() != null) {
            keys.add("status");
        }
        return excel.export(response, new ExportJob(PROG, "prog.qmsInspPlan", cols, mapper.count(q), keys), w -> {
            try (Cursor<PlanRow> c = mapper.cursor(q)) {
                c.forEach(w);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // ------------------------------------------------------------------ 검증·공통

    private PlanSearch search(String inspType, String target, String procCd, String status, Boolean withObsolete,
            Boolean specChangedOnly, PageQuery pq) {
        String t = VisibleText.isBlank(target) ? null : target.strip();
        String proc = VisibleText.isBlank(procCd) ? null : procCd.strip().toUpperCase(Locale.ROOT);
        if ((t != null && t.length() > 200) || (proc != null && proc.length() > 20)
                || (!VisibleText.isBlank(inspType) && !TYPES.contains(inspType))
                || (!VisibleText.isBlank(status) && !STATUSES.contains(status))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new PlanSearch(VisibleText.isBlank(inspType) ? null : inspType,
                t == null ? null : "%" + BomService.likeEscape(t) + "%", proc, VisibleText.isBlank(status) ? null : status,
                Boolean.TRUE.equals(withObsolete), Boolean.TRUE.equals(specChangedOnly), pq);
    }

    private HeaderValue validateHeader(HeaderForm in, PlanRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type;
        Long itemId = null;
        Long classId = null;
        Long procId = null;
        int altNo = in.altNo() == null ? 1 : in.altNo();
        if (cur != null) {
            type = cur.getInspType();
            itemId = cur.getItemId();
            classId = cur.getItemClassId();
            procId = cur.getProcId();
            altNo = cur.getAltNo();
        } else {
            type = VisibleText.isBlank(in.inspType()) ? null : in.inspType().strip();
            if (type == null) {
                e.add("inspType", "REQUIRED");
            } else if (!TYPES.contains(type)) {
                e.add("inspType", "CODE");
            }
            boolean hasItem = !VisibleText.isBlank(in.itemCd());
            boolean hasClass = !VisibleText.isBlank(in.classCd());
            if (hasItem && hasClass) {
                e.add("itemCd", "ONE_TARGET");
            } else if (!hasItem && !hasClass) {
                e.add("itemCd", "REQUIRED");
            } else if (hasItem) {
                ItemLookup it = in.itemCd().length() > 40 ? null : mapper.findItemByCd(in.itemCd().strip().toUpperCase(Locale.ROOT));
                if (it == null) {
                    e.add("itemCd", "NOT_FOUND");
                } else if (!"Y".equals(it.getUseYn())) {
                    e.add("itemCd", "INACTIVE");
                } else {
                    itemId = it.getItemId();
                }
            } else {
                TargetLookup c = in.classCd().length() > 30 ? null
                        : mapper.findClassByCd(in.classCd().strip().toUpperCase(Locale.ROOT));
                if (c == null) {
                    e.add("classCd", "NOT_FOUND");
                } else if (!"Y".equals(c.getUseYn())) {
                    e.add("classCd", "INACTIVE");
                } else {
                    classId = c.getId();
                }
            }
            if (!VisibleText.isBlank(in.procCd())) {
                TargetLookup p = in.procCd().length() > 20 ? null : mapper.findProcByCd(in.procCd().strip().toUpperCase(Locale.ROOT));
                if (p == null) {
                    e.add("procCd", "NOT_FOUND");
                } else if (!"Y".equals(p.getUseYn())) {
                    e.add("procCd", "INACTIVE");
                } else {
                    procId = p.getId();
                }
            }
            if (altNo < 1 || altNo > 99) {
                e.add("altNo", "RANGE", 1, 99);
            }
        }
        String rev = VisibleText.isBlank(in.rev()) ? null : in.rev().strip().toUpperCase(Locale.ROOT);
        if (rev != null && !BomService.REV.matcher(rev).matches()) {
            e.add("rev", "FORMAT");
        }
        LocalDate from = BomService.date(in.validFrom(), "validFrom", e);
        String changeNo = VisibleText.optional(in.changeNo(), "changeNo", 30, e);
        String reason = VisibleText.optional(in.changeReason(), "changeReason", 500, e);
        String remark = VisibleText.optional(in.remark(), "remark", 1000, e);
        e.throwIfAny();
        HeaderValue key = new HeaderValue(type, itemId, classId, procId, altNo, null, null, null, null, null, null);
        if (rev == null) {
            rev = cur != null ? cur.getRev() : nextRev(key);
        }
        if (from == null) {
            from = cur != null ? cur.getValidFrom() : clock.today();
        }
        return new HeaderValue(type, itemId, classId, procId, altNo, rev, from, changeNo, reason, remark, in.rowVer());
    }

    private static HeaderValue key(PlanRow p) {
        return new HeaderValue(p.getInspType(), p.getItemId(), p.getItemClassId(), p.getProcId(), p.getAltNo(), null, null, null,
                null, null, null);
    }

    /** 다음 자동 리비전 — BOM 과 같은 체계(A0…Z9, AA0…ZZ9) */
    String nextRev(HeaderValue key) {
        String max = mapper.findRevs(key).stream().filter(r -> BomService.AUTO_REV.matcher(r).matches())
                .max(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())).orElse(null);
        return max == null ? "A0" : BomService.nextAutoRev(max);
    }

    private PlanRow head(long planId) {
        PlanRow r = mapper.findById(planId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    private PlanRow draft(long planId) {
        PlanRow r = head(planId);
        if (!"DRAFT".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_PLAN_NOT_DRAFT);
        }
        return r;
    }

    /** 확정 검사 오류의 행 키 — 화면은 'L' + lineId 로 행을 찾는다 */
    static String lineKey(LineRow l) {
        return "L" + l.getLineId();
    }

    private static int rowVer(ActionRequest req) {
        if (req == null || req.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return req.rowVer();
    }

    /** 규격 글자 — "9.95 ~ 10.05 mm", "≥ 4.0 kN", "≤ 0.3", 계수는 "OK/NG" (특성 마스터 목록과 같은 표기) */
    static String spec(String dataType, BigDecimal lsl, BigDecimal usl, String unitNm, Integer scale) {
        if (dataType == null) {
            return null;
        }
        if ("ATTRIBUTE".equals(dataType)) {
            return "OK/NG";
        }
        int sc = scale == null ? 0 : scale;
        String unit = unitNm == null ? "" : " " + unitNm;
        String s;
        if (lsl != null && usl != null) {
            s = num(lsl, sc) + " ~ " + num(usl, sc);
        } else if (lsl != null) {
            s = "≥ " + num(lsl, sc);
        } else if (usl != null) {
            s = "≤ " + num(usl, sc);
        } else {
            s = "";
        }
        return s + unit;
    }

    private static String num(BigDecimal v, int scale) {
        return v.setScale(Math.max(scale, Math.max(0, v.stripTrailingZeros().scale())), RoundingMode.UNNECESSARY).toPlainString();
    }
}
