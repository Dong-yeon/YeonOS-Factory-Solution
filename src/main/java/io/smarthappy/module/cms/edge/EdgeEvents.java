package io.smarthappy.module.cms.edge;

import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.link.AlarmSink;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * 연동 사건 원장 + 알람 (docs/65 16.2·16.7) — 같은 트랜잭션(MANDATORY). 알람은 CMS 계약 {@link AlarmSink}(C8 원장)로.
 * 알람 코드: LINK_LOST · LINK_CONFLICT · PACKML_TRANSITION · CONS_LOW.{소모품} · CONS_EMPTY.{소모품}
 */
@Component
@RequiredArgsConstructor
class EdgeEvents {

    static final String LINK_LOST = "LINK_LOST";
    static final String LINK_CONFLICT = "LINK_CONFLICT";
    static final String PACKML_TRANSITION = "PACKML_TRANSITION";

    private final EdgeMapper mapper;
    private final ObjectProvider<AlarmSink> alarms;

    @Transactional(propagation = Propagation.MANDATORY)
    public void log(Long equipId, Long terminalId, String eventCd, Long seqFrom, Long seqTo, String detail, Long inboundId, Instant at) {
        String d = detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail;
        mapper.insertLog(equipId, terminalId, eventCd, seqFrom, seqTo, d, inboundId, at, TenantContext.currentActor());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void raise(long equipId, String alarmCd, String text, Instant at, Long terminalId, String ref) {
        AlarmSink.of(alarms).raise(equipId, alarmCd, text, at, terminalId, ref);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void clear(long equipId, String alarmCd, Instant at, Long terminalId, String ref) {
        AlarmSink.of(alarms).clear(equipId, alarmCd, at, terminalId, ref);
    }
}
