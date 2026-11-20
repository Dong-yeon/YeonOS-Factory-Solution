package io.smarthappy.module.srm.ack;

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

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.ack.AckDtos.PoLineRef;
import io.smarthappy.module.srm.ack.AckDtos.PoRef;
import io.smarthappy.module.srm.po.PoDtos.PoRow;
import io.smarthappy.module.srm.po.PoMapper;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.importer.ImportTemplate;
import lombok.RequiredArgsConstructor;

/**
 * 엑셀 회신 올리기 (SRM S5, docs/31 4.5). 한 행 = 발주 행 하나의 약속 한 줄(같은 행 여러 줄 = 분할 납기).
 * 회신은 덮어쓰지 않는다 — 기존 행과 비교하지 않고 언제나 새 행, **파일의 발주마다 새 회신 묶음 하나**(출처 IMPORT, 원본 작업 id).
 * 업무 키 = 협력사 | 발주 | 행 | 약속 납기 — 파일 안 같은 키 두 행은 엔진이 둘 다 오류로.
 * 행 오류: 없는 발주(다른 회사 발주는 RLS 로 안 보여 같은 오류), 파일 협력사와 발주 협력사가 다름, 회신할 수 없는 상태,
 * 이전 개정, 없는 행, 파일 안 합 > 열린 수량. 실행 때 발주를 잠그고 상태·개정을 다시 본다(그 사이 개정·취소 → 전체 롤백).
 */
@Component
@RequiredArgsConstructor
public class AckImportTemplate implements ImportTemplate {

    static final String CODE = "SRM_PO_ACK";
    static final String PO_ID = "_poId";
    static final String REV = "_rev";
    static final String LINE_ID = "_poLineId";

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("custCd", "srm.ack.col.custCd", true, "import.guide.srmAckCust", null, "V100").asText(),
            new ImportColumn("poNo", "srm.ack.col.poNo", true, "import.guide.srmAckPo", null, "PO26100001").asText(),
            new ImportColumn("rev", "srm.ack.col.rev", false, "import.guide.srmAckRev", null, "0"),
            new ImportColumn("lineNo", "srm.ack.col.lineNo", true, "import.guide.srmAckLine", null, "1"),
            new ImportColumn("promisedDate", "srm.ack.col.promisedDate", true, "import.guide.srmAckDate", null, "2026-11-20").asText(),
            new ImportColumn("promisedQty", "srm.ack.col.promisedQty", true, "import.guide.srmAckQty", null, "500"),
            new ImportColumn("ackByNm", "srm.ack.col.ackByNm", false, null, null, "홍길동"),
            new ImportColumn("ackOn", "srm.ack.col.ackOn", false, "import.guide.srmAckOn", null, "2026-10-21").asText(),
            new ImportColumn("remark", "srm.ack.col.remark", false, null, null, ""));

    private final AckService service;
    private final AckMapper mapper;
    private final PoMapper pos;

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
        return AckService.PROG;
    }

    @Override
    public String titleKey() {
        return "prog.srmAck";
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
        Map<String, Optional<PoRef>> poCache = new HashMap<>();
        Map<String, Optional<PoLineRef>> lineCache = new HashMap<>();
        Map<Long, BigDecimal> fileSums = new HashMap<>();
        Map<String, Integer> scales = new HashMap<>();
        // 실행: 발주 → 이 파일로 만든 회신 묶음(1,000행 묶음에 걸쳐도 같은 묶음)
        Map<Long, Long> ackByPo = new HashMap<>();
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
                String poNo = SrmChecks.code(cells.get("poNo"));
                if (custCd == null) {
                    e.add("custCd", "REQUIRED");
                }
                PoRef po = null;
                if (poNo == null) {
                    e.add("poNo", "REQUIRED");
                } else {
                    po = poCache.computeIfAbsent(poNo, k -> Optional.ofNullable(k.length() > 30 ? null : mapper.findPoRef(k))).orElse(null);
                    if (po == null) {
                        e.add("poNo", "NO_SUCH_CODE");
                    } else if (custCd != null && !custCd.equals(po.getCustCd())) {
                        e.add("custCd", "SRM_OTHER_SUPPLIER");
                    } else if (!"ISSUED".equals(po.getStatus()) && !"ACKED".equals(po.getStatus())) {
                        e.add("poNo", "SRM_PO_NOT_OPEN");
                    }
                }
                Integer rev = intCell(cells.get("rev"), "rev", 0, 999, false, e);
                if (po != null && rev != null && rev != po.getRev()) {
                    e.add("rev", "SRM_OLD_REV", po.getRev());
                }
                Integer lineNo = intCell(cells.get("lineNo"), "lineNo", 1, 9999, true, e);
                PoLineRef line = null;
                if (po != null && lineNo != null) {
                    PoRef p = po;
                    line = lineCache.computeIfAbsent(po.getPoId() + ":" + lineNo,
                            k -> Optional.ofNullable(mapper.findPoLineRef(p.getPoId(), lineNo))).orElse(null);
                    if (line == null) {
                        e.add("lineNo", "NO_SUCH_CODE");
                    } else if (!"OPEN".equals(line.getLineStatus())) {
                        e.add("lineNo", "SRM_LINE_CLOSED");
                    }
                }
                LocalDate d = date(cells.get("promisedDate"), "promisedDate", true, e);
                if (d != null && po != null && d.isBefore(po.getPoDate())) {
                    e.add("promisedDate", "SRM_ACK_BEFORE_PO");
                }
                BigDecimal qty = null;
                if (line != null) {
                    int scale = scales.computeIfAbsent(line.getUnitCd(), u -> {
                        Integer s = pos.unitScale(u);
                        return s == null ? 6 : Math.min(s, 6);
                    });
                    qty = decimal(cells.get("promisedQty"), "promisedQty", scale, AckService.QTY_INT_DIGITS, true, e);
                    if (qty != null) {
                        // 파일 안에서 같은 발주 행의 앞 줄들과 합해 열린 수량을 넘는 줄부터 오류
                        BigDecimal sum = fileSums.merge(line.getPoLineId(), qty, BigDecimal::add);
                        if (sum.compareTo(line.getOpenQty()) > 0) {
                            e.add("promisedQty", "SRM_ACK_OVER", line.getOpenQty().stripTrailingZeros().toPlainString());
                        }
                    }
                } else if (blank(cells.get("promisedQty"))) {
                    e.add("promisedQty", "REQUIRED");
                }
                String ackBy = text(cells.get("ackByNm"), "ackByNm", 100, false, e);
                LocalDate ackOn = po == null ? null : service.ackOn(cells.get("ackOn"), po.getPoDate(), e);
                String remark = text(cells.get("remark"), "remark", 500, false, e);
                v.put("custCd", custCd);
                v.put("poNo", poNo);
                v.put("rev", po == null ? null : String.valueOf(po.getRev()));
                v.put("lineNo", lineNo == null ? null : String.valueOf(lineNo));
                v.put("promisedDate", d == null ? null : d.toString());
                v.put("promisedQty", qty == null ? null : qty.stripTrailingZeros().toPlainString());
                v.put("ackByNm", ackBy);
                v.put("ackOn", ackOn == null ? null : ackOn.toString());
                v.put("remark", remark);
                // 숨은 값(파일 열 아님) — 실행 때 다시 찾지 않고, 검증 때 본 개정과 대조한다
                v.put(PO_ID, po == null ? null : String.valueOf(po.getPoId()));
                v.put(REV, po == null ? null : String.valueOf(po.getRev()));
                v.put(LINE_ID, line == null ? null : String.valueOf(line.getPoLineId()));
                String key = custCd == null || poNo == null || lineNo == null || d == null ? null
                        : String.join("|", custCd, poNo, String.valueOf(lineNo), d.toString());
                return new Validated(key, v, e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                insertAll(List.of(v), by);
                return 0;
            }

            @Override
            public void insertAll(List<Map<String, String>> rows, String by) {
                Map<Long, List<Map<String, String>>> byPo = new LinkedHashMap<>();
                rows.forEach(v -> byPo.computeIfAbsent(Long.parseLong(v.get(PO_ID)), k -> new ArrayList<>()).add(v));
                byPo.forEach((poId, list) -> {
                    Long ackId = ackByPo.get(poId);
                    if (ackId == null) {
                        PoRow po = service.lockOpen(poId);
                        if (po.getRev() != Integer.parseInt(list.get(0).get(REV))) {
                            throw new BusinessException(HbErrorCode.SRM_STATE);   // 검증 뒤 개정됨
                        }
                        Map<String, String> first = list.get(0);
                        ackId = service.insert(po, "IMPORT", "PARTIAL", null, first.get("ackByNm"),
                                LocalDate.parse(first.get("ackOn")), first.get("remark"), jobId, by);
                        ackByPo.put(poId, ackId);
                    }
                    for (Map<String, String> v : list) {
                        mapper.insertAckLine(ackId, Long.parseLong(v.get(LINE_ID)), LocalDate.parse(v.get("promisedDate")),
                                new BigDecimal(v.get("promisedQty")), v.get("remark"), by);
                    }
                    service.finish(ackId, poId, by);
                });
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                return 0;   // 회신은 바꾸지 않는다(find 가 언제나 null 이라 불리지 않음)
            }

            @Override
            public long count() {
                return mapper.countAckLines();
            }
        };
    }

    private static Integer intCell(String raw, String field, int min, int max, boolean required, FieldErrors e) {
        if (blank(raw)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = raw.strip();
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);    // 숫자 셀의 저장 값
        }
        if (!s.matches("^\\d{1,5}$")) {
            e.add(field, "FORMAT");
            return null;
        }
        int n = Integer.parseInt(s);
        if (n < min || n > max) {
            e.add(field, "RANGE", min, max);
            return null;
        }
        return n;
    }
}
