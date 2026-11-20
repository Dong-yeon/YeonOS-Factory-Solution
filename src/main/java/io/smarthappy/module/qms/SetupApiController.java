package io.smarthappy.module.qms;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.qms.SetupDtos.RecordForm;
import io.smarthappy.module.qms.SetupDtos.SetupDetail;
import io.smarthappy.module.qms.SetupDtos.VerdictRow;
import io.smarthappy.module.qms.SetupDtos.VoidForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 초품(셋업 검증) API (P3 QMS 2부 Q7, docs/29_P3 12.3) — 키는 ECO id. 판정 기록 = SAVE, 무효 = DELETE */
@RestController
@RequiredArgsConstructor
public class SetupApiController {

    private static final String S = "/api/qms/setups";

    private final SetupService setups;
    private final ChangeService changes;

    @GetMapping(S)
    @ProgAuth(prog = SetupService.PROG, action = Action.SEARCH)
    public ApiResponse<List<VerdictRow>> list() {
        return ApiResponse.ok(setups.list());
    }

    @GetMapping(S + "/by-no")
    @ProgAuth(prog = SetupService.PROG, action = Action.SEARCH)
    public ApiResponse<Long> byNo(@RequestParam(required = false) String ecoNo) {
        return ApiResponse.ok(changes.ecoIdByNo(ecoNo));
    }

    @GetMapping(S + "/{ecoId}")
    @ProgAuth(prog = SetupService.PROG, action = Action.SEARCH)
    public ApiResponse<SetupDetail> detail(@PathVariable long ecoId) {
        return ApiResponse.ok(setups.detail(ecoId));
    }

    @PostMapping(S + "/{ecoId}/verdicts")
    @ProgAuth(prog = SetupService.PROG, action = Action.SAVE)
    public ApiResponse<Long> record(@PathVariable long ecoId, @RequestBody RecordForm form) {
        return ApiResponse.ok(setups.record(ecoId, form));
    }

    @PostMapping(S + "/verdicts/{verifId}/void")
    @ProgAuth(prog = SetupService.PROG, action = Action.DELETE)
    public ApiResponse<String> voidVerdict(@PathVariable long verifId, @RequestBody VoidForm form) {
        setups.voidVerdict(verifId, form);
        return ApiResponse.ok("OK");
    }
}
