package io.smarthappy.module.admin;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import io.smarthappy.module.admin.AdminDtos.UserForm;
import io.smarthappy.module.admin.GrantGuard.Perms;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.importer.ImportCodes;
import io.smarthappy.platform.importer.ImportTemplate;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 사용자 엑셀 올리기 (시스템관리 4b, docs/36 Q26) — 사용자 관리(sys-user) SAVE.
 * <ul>
 * <li><b>비밀번호 칸 없음.</b> 새 사용자는 아무도 모르는 무작위 비밀번호 + 다음 로그인 때 변경으로 만든다 —
 *     넘겨줄 때 화면의 "비밀번호 초기화" 로 임시 비밀번호를 한 번 받는다(결과 파일에 비밀번호를 싣지 않는다).</li>
 * <li>권한그룹 열은 <b>새 사용자에게만</b> — 기존 사용자의 소속 변경은 화면에서(마지막 관리자·자기 소속 규칙이 한 곳에).
 *     줄 수 있는 그룹은 부여 상한 안(GRANT_EXCEEDS).</li>
 * <li>기존 사용자 갱신(이름·메일·부서·언어·사용)도 부여 상한 안, 자기 사용 중지 금지, 마지막 관리자 0명이면 전체 롤백.</li>
 * <li><b>실행 때 다시 검사</b> — 올린 뒤 실행 전에 그룹이 관리자로 바뀌거나, 올린 사람 권한이 줄거나, 대상이 관리자 그룹을 받을 수 있다
 *     (소속 변경은 sys_user.row_ver 를 올리지 않아 버전 확인으로 못 잡는다). 실행 세션은 open() 을 새로 불러 지금 권한·그룹으로
 *     행마다 부여 상한·자기 사용 중지를 다시 보고, 어긋나면 실행 전체 롤백(403).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class UserImportTemplate implements ImportTemplate {

    static final String CODE = "SYS_USER";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("loginId", "sysUser.col.loginId", true, "help.sys-user.loginId", null, "kim.mj").asText(),
            new ImportColumn("userNm", "sysUser.col.userNm", true, null, null, "김민지"),
            new ImportColumn("email", "sysUser.col.email", false, null, null, "minji@example.com"),
            new ImportColumn("deptCd", "sysUser.import.deptCd", false, "import.guide.sysUserDept", null, "PRD-1").asText(),
            new ImportColumn("localeCd", "sysUser.col.localeCd", false, "import.guide.sysUserLocale", null, "ko"),
            new ImportColumn("authGrpCds", "sysUser.import.authGrpCds", false, "import.guide.sysUserGroups", null, "WORK").asText(),
            new ImportColumn("useYn", "sysUser.col.useYn", false, "import.guide.yn", null, "Y"));

    private final AdminMapper mapper;
    private final GrantGuard guard;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final io.smarthappy.platform.approval.ApprovalDelegateService delegates;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String progCd() {
        return UserAdminService.PROG;
    }

    @Override
    public String titleKey() {
        return "prog.sysUser";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    private record Dept(long id, String useYn) {
    }

    private record Group(String adminYn, String useYn) {
    }

    @Override
    public ImportSession open() {
        LoginUser me = guard.me();
        Perms mine = guard.mine();
        Map<String, Dept> depts = new HashMap<>();
        Map<Long, String> deptCdById = new HashMap<>();
        jdbc.query("select dept_id, dept_cd, use_yn from sys_dept", rs -> {
            depts.put(rs.getString(2), new Dept(rs.getLong(1), rs.getString(3)));
            deptCdById.put(rs.getLong(1), rs.getString(2));
        });
        Map<String, Group> groups = new HashMap<>();
        jdbc.query("select auth_grp_cd, admin_yn, use_yn from sys_auth_group",
                rs -> { groups.put(rs.getString(1), new Group(rs.getString(2), rs.getString(3))); });
        Map<String, Existing> existing = new HashMap<>();
        // 협력사 포털 사용자는 엑셀로 바꾸지 않는다 — 화면에서 한 명씩(거래처 확인을 사람이, docs/42 5장)
        Set<String> suppliers = new HashSet<>();
        jdbc.query("select user_id, row_ver, login_id, user_nm, email, dept_id, locale_cd, use_yn, user_type from sys_user", rs -> {
            if (!LoginUser.INTERNAL.equals(rs.getString(9))) {
                suppliers.add(rs.getString(3).toLowerCase(Locale.ROOT));
                return;
            }
            Map<String, String> v = new LinkedHashMap<>();
            v.put("loginId", rs.getString(3));
            v.put("userNm", rs.getString(4));
            v.put("email", rs.getString(5));
            long dept = rs.getLong(6);
            v.put("deptCd", rs.wasNull() ? null : deptCdById.get(dept));
            v.put("localeCd", rs.getString(7));
            v.put("authGrpCds", null);
            v.put("useYn", rs.getString(8));
            existing.put(rs.getString(3).toLowerCase(Locale.ROOT), new Existing(rs.getLong(1), rs.getInt(2), v));
        });
        Map<Long, Boolean> withinCache = new HashMap<>();

        return new ImportSession() {
            @Override
            public Existing find(String key) {
                return key == null ? null : existing.get(key);
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                FieldErrors e = FieldErrors.create();
                String loginId = AdminInputs.blank(cells.get("loginId")) ? null : cells.get("loginId").strip();
                if (loginId == null) {
                    e.add("loginId", "REQUIRED");
                } else if (!UserAdminService.LOGIN_ID.matcher(loginId).matches()) {
                    e.add("loginId", "FORMAT");
                }
                String key = loginId == null || !e.list().isEmpty() ? null : loginId.toLowerCase(Locale.ROOT);
                if (key != null && suppliers.contains(key)) {
                    e.add("loginId", "SUPPLIER_USER");
                    key = null;
                }
                Existing cur = key == null ? null : existing.get(key);
                String nm = AdminInputs.text(cells.get("userNm"), "userNm", 50, true, e);
                String email = AdminInputs.text(cells.get("email"), "email", 200, false, e);
                if (email != null && !UserAdminService.EMAIL.matcher(email).matches()) {
                    e.add("email", "FORMAT");
                }
                String deptCd = AdminInputs.blank(cells.get("deptCd")) ? null : cells.get("deptCd").strip().toUpperCase(Locale.ROOT);
                if (deptCd != null) {
                    Dept d = depts.get(deptCd);
                    if (d == null) {
                        e.add("deptCd", "NO_SUCH_CODE");
                    } else if (!"Y".equals(d.useYn()) && (cur == null || !deptCd.equals(cur.values().get("deptCd")))) {
                        e.add("deptCd", "INACTIVE");   // 새 소속만 막는다 — 이미 그 부서인 사람은 그대로(Q10)
                    }
                }
                // 빈 칸 = 새 행은 기본값, 기존 행은 지금 값 유지(엔진은 채운 칸만 비교·반영)
                String locale = AdminInputs.blank(cells.get("localeCd")) ? null : cells.get("localeCd").strip().toLowerCase(Locale.ROOT);
                if (locale != null && !locale.equals("ko") && !locale.equals("en")) {
                    e.add("localeCd", "CODE");
                }
                String use = ImportCodes.yn(cells.get("useYn"));
                if (use != null && !use.equals("Y") && !use.equals("N")) {
                    e.add("useYn", "CODE");
                }
                String grpCsv = null;
                if (!AdminInputs.blank(cells.get("authGrpCds"))) {
                    if (cur != null) {
                        e.add("authGrpCds", "NEW_ONLY");
                    } else {
                        List<String> list = new ArrayList<>();
                        for (String g : cells.get("authGrpCds").split("[,;\\s]+")) {
                            String cd = g.strip().toUpperCase(Locale.ROOT);
                            if (cd.isEmpty() || list.contains(cd)) {
                                continue;
                            }
                            Group gr = groups.get(cd);
                            if (gr == null || !"Y".equals(gr.useYn())) {
                                e.add("authGrpCds", "NO_SUCH_CODE");
                            } else if (!guard.ofGroup(cd, gr.adminYn()).within(mine)) {
                                e.add("authGrpCds", "GRANT_EXCEEDS");
                            }
                            list.add(cd);
                        }
                        grpCsv = list.isEmpty() ? null : String.join(",", list);
                    }
                }
                if (cur != null) {
                    long id = cur.id();
                    if (!withinCache.computeIfAbsent(id, x -> guard.ofUser(x).within(mine))) {
                        e.add("loginId", "GRANT_EXCEEDS");   // 나보다 큰 사람은 바꿀 수 없다(부여 상한)
                    }
                    if (id == me.userId() && "N".equals(use)) {
                        e.add("useYn", "SELF_CHANGE");
                    }
                }
                Map<String, String> v = new LinkedHashMap<>();
                v.put("loginId", cur == null ? loginId : cur.values().get("loginId"));   // 기존 아이디 표기는 바꾸지 않는다
                v.put("userNm", nm);
                v.put("email", email);
                v.put("deptCd", deptCd);
                v.put("localeCd", locale);
                v.put("authGrpCds", grpCsv);
                v.put("useYn", use);
                return new Validated(key, v, e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                // 실행 때 다시: 지금 그룹 상태·지금 내 권한으로 부여 상한(검증 뒤 바뀌었을 수 있다)
                if (v.get("authGrpCds") != null) {
                    for (String g : v.get("authGrpCds").split(",")) {
                        Group gr = groups.get(g);
                        if (gr == null || !"Y".equals(gr.useYn())) {
                            throw new BusinessException(
                                    HbErrorCode.IMPORT_STALE, 0);
                        }
                        guard.requireWithin(guard.ofGroup(g, gr.adminYn()), mine, "import group=" + g);
                    }
                }
                // 아무도 모르는 비밀번호 + 다음 로그인 때 변경 — 넘겨줄 때 화면에서 비밀번호 초기화(Q26)
                byte[] b = new byte[24];
                RANDOM.nextBytes(b);
                long id = mapper.insertUser(form(v, null), encoder.encode(java.util.Base64.getEncoder().encodeToString(b)), by);
                if (v.get("authGrpCds") != null) {
                    for (String g : Arrays.asList(v.get("authGrpCds").split(","))) {
                        mapper.insertUserAuth(id, g, by);
                    }
                }
                return id;
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                // 실행 때 다시: 대상의 지금 소속(검증 뒤 관리자 그룹을 받았을 수 있다)·지금 내 권한, 자기 사용 중지
                guard.requireWithin(guard.ofUser(id), mine, "import user=" + id);
                if (id == me.userId() && "N".equals(v.get("useYn"))) {
                    guard.deny(HbErrorCode.SELF_CHANGE, "import self user=" + id);
                }
                // 사용 중지가 관리자를 0명으로 만들면 실행 전체를 되돌린다(마지막 관리자)
                int admins = "N".equals(v.get("useYn")) ? guard.lockAndCountAdmins() : 0;
                int n = mapper.updateUser(id, form(v, rowVer), by);
                guard.requireAdminLeft(admins);
                if (n > 0 && "N".equals(v.get("useYn"))) {
                    delegates.notifyEndedByDisable(id);   // 대리였던 위임은 트리거가 끝냈다(이미 N 이던 행은 0건) — docs/36 16.6
                }
                return n;
            }

            @Override
            public long count() {
                Long n = jdbc.queryForObject("select count(*) from sys_user", Long.class);
                return n == null ? 0 : n;
            }

            private UserForm form(Map<String, String> v, Integer rowVer) {
                Dept d = v.get("deptCd") == null ? null : depts.get(v.get("deptCd"));
                return new UserForm(v.get("loginId"), v.get("userNm"), v.get("email"),
                        v.get("localeCd") == null ? "ko" : v.get("localeCd"),
                        v.get("useYn") == null ? "Y" : v.get("useYn"), List.of(), d == null ? null : d.id(), rowVer);
            }
        };
    }
}
