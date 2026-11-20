package io.smarthappy.module.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.admin.AdminDtos.GrantRow;
import io.smarthappy.module.admin.AdminDtos.GroupDetail;
import io.smarthappy.module.admin.AdminDtos.GroupForm;
import io.smarthappy.module.admin.AdminDtos.GroupPage;
import io.smarthappy.module.admin.AdminDtos.GroupRow;
import io.smarthappy.module.admin.AdminDtos.GroupSort;
import io.smarthappy.module.admin.AdminDtos.PermItem;
import io.smarthappy.module.admin.AdminDtos.PermRow;
import io.smarthappy.module.admin.AdminDtos.PermSave;
import io.smarthappy.module.admin.AdminDtos.ProgRow;
import io.smarthappy.module.admin.GrantGuard.Perms;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 권한그룹 관리 (sys-auth-group, docs/36 4.1).
 * - 그룹 정보·권한 표를 고치려면 그 그룹의 지금 권한과 새 권한이 모두 내 권한 안이어야 한다(나보다 큰 그룹은 손댈 수 없다)
 * - 관리자 표시(admin_yn)는 관리자만 켜고 끈다. 끄기·사용 중지로 관리자가 0명이 되면 409 LAST_ADMIN
 * - 권한 표는 "한 벌" 저장: 사용 중 화면의 액션 목록 전체를 받아 서버가 추가·삭제(그룹 row_ver 로 동시 편집 409)
 * - 그룹 삭제 없음(사용 중지). 코드는 만든 뒤 고정
 */
@Service
@RequiredArgsConstructor
public class AuthGroupAdminService {

    static final String PROG = "sys-auth-group";
    static final Pattern GRP_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,29}$");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");

    private final AdminMapper mapper;
    private final GrantGuard guard;
    private final MessageSource messages;
    private final PolicyService policies;

    @Transactional(readOnly = true)
    public List<GroupRow> list() {
        Perms mine = guard.mine();
        List<GroupRow> rows = mapper.findGroups();
        rows.forEach(g -> g.setEditable(guard.ofGroup(g.getAuthGrpCd(), g.getAdminYn()).within(mine)));
        return rows;
    }

    /** grid(UI 단계 3 ①, docs/36 17.1) — 검색어(코드·이름) + 서버 페이징·정렬(허용 목록 {@link GroupSort}). 고칠 수 있는가는 목록과 같게 */
    @Transactional(readOnly = true)
    public GroupPage grid(String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        String kw = AdminInputs.blank(keyword) ? null : keyword.strip();
        if (kw != null && kw.length() > 50) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String like = kw == null ? null : "%" + UserAdminService.likeEscape(kw) + "%";
        PageQuery pq = PageQuery.parse(page, size, sort, total, GroupSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("sortNo,asc", "authGrpCd,asc"));
        Perms mine = guard.mine();
        List<GroupRow> rows = mapper.findGroupPage(like, pq);
        rows.forEach(g -> g.setEditable(guard.ofGroup(g.getAuthGrpCd(), g.getAdminYn()).within(mine)));
        return new GroupPage(rows, pq.isWithTotal() ? mapper.countGroups(like) : null);
    }

    /** grid 열 — 화면 표와 같은 칸. 관리자 여부는 화면이 글자로("관리자") */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("authGrpCd", "sysAuthGroup.col.authGrpCd", 130).withSort().withPriority(1),
            ColumnSpec.text("authGrpNm", "sysAuthGroup.col.authGrpNm", 180).withSort().withFlex().withPriority(1).withMinWidth(100),
            ColumnSpec.text("adminYn", "sysAuthGroup.col.adminYn", 80).withPriority(3),
            ColumnSpec.of("memberCnt", "sysAuthGroup.col.memberCnt", ColumnType.INT, 80).withSort(),
            ColumnSpec.of("useYn", "sysAuthGroup.col.useYn", ColumnType.YN, 80).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public boolean iAmAdmin() {
        return guard.mine().admin();
    }

    @Transactional(readOnly = true)
    public GroupDetail detail(String authGrpCd, Locale locale) {
        GroupRow g = requireGroup(authGrpCd);
        Perms mine = guard.mine();
        g.setEditable(guard.ofGroup(g.getAuthGrpCd(), g.getAdminYn()).within(mine));
        Map<String, Set<String>> granted = new HashMap<>();
        for (GrantRow r : mapper.groupRows(authGrpCd)) {
            granted.computeIfAbsent(r.getProgCd(), k -> new HashSet<>()).add(r.getActionCd());
        }
        List<PermRow> perms = new ArrayList<>();
        for (ProgRow p : mapper.findProgs()) {
            List<String> actions = p.actionList();
            Set<String> has = granted.getOrDefault(p.getProgCd(), Set.of());
            perms.add(new PermRow(p.getProgCd(), messages.getMessage(p.getProgNmKey(), null, p.getProgCd(), locale),
                    p.getModuleCd(), actions, actions.stream().filter(has::contains).toList(),
                    actions.stream().filter(a -> mine.admin() || mine.keys().contains(p.getProgCd() + ":" + a)).toList()));
        }
        return new GroupDetail(g, perms, mapper.groupMembers(authGrpCd));
    }

    @Transactional
    public String create(GroupForm form) {
        LoginUser me = guard.me();
        GroupForm f = validate(form, true);
        if ("Y".equals(f.adminYn()) && !guard.mine().admin()) {
            guard.deny(HbErrorCode.GRANT_EXCEEDS, "admin group create");
        }
        try {
            mapper.insertGroup(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("authGrpCd", "DUPLICATE").list());
        }
        return f.authGrpCd();
    }

    @Transactional
    public void update(String authGrpCd, GroupForm form) {
        LoginUser me = guard.me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        GroupRow cur = requireGroup(authGrpCd);
        GroupForm f = validate(new GroupForm(authGrpCd, form.authGrpNm(), form.sortNo(), form.homeProgCd(),
                form.adminYn(), form.useYn(), form.rowVer()), false);
        Perms mine = guard.mine();
        guard.requireWithin(guard.ofGroup(authGrpCd, cur.getAdminYn()), mine, "group=" + authGrpCd);
        if (!f.adminYn().equals(cur.getAdminYn()) && !mine.admin()) {
            guard.deny(HbErrorCode.GRANT_EXCEEDS, "admin flag group=" + authGrpCd);
        }
        int admins = guard.lockAndCountAdmins();
        if (mapper.updateGroup(f, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        guard.requireAdminLeft(admins);
    }

    /** 권한 표 한 벌 저장 — 반환: 새 row_ver */
    @Transactional
    public int savePerms(String authGrpCd, Integer rowVer, PermSave save) {
        LoginUser me = guard.me();
        if (rowVer == null || save == null || save.items() == null || save.items().size() > 2000
                || save.items().stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        GroupRow cur = requireGroup(authGrpCd);
        Map<String, ProgRow> progs = new HashMap<>();
        mapper.findProgs().forEach(p -> progs.put(p.getProgCd(), p));

        // 새 표: 사용 중 화면 + 그 화면이 정의한 액션만 (아니면 400 — 정상 화면은 보낼 수 없다)
        Set<String> next = new LinkedHashSet<>();
        Set<String> seenProgs = new HashSet<>();
        for (PermItem it : save.items()) {
            ProgRow p = it.progCd() == null ? null : progs.get(it.progCd());
            if (p == null || !seenProgs.add(it.progCd()) || it.actions() == null
                    || it.actions().stream().anyMatch(a -> a == null || !p.actionList().contains(a))) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            it.actions().forEach(a -> next.add(p.getProgCd() + ":" + a));
        }
        Perms mine = guard.mine();
        guard.requireWithin(guard.ofGroup(authGrpCd, cur.getAdminYn()), mine, "perms group=" + authGrpCd);
        guard.requireWithin(new Perms(false, next), mine, "perms new group=" + authGrpCd);

        // 그룹을 잠근다(버전 확인 + 증가) — 두 사람이 같은 그룹 표를 동시에 고치면 뒤쪽이 409
        if (mapper.touchGroup(authGrpCd, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        Set<String> existing = new HashSet<>();
        for (GrantRow r : mapper.groupRows(authGrpCd)) {
            String key = r.getProgCd() + ":" + r.getActionCd();
            // 사용 중지 화면의 행은 표에 없으므로 그대로 둔다(화면이 다시 켜지면 원래대로)
            if (!progs.containsKey(r.getProgCd())) {
                continue;
            }
            existing.add(key);
            if (!next.contains(key)) {
                mapper.deleteGrant(authGrpCd, r.getProgCd(), r.getActionCd());
            }
        }
        for (String key : next) {
            if (!existing.contains(key)) {
                int i = key.indexOf(':');
                mapper.insertGrant(authGrpCd, key.substring(0, i), key.substring(i + 1), me.loginId());
            }
        }
        return rowVer + 1;
    }

    private GroupRow requireGroup(String authGrpCd) {
        GroupRow g = authGrpCd == null || authGrpCd.length() > 30 ? null : mapper.findGroup(authGrpCd);
        if (g == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return g;
    }

    GroupForm validate(GroupForm in, boolean create) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = blank(in.authGrpCd()) ? null : in.authGrpCd().strip().toUpperCase(Locale.ROOT);
        if (create) {
            if (cd == null) {
                e.add("authGrpCd", "REQUIRED");
            } else if (cd.length() > 30) {
                e.add("authGrpCd", "TOO_LONG", 30);
            } else if (!GRP_CD.matcher(cd).matches()) {
                e.add("authGrpCd", "FORMAT");
            } else if (mapper.findGroup(cd) != null) {
                e.add("authGrpCd", "DUPLICATE");
            }
        }
        String nm = null;
        if (blank(in.authGrpNm())) {
            e.add("authGrpNm", "REQUIRED");
        } else {
            nm = in.authGrpNm().strip();
            if (CONTROL.matcher(nm).find()) {
                e.add("authGrpNm", "FORMAT");
            } else if (nm.length() > 100) {
                e.add("authGrpNm", "TOO_LONG", 100);
            }
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "FORMAT");
        }
        String home = blank(in.homeProgCd()) ? null : in.homeProgCd();
        if (home != null && mapper.findProgs().stream().noneMatch(p -> p.getProgCd().equals(home))) {
            e.add("homeProgCd", "NO_SUCH_CODE");
        }
        String admin = blank(in.adminYn()) ? "N" : in.adminYn();
        if (!admin.equals("Y") && !admin.equals("N")) {
            e.add("adminYn", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!use.equals("Y") && !use.equals("N")) {
            e.add("useYn", "CODE");
        }
        e.throwIfAny();
        return new GroupForm(cd, nm, sort, home, admin, use, in.rowVer());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
