package io.smarthappy.module.master.bom;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomDtos.ActionRequest;
import io.smarthappy.module.master.bom.BomDtos.BatchResult;
import io.smarthappy.module.master.bom.BomDtos.BomDetail;
import io.smarthappy.module.master.bom.BomDtos.BomRow;
import io.smarthappy.module.master.bom.BomDtos.BomSearch;
import io.smarthappy.module.master.bom.BomDtos.BomSort;
import io.smarthappy.module.master.bom.BomDtos.CreatedKey;
import io.smarthappy.module.master.bom.BomDtos.ExportRequest;
import io.smarthappy.module.master.bom.BomDtos.HeaderForm;
import io.smarthappy.module.master.bom.BomDtos.HeaderValue;
import io.smarthappy.module.master.bom.BomDtos.ItemLookup;
import io.smarthappy.module.master.bom.BomDtos.LineBatch;
import io.smarthappy.module.master.bom.BomDtos.LineItem;
import io.smarthappy.module.master.bom.BomDtos.LineRow;
import io.smarthappy.module.master.bom.BomDtos.LineValue;
import io.smarthappy.module.master.EcoReleaseGate;
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
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * BOM 관리 (P1 3a, docs/18 결정 B1~B8).
 *
 * - 머리 = 모품목 × 대체 번호 × 리비전. 작성 중(DRAFT)만 고친다 — 확정본은 "새 리비전"으로 복사해 고친 뒤 확정
 * - 확정: 행 1건 이상, 자품목 사용 중, 행 단위 → 자품목 기본 단위 환산 있음, 대체 그룹 비율 합 100, 시작일 ≥ 오늘.
 *   같은 모품목·대체 번호의 이전 확정본이 새 시작일 전에 시작했으면 그 종료일을 새 시작일로 마감, 같은 날 이후 시작이면 겹침 오류
 * - 순환: 서비스가 먼저 행 오류(CYCLE)로 알리고, DB 트리거가 동시 저장·직접 SQL 까지 막는다(회사 단위 잠금)
 * - 행 저장은 머리 버전을 올린다 — 두 사람이 같은 BOM 을 동시에 고치면 뒤쪽이 409
 * - 순번 유일은 커밋 시점 검사(DEFERRABLE) — 한 번 저장으로 순번 1↔2 를 바꿀 수 있다
 */
@Service
@RequiredArgsConstructor
public class BomService {

    static final String PROG = "mst-bom";
    public static final Pattern REV = Pattern.compile("^[A-Z0-9]{1,4}$");
    /** 자동 리비전: A0…Z9 다음 AA0…ZZ9 (문자 1~2 + 숫자 1). 순서 = 길이 → 글자 */
    public static final Pattern AUTO_REV = Pattern.compile("^[A-Z]{1,2}[0-9]$");
    static final Pattern ALT_GRP = Pattern.compile("^[A-Z0-9]{1,10}$");
    static final Pattern ROW_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    static final Pattern DECIMAL = Pattern.compile("^[0-9]{1,30}(\\.[0-9]{1,30})?$|^-[0-9]{1,30}(\\.[0-9]{1,30})?$");
    static final Set<String> STATUSES = Set.of("DRAFT", "RELEASED", "OBSOLETE");
    static final Set<String> LINE_TYPES = Set.of("NORMAL", "PHANTOM", "BYPRODUCT");
    static final Set<String> ISSUE_METHODS = Set.of("BACKFLUSH", "MANUAL");
    static final List<String> DEFAULT_SORT = List.of("itemCd,asc", "rev,desc");
    static final int QTY_SCALE = 6;
    static final int QTY_INT_DIGITS = 12;
    static final int PCT_SCALE = 2;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("itemCd", "mstBom.col.itemCd", 140).withSort().withPriority(1),
            ColumnSpec.text("itemNm", "mstBom.col.itemNm", 200).withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.of("altNo", "mstBom.col.altNo", ColumnSpec.ColumnType.INT, 60).withPriority(3),
            ColumnSpec.text("rev", "mstBom.col.rev", 70).withSort().withPriority(1),
            ColumnSpec.code("status", "mstBom.col.status", "BOM_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.of("validFrom", "mstBom.col.validFrom", ColumnSpec.ColumnType.DATE, 100).withSort(),
            ColumnSpec.of("validTo", "mstBom.col.validTo", ColumnSpec.ColumnType.DATE, 100),
            ColumnSpec.dec("baseQty", "mstBom.col.baseQty", 3, 90).withPriority(3),
            ColumnSpec.code("itemUnit", "mstBom.col.itemUnit", "UNIT", 60).withPriority(3),
            ColumnSpec.of("lineCnt", "mstBom.col.lineCnt", ColumnSpec.ColumnType.INT, 70),
            ColumnSpec.text("changeNo", "mstBom.col.changeNo", 110).withPriority(3),
            ColumnSpec.of("releasedAt", "mstBom.col.releasedAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(3),
            ColumnSpec.of("updatedAt", "mstBom.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort()
                    .withPriority(3),
            ColumnSpec.text("updatedBy", "mstBom.col.updatedBy", 100).withPriority(3));

    private final BomMapper mapper;
    private final PolicyService policies;
    private final InboxService inbox;
    private final ExcelExportService excel;
    private final BizClock clock;
    private final EcoReleaseGate ecoGate;

    public record Page(List<BomRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String itemCd, String itemNm, String status, Boolean withObsolete, Integer page, Integer size,
            List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, BomSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        BomSearch q = search(itemCd, itemNm, status, withObsolete, pq);
        return new Page(mapper.findPage(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    @Transactional(readOnly = true)
    public BomDetail detail(long bomId) {
        return new BomDetail(head(bomId), mapper.findLines(bomId));
    }

    /** 품목 찾기 — BOM 화면 권한으로 (품목관리 권한이 없어도 BOM 은 만든다) */
    @Transactional(readOnly = true)
    public ItemLookup item(String itemCd) {
        ItemLookup it = blank(itemCd) ? null : mapper.findItemByCd(itemCd.strip().toUpperCase(Locale.ROOT));
        if (it == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return it;
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    // ------------------------------------------------------------------ 머리

    /** 새 BOM(작성 중). 리비전을 비우면 다음 리비전(A0, A1 … Z9) */
    @Transactional
    public long create(HeaderForm form) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_BOM_CREATE");
        HeaderValue v = validateHeader(form, null);
        if (mapper.findDraftId(v.itemId(), v.altNo()) != null) {
            throw new BusinessException(HbErrorCode.BOM_DRAFT_EXISTS);
        }
        try {
            return mapper.insertHeader(v, me.loginId());
        } catch (DuplicateKeyException e) {
            if (blank(form.rev())) {
                // 리비전을 비웠는데 겹침 = 같은 품목을 동시에 신규(둘 다 같은 다음 리비전) — 리비전 중복이 아니다 (QA 3a #7)
                throw new BusinessException(HbErrorCode.BOM_DRAFT_EXISTS);
            }
            throw duplicateRev(e);
        }
    }

    /** 머리 수정 — 작성 중만. 모품목·대체 번호는 바꾸지 않는다(다른 BOM 이 됨) */
    @Transactional
    public void update(long bomId, HeaderForm form) {
        LoginUser me = me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        BomRow cur = draft(bomId);
        HeaderValue v = validateHeader(form, cur);
        try {
            if (mapper.updateHeader(bomId, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException e) {
            throw duplicateRev(e);
        }
    }

    // ------------------------------------------------------------------ 행

    /** 행 일괄 저장 — 순서 deleted → updated → created, 전부 아니면 전무 */
    @Transactional
    public BatchResult saveLines(long bomId, int headRowVer, LineBatch batch) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_BOM_LINES");
        List<LineItem> created = batch == null || batch.created() == null ? List.of() : batch.created();
        List<LineItem> updated = batch == null || batch.updated() == null ? List.of() : batch.updated();
        List<LineItem> deleted = batch == null || batch.deleted() == null ? List.of() : batch.deleted();
        int max = policies.intValue(Policies.GRID_BATCH_MAX_ROWS);
        if (created.size() + updated.size() + deleted.size() > max || created.stream().anyMatch(Objects::isNull)
                || updated.stream().anyMatch(Objects::isNull) || deleted.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        BomRow head = draft(bomId);
        // 머리 잠금(버전 확인 + 증가) — 같은 BOM 동시 편집은 뒤쪽이 409
        if (mapper.touch(bomId, headRowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }

        Map<Long, LineRow> existing = new HashMap<>();
        mapper.findLines(bomId).forEach(l -> existing.put(l.getLineId(), l));
        FieldErrors errors = FieldErrors.create();
        Set<String> keys = new HashSet<>();
        Map<String, ItemLookup> items = new HashMap<>();
        Set<Long> touched = new HashSet<>();

        for (LineItem d : deleted) {
            rowKey(d, keys);
            if (d.lineId() == null || d.rowVer() == null) {
                errors.row(d.rowKey()).add("lineId", "REQUIRED");
            } else {
                touched.add(d.lineId());
            }
        }
        List<LineValue> cleanUpdated = new ArrayList<>();
        for (LineItem u : updated) {
            rowKey(u, keys);
            if (u.lineId() == null || u.rowVer() == null) {
                errors.row(u.rowKey()).add("lineId", "REQUIRED");
                continue;
            }
            touched.add(u.lineId());
            LineValue v = validateLine(u, head, existing.get(u.lineId()), items, errors.row(u.rowKey()));
            if (v != null) {
                cleanUpdated.add(v);
            }
        }
        List<LineValue> cleanCreated = new ArrayList<>();
        for (LineItem c : created) {
            rowKey(c, keys);
            LineValue v = validateLine(c, head, null, items, errors.row(c.rowKey()));
            if (v != null) {
                cleanCreated.add(v);
            }
        }
        // 순번은 저장 후 기준 BOM 안에서 유일 — 남는 기존 행 + 이번 행
        Map<Integer, Integer> lineNos = new HashMap<>();
        existing.values().stream().filter(l -> !touched.contains(l.getLineId()))
                .forEach(l -> lineNos.merge(l.getLineNo(), 1, Integer::sum));
        cleanUpdated.forEach(v -> lineNos.merge(v.lineNo(), 1, Integer::sum));
        cleanCreated.forEach(v -> lineNos.merge(v.lineNo(), 1, Integer::sum));
        cleanUpdated.stream().filter(v -> lineNos.get(v.lineNo()) > 1)
                .forEach(v -> errors.row(v.rowKey()).add("lineNo", "DUPLICATE"));
        cleanCreated.stream().filter(v -> lineNos.get(v.lineNo()) > 1)
                .forEach(v -> errors.row(v.rowKey()).add("lineNo", "DUPLICATE"));
        errors.throwIfAny();

        for (LineItem d : deleted) {
            if (mapper.deleteLine(bomId, d.lineId(), d.rowVer()) == 0) {
                throw rowConflict(d.rowKey());
            }
        }
        List<CreatedKey> out = new ArrayList<>();
        try {
            for (LineValue v : cleanUpdated) {
                if (mapper.updateLine(bomId, v, me.loginId()) == 0) {
                    throw rowConflict(v.rowKey());
                }
            }
            for (LineValue v : cleanCreated) {
                out.add(new CreatedKey(v.rowKey(), mapper.insertLine(bomId, v, me.loginId())));
            }
        } catch (DataIntegrityViolationException e) {
            // 서비스 검사 뒤 다른 트랜잭션이 구조를 바꾼 경우(동시 저장) — DB 트리거가 막은 순환
            if (PgErrors.constraint(e).filter("ck_mst_bom_cycle"::equals).isPresent()) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE)
                        .withFieldErrors(FieldErrors.create().add("itemCd", "CYCLE").list());
            }
            throw e;
        }
        return new BatchResult(out, cleanUpdated.size(), deleted.size());
    }

    // ------------------------------------------------------------------ 상태

    /** 확정 — 이전 확정본 종료일 자동 마감 */
    @Transactional
    public void release(long bomId, ActionRequest req) {
        LoginUser me = me();
        int ver = rowVer(req);
        BomRow head = draft(bomId);
        if (head.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        // D4(docs/39 14.2): 회사 정책 PLM_ECO_REQUIRED=Y 면 ECO 적용 경로에서만 확정
        ecoGate.checkRelease();
        List<LineRow> lines = mapper.findLines(bomId);
        FieldErrors errors = FieldErrors.create();
        if (head.getValidFrom().isBefore(clock.today())) {
            // 지난 날짜로 확정하면 이미 생산한 실적의 "그때 BOM" 이 바뀐다
            errors.add("validFrom", "PAST");
        }
        if (lines.isEmpty()) {
            errors.add("lines", "NO_LINES");
        }
        Map<String, BigDecimal> altSum = new LinkedHashMap<>();
        Map<String, List<LineRow>> altLines = new LinkedHashMap<>();
        for (LineRow l : lines) {
            FieldErrors.RowScope row = errors.row(lineKey(l));
            if (!"Y".equals(l.getItemUseYn())) {
                row.add("itemCd", "INACTIVE");
            }
            if (!mapper.convertible(l.getItemId(), l.getUnitCd())) {
                row.add("unitCd", "NO_CONV");
            }
            if (l.getAltGrp() != null) {
                altSum.merge(l.getAltGrp(), l.getAltPct(), BigDecimal::add);
                altLines.computeIfAbsent(l.getAltGrp(), k -> new ArrayList<>()).add(l);
            }
        }
        altSum.forEach((grp, sum) -> {
            if (sum.compareTo(BigDecimal.valueOf(100)) != 0) {
                altLines.get(grp).forEach(l -> errors.row(lineKey(l)).add("altPct", "ALT_SUM"));
            }
        });
        // 그룹마다 우선순위는 서로 다르고 1(기본 소요)이 있어야 — 전개·투입 검증이 기본 자재를 하나로 정한다 (docs/18 §4, QA 3a #2)
        altLines.values().forEach(grp -> {
            Set<Integer> prios = new HashSet<>();
            boolean dup = grp.stream().anyMatch(l -> !prios.add(l.getAltPriority()));
            if (dup || !prios.contains(1)) {
                grp.forEach(l -> errors.row(lineKey(l)).add("altPriority", "ALT_PRIORITY"));
            }
        });
        if (!errors.isEmpty()) {
            throw new BusinessException(HbErrorCode.BOM_RELEASE_CHECK).withFieldErrors(errors.list());
        }
        LocalDate from = head.getValidFrom();
        for (BomRow prev : mapper.findReleasedOverlaps(head.getItemId(), head.getAltNo(), from, null, bomId)) {
            if (!prev.getValidFrom().isBefore(from)) {
                // 이전 확정본이 같은 날 이후 시작 — 자동으로 끊을 수 없다(그 리비전을 폐기하거나 시작일을 바꿔야)
                throw new BusinessException(HbErrorCode.BOM_PERIOD_OVERLAP, prev.getRev());
            }
            mapper.closeValidTo(prev.getBomId(), from, me.loginId());
        }
        if (mapper.release(bomId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 폐기 — 작성 중·확정본. 폐기본은 전개·순환 검사에서 빠진다 */
    @Transactional
    public void obsolete(long bomId, ActionRequest req) {
        LoginUser me = me();
        int ver = rowVer(req);
        BomRow head = head(bomId);
        if ("OBSOLETE".equals(head.getStatus())) {
            throw new BusinessException(HbErrorCode.BOM_NOT_DRAFT);
        }
        if (mapper.obsolete(bomId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 새 리비전 — 원본(어느 상태든) 행을 복사한 작성 중 BOM. 시작일을 비우면 오늘(원본 확정본이 오늘 이후 시작이면 그 다음 날) */
    @Transactional
    public long newRevision(long bomId, ActionRequest req) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_BOM_NEWREV");
        int ver = rowVer(req);
        BomRow src = head(bomId);
        if (src.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (mapper.findDraftId(src.getItemId(), src.getAltNo()) != null) {
            throw new BusinessException(HbErrorCode.BOM_DRAFT_EXISTS);
        }
        FieldErrors e = FieldErrors.create();
        LocalDate from = date(req.validFrom(), "validFrom", e);
        // 리비전은 비우면 자동(다음 순번), 넣으면 그 값 — 회사 규칙의 리비전 체계를 쓰는 경우 (QA 3a #4)
        String rev = blank(req.rev()) ? null : req.rev().strip().toUpperCase(Locale.ROOT);
        if (rev != null && !REV.matcher(rev).matches()) {
            e.add("rev", "FORMAT");
        }
        e.throwIfAny();
        if (from == null) {
            // 기본 시작일 = 오늘. 원본 확정본이 오늘(또는 이후) 시작이면 그 다음 날 — 그래야 확정할 때 원본을 자동 마감할 수 있다
            LocalDate today = clock.today();
            from = "RELEASED".equals(src.getStatus()) && !src.getValidFrom().isBefore(today)
                    ? src.getValidFrom().plusDays(1) : today;
            if (from.getYear() > 2999) {
                // 원본이 2999-12-31 시작 — 기본값이 날짜 범위(2000~2999)를 넘는다 (QA 3a #5)
                throw new BusinessException(HbErrorCode.INVALID_VALUE)
                        .withFieldErrors(FieldErrors.create().add("validFrom", "FORMAT").list());
            }
        }
        HeaderValue v = new HeaderValue(src.getItemId(), src.getAltNo(),
                rev != null ? rev : nextRev(src.getItemId(), src.getAltNo()), src.getBaseQty(), from, null, null,
                src.getRemark(), null);
        long id;
        try {
            id = mapper.insertHeader(v, me.loginId());
            mapper.copyLines(bomId, id, me.loginId());
        } catch (DuplicateKeyException ex) {
            if (rev != null && PgErrors.constraint(ex).filter("uq_mst_bom_rev"::equals).isPresent()) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE)
                        .withFieldErrors(FieldErrors.create().add("rev", "DUPLICATE").list());
            }
            throw new BusinessException(HbErrorCode.CONFLICT);
        } catch (DataIntegrityViolationException ex) {
            if (PgErrors.constraint(ex).filter("ck_mst_bom_cycle"::equals).isPresent()) {
                // 폐기본을 되살리는 사이 구조가 바뀌어 순환이 됨
                throw new BusinessException(HbErrorCode.INVALID_VALUE)
                        .withFieldErrors(FieldErrors.create().add("itemCd", "CYCLE").list());
            }
            throw ex;
        }
        return id;
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(ExportRequest req, HttpServletResponse response) throws IOException {
        ExportRequest r = req == null ? new ExportRequest(null, null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, r.columns());
        BomSearch q = search(r.itemCd(), r.itemNm(), r.status(), r.withObsolete(),
                PageQuery.sortOnly(r.sort(), BomSort.values(), DEFAULT_SORT));
        List<String> keys = new ArrayList<>();
        if (q.getItemCdLike() != null) {
            keys.add("itemCd");
        }
        if (q.getItemNmLike() != null) {
            keys.add("itemNm");
        }
        if (q.getStatus() != null) {
            keys.add("status");
        }
        return excel.export(response, new ExportJob(PROG, "prog.mstBom", cols, mapper.count(q), keys), w -> {
            try (Cursor<BomRow> c = mapper.cursor(q)) {
                c.forEach(w);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // ------------------------------------------------------------------ 검증

    private BomSearch search(String itemCd, String itemNm, String status, Boolean withObsolete, PageQuery pq) {
        String cd = blank(itemCd) ? null : itemCd.strip().toUpperCase(Locale.ROOT);
        String nm = blank(itemNm) ? null : itemNm.strip();
        if ((cd != null && cd.length() > 40) || (nm != null && nm.length() > 200)
                || (!blank(status) && !STATUSES.contains(status))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new BomSearch(cd == null ? null : likeEscape(cd) + "%", nm == null ? null : "%" + likeEscape(nm) + "%",
                blank(status) ? null : status, Boolean.TRUE.equals(withObsolete), pq);
    }

    private HeaderValue validateHeader(HeaderForm in, BomRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        long itemId = 0;
        int altNo = in.altNo() == null ? 1 : in.altNo();
        if (cur != null) {
            itemId = cur.getItemId();
            altNo = cur.getAltNo();
        } else {
            ItemLookup it = blank(in.itemCd()) ? null : mapper.findItemByCd(in.itemCd().strip().toUpperCase(Locale.ROOT));
            if (blank(in.itemCd())) {
                e.add("itemCd", "REQUIRED");
            } else if (it == null) {
                e.add("itemCd", "NOT_FOUND");
            } else if (!"Y".equals(it.getUseYn())) {
                e.add("itemCd", "INACTIVE");
            } else {
                itemId = it.getItemId();
            }
            if (altNo < 1 || altNo > 99) {
                e.add("altNo", "CODE");
            }
        }
        String rev = blank(in.rev()) ? null : in.rev().strip().toUpperCase(Locale.ROOT);
        if (rev != null && !REV.matcher(rev).matches()) {
            e.add("rev", "FORMAT");
        }
        BigDecimal base = blank(in.baseQty()) ? BigDecimal.ONE
                : decimal(in.baseQty(), "baseQty", QTY_SCALE, QTY_INT_DIGITS, true, e);
        LocalDate from = date(in.validFrom(), "validFrom", e);
        String changeNo = text(in.changeNo(), "changeNo", 30, false, e);
        String reason = text(in.changeReason(), "changeReason", 500, false, e);
        String remark = text(in.remark(), "remark", 1000, false, e);
        e.throwIfAny();
        if (rev == null) {
            rev = cur != null ? cur.getRev() : nextRev(itemId, altNo);
        }
        if (from == null) {
            from = cur != null ? cur.getValidFrom() : clock.today();
        }
        return new HeaderValue(itemId, altNo, rev, base, from, changeNo, reason, remark, in.rowVer());
    }

    /**
     * 행 검증. 자품목을 새로 지정할 때만(추가·자품목 변경) 사용 중·순환을 본다 — 이미 들어 있는 행은 확정 검사가 다시 본다.
     */
    private LineValue validateLine(LineItem in, BomRow head, LineRow cur, Map<String, ItemLookup> items,
            FieldErrors.RowScope row) {
        return validateLine(in, head.getItemId(), cur == null ? null : cur.getItemId(),
                cur == null ? null : cur.getUnitCd(), cd -> items.computeIfAbsent(cd, mapper::findItemByCd),
                mapper::wouldCycle, mapper::unitUsable, row);
    }

    /**
     * 행 검증 본체 — DB 확인(품목·순환·단위)은 밖에서 넣는다. 화면 저장은 한 건씩 조회, 엑셀 올리기는 미리 읽은 구조로(행마다 조회하지 않게).
     *
     * @param curItemId 수정이면 지금 자품목(같으면 사용 중·순환을 다시 보지 않는다), 추가면 null
     * @param cycle     (모품목, 자품목) → 순환이면 true
     * @return 오류가 있으면 null
     */
    LineValue validateLine(LineItem in, long parentItemId, Long curItemId, String curUnit,
            Function<String, ItemLookup> itemOf, BiPredicate<Long, Long> cycle, Predicate<String> unitOk,
            FieldErrors.RowScope row) {
        FieldErrors e = FieldErrors.create();
        if (in.lineNo() == null) {
            e.add("lineNo", "REQUIRED");
        } else if (in.lineNo() < 1 || in.lineNo() > 9999) {
            e.add("lineNo", "CODE");
        }
        ItemLookup it = null;
        String cd = blank(in.itemCd()) ? null : in.itemCd().strip().toUpperCase(Locale.ROOT);
        if (cd == null) {
            e.add("itemCd", "REQUIRED");
        } else {
            it = itemOf.apply(cd);
            if (it == null) {
                e.add("itemCd", "NOT_FOUND");
            } else if (curItemId == null || curItemId != it.getItemId()) {
                if (!"Y".equals(it.getUseYn())) {
                    e.add("itemCd", "INACTIVE");
                } else if (cycle.test(parentItemId, it.getItemId())) {
                    e.add("itemCd", "CYCLE");
                }
            }
        }
        String type = blank(in.lineType()) ? "NORMAL" : in.lineType();
        if (!LINE_TYPES.contains(type)) {
            e.add("lineType", "CODE");
        }
        BigDecimal qty = decimal(in.qty(), "qty", QTY_SCALE, QTY_INT_DIGITS, true, e);
        if (blank(in.qty())) {
            e.add("qty", "REQUIRED");
        }
        String unit = blank(in.unitCd()) ? (it == null ? null : it.getUnitCd()) : in.unitCd().strip();
        if (unit == null) {
            if (it != null) {
                e.add("unitCd", "REQUIRED");
            }
        } else if (!unit.equals(curUnit) && !unitOk.test(unit)) {
            e.add("unitCd", "CODE");
        }
        BigDecimal scrap = blank(in.scrapPct()) ? BigDecimal.ZERO
                : decimal(in.scrapPct(), "scrapPct", PCT_SCALE, 3, false, e);
        if (scrap != null && (scrap.signum() < 0 || scrap.compareTo(BigDecimal.valueOf(100)) >= 0)) {
            e.add("scrapPct", "RANGE_PCT");
        }
        String fixed = blank(in.fixedYn()) ? "N" : in.fixedYn();
        if (!fixed.equals("Y") && !fixed.equals("N")) {
            e.add("fixedYn", "CODE");
        }
        String issue = null;
        if ("NORMAL".equals(type)) {
            // 기본값: LOT 관리 자재는 출고 지시(MANUAL), 아니면 실적 기준 자동 차감(BACKFLUSH) — docs/18 C5
            issue = !blank(in.issueMethod()) ? in.issueMethod()
                    : it != null && "Y".equals(it.getLotCtrlYn()) ? "MANUAL" : "BACKFLUSH";
            if (!ISSUE_METHODS.contains(issue)) {
                e.add("issueMethod", "CODE");
            }
        } else if (!blank(in.issueMethod())) {
            e.add("issueMethod", "CODE");
        }
        if (in.opSeq() != null && (in.opSeq() < 1 || in.opSeq() > 9999)) {
            e.add("opSeq", "CODE");
        }
        String grp = blank(in.altGrp()) ? null : in.altGrp().strip().toUpperCase(Locale.ROOT);
        Integer prio = null;
        BigDecimal pct = null;
        if (grp != null) {
            if (!ALT_GRP.matcher(grp).matches()) {
                e.add("altGrp", "FORMAT");
            } else if (!"NORMAL".equals(type)) {
                e.add("altGrp", "CODE");
            }
            prio = in.altPriority();
            if (prio == null) {
                e.add("altPriority", "REQUIRED");
            } else if (prio < 1 || prio > 99) {
                e.add("altPriority", "CODE");
            }
            if (blank(in.altPct())) {
                e.add("altPct", "REQUIRED");
            } else {
                pct = decimal(in.altPct(), "altPct", PCT_SCALE, 3, false, e);
                if (pct != null && (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0)) {
                    e.add("altPct", "RANGE_PCT");
                }
            }
        }
        String pos = text(in.position(), "position", 100, false, e);
        String remark = text(in.remark(), "remark", 500, false, e);
        e.list().forEach(fe -> row.add(fe.field(), fe.code(), fe.args()));
        if (!e.isEmpty()) {
            return null;
        }
        return new LineValue(in.rowKey(), in.lineId(), in.rowVer(), in.lineNo(), it.getItemId(), type, qty, unit,
                scrap, fixed, issue, in.opSeq(), grp, prio, pct, pos, remark);
    }

    /**
     * 다음 자동 리비전: 자동 형식 리비전 중 가장 뒤(길이 → 글자 순) 다음. A0 → A1 … A9 → B0 … Z9 → AA0 … AZ9 → BA0 … ZZ9.
     * 사용자가 직접 넣은 Z9 한 번으로 채번이 끝나지 않게 두 글자까지 이어간다 (QA 3a #4). ZZ9 다음만 소진
     */
    String nextRev(long itemId, int altNo) {
        String max = mapper.findRevs(itemId, altNo).stream().filter(r -> AUTO_REV.matcher(r).matches())
                .max(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())).orElse(null);
        return max == null ? "A0" : nextAutoRev(max);
    }

    /** 라우팅(P1 5단계)도 같은 리비전 체계를 쓴다 */
    public static String nextAutoRev(String cur) {
        String letters = cur.substring(0, cur.length() - 1);
        int n = cur.charAt(cur.length() - 1) - '0';
        if (n < 9) {
            return letters + (n + 1);
        }
        char[] c = letters.toCharArray();
        for (int i = c.length - 1; i >= 0; i--) {
            if (c[i] < 'Z') {
                c[i]++;
                return new String(c) + '0';
            }
            c[i] = 'A';
        }
        if (letters.length() >= 2) {
            throw new BusinessException(HbErrorCode.BOM_REV_EXHAUSTED);
        }
        return "AA0";
    }

    private BomRow head(long bomId) {
        BomRow b = mapper.findById(bomId);
        if (b == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return b;
    }

    private BomRow draft(long bomId) {
        BomRow b = head(bomId);
        if (!"DRAFT".equals(b.getStatus())) {
            throw new BusinessException(HbErrorCode.BOM_NOT_DRAFT);
        }
        return b;
    }

    private static void rowKey(LineItem l, Set<String> keys) {
        if (l.rowKey() == null || !ROW_KEY.matcher(l.rowKey()).matches() || !keys.add(l.rowKey())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    /** 확정 검사 오류의 행 키 — 화면은 lineId 로 행을 찾는다 */
    static String lineKey(LineRow l) {
        return "L" + l.getLineId();
    }

    private static int rowVer(ActionRequest req) {
        if (req == null || req.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return req.rowVer();
    }

    /** 10진 문자열 → BigDecimal (쉼표·지수 거부, 반올림 없음) */
    public static BigDecimal decimal(String raw, String field, int scale, int intDigits, boolean positive, FieldErrors e) {
        if (blank(raw)) {
            return null;
        }
        String s = raw.strip();
        if (s.length() > 40 || !DECIMAL.matcher(s).matches()) {
            e.add(field, "FORMAT");
            return null;
        }
        BigDecimal v = new BigDecimal(s).stripTrailingZeros();
        if (positive && v.signum() <= 0) {
            e.add(field, "POSITIVE");
            return null;
        }
        if (v.scale() > scale) {
            e.add(field, "SCALE", scale);
            return null;
        }
        if (v.precision() - v.scale() > intDigits) {
            e.add(field, "INT_DIGITS", intDigits);
            return null;
        }
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    public static LocalDate date(String raw, String field, FieldErrors e) {
        if (blank(raw)) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(raw.strip());
            if (d.getYear() < 2000 || d.getYear() > 2999) {
                e.add(field, "FORMAT");
                return null;
            }
            return d;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
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

    private static BusinessException duplicateRev(DuplicateKeyException e) {
        // 같은 리비전(uq_mst_bom_rev) 또는 동시에 만든 두 번째 작성 중 BOM(uq_mst_bom_draft)
        if (PgErrors.constraint(e).filter("uq_mst_bom_draft"::equals).isPresent()) {
            return new BusinessException(HbErrorCode.BOM_DRAFT_EXISTS);
        }
        return new BusinessException(HbErrorCode.INVALID_VALUE)
                .withFieldErrors(FieldErrors.create().add("rev", "DUPLICATE").list());
    }

    private static BusinessException rowConflict(String rowKey) {
        return new BusinessException(HbErrorCode.CONFLICT)
                .withFieldErrors(List.of(new FieldErrors.FieldError(rowKey, null, "CONFLICT")));
    }

    public static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
