package io.smarthappy.module.srm.eval;

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

import io.smarthappy.module.srm.eval.EvalDtos.ActionForm;
import io.smarthappy.module.srm.eval.EvalDtos.EvalDetail;
import io.smarthappy.module.srm.eval.EvalDtos.EvalForm;
import io.smarthappy.module.srm.eval.EvalDtos.EvalPage;
import io.smarthappy.module.srm.eval.EvalDtos.EvalRow;
import io.smarthappy.module.srm.eval.EvalDtos.ScoresForm;
import io.smarthappy.module.srm.eval.EvalDtos.SupplierOption;
import io.smarthappy.module.srm.eval.EvalDtos.TmplDetail;
import io.smarthappy.module.srm.eval.EvalDtos.TmplForm;
import io.smarthappy.module.srm.eval.EvalDtos.TmplPage;
import io.smarthappy.module.srm.eval.EvalDtos.TmplRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 평가 템플릿·협력사 평가 API (SRM S6, docs/31 4.6) */
@RestController
@RequiredArgsConstructor
public class EvalApiController {

    private static final String TMPL = "/api/srm/eval-tmpls";
    private static final String EVAL = "/api/srm/evals";
    private static final String TP = EvalTmplService.PROG;
    private static final String EP = EvalService.PROG;

    private final EvalTmplService tmpls;
    private final EvalService evals;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    // ------------------------------------------------------------------ 템플릿

    @GetMapping(TMPL)
    @ProgAuth(prog = TP, action = Action.SEARCH)
    public ApiResponse<List<TmplRow>> tmplList(@RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(tmpls.list(status, keyword));
    }

    /** 템플릿 grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(TMPL + "/grid")
    @ProgAuth(prog = TP, action = Action.SEARCH)
    public ApiResponse<Void> tmplGrid(@RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        TmplPage p = tmpls.grid(status, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(TMPL + "/columns")
    @ProgAuth(prog = TP, action = Action.SEARCH)
    public ApiResponse<Void> tmplColumns(Locale locale) {
        List<Map<String, Object>> cols = tmpls.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(TMPL + "/export")
    @ProgAuth(prog = TP, action = Action.EXCEL)
    public void tmplExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, TP, tmpls.gridColumns(), q -> {
            var p = tmpls.grid(q.str("status"), q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(TMPL + "/{id}")
    @ProgAuth(prog = TP, action = Action.SEARCH)
    public ApiResponse<TmplDetail> tmplGet(@PathVariable long id) {
        return ApiResponse.ok(tmpls.get(id));
    }

    @PostMapping(TMPL)
    @ProgAuth(prog = TP, action = Action.SAVE)
    public ApiResponse<Long> tmplCreate(@RequestBody TmplForm form) {
        return ApiResponse.ok(tmpls.create(form));
    }

    @PutMapping(TMPL + "/{id}")
    @ProgAuth(prog = TP, action = Action.SAVE)
    public ApiResponse<String> tmplUpdate(@PathVariable long id, @RequestBody TmplForm form) {
        tmpls.update(id, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(TMPL + "/{id}/release")
    @ProgAuth(prog = TP, action = Action.APPROVE)
    public ApiResponse<String> tmplRelease(@PathVariable long id, @RequestBody ActionForm form) {
        tmpls.release(id, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(TMPL + "/{id}/obsolete")
    @ProgAuth(prog = TP, action = Action.APPROVE)
    public ApiResponse<String> tmplObsolete(@PathVariable long id, @RequestBody ActionForm form) {
        tmpls.obsolete(id, form);
        return ApiResponse.ok("OK");
    }

    /** 새 리비전(마지막 리비전 복사) → 새 id */
    @PostMapping(TMPL + "/{id}/new-rev")
    @ProgAuth(prog = TP, action = Action.SAVE)
    public ApiResponse<Long> tmplNewRev(@PathVariable long id) {
        return ApiResponse.ok(tmpls.newRevision(id));
    }

    // ------------------------------------------------------------------ 평가

    @GetMapping(EVAL + "/suppliers")
    @ProgAuth(prog = EP, action = Action.SEARCH)
    public ApiResponse<List<SupplierOption>> suppliers() {
        return ApiResponse.ok(evals.suppliers());
    }

    /** 평가에 쓸 수 있는 확정 템플릿 */
    @GetMapping(EVAL + "/tmpls")
    @ProgAuth(prog = EP, action = Action.SEARCH)
    public ApiResponse<List<TmplRow>> released() {
        return ApiResponse.ok(evals.releasedTemplates());
    }

    @GetMapping(EVAL)
    @ProgAuth(prog = EP, action = Action.SEARCH)
    public ApiResponse<List<EvalRow>> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(evals.list(from, to, status, keyword));
    }

    /** 평가 grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(EVAL + "/grid")
    @ProgAuth(prog = EP, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        EvalPage p = evals.grid(from, to, status, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(EVAL + "/columns")
    @ProgAuth(prog = EP, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = evals.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(EVAL + "/export")
    @ProgAuth(prog = EP, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, EP, evals.gridColumns(), q -> {
            var p = evals.grid(q.str("from"), q.str("to"), q.str("status"), q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(EVAL + "/{id}")
    @ProgAuth(prog = EP, action = Action.SEARCH)
    public ApiResponse<EvalDetail> get(@PathVariable long id) {
        return ApiResponse.ok(evals.get(id));
    }

    @PostMapping(EVAL)
    @ProgAuth(prog = EP, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody EvalForm form) {
        return ApiResponse.ok(evals.create(form));
    }

    @PutMapping(EVAL + "/{id}/scores")
    @ProgAuth(prog = EP, action = Action.SAVE)
    public ApiResponse<String> scores(@PathVariable long id, @RequestBody ScoresForm form) {
        evals.saveScores(id, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(EVAL + "/{id}/recalc")
    @ProgAuth(prog = EP, action = Action.SAVE)
    public ApiResponse<String> recalc(@PathVariable long id, @RequestBody ActionForm form) {
        evals.recalc(id, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(EVAL + "/{id}/confirm")
    @ProgAuth(prog = EP, action = Action.APPROVE)
    public ApiResponse<String> confirm(@PathVariable long id, @RequestBody ActionForm form) {
        evals.confirm(id, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(EVAL + "/{id}/cancel")
    @ProgAuth(prog = EP, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long id, @RequestBody ActionForm form) {
        evals.cancel(id, form);
        return ApiResponse.ok("OK");
    }
}
