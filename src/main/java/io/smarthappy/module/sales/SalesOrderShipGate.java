package io.smarthappy.module.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.sales.SalesShipMapper.ShipLine;
import io.smarthappy.module.sales.link.OrderShipGate;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 출하 게이트 구현 (docs/25 4.22) — 머리(order_id 순) → 행(line_id 순) 잠금, 미결·허용 초과율 확인, 출하 수량 투영,
 * 다 나가면 자동 마감(done_by_ship_yn = Y), 취소로 미결이 생기면 자동 마감 행·머리를 다시 연다. 사람이 닫은 행은 되돌림만 받는다.
 */
@Component
@RequiredArgsConstructor
class SalesOrderShipGate implements OrderShipGate {

    private final SalesMapper orders;
    private final SalesShipMapper mapper;
    private final PolicyService policies;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void ship(List<ShipQty> lines) {
        // 같은 행은 합친다(line_id 순)
        Map<Long, BigDecimal> qty = new TreeMap<>();
        for (ShipQty s : lines == null ? List.<ShipQty>of() : lines) {
            if (s == null || s.qty() == null || s.qty().signum() == 0) {
                throw new IllegalArgumentException("ship quantity must be non-zero");
            }
            qty.merge(s.orderLineId(), s.qty(), BigDecimal::add);
        }
        qty.values().removeIf(v -> v.signum() == 0);
        if (qty.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(qty.keySet());
        List<ShipLine> found = mapper.lineOrders(ids);
        if (found.size() != ids.size()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        // 잠금 순서: 머리 order_id 순 → 행 line_id 순(기존 영업 흐름과 같은 머리 → 행)
        TreeSet<Long> orderIds = new TreeSet<>();
        found.forEach(l -> orderIds.add(l.getOrderId()));
        orderIds.forEach(orders::lockOrder);
        List<ShipLine> locked = mapper.lockLines(ids);
        BigDecimal pct = new BigDecimal(policies.intValue("SAL_OVERSHIP_PCT"));
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        TreeSet<Long> reopened = new TreeSet<>();
        for (ShipLine l : locked) {
            BigDecimal d = qty.get(l.getLineId());
            BigDecimal shipped = l.getShippedQty().add(d);
            String label = l.getOrderNo() + "-" + l.getLineNo();
            boolean autoDone = "DONE".equals(l.getStatus()) && "Y".equals(l.getDoneByShipYn());
            if (d.signum() > 0) {
                // 출하: 열린 행 또는 자동 마감 행(허용 초과율 안) — 취소·사람 마감 행은 거부
                if (!"OPEN".equals(l.getStatus()) && !autoDone) {
                    throw new BusinessException(HbErrorCode.ORDER_LINE_CLOSED, label);
                }
                BigDecimal cap = l.getQty().multiply(BigDecimal.ONE.add(pct.movePointLeft(2)));
                if (shipped.compareTo(cap) > 0) {
                    BigDecimal open = cap.subtract(l.getShippedQty()).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN).stripTrailingZeros();
                    throw new BusinessException(HbErrorCode.OVER_SHIP, label, open.toPlainString());
                }
            } else if (shipped.signum() < 0) {
                throw new IllegalArgumentException("ship reversal exceeds shipped quantity of line " + l.getLineId());
            }
            String status = l.getStatus();
            String doneByShip = l.getDoneByShipYn();
            if ("OPEN".equals(status) && shipped.compareTo(l.getQty()) >= 0) {
                status = "DONE";
                doneByShip = "Y";
            } else if (autoDone && shipped.compareTo(l.getQty()) < 0) {
                status = "OPEN";
                doneByShip = "N";
                reopened.add(l.getOrderId());
            }
            mapper.updateShipped(l.getLineId(), shipped, status, doneByShip, by);
        }
        // 머리 상태: 다시 열린 행이 있으면 CLOSED → OPEN, 아니면 행에서 다시 계산(OPEN → CLOSED)
        for (Long o : orderIds) {
            if (reopened.contains(o)) {
                mapper.reopenOrder(o, by);
            } else {
                orders.refreshOrderStatus(o, by);
            }
        }
    }
}
