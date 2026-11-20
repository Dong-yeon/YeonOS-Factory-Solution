package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.decimal;
import static io.smarthappy.module.cms.CmsSupport.fieldError;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.text;
import static io.smarthappy.module.cms.CmsSupport.yn;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsDtos.MwoRow;
import io.smarthappy.module.cms.CmsDtos.TargetRow;
import io.smarthappy.module.cms.CmsSpareDtos.ItemRef;
import io.smarthappy.module.cms.CmsSpareDtos.PartForm;
import io.smarthappy.module.cms.CmsSpareDtos.PartOption;
import io.smarthappy.module.cms.CmsSpareDtos.PartRow;
import io.smarthappy.module.cms.CmsSpareDtos.ReturnForm;
import io.smarthappy.module.cms.CmsSpareDtos.SpareForm;
import io.smarthappy.module.cms.CmsSpareDtos.SpareExport;
import io.smarthappy.module.cms.CmsSpareDtos.SparePage;
import io.smarthappy.module.cms.CmsSpareDtos.SpareSort;
import io.smarthappy.module.cms.CmsSpareDtos.SpareRow;
import io.smarthappy.module.cms.CmsSpareDtos.UsageExport;
import io.smarthappy.module.cms.CmsSpareDtos.UsageRow;
import io.smarthappy.module.cms.link.SpareIssueLink;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.DateRanges;
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
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 설비 예비품 (cms-spare) + 작업지시 사용 예비품 (cms-mwo) — docs/33 4.5, D9.
 * 예비품은 품목 마스터 그대로이고 CMS 는 설비 연결·사용 기록만 둔다(수량·재고는 WMS). 사용 기록은 작업지시가 OPEN·WIP 일 때만 추가·삭제.
 */
@Service
@RequiredArgsConstructor
public class SpareService {

    static final String PROG = "cms-spare";
    static final int MAX_ROWS = 1000;
    static final int MAX_DAYS = 366;
    /** 단위 자릿수를 모르면 numeric(18,6) 의 최대 */
    static final int QTY_MAX_SCALE = 6;
    static final Set<String> KINDS = Set.of("EQUIP", "CLASS");

    private final CmsSpareMapper mapper;
    private final CmsMapper cms;
    private final CodeService codes;
    private final DateRanges ranges;
    private final InboxService inbox;
    private final BizClock clock;
    private final ObjectProvider<SpareIssueLink> spareLink;
    private final ExcelExportService excel;
    private final PolicyService policies;

    /** 단위별 자릿수(공통코드 UNIT attrs.scale) — 요청마다 한 번 */
    Map<String, Integer> unitScales() {
        Map<String, Integer> m = new HashMap<>();
        codes.codes("UNIT", Locale.KOREAN).forEach(c -> {
            if (c.attrs() != null && c.attrs().get("scale") instanceof Number n) {
                m.put(c.code(), Math.max(0, Math.min(n.intValue(), QTY_MAX_SCALE)));
            }
        });
        return m;
    }

    static int scaleOf(Map<String, Integer> scales, String unitCd) {
        return unitCd == null ? QTY_MAX_SCALE : scales.getOrDefault(unitCd, QTY_MAX_SCALE);
    }

    // ------------------------------------------------------------------ 설비 예비품

    /** 검색어 = 설비·클래스·품목 코드·이름 — 품목으로 찾으면 "이 품목을 쓰는 설비"(역방향) */
    @Transactional(readOnly = true)
    public List<SpareRow> list(String keyword, String useYn, String targetKind) {
        checkListArgs(keyword, useYn, targetKind);
        List<SpareRow> rows = mapper.findSpares(blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)),
                blank(useYn) ? null : useYn, blank(targetKind) ? null : targetKind, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_ROWS);
        }
        Map<String, Integer> scales = unitScales();
        rows.forEach(r -> r.setScale(scaleOf(scales, r.getUnitCd())));
        return rows;
    }

    private static void checkListArgs(String keyword, String useYn, String targetKind) {
        if (!blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn) || keyword != null && keyword.length() > 100
                || !blank(targetKind) && !KINDS.contains(targetKind)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link SpareSort}) */
    @Transactional(readOnly = true)
    public SparePage grid(String keyword, String useYn, String targetKind, Integer page, Integer size, List<String> sort, Boolean total) {
        checkListArgs(keyword, useYn, targetKind);
        String k = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        String u = blank(useYn) ? null : useYn;
        String t = blank(targetKind) ? null : targetKind;
        PageQuery pq = PageQuery.parse(page, size, sort, total, SpareSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("targetCd,asc", "itemCd,asc"));
        List<SpareRow> rows = mapper.findSparePage(k, u, t, pq);
        Map<String, Integer> scales = unitScales();
        rows.forEach(r -> r.setScale(scaleOf(scales, r.getUnitCd())));
        return new SparePage(rows, pq.isWithTotal() ? mapper.countSpares(k, u, t) : null);
    }

    /** grid 열(화면 연결 표와 같은 칸) */
    static final List<ColumnSpec> SPARE_GRID_COLUMNS = List.of(
            ColumnSpec.code("targetKind", "cms.spare.col.targetKind", "CMS_SPARE_KIND", 90).withSort(),
            ColumnSpec.text("targetCd", "cms.spare.col.target", 110).withSort(),
            ColumnSpec.text("targetNm", "cms.mwo.col.targetNm", 150),
            ColumnSpec.text("itemCd", "cms.spare.col.item", 110).withSort(),
            ColumnSpec.text("itemNm", "cms.spare.col.itemNm", 150).withSort(),
            ColumnSpec.text("spec", "cms.spare.col.spec", 110),
            new ColumnSpec("installQty", "cms.spare.col.installQty", ColumnType.DEC, QTY_MAX_SCALE, null, 90, true, true, 2, 60, false, 0),
            ColumnSpec.text("unitCd", "cms.spare.col.unit", 60),
            ColumnSpec.code("keySpareYn", "cms.spare.col.keySpare", "CMS_SPARE_KEY", 80).withSort(),
            ColumnSpec.of("useYn", "cms.col.useYn", ColumnType.YN, 70).withSort());

    public List<ColumnSpec> gridColumns() {
        return SPARE_GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public SpareRow get(long spareId) {
        SpareRow s = mapper.findSpare(spareId);
        if (s == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        s.setScale(scaleOf(unitScales(), s.getUnitCd()));
        return s;
    }

    @Transactional
    public long create(SpareForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_SPARE_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        SpareRow s = new SpareRow();
        String kind = blank(in.targetKind()) ? "EQUIP" : in.targetKind().strip();
        if (!KINDS.contains(kind)) {
            e.add("targetKind", "CODE");
        } else if (blank(in.targetCd())) {
            e.add("targetCd", "REQUIRED");
        } else {
            String cd = in.targetCd().strip().toUpperCase(Locale.ROOT);
            if ("EQUIP".equals(kind)) {
                TargetRow t = cd.length() > 30 ? null : cms.findEquipTarget(cd);
                if (t == null) {
                    e.add("targetCd", "NO_SUCH_CODE");
                } else if (!"Y".equals(t.getUseYn())) {
                    e.add("targetCd", "INACTIVE");
                } else {
                    s.setEquipId(t.getId());
                }
            } else {
                Long classId = cd.length() > 30 ? null : cms.findClassId(cd, false);
                if (classId == null) {
                    e.add("targetCd", "NO_SUCH_CODE");
                } else if (cms.findClassId(cd, true) == null) {
                    e.add("targetCd", "INACTIVE");
                } else {
                    s.setEquipClassId(classId);
                }
            }
        }
        ItemRef item = item(in.itemCd(), "itemCd", e);
        if (item != null) {
            s.setItemId(item.getItemId());
        }
        apply(s, in, item == null ? null : item.getUnitCd(), e);
        e.throwIfAny();
        try {
            return mapper.insertSpare(s, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("itemCd", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 수정 — 대상·품목 고정, 수량·핵심·사용·비고. 다시 사용(N → Y)은 새 참조라 사용 중 품목·설비만(트리거) */
    @Transactional
    public void update(long spareId, SpareForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.lockSpare(spareId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        SpareRow cur = mapper.findSpare(spareId);
        FieldErrors e = FieldErrors.create();
        if (!blank(in.targetKind()) && !in.targetKind().strip().equals(cur.getTargetKind())) {
            e.add("targetKind", "FIXED");
        }
        if (!blank(in.targetCd()) && !in.targetCd().strip().toUpperCase(Locale.ROOT).equals(cur.getTargetCd())) {
            e.add("targetCd", "FIXED");
        }
        if (!blank(in.itemCd()) && !in.itemCd().strip().toUpperCase(Locale.ROOT).equals(cur.getItemCd())) {
            e.add("itemCd", "FIXED");
        }
        boolean wasOff = "N".equals(cur.getUseYn());
        apply(cur, in, cur.getUnitCd(), e);
        if (wasOff && "Y".equals(cur.getUseYn()) && !"Y".equals(cur.getItemUseYn())) {
            e.add("useYn", "INACTIVE");   // 사용 중지 품목에 다시 걸지 않는다
        }
        e.throwIfAny();
        cur.setRowVer(in.rowVer());
        try {
            if (mapper.updateSpare(cur, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    private void apply(SpareRow s, SpareForm in, String unitCd, FieldErrors e) {
        int scale = scaleOf(unitScales(), unitCd);
        BigDecimal qty = decimal(in.installQty(), "installQty", scale, e);
        if (blank(in.installQty())) {
            e.add("installQty", "REQUIRED");
        } else if (qty != null && qty.signum() <= 0) {
            e.add("installQty", "POSITIVE");
        }
        s.setInstallQty(qty);
        s.setKeySpareYn(yn(in.keySpareYn(), "N", "keySpareYn", e));
        s.setUseYn(yn(in.useYn(), "Y", "useYn", e));
        s.setRemark(text(in.remark(), "remark", 500, false, e));
    }

    /** 품목 코드 → 같은 회사 사용 중 품목 (사용 중지 = 새 참조 차단) */
    private ItemRef item(String itemCd, String field, FieldErrors e) {
        if (blank(itemCd)) {
            e.add(field, "REQUIRED");
            return null;
        }
        String cd = itemCd.strip().toUpperCase(Locale.ROOT);
        ItemRef i = cd.length() > 40 ? null : mapper.findItemByCd(cd);
        if (i == null) {
            e.add(field, "NO_SUCH_CODE");
            return null;
        }
        if (!"Y".equals(i.getUseYn())) {
            e.add(field, "INACTIVE");
            return null;
        }
        return i;
    }

    /**
     * 사용 이력 — 기록일 기간(회사 시간대, 필수, 최대 366일) + 설비·클래스·품목 코드(선택). 클래스 = 그 클래스 설비의 작업지시
     */
    @Transactional(readOnly = true)
    public List<UsageRow> usage(String from, String to, String equipCd, String classCd, String itemCd) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        e.throwIfAny();
        ranges.check(f, t, MAX_DAYS);
        Long equipId = null;
        Long classId = null;
        Long itemId = null;
        if (!blank(equipCd)) {
            TargetRow r = equipCd.strip().length() > 30 ? null : cms.findEquipTarget(equipCd.strip().toUpperCase(Locale.ROOT));
            equipId = r == null ? -1L : r.getId();
        }
        if (!blank(classCd)) {
            Long c = classCd.strip().length() > 30 ? null : cms.findClassId(classCd.strip().toUpperCase(Locale.ROOT), false);
            classId = c == null ? -1L : c;
        }
        if (!blank(itemCd)) {
            ItemRef i = itemCd.strip().length() > 40 ? null : mapper.findItemByCd(itemCd.strip().toUpperCase(Locale.ROOT));
            itemId = i == null ? -1L : i.getItemId();
        }
        ZoneId z = clock.zone();
        OffsetDateTime a = f.atStartOfDay(z).toOffsetDateTime();
        OffsetDateTime b = t.plusDays(1).atStartOfDay(z).toOffsetDateTime();
        List<UsageRow> rows = mapper.findUsage(a, b, equipId, classId, itemId, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_ROWS);
        }
        Map<String, Integer> scales = unitScales();
        rows.forEach(r -> {
            r.setScale(scaleOf(scales, r.getUnitCd()));
            r.setUsedAt(CmsSupport.at(r.getUsedAt(), z));
            r.setCreatedAt(CmsSupport.at(r.getCreatedAt(), z));
        });
        return rows;
    }

    // ------------------------------------------------------------------ 작업지시 사용 예비품

    @Transactional(readOnly = true)
    public List<PartRow> parts(long mwoId) {
        MwoRow m = mwo(mwoId);
        Map<String, Integer> scales = unitScales();
        List<PartRow> rows = mapper.findParts(mwoId);
        rows.forEach(r -> r.setScale(scaleOf(scales, r.getUnitCd())));
        // 반납 누계(C18) — 출고는 완료 때라 완료·확인만 묻는다. 키 없는 줄 = 모름(null 그대로)
        if (!rows.isEmpty() && ("DONE".equals(m.getStatus()) || "CLOSED".equals(m.getStatus()))) {
            Map<Long, BigDecimal> returned = SpareIssueLink.of(spareLink).returnedQty(rows.stream().map(PartRow::getPartId).toList());
            if (returned != null) {
                rows.forEach(r -> r.setReturnedQty(returned.get(r.getPartId())));
            }
        }
        return rows;
    }

    /** 품목 후보 — 작업지시 대상 설비의 연결 + 그 클래스 연결 (금형·지그 작업지시는 후보 없음, 품목 코드 직접 입력) */
    @Transactional(readOnly = true)
    public List<PartOption> partOptions(long mwoId) {
        MwoRow m = mwo(mwoId);
        if (m.getEquipId() == null) {
            return List.of();
        }
        return mapper.partOptions(m.getEquipId(), m.getTargetClassId());
    }

    /** 추가 — 작업지시 OPEN·WIP, 사용 중 품목(같은 회사), 수량 > 0·품목 단위 자릿수. 단위는 품목 기본 단위로 스냅샷 */
    @Transactional
    public long addPart(long mwoId, PartForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_MWO_PART_ADD");
        lockOpen(mwoId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ItemRef item = item(in.itemCd(), "itemCd", e);
        int scale = scaleOf(unitScales(), item == null ? null : item.getUnitCd());
        BigDecimal qty = decimal(in.qty(), "qty", scale, e);
        if (blank(in.qty())) {
            e.add("qty", "REQUIRED");
        } else if (qty != null && qty.signum() <= 0) {
            e.add("qty", "POSITIVE");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        try {
            return mapper.insertPart(mwoId, item.getItemId(), qty, item.getUnitCd(), remark, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void deletePart(long mwoId, long partId) {
        me();
        lockOpen(mwoId);
        try {
            if (mapper.deletePart(mwoId, partId) == 0) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /**
     * 반납 — 완료(DONE·CLOSED) 뒤 덜 쓴 것을 WMS 재고로 되돌린다(docs/33 11.15, D32). CMS 사용 기록은 바꾸지 않고 WMS 원장에 + 행.
     * 누적 반납 ≤ 출고·요청 id 멱등은 WMS 가 검사(넘으면 409 WMS_MRO_RETURN_OVER). false = 그 줄의 출고 원장이 없음(스위치 꺼짐·원장 밖 품목 — 오류 아님)
     */
    @Transactional
    public boolean returnPart(long mwoId, long partId, ReturnForm in) {
        LoginUser me = me();
        if (cms.lockMwoShare(mwoId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String st = cms.findMwo(mwoId).getStatus();
        if (!"DONE".equals(st) && !"CLOSED".equals(st)) {
            throw new BusinessException(HbErrorCode.CMS_MWO_STATE);
        }
        PartRow part = mapper.findParts(mwoId).stream().filter(p -> p.getPartId() == partId).findFirst()
                .orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        BigDecimal qty = decimal(in.qty(), "qty", scaleOf(unitScales(), part.getUnitCd()), e);
        if (blank(in.qty())) {
            e.add("qty", "REQUIRED");
        } else if (qty != null && qty.signum() <= 0) {
            e.add("qty", "POSITIVE");
        }
        String reason = text(in.reason(), "reason", 500, true, e);
        e.throwIfAny();
        return SpareIssueLink.of(spareLink).onPartReturn(partId, qty, reason, in.clientReqId(), me.loginId());
    }

    private MwoRow mwo(long mwoId) {
        MwoRow m = cms.findMwo(mwoId);
        if (m == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return m;
    }

    /** 작업지시 행 잠금 → OPEN·WIP 확인 (완료와 줄 세움 — 트리거도 같은 잠금) */
    private void lockOpen(long mwoId) {
        if (cms.lockMwo(mwoId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String st = cms.findMwo(mwoId).getStatus();
        if (!"OPEN".equals(st) && !"WIP".equals(st)) {
            throw new BusinessException(HbErrorCode.CMS_MWO_STATE);
        }
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_cms_equip_spare_equip_comp", "fk_cms_equip_spare_class_comp" -> fieldError("targetCd", "NO_SUCH_CODE");
            case "fk_cms_equip_spare_item_comp", "fk_cms_mwo_part_item_comp" -> fieldError("itemCd", "NO_SUCH_CODE");
            case "ck_cms_mwo_part_status" -> new BusinessException(HbErrorCode.CMS_MWO_STATE);
            default -> e;
        };
    }

    // ------------------------------------------------------------------ 엑셀(C15)

    /** 수량 칸 — 품목 단위마다 자릿수가 달라 최대 6자리, 끝 0 은 채우지 않는다(숫자 칸 그대로 — 합계를 낼 수 있게) */
    private static ColumnSpec qty(String field, String titleKey) {
        return new ColumnSpec(field, titleKey, ColumnType.DEC, QTY_MAX_SCALE, null, 90, false, true, 2, 60, false, 0);
    }

    static final List<ColumnSpec> SPARE_COLUMNS = List.of(
            ColumnSpec.code("targetKind", "cms.spare.col.targetKind", "CMS_SPARE_KIND", 90),
            ColumnSpec.text("targetCd", "cms.spare.col.target", 110),
            ColumnSpec.text("targetNm", "cms.mwo.col.targetNm", 160),
            ColumnSpec.text("itemCd", "cms.spare.col.item", 110),
            ColumnSpec.text("itemNm", "cms.spare.col.itemNm", 160),
            ColumnSpec.text("spec", "cms.spare.col.spec", 120),
            qty("installQty", "cms.spare.col.installQty"),
            ColumnSpec.text("unitCd", "cms.spare.col.unit", 60),
            ColumnSpec.code("keySpareYn", "cms.spare.col.keySpare", "CMS_SPARE_KEY", 80),
            ColumnSpec.of("useYn", "cms.col.useYn", ColumnType.YN, 70),
            ColumnSpec.text("remark", "cms.col.remark", 200));

    static final List<ColumnSpec> USAGE_COLUMNS = List.of(
            ColumnSpec.text("usedAtText", "cms.spare.col.usedAt", 130),
            ColumnSpec.text("mwoNo", "cms.mwo.col.mwoNo", 120),
            ColumnSpec.code("mwoType", "cms.mwo.col.type", "CMS_MWO_TYPE", 80),
            ColumnSpec.code("mwoStatus", "cms.mwo.col.status", "CMS_MWO_STATUS", 80),
            ColumnSpec.text("targetCd", "cms.mwo.col.target", 110),
            ColumnSpec.text("targetNm", "cms.mwo.col.targetNm", 160),
            ColumnSpec.text("itemCd", "cms.spare.col.item", 110),
            ColumnSpec.text("itemNm", "cms.spare.col.itemNm", 160),
            qty("qty", "cms.spare.col.qty"),
            ColumnSpec.text("unitCd", "cms.spare.col.unit", 60),
            ColumnSpec.text("createdBy", "cms.spare.col.recordedBy", 90));

    /** 연결 표 엑셀 — 목록과 같은 함수·조건·상한 */
    @Transactional(readOnly = true)
    public long exportSpares(SpareExport req, HttpServletResponse response) throws IOException {
        SpareExport r = req == null ? new SpareExport(null, null, null) : req;
        List<SpareRow> rows = list(r.keyword(), r.useYn(), r.targetKind());
        List<String> keys = new ArrayList<>();
        if (!blank(r.keyword())) {
            keys.add("keyword");
        }
        if (!blank(r.useYn())) {
            keys.add("useYn");
        }
        if (!blank(r.targetKind())) {
            keys.add("targetKind");
        }
        return excel.export(response, new ExportJob(PROG, "prog.cmsSpare", SPARE_COLUMNS, rows.size(), keys), w -> rows.forEach(w));
    }

    /** 사용 이력 엑셀 — 사용 이력 조회와 같은 함수·조건(기간 필수)·상한. 기록 시각은 회사 시간대 글자 */
    @Transactional(readOnly = true)
    public long exportUsage(UsageExport req, HttpServletResponse response) throws IOException {
        UsageExport r = req == null ? new UsageExport(null, null, null, null, null) : req;
        List<UsageRow> rows = usage(r.from(), r.to(), r.equipCd(), r.classCd(), r.itemCd());
        ZoneId z = clock.zone();
        rows.forEach(u -> u.setCreatedAt(u.getCreatedAt() == null ? null : u.getCreatedAt().atZoneSameInstant(z).toOffsetDateTime()));
        List<String> keys = new ArrayList<>(List.of("from", "to"));
        if (!blank(r.equipCd())) {
            keys.add("equipCd");
        }
        if (!blank(r.classCd())) {
            keys.add("classCd");
        }
        if (!blank(r.itemCd())) {
            keys.add("itemCd");
        }
        return excel.export(response, new ExportJob(PROG, "cms.spare.usageFile", USAGE_COLUMNS, rows.size(), keys), w -> rows.forEach(w));
    }
}
