package io.smarthappy.module.plm;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.master.bom.BomChangeDtos.ReplaceDone;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplacePreview;
import io.smarthappy.module.plm.PlmDtos.Candidate;
import io.smarthappy.module.plm.PlmDtos.CustApprovalForm;
import io.smarthappy.module.plm.PlmDtos.CustOption;
import io.smarthappy.module.plm.PlmDtos.EcoActForm;
import io.smarthappy.module.plm.PlmDtos.EcoDetail;
import io.smarthappy.module.plm.PlmDtos.EcoForm;
import io.smarthappy.module.plm.PlmDtos.EcoList;
import io.smarthappy.module.plm.PlmDtos.EcrAcceptForm;
import io.smarthappy.module.plm.PlmDtos.EcrActForm;
import io.smarthappy.module.plm.PlmDtos.EcrForm;
import io.smarthappy.module.plm.PlmDtos.EcrList;
import io.smarthappy.module.plm.PlmDtos.EcrRow;
import io.smarthappy.module.plm.PlmDtos.MarkList;
import io.smarthappy.module.plm.PlmDtos.PcnForm;
import io.smarthappy.module.plm.PlmDtos.PcnResponseForm;
import io.smarthappy.module.plm.PlmDtos.PcnRow;
import io.smarthappy.module.plm.PlmDtos.PpapActForm;
import io.smarthappy.module.plm.PlmDtos.PpapCreateForm;
import io.smarthappy.module.plm.PlmDtos.PpapDecideForm;
import io.smarthappy.module.plm.PlmDtos.PpapDetail;
import io.smarthappy.module.plm.PlmDtos.PpapForm;
import io.smarthappy.module.plm.PlmDtos.PpapSubmitForm;
import io.smarthappy.module.plm.PlmDtos.ReplaceForm;
import io.smarthappy.module.plm.PlmDtos.SubmitForm;
import io.smarthappy.module.plm.PlmDtos.TargetSet;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserOption;
import lombok.RequiredArgsConstructor;

/** 설계 변경 API (PLM 1단계 docs/39 5장, 3b 표시된 LOT 13장, 4단계 PPAP·PCN·일괄 대체 14장) */
@RestController
@RequiredArgsConstructor
public class PlmApiController {

    private static final String ECR = "/api/plm/ecrs";
    private static final String ECO = "/api/plm/ecos";
    private static final String PPAP = "/api/plm/ppaps";
    private static final String PCN = "/api/plm/pcns";

    private final EcrService ecrs;
    private final EcoService ecos;
    private final PpapService ppaps;
    private final PpapApqpService ppapApqp;
    private final PcnService pcns;
    private final EcoNoticeService notices;
    private final PpapSummaryService ppapSummaries;
    private final EcoRecordService records;
    private final PpapMailService ppapMails;
    private final org.springframework.context.MessageSource messages;

    // ------------------------------------------------------------------ ECR

    /** grid(UI 단계 3, docs/39 21.2) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(ECR + "/grid")
    @ProgAuth(prog = EcrService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> ecrGrid(@RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String itemCd, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            jakarta.servlet.http.HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        var p = ecrs.grid(status, keyword, itemCd, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** 엑셀(docs/39 25장) — grid 메뉴 "엑셀", 같은 조건·정렬·보이는 열 */
    @PostMapping(ECR + "/export")
    @ProgAuth(prog = EcrService.PROG, action = Action.EXCEL)
    public void ecrExport(@RequestBody(required = false) io.smarthappy.module.plm.PlmDtos.EcrExport req,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        ecrs.export(req, response);
    }

    @GetMapping(ECR + "/columns")
    @ProgAuth(prog = EcrService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> ecrColumns(java.util.Locale locale) {
        List<java.util.Map<String, Object>> cols = ecrs.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(ECR)
    @ProgAuth(prog = EcrService.PROG, action = Action.SEARCH)
    public ApiResponse<EcrList> ecrList(@RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String itemCd) {
        return ApiResponse.ok(ecrs.list(status, keyword, itemCd));
    }

    @GetMapping(ECR + "/{ecrId}")
    @ProgAuth(prog = EcrService.PROG, action = Action.SEARCH)
    public ApiResponse<EcrRow> ecrDetail(@PathVariable long ecrId) {
        return ApiResponse.ok(ecrs.detail(ecrId));
    }

    @PostMapping(ECR)
    @ProgAuth(prog = EcrService.PROG, action = Action.SAVE)
    public ApiResponse<Long> ecrCreate(@RequestBody EcrForm form) {
        return ApiResponse.ok(ecrs.create(form));
    }

    @PutMapping(ECR + "/{ecrId}")
    @ProgAuth(prog = EcrService.PROG, action = Action.SAVE)
    public ApiResponse<String> ecrUpdate(@PathVariable long ecrId, @RequestBody EcrForm form) {
        ecrs.update(ecrId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ECR + "/{ecrId}/cancel")
    @ProgAuth(prog = EcrService.PROG, action = Action.SAVE)
    public ApiResponse<String> ecrCancel(@PathVariable long ecrId, @RequestBody EcrActForm form) {
        ecrs.cancel(ecrId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ECR + "/{ecrId}/reject")
    @ProgAuth(prog = EcrService.PROG, action = Action.APPROVE)
    public ApiResponse<String> ecrReject(@PathVariable long ecrId, @RequestBody EcrActForm form) {
        ecrs.reject(ecrId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ECR + "/{ecrId}/accept")
    @ProgAuth(prog = EcrService.PROG, action = Action.APPROVE)
    public ApiResponse<Long> ecrAccept(@PathVariable long ecrId, @RequestBody EcrAcceptForm form) {
        return ApiResponse.ok(ecrs.accept(ecrId, form));
    }

    @GetMapping(ECR + "/items")
    @ProgAuth(prog = EcrService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ItemOption>> ecrItems(@RequestParam(required = false) String q) {
        return ApiResponse.ok(ecos.items(q));
    }

    /** 접수 때 묶을 작성 중 ECO 고르기 */
    @GetMapping(ECR + "/draft-ecos")
    @ProgAuth(prog = EcrService.PROG, action = Action.APPROVE)
    public ApiResponse<EcoList> draftEcos() {
        return ApiResponse.ok(ecos.list("DRAFT", null, null));
    }

    // ------------------------------------------------------------------ ECO

    /** grid(UI 단계 3, docs/39 21.2) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(ECO + "/grid")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> ecoGrid(@RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String itemCd, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            jakarta.servlet.http.HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        var p = ecos.grid(status, keyword, itemCd, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** 엑셀(docs/39 25장) — grid 메뉴 "엑셀", 같은 조건·정렬·보이는 열 */
    @PostMapping(ECO + "/export")
    @ProgAuth(prog = EcoService.PROG, action = Action.EXCEL)
    public void ecoExport(@RequestBody(required = false) io.smarthappy.module.plm.PlmDtos.EcoExport req,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        ecos.export(req, response);
    }

    /** 변경 통지서 엑셀(docs/39 29장) — 고객 제출용 서식 한 장, 엑셀 권한 */
    @PostMapping(ECO + "/{ecoId}/notice")
    @ProgAuth(prog = EcoService.PROG, action = Action.EXCEL)
    public void ecoNotice(@PathVariable long ecoId, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        EcoNoticeService.Notice n = notices.notice(ecoId);
        io.smarthappy.platform.log.AccessLogService.setDetail("notice;ecoId=" + ecoId);   // 접속 로그 ACTION·EXCEL 에 어떤 문서(34장 R4)
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                .filename(n.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString());
        response.setHeader(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.getOutputStream().write(n.bytes());
    }

    /** PPAP 제출 요약서 엑셀(docs/39 31장) — 고객 제출용 한 장, 엑셀 권한 */
    @PostMapping(PPAP + "/{ppapId}/summary")
    @ProgAuth(prog = EcoService.PROG, action = Action.EXCEL)
    public void ppapSummary(@PathVariable long ppapId, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        PpapSummaryService.Summary s = ppapSummaries.summary(ppapId);
        io.smarthappy.platform.log.AccessLogService.setDetail("ppapSummary;ppapId=" + ppapId);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                .filename(s.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString());
        response.setHeader(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.getOutputStream().write(s.bytes());
    }

    /** 사내 지시 기록서 엑셀(docs/39 34장) — 감사·품질 보관용, 엑셀 권한(접속 로그 ACTION·EXCEL) */
    @PostMapping(ECO + "/{ecoId}/record")
    @ProgAuth(prog = EcoService.PROG, action = Action.EXCEL)
    public void ecoRecord(@PathVariable long ecoId, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        EcoRecordService.Record r = records.record(ecoId);
        io.smarthappy.platform.log.AccessLogService.setDetail("record;ecoId=" + ecoId);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                .filename(r.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString());
        response.setHeader(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.getOutputStream().write(r.bytes());
    }

    /** 지시 복사(docs/39 27장) — 새 작성 중 지시, 리비전 대상은 빼고 건수로 알림 */
    @PostMapping(ECO + "/{ecoId}/copy")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<io.smarthappy.module.plm.PlmDtos.EcoCopyResult> ecoCopy(@PathVariable long ecoId) {
        return ApiResponse.ok(ecos.copy(ecoId));
    }

    @GetMapping(ECO + "/columns")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> ecoColumns(java.util.Locale locale) {
        List<java.util.Map<String, Object>> cols = ecos.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(ECO)
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<EcoList> ecoList(@RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String itemCd) {
        return ApiResponse.ok(ecos.list(status, keyword, itemCd));
    }

    @GetMapping(ECO + "/{ecoId}")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<EcoDetail> ecoDetail(@PathVariable long ecoId) {
        return ApiResponse.ok(ecos.detail(ecoId));
    }

    /** 표시된 LOT — 변경 뒤 첫 N LOT·4M 구간 LOT (3b, docs/39 13장) */
    @GetMapping(ECO + "/{ecoId}/lots")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<MarkList> ecoLots(@PathVariable long ecoId) {
        return ApiResponse.ok(ecos.marks(ecoId));
    }

    @GetMapping(ECO + "/candidates")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<Candidate>> candidates(@RequestParam(required = false) String itemCd) {
        return ApiResponse.ok(ecos.candidates(itemCd));
    }

    /** 영향 대상 후보 보기(docs/39 20.7) — 읽기만, 권한은 설계 변경 조회(BOM 역전개 화면 권한은 보지 않는다, I4) */
    @GetMapping(ECO + "/{ecoId}/impact")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<io.smarthappy.module.plm.PlmDtos.ImpactResult> impact(@PathVariable long ecoId,
            @RequestParam(required = false) String itemCd, @RequestParam(required = false) String scope) {
        return ApiResponse.ok(ecos.impact(ecoId, itemCd, scope));
    }

    @GetMapping(ECO + "/items")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ItemOption>> ecoItems(@RequestParam(required = false) String q) {
        return ApiResponse.ok(ecos.items(q));
    }

    @GetMapping(ECO + "/users")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<UserOption>> users() {
        return ApiResponse.ok(ecos.users());
    }

    @GetMapping(ECO + "/custs")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CustOption>> custs() {
        return ApiResponse.ok(ecos.custs());
    }

    @PostMapping(ECO)
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<Long> ecoCreate(@RequestBody EcoForm form) {
        return ApiResponse.ok(ecos.create(form));
    }

    @PutMapping(ECO + "/{ecoId}")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<String> ecoUpdate(@PathVariable long ecoId, @RequestBody EcoForm form) {
        ecos.update(ecoId, form);
        return ApiResponse.ok("OK");
    }

    @PutMapping(ECO + "/{ecoId}/targets")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<String> saveTargets(@PathVariable long ecoId, @RequestBody TargetSet form) {
        ecos.saveTargets(ecoId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ECO + "/{ecoId}/submit")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<Long> submit(@PathVariable long ecoId, @RequestBody SubmitForm form) {
        return ApiResponse.ok(ecos.submit(ecoId, form));
    }

    @PostMapping(ECO + "/{ecoId}/cust-approval")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<String> custApproval(@PathVariable long ecoId, @RequestBody CustApprovalForm form) {
        ecos.custApproval(ecoId, form);
        return ApiResponse.ok("OK");
    }

    /**
     * 결재 승인 + 전자서명(docs/39 19장) — 권한은 결재선(결재 엔진이 내 차례인지 판정), 화면은 ECO 조회 권한.
     * 반려·회수는 서명 없이 공통 결재 API 그대로
     */
    @PostMapping(ECO + "/{ecoId}/approve")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<String> approve(@PathVariable long ecoId,
            @RequestBody(required = false) io.smarthappy.module.plm.PlmDtos.EcoApproveForm form) {
        ecos.approve(ecoId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ECO + "/{ecoId}/release")
    @ProgAuth(prog = EcoService.PROG, action = Action.APPROVE)
    public ApiResponse<String> release(@PathVariable long ecoId, @RequestBody EcoActForm form) {
        ecos.release(ecoId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(ECO + "/{ecoId}/cancel")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long ecoId, @RequestBody EcoActForm form) {
        ecos.cancel(ecoId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 4단계: 일괄 대체 연동 · PPAP · PCN (docs/39 14장)

    @GetMapping(ECO + "/{ecoId}/replace-preview")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<ReplacePreview> replacePreview(@PathVariable long ecoId, @RequestParam(required = false) String fromItemCd,
            @RequestParam(required = false) String toItemCd) {
        return ApiResponse.ok(ecos.replacePreview(ecoId, fromItemCd, toItemCd));
    }

    @PostMapping(ECO + "/{ecoId}/replace")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<List<ReplaceDone>> replace(@PathVariable long ecoId, @RequestBody ReplaceForm form) {
        return ApiResponse.ok(ecos.replace(ecoId, form));
    }

    @GetMapping(ECO + "/{ecoId}/ppap")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<PpapDetail> ppap(@PathVariable long ecoId) {
        return ApiResponse.ok(ppaps.ofEco(ecoId));
    }

    @PostMapping(ECO + "/{ecoId}/ppap")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<Long> ppapCreate(@PathVariable long ecoId, @RequestBody PpapCreateForm form) {
        return ApiResponse.ok(ppaps.create(ecoId, form));
    }

    @PutMapping(PPAP + "/{ppapId}")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<String> ppapSave(@PathVariable long ppapId, @RequestBody PpapForm form) {
        ppaps.save(ppapId, form);
        return ApiResponse.ok("OK");
    }

    /** 요소 6·7 APQP 근거 후보(docs/39 38장 Q1·Q4) — 지시 대상 품목의 현행 확정본, 그 APQP 화면 SEARCH 없는 종류는 빈 목록 */
    @GetMapping(PPAP + "/{ppapId}/apqp-candidates")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<io.smarthappy.module.plm.PlmDtos.PpapApqpCandidates> ppapApqpCandidates(@PathVariable long ppapId) {
        return ApiResponse.ok(ppapApqp.candidates(ppapId));
    }

    /** 요소 6·7 APQP 근거 연결·끊기(38장 Q2·Q3) — 작성 중만, 후보 밖 400, APQP SEARCH 없음 403 */
    @PutMapping(PPAP + "/{ppapId}/apqp")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<String> ppapApqpLink(@PathVariable long ppapId, @RequestBody io.smarthappy.module.plm.PlmDtos.PpapApqpForm form) {
        ppapApqp.link(ppapId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(PPAP + "/{ppapId}/submit")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<String> ppapSubmit(@PathVariable long ppapId, @RequestBody PpapSubmitForm form) {
        ppaps.submit(ppapId, form);
        return ApiResponse.ok("OK");
    }

    /** 고객 판정 기록 — 승인이면 ECO 고객 승인까지(적용 게이트) */
    @PostMapping(PPAP + "/{ppapId}/decide")
    @ProgAuth(prog = EcoService.PROG, action = Action.APPROVE)
    public ApiResponse<String> ppapDecide(@PathVariable long ppapId, @RequestBody PpapDecideForm form,
            jakarta.servlet.http.HttpSession session) {
        ppaps.decide(ppapId, form, session);
        return ApiResponse.ok("OK");
    }

    @PostMapping(PPAP + "/{ppapId}/reopen")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<String> ppapReopen(@PathVariable long ppapId, @RequestBody PpapActForm form) {
        ppaps.reopen(ppapId, form);
        return ApiResponse.ok("OK");
    }

    @GetMapping(ECO + "/{ecoId}/pcns")
    @ProgAuth(prog = EcoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PcnRow>> pcnList(@PathVariable long ecoId) {
        return ApiResponse.ok(pcns.ofEco(ecoId));
    }

    @PostMapping(ECO + "/{ecoId}/pcns")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<Long> pcnCreate(@PathVariable long ecoId, @RequestBody PcnForm form) {
        return ApiResponse.ok(pcns.create(ecoId, form));
    }

    /** 메일 받는 사람 후보 + 메일 켜짐 (docs/39 23장) — 담당자 메일은 개인정보라 SAVE(보낼 수 있는 사람)만 */
    /** PPAP 제출 메일 받는 사람 후보(docs/39 37장 P1·P9) — 패키지 고객의 담당자만, SAVE(담당자 메일은 개인정보) */
    @GetMapping(PPAP + "/{ppapId}/mail-targets")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<io.smarthappy.module.plm.PlmDtos.PcnMailTargets> ppapMailTargets(@PathVariable long ppapId) {
        return ApiResponse.ok(ppapMails.mailTargets(ppapId));
    }

    /** PPAP 제출 메일 보내기(37장) — 요약서 첨부, X-Idempotency-Key 필수 → 보낸 메일 수 */
    @PostMapping(PPAP + "/{ppapId}/mail")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> ppapMail(@PathVariable long ppapId, @RequestBody io.smarthappy.module.plm.PlmDtos.PpapMailForm form,
            @org.springframework.web.bind.annotation.RequestHeader(io.smarthappy.platform.messaging.InboxService.IDEMPOTENCY_HEADER)
            String idempotencyKey) {
        // 외부로 나가는 메일이라 연타·재전송 방지 키를 필수로(없으면 400, 23장과 같다) — 처리는 서비스의 claimRequestKey
        return ApiResponse.ok(ppapMails.mail(ppapId, form));
    }

    @GetMapping(PCN + "/{pcnId}/mail-targets")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<io.smarthappy.module.plm.PlmDtos.PcnMailTargets> pcnMailTargets(@PathVariable long pcnId) {
        return ApiResponse.ok(pcns.mailTargets(pcnId));
    }

    /** 고객 담당자에게 메일 — 열린 통지만, 멱등 키 필수, 보낸 통 수 */
    @PostMapping(PCN + "/{pcnId}/mail")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> pcnMail(@PathVariable long pcnId, @RequestBody io.smarthappy.module.plm.PlmDtos.PcnMailForm form,
            @org.springframework.web.bind.annotation.RequestHeader(io.smarthappy.platform.messaging.InboxService.IDEMPOTENCY_HEADER)
            String idempotencyKey) {
        // 외부로 나가는 메일이라 연타·재전송 방지 키를 필수로(없으면 400) — 처리는 서비스의 claimRequestKey
        return ApiResponse.ok(pcns.mail(pcnId, form));
    }

    @PostMapping(PCN + "/{pcnId}/response")
    @ProgAuth(prog = EcoService.PROG, action = Action.SAVE)
    public ApiResponse<String> pcnRespond(@PathVariable long pcnId, @RequestBody PcnResponseForm form) {
        pcns.respond(pcnId, form);
        return ApiResponse.ok("OK");
    }
}
