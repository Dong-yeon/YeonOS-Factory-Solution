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

import io.smarthappy.module.qms.ContainDtos.Candidates;
import io.smarthappy.module.qms.ContainDtos.ContainDetail;
import io.smarthappy.module.qms.ContainDtos.ContainPage;
import io.smarthappy.module.qms.ContainDtos.ContainRow;
import io.smarthappy.module.qms.ContainDtos.CreateForm;
import io.smarthappy.module.qms.ContainDtos.ReleaseForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 봉쇄 API (P3 QMS 2부 Q8b, docs/29_P3 12.8) — 후보·목록 = SEARCH, 봉쇄·해제 = SAVE */
@RestController
@RequiredArgsConstructor
public class ContainApiController {

    private static final String C = "/api/qms/contains";

    private final ContainService contains;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(C)
    @ProgAuth(prog = ContainService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ContainRow>> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status) {
        return ApiResponse.ok(contains.list(keyword, status));
    }

    /** 그리드(UI 단계 3) — 목록과 같은 조건 + page·size·sort. sort 는 원래 값 그대로(List 로 받으면 스프링이 쉼표로 쪼갠다, 결정 #47) */
    @GetMapping(C + "/grid")
    @ProgAuth(prog = ContainService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        ContainPage p = contains.grid(keyword, status, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(C + "/columns")
    @ProgAuth(prog = ContainService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = contains.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(C + "/export")
    @ProgAuth(prog = ContainService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ContainService.PROG, contains.gridColumns(), q -> {
            var p = contains.grid(q.str("keyword"), q.str("status"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(C + "/candidates")
    @ProgAuth(prog = ContainService.PROG, action = Action.SEARCH)
    public ApiResponse<Candidates> candidates(@RequestParam(required = false) String itemCd, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ApiResponse.ok(contains.candidates(itemCd, from, to));
    }

    @GetMapping(C + "/{containId}")
    @ProgAuth(prog = ContainService.PROG, action = Action.SEARCH)
    public ApiResponse<ContainDetail> detail(@PathVariable long containId) {
        return ApiResponse.ok(contains.detail(containId));
    }

    @PostMapping(C)
    @ProgAuth(prog = ContainService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody CreateForm form) {
        return ApiResponse.ok(contains.create(form));
    }

    @PostMapping(C + "/{containId}/release")
    @ProgAuth(prog = ContainService.PROG, action = Action.SAVE)
    public ApiResponse<String> release(@PathVariable long containId, @RequestBody ReleaseForm form) {
        contains.release(containId, form);
        return ApiResponse.ok("OK");
    }
}
