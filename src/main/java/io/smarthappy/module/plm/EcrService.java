package io.smarthappy.module.plm;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.PlmDtos.EcrActForm;
import io.smarthappy.module.plm.PlmDtos.EcrAcceptForm;
import io.smarthappy.module.plm.PlmDtos.EcrForm;
import io.smarthappy.module.plm.PlmDtos.EcrList;
import io.smarthappy.module.plm.PlmDtos.EcrRow;
import io.smarthappy.module.plm.PlmDtos.EcoRow;
import io.smarthappy.module.plm.PlmDtos.ItemRef;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 설계 변경 요청 ECR (plm-ecr) — docs/39 3·4장.
 *
 * - 누구나(SAVE) 올리고, 요청자만 고치거나 취소한다(열린 동안)
 * - 접수·반려는 검토 담당(APPROVE): 접수 = 새 작성 중 ECO(요청 내용 복사 + 주 품목을 ITEM 대상으로) 또는 기존 작성 중 ECO 에 묶기
 * - 닫힌 요청은 불변(트리거 ck_plm_ecr_transition)
 */
@Service
@RequiredArgsConstructor
public class EcrService {

    static final String PROG = "plm-ecr";
    static final String RULE = "PLM_ECR";
    static final int LIST_LIMIT = 500;
    static final Set<String> STATUSES = Set.of("OPEN", "ACCEPTED", "REJECTED", "CANCELLED");

    private final PlmMapper mapper;
    private final EcoService ecos;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final BizClock clock;
    private final io.smarthappy.platform.policy.PolicyService policies;
    private final io.smarthappy.platform.excel.ExcelExportService excel;

    /**
     * grid(UI 단계 3, docs/39 21.2) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link io.smarthappy.module.plm.PlmDtos.EcrSort}).
     * 없는 품목 코드면 빈 쪽
     */
    @Transactional(readOnly = true)
    public io.smarthappy.module.plm.PlmDtos.EcrPage grid(String status, String keyword, String itemCd, Integer page, Integer size,
            List<String> sort, Boolean total) {
        io.smarthappy.platform.common.query.PageQuery pq = io.smarthappy.platform.common.query.PageQuery.parse(page, size, sort, total,
                io.smarthappy.module.plm.PlmDtos.EcrSort.values(), policies.intValue(io.smarthappy.platform.policy.Policies.GRID_PAGE_SIZE_MAX),
                List.of("ecrNo,desc"));
        FieldErrors e = FieldErrors.create();
        if (!EcoService.blank(status) && !STATUSES.contains(status)) {
            e.add("status", "CODE");
        }
        if (!EcoService.blank(keyword) && keyword.strip().length() > 50) {
            e.add("keyword", "TOO_LONG", 50);
        }
        e.throwIfAny();
        Long itemId = null;
        if (!EcoService.blank(itemCd)) {
            ItemRef i = mapper.findItem(itemCd.strip().toUpperCase(Locale.ROOT));
            if (i == null) {
                return new io.smarthappy.module.plm.PlmDtos.EcrPage(List.of(), pq.isWithTotal() ? 0L : null);
            }
            itemId = i.getItemId();
        }
        String st = EcoService.blank(status) ? null : status;
        String kw = EcoService.blank(keyword) ? null : "%" + EcoService.like(keyword.strip().toUpperCase(Locale.ROOT)) + "%";
        return new io.smarthappy.module.plm.PlmDtos.EcrPage(mapper.findEcrPage(st, kw, itemId, null, null, pq),
                pq.isWithTotal() ? mapper.countEcrs(st, kw, itemId, null, null) : null);
    }

    /** 엑셀(docs/39 25장) — grid 와 같은 조건·정렬·보이는 열. 없는 품목 코드면 빈 파일 */
    @Transactional(readOnly = true)
    public long export(io.smarthappy.module.plm.PlmDtos.EcrExport req, jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        io.smarthappy.module.plm.PlmDtos.EcrExport r = req == null ? new io.smarthappy.module.plm.PlmDtos.EcrExport(null, null, null, null, null) : req;
        List<io.smarthappy.platform.grid.ColumnSpec> cols = io.smarthappy.platform.grid.ColumnSpec.selectExportable(GRID_COLUMNS, r.columns());
        io.smarthappy.platform.common.query.PageQuery pq = io.smarthappy.platform.common.query.PageQuery.sortOnly(r.sort(),
                io.smarthappy.module.plm.PlmDtos.EcrSort.values(), List.of("ecrNo,desc"));
        FieldErrors e = FieldErrors.create();
        if (!EcoService.blank(r.status()) && !STATUSES.contains(r.status())) {
            e.add("status", "CODE");
        }
        if (!EcoService.blank(r.keyword()) && r.keyword().strip().length() > 50) {
            e.add("keyword", "TOO_LONG", 50);
        }
        e.throwIfAny();
        String st = EcoService.blank(r.status()) ? null : r.status();
        String kw = EcoService.blank(r.keyword()) ? null : "%" + EcoService.like(r.keyword().strip().toUpperCase(Locale.ROOT)) + "%";
        List<String> keys = new java.util.ArrayList<>();
        if (st != null) {
            keys.add("status");
        }
        if (kw != null) {
            keys.add("keyword");
        }
        Long itemId = null;
        boolean none = false;
        if (!EcoService.blank(r.itemCd())) {
            keys.add("itemCd");
            ItemRef i = mapper.findItem(r.itemCd().strip().toUpperCase(Locale.ROOT));
            none = i == null;
            itemId = none ? null : i.getItemId();
        }
        Long item = itemId;
        long count = none ? 0 : mapper.countEcrs(st, kw, item, null, null);
        return excel.export(response, new io.smarthappy.platform.excel.ExcelExportService.ExportJob(PROG, "prog.plmEcr", cols, count, keys), w -> {
            if (count == 0) {
                return;
            }
            try (org.apache.ibatis.cursor.Cursor<io.smarthappy.module.plm.PlmDtos.EcrRow> c = mapper.cursorEcrs(st, kw, item, null, null, pq)) {
                c.forEach(w);
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        });
    }

    /** grid 열(화면 목록과 같은 칸) */
    static final List<io.smarthappy.platform.grid.ColumnSpec> GRID_COLUMNS = List.of(
            io.smarthappy.platform.grid.ColumnSpec.text("ecrNo", "plm.col.ecrNo", 110).withSort(),
            io.smarthappy.platform.grid.ColumnSpec.text("title", "plm.col.title", 160).withSort(),
            io.smarthappy.platform.grid.ColumnSpec.text("itemCd", "plm.col.itemCd", 100).withSort(),
            io.smarthappy.platform.grid.ColumnSpec.text("requesterNm", "plm.col.requester", 80),
            io.smarthappy.platform.grid.ColumnSpec.code("status", "plm.col.status", "PLM_ECR_STATUS", 80).withSort(),
            io.smarthappy.platform.grid.ColumnSpec.text("ecoNo", "plm.col.ecoNo", 110).withSort());

    public List<io.smarthappy.platform.grid.ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public EcrList list(String status, String keyword, String itemCd) {
        FieldErrors e = FieldErrors.create();
        if (!EcoService.blank(status) && !STATUSES.contains(status)) {
            e.add("status", "CODE");
        }
        if (!EcoService.blank(keyword) && keyword.strip().length() > 50) {
            e.add("keyword", "TOO_LONG", 50);
        }
        e.throwIfAny();
        Long itemId = null;
        if (!EcoService.blank(itemCd)) {
            ItemRef i = mapper.findItem(itemCd.strip().toUpperCase(Locale.ROOT));
            if (i == null) {
                return new EcrList(List.of(), false);
            }
            itemId = i.getItemId();
        }
        List<EcrRow> rows = mapper.findEcrs(EcoService.blank(status) ? null : status,
                EcoService.blank(keyword) ? null : "%" + EcoService.like(keyword.strip().toUpperCase(Locale.ROOT)) + "%",
                itemId, null, null, LIST_LIMIT + 1);
        boolean more = rows.size() > LIST_LIMIT;
        return new EcrList(more ? rows.subList(0, LIST_LIMIT) : rows, more);
    }

    @Transactional(readOnly = true)
    public EcrRow detail(long ecrId) {
        List<EcrRow> rows = mapper.findEcrs(null, null, null, ecrId, null, 1);
        if (rows.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        EcrRow r = rows.get(0);
        r.setMine(r.getRequesterId() == EcoService.me().userId());
        return r;
    }

    @Transactional
    public long create(EcrForm in) {
        LoginUser me = EcoService.me();
        inbox.claimRequestKey("PLM_ECR_CREATE");
        Value v = validate(in);
        String no = numbering.nextOrDefault(RULE, "설계 변경 요청 번호", "ECR{yyMM}-{seq:4}", "MONTH");
        return mapper.insertEcr(no, v.title, v.reasonCd, v.description, v.itemId, me.userId(), me.loginId());
    }

    /** 수정 — 열린 요청, 요청자만 */
    @Transactional
    public void update(long ecrId, EcrForm in) {
        LoginUser me = EcoService.me();
        int ver = EcoService.rowVer(in == null ? null : in.rowVer());
        EcrRow cur = lockOpen(ecrId);
        if (cur.getRequesterId() != me.userId()) {
            throw new BusinessException(HbErrorCode.PLM_NOT_REQUESTER);
        }
        Value v = validate(in);
        if (mapper.updateEcr(ecrId, ver, v.title, v.reasonCd, v.description, v.itemId, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 취소 — 요청자만 */
    @Transactional
    public void cancel(long ecrId, EcrActForm in) {
        LoginUser me = EcoService.me();
        int ver = EcoService.rowVer(in == null ? null : in.rowVer());
        EcrRow cur = lockOpen(ecrId);
        if (cur.getRequesterId() != me.userId()) {
            throw new BusinessException(HbErrorCode.PLM_NOT_REQUESTER);
        }
        FieldErrors e = FieldErrors.create();
        String comment = OrderService.text(in.comment(), "comment", 1000, e);
        e.throwIfAny();
        close(ecrId, ver, "CANCELLED", null, comment, me);
    }

    /** 반려 — 사유 필수 */
    @Transactional
    public void reject(long ecrId, EcrActForm in) {
        LoginUser me = EcoService.me();
        int ver = EcoService.rowVer(in == null ? null : in.rowVer());
        lockOpen(ecrId);
        FieldErrors e = FieldErrors.create();
        String comment = OrderService.text(in.comment(), "comment", 1000, e);
        if (comment == null) {
            e.add("comment", "REQUIRED");
        }
        e.throwIfAny();
        close(ecrId, ver, "REJECTED", null, comment, me);
    }

    /** 접수 — 새 ECO 또는 기존 작성 중 ECO 에 묶는다. 돌려주는 값 = ECO id */
    @Transactional
    public long accept(long ecrId, EcrAcceptForm in) {
        LoginUser me = EcoService.me();
        inbox.claimRequestKey("PLM_ECR_ACCEPT");
        int ver = EcoService.rowVer(in == null ? null : in.rowVer());
        EcrRow cur = lockOpen(ecrId);
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        String comment = OrderService.text(in.comment(), "comment", 1000, e);
        LocalDate eff = OrderService.date(in.effectiveDate(), "effectiveDate", false, e);
        if (eff != null && eff.isBefore(clock.today())) {
            e.add("effectiveDate", "PAST");
        }
        e.throwIfAny();
        long ecoId;
        if (in.ecoId() != null) {
            EcoRow eco = mapper.lockEco(in.ecoId());
            if (eco == null) {
                throw OrderService.fieldError("ecoId", "NOT_FOUND");
            }
            if (!"DRAFT".equals(eco.getStatus())) {
                throw new BusinessException(HbErrorCode.PLM_ECO_STATE);
            }
            ecoId = eco.getEcoId();
            if (cur.getItemId() != null) {
                ecos.addItemTarget(ecoId, cur.getItemId(), me);
            }
        } else {
            ecoId = ecos.createFromEcr(cur, eff != null ? eff : clock.today(), me);
        }
        close(ecrId, ver, "ACCEPTED", ecoId, comment, me);
        return ecoId;
    }

    // ------------------------------------------------------------------

    private record Value(String title, String reasonCd, String description, Long itemId) {
    }

    private Value validate(EcrForm in) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String title = OrderService.text(in.title(), "title", 200, e);
        if (title == null) {
            e.add("title", "REQUIRED");
        }
        String reason = EcoService.reason(in.reasonCd(), mapper, e);
        String desc = OrderService.text(in.description(), "description", 4000, e);
        Long itemId = null;
        if (!EcoService.blank(in.itemCd())) {
            ItemRef i = mapper.findItem(in.itemCd().strip().toUpperCase(Locale.ROOT));
            if (i == null) {
                e.add("itemCd", "NOT_FOUND");
            } else if (!"Y".equals(i.getUseYn())) {
                e.add("itemCd", "INACTIVE");
            } else {
                itemId = i.getItemId();
            }
        }
        e.throwIfAny();
        return new Value(title, reason, desc, itemId);
    }

    private EcrRow lockOpen(long ecrId) {
        EcrRow cur = mapper.lockEcr(ecrId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"OPEN".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_ECR_CLOSED);
        }
        return cur;
    }

    private void close(long ecrId, int ver, String status, Long ecoId, String comment, LoginUser me) {
        if (mapper.closeEcr(ecrId, ver, status, ecoId, comment, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }
}
