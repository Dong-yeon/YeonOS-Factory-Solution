package io.smarthappy.module.qms.preship;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.preship.PreshipDtos.AllowForm;
import io.smarthappy.module.qms.preship.PreshipDtos.CancelForm;
import io.smarthappy.module.qms.preship.PreshipDtos.CloseForm;
import io.smarthappy.module.qms.preship.PreshipDtos.HeadForm;
import io.smarthappy.module.qms.preship.PreshipDtos.InspHead;
import io.smarthappy.module.qms.preship.PreshipDtos.JudgeForm;
import io.smarthappy.module.qms.preship.PreshipDtos.JudgeResult;
import io.smarthappy.module.qms.preship.PreshipDtos.NcrForm;
import io.smarthappy.module.qms.preship.PreshipDtos.PunchPage;
import io.smarthappy.module.qms.preship.PreshipDtos.PunchRow;
import io.smarthappy.module.qms.preship.PreshipDtos.PunchSort;
import io.smarthappy.module.qms.preship.PreshipDtos.RequiredItem;
import io.smarthappy.module.qms.preship.PreshipDtos.ResultRow;
import io.smarthappy.module.qms.preship.PreshipDtos.ShipDetail;
import io.smarthappy.module.qms.preship.PreshipDtos.ShipPage;
import io.smarthappy.module.qms.preship.PreshipDtos.ShipRow;
import io.smarthappy.module.qms.preship.PreshipDtos.ShipSort;
import io.smarthappy.module.qms.preship.PreshipDtos.StartForm;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 출하 전 검수 실행·미결 (qms-preship · qms-preship-punch, docs/78 4장).
 *
 * <ul>
 * <li>시작: 출하를 FOR SHARE 로 잡고(출하 확정과 줄 섬) 양식이 걸린 품목마다 항목을 판정 줄로 복사. 출하당 살아 있는 검수 하나(부분 유일)</li>
 * <li>판정: 머리 FOR SHARE(완료·취소와 줄 섬) → 줄 rowVer(동시 판정 409, 덮어쓰기 없음). 사진 필수 OK 는 첨부 1개 이상, NA 는 허용 항목만,
 *     NG 는 비고 필수 + 미결 1건. 열린 미결이 있는 줄은 판정을 바꾸지 않고 미결 닫기로만</li>
 * <li>완료(APPROVE): 머리 FOR UPDATE → 모두 판정 + 검수자 이름 + 허용 안 된 열린 미결 없음. 완료 취소는 출하 확정 전까지</li>
 * <li>미결 닫기(SAVE) = 조치 + 재확인 OK(사진 필수 항목은 사진), 검수 DONE 이어도 된다. 조건부 출하 허용(APPROVE) = 사유, 검수 OPEN 동안만</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PreshipService {

    static final String PROG = "qms-preship";
    static final String PROG_PUNCH = "qms-preship-punch";
    static final int MAX_DAYS = 186;
    static final String RULE = "QMS_PRESHIP";
    static final Set<String> JUDGES = Set.of("OK", "NG", "NA");
    static final Set<String> INSP_FILTER = Set.of("NONE", "OPEN", "DONE");
    static final List<String> SHIP_SORT = List.of("planDate,desc");
    static final List<String> PUNCH_SORT = List.of("createdAt,desc");

    static final List<ColumnSpec> SHIP_COLUMNS = List.of(
            ColumnSpec.text("shipNo", "qms.preship.col.shipNo", 140).withSort().withPriority(1),
            ColumnSpec.of("planDate", "qms.preship.col.planDate", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(1),
            ColumnSpec.text("custNm", "qms.preship.col.custNm", 160).withSort().withPriority(2).withFlex().withMinWidth(120),
            ColumnSpec.code("shipStatus", "qms.preship.col.shipStatus", "WMS_SHIP_STATUS", 90).withPriority(2),
            ColumnSpec.labels("requiredYn", "qms.preship.col.requiredYn", "qms.preship.required.", 80, "Y", "N").withPriority(1),
            ColumnSpec.text("inspNo", "qms.preship.col.inspNo", 140).withPriority(2),
            ColumnSpec.labels("inspStatus", "qms.preship.col.inspStatus", "qms.preship.inspStatus.", 90, "NONE", "OPEN", "DONE").withSort().withPriority(1),
            ColumnSpec.of("judgedCnt", "qms.preship.col.judgedCnt", ColumnSpec.ColumnType.INT, 70).withPriority(3),
            ColumnSpec.of("resultCnt", "qms.preship.col.resultCnt", ColumnSpec.ColumnType.INT, 70).withPriority(3),
            ColumnSpec.of("openPunchCnt", "qms.preship.col.openPunchCnt", ColumnSpec.ColumnType.INT, 70).withPriority(2));

    static final List<ColumnSpec> PUNCH_COLUMNS = List.of(
            ColumnSpec.code("status", "qms.preship.col.punchStatus", "QMS_PUNCH_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.text("shipNo", "qms.preship.col.shipNo", 140).withSort().withPriority(1),
            ColumnSpec.text("inspNo", "qms.preship.col.inspNo", 140).withPriority(3),
            ColumnSpec.text("itemCd", "qms.preship.col.itemCd", 120).withSort().withPriority(1),
            ColumnSpec.text("checkText", "qms.preship.col.checkText", 220).withPriority(1).withFlex().withMinWidth(140),
            ColumnSpec.text("issueText", "qms.preship.col.issueText", 200).withPriority(2),
            ColumnSpec.of("shipAllowYn", "qms.preship.col.shipAllowYn", ColumnSpec.ColumnType.YN, 80).withPriority(2),
            ColumnSpec.text("ncrNo", "qms.preship.col.ncrNo", 120).withPriority(3),
            ColumnSpec.of("createdAt", "qms.preship.col.raisedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(2),
            ColumnSpec.of("closedAt", "qms.preship.col.closedAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(3));

    private final PreshipMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;
    private final NumberingService numbering;
    private final DateRanges ranges;
    private final BizClock clock;

    static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }

    // ================================================================ 출하 목록

    /** 기간(계획일) 필수 — 비우면 오늘 앞뒤 30일. insp = NONE/OPEN/DONE, onlyRequired = 검수 필요 출하만 */
    @Transactional(readOnly = true)
    public ShipPage shipGrid(LocalDate from, LocalDate to, String keyword, String insp, Boolean onlyRequired, Integer page, Integer size,
            List<String> sort, Boolean total) {
        LocalDate today = clock.today();
        LocalDate f = from == null ? today.minusDays(30) : from;
        LocalDate t = to == null ? today.plusDays(30) : to;
        ranges.check(f, t, MAX_DAYS);
        String st = VisibleText.isBlank(insp) ? null : insp.strip();
        if (st != null && !INSP_FILTER.contains(st)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        boolean req = Boolean.TRUE.equals(onlyRequired);
        String kw = PreshipTplService.like(keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, ShipSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX), SHIP_SORT);
        return new ShipPage(mapper.findShipPage(f, t, kw, st, req, pq), pq.isWithTotal() ? mapper.countShip(f, t, kw, st, req) : null);
    }

    public List<ColumnSpec> shipColumns() {
        return SHIP_COLUMNS;
    }

    public List<ColumnSpec> punchColumns() {
        return PUNCH_COLUMNS;
    }

    @Transactional(readOnly = true)
    public ShipDetail shipDetail(long shipId) {
        ShipRow s = mapper.findShip(shipId);
        if (s == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<String> req = mapper.requiredItems(shipId).stream().map(RequiredItem::getItemCd).toList();
        InspHead h = mapper.findLiveInspOfShip(shipId);
        return new ShipDetail(s, req, h, h == null ? List.of() : mapper.findResults(h.getInspId()));
    }

    // ================================================================ 검수

    @Transactional
    public ShipDetail start(StartForm in) {
        LoginUser me = me();
        if (in == null || in.shipId() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        inbox.claimRequestKey("QMS_PRESHIP_START");
        long shipId = in.shipId();
        String shipStatus = mapper.lockShip(shipId);
        if (shipStatus == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"DRAFT".equals(shipStatus) && !"PICKING".equals(shipStatus)) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_STATE);
        }
        if (mapper.findLiveInspOfShip(shipId) != null) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_STATE);
        }
        List<RequiredItem> items = mapper.requiredItems(shipId);
        if (items.isEmpty()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add("shipId", "PRESHIP_NOT_REQUIRED").list());
        }
        String no = numbering.nextOrDefault(RULE, "출하 전 검수 번호", "PSI{yyMMdd}-{seq:3}", "DAY");
        long inspId;
        try {
            inspId = mapper.insertInsp(no, shipId, me.loginId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_STATE);
        }
        for (RequiredItem it : items) {
            mapper.insertResults(inspId, it.getItemId(), it.getTplId(), me.loginId());
        }
        return shipDetail(shipId);
    }

    @Transactional
    public JudgeResult judge(long inspId, JudgeForm in) {
        LoginUser me = me();
        if (in == null || in.resultId() == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        InspHead h = mapper.shareInsp(inspId);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ResultRow r = mapper.findResult(in.resultId());
        if (r == null || r.getInspId() != inspId) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"OPEN".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_STATE);
        }
        FieldErrors e = FieldErrors.create();
        String judge = VisibleText.isBlank(in.judge()) ? null : in.judge().strip();
        if (judge == null) {
            e.add("judge", "REQUIRED");
        } else if (!JUDGES.contains(judge)) {
            e.add("judge", "CODE");
        }
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        e.throwIfAny();
        if (r.getOpenPunchId() != null && !"NG".equals(judge)) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_PUNCH_OPEN);
        }
        if ("NA".equals(judge) && !"Y".equals(r.getNaAllowedYn())) {
            e.add("judge", "PRESHIP_NA_NOT_ALLOWED");
        }
        if ("NG".equals(judge) && remark == null) {
            e.add("remark", "REQUIRED");
        }
        if ("OK".equals(judge) && "Y".equals(r.getPhotoReqYn()) && mapper.countFiles(r.getResultId()) == 0) {
            e.add("judge", "PHOTO_REQUIRED");
        }
        e.throwIfAny();
        if (mapper.judge(r.getResultId(), in.rowVer(), judge, remark, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        Long punchId = null;
        if ("NG".equals(judge) && r.getOpenPunchId() == null) {
            punchId = mapper.insertPunch(inspId, r.getResultId(), remark, me.loginId());
        }
        return new JudgeResult(mapper.findResult(r.getResultId()), punchId);
    }

    /** 검수자·입회자·비고 — OPEN 동안만 */
    @Transactional
    public InspHead saveHead(long inspId, HeadForm in) {
        LoginUser me = me();
        InspHead h = lockOpen(inspId, in == null ? null : in.rowVer());
        Head v = head(in, false);
        if (mapper.updateHead(inspId, h.getRowVer(), v.inspector(), v.witness(), v.remark(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return mapper.findInsp(inspId);
    }

    /** 검수 완료(APPROVE) — 보낸 검수자·입회자·비고를 함께 저장하고 DONE */
    @Transactional
    public InspHead complete(long inspId, HeadForm in) {
        LoginUser me = me();
        InspHead h = lockOpen(inspId, in == null ? null : in.rowVer());
        Head v = head(in, true);
        FieldErrors e = FieldErrors.create();
        int unjudged = mapper.countUnjudged(inspId);
        if (unjudged > 0) {
            e.add("results", "PRESHIP_UNJUDGED", unjudged);
        }
        int blocking = mapper.countBlockingPunch(inspId);
        if (blocking > 0) {
            e.add("results", "PRESHIP_PUNCH_OPEN", blocking);
        }
        e.throwIfAny();
        if (mapper.updateHead(inspId, h.getRowVer(), v.inspector(), v.witness(), v.remark(), me.loginId()) == 0
                || mapper.markDone(inspId, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return mapper.findInsp(inspId);
    }

    /** 완료 취소(APPROVE) — 출하 확정 전까지 */
    @Transactional
    public InspHead reopen(long inspId, CancelForm in) {
        LoginUser me = me();
        InspHead h = lock(inspId, in == null ? null : in.rowVer());
        if (!"DONE".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_STATE);
        }
        String ship = mapper.lockShip(h.getShipId());
        if ("SHIPPED".equals(ship)) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_STATE);
        }
        if (mapper.markOpen(inspId, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return mapper.findInsp(inspId);
    }

    /** 검수 취소(SAVE) — OPEN 만, 사유 필수. 같은 출하로 다시 시작할 수 있다(출하 줄이 바뀐 경우) */
    @Transactional
    public InspHead cancel(long inspId, CancelForm in) {
        LoginUser me = me();
        lockOpen(inspId, in == null ? null : in.rowVer());
        FieldErrors e = FieldErrors.create();
        String reason = VisibleText.reason(in.reason(), "reason", 1, 500, e);
        e.throwIfAny();
        if (mapper.markCancelled(inspId, reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return mapper.findInsp(inspId);
    }

    private InspHead lock(long inspId, Integer rowVer) {
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        InspHead h = mapper.lockInsp(inspId);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (h.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return h;
    }

    private InspHead lockOpen(long inspId, Integer rowVer) {
        InspHead h = lock(inspId, rowVer);
        if (!"OPEN".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_STATE);
        }
        return h;
    }

    private record Head(String inspector, String witness, String remark) {
    }

    private static Head head(HeadForm in, boolean done) {
        FieldErrors e = FieldErrors.create();
        String inspector = done ? VisibleText.reason(in.inspectorNm(), "inspectorNm", 1, 100, e) : VisibleText.optional(in.inspectorNm(), "inspectorNm", 100, e);
        String witness = VisibleText.optional(in.witnessNm(), "witnessNm", 100, e);
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        e.throwIfAny();
        return new Head(inspector, witness, remark);
    }

    // ================================================================ 미결

    @Transactional(readOnly = true)
    public PunchPage punchGrid(String status, String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        String st = VisibleText.isBlank(status) ? null : status.strip();
        if (st != null && !"OPEN".equals(st) && !"CLOSED".equals(st)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String kw = PreshipTplService.like(keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, PunchSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX), PUNCH_SORT);
        return new PunchPage(mapper.findPunchPage(st, kw, pq), pq.isWithTotal() ? mapper.countPunch(st, kw) : null);
    }

    @Transactional(readOnly = true)
    public PunchRow punch(long punchId) {
        PunchRow p = mapper.findPunch(punchId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    /** 닫기 = 조치 내용 + 재확인 OK(사진 필수 항목은 사진 1개 이상). 검수 OPEN·DONE 모두 — 취소된 검수는 409 */
    @Transactional
    public PunchRow close(long punchId, CloseForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PunchRow p = lockPunch(punchId, in.rowVer());
        if (!"OPEN".equals(p.getStatus()) || "CANCELLED".equals(p.getInspStatus())) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_STATE);
        }
        FieldErrors e = FieldErrors.create();
        String action = VisibleText.reason(in.actionText(), "actionText", 1, 500, e);
        if ("Y".equals(p.getPhotoReqYn()) && mapper.countFiles(p.getResultId()) == 0) {
            e.add("actionText", "PHOTO_REQUIRED");
        }
        e.throwIfAny();
        if (mapper.recheckOk(p.getResultId(), p.getResultRowVer(), me.loginId()) == 0
                || mapper.closePunch(punchId, in.rowVer(), action, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return mapper.findPunch(punchId);
    }

    /** 조건부 출하 허용(APPROVE) — 사유 필수, 검수 OPEN 동안만, 되돌리지 않는다 */
    @Transactional
    public PunchRow allow(long punchId, AllowForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PunchRow p = lockPunch(punchId, in.rowVer());
        if (!"OPEN".equals(p.getStatus()) || !"OPEN".equals(p.getInspStatus()) || "Y".equals(p.getShipAllowYn())) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_STATE);
        }
        FieldErrors e = FieldErrors.create();
        String reason = VisibleText.reason(in.reason(), "reason", 1, 500, e);
        e.throwIfAny();
        if (mapper.allowPunch(punchId, in.rowVer(), reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return mapper.findPunch(punchId);
    }

    /** NCR 연결 — 번호로 같은 회사 NCR(없으면 칸 오류), 비우면 연결 해제. 닫힌 미결도 된다(기록 보완) */
    @Transactional
    public PunchRow linkNcr(long punchId, NcrForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PunchRow p = lockPunch(punchId, in.rowVer());
        if ("CANCELLED".equals(p.getInspStatus())) {
            throw new BusinessException(HbErrorCode.QMS_PRESHIP_STATE);
        }
        Long ncrId = null;
        if (!VisibleText.isBlank(in.ncrNo())) {
            String no = in.ncrNo().strip();
            ncrId = no.length() > 30 ? null : mapper.ncrIdByNo(no);
            if (ncrId == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add("ncrNo", "NO_SUCH_CODE").list());
            }
        }
        if (mapper.linkNcr(punchId, in.rowVer(), ncrId, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return mapper.findPunch(punchId);
    }

    private PunchRow lockPunch(long punchId, int rowVer) {
        PunchRow p = mapper.lockPunch(punchId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (p.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return p;
    }
}
