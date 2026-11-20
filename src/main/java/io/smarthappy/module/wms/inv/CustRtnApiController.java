package io.smarthappy.module.wms.inv;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.wms.inv.CustRtnDtos.CancelForm;
import io.smarthappy.module.wms.inv.CustRtnDtos.CreateForm;
import io.smarthappy.module.wms.inv.CustRtnDtos.NodeOpt;
import io.smarthappy.module.wms.inv.CustRtnDtos.Rtn;
import io.smarthappy.module.wms.inv.CustRtnDtos.Source;
import io.smarthappy.module.wms.loc.LocAttrService;
import io.smarthappy.module.wms.loc.LocAttrDtos.PlantRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 고객 반품 입고(wms-cust-rtn) API — docs/59. 받기 = SAVE, 취소 = APPROVE(NCR 이 OPEN 일 때만 — R-6) */
@RestController
@RequiredArgsConstructor
public class CustRtnApiController {

    private static final String BASE = "/api/wms/cust-rtns";

    private final CustRtnService service;
    private final LocAttrService locs;

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = CustRtnService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(locs.plants());
    }

    @GetMapping(BASE + "/nodes")
    @ProgAuth(prog = CustRtnService.PROG, action = Action.SEARCH)
    public ApiResponse<List<NodeOpt>> nodes(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.nodes(plantCd));
    }

    /** 근거 찾기 — 출하 번호·LOT 번호(LOT: 스캔 가능)·고객 코드(+ 출하 기간) 중 하나 */
    @GetMapping(BASE + "/sources")
    @ProgAuth(prog = CustRtnService.PROG, action = Action.SEARCH)
    public ApiResponse<List<Source>> sources(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String shipNo,
            @RequestParam(required = false) String lotNo, @RequestParam(required = false) String custCd,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.sources(plantCd, shipNo, lotNo, custCd, from, to));
    }

    @GetMapping(BASE)
    @ProgAuth(prog = CustRtnService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String status,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        List<Rtn> l = service.list(plantCd, status, from, to);
        return ApiResponse.list(l, l.size());
    }

    @GetMapping(BASE + "/{id}")
    @ProgAuth(prog = CustRtnService.PROG, action = Action.SEARCH)
    public ApiResponse<Rtn> view(@PathVariable long id) {
        return ApiResponse.ok(service.view(id));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = CustRtnService.PROG, action = Action.SAVE)
    public ApiResponse<Rtn> receive(@RequestBody(required = false) CreateForm form) {
        return ApiResponse.ok(service.receive(form));
    }

    @PostMapping(BASE + "/{id}/cancel")
    @ProgAuth(prog = CustRtnService.PROG, action = Action.APPROVE)
    public ApiResponse<Rtn> cancel(@PathVariable long id, @RequestBody(required = false) CancelForm form) {
        return ApiResponse.ok(service.cancel(id, form));
    }
}
