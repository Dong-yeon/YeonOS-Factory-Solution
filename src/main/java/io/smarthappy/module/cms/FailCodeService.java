package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.fieldError;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.text;
import static io.smarthappy.module.cms.CmsSupport.yn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsDtos.FailCodeExport;
import io.smarthappy.module.cms.CmsDtos.FailCodeForm;
import io.smarthappy.module.cms.CmsDtos.FailCodePage;
import io.smarthappy.module.cms.CmsDtos.FailCodeRow;
import io.smarthappy.module.cms.CmsDtos.FailCodeSort;
import io.smarthappy.module.cms.CmsDtos.FailCodeValue;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 고장 코드 (cms-fail-code) — docs/33 C1·D1.
 * 현상·원인·조치 3종 회사 코드 + 전역 상위 분류 CMS_FAIL_CAT(원인은 필수) + 설비 클래스 한정(선택). 유형·코드는 고정, 삭제 = 사용 중지.
 */
@Service
@RequiredArgsConstructor
public class FailCodeService {

    static final String PROG = "cms-fail-code";
    static final Pattern FAIL_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    static final Set<String> TYPES = Set.of("SYMPTOM", "CAUSE", "ACTION");
    static final Set<String> CATS = Set.of("MECH", "ELEC", "CTRL", "FLUID", "UTIL", "TOOL", "OPER", "EXT", "UNKNOWN");

    private final CmsMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;
    private final ExcelExportService excel;

    @Transactional(readOnly = true)
    public List<FailCodeRow> list(String codeType, String useYn, String keyword) {
        checkListArgs(codeType, useYn, keyword);
        return mapper.findFailCodes(blank(codeType) ? null : codeType, blank(useYn) ? null : useYn,
                blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)));
    }

    private static void checkListArgs(String codeType, String useYn, String keyword) {
        if (!blank(codeType) && !TYPES.contains(codeType) || !blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn)
                || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link FailCodeSort}) */
    @Transactional(readOnly = true)
    public FailCodePage grid(String codeType, String useYn, String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        checkListArgs(codeType, useYn, keyword);
        String t = blank(codeType) ? null : codeType;
        String u = blank(useYn) ? null : useYn;
        String k = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        PageQuery pq = PageQuery.parse(page, size, sort, total, FailCodeSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("codeType,asc", "sortNo,asc", "failCd,asc"));
        List<FailCodeRow> rows = mapper.findFailCodePage(t, u, k, pq);
        return new FailCodePage(rows, pq.isWithTotal() ? mapper.countFailCodes(t, u, k) : null);
    }

    /** grid 열(화면 고장 코드 표와 같은 칸 + 순서) */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.code("codeType", "cms.fail.col.codeType", "CMS_FAIL_TYPE", 80).withSort(),
            ColumnSpec.text("failCd", "cms.fail.col.failCd", 110).withSort(),
            ColumnSpec.text("failNm", "cms.fail.col.failNm", 180).withSort(),
            ColumnSpec.code("failCat", "cms.fail.col.failCat", "CMS_FAIL_CAT", 110).withSort(),
            ColumnSpec.text("equipClassCd", "cms.fail.col.equipClass", 110).withSort(),
            ColumnSpec.of("sortNo", "cms.col.sortNo", ColumnType.INT, 60).withSort(),
            ColumnSpec.of("useYn", "cms.col.useYn", ColumnType.YN, 70).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 엑셀(C16) — grid 와 같은 조건·정렬(허용 목록)·보이는 열. 유형·분류는 코드 이름, 사용은 공용 YN(사용/미사용) */
    @Transactional(readOnly = true)
    public long export(FailCodeExport req, HttpServletResponse response) throws IOException {
        FailCodeExport r = req == null ? new FailCodeExport(null, null, null, null, null) : req;
        checkListArgs(r.codeType(), r.useYn(), r.keyword());
        List<ColumnSpec> cols = CmsSupport.exportColumns(GRID_COLUMNS, r.columns(), Map.of());
        String t = blank(r.codeType()) ? null : r.codeType();
        String u = blank(r.useYn()) ? null : r.useYn();
        String k = blank(r.keyword()) ? null : like(r.keyword().strip().toUpperCase(Locale.ROOT));
        PageQuery pq = PageQuery.sortOnly(r.sort(), FailCodeSort.values(), List.of("codeType,asc", "sortNo,asc", "failCd,asc"));
        List<String> keys = new ArrayList<>();
        if (t != null) {
            keys.add("codeType");
        }
        if (u != null) {
            keys.add("useYn");
        }
        if (k != null) {
            keys.add("keyword");
        }
        return excel.export(response, new ExportJob(PROG, "prog.cmsFailCode", cols, mapper.countFailCodes(t, u, k), keys),
                w -> mapper.findFailCodePage(t, u, k, pq).forEach(w));
    }

    @Transactional
    public long create(FailCodeForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_FAIL_CODE_CREATE");
        FailCodeValue f = validate(in, null);
        try {
            return mapper.insertFailCode(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("failCd", "DUPLICATE");
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public void update(long failId, FailCodeForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FailCodeRow cur = mapper.findFailCode(failId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        try {
            if (mapper.updateFailCode(failId, validate(in, cur), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private FailCodeValue validate(FailCodeForm in, FailCodeRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type = blank(in.codeType()) ? null : in.codeType().strip();
        String cd = blank(in.failCd()) ? null : in.failCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null) {
            if (type != null && !type.equals(cur.getCodeType())) {
                e.add("codeType", "FIXED");
            }
            if (cd != null && !cd.equals(cur.getFailCd())) {
                e.add("failCd", "FIXED");
            }
            type = cur.getCodeType();
            cd = cur.getFailCd();
        } else {
            if (type == null) {
                e.add("codeType", "REQUIRED");
            } else if (!TYPES.contains(type)) {
                e.add("codeType", "CODE");
            }
            if (cd == null) {
                e.add("failCd", "REQUIRED");
            } else if (!FAIL_CD.matcher(cd).matches()) {
                e.add("failCd", "FORMAT");
            }
        }
        String nm = text(in.failNm(), "failNm", 100, true, e);
        String cat = blank(in.failCat()) ? null : in.failCat().strip();
        if (cat != null && !CATS.contains(cat)) {
            e.add("failCat", "CODE");
        } else if (cat == null && "CAUSE".equals(type)) {
            e.add("failCat", "REQUIRED");
        }
        Long classId = null;
        if (!blank(in.equipClassCd())) {
            String ccd = in.equipClassCd().strip().toUpperCase(Locale.ROOT);
            classId = ccd.length() > 30 ? null : mapper.findClassId(ccd, false);
            boolean same = cur != null && classId != null && classId.equals(cur.getEquipClassId());
            if (classId == null) {
                e.add("equipClassCd", "NO_SUCH_CODE");
            } else if (!same && mapper.findClassId(ccd, true) == null) {
                e.add("equipClassCd", "INACTIVE");   // 사용 중지 클래스는 새로 한정하지 않는다(이미 걸린 것은 둔다)
            }
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String use = yn(in.useYn(), "Y", "useYn", e);
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new FailCodeValue(type, cd, nm, cat, classId, sort, use, remark, cur == null ? null : in.rowVer());
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_cms_fail_code_class", "fk_cms_fail_code_class_comp" -> fieldError("equipClassCd", "NO_SUCH_CODE");
            case "ck_cms_fail_code_cause_cat" -> fieldError("failCat", "REQUIRED");
            default -> e;
        };
    }
}
