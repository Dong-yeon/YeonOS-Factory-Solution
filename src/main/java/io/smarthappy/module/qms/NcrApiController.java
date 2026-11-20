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

import io.smarthappy.module.qms.NcrDtos.CancelForm;
import io.smarthappy.module.qms.NcrDtos.CloseForm;
import io.smarthappy.module.qms.NcrDtos.CreateForm;
import io.smarthappy.module.qms.NcrDtos.DefectOption;
import io.smarthappy.module.qms.NcrDtos.NcrDetail;
import io.smarthappy.module.qms.NcrDtos.NcrPage;
import io.smarthappy.module.qms.NcrDtos.NcrRow;
import io.smarthappy.module.qms.NcrDtos.NcrSubmitForm;
import io.smarthappy.module.qms.NcrDtos.SaveForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 부적합(NCR) API (P3 QMS 2부 Q8a, docs/29_P3 12.7) — 등록·저장·종결·특채 상신 = SAVE, 취소 = DELETE */
@RestController
@RequiredArgsConstructor
public class NcrApiController {

    private static final String N = "/api/qms/ncrs";

    private final NcrService ncrs;
    private final DocService docs;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(N)
    @ProgAuth(prog = NcrService.PROG, action = Action.SEARCH)
    public ApiResponse<List<NcrRow>> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(required = false) String source, @RequestParam(required = false) String basis) {
        return ApiResponse.ok(ncrs.list(keyword, status, source, basis));
    }

    /** 그리드(UI 단계 3) — 목록과 같은 조건 + page·size·sort. sort 는 원래 값 그대로(List 로 받으면 스프링이 쉼표로 쪼갠다, 결정 #47) */
    @GetMapping(N + "/grid")
    @ProgAuth(prog = NcrService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(required = false) String source, @RequestParam(required = false) String basis, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        NcrPage p = ncrs.grid(keyword, status, source, basis, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(N + "/columns")
    @ProgAuth(prog = NcrService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = ncrs.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(N + "/export")
    @ProgAuth(prog = NcrService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, NcrService.PROG, ncrs.gridColumns(), q -> {
            var p = ncrs.grid(q.str("keyword"), q.str("status"), q.str("source"), q.str("basis"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(N + "/defects")
    @ProgAuth(prog = NcrService.PROG, action = Action.SEARCH)
    public ApiResponse<List<DefectOption>> defects() {
        return ApiResponse.ok(ncrs.defects());
    }

    @GetMapping(N + "/users")
    @ProgAuth(prog = NcrService.PROG, action = Action.SEARCH)
    public ApiResponse<List<UserOption>> users() {
        return ApiResponse.ok(docs.users());
    }

    @GetMapping(N + "/{ncrId}")
    @ProgAuth(prog = NcrService.PROG, action = Action.SEARCH)
    public ApiResponse<NcrDetail> detail(@PathVariable long ncrId) {
        return ApiResponse.ok(ncrs.detail(ncrId));
    }

    @PostMapping(N)
    @ProgAuth(prog = NcrService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody CreateForm form) {
        return ApiResponse.ok(ncrs.create(form));
    }

    @PutMapping(N + "/{ncrId}")
    @ProgAuth(prog = NcrService.PROG, action = Action.SAVE)
    public ApiResponse<String> save(@PathVariable long ncrId, @RequestBody SaveForm form) {
        ncrs.save(ncrId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(N + "/{ncrId}/close")
    @ProgAuth(prog = NcrService.PROG, action = Action.SAVE)
    public ApiResponse<String> close(@PathVariable long ncrId, @RequestBody CloseForm form) {
        ncrs.close(ncrId, form == null ? null : form.rowVer(), form == null ? null : form.esignToken());
        return ApiResponse.ok("OK");
    }

    @PostMapping(N + "/{ncrId}/submit")
    @ProgAuth(prog = NcrService.PROG, action = Action.SAVE)
    public ApiResponse<Long> submit(@PathVariable long ncrId, @RequestBody NcrSubmitForm form) {
        return ApiResponse.ok(ncrs.submit(ncrId, form));
    }

    @PostMapping(N + "/{ncrId}/cancel")
    @ProgAuth(prog = NcrService.PROG, action = Action.DELETE)
    public ApiResponse<String> cancel(@PathVariable long ncrId, @RequestBody CancelForm form) {
        ncrs.cancel(ncrId, form);
        return ApiResponse.ok("OK");
    }
}
