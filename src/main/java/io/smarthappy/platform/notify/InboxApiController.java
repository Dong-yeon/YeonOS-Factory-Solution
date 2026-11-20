package io.smarthappy.platform.notify;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.notify.NotificationService.InboxItem;
import io.smarthappy.platform.notify.NotificationService.SubForm;
import io.smarthappy.platform.notify.NotificationService.SubView;
import lombok.RequiredArgsConstructor;

/**
 * 내 알림 화면(sys-inbox, 개인 화면 — 모든 사용자, docs/36 11.4) API. 데이터는 서비스가 "나" 로만 거른다.
 * 셸 종 버튼이 count 를 1분마다 부른다.
 */
@RestController
@RequiredArgsConstructor
public class InboxApiController {

    static final String PROG = "sys-inbox";
    private static final String BASE = "/api/platform/inbox";

    private final NotificationService service;

    @GetMapping(BASE)
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(required = false) Long before, @RequestParam(required = false) Integer size, Locale locale) {
        List<InboxItem> list = service.inbox(unread, before, size, locale);
        return ApiResponse.list(list, list.size());
    }

    @GetMapping(BASE + "/count")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Map<String, Long>> count() {
        return ApiResponse.ok(service.unreadCounts());
    }

    @PostMapping(BASE + "/{notifyId}/read")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> read(@PathVariable long notifyId) {
        service.markRead(notifyId);
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/read-all")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Integer> readAll() {
        return ApiResponse.ok(service.markAllRead());
    }

    @GetMapping(BASE + "/subscriptions")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Void> subscriptions(Locale locale) {
        List<SubView> list = service.subscriptions(locale);
        return ApiResponse.list(list, list.size());
    }

    @PutMapping(BASE + "/subscriptions")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> saveSubscriptions(@RequestBody List<SubForm> list) {
        service.saveSubscriptions(list);
        return ApiResponse.ok("OK");
    }
}
