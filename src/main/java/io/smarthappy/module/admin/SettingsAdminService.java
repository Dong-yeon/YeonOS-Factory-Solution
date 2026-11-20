package io.smarthappy.module.admin;

import java.text.Collator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.admin.GrantGuard.Perms;
import io.smarthappy.module.admin.SettingsDtos.CodeForm;
import io.smarthappy.module.admin.SettingsDtos.CodePage;
import io.smarthappy.module.admin.SettingsDtos.CodeSort;
import io.smarthappy.module.admin.SettingsDtos.CodeGroupRow;
import io.smarthappy.module.admin.SettingsDtos.CodeView;
import io.smarthappy.module.admin.SettingsDtos.CompCodeRow;
import io.smarthappy.module.admin.SettingsDtos.CompForm;
import io.smarthappy.module.admin.SettingsDtos.CompRow;
import io.smarthappy.module.admin.SettingsDtos.CompView;
import io.smarthappy.module.admin.SettingsDtos.ShownForm;
import io.smarthappy.module.admin.SettingsDtos.PolicyGridRow;
import io.smarthappy.module.admin.SettingsDtos.PolicyPage;
import io.smarthappy.module.admin.SettingsDtos.PolicyRow;
import io.smarthappy.module.admin.SettingsDtos.PolicySave;
import io.smarthappy.module.admin.SettingsDtos.PolicySort;
import io.smarthappy.module.admin.SettingsDtos.PolicyValueRow;
import io.smarthappy.module.admin.SettingsDtos.PolicyView;
import io.smarthappy.module.admin.SettingsDtos.Preview;
import io.smarthappy.module.admin.SettingsDtos.PreviewRequest;
import io.smarthappy.module.admin.SettingsDtos.RuleForm;
import io.smarthappy.module.admin.SettingsDtos.RuleGridRow;
import io.smarthappy.module.admin.SettingsDtos.RulePage;
import io.smarthappy.module.admin.SettingsDtos.RuleSort;
import io.smarthappy.module.admin.SettingsDtos.RuleRow;
import io.smarthappy.module.admin.SettingsDtos.RuleView;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.config.I18nConfig;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.numbering.NumberingPattern;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 시스템관리 2단계 (docs/36 9.2~9.5) — 정책·회사 공통코드·채번 규칙·회사 정보.
 * 검증 본체는 이미 있는 플랫폼 서비스(PolicyService·NumberingPattern)를 그대로 쓰고, 여기서는 화면 규칙만 더한다:
 * 보안 정책·시간대는 관리자 그룹만(Q6·Q9), 모든 저장은 row_ver 동시 편집 확인.
 */
@Service
@RequiredArgsConstructor
public class SettingsAdminService {

    static final String PROG_POLICY = "sys-policy";
    static final String PROG_CODE = "sys-code";
    static final String PROG_NUMBERING = "sys-numbering";
    static final String PROG_COMP = "sys-comp";

    /** 보안 정책 — 낮추면 권한 상승과 같은 효과라 관리자 그룹만 (Q6) */
    static final List<String> SECURITY_PREFIXES = List.of("PWD_", "LOGIN_", "MFA_", "AI_", "FILE_");
    static final Set<String> RESET_CYCLES = Set.of("NONE", "YEAR", "MONTH", "DAY");
    static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,29}$");
    static final Pattern RULE_CD = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");
    static final Pattern PLANT_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    static final Pattern TEL = Pattern.compile("^[0-9+()\\- ]{2,30}$");
    /** 미리보기에서 {plant} 자리 */
    static final String PLANT_SAMPLE = "P1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SettingsMapper mapper;
    private final GrantGuard guard;
    private final PolicyService policies;
    private final BizClock clock;
    private final MessageSource messages;

    static boolean securityPolicy(String key) {
        return SECURITY_PREFIXES.stream().anyMatch(key::startsWith) || key.contains("SELF_APPROVE");
    }

    // ================================================================== 정책

    @Transactional(readOnly = true)
    public List<PolicyView> policies(Locale locale) {
        boolean admin = guard.mine().admin();
        Map<String, List<PolicyValueRow>> values = new LinkedHashMap<>();
        for (PolicyValueRow v : mapper.findPolicyValues()) {
            values.computeIfAbsent(v.getPolicyKey(), k -> new ArrayList<>()).add(v);
        }
        List<PolicyView> out = new ArrayList<>();
        for (PolicyRow d : mapper.findPolicyDefs()) {
            String key = d.getPolicyKey();
            boolean sec = securityPolicy(key);
            out.add(new PolicyView(key, key.substring(0, Math.max(key.indexOf('_'), 1)),
                    messages.getMessage(d.getDescKey(), null, key, locale), d.getValueType(), d.getDefaultValue(),
                    d.getAllowedValues() == null ? List.of() : Arrays.asList(d.getAllowedValues()), d.getMinValue(),
                    d.getMaxValue(), "Y".equals(d.getPlantScopeYn()), sec, !sec || admin,
                    values.getOrDefault(key, List.of())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<String> plants() {
        return mapper.findPlantCds();
    }

    /**
     * grid(UI 단계 3 ①, docs/36 17.2) — 조건(분류·검색어·바꾼 것만)·정렬·쪽을 **서버 Java** 에서.
     * 정의는 전역 수십 건이고 설명이 문구 키(지금 언어 글자)라 SQL 로 거르거나 정렬하면 화면 글자와 어긋난다.
     * 정렬 허용 목록·쪽 크기 검증은 다른 grid 와 같은 PageQuery, 마지막은 키(PK)
     */
    @Transactional(readOnly = true)
    public PolicyPage policyGrid(String category, String keyword, String changedOnly, Integer page, Integer size,
            List<String> sort, Boolean total, Locale locale) {
        String cat = AdminInputs.blank(category) ? null : category.strip();
        String kw = AdminInputs.blank(keyword) ? null : keyword.strip().toLowerCase(Locale.ROOT);
        String changed = AdminInputs.blank(changedOnly) ? "N" : changedOnly;
        if (cat != null && cat.length() > 30 || kw != null && kw.length() > 50 || !changed.equals("Y") && !changed.equals("N")) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PageQuery pq = PageQuery.parse(page, size, sort, total, PolicySort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("policyKey,asc"));
        Comparator<PolicyGridRow> order = null;
        for (PageQuery.Sort s : pq.getSorts()) {
            Comparator<PolicyGridRow> c = switch (PolicySort.valueOf(s.getKey())) {
                case CATEGORY -> Comparator.comparing(PolicyGridRow::category);
                case DESC -> Comparator.comparing(PolicyGridRow::desc, Collator.getInstance(locale));
                default -> Comparator.comparing(PolicyGridRow::policyKey);
            };
            if (s.isDesc()) {
                c = c.reversed();
            }
            order = order == null ? c : order.thenComparing(c);
        }
        Comparator<PolicyGridRow> byKey = Comparator.comparing(PolicyGridRow::policyKey);
        List<PolicyGridRow> rows = policies(locale).stream().map(v -> gridRow(v, locale))
                .filter(r -> cat == null || r.category().equals(cat))
                .filter(r -> kw == null || r.policyKey().toLowerCase(Locale.ROOT).contains(kw) || r.desc().toLowerCase(locale).contains(kw))
                .filter(r -> changed.equals("N") || r.changed())
                .sorted(order == null ? byKey : order.thenComparing(byKey))
                .toList();
        int from = (int) Math.min(pq.getOffset(), rows.size());
        int to = Math.min(from + pq.getSize(), rows.size());
        return new PolicyPage(rows.subList(from, to), pq.isWithTotal() ? (long) rows.size() : null);
    }

    private PolicyGridRow gridRow(PolicyView v, Locale locale) {
        String company = null;
        List<String> plantVals = new ArrayList<>();
        for (PolicyValueRow x : v.values()) {
            if (PolicyService.COMPANY_WIDE.equals(x.getPlantCd())) {
                company = x.getPolicyValue();
            } else {
                plantVals.add(x.getPlantCd() + "=" + x.getPolicyValue());
            }
        }
        String text = company != null ? company : messages.getMessage("js.sysPolicy.defaultOf", new Object[] {v.defaultValue()}, locale);
        return new PolicyGridRow(v.policyKey(), v.category(), v.desc(), company, text, String.join(", ", plantVals),
                !v.values().isEmpty(), v);
    }

    /** grid 열 — 화면 표와 같은 칸. 회사 값이 없으면 "기본값 x"(서버가 지금 언어 글자로) */
    static final List<ColumnSpec> POLICY_GRID_COLUMNS = List.of(
            ColumnSpec.text("policyKey", "sysPolicy.col.key", 220).withSort().withPriority(1),
            ColumnSpec.text("desc", "sysPolicy.col.desc", 280).withSort().withFlex().withPriority(1).withMinWidth(140),
            ColumnSpec.text("companyText", "sysPolicy.col.value", 140).withPriority(1),
            ColumnSpec.text("plantValues", "sysPolicy.col.plants", 160).withPriority(3));

    public List<ColumnSpec> policyGridColumns() {
        return POLICY_GRID_COLUMNS;
    }

    /** 회사(또는 공장) 값 저장 — 검증은 PolicyService.set(정의의 형식·허용값·범위) */
    @Transactional
    public void savePolicy(String key, PolicySave req) {
        LoginUser me = guard.me();
        if (req == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String plant = policyScope(key, req.plantCd());
        PolicyValueRow cur = mapper.findPolicyValue(key, plant);   // FOR UPDATE
        if (cur == null ? req.rowVer() != null : !Integer.valueOf(cur.getRowVer()).equals(req.rowVer())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        try {
            policies.set(key, plant, req.value(), me.loginId());
        } catch (BusinessException e) {
            if (e.code() == HbErrorCode.INVALID_VALUE) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE)
                        .withFieldErrors(FieldErrors.create().add("value", "POLICY_VALUE").list());
            }
            throw e;
        }
    }

    /** 기본값으로 = 회사(공장) 값 행 삭제 (Q7) */
    @Transactional
    public void resetPolicy(String key, String plantCd, Integer rowVer) {
        String plant = policyScope(key, plantCd);
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.deletePolicyValue(key, plant, rowVer) == 0) {
            throw mapper.findPolicyValue(key, plant) == null ? new BusinessException(HbErrorCode.NOT_FOUND)
                    : new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 정의 확인 + 보안 정책 권한 + 범위(공장 값은 공장 범위 정책·사용 중 공장만) → 저장 키의 plant_cd */
    private String policyScope(String key, String plantCd) {
        PolicyRow def = key == null ? null
                : mapper.findPolicyDefs().stream().filter(d -> d.getPolicyKey().equals(key)).findFirst().orElse(null);
        if (def == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (securityPolicy(key) && !guard.mine().admin()) {
            guard.deny(HbErrorCode.GRANT_EXCEEDS, "policy=" + key);
        }
        if (AdminInputs.blank(plantCd) || PolicyService.COMPANY_WIDE.equals(plantCd)) {
            return PolicyService.COMPANY_WIDE;
        }
        if (!"Y".equals(def.getPlantScopeYn()) || !PLANT_CD.matcher(plantCd).matches()
                || !mapper.findPlantCds().contains(plantCd)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("plantCd", "NO_SUCH_CODE").list());
        }
        return plantCd;
    }

    // ================================================================== 회사 공통코드

    /** maxLen = 회사 코드 최대 길이(docs/68) — 화면 코드 칸 maxlength */
    public record GroupView(String grpCd, String name, int maxLen) {
    }

    @Transactional(readOnly = true)
    public List<GroupView> codeGroups(Locale locale) {
        List<GroupView> out = new ArrayList<>();
        for (CodeGroupRow g : mapper.findExtGroups()) {
            out.add(new GroupView(g.getGrpCd(), messages.getMessage(g.getGrpNmKey(), null, g.getGrpCd(), locale), g.getCodeMaxLen()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<CodeView> codes(String grpCd, Locale locale) {
        requireExtGroup(grpCd);
        return codeViews(mapper.findGroupCodes(grpCd), locale);
    }

    private List<CodeView> codeViews(List<CompCodeRow> rows, Locale locale) {
        List<CodeView> out = new ArrayList<>();
        for (CompCodeRow r : rows) {
            String name = r.isSystemYn() ? messages.getMessage(r.getCodeNmKey(), null, r.getCode(), locale) : r.getCodeNm();
            out.add(new CodeView(r.getCode(), name, r.getSortNo(), r.getUseYn(), r.isSystemYn(), scale(r.getAttrs()),
                    r.getRowVer(), r.isHideable()));
        }
        return out;
    }

    /** grid(UI 단계 3 ①, docs/36 17.1) — 목록과 같은 그룹 검증·같은 행 + 서버 페이징·정렬(허용 목록 {@link CodeSort}) */
    @Transactional(readOnly = true)
    public CodePage codeGrid(String grpCd, Integer page, Integer size, List<String> sort, Boolean total, Locale locale) {
        requireExtGroup(grpCd);
        PageQuery pq = PageQuery.parse(page, size, sort, total, CodeSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("sortNo,asc", "code,asc"));
        return new CodePage(codeViews(mapper.findGroupCodePage(grpCd, pq), locale), pq.isWithTotal() ? mapper.countGroupCodes(grpCd) : null);
    }

    /** grid 열 — 화면 표와 같은 칸. 구분(시스템·회사)은 화면이 글자로 */
    static final List<ColumnSpec> CODE_GRID_COLUMNS = List.of(
            ColumnSpec.text("code", "sysCode.col.code", 120).withSort().withPriority(1),
            ColumnSpec.text("name", "sysCode.col.name", 220).withFlex().withPriority(1).withMinWidth(120),
            ColumnSpec.text("system", "sysCode.col.kind", 80).withPriority(3),
            ColumnSpec.of("sortNo", "sysCode.col.sortNo", ColumnType.INT, 70).withSort(),
            ColumnSpec.of("useYn", "sysCode.col.useYn", ColumnType.YN, 80).withSort());

    public List<ColumnSpec> codeGridColumns() {
        return CODE_GRID_COLUMNS;
    }

    @Transactional
    public void createCode(String grpCd, CodeForm form) {
        LoginUser me = guard.me();
        int maxLen = requireExtGroup(grpCd).getCodeMaxLen();
        CodeForm f = validateCode(grpCd, form, null, maxLen);
        try {
            mapper.insertCompCode(grpCd, f, attrs(grpCd, null, f.scale()), me.loginId());
        } catch (DuplicateKeyException e) {
            // 회사 코드 중복(pk) 또는 시스템 코드와 같은 코드(트리거 uq_sys_comp_code_system)
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("code", "DUPLICATE").list());
        }
    }

    @Transactional
    public void updateCode(String grpCd, String code, CodeForm form) {
        LoginUser me = guard.me();
        requireExtGroup(grpCd);
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        CompCodeRow cur = code == null ? null : mapper.findCompCode(grpCd, code);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);   // 시스템 코드도 여기(회사가 고칠 수 없음)
        }
        CodeForm f = validateCode(grpCd, new CodeForm(code, form.codeNm(), form.sortNo(), form.useYn(), form.scale(),
                form.rowVer()), cur, 30);
        if (mapper.updateCompCode(grpCd, f, attrs(grpCd, cur.getAttrs(), f.scale()), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /**
     * 시스템 코드 숨기기(N)·되살리기(Y) — 이 회사 선택 목록에서만(docs/68 Q4). 저장 검사·이름표는 그대로라 이미 저장한 행은 깨지지 않는다.
     * 회사 확장 그룹이 아니면 404, 없는 시스템 코드 404, 분기에 쓰이는 코드(fixed)·전역 사용 중지 코드는 useYn CODE_FIXED.
     * 같은 값을 두 번 보내도 결과는 같다(멱등) — 버전 칸이 없다
     */
    @Transactional
    public void setSystemCodeShown(String grpCd, String code, ShownForm form) {
        LoginUser me = guard.me();
        requireExtGroup(grpCd);
        if (form == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        if (AdminInputs.blank(form.useYn())) {
            e.add("useYn", "REQUIRED");   // 빈 값을 "보이기"로 읽지 않는다(조용히 되살리지 않게)
        }
        String use = AdminInputs.yn(form.useYn(), "useYn", e);
        e.throwIfAny();
        CompCodeRow cur = code == null ? null : mapper.findSystemCode(grpCd, code);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!cur.isHideable()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("useYn", "CODE_FIXED").list());
        }
        if ("N".equals(use)) {
            mapper.hideSystemCode(grpCd, code, me.loginId());
        } else {
            mapper.unhideSystemCode(grpCd, code);
        }
    }

    private CodeGroupRow requireExtGroup(String grpCd) {
        return mapper.findExtGroups().stream().filter(g -> g.getGrpCd().equals(grpCd)).findFirst()
                .orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
    }

    /**
     * UNIT 은 소수 자릿수(scale) 하나만 — 늘리기만(줄이면 이미 저장한 수량이 자릿수를 넘는다).
     * maxLen = 그룹의 회사 코드 최대 길이(code_max_len, docs/68) — 새 코드에만(코드는 고칠 수 없다)
     */
    CodeForm validateCode(String grpCd, CodeForm in, CompCodeRow cur, int maxLen) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String code = AdminInputs.blank(in.code()) ? null : in.code().strip().toUpperCase(Locale.ROOT);
        if (cur == null) {
            if (code == null) {
                e.add("code", "REQUIRED");
            } else if (code.length() > maxLen) {
                e.add("code", "TOO_LONG", maxLen);
            } else if (!CODE.matcher(code).matches()) {
                e.add("code", "FORMAT");
            }
        }
        String nm = AdminInputs.text(in.codeNm(), "codeNm", 100, true, e);
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "FORMAT");
        }
        String use = AdminInputs.yn(in.useYn(), "useYn", e);
        Integer scale = null;
        if ("UNIT".equals(grpCd)) {
            scale = in.scale();
            Integer old = cur == null ? null : scale(cur.getAttrs());
            if (scale == null) {
                e.add("scale", "REQUIRED");
            } else if (scale < 0 || scale > 6) {
                e.add("scale", "FORMAT");
            } else if (old != null && scale < old) {
                e.add("scale", "SCALE_DOWN");
            }
        }
        e.throwIfAny();
        return new CodeForm(code, nm, sort, use, scale, in.rowVer());
    }

    /** 기존 속성은 그대로 두고 UNIT 의 scale 만 바꾼다 */
    private static String attrs(String grpCd, String current, Integer scale) {
        ObjectNode node = current == null || current.isBlank() ? JSON.createObjectNode()
                : (ObjectNode) JSON.readTree(current);
        if ("UNIT".equals(grpCd) && scale != null) {
            node.put("scale", scale);
        }
        return node.toString();
    }

    private static Integer scale(String attrs) {
        if (attrs == null || attrs.isBlank()) {
            return null;
        }
        var n = JSON.readTree(attrs).get("scale");
        return n != null && n.isInt() ? n.intValue() : null;
    }

    // ================================================================== 채번

    @Transactional(readOnly = true)
    public List<RuleView> rules() {
        return ruleViews(mapper.findRules());
    }

    private List<RuleView> ruleViews(List<RuleRow> rows) {
        LocalDate today = clock.today();
        List<RuleView> out = new ArrayList<>();
        for (RuleRow r : rows) {
            NumberingPattern p = NumberingPattern.parse(r.getPattern(), r.getResetCycle()).orElse(null);
            Long last = mapper.findLastNo(r.getRuleCd(), NumberingPattern.periodKey(r.getResetCycle(), today));
            String next = p == null ? null : p.format(today, PLANT_SAMPLE, (last == null ? 0 : last) + 1);
            out.add(new RuleView(r, last, next, p != null && p.needsPlant()));
        }
        return out;
    }

    /** grid(UI 단계 3 ①) — 검색어(코드·이름) + 서버 페이징·정렬(허용 목록 {@link RuleSort}). 마지막·다음 번호는 이 쪽 행만 계산 */
    @Transactional(readOnly = true)
    public RulePage ruleGrid(String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        String kw = AdminInputs.blank(keyword) ? null : keyword.strip();
        if (kw != null && kw.length() > 50) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String like = kw == null ? null : "%" + UserAdminService.likeEscape(kw) + "%";
        PageQuery pq = PageQuery.parse(page, size, sort, total, RuleSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("ruleCd,asc"));
        List<RuleGridRow> rows = ruleViews(mapper.findRulePage(like, pq)).stream().map(v -> {
            RuleRow r = v.rule();
            return new RuleGridRow(r.getRuleCd(), r.getRuleNm(), r.getPattern(), r.getResetCycle(), r.getUseYn(), r.getRowVer(),
                    r.getUpdatedAt(), r.getUpdatedBy(), v.lastNo(), v.nextPreview(), v.needsPlant());
        }).toList();
        return new RulePage(rows, pq.isWithTotal() ? mapper.countRules(like) : null);
    }

    /** grid 열 — 화면 표와 같은 칸. 초기화 주기 이름은 화면이 고르기 칸 글자로 */
    static final List<ColumnSpec> RULE_GRID_COLUMNS = List.of(
            ColumnSpec.text("ruleCd", "sysNumbering.col.ruleCd", 120).withSort().withPriority(1),
            ColumnSpec.text("ruleNm", "sysNumbering.col.ruleNm", 180).withSort().withFlex().withPriority(1).withMinWidth(100),
            ColumnSpec.text("pattern", "sysNumbering.col.pattern", 180).withPriority(2),
            ColumnSpec.text("resetCycle", "sysNumbering.col.resetCycle", 90).withSort().withPriority(3),
            ColumnSpec.of("lastNo", "sysNumbering.col.lastNo", ColumnType.INT, 90).withPriority(3),
            ColumnSpec.text("nextPreview", "sysNumbering.col.next", 160).withPriority(2),
            ColumnSpec.of("useYn", "sysNumbering.col.useYn", ColumnType.YN, 80).withSort());

    public List<ColumnSpec> ruleGridColumns() {
        return RULE_GRID_COLUMNS;
    }

    /** 저장 전 미리보기 — 번호를 쓰지 않는다. 기간이 바뀌면(주기 변경) 그 기간의 마지막 번호로 */
    @Transactional(readOnly = true)
    public Preview preview(PreviewRequest req) {
        if (req == null || !RESET_CYCLES.contains(req.resetCycle())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("resetCycle", "CODE").list());
        }
        NumberingPattern p = NumberingPattern.parse(req.pattern(), req.resetCycle())
                .orElseThrow(() -> new BusinessException(HbErrorCode.INVALID_VALUE)
                        .withFieldErrors(FieldErrors.create().add("pattern", "PATTERN").list()));
        LocalDate today = clock.today();
        RuleRow cur = req.ruleCd() == null ? null : mapper.findRule(req.ruleCd());
        Long last = cur == null ? null : mapper.findLastNo(cur.getRuleCd(), NumberingPattern.periodKey(req.resetCycle(), today));
        return new Preview(p.format(today, PLANT_SAMPLE, (last == null ? 0 : last) + 1), last,
                cur != null && !cur.getResetCycle().equals(req.resetCycle()));
    }

    @Transactional
    public void createRule(RuleForm form) {
        LoginUser me = guard.me();
        RuleForm f = validateRule(form, true);
        try {
            mapper.insertRule(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("ruleCd", "DUPLICATE").list());
        }
    }

    @Transactional
    public void updateRule(String ruleCd, RuleForm form) {
        LoginUser me = guard.me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (ruleCd == null || mapper.findRule(ruleCd) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        RuleForm f = validateRule(new RuleForm(ruleCd, form.ruleNm(), form.pattern(), form.resetCycle(), form.useYn(),
                form.rowVer()), false);
        if (mapper.updateRule(f, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 패턴 토큰·주기 정합은 NumberingPattern. 이번 기간에 이미 낸 번호가 새 자릿수를 넘으면 거부(다음 채번이 바로 409) */
    RuleForm validateRule(RuleForm in, boolean create) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = AdminInputs.blank(in.ruleCd()) ? null : in.ruleCd().strip().toUpperCase(Locale.ROOT);
        if (create) {
            if (cd == null) {
                e.add("ruleCd", "REQUIRED");
            } else if (!RULE_CD.matcher(cd).matches()) {
                e.add("ruleCd", "FORMAT");
            } else if (mapper.findRule(cd) != null) {
                e.add("ruleCd", "DUPLICATE");
            }
        }
        String nm = AdminInputs.text(in.ruleNm(), "ruleNm", 100, true, e);
        String reset = in.resetCycle();
        String pattern = in.pattern() == null ? null : in.pattern().strip();
        if (!RESET_CYCLES.contains(reset)) {
            e.add("resetCycle", "CODE");
        } else {
            NumberingPattern p = NumberingPattern.parse(pattern, reset).orElse(null);
            if (p == null) {
                e.add("pattern", "PATTERN");
            } else if (!create && cd != null) {
                Long last = mapper.findLastNo(cd, NumberingPattern.periodKey(reset, clock.today()));
                if (last != null && p.overflows(last + 1)) {
                    e.add("pattern", "SEQ_TOO_SHORT");
                }
            }
        }
        String use = AdminInputs.yn(in.useYn(), "useYn", e);
        e.throwIfAny();
        return new RuleForm(cd, nm, pattern, reset, use, in.rowVer());
    }

    // ================================================================== 회사 정보

    @Transactional(readOnly = true)
    public CompView comp() {
        CompRow c = mapper.findComp();
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new CompView(c, guard.mine().admin());
    }

    @Transactional
    public void updateComp(CompForm form) {
        LoginUser me = guard.me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        CompRow cur = mapper.findComp();
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String nm = AdminInputs.text(form.compNm(), "compNm", 100, true, e);
        String en = AdminInputs.text(form.compEnNm(), "compEnNm", 100, false, e);
        String biz = null;
        if (!AdminInputs.blank(form.bizNo())) {
            biz = form.bizNo().replaceAll("[\\s-]", "");
            if (!biz.matches("^[0-9]{10}$")) {
                e.add("bizNo", "FORMAT");
            }
        }
        String ceo = AdminInputs.text(form.ceoNm(), "ceoNm", 50, false, e);
        String tel = AdminInputs.text(form.telNo(), "telNo", 30, false, e);
        if (tel != null && !TEL.matcher(tel).matches()) {
            e.add("telNo", "FORMAT");
        }
        String addr = AdminInputs.text(form.addr(), "addr", 300, false, e);
        String tz = AdminInputs.blank(form.timeZone()) ? null : form.timeZone().strip();
        if (tz == null) {
            e.add("timeZone", "REQUIRED");
        } else if (!ZoneId.getAvailableZoneIds().contains(tz)) {
            e.add("timeZone", "CODE");
        }
        String locale = AdminInputs.blank(form.localeCd()) ? "ko" : form.localeCd();
        if (!I18nConfig.isSupported(locale)) {
            e.add("localeCd", "CODE");
        }
        boolean tzChange = tz != null && !tz.equals(cur.getTimeZone());
        if (tzChange && e.isEmpty() && !Boolean.TRUE.equals(form.confirmTimeZone())) {
            e.add("timeZone", "CONFIRM_TZ");   // 작업일·채번 날짜·근무조 판정 기준이 바뀐다는 확인
        }
        e.throwIfAny();
        if (tzChange) {
            Perms mine = guard.mine();
            if (!mine.admin()) {
                guard.deny(HbErrorCode.GRANT_EXCEEDS, "timeZone");
            }
        }
        CompForm f = new CompForm(nm, en, biz, ceo, tel, addr, tz, locale, form.confirmTimeZone(), form.rowVer());
        if (mapper.updateComp(f, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }
}
