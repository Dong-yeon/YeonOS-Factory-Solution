package io.smarthappy.module.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.admin.AdminDtos.MenuForm;
import io.smarthappy.module.admin.AdminDtos.MenuNode;
import io.smarthappy.module.admin.AdminDtos.MenuRow;
import io.smarthappy.module.admin.AdminDtos.ProgChoice;
import io.smarthappy.module.admin.AdminDtos.ProgRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 메뉴 관리 (sys-menu, docs/36 4.3). 회사 메뉴 트리 — 폴더(prog_cd 없음)와 화면 메뉴.
 * - 폴더·화면 구분은 만든 뒤 고정(폴더를 화면으로 바꾸면 자식이 사라진다). 화면 메뉴는 다른 화면으로 바꿀 수 있다
 * - 부모는 같은 회사 폴더, 순환·깊이(5) 금지 — 트리거 hb_check_menu_tree 가 최종(직접 SQL 포함), 서비스는 제약 → 칸 오류
 * - 지우지 않고 사용 안 함. 화면이 보이는지는 여전히 사용자의 조회 권한으로 정해진다(메뉴는 배치일 뿐)
 * 권한 판정과 무관한 배치 정보라 부여 상한은 적용하지 않는다(SAVE 권한만).
 */
@Service
@RequiredArgsConstructor
public class MenuAdminService {

    static final String PROG = "sys-menu";
    static final int MAX_DEPTH = 5;
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");

    /** 트리거 제약 → 칸 오류 */
    static final Map<String, String[]> CONSTRAINT_FIELD = Map.of(
            "ck_sys_menu_cycle", new String[] {"parentId", "MENU_CYCLE"},
            "ck_sys_menu_not_self", new String[] {"parentId", "MENU_CYCLE"},
            "ck_sys_menu_depth", new String[] {"parentId", "MENU_DEPTH"},
            "ck_sys_menu_parent_folder", new String[] {"parentId", "MENU_PARENT"},
            "fk_sys_menu_parent_comp", new String[] {"parentId", "NO_SUCH_CODE"},
            "ck_sys_menu_has_children", new String[] {"progCd", "MENU_HAS_CHILDREN"});

    /** 트리 열 (hb.tree — 이름이 트리 칸) */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("name", "sysMenu.col.name", 260).withFlex().withMinWidth(160),
            ColumnSpec.text("kindNm", "sysMenu.col.kind", 70),
            ColumnSpec.text("progCd", "sysMenu.col.progCd", 140),
            ColumnSpec.of("sortNo", "sysMenu.col.sortNo", ColumnSpec.ColumnType.INT, 70),
            ColumnSpec.of("useYn", "sysMenu.col.useYn", ColumnSpec.ColumnType.YN, 70));

    private final AdminMapper mapper;
    private final GrantGuard guard;
    private final MessageSource messages;

    /** 트리 전체(사용 안 함 포함) — 경로 순서 평면 행 + 레벨. 루트에서 닿지 않는 메뉴(옛 데이터)는 끝에 1레벨로 */
    @Transactional(readOnly = true)
    public List<MenuNode> tree(Locale locale) {
        List<MenuRow> rows = mapper.findMenus();
        Map<Long, List<MenuRow>> byParent = new HashMap<>();
        for (MenuRow r : rows) {
            byParent.computeIfAbsent(r.getParentId() == null ? 0L : r.getParentId(), k -> new ArrayList<>()).add(r);
        }
        List<MenuNode> out = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        walk(0L, 1, false, byParent, visited, out, locale);
        for (MenuRow r : rows) {
            if (!visited.contains(r.getMenuId())) {
                visited.add(r.getMenuId());
                out.add(node(r, 1, true, locale));
            }
        }
        return out;
    }

    private void walk(long parentId, int lvl, boolean hiddenAbove, Map<Long, List<MenuRow>> byParent, Set<Long> visited,
            List<MenuNode> out, Locale locale) {
        for (MenuRow r : byParent.getOrDefault(parentId, List.of())) {
            if (!visited.add(r.getMenuId())) {
                continue;
            }
            boolean hidden = hiddenAbove || !"Y".equals(r.getUseYn()) || lvl > MAX_DEPTH
                    || (r.getProgCd() != null && !"Y".equals(r.getProgUseYn()));
            out.add(node(r, lvl, hidden, locale));
            walk(r.getMenuId(), lvl + 1, hidden, byParent, visited, out, locale);
        }
    }

    private MenuNode node(MenuRow r, int lvl, boolean dim, Locale locale) {
        return new MenuNode(r.getMenuId(), r.getParentId(), lvl, name(r, locale), r.getMenuNm(), r.getMenuNmKey(),
                r.getProgCd(), r.getSortNo(), r.getUseYn(), r.getProgCd() == null ? "FOLDER" : "PROG",
                messages.getMessage(r.getProgCd() == null ? "sysMenu.kind.FOLDER" : "sysMenu.kind.PROG", null, locale),
                dim, r.getRowVer());
    }

    private String name(MenuRow r, Locale locale) {
        if (r.getMenuNm() != null) {
            return r.getMenuNm();
        }
        String key = r.getMenuNmKey() != null ? r.getMenuNmKey() : r.getProgNmKey();
        return key == null ? "?" : messages.getMessage(key, null, key, locale);
    }

    @Transactional(readOnly = true)
    public List<ProgChoice> progs(Locale locale) {
        return mapper.findProgs().stream().map(p -> new ProgChoice(p.getProgCd(),
                messages.getMessage(p.getProgNmKey(), null, p.getProgCd(), locale), p.getModuleCd())).toList();
    }

    @Transactional
    public long create(MenuForm form) {
        LoginUser me = guard.me();
        MenuForm f = validate(form, null);
        try {
            return mapper.insertMenu(f, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw fieldError(e);
        }
    }

    @Transactional
    public void update(long menuId, MenuForm form) {
        LoginUser me = guard.me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        MenuRow cur = mapper.findMenu(menuId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        MenuForm f = validate(form, cur);
        int n;
        try {
            n = mapper.updateMenu(menuId, f, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw fieldError(e);
        }
        if (n == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private static BusinessException fieldError(DataIntegrityViolationException e) {
        String[] fc = PgErrors.constraint(e).map(CONSTRAINT_FIELD::get).orElse(null);
        if (fc == null) {
            throw e;
        }
        return new BusinessException(HbErrorCode.INVALID_VALUE)
                .withFieldErrors(FieldErrors.create().add(fc[0], fc[1]).list());
    }

    /** cur = null 이면 추가. 폴더·화면 구분은 추가 때 progCd 유무로 정해지고 이후 고정 */
    MenuForm validate(MenuForm in, MenuRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String prog = in.progCd() == null || in.progCd().isBlank() ? null : in.progCd();
        boolean folder = cur == null ? prog == null : cur.getProgCd() == null;
        if (folder && prog != null) {
            e.add("progCd", "FIXED");
        } else if (!folder && prog == null) {
            e.add("progCd", "REQUIRED");
        } else if (prog != null && mapper.findProgs().stream().map(ProgRow::getProgCd).noneMatch(prog::equals)
                && (cur == null || !prog.equals(cur.getProgCd()))) {
            // 사용 중 화면만 새로 걸 수 있다(이미 걸린 사용 중지 화면은 그대로 둘 수 있음)
            e.add("progCd", "NO_SUCH_CODE");
        }
        String nm = null;
        if (in.menuNm() != null && !in.menuNm().isBlank()) {
            nm = in.menuNm().strip();
            if (CONTROL.matcher(nm).find()) {
                e.add("menuNm", "FORMAT");
            } else if (nm.length() > 100) {
                e.add("menuNm", "TOO_LONG", 100);
            }
        } else if (folder && (cur == null || cur.getMenuNmKey() == null)) {
            e.add("menuNm", "REQUIRED");   // 이름 키 없는 폴더는 이름이 있어야 한다(ck_sys_menu_name)
        }
        Long parent = in.parentId();
        if (parent != null) {
            MenuRow p = mapper.findMenu(parent);   // 다른 회사 메뉴는 RLS 로 안 보인다 → 없는 것
            if (p == null) {
                e.add("parentId", "NO_SUCH_CODE");
            } else if (p.getProgCd() != null) {
                e.add("parentId", "MENU_PARENT");
            } else if (cur != null && parent == cur.getMenuId()) {
                e.add("parentId", "MENU_CYCLE");
            }
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "FORMAT");
        }
        String use = in.useYn() == null || in.useYn().isBlank() ? "Y" : in.useYn();
        if (!use.equals("Y") && !use.equals("N")) {
            e.add("useYn", "CODE");
        }
        e.throwIfAny();
        return new MenuForm(parent, nm, prog, sort, use, in.rowVer());
    }
}
