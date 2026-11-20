package io.smarthappy.module.wms.shipnote;

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

import io.smarthappy.module.wms.shipnote.ShipNoteDtos.ClassOpt;
import io.smarthappy.module.wms.shipnote.ShipNoteDtos.Note;
import io.smarthappy.module.wms.shipnote.ShipNoteDtos.SaveForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 출하 특이사항 규칙 화면(wms-ship-note, docs/56) — 목록·상세·저장(추가·수정). 삭제 없음(사용 끔). 확인은 출하 화면·PDA 에서 */
@RestController
@RequiredArgsConstructor
public class ShipNoteApiController {

    private static final String BASE = "/api/wms/ship-notes";

    private final ShipNoteService service;
    private final PolicyService policy;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(BASE + "/grid")
    @ProgAuth(prog = ShipNoteService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String q, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) String ackRequiredYn, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로 읽는다(CustApiController 와 같음)
        String[] raw = request.getParameterValues("sort");
        List<String> sort = raw == null ? null : List.of(raw);
        ShipNoteDtos.Page<Note> p = service.grid(q, useYn, ackRequiredYn, page, size, sort, total, policy.intValue(Policies.GRID_PAGE_SIZE_MAX));
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = ShipNoteService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(BASE + "/export")
    @ProgAuth(prog = ShipNoteService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ShipNoteService.PROG, service.columns(), q -> {
            var p = service.grid(q.str("q"), q.str("useYn"), q.str("ackRequiredYn"), q.page(), q.size(), q.sort(), q.total(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX));
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(BASE + "/classes")
    @ProgAuth(prog = ShipNoteService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ClassOpt>> classes() {
        return ApiResponse.ok(service.classes());
    }

    @GetMapping(BASE + "/{id}")
    @ProgAuth(prog = ShipNoteService.PROG, action = Action.SEARCH)
    public ApiResponse<Note> view(@PathVariable long id) {
        return ApiResponse.ok(service.view(id));
    }

    /** 추가(noteId 없음)·수정(rowVer) — 글을 고치면 규칙 버전이 올라 걸린 지시는 다시 확인 */
    @PostMapping(BASE)
    @ProgAuth(prog = ShipNoteService.PROG, action = Action.SAVE)
    public ApiResponse<Note> save(@RequestBody SaveForm form) {
        return ApiResponse.ok(service.save(form));
    }
}
