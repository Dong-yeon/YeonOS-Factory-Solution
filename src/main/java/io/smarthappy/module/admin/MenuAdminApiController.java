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

import io.smarthappy.module.admin.AdminDtos.MenuForm;
import io.smarthappy.module.admin.AdminDtos.MenuNode;
import io.smarthappy.module.admin.AdminDtos.ProgChoice;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 메뉴 관리 API (sys-menu, docs/36) */
@RestController
@RequiredArgsConstructor
public class MenuAdminApiController {

    private static final String BASE = "/api/admin/menus";

    private final MenuAdminService service;
    private final MessageSource messages;

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = MenuAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = MenuAdminService.COLUMNS.stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(BASE)
    @ProgAuth(prog = MenuAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> tree(Locale locale) {
        List<MenuNode> list = service.tree(locale);
        return ApiResponse.list(list, list.size());
    }

    @GetMapping(BASE + "/progs")
    @ProgAuth(prog = MenuAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> progs(Locale locale) {
        List<ProgChoice> list = service.progs(locale);
        return ApiResponse.list(list, list.size());
    }

    @PostMapping(BASE)
    @ProgAuth(prog = MenuAdminService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody MenuForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(BASE + "/{menuId}")
    @ProgAuth(prog = MenuAdminService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long menuId, @RequestBody MenuForm form) {
        service.update(menuId, form);
        return ApiResponse.ok("OK");
    }
}
