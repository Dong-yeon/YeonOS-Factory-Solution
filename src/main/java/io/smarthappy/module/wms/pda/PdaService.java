package io.smarthappy.module.wms.pda;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.wms.alloc.AllocDtos.PickView;
import io.smarthappy.module.wms.count.CountDtos.CountHead;
import io.smarthappy.module.wms.count.CountDtos.CountView;
import io.smarthappy.module.wms.count.CountDtos.CountsForm;
import io.smarthappy.module.wms.count.CountService;
import io.smarthappy.module.srm.link.AsnQuery;
import io.smarthappy.module.wms.inv.InvDtos.PoLineOpt;
import io.smarthappy.module.wms.inv.InvDtos.PostResult;
import io.smarthappy.module.wms.inv.InvDtos.RcvLoc;
import io.smarthappy.module.wms.inv.InvDtos.RcvResult;
import io.smarthappy.module.wms.inv.InvDtos.RcvScan;
import io.smarthappy.module.wms.inv.MaterialReceiptService;
import io.smarthappy.module.wms.inv.ReturnIssueService;
import io.smarthappy.module.wms.inv.InvDtos.ScanForm;
import io.smarthappy.module.wms.inv.InvDtos.ScanHit;
import io.smarthappy.module.wms.inv.InvDtos.StockQuery;
import io.smarthappy.module.wms.inv.StockService;
import io.smarthappy.module.wms.ship.ShipDtos.PickForm;
import io.smarthappy.module.wms.ship.ShipDtos.ShipHead;
import io.smarthappy.module.wms.ship.ShipDtos.ShipView;
import io.smarthappy.module.wms.ship.ShipService;
import io.smarthappy.module.wms.pda.PdaDtos.MoveForm;
import io.smarthappy.module.wms.pda.PdaDtos.MoveResult;
import io.smarthappy.module.wms.pda.PdaDtos.PdaScan;
import io.smarthappy.module.wms.pda.PdaDtos.StockLine;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * 창고 PDA (B7-1, docs/30 12.12) — 단말(PDA) + 배지 작업자. 재고·실사 규칙은 사무 화면과 같은 서비스(StockService·CountService)에
 * 맡기고 여기서는 ① 작업자 필수 ② 단말 공장 안으로 범위 ③ 기록(performed_by = 작업자, recorded_by = 단말 — TenantContext 행위자)만 정한다.
 */
@Service
@RequiredArgsConstructor
public class PdaService {

    static final int STOCK_MAX = 50;
    static final List<String> METHODS = List.of("SCAN", "KEY");

    private final StockService stock;
    private final CountService counts;
    private final ShipService ships;
    private final ReturnIssueService returns;
    private final io.smarthappy.module.wms.loccheck.LocCheckService locChecks;
    private final MaterialReceiptService receipts;

    static PopWorker require(PopWorker w) {
        if (w == null) {
            throw new BusinessException(HbErrorCode.POP_NO_WORKER);
        }
        return w;
    }

    /** 스캔 — 위치·품목·LOT 판별은 사무 화면과 같은 규칙(ScanKeyResolver), 단말 공장 안. 위치면 그 위치 재고 */
    @Transactional(readOnly = true)
    public PdaScan scan(TerminalPrincipal t, PopWorker w, String text) {
        require(w);
        if (text == null || text.isBlank() || text.length() > 200) {
            throw fieldError("text", "REQUIRED");
        }
        ScanHit hit = stock.scan(new ScanForm(t.plantCd(), text.strip()));
        if (!"LOC".equals(hit.type()) || !hit.ok() || hit.nodeId() == null) {
            return new PdaScan(hit, List.of());
        }
        List<StockLine> lines = stock.stock(new StockQuery(t.plantCd(), hit.nodeId(), false, null, null, null, true, null), 1, STOCK_MAX,
                null, false).list().stream()
                .filter(r -> r.getQty() != null && r.getQty().signum() > 0)
                .map(r -> new StockLine(r.getItemId(), r.getItemCd(), r.getItemNm(), r.getUnitCd(), r.getLotId(), r.getLotNo(),
                        r.getStockStatus(), r.getQty()))
                .toList();
        return new PdaScan(hit, lines);
    }

    /** 이동 — 같은 상태로 위치만. 단말 공장의 위치끼리(서비스가 공장 밖 위치를 칸 오류로) */
    @Transactional
    public MoveResult move(TerminalPrincipal t, PopWorker w, MoveForm in) {
        PopWorker me = require(w);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.clientReqId() == null || in.clientReqId().isBlank()) {
            throw fieldError("clientReqId", "REQUIRED");          // 오프라인 재전송이 같은 결과가 되려면 필수
        }
        String method = method(in.entryMethod());
        BigDecimal qty = decimal(in.qty());
        PostResult r = stock.move(new io.smarthappy.module.wms.inv.InvDtos.MoveForm(t.plantCd(), in.itemId(), in.fromNodeId(),
                in.stockStatus(), in.toNodeId(), in.stockStatus(), qty, null, in.occurredAt(), me.workerId(), method, in.clientReqId(),
                in.lotNo()));
        return new MoveResult(r.trxIds(), r.replay(), r.warnings());
    }

    /**
     * 같은 clientReqId 가 동시에 두 번 와서 한쪽이 unique 에 걸렸을 때 — 새 트랜잭션에서 같은 이동을 다시 부른다. 먼저 들어간 행과 내용이 같으면
     * 처음 결과(replay), 다르면 409 그대로(PQ-1 — {@link io.smarthappy.module.wms.inv.InventoryService#postChecked})
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MoveResult replayMove(TerminalPrincipal t, PopWorker w, MoveForm in) {
        return move(t, w, in);
    }

    /** 이 공장의 세는 중(COUNTING) 실사 */
    @Transactional(readOnly = true)
    public List<CountHead> counts(TerminalPrincipal t, PopWorker w) {
        require(w);
        return counts.list(t.plantCd(), "COUNTING");
    }

    /** 실사 한 건 — 단말 공장 것만(남의 공장·회사 id 는 없는 것처럼). 블라인드면 장부는 서비스가 감춘다 */
    @Transactional(readOnly = true)
    public CountView count(TerminalPrincipal t, PopWorker w, long id) {
        require(w);
        CountView v = counts.view(id);
        if (!t.plantCd().equals(v.head().getPlantCd())) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return v;
    }

    /** 센 수량 — 온라인만(D-B21). 머리 rowVer 로 다른 사람·사무 화면과의 동시 저장을 409 로 */
    @Transactional
    public CountView enter(TerminalPrincipal t, PopWorker w, long id, CountsForm f) {
        count(t, w, id);
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return counts.enter(id, f, require(w).workerId());     // 센 사람 = 배지 작업자(V9315)
    }

    // ================================================================== 피킹 (B7-2a)

    /** 이 공장의 피킹할 출하(DRAFT·PICKING) */
    @Transactional(readOnly = true)
    public List<ShipHead> ships(TerminalPrincipal t, PopWorker w) {
        require(w);
        return ships.list(t.plantCd(), null, null, null).stream()
                .filter(h -> "DRAFT".equals(h.getStatus()) || "PICKING".equals(h.getStatus())).toList();
    }

    /** 출하 한 건 — 단말 공장 것만(남의 공장·회사 id 는 없는 것처럼) */
    @Transactional(readOnly = true)
    public ShipView ship(TerminalPrincipal t, PopWorker w, long id) {
        require(w);
        ShipView v = ships.view(id);
        if (!t.plantCd().equals(v.head().getPlantCd())) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return v;
    }

    /** 행의 제안 칸(FEFO·FIFO) — 사무 피킹과 같은 제안 */
    @Transactional(readOnly = true)
    public List<PickView> suggest(TerminalPrincipal t, PopWorker w, long id, long lineId) {
        ship(t, w, id);
        return ships.suggest(id, lineId);
    }

    /**
     * 피킹 — ShipService.pick 그대로(clientReqId 는 inbox 멱등, 머리 rowVer 409, 출발 칸은 제안 밖도 서비스 규칙대로 — 12.7 의 B7 몫).
     * 온라인만(D-B25): rowVer 를 대기 뒤에 풀면 남의 피킹을 덮는다
     */
    @Transactional
    public ShipView pick(TerminalPrincipal t, PopWorker w, long id, PickForm f) {
        ship(t, w, id);
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return ships.pick(id, f);
    }

    /** 출하 특이사항 확인(docs/56) — 단말 공장 지시, 확인자 = 배지 작업자. 온라인만(피킹과 같음) */
    @Transactional
    public ShipView ackNote(TerminalPrincipal t, PopWorker w, long id, long noteId) {
        ship(t, w, id);
        return ships.ackNote(id, noteId, require(w).workerId());
    }

    // ------------------------------------------------------------------ 반품 출고 (B7-3, 온라인만)

    /** SRM 이 있는 설치본인가(메뉴 버튼) */
    public boolean returnsAvailable(PopWorker w) {
        require(w);
        return returns.available();
    }

    /** 반품 지시 + 후보 칸 — 단말 공장 반품만 */
    @Transactional(readOnly = true)
    public ReturnIssueService.ReturnView returnView(TerminalPrincipal t, PopWorker w, String returnNo) {
        require(w);
        return returns.view(returnNo, t.plantCd());
    }

    /**
     * 반품 출고 — 사무 화면과 같은 {@link ReturnIssueService}(SRM 게이트·원장 RTN_SUP·멱등). 온라인만: 나눠 출고가 없어(R4-9) 대기 뒤에
     * 늦게 보내면 그사이 재고·반품 상태가 바뀐 판정을 단말과 서버가 따로 하게 된다(D-B25 와 같은 이유)
     */
    @Transactional
    public ReturnIssueService.IssueResult returnIssue(TerminalPrincipal t, PopWorker w,
            io.smarthappy.module.wms.inv.InvDtos.RtnIssueForm f) {
        PopWorker me = require(w);
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        io.smarthappy.module.wms.inv.InvDtos.RtnIssueForm in = new io.smarthappy.module.wms.inv.InvDtos.RtnIssueForm(f.returnNo(), f.rows(),
                f.remark(), f.occurredAt(), method(f.entryMethod()), f.clientReqId());
        return returns.issue(in, me.workerId(), t.plantCd());
    }

    /** 같은 요청 id 동시 두 번의 늦은 쪽 — 새 트랜잭션에서 다시(QR-1, {@link ReturnIssueService#replayIssue}) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReturnIssueService.IssueResult replayReturnIssue(TerminalPrincipal t, PopWorker w,
            io.smarthappy.module.wms.inv.InvDtos.RtnIssueForm f) {
        return returnIssue(t, w, f);
    }

    // ------------------------------------------------------------------ 입고 (docs/30 12.12.15, 온라인만 — D-B25)

    /** 발주 행 입고를 쓸 수 있나(SRM 설치본) — 화면이 발주 행 단계를 건너뛸지 */
    public boolean rcvPoEnabled(PopWorker w) {
        require(w);
        return receipts.poEnabled();
    }

    /** 입고 스캔 — 사무 입고 화면과 같은 판정(위치·품목·GS1 라벨·ASN 납품서, 우리 LOT 라벨은 입고 대상 아님), 단말 공장 안 */
    @Transactional(readOnly = true)
    public RcvScan rcvScan(TerminalPrincipal t, PopWorker w, String text) {
        require(w);
        if (text == null || text.isBlank() || text.length() > 200) {
            throw fieldError("text", "REQUIRED");
        }
        return receipts.scan(new ScanForm(t.plantCd(), text.strip()));
    }

    /** ASN 한 건(번호·협력사 납품서 번호) — 다른 공장 ASN 은 칸 오류, 없으면 404 */
    @Transactional(readOnly = true)
    public AsnQuery.Asn rcvAsn(TerminalPrincipal t, PopWorker w, String key) {
        require(w);
        return receipts.asn(t.plantCd(), key);
    }

    /** 이 공장·품목의 열린 발주 행 */
    @Transactional(readOnly = true)
    public List<PoLineOpt> rcvPoLines(TerminalPrincipal t, PopWorker w, Long itemId) {
        require(w);
        return receipts.poLines(t.plantCd(), itemId);
    }

    /** 위치 후보 — 못 받는 칸은 사유와 함께(저장과 같은 판정) */
    @Transactional(readOnly = true)
    public List<RcvLoc> rcvLocs(TerminalPrincipal t, PopWorker w, Long itemId) {
        require(w);
        return receipts.locations(t.plantCd(), itemId);
    }

    /**
     * 입고 — 사무 입고와 같은 {@link MaterialReceiptService}(SRM 게이트·ASN·MES LOT·원장·멱등) + 기록(performed_by = 배지 작업자,
     * recorded_by = 단말 — LOT 의 기록자도 단말, W-6). 온라인만: 발주 잔량·ASN 이 대기 사이에 바뀌어 늦게 보낸 요청이 초과를 만들 수 있다(D-B25)
     */
    @Transactional
    public RcvResult receive(TerminalPrincipal t, PopWorker w, PdaDtos.RcvForm in) {
        PopWorker me = require(w);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.clientReqId() == null || in.clientReqId().isBlank()) {
            throw fieldError("clientReqId", "REQUIRED");
        }
        BigDecimal qty = decimal(in.qty());
        return receipts.receiveByWorker(new io.smarthappy.module.wms.inv.InvDtos.RcvForm(t.plantCd(), in.nodeId(), in.itemId(), qty, null,
                null, in.supplierLotNo(), null, null, method(in.entryMethod()), in.clientReqId(), null, null, null, null, in.poLineId(),
                in.asnLineId()), me.workerId());
    }

    // ------------------------------------------------------------------ 정위치 점검 (docs/55, 온라인만)

    /** 위치 스캔 → 오늘 그 칸 점검(없으면 만들고, OPEN 이면 이어 쓰기, DONE 이면 409) — 단말 공장의 재고 위치만 */
    @Transactional
    public io.smarthappy.module.wms.loccheck.LocCheckDtos.View locCheckStart(TerminalPrincipal t, PopWorker w, String text) {
        PopWorker me = require(w);
        if (text == null || text.isBlank() || text.length() > 200) {
            throw fieldError("text", "REQUIRED");
        }
        ScanHit hit = stock.scan(new ScanForm(t.plantCd(), text.strip()));
        if (!"LOC".equals(hit.type()) || !hit.ok() || hit.nodeId() == null) {
            throw fieldError("text", "NOT_LOCATION");
        }
        return locChecks.start(t.plantCd(), hit.nodeId(), me.workerId());
    }

    @Transactional(readOnly = true)
    public io.smarthappy.module.wms.loccheck.LocCheckDtos.View locCheck(TerminalPrincipal t, PopWorker w, long id) {
        require(w);
        var v = locChecks.view(id);
        if (!t.plantCd().equals(v.head().getPlantCd())) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return v;
    }

    /** 물건 스캔 한 번 = 한 줄 — 판정은 서버가 받은 때의 장부(그래서 온라인만, 상위 검토 2026-10-29) */
    @Transactional
    public io.smarthappy.module.wms.loccheck.LocCheckDtos.ScanResult locCheckScan(TerminalPrincipal t, PopWorker w, long id,
            io.smarthappy.module.wms.loccheck.LocCheckDtos.LineForm f) {
        PopWorker me = require(w);
        if (f == null || f.text() == null || f.text().isBlank() || f.text().length() > 200) {
            throw fieldError("text", "REQUIRED");
        }
        ScanHit hit = stock.scan(new ScanForm(t.plantCd(), f.text().strip()));
        return locChecks.scan(id, t.plantCd(), f.text(), hit, f.clientReqId(), f.scannedAt(), me.workerId());
    }

    /** 전수 확인 완료 — 스캔 안 된 장부 = 없음 */
    @Transactional
    public io.smarthappy.module.wms.loccheck.LocCheckDtos.View locCheckDone(TerminalPrincipal t, PopWorker w, long id) {
        require(w);
        return locChecks.done(id, t.plantCd());
    }

    private static String method(String raw) {
        String m = raw == null ? "SCAN" : raw.strip().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(m)) {
            throw fieldError("entryMethod", "CODE");
        }
        return m;
    }

    private static BigDecimal decimal(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            throw fieldError("qty", "FORMAT");
        }
    }

    static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
    }
}
