package io.smarthappy.module.wms.loc;

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

import io.smarthappy.module.wms.loc.LocAttrDtos.ClassOpt;
import io.smarthappy.module.wms.loc.LocAttrDtos.AllowView;
import io.smarthappy.module.wms.loc.LocAttrDtos.AllowForm;
import io.smarthappy.module.wms.loc.LocAttrDtos.AttrForm;
import io.smarthappy.module.wms.loc.LocAttrDtos.ExportRequest;
import io.smarthappy.module.wms.loc.LocAttrDtos.LineOpt;
import io.smarthappy.module.wms.loc.LocAttrDtos.LocRow;
import io.smarthappy.module.wms.label.LabelDtos.PrinterRow;
import io.smarthappy.module.wms.label.LabelDtos.TplRow;
import io.smarthappy.module.wms.loc.LocAttrDtos.PlantRow;
import io.smarthappy.module.wms.loc.LocAttrDtos.PrintRequest;
import io.smarthappy.module.wms.loc.LocAttrDtos.PrintResult;
import io.smarthappy.module.wms.loc.LocAttrDtos.RenumberForm;
import io.smarthappy.module.wms.loc.LocAttrDtos.SaveResult;
import io.smarthappy.module.wms.loc.LocAttrDtos.ScanRequest;
import io.smarthappy.module.wms.loc.LocAttrDtos.ScanView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 로케이션 속성·스캔 확인 API (P4 W1). 속성은 지우지 않는다 — 기본값으로 저장하면 된다 */
@RestController
@RequiredArgsConstructor
public class LocAttrApiController {

    private static final String BASE = "/api/wms/loc";

    private final LocAttrService service;
    private final MessageSource messages;

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    /** 보급 라인 선택지(LINESIDE 위치용) */
    @GetMapping(BASE + "/lines")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.SEARCH)
    public ApiResponse<List<LineOpt>> lines(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.lines(plantCd));
    }

    @GetMapping(BASE)
    @ProgAuth(prog = LocAttrService.PROG, action = Action.SEARCH)
    public ApiResponse<List<LocRow>> tree(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) Boolean withUnused) {
        return ApiResponse.ok(service.tree(plantCd, withUnused));
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.treeColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @PutMapping(BASE + "/{nodeId}")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.SAVE)
    public ApiResponse<SaveResult> save(@PathVariable long nodeId, @RequestBody AttrForm form) {
        return ApiResponse.ok(service.save(nodeId, form));
    }

    /** 적치 검사(W-12) 품목군 선택지 */
    @GetMapping(BASE + "/classes")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ClassOpt>> classes() {
        return ApiResponse.ok(service.classOptions());
    }

    /** 노드의 허용 품목군(직접·상속) */
    @GetMapping(BASE + "/{nodeId}/allow")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.SEARCH)
    public ApiResponse<AllowView> allow(@PathVariable long nodeId) {
        return ApiResponse.ok(service.allow(nodeId));
    }

    @PutMapping(BASE + "/{nodeId}/allow")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.SAVE)
    public ApiResponse<AllowView> saveAllow(@PathVariable long nodeId, @RequestBody(required = false) AllowForm form) {
        return ApiResponse.ok(service.saveAllow(nodeId, form));
    }

    @PostMapping(BASE + "/{nodeId}/renumber-pick")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> renumberPick(@PathVariable long nodeId, @RequestBody(required = false) RenumberForm form) {
        return ApiResponse.ok(service.renumberPick(nodeId, form));
    }

    /** 로케이션 라벨 인쇄 (W2) — 프린터·템플릿 선택지는 이 화면 권한으로 */
    @PostMapping(BASE + "/print")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.PRINT)
    public ApiResponse<PrintResult> print(@RequestBody PrintRequest req) {
        return ApiResponse.ok(service.printLabels(req));
    }

    @GetMapping(BASE + "/printers")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.PRINT)
    public ApiResponse<List<PrinterRow>> printers(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.printers(plantCd));
    }

    @GetMapping(BASE + "/templates")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.PRINT)
    public ApiResponse<List<TplRow>> templates() {
        return ApiResponse.ok(service.locTemplates());
    }

    /** 스캔 확인 — 바코드 글자는 URL 이 아니라 본문으로(접속 로그·프록시 로그에 남지 않게) */
    @PostMapping(BASE + "/scan")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.SEARCH)
    public ApiResponse<ScanView> scan(@RequestBody ScanRequest req) {
        return ApiResponse.ok(service.scan(req));
    }

    @PostMapping(BASE + "/export")
    @ProgAuth(prog = LocAttrService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) ExportRequest req, HttpServletResponse response) throws IOException {
        service.export(req, response);
    }
}
