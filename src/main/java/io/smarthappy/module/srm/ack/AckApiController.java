package io.smarthappy.module.srm.ack;

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

import io.smarthappy.module.srm.ack.AckDtos.AckDetail;
import io.smarthappy.module.srm.ack.AckDtos.AckForm;
import io.smarthappy.module.srm.ack.AckDtos.AckPage;
import io.smarthappy.module.srm.ack.AckDtos.AckPoRow;
import io.smarthappy.module.srm.ack.AckDtos.AckResult;
import io.smarthappy.module.srm.ack.AckDtos.StatusPage;
import io.smarthappy.module.srm.ack.AckDtos.StatusRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 납기 회신·발주 진행 현황 API (SRM S5, docs/31 4.5) */
@RestController
@RequiredArgsConstructor
public class AckApiController {

    private static final String API = "/api/srm/acks";

    private final AckService service;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    /** 회신할 발주 — state OPEN(기본) / NO_ACK / ACKED / ALL */
    @GetMapping(API + "/pos")
    @ProgAuth(prog = AckService.PROG, action = Action.SEARCH)
    public ApiResponse<List<AckPoRow>> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String state, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.list(from, to, state, keyword));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(API + "/pos/grid")
    @ProgAuth(prog = AckService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String state, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        AckPage p = service.grid(from, to, state, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(API + "/pos/columns")
    @ProgAuth(prog = AckService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(API + "/pos/export")
    @ProgAuth(prog = AckService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, AckService.PROG, service.gridColumns(), q -> {
            var p = service.grid(q.str("from"), q.str("to"), q.str("state"), q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(API + "/pos/{poId}")
    @ProgAuth(prog = AckService.PROG, action = Action.SEARCH)
    public ApiResponse<AckDetail> get(@PathVariable long poId) {
        return ApiResponse.ok(service.get(poId));
    }

    /** 회신 입력(협력사 대신) — 새 회신 묶음. 응답 = 묶음 id·순번·상태·경고 */
    @PostMapping(API + "/pos/{poId}")
    @ProgAuth(prog = AckService.PROG, action = Action.SAVE)
    public ApiResponse<AckResult> record(@PathVariable long poId, @RequestBody AckForm form) {
        return ApiResponse.ok(service.record(poId, form));
    }

    /** 진행 현황 grid(UI 단계 3 ①) — 목록과 같은 조건(보기·임박 일수) + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping("/api/srm/po-status/grid")
    @ProgAuth(prog = AckService.STATUS_PROG, action = Action.SEARCH)
    public ApiResponse<Void> statusGrid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String view, @RequestParam(required = false) String custCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String soonDays,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        StatusPage p = service.statusGrid(from, to, view, custCd, keyword, soonDays, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping("/api/srm/po-status/columns")
    @ProgAuth(prog = AckService.STATUS_PROG, action = Action.SEARCH)
    public ApiResponse<Void> statusColumns(Locale locale) {
        List<Map<String, Object>> cols = service.statusColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping("/api/srm/po-status/export")
    @ProgAuth(prog = AckService.STATUS_PROG, action = Action.EXCEL)
    public void statusExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, AckService.STATUS_PROG, service.statusColumns(), q -> {
            var p = service.statusGrid(q.str("from"), q.str("to"), q.str("view"), q.str("custCd"), q.str("keyword"),
                    q.str("soonDays"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    /** 발주 진행 현황 — view ALL / NO_ACK / LATE / DUE_SOON(soonDays, 기본 7) */
    @GetMapping("/api/srm/po-status")
    @ProgAuth(prog = AckService.STATUS_PROG, action = Action.SEARCH)
    public ApiResponse<List<StatusRow>> status(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String view, @RequestParam(required = false) String custCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String soonDays) {
        return ApiResponse.ok(service.status(from, to, view, custCd, keyword, soonDays));
    }
}
