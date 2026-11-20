package io.smarthappy.module.master.bom;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.master.bom.BomTreeDtos.ExplodeExport;
import io.smarthappy.module.master.bom.BomTreeDtos.ExplodeResult;
import io.smarthappy.module.master.bom.BomTreeDtos.WhereExport;
import io.smarthappy.module.master.bom.BomTreeDtos.WhereResult;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** BOM 전개(mst-bom-tree)·역전개(mst-bom-where) 조회 API (P1 3b) */
@RestController
@RequiredArgsConstructor
public class BomTreeApiController {

    private static final String TREE = "/api/master/bom-tree";
    private static final String WHERE = "/api/master/bom-where";

    private final BomTreeService service;
    private final MessageSource messages;

    @GetMapping(TREE)
    @ProgAuth(prog = BomTreeService.PROG_TREE, action = Action.SEARCH)
    public ApiResponse<ExplodeResult> explode(@RequestParam(required = false) String itemCd,
            @RequestParam(required = false) Long bomId, @RequestParam(required = false) String date,
            @RequestParam(required = false) String qty, @RequestParam(required = false) String view) {
        return ApiResponse.ok(service.explode(itemCd, bomId, date, qty, view));
    }

    @GetMapping(TREE + "/columns")
    @ProgAuth(prog = BomTreeService.PROG_TREE, action = Action.SEARCH)
    public ApiResponse<Void> explodeColumns(Locale locale) {
        List<Map<String, Object>> cols = service.explodeColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @PostMapping(TREE + "/export")
    @ProgAuth(prog = BomTreeService.PROG_TREE, action = Action.EXCEL)
    public void exportExplode(@RequestBody(required = false) ExplodeExport req, HttpServletResponse response)
            throws IOException {
        service.exportExplode(req, response);
    }

    @GetMapping(WHERE)
    @ProgAuth(prog = BomTreeService.PROG_WHERE, action = Action.SEARCH)
    public ApiResponse<WhereResult> whereUsed(@RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String date, @RequestParam(required = false) Boolean allRevs,
            @RequestParam(required = false) Boolean topOnly) {
        return ApiResponse.ok(service.whereUsed(itemCd, date, allRevs, topOnly));
    }

    @GetMapping(WHERE + "/columns")
    @ProgAuth(prog = BomTreeService.PROG_WHERE, action = Action.SEARCH)
    public ApiResponse<Void> whereColumns(Locale locale) {
        List<Map<String, Object>> cols = service.whereColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @PostMapping(WHERE + "/export")
    @ProgAuth(prog = BomTreeService.PROG_WHERE, action = Action.EXCEL)
    public void exportWhere(@RequestBody(required = false) WhereExport req, HttpServletResponse response)
            throws IOException {
        service.exportWhere(req, response);
    }
}
