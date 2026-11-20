package io.smarthappy.module.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.sales.SalesDtos.CustRef;
import io.smarthappy.module.sales.SalesDtos.FcstLineValue;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.importer.ImportCodes;
import io.smarthappy.platform.importer.ImportTemplate;
import lombok.RequiredArgsConstructor;

/**
 * 내시 엑셀 올리기 (P2 2단계, docs/25 4.3). 한 행 = (거래처, 품목, 기간) 수량 하나.
 *
 * 내시는 덮어쓰지 않는다 — 기존 행과 비교(SAME·CHANGE)하지 않고 언제나 새 행이며, **파일 하나 = 거래처별 새 버전 하나**
 * (파일에 거래처가 둘이면 버전 둘). 버전은 원본 올리기 작업(import_job_id)을 가리킨다.
 * 업무 키 = 거래처 | 품목 | 기간 유형 | 시작 — 파일 안 같은 키 두 행은 엔진이 둘 다 오류로.
 */
@Component
@RequiredArgsConstructor
public class ForecastImportTemplate implements ImportTemplate {

    static final String CODE = "SAL_FORECAST";
    static final String ITEM_ID = "_itemId";

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("custCd", "salFcst.col.custCd", true, "import.guide.salCust", null, "C-001").asText(),
            new ImportColumn("itemCd", "salFcst.col.itemCd", true, "import.guide.salItem", null, "64310-DM100").asText(),
            new ImportColumn("bucketType", "salFcst.col.bucketType", true, "import.guide.fcstBucket", "FCST_BUCKET", "WEEK"),
            new ImportColumn("bucketStart", "salFcst.col.bucketStart", true, "import.guide.fcstStart", null, "2026-11-02").asText(),
            new ImportColumn("qty", "salFcst.col.qty", true, "import.guide.fcstQty", null, "800"),
            new ImportColumn("firmYn", "salFcst.col.firmYn", false, "import.guide.fcstFirm", null, "N"));

    private final OrderService orders;
    private final ForecastService forecasts;
    private final SalesMapper mapper;
    private final CodeService codes;

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
        return ForecastService.PROG;
    }

    @Override
    public String titleKey() {
        return "prog.salForecast";
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
        Map<String, Integer> scales = orders.unitScales();
        ImportCodes buckets = new ImportCodes(codes, "FCST_BUCKET");
        // 실행: 거래처 → 이 파일로 만든 새 버전(파일 안 여러 묶음에 걸쳐도 같은 버전)
        Map<String, Long> revByCust = new HashMap<>();
        return new ImportSession() {
            @Override
            public Existing find(String key) {
                return null;
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                FieldErrors e = FieldErrors.create();
                CustRef cust = orders.cust(cells.get("custCd"), e);
                String type = buckets.resolve(cells.get("bucketType"));
                FcstLineValue l = forecasts.line(cells.get("itemCd"), type == null ? cells.get("bucketType") : type,
                        cells.get("bucketStart"), cells.get("qty"), ImportCodes.yn(cells.get("firmYn")), "", scales, e);
                Map<String, String> v = new LinkedHashMap<>();
                v.put("custCd", cust == null ? null : cust.getCustCd());
                v.put("itemCd", l == null ? null : cells.get("itemCd").strip().toUpperCase(Locale.ROOT));
                v.put("bucketType", l == null ? null : l.bucketType());
                v.put("bucketStart", l == null ? null : l.bucketStart().toString());
                v.put("qty", l == null ? null : OrderImportTemplate.plain(l.qty()));
                v.put("firmYn", l == null ? null : l.firmYn());
                // 숨은 값(파일 열 아님) — 실행 때 다시 찾지 않게
                v.put(ITEM_ID, l == null ? null : String.valueOf(l.itemId()));
                String key = cust == null || l == null ? null
                        : String.join("|", v.get("custCd"), v.get("itemCd"), v.get("bucketType"), v.get("bucketStart"));
                return new Validated(key, v, e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                insertAll(List.of(v), by);
                return 0;
            }

            @Override
            public void insertAll(List<Map<String, String>> rows, String by) {
                Map<String, List<FcstLineValue>> byCust = new LinkedHashMap<>();
                for (Map<String, String> v : rows) {
                    byCust.computeIfAbsent(v.get("custCd"), k -> new ArrayList<>()).add(new FcstLineValue(
                            Long.parseLong(v.get(ITEM_ID)), v.get("bucketType"),
                            LocalDate.parse(v.get("bucketStart")), new BigDecimal(v.get("qty")), v.get("firmYn")));
                }
                byCust.forEach((custCd, lines) -> {
                    Long revId = revByCust.get(custCd);
                    if (revId == null) {
                        CustRef c = mapper.findCust(custCd);
                        revByCust.put(custCd, forecasts.store(c.getCustId(), "FILE", jobId, null, lines, by));
                    } else {
                        for (int i = 0; i < lines.size(); i += 500) {
                            mapper.insertFcstLines(revId, lines.subList(i, Math.min(lines.size(), i + 500)));
                        }
                    }
                });
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                return 0;   // 내시는 바꾸지 않는다(find 가 언제나 null 이라 불리지 않음)
            }

            @Override
            public long count() {
                return mapper.countFcstLines();
            }
        };
    }
}
