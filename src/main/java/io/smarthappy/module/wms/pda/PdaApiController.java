package io.smarthappy.module.wms.pda;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.wms.alloc.AllocDtos.PickView;
import io.smarthappy.module.wms.count.CountDtos.CountHead;
import io.smarthappy.module.wms.count.CountDtos.CountView;
import io.smarthappy.module.wms.count.CountDtos.CountsForm;
import io.smarthappy.module.wms.pda.PdaDtos.MoveForm;
import io.smarthappy.module.wms.pda.PdaDtos.MoveResult;
import io.smarthappy.module.wms.pda.PdaDtos.PdaScan;
import io.smarthappy.module.wms.ship.ShipDtos.PickForm;
import io.smarthappy.module.wms.ship.ShipDtos.ShipHead;
import io.smarthappy.module.wms.ship.ShipDtos.ShipView;
import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 창고 PDA API (/api/pop/wh/**, B7-1 docs/30 12.12) — 경로 주인은 WMS, 보안은 MES 단말 체인(PDA 유형만, PopSecurityConfig).
 * 업무 권한은 "단말 + 지금 작업자" 를 서비스가 본다(5a 규칙과 같음).
 */
@RestController
@RequiredArgsConstructor
public class PdaApiController {

    private static final String WH = "/api/pop/wh";
    private static final String EXEMPT = "단말 체인(ROLE_TERMINAL, PDA 유형) — 단말 공장 + 지금 작업자를 서비스가 확인(docs/30 12.12)";

    private final PopAuthService auth;
    private final PdaService service;

    @GetMapping(WH + "/scan")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<PdaScan> scan(@AuthenticationPrincipal TerminalPrincipal t, @RequestParam(required = false) String text,
            HttpServletRequest request) {
        return ApiResponse.ok(service.scan(t, auth.currentWorker(request, t), text));
    }

    /**
     * 이동 — 오프라인 대기열이 같은 clientReqId·같은 내용으로 다시 보내면 처음 결과, 내용이 다르면 409(PQ-1).
     * 동시에 두 번이면 한쪽이 unique 에 걸린다 → 새 트랜잭션에서 다시 불러 먼저 들어간 행과 같으면 처음 결과
     */
    @PostMapping(WH + "/moves")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<MoveResult> move(@AuthenticationPrincipal TerminalPrincipal t, @RequestBody MoveForm form,
            HttpServletRequest request) {
        try {
            return ApiResponse.ok(service.move(t, auth.currentWorker(request, t), form));
        } catch (BusinessException e) {
            if (e.code() == HbErrorCode.DUPLICATE_REQUEST && form != null && form.clientReqId() != null) {
                return ApiResponse.ok(service.replayMove(t, auth.currentWorker(request, t), form));
            }
            throw e;
        }
    }

    @GetMapping(WH + "/counts")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<List<CountHead>> counts(@AuthenticationPrincipal TerminalPrincipal t, HttpServletRequest request) {
        return ApiResponse.ok(service.counts(t, auth.currentWorker(request, t)));
    }

    @GetMapping(WH + "/counts/{id}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<CountView> count(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.count(t, auth.currentWorker(request, t), id));
    }

    @PostMapping(WH + "/counts/{id}/counts")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<CountView> enter(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id, @RequestBody CountsForm form,
            HttpServletRequest request) {
        return ApiResponse.ok(service.enter(t, auth.currentWorker(request, t), id, form));
    }

    // ------------------------------------------------------------------ 피킹 (B7-2a, 온라인만)

    @GetMapping(WH + "/ships")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<List<ShipHead>> ships(@AuthenticationPrincipal TerminalPrincipal t, HttpServletRequest request) {
        return ApiResponse.ok(service.ships(t, auth.currentWorker(request, t)));
    }

    @GetMapping(WH + "/ships/{id}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<ShipView> ship(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.ship(t, auth.currentWorker(request, t), id));
    }

    @GetMapping(WH + "/ships/{id}/lines/{lineId}/suggest")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<List<PickView>> suggest(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id, @PathVariable long lineId,
            HttpServletRequest request) {
        return ApiResponse.ok(service.suggest(t, auth.currentWorker(request, t), id, lineId));
    }

    // ------------------------------------------------------------------ 반품 출고 (B7-3, 온라인만)

    // ------------------------------------------------------------------ 정위치 점검 (docs/55) — 온라인만(대기열 안 씀)

    @PostMapping(WH + "/loc-checks/scan")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<io.smarthappy.module.wms.loccheck.LocCheckDtos.View> locCheckStart(@AuthenticationPrincipal TerminalPrincipal t,
            @RequestBody io.smarthappy.module.wms.inv.InvDtos.ScanForm form, HttpServletRequest request) {
        return ApiResponse.ok(service.locCheckStart(t, auth.currentWorker(request, t), form == null ? null : form.text()));
    }

    @GetMapping(WH + "/loc-checks/{id}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<io.smarthappy.module.wms.loccheck.LocCheckDtos.View> locCheck(@AuthenticationPrincipal TerminalPrincipal t,
            @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.locCheck(t, auth.currentWorker(request, t), id));
    }

    @PostMapping(WH + "/loc-checks/{id}/lines")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<io.smarthappy.module.wms.loccheck.LocCheckDtos.ScanResult> locCheckScan(@AuthenticationPrincipal TerminalPrincipal t,
            @PathVariable long id, @RequestBody io.smarthappy.module.wms.loccheck.LocCheckDtos.LineForm form, HttpServletRequest request) {
        return ApiResponse.ok(service.locCheckScan(t, auth.currentWorker(request, t), id, form));
    }

    @PostMapping(WH + "/loc-checks/{id}/done")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<io.smarthappy.module.wms.loccheck.LocCheckDtos.View> locCheckDone(@AuthenticationPrincipal TerminalPrincipal t,
            @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.locCheckDone(t, auth.currentWorker(request, t), id));
    }

    @GetMapping(WH + "/returns/available")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Boolean> returnsAvailable(@AuthenticationPrincipal TerminalPrincipal t, HttpServletRequest request) {
        return ApiResponse.ok(service.returnsAvailable(auth.currentWorker(request, t)));
    }

    @GetMapping(WH + "/returns")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<io.smarthappy.module.wms.inv.ReturnIssueService.ReturnView> returnView(@AuthenticationPrincipal TerminalPrincipal t,
            @RequestParam(required = false) String returnNo, HttpServletRequest request) {
        return ApiResponse.ok(service.returnView(t, auth.currentWorker(request, t), returnNo));
    }

    @PostMapping(WH + "/returns")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<io.smarthappy.module.wms.inv.ReturnIssueService.IssueResult> returnIssue(@AuthenticationPrincipal TerminalPrincipal t,
            @RequestBody io.smarthappy.module.wms.inv.InvDtos.RtnIssueForm form, HttpServletRequest request) {
        try {
            return ApiResponse.ok(service.returnIssue(t, auth.currentWorker(request, t), form));
        } catch (BusinessException e) {
            if (!io.smarthappy.module.wms.inv.ReturnIssueService.replayable(e, form)) {
                throw e;
            }
            return ApiResponse.ok(service.replayReturnIssue(t, auth.currentWorker(request, t), form));     // QR-1
        }
    }

    // ---------------------------------------------------------------- 입고 (docs/30 12.12.15, 온라인만)

    @GetMapping(WH + "/rcv/po-enabled")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Boolean> rcvPoEnabled(@AuthenticationPrincipal TerminalPrincipal t, HttpServletRequest request) {
        return ApiResponse.ok(service.rcvPoEnabled(auth.currentWorker(request, t)));
    }

    @GetMapping(WH + "/rcv/scan")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<io.smarthappy.module.wms.inv.InvDtos.RcvScan> rcvScan(@AuthenticationPrincipal TerminalPrincipal t,
            @RequestParam(required = false) String text, HttpServletRequest request) {
        return ApiResponse.ok(service.rcvScan(t, auth.currentWorker(request, t), text));
    }

    @GetMapping(WH + "/rcv/asn")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<io.smarthappy.module.srm.link.AsnQuery.Asn> rcvAsn(@AuthenticationPrincipal TerminalPrincipal t,
            @RequestParam(required = false) String key, HttpServletRequest request) {
        return ApiResponse.ok(service.rcvAsn(t, auth.currentWorker(request, t), key));
    }

    @GetMapping(WH + "/rcv/po-lines")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<List<io.smarthappy.module.wms.inv.InvDtos.PoLineOpt>> rcvPoLines(@AuthenticationPrincipal TerminalPrincipal t,
            @RequestParam(required = false) Long itemId, HttpServletRequest request) {
        return ApiResponse.ok(service.rcvPoLines(t, auth.currentWorker(request, t), itemId));
    }

    @GetMapping(WH + "/rcv/locs")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<List<io.smarthappy.module.wms.inv.InvDtos.RcvLoc>> rcvLocs(@AuthenticationPrincipal TerminalPrincipal t,
            @RequestParam(required = false) Long itemId, HttpServletRequest request) {
        return ApiResponse.ok(service.rcvLocs(t, auth.currentWorker(request, t), itemId));
    }

    /** 입고 — 같은 clientReqId 다시 = 처음 결과(replay, 동시면 뒤 요청이 기다렸다가 처음 결과 — MaterialReceiptService inbox) */
    @PostMapping(WH + "/rcv")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<io.smarthappy.module.wms.inv.InvDtos.RcvResult> receive(@AuthenticationPrincipal TerminalPrincipal t,
            @RequestBody(required = false) PdaDtos.RcvForm form, HttpServletRequest request) {
        return ApiResponse.ok(service.receive(t, auth.currentWorker(request, t), form));
    }

    @PostMapping(WH + "/ships/{id}/notes/{noteId}/ack")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<ShipView> ackNote(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id, @PathVariable long noteId,
            HttpServletRequest request) {
        return ApiResponse.ok(service.ackNote(t, auth.currentWorker(request, t), id, noteId));
    }

    @PostMapping(WH + "/ships/{id}/picks")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<ShipView> pick(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id, @RequestBody PickForm form,
            HttpServletRequest request) {
        return ApiResponse.ok(service.pick(t, auth.currentWorker(request, t), id, form));
    }
}
