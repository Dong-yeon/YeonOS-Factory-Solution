package io.smarthappy.module.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.admin.LogDtos.TimeSort;
import io.smarthappy.module.admin.OutboxDtos.CloseForm;
import io.smarthappy.module.admin.OutboxDtos.CloseResult;
import io.smarthappy.module.admin.OutboxDtos.LineItem;
import io.smarthappy.module.admin.OutboxDtos.OutboxDetail;
import io.smarthappy.module.admin.OutboxDtos.OutboxDetailRow;
import io.smarthappy.module.admin.OutboxDtos.OutboxRow;
import io.smarthappy.module.admin.OutboxDtos.OutboxSearch;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.OutboxHandler;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;

/**
 * 연계 현황·실패 재전송 (sys-outbox, docs/36 19장 — docs/49 6장 1·W-2). 회사 전체 아웃박스를 주제·상태·기간으로 보고
 * 실패(FAIL)를 재시도(SAVE)하거나 사유를 남기고 수동 종결(FN1, CLOSED_MANUAL)한다. 자기 회사 것만(RLS).
 * <ul>
 *   <li>재시도·종결은 조건부 UPDATE 한 문장 — 0행이면 409 OUTBOX_STATE(이미 처리됐거나 결재 대기). 동시에 둘이 눌러도 한쪽만</li>
 *   <li>정책 OUTBOX_CLOSE_APPROVAL = Y 면 종결은 결재(문서 유형 OUTBOX_CLOSE) — 대기 동안 행은 FAIL 그대로(릴레이는 FAIL 을 집지 않는다)</li>
 *   <li>상세 payload 는 화면용으로만 가린다(OutboxPayloadMask) — 응답은 송신기가 저장하지 않아 없다(Q-O1)</li>
 * </ul>
 */
@Service
public class OutboxAdminService {

    static final String PROG = "sys-outbox";
    static final int MAX_DAYS = 92;
    static final int MAX_REASON = 500;
    static final int MAX_BIZ_KEY = 100;
    static final List<String> STATUSES = List.of("NEW", "SENDING", "SENT", "FAIL", "CANCELED", "CLOSED_MANUAL");
    static final List<String> DEFAULT_SORT = List.of("createdAt,desc");
    /** 인쇄 주제 상세에서 여는 WMS 인쇄 이력 화면(O10) — 그 화면 권한으로 다시 판정 */
    static final String LABEL_TOPIC = "LABEL_PRINT";
    static final String LABEL_PROG = "wms-print-job";

    /** 칸 — 주제·상태는 서버 문구(outboxTopic.*·outboxStatus.*). 주제 값은 송신기가 있는 주제 */
    static List<ColumnSpec> columns(List<String> topics) {
        return List.of(
                ColumnSpec.of("createdAt", "sysOutbox.col.createdAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(1),
                ColumnSpec.labels("topic", "sysOutbox.col.topic", "outboxTopic.", 130, topics.toArray(String[]::new)).withPriority(1),
                ColumnSpec.text("bizKey", "sysOutbox.col.bizKey", 140).withPriority(2),
                ColumnSpec.labels("status", "sysOutbox.col.status", "outboxStatus.", 100, STATUSES.toArray(String[]::new)).withPriority(1),
                ColumnSpec.text("tries", "sysOutbox.col.tries", 70).withPriority(3),
                ColumnSpec.of("nextRetryAt", "sysOutbox.col.nextRetryAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(3),
                ColumnSpec.of("sentAt", "sysOutbox.col.sentAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(3),
                ColumnSpec.text("lastError", "sysOutbox.col.lastError", 240).withFlex().withPriority(2));
    }

    private final OutboxMapper mapper;
    private final GrantGuard guard;
    private final DateRanges ranges;
    private final BizClock clock;
    private final PolicyService policies;
    private final ApprovalService approvals;
    private final Set<String> topics;
    private final List<ColumnSpec> columns;

    public OutboxAdminService(OutboxMapper mapper, GrantGuard guard, DateRanges ranges, BizClock clock, PolicyService policies,
            ApprovalService approvals, List<OutboxHandler> handlers) {
        this.mapper = mapper;
        this.guard = guard;
        this.ranges = ranges;
        this.clock = clock;
        this.policies = policies;
        this.approvals = approvals;
        this.topics = new TreeSet<>(handlers.stream().map(OutboxHandler::topic).toList());
        this.columns = columns(List.copyOf(topics));
    }

    public record Page<T>(List<T> list, Long total) {
    }

    /** 주제 선택지 — 송신기가 있는 주제(코드 그대로, 이름은 화면 문구 outboxTopic.*) */
    public List<String> topics() {
        return List.copyOf(topics);
    }

    public List<ColumnSpec> columns() {
        return columns;
    }

    @Transactional(readOnly = true)
    public Page<OutboxRow> list(LocalDate from, LocalDate to, String topic, String status, String bizKey, Integer page, Integer size,
            Boolean total) {
        ranges.check(from, to, MAX_DAYS);
        if ((!blank(topic) && !topics.contains(topic)) || (!blank(status) && !STATUSES.contains(status))
                || (!blank(bizKey) && bizKey.strip().length() > MAX_BIZ_KEY)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ZoneId zone = clock.zone();
        Instant f = from.atStartOfDay(zone).toInstant();
        Instant t = to.plusDays(1).atStartOfDay(zone).toInstant();
        PageQuery pq = PageQuery.parse(page, size, null, total, TimeSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                DEFAULT_SORT);
        OutboxSearch q = new OutboxSearch(f, t, blank(topic) ? null : topic, blank(status) ? null : status,
                blank(bizKey) ? null : LogAdminService.likeEscape(bizKey.strip()) + "%", pq);
        return new Page<>(mapper.find(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    /** 한 행. 다른 회사·없는 행 404 */
    @Transactional(readOnly = true)
    public OutboxDetail detail(long outboxId) {
        OutboxDetailRow r = mapper.findOne(outboxId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new OutboxDetail(r.getOutboxId(), r.getMessageId(), r.getCreatedAt(), r.getCreatedBy(), r.getTopic(), r.getBizKey(),
                r.getStatus(), r.getAttempts(), r.getMaxAttempts(), r.getNextRetryAt(), r.getSentAt(), r.getLastError(),
                OutboxPayloadMask.mask(r.getPayload()), r.getRetryCnt(), r.getRetriedAt(), r.getRetriedBy(), r.getClosedAt(),
                r.getClosedBy(), r.getCloseReason(), r.getCloseAprId(), r.getCloseAprStatus(),
                policies.bool(Policies.OUTBOX_CLOSE_APPROVAL), LABEL_TOPIC.equals(r.getTopic()) ? LABEL_PROG : null);
    }

    /** 재시도(O5) — FAIL 이고 종결 결재 대기가 아닐 때만. 없음 404, 그 밖 409 */
    @Transactional
    public void retry(long outboxId) {
        LoginUser me = guard.me();
        if (mapper.retry(outboxId, me.loginId()) == 0) {
            throw stateError(outboxId);
        }
    }

    /** 수동 종결(O6) — 사유 필수. 정책 N = 바로 CLOSED_MANUAL, Y = 결재 상신(결재선 필수) */
    @Transactional
    public CloseResult close(long outboxId, CloseForm in) {
        LoginUser me = guard.me();
        String reason = in == null ? null : VisibleText.strip(in.reason());
        if (reason == null || reason.length() > MAX_REASON) {   // 보이지 않는 글자뿐 = 사유 없음(QA R4-a)
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        OutboxDetailRow cur = mapper.lockOne(outboxId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"FAIL".equals(cur.getStatus()) || cur.getCloseAprId() != null) {
            throw new BusinessException(HbErrorCode.OUTBOX_STATE);
        }
        if (!policies.bool(Policies.OUTBOX_CLOSE_APPROVAL)) {
            if (mapper.close(outboxId, reason, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.OUTBOX_STATE);
            }
            return new CloseResult(true, null);
        }
        List<LineSpec> lines = new ArrayList<>();
        for (LineItem l : in.lines() == null ? List.<LineItem>of() : in.lines()) {
            LineType type;
            try {
                type = l == null || l.userId() == null ? null : LineType.valueOf(l.lineType() == null ? "APPROVE" : l.lineType());
            } catch (IllegalArgumentException ex) {
                type = null;
            }
            if (type == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            lines.add(new LineSpec(l.userId(), type));
        }
        String title = OutboxCloseDocType.title(cur.getTopic(), cur.getBizKey(), outboxId, reason);
        long aprId = approvals.submit(OutboxCloseDocType.DOC_TYPE, String.valueOf(outboxId), title, lines);
        if (mapper.markClosePending(outboxId, aprId, reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.OUTBOX_STATE);
        }
        return new CloseResult(false, aprId);
    }

    private BusinessException stateError(long outboxId) {
        return new BusinessException(mapper.findOne(outboxId) == null ? HbErrorCode.NOT_FOUND : HbErrorCode.OUTBOX_STATE);
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
