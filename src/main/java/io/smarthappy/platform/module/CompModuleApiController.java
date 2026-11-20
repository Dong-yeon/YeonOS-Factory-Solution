package io.smarthappy.platform.module;

import java.util.List;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.module.CompModuleService.ModuleView;
import io.smarthappy.platform.module.CompModuleService.SaveForm;
import lombok.RequiredArgsConstructor;

/** 사용 모듈 화면 sys-module (docs/80 5장) — 회사 관리자가 업무 모듈을 켜고 끈다 */
@RestController
@RequiredArgsConstructor
public class CompModuleApiController {

    private final CompModuleService service;

    @GetMapping("/api/admin/modules")
    @ProgAuth(prog = CompModuleService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(Locale locale) {
        List<ModuleView> list = service.list(locale);
        return ApiResponse.list(list, list.size());
    }

    /** 켤 모듈 전부를 보낸다(빠진 끌 수 있는 모듈 = 끔). 필수 짝이 빠지면 400 MODULE_DEPENDENCY */
    @PutMapping("/api/admin/modules")
    @ProgAuth(prog = CompModuleService.PROG, action = Action.SAVE)
    public ApiResponse<Void> save(@RequestBody SaveForm form, Locale locale) {
        List<ModuleView> list = service.save(form, locale);
        return ApiResponse.list(list, list.size());
    }
}
