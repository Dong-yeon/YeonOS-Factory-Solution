package io.smarthappy.module.pms;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;
import static io.smarthappy.module.pms.PmsInputs.date;
import static io.smarthappy.module.pms.PmsInputs.fieldError;
import static io.smarthappy.module.pms.PmsInputs.me;
import static io.smarthappy.module.pms.PmsInputs.visible;
import static io.smarthappy.module.pms.PmsInputs.upper;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.WorkDtos.DayAction;
import io.smarthappy.module.pms.WorkDtos.DayForm;
import io.smarthappy.module.pms.WorkDtos.DayView;
import io.smarthappy.module.pms.WorkDtos.LogIn;
import io.smarthappy.module.pms.WorkDtos.LogRow;
import io.smarthappy.module.pms.WorkDtos.SummaryRow;
import io.smarthappy.module.pms.WorkDtos.Suggestion;
import io.smarthappy.module.pms.WorkDtos.WeekView;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 내 공수 (pms-worklog) — docs/32 PM4, D9.
 * - 늘 본인 행만(조회·저장·확정 모두 user_id = 나). PM 은 프로젝트 요약만 본다(남의 공수를 고칠 길이 없다)
 * - 하루 = 작성 중 행 한 벌 저장 → "확정"(분류 필수, 0시간 행은 지움) → 확정 뒤는 "다시 열기"(사유)로만
 * - 하루 합계 ≤ 정책 PMS_DAY_MAX_HOURS(서비스, 사용자·날짜 advisory 잠금) + 24h(트리거), 미래 날짜 거부
 * - 미리 채움 = 내가 담당인 진행 중 작업(또는 그 날이 계획 안인 시작 전 작업) → 최근 14일 같은 작업 시간·분류를 복사(PREFILL)
 */
@Service
@RequiredArgsConstructor
public class WorkLogService {

    static final String PROG = "pms-worklog";
    static final String CAT_GRP = "PMS_WORK_CAT";
    private static final int MAX_ROWS = 30;
    private static final BigDecimal H24 = BigDecimal.valueOf(24);

    private final WorkMapper wm;
    private final PmsMapper mapper;
    private final PolicyService policy;
    private final BizClock clock;
    private final PmsDays days;

    // ------------------------------------------------------------------ 조회

    /** from 이 속한 주(월~일). 비우면 이번 주 */
    @Transactional(readOnly = true)
    public WeekView week(String fromRaw) {
        LoginUser me = me();
        LocalDate today = clock.today();
        FieldErrors e = FieldErrors.create();
        LocalDate d = date(fromRaw, "from", false, e);
        e.throwIfAny();
        LocalDate from = (d == null ? today : d).with(DayOfWeek.MONDAY);
        LocalDate to = from.plusDays(6);
        Map<LocalDate, List<LogRow>> byDay = new HashMap<>();
        for (LogRow r : wm.findLogs(me.userId(), from, to)) {
            byDay.computeIfAbsent(r.getWorkDay(), k -> new ArrayList<>()).add(r);
        }
        List<DayView> days = new ArrayList<>();
        for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) {
            List<LogRow> rows = byDay.getOrDefault(x, List.of());
            BigDecimal sum = rows.stream().map(LogRow::getHours).reduce(BigDecimal.ZERO, BigDecimal::add);
            String st = rows.isEmpty() ? "NONE"
                    : rows.stream().anyMatch(r -> "DRAFT".equals(r.getStatus())) ? "DRAFT" : "CONFIRMED";
            days.add(new DayView(x, sum.stripTrailingZeros().toPlainString(), st, x.isAfter(today), rows));
        }
        LocalDate sugDay = today.isBefore(from) || today.isAfter(to) ? from : today;
        return new WeekView(from, to, today, policy.intValue(Policies.PMS_DAY_MAX_HOURS), days,
                wm.findSuggestions(me.userId(), sugDay), wm.findOpenProjects());
    }

    /** 프로젝트 공수 요약 — PM 조회용(사용자 × 분류, 확정·작성 중) */
    @Transactional(readOnly = true)
    public List<SummaryRow> summary(long projId) {
        if (mapper.findProject(projId, days.lateRef()) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return wm.summary(projId);
    }

    // ------------------------------------------------------------------ 하루 한 벌 저장

    /** 작성 중 행 한 벌: wlId 있는 행 = 수정, 없는 행 = 추가, 보내지 않은 작성 중 행 = 삭제. 확정된 날은 409(다시 열기 먼저) */
    @Transactional
    public void saveDay(DayForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LocalDate day = day(in.day());
        lock(me, day);
        List<LogRow> have = wm.findLogs(me.userId(), day, day);
        if (have.stream().anyMatch(r -> "CONFIRMED".equals(r.getStatus()))) {
            throw new BusinessException(HbErrorCode.PMS_DAY_CONFIRMED);
        }
        List<LogIn> rows = in.rows() == null ? List.of() : in.rows();
        if (rows.size() > MAX_ROWS) {
            throw fieldError("rows", "PMS_TOO_MANY_ROWS", MAX_ROWS);
        }
        if (rows.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Map<Long, LogRow> byId = new HashMap<>();
        have.forEach(r -> byId.put(r.getWlId(), r));
        FieldErrors e = FieldErrors.create();
        List<Object[]> vals = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            LogIn r = rows.get(i);
            String rk = r.wlId() == null ? "n" + i : String.valueOf(r.wlId());
            FieldErrors re = FieldErrors.create();
            LogRow cur = r.wlId() == null ? null : byId.get(r.wlId());
            if (r.wlId() != null && cur == null) {
                // 남의 행·다른 날 행·이미 지운 행 — 본인 그 날 행만 고칠 수 있다
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            if (cur != null && (r.rowVer() == null || r.rowVer() != cur.getRowVer())) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            Long projId = cur != null ? Long.valueOf(cur.getProjId()) : r.projId();
            if (cur != null && r.projId() != null && r.projId() != cur.getProjId()) {
                re.add("projId", "FIXED");
            }
            if (projId == null) {
                re.add("projId", "REQUIRED");
            } else if (cur == null) {
                String st = mapper.lockProjectShare(projId);
                if (st == null) {
                    re.add("projId", "NOT_FOUND");
                } else if (!TaskService.OPEN.contains(st)) {
                    re.add("projId", "PMS_PROJECT_CLOSED");
                }
            }
            Long taskId = r.taskId();
            if (taskId != null && projId != null) {
                TaskRow t = mapper.findTask(taskId);
                if (t == null || t.getProjId() != projId) {
                    re.add("taskId", "NOT_FOUND");
                }
            }
            String cat = upper(r.categoryCd());
            if (cat != null && !Boolean.TRUE.equals(mapper.codeUsable(CAT_GRP, cat))) {
                re.add("categoryCd", "NO_SUCH_CODE");
            }
            BigDecimal h = hours(r.hours(), re);
            String note = visible(r.note(), "note", 500, false, re);
            if (h != null) {
                sum = sum.add(h);
            }
            if (projId != null && !keys.add(projId + ":" + taskId + ":" + cat)) {
                re.add("taskId", "DUPLICATE");
            }
            re.list().forEach(x -> e.row(rk).add(x.field(), x.code(), x.args()));
            vals.add(new Object[] {cur, projId, taskId, cat, h, note});
        }
        int max = policy.intValue(Policies.PMS_DAY_MAX_HOURS);
        if (sum.compareTo(BigDecimal.valueOf(max)) > 0) {
            e.add("hours", "PMS_DAY_OVER", max, sum.stripTrailingZeros().toPlainString());
        }
        e.throwIfAny();
        Set<Long> keep = new HashSet<>();
        vals.forEach(v -> {
            if (v[0] != null) {
                keep.add(((LogRow) v[0]).getWlId());
            }
        });
        for (LogRow r : have) {
            if (!keep.contains(r.getWlId())) {
                wm.deleteLog(r.getWlId(), me.userId());
            }
        }
        for (Object[] v : vals) {
            LogRow cur = (LogRow) v[0];
            BigDecimal h = (BigDecimal) v[4];
            try {
                if (cur == null) {
                    wm.insertLog(me.userId(), day, (Long) v[1], (Long) v[2], (String) v[3], h, "KEY", (String) v[5], me.loginId());
                } else {
                    // 미리 채운 행이라도 시간을 바꾸면 사람이 넣은 값(KEY)
                    String method = "PREFILL".equals(cur.getEntryMethod()) && h.compareTo(cur.getHours()) == 0 ? "PREFILL" : "KEY";
                    if (wm.updateLog(cur.getWlId(), me.userId(), (Long) v[2], (String) v[3], h, method, (String) v[5], cur.getRowVer(),
                            me.loginId()) == 0) {
                        throw new BusinessException(HbErrorCode.CONFLICT);
                    }
                }
            } catch (DuplicateKeyException ex) {
                throw fieldError("taskId", "DUPLICATE");
            } catch (DataIntegrityViolationException ex) {
                throw translate(ex);
            }
        }
    }

    // ------------------------------------------------------------------ 미리 채움·확정·다시 열기

    /** 반환 = 새로 채운 행 수(이미 있는 작업은 건너뛴다) */
    @Transactional
    public int prefill(DayAction in) {
        LoginUser me = me();
        LocalDate day = day(in == null ? null : in.day());
        lock(me, day);
        List<LogRow> have = wm.findLogs(me.userId(), day, day);
        if (have.stream().anyMatch(r -> "CONFIRMED".equals(r.getStatus()))) {
            throw new BusinessException(HbErrorCode.PMS_DAY_CONFIRMED);
        }
        Set<String> exists = new HashSet<>();
        have.forEach(r -> exists.add(r.getProjId() + ":" + r.getTaskId()));
        BigDecimal sum = have.stream().map(LogRow::getHours).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal max = BigDecimal.valueOf(policy.intValue(Policies.PMS_DAY_MAX_HOURS));
        int n = 0;
        for (Suggestion s : wm.findSuggestions(me.userId(), day)) {
            if (!exists.add(s.getProjId() + ":" + s.getTaskId()) || have.size() + n >= MAX_ROWS) {
                continue;
            }
            LogRow prev = wm.findPrevious(me.userId(), day, s.getProjId(), s.getTaskId());
            BigDecimal h = prev == null ? BigDecimal.ZERO : prev.getHours();
            if (sum.add(h).compareTo(max) > 0) {
                h = BigDecimal.ZERO;          // 넘치면 시간은 비워 두고 행만 — 사람이 나눈다
            }
            sum = sum.add(h);
            wm.insertLog(me.userId(), day, s.getProjId(), s.getTaskId(), prev == null ? null : prev.getCategoryCd(), h, "PREFILL", null,
                    me.loginId());
            n++;
        }
        return n;
    }

    /** 그 날 작성 중 행 확정 — 0시간 행은 지우고, 남은 행은 분류 필수, 합계 > 0 */
    @Transactional
    public int confirm(DayAction in) {
        LoginUser me = me();
        LocalDate day = day(in == null ? null : in.day());
        lock(me, day);
        List<LogRow> drafts = wm.findLogs(me.userId(), day, day).stream().filter(r -> "DRAFT".equals(r.getStatus())).toList();
        FieldErrors e = FieldErrors.create();
        int left = 0;
        for (LogRow r : drafts) {
            if (r.getHours().signum() == 0) {
                continue;
            }
            left++;
            if (r.getCategoryCd() == null) {
                e.row(String.valueOf(r.getWlId())).add("categoryCd", "REQUIRED");
            }
        }
        if (left == 0) {
            throw fieldError("day", "PMS_NOTHING_TO_CONFIRM");
        }
        e.throwIfAny();
        for (LogRow r : drafts) {
            if (r.getHours().signum() == 0) {
                wm.deleteLog(r.getWlId(), me.userId());
            }
        }
        return wm.confirmDay(me.userId(), day, me.loginId());
    }

    @Transactional
    public int reopen(DayAction in) {
        LoginUser me = me();
        LocalDate day = day(in == null ? null : in.day());
        FieldErrors e = FieldErrors.create();
        String reason = visible(in == null ? null : in.reason(), "reason", 500, true, e);
        e.throwIfAny();
        lock(me, day);
        int n = wm.reopenDay(me.userId(), day, reason, me.loginId());
        if (n == 0) {
            throw new BusinessException(HbErrorCode.PMS_DAY_NOT_CONFIRMED);
        }
        return n;
    }

    // ------------------------------------------------------------------ 내부

    private LocalDate day(String raw) {
        FieldErrors e = FieldErrors.create();
        LocalDate d = date(raw, "day", true, e);
        e.throwIfAny();
        if (d.isAfter(clock.today())) {
            throw fieldError("day", "FUTURE");
        }
        return d;
    }

    private void lock(LoginUser me, LocalDate day) {
        wm.lockDay("pms_work_log:" + me.compCd() + ":" + me.userId() + ":" + day);
    }

    /** "7.5" → 7.5 (0~24, 소수 1자리) */
    static BigDecimal hours(String raw, FieldErrors e) {
        if (isBlank(raw)) {
            e.add("hours", "REQUIRED");
            return null;
        }
        BigDecimal h;
        try {
            h = new BigDecimal(raw.strip());
        } catch (NumberFormatException ex) {
            e.add("hours", "FORMAT");
            return null;
        }
        if (h.signum() < 0 || h.compareTo(H24) > 0) {
            e.add("hours", "RANGE", 0, 24);
            return null;
        }
        if (h.stripTrailingZeros().scale() > 1) {
            e.add("hours", "SCALE", 1);
            return null;
        }
        return h.setScale(1);
    }

    private static RuntimeException translate(DataIntegrityViolationException ex) {
        String c = PgErrors.constraint(ex).orElse("");
        return switch (c) {
            case "ck_pms_work_log_day_sum" -> fieldError("hours", "PMS_DAY_OVER", 24, "24+");
            case "fk_pms_work_log_category" -> fieldError("categoryCd", "NO_SUCH_CODE");
            case "fk_pms_work_log_task_proj" -> fieldError("taskId", "NOT_FOUND");
            case "ck_pms_work_log_project_closed" -> new BusinessException(HbErrorCode.PMS_PROJECT_CLOSED);
            default -> ex;
        };
    }
}
