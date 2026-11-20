package io.smarthappy.module.master.cust;

import java.io.IOException;
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

import io.smarthappy.module.master.cust.CustDtos.BatchResult;
import io.smarthappy.module.master.cust.CustDtos.ContactBatch;
import io.smarthappy.module.master.cust.CustDtos.CustDetail;
import io.smarthappy.module.master.cust.CustDtos.CustForm;
import io.smarthappy.module.master.cust.CustDtos.DeleteRequest;
import io.smarthappy.module.master.cust.CustDtos.ExportRequest;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 거래처관리 API — 01 5.7 규약의 표준 예 (목록·상세·생성·수정·다건 사용중지·디테일 일괄 저장·엑셀·열 정의) */
@RestController
@RequiredArgsConstructor
public class CustApiController {

    private static final String BASE = "/api/master/customers";

    private final CustService service;
    private final MessageSource messages;

    @GetMapping(BASE)
    @ProgAuth(prog = CustService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String custCd,
            @RequestParam(required = false) String custNm, @RequestParam(required = false) String custType,
            @RequestParam(required = false) String useYn, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, HttpServletRequest request,
            @RequestParam(required = false) Boolean total) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개 버리므로 원래 값 그대로 읽는다
        String[] sort = request.getParameterValues("sort");
        CustService.Page p = service.list(custCd, custNm, custType, useYn, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = CustService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(BASE + "/{custId}")
    @ProgAuth(prog = CustService.PROG, action = Action.SEARCH)
    public ApiResponse<CustDetail> detail(@PathVariable long custId) {
        return ApiResponse.ok(service.detail(custId));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = CustService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody CustForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(BASE + "/{custId}")
    @ProgAuth(prog = CustService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long custId, @RequestBody CustForm form) {
        service.update(custId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/delete")
    @ProgAuth(prog = CustService.PROG, action = Action.DELETE)
    public ApiResponse<Integer> delete(@RequestBody DeleteRequest req) {
        return ApiResponse.ok(service.disable(req == null ? null : req.items()));
    }

    @PostMapping(BASE + "/{custId}/contacts/batch")
    @ProgAuth(prog = CustService.PROG, action = Action.SAVE)
    public ApiResponse<BatchResult> saveContacts(@PathVariable long custId, @RequestParam int rowVer,
            @RequestBody ContactBatch batch) {
        return ApiResponse.ok(service.saveContacts(custId, rowVer, batch));
    }

    @PostMapping(BASE + "/export")
    @ProgAuth(prog = CustService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) ExportRequest req, HttpServletResponse response)
            throws IOException {
        service.export(req, response);
    }
}
