package io.smarthappy.platform.ops;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * 지원 세션 허락 (회사 관리자 쪽, 화면 sys-support). 운영자가 회사 데이터를 보려면 이 허락이 있어야 한다(05 D8).
 * 허락은 운영자·범위·기간(최대 7일)·사유를 정하고, 언제든 회수할 수 있다. 운영자가 무엇을 봤는지는 운영 기록으로 확인.
 */
@Service
@RequiredArgsConstructor
public class SupportGrantService {

    static final Set<String> SCOPES = Set.of("LOGS", "FEEDBACK", "USERS");
    static final int MAX_HOURS = 168;

    private final JdbcTemplate jdbc;

    public record GrantRequest(String opLoginId, String scopeCd, String reason, Integer hours) {
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                select grant_id as "grantId", op_login_id as "opLoginId", scope_cd as "scopeCd", reason,
                       from_at as "fromAt", to_at as "toAt", revoked_at as "revokedAt",
                       (revoked_at is null and now() >= from_at and now() < to_at) as "active", created_by as "createdBy"
                  from sys_support_grant order by grant_id desc limit 100""");
    }

    @Transactional
    public long grant(GrantRequest req) {
        LoginUser me = me();
        FieldErrors e = FieldErrors.create();
        String op = req == null || req.opLoginId() == null ? "" : req.opLoginId().strip().toLowerCase(java.util.Locale.ROOT);
        Boolean exists = op.isEmpty() ? Boolean.FALSE : jdbc.queryForObject(
                "select exists (select 1 from sys_operator where login_id = ? and use_yn = 'Y')", Boolean.class, op);
        if (!Boolean.TRUE.equals(exists)) {
            e.add("opLoginId", "NOT_FOUND");
        }
        if (req == null || req.scopeCd() == null || !SCOPES.contains(req.scopeCd())) {
            e.add("scopeCd", "CODE");
        }
        String reason = req == null ? null : VisibleText.strip(req.reason());
        if (reason == null) {   // 보이지 않는 글자(U+200B·NBSP 등)뿐 = 사유 없음(QA R4-a)
            e.add("reason", "REQUIRED");
        } else if (reason.length() > 500) {
            e.add("reason", "TOO_LONG", 500);
        }
        int hours = req == null || req.hours() == null ? 24 : req.hours();
        if (hours < 1 || hours > MAX_HOURS) {
            e.add("hours", "FORMAT");
        }
        e.throwIfAny();
        return jdbc.queryForObject("""
                insert into sys_support_grant (comp_cd, op_login_id, scope_cd, reason, from_at, to_at, approved_by,
                                               created_by, updated_by)
                values (?, ?, ?, ?, now(), now() + make_interval(hours => ?), ?, ?, ?) returning grant_id""", Long.class,
                TenantContext.requireCompCd(), op, req.scopeCd(), reason, hours, me.userId(), me.loginId(), me.loginId());
    }

    @Transactional
    public void revoke(long grantId) {
        LoginUser me = me();
        int n = jdbc.update("""
                update sys_support_grant set revoked_at = now(), row_ver = row_ver + 1, updated_at = now(), updated_by = ?
                 where grant_id = ? and revoked_at is null""", me.loginId(), grantId);
        if (n == 0) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    /** 우리 회사를 대상으로 한 운영자 동작 (전역 표라 회사 조건을 직접 건다) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> opsLog() {
        return jdbc.queryForList("""
                select occurred_at as "occurredAt", op_login_id as "opLoginId", action, result_cd as "resultCd", detail
                  from sys_ops_log where target_comp = ? order by log_id desc limit 200""", TenantContext.requireCompCd());
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
