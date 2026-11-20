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

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsStateDtos.ReasonExport;
import io.smarthappy.module.cms.CmsStateDtos.ReasonForm;
import io.smarthappy.module.cms.CmsStateDtos.ReasonPage;
import io.smarthappy.module.cms.CmsStateDtos.ReasonRow;
import io.smarthappy.module.cms.CmsStateDtos.ReasonSort;
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
 * 설비 상태 사유 관리 (cms-state-reason) — 표는 MES 6단계 것(cms_state_reason), 화면·API 는 CMS (docs/33 11.4 Q1).
 * 코드·분류는 만든 뒤 고정: 원장 트리거가 구간을 넣을 때 "사유 분류 = 구간 분류" 를 확인했으므로, 분류를 바꾸면 지난 구간이 어긋난다.
 * 계획 여부·이름·순서·사용은 바꿀 수 있다(계획 여부는 KPI 계산 때 읽는다 — 바꾸면 지난 기간 KPI 도 바뀐다, 화면 안내).
 */
@Service
@RequiredArgsConstructor
public class StateReasonService {

    static final String PROG = "cms-state-reason";
    /** 사유가 가질 수 있는 분류 — UNKNOWN(통신 끊김)은 사유가 없다(원장 CHECK) */
    static final Set<String> CATS = Set.of("PRD", "SBY", "ENG", "SDT", "UDT", "NST");
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,29}$");

    private final CmsStateMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;
    private final ExcelExportService excel;
    private final MessageSource messages;

    @Transactional(readOnly = true)
    public List<ReasonRow> list(String keyword, String stateCat, String useYn) {
        checkListArgs(keyword, stateCat, useYn);
        return mapper.findReasons(blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)), blank(stateCat) ? null : stateCat,
                blank(useYn) ? null : useYn);
    }

    private static void checkListArgs(String keyword, String stateCat, String useYn) {
        if (keyword != null && keyword.length() > 100 || !blank(stateCat) && !CATS.contains(stateCat)
                || !blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link ReasonSort}) */
    @Transactional(readOnly = true)
    public ReasonPage grid(String keyword, String stateCat, String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        checkListArgs(keyword, stateCat, useYn);
        String k = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        String c = blank(stateCat) ? null : stateCat;
        String u = blank(useYn) ? null : useYn;
        PageQuery pq = PageQuery.parse(page, size, sort, total, ReasonSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("stateCat,asc", "sortNo,asc", "reasonCd,asc"));
        return new ReasonPage(mapper.findReasonPage(k, c, u, pq), pq.isWithTotal() ? mapper.countReasons(k, c, u) : null);
    }

    /** grid 열(화면 사유 표와 같은 칸) */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.code("stateCat", "cms.stateReason.col.stateCat", "STATE_CAT", 90).withSort(),
            ColumnSpec.text("reasonCd", "cms.stateReason.col.reasonCd", 110).withSort(),
            ColumnSpec.text("reasonNm", "cms.stateReason.col.reasonNm", 180).withSort(),
            ColumnSpec.text("plannedYn", "cms.stateReason.col.planned", 80).withSort(),   // 화면이 Y 만 "계획" 표시(YN 열이면 N 이 "미사용")
            ColumnSpec.of("sortNo", "cms.stateReason.col.sortNo", ColumnType.INT, 60).withSort(),
            ColumnSpec.of("useYn", "cms.col.useYn", ColumnType.YN, 70).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 엑셀 열 바꿈 — 계획은 화면처럼 표시 글자(Y 만) */
    static final Map<String, List<ColumnSpec>> EXPORT_REPLACE = Map.of(
            "plannedYn", List.of(ColumnSpec.text("plannedNm", "cms.stateReason.col.planned", 80)));

    /** 엑셀(C23) — grid 와 같은 조건·정렬(허용 목록)·보이는 열 */
    @Transactional(readOnly = true)
    public long export(ReasonExport req, HttpServletResponse response) throws IOException {
        ReasonExport r = req == null ? new ReasonExport(null, null, null, null, null) : req;
        checkListArgs(r.keyword(), r.stateCat(), r.useYn());
        List<ColumnSpec> cols = CmsSupport.exportColumns(GRID_COLUMNS, r.columns(), EXPORT_REPLACE);
        String k = blank(r.keyword()) ? null : like(r.keyword().strip().toUpperCase(Locale.ROOT));
        String c = blank(r.stateCat()) ? null : r.stateCat();
        String u = blank(r.useYn()) ? null : r.useYn();
        PageQuery pq = PageQuery.sortOnly(r.sort(), ReasonSort.values(), List.of("stateCat,asc", "sortNo,asc", "reasonCd,asc"));
        List<String> keys = new ArrayList<>();
        for (String[] x : new String[][] {{"keyword", k}, {"stateCat", c}, {"useYn", u}}) {
            if (x[1] != null) {
                keys.add(x[0]);
            }
        }
        String mark = messages.getMessage("js.cms.stateReason.plannedMark", null, "Y", LocaleContextHolder.getLocale());
        return excel.export(response, new ExportJob(PROG, "prog.cmsStateReason", cols, mapper.countReasons(k, c, u), keys),
                w -> mapper.findReasonPage(k, c, u, pq).forEach(x -> {
                    x.setPlannedNm("Y".equals(x.getPlannedYn()) ? mark : null);
                    w.accept(x);
                }));
    }

    @Transactional(readOnly = true)
    public ReasonRow get(long reasonId) {
        ReasonRow r = mapper.findReason(reasonId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    @Transactional
    public long create(ReasonForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_STATE_REASON_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ReasonRow r = new ReasonRow();
        if (blank(in.reasonCd())) {
            e.add("reasonCd", "REQUIRED");
        } else {
            String cd = in.reasonCd().strip().toUpperCase(Locale.ROOT);
            if (!CODE.matcher(cd).matches()) {
                e.add("reasonCd", "FORMAT");
            }
            r.setReasonCd(cd);
        }
        if (blank(in.stateCat())) {
            e.add("stateCat", "REQUIRED");
        } else if (!CATS.contains(in.stateCat().strip())) {
            e.add("stateCat", "CODE");
        } else {
            r.setStateCat(in.stateCat().strip());
        }
        apply(r, in, e);
        e.throwIfAny();
        try {
            return mapper.insertReason(r, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("reasonCd", "DUPLICATE");
        }
    }

    @Transactional
    public void update(long reasonId, ReasonForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.lockReason(reasonId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ReasonRow cur = mapper.findReason(reasonId);
        FieldErrors e = FieldErrors.create();
        if (!blank(in.reasonCd()) && !in.reasonCd().strip().toUpperCase(Locale.ROOT).equals(cur.getReasonCd())) {
            e.add("reasonCd", "FIXED");
        }
        if (!blank(in.stateCat()) && !in.stateCat().strip().equals(cur.getStateCat())) {
            e.add("stateCat", "FIXED");
        }
        apply(cur, in, e);
        e.throwIfAny();
        cur.setRowVer(in.rowVer());
        if (mapper.updateReason(cur, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private static void apply(ReasonRow r, ReasonForm in, FieldErrors e) {
        r.setReasonNm(text(in.reasonNm(), "reasonNm", 100, true, e));
        r.setPlannedYn(yn(in.plannedYn(), "N", "plannedYn", e));
        r.setUseYn(yn(in.useYn(), "Y", "useYn", e));
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 9999) {
            e.add("sortNo", "RANGE", 0, 9999);
        }
        r.setSortNo(sort);
    }
}
