package io.smarthappy.module.home;

import java.util.List;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.home.HomeCardAdminService.GroupCards;
import io.smarthappy.module.home.HomeMapper.GroupRow;
import io.smarthappy.module.home.HomeService.SettingForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 역할별 기본 카드 세트 관리 API (home-card-mgt, docs/37 15.3) */
@RestController
@RequiredArgsConstructor
public class HomeCardAdminApiController {

    private static final String BASE = "/api/home/grp-cards";

    private final HomeCardAdminService service;

    @GetMapping(BASE)
    @ProgAuth(prog = HomeCardAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> groups() {
        List<GroupRow> list = service.groups();
        return ApiResponse.list(list, list.size());
    }

    @GetMapping(BASE + "/{authGrpCd}")
    @ProgAuth(prog = HomeCardAdminService.PROG, action = Action.SEARCH)
    public ApiResponse<GroupCards> detail(@PathVariable String authGrpCd, Locale locale) {
        return ApiResponse.ok(service.detail(authGrpCd, locale));
    }

    /** 한 벌 저장 — ver = 상세의 판 지문(다르면 409). 반환: 새 판 */
    @PutMapping(BASE + "/{authGrpCd}")
    @ProgAuth(prog = HomeCardAdminService.PROG, action = Action.SAVE)
    public ApiResponse<String> save(@PathVariable String authGrpCd, @RequestParam(required = false) String ver,
            @RequestBody(required = false) List<SettingForm> in) {
        return ApiResponse.ok(service.save(authGrpCd, ver, in));
    }

    @PostMapping(BASE + "/{authGrpCd}/reset")
    @ProgAuth(prog = HomeCardAdminService.PROG, action = Action.SAVE)
    public ApiResponse<String> reset(@PathVariable String authGrpCd, @RequestParam(required = false) String ver) {
        return ApiResponse.ok(service.reset(authGrpCd, ver));
    }
}
