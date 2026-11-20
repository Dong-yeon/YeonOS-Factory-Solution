package io.smarthappy.module.master.routing;

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

import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.master.routing.RoutingDtos.ActionRequest;
import io.smarthappy.module.master.routing.RoutingDtos.ExportRequest;
import io.smarthappy.module.master.routing.RoutingDtos.HeaderForm;
import io.smarthappy.module.master.routing.RoutingDtos.ItemLookup;
import io.smarthappy.module.master.routing.RoutingDtos.PlaceRow;
import io.smarthappy.module.master.routing.RoutingDtos.ProcForm;
import io.smarthappy.module.master.routing.RoutingDtos.ProcRow;
import io.smarthappy.module.master.routing.RoutingDtos.RoutingDetail;
import io.smarthappy.module.master.routing.RoutingDtos.SaveResult;
import io.smarthappy.module.master.routing.RoutingDtos.StructureForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import io.smarthappy.platform.excel.GridExcel;

/** 공정 관리·라우팅 관리 API (P1 5단계). 라우팅 확정 = APPROVE, 폐기 = DELETE */
@RestController
@RequiredArgsConstructor
public class RoutingApiController {

    private static final String PROC = "/api/master/procs";
    private static final String BASE = "/api/master/routings";

    private final ProcService procs;
    private final RoutingService service;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    // ------------------------------------------------------------------ 공정

    @GetMapping(PROC)
    @ProgAuth(prog = ProcService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ProcRow>> procs(@RequestParam(required = false) String useYn) {
        return ApiResponse.ok(procs.list(useYn));
    }

    /** 그리드(UI 단계 3 ①) — 기존 목록 API 는 그대로, 같은 조건 + 빠른 찾기·페이지·정렬·건수 */
    @GetMapping(PROC + "/grid")
    @ProgAuth(prog = ProcService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> procGrid(@RequestParam(required = false) String useYn,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        ProcService.Page p = procs.grid(useYn, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(PROC + "/columns")
    @ProgAuth(prog = ProcService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> procColumns(Locale locale) {
        List<Map<String, Object>> cols = procs.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(PROC + "/export")
    @ProgAuth(prog = ProcService.PROG, action = Action.EXCEL)
    public void procExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ProcService.PROG, procs.columns(), q -> {
            var p = procs.grid(q.str("useYn"), q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @PostMapping(PROC)
    @ProgAuth(prog = ProcService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createProc(@RequestBody ProcForm form) {
        return ApiResponse.ok(procs.create(form));
    }

    @PutMapping(PROC + "/{procId}")
    @ProgAuth(prog = ProcService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateProc(@PathVariable long procId, @RequestBody ProcForm form) {
        procs.update(procId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 라우팅

    @GetMapping(BASE)
    @ProgAuth(prog = RoutingService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String itemNm, @RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String status, @RequestParam(required = false) Boolean withObsolete,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        RoutingService.Page p = service.list(itemCd, itemNm, plantCd, status, withObsolete, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = RoutingService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(BASE + "/item")
    @ProgAuth(prog = RoutingService.PROG, action = Action.SEARCH)
    public ApiResponse<ItemLookup> item(@RequestParam String itemCd) {
        return ApiResponse.ok(service.item(itemCd));
    }

    /** 공정 선택지 — 공정 관리 권한 없이도 사용 중인 공정은 보인다 */
    @GetMapping(BASE + "/procs")
    @ProgAuth(prog = RoutingService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ProcRow>> procChoices() {
        return ApiResponse.ok(service.procs());
    }

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = RoutingService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    @GetMapping(BASE + "/places")
    @ProgAuth(prog = RoutingService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlaceRow>> places(@RequestParam String plantCd) {
        return ApiResponse.ok(service.places(plantCd));
    }

    @GetMapping(BASE + "/{routingId}")
    @ProgAuth(prog = RoutingService.PROG, action = Action.SEARCH)
    public ApiResponse<RoutingDetail> detail(@PathVariable long routingId) {
        return ApiResponse.ok(service.detail(routingId));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = RoutingService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody HeaderForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(BASE + "/{routingId}")
    @ProgAuth(prog = RoutingService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long routingId, @RequestBody HeaderForm form) {
        service.update(routingId, form);
        return ApiResponse.ok("OK");
    }

    @PutMapping(BASE + "/{routingId}/steps")
    @ProgAuth(prog = RoutingService.PROG, action = Action.SAVE)
    public ApiResponse<SaveResult> saveStructure(@PathVariable long routingId, @RequestParam int rowVer,
            @RequestBody StructureForm form) {
        return ApiResponse.ok(service.saveStructure(routingId, rowVer, form));
    }

    @PostMapping(BASE + "/{routingId}/release")
    @ProgAuth(prog = RoutingService.PROG, action = Action.APPROVE)
    public ApiResponse<String> release(@PathVariable long routingId, @RequestBody ActionRequest req) {
        service.release(routingId, req);
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/{routingId}/obsolete")
    @ProgAuth(prog = RoutingService.PROG, action = Action.DELETE)
    public ApiResponse<String> obsolete(@PathVariable long routingId, @RequestBody ActionRequest req) {
        service.obsolete(routingId, req);
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/{routingId}/new-revision")
    @ProgAuth(prog = RoutingService.PROG, action = Action.SAVE)
    public ApiResponse<Long> newRevision(@PathVariable long routingId, @RequestBody ActionRequest req) {
        return ApiResponse.ok(service.newRevision(routingId, req));
    }

    @PostMapping(BASE + "/export")
    @ProgAuth(prog = RoutingService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) ExportRequest req, HttpServletResponse response)
            throws IOException {
        service.export(req, response);
    }
}
