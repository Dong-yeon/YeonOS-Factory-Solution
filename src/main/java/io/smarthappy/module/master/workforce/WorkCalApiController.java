package io.smarthappy.module.master.workforce;

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
import io.smarthappy.module.master.workforce.WorkCalDtos.CalDisable;
import io.smarthappy.module.master.workforce.WorkCalDtos.CalForm;
import io.smarthappy.module.master.workforce.WorkCalDtos.MonthView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 공장 휴일 달력 API (docs/25 4.15) */
@RestController
@RequiredArgsConstructor
public class WorkCalApiController {

    private static final String CAL = "/api/master/work-cal";

    private final WorkCalService service;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(CAL + "/plants")
    @ProgAuth(prog = WorkCalService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    /** 한 달 — plantCd 비우면 회사 공통 */
    @GetMapping(CAL)
    @ProgAuth(prog = WorkCalService.PROG, action = Action.SEARCH)
    public ApiResponse<MonthView> month(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String month) {
        return ApiResponse.ok(service.month(plantCd, month));
    }

    /** 예외 목록 그리드(UI 단계 3 ①) — 한 달 API 는 그대로(달력 칸), 같은 조건(범위·달) + 페이지·정렬·건수 */
    @GetMapping(CAL + "/grid")
    @ProgAuth(prog = WorkCalService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String month,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        WorkCalService.Page p = service.grid(plantCd, month, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(CAL + "/columns")
    @ProgAuth(prog = WorkCalService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(CAL + "/export")
    @ProgAuth(prog = WorkCalService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, WorkCalService.PROG, service.columns(), q -> {
            var p = service.grid(q.str("plantCd"), q.str("month"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @PostMapping(CAL)
    @ProgAuth(prog = WorkCalService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody CalForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(CAL + "/{calId}")
    @ProgAuth(prog = WorkCalService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long calId, @RequestBody CalForm form) {
        service.update(calId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(CAL + "/{calId}/disable")
    @ProgAuth(prog = WorkCalService.PROG, action = Action.SAVE)
    public ApiResponse<String> disable(@PathVariable long calId, @RequestBody CalDisable form) {
        service.disable(calId, form);
        return ApiResponse.ok("OK");
    }
}
