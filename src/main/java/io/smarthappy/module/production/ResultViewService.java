package io.smarthappy.module.production;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;
import io.smarthappy.module.master.workforce.WorkforceMapper;
import io.smarthappy.module.production.ResultViewDtos.DefectSum;
import io.smarthappy.module.production.ResultViewDtos.ResultExportForm;
import io.smarthappy.module.production.ResultViewDtos.ResultLine;
import io.smarthappy.module.production.ResultViewDtos.ResultQuery;
import io.smarthappy.module.production.ResultViewDtos.ResultSort;
import io.smarthappy.module.production.ResultViewDtos.ResultView;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.module.sales.SalesMapper;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 사무 실적 조회 (prd-result, docs/25 4.16) — 읽기 전용. 줄의 기준은 원래 실적, 정정은 표시(상세)·제외(묶음 = 순수량).
 * 기간 = 작업일(저장 때 WorkDayResolver 가 남긴 값 — 다시 계산하지 않는다). 엑셀은 지금 보기 그대로(7c 와 같은 규칙).
 */
@Service
@RequiredArgsConstructor
public class ResultViewService {

    public static final String PROG = "prd-result";
    static final int SCREEN_MAX_DAYS = 92;
    static final int PREVIEW_LIMIT = 500;
    static final Set<String> VIEWS = Set.of("DETAIL", "WO", "PROC", "WORKER", "SHIFT");
    private static final int QTY_SCALE = 6;

    private static final List<ColumnSpec> SUMS = List.of(
            ColumnSpec.of("cnt", "prdResult.col.cnt", ColumnType.INT, 70),
            ColumnSpec.dec("goodQty", "prdResult.col.goodQty", QTY_SCALE, 100).withMinScale(0),
            ColumnSpec.dec("scrapQty", "prdResult.col.scrapQty", QTY_SCALE, 100).withMinScale(0),
            ColumnSpec.dec("reworkQty", "prdResult.col.reworkQty", QTY_SCALE, 100).withMinScale(0),
            ColumnSpec.dec("repairQty", "prdResult.col.repairQty", QTY_SCALE, 100).withMinScale(0),
            ColumnSpec.text("unitCd", "prdResult.col.unitCd", 70),
            ColumnSpec.dec("defectRate", "prdResult.col.defectRate", 2, 90));

    /** 보기별 엑셀 열 — 화면 표와 같은 순서 */
    static final Map<String, List<ColumnSpec>> COLUMNS = Map.of(
            "DETAIL", List.of(
                    ColumnSpec.of("workDay", "prdResult.col.workDay", ColumnType.DATE, 100),
                    ColumnSpec.text("shiftCd", "prdResult.col.shiftCd", 70),
                    ColumnSpec.text("woNo", "prdResult.col.woNo", 140),
                    ColumnSpec.of("opSeq", "prdResult.col.opSeq", ColumnType.INT, 60),
                    ColumnSpec.text("procCd", "prdResult.col.procCd", 100),
                    ColumnSpec.text("itemCd", "prdResult.col.itemCd", 140),
                    ColumnSpec.text("itemNm", "prdResult.col.itemNm", 180),
                    ColumnSpec.text("equipCd", "prdResult.col.equipCd", 100),
                    ColumnSpec.text("workerCd", "prdResult.col.workerCd", 90),
                    ColumnSpec.text("workerNm", "prdResult.col.workerNm", 100),
                    ColumnSpec.dec("goodQty", "prdResult.col.goodQty", QTY_SCALE, 100).withMinScale(0),
                    ColumnSpec.dec("scrapQty", "prdResult.col.scrapQty", QTY_SCALE, 100).withMinScale(0),
                    ColumnSpec.dec("reworkQty", "prdResult.col.reworkQty", QTY_SCALE, 100).withMinScale(0),
                    ColumnSpec.dec("repairQty", "prdResult.col.repairQty", QTY_SCALE, 100).withMinScale(0),
                    ColumnSpec.text("unitCd", "prdResult.col.unitCd", 70),
                    ColumnSpec.text("lotNo", "prdResult.col.lotNo", 160),
                    ColumnSpec.of("occurredAt", "prdResult.col.occurredAt", ColumnType.DATETIME, 150),
                    ColumnSpec.text("recordedBy", "prdResult.col.recordedBy", 140),
                    ColumnSpec.of("correctedAt", "prdResult.col.correctedAt", ColumnType.DATETIME, 150),
                    ColumnSpec.text("correctReason", "prdResult.col.correctReason", 200),
                    ColumnSpec.text("approverCd", "prdResult.col.approverCd", 110),
                    ColumnSpec.text("approverNm", "prdResult.col.approverNm", 120)),
            "WO", concat(List.of(
                    ColumnSpec.text("woNo", "prdResult.col.woNo", 140),
                    ColumnSpec.text("itemCd", "prdResult.col.itemCd", 140),
                    ColumnSpec.text("itemNm", "prdResult.col.itemNm", 180))),
            "PROC", concat(List.of(
                    ColumnSpec.text("woNo", "prdResult.col.woNo", 140),
                    ColumnSpec.of("opSeq", "prdResult.col.opSeq", ColumnType.INT, 60),
                    ColumnSpec.text("procCd", "prdResult.col.procCd", 100),
                    ColumnSpec.text("procNm", "prdResult.col.procNm", 140),
                    ColumnSpec.text("itemCd", "prdResult.col.itemCd", 140))),
            "WORKER", concat(List.of(
                    ColumnSpec.text("workerCd", "prdResult.col.workerCd", 90),
                    ColumnSpec.text("workerNm", "prdResult.col.workerNm", 100))),
            "SHIFT", concat(List.of(
                    ColumnSpec.of("workDay", "prdResult.col.workDay", ColumnType.DATE, 100),
                    ColumnSpec.text("shiftCd", "prdResult.col.shiftCd", 70))));

    private static final List<ResultSort> SUM_SORTS = List.of(ResultSort.CNT, ResultSort.GOOD_QTY, ResultSort.SCRAP_QTY,
            ResultSort.REWORK_QTY, ResultSort.REPAIR_QTY, ResultSort.UNIT_CD, ResultSort.DEFECT_RATE);

    /** 보기별 그리드 정렬 허용 — 그 보기의 select 에 있는 칸만(docs/40 §10.2 D1) */
    static final Map<String, ResultSort[]> SORTS = Map.of(
            "DETAIL", new ResultSort[] {ResultSort.WORK_DAY, ResultSort.SHIFT_CD, ResultSort.WO_NO, ResultSort.OP_SEQ, ResultSort.PROC_CD,
                ResultSort.ITEM_CD, ResultSort.EQUIP_CD, ResultSort.WORKER_CD, ResultSort.GOOD_QTY, ResultSort.SCRAP_QTY,
                ResultSort.REWORK_QTY, ResultSort.REPAIR_QTY, ResultSort.UNIT_CD, ResultSort.LOT_NO, ResultSort.OCCURRED_AT},
            "WO", withSums(ResultSort.WO_NO, ResultSort.ITEM_CD),
            "PROC", withSums(ResultSort.WO_NO, ResultSort.OP_SEQ, ResultSort.PROC_CD, ResultSort.ITEM_CD),
            "WORKER", withSums(ResultSort.WORKER_CD),
            "SHIFT", withSums(ResultSort.WORK_DAY, ResultSort.SHIFT_CD));

    /** 그리드 기본 정렬 = 기존 목록(500행 표)과 같은 순서 — 마지막 고정 키(상세 result_id, 묶음 묶음 키)는 매퍼가 붙인다 */
    static final Map<String, List<String>> DEFAULT_SORT = Map.of(
            "DETAIL", List.of("workDay,desc"),
            "WO", List.of("woNo,asc", "itemCd,asc", "unitCd,asc"),
            "PROC", List.of("woNo,asc", "opSeq,asc", "unitCd,asc"),
            "WORKER", List.of("workerCd,asc", "unitCd,asc"),
            "SHIFT", List.of("workDay,desc", "shiftCd,asc", "unitCd,asc"));

    /** 보기별 그리드 열 = 엑셀 열과 같은 칸·순서 + 정렬 허용 표시 */
    static final Map<String, List<ColumnSpec>> GRID_COLUMNS = Map.of(
            "DETAIL", gridColumns("DETAIL"), "WO", gridColumns("WO"), "PROC", gridColumns("PROC"),
            "WORKER", gridColumns("WORKER"), "SHIFT", gridColumns("SHIFT"));

    public record Page(List<ResultLine> list, Long total) {
    }

    private final ResultViewMapper mapper;
    private final WorkforceMapper workforce;
    private final SalesMapper salesMapper;
    private final ExcelExportService excel;
    private final DateRanges ranges;
    private final PolicyService policies;

    @Transactional(readOnly = true)
    public ResultView view(String view, LocalDate from, LocalDate to, String plantCd, String shiftCd, String keyword, String itemCd,
            String procCd, String workerCd) {
        ResultQuery q = query(view, from, to, plantCd, shiftCd, keyword, itemCd, procCd, workerCd);
        if (q == null) {
            return new ResultView(view(view), List.of(), 0, false, List.of());
        }
        List<ResultLine> rows = mapper.rows(q, PREVIEW_LIMIT + 1);
        boolean truncated = rows.size() > PREVIEW_LIMIT;
        return new ResultView(q.view(), truncated ? rows.subList(0, PREVIEW_LIMIT) : rows, mapper.count(q), truncated,
                mapper.defects(q));
    }

    /**
     * 그리드 (docs/40 §10.2 D1, 결정 docs/29 #45 — 기존 목록·엑셀 API 는 그대로) — 같은 조건·같은 보기 + 페이지·정렬·건수.
     * 500행 상한 없이 쪽으로 넘긴다. 기간 92일 규칙은 목록과 같다(query 가 검사)
     */
    @Transactional(readOnly = true)
    public Page grid(String view, LocalDate from, LocalDate to, String plantCd, String shiftCd, String keyword, String itemCd,
            String procCd, String workerCd, Integer page, Integer size, List<String> sort, Boolean total) {
        String v = view(view);
        PageQuery pq = PageQuery.parse(page, size, sort, total, SORTS.get(v), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                DEFAULT_SORT.get(v));
        ResultQuery q = query(v, from, to, plantCd, shiftCd, keyword, itemCd, procCd, workerCd);
        if (q == null) {
            return new Page(List.of(), pq.isWithTotal() ? 0L : null);
        }
        return new Page(mapper.page(q, pq), pq.isWithTotal() ? mapper.count(q) : null);
    }

    /** 보기별 그리드 열 — 보기를 바꾸면 화면이 이 열로 그리드를 다시 만든다 */
    public List<ColumnSpec> columns(String view) {
        return GRID_COLUMNS.get(view(view));
    }

    /** 불량 유형 합계(정정 제외) — 그리드 화면의 아래 요약 표. 목록 API 의 defects 와 같은 값 */
    @Transactional(readOnly = true)
    public List<DefectSum> defects(String view, LocalDate from, LocalDate to, String plantCd, String shiftCd, String keyword,
            String itemCd, String procCd, String workerCd) {
        ResultQuery q = query(view, from, to, plantCd, shiftCd, keyword, itemCd, procCd, workerCd);
        return q == null ? List.of() : mapper.defects(q);
    }

    /** 엑셀 — 지금 보기 그대로. 건수 상한·동시 자리 잠금·수식 주입 방지·출력 정보 시트는 플랫폼(7c 와 같이 일반 트랜잭션) */
    @Transactional
    public long export(ResultExportForm in, HttpServletResponse response) throws IOException {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ResultQuery q = query(in.view(), in.from(), in.to(), in.plantCd(), in.shiftCd(), in.keyword(), in.itemCd(), in.procCd(),
                in.workerCd());
        String v = q == null ? view(in.view()) : q.view();
        long count = q == null ? 0 : mapper.count(q);
        return excel.export(response, new ExportJob(PROG, "prog.prdResult", COLUMNS.get(v), count,
                List.of("view", "from", "to", "plantCd", "shiftCd", "keyword", "itemCd", "procCd", "workerCd")), w -> {
                    if (q == null) {
                        return;
                    }
                    try (Cursor<ResultLine> c = mapper.cursor(q, null)) {
                        c.forEach(w);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /** 조회 칸 선택지 — 사용 중 공장 */
    @Transactional(readOnly = true)
    public List<PlantRow> plants() {
        return workforce.findPlants();
    }

    /** 조회 칸 선택지 — 공장의 근무조(사용 중지 포함 — 과거 실적의 근무조도 고를 수 있게) */
    @Transactional(readOnly = true)
    public List<ShiftRow> shifts(String plantCd) {
        return isBlank(plantCd) ? List.of() : workforce.findShifts(plantCd.strip().toUpperCase(Locale.ROOT), false);
    }

    /** 검증 → 조건. 없는 품목·작업자 코드면 null(빈 결과 — 다른 목록과 같은 규칙) */
    private ResultQuery query(String view, LocalDate from, LocalDate to, String plantCd, String shiftCd, String keyword, String itemCd,
            String procCd, String workerCd) {
        String v = view(view);
        ranges.check(from, to, SCREEN_MAX_DAYS);
        if (!isBlank(keyword) && keyword.strip().length() > 50) {
            throw OrderService.fieldError("keyword", "TOO_LONG");
        }
        Long itemId = null;
        if (!isBlank(itemCd)) {
            ItemOption i = salesMapper.findItem(itemCd.strip().toUpperCase(Locale.ROOT));
            if (i == null) {
                return null;
            }
            itemId = i.getItemId();
        }
        Long workerId = null;
        if (!isBlank(workerCd)) {
            workerId = mapper.findWorkerId(workerCd.strip().toUpperCase(Locale.ROOT));
            if (workerId == null) {
                return null;
            }
        }
        return new ResultQuery(v, from, to, upper(plantCd), upper(shiftCd),
                isBlank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)), itemId, upper(procCd), workerId);
    }

    private static String view(String raw) {
        String v = isBlank(raw) ? "DETAIL" : raw.strip();
        if (!VIEWS.contains(v)) {
            throw OrderService.fieldError("view", "CODE");
        }
        return v;
    }

    private static String upper(String v) {
        return isBlank(v) ? null : v.strip().toUpperCase(Locale.ROOT);
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<ColumnSpec> concat(List<ColumnSpec> keys) {
        return Stream.concat(keys.stream(), SUMS.stream()).toList();
    }

    private static ResultSort[] withSums(ResultSort... keys) {
        return Stream.concat(Stream.of(keys), SUM_SORTS.stream()).toArray(ResultSort[]::new);
    }

    /** 엑셀 열에 정렬 허용 표시만 더한다(정렬 가능 = 그 보기 SORTS 에 있는 필드) */
    private static List<ColumnSpec> gridColumns(String view) {
        Set<String> sortable = Stream.of(SORTS.get(view)).map(ResultSort::field).collect(java.util.stream.Collectors.toSet());
        return COLUMNS.get(view).stream().map(c -> sortable.contains(c.field()) ? c.withSort() : c).toList();
    }
}
