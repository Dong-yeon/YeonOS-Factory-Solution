package io.smarthappy.module.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.admin.SettingsDtos.DeptChoice;
import io.smarthappy.module.admin.SettingsDtos.DeptForm;
import io.smarthappy.module.admin.SettingsDtos.DeptNode;
import io.smarthappy.module.admin.SettingsDtos.DeptRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import lombok.RequiredArgsConstructor;

/**
 * 부서 관리 (sys-dept, docs/36 9.1). 회사 부서 트리 + 다른 화면의 부서 선택지.
 * 부모·순환·10단·사용 중 하위·같은 회사는 트리거 hb_check_dept_tree 가 최종(직접 SQL 포함), 서비스는 형식 + 제약 → 칸 오류.
 * 삭제 없음(사용 중지). 소속 사용자가 있어도 사용 중지는 된다 — 새 소속만 막는다(Q10).
 * 부서장(head_user_id, 9.7 D-1): 사용 중 회사 사용자만 새로 지정 — 트리거 hb_check_dept_head 가 최종. 결재선 기본값은 UserDirectory.deptHeadOf.
 */
@Service
@RequiredArgsConstructor
public class DeptAdminService {

    static final String PROG = "sys-dept";
    static final Pattern DEPT_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,29}$");

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("deptNm", "sysDept.col.deptNm", 240).withFlex().withMinWidth(160),
            ColumnSpec.text("deptCd", "sysDept.col.deptCd", 110),
            ColumnSpec.text("headNm", "sysDept.col.headNm", 110),
            ColumnSpec.of("memberCnt", "sysDept.col.memberCnt", ColumnSpec.ColumnType.INT, 80),
            ColumnSpec.of("sortNo", "sysDept.col.sortNo", ColumnSpec.ColumnType.INT, 70),
            ColumnSpec.of("useYn", "sysDept.col.useYn", ColumnSpec.ColumnType.YN, 70));

    static final Map<String, String[]> CONSTRAINT_FIELD = Map.of(
            "ck_sys_dept_cycle", new String[] {"parentId", "DEPT_CYCLE"},
            "ck_sys_dept_not_self", new String[] {"parentId", "DEPT_CYCLE"},
            "ck_sys_dept_depth", new String[] {"parentId", "DEPT_DEPTH"},
            "ck_sys_dept_parent_use", new String[] {"parentId", "INACTIVE"},
            "fk_sys_dept_parent_comp", new String[] {"parentId", "NO_SUCH_CODE"},
            "ck_sys_dept_children_active", new String[] {"useYn", "CHILDREN_ACTIVE"},
            "fk_sys_dept_head_comp", new String[] {"headUserId", "NO_SUCH_CODE"},
            "fk_sys_dept_head", new String[] {"headUserId", "NO_SUCH_CODE"},
            "ck_sys_dept_head_use", new String[] {"headUserId", "INACTIVE"});

    private final SettingsMapper mapper;
    private final GrantGuard guard;
    private final UserDirectory users;

    /** 트리(사용 중지 포함) — 경로 순서 평면 행 + 레벨, 경로는 코드 */
    @Transactional(readOnly = true)
    public List<DeptNode> tree() {
        List<DeptRow> rows = mapper.findDepts();
        Map<Long, List<DeptRow>> byParent = new HashMap<>();
        for (DeptRow r : rows) {
            byParent.computeIfAbsent(r.getParentId() == null ? 0L : r.getParentId(), k -> new ArrayList<>()).add(r);
        }
        List<DeptNode> out = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        walk(0L, 1, "", false, byParent, visited, out);
        return out;
    }

    private void walk(long parentId, int lvl, String path, boolean hiddenAbove, Map<Long, List<DeptRow>> byParent,
            Set<Long> visited, List<DeptNode> out) {
        for (DeptRow r : byParent.getOrDefault(parentId, List.of())) {
            if (!visited.add(r.getDeptId())) {
                continue;
            }
            String p = path.isEmpty() ? r.getDeptCd() : path + "/" + r.getDeptCd();
            boolean dim = hiddenAbove || !"Y".equals(r.getUseYn());
            out.add(new DeptNode(r.getDeptId(), r.getParentId(), lvl, r.getDeptCd(), r.getDeptNm(), p, r.getSortNo(),
                    r.getUseYn(), r.getMemberCnt(), dim, r.getRowVer(), r.getHeadUserId(), r.getHeadNm(), r.getHeadLoginId(),
                    r.isHeadActive()));
            walk(r.getDeptId(), lvl + 1, p, dim, byParent, visited, out);
        }
    }

    /** 다른 화면(사용자 관리 등)의 선택지 — 사용 중인 부서만, 경로 순서 */
    @Transactional(readOnly = true)
    public List<DeptChoice> choices() {
        return tree().stream().filter(n -> !n.dim())
                .map(n -> new DeptChoice(n.deptId(), n.deptCd(), n.deptNm(), n.path())).toList();
    }

    /** 부서장 선택지 — 사용 중 회사 사용자(공통 사용자 고르기) */
    @Transactional(readOnly = true)
    public List<UserOption> headCandidates() {
        return users.activeUsers();
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional
    public long create(DeptForm form) {
        LoginUser me = guard.me();
        DeptForm f = validate(form, null);
        try {
            return mapper.insertDept(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("deptCd", "DUPLICATE").list());
        } catch (DataIntegrityViolationException e) {
            throw fieldError(e);
        }
    }

    @Transactional
    public void update(long deptId, DeptForm form) {
        LoginUser me = guard.me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        DeptRow cur = mapper.findDept(deptId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        DeptForm f = validate(form, cur);
        int n;
        try {
            n = mapper.updateDept(deptId, f, me.loginId());
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

    /** cur = null 이면 추가(코드 검사), 아니면 코드는 그대로 */
    DeptForm validate(DeptForm in, DeptRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd;
        if (cur == null) {
            cd = AdminInputs.blank(in.deptCd()) ? null : in.deptCd().strip().toUpperCase(Locale.ROOT);
            if (cd == null) {
                e.add("deptCd", "REQUIRED");
            } else if (cd.length() > 30) {
                e.add("deptCd", "TOO_LONG", 30);
            } else if (!DEPT_CD.matcher(cd).matches()) {
                e.add("deptCd", "FORMAT");
            } else if (mapper.existsDeptCd(cd)) {
                e.add("deptCd", "DUPLICATE");
            }
        } else {
            cd = cur.getDeptCd();
        }
        String nm = AdminInputs.text(in.deptNm(), "deptNm", 100, true, e);
        Long parent = in.parentId();
        if (parent != null) {
            DeptRow p = mapper.findDept(parent);   // 다른 회사 부서는 RLS 로 안 보인다 → 없는 것
            if (p == null) {
                e.add("parentId", "NO_SUCH_CODE");
            } else if (cur != null && parent == cur.getDeptId()) {
                e.add("parentId", "DEPT_CYCLE");
            }
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "FORMAT");
        }
        String use = AdminInputs.yn(in.useYn(), "useYn", e);
        // 부서장: 새로 넣거나 바꿀 때만 사용 중 회사 사용자인지 — 이미 걸린 장이 나중에 사용 중지돼도 다른 칸 저장은 막지 않는다(트리거도 같은 규칙)
        Long head = in.headUserId();
        if (head != null && (cur == null || !head.equals(cur.getHeadUserId())) && !users.isActive(head)) {
            e.add("headUserId", "INACTIVE");
        }
        e.throwIfAny();
        return new DeptForm(parent, cd, nm, sort, use, in.rowVer(), head);
    }
}
