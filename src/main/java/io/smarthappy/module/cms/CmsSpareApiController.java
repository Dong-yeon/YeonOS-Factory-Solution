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

import io.smarthappy.module.cms.CmsDtos.IdOption;
import io.smarthappy.module.cms.CmsDtos.TargetRow;
import io.smarthappy.module.cms.CmsSpareDtos.ItemRef;
import io.smarthappy.module.cms.CmsSpareDtos.PartForm;
import io.smarthappy.module.cms.CmsSpareDtos.PartOption;
import io.smarthappy.module.cms.CmsSpareDtos.PartRow;
import io.smarthappy.module.cms.CmsSpareDtos.ReturnForm;
import io.smarthappy.module.cms.CmsSpareDtos.SpareExport;
import io.smarthappy.module.cms.CmsSpareDtos.SpareForm;
import io.smarthappy.module.cms.CmsSpareDtos.SpareRow;
import io.smarthappy.module.cms.CmsSpareDtos.UsageExport;
import io.smarthappy.module.cms.CmsSpareDtos.UsageRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** CMS 1부 C4 API — 설비 예비품(cms-spare)·작업지시 사용 예비품(cms-mwo) (docs/33 4.5) */
@RestController
@RequiredArgsConstructor
public class CmsSpareApiController {

    private static final String SPARE = "/api/cms/spares";
    private static final String MWO = "/api/cms/mwos";

    private static final int PICK_LIMIT = 50;

    private final MessageSource messages;
    private final SpareService spares;
    private final CmsMapper mapper;
    private final CmsSpareMapper spareMapper;

    private static String pickKeyword(String keyword) {
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return keyword == null || keyword.isBlank() ? null : CmsSupport.like(keyword.strip().toUpperCase(Locale.ROOT));
    }

    /** 설비 찾기(사용 중) */
    @GetMapping(SPARE + "/targets")
    @ProgAuth(prog = SpareService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<TargetRow>> targets(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(mapper.findTargets(pickKeyword(keyword), null, false, PICK_LIMIT));
    }

    /** 품목 찾기(사용 중) — 예비품 연결용 */
    @GetMapping(SPARE + "/items")
    @ProgAuth(prog = SpareService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<ItemRef>> items(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(spareMapper.findItems(pickKeyword(keyword), PICK_LIMIT));
    }

    @GetMapping(SPARE)
    @ProgAuth(prog = SpareService.PROG, action = Action.SEARCH)
    public ApiResponse<List<SpareRow>> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) String targetKind) {
        return ApiResponse.ok(spares.list(keyword, useYn, targetKind));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(SPARE + "/grid")
    @ProgAuth(prog = SpareService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) String targetKind,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        var p = spares.grid(keyword, useYn, targetKind, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(SPARE + "/columns")
    @ProgAuth(prog = SpareService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> gridColumns(Locale locale) {
        List<Map<String, Object>> cols = spares.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(SPARE + "/classes")
    @ProgAuth(prog = SpareService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<IdOption>> classes() {
        return ApiResponse.ok(mapper.findEquipClasses());
    }

    /** 사용 이력: 기간(필수) + 설비·클래스·품목 코드 */
    @GetMapping(SPARE + "/usage")
    @ProgAuth(prog = SpareService.PROG, action = Action.SEARCH)
    public ApiResponse<List<UsageRow>> usage(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String equipCd, @RequestParam(required = false) String classCd,
            @RequestParam(required = false) String itemCd) {
        return ApiResponse.ok(spares.usage(from, to, equipCd, classCd, itemCd));
    }

    /** 엑셀(C15) — 연결 표, 목록과 같은 조건 */
    @PostMapping(SPARE + "/export")
    @ProgAuth(prog = SpareService.PROG, action = Action.EXCEL)
    public void exportSpares(@RequestBody(required = false) SpareExport req, HttpServletResponse response) throws IOException {
        spares.exportSpares(req, response);
    }

    /** 엑셀(C15) — 사용 이력, 사용 이력 조회와 같은 조건 */
    @PostMapping(SPARE + "/usage/export")
    @ProgAuth(prog = SpareService.PROG, action = Action.EXCEL)
    public void exportUsage(@RequestBody(required = false) UsageExport req, HttpServletResponse response) throws IOException {
        spares.exportUsage(req, response);
    }

    @GetMapping(SPARE + "/{spareId}")
    @ProgAuth(prog = SpareService.PROG, action = Action.SEARCH)
    public ApiResponse<SpareRow> get(@PathVariable long spareId) {
        return ApiResponse.ok(spares.get(spareId));
    }

    @PostMapping(SPARE)
    @ProgAuth(prog = SpareService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody SpareForm form) {
        return ApiResponse.ok(spares.create(form));
    }

    @PutMapping(SPARE + "/{spareId}")
    @ProgAuth(prog = SpareService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long spareId, @RequestBody SpareForm form) {
        spares.update(spareId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 작업지시 사용 예비품

    @GetMapping(MWO + "/{mwoId}/parts")
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PartRow>> parts(@PathVariable long mwoId) {
        return ApiResponse.ok(spares.parts(mwoId));
    }

    @GetMapping(MWO + "/{mwoId}/part-options")
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PartOption>> partOptions(@PathVariable long mwoId) {
        return ApiResponse.ok(spares.partOptions(mwoId));
    }

    @PostMapping(MWO + "/{mwoId}/parts")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addPart(@PathVariable long mwoId, @RequestBody PartForm form) {
        return ApiResponse.ok(spares.addPart(mwoId, form));
    }

    @PostMapping(MWO + "/{mwoId}/parts/{partId}/delete")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<String> deletePart(@PathVariable long mwoId, @PathVariable long partId) {
        spares.deletePart(mwoId, partId);
        return ApiResponse.ok("OK");
    }

    /** 반납(완료 뒤) — WMS 재고 + 행. data = false 면 재고 연동이 안 된 줄(할 일 없음) */
    @PostMapping(MWO + "/{mwoId}/parts/{partId}/return")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<Boolean> returnPart(@PathVariable long mwoId, @PathVariable long partId, @RequestBody ReturnForm form) {
        return ApiResponse.ok(spares.returnPart(mwoId, partId, form));
    }
}
