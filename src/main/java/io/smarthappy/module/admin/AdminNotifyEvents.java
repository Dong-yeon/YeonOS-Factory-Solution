package io.smarthappy.module.admin;

import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.notify.NotifyEventDef;
import io.smarthappy.platform.notify.NotifyEventSource;

/** 시스템관리 알림 이벤트 (docs/36 12.3) */
@Component
public class AdminNotifyEvents implements NotifyEventSource {

    /** 내가 보낸 의견이 처리 완료됨 */
    public static final String FEEDBACK_DONE = "FEEDBACK_DONE";

    @Override
    public List<NotifyEventDef> events() {
        return List.of(new NotifyEventDef(FEEDBACK_DONE, true, 50));
    }
}
