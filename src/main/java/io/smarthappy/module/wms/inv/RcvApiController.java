package io.smarthappy.module.wms.inv;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.srm.link.AsnQuery;
import io.smarthappy.module.wms.inv.InvDtos.ItemOpt;
import io.smarthappy.module.wms.inv.InvDtos.NodeOpt;
import io.smarthappy.module.wms.inv.InvDtos.PoLineOpt;
import io.smarthappy.module.wms.inv.InvDtos.PrintOpt;
import io.smarthappy.module.wms.inv.InvDtos.RcvForm;
import io.smarthappy.module.wms.inv.InvDtos.RcvResult;
import io.smarthappy.module.wms.inv.InvDtos.RcvScan;
import io.smarthappy.module.wms.inv.InvDtos.ScanForm;
import io.smarthappy.module.wms.inv.InvDtos.TrxRow;
import io.smarthappy.module.wms.loc.LocAttrDtos.PlantRow;
import io.smarthappy.module.wms.loc.LocAttrService;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 자재·구매 입고(wms-rcv) API — P4 2부 B4a·B4b (docs/30 12.3·12.6). 정정은 수불 원장 화면의 역분개(구매 입고는 SRM 입고 수량도 되돌림) */
@RestController
@RequiredArgsConstructor
public class RcvApiController {

    private static final String BASE = "/api/wms/rcv";

    private final MaterialReceiptService service;
    private final StockService stock;
    private final LocAttrService locs;

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(locs.plants());
    }

    @GetMapping(BASE + "/nodes")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<List<NodeOpt>> nodes(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(stock.nodes(plantCd));
    }

    @GetMapping(BASE + "/item")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<ItemOpt> item(@RequestParam(required = false) String itemCd) {
        return ApiResponse.ok(stock.item(itemCd));
    }

    @PostMapping(BASE + "/scan")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<RcvScan> scan(@RequestBody ScanForm form) {
        return ApiResponse.ok(service.scan(form));
    }

    @GetMapping(BASE + "/printers")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PrintOpt>> printers(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.printers(plantCd));
    }

    @GetMapping(BASE + "/templates")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PrintOpt>> templates() {
        return ApiResponse.ok(service.templates());
    }

    /** 오늘 이 공장 자재 입고(최근 50건) */
    @GetMapping(BASE + "/recent")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> recent(@RequestParam(required = false) String plantCd) {
        List<TrxRow> list = service.recent(plantCd);
        return ApiResponse.list(list, list.size());
    }

    /** 지금 받으면 검사 대기(QC)로 고정되는 품목인가 — 화면이 상태 칸을 QC 로 묶고 안내한다 */
    @GetMapping(BASE + "/qc-fixed")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<Boolean> qcFixed(@RequestParam(required = false) String plantCd, @RequestParam(required = false) Long itemId) {
        return ApiResponse.ok(service.qcFixed(plantCd, itemId));
    }

    /** 열린 발주 행(SRM 없는 설치본은 enabled = false·빈 목록 — 화면이 발주 칸을 숨긴다) */
    @GetMapping(BASE + "/po-lines")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<PoLines> poLines(@RequestParam(required = false) String plantCd, @RequestParam(required = false) Long itemId) {
        boolean on = service.poEnabled();
        return ApiResponse.ok(new PoLines(on, on ? service.poLines(plantCd, itemId) : List.of()));
    }

    public record PoLines(boolean enabled, List<PoLineOpt> lines) {
    }

    /** 도착 예정·입고 중 ASN(SRM 없는 설치본은 빈 목록 — 화면은 발주 칸과 함께 숨긴다) */
    @GetMapping(BASE + "/asns")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<List<AsnQuery.AsnHead>> asns(@RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(service.poEnabled() ? service.asns(plantCd) : List.of());
    }

    /** ASN 한 건(번호·ASN: 바코드·협력사 납품서 번호) */
    @GetMapping(BASE + "/asn")
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SEARCH)
    public ApiResponse<AsnQuery.Asn> asn(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String key) {
        return ApiResponse.ok(service.asn(plantCd, key));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = MaterialReceiptService.PROG, action = Action.SAVE)
    public ApiResponse<RcvResult> receive(@RequestBody RcvForm form) {
        return ApiResponse.ok(service.receive(form));
    }
}
