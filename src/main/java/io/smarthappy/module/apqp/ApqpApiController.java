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

import io.smarthappy.module.apqp.ApqpDtos.ActionForm;
import io.smarthappy.module.apqp.ApqpDtos.ApCopyForm;
import io.smarthappy.module.apqp.ApqpDtos.ApSetForm;
import io.smarthappy.module.apqp.ApqpDtos.ApSetRow;
import io.smarthappy.module.apqp.ApqpDtos.ApSetView;
import io.smarthappy.module.apqp.ApqpDtos.ChainForm;
import io.smarthappy.module.apqp.ApqpDtos.CharOption;
import io.smarthappy.module.apqp.ApqpDtos.DefectOption;
import io.smarthappy.module.apqp.ApqpDtos.FailForm;
import io.smarthappy.module.apqp.ApqpDtos.FmeaDetail;
import io.smarthappy.module.apqp.ApqpDtos.FmeaExportRequest;
import io.smarthappy.module.apqp.ApqpDtos.FmeaForm;
import io.smarthappy.module.apqp.ApqpDtos.FuncForm;
import io.smarthappy.module.apqp.ApqpDtos.ItemOption;
import io.smarthappy.module.apqp.ApqpDtos.MoveForm;
import io.smarthappy.module.apqp.ApqpDtos.NodeForm;
import io.smarthappy.module.apqp.ApqpDtos.ProjOption;
import io.smarthappy.module.apqp.ApqpDtos.RelinkForm;
import io.smarthappy.module.apqp.ApqpDtos.RelinkPlan;
import io.smarthappy.module.apqp.ApqpDtos.RiskForm;
import io.smarthappy.module.apqp.ApqpDtos.RoutingOption;
import io.smarthappy.module.apqp.ApqpDtos.RowVerForm;
import io.smarthappy.module.apqp.ApqpDtos.SubmitForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** P6 APQP A1 API — PFMEA(apqp-fmea)·AP 규칙표(apqp-ap) (docs/41). 행·상태 판정은 서비스·트리거 */
@RestController
@RequiredArgsConstructor
public class ApqpApiController {

    private static final String F = "/api/apqp/fmeas";
    private static final String AP = "/api/apqp/ap-sets";

    private final FmeaService fmeas;
    private final FmeaTypeGuard guard;
    private final ApqpFlowService flows;
    private final ApSetService apSets;
    private final ApqpRepository repo;
    private final UserDirectory users;
    private final MessageSource messages;

    // ------------------------------------------------------------------ FMEA 목록·머리

    /** grid — 검색어·상태(비우면 현행, ALL)·프로젝트 + page·size·sort(허용 목록)·total */
    @GetMapping(F + "/grid")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(required = false) Long projId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        var p = fmeas.grid(FmeaService.PFMEA, keyword, status, projId, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** 목록 엑셀 — 재검토 칸은 화면과 같은 글자(서버 i18n) */
    @PostMapping(F + "/export")
    @ProgAuth(prog = FmeaService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) FmeaExportRequest req, HttpServletResponse response) throws IOException {
        fmeas.export(FmeaService.PFMEA, req, response);
    }

    @GetMapping(F + "/columns")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = fmeas.gridColumns(FmeaService.PFMEA).stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(F + "/{id}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    public ApiResponse<FmeaDetail> fmea(@PathVariable long id) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.get(id));
    }

    /** 공정 흐름도 PFD(A5-1) — 저장 없이 라우팅·위험행·관리계획서에서 만든다 */
    @GetMapping(F + "/{id}/flow")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    public ApiResponse<ApqpDtos.FlowView> flow(@PathVariable long id) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(flows.flow(id));
    }

    @PostMapping(F)
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody FmeaForm form) {
        return ApiResponse.ok(fmeas.create(FmeaService.PFMEA, form));
    }

    @PutMapping(F + "/{id}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long id, @RequestBody FmeaForm form) {
        guard.fmea(FmeaService.PFMEA, id);
        fmeas.update(id, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping(F + "/{id}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> delete(@PathVariable long id, @RequestParam(required = false) Integer rowVer) {
        guard.fmea(FmeaService.PFMEA, id);
        fmeas.delete(id, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/release")
    @ProgAuth(prog = FmeaService.PROG, action = Action.APPROVE)
    public ApiResponse<String> release(@PathVariable long id, @RequestBody RowVerForm form) {
        guard.fmea(FmeaService.PFMEA, id);
        fmeas.release(id, form == null ? null : form.rowVer());
        return ApiResponse.ok("OK");
    }

    /** 결재 상신(정책 APQP_RELEASE_APPROVAL = Y) — 작성자 몫(SAVE), 승인은 공통 결재함에서 */
    @PostMapping(F + "/{id}/submit")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> submit(@PathVariable long id, @RequestBody SubmitForm form) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.submit(id, form));
    }

    /** 라우팅 바꾸기 미리보기(11.9) — 바꾸지 않는다 */
    @GetMapping(F + "/{id}/routing-relink")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    public ApiResponse<RelinkPlan> relinkPreview(@PathVariable long id, @RequestParam(required = false) Long routingId) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.relinkPreview(id, routingId));
    }

    /** 라우팅 바꾸기 적용 — 서버가 다시 계산, rowVer 다르면 409 */
    @PostMapping(F + "/{id}/routing")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<RelinkPlan> relink(@PathVariable long id, @RequestBody RelinkForm form) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.relink(id, form));
    }

    @PostMapping(F + "/{id}/revise")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> revise(@PathVariable long id) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.revise(id));
    }

    // ------------------------------------------------------------------ 하위 행

    @PostMapping(F + "/{id}/nodes")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addNode(@PathVariable long id, @RequestBody NodeForm form) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.addNode(id, form));
    }

    @PutMapping("/api/apqp/fmea-nodes/{nodeId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateNode(@PathVariable long nodeId, @RequestBody NodeForm form) {
        guard.row(FmeaService.PFMEA, "NODE", nodeId);
        fmeas.updateNode(nodeId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/apqp/fmea-nodes/{nodeId}/move")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> moveNode(@PathVariable long nodeId, @RequestBody MoveForm form) {
        guard.row(FmeaService.PFMEA, "NODE", nodeId);
        fmeas.moveNode(nodeId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/fmea-nodes/{nodeId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteNode(@PathVariable long nodeId, @RequestParam(required = false) Integer rowVer) {
        guard.row(FmeaService.PFMEA, "NODE", nodeId);
        fmeas.deleteNode(nodeId, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/funcs")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addFunc(@PathVariable long id, @RequestBody FuncForm form) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.addFunc(id, form));
    }

    @PutMapping("/api/apqp/fmea-funcs/{funcId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateFunc(@PathVariable long funcId, @RequestBody FuncForm form) {
        guard.row(FmeaService.PFMEA, "FUNC", funcId);
        fmeas.updateFunc(funcId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/fmea-funcs/{funcId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteFunc(@PathVariable long funcId, @RequestParam(required = false) Integer rowVer) {
        guard.row(FmeaService.PFMEA, "FUNC", funcId);
        fmeas.deleteFunc(funcId, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/fails")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addFail(@PathVariable long id, @RequestBody FailForm form) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.addFail(id, form));
    }

    @PutMapping("/api/apqp/fmea-fails/{failId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateFail(@PathVariable long failId, @RequestBody FailForm form) {
        guard.row(FmeaService.PFMEA, "FAIL", failId);
        fmeas.updateFail(failId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/fmea-fails/{failId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteFail(@PathVariable long failId, @RequestParam(required = false) Integer rowVer) {
        guard.row(FmeaService.PFMEA, "FAIL", failId);
        fmeas.deleteFail(failId, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/chains")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addChain(@PathVariable long id, @RequestBody ChainForm form) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.addChain(id, form));
    }

    @DeleteMapping("/api/apqp/fmea-chains/{chainId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteChain(@PathVariable long chainId) {
        guard.row(FmeaService.PFMEA, "CHAIN", chainId);
        fmeas.deleteChain(chainId);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/risks")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addRisk(@PathVariable long id, @RequestBody RiskForm form) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.addRisk(id, form));
    }

    @PutMapping("/api/apqp/fmea-risks/{riskId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateRisk(@PathVariable long riskId, @RequestBody RiskForm form) {
        guard.row(FmeaService.PFMEA, "RISK", riskId);
        fmeas.updateRisk(riskId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/fmea-risks/{riskId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteRisk(@PathVariable long riskId, @RequestParam(required = false) Integer rowVer) {
        guard.row(FmeaService.PFMEA, "RISK", riskId);
        fmeas.deleteRisk(riskId, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/actions")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addAction(@PathVariable long id, @RequestBody ActionForm form) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.addAction(id, form));
    }

    @PutMapping("/api/apqp/fmea-actions/{actionId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateAction(@PathVariable long actionId, @RequestBody ActionForm form) {
        guard.row(FmeaService.PFMEA, "ACTION", actionId);
        fmeas.updateAction(actionId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/fmea-actions/{actionId}")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteAction(@PathVariable long actionId, @RequestParam(required = false) Integer rowVer) {
        guard.row(FmeaService.PFMEA, "ACTION", actionId);
        fmeas.deleteAction(actionId, rowVer);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 고르기 목록 (FMEA 화면)

    @GetMapping("/api/apqp/items")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<ItemOption>> items(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(repo.findItems(ApqpInputs.like(keyword), 50));
    }

    @GetMapping("/api/apqp/items/{itemId}/routings")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<RoutingOption>> routings(@PathVariable long itemId) {
        return ApiResponse.ok(repo.findRoutings(itemId));
    }

    @GetMapping(F + "/{id}/chars")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CharOption>> chars(@PathVariable long id) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.chars(id));
    }

    /** 고장 영향 제안(A5-2b D7) — 같은 품목 확정 DFMEA 의 고장 형태(DFMEA 화면 권한과 무관하게 글자·번호만) */
    @GetMapping(F + "/{id}/dfmea-sources")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ApqpDtos.DfmeaSource>> dfmeaSources(@PathVariable long id) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(fmeas.dfmeaSources(id));
    }

    @GetMapping("/api/apqp/defects")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<DefectOption>> defects() {
        return ApiResponse.ok(repo.findDefects());
    }

    @GetMapping("/api/apqp/projects")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<ProjOption>> projects() {
        return ApiResponse.ok(repo.findProjects());
    }

    @GetMapping("/api/apqp/users")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<UserOption>> users() {
        return ApiResponse.ok(users.activeUsers());
    }

    /** FMEA 머리에서 고를 수 있는 규칙표(사용 Y) */
    @GetMapping("/api/apqp/fmea-ap-sets")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ApSetRow>> usableSets() {
        return ApiResponse.ok(apSets.list(true));
    }

    // ------------------------------------------------------------------ AP 규칙표 (apqp-ap)

    @GetMapping(AP)
    @ProgAuth(prog = ApSetService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ApSetRow>> apSets() {
        return ApiResponse.ok(apSets.list(false));
    }

    @GetMapping(AP + "/{id}")
    @ProgAuth(prog = ApSetService.PROG, action = Action.SEARCH)
    public ApiResponse<ApSetView> apSet(@PathVariable long id) {
        return ApiResponse.ok(apSets.get(id));
    }

    @PostMapping(AP)
    @ProgAuth(prog = ApSetService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createApSet(@RequestBody ApSetForm form) {
        return ApiResponse.ok(apSets.create(form));
    }

    @PutMapping(AP + "/{id}")
    @ProgAuth(prog = ApSetService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateApSet(@PathVariable long id, @RequestBody ApSetForm form) {
        apSets.update(id, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(AP + "/{id}/copy")
    @ProgAuth(prog = ApSetService.PROG, action = Action.SAVE)
    public ApiResponse<Long> copyApSet(@PathVariable long id, @RequestBody ApCopyForm form) {
        return ApiResponse.ok(apSets.copy(id, form));
    }
}
