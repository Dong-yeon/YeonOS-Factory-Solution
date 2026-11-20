package io.smarthappy.module.production;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.production.CompletionDtos.CloseCheck;
import io.smarthappy.module.production.CompletionDtos.CloseForm;
import io.smarthappy.module.production.CompletionDtos.Completion;
import io.smarthappy.module.production.CompletionDtos.ReworkForm;
import io.smarthappy.module.production.CompletionDtos.ReworkReply;
import io.smarthappy.module.production.WoDtos.ActForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 공정 완료판정·재작업/수리 처리·지시 완료·마감 (작업지시 화면 prd-wo, P2 7a) */
@RestController
@RequiredArgsConstructor
public class CompletionApiController {

    private static final String WO = "/api/production/wos";

    private final CompletionService service;

    @GetMapping(WO + "/{woId}/completion")
    @ProgAuth(prog = WoService.PROG, action = Action.SEARCH)
    public ApiResponse<Completion> completion(@PathVariable long woId) {
        return ApiResponse.ok(service.completion(woId));
    }

    @GetMapping(WO + "/procs/{woProcId}/close-check")
    @ProgAuth(prog = WoService.PROG, action = Action.SEARCH)
    public ApiResponse<CloseCheck> check(@PathVariable long woProcId) {
        return ApiResponse.ok(service.check(woProcId));
    }

    @PostMapping(WO + "/procs/{woProcId}/close")
    @ProgAuth(prog = WoService.PROG, action = Action.SAVE)
    public ApiResponse<CloseCheck> close(@PathVariable long woProcId, @RequestBody CloseForm in) {
        return ApiResponse.ok(service.close(woProcId, in));
    }

    @PostMapping(WO + "/procs/{woProcId}/rework")
    @ProgAuth(prog = WoService.PROG, action = Action.SAVE)
    public ApiResponse<ReworkReply> rework(@PathVariable long woProcId, @RequestBody ReworkForm in) {
        return ApiResponse.ok(service.rework(woProcId, in));
    }

    @PostMapping(WO + "/{woId}/complete")
    @ProgAuth(prog = WoService.PROG, action = Action.SAVE)
    public ApiResponse<String> complete(@PathVariable long woId, @RequestBody ActForm in) {
        service.complete(woId, in);
        return ApiResponse.ok("OK");
    }

    @PostMapping(WO + "/{woId}/close")
    @ProgAuth(prog = WoService.PROG, action = Action.SAVE)
    public ApiResponse<String> close(@PathVariable long woId, @RequestBody ActForm in) {
        service.closeWo(woId, in);
        return ApiResponse.ok("OK");
    }
}
