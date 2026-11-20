package io.smarthappy.module.wms.labeler;

import java.time.LocalDate;
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

import io.smarthappy.module.wms.labeler.LabelerDtos.BoxForm;
import io.smarthappy.module.wms.labeler.LabelerDtos.BoxQuery;
import io.smarthappy.module.wms.labeler.LabelerDtos.BoxRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.DevLogRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.EventRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.LabelerForm;
import io.smarthappy.module.wms.labeler.LabelerDtos.LabelerRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 오토라벨러 화면 API (wms-labeler, docs/65 21.5) — 삭제 없음(라벨러 사용 중지, 박스는 원장과 함께 남는다) */
@RestController
@RequiredArgsConstructor
public class LabelerApiController {

    private static final String LBL = "/api/wms/labelers";
    private static final String BOX = "/api/wms/labeler-boxes";

    private final LabelerService service;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(LBL)
    @ProgAuth(prog = LabelerService.PROG, action = Action.SEARCH)
    public ApiResponse<List<LabelerRow>> labelers(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.labelers(plantCd));
    }

    @PostMapping(LBL)
    @ProgAuth(prog = LabelerService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createLabeler(@RequestBody LabelerForm form) {
        return ApiResponse.ok(service.createLabeler(form));
    }

    @PutMapping(LBL + "/{id}")
    @ProgAuth(prog = LabelerService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateLabeler(@PathVariable long id, @RequestBody LabelerForm form) {
        service.updateLabeler(id, form);
        return ApiResponse.ok("OK");
    }

    @GetMapping(LBL + "/options")
    @ProgAuth(prog = LabelerService.PROG, action = Action.SEARCH)
    public ApiResponse<Map<String, Object>> options() {
        return ApiResponse.ok(service.options());
    }

    @GetMapping(LBL + "/{id}/device-log")
    @ProgAuth(prog = LabelerService.PROG, action = Action.SEARCH)
    public ApiResponse<List<DevLogRow>> devLog(@PathVariable long id) {
        return ApiResponse.ok(service.devLogs(id));
    }

    @GetMapping(BOX + "/grid")
    @ProgAuth(prog = LabelerService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> boxes(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String status,
            @RequestParam(required = false) String boxCd, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] raw = request.getParameterValues("sort");                   // 쉼표째(List 바인딩은 쪼갠다)
        LabelerService.Page p = service.boxes(new BoxQuery(plantCd, status, boxCd, from, to), page, size,
                raw == null ? null : List.of(raw), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BOX + "/columns")
    @ProgAuth(prog = LabelerService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.boxColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(BOX + "/export")
    @ProgAuth(prog = LabelerService.PROG, action = Action.EXCEL)
    public void boxesExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, LabelerService.PROG, service.boxColumns(), q -> {
            var p = service.boxes(new BoxQuery(q.str("plantCd"), q.str("status"), q.str("boxCd"), q.date("from"), q.date("to")), q.page(),
                    q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(BOX + "/{id}")
    @ProgAuth(prog = LabelerService.PROG, action = Action.SEARCH)
    public ApiResponse<BoxRow> box(@PathVariable long id) {
        return ApiResponse.ok(service.box(id));
    }

    @GetMapping(BOX + "/{id}/events")
    @ProgAuth(prog = LabelerService.PROG, action = Action.SEARCH)
    public ApiResponse<List<EventRow>> events(@PathVariable long id) {
        return ApiResponse.ok(service.events(id));
    }

    @PostMapping(BOX)
    @ProgAuth(prog = LabelerService.PROG, action = Action.SAVE)
    public ApiResponse<Long> register(@RequestBody BoxForm form) {
        return ApiResponse.ok(service.registerBox(form));
    }

    @PutMapping(BOX + "/{id}")
    @ProgAuth(prog = LabelerService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long id, @RequestBody BoxForm form) {
        service.updateBox(id, form);
        return ApiResponse.ok("OK");
    }
}
