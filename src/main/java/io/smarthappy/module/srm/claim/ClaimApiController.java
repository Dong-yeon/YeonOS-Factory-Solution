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

import io.smarthappy.module.srm.claim.ClaimDtos.ActionForm;
import io.smarthappy.module.srm.claim.ClaimDtos.AnswerForm;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimDetail;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimForm;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimPage;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimRow;
import io.smarthappy.module.srm.claim.ClaimDtos.NcrCandidatePage;
import io.smarthappy.module.srm.claim.ReturnDtos.SupplierRef;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 협력사 클레임 API (SRM 2부 R4d, docs/31 4.12.4) — 근거 NCR 후보(QMS 계약)·목록·상세·저장·보내기·회신·반려·종결(APPROVE)·취소 */
@RestController
@RequiredArgsConstructor
public class ClaimApiController {

    private static final String API = "/api/srm/claims";
    private static final String PROG = ClaimService.PROG;

    private final ClaimService service;
    private final ReturnMapper returns;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(API + "/suppliers")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<SupplierRef>> suppliers() {
        return ApiResponse.ok(returns.findSuppliers());
    }

    @GetMapping(API + "/ncr-candidates")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<NcrCandidatePage> candidates(@RequestParam(required = false) String custCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.candidates(custCd, from, to));
    }

    @GetMapping(API)
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<ClaimRow>> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
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
        ClaimPage p = service.grid(from, to, status, keyword, page, size, sort == null ? null : List.of(sort), total);
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

    @GetMapping(API + "/{claimId}")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<ClaimDetail> get(@PathVariable long claimId) {
        return ApiResponse.ok(service.get(claimId));
    }

    @PostMapping(API)
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody ClaimForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(API + "/{claimId}")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long claimId, @RequestBody ClaimForm form) {
        service.update(claimId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{claimId}/send")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> send(@PathVariable long claimId, @RequestBody ActionForm form) {
        service.send(claimId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{claimId}/answer")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> answer(@PathVariable long claimId, @RequestBody AnswerForm form) {
        service.answer(claimId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{claimId}/reject")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> reject(@PathVariable long claimId, @RequestBody ActionForm form) {
        service.reject(claimId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{claimId}/close")
    @ProgAuth(prog = PROG, action = Action.APPROVE)
    public ApiResponse<String> close(@PathVariable long claimId, @RequestBody ActionForm form) {
        service.close(claimId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{claimId}/cancel")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long claimId, @RequestBody ActionForm form) {
        service.cancel(claimId, form);
        return ApiResponse.ok("OK");
    }
}
