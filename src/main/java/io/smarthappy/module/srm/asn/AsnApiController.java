package io.smarthappy.module.srm.asn;

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

import io.smarthappy.module.srm.asn.AsnDtos.ActionForm;
import io.smarthappy.module.srm.asn.AsnDtos.AsnDetail;
import io.smarthappy.module.srm.asn.AsnDtos.AsnForm;
import io.smarthappy.module.srm.asn.AsnDtos.AsnPage;
import io.smarthappy.module.srm.asn.AsnDtos.AsnRow;
import io.smarthappy.module.srm.asn.AsnDtos.PlantOption;
import io.smarthappy.module.srm.asn.AsnDtos.PoLineOption;
import io.smarthappy.module.srm.asn.AsnDtos.SupplierRef;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 사전 납품 통지 API (SRM 2부 R3a, docs/31 4.11) — 목록·상세·한 벌 저장·확정·취소·마감. 납품서 인쇄 화면(srm-asn-print)도 상세(SEARCH)를 읽는다 */
@RestController
@RequiredArgsConstructor
public class AsnApiController {

    private static final String API = "/api/srm/asns";
    private static final String PROG = AsnService.PROG;

    private final AsnService service;
    private final AsnMapper mapper;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(API + "/plants")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<PlantOption>> plants() {
        return ApiResponse.ok(mapper.findPlants());
    }

    @GetMapping(API + "/suppliers")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<SupplierRef>> suppliers() {
        return ApiResponse.ok(mapper.findSuppliers());
    }

    /** 고를 수 있는 발주 행(협력사·공장) — 고치는 중이면 asnId 로 그 ASN 을 "다른 ASN" 에서 뺀다 */
    @GetMapping(API + "/po-lines")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<PoLineOption>> poLines(@RequestParam(required = false) String custCd,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) Long asnId) {
        return ApiResponse.ok(service.poLines(custCd, plantCd, asnId));
    }

    @GetMapping(API)
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<AsnRow>> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) boolean late) {
        return ApiResponse.ok(service.list(from, to, status, keyword, late));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건(늦은 ASN 만 포함) + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(API + "/grid")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) boolean late, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        AsnPage p = service.grid(from, to, status, keyword, late, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(API + "/columns")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(API + "/export")
    @ProgAuth(prog = PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, PROG, service.gridColumns(), q -> {
            var p = service.grid(q.str("from"), q.str("to"), q.str("status"), q.str("keyword"), Boolean.TRUE.equals(q.bool("late")), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(API + "/{asnId}")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<AsnDetail> get(@PathVariable long asnId) {
        return ApiResponse.ok(service.get(asnId));
    }

    @PostMapping(API)
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody AsnForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(API + "/{asnId}")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long asnId, @RequestBody AsnForm form) {
        service.update(asnId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{asnId}/send")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> send(@PathVariable long asnId, @RequestBody ActionForm form) {
        service.send(asnId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{asnId}/cancel")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long asnId, @RequestBody ActionForm form) {
        service.cancel(asnId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(API + "/{asnId}/close")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> close(@PathVariable long asnId, @RequestBody ActionForm form) {
        service.close(asnId, form);
        return ApiResponse.ok("OK");
    }
}
