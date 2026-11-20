package io.smarthappy.module.srm.pr;

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

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.pr.PrDtos.ActionForm;
import io.smarthappy.module.srm.pr.PrDtos.PlantOption;
import io.smarthappy.module.srm.pr.PrDtos.PrDetail;
import io.smarthappy.module.srm.pr.PrDtos.PrForm;
import io.smarthappy.module.srm.pr.PrDtos.PrPage;
import io.smarthappy.module.srm.pr.PrDtos.PrRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.module.srm.supplier.SupplierDtos.ItemOption;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 구매요청 API (SRM S2, docs/31) — 목록·상세·한 벌 저장·상태 전이 */
@RestController
@RequiredArgsConstructor
public class PrApiController {

    private static final String API = "/api/srm/prs";
    private static final String PROG = PrService.PROG;

    private final PrService service;
    private final PrMapper mapper;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(API + "/plants")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<PlantOption>> plants() {
        return ApiResponse.ok(mapper.findPlants());
    }

    /** 행 입력용 품목 찾기(사용 중 품목 50건) — 품목관리 권한 없이 */
    @GetMapping(API + "/items")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<ItemOption>> items(
            @RequestParam(required = false) String keyword) {
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = keyword == null || keyword.isBlank() ? null : "%" + SrmChecks.like(keyword.strip()) + "%";
        return ApiResponse.ok(mapper.searchItems(k));
    }

    @GetMapping(API + "/suppliers")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<PrDtos.SupplierRef>> suppliers() {
        return ApiResponse.ok(mapper.findOpenSuppliers());
    }

    @GetMapping(API)
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<PrRow>> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.list(from, to, status, keyword, plantCd));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(API + "/grid")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        PrPage p = service.grid(from, to, status, keyword, plantCd, page, size, sort == null ? null : List.of(sort), total);
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
            var p = service.grid(q.str("from"), q.str("to"), q.str("status"), q.str("keyword"),
                    q.str("plantCd"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(API + "/{prId}")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<PrDetail> get(@PathVariable long prId) {
        return ApiResponse.ok(service.get(prId));
    }

    @PostMapping(API)
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody PrForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(API + "/{prId}")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long prId, @RequestBody PrForm form) {
        service.update(prId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{prId}/request")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> request(@PathVariable long prId, @RequestBody ActionForm form) {
        service.request(prId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{prId}/withdraw")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> withdraw(@PathVariable long prId, @RequestBody ActionForm form) {
        service.withdraw(prId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{prId}/reopen")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> reopen(@PathVariable long prId, @RequestBody ActionForm form) {
        service.reopen(prId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{prId}/cancel")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long prId, @RequestBody ActionForm form) {
        service.cancel(prId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{prId}/approve")
    @ProgAuth(prog = PROG, action = Action.APPROVE)
    public ApiResponse<String> approve(@PathVariable long prId, @RequestBody ActionForm form) {
        service.approve(prId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{prId}/reject")
    @ProgAuth(prog = PROG, action = Action.APPROVE)
    public ApiResponse<String> reject(@PathVariable long prId, @RequestBody ActionForm form) {
        service.reject(prId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{prId}/close")
    @ProgAuth(prog = PROG, action = Action.APPROVE)
    public ApiResponse<String> close(@PathVariable long prId, @RequestBody ActionForm form) {
        service.close(prId, form);
        return ApiResponse.ok("OK");
    }
}
