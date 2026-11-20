package io.smarthappy.module.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.admin.LogDtos.FeedbackRow;
import io.smarthappy.module.admin.LogDtos.FeedbackSearch;
import io.smarthappy.module.admin.LogDtos.FeedbackStatusForm;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyMessage;
import lombok.RequiredArgsConstructor;

/**
 * 받은 의견함 (sys-feedback, docs/36 12.2). 화면마다 "의견 보내기" 로 들어온 의견을 회사 관리자가 본다(자기 회사만 — RLS).
 * 상태 NEW → SEEN → DONE(끝), SEEN → NEW 되돌리기 — 트리거 hb_guard_feedback 가 최종. 내용은 바꾸지 않는다.
 * 처리 완료로 바꾸면 보낸 사람 알림함에 FEEDBACK_DONE(링크 = 의견을 보낸 화면, 그 사람이 아직 볼 수 있을 때만).
 */
@Service
@RequiredArgsConstructor
public class FeedbackAdminService {

    static final String PROG = "sys-feedback";
    static final Set<String> STATUSES = Set.of("NEW", "SEEN", "DONE");
    static final int TITLE_CHARS = 40;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("createdAt", "sysFeedback.col.createdAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(1),
            ColumnSpec.code("status", "sysFeedback.col.status", "FEEDBACK_STATUS", 80).withPriority(1),
            ColumnSpec.text("progNm", "sysFeedback.col.progNm", 140).withPriority(1),
            ColumnSpec.text("content", "sysFeedback.col.content", 320).withFlex().withMinWidth(160),
            ColumnSpec.text("userNm", "sysFeedback.col.userNm", 100).withPriority(2).noExport(),
            ColumnSpec.text("clientType", "sysFeedback.col.clientType", 60).withPriority(3),
            ColumnSpec.text("handledBy", "sysFeedback.col.handledBy", 100).withPriority(3));

    private final LogMapper mapper;
    private final LogAdminService logs;
    private final GrantGuard guard;
    private final NotificationService notify;

    @Transactional(readOnly = true)
    public LogAdminService.Page<FeedbackRow> list(LocalDate from, LocalDate to, String status, String progCd,
            Integer page, Integer size, Boolean total, Locale locale) {
        Instant[] t = logs.period(from, to);
        if ((!LogAdminService.blank(status) && !STATUSES.contains(status))
                || (!LogAdminService.blank(progCd) && !progCd.matches("^[a-z0-9][a-z0-9-]{1,49}$"))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PageQuery pq = PageQuery.parse(page, size, null, total, LogDtos.TimeSort.values(), 500, List.of("createdAt,desc"));
        FeedbackSearch q = new FeedbackSearch(t[0], t[1], LogAdminService.blank(status) ? null : status,
                LogAdminService.blank(progCd) ? null : progCd, pq);
        List<FeedbackRow> rows = mapper.findFeedback(q);
        Map<String, String> names = logs.progNames(locale);
        rows.forEach(r -> r.setProgNm(names.getOrDefault(r.getProgCd(), r.getProgCd())));
        return new LogAdminService.Page<>(rows, pq.isWithTotal() ? mapper.countFeedback(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public FeedbackRow detail(long feedbackId, Locale locale) {
        FeedbackRow r = mapper.findFeedbackOne(feedbackId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        r.setProgNm(logs.progNames(locale).getOrDefault(r.getProgCd(), r.getProgCd()));
        return r;
    }

    @Transactional
    public void setStatus(long feedbackId, FeedbackStatusForm form) {
        if (form == null || form.rowVer() == null || form.status() == null || !STATUSES.contains(form.status())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("status", "CODE").list());
        }
        FeedbackRow cur = mapper.findFeedbackOne(feedbackId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (cur.getStatus().equals(form.status())) {
            return;   // 같은 상태 다시 누름 — 멱등
        }
        int n;
        try {
            n = mapper.updateFeedbackStatus(feedbackId, form.status(), form.rowVer(), guard.me().loginId());
        } catch (DataAccessException e) {
            // 동시에 다른 관리자가 끝낸 의견 등 — 전이 표는 트리거가 최종(55000)
            if (PgErrors.constraint(e).filter("ck_sys_feedback_transition"::equals).isEmpty()) {
                throw e;
            }
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("status", "STATE").list());
        }
        if (n == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if ("DONE".equals(form.status())) {
            String c = cur.getContent().strip();
            notify.send(NotifyMessage.of(AdminNotifyEvents.FEEDBACK_DONE, cur.getUserId(), "FB:" + feedbackId,
                    "notify.feedback.done", c.length() > TITLE_CHARS ? c.substring(0, TITLE_CHARS) + "…" : c)
                    .withLink(cur.getProgCd(), Map.of()));
        }
    }
}
