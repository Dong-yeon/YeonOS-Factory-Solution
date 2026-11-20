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

import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.production.WoDtos.ActForm;
import io.smarthappy.module.production.WoDtos.AssignForm;
import io.smarthappy.module.production.WoDtos.EquipOption;
import io.smarthappy.module.production.WoDtos.WoDetail;
import io.smarthappy.module.production.WoDtos.WoForm;
import io.smarthappy.module.production.WoDtos.WoList;
import io.smarthappy.module.production.WoDtos.WoOptions;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/** 작업지시 API (P2 3단계, docs/25 4.4) */
@RestController
@RequiredArgsConstructor
public class WoApiController {

    private static final String WO = "/api/production/wos";

    private final WoService service;
    private final MessageSource messages;

    @GetMapping(WO + "/plants")
    @ProgAuth(prog = WoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    @GetMapping(WO + "/options")
    @ProgAuth(prog = WoService.PROG, action = Action.SEARCH)
    public ApiResponse<WoOptions> options(@RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) String date) {
        return ApiResponse.ok(service.options(itemCd, plantCd, date));
    }

    @GetMapping(WO + "/items")
    @ProgAuth(prog = WoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ItemOption>> items(@RequestParam(required = false) String q) {
        return ApiResponse.ok(service.items(q));
    }

    @GetMapping(WO + "/equips")
    @ProgAuth(prog = WoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<EquipOption>> equips(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.equips(plantCd));
    }

    @GetMapping(WO)
    @ProgAuth(prog = WoService.PROG, action = Action.SEARCH)
    public ApiResponse<WoList> list(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String status,
            @RequestParam(required = false) String itemCd, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.list(plantCd, from, to, status, itemCd, keyword));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(WO + "/grid")
    @ProgAuth(prog = WoService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String status,
            @RequestParam(required = false) String itemCd, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로(결정 #47)
        var p = service.grid(plantCd, from, to, status, itemCd, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** grid 엑셀(docs/25 4.40) — grid 메뉴 [엑셀], 권한 EXCEL */
    @org.springframework.web.bind.annotation.PostMapping(WO + "/export")
    @ProgAuth(prog = WoService.PROG, action = Action.EXCEL)
    public void export(@org.springframework.web.bind.annotation.RequestBody(required = false) WoDtos.WoExport req,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        service.export(req, response);
    }

    @GetMapping(WO + "/columns")
    @ProgAuth(prog = WoService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(WO + "/{woId}")
    @ProgAuth(prog = WoService.PROG, action = Action.SEARCH)
    public ApiResponse<WoDetail> detail(@PathVariable long woId) {
        return ApiResponse.ok(service.detail(woId));
    }

    @PostMapping(WO)
    @ProgAuth(prog = WoService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody WoForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(WO + "/{woId}")
    @ProgAuth(prog = WoService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long woId, @RequestBody WoForm form) {
        service.update(woId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(WO + "/{woId}/release")
    @ProgAuth(prog = WoService.PROG, action = Action.SAVE)
    public ApiResponse<String> release(@PathVariable long woId, @RequestBody ActForm form) {
        service.release(woId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(WO + "/{woId}/cancel")
    @ProgAuth(prog = WoService.PROG, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long woId, @RequestBody ActForm form) {
        service.cancel(woId, form);
        return ApiResponse.ok("OK");
    }

    @PutMapping(WO + "/procs/{woProcId}/equip")
    @ProgAuth(prog = WoService.PROG, action = Action.SAVE)
    public ApiResponse<String> assign(@PathVariable long woProcId, @RequestBody AssignForm form) {
        service.assign(woProcId, form);
        return ApiResponse.ok("OK");
    }
}
