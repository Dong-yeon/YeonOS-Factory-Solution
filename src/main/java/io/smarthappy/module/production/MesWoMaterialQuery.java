package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.WoDtos.RefRow;
import io.smarthappy.module.production.WoDtos.SnapBom;
import io.smarthappy.module.production.WoMaterialMapper.NeedRow;
import io.smarthappy.module.production.WoMaterialMapper.WoHead;
import io.smarthappy.module.production.link.WoMaterialQuery;
import io.smarthappy.platform.common.web.BusinessException;
import lombok.RequiredArgsConstructor;

/**
 * 남은 자재 소요 계약 구현 (docs/25 4.18). 계획 지시는 {@link WoService#explode} 를 그대로 쓴다(발행과 같은 전개 — 패키지 밖에 열지 않는다).
 * 투입은 같은 공정 순번의 줄에서 먼저 빼고, 남는 양은 같은 품목의 다른 줄에서 공정 순서대로 뺀다(공정 지정 없음은 맨 뒤). 0 아래로 내려가지 않는다.
 */
@Component
@RequiredArgsConstructor
class MesWoMaterialQuery implements WoMaterialQuery {

    /** 품목 → 공정 순번(없음은 맨 뒤) → 단위 */
    private static final Comparator<Key> ORDER = Comparator.comparingLong(Key::itemId)
            .thenComparing(Key::opSeq, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Key::unitCd);

    private final WoMaterialMapper mapper;
    private final WoMapper woMapper;
    private final WoService wos;

    private record Key(long itemId, Integer opSeq, String unitCd) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<WoNeeds> remaining(Collection<Long> woIds) {
        List<Long> ids = woIds == null ? List.of() : woIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("too many work order ids: " + ids.size() + " > " + MAX_IDS);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<WoHead> heads = mapper.heads(ids);
        List<Long> released = heads.stream().filter(h -> !"PLANNED".equals(h.getStatus())).map(WoHead::getWoId).toList();
        Map<Long, List<NeedRow>> snap = new HashMap<>();
        Map<Long, List<NeedRow>> used = new HashMap<>();
        if (!released.isEmpty()) {
            mapper.snapshot(released).forEach(r -> snap.computeIfAbsent(r.getWoId(), k -> new ArrayList<>()).add(r));
            mapper.consumed(released).forEach(r -> used.computeIfAbsent(r.getWoId(), k -> new ArrayList<>()).add(r));
        }
        List<WoNeeds> out = new ArrayList<>(heads.size());
        for (WoHead h : heads) {
            if ("PLANNED".equals(h.getStatus())) {
                out.add(planned(h));
                continue;
            }
            TreeMap<Key, BigDecimal> need = new TreeMap<>(ORDER);
            snap.getOrDefault(h.getWoId(), List.of()).forEach(r -> need.merge(key(r), r.getQty(), BigDecimal::add));
            subtract(need, used.getOrDefault(h.getWoId(), List.of()));
            out.add(new WoNeeds(h.getWoId(), h.getStatus(), true, lines(need)));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Long routing(long itemId, String plantCd, LocalDate on) {
        return woMapper.routingOptions(itemId, plantCd, on).stream().findFirst().map(o -> o.getId()).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public WoNeeds explode(long itemId, BigDecimal qty, LocalDate on) {
        RefRow bom = woMapper.bomOptions(itemId, on).stream().findFirst().map(o -> woMapper.findBom(o.getId())).orElse(null);
        return needs(0, "PLANNED", bom, qty, on);
    }

    /** 계획 지시 — BOM 없음은 소요 없음, 전개 실패(환산·가상 조립 BOM 없음)는 exploded = false */
    private WoNeeds planned(WoHead h) {
        RefRow bom = h.getBomId() == null ? null : woMapper.findBom(h.getBomId());
        return needs(h.getWoId(), h.getStatus(), bom, h.getPlanQty(), h.getPlanStart());
    }

    /** 발행과 같은 전개(WoService.explode) → (품목, 공정 순번) 합계 */
    private WoNeeds needs(long woId, String status, RefRow bom, BigDecimal qty, LocalDate date) {
        if (bom == null) {
            return new WoNeeds(woId, status, true, List.of());
        }
        List<SnapBom> lines;
        try {
            lines = wos.explode(bom, qty, date);
        } catch (BusinessException e) {
            return new WoNeeds(woId, status, false, List.of());
        }
        TreeMap<Key, BigDecimal> need = new TreeMap<>(ORDER);
        lines.stream().filter(l -> "NORMAL".equals(l.lineType()))
                .forEach(l -> need.merge(new Key(l.itemId(), l.opSeq(), l.unitCd()), l.reqQty(), BigDecimal::add));
        return new WoNeeds(woId, status, true, lines(need));
    }

    private static void subtract(TreeMap<Key, BigDecimal> need, List<NeedRow> used) {
        // 1) 같은 공정 순번의 줄에서
        Map<Key, BigDecimal> left = new TreeMap<>(ORDER);
        for (NeedRow u : used) {
            if (u.getQty().signum() <= 0) {
                continue;
            }
            Key k = key(u);
            BigDecimal rest = take(need, k, u.getQty());
            if (rest.signum() > 0) {
                left.merge(new Key(k.itemId(), null, k.unitCd()), rest, BigDecimal::add);
            }
        }
        // 2) 남는 양은 같은 품목·단위의 줄에서 공정 순서대로
        for (Map.Entry<Key, BigDecimal> e : left.entrySet()) {
            BigDecimal rest = e.getValue();
            for (Key k : need.keySet()) {
                if (rest.signum() <= 0) {
                    break;
                }
                if (k.itemId() == e.getKey().itemId() && k.unitCd().equals(e.getKey().unitCd())) {
                    rest = take(need, k, rest);
                }
            }
        }
    }

    /** 줄 k 에서 qty 만큼 빼고 못 뺀 양을 돌려준다 */
    private static BigDecimal take(Map<Key, BigDecimal> need, Key k, BigDecimal qty) {
        BigDecimal have = need.get(k);
        if (have == null || have.signum() <= 0) {
            return qty;
        }
        BigDecimal d = have.min(qty);
        need.put(k, have.subtract(d));
        return qty.subtract(d);
    }

    private static List<Need> lines(TreeMap<Key, BigDecimal> need) {
        return need.entrySet().stream().filter(e -> e.getValue().signum() > 0)
                .map(e -> new Need(e.getKey().itemId(), e.getKey().opSeq(), plain(e.getValue()), e.getKey().unitCd()))
                .toList();
    }

    private static BigDecimal plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static Key key(NeedRow r) {
        return new Key(r.getItemId(), r.getOpSeq(), r.getUnitCd());
    }
}
