package io.smarthappy.module.master.quality;

import static io.smarthappy.module.master.quality.CharService.blank;
import static io.smarthappy.module.master.quality.CharService.fieldError;
import static io.smarthappy.module.master.quality.CharService.me;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.master.quality.QualityDtos.AliasForm;
import io.smarthappy.module.master.quality.QualityDtos.AliasRow;
import io.smarthappy.module.master.quality.QualityDtos.DefectForm;
import io.smarthappy.module.master.quality.QualityDtos.DefectRow;
import io.smarthappy.module.master.quality.QualityDtos.DefectSort;
import io.smarthappy.module.master.quality.QualityDtos.TermRow;
import io.smarthappy.module.master.quality.QualityDtos.TermSort;
import io.smarthappy.platform.common.query.PageQuery;
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
 * 불량 유형(mst-defect)·용어 사전(mst-term) — P1 6단계, docs/21.
 *
 * 불량 유형 = 표준 상위 DEFECT_CAT(시스템, 회사 추가 불가) + 회사 하위 코드. 용어 = 전역 sys_term(읽기 전용) + 회사 동의어.
 * 동의어는 회사 안에서 대소문자·앞뒤 공백 무시 유일 — 한 말이 두 용어를 가리키지 않게(uq_mst_term_alias).
 */
@Service
@RequiredArgsConstructor
public class DefectTermService {

    static final String PROG_DEFECT = "mst-defect";
    static final String PROG_TERM = "mst-term";
    static final Pattern DEFECT_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    static final Set<String> CATS = Set.of("APPEARANCE", "DIMENSION", "FUNCTION", "MATERIAL", "WELD", "ASSEMBLY", "CONTAM",
            "LABEL", "OTHER");
    static final Set<String> LANGS = Set.of("ko", "en", "vi");

    /** 그리드 기본 정렬 = 기존 목록과 같은 순서 */
    static final List<String> DEFECT_SORT = List.of("catCd,asc", "sortNo,asc", "defectCd,asc");
    static final List<String> TERM_SORT = List.of("sortNo,asc", "termCd,asc");

    static final List<ColumnSpec> DEFECT_COLUMNS = List.of(
            ColumnSpec.code("catCd", "defect.col.catCd", "DEFECT_CAT", 110).withSort().withPriority(1),
            ColumnSpec.text("defectCd", "defect.col.defectCd", 110).withSort().withPriority(1),
            ColumnSpec.text("defectNm", "defect.col.defectNm", 200).withSort().withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.of("sortNo", "defect.col.sortNo", ColumnSpec.ColumnType.INT, 60).withSort().withPriority(3),
            ColumnSpec.of("useYn", "defect.col.useYn", ColumnSpec.ColumnType.YN, 70).withPriority(2),
            ColumnSpec.of("updatedAt", "mstChar.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));

    /** 분류는 공통 코드가 아니라 고정 4값(CHECK) — 이름은 화면이 붙인다(format) */
    static final List<ColumnSpec> TERM_COLUMNS = List.of(
            ColumnSpec.text("category", "term.col.category", 90).withSort().withPriority(2),
            ColumnSpec.text("termKo", "term.col.term", 160).withSort().withPriority(1).withFlex().withMinWidth(100),
            ColumnSpec.text("termEn", "term.col.termEn", 180).withSort().withPriority(1).withFlex().withMinWidth(100),
            ColumnSpec.of("aliasCnt", "term.col.aliasCnt", ColumnSpec.ColumnType.INT, 70).withSort().withPriority(2));

    private final QualityMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;

    public record Page<T>(List<T> list, Long total) {
    }

    // ------------------------------------------------------------------ 불량 유형

    @Transactional(readOnly = true)
    public List<DefectRow> defects(String useYn) {
        if (!blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findDefects(blank(useYn) ? null : useYn);
    }

    /** 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 기존 목록 API 와 같은 조건 + 빠른 찾기(코드·이름) + 페이지·정렬·건수 */
    @Transactional(readOnly = true)
    public Page<DefectRow> defectGrid(String useYn, String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, DefectSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFECT_SORT);
        if (!blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String yn = blank(useYn) ? null : useYn;
        String kw = keywordLike(keyword, 100);
        return new Page<>(mapper.findDefectPage(yn, kw, pq), pq.isWithTotal() ? mapper.countDefects(yn, kw) : null);
    }

    public List<ColumnSpec> defectColumns() {
        return DEFECT_COLUMNS;
    }

    /** 빠른 찾기 — 앞뒤 공백 버림, 길이 초과는 400, LIKE 특수 문자는 글자로 */
    static String keywordLike(String keyword, int max) {
        if (blank(keyword)) {
            return null;
        }
        String k = keyword.strip();
        if (k.length() > max) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return "%" + BomService.likeEscape(k) + "%";
    }

    @Transactional
    public long createDefect(DefectForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_DEFECT_CREATE");
        DefectForm f = validateDefect(in, null);
        try {
            return mapper.insertDefect(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("defectCd", "DUPLICATE");
        }
    }

    /** 수정 — 코드는 바꾸지 않는다(실적·NCR 이 코드로 남는다). 상위 분류는 바꿀 수 있다(감사 이력) */
    @Transactional
    public void updateDefect(long defectId, DefectForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        DefectRow cur = mapper.findDefect(defectId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.updateDefect(defectId, validateDefect(in, cur), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private DefectForm validateDefect(DefectForm in, DefectRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = blank(in.defectCd()) ? null : in.defectCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null) {
            if (cd != null && !cd.equals(cur.getDefectCd())) {
                e.add("defectCd", "FIXED");
            }
            cd = cur.getDefectCd();
        } else if (cd == null) {
            e.add("defectCd", "REQUIRED");
        } else if (!DEFECT_CD.matcher(cd).matches()) {
            e.add("defectCd", "FORMAT");
        }
        String nm = blank(in.defectNm()) ? null : in.defectNm().strip();
        if (nm == null) {
            e.add("defectNm", "REQUIRED");
        } else if (nm.length() > 100) {
            e.add("defectNm", "TOO_LONG", 100);
        }
        if (blank(in.catCd())) {
            e.add("catCd", "REQUIRED");
        } else if (!CATS.contains(in.catCd())) {
            e.add("catCd", "CODE");
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = blank(in.remark()) ? null : in.remark().strip();
        if (remark != null && remark.length() > 500) {
            e.add("remark", "TOO_LONG", 500);
        }
        e.throwIfAny();
        return new DefectForm(cd, nm, in.catCd(), sort, use, remark, cur == null ? null : in.rowVer());
    }

    // ------------------------------------------------------------------ 용어 사전

    @Transactional(readOnly = true)
    public List<TermRow> terms() {
        return mapper.findTerms();
    }

    /** 용어 그리드 — 전역 용어(sys_term) + 이 회사 동의어 수. 빠른 찾기 = 코드·한글·영문·이 회사 동의어 */
    @Transactional(readOnly = true)
    public Page<TermRow> termGrid(String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, TermSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), TERM_SORT);
        String kw = keywordLike(keyword, 100);
        return new Page<>(mapper.findTermPage(kw, pq), pq.isWithTotal() ? mapper.countTerms(kw) : null);
    }

    public List<ColumnSpec> termColumns() {
        return TERM_COLUMNS;
    }

    @Transactional(readOnly = true)
    public List<AliasRow> aliases(String termCd) {
        if (blank(termCd) || !mapper.termExists(termCd)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.findAliases(termCd);
    }

    @Transactional
    public long createAlias(AliasForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_TERM_ALIAS_CREATE");
        AliasForm f = validateAlias(in, null);
        try {
            return mapper.insertAlias(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("alias", "DUPLICATE");
        }
    }

    /** 수정 — 가리키는 용어는 바꾸지 않는다(다른 용어면 새로 추가) */
    @Transactional
    public void updateAlias(long aliasId, AliasForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        AliasRow cur = mapper.findAlias(aliasId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        AliasForm f = validateAlias(in, cur);
        try {
            if (mapper.updateAlias(aliasId, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException e) {
            throw fieldError("alias", "DUPLICATE");
        }
    }

    private static final java.util.regex.Pattern ZERO_WIDTH = java.util.regex.Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("[\\s\\p{Z}]+");

    /**
     * 동의어 정규화 — 눈에 같은 말은 같은 글자로: NFKC(전각 영숫자·NFD 한글 자모 → 완성형), 폭 없는 문자 제거,
     * 모든 공백(전각·NBSP 포함)을 하나로, 앞뒤 제거. 빈 값이면 null(QA 6 결함 #6)
     */
    static String normalizeAlias(String v) {
        if (v == null) {
            return null;
        }
        String s = java.text.Normalizer.normalize(v, java.text.Normalizer.Form.NFKC);
        s = ZERO_WIDTH.matcher(s).replaceAll("");
        s = SPACES.matcher(s).replaceAll(" ").strip();
        return s.isEmpty() ? null : s;
    }

    private AliasForm validateAlias(AliasForm in, AliasRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String term = cur != null ? cur.getTermCd() : in.termCd();
        if (cur != null && !blank(in.termCd()) && !in.termCd().equals(cur.getTermCd())) {
            e.add("termCd", "FIXED");
        } else if (blank(term) || term.length() > 30 || !mapper.termExists(term)) {
            e.add("termCd", "NOT_FOUND");
        }
        // 눈에 같은 말은 하나로 — "B 급"·"B  급"·"B\u3000급"·NFD "폐기" 가 다른 동의어가 되지 않게
        String alias = normalizeAlias(in.alias());
        if (alias == null) {
            e.add("alias", "REQUIRED");
        } else if (alias.length() > 100) {
            e.add("alias", "TOO_LONG", 100);
        }
        String lang = blank(in.lang()) ? "ko" : in.lang();
        if (!LANGS.contains(lang)) {
            e.add("lang", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        e.throwIfAny();
        return new AliasForm(term, alias, lang, use, cur == null ? null : in.rowVer());
    }
}
