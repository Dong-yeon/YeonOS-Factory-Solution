package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.aps.ApsDtos.CtpForm;
import io.smarthappy.module.aps.ApsDtos.CtpMat;
import io.smarthappy.module.aps.ApsDtos.CtpOp;
import io.smarthappy.module.aps.ApsDtos.CtpResult;
import io.smarthappy.module.aps.ApsDtos.ItemBrief;
import io.smarthappy.module.aps.ApsDtos.OpSrc;
import io.smarthappy.module.aps.FiniteScheduler.OpOut;
import io.smarthappy.module.aps.FiniteScheduler.WoOut;
import io.smarthappy.module.production.link.WoMaterialQuery;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * CTP 납기 답변 (APS 6단계, docs/38 15장) — "이 품목 N 개를 지금 넣으면 언제 끝나나".
 *
 * - 지금 대상 지시 + 사용 중 고정 + **가상 지시 1개**를 계획 계산과 같은 입력({@link ApsService#prepare})으로 순방향 엔진에 넣는다
 * - 가상 지시 = 확정 라우팅(지시 생성과 같은 고르기) 단계 × 수량, 자재 = 계획 지시와 같은 전개(MES 계약). 자재 판정은 DELAY(A38)
 * - 읽기 전용 트랜잭션 — 계획 버전·지시·고정을 바꾸지 않고 답도 저장하지 않는다(A37). 같은 질문도 지시가 바뀌면 답이 달라진다
 */
@Service
@RequiredArgsConstructor
public class ApsCtpService {

    /** 가상 지시 id — 실제 지시 id(양수)와 겹치지 않는다 */
    static final long VIRTUAL_WO = -1L;
    /** 우선순위를 안 주면 맨 뒤(지금 지시가 다 먼저 가진다 — 지시 우선순위는 1~9) */
    static final int LAST_PRIORITY = 10;
    static final int DEFAULT_DAYS = 14;

    private final ApsService plans;
    private final ApsMapper mapper;
    private final OrderService orders;
    private final BizClock clock;
    private final ObjectProvider<WoMaterialQuery> materialQueries;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CtpResult answer(CtpForm in) {
        long t0 = System.nanoTime();
        if (in == null) {
            throw OrderService.fieldError("itemCd", "REQUIRED");
        }
        FieldErrors e = FieldErrors.create();
        String plant = null;
        if (in.plantCd() == null || in.plantCd().isBlank()) {
            e.add("plantCd", "REQUIRED");
        } else {
            plant = in.plantCd().strip().toUpperCase(Locale.ROOT);
        }
        ItemOption item = orders.item(in.itemCd(), "itemCd", e);
        BigDecimal qty = OrderService.qty(in.qty(), "qty",
                item == null ? 6 : OrderService.scaleOf(orders.unitScales(), item.getUnitCd()), true, e);
        if (qty == null && (in.qty() == null || in.qty().isBlank())) {
            e.add("qty", "REQUIRED");
        }
        LocalDate want = OrderService.date(in.wantDate(), "wantDate", false, e);
        int prio = in.priority() == null ? LAST_PRIORITY : in.priority();
        if (in.priority() != null && (prio < 1 || prio > 9)) {
            e.add("priority", "RANGE", 1, 9);
        }
        int days = in.horizonDays() == null ? DEFAULT_DAYS : in.horizonDays();
        if (days < 1 || days > ApsService.MAX_DAYS) {
            e.add("horizonDays", "RANGE", 1, ApsService.MAX_DAYS);
        }
        e.throwIfAny();
        // 첫날 = 공장 시간대의 오늘(계산은 지금 시각부터), 기간 = days 일
        LocalDate first = LocalDate.ofInstant(clock.instant(), clock.zone(plant));
        WoMaterialQuery mq = WoMaterialQuery.of(materialQueries);
        Long routingId = mq.routing(item.getItemId(), plant, first);
        if (routingId == null) {
            throw OrderService.fieldError("itemCd", "NO_ROUTING");
        }
        FiniteScheduler.WoIn wo = new FiniteScheduler.WoIn();
        wo.woId = VIRTUAL_WO;
        wo.woNo = "CTP";
        wo.priority = prio;
        wo.planStart = first;
        wo.planEnd = want == null ? first.plusDays(days - 1L) : want;
        wo.due = want;
        for (OpSrc s : mapper.findRoutingOps(routingId, qty)) {
            wo.ops.add(ApsService.opIn(s));
        }
        if (wo.ops.isEmpty()) {   // 단계 없는 라우팅 — 계획할 공정이 없다
            throw OrderService.fieldError("itemCd", "NO_ROUTING");
        }
        WoMaterialQuery.WoNeeds wn = mq.explode(item.getItemId(), qty, first);
        List<MaterialPlanner.Need> needs = new ArrayList<>();
        wn.needs().stream().filter(n -> n.remainingQty() != null && n.remainingQty().signum() > 0)
                .forEach(n -> needs.add(new MaterialPlanner.Need(n.itemId(), n.opSeq(), n.remainingQty(), n.unitCd())));

        ApsService.Prepared pp = plans.prepare(plant, first, days, ApsService.DEFAULT_LEAD_HR, MaterialPlanner.Mode.DELAY, true,
                new ApsService.Virtual(wo, needs));
        List<WoOut> result = pp.scheduler().schedule(new ArrayList<>(pp.inputs().values()));
        WoOut mine = result.stream().filter(r -> r.wo.woId == VIRTUAL_WO).findFirst().orElseThrow();
        ZoneId zone = pp.zone();

        List<CtpOp> ops = new ArrayList<>();
        for (OpOut o : mine.ops) {
            ops.add(new CtpOp(o.op.opSeq, o.op.procCd, o.op.procNm, o.res == null ? null : o.res.kind(),
                    o.res == null || o.res.sortKey() == null || o.res.sortKey().isEmpty() ? null : o.res.sortKey(),
                    odt(o.start), odt(o.end), o.exc));
        }
        List<MaterialPlanner.MatOut> matOuts = pp.matIn().planner().outs().stream().filter(m -> m.woId == VIRTUAL_WO).toList();
        Set<Long> ids = new LinkedHashSet<>();
        matOuts.forEach(m -> ids.add(m.itemId));
        Map<Long, ItemBrief> names = new HashMap<>();
        if (!ids.isEmpty()) {
            mapper.findItemBriefs(ids).forEach(b -> names.put(b.getItemId(), b));
        }
        List<CtpMat> mats = matOuts.stream().map(m -> {
            ItemBrief b = names.get(m.itemId);
            return new CtpMat(m.itemId, b == null ? null : b.getItemCd(), b == null ? null : b.getItemNm(), m.opSeq, m.needQty, m.allocQty,
                    m.shortQty, m.unitCd, odt(m.needAt), odt(m.coveredAt));
        }).toList();
        // 지시 운영 규칙: 계획 못 함(exc)이 아니고 늦음 = 희망 납기(없으면 기간 끝) 다음 날 0시를 넘김
        boolean planned = mine.exc == null && mine.end != null;
        int ms = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - t0) / 1_000_000);
        return new CtpResult(plant, item.getItemId(), item.getItemCd(), item.getItemNm(), qty, item.getUnitCd(), want, prio, zone.getId(),
                odt(pp.from()), odt(pp.to()), planned ? odt(mine.start) : null, planned ? odt(mine.end) : null,
                planned && mine.late ? "Y" : "N", mine.exc != null ? mine.exc : wn.exploded() ? null : "NO_BOM",
                result.size() - 1, ms, ops, mats);
    }

    private static OffsetDateTime odt(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
