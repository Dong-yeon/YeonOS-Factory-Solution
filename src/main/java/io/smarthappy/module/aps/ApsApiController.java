package io.smarthappy.module.aps;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.aps.ApsDtos.ApplyForm;
import io.smarthappy.module.aps.ApsDtos.ApplyPreview;
import io.smarthappy.module.aps.ApsDtos.ApplyResult;
import io.smarthappy.module.aps.ApsDtos.Compare;
import io.smarthappy.module.aps.ApsDtos.CtpForm;
import io.smarthappy.module.aps.ApsDtos.CtpResult;
import io.smarthappy.module.aps.ApsDtos.DiscardForm;
import io.smarthappy.module.aps.ApsDtos.Gantt;
import io.smarthappy.module.aps.ApsDtos.PinForm;
import io.smarthappy.module.aps.ApsDtos.PinMoveForm;
import io.smarthappy.module.aps.ApsDtos.PinRow;
import io.smarthappy.module.aps.ApsDtos.PinSaved;
import io.smarthappy.module.aps.ApsDtos.PlanDetail;
import io.smarthappy.module.aps.ApsDtos.PlanForm;
import io.smarthappy.module.aps.ApsDtos.PlanList;
import io.smarthappy.module.aps.ApsDtos.PlanPage;
import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** APS 생산 계획 API (docs/38 5장·11.4) — 계산은 새 버전(지시 무변경), 반영만 지시를 바꾼다(MES 서비스 경유) */
@RestController
@RequiredArgsConstructor
public class ApsApiController {

    private static final String PLAN = "/api/aps/plans";
    private static final String PIN = "/api/aps/pins";

    private final ApsService service;
    private final ApsApplyService applies;
    private final ApsCompareService compares;
    private final ApsPinService pins;
    private final MessageSource messages;
    private final ApsCtpService ctps;
    private final ApsPrService prs;
    private final ApsDispatchService dispatch;
    private final GridExcel gridExcel;

    @GetMapping(PLAN + "/plants")
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(service.plants());
    }

    @GetMapping(PLAN)
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<PlanList> list(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.list(plantCd, from, to));
    }

    /** grid(UI 단계 3, docs/29 #45) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로(비교 선택지가 쓴다) */
    @GetMapping(PLAN + "/grid")
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로(docs/29 #47)
        String[] sort = request.getParameterValues("sort");
        PlanPage p = service.grid(plantCd, from, to, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(PLAN + "/columns")
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(PLAN + "/export")
    @ProgAuth(prog = ApsService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ApsService.PROG, service.gridColumns(), q -> {
            var p = service.grid(q.str("plantCd"), q.str("from"), q.str("to"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(PLAN + "/{planId}")
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<PlanDetail> detail(@PathVariable long planId) {
        return ApiResponse.ok(service.detail(planId));
    }

    @GetMapping(PLAN + "/{planId}/gantt")
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<Gantt> gantt(@PathVariable long planId) {
        return ApiResponse.ok(service.gantt(planId));
    }

    /** CTP 납기 답변(6단계, docs/38 15장) — 읽기 전용(계획·지시 무변경, 저장 없음)이라 조회 권한 */
    @PostMapping("/api/aps/ctp")
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<CtpResult> ctp(@RequestBody CtpForm form) {
        return ApiResponse.ok(ctps.answer(form));
    }

    @PostMapping(PLAN)
    @ProgAuth(prog = ApsService.PROG, action = Action.SAVE)
    public ApiResponse<Long> calculate(@RequestBody PlanForm form) {
        return ApiResponse.ok(service.calculate(form));
    }

    /** 반영 미리보기(2단계) — 바뀔 지시·설비, 낡음 사유 */
    @GetMapping(PLAN + "/{planId}/apply")
    @ProgAuth(prog = ApsService.PROG, action = Action.APPROVE)
    public ApiResponse<ApplyPreview> applyPreview(@PathVariable long planId) {
        return ApiResponse.ok(applies.preview(planId));
    }

    /** 반영 — 지시 일정·설비 배정(MES 서비스 경유). 서비스가 prd-wo SAVE 도 확인 */
    @PostMapping(PLAN + "/{planId}/apply")
    @ProgAuth(prog = ApsService.PROG, action = Action.APPROVE)
    public ApiResponse<ApplyResult> apply(@PathVariable long planId, @RequestBody ApplyForm form) {
        return ApiResponse.ok(applies.apply(planId, form));
    }

    /** 같은 공장 두 계획 비교 */
    @GetMapping(PLAN + "/{planId}/compare")
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<Compare> compare(@PathVariable long planId, @RequestParam(required = false) Long other) {
        return ApiResponse.ok(compares.compare(planId, other));
    }

    // ---------- 수동 고정 (3단계, docs/38 12장) ----------

    @GetMapping(PIN)
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PinRow>> pins(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(pins.list(plantCd));
    }

    @PostMapping(PIN)
    @ProgAuth(prog = ApsService.PROG, action = Action.SAVE)
    public ApiResponse<PinSaved> pin(@RequestBody PinForm form) {
        return ApiResponse.ok(pins.create(form));
    }

    @PutMapping(PIN + "/{pinId}")
    @ProgAuth(prog = ApsService.PROG, action = Action.SAVE)
    public ApiResponse<PinSaved> movePin(@PathVariable long pinId, @RequestBody PinMoveForm form) {
        return ApiResponse.ok(pins.move(pinId, form));
    }

    @PostMapping(PIN + "/{pinId}/release")
    @ProgAuth(prog = ApsService.PROG, action = Action.SAVE)
    public ApiResponse<String> releasePin(@PathVariable long pinId, @RequestBody PinMoveForm form) {
        pins.release(pinId, form);
        return ApiResponse.ok("OK");
    }

    // ---------- 일일 작업 배정 (9단계, docs/38 18.5) ----------

    /** 현행 계획(공장의 마지막 반영 계획) 배정표 — 읽기만. day 비우면 공장 오늘 */
    @GetMapping("/api/aps/dispatch")
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<DispatchDtos.Board> dispatch(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String day) {
        return ApiResponse.ok(dispatch.board(plantCd, day));
    }

    // ---------- 부족 → 구매 요청 초안 (8단계, docs/38 17.4) ----------

    /** 미리보기 — 쓰지 않는다(조회 권한) */
    @PostMapping(PLAN + "/{planId}/pr/preview")
    @ProgAuth(prog = ApsService.PROG, action = Action.SEARCH)
    public ApiResponse<ApsPrDtos.PrPreview> prPreview(@PathVariable long planId, @RequestBody ApsPrDtos.PrPickForm form) {
        return ApiResponse.ok(prs.preview(planId, form));
    }

    /** 구매 요청 초안 1건(PrService 경유) — 서비스가 srm-pr SAVE 도 확인 */
    @PostMapping(PLAN + "/{planId}/pr")
    @ProgAuth(prog = ApsService.PROG, action = Action.SAVE)
    public ApiResponse<ApsPrDtos.PrCreated> prCreate(@PathVariable long planId, @RequestBody ApsPrDtos.PrPickForm form) {
        return ApiResponse.ok(prs.create(planId, form));
    }

    @PostMapping(PLAN + "/{planId}/discard")
    @ProgAuth(prog = ApsService.PROG, action = Action.SAVE)
    public ApiResponse<String> discard(@PathVariable long planId, @RequestBody DiscardForm form) {
        service.discard(planId, form);
        return ApiResponse.ok("OK");
    }
}
