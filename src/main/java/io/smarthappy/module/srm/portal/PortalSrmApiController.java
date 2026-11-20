package io.smarthappy.module.srm.portal;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.srm.ack.AckDtos.AckForm;
import io.smarthappy.module.srm.ack.AckDtos.AckResult;
import io.smarthappy.module.srm.asn.AsnDtos.ActionForm;
import io.smarthappy.module.srm.portal.PortalSrmDtos.AsnDetail;
import io.smarthappy.module.srm.portal.PortalSrmDtos.AsnForm;
import io.smarthappy.module.srm.portal.PortalSrmDtos.AsnRow;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PlantOption;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PoDetail;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PoLineOption;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PoRow;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.portal.PortalApi;
import io.smarthappy.platform.security.PortalBoundaryFilter;
import lombok.RequiredArgsConstructor;

/**
 * 협력사 포털 SRM API (docs/42 7장 3) — /api/portal/srm/**, 모든 핸들러 @PortalApi(협력사 사용자만, 가드 PortalApiCoverageTest).
 * 협력사 값은 받지 않는다 — 서비스가 로그인 소속으로 거른다.
 */
@RestController
@RequestMapping(PortalBoundaryFilter.API + "/srm")
@RequiredArgsConstructor
public class PortalSrmApiController {

    private final PortalSrmService service;

    @GetMapping("/pos")
    @PortalApi
    public ApiResponse<List<PoRow>> pos(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String state, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.pos(from, to, state, keyword));
    }

    @GetMapping("/pos/{poId}")
    @PortalApi
    public ApiResponse<PoDetail> po(@PathVariable long poId) {
        return ApiResponse.ok(service.po(poId));
    }

    @PostMapping("/pos/{poId}/ack")
    @PortalApi
    public ApiResponse<AckResult> ack(@PathVariable long poId, @RequestBody(required = false) AckForm in) {
        return ApiResponse.ok(service.ack(poId, in));
    }

    @GetMapping("/asns")
    @PortalApi
    public ApiResponse<List<AsnRow>> asns(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.asnList(from, to, status, keyword));
    }

    @GetMapping("/asns/{asnId}")
    @PortalApi
    public ApiResponse<AsnDetail> asn(@PathVariable long asnId) {
        return ApiResponse.ok(service.asn(asnId));
    }

    @GetMapping("/asn-plants")
    @PortalApi
    public ApiResponse<List<PlantOption>> plants() {
        return ApiResponse.ok(service.plants());
    }

    @GetMapping("/asn-po-lines")
    @PortalApi
    public ApiResponse<List<PoLineOption>> poLines(@RequestParam(required = false) String plantCd, @RequestParam(required = false) Long asnId) {
        return ApiResponse.ok(service.poLines(plantCd, asnId));
    }

    @PostMapping("/asns")
    @PortalApi
    public ApiResponse<Long> create(@RequestBody(required = false) AsnForm in) {
        return ApiResponse.ok(service.createAsn(in));
    }

    @PutMapping("/asns/{asnId}")
    @PortalApi
    public ApiResponse<String> update(@PathVariable long asnId, @RequestBody(required = false) AsnForm in) {
        service.updateAsn(asnId, in);
        return ApiResponse.ok("OK");
    }

    @PostMapping("/asns/{asnId}/send")
    @PortalApi
    public ApiResponse<String> send(@PathVariable long asnId, @RequestBody(required = false) ActionForm in) {
        service.sendAsn(asnId, in);
        return ApiResponse.ok("OK");
    }

    @PostMapping("/asns/{asnId}/cancel")
    @PortalApi
    public ApiResponse<String> cancel(@PathVariable long asnId, @RequestBody(required = false) ActionForm in) {
        service.cancelAsn(asnId, in);
        return ApiResponse.ok("OK");
    }

    @GetMapping("/claims")
    @PortalApi
    public ApiResponse<List<PortalSrmDtos.ClaimRow>> claims(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.claims(from, to, status));
    }

    @GetMapping("/claims/{claimId}")
    @PortalApi
    public ApiResponse<PortalSrmDtos.ClaimDetail> claim(@PathVariable long claimId) {
        return ApiResponse.ok(service.claim(claimId));
    }

    @PostMapping("/claims/{claimId}/answer")
    @PortalApi
    public ApiResponse<String> answerClaim(@PathVariable long claimId, @RequestBody(required = false) PortalSrmDtos.ClaimAnswerForm in) {
        service.answerClaim(claimId, in);
        return ApiResponse.ok("OK");
    }
}
