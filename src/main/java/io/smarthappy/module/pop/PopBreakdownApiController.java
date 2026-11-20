package io.smarthappy.module.pop;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.smarthappy.module.cms.link.BreakdownReport.PhotoResult;
import io.smarthappy.module.cms.link.BreakdownReport.Symptom;
import io.smarthappy.module.pop.PopWorkDtos.BreakdownForm;
import io.smarthappy.module.pop.PopWorkDtos.BreakdownReply;
import io.smarthappy.module.pop.PopWorkDtos.EquipOption;
import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/** POP 고장 신고 API (docs/25 4.35, /api/pop/breakdown/** = 생산 단말(POP)만 — PDA 는 403) */
@RestController
@RequiredArgsConstructor
public class PopBreakdownApiController {

    private static final String EXEMPT = "POP 단말 체인(ROLE_TERMINAL_POP) — 단말 공장·작업장 + 지금 작업자를 서비스가 확인(docs/25 4.35)";

    private final PopAuthService auth;
    private final PopBreakdownService breakdowns;

    @GetMapping("/api/pop/breakdown/equips")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<List<EquipOption>> equips(@AuthenticationPrincipal TerminalPrincipal t, HttpServletRequest request) {
        return ApiResponse.ok(breakdowns.equips(t, auth.currentWorker(request, t)));
    }

    @GetMapping("/api/pop/breakdown/symptoms")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<List<Symptom>> symptoms(@AuthenticationPrincipal TerminalPrincipal t, @RequestParam(required = false) Long equipId,
            HttpServletRequest request) {
        return ApiResponse.ok(breakdowns.symptoms(t, auth.currentWorker(request, t), equipId));
    }

    @PostMapping("/api/pop/breakdown")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<BreakdownReply> report(@AuthenticationPrincipal TerminalPrincipal t, @RequestBody(required = false) BreakdownForm in,
            HttpServletRequest request) {
        return ApiResponse.ok(breakdowns.report(t, auth.currentWorker(request, t), in));
    }

    /** 신고 사진(docs/25 4.44) — multipart reqNo·clientReqId·file. 범위·형식·크기·개수는 CMS 가 판정 */
    @PostMapping("/api/pop/breakdown/photos")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<PhotoResult> photo(@AuthenticationPrincipal TerminalPrincipal t, @RequestParam(required = false) String reqNo,
            @RequestParam(required = false) String clientReqId, @RequestParam(required = false) MultipartFile file,
            HttpServletRequest request) {
        return ApiResponse.ok(breakdowns.attachPhoto(t, auth.currentWorker(request, t), reqNo, clientReqId, file));
    }
}
