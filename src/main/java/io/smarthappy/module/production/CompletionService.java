package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.CompletionDtos.CloseCheck;
import io.smarthappy.module.production.CompletionDtos.CloseForm;
import io.smarthappy.module.production.CompletionDtos.Completion;
import io.smarthappy.module.production.CompletionDtos.NewClose;
import io.smarthappy.module.production.CompletionDtos.NewRework;
import io.smarthappy.module.production.CompletionDtos.NoticeLine;
import io.smarthappy.module.production.CompletionDtos.ProcQty;
import io.smarthappy.module.production.CompletionDtos.ReworkForm;
import io.smarthappy.module.production.CompletionDtos.ReworkReply;
import io.smarthappy.module.production.CompletionDtos.ReworkRow;
import io.smarthappy.module.production.WoDtos.ActForm;
import io.smarthappy.module.production.WoDtos.WoRow;
import io.smarthappy.module.production.erp.ErpPublisher;
import io.smarthappy.module.production.link.CloseNotice.CloseContext;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 공정 완료판정·재작업/수리 처리·지시 완료·마감 (P2 7a, docs/25 4.9). 권한은 작업지시 화면(prd-wo).
 *
 * - 판정 = 미리보기와 같은 계산(check) → 막는 이유가 없으면 판정 원장 + 공정 READY → DONE. 트리거가 같은 계산으로 최종 확인
 * - 보존식: 투입(앞 공정 유효 양품, 첫 공정은 처리 합계 — D15) = 양품 + 폐기 + 재작업 + 수리, 허용 오차 = 공장 정책 PROC_BALANCE_TOL_PCT
 * - 재작업·수리 처리: 대기 수량 → 양품(새 산출 LOT) + 폐기. 같은 요청 id 는 처음 응답
 * - 지시 완료 = 모든 공정 DONE, 마감 = 완료에서(미입고는 보여만 준다 — D16)
 * - 다른 모듈 경고(CloseNotice 빈, 4.46)는 미리보기·판정에서 notices 로만 — 막지 않는다
 */
@Service
@RequiredArgsConstructor
public class CompletionService {

    static final String POLICY_TOL = "PROC_BALANCE_TOL_PCT";
    private static final Pattern REQ = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final CompletionMapper mapper;
    private final WoMapper woMapper;
    private final LotService lots;
    private final OrderService orders;
    private final PolicyService policies;
    private final BizClock clock;
    private final ErpPublisher erp;
    private final CloseNotices notices;
    /** 막는 이유 확장점(link.CloseBlock, docs/72 2.3). 없으면 지금 그대로 */
    private final org.springframework.beans.factory.ObjectProvider<io.smarthappy.module.production.link.CloseBlock> blocks;

    // ------------------------------------------------------------------ 조회

    /** 지시의 공정별 판정 미리보기 + 마지막 공정 양품·입고 */
    @Transactional(readOnly = true)
    public Completion completion(long woId) {
        WoRow w = woMapper.findWo(woId);
        if (w == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<ProcQty> procs = mapper.procQtys(woId);
        List<CloseCheck> checks = new ArrayList<>();
        ProcQty prev = null;
        for (ProcQty p : procs) {
            checks.add(withNotices(check(p, prev), p, true));
            prev = p;
        }
        BigDecimal lastGood = prev == null ? BigDecimal.ZERO : prev.getGood().add(prev.getDispGood());
        BigDecimal received = mapper.receivedQty(woId);
        return new Completion(woId, w.getWoNo(), w.getStatus(), w.getRowVer(), w.getUnitCd(), checks, plain(lastGood), plain(received),
                plain(lastGood.subtract(received).max(BigDecimal.ZERO)));
    }

    @Transactional(readOnly = true)
    public CloseCheck check(long woProcId) {
        ProcQty p = proc(woProcId);
        return withNotices(check(p, mapper.prevProc(p.getWoId(), p.getOpSeq())), p, true);
    }

    // ------------------------------------------------------------------ 완료판정

    @Transactional
    public CloseCheck close(long woProcId, CloseForm in) {
        return closeAs(woProcId, in, me().loginId());
    }

    /** 요청 밖(데모 시더)에서 완료판정 — 판정자 = by */
    @Transactional
    public CloseCheck closeAs(long woProcId, CloseForm in, String by) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String remark = OrderService.text(in.remark(), "remark", 500, e);
        e.throwIfAny();
        Long woId = woMapper.procWoId(woProcId);
        if (woId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        // 지시 → 공정 순으로 잠근다(완료·처리·정정과 줄 세움)
        woMapper.lockWo(woId);
        mapper.lockProc(woProcId);
        ProcQty p = proc(woProcId);
        if (p.getRowVer() != in.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        ProcQty prev = mapper.prevProc(p.getWoId(), p.getOpSeq());
        CloseCheck c = check(p, prev);
        if (!c.problems().isEmpty()) {
            throw problem(c);
        }
        BigDecimal output = new BigDecimal(c.outputQty());
        BigDecimal input = new BigDecimal(c.inputQty());
        try {
            mapper.insertClose(new NewClose(p.getWoId(), woProcId, input, p.getGood(), p.getScrap(), p.getRework(), p.getRepair(),
                    p.getGood().add(p.getDispGood()), output.subtract(input), c.tolPct(), c.firstOp() ? "Y" : "N", remark),
                    clock.instant(), by);
            if (mapper.closeProc(woProcId, in.rowVer(), by) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
        // 판정 행을 쓴 뒤 경고만 모은다(막지 않음 — 빈 예외는 세이브포인트로 되돌리고 무시)
        var found = notices.collect(context(p, false));
        ProcQty after = proc(woProcId);
        CloseCheck done = check(after, mapper.prevProc(after.getWoId(), after.getOpSeq()));
        return copy(done, found);
    }

    /** 판정 전 공정(DONE 아님)이면 경고 빈을 부른다 — 끝난 공정은 다시 부르지 않는다 */
    private CloseCheck withNotices(CloseCheck c, ProcQty p, boolean preview) {
        return "DONE".equals(p.getStatus()) ? c : copy(c, notices.collect(context(p, preview)));
    }

    private CloseContext context(ProcQty p, boolean preview) {
        return new CloseContext(p.getPlantCd(), p.getWoId(), p.getWoProcId(), p.getProcId(), p.getItemId(),
                List.copyOf(mapper.outputLots(p.getWoProcId())), preview);
    }

    private static CloseCheck copy(CloseCheck c, List<NoticeLine> found) {
        return new CloseCheck(c.woProcId(), c.opSeq(), c.procCd(), c.procNm(), c.status(), c.rowVer(), c.firstOp(), c.prevOpSeq(),
                c.prevStatus(), c.inputQty(), c.goodQty(), c.scrapQty(), c.reworkQty(), c.repairQty(), c.reworkWait(), c.repairWait(),
                c.dispGoodQty(), c.effGoodQty(), c.outputQty(), c.diffQty(), c.tolPct(), c.tolQty(), c.unitCd(), c.problems(), found);
    }

    /** 미리보기·판정 공용 — 막는 이유를 모두 모은다 */
    CloseCheck check(ProcQty p, ProcQty prev) {
        List<String> problems = new ArrayList<>();
        if (!"READY".equals(p.getStatus()) || !"IN_PROGRESS".equals(p.getWoStatus())) {
            problems.add("STATE");
        }
        if (prev != null && !"DONE".equals(prev.getStatus())) {
            problems.add("PREV_OPEN");
        }
        BigDecimal reworkWait = p.getRework().subtract(p.getReworkDone());
        BigDecimal repairWait = p.getRepair().subtract(p.getRepairDone());
        if (reworkWait.signum() != 0 || repairWait.signum() != 0) {
            problems.add("PENDING");
        }
        BigDecimal output = p.getGood().add(p.getScrap()).add(p.getRework()).add(p.getRepair());
        if (output.signum() <= 0) {
            problems.add("NO_RESULT");
        }
        // 산출 LOT 이 보류(부적합 처분 대기 등) 중이면 공정 수량이 아직 정해지지 않았다 — 완료판정하지 않는다(docs/72 3.1)
        if (!"DONE".equals(p.getStatus()) && mapper.heldOutputLots(p.getWoProcId()) > 0) {
            problems.add("LOT_HOLD");
        }
        BigDecimal input = prev == null ? output : prev.getGood().add(prev.getDispGood());
        int tol = tolPct(p.getPlantCd());
        BigDecimal tolQty = input.multiply(BigDecimal.valueOf(tol)).divide(BigDecimal.valueOf(100), 6, RoundingMode.DOWN);
        BigDecimal diff = output.subtract(input);
        // 트리거와 같은 식: |차이| × 100 ≤ 투입 × 허용 %
        if (prev != null && output.signum() > 0 && diff.abs().multiply(BigDecimal.valueOf(100)).compareTo(input.multiply(BigDecimal.valueOf(tol))) > 0) {
            problems.add("BALANCE");
        }
        // 다른 모듈의 막는 이유(link.CloseBlock) — 끝난 공정에는 부르지 않는다
        if (!"DONE".equals(p.getStatus())) {
            blocks.orderedStream().forEach(b -> problems.addAll(b.problems(context(p, true))));
        }
        if ("DONE".equals(p.getStatus())) {
            problems.clear();
            problems.add("DONE");
        }
        return new CloseCheck(p.getWoProcId(), p.getOpSeq(), p.getProcCd(), p.getProcNm(), p.getStatus(), p.getRowVer(), prev == null,
                prev == null ? null : prev.getOpSeq(), prev == null ? null : prev.getStatus(), plain(input), plain(p.getGood()),
                plain(p.getScrap()), plain(p.getRework()), plain(p.getRepair()), plain(reworkWait), plain(repairWait),
                plain(p.getDispGood()), plain(p.getGood().add(p.getDispGood())), plain(output), plain(diff), tol, plain(tolQty),
                p.getUnitCd(), problems, List.of());
    }

    private int tolPct(String plantCd) {
        try {
            return Integer.parseInt(policies.value(POLICY_TOL, plantCd));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 첫 번째 막는 이유 → 칸 오류(status). 보존식은 투입·처리·허용 오차를 문구에 */
    private static BusinessException problem(CloseCheck c) {
        String code = c.problems().get(0);
        FieldErrors e = FieldErrors.create();
        if ("BALANCE".equals(code)) {
            e.add("status", "BALANCE", c.inputQty(), c.outputQty(), c.tolPct());
        } else {
            e.add("status", "DONE".equals(code) ? "STATE" : code);
        }
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
    }

    // ------------------------------------------------------------------ 재작업·수리 처리

    @Transactional
    public ReworkReply rework(long woProcId, ReworkForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String req = in.clientReqId() == null ? null : in.clientReqId().strip();
        if (req == null || !REQ.matcher(req).matches()) {
            throw OrderService.fieldError("clientReqId", "FORMAT");
        }
        ReworkRow done = mapper.findReworkByReq(req);
        if (done != null) {
            if (done.getWoProcId() != woProcId) {
                throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
            }
            return new ReworkReply(done.getReworkId(), done.getLotNo(), true);
        }
        Long woId = woMapper.procWoId(woProcId);
        if (woId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        woMapper.lockWo(woId);
        mapper.lockProc(woProcId);
        ProcQty p = proc(woProcId);
        FieldErrors e = FieldErrors.create();
        String kind = in.kind() == null ? null : in.kind().strip().toUpperCase(Locale.ROOT);
        if (!"REWORK".equals(kind) && !"REPAIR".equals(kind)) {
            e.add("kind", "CODE");
        }
        int scale = OrderService.scaleOf(orders.unitScales(), p.getUnitCd());
        BigDecimal good = zero(OrderService.qty(in.goodQty(), "goodQty", scale, false, e));
        BigDecimal scrap = zero(OrderService.qty(in.scrapQty(), "scrapQty", scale, false, e));
        String reason = OrderService.text(in.reason(), "reason", 500, e);
        e.throwIfAny();
        BigDecimal qty = good.add(scrap);
        if (qty.signum() <= 0) {
            throw OrderService.fieldError("goodQty", "POSITIVE");
        }
        if ("DONE".equals(p.getStatus()) || !"IN_PROGRESS".equals(p.getWoStatus())) {
            throw OrderService.fieldError("status", "STATE");
        }
        BigDecimal wait = "REWORK".equals(kind) ? p.getRework().subtract(p.getReworkDone()) : p.getRepair().subtract(p.getRepairDone());
        if (qty.compareTo(wait) > 0) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("goodQty", "OVER_PENDING", plain(wait)).list());
        }
        var at = clock.instant();
        try {
            Long lotId = good.signum() > 0 ? lots.createProduced(p.getWoId(), good, at, me.loginId()) : null;
            if (lotId != null) {
                // 지시·지시 공정 행을 위에서 잠갔다 → 표시(ECO 행). 처리 LOT 은 계보 부모가 없어 표시는 plm_eco_lot 에만(3c)
                lots.mark(lotId, p.getWoId(), p.getEquipId(), at);
            }
            long id = mapper.insertRework(new NewRework(p.getWoId(), woProcId, kind, qty, good, scrap, p.getUnitCd(), lotId, reason, req),
                    at, me.loginId());
            ReworkRow row = mapper.findReworkByReq(req);
            return new ReworkReply(id, row == null ? null : row.getLotNo(), false);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    // ------------------------------------------------------------------ 지시 완료·마감

    @Transactional
    public void complete(long woId, ActForm in) {
        completeAs(woId, in, me().loginId());
    }

    /** 요청 밖(데모 시더)에서 지시 완료 */
    @Transactional
    public void completeAs(long woId, ActForm in, String by) {
        int rowVer = rowVer(in);
        String st = woMapper.lockWo(woId);
        if (st == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"IN_PROGRESS".equals(st)) {
            throw OrderService.fieldError("status", "STATE");
        }
        if (mapper.openProcs(woId) > 0) {
            throw OrderService.fieldError("status", "PROCS_OPEN");
        }
        try {
            if (mapper.complete(woId, rowVer, clock.instant(), by) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
        // ERP 실적 송신(정책 ERP_SYSTEM 이 어댑터일 때만) — 같은 트랜잭션의 아웃박스 행, 송신은 릴레이(docs/25 4.11)
        erp.woCompleted(woId);
    }

    @Transactional
    public void closeWo(long woId, ActForm in) {
        LoginUser me = me();
        int rowVer = rowVer(in);
        String st = woMapper.lockWo(woId);
        if (st == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"DONE".equals(st)) {
            throw OrderService.fieldError("status", "STATE");
        }
        if (mapper.closeWo(woId, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 도우미

    private ProcQty proc(long woProcId) {
        ProcQty p = mapper.procQty(woProcId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    private static int rowVer(ActForm in) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return in.rowVer();
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static String plain(BigDecimal v) {
        return v == null ? null : v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    /** 트리거 제약 이름 → 칸 오류 (서비스가 먼저 거르지만 동시 변경·직접 경로의 마지막 방어) */
    static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "ck_prd_wo_proc_close_state", "ck_prd_wo_proc_close_required", "ck_prd_rework_proc_open" -> OrderService.fieldError("status", "STATE");
            case "ck_prd_wo_proc_close_prev" -> OrderService.fieldError("status", "PREV_OPEN");
            case "ck_prd_wo_proc_close_pending" -> OrderService.fieldError("status", "PENDING");
            case "ck_prd_wo_proc_close_empty" -> OrderService.fieldError("status", "NO_RESULT");
            case "ck_prd_wo_proc_close_balance", "ck_prd_wo_proc_close_values" -> new BusinessException(HbErrorCode.CONFLICT);
            case "ck_prd_rework_pending" -> new BusinessException(HbErrorCode.CONFLICT);
            case "ck_prd_wo_procs_done" -> OrderService.fieldError("status", "PROCS_OPEN");
            case "uq_prd_wo_proc_close" -> new BusinessException(HbErrorCode.CONFLICT);
            default -> e;
        };
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
