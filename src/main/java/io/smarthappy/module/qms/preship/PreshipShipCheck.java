package io.smarthappy.module.qms.preship;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.smarthappy.module.qms.preship.PreshipDtos.LiveInsp;
import io.smarthappy.module.wms.link.ShipConfirmCheck;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * 출하 확정 전 확인 — 출하 전 검수 (docs/78 5장, 확장점 docs/72 2.1). 읽기만.
 * 정책 {@code QMS_PRESHIP_REQUIRED}=N 이거나 양식이 걸린 품목이 없으면 통과(지금 동작 그대로).
 * 그 밖은 살아 있는 검수가 DONE 이고, 허용 안 된 열린 미결이 없고, 양식이 걸린 품목이 모두 검수에 있어야 통과.
 * 막을 때는 그 품목의 피킹 LOT 마다 한 건 — 그런 LOT 이 없으면(LOT 관리 안 하는 품목) LOT 없는 한 건(lotId 0).
 */
@Component
@Order(500)
@RequiredArgsConstructor
public class PreshipShipCheck implements ShipConfirmCheck {

    static final String KEY = "QMS_PRESHIP";

    private final PreshipMapper mapper;
    private final PolicyService policies;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public List<Block> check(ShipContext ctx) {
        if (!policies.bool(Policies.QMS_PRESHIP_REQUIRED)) {
            return List.of();
        }
        List<Long> required = mapper.requiredItemIds(ctx.shipId());
        if (required.isEmpty()) {
            return List.of();
        }
        LiveInsp h = mapper.liveInsp(ctx.shipId());
        Set<Long> blockItems;
        String messageKey;
        List<String> args;
        if (h == null) {
            blockItems = Set.copyOf(required);
            messageKey = "qms.preship.block.none";
            args = List.of();
        } else if (!"DONE".equals(h.getStatus())) {
            blockItems = Set.copyOf(required);
            messageKey = "qms.preship.block.open";
            args = List.of(h.getInspNo());
        } else if (h.getBlockingPunchCnt() > 0) {
            blockItems = Set.copyOf(required);
            messageKey = "qms.preship.block.punch";
            args = List.of(h.getInspNo(), String.valueOf(h.getBlockingPunchCnt()));
        } else {
            Set<Long> missing = new HashSet<>(required);
            missing.removeAll(mapper.coveredItemIds(h.getInspId()));
            if (missing.isEmpty()) {
                return List.of();
            }
            blockItems = missing;
            messageKey = "qms.preship.block.missing";
            args = List.of(h.getInspNo());
        }
        List<Block> out = new ArrayList<>();
        ctx.lots().stream().filter(l -> blockItems.contains(l.itemId()))
                .forEach(l -> out.add(new Block(KEY, l.lotId(), l.lotNo(), messageKey, args)));
        if (out.isEmpty()) {
            out.add(new Block(KEY, 0L, null, messageKey, args));
        }
        return out;
    }
}
