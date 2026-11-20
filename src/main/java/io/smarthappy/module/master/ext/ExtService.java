package io.smarthappy.module.master.ext;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.ext.ExtDtos.CodeMapForm;
import io.smarthappy.module.master.ext.ExtDtos.CodeMapRow;
import io.smarthappy.module.master.ext.ExtDtos.IdMapForm;
import io.smarthappy.module.master.ext.ExtDtos.IdMapRow;
import io.smarthappy.module.master.ext.ExtDtos.IdMapValue;
import io.smarthappy.module.master.ext.ExtDtos.SystemForm;
import io.smarthappy.module.master.ext.ExtDtos.SystemRow;
import io.smarthappy.module.master.ext.ExtDtos.SystemSort;
import io.smarthappy.module.master.ext.ExtDtos.SystemValue;
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

/**
 * 외부 시스템·코드 매핑(mst-ext-system)·외부 ID 매핑 — P1 7a, docs/22.
 *
 * - 업무 테이블에 외부 코드 컬럼을 두지 않는다(docs/07 하지 말 것 12). 외부 코드는 mst_ext_id_map·if_code_map 에만
 * - 외부 ID 는 시스템 안에서 1:1 (사용 중 행만, 부분 유일 인덱스). 잘못 넣은 매핑은 사용 중지 후 새로
 * - 외부 코드는 대소문자를 바꾸지 않는다(ERP 코드가 구분할 수 있다). 코드 매핑의 외부 표기는 대소문자 무시 유일
 * - 회사·대상·내부 코드 검사는 DB 트리거가 최종, 서비스는 칸 오류로 먼저 알린다
 */
@Service
@RequiredArgsConstructor
public class ExtService {

    public static final String PROG = "mst-ext-system";
    static final Pattern SYSTEM_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    /** 제어 문자 C0·C1(DB [[:cntrl:]] 와 같은 범위) — 7단계 QA 결함 #9 */
    static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    /** 앞뒤 유니코드 공백(NBSP 등)·폭 없는 문자 — 같은 모양의 다른 코드를 막는다(결함 #8) */
    static final Pattern EDGE_SPACE = Pattern.compile("^[\\s\\p{Z}]|[\\s\\p{Z}]$");
    static final Pattern FORMAT_CHAR = Pattern.compile("\\p{Cf}");
    static final Set<String> SYSTEM_TYPES = Set.of("ERP", "CUSTOMER", "SUPPLIER", "OTHER");
    static final Set<String> CONN_MODES = Set.of("DIRECT", "EDGE", "AGENT");
    static final Set<String> CODE_TYPES = Set.of("UOM", "ITEM_TYPE", "WH");
    static final Set<String> ENTITY_TYPES = Set.of("ITEM", "CUST");

    private final ExtMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;

    /** 시스템 그리드 기본 정렬 = 기존 목록 순서 */
    static final List<String> SYSTEM_SORT = List.of("systemType,asc", "systemCd,asc");

    static final List<ColumnSpec> SYSTEM_COLUMNS = List.of(
            ColumnSpec.text("systemCd", "extSys.col.systemCd", 110).withSort().withPriority(1),
            ColumnSpec.text("systemNm", "extSys.col.systemNm", 160).withSort().withPriority(1).withFlex().withMinWidth(100),
            ColumnSpec.code("systemType", "extSys.col.systemType", "EXT_SYSTEM_TYPE", 90).withSort().withPriority(2),
            ColumnSpec.text("custNm", "extSys.col.cust", 130).withPriority(3),
            ColumnSpec.of("mapCnt", "extSys.col.mapCnt", ColumnSpec.ColumnType.INT, 80).withSort().withPriority(3),
            ColumnSpec.of("codeMapCnt", "extSys.col.codeMapCnt", ColumnSpec.ColumnType.INT, 80).withSort().withPriority(3),
            ColumnSpec.of("useYn", "extSys.col.useYn", ColumnSpec.ColumnType.YN, 70).withPriority(2));

    public record SystemPage(List<SystemRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 시스템

    @Transactional(readOnly = true)
    public List<SystemRow> systems(String useYn) {
        if (!blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findSystems(blank(useYn) ? null : useYn);
    }

    /** 시스템 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 기존 목록과 같은 조건 + 페이지·정렬·건수 */
    @Transactional(readOnly = true)
    public SystemPage systemGrid(String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, SystemSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), SYSTEM_SORT);
        if (!blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String yn = blank(useYn) ? null : useYn;
        return new SystemPage(mapper.findSystemPage(yn, pq), pq.isWithTotal() ? mapper.countSystems(yn) : null);
    }

    public List<ColumnSpec> systemColumns() {
        return SYSTEM_COLUMNS;
    }

    @Transactional
    public long createSystem(SystemForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_EXT_SYSTEM_CREATE");
        SystemValue v = validateSystem(in, null);
        try {
            return mapper.insertSystem(v, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("systemCd", "DUPLICATE");
        }
    }

    /** 수정 — 코드는 바꾸지 않는다(어댑터 설정·송신 이력이 코드로 읽는다) */
    @Transactional
    public void updateSystem(long systemId, SystemForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        SystemRow cur = mapper.findSystem(systemId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.updateSystem(systemId, validateSystem(in, cur), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private SystemValue validateSystem(SystemForm in, SystemRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = blank(in.systemCd()) ? null : in.systemCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null) {
            if (cd != null && !cd.equals(cur.getSystemCd())) {
                e.add("systemCd", "FIXED");
            }
            cd = cur.getSystemCd();
        } else if (cd == null) {
            e.add("systemCd", "REQUIRED");
        } else if (!SYSTEM_CD.matcher(cd).matches()) {
            e.add("systemCd", "FORMAT");
        }
        String nm = text(in.systemNm(), "systemNm", 100, true, e);
        String type = in.systemType();
        if (blank(type)) {
            e.add("systemType", "REQUIRED");
        } else if (!SYSTEM_TYPES.contains(type)) {
            e.add("systemType", "CODE");
        }
        Long custId = null;
        if (!blank(in.custCd())) {
            if (!"CUSTOMER".equals(type) && !"SUPPLIER".equals(type)) {
                e.add("custCd", "CUST_TYPE");      // 거래처 연결은 고객·협력사 시스템만
            } else {
                custId = in.custCd().length() > 30 ? null : mapper.findCustId(in.custCd().strip().toUpperCase(Locale.ROOT));
                if (custId == null) {
                    e.add("custCd", "NO_SUCH_CODE");
                } else if ((cur == null || !custId.equals(cur.getCustId())) && !"Y".equals(mapper.custUseYn(custId))) {
                    e.add("custCd", "INACTIVE");       // 새로 연결·다른 거래처로 바꿀 때만(그대로는 통과) — 결함 #7
                }
            }
        }
        String use = yn(in.useYn(), e);
        // 연결 방식(docs/65 3장) — 비우면 신규는 DIRECT, 수정은 지금 값
        String conn = blank(in.connMode()) ? (cur == null || cur.getConnMode() == null ? "DIRECT" : cur.getConnMode()) : in.connMode();
        if (!CONN_MODES.contains(conn)) {
            e.add("connMode", "CODE");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new SystemValue(cd, nm, type, custId, use, conn, remark, cur == null ? null : in.rowVer());
    }

    // ------------------------------------------------------------------ 코드 매핑

    @Transactional(readOnly = true)
    public List<CodeMapRow> codeMaps(long systemId) {
        system(systemId);
        return mapper.findCodeMaps(systemId);
    }

    @Transactional
    public long createCodeMap(long systemId, CodeMapForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_CODE_MAP_CREATE");
        if (!"Y".equals(system(systemId).getUseYn())) {
            throw fieldError("extCode", "INACTIVE");          // 사용 중지 시스템에 새 매핑 — 결함 #7
        }
        CodeMapForm f = validateCodeMap(in, null);
        try {
            return mapper.insertCodeMap(systemId, f, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw translateCodeMap(e);
        }
    }

    /** 수정 — 유형은 바꾸지 않는다. 외부 표기·내부 코드·사용 여부 */
    @Transactional
    public void updateCodeMap(long codeMapId, CodeMapForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        CodeMapRow cur = mapper.findCodeMap(codeMapId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        CodeMapForm f = validateCodeMap(in, cur);
        if ("Y".equals(f.useYn()) && !"Y".equals(cur.getUseYn()) && !"Y".equals(system(cur.getSystemId()).getUseYn())) {
            throw fieldError("useYn", "INACTIVE");            // 사용 중지 시스템의 매핑 다시 사용 — 결함 #7
        }
        try {
            if (mapper.updateCodeMap(codeMapId, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translateCodeMap(e);
        }
    }

    private CodeMapForm validateCodeMap(CodeMapForm in, CodeMapRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type = cur != null ? cur.getCodeType() : in.codeType();
        if (cur != null && !blank(in.codeType()) && !in.codeType().equals(cur.getCodeType())) {
            e.add("codeType", "FIXED");
        } else if (blank(type)) {
            e.add("codeType", "REQUIRED");
        } else if (!CODE_TYPES.contains(type)) {
            e.add("codeType", "CODE");
        }
        String ext = extText(in.extCode(), "extCode", 50, true, e);
        String use = yn(in.useYn(), e);
        String internal = blank(in.intCode()) ? null : in.intCode().strip().toUpperCase(Locale.ROOT);
        if (internal == null) {
            e.add("intCode", "REQUIRED");
        } else if (internal.length() > 30) {
            e.add("intCode", "TOO_LONG", 30);
        } else if (CODE_TYPES.contains(type) && "Y".equals(use)
                && (cur == null || !internal.equals(cur.getIntCode()) || "N".equals(cur.getUseYn()))
                && !mapper.intCodeUsable(type, internal)) {
            e.add("intCode", "NO_SUCH_CODE");
        }
        e.throwIfAny();
        return new CodeMapForm(type, ext, internal, use, cur == null ? null : in.rowVer());
    }

    private static RuntimeException translateCodeMap(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return fieldError("extCode", "DUPLICATE");
        }
        if (PgErrors.constraint(e).filter("fk_if_code_map_int_code"::equals).isPresent()) {
            return fieldError("intCode", "NO_SUCH_CODE");
        }
        return e;
    }

    // ------------------------------------------------------------------ 외부 ID

    @Transactional(readOnly = true)
    public List<IdMapRow> idMaps(String entityType, long internalId) {
        target(entityType, internalId);
        return mapper.findIdMaps(entityType, internalId);
    }

    @Transactional
    public long createIdMap(String entityType, long internalId, IdMapForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_EXT_ID_CREATE");
        target(entityType, internalId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        SystemRow sys = blank(in.systemCd()) || in.systemCd().length() > 20 ? null
                : mapper.findSystemByCd(in.systemCd().strip().toUpperCase(Locale.ROOT));
        if (blank(in.systemCd())) {
            e.add("systemCd", "REQUIRED");
        } else if (sys == null) {
            e.add("systemCd", "NO_SUCH_CODE");
        } else if (!"Y".equals(sys.getUseYn())) {
            e.add("systemCd", "INACTIVE");
        }
        String ext = extText(in.externalId(), "externalId", 100, false, e);
        String nm = text(in.externalNm(), "externalNm", 200, false, e);
        e.throwIfAny();
        try {
            return mapper.insertIdMap(new IdMapValue(sys.getSystemId(), entityType, internalId, ext, nm, "Y", null),
                    me.loginId());
        } catch (DuplicateKeyException ex) {
            throw duplicateIdMap(ex);
        }
    }

    /** 수정 — 시스템·대상은 바꾸지 않는다(트리거도 막는다). 외부 코드 오타 정정·이름·사용 여부 */
    @Transactional
    public void updateIdMap(String entityType, long mapId, IdMapForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        IdMapRow cur = mapper.findIdMap(mapId);
        if (cur == null || !cur.getEntityType().equals(entityType)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        if (!blank(in.systemCd()) && !in.systemCd().strip().equalsIgnoreCase(cur.getSystemCd())) {
            e.add("systemCd", "FIXED");
        }
        String ext = extText(in.externalId(), "externalId", 100, false, e);
        String nm = text(in.externalNm(), "externalNm", 200, false, e);
        String use = yn(in.useYn(), e);
        if ("Y".equals(use) && !"Y".equals(cur.getUseYn()) && !"Y".equals(system(cur.getSystemId()).getUseYn())) {
            e.add("useYn", "INACTIVE");                       // 사용 중지 시스템의 외부 ID 다시 사용 — 결함 #7
        }
        e.throwIfAny();
        try {
            if (mapper.updateIdMap(mapId, new IdMapValue(cur.getSystemId(), entityType, cur.getInternalId(), ext, nm, use,
                    in.rowVer()), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException ex) {
            throw duplicateIdMap(ex);
        }
    }

    /** 같은 외부 코드가 다른 대상에 이미 있음 / 이 대상에 이 시스템 코드가 이미 있음 — 어느 쪽인지 칸으로 */
    private static BusinessException duplicateIdMap(DuplicateKeyException ex) {
        return PgErrors.constraint(ex).filter("uq_mst_ext_id_map_int"::equals).isPresent()
                ? fieldError("systemCd", "ONE_PER_SYSTEM") : fieldError("externalId", "DUPLICATE");
    }

    // ------------------------------------------------------------------ 품목 ERP 품번 (docs/17 9장)

    /** 검증된 ERP 품번 — 붙일 ERP 시스템과 정리된 외부 코드 */
    public record ErpItemCd(long systemId, String externalId) {
    }

    /** 사용 중 ERP 시스템 하나(시스템 코드 순 첫째, ErpMapper.erpItem 과 같은 규칙). 없으면 null */
    @Transactional(readOnly = true)
    public Long erpSystemId() {
        return mapper.findErpSystemId();
    }

    /** 사용 중 ERP 시스템의 품목 매핑 (외부 코드 → 품목 id). 시스템이 없으면 빈 맵 — 엑셀 올리기가 한 번에 읽는다 */
    @Transactional(readOnly = true)
    public Map<String, Long> erpItemMaps(Long systemId) {
        Map<String, Long> m = new java.util.HashMap<>();
        if (systemId != null) {
            mapper.findItemMaps(systemId).forEach(r -> m.put((String) r.get("external_id"),
                    ((Number) r.get("internal_id")).longValue()));
        }
        return m;
    }

    /**
     * 품목 신규 폼의 ERP 품번 검증 — 비었으면 null. 형식은 외부 ID 규칙(대소문자 유지·앞뒤 공백·제어 문자),
     * ERP 시스템이 없으면 NO_ERP_SYSTEM, 다른 품목이 쓰면 DUPLICATE. 오류는 e 에(칸 field)
     */
    @Transactional(readOnly = true)
    public ErpItemCd checkErpItemCd(String raw, String field, FieldErrors e) {
        if (blank(raw)) {
            return null;
        }
        int before = e.list().size();
        String ext = extText(raw, field, 100, false, e);
        if (e.list().size() > before) {
            return null;
        }
        Long sys = mapper.findErpSystemId();
        if (sys == null) {
            e.add(field, "NO_ERP_SYSTEM");
            return null;
        }
        if (mapper.itemExtIdTaken(sys, ext)) {
            e.add(field, "DUPLICATE");
            return null;
        }
        return new ErpItemCd(sys, ext);
    }

    /** 검증한 ERP 품번을 품목에 붙인다(호출한 업무 트랜잭션 안). 그 사이 다른 품목이 같은 코드를 붙였으면 칸 오류 DUPLICATE */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void attachErpItemCd(ErpItemCd v, long itemId, String field, String by) {
        try {
            mapper.insertIdMap(new IdMapValue(v.systemId(), "ITEM", itemId, v.externalId(), null, "Y", null), by);
        } catch (DuplicateKeyException ex) {
            throw fieldError(field, "DUPLICATE");
        }
    }

    private void target(String entityType, long internalId) {
        if (!ENTITY_TYPES.contains(entityType) || !mapper.targetExists(entityType, internalId)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    private SystemRow system(long systemId) {
        SystemRow s = mapper.findSystem(systemId);
        if (s == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return s;
    }

    // ------------------------------------------------------------------ 공통

    /** 외부 ID(ERP·고객 품번) 형식만 — 엑셀 올리기가 행마다 DB 를 보지 않고 쓴다. 빈 칸은 REQUIRED 를 넣으므로 부르기 전에 거른다 */
    public static String cleanExternalId(String v, String field, FieldErrors e) {
        return extText(v, field, 100, false, e);
    }

    /**
     * 외부 코드·표기: 앞뒤 ASCII 공백 제거(대소문자 유지), 제어 문자·폭 없는 문자·남은 앞뒤 유니코드 공백(NBSP)은 FORMAT.
     * ERP 코드(외부 ID)는 바꾸면 안 되므로 정규화하지 않고 거부. 코드 매핑 표기(사람이 쓰는 말)는 NFKC 후 저장 — Ｂｏｘ = Box (결함 #8)
     */
    private static String extText(String v, String field, int max, boolean nfkc, FieldErrors e) {
        if (blank(v)) {
            e.add(field, "REQUIRED");
            return null;
        }
        String s = nfkc ? java.text.Normalizer.normalize(v, java.text.Normalizer.Form.NFKC).strip() : v.strip();
        if (s.isEmpty()) {
            e.add(field, "REQUIRED");
        } else if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        } else if (CONTROL.matcher(s).find() || FORMAT_CHAR.matcher(s).find() || EDGE_SPACE.matcher(s).find()) {
            e.add(field, "FORMAT");
        }
        return s;
    }

    private static String yn(String v, FieldErrors e) {
        String use = blank(v) ? "Y" : v;
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        return use;
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
            e.add(field, "FORMAT");                           // 제어 문자 — 결함 #9
        } else if (s.length() > max) {
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
