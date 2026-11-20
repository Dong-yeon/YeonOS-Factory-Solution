package io.smarthappy.module.production;

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

import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.production.ReceiptDtos.CorrectForm;
import io.smarthappy.module.production.ReceiptDtos.LocOption;
import io.smarthappy.module.production.ReceiptDtos.ReceiptView;
import io.smarthappy.module.production.ReceiptDtos.ReceiptWo;
import io.smarthappy.module.production.ReceiptDtos.ReceiveForm;
import io.smarthappy.module.production.ReceiptDtos.ReceiveReply;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/** 생산 입고 (화면 prd-receipt, P2 7a) */
@RestController
@RequiredArgsConstructor
public class ReceiptApiController {

    private static final String BASE = "/api/production/receipts";

    private final ReceiptService service;
    private final MessageSource messages;

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = ReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    @GetMapping(BASE + "/wos")
    @ProgAuth(prog = ReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ReceiptWo>> wos(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.wos(plantCd, status, keyword));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(BASE + "/wos/grid")
    @ProgAuth(prog = ReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> woGrid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로(결정 #47)
        var p = service.woGrid(plantCd, status, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** grid 엑셀(docs/25 4.40) — grid 메뉴 [엑셀], 권한 EXCEL */
    @org.springframework.web.bind.annotation.PostMapping(BASE + "/wos/export")
    @ProgAuth(prog = ReceiptService.PROG, action = Action.EXCEL)
    public void woExport(@org.springframework.web.bind.annotation.RequestBody(required = false) ReceiptDtos.ReceiptWoExport req,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        service.woExport(req, response);
    }

    @GetMapping(BASE + "/wos/columns")
    @ProgAuth(prog = ReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> woColumns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(BASE + "/wos/{woId}")
    @ProgAuth(prog = ReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<ReceiptView> view(@PathVariable long woId) {
        return ApiResponse.ok(service.view(woId));
    }

    @GetMapping(BASE + "/locations")
    @ProgAuth(prog = ReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<List<LocOption>> locations(@RequestParam(required = false) String plantCd, @RequestParam(required = false) Long woId,
            @RequestParam(required = false) Long lotId) {
        return ApiResponse.ok(service.locations(plantCd, woId, lotId));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = ReceiptService.PROG, action = Action.SAVE)
    public ApiResponse<ReceiveReply> receive(@RequestBody ReceiveForm in) {
        return ApiResponse.ok(service.receive(in));
    }

    @PostMapping(BASE + "/{receiptId}/correct")
    @ProgAuth(prog = ReceiptService.PROG, action = Action.SAVE)
    public ApiResponse<Long> correct(@PathVariable long receiptId, @RequestBody CorrectForm in) {
        return ApiResponse.ok(service.correct(receiptId, in));
    }
}
