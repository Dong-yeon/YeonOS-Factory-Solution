package io.smarthappy.module.cms;

import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.notify.NotifyEventDef;
import io.smarthappy.platform.notify.NotifyEventSource;

/** CMS 알림 이벤트 (docs/33 11.14 C12) */
@Component
public class CmsNotifyEvents implements NotifyEventSource {

    /** 중대(CRITICAL) 설비 알람 발생 — 알람 이력 화면을 볼 수 있는 사람에게, 같은 설비·코드는 한 시간에 한 번 */
    public static final String CMS_ALARM_CRITICAL = "CMS_ALARM_CRITICAL";

    @Override
    public List<NotifyEventDef> events() {
        return List.of(new NotifyEventDef(CMS_ALARM_CRITICAL, true, 100));
    }
}
