package io.smarthappy.module.qms;

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

import io.smarthappy.module.qms.OcapDtos.OcapForm;
import io.smarthappy.module.qms.OcapDtos.OcapPage;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** OCAP 표준 조치 목록 API (docs/29_P3 26장) — 화면 qms-ocap: 조회 = SEARCH, 등록·수정(사용 중지 포함) = SAVE. 삭제 API 없음 */
@RestController
@RequiredArgsConstructor
public class OcapApiController {

    private static final String O = "/api/qms/ocaps";

    private final OcapService ocaps;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    /** 그리드 — ruleCd(비우면 전체, * = 공통만)·useYn·검색어 + page·size·sort */
    @GetMapping(O + "/grid")
    @ProgAuth(prog = OcapService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String ruleCd, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        OcapPage p = ocaps.grid(ruleCd, useYn, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(O + "/columns")
    @ProgAuth(prog = OcapService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = ocaps.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(O + "/export")
    @ProgAuth(prog = OcapService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, OcapService.PROG, ocaps.columns(), q -> {
            var p = ocaps.grid(q.str("ruleCd"), q.str("useYn"), q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @PostMapping(O)
    @ProgAuth(prog = OcapService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody OcapForm form) {
        return ApiResponse.ok(ocaps.create(form));
    }

    @PutMapping(O + "/{ocapId}")
    @ProgAuth(prog = OcapService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long ocapId, @RequestBody OcapForm form) {
        ocaps.update(ocapId, form);
        return ApiResponse.ok("OK");
    }
}
