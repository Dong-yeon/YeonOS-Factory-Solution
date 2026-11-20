package io.smarthappy.module.pop;

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

import io.smarthappy.module.pop.PopDtos.ActivationCode;
import io.smarthappy.module.pop.PopDtos.NodeOption;
import io.smarthappy.module.pop.PopDtos.PlantOption;
import io.smarthappy.module.pop.PopDtos.RevokeForm;
import io.smarthappy.module.pop.PopDtos.TerminalForm;
import io.smarthappy.module.pop.PopDtos.TerminalRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/** POP 단말 관리 (사무 화면 sys-terminal) */
@RestController
@RequiredArgsConstructor
public class TerminalApiController {

    private static final String BASE = "/api/production/terminals";

    private final TerminalService terminals;
    private final MessageSource messages;

    @GetMapping(BASE)
    @ProgAuth(prog = TerminalService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TerminalRow>> list() {
        return ApiResponse.ok(terminals.list());
    }

    /** grid(UI 단계 3 ①) — page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(BASE + "/grid")
    @ProgAuth(prog = TerminalService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로(결정 #47)
        var p = terminals.grid(page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** grid 엑셀(docs/25 4.40) — grid 메뉴 [엑셀], 권한 EXCEL */
    @org.springframework.web.bind.annotation.PostMapping(BASE + "/export")
    @ProgAuth(prog = TerminalService.PROG, action = Action.EXCEL)
    public void export(@org.springframework.web.bind.annotation.RequestBody(required = false) PopDtos.TerminalExport req,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        terminals.export(req, response);
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = TerminalService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = terminals.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = TerminalService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantOption>> plants() {
        return ApiResponse.ok(terminals.plantOptions());
    }

    @GetMapping(BASE + "/nodes")
    @ProgAuth(prog = TerminalService.PROG, action = Action.SEARCH)
    public ApiResponse<List<NodeOption>> nodes(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(terminals.nodeOptions(plantCd));
    }

    @GetMapping(BASE + "/{id}")
    @ProgAuth(prog = TerminalService.PROG, action = Action.SEARCH)
    public ApiResponse<TerminalRow> get(@PathVariable long id) {
        return ApiResponse.ok(terminals.get(id));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = TerminalService.PROG, action = Action.SAVE)
    public ApiResponse<ActivationCode> create(@RequestBody TerminalForm form) {
        return ApiResponse.ok(terminals.create(form));
    }

    @PutMapping(BASE + "/{id}")
    @ProgAuth(prog = TerminalService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long id, @RequestBody TerminalForm form) {
        terminals.update(id, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/{id}/reissue")
    @ProgAuth(prog = TerminalService.PROG, action = Action.SAVE)
    public ApiResponse<ActivationCode> reissue(@PathVariable long id, @RequestBody RevokeForm form) {
        return ApiResponse.ok(terminals.reissue(id, form == null ? null : form.rowVer()));
    }

    @PostMapping(BASE + "/{id}/revoke")
    @ProgAuth(prog = TerminalService.PROG, action = Action.SAVE)
    public ApiResponse<String> revoke(@PathVariable long id, @RequestBody RevokeForm form) {
        terminals.revoke(id, form);
        return ApiResponse.ok("OK");
    }
}
