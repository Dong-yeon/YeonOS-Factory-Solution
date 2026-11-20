package io.smarthappy.module.master.equip;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.equip.EquipDtos.ExportRequest;
import io.smarthappy.module.master.equip.EquipDtos.NodeForm;
import io.smarthappy.module.master.equip.EquipDtos.NodeRow;
import io.smarthappy.module.master.equip.EquipDtos.PlantForm;
import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.master.equip.EquipDtos.PlantSort;
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
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 공장 관리(mst-plant)·장비 계층(mst-equip-hier) — P1 4a, docs/19.
 *
 * 트리 규칙(부모 유형·순환·깊이·같은 회사·같은 공장·사용 중지)은 DB 트리거가 최종 판정한다. 서비스는 칸 형식만 먼저 보고,
 * 트리거·제약 위반을 칸 오류로 바꾼다 — 규칙을 두 곳에 두지 않는다(트리거 = 직접 SQL 까지 막는 한 곳).
 */
@Service
@RequiredArgsConstructor
public class EquipService {

    static final String PROG_PLANT = "mst-plant";
    static final String PROG_HIER = "mst-equip-hier";
    static final Pattern PLANT_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    static final Pattern NODE_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,29}$");
    static final Set<String> NODE_TYPES = Set.of("AREA", "LINE", "CELL", "WH", "LOC");

    /** 트리거·제약 이름 → (칸, 오류 코드). 설비·확정 라우팅이 걸린 노드 사용 중지는 V021·V022 트리거 */
    static final Map<String, String[]> CONSTRAINT_FIELD = Map.ofEntries(
            Map.entry("ck_mst_equip_hier_parent_type", new String[] {"nodeType", "PARENT_TYPE"}),
            Map.entry("ck_mst_equip_hier_cycle", new String[] {"parentId", "CYCLE"}),
            Map.entry("ck_mst_equip_hier_depth", new String[] {"parentId", "DEPTH"}),
            Map.entry("ck_mst_equip_hier_parent_use", new String[] {"parentId", "INACTIVE"}),
            Map.entry("ck_mst_equip_hier_plant_use", new String[] {"plantCd", "INACTIVE"}),
            Map.entry("ck_mst_equip_hier_children_use", new String[] {"useYn", "CHILDREN_ACTIVE"}),
            Map.entry("fk_mst_equip_hier_parent_plant", new String[] {"parentId", "NOT_FOUND"}),
            Map.entry("fk_mst_equip_hier_parent", new String[] {"parentId", "NOT_FOUND"}),
            Map.entry("fk_mst_equip_hier_plant", new String[] {"plantCd", "NOT_FOUND"}),
            Map.entry("uq_mst_equip_hier_cd", new String[] {"nodeCd", "DUPLICATE"}),
            Map.entry("ck_mst_equip_hier_routing_use", new String[] {"useYn", "HAS_ROUTING"}),
            Map.entry("ck_mst_equip_hier_equip_use", new String[] {"useYn", "NODE_HAS_EQUIP"}));

    static final List<ColumnSpec> TREE_COLUMNS = List.of(
            ColumnSpec.text("lvlMark", "equipHier.col.lvl", 70),
            ColumnSpec.text("nodeCd", "equipHier.col.nodeCd", 200),
            ColumnSpec.text("nodeNm", "equipHier.col.nodeNm", 200).withFlex().withMinWidth(120),
            ColumnSpec.code("nodeType", "equipHier.col.nodeType", "EQUIP_NODE_TYPE", 90),
            ColumnSpec.text("path", "equipHier.col.path", 240),
            ColumnSpec.of("sortNo", "equipHier.col.sortNo", ColumnSpec.ColumnType.INT, 60),
            ColumnSpec.of("useYn", "equipHier.col.useYn", ColumnSpec.ColumnType.YN, 70),
            ColumnSpec.text("remark", "equipHier.col.remark", 200));

    private final EquipMapper mapper;
    private final InboxService inbox;
    private final ExcelExportService excel;
    private final PolicyService policies;

    // ------------------------------------------------------------------ 공장

    /** 공장 그리드 기본 정렬 = 기존 목록 순서 */
    static final List<String> PLANT_SORT = List.of("sortNo,asc", "plantCd,asc");

    static final List<ColumnSpec> PLANT_COLUMNS = List.of(
            ColumnSpec.text("plantCd", "plant.col.plantCd", 100).withSort().withPriority(1),
            ColumnSpec.text("plantNm", "plant.col.plantNm", 160).withSort().withPriority(1).withFlex().withMinWidth(100),
            ColumnSpec.text("timeZone", "plant.col.timeZone", 130).withPriority(3),
            ColumnSpec.of("nodeCnt", "plant.col.nodeCnt", ColumnSpec.ColumnType.INT, 90).withSort().withPriority(2),
            ColumnSpec.of("sortNo", "plant.col.sortNo", ColumnSpec.ColumnType.INT, 60).withSort().withPriority(3),
            ColumnSpec.of("useYn", "plant.col.useYn", ColumnSpec.ColumnType.YN, 70).withPriority(2));

    public record PlantPage(List<PlantRow> list, Long total) {
    }

    /** 공장 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 기존 목록과 같은 조건 + 페이지·정렬·건수 */
    @Transactional(readOnly = true)
    public PlantPage plantGrid(String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, PlantSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), PLANT_SORT);
        if (useYn != null && !useYn.isBlank() && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String yn = useYn == null || useYn.isBlank() ? null : useYn;
        return new PlantPage(mapper.findPlantPage(yn, pq), pq.isWithTotal() ? mapper.countPlants(yn) : null);
    }

    public List<ColumnSpec> plantColumns() {
        return PLANT_COLUMNS;
    }

    @Transactional(readOnly = true)
    public List<PlantRow> plants(String useYn) {
        if (useYn != null && !useYn.isBlank() && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findPlants(useYn == null || useYn.isBlank() ? null : useYn);
    }

    @Transactional
    public String createPlant(PlantForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_PLANT_CREATE");
        PlantForm f = validatePlant(in, true);
        try {
            mapper.insertPlant(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("plantCd", "DUPLICATE");
        }
        return f.plantCd();
    }

    @Transactional
    public void updatePlant(String plantCd, PlantForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PlantRow cur = mapper.findPlant(plantCd);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        PlantForm f = validatePlant(new PlantForm(plantCd, in.plantNm(), in.addr(), in.timeZone(), in.sortNo(), in.useYn(),
                in.rowVer()), false);
        if ("N".equals(f.useYn()) && "Y".equals(cur.getUseYn()) && mapper.activeNodes(plantCd) > 0) {
            // 사용 중인 구역·창고가 있는 공장은 사용 중지하지 않는다 — 계층을 먼저 정리
            throw fieldError("useYn", "CHILDREN_ACTIVE");
        }
        if (mapper.updatePlant(f, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private PlantForm validatePlant(PlantForm in, boolean create) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = blank(in.plantCd()) ? null : in.plantCd().strip().toUpperCase(Locale.ROOT);
        if (cd == null) {
            e.add("plantCd", "REQUIRED");
        } else if (!PLANT_CD.matcher(cd).matches()) {
            e.add("plantCd", "FORMAT");
        }
        String nm = text(in.plantNm(), "plantNm", 100, true, e);
        String addr = text(in.addr(), "addr", 300, false, e);
        String tz = blank(in.timeZone()) ? null : in.timeZone().strip();
        if (tz != null) {
            try {
                ZoneId.of(tz);
            } catch (DateTimeException ex) {
                e.add("timeZone", "CODE");
            }
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        e.throwIfAny();
        return new PlantForm(cd, nm, addr, tz, sort, use, create ? null : in.rowVer());
    }

    // ------------------------------------------------------------------ 장비 계층

    @Transactional(readOnly = true)
    public List<NodeRow> tree(String plantCd, Boolean withUnused) {
        if (blank(plantCd) || mapper.findPlant(plantCd.strip()) == null) {
            throw fieldError("plantCd", "NOT_FOUND");
        }
        List<NodeRow> rows = mapper.findTree(plantCd.strip(), Boolean.TRUE.equals(withUnused));
        rows.forEach(r -> r.setLvlMark(". ".repeat(r.getLvl() - 1) + r.getLvl()));
        return rows;
    }

    @Transactional
    public long createNode(NodeForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_EQUIP_NODE_CREATE");
        NodeForm f = validateNode(in, null);
        try {
            return mapper.insertNode(f, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /** 수정·이동(부모 변경)·사용 여부. 유형은 만든 뒤 바꾸지 않는다(하위 부모 규칙이 깨진다) */
    @Transactional
    public void updateNode(long nodeId, NodeForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        NodeRow cur = mapper.findNode(nodeId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        NodeForm f = validateNode(in, cur);
        if ("Y".equals(f.useYn()) && "N".equals(cur.getUseYn()) && f.parentId() != null) {
            // 다시 사용: 상위가 사용 중이어야 한다(사용 중지 상위 아래 사용 중 하위 금지)
            NodeRow p = mapper.findNode(f.parentId());
            if (p != null && !"Y".equals(p.getUseYn())) {
                throw fieldError("parentId", "INACTIVE");
            }
        }
        try {
            if (mapper.updateNode(nodeId, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private NodeForm validateNode(NodeForm in, NodeRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = cur != null ? cur.getPlantCd() : blank(in.plantCd()) ? null : in.plantCd().strip();
        if (plant == null) {
            e.add("plantCd", "REQUIRED");
        }
        String type = cur != null ? cur.getNodeType() : in.nodeType();
        if (cur != null && in.nodeType() != null && !in.nodeType().equals(cur.getNodeType())) {
            e.add("nodeType", "FIXED");
        } else if (type == null || !NODE_TYPES.contains(type)) {
            e.add("nodeType", type == null ? "REQUIRED" : "CODE");
        }
        String cd = blank(in.nodeCd()) ? null : in.nodeCd().strip().toUpperCase(Locale.ROOT);
        if (cd == null) {
            e.add("nodeCd", "REQUIRED");
        } else if (!NODE_CD.matcher(cd).matches()) {
            e.add("nodeCd", "FORMAT");
        }
        String nm = text(in.nodeNm(), "nodeNm", 100, true, e);
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new NodeForm(plant, in.parentId(), type, cd, nm, sort, use, remark, in.rowVer());
    }

    private RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse(null);
        String[] fe = c == null ? null : CONSTRAINT_FIELD.get(c);
        if (fe == null) {
            return e;
        }
        return fieldError(fe[0], fe[1]);
    }

    // ------------------------------------------------------------------ 엑셀

    public List<ColumnSpec> treeColumns() {
        return TREE_COLUMNS;
    }

    @Transactional(readOnly = true)
    public long export(ExportRequest req, HttpServletResponse response) throws IOException {
        ExportRequest r = req == null ? new ExportRequest(null, null, null) : req;
        List<NodeRow> rows = tree(r.plantCd(), r.withUnused());
        List<ColumnSpec> cols = ColumnSpec.selectExportable(TREE_COLUMNS, r.columns());
        return excel.export(response, new ExportJob(PROG_HIER, "prog.mstEquipHier", cols, rows.size(), List.of("plantCd")),
                w -> rows.forEach(w));
    }

    // ------------------------------------------------------------------ 공통

    private static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
