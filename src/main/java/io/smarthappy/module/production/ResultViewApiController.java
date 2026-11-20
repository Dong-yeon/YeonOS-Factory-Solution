package io.smarthappy.module.production;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;
import io.smarthappy.module.production.ResultViewDtos.DefectSum;
import io.smarthappy.module.production.ResultViewDtos.ResultExportForm;
import io.smarthappy.module.production.ResultViewDtos.ResultView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 사무 실적 조회 API (docs/25 4.16) — 조회·엑셀만 */
@RestController
@RequiredArgsConstructor
public class ResultViewApiController {

    private static final String RES = "/api/production/results";

    private final ResultViewService service;
    private final MessageSource messages;

    @GetMapping(RES)
    @ProgAuth(prog = ResultViewService.PROG, action = Action.SEARCH)
    public ApiResponse<ResultView> view(@RequestParam(required = false) String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) String shiftCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String procCd, @RequestParam(required = false) String workerCd) {
        return ApiResponse.ok(service.view(view, from, to, plantCd, shiftCd, keyword, itemCd, procCd, workerCd));
    }

    /** 그리드(docs/40 §10.2 D1, docs/29 #45) — 기존 목록 API 는 그대로, 같은 조건·보기 + 페이지·정렬·건수 */
    @GetMapping(RES + "/grid")
    @ProgAuth(prog = ResultViewService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) String shiftCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String procCd, @RequestParam(required = false) String workerCd,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        ResultViewService.Page p = service.grid(view, from, to, plantCd, shiftCd, keyword, itemCd, procCd, workerCd, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** 보기별 열 정의(번역된 제목) — 보기마다 칸이 다르다 */
    @GetMapping(RES + "/columns")
    @ProgAuth(prog = ResultViewService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(@RequestParam(required = false) String view, Locale locale) {
        List<Map<String, Object>> cols = service.columns(view).stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 불량 유형 합계 — 그리드 화면 아래 요약 표(목록 API 의 defects 와 같은 값) */
    @GetMapping(RES + "/defects")
    @ProgAuth(prog = ResultViewService.PROG, action = Action.SEARCH)
    public ApiResponse<List<DefectSum>> defects(@RequestParam(required = false) String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) String shiftCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String procCd, @RequestParam(required = false) String workerCd) {
        return ApiResponse.ok(service.defects(view, from, to, plantCd, shiftCd, keyword, itemCd, procCd, workerCd));
    }

    @GetMapping(RES + "/plants")
    @ProgAuth(prog = ResultViewService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    @GetMapping(RES + "/shifts")
    @ProgAuth(prog = ResultViewService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ShiftRow>> shifts(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.shifts(plantCd));
    }

    @PostMapping(RES + "/excel")
    @ProgAuth(prog = ResultViewService.PROG, action = Action.EXCEL)
    public void excel(@RequestBody(required = false) ResultExportForm in, HttpServletResponse response) throws IOException {
        service.export(in, response);
    }
}
