package io.smarthappy.module.qms;

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

import io.smarthappy.module.qms.SpcEventDtos.ActForm;
import io.smarthappy.module.qms.SpcEventDtos.EventDetail;
import io.smarthappy.module.qms.SpcEventDtos.EventPage;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** SPC 위반 API (docs/29_P3 19장 SV-7) — 화면 qms-spc "위반": 조회 = SEARCH, 처리(ACK·ACTION·CLOSE·VOID) = SAVE */
@RestController
@RequiredArgsConstructor
public class SpcEventApiController {

    private static final String E = "/api/qms/spc/events";

    private final SpcEventService events;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    /** 그리드 — 조건: 검색어·상태(OPEN_ALL 포함)·관리도 단위(품목·특성·유형·공정) + page·size·sort(원래 값 그대로, 결정 #47) */
    @GetMapping(E + "/grid")
    @ProgAuth(prog = SpcService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(required = false) Long itemId, @RequestParam(required = false) Long charId,
            @RequestParam(required = false) String inspType, @RequestParam(required = false) Long procId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        EventPage p = events.grid(keyword, status, itemId, charId, inspType, procId, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(E + "/columns")
    @ProgAuth(prog = SpcService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = events.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(E + "/export")
    @ProgAuth(prog = SpcService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, SpcService.PROG, events.gridColumns(), q -> {
            var p = events.grid(q.str("keyword"), q.str("status"), q.longValue("itemId"), q.longValue("charId"), q.str("inspType"),
                    q.longValue("procId"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(E + "/{eventId}")
    @ProgAuth(prog = SpcService.PROG, action = Action.SEARCH)
    public ApiResponse<EventDetail> detail(@PathVariable long eventId) {
        return ApiResponse.ok(events.detail(eventId));
    }

    /** 조치(ACTION) 폼의 OCAP 고르기 목록 — 그 위반 규칙 + 공통의 사용 중 항목(docs/29_P3 26장 OC-8) */
    @GetMapping(E + "/{eventId}/ocaps")
    @ProgAuth(prog = SpcService.PROG, action = Action.SEARCH)
    public ApiResponse<List<OcapDtos.OcapRow>> ocaps(@PathVariable long eventId) {
        return ApiResponse.ok(events.ocapOptions(eventId));
    }

    @PostMapping(E + "/{eventId}/acts")
    @ProgAuth(prog = SpcService.PROG, action = Action.SAVE)
    public ApiResponse<String> act(@PathVariable long eventId, @RequestBody ActForm form) {
        events.act(eventId, form);
        return ApiResponse.ok("OK");
    }
}
