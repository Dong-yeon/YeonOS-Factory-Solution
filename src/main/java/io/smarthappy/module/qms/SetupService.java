package io.smarthappy.module.qms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.link.EcoLookup;
import io.smarthappy.module.plm.link.EcoLookup.EcoView;
import io.smarthappy.module.plm.link.EcoTrace;
import io.smarthappy.module.plm.link.EcoTrace.MarkedLot;
import io.smarthappy.module.qms.SetupDtos.EcoHead;
import io.smarthappy.module.qms.SetupDtos.FirstLot;
import io.smarthappy.module.qms.SetupDtos.InspOption;
import io.smarthappy.module.qms.SetupDtos.InspRef;
import io.smarthappy.module.qms.SetupDtos.RecordForm;
import io.smarthappy.module.qms.SetupDtos.SetupDetail;
import io.smarthappy.module.qms.SetupDtos.VerdictRow;
import io.smarthappy.module.qms.SetupDtos.VoidForm;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.security.LoginUser;

/**
 * 초품(셋업 검증) (qms-setup) — P3 QMS 2부 Q7, docs/29_P3 12.3.
 *
 * - 대상 = ECO 가 첫 LOT(FIRST_LOT)으로 표시한 LOT(PLM 계약 EcoTrace.markedLots, 사용자 Q-d)
 * - 판정 = 그 LOT 의 판정 확정된 Q6 검사 하나를 근거로 결과 복사. ECO·LOT 마다 살아 있는 판정 하나, 정정 = 무효 뒤 다시
 * - ECO 머리·표시 목록은 계약으로만(PLM 표 직접 조회 없음)
 */
@Service
public class SetupService {

    static final String PROG = "qms-setup";
    static final String FIRST_LOT = "FIRST_LOT";
    static final int LIST_LIMIT = 200;

    private final SetupMapper mapper;
    private final InboxService inbox;
    private final ObjectProvider<EcoLookup> ecoLookup;
    private final ObjectProvider<EcoTrace> ecoTrace;

    public SetupService(SetupMapper mapper, InboxService inbox, ObjectProvider<EcoLookup> ecoLookup, ObjectProvider<EcoTrace> ecoTrace) {
        this.mapper = mapper;
        this.inbox = inbox;
        this.ecoLookup = ecoLookup;
        this.ecoTrace = ecoTrace;
    }

    @Transactional(readOnly = true)
    public List<VerdictRow> list() {
        List<VerdictRow> rows = mapper.findRecent(LIST_LIMIT);
        Map<Long, EcoView> ecos = EcoLookup.of(ecoLookup).ecos(rows.stream().map(VerdictRow::getEcoId).distinct().toList()).stream()
                .collect(Collectors.toMap(EcoView::ecoId, Function.identity()));
        rows.forEach(r -> {
            EcoView v = ecos.get(r.getEcoId());
            if (v != null) {
                r.setEcoNo(v.ecoNo());
                r.setEcoTitle(v.title());
            }
        });
        return rows;
    }

    @Transactional(readOnly = true)
    public SetupDetail detail(long ecoId) {
        EcoView v = eco(ecoId);
        List<MarkedLot> firsts = firstLots(ecoId);
        Map<Long, VerdictRow> active = mapper.findActive(ecoId).stream().collect(Collectors.toMap(VerdictRow::getLotId, Function.identity()));
        Map<Long, List<InspOption>> cands = firsts.isEmpty() ? Map.of()
                : mapper.judgedInsps(firsts.stream().map(MarkedLot::lotId).toList()).stream().collect(Collectors.groupingBy(InspOption::getLotId));
        List<FirstLot> lots = new ArrayList<>();
        int ok = 0;
        int ng = 0;
        for (MarkedLot m : firsts) {
            VerdictRow r = active.get(m.lotId());
            if (r != null) {
                if ("OK".equals(r.getResult())) {
                    ok++;
                } else {
                    ng++;
                }
            }
            lots.add(new FirstLot(m.lotId(), m.lotNo(), m.seqNo(), m.occurredAt(), r, cands.getOrDefault(m.lotId(), List.of())));
        }
        return new SetupDetail(new EcoHead(v.ecoId(), v.ecoNo(), v.title(), v.changeKind(), v.status(), v.effectiveDate()), lots, ok, ng,
                firsts.size() - ok - ng);
    }

    /** 판정 기록 — 근거 검사의 결과를 복사 */
    @Transactional
    public long record(long ecoId, RecordForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_SETUP_RECORD");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        eco(ecoId);
        FieldErrors e = FieldErrors.create();
        if (in.lotId() == null) {
            e.add("lotId", "REQUIRED");
        } else if (firstLots(ecoId).stream().noneMatch(m -> m.lotId() == in.lotId())) {
            e.add("lotId", "NOT_FIRST_LOT");
        }
        InspRef insp = null;
        if (in.inspId() == null) {
            e.add("inspId", "REQUIRED");
        } else if ((insp = mapper.findInsp(in.inspId())) == null) {
            e.add("inspId", "NOT_FOUND");
        } else if (in.lotId() != null && (insp.getLotId() != in.lotId() || !"JUDGED".equals(insp.getStatus()))) {
            e.add("inspId", "SETUP_EVIDENCE");
        }
        String note = VisibleText.optional(in.note(), "note", 1000, e);
        e.throwIfAny();
        if (mapper.findActive(ecoId).stream().anyMatch(r -> r.getLotId() == in.lotId())) {
            throw new BusinessException(HbErrorCode.QMS_SETUP_DONE);
        }
        return mapper.insert(ecoId, in.lotId(), insp.getInspId(), insp.getResult(), note, me.loginId());
    }

    @Transactional
    public void voidVerdict(long verifId, VoidForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String reason = VisibleText.reason(in.reason(), "reason", 1, 500, e);
        e.throwIfAny();
        VerdictRow r = mapper.findById(verifId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"ACTIVE".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_SETUP_DONE);
        }
        if (mapper.voidVerdict(verifId, reason, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** ECO 의 첫 LOT 표시(도착 순) — 요약 계약 ③ 도 쓴다 */
    List<MarkedLot> firstLots(long ecoId) {
        return EcoTrace.of(ecoTrace).markedLots(ecoId).stream().filter(m -> FIRST_LOT.equals(m.reason())).toList();
    }

    private EcoView eco(long ecoId) {
        return EcoLookup.of(ecoLookup).eco(ecoId).orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
    }
}
