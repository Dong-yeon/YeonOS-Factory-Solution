package io.smarthappy.module.master.routing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
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

import org.apache.ibatis.cursor.Cursor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.master.routing.RoutingDtos.ActionRequest;
import io.smarthappy.module.master.routing.RoutingDtos.ExportRequest;
import io.smarthappy.module.master.routing.RoutingDtos.HeaderForm;
import io.smarthappy.module.master.routing.RoutingDtos.HeaderValue;
import io.smarthappy.module.master.routing.RoutingDtos.ItemLookup;
import io.smarthappy.module.master.routing.RoutingDtos.ParamItem;
import io.smarthappy.module.master.routing.RoutingDtos.ParamRow;
import io.smarthappy.module.master.routing.RoutingDtos.ParamValue;
import io.smarthappy.module.master.routing.RoutingDtos.PlaceRow;
import io.smarthappy.module.master.routing.RoutingDtos.ProcRow;
import io.smarthappy.module.master.routing.RoutingDtos.RoutingDetail;
import io.smarthappy.module.master.routing.RoutingDtos.RoutingRow;
import io.smarthappy.module.master.routing.RoutingDtos.RoutingSearch;
import io.smarthappy.module.master.routing.RoutingDtos.RoutingSort;
import io.smarthappy.module.master.routing.RoutingDtos.SaveResult;
import io.smarthappy.module.master.routing.RoutingDtos.StepItem;
import io.smarthappy.module.master.routing.RoutingDtos.StepRow;
import io.smarthappy.module.master.routing.RoutingDtos.StepValue;
import io.smarthappy.module.master.routing.RoutingDtos.StructureForm;
import io.smarthappy.module.master.EcoReleaseGate;
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
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 라우팅 관리 (mst-routing) — P1 5단계, docs/20.
 *
 * - 머리 = 품목 × 공장 × 대체 번호 × 리비전. 상태·기간·자동 마감·새 리비전은 BOM(BomService)과 같은 규칙
 * - 단계·파라미터는 "한 벌" 저장: 화면이 보낸 목록과 DB 를 id 로 맞춰 추가·수정·삭제. 바뀐 행만 UPDATE(감사 이력이 깨끗하게)
 * - 저장은 머리 버전을 올린다 — 두 사람이 같은 라우팅을 동시에 고치면 뒤쪽이 409
 * - 같은 회사·같은 공장·LINE/CELL·DRAFT 만 같은 규칙은 DB 트리거가 최종 판정, 서비스는 칸 오류로 먼저 알린다
 */
@Service
@RequiredArgsConstructor
public class RoutingService {

    static final String PROG = "mst-routing";
    static final Pattern PARAM_CD = Pattern.compile("^[A-Z0-9_]{1,30}$");
    /** 공학 단위 공통코드(docs/68 Q2) */
    static final String UNIT_GRP = "MEAS_UNIT";
    static final Pattern ROW_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    static final Set<String> STATUSES = Set.of("DRAFT", "RELEASED", "OBSOLETE");
    static final List<String> DEFAULT_SORT = List.of("itemCd,asc", "plantCd,asc", "rev,desc");
    static final int MAX_STEPS = 200;
    static final int MAX_PARAMS = 50;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("itemCd", "mstRouting.col.itemCd", 140).withSort().withPriority(1),
            ColumnSpec.text("itemNm", "mstRouting.col.itemNm", 200).withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.text("plantCd", "mstRouting.col.plantCd", 70).withSort().withPriority(2),
            ColumnSpec.of("altNo", "mstRouting.col.altNo", ColumnSpec.ColumnType.INT, 60).withPriority(3),
            ColumnSpec.text("rev", "mstRouting.col.rev", 70).withSort().withPriority(1),
            ColumnSpec.code("status", "mstRouting.col.status", "BOM_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.of("validFrom", "mstRouting.col.validFrom", ColumnSpec.ColumnType.DATE, 100).withSort(),
            ColumnSpec.of("validTo", "mstRouting.col.validTo", ColumnSpec.ColumnType.DATE, 100),
            ColumnSpec.of("stepCnt", "mstRouting.col.stepCnt", ColumnSpec.ColumnType.INT, 70),
            ColumnSpec.dec("totalCtSec", "mstRouting.col.totalCtSec", 3, 100).withPriority(3),
            ColumnSpec.text("changeNo", "mstRouting.col.changeNo", 110).withPriority(3),
            ColumnSpec.of("releasedAt", "mstRouting.col.releasedAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(3),
            ColumnSpec.of("updatedAt", "mstRouting.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort()
                    .withPriority(3),
            ColumnSpec.text("updatedBy", "mstRouting.col.updatedBy", 100).withPriority(3));

    private final RoutingMapper mapper;
    private final PolicyService policies;
    private final InboxService inbox;
    private final ExcelExportService excel;
    private final BizClock clock;
    private final EcoReleaseGate ecoGate;
    private final io.smarthappy.platform.code.CodeService codes;

    public record Page(List<RoutingRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String itemCd, String itemNm, String plantCd, String status, Boolean withObsolete, Integer page,
            Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, RoutingSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        RoutingSearch q = search(itemCd, itemNm, plantCd, status, withObsolete, pq);
        return new Page(mapper.findPage(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public RoutingDetail detail(long routingId) {
        RoutingRow r = head(routingId);
        List<StepRow> steps = steps(routingId);
        // 점검 기준일 = 라우팅 시작일(미래면) 또는 오늘
        LocalDate today = clock.today();
        LocalDate at = r.getValidFrom().isAfter(today) ? r.getValidFrom() : today;
        return new RoutingDetail(r, steps, mapper.unmatchedBomOps(routingId, r.getItemId(), at));
    }

    /** 단계 + 파라미터 (파라미터는 한 번에 읽어 단계에 붙인다) */
    private List<StepRow> steps(long routingId) {
        List<StepRow> steps = mapper.findSteps(routingId);
        Map<Long, List<ParamRow>> byStep = new HashMap<>();
        mapper.findParams(routingId).forEach(p -> byStep.computeIfAbsent(p.getStepId(), k -> new ArrayList<>()).add(p));
        steps.forEach(s -> s.setParams(byStep.getOrDefault(s.getStepId(), List.of())));
        return steps;
    }

    @Transactional(readOnly = true)
    public ItemLookup item(String itemCd) {
        ItemLookup it = blank(itemCd) ? null : mapper.findItemByCd(itemCd.strip().toUpperCase(Locale.ROOT));
        if (it == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return it;
    }

    /** 라우팅 화면의 공정 선택지 — 사용 중인 공정 (공정 관리 권한이 없어도 라우팅은 만든다) */
    @Transactional(readOnly = true)
    public List<ProcRow> procs() {
        return mapper.findProcs("Y");
    }

    @Transactional(readOnly = true)
    public List<PlantRow> plants() {
        return mapper.findPlants();
    }

    @Transactional(readOnly = true)
    public List<PlaceRow> places(String plantCd) {
        if (blank(plantCd) || plantCd.length() > 20) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findPlaces(plantCd.strip());
    }

    // ------------------------------------------------------------------ 머리

    /** 새 라우팅(작성 중). 리비전을 비우면 다음 자동 리비전, 시작일을 비우면 오늘 */
    @Transactional
    public long create(HeaderForm form) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_ROUTING_CREATE");
        HeaderValue v = validateHeader(form, null);
        if (mapper.findDraftId(v.itemId(), v.plantCd(), v.altNo()) != null) {
            throw new BusinessException(HbErrorCode.ROUTING_DRAFT_EXISTS);
        }
        try {
            return mapper.insertHeader(v, me.loginId());
        } catch (DuplicateKeyException e) {
            if (blank(form.rev()) || PgErrors.constraint(e).filter("uq_mst_routing_draft"::equals).isPresent()) {
                // 같은 품목·공장을 동시에 신규 — 리비전 중복이 아니다 (BOM QA 3a #7 과 같은 경우)
                throw new BusinessException(HbErrorCode.ROUTING_DRAFT_EXISTS);
            }
            throw fieldError("rev", "DUPLICATE");
        }
    }

    /** 머리 수정 — 작성 중만. 품목·공장·대체 번호는 바꾸지 않는다(다른 라우팅이 됨) */
    @Transactional
    public void update(long routingId, HeaderForm form) {
        LoginUser me = me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        RoutingRow cur = draft(routingId);
        HeaderValue v = validateHeader(form, cur);
        try {
            if (mapper.updateHeader(routingId, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException e) {
            throw fieldError("rev", "DUPLICATE");
        }
    }

    // ------------------------------------------------------------------ 구조 (단계 + 파라미터)

    @Transactional
    public SaveResult saveStructure(long routingId, int headRowVer, StructureForm form) {
        LoginUser me = me();
        List<StepItem> items = form == null || form.steps() == null ? List.of() : form.steps();
        if (items.size() > MAX_STEPS || items.stream().anyMatch(Objects::isNull)
                || items.stream().anyMatch(s -> s.params() != null
                        && (s.params().size() > MAX_PARAMS || s.params().stream().anyMatch(Objects::isNull)))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        RoutingRow head = draft(routingId);
        if (mapper.touch(routingId, headRowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        Map<Long, StepRow> existing = new HashMap<>();
        steps(routingId).forEach(s -> existing.put(s.getStepId(), s));

        FieldErrors errors = FieldErrors.create();
        Set<String> keys = new HashSet<>();
        Map<String, ProcRow> procs = new HashMap<>();
        Map<Long, PlaceRow> places = new HashMap<>();
        Set<Long> seenSteps = new HashSet<>();
        Map<Integer, Integer> seqs = new HashMap<>();
        List<StepValue> values = new ArrayList<>();
        for (StepItem s : items) {
            rowKey(s.rowKey(), keys);
            if (s.stepId() != null && (!existing.containsKey(s.stepId()) || !seenSteps.add(s.stepId()))) {
                // 다른 라우팅의 단계 id, 또는 같은 단계를 두 번 — 화면이 만들 수 없는 요청
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            StepValue v = validateStep(s, head, s.stepId() == null ? null : existing.get(s.stepId()), procs, places, keys,
                    errors);
            if (v != null) {
                values.add(v);
                seqs.merge(v.opSeq(), 1, Integer::sum);
            }
        }
        values.stream().filter(v -> seqs.get(v.opSeq()) > 1).forEach(v -> errors.row(v.rowKey()).add("opSeq", "DUPLICATE"));
        errors.throwIfAny();

        int stepChanges = 0;
        int paramChanges = 0;
        try {
            // 빠진 단계 삭제(파라미터 먼저)
            for (StepRow old : existing.values()) {
                if (!seenSteps.contains(old.getStepId())) {
                    paramChanges += mapper.deleteParamsOfStep(old.getStepId());
                    stepChanges += mapper.deleteStep(routingId, old.getStepId());
                }
            }
            for (StepValue v : values) {
                long stepId;
                List<ParamRow> oldParams;
                if (v.stepId() == null) {
                    stepId = mapper.insertStep(routingId, v, me.loginId());
                    oldParams = List.of();
                    stepChanges++;
                } else {
                    stepId = v.stepId();
                    StepRow old = existing.get(stepId);
                    oldParams = old.getParams();
                    if (changed(old, v)) {
                        mapper.updateStep(routingId, v, me.loginId());
                        stepChanges++;
                    }
                }
                paramChanges += saveParams(stepId, v.params(), oldParams, me.loginId());
            }
        } catch (DataIntegrityViolationException e) {
            // 서비스 검사 뒤 다른 트랜잭션이 공정·작업장을 사용 중지한 경우 — 트리거가 막는다
            String c = PgErrors.constraint(e).orElse("");
            switch (c) {
                case "fk_mst_routing_step_proc_comp" -> throw fieldError("procCd", "INACTIVE");
                case "fk_mst_routing_step_node_plant", "ck_mst_routing_step_node_type" -> throw fieldError("nodeId",
                        "WORK_CENTER");
                default -> throw e;
            }
        }
        return new SaveResult(stepChanges, paramChanges);
    }

    private int saveParams(long stepId, List<ParamValue> in, List<ParamRow> old, String by) {
        int n = 0;
        Map<Long, ParamRow> byId = new HashMap<>();
        old.forEach(p -> byId.put(p.getParamId(), p));
        Set<Long> keep = new HashSet<>();
        in.stream().filter(p -> p.paramId() != null).forEach(p -> keep.add(p.paramId()));
        for (ParamRow p : old) {
            if (!keep.contains(p.getParamId())) {
                n += mapper.deleteParam(p.getParamId());
            }
        }
        for (ParamValue p : in) {
            if (p.paramId() == null) {
                mapper.insertParam(stepId, p, by);
                n++;
            } else if (changed(byId.get(p.paramId()), p)) {
                mapper.updateParam(stepId, p, by);
                n++;
            }
        }
        return n;
    }

    private StepValue validateStep(StepItem in, RoutingRow head, StepRow cur, Map<String, ProcRow> procs,
            Map<Long, PlaceRow> places, Set<String> keys, FieldErrors errors) {
        FieldErrors e = FieldErrors.create();
        if (in.opSeq() == null) {
            e.add("opSeq", "REQUIRED");
        } else if (in.opSeq() < 1 || in.opSeq() > 9999) {
            e.add("opSeq", "CODE");
        }
        ProcRow proc = null;
        String pcd = blank(in.procCd()) ? null : in.procCd().strip().toUpperCase(Locale.ROOT);
        if (pcd == null) {
            e.add("procCd", "REQUIRED");
        } else {
            proc = procs.computeIfAbsent(pcd, mapper::findProcByCd);
            if (proc == null) {
                e.add("procCd", "NOT_FOUND");
            } else if ((cur == null || cur.getProcId() != proc.getProcId()) && !"Y".equals(proc.getUseYn())) {
                // 새로 고른 공정만 사용 중 검사 — 이미 들어 있는 단계는 확정 검사가 본다
                e.add("procCd", "INACTIVE");
            }
        }
        if (in.nodeId() != null) {
            PlaceRow node = places.computeIfAbsent(in.nodeId(), mapper::findPlace);
            boolean fresh = cur == null || !in.nodeId().equals(cur.getNodeId());
            if (node == null || !node.getPlantCd().equals(head.getPlantCd())) {
                e.add("nodeId", "NOT_FOUND");
            } else if (fresh && (!"Y".equals(node.getUseYn()) || !Set.of("LINE", "CELL").contains(node.getNodeType()))) {
                e.add("nodeId", "WORK_CENTER");
            }
        }
        BigDecimal ct = BomService.decimal(in.stdCtSec(), "stdCtSec", 3, 9, true, e);
        int upc = in.unitsPerCycle() == null ? 1 : in.unitsPerCycle();
        if (upc < 1 || upc > 999) {
            e.add("unitsPerCycle", "CODE");
        }
        BigDecimal setup = blank(in.setupMin()) ? BigDecimal.ZERO : BomService.decimal(in.setupMin(), "setupMin", 2, 6, false, e);
        if (setup != null && setup.signum() < 0) {
            e.add("setupMin", "NOT_NEGATIVE");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        FieldErrors.RowScope row = errors.row(in.rowKey());
        e.list().forEach(fe -> row.add(fe.field(), fe.code(), fe.args()));

        List<ParamValue> params = new ArrayList<>();
        Map<String, Integer> cds = new HashMap<>();
        Set<Long> curParams = new HashSet<>();
        if (cur != null) {
            cur.getParams().forEach(p -> curParams.add(p.getParamId()));
        }
        boolean paramErr = false;
        for (ParamItem p : in.params() == null ? List.<ParamItem>of() : in.params()) {
            rowKey(p.rowKey(), keys);
            if (p.paramId() != null && !curParams.remove(p.paramId())) {
                // 다른 단계의 파라미터 id 또는 두 번
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            ParamRow oldP = p.paramId() == null || cur == null ? null
                    : cur.getParams().stream().filter(x -> x.getParamId() == p.paramId()).findFirst().orElse(null);
            ParamValue pv = validateParam(p, oldP, errors.row(p.rowKey()));
            if (pv == null) {
                paramErr = true;
            } else {
                params.add(pv);
                cds.merge(pv.paramCd(), 1, Integer::sum);
            }
        }
        for (ParamValue pv : params) {
            if (cds.get(pv.paramCd()) > 1) {
                errors.row(pv.rowKey()).add("paramCd", "DUPLICATE");
                paramErr = true;
            }
        }
        if (!e.isEmpty() || paramErr) {
            return null;
        }
        return new StepValue(in.rowKey(), in.stepId(), in.opSeq(), proc.getProcId(), in.nodeId(), ct, upc, setup, remark,
                params);
    }

    private ParamValue validateParam(ParamItem in, ParamRow cur, FieldErrors.RowScope row) {
        FieldErrors e = FieldErrors.create();
        String cd = blank(in.paramCd()) ? null : in.paramCd().strip().toUpperCase(Locale.ROOT);
        if (cd == null) {
            e.add("paramCd", "REQUIRED");
        } else if (!PARAM_CD.matcher(cd).matches()) {
            e.add("paramCd", "FORMAT");
        }
        String nm = text(in.paramNm(), "paramNm", 100, true, e);
        // 공학 단위 = 공통코드 MEAS_UNIT + 이름 사본(docs/68 Q2) — 코드가 없으면 옛 글자(같은 이름 코드가 있으면 그 코드로)
        io.smarthappy.platform.code.CodeService.CodeAndName unit = codes.codeAndName(UNIT_GRP, in.unitCd(), in.unitNm(), "unitCd",
                "unitNm", 20, cur == null ? null : cur.getUnitCd(), cur == null ? null : cur.getUnitNm(), e);
        BigDecimal target = BomService.decimal(in.targetVal(), "targetVal", 6, 12, false, e);
        BigDecimal lsl = BomService.decimal(in.lsl(), "lsl", 6, 12, false, e);
        BigDecimal usl = BomService.decimal(in.usl(), "usl", 6, 12, false, e);
        if (blank(in.targetVal()) && blank(in.lsl()) && blank(in.usl())) {
            e.add("targetVal", "PARAM_VALUE");
        }
        if (lsl != null && usl != null && lsl.compareTo(usl) > 0) {
            e.add("usl", "PARAM_RANGE");
        }
        if (target != null && ((lsl != null && target.compareTo(lsl) < 0) || (usl != null && target.compareTo(usl) > 0))) {
            e.add("targetVal", "PARAM_RANGE");
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        e.list().forEach(fe -> row.add(fe.field(), fe.code(), fe.args()));
        if (!e.isEmpty()) {
            return null;
        }
        return new ParamValue(in.rowKey(), in.paramId(), cd, nm, unit.code(), unit.name(), target, lsl, usl, sort);
    }

    private static boolean changed(StepRow o, StepValue v) {
        return o.getOpSeq() != v.opSeq() || o.getProcId() != v.procId() || !Objects.equals(o.getNodeId(), v.nodeId())
                || !sameNum(o.getStdCtSec(), v.stdCtSec()) || o.getUnitsPerCycle() != v.unitsPerCycle()
                || !sameNum(o.getSetupMin(), v.setupMin()) || !Objects.equals(o.getRemark(), v.remark());
    }

    private static boolean changed(ParamRow o, ParamValue v) {
        return !o.getParamCd().equals(v.paramCd()) || !o.getParamNm().equals(v.paramNm())
                || !Objects.equals(o.getUnitCd(), v.unitCd()) || !Objects.equals(o.getUnitNm(), v.unitNm()) || !sameNum(o.getTargetVal(), v.targetVal())
                || !sameNum(o.getLsl(), v.lsl()) || !sameNum(o.getUsl(), v.usl()) || o.getSortNo() != v.sortNo();
    }

    private static boolean sameNum(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    // ------------------------------------------------------------------ 상태

    /** 확정 — 이전 확정본 종료일 자동 마감 (docs/20 2장) */
    @Transactional
    public void release(long routingId, ActionRequest req) {
        LoginUser me = me();
        int ver = rowVer(req);
        RoutingRow head = draft(routingId);
        if (head.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        // D4(docs/39 14.2): 회사 정책 PLM_ECO_REQUIRED=Y 면 ECO 적용 경로에서만 확정
        ecoGate.checkRelease();
        List<StepRow> steps = mapper.findSteps(routingId);
        FieldErrors errors = FieldErrors.create();
        if (head.getValidFrom().isBefore(clock.today())) {
            errors.add("validFrom", "PAST");
        }
        if (!"Y".equals(head.getItemUseYn())) {
            errors.add("itemCd", "INACTIVE");
        }
        if (steps.isEmpty()) {
            errors.add("steps", "NO_STEPS");
        }
        for (StepRow s : steps) {
            FieldErrors.RowScope row = errors.row(stepKey(s));
            if (!"Y".equals(s.getProcUseYn())) {
                row.add("procCd", "INACTIVE");
            }
            boolean inHouse = !"Y".equals(s.getOutsrcYn());
            if (s.getNodeId() == null) {
                if (inHouse) {
                    row.add("nodeId", "REQUIRED");
                }
            } else if (!"Y".equals(s.getNodeUseYn()) || !Set.of("LINE", "CELL").contains(s.getNodeType())) {
                row.add("nodeId", "WORK_CENTER");
            }
            if (inHouse && s.getStdCtSec() == null) {
                row.add("stdCtSec", "REQUIRED");
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(HbErrorCode.ROUTING_RELEASE_CHECK).withFieldErrors(errors.list());
        }
        LocalDate from = head.getValidFrom();
        for (RoutingRow prev : mapper.findReleasedOverlaps(head.getItemId(), head.getPlantCd(), head.getAltNo(), from,
                routingId)) {
            if (!prev.getValidFrom().isBefore(from)) {
                throw new BusinessException(HbErrorCode.ROUTING_PERIOD_OVERLAP, prev.getRev());
            }
            mapper.closeValidTo(prev.getRoutingId(), from, me.loginId());
        }
        if (mapper.release(routingId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    @Transactional
    public void obsolete(long routingId, ActionRequest req) {
        LoginUser me = me();
        int ver = rowVer(req);
        RoutingRow head = head(routingId);
        if ("OBSOLETE".equals(head.getStatus())) {
            throw new BusinessException(HbErrorCode.ROUTING_NOT_DRAFT);
        }
        if (mapper.obsolete(routingId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /**
     * 새 리비전 — 원본(어느 상태든) 단계·파라미터를 복사한 작성 중 라우팅. 시작일을 비우면 오늘.
     * 원본의 공정·작업장이 사용 중지여도 복사한다 — 단종 공정을 바꾸는 개정이 이 경로다(QA 5 #5)
     * (원본 확정본이 오늘 이후 시작이면 그 다음 날 — 확정 때 원본을 자동 마감할 수 있게, BOM 과 같음)
     */
    @Transactional
    public long newRevision(long routingId, ActionRequest req) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_ROUTING_NEWREV");
        int ver = rowVer(req);
        RoutingRow src = head(routingId);
        if (src.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (mapper.findDraftId(src.getItemId(), src.getPlantCd(), src.getAltNo()) != null) {
            throw new BusinessException(HbErrorCode.ROUTING_DRAFT_EXISTS);
        }
        FieldErrors e = FieldErrors.create();
        LocalDate from = BomService.date(req.validFrom(), "validFrom", e);
        String rev = blank(req.rev()) ? null : req.rev().strip().toUpperCase(Locale.ROOT);
        if (rev != null && !BomService.REV.matcher(rev).matches()) {
            e.add("rev", "FORMAT");
        }
        e.throwIfAny();
        if (from == null) {
            LocalDate today = clock.today();
            from = "RELEASED".equals(src.getStatus()) && !src.getValidFrom().isBefore(today)
                    ? src.getValidFrom().plusDays(1) : today;
            if (from.getYear() > 2999) {
                throw fieldError("validFrom", "FORMAT");
            }
        }
        HeaderValue v = new HeaderValue(src.getItemId(), src.getPlantCd(), src.getAltNo(),
                rev != null ? rev : nextRev(src.getItemId(), src.getPlantCd(), src.getAltNo()), from, null, null,
                src.getRemark(), null);
        long id;
        try {
            id = mapper.insertHeader(v, me.loginId());
        } catch (DuplicateKeyException ex) {
            if (rev != null && PgErrors.constraint(ex).filter("uq_mst_routing_rev"::equals).isPresent()) {
                throw fieldError("rev", "DUPLICATE");
            }
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        try {
            mapper.copySteps(routingId, id, me.loginId());
            mapper.copyParams(routingId, id, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            // 사용 중지된 공정·작업장도 같은 품목·공장·대체의 다른 리비전이 쓰던 것이면 트리거가 받는다(V028 — 확정 검사가 그 단계에
            // 알린다). 여기 오는 건 원본과 다른 회사·공장 같은 비정상 데이터뿐
            String c = PgErrors.constraint(ex).orElse("");
            if (c.equals("fk_mst_routing_step_proc_comp")) {
                throw fieldError("procCd", "INACTIVE");
            }
            if (c.equals("ck_mst_routing_step_node_type") || c.equals("fk_mst_routing_step_node_plant")) {
                throw fieldError("nodeId", "WORK_CENTER");
            }
            throw ex;
        }
        return id;
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(ExportRequest req, HttpServletResponse response) throws IOException {
        ExportRequest r = req == null ? new ExportRequest(null, null, null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, r.columns());
        RoutingSearch q = search(r.itemCd(), r.itemNm(), r.plantCd(), r.status(), r.withObsolete(),
                PageQuery.sortOnly(r.sort(), RoutingSort.values(), DEFAULT_SORT));
        List<String> keys = new ArrayList<>();
        if (q.getItemCdLike() != null) {
            keys.add("itemCd");
        }
        if (q.getItemNmLike() != null) {
            keys.add("itemNm");
        }
        if (q.getPlantCd() != null) {
            keys.add("plantCd");
        }
        if (q.getStatus() != null) {
            keys.add("status");
        }
        return excel.export(response, new ExportJob(PROG, "prog.mstRouting", cols, mapper.count(q), keys), w -> {
            try (Cursor<RoutingRow> c = mapper.cursor(q)) {
                c.forEach(w);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // ------------------------------------------------------------------ 검증·공통

    private RoutingSearch search(String itemCd, String itemNm, String plantCd, String status, Boolean withObsolete,
            PageQuery pq) {
        String cd = blank(itemCd) ? null : itemCd.strip().toUpperCase(Locale.ROOT);
        String nm = blank(itemNm) ? null : itemNm.strip();
        String plant = blank(plantCd) ? null : plantCd.strip();
        if ((cd != null && cd.length() > 40) || (nm != null && nm.length() > 200) || (plant != null && plant.length() > 20)
                || (!blank(status) && !STATUSES.contains(status))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new RoutingSearch(cd == null ? null : BomService.likeEscape(cd) + "%",
                nm == null ? null : "%" + BomService.likeEscape(nm) + "%", plant, blank(status) ? null : status,
                Boolean.TRUE.equals(withObsolete), pq);
    }

    private HeaderValue validateHeader(HeaderForm in, RoutingRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        long itemId = 0;
        String plant = null;
        int altNo = in.altNo() == null ? 1 : in.altNo();
        if (cur != null) {
            itemId = cur.getItemId();
            plant = cur.getPlantCd();
            altNo = cur.getAltNo();
        } else {
            ItemLookup it = blank(in.itemCd()) ? null : mapper.findItemByCd(in.itemCd().strip().toUpperCase(Locale.ROOT));
            if (blank(in.itemCd())) {
                e.add("itemCd", "REQUIRED");
            } else if (it == null) {
                e.add("itemCd", "NOT_FOUND");
            } else if (!"Y".equals(it.getUseYn())) {
                e.add("itemCd", "INACTIVE");
            } else {
                itemId = it.getItemId();
            }
            plant = blank(in.plantCd()) ? null : in.plantCd().strip();
            if (plant == null) {
                e.add("plantCd", "REQUIRED");
            } else if (plant.length() > 20 || !mapper.plantActive(plant)) {
                e.add("plantCd", "NOT_FOUND");
            }
            if (altNo < 1 || altNo > 99) {
                e.add("altNo", "CODE");
            }
        }
        String rev = blank(in.rev()) ? null : in.rev().strip().toUpperCase(Locale.ROOT);
        if (rev != null && !BomService.REV.matcher(rev).matches()) {
            e.add("rev", "FORMAT");
        }
        LocalDate from = BomService.date(in.validFrom(), "validFrom", e);
        String changeNo = text(in.changeNo(), "changeNo", 30, false, e);
        String reason = text(in.changeReason(), "changeReason", 500, false, e);
        String remark = text(in.remark(), "remark", 1000, false, e);
        e.throwIfAny();
        if (rev == null) {
            rev = cur != null ? cur.getRev() : nextRev(itemId, plant, altNo);
        }
        if (from == null) {
            from = cur != null ? cur.getValidFrom() : clock.today();
        }
        return new HeaderValue(itemId, plant, altNo, rev, from, changeNo, reason, remark, in.rowVer());
    }

    /** 다음 자동 리비전 — BOM 과 같은 체계(A0…Z9, AA0…ZZ9) */
    String nextRev(long itemId, String plantCd, int altNo) {
        String max = mapper.findRevs(itemId, plantCd, altNo).stream().filter(r -> BomService.AUTO_REV.matcher(r).matches())
                .max(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())).orElse(null);
        return max == null ? "A0" : BomService.nextAutoRev(max);
    }

    private RoutingRow head(long routingId) {
        RoutingRow r = mapper.findById(routingId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    private RoutingRow draft(long routingId) {
        RoutingRow r = head(routingId);
        if (!"DRAFT".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.ROUTING_NOT_DRAFT);
        }
        return r;
    }

    /** 확정 검사 오류의 행 키 — 화면은 'S' + stepId 로 단계를 찾는다 */
    static String stepKey(StepRow s) {
        return "S" + s.getStepId();
    }

    private static void rowKey(String key, Set<String> keys) {
        if (key == null || !ROW_KEY.matcher(key).matches() || !keys.add(key)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    private static int rowVer(ActionRequest req) {
        if (req == null || req.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return req.rowVer();
    }

    private static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    private static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
