package io.smarthappy.module.admin;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.admin.AdminDtos.GroupChoice;
import io.smarthappy.module.admin.AdminDtos.GroupRow;
import io.smarthappy.module.admin.AdminDtos.SupplierChoice;
import io.smarthappy.module.admin.AdminDtos.TempPassword;
import io.smarthappy.module.admin.AdminDtos.UserDetail;
import io.smarthappy.module.admin.AdminDtos.UserForm;
import io.smarthappy.module.admin.AdminDtos.UserRow;
import io.smarthappy.module.admin.AdminDtos.UserSearch;
import io.smarthappy.module.admin.AdminDtos.UserSort;
import io.smarthappy.module.admin.GrantGuard.Perms;
import io.smarthappy.platform.approval.ApprovalDelegateService;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.config.I18nConfig;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.security.MfaService;
import io.smarthappy.platform.security.PasswordPolicyService;
import lombok.RequiredArgsConstructor;

/**
 * 사용자 관리 (sys-user, docs/36 4.1·4.2).
 * - 비밀번호는 관리자가 정하지 않는다: 추가·초기화 때 서버가 임시 비밀번호를 만들어 응답으로 한 번만 보여 주고, 다음 로그인 때 변경을 강제
 * - 부여 상한: 대상 사용자·주고 빼는 그룹이 내 권한 안이어야 한다. 자기 소속·자기 사용 중지·잠금·초기화는 거부
 * - 마지막 관리자 보호: 회사 단위 잠금 뒤 변경 전후 관리자 수
 * - 삭제 없음(사용 중지). 아이디는 추가 뒤 바꾸지 않는다(감사·접속 로그의 행위자 글자)
 * - 협력사 포털 사용자(SUPPLIER, docs/42 5장): 추가 때 유형·소속 협력사(사용 중 구매·겸용 거래처)를 정하고 그 뒤 바꾸지 않는다.
 *   권한 그룹·부서에 넣지 않는다. 비밀번호 초기화·잠금·MFA 초기화는 회사 사용자와 같다
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    static final String PROG = "sys-user";
    static final Pattern LOGIN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._@-]{1,49}$");
    static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,}$");
    static final Set<String> LOCK_STATES = Set.of("NONE", "FAIL", "MANUAL");
    static final Set<String> USER_TYPES = Set.of(LoginUser.INTERNAL, LoginUser.SUPPLIER);
    static final List<String> DEFAULT_SORT = List.of("loginId,asc");
    /** 헷갈리는 글자(0 O o 1 l I) 뺀 영문 대소문자·숫자 */
    private static final String TEMP_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int TEMP_LEN = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");

    /** 목록 열 — 메일은 개인정보라 엑셀 없음(이 화면은 내보내기 자체가 없다) */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("loginId", "sysUser.col.loginId", 130).withSort().withPriority(1),
            ColumnSpec.text("userNm", "sysUser.col.userNm", 120).withSort().withPriority(1),
            ColumnSpec.code("userType", "sysUser.col.userType", "USER_TYPE", 90).withPriority(2),
            ColumnSpec.text("custNm", "sysUser.col.cust", 140).withPriority(2),
            ColumnSpec.text("deptNm", "sysUser.col.dept", 120),
            ColumnSpec.text("groupNames", "sysUser.col.groups", 220).withFlex().withMinWidth(120),
            ColumnSpec.code("lockState", "sysUser.col.lockState", "USER_LOCK_STATE", 100),
            // Y/N 열(YN)은 "사용/미사용" 으로 보여 뜻이 맞지 않는다 → 글자 열 + 화면 format("변경 대기"/빈칸)
            ColumnSpec.text("pwdChangeReqYn", "sysUser.col.pwdChangeReq", 110).withPriority(3),
            ColumnSpec.of("lastLoginAt", "sysUser.col.lastLoginAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(2),
            ColumnSpec.of("useYn", "sysUser.col.useYn", ColumnSpec.ColumnType.YN, 70).withSort(),
            ColumnSpec.of("updatedAt", "sysUser.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3),
            ColumnSpec.text("updatedBy", "sysUser.col.updatedBy", 100).withPriority(3));

    private final AdminMapper mapper;
    private final SettingsMapper settings;
    private final GrantGuard guard;
    private final PolicyService policies;
    private final PasswordEncoder encoder;
    private final PasswordPolicyService passwordPolicy;
    private final AccessLogService accessLog;
    private final InboxService inbox;
    private final MfaService mfa;
    private final ApprovalDelegateService delegates;

    public record Page(List<UserRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String keyword, String authGrpCd, String useYn, String lockState, Long deptId, String userType,
            Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, UserSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        String kw = blank(keyword) ? null : keyword.strip();
        if ((kw != null && kw.length() > 50) || (!blank(authGrpCd) && authGrpCd.length() > 30)
                || (!blank(useYn) && !useYn.equals("Y") && !useYn.equals("N"))
                || (!blank(lockState) && !LOCK_STATES.contains(lockState))
                || (!blank(userType) && !USER_TYPES.contains(userType))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        UserSearch q = new UserSearch(kw == null ? null : "%" + likeEscape(kw) + "%", blank(authGrpCd) ? null : authGrpCd,
                blank(useYn) ? null : useYn, blank(lockState) ? null : lockState, deptId,
                blank(userType) ? null : userType, pq);
        return new Page(mapper.findUserPage(q), pq.isWithTotal() ? mapper.countUsers(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public UserDetail detail(long userId) {
        UserRow row = mapper.findUser(userId);
        if (row == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        Perms mine = guard.mine();
        return new UserDetail(row, mapper.userGroupCds(userId), choices(mine), userId == guard.me().userId(),
                guard.ofUser(userId).within(mine));
    }

    /** 새 사용자 화면용 권한그룹 선택지 */
    @Transactional(readOnly = true)
    public List<GroupChoice> groupChoices() {
        return choices(guard.mine());
    }

    /** 협력사 사용자 소속 선택지 — 사용 중 구매·겸용 거래처(이름·코드 검색, 50건) */
    @Transactional(readOnly = true)
    public List<SupplierChoice> supplierChoices(String keyword) {
        String kw = blank(keyword) ? null : keyword.strip();
        if (kw != null && kw.length() > 50) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findSupplierChoices(kw == null ? null : "%" + likeEscape(kw) + "%");
    }

    private List<GroupChoice> choices(Perms mine) {
        List<GroupChoice> out = new ArrayList<>();
        for (GroupRow g : mapper.findGroups()) {
            out.add(new GroupChoice(g.getAuthGrpCd(), g.getAuthGrpNm(), g.getAdminYn(), g.getUseYn(),
                    guard.ofGroup(g.getAuthGrpCd(), g.getAdminYn()).within(mine)));
        }
        return out;
    }

    // ------------------------------------------------------------------ 저장

    @Transactional
    public TempPassword create(UserForm form) {
        LoginUser me = guard.me();
        inbox.claimRequestKey("SYS_USER_CREATE");
        Map<String, GroupRow> groups = groupMap();
        UserForm f = validate(form, true, groups);
        if (f.deptId() != null) {
            requireActiveDept(f.deptId());
        }
        if (LoginUser.SUPPLIER.equals(f.userType())) {
            requireSupplierCust(f.custId());
        }
        Perms mine = guard.mine();
        for (String g : f.authGrpCds()) {
            GroupRow row = groups.get(g);
            guard.requireWithin(guard.ofGroup(g, row.getAdminYn()), mine, "group=" + g);
        }
        String temp = tempPassword(me.compCd(), f.loginId());
        long id;
        try {
            id = mapper.insertUser(f, encoder.encode(temp), me.loginId());
        } catch (DuplicateKeyException e) {
            // 동시에 같은 아이디로 추가 — 먼저 들어간 쪽이 이긴다
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("loginId", "DUPLICATE").list());
        }
        for (String g : f.authGrpCds()) {
            mapper.insertUserAuth(id, g, me.loginId());
        }
        return new TempPassword(id, f.loginId(), temp, 0);
    }

    @Transactional
    public void update(long userId, UserForm form) {
        LoginUser me = guard.me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        UserRow cur = requireUser(userId);
        Map<String, GroupRow> groups = groupMap();
        UserForm f = validate(form, false, groups, cur);
        boolean self = userId == me.userId();
        Perms mine = guard.mine();
        guard.requireWithin(guard.ofUser(userId), mine, "user=" + userId);

        Set<String> before = new LinkedHashSet<>(mapper.userGroupCds(userId));
        Set<String> after = new LinkedHashSet<>(f.authGrpCds());
        Set<String> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        Set<String> removed = new LinkedHashSet<>(before);
        removed.removeAll(after);
        if (self && (!added.isEmpty() || !removed.isEmpty() || !"Y".equals(f.useYn()))) {
            guard.deny(HbErrorCode.SELF_CHANGE, "self user=" + userId);
        }
        for (String g : added) {
            guard.requireWithin(guard.ofGroup(g, groups.get(g).getAdminYn()), mine, "group=" + g);
        }
        for (String g : removed) {
            GroupRow row = groups.get(g);
            guard.requireWithin(guard.ofGroup(g, row == null ? "N" : row.getAdminYn()), mine, "group=" + g);
        }

        int admins = guard.lockAndCountAdmins();
        UserForm withId = new UserForm(cur.getLoginId(), f.userNm(), f.email(), f.localeCd(), f.useYn(), f.authGrpCds(),
                f.deptId(), form.rowVer(), cur.getUserType(), cur.getCustId());
        if (f.deptId() != null && !f.deptId().equals(cur.getDeptId())) {
            requireActiveDept(f.deptId());
        }
        if (mapper.updateUser(userId, withId, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        for (String g : removed) {
            mapper.deleteUserAuth(userId, g);
        }
        for (String g : added) {
            mapper.insertUserAuth(userId, g, me.loginId());
        }
        guard.requireAdminLeft(admins);
        if ("Y".equals(cur.getUseYn()) && "N".equals(f.useYn())) {
            delegates.notifyEndedByDisable(userId);   // 대리였던 위임은 트리거가 끝냈다 — 위임자에게 알림 (docs/36 16.6)
        }
    }

    /** 비밀번호 초기화 (FN1): 임시 비밀번호 + 변경 강제 + 실패 잠금 해제 + 기존 세션 끊기 */
    @Transactional
    public TempPassword resetPassword(long userId, Integer rowVer) {
        LoginUser me = guard.me();
        UserRow cur = manageable(userId, rowVer, me, "reset");
        String temp = tempPassword(me.compCd(), cur.getLoginId());
        if (mapper.resetPassword(userId, rowVer, encoder.encode(temp), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        accessLog.recordCurrent(AccessEvent.PASSWORD_RESET, PROG, "FN1", AccessLogService.Result.OK,
                "user=" + userId + ":" + cur.getLoginId());
        return new TempPassword(userId, cur.getLoginId(), temp, rowVer + 1);
    }

    /**
     * MFA 초기화 (FN1 — 비밀번호 초기화와 같은 헬프데스크 권한, docs/36 Q25). 부여 상한·자기 금지 그대로.
     * 그 사용자의 세션을 끊는다 → 다음 로그인 때 다시 등록(강제 대상이면). 등록하지 않은 사람은 409
     */
    @Transactional
    public void resetMfa(long userId, Integer rowVer) {
        LoginUser me = guard.me();
        UserRow cur = manageable(userId, rowVer, me, "reset-mfa");
        if (!mfa.reset(userId)) {
            throw new BusinessException(HbErrorCode.MFA_STATE);
        }
        if (mapper.revokeSessions(userId, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        accessLog.recordCurrent(AccessEvent.MFA_RESET, PROG, "FN1", AccessLogService.Result.OK,
                "user=" + userId + ":" + cur.getLoginId());
    }

    /** 관리자 수동 잠금 (FN2) — 자동 해제 없음, 그 사용자의 세션은 다음 요청에서 끊긴다 */
    @Transactional
    public void lock(long userId, Integer rowVer) {
        LoginUser me = guard.me();
        UserRow cur = manageable(userId, rowVer, me, "lock");
        int admins = guard.lockAndCountAdmins();
        if (mapper.lockUser(userId, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        guard.requireAdminLeft(admins);
        accessLog.recordCurrent(AccessEvent.USER_LOCKED, PROG, "FN2", AccessLogService.Result.OK,
                "user=" + userId + ":" + cur.getLoginId());
    }

    /** 잠금 해제 (FN2) — 실패 잠금·관리자 잠금 모두 */
    @Transactional
    public void unlock(long userId, Integer rowVer) {
        LoginUser me = guard.me();
        UserRow cur = manageable(userId, rowVer, me, "unlock");
        if (mapper.unlockUser(userId, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        accessLog.recordCurrent(AccessEvent.USER_UNLOCKED, PROG, "FN2", AccessLogService.Result.OK,
                "user=" + userId + ":" + cur.getLoginId());
    }

    /** 대상이 있고 내가 아니며 내 권한 안인 사용자 */
    private UserRow manageable(long userId, Integer rowVer, LoginUser me, String what) {
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        UserRow cur = requireUser(userId);
        if (userId == me.userId()) {
            guard.deny(HbErrorCode.SELF_CHANGE, what + " self");
        }
        guard.requireWithin(guard.ofUser(userId), guard.mine(), what + " user=" + userId);
        return cur;
    }

    /** 새 소속은 사용 중 부서만(이미 소속된 부서가 사용 중지돼도 사람은 그대로 — docs/36 Q10). 다른 회사 부서는 RLS 로 없음 */
    private void requireActiveDept(long deptId) {
        var d = settings.findDept(deptId);
        if (d == null || !"Y".equals(d.getUseYn())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(
                    FieldErrors.create().add("deptId", d == null ? "NO_SUCH_CODE" : "INACTIVE").list());
        }
    }

    /** 협력사 사용자 소속: 같은 회사(RLS)의 사용 중 구매·겸용 거래처 — 트리거도 같은 규칙으로 막는다 */
    private void requireSupplierCust(long custId) {
        SupplierChoice c = mapper.findSupplierCust(custId);
        String code = c == null ? "NO_SUCH_CODE" : "INACTIVE".equals(c.custType()) ? "INACTIVE"
                : !"PURCHASE".equals(c.custType()) && !"BOTH".equals(c.custType()) ? "NOT_SUPPLIER_CUST" : null;
        if (code != null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add("custId", code).list());
        }
    }

    private UserRow requireUser(long userId) {
        UserRow cur = mapper.findUser(userId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return cur;
    }

    private Map<String, GroupRow> groupMap() {
        Map<String, GroupRow> m = new HashMap<>();
        mapper.findGroups().forEach(g -> m.put(g.getAuthGrpCd(), g));
        return m;
    }

    // ------------------------------------------------------------------ 검증

    /** 폼 검증 — 오류를 모두 모은 뒤 400. 아이디는 추가 때만 본다 */
    UserForm validate(UserForm in, boolean create, Map<String, GroupRow> groups) {
        return validate(in, create, groups, null);
    }

    /**
     * cur = 수정 대상(추가면 null). 유형·소속 협력사는 추가 때만 정한다 — 수정에서 다른 값을 보내면 FIXED(조용히 무시하지 않는다).
     * 협력사 사용자는 권한 그룹·부서 없음(SUPPLIER_NO_GROUP) — 화면은 칸을 숨기지만 API 로 보내면 여기서 막는다
     */
    UserForm validate(UserForm in, boolean create, Map<String, GroupRow> groups, UserRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String loginId = null;
        if (create) {
            loginId = blank(in.loginId()) ? null : in.loginId().strip();
            if (loginId == null) {
                e.add("loginId", "REQUIRED");
            } else if (loginId.length() > 50) {
                e.add("loginId", "TOO_LONG", 50);
            } else if (!LOGIN_ID.matcher(loginId).matches()) {
                e.add("loginId", "FORMAT");
            } else if (mapper.existsLoginId(loginId)) {
                e.add("loginId", "DUPLICATE");
            }
        }
        String nm = text(in.userNm(), "userNm", 50, true, e);
        String email = text(in.email(), "email", 200, false, e);
        if (email != null && !EMAIL.matcher(email).matches()) {
            e.add("email", "FORMAT");
        }
        String locale = blank(in.localeCd()) ? "ko" : in.localeCd();
        if (!I18nConfig.isSupported(locale)) {
            e.add("localeCd", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!use.equals("Y") && !use.equals("N")) {
            e.add("useYn", "CODE");
        }
        List<String> grps = in.authGrpCds() == null ? List.of() : in.authGrpCds();
        if (grps.size() > 100 || grps.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Set<String> clean = new LinkedHashSet<>(grps);
        for (String g : clean) {
            GroupRow row = groups.get(g);
            if (row == null) {
                e.add("authGrpCds", "CODE");
                break;
            }
        }
        String type;
        Long custId;
        if (create) {
            type = blank(in.userType()) ? LoginUser.INTERNAL : in.userType();
            custId = in.custId();
            if (!USER_TYPES.contains(type)) {
                e.add("userType", "CODE");
            } else if (LoginUser.SUPPLIER.equals(type) && custId == null) {
                e.add("custId", "REQUIRED");
            } else if (LoginUser.INTERNAL.equals(type) && custId != null) {
                e.add("custId", "CODE");
            }
        } else {
            type = cur.getUserType();
            custId = cur.getCustId();
            if ((!blank(in.userType()) && !in.userType().equals(type))
                    || (in.custId() != null && !in.custId().equals(custId))) {
                e.add(!blank(in.userType()) && !in.userType().equals(type) ? "userType" : "custId", "FIXED");
            }
        }
        if (LoginUser.SUPPLIER.equals(type)) {
            if (!clean.isEmpty()) {
                e.add("authGrpCds", "SUPPLIER_NO_GROUP");
            }
            if (in.deptId() != null) {
                e.add("deptId", "SUPPLIER_NO_GROUP");
            }
        }
        e.throwIfAny();
        return new UserForm(loginId, nm, email, locale, use, List.copyOf(clean), in.deptId(), in.rowVer(), type, custId);
    }

    /** 회사 비밀번호 정책을 통과하는 임시 비밀번호 (조합 규칙이 켜진 회사도 대개 첫 번에 통과) */
    String tempPassword(String compCd, String loginId) {
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder sb = new StringBuilder(TEMP_LEN);
            for (int i = 0; i < TEMP_LEN; i++) {
                sb.append(TEMP_CHARS.charAt(RANDOM.nextInt(TEMP_CHARS.length())));
            }
            String pw = sb.toString();
            if (passwordPolicy.check(pw, compCd, loginId).isEmpty()) {
                return pw;
            }
        }
        throw new IllegalStateException("임시 비밀번호를 만들지 못했습니다 — 비밀번호 정책 확인");
    }

    private static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (CONTROL.matcher(s).find()) {
            e.add(field, "FORMAT");
        } else if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
