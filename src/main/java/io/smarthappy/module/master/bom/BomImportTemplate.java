package io.smarthappy.module.master.bom;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.module.master.bom.BomDtos.HeaderValue;
import io.smarthappy.module.master.bom.BomDtos.ItemLookup;
import io.smarthappy.module.master.bom.BomDtos.LineItem;
import io.smarthappy.module.master.bom.BomDtos.LineValue;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.importer.ImportCodes;
import io.smarthappy.platform.importer.ImportTemplate;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * BOM 엑셀 올리기 (P1 3a, docs/18 C8·§10.1) — 처음 이관·신제품 대량 등록용. 단단계 쌍 형식(한 행 = 모품목·순번·자품목).
 *
 * - 업무 키 = 모품목|순번. 대상은 그 모품목의 **작성 중(DRAFT)** BOM 만 — 없으면 실행할 때 새 작성 중 리비전을 만든다.
 *   확정본은 건드리지 않는다(바꾸려면 새 리비전으로 복사 → 올리기 → 화면에서 확정)
 * - 검증은 화면 저장과 같은 BomService.validateLine. 품목·단위·BOM 구조는 세션을 열 때 한 번 읽고,
 *   **파일 안 순환**(1행 A←B, 2행 B←A)은 앞 행에서 쌓은 간선까지 합쳐 메모리에서 본다. 실행 때는 DB 트리거가 한 번 더 막는다
 * - 확정은 올리기로 하지 않는다 — 화면에서 확정 검사를 지나야 한다
 */
@Component
@RequiredArgsConstructor
public class BomImportTemplate implements ImportTemplate {

    static final String CODE = "MST_BOM";
    /** 검증 결과에만 있는 숨은 값(파일 열 아님) */
    static final String TARGET = "_targetBomId";

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("parentCd", "mstBom.col.itemCd", true, "help.mst-bom.itemCd", null, "64310-DM100").asText(),
            new ImportColumn("lineNo", "js.mstBom.col.lineNo", true, "import.guide.bomLineNo", null, "10"),
            new ImportColumn("itemCd", "js.mstBom.col.itemCd", true, null, null, "64311-DM100").asText(),
            new ImportColumn("qty", "js.mstBom.col.qty", true, "import.guide.bomQty", null, "1"),
            new ImportColumn("unitCd", "js.mstBom.col.unitCd", false, "import.guide.bomUnit", "UNIT", "EA"),
            new ImportColumn("lineType", "js.mstBom.col.lineType", false, null, "BOM_LINE_TYPE", "NORMAL"),
            new ImportColumn("scrapPct", "js.mstBom.col.scrapPct", false, null, null, "0"),
            new ImportColumn("fixedYn", "js.mstBom.col.fixedYn", false, null, null, "N"),
            new ImportColumn("issueMethod", "js.mstBom.col.issueMethod", false, null, "ISSUE_METHOD", null),
            new ImportColumn("opSeq", "js.mstBom.col.opSeq", false, null, null, null),
            new ImportColumn("altGrp", "js.mstBom.col.altGrp", false, null, null, null),
            new ImportColumn("altPriority", "js.mstBom.col.altPriority", false, null, null, null),
            new ImportColumn("altPct", "js.mstBom.col.altPct", false, null, null, null),
            new ImportColumn("position", "js.mstBom.col.position", false, null, null, null),
            new ImportColumn("remark", "js.mstBom.col.remark", false, null, null, null));

    private final BomService service;
    private final BomMapper mapper;
    private final CodeService codes;
    private final JdbcTemplate jdbc;
    private final BizClock clock;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String progCd() {
        return BomService.PROG;
    }

    @Override
    public String titleKey() {
        return "prog.mstBom";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    @Override
    public ImportSession open() {
        Map<String, ItemLookup> items = new HashMap<>();
        Map<Long, String> itemCd = new HashMap<>();
        jdbc.query("select item_id, item_cd, item_nm, spec, item_type, unit_cd, lot_ctrl_yn, use_yn from mst_item", rs -> {
            ItemLookup it = new ItemLookup();
            it.setItemId(rs.getLong("item_id"));
            it.setItemCd(rs.getString("item_cd"));
            it.setItemNm(rs.getString("item_nm"));
            it.setSpec(rs.getString("spec"));
            it.setItemType(rs.getString("item_type"));
            it.setUnitCd(rs.getString("unit_cd"));
            it.setLotCtrlYn(rs.getString("lot_ctrl_yn"));
            it.setUseYn(rs.getString("use_yn"));
            items.put(it.getItemCd(), it);
            itemCd.put(it.getItemId(), it.getItemCd());
        });
        // 구조(미폐기 모든 리비전·대체) — 순환 검사 기준. 파일 행은 검증하면서 더한다
        Map<Long, Set<Long>> children = new HashMap<>();
        jdbc.query("""
                select b.item_id as parent_id, l.item_id as child_id
                  from mst_bom b join mst_bom_line l on l.bom_id = b.bom_id
                 where b.status <> 'OBSOLETE'""",
                rs -> {
                    children.computeIfAbsent(rs.getLong("parent_id"), k -> new HashSet<>()).add(rs.getLong("child_id"));
                });
        // 작성 중 BOM(대체 1)과 그 행 — 키 = 모품목|순번
        Map<String, Long> drafts = new HashMap<>();
        Map<String, Existing> existing = new HashMap<>();
        jdbc.query("""
                select i.item_cd as parent_cd, b.bom_id, l.line_id, l.row_ver, l.line_no, c.item_cd, l.qty, l.unit_cd,
                       l.line_type, l.scrap_pct, l.fixed_yn, l.issue_method, l.op_seq, l.alt_grp, l.alt_priority, l.alt_pct,
                       l.position, l.remark
                  from mst_bom b
                  join mst_item i on i.item_id = b.item_id
                  left join mst_bom_line l on l.bom_id = b.bom_id
                  left join mst_item c on c.item_id = l.item_id
                 where b.status = 'DRAFT' and b.alt_no = 1""", rs -> {
            String parent = rs.getString("parent_cd");
            drafts.put(parent, rs.getLong("bom_id"));
            if (rs.getObject("line_id") == null) {
                return;
            }
            Map<String, String> v = new LinkedHashMap<>();
            v.put("parentCd", parent);
            v.put("lineNo", rs.getString("line_no"));
            v.put("itemCd", rs.getString("item_cd"));
            v.put("qty", plain(rs.getBigDecimal("qty")));
            v.put("unitCd", rs.getString("unit_cd"));
            v.put("lineType", rs.getString("line_type"));
            v.put("scrapPct", plain(rs.getBigDecimal("scrap_pct")));
            v.put("fixedYn", rs.getString("fixed_yn"));
            v.put("issueMethod", rs.getString("issue_method"));
            v.put("opSeq", rs.getString("op_seq"));
            v.put("altGrp", rs.getString("alt_grp"));
            v.put("altPriority", rs.getString("alt_priority"));
            v.put("altPct", plain(rs.getBigDecimal("alt_pct")));
            v.put("position", rs.getString("position"));
            v.put("remark", rs.getString("remark"));
            existing.put(parent + "|" + rs.getInt("line_no"), new Existing(rs.getLong("line_id"), rs.getInt("row_ver"), v));
        });
        ImportCodes units = new ImportCodes(codes, "UNIT");
        ImportCodes types = new ImportCodes(codes, "BOM_LINE_TYPE");
        ImportCodes issues = new ImportCodes(codes, "ISSUE_METHOD");
        Set<Long> touched = new HashSet<>();
        Set<Long> created = new HashSet<>();

        return new ImportSession() {
            @Override
            public Existing find(String key) {
                return key == null ? null : existing.get(key);
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                FieldErrors e = FieldErrors.create();
                FieldErrors.RowScope row = e.row(null);
                String parentCd = upper(cells.get("parentCd"));
                ItemLookup parent = parentCd == null ? null : items.get(parentCd);
                if (parentCd == null) {
                    e.add("parentCd", "REQUIRED");
                } else if (parent == null) {
                    e.add("parentCd", "NOT_FOUND");
                } else if (!"Y".equals(parent.getUseYn())) {
                    e.add("parentCd", "INACTIVE");
                }
                Integer lineNo = integer(cells.get("lineNo"), "lineNo", e);
                Integer opSeq = integer(cells.get("opSeq"), "opSeq", e);
                Integer prio = integer(cells.get("altPriority"), "altPriority", e);
                String key = parent == null || lineNo == null ? null : parentCd + "|" + lineNo;
                Existing cur = key == null ? null : existing.get(key);
                LineItem in = new LineItem("x", null, null, lineNo, cells.get("itemCd"), types.resolve(cells.get("lineType")),
                        cells.get("qty"), units.resolve(cells.get("unitCd")), cells.get("scrapPct"),
                        ImportCodes.yn(cells.get("fixedYn")), issues.resolve(cells.get("issueMethod")), opSeq,
                        cells.get("altGrp"), prio, cells.get("altPct"), cells.get("position"), cells.get("remark"));
                LineValue v = null;
                if (parent != null) {
                    String curChild = cur == null ? null : cur.values().get("itemCd");
                    Long curItemId = curChild == null || items.get(curChild) == null ? null : items.get(curChild).getItemId();
                    v = service.validateLine(in, parent.getItemId(), curItemId,
                            cur == null ? null : cur.values().get("unitCd"), items::get,
                            (p, c) -> reaches(children, c, p), units::contains, row);
                }
                if (v != null && e.isEmpty()) {
                    // 앞 행에서 만든 구조도 다음 행의 순환 검사에 들어간다
                    children.computeIfAbsent(parent.getItemId(), k -> new HashSet<>()).add(v.itemId());
                }
                Map<String, String> out = new LinkedHashMap<>();
                out.put("parentCd", parentCd);
                out.put("lineNo", lineNo == null ? null : String.valueOf(lineNo));
                out.put("itemCd", v == null ? upper(cells.get("itemCd")) : itemCd.get(v.itemId()));
                out.put("qty", v == null ? null : plain(v.qty()));
                out.put("unitCd", v == null ? null : v.unitCd());
                out.put("lineType", v == null ? null : v.lineType());
                out.put("scrapPct", v == null ? null : plain(v.scrapPct()));
                out.put("fixedYn", v == null ? null : v.fixedYn());
                out.put("issueMethod", v == null ? null : v.issueMethod());
                out.put("opSeq", v == null || v.opSeq() == null ? null : String.valueOf(v.opSeq()));
                out.put("altGrp", v == null ? null : v.altGrp());
                out.put("altPriority", v == null || v.altPriority() == null ? null : String.valueOf(v.altPriority()));
                out.put("altPct", v == null ? null : plain(v.altPct()));
                out.put("position", v == null ? null : v.position());
                out.put("remark", v == null ? null : v.remark());
                // 검증 때의 대상(작성 중 BOM id, 없으면 비움) — 실행 때 달라졌으면(그 사이 확정·새로 생김) 전부 취소
                Long target = parentCd == null ? null : drafts.get(parentCd);
                out.put(TARGET, target == null ? null : String.valueOf(target));
                boolean keyOk = e.list().stream().noneMatch(x -> "parentCd".equals(x.field()) || "lineNo".equals(x.field()));
                return new Validated(keyOk ? key : null, out, e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                long bomId = draftOf(v.get("parentCd"), v.get(TARGET), by);
                return mapper.insertLine(bomId, value(v, null, null), by);
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                // 고칠 행은 엔진이 실행 직전 다시 찾았다(find — 지금의 작성 중 BOM 에서). 파일 열만 덮은 값이라 숨은 값은 없다
                long bomId = draftOf(v.get("parentCd"), String.valueOf(drafts.get(v.get("parentCd"))), by);
                return mapper.updateLine(bomId, value(v, id, rowVer), by);
            }

            @Override
            public long count() {
                Long n = jdbc.queryForObject("select count(*) from mst_bom_line", Long.class);
                return n == null ? 0 : n;
            }

            /** 그 모품목의 작성 중 BOM — 없으면 만든다. 처음 쓸 때 머리 버전을 올려 화면에서 동시에 고치던 사람이 409 를 받게 */
            private long draftOf(String parentCd, String expected, String by) {
                ItemLookup p = items.get(parentCd);
                Long id = drafts.get(parentCd);
                boolean stale = expected == null ? id != null && !created.contains(id)
                        : id == null || !expected.equals(String.valueOf(id));
                if (stale) {
                    // 검증 때 본 작성 중 BOM 이 확정·폐기됐거나, 없던 작성 중 BOM 을 누가 만들었다
                    throw new BusinessException(HbErrorCode.BOM_NOT_DRAFT);
                }
                if (id == null) {
                    id = mapper.insertHeader(new HeaderValue(p.getItemId(), 1, service.nextRev(p.getItemId(), 1),
                            BigDecimal.ONE, clock.today(), null, "엑셀 올리기", null, null), by);
                    created.add(id);
                    touched.add(id);
                }
                drafts.put(parentCd, id);
                if (touched.add(id) && jdbc.update("""
                        update mst_bom set row_ver = row_ver + 1, updated_at = now(), updated_by = ?
                         where bom_id = ? and status = 'DRAFT'""", by, id) == 0) {
                    // 검증 뒤 누군가 그 작성 중 BOM 을 확정·폐기했다 — 전체 롤백
                    throw new BusinessException(HbErrorCode.BOM_NOT_DRAFT);
                }
                return id;
            }
        };
    }

    /** from 아래(자기 포함)에 target 이 있는가 — 메모리 그래프, 이미 있는 순환에서도 끝난다 */
    static boolean reaches(Map<Long, Set<Long>> children, long from, long target) {
        Deque<Long> todo = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        todo.push(from);
        while (!todo.isEmpty()) {
            long n = todo.pop();
            if (n == target) {
                return true;
            }
            if (seen.add(n)) {
                children.getOrDefault(n, Set.of()).forEach(todo::push);
            }
        }
        return false;
    }

    private LineValue value(Map<String, String> v, Long lineId, Integer rowVer) {
        ItemLookup child = mapper.findItemByCd(v.get("itemCd"));
        return new LineValue(null, lineId, rowVer, Integer.parseInt(v.get("lineNo")), child.getItemId(), v.get("lineType"),
                new BigDecimal(v.get("qty")), v.get("unitCd"), dec(v.get("scrapPct")), v.get("fixedYn"),
                v.get("issueMethod"), v.get("opSeq") == null ? null : Integer.valueOf(v.get("opSeq")), v.get("altGrp"),
                v.get("altPriority") == null ? null : Integer.valueOf(v.get("altPriority")), dec(v.get("altPct")),
                v.get("position"), v.get("remark"));
    }

    private static BigDecimal dec(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private static String plain(BigDecimal d) {
        return d == null ? null : d.stripTrailingZeros().toPlainString();
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    /** 정수 칸 — 엑셀이 10 을 "10.0" 으로 줄 수도 있어 끝의 .0 은 받는다 */
    private static Integer integer(String raw, String field, FieldErrors e) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip().replaceFirst("\\.0+$", "");
        if (!s.matches("^[0-9]{1,6}$")) {
            e.add(field, "FORMAT");
            return null;
        }
        return Integer.valueOf(s);
    }
}
