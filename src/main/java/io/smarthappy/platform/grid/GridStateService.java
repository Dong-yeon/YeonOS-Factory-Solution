package io.smarthappy.platform.grid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import tools.jackson.databind.json.JsonMapper;

/**
 * 그리드 화면 설정(사용자별) — 열 폭·순서·숨김·고정·정렬·페이지 크기 (docs/09 4.6, G8).
 * 저장값은 **받은 그대로 저장하지 않고** 허용 구조로 다시 만든다(필드 이름 형식·숫자 범위·개수 상한).
 * 복원은 화면(hb.grid)이 코드 정의에 병합한다 — 사라진 열은 버리고 새 열은 기본 위치.
 * 권한: 그 화면 조회(SEARCH) 권한이 있는 사람만, 사용자는 로그인 principal 에서만.
 */
@Service
public class GridStateService {

    static final Pattern GRID_ID = Pattern.compile("^[a-z][a-zA-Z0-9]{0,29}$");
    static final Pattern FIELD = Pattern.compile("^[a-z][a-zA-Z0-9]{0,49}$");
    static final Pattern HASH = Pattern.compile("^[0-9a-f]{8,64}$");
    static final int MAX_COLS = 100;
    static final Set<Integer> PAGE_SIZES = Set.of(20, 50, 100, 200, 500);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public GridStateService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    public record SaveRequest(String defHash, Map<String, Object> state) {
    }

    @Transactional(readOnly = true)
    public Map<String, Object> load(String progCd, String gridId) {
        LoginUser me = check(progCd, gridId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select def_hash, state::text as state from sys_grid_state
                 where user_id = ? and prog_cd = ? and grid_id = ?""", me.userId(), progCd, gridId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defHash", rows.get(0).get("def_hash"));
        out.put("state", JSON.readValue((String) rows.get(0).get("state"), Map.class));
        return out;
    }

    @Transactional
    public void save(String progCd, String gridId, SaveRequest req) {
        LoginUser me = check(progCd, gridId);
        if (req == null || req.defHash() == null || !HASH.matcher(req.defHash()).matches() || req.state() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String json = JSON.writeValueAsString(sanitize(req.state()));
        jdbc.update("""
                insert into sys_grid_state (comp_cd, user_id, prog_cd, grid_id, def_hash, state, created_by, updated_by)
                values (hb_current_comp(), ?, ?, ?, ?, ?::jsonb, ?, ?)
                on conflict (comp_cd, user_id, prog_cd, grid_id)
                do update set def_hash = excluded.def_hash, state = excluded.state, row_ver = sys_grid_state.row_ver + 1,
                              updated_at = now(), updated_by = excluded.updated_by""",
                me.userId(), progCd, gridId, req.defHash(), json, me.loginId(), me.loginId());
    }

    /** 기본값으로 — 내 설정 행만 지운다 */
    @Transactional
    public void reset(String progCd, String gridId) {
        LoginUser me = check(progCd, gridId);
        jdbc.update("delete from sys_grid_state where user_id = ? and prog_cd = ? and grid_id = ?", me.userId(), progCd,
                gridId);
    }

    private LoginUser check(String progCd, String gridId) {
        if (progCd == null || !progCd.matches("^[a-z0-9][a-z0-9-]{1,49}$") || gridId == null
                || !GRID_ID.matcher(gridId).matches()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LoginUser me = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        if (!permissions.actions(progCd).contains(Action.SEARCH)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        return me;
    }

    /** {cols:[{f,w,h,o,l}], sort:[{f,d}], pageSize} 만 남긴다 */
    static Map<String, Object> sanitize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> cols = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (raw.get("cols") instanceof List<?> list) {
            if (list.size() > MAX_COLS) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || !(m.get("f") instanceof String f) || !FIELD.matcher(f).matches()
                        || !seen.add(f)) {
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("f", f);
                if (m.get("w") instanceof Number w) {
                    c.put("w", Math.max(30, Math.min(1000, w.intValue())));
                }
                c.put("h", Boolean.TRUE.equals(m.get("h")));
                c.put("l", Boolean.TRUE.equals(m.get("l")));
                cols.add(c);
            }
        }
        out.put("cols", cols);
        List<Map<String, Object>> sort = new ArrayList<>();
        if (raw.get("sort") instanceof List<?> list) {
            for (Object o : list) {
                if (sort.size() >= 3) {
                    break;
                }
                if (o instanceof Map<?, ?> m && m.get("f") instanceof String f && FIELD.matcher(f).matches()) {
                    sort.add(Map.of("f", f, "d", Boolean.TRUE.equals(m.get("d"))));
                }
            }
        }
        out.put("sort", sort);
        if (raw.get("pageSize") instanceof Number n && PAGE_SIZES.contains(n.intValue())) {
            out.put("pageSize", n.intValue());
        }
        return out;
    }
}
