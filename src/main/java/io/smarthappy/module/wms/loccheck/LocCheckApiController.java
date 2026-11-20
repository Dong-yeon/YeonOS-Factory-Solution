package io.smarthappy.module.wms.loccheck;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.wms.loc.LocAttrDtos.PlantRow;
import io.smarthappy.module.wms.loc.LocAttrService;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.ConfirmForm;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.CorrectForm;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.Head;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.View;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 정위치 점검 사무 화면(wms-loc-check, docs/55) — 목록·상세·보정 이동·확인만. 스캔은 PDA(/api/pop/wh/loc-checks) */
@RestController
@RequiredArgsConstructor
public class LocCheckApiController {

    private static final String BASE = "/api/wms/loc-checks";

    private final LocCheckService service;
    private final LocAttrService locs;
    private final PolicyService policy;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = LocCheckService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(locs.plants());
    }

    @GetMapping(BASE + "/grid")
    @ProgAuth(prog = LocCheckService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean openOnly, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로 읽는다(CustApiController 와 같음)
        String[] raw = request.getParameterValues("sort");
        List<String> sort = raw == null ? null : List.of(raw);
        LocCheckDtos.Page<Head> p = service.grid(plantCd, from, to, status, openOnly, page, size, sort, total,
                policy.intValue(Policies.GRID_PAGE_SIZE_MAX));
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = LocCheckService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(BASE + "/export")
    @ProgAuth(prog = LocCheckService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, LocCheckService.PROG, service.columns(), q -> {
            var p = service.grid(q.str("plantCd"), q.str("from"), q.str("to"), q.str("status"), q.bool("openOnly"), q.page(), q.size(), q.sort(), q.total(),
                    policy.intValue(Policies.GRID_PAGE_SIZE_MAX));
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(BASE + "/{id}")
    @ProgAuth(prog = LocCheckService.PROG, action = Action.SEARCH)
    public ApiResponse<View> view(@PathVariable long id) {
        return ApiResponse.ok(service.view(id));
    }

    /** 보정 이동 — 사람이 확인 뒤(docs/55 2.4). 같은 줄 두 번은 409 */
    @PostMapping(BASE + "/lines/{lineId}/correct")
    @ProgAuth(prog = LocCheckService.PROG, action = Action.SAVE)
    public ApiResponse<View> correct(@PathVariable long lineId, @RequestBody CorrectForm form) {
        return ApiResponse.ok(service.correct(lineId, form));
    }

    /** 확인만(사유) */
    @PostMapping(BASE + "/lines/{lineId}/confirm")
    @ProgAuth(prog = LocCheckService.PROG, action = Action.SAVE)
    public ApiResponse<View> confirm(@PathVariable long lineId, @RequestBody ConfirmForm form) {
        return ApiResponse.ok(service.confirm(lineId, form));
    }
}
