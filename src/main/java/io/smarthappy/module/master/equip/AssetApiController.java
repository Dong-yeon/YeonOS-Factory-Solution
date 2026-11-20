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

import io.smarthappy.module.master.equip.AssetDtos.AssetExport;
import io.smarthappy.module.master.equip.AssetDtos.AssetForm;
import io.smarthappy.module.master.equip.AssetDtos.AssetRow;
import io.smarthappy.module.master.equip.AssetDtos.ClassForm;
import io.smarthappy.module.master.equip.AssetDtos.ClassRow;
import io.smarthappy.module.master.equip.AssetDtos.DeleteRequest;
import io.smarthappy.module.master.equip.AssetDtos.EquipExport;
import io.smarthappy.module.master.equip.AssetDtos.EquipForm;
import io.smarthappy.module.master.equip.AssetDtos.EquipRow;
import io.smarthappy.module.master.equip.AssetDtos.InstallRequest;
import io.smarthappy.module.master.equip.AssetDtos.InstallRow;
import io.smarthappy.module.master.equip.AssetDtos.RemoveRequest;
import io.smarthappy.module.master.equip.AssetDtos.VoidRequest;
import io.smarthappy.module.master.equip.EquipDtos.NodeRow;
import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 설비(mst-equip)·물리 자산(mst-asset) API (P1 4b). 설치 = 저장, 무효 = 삭제 권한 */
@RestController
@RequiredArgsConstructor
public class AssetApiController {

    private static final String EQUIP = "/api/master/equips";
    private static final String ASSET = "/api/master/assets";

    private final AssetService service;
    private final EquipService equipService;
    private final MessageSource messages;

    // ---------- 설비 ----------

    @GetMapping(EQUIP)
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.SEARCH)
    public ApiResponse<Void> equips(@RequestParam(required = false) String plantCd, @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String equipCd, @RequestParam(required = false) String equipNm,
            @RequestParam(required = false) String useYn, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        AssetService.Page<EquipRow> p = service.equips(plantCd, classId, equipCd, equipNm, useYn, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(EQUIP + "/columns")
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.SEARCH)
    public ApiResponse<Void> equipColumns(Locale locale) {
        List<Map<String, Object>> cols = service.equipColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(EQUIP + "/{equipId}")
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.SEARCH)
    public ApiResponse<EquipRow> equip(@PathVariable long equipId) {
        return ApiResponse.ok(service.equip(equipId));
    }

    @GetMapping(EQUIP + "/plants")
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(equipService.plants("Y"));
    }

    @GetMapping(EQUIP + "/places")
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.SEARCH)
    public ApiResponse<List<NodeRow>> places(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.placeNodes(plantCd));
    }

    @PostMapping(EQUIP)
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.SAVE)
    public ApiResponse<Long> createEquip(@RequestBody EquipForm form) {
        return ApiResponse.ok(service.createEquip(form));
    }

    @PutMapping(EQUIP + "/{equipId}")
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.SAVE)
    public ApiResponse<String> updateEquip(@PathVariable long equipId, @RequestBody EquipForm form) {
        service.updateEquip(equipId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(EQUIP + "/delete")
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.DELETE)
    public ApiResponse<Integer> disableEquips(@RequestBody DeleteRequest req) {
        return ApiResponse.ok(service.disableEquips(req == null ? null : req.items()));
    }

    @PostMapping(EQUIP + "/export")
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.EXCEL)
    public void exportEquips(@RequestBody(required = false) EquipExport req, HttpServletResponse response) throws IOException {
        service.exportEquips(req, response);
    }

    @GetMapping("/api/master/equip-classes")
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.SEARCH)
    public ApiResponse<List<ClassRow>> classes() {
        return ApiResponse.ok(service.classes());
    }

    @PostMapping("/api/master/equip-classes")
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.SAVE)
    public ApiResponse<Long> createClass(@RequestBody ClassForm form) {
        return ApiResponse.ok(service.saveClass(null, form));
    }

    @PutMapping("/api/master/equip-classes/{classId}")
    @ProgAuth(prog = AssetService.PROG_EQUIP, action = Action.SAVE)
    public ApiResponse<Long> updateClass(@PathVariable long classId, @RequestBody ClassForm form) {
        return ApiResponse.ok(service.saveClass(classId, form));
    }

    // ---------- 물리 자산 ----------

    @GetMapping(ASSET)
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.SEARCH)
    public ApiResponse<Void> assets(@RequestParam(required = false) String assetType,
            @RequestParam(required = false) String assetCd, @RequestParam(required = false) String assetNm,
            @RequestParam(required = false) String useYn, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        AssetService.Page<AssetRow> p = service.assets(assetType, assetCd, assetNm, useYn, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(ASSET + "/columns")
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.SEARCH)
    public ApiResponse<Void> assetColumns(Locale locale) {
        List<Map<String, Object>> cols = service.assetColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(ASSET + "/{assetId}")
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.SEARCH)
    public ApiResponse<AssetRow> asset(@PathVariable long assetId) {
        return ApiResponse.ok(service.asset(assetId));
    }

    @PostMapping(ASSET)
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.SAVE)
    public ApiResponse<Long> createAsset(@RequestBody AssetForm form) {
        return ApiResponse.ok(service.createAsset(form));
    }

    @PutMapping(ASSET + "/{assetId}")
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.SAVE)
    public ApiResponse<String> updateAsset(@PathVariable long assetId, @RequestBody AssetForm form) {
        service.updateAsset(assetId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ASSET + "/delete")
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.DELETE)
    public ApiResponse<Integer> disableAssets(@RequestBody DeleteRequest req) {
        return ApiResponse.ok(service.disableAssets(req == null ? null : req.items()));
    }

    @PostMapping(ASSET + "/export")
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.EXCEL)
    public void exportAssets(@RequestBody(required = false) AssetExport req, HttpServletResponse response) throws IOException {
        service.exportAssets(req, response);
    }

    @GetMapping(ASSET + "/{assetId}/installs")
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.SEARCH)
    public ApiResponse<List<InstallRow>> installs(@PathVariable long assetId) {
        return ApiResponse.ok(service.installs(assetId));
    }

    /** 설치할 설비 선택지 — 설비 관리 권한 없이도 자산 화면에서 고른다 */
    @GetMapping(ASSET + "/targets")
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.SEARCH)
    public ApiResponse<List<EquipRow>> targets() {
        return ApiResponse.ok(service.installTargets());
    }

    @PostMapping(ASSET + "/{assetId}/install")
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.SAVE)
    public ApiResponse<Long> install(@PathVariable long assetId, @RequestBody InstallRequest req) {
        return ApiResponse.ok(service.install(assetId, req));
    }

    @PostMapping(ASSET + "/installs/{installId}/remove")
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.SAVE)
    public ApiResponse<String> remove(@PathVariable long installId, @RequestBody(required = false) RemoveRequest req) {
        service.remove(installId, req == null ? null : req.removedAt());
        return ApiResponse.ok("OK");
    }

    @PostMapping(ASSET + "/installs/{installId}/void")
    @ProgAuth(prog = AssetService.PROG_ASSET, action = Action.DELETE)
    public ApiResponse<String> voidInstall(@PathVariable long installId, @RequestBody VoidRequest req) {
        service.voidInstall(installId, req == null ? null : req.reason());
        return ApiResponse.ok("OK");
    }
}
