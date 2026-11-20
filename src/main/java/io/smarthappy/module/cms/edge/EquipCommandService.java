package io.smarthappy.module.cms.edge;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.edge.EdgeDtos.ClaimedCmd;
import io.smarthappy.module.cms.edge.EdgeDtos.CmdForm;
import io.smarthappy.module.cms.edge.EdgeDtos.CmdRow;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRef;
import io.smarthappy.module.cms.edge.EdgeDtos.StepRow;
import io.smarthappy.module.cms.edge.EdgeDtos.TagRow;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;

/**
 * 장비 명령 쓰기 4단 (docs/65 5.3·16.4) — 서버는 장비에 직접 쓰지 않는다.
 *
 * 의도(여기) → 보내기(연결 방식별 {@link EquipCommandTransport}) → 엣지 2차 검사·쓰기 → 다시 읽기 → 결과({@link CmdResultRoute}).
 * - 1차 검사: 쓰기 활성화 · 논리 태그 허용 목록(형식·범위) · 인터록(쓸 수 있는 PackML 상태). 엣지·PLC 가 최종
 * - 같은 설비·태그에 진행 중 명령은 하나(부분 유일) — 서로 덮어쓰는 쓰기를 막는다
 * - 시간 초과: 가져가기·보내기 전 = EXPIRED(안 씀이 확실), 그 뒤 = UNKNOWN(썼을 수 있음 — 자동 재전송 없음, 사람이 판단)
 */
@Service
public class EquipCommandService {

    static final int LEASE_SEC = 60;
    static final int CLAIM_MAX = 20;

    private final EdgeMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;
    private final BizClock clock;
    private final Map<String, EquipCommandTransport> transports;

    public EquipCommandService(EdgeMapper mapper, InboxService inbox, PolicyService policies, BizClock clock,
            List<EquipCommandTransport> transports) {
        this.mapper = mapper;
        this.inbox = inbox;
        this.policies = policies;
        this.clock = clock;
        this.transports = transports.stream().collect(Collectors.toUnmodifiableMap(EquipCommandTransport::connMode, Function.identity()));
    }

    /** 명령 요청(화면) — 요청 키(X-Idempotency-Key) 필수는 컨트롤러가 */
    @Transactional
    public CmdRow request(long equipId, CmdForm f) {
        inbox.claimRequestKey("CMS_EQUIP_CMD");
        LinkRef link = mapper.lockLink(equipId);
        if (link == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"Y".equals(link.getUseYn()) || !"Y".equals(link.getWriteEnabledYn())) {
            throw new BusinessException(HbErrorCode.EQUIP_WRITE_DISABLED);
        }
        FieldErrors e = FieldErrors.create();
        String tagCd = f == null || f.tagCd() == null ? null : EdgeSupport.upper(f.tagCd().strip());
        TagRow tag = tagCd == null || tagCd.isEmpty() ? null : mapper.findTag(equipId, tagCd);
        if (tagCd == null || tagCd.isEmpty()) {
            e.add("tagCd", "REQUIRED");
        } else if (tag == null) {
            e.add("tagCd", "NOT_FOUND");
        } else if (!"Y".equals(tag.getUseYn()) || !"Y".equals(tag.getWritableYn())) {
            e.add("tagCd", "INACTIVE");
        }
        String value = null;
        if (f == null || f.value() == null || f.value().isBlank()) {
            e.add("value", "REQUIRED");
        } else if (tag != null) {
            value = EdgeSupport.normalize(tag.getDataType(), f.value());
            if (value == null) {
                e.add("value", "FORMAT");
            } else if ("NUM".equals(tag.getDataType())) {
                BigDecimal v = new BigDecimal(value);
                if (v.compareTo(tag.getMinValue()) < 0 || v.compareTo(tag.getMaxValue()) > 0) {
                    e.add("value", "RANGE", tag.getMinValue().stripTrailingZeros().toPlainString(),
                            tag.getMaxValue().stripTrailingZeros().toPlainString());
                }
            }
        }
        // 필수 사유 — 보이지 않는 글자(U+200B 등)만 넣으면 빈 값(VisibleText)
        String reason = VisibleText.reason(f == null ? null : f.reason(), "reason", 1, 500, e);
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
        }
        // 인터록 1차 — 지금 PackML 상태가 허용 목록 밖이면 거부(모르는 상태도 거부)
        String states = tag.getAllowedStates();
        if (states != null && !states.isEmpty() && (link.getPackState() == null
                || !Arrays.asList(states.split(",")).contains(link.getPackState()))) {
            throw new BusinessException(HbErrorCode.EQUIP_CMD_INTERLOCK, link.getPackState() == null ? "-" : link.getPackState(), states);
        }
        EquipCommandTransport transport = transports.get(link.getConnMode());
        if (transport == null) {
            throw new IllegalStateException("연결 방식 송신기 없음: " + link.getConnMode());
        }
        Instant now = clock.instant();
        CmdRow c = new CmdRow();
        c.setCmdKey(UUID.randomUUID());
        c.setEquipId(equipId);
        c.setTagCd(tag.getTagCd());
        c.setCmdValue(value);
        c.setReason(reason);
        c.setConnMode(link.getConnMode());
        c.setTerminalId(link.getTerminalId());
        c.setRequestedBy(TenantContext.currentActor());
        c.setExpiresAt(now.plus(Duration.ofSeconds(policies.intValue("EQUIP_CMD_TIMEOUT_SEC"))).atOffset(ZoneOffset.UTC));
        long id;
        try {
            id = mapper.insertCmd(c);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.EQUIP_CMD_BUSY);
        }
        String by = TenantContext.currentActor();
        mapper.insertStep(id, "INTENT", null, value, reason, "USER", null, null, now, "KEY", by);
        CmdRow row = mapper.findCmd(id);
        String sent = transport.dispatch(row, link);
        if (sent != null) {
            mapper.setCmdStatus(id, "SENT", null, null);
            mapper.insertStep(id, "SENT", null, null, sent, "SERVER", link.getTerminalId(), null, now, "AUTO", by);
        }
        return withSteps(mapper.findCmd(id));
    }

    /** 엣지가 자기 명령을 가져간다(EDGE) — lease 를 잡고 단계 CLAIM. 만료 전 REQUESTED 만, 다른 장치·다른 회사 명령은 안 보인다 */
    @Transactional
    public List<ClaimedCmd> claim(TerminalPrincipal d, int max) {
        int n = Math.max(1, Math.min(max, CLAIM_MAX));
        List<ClaimedCmd> out = new ArrayList<>();
        Instant now = clock.instant();
        for (CmdRow c : mapper.claimable(d.terminalId(), n)) {
            if (mapper.markClaimed(c.getCmdId(), LEASE_SEC) == 1) {
                mapper.insertStep(c.getCmdId(), "CLAIM", null, null, null, "EDGE", d.terminalId(), null, now, "AUTO", EdgeSupport.actor(d));
                out.add(mapper.toClaimed(c.getCmdId()));
            }
        }
        return out;
    }

    /**
     * 시간 초과 판정(정기 작업, 회사 범위 트랜잭션 안). 보내지 않은 것(EDGE 큐 REQUESTED, DIRECT 아웃박스가 못 보냄) = EXPIRED,
     * 보냈거나 가져간 것 = UNKNOWN. @return 처리한 수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int expireOverdue() {
        Instant now = clock.instant();
        int n = 0;
        for (CmdRow c : mapper.overdue(now)) {
            boolean delivered = !"REQUESTED".equals(c.getStatus())
                    && !("SENT".equals(c.getStatus()) && "DIRECT".equals(c.getConnMode()) && !mapper.outboxSent(DirectCommandTransport.bizKey(c.getCmdId())));
            String status = delivered ? "UNKNOWN" : "EXPIRED";
            String detail = delivered ? "NO_RESULT" : "NOT_DELIVERED";
            mapper.setCmdStatus(c.getCmdId(), status, detail, null);
            mapper.insertStep(c.getCmdId(), "TIMEOUT", "N", null, detail, "SERVER", null, null, now, "AUTO", TenantContext.currentActor());
            n++;
        }
        return n;
    }

    /** UNKNOWN 을 사람이 확정 — 현장에서 확인한 값으로 DONE/FAILED */
    @Transactional
    public CmdRow resolve(long equipId, long cmdId, String status, String note) {
        CmdRow c = mapper.findCmd(cmdId);
        if (c == null || c.getEquipId() != equipId) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        if (!"DONE".equals(status) && !"FAILED".equals(status)) {
            e.add("status", "FORMAT");
        }
        String n = VisibleText.reason(note, "note", 1, 500, e);
        e.throwIfAny();
        CmdRow locked = mapper.lockCmdByKey(c.getCmdKey());
        if (!"UNKNOWN".equals(locked.getStatus())) {
            throw new BusinessException(HbErrorCode.EQUIP_CMD_STATE);
        }
        mapper.setCmdStatus(cmdId, status, "RESOLVED", null);
        mapper.insertStep(cmdId, "RESOLVE", "DONE".equals(status) ? "Y" : "N", null, n, "USER", null, null, clock.instant(), "KEY",
                TenantContext.currentActor());
        return withSteps(mapper.findCmd(cmdId));
    }

    @Transactional(readOnly = true)
    public List<CmdRow> list(long equipId, int limit) {
        List<CmdRow> rows = mapper.cmds(equipId, Math.max(1, Math.min(limit, 200)));
        if (rows.isEmpty()) {
            return rows;
        }
        Map<Long, List<StepRow>> steps = mapper.steps(rows.stream().map(CmdRow::getCmdId).toList()).stream()
                .collect(Collectors.groupingBy(StepRow::getCmdId));
        rows.forEach(r -> r.setSteps(steps.getOrDefault(r.getCmdId(), List.of())));
        return rows;
    }

    private CmdRow withSteps(CmdRow c) {
        c.setSteps(mapper.steps(List.of(c.getCmdId())));
        return c;
    }
}
