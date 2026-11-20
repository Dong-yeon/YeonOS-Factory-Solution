package io.smarthappy.module.srm.supplier;

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

import io.smarthappy.module.srm.supplier.SupplierDtos.CertForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.CertRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.ItemOption;
import io.smarthappy.module.srm.supplier.SupplierDtos.PriceForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.PriceRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.QualitySummary;
import io.smarthappy.module.srm.supplier.SupplierDtos.SourceForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.SourceRow;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierForm;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierPage;
import io.smarthappy.module.srm.supplier.SupplierDtos.SupplierRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** 협력사 정보 API (SRM S1, docs/31) — 협력사 · 인증서 · 공급 품목 · 단가 */
@RestController
@RequiredArgsConstructor
public class SupplierApiController {

    private static final String API = "/api/srm/suppliers";
    private static final String PROG = SupplierService.PROG;

    private final SupplierService suppliers;
    private final SourcePriceService sourcePrice;
    private final SupplierQualityService qualityService;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(API)
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<SupplierRow>> list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tradeStatus, @RequestParam(required = false) String registered) {
        return ApiResponse.ok(suppliers.list(keyword, tradeStatus, registered));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(API + "/grid")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String tradeStatus,
            @RequestParam(required = false) String registered, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        SupplierPage p = suppliers.grid(keyword, tradeStatus, registered, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(API + "/columns")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = suppliers.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(API + "/export")
    @ProgAuth(prog = PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, PROG, suppliers.gridColumns(), q -> {
            var p = suppliers.grid(q.str("keyword"), q.str("tradeStatus"), q.str("registered"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    /** 공급 품목·단가 입력용 품목 찾기(사용 중 품목, 50건) — 품목관리 권한 없이 */
    @GetMapping(API + "/items")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<ItemOption>> items(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(sourcePrice.items(keyword));
    }

    @GetMapping(API + "/{custId}")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<SupplierRow> get(@PathVariable long custId) {
        return ApiResponse.ok(suppliers.get(custId));
    }

    /** 품질 요약 카드 — 최근 90일 입고 PPM·처리 중 NCR(QMS 계약) + 열린 클레임·출고 대기 반품 */
    @GetMapping(API + "/{custId}/quality")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<QualitySummary> quality(@PathVariable long custId) {
        return ApiResponse.ok(qualityService.summary(custId));
    }

    /** 구매 거래처를 협력사로 등록 */
    @PostMapping(API)
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody SupplierForm form) {
        return ApiResponse.ok(suppliers.create(form));
    }

    @PutMapping(API + "/{custId}")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long custId, @RequestBody SupplierForm form) {
        suppliers.update(custId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 인증서

    @GetMapping(API + "/{custId}/certs")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<CertRow>> certs(@PathVariable long custId) {
        return ApiResponse.ok(suppliers.certs(custId));
    }

    @PostMapping(API + "/{custId}/certs")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Long> addCert(@PathVariable long custId, @RequestBody CertForm form) {
        return ApiResponse.ok(suppliers.addCert(custId, form));
    }

    @PutMapping(API + "/{custId}/certs/{certId}")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> updateCert(@PathVariable long custId, @PathVariable long certId, @RequestBody CertForm form) {
        suppliers.updateCert(custId, certId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 공급 품목

    @GetMapping(API + "/{custId}/sources")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<SourceRow>> sources(@PathVariable long custId) {
        return ApiResponse.ok(sourcePrice.sources(custId));
    }

    @PostMapping(API + "/{custId}/sources")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Long> addSource(@PathVariable long custId, @RequestBody SourceForm form) {
        return ApiResponse.ok(sourcePrice.addSource(custId, form));
    }

    @PutMapping(API + "/{custId}/sources/{sourceId}")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> updateSource(@PathVariable long custId, @PathVariable long sourceId,
            @RequestBody SourceForm form) {
        sourcePrice.updateSource(custId, sourceId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 단가

    @GetMapping(API + "/{custId}/prices")
    @ProgAuth(prog = PROG, action = Action.SEARCH)
    public ApiResponse<List<PriceRow>> prices(@PathVariable long custId, @RequestParam(required = false) String itemCd) {
        return ApiResponse.ok(sourcePrice.prices(custId, itemCd));
    }

    @PostMapping(API + "/{custId}/prices")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<Long> addPrice(@PathVariable long custId, @RequestBody PriceForm form) {
        return ApiResponse.ok(sourcePrice.addPrice(custId, form));
    }

    @PutMapping(API + "/{custId}/prices/{priceId}")
    @ProgAuth(prog = PROG, action = Action.SAVE)
    public ApiResponse<String> updatePrice(@PathVariable long custId, @PathVariable long priceId,
            @RequestBody PriceForm form) {
        sourcePrice.updatePrice(custId, priceId, form);
        return ApiResponse.ok("OK");
    }
}
