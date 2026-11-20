package io.smarthappy.module.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.admin.OutboxDtos.CloseForm;
import io.smarthappy.module.admin.OutboxDtos.CloseResult;
import io.smarthappy.module.admin.OutboxDtos.OutboxDetail;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 연계 현황·실패 재전송(sys-outbox) API — docs/36 19장. 재시도 = SAVE, 수동 종결 = FN1(접속 로그 ACTION 은 ProgAuth 가 남긴다) */
@RestController
@RequiredArgsConstructor
public class OutboxAdminApiController {

    private final OutboxAdminService outbox;
    private final UserDirectory users;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping("/api/admin/outbox")
    @ProgAuth(prog = OutboxAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String topic, @RequestParam(required = false) String status,
            @RequestParam(required = false) String bizKey, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total) {
        var p = outbox.list(from, to, topic, status, bizKey, page, size, total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping("/api/admin/outbox/columns")
    @ProgAuth(prog = OutboxAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = outbox.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping("/api/admin/outbox/export")
    @ProgAuth(prog = OutboxAdminService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, OutboxAdminService.PROG, outbox.columns(), q -> {
            var p = outbox.list(q.date("from"), q.date("to"), q.str("topic"), q.str("status"), q.str("bizKey"), q.page(), q.size(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    /** 주제 선택지 — 코드와 번역된 이름 */
    @GetMapping("/api/admin/outbox/topics")
    @ProgAuth(prog = OutboxAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> topics(Locale locale) {
        List<Map<String, String>> list = outbox.topics().stream()
                .map(t -> Map.of("code", t, "name", messages.getMessage("outboxTopic." + t, null, t, locale))).toList();
        return ApiResponse.list(list, list.size());
    }

    @GetMapping("/api/admin/outbox/{outboxId}")
    @ProgAuth(prog = OutboxAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<OutboxDetail> detail(@PathVariable long outboxId) {
        return ApiResponse.ok(outbox.detail(outboxId));
    }

    @PostMapping("/api/admin/outbox/{outboxId}/retry")
    @ProgAuth(prog = OutboxAdminService.PROG, action = Action.SAVE)
    public ApiResponse<String> retry(@PathVariable long outboxId) {
        outbox.retry(outboxId);
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/admin/outbox/{outboxId}/close")
    @ProgAuth(prog = OutboxAdminService.PROG, action = Action.FN1)
    public ApiResponse<CloseResult> close(@PathVariable long outboxId, @RequestBody(required = false) CloseForm in) {
        return ApiResponse.ok(outbox.close(outboxId, in));
    }

    /** 종결 결재선 후보(정책 Y) — 공통 사용자 고르기(회사 사용자만) */
    @GetMapping("/api/admin/outbox/approvers")
    @ProgAuth(prog = OutboxAdminService.PROG, action = Action.FN1)
    public ApiResponse<Void> approvers() {
        List<UserOption> list = users.activeUsers();
        return ApiResponse.list(list, list.size());
    }
}
