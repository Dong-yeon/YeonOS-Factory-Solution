package io.smarthappy.platform.approval;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.BusinessException.Detail;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.messaging.NotifyLogHandler;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.PlatformNotifyEvents;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;

/**
 * 결재 엔진 (01 5.4). 결재선은 APPROVE·AGREE 를 순서대로 한 명씩(CURRENT), REF 는 참조만.
 *
 * - 상신: 업무 모듈이 자기 트랜잭션 안에서 {@link #submit} 호출 (업무 저장과 같이 커밋)
 * - 처리(승인·합의·반려·회수): 요청 행을 `for update` 로 잠그고 판정 → 연타·동시 처리는 한 건만 통과
 * - 끝나면 문서 유형 핸들러가 같은 트랜잭션에서 업무 상태를 바꾸고, 알림은 아웃박스(NOTIFY)로
 * - 권한 = 결재선: 현재 차례인 사람만 승인·반려, 요청자만 회수(아무도 처리하기 전), 조회는 요청자·결재선만
 */
@Service
public class ApprovalService {

    static final Pattern BIZ_KEY = Pattern.compile("^[A-Za-z0-9_.:-]{1,100}$");
    static final int MAX_TITLE = 200;
    static final int MAX_COMMENT = 1000;
    /** 결재 알림을 누르면 열 화면 — 내 결재함(개인 화면, docs/36 12.3) */
    static final String LINK_PROG = "sys-approval";
    /** 내 결재함 한 쪽 상한 */
    static final int BOX_LIMIT = 200;

    public enum LineType {
        APPROVE, AGREE, REF
    }

    public record LineSpec(long approverId, LineType type) {
    }

    public record RequestView(long aprId, String docType, String bizKey, String title, String status, long requesterId,
            Integer curSeq, OffsetDateTime requestedAt, OffsetDateTime finishedAt) {
    }

    /** actedByNm = 실제 처리자가 결재자와 다를 때(대결) 그 사람 이름, 아니면 null */
    public record LineView(int seq, long approverId, String approverNm, String lineType, String status,
            OffsetDateTime actedAt, String comment, String actedByNm) {
    }

    /** onBehalfOf = 대결일 때 원 결재자 */
    public record HistoryView(String action, Integer seq, long actorId, String comment, OffsetDateTime occurredAt,
            Long onBehalfOf) {
    }

    /** esignApprove·esignReject = 지금 차례 줄을 승인·반려할 때 열 서명(문서 유형 esignFor, 필요 없으면 null) */
    public record Detail3(RequestView request, List<LineView> lines, List<HistoryView> history,
            ApprovalDocType.ESignSpec esignApprove, ApprovalDocType.ESignSpec esignReject) {
    }

    public record TodoView(long aprId, String docType, String bizKey, String title, long requesterId,
            String requesterNm, OffsetDateTime requestedAt, int seq, String lineType) {
    }

    /** 내 결재함 한 줄. myLine = 이 결재에서 내 차례 상태(요청자만이면 null) */
    public record BoxView(long aprId, String docType, String bizKey, String title, String status, long requesterId,
            String requesterNm, OffsetDateTime requestedAt, OffsetDateTime finishedAt, String myLineType,
            String myLineStatus, String docProg, Map<String, String> docParams, boolean myTurn, boolean mine,
            boolean delegated) {
    }

    /** TODO 내 차례 · REQUESTED 내가 올린 것 · PASSED 결재선에 있었던 것(참조·처리함·대기) */
    public enum Box {
        TODO, REQUESTED, PASSED
    }

    private final JdbcTemplate jdbc;
    private final Map<String, ApprovalDocType> docTypes;
    private final OutboxService outbox;
    private final InboxService inbox;
    private final PolicyService policies;
    private final NotificationService notify;
    private final BizClock clock;

    public ApprovalService(JdbcTemplate jdbc, ObjectProvider<ApprovalDocType> docTypes, OutboxService outbox, InboxService inbox,
            PolicyService policies, NotificationService notify, BizClock clock) {
        this.jdbc = jdbc;
        this.docTypes = docTypes.orderedStream()
                .collect(Collectors.toUnmodifiableMap(ApprovalDocType::docType, Function.identity()));
        this.outbox = outbox;
        this.inbox = inbox;
        this.policies = policies;
        this.notify = notify;
        this.clock = clock;
    }

    /**
     * 오늘(회사 날짜) 유효한 위임 — 위임자 컬럼 식(예 l.approver_id)·문서 유형 식(예 r.doc_type)을 받아 조건 조각을 만든다.
     * 인자 = 대리 id, 오늘 (docs/36 14.6)
     */
    private static String delegated(String approverExpr, String docTypeExpr) {
        // 앞뒤 공백: 텍스트 블록은 줄 끝 공백을 지우므로 "... and \"\"\" + 조각" 이 붙어 버리지 않게
        return " exists (select 1 from apr_delegate dg where dg.delegator_id = " + approverExpr
                + " and dg.delegate_id = ? and dg.revoked_at is null and ? between dg.from_date and dg.to_date"
                + " and (dg.doc_type is null or dg.doc_type = " + docTypeExpr + ")) ";
    }

    /** 지금 차례에 내가 처리할 수 있는가 — 본인 차례 또는 오늘 유효한 위임(요청자 본인은 대결 불가). onBehalfOf = 원 결재자 */
    private record Acting(int seq, String lineType, Long onBehalfOf) {
    }

    private Acting acting(long aprId, Map<String, Object> req, long userId) {
        List<Map<String, Object>> cur = jdbc.queryForList(
                "select seq, approver_id, line_type from apr_line where apr_id = ? and status = 'CURRENT'", aprId);
        if (cur.isEmpty()) {
            throw new BusinessException(HbErrorCode.APR_NOT_YOUR_TURN);
        }
        int seq = ((Number) cur.get(0).get("seq")).intValue();
        long approver = ((Number) cur.get(0).get("approver_id")).longValue();
        String type = (String) cur.get(0).get("line_type");
        if (approver == userId) {
            return new Acting(seq, type, null);
        }
        if (((Number) req.get("requester_id")).longValue() != userId && Boolean.TRUE.equals(jdbc.queryForObject(
                "select " + delegated("?", "?"), Boolean.class, approver, userId, clock.today(), req.get("doc_type")))) {
            return new Acting(seq, type, approver);
        }
        throw new BusinessException(HbErrorCode.APR_NOT_YOUR_TURN);
    }

    // ------------------------------------------------------------------ 상신

    @Transactional
    public long submit(String docType, String bizKey, String title, List<LineSpec> lines) {
        LoginUser me = me();
        ApprovalDocType handler = docType == null ? null : docTypes.get(docType);
        if (handler == null || bizKey == null || !BIZ_KEY.matcher(bizKey).matches()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String cleanTitle = title == null ? "" : title.strip();
        if (cleanTitle.isEmpty() || cleanTitle.length() > MAX_TITLE) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        validateLines(lines, me.userId());
        handler.beforeSubmit(bizKey, me.userId());

        Long aprId;
        try {
            aprId = jdbc.queryForObject("""
                    insert into apr_request (comp_cd, doc_type, biz_key, title, requester_id, created_by, updated_by)
                    values (hb_current_comp(), ?, ?, ?, ?, ?, ?) returning apr_id""", Long.class,
                    docType, bizKey, cleanTitle, me.userId(), me.loginId(), me.loginId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HbErrorCode.APR_ALREADY_PENDING);
        }
        Integer first = null;
        for (int i = 0; i < lines.size(); i++) {
            LineSpec l = lines.get(i);
            int seq = i + 1;
            String status;
            if (l.type() == LineType.REF) {
                status = "REF";
            } else if (first == null) {
                first = seq;
                status = "CURRENT";
            } else {
                status = "WAIT";
            }
            jdbc.update("""
                    insert into apr_line (comp_cd, apr_id, seq, approver_id, line_type, status)
                    values (hb_current_comp(), ?, ?, ?, ?, ?)""", aprId, seq, l.approverId(), l.type().name(), status);
        }
        jdbc.update("update apr_request set cur_seq = ? where apr_id = ?", first, aprId);
        history(aprId, "SUBMIT", null, me.userId(), null);
        notifyCurrent(aprId);
        return aprId;
    }

    private void validateLines(List<LineSpec> lines, long requesterId) {
        if (lines == null || lines.isEmpty()) {
            throw invalidLine("apr.line.empty");
        }
        int max = policies.intValue(Policies.APR_MAX_LINES);
        if (lines.size() > max) {
            throw invalidLine("apr.line.tooMany", max);
        }
        if (lines.stream().anyMatch(l -> l == null || l.type() == null)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (lines.stream().noneMatch(l -> l.type() == LineType.APPROVE)) {
            throw invalidLine("apr.line.noApprover");
        }
        Set<Long> seen = new HashSet<>();
        for (LineSpec l : lines) {
            if (!seen.add(l.approverId())) {
                throw invalidLine("apr.line.duplicate");
            }
        }
        boolean selfAllowed = policies.bool(Policies.APR_SELF_APPROVE_ALLOWED);
        if (!selfAllowed && lines.stream().anyMatch(l -> l.type() != LineType.REF && l.approverId() == requesterId)) {
            throw invalidLine("apr.line.self");
        }
        // 같은 회사(RLS)의 사용 중·관리자 잠금 아닌 회사 사용자만 — 다른 회사 사용자 ID 는 0건으로 보인다,
        // 협력사 포털 사용자는 결재선에 들지 않는다(docs/42 2장, 트리거도 막는다)
        String ids = seen.stream().map(String::valueOf).collect(Collectors.joining(","));
        Integer active = jdbc.queryForObject("""
                select count(*) from sys_user
                 where user_id = any (string_to_array(?, ',')::bigint[])
                   and user_type = 'INTERNAL'
                   and use_yn = 'Y' and not (lock_yn = 'Y' and locked_until is null)""", Integer.class, ids);
        if (active == null || active != seen.size()) {
            throw invalidLine("apr.line.inactive");
        }
    }

    // ------------------------------------------------------------------ 처리

    @Transactional
    public void approve(long aprId, String comment) {
        approve(aprId, comment, null);
    }

    /** esignToken = 문서 유형 beforeAct 로 넘기는 전자서명 토큰(없으면 null) */
    @Transactional
    public void approve(long aprId, String comment, String esignToken) {
        LoginUser me = me();
        inbox.claimRequestKey("APR_ACT");
        Map<String, Object> req = lockPending(aprId);
        Acting act = acting(aprId, req, me.userId());
        int seq = act.seq();
        String action = "AGREE".equals(act.lineType()) ? "AGREE" : "APPROVE";
        List<Integer> next = jdbc.queryForList("""
                select seq from apr_line where apr_id = ? and status = 'WAIT' order by seq limit 1""", Integer.class, aprId);
        beforeAct(req, aprId, act, true, next.isEmpty(), me, esignToken);
        jdbc.update("""
                update apr_line set status = 'APPROVED', acted_at = now(), comment = ?, acted_by = ?
                 where apr_id = ? and seq = ? and status = 'CURRENT'""", clip(comment), me.userId(), aprId, seq);
        history(aprId, action, seq, me.userId(), comment, act.onBehalfOf());

        if (next.isEmpty()) {
            finish(aprId, ApprovalStatus.APPROVED, me);
        } else {
            jdbc.update("update apr_line set status = 'CURRENT' where apr_id = ? and seq = ?", aprId, next.get(0));
            touch(aprId, next.get(0), me);
            notifyCurrent(aprId);
        }
    }

    @Transactional
    public void reject(long aprId, String comment) {
        reject(aprId, comment, null);
    }

    @Transactional
    public void reject(long aprId, String comment, String esignToken) {
        LoginUser me = me();
        if (VisibleText.isBlank(comment)) {   // 보이지 않는 글자(U+200B·NBSP 등)뿐 = 의견 없음(QA R4-a)
            throw new BusinessException(HbErrorCode.INVALID_VALUE, List.of(new Detail("apr.reject.commentRequired")));
        }
        inbox.claimRequestKey("APR_ACT");
        Map<String, Object> req = lockPending(aprId);
        Acting act = acting(aprId, req, me.userId());
        int seq = act.seq();
        beforeAct(req, aprId, act, false, true, me, esignToken);
        jdbc.update("""
                update apr_line set status = 'REJECTED', acted_at = now(), comment = ?, acted_by = ?
                 where apr_id = ? and seq = ? and status = 'CURRENT'""", clip(comment), me.userId(), aprId, seq);
        jdbc.update("update apr_line set status = 'SKIPPED' where apr_id = ? and status = 'WAIT'", aprId);
        history(aprId, "REJECT", seq, me.userId(), comment, act.onBehalfOf());
        finish(aprId, ApprovalStatus.REJECTED, me);
    }

    /** 요청자만, 아무도 처리하기 전에만 */
    @Transactional
    public void withdraw(long aprId, String comment) {
        LoginUser me = me();
        inbox.claimRequestKey("APR_ACT");
        Map<String, Object> req = lockPending(aprId);
        if (((Number) req.get("requester_id")).longValue() != me.userId()) {
            throw new BusinessException(HbErrorCode.APR_NOT_YOUR_TURN);
        }
        Integer acted = jdbc.queryForObject(
                "select count(*) from apr_line where apr_id = ? and status = 'APPROVED'", Integer.class, aprId);
        if (acted != null && acted > 0) {
            throw new BusinessException(HbErrorCode.APR_NOT_WITHDRAWABLE);
        }
        jdbc.update("update apr_line set status = 'SKIPPED' where apr_id = ? and status in ('CURRENT', 'WAIT')", aprId);
        history(aprId, "WITHDRAW", null, me.userId(), comment);
        finish(aprId, ApprovalStatus.WITHDRAWN, me);
    }

    private void finish(long aprId, ApprovalStatus result, LoginUser me) {
        Map<String, Object> req = jdbc.queryForMap("""
                update apr_request set status = ?, finished_at = now(), cur_seq = null, row_ver = row_ver + 1,
                       updated_at = now(), updated_by = ?
                 where apr_id = ? returning doc_type, biz_key, requester_id, title""", result.name(), me.loginId(), aprId);
        handler((String) req.get("doc_type")).onFinished((String) req.get("biz_key"), result);
        if (result != ApprovalStatus.WITHDRAWN) {
            long requester = ((Number) req.get("requester_id")).longValue();
            outbox.publish(NotifyLogHandler.TOPIC, "APR:" + aprId, Map.of("kind", "APR_" + result.name(),
                    "aprId", aprId, "userId", requester));
            // 사내 알림함 (docs/36 11.3) — 같은 트랜잭션: 결재가 롤백되면 알림도 없다
            notify.send(NotifyMessage.of(PlatformNotifyEvents.APR_DONE, requester, "APR:" + aprId + ":" + result.name(),
                    result == ApprovalStatus.APPROVED ? "notify.apr.approved" : "notify.apr.rejected",
                    (String) req.get("title")).withLink(LINK_PROG, Map.of()));
        }
    }

    private ApprovalDocType handler(String docType) {
        ApprovalDocType handler = docTypes.get(docType);
        if (handler == null) {
            // 상신 뒤 모듈이 빠진 경우 — 업무 상태를 바꿀 수 없으므로 결재도 처리하지 않는다
            throw new IllegalStateException("결재 문서 유형 핸들러 없음: " + docType);
        }
        return handler;
    }

    /** 줄 처리 직전 문서 유형 확장점 (ApprovalDocType.beforeAct) — 같은 트랜잭션, 거부하면 처리 전체 롤백 */
    private void beforeAct(Map<String, Object> req, long aprId, Acting act, boolean approve, boolean finishes, LoginUser me,
            String esignToken) {
        handler((String) req.get("doc_type")).beforeAct((String) req.get("biz_key"), new ApprovalDocType.LineAct(aprId, act.seq(),
                LineType.valueOf(act.lineType()), approve, finishes, me.userId(), act.onBehalfOf(),
                esignToken == null || esignToken.isBlank() ? null : esignToken));
    }

    private Map<String, Object> lockPending(long aprId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status, requester_id, doc_type, biz_key from apr_request where apr_id = ? for update", aprId);
        if (rows.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!ApprovalStatus.PENDING.name().equals(rows.get(0).get("status"))) {
            throw new BusinessException(HbErrorCode.APR_CLOSED);
        }
        return rows.get(0);
    }

    private void touch(long aprId, int curSeq, LoginUser me) {
        jdbc.update("""
                update apr_request set cur_seq = ?, row_ver = row_ver + 1, updated_at = now(), updated_by = ?
                 where apr_id = ?""", curSeq, me.loginId(), aprId);
    }

    private void notifyCurrent(long aprId) {
        jdbc.query("""
                select l.approver_id, l.seq, r.title, u.user_nm
                  from apr_line l
                  join apr_request r on r.apr_id = l.apr_id
                  join sys_user u on u.user_id = r.requester_id
                 where l.apr_id = ? and l.status = 'CURRENT'""", rs -> {
            long userId = rs.getLong(1);
            outbox.publish(NotifyLogHandler.TOPIC, "APR:" + aprId, Map.of("kind", "APR_TODO", "aprId", aprId, "userId", userId));
            // 사내 알림함 — 차례마다 한 번(중복 키 = 결재·차례)
            notify.send(NotifyMessage.of(PlatformNotifyEvents.APR_TODO, userId, "APR:" + aprId + ":" + rs.getInt(2),
                    "notify.apr.todo", rs.getString(3), rs.getString(4)).withLink(LINK_PROG, Map.of()));
        }, aprId);
        // 오늘 유효한 대리에게도(요청자 본인 제외, docs/36 14.6)
        jdbc.query("""
                select dg.delegate_id, l.seq, r.title, u.user_nm
                  from apr_line l
                  join apr_request r on r.apr_id = l.apr_id
                  join sys_user u on u.user_id = r.requester_id
                  join apr_delegate dg on dg.delegator_id = l.approver_id and dg.revoked_at is null
                                      and ? between dg.from_date and dg.to_date
                                      and (dg.doc_type is null or dg.doc_type = r.doc_type)
                 where l.apr_id = ? and l.status = 'CURRENT' and dg.delegate_id <> r.requester_id""", rs -> {
            notify.send(NotifyMessage.of(PlatformNotifyEvents.APR_TODO, rs.getLong(1), "APR:" + aprId + ":" + rs.getInt(2),
                    "notify.apr.todo", rs.getString(3), rs.getString(4)).withLink(LINK_PROG, Map.of()));
        }, clock.today(), aprId);
    }

    private void history(long aprId, String action, Integer seq, long actorId, String comment) {
        history(aprId, action, seq, actorId, comment, null);
    }

    private void history(long aprId, String action, Integer seq, long actorId, String comment, Long onBehalfOf) {
        jdbc.update("""
                insert into apr_history (comp_cd, apr_id, action, seq, actor_id, comment, on_behalf_of)
                values (hb_current_comp(), ?, ?, ?, ?, ?, ?)""", aprId, action, seq, actorId, clip(comment), onBehalfOf);
    }

    // ------------------------------------------------------------------ 조회

    /** 내가 지금 처리할 결재 ("내 할 일" 카드의 원천, docs/11) */
    @Transactional(readOnly = true)
    public List<TodoView> todo() {
        LoginUser me = me();
        return jdbc.query("""
                select r.apr_id, r.doc_type, r.biz_key, r.title, r.requester_id, u.user_nm, r.requested_at,
                       l.seq, l.line_type
                  from apr_line l
                  join apr_request r on r.apr_id = l.apr_id and r.status = 'PENDING'
                  join sys_user u on u.user_id = r.requester_id
                 where l.status = 'CURRENT'
                   and (l.approver_id = ? or (r.requester_id <> ? and """ + delegated("l.approver_id", "r.doc_type") + """
                       ))
                 order by r.requested_at, r.apr_id""",
                (rs, i) -> new TodoView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                        rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getInt(8), rs.getString(9)),
                me.userId(), me.userId(), me.userId(), clock.today());
    }

    /** 내 결재함 (docs/36 12.2) — 나와 관계있는 결재만(요청자·결재선), 최신순 */
    @Transactional(readOnly = true)
    public List<BoxView> box(Box box) {
        LoginUser me = me();
        String b = (box == null ? Box.TODO : box).name();
        java.time.LocalDate today = clock.today();
        // dl = 지금 차례 결재자에게서 오늘 유효한 위임을 받았는가(요청자 본인 제외), ah = 대결로 처리한 적이 있는가
        return jdbc.query("""
                select r.apr_id, r.doc_type, r.biz_key, r.title, r.status, r.requester_id, u.user_nm, r.requested_at,
                       r.finished_at, ml.line_type, ml.status, dl.ok, ah.ok
                  from apr_request r
                  join sys_user u on u.user_id = r.requester_id
                  left join lateral (select l.line_id, l.line_type, l.status from apr_line l
                                      where l.apr_id = r.apr_id and l.approver_id = ?
                                      order by (l.status = 'CURRENT') desc, l.seq limit 1) ml on true
                  left join lateral (select true as ok from apr_line c
                                      where c.apr_id = r.apr_id and c.status = 'CURRENT' and r.status = 'PENDING'
                                        and r.requester_id <> ? and """ + delegated("c.approver_id", "r.doc_type") + """
                                      limit 1) dl on true
                  left join lateral (select true as ok from apr_history h
                                      where h.apr_id = r.apr_id and h.actor_id = ? and h.on_behalf_of is not null
                                      limit 1) ah on true
                 where (r.requester_id = ? or ml.line_id is not null or dl.ok or ah.ok)
                   and ((? = 'TODO' and r.status = 'PENDING' and (ml.status = 'CURRENT' or dl.ok))
                     or (? = 'REQUESTED' and r.requester_id = ?)
                     or (? = 'PASSED' and ((ml.line_id is not null and ml.status <> 'CURRENT') or ah.ok)))
                 order by r.requested_at desc, r.apr_id desc
                 limit ?""",
                (rs, i) -> {
                    ApprovalDocType t = docTypes.get(rs.getString(2));
                    String prog = t == null ? null : t.progCd();
                    boolean viaDelegate = Boolean.TRUE.equals(rs.getObject(12));
                    return new BoxView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getLong(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class),
                            rs.getObject(9, OffsetDateTime.class), rs.getString(10), rs.getString(11), prog,
                            prog == null ? Map.of() : t.linkParams(rs.getString(3)),
                            "PENDING".equals(rs.getString(5)) && ("CURRENT".equals(rs.getString(11)) || viaDelegate),
                            rs.getLong(6) == me.userId(), viaDelegate && !"CURRENT".equals(rs.getString(11)));
                },
                me.userId(), me.userId(), me.userId(), today, me.userId(), me.userId(), b, b, me.userId(), b, BOX_LIMIT);
    }

    /** 상세 — 요청자·결재선(참조 포함)만. 그 밖은 존재를 알리지 않고 404 */
    @Transactional(readOnly = true)
    public Detail3 detail(long aprId) {
        LoginUser me = me();
        List<RequestView> req = jdbc.query("""
                select apr_id, doc_type, biz_key, title, status, requester_id, cur_seq, requested_at, finished_at
                  from apr_request r
                 where apr_id = ?
                   and (requester_id = ? or exists (select 1 from apr_line l where l.apr_id = r.apr_id and l.approver_id = ?)
                        -- 대리: 지금 차례 결재자에게서 오늘 유효한 위임 / 대결로 처리한 적이 있음 (docs/36 14.6)
                        or exists (select 1 from apr_line c where c.apr_id = r.apr_id and c.status = 'CURRENT'
                                      and r.requester_id <> ? and """ + delegated("c.approver_id", "r.doc_type") + """
                                  )
                        or exists (select 1 from apr_history h where h.apr_id = r.apr_id and h.actor_id = ?))""",
                (rs, i) -> new RequestView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getLong(6), (Integer) rs.getObject(7), rs.getObject(8, OffsetDateTime.class),
                        rs.getObject(9, OffsetDateTime.class)),
                aprId, me.userId(), me.userId(), me.userId(), me.userId(), clock.today(), me.userId());
        if (req.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<LineView> lines = jdbc.query("""
                select l.seq, l.approver_id, u.user_nm, l.line_type, l.status, l.acted_at, l.comment,
                       case when l.acted_by is not null and l.acted_by <> l.approver_id then a.user_nm end
                  from apr_line l join sys_user u on u.user_id = l.approver_id
                  left join sys_user a on a.user_id = l.acted_by
                 where l.apr_id = ? order by l.seq""",
                (rs, i) -> new LineView(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, OffsetDateTime.class), rs.getString(7), rs.getString(8)), aprId);
        List<HistoryView> history = jdbc.query("""
                select action, seq, actor_id, comment, occurred_at, on_behalf_of from apr_history where apr_id = ? order by hist_id""",
                (rs, i) -> new HistoryView(rs.getString(1), (Integer) rs.getObject(2), rs.getLong(3), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class), (Long) rs.getObject(6)), aprId);
        RequestView r = req.get(0);
        ApprovalDocType.ESignSpec signApprove = null;
        ApprovalDocType.ESignSpec signReject = null;
        LineView cur = lines.stream().filter(l -> "CURRENT".equals(l.status())).findFirst().orElse(null);
        ApprovalDocType handler = docTypes.get(r.docType());
        if ("PENDING".equals(r.status()) && cur != null && handler != null) {
            LineType type = LineType.valueOf(cur.lineType());
            signApprove = handler.esignFor(r.bizKey(), type, true);
            signReject = handler.esignFor(r.bizKey(), type, false);
        }
        return new Detail3(r, lines, history, signApprove, signReject);
    }

    // ------------------------------------------------------------------

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }

    private static BusinessException invalidLine(String key, Object... args) {
        List<Detail> d = new ArrayList<>();
        d.add(new Detail(key, args));
        return new BusinessException(HbErrorCode.APR_INVALID_LINE, d);
    }

    private static String clip(String comment) {
        if (comment == null) {
            return null;
        }
        String c = VisibleText.strip(comment);
        return c == null ? null : c.length() > MAX_COMMENT ? c.substring(0, MAX_COMMENT) : c;
    }
}
