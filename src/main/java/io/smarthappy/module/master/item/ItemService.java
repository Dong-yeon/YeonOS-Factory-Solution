package io.smarthappy.module.master.item;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.ext.ExtService;
import io.smarthappy.module.master.ext.ExtService.ErpItemCd;
import io.smarthappy.module.master.item.ItemDtos.ExportRequest;
import io.smarthappy.module.master.item.ItemDtos.IdVer;
import io.smarthappy.module.master.item.ItemDtos.ItemForm;
import io.smarthappy.module.master.item.ItemDtos.ItemRow;
import io.smarthappy.module.master.item.ItemDtos.ItemSearch;
import io.smarthappy.module.master.item.ItemDtos.ItemSort;
import io.smarthappy.module.master.item.ItemDtos.ItemValue;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 품목관리 (P1 1단계, docs/17 3장) — 구조는 거래처관리(표준 참조 구현)와 같다.
 *
 * - 품목코드: 대문자 정리, 자동차 부품 품번의 - . / 허용, 회사 안 유일
 * - 단위: 시스템 단위 또는 이 회사가 추가한 단위(사용 중). DB 트리거가 같은 규칙을 한 번 더 본다(다른 회사 단위 차단)
 * - 순질량: 10진 문자열 → BigDecimal. 0 이하·소수 6자리 초과·정수부 9자리 초과는 오류(반올림하지 않음)
 * - 삭제는 사용 안 함(use_yn = N)
 */
@Service
@RequiredArgsConstructor
public class ItemService {

    static final String PROG = "mst-item";
    static final Pattern ITEM_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._/-]{0,39}$");
    static final Pattern DECIMAL = Pattern.compile("^[0-9]{1,30}(\\.[0-9]{1,30})?$|^-[0-9]{1,30}(\\.[0-9]{1,30})?$");
    static final Pattern HS_CD = Pattern.compile("^[0-9]{6,10}$");
    static final Set<String> ITEM_TYPES = Set.of("FG", "SFG", "RM", "SUB", "CON");
    static final Set<String> PROC_TYPES = Set.of("MAKE", "BUY", "OUTSRC");
    static final List<String> DEFAULT_SORT = List.of("itemCd,asc");
    static final int MAX_DELETE = 1000;
    /** 채번 규칙 코드 (sys_numbering_rule — 관리자가 패턴을 바꿀 수 있다) */
    public static final String NUMBER_RULE = "ITEM";
    /** 이미 있는 코드라 넘기는 번호 상한 (한 요청) */
    static final int MAX_SKIP = 50;
    static final int MASS_SCALE = 6;
    static final int MASS_INT_DIGITS = 9;
    static final int PRICE_SCALE = 4;
    static final int PRICE_INT_DIGITS = 14;
    /** ERP 가 주인인 품목 필드 (docs/07 3.1, docs/22 2장 · 표준단가 docs/29_P3 29장 CQ-2). 정책 ERP_ITEM_SYNC_MODE=PULL 이면 MES 에서 바꾸지 않는다 */
    public static final List<String> ERP_OWNED = List.of("itemCd", "itemNm", "spec", "itemType", "procType", "unitCd", "stdPrice");

    /** 목록·엑셀 공용 열 정의. priority·flex 는 docs/15 R6 (코드·명 항상, 규격은 남는 폭) */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("itemCd", "mstItem.col.itemCd", 140).withSort().withPriority(1),
            ColumnSpec.text("itemNm", "mstItem.col.itemNm", 200).withSort().withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.text("erpItemCd", "mstItem.col.erpItemCd", 130).withPriority(2),   // 사용 중 ERP 시스템 매핑(docs/17 9장)
            ColumnSpec.text("spec", "mstItem.col.spec", 160).withFlex(),
            ColumnSpec.code("itemType", "mstItem.col.itemType", "ITEM_TYPE", 90).withSort(),
            ColumnSpec.code("procType", "mstItem.col.procType", "PROC_TYPE", 90).withSort(),
            ColumnSpec.code("unitCd", "mstItem.col.unitCd", "UNIT", 70),
            ColumnSpec.of("lotCtrlYn", "mstItem.col.lotCtrlYn", ColumnSpec.ColumnType.YN, 80).withPriority(3),
            ColumnSpec.dec("netMassKg", "mstItem.col.netMassKg", MASS_SCALE, 110).withMinScale(0).withPriority(3),   // 저장 자릿수 그대로, 끝 0 은 안 채움
            ColumnSpec.text("hsCd", "mstItem.col.hsCd", 100).withPriority(3),
            ColumnSpec.dec("stdPrice", "mstItem.col.stdPrice", PRICE_SCALE, 110).withMinScale(0).withPriority(3),
            ColumnSpec.of("useYn", "mstItem.col.useYn", ColumnSpec.ColumnType.YN, 70).withSort(),
            ColumnSpec.of("updatedAt", "mstItem.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3),
            ColumnSpec.text("updatedBy", "mstItem.col.updatedBy", 100).withPriority(3));

    private final ItemMapper mapper;
    private final PolicyService policies;
    private final InboxService inbox;
    private final ExcelExportService excel;
    private final NumberingService numbering;
    private final ExtService ext;

    public record Page(List<ItemRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String itemCd, String itemNm, String itemType, String procType, String useYn, Integer page,
            Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, ItemSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        ItemSearch q = search(itemCd, itemNm, itemType, procType, useYn, pq);
        return new Page(mapper.findPage(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    @Transactional(readOnly = true)
    public ItemRow detail(long itemId) {
        ItemRow row = mapper.findById(itemId);
        if (row == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return row;
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    /** 화면 설정 — ERP 동기화 방식과 잠글 칸 */
    @Transactional(readOnly = true)
    public Map<String, Object> settings() {
        String mode = policies.value(Policies.ERP_ITEM_SYNC_MODE);
        return Map.of("erpSyncMode", mode, "erpOwnedFields", "PULL".equals(mode) ? ERP_OWNED : List.of(),
                "itemCdMode", policies.value(Policies.ITEM_CD_MODE));
    }

    /** ERP 가 품목 주인인가 (정책 PULL) */
    public boolean erpPull() {
        return "PULL".equals(policies.value(Policies.ERP_ITEM_SYNC_MODE));
    }

    /** ERP 주인 필드 중 지금 값과 다른 칸 — 값이 비었으면(null) 비교 대상인 지금 값도 null 이어야 같다 */
    public static List<String> erpOwnedChanged(Map<String, String> cur, ItemValue v) {
        Map<String, String> next = new java.util.HashMap<>();
        next.put("itemCd", v.itemCd());
        next.put("itemNm", v.itemNm());
        next.put("spec", v.spec());
        next.put("itemType", v.itemType());
        next.put("procType", v.procType());
        next.put("unitCd", v.unitCd());
        next.put("stdPrice", plain(v.stdPrice()));
        return ERP_OWNED.stream().filter(f -> !java.util.Objects.equals(cur.get(f), next.get(f))).toList();
    }

    private static Map<String, String> owned(ItemRow r) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("itemCd", r.getItemCd());
        m.put("itemNm", r.getItemNm());
        m.put("spec", r.getSpec());
        m.put("itemType", r.getItemType());
        m.put("procType", r.getProcType());
        m.put("unitCd", r.getUnitCd());
        m.put("stdPrice", plain(r.getStdPrice()));
        return m;
    }

    /** 단가 비교·엑셀 값 — 끝 0 을 떼고 지수 없이(1000.0000 과 1000 은 같다) */
    public static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ 저장

    @Transactional
    public long create(ItemForm form) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_ITEM_CREATE");
        if (erpPull()) {
            // 품목은 ERP 에서 들어온다 — MES 신규 금지 (docs/22 2장)
            throw new BusinessException(HbErrorCode.ERP_OWNED);
        }
        if (form == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        boolean auto = autoCd();
        FieldErrors e = FieldErrors.create();
        if (auto && !blank(form.itemCd())) {
            // AUTO 인데 코드를 보냄 — 조용히 버리지 않고 알린다 (docs/17 9.2 (a))
            e.add("itemCd", "AUTO_NUMBERED");
        }
        ItemValue v = validate(auto ? withCode(form, AUTO_PLACEHOLDER) : form, cd -> !auto && mapper.existsCd(cd, null),
                unit -> unitAllowed(unit, null), e);
        ErpItemCd erp = ext.checkErpItemCd(form.erpItemCd(), "erpItemCd", e);
        e.throwIfAny();
        if (auto) {
            v = v.withItemCd(nextItemCd());
        }
        long id;
        try {
            id = mapper.insert(v, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw duplicate();
        }
        if (erp != null) {
            ext.attachErpItemCd(erp, id, "erpItemCd", me.loginId());
        }
        return id;
    }

    /** 정책 ITEM_CD_MODE = AUTO — 신규 품목코드를 채번 규칙 ITEM 으로 (docs/17 9장) */
    public boolean autoCd() {
        return "AUTO".equals(policies.value(Policies.ITEM_CD_MODE));
    }

    /**
     * 채번 규칙 ITEM 으로 다음 품목코드 (업무 트랜잭션 안 — 롤백되면 번호도 롤백, 결번 없음).
     * 규칙이 없는 회사는 기본 IT{seq:6}·초기화 없음으로 만든다. 품목코드 형식에 안 맞는 규칙이면 409 ITEM_CD_RULE,
     * 이미 있는 코드(MANUAL 때 직접 넣은 같은 번호)는 넘긴다 — 그 번호는 이미 품목이 쓰고 있어 결번이 아니다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextItemCd() {
        for (int i = 0; i < MAX_SKIP; i++) {
            String cd = numbering.nextOrDefault(NUMBER_RULE, "품목 코드", "IT{seq:6}", "NONE");
            if (!ITEM_CD.matcher(cd).matches()) {
                throw new BusinessException(HbErrorCode.ITEM_CD_RULE);
            }
            if (!mapper.existsCd(cd, null)) {
                return cd;
            }
        }
        throw new BusinessException(HbErrorCode.NUMBERING_EXHAUSTED);
    }

    /** 검증용 자리표시 코드 — AUTO 신규는 형식·중복 검사 대상이 아니다(번호는 검증 뒤에 받는다) */
    static final String AUTO_PLACEHOLDER = "AUTO";

    static ItemForm withCode(ItemForm f, String cd) {
        return new ItemForm(cd, f.itemNm(), f.spec(), f.itemType(), f.procType(), f.unitCd(), f.lotCtrlYn(), f.netMassKg(),
                f.hsCd(), f.remark(), f.useYn(), f.stdPrice(), f.rowVer(), f.erpItemCd());
    }

    @Transactional
    public void update(long itemId, ItemForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ItemRow cur = mapper.findById(itemId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        // 시스템 코드는 만든 뒤 고정 (docs/17 9.2 (b)) — 비우면 그대로. PULL 은 아래 ERP 주인 칸 검사가 ERP_OWNED 로 알린다(지금 동작)
        ItemForm form = blank(in.itemCd()) ? withCode(in, cur.getItemCd()) : in;
        if (!erpPull() && !cur.getItemCd().equals(form.itemCd().strip().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("itemCd", "FIXED").list());
        }
        ItemValue v = validate(form, itemId);
        if (erpPull()) {
            List<String> changed = erpOwnedChanged(owned(cur), v);
            if (!changed.isEmpty()) {
                FieldErrors fe = FieldErrors.create();
                changed.forEach(f -> fe.add(f, "ERP_OWNED"));
                throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(fe.list());
            }
        }
        if (cur != null && !cur.getUnitCd().equals(v.unitCd()) && mapper.unitInUse(itemId)) {
            // BOM·단위 환산에 쓰인 품목의 기본 단위는 바꾸지 않는다 — 행 수량·환산의 뜻이 바뀐다 (docs/18 §10, V018)
            throw new BusinessException(HbErrorCode.IN_USE)
                    .withFieldErrors(FieldErrors.create().add("unitCd", "IN_USE").list());
        }
        int n;
        try {
            n = mapper.update(itemId, v, me.loginId());
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        if (n == 0) {
            throw notFoundOrConflict(itemId);
        }
    }

    /** 사용 안 함 처리 (여러 건). 하나라도 충돌이면 전부 취소 */
    @Transactional
    public int disable(List<IdVer> items) {
        LoginUser me = me();
        if (items == null || items.isEmpty() || items.size() > MAX_DELETE
                || items.stream().anyMatch(i -> i == null || i.id() == null || i.rowVer() == null)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        for (IdVer i : items) {
            if (mapper.disable(i.id(), i.rowVer(), me.loginId()) == 0) {
                throw notFoundOrConflict(i.id());
            }
        }
        return items.size();
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(ExportRequest req, HttpServletResponse response) throws IOException {
        ExportRequest r = req == null ? new ExportRequest(null, null, null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, r.columns());
        ItemSearch q = search(r.itemCd(), r.itemNm(), r.itemType(), r.procType(), r.useYn(),
                PageQuery.sortOnly(r.sort(), ItemSort.values(), DEFAULT_SORT));
        List<String> keys = new ArrayList<>();
        if (q.getItemCdLike() != null) {
            keys.add("itemCd");
        }
        if (q.getItemNmLike() != null) {
            keys.add("itemNm");
        }
        if (q.getItemType() != null) {
            keys.add("itemType");
        }
        if (q.getProcType() != null) {
            keys.add("procType");
        }
        if (q.getUseYn() != null) {
            keys.add("useYn");
        }
        return excel.export(response, new ExportJob(PROG, "prog.mstItem", cols, mapper.count(q), keys), w -> {
            try (Cursor<ItemRow> c = mapper.cursor(q)) {
                c.forEach(w);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // ------------------------------------------------------------------ 검증

    private ItemSearch search(String itemCd, String itemNm, String itemType, String procType, String useYn,
            PageQuery pq) {
        String cd = blank(itemCd) ? null : itemCd.strip().toUpperCase(Locale.ROOT);
        String nm = blank(itemNm) ? null : itemNm.strip();
        if ((cd != null && cd.length() > 100) || (nm != null && nm.length() > 200)
                || (!blank(itemType) && !ITEM_TYPES.contains(itemType))
                || (!blank(procType) && !PROC_TYPES.contains(procType))
                || (!blank(useYn) && !useYn.equals("Y") && !useYn.equals("N"))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new ItemSearch(cd == null ? null : likeEscape(cd) + "%", nm == null ? null : "%" + likeEscape(nm) + "%",
                blank(itemType) ? null : itemType, blank(procType) ? null : procType, blank(useYn) ? null : useYn, pq);
    }

    /** 폼 검증 — 오류를 모두 모은 뒤 400. 통과하면 정리된 값 */
    ItemValue validate(ItemForm in, Long exceptId) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ItemValue v = validate(in, cd -> mapper.existsCd(cd, exceptId), unit -> unitAllowed(unit, exceptId), e);
        e.throwIfAny();
        return v;
    }

    /**
     * 검증 본체 — DB 확인(코드 중복·단위)은 밖에서 넣는다. 화면 저장은 한 건씩 조회, 엑셀 올리기는 미리 읽은 목록으로(행마다 조회하지 않게).
     * 오류는 e 에 모으고, 값은 오류가 있어도 정리된 만큼 돌려준다.
     */
    ItemValue validate(ItemForm in, Predicate<String> codeTaken, Predicate<String> unitOk, FieldErrors e) {
        String cd = blank(in.itemCd()) ? null : in.itemCd().strip().toUpperCase(Locale.ROOT);
        if (cd == null) {
            e.add("itemCd", "REQUIRED");
        } else if (cd.length() > 40) {
            e.add("itemCd", "TOO_LONG", 40);
        } else if (!ITEM_CD.matcher(cd).matches()) {
            e.add("itemCd", "FORMAT");
        } else if (codeTaken.test(cd)) {
            e.add("itemCd", "DUPLICATE");
        }
        String nm = text(in.itemNm(), "itemNm", 200, true, e);
        String spec = text(in.spec(), "spec", 200, false, e);
        code(in.itemType(), "itemType", ITEM_TYPES, e);
        code(in.procType(), "procType", PROC_TYPES, e);
        String unit = blank(in.unitCd()) ? null : in.unitCd().strip();
        if (unit == null) {
            e.add("unitCd", "REQUIRED");
        } else if (!unitOk.test(unit)) {
            e.add("unitCd", "CODE");
        }
        String lot = blank(in.lotCtrlYn()) ? "Y" : in.lotCtrlYn();
        if (!lot.equals("Y") && !lot.equals("N")) {
            e.add("lotCtrlYn", "CODE");
        }
        BigDecimal mass = mass(in.netMassKg(), e);
        String hs = null;
        if (!blank(in.hsCd())) {
            hs = in.hsCd().replaceAll("[\\s.-]", "");
            if (!HS_CD.matcher(hs).matches()) {
                e.add("hsCd", "FORMAT");
            }
        }
        String remark = text(in.remark(), "remark", 1000, false, e);
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!use.equals("Y") && !use.equals("N")) {
            e.add("useYn", "CODE");
        }
        BigDecimal price = price(in.stdPrice(), e);
        return new ItemValue(cd, nm, spec, in.itemType(), in.procType(), unit, lot, mass, hs, remark, use, price, in.rowVer());
    }

    /** 단위: 사용 중인 단위, 또는 수정인데 지금 쓰고 있는 단위 그대로(나중에 사용 중지된 단위도 품목 수정은 가능) */
    private boolean unitAllowed(String unit, Long exceptId) {
        if (mapper.unitUsable(unit)) {
            return true;
        }
        ItemRow cur = exceptId == null ? null : mapper.findById(exceptId);
        return cur != null && unit.equals(cur.getUnitCd());
    }

    /** 순질량: 10진 문자열만(쉼표·지수·NaN 거부), 반올림하지 않는다 */
    static BigDecimal mass(String raw, FieldErrors e) {
        if (blank(raw)) {
            return null;
        }
        String s = raw.strip();
        if (s.length() > 40 || !DECIMAL.matcher(s).matches()) {
            e.add("netMassKg", "FORMAT");
            return null;
        }
        BigDecimal v = new BigDecimal(s).stripTrailingZeros();
        if (v.signum() <= 0) {
            e.add("netMassKg", "POSITIVE");
            return null;
        }
        if (v.scale() > MASS_SCALE) {
            e.add("netMassKg", "SCALE", MASS_SCALE);
            return null;
        }
        if (v.precision() - v.scale() > MASS_INT_DIGITS) {
            e.add("netMassKg", "INT_DIGITS", MASS_INT_DIGITS);
            return null;
        }
        return v;
    }

    /** 표준단가: 비우면 단가 없음(0 과 다름), 10진 문자열만·0 이상·소수 4자리·정수 14자리, 반올림하지 않는다 */
    static BigDecimal price(String raw, FieldErrors e) {
        if (blank(raw)) {
            return null;
        }
        String s = raw.strip();
        if (s.length() > 40 || !DECIMAL.matcher(s).matches()) {
            e.add("stdPrice", "FORMAT");
            return null;
        }
        BigDecimal v = new BigDecimal(s);
        if (v.signum() < 0) {
            e.add("stdPrice", "NOT_NEGATIVE");
            return null;
        }
        v = v.signum() == 0 ? BigDecimal.ZERO : v.stripTrailingZeros();
        if (v.scale() > PRICE_SCALE) {
            e.add("stdPrice", "SCALE", PRICE_SCALE);
            return null;
        }
        if (v.precision() - v.scale() > PRICE_INT_DIGITS) {
            e.add("stdPrice", "INT_DIGITS", PRICE_INT_DIGITS);
            return null;
        }
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    private static void code(String v, String field, Set<String> allowed, FieldErrors e) {
        if (blank(v)) {
            e.add(field, "REQUIRED");
        } else if (!allowed.contains(v)) {
            e.add(field, "CODE");
        }
    }

    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (CONTROL.matcher(s).find()) {
            e.add(field, "FORMAT");          // NUL 등 제어 문자(탭·줄바꿈 제외) — DB 가 500 으로 거부하기 전에
        } else if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    private static BusinessException duplicate() {
        // 동시에 같은 코드로 저장 — 먼저 들어간 쪽이 이긴다
        return new BusinessException(HbErrorCode.INVALID_VALUE)
                .withFieldErrors(FieldErrors.create().add("itemCd", "DUPLICATE").list());
    }

    private BusinessException notFoundOrConflict(long itemId) {
        return mapper.findById(itemId) == null ? new BusinessException(HbErrorCode.NOT_FOUND)
                : new BusinessException(HbErrorCode.CONFLICT);
    }

    static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
