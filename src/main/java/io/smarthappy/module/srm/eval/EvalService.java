package io.smarthappy.module.srm.eval;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.fieldError;
import static io.smarthappy.module.srm.SrmChecks.me;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.SrmNumbers;
import io.smarthappy.module.srm.eval.EvalDtos.AckRate;
import io.smarthappy.module.srm.eval.EvalDtos.ActionForm;
import io.smarthappy.module.srm.eval.EvalDtos.CertState;
import io.smarthappy.module.srm.eval.EvalDtos.EvalCrit;
import io.smarthappy.module.srm.eval.EvalDtos.EvalDetail;
import io.smarthappy.module.srm.eval.EvalDtos.EvalForm;
import io.smarthappy.module.srm.eval.EvalDtos.EvalPage;
import io.smarthappy.module.srm.eval.EvalDtos.EvalRow;
import io.smarthappy.module.srm.eval.EvalDtos.EvalSort;
import io.smarthappy.module.srm.eval.EvalDtos.ItemSpec;
import io.smarthappy.module.srm.eval.EvalDtos.OtdRate;
import io.smarthappy.module.srm.eval.EvalDtos.Preview;
import io.smarthappy.module.srm.eval.EvalDtos.ScoreForm;
import io.smarthappy.module.srm.eval.EvalDtos.ScoreSpec;
import io.smarthappy.module.srm.eval.EvalDtos.ScoresForm;
import io.smarthappy.module.srm.eval.EvalDtos.SupplierOption;
import io.smarthappy.module.srm.eval.EvalDtos.TmplRow;
import io.smarthappy.module.srm.supplier.CertStatus;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 협력사 평가 (srm-eval) — SRM S6, docs/31 4.6, D10·D11.
 * 만들기 = 협력사 × 기간 × 템플릿(기간 끝 날에 유효한 확정 리비전) + 항목마다 점수 행 + AUTO 계산.
 * 점수 저장(작성 중, 사람 입력 항목만) → 확정(APPROVE): 모든 항목 점수 → EvalScorer(총점·등급·과락·HOLD 제안) → 불변.
 * AUTO 1부 계산: ACK_RATE(기간 발행 개정 중 회신 기한 안 회신 비율)·CERT_VALID(기간 끝 날 현재 인증 유효).
 * 2부 R2: OTD_RATE(요구 납기가 기간 안·오늘 이하인 행 중 납기까지 다 받은 행 비율, 입고 이력 — docs/31 4.10).
 * 2부 R4: RECV_PPM(QMS 계약 {@link SupplierQualityQuery#receiptQuality} — 불량 ÷ 검사 수량 × 10⁶, 템플릿 구간 표로 점수 — docs/31 4.12.3).
 * 거래 상태는 바꾸지 않는다 — HOLD 제안만(D11, 사람이 협력사 정보에서).
 */
@Service
@RequiredArgsConstructor
public class EvalService {

    public static final String PROG = "srm-eval";
    static final int SEARCH_MAX_DAYS = 731;
    static final int PERIOD_MAX_DAYS = 366;
    static final Set<String> STATUS = Set.of("DRAFT", "CONFIRMED", "CANCELLED");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final EvalMapper mapper;
    private final SrmNumbers numbers;
    private final InboxService inbox;
    private final PolicyService policies;
    private final DateRanges ranges;
    private final BizClock clock;
    private final ObjectProvider<SupplierQualityQuery> quality;

    // ------------------------------------------------------------------ 조회

    /** 기간 = 평가 기간 끝 날 기준 */
    @Transactional(readOnly = true)
    public List<EvalRow> list(String from, String to, String status, String keyword) {
        return mapper.findEvals(criteria(from, to, status, keyword));
    }

    /**
     * grid(UI 단계 3 ①, docs/31 5.2) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link EvalSort}, 최대 3개, 모르는 열 400).
     * 기간 필수·상한도 목록과 같다(행 수 상한 대신 페이지)
     */
    @Transactional(readOnly = true)
    public EvalPage grid(String from, String to, String status, String keyword, Integer page, Integer size, List<String> sort,
            Boolean total) {
        EvalCrit c = criteria(from, to, status, keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, EvalSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("periodTo,desc"));
        return new EvalPage(mapper.findEvalPage(c, pq), pq.isWithTotal() ? mapper.countEvals(c) : null);
    }

    /** grid 열(화면 목록 칸을 나눠서 — 기간 시작·끝, 템플릿·리비전, 점수·등급·과락·보류 제안) — 정렬 가능 = {@link EvalSort} */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("evalNo", "srm.eval.col.evalNo", 120).withSort().withPriority(1),
            ColumnSpec.text("custNm", "srm.eval.col.supplier", 140).withSort(),
            ColumnSpec.of("periodFrom", "srm.eval.col.periodFrom", ColumnType.DATE, 100).withPriority(3),
            ColumnSpec.of("periodTo", "srm.eval.col.periodTo", ColumnType.DATE, 100).withSort(),
            ColumnSpec.text("tmplCd", "srm.eval.col.tmpl", 100).withPriority(3),
            ColumnSpec.of("tmplRev", "srm.eval.col.tmplRev", ColumnType.INT, 60).withPriority(3),
            ColumnSpec.code("status", "srm.eval.col.status", "EVAL_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.dec("totalScore", "srm.eval.col.score", 2, 70).withMinScale(0).withSort(),
            ColumnSpec.text("gradeCd", "srm.eval.col.grade", 60).withSort(),
            ColumnSpec.of("failYn", "srm.eval.col.fail", ColumnType.YN, 60).withPriority(3),
            ColumnSpec.code("suggestTradeStatus", "srm.eval.col.suggest", "TRADE_STATUS", 90).withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록·grid 공통 조건 검증 */
    private EvalCrit criteria(String from, String to, String status, String keyword) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", false, e);
        LocalDate t = date(to, "to", false, e);
        e.throwIfAny();
        ranges.check(f, t, SEARCH_MAX_DAYS);
        String st = code(status);
        if (st != null && !STATUS.contains(st) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new EvalCrit(f, t, st, blank(keyword) ? null : "%" + SrmChecks.like(keyword.strip()) + "%");
    }

    @Transactional(readOnly = true)
    public EvalDetail get(long id) {
        EvalRow ev = mapper.findEval(id);
        if (ev == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        EvalScorer.Result r = score(ev.getTmplId(), id);
        Preview p = new Preview(r.total() == null ? null : r.total().stripTrailingZeros().toPlainString(), r.gradeCd(), r.fail(),
                r.suggestHold(), r.complete(), r.failedItems());
        return new EvalDetail(ev, mapper.findScores(id), mapper.findGrades(ev.getTmplId()), p);
    }

    @Transactional(readOnly = true)
    public List<SupplierOption> suppliers() {
        return mapper.findSuppliers();
    }

    @Transactional(readOnly = true)
    public List<TmplRow> releasedTemplates() {
        return mapper.findReleasedTmpls();
    }

    private EvalScorer.Result score(long tmplId, long evalId) {
        List<EvalScorer.Item> items = mapper.itemSpecs(tmplId).stream()
                .map(i -> new EvalScorer.Item(i.getItemId(), i.getMaxScore(), i.getWeight(), i.getFailBelow())).toList();
        List<EvalScorer.Grade> grades = mapper.gradeSpecs(tmplId).stream()
                .map(g -> new EvalScorer.Grade(g.getGradeCd(), g.getMinScore(), "Y".equals(g.getSuggestHoldYn()))).toList();
        Map<Long, BigDecimal> scores = new HashMap<>();
        mapper.scoreSpecs(evalId).forEach(s -> {
            if (s.getScore() != null) {
                scores.put(s.getItemId(), s.getScore());
            }
        });
        return EvalScorer.score(items, scores, grades);
    }

    // ------------------------------------------------------------------ 만들기

    @Transactional
    public long create(EvalForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_EVAL_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        SupplierOption sup = null;
        String custCd = code(in.custCd());
        if (custCd == null) {
            e.add("custCd", "REQUIRED");
        } else {
            sup = custCd.length() > 30 ? null : mapper.findSupplier(custCd);
            if (sup == null) {
                e.add("custCd", "NO_SUCH_CODE");
            }
        }
        LocalDate from = date(in.periodFrom(), "periodFrom", true, e);
        LocalDate to = date(in.periodTo(), "periodTo", true, e);
        if (from != null && to != null) {
            if (to.isBefore(from)) {
                e.add("periodTo", "INVALID_RANGE");
            } else if (ChronoUnit.DAYS.between(from, to) + 1 > PERIOD_MAX_DAYS) {
                e.add("periodTo", "RANGE_TOO_LONG", ChronoUnit.DAYS.between(from, to) + 1, PERIOD_MAX_DAYS);
            } else if (to.isAfter(clock.today())) {
                e.add("periodTo", "SRM_EVAL_FUTURE");
            }
        }
        String tmplCd = code(in.tmplCd());
        TmplRow tmpl = null;
        if (tmplCd == null) {
            e.add("tmplCd", "REQUIRED");
        } else if (to != null) {
            tmpl = tmplCd.length() > 30 ? null : mapper.findEffectiveTmpl(tmplCd, to);
            if (tmpl == null) {
                e.add("tmplCd", "SRM_EVAL_NO_TMPL");
            }
        }
        String remark = text(in.remark(), "remark", 1000, false, e);
        e.throwIfAny();
        String no = numbers.next(SrmNumbers.EVAL, me.loginId());
        try {
            long id = mapper.insertEval(no, sup.getCustId(), tmpl.getTmplId(), from, to, remark, me.loginId());
            mapper.insertScores(id, tmpl.getTmplId(), me.loginId());
            computeAuto(id, tmpl.getTmplId(), sup.getCustId(), from, to, me.loginId());
            return id;
        } catch (DataIntegrityViolationException ex) {
            if ("uq_srm_eval_period".equals(PgErrors.constraint(ex).orElse(""))) {
                throw fieldError("custCd", "SRM_EVAL_DUP");
            }
            throw ex;
        }
    }

    /**
     * AUTO 항목 계산 — 계산할 수 있으면 auto_yn Y + 점수·원값·근거, 없으면 auto_yn N + 근거(사람이 넣는다, 넣은 점수는 둔다).
     * 근거는 기계 글(화면이 문구로): ACK_RATE:발행:기한 안 / ACK_NONE / OTD:대상:적시 / OTD_NONE / CERT:개수:가장 이른 만료 / CERT_NONE /
     * PPM:검사:불량:처리 중 NCR:검사 밖 NCR / PPM_NONE / PPM_UNITS:단위들 / PPM_BAND:ppm / PART2
     */
    private void computeAuto(long evalId, long tmplId, long custId, LocalDate from, LocalDate to, String by) {
        for (ItemSpec i : mapper.itemSpecs(tmplId)) {
            if (!"AUTO".equals(i.getInputType())) {
                continue;
            }
            switch (i.getMetricCd()) {
                case "ACK_RATE" -> {
                    AckRate a = mapper.ackRate(custId, from, to, clock.zone().getId(), policies.intValue(Policies.SRM_ACK_DUE_DAYS));
                    if (a.getIssuedCnt() == 0) {
                        mapper.updateAuto(evalId, i.getItemId(), "N", null, null, "ACK_NONE", by);
                    } else {
                        BigDecimal on = BigDecimal.valueOf(a.getOnTimeCnt());
                        BigDecimal all = BigDecimal.valueOf(a.getIssuedCnt());
                        BigDecimal raw = on.multiply(HUNDRED).divide(all, 4, RoundingMode.HALF_UP);
                        BigDecimal s = i.getMaxScore().multiply(on).divide(all, 2, RoundingMode.HALF_UP);
                        mapper.updateAuto(evalId, i.getItemId(), "Y", s, raw, "ACK_RATE:" + a.getIssuedCnt() + ":" + a.getOnTimeCnt(), by);
                    }
                }
                case "CERT_VALID" -> {
                    CertState c = mapper.certState(custId, to);
                    String st = CertStatus.ofSupplier(c.getCurrentCnt(), c.getMinExpires(), to, 0);
                    boolean ok = CertStatus.VALID.equals(st) || CertStatus.DUE_SOON.equals(st);     // 만료일 당일까지 유효
                    String basis = c.getCurrentCnt() == 0 ? "CERT_NONE"
                            : "CERT:" + c.getCurrentCnt() + ":" + (c.getMinExpires() == null ? "" : c.getMinExpires());
                    mapper.updateAuto(evalId, i.getItemId(), "Y", ok ? i.getMaxScore() : BigDecimal.ZERO, ok ? HUNDRED : BigDecimal.ZERO,
                            basis, by);
                }
                case "OTD_RATE" -> {
                    OtdRate o = mapper.otdRate(custId, from, to);   // 기간 끝 ≤ 오늘(SRM_EVAL_FUTURE)이라 납기가 안 온 행은 자연히 빠진다(R2-3)
                    if (o.getDueCnt() == 0) {
                        mapper.updateAuto(evalId, i.getItemId(), "N", null, null, "OTD_NONE", by);
                    } else {
                        BigDecimal on = BigDecimal.valueOf(o.getOnTimeCnt());
                        BigDecimal all = BigDecimal.valueOf(o.getDueCnt());
                        BigDecimal raw = on.multiply(HUNDRED).divide(all, 4, RoundingMode.HALF_UP);
                        BigDecimal s = i.getMaxScore().multiply(on).divide(all, 2, RoundingMode.HALF_UP);
                        mapper.updateAuto(evalId, i.getItemId(), "Y", s, raw, "OTD:" + o.getDueCnt() + ":" + o.getOnTimeCnt(), by);
                    }
                }
                case "RECV_PPM" -> recvPpm(evalId, tmplId, i, custId, from, to, by);
                default -> mapper.updateAuto(evalId, i.getItemId(), "N", null, null, "PART2", by);   // 모르는 지표 — 사람 입력
            }
        }
    }

    /**
     * 입고 PPM (R4, docs/31 4.12.3) — QMS 수입검사 품질(판정 시각이 기간 안, LOT 마다 마지막 판정)의 Σ불량 ÷ Σ검사 수량 × 10⁶(소수 2자리 HALF_UP),
     * 점수 = 그 PPM 이 드는 구간 비율 × 배점({@link EvalScorer#ppmScore}). 사람 입력으로 남기는 경우:
     * 검사 수량 0(QMS 없음 포함) → PPM_NONE / 단위가 둘 이상(수량을 더할 수 없음) → PPM_UNITS / 맞는 구간 없음 → PPM_BAND.
     * 검사 밖 NCR(otherNcQty)·처리 중 NCR 수는 PPM 에 넣지 않고 근거에만(확정 전 사람이 본다 — QMS 답 ②).
     */
    private void recvPpm(long evalId, long tmplId, ItemSpec i, long custId, LocalDate from, LocalDate to, String by) {
        List<SupplierQualityQuery.ItemQuality> rows = SupplierQualityQuery.of(quality).receiptQuality(custId, from, to);
        BigDecimal inspected = rows.stream().map(SupplierQualityQuery.ItemQuality::inspectedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (inspected.signum() <= 0) {
            mapper.updateAuto(evalId, i.getItemId(), "N", null, null, "PPM_NONE", by);
            return;
        }
        List<String> units = rows.stream().filter(r -> r.inspectedQty().signum() > 0).map(SupplierQualityQuery.ItemQuality::unitCd)
                .distinct().sorted().toList();
        if (units.size() > 1) {
            mapper.updateAuto(evalId, i.getItemId(), "N", null, null, "PPM_UNITS:" + String.join(",", units), by);
            return;
        }
        BigDecimal rejected = rows.stream().map(SupplierQualityQuery.ItemQuality::rejectedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ppm = rejected.multiply(MILLION).divide(inspected, 2, RoundingMode.HALF_UP);
        List<EvalScorer.Band> bands = mapper.findBands(tmplId).stream().filter(b -> b.getItemId() == i.getItemId())
                .map(b -> new EvalScorer.Band(b.getUpperPpm() == null ? null : new BigDecimal(b.getUpperPpm()), new BigDecimal(b.getScorePct())))
                .toList();
        BigDecimal score = EvalScorer.ppmScore(bands, i.getMaxScore(), ppm);
        if (score == null) {
            mapper.updateAuto(evalId, i.getItemId(), "N", null, ppm, "PPM_BAND:" + plain(ppm), by);
            return;
        }
        int open = rows.stream().mapToInt(SupplierQualityQuery.ItemQuality::openNcrCnt).sum();
        BigDecimal other = rows.stream().map(SupplierQualityQuery.ItemQuality::otherNcQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        mapper.updateAuto(evalId, i.getItemId(), "Y", score, ppm,
                "PPM:" + plain(inspected) + ":" + plain(rejected) + ":" + open + ":" + plain(other), by);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ 점수 · 상태

    /** 점수 저장(작성 중) — 행 키 = 보낸 배열 순번. 자동 계산된 항목은 바꿀 수 없다(SRM_EVAL_AUTO), 빈 점수 = 지움 */
    @Transactional
    public void saveScores(long id, ScoresForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        EvalRow cur = start(id, in.rowVer(), "DRAFT");
        Map<Long, ItemSpec> items = new HashMap<>();
        mapper.itemSpecs(cur.getTmplId()).forEach(i -> items.put(i.getItemId(), i));
        Map<Long, String> auto = new HashMap<>();
        mapper.scoreSpecs(id).forEach(s -> auto.put(s.getItemId(), s.getAutoYn()));
        FieldErrors e = FieldErrors.create();
        String remark = text(in.remark(), "remark", 1000, false, e);
        List<Object[]> writes = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<ScoreForm> list = in.scores() == null ? List.of() : in.scores();
        for (int k = 0; k < list.size(); k++) {
            ScoreForm f = list.get(k);
            if (f == null || f.itemId() == null || !items.containsKey(f.itemId()) || !seen.add(f.itemId())) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);     // 다른 템플릿(다른 회사 포함)의 항목·같은 항목 두 번
            }
            ItemSpec it = items.get(f.itemId());
            FieldErrors le = FieldErrors.create();
            BigDecimal s = SrmChecks.nonNegative(f.score(), "score", 2, 5, false, le);
            if (s != null && s.compareTo(it.getMaxScore()) > 0) {
                le.add("score", "SRM_EVAL_OVER_MAX", it.getMaxScore().stripTrailingZeros().toPlainString());
            }
            String comment = text(f.comment(), "comment", 500, false, le);
            if ("Y".equals(auto.get(f.itemId())) && !blank(f.score())) {
                le.add("score", "SRM_EVAL_AUTO");
            }
            if (le.isEmpty()) {
                writes.add(new Object[] {f.itemId(), "Y".equals(auto.get(f.itemId())) ? null : s, comment});
            }
            String key = String.valueOf(k);
            le.list().forEach(x -> e.row(key).add(x.field(), x.code(), x.args()));
        }
        e.throwIfAny();
        if (mapper.touchEval(id, remark, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        for (Object[] w : writes) {
            long itemId = (Long) w[0];
            if ("Y".equals(auto.get(itemId))) {
                mapper.updateAutoComment(id, itemId, (String) w[2], me.loginId());
            } else {
                mapper.updateScore(id, itemId, (BigDecimal) w[1], (String) w[2], me.loginId());
            }
        }
    }

    /** AUTO 다시 계산(작성 중) — 기간 중 회신·인증서가 바뀐 뒤 */
    @Transactional
    public void recalc(long id, ActionForm in) {
        LoginUser me = me();
        EvalRow cur = start(id, rowVer(in), "DRAFT");
        if (mapper.touchEval(id, mapper.findEval(id).getRemark(), in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        computeAuto(id, cur.getTmplId(), cur.getCustId(), cur.getPeriodFrom(), cur.getPeriodTo(), me.loginId());
    }

    /** 확정(APPROVE) — 모든 항목 점수 필수, 결과는 EvalScorer(트리거가 같은 식으로 다시 확인), 그 뒤 불변 */
    @Transactional
    public void confirm(long id, ActionForm in) {
        LoginUser me = me();
        EvalRow cur = start(id, rowVer(in), "DRAFT");
        List<ScoreSpec> scores = mapper.scoreSpecs(id);
        FieldErrors e = FieldErrors.create();
        List<ItemSpec> items = mapper.itemSpecs(cur.getTmplId());
        Map<Long, BigDecimal> byItem = new HashMap<>();
        scores.forEach(s -> byItem.put(s.getItemId(), s.getScore()));
        for (int k = 0; k < items.size(); k++) {
            if (byItem.get(items.get(k).getItemId()) == null) {
                e.row(String.valueOf(k)).add("score", "REQUIRED");     // 행 키 = 항목 순서(화면 표시 순)
            }
        }
        e.throwIfAny();
        EvalScorer.Result r = score(cur.getTmplId(), id);
        if (mapper.confirm(id, in.rowVer(), r.total(), r.gradeCd(), r.fail() ? "Y" : "N", r.suggestHold() ? "HOLD" : null,
                me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    @Transactional
    public void cancel(long id, ActionForm in) {
        LoginUser me = me();
        start(id, rowVer(in), "DRAFT");
        FieldErrors e = FieldErrors.create();
        String reason = text(in.reason(), "reason", 500, true, e);
        e.throwIfAny();
        if (mapper.cancel(id, in.rowVer(), reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private EvalRow start(long id, Integer rowVer, String... from) {
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        EvalRow cur = mapper.lockEval(id);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!List.of(from).contains(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        if (cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return cur;
    }

    private static Integer rowVer(ActionForm in) {
        return in == null ? null : in.rowVer();
    }
}
