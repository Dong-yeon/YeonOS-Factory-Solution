package io.smarthappy.module.master.workforce;

import java.time.LocalDate;
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

import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.BadgeForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.PinForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.GateResult;
import io.smarthappy.module.master.workforce.WorkforceDtos.QualForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.QualRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.ReqForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.ReqRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkDayView;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerQualForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerQualRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 근무조·자격·작업자 API (P2 1단계, docs/25 4.2) */
@RestController
@RequiredArgsConstructor
public class WorkforceApiController {

    private static final String SHIFT = "/api/master/shifts";
    private static final String QUAL = "/api/master/quals";
    private static final String WORKER = "/api/master/workers";

    private final ShiftService shifts;
    private final QualService quals;
    private final WorkerService workers;
    private final QualGate gate;
    private final WorkforceMapper mapper;
    private final BizClock clock;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    // ------------------------------------------------------------------ 근무조

    @GetMapping(SHIFT + "/plants")
    @ProgAuth(prog = ShiftService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<PlantRow>> shiftPlants() {
        return ApiResponse.ok(mapper.findPlants());
    }

    @GetMapping(SHIFT)
    @ProgAuth(prog = ShiftService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ShiftRow>> shifts(@RequestParam String plantCd) {
        return ApiResponse.ok(shifts.list(plantCd));
    }

    @PostMapping(SHIFT)
    @ProgAuth(prog = ShiftService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createShift(@RequestBody ShiftForm form) {
        return ApiResponse.ok(shifts.create(form));
    }

    @PutMapping(SHIFT + "/{shiftId}")
    @ProgAuth(prog = ShiftService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateShift(@PathVariable long shiftId, @RequestBody ShiftForm form) {
        shifts.update(shiftId, form);
        return ApiResponse.ok("OK");
    }

    /** 공장 현지 시각 → 작업일·근무조 (확인 칸) */
    /** 그리드(UI 단계 3 ①) — 기존 목록 API 는 그대로, 같은 조건(공장 필수) + 페이지·정렬·건수 */
    @GetMapping(SHIFT + "/grid")
    @ProgAuth(prog = ShiftService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> shiftGrid(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        ShiftService.Page p = shifts.grid(plantCd, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(SHIFT + "/columns")
    @ProgAuth(prog = ShiftService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> shiftColumns(Locale locale) {
        List<Map<String, Object>> cols = shifts.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(SHIFT + "/export")
    @ProgAuth(prog = ShiftService.PROG, action = Action.EXCEL)
    public void shiftExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ShiftService.PROG, shifts.columns(), q -> {
            var p = shifts.grid(q.str("plantCd"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(SHIFT + "/work-day")
    @ProgAuth(prog = ShiftService.PROG, action = Action.SEARCH)
    public ApiResponse<WorkDayView> workDay(@RequestParam String plantCd, @RequestParam(required = false) String at) {
        return ApiResponse.ok(shifts.resolve(plantCd, at));
    }

    // ------------------------------------------------------------------ 자격

    @GetMapping(QUAL)
    @ProgAuth(prog = QualService.PROG, action = Action.SEARCH)
    public ApiResponse<List<QualRow>> quals(@RequestParam(required = false) String useYn) {
        return ApiResponse.ok(quals.list(useYn));
    }

    /** 그리드(UI 단계 3 ①) — 기존 목록 API 는 그대로(작업자 화면 자격 고르기도 씀), 같은 조건 + 페이지·정렬·건수 */
    @GetMapping(QUAL + "/grid")
    @ProgAuth(prog = QualService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> qualGrid(@RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        QualService.Page p = quals.grid(useYn, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(QUAL + "/columns")
    @ProgAuth(prog = QualService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> qualColumns(Locale locale) {
        List<Map<String, Object>> cols = quals.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(QUAL + "/export")
    @ProgAuth(prog = QualService.PROG, action = Action.EXCEL)
    public void qualExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, QualService.PROG, quals.columns(), q -> {
            var p = quals.grid(q.str("useYn"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @PostMapping(QUAL)
    @ProgAuth(prog = QualService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createQual(@RequestBody QualForm form) {
        return ApiResponse.ok(quals.create(form));
    }

    @PutMapping(QUAL + "/{qualId}")
    @ProgAuth(prog = QualService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateQual(@PathVariable long qualId, @RequestBody QualForm form) {
        quals.update(qualId, form);
        return ApiResponse.ok("OK");
    }

    @GetMapping(QUAL + "/procs")
    @ProgAuth(prog = QualService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<WorkforceDtos.ProcOption>> qualProcs() {
        return ApiResponse.ok(mapper.findActiveProcs());
    }

    @GetMapping(QUAL + "/reqs")
    @ProgAuth(prog = QualService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ReqRow>> reqs(@RequestParam(required = false) Long qualId,
            @RequestParam(required = false) String procCd) {
        return ApiResponse.ok(quals.reqs(qualId, procCd));
    }

    @PostMapping(QUAL + "/reqs")
    @ProgAuth(prog = QualService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createReq(@RequestBody ReqForm form) {
        return ApiResponse.ok(quals.createReq(form));
    }

    @PutMapping(QUAL + "/reqs/{reqId}")
    @ProgAuth(prog = QualService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateReq(@PathVariable long reqId, @RequestBody ReqForm form) {
        quals.updateReq(reqId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 작업자

    @GetMapping(WORKER + "/plants")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<PlantRow>> workerPlants() {
        return ApiResponse.ok(mapper.findPlants());
    }

    /** 작업자 화면의 자격 선택지(사용 중 자격) — 자격 화면 권한 없이 */
    @GetMapping(WORKER + "/quals")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SEARCH)
    public ApiResponse<List<QualRow>> workerQualOptions() {
        return ApiResponse.ok(quals.list("Y"));
    }

    @GetMapping(WORKER + "/procs")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<WorkforceDtos.ProcOption>> workerProcs() {
        return ApiResponse.ok(mapper.findActiveProcs());
    }

    @GetMapping(WORKER)
    @ProgAuth(prog = WorkerService.PROG, action = Action.SEARCH)
    public ApiResponse<List<WorkerRow>> workers(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String useYn) {
        return ApiResponse.ok(workers.list(plantCd, keyword, useYn));
    }

    /** 그리드(UI 단계 3 ①) — 기존 목록 API 는 그대로, 같은 조건 + 페이지·정렬·건수 */
    @GetMapping(WORKER + "/grid")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> workerGrid(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        WorkerService.Page p = workers.grid(plantCd, keyword, useYn, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(WORKER + "/columns")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> workerColumns(Locale locale) {
        List<Map<String, Object>> cols = workers.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(WORKER + "/export")
    @ProgAuth(prog = WorkerService.PROG, action = Action.EXCEL)
    public void workerExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, WorkerService.PROG, workers.columns(), q -> {
            var p = workers.grid(q.str("plantCd"), q.str("keyword"), q.str("useYn"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(WORKER + "/{workerId}")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SEARCH)
    public ApiResponse<WorkerRow> worker(@PathVariable long workerId) {
        return ApiResponse.ok(workers.get(workerId));
    }

    @PostMapping(WORKER)
    @ProgAuth(prog = WorkerService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createWorker(@RequestBody WorkerForm form) {
        return ApiResponse.ok(workers.create(form));
    }

    @PutMapping(WORKER + "/{workerId}")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateWorker(@PathVariable long workerId, @RequestBody WorkerForm form) {
        workers.update(workerId, form);
        return ApiResponse.ok("OK");
    }

    @PutMapping(WORKER + "/{workerId}/badge")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SAVE)
    public ApiResponse<String> badge(@PathVariable long workerId, @RequestBody BadgeForm form) {
        workers.setBadge(workerId, form);
        return ApiResponse.ok("OK");
    }

    @PutMapping(WORKER + "/{workerId}/pin")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SAVE)
    public ApiResponse<String> pin(@PathVariable long workerId, @RequestBody PinForm form) {
        workers.setPin(workerId, form);
        return ApiResponse.ok("OK");
    }

    @GetMapping(WORKER + "/{workerId}/quals")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SEARCH)
    public ApiResponse<List<WorkerQualRow>> workerQuals(@PathVariable long workerId) {
        return ApiResponse.ok(workers.quals(workerId));
    }

    @PostMapping(WORKER + "/{workerId}/quals")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addWorkerQual(@PathVariable long workerId, @RequestBody WorkerQualForm form) {
        return ApiResponse.ok(workers.addQual(workerId, form));
    }

    @PutMapping(WORKER + "/{workerId}/quals/{wqId}")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateWorkerQual(@PathVariable long workerId, @PathVariable long wqId,
            @RequestBody WorkerQualForm form) {
        workers.updateQual(workerId, wqId, form);
        return ApiResponse.ok("OK");
    }

    /** 게이트 미리보기 — 공정 코드 + 공장(비면 작업자 소속) + 작업일(비면 오늘) */
    @GetMapping(WORKER + "/{workerId}/gate")
    @ProgAuth(prog = WorkerService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<GateResult> gate(@PathVariable long workerId, @RequestParam String procCd,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) String workDay) {
        WorkerRow w = workers.get(workerId);
        Long procId = procCd.length() > 20 ? null : mapper.findProcId(procCd.strip().toUpperCase(Locale.ROOT), false);
        if (procId == null) {
            throw ShiftService.fieldError("procCd", "NO_SUCH_CODE");
        }
        String plant = plantCd == null || plantCd.isBlank() ? w.getPlantCd() : plantCd.strip().toUpperCase(Locale.ROOT);
        LocalDate day;
        if (workDay == null || workDay.isBlank()) {
            day = clock.today();
        } else {
            FieldErrors e = FieldErrors.create();
            day = WorkerService.date(workDay, "workDay", true, e);
            if (!e.isEmpty()) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
            }
        }
        return ApiResponse.ok(gate.check(plant, procId, workerId, day));
    }
}
