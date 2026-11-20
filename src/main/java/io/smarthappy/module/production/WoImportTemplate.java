package io.smarthappy.module.production;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.module.production.WoDtos.WoForm;
import io.smarthappy.module.production.WoDtos.WoRow;
import io.smarthappy.module.production.WoDtos.WoValue;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.FieldErrors.FieldError;
import io.smarthappy.platform.importer.ImportTemplate;
import lombok.RequiredArgsConstructor;

/**
 * 작업지시 엑셀 올리기 (docs/25 4.26, 11.4a 3번). 지시 번호를 비우면 새 지시(채번), 쓰면 그 지시를 고친다 — **계획(PLANNED) 지시만**.
 * 검증은 화면 저장과 같은 {@link WoService#validate}. 라우팅·BOM 은 새 지시면 계획 시작일의 기본 확정본(화면에서 안 고른 것과 같다),
 * 고치는 지시면 지금 것 그대로(빈 칸이 기본값으로 바뀌지 않게). 수주 연결·발행·취소는 화면에서.
 */
@Component
@RequiredArgsConstructor
public class WoImportTemplate implements ImportTemplate {

    static final String CODE = "PRD_WO";

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("woNo", "prdWo.col.woNo", false, "import.guide.woNo", null, null).asText(),
            new ImportColumn("plantCd", "prdWo.col.plant", true, "import.guide.woPlant", null, "P1").asText(),
            new ImportColumn("itemCd", "prdWo.col.itemCd", true, "help.prd-wo.itemCd", null, "FG-100").asText(),
            new ImportColumn("planQty", "prdWo.col.planQty", true, "help.prd-wo.planQty", null, "100"),
            new ImportColumn("planStart", "prdWo.col.planStart", true, "import.guide.woStart", null, "2026-11-02").asText(),
            new ImportColumn("planEnd", "prdWo.col.planEnd", false, "import.guide.woEnd", null, "2026-11-05").asText(),
            new ImportColumn("priority", "prdWo.col.priority", false, "import.guide.woPriority", null, "5"),
            new ImportColumn("remark", "prdWo.col.remark", false, null, null, null));

    private final WoService service;
    private final WoMapper mapper;
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
        return WoService.PROG;
    }

    @Override
    public String titleKey() {
        return "prog.prdWo";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    @Override
    public ImportSession open() {
        Map<String, WoRow> byNo = new HashMap<>();
        return new ImportSession() {
            /** 지시 번호 → 지시(없으면 null). 한 세션 안에서 한 번만 읽는다 */
            private WoRow wo(String no) {
                return byNo.computeIfAbsent(no, n -> {
                    List<Long> ids = jdbc.queryForList("select wo_id from prd_wo where wo_no = ?", Long.class, n);
                    return ids.isEmpty() ? null : mapper.findWo(ids.get(0));
                });
            }

            @Override
            public Existing find(String key) {
                WoRow w = key == null ? null : wo(key);
                return w == null ? null : new Existing(w.getWoId(), w.getRowVer(), values(w));
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                String no = isBlank(cells.get("woNo")) ? null : cells.get("woNo").strip().toUpperCase(Locale.ROOT);
                WoRow cur = null;
                if (no != null) {
                    cur = no.length() > 30 ? null : wo(no);
                    if (cur == null) {
                        return error(cells, no, "woNo", "NO_SUCH_CODE");
                    }
                    if (!"PLANNED".equals(cur.getStatus())) {
                        // 발행 뒤 지시는 화면에서만(일정·우선순위) — 엑셀로 현장 지시를 바꾸지 않는다
                        return error(cells, no, "woNo", "STATE");
                    }
                }
                // 고치는 행의 빈 칸은 지금 값(엔진 규칙과 같게 — 검증도 채운 값으로)
                Map<String, String> v = new LinkedHashMap<>(cur == null ? Map.of() : values(cur));
                cells.forEach((k, x) -> {
                    if (!isBlank(x)) {
                        v.put(k, x);
                    }
                });
                FieldErrors e = FieldErrors.create();
                WoValue wv = null;
                try {
                    wv = service.validate(form(v, cur), cur);
                } catch (BusinessException ex) {
                    ex.fieldErrors().forEach(f -> e.add(f.field(), f.code(), f.args() == null ? new Object[0] : f.args()));
                    if (ex.fieldErrors().isEmpty()) {
                        e.add("woNo", ex.code().name());
                    }
                }
                return new Validated(no, wv == null ? v : normalized(no, v.get("plantCd"), v.get("itemCd"), wv), e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                return service.insert(service.validate(form(v, null), null), by);
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                WoRow cur = mapper.findWo(id);
                if (cur == null || !"PLANNED".equals(cur.getStatus())) {
                    return 0;           // 그 사이 발행·취소됨 → 엔진이 "그 사이 바뀜"
                }
                try {
                    return mapper.updatePlanned(id, service.validate(form(v, cur), cur), rowVer, by);
                } catch (DataIntegrityViolationException ex) {
                    throw WoService.translate(ex);
                }
            }

            @Override
            public long count() {
                Long n = jdbc.queryForObject("select count(*) from prd_wo", Long.class);
                return n == null ? 0 : n;
            }
        };
    }

    private static Validated error(Map<String, String> cells, String no, String field, String code) {
        return new Validated(no, new LinkedHashMap<>(cells), List.of(new FieldError(null, field, code)));
    }

    /** 고치는 지시는 라우팅·BOM·수주 연결을 그대로 넘긴다(비우면 서비스가 기본 리비전을 고르므로) */
    private static WoForm form(Map<String, String> v, WoRow cur) {
        Integer priority = null;
        if (!isBlank(v.get("priority"))) {
            try {
                priority = Integer.valueOf(v.get("priority").strip());
            } catch (NumberFormatException ex) {
                priority = -1;          // 서비스가 RANGE 로
            }
        }
        return new WoForm(v.get("plantCd"), v.get("itemCd"), v.get("planQty"), v.get("planStart"), v.get("planEnd"),
                cur == null ? null : cur.getRoutingId(), cur == null ? null : cur.getBomId(), cur == null ? null : cur.getOrderLineId(),
                priority, v.get("remark"), cur == null ? null : cur.getRowVer());
    }

    private static Map<String, String> values(WoRow w) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("woNo", w.getWoNo());
        v.put("plantCd", w.getPlantCd());
        v.put("itemCd", w.getItemCd());
        v.put("planQty", plain(new BigDecimal(w.getPlanQty())));
        v.put("planStart", w.getPlanStart().toString());
        v.put("planEnd", w.getPlanEnd() == null ? null : w.getPlanEnd().toString());
        v.put("priority", String.valueOf(w.getPriority()));
        v.put("remark", w.getRemark());
        return v;
    }

    private static Map<String, String> normalized(String no, String plantCd, String itemCd, WoValue wv) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("woNo", no);
        v.put("plantCd", wv.plantCd());
        v.put("itemCd", itemCd == null ? null : itemCd.strip().toUpperCase(Locale.ROOT));
        v.put("planQty", plain(wv.planQty()));
        v.put("planStart", wv.planStart().toString());
        v.put("planEnd", wv.planEnd() == null ? null : wv.planEnd().toString());
        v.put("priority", String.valueOf(wv.priority()));
        v.put("remark", wv.remark());
        return v;
    }

    private static String plain(BigDecimal d) {
        return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
    }
}
