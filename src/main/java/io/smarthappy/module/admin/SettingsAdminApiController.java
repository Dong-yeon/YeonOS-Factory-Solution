package io.smarthappy.module.admin;

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

import io.smarthappy.module.admin.SettingsDtos.CodeForm;
import io.smarthappy.module.admin.SettingsDtos.CodePage;
import io.smarthappy.module.admin.SettingsDtos.CodeView;
import io.smarthappy.module.admin.SettingsDtos.CompForm;
import io.smarthappy.module.admin.SettingsDtos.CompView;
import io.smarthappy.module.admin.SettingsDtos.PolicyPage;
import io.smarthappy.module.admin.SettingsDtos.PolicySave;
import io.smarthappy.module.admin.SettingsDtos.PolicyView;
import io.smarthappy.module.admin.SettingsDtos.Preview;
import io.smarthappy.module.admin.SettingsDtos.PreviewRequest;
import io.smarthappy.module.admin.SettingsDtos.RuleForm;
import io.smarthappy.module.admin.SettingsDtos.RulePage;
import io.smarthappy.module.admin.SettingsDtos.RuleView;
import io.smarthappy.module.admin.SettingsDtos.ShownForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 시스템관리 2단계 API (docs/36 9.2~9.5) — 정책 · 회사 공통코드 · 채번 규칙 · 회사 정보 */
@RestController
@RequiredArgsConstructor
public class SettingsAdminApiController {

    private final SettingsAdminService service;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    // ---------- 정책 (sys-policy) ----------

    @GetMapping("/api/admin/policies")
    @ProgAuth(prog = SettingsAdminService.PROG_POLICY, action = Action.SEARCH)
    public ApiResponse<Void> policies(Locale locale) {
        List<PolicyView> list = service.policies(locale);
        return ApiResponse.list(list, list.size());
    }

    /** grid(UI 단계 3 ①, docs/36 17.2) — 분류·검색어·바꾼 것만 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping("/api/admin/policies/grid")
    @ProgAuth(prog = SettingsAdminService.PROG_POLICY, action = Action.SEARCH)
    public ApiResponse<Void> policyGrid(@RequestParam(required = false) String category, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String changedOnly, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request,
            Locale locale) {
        String[] sort = request.getParameterValues("sort");   // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로(결정 #47)
        PolicyPage p = service.policyGrid(category, keyword, changedOnly, page, size, sort == null ? null : List.of(sort), total, locale);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping("/api/admin/policies/columns")
    @ProgAuth(prog = SettingsAdminService.PROG_POLICY, action = Action.SEARCH)
    public ApiResponse<Void> policyColumns(Locale locale) {
        List<Map<String, Object>> cols = service.policyGridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping("/api/admin/policies/export")
    @ProgAuth(prog = SettingsAdminService.PROG_POLICY, action = Action.EXCEL)
    public void policyExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response, Locale locale) throws IOException {
        gridExcel.export(response, body, SettingsAdminService.PROG_POLICY, service.policyGridColumns(), q -> {
            var p = service.policyGrid(q.str("category"), q.str("keyword"), q.str("changedOnly"), q.page(), q.size(), q.sort(), q.total(), locale);
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping("/api/admin/policies/plants")
    @ProgAuth(prog = SettingsAdminService.PROG_POLICY, action = Action.SEARCH)
    public ApiResponse<Void> plants() {
        List<String> list = service.plants();
        return ApiResponse.list(list, list.size());
    }

    @PutMapping("/api/admin/policies/{policyKey}")
    @ProgAuth(prog = SettingsAdminService.PROG_POLICY, action = Action.SAVE)
    public ApiResponse<String> savePolicy(@PathVariable String policyKey, @RequestBody PolicySave req) {
        service.savePolicy(policyKey, req);
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/admin/policies/{policyKey}/reset")
    @ProgAuth(prog = SettingsAdminService.PROG_POLICY, action = Action.SAVE)
    public ApiResponse<String> resetPolicy(@PathVariable String policyKey, @RequestBody PolicySave req) {
        service.resetPolicy(policyKey, req == null ? null : req.plantCd(), req == null ? null : req.rowVer());
        return ApiResponse.ok("OK");
    }

    // ---------- 회사 공통코드 (sys-code) ----------

    @GetMapping("/api/admin/codes")
    @ProgAuth(prog = SettingsAdminService.PROG_CODE, action = Action.SEARCH)
    public ApiResponse<Void> groups(Locale locale) {
        List<SettingsAdminService.GroupView> list = service.codeGroups(locale);
        return ApiResponse.list(list, list.size());
    }

    /** grid(UI 단계 3 ①, docs/36 17.1) — 그룹은 조회 조건(grpCd), 목록과 같은 행 + page·size·sort·total. 그룹 코드는 대문자라 /grid 와 겹치지 않는다 */
    @GetMapping("/api/admin/codes/grid")
    @ProgAuth(prog = SettingsAdminService.PROG_CODE, action = Action.SEARCH)
    public ApiResponse<Void> codeGrid(@RequestParam(required = false) String grpCd, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request,
            Locale locale) {
        String[] sort = request.getParameterValues("sort");   // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로(결정 #47)
        CodePage p = service.codeGrid(grpCd, page, size, sort == null ? null : List.of(sort), total, locale);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping("/api/admin/codes/columns")
    @ProgAuth(prog = SettingsAdminService.PROG_CODE, action = Action.SEARCH)
    public ApiResponse<Void> codeColumns(Locale locale) {
        List<Map<String, Object>> cols = service.codeGridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping("/api/admin/codes/export")
    @ProgAuth(prog = SettingsAdminService.PROG_CODE, action = Action.EXCEL)
    public void codeExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response, Locale locale) throws IOException {
        gridExcel.export(response, body, SettingsAdminService.PROG_CODE, service.codeGridColumns(), q -> {
            var p = service.codeGrid(q.str("grpCd"), q.page(), q.size(), q.sort(), q.total(), locale);
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping("/api/admin/codes/{grpCd}")
    @ProgAuth(prog = SettingsAdminService.PROG_CODE, action = Action.SEARCH)
    public ApiResponse<Void> codes(@PathVariable String grpCd, Locale locale) {
        List<CodeView> list = service.codes(grpCd, locale);
        return ApiResponse.list(list, list.size());
    }

    @PostMapping("/api/admin/codes/{grpCd}")
    @ProgAuth(prog = SettingsAdminService.PROG_CODE, action = Action.SAVE)
    public ApiResponse<String> createCode(@PathVariable String grpCd, @RequestBody CodeForm form) {
        service.createCode(grpCd, form);
        return ApiResponse.ok("OK");
    }

    @PutMapping("/api/admin/codes/{grpCd}/{code}")
    @ProgAuth(prog = SettingsAdminService.PROG_CODE, action = Action.SAVE)
    public ApiResponse<String> updateCode(@PathVariable String grpCd, @PathVariable String code,
            @RequestBody CodeForm form) {
        service.updateCode(grpCd, code, form);
        return ApiResponse.ok("OK");
    }

    /** 시스템 코드 숨기기·되살리기(docs/68 Q4) — 이 회사 선택 목록에서만 */
    @PutMapping("/api/admin/codes/{grpCd}/{code}/shown")
    @ProgAuth(prog = SettingsAdminService.PROG_CODE, action = Action.SAVE)
    public ApiResponse<String> setSystemCodeShown(@PathVariable String grpCd, @PathVariable String code,
            @RequestBody ShownForm form) {
        service.setSystemCodeShown(grpCd, code, form);
        return ApiResponse.ok("OK");
    }

    // ---------- 채번 규칙 (sys-numbering) ----------

    @GetMapping("/api/admin/numbering")
    @ProgAuth(prog = SettingsAdminService.PROG_NUMBERING, action = Action.SEARCH)
    public ApiResponse<Void> rules() {
        List<RuleView> list = service.rules();
        return ApiResponse.list(list, list.size());
    }

    /** grid(UI 단계 3 ①) — 검색어 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping("/api/admin/numbering/grid")
    @ProgAuth(prog = SettingsAdminService.PROG_NUMBERING, action = Action.SEARCH)
    public ApiResponse<Void> ruleGrid(@RequestParam(required = false) String keyword, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로(결정 #47)
        RulePage p = service.ruleGrid(keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping("/api/admin/numbering/columns")
    @ProgAuth(prog = SettingsAdminService.PROG_NUMBERING, action = Action.SEARCH)
    public ApiResponse<Void> ruleColumns(Locale locale) {
        List<Map<String, Object>> cols = service.ruleGridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping("/api/admin/numbering/export")
    @ProgAuth(prog = SettingsAdminService.PROG_NUMBERING, action = Action.EXCEL)
    public void ruleExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, SettingsAdminService.PROG_NUMBERING, service.ruleGridColumns(), q -> {
            var p = service.ruleGrid(q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    /** 미리보기는 저장 전 계산 — 조회 권한으로(번호를 쓰지 않음) */
    @PostMapping("/api/admin/numbering/preview")
    @ProgAuth(prog = SettingsAdminService.PROG_NUMBERING, action = Action.SEARCH)
    public ApiResponse<Preview> preview(@RequestBody PreviewRequest req) {
        return ApiResponse.ok(service.preview(req));
    }

    @PostMapping("/api/admin/numbering")
    @ProgAuth(prog = SettingsAdminService.PROG_NUMBERING, action = Action.SAVE)
    public ApiResponse<String> createRule(@RequestBody RuleForm form) {
        service.createRule(form);
        return ApiResponse.ok("OK");
    }

    @PutMapping("/api/admin/numbering/{ruleCd}")
    @ProgAuth(prog = SettingsAdminService.PROG_NUMBERING, action = Action.SAVE)
    public ApiResponse<String> updateRule(@PathVariable String ruleCd, @RequestBody RuleForm form) {
        service.updateRule(ruleCd, form);
        return ApiResponse.ok("OK");
    }

    // ---------- 회사 정보 (sys-comp) ----------

    @GetMapping("/api/admin/company")
    @ProgAuth(prog = SettingsAdminService.PROG_COMP, action = Action.SEARCH)
    public ApiResponse<CompView> comp() {
        return ApiResponse.ok(service.comp());
    }

    @PutMapping("/api/admin/company")
    @ProgAuth(prog = SettingsAdminService.PROG_COMP, action = Action.SAVE)
    public ApiResponse<String> updateComp(@RequestBody CompForm form) {
        service.updateComp(form);
        return ApiResponse.ok("OK");
    }

    /** 시간대 선택지 (IANA, 정렬) */
    @GetMapping("/api/admin/company/time-zones")
    @ProgAuth(prog = SettingsAdminService.PROG_COMP, action = Action.SEARCH)
    public ApiResponse<Void> timeZones(@RequestParam(required = false) String q) {
        List<String> list = java.time.ZoneId.getAvailableZoneIds().stream().filter(z -> z.contains("/"))
                .filter(z -> q == null || z.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))).sorted().toList();
        return ApiResponse.list(list, list.size());
    }
}
