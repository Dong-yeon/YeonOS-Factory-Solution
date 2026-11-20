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

import io.smarthappy.module.cms.CmsDtos.Created;
import io.smarthappy.module.cms.CmsDtos.FailCodeExport;
import io.smarthappy.module.cms.CmsDtos.FailCodeForm;
import io.smarthappy.module.cms.CmsDtos.FailCodeRow;
import io.smarthappy.module.cms.CmsDtos.IdOption;
import io.smarthappy.module.cms.CmsDtos.InstalledAsset;
import io.smarthappy.module.cms.CmsDtos.LaborForm;
import io.smarthappy.module.cms.CmsDtos.LaborRow;
import io.smarthappy.module.cms.CmsDtos.MwoExport;
import io.smarthappy.module.cms.CmsDtos.MwoForm;
import io.smarthappy.module.cms.CmsDtos.MwoPage;
import io.smarthappy.module.cms.CmsDtos.MwoRow;
import io.smarthappy.module.cms.CmsDtos.Option;
import io.smarthappy.module.cms.CmsDtos.ProfileExport;
import io.smarthappy.module.cms.CmsDtos.ProfileForm;
import io.smarthappy.module.cms.CmsDtos.ProfileRow;
import io.smarthappy.module.cms.CmsDtos.TargetRow;
import io.smarthappy.module.cms.CmsDtos.TransitionForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** CMS 1부 C1 API — 고장 코드·설비 프로필·고장 신고·보전 작업지시 (docs/33) */
@RestController
@RequiredArgsConstructor
public class CmsApiController {

    private static final String FAIL = "/api/cms/fail-codes";
    private static final String PROFILE = "/api/cms/equip-profiles";
    private static final String BREAKDOWN = "/api/cms/breakdowns";
    private static final String MWO = "/api/cms/mwos";
    /** 대상 찾기 상한 */
    private static final int TARGET_LIMIT = 50;

    private final FailCodeService failCodes;
    private final EquipProfileService profiles;
    private final MwoService mwos;
    private final CmsMapper mapper;
    private final UserDirectory users;
    private final MessageSource messages;

    // ------------------------------------------------------------------ 고장 코드

    @GetMapping(FAIL)
    @ProgAuth(prog = FailCodeService.PROG, action = Action.SEARCH)
    public ApiResponse<List<FailCodeRow>> failCodes(@RequestParam(required = false) String codeType,
            @RequestParam(required = false) String useYn, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(failCodes.list(codeType, useYn, keyword));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(FAIL + "/grid")
    @ProgAuth(prog = FailCodeService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> failCodeGrid(@RequestParam(required = false) String codeType,
            @RequestParam(required = false) String useYn, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = failCodes.grid(codeType, useYn, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(FAIL + "/columns")
    @ProgAuth(prog = FailCodeService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> failCodeColumns(Locale locale) {
        List<Map<String, Object>> cols = failCodes.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 엑셀(C16) — grid 와 같은 조건·정렬·보이는 열 */
    @PostMapping(FAIL + "/export")
    @ProgAuth(prog = FailCodeService.PROG, action = Action.EXCEL)
    public void failCodeExport(@RequestBody(required = false) FailCodeExport req, HttpServletResponse response) throws IOException {
        failCodes.export(req, response);
    }

    @GetMapping(FAIL + "/classes")
    @ProgAuth(prog = FailCodeService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<IdOption>> failCodeClasses() {
        return ApiResponse.ok(mapper.findEquipClasses());
    }

    @PostMapping(FAIL)
    @ProgAuth(prog = FailCodeService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createFailCode(@RequestBody FailCodeForm form) {
        return ApiResponse.ok(failCodes.create(form));
    }

    @PutMapping(FAIL + "/{failId}")
    @ProgAuth(prog = FailCodeService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateFailCode(@PathVariable long failId, @RequestBody FailCodeForm form) {
        failCodes.update(failId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 설비 프로필

    @GetMapping(PROFILE + "/options")
    @ProgAuth(prog = EquipProfileService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<Options> profileOptions() {
        return ApiResponse.ok(new Options(mapper.findPlants(), users.activeUsers(), null));
    }

    @GetMapping(PROFILE)
    @ProgAuth(prog = EquipProfileService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ProfileRow>> profiles(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String criticality,
            @RequestParam(required = false) String keyOnly) {
        return ApiResponse.ok(profiles.list(plantCd, keyword, criticality, keyOnly));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(PROFILE + "/grid")
    @ProgAuth(prog = EquipProfileService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> profileGrid(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String criticality,
            @RequestParam(required = false) String keyOnly,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = profiles.grid(plantCd, keyword, criticality, keyOnly, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(PROFILE + "/columns")
    @ProgAuth(prog = EquipProfileService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> profileColumns(Locale locale) {
        List<Map<String, Object>> cols = profiles.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 엑셀(C16) — grid 와 같은 조건·정렬·보이는 열 */
    @PostMapping(PROFILE + "/export")
    @ProgAuth(prog = EquipProfileService.PROG, action = Action.EXCEL)
    public void profileExport(@RequestBody(required = false) ProfileExport req, HttpServletResponse response) throws IOException {
        profiles.export(req, response);
    }

    @GetMapping(PROFILE + "/{equipId}")
    @ProgAuth(prog = EquipProfileService.PROG, action = Action.SEARCH)
    public ApiResponse<ProfileRow> profile(@PathVariable long equipId) {
        return ApiResponse.ok(profiles.get(equipId));
    }

    @PutMapping(PROFILE + "/{equipId}")
    @ProgAuth(prog = EquipProfileService.PROG, action = Action.SAVE)
    public ApiResponse<String> saveProfile(@PathVariable long equipId, @RequestBody ProfileForm form) {
        profiles.save(equipId, form);
        return ApiResponse.ok("OK");
    }

    /** 설비 이력: 지금 설치된 금형·지그·게이지 (P1 설치 이력) */
    @GetMapping(PROFILE + "/{equipId}/installed")
    @ProgAuth(prog = EquipProfileService.PROG, action = Action.SEARCH)
    public ApiResponse<List<InstalledAsset>> installed(@PathVariable long equipId) {
        return ApiResponse.ok(profiles.installed(equipId));
    }

    /** 설비 이력: 그 설비의 작업지시(기간 필수) */
    @GetMapping(PROFILE + "/{equipId}/mwos")
    @ProgAuth(prog = EquipProfileService.PROG, action = Action.SEARCH)
    public ApiResponse<List<MwoRow>> equipMwos(@PathVariable long equipId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        profiles.get(equipId);
        return ApiResponse.ok(mwos.list(from, to, null, null, null, null, null, null, equipId));
    }

    // ------------------------------------------------------------------ 고장 신고

    @GetMapping(BREAKDOWN + "/targets")
    @ProgAuth(prog = MwoService.PROG_BREAKDOWN, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<TargetRow>> breakdownTargets(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(targets(keyword, plantCd));
    }

    /** 현상 코드 선택지(사용 중) — 고장 코드 화면 권한 없이 */
    @GetMapping(BREAKDOWN + "/symptoms")
    @ProgAuth(prog = MwoService.PROG_BREAKDOWN, action = Action.SEARCH)
    public ApiResponse<List<FailCodeRow>> symptoms() {
        return ApiResponse.ok(failCodes.list("SYMPTOM", "Y", null));
    }

    @PostMapping(BREAKDOWN)
    @ProgAuth(prog = MwoService.PROG_BREAKDOWN, action = Action.SAVE)
    public ApiResponse<Created> report(@RequestBody MwoForm form) {
        return ApiResponse.ok(mwos.report(form));
    }

    /** 내 신고(기간 필수) */
    @GetMapping(BREAKDOWN)
    @ProgAuth(prog = MwoService.PROG_BREAKDOWN, action = Action.SEARCH)
    public ApiResponse<List<MwoRow>> myReports(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(mwos.list(from, to, null, null, null, null, "REQUESTED", null, null));
    }

    @GetMapping(BREAKDOWN + "/{mwoId}")
    @ProgAuth(prog = MwoService.PROG_BREAKDOWN, action = Action.SEARCH)
    public ApiResponse<MwoRow> myReport(@PathVariable long mwoId) {
        return ApiResponse.ok(mwos.getMine(mwoId));
    }

    /** 신고자 확인 (DONE → CLOSED) */
    @PostMapping(BREAKDOWN + "/{mwoId}/confirm")
    @ProgAuth(prog = MwoService.PROG_BREAKDOWN, action = Action.SAVE)
    public ApiResponse<String> confirm(@PathVariable long mwoId, @RequestBody TransitionForm form) {
        mwos.confirmMine(mwoId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 보전 작업지시

    public record Options(List<Option> plants, List<UserOption> users, List<FailCodeRow> failCodes) {
    }

    /** 공장·사용자·고장 코드(사용 중 3종) 선택지 */
    @GetMapping(MWO + "/options")
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<Options> mwoOptions() {
        return ApiResponse.ok(new Options(mapper.findPlants(), users.activeUsers(), failCodes.list(null, "Y", null)));
    }

    @GetMapping(MWO + "/targets")
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<TargetRow>> mwoTargets(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(targets(keyword, plantCd));
    }

    @GetMapping(MWO)
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<MwoRow>> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String mwoType,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope, @RequestParam(required = false) String source) {
        return ApiResponse.ok(mwos.list(from, to, status, mwoType, plantCd, keyword, scope, source, null));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(MWO + "/grid")
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String mwoType,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope, @RequestParam(required = false) String source, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        MwoPage p = mwos.grid(from, to, status, mwoType, plantCd, keyword, scope, source, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(MWO + "/columns")
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> mwoColumns(Locale locale) {
        List<Map<String, Object>> cols = mwos.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 엑셀(C14) — 목록과 같은 조건 */
    @PostMapping(MWO + "/export")
    @ProgAuth(prog = MwoService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) MwoExport req, HttpServletResponse response) throws IOException {
        mwos.export(req, response);
    }

    @GetMapping(MWO + "/{mwoId}")
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    public ApiResponse<MwoRow> get(@PathVariable long mwoId) {
        return ApiResponse.ok(mwos.get(mwoId));
    }

    @PostMapping(MWO)
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<Created> create(@RequestBody MwoForm form) {
        return ApiResponse.ok(mwos.create(form));
    }

    @PutMapping(MWO + "/{mwoId}")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long mwoId, @RequestBody MwoForm form) {
        mwos.update(mwoId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(MWO + "/{mwoId}/accept")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<String> accept(@PathVariable long mwoId, @RequestBody TransitionForm form) {
        mwos.accept(mwoId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(MWO + "/{mwoId}/start")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<String> start(@PathVariable long mwoId, @RequestBody TransitionForm form) {
        mwos.start(mwoId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(MWO + "/{mwoId}/finish")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<String> finish(@PathVariable long mwoId, @RequestBody MwoForm form) {
        mwos.finish(mwoId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(MWO + "/{mwoId}/close")
    @ProgAuth(prog = MwoService.PROG, action = Action.APPROVE)
    public ApiResponse<String> close(@PathVariable long mwoId, @RequestBody TransitionForm form) {
        mwos.close(mwoId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(MWO + "/{mwoId}/cancel")
    @ProgAuth(prog = MwoService.PROG, action = Action.DELETE)
    public ApiResponse<String> cancel(@PathVariable long mwoId, @RequestBody TransitionForm form) {
        mwos.cancel(mwoId, form);
        return ApiResponse.ok("OK");
    }

    @GetMapping(MWO + "/{mwoId}/labor")
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    public ApiResponse<List<LaborRow>> labor(@PathVariable long mwoId) {
        return ApiResponse.ok(mwos.labor(mwoId));
    }

    @PostMapping(MWO + "/{mwoId}/labor")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addLabor(@PathVariable long mwoId, @RequestBody LaborForm form) {
        return ApiResponse.ok(mwos.addLabor(mwoId, form));
    }

    @PostMapping(MWO + "/{mwoId}/labor/{laborId}/delete")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteLabor(@PathVariable long mwoId, @PathVariable long laborId) {
        mwos.deleteLabor(mwoId, laborId);
        return ApiResponse.ok("OK");
    }

    private List<TargetRow> targets(String keyword, String plantCd) {
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = keyword == null || keyword.isBlank() ? null : CmsSupport.like(keyword.strip().toUpperCase(Locale.ROOT));
        String p = plantCd == null || plantCd.isBlank() ? null : plantCd.strip().toUpperCase(Locale.ROOT);
        return mapper.findTargets(k, p, true, TARGET_LIMIT);
    }
}
