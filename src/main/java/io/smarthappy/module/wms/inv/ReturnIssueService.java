package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.link.ReturnShipGate;
import io.smarthappy.module.srm.link.ReturnShipGate.ReturnRef;
import io.smarthappy.module.srm.link.ReturnShipGate.Shipped;
import io.smarthappy.module.wms.inv.InvDtos.ItemOpt;
import io.smarthappy.module.wms.inv.InvDtos.NodeOpt;
import io.smarthappy.module.wms.inv.InvDtos.PostResult;
import io.smarthappy.module.wms.inv.InvDtos.RtnIssueForm;
import io.smarthappy.module.wms.inv.InvDtos.RtnIssueRow;
import io.smarthappy.module.wms.inv.InvDtos.RtnStock;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * 협력사 반품 출고 RTN_SUP (SRM 2부 R4-4·R4-3·R4-9·R4-10, docs/30 12.14 · docs/31 4.12.7).
 *
 * <ul>
 * <li>반품 지시(SRM 확정)를 번호로 찾아 → 그 공장·품목(LOT 관리 품목이면 지시 LOT, 아니면 LOT 없는 칸) 재고 칸에서 → 지시 수량 전부를 한 번에</li>
 * <li>SRM {@link ReturnShipGate#ship} 을 원장보다 먼저(같은 트랜잭션) — 잠금 = 반품 → 발주 머리 → 발주 행, 그 뒤 LOT·재고(입고와 같은 방향)</li>
 * <li>같은 요청 id 다시 → 내용이 같으면 처음 결과(게이트를 다시 부르지 않음), 다르면 409 DUPLICATE_REQUEST(PQ-1 규칙)</li>
 * <li>정정 = 수불 원장 화면의 역분개 — 한 행을 되돌리면 그 출고 전체가 되돌아가고 SRM 반품은 확정으로({@link InventoryService})</li>
 * <li>LOT 마스터는 건드리지 않는다(출하 SHIP 과 같음 — 원장이 LOT 행을 FOR SHARE 로 읽을 뿐). 보류 LOT 도 나간다(반품 대상은 보통 보류 불량 LOT)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReturnIssueService {

    public static final String PROG = "wms-rtn-issue";
    static final String SRC = "SRM_RETURN";
    static final int MAX_ROWS = 50;

    private final StockService stock;
    private final InventoryService inventory;
    private final InvMapper mapper;
    private final ObjectProvider<ReturnShipGate> gate;

    /** 화면 켜기 — SRM 없는 설치본은 반품 지시가 없다 */
    public boolean available() {
        return ReturnShipGate.of(gate).available();
    }

    /** 반품 지시 + 출고 후보 재고 칸 */
    public record ReturnView(ReturnRef ret, String itemCd, String itemNm, boolean lotCtrl, List<RtnStock> stock) {
    }

    public record IssueResult(List<Long> trxIds, boolean replay, Shipped shipped) {
    }

    @Transactional(readOnly = true)
    public ReturnView view(String returnNo) {
        return view(returnNo, null);
    }

    /** plantOnly 가 있으면 그 공장 반품만(창고 PDA — 단말 공장, 남의 공장 반품은 없는 것처럼) */
    @Transactional(readOnly = true)
    public ReturnView view(String returnNo, String plantOnly) {
        if (!available()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ReturnRef r = find(returnNo, plantOnly);
        FieldErrors e = FieldErrors.create();
        ItemOpt item = stock.itemById(r.itemId(), e);
        e.throwIfAny();
        boolean lotCtrl = "Y".equals(item.getLotCtrlYn());
        List<RtnStock> rows = "CONFIRMED".equals(r.status()) ? mapper.rtnStock(r.plantCd(), r.itemId(), lotCtrl ? r.lotId() : null) : List.of();
        return new ReturnView(r, item.getItemCd(), item.getItemNm(), lotCtrl, rows);
    }

    @Transactional
    public IssueResult issue(RtnIssueForm f) {
        return issue(f, null, null);
    }

    /**
     * 같은 요청 id 가 동시에 두 번(QR-1): 늦은 쪽은 {@link #issue} 의 요청 확인을 지난 뒤 게이트의 반품 잠금을 기다리다 SHIPPED 를 보고
     * SRM_STATE(또는 원장 unique 에 DUPLICATE_REQUEST) — 게이트가 MANDATORY 라 그 트랜잭션은 이미 롤백 전용이다.
     * 컨트롤러가 그 둘을 잡아 새 트랜잭션에서 다시 부르면 먼저 커밋된 같은 요청 원장이 보여 처음 결과(내용이 다르면 409 — PQ-1).
     * 같은 요청 원장이 없으면(진짜 이미 출고됨·다른 요청) 게이트가 같은 오류를 다시 낸다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IssueResult replayIssue(RtnIssueForm f, Long performedBy, String plantOnly) {
        return issue(f, performedBy, plantOnly);
    }

    /** 다시 불러 볼 오류인가 — 요청 id 가 있고 게이트 상태·원장 중복일 때만 */
    public static boolean replayable(BusinessException e, RtnIssueForm f) {
        return f != null && f.clientReqId() != null && (e.code() == HbErrorCode.SRM_STATE || e.code() == HbErrorCode.DUPLICATE_REQUEST);
    }

    /**
     * 창고 PDA(B7-3) — performedBy = 배지 작업자(원장 performed_by), plantOnly = 단말 공장. 기록자(recorded_by·SRM by)는
     * {@link InventoryService#me()} 대체 규칙대로 단말 행위자(t{id}:{cd}, 최대 41자 — 칸 50자 안)
     */
    @Transactional
    public IssueResult issue(RtnIssueForm f, Long performedBy, String plantOnly) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String req = f.clientReqId() == null ? "" : f.clientReqId().strip();
        if (!InventoryService.REQ_ID.matcher(req).matches()) {
            throw StockService.fieldError("clientReqId", req.isEmpty() ? "REQUIRED" : "FORMAT");
        }
        if (!available()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ReturnRef r = find(f.returnNo(), plantOnly);
        FieldErrors e = FieldErrors.create();
        ItemOpt item = stock.itemById(r.itemId(), e);
        List<RtnIssueRow> in = f.rows() == null ? List.of() : f.rows();
        if (in.isEmpty()) {
            e.add("rows", "REQUIRED");
        } else if (in.size() > MAX_ROWS) {
            e.add("rows", "TOO_MANY_LABELS", MAX_ROWS);
        }
        String remark = StockService.remark(f.remark(), e);
        OffsetDateTime at = stock.occurredAt(f.occurredAt(), r.plantCd(), e);
        String method = StockService.method(f.entryMethod(), e);
        e.throwIfAny();
        Long lot = "Y".equals(item.getLotCtrlYn()) ? r.lotId() : null;
        List<TrxReq> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < in.size(); i++) {
            RtnIssueRow x = in.get(i);
            String p = "rows[" + i + "].";
            if (x == null) {
                e.add(p + "nodeId", "REQUIRED");
                continue;
            }
            NodeOpt node = stock.node(x.nodeId(), r.plantCd(), p + "nodeId", e);      // 반품 공장 밖 칸 = 없음
            String status = StockService.status(x.stockStatus(), p + "stockStatus", e);
            if (x.qty() == null || x.qty().signum() <= 0) {
                e.add(p + "qty", "POSITIVE");
            }
            if (node == null || status == null || x.qty() == null || x.qty().signum() <= 0) {
                continue;
            }
            if (!seen.add(node.getNodeId() + "/" + status)) {
                e.add(p + "nodeId", "DUPLICATE");
                continue;
            }
            total = total.add(x.qty());
            rows.add(new TrxReq(InventoryService.RETURN_ISSUE, r.itemId(), node.getNodeId(), status, x.qty().negate(), null, SRC, r.returnId(),
                    null, remark == null ? r.returnNo() : remark, at, method, performedBy, lot));
        }
        e.throwIfAny();
        // 같은 요청 다시: 처음과 같으면 그 결과(게이트는 이미 출고됨), 다르면 409 — 게이트보다 먼저 본다
        if (!mapper.findReq(req).isEmpty()) {
            PostResult again = inventory.postChecked(rows, req);
            return new IssueResult(again.trxIds(), true, null);
        }
        Shipped s = ReturnShipGate.of(gate).ship(r.returnId(), lot, total, InventoryService.RETURN_REF + req,
                InventoryService.me().loginId(), at);
        PostResult posted = inventory.post(rows, req);
        return new IssueResult(posted.trxIds(), posted.replay(), s);
    }

    private ReturnRef find(String returnNo, String plantOnly) {
        String no = returnNo == null ? "" : returnNo.strip();
        if (no.regionMatches(true, 0, "RTN:", 0, 4)) {
            no = no.substring(4).strip();           // 스캔 키 RTN:{번호}
        }
        if (no.isEmpty()) {
            throw StockService.fieldError("returnNo", "REQUIRED");
        }
        if (no.length() > 30) {
            throw StockService.fieldError("returnNo", "NOT_FOUND");
        }
        return ReturnShipGate.of(gate).find(no).filter(r -> plantOnly == null || plantOnly.equals(r.plantCd()))
                .orElseThrow(() -> StockService.fieldError("returnNo", "NOT_FOUND"));
    }
}
