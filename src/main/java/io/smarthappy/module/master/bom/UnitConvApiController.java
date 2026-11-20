package io.smarthappy.module.master.bom;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.master.bom.BomDtos.BatchResult;
import io.smarthappy.module.master.bom.BomDtos.ConvBatch;
import io.smarthappy.module.master.bom.BomDtos.ConvRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 품목별 단위 환산 API — 품목관리 화면(mst-item) 권한 */
@RestController
@RequiredArgsConstructor
public class UnitConvApiController {

    private static final String BASE = "/api/master/items/{itemId}/unit-conv";

    private final UnitConvService service;

    @GetMapping(BASE)
    @ProgAuth(prog = UnitConvService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ConvRow>> list(@PathVariable long itemId) {
        return ApiResponse.ok(service.list(itemId));
    }

    @PostMapping(BASE + "/batch")
    @ProgAuth(prog = UnitConvService.PROG, action = Action.SAVE)
    public ApiResponse<BatchResult> save(@PathVariable long itemId, @RequestParam int rowVer,
            @RequestBody ConvBatch batch) {
        return ApiResponse.ok(service.save(itemId, rowVer, batch));
    }
}
