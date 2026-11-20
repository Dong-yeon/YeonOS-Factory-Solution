package io.smarthappy.module.master.bom;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.master.bom.BomChangeDtos.CompareResult;
import io.smarthappy.module.master.bom.BomChangeDtos.CopyRequest;
import io.smarthappy.module.master.bom.BomChangeDtos.LlcRow;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplaceDone;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplacePreview;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplaceRequest;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** BOM 변경 도구 API (P1 3c): 비교·복사 = BOM 관리 권한, 일괄 대체 = mst-bom-replace, 저레벨 코드 = 역전개 조회 권한 */
@RestController
@RequiredArgsConstructor
public class BomChangeApiController {

    private final BomChangeService service;

    @GetMapping("/api/master/boms/compare")
    @ProgAuth(prog = BomService.PROG, action = Action.SEARCH)
    public ApiResponse<CompareResult> compare(@RequestParam Long leftId, @RequestParam Long rightId) {
        return ApiResponse.ok(service.compare(leftId, rightId));
    }

    @PostMapping("/api/master/boms/{bomId}/copy")
    @ProgAuth(prog = BomService.PROG, action = Action.SAVE)
    public ApiResponse<Long> copy(@PathVariable long bomId, @RequestBody CopyRequest req) {
        return ApiResponse.ok(service.copyTo(bomId, req));
    }

    @GetMapping("/api/master/bom-replace/preview")
    @ProgAuth(prog = BomChangeService.PROG_REPLACE, action = Action.SEARCH)
    public ApiResponse<ReplacePreview> preview(@RequestParam(required = false) String fromItemCd,
            @RequestParam(required = false) String toItemCd, @RequestParam(required = false) String validFrom) {
        return ApiResponse.ok(service.replacePreview(fromItemCd, toItemCd, validFrom));
    }

    @PostMapping("/api/master/bom-replace")
    @ProgAuth(prog = BomChangeService.PROG_REPLACE, action = Action.SAVE)
    public ApiResponse<List<ReplaceDone>> replace(@RequestBody ReplaceRequest req) {
        return ApiResponse.ok(service.replace(req));
    }

    @GetMapping("/api/master/bom-llc")
    @ProgAuth(prog = BomTreeService.PROG_WHERE, action = Action.SEARCH)
    public ApiResponse<List<LlcRow>> llc() {
        return ApiResponse.ok(service.llc());
    }
}
