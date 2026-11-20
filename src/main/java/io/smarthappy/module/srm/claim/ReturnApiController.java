package io.smarthappy.module.srm.claim;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.srm.claim.ReturnDtos.ActionForm;
import io.smarthappy.module.srm.claim.ReturnDtos.CandidatePage;
import io.smarthappy.module.srm.claim.ReturnDtos.PlantOption;
import io.smarthappy.module.srm.claim.ReturnDtos.PoLineOption;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnForm;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnPage;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnRow;
import io.smarthappy.module.srm.claim.ReturnDtos.SupplierRef;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 반품 지시 API (SRM 2부 R4c, docs/31 4.12.4) — 반품 대상(QMS 계약)·목록·상세·만들기·수정·확정(APPROVE)·취소 */
@RestController
@RequiredArgsConstructor
public class ReturnApiController {

    private static final String API = "/api/srm/returns";
    private static final String PROG = ReturnService.PROG;

    private final ReturnService service;
    private final ReturnMapper mapper;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(API + "/plants")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<PlantOption>> plants() {
        return ApiResponse.ok(mapper.findPlants());
    }

    @GetMapping(API + "/suppliers")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<SupplierRef>> suppliers() {
        return ApiResponse.ok(mapper.findSuppliers());
    }

    /** 반품 대상(닫힌 NCR 의 반품 처리 줄, 종결 시각이 기간 안) — 목록 표시용, truncated 면 기간을 줄인다 */
    @GetMapping(API + "/candidates")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<CandidatePage> candidates(@RequestParam(required = false) String custCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.candidates(custCd, from, to));
    }

    @GetMapping(API + "/po-lines")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<PoLineOption>> poLines(@RequestParam(required = false) String custCd,
            @RequestParam(required = false) Long itemId) {
        return ApiResponse.ok(service.poLines(custCd, itemId));
    }

    @GetMapping(API)
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<ReturnRow>> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.list(from, to, status, keyword));
    }

    @GetMapping(API + "/grid")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        ReturnPage p = service.grid(from, to, status, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(API + "/columns")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(API + "/export")
    @ProgAuth(prog = PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, PROG, service.gridColumns(), q -> {
            var p = service.grid(q.str("from"), q.str("to"), q.str("status"), q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(API + "/{returnId}")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<ReturnRow> get(@PathVariable long returnId) {
        return ApiResponse.ok(service.get(returnId));
    }

    @PostMapping(API)
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody ReturnForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(API + "/{returnId}")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long returnId, @RequestBody ReturnForm form) {
        service.update(returnId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{returnId}/confirm")
    @ProgAuth(prog = PROG, action = Action.APPROVE)
    public ApiResponse<String> confirm(@PathVariable long returnId, @RequestBody ActionForm form) {
        service.confirm(returnId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{returnId}/cancel")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long returnId, @RequestBody ActionForm form) {
        service.cancel(returnId, form);
        return ApiResponse.ok("OK");
    }
}
