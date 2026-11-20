package io.smarthappy.module.wms.asrs;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.wms.asrs.AsrsDtos.AdjustIn;
import io.smarthappy.module.wms.asrs.AsrsDtos.AsrsForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.AsrsRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ContentForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrTypeForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrTypeRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.DismissIn;
import io.smarthappy.module.wms.asrs.AsrsDtos.InReqForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.OutReqForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReconRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReconView;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReqRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReqView;
import io.smarthappy.module.wms.asrs.AsrsDtos.UrgentForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.UrgentStats;
import io.smarthappy.module.wms.inv.InvDtos.PostResult;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 자동창고 화면 API (wms-asrs, docs/65 23장) — 삭제 없음(사용 중지·취소) */
@RestController
@RequiredArgsConstructor
public class AsrsApiController {

    private static final String BASE = "/api/wms/asrs";

    private final AsrsService service;
    private final AsrsReconService recon;

    public record RowVer(Integer rowVer) {
    }

    @GetMapping(BASE)
    @ProgAuth(prog = AsrsService.PROG, action = Action.SEARCH)
    public ApiResponse<List<AsrsRow>> list(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.asrsList(plantCd));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody AsrsForm f) {
        return ApiResponse.ok(service.createAsrs(f));
    }

    @PutMapping(BASE + "/{id}")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long id, @RequestBody AsrsForm f) {
        service.updateAsrs(id, f);
        return ApiResponse.ok("OK");
    }

    @GetMapping(BASE + "/ctr-types")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CtrTypeRow>> types() {
        return ApiResponse.ok(service.types());
    }

    @PostMapping(BASE + "/ctr-types")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createType(@RequestBody CtrTypeForm f) {
        return ApiResponse.ok(service.saveType(null, f));
    }

    @PutMapping(BASE + "/ctr-types/{id}")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<Long> updateType(@PathVariable long id, @RequestBody CtrTypeForm f) {
        return ApiResponse.ok(service.saveType(id, f));
    }

    @GetMapping(BASE + "/ctrs")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CtrRow>> ctrs(@RequestParam(required = false) Long asrsId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(service.ctrs(asrsId, status, q));
    }

    @GetMapping(BASE + "/ctrs/{id}")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SEARCH)
    public ApiResponse<Map<String, Object>> ctr(@PathVariable long id) {
        return ApiResponse.ok(service.ctr(id));
    }

    @PostMapping(BASE + "/ctrs")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createCtr(@RequestBody CtrForm f) {
        return ApiResponse.ok(service.saveCtr(null, f));
    }

    @PutMapping(BASE + "/ctrs/{id}")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<Long> updateCtr(@PathVariable long id, @RequestBody CtrForm f) {
        return ApiResponse.ok(service.saveCtr(id, f));
    }

    /** 용기 내용 정의(전체 덮어쓰기) — 경고(같은 칸 다른 LOT)를 돌려준다 */
    @PutMapping(BASE + "/ctrs/{id}/contents")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<List<String>> contents(@PathVariable long id, @RequestBody ContentForm f) {
        return ApiResponse.ok(service.defineContents(id, f));
    }

    @GetMapping(BASE + "/reqs")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ReqRow>> reqs(@RequestParam(required = false) Long asrsId, @RequestParam(required = false) String sendState) {
        return ApiResponse.ok(service.reqs(asrsId, sendState));
    }

    @GetMapping(BASE + "/reqs/{id}")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SEARCH)
    public ApiResponse<ReqView> req(@PathVariable long id) {
        return ApiResponse.ok(service.req(id));
    }

    @PostMapping(BASE + "/reqs/in")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createIn(@RequestBody InReqForm f) {
        return ApiResponse.ok(service.createIn(f));
    }

    @PostMapping(BASE + "/reqs/{id}/send")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<String> send(@PathVariable long id, @RequestBody RowVer v) {
        return ApiResponse.ok(service.send(id, v == null ? null : v.rowVer()));
    }

    @PostMapping(BASE + "/reqs/{id}/cancel")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long id, @RequestBody RowVer v) {
        service.cancel(id, v == null ? null : v.rowVer());
        return ApiResponse.ok("OK");
    }

    // ---------------------------------------------------------------- 출고·긴급 (docs/65 25.1·25.2)

    @PostMapping(BASE + "/reqs/out")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createOut(@RequestBody OutReqForm f) {
        return ApiResponse.ok(service.createOut(f));
    }

    /** 긴급 표시 — APPROVE(조회·저장만 있는 사람은 못 붙인다, Q-E4-4) */
    @PostMapping(BASE + "/reqs/{id}/urgent")
    @ProgAuth(prog = AsrsService.PROG, action = Action.APPROVE)
    public ApiResponse<String> urgent(@PathVariable long id, @RequestBody UrgentForm f) {
        service.markUrgent(id, f);
        return ApiResponse.ok("OK");
    }

    @GetMapping(BASE + "/urgent-stats")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SEARCH)
    public ApiResponse<UrgentStats> urgentStats(@RequestParam(required = false) Integer days) {
        return ApiResponse.ok(service.urgentStats(days));
    }

    // ---------------------------------------------------------------- 대사 (docs/65 25.3)

    @GetMapping(BASE + "/recons")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ReconRow>> recons(@RequestParam(required = false) Long asrsId) {
        return ApiResponse.ok(recon.recons(asrsId));
    }

    @GetMapping(BASE + "/recons/{id}")
    @ProgAuth(prog = AsrsService.PROG, action = Action.SEARCH)
    public ApiResponse<ReconView> recon(@PathVariable long id) {
        return ApiResponse.ok(recon.recon(id));
    }

    /** 대사 차이 바로 조정 — APPROVE, 원장은 재고 조정 서비스 경유(Q-E4-1) */
    @PostMapping(BASE + "/recon-diffs/{id}/adjust")
    @ProgAuth(prog = AsrsService.PROG, action = Action.APPROVE)
    public ApiResponse<PostResult> adjust(@PathVariable long id, @RequestBody AdjustIn in) {
        return ApiResponse.ok(recon.adjust(id, in));
    }

    @PostMapping(BASE + "/recon-diffs/{id}/dismiss")
    @ProgAuth(prog = AsrsService.PROG, action = Action.APPROVE)
    public ApiResponse<String> dismiss(@PathVariable long id, @RequestBody DismissIn in) {
        recon.dismiss(id, in);
        return ApiResponse.ok("OK");
    }
}
