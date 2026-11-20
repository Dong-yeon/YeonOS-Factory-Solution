package io.smarthappy.module.sales;

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

import io.smarthappy.module.sales.SalesDtos.CloseForm;
import io.smarthappy.module.sales.SalesDtos.CompareView;
import io.smarthappy.module.sales.SalesDtos.CustOption;
import io.smarthappy.module.sales.SalesDtos.FcstForm;
import io.smarthappy.module.sales.SalesDtos.FcstLineRow;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.module.sales.SalesDtos.LineForm;
import io.smarthappy.module.sales.SalesDtos.OrderDetail;
import io.smarthappy.module.sales.SalesDtos.OrderForm;
import io.smarthappy.module.sales.SalesDtos.OrderList;
import io.smarthappy.module.sales.SalesDtos.RevRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 수주·내시 API (P2 2단계, docs/25 4.3) */
@RestController
@RequiredArgsConstructor
public class SalesApiController {

    private static final String ORDER = "/api/sales/orders";
    private static final String FCST = "/api/sales/forecasts";
    /** 납기 답변(CTP)을 내주는 APS 화면 — 계약 POST /api/aps/ctp 의 @ProgAuth(docs/38 15.4). APS 클래스는 쓰지 않는다 */
    static final String APS_PLAN_PROG = "aps-plan";

    private final OrderService orders;
    private final ForecastService forecasts;
    private final MessageSource messages;
    private final PermissionService permissions;
    private final GridExcel gridExcel;

    // ------------------------------------------------------------------ 수주

    @GetMapping(ORDER + "/customers")
    @ProgAuth(prog = OrderService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CustOption>> orderCusts() {
        return ApiResponse.ok(orders.custs());
    }

    /**
     * [납기 답변] 버튼을 보일지 — 지금 사용자가 APS 생산 계획을 조회할 수 있나(프로그램이 없거나 사용 중지면 false = APS 없음).
     * 숨김은 편의, 판정은 APS API 의 @ProgAuth. APS 끝점을 떠보지 않아 권한 거부 기록이 화면을 열 때마다 쌓이지 않는다
     */
    @GetMapping(ORDER + "/ctp-available")
    @ProgAuth(prog = OrderService.PROG, action = Action.SEARCH)
    public ApiResponse<Boolean> ctpAvailable() {
        return ApiResponse.ok(permissions.actions(APS_PLAN_PROG).contains(Action.SEARCH));
    }

    @GetMapping(ORDER + "/items")
    @ProgAuth(prog = OrderService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ItemOption>> orderItems(@RequestParam(required = false) String q) {
        return ApiResponse.ok(orders.items(q));
    }

    @GetMapping(ORDER)
    @ProgAuth(prog = OrderService.PROG, action = Action.SEARCH)
    public ApiResponse<OrderList> orderList(@RequestParam(required = false) String custCd,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String itemCd, @RequestParam(required = false) String dueFrom,
            @RequestParam(required = false) String dueTo) {
        return ApiResponse.ok(orders.list(custCd, from, to, status, keyword, itemCd, dueFrom, dueTo));
    }

    /** 그리드(UI 단계 3 ①) — 기존 목록 API 는 그대로(납기 행 수·500건 표시는 그쪽), 같은 조건 + 페이지·정렬·건수 */
    @GetMapping(ORDER + "/grid")
    @ProgAuth(prog = OrderService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> orderGrid(@RequestParam(required = false) String custCd,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String itemCd, @RequestParam(required = false) String dueFrom,
            @RequestParam(required = false) String dueTo, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        OrderService.Page p = orders.grid(custCd, from, to, status, keyword, itemCd, dueFrom, dueTo, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(ORDER + "/columns")
    @ProgAuth(prog = OrderService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> orderColumns(Locale locale) {
        List<Map<String, Object>> cols = orders.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(ORDER + "/export")
    @ProgAuth(prog = OrderService.PROG, action = Action.EXCEL)
    public void orderExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, OrderService.PROG, orders.columns(), q -> {
            var p = orders.grid(q.str("custCd"), q.str("from"), q.str("to"), q.str("status"), q.str("keyword"), q.str("itemCd"),
                    q.str("dueFrom"), q.str("dueTo"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(ORDER + "/{orderId}")
    @ProgAuth(prog = OrderService.PROG, action = Action.SEARCH)
    public ApiResponse<OrderDetail> order(@PathVariable long orderId) {
        return ApiResponse.ok(orders.detail(orderId));
    }

    @PostMapping(ORDER)
    @ProgAuth(prog = OrderService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createOrder(@RequestBody OrderForm form) {
        return ApiResponse.ok(orders.create(form));
    }

    @PutMapping(ORDER + "/{orderId}")
    @ProgAuth(prog = OrderService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateOrder(@PathVariable long orderId, @RequestBody OrderForm form) {
        orders.update(orderId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ORDER + "/{orderId}/cancel")
    @ProgAuth(prog = OrderService.PROG, action = Action.SAVE)
    public ApiResponse<String> cancelOrder(@PathVariable long orderId, @RequestBody CloseForm form) {
        orders.cancel(orderId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ORDER + "/{orderId}/lines")
    @ProgAuth(prog = OrderService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addLine(@PathVariable long orderId, @RequestBody LineForm form) {
        return ApiResponse.ok(orders.addLine(orderId, form));
    }

    @PutMapping(ORDER + "/lines/{lineId}")
    @ProgAuth(prog = OrderService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateLine(@PathVariable long lineId, @RequestBody LineForm form) {
        orders.updateLine(lineId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ORDER + "/lines/{lineId}/close")
    @ProgAuth(prog = OrderService.PROG, action = Action.SAVE)
    public ApiResponse<String> closeLine(@PathVariable long lineId, @RequestBody CloseForm form) {
        orders.closeLine(lineId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ORDER + "/lines/{lineId}/cancel")
    @ProgAuth(prog = OrderService.PROG, action = Action.SAVE)
    public ApiResponse<String> cancelLine(@PathVariable long lineId, @RequestBody CloseForm form) {
        orders.cancelLine(lineId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 내시

    @GetMapping(FCST + "/customers")
    @ProgAuth(prog = ForecastService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CustOption>> forecastCusts() {
        return ApiResponse.ok(forecasts.custs());
    }

    @GetMapping(FCST + "/revs")
    @ProgAuth(prog = ForecastService.PROG, action = Action.SEARCH)
    public ApiResponse<List<RevRow>> revs(@RequestParam(required = false) String custCd) {
        return ApiResponse.ok(forecasts.revs(custCd));
    }

    /** 버전 그리드(UI 단계 3 ①) — 기존 목록 API 는 그대로(비교 대상 고르기가 모든 버전을 씀), 같은 조건 + 페이지·정렬·건수 */
    @GetMapping(FCST + "/revs/grid")
    @ProgAuth(prog = ForecastService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> revGrid(@RequestParam(required = false) String custCd,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        ForecastService.RevPage p = forecasts.revGrid(custCd, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(FCST + "/revs/columns")
    @ProgAuth(prog = ForecastService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> revColumns(Locale locale) {
        List<Map<String, Object>> cols = forecasts.revColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(FCST + "/revs/export")
    @ProgAuth(prog = ForecastService.PROG, action = Action.EXCEL)
    public void revExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ForecastService.PROG, forecasts.revColumns(), q -> {
            var p = forecasts.revGrid(q.str("custCd"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(FCST + "/revs/{revId}/lines")
    @ProgAuth(prog = ForecastService.PROG, action = Action.SEARCH)
    public ApiResponse<List<FcstLineRow>> revLines(@PathVariable long revId) {
        return ApiResponse.ok(forecasts.lines(revId));
    }

    @GetMapping(FCST + "/compare")
    @ProgAuth(prog = ForecastService.PROG, action = Action.SEARCH)
    public ApiResponse<CompareView> compare(@RequestParam(required = false) Long fromRevId, @RequestParam long toRevId) {
        return ApiResponse.ok(forecasts.compare(fromRevId, toRevId));
    }

    @PostMapping(FCST)
    @ProgAuth(prog = ForecastService.PROG, action = Action.SAVE)
    public ApiResponse<Long> receive(@RequestBody FcstForm form) {
        return ApiResponse.ok(forecasts.receive(form));
    }
}
