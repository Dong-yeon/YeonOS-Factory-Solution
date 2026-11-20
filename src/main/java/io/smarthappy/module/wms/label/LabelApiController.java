package io.smarthappy.module.wms.label;

import java.time.LocalDate;
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

import io.smarthappy.module.wms.label.LabelDtos.JobQuery;
import io.smarthappy.module.wms.label.LabelDtos.JobRow;
import io.smarthappy.module.wms.label.LabelDtos.PreviewRequest;
import io.smarthappy.module.wms.label.LabelDtos.PreviewResult;
import io.smarthappy.module.wms.label.LabelDtos.PrinterForm;
import io.smarthappy.module.wms.label.LabelDtos.PrinterRow;
import io.smarthappy.module.wms.label.LabelDtos.ReprintForm;
import io.smarthappy.module.wms.label.LabelDtos.SampleView;
import io.smarthappy.module.wms.label.LabelDtos.TestPrintRequest;
import io.smarthappy.module.wms.label.LabelDtos.TplForm;
import io.smarthappy.module.wms.label.LabelDtos.TplRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.grid.ColumnSpec;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 라벨 템플릿·프린터(wms-label)·인쇄 이력(wms-print-job) API — P4 W2. 삭제 없음(사용 중지) */
@RestController
@RequiredArgsConstructor
public class LabelApiController {

    private static final String TPL = "/api/wms/labels";
    private static final String PRN = "/api/wms/printers";
    private static final String JOB = "/api/wms/print-jobs";

    static final List<ColumnSpec> JOB_COLUMNS = List.of(
            ColumnSpec.of("jobId", "wmsJob.col.jobId", ColumnSpec.ColumnType.INT, 80),
            ColumnSpec.of("requestedAt", "wmsJob.col.requestedAt", ColumnSpec.ColumnType.DATETIME, 150).withSort(),
            ColumnSpec.code("printState", "wmsJob.col.printState", "WMS_PRINT_STATE", 100),
            ColumnSpec.code("status", "wmsJob.col.status", "WMS_PRINT_STATUS", 90).withPriority(3),
            ColumnSpec.code("labelType", "wmsJob.col.labelType", "WMS_LABEL_TYPE", 90),
            ColumnSpec.text("bizKey", "wmsJob.col.bizKey", 200).withFlex().withMinWidth(120),
            ColumnSpec.text("printerCd", "wmsJob.col.printerCd", 110),
            ColumnSpec.text("tplCd", "wmsJob.col.tplCd", 110),
            ColumnSpec.of("copies", "wmsJob.col.copies", ColumnSpec.ColumnType.INT, 60),
            ColumnSpec.of("testYn", "wmsJob.col.testYn", ColumnSpec.ColumnType.YN, 60),
            ColumnSpec.of("reprintOfId", "wmsJob.col.reprintOfId", ColumnSpec.ColumnType.INT, 80),
            ColumnSpec.text("requestedBy", "wmsJob.col.requestedBy", 100),
            ColumnSpec.text("lastError", "wmsJob.col.lastError", 200));

    private final LabelService labels;
    private final io.smarthappy.module.wms.loc.LocAttrService locs;
    private final LabelPrintService print;
    private final MessageSource messages;
    private final PrintTrackService track;
    private final ZebraProbe zebra;
    private final GridExcel gridExcel;
    private static final String RULE = "/api/wms/label-rules";

    // ---------------------------------------------------------------- 템플릿

    @GetMapping(TPL)
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TplRow>> templates(@RequestParam(required = false) String labelType,
            @RequestParam(required = false) String useYn) {
        return ApiResponse.ok(labels.templates(labelType, useYn));
    }

    /** 템플릿 그리드(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort·total */
    @GetMapping(TPL + "/grid")
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> tplGrid(@RequestParam(required = false) String labelType, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] raw = request.getParameterValues("sort");                   // 쉼표째(List 바인딩은 쪼갠다)
        LabelService.Page<?> p = labels.tplGrid(labelType, useYn, page, size, raw == null ? null : List.of(raw), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(TPL + "/columns")
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> tplColumns(Locale locale) {
        List<Map<String, Object>> cols = labels.tplColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(TPL + "/export")
    @ProgAuth(prog = LabelService.PROG, action = Action.EXCEL)
    public void tplExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, LabelService.PROG, labels.tplColumns(), q -> {
            var p = labels.tplGrid(q.str("labelType"), q.str("useYn"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(TPL + "/{id}")
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<TplRow> template(@PathVariable long id) {
        return ApiResponse.ok(labels.template(id));
    }

    @GetMapping(TPL + "/sample")
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<SampleView> sample(@RequestParam(required = false) String labelType) {
        return ApiResponse.ok(labels.sample(labelType));
    }

    @PostMapping(TPL + "/preview")
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<PreviewResult> preview(@RequestBody PreviewRequest req, Locale locale) {
        return ApiResponse.ok(labels.preview(req).withTexts(w -> warnText(w, locale)));
    }

    /** 경고 "CODE:인자" → wmsLabel.warn.CODE 문구(인자 {0}) — 모르는 코드는 코드 그대로 */
    String warnText(String w, Locale locale) {
        int c = w.indexOf(':');
        String code = c < 0 ? w : w.substring(0, c);
        String arg = c < 0 ? "" : w.substring(c + 1);
        return messages.getMessage("wmsLabel.warn." + code, new Object[] {arg}, w, locale);
    }

    @PostMapping(TPL)
    @ProgAuth(prog = LabelService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createTemplate(@RequestBody TplForm form) {
        return ApiResponse.ok(labels.createTemplate(form));
    }

    @PutMapping(TPL + "/{id}")
    @ProgAuth(prog = LabelService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateTemplate(@PathVariable long id, @RequestBody TplForm form) {
        labels.updateTemplate(id, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(TPL + "/{id}/test-print")
    @ProgAuth(prog = LabelService.PROG, action = Action.PRINT)
    public ApiResponse<Long> testPrint(@PathVariable long id, @RequestBody TestPrintRequest req) {
        return ApiResponse.ok(labels.testPrint(id, req));
    }

    // ---------------------------------------------------------------- 프린터

    @GetMapping(PRN)
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PrinterRow>> printers(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String useYn) {
        return ApiResponse.ok(labels.printers(plantCd, useYn));
    }

    /** 프린터 그리드(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort·total */
    @GetMapping(PRN + "/grid")
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> printerGrid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] raw = request.getParameterValues("sort");                   // 쉼표째(List 바인딩은 쪼갠다)
        LabelService.Page<?> p = labels.printerGrid(plantCd, useYn, page, size, raw == null ? null : List.of(raw), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(PRN + "/columns")
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> printerColumns(Locale locale) {
        List<Map<String, Object>> cols = labels.printerColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(PRN + "/export")
    @ProgAuth(prog = LabelService.PROG, action = Action.EXCEL)
    public void printerExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, LabelService.PROG, labels.printerColumns(), q -> {
            var p = labels.printerGrid(q.str("plantCd"), q.str("useYn"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    /** 프린터 공장 선택지 — 로케이션 화면 권한 없이 */
    @GetMapping(PRN + "/plants")
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<List<io.smarthappy.module.wms.loc.LocAttrDtos.PlantRow>> plants() {
        return ApiResponse.ok(locs.plants());
    }

    @PostMapping(PRN)
    @ProgAuth(prog = LabelService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createPrinter(@RequestBody PrinterForm form) {
        return ApiResponse.ok(labels.createPrinter(form));
    }

    @PutMapping(PRN + "/{id}")
    @ProgAuth(prog = LabelService.PROG, action = Action.SAVE)
    public ApiResponse<String> updatePrinter(@PathVariable long id, @RequestBody PrinterForm form) {
        labels.updatePrinter(id, form);
        return ApiResponse.ok("OK");
    }

    // ---------------------------------------------------------------- 인쇄 이력

    @GetMapping(JOB)
    @ProgAuth(prog = LabelPrintService.PROG_JOB, action = Action.SEARCH)
    public ApiResponse<Void> jobs(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String labelType, @RequestParam(required = false) String status,
            @RequestParam(required = false) String bizKey, @RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String printState,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개 한 키 정렬이 400 이 된다. 원래 값 그대로 읽는다(CustApiController 와 같음)
        String[] raw = request.getParameterValues("sort");
        List<String> sort = raw == null ? null : List.of(raw);
        LabelPrintService.Page p = print.jobs(new JobQuery(from, to, labelType, status, bizKey, plantCd, printState), page, size, sort,
                total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(JOB + "/columns")
    @ProgAuth(prog = LabelPrintService.PROG_JOB, action = Action.SEARCH)
    public ApiResponse<Void> jobColumns(Locale locale) {
        List<Map<String, Object>> cols = JOB_COLUMNS.stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(JOB + "/export")
    @ProgAuth(prog = LabelPrintService.PROG_JOB, action = Action.EXCEL)
    public void jobsExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, LabelPrintService.PROG_JOB, JOB_COLUMNS, q -> {
            var p = print.jobs(new JobQuery(q.date("from"), q.date("to"), q.str("labelType"), q.str("status"), q.str("bizKey"), q.str("plantCd"), q.str("printState")), q.page(),
                    q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(JOB + "/{id}")
    @ProgAuth(prog = LabelPrintService.PROG_JOB, action = Action.SEARCH)
    public ApiResponse<JobRow> job(@PathVariable long id) {
        return ApiResponse.ok(print.job(id));
    }

    /** 재발행 화면의 프린터 선택지 — 라벨 관리 권한 없이도 사용 중 프린터는 보인다 */
    @GetMapping(JOB + "/printers")
    @ProgAuth(prog = LabelPrintService.PROG_JOB, action = Action.SEARCH)
    public ApiResponse<List<PrinterRow>> jobPrinters() {
        return ApiResponse.ok(labels.printers(null, "Y"));
    }

    @PostMapping(JOB + "/{id}/reprint")
    @ProgAuth(prog = LabelPrintService.PROG_JOB, action = Action.PRINT)
    public ApiResponse<Long> reprint(@PathVariable long id, @RequestBody ReprintForm form) {
        return ApiResponse.ok(print.reprint(id, form));
    }

    // ---------------------------------------------------------------- 인쇄 상태 3단 (docs/65 18.1)

    @GetMapping(JOB + "/{id}/events")
    @ProgAuth(prog = LabelPrintService.PROG_JOB, action = Action.SEARCH)
    public ApiResponse<List<PrintTrackDtos.EventRow>> events(@PathVariable long id) {
        return ApiResponse.ok(track.events(id));
    }

    /** 검증 스캔 — 같으면 VERIFIED, 다르면 VERIFY_NG 사건(상태 그대로) */
    @PostMapping(JOB + "/{id}/verify")
    @ProgAuth(prog = LabelPrintService.PROG_JOB, action = Action.PRINT)
    public ApiResponse<PrintTrackDtos.VerifyResult> verify(@PathVariable long id, @RequestBody PrintTrackDtos.VerifyForm form) {
        return ApiResponse.ok(track.verify(id, form, "SCAN"));
    }

    /** 결과 불명·프린터 오류 작업을 사람이 닫는다(PRINTED / CANCELLED + 사유) */
    @PostMapping(JOB + "/{id}/resolve")
    @ProgAuth(prog = LabelPrintService.PROG_JOB, action = Action.PRINT)
    public ApiResponse<String> resolve(@PathVariable long id, @RequestBody PrintTrackDtos.ResolveForm form) {
        return ApiResponse.ok(print.resolve(id, form));
    }

    /** 인쇄 에이전트 후보 — 시스템관리에서 키를 발급한 장치(docs/65 19.1) */
    @GetMapping(PRN + "/agents")
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<List<Map<String, Object>>> agents() {
        return ApiResponse.ok(labels.agentOptions());
    }

    /** 다른 프린터로 넘기기(docs/65 19.2) — 아직 안 가져간·결과 불명·오류·실패 작업만, 원 작업은 닫고 같은 ZPL 로 새 작업 */
    @PostMapping(JOB + "/{id}/move")
    @ProgAuth(prog = LabelPrintService.PROG_JOB, action = Action.PRINT)
    public ApiResponse<Long> move(@PathVariable long id, @RequestBody PrintTrackDtos.MoveForm form) {
        return ApiResponse.ok(print.move(id, form));
    }

    /** 설치형 프린터 "지금 확인" — TCP 는 트랜잭션 밖, 반영은 새 트랜잭션 */
    @PostMapping(PRN + "/{id}/probe")
    @ProgAuth(prog = LabelService.PROG, action = Action.SAVE)
    public ApiResponse<PrintTrackDtos.ApplyResult> probe(@PathVariable long id) {
        PrinterRow p = labels.printer(id);
        if (!"DIRECT".equals(p.getConnMode())) {
            throw new io.smarthappy.platform.common.web.BusinessException(io.smarthappy.platform.common.web.HbErrorCode.CONFLICT);
        }
        return ApiResponse.ok(track.applyNow(id, zebra.probe(p.getHost(), p.getPort()), "DIRECT"));
    }

    // ---------------------------------------------------------------- 템플릿 선택 규칙 (docs/65 18.4)

    @GetMapping(RULE)
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<List<LabelDtos.RuleRow>> rules(@RequestParam(required = false) String labelType,
            @RequestParam(required = false) String useYn) {
        return ApiResponse.ok(labels.rules(labelType, useYn));
    }

    /** 규칙 대상 고르기 — scope = CUST|ITEM, q = 코드·이름 일부 */
    @GetMapping(RULE + "/targets")
    @ProgAuth(prog = LabelService.PROG, action = Action.SEARCH)
    public ApiResponse<List<Map<String, Object>>> ruleTargets(@RequestParam(required = false) String scope,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(labels.ruleTargets(scope, q));
    }

    @PostMapping(RULE)
    @ProgAuth(prog = LabelService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createRule(@RequestBody LabelDtos.RuleForm form) {
        return ApiResponse.ok(labels.createRule(form));
    }

    @PutMapping(RULE + "/{id}")
    @ProgAuth(prog = LabelService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateRule(@PathVariable long id, @RequestBody LabelDtos.RuleForm form) {
        labels.updateRule(id, form);
        return ApiResponse.ok("OK");
    }
}
