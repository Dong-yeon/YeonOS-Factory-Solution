package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.text;
import static io.smarthappy.module.cms.CmsSupport.yn;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsSecDtos.SecExport;
import io.smarthappy.module.cms.CmsSecDtos.SecForm;
import io.smarthappy.module.cms.CmsSecDtos.SecPage;
import io.smarthappy.module.cms.CmsSecDtos.SecRow;
import io.smarthappy.module.cms.CmsSecDtos.SecSort;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 설비 보안 대장 (cms-equip-sec) — docs/33 4.6, D11. 설비 1:1, 경고는 SecWarn 이 조회 때 계산(설비 공장 시간대의 오늘).
 */
@Service
@RequiredArgsConstructor
public class EquipSecService {

    static final String PROG = "cms-equip-sec";
    static final int MAX_ROWS = 3000;
    static final Set<String> LEVELS = Set.of("SL0", "SL1", "SL2", "SL3");
    static final Set<String> REMOTE = Set.of("NONE", "VENDOR_VPN", "OTHER");
    static final Set<String> WARNS = Set.of("UNREGISTERED", "EOS", "SCAN_OVERDUE", "SL_GAP", "UNREVIEWED", "ANY");

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("plantCd", "cms.col.plant", 80),
            ColumnSpec.text("equipCd", "cms.sec.col.equipCd", 110),
            ColumnSpec.text("equipNm", "cms.sec.col.equipNm", 180),
            ColumnSpec.text("warnText", "cms.sec.col.warn", 220),
            ColumnSpec.of("networkYn", "cms.sec.col.network", ColumnType.YN, 80),
            ColumnSpec.text("osNm", "cms.sec.col.osNm", 140),
            ColumnSpec.text("osVer", "cms.sec.col.osVer", 100),
            ColumnSpec.of("osEosDate", "cms.sec.col.osEosDate", ColumnType.DATE, 110),
            ColumnSpec.of("lastScanDate", "cms.sec.col.lastScanDate", ColumnType.DATE, 110),
            ColumnSpec.of("scanDueDate", "cms.sec.col.scanDueDate", ColumnType.DATE, 110),
            ColumnSpec.text("scanTool", "cms.sec.col.scanTool", 120),
            ColumnSpec.code("targetSl", "cms.sec.col.targetSl", "CMS_SEC_LEVEL", 90),
            ColumnSpec.code("currentSl", "cms.sec.col.currentSl", "CMS_SEC_LEVEL", 90),
            ColumnSpec.of("usbCtrlYn", "cms.sec.col.usbCtrl", ColumnType.YN, 80),
            ColumnSpec.code("remoteAccess", "cms.sec.col.remoteAccess", "CMS_REMOTE_ACCESS", 110),
            ColumnSpec.text("remoteText", "cms.sec.col.remoteText", 140),
            ColumnSpec.of("reviewDate", "cms.sec.col.reviewDate", ColumnType.DATE, 110),
            ColumnSpec.text("remark", "cms.col.remark", 200));

    private final CmsSecMapper mapper;
    private final PolicyService policies;
    private final CodeService codes;
    private final ExcelExportService excel;
    private final BizClock clock;

    // ------------------------------------------------------------------ 조회

    /** 경고 필터: 비우면 전체, ANY = 경고 하나 이상, 그 밖 = 그 경고가 있는 행 */
    @Transactional(readOnly = true)
    public List<SecRow> list(String keyword, String plantCd, String warn) {
        if (keyword != null && keyword.length() > 100 || !blank(warn) && !WARNS.contains(warn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<SecRow> rows = mapper.findSecs(blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)),
                blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT), MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_ROWS);
        }
        Ctx ctx = ctx();
        rows.forEach(r -> decorate(r, ctx));
        if (blank(warn)) {
            return rows;
        }
        return rows.stream().filter(r -> "ANY".equals(warn) ? !r.getWarns().isEmpty() : r.getWarns().contains(warn)).toList();
    }

    /** grid 열(docs/40 10.2) — 표였던 때와 같은 8칸. OS 칸은 버전을, 등급 칸은 서버가 "현재 / 목표" 글자로 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("plantCd", "cms.col.plant", 80).withSort().withPriority(2),
            ColumnSpec.text("equipCd", "cms.sec.col.equipCd", 110).withSort().withPriority(1),
            ColumnSpec.text("equipNm", "cms.sec.col.equipNm", 180).withSort().withPriority(1).withFlex().withMinWidth(110),
            ColumnSpec.text("osNm", "cms.sec.col.os", 140).withSort().withPriority(3),
            ColumnSpec.of("osEosDate", "cms.sec.col.osEosDate", ColumnType.DATE, 110).withSort().withPriority(2),
            ColumnSpec.of("lastScanDate", "cms.sec.col.lastScanDate", ColumnType.DATE, 110).withSort().withPriority(2),
            ColumnSpec.text("levelText", "cms.sec.col.level", 110).withSort().withPriority(3),
            ColumnSpec.of("reviewDate", "cms.sec.col.reviewDate", ColumnType.DATE, 110).withSort().withPriority(3),
            ColumnSpec.text("warnText", "cms.sec.col.warn", 220).withSort().withPriority(1));

    /** grid 정렬 — 칸(SecSort.field)마다 값 꺼내기(PageQuery 의 키는 enum 이름). 빈 값은 방향과 관계없이 맨 뒤(nulls last, 다른 grid 와 같음) */
    private static final Map<String, Function<SecRow, Comparable<?>>> SORT_KEYS = Map.of(
            "plantCd", SecRow::getPlantCd, "equipCd", SecRow::getEquipCd, "equipNm", SecRow::getEquipNm, "osNm", SecRow::getOsNm,
            "osEosDate", SecRow::getOsEosDate, "lastScanDate", SecRow::getLastScanDate,
            "reviewDate", SecRow::getReviewDate, "levelText", SecRow::getLevelText, "warnText", r -> blank(r.getWarnText()) ? null : r.getWarnText());

    /** 계산 값(서버가 만든 글자)이라 SQL 로 정렬할 수 없는 칸 — 이 칸으로 정렬하면 메모리 */
    static final Set<SecSort> COMPUTED_SORTS = Set.of(SecSort.LEVEL_TEXT, SecSort.WARN_TEXT);

    /**
     * grid(docs/40 10.2) — 목록과 같은 조건. 기본 순서 = 목록 순서(공장·설비 코드).
     * <b>경고 필터가 없고 정렬이 저장 칸뿐이면 SQL 로 정렬·쪽 나누기</b>(QA G-2, docs/33 13.18 — 목록·엑셀과 같은 DB 정렬 규칙, 언어 정렬 DB 에서도
     * 순서가 갈리지 않는다). 경고 필터·계산 칸 정렬은 계산 값이라 메모리 — 그때도 <b>행은 SQL 이 저장 칸으로 정렬해 주고</b>, 저장 칸은 Java 로 다시
     * 비교하지 않으며(그 순서의 묶음 번호로) 계산 칸만 값으로 비교해 안정 정렬한다(G-2 잔여). 메모리 경로만 행 수가 목록과 같은 상한(MAX_ROWS)
     */
    @Transactional(readOnly = true)
    public SecPage grid(String keyword, String plantCd, String warn, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total,
                SecSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX), List.of("plantCd,asc", "equipCd,asc"));
        if (keyword != null && keyword.length() > 100 || !blank(warn) && !WARNS.contains(warn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        String pl = blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT);
        List<PageQuery.Sort> stored = pq.getSorts().stream().filter(o -> !COMPUTED_SORTS.contains(SecSort.valueOf(o.getKey()))).toList();
        Ctx ctx = ctx();
        if (blank(warn) && stored.size() == pq.getSorts().size()) {
            List<SecRow> rows = mapper.findSecPage(k, pl, stored, pq);
            rows.forEach(r -> decorate(r, ctx));
            return new SecPage(rows, pq.isWithTotal() ? mapper.countSecs(k, pl) : null);
        }
        List<SecRow> rows = mapper.findSecsSorted(k, pl, stored, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_ROWS);
        }
        rows.forEach(r -> decorate(r, ctx));
        // 저장 칸 묶음 번호 — SQL 이 준 순서에서 앞 칸부터 그 칸까지 값이 같은 줄은 같은 번호(DB 정렬 규칙을 그대로 옮긴다)
        int[][] group = new int[rows.size()][stored.size()];
        for (int i = 0; i < rows.size(); i++) {
            boolean same = i > 0;
            for (int p = 0; p < stored.size(); p++) {
                Function<SecRow, Comparable<?>> f = SORT_KEYS.get(SecSort.valueOf(stored.get(p).getKey()).field());
                same = same && Objects.equals(f.apply(rows.get(i)), f.apply(rows.get(i - 1)));
                group[i][p] = same ? group[i - 1][p] : i;
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (blank(warn) || ("ANY".equals(warn) ? !rows.get(i).getWarns().isEmpty() : rows.get(i).getWarns().contains(warn))) {
                order.add(i);
            }
        }
        if (stored.size() < pq.getSorts().size()) {
            Comparator<Integer> cmp = null;
            int p = 0;
            for (PageQuery.Sort o : pq.getSorts()) {
                Comparator<Integer> c;
                if (COMPUTED_SORTS.contains(SecSort.valueOf(o.getKey()))) {
                    Comparator<SecRow> byValue = byKey(SORT_KEYS.get(SecSort.valueOf(o.getKey()).field()), o.isDesc());
                    c = (a, b) -> byValue.compare(rows.get(a), rows.get(b));
                } else {
                    int at = p++;
                    c = Comparator.comparingInt(x -> group[x][at]);
                }
                cmp = cmp == null ? c : cmp.thenComparing(c);
            }
            order.sort(cmp);   // 안정 정렬 — 같은 값은 SQL 순서(마지막 설비 id) 그대로
        }
        int from = (int) Math.min(pq.getOffset(), order.size());
        int to = Math.min(from + pq.getSize(), order.size());
        List<SecRow> out = new ArrayList<>();
        order.subList(from, to).forEach(x -> out.add(rows.get(x)));
        return new SecPage(out, pq.isWithTotal() ? (long) order.size() : null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparator<SecRow> byKey(Function<SecRow, Comparable<?>> key, boolean desc) {
        Comparator<Comparable> natural = desc ? Comparator.<Comparable>reverseOrder() : Comparator.<Comparable>naturalOrder();
        return Comparator.comparing(r -> (Comparable) key.apply(r), Comparator.nullsLast(natural));
    }

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public SecRow get(long equipId) {
        SecRow r = mapper.findSec(equipId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        decorate(r, ctx());
        return r;
    }

    /** 조회 한 번의 판정 기준(정책·공장 시간대·경고 이름) */
    private record Ctx(int scanMax, int review, Map<String, ZoneId> zones, Map<String, String> warnNames) {
    }

    private Ctx ctx() {
        Map<String, String> names = new HashMap<>();
        codes.codes("CMS_SEC_WARN", LocaleContextHolder.getLocale()).forEach(c -> names.put(c.code(), c.name()));
        return new Ctx(policies.intValue(Policies.CMS_SEC_SCAN_MAX_DAYS), policies.intValue(Policies.CMS_SEC_REVIEW_DAYS),
                new HashMap<>(), names);
    }

    private void decorate(SecRow r, Ctx c) {
        ZoneId z = c.zones().computeIfAbsent(r.getPlantCd() == null ? "" : r.getPlantCd(),
                k -> k.isEmpty() ? clock.zone() : clock.zone(k));
        LocalDate today = clock.instant().atZone(z).toLocalDate();
        List<String> w = SecWarn.of(new SecWarn.Input(r.getSecId() != null, r.getNetworkYn(), r.getOsEosDate(), r.getLastScanDate(),
                r.getTargetSl(), r.getCurrentSl(), r.getReviewDate()), today, c.scanMax(), c.review())
                .stream().map(Enum::name).toList();
        r.setWarns(w);
        r.setWarnText(w.stream().map(x -> c.warnNames().getOrDefault(x, x)).collect(Collectors.joining(", ")));
        r.setScanDueDate("Y".equals(r.getNetworkYn()) && r.getLastScanDate() != null ? r.getLastScanDate().plusDays(c.scanMax()) : null);
        r.setLevelText(r.getCurrentSl() == null && r.getTargetSl() == null ? null
                : (r.getCurrentSl() == null ? "-" : r.getCurrentSl()) + " / " + (r.getTargetSl() == null ? "-" : r.getTargetSl()));
    }

    // ------------------------------------------------------------------ 저장

    /** 처음 저장 = rowVer 없이(이미 있으면 409), 그 뒤 = rowVer. 새 행은 사용 중 설비만(트리거) */
    @Transactional
    public void save(long equipId, SecForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        SecRow cur = mapper.findSec(equipId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ZoneId z = cur.getPlantCd() == null ? clock.zone() : clock.zone(cur.getPlantCd());
        LocalDate today = clock.instant().atZone(z).toLocalDate();
        FieldErrors e = FieldErrors.create();
        SecRow s = new SecRow();
        s.setNetworkYn(yn(in.networkYn(), "N", "networkYn", e));
        s.setOsNm(text(in.osNm(), "osNm", 100, false, e));
        s.setOsVer(text(in.osVer(), "osVer", 50, false, e));
        s.setOsEosDate(date(in.osEosDate(), "osEosDate", e));
        s.setLastScanDate(notFuture(in.lastScanDate(), "lastScanDate", today, e));
        s.setScanTool(text(in.scanTool(), "scanTool", 100, false, e));
        if (s.getScanTool() != null && blank(in.lastScanDate())) {
            e.add("lastScanDate", "REQUIRED");   // 도구만 적고 날짜 없는 검사 기록은 받지 않는다
        }
        s.setTargetSl(code(in.targetSl(), LEVELS, "targetSl", e));
        s.setCurrentSl(code(in.currentSl(), LEVELS, "currentSl", e));
        s.setUsbCtrlYn(yn(in.usbCtrlYn(), "N", "usbCtrlYn", e));
        String remote = blank(in.remoteAccess()) ? "NONE" : in.remoteAccess().strip();
        if (!REMOTE.contains(remote)) {
            e.add("remoteAccess", "CODE");
        }
        s.setRemoteAccess(remote);
        s.setRemoteText(text(in.remoteText(), "remoteText", 200, "OTHER".equals(remote), e));
        s.setReviewDate(notFuture(in.reviewDate(), "reviewDate", today, e));
        s.setRemark(text(in.remark(), "remark", 500, false, e));
        e.throwIfAny();
        try {
            if (in.rowVer() == null) {
                if (cur.getSecId() != null) {
                    throw new BusinessException(HbErrorCode.CONFLICT);   // 다른 사람이 먼저 등록
                }
                mapper.insertSec(equipId, s, me.loginId());
            } else {
                if (mapper.lockSec(equipId) == null) {
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
                s.setRowVer(in.rowVer());
                if (mapper.updateSec(equipId, s, me.loginId()) == 0) {
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.CONFLICT);   // 동시에 처음 등록
        } catch (DataIntegrityViolationException ex) {
            if ("fk_cms_equip_sec_equip_comp".equals(io.smarthappy.platform.common.db.PgErrors.constraint(ex).orElse(""))) {
                throw CmsSupport.fieldError("equipCd", "INACTIVE");   // 사용 중지 설비는 새로 등록하지 않는다
            }
            throw ex;
        }
    }

    private static LocalDate notFuture(String raw, String field, LocalDate today, FieldErrors e) {
        LocalDate d = date(raw, field, e);
        if (d != null && d.isAfter(today)) {
            e.add(field, "FUTURE");
            return null;
        }
        return d;
    }

    private static String code(String raw, Set<String> allowed, String field, FieldErrors e) {
        if (blank(raw)) {
            return null;
        }
        String v = raw.strip();
        if (!allowed.contains(v)) {
            e.add(field, "CODE");
        }
        return v;
    }

    // ------------------------------------------------------------------ 엑셀

    /** 조회와 같은 조건·같은 경고 계산. 행이 설비 수만큼이라 목록을 그대로 흘려 보낸다 */
    @Transactional(readOnly = true)
    public long export(SecExport req, HttpServletResponse response) throws IOException {
        SecExport r = req == null ? new SecExport(null, null, null) : req;
        List<SecRow> rows = list(r.keyword(), r.plantCd(), r.warn());
        List<String> keys = new ArrayList<>();
        if (!blank(r.keyword())) {
            keys.add("keyword");
        }
        if (!blank(r.plantCd())) {
            keys.add("plantCd");
        }
        if (!blank(r.warn())) {
            keys.add("warn");
        }
        return excel.export(response, new ExportJob(PROG, "prog.cmsEquipSec", COLUMNS, rows.size(), keys), w -> rows.forEach(w));
    }
}
