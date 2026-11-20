package io.smarthappy.module.aps;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.aps.ApsDtos.ApplyEquip;
import io.smarthappy.module.aps.ApsDtos.ApplyForm;
import io.smarthappy.module.aps.ApsDtos.ApplyOpSrc;
import io.smarthappy.module.aps.ApsDtos.ApplyPreview;
import io.smarthappy.module.aps.ApsDtos.ApplyResult;
import io.smarthappy.module.aps.ApsDtos.ApplyWo;
import io.smarthappy.module.aps.ApsDtos.FencedWo;
import io.smarthappy.module.aps.ApsDtos.ApplyWoSrc;
import io.smarthappy.module.aps.ApsDtos.PlanRow;
import io.smarthappy.module.aps.ApsDtos.StaleRow;
import io.smarthappy.module.production.WoDtos.AssignForm;
import io.smarthappy.module.production.WoDtos.WoForm;
import io.smarthappy.module.production.WoDtos.WoRow;
import io.smarthappy.module.production.WoService;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.FieldErrors.FieldError;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 계획 반영 (aps-plan) — APS 2단계, docs/38 11장.
 *
 * - 지시 표에 직접 쓰지 않는다: 일정은 {@link WoService#update}, 설비 배정은 {@link WoService#assign} (MES 규칙·rowVer·감사가 한 곳에)
 * - 낡은 계획(계산 때 지시·공정 row_ver 사본과 다름, 새 대상 지시)은 거부 — 미리보기와 실행이 같은 {@link #build}
 * - 실행 = 한 트랜잭션: 계획 잠금 → 지시 잠금(번호 순) → 다시 계산 → MES 호출 → APPLIED. 하나라도 실패하면 전부 취소
 */
@Service
@RequiredArgsConstructor
public class ApsApplyService {

    static final String WO_PROG = "prd-wo";

    private final ApsMapper mapper;
    private final WoService wos;
    private final PermissionService permissions;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;

    @Transactional(readOnly = true)
    public ApplyPreview preview(long planId) {
        return build(notPurged(plan(planId)));
    }

    @Transactional
    public ApplyResult apply(long planId, ApplyForm in) {
        LoginUser me = me();
        permissions.require(WO_PROG, Action.SAVE);      // APS 가 지시 변경의 뒷문이 되지 않게(A13)
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        inbox.claimRequestKey("APS_PLAN_APPLY");
        String st = mapper.lockPlan(planId);
        if (st == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"CALCULATED".equals(st)) {
            throw OrderService.fieldError("status", "STATE");
        }
        PlanRow p = notPurged(plan(planId));
        if (p.getRowVer() != in.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.lockWos(planId);
        ApplyPreview pv = build(p);
        if (pv.expired()) {
            throw OrderService.fieldError("status", "APS_EXPIRED");
        }
        if (!pv.stale().isEmpty()) {
            FieldErrors e = FieldErrors.create();
            pv.stale().forEach(s -> e.row(s.woNo() == null ? p.getPlanNo() : s.woNo()).add("woNo", s.code()));
            throw new BusinessException(HbErrorCode.APS_PLAN_STALE).withFieldErrors(e.list());
        }
        for (ApplyWo w : pv.wos()) {
            try {
                if (!w.toStart().equals(w.fromStart()) || !w.toEnd().equals(w.fromEnd())) {
                    WoRow cur = wos.detail(w.woId()).wo();
                    wos.update(w.woId(), new WoForm(cur.getPlantCd(), cur.getItemCd(), cur.getPlanQty(), w.toStart().toString(),
                            w.toEnd().toString(), cur.getRoutingId(), cur.getBomId(), cur.getOrderLineId(), cur.getPriority(),
                            cur.getRemark(), cur.getRowVer()));
                }
                for (ApplyEquip q : w.equips()) {
                    wos.assign(q.woProcId(), new AssignForm(q.toEquipCd(), q.rowVer()));
                }
            } catch (BusinessException ex) {
                throw withWo(ex, w.woNo());
            }
        }
        if (mapper.markApplied(planId, in.rowVer(), me.loginId(), pv.woCnt(), pv.opCnt()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return new ApplyResult(pv.woCnt(), pv.opCnt());
    }

    /** 바뀔 값·낡음 — 미리보기와 실행이 같은 계산(실행은 잠근 뒤 다시 부른다) */
    ApplyPreview build(PlanRow p) {
        ZoneId zone = ZoneId.of(p.getTimeZone());
        List<ApplyWoSrc> src = mapper.findApplyWos(p.getPlanId());
        Map<Long, List<ApplyOpSrc>> ops = new HashMap<>();
        mapper.findApplyOps(p.getPlanId()).forEach(o -> ops.computeIfAbsent(o.getWoId(), k -> new ArrayList<>()).add(o));
        List<StaleRow> stale = new ArrayList<>();
        if (src.stream().anyMatch(w -> w.getWoRowVer() == null)) {
            stale.add(new StaleRow(null, "APS_OLD_PLAN"));     // 1단계 계획 — 사본이 없어 판정할 수 없다
        } else {
            for (ApplyWoSrc w : src) {
                if (w.getWoRowVer() != w.getCurRowVer()) {
                    stale.add(new StaleRow(w.getWoNo(), "APS_WO_CHANGED"));
                } else if (ops.getOrDefault(w.getWoId(), List.of()).stream()
                        .anyMatch(o -> !Objects.equals(o.getProcRowVer(), o.getCurRowVer()))) {
                    stale.add(new StaleRow(w.getWoNo(), "APS_PROC_CHANGED"));
                }
            }
        }
        LocalDate last = p.getHorizonStart().plusDays(p.getHorizonDays() - 1L);
        mapper.findNewWos(p.getPlanId(), p.getPlantCd(), last).forEach(no -> stale.add(new StaleRow(no, "APS_NEW_WO")));

        // 확정 구간(docs/38 18.3): 공장 오늘 + 일수(품목별 계획 기준정보 → 공장 정책), 지금 시작일이 구간 안이면 그대로(사유 있는 고정은 예외)
        int plantFence = Integer.parseInt(policies.value(Policies.APS_FENCE_DAYS, p.getPlantCd()));
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        List<FencedWo> fenced = new ArrayList<>();
        List<ApplyWo> out = new ArrayList<>();
        int opCnt = 0;
        int pushed = 0;
        for (ApplyWoSrc w : src) {
            if (w.getStartAt() == null || w.getExcCd() != null) {
                continue;               // 계획 못 한 지시는 그대로(A11)
            }
            LocalDate[] to = targetDates(w.getStartAt(), w.getEndAt(), zone, "IN_PROGRESS".equals(w.getCurStatus()), w.getCurStart());
            LocalDate toStart = to[0];
            LocalDate toEnd = to[1];
            List<ApplyEquip> eq = new ArrayList<>();
            for (ApplyOpSrc o : ops.getOrDefault(w.getWoId(), List.of())) {
                if (o.isScheduled() && "EQUIP".equals(o.getResKind()) && !Objects.equals(o.getEquipId(), o.getCurEquipId())) {
                    eq.add(new ApplyEquip(o.getWoProcId(), o.getOpSeq(), o.getProcCd(), o.getCurEquipCd(), o.getEquipCd(),
                            o.getCurRowVer()));
                }
            }
            boolean moved = !toStart.equals(w.getCurStart()) || !toEnd.equals(w.getCurEnd());
            if (!moved && eq.isEmpty()) {
                continue;               // 같으면 건드리지 않는다(row_ver 를 올리지 않음)
            }
            int fenceDays = w.getItemFenceDays() != null ? w.getItemFenceDays() : plantFence;
            if (fenceDays > 0 && !w.isPinReason() && w.getCurStart().isBefore(today.plusDays(fenceDays))) {
                fenced.add(new FencedWo(w.getWoId(), w.getWoNo(), w.getCurStatus(), w.getCurStart(), w.getCurEnd(), toStart, toEnd,
                        fenceDays, eq.size()));
                continue;               // 구간 안 — 바꾸지 않는다(사유 있는 고정으로만)
            }
            boolean late = toEnd.isAfter(w.getSnapEnd());
            out.add(new ApplyWo(w.getWoId(), w.getWoNo(), w.getCurStatus(), w.getCurStart(), w.getCurEnd(), toStart, toEnd, late, eq));
            opCnt += eq.size();
            pushed += late ? 1 : 0;
        }
        boolean expired = !clock.instant().isBefore(p.getSchedTo().toInstant());
        return new ApplyPreview(p, out, stale, expired, out.size(), opCnt, pushed, fenced, plantFence);
    }

    /**
     * 계획 시각 → 지시 계획 시작일·완료일(A14): 시작 = 첫 공정 시작의 공장 날짜, 완료 = 마지막 공정 끝 1초 전의 공장 날짜(자정 정각에
     * 끝나면 전날, 길이 0 이면 시작일). 진행 중 지시는 시작일을 두고 완료일만 — 지금 시작일이 새 완료일보다 뒤면 완료일로 당긴다
     */
    static LocalDate[] targetDates(OffsetDateTime start, OffsetDateTime end, ZoneId zone, boolean inProgress, LocalDate curStart) {
        LocalDate s = local(start, zone);
        LocalDate e = local(end.minusSeconds(1), zone);
        if (e.isBefore(s)) {
            e = s;
        }
        if (inProgress) {
            s = curStart.isAfter(e) ? e : curStart;
        }
        return new LocalDate[] {s, e};
    }

    /** MES 검사 오류에 지시 번호를 붙여 다시 던진다 — 어느 지시가 반영을 막았는지 */
    private static BusinessException withWo(BusinessException ex, String woNo) {
        List<FieldError> errs = new ArrayList<>();
        ex.fieldErrors().forEach(f -> errs.add(new FieldError(woNo, f.field(), f.code(), f.args())));
        if (errs.isEmpty()) {
            errs.add(new FieldError(woNo, "woNo", "APS_APPLY_FAILED"));
        }
        return new BusinessException(ex.code(), ex.details(), ex.args()).withFieldErrors(errs);
    }

    /** 결과를 보존 기간이 지나 정리한 계획은 반영·미리보기 불가(A35) */
    static PlanRow notPurged(PlanRow p) {
        if (p.getPurgedAt() != null) {
            throw OrderService.fieldError("status", "APS_PURGED");
        }
        return p;
    }

    private PlanRow plan(long planId) {
        PlanRow p = mapper.findPlan(planId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    private static LocalDate local(OffsetDateTime t, ZoneId zone) {
        return t.atZoneSameInstant(zone).toLocalDate();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
