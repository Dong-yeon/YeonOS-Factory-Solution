package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.smarthappy.module.production.SerialDtos.AltCand;
import io.smarthappy.module.production.SerialDtos.CompareLine;
import io.smarthappy.module.production.SerialDtos.Installed;
import io.smarthappy.module.production.SerialDtos.WoBomLine;

/**
 * BOM 대비 — 장비 1대의 설계 구성(지시 자재 스냅샷) 과 실제 장착 구성 비교 (docs/77 5.2). 순수 함수(DB 없음).
 *
 * - 1대 소요 = 행 소요 ÷ 계획 수량 ÷ (1 + 스크랩 %) — 스냅샷 소요는 지시 전체·스크랩 포함. 소수 6자리 반올림
 * - 묶음 = 대체 그룹(원래 BOM × alt_grp) 하나 또는 품목 하나(같은 품목 여러 행은 합)
 * - 대체 그룹 후보 = 원래 BOM 의 같은 그룹 품목 전부(비율 0 이라 스냅샷에 없는 대체품 포함), 우선순위가 가장 낮은 번호 = 주 품목
 * - 판정 순서 MISSING(0) → SHORT(<) → OVER(>) → SUBST(주 품목 아닌 품목이 들어감) → OK, BOM 에 없는 장착 품목 = EXTRA
 */
public final class SerialCompare {

    public static final String OK = "OK";
    public static final String MISSING = "MISSING";
    public static final String SHORT = "SHORT";
    public static final String OVER = "OVER";
    public static final String SUBST = "SUBST";
    public static final String EXTRA = "EXTRA";

    private static final MathContext MC = MathContext.DECIMAL128;

    private SerialCompare() {
    }

    private static final class Group {
        String altGrp;
        long primaryId;
        String primaryCd;
        String primaryNm;
        int primaryPriority = Integer.MAX_VALUE;
        Integer opSeq;
        String unitCd;
        BigDecimal req = BigDecimal.ZERO;
        final Set<Long> cands = new LinkedHashSet<>();
    }

    /** installed = 이 시리얼의 장착 중 수량(품목별) */
    public static List<CompareLine> compare(BigDecimal planQty, List<WoBomLine> bom, List<AltCand> alts, List<Installed> installed) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (WoBomLine l : bom) {
            String key = l.getAltGrp() == null ? "I:" + l.getItemId() : "G:" + l.getSrcBomId() + ":" + l.getAltGrp();
            Group g = groups.computeIfAbsent(key, k -> new Group());
            g.altGrp = l.getAltGrp();
            g.unitCd = g.unitCd == null ? l.getUnitCd() : g.unitCd;
            g.req = g.req.add(perUnit(l, planQty), MC);
            g.cands.add(l.getItemId());
            if (l.getOpSeq() != null && (g.opSeq == null || l.getOpSeq() < g.opSeq)) {
                g.opSeq = l.getOpSeq();
            }
            int pri = l.getAltPriority() == null ? 0 : l.getAltPriority();
            if (g.primaryCd == null || pri < g.primaryPriority) {
                g.primaryId = l.getItemId();
                g.primaryCd = l.getItemCd();
                g.primaryNm = l.getItemNm();
                g.primaryPriority = pri;
            }
        }
        for (AltCand a : alts) {
            Group g = groups.get("G:" + a.getBomId() + ":" + a.getAltGrp());
            if (g == null) {
                continue;
            }
            g.cands.add(a.getItemId());
            if (a.getAltPriority() < g.primaryPriority) {
                g.primaryId = a.getItemId();
                g.primaryCd = a.getItemCd();
                g.primaryNm = a.getItemNm();
                g.primaryPriority = a.getAltPriority();
            }
        }
        Map<Long, Installed> inst = new LinkedHashMap<>();
        for (Installed i : installed) {
            inst.merge(i.getItemId(), i, (x, y) -> {
                x.setQty(x.getQty().add(y.getQty()));
                return x;
            });
        }
        Set<Long> used = new HashSet<>();
        List<CompareLine> out = new ArrayList<>();
        for (Group g : groups.values()) {
            BigDecimal got = BigDecimal.ZERO;
            List<String> items = new ArrayList<>();
            boolean subst = false;
            for (Long id : g.cands) {
                Installed i = inst.get(id);
                if (i == null || used.contains(id)) {
                    continue;
                }
                used.add(id);
                if (i.getQty().signum() > 0) {
                    got = got.add(i.getQty());
                    items.add(i.getItemCd());
                    subst |= g.altGrp != null && id != g.primaryId;
                }
            }
            BigDecimal req = g.req.setScale(6, RoundingMode.HALF_UP);
            String status;
            if (got.signum() == 0) {
                status = MISSING;
            } else if (got.compareTo(req) < 0) {
                status = SHORT;
            } else if (got.compareTo(req) > 0) {
                status = OVER;
            } else {
                status = subst ? SUBST : OK;
            }
            out.add(new CompareLine(status, g.primaryCd, g.primaryNm, g.altGrp, g.opSeq, plain(req), plain(got), g.unitCd, items));
        }
        for (Installed i : inst.values()) {
            if (!used.contains(i.getItemId()) && i.getQty().signum() > 0) {
                out.add(new CompareLine(EXTRA, i.getItemCd(), i.getItemNm(), null, null, "0", plain(i.getQty()), i.getUnitCd(),
                        List.of(i.getItemCd())));
            }
        }
        return out;
    }

    /** 1대 소요(스크랩 뺀 양) */
    static BigDecimal perUnit(WoBomLine l, BigDecimal planQty) {
        BigDecimal scrap = l.getScrapPct() == null ? BigDecimal.ZERO : l.getScrapPct();
        return l.getReqQty().divide(planQty, MC).divide(BigDecimal.ONE.add(scrap.movePointLeft(2)), MC);
    }

    /** 덜 들어간 묶음인가(완료판정 경고) */
    public static boolean isShort(CompareLine c) {
        return MISSING.equals(c.status()) || SHORT.equals(c.status());
    }

    static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }
}
