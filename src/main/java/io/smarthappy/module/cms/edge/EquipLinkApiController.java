package io.smarthappy.module.cms.edge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.StateTimelineService;
import io.smarthappy.module.cms.edge.EdgeDtos.CmdForm;
import io.smarthappy.module.cms.edge.EdgeDtos.CmdRow;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkForm;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRef;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRow;
import io.smarthappy.module.cms.edge.EdgeDtos.TagForm;
import io.smarthappy.module.cms.edge.EdgeDtos.TagRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.messaging.InboxService;
import lombok.RequiredArgsConstructor;

/**
 * 설비 연동 화면 API (cms-equip-link, docs/65 16장). 조회 SEARCH · 연동·태그 설정 SAVE · **명령 쓰기 FN1**(설정 권한과 나눔, docs/05).
 * 명령 요청은 X-Idempotency-Key 필수 — 장비에 쓰는 일이라 두 번 일어나면 안 된다(SKILL 메시징 규칙).
 */
@RestController
@RequiredArgsConstructor
public class EquipLinkApiController {

    private static final String API = "/api/cms/equip-links";
    private static final String PROG = EquipLinkService.PROG;

    private final EquipLinkService links;
    private final EquipCommandService commands;
    private final EdgeIngestService ingest;
    private final StateTimelineService timeline;

    @GetMapping(API + "/options")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plants", timeline.plants());
        m.put("collectors", links.collectors());
        return ApiResponse.ok(m);
    }

    @GetMapping(API)
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<LinkRow>> list(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(links.links(plantCd, keyword));
    }

    @GetMapping(API + "/{equipId}")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Map<String, Object>> detail(@PathVariable long equipId) {
        return ApiResponse.ok(links.detail(equipId));
    }

    @PutMapping(API + "/{equipId}")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<LinkRef> save(@PathVariable long equipId, @RequestBody LinkForm form) {
        return ApiResponse.ok(links.save(equipId, form));
    }

    @PostMapping(API + "/{equipId}/tags")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<TagRow> saveTag(@PathVariable long equipId, @RequestBody TagForm form) {
        return ApiResponse.ok(links.saveTag(equipId, form));
    }

    @GetMapping(API + "/{equipId}/commands")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<CmdRow>> commands(@PathVariable long equipId, @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(commands.list(equipId, limit == null ? 30 : limit));
    }

    @PostMapping(API + "/{equipId}/commands")
    @ProgAuth(prog = PROG, action = Action.FN1)
    public ApiResponse<CmdRow> command(@PathVariable long equipId, @RequestBody CmdForm form,
            @RequestHeader(InboxService.IDEMPOTENCY_HEADER) String requestKey) {
        return ApiResponse.ok(commands.request(equipId, form));
    }

    /** 결과 불명(UNKNOWN)을 사람이 확정 — 현장에서 확인한 결과 */
    @PostMapping(API + "/{equipId}/commands/{cmdId}/resolve")
    @ProgAuth(prog = PROG, action = Action.FN1)
    public ApiResponse<CmdRow> resolve(@PathVariable long equipId, @PathVariable long cmdId, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(commands.resolve(equipId, cmdId, body.get("status"), body.get("note")));
    }

    @GetMapping(API + "/inbound")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<Map<String, Object>>> inbound(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(links.inbound(status, limit == null ? 100 : limit));
    }

    /** 격리된 원문을 다시 처리 대기로 */
    @PostMapping(API + "/inbound/{inboundId}/requeue")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Void> requeue(@PathVariable long inboundId) {
        links.requeue(inboundId);
        return ApiResponse.ok(null);
    }

    @GetMapping(API + "/logs")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Object> logs(@RequestParam(required = false) Long equipId, @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(links.logs(equipId, limit == null ? 100 : limit));
    }

    @GetMapping(API + "/{equipId}/consumables")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Map<String, String>> consumables(@PathVariable long equipId) {
        return ApiResponse.ok(ingest.consumableLevels(equipId));
    }
}
