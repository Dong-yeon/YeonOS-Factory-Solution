package io.smarthappy.platform.ops;

import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.UsageDailyService;
import io.smarthappy.platform.security.DevCompanySeed;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.RequiredArgsConstructor;

/**
 * 운영자 콘솔 기능 (01 9장 9단계). 모든 동작은 sys_ops_log 에 남는다.
 *
 * - 회사 목록·생성(초기 관리자·권한그룹·기본 메뉴 포함)·상태 변경·관리자 잠금 해제
 * - 활용 현황: **집계 숫자만**(docs/11 D3 사용자 결정) — 운영자 범위(OPS) 정책으로 읽는다
 * - 회사의 업무 데이터·로그 원문은 **고객이 허락한 지원 세션** 안에서만(기간·범위·운영자 지정, 05 D8)
 * "모든 회사 업무 데이터 조회" 경로는 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class OpsConsoleService {

    static final Pattern LOGIN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,49}$");
    static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final OpsLogService opsLog;
    private final UsageDailyService usage;
    private final io.smarthappy.platform.security.MfaService mfa;
    private final io.smarthappy.platform.module.CompModuleService modules;

    /** modules = 켤 업무 모듈(docs/80) — 비우면(null) 전부 켜짐(행 없음) */
    public record CreateCompany(String compCd, String compNm, String timeZone, String adminLoginId, String adminName,
            java.util.List<String> modules) {
    }

    /** 생성 결과 — 임시 비밀번호는 이 응답에서 한 번만 보인다(다음 로그인 때 바꾸게 강제) */
    public record Created(String compCd, String adminLoginId, String tempPassword) {
    }

    public List<Map<String, Object>> companies() {
        OpsUser me = me();
        return runner.callOps(me.actor(), () -> jdbc.queryForList("""
                select comp_cd as "compCd", comp_nm as "compNm", status_cd as "statusCd", use_yn as "useYn",
                       time_zone as "timeZone", created_at as "createdAt"
                  from sys_comp order by comp_cd"""));
    }

    public Created createCompany(CreateCompany req, String ip) {
        OpsUser me = me();
        FieldErrors e = FieldErrors.create();
        String comp = req == null || req.compCd() == null ? null : req.compCd().strip().toUpperCase(Locale.ROOT);
        if (comp == null || !TenantContext.isValidCompCd(comp)) {
            e.add("compCd", "FORMAT");
        }
        String nm = req == null || req.compNm() == null ? "" : req.compNm().strip();
        if (nm.isEmpty() || nm.length() > 100) {
            e.add("compNm", nm.isEmpty() ? "REQUIRED" : "TOO_LONG", 100);
        }
        String tz = req == null || req.timeZone() == null || req.timeZone().isBlank() ? "Asia/Seoul" : req.timeZone().strip();
        try {
            ZoneId.of(tz);
        } catch (DateTimeException ex) {
            e.add("timeZone", "FORMAT");
        }
        String adminId = req == null || req.adminLoginId() == null ? "" : req.adminLoginId().strip();
        if (!LOGIN_ID.matcher(adminId).matches()) {
            e.add("adminLoginId", "FORMAT");
        }
        String adminNm = req == null || req.adminName() == null || req.adminName().isBlank() ? "관리자" : req.adminName().strip();
        if (adminNm.length() > 100) {
            e.add("adminName", "TOO_LONG", 100);
        }
        e.throwIfAny();
        // 사용 모듈: 목록 밖 코드·필수 짝 빠짐은 회사를 만들기 전에 거부(400)
        java.util.Set<String> moduleOn = req.modules() == null ? null
                : modules.validate(req.modules(), org.springframework.context.i18n.LocaleContextHolder.getLocale());
        boolean exists = Boolean.TRUE.equals(runner.callOps(me.actor(), () -> jdbc.queryForObject(
                "select exists (select 1 from sys_comp where comp_cd = ?)", Boolean.class, comp)));
        if (exists) {
            opsLog.record(me.loginId(), "COMP_CREATE", comp, "FAIL", ip, "duplicate");
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(
                    FieldErrors.create().add("compCd", "DUPLICATE").list());
        }
        String temp = tempPassword();
        String finalNm = nm;
        runner.callAs(comp, me.actor(), () -> {
            jdbc.update("""
                    insert into sys_comp (comp_cd, comp_nm, time_zone, created_by, updated_by) values (?, ?, ?, ?, ?)""",
                    comp, finalNm, tz, me.actor(), me.actor());
            jdbc.update("""
                    insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, admin_yn, created_by, updated_by)
                    values (?, 'ADMIN', '관리자', 'Y', ?, ?)""", comp, me.actor(), me.actor());
            jdbc.update("""
                    insert into sys_auth_prog (comp_cd, auth_grp_cd, prog_cd, action_cd, created_by)
                    select ?, 'ADMIN', p.prog_cd, a, ? from sys_prog p, unnest(p.actions) a where p.use_yn = 'Y'""",
                    comp, me.actor());
            Long userId = jdbc.queryForObject("""
                    insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, pwd_change_req_yn, created_by, updated_by)
                    values (?, ?, ?, ?, 'Y', ?, ?) returning user_id""", Long.class,
                    comp, adminId, adminNm, encoder.encode(temp), me.actor(), me.actor());
            jdbc.update("""
                    insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, 'ADMIN', ?)""",
                    comp, userId, me.actor());
            copyDefaultMenus(comp, me.actor());
            if (moduleOn != null) {
                io.smarthappy.platform.module.CompModuleService.write(jdbc, comp, moduleOn, me.actor());
            }
            return null;
        });
        opsLog.record(me.loginId(), "COMP_CREATE", comp, "OK", ip, "admin=" + adminId);
        return new Created(comp, adminId, temp);
    }

    /** 기본 메뉴: 개발·데모 시드와 같은 트리 한 벌(docs/75) — 회사 관리자가 이후 메뉴 관리에서 손본다 */
    private void copyDefaultMenus(String comp, String actor) {
        DevCompanySeed.ensureMenuTree(jdbc, comp, actor);
    }

    public void setStatus(String comp, String status, String ip) {
        OpsUser me = me();
        if (!TenantContext.isValidCompCd(comp) || status == null || !STATUSES.contains(status)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        int n = runner.callAs(comp, me.actor(), () -> jdbc.update("""
                update sys_comp set status_cd = ?, updated_at = now(), updated_by = ? where comp_cd = ?""",
                status, me.actor(), comp));
        if (n == 0) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        opsLog.record(me.loginId(), "COMP_STATUS", comp, "OK", ip, status);
    }

    /** 회사 관리자 잠금 해제 — 실패 잠금·관리자 잠금 모두 풀고 실패 횟수 초기화 (회사는 운영 기록으로 확인 가능) */
    public void unlockUser(String comp, String loginId, String ip) {
        OpsUser me = me();
        if (!TenantContext.isValidCompCd(comp) || loginId == null || !LOGIN_ID.matcher(loginId).matches()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        int n = runner.callAs(comp, me.actor(), () -> jdbc.update("""
                update sys_user set lock_yn = 'N', locked_until = null, login_fail_cnt = 0, updated_at = now(), updated_by = ?
                 where lower(login_id) = lower(?)""", me.actor(), loginId));
        if (n == 0) {
            opsLog.record(me.loginId(), "USER_UNLOCK", comp, "FAIL", ip, "not found");
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        opsLog.record(me.loginId(), "USER_UNLOCK", comp, "OK", ip, "login=" + loginId);
    }

    /**
     * 회사 사용자의 MFA 초기화 (docs/36 Q25 — 다른 관리자가 없는 마지막 관리자가 인증 앱을 잃었을 때).
     * 그 회사가 연 지원 세션(USERS 범위) 안에서만, 운영자 동작 로그에 남는다. 세션은 끊고 다음 로그인 때 다시 등록.
     */
    public void resetUserMfa(String comp, String loginId, String ip) {
        OpsUser me = me();
        if (!TenantContext.isValidCompCd(comp) || loginId == null || !LOGIN_ID.matcher(loginId).matches()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String result = runner.callAs(comp, me.actor(), () -> {
            Boolean granted = jdbc.queryForObject("""
                    select exists (select 1 from sys_support_grant
                                    where op_login_id = ? and scope_cd = 'USERS' and revoked_at is null
                                      and now() >= from_at and now() < to_at)""", Boolean.class, me.loginId());
            if (!Boolean.TRUE.equals(granted)) {
                return "DENIED";
            }
            List<Long> ids = jdbc.queryForList("select user_id from sys_user where lower(login_id) = lower(?)", Long.class, loginId);
            if (ids.isEmpty() || !mfa.reset(ids.get(0))) {
                return "NOT_FOUND";
            }
            jdbc.update("""
                    update sys_user set session_not_before = now(), row_ver = row_ver + 1, updated_at = now(), updated_by = ?
                     where user_id = ?""", me.actor(), ids.get(0));
            return "OK";
        });
        opsLog.record(me.loginId(), "USER_MFA_RESET", comp, "OK".equals(result) ? "OK" : "DENIED".equals(result) ? "DENIED" : "FAIL",
                ip, "login=" + loginId);
        if ("DENIED".equals(result)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        if ("NOT_FOUND".equals(result)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    /** 활용 집계 숫자만 (D3) */
    public Map<String, Object> usage(String comp, LocalDate from, LocalDate to, String ip) {
        OpsUser me = me();
        if (!TenantContext.isValidCompCd(comp)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Map<String, Object> out = runner.callOps(me.actor(), () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("days", usage.summary(comp, from, to));
            m.put("programs", usage.topPrograms(comp, from, to));
            return m;
        });
        opsLog.record(me.loginId(), "USAGE_VIEW", comp, "OK", ip, from + "~" + to);
        return out;
    }

    /**
     * 지원 세션 열람 — 그 회사 관리자가 이 운영자에게 이 범위로 지금 유효한 허락을 줬을 때만.
     * LOGS = 최근 7일 접속·보안 로그(최대 500), FEEDBACK = 화면 의견(최대 200)
     */
    public List<Map<String, Object>> supportView(String comp, String scope, String ip) {
        OpsUser me = me();
        if (!TenantContext.isValidCompCd(comp) || scope == null || !Set.of("LOGS", "FEEDBACK").contains(scope)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return runner.callAs(comp, me.actor(), () -> {
            Boolean granted = jdbc.queryForObject("""
                    select exists (select 1 from sys_support_grant
                                    where op_login_id = ? and scope_cd = ? and revoked_at is null
                                      and now() >= from_at and now() < to_at)""", Boolean.class, me.loginId(), scope);
            if (!Boolean.TRUE.equals(granted)) {
                opsLog.record(me.loginId(), "SUPPORT_VIEW", comp, "DENIED", ip, scope);
                throw new BusinessException(HbErrorCode.NO_PERMISSION);
            }
            opsLog.record(me.loginId(), "SUPPORT_VIEW", comp, "OK", ip, scope);
            return "LOGS".equals(scope)
                    ? jdbc.queryForList("""
                            select occurred_at as "occurredAt", login_id as "loginId", event_cd as "eventCd",
                                   prog_cd as "progCd", action_cd as "actionCd", result_cd as "resultCd",
                                   severity, client_type as "clientType"
                              from sys_access_log where occurred_at > now() - interval '7 days'
                             order by log_id desc limit 500""")
                    : jdbc.queryForList("""
                            select created_at as "createdAt", prog_cd as "progCd", content, status, client_type as "clientType"
                              from sys_feedback order by feedback_id desc limit 200""");
        });
    }

    public List<Map<String, Object>> myLog() {
        OpsUser me = me();
        return runner.callOps(me.actor(), () -> jdbc.queryForList("""
                select occurred_at as "occurredAt", action, target_comp as "targetComp", result_cd as "resultCd", ip, detail
                  from sys_ops_log where op_login_id = ? order by log_id desc limit 100""", me.loginId()));
    }

    /** 임시 비밀번호 16자 — 대·소문자·숫자·기호 모두 포함, 헷갈리는 글자(0 O 1 l I) 제외 */
    static String tempPassword() {
        String[] sets = {"ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnpqrstuvwxyz", "23456789", "!@#$%*-_+"};
        StringBuilder sb = new StringBuilder();
        for (String s : sets) {
            sb.append(s.charAt(RANDOM.nextInt(s.length())));
        }
        String all = String.join("", sets);
        while (sb.length() < 16) {
            sb.append(all.charAt(RANDOM.nextInt(all.length())));
        }
        char[] c = sb.toString().toCharArray();
        for (int i = c.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = c[i];
            c[i] = c[j];
            c[j] = t;
        }
        return new String(c);
    }

    private static OpsUser me() {
        return OpsUser.current().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
