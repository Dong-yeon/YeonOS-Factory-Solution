package io.smarthappy.module.srm.portal;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.ack.AckDtos.AckForm;
import io.smarthappy.module.srm.ack.AckDtos.AckResult;
import io.smarthappy.module.srm.ack.AckService;
import io.smarthappy.module.srm.asn.AsnDtos.ActionForm;
import io.smarthappy.module.srm.asn.AsnService;
import io.smarthappy.module.srm.claim.ClaimService;
import io.smarthappy.module.srm.portal.PortalSrmDtos.AsnDetail;
import io.smarthappy.module.srm.portal.PortalSrmDtos.AsnForm;
import io.smarthappy.module.srm.portal.PortalSrmDtos.AsnRow;
import io.smarthappy.module.srm.portal.PortalSrmDtos.OwnAsn;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PlantOption;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PoDetail;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PoLineOption;
import io.smarthappy.module.srm.portal.PortalSrmDtos.PoRow;
import io.smarthappy.module.srm.portal.PortalSrmDtos.SupplierInfo;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.portal.PortalUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 협력사 포털 SRM 업무 (docs/42 7장 3, docs/31 4.13 P-1 발주 보기·회신 / P-2 ASN 입력·확정 + 납품서).
 * <ul>
 * <li>협력사는 {@link PortalUser#current()} 로만 — 요청 값·인자로 받지 않는다. 모든 읽기는 {@link PortalSrmMapper}(문장마다 custId).</li>
 * <li>쓰기는 소유를 같은 트랜잭션에서 잠가 확인(남의 것·없는 것 = 404, 있는지조차 알려주지 않음)한 뒤 내부 서비스로 — 검사·상태 계산을
 * 두 벌 두지 않는다. 출처 PORTAL.</li>
 * <li>거래 정지 협력사(trade_status BLOCKED)는 조회만, 새 입력(회신·ASN 만들기·고치기·확정)은 409(docs/42 P1). 취소는 받는다 — 보낸 납품
 * 통지를 거둘 길은 남긴다.</li>
 * <li>포털이 고칠 수 있는 ASN 은 출처 PORTAL 만 — 우리 직원이 대신 넣은(MANUAL)·엑셀(IMPORT) ASN 은 보기만(409).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PortalSrmService {

    static final int SEARCH_MAX_DAYS = 366;
    static final Set<String> ASN_STATUSES = Set.of("DRAFT", "SENT", "PARTIAL_RECEIVED", "RECEIVED", "CLOSED", "CANCELLED");

    private final PortalSrmMapper mapper;
    private final AckService acks;
    private final AsnService asns;
    private final ClaimService claimsService;
    private final PolicyService policies;
    private final DateRanges ranges;
    private final BizClock clock;

    private static long custId() {
        return PortalUser.current().custId();
    }

    @Transactional(readOnly = true)
    public SupplierInfo supplier() {
        SupplierInfo s = mapper.supplier(custId());
        if (s == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return s;
    }

    /** 새 입력 전에 — 거래 정지면 409(조회는 된다, docs/42 P1) */
    private SupplierInfo writable() {
        SupplierInfo s = supplier();
        if ("BLOCKED".equals(s.getTradeStatus())) {
            throw new BusinessException(HbErrorCode.SRM_PORTAL_BLOCKED);
        }
        return s;
    }

    // ------------------------------------------------------------------ 발주·회신 (P-1)

    @Transactional(readOnly = true)
    public List<PoRow> pos(String from, String to, String state, String keyword) {
        long cust = custId();
        LocalDate[] r = range(from, to);
        String st = blank(state) ? "OPEN" : code(state);
        if (!"OPEN".equals(st) && !"ALL".equals(st) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findPos(cust, r[0], r[1], "OPEN".equals(st), like(keyword), clock.zone().getId(), dueDays());
    }

    @Transactional(readOnly = true)
    public PoDetail po(long poId) {
        long cust = custId();
        PoRow po = mapper.findPo(cust, poId, clock.zone().getId(), dueDays());
        if (po == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new PoDetail(po, mapper.findPoLines(cust, poId), mapper.findAcks(cust, poId), mapper.findAckLines(cust, poId));
    }

    /** 납기 회신 — 검사·상태 계산은 대리 입력(AckService)과 같다. 발주 행 id 가 다른 발주 것이면 그쪽이 400 */
    @Transactional
    public AckResult ack(long poId, AckForm in) {
        writable();
        if (mapper.lockOwnPo(custId(), poId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return acks.recordFromPortal(poId, in);
    }

    // ------------------------------------------------------------------ ASN (P-2)

    @Transactional(readOnly = true)
    public List<AsnRow> asnList(String from, String to, String status, String keyword) {
        long cust = custId();
        LocalDate[] r = range(from, to);
        String st = blank(status) ? null : code(status);
        if (st != null && !ASN_STATUSES.contains(st) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findAsns(cust, r[0], r[1], st, like(keyword));
    }

    @Transactional(readOnly = true)
    public AsnDetail asn(long asnId) {
        long cust = custId();
        AsnRow a = mapper.findAsn(cust, asnId);
        if (a == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new AsnDetail(a, mapper.findAsnLines(cust, asnId));
    }

    @Transactional(readOnly = true)
    public List<PlantOption> plants() {
        return mapper.plants(custId());
    }

    /** ASN 에 넣을 수 있는 내 발주 행 — 고치는 중이면 그 ASN 의 몫은 빼고 센다(남의 ASN id 면 404) */
    @Transactional(readOnly = true)
    public List<PoLineOption> poLines(String plantCd, Long asnId) {
        long cust = custId();
        String plant = code(plantCd);
        if (plant == null || plant.length() > 20) {
            throw SrmChecks.fieldError("plantCd", plant == null ? "REQUIRED" : "NO_SUCH_CODE");
        }
        if (asnId != null && mapper.findAsn(cust, asnId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.poLineOptions(cust, plant, asnId);
    }

    @Transactional
    public long createAsn(AsnForm in) {
        SupplierInfo s = writable();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return asns.createFromPortal(internal(s.getCustCd(), in));
    }

    @Transactional
    public void updateAsn(long asnId, AsnForm in) {
        writable();
        ownPortalAsn(asnId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        asns.update(asnId, internal(null, in));       // 협력사는 고칠 때 바꾸지 않는다(내부 서비스가 지금 행의 것을 쓴다)
    }

    /** 확정(SENT = 협력사가 보냈음) — 한도 검사는 내부와 같다 */
    @Transactional
    public void sendAsn(long asnId, ActionForm in) {
        writable();
        ownPortalAsn(asnId);
        asns.send(asnId, in);
    }

    /** 취소(사유 필수) — 받은 것이 없을 때만(내부 규칙 그대로). 거래 정지여도 받는다 */
    @Transactional
    public void cancelAsn(long asnId, ActionForm in) {
        supplier();
        ownPortalAsn(asnId);
        asns.cancel(asnId, in);
    }

    /** 내 ASN 을 잠그고, 포털이 만든 것인지 — 남의 것·없는 것 404, 우리 직원·엑셀이 넣은 것 409 */
    private OwnAsn ownPortalAsn(long asnId) {
        OwnAsn a = mapper.lockOwnAsn(custId(), asnId);
        if (a == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"PORTAL".equals(a.getSource())) {
            throw new BusinessException(HbErrorCode.SRM_PORTAL_ASN_INTERNAL);
        }
        return a;
    }

    private static io.smarthappy.module.srm.asn.AsnDtos.AsnForm internal(String custCd, AsnForm in) {
        return new io.smarthappy.module.srm.asn.AsnDtos.AsnForm(custCd, in.plantCd(), in.shipDate(), in.etaDate(), in.supplierRef(),
                in.carrierMemo(), in.remark(), in.rowVer(), in.lines());
    }

    // ------------------------------------------------------------------ 클레임 회신 (P-4)

    static final Set<String> CLAIM_STATUSES = Set.of("SENT", "ANSWERED", "CLOSED", "CANCELLED");

    /** 내게 보낸 클레임(보낸 날 기간) — 작성 중은 보이지 않는다 */
    @Transactional(readOnly = true)
    public List<PortalSrmDtos.ClaimRow> claims(String from, String to, String status) {
        long cust = custId();
        LocalDate[] r = range(from, to);
        String st = blank(status) ? null : code(status);
        if (st != null && !CLAIM_STATUSES.contains(st)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findClaims(cust, r[0], r[1], st, clock.zone().getId());
    }

    @Transactional(readOnly = true)
    public PortalSrmDtos.ClaimDetail claim(long claimId) {
        long cust = custId();
        PortalSrmDtos.ClaimRow c = mapper.findClaim(cust, claimId);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new PortalSrmDtos.ClaimDetail(c, mapper.findClaimNcrs(cust, claimId));
    }

    /**
     * 클레임 회신 — 보냄 상태만(검사·상태는 ClaimService.answerFromPortal). 거래 정지여도 받는다: 클레임 회신은 정지의 원인이 된 품질 문제를
     * 푸는 길이다(새 납기 회신·ASN 과 다름 — docs/31 4.15)
     */
    @Transactional
    public void answerClaim(long claimId, PortalSrmDtos.ClaimAnswerForm in) {
        if (mapper.lockOwnClaim(custId(), claimId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        claimsService.answerFromPortal(claimId, in.rowVer(), in.answerCause(), in.answerAction());
    }

    // ------------------------------------------------------------------ 공통

    private LocalDate[] range(String from, String to) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", false, e);
        LocalDate t = date(to, "to", false, e);
        e.throwIfAny();
        ranges.check(f, t, SEARCH_MAX_DAYS);
        return new LocalDate[] {f, t};
    }

    private int dueDays() {
        return policies.intValue(Policies.SRM_ACK_DUE_DAYS);
    }

    private static String like(String keyword) {
        return blank(keyword) ? null : "%" + SrmChecks.like(keyword.strip()) + "%";
    }
}
