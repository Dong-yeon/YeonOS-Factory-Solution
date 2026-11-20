package io.smarthappy.module.admin;

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

import io.smarthappy.module.admin.NoticeDtos.NoticeForm;
import io.smarthappy.module.admin.NoticeDtos.NoticeItem;
import io.smarthappy.module.admin.NoticeDtos.NoticeRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 공지 관리 API (sys-notice) + 읽는 사람 API (내 알림 화면 sys-inbox — 모든 사용자, docs/36 11.4) */
@RestController
@RequiredArgsConstructor
public class NoticeApiController {

    private static final String BASE = "/api/admin/notices";
    private static final String READER = "/api/platform/notices";
    private static final String INBOX = "sys-inbox";

    private final NoticeService service;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(BASE)
    @ProgAuth(prog = NoticeService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String catCd, @RequestParam(required = false) String stateCd,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        NoticeService.Page p = service.list(keyword, catCd, stateCd, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = NoticeService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(BASE + "/export")
    @ProgAuth(prog = NoticeService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, NoticeService.PROG, service.columns(), q -> {
            var p = service.list(q.str("keyword"), q.str("catCd"), q.str("stateCd"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(BASE + "/{noticeId}")
    @ProgAuth(prog = NoticeService.PROG, action = Action.SEARCH)
    public ApiResponse<NoticeRow> detail(@PathVariable long noticeId) {
        return ApiResponse.ok(service.detail(noticeId));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = NoticeService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody NoticeForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(BASE + "/{noticeId}")
    @ProgAuth(prog = NoticeService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long noticeId, @RequestBody NoticeForm form) {
        service.update(noticeId, form);
        return ApiResponse.ok("OK");
    }

    // ---------- 읽는 사람 (게시 중만) ----------

    @GetMapping(READER)
    @ProgAuth(prog = INBOX, action = Action.SEARCH)
    public ApiResponse<Void> posted(@RequestParam(required = false) String catCd) {
        List<NoticeItem> list = service.posted(catCd);
        return ApiResponse.list(list, list.size());
    }

    /** 열면 읽음으로 남는다(조회지만 SEARCH — 자기 읽음 기록만 쓴다) */
    @GetMapping(READER + "/{noticeId}")
    @ProgAuth(prog = INBOX, action = Action.SEARCH)
    public ApiResponse<NoticeRow> read(@PathVariable long noticeId) {
        return ApiResponse.ok(service.read(noticeId));
    }
}
