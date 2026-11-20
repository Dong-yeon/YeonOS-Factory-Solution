package io.smarthappy.module.master.routing;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.master.routing.RoutingDtos.ProcForm;
import io.smarthappy.module.master.routing.RoutingDtos.ProcRow;
import io.smarthappy.module.master.routing.RoutingDtos.ProcSort;
import io.smarthappy.platform.code.CodeService;
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
 * 공정 관리 (mst-proc) — P1 5단계, docs/20.
 *
 * 사용 중지는 확정 라우팅이 써도 허용(새 참조만 막힘 — docs/17 2장). 외주 여부는 확정 라우팅이 쓰면 바꾸지 않는다
 * (자체 공정 = 작업장 필수 규칙이 확정본 뒤에서 바뀐다) — DB 트리거 ck_mst_proc_outsrc_in_use 가 최종 판정.
 */
@Service
@RequiredArgsConstructor
public class ProcService {

    static final String PROG = "mst-proc";
    static final Pattern PROC_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    /** 공정 분류 = 공통코드 PROC_CAT(시스템 9종 + 회사 코드, docs/68). 흐름도 분기는 INSPECT·PACK 만 */
    static final String CAT_GRP = "PROC_CAT";

    /** 그리드 기본 정렬 = 기존 목록과 같은 순서 */
    static final List<String> DEFAULT_SORT = List.of("sortNo,asc", "procCd,asc");

    /** 외주 여부는 YN(사용/미사용) 글자가 아니라 외주/자체 — 이름은 화면이 붙인다(format) */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("procCd", "proc.col.procCd", 110).withSort().withPriority(1),
            ColumnSpec.text("procNm", "proc.col.procNm", 180).withSort().withPriority(1).withFlex().withMinWidth(110),
            ColumnSpec.code("procCat", "proc.col.procCat", "PROC_CAT", 90).withSort().withPriority(2),
            ColumnSpec.text("outsrcYn", "proc.col.outsrcYn", 70).withPriority(2),
            ColumnSpec.of("routingCnt", "proc.col.routingCnt", ColumnSpec.ColumnType.INT, 90).withSort().withPriority(3),
            ColumnSpec.of("sortNo", "proc.col.sortNo", ColumnSpec.ColumnType.INT, 60).withSort().withPriority(3),
            ColumnSpec.of("useYn", "proc.col.useYn", ColumnSpec.ColumnType.YN, 70).withPriority(2));

    private final RoutingMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;
    private final CodeService codes;

    public record Page(List<ProcRow> list, Long total) {
    }

    @Transactional(readOnly = true)
    public List<ProcRow> list(String useYn) {
        if (useYn != null && !useYn.isBlank() && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findProcs(useYn == null || useYn.isBlank() ? null : useYn);
    }

    /** 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 기존 목록 API 와 같은 조건 + 빠른 찾기(코드·이름) + 페이지·정렬·건수 */
    @Transactional(readOnly = true)
    public Page grid(String useYn, String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, ProcSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        if (useYn != null && !useYn.isBlank() && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String yn = useYn == null || useYn.isBlank() ? null : useYn;
        String kw = null;
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.strip();
            if (k.length() > 100) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            kw = "%" + BomService.likeEscape(k) + "%";
        }
        return new Page(mapper.findProcPage(yn, kw, pq), pq.isWithTotal() ? mapper.countProcs(yn, kw) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional
    public long create(ProcForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_PROC_CREATE");
        ProcForm f = validate(in, null);
        try {
            return mapper.insertProc(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("procCd", "DUPLICATE");
        }
    }

    /** 수정 — 코드는 바꾸지 않는다(라우팅·실적이 코드로 읽힌다) */
    @Transactional
    public void update(long procId, ProcForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ProcRow cur = mapper.findProc(procId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ProcForm f = validate(in, cur);
        try {
            if (mapper.updateProc(procId, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            if (PgErrors.constraint(e).filter("ck_mst_proc_outsrc_in_use"::equals).isPresent()) {
                throw fieldError("outsrcYn", "OUTSRC_IN_USE");
            }
            throw e;
        }
    }

    private ProcForm validate(ProcForm in, ProcRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = cur != null ? cur.getProcCd() : blank(in.procCd()) ? null : in.procCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null && !blank(in.procCd()) && !cur.getProcCd().equals(in.procCd().strip().toUpperCase(Locale.ROOT))) {
            e.add("procCd", "FIXED");
        } else if (cd == null) {
            e.add("procCd", "REQUIRED");
        } else if (!PROC_CD.matcher(cd).matches()) {
            e.add("procCd", "FORMAT");
        }
        String nm = text(in.procNm(), "procNm", 100, true, e);
        String cat = blank(in.procCat()) ? null : in.procCat();
        if (cat == null) {
            e.add("procCat", "REQUIRED");
        } else if (!codes.usable(CAT_GRP, cat)) {
            e.add("procCat", "CODE");
        }
        String outsrc = blank(in.outsrcYn()) ? "N" : in.outsrcYn();
        if (!"Y".equals(outsrc) && !"N".equals(outsrc)) {
            e.add("outsrcYn", "CODE");
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new ProcForm(cd, nm, cat, outsrc, sort, use, remark, cur == null ? null : in.rowVer());
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
