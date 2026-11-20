package io.smarthappy.module.qms;

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

import io.smarthappy.module.qms.ChangeDtos.ChangeDetail;
import io.smarthappy.module.qms.ChangeDtos.ChangeForm;
import io.smarthappy.module.qms.ChangeDtos.ChangePage;
import io.smarthappy.module.qms.ChangeDtos.ChangeRow;
import io.smarthappy.module.qms.ChangeDtos.Choice;
import io.smarthappy.module.qms.ChangeDtos.DeleteForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 4M 변경 API (P3 QMS 2부 Q5, docs/29_P3 11장) — 키는 ECO id. 저장 = SAVE, 지우기 = DELETE */
@RestController
@RequiredArgsConstructor
public class ChangeApiController {

    private static final String C = "/api/qms/changes";

    private final ChangeService changes;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(C)
    @ProgAuth(prog = ChangeService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ChangeRow>> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String m4Type,
            @RequestParam(required = false) String revertDue) {
        return ApiResponse.ok(changes.list(keyword, m4Type, revertDue));
    }

    /** 그리드(UI 단계 3) — 목록과 같은 조건 + page·size·sort. sort 는 원래 값 그대로(List 로 받으면 스프링이 쉼표로 쪼갠다, 결정 #47) */
    @GetMapping(C + "/grid")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String m4Type,
            @RequestParam(required = false) String revertDue,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        ChangePage p = changes.grid(keyword, m4Type, revertDue, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(C + "/columns")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = changes.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(C + "/export")
    @ProgAuth(prog = ChangeService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ChangeService.PROG, changes.gridColumns(), q -> {
            var p = changes.grid(q.str("keyword"), q.str("m4Type"), q.str("revertDue"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    /** ECO 번호 → ECO id (화면 "ECO 번호로 열기") */
    @GetMapping(C + "/by-no")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SEARCH)
    public ApiResponse<Long> byNo(@RequestParam(required = false) String ecoNo) {
        return ApiResponse.ok(changes.ecoIdByNo(ecoNo));
    }

    @GetMapping(C + "/{ecoId}")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SEARCH)
    public ApiResponse<ChangeDetail> detail(@PathVariable long ecoId) {
        return ApiResponse.ok(changes.detail(ecoId));
    }

    @GetMapping(C + "/{ecoId}/choices")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SEARCH)
    public ApiResponse<List<Choice>> choices(@PathVariable long ecoId) {
        return ApiResponse.ok(changes.choices(ecoId));
    }

    @PutMapping(C + "/{ecoId}")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SAVE)
    public ApiResponse<String> save(@PathVariable long ecoId, @RequestBody ChangeForm form) {
        changes.save(ecoId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(C + "/{ecoId}/delete")
    @ProgAuth(prog = ChangeService.PROG, action = Action.DELETE)
    public ApiResponse<String> delete(@PathVariable long ecoId, @RequestBody DeleteForm form) {
        changes.delete(ecoId, form);
        return ApiResponse.ok("OK");
    }
}
