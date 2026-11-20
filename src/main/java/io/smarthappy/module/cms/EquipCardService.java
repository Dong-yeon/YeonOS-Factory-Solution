package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.like;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsCardDtos.CardSummary;
import io.smarthappy.module.cms.CmsCardDtos.EquipOption;
import io.smarthappy.module.cms.CmsCardDtos.HistExport;
import io.smarthappy.module.cms.CmsCardDtos.HistPage;
import io.smarthappy.module.cms.CmsCardDtos.HistRow;
import io.smarthappy.module.cms.CmsCardDtos.HistSort;
import io.smarthappy.module.cms.CmsDtos.ProfileRow;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 설비 이력 카드 (cms-equip-card) — docs/33 13.5 C17. 설비 한 대 × 기간(공장 현지 날짜, 최대 1년)의 요약 + 이력 한 표.
 * 읽기만. 대상 = 설비인 행만(금형·지그 대상 작업지시·점검은 그 자산의 것 — D40). MTBF/MTTR 은 KPI 화면 몫(D10).
 */
@Service
@RequiredArgsConstructor
public class EquipCardService {

    static final String PROG = "cms-equip-card";
    /** 화면 기간 상한(일, 종료일 포함) — D39: 1년 + 윤일, 회사 정책이 더 짧으면 그것 */
    static final int MAX_DAYS = 366;
    static final int EQUIP_LIMIT = 30;
    static final Set<String> KINDS = Set.of("MWO", "CHK", "PART", "RESET");
    static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CmsCardMapper mapper;
    private final CmsMapper cms;
    private final PolicyService policies;
    private final DateRanges ranges;
    private final BizClock clock;
    private final MessageSource messages;
    private final ExcelExportService excel;

    /** 설비 고르기 — 코드·이름 검색, 사용 중지 포함 */
    @Transactional(readOnly = true)
    public List<EquipOption> equips(String keyword, String plantCd) {
        if (keyword != null && keyword.length() > 100 || plantCd != null && plantCd.length() > 20) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findEquips(blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)),
                blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT), EQUIP_LIMIT);
    }

    /** 검사된 조건 — 설비 코드(없거나 다른 회사 = 404, 회사 안에서 고유)·기간(공장 현지 날짜)·종류 */
    record Scope(EquipOption equip, LocalDate fromDate, LocalDate toDate, ZoneId zone, OffsetDateTime from, OffsetDateTime to, String kind) {
    }

    Scope scope(String equipCd, String from, String to, String kind) {
        FieldErrors e = FieldErrors.create();
        if (blank(equipCd)) {
            e.add("equipCd", "REQUIRED");
        } else if (equipCd.strip().length() > 30) {
            e.add("equipCd", "FORMAT");
        }
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        if (!blank(kind) && !KINDS.contains(kind)) {
            e.add("kind", "CODE");
        }
        e.throwIfAny();
        ranges.check(f, t, MAX_DAYS);   // 공통 기간 검사 — 상한 = min(1년, 회사 정책 SEARCH_MAX_RANGE_DAYS)
        EquipOption eq = mapper.findEquip(equipCd.strip().toUpperCase(Locale.ROOT));
        if (eq == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ZoneId z = clock.zone(eq.getPlantCd());
        return new Scope(eq, f, t, z, f.atStartOfDay(z).toOffsetDateTime(), t.plusDays(1).atStartOfDay(z).toOffsetDateTime(), blank(kind) ? null : kind);
    }

    /** 카드 머리 — 프로필·설치 자산·작업지시/점검/예비품/리셋 집계·카운터 */
    @Transactional(readOnly = true)
    public CardSummary summary(String equipCd, String from, String to) {
        Scope s = scope(equipCd, from, to, null);
        long id = s.equip().getEquipId();
        ProfileRow p = cms.findProfile(id);
        LocalDate today = clock.instant().atZone(s.zone()).toLocalDate();
        return new CardSummary(p, s.fromDate().toString(), s.toDate().toString(), s.zone().getId(), cms.findInstalledAssets(id),
                mapper.mwoSum(id, s.from(), s.to()), mapper.chkSum(id, s.fromDate(), s.toDate(), today), mapper.partSum(id, s.from(), s.to()),
                mapper.resetCount(id, s.from(), s.to()), mapper.findCounters(id));
    }

    /** 이력 grid — 기본 = 최신순 */
    @Transactional(readOnly = true)
    public HistPage grid(String equipCd, String from, String to, String kind, Integer page, Integer size, List<String> sort, Boolean total) {
        Scope s = scope(equipCd, from, to, kind);
        PageQuery pq = PageQuery.parse(page, size, sort, total, HistSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        List<HistRow> rows = find(s, pq);
        return new HistPage(rows, pq.isWithTotal() ? count(s) : null);
    }

    static final List<String> DEFAULT_SORT = List.of("atText,desc");

    private List<HistRow> find(Scope s, PageQuery pq) {
        List<HistRow> rows = mapper.findHistPage(s.equip().getEquipId(), s.from(), s.to(), s.fromDate(), s.toDate(), s.zone().getId(), s.kind(), pq);
        Locale locale = LocaleContextHolder.getLocale();
        rows.forEach(r -> decorate(r, s.zone(), locale));
        return rows;
    }

    private long count(Scope s) {
        return mapper.countHist(s.equip().getEquipId(), s.from(), s.to(), s.fromDate(), s.toDate(), s.zone().getId(), s.kind());
    }

    /** 화면 글자 — 시각은 공장 현지, 내용은 종류마다(상태·유형·판정·수량·정지 분) */
    void decorate(HistRow r, ZoneId zone, Locale locale) {
        r.setAt(CmsSupport.at(r.getAt(), zone));
        r.setAtText(r.getAt() == null ? null : r.getAt().format(AT));
        List<String> parts = new ArrayList<>();
        switch (r.getKind()) {
            case "MWO" -> {
                parts.add(code("CMS_MWO_TYPE", r.getSubType(), locale));
                parts.add(code("CMS_MWO_STATUS", r.getStatus(), locale));
                if (r.getDownMin() != null) {
                    parts.add(messages.getMessage("cms.card.downMin", new Object[] {r.getDownMin()}, locale));
                }
            }
            case "CHK" -> {
                parts.add(code("CMS_RUN_STATUS", r.getStatus(), locale));
                if (r.getResult() != null) {
                    parts.add(r.getResult());
                }
            }
            case "PART" -> parts.add(qty(r) + (r.getUnit() == null ? "" : " " + r.getUnit()));
            case "RESET" -> parts.add(messages.getMessage("cms.card.resetTo", new Object[] {qty(r) + (r.getUnit() == null ? "" : " " + r.getUnit())}, locale));
            default -> {
            }
        }
        r.setDetail(String.join(" · ", parts));
    }

    private static String qty(HistRow r) {
        return r.getQty() == null ? "" : r.getQty().stripTrailingZeros().toPlainString();
    }

    private String code(String grp, String v, Locale locale) {
        return v == null ? "" : messages.getMessage("code." + grp + "." + v, null, v, locale);
    }

    /** grid 열 — 시각(공장 현지 글자)·종류·번호·내용 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("atText", "cms.card.col.at", 130).withSort(),
            ColumnSpec.code("kind", "cms.card.col.kind", "CMS_HIST_KIND", 90).withSort(),
            ColumnSpec.text("refNo", "cms.card.col.refNo", 140).withSort(),
            ColumnSpec.text("title", "cms.card.col.title", 220).withFlex(),
            ColumnSpec.text("detail", "cms.card.col.detail", 200),
            ColumnSpec.text("byNm", "cms.card.col.by", 100).withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 엑셀 — grid 와 같은 조건·정렬·보이는 열. 파일 = "설비 이력 카드" */
    @Transactional(readOnly = true)
    public long export(HistExport req, HttpServletResponse response) throws IOException {
        HistExport r = req == null ? new HistExport(null, null, null, null, null, null) : req;
        Scope s = scope(r.equipCd(), r.from(), r.to(), r.kind());
        List<ColumnSpec> cols = ColumnSpec.selectExportable(GRID_COLUMNS, r.columns());
        PageQuery pq = PageQuery.sortOnly(r.sort(), HistSort.values(), DEFAULT_SORT);
        List<String> keys = new ArrayList<>(List.of("equipCd", "from", "to"));
        if (s.kind() != null) {
            keys.add("kind");
        }
        return excel.export(response, new ExportJob(PROG, "prog.cmsEquipCard", cols, count(s), keys), w -> find(s, pq).forEach(w));
    }
}
