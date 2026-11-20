package io.smarthappy.module.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/**
 * 현장 단말 진입 화면(docs/36 21장) — 생산 "현장 단말(POP)" prd-pop · 창고 "창고 단말(PDA)" wms-pda.
 * 화면이 바로가기(POP 단말 관리 sys-terminal · 현장 단말 앱 sys-pda-app)를 그 화면 권한이 있을 때만 보이도록 서버가 판정한다.
 * POP 체인(/api/pop/**)과 경로가 겹치지 않게 /api/terminal-entry 아래 — 사무 로그인 체인에서만 돈다(단말 쿠키를 읽지 않음)
 */
@RestController
@RequiredArgsConstructor
public class TerminalEntryApiController {

    static final String PROG_POP = "prd-pop";
    static final String PROG_PDA = "wms-pda";

    private final PermissionService permissions;

    /** terminalMgt = POP 단말 관리 화면, pdaApp = 현장 단말 앱(APK) 화면을 열 수 있는지 */
    public record Links(boolean terminalMgt, boolean pdaApp) {
    }

    @GetMapping("/api/terminal-entry/pop/links")
    @ProgAuth(prog = PROG_POP, action = Action.SEARCH)
    public ApiResponse<Links> popLinks() {
        return ApiResponse.ok(links());
    }

    @GetMapping("/api/terminal-entry/pda/links")
    @ProgAuth(prog = PROG_PDA, action = Action.SEARCH)
    public ApiResponse<Links> pdaLinks() {
        return ApiResponse.ok(links());
    }

    private Links links() {
        return new Links(permissions.actions("sys-terminal").contains(Action.SEARCH),
                permissions.actions("sys-pda-app").contains(Action.SEARCH));
    }
}
