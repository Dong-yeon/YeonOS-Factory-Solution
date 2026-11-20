package io.smarthappy.module.qms;

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

import io.smarthappy.module.qms.DocDtos.ActionForm;
import io.smarthappy.module.qms.DocDtos.ConsistencyExportRequest;
import io.smarthappy.module.qms.DocDtos.DocDetail;
import io.smarthappy.module.qms.DocDtos.DocForm;
import io.smarthappy.module.qms.DocDtos.NewRevForm;
import io.smarthappy.module.qms.DocDtos.RevForm;
import io.smarthappy.module.qms.DocDtos.SubmitForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import io.smarthappy.platform.excel.GridExcel;

/**
 * 관리문서·정합 점검 API (P3 QMS Q3, docs/29) — 문서·리비전 저장 = SAVE, 결재 없는 배포 = APPROVE, 폐기 = DELETE.
 * 결재 승인·반려·회수는 공통 결재 API(/api/platform/approvals)
 */
@RestController
@RequiredArgsConstructor
public class DocApiController {

    private static final String D = "/api/qms/docs";
    private static final String C = "/api/qms/consistency";

    private final DocService docs;
    private final ConsistencyService consistency;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(D)
    @ProgAuth(prog = DocService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String docType,
            @RequestParam(required = false) Boolean withUnused, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        DocService.Page p = docs.list(keyword, docType, withUnused, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(D + "/columns")
    @ProgAuth(prog = DocService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = docs.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(D + "/export")
    @ProgAuth(prog = DocService.PROG, action = Action.EXCEL)
    public void gridExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, DocService.PROG, docs.columns(), q -> {
            var p = docs.list(q.str("keyword"), q.str("docType"), q.bool("withUnused"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(D + "/users")
    @ProgAuth(prog = DocService.PROG, action = Action.SEARCH)
    public ApiResponse<List<UserOption>> users() {
        return ApiResponse.ok(docs.users());
    }

    @GetMapping(D + "/{docId}")
    @ProgAuth(prog = DocService.PROG, action = Action.SEARCH)
    public ApiResponse<DocDetail> detail(@PathVariable long docId) {
        return ApiResponse.ok(docs.detail(docId));
    }

    @PostMapping(D)
    @ProgAuth(prog = DocService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody DocForm form) {
        return ApiResponse.ok(docs.create(form));
    }

    @PutMapping(D + "/{docId}")
    @ProgAuth(prog = DocService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long docId, @RequestBody DocForm form) {
        docs.update(docId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(D + "/{docId}/revisions")
    @ProgAuth(prog = DocService.PROG, action = Action.SAVE)
    public ApiResponse<Long> newRevision(@PathVariable long docId, @RequestBody(required = false) NewRevForm form) {
        return ApiResponse.ok(docs.newRevision(docId, form));
    }

    @PutMapping(D + "/revisions/{revId}")
    @ProgAuth(prog = DocService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateRevision(@PathVariable long revId, @RequestBody RevForm form) {
        docs.updateRevision(revId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(D + "/revisions/{revId}/submit")
    @ProgAuth(prog = DocService.PROG, action = Action.SAVE)
    public ApiResponse<Long> submit(@PathVariable long revId, @RequestBody SubmitForm form) {
        return ApiResponse.ok(docs.submit(revId, form));
    }

    @PostMapping(D + "/revisions/{revId}/release")
    @ProgAuth(prog = DocService.PROG, action = Action.APPROVE)
    public ApiResponse<String> release(@PathVariable long revId, @RequestBody ActionForm form) {
        docs.release(revId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(D + "/revisions/{revId}/obsolete")
    @ProgAuth(prog = DocService.PROG, action = Action.DELETE)
    public ApiResponse<String> obsolete(@PathVariable long revId, @RequestBody ActionForm form) {
        docs.obsolete(revId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 정합 점검

    @GetMapping(C)
    @ProgAuth(prog = ConsistencyService.PROG, action = Action.SEARCH)
    public ApiResponse<ConsistencyService.Result> check(@RequestParam(required = false) String checkCd) {
        return ApiResponse.ok(consistency.run(checkCd));
    }

    @PostMapping(C + "/export")
    @ProgAuth(prog = ConsistencyService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) ConsistencyExportRequest req, HttpServletResponse response) throws IOException {
        consistency.export(req == null ? null : req.checkCd(), response);
    }
}
