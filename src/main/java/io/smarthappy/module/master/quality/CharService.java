package io.smarthappy.module.master.quality;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.master.quality.QualityDtos.CharExportRequest;
import io.smarthappy.module.master.quality.QualityDtos.CharForm;
import io.smarthappy.module.master.quality.QualityDtos.CharRow;
import io.smarthappy.module.master.quality.QualityDtos.CharSearch;
import io.smarthappy.module.master.quality.QualityDtos.CharSort;
import io.smarthappy.module.master.quality.QualityDtos.CharValue;
import io.smarthappy.module.master.quality.QualityDtos.ItemLookup;
import io.smarthappy.module.master.quality.QualityDtos.ProcLookup;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 특성 마스터 (mst-char) — P1 6단계, docs/21. 검사·SPC·FMEA·CP·PPAP 가 같은 char_id 를 가리킨다.
 *
 * - 제품 특성 = 품목 + 풍선 번호(공정은 "어디서 만들어지는가", 선택), 공정 특성 = 공정 + 번호(품목 선택)
 * - 계량은 하한·상한 중 하나 이상(하한 < 상한, 하한 ≤ 공칭 ≤ 상한), 계수는 수치 없음 — 수치는 서버가 10진 문자열로 받는다(반올림 없음)
 * - 유형·품목은 만든 뒤 바꾸지 않는다(다른 특성이 된다). 행 리비전 없음 — 규격 변경은 감사 이력 + 도면 리비전
 * - 특별특성은 시스템 코드(NONE·CC·SC) 또는 회사가 추가한 고객 기호 — DB 트리거가 최종 판정
 */
@Service
@RequiredArgsConstructor
public class CharService {

    static final String PROG = "mst-char";
    static final Pattern CHAR_NO = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,19}$");
    static final Set<String> TYPES = Set.of("PRODUCT", "PROCESS");
    static final Set<String> DATA_TYPES = Set.of("VARIABLE", "ATTRIBUTE");
    static final Set<String> SOURCES = Set.of("DRAWING", "CUSTOMER", "FMEA", "SPEC");
    static final List<String> DEFAULT_SORT = List.of("itemCd,asc", "charNo,asc");

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("itemCd", "mstChar.col.itemCd", 130).withSort().withPriority(1),
            ColumnSpec.text("itemNm", "mstChar.col.itemNm", 160).withPriority(2),
            // 공정 특성은 품목 칸이 비어 공정이 유일한 대상 — 좁은 화면에서도 남게 우선순위 1
            ColumnSpec.text("procCd", "mstChar.col.procCd", 90).withPriority(1),
            ColumnSpec.text("charNo", "mstChar.col.charNo", 90).withSort().withPriority(1),
            ColumnSpec.text("charNm", "mstChar.col.charNm", 200).withSort().withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.text("specText", "mstChar.col.spec", 170).withPriority(1),
            ColumnSpec.code("specialCls", "mstChar.col.specialCls", "SPECIAL_CHAR", 80).withSort().withPriority(1),
            ColumnSpec.code("charType", "mstChar.col.charType", "CHAR_TYPE", 70).withPriority(2),
            ColumnSpec.code("dataType", "mstChar.col.dataType", "CHAR_DATA_TYPE", 90).withPriority(3),
            ColumnSpec.code("source", "mstChar.col.source", "CHAR_SOURCE", 80).withPriority(3),
            ColumnSpec.text("drawingNo", "mstChar.col.drawingNo", 120).withPriority(3),
            ColumnSpec.of("useYn", "mstChar.col.useYn", ColumnSpec.ColumnType.YN, 60).withPriority(3),
            ColumnSpec.of("updatedAt", "mstChar.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort()
                    .withPriority(3));

    private final QualityMapper mapper;
    private final PolicyService policies;
    private final InboxService inbox;
    private final ExcelExportService excel;
    private final io.smarthappy.platform.code.CodeService codes;

    public record Page(List<CharRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String itemCd, String keyword, String procCd, String charType, String specialCls, Boolean withUnused,
            Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, CharSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        CharSearch q = search(itemCd, keyword, procCd, charType, specialCls, withUnused, pq);
        if (q == null) {
            return new Page(List.of(), pq.isWithTotal() ? 0L : null);
        }
        List<CharRow> rows = mapper.findChars(q);
        rows.forEach(CharService::fillSpec);
        return new Page(rows, pq.isWithTotal() ? mapper.countChars(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public CharRow detail(long charId) {
        CharRow c = mapper.findChar(charId);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        fillSpec(c);
        return c;
    }

    @Transactional(readOnly = true)
    public ItemLookup item(String itemCd) {
        ItemLookup it = blank(itemCd) ? null : mapper.findItemByCd(itemCd.strip().toUpperCase(Locale.ROOT));
        if (it == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return it;
    }

    /** 특성 화면의 공정 선택지 — 공정 관리 권한 없이도 사용 중인 공정은 보인다 */
    @Transactional(readOnly = true)
    public List<ProcLookup> procs() {
        return mapper.findProcs();
    }

    // ------------------------------------------------------------------ 저장

    @Transactional
    public long create(CharForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_CHAR_CREATE");
        CharValue v = validate(in, null);
        try {
            return mapper.insertChar(v, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public void update(long charId, CharForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        CharRow cur = mapper.findChar(charId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        CharValue v = validate(in, cur);
        try {
            if (mapper.updateChar(charId, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private CharValue validate(CharForm in, CharRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type = cur != null ? cur.getCharType() : blank(in.charType()) ? "PRODUCT" : in.charType();
        if (cur != null && !blank(in.charType()) && !in.charType().equals(cur.getCharType())) {
            e.add("charType", "FIXED");
        } else if (!TYPES.contains(type)) {
            e.add("charType", "CODE");
        }
        // 품목: 만들 때만 정한다
        Long itemId = cur != null ? cur.getItemId() : null;
        String itemCd = blank(in.itemCd()) ? null : in.itemCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null) {
            if (itemCd != null && !itemCd.equals(cur.getItemCd())) {      // 비우면 그대로
                e.add("itemCd", "FIXED");
            }
        } else if (itemCd != null) {
            ItemLookup it = mapper.findItemByCd(itemCd);
            if (it == null) {
                e.add("itemCd", "NOT_FOUND");
            } else if (!"Y".equals(it.getUseYn())) {
                e.add("itemCd", "INACTIVE");
            } else {
                itemId = it.getItemId();
            }
        } else if ("PRODUCT".equals(type)) {
            e.add("itemCd", "REQUIRED");
        }
        // 공정: 공정 특성은 필수·고정, 제품 특성은 선택·수정 가능
        Long procId = null;
        String procCd = blank(in.procCd()) ? null : in.procCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null && "PROCESS".equals(type) && !java.util.Objects.equals(procCd, cur.getProcCd())) {
            e.add("procCd", "FIXED");
        } else if (procCd != null) {
            ProcLookup p = mapper.findProcByCd(procCd);
            boolean same = cur != null && procCd.equals(cur.getProcCd());
            if (p == null) {
                e.add("procCd", "NOT_FOUND");
            } else if (!same && !"Y".equals(p.getUseYn())) {
                e.add("procCd", "INACTIVE");
            } else {
                procId = p.getProcId();
            }
        } else if ("PROCESS".equals(type)) {
            e.add("procCd", "REQUIRED");
        }
        String no = blank(in.charNo()) ? null : in.charNo().strip().toUpperCase(Locale.ROOT);
        if (no == null) {
            e.add("charNo", "REQUIRED");
        } else if (!CHAR_NO.matcher(no).matches()) {
            e.add("charNo", "FORMAT");
        }
        String nm = text(in.charNm(), "charNm", 200, true, e);
        String data = blank(in.dataType()) ? "VARIABLE" : in.dataType();
        if (!DATA_TYPES.contains(data)) {
            e.add("dataType", "CODE");
        }
        // 측정 단위 = 공통코드 MEAS_UNIT + 이름 사본(docs/68 Q2) — 코드가 없으면 옛 글자(같은 이름 코드가 있으면 그 코드로)
        io.smarthappy.platform.code.CodeService.CodeAndName unit = codes.codeAndName("MEAS_UNIT", in.unitCd(), in.unitNm(), "unitCd",
                "unitNm", 20, cur == null ? null : cur.getUnitCd(), cur == null ? null : cur.getUnitNm(), e);
        int scale = in.scale() == null ? 2 : in.scale();
        if (scale < 0 || scale > 6) {
            e.add("scale", "CODE");
        }
        BigDecimal nominal = BomService.decimal(in.nominal(), "nominal", 6, 12, false, e);
        BigDecimal lsl = BomService.decimal(in.lsl(), "lsl", 6, 12, false, e);
        BigDecimal usl = BomService.decimal(in.usl(), "usl", 6, 12, false, e);
        if ("ATTRIBUTE".equals(data)) {
            // 계수(합부)는 수치 규격이 없다 — 넣었으면 지우라고 알린다(조용히 버리지 않음)
            if (!blank(in.nominal()) || !blank(in.lsl()) || !blank(in.usl())) {
                e.add("lsl", "ATTR_NO_SPEC");
            }
        } else if ("VARIABLE".equals(data)) {
            if (blank(in.lsl()) && blank(in.usl())) {
                e.add("lsl", "SPEC_LIMIT");
            }
            if (lsl != null && usl != null && lsl.compareTo(usl) >= 0) {
                e.add("usl", "SPEC_RANGE");
            }
            if (nominal != null && ((lsl != null && nominal.compareTo(lsl) < 0) || (usl != null && nominal.compareTo(usl) > 0))) {
                e.add("nominal", "SPEC_RANGE");
            }
            for (BigDecimal[] x : new BigDecimal[][] {{nominal}, {lsl}, {usl}}) {
                if (x[0] != null && x[0].scale() > scale) {
                    // 규격 자릿수가 측정 자릿수보다 길면 측정으로 판정할 수 없다
                    e.add("scale", "SCALE_SHORT");
                    break;
                }
            }
        }
        String special = blank(in.specialCls()) ? "NONE" : in.specialCls().strip();
        // 새 참조만 막는다 — 지금 값이 그 뒤 사용 중지된 코드여도 다른 칸 수정·사용 중지는 된다(트리거도 바뀔 때만 검사)
        boolean sameSpecial = cur != null && special.equals(cur.getSpecialCls());
        if (special.length() > 30 || !sameSpecial && !mapper.codeUsable("SPECIAL_CHAR", special)) {
            e.add("specialCls", "CODE");
        }
        String source = blank(in.source()) ? "DRAWING" : in.source();
        if (!SOURCES.contains(source)) {
            e.add("source", "CODE");
        }
        String dwg = text(in.drawingNo(), "drawingNo", 50, false, e);
        String dwgRev = text(in.drawingRev(), "drawingRev", 10, false, e);
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        } else if (cur != null && "Y".equals(use) && !"Y".equals(cur.getUseYn())) {
            // 다시 사용(N→Y)도 새 참조처럼 — 그 사이 품목·공정이 사용 중지됐으면 막는다(4단계 QA 교훈)
            if (cur.getItemCd() != null) {
                ItemLookup it = mapper.findItemByCd(cur.getItemCd());
                if (it == null || !"Y".equals(it.getUseYn())) {
                    e.add("itemCd", "INACTIVE");
                }
            }
            if (procCd != null) {
                ProcLookup p = mapper.findProcByCd(procCd);
                if (p == null || !"Y".equals(p.getUseYn())) {
                    e.add("procCd", "INACTIVE");
                }
            }
        }
        String remark = text(in.remark(), "remark", 1000, false, e);
        e.throwIfAny();
        return new CharValue(type, itemId, procId, no, nm, data, unit.code(), unit.name(), scale, nominal, lsl, usl, special, source, dwg, dwgRev,
                sort, use, remark, cur == null ? null : in.rowVer());
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return fieldError("charNo", "DUPLICATE");
        }
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_qms_char_special" -> fieldError("specialCls", "CODE");
            case "fk_qms_char_item_comp" -> fieldError("itemCd", "NOT_FOUND");
            case "fk_qms_char_proc_comp" -> fieldError("procCd", "NOT_FOUND");
            default -> e;
        };
    }

    /** 목록용 규격 글자 — "9.95 ~ 10.05 mm", "≥ 440 MPa", "≤ 0.3", 계수는 "OK/NG" */
    static void fillSpec(CharRow c) {
        if ("ATTRIBUTE".equals(c.getDataType())) {
            c.setSpecText("OK/NG");
            return;
        }
        String unit = c.getUnitNm() == null ? "" : " " + c.getUnitNm();
        String s;
        if (c.getLsl() != null && c.getUsl() != null) {
            s = num(c.getLsl(), c.getScale()) + " ~ " + num(c.getUsl(), c.getScale());
        } else if (c.getLsl() != null) {
            s = "≥ " + num(c.getLsl(), c.getScale());
        } else if (c.getUsl() != null) {
            s = "≤ " + num(c.getUsl(), c.getScale());
        } else {
            s = "";
        }
        c.setSpecText(s + unit);
    }

    private static String num(BigDecimal v, int scale) {
        return v.setScale(Math.max(scale, Math.max(0, v.stripTrailingZeros().scale())), RoundingMode.UNNECESSARY).toPlainString();
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(CharExportRequest req, HttpServletResponse response) throws IOException {
        CharExportRequest r = req == null ? new CharExportRequest(null, null, null, null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, r.columns());
        CharSearch q = search(r.itemCd(), r.keyword(), r.procCd(), r.charType(), r.specialCls(), r.withUnused(),
                PageQuery.sortOnly(r.sort(), CharSort.values(), DEFAULT_SORT));
        List<String> keys = new ArrayList<>();
        if (!blank(r.itemCd())) {
            keys.add("itemCd");
        }
        if (!blank(r.keyword())) {
            keys.add("keyword");
        }
        if (!blank(r.specialCls())) {
            keys.add("specialCls");
        }
        if (!blank(r.procCd())) {
            keys.add("procCd");
        }
        if (!blank(r.charType())) {
            keys.add("charType");
        }
        if (Boolean.TRUE.equals(r.withUnused())) {
            keys.add("withUnused");
        }
        long count = q == null ? 0 : mapper.countChars(q);
        return excel.export(response, new ExportJob(PROG, "prog.mstChar", cols, count, keys), w -> {
            if (q == null) {
                return;
            }
            try (Cursor<CharRow> c = mapper.cursorChars(q)) {
                c.forEach(row -> {
                    fillSpec(row);
                    w.accept(row);
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** 조회 조건 — 없는 공정 코드면 null(결과 0건) */
    private CharSearch search(String itemCd, String keyword, String procCd, String charType, String specialCls,
            Boolean withUnused, PageQuery pq) {
        String cd = blank(itemCd) ? null : itemCd.strip().toUpperCase(Locale.ROOT);
        String kw = blank(keyword) ? null : keyword.strip();
        if ((cd != null && cd.length() > 40) || (kw != null && kw.length() > 200)
                || (!blank(charType) && !TYPES.contains(charType)) || (!blank(specialCls) && specialCls.length() > 30)
                || (!blank(procCd) && procCd.length() > 20)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Long procId = null;
        if (!blank(procCd)) {
            ProcLookup p = mapper.findProcByCd(procCd.strip().toUpperCase(Locale.ROOT));
            if (p == null) {
                return null;
            }
            procId = p.getProcId();
        }
        return new CharSearch(cd == null ? null : BomService.likeEscape(cd) + "%",
                kw == null ? null : "%" + BomService.likeEscape(kw) + "%", procId, blank(charType) ? null : charType,
                blank(specialCls) ? null : specialCls.strip(), Boolean.TRUE.equals(withUnused), pq);
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

    static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
