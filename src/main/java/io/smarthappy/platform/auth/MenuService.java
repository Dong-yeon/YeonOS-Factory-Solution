package io.smarthappy.platform.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 사용자의 메뉴 트리.
 * - 화면(프로그램) 메뉴는 SEARCH 권한이 있을 때만, 폴더는 보이는 하위 메뉴가 있을 때만 나온다
 * - 사용중지 폴더 아래는 통째로 숨는다 (루트에서 내려가며 만든다)
 * - 부모가 꼬여 순환하거나 끊긴 메뉴는 루트에서 닿지 않으므로 나오지 않는다(오류 없이 무시)
 * - 이름: 회사가 입력한 이름 → 메뉴 메시지 키 → 프로그램 이름 키 순
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    static final int MAX_DEPTH = 5;

    private final AuthMapper mapper;
    private final MessageSource messages;

    public record MenuNode(long menuId, String name, String progCd, List<MenuNode> children) {
    }

    /**
     * 역할별 홈이 없을 때 여는 기본 홈 (docs/37 H2 "홈은 모두 동일하게", 2026-10-20). 플랫폼은 홈 모듈 클래스를 참조하지 않고
     * 화면 코드만 안다 — 화면이 없거나 사용 중지면 조회 권한 판정(findActions)에서 빠져 null 이 된다.
     */
    static final String DEFAULT_HOME = "home-main";

    /**
     * 로그인 직후 열 화면 (역할별 홈, docs/11 D6) — 권한그룹 home_prog_cd(조회 권한이 있는 첫 그룹) →
     * 없으면 기본 홈(조회 권한이 있을 때만) → 그것도 없으면 null
     */
    @Transactional(readOnly = true)
    public String home() {
        LoginUser user = CurrentUser.get().orElse(null);
        if (user == null) {
            return null;
        }
        String home = mapper.findHomeProg(user.userId());
        if (home == null && mapper.findActions(user.userId(), DEFAULT_HOME).contains(Action.SEARCH.name())) {
            home = DEFAULT_HOME;
        }
        return home;
    }

    @Transactional(readOnly = true)
    public List<MenuNode> myMenus(Locale locale) {
        LoginUser user = CurrentUser.get().orElse(null);
        if (user == null) {
            return List.of();
        }
        Set<String> searchable = new HashSet<>(mapper.findSearchableProgs(user.userId()));
        Map<Long, List<MenuRow>> byParent = new HashMap<>();
        for (MenuRow row : mapper.findMenus()) {
            byParent.computeIfAbsent(row.getParentId() == null ? 0L : row.getParentId(), k -> new ArrayList<>()).add(row);
        }
        return build(0L, byParent, searchable, locale, 1, new HashSet<>());
    }

    private List<MenuNode> build(long parentId, Map<Long, List<MenuRow>> byParent, Set<String> searchable,
            Locale locale, int depth, Set<Long> visited) {
        List<MenuNode> result = new ArrayList<>();
        if (depth > MAX_DEPTH) {
            return result;
        }
        for (MenuRow row : byParent.getOrDefault(parentId, List.of())) {
            if (!visited.add(row.getMenuId())) {
                continue;
            }
            if (row.getProgCd() != null) {
                if (searchable.contains(row.getProgCd())) {
                    result.add(new MenuNode(row.getMenuId(), name(row, locale), row.getProgCd(), List.of()));
                }
            } else {
                List<MenuNode> children = build(row.getMenuId(), byParent, searchable, locale, depth + 1, visited);
                if (!children.isEmpty()) {
                    result.add(new MenuNode(row.getMenuId(), name(row, locale), null, children));
                }
            }
        }
        return result;
    }

    private String name(MenuRow row, Locale locale) {
        if (row.getMenuNm() != null) {
            return row.getMenuNm();
        }
        String key = row.getMenuNmKey() != null ? row.getMenuNmKey() : row.getProgNmKey();
        return key == null ? "?" : messages.getMessage(key, null, key, locale);
    }
}
