package io.smarthappy.module.wms.count;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.wms.count.CountDtos.AddLineForm;
import io.smarthappy.module.wms.count.CountDtos.CancelForm;
import io.smarthappy.module.wms.count.CountDtos.CountHead;
import io.smarthappy.module.wms.count.CountDtos.CountView;
import io.smarthappy.module.wms.count.CountDtos.CountsForm;
import io.smarthappy.module.wms.count.CountDtos.CreateForm;
import io.smarthappy.module.wms.count.CountDtos.PeriodCheck;
import io.smarthappy.module.wms.count.CountDtos.PeriodForm;
import io.smarthappy.module.wms.count.CountDtos.PeriodRow;
import io.smarthappy.module.wms.count.CountDtos.RecountForm;
import io.smarthappy.module.wms.count.CountDtos.VerForm;
import io.smarthappy.module.wms.inv.InvDtos.NodeOpt;
import io.smarthappy.module.wms.inv.StockService;
import io.smarthappy.module.wms.loc.LocAttrDtos.PlantRow;
import io.smarthappy.module.wms.loc.LocAttrService;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 재고 실사(wms-count)·기간 마감(wms-period) API — P4 W5 */
@RestController
@RequiredArgsConstructor
public class CountApiController {

    private static final String COUNT = "/api/wms/counts";
    private static final String PERIOD = "/api/wms/periods";

    private final CountService counts;
    private final PeriodService periods;
    private final LocAttrService locs;
    private final StockService stock;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    // ---------------------------------------------------------------- 실사

    @GetMapping(COUNT + "/plants")
    @ProgAuth(prog = CountService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(locs.plants());
    }

    @GetMapping(COUNT + "/nodes")
    @ProgAuth(prog = CountService.PROG, action = Action.SEARCH)
    public ApiResponse<List<NodeOpt>> nodes(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(stock.nodes(plantCd));
    }

    @GetMapping(COUNT)
    @ProgAuth(prog = CountService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String status) {
        List<CountHead> l = counts.list(plantCd, status);
        return ApiResponse.list(l, l.size());
    }

    /** 실사 목록 그리드(UI 단계 3 ①) — list 와 같은 조건 + page·size·sort·total */
    @GetMapping(COUNT + "/grid")
    @ProgAuth(prog = CountService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로 읽는다(CustApiController 와 같음)
        String[] raw = request.getParameterValues("sort");
        List<String> sort = raw == null ? null : List.of(raw);
        CountService.Page<CountHead> p = counts.grid(plantCd, status, page, size, sort, total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(COUNT + "/columns")
    @ProgAuth(prog = CountService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = counts.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(COUNT + "/export")
    @ProgAuth(prog = CountService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, CountService.PROG, counts.columns(), q -> {
            var p = counts.grid(q.str("plantCd"), q.str("status"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(COUNT + "/{id}")
    @ProgAuth(prog = CountService.PROG, action = Action.SEARCH)
    public ApiResponse<CountView> view(@PathVariable long id) {
        return ApiResponse.ok(counts.view(id));
    }

    @PostMapping(COUNT)
    @ProgAuth(prog = CountService.PROG, action = Action.SAVE)
    public ApiResponse<CountView> create(@RequestBody(required = false) CreateForm form) {
        return ApiResponse.ok(counts.view(counts.create(form)));
    }

    @PostMapping(COUNT + "/{id}/start")
    @ProgAuth(prog = CountService.PROG, action = Action.SAVE)
    public ApiResponse<CountView> start(@PathVariable long id, @RequestBody(required = false) VerForm form) {
        return ApiResponse.ok(counts.start(id, form == null ? null : form.rowVer()));
    }

    @PostMapping(COUNT + "/{id}/lines")
    @ProgAuth(prog = CountService.PROG, action = Action.SAVE)
    public ApiResponse<CountView> addLine(@PathVariable long id, @RequestBody(required = false) AddLineForm form) {
        return ApiResponse.ok(counts.addLine(id, form == null ? new AddLineForm(null, null, null, null, null) : form));
    }

    /** 실사 줄 엑셀(③) — 줄 번호 열이 붙여넣기 열쇠 */
    @PostMapping(COUNT + "/{id}/export")
    @ProgAuth(prog = CountService.PROG, action = Action.EXCEL)
    public void exportLines(@PathVariable long id, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        counts.exportLines(id, response);
    }

    @PutMapping(COUNT + "/{id}/counts")
    @ProgAuth(prog = CountService.PROG, action = Action.SAVE)
    public ApiResponse<CountView> enter(@PathVariable long id, @RequestBody(required = false) CountsForm form) {
        return ApiResponse.ok(counts.enter(id, form == null ? new CountsForm(null, null) : form));
    }

    @PostMapping(COUNT + "/{id}/submit")
    @ProgAuth(prog = CountService.PROG, action = Action.SAVE)
    public ApiResponse<CountView> submit(@PathVariable long id, @RequestBody(required = false) VerForm form) {
        return ApiResponse.ok(counts.submit(id, form == null ? null : form.rowVer()));
    }

    @PostMapping(COUNT + "/{id}/recount")
    @ProgAuth(prog = CountService.PROG, action = Action.SAVE)
    public ApiResponse<CountView> recount(@PathVariable long id, @RequestBody(required = false) RecountForm form) {
        return ApiResponse.ok(counts.recount(id, form == null ? new RecountForm(null, null) : form));
    }

    /** 확정 — 한도를 넘는 차이가 있으면 서비스가 APPROVE 를 더 본다 */
    @PostMapping(COUNT + "/{id}/post")
    @ProgAuth(prog = CountService.PROG, action = Action.SAVE)
    public ApiResponse<CountView> post(@PathVariable long id, @RequestBody(required = false) VerForm form) {
        return ApiResponse.ok(counts.post(id, form == null ? null : form.rowVer()));
    }

    @PostMapping(COUNT + "/{id}/cancel")
    @ProgAuth(prog = CountService.PROG, action = Action.SAVE)
    public ApiResponse<CountView> cancel(@PathVariable long id, @RequestBody(required = false) CancelForm form) {
        return ApiResponse.ok(counts.cancel(id, form == null ? new CancelForm(null, null) : form));
    }

    // ---------------------------------------------------------------- 기간 마감

    @GetMapping(PERIOD + "/plants")
    @ProgAuth(prog = "wms-period", action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> periodPlants() {
        return ApiResponse.ok(locs.plants());
    }

    @GetMapping(PERIOD)
    @ProgAuth(prog = "wms-period", action = Action.SEARCH)
    public ApiResponse<Void> periods(@RequestParam(required = false) String plantCd, @RequestParam(required = false) Integer year) {
        List<PeriodRow> l = periods.year(plantCd, year);
        return ApiResponse.list(l, l.size());
    }

    /** 기간 마감 그리드(docs/40 §10.2 D1) — 목록과 같은 조건 + page·size·sort·total. 기존 목록 API 는 그대로 */
    @GetMapping(PERIOD + "/grid")
    @ProgAuth(prog = "wms-period", action = Action.SEARCH)
    public ApiResponse<Void> periodGrid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] raw = request.getParameterValues("sort");   // sort=필드,방향 — 쉼표로 쪼개지 않게 원래 값 그대로
        CountService.Page<PeriodRow> p = periods.grid(plantCd, year, page, size, raw == null ? null : List.of(raw), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(PERIOD + "/columns")
    @ProgAuth(prog = "wms-period", action = Action.SEARCH)
    public ApiResponse<Void> periodColumns(Locale locale) {
        List<Map<String, Object>> cols = periods.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(PERIOD + "/export")
    @ProgAuth(prog = "wms-period", action = Action.EXCEL)
    public void periodExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, "wms-period", periods.columns(), q -> {
            var p = periods.grid(q.str("plantCd"), q.intValue("year"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(PERIOD + "/check")
    @ProgAuth(prog = "wms-period", action = Action.SEARCH)
    public ApiResponse<PeriodCheck> check(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String period) {
        return ApiResponse.ok(periods.check(plantCd, period));
    }

    @PostMapping(PERIOD + "/close")
    @ProgAuth(prog = "wms-period", action = Action.APPROVE)
    public ApiResponse<PeriodRow> close(@RequestBody(required = false) PeriodForm form) {
        return ApiResponse.ok(periods.close(form));
    }

    @PostMapping(PERIOD + "/reopen")
    @ProgAuth(prog = "wms-period", action = Action.APPROVE)
    public ApiResponse<PeriodRow> reopen(@RequestBody(required = false) PeriodForm form) {
        return ApiResponse.ok(periods.reopen(form));
    }
}
