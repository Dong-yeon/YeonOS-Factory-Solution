package io.smarthappy.module.srm.asn;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.decimal;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.asn.AsnDtos.AsnLineValue;
import io.smarthappy.module.srm.asn.AsnDtos.AsnValue;
import io.smarthappy.module.srm.asn.AsnDtos.PoLineRef;
import io.smarthappy.module.srm.asn.AsnDtos.SupplierRef;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.importer.ImportTemplate;
import lombok.RequiredArgsConstructor;

/**
 * 엑셀 ASN 올리기 (SRM 2부 R3a, docs/31 4.11) — 협력사가 보낸 납품 목록을 그대로. 한 행 = ASN 행 하나,
 * **같은 협력사 × 같은 납품서 번호 = ASN 하나**(머리 칸 — 공장·출하일·도착 예정일 — 은 같아야, 다르면 SRM_ASN_HEAD_MISMATCH).
 * 업무 키 = 협력사 | 납품서 번호 | 발주 | 행 | 공급사 LOT (파일 안 같은 키 두 행은 엔진이 둘 다 오류). 언제나 새 ASN(덮어쓰지 않음) —
 * 같은 협력사의 살아 있는 ASN 이 이미 그 납품서 번호를 쓰면 DUPLICATE. 행 오류: 없는 발주 행(다른 회사는 RLS 로 같은 오류),
 * 다른 협력사·다른 공장, ASN 을 걸 수 없는 발주, 파일 안 합 &gt; 통지 가능 양. 행을 다 넣은 뒤 커밋 직전 확정(SENT) — 협력사가 보낸 것이므로.
 */
@Component
@RequiredArgsConstructor
public class AsnImportTemplate implements ImportTemplate {

    static final String CODE = "SRM_ASN";
    static final String CUST_ID = "_custId";
    static final String LINE_ID = "_poLineId";
    static final String ITEM_ID = "_itemId";
    static final String UNIT = "_unitCd";

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("custCd", "srm.asn.imp.custCd", true, "import.guide.srmAsnCust", null, "V100").asText(),
            new ImportColumn("supplierRef", "srm.asn.imp.supplierRef", true, "import.guide.srmAsnRef", null, "DN-2611-001").asText(),
            new ImportColumn("plantCd", "srm.asn.imp.plantCd", true, null, null, "P1").asText(),
            new ImportColumn("shipDate", "srm.asn.imp.shipDate", true, null, null, "2026-11-18").asText(),
            new ImportColumn("etaDate", "srm.asn.imp.etaDate", true, "import.guide.srmAsnEta", null, "2026-11-20").asText(),
            new ImportColumn("poNo", "srm.asn.imp.poNo", true, null, null, "PO26100001").asText(),
            new ImportColumn("lineNo", "srm.asn.imp.lineNo", true, null, null, "1"),
            new ImportColumn("qty", "srm.asn.imp.qty", true, "import.guide.srmAsnQty", null, "500"),
            new ImportColumn("supplierLotNo", "srm.asn.imp.lot", false, null, null, "L2611-07").asText(),
            new ImportColumn("mfgDate", "srm.asn.imp.mfgDate", false, null, null, "").asText(),
            new ImportColumn("expiryDate", "srm.asn.imp.expiryDate", false, null, null, "").asText(),
            new ImportColumn("packCnt", "srm.asn.imp.packCnt", false, null, null, "10"),
            new ImportColumn("remark", "srm.asn.imp.remark", false, null, null, ""));

    private final AsnService service;
    private final AsnMapper mapper;

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
        return AsnService.PROG;
    }

    @Override
    public String titleKey() {
        return "prog.srmAsn";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    @Override
    public ImportSession open() {
        return session(null);
    }

    @Override
    public ImportSession openForExecute(long jobId) {
        return session(jobId);
    }

    private ImportSession session(Long jobId) {
        Map<String, Optional<SupplierRef>> sups = new HashMap<>();
        Map<String, Optional<PoLineRef>> lineCache = new HashMap<>();
        Map<Long, BigDecimal> fileSums = new HashMap<>();
        Map<String, String> heads = new HashMap<>();          // 협력사|납품서 → 공장|출하일|도착 예정일 (같아야)
        Map<String, Long> created = new LinkedHashMap<>();    // 실행: 협력사|납품서 → 만든 ASN(1,000행 묶음에 걸쳐도 같은 ASN)
        Map<String, Integer> nextLine = new HashMap<>();
        boolean[] hooked = {false};
        return new ImportSession() {
            @Override
            public Existing find(String key) {
                return null;
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                FieldErrors e = FieldErrors.create();
                Map<String, String> v = new LinkedHashMap<>();
                String custCd = SrmChecks.code(cells.get("custCd"));
                SupplierRef sup = null;
                if (custCd == null) {
                    e.add("custCd", "REQUIRED");
                } else {
                    sup = sups.computeIfAbsent(custCd, k -> Optional.ofNullable(k.length() > 30 ? null : mapper.findSupplier(k))).orElse(null);
                    if (sup == null) {
                        e.add("custCd", "NO_SUCH_CODE");
                    }
                }
                String ref = text(cells.get("supplierRef"), "supplierRef", 60, true, e);
                if (sup != null && ref != null && mapper.supplierRefUsed(sup.getCustId(), ref)) {
                    e.add("supplierRef", "DUPLICATE");
                }
                String plant = SrmChecks.code(cells.get("plantCd"));
                if (plant == null) {
                    e.add("plantCd", "REQUIRED");
                } else if (plant.length() > 20 || mapper.plantUseYn(plant) == null) {
                    e.add("plantCd", "NO_SUCH_CODE");
                }
                LocalDate ship = date(cells.get("shipDate"), "shipDate", true, e);
                LocalDate eta = date(cells.get("etaDate"), "etaDate", true, e);
                if (ship != null && eta != null && eta.isBefore(ship)) {
                    e.add("etaDate", "SRM_ASN_ETA");
                }
                if (custCd != null && ref != null) {
                    String h = plant + "|" + ship + "|" + eta;
                    String prev = heads.putIfAbsent(custCd + "|" + ref, h);
                    if (prev != null && !prev.equals(h)) {
                        e.add("supplierRef", "SRM_ASN_HEAD_MISMATCH");
                    }
                }
                String poNo = SrmChecks.code(cells.get("poNo"));
                Integer lineNo = intCell(cells.get("lineNo"), "lineNo", e);
                PoLineRef po = null;
                if (poNo == null) {
                    e.add("poNo", "REQUIRED");
                } else if (lineNo != null) {
                    po = lineCache.computeIfAbsent(poNo + ":" + lineNo,
                            k -> Optional.ofNullable(poNo.length() > 30 ? null : mapper.findPoLineByNo(poNo, lineNo))).orElse(null);
                    if (po == null) {
                        e.add("lineNo", "NO_SUCH_CODE");
                    } else if (sup != null && po.getCustId() != sup.getCustId()) {
                        e.add("poNo", "SRM_OTHER_SUPPLIER");
                    } else if (plant != null && !plant.equals(po.getPlantCd())) {
                        e.add("poNo", "SRM_ASN_OTHER_PLANT");
                    } else if (!"SRM".equals(po.getSource()) || !AsnService.PO_OPEN.contains(po.getStatus())
                            || !"OPEN".equals(po.getLineStatus())) {
                        e.add("poNo", "SRM_ASN_PO_STATE");
                    }
                }
                BigDecimal qty = null;
                if (po != null) {
                    int scale = po.getUnitScale() == null ? 6 : Math.min(po.getUnitScale(), 6);
                    qty = decimal(cells.get("qty"), "qty", scale, AsnService.QTY_INT_DIGITS, true, e);
                    if (qty != null && e.isEmpty()) {
                        // 파일 안 같은 발주 행의 앞 줄들과 합해 통지 가능 양을 넘는 줄부터 오류(R3-4).
                        // 다른 오류가 있는 줄·넘는 줄은 합에 넣지 않는다(들어가지 않을 줄이 뒤 줄을 막지 않게)
                        BigDecimal room = po.getNotifiable().max(BigDecimal.ZERO);
                        BigDecimal sum = fileSums.getOrDefault(po.getPoLineId(), BigDecimal.ZERO).add(qty);
                        if (sum.compareTo(room) > 0) {
                            e.add("qty", "SRM_ASN_OVER", room.stripTrailingZeros().toPlainString());
                        } else {
                            fileSums.put(po.getPoLineId(), sum);
                        }
                    }
                } else if (blank(cells.get("qty"))) {
                    e.add("qty", "REQUIRED");
                }
                String lot = text(cells.get("supplierLotNo"), "supplierLotNo", 60, false, e);
                LocalDate mfg = date(cells.get("mfgDate"), "mfgDate", false, e);
                LocalDate exp = date(cells.get("expiryDate"), "expiryDate", false, e);
                if (mfg != null && exp != null && exp.isBefore(mfg)) {
                    e.add("expiryDate", "SRM_ASN_EXPIRY");
                }
                BigDecimal pack = decimal(cells.get("packCnt"), "packCnt", 0, 5, false, e);
                String remark = text(cells.get("remark"), "remark", 500, false, e);
                v.put("custCd", custCd);
                v.put("supplierRef", ref);
                v.put("plantCd", plant);
                v.put("shipDate", ship == null ? null : ship.toString());
                v.put("etaDate", eta == null ? null : eta.toString());
                v.put("poNo", poNo);
                v.put("lineNo", lineNo == null ? null : String.valueOf(lineNo));
                v.put("qty", qty == null ? null : qty.stripTrailingZeros().toPlainString());
                v.put("supplierLotNo", lot);
                v.put("mfgDate", mfg == null ? null : mfg.toString());
                v.put("expiryDate", exp == null ? null : exp.toString());
                v.put("packCnt", pack == null ? null : pack.toPlainString());
                v.put("remark", remark);
                // 숨은 값(파일 열 아님) — 실행 때 다시 찾지 않는다
                v.put(CUST_ID, sup == null ? null : String.valueOf(sup.getCustId()));
                v.put(LINE_ID, po == null ? null : String.valueOf(po.getPoLineId()));
                v.put(ITEM_ID, po == null ? null : String.valueOf(po.getItemId()));
                v.put(UNIT, po == null ? null : po.getUnitCd());
                String key = custCd == null || ref == null || poNo == null || lineNo == null ? null
                        : String.join("|", custCd, ref, poNo, String.valueOf(lineNo), lot == null ? "" : lot);
                return new Validated(key, v, e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                insertAll(List.of(v), by);
                return 0;
            }

            @Override
            public void insertAll(List<Map<String, String>> rows, String by) {
                for (Map<String, String> v : rows) {
                    String k = v.get("custCd") + "|" + v.get("supplierRef");
                    Long asnId = created.get(k);
                    if (asnId == null) {
                        AsnValue head = new AsnValue(Long.parseLong(v.get(CUST_ID)), v.get("plantCd"), LocalDate.parse(v.get("shipDate")),
                                LocalDate.parse(v.get("etaDate")), v.get("supplierRef"), null, null);
                        asnId = service.insertImported(head, jobId, by);
                        created.put(k, asnId);
                        nextLine.put(k, 1);
                    }
                    int no = nextLine.merge(k, 1, Integer::sum) - 1;
                    service.insertImportedLine(asnId, no, new AsnLineValue(Long.parseLong(v.get(LINE_ID)), Long.parseLong(v.get(ITEM_ID)),
                            v.get(UNIT), new BigDecimal(v.get("qty")), v.get("supplierLotNo"), parse(v.get("mfgDate")), parse(v.get("expiryDate")),
                            v.get("packCnt") == null ? null : Integer.valueOf(v.get("packCnt")), v.get("remark")), by);
                }
                if (!hooked[0]) {
                    hooked[0] = true;
                    // 행을 다 넣은 뒤 커밋 직전 확정(한도·행 수는 트리거가 최종 — 실패면 실행 전체 롤백)
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            new ArrayList<>(created.values()).forEach(id -> service.sendImported(id, by));
                        }
                    });
                }
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                return 0;   // ASN 은 덮어쓰지 않는다(find 가 언제나 null 이라 불리지 않음)
            }

            @Override
            public long count() {
                return mapper.countLines();
            }
        };
    }

    private static LocalDate parse(String v) {
        return v == null ? null : LocalDate.parse(v);
    }

    private static Integer intCell(String raw, String field, FieldErrors e) {
        if (blank(raw)) {
            e.add(field, "REQUIRED");
            return null;
        }
        String s = raw.strip();
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);    // 숫자 셀의 저장 값
        }
        if (!s.matches("^\\d{1,4}$") || Integer.parseInt(s) < 1) {
            e.add(field, "RANGE", 1, 9999);
            return null;
        }
        return Integer.parseInt(s);
    }
}
