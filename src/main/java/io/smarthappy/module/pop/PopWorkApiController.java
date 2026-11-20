package io.smarthappy.module.pop;

import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.pop.PopDtos.ApprovalForm;
import io.smarthappy.module.pop.PopDtos.ApprovalReply;
import io.smarthappy.module.pop.PopDtos.KioskExitForm;
import io.smarthappy.module.pop.PopDtos.KioskExitReply;
import io.smarthappy.module.pop.PopDtos.KioskUnlockFailedForm;
import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopWorkDtos.CorrectForm;
import io.smarthappy.module.pop.PopWorkDtos.EventForm;
import io.smarthappy.module.pop.PopWorkDtos.EventReply;
import io.smarthappy.module.pop.PopWorkDtos.LotScan;
import io.smarthappy.module.pop.PopWorkDtos.ParamForm;
import io.smarthappy.module.pop.PopWorkDtos.ParamReply;
import io.smarthappy.module.pop.PopWorkDtos.ProcView;
import io.smarthappy.module.pop.PopWorkDtos.ResultForm;
import io.smarthappy.module.pop.PopWorkDtos.ResultReply;
import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.metrics.HbMetrics;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * POP 착수·실적·투입 API (/api/pop/**, 단말 체인). 같은 client_req_id 가 동시에 두 번 오면 한쪽은 unique 위반으로
 * 트랜잭션이 깨진다 → 여기서 잡아 새 트랜잭션으로 먼저 저장된 행을 읽어 처음 응답 그대로 돌려준다(409 아님).
 */
@RestController
@RequiredArgsConstructor
public class PopWorkApiController {

    private static final String EXEMPT = "POP 단말 체인(ROLE_TERMINAL) — 단말 작업장 + 지금 작업자를 서비스가 확인(docs/25 4.7)";
    private static final Set<String> EVENTS = Set.of("START", "PAUSE", "RESUME", "END");

    private final PopAuthService auth;
    private final PopWorkService work;
    private final HbMetrics metrics;

    @GetMapping("/api/pop/procs/{id}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<ProcView> proc(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(work.view(t, auth.currentWorker(request, t), id));
    }

    @GetMapping("/api/pop/lots")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<LotScan> lot(@AuthenticationPrincipal TerminalPrincipal t, @RequestParam String lotNo,
            HttpServletRequest request) {
        return ApiResponse.ok(work.lot(auth.currentWorker(request, t), lotNo));
    }

    @PostMapping("/api/pop/procs/{id}/events/{event}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<EventReply> event(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id,
            @PathVariable String event, @RequestBody EventForm form, HttpServletRequest request) {
        String cd = event.toUpperCase(Locale.ROOT);
        if (!EVENTS.contains(cd)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return ApiResponse.ok(resent("event", once(() -> work.event(t, auth.currentWorker(request, t), id, cd, form), "uq_prd_wo_proc_event_req",
                () -> work.replayEvent(form.clientReqId(), id)), EventReply::replayed));
    }

    @PostMapping("/api/pop/procs/{id}/results")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<ResultReply> result(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id,
            @RequestBody ResultForm form, HttpServletRequest request) {
        return ApiResponse.ok(resent("result", once(() -> work.result(t, auth.currentWorker(request, t), id, form), "uq_prd_result_req",
                () -> work.replayResult(form.clientReqId(), id)), ResultReply::replayed));
    }

    @PostMapping("/api/pop/results/{id}/correct")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Long> correct(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id,
            @RequestBody CorrectForm form, HttpServletRequest request) {
        PopWorker me = auth.currentWorker(request, t);
        long rev = work.correct(t, me, id, form,
                auth.approval(request, t, me, "RESULT_CORRECT", id, form == null ? null : form.approvalToken()));
        auth.clearApproval(request);   // 쓴 승인은 버린다(한 번)
        return ApiResponse.ok(rev);
    }

    @PostMapping("/api/pop/procs/{id}/params")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<ParamReply> param(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id,
            @RequestBody ParamForm form, HttpServletRequest request) {
        return ApiResponse.ok(resent("param", once(() -> work.param(t, auth.currentWorker(request, t), id, form), "uq_prd_param_actual_req",
                () -> work.replayParam(form.clientReqId(), id)), ParamReply::replayed));
    }

    @PostMapping("/api/pop/params/{id}/correct")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Long> correctParam(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id,
            @RequestBody CorrectForm form, HttpServletRequest request) {
        PopWorker me = auth.currentWorker(request, t);
        long rev = work.correctParam(t, me, id, form,
                auth.approval(request, t, me, "PARAM_CORRECT", id, form == null ? null : form.approvalToken()));
        auth.clearApproval(request);
        return ApiResponse.ok(rev);
    }

    /** 감독자 승인 (docs/25 4.37) — 배지 + PIN → 승인 토큰(2분, 그 동작·그 대상). 정정 요청 본문 approvalToken 에 실어 보낸다 */
    @PostMapping("/api/pop/approvals")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<ApprovalReply> approve(@AuthenticationPrincipal TerminalPrincipal t, @RequestBody(required = false) ApprovalForm form,
            HttpServletRequest request) {
        return ApiResponse.ok(auth.approve(request, t, auth.currentWorker(request, t), form));
    }

    /**
     * 키오스크 빠져나오기 (docs/25 4.52, docs/50 K5) — POP·PDA 단말 공용(체인 기본 ROLE_TERMINAL). 감독자 배지 + PIN, 지금 작업자는 없어도 된다.
     * 성공해야 화면이 껍데기 다리로 고정을 푼다
     */
    @PostMapping("/api/pop/kiosk/exit")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<KioskExitReply> kioskExit(@AuthenticationPrincipal TerminalPrincipal t, @RequestBody(required = false) KioskExitForm form) {
        return ApiResponse.ok(auth.kioskExit(t, form));
    }

    /**
     * 키오스크 풀기 실패 알림 (KS9, docs/50 7.2) — 빠져나오기 응답의 exitRef 로, 서버 기록(빠져나옴)과 실제(잠긴 채)가 어긋났음을 남긴다.
     * 같은 단말의 exitRef 만(그 밖 404), 두 번째는 아무것도 안 함. 응답 본문은 화면이 쓰지 않는다
     */
    @PostMapping("/api/pop/kiosk/unlock-failed")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Void> kioskUnlockFailed(@AuthenticationPrincipal TerminalPrincipal t,
            @RequestBody(required = false) KioskUnlockFailedForm form) {
        auth.kioskUnlockFailed(t, form);
        return ApiResponse.ok(null);
    }

    /** 재전송(처음 저장한 행으로 다시 응답)이면 운영 지표 hb_pop_resend_total 에 센다(docs/52 Y2) */
    private <T> T resent(String kind, T reply, java.util.function.Predicate<T> replayed) {
        if (replayed.test(reply)) {
            metrics.popResend(kind);
        }
        return reply;
    }

    /** 동시 도착: 요청 id unique 위반이면 먼저 저장된 행으로 다시 응답 */
    private static <T> T once(Supplier<T> save, String reqConstraint, Supplier<T> replay) {
        try {
            return save.get();
        } catch (DuplicateKeyException e) {
            if (reqConstraint.equals(PgErrors.constraint(e).orElse(""))) {
                return replay.get();
            }
            throw e;
        }
    }
}
