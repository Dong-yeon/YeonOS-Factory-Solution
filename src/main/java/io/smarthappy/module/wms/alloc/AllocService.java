package io.smarthappy.module.wms.alloc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.alloc.AllocDtos.CandRow;
import io.smarthappy.module.wms.alloc.AllocDtos.ItemRow;
import io.smarthappy.module.wms.alloc.AllocDtos.PickView;
import io.smarthappy.module.wms.alloc.AllocDtos.Preview;
import io.smarthappy.module.wms.alloc.AllocDtos.PreviewForm;
import io.smarthappy.module.wms.alloc.AllocDtos.SkipView;
import io.smarthappy.module.wms.alloc.AllocationStrategy.Candidate;
import io.smarthappy.module.wms.alloc.AllocationStrategy.Options;
import io.smarthappy.module.wms.alloc.AllocationStrategy.Result;
import io.smarthappy.module.wms.alloc.AllocationStrategy.Strategy;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 출고 후보 미리보기 (docs/30 W4) — "이 품목 120 EA 를 꺼내면 어느 위치에서" 를 현재고로 계산해 보여 준다. 저장하지 않는다.
 * 전략 결정 순서: 화면에서 고른 값 → 품목 클래스 속성 ALLOC_STRATEGY → 공장·회사 정책 WMS_ALLOC_STRATEGY.
 * 1부는 LOT 이 없어 유효기한 = 선입 기준일 + 품목 클래스 속성 SHELF_LIFE_DAYS(있을 때만 — D5). 2부는 LOT 유효기한.
 */
@Service
@RequiredArgsConstructor
public class AllocService {

    static final String POLICY = "WMS_ALLOC_STRATEGY";
    static final int MAX_SHELF_DAYS = 36500;

    /** 품질 상태 연동 공장 스위치(docs/30 12.15) */
    static final String QUALITY_POLICY = "WMS_QUALITY_LINK";

    private final AllocMapper mapper;
    private final PolicyService policy;
    private final BizClock clock;

    @Transactional(readOnly = true)
    public Preview preview(PreviewForm f) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = f.plantCd() == null || f.plantCd().isBlank() ? null : f.plantCd().strip().toUpperCase(Locale.ROOT);
        if (plant == null) {
            e.add("plantCd", "REQUIRED");
        } else if (plant.length() > 20 || !mapper.plantExists(plant)) {
            e.add("plantCd", "NOT_FOUND");
            plant = null;
        }
        BigDecimal qty = qty(f.qty(), e);
        Strategy requested = null;
        if (f.strategy() != null && !f.strategy().isBlank()) {
            try {
                requested = Strategy.valueOf(f.strategy().strip());
            } catch (IllegalArgumentException ex) {
                e.add("strategy", "CODE");
            }
        }
        ItemRow item = null;
        String code = f.itemCd() == null ? null : f.itemCd().strip().toUpperCase(Locale.ROOT);
        if (f.itemId() == null && (code == null || code.isEmpty())) {
            e.add("itemCd", "REQUIRED");
        } else if (code != null && code.length() > 40) {
            e.add("itemCd", "TOO_LONG", 40);
        } else {
            item = mapper.findItem(f.itemId(), code);
            if (item == null) {
                e.add("itemCd", "NO_SUCH_CODE");
            }
        }
        if (plant != null && f.nodeId() != null && !mapper.nodeOfPlant(f.nodeId(), plant)) {
            e.add("nodeId", "NOT_FOUND");
        }
        // 품질 상태 연동 공장은 검사 대기(QC) 재고를 꺼내는 후보로 보이지 않는다 — 판정이 풀어 줘야 가용(docs/30 12.15.6)
        if (plant != null && Boolean.FALSE.equals(f.availableOnly()) && "Y".equals(policy.value(QUALITY_POLICY, plant))) {
            e.add("availableOnly", "QUALITY_LINK");
        }
        e.throwIfAny();

        String source;
        Strategy strategy;
        Strategy fromItem = parse(item.getAllocAttr());
        if (requested != null) {
            strategy = requested;
            source = "REQUEST";
        } else if (fromItem != null) {
            strategy = fromItem;
            source = "ITEM";
        } else {
            strategy = parse(policy.value(POLICY, plant));
            source = "POLICY";
            if (strategy == null) {
                strategy = Strategy.FIFO;
            }
        }
        boolean availableOnly = !Boolean.FALSE.equals(f.availableOnly());
        ZoneId zone = clock.zone(plant);
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        LocalDate expiredBefore = Boolean.FALSE.equals(f.excludeExpired()) ? null : today;
        Integer shelf = shelfDays(item.getShelfLifeAttr());

        List<CandRow> rows = mapper.findCandidates(plant, item.getItemId(), f.nodeId());
        List<Candidate> cands = rows.stream().map(r -> {
            LocalDate fifo = r.getFifoAt() == null ? null : r.getFifoAt().atZoneSameInstant(zone).toLocalDate();
            LocalDate exp = fifo == null || shelf == null ? null : fifo.plusDays(shelf);
            return new Candidate(r.getNodeId(), r.getPickSeq(), r.getLotId(), r.getLotNo(), fifo, exp, r.getStockStatus(), r.getQty(),
                    r.isAvailLoc(), r.isLotFree());
        }).toList();
        Result res = AllocationStrategy.allocate(cands, qty, new Options(strategy, List.of(), availableOnly, expiredBefore, true));

        List<PickView> picks = res.picks().stream().map(p -> {
            CandRow r = row(rows, p.from());
            return new PickView(r.getNodeId(), r.getNodeCd(), r.getPath(), r.getStockStatus(), r.getLotId(), p.from().fifoDate(),
                    p.from().expiryDate(), text(p.from().qty()), text(p.qty()), r.getLotNo());
        }).toList();
        List<SkipView> skipped = res.skipped().stream().map(s -> {
            CandRow r = row(rows, s.candidate());
            return new SkipView(r.getNodeId(), r.getPath(), r.getStockStatus(), text(r.getQty()), s.reason().name(), r.getLotNo());
        }).toList();
        return new Preview(item.getItemCd(), item.getItemNm(), item.getUnitCd(), strategy.name(), source, availableOnly, expiredBefore,
                shelf, text(qty), text(res.allocated()), text(res.shortage()), picks, skipped);
    }

    private static CandRow row(List<CandRow> rows, Candidate c) {
        return rows.stream().filter(r -> r.getNodeId() == c.nodeId() && r.getStockStatus().equals(c.status())
                && java.util.Objects.equals(r.getLotId(), c.lotId())).findFirst()
                .orElseThrow();
    }

    /** 요청 수량: 글자 → BigDecimal(반올림 없음). 양수, 소수 6자리, 정수 12자리 */
    static BigDecimal qty(String v, FieldErrors e) {
        if (v == null || v.isBlank()) {
            e.add("qty", "REQUIRED");
            return null;
        }
        BigDecimal q;
        try {
            q = new BigDecimal(v.strip());
        } catch (NumberFormatException ex) {
            e.add("qty", "FORMAT");
            return null;
        }
        if (q.signum() <= 0) {
            e.add("qty", "POSITIVE");
        } else if (q.stripTrailingZeros().scale() > 6) {
            e.add("qty", "SCALE", 6);
        } else if (q.compareTo(new BigDecimal("1000000000000")) >= 0) {
            e.add("qty", "INT_DIGITS", 12);
        } else {
            return q;
        }
        return null;
    }

    static Strategy parse(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Strategy.valueOf(v.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;           // 속성 값이 틀리면 무시하고 정책으로(클래스 속성 정의가 LIST 면 생기지 않는다)
        }
    }

    static Integer shelfDays(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(v.strip());
            if (d.signum() <= 0 || d.stripTrailingZeros().scale() > 0 || d.compareTo(BigDecimal.valueOf(MAX_SHELF_DAYS)) > 0) {
                return null;
            }
            return d.intValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }

    static String text(BigDecimal v) {
        return v == null ? null : v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

}
