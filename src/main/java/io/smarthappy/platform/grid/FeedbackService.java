package io.smarthappy.platform.grid;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;

/**
 * 화면 피드백 "이 화면 불편해요" (docs/11 Top #10) — 1인 개발자의 가장 싼 현장 관찰.
 * 그 화면을 쓸 수 있는 사람(SEARCH)만, 사용자당 1시간 {@value #HOURLY_LIMIT}건까지(도배 방지).
 */
@Service
public class FeedbackService {

    static final int MAX_CONTENT = 2000;
    static final int HOURLY_LIMIT = 10;

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public FeedbackService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    @Transactional
    public long submit(String progCd, String content) {
        LoginUser me = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        String text = content == null ? "" : content.strip();
        if (progCd == null || !progCd.matches("^[a-z0-9][a-z0-9-]{1,49}$") || text.isEmpty()
                || text.length() > MAX_CONTENT) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (!permissions.actions(progCd).contains(Action.SEARCH)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        Integer recent = jdbc.queryForObject("""
                select count(*) from sys_feedback where user_id = ? and created_at > now() - interval '1 hour'""",
                Integer.class, me.userId());
        if (recent != null && recent >= HOURLY_LIMIT) {
            throw new BusinessException(HbErrorCode.TOO_MANY_REQUESTS);
        }
        String clientType = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? AccessLogService.clientTypeOf(a.getRequest()) : "WEB";
        return jdbc.queryForObject("""
                insert into sys_feedback (comp_cd, user_id, prog_cd, content, client_type, created_by, updated_by)
                values (hb_current_comp(), ?, ?, ?, ?, ?, ?) returning feedback_id""", Long.class,
                me.userId(), progCd, text, clientType, me.loginId(), me.loginId());
    }
}
