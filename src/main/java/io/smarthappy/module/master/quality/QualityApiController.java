package io.smarthappy.module.master.quality;

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

import io.smarthappy.module.master.quality.QualityDtos.AliasForm;
import io.smarthappy.module.master.quality.QualityDtos.AliasRow;
import io.smarthappy.module.master.quality.QualityDtos.CharExportRequest;
import io.smarthappy.module.master.quality.QualityDtos.CharForm;
import io.smarthappy.module.master.quality.QualityDtos.CharRow;
import io.smarthappy.module.master.quality.QualityDtos.DefectForm;
import io.smarthappy.module.master.quality.QualityDtos.DefectRow;
import io.smarthappy.module.master.quality.QualityDtos.ItemLookup;
import io.smarthappy.module.master.quality.QualityDtos.ProcLookup;
import io.smarthappy.module.master.quality.QualityDtos.TermRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import io.smarthappy.platform.excel.GridExcel;

/** 특성 마스터·불량 유형·용어 사전 API (P1 6단계). 삭제 없음 = 사용 중지(저장) */
@RestController
@RequiredArgsConstructor
public class QualityApiController {

    private static final String CHAR = "/api/master/chars";
    private static final String DEFECT = "/api/master/defects";
    private static final String TERM = "/api/master/terms";

    private final CharService chars;
    private final DefectTermService defects;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    // ------------------------------------------------------------------ 특성

    @GetMapping(CHAR)
    @ProgAuth(prog = CharService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String procCd,
            @RequestParam(required = false) String charType, @RequestParam(required = false) String specialCls,
            @RequestParam(required = false) Boolean withUnused, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        CharService.Page p = chars.list(itemCd, keyword, procCd, charType, specialCls, withUnused, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(CHAR + "/columns")
    @ProgAuth(prog = CharService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = chars.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(CHAR + "/item")
    @ProgAuth(prog = CharService.PROG, action = Action.SEARCH)
    public ApiResponse<ItemLookup> item(@RequestParam String itemCd) {
        return ApiResponse.ok(chars.item(itemCd));
    }

    @GetMapping(CHAR + "/procs")
    @ProgAuth(prog = CharService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ProcLookup>> procs() {
        return ApiResponse.ok(chars.procs());
    }

    @GetMapping(CHAR + "/{charId}")
    @ProgAuth(prog = CharService.PROG, action = Action.SEARCH)
    public ApiResponse<CharRow> detail(@PathVariable long charId) {
        return ApiResponse.ok(chars.detail(charId));
    }

    @PostMapping(CHAR)
    @ProgAuth(prog = CharService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody CharForm form) {
        return ApiResponse.ok(chars.create(form));
    }

    @PutMapping(CHAR + "/{charId}")
    @ProgAuth(prog = CharService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long charId, @RequestBody CharForm form) {
        chars.update(charId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(CHAR + "/export")
    @ProgAuth(prog = CharService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) CharExportRequest req, HttpServletResponse response)
            throws IOException {
        chars.export(req, response);
    }

    // ------------------------------------------------------------------ 불량 유형

    @GetMapping(DEFECT)
    @ProgAuth(prog = DefectTermService.PROG_DEFECT, action = Action.SEARCH)
    public ApiResponse<List<DefectRow>> defects(@RequestParam(required = false) String useYn) {
        return ApiResponse.ok(defects.defects(useYn));
    }

    /** 그리드(UI 단계 3 ①) — 기존 목록 API 는 그대로, 같은 조건 + 빠른 찾기·페이지·정렬·건수 */
    @GetMapping(DEFECT + "/grid")
    @ProgAuth(prog = DefectTermService.PROG_DEFECT, action = Action.SEARCH)
    public ApiResponse<Void> defectGrid(@RequestParam(required = false) String useYn,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        DefectTermService.Page<DefectRow> p = defects.defectGrid(useYn, keyword, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(DEFECT + "/columns")
    @ProgAuth(prog = DefectTermService.PROG_DEFECT, action = Action.SEARCH)
    public ApiResponse<Void> defectColumns(Locale locale) {
        List<Map<String, Object>> cols = defects.defectColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(DEFECT + "/export")
    @ProgAuth(prog = DefectTermService.PROG_DEFECT, action = Action.EXCEL)
    public void defectExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, DefectTermService.PROG_DEFECT, defects.defectColumns(), q -> {
            var p = defects.defectGrid(q.str("useYn"), q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @PostMapping(DEFECT)
    @ProgAuth(prog = DefectTermService.PROG_DEFECT, action = Action.SAVE)
    public ApiResponse<Long> createDefect(@RequestBody DefectForm form) {
        return ApiResponse.ok(defects.createDefect(form));
    }

    @PutMapping(DEFECT + "/{defectId}")
    @ProgAuth(prog = DefectTermService.PROG_DEFECT, action = Action.SAVE)
    public ApiResponse<String> updateDefect(@PathVariable long defectId, @RequestBody DefectForm form) {
        defects.updateDefect(defectId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 용어 사전

    @GetMapping(TERM)
    @ProgAuth(prog = DefectTermService.PROG_TERM, action = Action.SEARCH)
    public ApiResponse<List<TermRow>> terms() {
        return ApiResponse.ok(defects.terms());
    }

    @GetMapping(TERM + "/grid")
    @ProgAuth(prog = DefectTermService.PROG_TERM, action = Action.SEARCH)
    public ApiResponse<Void> termGrid(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        DefectTermService.Page<TermRow> p = defects.termGrid(keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(TERM + "/columns")
    @ProgAuth(prog = DefectTermService.PROG_TERM, action = Action.SEARCH)
    public ApiResponse<Void> termColumns(Locale locale) {
        List<Map<String, Object>> cols = defects.termColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(TERM + "/export")
    @ProgAuth(prog = DefectTermService.PROG_TERM, action = Action.EXCEL)
    public void termExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, DefectTermService.PROG_TERM, defects.termColumns(), q -> {
            var p = defects.termGrid(q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(TERM + "/{termCd}/aliases")
    @ProgAuth(prog = DefectTermService.PROG_TERM, action = Action.SEARCH)
    public ApiResponse<List<AliasRow>> aliases(@PathVariable String termCd) {
        return ApiResponse.ok(defects.aliases(termCd));
    }

    @PostMapping(TERM + "/aliases")
    @ProgAuth(prog = DefectTermService.PROG_TERM, action = Action.SAVE)
    public ApiResponse<Long> createAlias(@RequestBody AliasForm form) {
        return ApiResponse.ok(defects.createAlias(form));
    }

    @PutMapping(TERM + "/aliases/{aliasId}")
    @ProgAuth(prog = DefectTermService.PROG_TERM, action = Action.SAVE)
    public ApiResponse<String> updateAlias(@PathVariable long aliasId, @RequestBody AliasForm form) {
        defects.updateAlias(aliasId, form);
        return ApiResponse.ok("OK");
    }
}
