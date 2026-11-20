package io.smarthappy.module.master.ext;

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

import io.smarthappy.module.master.ext.ExtDtos.CodeMapForm;
import io.smarthappy.module.master.ext.ExtDtos.CodeMapRow;
import io.smarthappy.module.master.ext.ExtDtos.IdMapForm;
import io.smarthappy.module.master.ext.ExtDtos.IdMapRow;
import io.smarthappy.module.master.ext.ExtDtos.SystemForm;
import io.smarthappy.module.master.ext.ExtDtos.SystemRow;
import io.smarthappy.module.master.item.ItemService;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 외부 시스템·코드 매핑 API(mst-ext-system) + 품목 외부 품번 API(품목관리 권한으로 — 품목 화면의 칸) — P1 7a.
 * 삭제 없음 = 사용 중지(저장)
 */
@RestController
@RequiredArgsConstructor
public class ExtApiController {

    private static final String SYS = "/api/master/ext-systems";
    private static final String ITEM = "/api/master/items";

    private final ExtService service;
    private final ItemService items;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    // ------------------------------------------------------------------ 시스템·코드 매핑

    @GetMapping(SYS)
    @ProgAuth(prog = ExtService.PROG, action = Action.SEARCH)
    public ApiResponse<List<SystemRow>> systems(@RequestParam(required = false) String useYn) {
        return ApiResponse.ok(service.systems(useYn));
    }

    /** 그리드(UI 단계 3 ①) — 기존 목록 API 는 그대로, 같은 조건 + 페이지·정렬·건수 */
    @GetMapping(SYS + "/grid")
    @ProgAuth(prog = ExtService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> systemGrid(@RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        ExtService.SystemPage p = service.systemGrid(useYn, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(SYS + "/columns")
    @ProgAuth(prog = ExtService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> systemColumns(Locale locale) {
        List<Map<String, Object>> cols = service.systemColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(SYS + "/export")
    @ProgAuth(prog = ExtService.PROG, action = Action.EXCEL)
    public void systemExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ExtService.PROG, service.systemColumns(), q -> {
            var p = service.systemGrid(q.str("useYn"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @PostMapping(SYS)
    @ProgAuth(prog = ExtService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createSystem(@RequestBody SystemForm form) {
        return ApiResponse.ok(service.createSystem(form));
    }

    @PutMapping(SYS + "/{systemId}")
    @ProgAuth(prog = ExtService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateSystem(@PathVariable long systemId, @RequestBody SystemForm form) {
        service.updateSystem(systemId, form);
        return ApiResponse.ok("OK");
    }

    @GetMapping(SYS + "/{systemId}/code-maps")
    @ProgAuth(prog = ExtService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CodeMapRow>> codeMaps(@PathVariable long systemId) {
        return ApiResponse.ok(service.codeMaps(systemId));
    }

    @PostMapping(SYS + "/{systemId}/code-maps")
    @ProgAuth(prog = ExtService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createCodeMap(@PathVariable long systemId, @RequestBody CodeMapForm form) {
        return ApiResponse.ok(service.createCodeMap(systemId, form));
    }

    @PutMapping(SYS + "/code-maps/{codeMapId}")
    @ProgAuth(prog = ExtService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateCodeMap(@PathVariable long codeMapId, @RequestBody CodeMapForm form) {
        service.updateCodeMap(codeMapId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 품목 외부 품번 (품목관리 권한)

    /** 품목 화면 설정 — ERP 동기화 방식과 잠글 칸 */
    @GetMapping(ITEM + "/settings")
    @ProgAuth(prog = "mst-item", action = Action.SEARCH)
    public ApiResponse<Map<String, Object>> itemSettings() {
        return ApiResponse.ok(items.settings());
    }

    /** 외부 품번을 붙일 수 있는 시스템 — 외부 시스템 화면 권한 없이도 사용 중인 시스템은 보인다 */
    @GetMapping(ITEM + "/ext-systems")
    @ProgAuth(prog = "mst-item", action = Action.SEARCH)
    public ApiResponse<List<SystemRow>> itemSystems() {
        return ApiResponse.ok(service.systems("Y"));
    }

    @GetMapping(ITEM + "/{itemId}/ext-ids")
    @ProgAuth(prog = "mst-item", action = Action.SEARCH)
    public ApiResponse<List<IdMapRow>> itemExtIds(@PathVariable long itemId) {
        return ApiResponse.ok(service.idMaps("ITEM", itemId));
    }

    @PostMapping(ITEM + "/{itemId}/ext-ids")
    @ProgAuth(prog = "mst-item", action = Action.SAVE)
    public ApiResponse<Long> createItemExtId(@PathVariable long itemId, @RequestBody IdMapForm form) {
        return ApiResponse.ok(service.createIdMap("ITEM", itemId, form));
    }

    @PutMapping(ITEM + "/ext-ids/{mapId}")
    @ProgAuth(prog = "mst-item", action = Action.SAVE)
    public ApiResponse<String> updateItemExtId(@PathVariable long mapId, @RequestBody IdMapForm form) {
        service.updateIdMap("ITEM", mapId, form);
        return ApiResponse.ok("OK");
    }
}
