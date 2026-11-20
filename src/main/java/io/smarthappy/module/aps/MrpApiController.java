package io.smarthappy.module.aps;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.aps.ApsDtos.DiscardForm;
import io.smarthappy.module.aps.MrpDtos.ItemList;
import io.smarthappy.module.aps.MrpDtos.MrpDetail;
import io.smarthappy.module.aps.MrpDtos.MrpForm;
import io.smarthappy.module.aps.MrpDtos.MrpList;
import io.smarthappy.module.aps.MrpDtos.OrderList;
import io.smarthappy.module.aps.MrpDtos.PegChain;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** MRP 자재소요계획 API (docs/38 19.8) — 계산·폐기는 SAVE, 나머지 SEARCH. 지시·구매 요청은 만들지 않는다(AP-4) */
@RestController
@RequiredArgsConstructor
public class MrpApiController {

    private static final String MRP = "/api/aps/mrp";

    private final MrpService service;

    @GetMapping(MRP + "/plants")
    @ProgAuth(prog = MrpService.PROG, action = Action.SEARCH)
    public ApiResponse<java.util.List<io.smarthappy.module.master.equip.EquipDtos.PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    @GetMapping(MRP)
    @ProgAuth(prog = MrpService.PROG, action = Action.SEARCH)
    public ApiResponse<MrpList> list(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.list(plantCd, from, to));
    }

    @PostMapping(MRP)
    @ProgAuth(prog = MrpService.PROG, action = Action.SAVE)
    public ApiResponse<Long> calculate(@RequestBody MrpForm form) {
        return ApiResponse.ok(service.calculate(form));
    }

    @GetMapping(MRP + "/{mrpId}")
    @ProgAuth(prog = MrpService.PROG, action = Action.SEARCH)
    public ApiResponse<MrpDetail> detail(@PathVariable long mrpId) {
        return ApiResponse.ok(service.detail(mrpId));
    }

    @GetMapping(MRP + "/{mrpId}/orders")
    @ProgAuth(prog = MrpService.PROG, action = Action.SEARCH)
    public ApiResponse<OrderList> orders(@PathVariable long mrpId, @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) String kind) {
        return ApiResponse.ok(service.orders(mrpId, itemId, kind));
    }

    @GetMapping(MRP + "/{mrpId}/items/{itemId}")
    @ProgAuth(prog = MrpService.PROG, action = Action.SEARCH)
    public ApiResponse<ItemList> item(@PathVariable long mrpId, @PathVariable long itemId) {
        return ApiResponse.ok(service.itemList(mrpId, itemId));
    }

    @GetMapping(MRP + "/{mrpId}/elems/{seq}/peg")
    @ProgAuth(prog = MrpService.PROG, action = Action.SEARCH)
    public ApiResponse<PegChain> peg(@PathVariable long mrpId, @PathVariable int seq) {
        return ApiResponse.ok(service.pegUp(mrpId, seq));
    }

    @PostMapping(MRP + "/{mrpId}/discard")
    @ProgAuth(prog = MrpService.PROG, action = Action.SAVE)
    public ApiResponse<String> discard(@PathVariable long mrpId, @RequestBody DiscardForm form) {
        service.discard(mrpId, form);
        return ApiResponse.ok("OK");
    }
}
