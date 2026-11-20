package io.smarthappy.platform.security;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * 두 번째 인증(MFA) 화면·API (docs/36 14장). 대기 중인 로그인은 TenantRequestFilter 가 여기만 연다.
 * 대상은 언제나 로그인 사용자 자신 — 권한그룹이 없는 사람도 등록·확인할 수 있어야 하므로 화면 권한 대신 본인 한정.
 */
@Controller
@RequiredArgsConstructor
public class MfaController {

    static final String PAGE = "/mfa";
    static final String API = "/api/platform/mfa";
    private static final String EXEMPT = "본인 두 번째 인증(MFA) — 대상은 로그인 사용자 자신뿐, 권한그룹이 없는 대기 로그인도 열려야 한다";

    private final MfaService service;

    public record CodeRequest(String code) {
    }

    /** 대기 없이 스스로 등록(내 정보에서 연 화면) — 끝나면 돌아갈 곳으로 */
    static final String SELF = "SELF";

    /**
     * 로그인 대기(VERIFY·ENROLL) 화면. 대기가 없으면 셸로 — 단 {@code ?self} 로 열었고 MFA 를 쓸 수 있으며 아직 등록하지 않았으면
     * 스스로 등록(SELF, docs/36 16.7). 이미 등록했으면 돌아갈 곳으로(바꾸려면 관리자 초기화)
     */
    @GetMapping(PAGE)
    public String page(@AuthenticationPrincipal LoginUser user, HttpSession session, Model model,
            @RequestParam(name = "self", required = false) String self) {
        Object state = session.getAttribute(MfaService.SESSION_STATE);
        String back = user != null && user.supplier() ? "/portal/me" : "/page/sys-my-info";
        if (user != null && state == null && self != null) {
            MfaService.Status st = service.status(user);
            if (!st.available() || st.enrolled()) {
                return "redirect:" + back;
            }
            state = SELF;
        }
        if (user == null || state == null) {
            return "redirect:/main";
        }
        model.addAttribute("back", back);
        model.addAttribute("user", user);
        model.addAttribute("state", state);
        model.addAttribute("available", service.available());
        return "platform/mfa";
    }

    /** 로그인 대기(VERIFY) — 맞으면 대기를 풀고 세션 ID 를 바꾼다 */
    @PostMapping(API + "/verify")
    @ResponseBody
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> verify(@RequestBody CodeRequest req, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !MfaService.VERIFY.equals(session.getAttribute(MfaService.SESSION_STATE))) {
            throw new BusinessException(HbErrorCode.MFA_STATE);
        }
        service.verify(me(), req == null ? null : req.code());
        pass(request);
        return ApiResponse.ok("OK");
    }

    /** 등록 시작 — 대기 중 등록(ENROLL)이거나, 대기 없이 내 정보에서 스스로 등록할 때 */
    @PostMapping(API + "/enroll/start")
    @ResponseBody
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<MfaService.Enrollment> start(HttpServletRequest request) {
        notVerifying(request);
        return ApiResponse.ok(service.startEnroll(me()));
    }

    /** 첫 코드로 확인 → 복구 코드(이 응답에서만). 대기 중 등록이었으면 대기를 푼다 */
    @PostMapping(API + "/enroll/confirm")
    @ResponseBody
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Map<String, List<String>>> confirm(@RequestBody CodeRequest req, HttpServletRequest request) {
        notVerifying(request);
        List<String> codes = service.confirmEnroll(me(), req == null ? null : req.code());
        pass(request);
        return ApiResponse.ok(Map.of("recoveryCodes", codes));
    }

    @GetMapping(API + "/status")
    @ResponseBody
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<MfaService.Status> status() {
        return ApiResponse.ok(service.status(me()));
    }

    /** 지금 인증 코드로 확인 후 복구 코드 다시 만들기 (내 정보) */
    @PostMapping(API + "/recovery")
    @ResponseBody
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Map<String, List<String>>> recovery(@RequestBody CodeRequest req) {
        return ApiResponse.ok(Map.of("recoveryCodes", service.regenerateRecovery(me(), req == null ? null : req.code())));
    }

    /** 확인 대기(VERIFY) 중에는 새 등록으로 두 번째 요소를 바꿔치기할 수 없다 */
    private static void notVerifying(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && MfaService.VERIFY.equals(session.getAttribute(MfaService.SESSION_STATE))) {
            throw new BusinessException(HbErrorCode.MFA_STATE);
        }
    }

    private static void pass(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(MfaService.SESSION_STATE) != null) {
            session.removeAttribute(MfaService.SESSION_STATE);
            request.changeSessionId();   // 두 번째 요소를 마친 뒤 세션 고정 방지
        }
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
