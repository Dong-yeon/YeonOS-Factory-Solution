package io.smarthappy.module.cms;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.CmsCounterDtos.VoidForm;
import io.smarthappy.module.cms.CollectorDtos.PlantRow;
import io.smarthappy.module.cms.CmsStateDtos.LinkForm;
import io.smarthappy.module.cms.CmsStateDtos.MwoDowntime;
import io.smarthappy.module.cms.CmsStateDtos.MwoOption;
import io.smarthappy.module.cms.CmsStateDtos.NoteForm;
import io.smarthappy.module.cms.CmsStateDtos.ReasonForm;
import io.smarthappy.module.cms.CmsStateDtos.ReasonRow;
import io.smarthappy.module.cms.CmsStateDtos.ReviewDetail;
import io.smarthappy.module.cms.CmsStateDtos.ReviewRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** CMS 2부 C7 API — 상태 사유 관리(cms-state-reason)·비가동 사유 검토(cms-state-review) (docs/33 11.2 C7) */
@RestController
@RequiredArgsConstructor
public class CmsStateApiController {

    private static final String REASON = "/api/cms/state-reasons";
    private static final String REVIEW = "/api/cms/state-review";

    private final StateReasonService reasons;
    private final StateReviewService review;
    private final StateTimelineService timeline;
    private final MwoService mwos;
    private final MessageSource messages;

    // ------------------------------------------------------------------ 사유

    @GetMapping(REASON)
    @ProgAuth(prog = StateReasonService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ReasonRow>> reasons(@RequestParam(required = false) String keyword, @RequestParam(required = false) String stateCat,
            @RequestParam(required = false) String useYn) {
        return ApiResponse.ok(reasons.list(keyword, stateCat, useYn));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(REASON + "/grid")
    @ProgAuth(prog = StateReasonService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> reasonGrid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String stateCat,
            @RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = reasons.grid(keyword, stateCat, useYn, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(REASON + "/columns")
    @ProgAuth(prog = StateReasonService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> reasonColumns(Locale locale) {
        List<Map<String, Object>> cols = reasons.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 엑셀(C23) — grid 와 같은 조건·정렬·보이는 열 */
    @PostMapping(REASON + "/export")
    @ProgAuth(prog = StateReasonService.PROG, action = Action.EXCEL)
    public void reasonExport(@RequestBody(required = false) CmsStateDtos.ReasonExport req, HttpServletResponse response) throws IOException {
        reasons.export(req, response);
    }

    @GetMapping(REASON + "/{reasonId}")
    @ProgAuth(prog = StateReasonService.PROG, action = Action.SEARCH)
    public ApiResponse<ReasonRow> reason(@PathVariable long reasonId) {
        return ApiResponse.ok(reasons.get(reasonId));
    }

    @PostMapping(REASON)
    @ProgAuth(prog = StateReasonService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createReason(@RequestBody ReasonForm form) {
        return ApiResponse.ok(reasons.create(form));
    }

    @PutMapping(REASON + "/{reasonId}")
    @ProgAuth(prog = StateReasonService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateReason(@PathVariable long reasonId, @RequestBody ReasonForm form) {
        reasons.update(reasonId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 검토

    @GetMapping(REVIEW + "/plants")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(timeline.plants());
    }

    @GetMapping(REVIEW + "/reasons")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ReasonRow>> activeReasons() {
        return ApiResponse.ok(review.reasons());
    }

    @GetMapping(REVIEW)
    @ProgAuth(prog = StateReviewService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ReviewRow>> list(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String effCat, @RequestParam(required = false) String pending,
            @RequestParam(required = false) String reasonCd) {
        return ApiResponse.ok(review.list(plantCd, from, to, keyword, effCat, "Y".equals(pending), reasonCd));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(REVIEW + "/grid")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> reviewGrid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String effCat, @RequestParam(required = false) String pending,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, @RequestParam(required = false) String reasonCd, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = review.grid(plantCd, from, to, keyword, effCat, "Y".equals(pending), page, size, sort == null ? null : List.of(sort), total,
                reasonCd);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(REVIEW + "/columns")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> reviewColumns(Locale locale) {
        List<Map<String, Object>> cols = review.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 엑셀(C21) — grid 와 같은 조건·정렬·보이는 열 */
    @PostMapping(REVIEW + "/export")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.EXCEL)
    public void reviewExport(@RequestBody(required = false) CmsStateDtos.ReviewExport req, HttpServletResponse response) throws IOException {
        review.export(req, response);
    }

    @GetMapping(REVIEW + "/{stateLogId}")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.SEARCH)
    public ApiResponse<ReviewDetail> get(@PathVariable long stateLogId) {
        return ApiResponse.ok(review.get(stateLogId));
    }

    @GetMapping(REVIEW + "/{stateLogId}/mwo-options")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.SEARCH)
    public ApiResponse<List<MwoOption>> mwoOptions(@PathVariable long stateLogId) {
        return ApiResponse.ok(review.mwoOptions(stateLogId));
    }

    @PostMapping(REVIEW + "/{stateLogId}/notes")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addNote(@PathVariable long stateLogId, @RequestBody NoteForm form) {
        return ApiResponse.ok(review.addNote(stateLogId, form));
    }

    /** 무효 — 사유 필수. 다시 지정은 무효 뒤 새 행 */
    @PostMapping(REVIEW + "/{stateLogId}/notes/{noteId}/void")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.DELETE)
    public ApiResponse<String> voidNote(@PathVariable long stateLogId, @PathVariable long noteId, @RequestBody VoidForm form) {
        review.voidNote(stateLogId, noteId, form == null ? null : form.reason());
        return ApiResponse.ok("OK");
    }

    @PostMapping(REVIEW + "/{stateLogId}/links")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.SAVE)
    public ApiResponse<Long> link(@PathVariable long stateLogId, @RequestBody LinkForm form) {
        return ApiResponse.ok(review.link(stateLogId, form));
    }

    @PostMapping(REVIEW + "/{stateLogId}/links/{linkId}/void")
    @ProgAuth(prog = StateReviewService.PROG, action = Action.DELETE)
    public ApiResponse<String> unlink(@PathVariable long stateLogId, @PathVariable long linkId, @RequestBody VoidForm form) {
        review.unlink(stateLogId, linkId, form == null ? null : form.reason());
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 보전 작업지시 상세(C11)

    /** 연결된 수집 정지 구간·이 작업지시를 만든 알람 — 작업지시 조회 권한 */
    @GetMapping("/api/cms/mwos/{mwoId}/downtime")
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    public ApiResponse<MwoDowntime> mwoDowntime(@PathVariable long mwoId) {
        return ApiResponse.ok(review.mwoDowntime(mwoId, mwos.get(mwoId).getPlantCd()));
    }
}
