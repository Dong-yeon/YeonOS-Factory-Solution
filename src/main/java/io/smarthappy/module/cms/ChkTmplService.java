package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.decimal;
import static io.smarthappy.module.cms.CmsSupport.fieldError;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.text;
import static io.smarthappy.module.cms.CmsSupport.yn;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsPmDtos.ItemForm;
import io.smarthappy.module.cms.CmsPmDtos.ItemRow;
import io.smarthappy.module.cms.CmsPmDtos.ItemValue;
import io.smarthappy.module.cms.CmsPmDtos.TmplExport;
import io.smarthappy.module.cms.CmsPmDtos.TmplForm;
import io.smarthappy.module.cms.CmsPmDtos.TmplPage;
import io.smarthappy.module.cms.CmsPmDtos.TmplRow;
import io.smarthappy.module.cms.CmsPmDtos.TmplSort;
import io.smarthappy.module.cms.CmsPmDtos.TmplValue;
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
 * 점검표 (cms-chk-tmpl) — docs/33 4.3, D8. 리비전 없음(점검 실행이 스냅샷). 항목은 한 벌 저장.
 */
@Service
@RequiredArgsConstructor
public class ChkTmplService {

    static final String PROG = "cms-chk-tmpl";
    static final Pattern TMPL_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    static final Set<String> JUDGES = Set.of("OKNG", "NUM", "TEXT");
    static final int MAX_ITEMS = 200;

    private final CmsPmMapper mapper;
    private final CmsMapper cms;
    private final InboxService inbox;
    private final PolicyService policies;
    private final ExcelExportService excel;
    private final MessageSource messages;

    @Transactional(readOnly = true)
    public List<TmplRow> list(String useYn, String keyword) {
        checkListArgs(useYn, keyword);
        return mapper.findTmpls(blank(useYn) ? null : useYn, blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)));
    }

    private static void checkListArgs(String useYn, String keyword) {
        if (!blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link TmplSort}) */
    @Transactional(readOnly = true)
    public TmplPage grid(String useYn, String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        checkListArgs(useYn, keyword);
        String u = blank(useYn) ? null : useYn;
        String k = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        PageQuery pq = PageQuery.parse(page, size, sort, total, TmplSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("tmplCd,asc"));
        return new TmplPage(mapper.findTmplPage(u, k, pq), pq.isWithTotal() ? mapper.countTmpls(u, k) : null);
    }

    /** grid 열(화면 점검표 표와 같은 칸). 대상 종류는 화면이 이름으로 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("tmplCd", "cms.fail.col.failCd", 110).withSort(),
            ColumnSpec.text("tmplNm", "cms.fail.col.failNm", 180).withSort(),
            ColumnSpec.text("targetKind", "cms.tmpl.col.targetKind", 80).withSort(),
            ColumnSpec.text("equipClassCd", "cms.fail.col.equipClass", 110).withSort(),
            ColumnSpec.of("itemCnt", "cms.tmpl.col.itemCnt", ColumnType.INT, 60),
            ColumnSpec.of("planCnt", "cms.tmpl.col.planCnt", ColumnType.INT, 60),
            ColumnSpec.of("useYn", "cms.col.useYn", ColumnType.YN, 70).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 엑셀 열 바꿈 — 대상 종류는 화면처럼 이름(설비·금형/지그) */
    static final Map<String, List<ColumnSpec>> EXPORT_REPLACE = Map.of(
            "targetKind", List.of(ColumnSpec.text("targetKindNm", "cms.tmpl.col.targetKind", 80)));

    /** 엑셀(C23) — grid 와 같은 조건·정렬(허용 목록)·보이는 열 */
    @Transactional(readOnly = true)
    public long export(TmplExport req, HttpServletResponse response) throws IOException {
        TmplExport r = req == null ? new TmplExport(null, null, null, null) : req;
        checkListArgs(r.useYn(), r.keyword());
        List<ColumnSpec> cols = CmsSupport.exportColumns(GRID_COLUMNS, r.columns(), EXPORT_REPLACE);
        String u = blank(r.useYn()) ? null : r.useYn();
        String k = blank(r.keyword()) ? null : like(r.keyword().strip().toUpperCase(Locale.ROOT));
        PageQuery pq = PageQuery.sortOnly(r.sort(), TmplSort.values(), List.of("tmplCd,asc"));
        List<String> keys = new ArrayList<>();
        if (u != null) {
            keys.add("useYn");
        }
        if (k != null) {
            keys.add("keyword");
        }
        Locale locale = LocaleContextHolder.getLocale();
        return excel.export(response, new ExportJob(PROG, "prog.cmsChkTmpl", cols, mapper.countTmpls(u, k), keys),
                w -> mapper.findTmplPage(u, k, pq).forEach(t -> {
                    t.setTargetKindNm(t.getTargetKind() == null ? null
                            : messages.getMessage("js.cms.kind." + t.getTargetKind(), null, t.getTargetKind(), locale));
                    w.accept(t);
                }));
    }

    @Transactional(readOnly = true)
    public TmplRow get(long tmplId) {
        TmplRow t = mapper.findTmpl(tmplId);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return t;
    }

    @Transactional(readOnly = true)
    public List<ItemRow> items(long tmplId) {
        get(tmplId);
        return mapper.findItems(tmplId);
    }

    @Transactional
    public long create(TmplForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_CHK_TMPL_CREATE");
        TmplValue v = validate(in, null);
        try {
            return mapper.insertTmpl(v, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("tmplCd", "DUPLICATE");
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public void update(long tmplId, TmplForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        TmplRow cur = get(tmplId);
        try {
            if (mapper.updateTmpl(tmplId, validate(in, cur), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private TmplValue validate(TmplForm in, TmplRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = blank(in.tmplCd()) ? null : in.tmplCd().strip().toUpperCase(Locale.ROOT);
        String kind = blank(in.targetKind()) ? (cur == null ? "EQUIP" : cur.getTargetKind()) : in.targetKind().strip();
        if (cur != null) {
            if (cd != null && !cd.equals(cur.getTmplCd())) {
                e.add("tmplCd", "FIXED");
            }
            if (!kind.equals(cur.getTargetKind())) {
                e.add("targetKind", "FIXED");
            }
            cd = cur.getTmplCd();
        } else if (cd == null) {
            e.add("tmplCd", "REQUIRED");
        } else if (!TMPL_CD.matcher(cd).matches()) {
            e.add("tmplCd", "FORMAT");
        }
        if (!"EQUIP".equals(kind) && !"ASSET".equals(kind)) {
            e.add("targetKind", "CODE");
        }
        String nm = text(in.tmplNm(), "tmplNm", 100, true, e);
        Long classId = null;
        if (!blank(in.equipClassCd())) {
            String ccd = in.equipClassCd().strip().toUpperCase(Locale.ROOT);
            classId = ccd.length() > 30 ? null : cms.findClassId(ccd, false);
            boolean same = cur != null && classId != null && classId.equals(cur.getEquipClassId());
            if (classId == null) {
                e.add("equipClassCd", "NO_SUCH_CODE");
            } else if (!"EQUIP".equals(kind)) {
                e.add("equipClassCd", "CODE");   // 클래스 한정은 설비 점검표만
            } else if (!same && cms.findClassId(ccd, true) == null) {
                e.add("equipClassCd", "INACTIVE");
            }
        }
        String use = yn(in.useYn(), "Y", "useYn", e);
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new TmplValue(cd, nm, kind, classId, use, remark, cur == null ? null : in.rowVer());
    }

    /**
     * 항목 한 벌 저장 — 머리 rowVer 로 동시 편집 409. id 가 있으면 수정(바뀐 행만), 없으면 추가, 보내지 않은 기존 행은 삭제.
     * 다른 점검표의 id 가 섞이면 400. 칸 오류는 행 키 = 보낸 순서(0부터)
     */
    @Transactional
    public void saveItems(long tmplId, Integer rowVer, List<ItemForm> rows) {
        LoginUser me = me();
        if (rowVer == null || rows == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        get(tmplId);
        if (rows.size() > MAX_ITEMS) {
            throw fieldError("items", "TOO_MANY_ITEMS", MAX_ITEMS);
        }
        Map<Long, ItemRow> current = new HashMap<>();
        mapper.findItems(tmplId).forEach(i -> current.put(i.getItemId(), i));
        FieldErrors e = FieldErrors.create();
        Set<Integer> seqs = new HashSet<>();
        Set<Long> kept = new HashSet<>();
        List<ItemValue> values = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ItemForm f = rows.get(i);
            FieldErrors.RowScope r = e.row(String.valueOf(i));
            if (f == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            if (f.itemId() != null) {
                if (!current.containsKey(f.itemId()) || !kept.add(f.itemId())) {
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);   // 다른 점검표 id·같은 id 두 번
                }
            }
            values.add(item(f, r, seqs));
        }
        e.throwIfAny();
        if (mapper.touchTmpl(tmplId, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        for (Long id : current.keySet()) {
            if (!kept.contains(id)) {
                mapper.deleteItem(tmplId, id);
            }
        }
        for (ItemValue v : values) {
            if (v.itemId() == null) {
                mapper.insertItem(tmplId, v, me.loginId());
            } else if (changed(current.get(v.itemId()), v)) {
                mapper.updateItem(tmplId, v, me.loginId());
            }
        }
    }

    private static ItemValue item(ItemForm f, FieldErrors.RowScope r, Set<Integer> seqs) {
        FieldErrors one = FieldErrors.create();
        if (f.seq() == null) {
            one.add("seq", "REQUIRED");
        } else if (f.seq() < 1 || f.seq() > 9999) {
            one.add("seq", "RANGE", 1, 9999);
        } else if (!seqs.add(f.seq())) {
            one.add("seq", "DUPLICATE");
        }
        String nm = text(f.itemNm(), "itemNm", 200, true, one);
        String method = text(f.methodText(), "methodText", 500, false, one);
        String judge = blank(f.judgeType()) ? "OKNG" : f.judgeType().strip();
        if (!JUDGES.contains(judge)) {
            one.add("judgeType", "CODE");
        }
        int scale = f.scale() == null ? 2 : f.scale();
        if (scale < 0 || scale > 6) {
            one.add("scale", "RANGE", 0, 6);
            scale = 6;
        }
        BigDecimal lsl = null;
        BigDecimal usl = null;
        if ("NUM".equals(judge)) {
            lsl = decimal(f.lsl(), "lsl", scale, one);
            usl = decimal(f.usl(), "usl", scale, one);
            if (blank(f.lsl()) && blank(f.usl())) {
                one.add("lsl", "CHK_LIMIT");
            } else if (lsl != null && usl != null && lsl.compareTo(usl) >= 0) {
                one.add("usl", "CHK_RANGE");
            }
        } else if (!blank(f.lsl()) || !blank(f.usl())) {
            one.add("lsl", "CHK_NO_SPEC");
        }
        String unit = text(f.unitNm(), "unitNm", 20, false, one);
        String req = yn(f.requiredYn(), "Y", "requiredYn", one);
        one.list().forEach(x -> r.add(x.field(), x.code(), x.args()));
        return new ItemValue(f.itemId(), f.seq() == null ? 0 : f.seq(), nm, method, judge, lsl, usl, unit, scale, req);
    }

    private static boolean changed(ItemRow c, ItemValue v) {
        return c.getSeq() != v.seq() || !Objects.equals(c.getItemNm(), v.itemNm()) || !Objects.equals(c.getMethodText(), v.methodText())
                || !Objects.equals(c.getJudgeType(), v.judgeType()) || !same(c.getLsl(), v.lsl()) || !same(c.getUsl(), v.usl())
                || !Objects.equals(c.getUnitNm(), v.unitNm()) || c.getScale() != v.scale()
                || !Objects.equals(c.getRequiredYn(), v.requiredYn());
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_cms_chk_tmpl_class", "fk_cms_chk_tmpl_class_comp" -> fieldError("equipClassCd", "NO_SUCH_CODE");
            default -> e;
        };
    }
}
