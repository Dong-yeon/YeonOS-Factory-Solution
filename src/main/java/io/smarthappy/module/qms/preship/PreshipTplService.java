package io.smarthappy.module.qms.preship;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.preship.PreshipDtos.LineSave;
import io.smarthappy.module.qms.preship.PreshipDtos.TplForm;
import io.smarthappy.module.qms.preship.PreshipDtos.TplLine;
import io.smarthappy.module.qms.preship.PreshipDtos.TplLineForm;
import io.smarthappy.module.qms.preship.PreshipDtos.TplPage;
import io.smarthappy.module.qms.preship.PreshipDtos.TplRow;
import io.smarthappy.module.qms.preship.PreshipDtos.TplSave;
import io.smarthappy.module.qms.preship.PreshipDtos.TplSort;
import io.smarthappy.module.qms.preship.PreshipDtos.TplView;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 출하 전 검수 양식 (qms-preship-tpl, docs/78 3·4장) — 대상(품목 또는 품목군 하나) + 항목 한 벌.
 * 사용 중 양식은 대상당 하나(부분 유일 → 칸 오류 DUPLICATE). 항목은 지우지 않고, 보낸 목록에 없는 항목은 사용 중지.
 * 이미 시작한 검수는 항목 사본을 가져 양식을 고쳐도 바뀌지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PreshipTplService {

    static final String PROG = "qms-preship-tpl";
    static final int MAX_LINES = 200;
    static final List<String> DEFAULT_SORT = List.of("tplCd,asc");
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("tplCd", "qms.preship.col.tplCd", 120).withSort().withPriority(1),
            ColumnSpec.text("tplNm", "qms.preship.col.tplNm", 200).withSort().withPriority(1).withFlex().withMinWidth(140),
            ColumnSpec.labels("targetType", "qms.preship.col.targetType", "qms.preship.target.", 80, "ITEM", "CLASS").withPriority(2),
            ColumnSpec.text("targetCd", "qms.preship.col.targetCd", 120).withSort().withPriority(1),
            ColumnSpec.text("targetNm", "qms.preship.col.targetNm", 160).withPriority(3),
            ColumnSpec.of("lineCnt", "qms.preship.col.lineCnt", ColumnSpec.ColumnType.INT, 70).withPriority(2),
            ColumnSpec.of("useYn", "qms.preship.col.useYn", ColumnSpec.ColumnType.YN, 70).withPriority(2),
            ColumnSpec.of("updatedAt", "mstChar.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));

    private final PreshipMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;

    @Transactional(readOnly = true)
    public TplPage grid(String keyword, String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, TplSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        String yn = yn(useYn);
        String kw = like(keyword);
        return new TplPage(mapper.findTplPage(kw, yn, pq), pq.isWithTotal() ? mapper.countTpl(kw, yn) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public TplView get(long id) {
        TplRow h = mapper.findTpl(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new TplView(h, mapper.findTplLines(id));
    }

    @Transactional
    public long create(TplForm in) {
        LoginUser me = PreshipService.me();
        inbox.claimRequestKey("QMS_PRESHIP_TPL_CREATE");
        TplSave f = validateHead(in, false);
        List<LineSave> lines = validateLines(in.lines(), Map.of());
        long id;
        try {
            id = mapper.insertTpl(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        lines.forEach(l -> mapper.insertTplLine(id, l, me.loginId()));
        return id;
    }

    /** 머리 rowVer 로 동시 편집 409, 항목은 id 로 추가·수정, 빠진 항목은 사용 중지(다른 양식의 id 는 400) */
    @Transactional
    public void update(long id, TplForm in) {
        LoginUser me = PreshipService.me();
        TplSave f = validateHead(in, true);
        if (mapper.findTpl(id) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        Map<Long, TplLine> existing = mapper.findTplLines(id).stream().collect(Collectors.toMap(TplLine::getTplLineId, Function.identity()));
        List<LineSave> lines = validateLines(in.lines(), existing);
        try {
            if (mapper.updateTpl(id, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        // 빠진 항목만 사용 중지 — 남길 id = 보낸 기존 id + 이번에 새로 넣은 id
        List<Long> keep = new ArrayList<>();
        for (LineSave l : lines) {
            if (l.tplLineId() == null) {
                keep.add(mapper.insertTplLine(id, l, me.loginId()));     // 새 항목도 남긴다 — 아래 사용 중지에서 빠지게
            } else {
                keep.add(l.tplLineId());
                mapper.updateTplLine(id, l, me.loginId());
            }
        }
        mapper.disableTplLines(id, keep, me.loginId());
    }

    private TplSave validateHead(TplForm in, boolean update) {
        if (in == null || (update && in.rowVer() == null)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = VisibleText.reason(in.tplCd(), "tplCd", 1, 30, e);
        if (cd != null && !cd.matches("^[A-Za-z0-9_.-]{1,30}$")) {
            e.add("tplCd", "FORMAT");
        }
        String nm = VisibleText.reason(in.tplNm(), "tplNm", 1, 100, e);
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        String use = VisibleText.isBlank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String itemCd = VisibleText.isBlank(in.itemCd()) ? null : in.itemCd().strip().toUpperCase(java.util.Locale.ROOT);
        String classCd = VisibleText.isBlank(in.classCd()) ? null : in.classCd().strip().toUpperCase(java.util.Locale.ROOT);
        Long itemId = null;
        Long classId = null;
        if ((itemCd == null) == (classCd == null)) {
            e.add("itemCd", "PRESHIP_TARGET");
        } else if (itemCd != null) {
            itemId = mapper.itemIdByCd(itemCd);
            if (itemId == null) {
                e.add("itemCd", "NO_SUCH_CODE");
            }
        } else {
            classId = mapper.classIdByCd(classCd);
            if (classId == null) {
                e.add("classCd", "NO_SUCH_CODE");
            }
        }
        e.throwIfAny();
        return new TplSave(cd == null ? null : cd.toUpperCase(java.util.Locale.ROOT), nm, itemId, classId, remark, use, in.rowVer());
    }

    /** 행 키 = 배열 순번. 사용 중 항목 1개 이상, 순번 1~9999·사용 중 항목끼리 중복 금지 */
    private static List<LineSave> validateLines(List<TplLineForm> in, Map<Long, TplLine> existing) {
        List<TplLineForm> rows = in == null ? List.of() : in;
        if (rows.size() > MAX_LINES) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add("lines", "TOO_MANY_ITEMS", MAX_LINES).list());
        }
        FieldErrors e = FieldErrors.create();
        List<LineSave> out = new ArrayList<>();
        Set<Integer> seqs = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        int active = 0;
        for (int i = 0; i < rows.size(); i++) {
            TplLineForm r = rows.get(i);
            FieldErrors.RowScope row = e.row(String.valueOf(i));
            if (r == null) {
                row.add("checkText", "REQUIRED");
                continue;
            }
            if (r.tplLineId() != null && (!existing.containsKey(r.tplLineId()) || !ids.add(r.tplLineId()))) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            int seq = r.seq() == null ? (i + 1) * 10 : r.seq();
            if (seq < 1 || seq > 9999) {
                row.add("seq", "RANGE", 1, 9999);
            }
            FieldErrors tmp = FieldErrors.create();
            String text = VisibleText.reason(r.checkText(), "checkText", 1, 300, tmp);
            String grp = VisibleText.optional(r.grpNm(), "grpNm", 60, tmp);
            tmp.list().forEach(x -> row.add(x.field(), x.code(), x.args()));
            String na = yn(r.naAllowedYn(), "N", row, "naAllowedYn");
            String photo = yn(r.photoReqYn(), "N", row, "photoReqYn");
            String use = yn(r.useYn(), "Y", row, "useYn");
            if ("Y".equals(use)) {
                active++;
                if (!seqs.add(seq)) {
                    row.add("seq", "DUPLICATE");
                }
            }
            out.add(new LineSave(r.tplLineId(), seq, grp, text, na, photo, use));
        }
        if (active == 0) {
            e.add("lines", "PRESHIP_NO_LINE");
        }
        e.throwIfAny();
        return out;
    }

    private static String yn(String v, String dflt, FieldErrors.RowScope row, String field) {
        String s = VisibleText.isBlank(v) ? dflt : v.strip();
        if (!"Y".equals(s) && !"N".equals(s)) {
            row.add(field, "CODE");
        }
        return s;
    }

    static String yn(String useYn) {
        String yn = VisibleText.isBlank(useYn) ? null : useYn.strip();
        if (yn != null && !"Y".equals(yn) && !"N".equals(yn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return yn;
    }

    static String like(String keyword) {
        if (VisibleText.isBlank(keyword)) {
            return null;
        }
        String k = keyword.strip();
        if (k.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return "%" + BomService.likeEscape(k) + "%";
    }

    private static BusinessException duplicate(DuplicateKeyException e) {
        String c = PgErrors.constraint(e).orElse("");
        String field = "uq_qms_preship_tpl_cd".equals(c) ? "tplCd" : "uq_qms_preship_tpl_class".equals(c) ? "classCd" : "itemCd";
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, "DUPLICATE").list());
    }
}
