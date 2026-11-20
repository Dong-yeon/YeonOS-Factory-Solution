package io.smarthappy.module.master.classprop;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.classprop.ClassPropDtos.AttrsForm;
import io.smarthappy.module.master.classprop.ClassPropDtos.AttrsView;
import io.smarthappy.module.master.classprop.ClassPropDtos.ClassForm;
import io.smarthappy.module.master.classprop.ClassPropDtos.ClassRow;
import io.smarthappy.module.master.classprop.ClassPropDtos.PropForm;
import io.smarthappy.module.master.classprop.ClassPropDtos.PropRow;
import io.smarthappy.module.master.classprop.ClassPropDtos.PropValue;
import io.smarthappy.module.master.classprop.ClassPropDtos.ClassSort;
import io.smarthappy.module.master.classprop.ClassPropMapper.TargetRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 품목 클래스·클래스 속성(mst-class-prop)과 대상(품목·설비)의 attrs 저장 — P1 7b, docs/22 5b.
 *
 * - 값은 대상 테이블 attrs jsonb. 키 = 속성 코드, 값 = 형식대로(TEXT·DATE·LIST 문자열, NUM 숫자, BOOL true/false), 빈 값은 키 없음
 * - 검증 규칙은 DB 함수 hb_check_attrs 와 같다(트리거가 최종). 서비스는 속성마다 칸 오류 "attrs.{속성 코드}"
 * - 속성의 형식·코드는 만든 뒤 고정, 사용 중지 속성은 저장된 값만 남고 필수 검사에서 빠진다
 */
@Service
@RequiredArgsConstructor
public class ClassPropService {

    public static final String PROG = "mst-class-prop";
    /**
     * 숫자는 BigDecimal 로 읽고 일반 표기로 쓴다 — Double 로 읽으면 123456789012.123456 이 조용히 반올림되고
     * 5.0E-4 같은 표기가 "그대로인가" 비교를 깬다(7단계 QA 결함 #1·#2)
     */
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();
    /** TEXT 속성: 제어 문자(C0·C1) 금지, 보이지 않는 글자(공백·NBSP·폭 없는 문자)만이면 빈 값 */
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    private static final Pattern INVISIBLE = Pattern.compile("[\\s\\p{Z}\\p{Cf}]+");
    static final Pattern CLASS_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,29}$");
    static final Pattern PROP_CD = Pattern.compile("^[A-Z][A-Z0-9_]{0,29}$");
    static final Set<String> CLASS_TYPES = Set.of("ITEM", "EQUIP");
    static final Set<String> DATA_TYPES = Set.of("TEXT", "NUM", "DATE", "BOOL", "LIST");
    static final BigDecimal NUM_LIMIT = new BigDecimal("1E15");

    private final ClassPropMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;

    /** 클래스 그리드 기본 정렬 = 기존 목록 순서 */
    static final List<String> CLASS_SORT = List.of("sortNo,asc", "classCd,asc");

    static final List<ColumnSpec> CLASS_COLUMNS = List.of(
            ColumnSpec.text("classCd", "classProp.col.classCd", 110).withSort().withPriority(1),
            ColumnSpec.text("classNm", "classProp.col.classNm", 160).withSort().withPriority(1).withFlex().withMinWidth(100),
            ColumnSpec.of("propCnt", "classProp.col.propCnt", ColumnSpec.ColumnType.INT, 70).withSort().withPriority(2),
            ColumnSpec.of("targetCnt", "classProp.col.targetCnt", ColumnSpec.ColumnType.INT, 80).withSort().withPriority(2),
            ColumnSpec.of("useYn", "classProp.col.useYn", ColumnSpec.ColumnType.YN, 70).withPriority(2));

    public record ClassPage(List<ClassRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 클래스

    @Transactional(readOnly = true)
    public List<ClassRow> classes(String classType, String useYn) {
        if ("ITEM".equals(classType)) {
            return mapper.findItemClasses(blank(useYn) ? null : useYn);
        }
        if ("EQUIP".equals(classType)) {
            return mapper.findEquipClasses();
        }
        throw new BusinessException(HbErrorCode.INVALID_VALUE);
    }

    /** 클래스 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 기존 목록과 같은 조건(클래스 유형 필수) + 페이지·정렬·건수 */
    @Transactional(readOnly = true)
    public ClassPage classGrid(String classType, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, ClassSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), CLASS_SORT);
        if (!"ITEM".equals(classType) && !"EQUIP".equals(classType)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new ClassPage(mapper.findClassPage(classType, null, pq), pq.isWithTotal() ? mapper.countClasses(classType, null) : null);
    }

    public List<ColumnSpec> classColumns() {
        return CLASS_COLUMNS;
    }

    @Transactional
    public long createItemClass(ClassForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_ITEM_CLASS_CREATE");
        ClassForm f = validateClass(in, null);
        try {
            return mapper.insertItemClass(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("classCd", "DUPLICATE");
        }
    }

    @Transactional
    public void updateItemClass(long classId, ClassForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ClassRow cur = mapper.findItemClass(classId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.updateItemClass(classId, validateClass(in, cur), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private ClassForm validateClass(ClassForm in, ClassRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = blank(in.classCd()) ? null : in.classCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null) {
            if (cd != null && !cd.equals(cur.getClassCd())) {
                e.add("classCd", "FIXED");
            }
            cd = cur.getClassCd();
        } else if (cd == null) {
            e.add("classCd", "REQUIRED");
        } else if (!CLASS_CD.matcher(cd).matches()) {
            e.add("classCd", "FORMAT");
        }
        String nm = text(in.classNm(), "classNm", 100, true, e);
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String use = yn(in.useYn(), "useYn", "Y", e);
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new ClassForm(cd, nm, sort, use, remark, cur == null ? null : in.rowVer());
    }

    // ------------------------------------------------------------------ 속성 정의

    @Transactional(readOnly = true)
    public List<PropRow> props(String classType, long classId, boolean activeOnly) {
        requireClass(classType, classId);
        return withOptions(mapper.findProps(classType, classId, activeOnly));
    }

    @Transactional
    public long createProp(PropForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_CLASS_PROP_CREATE");
        if (in == null || !CLASS_TYPES.contains(in.classType()) || in.classId() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        requireClass(in.classType(), in.classId());
        PropValue v = validateProp(in, null);
        try {
            return mapper.insertProp(v, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("propCd", "DUPLICATE");
        }
    }

    /** 수정 — 코드·형식은 고정(저장된 값의 뜻이 바뀐다). 이름·단위·자릿수·선택지·필수·순서·사용 */
    @Transactional
    public void updateProp(long propId, PropForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PropRow cur = mapper.findProp(propId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        try {
            if (mapper.updateProp(propId, validateProp(in, cur), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            if (PgErrors.constraint(e).filter("ck_mst_class_prop_fixed"::equals).isPresent()) {
                throw fieldError("dataType", "FIXED");
            }
            throw e;
        }
    }

    private PropValue validateProp(PropForm in, PropRow cur) {
        FieldErrors e = FieldErrors.create();
        String cd = blank(in.propCd()) ? null : in.propCd().strip().toUpperCase(Locale.ROOT);
        String type = in.dataType();
        if (cur != null) {
            if (cd != null && !cd.equals(cur.getPropCd())) {
                e.add("propCd", "FIXED");
            }
            if (!blank(type) && !type.equals(cur.getDataType())) {
                e.add("dataType", "FIXED");
            }
            cd = cur.getPropCd();
            type = cur.getDataType();
        } else {
            if (cd == null) {
                e.add("propCd", "REQUIRED");
            } else if (!PROP_CD.matcher(cd).matches()) {
                e.add("propCd", "FORMAT");
            }
            if (blank(type)) {
                e.add("dataType", "REQUIRED");
            } else if (!DATA_TYPES.contains(type)) {
                e.add("dataType", "CODE");
            }
        }
        String nm = text(in.propNm(), "propNm", 100, true, e);
        String unit = text(in.unitNm(), "unitNm", 20, false, e);
        Integer scale = null;
        if ("NUM".equals(type)) {
            scale = in.scale() == null ? 0 : in.scale();
            if (scale < 0 || scale > 6) {
                e.add("scale", "CODE");
            }
        }
        String optionsJson = null;
        if ("LIST".equals(type)) {
            // 앞뒤 공백 정리, 같은 선택지 하나로 — 순서는 입력 순서
            Set<String> opts = new LinkedHashSet<>();
            if (in.options() != null) {
                in.options().stream().filter(o -> !blank(o)).map(String::strip).forEach(opts::add);
            }
            if (opts.isEmpty()) {
                e.add("options", "REQUIRED");
            } else if (opts.size() > 50 || opts.stream().anyMatch(o -> o.length() > 100)) {
                e.add("options", "TOO_MANY");
            } else {
                optionsJson = JSON.writeValueAsString(new ArrayList<>(opts));
            }
        }
        String req = yn(in.requiredYn(), "requiredYn", "N", e);
        String sem = text(in.semanticId(), "semanticId", 200, false, e);
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String use = yn(in.useYn(), "useYn", "Y", e);
        e.throwIfAny();
        String classType = cur != null ? cur.getClassType() : in.classType();
        long classId = cur != null ? cur.getClassId() : in.classId();
        return new PropValue(classType, classId, cd, nm, type, unit, scale, optionsJson, req, sem, sort, use,
                cur == null ? null : in.rowVer());
    }

    // ------------------------------------------------------------------ 대상 값

    /** 품목·설비 화면: 지금 클래스·값 + 그 클래스의 속성(사용 중 + 사용 중지인데 값이 있는 것) */
    @Transactional(readOnly = true)
    public AttrsView attrs(String classType, long targetId) {
        TargetRow t = target(classType, targetId);
        Map<String, Object> cur = parse(t.getAttrs());
        List<PropRow> props = t.getClassId() == null ? List.of()
                : withOptions(mapper.findProps(classType, t.getClassId(), false)).stream()
                        .filter(p -> "Y".equals(p.getUseYn()) || cur.containsKey(p.getPropCd())).toList();
        // 숫자는 일반 표기 글자로 내려 준다 — 브라우저 JSON.parse 가 Double 로 읽어 자릿수를 잃지 않게
        Map<String, Object> out = new LinkedHashMap<>();
        cur.forEach((k, v) -> out.put(k, v instanceof BigDecimal b ? b.toPlainString() : v instanceof BigInteger i ? i.toString() : v));
        return new AttrsView(t.getClassId(), out, props, t.getRowVer());
    }

    /** 요청 본문 → AttrsForm (숫자를 BigDecimal 로 읽으려고 우리 JSON 설정으로) */
    public static AttrsForm parseForm(String body) {
        try {
            return body == null || body.isBlank() ? null : JSON.readValue(body, AttrsForm.class);
        } catch (JacksonException e) {
            throw new BusinessException(HbErrorCode.BAD_REQUEST);     // 다른 API 의 본문 파싱 실패와 같은 코드
        }
    }

    /**
     * 클래스·값 저장. 품목은 클래스도 바꾼다(비우면 클래스 없음 — 값도 비워야). 설비는 classId 를 보내면 클래스와 새 클래스 값을
     * 한 번에(보내지 않으면 지금 클래스) — 필수 속성 값이 있는 설비를 그 속성이 없는 클래스로 옮길 길(7단계 QA 결함 #3).
     * 사용 중지 속성의 값은 그대로 두는 것만, 클래스가 같으면 **바뀌지 않은 값은 정의가 바뀌었어도 둔다**(결함 #4, V031)
     */
    @Transactional
    public void saveAttrs(String classType, long targetId, AttrsForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        TargetRow t = target(classType, targetId);
        Long classId = "ITEM".equals(classType) || in.classId() != null ? in.classId() : t.getClassId();
        FieldErrors e = FieldErrors.create();
        if ("EQUIP".equals(classType) && classId == null) {
            e.add("classId", "REQUIRED");             // 설비는 클래스 필수
        } else if (classId != null && !classId.equals(t.getClassId())) {
            ClassRow c = "ITEM".equals(classType) ? mapper.findItemClass(classId) : mapper.findEquipClass(classId);
            if (c == null) {
                e.add("classId", "NOT_FOUND");
            } else if (!"Y".equals(c.getUseYn())) {
                e.add("classId", "INACTIVE");
            }
            e.throwIfAny();
        }
        Map<String, Object> attrs = in.attrs() == null ? Map.of() : in.attrs();
        String json = JSON.writeValueAsString(validateAttrs(classType, classId, attrs, parse(t.getAttrs()),
                Objects.equals(classId, t.getClassId()), e));
        e.throwIfAny();
        try {
            if (mapper.updateTarget(classType, targetId, classId, json, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            // 서비스 검사 뒤 다른 사용자가 속성 정의를 바꿈 — 다시 열어 저장하게
            if (PgErrors.constraint(ex).filter(c -> c.startsWith("ck_attrs_")).isPresent()) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            throw ex;
        }
    }

    /** hb_check_attrs 와 같은 규칙 — 오류는 e 에 "attrs.{속성 코드}" 로, 값은 정리된 맵(빈 값은 키 없음) */
    Map<String, Object> validateAttrs(String classType, Long classId, Map<String, Object> in, Map<String, Object> cur,
            boolean classSame, FieldErrors e) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (classId == null) {
            if (in.values().stream().anyMatch(v -> v != null && !(v instanceof String s && s.isBlank()))) {
                e.add("classId", "NO_CLASS");
            }
            return out;
        }
        Map<String, PropRow> props = new HashMap<>();
        withOptions(mapper.findProps(classType, classId, false)).forEach(p -> props.put(p.getPropCd(), p));
        for (Map.Entry<String, Object> kv : in.entrySet()) {
            PropRow p = props.get(kv.getKey());
            String f = "attrs." + kv.getKey();
            Object old = cur.get(kv.getKey());
            if (classSame && old != null && same(old, kv.getValue())) {
                out.put(kv.getKey(), old);     // 바뀌지 않은 값 — 정의가 그 뒤 바뀌었어도 둔다(새 참조만 막는다)
                continue;
            }
            if (p == null) {
                e.add(f, "UNKNOWN_PROP");
                continue;
            }
            Object v = normalize(p, kv.getValue(), f, e);
            if (v == null) {
                continue;
            }
            if (!"Y".equals(p.getUseYn())) {
                e.add(f, "INACTIVE");      // 사용 중지 속성은 새로 넣거나 바꾸지 않는다
                continue;
            }
            out.put(p.getPropCd(), v);
        }
        props.values().stream()
                .filter(p -> "Y".equals(p.getUseYn()) && "Y".equals(p.getRequiredYn()) && !out.containsKey(p.getPropCd()))
                .filter(p -> e.list().stream().noneMatch(x -> x.field().equals("attrs." + p.getPropCd())))
                .forEach(p -> e.add("attrs." + p.getPropCd(), "REQUIRED"));
        return out;
    }

    /** 저장된 값과 보낸 값이 같은가 — 숫자는 크기로(1.50 = 1.5), 글자는 앞뒤 공백 빼고, 그 밖은 그대로 */
    static boolean same(Object old, Object raw) {
        if (raw == null) {
            return false;
        }
        if (old instanceof BigDecimal || old instanceof BigInteger) {
            try {
                BigDecimal a = new BigDecimal(old.toString());
                // 글자는 일반 10진 표기만(BigDecimal 은 아라비아 숫자·지수도 받는다 — 같은 값이어도 형식 검사를 피하지 않게)
                BigDecimal b = raw instanceof Number n ? new BigDecimal(n.toString())
                        : raw instanceof String str && str.strip().matches("^-?[0-9]+(\\.[0-9]+)?$") ? new BigDecimal(str.strip()) : null;
                return b != null && a.compareTo(b) == 0;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        if (old instanceof String o) {
            return raw instanceof String r && o.equals(r.strip());
        }
        return old.equals(raw);
    }

    /** 한 값 정리 — 빈 값이면 null(키 없음), 형식이 틀리면 오류 후 null */
    private static Object normalize(PropRow p, Object raw, String f, FieldErrors e) {
        if (raw == null || (raw instanceof String s && INVISIBLE.matcher(s).replaceAll("").isEmpty())) {
            return null;
        }
        switch (p.getDataType()) {
            case "TEXT" -> {
                if (!(raw instanceof String s) || CONTROL.matcher(s).find()) {
                    e.add(f, "FORMAT");
                    return null;
                }
                String v = s.strip();
                if (v.length() > 500) {
                    e.add(f, "TOO_LONG", 500);
                    return null;
                }
                return v;
            }
            case "NUM" -> {
                BigDecimal v;
                try {
                    v = raw instanceof Number n ? new BigDecimal(n.toString()) : raw instanceof String s ? new BigDecimal(s.strip()) : null;
                } catch (NumberFormatException ex) {
                    v = null;
                }
                if (v == null || (raw instanceof String s && !s.strip().matches("^-?[0-9]+(\\.[0-9]+)?$"))) {
                    e.add(f, "FORMAT");
                    return null;
                }
                v = v.stripTrailingZeros();
                if (v.scale() < 0) {
                    v = v.setScale(0);
                }
                if (v.scale() > p.getScale()) {
                    e.add(f, "SCALE", p.getScale());
                    return null;
                }
                if (v.abs().compareTo(NUM_LIMIT) >= 0) {
                    e.add(f, "INT_DIGITS", 15);
                    return null;
                }
                return v;
            }
            case "DATE" -> {
                try {
                    LocalDate d = raw instanceof String s ? LocalDate.parse(s.strip()) : null;
                    if (d == null || d.getYear() < 1900 || d.getYear() > 2199) {
                        e.add(f, "FORMAT");
                        return null;
                    }
                    return d.toString();
                } catch (DateTimeParseException ex) {
                    e.add(f, "FORMAT");
                    return null;
                }
            }
            case "BOOL" -> {
                if (!(raw instanceof Boolean b)) {
                    e.add(f, "FORMAT");
                    return null;
                }
                return b;
            }
            default -> {
                if (!(raw instanceof String s) || !p.getOptions().contains(s.strip())) {
                    e.add(f, "CODE");
                    return null;
                }
                return s.strip();
            }
        }
    }

    // ------------------------------------------------------------------ 공통

    private TargetRow target(String classType, long targetId) {
        TargetRow t = CLASS_TYPES.contains(classType) ? mapper.findTarget(classType, targetId) : null;
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return t;
    }

    private void requireClass(String classType, long classId) {
        ClassRow c = "ITEM".equals(classType) ? mapper.findItemClass(classId)
                : "EQUIP".equals(classType) ? mapper.findEquipClass(classId) : null;
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    private static List<PropRow> withOptions(List<PropRow> rows) {
        rows.forEach(p -> p.setOptions(p.getOptionsJson() == null ? null
                : JSON.readValue(p.getOptionsJson(), new TypeReference<List<String>>() { })));
        return rows;
    }

    private static Map<String, Object> parse(String json) {
        return json == null ? Map.of() : JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static String yn(String v, String field, String dflt, FieldErrors e) {
        String s = blank(v) ? dflt : v;
        if (!"Y".equals(s) && !"N".equals(s)) {
            e.add(field, "CODE");
        }
        return s;
    }

    private static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    private static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
