package io.smarthappy.platform.approval;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.approval.ApprovalService.Detail3;
import io.smarthappy.platform.approval.ApprovalService.TodoView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/**
 * 결재 처리 API. 상신은 업무 모듈 API 가 자기 화면 권한(@ProgAuth)으로 받아 ApprovalService.submit 을 부른다.
 * 처리·조회 권한은 화면이 아니라 결재선(현재 차례·요청자)이라 서비스가 판정한다.
 * 처리 요청은 X-Idempotency-Key 를 받는다(연타·재전송 → 409 DUPLICATE_REQUEST).
 */
@RestController
@RequiredArgsConstructor
public class ApprovalApiController {

    private static final String EXEMPT = "결재 권한은 결재선(현재 차례·요청자·결재선 구성원)으로 ApprovalService 가 판정";

    private final ApprovalService service;
    private final ApprovalDelegateService delegates;
    private final org.springframework.context.MessageSource messages;

    /** esignToken = 문서 유형이 결재 처리에 서명을 요구할 때(hb.esign 토큰, ApprovalDocType.beforeAct 로 넘어감) */
    public record ActRequest(String comment, String esignToken) {
    }

    @GetMapping("/api/platform/approvals/todo")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Void> todo() {
        List<TodoView> list = service.todo();
        return ApiResponse.list(list, list.size());
    }

    /** 내 결재함(sys-approval, 개인 화면) — 나와 관계있는 결재만 */
    @GetMapping("/api/platform/approvals/box")
    @ProgAuth(prog = "sys-approval", action = Action.SEARCH)
    public ApiResponse<Void> box(@RequestParam(defaultValue = "TODO") String box) {
        ApprovalService.Box b;
        try {
            b = ApprovalService.Box.valueOf(box);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<ApprovalService.BoxView> list = service.box(b);
        return ApiResponse.list(list, list.size());
    }

    // ---------- 결재 위임 (docs/36 14.6) — 내 결재함(개인 화면), 위임자 본인만 ----------

    private static final String DELEG = "/api/platform/approvals/delegations";

    public record RevokeRequest(Integer rowVer) {
    }

    @GetMapping(DELEG)
    @ProgAuth(prog = "sys-approval", action = Action.SEARCH)
    public ApiResponse<Void> delegations() {
        List<ApprovalDelegateService.DelegateView> list = delegates.mine();
        return ApiResponse.list(list, list.size());
    }

    @GetMapping(DELEG + "/candidates")
    @ProgAuth(prog = "sys-approval", action = Action.SEARCH)
    public ApiResponse<Void> candidates(@RequestParam(required = false) String q) {
        List<ApprovalDelegateService.Candidate> list = delegates.candidates(q);
        return ApiResponse.list(list, list.size());
    }

    /** 위임할 수 있는 문서 유형 — 이름은 apr.docType.{코드} 문구(없으면 코드) */
    @GetMapping(DELEG + "/doc-types")
    @ProgAuth(prog = "sys-approval", action = Action.SEARCH)
    public ApiResponse<Void> docTypes(java.util.Locale locale) {
        List<Map<String, String>> list = delegates.docTypes().stream().sorted()
                .map(t -> Map.of("docType", t, "name", messages.getMessage("apr.docType." + t, null, t, locale))).toList();
        return ApiResponse.list(list, list.size());
    }

    @PostMapping(DELEG)
    @ProgAuth(prog = "sys-approval", action = Action.SAVE)
    public ApiResponse<Long> createDelegation(@RequestBody ApprovalDelegateService.DelegateForm form) {
        return ApiResponse.ok(delegates.create(form));
    }

    @PostMapping(DELEG + "/{delegId}/revoke")
    @ProgAuth(prog = "sys-approval", action = Action.SAVE)
    public ApiResponse<String> revokeDelegation(@PathVariable long delegId, @RequestBody RevokeRequest req) {
        delegates.revoke(delegId, req == null ? null : req.rowVer());
        return ApiResponse.ok("OK");
    }

    @GetMapping("/api/platform/approvals/{aprId}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Detail3> detail(@PathVariable long aprId) {
        return ApiResponse.ok(service.detail(aprId));
    }

    @PostMapping("/api/platform/approvals/{aprId}/approve")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> approve(@PathVariable long aprId, @RequestBody(required = false) ActRequest req) {
        service.approve(aprId, req == null ? null : req.comment(), req == null ? null : req.esignToken());
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/platform/approvals/{aprId}/reject")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> reject(@PathVariable long aprId, @RequestBody(required = false) ActRequest req) {
        service.reject(aprId, req == null ? null : req.comment(), req == null ? null : req.esignToken());
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/platform/approvals/{aprId}/withdraw")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> withdraw(@PathVariable long aprId, @RequestBody(required = false) ActRequest req) {
        service.withdraw(aprId, req == null ? null : req.comment());
        return ApiResponse.ok("OK");
    }
}
