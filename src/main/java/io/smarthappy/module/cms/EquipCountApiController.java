package io.smarthappy.module.cms;

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

import io.smarthappy.module.cms.CollectorDtos.PlantRow;
import io.smarthappy.module.cms.EquipCountDtos.CorrectForm;
import io.smarthappy.module.cms.EquipCountDtos.CountRow;
import io.smarthappy.module.cms.EquipCountDtos.EquipOption;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/** 수집 COUNT 조회·정정 API (docs/25 4.36) */
@RestController
@RequiredArgsConstructor
public class EquipCountApiController {

    private static final String BASE = "/api/cms/equip-counts";

    private final EquipCountService service;
    private final MessageSource messages;

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = EquipCountService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    @GetMapping(BASE + "/equips")
    @ProgAuth(prog = EquipCountService.PROG, action = Action.SEARCH)
    public ApiResponse<List<EquipOption>> equips(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.equips(plantCd));
    }

    @GetMapping(BASE + "/grid")
    @ProgAuth(prog = EquipCountService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) Long equipId,
            @RequestParam(required = false) String metricCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로(결정 #47)
        var p = service.grid(plantCd, equipId, metricCd, from, to, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** grid 엑셀(docs/25 4.40) — grid 메뉴 [엑셀], 권한 EXCEL */
    @org.springframework.web.bind.annotation.PostMapping(BASE + "/export")
    @ProgAuth(prog = EquipCountService.PROG, action = Action.EXCEL)
    public void export(@org.springframework.web.bind.annotation.RequestBody(required = false) EquipCountDtos.CountExport req,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        service.export(req, response);
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = EquipCountService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(BASE + "/{countId}")
    @ProgAuth(prog = EquipCountService.PROG, action = Action.SEARCH)
    public ApiResponse<CountRow> detail(@PathVariable long countId) {
        return ApiResponse.ok(service.detail(countId));
    }

    @PostMapping(BASE + "/{countId}/correct")
    @ProgAuth(prog = EquipCountService.PROG, action = Action.SAVE)
    public ApiResponse<Long> correct(@PathVariable long countId, @RequestBody(required = false) CorrectForm in) {
        return ApiResponse.ok(service.correct(countId, in));
    }
}
