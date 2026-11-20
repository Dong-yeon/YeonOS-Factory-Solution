package io.smarthappy.module.pms;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.pms.PortfolioDtos.MyTodo;
import io.smarthappy.module.pms.PortfolioDtos.PortRow;
import io.smarthappy.module.pms.PortfolioDtos.Report;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** PMS 홈·포트폴리오·프로젝트 보고서 API (docs/32 PM5) */
@RestController
@RequiredArgsConstructor
public class PortfolioApiController {

    private final PortfolioService service;
    private final MessageSource messages;

    @GetMapping("/api/pms/portfolio")
    @ProgAuth(prog = PortfolioService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PortRow>> portfolio(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String projType, @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        return ApiResponse.ok(service.portfolio(keyword, projType, status, mine));
    }

    /** grid(UI 단계 3 ②, docs/32 4.11) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping("/api/pms/portfolio/grid")
    @ProgAuth(prog = PortfolioService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String projType, @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        var p = service.grid(keyword, projType, status, mine, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** 연도·유형 차트(docs/32 4.20) — 목록과 같은 조건 */
    @GetMapping("/api/pms/portfolio/trend")
    @ProgAuth(prog = PortfolioService.PROG, action = Action.SEARCH)
    public ApiResponse<io.smarthappy.module.pms.PortfolioDtos.TrendView> trend(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String projType, @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        return ApiResponse.ok(service.trend(keyword, projType, status, mine));
    }

    /** 상태별 차트(docs/32 4.15) — 목록과 같은 조건, 쪽 나눔 없이 전체 */
    @GetMapping("/api/pms/portfolio/chart")
    @ProgAuth(prog = PortfolioService.PROG, action = Action.SEARCH)
    public ApiResponse<List<io.smarthappy.module.pms.PortfolioDtos.ChartRow>> chart(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String projType, @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        return ApiResponse.ok(service.chart(keyword, projType, status, mine));
    }

    /** 공수 표시 단위(docs/48 R-2) — 회사 하루 시간(정책 PMS_HOURS_PER_DAY). 화면이 시간과 MD 를 함께 보인다 */
    @GetMapping("/api/pms/portfolio/effort-unit")
    @ProgAuth(prog = PortfolioService.PROG, action = Action.SEARCH)
    public ApiResponse<Map<String, Integer>> effortUnit() {
        return ApiResponse.ok(Map.of("hoursPerDay", service.hoursPerDay()));
    }

    @GetMapping("/api/pms/portfolio/columns")
    @ProgAuth(prog = PortfolioService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> gridColumns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    public record ExportRequest(String keyword, String projType, String status, Boolean mine, List<String> columns) {
    }

    @PostMapping("/api/pms/portfolio/export")
    @ProgAuth(prog = PortfolioService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) ExportRequest req, HttpServletResponse response) throws IOException {
        ExportRequest r = req == null ? new ExportRequest(null, null, null, null, null) : req;
        service.export(r.keyword(), r.projType(), r.status(), Boolean.TRUE.equals(r.mine()), r.columns(), response);
    }

    @GetMapping("/api/pms/my-todo")
    @ProgAuth(prog = PortfolioService.PROG, action = Action.SEARCH)
    public ApiResponse<MyTodo> myTodo() {
        return ApiResponse.ok(service.myTodo());
    }

    @GetMapping("/api/pms/projects/{projId}/report")
    @ProgAuth(prog = PortfolioService.PROG, action = Action.SEARCH)
    public ApiResponse<Report> report(@PathVariable long projId) {
        return ApiResponse.ok(service.report(projId));
    }
}
