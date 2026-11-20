package io.smarthappy.module.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.module.sales.SalesDtos.CustRef;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.module.sales.SalesDtos.LineValue;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.importer.ImportTemplate;
import lombok.RequiredArgsConstructor;

/**
 * 수주 엑셀 올리기 (P2 2단계, docs/25 4.3). 한 행 = 수주 행 하나.
 *
 * - 업무 키 = 거래처 | 고객 발주 번호 | 행 번호 — 발주 번호가 수주를 찾는 열쇠라 필수
 * - 새 (거래처, 발주) → 실행 때 수주 머리를 채번해 만들고 행을 붙인다. 있는 발주의 새 행 → 행 추가(열린 수주만)
 * - 있는 행 → 수량·납기·비고만 바꾼다(열린 행만). 품목은 바꾸지 않는다(FIXED), 수주일은 새 수주에만(다르면 FIXED)
 * - 검증은 화면 저장과 같은 OrderService 규칙(거래처·품목·수량 자릿수·날짜)
 */
@Component
@RequiredArgsConstructor
public class OrderImportTemplate implements ImportTemplate {

    static final String CODE = "SAL_ORDER";

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("custCd", "salOrder.col.custCd", true, "import.guide.salCust", null, "C-001").asText(),
            new ImportColumn("custPoNo", "salOrder.col.custPoNo", true, "import.guide.salPoNo", null, "PO-2610-001").asText(),
            new ImportColumn("lineNo", "salOrder.col.lineNo", true, "import.guide.salLineNo", null, "10"),
            new ImportColumn("itemCd", "salOrder.col.itemCd", true, "import.guide.salItem", null, "64310-DM100").asText(),
            new ImportColumn("qty", "salOrder.col.qty", true, "import.guide.salQty", null, "1200"),
            new ImportColumn("dueDate", "salOrder.col.dueDate", true, "import.guide.date", null, "2026-11-30").asText(),
            new ImportColumn("orderDate", "salOrder.col.orderDate", false, "import.guide.salOrderDate", null, "2026-10-20").asText(),
            new ImportColumn("remark", "salOrder.col.remark", false, null, null, null));

    private final OrderService orders;
    private final SalesMapper mapper;
    private final JdbcTemplate jdbc;

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
        return OrderService.PROG;
    }

    @Override
    public String titleKey() {
        return "prog.salOrder";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    /** 기존 행 + 그 행의 상태·수주 상태(검증용) */
    private record LineRef(Existing ex, long orderId, String lineStatus, String orderStatus) {
    }

    @Override
    public ImportSession open() {
        Map<String, LineRef> lines = new HashMap<>();
        // (거래처 | 발주) → (수주 상태, 수주일) — 새 행을 붙일 수주가 끝났는지·수주일이 같은지
        Map<String, String[]> orderByPo = new HashMap<>();
        jdbc.query("""
                select l.line_id, l.row_ver, l.line_no, l.qty, l.due_date, l.remark, l.status, i.item_cd,
                       o.order_id, o.cust_po_no, o.order_date, o.status as order_status, c.cust_cd
                  from sal_order o join mst_cust c on c.cust_id = o.cust_id
                  join sal_order_line l on l.order_id = o.order_id
                  join mst_item i on i.item_id = l.item_id
                 where o.cust_po_no is not null""", rs -> {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("custCd", rs.getString("cust_cd"));
            v.put("custPoNo", rs.getString("cust_po_no"));
            v.put("lineNo", String.valueOf(rs.getInt("line_no")));
            v.put("itemCd", rs.getString("item_cd"));
            v.put("qty", plain(rs.getBigDecimal("qty")));
            v.put("dueDate", rs.getObject("due_date", LocalDate.class).toString());
            v.put("orderDate", rs.getObject("order_date", LocalDate.class).toString());
            v.put("remark", rs.getString("remark"));
            String key = key(v.get("custCd"), v.get("custPoNo"), v.get("lineNo"));
            lines.put(key, new LineRef(new Existing(rs.getLong("line_id"), rs.getInt("row_ver"), v), rs.getLong("order_id"),
                    rs.getString("status"), rs.getString("order_status")));
            orderByPo.put(v.get("custCd") + "|" + v.get("custPoNo"), new String[] {rs.getString("order_status"), v.get("orderDate")});
        });
        Map<String, Integer> scales = orders.unitScales();
        // 파일 안 새 수주의 수주일(같은 발주의 행끼리 같아야 한다)
        Map<String, String> newOrderDates = new HashMap<>();
        return new ImportSession() {
            @Override
            public Existing find(String key) {
                LineRef r = key == null ? null : lines.get(key);
                return r == null ? null : r.ex();
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                FieldErrors e = FieldErrors.create();
                CustRef cust = orders.cust(cells.get("custCd"), e);
                String po = VisibleText.isBlank(cells.get("custPoNo")) ? null : cells.get("custPoNo").strip();
                if (po == null) {
                    e.add("custPoNo", "REQUIRED");
                } else if (po.length() > 50) {
                    e.add("custPoNo", "TOO_LONG", 50);
                } else if (!OrderService.PO_NO.matcher(po).matches()) {
                    e.add("custPoNo", "FORMAT");
                }
                Integer lineNo = lineNo(cells.get("lineNo"), e);
                String custCd = cust == null ? null : cust.getCustCd();
                String key = custCd == null || po == null || lineNo == null ? null : key(custCd, po, String.valueOf(lineNo));
                LineRef cur = key == null ? null : lines.get(key);
                String[] order = custCd == null || po == null ? null : orderByPo.get(custCd + "|" + po);

                Map<String, String> v = new LinkedHashMap<>();
                v.put("custCd", custCd);
                v.put("custPoNo", po);
                v.put("lineNo", lineNo == null ? null : String.valueOf(lineNo));
                String itemCd = VisibleText.isBlank(cells.get("itemCd")) ? null : cells.get("itemCd").strip().toUpperCase(Locale.ROOT);
                ItemOption item = null;
                if (cur != null) {
                    // 있는 행: 품목은 그대로여야 한다
                    if (itemCd != null && !itemCd.equals(cur.ex().values().get("itemCd"))) {
                        e.add("itemCd", "FIXED");
                    }
                    itemCd = cur.ex().values().get("itemCd");
                } else {
                    item = orders.item(cells.get("itemCd"), "itemCd", e);
                    itemCd = item == null ? itemCd : item.getItemCd();
                }
                v.put("itemCd", itemCd);
                String unit = item != null ? item.getUnitCd() : cur != null ? mapper.findItem(itemCd).getUnitCd() : null;
                BigDecimal qty = OrderService.qty(cells.get("qty"), "qty", OrderService.scaleOf(scales, unit), true, e);
                if (qty == null && VisibleText.isBlank(cells.get("qty")) && cur == null) {
                    e.add("qty", "REQUIRED");
                }
                v.put("qty", qty == null ? null : plain(qty));
                LocalDate due = OrderService.date(cells.get("dueDate"), "dueDate", cur == null, e);
                v.put("dueDate", due == null ? null : due.toString());
                LocalDate od = OrderService.date(cells.get("orderDate"), "orderDate", false, e);
                if (order != null) {
                    // 있는 수주: 수주일은 바꾸지 않는다, 끝난 수주에 새 행 불가
                    if (od != null && !od.toString().equals(order[1])) {
                        e.add("orderDate", "FIXED");
                    }
                    v.put("orderDate", order[1]);
                    if (cur == null && !"OPEN".equals(order[0])) {
                        e.add("custPoNo", "CLOSED");
                    }
                } else if (custCd != null && po != null) {
                    String d = od == null ? null : od.toString();
                    String prev = newOrderDates.putIfAbsent(custCd + "|" + po, d == null ? "" : d);
                    if (prev != null && !prev.equals(d == null ? "" : d)) {
                        e.add("orderDate", "SAME_ORDER");
                    }
                    v.put("orderDate", d);
                }
                v.put("remark", OrderService.text(cells.get("remark"), "remark", 1000, e));
                // 끝난 행은 바꿀 수 없다 — 파일 값이 지금과 같으면(SAME) 그대로 통과
                if (cur != null && !"OPEN".equals(cur.lineStatus()) && changed(cur.ex().values(), v, cells)) {
                    e.add("qty", "CLOSED");
                }
                return new Validated(e.list().stream().anyMatch(x -> List.of("custCd", "custPoNo", "lineNo").contains(x.field()))
                        ? null : key, v, e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                CustRef c = mapper.findCust(v.get("custCd"));
                LocalDate od = v.get("orderDate") == null ? null : LocalDate.parse(v.get("orderDate"));
                long orderId = orders.orderForImport(c.getCustId(), v.get("custPoNo"), od, by);
                orders.lockOpen(orderId);
                ItemOption item = mapper.findItem(v.get("itemCd"));
                return mapper.insertLine(orderId, new LineValue(Integer.valueOf(v.get("lineNo")), item.getItemId(),
                        new BigDecimal(v.get("qty")), LocalDate.parse(v.get("dueDate")), v.get("remark")), by);
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                SalesDtos.LineRow cur = mapper.findLine(id);
                if (cur == null) {
                    return 0;
                }
                orders.lockOpen(cur.getOrderId());
                return mapper.updateLine(id, new LineValue(cur.getLineNo(), cur.getItemId(), new BigDecimal(v.get("qty")),
                        LocalDate.parse(v.get("dueDate")), v.get("remark")), rowVer, by);
            }

            @Override
            public long count() {
                return mapper.countLines();
            }
        };
    }

    static String key(String custCd, String po, String lineNo) {
        return custCd + "|" + po + "|" + lineNo;
    }

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static Integer lineNo(String raw, FieldErrors e) {
        if (VisibleText.isBlank(raw)) {
            e.add("lineNo", "REQUIRED");
            return null;
        }
        String s = raw.strip();
        if (!s.matches("^\\d{1,5}$")) {
            e.add("lineNo", "FORMAT");
            return null;
        }
        int n = Integer.parseInt(s);
        if (n < 1) {
            e.add("lineNo", "RANGE", 1, 99999);
            return null;
        }
        return n;
    }

    /** 채운 칸 중 지금 값과 다른 것이 있나(엔진의 SAME 판정과 같은 규칙) */
    private static boolean changed(Map<String, String> cur, Map<String, String> v, Map<String, String> cells) {
        for (String k : List.of("qty", "dueDate", "remark")) {
            if (!VisibleText.isBlank(cells.get(k)) && !java.util.Objects.equals(cur.get(k), v.get(k))) {
                return true;
            }
        }
        return false;
    }
}
