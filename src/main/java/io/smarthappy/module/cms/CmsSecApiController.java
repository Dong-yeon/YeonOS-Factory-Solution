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

import io.smarthappy.module.cms.CmsDtos.Option;
import io.smarthappy.module.cms.CmsSecDtos.SecExport;
import io.smarthappy.module.cms.CmsSecDtos.SecForm;
import io.smarthappy.module.cms.CmsSecDtos.SecPage;
import io.smarthappy.module.cms.CmsSecDtos.SecRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** CMS 1부 C5 API — 설비 보안 대장 (docs/33 4.6) */
@RestController
@RequiredArgsConstructor
public class CmsSecApiController {

    private static final String SEC = "/api/cms/equip-secs";

    private final EquipSecService secs;
    private final CmsMapper mapper;
    private final MessageSource messages;

    @GetMapping(SEC)
    @ProgAuth(prog = EquipSecService.PROG, action = Action.SEARCH)
    public ApiResponse<List<SecRow>> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String warn) {
        return ApiResponse.ok(secs.list(keyword, plantCd, warn));
    }

    /** grid(docs/40 10.2) — 기존 목록 API 는 그대로, 같은 조건 + 페이지·정렬·건수 */
    @GetMapping(SEC + "/grid")
    @ProgAuth(prog = EquipSecService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String warn, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        SecPage p = secs.grid(keyword, plantCd, warn, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(SEC + "/columns")
    @ProgAuth(prog = EquipSecService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = secs.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(SEC + "/plants")
    @ProgAuth(prog = EquipSecService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<Option>> plants() {
        return ApiResponse.ok(mapper.findPlants());
    }

    @GetMapping(SEC + "/{equipId}")
    @ProgAuth(prog = EquipSecService.PROG, action = Action.SEARCH)
    public ApiResponse<SecRow> get(@PathVariable long equipId) {
        return ApiResponse.ok(secs.get(equipId));
    }

    @PutMapping(SEC + "/{equipId}")
    @ProgAuth(prog = EquipSecService.PROG, action = Action.SAVE)
    public ApiResponse<String> save(@PathVariable long equipId, @RequestBody SecForm form) {
        secs.save(equipId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(SEC + "/export")
    @ProgAuth(prog = EquipSecService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) SecExport req, HttpServletResponse response) throws IOException {
        secs.export(req, response);
    }
}
