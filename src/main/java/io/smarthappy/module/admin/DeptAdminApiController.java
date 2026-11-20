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
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.admin.SettingsDtos.DeptChoice;
import io.smarthappy.module.admin.SettingsDtos.DeptForm;
import io.smarthappy.module.admin.SettingsDtos.DeptNode;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserOption;
import lombok.RequiredArgsConstructor;

/** 부서 관리 API (sys-dept, docs/36 9.1) + 다른 화면의 부서 선택지 */
@RestController
@RequiredArgsConstructor
public class DeptAdminApiController {

    private static final String BASE = "/api/admin/depts";

    private final DeptAdminService service;
    private final MessageSource messages;

    @GetMapping(BASE)
    @ProgAuth(prog = DeptAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> tree() {
        List<DeptNode> list = service.tree();
        return ApiResponse.list(list, list.size());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = DeptAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 부서장 고르기 — 사용 중 회사 사용자(이름·아이디만) */
    @GetMapping(BASE + "/head-candidates")
    @ProgAuth(prog = DeptAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<List<UserOption>> headCandidates() {
        return ApiResponse.ok(service.headCandidates());
    }

    @PostMapping(BASE)
    @ProgAuth(prog = DeptAdminService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody DeptForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(BASE + "/{deptId}")
    @ProgAuth(prog = DeptAdminService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long deptId, @RequestBody DeptForm form) {
        service.update(deptId, form);
        return ApiResponse.ok("OK");
    }

    /** 부서 선택지 — 사용 중 부서만(코드·이름·경로) */
    @GetMapping("/api/platform/depts")
    @ProgAuthExempt(reason = "부서 선택지 — 자기 회사 부서만(RLS), 공통코드처럼 화면 권한과 무관한 참조 데이터")
    public ApiResponse<Void> choices() {
        List<DeptChoice> list = service.choices();
        return ApiResponse.list(list, list.size());
    }
}
