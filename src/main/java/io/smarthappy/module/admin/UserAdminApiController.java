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

import io.smarthappy.module.admin.AdminDtos.GroupChoice;
import io.smarthappy.module.admin.AdminDtos.RowVerRequest;
import io.smarthappy.module.admin.AdminDtos.SupplierChoice;
import io.smarthappy.module.admin.AdminDtos.TempPassword;
import io.smarthappy.module.admin.AdminDtos.UserDetail;
import io.smarthappy.module.admin.AdminDtos.UserForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;

/** 사용자 관리 API (sys-user, docs/36) — 비밀번호 초기화 FN1, 잠금·해제 FN2 */
@RestController
@RequiredArgsConstructor
public class UserAdminApiController {

    private static final String BASE = "/api/admin/users";

    private final UserAdminService service;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(BASE)
    @ProgAuth(prog = UserAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String authGrpCd, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) String lockState, @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String userType, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        UserAdminService.Page p = service.list(keyword, authGrpCd, useYn, lockState, deptId, userType, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = UserAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(BASE + "/export")
    @ProgAuth(prog = UserAdminService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, UserAdminService.PROG, service.columns(), q -> {
            var p = service.list(q.str("keyword"), q.str("authGrpCd"), q.str("useYn"), q.str("lockState"), q.longValue("deptId"),
                    q.str("userType"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(BASE + "/groups")
    @ProgAuth(prog = UserAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> groups() {
        List<GroupChoice> list = service.groupChoices();
        return ApiResponse.list(list, list.size());
    }

    /** 협력사 사용자 소속 선택지 (docs/42 5장) — 사용자 추가 권한(SAVE)이 쓰는 고르기라 SAVE */
    @GetMapping(BASE + "/suppliers")
    @ProgAuth(prog = UserAdminService.PROG, action = Action.SAVE)
    public ApiResponse<Void> suppliers(@RequestParam(required = false) String keyword) {
        List<SupplierChoice> list = service.supplierChoices(keyword);
        return ApiResponse.list(list, list.size());
    }

    @GetMapping(BASE + "/{userId}")
    @ProgAuth(prog = UserAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<UserDetail> detail(@PathVariable long userId) {
        return ApiResponse.ok(service.detail(userId));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = UserAdminService.PROG, action = Action.SAVE)
    public ApiResponse<TempPassword> create(@RequestBody UserForm form, HttpServletResponse response) {
        noStore(response);
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(BASE + "/{userId}")
    @ProgAuth(prog = UserAdminService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long userId, @RequestBody UserForm form) {
        service.update(userId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/{userId}/reset-password")
    @ProgAuth(prog = UserAdminService.PROG, action = Action.FN1)
    public ApiResponse<TempPassword> resetPassword(@PathVariable long userId, @RequestBody RowVerRequest req,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.ok(service.resetPassword(userId, req == null ? null : req.rowVer()));
    }

    /** MFA 초기화 (docs/36 Q25) — 비밀번호 초기화와 같은 FN1 */
    @PostMapping(BASE + "/{userId}/reset-mfa")
    @ProgAuth(prog = UserAdminService.PROG, action = Action.FN1)
    public ApiResponse<String> resetMfa(@PathVariable long userId, @RequestBody RowVerRequest req) {
        service.resetMfa(userId, req == null ? null : req.rowVer());
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/{userId}/lock")
    @ProgAuth(prog = UserAdminService.PROG, action = Action.FN2)
    public ApiResponse<String> lock(@PathVariable long userId, @RequestBody RowVerRequest req) {
        service.lock(userId, req == null ? null : req.rowVer());
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/{userId}/unlock")
    @ProgAuth(prog = UserAdminService.PROG, action = Action.FN2)
    public ApiResponse<String> unlock(@PathVariable long userId, @RequestBody RowVerRequest req) {
        service.unlock(userId, req == null ? null : req.rowVer());
        return ApiResponse.ok("OK");
    }

    /** 임시 비밀번호가 실린 응답은 어디에도 캐시하지 않는다 */
    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }
}
