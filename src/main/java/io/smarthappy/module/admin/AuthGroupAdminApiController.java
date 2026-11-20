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

import io.smarthappy.module.admin.AdminDtos.GroupDetail;
import io.smarthappy.module.admin.AdminDtos.GroupForm;
import io.smarthappy.module.admin.AdminDtos.GroupPage;
import io.smarthappy.module.admin.AdminDtos.GroupRow;
import io.smarthappy.module.admin.AdminDtos.PermSave;
import io.smarthappy.module.admin.AdminDtos.ProgChoice;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 권한그룹 관리 API (sys-auth-group, docs/36) */
@RestController
@RequiredArgsConstructor
public class AuthGroupAdminApiController {

    private static final String BASE = "/api/admin/auth-groups";

    private final AuthGroupAdminService service;
    private final MenuAdminService menus;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(BASE)
    @ProgAuth(prog = AuthGroupAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list() {
        List<GroupRow> list = service.list();
        return ApiResponse.list(list, list.size());
    }

    /** grid(UI 단계 3 ①, docs/36 17.1) — 검색어 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(BASE + "/grid")
    @ProgAuth(prog = AuthGroupAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로(결정 #47)
        GroupPage p = service.grid(keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = AuthGroupAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(BASE + "/export")
    @ProgAuth(prog = AuthGroupAdminService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, AuthGroupAdminService.PROG, service.gridColumns(), q -> {
            var p = service.grid(q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    /** 홈 화면 선택지 (사용 중 화면) */
    @GetMapping(BASE + "/progs")
    @ProgAuth(prog = AuthGroupAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> progs(Locale locale) {
        List<ProgChoice> list = menus.progs(locale);
        return ApiResponse.list(list, list.size());
    }

    /** 내 범위 — 관리자 그룹이면 관리자 표시 칸을 연다(화면 편의, 판정은 저장 때 서버) */
    @GetMapping(BASE + "/my-scope")
    @ProgAuth(prog = AuthGroupAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<java.util.Map<String, Boolean>> myScope() {
        return ApiResponse.ok(java.util.Map.of("admin", service.iAmAdmin()));
    }

    @GetMapping(BASE + "/{authGrpCd}")
    @ProgAuth(prog = AuthGroupAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<GroupDetail> detail(@PathVariable String authGrpCd, Locale locale) {
        return ApiResponse.ok(service.detail(authGrpCd, locale));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = AuthGroupAdminService.PROG, action = Action.SAVE)
    public ApiResponse<String> create(@RequestBody GroupForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(BASE + "/{authGrpCd}")
    @ProgAuth(prog = AuthGroupAdminService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable String authGrpCd, @RequestBody GroupForm form) {
        service.update(authGrpCd, form);
        return ApiResponse.ok("OK");
    }

    @PutMapping(BASE + "/{authGrpCd}/perms")
    @ProgAuth(prog = AuthGroupAdminService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> savePerms(@PathVariable String authGrpCd, @RequestParam Integer rowVer,
            @RequestBody PermSave save) {
        return ApiResponse.ok(service.savePerms(authGrpCd, rowVer, save));
    }
}
