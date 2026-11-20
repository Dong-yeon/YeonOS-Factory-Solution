package io.smarthappy.module.cms;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.CmsDtos.Option;
import io.smarthappy.module.cms.CmsRiskDtos.CopyForm;
import io.smarthappy.module.cms.CmsRiskDtos.ItemForm;
import io.smarthappy.module.cms.CmsRiskDtos.MemberForm;
import io.smarthappy.module.cms.CmsRiskDtos.NotifyForm;
import io.smarthappy.module.cms.CmsRiskDtos.ProgressForm;
import io.smarthappy.module.cms.CmsRiskDtos.RaAction;
import io.smarthappy.module.cms.CmsRiskDtos.RaForm;
import io.smarthappy.module.cms.CmsRiskDtos.RaRow;
import io.smarthappy.module.cms.CmsRiskDtos.ReleaseForm;
import io.smarthappy.module.cms.CmsRiskDtos.WorkerOption;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import io.smarthappy.platform.excel.GridExcel;

/** CMS 1부 C6 API — 위험성평가 (docs/33 4.7). 확정 요청은 SAVE(결재 상신) — 결재 없이 바로 확정은 서비스가 APPROVE 를 본다 */
@RestController
@RequiredArgsConstructor
public class CmsRiskApiController {

    private static final String RA = "/api/cms/risks";

    private final MessageSource messages;
    private final RiskService risks;
    private final CmsMapper mapper;
    private final UserDirectory users;
    private final GridExcel gridExcel;

    /** 참여자 고르기 — 사용자 + 사용 중 작업자(C19, 기준정보 읽기) */
    public record RiskOptions(List<Option> plants, List<UserOption> users, List<WorkerOption> workers) {
    }

    @GetMapping(RA + "/options")
    @ProgAuth(prog = RiskService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<RiskOptions> options() {
        return ApiResponse.ok(new RiskOptions(mapper.findPlants(), users.activeUsers(), risks.workers()));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(RA + "/grid")
    @ProgAuth(prog = RiskService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        var p = risks.grid(from, to, status, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(RA + "/columns")
    @ProgAuth(prog = RiskService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> gridColumns(Locale locale) {
        List<Map<String, Object>> cols = risks.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(RA + "/export")
    @ProgAuth(prog = RiskService.PROG, action = Action.EXCEL)
    public void gridExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, RiskService.PROG, risks.gridColumns(), q -> {
            var p = risks.grid(q.str("from"), q.str("to"), q.str("status"), q.str("keyword"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(RA)
    @ProgAuth(prog = RiskService.PROG, action = Action.SEARCH)
    public ApiResponse<List<RaRow>> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(risks.list(from, to, status, keyword));
    }

    @GetMapping(RA + "/{raId}")
    @ProgAuth(prog = RiskService.PROG, action = Action.SEARCH)
    public ApiResponse<RaRow> get(@PathVariable long raId) {
        return ApiResponse.ok(risks.get(raId));
    }

    @PostMapping(RA)
    @ProgAuth(prog = RiskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody RaForm form) {
        return ApiResponse.ok(risks.create(form));
    }

    @PutMapping(RA + "/{raId}")
    @ProgAuth(prog = RiskService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long raId, @RequestBody RaForm form) {
        risks.update(raId, form);
        return ApiResponse.ok("OK");
    }

    /** 이전 확정본 복사 → 새 평가(작성 중) */
    @PostMapping(RA + "/{raId}/copy")
    @ProgAuth(prog = RiskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> copy(@PathVariable long raId, @RequestBody(required = false) CopyForm form) {
        return ApiResponse.ok(risks.copy(raId, form));
    }

    @PostMapping(RA + "/{raId}/items")
    @ProgAuth(prog = RiskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> saveItem(@PathVariable long raId, @RequestBody ItemForm form) {
        return ApiResponse.ok(risks.saveItem(raId, form));
    }

    @PostMapping(RA + "/{raId}/items/{itemId}/delete")
    @ProgAuth(prog = RiskService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteItem(@PathVariable long raId, @PathVariable long itemId) {
        risks.deleteItem(raId, itemId);
        return ApiResponse.ok("OK");
    }

    /** 확정 뒤 개선 진행 */
    @PutMapping(RA + "/{raId}/items/{itemId}/progress")
    @ProgAuth(prog = RiskService.PROG, action = Action.SAVE)
    public ApiResponse<String> progress(@PathVariable long raId, @PathVariable long itemId, @RequestBody ProgressForm form) {
        risks.progress(raId, itemId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(RA + "/{raId}/members")
    @ProgAuth(prog = RiskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addMember(@PathVariable long raId, @RequestBody MemberForm form) {
        return ApiResponse.ok(risks.addMember(raId, form));
    }

    @PostMapping(RA + "/{raId}/members/{memberId}/delete")
    @ProgAuth(prog = RiskService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteMember(@PathVariable long raId, @PathVariable long memberId) {
        risks.deleteMember(raId, memberId);
        return ApiResponse.ok("OK");
    }

    @PutMapping(RA + "/{raId}/members/{memberId}/notify")
    @ProgAuth(prog = RiskService.PROG, action = Action.SAVE)
    public ApiResponse<String> notify(@PathVariable long raId, @PathVariable long memberId, @RequestBody NotifyForm form) {
        risks.notify(raId, memberId, form);
        return ApiResponse.ok("OK");
    }

    /** 확정 요청 — 결과 = 결재 번호(결재 없이 바로 확정이면 0) */
    @PostMapping(RA + "/{raId}/release")
    @ProgAuth(prog = RiskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> release(@PathVariable long raId, @RequestBody ReleaseForm form) {
        return ApiResponse.ok(risks.release(raId, form));
    }

    @PostMapping(RA + "/{raId}/obsolete")
    @ProgAuth(prog = RiskService.PROG, action = Action.DELETE)
    public ApiResponse<String> obsolete(@PathVariable long raId, @RequestBody RaAction form) {
        risks.obsolete(raId, form == null ? null : form.rowVer());
        return ApiResponse.ok("OK");
    }

    @PostMapping(RA + "/{raId}/export")
    @ProgAuth(prog = RiskService.PROG, action = Action.EXCEL)
    public void export(@PathVariable long raId, HttpServletResponse response) throws IOException {
        risks.export(raId, response);
    }
}
