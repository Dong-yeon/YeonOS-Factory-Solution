package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.aps.ApsPrDtos.LinkInsert;
import io.smarthappy.module.aps.ApsPrDtos.MatSrc;
import io.smarthappy.module.aps.ApsPrDtos.PickError;
import io.smarthappy.module.aps.ApsPrDtos.PlanHead;
import io.smarthappy.module.aps.ApsPrDtos.PrCreated;
import io.smarthappy.module.aps.ApsPrDtos.PrLine;
import io.smarthappy.module.aps.ApsPrDtos.PrLineRef;
import io.smarthappy.module.aps.ApsPrDtos.PrPickForm;
import io.smarthappy.module.aps.ApsPrDtos.PrPreview;
import io.smarthappy.module.aps.ApsPrDtos.SourceSrc;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.module.srm.pr.PrDtos.PrForm;
import io.smarthappy.module.srm.pr.PrDtos.PrLineForm;
import io.smarthappy.module.srm.pr.PrService;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 부족 → 구매 요청 초안 (APS 8단계, docs/38 17.4 — docs/63 G2, 결정 AP-7).
 * 사람이 고른 계획 자재 부족 줄만 → 품목마다 한 줄(부족 합을 주 공급원 MOQ·배수로 올림, {@link LotSizing}) → {@link PrService#create} 로 DRAFT 한 건 →
 * 자재 줄 ↔ 요청 줄 연결은 APS 표 {@code aps_plan_pr}. SRM 표에 직접 쓰지 않고, 자동으로 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ApsPrService {

    static final int MAX_PICK = 200;
    static final String PR_PROG = PrService.PROG;

    private final ApsPrMapper mapper;
    private final PrService prs;
    private final PermissionService permissions;
    private final InboxService inbox;
    private final OrderService orders;
    private final BizClock clock;
    private final MessageSource messages;

    /** 미리보기 — 쓰지 않는다. 줄 문제는 errors 로(만들기는 같은 계산이 400·409) */
    @Transactional(readOnly = true)
    public PrPreview preview(long planId, PrPickForm in) {
        PlanHead p = plan(planId);
        Calc c = calc(p, ids(in));
        return new PrPreview(p.getPlanId(), p.getPlanNo(), p.getPlantCd(), c.needDate(), c.lines(), c.errors());
    }

    /**
     * 만들기 — 요청 키 → (구매 요청 권한) → 계획 머리 잠금 → 다음 문장에서 줄·연결 검사(잠금 문장 안 집계는 옛 스냅샷) →
     * PrService.create → 요청 줄 읽기 → 연결 추가. 한 트랜잭션
     */
    @Transactional
    public PrCreated create(long planId, PrPickForm in) {
        LoginUser me = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        permissions.require(PR_PROG, Action.SAVE);       // APS 가 구매 요청의 뒷문이 되지 않게(반영의 prd-wo SAVE 와 같은 원칙, A13)
        inbox.claimRequestKey("APS_PR_CREATE");
        Set<Long> ids = ids(in);
        if (mapper.lockPlan(planId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        PlanHead p = plan(planId);
        Calc c = calc(p, ids);
        if (!c.errors().isEmpty()) {
            FieldErrors e = FieldErrors.create();
            boolean linked = false;
            for (PickError x : c.errors()) {
                e.row(String.valueOf(x.matId())).add("matId", x.code());
                linked |= "APS_PR_LINKED".equals(x.code());
            }
            throw new BusinessException(linked ? HbErrorCode.CONFLICT : HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
        }
        List<PrLineForm> lines = new ArrayList<>();
        for (PrLine l : c.lines()) {
            lines.add(new PrLineForm(null, l.itemCd(), l.reqQty(), l.needDate().toString(), l.supplierCd(),
                    cut(text("apsPr.lineRemark", p.getPlanNo(), String.join(", ", l.woNos())), 500)));
        }
        long prId = prs.create(new PrForm(p.getPlantCd(), null, c.needDate().toString(), cut(text("apsPr.reason", p.getPlanNo()), 500),
                null, lines));
        List<PrLineRef> refs = mapper.findPrLines(prId);
        Map<Long, Long> lineOfItem = new HashMap<>();
        refs.forEach(r -> lineOfItem.putIfAbsent(r.getItemId(), r.getPrLineId()));
        List<LinkInsert> links = new ArrayList<>();
        for (MatSrc m : c.mats()) {
            Long lineId = lineOfItem.get(m.getItemId());
            if (lineId == null) {
                throw new IllegalStateException("purchase request line missing for item " + m.getItemId());
            }
            links.add(new LinkInsert(m.getPlanMatId(), m.getWoId(), m.getOpSeq(), m.getItemId(), m.getShortQty(), lineId));
        }
        mapper.insertLinks(planId, prId, links, me.loginId());
        return new PrCreated(prId, mapper.findPrNo(prId), refs.size());
    }

    // ------------------------------------------------------------------ 계산(미리보기·만들기 공용)

    private record Calc(LocalDate needDate, List<PrLine> lines, List<PickError> errors, List<MatSrc> mats) {
    }

    private Calc calc(PlanHead p, Set<Long> ids) {
        if (p.isPurged()) {
            throw OrderService.fieldError("status", "APS_PURGED");
        }
        if ("DISCARDED".equals(p.getStatus())) {
            throw OrderService.fieldError("status", "APS_PR_DISCARDED");
        }
        List<MatSrc> mats = mapper.findMats(p.getPlanId(), ids);
        if (mats.size() != ids.size()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);     // 다른 계획·다른 회사·없는 줄 id
        }
        List<PickError> errors = new ArrayList<>();
        List<MatSrc> ok = new ArrayList<>();
        for (MatSrc m : mats) {
            String code = m.getShortQty() == null || m.getShortQty().signum() <= 0 || m.getNeedAt() == null ? "APS_PR_NOT_SHORT"
                    : !"BUY".equals(m.getProcType()) ? "APS_PR_MAKE"
                    : !"Y".equals(m.getItemUseYn()) ? "INACTIVE"
                    : m.isLinked() ? "APS_PR_LINKED" : null;
            if (code != null) {
                errors.add(new PickError(m.getPlanMatId(), code));
            } else {
                ok.add(m);
            }
        }
        ZoneId zone = zone(p);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        Map<Long, List<MatSrc>> byItem = new LinkedHashMap<>();
        ok.forEach(m -> byItem.computeIfAbsent(m.getItemId(), k -> new ArrayList<>()).add(m));
        Map<Long, SourceSrc> src = new HashMap<>();
        if (!byItem.isEmpty()) {
            mapper.findSources(byItem.keySet()).forEach(s -> src.put(s.getItemId(), s));
        }
        Map<String, Integer> scales = orders.unitScales();
        List<PrLine> lines = new ArrayList<>();
        LocalDate head = null;
        for (List<MatSrc> group : byItem.values()) {
            MatSrc first = group.get(0);
            BigDecimal shortSum = BigDecimal.ZERO;
            LocalDate need = null;
            Set<String> wos = new LinkedHashSet<>();
            List<Long> matIds = new ArrayList<>();
            for (MatSrc m : group) {
                shortSum = shortSum.add(m.getShortQty());
                LocalDate d = m.getNeedAt().atZoneSameInstant(zone).toLocalDate();
                need = need == null || d.isBefore(need) ? d : need;
                wos.add(m.getWoNo());
                matIds.add(m.getPlanMatId());
            }
            SourceSrc s = src.get(first.getItemId());
            BigDecimal moq = s == null ? null : s.getMoq();
            BigDecimal mult = s == null ? null : s.getOrderMult();
            BigDecimal req = LotSizing.size(shortSum, moq == null ? LotSizing.EX : LotSizing.MIN, moq, mult,
                    OrderService.scaleOf(scales, first.getUnitCd()));
            int lead = s == null ? 0 : s.getLeadDays();
            LocalDate orderBy = need.minusDays(lead);
            lines.add(new PrLine(first.getItemId(), first.getItemCd(), first.getItemNm(), first.getUnitCd(), plain(shortSum), plain(moq),
                    plain(mult), plain(req), need, lead, orderBy, orderBy.isBefore(today), s == null ? null : s.getCustCd(),
                    s == null ? null : s.getCustNm(), s == null, matIds, List.copyOf(wos)));
            head = head == null || need.isBefore(head) ? need : head;
        }
        if (lines.isEmpty() && errors.isEmpty()) {
            throw OrderService.fieldError("matIds", "REQUIRED");
        }
        return new Calc(head, lines, errors, ok);
    }

    private PlanHead plan(long planId) {
        PlanHead p = mapper.findPlan(planId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    private static Set<Long> ids(PrPickForm in) {
        if (in == null || in.matIds() == null || in.matIds().isEmpty()) {
            throw OrderService.fieldError("matIds", "REQUIRED");
        }
        if (in.matIds().size() > MAX_PICK) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("matIds", "TOO_MANY_LINES", MAX_PICK).list());
        }
        Set<Long> out = new LinkedHashSet<>();
        for (Long id : in.matIds()) {
            if (id == null) {
                throw OrderService.fieldError("matIds", "FORMAT");
            }
            out.add(id);
        }
        return out;
    }

    private ZoneId zone(PlanHead p) {
        try {
            return ZoneId.of(p.getTimeZone());
        } catch (RuntimeException e) {
            return clock.zone(p.getPlantCd());
        }
    }

    private String text(String key, Object... args) {
        return messages.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}
