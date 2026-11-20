package io.smarthappy.module.srm.po;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
import io.smarthappy.module.srm.po.PoDtos.ActionForm;
import io.smarthappy.module.srm.po.PoDtos.PoDetail;
import io.smarthappy.module.srm.po.PoDtos.PoForm;
import io.smarthappy.module.srm.po.PoDtos.PoPage;
import io.smarthappy.module.srm.po.PoDtos.PoRow;
import io.smarthappy.module.srm.po.PoDtos.PrLineRef;
import io.smarthappy.module.srm.po.PoDtos.SubmitForm;
import io.smarthappy.module.srm.po.PoDtos.WhOption;
import io.smarthappy.module.srm.pr.PrDtos.PlantOption;
import io.smarthappy.module.srm.pr.PrDtos.SupplierRef;
import io.smarthappy.module.srm.pr.PrMapper;
import io.smarthappy.module.srm.supplier.SupplierDtos.ItemOption;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import lombok.RequiredArgsConstructor;
import io.smarthappy.platform.excel.GridExcel;

/** 발주 API (SRM S3, docs/31) — 목록·상세·한 벌 저장·결재 상신·확정·발행·개정·취소·마감 + 선택지 */
@RestController
@RequiredArgsConstructor
public class PoApiController {

    private static final String API = "/api/srm/pos";
    private static final String PROG = PoService.PROG;

    private final PoService service;
    private final PoMapper mapper;
    private final UserDirectory users;
    private final PrMapper lookups;
    private final PoDocumentService documents;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    // ------------------------------------------------------------------ 선택지

    @GetMapping(API + "/plants")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<PlantOption>> plants() {
        return ApiResponse.ok(lookups.findPlants());
    }

    @GetMapping(API + "/whs")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<WhOption>> whs(@RequestParam String plantCd) {
        return ApiResponse.ok(plantCd.length() > 20 ? List.of() : mapper.findWhs(SrmChecks.code(plantCd)));
    }

    /** 새 발주 협력사 선택지 — 거래 정지 아닌 등록 협력사 */
    @GetMapping(API + "/suppliers")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<SupplierRef>> suppliers() {
        return ApiResponse.ok(lookups.findOpenSuppliers());
    }

    @GetMapping(API + "/items")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<ItemOption>> items(@RequestParam(required = false) String keyword) {
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = keyword == null || keyword.isBlank() ? null : "%" + SrmChecks.like(keyword.strip()) + "%";
        return ApiResponse.ok(lookups.searchItems(k));
    }

    @GetMapping(API + "/approvers")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<UserOption>> approvers() {
        return ApiResponse.ok(users.groupActionHolders(PROG, "APPROVE"));   // 발주 승인 권한(그룹 행)이 있는 사용 중 회사 사용자
    }

    /** "구매요청에서 가져오기" — 승인된 요청의 열린 행과 남은 수량 */
    @GetMapping(API + "/pr-lines")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<PrLineRef>> prLines(@RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.openPrLines(itemCd, keyword));
    }

    // ------------------------------------------------------------------ 발주

    @GetMapping(API)
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<PoRow>> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String custCd) {
        return ApiResponse.ok(service.list(from, to, status, keyword, custCd));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(API + "/grid")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String custCd, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        PoPage p = service.grid(from, to, status, keyword, custCd, page, size, sort == null ? null : List.of(sort), total);
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
                    q.str("custCd"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(API + "/{poId}")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<PoDetail> get(@PathVariable long poId) {
        return ApiResponse.ok(service.get(poId));
    }

    @PostMapping(API)
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody PoForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(API + "/{poId}")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long poId, @RequestBody PoForm form) {
        service.update(poId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{poId}/submit")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Long> submit(@PathVariable long poId, @RequestBody SubmitForm form) {
        return ApiResponse.ok(service.submit(poId, form));
    }

    @PostMapping(API + "/{poId}/confirm")
    @ProgAuth(prog = PROG, action = Action.APPROVE)
    public ApiResponse<String> confirm(@PathVariable long poId, @RequestBody ActionForm form) {
        service.confirm(poId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{poId}/issue")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> issue(@PathVariable long poId, @RequestBody ActionForm form) {
        service.issue(poId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{poId}/revise")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> revise(@PathVariable long poId, @RequestBody ActionForm form) {
        service.revise(poId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{poId}/cancel")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long poId, @RequestBody ActionForm form) {
        service.cancel(poId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{poId}/close")
    @ProgAuth(prog = PROG, action = Action.APPROVE)
    public ApiResponse<String> close(@PathVariable long poId, @RequestBody ActionForm form) {
        service.close(poId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 발주서 (S4)

    /** 발주서 자료(인쇄 화면) — 발행본 스냅샷, 발행 전이면 지금 내용 + 초안 표시 */
    @GetMapping(API + "/{poId}/document")
    @ProgAuth(prog = PROG, action = Action.PRINT)
    public ApiResponse<PoDocumentService.PoDocument> document(@PathVariable long poId, @RequestParam(required = false) Integer rev) {
        return ApiResponse.ok(documents.document(poId, rev));
    }

    /** 발주서 엑셀(서식 고정) */
    @GetMapping(API + "/{poId}/excel")
    @ProgAuth(prog = PROG, action = Action.PRINT)
    public void excel(@PathVariable long poId, @RequestParam(required = false) Integer rev, HttpServletResponse response)
            throws IOException {
        PoDocumentService.PoDocument d = documents.document(poId, rev);
        String name = String.valueOf(((java.util.Map<?, ?>) d.snapshot().get("po")).get("poNo")) + "_rev" + d.rev()
                + (d.draft() ? "_draft" : "") + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8)
                .build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        documents.writeExcel(d, response.getOutputStream());
    }
}
