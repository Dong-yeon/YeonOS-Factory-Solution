package io.smarthappy.module.wms.inv;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.wms.inv.InvDtos.AdjustForm;
import io.smarthappy.module.wms.inv.InvDtos.ItemOpt;
import io.smarthappy.module.wms.inv.InvDtos.MoveForm;
import io.smarthappy.module.wms.inv.InvDtos.NodeOpt;
import io.smarthappy.module.wms.inv.InvDtos.PostResult;
import io.smarthappy.module.wms.inv.InvDtos.ReconcileRow;
import io.smarthappy.module.wms.inv.InvDtos.ReverseForm;
import io.smarthappy.module.wms.inv.InvDtos.ScanForm;
import io.smarthappy.module.wms.inv.InvDtos.ScanHit;
import io.smarthappy.module.wms.inv.InvDtos.StockQuery;
import io.smarthappy.module.wms.inv.InvDtos.StockExport;
import io.smarthappy.module.wms.inv.InvDtos.StockRow;
import io.smarthappy.module.wms.inv.InvDtos.TrxQuery;
import io.smarthappy.module.wms.inv.InvDtos.TrxExport;
import io.smarthappy.module.wms.inv.InvDtos.TrxRow;
import io.smarthappy.module.wms.loc.LocAttrDtos.PlantRow;
import io.smarthappy.module.wms.loc.LocAttrService;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 현재고(wms-stock)·수불 원장(wms-trx)·재고 이동/조정(wms-move) API — P4 W3. 원장은 고치지 않는다(역분개만) */
@RestController
@RequiredArgsConstructor
public class InvApiController {

    private static final String STOCK = "/api/wms/stock";
    private static final String TRX = "/api/wms/trx";
    private static final String MOVE = "/api/wms/move";

    private final StockService service;
    private final ReceiptBackfillService backfill;
    private final LocAttrService locs;
    private final MessageSource messages;
    private final BizClock clock;

    private List<Map<String, Object>> view(List<ColumnSpec> cols, Locale locale) {
        return cols.stream().map(c -> c.toView(messages, locale)).toList();
    }

    // ---------------------------------------------------------------- 현재고

    @GetMapping(STOCK + "/plants")
    @ProgAuth(prog = StockService.PROG_STOCK, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> stockPlants() {
        return ApiResponse.ok(locs.plants());
    }

    @GetMapping(STOCK + "/nodes")
    @ProgAuth(prog = StockService.PROG_STOCK, action = Action.SEARCH)
    public ApiResponse<List<NodeOpt>> stockNodes(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.nodes(plantCd));
    }

    @GetMapping(STOCK)
    @ProgAuth(prog = StockService.PROG_STOCK, action = Action.SEARCH)
    public ApiResponse<Void> stock(@RequestParam(required = false) String plantCd, @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) Boolean withChildren, @RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String itemNm, @RequestParam(required = false) String stockStatus,
            @RequestParam(required = false) Boolean hideZero, @RequestParam(required = false) String lotNo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개 한 키 정렬이 400 이 된다. 원래 값 그대로 읽는다(CustApiController 와 같음)
        String[] raw = request.getParameterValues("sort");
        List<String> sort = raw == null ? null : List.of(raw);
        StockService.Page<StockRow> p = service.stock(new StockQuery(plantCd, nodeId, withChildren, itemCd, itemNm, stockStatus, hideZero, lotNo),
                page, size, sort, total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(STOCK + "/columns")
    @ProgAuth(prog = StockService.PROG_STOCK, action = Action.SEARCH)
    public ApiResponse<Void> stockColumns(Locale locale) {
        List<Map<String, Object>> cols = view(service.stockColumns(), locale);
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(STOCK + "/{stockId}/history")
    @ProgAuth(prog = StockService.PROG_STOCK, action = Action.SEARCH)
    public ApiResponse<List<TrxRow>> stockHistory(@PathVariable long stockId) {
        return ApiResponse.ok(service.stockHistory(stockId));
    }

    @PostMapping(STOCK + "/export")
    @ProgAuth(prog = StockService.PROG_STOCK, action = Action.EXCEL)
    public void exportStock(@RequestBody(required = false) StockExport req, HttpServletResponse response) throws IOException {
        service.exportStock(req, response);
    }

    @GetMapping(STOCK + "/reconcile")
    @ProgAuth(prog = StockService.PROG_STOCK, action = Action.SEARCH)
    public ApiResponse<ReconcileRow> lastReconcile() {
        return ApiResponse.ok(service.lastReconcile());
    }

    /** 대사 지금 실행 (FN1) — 결과 행 + 불일치면 알림, 고치지 않는다 */
    @PostMapping(STOCK + "/reconcile")
    @ProgAuth(prog = StockService.PROG_STOCK, action = Action.FN1)
    public ApiResponse<ReconcileRow> reconcile() {
        return ApiResponse.ok(service.reconcile());
    }

    /** 생산 입고 이관 미리보기 (FN2, docs/30 11.4) — 전체·이미 들어감·남음·마지막 실행 */
    @GetMapping(STOCK + "/backfill")
    @ProgAuth(prog = StockService.PROG_STOCK, action = Action.FN2)
    public ApiResponse<ReceiptBackfillService.Preview> backfillPreview() {
        return ApiResponse.ok(backfill.preview());
    }

    /** 생산 입고 이관 실행 — 입고마다 트랜잭션, 실패는 결과 행에(멱등 — 다시 돌리면 실패 행만) */
    @PostMapping(STOCK + "/backfill")
    @ProgAuth(prog = StockService.PROG_STOCK, action = Action.FN2)
    public ApiResponse<ReceiptPostingMapper.BackfillRun> backfillRun() {
        return ApiResponse.ok(backfill.run());
    }

    // ---------------------------------------------------------------- 수불 원장

    @GetMapping(TRX + "/plants")
    @ProgAuth(prog = StockService.PROG_TRX, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> trxPlants() {
        return ApiResponse.ok(locs.plants());
    }

    @GetMapping(TRX)
    @ProgAuth(prog = StockService.PROG_TRX, action = Action.SEARCH)
    public ApiResponse<Void> trxs(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) String itemCd, @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) String trxType, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개 한 키 정렬이 400 이 된다. 원래 값 그대로 읽는다(CustApiController 와 같음)
        String[] raw = request.getParameterValues("sort");
        List<String> sort = raw == null ? null : List.of(raw);
        StockService.Page<TrxRow> p = service.trxs(new TrxQuery(from, to, plantCd, itemId, itemCd, nodeId, trxType), page, size, sort,
                total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(TRX + "/columns")
    @ProgAuth(prog = StockService.PROG_TRX, action = Action.SEARCH)
    public ApiResponse<Void> trxColumns(Locale locale) {
        List<Map<String, Object>> cols = view(service.trxColumns(), locale);
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(TRX + "/{id}")
    @ProgAuth(prog = StockService.PROG_TRX, action = Action.SEARCH)
    public ApiResponse<TrxRow> trx(@PathVariable long id) {
        return ApiResponse.ok(service.trx(id));
    }

    @PostMapping(TRX + "/export")
    @ProgAuth(prog = StockService.PROG_TRX, action = Action.EXCEL)
    public void exportTrx(@RequestBody(required = false) TrxExport req, HttpServletResponse response) throws IOException {
        service.exportTrx(req, response);
    }

    /** 역분개 — 원래 행(이동이면 짝)을 정확히 되돌리는 새 행. 사유 필수 */
    @PostMapping(TRX + "/{id}/reverse")
    @ProgAuth(prog = StockService.PROG_TRX, action = Action.SAVE)
    public ApiResponse<List<Long>> reverse(@PathVariable long id, @RequestBody(required = false) ReverseForm form) {
        return ApiResponse.ok(service.reverse(id, form == null ? null : form.reason()));
    }

    // ---------------------------------------------------------------- 재고 이동·조정

    @GetMapping(MOVE + "/plants")
    @ProgAuth(prog = StockService.PROG_MOVE, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> movePlants() {
        return ApiResponse.ok(locs.plants());
    }

    @GetMapping(MOVE + "/nodes")
    @ProgAuth(prog = StockService.PROG_MOVE, action = Action.SEARCH)
    public ApiResponse<List<NodeOpt>> moveNodes(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.nodes(plantCd));
    }

    @GetMapping(MOVE + "/item")
    @ProgAuth(prog = StockService.PROG_MOVE, action = Action.SEARCH)
    public ApiResponse<ItemOpt> moveItem(@RequestParam(required = false) String itemCd) {
        return ApiResponse.ok(service.item(itemCd));
    }

    /** 스캔 판별 — 바코드 글자는 본문으로 */
    @PostMapping(MOVE + "/scan")
    @ProgAuth(prog = StockService.PROG_MOVE, action = Action.SEARCH)
    public ApiResponse<ScanHit> moveScan(@RequestBody ScanForm form) {
        return ApiResponse.ok(service.scan(form));
    }

    /** 오늘 이 공장 원장 (입력 확인용 — 최근 50건) */
    @GetMapping(MOVE + "/recent")
    @ProgAuth(prog = StockService.PROG_MOVE, action = Action.SEARCH)
    public ApiResponse<Void> recent(@RequestParam(required = false) String plantCd) {
        LocalDate today = clock.today();
        StockService.Page<TrxRow> p = service.trxs(new TrxQuery(today, today, plantCd, null, null, null, null), 1, 50, null, false);
        return ApiResponse.list(p.list(), p.list().size());
    }

    @PostMapping(MOVE + "/adjust")
    @ProgAuth(prog = StockService.PROG_MOVE, action = Action.SAVE)
    public ApiResponse<PostResult> adjust(@RequestBody AdjustForm form) {
        return ApiResponse.ok(service.adjust(form));
    }

    @PostMapping(MOVE + "/move")
    @ProgAuth(prog = StockService.PROG_MOVE, action = Action.SAVE)
    public ApiResponse<PostResult> move(@RequestBody MoveForm form) {
        return ApiResponse.ok(service.move(form));
    }
}
