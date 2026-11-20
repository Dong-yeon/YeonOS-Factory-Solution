package io.smarthappy.module.qms;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.qms.ChangeRecordDtos.EffectCompare;
import io.smarthappy.module.qms.ChangeRecordDtos.EffectForm;
import io.smarthappy.module.qms.ChangeRecordDtos.RecordsView;
import io.smarthappy.module.qms.ChangeRecordDtos.RevertForm;
import io.smarthappy.module.qms.ChangeRecordDtos.VoidForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 4M 임시 변경 복귀 · 변경 효과 API (docs/29_P3 51장) — 화면 qms-change. 기록 = SAVE, 무효 = DELETE(초품과 같은 짝) */
@RestController
@RequiredArgsConstructor
public class ChangeRecordApiController {

    private static final String C = "/api/qms/changes";

    private final ChangeRecordService records;
    private final CustNotifyService custNotify;

    /** 4M 고객 통지 판단(docs/29_P3 52장) — 대상 고객 = ECO 고객 칸, PCN 은 PLM 계약으로 읽기만 */
    @GetMapping(C + "/{ecoId}/cust-notify")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SEARCH)
    public ApiResponse<CustNotifyService.NotifyView> custNotify(@PathVariable long ecoId) {
        return ApiResponse.ok(custNotify.view(ecoId));
    }

    @GetMapping(C + "/{ecoId}/records")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SEARCH)
    public ApiResponse<RecordsView> records(@PathVariable long ecoId) {
        return ApiResponse.ok(records.records(ecoId));
    }

    /** 변경 전후 비교 표(읽기만) — n = 특성마다 앞·뒤 측정값 수(5~200), days = 품목 집계 기간(1~180) */
    @GetMapping(C + "/{ecoId}/effect")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SEARCH)
    public ApiResponse<EffectCompare> effect(@PathVariable long ecoId, @RequestParam(required = false) Integer n,
            @RequestParam(required = false) Integer days) {
        return ApiResponse.ok(records.compare(ecoId, n, days));
    }

    @PostMapping(C + "/{ecoId}/reverts")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SAVE)
    public ApiResponse<Long> recordRevert(@PathVariable long ecoId, @RequestBody RevertForm form) {
        return ApiResponse.ok(records.recordRevert(ecoId, form));
    }

    @PostMapping(C + "/reverts/{revertId}/void")
    @ProgAuth(prog = ChangeService.PROG, action = Action.DELETE)
    public ApiResponse<String> voidRevert(@PathVariable long revertId, @RequestBody VoidForm form) {
        records.voidRevert(revertId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(C + "/{ecoId}/effects")
    @ProgAuth(prog = ChangeService.PROG, action = Action.SAVE)
    public ApiResponse<Long> recordEffect(@PathVariable long ecoId, @RequestBody EffectForm form) {
        return ApiResponse.ok(records.recordEffect(ecoId, form));
    }

    @PostMapping(C + "/effects/{effectId}/void")
    @ProgAuth(prog = ChangeService.PROG, action = Action.DELETE)
    public ApiResponse<String> voidEffect(@PathVariable long effectId, @RequestBody VoidForm form) {
        records.voidEffect(effectId, form);
        return ApiResponse.ok("OK");
    }
}
