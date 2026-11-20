package io.smarthappy.module.aps;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.aps.ApsDtos.DiscardForm;
import io.smarthappy.module.aps.MpsDtos.CellsForm;
import io.smarthappy.module.aps.MpsDtos.ItemForm;
import io.smarthappy.module.aps.MpsDtos.ItemRow;
import io.smarthappy.module.aps.MpsDtos.MpsDetail;
import io.smarthappy.module.aps.MpsDtos.MpsForm;
import io.smarthappy.module.aps.MpsDtos.MpsList;
import io.smarthappy.module.aps.MpsDtos.RowVerForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 기준생산계획 MPS API (docs/38 16.6) — 계산·편집은 SAVE, 확정은 APPROVE. 지시·구매 요청은 만들지 않는다 */
@RestController
@RequiredArgsConstructor
public class MpsApiController {

    private static final String MPS = "/api/aps/mps";

    private final MpsService service;

    @GetMapping(MPS + "/plants")
    @ProgAuth(prog = MpsService.PROG, action = Action.SEARCH)
    public ApiResponse<List<io.smarthappy.module.master.equip.EquipDtos.PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    @GetMapping(MPS + "/items")
    @ProgAuth(prog = MpsService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ItemRow>> items(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.items(plantCd));
    }

    @PostMapping(MPS + "/items")
    @ProgAuth(prog = MpsService.PROG, action = Action.SAVE)
    public ApiResponse<ItemRow> createItem(@RequestBody ItemForm form) {
        return ApiResponse.ok(service.saveItem(null, form));
    }

    @PutMapping(MPS + "/items/{mpsItemId}")
    @ProgAuth(prog = MpsService.PROG, action = Action.SAVE)
    public ApiResponse<ItemRow> updateItem(@PathVariable long mpsItemId, @RequestBody ItemForm form) {
        return ApiResponse.ok(service.saveItem(mpsItemId, form));
    }

    @GetMapping(MPS)
    @ProgAuth(prog = MpsService.PROG, action = Action.SEARCH)
    public ApiResponse<MpsList> list(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.list(plantCd, from, to));
    }

    @GetMapping(MPS + "/{mpsId}")
    @ProgAuth(prog = MpsService.PROG, action = Action.SEARCH)
    public ApiResponse<MpsDetail> detail(@PathVariable long mpsId) {
        return ApiResponse.ok(service.detail(mpsId));
    }

    @PostMapping(MPS)
    @ProgAuth(prog = MpsService.PROG, action = Action.SAVE)
    public ApiResponse<Long> calculate(@RequestBody MpsForm form) {
        return ApiResponse.ok(service.calculate(form));
    }

    /** 초안 칸 편집 → 새 rowVer */
    @PutMapping(MPS + "/{mpsId}/cells")
    @ProgAuth(prog = MpsService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> saveCells(@PathVariable long mpsId, @RequestBody CellsForm form) {
        return ApiResponse.ok(service.saveCells(mpsId, form));
    }

    @PostMapping(MPS + "/{mpsId}/firm")
    @ProgAuth(prog = MpsService.PROG, action = Action.APPROVE)
    public ApiResponse<String> firm(@PathVariable long mpsId, @RequestBody RowVerForm form) {
        service.firm(mpsId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(MPS + "/{mpsId}/discard")
    @ProgAuth(prog = MpsService.PROG, action = Action.SAVE)
    public ApiResponse<String> discard(@PathVariable long mpsId, @RequestBody DiscardForm form) {
        service.discard(mpsId, form);
        return ApiResponse.ok("OK");
    }
}
