package io.smarthappy.module.master.bom;

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

import io.smarthappy.module.master.bom.BomDtos.ActionRequest;
import io.smarthappy.module.master.bom.BomDtos.BatchResult;
import io.smarthappy.module.master.bom.BomDtos.BomDetail;
import io.smarthappy.module.master.bom.BomDtos.ExportRequest;
import io.smarthappy.module.master.bom.BomDtos.HeaderForm;
import io.smarthappy.module.master.bom.BomDtos.ItemLookup;
import io.smarthappy.module.master.bom.BomDtos.LineBatch;
import io.smarthappy.module.master.bom.BomDtos.ReleaseBatch;
import io.smarthappy.module.master.bom.BomDtos.ReleaseOutcome;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** BOM 관리 API (P1 3a). 확정 = APPROVE, 폐기 = DELETE 권한 */
@RestController
@RequiredArgsConstructor
public class BomApiController {

    private static final String BASE = "/api/master/boms";

    private final BomService service;
    private final BomReleaseBatchService releaseBatch;
    private final MessageSource messages;

    @GetMapping(BASE)
    @ProgAuth(prog = BomService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String itemNm, @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean withObsolete, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        BomService.Page p = service.list(itemCd, itemNm, status, withObsolete, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = BomService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(BASE + "/item")
    @ProgAuth(prog = BomService.PROG, action = Action.SEARCH)
    public ApiResponse<ItemLookup> item(@RequestParam String itemCd) {
        return ApiResponse.ok(service.item(itemCd));
    }

    @GetMapping(BASE + "/{bomId}")
    @ProgAuth(prog = BomService.PROG, action = Action.SEARCH)
    public ApiResponse<BomDetail> detail(@PathVariable long bomId) {
        return ApiResponse.ok(service.detail(bomId));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = BomService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody HeaderForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(BASE + "/{bomId}")
    @ProgAuth(prog = BomService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long bomId, @RequestBody HeaderForm form) {
        service.update(bomId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/{bomId}/lines/batch")
    @ProgAuth(prog = BomService.PROG, action = Action.SAVE)
    public ApiResponse<BatchResult> saveLines(@PathVariable long bomId, @RequestParam int rowVer,
            @RequestBody LineBatch batch) {
        return ApiResponse.ok(service.saveLines(bomId, rowVer, batch));
    }

    @PostMapping(BASE + "/{bomId}/release")
    @ProgAuth(prog = BomService.PROG, action = Action.APPROVE)
    public ApiResponse<String> release(@PathVariable long bomId, @RequestBody ActionRequest req) {
        service.release(bomId, req);
        return ApiResponse.ok("OK");
    }

    /** 고른 작성 중 BOM 일괄 확정 — 건마다 확정과 같은 검사, 실패는 그 건만(G5). 권한 = 확정(APPROVE) */
    @PostMapping(BASE + "/release-batch")
    @ProgAuth(prog = BomService.PROG, action = Action.APPROVE)
    public ApiResponse<List<ReleaseOutcome>> releaseBatch(@RequestBody ReleaseBatch req) {
        return ApiResponse.ok(releaseBatch.release(req));
    }

    @PostMapping(BASE + "/{bomId}/obsolete")
    @ProgAuth(prog = BomService.PROG, action = Action.DELETE)
    public ApiResponse<String> obsolete(@PathVariable long bomId, @RequestBody ActionRequest req) {
        service.obsolete(bomId, req);
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/{bomId}/new-revision")
    @ProgAuth(prog = BomService.PROG, action = Action.SAVE)
    public ApiResponse<Long> newRevision(@PathVariable long bomId, @RequestBody ActionRequest req) {
        return ApiResponse.ok(service.newRevision(bomId, req));
    }

    @PostMapping(BASE + "/export")
    @ProgAuth(prog = BomService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) ExportRequest req, HttpServletResponse response)
            throws IOException {
        service.export(req, response);
    }
}
