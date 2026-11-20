package io.smarthappy.module.plm;

import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.notify.NotifyEventDef;
import io.smarthappy.platform.notify.NotifyEventSource;

/** PLM 알림 이벤트 (docs/39 26장) — 받는 사람 = 지시 담당, 기본 켬(본인 지시만 오므로 많지 않다) */
@Component
public class PlmNotifyEvents implements NotifyEventSource {

    /** 결재 승인됐는데 유효일이 지나도록 적용하지 않은 지시 — 지시·유효일마다 한 번 */
    public static final String PLM_ECO_OVERDUE = "PLM_ECO_OVERDUE";
    /** 고객 통지(PCN) 응답 기한 지남 — 통지마다 한 번 */
    public static final String PLM_PCN_OVERDUE = "PLM_PCN_OVERDUE";
    /** 고객 통지(PCN) 응답 기한 임박(오늘 ~ 오늘 + {@link PlmOverdueNotifier#DUE_SOON_DAYS}일) — 통지·기한마다 한 번 (35장) */
    public static final String PLM_PCN_DUE_SOON = "PLM_PCN_DUE_SOON";

    @Override
    public List<NotifyEventDef> events() {
        return List.of(new NotifyEventDef(PLM_ECO_OVERDUE, true, 140), new NotifyEventDef(PLM_PCN_OVERDUE, true, 141),
                new NotifyEventDef(PLM_PCN_DUE_SOON, true, 142));
    }
}
