package io.smarthappy.module.apqp;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.apqp.ApqpDtos.ItemOption;
import io.smarthappy.module.apqp.ApqpDtos.MoveForm;
import io.smarthappy.module.apqp.ApqpDtos.ProjOption;
import io.smarthappy.module.apqp.ApqpDtos.RoutingOption;
import io.smarthappy.module.apqp.ApqpDtos.RowVerForm;
import io.smarthappy.module.apqp.ApqpDtos.SubmitForm;
import io.smarthappy.module.apqp.CpDtos.CpCopyForm;
import io.smarthappy.module.apqp.CpDtos.CpDetail;
import io.smarthappy.module.apqp.CpDtos.CpExportRequest;
import io.smarthappy.module.apqp.CpDtos.CpForm;
import io.smarthappy.module.apqp.CpDtos.CpLineForm;
import io.smarthappy.module.apqp.CpDtos.FmeaOption;
import io.smarthappy.module.apqp.CpDtos.PullResult;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** P6 APQP A2 API — 관리계획서(apqp-cp) (docs/41 5장). 고르기 목록도 이 화면 권한으로(FMEA 화면 권한 없이 쓸 수 있게) */
@RestController
@RequiredArgsConstructor
public class CpApiController {

    private static final String C = "/api/apqp/cps";

    private final CpService cps;
    private final ApqpRepository repo;
    private final UserDirectory users;
    private final MessageSource messages;

    // ------------------------------------------------------------------ 목록·머리

    /** grid — 검색어·상태(비우면 현행, ALL)·단계 + page·size·sort(허용 목록)·total */
    @GetMapping(C + "/grid")
    @ProgAuth(prog = CpService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(required = false) String phase, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        var p = cps.grid(keyword, status, phase, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** 목록 엑셀 — 재검토 칸은 화면과 같은 글자(서버 i18n) */
    @PostMapping(C + "/export")
    @ProgAuth(prog = CpService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) CpExportRequest req, HttpServletResponse response) throws IOException {
        cps.export(req, response);
    }

    @GetMapping(C + "/columns")
    @ProgAuth(prog = CpService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = cps.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(C + "/{id}")
    @ProgAuth(prog = CpService.PROG, action = Action.SEARCH)
    public ApiResponse<CpDetail> cp(@PathVariable long id) {
        return ApiResponse.ok(cps.get(id));
    }

    @PostMapping(C)
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody CpForm form) {
        return ApiResponse.ok(cps.create(form));
    }

    @PutMapping(C + "/{id}")
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long id, @RequestBody CpForm form) {
        cps.update(id, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping(C + "/{id}")
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<String> delete(@PathVariable long id, @RequestParam(required = false) Integer rowVer) {
        cps.delete(id, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(C + "/{id}/release")
    @ProgAuth(prog = CpService.PROG, action = Action.APPROVE)
    public ApiResponse<String> release(@PathVariable long id, @RequestBody RowVerForm form) {
        cps.release(id, form == null ? null : form.rowVer());
        return ApiResponse.ok("OK");
    }

    /** 결재 상신(정책 APQP_RELEASE_APPROVAL = Y) — 작성자 몫(SAVE), 승인은 공통 결재함에서 */
    @PostMapping(C + "/{id}/submit")
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<Long> submit(@PathVariable long id, @RequestBody SubmitForm form) {
        return ApiResponse.ok(cps.submit(id, form));
    }

    @PostMapping(C + "/{id}/revise")
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<Long> revise(@PathVariable long id, @RequestBody(required = false) CpCopyForm form) {
        return ApiResponse.ok(cps.revise(id, form));
    }

    @PostMapping(C + "/{id}/next-phase")
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<Long> nextPhase(@PathVariable long id, @RequestBody CpCopyForm form) {
        return ApiResponse.ok(cps.nextPhase(id, form));
    }

    @PostMapping(C + "/{id}/pull")
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<PullResult> pull(@PathVariable long id) {
        return ApiResponse.ok(cps.pull(id));
    }

    // ------------------------------------------------------------------ 행

    @PostMapping(C + "/{id}/lines")
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addLine(@PathVariable long id, @RequestBody CpLineForm form) {
        return ApiResponse.ok(cps.addLine(id, form));
    }

    @PutMapping("/api/apqp/cp-lines/{lineId}")
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateLine(@PathVariable long lineId, @RequestBody CpLineForm form) {
        cps.updateLine(lineId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/apqp/cp-lines/{lineId}/move")
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<String> moveLine(@PathVariable long lineId, @RequestBody MoveForm form) {
        cps.moveLine(lineId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/cp-lines/{lineId}")
    @ProgAuth(prog = CpService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteLine(@PathVariable long lineId, @RequestParam(required = false) Integer rowVer) {
        cps.deleteLine(lineId, rowVer);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 고르기 목록 (관리계획서 화면)

    @GetMapping(C + "/items")
    @ProgAuth(prog = CpService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<ItemOption>> items(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(repo.findItems(ApqpInputs.like(keyword), 50));
    }

    @GetMapping(C + "/items/{itemId}/routings")
    @ProgAuth(prog = CpService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<RoutingOption>> routings(@PathVariable long itemId) {
        return ApiResponse.ok(repo.findRoutings(itemId));
    }

    /** 품목의 확정 PFMEA(근거) */
    @GetMapping(C + "/fmeas")
    @ProgAuth(prog = CpService.PROG, action = Action.SEARCH)
    public ApiResponse<List<FmeaOption>> fmeas(@RequestParam(required = false) String itemCd) {
        return ApiResponse.ok(cps.fmeas(itemCd));
    }

    /** 결재선 고르기 */
    @GetMapping(C + "/users")
    @ProgAuth(prog = CpService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<UserOption>> users() {
        return ApiResponse.ok(users.activeUsers());
    }

    @GetMapping(C + "/projects")
    @ProgAuth(prog = CpService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<ProjOption>> projects() {
        return ApiResponse.ok(repo.findProjects());
    }
}
