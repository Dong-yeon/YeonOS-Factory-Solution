package io.smarthappy.module.production;

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

import io.smarthappy.module.production.LotDtos.HoldForm;
import io.smarthappy.module.production.LotDtos.LotDetail;
import io.smarthappy.module.production.LotDtos.LotList;
import io.smarthappy.module.production.LotDtos.LotRow;
import io.smarthappy.module.production.LotDtos.MergeForm;
import io.smarthappy.module.production.LotDtos.ReasonForm;
import io.smarthappy.module.production.LotDtos.ReceiveForm;
import io.smarthappy.module.production.LotDtos.SplitForm;
import io.smarthappy.module.production.LotDtos.TraceView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/** LOT·계보 API (P2 4단계, docs/25 4.5) */
@RestController
@RequiredArgsConstructor
public class LotApiController {

    private static final String LOT = "/api/production/lots";

    private final LotService service;
    private final MessageSource messages;

    @GetMapping(LOT)
    @ProgAuth(prog = LotService.PROG, action = Action.SEARCH)
    public ApiResponse<LotList> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String itemCd, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String origin, @RequestParam(required = false) String holdOnly) {
        return ApiResponse.ok(service.list(from, to, itemCd, keyword, origin, holdOnly));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(LOT + "/grid")
    @ProgAuth(prog = LotService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String itemCd, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String origin, @RequestParam(required = false) String holdOnly,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로(결정 #47)
        var p = service.grid(from, to, itemCd, keyword, origin, holdOnly, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** grid 엑셀(docs/25 4.40) — grid 메뉴 [엑셀], 권한 EXCEL */
    @org.springframework.web.bind.annotation.PostMapping(LOT + "/export")
    @ProgAuth(prog = LotService.PROG, action = Action.EXCEL)
    public void export(@org.springframework.web.bind.annotation.RequestBody(required = false) LotDtos.LotExport req,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        service.export(req, response);
    }

    @GetMapping(LOT + "/columns")
    @ProgAuth(prog = LotService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(LOT + "/by-no")
    @ProgAuth(prog = LotService.PROG, action = Action.SEARCH)
    public ApiResponse<LotRow> byNo(@RequestParam(required = false) String lotNo) {
        return ApiResponse.ok(service.byNo(lotNo));
    }

    @GetMapping(LOT + "/{lotId}")
    @ProgAuth(prog = LotService.PROG, action = Action.SEARCH)
    public ApiResponse<LotDetail> detail(@PathVariable long lotId) {
        return ApiResponse.ok(service.detail(lotId));
    }

    @GetMapping(LOT + "/{lotId}/trace")
    @ProgAuth(prog = LotService.PROG, action = Action.SEARCH)
    public ApiResponse<TraceView> trace(@PathVariable long lotId, @RequestParam(required = false) String dir) {
        return ApiResponse.ok(service.trace(lotId, dir));
    }

    @PostMapping(LOT + "/receive")
    @ProgAuth(prog = LotService.PROG, action = Action.SAVE)
    public ApiResponse<Long> receive(@RequestBody ReceiveForm form) {
        return ApiResponse.ok(service.receive(form));
    }

    @PostMapping(LOT + "/{lotId}/split")
    @ProgAuth(prog = LotService.PROG, action = Action.SAVE)
    public ApiResponse<List<String>> split(@PathVariable long lotId, @RequestBody SplitForm form) {
        return ApiResponse.ok(service.split(lotId, form));
    }

    @PostMapping(LOT + "/merge")
    @ProgAuth(prog = LotService.PROG, action = Action.SAVE)
    public ApiResponse<Long> merge(@RequestBody MergeForm form) {
        return ApiResponse.ok(service.merge(form));
    }

    @PostMapping(LOT + "/{lotId}/rework")
    @ProgAuth(prog = LotService.PROG, action = Action.SAVE)
    public ApiResponse<Long> rework(@PathVariable long lotId, @RequestBody LotDtos.ReworkLotForm form) {
        return ApiResponse.ok(service.rework(lotId, form));
    }

    @PostMapping(LOT + "/{lotId}/relabel")
    @ProgAuth(prog = LotService.PROG, action = Action.SAVE)
    public ApiResponse<Long> relabel(@PathVariable long lotId, @RequestBody ReasonForm form) {
        return ApiResponse.ok(service.relabel(lotId, form));
    }

    @PutMapping(LOT + "/{lotId}/hold")
    @ProgAuth(prog = LotService.PROG, action = Action.SAVE)
    public ApiResponse<String> hold(@PathVariable long lotId, @RequestBody HoldForm form) {
        service.hold(lotId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(LOT + "/{lotId}/holds/{holdId}/release")
    @ProgAuth(prog = LotService.PROG, action = Action.SAVE)
    public ApiResponse<String> releaseHold(@PathVariable long lotId, @PathVariable long holdId, @RequestBody LotDtos.HoldReleaseForm form) {
        service.releaseHold(lotId, holdId, form);
        return ApiResponse.ok("OK");
    }
}
