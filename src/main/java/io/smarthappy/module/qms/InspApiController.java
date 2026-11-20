package io.smarthappy.module.qms;

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

import io.smarthappy.module.qms.InspDtos.ActionForm;
import io.smarthappy.module.qms.InspDtos.CorrectForm;
import io.smarthappy.module.qms.InspDtos.CreateForm;
import io.smarthappy.module.qms.InspDtos.GaugeOption;
import io.smarthappy.module.qms.InspDtos.InspDetail;
import io.smarthappy.module.qms.InspDtos.InspPage;
import io.smarthappy.module.qms.InspDtos.InspRow;
import io.smarthappy.module.qms.InspDtos.MeasForm;
import io.smarthappy.module.qms.InspDtos.OffPair;
import io.smarthappy.module.qms.InspDtos.VoidMeasForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 검사 실행 API (P3 QMS 2부 Q6, docs/29_P3 12.2) — 만들기·측정·판정 확정 = SAVE, 검사 무효 = DELETE */
@RestController
@RequiredArgsConstructor
public class InspApiController {

    private static final String I = "/api/qms/insps";

    private final InspService insps;
    private final MessageSource messages;
    private final OffInspService offs;
    private final GridExcel gridExcel;

    @GetMapping(I)
    @ProgAuth(prog = InspService.PROG, action = Action.SEARCH)
    public ApiResponse<List<InspRow>> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String inspType,
            @RequestParam(required = false) String status, @RequestParam(required = false) String result) {
        return ApiResponse.ok(insps.list(keyword, inspType, status, result));
    }

    /** 그리드(UI 단계 3) — 목록과 같은 조건 + page·size·sort. sort 는 원래 값 그대로(List 로 받으면 스프링이 쉼표로 쪼갠다, 결정 #47) */
    @GetMapping(I + "/grid")
    @ProgAuth(prog = InspService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String inspType,
            @RequestParam(required = false) String status, @RequestParam(required = false) String result,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        InspPage p = insps.grid(keyword, inspType, status, result, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(I + "/columns")
    @ProgAuth(prog = InspService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = insps.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(I + "/export")
    @ProgAuth(prog = InspService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, InspService.PROG, insps.gridColumns(), q -> {
            var p = insps.grid(q.str("keyword"), q.str("inspType"), q.str("status"), q.str("result"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(I + "/{inspId}")
    @ProgAuth(prog = InspService.PROG, action = Action.SEARCH)
    public ApiResponse<InspDetail> detail(@PathVariable long inspId) {
        return ApiResponse.ok(insps.detail(inspId));
    }

    /** 같은 지시·공정의 초품·종품 판정(docs/29_P3 27장) */
    @GetMapping(I + "/{inspId}/off-pair")
    @ProgAuth(prog = InspService.PROG, action = Action.SEARCH)
    public ApiResponse<OffPair> offPair(@PathVariable long inspId) {
        return ApiResponse.ok(offs.pair(inspId));
    }

    @GetMapping(I + "/gauges")
    @ProgAuth(prog = InspService.PROG, action = Action.SEARCH)
    public ApiResponse<List<GaugeOption>> gauges(@RequestParam(required = false) String gaugeClass) {
        return ApiResponse.ok(insps.gaugeOptions(gaugeClass));
    }

    @PostMapping(I)
    @ProgAuth(prog = InspService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody CreateForm form) {
        return ApiResponse.ok(insps.create(form));
    }

    @PostMapping(I + "/{inspId}/meas")
    @ProgAuth(prog = InspService.PROG, action = Action.SAVE)
    public ApiResponse<String> measure(@PathVariable long inspId, @RequestBody MeasForm form) {
        insps.measure(inspId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(I + "/{inspId}/meas/void")
    @ProgAuth(prog = InspService.PROG, action = Action.SAVE)
    public ApiResponse<String> voidMeas(@PathVariable long inspId, @RequestBody VoidMeasForm form) {
        insps.voidMeas(inspId, form);
        return ApiResponse.ok("OK");
    }

    /** 측정값 정정(docs/72 3.3) — 무효 행 + 같은 샘플 번호의 새 값을 한 트랜잭션으로 */
    @PostMapping(I + "/{inspId}/meas/correct")
    @ProgAuth(prog = InspService.PROG, action = Action.SAVE)
    public ApiResponse<String> correctMeas(@PathVariable long inspId, @RequestBody CorrectForm form) {
        insps.correct(inspId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(I + "/{inspId}/judge")
    @ProgAuth(prog = InspService.PROG, action = Action.SAVE)
    public ApiResponse<String> judge(@PathVariable long inspId, @RequestBody ActionForm form) {
        insps.judge(inspId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(I + "/{inspId}/void")
    @ProgAuth(prog = InspService.PROG, action = Action.DELETE)
    public ApiResponse<String> voidInsp(@PathVariable long inspId, @RequestBody ActionForm form) {
        insps.voidInsp(inspId, form);
        return ApiResponse.ok("OK");
    }
}
