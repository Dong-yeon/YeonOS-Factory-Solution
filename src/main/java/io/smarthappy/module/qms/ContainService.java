package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.production.link.LotHoldPort;
import io.smarthappy.module.qms.ContainDtos.Candidates;
import io.smarthappy.module.qms.ContainDtos.ContainDetail;
import io.smarthappy.module.qms.ContainDtos.ContainLotRow;
import io.smarthappy.module.qms.ContainDtos.ContainPage;
import io.smarthappy.module.qms.ContainDtos.ContainRow;
import io.smarthappy.module.qms.ContainDtos.ContainSort;
import io.smarthappy.module.qms.ContainDtos.CreateForm;
import io.smarthappy.module.qms.ContainDtos.ItemRef;
import io.smarthappy.module.qms.ContainDtos.LotRow;
import io.smarthappy.module.qms.ContainDtos.NcrRef;
import io.smarthappy.module.qms.ContainDtos.ReleaseForm;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.time.BizClock;

/**
 * 봉쇄 (qms-contain) — P3 QMS 2부 Q8b, docs/29_P3 12.8.
 *
 * - 후보 = 품목 × LOT 발생 기간(회사 시간대), 각 LOT 의 살아 있는 보류 근거를 같이 — 사람이 고른다(자동 일괄 보류 아님)
 * - 봉쇄 = 고른 LOT 을 **lot_id 오름차순**으로 MES LotHoldPort hold(출처 QMS_CONTAIN, 참조 = contain_id).
 *   contain_id 를 시퀀스에서 먼저 받고 → 포트(LOT FOR UPDATE) → INSERT(잠금 순서 LOT → qms_contain)
 * - 해제 = 고른 살아 있는 줄을 오름차순으로 포트 release → 줄 기록, 모두 풀리면 CLOSED
 */
@Service
public class ContainService {

    static final String PROG = "qms-contain";
    static final String RULE = "QMS_CONTAIN";
    static final int LIST_LIMIT = 300;
    static final int MAX_LOTS = LotHoldPort.MAX_IDS;
    static final int MAX_DAYS = 366;
    static final List<String> DEFAULT_SORT = List.of("createdAt,desc");

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("containNo", "qms.contain.col.no", 120).withSort().withPriority(1),
            ColumnSpec.code("status", "qms.contain.col.status", "QMS_CONTAIN_STATUS", 90).withSort().withPriority(1),
            ColumnSpec.text("itemCd", "qms.contain.col.item", 120).withSort().withPriority(1),
            ColumnSpec.text("itemNm", "qms.contain.col.itemNm", 160).withFlex().withMinWidth(100).withPriority(2),
            ColumnSpec.of("fromDate", "qms.contain.col.from", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(2),
            ColumnSpec.of("toDate", "qms.contain.col.to", ColumnSpec.ColumnType.DATE, 100).withPriority(2),
            ColumnSpec.of("heldCnt", "qms.contain.col.held", ColumnSpec.ColumnType.INT, 70).withPriority(1),
            ColumnSpec.of("lotCnt", "qms.contain.col.lotCnt", ColumnSpec.ColumnType.INT, 70).withPriority(3),
            ColumnSpec.text("ncrNo", "qms.contain.col.ncr", 120).withPriority(3),
            ColumnSpec.of("createdAt", "qms.contain.col.created", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));

    private final ContainMapper mapper;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final BizClock clock;
    private final ObjectProvider<LotHoldPort> holdPort;
    private final PolicyService policies;

    public ContainService(ContainMapper mapper, NumberingService numbering, InboxService inbox, BizClock clock,
            ObjectProvider<LotHoldPort> holdPort, PolicyService policies) {
        this.mapper = mapper;
        this.numbering = numbering;
        this.inbox = inbox;
        this.clock = clock;
        this.holdPort = holdPort;
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public List<ContainRow> list(String keyword, String status) {
        String kw = VisibleText.isBlank(keyword) ? null : "%" + BomService.likeEscape(keyword.strip()) + "%";
        return mapper.findList(kw, VisibleText.isBlank(status) ? null : status, LIST_LIMIT);
    }

    /** 그리드(UI 단계 3, 결정 #45) — 목록과 같은 조건 + 쪽·정렬, 기본 = 등록 최근 */
    @Transactional(readOnly = true)
    public ContainPage grid(String keyword, String status, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, ContainSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                DEFAULT_SORT);
        String kw = VisibleText.isBlank(keyword) ? null : "%" + BomService.likeEscape(keyword.strip()) + "%";
        String st = VisibleText.isBlank(status) ? null : status;
        return new ContainPage(mapper.findPage(kw, st, pq), pq.isWithTotal() ? mapper.countList(kw, st) : null);
    }

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public ContainDetail detail(long id) {
        ContainRow c = find(id);
        List<ContainLotRow> lots = mapper.findLots(id);
        return new ContainDetail(c, lots, LotHoldPort.of(holdPort).holds(lots.stream().map(ContainLotRow::getLotId).toList()));
    }

    /** 후보 LOT — 품목 × 기간, 상한 500(넘으면 truncated) */
    @Transactional(readOnly = true)
    public Candidates candidates(String itemCd, LocalDate from, LocalDate to) {
        FieldErrors e = FieldErrors.create();
        ItemRef item = item(itemCd, e);
        dates(from, to, e);
        e.throwIfAny();
        List<LotRow> lots = mapper.findCandidates(item.getItemId(), start(from), start(to.plusDays(1)), MAX_LOTS + 1);
        boolean truncated = lots.size() > MAX_LOTS;
        List<LotRow> out = truncated ? lots.subList(0, MAX_LOTS) : lots;
        return new Candidates(item, out, out.isEmpty() ? List.of() : LotHoldPort.of(holdPort).holds(out.stream().map(LotRow::getLotId).toList()),
                truncated);
    }

    /** 봉쇄 — 고른 LOT 을 lot_id 오름차순으로 포트 hold 뒤 INSERT */
    @Transactional
    public long create(CreateForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_CONTAIN_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ItemRef item = item(in.itemCd(), e);
        dates(in.fromDate(), in.toDate(), e);
        Long ncrId = null;
        if (!VisibleText.isBlank(in.ncrNo())) {
            NcrRef n = mapper.findNcrByNo(in.ncrNo().strip());
            if (n == null) {
                e.add("ncrNo", "NOT_FOUND");
            } else if (item != null && n.getItemId() != item.getItemId()) {
                e.add("ncrNo", "CONTAIN_NCR_ITEM");
            } else {
                ncrId = n.getNcrId();
            }
        }
        String reason = VisibleText.reason(in.reason(), "reason", 1, 500, e);
        TreeSet<Long> ids = new TreeSet<>();
        if (in.lotIds() == null || in.lotIds().isEmpty()) {
            e.add("lotIds", "REQUIRED");
        } else if (in.lotIds().size() > MAX_LOTS) {
            e.add("lotIds", "TOO_MANY", MAX_LOTS);
        } else {
            in.lotIds().stream().filter(Objects::nonNull).forEach(ids::add);
            if (ids.size() != in.lotIds().size()) {
                e.add("lotIds", "INVALID");
            }
        }
        e.throwIfAny();
        List<LotRow> lots = mapper.findLotsByIds(ids);
        if (lots.size() != ids.size() || lots.stream().anyMatch(l -> l.getItemId() != item.getItemId())) {
            throw SamplingService.fieldError("lotIds", "CONTAIN_LOT_ITEM");
        }
        long id = mapper.nextId();
        String no = numbering.nextOrDefault(RULE, "봉쇄 번호", "CT{yyMM}-{seq:4}", "MONTH");
        String why = NcrService.cut("봉쇄 " + no + ": " + reason);
        LotHoldPort port = LotHoldPort.of(holdPort);
        try {
            for (long lotId : ids) {
                port.hold(lotId, LotHoldPort.QMS_CONTAIN, id, why, me.loginId());
            }
        } catch (IllegalStateException ex) {
            throw new BusinessException(HbErrorCode.QMS_CONTAIN_HOLD_UNAVAILABLE);
        }
        mapper.insert(id, no, item.getItemId(), ncrId, in.fromDate(), in.toDate(), reason, ids.size(), me.loginId());
        for (long lotId : ids) {
            mapper.insertLot(id, lotId, me.loginId());
        }
        return id;
    }

    /** 해제 — 고른 살아 있는 줄을 오름차순으로 포트 release → 줄 기록, 모두 풀리면 CLOSED */
    @Transactional
    public void release(long id, ReleaseForm in) {
        LoginUser me = SamplingService.me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String reason = VisibleText.reason(in.reason(), "reason", 1, 500, e);
        if (in.lotIds() == null || in.lotIds().isEmpty()) {
            e.add("lotIds", "REQUIRED");
        }
        e.throwIfAny();
        ContainRow c = find(id);
        if (!"OPEN".equals(c.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_CONTAIN_STATE);
        }
        Map<Long, ContainLotRow> held = mapper.findLots(id).stream().filter(l -> l.getReleasedAt() == null)
                .collect(Collectors.toMap(ContainLotRow::getLotId, Function.identity()));
        TreeSet<Long> ids = new TreeSet<>();
        for (Long lotId : in.lotIds()) {
            if (lotId == null || !held.containsKey(lotId)) {
                throw SamplingService.fieldError("lotIds", "CONTAIN_NOT_HELD");
            }
            ids.add(lotId);
        }
        String why = NcrService.cut("봉쇄 해제 " + c.getContainNo() + ": " + reason);
        LotHoldPort port = LotHoldPort.of(holdPort);
        for (long lotId : ids) {
            try {
                port.release(lotId, LotHoldPort.QMS_CONTAIN, id, why, me.loginId());
            } catch (IllegalStateException ex) {
                throw new BusinessException(HbErrorCode.QMS_CONTAIN_HOLD_UNAVAILABLE);
            }
            if (mapper.releaseLot(id, lotId, reason, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        }
        // 같은 봉쇄의 다른 LOT 을 동시에 푸는 요청이 서로 상대의 미커밋 해제를 못 보고 둘 다 "남음" 으로 셀 수 있다 —
        // 머리를 잠그고(LOT 뒤라 순서 유지) 그 뒤 문장으로 세면 둘째는 첫째 커밋 뒤 새 스냅샷으로 센다(READ COMMITTED)
        mapper.lockContain(id);
        if (mapper.heldCount(id) == 0 && mapper.close(id, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 내부

    private ContainRow find(long id) {
        ContainRow c = mapper.findById(id);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return c;
    }

    private ItemRef item(String itemCd, FieldErrors e) {
        if (VisibleText.isBlank(itemCd)) {
            e.add("itemCd", "REQUIRED");
            return null;
        }
        ItemRef item = mapper.findItem(itemCd.strip());
        if (item == null) {
            e.add("itemCd", "NOT_FOUND");
        }
        return item;
    }

    private static void dates(LocalDate from, LocalDate to, FieldErrors e) {
        if (from == null) {
            e.add("fromDate", "REQUIRED");
        }
        if (to == null) {
            e.add("toDate", "REQUIRED");
        }
        if (from != null && to != null && (from.isAfter(to) || from.plusDays(MAX_DAYS).isBefore(to))) {
            e.add("toDate", "CONTAIN_RANGE", MAX_DAYS);
        }
    }

    private OffsetDateTime start(LocalDate d) {
        return d.atStartOfDay(clock.zone()).toOffsetDateTime();
    }
}
