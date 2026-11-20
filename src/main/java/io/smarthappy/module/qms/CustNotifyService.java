package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.link.EcoLookup;
import io.smarthappy.module.plm.link.EcoLookup.EcoView;
import io.smarthappy.module.plm.link.EcoLookup.PcnView;
import io.smarthappy.module.qms.CustNotifyJudge.Basis;
import io.smarthappy.module.qms.CustNotifyJudge.Result;
import io.smarthappy.platform.common.web.ApiError.FieldErrorView;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors.FieldError;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 4M 고객 통지 판단 (docs/29_P3 52장, docs/66 (라)).
 *
 * - 대상 고객 = ECO 고객 칸(PLM 계약 EcoLookup.custId)만 — 없으면 판정 NO_CUSTOMER, 경고 없음(Q4-4). 수주 이력 추정 안 함
 * - 요구 = 그 고객의 맞는 CSR(qms_cust_csr.notify_req) × 4M 세부 유형 → 순수 함수 {@link CustNotifyJudge}
 * - PCN 은 PLM(plm_pcn) — 계약 EcoLookup.pcns 로 읽기만. QMS 는 PCN 을 만들지도 메일을 보내지도 않는다(52장 A 채택·CN-9)
 * - 상신 막기는 정책 QMS_CUST_NOTIFY_GATE = BLOCK 일 때만, "승인 요구인데 ECO 고객 승인 필요 꺼짐" 하나(CN-7)
 */
@Service
public class CustNotifyService {

    static final String GATE_BLOCK = "BLOCK";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** PCN 한 줄 — overdue = SENT 인데 응답 기한 지남 */
    public record PcnRow(long pcnId, String pcnNo, LocalDate notifiedOn, LocalDate responseDue, String status, LocalDate respondedOn,
            boolean overdue) {
    }

    /**
     * 통지 구획 — state = NO_CUSTOMER·NO_4M·JUDGED, level = NONE·NOTIFY·APPROVE(JUDGED 일 때), warnings = 문구 포함,
     * otherCustPcns = ECO 고객이 아닌 고객에게 보낸 PCN 수(참고), gate = 정책 WARN·BLOCK
     */
    public record NotifyView(long ecoId, String ecoNo, String ecoStatus, Long custId, String custCd, String custNm,
            String custApprovalStatus, String state, String level, List<Basis> basis, List<FieldErrorView> warnings, List<PcnRow> pcns,
            int otherCustPcns, String gate) {
    }

    private final CustNotifyMapper mapper;
    private final ChangeMapper changes;
    private final ObjectProvider<EcoLookup> ecoLookup;
    private final PolicyService policies;
    private final BizClock clock;
    private final MessageSource messages;

    public CustNotifyService(CustNotifyMapper mapper, ChangeMapper changes, ObjectProvider<EcoLookup> ecoLookup, PolicyService policies,
            BizClock clock, MessageSource messages) {
        this.mapper = mapper;
        this.changes = changes;
        this.ecoLookup = ecoLookup;
        this.policies = policies;
        this.clock = clock;
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    public NotifyView view(long ecoId) {
        EcoLookup lookup = EcoLookup.of(ecoLookup);
        EcoView v = lookup.eco(ecoId).orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        Long custId = lookup.custId(ecoId).orElse(null);
        LocalDate today = clock.today();
        List<PcnView> all = lookup.pcns(ecoId);
        List<PcnView> mine = custId == null ? List.of() : all.stream().filter(p -> p.custId() == custId).toList();
        Result r = judge(v, custId, mine, today);
        CustNotifyMapper.CustRef cust = custId == null ? null : mapper.findCust(custId);
        return new NotifyView(ecoId, v.ecoNo(), v.status(), custId, cust == null ? null : cust.getCustCd(), cust == null ? null : cust.getCustNm(),
                v.custApprovalStatus(), r.state(), r.level(), r.basis(),
                r.warnings().stream().map(code -> new FieldErrorView(null, null, code,
                        messages.getMessage("valid." + code, null, code, LocaleContextHolder.getLocale()))).toList(),
                mine.stream().map(p -> new PcnRow(p.pcnId(), p.pcnNo(), p.notifiedOn(), p.responseDue(), p.status(), p.respondedOn(),
                        r.overdue().contains(p.pcnNo()))).toList(),
                all.size() - mine.size(), policies.value(Policies.QMS_CUST_NOTIFY_GATE));
    }

    /**
     * ECO 상신 규칙 ④ 에 더할 칸 오류(CN-7) — 정책 BLOCK 이고 "승인 요구인데 ECO 고객 승인 필요 꺼짐" 일 때만. PLM 상신 트랜잭션 안(읽기만)
     */
    List<FieldError> gateIssues(EcoView v) {
        if (!GATE_BLOCK.equals(policies.value(Policies.QMS_CUST_NOTIFY_GATE))) {
            return List.of();
        }
        Optional<Long> custId = EcoLookup.of(ecoLookup).custId(v.ecoId());
        if (custId.isEmpty()) {
            return List.of();
        }
        Result r = judge(v, custId.get(), List.of(), clock.today());
        return r.warnings().contains(CustNotifyJudge.W_APPROVAL)
                ? List.of(new FieldError(ChangeService.RULE_ROW, "custApproval", CustNotifyJudge.W_APPROVAL))
                : List.of();
    }

    private Result judge(EcoView v, Long custId, List<PcnView> pcns, LocalDate today) {
        if (custId == null) {
            return CustNotifyJudge.judge(null, List.of(), List.of(), v.custApprovalStatus(), v.status(), List.of(), today);
        }
        ChangeDtos.ChangeHead head = changes.findHead(v.ecoId());
        List<String> types = head == null ? List.of() : head.getM4Types();
        List<CustNotifyJudge.Csr> csrs = types.isEmpty() ? List.of()
                : mapper.matchedCsrs(custId, v.itemIds(), today).stream()
                        .map(c -> new CustNotifyJudge.Csr(c.getCsrId(), c.getCsrNo(), c.getCsrRev(), parse(c.getNotifyReqJson()))).toList();
        return CustNotifyJudge.judge(custId, types, csrs, v.custApprovalStatus(), v.status(),
                pcns.stream().map(p -> new CustNotifyJudge.Pcn(p.pcnNo(), p.responseDue(), p.status())).toList(), today);
    }

    private static Map<String, String> parse(String json) {
        return json == null ? Map.of() : JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
    }
}
