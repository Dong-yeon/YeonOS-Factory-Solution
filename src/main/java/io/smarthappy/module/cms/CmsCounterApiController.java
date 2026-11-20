package io.smarthappy.module.cms;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.CmsCounterDtos.CounterForm;
import io.smarthappy.module.cms.CmsCounterDtos.CounterRow;
import io.smarthappy.module.cms.CmsCounterDtos.ReadingForm;
import io.smarthappy.module.cms.CmsCounterDtos.ReadingResult;
import io.smarthappy.module.cms.CmsCounterDtos.ReadingRow;
import io.smarthappy.module.cms.CmsCounterDtos.VoidForm;
import io.smarthappy.module.cms.CmsDtos.TargetRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** CMS 1부 C3 API — 사용량 카운터·읽기 (docs/33 4.4). 계획 화면의 카운터 고르기는 계획 권한으로 */
@RestController
@RequiredArgsConstructor
public class CmsCounterApiController {

    private static final String COUNTER = "/api/cms/counters";
    private static final int TARGET_LIMIT = 50;

    private final CounterService counters;
    private final CmsMapper mapper;
    private final MessageSource messages;

    @GetMapping(COUNTER)
    @ProgAuth(prog = CounterService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CounterRow>> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) String targetType) {
        return ApiResponse.ok(counters.list(keyword, useYn, targetType));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(COUNTER + "/grid")
    @ProgAuth(prog = CounterService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> counterGrid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = counters.grid(keyword, useYn, targetType, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(COUNTER + "/columns")
    @ProgAuth(prog = CounterService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> counterColumns(Locale locale) {
        List<Map<String, Object>> cols = counters.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 엑셀(C21) — grid 와 같은 조건·정렬·보이는 열 */
    @PostMapping(COUNTER + "/export")
    @ProgAuth(prog = CounterService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) CmsCounterDtos.CounterExport req, HttpServletResponse response) throws IOException {
        counters.export(req, response);
    }

    @GetMapping(COUNTER + "/targets")
    @ProgAuth(prog = CounterService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<TargetRow>> targets(@RequestParam(required = false) String keyword) {
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = keyword == null || keyword.isBlank() ? null : CmsSupport.like(keyword.strip().toUpperCase(Locale.ROOT));
        return ApiResponse.ok(mapper.findTargets(k, null, true, TARGET_LIMIT));
    }

    @GetMapping(COUNTER + "/{counterId}")
    @ProgAuth(prog = CounterService.PROG, action = Action.SEARCH)
    public ApiResponse<CounterRow> get(@PathVariable long counterId) {
        return ApiResponse.ok(counters.get(counterId));
    }

    @GetMapping(COUNTER + "/{counterId}/readings")
    @ProgAuth(prog = CounterService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ReadingRow>> readings(@PathVariable long counterId) {
        return ApiResponse.ok(counters.readings(counterId));
    }

    @PostMapping(COUNTER)
    @ProgAuth(prog = CounterService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody CounterForm form) {
        return ApiResponse.ok(counters.create(form));
    }

    @PutMapping(COUNTER + "/{counterId}")
    @ProgAuth(prog = CounterService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long counterId, @RequestBody CounterForm form) {
        counters.update(counterId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(COUNTER + "/{counterId}/readings")
    @ProgAuth(prog = CounterService.PROG, action = Action.SAVE)
    public ApiResponse<ReadingResult> addReading(@PathVariable long counterId, @RequestBody ReadingForm form) {
        return ApiResponse.ok(counters.addReading(counterId, form));
    }

    /** 무효 — 마지막 유효 행부터, 사유 필수 */
    @PostMapping(COUNTER + "/{counterId}/readings/{readingId}/void")
    @ProgAuth(prog = CounterService.PROG, action = Action.DELETE)
    public ApiResponse<String> voidReading(@PathVariable long counterId, @PathVariable long readingId, @RequestBody VoidForm form) {
        counters.voidReading(counterId, readingId, form == null ? null : form.reason());
        return ApiResponse.ok("OK");
    }

    /** 예방점검 계획 화면: 대상의 사용 중 카운터(사용량 주기 고르기) */
    @GetMapping("/api/cms/pm-plans/counters")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CounterRow>> ofTarget(@RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetCd) {
        return ApiResponse.ok(counters.ofTarget(targetType, targetCd));
    }
}
