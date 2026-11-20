package io.smarthappy.module.master.cust;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.cust.CustDtos.BatchResult;
import io.smarthappy.module.master.cust.CustDtos.ContactBatch;
import io.smarthappy.module.master.cust.CustDtos.ContactItem;
import io.smarthappy.module.master.cust.CustDtos.CreatedKey;
import io.smarthappy.module.master.cust.CustDtos.CustDetail;
import io.smarthappy.module.master.cust.CustDtos.CustForm;
import io.smarthappy.module.master.cust.CustDtos.CustRow;
import io.smarthappy.module.master.cust.CustDtos.CustSearch;
import io.smarthappy.module.master.cust.CustDtos.CustSort;
import io.smarthappy.module.master.cust.CustDtos.ExportRequest;
import io.smarthappy.module.master.cust.CustDtos.IdVer;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
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
 * 거래처관리 — 이후 모든 기준정보 화면의 표준 참조 구현.
 *
 * - 검증은 끝까지 돌려 필드 오류를 모아 한 번에 400 (FieldErrors)
 * - 수정·삭제는 rowVer 필수, 0행이면 409 CONFLICT
 * - 삭제는 사용 안 함(use_yn = N) — 앱 계정에 DELETE 권한이 없다
 * - 담당자(디테일)는 일괄 저장 규약(created·updated·deleted, 한 트랜잭션, 거래처 rowVer 로 동시 편집 감지)
 * - 엑셀은 목록과 같은 조건·정렬 SQL 의 커서
 */
@Service
@RequiredArgsConstructor
public class CustService {

    static final String PROG = "mst-cust";
    static final Pattern CUST_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,29}$");
    static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,}$");
    static final Pattern TEL = Pattern.compile("^[0-9+()\\- ]{2,30}$");
    static final Set<String> TYPES = Set.of("SALES", "PURCHASE", "BOTH");
    static final List<String> DEFAULT_SORT = List.of("custCd,asc");
    static final int MAX_DELETE = 1000;

    /** 목록·엑셀 공용 열 정의 (docs/09 G2) — 담당자 연락처 같은 개인정보는 여기 없다. priority·flex 는 docs/15 4.6 표 */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("custCd", "mstCust.col.custCd", 110).withSort().withPriority(1),
            ColumnSpec.text("custNm", "mstCust.col.custNm", 220).withSort().withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.code("custType", "mstCust.col.custType", "CUST_TYPE", 100).withSort(),
            ColumnSpec.text("bizNo", "mstCust.col.bizNo", 120).withPriority(3),
            ColumnSpec.text("ceoNm", "mstCust.col.ceoNm", 100).withPriority(3),
            ColumnSpec.text("tel", "mstCust.col.tel", 120),
            ColumnSpec.of("contactCnt", "mstCust.col.contactCnt", ColumnSpec.ColumnType.INT, 80).withPriority(3),
            ColumnSpec.of("useYn", "mstCust.col.useYn", ColumnSpec.ColumnType.YN, 80).withSort(),
            ColumnSpec.of("updatedAt", "mstCust.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3),
            ColumnSpec.text("updatedBy", "mstCust.col.updatedBy", 100).withPriority(3));

    private final CustMapper mapper;
    private final PolicyService policies;
    private final InboxService inbox;
    private final PermissionService permissions;
    private final ExcelExportService excel;

    public record Page(List<CustRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String custCd, String custNm, String custType, String useYn, Integer page, Integer size,
            List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, CustSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        CustSearch q = search(custCd, custNm, custType, useYn, pq);
        return new Page(mapper.findPage(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    @Transactional(readOnly = true)
    public CustDetail detail(long custId) {
        CustRow row = mapper.findById(custId);
        if (row == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new CustDetail(row, mapper.findContacts(custId));
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    // ------------------------------------------------------------------ 저장

    @Transactional
    public long create(CustForm form) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_CUST_CREATE");
        CustForm f = validate(form, null);
        try {
            return mapper.insert(f, me.loginId());
        } catch (DuplicateKeyException e) {
            // 동시에 같은 코드로 저장 — 먼저 들어간 쪽이 이긴다
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("custCd", "DUPLICATE").list());
        }
    }

    @Transactional
    public void update(long custId, CustForm form) {
        LoginUser me = me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        CustForm f = validate(form, custId);
        int n;
        try {
            n = mapper.update(custId, f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("custCd", "DUPLICATE").list());
        }
        if (n == 0) {
            throw notFoundOrConflict(custId);
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

    /** 담당자 일괄 저장 — 순서 deleted → updated → created, 전부 아니면 전무 */
    @Transactional
    public BatchResult saveContacts(long custId, int custRowVer, ContactBatch batch) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_CUST_CONTACT");
        List<ContactItem> created = batch == null || batch.created() == null ? List.of() : batch.created();
        List<ContactItem> updated = batch == null || batch.updated() == null ? List.of() : batch.updated();
        List<ContactItem> deleted = batch == null || batch.deleted() == null ? List.of() : batch.deleted();
        int max = policies.intValue(Policies.GRID_BATCH_MAX_ROWS);
        if (created.size() + updated.size() + deleted.size() > max
                || created.stream().anyMatch(c -> c == null) || updated.stream().anyMatch(c -> c == null)
                || deleted.stream().anyMatch(c -> c == null)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (!deleted.isEmpty() && !permissions.actions(PROG).contains(Action.DELETE)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        // 거래처 자체를 잠근다(버전 확인 + 증가) — 두 사람이 같은 거래처 담당자를 동시에 고치면 뒤쪽이 409
        if (mapper.touch(custId, custRowVer, me.loginId()) == 0) {
            throw notFoundOrConflict(custId);
        }

        FieldErrors errors = FieldErrors.create();
        List<ContactItem> cleanCreated = new ArrayList<>();
        List<ContactItem> cleanUpdated = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (ContactItem c : created) {
            cleanCreated.add(validateContact(c, false, errors, keys));
        }
        for (ContactItem c : updated) {
            cleanUpdated.add(validateContact(c, true, errors, keys));
        }
        for (ContactItem c : deleted) {
            if (c.contactId() == null || c.rowVer() == null) {
                errors.row(c.rowKey()).add("contactId", "REQUIRED");
            }
        }
        // 주 담당자는 저장 후 기준 한 명 — 남는 기존 주 담당자 + 이번에 Y 로 오는 것
        Set<Long> touched = new HashSet<>();
        updated.forEach(c -> touched.add(c.contactId()));
        deleted.forEach(c -> touched.add(c.contactId()));
        long existingMain = mapper.findContacts(custId).stream()
                .filter(r -> "Y".equals(r.getMainYn()) && !touched.contains(r.getContactId())).count();
        long newMain = cleanCreated.stream().filter(c -> "Y".equals(c.mainYn())).count()
                + cleanUpdated.stream().filter(c -> "Y".equals(c.mainYn())).count();
        if (existingMain + newMain > 1) {
            cleanCreated.stream().filter(c -> "Y".equals(c.mainYn()))
                    .forEach(c -> errors.row(c.rowKey()).add("mainYn", "ONE_MAIN"));
            cleanUpdated.stream().filter(c -> "Y".equals(c.mainYn()))
                    .forEach(c -> errors.row(c.rowKey()).add("mainYn", "ONE_MAIN"));
        }
        errors.throwIfAny();

        for (ContactItem c : deleted) {
            if (mapper.disableContact(custId, c.contactId(), c.rowVer(), me.loginId()) == 0) {
                throw rowConflict(c.rowKey());
            }
        }
        for (ContactItem c : cleanUpdated) {
            if (mapper.updateContact(custId, c, me.loginId()) == 0) {
                throw rowConflict(c.rowKey());
            }
        }
        List<CreatedKey> keysOut = new ArrayList<>();
        for (ContactItem c : cleanCreated) {
            keysOut.add(new CreatedKey(c.rowKey(), mapper.insertContact(custId, c, me.loginId())));
        }
        return new BatchResult(keysOut, cleanUpdated.size(), deleted.size());
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(ExportRequest req, HttpServletResponse response) throws IOException {
        ExportRequest r = req == null ? new ExportRequest(null, null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, r.columns());
        CustSearch q = search(r.custCd(), r.custNm(), r.custType(), r.useYn(),
                PageQuery.sortOnly(r.sort(), CustSort.values(), DEFAULT_SORT));
        List<String> keys = new ArrayList<>();
        if (r.custCd() != null && !r.custCd().isBlank()) {
            keys.add("custCd");
        }
        if (r.custNm() != null && !r.custNm().isBlank()) {
            keys.add("custNm");
        }
        if (q.getCustType() != null) {
            keys.add("custType");
        }
        if (q.getUseYn() != null) {
            keys.add("useYn");
        }
        return excel.export(response, new ExportJob(PROG, "prog.mstCust", cols, mapper.count(q), keys), w -> {
            try (Cursor<CustRow> c = mapper.cursor(q)) {
                c.forEach(w);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    // ------------------------------------------------------------------ 검증

    private CustSearch search(String custCd, String custNm, String custType, String useYn, PageQuery pq) {
        String cd = blank(custCd) ? null : custCd.strip().toUpperCase(Locale.ROOT);
        String nm = blank(custNm) ? null : custNm.strip();
        if ((cd != null && cd.length() > 30) || (nm != null && nm.length() > 200)
                || (!blank(custType) && !TYPES.contains(custType))
                || (!blank(useYn) && !useYn.equals("Y") && !useYn.equals("N"))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new CustSearch(cd == null ? null : likeEscape(cd) + "%", nm == null ? null : "%" + likeEscape(nm) + "%",
                blank(custType) ? null : custType, blank(useYn) ? null : useYn, pq);
    }

    /** 폼 검증 — 오류를 모두 모은 뒤 400. 통과하면 정리된 값(대문자 코드·숫자만 사업자번호 등) */
    CustForm validate(CustForm in, Long exceptId) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        CustForm f = validate(in, cd -> mapper.existsCd(cd, exceptId), e);
        e.throwIfAny();
        return f;
    }

    /** 검증 본체 — 코드 중복 확인은 밖에서 넣는다(엑셀 올리기는 미리 읽은 목록으로). 오류는 e 에 모은다 */
    CustForm validate(CustForm in, Predicate<String> codeTaken, FieldErrors e) {
        String cd = blank(in.custCd()) ? null : in.custCd().strip().toUpperCase(Locale.ROOT);
        if (cd == null) {
            e.add("custCd", "REQUIRED");
        } else if (cd.length() > 30) {
            e.add("custCd", "TOO_LONG", 30);
        } else if (!CUST_CD.matcher(cd).matches()) {
            e.add("custCd", "FORMAT");
        } else if (codeTaken.test(cd)) {
            e.add("custCd", "DUPLICATE");
        }
        String nm = text(in.custNm(), "custNm", 200, true, e);
        if (blank(in.custType())) {
            e.add("custType", "REQUIRED");
        } else if (!TYPES.contains(in.custType())) {
            e.add("custType", "CODE");
        }
        String biz = null;
        if (!blank(in.bizNo())) {
            biz = in.bizNo().replaceAll("[\\s-]", "");
            if (!biz.matches("^[0-9]{10}$")) {
                e.add("bizNo", "FORMAT");
            } else if (!bizNoChecksum(biz)) {
                e.add("bizNo", "CHECKSUM");
            }
        }
        String ceo = text(in.ceoNm(), "ceoNm", 100, false, e);
        String tel = text(in.tel(), "tel", 30, false, e);
        if (tel != null && !TEL.matcher(tel).matches()) {
            e.add("tel", "FORMAT");
        }
        String email = text(in.email(), "email", 200, false, e);
        if (email != null && !EMAIL.matcher(email).matches()) {
            e.add("email", "FORMAT");
        }
        String addr = text(in.addr(), "addr", 500, false, e);
        String remark = text(in.remark(), "remark", 1000, false, e);
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!use.equals("Y") && !use.equals("N")) {
            e.add("useYn", "CODE");
        }
        return new CustForm(cd, nm, in.custType(), biz, ceo, tel, email, addr, remark, use, in.rowVer());
    }

    private ContactItem validateContact(ContactItem c, boolean update, FieldErrors errors, Set<String> keys) {
        FieldErrors.RowScope row = errors.row(c.rowKey());
        if (c.rowKey() == null || !c.rowKey().matches("^[A-Za-z0-9_-]{1,40}$") || !keys.add(c.rowKey())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (update && (c.contactId() == null || c.rowVer() == null)) {
            row.add("contactId", "REQUIRED");
        }
        FieldErrors local = FieldErrors.create();
        String nm = text(c.contactNm(), "contactNm", 100, true, local);
        String dept = text(c.deptNm(), "deptNm", 100, false, local);
        String tel = text(c.tel(), "tel", 30, false, local);
        if (tel != null && !TEL.matcher(tel).matches()) {
            local.add("tel", "FORMAT");
        }
        String email = text(c.email(), "email", 200, false, local);
        if (email != null && !EMAIL.matcher(email).matches()) {
            local.add("email", "FORMAT");
        }
        String main = blank(c.mainYn()) ? "N" : c.mainYn();
        if (!main.equals("Y") && !main.equals("N")) {
            local.add("mainYn", "CODE");
        }
        local.list().forEach(fe -> row.add(fe.field(), fe.code(), fe.args()));
        return new ContactItem(c.rowKey(), c.contactId(), c.rowVer(), nm, dept, tel, email, main);
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

    /** 국세청 사업자등록번호 검증식 (가중치 1,3,7,1,3,7,1,3,5) */
    static boolean bizNoChecksum(String d) {
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (d.charAt(i) - '0') * w[i];
        }
        sum += ((d.charAt(8) - '0') * 5) / 10;
        return (10 - sum % 10) % 10 == d.charAt(9) - '0';
    }

    private BusinessException notFoundOrConflict(long custId) {
        return mapper.findById(custId) == null ? new BusinessException(HbErrorCode.NOT_FOUND)
                : new BusinessException(HbErrorCode.CONFLICT);
    }

    private static BusinessException rowConflict(String rowKey) {
        return new BusinessException(HbErrorCode.CONFLICT)
                .withFieldErrors(List.of(new FieldErrors.FieldError(rowKey, null, "CONFLICT")));
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
