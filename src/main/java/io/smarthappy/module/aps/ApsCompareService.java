package io.smarthappy.module.aps;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.aps.ApsDtos.CmpOp;
import io.smarthappy.module.aps.ApsDtos.Compare;
import io.smarthappy.module.aps.ApsDtos.CompareRow;
import io.smarthappy.module.aps.ApsDtos.PlanRow;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * 계획 비교 (aps-plan) — APS 2단계, docs/38 11장 A18. 읽기만.
 * 같은 공장 두 계획을 (지시, 공정 순번)으로 맞대어 구분·시작 이동·자원 전후를 낸다.
 */
@Service
@RequiredArgsConstructor
public class ApsCompareService {

    static final List<String> KINDS = List.of("SAME", "MOVED", "RES", "SCHEDULED", "UNSCHEDULED", "ONLY_A", "ONLY_B");

    private final ApsMapper mapper;

    @Transactional(readOnly = true)
    public Compare compare(long aId, Long bId) {
        if (bId == null) {
            throw OrderService.fieldError("other", "REQUIRED");
        }
        if (bId == aId) {
            throw OrderService.fieldError("other", "APS_SAME_PLAN");
        }
        PlanRow a = plan(aId);
        PlanRow b = plan(bId);
        if (a.getPurgedAt() != null || b.getPurgedAt() != null) {
            throw OrderService.fieldError("other", "APS_PURGED");
        }
        if (!a.getPlantCd().equals(b.getPlantCd())) {
            throw OrderService.fieldError("other", "APS_OTHER_PLANT");
        }
        Map<String, CmpOp> as = byKey(mapper.findCmpOps(aId));
        Map<String, CmpOp> bs = byKey(mapper.findCmpOps(bId));
        TreeSet<CmpOp> keys = new TreeSet<>(Comparator.comparing(CmpOp::getWoNo).thenComparingInt(CmpOp::getOpSeq));
        keys.addAll(as.values());
        keys.addAll(bs.values());
        Map<String, Integer> kinds = new LinkedHashMap<>();
        KINDS.forEach(k -> kinds.put(k, 0));
        List<CompareRow> rows = new ArrayList<>();
        for (CmpOp k : keys) {
            CmpOp x = as.get(key(k));
            CmpOp y = bs.get(key(k));
            String kind = kind(x, y);
            kinds.merge(kind, 1, Integer::sum);
            Long shift = x != null && y != null && x.getStartAt() != null && y.getStartAt() != null
                    ? y.getStartAt().toEpochSecond() - x.getStartAt().toEpochSecond() : null;
            rows.add(new CompareRow(k.getWoId(), k.getWoNo(), k.getOpSeq(), k.getProcCd(), k.getProcNm(), kind,
                    x == null ? null : x.getStartAt(), x == null ? null : x.getEndAt(), x == null ? null : res(x), x == null ? null : x.getExcCd(),
                    y == null ? null : y.getStartAt(), y == null ? null : y.getEndAt(), y == null ? null : res(y), y == null ? null : y.getExcCd(),
                    shift));
        }
        return new Compare(a, b, rows, kinds);
    }

    /** 구분 — 자원이 바뀌면 시각이 같아도 RES */
    static String kind(CmpOp x, CmpOp y) {
        if (x == null) {
            return "ONLY_B";
        }
        if (y == null) {
            return "ONLY_A";
        }
        boolean xs = x.getStartAt() != null;
        boolean ys = y.getStartAt() != null;
        if (xs != ys) {
            return xs ? "UNSCHEDULED" : "SCHEDULED";
        }
        if (!xs) {
            return "SAME";
        }
        if (!Objects.equals(res(x), res(y))) {
            return "RES";
        }
        return x.getStartAt().toInstant().equals(y.getStartAt().toInstant()) && x.getEndAt().toInstant().equals(y.getEndAt().toInstant())
                ? "SAME" : "MOVED";
    }

    /** 자원 표기: 설비·작업장 코드, 외주는 OUTSRC */
    private static String res(CmpOp o) {
        return o.getResKind() == null ? null : "OUTSRC".equals(o.getResKind()) ? "OUTSRC" : o.getResCd();
    }

    private static Map<String, CmpOp> byKey(List<CmpOp> ops) {
        Map<String, CmpOp> m = new LinkedHashMap<>();
        ops.forEach(o -> m.put(key(o), o));
        return m;
    }

    private static String key(CmpOp o) {
        return o.getWoId() + ":" + o.getOpSeq();
    }

    private PlanRow plan(long id) {
        PlanRow p = mapper.findPlan(id);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }
}
