package io.smarthappy.platform.security;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.platform.module.CompModuleService;
import io.smarthappy.platform.security.DefaultMenuTree.Node;

/**
 * 시더 공용(local 개발 계정 · demo 공개 데모 계정): 회사·공장·ADMIN 권한그룹·메뉴가 없으면 만든다.
 * 반드시 TenantRunner.run(compCd, …) 안에서 부른다(회사 범위·트랜잭션). 실제 회사 생성은 운영자 콘솔이 담당한다.
 * actor 는 created_by 에 남는 시더 이름(DEVSEED·DEMOSEED).
 *
 * 메뉴 모양은 {@link DefaultMenuTree} 한 벌 — 새 화면은 그 트리에 넣는다(docs/75).
 */
public final class DevCompanySeed {

    private DevCompanySeed() {
    }

    /** 회사 + 공장 P1 */
    public static void ensureCompany(JdbcTemplate jdbc, String compCd, String actor) {
        jdbc.update("""
                insert into sys_comp (comp_cd, comp_nm, created_by, updated_by)
                values (?, '데모 회사', ?, ?) on conflict (comp_cd) do nothing""", compCd, actor, actor);
        jdbc.update("""
                insert into sys_plant (comp_cd, plant_cd, plant_nm, created_by, updated_by)
                values (?, 'P1', '데모 1공장', ?, ?) on conflict do nothing""", compCd, actor, actor);
    }

    /**
     * 사용 모듈 기본(docs/80 6장) — 회사에 모듈 행이 하나도 없을 때만 핵심(기준정보·생산·품질·창고·설비) + 구매를 켜고 나머지는 끈다.
     * 관리자가 사용 모듈 화면에서 바꾼 값은 두 번째 기동부터 그대로 둔다. 운영 콘솔의 새 회사는 운영자가 고른다(기본 전부).
     */
    public static void ensureModules(JdbcTemplate jdbc, String compCd, String actor) {
        CompModuleService.seedDefaults(jdbc, compCd, actor);
    }

    /** 권한그룹 ADMIN(모든 프로그램의 모든 액션) + 기본 메뉴 트리 + ADMIN 홈 */
    public static void ensureAdminGroupAndMenus(JdbcTemplate jdbc, String compCd, String actor) {
        jdbc.update("""
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, admin_yn, created_by, updated_by)
                values (?, 'ADMIN', '관리자', 'Y', ?, ?) on conflict do nothing""", compCd, actor, actor);
        jdbc.update("""
                insert into sys_auth_prog (comp_cd, auth_grp_cd, prog_cd, action_cd, created_by)
                select ?, 'ADMIN', p.prog_cd, a, ? from sys_prog p, unnest(p.actions) a
                on conflict do nothing""", compCd, actor);
        ensureMenuTree(jdbc, compCd, actor);
        // 홈 (docs/37): ADMIN 역할별 홈 — 비어 있을 때만(사용자가 바꾼 값은 둔다)
        jdbc.update("""
                update sys_auth_group set home_prog_cd = 'home-main', updated_by = ?
                 where comp_cd = ? and auth_grp_cd = 'ADMIN' and home_prog_cd is null""", actor, compCd);
    }

    /**
     * 기본 메뉴 트리를 회사에 맞춘다(docs/75 3장). 몇 번을 돌려도 같은 결과.
     *
     * - 없는 폴더·화면은 만든다. 화면 본 자리는 그 화면 메뉴가 회사에 하나도 없을 때만 새로 만든다(예전 시드와 같은 규칙).
     * - **시드가 만든 뒤 아무도 손대지 않은 행**(row_ver = 0, created_by = updated_by = actor)만 트리 자리로 옮긴다.
     *   메뉴 관리(sys-menu)에서 고친 행은 row_ver 가 올라가므로 그대로 둔다 — 회사가 바꾼 메뉴를 시드가 되돌리지 않는다.
     * - 시드가 만든 빈 폴더 중 트리에 없는 이름은 지운다(옛 폴더 정리).
     * - 메뉴에서 뺀 화면({@link DefaultMenuTree#NOT_IN_MENU})의 메뉴 행도 시드가 만든 그대로인 것만 지운다(docs/79 Q6).
     * - sys_prog 에 없는 화면은 건너뛴다(파생 설치본·부분 설치 보호).
     */
    public static void ensureMenuTree(JdbcTemplate jdbc, String compCd, String actor) {
        Set<String> progs = new HashSet<>(jdbc.queryForList("select prog_cd from sys_prog", String.class));
        Map<String, Long> folders = new HashMap<>();
        placeFolders(jdbc, compCd, actor, DefaultMenuTree.ROOTS, null, folders);
        // 화면마다 트리 안 자리(본 자리 + 바로가기)의 부모 — 그 밖에 있는 행만 "옮길 수 있는 행"
        Map<String, Set<Long>> targets = new HashMap<>();
        collectTargets(DefaultMenuTree.ROOTS, null, folders, targets);
        placeProgs(jdbc, compCd, actor, DefaultMenuTree.ROOTS, null, folders, targets, progs, false);
        placeProgs(jdbc, compCd, actor, DefaultMenuTree.ROOTS, null, folders, targets, progs, true);
        // 메뉴에서 뺀 화면(합치기·숨김, docs/79 Q6): 시드가 만든 뒤 손대지 않은 행만 지운다 — 회사가 고친 행(row_ver > 0)은 그대로.
        // 트리에도 없고 NOT_IN_MENU 에도 없는 화면(파생 설치본이 덧붙인 화면 등)은 건드리지 않는다. 비게 된 폴더는 아래에서 정리된다
        jdbc.update("""
                delete from sys_menu m
                 where m.comp_cd = ? and m.prog_cd = any (?) and m.row_ver = 0 and m.created_by = ? and m.updated_by = ?
                   and not exists (select 1 from sys_menu c where c.parent_id = m.menu_id)""",
                compCd, DefaultMenuTree.NOT_IN_MENU.toArray(String[]::new), actor, actor);
        String[] keys = DefaultMenuTree.folderKeys().toArray(String[]::new);
        jdbc.update("""
                delete from sys_menu f
                 where f.comp_cd = ? and f.prog_cd is null and f.row_ver = 0 and f.created_by = ? and f.updated_by = ?
                   and f.menu_nm_key is not null and not (f.menu_nm_key = any (?))
                   and not exists (select 1 from sys_menu c where c.parent_id = f.menu_id)""",
                compCd, actor, actor, keys);
    }

    private static void placeFolders(JdbcTemplate jdbc, String compCd, String actor, List<Node> nodes, Long parentId,
            Map<String, Long> folders) {
        for (Node n : nodes) {
            if (!n.folder()) {
                continue;
            }
            List<Long> found = jdbc.queryForList("""
                    select menu_id from sys_menu where comp_cd = ? and prog_cd is null and menu_nm_key = ?
                     order by menu_id limit 1""", Long.class, compCd, n.key());
            Long id;
            if (found.isEmpty()) {
                id = jdbc.queryForObject("""
                        insert into sys_menu (comp_cd, parent_id, menu_nm_key, sort_no, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?) returning menu_id""", Long.class,
                        compCd, parentId, n.key(), n.sortNo(), actor, actor);
            } else {
                id = found.get(0);
                moveIfUntouched(jdbc, id, parentId, n.sortNo(), actor);
            }
            folders.put(n.key(), id);
            placeFolders(jdbc, compCd, actor, n.children(), id, folders);
        }
    }

    private static void collectTargets(List<Node> nodes, Long parentId, Map<String, Long> folders,
            Map<String, Set<Long>> targets) {
        for (Node n : nodes) {
            if (n.folder()) {
                collectTargets(n.children(), folders.get(n.key()), folders, targets);
            } else {
                targets.computeIfAbsent(n.progCd(), k -> new HashSet<>()).add(parentId == null ? 0L : parentId);
            }
        }
    }

    private record MenuRow(long menuId, Long parentId, boolean untouched) {
    }

    private static void placeProgs(JdbcTemplate jdbc, String compCd, String actor, List<Node> nodes, Long parentId,
            Map<String, Long> folders, Map<String, Set<Long>> targets, Set<String> progs, boolean links) {
        for (Node n : nodes) {
            if (n.folder()) {
                placeProgs(jdbc, compCd, actor, n.children(), folders.get(n.key()), folders, targets, progs, links);
                continue;
            }
            if (n.link() != links || !progs.contains(n.progCd())) {
                continue;
            }
            List<MenuRow> rows = jdbc.query("""
                    select menu_id, parent_id, (row_ver = 0 and created_by = ? and updated_by = ?) as untouched
                      from sys_menu where comp_cd = ? and prog_cd = ? order by menu_id""",
                    (rs, i) -> new MenuRow(rs.getLong(1), (Long) rs.getObject(2), rs.getBoolean(3)),
                    actor, actor, compCd, n.progCd());
            MenuRow here = rows.stream().filter(r -> Objects.equals(r.parentId(), parentId)).findFirst().orElse(null);
            if (here != null) {
                if (here.untouched()) {
                    moveIfUntouched(jdbc, here.menuId(), parentId, n.sortNo(), actor);
                }
                continue;
            }
            Set<Long> mine = targets.getOrDefault(n.progCd(), Set.of());
            MenuRow stray = rows.stream()
                    .filter(r -> r.untouched() && !mine.contains(r.parentId() == null ? 0L : r.parentId()))
                    .findFirst().orElse(null);
            if (stray != null) {
                moveIfUntouched(jdbc, stray.menuId(), parentId, n.sortNo(), actor);
            } else if (links || rows.isEmpty()) {
                jdbc.update("""
                        insert into sys_menu (comp_cd, parent_id, prog_cd, sort_no, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?)""", compCd, parentId, n.progCd(), n.sortNo(), actor, actor);
            }
            // 그 밖(회사가 다른 곳으로 옮긴 본 자리) — 그대로 둔다
        }
    }

    /** 시드가 만든 그대로인 행만 자리·순서를 맞춘다(row_ver·updated_by 는 그대로 — 계속 "손대지 않은 행") */
    private static void moveIfUntouched(JdbcTemplate jdbc, long menuId, Long parentId, int sortNo, String actor) {
        jdbc.update("""
                update sys_menu set parent_id = ?, sort_no = ?, updated_at = now()
                 where menu_id = ? and row_ver = 0 and created_by = ? and updated_by = ?
                   and (parent_id is distinct from ? or sort_no <> ?)""",
                parentId, sortNo, menuId, actor, actor, parentId, sortNo);
    }
}
