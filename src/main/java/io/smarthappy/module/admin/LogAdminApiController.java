package io.smarthappy.module.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.admin.LogDtos.AuditDetail;
import io.smarthappy.module.admin.LogDtos.AuditTable;
import io.smarthappy.module.admin.LogDtos.FeedbackRow;
import io.smarthappy.module.admin.LogDtos.FeedbackStatusForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.grid.ColumnSpec;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import org.springframework.web.bind.annotation.PostMapping;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 접속·보안 로그(sys-access-log)·변경 이력(sys-audit-log)·받은 의견함(sys-feedback) API — docs/36 12장 */
@RestController
@RequiredArgsConstructor
public class LogAdminApiController {

    private final LogAdminService logs;
    private final FeedbackAdminService feedback;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    private ApiResponse<Void> columns(List<ColumnSpec> specs, Locale locale) {
        List<Map<String, Object>> cols = specs.stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    // ---------- 접속·보안 로그 ----------

    @GetMapping("/api/admin/access-logs")
    @ProgAuth(prog = LogAdminService.ACCESS_PROG, action = Action.SEARCH)
    public ApiResponse<Void> access(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String eventCd, @RequestParam(defaultValue = "false") boolean grantOnly,
            @RequestParam(required = false) String severity, @RequestParam(required = false) String resultCd,
            @RequestParam(required = false) String loginId, @RequestParam(required = false) String ip,
            @RequestParam(required = false) String userType, @RequestParam(required = false) String clientType,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, Locale locale) {
        var p = logs.access(from, to, eventCd, grantOnly, severity, resultCd, loginId, ip, userType, clientType, page, size, total,
                locale);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping("/api/admin/access-logs/columns")
    @ProgAuth(prog = LogAdminService.ACCESS_PROG, action = Action.SEARCH)
    public ApiResponse<Void> accessColumns(Locale locale) {
        return columns(logs.accessColumns(), locale);
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping("/api/admin/access-logs/export")
    @ProgAuth(prog = LogAdminService.ACCESS_PROG, action = Action.EXCEL)
    public void accessExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response, Locale locale) throws IOException {
        gridExcel.export(response, body, LogAdminService.ACCESS_PROG, logs.accessColumns(), q -> {
            var p = logs.access(q.date("from"), q.date("to"), q.str("eventCd"), Boolean.TRUE.equals(q.bool("grantOnly")), q.str("severity"), q.str("resultCd"),
                    q.str("loginId"), q.str("ip"), q.str("userType"), q.str("clientType"), q.page(), q.size(), q.total(), locale);
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    // ---------- 변경 이력 ----------

    @GetMapping("/api/admin/audit-logs")
    @ProgAuth(prog = LogAdminService.AUDIT_PROG, action = Action.SEARCH)
    public ApiResponse<Void> audit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String tableNm, @RequestParam(defaultValue = "false") boolean grantOnly,
            @RequestParam(required = false) String action, @RequestParam(required = false) String actor,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, Locale locale) {
        var p = logs.audit(from, to, tableNm, grantOnly, action, actor, page, size, total, locale);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping("/api/admin/audit-logs/columns")
    @ProgAuth(prog = LogAdminService.AUDIT_PROG, action = Action.SEARCH)
    public ApiResponse<Void> auditColumns(Locale locale) {
        return columns(logs.auditColumns(), locale);
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping("/api/admin/audit-logs/export")
    @ProgAuth(prog = LogAdminService.AUDIT_PROG, action = Action.EXCEL)
    public void auditExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response, Locale locale) throws IOException {
        gridExcel.export(response, body, LogAdminService.AUDIT_PROG, logs.auditColumns(), q -> {
            var p = logs.audit(q.date("from"), q.date("to"), q.str("tableNm"), Boolean.TRUE.equals(q.bool("grantOnly")), q.str("action"), q.str("actor"),
                    q.page(), q.size(), q.total(), locale);
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping("/api/admin/audit-logs/tables")
    @ProgAuth(prog = LogAdminService.AUDIT_PROG, action = Action.SEARCH)
    public ApiResponse<Void> auditTables(Locale locale) {
        List<AuditTable> list = logs.auditTables(locale);
        return ApiResponse.list(list, list.size());
    }

    @GetMapping("/api/admin/audit-logs/{auditId}")
    @ProgAuth(prog = LogAdminService.AUDIT_PROG, action = Action.SEARCH)
    public ApiResponse<AuditDetail> auditDetail(@PathVariable long auditId, Locale locale) {
        return ApiResponse.ok(logs.auditDetail(auditId, locale));
    }

    // ---------- 받은 의견 ----------

    @GetMapping("/api/admin/feedback")
    @ProgAuth(prog = FeedbackAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> feedback(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String progCd,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, Locale locale) {
        var p = feedback.list(from, to, status, progCd, page, size, total, locale);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping("/api/admin/feedback/columns")
    @ProgAuth(prog = FeedbackAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> feedbackColumns(Locale locale) {
        return columns(feedback.columns(), locale);
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping("/api/admin/feedback/export")
    @ProgAuth(prog = FeedbackAdminService.PROG, action = Action.EXCEL)
    public void feedbackExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response, Locale locale) throws IOException {
        gridExcel.export(response, body, FeedbackAdminService.PROG, feedback.columns(), q -> {
            var p = feedback.list(q.date("from"), q.date("to"), q.str("status"), q.str("progCd"), q.page(), q.size(), q.total(), locale);
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping("/api/admin/feedback/{feedbackId}")
    @ProgAuth(prog = FeedbackAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<FeedbackRow> feedbackDetail(@PathVariable long feedbackId, Locale locale) {
        return ApiResponse.ok(feedback.detail(feedbackId, locale));
    }

    @PutMapping("/api/admin/feedback/{feedbackId}/status")
    @ProgAuth(prog = FeedbackAdminService.PROG, action = Action.SAVE)
    public ApiResponse<String> feedbackStatus(@PathVariable long feedbackId, @RequestBody FeedbackStatusForm form) {
        feedback.setStatus(feedbackId, form);
        return ApiResponse.ok("OK");
    }
}
