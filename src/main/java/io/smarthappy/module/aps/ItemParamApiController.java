package io.smarthappy.module.aps;

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

import io.smarthappy.module.aps.ItemParamDtos.ParamForm;
import io.smarthappy.module.aps.ItemParamDtos.ParamPage;
import io.smarthappy.module.aps.ItemParamDtos.ParamRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 계획 기준정보 API (docs/38 17.5) — 조회 SEARCH, 등록·수정 SAVE. 삭제 없음(사용 중지) */
@RestController
@RequiredArgsConstructor
public class ItemParamApiController {

    private static final String BASE = "/api/aps/item-params";

    private final ItemParamService service;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = ItemParamService.PROG, action = Action.SEARCH)
    public ApiResponse<List<io.smarthappy.module.master.equip.EquipDtos.PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    @GetMapping(BASE + "/grid")
    @ProgAuth(prog = ItemParamService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String useYn, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");    // 쉼표 값 그대로(docs/29 #47)
        ParamPage p = service.grid(plantCd, keyword, useYn, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = ItemParamService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(BASE + "/export")
    @ProgAuth(prog = ItemParamService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ItemParamService.PROG, service.gridColumns(), q -> {
            var p = service.grid(q.str("plantCd"), q.str("keyword"), q.str("useYn"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(BASE + "/{paramId}")
    @ProgAuth(prog = ItemParamService.PROG, action = Action.SEARCH)
    public ApiResponse<ParamRow> get(@PathVariable long paramId) {
        return ApiResponse.ok(service.get(paramId));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = ItemParamService.PROG, action = Action.SAVE)
    public ApiResponse<ParamRow> create(@RequestBody ParamForm form) {
        return ApiResponse.ok(service.save(null, form));
    }

    @PutMapping(BASE + "/{paramId}")
    @ProgAuth(prog = ItemParamService.PROG, action = Action.SAVE)
    public ApiResponse<ParamRow> update(@PathVariable long paramId, @RequestBody ParamForm form) {
        return ApiResponse.ok(service.save(paramId, form));
    }
}
