package io.smarthappy.module.master.bom;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomDtos.BomRow;
import io.smarthappy.module.master.bom.BomDtos.ItemLookup;
import io.smarthappy.module.master.bom.BomTreeDtos.ExplodeExport;
import io.smarthappy.module.master.bom.BomTreeDtos.ExplodeResult;
import io.smarthappy.module.master.bom.BomTreeDtos.ExplodeRow;
import io.smarthappy.module.master.bom.BomTreeDtos.View;
import io.smarthappy.module.master.bom.BomTreeDtos.WhereExport;
import io.smarthappy.module.master.bom.BomTreeDtos.WhereResult;
import io.smarthappy.module.master.bom.BomTreeDtos.WhereRow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * BOM 전개·역전개 조회 (P1 3b, docs/18 Q1~Q7 · 5.2·5.3). 계산은 DB 재귀 CTE 한 번, 보기(단계·다단·합산)와 경로는 여기서.
 *
 * - 정전개 루트 = 기준일 유효 확정 BOM, 또는 지정한 BOM(작성 중 미리보기). 아래 단계는 항상 기준일 유효 확정 BOM
 * - 합산 = 말단(기준일에 유효 BOM 이 없는 자품목)별 필요량 합. 가상 조립은 통과(합산에서 빠지고 아래가 올라옴), 부산물은 산출로 따로
 * - 레벨 상한·행 상한(정책)을 넘으면 오류 — 잘린 결과를 정답처럼 보여 주지 않는다
 */
@Service
@RequiredArgsConstructor
public class BomTreeService {

    static final String PROG_TREE = "mst-bom-tree";
    static final String PROG_WHERE = "mst-bom-where";

    /** 단위는 코드 그대로(EA·KG) — 이름(킬로그램)은 좁은 칸에서 잘린다. 트리 열은 들여쓰기만큼 넓게 */
    static final List<ColumnSpec> EXPLODE_COLUMNS = List.of(
            // 레벨은 숫자 칸(정렬·엑셀 필터 — 점 표기 ". . 3" 은 8레벨부터 잘렸다, docs/60 D2·D3). 들여쓰기는 코드 칸이 맡는다
            ColumnSpec.of("lvl", "bomTree.col.lvl", ColumnSpec.ColumnType.INT, 50).withPriority(1),
            ColumnSpec.text("itemCd", "bomTree.col.itemCd", 190).withPriority(1),
            ColumnSpec.text("itemNm", "bomTree.col.itemNm", 200).withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.text("spec", "bomTree.col.spec", 140).withPriority(3),
            ColumnSpec.code("lineType", "bomTree.col.lineType", "BOM_LINE_TYPE", 80).withPriority(2),
            ColumnSpec.dec("qty", "bomTree.col.qty", 3, 90).withPriority(2),
            ColumnSpec.text("lineUnit", "bomTree.col.lineUnit", 60).withPriority(2),
            ColumnSpec.dec("scrapPct", "bomTree.col.scrapPct", 2, 70).withPriority(3),
            ColumnSpec.dec("reqQty", "bomTree.col.reqQty", 3, 110).withPriority(1),
            ColumnSpec.text("itemUnit", "bomTree.col.itemUnit", 60).withPriority(1),
            ColumnSpec.text("childRev", "bomTree.col.childRev", 70).withPriority(3),
            ColumnSpec.code("issueMethod", "bomTree.col.issueMethod", "ISSUE_METHOD", 90).withPriority(3),
            ColumnSpec.text("altGrp", "bomTree.col.altGrp", 70).withPriority(3),
            ColumnSpec.text("parentCd", "bomTree.col.parentCd", 140).withPriority(3),
            ColumnSpec.text("path", "bomTree.col.path", 320).withPriority(3));

    static final List<ColumnSpec> WHERE_COLUMNS = List.of(
            ColumnSpec.of("lvl", "bomTree.col.lvl", ColumnSpec.ColumnType.INT, 50).withPriority(1),
            ColumnSpec.text("parentCd", "bomWhere.col.parentCd", 190).withPriority(1),
            ColumnSpec.text("parentNm", "bomWhere.col.parentNm", 200).withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.code("parentType", "bomWhere.col.parentType", "ITEM_TYPE", 80).withPriority(2),
            ColumnSpec.text("rev", "bomWhere.col.rev", 70).withPriority(1),
            ColumnSpec.code("status", "bomWhere.col.status", "BOM_STATUS", 80).withPriority(2),
            ColumnSpec.of("validFrom", "bomWhere.col.validFrom", ColumnSpec.ColumnType.DATE, 100).withPriority(3),
            ColumnSpec.of("validTo", "bomWhere.col.validTo", ColumnSpec.ColumnType.DATE, 100).withPriority(3),
            ColumnSpec.text("childCd", "bomWhere.col.childCd", 150).withPriority(2),
            ColumnSpec.dec("qty", "bomWhere.col.qty", 3, 90).withPriority(1),
            ColumnSpec.text("lineUnit", "bomWhere.col.lineUnit", 60).withPriority(2),
            // 예/아니오(LABEL) — YN 은 엑셀에서 "사용/미사용"(사용 여부 칸용)이라 boolean 이 "true/false"·"미사용"으로 나갔다(docs/60 D3)
            ColumnSpec.labels("top", "bomWhere.col.top", "bomWhere.top.", 70, "true", "false").withPriority(2),
            ColumnSpec.text("path", "bomTree.col.path", 320).withPriority(3));

    private final BomMapper mapper;
    private final PolicyService policies;
    private final BizClock clock;
    private final ExcelExportService excel;

    // ------------------------------------------------------------------ 정전개

    @Transactional(readOnly = true)
    public ExplodeResult explode(String itemCd, Long bomId, String date, String qty, String view) {
        FieldErrors e = FieldErrors.create();
        LocalDate d = BomService.date(date, "date", e);
        BigDecimal q = qty == null || qty.isBlank() ? BigDecimal.ONE
                : BomService.decimal(qty, "qty", BomService.QTY_SCALE, BomService.QTY_INT_DIGITS, true, e);
        View v = view(view, e);
        e.throwIfAny();
        LocalDate on = d == null ? clock.today() : d;
        BomRow root;
        if (bomId != null) {
            root = mapper.findById(bomId);
            if (root == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
        } else {
            ItemLookup it = item(itemCd);
            root = mapper.findEffective(it.getItemId(), on);
            if (root == null) {
                throw new BusinessException(HbErrorCode.BOM_NO_EFFECTIVE, it.getItemCd(), on.toString());
            }
        }
        int maxLevel = policies.intValue(Policies.BOM_MAX_LEVEL);
        int maxRows = policies.intValue(Policies.BOM_EXPLODE_MAX_ROWS);
        List<ExplodeRow> rows = mapper.explode(root.getBomId(), on, q, v == View.SINGLE ? 1 : maxLevel + 1, maxRows + 1);
        if (rows.size() > maxRows) {
            throw new BusinessException(HbErrorCode.BOM_TOO_MANY_ROWS, maxRows);
        }
        if (rows.stream().anyMatch(r -> r.getLvl() > maxLevel)) {
            throw new BusinessException(HbErrorCode.BOM_TOO_DEEP, maxLevel);
        }
        marks(rows, root.getItemCd());
        List<ExplodeRow> out = v == View.SUM ? sum(rows) : rows;
        return new ExplodeResult(root.getItemCd(), root.getItemNm(), root.getItemUnit(), root.getRev(), on, q, v, maxLevel,
                out);
    }

    /** 레벨 표기(". . 3")·경로(모품목 > … > 자품목) — 정렬된 결과에서 레벨마다 마지막 품목을 쌓아 만든다 */
    static void marks(List<ExplodeRow> rows, String rootCd) {
        List<String> stack = new ArrayList<>();
        stack.add(rootCd);
        for (ExplodeRow r : rows) {
            while (stack.size() > r.getLvl()) {
                stack.remove(stack.size() - 1);
            }
            stack.add(r.getItemCd());
            r.setPath(String.join(" > ", stack));
            r.setLvlMark(lvlMark(r.getLvl()));
        }
    }

    static String lvlMark(int lvl) {
        return ". ".repeat(Math.max(0, lvl - 1)) + lvl;
    }

    /** 합산: 말단 자재(유효 BOM 없음)·부산물별 합. 가상 조립·중간 조립은 빠진다 */
    static List<ExplodeRow> sum(List<ExplodeRow> rows) {
        Map<String, ExplodeRow> acc = new LinkedHashMap<>();
        for (ExplodeRow r : rows) {
            boolean byproduct = "BYPRODUCT".equals(r.getLineType());
            boolean leaf = r.getChildRev() == null || byproduct;
            if (!leaf || r.isCycle()) {
                continue;
            }
            String key = r.getItemId() + "|" + (byproduct ? "B" : "N");
            ExplodeRow a = acc.get(key);
            if (a == null) {
                a = new ExplodeRow();
                a.setLvl(1);
                a.setLvlMark("");
                a.setItemId(r.getItemId());
                a.setItemCd(r.getItemCd());
                a.setItemNm(r.getItemNm());
                a.setSpec(r.getSpec());
                a.setItemType(r.getItemType());
                a.setLineType(byproduct ? "BYPRODUCT" : "NORMAL");
                a.setItemUnit(r.getItemUnit());
                a.setIssueMethod(r.getIssueMethod());
                a.setReqQty(BigDecimal.ZERO);
                a.setPath("");
                acc.put(key, a);
            }
            a.setReqQty(a.getReqQty().add(r.getReqQty()));
            a.setNoConv(a.isNoConv() || r.isNoConv());
            // 어디에 쓰이는지(직접 상위 품목) 목록 — 합산 행의 출처
            LinkedHashSet<String> parents = new LinkedHashSet<>(a.getParentCd() == null ? List.of()
                    : List.of(a.getParentCd().split(", ")));
            parents.add(r.getParentCd());
            a.setParentCd(String.join(", ", parents));
        }
        List<ExplodeRow> out = new ArrayList<>(acc.values());
        // 투입 자재 먼저(품목코드 순), 부산물은 뒤
        out.sort((x, y) -> {
            int t = x.getLineType().compareTo(y.getLineType());
            return t != 0 ? -t : x.getItemCd().compareTo(y.getItemCd());
        });
        return out;
    }

    // ------------------------------------------------------------------ 역전개

    @Transactional(readOnly = true)
    public WhereResult whereUsed(String itemCd, String date, Boolean allRevs, Boolean topOnly) {
        FieldErrors e = FieldErrors.create();
        LocalDate d = BomService.date(date, "date", e);
        e.throwIfAny();
        ItemLookup it = item(itemCd);
        LocalDate on = d == null ? clock.today() : d;
        boolean all = Boolean.TRUE.equals(allRevs);
        boolean top = Boolean.TRUE.equals(topOnly);
        int maxLevel = policies.intValue(Policies.BOM_MAX_LEVEL);
        int maxRows = policies.intValue(Policies.BOM_EXPLODE_MAX_ROWS);
        List<WhereRow> rows = mapper.whereUsed(it.getItemId(), on, all, maxLevel + 1, maxRows + 1);
        if (rows.size() > maxRows) {
            throw new BusinessException(HbErrorCode.BOM_TOO_MANY_ROWS, maxRows);
        }
        if (rows.stream().anyMatch(r -> r.getLvl() > maxLevel)) {
            throw new BusinessException(HbErrorCode.BOM_TOO_DEEP, maxLevel);
        }
        // 최상위 = 이 결과 안에서 자품목으로 나오지 않는 모품목 (SQL 상관 서브쿼리는 O(행²) — 매퍼 주석)
        Set<Long> used = new HashSet<>();
        rows.forEach(r -> used.add(r.getChildId()));
        rows.forEach(r -> r.setTop(!used.contains(r.getParentId())));
        List<String> stack = new ArrayList<>();
        stack.add(it.getItemCd());
        for (WhereRow r : rows) {
            while (stack.size() > r.getLvl()) {
                stack.remove(stack.size() - 1);
            }
            stack.add(r.getParentCd());
            r.setPath(String.join(" < ", stack));
            r.setLvlMark(lvlMark(r.getLvl()));
        }
        List<WhereRow> out = rows;
        if (top) {
            // 최상위만 — 여러 경로로 닿아도 모품목·리비전마다 한 줄
            Map<Long, WhereRow> tops = new LinkedHashMap<>();
            rows.stream().filter(WhereRow::isTop).forEach(r -> tops.putIfAbsent(r.getBomId(), r));
            out = new ArrayList<>(tops.values());
            out.forEach(r -> r.setLvlMark(""));
            out.sort((x, y) -> x.getParentCd().compareTo(y.getParentCd()));
        }
        Integer llc = BomChangeService.computeLlc(mapper.edges()).get(it.getItemId());
        return new WhereResult(it.getItemCd(), it.getItemNm(), on, all, top, llc, out);
    }

    public List<ColumnSpec> explodeColumns() {
        return EXPLODE_COLUMNS;
    }

    public List<ColumnSpec> whereColumns() {
        return WHERE_COLUMNS;
    }

    // ------------------------------------------------------------------ 엑셀 (화면과 같은 결과 그대로)

    @Transactional(readOnly = true)
    public long exportExplode(ExplodeExport req, HttpServletResponse response) throws IOException {
        ExplodeExport r = req == null ? new ExplodeExport(null, null, null, null, null, null) : req;
        ExplodeResult res = explode(r.itemCd(), r.bomId(), r.date(), r.qty(), r.view());
        List<ColumnSpec> cols = ColumnSpec.selectExportable(EXPLODE_COLUMNS, r.columns());
        return excel.export(response, new ExportJob(PROG_TREE, "prog.mstBomTree", cols, res.rows().size(),
                List.of("itemCd", "date", "qty", "view")), w -> res.rows().forEach(w));
    }

    @Transactional(readOnly = true)
    public long exportWhere(WhereExport req, HttpServletResponse response) throws IOException {
        WhereExport r = req == null ? new WhereExport(null, null, null, null, null) : req;
        WhereResult res = whereUsed(r.itemCd(), r.date(), r.allRevs(), r.topOnly());
        List<ColumnSpec> cols = ColumnSpec.selectExportable(WHERE_COLUMNS, r.columns());
        return excel.export(response, new ExportJob(PROG_WHERE, "prog.mstBomWhere", cols, res.rows().size(),
                List.of("itemCd", "date")), w -> res.rows().forEach(w));
    }

    // ------------------------------------------------------------------ 공통

    private ItemLookup item(String itemCd) {
        if (itemCd == null || itemCd.isBlank()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("itemCd", "REQUIRED").list());
        }
        ItemLookup it = mapper.findItemByCd(itemCd.strip().toUpperCase(Locale.ROOT));
        if (it == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("itemCd", "NOT_FOUND").list());
        }
        return it;
    }

    private static View view(String raw, FieldErrors e) {
        if (raw == null || raw.isBlank()) {
            return View.MULTI;
        }
        try {
            return View.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            e.add("view", "CODE");
            return View.MULTI;
        }
    }
}
