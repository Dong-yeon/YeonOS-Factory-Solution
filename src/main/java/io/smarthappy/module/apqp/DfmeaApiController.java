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
import io.smarthappy.module.apqp.ApqpDtos.ApSetRow;
import io.smarthappy.module.apqp.ApqpDtos.BomOption;
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
import io.smarthappy.module.apqp.ApqpDtos.RiskForm;
import io.smarthappy.module.apqp.ApqpDtos.RowVerForm;
import io.smarthappy.module.apqp.ApqpDtos.SubmitForm;
import io.smarthappy.module.qms.link.DocLookup;
import io.smarthappy.module.qms.link.DocLookup.DocRevView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * DFMEA API (apqp-dfmea, docs/41 13장 A5-2) — PFMEA 와 같은 서비스·같은 표, 경로·화면 권한만 따로(D8).
 * 경로의 id 가 DFMEA 기록이 아니면 404(FmeaTypeGuard). 라우팅·공정 흐름·라우팅 다시 잇기는 PFMEA 만
 */
@RestController
@RequiredArgsConstructor
public class DfmeaApiController {

    private static final String F = "/api/apqp/dfmeas";

    private final FmeaService fmeas;
    private final FmeaTypeGuard guard;
    private final ApSetService apSets;
    private final ApqpRepository repo;
    private final ApqpFormService forms;
    private final ApqpDocService qmsDocs;
    private final org.springframework.beans.factory.ObjectProvider<DocLookup> docs;
    private final UserDirectory users;
    private final MessageSource messages;

    // ------------------------------------------------------------------ FMEA 목록·머리

    /** grid — 검색어·상태(비우면 현행, ALL)·프로젝트 + page·size·sort(허용 목록)·total */
    @GetMapping(F + "/grid")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(required = false) Long projId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        var p = fmeas.grid(FmeaService.DFMEA, keyword, status, projId, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    /** 목록 엑셀 — 재검토 칸은 화면과 같은 글자(서버 i18n) */
    @PostMapping(F + "/export")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) FmeaExportRequest req, HttpServletResponse response) throws IOException {
        fmeas.export(FmeaService.DFMEA, req, response);
    }

    @GetMapping(F + "/columns")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = fmeas.gridColumns(FmeaService.DFMEA).stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(F + "/{id}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    public ApiResponse<FmeaDetail> fmea(@PathVariable long id) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.get(id));
    }

    /** 공정 흐름도 PFD(A5-1) — 저장 없이 라우팅·위험행·관리계획서에서 만든다 */
    @PostMapping(F)
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody FmeaForm form) {
        return ApiResponse.ok(fmeas.create(FmeaService.DFMEA, form));
    }

    @PutMapping(F + "/{id}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long id, @RequestBody FmeaForm form) {
        guard.fmea(FmeaService.DFMEA, id);
        fmeas.update(id, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping(F + "/{id}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> delete(@PathVariable long id, @RequestParam(required = false) Integer rowVer) {
        guard.fmea(FmeaService.DFMEA, id);
        fmeas.delete(id, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/release")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.APPROVE)
    public ApiResponse<String> release(@PathVariable long id, @RequestBody RowVerForm form) {
        guard.fmea(FmeaService.DFMEA, id);
        fmeas.release(id, form == null ? null : form.rowVer());
        return ApiResponse.ok("OK");
    }

    /** 결재 상신(정책 APQP_RELEASE_APPROVAL = Y) — 작성자 몫(SAVE), 승인은 공통 결재함에서 */
    @PostMapping(F + "/{id}/submit")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<Long> submit(@PathVariable long id, @RequestBody SubmitForm form) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.submit(id, form));
    }

    /** 라우팅 바꾸기 미리보기(11.9) — 바꾸지 않는다 */
    /** 라우팅 바꾸기 적용 — 서버가 다시 계산, rowVer 다르면 409 */
    @PostMapping(F + "/{id}/revise")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<Long> revise(@PathVariable long id) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.revise(id));
    }

    // ------------------------------------------------------------------ 하위 행

    @PostMapping(F + "/{id}/nodes")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<Long> addNode(@PathVariable long id, @RequestBody NodeForm form) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.addNode(id, form));
    }

    @PutMapping("/api/apqp/dfmea-nodes/{nodeId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> updateNode(@PathVariable long nodeId, @RequestBody NodeForm form) {
        guard.row(FmeaService.DFMEA, "NODE", nodeId);
        fmeas.updateNode(nodeId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/apqp/dfmea-nodes/{nodeId}/move")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> moveNode(@PathVariable long nodeId, @RequestBody MoveForm form) {
        guard.row(FmeaService.DFMEA, "NODE", nodeId);
        fmeas.moveNode(nodeId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/dfmea-nodes/{nodeId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> deleteNode(@PathVariable long nodeId, @RequestParam(required = false) Integer rowVer) {
        guard.row(FmeaService.DFMEA, "NODE", nodeId);
        fmeas.deleteNode(nodeId, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/funcs")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<Long> addFunc(@PathVariable long id, @RequestBody FuncForm form) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.addFunc(id, form));
    }

    @PutMapping("/api/apqp/dfmea-funcs/{funcId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> updateFunc(@PathVariable long funcId, @RequestBody FuncForm form) {
        guard.row(FmeaService.DFMEA, "FUNC", funcId);
        fmeas.updateFunc(funcId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/dfmea-funcs/{funcId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> deleteFunc(@PathVariable long funcId, @RequestParam(required = false) Integer rowVer) {
        guard.row(FmeaService.DFMEA, "FUNC", funcId);
        fmeas.deleteFunc(funcId, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/fails")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<Long> addFail(@PathVariable long id, @RequestBody FailForm form) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.addFail(id, form));
    }

    @PutMapping("/api/apqp/dfmea-fails/{failId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> updateFail(@PathVariable long failId, @RequestBody FailForm form) {
        guard.row(FmeaService.DFMEA, "FAIL", failId);
        fmeas.updateFail(failId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/dfmea-fails/{failId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> deleteFail(@PathVariable long failId, @RequestParam(required = false) Integer rowVer) {
        guard.row(FmeaService.DFMEA, "FAIL", failId);
        fmeas.deleteFail(failId, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/chains")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<Long> addChain(@PathVariable long id, @RequestBody ChainForm form) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.addChain(id, form));
    }

    @DeleteMapping("/api/apqp/dfmea-chains/{chainId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> deleteChain(@PathVariable long chainId) {
        guard.row(FmeaService.DFMEA, "CHAIN", chainId);
        fmeas.deleteChain(chainId);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/risks")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<Long> addRisk(@PathVariable long id, @RequestBody RiskForm form) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.addRisk(id, form));
    }

    @PutMapping("/api/apqp/dfmea-risks/{riskId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> updateRisk(@PathVariable long riskId, @RequestBody RiskForm form) {
        guard.row(FmeaService.DFMEA, "RISK", riskId);
        fmeas.updateRisk(riskId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/dfmea-risks/{riskId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> deleteRisk(@PathVariable long riskId, @RequestParam(required = false) Integer rowVer) {
        guard.row(FmeaService.DFMEA, "RISK", riskId);
        fmeas.deleteRisk(riskId, rowVer);
        return ApiResponse.ok("OK");
    }

    @PostMapping(F + "/{id}/actions")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<Long> addAction(@PathVariable long id, @RequestBody ActionForm form) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.addAction(id, form));
    }

    @PutMapping("/api/apqp/dfmea-actions/{actionId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> updateAction(@PathVariable long actionId, @RequestBody ActionForm form) {
        guard.row(FmeaService.DFMEA, "ACTION", actionId);
        fmeas.updateAction(actionId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/apqp/dfmea-actions/{actionId}")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<String> deleteAction(@PathVariable long actionId, @RequestParam(required = false) Integer rowVer) {
        guard.row(FmeaService.DFMEA, "ACTION", actionId);
        fmeas.deleteAction(actionId, rowVer);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 고르기 목록 (DFMEA 화면)

    @GetMapping("/api/apqp/dfmea/items")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<ItemOption>> items(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(repo.findItems(ApqpInputs.like(keyword), 50));
    }

    @GetMapping(F + "/{id}/chars")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    public ApiResponse<List<CharOption>> chars(@PathVariable long id) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.chars(id));
    }

    @GetMapping("/api/apqp/dfmea/defects")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<DefectOption>> defects() {
        return ApiResponse.ok(repo.findDefects());
    }

    @GetMapping("/api/apqp/dfmea/projects")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<ProjOption>> projects() {
        return ApiResponse.ok(repo.findProjects());
    }

    @GetMapping("/api/apqp/dfmea/users")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<UserOption>> users() {
        return ApiResponse.ok(users.activeUsers());
    }

    /** DFMEA 머리에서 고를 수 있는 규칙표(사용 Y) */
    @GetMapping("/api/apqp/dfmea/ap-sets")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    public ApiResponse<List<ApSetRow>> usableSets() {
        return ApiResponse.ok(apSets.list(true));
    }

    /** BOM 바꾸기 미리보기(A5-2b D6) — 바꾸지 않는다 */
    @GetMapping(F + "/{id}/bom-relink")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    public ApiResponse<ApqpDtos.BomRelinkPlan> bomRelinkPreview(@PathVariable long id, @RequestParam(required = false) Long bomId) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.bomRelinkPreview(id, bomId));
    }

    /** BOM 바꾸기 적용 — 서버가 다시 계산, rowVer 다르면 409 */
    @PostMapping(F + "/{id}/bom")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SAVE)
    public ApiResponse<ApqpDtos.BomRelinkPlan> bomRelink(@PathVariable long id, @RequestBody ApqpDtos.BomRelinkForm form) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(fmeas.bomRelink(id, form));
    }

    /** 머리 품목의 확정 BOM(대체 번호·리비전) — DFMEA 대상(D5) */
    @GetMapping("/api/apqp/dfmea/items/{itemId}/boms")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<BomOption>> boms(@PathVariable long itemId) {
        return ApiResponse.ok(repo.findReleasedBoms(itemId));
    }

    /** 도면 문서 번호의 리비전(QMS 계약 DocLookup) — DRAWING 유형인지는 저장 때 트리거가 본다 */
    @GetMapping("/api/apqp/dfmea/drawing-revs")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<DocRevView>> drawingRevs(@RequestParam(required = false) String docNo) {
        return ApiResponse.ok(docNo == null || docNo.isBlank() ? List.of() : DocLookup.of(docs).revsByDocNo(docNo.strip()));
    }

    /** 확정 DFMEA → QMS 관리문서(A3-3 재사용, 문서 유형 DFMEA) — 상태 */
    @GetMapping(F + "/{id}/qms-doc")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.SEARCH)
    public ApiResponse<ApqpDocService.DocState> qmsDoc(@PathVariable long id) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(qmsDocs.state(ApqpDocService.FMEA, id));
    }

    /** 올리기 — QMS 가 작성 중 리비전을 만들고 양식 엑셀을 붙인다(결재·배포는 QMS 쪽) */
    @PostMapping(F + "/{id}/qms-doc")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.PRINT)
    public ApiResponse<ApqpDocService.DocView> publishQmsDoc(@PathVariable long id) {
        guard.fmea(FmeaService.DFMEA, id);
        return ApiResponse.ok(qmsDocs.publish(ApqpDocService.FMEA, id));
    }

    /** 엑셀 양식 — PFMEA 와 같은 양식, DFMEA 글자(구조 수준 이름) */
    @PostMapping(F + "/{id}/excel")
    @ProgAuth(prog = FmeaService.DFMEA_PROG, action = Action.PRINT)
    public void excel(@PathVariable long id, HttpServletResponse response) throws IOException {
        guard.fmea(FmeaService.DFMEA, id);
        ApqpFormController.send(forms.fmea(id), response);
    }
}
