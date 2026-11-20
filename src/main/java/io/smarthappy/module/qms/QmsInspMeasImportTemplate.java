package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import io.smarthappy.module.qms.InspDtos.InspRow;
import io.smarthappy.module.qms.InspDtos.LineRow;
import io.smarthappy.module.qms.InspDtos.MeasEntry;
import io.smarthappy.module.qms.InspDtos.MeasValue;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.FieldErrors.FieldError;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.importer.ImportTemplate;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 검사 실행 측정값 엑셀 올리기 (docs/29_P3 25장). 한 행 = 샘플 하나, 업무 키 = 검사 번호 | 행 번호 | 샘플 번호.
 *
 * 검증·실행은 화면 입력과 **같은 경로** — 샘플 검증은 {@link InspService#checkEntry}, 실행은 {@link InspService#measureImported}
 * (검사 머리 잠금 → 다시 검증 → INSERT). 측정값은 원장이라 **새 값만**: 이미 살아 있는 값이 있는 샘플은 검증 오류(`SAMPLE_TAKEN`,
 * 화면과 같은 코드)로 돌려주고 {@link ImportSession#find} 는 언제나 null — 엔진 모드(INSERT_ONLY·UPSERT)와 상관없이 갱신이 없다(XI-4, Q-xb 가).
 * 같은 파일을 다시 올리면 모든 행이 그 오류라 들어가는 것이 없다(XI-5 멱등). 판정은 하지 않는다(XI-8 — 사람이 화면에서 judge).
 */
@Component
@RequiredArgsConstructor
public class QmsInspMeasImportTemplate implements ImportTemplate {

    static final String CODE = "QMS_INSP_MEAS";
    static final String INSP_ID = "_inspId";
    static final String LINE_ID = "_lineId";
    static final String GAUGE_ID = "_gaugeId";
    static final String AT = "_at";
    static final DateTimeFormatter MEASURED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]");

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("inspNo", "qms.inspImport.col.inspNo", true, "import.guide.qmsInspNo", null, "IN2610-00001").asText(),
            new ImportColumn("lineNo", "qms.inspImport.col.lineNo", true, "import.guide.qmsInspLineNo", null, "10"),
            new ImportColumn("sampleNo", "qms.inspImport.col.sampleNo", true, null, null, "1"),
            new ImportColumn("value", "qms.inspImport.col.value", false, "import.guide.qmsInspValue", null, "12.03"),
            new ImportColumn("okYn", "qms.inspImport.col.okYn", false, "import.guide.qmsInspOkYn", null, "Y"),
            new ImportColumn("gaugeCd", "qms.inspImport.col.gaugeCd", false, "import.guide.qmsInspGauge", null, "CAL-001").asText(),
            new ImportColumn("measuredAt", "qms.inspImport.col.measuredAt", false, "import.guide.qmsInspMeasuredAt", null, "2026-10-27 09:30")
                    .asText());

    private final InspService insps;
    private final InspMapper mapper;
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
        return InspService.PROG;
    }

    @Override
    public String titleKey() {
        return "qms.inspImport.title";
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
        Map<String, InspRow> insp = new HashMap<>();
        Map<Long, List<LineRow>> lines = new HashMap<>();
        Map<Long, Set<Integer>> taken = new HashMap<>();
        Map<String, Long> gauges = new HashMap<>();
        Map<InspService.GaugeDay, GaugeStatus.Status> gaugeStatus = new HashMap<>();
        OffsetDateTime now = clock.now().toOffsetDateTime();
        return new ImportSession() {
            @Override
            public Existing find(String key) {
                return null;
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                List<FieldError> errs = new ArrayList<>();
                Map<String, String> v = new LinkedHashMap<>();
                COLUMNS.forEach(c -> v.put(c.key(), null));
                String no = upper(cells.get("inspNo"));
                InspRow h = no == null ? null : insp.computeIfAbsent(no, mapper::findInspByNo);
                if (no == null) {
                    errs.add(new FieldError(null, "inspNo", "REQUIRED"));
                } else if (h == null) {
                    errs.add(new FieldError(null, "inspNo", "NOT_FOUND"));
                } else if (!"OPEN".equals(h.getStatus())) {
                    errs.add(new FieldError(null, "inspNo", "STATE"));
                }
                Integer lineNo = integer(cells.get("lineNo"), "lineNo", errs);
                Integer sampleNo = integer(cells.get("sampleNo"), "sampleNo", errs);
                LineRow line = null;
                if (h != null && lineNo != null) {
                    line = lines.computeIfAbsent(h.getInspId(), mapper::findLines).stream().filter(x -> x.getLineNo() == lineNo)
                            .findFirst().orElse(null);
                    if (line == null) {
                        errs.add(new FieldError(null, "lineNo", "NOT_FOUND"));
                    }
                }
                BigDecimal value = null;
                String raw = blank(cells.get("value"));
                if (raw != null) {
                    try {
                        value = new BigDecimal(raw);
                    } catch (NumberFormatException e) {
                        errs.add(new FieldError(null, "value", "FORMAT"));
                    }
                }
                Long gaugeId = null;
                String gaugeCd = upper(cells.get("gaugeCd"));
                if (gaugeCd != null) {
                    gaugeId = gauges.computeIfAbsent(gaugeCd, mapper::gaugeIdByCd);
                    if (gaugeId == null) {
                        errs.add(new FieldError(null, "gaugeCd", "NOT_FOUND"));
                    }
                }
                OffsetDateTime at = measuredAt(cells.get("measuredAt"), h, errs);
                boolean headOk = h != null && "OPEN".equals(h.getStatus());
                if (headOk && line != null && sampleNo != null && errs.isEmpty()) {
                    LineRow ln = line;
                    FieldErrors e = FieldErrors.create();
                    MeasValue m = insps.checkEntry(ln, new MeasEntry(sampleNo, value, blank(cells.get("okYn")), gaugeId), at,
                            taken.computeIfAbsent(ln.getInspLineId(), id -> insps.takenSamples(h.getInspId(), id)), new HashSet<>(),
                            gaugeStatus, e.row("0"));
                    // 화면 칸 이름 → 파일 열 이름(계측기는 id 대신 코드 열)
                    e.list().forEach(x -> errs.add(new FieldError(null, "gaugeAssetId".equals(x.field()) ? "gaugeCd" : x.field(), x.code(), x.args())));
                    if (m != null) {
                        v.put(INSP_ID, String.valueOf(h.getInspId()));
                        v.put(LINE_ID, String.valueOf(ln.getInspLineId()));
                        v.put(GAUGE_ID, gaugeId == null ? null : String.valueOf(gaugeId));
                        v.put(AT, at.toString());
                    }
                }
                v.put("inspNo", no);
                v.put("lineNo", lineNo == null ? null : String.valueOf(lineNo));
                v.put("sampleNo", sampleNo == null ? null : String.valueOf(sampleNo));
                v.put("value", value == null ? null : value.stripTrailingZeros().toPlainString());
                v.put("okYn", blank(cells.get("okYn")));
                v.put("gaugeCd", gaugeCd);
                v.put("measuredAt", blank(cells.get("measuredAt")));
                String key = no == null || lineNo == null || sampleNo == null ? null : no + "|" + lineNo + "|" + sampleNo;
                return new Validated(key, v, errs);
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                insertAll(List.of(v), by);
                return 0;
            }

            /** 검사 id 오름차순 → 행 id 오름차순으로 묶어 같은 measure 경로(XI-10 — 여러 검사 파일 둘이 동시여도 교착 없음) */
            @Override
            public void insertAll(List<Map<String, String>> rows, String by) {
                Map<Long, Map<Long, List<Map<String, String>>>> groups = new TreeMap<>();
                for (Map<String, String> v : rows) {
                    groups.computeIfAbsent(Long.parseLong(v.get(INSP_ID)), k -> new TreeMap<>())
                            .computeIfAbsent(Long.parseLong(v.get(LINE_ID)), k -> new ArrayList<>()).add(v);
                }
                groups.forEach((inspId, byLine) -> byLine.forEach((lineId, list) -> {
                    for (int i = 0; i < list.size(); i += InspService.MAX_ENTRIES) {
                        List<Map<String, String>> part = list.subList(i, Math.min(list.size(), i + InspService.MAX_ENTRIES));
                        List<MeasEntry> entries = part.stream().map(v -> new MeasEntry(Integer.valueOf(v.get("sampleNo")),
                                v.get("value") == null ? null : new BigDecimal(v.get("value")), v.get("okYn"),
                                v.get(GAUGE_ID) == null ? null : Long.valueOf(v.get(GAUGE_ID)))).toList();
                        List<OffsetDateTime> at = part.stream().map(v -> OffsetDateTime.parse(v.get(AT))).toList();
                        try {
                            insps.measureImported(inspId, lineId, entries, at, jobId);
                        } catch (BusinessException e) {
                            // 미리보기 뒤 검사가 바뀜 — 어느 샘플인지 알려 주고 전체 롤백(엔진 트랜잭션)
                            Map<String, String> bad = part.get(failedIndex(e, part.size()));
                            throw new BusinessException(HbErrorCode.QMS_INSP_IMPORT_STALE, bad.get("inspNo"), bad.get("lineNo"), bad.get("sampleNo"));
                        }
                    }
                }));
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                return 0;   // find 가 언제나 null 이라 불리지 않음 — 측정값은 원장(갱신 없음)
            }

            @Override
            public long count() {
                return mapper.countLiveMeas();
            }

            /** measuredAt — 비우면 실행(검증) 시각, 회사 시간대로 해석, 검사 생성 시각 이상·지금 이하 */
            private OffsetDateTime measuredAt(String text, InspRow h, List<FieldError> errs) {
                String t = blank(text);
                if (t == null) {
                    return now;
                }
                OffsetDateTime at;
                try {
                    at = LocalDateTime.parse(t.replace('T', ' '), MEASURED_AT).atZone(clock.zone()).toOffsetDateTime();
                } catch (DateTimeParseException e) {
                    errs.add(new FieldError(null, "measuredAt", "FORMAT"));
                    return now;
                }
                if (at.isAfter(now)) {
                    errs.add(new FieldError(null, "measuredAt", "FUTURE"));
                } else if (h != null && h.getOccurredAt() != null && at.isBefore(h.getOccurredAt().withNano(0))) {
                    // 초 단위(QA I-2) — 같은 분이라도 시작 전이면 거부. 파일은 초까지라 시작 시각의 초 미만만 버린다
                    errs.add(new FieldError(null, "measuredAt", "BEFORE_START"));
                }
                return at;
            }
        };
    }

    static int failedIndex(BusinessException e, int size) {
        for (FieldError f : e.fieldErrors()) {
            try {
                int i = Integer.parseInt(f.rowKey());
                if (i >= 0 && i < size) {
                    return i;
                }
            } catch (NumberFormatException ignored) {
                // 행 키 없는 오류(머리 상태 등) — 첫 샘플
            }
        }
        return 0;
    }

    static Integer integer(String text, String field, List<FieldError> errs) {
        String t = blank(text);
        if (t == null) {
            errs.add(new FieldError(null, field, "REQUIRED"));
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(t);
            return d.stripTrailingZeros().scale() > 0 ? fail(field, errs) : d.intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return fail(field, errs);
        }
    }

    private static Integer fail(String field, List<FieldError> errs) {
        errs.add(new FieldError(null, field, "FORMAT"));
        return null;
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static String upper(String s) {
        String t = blank(s);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }
}
