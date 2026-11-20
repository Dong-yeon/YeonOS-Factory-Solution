package io.smarthappy.module.cms;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsAlarmDtos.DefRow;
import io.smarthappy.module.cms.CmsAlarmDtos.EquipRef;
import io.smarthappy.module.cms.link.AlarmSink;
import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 알람 원장 (C8, docs/33 11.6) — {@link AlarmSink} 구현. MES 수집 트랜잭션 안에서만(MANDATORY).
 *
 * - 발생마다 한 행. 같은 설비·코드는 advisory 잠금으로 줄 세운다(동시 발생 두 개가 자동 신고를 둘 만들지 않게)
 * - 자동 고장 신고(D22): 사용 중 정의가 auto_bm_yn = Y 이고 같은 설비·코드 알람이 만든 신고가 열려 있지 않을 때만, 같은 트랜잭션
 * - 해제는 열린 행 모두를 한 번만, 열린 행이 없으면 아무것도 하지 않는다
 * - 중대(CRITICAL) 정의 알람은 플랫폼 알림(C12, 한 시간에 한 번)
 * - 수집을 막지 않는다: 코드 형식이 틀리거나 설비가 이 회사 것이 아니면 기록하지 않고 경고 로그만(MES 가 이미 거른 값이라 정상 경로는 아님)
 */
@Component
@RequiredArgsConstructor
class CmsAlarmSink implements AlarmSink {

    private static final Logger log = LoggerFactory.getLogger(CmsAlarmSink.class);
    static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_.:-]{0,49}$");
    static final int TEXT_MAX = 200;
    static final String BY = "ALARM";

    private final CmsAlarmMapper mapper;
    private final MwoService mwos;
    private final BizClock clock;
    private final NotificationService notify;

    static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void raise(long equipId, String alarmCd, String alarmText, Instant occurredAt, Long terminalId, String eventId) {
        String cd = code(alarmCd);
        if (cd == null || occurredAt == null) {
            log.warn("alarm raise ignored: bad code or time (equip {}, event {})", equipId, eventId);
            return;
        }
        mapper.lockKey(key(equipId, cd));
        EquipRef e = mapper.findEquip(equipId);
        if (e == null) {
            log.warn("alarm raise ignored: equipment {} not visible (event {})", equipId, eventId);
            return;
        }
        Long mwoId = null;
        DefRow def = mapper.defFor(cd, e.getClassId());
        if (def != null && "Y".equals(def.getAutoBmYn()) && !mapper.hasOpenAutoBm(equipId, cd)) {
            ZoneId z = clock.zone(e.getPlantCd());
            String sym = cd + (alarmText == null ? "" : " " + text(alarmText));
            mwoId = mwos.createFromAlarm(equipId, e.getPlantCd(), e.getClassId(), "알람: " + def.getAlarmNm(), def.getSymptomId(), sym,
                    occurredAt.atZone(z).toOffsetDateTime(), BY);
        }
        mapper.insertLog(equipId, cd, text(alarmText), occurredAt, terminalId, eventId, mwoId, BY);
        if (def != null && "CRITICAL".equals(def.getSeverity())) {
            notifyCritical(e, cd, def.getAlarmNm(), occurredAt);
        }
    }

    /**
     * 중대 알람 알림(C12) — 알람 이력 화면을 볼 수 있는 사람의 알림함으로, 같은 설비·코드는 한 시간(발생 시각 UTC 시)에 한 번(중복 키).
     * 같은 수집 트랜잭션 — 받는 사람이 없거나 구독을 끈 것은 조용히 건너뛴다
     */
    private void notifyCritical(EquipRef e, String cd, String alarmNm, Instant occurredAt) {
        String key = "ALARM:" + e.getEquipId() + ":" + cd + ":" + HOUR.format(occurredAt);
        for (Long uid : notify.searchers(AlarmService.PROG)) {
            notify.send(NotifyMessage.of(CmsNotifyEvents.CMS_ALARM_CRITICAL, uid, key, "notify.cms.alarmCritical", e.getEquipCd(), cd, alarmNm)
                    .withLink(AlarmService.PROG, Map.of("plantCd", e.getPlantCd(), "keyword", e.getEquipCd(), "openOnly", "Y")));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void clear(long equipId, String alarmCd, Instant occurredAt, Long terminalId, String eventId) {
        String cd = code(alarmCd);
        if (cd == null || occurredAt == null) {
            log.warn("alarm clear ignored: bad code or time (equip {}, event {})", equipId, eventId);
            return;
        }
        mapper.lockKey(key(equipId, cd));
        mapper.clearOpen(equipId, cd, occurredAt, eventId);
    }

    private static String key(long equipId, String cd) {
        return "cms_alarm:" + TenantContext.requireCompCd() + ":" + equipId + ":" + cd;
    }

    static String code(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toUpperCase(Locale.ROOT);
        return CODE.matcher(s).matches() ? s : null;
    }

    static String text(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        return s.length() > TEXT_MAX ? s.substring(0, TEXT_MAX) : s;
    }
}
