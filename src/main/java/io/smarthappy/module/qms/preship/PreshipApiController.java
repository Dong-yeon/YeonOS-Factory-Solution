package io.smarthappy.module.qms.preship;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.qms.preship.PreshipDtos.AllowForm;
import io.smarthappy.module.qms.preship.PreshipDtos.CancelForm;
import io.smarthappy.module.qms.preship.PreshipDtos.CloseForm;
import io.smarthappy.module.qms.preship.PreshipDtos.HeadForm;
import io.smarthappy.module.qms.preship.PreshipDtos.InspHead;
import io.smarthappy.module.qms.preship.PreshipDtos.JudgeForm;
import io.smarthappy.module.qms.preship.PreshipDtos.JudgeResult;
import io.smarthappy.module.qms.preship.PreshipDtos.NcrForm;
import io.smarthappy.module.qms.preship.PreshipDtos.PunchPage;
import io.smarthappy.module.qms.preship.PreshipDtos.PunchRow;
import io.smarthappy.module.qms.preship.PreshipDtos.ShipDetail;
import io.smarthappy.module.qms.preship.PreshipDtos.ShipPage;
import io.smarthappy.module.qms.preship.PreshipDtos.StartForm;
import io.smarthappy.module.qms.preship.PreshipDtos.TplForm;
import io.smarthappy.module.qms.preship.PreshipDtos.TplPage;
import io.smarthappy.module.qms.preship.PreshipDtos.TplView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.excel.GridExcel;
import io.smarthappy.platform.grid.ColumnSpec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 출하 전 검수 API (docs/78 6장) — 양식 qms-preship-tpl(SEARCH·SAVE·EXCEL), 실행 qms-preship(SEARCH·SAVE·APPROVE·EXCEL),
 * 미결 qms-preship-punch(SEARCH·SAVE·APPROVE·EXCEL). 지우는 API 없음(사용 중지·취소). 사진은 공통 첨부 /api/platform/files(대상 qms-preship)
 */
@RestController
@RequiredArgsConstructor
public class PreshipApiController {

    private static final String B = "/api/qms/preship";

    private final PreshipTplService tpls;
    private final PreshipService insps;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    private List<Map<String, Object>> cols(List<ColumnSpec> c, Locale locale) {
        return c.stream().map(x -> x.toView(messages, locale)).toList();
    }

    private static List<String> sort(HttpServletRequest request) {
        String[] s = request.getParameterValues("sort");
        return s == null ? null : List.of(s);
    }

    // ---------------------------------------------------------------- 양식

    @GetMapping(B + "/tpls/grid")
    @ProgAuth(prog = PreshipTplService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> tplGrid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        TplPage p = tpls.grid(keyword, useYn, page, size, sort(request), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(B + "/tpls/columns")
    @ProgAuth(prog = PreshipTplService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> tplColumns(Locale locale) {
        List<Map<String, Object>> c = cols(tpls.columns(), locale);
        return ApiResponse.list(c, c.size());
    }

    @PostMapping(B + "/tpls/export")
    @ProgAuth(prog = PreshipTplService.PROG, action = Action.EXCEL)
    public void tplExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, PreshipTplService.PROG, tpls.columns(), q -> {
            var p = tpls.grid(q.str("keyword"), q.str("useYn"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(B + "/tpls/{tplId}")
    @ProgAuth(prog = PreshipTplService.PROG, action = Action.SEARCH)
    public ApiResponse<TplView> tpl(@PathVariable long tplId) {
        return ApiResponse.ok(tpls.get(tplId));
    }

    @PostMapping(B + "/tpls")
    @ProgAuth(prog = PreshipTplService.PROG, action = Action.SAVE)
    public ApiResponse<Long> tplCreate(@RequestBody TplForm form) {
        return ApiResponse.ok(tpls.create(form));
    }

    @PutMapping(B + "/tpls/{tplId}")
    @ProgAuth(prog = PreshipTplService.PROG, action = Action.SAVE)
    public ApiResponse<String> tplUpdate(@PathVariable long tplId, @RequestBody TplForm form) {
        tpls.update(tplId, form);
        return ApiResponse.ok("OK");
    }

    // ---------------------------------------------------------------- 출하 목록·검수

    @GetMapping(B + "/ships/grid")
    @ProgAuth(prog = PreshipService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> shipGrid(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String inspStatus,
            @RequestParam(required = false) Boolean onlyRequired, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        ShipPage p = insps.shipGrid(from, to, keyword, inspStatus, onlyRequired, page, size, sort(request), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(B + "/ships/columns")
    @ProgAuth(prog = PreshipService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> shipColumns(Locale locale) {
        List<Map<String, Object>> c = cols(insps.shipColumns(), locale);
        return ApiResponse.list(c, c.size());
    }

    @PostMapping(B + "/ships/export")
    @ProgAuth(prog = PreshipService.PROG, action = Action.EXCEL)
    public void shipExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, PreshipService.PROG, insps.shipColumns(), q -> {
            var p = insps.shipGrid(q.date("from"), q.date("to"), q.str("keyword"), q.str("inspStatus"), q.bool("onlyRequired"),
                    q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(B + "/ships/{shipId}")
    @ProgAuth(prog = PreshipService.PROG, action = Action.SEARCH)
    public ApiResponse<ShipDetail> ship(@PathVariable long shipId) {
        return ApiResponse.ok(insps.shipDetail(shipId));
    }

    @PostMapping(B + "/insps")
    @ProgAuth(prog = PreshipService.PROG, action = Action.SAVE)
    public ApiResponse<ShipDetail> start(@RequestBody StartForm form) {
        return ApiResponse.ok(insps.start(form));
    }

    @PostMapping(B + "/insps/{inspId}/judge")
    @ProgAuth(prog = PreshipService.PROG, action = Action.SAVE)
    public ApiResponse<JudgeResult> judge(@PathVariable long inspId, @RequestBody JudgeForm form) {
        return ApiResponse.ok(insps.judge(inspId, form));
    }

    @PutMapping(B + "/insps/{inspId}")
    @ProgAuth(prog = PreshipService.PROG, action = Action.SAVE)
    public ApiResponse<InspHead> saveHead(@PathVariable long inspId, @RequestBody HeadForm form) {
        return ApiResponse.ok(insps.saveHead(inspId, form));
    }

    @PostMapping(B + "/insps/{inspId}/complete")
    @ProgAuth(prog = PreshipService.PROG, action = Action.APPROVE)
    public ApiResponse<InspHead> complete(@PathVariable long inspId, @RequestBody HeadForm form) {
        return ApiResponse.ok(insps.complete(inspId, form));
    }

    @PostMapping(B + "/insps/{inspId}/reopen")
    @ProgAuth(prog = PreshipService.PROG, action = Action.APPROVE)
    public ApiResponse<InspHead> reopen(@PathVariable long inspId, @RequestBody CancelForm form) {
        return ApiResponse.ok(insps.reopen(inspId, form));
    }

    @PostMapping(B + "/insps/{inspId}/cancel")
    @ProgAuth(prog = PreshipService.PROG, action = Action.SAVE)
    public ApiResponse<InspHead> cancel(@PathVariable long inspId, @RequestBody CancelForm form) {
        return ApiResponse.ok(insps.cancel(inspId, form));
    }

    // ---------------------------------------------------------------- 미결

    @GetMapping(B + "/punches/grid")
    @ProgAuth(prog = PreshipService.PROG_PUNCH, action = Action.SEARCH)
    public ApiResponse<Void> punchGrid(@RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        PunchPage p = insps.punchGrid(status, keyword, page, size, sort(request), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(B + "/punches/columns")
    @ProgAuth(prog = PreshipService.PROG_PUNCH, action = Action.SEARCH)
    public ApiResponse<Void> punchColumns(Locale locale) {
        List<Map<String, Object>> c = cols(insps.punchColumns(), locale);
        return ApiResponse.list(c, c.size());
    }

    @PostMapping(B + "/punches/export")
    @ProgAuth(prog = PreshipService.PROG_PUNCH, action = Action.EXCEL)
    public void punchExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, PreshipService.PROG_PUNCH, insps.punchColumns(), q -> {
            var p = insps.punchGrid(q.str("status"), q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(B + "/punches/{punchId}")
    @ProgAuth(prog = PreshipService.PROG_PUNCH, action = Action.SEARCH)
    public ApiResponse<PunchRow> punch(@PathVariable long punchId) {
        return ApiResponse.ok(insps.punch(punchId));
    }

    @PostMapping(B + "/punches/{punchId}/close")
    @ProgAuth(prog = PreshipService.PROG_PUNCH, action = Action.SAVE)
    public ApiResponse<PunchRow> close(@PathVariable long punchId, @RequestBody CloseForm form) {
        return ApiResponse.ok(insps.close(punchId, form));
    }

    @PostMapping(B + "/punches/{punchId}/allow")
    @ProgAuth(prog = PreshipService.PROG_PUNCH, action = Action.APPROVE)
    public ApiResponse<PunchRow> allow(@PathVariable long punchId, @RequestBody AllowForm form) {
        return ApiResponse.ok(insps.allow(punchId, form));
    }

    @PostMapping(B + "/punches/{punchId}/ncr")
    @ProgAuth(prog = PreshipService.PROG_PUNCH, action = Action.SAVE)
    public ApiResponse<PunchRow> ncr(@PathVariable long punchId, @RequestBody NcrForm form) {
        return ApiResponse.ok(insps.linkNcr(punchId, form));
    }
}
