package io.smarthappy.module.srm.claim;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.decimal;
import static io.smarthappy.module.srm.SrmChecks.fieldError;
import static io.smarthappy.module.srm.SrmChecks.me;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.module.qms.link.SupplierQualityQuery.NcrDetail;
import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.SrmNumbers;
import io.smarthappy.module.srm.claim.ClaimDtos.ActionForm;
import io.smarthappy.module.srm.claim.ClaimDtos.AnswerForm;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimCrit;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimDetail;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimForm;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimPage;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimRow;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimSort;
import io.smarthappy.module.srm.claim.ClaimDtos.ClaimValue;
import io.smarthappy.module.srm.claim.ClaimDtos.NcrCandidate;
import io.smarthappy.module.srm.claim.ClaimDtos.NcrCandidatePage;
import io.smarthappy.module.srm.claim.ClaimDtos.NcrValue;
import io.smarthappy.module.srm.claim.ReturnDtos.SupplierRef;
import io.smarthappy.module.srm.notify.ClaimSentMailHandler;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 협력사 클레임 (srm-claim) — SRM 2부 R4d, docs/31 4.12.4, 결정 R4-6.
 * 근거 NCR(공개 계약 {@link SupplierQualityQuery#ncr} 로 확인·스냅샷) + 요구 조치(시정조치 / 비용 청구 / 둘 다)·금액(표기용, 청구는 ERP) →
 * 보내기(통지 아웃박스 SRM_CLAIM_SENT) → 회신 입력(원인·대책·회신일, 협력사 대신 — D4) → 반려(다시 요구) 또는 종결(APPROVE) / 취소(사유).
 * 한 NCR 은 살아 있는 클레임 하나(트리거 uq_srm_claim_ncr_live). 전이·고정 칸은 트리거가 최종.
 */
@Service
@RequiredArgsConstructor
public class ClaimService {

    public static final String PROG = "srm-claim";
    static final int SEARCH_MAX_DAYS = 366;
    static final int MAX_NCRS = 50;
    static final Set<String> STATUS = Set.of("DRAFT", "SENT", "ANSWERED", "CLOSED", "CANCELLED");
    static final Set<String> ACTIONS = Set.of("CAPA", "CHARGE", "BOTH");

    private final ClaimMapper mapper;
    private final ReturnMapper returns;
    private final SrmNumbers numbers;
    private final InboxService inbox;
    private final OutboxService outbox;
    private final DateRanges ranges;
    private final BizClock clock;
    private final PolicyService policies;
    private final ObjectProvider<SupplierQualityQuery> quality;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<ClaimRow> list(String from, String to, String status, String keyword) {
        return mapper.findClaims(criteria(from, to, status, keyword));
    }

    @Transactional(readOnly = true)
    public ClaimPage grid(String from, String to, String status, String keyword, Integer page, Integer size, List<String> sort,
            Boolean total) {
        ClaimCrit c = criteria(from, to, status, keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, ClaimSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("createdAt,desc"));
        return new ClaimPage(mapper.findClaimPage(c, pq), pq.isWithTotal() ? mapper.countClaims(c) : null);
    }

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("claimNo", "srm.claim.col.claimNo", 120).withSort().withPriority(1),
            ColumnSpec.text("custNm", "srm.claim.col.supplier", 140).withSort(),
            ColumnSpec.text("title", "srm.claim.col.title", 200).withSort().withFlex(),
            ColumnSpec.code("actionCd", "srm.claim.col.action", "CLAIM_ACTION", 120).withPriority(3),
            ColumnSpec.of("ncrCnt", "srm.claim.col.ncrCnt", ColumnType.INT, 60).withPriority(3),
            ColumnSpec.of("replyDue", "srm.claim.col.replyDue", ColumnType.DATE, 100).withSort().withPriority(3),
            ColumnSpec.of("createdAt", "srm.claim.col.createdAt", ColumnType.DATETIME, 130).withSort().withPriority(3),
            ColumnSpec.code("status", "srm.claim.col.status", "CLAIM_STATUS", 90).withSort().withPriority(1));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    private ClaimCrit criteria(String from, String to, String status, String keyword) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", true, e);
        LocalDate t = date(to, "to", true, e);
        e.throwIfAny();
        ranges.check(f, t, SEARCH_MAX_DAYS);
        String st = code(status);
        if (st != null && !STATUS.contains(st) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = blank(keyword) ? null : "%" + SrmChecks.like(keyword.strip()) + "%";
        return new ClaimCrit(f, t, clock.zone().getId(), st, k);
    }

    @Transactional(readOnly = true)
    public ClaimDetail get(long claimId) {
        ClaimRow c = mapper.findClaim(claimId);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new ClaimDetail(c, mapper.findNcrs(claimId), mapper.returnsOfClaim(claimId, clock.zone().getId()));
    }

    /**
     * 근거 NCR 후보 — QMS 계약 {@link SupplierQualityQuery#ncrsOfSupplier}(협력사 LOT 의 NCR, 발견 시각이 기간 안, 취소 제외 — 처리 중·반품 없는
     * NCR 도) + NCR 마다 살아 있는 클레임 번호. 보기용, truncated 면 화면이 기간을 줄이라고 알린다
     */
    @Transactional(readOnly = true)
    public NcrCandidatePage candidates(String custCd, String from, String to) {
        SupplierRef s = supplier(custCd);
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", true, e);
        LocalDate t = date(to, "to", true, e);
        e.throwIfAny();
        ranges.check(f, t, SEARCH_MAX_DAYS);
        SupplierQualityQuery.NcrPage page = SupplierQualityQuery.of(quality).ncrsOfSupplier(s.getCustId(), f, t);
        Map<Long, String> live = new HashMap<>();
        if (!page.rows().isEmpty()) {
            mapper.liveClaims(page.rows().stream().map(NcrDetail::ncrId).toList())
                    .forEach(m -> live.put(((Number) m.get("ncr_id")).longValue(), String.valueOf(m.get("claim_no"))));
        }
        List<NcrCandidate> rows = page.rows().stream().map(n -> new NcrCandidate(n.ncrId(), n.ncrNo(), n.status(), n.lotNo(), n.itemCd(),
                plain(n.ncQty()), n.unitCd(), n.defectNm(), n.occurredAt(), live.get(n.ncrId()))).toList();
        return new NcrCandidatePage(rows, page.truncated());
    }

    private SupplierRef supplier(String custCd) {
        String cd = code(custCd);
        SupplierRef s = cd == null || cd.length() > 30 ? null : returns.findSupplier(cd);
        if (s == null) {
            throw fieldError("custCd", cd == null ? "REQUIRED" : "NO_SUCH_CODE");
        }
        return s;
    }

    // ------------------------------------------------------------------ 저장(머리 + 근거 NCR 한 벌)

    @Transactional
    public long create(ClaimForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_CLAIM_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        Long custId = null;
        String cd = code(in.custCd());
        if (cd == null) {
            e.add("custCd", "REQUIRED");
        } else {
            SupplierRef s = cd.length() > 30 ? null : returns.findSupplier(cd);
            if (s == null) {
                e.add("custCd", "NO_SUCH_CODE");
            } else {
                custId = s.getCustId();
            }
        }
        ClaimValue v = values(in, custId == null ? 0 : custId, e);
        List<NcrValue> ncrs = custId == null ? List.of() : ncrs(in.ncrIds(), custId, e);
        e.throwIfAny();
        try {
            long id = mapper.insertClaim(numbers.next(SrmNumbers.CLAIM, me.loginId()), v, me.loginId());
            for (NcrValue n : ncrs) {
                mapper.insertNcr(id, n, me.loginId());
            }
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void update(long claimId, ClaimForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ClaimRow cur = start(claimId, in.rowVer(), "DRAFT");
        FieldErrors e = FieldErrors.create();
        ClaimValue v = values(in, cur.getCustId(), e);
        List<NcrValue> ncrs = ncrs(in.ncrIds(), cur.getCustId(), e);
        e.throwIfAny();
        try {
            if (mapper.updateClaim(claimId, v, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            mapper.deleteNcrs(claimId);              // 작성 중 근거는 통째로 다시
            for (NcrValue n : ncrs) {
                mapper.insertNcr(claimId, n, me.loginId());
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 머리 검사 — 비용 청구가 들면 금액(> 0)·통화, 시정조치만이면 금액 없음 */
    private ClaimValue values(ClaimForm in, long custId, FieldErrors e) {
        String action = code(in.actionCd());
        if (action == null) {
            e.add("actionCd", "REQUIRED");
        } else if (!ACTIONS.contains(action)) {
            e.add("actionCd", "CODE");
        }
        String title = text(in.title(), "title", 200, true, e);
        String request = text(in.requestText(), "requestText", 2000, true, e);
        LocalDate due = date(in.replyDue(), "replyDue", false, e);
        BigDecimal amt = null;
        String currency = null;
        if ("CAPA".equals(action)) {
            if (!blank(in.chargeAmt())) {
                e.add("chargeAmt", "SRM_CLM_NO_CHARGE");
            }
        } else if (action != null) {
            amt = decimal(in.chargeAmt(), "chargeAmt", 4, 16, true, e);
            currency = code(in.currencyCd());
            if (currency == null) {
                e.add("currencyCd", "REQUIRED");
            } else if (currency.length() > 30) {
                e.add("currencyCd", "CODE");
            }
        }
        return new ClaimValue(custId, action, title, request, due, amt, currency);
    }

    /** 근거 NCR — 계약으로 읽어 이 협력사 LOT 의 NCR(취소 아님)인지 보고 번호·품목·수량을 스냅샷. 행 키 = 배열 순번 */
    private List<NcrValue> ncrs(List<Long> ids, long custId, FieldErrors e) {
        List<NcrValue> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        if (ids.size() > MAX_NCRS) {
            e.add("ncrIds", "TOO_MANY_LINES", MAX_NCRS);
            return out;
        }
        SupplierQualityQuery q = SupplierQualityQuery.of(quality);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            String key = String.valueOf(i);
            if (id == null) {
                e.row(key).add("ncrId", "REQUIRED");
                continue;
            }
            if (!seen.add(id)) {
                e.row(key).add("ncrId", "DUPLICATE");
                continue;
            }
            NcrDetail n = q.ncr(id).orElse(null);
            if (n == null) {
                e.row(key).add("ncrId", "NO_SUCH_CODE");
            } else if (n.supplierCustId() == null || n.supplierCustId() != custId) {
                e.row(key).add("ncrId", "SRM_RTN_NCR_SUPPLIER");
            } else if ("CANCELLED".equals(n.status())) {
                e.row(key).add("ncrId", "SRM_CLM_NCR_CANCELLED");
            } else {
                out.add(new NcrValue(n.ncrId(), n.ncrNo(), n.itemId(), n.ncQty(), n.unitCd()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 상태 전이

    /** 보내기 — 근거 NCR 1개 이상(트리거 ck_srm_claim_ncrs) + 통지(아웃박스, 같은 트랜잭션) */
    @Transactional
    public void send(long claimId, ActionForm in) {
        LoginUser me = me();
        start(claimId, rowVer(in), "DRAFT");
        if (mapper.findNcrs(claimId).isEmpty()) {
            throw fieldError("ncrIds", "LINES_REQUIRED");
        }
        if (mapper.send(claimId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        notifySupplier(claimId);
    }

    /** 회신 입력(협력사 대신) — 보냄 → 회신 받음, 회신 받음이면 고쳐 쓰기. 회신일은 오늘 이전 */
    @Transactional
    public void answer(long claimId, AnswerForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        start(claimId, in.rowVer(), "SENT", "ANSWERED");
        FieldErrors e = FieldErrors.create();
        String cause = text(in.answerCause(), "answerCause", 2000, true, e);
        String action = text(in.answerAction(), "answerAction", 2000, true, e);
        LocalDate on = date(in.answeredOn(), "answeredOn", true, e);
        if (on != null && on.isAfter(clock.today())) {
            e.add("answeredOn", "SRM_CLM_FUTURE");
        }
        e.throwIfAny();
        if (mapper.answer(claimId, in.rowVer(), cause, action, on, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /**
     * 협력사 포털 회신(P-4, docs/31 4.15) — **보냄(SENT)에서만**(회신 받음·종결·취소·작성 중은 409 SRM_STATE, 고쳐 쓰기 없음 — 고칠 일은
     * 우리 쪽 반려 뒤 다시 회신). 글만(파일 없음), 글자 수는 대신 입력과 같다(원인·대책 각 2000). 회신일 = 오늘(회사 날짜), 경로 PORTAL.
     * 같은 요청 반복: 멱등이 아니다 — 두 번째는 이미 회신 받음이라 409 SRM_STATE(요청 키 헤더가 같으면 그 전에 409 DUPLICATE_REQUEST).
     * 부르는 쪽(module.srm.portal)이 같은 트랜잭션에서 그 클레임이 로그인 협력사 것인지 먼저 잠가 확인한다.
     */
    @Transactional
    public void answerFromPortal(long claimId, Integer rowVer, String answerCause, String answerAction) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_CLAIM_PORTAL_ANSWER");
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        start(claimId, rowVer, "SENT");
        FieldErrors e = FieldErrors.create();
        String cause = text(answerCause, "answerCause", 2000, true, e);
        String action = text(answerAction, "answerAction", 2000, true, e);
        e.throwIfAny();
        if (mapper.answerFromPortal(claimId, rowVer, cause, action, clock.today(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 회신 반려 — 다시 요구(보냄으로). 회신 칸은 남겨 두고 다음 회신이 덮는다 */
    @Transactional
    public void reject(long claimId, ActionForm in) {
        LoginUser me = me();
        start(claimId, rowVer(in), "ANSWERED");
        if (mapper.reject(claimId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 종결(APPROVE) — 회신 받은 클레임만 */
    @Transactional
    public void close(long claimId, ActionForm in) {
        LoginUser me = me();
        start(claimId, rowVer(in), "ANSWERED");
        if (mapper.close(claimId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 취소(사유 필수) — 작성 중·보냄에서 */
    @Transactional
    public void cancel(long claimId, ActionForm in) {
        LoginUser me = me();
        start(claimId, rowVer(in), "DRAFT", "SENT");
        FieldErrors e = FieldErrors.create();
        String reason = text(in.reason(), "reason", 500, true, e);
        e.throwIfAny();
        if (mapper.cancel(claimId, in.rowVer(), reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 통지 이력의 "다시 보내기"(실패 통지) — 보냄·회신 받음·종결 상태에서만(작성 중·취소는 통지할 것이 없음). 새 메시지로 다시 넣는다 */
    @Transactional
    public void renotify(long claimId) {
        ClaimRow cur = mapper.lockClaim(claimId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!Set.of("SENT", "ANSWERED", "CLOSED").contains(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        notifySupplier(claimId);
    }

    /** 협력사 통지 — 아웃박스(같은 트랜잭션, 실제 송신은 릴레이). bizKey = CLM:id, 본문에 메일 재료를 싣는다 */
    private void notifySupplier(long claimId) {
        Map<String, Object> i = mapper.notifyInfo(claimId);
        Map<String, Object> p = new HashMap<>();
        p.put("kind", ClaimSentMailHandler.TOPIC);
        p.put("claimId", claimId);
        p.put("claimNo", i.get("claim_no"));
        p.put("title", i.get("title"));
        p.put("actionCd", i.get("action_cd"));
        p.put("chargeAmt", i.get("charge_amt"));
        p.put("currencyCd", i.get("currency_cd"));
        p.put("replyDue", i.get("reply_due") == null ? null : i.get("reply_due").toString());
        p.put("ncrCnt", i.get("ncr_cnt"));
        p.put("custNm", i.get("cust_nm"));
        p.put("compNm", i.get("comp_nm"));
        p.put("to", i.get("email"));
        outbox.publish(ClaimSentMailHandler.TOPIC, "CLM:" + claimId, p);
    }

    private static int rowVer(ActionForm in) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return in.rowVer();
    }

    private ClaimRow start(long claimId, int rowVer, String... from) {
        ClaimRow cur = mapper.lockClaim(claimId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!List.of(from).contains(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        if (cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return cur;
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "uq_srm_claim_ncr_live" -> fieldError("ncrIds", "SRM_CLM_NCR_TAKEN");
            case "fk_srm_claim_currency" -> fieldError("currencyCd", "NO_SUCH_CODE");
            case "ck_srm_claim_charge" -> fieldError("chargeAmt", "POSITIVE");
            default -> e;
        };
    }
}
