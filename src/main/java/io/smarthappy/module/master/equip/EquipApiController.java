package io.smarthappy.module.master.equip;

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

import io.smarthappy.module.master.equip.EquipDtos.ExportRequest;
import io.smarthappy.module.master.equip.EquipDtos.NodeForm;
import io.smarthappy.module.master.equip.EquipDtos.NodeRow;
import io.smarthappy.module.master.equip.EquipDtos.PlantForm;
import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import io.smarthappy.platform.excel.GridExcel;

/** 공장 관리·장비 계층 API (P1 4a). 사용 중지는 저장(useYn = N) — 삭제 없음 */
@RestController
@RequiredArgsConstructor
public class EquipApiController {

    private static final String PLANT = "/api/master/plants";
    private static final String HIER = "/api/master/equip-hier";

    private final EquipService service;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(PLANT)
    @ProgAuth(prog = EquipService.PROG_PLANT, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants(@RequestParam(required = false) String useYn) {
        return ApiResponse.ok(service.plants(useYn));
    }

    /** 그리드(UI 단계 3 ①) — 기존 목록 API 는 그대로, 같은 조건 + 페이지·정렬·건수 */
    @GetMapping(PLANT + "/grid")
    @ProgAuth(prog = EquipService.PROG_PLANT, action = Action.SEARCH)
    public ApiResponse<Void> plantGrid(@RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        EquipService.PlantPage p = service.plantGrid(useYn, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(PLANT + "/columns")
    @ProgAuth(prog = EquipService.PROG_PLANT, action = Action.SEARCH)
    public ApiResponse<Void> plantColumns(Locale locale) {
        List<Map<String, Object>> cols = service.plantColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(PLANT + "/export")
    @ProgAuth(prog = EquipService.PROG_PLANT, action = Action.EXCEL)
    public void plantExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, EquipService.PROG_PLANT, service.plantColumns(), q -> {
            var p = service.plantGrid(q.str("useYn"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @PostMapping(PLANT)
    @ProgAuth(prog = EquipService.PROG_PLANT, action = Action.SAVE)
    public ApiResponse<String> createPlant(@RequestBody PlantForm form) {
        return ApiResponse.ok(service.createPlant(form));
    }

    @PutMapping(PLANT + "/{plantCd}")
    @ProgAuth(prog = EquipService.PROG_PLANT, action = Action.SAVE)
    public ApiResponse<String> updatePlant(@PathVariable String plantCd, @RequestBody PlantForm form) {
        service.updatePlant(plantCd, form);
        return ApiResponse.ok("OK");
    }

    /** 장비 계층 화면의 공장 선택 — 공장 관리 권한 없이도 사용 중인 공장은 보인다 */
    @GetMapping(HIER + "/plants")
    @ProgAuth(prog = EquipService.PROG_HIER, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> hierPlants() {
        return ApiResponse.ok(service.plants("Y"));
    }

    @GetMapping(HIER)
    @ProgAuth(prog = EquipService.PROG_HIER, action = Action.SEARCH)
    public ApiResponse<List<NodeRow>> tree(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) Boolean withUnused) {
        return ApiResponse.ok(service.tree(plantCd, withUnused));
    }

    @GetMapping(HIER + "/columns")
    @ProgAuth(prog = EquipService.PROG_HIER, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.treeColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @PostMapping(HIER)
    @ProgAuth(prog = EquipService.PROG_HIER, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody NodeForm form) {
        return ApiResponse.ok(service.createNode(form));
    }

    @PutMapping(HIER + "/{nodeId}")
    @ProgAuth(prog = EquipService.PROG_HIER, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long nodeId, @RequestBody NodeForm form) {
        service.updateNode(nodeId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(HIER + "/export")
    @ProgAuth(prog = EquipService.PROG_HIER, action = Action.EXCEL)
    public void export(@RequestBody(required = false) ExportRequest req, HttpServletResponse response) throws IOException {
        service.export(req, response);
    }
}
