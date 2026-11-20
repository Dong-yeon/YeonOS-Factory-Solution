package io.smarthappy.module.qms;

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

import io.smarthappy.module.qms.SpcDtos.ChartQuery;
import io.smarthappy.module.qms.SpcDtos.ChartUnit;
import io.smarthappy.module.qms.SpcDtos.ChartView;
import io.smarthappy.module.qms.SpcDtos.LimitForm;
import io.smarthappy.module.qms.SpcDtos.LimitRow;
import io.smarthappy.module.qms.SpcDtos.UnitPage;
import io.smarthappy.module.qms.SpcDtos.VoidForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** SPC API (P3 QMS 2부 Q10, docs/29_P3 12.6) — 관리도 조회 = SEARCH, 고정 한계 정하기 = SAVE, 무효 = DELETE */
@RestController
@RequiredArgsConstructor
public class SpcApiController {

    private static final String S = "/api/qms/spc";

    private final SpcService spc;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(S + "/units")
    @ProgAuth(prog = SpcService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ChartUnit>> units(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(spc.units(keyword));
    }

    /** 관리도 단위 그리드(UI 단계 3) — 목록과 같은 조건 + page·size·sort(원래 값 그대로, 결정 #47) */
    @GetMapping(S + "/units/grid")
    @ProgAuth(prog = SpcService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> unitGrid(@RequestParam(required = false) String keyword, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        UnitPage p = spc.unitGrid(keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(S + "/units/columns")
    @ProgAuth(prog = SpcService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> unitColumns(Locale locale) {
        List<Map<String, Object>> cols = spc.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(S + "/units/export")
    @ProgAuth(prog = SpcService.PROG, action = Action.EXCEL)
    public void unitExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, SpcService.PROG, spc.gridColumns(), q -> {
            var p = spc.unitGrid(q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(S + "/chart")
    @ProgAuth(prog = SpcService.PROG, action = Action.SEARCH)
    public ApiResponse<ChartView> chart(@RequestParam(required = false) Long itemId, @RequestParam(required = false) Long charId,
            @RequestParam(required = false) String inspType, @RequestParam(required = false) Long procId,
            @RequestParam(required = false) Integer n, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) List<String> rules) {
        return ApiResponse.ok(spc.chart(new ChartQuery(itemId, charId, inspType, procId, n, to, rules)));
    }

    @GetMapping(S + "/limits")
    @ProgAuth(prog = SpcService.PROG, action = Action.SEARCH)
    public ApiResponse<List<LimitRow>> limits(@RequestParam long itemId, @RequestParam long charId, @RequestParam String inspType,
            @RequestParam(required = false) Long procId) {
        return ApiResponse.ok(spc.limits(itemId, charId, inspType, procId));
    }

    @PostMapping(S + "/limits")
    @ProgAuth(prog = SpcService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createLimit(@RequestBody LimitForm form) {
        return ApiResponse.ok(spc.createLimit(form));
    }

    @PostMapping(S + "/limits/{limitId}/void")
    @ProgAuth(prog = SpcService.PROG, action = Action.DELETE)
    public ApiResponse<String> voidLimit(@PathVariable long limitId, @RequestBody VoidForm form) {
        spc.voidLimit(limitId, form);
        return ApiResponse.ok("OK");
    }
}
