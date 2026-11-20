package io.smarthappy.platform.approval;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.notify.PlatformNotifyEvents;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;

/**
 * 결재 위임 (docs/36 14.6, Q27) — 위임자 <b>본인</b>이 만들고 회수한다(내 결재함). 대결 판정은 {@link ApprovalService}.
 * 기간은 회사 날짜(양끝 포함, 시작 ≥ 오늘, 최대 366일), 같은 위임자의 사용 중 위임 겹침·자기 위임·다른 회사 사용자는 트리거가 최종.
 * 바꾸지 않는다 — 회수하고 새로.
 * 시작 알림(14.8): 위임이 시작되는 날(회사 날짜) 대리에게 "지금 대신 처리할 결재 n건" 한 번 — 오늘 시작이면 만들 때,
 * 미래 시작이면 정기 작업({@link ApprovalJobs}). 중복 키 = 위임 id 라 몇 번 불러도 한 번.
 */
@Service
public class ApprovalDelegateService {

    static final int MAX_DAYS = 365;
    static final int MAX_REASON = 500;
    static final Map<String, String[]> CONSTRAINT_FIELD = Map.of(
            "ck_apr_delegate_overlap", new String[] {"fromDate", "OVERLAP"},
            "ck_apr_delegate_self", new String[] {"delegateId", "SELF_DELEGATE"},
            "fk_apr_delegate_comp_user", new String[] {"delegateId", "NO_SUCH_CODE"},
            // 협력사 포털 사용자는 대리가 될 수 없다(docs/42 2장) — 후보 목록에 없으니 id 를 직접 넣은 경우
            "ck_internal_user_only", new String[] {"delegateId", "NO_SUCH_CODE"});

    public record DelegateView(long delegId, long delegatorId, String delegatorNm, long delegateId, String delegateNm,
            String docType, LocalDate fromDate, LocalDate toDate, String reason, OffsetDateTime revokedAt, String state,
            int rowVer, boolean mine, String revokeCd) {
    }

    public record DelegateForm(Long delegateId, String docType, LocalDate fromDate, LocalDate toDate, String reason) {
    }

    /** 대리 후보 (같은 회사 사용 중 사용자, 나 빼고) */
    public record Candidate(long userId, String loginId, String userNm) {
    }

    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final Set<String> docTypes;
    private final NotificationService notify;

    public ApprovalDelegateService(JdbcTemplate jdbc, BizClock clock, ObjectProvider<ApprovalDocType> docTypes,
            NotificationService notify) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.notify = notify;
        this.docTypes = docTypes.orderedStream().map(ApprovalDocType::docType).collect(Collectors.toUnmodifiableSet());
    }

    /** 내가 위임한 것 + 나에게 위임된 것 (최근 것부터 200건). mine = 내가 위임자(회수 버튼) */
    @Transactional(readOnly = true)
    public List<DelegateView> mine() {
        LoginUser me = me();
        LocalDate today = clock.today();
        return jdbc.query("""
                select d.deleg_id, d.delegator_id, a.user_nm, d.delegate_id, b.user_nm, d.doc_type, d.from_date, d.to_date,
                       d.reason, d.revoked_at, d.row_ver, d.revoke_cd
                  from apr_delegate d
                  join sys_user a on a.user_id = d.delegator_id
                  join sys_user b on b.user_id = d.delegate_id
                 where d.delegator_id = ? or d.delegate_id = ?
                 order by d.from_date desc, d.deleg_id desc
                 limit 200""", (rs, i) -> {
            LocalDate from = rs.getObject(7, LocalDate.class);
            LocalDate to = rs.getObject(8, LocalDate.class);
            OffsetDateTime revoked = rs.getObject(10, OffsetDateTime.class);
            String state = revoked != null ? "REVOKED" : today.isBefore(from) ? "SCHEDULED" : today.isAfter(to) ? "ENDED" : "ACTIVE";
            return new DelegateView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                    rs.getString(6), from, to, rs.getString(9), revoked, state, rs.getInt(11), rs.getLong(2) == me.userId(),
                    revoked == null ? null : rs.getString(12) == null ? "SELF" : rs.getString(12));
        }, me.userId(), me.userId());
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(String q) {
        LoginUser me = me();
        String kw = q == null ? "" : q.strip();
        if (kw.length() > 50) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String like = "%" + kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("""
                select user_id, login_id, user_nm from sys_user
                 where user_id <> ? and user_type = 'INTERNAL'
                   and use_yn = 'Y' and not (lock_yn = 'Y' and locked_until is null)
                   and (login_id ilike ? escape '\\' or user_nm ilike ? escape '\\')
                 order by user_nm, login_id limit 20""",
                (rs, i) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3)), me.userId(), like, like);
    }

    public Set<String> docTypes() {
        return docTypes;
    }

    @Transactional
    public long create(DelegateForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        LocalDate today = clock.today();
        if (in.delegateId() == null) {
            e.add("delegateId", "REQUIRED");
        } else if (in.delegateId() == me.userId()) {
            e.add("delegateId", "SELF_DELEGATE");
        }
        String docType = in.docType() == null || in.docType().isBlank() ? null : in.docType().strip();
        if (docType != null && !docTypes.contains(docType)) {
            e.add("docType", "CODE");
        }
        if (in.fromDate() == null) {
            e.add("fromDate", "REQUIRED");
        } else if (in.fromDate().isBefore(today)) {
            e.add("fromDate", "PAST");
        }
        if (in.toDate() == null) {
            e.add("toDate", "REQUIRED");
        } else if (in.fromDate() != null && (in.toDate().isBefore(in.fromDate())
                || in.toDate().isAfter(in.fromDate().plusDays(MAX_DAYS)))) {
            e.add("toDate", "INVALID_RANGE");
        }
        String reason = VisibleText.strip(in.reason());
        if (reason == null) {   // 보이지 않는 글자(U+200B·NBSP 등)뿐 = 사유 없음(QA R4-a)
            e.add("reason", "REQUIRED");
        } else if (reason.length() > MAX_REASON) {
            e.add("reason", "TOO_LONG", MAX_REASON);
        } else if (reason.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            e.add("reason", "FORMAT");
        }
        e.throwIfAny();
        long id;
        try {
            id = jdbc.queryForObject("""
                    insert into apr_delegate (comp_cd, delegator_id, delegate_id, doc_type, from_date, to_date, reason,
                                              created_by, updated_by)
                    values (hb_current_comp(), ?, ?, ?, ?, ?, ?, ?, ?) returning deleg_id""", Long.class,
                    me.userId(), in.delegateId(), docType, in.fromDate(), in.toDate(), reason, actor(me), actor(me));
        } catch (DataAccessException ex) {
            String[] fc = PgErrors.constraint(ex).map(CONSTRAINT_FIELD::get).orElse(null);
            if (fc == null) {
                throw ex;
            }
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(fc[0], fc[1]).list());
        }
        if (!in.fromDate().isAfter(today)) {
            notifyStarted(today, id);   // 오늘 시작 — 지금 알린다(같은 트랜잭션: 만들기가 롤백되면 알림도 없다)
        }
        return id;
    }

    /**
     * 오늘(회사 날짜) 사용 중인 위임마다 대리에게 시작 알림 — 중복 키 "DELEG:{id}" 라 이미 보낸 위임은 그냥 지나간다
     * (정기 작업을 놓친 날이 있어도 기간 안이면 다음 실행에서 한 번). 회사 범위 트랜잭션 안. 돌려주는 값 = 새로 보낸 수
     */
    @Transactional
    public int notifyStarted(LocalDate today) {
        return notifyStarted(today, null);
    }

    /**
     * 대리가 사용 중지돼 이 트랜잭션에서 끝난 위임(트리거 `hb_end_delegations_of_disabled`, docs/36 16.6)의 위임자에게 알림 한 번.
     * 사용 중지를 저장한 같은 트랜잭션 안에서 부른다 — 이번 트랜잭션의 행은 revoked_at = now()(트랜잭션 시작 시각)로 가린다.
     * 돌려주는 값 = 끝난 위임 수
     */
    @Transactional
    public int notifyEndedByDisable(long delegateUserId) {
        int[] n = {0};
        jdbc.query("""
                select d.deleg_id, d.delegator_id, b.user_nm from apr_delegate d
                  join sys_user b on b.user_id = d.delegate_id
                 where d.delegate_id = ? and d.revoke_cd = 'DELEGATE_OFF' and d.revoked_at = now()""", rs -> {
            n[0]++;
            notify.send(NotifyMessage.of(PlatformNotifyEvents.APR_DELEG_ENDED, rs.getLong(2), "DELEG-OFF:" + rs.getLong(1),
                    "notify.apr.delegEnded", rs.getString(3)).withLink(ApprovalService.LINK_PROG, Map.of()));
        }, delegateUserId);
        return n[0];
    }

    /** onlyId 가 있으면 그 위임만(만들 때) */
    private int notifyStarted(LocalDate today, Long onlyId) {
        int[] sent = {0};
        jdbc.query("""
                select d.deleg_id, d.delegate_id, a.user_nm,
                       (select count(*) from apr_line l
                          join apr_request r on r.apr_id = l.apr_id and r.status = 'PENDING'
                         where l.approver_id = d.delegator_id and l.status = 'CURRENT'
                           and r.requester_id <> d.delegate_id
                           and (d.doc_type is null or d.doc_type = r.doc_type)) as cnt
                  from apr_delegate d
                  join sys_user a on a.user_id = d.delegator_id
                 where d.revoked_at is null and ? between d.from_date and d.to_date
                   and (?::bigint is null or d.deleg_id = ?)""", rs -> {
            NotificationService.Result r = notify.send(NotifyMessage.of(PlatformNotifyEvents.APR_DELEG_START, rs.getLong(2),
                    "DELEG:" + rs.getLong(1), "notify.apr.delegStart", rs.getString(3), String.valueOf(rs.getLong(4)))
                    .withLink(ApprovalService.LINK_PROG, Map.of()));
            if (r == NotificationService.Result.SENT) {
                sent[0]++;
            }
        }, today, onlyId, onlyId);
        return sent[0];
    }

    /** 위임자 본인만 회수(대리는 거절할 수 없다 — 위임자에게 말한다). 남의 것·없는 것은 404 */
    @Transactional
    public void revoke(long delegId, Integer rowVer) {
        LoginUser me = me();
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select revoked_at from apr_delegate where deleg_id = ? and delegator_id = ?", delegId, me.userId());
        if (rows.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (rows.get(0).get("revoked_at") != null) {
            return;   // 이미 회수 — 멱등
        }
        int n = jdbc.update("""
                update apr_delegate set revoked_at = now(), revoke_cd = 'SELF', row_ver = row_ver + 1, updated_at = now(),
                       updated_by = ?
                 where deleg_id = ? and row_ver = ? and revoked_at is null""", actor(me), delegId, rowVer);
        if (n == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private static String actor(LoginUser me) {
        return "u" + me.userId() + ":" + me.loginId();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
