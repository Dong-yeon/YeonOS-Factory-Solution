package io.smarthappy.module.wms.loc;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.loc.LocAttrDtos.ClassOpt;
import io.smarthappy.module.wms.loc.LocAttrDtos.AllowView;
import io.smarthappy.module.wms.loc.LocAttrDtos.AllowForm;
import io.smarthappy.module.wms.loc.LocAttrDtos.AttrForm;
import io.smarthappy.module.wms.loc.LocAttrDtos.ExportRequest;
import io.smarthappy.module.wms.loc.LocAttrDtos.ItemHit;
import io.smarthappy.module.wms.loc.LocAttrDtos.LineOpt;
import io.smarthappy.module.wms.loc.LocAttrDtos.LocRow;
import io.smarthappy.module.wms.loc.LocAttrDtos.NodeHit;
import io.smarthappy.module.wms.label.LabelDtos.PrintItem;
import io.smarthappy.module.wms.label.LabelDtos.PrinterRow;
import io.smarthappy.module.wms.label.LabelDtos.TplRow;
import io.smarthappy.module.wms.label.LabelMapper;
import io.smarthappy.module.wms.label.LabelPrintService;
import io.smarthappy.module.wms.loc.LocAttrDtos.PlantRow;
import io.smarthappy.module.wms.loc.LocAttrDtos.PrintRequest;
import io.smarthappy.module.wms.loc.LocAttrDtos.PrintResult;
import io.smarthappy.module.wms.loc.LocAttrDtos.RenumberForm;
import io.smarthappy.module.wms.loc.LocAttrDtos.SaveResult;
import io.smarthappy.module.wms.loc.LocAttrDtos.ScanRequest;
import io.smarthappy.module.wms.loc.LocAttrDtos.ScanView;
import io.smarthappy.module.wms.scan.ScanKeyResolver;
import io.smarthappy.module.wms.scan.ScanKeyResolver.BareAs;
import io.smarthappy.module.wms.scan.ScanKeyResolver.Context;
import io.smarthappy.module.wms.scan.ScanKeyResolver.Gs1;
import io.smarthappy.module.wms.scan.ScanKeyResolver.Result;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 로케이션 속성(wms-loc) — P4 1부 W1, docs/30.
 *
 * 노드 규칙(창고·로케이션만, 같은 회사, 새로 붙일 땐 사용 중 노드만, 대상 고정)은 V037 트리거가 최종 판정하고,
 * 서비스는 칸 형식을 먼저 본 뒤 트리거 위반을 칸 오류로 바꾼다(EquipService 와 같은 방식).
 */
@Service
@RequiredArgsConstructor
public class LocAttrService {

    static final String PROG = "wms-loc";
    static final String POLICY_BARE = "WMS_SCAN_BARE_AS";
    static final Set<String> USAGES = Set.of("STORAGE", "RECEIVING", "QC", "STAGING", "LINESIDE", "SCRAP", "AUTO_STORE");   // AUTO_STORE = 자동창고 가상 로케이션(docs/65 23장)
    static final Set<String> STATUSES = Set.of("AVAILABLE", "QC", "HOLD", "BLOCKED");
    /** 피킹 순서 다시 매기기 한 번에 다룰 하위 수 상한 — 999,999 / 간격 */
    static final int MAX_PICK_SEQ = 999_999;

    static final Map<String, String[]> CONSTRAINT_FIELD = Map.of(
            "ck_wms_loc_attr_node_type", new String[] {"nodeId", "NOT_STORAGE_NODE"},
            "ck_wms_loc_attr_node_use", new String[] {"nodeId", "INACTIVE"},
            "fk_wms_loc_attr_node_comp", new String[] {"nodeId", "NOT_FOUND"},
            "ck_wms_loc_attr_usage", new String[] {"locUsage", "CODE"},
            "ck_wms_loc_attr_status", new String[] {"defaultStatus", "CODE"},
            "ck_wms_loc_attr_pick_seq", new String[] {"pickSeq", "CODE"},
            "fk_wms_loc_attr_feed_comp", new String[] {"feedNodeId", "NOT_FOUND"},
            "ck_wms_loc_attr_feed", new String[] {"feedNodeId", "LINESIDE_ONLY"});

    static final List<ColumnSpec> TREE_COLUMNS = List.of(
            ColumnSpec.text("lvlMark", "wmsLoc.col.lvl", 70),
            ColumnSpec.text("nodeCd", "wmsLoc.col.nodeCd", 200),
            ColumnSpec.text("nodeNm", "wmsLoc.col.nodeNm", 180).withFlex().withMinWidth(120),
            ColumnSpec.code("nodeType", "wmsLoc.col.nodeType", "EQUIP_NODE_TYPE", 80),
            ColumnSpec.code("locUsage", "wmsLoc.col.locUsage", "WMS_LOC_USAGE", 100),
            ColumnSpec.of("stockYn", "wmsLoc.col.stockYn", ColumnSpec.ColumnType.YN, 70),
            ColumnSpec.of("pickSeq", "wmsLoc.col.pickSeq", ColumnSpec.ColumnType.INT, 80),
            ColumnSpec.of("mixItemYn", "wmsLoc.col.mixItemYn", ColumnSpec.ColumnType.YN, 80),
            ColumnSpec.of("mixLotYn", "wmsLoc.col.mixLotYn", ColumnSpec.ColumnType.YN, 80),
            ColumnSpec.code("defaultStatus", "wmsLoc.col.defaultStatus", "STOCK_STATUS", 90),
            ColumnSpec.text("path", "wmsLoc.col.path", 220),
            ColumnSpec.of("useYn", "wmsLoc.col.useYn", ColumnSpec.ColumnType.YN, 70),
            ColumnSpec.text("remark", "wmsLoc.col.remark", 200));

    private final LocAttrMapper mapper;
    private final PolicyService policy;
    private final BizClock clock;
    private final MessageSource messages;
    private final ExcelExportService excel;
    private final LabelPrintService print;
    private final LabelMapper labelMapper;
    private final io.smarthappy.module.wms.inv.PutawayMapper putaway;

    // ------------------------------------------------------------------ 조회

    /** 보급 라인 선택지 — 그 공장의 사용 중 LINE·CELL */
    @Transactional(readOnly = true)
    public List<LineOpt> lines(String plantCd) {
        return mapper.findLines(plant(plantCd));
    }

    @Transactional(readOnly = true)
    public List<PlantRow> plants() {
        return mapper.findPlants();
    }

    @Transactional(readOnly = true)
    public List<LocRow> tree(String plantCd, Boolean withUnused) {
        String plant = plant(plantCd);
        List<LocRow> rows = mapper.findTree(plant, Boolean.TRUE.equals(withUnused));
        rows.forEach(r -> r.setLvlMark(". ".repeat(Math.max(0, r.getLvl() - 1)) + r.getLvl()));
        return rows;
    }

    // ------------------------------------------------------------------ 저장

    /** 속성 저장(없으면 만든다). rowVer: 속성 행이 있으면 그 값, 없으면 null */
    @Transactional
    public SaveResult save(long nodeId, AttrForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        NodeHit cur = storageNode(nodeId);
        AttrForm f = validate(in);
        try {
            if (cur.isAttrSet()) {
                if (f.rowVer() == null || mapper.updateAttr(nodeId, f, me.loginId()) == 0) {
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
            } else {
                if (f.rowVer() != null) {      // 화면이 본 행이 그 사이 사라질 수는 없다(삭제 금지) — 잘못 보낸 값
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
                mapper.insertAttr(nodeId, f, me.loginId());
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HbErrorCode.CONFLICT);   // 같은 노드를 두 사람이 동시에 처음 저장
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        int applied = 0;
        if (Boolean.TRUE.equals(in.applyToChildren())) {
            mapper.lockNode(nodeId);
            for (NodeHit d : mapper.findDescendants(nodeId)) {
                if (!d.isAttrSet() && !"Y".equals(d.getUseYn())) {
                    continue;                // 사용 중지 노드에 속성을 새로 붙이지 않는다(트리거와 같은 규칙)
                }
                applied += mapper.upsertShared(d.getNodeId(), f, me.loginId());
            }
        }
        return new SaveResult(applied);
    }

    // ------------------------------------------------------------------ 적치 검사 허용 품목군 (W-12, docs/30 12.19)

    /** 품목군 선택지(사용 중) */
    @Transactional(readOnly = true)
    public List<ClassOpt> classOptions() {
        return putaway.classOptions().stream().map(a -> new ClassOpt(a.getClassId(), a.getClassCd(), a.getClassNm())).toList();
    }

    /** 노드의 허용 품목군 — 직접 목록, 없으면 상속된 실효 목록(어느 상위에서 왔는지) */
    @Transactional(readOnly = true)
    public AllowView allow(long nodeId) {
        storageNode(nodeId);
        List<Long> own = putaway.ownClassIds(nodeId);
        if (!own.isEmpty()) {
            return new AllowView(own, null, List.of());
        }
        List<io.smarthappy.module.wms.inv.PutawayMapper.Allow> eff = putaway.allowedFor(nodeId);
        if (eff.isEmpty()) {
            return new AllowView(List.of(), null, List.of());
        }
        return new AllowView(List.of(), putaway.nodeCd(eff.get(0).getOwnerNodeId()),
                eff.stream().map(io.smarthappy.module.wms.inv.PutawayMapper.Allow::getClassCd).toList());
    }

    /** 허용 품목군 저장 — 이 노드의 목록 전체로 맞춘다(뺀 것은 use_yn = 'N', 마스터 삭제 금지) */
    @Transactional
    public AllowView saveAllow(long nodeId, AllowForm in) {
        LoginUser me = me();
        storageNode(nodeId);
        List<Long> want = in == null || in.classIds() == null ? List.of()
                : in.classIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (want.size() > 50) {                                         // valid.TOO_MANY = 선택지 50개까지
            throw fieldError("classIds", "TOO_MANY");
        }
        java.util.Set<Long> known = new java.util.HashSet<>(putaway.classOptions().stream()
                .map(io.smarthappy.module.wms.inv.PutawayMapper.Allow::getClassId).toList());
        if (!known.containsAll(want)) {
            throw fieldError("classIds", "NOT_FOUND");                 // 다른 회사·사용 중지·없는 품목군
        }
        mapper.lockNode(nodeId);                                        // 같은 노드 동시 저장은 줄 선다
        java.util.Set<Long> left = new java.util.LinkedHashSet<>(want);
        for (io.smarthappy.module.wms.inv.PutawayMapper.AllowRow r : putaway.lockRows(nodeId)) {
            boolean keep = left.remove(r.getClassId());
            String use = keep ? "Y" : "N";
            if (!use.equals(r.getUseYn())) {
                putaway.setUse(r.getAllowId(), use, me.loginId());
            }
        }
        for (Long c : left) {
            putaway.insert(nodeId, c, me.loginId());
        }
        return allow(nodeId);
    }

    /** 하위 창고·로케이션의 피킹 순서를 트리 순서대로 step, 2·step … 으로. 반환 = 바뀐 행 수 */
    @Transactional
    public int renumberPick(long nodeId, RenumberForm in) {
        LoginUser me = me();
        storageNode(nodeId);
        int step = in == null || in.step() == null ? 10 : in.step();
        if (step < 1 || step > 1000) {
            throw fieldError("step", "CODE");
        }
        mapper.lockNode(nodeId);
        List<NodeHit> targets = mapper.findDescendants(nodeId).stream()
                .filter(d -> d.isAttrSet() || "Y".equals(d.getUseYn())).toList();
        if ((long) targets.size() * step > MAX_PICK_SEQ) {
            throw fieldError("step", "PICK_SEQ_OVERFLOW");
        }
        int changed = 0;
        int seq = 0;
        for (NodeHit d : targets) {
            seq += step;
            changed += mapper.upsertPickSeq(d.getNodeId(), seq, me.loginId());
        }
        return changed;
    }

    private NodeHit storageNode(long nodeId) {
        NodeHit cur = mapper.findNode(nodeId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"WH".equals(cur.getNodeType()) && !"LOC".equals(cur.getNodeType())) {
            throw fieldError("nodeId", "NOT_STORAGE_NODE");
        }
        return cur;
    }

    private AttrForm validate(AttrForm in) {
        FieldErrors e = FieldErrors.create();
        String usage = blank(in.locUsage()) ? "STORAGE" : in.locUsage().strip();
        if (!USAGES.contains(usage)) {
            e.add("locUsage", "CODE");
        }
        String stock = yn(in.stockYn(), "stockYn", e);
        int pick = in.pickSeq() == null ? 0 : in.pickSeq();
        if (pick < 0 || pick > MAX_PICK_SEQ) {
            e.add("pickSeq", "CODE");
        }
        String mixItem = yn(in.mixItemYn(), "mixItemYn", e);
        String mixLot = yn(in.mixLotYn(), "mixLotYn", e);
        String status = blank(in.defaultStatus()) ? null : in.defaultStatus().strip();
        if (status != null && !STATUSES.contains(status)) {
            e.add("defaultStatus", "CODE");
        }
        String remark = blank(in.remark()) ? null : in.remark().strip();
        if (remark != null && remark.length() > 500) {
            e.add("remark", "TOO_LONG", 500);
        }
        // 보급 라인은 LINESIDE 용도만(D-B12) — 같은 공장의 LINE·CELL 인지는 DB 가 본다(fk_wms_loc_attr_feed_comp)
        Long feed = in.feedNodeId();
        if (feed != null && !"LINESIDE".equals(usage)) {
            e.add("feedNodeId", "LINESIDE_ONLY");
        }
        e.throwIfAny();
        return new AttrForm(usage, stock, pick, mixItem, mixLot, status, remark, in.rowVer(), in.applyToChildren(), feed);
    }

    private RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse(null);
        String[] fe = c == null ? null : CONSTRAINT_FIELD.get(c);
        return fe == null ? e : fieldError(fe[0], fe[1]);
    }

    // ------------------------------------------------------------------ 로케이션 라벨 인쇄 (W2)

    /** 고른 노드(+ 하위)의 로케이션 라벨. 사용 중 창고·로케이션만, 같은 공장만. 노드마다 작업 1건 */
    @Transactional
    public PrintResult printLabels(PrintRequest req) {
        if (req == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String plant = plant(req.plantCd());
        if (req.nodeIds() == null || req.nodeIds().isEmpty()) {
            throw fieldError("nodeIds", "REQUIRED");
        }
        if (req.printerId() == null) {
            throw fieldError("printerId", "REQUIRED");
        }
        java.util.LinkedHashMap<Long, NodeHit> targets = new java.util.LinkedHashMap<>();
        for (Long id : req.nodeIds()) {
            if (id == null) {
                continue;
            }
            NodeHit n = mapper.findNode(id);
            if (n == null || !plant.equals(n.getPlantCd())) {
                throw fieldError("nodeIds", "NOT_FOUND");
            }
            if (!"WH".equals(n.getNodeType()) && !"LOC".equals(n.getNodeType())) {
                throw fieldError("nodeIds", "NOT_STORAGE_NODE");
            }
            if ("Y".equals(n.getUseYn())) {
                targets.putIfAbsent(n.getNodeId(), n);
            }
            if (Boolean.TRUE.equals(req.includeChildren())) {
                for (NodeHit d : mapper.findDescendants(id)) {
                    if ("Y".equals(d.getUseYn())) {
                        targets.putIfAbsent(d.getNodeId(), mapper.findNode(d.getNodeId()));   // 경로가 필요하다
                    }
                }
            }
            if (targets.size() > 500) {
                throw fieldError("nodeIds", "TOO_MANY_LABELS", 500);
            }
        }
        if (targets.isEmpty()) {
            throw fieldError("nodeIds", "INACTIVE");
        }
        List<PrintItem> items = targets.values().stream().map(n -> {
            Map<String, String> v = new java.util.LinkedHashMap<>();
            v.put("plantCd", n.getPlantCd());
            v.put("locCd", n.getNodeCd());
            v.put("locNm", n.getNodeNm());
            v.put("path", n.getPath());
            v.put("usage", n.getLocUsage());
            v.put("barcode", "LOC:" + n.getPlantCd() + ":" + n.getNodeCd());     // ScanKeyResolver 내부 형식(D7)
            return new PrintItem(v.get("barcode"), v);      // 대상 = 라벨 바코드 값(이력에서 사람이 읽고 찾게)
        }).toList();
        int copies = req.copies() == null ? 1 : req.copies();
        return new PrintResult(print.request("LOC", req.tplId(), req.printerId(), copies, items).size());
    }

    @Transactional(readOnly = true)
    public List<PrinterRow> printers(String plantCd) {
        return labelMapper.findPrinters(plant(plantCd), "Y").stream()
                .filter(p -> "ANY".equals(p.getUsage()) || "LOC".equals(p.getUsage())).toList();
    }

    @Transactional(readOnly = true)
    public List<TplRow> locTemplates() {
        return labelMapper.findTpls("LOC", "Y");
    }

    // ------------------------------------------------------------------ 스캔 확인

    /**
     * 바코드 글자를 스캔 키 규칙(순수 함수)으로 판별한 뒤, 로케이션·품목이면 실제로 있는지 본다.
     * 판별 실패도 200 으로 돌려준다(화면이 이유를 보여 주는 확인 칸이므로) — 공장만 틀리면 400.
     */
    @Transactional(readOnly = true)
    public ScanView scan(ScanRequest req) {
        if (req == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String plant = plant(req.plantCd());
        BareAs bareAs = BareAs.valueOf(policy.value(POLICY_BARE));
        Result r = ScanKeyResolver.resolve(req.text(), new Context(plant, bareAs, clock.today()));
        Locale loc = LocaleContextHolder.getLocale();
        if (!r.ok()) {
            String msg = messages.getMessage("wmsScan.reason." + r.reason().name(),
                    new Object[] {r.detail() == null ? "" : r.detail()}, loc);
            return view(r, false, r.reason().name(), msg, null, null);
        }
        switch (r.type()) {
            case LOC: {
                NodeHit n = mapper.findNodeByCode(r.plantCd(), r.key());
                if (n == null) {
                    return view(r, false, "NOT_FOUND", messages.getMessage("wmsScan.lookup.locNotFound", new Object[] {r.key()}, loc), null, null);
                }
                if (!"WH".equals(n.getNodeType()) && !"LOC".equals(n.getNodeType())) {
                    return view(r, false, "NOT_STORAGE_NODE", messages.getMessage("wmsScan.lookup.notStorage", new Object[] {n.getPath()}, loc), n, null);
                }
                boolean active = "Y".equals(n.getUseYn());
                return view(r, active, active ? null : "INACTIVE",
                        messages.getMessage(active ? "wmsScan.lookup.locOk" : "wmsScan.lookup.inactive", new Object[] {n.getPath()}, loc), n, null);
            }
            case ITEM: {
                ItemHit it = mapper.findItemByCode(r.key());
                if (it == null) {
                    return view(r, false, "NOT_FOUND", messages.getMessage("wmsScan.lookup.itemNotFound", new Object[] {r.key()}, loc), null, null);
                }
                boolean active = "Y".equals(it.getUseYn());
                return view(r, active, active ? null : "INACTIVE",
                        messages.getMessage(active ? "wmsScan.lookup.itemOk" : "wmsScan.lookup.inactive", new Object[] {it.getItemCd()}, loc), null, it);
            }
            case GS1:
                return view(r, true, null, messages.getMessage("wmsScan.lookup.gs1", null, loc), null, null);
            default:      // LOT·PLT — 2부(P2 LOT 마스터 병합 뒤)에 존재 확인
                return view(r, true, null, messages.getMessage("wmsScan.lookup.later", new Object[] {r.key()}, loc), null, null);
        }
    }

    private static ScanView view(Result r, boolean ok, String reason, String message, NodeHit n, ItemHit it) {
        Gs1 g = r.gs1();
        return new ScanView(r.type().name(), r.plantCd(), r.key(), r.bare(), ok, reason, message,
                n == null ? null : n.getNodeId(), n == null ? null : n.getPath(), n == null ? null : n.getNodeNm(),
                n == null ? null : n.getUseYn(), n == null ? null : n.getLocUsage(),
                it == null ? null : it.getItemId(), it == null ? null : it.getItemNm(), it == null ? null : it.getUseYn(),
                g == null ? null : g.gtin(), g == null ? null : g.sscc(), g == null ? null : g.batch(),
                g == null ? null : g.expiry(), g == null ? null : g.prodDate(), g == null ? null : g.serial(),
                g == null ? null : g.qty());
    }

    // ------------------------------------------------------------------ 엑셀

    public List<ColumnSpec> treeColumns() {
        return TREE_COLUMNS;
    }

    @Transactional(readOnly = true)
    public long export(ExportRequest req, HttpServletResponse response) throws IOException {
        ExportRequest r = req == null ? new ExportRequest(null, null, null) : req;
        List<LocRow> rows = tree(r.plantCd(), r.withUnused());
        List<ColumnSpec> cols = ColumnSpec.selectExportable(TREE_COLUMNS, r.columns());
        return excel.export(response, new ExportJob(PROG, "prog.wmsLoc", cols, rows.size(), List.of("plantCd")),
                w -> rows.forEach(w));
    }

    // ------------------------------------------------------------------ 공통

    private String plant(String plantCd) {
        if (blank(plantCd)) {
            throw fieldError("plantCd", "REQUIRED");
        }
        String p = plantCd.strip().toUpperCase(Locale.ROOT);
        if (p.length() > 20 || !mapper.plantExists(p)) {
            throw fieldError("plantCd", "NOT_FOUND");
        }
        return p;
    }

    private static String yn(String v, String field, FieldErrors e) {
        String s = blank(v) ? "Y" : v.strip();
        if (!"Y".equals(s) && !"N".equals(s)) {
            e.add(field, "CODE");
        }
        return s;
    }

    private static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
