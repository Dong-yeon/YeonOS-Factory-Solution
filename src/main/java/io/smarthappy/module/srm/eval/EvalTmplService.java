package io.smarthappy.module.srm.eval;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.decimal;
import static io.smarthappy.module.srm.SrmChecks.fieldError;
import static io.smarthappy.module.srm.SrmChecks.me;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.eval.EvalDtos.ActionForm;
import io.smarthappy.module.srm.eval.EvalDtos.BandForm;
import io.smarthappy.module.srm.eval.EvalDtos.BandRow;
import io.smarthappy.module.srm.eval.EvalDtos.BandValue;
import io.smarthappy.module.srm.eval.EvalDtos.GradeForm;
import io.smarthappy.module.srm.eval.EvalDtos.GradeValue;
import io.smarthappy.module.srm.eval.EvalDtos.ItemForm;
import io.smarthappy.module.srm.eval.EvalDtos.ItemRow;
import io.smarthappy.module.srm.eval.EvalDtos.ItemValue;
import io.smarthappy.module.srm.eval.EvalDtos.TmplDetail;
import io.smarthappy.module.srm.eval.EvalDtos.TmplCrit;
import io.smarthappy.module.srm.eval.EvalDtos.TmplForm;
import io.smarthappy.module.srm.eval.EvalDtos.TmplPage;
import io.smarthappy.module.srm.eval.EvalDtos.TmplRow;
import io.smarthappy.module.srm.eval.EvalDtos.TmplSort;
import io.smarthappy.platform.common.db.PgErrors;
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
 * 평가 템플릿 (srm-eval-tmpl) — SRM S6, docs/31 4.6, D10. BOM 리비전 규칙: 작성 중만 편집(항목·등급은 한 벌로 통째 교체),
 * 확정 = 항목 1+ · 가중치 합 100 · 최소 점수 0 인 등급(APPROVE). 끝 없는 이전 확정본은 새 시작일 전날로 마감, 그래도 겹치면 거부.
 * 새 리비전 = 마지막 리비전 복사(작성 중은 템플릿마다 하나).
 */
@Service
@RequiredArgsConstructor
public class EvalTmplService {

    public static final String PROG = "srm-eval-tmpl";
    static final int MAX_ITEMS = 100;
    static final int MAX_GRADES = 10;
    static final int MAX_BANDS = 10;
    static final Set<String> STATUS = Set.of("DRAFT", "RELEASED", "OBSOLETE");
    static final Set<String> METRICS = Set.of("OTD_RATE", "RECV_PPM", "CERT_VALID", "ACK_RATE");
    private static final Pattern TMPL_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,29}$");
    private static final Pattern GRADE_CD = Pattern.compile("^[A-Z][A-Z0-9+-]{0,9}$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EvalMapper mapper;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;

    @Transactional(readOnly = true)
    public List<TmplRow> list(String status, String keyword) {
        return mapper.findTmpls(criteria(status, keyword));
    }

    /**
     * grid(UI 단계 3 ①, docs/31 5.2) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link TmplSort}, 최대 3개, 모르는 열 400).
     * 템플릿은 기간 조건이 없다(목록과 같음)
     */
    @Transactional(readOnly = true)
    public TmplPage grid(String status, String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        TmplCrit c = criteria(status, keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, TmplSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("tmplCd,asc"));
        return new TmplPage(mapper.findTmplPage(c, pq), pq.isWithTotal() ? mapper.countTmpls(c) : null);
    }

    /** grid 열(화면 목록 칸을 나눠서 — 리비전·항목 수·가중치 합) — 정렬 가능 = {@link TmplSort} 에 있는 열 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("tmplCd", "srm.evalTmpl.col.tmplCd", 110).withSort().withPriority(1),
            ColumnSpec.of("rev", "srm.evalTmpl.col.rev", ColumnType.INT, 60),
            ColumnSpec.text("tmplNm", "srm.evalTmpl.col.tmplNm", 180).withSort(),
            ColumnSpec.code("status", "srm.evalTmpl.col.status", "BOM_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.of("validFrom", "srm.evalTmpl.col.validFrom", ColumnType.DATE, 100).withSort(),
            ColumnSpec.of("validTo", "srm.evalTmpl.col.validTo", ColumnType.DATE, 100).withPriority(3),
            ColumnSpec.of("itemCnt", "srm.evalTmpl.col.itemCnt", ColumnType.INT, 70).withPriority(3),
            ColumnSpec.dec("weightSum", "srm.evalTmpl.col.weightSum", 2, 80).withMinScale(0).withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    private TmplCrit criteria(String status, String keyword) {
        String st = code(status);
        if (st != null && !STATUS.contains(st) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new TmplCrit(st, blank(keyword) ? null : "%" + SrmChecks.like(keyword.strip()) + "%");
    }

    @Transactional(readOnly = true)
    public TmplDetail get(long id) {
        TmplRow t = mapper.findTmpl(id);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<ItemRow> items = mapper.findItems(id);
        List<BandRow> bands = mapper.findBands(id);
        items.forEach(i -> i.setBands(bands.stream().filter(b -> b.getItemId() == i.getItemId()).toList()));
        return new TmplDetail(t, items, mapper.findGrades(id));
    }

    // ------------------------------------------------------------------ 저장

    @Transactional
    public long create(TmplForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_EVAL_TMPL_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = code(in.tmplCd());
        if (cd == null) {
            e.add("tmplCd", "REQUIRED");
        } else if (!TMPL_CD.matcher(cd).matches()) {
            e.add("tmplCd", "FORMAT");
        } else if (mapper.tmplCdExists(cd)) {
            e.add("tmplCd", "DUPLICATE");      // 다음 리비전은 [새 리비전]으로
        }
        Head h = head(in, e);
        List<ItemValue> items = items(in.items(), e);
        List<GradeValue> grades = grades(in.grades(), e);
        e.throwIfAny();
        try {
            long id = mapper.insertTmpl(cd, 0, h.nm, h.from, h.to, h.remark, me.loginId());
            writeRows(id, items, grades, me.loginId());
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void update(long id, TmplForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        TmplRow cur = start(id, in.rowVer(), "DRAFT");
        FieldErrors e = FieldErrors.create();
        String cd = code(in.tmplCd());
        if (cd != null && !cd.equals(cur.getTmplCd())) {
            e.add("tmplCd", "FIXED");
        }
        Head h = head(in, e);
        List<ItemValue> items = items(in.items(), e);
        List<GradeValue> grades = grades(in.grades(), e);
        e.throwIfAny();
        try {
            if (mapper.updateTmpl(id, h.nm, h.from, h.to, h.remark, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            mapper.deleteBands(id);       // 구간 → 항목 순(구간이 항목을 참조)
            mapper.deleteItems(id);
            mapper.deleteGrades(id);
            writeRows(id, items, grades, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    private void writeRows(long id, List<ItemValue> items, List<GradeValue> grades, String by) {
        items.forEach(i -> mapper.insertItem(id, i, by));
        items.forEach(i -> i.bands().forEach(b -> mapper.insertBand(id, i.itemNo(), b, by)));
        grades.forEach(g -> mapper.insertGrade(id, g, by));
    }

    private record Head(String nm, LocalDate from, LocalDate to, String remark) {
    }

    private Head head(TmplForm in, FieldErrors e) {
        String nm = text(in.tmplNm(), "tmplNm", 200, true, e);
        LocalDate from = date(in.validFrom(), "validFrom", true, e);
        LocalDate to = date(in.validTo(), "validTo", false, e);
        if (from != null && from.getYear() < 2000) {
            e.add("validFrom", "FORMAT");
        }
        if (from != null && to != null && to.isBefore(from)) {
            e.add("validTo", "INVALID_RANGE");
        }
        String remark = text(in.remark(), "remark", 1000, false, e);
        return new Head(nm, from, to, remark);
    }

    /** 항목 — 행 키 = 배열 순번, 항목 번호 = 순번 + 1 */
    private List<ItemValue> items(List<ItemForm> in, FieldErrors e) {
        List<ItemValue> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        if (in.size() > MAX_ITEMS) {
            e.add("items", "TOO_MANY_LINES", MAX_ITEMS);
            return out;
        }
        for (int i = 0; i < in.size(); i++) {
            ItemForm f = in.get(i);
            FieldErrors le = FieldErrors.create();
            if (f == null) {
                le.add("itemNm", "REQUIRED");
            } else {
                String section = text(f.sectionNm(), "sectionNm", 100, false, le);
                String nm = text(f.itemNm(), "itemNm", 200, true, le);
                BigDecimal max = decimal(f.maxScore(), "maxScore", 2, 5, true, le);
                BigDecimal w = decimal(f.weight(), "weight", 2, 3, true, le);
                if (w != null && w.compareTo(HUNDRED) > 0) {
                    le.add("weight", "RANGE", 0, 100);
                }
                BigDecimal fail = decimal(f.failBelow(), "failBelow", 2, 5, false, le);
                if (fail != null && max != null && fail.compareTo(max) > 0) {
                    le.add("failBelow", "SRM_EVAL_FAIL_ABOVE_MAX");
                }
                String type = blank(f.inputType()) ? "MANUAL" : code(f.inputType());
                String metric = code(f.metricCd());
                if (!"MANUAL".equals(type) && !"AUTO".equals(type)) {
                    le.add("inputType", "CODE");
                } else if ("AUTO".equals(type) && metric == null) {
                    le.add("metricCd", "REQUIRED");
                } else if ("AUTO".equals(type) && !METRICS.contains(metric)) {
                    le.add("metricCd", "CODE");
                }
                String guide = text(f.guide(), "guide", 1000, false, le);
                List<BandValue> bands = bands(f.bands(), "AUTO".equals(type) && "RECV_PPM".equals(metric), le);
                if (le.isEmpty()) {
                    out.add(new ItemValue(i + 1, section, nm, max, w, fail, type, "AUTO".equals(type) ? metric : null, guide, bands));
                }
            }
            String key = String.valueOf(i);
            le.list().forEach(x -> e.row(key).add(x.field(), x.code(), x.args()));
        }
        return out;
    }

    /**
     * 점수 구간(R4b, docs/31 4.12.3) — RECV_PPM 항목만. 상한 오름차순으로 정렬해 두고, 끝 없는 구간(상한 빈칸)은 하나·마지막.
     * 작성 중엔 비어도 되고(확정 때 DB 가 끝 없는 구간을 요구 — ck_srm_eval_tmpl_bands), 다른 항목에 구간이 오면 오류
     */
    private List<BandValue> bands(List<BandForm> in, boolean ppm, FieldErrors le) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        if (!ppm) {
            le.add("bands", "SRM_EVAL_BAND_METRIC");
            return List.of();
        }
        if (in.size() > MAX_BANDS) {
            le.add("bands", "TOO_MANY_LINES", MAX_BANDS);
            return List.of();
        }
        List<BandValue> out = new ArrayList<>();
        Set<BigDecimal> uppers = new HashSet<>();
        int open = 0;
        for (BandForm b : in) {
            if (b == null) {
                le.add("bands", "REQUIRED");
                continue;
            }
            BigDecimal upper = SrmChecks.nonNegative(b.upperPpm(), "bands", 2, 10, false, le);
            BigDecimal pct = SrmChecks.nonNegative(b.scorePct(), "bands", 2, 3, true, le);
            if (pct != null && pct.compareTo(HUNDRED) > 0) {
                le.add("bands", "RANGE", 0, 100);
            }
            if (upper == null && blank(b.upperPpm())) {
                open++;
            } else if (upper != null && !uppers.add(upper.stripTrailingZeros())) {
                le.add("bands", "DUPLICATE");
            }
            if (pct != null) {
                out.add(new BandValue(upper, pct));
            }
        }
        if (open > 1) {
            le.add("bands", "SRM_EVAL_BAND_OPEN");
        }
        out.sort(Comparator.comparing(BandValue::upperPpm, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /** 등급 — 행 키 = "g" + 배열 순번(항목 행 키와 겹치지 않게) */
    private List<GradeValue> grades(List<GradeForm> in, FieldErrors e) {
        List<GradeValue> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        if (in.size() > MAX_GRADES) {
            e.add("grades", "TOO_MANY_LINES", MAX_GRADES);
            return out;
        }
        Set<String> codes = new HashSet<>();
        Set<BigDecimal> mins = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            GradeForm f = in.get(i);
            FieldErrors le = FieldErrors.create();
            if (f == null) {
                le.add("gradeCd", "REQUIRED");
            } else {
                String cd = code(f.gradeCd());
                if (cd == null) {
                    le.add("gradeCd", "REQUIRED");
                } else if (!GRADE_CD.matcher(cd).matches()) {
                    le.add("gradeCd", "FORMAT");
                } else if (!codes.add(cd)) {
                    le.add("gradeCd", "DUPLICATE");
                }
                BigDecimal min = SrmChecks.nonNegative(f.minScore(), "minScore", 2, 3, true, le);
                if (min != null && min.compareTo(HUNDRED) > 0) {
                    le.add("minScore", "RANGE", 0, 100);
                } else if (min != null && !mins.add(min.stripTrailingZeros())) {
                    le.add("minScore", "DUPLICATE");
                }
                String hold = blank(f.suggestHoldYn()) ? "N" : f.suggestHoldYn();
                if (!"Y".equals(hold) && !"N".equals(hold)) {
                    le.add("suggestHoldYn", "CODE");
                }
                if (le.isEmpty()) {
                    out.add(new GradeValue(cd, min, hold));
                }
            }
            String key = "g" + i;
            le.list().forEach(x -> e.row(key).add(x.field(), x.code(), x.args()));
        }
        return out;
    }

    // ------------------------------------------------------------------ 상태

    /** 확정 — 끝 없는 이전 확정본(더 이른 시작)을 새 시작일 전날로 마감한 뒤, 그래도 겹치면 SRM_EVAL_OVERLAP */
    @Transactional
    public void release(long id, ActionForm in) {
        LoginUser me = me();
        TmplRow cur = start(id, rowVer(in), "DRAFT");
        TmplRow full = mapper.findTmpl(id);
        FieldErrors e = FieldErrors.create();
        if (full.getItemCnt() == 0) {
            e.add("items", "LINES_REQUIRED");
        } else if (new BigDecimal(full.getWeightSum()).compareTo(HUNDRED) != 0) {
            e.add("items", "SRM_EVAL_WEIGHT", full.getWeightSum());
        }
        if (mapper.findGrades(id).stream().noneMatch(g -> new BigDecimal(g.getMinScore()).signum() == 0)) {
            e.add("grades", "SRM_EVAL_GRADE0");
        }
        // RECV_PPM 항목은 끝 없는 구간이 있어야 어떤 PPM 이든 점수를 찾는다(DB 도 ck_srm_eval_tmpl_bands 로 다시 본다)
        List<BandRow> bands = mapper.findBands(id);
        mapper.findItems(id).stream()
                .filter(i -> "AUTO".equals(i.getInputType()) && "RECV_PPM".equals(i.getMetricCd()))
                .filter(i -> bands.stream().noneMatch(b -> b.getItemId() == i.getItemId() && b.getUpperPpm() == null))
                .findFirst().ifPresent(i -> e.add("items", "SRM_EVAL_BAND_NEED", i.getItemNo()));
        e.throwIfAny();
        mapper.closeOpenReleased(cur.getTmplCd(), id, cur.getValidFrom(), me.loginId());
        try {
            if (mapper.release(id, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void obsolete(long id, ActionForm in) {
        LoginUser me = me();
        TmplRow cur = start(id, rowVer(in), "DRAFT", "RELEASED");
        if (mapper.obsolete(id, cur.getStatus(), in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 새 리비전 — 마지막 리비전(상태 무관)의 항목·등급을 복사, 시작일 = 오늘(작성 중에 고친다) */
    @Transactional
    public long newRevision(long id) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_EVAL_TMPL_REV");
        TmplRow src = mapper.lockTmpl(id);
        if (src == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        int rev = mapper.maxRev(src.getTmplCd()) + 1;
        if (rev > 999) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        TmplRow full = mapper.findTmpl(id);
        try {
            long to = mapper.insertTmpl(src.getTmplCd(), rev, full.getTmplNm(), clock.today(), null, full.getRemark(), me.loginId());
            mapper.copyItems(id, to, me.loginId());
            mapper.copyBands(id, to, me.loginId());
            mapper.copyGrades(id, to, me.loginId());
            return to;
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    private TmplRow start(long id, Integer rowVer, String... from) {
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        TmplRow cur = mapper.lockTmpl(id);
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

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "ex_srm_eval_tmpl_period" -> fieldError("validFrom", "SRM_EVAL_OVERLAP");
            case "uq_srm_eval_tmpl_draft" -> new BusinessException(HbErrorCode.SRM_EVAL_DRAFT_EXISTS);
            case "uq_srm_eval_tmpl_rev" -> fieldError("tmplCd", "DUPLICATE");
            default -> e;
        };
    }
}
