package io.smarthappy.module.wms.ship;

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

import io.smarthappy.module.wms.alloc.AllocDtos.PickView;
import io.smarthappy.module.wms.loc.LocAttrDtos.PlantRow;
import io.smarthappy.module.wms.loc.LocAttrService;
import io.smarthappy.module.wms.ship.ShipDtos.CancelForm;
import io.smarthappy.module.wms.ship.ShipDtos.ConfirmForm;
import io.smarthappy.module.wms.ship.ShipDtos.CreateForm;
import io.smarthappy.module.wms.ship.ShipDtos.NodeOpt;
import io.smarthappy.module.wms.ship.ShipDtos.OrderLineOpt;
import io.smarthappy.module.wms.ship.ShipDtos.PalletCloseForm;
import io.smarthappy.module.wms.ship.ShipDtos.PalletForm;
import io.smarthappy.module.wms.ship.ShipDtos.PalletPicksForm;
import io.smarthappy.module.wms.ship.ShipDtos.PalletReopenForm;
import io.smarthappy.module.wms.ship.ShipDtos.PickForm;
import io.smarthappy.module.wms.ship.ShipDtos.ShipHead;
import io.smarthappy.module.wms.ship.ShipDtos.ShipView;
import io.smarthappy.module.wms.ship.ShipDtos.UnpickForm;
import io.smarthappy.module.wms.ship.ShipService.ConfirmResult;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 출하(wms-ship) API — P4 2부 B6a (docs/30 12.7)·팔레트 B6b (12.10). 출하 취소(확정 뒤)는 승인 권한 */
@RestController
@RequiredArgsConstructor
public class ShipApiController {

    private static final String BASE = "/api/wms/ships";

    private final ShipService service;
    private final ShipTransportService transport;
    private final LocAttrService locs;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(locs.plants());
    }

    @GetMapping(BASE + "/stages")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<List<NodeOpt>> stages(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.stages(plantCd));
    }

    /** 고객의 열린 수주 행(남은 수량 > 0) */
    @GetMapping(BASE + "/order-lines")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<List<OrderLineOpt>> orderLines(@RequestParam(required = false) String custCd,
            @RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.orderLines(custCd, plantCd));
    }

    /** 출하 라벨 템플릿 */
    @GetMapping(BASE + "/templates")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<List<NodeOpt>> templates(@RequestParam(required = false) String usage) {
        return ApiResponse.ok(service.templates(usage));
    }

    /** 출하 라벨 프린터(용도 SHIP·ANY) */
    @GetMapping(BASE + "/printers")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<List<NodeOpt>> printers(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String usage) {
        return ApiResponse.ok(service.printers(plantCd, usage));
    }

    @GetMapping(BASE)
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String status,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        List<ShipHead> l = service.list(plantCd, status, from, to);
        return ApiResponse.list(l, l.size());
    }

    /** 출하 목록 그리드(UI 단계 3 ①) — list 와 같은 조건 + page·size·sort·total */
    @GetMapping(BASE + "/grid")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String status,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로 읽는다(CustApiController 와 같음)
        String[] raw = request.getParameterValues("sort");
        List<String> sort = raw == null ? null : List.of(raw);
        ShipService.Page<ShipHead> p = service.grid(plantCd, status, from, to, page, size, sort, total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(BASE + "/export")
    @ProgAuth(prog = ShipService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ShipService.PROG, service.columns(), q -> {
            var p = service.grid(q.str("plantCd"), q.str("status"), q.str("from"), q.str("to"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(BASE + "/{id}")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<ShipView> view(@PathVariable long id) {
        return ApiResponse.ok(service.view(id));
    }

    @GetMapping(BASE + "/{id}/lines/{lineId}/suggest")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PickView>> suggest(@PathVariable long id, @PathVariable long lineId) {
        return ApiResponse.ok(service.suggest(id, lineId));
    }

    /** 다른 칸 — 줄 품목의 가용 재고 칸, scan(위치·LOT·품목 스캔 키)으로 좁힘(12.7.5) */
    @GetMapping(BASE + "/{id}/lines/{lineId}/stock")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<ShipDtos.StockPick> stock(@PathVariable long id, @PathVariable long lineId,
            @RequestParam(required = false) String scan) {
        return ApiResponse.ok(service.stock(id, lineId, scan));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipView> create(@RequestBody(required = false) CreateForm form) {
        return ApiResponse.ok(service.view(service.create(form)));
    }

    @PostMapping(BASE + "/{id}/cancel")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipView> cancel(@PathVariable long id, @RequestBody(required = false) CancelForm form) {
        return ApiResponse.ok(service.cancel(id, form));
    }

    /** 운송 정보(docs/58) — 운송사·차량·송장 번호. DRAFT·PICKING·SHIPPED, 머리 rowVer 는 바뀌지 않는다 */
    @PostMapping(BASE + "/{id}/transport")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipView> transport(@PathVariable long id, @RequestBody(required = false) ShipDtos.TransportForm form) {
        transport.save(id, form);
        return ApiResponse.ok(service.view(id));
    }

    /** 출하 특이사항 확인(docs/56) — 확인 필수 규칙은 이것이 있어야 피킹·확정 */
    @PostMapping(BASE + "/{id}/notes/{noteId}/ack")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipView> ackNote(@PathVariable long id, @PathVariable long noteId) {
        return ApiResponse.ok(service.ackNote(id, noteId, null));
    }

    @PostMapping(BASE + "/{id}/pick")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipView> pick(@PathVariable long id, @RequestBody(required = false) PickForm form) {
        return ApiResponse.ok(service.pick(id, form));
    }

    @PostMapping(BASE + "/{id}/unpick")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipView> unpick(@PathVariable long id, @RequestBody(required = false) UnpickForm form) {
        return ApiResponse.ok(service.unpick(id, form));
    }

    @PostMapping(BASE + "/{id}/confirm")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ConfirmResult> confirm(@PathVariable long id, @RequestBody(required = false) ConfirmForm form) {
        return ApiResponse.ok(service.confirm(id, form));
    }

    @PostMapping(BASE + "/{id}/undo")
    @ProgAuth(prog = ShipService.PROG, action = Action.APPROVE)
    public ApiResponse<ConfirmResult> undo(@PathVariable long id, @RequestBody(required = false) CancelForm form) {
        return ApiResponse.ok(service.undo(id, form));
    }

    // ---------------------------------------------------------------- 팔레트 (B6b)

    @PostMapping(BASE + "/scan")
    @ProgAuth(prog = ShipService.PROG, action = Action.SEARCH)
    public ApiResponse<ShipDtos.PalletHit> scan(@RequestBody(required = false) ShipDtos.PalletScanForm form) {
        return ApiResponse.ok(service.scanPallet(form));
    }

    @PostMapping(BASE + "/{id}/pallets")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipView> createPallet(@PathVariable long id, @RequestBody(required = false) PalletForm form) {
        return ApiResponse.ok(service.createPallet(id, form));
    }

    @PostMapping(BASE + "/{id}/pallets/picks")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipView> setPallet(@PathVariable long id, @RequestBody(required = false) PalletPicksForm form) {
        return ApiResponse.ok(service.setPallet(id, form));
    }

    @PostMapping(BASE + "/{id}/pallets/{palletId}/close")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ConfirmResult> closePallet(@PathVariable long id, @PathVariable long palletId,
            @RequestBody(required = false) PalletCloseForm form) {
        return ApiResponse.ok(service.closePallet(id, palletId, form));
    }

    @PostMapping(BASE + "/{id}/pallets/{palletId}/reopen")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipView> reopenPallet(@PathVariable long id, @PathVariable long palletId,
            @RequestBody(required = false) PalletReopenForm form) {
        return ApiResponse.ok(service.reopenPallet(id, palletId, form));
    }

    @PostMapping(BASE + "/{id}/pallets/{palletId}/cancel")
    @ProgAuth(prog = ShipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipView> cancelPallet(@PathVariable long id, @PathVariable long palletId,
            @RequestBody(required = false) PalletForm form) {
        return ApiResponse.ok(service.cancelPallet(id, palletId, form));
    }
}
