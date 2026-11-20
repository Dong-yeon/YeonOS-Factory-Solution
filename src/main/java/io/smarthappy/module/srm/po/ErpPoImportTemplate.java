package io.smarthappy.module.srm.po;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.decimal;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.po.PoDtos.ItemRef;
import io.smarthappy.module.srm.po.PoDtos.PoLineValue;
import io.smarthappy.module.srm.po.PoDtos.SupplierRef;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.importer.ImportTemplate;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * ERP 발주 받기 (SRM S3b, docs/31 4.7, D1). 정책 SRM_PO_SOURCE=ERP 인 회사만. 한 행 = ERP 발주 행 하나.
 * 파일의 ERP 발주 번호마다 SRM 발주 하나(source = ERP)를 만들고 외부 ID 매핑(시스템 × PO × ERP 번호)으로 묶는다 →
 * **같은 번호를 다시 받으면 멱등**: 내용이 같으면 SAME(반영 없음), 다르면 행 오류(ERP 변경 반영은 하지 않는다 — 사람이 판단).
 * 협력사·품목은 내부 코드 또는 그 ERP 시스템의 외부 ID 매핑(CUST·ITEM) 둘 다 받는다.
 * 업무 키 = ERP 시스템 | ERP 발주 번호 | 행 번호. 발주 머리 값(협력사·공장·창고·발주일·통화)은 같은 번호의 모든 행이 같아야 한다.
 * 실행: 발주마다 작성 중 머리 + 매핑 → 행 → **트랜잭션 커밋 직전에** 확정·발행(발행본 스냅샷·협력사 통지) —
 * 1,000행 묶음이 한 발주를 나눠도 행을 다 넣은 뒤 한 번 발행한다(발행 뒤에는 행을 더할 수 없다).
 */
@Component
@RequiredArgsConstructor
public class ErpPoImportTemplate implements ImportTemplate {

    static final String CODE = "SRM_ERP_PO";
    static final String SYSTEM_ID = "_systemId";
    static final String CUST_ID = "_custId";
    static final String ITEM_ID = "_itemId";
    static final String WH_ID = "_whId";
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("erpSystemCd", "srm.erpPo.col.erpSystemCd", true, "import.guide.srmErpSystem", null, "ERP").asText(),
            new ImportColumn("erpPoNo", "srm.erpPo.col.erpPoNo", true, "import.guide.srmErpPoNo", null, "4500012345").asText(),
            new ImportColumn("lineNo", "srm.erpPo.col.lineNo", true, "import.guide.srmErpLine", null, "10"),
            new ImportColumn("custCd", "srm.erpPo.col.custCd", true, "import.guide.srmErpCust", null, "V100").asText(),
            new ImportColumn("plantCd", "srm.erpPo.col.plantCd", true, null, null, "P1").asText(),
            new ImportColumn("whCd", "srm.erpPo.col.whCd", false, null, null, "WH1").asText(),
            new ImportColumn("poDate", "srm.erpPo.col.poDate", true, "import.guide.srmErpPoDate", null, "2026-10-21").asText(),
            new ImportColumn("currencyCd", "srm.erpPo.col.currencyCd", false, "import.guide.srmErpCurrency", null, "KRW").asText(),
            new ImportColumn("itemCd", "srm.erpPo.col.itemCd", true, "import.guide.srmErpItem", null, "BOLT-M8").asText(),
            new ImportColumn("qty", "srm.erpPo.col.qty", true, null, null, "100"),
            new ImportColumn("unitPrice", "srm.erpPo.col.unitPrice", true, "import.guide.srmErpPrice", null, "120.5"),
            new ImportColumn("dueDate", "srm.erpPo.col.dueDate", true, null, null, "2026-11-20").asText(),
            new ImportColumn("remark", "srm.erpPo.col.remark", false, null, null, ""));

    private final PoService service;
    private final PoMapper mapper;
    private final PolicyService policies;
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
        return PoService.PROG;
    }

    @Override
    public String titleKey() {
        return "srm.erpPo.title";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    @Override
    public ImportSession open() {
        return new Session();
    }

    /** 검증·실행 세션 — 코드 캐시, 발주 번호별 첫 머리 값, 실행 중 만든 발주 */
    private final class Session implements ImportSession {

        private final Map<String, Optional<Long>> systems = new HashMap<>();
        private final Map<String, List<String>> heads = new HashMap<>();
        private final Map<String, Long> created = new LinkedHashMap<>();
        private boolean releaseHooked;

        private Long system(String cd) {
            return systems.computeIfAbsent(cd, k -> Optional.ofNullable(k.length() > 20 ? null : mapper.findErpSystem(k))).orElse(null);
        }

        @Override
        public Existing find(String key) {
            String[] k = key.split("\\|", 3);
            Long sys = system(k[0]);
            Map<String, Object> l = sys == null ? null : mapper.findErpLine(sys, k[1], Integer.parseInt(k[2]));
            return l == null ? null : new Existing(((Number) l.get("po_line_id")).longValue(), 0, existingValues(k, l));
        }

        @Override
        public Validated validate(Map<String, String> cells) {
            FieldErrors e = FieldErrors.create();
            Map<String, String> v = new LinkedHashMap<>();
            if (!"ERP".equals(policies.value("SRM_PO_SOURCE"))) {
                e.add("erpPoNo", "SRM_NOT_ERP_SOURCE");
            }
            String sysCd = SrmChecks.code(cells.get("erpSystemCd"));
            Long sys = null;
            if (sysCd == null) {
                e.add("erpSystemCd", "REQUIRED");
            } else if ((sys = system(sysCd)) == null) {
                e.add("erpSystemCd", "NO_SUCH_CODE");
            }
            String erpNo = externalId(cells.get("erpPoNo"), "erpPoNo", e);
            Integer lineNo = lineNo(cells.get("lineNo"), e);
            // 협력사: 내부 코드 → 없으면 ERP 외부 ID(CUST)
            SupplierRef sup = null;
            String custRaw = blank(cells.get("custCd")) ? null : cells.get("custCd").strip();
            if (custRaw == null) {
                e.add("custCd", "REQUIRED");
            } else {
                String cc = SrmChecks.code(custRaw);
                sup = cc.length() > 30 ? null : mapper.findSupplier(cc);
                if (sup == null && sys != null) {
                    Long id = mapper.findMappedId(sys, "CUST", custRaw);
                    sup = id == null ? null : mapper.findSupplierById(id);
                }
                if (sup == null) {
                    e.add("custCd", "NO_SUCH_CODE");
                } else if ("BLOCKED".equals(sup.getTradeStatus())) {
                    e.add("custCd", "SUPPLIER_BLOCKED");
                }
            }
            String plant = SrmChecks.code(cells.get("plantCd"));
            if (plant == null) {
                e.add("plantCd", "REQUIRED");
            } else {
                String use = plant.length() > 20 ? null : mapper.plantUseYn(plant);
                if (use == null) {
                    e.add("plantCd", "NO_SUCH_CODE");
                } else if (!"Y".equals(use)) {
                    e.add("plantCd", "INACTIVE");
                }
            }
            String whCd = SrmChecks.code(cells.get("whCd"));
            Long wh = null;
            if (whCd != null && plant != null) {
                wh = whCd.length() > 30 ? null : mapper.findWh(plant, whCd);
                if (wh == null) {
                    e.add("whCd", "NO_SUCH_CODE");
                }
            }
            LocalDate poDate = date(cells.get("poDate"), "poDate", true, e);
            if (poDate != null && poDate.isAfter(clock.today())) {
                e.add("poDate", "FUTURE");
            }
            String cur = SrmChecks.code(cells.get("currencyCd"));
            if (cur == null && sup != null) {
                cur = sup.getCurrencyCd();
            } else if (cur != null && (cur.length() > 30 || !mapper.codeUsable("CURRENCY", cur))) {
                e.add("currencyCd", "NO_SUCH_CODE");
            }
            // 품목: 내부 코드 → 없으면 ERP 외부 ID(ITEM)
            ItemRef item = null;
            String itemRaw = blank(cells.get("itemCd")) ? null : cells.get("itemCd").strip();
            if (itemRaw == null) {
                e.add("itemCd", "REQUIRED");
            } else if (sup != null) {
                String ic = SrmChecks.code(itemRaw);
                item = ic.length() > 40 ? null : mapper.findItem(ic, sup.getCustId());
                if (item == null && sys != null) {
                    Long id = mapper.findMappedId(sys, "ITEM", itemRaw);
                    String cd = id == null ? null : mapper.findItemCd(id);
                    item = cd == null ? null : mapper.findItem(cd, sup.getCustId());
                }
                if (item == null) {
                    e.add("itemCd", "NO_SUCH_CODE");
                } else if (!"Y".equals(item.getUseYn())) {
                    e.add("itemCd", "INACTIVE");
                }
            }
            BigDecimal qty = null;
            if (item != null) {
                Integer s = mapper.unitScale(item.getUnitCd());
                qty = decimal(cells.get("qty"), "qty", s == null ? 6 : Math.min(s, 6), PoService.QTY_INT_DIGITS, true, e);
            }
            BigDecimal price = decimal(cells.get("unitPrice"), "unitPrice", PoService.PRICE_SCALE, PoService.PRICE_INT_DIGITS, true, e);
            LocalDate due = date(cells.get("dueDate"), "dueDate", true, e);
            if (due != null && poDate != null && due.isBefore(poDate)) {
                e.add("dueDate", "SRM_ERP_DUE_BEFORE_PO");
            }
            String remark = text(cells.get("remark"), "remark", 500, false, e);
            v.put("erpSystemCd", sysCd);
            v.put("erpPoNo", erpNo);
            v.put("lineNo", lineNo == null ? null : String.valueOf(lineNo));
            v.put("custCd", sup == null ? null : sup.getCustCd());
            v.put("plantCd", plant);
            v.put("whCd", wh == null ? null : whCd);
            v.put("poDate", poDate == null ? null : poDate.toString());
            v.put("currencyCd", cur);
            v.put("itemCd", item == null ? null : item.getItemCd());
            v.put("qty", qty == null ? null : plain(qty));
            v.put("unitPrice", price == null ? null : plain(price));
            v.put("dueDate", due == null ? null : due.toString());
            v.put("remark", remark);
            String key = sysCd == null || erpNo == null || lineNo == null ? null : String.join("|", sysCd, erpNo, String.valueOf(lineNo));
            if (key != null && e.isEmpty()) {
                // 같은 발주 번호의 행은 머리 값이 같아야
                String poKey = sysCd + "|" + erpNo;
                List<String> head = new ArrayList<>();
                for (String k : List.of("custCd", "plantCd", "whCd", "poDate", "currencyCd")) {
                    head.add(v.get(k));
                }
                List<String> first = heads.putIfAbsent(poKey, head);
                if (first != null && !first.equals(head)) {
                    e.add("erpPoNo", "SRM_ERP_HEAD_MISMATCH");
                }
                // 이미 받은 번호: 같은 행이 같은 내용이면 통과(SAME), 아니면 오류(변경·새 행은 반영하지 않는다)
                if (e.isEmpty() && mapper.erpPoExists(sys, erpNo)) {
                    Map<String, Object> old = mapper.findErpLine(sys, erpNo, lineNo);
                    if (old == null || !existingValues(key.split("\\|", 3), old).equals(compareView(v))) {
                        e.add("erpPoNo", "SRM_ERP_PO_CHANGED");
                    }
                }
            }
            // 숨은 값(파일 열 아님) — 실행 때 다시 찾지 않게
            v.put(SYSTEM_ID, sys == null ? null : String.valueOf(sys));
            v.put(CUST_ID, sup == null ? null : String.valueOf(sup.getCustId()));
            v.put(ITEM_ID, item == null ? null : String.valueOf(item.getItemId()));
            v.put(WH_ID, wh == null ? null : String.valueOf(wh));
            return new Validated(key, v, e.list());
        }

        @Override
        public long insert(Map<String, String> v, String by) {
            insertAll(List.of(v), by);
            return 0;
        }

        @Override
        public void insertAll(List<Map<String, String>> rows, String by) {
            hookRelease(by);
            for (Map<String, String> v : rows) {
                String poKey = v.get("erpSystemCd") + "|" + v.get("erpPoNo");
                Long poId = created.get(poKey);
                if (poId == null) {
                    SupplierRef sup = mapper.findSupplierById(Long.parseLong(v.get(CUST_ID)));
                    poId = service.insertErp(Long.parseLong(v.get(SYSTEM_ID)), v.get("erpPoNo"), new PoService.ErpHead(sup.getCustId(),
                            v.get("currencyCd"), sup.getPayTermsCd(), v.get("plantCd"), v.get(WH_ID) == null ? null : Long.parseLong(v.get(WH_ID)),
                            LocalDate.parse(v.get("poDate")), null), by);
                    created.put(poKey, poId);
                }
                ItemRef item = mapper.findItem(v.get("itemCd"), Long.parseLong(v.get(CUST_ID)));
                BigDecimal qty = new BigDecimal(v.get("qty"));
                BigDecimal price = new BigDecimal(v.get("unitPrice"));
                BigDecimal amt = qty.multiply(price).setScale(service.erpAmountScale(v.get("currencyCd")), RoundingMode.HALF_UP);
                service.insertErpLine(poId, Integer.parseInt(v.get("lineNo")), new PoLineValue(null, item.getItemId(), item.getItemNm(),
                        item.getSupplierItemCd(), item.getUnitCd(), qty, price, amt, LocalDate.parse(v.get("dueDate")), null,
                        v.get("remark")), by);
            }
        }

        /** 커밋 직전에 만든 발주를 한 번에 확정·발행 — 같은 트랜잭션(통지 아웃박스도), 롤백이면 불리지 않는다 */
        private void hookRelease(String by) {
            if (releaseHooked) {
                return;
            }
            releaseHooked = true;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    created.values().forEach(id -> service.releaseErp(id, by));
                }
            });
        }

        @Override
        public int update(long id, int rowVer, Map<String, String> v, String by) {
            return 0;   // 받은 ERP 발주는 엑셀로 바꾸지 않는다(검증이 변경 행을 오류로 — 여기 오지 않음)
        }

        @Override
        public long count() {
            return mapper.countErpLines();
        }
    }

    /** 기존 행의 대조 값 — 검증 정규화 값과 같은 모양 */
    private static Map<String, String> existingValues(String[] key, Map<String, Object> l) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("erpSystemCd", key[0]);
        m.put("erpPoNo", key[1]);
        m.put("lineNo", key[2]);
        for (String k : List.of("cust_cd", "plant_cd", "wh_cd", "po_date", "currency_cd", "item_cd", "qty", "unit_price", "due_date",
                "remark")) {
            m.put(camel(k), l.get(k) == null ? null : String.valueOf(l.get(k)));
        }
        return m;
    }

    private static Map<String, String> compareView(Map<String, String> v) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : List.of("erpSystemCd", "erpPoNo", "lineNo", "custCd", "plantCd", "whCd", "poDate", "currencyCd", "itemCd", "qty",
                "unitPrice", "dueDate", "remark")) {
            m.put(k, v.get(k));
        }
        return m;
    }

    private static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** ERP 번호는 대소문자를 바꾸지 않는다(외부 ID 규칙 — 앞뒤 공백만 정리, 제어 문자 금지) */
    private static String externalId(String raw, String field, FieldErrors e) {
        if (blank(raw)) {
            e.add(field, "REQUIRED");
            return null;
        }
        String s = raw.strip();
        if (s.endsWith(".0") && s.matches("^\\d+\\.0$")) {
            s = s.substring(0, s.length() - 2);     // 숫자 셀의 저장 값
        }
        if (CONTROL.matcher(s).find()) {
            e.add(field, "FORMAT");
            return null;
        }
        if (s.length() > 100) {
            e.add(field, "TOO_LONG", 100);
            return null;
        }
        return s;
    }

    private static Integer lineNo(String raw, FieldErrors e) {
        if (blank(raw)) {
            e.add("lineNo", "REQUIRED");
            return null;
        }
        String s = raw.strip();
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        if (!s.matches("^\\d{1,4}$") || Integer.parseInt(s) < 1) {
            e.add("lineNo", "RANGE", 1, 9999);
            return null;
        }
        return Integer.parseInt(s);
    }
}
