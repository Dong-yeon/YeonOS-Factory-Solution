package io.smarthappy.module.admin;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.admin.IntegrityDtos.Run;
import io.smarthappy.module.admin.IntegrityDtos.RunRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 기록 무결성 점검(sys-integrity, docs/52 Y1) API — 실행 = FN1(접속 로그 ACTION 으로 남는다), 이력·결과 = SEARCH, 엑셀 = EXCEL.
 * 기본 권한은 관리자 그룹만(회사 관리자가 다른 그룹에 줄 수 있다).
 */
@RestController
@RequiredArgsConstructor
public class IntegrityApiController {

    private final IntegrityCheckService integrity;

    @PostMapping("/api/admin/integrity/runs")
    @ProgAuth(prog = IntegrityCheckService.PROG, action = Action.FN1)
    public ApiResponse<Run> run() {
        return ApiResponse.ok(integrity.run());
    }

    @GetMapping("/api/admin/integrity/runs")
    @ProgAuth(prog = IntegrityCheckService.PROG, action = Action.SEARCH)
    public ApiResponse<List<RunRow>> history() {
        return ApiResponse.ok(integrity.history());
    }

    @GetMapping("/api/admin/integrity/runs/{id}")
    @ProgAuth(prog = IntegrityCheckService.PROG, action = Action.SEARCH)
    public ApiResponse<Run> detail(@PathVariable long id) {
        return ApiResponse.ok(integrity.detail(id));
    }

    @PostMapping("/api/admin/integrity/runs/{id}/excel")
    @ProgAuth(prog = IntegrityCheckService.PROG, action = Action.EXCEL)
    public void excel(@PathVariable long id, Locale locale, HttpServletResponse response) throws IOException {
        integrity.export(id, locale, response);
    }
}
