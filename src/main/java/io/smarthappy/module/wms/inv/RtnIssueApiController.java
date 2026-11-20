package io.smarthappy.module.wms.inv;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.wms.inv.InvDtos.RtnIssueForm;
import io.smarthappy.module.wms.inv.ReturnIssueService.IssueResult;
import io.smarthappy.module.wms.inv.ReturnIssueService.ReturnView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import lombok.RequiredArgsConstructor;

/** 협력사 반품 출고(wms-rtn-issue) API — SRM 2부 R4-4 (docs/30 12.14). 정정은 수불 원장 화면의 역분개(SRM 반품도 확정으로) */
@RestController
@RequiredArgsConstructor
public class RtnIssueApiController {

    private static final String BASE = "/api/wms/rtn-issue";

    private final ReturnIssueService service;

    @GetMapping(BASE + "/available")
    @ProgAuth(prog = ReturnIssueService.PROG, action = Action.SEARCH)
    public ApiResponse<Boolean> available() {
        return ApiResponse.ok(service.available());
    }

    /** 반품 번호(또는 스캔 키 RTN:{번호}) → 지시 + 출고 후보 재고 칸 */
    @GetMapping(BASE)
    @ProgAuth(prog = ReturnIssueService.PROG, action = Action.SEARCH)
    public ApiResponse<ReturnView> view(@RequestParam(required = false) String returnNo) {
        return ApiResponse.ok(service.view(returnNo));
    }

    /** 같은 clientReqId 다시 = 처음 결과. 동시에 두 번이면 늦은 쪽을 새 트랜잭션에서 다시 불러 처음 결과로(QR-1) */
    @PostMapping(BASE)
    @ProgAuth(prog = ReturnIssueService.PROG, action = Action.SAVE)
    public ApiResponse<IssueResult> issue(@RequestBody RtnIssueForm form) {
        try {
            return ApiResponse.ok(service.issue(form));
        } catch (BusinessException e) {
            if (!ReturnIssueService.replayable(e, form)) {
                throw e;
            }
            return ApiResponse.ok(service.replayIssue(form, null, null));
        }
    }
}
