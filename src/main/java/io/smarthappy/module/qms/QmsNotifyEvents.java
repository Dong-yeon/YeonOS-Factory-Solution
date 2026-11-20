package io.smarthappy.module.qms;

import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.notify.NotifyEventDef;
import io.smarthappy.platform.notify.NotifyEventSource;

/** QMS 알림 이벤트 (docs/29_P3 19장 SV-8) */
@Component
public class QmsNotifyEvents implements NotifyEventSource {

    /** SPC 위반 이벤트 발생 — SPC 화면을 볼 수 있는 사람 중 켠 사람에게(기본 꺼짐 — 데모·시험 소음 없이), 이벤트마다 한 번 */
    public static final String QMS_SPC_EVENT = "QMS_SPC_EVENT";

    @Override
    public List<NotifyEventDef> events() {
        return List.of(new NotifyEventDef(QMS_SPC_EVENT, false, 200));
    }
}
